//! Rust bindings for the Tiqian layout engine's native precompute C ABI (ADR 0050).
//!
//! Font backend protocol types ([`font_backend`]) and the packed shape-buffer
//! encoder ([`shape_buffer`]) are pure Rust and testable without the engine.
//! The `extern` declarations for `tiqian_install_font_backend` and
//! `tiqian_precompute_paragraph` link against the platform crates' static
//! libraries and land with those crates, so this crate keeps building before
//! they exist.

pub mod font_backend;
pub mod shape_buffer;

/// A named issue reported by the engine across the C ABI. Names match the npm
/// test assertions byte for byte (`InvalidMaximumMeasure`, `FontBackendNotInstalled`, ...).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct NamedError(pub String);

impl NamedError {
    pub fn name(&self) -> &str {
        &self.0
    }
}

impl std::fmt::Display for NamedError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for NamedError {}
