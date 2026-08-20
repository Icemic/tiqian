//! Font session and precompute orchestration in Rust (ADR 0050).
//!
//! The Kotlin/JS implementation stays the parity oracle; the font session
//! (HarfBuzz, WOFF2, face selection), the wire orchestration and the two cache
//! lanes land slice by slice.

pub mod base_table;
pub mod emit;
pub mod font_face;
pub mod font_record;
pub mod font_source;
pub mod json;
pub mod js_compat;
pub mod metrics;
pub mod name_language;
pub mod name_table;
pub mod policy;
pub mod replay;
pub mod selection;
pub mod session;
pub mod sfnt;
pub mod shaping;
pub mod source_boundaries;
