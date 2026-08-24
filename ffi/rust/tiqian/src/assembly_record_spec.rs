//! Generated from ffi/schema/assembly-record.schema.json revision 1.
//! Edit the schema and run python3 tools/schema/generate_rust.py.
//!
//! Note: inlineObjects table has rustAbi set to false, so it does not appear in the Rust ABI.

/// Line-break policy codes of the request protocol.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LineBreakPolicyCode {
    ProgressiveTechnical = 0,
}

impl LineBreakPolicyCode {
    /// Wire code of the variant.
    pub fn code(self) -> i32 {
        match self {
            LineBreakPolicyCode::ProgressiveTechnical => 0,
        }
    }
}

/// Inline-box outer spacing codes of the request protocol.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum InlineBoxOuterSpacingCode {
    Narrow = 0,
    Source = 1,
}

impl InlineBoxOuterSpacingCode {
    /// Wire code of the variant.
    pub fn code(self) -> i32 {
        match self {
            InlineBoxOuterSpacingCode::Narrow => 0,
            InlineBoxOuterSpacingCode::Source => 1,
        }
    }
}

/// One styled text span. Ranges count UTF-16 code units.
#[derive(Debug, Clone, PartialEq)]
pub struct TextSpanSpec {
    pub start: i32,
    pub end: i32,
    pub font_size_px: f32,
    pub font_weight: i32,
    pub italic: bool,
    pub baseline_shift: f32,
    pub families: Vec<String>,
}

/// One line-break policy span. Ranges count UTF-16 code units.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct LineBreakSpanSpec {
    pub start: i32,
    pub end: i32,
    pub policy: LineBreakPolicyCode,
}

/// One inline box. Ranges count UTF-16 code units.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct InlineBoxSpec {
    pub start: i32,
    pub end: i32,
    pub inline_start: f32,
    pub inline_end: f32,
    pub outer_spacing: InlineBoxOuterSpacingCode,
}

/// Engine-level layout request. Domain validation (empty paragraph, font
/// ranges, span geometry) belongs to the caller; the engine re-checks the
/// packed structure and reports named protocol errors.
#[derive(Debug, Clone, PartialEq)]
pub struct LayoutRequest {
    pub max_width_px: f32,
    pub font_size_px: f32,
    pub line_height_px: f32,
    pub first_line_indent_ic: f32,
    pub font_weight: i32,
    pub italic: bool,
    pub line_length_grid_enabled: bool,
    pub locale: String,
    pub families: Vec<String>,
    pub text: String,
    pub text_spans: Vec<TextSpanSpec>,
    pub source_boundaries: Vec<i32>,
    pub line_break_spans: Vec<LineBreakSpanSpec>,
    pub inline_boxes: Vec<InlineBoxSpec>,
    pub font_session_id: String,
}
