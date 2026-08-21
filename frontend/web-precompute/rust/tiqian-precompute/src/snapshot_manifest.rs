//! Snapshot manifest transport of `snapshot-manifest.js` (ADR 0050). Shared
//! tables deduplicate typography values, face descriptors, and shaping
//! replay rows; the compact encoding interns replay strings.
//!
//! Values cross this module as wire `Json`. Damage that js reports with a raw
//! `TypeError` (a null face descriptor, a non-object entry) surfaces with the
//! nearest named issue instead. Fields js would leave `undefined` stay absent
//! from the wire objects; version evidence compares structurally.

use std::collections::HashMap;

use tiqian::NamedError;

use crate::json::{parse_json, Json};
use crate::replay::{metric_replay_key, shape_replay_key};
use crate::schema::{stable_stringify, FONT_REPLAY_REVISION, FONT_REPLAY_TRANSPORT};
use crate::snapshot_source::js_number_value;

fn field<'a>(value: &'a Json, key: &str) -> Option<&'a Json> {
    match value {
        Json::Obj(fields) => fields.iter().find(|(name, _)| name == key).map(|(_, v)| v),
        _ => None,
    }
}

fn fields_of(value: &Json) -> Option<&[(String, Json)]> {
    match value {
        Json::Obj(fields) => Some(fields),
        _ => None,
    }
}

fn arr_of(value: Option<&Json>) -> Option<&[Json]> {
    match value {
        Some(Json::Arr(items)) => Some(items),
        _ => None,
    }
}

fn named(message: impl Into<String>) -> NamedError {
    NamedError(message.into())
}

/// js truthiness over a wire value.
fn truthy(value: &Json) -> bool {
    match value {
        Json::Null | Json::Bool(false) => false,
        Json::Num(inner) => *inner != 0.0,
        Json::Str(inner) => !inner.is_empty(),
        Json::Arr(_) | Json::Obj(_) => true,
        Json::Bool(true) => true,
    }
}

fn is_safe_integer(value: f64) -> bool {
    const MAX_SAFE_INTEGER: f64 = 9_007_199_254_740_991.0;
    value.fract() == 0.0 && value.abs() <= MAX_SAFE_INTEGER
}

fn key_string_of(value: &Json) -> String {
    match field(value, "key") {
        Some(Json::Str(text)) => text.clone(),
        Some(Json::Null) => "null".to_string(),
        None => "undefined".to_string(),
        Some(other) => crate::snapshot_source::js_string_value(other),
    }
}

/// `faceDescriptor`: the shared face identity drops per-paragraph coverage
/// and probe evidence.
/// js nullish: absent or `null`.
fn nullish(value: &Option<Json>) -> bool {
    matches!(value, None | Some(Json::Null))
}

/// `faceDescriptor`: the shared face identity drops per-paragraph coverage
/// and probe evidence. Any non-null value without entries spreads to an empty
/// descriptor, mirroring `Object.entries`.
fn face_descriptor(face: &Json, entry_key: &str) -> Result<Json, NamedError> {
    let filtered: Vec<(String, Json)> = match face {
        Json::Obj(fields) => fields
            .iter()
            .filter(|(name, _)| name != "coverageText" && name != "probe")
            .cloned()
            .collect(),
        Json::Null => {
            return Err(named(format!("SnapshotFontEvidenceInvalid:{entry_key}")))
        }
        _ => Vec::new(),
    };
    Ok(Json::Obj(filtered))
}

/// `tableIndex`: deduplicate by the stable rendering of the whole value.
fn table_index(table: &mut Vec<Json>, indexes: &mut HashMap<String, usize>, value: Json) -> usize {
    let signature = stable_stringify(&value);
    if let Some(existing) = indexes.get(&signature) {
        return *existing;
    }
    let index = table.len();
    table.push(value);
    indexes.insert(signature, index);
    index
}

/// `replayTableIndex`: replay rows deduplicate by key; a repeated key with a
/// different payload is a conflict.
fn replay_table_index(
    table: &mut Vec<Json>,
    indexes: &mut HashMap<String, usize>,
    value: &Json,
    conflict_issue: &str,
) -> Result<usize, NamedError> {
    let key = match field(value, "key") {
        Some(Json::Str(text)) if !text.is_empty() => text.clone(),
        _ => return Err(named(conflict_issue.replace("Conflict", "Invalid"))),
    };
    if let Some(existing) = indexes.get(&key) {
        if stable_stringify(&table[*existing]) != stable_stringify(value) {
            return Err(named(conflict_issue));
        }
        return Ok(*existing);
    }
    let index = table.len();
    table.push(value.clone());
    indexes.insert(key, index);
    Ok(index)
}

/// `replayKeyParts`: the key is a JSON array of a fixed length.
fn replay_key_parts(key: &str, expected_length: usize, issue: &str) -> Result<Vec<Json>, NamedError> {
    let parsed = parse_json(key).map_err(|_| named(issue))?;
    match parsed {
        Json::Arr(parts) if parts.len() == expected_length => Ok(parts),
        _ => Err(named(issue)),
    }
}

/// `compactFontReplay`: intern strings and flatten replay rows.
pub fn compact_font_replay(shapes: &[Json], metrics: &[Json]) -> Result<Json, NamedError> {
    let mut strings: Vec<Json> = Vec::new();
    let mut string_indexes: HashMap<String, usize> = HashMap::new();
    let string_ref = |value: &Json, strings: &mut Vec<Json>,
                      indexes: &mut HashMap<String, usize>|
     -> Result<usize, NamedError> {
        let Json::Str(text) = value else {
            return Err(named("SnapshotFontReplayStringInvalid"));
        };
        if let Some(existing) = indexes.get(text) {
            return Ok(*existing);
        }
        let index = strings.len();
        strings.push(value.clone());
        indexes.insert(text.clone(), index);
        Ok(index)
    };

    let mut compact_shapes = Vec::with_capacity(shapes.len());
    for item in shapes {
        let result = match field(item, "result") {
            Some(result) if truthy(result) => result,
            _ => return Err(named("SnapshotFontReplayShapeInvalid")),
        };
        let Some(Json::Str(key)) = field(item, "key") else {
            return Err(named("SnapshotFontReplayShapeInvalid"));
        };
        let (Some(Json::Arr(features)), Some(Json::Arr(glyphs))) =
            (field(result, "features"), field(result, "glyphs"))
        else {
            return Err(named("SnapshotFontReplayShapeInvalid"));
        };
        let parts =
            replay_key_parts(key, 7, "SnapshotFontReplayShapeKeyInvalid")?;
        let mut glyphs_flat = Vec::with_capacity(glyphs.len() * 8);
        for glyph in glyphs {
            let glyph_fields: &[(String, Json)] = match glyph {
                Json::Obj(fields) => fields,
                Json::Arr(_) => &[],
                _ => return Err(named("SnapshotFontReplayGlyphInvalid")),
            };
            let lookup = |name: &str| {
                glyph_fields
                    .iter()
                    .find(|(key, _)| key == name)
                    .map(|(_, value)| value)
            };
            let bounds: Vec<Json> = match lookup("boundsEm") {
                None | Some(Json::Null) => vec![Json::Null; 4],
                Some(Json::Arr(values)) if values.len() == 4 => values.clone(),
                _ => return Err(named("SnapshotFontReplayGlyphBoundsInvalid")),
            };
            for name in ["id", "advanceEm", "xEm", "yEm"] {
                glyphs_flat.push(lookup(name).cloned().unwrap_or(Json::Null));
            }
            glyphs_flat.extend(bounds);
        }
        let part = |index: usize| parts.get(index).cloned().unwrap_or(Json::Null);
        let mut row = vec![
            Json::Num(string_ref(&part(0), &mut strings, &mut string_indexes)? as f64),
            Json::Num(string_ref(&part(1), &mut strings, &mut string_indexes)? as f64),
            part(2),
            Json::Num(truthy(&part(3)) as u8 as f64),
            Json::Num(string_ref(&part(4), &mut strings, &mut string_indexes)? as f64),
            Json::Num(string_ref(&part(5), &mut strings, &mut string_indexes)? as f64),
            Json::Num(string_ref(&part(6), &mut strings, &mut string_indexes)? as f64),
            Json::Num(
                string_ref(
                    field(result, "faceId").unwrap_or(&Json::Null),
                    &mut strings,
                    &mut string_indexes,
                )? as f64,
            ),
            Json::Num(
                string_ref(
                    field(result, "fontInstanceId").unwrap_or(&Json::Null),
                    &mut strings,
                    &mut string_indexes,
                )? as f64,
            ),
            Json::Num(
                string_ref(
                    field(result, "script").unwrap_or(&Json::Null),
                    &mut strings,
                    &mut string_indexes,
                )? as f64,
            ),
        ];
        row.push(Json::Arr(
            features
                .iter()
                .map(|feature| {
                    string_ref(feature, &mut strings, &mut string_indexes)
                        .map(|index| Json::Num(index as f64))
                })
                .collect::<Result<Vec<_>, _>>()?,
        ));
        row.push(
            field(result, "unsafeBreakCount")
                .cloned()
                .unwrap_or(Json::Null),
        );
        row.push(field(result, "advanceEm").cloned().unwrap_or(Json::Null));
        row.push(Json::Arr(glyphs_flat));
        compact_shapes.push(Json::Arr(row));
    }

    let mut compact_metrics = Vec::with_capacity(metrics.len());
    for item in metrics {
        let Some(Json::Str(key)) = field(item, "key") else {
            return Err(named("SnapshotFontReplayMetricsInvalid"));
        };
        let values = arr_of(field(item, "valuesEm"))
            .filter(|values| values.len() == 5)
            .ok_or_else(|| named("SnapshotFontReplayMetricsInvalid"))?;
        let parts =
            replay_key_parts(key, 5, "SnapshotFontReplayMetricsKeyInvalid")?;
        let part = |index: usize| parts.get(index).cloned().unwrap_or(Json::Null);
        let mut row = vec![
            Json::Num(string_ref(&part(0), &mut strings, &mut string_indexes)? as f64),
            part(1),
            Json::Num(truthy(&part(2)) as u8 as f64),
            Json::Num(string_ref(&part(3), &mut strings, &mut string_indexes)? as f64),
            Json::Num(string_ref(&part(4), &mut strings, &mut string_indexes)? as f64),
        ];
        row.extend(values.iter().cloned());
        compact_metrics.push(Json::Arr(row));
    }

    Ok(Json::Obj(vec![
        ("revision".to_string(), Json::str(FONT_REPLAY_REVISION)),
        ("encoding".to_string(), Json::str(FONT_REPLAY_TRANSPORT)),
        ("strings".to_string(), Json::Arr(strings)),
        ("shapes".to_string(), Json::Arr(compact_shapes)),
        ("metrics".to_string(), Json::Arr(compact_metrics)),
    ]))
}

/// `tableReference`: a table index must be a safe integer inside the table.
fn table_reference<'a>(table: &'a [Json], index: Option<&Json>, issue: &str) -> Result<&'a Json, NamedError> {
    let Json::Num(value) = index.cloned().unwrap_or(Json::Null) else {
        return Err(named(issue));
    };
    if !is_safe_integer(value) || value < 0.0 || value >= table.len() as f64 {
        return Err(named(issue));
    }
    Ok(&table[value as usize])
}

fn string_at<'a>(strings: &'a [Json], index: &Json) -> Result<&'a str, NamedError> {
    let referenced =
        table_reference(strings, Some(index), "SnapshotFontReplayStringReferenceInvalid")?;
    let Json::Str(text) = referenced else {
        return Err(named("SnapshotFontReplayStringReferenceInvalid"));
    };
    Ok(text)
}

fn flag_row_value(value: &Json) -> Option<bool> {
    match value {
        Json::Num(inner) if *inner == 0.0 => Some(false),
        Json::Num(inner) if *inner == 1.0 => Some(true),
        _ => None,
    }
}

/// `expandFontReplay`: rebuild canonical replay rows from the compact
/// transport. A replay without an encoding is already canonical and passes
/// through unchanged.
pub fn expand_font_replay(replay: &Json) -> Result<Json, NamedError> {
    let revision_ok = field(replay, "revision") == Some(&Json::str(FONT_REPLAY_REVISION));
    let (Some(shapes), Some(metrics)) = (
        arr_of(field(replay, "shapes")),
        arr_of(field(replay, "metrics")),
    ) else {
        return Err(named("SnapshotFontReplayInvalid"));
    };
    if !revision_ok {
        return Err(named("SnapshotFontReplayInvalid"));
    }
    let encoding = field(replay, "encoding");
    if encoding.is_none() || encoding == Some(&Json::Null) {
        return Ok(replay.clone());
    }
    if !matches!(encoding, Some(&Json::Str(ref value)) if value == FONT_REPLAY_TRANSPORT) {
        return Err(named("SnapshotFontReplayTransportInvalid"));
    }
    let Some(strings) = arr_of(field(replay, "strings")) else {
        return Err(named("SnapshotFontReplayTransportInvalid"));
    };
    if strings.iter().any(|value| !matches!(value, Json::Str(_))) {
        return Err(named("SnapshotFontReplayTransportInvalid"));
    }

    let mut expanded_shapes = Vec::with_capacity(shapes.len());
    for row in shapes {
        let Json::Arr(row) = row else {
            return Err(named("SnapshotFontReplayShapeTransportInvalid"));
        };
        if row.len() != 14
            || !matches!(row[10], Json::Arr(_))
            || flag_row_value(&row[3]).is_none()
        {
            return Err(named("SnapshotFontReplayShapeTransportInvalid"));
        }
        let glyph_rows_ok = match &row[13] {
            Json::Arr(values) => values.len() % 8 == 0,
            _ => false,
        };
        if !glyph_rows_ok {
            return Err(named("SnapshotFontReplayShapeTransportInvalid"));
        }
        let Json::Arr(glyph_values) = &row[13] else {
            return Err(named("SnapshotFontReplayShapeTransportInvalid"));
        };
        let mut glyphs = Vec::with_capacity(glyph_values.len() / 8);
        for glyph in glyph_values.chunks(8) {
            let bounds = glyph[4..8].to_vec();
            let all_null = bounds.iter().all(|value| *value == Json::Null);
            if !all_null && bounds.iter().any(|value| *value == Json::Null) {
                return Err(named("SnapshotFontReplayGlyphBoundsInvalid"));
            }
            glyphs.push(Json::Obj(vec![
                ("id".to_string(), glyph[0].clone()),
                ("advanceEm".to_string(), glyph[1].clone()),
                ("xEm".to_string(), glyph[2].clone()),
                ("yEm".to_string(), glyph[3].clone()),
                ("boundsEm".to_string(), if all_null { Json::Null } else { Json::Arr(bounds) }),
            ]));
        }
        let Json::Arr(features) = &row[10] else {
            return Err(named("SnapshotFontReplayShapeTransportInvalid"));
        };
        let display_text = string_at(strings, &row[0])?;
        let serialized_families = string_at(strings, &row[1])?;
        let italic = flag_row_value(&row[3]).expect("checked above");
        let locale = string_at(strings, &row[4])?;
        let role = string_at(strings, &row[5])?;
        let source_text = string_at(strings, &row[6])?;
        let key = shape_replay_key(
            display_text,
            serialized_families,
            js_number_value(&row[2]),
            italic,
            locale,
            Some(role),
            source_text,
        );
        expanded_shapes.push(Json::Obj(vec![
            ("key".to_string(), Json::str(key)),
            (
                "result".to_string(),
                Json::Obj(vec![
                    ("faceId".to_string(), Json::str(string_at(strings, &row[7])?)),
                    (
                        "fontInstanceId".to_string(),
                        Json::str(string_at(strings, &row[8])?),
                    ),
                    ("script".to_string(), Json::str(string_at(strings, &row[9])?)),
                    (
                        "features".to_string(),
                        Json::Arr(
                            features
                                .iter()
                                .map(|index| Ok(Json::str(string_at(strings, index)?)))
                                .collect::<Result<Vec<_>, _>>()?,
                        ),
                    ),
                    ("unsafeBreakCount".to_string(), row[11].clone()),
                    ("advanceEm".to_string(), row[12].clone()),
                    ("glyphs".to_string(), Json::Arr(glyphs)),
                ]),
            ),
        ]));
    }

    let mut expanded_metrics = Vec::with_capacity(metrics.len());
    for row in metrics {
        let Json::Arr(row) = row else {
            return Err(named("SnapshotFontReplayMetricsTransportInvalid"));
        };
        if row.len() != 10 || flag_row_value(&row[2]).is_none() {
            return Err(named("SnapshotFontReplayMetricsTransportInvalid"));
        }
        let serialized_families = string_at(strings, &row[0])?;
        let italic = flag_row_value(&row[2]).expect("checked above");
        let role = string_at(strings, &row[3])?;
        let face_selection_text = string_at(strings, &row[4])?;
        let key = metric_replay_key(
            serialized_families,
            js_number_value(&row[1]),
            italic,
            Some(role),
            Some(face_selection_text),
        );
        expanded_metrics.push(Json::Obj(vec![
            ("key".to_string(), Json::str(key)),
            ("valuesEm".to_string(), Json::Arr(row[5..10].to_vec())),
        ]));
    }

    Ok(Json::Obj(vec![
        ("revision".to_string(), field(replay, "revision").cloned().unwrap_or(Json::Null)),
        ("shapes".to_string(), Json::Arr(expanded_shapes)),
        ("metrics".to_string(), Json::Arr(expanded_metrics)),
    ]))
}

/// `compactSnapshotManifest`: shared tables plus per-paragraph references.
pub fn compact_snapshot_manifest(entries: &Json, metadata: &Json) -> Result<Json, NamedError> {
    let entry_list = arr_of(Some(entries))
        .ok_or_else(|| named("SnapshotFontEvidenceInvalid:undefined"))?;
    let mut typographies: Vec<Json> = Vec::new();
    let mut typography_indexes: HashMap<String, usize> = HashMap::new();
    let mut faces: Vec<Json> = Vec::new();
    let mut face_indexes: HashMap<String, usize> = HashMap::new();
    let mut backend_revision: Option<Json> = Some(Json::Null);
    let mut harfbuzz_version: Option<Json> = Some(Json::Null);
    let mut replay_shapes: Vec<Json> = Vec::new();
    let mut replay_shape_indexes: HashMap<String, usize> = HashMap::new();
    let mut replay_metrics: Vec<Json> = Vec::new();
    let mut replay_metric_indexes: HashMap<String, usize> = HashMap::new();

    let mut compact_entries = Vec::with_capacity(entry_list.len());
    for entry in entry_list {
        let entry_key = key_string_of(entry);
        let evidence = field(entry, "fontEvidence");
        let faces_list = evidence.and_then(|value| arr_of(field(value, "faces")));
        let evidence_ok = evidence.is_some_and(truthy)
            && faces_list.is_some_and(|list| !list.is_empty());
        if !evidence_ok {
            return Err(named(format!("SnapshotFontEvidenceInvalid:{entry_key}")));
        }
        let evidence = evidence.expect("checked above");
        let faces_list = faces_list.expect("checked above");
        let replay = field(evidence, "replay");
        let replay_ok = replay.is_some_and(|value| {
            field(value, "revision") == Some(&Json::str(FONT_REPLAY_REVISION))
                && arr_of(field(value, "shapes")).is_some()
                && arr_of(field(value, "metrics")).is_some()
        });
        if !replay_ok {
            return Err(named(format!("SnapshotFontReplayInvalid:{entry_key}")));
        }
        let replay = replay.expect("checked above");
        for shape in arr_of(field(replay, "shapes")).expect("checked above") {
            replay_table_index(
                &mut replay_shapes,
                &mut replay_shape_indexes,
                shape,
                "SnapshotFontReplayShapeConflict",
            )?;
        }
        for metric in arr_of(field(replay, "metrics")).expect("checked above") {
            replay_table_index(
                &mut replay_metrics,
                &mut replay_metric_indexes,
                metric,
                "SnapshotFontReplayMetricsConflict",
            )?;
        }
        // `??=` keeps the slot writable until real evidence arrives, so a
        // missing version on early entries never conflicts with later ones.
        let backend = field(evidence, "backendRevision").cloned();
        if nullish(&backend_revision) {
            backend_revision = backend.clone();
        }
        if backend_revision != backend {
            return Err(named("SnapshotFontEvidenceVersionConflict"));
        }
        let version = field(evidence, "harfbuzzVersion").cloned();
        if nullish(&harfbuzz_version) {
            harfbuzz_version = version.clone();
        }
        if harfbuzz_version != version {
            return Err(named("SnapshotFontEvidenceVersionConflict"));
        }
        let typography_ref = table_index(
            &mut typographies,
            &mut typography_indexes,
            Json::Obj(vec![
                (
                    "sha256".to_string(),
                    field(entry, "typographySha256").cloned().unwrap_or(Json::Null),
                ),
                (
                    "value".to_string(),
                    field(entry, "typography").cloned().unwrap_or(Json::Null),
                ),
            ]),
        );
        let mut font_face_evidence = Vec::with_capacity(faces_list.len());
        for face in faces_list {
            let descriptor = face_descriptor(face, &entry_key)?;
            let face_ref = table_index(&mut faces, &mut face_indexes, descriptor);
            font_face_evidence.push(Json::Obj(vec![
                ("faceRef".to_string(), Json::Num(face_ref as f64)),
                (
                    "coverageText".to_string(),
                    field(face, "coverageText").cloned().unwrap_or(Json::Null),
                ),
                ("probe".to_string(), field(face, "probe").cloned().unwrap_or(Json::Null)),
            ]));
        }
        let mut compact = vec![
            ("key".to_string(), field(entry, "key").cloned().unwrap_or(Json::Null)),
            (
                "sourceSha256".to_string(),
                field(entry, "sourceSha256").cloned().unwrap_or(Json::Null),
            ),
        ];
        if let Some(Json::Str(artifact)) = field(entry, "sourceArtifactSha256") {
            compact.push(("sourceArtifactSha256".to_string(), Json::str(artifact.clone())));
        }
        if matches!(field(entry, "semantics"), Some(Json::Arr(list)) if !list.is_empty()) {
            compact.push(("semantic".to_string(), Json::Bool(true)));
        }
        compact.extend([
            ("typographyRef".to_string(), Json::Num(typography_ref as f64)),
            (
                "maxWidthPx".to_string(),
                field(entry, "maxWidthPx").cloned().unwrap_or(Json::Null),
            ),
            ("fontFaceEvidence".to_string(), Json::Arr(font_face_evidence)),
            (
                "renderArtifactSha256".to_string(),
                field(entry, "renderArtifactSha256").cloned().unwrap_or(Json::Null),
            ),
        ]);
        compact_entries.push(Json::Obj(compact));
    }

    let mut output: Vec<(String, Json)> = fields_of(metadata)
        .map(|fields| fields.to_vec())
        .unwrap_or_default();
    output.push(("typographies".to_string(), Json::Arr(typographies)));
    // Version slots no entry ever supplied stay undefined and drop from the
    // wire. An empty entry list keeps the initial null slot.
    let mut font_evidence: Vec<(String, Json)> = Vec::new();
    if let Some(value) = backend_revision {
        font_evidence.push(("backendRevision".to_string(), value));
    }
    if let Some(value) = harfbuzz_version {
        font_evidence.push(("harfbuzzVersion".to_string(), value));
    }
    font_evidence.push(("faces".to_string(), Json::Arr(faces)));
    output.push(("fontEvidence".to_string(), Json::Obj(font_evidence)));
    output.push((
        "fontReplay".to_string(),
        compact_font_replay(&replay_shapes, &replay_metrics)?,
    ));
    output.push(("entries".to_string(), Json::Arr(compact_entries)));
    Ok(Json::Obj(output))
}

fn expanded_entry(
    entry: &Json,
    typographies: &[Json],
    descriptors: &[Json],
    manifest_evidence: &Json,
) -> Result<Json, NamedError> {
    let typography = table_reference(
        typographies,
        field(entry, "typographyRef"),
        "SnapshotTypographyReferenceInvalid",
    )?;
    let sha256_string = matches!(field(typography, "sha256"), Some(Json::Str(_)));
    let value_truthy = field(typography, "value").is_some_and(truthy);
    if !sha256_string || !value_truthy {
        return Err(named("SnapshotTypographyTableInvalid"));
    }
    let evidence_list = arr_of(field(entry, "fontFaceEvidence"))
        .filter(|list| !list.is_empty())
        .ok_or_else(|| named("SnapshotFontEvidenceReferenceInvalid"))?;
    let mut faces = Vec::with_capacity(evidence_list.len());
    for evidence in evidence_list {
        let descriptor = table_reference(
            descriptors,
            field(evidence, "faceRef"),
            "SnapshotFontFaceReferenceInvalid",
        )?;
        let mut face = match descriptor {
            Json::Obj(fields) => fields.to_vec(),
            _ => Vec::new(),
        };
        face.push((
            "coverageText".to_string(),
            field(evidence, "coverageText").cloned().unwrap_or(Json::Null),
        ));
        face.push((
            "probe".to_string(),
            field(evidence, "probe").cloned().unwrap_or(Json::Null),
        ));
        faces.push(Json::Obj(face));
    }
    let mut expanded = vec![
        ("key".to_string(), field(entry, "key").cloned().unwrap_or(Json::Null)),
        (
            "sourceSha256".to_string(),
            field(entry, "sourceSha256").cloned().unwrap_or(Json::Null),
        ),
    ];
    if let Some(Json::Str(artifact)) = field(entry, "sourceArtifactSha256") {
        expanded.push(("sourceArtifactSha256".to_string(), Json::str(artifact.clone())));
    }
    if field(entry, "semantic") == Some(&Json::Bool(true)) {
        expanded.push(("semantic".to_string(), Json::Bool(true)));
    }
    expanded.extend([
        (
            "typographySha256".to_string(),
            field(typography, "sha256").cloned().unwrap_or(Json::Null),
        ),
        (
            "typography".to_string(),
            field(typography, "value").cloned().unwrap_or(Json::Null),
        ),
        (
            "maxWidthPx".to_string(),
            field(entry, "maxWidthPx").cloned().unwrap_or(Json::Null),
        ),
    ]);
    // Version evidence copies through only when the manifest carries it.
    let mut evidence_obj: Vec<(String, Json)> = Vec::new();
    if let Some(value) = field(manifest_evidence, "backendRevision") {
        evidence_obj.push(("backendRevision".to_string(), value.clone()));
    }
    if let Some(value) = field(manifest_evidence, "harfbuzzVersion") {
        evidence_obj.push(("harfbuzzVersion".to_string(), value.clone()));
    }
    evidence_obj.push(("faces".to_string(), Json::Arr(faces)));
    expanded.push(("fontEvidence".to_string(), Json::Obj(evidence_obj)));
    expanded.push((
        "renderArtifactSha256".to_string(),
        field(entry, "renderArtifactSha256").cloned().unwrap_or(Json::Null),
    ));
    Ok(Json::Obj(expanded))
}

fn expand_entries_list(
    entries: &[Json],
    typographies: &[Json],
    descriptors: &[Json],
    manifest_evidence: &Json,
) -> Result<Vec<Json>, NamedError> {
    entries
        .iter()
        .map(|entry| expanded_entry(entry, typographies, descriptors, manifest_evidence))
        .collect()
}

/// `expandSnapshotManifest`: rebuild the canonical runtime manifest from the
/// compact transport, keeping every metadata field in place.
pub fn expand_snapshot_manifest(manifest: &Json) -> Result<Json, NamedError> {
    let manifest_fields = fields_of(manifest).ok_or_else(|| named("SnapshotManifestInvalid"))?;
    let typographies = arr_of(field(manifest, "typographies"))
        .ok_or_else(|| named("SnapshotManifestTablesInvalid"))?;
    let font_evidence = field(manifest, "fontEvidence").and_then(fields_of);
    let descriptors = font_evidence
        .and_then(|fields| {
            fields
                .iter()
                .find(|(name, _)| name == "faces")
                .map(|(_, value)| value)
        })
        .and_then(|faces| arr_of(Some(faces)));
    let entries = arr_of(field(manifest, "entries"))
        .ok_or_else(|| named("SnapshotManifestTablesInvalid"))?;
    if font_evidence.is_none() || descriptors.is_none() {
        return Err(named("SnapshotManifestTablesInvalid"));
    }
    let font_evidence = field(manifest, "fontEvidence").expect("checked above");
    let descriptors = descriptors.expect("checked above");

    let font_replay = match field(manifest, "fontReplay") {
        None | Some(Json::Null) => None,
        Some(replay) => Some(expand_font_replay(replay)?),
    };
    let expanded_entries =
        expand_entries_list(entries, typographies, descriptors, font_evidence)?;
    let font_contract_entries = match field(manifest, "fontContractEntries") {
        Some(Json::Arr(list)) => Some(expand_entries_list(
            list,
            typographies,
            descriptors,
            font_evidence,
        )?),
        _ => None,
    };

    // The spread keeps each existing key in place and only replaces values.
    let mut output: Vec<(String, Json)> = manifest_fields.to_vec();
    let replace = |output: &mut Vec<(String, Json)>, key: &str, value: Option<Json>| {
        if let Some(value) = value {
            match output.iter_mut().find(|(name, _)| name == key) {
                Some(slot) => slot.1 = value,
                None => output.push((key.to_string(), value)),
            }
        }
    };
    replace(&mut output, "fontReplay", font_replay);
    replace(&mut output, "entries", Some(Json::Arr(expanded_entries)));
    replace(&mut output, "fontContractEntries", font_contract_entries.map(Json::Arr));
    Ok(Json::Obj(output))
}

/// `parseSnapshotManifest`: parse the wire text and expand.
pub fn parse_snapshot_manifest(text: &str) -> Result<Json, NamedError> {
    let parsed = parse_json(text)
        .map_err(|error| named(format!("InvalidSnapshotManifestJson:{error}")))?;
    expand_snapshot_manifest(&parsed)
}
