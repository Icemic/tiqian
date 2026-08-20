//! Neon addon boundary for `tiqian-precompute` (ADR 0050). The font session
//! API runs in Rust; this crate only wires the exported names to it.

mod calls;
mod registry;

use neon::prelude::*;

#[neon::main]
fn main(mut cx: ModuleContext) -> NeonResult<()> {
    cx.export_function("backendRevision", calls::backend_revision)?;
    cx.export_function("harfbuzzVersion", calls::harfbuzz_version)?;
    cx.export_function("createFontSession", calls::create_font_session)?;
    cx.export_function("sessionFaces", calls::session_faces)?;
    cx.export_function("shape", calls::shape)?;
    cx.export_function("metrics", calls::metrics)?;
    cx.export_function("renderFamilies", calls::render_families)?;
    cx.export_function("sourceBoundaries", calls::source_boundaries)?;
    cx.export_function("beginCapture", calls::begin_capture)?;
    cx.export_function("captureEvidence", calls::capture_evidence)?;
    cx.export_function("closeSession", calls::close_session)?;
    Ok(())
}
