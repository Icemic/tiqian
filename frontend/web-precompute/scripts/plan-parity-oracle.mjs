// Plan parity oracle (ADR 0050 amendment Verification).
//
// Runs the Kotlin/JS precompute bundle over the same deterministic fixture
// font backend and the same corpus as the Rust integration test
// `rust/tiqian-precompute/tests/plan_parity.rs`, and writes
// `build/plan-parity/oracle.json`. The Rust test byte-compares that file
// against its own dump, so both lanes must serialize the corpus in the same
// order with the same argument values.
//
// The evidence policy is the precompute lane's: plans stay evidence-free.
// The native lane this oracle is compared against calls the engine without
// an evidence flag (the packed layout request carries none), and the wire
// face's shape-derived default belongs to the runtime path, not to this
// build-time consumer. The explicit `false` below pins that policy; without
// it the derived default would diverge from the native lane on every
// span-carrying case.
//
// Node only: node scripts/plan-parity-oracle.mjs (from frontend/web-precompute).

import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const bundleUrl = new URL(
  "../../../ffi/js/build/compileSync/js/main/productionExecutable/kotlin/Tiqian-tiqian-ffi-js.mjs",
  import.meta.url,
);

const FAMILY_SEPARATOR = "\u001f";
const RECORD_SEPARATOR = "\u001e";
const FIELD_SEPARATOR = "\u001d";

// The fixture backend of PrecomputeExportsTest.kt: one glyph per code point,
// advance and x scaled by the font size, glyph id 0 marks a missing glyph.
// The callback protocol is the synchronous JSON request/response contract.
function makeFixtureCallbacks() {
  return {
    shapeJson: function(requestJson) {
      const request = JSON.parse(requestJson);
      const displayText = request.displayText;
      const fontSize = request.style.fontSize;
      const missing = String(displayText).includes("\u22ef");
      const glyphs = [];
      let index = 0;
      for (const _point of displayText) {
        glyphs.push({
          id: missing ? 0 : 100 + index,
          advance: fontSize,
          x: index * fontSize,
          y: 0,
          bounds: [0, -fontSize * 0.88, fontSize, fontSize * 0.12],
        });
        index += 1;
      }
      return JSON.stringify({
        clusters: [{
          range: request.range,
          text: request.text.substring(request.range.start, request.range.end),
          displayText,
          fontKey: request.fontDecision.candidateKey,
          advance: glyphs.length * fontSize,
        }],
        glyphRuns: [{
          range: request.range,
          fontKey: request.fontDecision.candidateKey,
          glyphs: glyphs.map(function(g) {
            return {
              id: g.id,
              clusterRange: request.range,
              advance: g.advance,
              x: g.x,
              y: g.y,
              bounds: { left: g.bounds[0], top: g.bounds[1], right: g.bounds[2], bottom: g.bounds[3] },
            };
          }),
          advance: glyphs.length * fontSize,
          openTypeFeatures: [],
        }],
        decisions: [{
          range: request.range,
          sourceText: request.text.substring(request.range.start, request.range.end),
          displayText,
          fontKey: request.fontDecision.candidateKey,
          glyphCount: glyphs.length,
          advance: glyphs.length * fontSize,
          source: "HarfBuzz",
          reason: "test",
          glyphsWithoutInkBounds: 0,
          missingGlyphs: missing ? glyphs.length : 0,
          resolvedFace: "Fixture CJK",
          script: "Hani",
          language: request.style.locale,
          featureEvidence: null,
        }],
      });
    },
    metricsJson: function(requestJson) {
      const request = JSON.parse(requestJson);
      const fontSize = request.fontSize;
      return JSON.stringify({
        ascent: fontSize * 1.04,
        descent: fontSize * 0.28,
        leading: 0,
        source: "RawTables",
        typoAscent: fontSize * 0.88,
        typoDescent: fontSize * 0.12,
      });
    },
  };
}

function encodedTextSpan(span) {
  return [
    span.start,
    span.end,
    span.families.join(FAMILY_SEPARATOR),
    span.fontSizePx,
    span.fontWeight,
    span.italic,
    span.baselineShiftPx,
  ].join(FIELD_SEPARATOR);
}

function encodedInlineBox(box) {
  return [box.start, box.end, box.inlineStartPx, box.inlineEndPx, box.outerSpacing]
    .join(FIELD_SEPARATOR);
}

function encodedLineBreakSpan(span) {
  return [span.start, span.end, span.policy].join(FIELD_SEPARATOR);
}

// Same base values and same case list as the Rust corpus; the byte comparison
// catches drift in either direction.
function corpus() {
  const base = function() {
    return {
      text: "",
      maxWidthPx: 36,
      fontFamilies: ["Fixture CJK"],
      fontSizePx: 18,
      lineHeightPx: 27,
      locale: "zh-Hans",
      fontWeight: 400,
      italic: false,
      firstLineIndentIc: 0,
      lineLengthGridEnabled: true,
      sourceBoundaries: [],
      textSpans: [],
      inlineBoxes: [],
      lineBreakSpans: [],
    };
  };

  const plain = Object.assign({}, base(), { text: "中文中文中文中文" });

  const punctuation = Object.assign({}, base(), { text: "中文，中文；中文。", maxWidthPx: 72 });

  const mixed = Object.assign({}, base(), {
    text: "Hello 中文 world 字",
    maxWidthPx: 90,
    lineLengthGridEnabled: false,
  });

  const indent = Object.assign({}, base(), { text: "中文中文中文", firstLineIndentIc: 2 });

  const span = Object.assign({}, base(), {
    text: "中文中文",
    textSpans: [{
      start: 0,
      end: 2,
      families: ["Fixture CJK"],
      fontSizePx: 20,
      fontWeight: 700,
      italic: false,
      baselineShiftPx: 0,
    }],
  });

  const boundaries = Object.assign({}, base(), { text: "中文中文中文", sourceBoundaries: [2, 4] });

  const policy = Object.assign({}, base(), {
    text: "URLhttps://example.com/中文",
    maxWidthPx: 90,
    lineLengthGridEnabled: false,
    lineBreakSpans: [{ start: 0, end: 25, policy: "ProgressiveTechnical" }],
  });

  const inlineBox = Object.assign({}, base(), {
    text: "中文字中文",
    inlineBoxes: [{ start: 2, end: 3, inlineStartPx: 6, inlineEndPx: 12, outerSpacing: "Narrow" }],
  });

  const ellipsis = Object.assign({}, base(), { text: "\u2026\u2026", maxWidthPx: 72 });

  return [
    ["plainWrap", plain],
    ["punctuation", punctuation],
    ["mixed", mixed],
    ["indent", indent],
    ["span", span],
    ["boundaries", boundaries],
    ["lineBreakPolicy", policy],
    ["inlineBox", inlineBox],
    ["ellipsis", ellipsis],
  ];
}

const callbacks = makeFixtureCallbacks();
const runtime = await import(bundleUrl.href);

const dump = {};
for (const [name, request] of corpus()) {
  const envelope = JSON.parse(runtime.precomputeParagraphWithDiagnostics(
    request.text,
    request.maxWidthPx,
    request.fontFamilies.join(FAMILY_SEPARATOR),
    request.fontSizePx,
    request.lineHeightPx,
    request.locale,
    request.fontWeight,
    request.italic,
    request.firstLineIndentIc,
    request.lineLengthGridEnabled,
    request.sourceBoundaries.join(","),
    request.textSpans.map(encodedTextSpan).join(RECORD_SEPARATOR),
    request.inlineBoxes.map(encodedInlineBox).join(RECORD_SEPARATOR),
    request.lineBreakSpans.map(encodedLineBreakSpan).join(RECORD_SEPARATOR),
    "",
    0.0,
    callbacks.shapeJson,
    callbacks.metricsJson,
    "",
    null,
    false,
  ));
  dump[name] = envelope.plan;
}

const outPath = resolve(here, "../build/plan-parity/oracle.json");
mkdirSync(dirname(outPath), { recursive: true });
writeFileSync(outPath, JSON.stringify(dump) + "\n");
process.stdout.write("oracle dump: " + outPath + " (" + Object.keys(dump).length + " cases)\n");