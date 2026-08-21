//! Session-backed font backend for the engine ABI (ADR 0050 Slice C).
//!
//! The engine consumes fonts exclusively through the vtable protocol. This
//! module presents a [`FontSession`] as that backend and runs one paragraph
//! request through `tiqian_layout_paragraph`. The vtable callbacks are free
//! functions, so the active session travels in a thread local; the engine
//! call is synchronous and the borrow ends when the call returns. The session
//! id the engine passes back is the id of the lent session and needs no
//! second lookup.

use std::cell::RefCell;
use std::ffi::{c_char, CStr, CString};
use std::sync::Once;

use tiqian::font_backend::{FontBackendVtable, InstallOutcome, FONT_BACKEND_PROTOCOL_REVISION};
use tiqian::shape_buffer::{
    required_shape_buffer_size, write_shape_buffer, ShapeEvidence, ShapeGlyphRecord,
};

use crate::paragraph::ParagraphRequest;
use crate::session::{FontSession, MetricsInput, ShapeInput};

thread_local! {
    static CURRENT_SESSION: RefCell<Option<*mut FontSession>> =
        const { RefCell::new(None) };
}

/// Clears the thread local when the engine call ends, including the panic
/// path.
struct SessionSlot;

impl SessionSlot {
    fn set(session: &mut FontSession) -> Self {
        CURRENT_SESSION.with_borrow_mut(|slot| *slot = Some(session as *mut FontSession));
        SessionSlot
    }
}

impl Drop for SessionSlot {
    fn drop(&mut self) {
        CURRENT_SESSION.with_borrow_mut(|slot| *slot = None);
    }
}

/// Runs one paragraph request through the engine with `session` as the font
/// backend. The session stays borrowed for the duration of the call; nested
/// engine calls on the same thread are not supported. Errors are the named
/// validation issues of the request and the engine.
pub fn precompute_paragraph(
    session: &mut FontSession,
    request: &ParagraphRequest,
) -> Result<String, String> {
    install_session_backend();
    let packed = request.to_layout_request().map_err(|error| error.0)?.pack();
    let _slot = SessionSlot::set(session);
    tiqian::engine::layout_paragraph(&packed).map_err(|error| error.0)
}

fn install_session_backend() {
    static INSTALL: Once = Once::new();
    INSTALL.call_once(|| {
        static VTABLE: FontBackendVtable = FontBackendVtable {
            size: std::mem::size_of::<FontBackendVtable>() as u32,
            protocol_revision: FONT_BACKEND_PROTOCOL_REVISION,
            shape: Some(session_shape),
            metrics: Some(session_metrics),
            release_string: Some(session_release_string),
        };
        assert_eq!(
            tiqian::engine::install_font_backend(&VTABLE),
            InstallOutcome::Installed
        );
    });
}

/// The lent session of the running engine call. The pointer is valid because
/// [`precompute_paragraph`] holds the borrow for the whole call.
fn with_current_session<T>(call: impl FnOnce(&mut FontSession) -> T) -> Option<T> {
    let pointer = CURRENT_SESSION.with_borrow(|slot| *slot)?;
    // SAFETY: the slot is set only inside `precompute_paragraph`, which holds
    // `&mut FontSession` across the engine call, and the engine invokes
    // callbacks on the same call stack.
    Some(call(unsafe { &mut *pointer }))
}

/// Reads a C string argument; null maps to `None`, undecodable bytes map to
/// the empty string.
unsafe fn c_str<'a>(pointer: *const c_char) -> Option<&'a str> {
    if pointer.is_null() {
        return None;
    }
    Some(unsafe { CStr::from_ptr(pointer) }.to_str().unwrap_or(""))
}

/// Error strings cross the boundary as C strings the engine releases through
/// `release_string`; both ends are this module, so the Rust allocator serves
/// the pair.
fn set_error(error_out: *mut *mut c_char, message: &str) {
    if error_out.is_null() {
        return;
    }
    let cstring = CString::new(message.replace('\0', " "))
        .unwrap_or_else(|_| CString::new("FontBackendError").expect("static message encodes"));
    unsafe { *error_out = cstring.into_raw() };
}

unsafe extern "C" fn session_shape(
    _session_id: *const c_char,
    display_text: *const c_char,
    serialized_families: *const c_char,
    font_size: f64,
    font_weight: i32,
    italic: i32,
    locale: *const c_char,
    role: *const c_char,
    source_text: *const c_char,
    buffer: *mut u8,
    capacity: u64,
    error_out: *mut *mut c_char,
) -> i64 {
    let Some(display_text) = (unsafe { c_str(display_text) }) else {
        set_error(error_out, "FontBackendMissingDisplayText");
        return -1;
    };
    let input = ShapeInput {
        display_text,
        serialized_families: unsafe { c_str(serialized_families) }.unwrap_or(""),
        font_size,
        font_weight: font_weight as f64,
        italic: italic != 0,
        locale: unsafe { c_str(locale) }.unwrap_or(""),
        role: unsafe { c_str(role) },
        source_text: unsafe { c_str(source_text) },
    };
    let Some(record) = with_current_session(|session| session.shape(&input)) else {
        set_error(error_out, "FontBackendSessionMissing");
        return -1;
    };
    let record = match record {
        Ok(record) => record,
        Err(message) => {
            set_error(error_out, &message);
            return -1;
        }
    };
    let glyphs: Vec<ShapeGlyphRecord> = record
        .glyphs
        .iter()
        .map(|glyph| ShapeGlyphRecord {
            id: glyph.id,
            advance: glyph.advance,
            x: glyph.x,
            y: glyph.y,
            bounds: glyph.bounds,
        })
        .collect();
    let evidence = ShapeEvidence {
        face_id: record.face_id,
        instance_id: record.font_instance_id,
        script: record.script,
        features: record.features,
        total_advance: record.advance,
        unsafe_break_count: record.unsafe_break_count as u32,
    };
    let needed = required_shape_buffer_size(glyphs.len(), &evidence);
    if buffer.is_null() || (capacity as usize) < needed {
        return needed as i64;
    }
    // SAFETY: the engine passes `capacity` live bytes at `buffer`.
    let out = unsafe { std::slice::from_raw_parts_mut(buffer, needed) };
    write_shape_buffer(out, &glyphs, &evidence);
    needed as i64
}

unsafe extern "C" fn session_metrics(
    _session_id: *const c_char,
    serialized_families: *const c_char,
    font_size: f64,
    font_weight: i32,
    italic: i32,
    role: *const c_char,
    face_selection_text: *const c_char,
    out_metrics: *mut f64,
    error_out: *mut *mut c_char,
) -> i64 {
    if out_metrics.is_null() {
        return -1;
    }
    let input = MetricsInput {
        serialized_families: unsafe { c_str(serialized_families) }.unwrap_or(""),
        font_size,
        font_weight: font_weight as f64,
        italic: italic != 0,
        role: unsafe { c_str(role) },
        face_selection_text: unsafe { c_str(face_selection_text) },
    };
    let Some(values) = with_current_session(|session| session.metrics(&input)) else {
        set_error(error_out, "FontBackendSessionMissing");
        return -1;
    };
    let values = match values {
        Ok(values) => values,
        Err(message) => {
            set_error(error_out, &message);
            return -1;
        }
    };
    for (index, value) in values.iter().enumerate() {
        // SAFETY: the engine passes five live doubles at `out_metrics`.
        unsafe { *out_metrics.add(index) = *value };
    }
    0
}

unsafe extern "C" fn session_release_string(string: *const c_char) {
    if string.is_null() {
        return;
    }
    // SAFETY: the pointer came from `CString::into_raw` in `set_error`.
    drop(unsafe { CString::from_raw(string as *mut c_char) });
}
