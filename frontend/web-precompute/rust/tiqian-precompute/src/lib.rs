//! Font session and precompute orchestration in Rust (ADR 0050).
//!
//! The Kotlin/JS implementation stays the parity oracle; the font session
//! (HarfBuzz, WOFF2, face selection), the wire orchestration and the two cache
//! lanes land slice by slice.

pub mod base_table;
pub mod emit;
#[cfg(tiqian_engine_link)]
pub mod engine_bridge;
pub mod font_contract;
pub mod font_face;
pub mod font_record;
pub mod font_source;
pub mod json;
pub mod js_compat;
pub mod metrics;
pub mod name_language;
pub mod name_table;
pub mod normalize;
pub mod paragraph;
pub mod plan;
pub mod policy;
pub mod precomputer;
pub mod prepared_dom;
pub mod replay;
pub mod selection;
pub mod schema;
pub mod session;
pub mod sfnt;
pub mod shaping;
pub mod snapshot_bundle;
pub mod snapshot_manifest;
pub mod snapshot_source;
pub mod source_boundaries;
pub mod unicode_tables;
