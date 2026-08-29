//! Replay capture port of `precompute-fonts.js` and `snapshot-schema.js`:
//! `instanceId`, `normalizedReplayNumber`, `shapeReplayKey`,
//! `metricReplayKey` (ADR 0050 parity oracle). Replay keys are
//! `JSON.stringify([...])` strings; the Json writer reproduces them
//! byte-for-byte.

use crate::font_record::FontRecord;
use crate::js_compat::js_number_string;
use crate::json::Json;

/// `instanceAxes`: the requested weight names the one instance axis.
pub fn instance_axes(record: &FontRecord, requested_weight: f64) -> Vec<(String, f64)> {
    match record.wght_axis() {
        Some(_) => vec![("wght".to_string(), requested_weight)],
        None => Vec::new(),
    }
}

/// `instanceId`: `${sfntSha256}:${faceIndex}:${axes|default}`.
pub fn instance_id(record: &FontRecord, requested_weight: f64) -> String {
    let axes = instance_axes(record, requested_weight);
    let axes_text = if axes.is_empty() {
        "default".to_string()
    } else {
        axes.iter()
            .map(|(tag, value)| format!("{}={}", tag, js_number_string(*value)))
            .collect::<Vec<_>>()
            .join(",")
    };
    format!(
        "{}:{}:{}",
        record.sfnt_sha256,
        js_number_string(record.face_index),
        axes_text
    )
}

/// `normalizedReplayNumber`: non-finite becomes null; finite values divide
/// by the font size and canonicalize to 12 decimals
/// (FontSizeIndependentReplayCanonicalization). No binary double sits
/// exactly at a 12-decimal tie, so Rust's `format!("{:.12}")` rounds the
/// same way as `Number.prototype.toFixed`, and `-0` maps to `0`.
pub fn normalized_replay_number(value: f64, font_size: f64) -> Option<f64> {
    if !value.is_finite() {
        return None;
    }
    let text = format!("{:.12}", value / font_size);
    // The text comes from Rust's own {:.12} formatter, so parsing it back
    // always succeeds; ok() maps the impossible failure onto the null path.
    let normalized: f64 = text.parse().ok()?;
    Some(if normalized == 0.0 { 0.0 } else { normalized })
}

/// Typed struct for shape replay keys (corrective wave 5, #106).
/// Holds the raw inputs; `.render()` produces the byte-identical JSON
/// string form for cache key compatibility.
pub struct ShapeReplayKey {
    display_text: String,
    serialized_families: String,
    font_weight: f64,
    italic: bool,
    locale: String,
    role: String,
    source_text: String,
}

impl ShapeReplayKey {
    /// Render the key as a JSON.stringify([...]) string, byte-identical
    /// to the previous `shape_replay_key` return value.
    pub fn render(&self) -> String {
        Json::Arr(vec![
            Json::str(&self.display_text),
            Json::str(&self.serialized_families),
            Json::Num(self.font_weight),
            Json::Bool(self.italic),
            Json::str(&self.locale),
            Json::str(&self.role),
            Json::str(&self.source_text),
        ])
        .render()
    }
}

/// `shapeReplayKey`: structured form of the shape replay inputs.
pub fn shape_replay_key(
    display_text: &str,
    font_families: &[String],
    font_weight: f64,
    italic: bool,
    locale: &str,
    role: Option<&str>,
    source_text: &str,
) -> ShapeReplayKey {
    ShapeReplayKey {
        display_text: display_text.to_string(),
        serialized_families: font_families.join("\u{001f}"),
        font_weight,
        italic,
        locale: locale.to_string(),
        role: role.unwrap_or("null").to_string(),
        source_text: source_text.to_string(),
    }
}

/// Typed struct for metric replay keys (corrective wave 5, #106).
/// Holds the raw inputs; `.render()` produces the byte-identical JSON
/// string form for cache key compatibility.
pub struct MetricReplayKey {
    serialized_families: String,
    font_weight: f64,
    italic: bool,
    role: String,
    face_selection_text: String,
}

impl MetricReplayKey {
    /// Render the key as a JSON.stringify([...]) string, byte-identical
    /// to the previous `metric_replay_key` return value.
    pub fn render(&self) -> String {
        Json::Arr(vec![
            Json::str(&self.serialized_families),
            Json::Num(self.font_weight),
            Json::Bool(self.italic),
            Json::str(&self.role),
            Json::str(&self.face_selection_text),
        ])
        .render()
    }
}

/// `metricReplayKey`: structured form of the metric replay inputs.
pub fn metric_replay_key(
    font_families: &[String],
    font_weight: f64,
    italic: bool,
    role: Option<&str>,
    face_selection_text: Option<&str>,
) -> MetricReplayKey {
    MetricReplayKey {
        serialized_families: font_families.join("\u{001f}"),
        font_weight,
        italic,
        role: role.unwrap_or("null").to_string(),
        face_selection_text: face_selection_text.unwrap_or("null").to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn replay_numbers_canonicalize_to_twelve_decimals() {
        assert_eq!(normalized_replay_number(16.0, 16.0), Some(1.0));
        assert_eq!(normalized_replay_number(15.5, 15.5), Some(1.0));
        // 0.1 + 0.2 tails collapse: (3.3000000000000003/3.3) → 1
        let value = 3.3000000000000003f64;
        assert_eq!(normalized_replay_number(value, 3.3), Some(1.0));
        assert_eq!(normalized_replay_number(-0.0, 16.0), Some(0.0));
        assert_eq!(normalized_replay_number(f64::NAN, 16.0), None);
        assert_eq!(normalized_replay_number(f64::INFINITY, 16.0), None);
        // toFixed(12) of a sub-ulp quotient: 1e-13 → "0.000000000000100"
        assert_eq!(normalized_replay_number(1e-13 * 16.0, 16.0), Some(0.0));
    }

    #[test]
    fn instance_ids_name_axes_or_default() {
        let mut record = crate::font_record::FontRecord {
            sfnt: Vec::new(),
            upem: 1000,
            face_index: 0.0,
            source_order: 0,
            family: "F".into(),
            style: "normal",
            weight_range: [400.0, 400.0],
            unicode_range: String::new(),
            unicode_ranges: None,
            public_url: "/f".into(),
            source_sha256: "aa".into(),
            sfnt_sha256: "bb".into(),
            axis_infos: Vec::new(),
            local_names: Vec::new(),
            table_metrics: crate::font_record::TableMetrics {
                typo_ascender: None,
                typo_descender: None,
                base_ideo: None,
                base_idtp: None,
                base_has_variation_index: false,
            },
            face_id: "F".into(),
        };
        assert_eq!(instance_id(&record, 700.0), "bb:0:default");
        record.axis_infos.push(crate::sfnt::AxisInfo {
            tag: "wght".into(),
            min: 100.0,
            default: 400.0,
            max: 900.0,
        });
        assert_eq!(instance_id(&record, 700.0), "bb:0:wght=700");
        assert_eq!(instance_id(&record, 400.5), "bb:0:wght=400.5");
    }

    #[test]
    fn replay_keys_match_json_stringify_forms() {
        assert_eq!(
            shape_replay_key(
                "你好",
                &["A".to_string(), "B".to_string()],
                400.0,
                false,
                "zh-cn",
                Some("CjkText"),
                "你好"
            )
            .render(),
            "[\"你好\",\"A\\u001fB\",400,false,\"zh-cn\",\"CjkText\",\"你好\"]"
        );
        // String(null) is "null"; JSON.stringify writes it as a string.
        assert_eq!(
            shape_replay_key("x", &["A".to_string()], 700.5, true, "en", None, "y").render(),
            "[\"x\",\"A\",700.5,true,\"en\",\"null\",\"y\"]"
        );
        assert_eq!(
            metric_replay_key(
                &["A".to_string(), "B".to_string()],
                400.0,
                false,
                None,
                Some("B")
            )
            .render(),
            "[\"A\\u001fB\",400,false,\"null\",\"B\"]"
        );
        assert_eq!(
            metric_replay_key(&["A".to_string()], 400.0, false, None, None).render(),
            "[\"A\",400,false,\"null\",\"null\"]"
        );
    }
}
