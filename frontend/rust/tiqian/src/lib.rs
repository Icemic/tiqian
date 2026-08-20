//! Rust bindings for the Tiqian layout engine's native precompute C ABI (ADR 0050).
//!
//! Slice A skeleton: this crate fixes the workspace layout and the shared error
//! vocabulary. Static-library linkage, `precompute_paragraph` and
//! `install_font_backend` land with the Rust font session slice.

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
