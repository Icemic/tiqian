// Snapshot manifest transport parity (ADR 0050).
//
// Byte goldens and error names come from the js oracle
// `frontend/web/npm/snapshot-manifest.js`, generated with node against the
// same inputs. The goldens pin shared-table dedup order, string interning,
// version lock-in, and the exact issue vocabulary.

use tiqian::NamedError;
use tiqian_precompute::json::{parse_json, Json};
use tiqian_precompute::replay::{metric_replay_key, shape_replay_key};
use tiqian_precompute::snapshot_manifest::{
    compact_snapshot_manifest, expand_snapshot_manifest, parse_snapshot_manifest,
};

const SHAPE_KEY: &str = r#"["排","Tiqian Han",400,false,"zh-Hans","body","排"]"#;
const METRIC_KEY: &str = r#"["Tiqian Han",400,false,"body","永"]"#;

const SHAPE_ITEM: &str = r#"{"key":"[\"排\",\"Tiqian Han\",400,false,\"zh-Hans\",\"body\",\"排\"]","result":{"faceId":"face-1","fontInstanceId":"fi-1","script":"hani","features":["pwid","palt"],"unsafeBreakCount":0,"advanceEm":1000,"glyphs":[{"id":1,"advanceEm":500,"xEm":10,"yEm":-2,"boundsEm":[0,-2,500,700]},{"id":2,"advanceEm":500,"xEm":5,"yEm":0,"boundsEm":null}]}}"#;
const METRIC_ITEM: &str =
    r#"{"key":"[\"Tiqian Han\",400,false,\"body\",\"永\"]","valuesEm":[1.5,2,3,4,5]}"#;

const COMPACT_A: &str = r#"{"createdAt":"2026-08-20","locale":"zh-Hans","typographies":[{"sha256":"typ-typo-a","value":{"value":"typo-a","lineHeight":1.6}},{"sha256":"typ-typo-b","value":{"value":"typo-b","lineHeight":1.6}}],"fontEvidence":{"backendRevision":"backend-7","faces":[{"family":"Tiqian Han","style":"normal","weight":400}]},"fontReplay":{"revision":"tiqian-server-shaping-replay-v1","encoding":"shared-strings-v1","strings":["排","Tiqian Han","zh-Hans","body","face-1","fi-1","hani","pwid","palt","永"],"shapes":[[0,1,400,0,2,3,0,4,5,6,[7,8],0,1000,[1,500,10,-2,0,-2,500,700,2,500,5,0,null,null,null,null]]],"metrics":[[1,400,0,3,9,1.5,2,3,4,5]]},"entries":[{"key":"p1","sourceSha256":"sha-p1","typographyRef":0,"maxWidthPx":320,"fontFaceEvidence":[{"faceRef":0,"coverageText":"永中","probe":1}],"renderArtifactSha256":"render-p1"},{"key":"p2","sourceSha256":"sha-p2","sourceArtifactSha256":"art-p2","semantic":true,"typographyRef":0,"maxWidthPx":320,"fontFaceEvidence":[{"faceRef":0,"coverageText":"永中","probe":1}],"renderArtifactSha256":"render-p2"},{"key":"p3","sourceSha256":"sha-p3","typographyRef":1,"maxWidthPx":320,"fontFaceEvidence":[{"faceRef":0,"coverageText":"永中","probe":1}],"renderArtifactSha256":"render-p3"}]}"#;

const EXPAND_A: &str = r#"{"createdAt":"2026-08-20","locale":"zh-Hans","typographies":[{"sha256":"typ-typo-a","value":{"value":"typo-a","lineHeight":1.6}},{"sha256":"typ-typo-b","value":{"value":"typo-b","lineHeight":1.6}}],"fontEvidence":{"backendRevision":"backend-7","faces":[{"family":"Tiqian Han","style":"normal","weight":400}]},"fontReplay":{"revision":"tiqian-server-shaping-replay-v1","shapes":[{"key":"[\"排\",\"Tiqian Han\",400,false,\"zh-Hans\",\"body\",\"排\"]","result":{"faceId":"face-1","fontInstanceId":"fi-1","script":"hani","features":["pwid","palt"],"unsafeBreakCount":0,"advanceEm":1000,"glyphs":[{"id":1,"advanceEm":500,"xEm":10,"yEm":-2,"boundsEm":[0,-2,500,700]},{"id":2,"advanceEm":500,"xEm":5,"yEm":0,"boundsEm":null}]}}],"metrics":[{"key":"[\"Tiqian Han\",400,false,\"body\",\"永\"]","valuesEm":[1.5,2,3,4,5]}]},"entries":[{"key":"p1","sourceSha256":"sha-p1","typographySha256":"typ-typo-a","typography":{"value":"typo-a","lineHeight":1.6},"maxWidthPx":320,"fontEvidence":{"backendRevision":"backend-7","faces":[{"family":"Tiqian Han","style":"normal","weight":400,"coverageText":"永中","probe":1}]},"renderArtifactSha256":"render-p1"},{"key":"p2","sourceSha256":"sha-p2","sourceArtifactSha256":"art-p2","semantic":true,"typographySha256":"typ-typo-a","typography":{"value":"typo-a","lineHeight":1.6},"maxWidthPx":320,"fontEvidence":{"backendRevision":"backend-7","faces":[{"family":"Tiqian Han","style":"normal","weight":400,"coverageText":"永中","probe":1}]},"renderArtifactSha256":"render-p2"},{"key":"p3","sourceSha256":"sha-p3","typographySha256":"typ-typo-b","typography":{"value":"typo-b","lineHeight":1.6},"maxWidthPx":320,"fontEvidence":{"backendRevision":"backend-7","faces":[{"family":"Tiqian Han","style":"normal","weight":400,"coverageText":"永中","probe":1}]},"renderArtifactSha256":"render-p3"}]}"#;

const COMPACT_B: &str = r#"{"createdAt":"2026-08-20","locale":"zh-Hans","typographies":[{"sha256":"typ-typo-a","value":{"value":"typo-a","lineHeight":1.6}}],"fontEvidence":{"backendRevision":"backend-9","faces":[{"family":"Tiqian Han","style":"normal","weight":400}]},"fontReplay":{"revision":"tiqian-server-shaping-replay-v1","encoding":"shared-strings-v1","strings":["排","Tiqian Han","zh-Hans","body","face-1","fi-1","hani","pwid","palt","永"],"shapes":[[0,1,400,0,2,3,0,4,5,6,[7,8],0,1000,[1,500,10,-2,0,-2,500,700,2,500,5,0,null,null,null,null]]],"metrics":[[1,400,0,3,9,1.5,2,3,4,5]]},"entries":[{"key":"p1","sourceSha256":"sha-p1","typographyRef":0,"maxWidthPx":320,"fontFaceEvidence":[{"faceRef":0,"coverageText":"永中","probe":1}],"renderArtifactSha256":"render-p1"},{"key":"p2","sourceSha256":"sha-p2","typographyRef":0,"maxWidthPx":320,"fontFaceEvidence":[{"faceRef":0,"coverageText":"永中","probe":1}],"renderArtifactSha256":"render-p2"}]}"#;

const EXPAND_CONTRACT: &str = r#"{"createdAt":"2026-08-20","locale":"zh-Hans","typographies":[{"sha256":"typ-typo-a","value":{"value":"typo-a","lineHeight":1.6}},{"sha256":"typ-typo-b","value":{"value":"typo-b","lineHeight":1.6}}],"fontEvidence":{"backendRevision":"backend-7","faces":[{"family":"Tiqian Han","style":"normal","weight":400}]},"fontReplay":{"revision":"tiqian-server-shaping-replay-v1","shapes":[{"key":"[\"排\",\"Tiqian Han\",400,false,\"zh-Hans\",\"body\",\"排\"]","result":{"faceId":"face-1","fontInstanceId":"fi-1","script":"hani","features":["pwid","palt"],"unsafeBreakCount":0,"advanceEm":1000,"glyphs":[{"id":1,"advanceEm":500,"xEm":10,"yEm":-2,"boundsEm":[0,-2,500,700]},{"id":2,"advanceEm":500,"xEm":5,"yEm":0,"boundsEm":null}]}}],"metrics":[{"key":"[\"Tiqian Han\",400,false,\"body\",\"永\"]","valuesEm":[1.5,2,3,4,5]}]},"entries":[{"key":"p1","sourceSha256":"sha-p1","typographySha256":"typ-typo-a","typography":{"value":"typo-a","lineHeight":1.6},"maxWidthPx":320,"fontEvidence":{"backendRevision":"backend-7","faces":[{"family":"Tiqian Han","style":"normal","weight":400,"coverageText":"永中","probe":1}]},"renderArtifactSha256":"render-p1"},{"key":"p2","sourceSha256":"sha-p2","sourceArtifactSha256":"art-p2","semantic":true,"typographySha256":"typ-typo-a","typography":{"value":"typo-a","lineHeight":1.6},"maxWidthPx":320,"fontEvidence":{"backendRevision":"backend-7","faces":[{"family":"Tiqian Han","style":"normal","weight":400,"coverageText":"永中","probe":1}]},"renderArtifactSha256":"render-p2"},{"key":"p3","sourceSha256":"sha-p3","typographySha256":"typ-typo-b","typography":{"value":"typo-b","lineHeight":1.6},"maxWidthPx":320,"fontEvidence":{"backendRevision":"backend-7","faces":[{"family":"Tiqian Han","style":"normal","weight":400,"coverageText":"永中","probe":1}]},"renderArtifactSha256":"render-p3"}],"fontContractEntries":[{"key":"p1","sourceSha256":"sha-p1","typographySha256":"typ-typo-a","typography":{"value":"typo-a","lineHeight":1.6},"maxWidthPx":320,"fontEvidence":{"backendRevision":"backend-7","faces":[{"family":"Tiqian Han","style":"normal","weight":400,"coverageText":"永中","probe":1}]},"renderArtifactSha256":"render-p1"}]}"#;

const COMPACT_EMPTY: &str = r#"{"typographies":[],"fontEvidence":{"backendRevision":null,"harfbuzzVersion":null,"faces":[]},"fontReplay":{"revision":"tiqian-server-shaping-replay-v1","encoding":"shared-strings-v1","strings":[],"shapes":[],"metrics":[]},"entries":[]}"#;

fn entry_text_with(
    shapes: &str,
    metrics: &str,
    key: &str,
    typography: &str,
    extra: &str,
    versions: &str,
) -> String {
    format!(
        r#"{{"key":"{key}","sourceSha256":"sha-{key}",{extra}"typographySha256":"typ-{typography}","typography":{{"value":"{typography}","lineHeight":1.6}},"maxWidthPx":320,"fontEvidence":{{{versions}"faces":[{{"family":"Tiqian Han","style":"normal","weight":400,"coverageText":"永中","probe":1}}],"replay":{{"revision":"tiqian-server-shaping-replay-v1","shapes":[{shapes}],"metrics":[{metrics}]}}}},"renderArtifactSha256":"render-{key}"}}"#,
    )
}

fn entry_text(key: &str, typography: &str, extra: &str, versions: &str) -> String {
    entry_text_with(SHAPE_ITEM, METRIC_ITEM, key, typography, extra, versions)
}

const BACKEND_7: &str = r#""backendRevision":"backend-7","#;

fn entries_a() -> Json {
    let p1 = entry_text("p1", "typo-a", "", BACKEND_7);
    let p2 = entry_text(
        "p2",
        "typo-a",
        r#""sourceArtifactSha256":"art-p2","semantics":[{"start":0,"end":2}],"#,
        BACKEND_7,
    );
    let p3 = entry_text("p3", "typo-b", "", BACKEND_7);
    parse_json(&format!("[{p1},{p2},{p3}]")).expect("entries parse")
}

fn entries_b() -> Json {
    let p1 = entry_text("p1", "typo-a", "", "");
    let p2 = entry_text("p2", "typo-a", "", r#""backendRevision":"backend-9","#);
    parse_json(&format!("[{p1},{p2}]")).expect("entries parse")
}

fn metadata() -> Json {
    parse_json(r#"{"createdAt":"2026-08-20","locale":"zh-Hans"}"#).expect("metadata parses")
}

fn compact_a() -> Json {
    compact_snapshot_manifest(&entries_a(), &metadata()).expect("compact A")
}

fn obj_field_mut<'a>(value: &'a mut Json, key: &str) -> Option<&'a mut Json> {
    let Json::Obj(fields) = value else {
        return None;
    };
    fields
        .iter_mut()
        .find(|(name, _)| name == key)
        .map(|(_, v)| v)
}

fn field<'a>(value: &'a Json, key: &str) -> Option<&'a Json> {
    match value {
        Json::Obj(fields) => fields.iter().find(|(name, _)| name == key).map(|(_, v)| v),
        _ => None,
    }
}

fn edit_shape_row(manifest: &mut Json, edit: impl FnOnce(&mut Vec<Json>)) {
    let replay = obj_field_mut(manifest, "fontReplay").expect("fontReplay");
    let shapes = obj_field_mut(replay, "shapes").expect("shapes");
    let Json::Arr(rows) = shapes else {
        panic!("shape rows")
    };
    let Json::Arr(cells) = rows.get_mut(0).expect("first row") else {
        panic!("row cells")
    };
    edit(cells);
}

fn edit_metric_row(manifest: &mut Json, edit: impl FnOnce(&mut Vec<Json>)) {
    let replay = obj_field_mut(manifest, "fontReplay").expect("fontReplay");
    let metrics = obj_field_mut(replay, "metrics").expect("metrics");
    let Json::Arr(rows) = metrics else {
        panic!("metric rows")
    };
    let Json::Arr(cells) = rows.get_mut(0).expect("first row") else {
        panic!("row cells")
    };
    edit(cells);
}

fn edit_entry(manifest: &mut Json, index: usize, edit: impl FnOnce(&mut Json)) {
    let entries = obj_field_mut(manifest, "entries").expect("entries");
    let Json::Arr(list) = entries else {
        panic!("entry list")
    };
    edit(list.get_mut(index).expect("entry"));
}

fn error_name(result: Result<Json, NamedError>) -> String {
    match result {
        Err(error) => error.name().to_string(),
        Ok(_) => panic!("expected an error, got a manifest"),
    }
}

#[test]
fn compact_matches_the_js_oracle_bytes() {
    let compact = compact_a();
    assert_eq!(compact.render(), COMPACT_A);
}

#[test]
fn expand_matches_the_js_oracle_bytes() {
    let expanded = expand_snapshot_manifest(&compact_a()).expect("expand A");
    assert_eq!(expanded.render(), EXPAND_A);
}

#[test]
fn replay_keys_round_trip_through_the_compact_transport() {
    assert_eq!(
        shape_replay_key(
            "排",
            "Tiqian Han",
            400.0,
            false,
            "zh-Hans",
            Some("body"),
            "排"
        ),
        SHAPE_KEY
    );
    assert_eq!(
        metric_replay_key("Tiqian Han", 400.0, false, Some("body"), Some("永")),
        METRIC_KEY
    );
    let expanded = expand_snapshot_manifest(&compact_a()).expect("expand A");
    let replay = field(&expanded, "fontReplay").expect("fontReplay");
    let Json::Arr(shapes) = field(replay, "shapes").expect("shapes") else {
        panic!("shape rows")
    };
    match field(&shapes[0], "key") {
        Some(Json::Str(key)) => assert_eq!(key, SHAPE_KEY),
        other => panic!("shape key: {other:?}"),
    }
    let Json::Arr(metrics) = field(replay, "metrics").expect("metrics") else {
        panic!("metric rows")
    };
    match field(&metrics[0], "key") {
        Some(Json::Str(key)) => assert_eq!(key, METRIC_KEY),
        other => panic!("metric key: {other:?}"),
    }
}

#[test]
fn version_evidence_locks_in_at_the_first_real_value() {
    let compact = compact_snapshot_manifest(&entries_b(), &metadata()).expect("compact B");
    assert_eq!(compact.render(), COMPACT_B);
}

#[test]
fn version_evidence_conflicts_after_locking_in() {
    let p1 = entry_text("p1", "typo-a", "", BACKEND_7);
    let p2 = entry_text("p2", "typo-a", "", "");
    let entries = parse_json(&format!("[{p1},{p2}]")).expect("entries parse");
    let error = error_name(compact_snapshot_manifest(&entries, &metadata()));
    assert_eq!(error, "SnapshotFontEvidenceVersionConflict");
}

#[test]
fn empty_entries_keep_null_versions() {
    let empty = parse_json("[]").expect("empty entries");
    let metadata = parse_json("{}").expect("empty metadata");
    let compact = compact_snapshot_manifest(&empty, &metadata).expect("compact empty");
    assert_eq!(compact.render(), COMPACT_EMPTY);
}

/// js leaves `undefined` fields out of the wire objects; explicit nulls stay.
/// The first face misses coverageText and probe, the second carries nulls,
/// and the entry misses every optional passthrough field.
#[test]
fn compact_omits_missing_fields_and_keeps_explicit_nulls() {
    let entry = r#"{"key":"p1","fontEvidence":{"backendRevision":"b1","faces":[{"family":"F","weight":400},{"family":"G","coverageText":null,"probe":null}],"replay":{"revision":"tiqian-server-shaping-replay-v1","shapes":[],"metrics":[]}}}"#;
    let entries = parse_json(&format!("[{entry}]")).expect("entries parse");
    let metadata = parse_json(r#"{"schema":1}"#).expect("metadata parses");
    let compact = compact_snapshot_manifest(&entries, &metadata).expect("compact sparse");
    assert_eq!(
        compact.render(),
        r#"{"schema":1,"typographies":[{}],"fontEvidence":{"backendRevision":"b1","faces":[{"family":"F","weight":400},{"family":"G"}]},"fontReplay":{"revision":"tiqian-server-shaping-replay-v1","encoding":"shared-strings-v1","strings":[],"shapes":[],"metrics":[]},"entries":[{"key":"p1","typographyRef":0,"fontFaceEvidence":[{"faceRef":0},{"faceRef":1,"coverageText":null,"probe":null}]}]}"#
    );
}

#[test]
fn compact_reports_replay_damage_with_js_issue_names() {
    let short_metrics =
        r#"{"key":"[\"Tiqian Han\",400,false,\"body\",\"永\"]","valuesEm":[1,2,3,4]}"#;
    let entry = entry_text_with(SHAPE_ITEM, short_metrics, "p1", "typo-a", "", BACKEND_7);
    let entries = parse_json(&format!("[{entry}]")).expect("entries parse");
    assert_eq!(
        error_name(compact_snapshot_manifest(&entries, &metadata())),
        "SnapshotFontReplayMetricsInvalid"
    );

    let bad_bounds = r#"{"key":"[\"排\",\"Tiqian Han\",400,false,\"zh-Hans\",\"body\",\"排\"]","result":{"faceId":"face-1","fontInstanceId":"fi-1","script":"hani","features":[],"unsafeBreakCount":0,"advanceEm":1,"glyphs":[{"id":1,"boundsEm":[1,2,3]}]}}"#;
    let entry = entry_text_with(bad_bounds, METRIC_ITEM, "p1", "typo-a", "", BACKEND_7);
    let entries = parse_json(&format!("[{entry}]")).expect("entries parse");
    assert_eq!(
        error_name(compact_snapshot_manifest(&entries, &metadata())),
        "SnapshotFontReplayGlyphBoundsInvalid"
    );

    let conflicting = r#"{"key":"[\"排\",\"Tiqian Han\",400,false,\"zh-Hans\",\"body\",\"排\"]","result":{"faceId":"face-1","fontInstanceId":"fi-1","script":"hani","features":["pwid","palt"],"unsafeBreakCount":0,"advanceEm":2000,"glyphs":[]}}"#;
    let other = r#"{"key":"[\"版\",\"Tiqian Han\",400,false,\"zh-Hans\",\"body\",\"版\"]","result":{"faceId":"face-1","fontInstanceId":"fi-1","script":"hani","features":["pwid","palt"],"unsafeBreakCount":0,"advanceEm":1000,"glyphs":[]}}"#;
    let p1 = entry_text("p1", "typo-a", "", BACKEND_7);
    let p2 = entry_text_with(
        &format!("{other},{conflicting}"),
        METRIC_ITEM,
        "p2",
        "typo-a",
        "",
        BACKEND_7,
    );
    let entries = parse_json(&format!("[{p1},{p2}]")).expect("entries parse");
    assert_eq!(
        error_name(compact_snapshot_manifest(&entries, &metadata())),
        "SnapshotFontReplayShapeConflict"
    );

    let empty_faces = r#"{"key":"p1","sourceSha256":"sha-p1","typographySha256":"typ-typo-a","typography":{"value":"typo-a","lineHeight":1.6},"maxWidthPx":320,"fontEvidence":{"faces":[]},"renderArtifactSha256":"render-p1"}"#;
    let entries = parse_json(&format!("[{empty_faces}]")).expect("entries parse");
    assert_eq!(
        error_name(compact_snapshot_manifest(&entries, &metadata())),
        "SnapshotFontEvidenceInvalid:p1"
    );
}

#[test]
fn expand_reports_transport_damage_with_js_issue_names() {
    let cases: Vec<(&str, Box<dyn FnOnce(&mut Json)>)> = vec![
        (
            "SnapshotFontReplayStringReferenceInvalid",
            Box::new(|m: &mut Json| {
                edit_shape_row(m, |cells| cells[0] = Json::Num(99.0));
            }),
        ),
        (
            "SnapshotFontReplayShapeTransportInvalid",
            Box::new(|m: &mut Json| {
                edit_shape_row(m, |cells| cells[3] = Json::Num(2.0));
            }),
        ),
        (
            "SnapshotFontReplayShapeTransportInvalid",
            Box::new(|m: &mut Json| {
                edit_shape_row(m, |cells| {
                    cells.pop();
                });
            }),
        ),
        (
            "SnapshotFontReplayGlyphBoundsInvalid",
            Box::new(|m: &mut Json| {
                edit_shape_row(m, |cells| {
                    cells[13] = Json::Arr(vec![
                        Json::Num(1.0),
                        Json::Num(100.0),
                        Json::Num(50.0),
                        Json::Null,
                        Json::Null,
                        Json::Num(500.0),
                        Json::Num(0.0),
                        Json::Num(700.0),
                    ]);
                });
            }),
        ),
        (
            "SnapshotFontReplayMetricsTransportInvalid",
            Box::new(|m: &mut Json| {
                edit_metric_row(m, |cells| {
                    cells.pop();
                });
            }),
        ),
        (
            "SnapshotTypographyReferenceInvalid",
            Box::new(|m: &mut Json| {
                edit_entry(m, 0, |entry| {
                    obj_field_mut(entry, "typographyRef").map(|v| *v = Json::Num(9.0));
                });
            }),
        ),
        (
            "SnapshotFontEvidenceReferenceInvalid",
            Box::new(|m: &mut Json| {
                edit_entry(m, 0, |entry| {
                    obj_field_mut(entry, "fontFaceEvidence").map(|v| *v = Json::Arr(vec![]));
                });
            }),
        ),
        (
            "SnapshotFontFaceReferenceInvalid",
            Box::new(|m: &mut Json| {
                edit_entry(m, 0, |entry| {
                    if let Some(Json::Arr(list)) = obj_field_mut(entry, "fontFaceEvidence") {
                        if let Some(first) = list.get_mut(0) {
                            obj_field_mut(first, "faceRef").map(|v| *v = Json::Num(9.0));
                        }
                    }
                });
            }),
        ),
    ];
    for (expected, edit) in cases {
        let mut manifest = compact_a();
        edit(&mut manifest);
        assert_eq!(error_name(expand_snapshot_manifest(&manifest)), expected);
    }
}

#[test]
fn expand_rejects_non_object_manifests_and_missing_tables() {
    let array = parse_json("[1]").expect("array manifest");
    assert_eq!(
        error_name(expand_snapshot_manifest(&array)),
        "SnapshotManifestInvalid"
    );
    let bare = parse_json(r#"{"entries":[]}"#).expect("bare manifest");
    assert_eq!(
        error_name(expand_snapshot_manifest(&bare)),
        "SnapshotManifestTablesInvalid"
    );
}

#[test]
fn font_contract_entries_expand_through_the_same_tables() {
    let mut manifest = compact_a();
    let first_entry = match obj_field_mut(&mut manifest, "entries") {
        Some(Json::Arr(list)) => list[0].clone(),
        _ => panic!("entries"),
    };
    let Json::Obj(mut fields) = manifest else {
        panic!("compact manifest")
    };
    fields.push((
        "fontContractEntries".to_string(),
        Json::Arr(vec![first_entry]),
    ));
    let manifest = Json::Obj(fields);
    let expanded = expand_snapshot_manifest(&manifest).expect("expand contract");
    assert_eq!(expanded.render(), EXPAND_CONTRACT);
}

#[test]
fn canonical_replay_passes_through_without_an_encoding() {
    let canonical_replay = {
        let expanded = expand_snapshot_manifest(&compact_a()).expect("expand A");
        field(&expanded, "fontReplay").expect("fontReplay").clone()
    };
    let mut manifest = compact_a();
    if let Some(replay) = obj_field_mut(&mut manifest, "fontReplay") {
        *replay = canonical_replay.clone();
    }
    let expanded = expand_snapshot_manifest(&manifest).expect("expand canonical");
    assert_eq!(
        field(&expanded, "fontReplay").expect("fontReplay").render(),
        canonical_replay.render()
    );
    // Entries still expand through the shared tables.
    match field(&expanded, "entries") {
        Some(Json::Arr(list)) => assert!(field(&list[0], "typography").is_some()),
        _ => panic!("entries"),
    }
}

#[test]
fn parse_expands_the_wire_text() {
    let parsed = parse_snapshot_manifest(COMPACT_A).expect("parse compact A");
    assert_eq!(parsed.render(), EXPAND_A);
    let error = error_name(parse_snapshot_manifest("{"));
    assert!(
        error.starts_with("InvalidSnapshotManifestJson:"),
        "unexpected parse error name: {error}"
    );
}
