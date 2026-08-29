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
// Node only: node scripts/plan-parity-oracle.ts (from frontend/web-precompute).

import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here: string = dirname(fileURLToPath(import.meta.url));
const bundleUrl: URL = new URL(
  "../../../ffi/js/build/compileSync/js/main/productionExecutable/kotlin/Tiqian-tiqian-ffi-js.mjs",
  import.meta.url,
);

interface ShapeGlyph {
  id: number;
  advance: number;
  x: number;
  y: number;
  bounds: [number, number, number, number];
}

interface ShapeRequest {
  displayText: string;
  style: { fontSize: number; locale: string };
  range: { start: number; end: number };
  text: string;
  fontDecision: { candidateKey: string };
}

interface MetricsRequest {
  fontSize: number;
}

interface ShapeGlyphRun {
  range: { start: number; end: number };
  fontKey: string;
  glyphs: {
    id: number;
    clusterRange: { start: number; end: number };
    advance: number;
    x: number;
    y: number;
    bounds: { left: number; top: number; right: number; bottom: number };
  }[];
  advance: number;
  openTypeFeatures: string[];
}

interface ShapeCluster {
  range: { start: number; end: number };
  text: string;
  displayText: string;
  fontKey: string;
  advance: number;
}

interface ShapeDecision {
  range: { start: number; end: number };
  sourceText: string;
  displayText: string;
  fontKey: string;
  glyphCount: number;
  advance: number;
  source: string;
  reason: string;
  glyphsWithoutInkBounds: number;
  missingGlyphs: number;
  resolvedFace: string;
  script: string;
  language: string;
  featureEvidence: null;
}

interface ShapeResult {
  clusters: ShapeCluster[];
  glyphRuns: ShapeGlyphRun[];
  decisions: ShapeDecision[];
}

interface MetricsResult {
  ascent: number;
  descent: number;
  leading: number;
  source: string;
  typoAscent: number;
  typoDescent: number;
}

// The fixture backend of PrecomputeExportsTest.kt: one glyph per code point,
// advance and x scaled by the font size, glyph id 0 marks a missing glyph.
// The callback protocol is the synchronous JSON request/response contract.
function makeFixtureCallbacks(): {
  shapeJson: (requestJson: string) => string;
  metricsJson: (requestJson: string) => string;
} {
  return {
    shapeJson: function (requestJson: string): string {
      const request: ShapeRequest = JSON.parse(requestJson) as ShapeRequest;
      const displayText: string = request.displayText;
      const fontSize: number = request.style.fontSize;
      const missing: boolean = String(displayText).includes("\u22ef");
      const glyphs: ShapeGlyph[] = [];
      let index: number = 0;
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
          glyphs: glyphs.map(function (g: ShapeGlyph) {
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
      } satisfies ShapeResult);
    },
    metricsJson: function (requestJson: string): string {
      const request: MetricsRequest = JSON.parse(requestJson) as MetricsRequest;
      const fontSize: number = request.fontSize;
      return JSON.stringify({
        ascent: fontSize * 1.04,
        descent: fontSize * 0.28,
        leading: 0,
        source: "RawTables",
        typoAscent: fontSize * 0.88,
        typoDescent: fontSize * 0.12,
      } satisfies MetricsResult);
    },
  };
}

interface TextSpanWire {
  start: number;
  end: number;
  families: string[];
  fontSizePx: number;
  fontWeight: number;
  italic: boolean;
  baselineShiftPx: number;
}

interface InlineBoxWire {
  start: number;
  end: number;
  inlineStartPx: number;
  inlineEndPx: number;
  outerSpacing: string;
}

interface LineBreakSpanWire {
  start: number;
  end: number;
  policy: string;
}

interface CorpusRequest {
  text: string;
  maxWidthPx: number;
  fontFamilies: string[];
  fontSizePx: number;
  lineHeightPx: number;
  locale: string;
  fontWeight: number;
  italic: boolean;
  firstLineIndentIc: number;
  lineLengthGridEnabled: boolean;
  sourceBoundaries: number[];
  textSpans: TextSpanWire[];
  inlineBoxes: InlineBoxWire[];
  lineBreakSpans: LineBreakSpanWire[];
}

interface WireTextSpan {
  start: number;
  end: number;
  fontFamilies: string[];
  fontSize: number;
  fontWeight: number;
  italic: boolean;
  baselineShift: number;
}

interface WireInlineBox {
  start: number;
  end: number;
  inlineStart: number;
  inlineEnd: number;
  outerSpacing: string;
}

interface WireRequest {
  text: string;
  maxWidthPx: number;
  fontFamilies: string[];
  fontSizePx: number;
  lineHeightPx: number;
  locale: string;
  fontWeight: number;
  italic: boolean;
  firstLineIndentIc: number;
  lineLengthGridEnabled: boolean;
  sourceBoundaries: number[];
  textSpans: WireTextSpan[];
  inlineBoxes: WireInlineBox[];
  lineBreakSpans: LineBreakSpanWire[];
  inlineObjects: unknown[];
  decorations: unknown[];
  emphasisDotGapEm: null;
  renderEvidenceOverride: boolean;
}

// Same base values and same case list as the Rust corpus; the byte comparison
// catches drift in either direction.
function corpus(): [string, CorpusRequest][] {
  const base: () => CorpusRequest = function (): CorpusRequest {
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

  const plain: CorpusRequest = Object.assign({}, base(), { text: "\u4E2D\u6587\u4E2D\u6587\u4E2D\u6587\u4E2D\u6587" });

  const punctuation: CorpusRequest = Object.assign({}, base(), { text: "\u4E2D\u6587\uFF0C\u4E2D\u6587\uFF1B\u4E2D\u6587\u3002", maxWidthPx: 72 });

  const mixed: CorpusRequest = Object.assign({}, base(), {
    text: "Hello \u4E2D\u6587 world \u5B57",
    maxWidthPx: 90,
    lineLengthGridEnabled: false,
  });

  const indent: CorpusRequest = Object.assign({}, base(), { text: "\u4E2D\u6587\u4E2D\u6587\u4E2D\u6587", firstLineIndentIc: 2 });

  const span: CorpusRequest = Object.assign({}, base(), {
    text: "\u4E2D\u6587\u4E2D\u6587",
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

  const boundaries: CorpusRequest = Object.assign({}, base(), { text: "\u4E2D\u6587\u4E2D\u6587\u4E2D\u6587", sourceBoundaries: [2, 4] });

  const policy: CorpusRequest = Object.assign({}, base(), {
    text: "URLhttps://example.com/\u4E2D\u6587",
    maxWidthPx: 90,
    lineLengthGridEnabled: false,
    lineBreakSpans: [{ start: 0, end: 25, policy: "ProgressiveTechnical" }],
  });

  const inlineBox: CorpusRequest = Object.assign({}, base(), {
    text: "\u4E2D\u6587\u5B57\u4E2D\u6587",
    inlineBoxes: [{ start: 2, end: 3, inlineStartPx: 6, inlineEndPx: 12, outerSpacing: "Narrow" }],
  });

  const ellipsis: CorpusRequest = Object.assign({}, base(), { text: "\u2026\u2026", maxWidthPx: 72 });

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

interface PrecomputeExports {
  precomputeParagraphWithDiagnostics: (
    request: WireRequest,
    evidenceFlag: number,
    shapeCallback: (json: string) => string,
    metricsCallback: (json: string) => string,
  ) => string;
}

const callbacks = makeFixtureCallbacks();
const runtime: PrecomputeExports = await import(bundleUrl.href) as PrecomputeExports;

// Wire form: the PrepareParagraphRequest DTO (ADR 0053 corrective wave 5).
// Field names follow TextSpanWireDto/InlineBoxWireDto; the corpus keeps its
// own fixture names and maps here so both sides stay readable.
function toWireRequest(request: CorpusRequest): WireRequest {
  return {
    text: request.text,
    maxWidthPx: request.maxWidthPx,
    fontFamilies: request.fontFamilies,
    fontSizePx: request.fontSizePx,
    lineHeightPx: request.lineHeightPx,
    locale: request.locale,
    fontWeight: request.fontWeight,
    italic: request.italic,
    firstLineIndentIc: request.firstLineIndentIc,
    lineLengthGridEnabled: request.lineLengthGridEnabled,
    sourceBoundaries: request.sourceBoundaries,
    textSpans: request.textSpans.map(function (span: TextSpanWire): WireTextSpan {
      return {
        start: span.start,
        end: span.end,
        fontFamilies: span.families,
        fontSize: span.fontSizePx,
        fontWeight: span.fontWeight,
        italic: span.italic,
        baselineShift: span.baselineShiftPx,
      };
    }),
    inlineBoxes: request.inlineBoxes.map(function (box: InlineBoxWire): WireInlineBox {
      return {
        start: box.start,
        end: box.end,
        inlineStart: box.inlineStartPx,
        inlineEnd: box.inlineEndPx,
        outerSpacing: box.outerSpacing,
      };
    }),
    lineBreakSpans: request.lineBreakSpans,
    inlineObjects: [],
    decorations: [],
    emphasisDotGapEm: null,
    renderEvidenceOverride: false,
  };
}

interface DumpPlan {
  [key: string]: unknown;
}

const dump: DumpPlan = {};
for (const [name, request] of corpus()) {
  const envelope: { plan: unknown } = JSON.parse(runtime.precomputeParagraphWithDiagnostics(
    toWireRequest(request),
    0.0,
    callbacks.shapeJson,
    callbacks.metricsJson,
  )) as { plan: unknown };
  dump[name] = envelope.plan;
}

const outPath: string = resolve(here, "../build/plan-parity/oracle.json");
mkdirSync(dirname(outPath), { recursive: true });
writeFileSync(outPath, JSON.stringify(dump) + "\n");
process.stdout.write("oracle dump: " + outPath + " (" + Object.keys(dump).length + " cases)\n");
