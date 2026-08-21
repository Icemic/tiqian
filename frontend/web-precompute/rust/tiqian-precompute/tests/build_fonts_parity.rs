//! Build-font stylesheet parity against the js oracle (ADR 0050 amendment
//! `PrecomputeInRust`). The oracle is `parseBuildFontStylesheet` from
//! `frontend/web/npm/precompute-node-fonts.js`; the matrix lives in this
//! file, is written to cases.json for the node oracle, and produces one
//! `name\tdump` line per case on both sides (stableStringify of the face
//! list, or `ERROR:<message>` for throws). Every case names its stylesheet
//! as a `file:` URL string, so Node path resolution stays out of the
//! comparison. Pure parsing; the engine link is not required.

use std::path::PathBuf;
use std::process::Command;

use tiqian_precompute::build_fonts::parse_build_font_stylesheet;
use tiqian_precompute::font_record::FontWeightSpec;
use tiqian_precompute::json::Json;
use tiqian_precompute::schema::stable_stringify;

const SOURCE_URL: &str = "file:///srv/styles/main.css";

fn case(name: &str, css: &str, public_url: Option<&str>) -> Json {
    let mut fields = vec![
        ("name".to_string(), Json::str(name)),
        ("css".to_string(), Json::str(css)),
        ("source".to_string(), Json::str(SOURCE_URL)),
    ];
    if let Some(public_url) = public_url {
        fields.push(("publicUrl".to_string(), Json::str(public_url)));
    }
    Json::Obj(fields)
}

fn case_matrix() -> Vec<Json> {
    vec![
        case(
            "simpleFace",
            "@font-face { font-family: Test; src: url(font.woff2); }",
            Some("/styles/main.css"),
        ),
        case(
            "quotedFamilyAndUrl",
            "@font-face { font-family: 'Dela Gothic One'; src: url('font.ttf'); }",
            Some("https://cdn.example/styles/main.css"),
        ),
        case(
            "subdirTraversal",
            "@font-face { font-family: Test; src: url(../fonts/f.woff2); }",
            Some("/styles/main.css"),
        ),
        case(
            "absoluteAssetPath",
            "@font-face { font-family: Test; src: url(/assets/f.woff2); }",
            Some("/styles/main.css"),
        ),
        case(
            "queryHash",
            "@font-face { font-family: Test; src: url(font.woff2?v=3#h); }",
            Some("/styles/main.css"),
        ),
        case(
            "weightRange",
            "@font-face { font-family: T; src: url(f); font-weight: 300 500; }",
            Some("/styles/main.css"),
        ),
        case(
            "propertyCasing",
            "@font-face { FONT-FAMILY: T; SRC: url(f); FONT-WEIGHT: 700; }",
            Some("/styles/main.css"),
        ),
        case(
            "missingFamily",
            "@font-face { src: url(f); }",
            Some("/styles/main.css"),
        ),
        case(
            "localSourceOnly",
            "@font-face { font-family: T; src: local(T); }",
            Some("/styles/main.css"),
        ),
        case(
            "badWeight",
            "@font-face { font-family: T; src: url(f); font-weight: bold; }",
            Some("/styles/main.css"),
        ),
        case(
            "descendingWeight",
            "@font-face { font-family: T; src: url(f); font-weight: 500 300; }",
            Some("/styles/main.css"),
        ),
        case(
            "negativeWeight",
            "@font-face { font-family: T; src: url(f); font-weight: -400; }",
            Some("/styles/main.css"),
        ),
        case(
            "italicStyleUpper",
            "@font-face { font-family: T; src: url(f); font-style: ITALIC; }",
            Some("/styles/main.css"),
        ),
        case(
            "obliqueStyle",
            "@font-face { font-family: T; src: url(f); font-style: oblique; }",
            Some("/styles/main.css"),
        ),
        case(
            "unicodeRangePresent",
            "@font-face { font-family: T; src: url(f); unicode-range: U+4E00-9FFF; }",
            Some("/styles/main.css"),
        ),
        case(
            "commentedOutRule",
            "/* @font-face { font-family: Hidden; src: url(h); } */\n@font-face { font-family: T; src: url(f); }",
            Some("/styles/main.css"),
        ),
        case(
            "inlineComment",
            "@font-face { /* font-family: Hidden; */ font-family: T; src: url(f); }",
            Some("/styles/main.css"),
        ),
        case(
            "plainRulesOnly",
            "p { color: red; }",
            Some("/styles/main.css"),
        ),
        case(
            "noPublicUrlRelative",
            "@font-face { font-family: T; src: url(font.woff2); }",
            None,
        ),
        case(
            "schemeAssetUrl",
            "@font-face { font-family: T; src: url(https://cdn.example/f.woff2); }",
            Some("/styles/main.css"),
        ),
        case(
            "spacesInUrl",
            "@font-face { font-family: T; src: url( \"my font.ttf\" ); }",
            Some("/styles/main.css"),
        ),
        case(
            "firstUrlWins",
            "@font-face { font-family: T; src: url(a.woff2) format(\"woff2\"), url(b.ttf); }",
            Some("/styles/main.css"),
        ),
        case(
            "uppercaseAtRule",
            "@FONT-FACE { font-family: T; src: url(f); }",
            Some("/styles/main.css"),
        ),
        case(
            "noSpaceBeforeBrace",
            "@font-face{font-family:T;src:url(f)}",
            Some("/styles/main.css"),
        ),
        case(
            "twoFaces",
            "@font-face { font-family: A; src: url(a); }\n@font-face { font-family: B; src: url(b); font-weight: 550; }",
            Some("/styles/main.css"),
        ),
        case(
            "driveLetterAsset",
            "@font-face { font-family: T; src: url(C:/fonts/f.ttf); }",
            Some("/styles/main.css"),
        ),
        case(
            "decimalWeight",
            "@font-face { font-family: T; src: url(f); font-weight: 400.5; }",
            Some("/styles/main.css"),
        ),
    ]
}

fn weight_json(weight: &FontWeightSpec) -> Json {
    match weight {
        FontWeightSpec::Single(Some(value)) => Json::Num(*value),
        FontWeightSpec::Single(None) => Json::Null,
        FontWeightSpec::Range(low, high) => Json::Arr(vec![Json::Num(*low), Json::Num(*high)]),
    }
}

fn run_rust_side(cases: &[Json]) -> Vec<String> {
    cases
        .iter()
        .map(|entry| {
            let Json::Obj(fields) = entry else {
                panic!("case object");
            };
            let member = |key: &str| {
                fields
                    .iter()
                    .find(|(name, _)| name == key)
                    .map(|(_, value)| value.clone())
            };
            let name = match member("name") {
                Some(Json::Str(name)) => name,
                _ => panic!("case name"),
            };
            let css = match member("css") {
                Some(Json::Str(css)) => css,
                _ => panic!("case css"),
            };
            let source = match member("source") {
                Some(Json::Str(source)) => source,
                _ => panic!("case source"),
            };
            let public_url = match member("publicUrl") {
                Some(Json::Str(value)) => Some(value),
                _ => None,
            };
            let result = parse_build_font_stylesheet(&css, &source, public_url.as_deref());
            match result {
                Ok(faces) => {
                    let dumped = Json::Arr(
                        faces
                            .iter()
                            .map(|face| {
                                Json::Obj(vec![
                                    ("family".to_string(), Json::str(face.family.clone())),
                                    ("source".to_string(), Json::str(face.source_path.clone())),
                                    ("publicUrl".to_string(), Json::str(face.public_url.clone())),
                                    ("weight".to_string(), weight_json(&face.weight)),
                                    ("style".to_string(), Json::str(face.style.clone())),
                                    (
                                        "unicodeRange".to_string(),
                                        Json::str(face.unicode_range.clone()),
                                    ),
                                ])
                            })
                            .collect(),
                    );
                    format!("{name}\t{}", stable_stringify(&dumped))
                }
                Err(error) => format!("{name}\tERROR:{}", error.0),
            }
        })
        .collect()
}

#[test]
fn build_font_stylesheet_matches_the_js_oracle() {
    let cases = case_matrix();
    let workdir = std::env::temp_dir().join("tiqian-build-fonts-parity");
    std::fs::create_dir_all(&workdir).expect("workdir creates");
    let cases_path = workdir.join("cases.json");
    std::fs::write(&cases_path, Json::Arr(cases.clone()).render()).expect("cases.json writes");

    let oracle =
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/oracle/build_fonts_oracle.mjs");
    let repo_root = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../../../");
    let oracle_run = Command::new("node")
        .arg(&oracle)
        .arg(&cases_path)
        .arg(&repo_root)
        .output()
        .expect("node spawns");
    if !oracle_run.status.success() {
        panic!(
            "build-fonts oracle failed:\n{}",
            String::from_utf8_lossy(&oracle_run.stderr)
        );
    }
    let js_dump = String::from_utf8_lossy(&oracle_run.stdout)
        .trim()
        .to_string();
    let js_lines: Vec<&str> = js_dump.lines().collect();
    let rust_lines = run_rust_side(&cases);

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
        eprintln!("js:   {js_line}\nrust: {rust_line}");
    }
    if failed {
        panic!("build-font stylesheet dump differs from the js oracle");
    }
}
