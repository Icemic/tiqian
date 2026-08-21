//! Precompute-html parity against the js oracle (ADR 0050 amendment
//! `PrecomputeInRust`). The oracle is `createHtmlPreparer` from
//! `frontend/web/npm/precompute-html.js` over the fixture font; the matrix
//! lives in this file, is written to cases.json for the node oracle, and is
//! walked in the same order by both harnesses so session usage accumulation
//! matches. Every case dumps `stableStringify(result)` (or `ERROR:<message>`
//! for throws). The one exempt engine-identity field,
//! `fontEvidence.harfbuzzVersion`, is aligned before the byte comparison.
//! The engine archive must be linked.
//!
//! The matrix sticks to inputs where linkedom and html5ever build the same
//! tree. `<p>` inside `<select>`, fostered table content, and markup after
//! `<plaintext>` diverge between the two parsers; prose hosts do not produce
//! them and the mismatch/order errors they induce stay out of parity scope.

#![cfg(tiqian_engine_link)]

use std::path::PathBuf;
use std::process::Command;

use tiqian_precompute::font_record::{FontFaceSpec, FontWeightSpec};
use tiqian_precompute::json::Json;
use tiqian_precompute::normalize::TypographyInput;
use tiqian_precompute::precompute_html::{
    create_html_preparer, HtmlPrepareOptions, HtmlPreparerOptions,
    HtmlProjectionContext, SnapshotParagraphProjector, SnapshotProjection,
};
use tiqian_precompute::precomputer::PrecomputerOptions;
use tiqian_precompute::schema::stable_stringify;
use tiqian_precompute::session::SessionFaceSpec;

const BAD_SELECTOR: &str = "p div";

fn dela_gothic_path() -> Option<PathBuf> {
    let home = std::env::var("HOME").ok()?;
    let path = PathBuf::from(home).join(".local/share/fonts/DelaGothicOne-Regular.ttf");
    path.is_file().then_some(path)
}

fn repo_root() -> PathBuf {
    // frontend/web-precompute/rust/tiqian-precompute -> repo root
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../../../")
}

/// The projection callback of the projector lane, mirroring the hardcoded
/// oracle projector: paragraphs whose source text contains "链" get empty
/// override arrays, everything else declines.
struct MatrixProjector;

impl SnapshotParagraphProjector for MatrixProjector {
    fn project(&mut self, context: HtmlProjectionContext) -> Option<SnapshotProjection> {
        if !context.source_text.contains('链') {
            return None;
        }
        Some(SnapshotProjection {
            semantics: Some(Json::Arr(Vec::new())),
            text_spans: Some(Json::Arr(Vec::new())),
            inline_boxes: Some(Json::Arr(Vec::new())),
            source_boundaries: Some(Json::Arr(Vec::new())),
        })
    }
}

fn case(name: &str, html: &str, width: Option<Json>) -> Json {
    let mut fields = vec![
        ("name".to_string(), Json::str(name)),
        ("html".to_string(), Json::str(html)),
    ];
    if let Some(width) = width {
        fields.push(("snapshotMaxWidthPx".to_string(), width));
    }
    Json::Obj(fields)
}

fn closing_case(name: &str, html: &str, width: Option<Json>) -> Json {
    let Json::Obj(mut fields) = case(name, html, width) else {
        unreachable!("case builds an object");
    };
    fields.push(("close".to_string(), Json::Bool(true)));
    Json::Obj(fields)
}

fn case_matrix() -> (Vec<Json>, Vec<Json>) {
    let width = || Some(Json::Num(144.0));
    let main = vec![
        case("plainFixed", "<p>中文文字排版段落</p>", width()),
        case("plainFree", "<p>中文文字排版段落</p>", None),
        case(
            "twoParagraphs",
            "<p>中文段落文字</p><p>第二段落排版</p>",
            width(),
        ),
        case(
            "anchorWidth",
            "<p>中文<a href=\"https://example.com\">链接</a>排版</p>",
            width(),
        ),
        case("brHardBreak", "<p>中文<br>排版</p>", width()),
        case(
            "listItems",
            "<ul><li>列表项目</li><li><p>嵌套项目</p></li></ul>",
            width(),
        ),
        case(
            "skippedAncestor",
            "<div data-tiqian-skip><p>跳过段落</p></div><p>保留段落</p>",
            width(),
        ),
        case("bundleNull", "<div data-tiqian-skip><p>跳过段落</p></div>", width()),
        case(
            "templateLiteral",
            "<template><p>模板文字</p></template><p>排版段落</p>",
            width(),
        ),
        case(
            "scriptLiteral",
            "<script>var s = \"<p>脚本假段落</p>\";</script><p>排版段落</p>",
            width(),
        ),
        case("commentLiteral", "<!-- <p>注释假段落</p> --><p>排版段落</p>", width()),
        case(
            "noscriptLiteral",
            "<noscript><p>无脚本段落</p></noscript><p>排版段落</p>",
            width(),
        ),
        case(
            "iframeLiteral",
            "<iframe srcdoc=\"&lt;p&gt;框架段落&lt;/p&gt;\"></iframe><p>排版段落</p>",
            width(),
        ),
        case(
            "quotedAttrLiteral",
            "<p title=\"<p>属性假段落</p>\">中文排版</p>",
            width(),
        ),
        case("emptyParagraph", "<p>  </p><p>中文</p>", width()),
        case("emojiIssue", "<p>中🦀文</p>", width()),
        explicit_id_case(),
        case("anchorFree", "<p>中文<a href=\"https://example.com\">链接</a>排版</p>", None),
        case("invalidWidth", "<p>中文</p>", Some(Json::Num(0.0))),
        case("nanWidth", "<p>中文</p>", Some(Json::str("abc"))),
        closing_case("closedPreparer", "<p>中文</p>", width()),
    ];
    let projector = vec![case(
        "projectorOverride",
        "<p>中文<a href=\"https://example.com\">链接</a>排版</p>",
        width(),
    )];
    (main, projector)
}

/// The explicitId case in the plain builder shape: id then width.
fn explicit_id_case() -> Json {
    Json::Obj(vec![
        ("name".to_string(), Json::str("explicitId")),
        ("html".to_string(), Json::str("<p>中文标识</p>")),
        ("id".to_string(), Json::str("custom-root-id")),
        ("snapshotMaxWidthPx".to_string(), Json::Num(144.0)),
    ])
}

fn plan_json(font_path: &str) -> Json {
    let (main, projector) = case_matrix();
    Json::Obj(vec![
        ("fontPath".to_string(), Json::str(font_path)),
        ("badSelector".to_string(), Json::str(BAD_SELECTOR)),
        ("mainCases".to_string(), Json::Arr(main)),
        ("projectorCases".to_string(), Json::Arr(projector)),
    ])
}

fn dump_line(name: &str, result: Result<Json, String>) -> String {
    match result {
        Ok(entry) => format!("{name}\t{}", stable_stringify(&entry)),
        Err(message) => format!("{name}\t{message}"),
    }
}

fn prepare_options(entry: &Json) -> (Option<String>, Option<Json>) {
    let Json::Obj(fields) = entry else {
        panic!("case object");
    };
    let member = |key: &str| {
        fields
            .iter()
            .find(|(name, _)| name == key)
            .map(|(_, value)| value.clone())
    };
    let id = member("id").map(|value| match value {
        Json::Str(id) => id,
        _ => panic!("case id is a string"),
    });
    (id, member("snapshotMaxWidthPx"))
}

fn run_rust_side(font_bytes: &[u8], plan: &Json) -> Vec<String> {
    let typography = TypographyInput {
        font_families: Some(vec!["Dela Gothic One".to_string()]),
        font_size_px: Some(18.0),
        line_height_px: Some(27.0),
        ..Default::default()
    };
    let faces = vec![SessionFaceSpec {
        spec: FontFaceSpec {
            family: "Dela Gothic One",
            public_url: "/fonts/DelaGothicOne-Regular.ttf",
            source: font_bytes,
            face_index: None,
            weight: FontWeightSpec::Single(Some(400.0)),
            style: "normal",
            unicode_range: None,
            source_order: 0,
        },
        source_order: Some(0.0),
    }];
    let create = PrecomputerOptions::new(typography.clone(), faces.clone());
    let mut lines = Vec::new();

    let bad = create_html_preparer(HtmlPreparerOptions {
        precomputer: None,
        create: PrecomputerOptions::new(typography.clone(), faces.clone()),
        paragraph_selector: Some(BAD_SELECTOR),
        skipped_ancestor_selector: None,
        shared_runtime_style: "",
        projector: None,
    });
    match bad {
        Err(error) => lines.push(format!("badSelector\tERROR:{}", error.0)),
        Ok(_) => panic!("bad selector creation unexpectedly succeeded"),
    }

    let style_path = repo_root().join("frontend/web/npm/styles.css");
    let shared_style = std::fs::read_to_string(&style_path).expect("npm styles.css reads");
    let mut main = create_html_preparer(HtmlPreparerOptions {
        precomputer: None,
        create,
        paragraph_selector: None,
        skipped_ancestor_selector: None,
        shared_runtime_style: &shared_style,
        projector: None,
    })
    .expect("main preparer builds");
    let Json::Obj(plan_fields) = plan else {
        panic!("plan object");
    };
    let main_cases = plan_fields
        .iter()
        .find(|(name, _)| name == "mainCases")
        .map(|(_, value)| value.clone())
        .expect("mainCases present");
    let Json::Arr(main_cases) = main_cases else {
        panic!("mainCases array");
    };
    for entry in &main_cases {
        let Json::Obj(fields) = entry else {
            panic!("case object");
        };
        let closes = fields
            .iter()
            .any(|(name, value)| name == "close" && matches!(value, Json::Bool(true)));
        if closes {
            main.close();
        }
        let name = fields
            .iter()
            .find(|(key, _)| key == "name")
            .and_then(|(_, value)| match value { Json::Str(name) => Some(name.clone()), _ => None })
            .expect("case name");
        let html = fields
            .iter()
            .find(|(key, _)| key == "html")
            .and_then(|(_, value)| match value { Json::Str(html) => Some(html.clone()), _ => None })
            .expect("case html");
        let (id, width) = prepare_options(entry);
        let options = HtmlPrepareOptions {
            id: id.as_deref(),
            snapshot_max_width_px: width.as_ref(),
        };
        let result = main
            .prepare(&html, &options)
            .map_err(|error| format!("ERROR:{}", error.0));
        lines.push(dump_line(&name, result));
    }

    let mut projecting = create_html_preparer(HtmlPreparerOptions {
        precomputer: None,
        create: PrecomputerOptions::new(typography, faces),
        paragraph_selector: None,
        skipped_ancestor_selector: None,
        shared_runtime_style: &shared_style,
        projector: Some(Box::new(MatrixProjector)),
    })
    .expect("projector preparer builds");
    let projector_cases = plan_fields
        .iter()
        .find(|(name, _)| name == "projectorCases")
        .map(|(_, value)| value.clone())
        .expect("projectorCases present");
    let Json::Arr(projector_cases) = projector_cases else {
        panic!("projectorCases array");
    };
    for entry in &projector_cases {
        let Json::Obj(fields) = entry else {
            panic!("case object");
        };
        let name = fields
            .iter()
            .find(|(key, _)| key == "name")
            .and_then(|(_, value)| match value { Json::Str(name) => Some(name.clone()), _ => None })
            .expect("case name");
        let html = fields
            .iter()
            .find(|(key, _)| key == "html")
            .and_then(|(_, value)| match value { Json::Str(html) => Some(html.clone()), _ => None })
            .expect("case html");
        let (id, width) = prepare_options(entry);
        let options = HtmlPrepareOptions {
            id: id.as_deref(),
            snapshot_max_width_px: width.as_ref(),
        };
        let result = projecting
            .prepare(&html, &options)
            .map_err(|error| format!("ERROR:{}", error.0));
        lines.push(dump_line(&name, result));
    }
    projecting.close();
    lines
}

/// Rewrites every `harfbuzzVersion` value to a common placeholder; the js
/// side reports the wasm HarfBuzz version, the Rust stack names its own.
/// The bundle embeds some evidence as stringified JSON, so both the plain
/// and the escaped form need the rewrite.
fn normalize_engine_versions(dump: &str) -> String {
    rewrite_version_values(
        &rewrite_version_values(dump, "\\\"harfbuzzVersion\\\":\\\"", "\\\""),
        "\"harfbuzzVersion\":\"",
        "\"",
    )
}

fn rewrite_version_values(dump: &str, marker: &str, terminator: &str) -> String {
    let mut out = String::with_capacity(dump.len());
    let mut rest = dump;
    while let Some(position) = rest.find(marker) {
        out.push_str(&rest[..position + marker.len()]);
        let after = &rest[position + marker.len()..];
        match after.find(terminator) {
            Some(end) => {
                out.push_str("<engine>");
                rest = &after[end..];
            }
            None => {
                out.push_str(after);
                rest = "";
            }
        }
    }
    out.push_str(rest);
    out
}

#[test]
fn precompute_html_matches_the_js_oracle() {
    let Some(font_path) = dela_gothic_path() else {
        eprintln!("skipped: DelaGothicOne-Regular.ttf absent under $HOME");
        return;
    };
    let font_bytes = std::fs::read(&font_path).expect("fixture font reads");
    let plan = plan_json(font_path.to_str().expect("font path is utf8"));

    let workdir = std::env::temp_dir().join("tiqian-precompute-html-parity");
    std::fs::create_dir_all(&workdir).expect("workdir creates");
    let cases_path = workdir.join("cases.json");
    std::fs::write(&cases_path, plan.render()).expect("cases.json writes");

    let oracle = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("tests/oracle/precompute_html_oracle.mjs");
    let oracle_run = Command::new("node")
        .arg(&oracle)
        .arg(&cases_path)
        .arg(repo_root())
        .output()
        .expect("node spawns");
    if !oracle_run.status.success() {
        panic!(
            "precompute-html oracle failed:\n{}",
            String::from_utf8_lossy(&oracle_run.stderr)
        );
    }
    let js_dump = String::from_utf8_lossy(&oracle_run.stdout).trim().to_string();
    let js_lines: Vec<String> = js_dump
        .lines()
        .map(|line| normalize_engine_versions(line))
        .collect();
    let rust_lines: Vec<String> = run_rust_side(&font_bytes, &plan)
        .into_iter()
        .map(|line| normalize_engine_versions(&line))
        .collect();

    if js_lines.len() != rust_lines.len() {
        panic!(
            "line count differs: js {} rust {}",
            js_lines.len(),
            rust_lines.len()
        );
    }
    let mut failed = false;
    for (js_line, rust_line) in js_lines.iter().zip(rust_lines.iter()) {
        if js_line == rust_line {
            continue;
        }
        failed = true;
        let name = js_line.split('\t').next().unwrap_or("?");
        let common = js_line
            .bytes()
            .zip(rust_line.bytes())
            .take_while(|(left, right)| left == right)
            .count();
        let window = common.saturating_sub(60);
        eprintln!(
            "case {name} differs near byte {common}:\n  js:   {}\n  rust: {}",
            &js_line[window..(common + 120).min(js_line.len())],
            &rust_line[window..(common + 120).min(rust_line.len())],
        );
    }
    if failed {
        panic!("precompute-html dump differs from the js oracle");
    }
}
