// Snapshot bundle parity (ADR 0050).
//
// Template bytes, first-paint style bytes, and error names come from the js
// oracle `frontend/web/npm/precompute.js` (renderSnapshotBundle,
// renderFontContractBundle, renderSnapshotTemplate) run with node against the
// same entry fixtures. Snapshot bundles record the initial style as the bytes
// appended after the shared runtime style, which this crate takes as a caller
// parameter; font contract bundles carry the full (short) initial style.

use tiqian::NamedError;
use tiqian_precompute::json::{parse_json, Json};
use tiqian_precompute::snapshot_bundle::{
    render_font_contract_bundle, render_snapshot_bundle, render_snapshot_template, SnapshotBundle,
    SnapshotBundleOptions, PLAIN_PARAGRAPH_SELECTOR, RUNTIME_PARAGRAPH_SELECTOR,
};

const SHARED_STYLE: &str = "/*shared-runtime-style*/";

const ENTRY_A: &str = r###"{"status":"prepared","schema":1,"layoutRevision":"tiqian-layout-v2","renderRevision":"prebroken-dom-v15","key":"p-1","sourceText":"正文","sourceSha256":"d661c3d96d53ebc0ca8a55aae24b5df4a4d1bf28d37337b982fe8ebf54846eeb","sourceArtifactSha256":"022be43d07155ae6136f2cec8d5a4054bbe28c175f11bfa4e31bc89c221ce73d","semantics":[{"start":0,"end":2,"tagName":"strong","attributes":[]}],"inlineBoxes":[],"renderTextSpans":[],"typography":{"fontFamilies":["Fixture CJK"],"fontSizePx":18,"lineHeightPx":27,"locale":"zh-Hans","fontWeight":400,"italic":false,"firstLineIndentIc":0,"lineLengthGridEnabled":true,"letterSpacingPx":0,"fontFeatureSettings":"normal","fontVariationSettings":"normal","fontVariantNumeric":"normal"},"renderFontFamilies":["Snapshot Sans"],"typographySha256":"c21cdb8a7e87a74ea0133ebef2938e3741095446d97278621f940d1f03a1eb68","maxWidthPx":360,"fontEvidence":{"backendRevision":"tiqian-shared-harfbuzz-v5","harfbuzzVersion":"harfrust-0.13.0","faces":[{"family":"Fixture CJK","publicUrl":"/fonts/f-a.woff2","coverageText":"正文","probe":{"text":"正"}}],"replay":{"revision":"tiqian-server-shaping-replay-v1","shapes":[],"metrics":[]}},"plan":{"schema":1,"layoutRevision":"tiqian-layout-v2","width":360,"height":27,"lines":[{"rangeStart":0,"rangeEnd":2,"top":0,"bottom":27,"baseline":20,"indent":0,"visualWidth":36,"hyphenAdvance":0,"endReason":"ParagraphEnd","cells":[{"rangeStart":0,"rangeEnd":2,"source":"正文","display":"正文","drawX":0,"naturalWidth":36,"leadingLayoutAdvance":0}]}]},"html":"<span>正文</span>","renderArtifactSha256":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"}"###;

const ENTRY_B: &str = r###"{"status":"prepared","schema":1,"layoutRevision":"tiqian-layout-v2","renderRevision":"prebroken-dom-v15","key":"p-2","sourceText":"后文","sourceSha256":"30bc60d288a5cf9c0e04bfd90351fa771abf3520b8f812a8123da21e19f39a0d","inlineBoxes":[],"renderTextSpans":[],"typography":{"fontFamilies":["Fixture CJK"],"fontSizePx":18,"lineHeightPx":27,"locale":"zh-Hans","fontWeight":400,"italic":false,"firstLineIndentIc":0,"lineLengthGridEnabled":true,"letterSpacingPx":0,"fontFeatureSettings":"normal","fontVariationSettings":"normal","fontVariantNumeric":"normal"},"renderFontFamilies":["Snapshot Sans"],"typographySha256":"c21cdb8a7e87a74ea0133ebef2938e3741095446d97278621f940d1f03a1eb68","maxWidthPx":360,"fontEvidence":{"backendRevision":"tiqian-shared-harfbuzz-v5","harfbuzzVersion":"harfrust-0.13.0","faces":[{"family":"Fixture CJK","publicUrl":"/fonts/f-a.woff2","coverageText":"后文","probe":{"text":"后"}}],"replay":{"revision":"tiqian-server-shaping-replay-v1","shapes":[],"metrics":[]}},"plan":{"schema":1,"layoutRevision":"tiqian-layout-v2","width":360,"height":27,"lines":[{"rangeStart":0,"rangeEnd":2,"top":0,"bottom":27,"baseline":20,"indent":0,"visualWidth":36,"hyphenAdvance":0,"endReason":"ParagraphEnd","cells":[{"rangeStart":0,"rangeEnd":2,"source":"后文","display":"后文","drawX":0,"naturalWidth":36,"leadingLayoutAdvance":0}]}]},"html":"<span>后文</span>","renderArtifactSha256":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"}"###;

const CONTRACT_FACE: &str = r###"{"status":"prepared","schema":1,"layoutRevision":"tiqian-layout-v2","renderRevision":"prebroken-dom-v15","key":"p-3","sourceText":"序言","sourceSha256":"d9dc1d2fbcde91ce50edb69f99f0f9ef7d00e31de655cd1884a761578b0ca8a0","inlineBoxes":[],"renderTextSpans":[],"typography":{"fontFamilies":["Fixture CJK"],"fontSizePx":18,"lineHeightPx":27,"locale":"zh-Hans","fontWeight":400,"italic":false,"firstLineIndentIc":0,"lineLengthGridEnabled":true,"letterSpacingPx":0,"fontFeatureSettings":"normal","fontVariationSettings":"normal","fontVariantNumeric":"normal"},"renderFontFamilies":["Snapshot Sans"],"typographySha256":"c21cdb8a7e87a74ea0133ebef2938e3741095446d97278621f940d1f03a1eb68","maxWidthPx":360,"fontEvidence":{"backendRevision":"tiqian-shared-harfbuzz-v5","harfbuzzVersion":"harfrust-0.13.0","faces":[{"family":"Fixture CJK","publicUrl":"/fonts/f-b.woff2","coverageText":"序言","probe":{"text":"序"}}],"replay":{"revision":"tiqian-server-shaping-replay-v1","shapes":[],"metrics":[]}},"plan":{"schema":1,"layoutRevision":"tiqian-layout-v2","width":360,"height":27,"lines":[{"rangeStart":0,"rangeEnd":2,"top":0,"bottom":27,"baseline":20,"indent":0,"visualWidth":36,"hyphenAdvance":0,"endReason":"ParagraphEnd","cells":[{"rangeStart":0,"rangeEnd":2,"source":"序言","display":"序言","drawX":0,"naturalWidth":36,"leadingLayoutAdvance":0}]}]},"html":"<span>序言</span>","renderArtifactSha256":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"}"###;

const TEMPLATE_A: &str = r###"<template id="tq-page" data-tq-snapshot-schema="1" data-tq-layout-revision="tiqian-layout-v2" data-tq-render-revision="prebroken-dom-v15" data-pagefind-ignore><script type="application/json" data-tq-snapshot-manifest>{"schema":1,"layoutRevision":"tiqian-layout-v2","renderRevision":"prebroken-dom-v15","fontSourcePolicy":"host-compatible-stylesheet-v1","paragraphSelector":":is(p, li)[data-tq-snapshot-key]","valueStyles":["--tq-line-height:27px!important;--tq-line-baseline-offset:-7px!important"],"valueStylesSha256":"e5c73b5c5ab5ca77bf2e85c8e56375b63c228b5f8036902279a2de249288c34b","renderFontFamilies":["Snapshot Sans"],"typographies":[{"sha256":"c21cdb8a7e87a74ea0133ebef2938e3741095446d97278621f940d1f03a1eb68","value":{"fontFamilies":["Fixture CJK"],"fontSizePx":18,"lineHeightPx":27,"locale":"zh-Hans","fontWeight":400,"italic":false,"firstLineIndentIc":0,"lineLengthGridEnabled":true,"letterSpacingPx":0,"fontFeatureSettings":"normal","fontVariationSettings":"normal","fontVariantNumeric":"normal"}}],"fontEvidence":{"backendRevision":"tiqian-shared-harfbuzz-v5","harfbuzzVersion":"harfrust-0.13.0","faces":[{"family":"Fixture CJK","publicUrl":"/fonts/f-a.woff2"}]},"fontReplay":{"revision":"tiqian-server-shaping-replay-v1","encoding":"shared-strings-v1","strings":[],"shapes":[],"metrics":[]},"entries":[{"key":"p-1","sourceSha256":"d661c3d96d53ebc0ca8a55aae24b5df4a4d1bf28d37337b982fe8ebf54846eeb","sourceArtifactSha256":"022be43d07155ae6136f2cec8d5a4054bbe28c175f11bfa4e31bc89c221ce73d","semantic":true,"typographyRef":0,"maxWidthPx":360,"fontFaceEvidence":[{"faceRef":0,"coverageText":"正文","probe":{"text":"正"}}],"renderArtifactSha256":"0ac0e6018b8b1608c4c3b37f152264734f0fbde1c043909a6681fdbeb3976024"},{"key":"p-2","sourceSha256":"30bc60d288a5cf9c0e04bfd90351fa771abf3520b8f812a8123da21e19f39a0d","typographyRef":0,"maxWidthPx":360,"fontFaceEvidence":[{"faceRef":0,"coverageText":"后文","probe":{"text":"后"}}],"renderArtifactSha256":"4eebfbda1851febae2783067bb4625f8af464c018b6ac569b8ce9b208e74a8c6"}]}</script><div data-tq-entry="p-1"><span aria-hidden="true" class="tq-line tqv-0" data-tq-copy-ignore="true" data-tq-geometry="true" data-tq-line-baseline="20" data-tq-line-bottom="27" data-tq-line-empty="false" data-tq-line-end="ParagraphEnd" data-tq-line-flow-width="36" data-tq-line-index="0" data-tq-line-range="0-2" data-tq-line-top="0" data-tq-line-width="36" data-tq-paragraph-height="27"></span><strong data-tq-source-semantic="true">正文</strong><span aria-hidden="true" data-tq-copy-ignore="true" data-tq-geometry="true" data-tq-line-end-sentinel="0"></span><span aria-hidden="true" data-tq-copy-ignore="true" data-tq-selection-end="true">​</span></div><div data-tq-entry="p-2"><span aria-hidden="true" class="tq-line tqv-0" data-tq-copy-ignore="true" data-tq-geometry="true" data-tq-line-baseline="20" data-tq-line-bottom="27" data-tq-line-empty="false" data-tq-line-end="ParagraphEnd" data-tq-line-flow-width="36" data-tq-line-index="0" data-tq-line-range="0-2" data-tq-line-top="0" data-tq-line-width="36" data-tq-paragraph-height="27"></span>后文<span aria-hidden="true" data-tq-copy-ignore="true" data-tq-geometry="true" data-tq-line-end-sentinel="0"></span><span aria-hidden="true" data-tq-copy-ignore="true" data-tq-selection-end="true">​</span></div></template>"###;

const CLIENT_TEMPLATE_A: &str = r###"<template id="tq-page" data-tq-snapshot-schema="1" data-tq-layout-revision="tiqian-layout-v2" data-tq-render-revision="prebroken-dom-v15" data-pagefind-ignore><script type="application/json" data-tq-snapshot-manifest>{"schema":1,"layoutRevision":"tiqian-layout-v2","renderRevision":"prebroken-dom-v15","fontSourcePolicy":"host-compatible-stylesheet-v1","paragraphSelector":":is(p, li)[data-tq-snapshot-key]","valueStyles":[],"valueStylesSha256":"4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945","renderFontFamilies":["Snapshot Sans"],"typographies":[{"sha256":"c21cdb8a7e87a74ea0133ebef2938e3741095446d97278621f940d1f03a1eb68","value":{"fontFamilies":["Fixture CJK"],"fontSizePx":18,"lineHeightPx":27,"locale":"zh-Hans","fontWeight":400,"italic":false,"firstLineIndentIc":0,"lineLengthGridEnabled":true,"letterSpacingPx":0,"fontFeatureSettings":"normal","fontVariationSettings":"normal","fontVariantNumeric":"normal"}}],"fontEvidence":{"backendRevision":"tiqian-shared-harfbuzz-v5","harfbuzzVersion":"harfrust-0.13.0","faces":[{"family":"Fixture CJK","publicUrl":"/fonts/f-a.woff2"}]},"fontReplay":{"revision":"tiqian-server-shaping-replay-v1","encoding":"shared-strings-v1","strings":[],"shapes":[],"metrics":[]},"entries":[{"key":"font-contract-0","sourceSha256":"0000000000000000000000000000000000000000000000000000000000000000","typographyRef":0,"maxWidthPx":1,"fontFaceEvidence":[{"faceRef":0,"coverageText":"正文后","probe":{"text":"正"}}],"renderArtifactSha256":"0000000000000000000000000000000000000000000000000000000000000000"},{"key":"font-contract-1","sourceSha256":"0000000000000000000000000000000000000000000000000000000000000000","typographyRef":0,"maxWidthPx":1,"fontFaceEvidence":[{"faceRef":0,"coverageText":"正文后","probe":{"text":"后"}}],"renderArtifactSha256":"0000000000000000000000000000000000000000000000000000000000000000"}],"entrySource":"font-contract-v1"}</script></template>"###;

const INITIAL_STYLE_SUFFIX_A: &str = r###":is(tiqian-prose,[data-tiqian-root])[snapshot-ref="tq-page"][data-tiqian-exact-render-font=true]:not([data-tiqian-exact-layout-fallback]) [data-tq-rendered=true]:is([data-tq-canonical-plain=true],[data-tq-exact-prepared-dom=true]){font-kerning:normal!important;font-optical-sizing:none!important}tiqian-prose[snapshot-ref="tq-page"] [data-tq-rendered="true"] .tqv-0{--tq-line-height:27px!important;--tq-line-baseline-offset:-7px!important}"###;

const ENTRIES_A: &str = r###"[{"key":"p-1","html":"<span aria-hidden=\"true\" class=\"tq-line tqv-0\" data-tq-copy-ignore=\"true\" data-tq-geometry=\"true\" data-tq-line-baseline=\"20\" data-tq-line-bottom=\"27\" data-tq-line-empty=\"false\" data-tq-line-end=\"ParagraphEnd\" data-tq-line-flow-width=\"36\" data-tq-line-index=\"0\" data-tq-line-range=\"0-2\" data-tq-line-top=\"0\" data-tq-line-width=\"36\" data-tq-paragraph-height=\"27\"></span><strong data-tq-source-semantic=\"true\">正文</strong><span aria-hidden=\"true\" data-tq-copy-ignore=\"true\" data-tq-geometry=\"true\" data-tq-line-end-sentinel=\"0\"></span><span aria-hidden=\"true\" data-tq-copy-ignore=\"true\" data-tq-selection-end=\"true\">​</span>"},{"key":"p-2","html":"<span aria-hidden=\"true\" class=\"tq-line tqv-0\" data-tq-copy-ignore=\"true\" data-tq-geometry=\"true\" data-tq-line-baseline=\"20\" data-tq-line-bottom=\"27\" data-tq-line-empty=\"false\" data-tq-line-end=\"ParagraphEnd\" data-tq-line-flow-width=\"36\" data-tq-line-index=\"0\" data-tq-line-range=\"0-2\" data-tq-line-top=\"0\" data-tq-line-width=\"36\" data-tq-paragraph-height=\"27\"></span>后文<span aria-hidden=\"true\" data-tq-copy-ignore=\"true\" data-tq-geometry=\"true\" data-tq-line-end-sentinel=\"0\"></span><span aria-hidden=\"true\" data-tq-copy-ignore=\"true\" data-tq-selection-end=\"true\">​</span>"}]"###;

const TEMPLATE_CONTRACT: &str = r###"<template id="tq-sem" data-tq-snapshot-schema="1" data-tq-layout-revision="tiqian-layout-v2" data-tq-render-revision="prebroken-dom-v15" data-pagefind-ignore><script type="application/json" data-tq-snapshot-manifest>{"schema":1,"layoutRevision":"tiqian-layout-v2","renderRevision":"prebroken-dom-v15","fontSourcePolicy":"host-compatible-stylesheet-v1","paragraphSelector":":is(p, li)[data-tq-snapshot-key]","valueStyles":["--tq-line-height:27px!important;--tq-line-baseline-offset:-7px!important"],"valueStylesSha256":"e5c73b5c5ab5ca77bf2e85c8e56375b63c228b5f8036902279a2de249288c34b","renderFontFamilies":["Snapshot Sans"],"typographies":[{"sha256":"c21cdb8a7e87a74ea0133ebef2938e3741095446d97278621f940d1f03a1eb68","value":{"fontFamilies":["Fixture CJK"],"fontSizePx":18,"lineHeightPx":27,"locale":"zh-Hans","fontWeight":400,"italic":false,"firstLineIndentIc":0,"lineLengthGridEnabled":true,"letterSpacingPx":0,"fontFeatureSettings":"normal","fontVariationSettings":"normal","fontVariantNumeric":"normal"}}],"fontEvidence":{"backendRevision":"tiqian-shared-harfbuzz-v5","harfbuzzVersion":"harfrust-0.13.0","faces":[{"family":"Fixture CJK","publicUrl":"/fonts/f-a.woff2"},{"family":"Fixture CJK","publicUrl":"/fonts/f-b.woff2"}]},"fontReplay":{"revision":"tiqian-server-shaping-replay-v1","encoding":"shared-strings-v1","strings":[],"shapes":[],"metrics":[]},"entries":[{"key":"p-1","sourceSha256":"d661c3d96d53ebc0ca8a55aae24b5df4a4d1bf28d37337b982fe8ebf54846eeb","sourceArtifactSha256":"022be43d07155ae6136f2cec8d5a4054bbe28c175f11bfa4e31bc89c221ce73d","semantic":true,"typographyRef":0,"maxWidthPx":360,"fontFaceEvidence":[{"faceRef":0,"coverageText":"正文","probe":{"text":"正"}}],"renderArtifactSha256":"0ac0e6018b8b1608c4c3b37f152264734f0fbde1c043909a6681fdbeb3976024"}],"fontContractEntries":[{"key":"p-3","sourceSha256":"d9dc1d2fbcde91ce50edb69f99f0f9ef7d00e31de655cd1884a761578b0ca8a0","typographyRef":0,"maxWidthPx":360,"fontFaceEvidence":[{"faceRef":1,"coverageText":"序言","probe":{"text":"序"}}],"renderArtifactSha256":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"}]}</script><div data-tq-entry="p-1"><span aria-hidden="true" class="tq-line tqv-0" data-tq-copy-ignore="true" data-tq-geometry="true" data-tq-line-baseline="20" data-tq-line-bottom="27" data-tq-line-empty="false" data-tq-line-end="ParagraphEnd" data-tq-line-flow-width="36" data-tq-line-index="0" data-tq-line-range="0-2" data-tq-line-top="0" data-tq-line-width="36" data-tq-paragraph-height="27"></span><strong data-tq-source-semantic="true">正文</strong><span aria-hidden="true" data-tq-copy-ignore="true" data-tq-geometry="true" data-tq-line-end-sentinel="0"></span><span aria-hidden="true" data-tq-copy-ignore="true" data-tq-selection-end="true">​</span></div></template>"###;

const CLIENT_TEMPLATE_CONTRACT: &str = r###"<template id="tq-sem" data-tq-snapshot-schema="1" data-tq-layout-revision="tiqian-layout-v2" data-tq-render-revision="prebroken-dom-v15" data-pagefind-ignore><script type="application/json" data-tq-snapshot-manifest>{"schema":1,"layoutRevision":"tiqian-layout-v2","renderRevision":"prebroken-dom-v15","fontSourcePolicy":"host-compatible-stylesheet-v1","paragraphSelector":":is(p, li)[data-tq-snapshot-key]","valueStyles":[],"valueStylesSha256":"4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945","renderFontFamilies":["Snapshot Sans"],"typographies":[{"sha256":"c21cdb8a7e87a74ea0133ebef2938e3741095446d97278621f940d1f03a1eb68","value":{"fontFamilies":["Fixture CJK"],"fontSizePx":18,"lineHeightPx":27,"locale":"zh-Hans","fontWeight":400,"italic":false,"firstLineIndentIc":0,"lineLengthGridEnabled":true,"letterSpacingPx":0,"fontFeatureSettings":"normal","fontVariationSettings":"normal","fontVariantNumeric":"normal"}}],"fontEvidence":{"backendRevision":"tiqian-shared-harfbuzz-v5","harfbuzzVersion":"harfrust-0.13.0","faces":[{"family":"Fixture CJK","publicUrl":"/fonts/f-a.woff2"},{"family":"Fixture CJK","publicUrl":"/fonts/f-b.woff2"}]},"fontReplay":{"revision":"tiqian-server-shaping-replay-v1","encoding":"shared-strings-v1","strings":[],"shapes":[],"metrics":[]},"entries":[{"key":"font-contract-0","sourceSha256":"0000000000000000000000000000000000000000000000000000000000000000","typographyRef":0,"maxWidthPx":1,"fontFaceEvidence":[{"faceRef":0,"coverageText":"正文","probe":{"text":"正"}}],"renderArtifactSha256":"0000000000000000000000000000000000000000000000000000000000000000"},{"key":"font-contract-1","sourceSha256":"0000000000000000000000000000000000000000000000000000000000000000","typographyRef":0,"maxWidthPx":1,"fontFaceEvidence":[{"faceRef":1,"coverageText":"序言","probe":{"text":"序"}}],"renderArtifactSha256":"0000000000000000000000000000000000000000000000000000000000000000"}],"entrySource":"font-contract-v1"}</script></template>"###;

const FONT_CONTRACT_TEMPLATE: &str = r###"<template id="tq-fc" data-tq-snapshot-schema="1" data-tq-layout-revision="tiqian-layout-v2" data-tq-render-revision="prebroken-dom-v15" data-pagefind-ignore><script type="application/json" data-tq-snapshot-manifest>{"schema":1,"layoutRevision":"tiqian-layout-v2","renderRevision":"prebroken-dom-v15","fontSourcePolicy":"host-compatible-stylesheet-v1","paragraphSelector":":is(p, li):not([data-tiqian-skip])","valueStyles":[],"valueStylesSha256":"4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945","renderFontFamilies":["Snapshot Sans"],"typographies":[{"sha256":"c21cdb8a7e87a74ea0133ebef2938e3741095446d97278621f940d1f03a1eb68","value":{"fontFamilies":["Fixture CJK"],"fontSizePx":18,"lineHeightPx":27,"locale":"zh-Hans","fontWeight":400,"italic":false,"firstLineIndentIc":0,"lineLengthGridEnabled":true,"letterSpacingPx":0,"fontFeatureSettings":"normal","fontVariationSettings":"normal","fontVariantNumeric":"normal"}}],"fontEvidence":{"backendRevision":"tiqian-shared-harfbuzz-v5","harfbuzzVersion":"harfrust-0.13.0","faces":[{"family":"Fixture CJK","publicUrl":"/fonts/f-a.woff2"}]},"fontReplay":{"revision":"tiqian-server-shaping-replay-v1","encoding":"shared-strings-v1","strings":[],"shapes":[],"metrics":[]},"entries":[{"key":"font-contract-0","sourceSha256":"0000000000000000000000000000000000000000000000000000000000000000","typographyRef":0,"maxWidthPx":1,"fontFaceEvidence":[{"faceRef":0,"coverageText":"正文后","probe":{"text":"正"}}],"renderArtifactSha256":"0000000000000000000000000000000000000000000000000000000000000000"},{"key":"font-contract-1","sourceSha256":"0000000000000000000000000000000000000000000000000000000000000000","typographyRef":0,"maxWidthPx":1,"fontFaceEvidence":[{"faceRef":0,"coverageText":"正文后","probe":{"text":"后"}}],"renderArtifactSha256":"0000000000000000000000000000000000000000000000000000000000000000"}],"entrySource":"font-contract-v1"}</script></template>"###;

const FONT_CONTRACT_INITIAL_STYLE: &str = r###":is(tiqian-prose,[data-tiqian-root])[snapshot-ref="tq-fc"][data-tiqian-exact-render-font=true]:not([data-tiqian-exact-layout-fallback]) [data-tq-rendered=true]:is([data-tq-canonical-plain=true],[data-tq-exact-prepared-dom=true]){font-kerning:normal!important;font-optical-sizing:none!important}"###;

fn paragraphs() -> Json {
    parse_json(&format!("[{ENTRY_A},{ENTRY_B}]")).expect("entries parse")
}

fn entry_alone(entry: &str) -> Json {
    parse_json(&format!("[{entry}]")).expect("entry parses")
}

fn two_entries(first: &str, second: &str) -> Json {
    parse_json(&format!("[{first},{second}]")).expect("entries parse")
}

fn options<'a>(id: &'a str) -> SnapshotBundleOptions<'a> {
    let mut options = SnapshotBundleOptions::new(SHARED_STYLE);
    options.id = Some(id);
    options
}

fn bundle(result: Result<SnapshotBundle, NamedError>) -> SnapshotBundle {
    match result {
        Ok(bundle) => bundle,
        Err(error) => panic!("expected a bundle, got {}", error.name()),
    }
}

fn error_name(result: Result<SnapshotBundle, NamedError>) -> String {
    match result {
        Err(error) => error.name().to_string(),
        Ok(_) => panic!("expected an error, got a bundle"),
    }
}

fn set_field(entry: &mut Json, key: &str, value_text: &str) {
    let value = parse_json(value_text).expect("value parses");
    let Json::Obj(fields) = entry else { panic!("entry object") };
    for (name, slot) in fields.iter_mut() {
        if name == key {
            *slot = value;
            return;
        }
    }
    panic!("key {key} missing");
}


#[test]
fn snapshot_bundle_matches_the_js_oracle_bytes() {
    let bundle = bundle(render_snapshot_bundle(Some(&paragraphs()), &options("tq-page")));
    assert_eq!(bundle.id, "tq-page");
    assert_eq!(bundle.template, TEMPLATE_A);
    assert_eq!(bundle.inert_template, TEMPLATE_A);
    assert_eq!(bundle.client_template, CLIENT_TEMPLATE_A);
    assert_eq!(bundle.initial_style, format!("{SHARED_STYLE}{INITIAL_STYLE_SUFFIX_A}"));
    assert_eq!(bundle.entries.render(), ENTRIES_A);
    assert_eq!(bundle.render_font_families.render(), r#"["Snapshot Sans"]"#);
    assert_eq!(bundle.font_preloads.render(), "[]");
    assert_eq!(
        bundle.root_attributes.render(),
        r#"{"data-tiqian-exact-render-font":"true"}"#
    );
}

#[test]
fn snapshot_template_alone_matches_the_inert_template() {
    let template =
        render_snapshot_template(Some(&paragraphs()), &options("tq-page")).expect("template renders");
    assert_eq!(template, TEMPLATE_A);
}

#[test]
fn font_contract_paragraphs_split_their_own_manifest_entries() {
    let mut options = options("tq-sem");
    let contract = entry_alone(CONTRACT_FACE);
    options.font_contract_paragraphs = Some(&contract);
    let bundle = bundle(render_snapshot_bundle(Some(&entry_alone(ENTRY_A)), &options));
    assert_eq!(bundle.template, TEMPLATE_CONTRACT);
    assert_eq!(bundle.client_template, CLIENT_TEMPLATE_CONTRACT);
    assert!(bundle.template.contains("fontContractEntries"));
}

#[test]
fn font_contract_bundle_matches_the_js_oracle_bytes() {
    let bundle = bundle(render_font_contract_bundle(Some(&paragraphs()), &options("tq-fc")));
    assert_eq!(bundle.template, FONT_CONTRACT_TEMPLATE);
    assert_eq!(bundle.inert_template, FONT_CONTRACT_TEMPLATE);
    assert_eq!(bundle.client_template, FONT_CONTRACT_TEMPLATE);
    assert_eq!(bundle.initial_style, FONT_CONTRACT_INITIAL_STYLE);
    assert_eq!(bundle.entries.render(), "[]");
    assert_eq!(bundle.root_attributes.render(), "{}");
    assert!(bundle.template.contains(RUNTIME_PARAGRAPH_SELECTOR));
    assert!(!bundle.template.contains(PLAIN_PARAGRAPH_SELECTOR));
}

#[test]
fn bundle_input_damage_reports_the_paragraph_list_gate_name() {
    assert_eq!(
        error_name(render_snapshot_bundle(None, &options("tq-page"))),
        "MissingPreparedParagraphs"
    );
    assert_eq!(
        error_name(render_snapshot_bundle(Some(&Json::Null), &options("tq-page"))),
        "MissingPreparedParagraphs"
    );
    assert_eq!(
        error_name(render_snapshot_bundle(Some(&Json::Num(3.0)), &options("tq-page"))),
        "MissingPreparedParagraphs"
    );
    assert_eq!(
        error_name(render_snapshot_bundle(Some(&Json::str("p")), &options("tq-page"))),
        "MissingPreparedParagraphs"
    );
}


#[test]
fn unsupported_status_reports_unsupported_paragraph() {
    let mut first = parse_json(ENTRY_A).expect("entry A");
    let mut second = parse_json(ENTRY_B).expect("entry B");
    set_field(if true { &mut second } else { &mut first }, "status", r#""unsupported""#);
    assert_eq!(
        error_name(render_snapshot_bundle(
            Some(&two_entries(&first.render(), &second.render())),
            &options("tq-page"),
        )),
        "SnapshotTemplateContainsUnsupportedParagraph",
    );
}


#[test]
fn wrong_schema_reports_stale_paragraph() {
    let mut first = parse_json(ENTRY_A).expect("entry A");
    let mut second = parse_json(ENTRY_B).expect("entry B");
    set_field(if false { &mut second } else { &mut first }, "schema", r#"2"#);
    assert_eq!(
        error_name(render_snapshot_bundle(
            Some(&two_entries(&first.render(), &second.render())),
            &options("tq-page"),
        )),
        "SnapshotTemplateContainsStalePreparedParagraph",
    );
}


#[test]
fn wrong_layout_revision_reports_stale_paragraph() {
    let mut first = parse_json(ENTRY_A).expect("entry A");
    let mut second = parse_json(ENTRY_B).expect("entry B");
    set_field(if false { &mut second } else { &mut first }, "layoutRevision", r#""old""#);
    assert_eq!(
        error_name(render_snapshot_bundle(
            Some(&two_entries(&first.render(), &second.render())),
            &options("tq-page"),
        )),
        "SnapshotTemplateContainsStalePreparedParagraph",
    );
}


#[test]
fn wrong_render_revision_reports_stale_paragraph() {
    let mut first = parse_json(ENTRY_A).expect("entry A");
    let mut second = parse_json(ENTRY_B).expect("entry B");
    set_field(if false { &mut second } else { &mut first }, "renderRevision", r#""old""#);
    assert_eq!(
        error_name(render_snapshot_bundle(
            Some(&two_entries(&first.render(), &second.render())),
            &options("tq-page"),
        )),
        "SnapshotTemplateContainsStalePreparedParagraph",
    );
}


#[test]
fn numeric_render_artifact_sha_reports_stale_paragraph() {
    let mut first = parse_json(ENTRY_A).expect("entry A");
    let mut second = parse_json(ENTRY_B).expect("entry B");
    set_field(if false { &mut second } else { &mut first }, "renderArtifactSha256", r#"1"#);
    assert_eq!(
        error_name(render_snapshot_bundle(
            Some(&two_entries(&first.render(), &second.render())),
            &options("tq-page"),
        )),
        "SnapshotTemplateContainsStalePreparedParagraph",
    );
}


#[test]
fn duplicate_key_reports_duplicate_snapshot_key() {
    let mut first = parse_json(ENTRY_A).expect("entry A");
    let mut second = parse_json(ENTRY_B).expect("entry B");
    set_field(if true { &mut second } else { &mut first }, "key", r#""p-1""#);
    assert_eq!(
        error_name(render_snapshot_bundle(
            Some(&two_entries(&first.render(), &second.render())),
            &options("tq-page"),
        )),
        "DuplicateSnapshotKey",
    );
}


#[test]
fn empty_render_font_families_report_missing_exact_families() {
    let mut first = parse_json(ENTRY_A).expect("entry A");
    let mut second = parse_json(ENTRY_B).expect("entry B");
    set_field(if false { &mut second } else { &mut first }, "renderFontFamilies", r#"[]"#);
    assert_eq!(
        error_name(render_snapshot_bundle(
            Some(&two_entries(&first.render(), &second.render())),
            &options("tq-page"),
        )),
        "MissingExactRenderFontFamilies",
    );
}


#[test]
fn blank_render_font_family_reports_missing_exact_families() {
    let mut first = parse_json(ENTRY_A).expect("entry A");
    let mut second = parse_json(ENTRY_B).expect("entry B");
    set_field(if false { &mut second } else { &mut first }, "renderFontFamilies", r#"[" "]"#);
    assert_eq!(
        error_name(render_snapshot_bundle(
            Some(&two_entries(&first.render(), &second.render())),
            &options("tq-page"),
        )),
        "MissingExactRenderFontFamilies",
    );
}


#[test]
fn numeric_render_font_family_reports_missing_exact_families() {
    let mut first = parse_json(ENTRY_A).expect("entry A");
    let mut second = parse_json(ENTRY_B).expect("entry B");
    set_field(if false { &mut second } else { &mut first }, "renderFontFamilies", r#"[1]"#);
    assert_eq!(
        error_name(render_snapshot_bundle(
            Some(&two_entries(&first.render(), &second.render())),
            &options("tq-page"),
        )),
        "MissingExactRenderFontFamilies",
    );
}


#[test]
fn conflicting_render_font_families_report_a_conflict() {
    let mut first = parse_json(ENTRY_A).expect("entry A");
    let mut second = parse_json(ENTRY_B).expect("entry B");
    set_field(if true { &mut second } else { &mut first }, "renderFontFamilies", r#"["Other Sans"]"#);
    assert_eq!(
        error_name(render_snapshot_bundle(
            Some(&two_entries(&first.render(), &second.render())),
            &options("tq-page"),
        )),
        "SnapshotRenderFontFamilyConflict",
    );
}


#[test]
fn missing_render_font_families_report_a_conflict() {
    let mut second = parse_json(ENTRY_B).expect("entry B");
    let Json::Obj(fields) = &mut second else { panic!("entry object") };
    fields.retain(|(name, _)| name != "renderFontFamilies");
    assert_eq!(
        error_name(render_snapshot_bundle(
            Some(&two_entries(ENTRY_A, &second.render())),
            &options("tq-page"),
        )),
        "SnapshotRenderFontFamilyConflict",
    );
}


#[test]
fn template_id_and_selector_damage_reports_the_js_gate_names() {
    let paragraphs = paragraphs();
    for id in ["", " ", "1abc", "ab cd"] {
        let expected = if id.trim().is_empty() {
            "MissingSnapshotTemplateId"
        } else {
            "InvalidSnapshotTemplateId"
        };
        assert_eq!(error_name(render_snapshot_bundle(Some(&paragraphs), &options(id))), expected);
    }
    let mut selector_options = options("tq-page");
    selector_options.paragraph_selector = Some(":is(p)");
    assert_eq!(
        error_name(render_snapshot_bundle(Some(&paragraphs), &selector_options)),
        "UnsupportedSnapshotParagraphSelector"
    );
    let mut plain_options = options("tq-fc-plain");
    plain_options.paragraph_selector = Some(PLAIN_PARAGRAPH_SELECTOR);
    assert_eq!(
        error_name(render_font_contract_bundle(Some(&paragraphs), &plain_options)),
        "UnsupportedSnapshotParagraphSelector"
    );
}

#[test]
fn unsupported_contract_paragraph_reports_unsupported_paragraph() {
    let mut damage = parse_json(CONTRACT_FACE).expect("contract parses");
    set_field(&mut damage, "status", "\"unsupported\"");
    let mut options = options("tq-page");
    let contract = parse_json(&format!("[{}]", damage.render())).expect("contract parses");
    options.font_contract_paragraphs = Some(&contract);
    assert_eq!(
        error_name(render_snapshot_bundle(Some(&entry_alone(ENTRY_A)), &options)),
        "SnapshotTemplateContainsUnsupportedParagraph"
    );
}
