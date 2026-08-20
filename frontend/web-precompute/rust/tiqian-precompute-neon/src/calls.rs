//! Exported functions. Flat arguments mirror the global backend protocol
//! (`shape(sessionId, displayText, families, ...)`); structured results are
//! JSON strings built by `tiqian_precompute::emit`, the same emitters the
//! parity harness byte-compares against the Kotlin/JS oracle.

use neon::prelude::*;
use neon::types::buffer::TypedArray;

use tiqian_precompute::emit;
use tiqian_precompute::font_record::{FontFaceSpec, FontWeightSpec};
use tiqian_precompute::json::Json;
use tiqian_precompute::session::{
    create_font_session as create_session_impl, MetricsInput, SessionFaceSpec, SessionOptions,
    ShapeInput, BACKEND_REVISION, HARFBUZZ_VERSION,
};
use tiqian_precompute::source_boundaries::{BoundaryStyle, BoundaryTextSpan};

use crate::registry;

pub fn backend_revision(mut cx: FunctionContext) -> JsResult<JsString> {
    Ok(cx.string(BACKEND_REVISION))
}

pub fn harfbuzz_version(mut cx: FunctionContext) -> JsResult<JsString> {
    Ok(cx.string(HARFBUZZ_VERSION))
}

/// One face entry read from the `faces` array, with the font bytes it points
/// at held in the caller's `sources` list.
struct FaceSpecOwned {
    family: String,
    public_url: String,
    source_index: usize,
    face_index: Option<f64>,
    weight: FontWeightSpec,
    style: String,
    unicode_range: Option<String>,
    source_order: Option<f64>,
}

pub fn create_font_session(mut cx: FunctionContext) -> JsResult<JsString> {
    let faces = cx.argument::<JsArray>(0)?;
    let sources = cx.argument::<JsArray>(1)?;
    let options = cx.argument::<JsObject>(2)?;

    // Font bytes are copied out of their buffers up front: the session
    // outlives the call, and the napi borrows end here.
    let mut fonts: Vec<Vec<u8>> = Vec::with_capacity(sources.len(&mut cx) as usize);
    for value in sources.to_vec(&mut cx)? {
        let buffer = value.downcast_or_throw::<JsBuffer, _>(&mut cx)?;
        fonts.push(buffer.as_slice(&cx).to_vec());
    }

    let mut owned: Vec<FaceSpecOwned> = Vec::with_capacity(faces.len(&mut cx) as usize);
    for value in faces.to_vec(&mut cx)? {
        let face = value.downcast_or_throw::<JsObject, _>(&mut cx)?;
        let source_index = face.prop(&mut cx, "font").get::<f64>()? as usize;
        if source_index >= fonts.len() {
            return cx.throw_error(format!("FontSourceOutOfRange:{source_index}"));
        }
        owned.push(FaceSpecOwned {
            family: face.prop(&mut cx, "family").get::<String>()?,
            public_url: face.prop(&mut cx, "publicUrl").get::<String>()?,
            source_index,
            face_index: face.prop(&mut cx, "faceIndex").get::<Option<f64>>()?,
            weight: read_weight_spec(&mut cx, &face)?,
            style: face
                .prop(&mut cx, "style")
                .get::<Option<String>>()?
                .unwrap_or_else(|| "normal".to_string()),
            unicode_range: face.prop(&mut cx, "unicodeRange").get::<Option<String>>()?,
            source_order: face.prop(&mut cx, "sourceOrder").get::<Option<f64>>()?,
        });
    }

    let session_prefix = options
        .prop(&mut cx, "sessionPrefix")
        .get::<Option<String>>()?
        .unwrap_or_else(|| "tq-font".to_string());
    let base_features = match options.prop(&mut cx, "baseFeatures").get::<Option<Handle<JsArray>>>()? {
        Some(array) => Some(read_string_elements(&mut cx, &array)?),
        None => None,
    };

    let specs: Vec<SessionFaceSpec> = owned
        .iter()
        .map(|face| SessionFaceSpec {
            spec: FontFaceSpec {
                family: face.family.as_str(),
                public_url: face.public_url.as_str(),
                source: &fonts[face.source_index],
                face_index: face.face_index,
                weight: face.weight.clone(),
                style: face.style.as_str(),
                unicode_range: face.unicode_range.as_deref(),
                source_order: 0,
            },
            source_order: face.source_order,
        })
        .collect();

    match create_session_impl(specs, SessionOptions { session_prefix, base_features }) {
        Ok(session) => Ok(cx.string(registry::insert(session))),
        Err(error) => cx.throw_error(error.to_string()),
    }
}

fn read_weight_spec(cx: &mut FunctionContext, face: &Handle<JsObject>) -> NeonResult<FontWeightSpec> {
    let value = face.prop(&mut *cx, "weight").get::<Handle<JsValue>>()?;
    if value.is_a::<JsArray, _>(cx) {
        let array = value.downcast_or_throw::<JsArray, _>(cx)?;
        let items = array.to_vec(cx)?;
        if items.len() != 2 {
            return cx.throw_error("InvalidFontFaceWeight");
        }
        let low = items[0].downcast_or_throw::<JsNumber, _>(cx)?.value(cx);
        let high = items[1].downcast_or_throw::<JsNumber, _>(cx)?.value(cx);
        Ok(FontWeightSpec::Range(low, high))
    } else if value.is_a::<JsNumber, _>(cx) {
        let weight = value.downcast_or_throw::<JsNumber, _>(cx)?.value(cx);
        Ok(FontWeightSpec::Single(Some(weight)))
    } else {
        Ok(FontWeightSpec::Single(None))
    }
}

pub fn session_faces(mut cx: FunctionContext) -> JsResult<JsString> {
    let session_id = cx.argument::<JsString>(0)?.value(&mut cx);
    match registry::with_session(&session_id, |session| {
        Json::Arr(session.faces().iter().map(emit::face_info_json).collect())
    }) {
        Ok(json) => Ok(cx.string(json.render())),
        Err(error) => cx.throw_error(error),
    }
}

/// `shape(sessionId, displayText, families, fontSize, fontWeight, italic,
/// locale, role, sourceText)` in the global backend protocol; `families` is
/// pre-joined with U+001F by the JS wrapper.
pub fn shape(mut cx: FunctionContext) -> JsResult<JsString> {
    let session_id = cx.argument::<JsString>(0)?.value(&mut cx);
    let display_text = cx.argument::<JsString>(1)?.value(&mut cx);
    let families = cx.argument::<JsString>(2)?.value(&mut cx);
    let font_size = cx.argument::<JsNumber>(3)?.value(&mut cx);
    let font_weight = cx.argument::<JsNumber>(4)?.value(&mut cx);
    let italic = cx.argument::<JsBoolean>(5)?.value(&mut cx);
    let locale = cx.argument::<JsString>(6)?.value(&mut cx);
    let role = optional_string(&mut cx, 7)?;
    let source_text = optional_string(&mut cx, 8)?;

    let input = ShapeInput {
        display_text: &display_text,
        serialized_families: &families,
        font_size,
        font_weight,
        italic,
        locale: &locale,
        role: role.as_deref(),
        source_text: source_text.as_deref(),
    };
    match registry::with_session(&session_id, |session| session.shape(&input)) {
        Ok(Ok(result)) => Ok(cx.string(emit::shape_result_json(&result).render())),
        Ok(Err(error)) => cx.throw_error(error),
        Err(error) => cx.throw_error(error),
    }
}

pub fn metrics(mut cx: FunctionContext) -> JsResult<JsString> {
    let session_id = cx.argument::<JsString>(0)?.value(&mut cx);
    let families = cx.argument::<JsString>(1)?.value(&mut cx);
    let font_size = cx.argument::<JsNumber>(2)?.value(&mut cx);
    let font_weight = cx.argument::<JsNumber>(3)?.value(&mut cx);
    let italic = cx.argument::<JsBoolean>(4)?.value(&mut cx);
    let role = optional_string(&mut cx, 5)?;
    let face_selection_text = optional_string(&mut cx, 6)?;

    let input = MetricsInput {
        serialized_families: &families,
        font_size,
        font_weight,
        italic,
        role: role.as_deref(),
        face_selection_text: face_selection_text.as_deref(),
    };
    match registry::with_session(&session_id, |session| session.metrics(&input)) {
        Ok(Ok(values)) => {
            Ok(cx.string(Json::Arr(values.iter().map(|value| Json::Num(*value)).collect()).render()))
        }
        Ok(Err(error)) => cx.throw_error(error),
        Err(error) => cx.throw_error(error),
    }
}

pub fn render_families(mut cx: FunctionContext) -> JsResult<JsString> {
    let session_id = cx.argument::<JsString>(0)?.value(&mut cx);
    let requested = cx.argument::<JsArray>(1)?;
    let names = read_string_elements(&mut cx, &requested)?;
    match registry::with_session(&session_id, |session| session.render_families(&names)) {
        Ok(Ok(families)) => Ok(
            cx.string(Json::Arr(families.iter().map(|name| Json::str(name.clone())).collect()).render()),
        ),
        Ok(Err(error)) => cx.throw_error(error),
        Err(error) => cx.throw_error(error),
    }
}

/// `sourceBoundaries(sessionId, text, baseStyle, textSpans)`.
pub fn source_boundaries(mut cx: FunctionContext) -> JsResult<JsString> {
    let session_id = cx.argument::<JsString>(0)?.value(&mut cx);
    let text = cx.argument::<JsString>(1)?.value(&mut cx);
    let base_style = cx.argument::<JsObject>(2)?;
    let spans = cx.argument::<JsArray>(3)?;

    let base = read_boundary_style(&mut cx, &base_style)?;
    let mut parsed: Vec<BoundaryTextSpan> = Vec::with_capacity(spans.len(&mut cx) as usize);
    for value in spans.to_vec(&mut cx)? {
        let span = value.downcast_or_throw::<JsObject, _>(&mut cx)?;
        let style_value = span.prop(&mut cx, "style").get::<Handle<JsValue>>()?;
        let style = style_value.downcast_or_throw::<JsObject, _>(&mut cx)?;
        parsed.push(BoundaryTextSpan {
            start: span.prop(&mut cx, "start").get::<f64>()?,
            end: span.prop(&mut cx, "end").get::<f64>()?,
            style: read_boundary_style(&mut cx, &style)?,
        });
    }

    match registry::with_session(&session_id, |session| {
        session.source_boundaries(&text, &base, &parsed)
    }) {
        Ok(Ok(boundaries)) => Ok(
            cx.string(Json::Arr(boundaries.iter().map(|v| Json::Num(*v)).collect()).render()),
        ),
        Ok(Err(error)) => cx.throw_error(error),
        Err(error) => cx.throw_error(error),
    }
}

fn read_boundary_style(cx: &mut FunctionContext, style: &Handle<JsObject>) -> NeonResult<BoundaryStyle> {
    Ok(BoundaryStyle {
        font_families: read_property_string_array(cx, style, "fontFamilies")?,
        font_size_px: style.prop(&mut *cx, "fontSizePx").get::<f64>()?,
        font_weight: style.prop(&mut *cx, "fontWeight").get::<f64>()?,
        italic: style.prop(&mut *cx, "italic").get::<bool>()?,
        baseline_shift_px: style.prop(&mut *cx, "baselineShiftPx").get::<Option<f64>>()?,
    })
}

pub fn begin_capture(mut cx: FunctionContext) -> JsResult<JsUndefined> {
    let session_id = cx.argument::<JsString>(0)?.value(&mut cx);
    match registry::with_session(&session_id, |session| session.begin_capture()) {
        Ok(()) => Ok(cx.undefined()),
        Err(error) => cx.throw_error(error),
    }
}

pub fn capture_evidence(mut cx: FunctionContext) -> JsResult<JsString> {
    let session_id = cx.argument::<JsString>(0)?.value(&mut cx);
    match registry::with_session(&session_id, |session| emit::evidence_json(&session.capture_evidence())) {
        Ok(json) => Ok(cx.string(json.render())),
        Err(error) => cx.throw_error(error),
    }
}

pub fn close_session(mut cx: FunctionContext) -> JsResult<JsUndefined> {
    let session_id = cx.argument::<JsString>(0)?.value(&mut cx);
    match registry::with_session(&session_id, |session| session.close()) {
        Ok(()) => Ok(cx.undefined()),
        Err(error) => cx.throw_error(error),
    }
}

fn optional_string(cx: &mut FunctionContext, index: usize) -> NeonResult<Option<String>> {
    let Some(value) = cx.argument_opt(index) else {
        return Ok(None);
    };
    if value.is_a::<JsNull, _>(cx) || value.is_a::<JsUndefined, _>(cx) {
        return Ok(None);
    }
    Ok(Some(value.downcast_or_throw::<JsString, _>(cx)?.value(cx)))
}

fn read_string_elements(cx: &mut FunctionContext, array: &Handle<JsArray>) -> NeonResult<Vec<String>> {
    let mut items = Vec::with_capacity(array.len(&mut *cx) as usize);
    for value in array.to_vec(&mut *cx)? {
        items.push(value.downcast_or_throw::<JsString, _>(cx)?.value(cx));
    }
    Ok(items)
}

fn read_property_string_array(
    cx: &mut FunctionContext,
    object: &Handle<JsObject>,
    key: &str,
) -> NeonResult<Vec<String>> {
    let array = object.prop(&mut *cx, key).get::<Handle<JsArray>>()?;
    read_string_elements(cx, &array)
}
