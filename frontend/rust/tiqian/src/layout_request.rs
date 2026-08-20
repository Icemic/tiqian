//! Packed layout-request encoding (ADR 0050 amendment `EngineLevelAbi`).
//!
//! The Rust side packs, the Kotlin engine reads. The byte layout is documented
//! in `ffi/native/tiqian_layout_abi.h`; this writer is the single Rust-side
//! encoder. All text indices count UTF-16 code units. Callers holding another
//! index space must convert before packing.

/// Versions the request buffer layout and the symbol set, not the engine.
/// Must equal TIQIAN_LAYOUT_ABI_PROTOCOL_REVISION in tiqian_layout_abi.h.
pub const LAYOUT_ABI_PROTOCOL_REVISION: u32 = 1;

/// First four bytes of a request buffer: "TQLR" in little endian. Must equal
/// TIQIAN_LAYOUT_REQUEST_MAGIC in tiqian_layout_abi.h.
pub const LAYOUT_REQUEST_MAGIC: u32 = 0x5451_4C52;

/// Line-break policy codes of the request protocol.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LineBreakPolicyCode {
    ProgressiveTechnical = 0,
}

/// Inline-box outer spacing codes of the request protocol.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum InlineBoxOuterSpacingCode {
    Narrow = 0,
    Source = 1,
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

impl LayoutRequest {
    /// Encodes the request as the little-endian cursor layout of
    /// tiqian_layout_abi.h: a fixed 32-byte header, then sequential sections.
    pub fn pack(&self) -> Vec<u8> {
        let mut writer = RequestWriter::default();
        writer.u32(LAYOUT_REQUEST_MAGIC);
        writer.u32(LAYOUT_ABI_PROTOCOL_REVISION);
        writer.f32(self.max_width_px);
        writer.f32(self.font_size_px);
        writer.f32(self.line_height_px);
        writer.f32(self.first_line_indent_ic);
        writer.i32(self.font_weight);
        writer.boolean(self.italic);
        writer.boolean(self.line_length_grid_enabled);
        writer.u16(0); // reserved
        writer.string(&self.locale);
        writer.u32(self.families.len() as u32);
        for family in &self.families {
            writer.string(family);
        }
        writer.string(&self.text);
        writer.u32(self.text_spans.len() as u32);
        for span in &self.text_spans {
            writer.i32(span.start);
            writer.i32(span.end);
            writer.f32(span.font_size_px);
            writer.i32(span.font_weight);
            writer.boolean(span.italic);
            writer.f32(span.baseline_shift);
            writer.u32(span.families.len() as u32);
            for family in &span.families {
                writer.string(family);
            }
        }
        writer.u32(self.source_boundaries.len() as u32);
        for boundary in &self.source_boundaries {
            writer.i32(*boundary);
        }
        writer.u32(self.line_break_spans.len() as u32);
        for span in &self.line_break_spans {
            writer.i32(span.start);
            writer.i32(span.end);
            writer.i32(span.policy as i32);
        }
        writer.u32(self.inline_boxes.len() as u32);
        for inline_box in &self.inline_boxes {
            writer.i32(inline_box.start);
            writer.i32(inline_box.end);
            writer.f32(inline_box.inline_start);
            writer.f32(inline_box.inline_end);
            writer.i32(inline_box.outer_spacing as i32);
        }
        writer.string(&self.font_session_id);
        writer.bytes
    }
}

#[derive(Default)]
struct RequestWriter {
    bytes: Vec<u8>,
}

impl RequestWriter {
    fn u32(&mut self, value: u32) {
        self.bytes.extend_from_slice(&value.to_le_bytes());
    }

    fn i32(&mut self, value: i32) {
        self.bytes.extend_from_slice(&value.to_le_bytes());
    }

    fn f32(&mut self, value: f32) {
        self.bytes.extend_from_slice(&value.to_le_bytes());
    }

    fn u16(&mut self, value: u16) {
        self.bytes.extend_from_slice(&value.to_le_bytes());
    }

    fn boolean(&mut self, value: bool) {
        self.bytes.push(u8::from(value));
    }

    fn string(&mut self, value: &str) {
        let payload = value.as_bytes();
        self.u32(payload.len() as u32);
        self.bytes.extend_from_slice(payload);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn full_request() -> LayoutRequest {
        LayoutRequest {
            max_width_px: 80.0,
            font_size_px: 16.0,
            line_height_px: 24.0,
            first_line_indent_ic: 2.0,
            font_weight: 400,
            italic: true,
            line_length_grid_enabled: false,
            locale: "zh-Hans".to_string(),
            families: vec!["Fira".to_string()],
            text: "正文".to_string(),
            text_spans: vec![TextSpanSpec {
                start: 0,
                end: 2,
                font_size_px: 16.0,
                font_weight: 400,
                italic: false,
                baseline_shift: 0.0,
                families: vec!["Fira".to_string()],
            }],
            source_boundaries: vec![2],
            line_break_spans: vec![LineBreakSpanSpec {
                start: 0,
                end: 2,
                policy: LineBreakPolicyCode::ProgressiveTechnical,
            }],
            inline_boxes: vec![InlineBoxSpec {
                start: 0,
                end: 1,
                inline_start: 1.0,
                inline_end: 2.0,
                outer_spacing: InlineBoxOuterSpacingCode::Source,
            }],
            font_session_id: "s1".to_string(),
        }
    }

    fn u32_at(buffer: &[u8], offset: usize) -> u32 {
        let mut bytes = [0u8; 4];
        bytes.copy_from_slice(&buffer[offset..offset + 4]);
        u32::from_le_bytes(bytes)
    }

    fn f32_at(buffer: &[u8], offset: usize) -> f32 {
        let mut bytes = [0u8; 4];
        bytes.copy_from_slice(&buffer[offset..offset + 4]);
        f32::from_le_bytes(bytes)
    }

    #[test]
    fn header_fields_sit_at_the_documented_offsets() {
        let buffer = full_request().pack();
        assert_eq!(u32_at(&buffer, 0), LAYOUT_REQUEST_MAGIC);
        assert_eq!(u32_at(&buffer, 4), LAYOUT_ABI_PROTOCOL_REVISION);
        assert_eq!(f32_at(&buffer, 8), 80.0);
        assert_eq!(f32_at(&buffer, 12), 16.0);
        assert_eq!(f32_at(&buffer, 16), 24.0);
        assert_eq!(f32_at(&buffer, 20), 2.0);
        assert_eq!(u32_at(&buffer, 24) as i32, 400);
        assert_eq!(buffer[28], 1);
        assert_eq!(buffer[29], 0);
        assert_eq!(&buffer[30..32], &[0, 0]);
    }

    #[test]
    fn sections_follow_the_documented_order() {
        let buffer = full_request().pack();
        // locale at 32: u32 length then UTF-8 bytes.
        assert_eq!(u32_at(&buffer, 32), 7);
        assert_eq!(&buffer[36..43], b"zh-Hans");
        // families at 43: count, then one length-prefixed string.
        assert_eq!(u32_at(&buffer, 43), 1);
        assert_eq!(u32_at(&buffer, 47), 4);
        assert_eq!(&buffer[51..55], b"Fira");
        // text at 55.
        assert_eq!(u32_at(&buffer, 55), 6);
        assert_eq!(&buffer[59..65], "正文".as_bytes());
        // textSpans at 65: one record, families inline after the scalars.
        assert_eq!(u32_at(&buffer, 65), 1);
        assert_eq!(u32_at(&buffer, 69) as i32, 0);
        assert_eq!(u32_at(&buffer, 73) as i32, 2);
        assert_eq!(u32_at(&buffer, 90), 1);
        assert_eq!(&buffer[98..102], b"Fira");
        // sourceBoundaries at 102, lineBreakSpans at 110, inlineBoxes at 126.
        assert_eq!(u32_at(&buffer, 102), 1);
        assert_eq!(u32_at(&buffer, 106) as i32, 2);
        assert_eq!(u32_at(&buffer, 110), 1);
        assert_eq!(u32_at(&buffer, 122) as i32, LineBreakPolicyCode::ProgressiveTechnical as i32);
        assert_eq!(u32_at(&buffer, 126), 1);
        assert_eq!(f32_at(&buffer, 138), 1.0);
        assert_eq!(f32_at(&buffer, 142), 2.0);
        assert_eq!(u32_at(&buffer, 146) as i32, InlineBoxOuterSpacingCode::Source as i32);
        // fontSessionId at 150, buffer ends right after it.
        assert_eq!(u32_at(&buffer, 150), 2);
        assert_eq!(&buffer[154..156], b"s1");
        assert_eq!(buffer.len(), 156);
    }

    #[test]
    fn empty_sections_cost_one_count_each() {
        let request = LayoutRequest {
            italic: false,
            line_length_grid_enabled: false,
            text_spans: Vec::new(),
            source_boundaries: Vec::new(),
            line_break_spans: Vec::new(),
            inline_boxes: Vec::new(),
            ..full_request()
        };
        let buffer = request.pack();
        // 32 header + 11 locale + 12 families + 10 text + 4 spans
        // + 4 boundaries + 4 line-break spans + 4 boxes + 6 session.
        assert_eq!(buffer.len(), 87);
    }
}
