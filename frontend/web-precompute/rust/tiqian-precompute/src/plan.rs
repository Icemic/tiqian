//! Plan JSON deserialization (ADR 0050 amendment `PrecomputeInRust`).
//!
//! `toPreparedParagraphJson` in the Kotlin layout module is the single plan
//! producer. This module only reads the bytes back for Rust consumers; it
//! emits nothing. Field names and value shapes mirror the Kotlin emitter
//! one to one.

use crate::js_compat::{trunc_sat_i32, trunc_sat_i64};
use crate::json::{parse_json, Json};
use tiqian::NamedError;

/// `schema` of every plan this revision understands.
pub const PLAN_SCHEMA: i64 = 1;

/// `layoutRevision` of every plan this revision understands.
pub const PLAN_LAYOUT_REVISION: &str = "tiqian-layout-v2";

#[derive(Debug, Clone, PartialEq)]
pub struct Plan {
    pub width: f64,
    pub height: f64,
    pub lines: Vec<PlanLine>,
    /// Emphasis decoration source ranges (`[start, end]` pairs) from
    /// `appendParagraphRenderEvidence`; absent plans keep an empty list so the
    /// Latin-in-emphasis italic effect never fires.
    pub emphasis_ranges: Vec<(f64, f64)>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct PlanLine {
    pub range_start: i32,
    pub range_end: i32,
    pub top: f64,
    pub bottom: f64,
    pub baseline: f64,
    pub indent: f64,
    pub visual_width: f64,
    pub hyphen_advance: f64,
    pub end_reason: PlanEndReason,
    pub cells: Vec<PlanCell>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PlanEndReason {
    AutoWrap,
    MandatoryBreak,
    ParagraphEnd,
}

#[derive(Debug, Clone, PartialEq)]
pub struct PlanCell {
    pub range_start: i32,
    pub range_end: i32,
    pub source: String,
    pub display: String,
    pub draw_x: f64,
    pub natural_width: f64,
    pub leading_layout_advance: f64,
    /// Present only on multi-code-unit clusters; absent means false.
    pub shaping_boundary: bool,
    /// Present only when shaping applied OpenType features by policy.
    pub open_type_features: Vec<String>,
    /// `renderFontFamily`: the evidence render face; the DOM lowerer projects
    /// it onto the run and replays it in the dash block.
    pub render_font_family: Option<String>,
    /// `dashStrategy` with its siblings, all only when a dash shaping decision
    /// was recorded.
    pub dash_strategy: Option<String>,
    pub shaping_language: Option<String>,
    pub resolved_face: Option<String>,
    pub glyph_ids: Option<String>,
    pub shaping_evidence: Option<String>,
    /// `punctuationInkFloor` / `punctuationBodyWidth`, only when ink
    /// containment applied.
    pub punctuation_ink_floor: Option<f64>,
    pub punctuation_body_width: Option<f64>,
    /// `latin`: the cluster shaped under a Latin font role.
    pub latin: bool,
    /// `style`: the paint-relevant style delta object, kept as parsed so the
    /// renderer replays its members and merge signatures the way js reads
    /// `cell.style`; absent or JSON null both mean no delta.
    pub style_delta: Option<Json>,
}

impl Plan {
    /// Deserializes one plan document. Schema and layout revision must match
    /// this revision's constants; failures carry named issues so callers can
    /// surface them the way engine errors surface.
    pub fn from_json_str(text: &str) -> Result<Plan, NamedError> {
        let value =
            parse_json(text).map_err(|error| NamedError(format!("InvalidPlanJson:{error}")))?;
        let fields = match value {
            Json::Obj(fields) => fields,
            _ => return Err(NamedError("InvalidPlanJson:not an object".to_string())),
        };
        let schema = number_field(&fields, "schema")
            .map_err(|_| NamedError("InvalidPlanSchema".to_string()))?;
        if trunc_sat_i64(schema) != PLAN_SCHEMA {
            return Err(NamedError("InvalidPlanSchema".to_string()));
        }
        let revision = string_field(&fields, "layoutRevision")
            .map_err(|_| NamedError("InvalidPlanLayoutRevision".to_string()))?;
        if revision != PLAN_LAYOUT_REVISION {
            return Err(NamedError("InvalidPlanLayoutRevision".to_string()));
        }
        // The js plan reader treats `width` as optional and no render path
        // reads it; hand-built plans without it must lower the same way.
        let width = match find(&fields, "width") {
            Some(_) => number_field(&fields, "width").map_err(field_error("width"))?,
            None => 0.0,
        };
        let height = number_field(&fields, "height").map_err(field_error("height"))?;
        let lines = array_field(&fields, "lines").map_err(field_error("lines"))?;
        let lines = lines
            .iter()
            .map(|line| PlanLine::from_json(line))
            .collect::<Result<Vec<_>, _>>()?;
        let emphasis_ranges = match find(&fields, "emphasisRanges") {
            Some(Json::Arr(items)) => items
                .iter()
                .map(|item| emphasis_range(item))
                .collect::<Result<Vec<_>, _>>()?,
            Some(_) => {
                return Err(NamedError(
                    "InvalidPlanJsonField:emphasisRanges".to_string(),
                ))
            }
            None => Vec::new(),
        };
        Ok(Plan {
            width,
            height,
            lines,
            emphasis_ranges,
        })
    }
}

impl PlanLine {
    fn from_json(value: &Json) -> Result<PlanLine, NamedError> {
        let fields = object_value(value, "line")?;
        let end_reason = string_field(&fields, "endReason").map_err(field_error("endReason"))?;
        let cells = array_field(&fields, "cells").map_err(field_error("cells"))?;
        let cells = cells
            .iter()
            .map(|cell| PlanCell::from_json(cell))
            .collect::<Result<Vec<_>, _>>()?;
        Ok(PlanLine {
            range_start: integer_field(&fields, "rangeStart").map_err(field_error("rangeStart"))?,
            range_end: integer_field(&fields, "rangeEnd").map_err(field_error("rangeEnd"))?,
            top: number_field(&fields, "top").map_err(field_error("top"))?,
            bottom: number_field(&fields, "bottom").map_err(field_error("bottom"))?,
            baseline: number_field(&fields, "baseline").map_err(field_error("baseline"))?,
            indent: number_field(&fields, "indent").map_err(field_error("indent"))?,
            visual_width: number_field(&fields, "visualWidth")
                .map_err(field_error("visualWidth"))?,
            hyphen_advance: number_field(&fields, "hyphenAdvance")
                .map_err(field_error("hyphenAdvance"))?,
            end_reason: match end_reason.as_str() {
                "AutoWrap" => PlanEndReason::AutoWrap,
                "MandatoryBreak" => PlanEndReason::MandatoryBreak,
                "ParagraphEnd" => PlanEndReason::ParagraphEnd,
                _ => return Err(NamedError("InvalidPlanEndReason".to_string())),
            },
            cells,
        })
    }
}

impl PlanCell {
    fn from_json(value: &Json) -> Result<PlanCell, NamedError> {
        let fields = object_value(value, "cell")?;
        let open_type_features = match find(&fields, "openTypeFeatures") {
            Some(Json::Arr(items)) => items
                .iter()
                .map(|item| match item {
                    Json::Str(text) => Ok(text.clone()),
                    _ => Err(NamedError(
                        "InvalidPlanJsonField:openTypeFeatures".to_string(),
                    )),
                })
                .collect::<Result<Vec<_>, _>>()?,
            Some(_) => {
                return Err(NamedError(
                    "InvalidPlanJsonField:openTypeFeatures".to_string(),
                ))
            }
            None => Vec::new(),
        };
        let style_delta = match find(&fields, "style") {
            Some(Json::Null) | None => None,
            Some(value @ Json::Obj(_)) => {
                validate_style(value)?;
                Some(value.clone())
            }
            Some(_) => return Err(NamedError("InvalidPlanJsonField:style".to_string())),
        };
        Ok(PlanCell {
            range_start: integer_field(&fields, "rangeStart").map_err(field_error("rangeStart"))?,
            range_end: integer_field(&fields, "rangeEnd").map_err(field_error("rangeEnd"))?,
            source: string_field(&fields, "source").map_err(field_error("source"))?,
            display: string_field(&fields, "display").map_err(field_error("display"))?,
            draw_x: number_field(&fields, "drawX").map_err(field_error("drawX"))?,
            natural_width: number_field(&fields, "naturalWidth")
                .map_err(field_error("naturalWidth"))?,
            leading_layout_advance: number_field(&fields, "leadingLayoutAdvance")
                .map_err(field_error("leadingLayoutAdvance"))?,
            shaping_boundary: match find(&fields, "shapingBoundary") {
                Some(Json::Bool(value)) => *value,
                Some(_) => {
                    return Err(NamedError(
                        "InvalidPlanJsonField:shapingBoundary".to_string(),
                    ))
                }
                None => false,
            },
            open_type_features,
            render_font_family: optional_string_field(&fields, "renderFontFamily")?,
            dash_strategy: optional_string_field(&fields, "dashStrategy")?,
            shaping_language: optional_string_field(&fields, "shapingLanguage")?,
            resolved_face: optional_string_field(&fields, "resolvedFace")?,
            glyph_ids: optional_string_field(&fields, "glyphIds")?,
            shaping_evidence: optional_string_field(&fields, "shapingEvidence")?,
            punctuation_ink_floor: optional_number_field(&fields, "punctuationInkFloor")?,
            punctuation_body_width: optional_number_field(&fields, "punctuationBodyWidth")?,
            latin: optional_bool_field(&fields, "latin")?.unwrap_or(false),
            style_delta,
        })
    }
}

fn find<'a>(fields: &'a [(String, Json)], key: &str) -> Option<&'a Json> {
    fields
        .iter()
        .find(|(name, _)| name == key)
        .map(|(_, value)| value)
}

fn field_error(field: &str) -> impl Fn(NamedError) -> NamedError {
    let field = field.to_string();
    move |_| NamedError(format!("InvalidPlanJsonField:{field}"))
}

fn object_value<'a>(value: &'a Json, what: &str) -> Result<&'a [(String, Json)], NamedError> {
    match value {
        Json::Obj(fields) => Ok(fields),
        _ => Err(NamedError(format!("InvalidPlanJsonField:{what}"))),
    }
}

fn number_field(fields: &[(String, Json)], key: &str) -> Result<f64, NamedError> {
    match find(fields, key) {
        Some(Json::Num(value)) => Ok(*value),
        _ => Err(NamedError(format!("InvalidPlanJsonField:{key}"))),
    }
}

fn integer_field(fields: &[(String, Json)], key: &str) -> Result<i32, NamedError> {
    let value = number_field(fields, key)?;
    if value.fract() != 0.0 || !(f64::from(i32::MIN)..=f64::from(i32::MAX)).contains(&value) {
        return Err(NamedError(format!("InvalidPlanJsonField:{key}")));
    }
    Ok(trunc_sat_i32(value))
}

fn string_field(fields: &[(String, Json)], key: &str) -> Result<String, NamedError> {
    match find(fields, key) {
        Some(Json::Str(value)) => Ok(value.clone()),
        _ => Err(NamedError(format!("InvalidPlanJsonField:{key}"))),
    }
}

/// Optional evidence fields mirror the js `?? null` reads: absent and JSON
/// null both read as none, a present value of the wrong type is damage.
fn optional_string_field(
    fields: &[(String, Json)],
    key: &str,
) -> Result<Option<String>, NamedError> {
    match find(fields, key) {
        Some(Json::Null) | None => Ok(None),
        Some(Json::Str(value)) => Ok(Some(value.clone())),
        Some(_) => Err(NamedError(format!("InvalidPlanJsonField:{key}"))),
    }
}

fn optional_number_field(fields: &[(String, Json)], key: &str) -> Result<Option<f64>, NamedError> {
    match find(fields, key) {
        Some(Json::Null) | None => Ok(None),
        Some(Json::Num(value)) => Ok(Some(*value)),
        Some(_) => Err(NamedError(format!("InvalidPlanJsonField:{key}"))),
    }
}

fn optional_bool_field(fields: &[(String, Json)], key: &str) -> Result<Option<bool>, NamedError> {
    match find(fields, key) {
        Some(Json::Null) | None => Ok(None),
        Some(Json::Bool(value)) => Ok(Some(*value)),
        Some(_) => Err(NamedError(format!("InvalidPlanJsonField:{key}"))),
    }
}

/// One `[start, end]` emphasis range; js `Number(range[0])` reads the members
/// as numbers, and the engine emits integers.
fn emphasis_range(value: &Json) -> Result<(f64, f64), NamedError> {
    let Json::Arr(pair) = value else {
        return Err(NamedError(
            "InvalidPlanJsonField:emphasisRanges".to_string(),
        ));
    };
    if pair.len() != 2 {
        return Err(NamedError(
            "InvalidPlanJsonField:emphasisRanges".to_string(),
        ));
    }
    let (Json::Num(start), Json::Num(end)) = (&pair[0], &pair[1]) else {
        return Err(NamedError(
            "InvalidPlanJsonField:emphasisRanges".to_string(),
        ));
    };
    Ok((*start, *end))
}

/// `style` members carry the paint deltas `PreparedParagraph.kt` emits:
/// `fontSize` number, `fontWeight` number, `italic` boolean. JSON null reads
/// as absent like the renderer's `?.` access; unknown members stay untouched
/// so the merge signature keeps them the way `JSON.stringify` does.
fn validate_style(value: &Json) -> Result<(), NamedError> {
    let Json::Obj(fields) = value else {
        return Err(NamedError("InvalidPlanJsonField:style".to_string()));
    };
    for (name, member) in fields {
        let valid = match name.as_str() {
            "fontSize" | "fontWeight" => matches!(member, Json::Num(_) | Json::Null),
            "italic" => matches!(member, Json::Bool(_) | Json::Null),
            _ => true,
        };
        if !valid {
            return Err(NamedError(format!("InvalidPlanJsonField:style.{name}")));
        }
    }
    Ok(())
}

fn array_field<'a>(fields: &'a [(String, Json)], key: &str) -> Result<&'a [Json], NamedError> {
    match find(fields, key) {
        Some(Json::Arr(items)) => Ok(items),
        _ => Err(NamedError(format!("InvalidPlanJsonField:{key}"))),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn two_line_plan() -> String {
        "{\"schema\":1,\"layoutRevision\":\"tiqian-layout-v2\",\"width\":80.0,\"height\":48.0,\
\"lines\":[\
{\"rangeStart\":0,\"rangeEnd\":5,\"top\":0.0,\"bottom\":24.0,\"baseline\":19.0,\"indent\":32.0,\
\"visualWidth\":80.0,\"hyphenAdvance\":0.0,\"endReason\":\"AutoWrap\",\"cells\":[\
{\"rangeStart\":0,\"rangeEnd\":1,\"source\":\"字\",\"display\":\"字\",\"drawX\":0.0,\
\"naturalWidth\":16.0,\"leadingLayoutAdvance\":16.0},\
{\"rangeStart\":4,\"rangeEnd\":5,\"source\":\"字\",\"display\":\"字\",\"drawX\":64.0,\
\"naturalWidth\":16.0,\"leadingLayoutAdvance\":16.0,\"shapingBoundary\":true,\
\"openTypeFeatures\":[\"fwid\"]}]}\
,{\"rangeStart\":5,\"rangeEnd\":10,\"top\":24.0,\"bottom\":48.0,\"baseline\":43.0,\"indent\":0.0,\
\"visualWidth\":80.0,\"hyphenAdvance\":0.0,\"endReason\":\"ParagraphEnd\",\"cells\":[]}]}"
            .to_string()
    }

    #[test]
    fn reads_every_field_the_emitter_writes() {
        let plan = Plan::from_json_str(&two_line_plan()).unwrap();
        assert_eq!(plan.width, 80.0);
        assert_eq!(plan.height, 48.0);
        assert_eq!(plan.lines.len(), 2);
        let first = &plan.lines[0];
        assert_eq!((first.range_start, first.range_end), (0, 5));
        assert_eq!(first.indent, 32.0);
        assert_eq!(first.end_reason, PlanEndReason::AutoWrap);
        assert_eq!(first.cells[0].source, "字");
        assert!(!first.cells[0].shaping_boundary);
        assert!(first.cells[0].open_type_features.is_empty());
        assert!(first.cells[1].shaping_boundary);
        assert_eq!(first.cells[1].open_type_features, vec!["fwid".to_string()]);
        assert_eq!(plan.lines[1].end_reason, PlanEndReason::ParagraphEnd);
    }

    #[test]
    fn rejects_schema_and_revision_damage() {
        let plan = two_line_plan();
        assert_eq!(
            Plan::from_json_str(&plan.replace("\"schema\":1", "\"schema\":2"))
                .unwrap_err()
                .name(),
            "InvalidPlanSchema"
        );
        assert_eq!(
            Plan::from_json_str(&plan.replace("tiqian-layout-v2", "other"))
                .unwrap_err()
                .name(),
            "InvalidPlanLayoutRevision"
        );
    }

    #[test]
    fn rejects_structural_damage_with_field_names() {
        let plan = two_line_plan();
        let error =
            Plan::from_json_str(&plan.replace("\"width\":80.0", "\"width\":\"80\"")).unwrap_err();
        assert_eq!(error.name(), "InvalidPlanJsonField:width");
        let error = Plan::from_json_str(
            &plan.replace("\"endReason\":\"AutoWrap\"", "\"endReason\":\"New\""),
        )
        .unwrap_err();
        assert_eq!(error.name(), "InvalidPlanEndReason");
        let error = Plan::from_json_str("{").unwrap_err();
        assert!(error.name().starts_with("InvalidPlanJson"));
    }

    #[test]
    fn reads_plans_without_width() {
        let plan = Plan::from_json_str(&two_line_plan().replace("\"width\":80.0,", "")).unwrap();
        assert_eq!(plan.width, 0.0);
        assert_eq!(plan.height, 48.0);
        assert_eq!(plan.lines.len(), 2);
    }

    fn evidence_plan() -> String {
        "{\"schema\":1,\"layoutRevision\":\"tiqian-layout-v2\",\"width\":80.0,\"height\":27.0,\
\"emphasisRanges\":[[0,1]],\
\"lines\":[\
{\"rangeStart\":0,\"rangeEnd\":1,\"top\":0.0,\"bottom\":27.0,\"baseline\":20.0,\"indent\":0.0,\
\"visualWidth\":18.0,\"hyphenAdvance\":0.0,\"endReason\":\"ParagraphEnd\",\"cells\":[\
{\"rangeStart\":0,\"rangeEnd\":1,\"source\":\"—\",\"display\":\"—\",\"drawX\":0.0,\
\"naturalWidth\":18.0,\"leadingLayoutAdvance\":0.0,\"dashStrategy\":\"ReplaceEmDash\",\
\"shapingLanguage\":\"zh-Hans\",\"resolvedFace\":\"FaceA\",\"glyphIds\":\"71,72\",\
\"shapingEvidence\":\"ShapingReason\",\"renderFontFamily\":\"Han Face\",\
\"punctuationInkFloor\":2.5,\"punctuationBodyWidth\":16.0,\"latin\":true,\
\"style\":{\"fontSize\":12.0,\"fontWeight\":700,\"italic\":true}}]}]}"
            .to_string()
    }

    #[test]
    fn reads_evidence_fields_when_present() {
        let plan = Plan::from_json_str(&evidence_plan()).unwrap();
        assert_eq!(plan.emphasis_ranges, vec![(0.0, 1.0)]);
        let cell = &plan.lines[0].cells[0];
        assert_eq!(cell.dash_strategy.as_deref(), Some("ReplaceEmDash"));
        assert_eq!(cell.shaping_language.as_deref(), Some("zh-Hans"));
        assert_eq!(cell.resolved_face.as_deref(), Some("FaceA"));
        assert_eq!(cell.glyph_ids.as_deref(), Some("71,72"));
        assert_eq!(cell.shaping_evidence.as_deref(), Some("ShapingReason"));
        assert_eq!(cell.render_font_family.as_deref(), Some("Han Face"));
        assert_eq!(cell.punctuation_ink_floor, Some(2.5));
        assert_eq!(cell.punctuation_body_width, Some(16.0));
        assert!(cell.latin);
        let style = cell.style_delta.as_ref().unwrap();
        assert_eq!(
            style.render(),
            "{\"fontSize\":12,\"fontWeight\":700,\"italic\":true}"
        );
    }

    #[test]
    fn evidence_absent_keeps_today_s_defaults() {
        let plan = Plan::from_json_str(&two_line_plan()).unwrap();
        assert!(plan.emphasis_ranges.is_empty());
        for cell in plan.lines.iter().flat_map(|line| line.cells.iter()) {
            assert!(cell.render_font_family.is_none());
            assert!(cell.dash_strategy.is_none());
            assert!(cell.shaping_language.is_none());
            assert!(cell.resolved_face.is_none());
            assert!(cell.glyph_ids.is_none());
            assert!(cell.shaping_evidence.is_none());
            assert!(cell.punctuation_ink_floor.is_none());
            assert!(cell.punctuation_body_width.is_none());
            assert!(!cell.latin);
            assert!(cell.style_delta.is_none());
        }
    }

    #[test]
    fn rejects_evidence_field_damage() {
        let plan = evidence_plan();
        let error = Plan::from_json_str(
            &plan.replace("\"dashStrategy\":\"ReplaceEmDash\"", "\"dashStrategy\":7"),
        )
        .unwrap_err();
        assert_eq!(error.name(), "InvalidPlanJsonField:dashStrategy");
        let error = Plan::from_json_str(&plan.replace(
            "\"punctuationInkFloor\":2.5",
            "\"punctuationInkFloor\":\"2.5\"",
        ))
        .unwrap_err();
        assert_eq!(error.name(), "InvalidPlanJsonField:punctuationInkFloor");
        let error = Plan::from_json_str(&plan.replace(
            "\"style\":{\"fontSize\":12.0,\"fontWeight\":700,\"italic\":true}",
            "\"style\":{\"fontSize\":\"12\"}",
        ))
        .unwrap_err();
        assert_eq!(error.name(), "InvalidPlanJsonField:style.fontSize");
        let error = Plan::from_json_str(&plan.replace(
            "\"emphasisRanges\":[[0,1]]",
            "\"emphasisRanges\":[[0,\"1\"]]",
        ))
        .unwrap_err();
        assert_eq!(error.name(), "InvalidPlanJsonField:emphasisRanges");
    }
}
