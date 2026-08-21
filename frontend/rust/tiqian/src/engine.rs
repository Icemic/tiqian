//! Engine entry points over the C ABI (ADR 0050 amendment `EngineLevelAbi`).
//!
//! The `extern` declarations link the Kotlin/Native archive only when build.rs
//! saw `TIQIAN_NATIVE_LIB_DIR` and emitted the `tiqian_engine_link` cfg;
//! without the archive this module is compiled out and the crate stays pure
//! Rust. Buffer protocol and status codes: `ffi/native/tiqian_layout_abi.h`.

use crate::font_backend::{FontBackendVtable, InstallOutcome};
use crate::NamedError;
use std::ffi::{c_char, c_int, CStr};
use std::sync::Once;

extern "C" {
    fn tiqian_layout_paragraph(
        request: *const u8,
        request_len: u64,
        plan_json_out: *mut *mut c_char,
        error_out: *mut *mut c_char,
    ) -> c_int;
    fn tiqian_release_buffer(buffer: *mut c_char);
    fn tiqian_install_font_backend(vtable: *const FontBackendVtable) -> c_int;
    /// Forces the Kotlin/Native runtime to initialize before any engine call.
    /// The name derives from the Gradle module name behind the archive;
    /// `ffi/native` produces libnative.a.
    fn libnative_symbols() -> *const u8;
}

static RUNTIME_INIT: Once = Once::new();

fn ensure_runtime() {
    RUNTIME_INIT.call_once(|| unsafe {
        libnative_symbols();
    });
}

/// Installs the process-wide font backend. The vtable stays owned by the
/// caller and must outlive the process. Reinstalling another vtable of the
/// same revision reports [`InstallOutcome::Installed`] and keeps the first.
pub fn install_font_backend(vtable: &FontBackendVtable) -> InstallOutcome {
    ensure_runtime();
    let code = unsafe { tiqian_install_font_backend(vtable as *const FontBackendVtable) };
    InstallOutcome::from_code(code).expect("install codes are a closed set")
}

/// Runs the layout for one packed request ([`crate::layout_request`]) and
/// returns the engine-produced plan JSON. Named protocol errors and engine
/// failures surface as [`NamedError`]; released buffers never leak across the
/// boundary on either status.
pub fn layout_paragraph(request: &[u8]) -> Result<String, NamedError> {
    ensure_runtime();
    if request.is_empty() {
        return Err(NamedError("InvalidLayoutRequest".to_string()));
    }
    let mut plan: *mut c_char = std::ptr::null_mut();
    let mut error: *mut c_char = std::ptr::null_mut();
    let status = unsafe {
        tiqian_layout_paragraph(
            request.as_ptr(),
            request.len() as u64,
            &mut plan,
            &mut error,
        )
    };
    match status {
        0 => {
            let bytes = unsafe { CStr::from_ptr(plan) }.to_bytes().to_vec();
            unsafe { tiqian_release_buffer(plan) };
            String::from_utf8(bytes)
                .map_err(|_| NamedError("InvalidLayoutResponseUtf8".to_string()))
        }
        1 => {
            let name = unsafe { CStr::from_ptr(error) }
                .to_string_lossy()
                .into_owned();
            unsafe { tiqian_release_buffer(error) };
            Err(NamedError(name))
        }
        code => Err(NamedError(format!("InvalidLayoutResponseStatus{code}"))),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn engine_reports_revision_mismatch_without_fonts() {
        let vtable = FontBackendVtable {
            size: std::mem::size_of::<FontBackendVtable>() as u32,
            protocol_revision: 0,
            shape: None,
            metrics: None,
            release_string: None,
        };
        assert_eq!(
            install_font_backend(&vtable),
            InstallOutcome::RevisionMismatch
        );
    }

    #[test]
    fn garbage_request_reports_magic_error_through_the_abi() {
        let failure = layout_paragraph(&[0u8; 8]).expect_err("garbage must fail");
        assert_eq!(failure.name(), "InvalidLayoutRequestMagic");
    }

    #[test]
    fn empty_request_is_rejected_before_the_call() {
        assert_eq!(
            layout_paragraph(&[]),
            Err(NamedError("InvalidLayoutRequest".to_string()))
        );
    }
}
