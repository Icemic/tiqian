import assert from "node:assert/strict";
import test from "node:test";

import { prepareParagraphLayout, wireArguments } from "../core/engine/prepare-paragraph-layout.js";
import { effectiveLineMeasure } from "../core/engine/responsive-measure.js";
import { installFixtureFontBackend, installThrowingFontBackend } from "../test-support/fixture-font-backend.mjs";
import type { LoweredParagraph, TextStyle, TextSpan, InlineBoxSpan, LineBreakSpan, InlineObjectSpan, DecorationSpan, DomSourceSpan, DomInlineBoxStyle } from "../core/engine/lowered-paragraph.js";
import type { PrepareLayoutResult, PrepareReadyResult } from "../core/engine/prepare-paragraph-layout.js";

interface SavedGlobal {
  name: string;
  own: boolean;
  value: unknown;
}

interface ComputedStyleValues {
  paddingLeft?: string;
  paddingRight?: string;
  borderLeftWidth?: string;
  borderRightWidth?: string;
  [key: string]: string | undefined;
}

interface FakeComputedProps extends ComputedStyleValues {
  paddingLeft: string;
  paddingRight: string;
  borderLeftWidth: string;
  borderRightWidth: string;
}

interface FakeComputedStyle {
  [key: string]: string | ((name: string) => string);
  getPropertyValue: (name: string) => string;
}

interface SpanOverrides {
  start?: number;
  end?: number;
  style?: Partial<TextStyle>;
}

interface InlineBoxStyleOverrides {
  inlineStart?: number;
  inlineEnd?: number;
  marginRight?: number;
  letterSpacing?: number;
  boxDecorationBreak?: string;
}

interface SourceSpanOverrides {
  start?: number;
  end?: number;
  element?: { tagName: string; attributes: Array<[string, string]> };
  depth?: number;
  cjkStrongBaseWeight?: number | null;
  computedColor?: string | null;
  inlineBoxStyle?: InlineBoxStyleOverrides;
}

interface ParagraphOverrides {
  text?: string;
  textStyle?: Partial<TextStyle>;
  lineHeight?: number;
  spans?: TextSpan[];
  decorations?: DecorationSpan[];
  inlineBoxes?: InlineBoxSpan[];
  inlineObjects?: InlineObjectSpan[];
  domInlineObjects?: unknown[];
  sourceSpans?: DomSourceSpan[];
  sourceBoundaries?: number[];
  lineBreakSpans?: LineBreakSpan[];
}

interface ElementOverrides {
  width?: number;
}

interface FakeElement {
  tagName: string;
  getBoundingClientRect: () => { width: number };
  getClientRects: () => DOMRectList;
  parentElement: Element | null;
  _computedValues?: ComputedStyleValues;
}

interface BridgeShapeRequest {
  text: string;
  range: { start: number; end: number };
  style: { fontSize: number };
  displayText: string;
}

interface BridgeGlyph {
  id: number;
  clusterRange: { start: number; end: number };
  advance: number;
  x: number;
  y: number;
  bounds: { left: number; top: number; right: number; bottom: number };
}

interface BridgeCluster {
  range: { start: number; end: number };
  text: string;
  displayText: string;
  fontKey: string;
  advance: number;
  baselineShift: number;
}

interface BridgeGlyphRun {
  range: { start: number; end: number };
  fontKey: string;
  glyphs: BridgeGlyph[];
  advance: number;
  openTypeFeatures: unknown[];
}

interface BridgeDecision {
  range: { start: number; end: number };
  sourceText: string;
  displayText: string;
  fontKey: string;
  glyphCount: number;
  advance: number;
  source: string;
  reason: string;
  capabilityIssue?: string;
}

interface BridgeShapeResponse {
  clusters: BridgeCluster[];
  glyphRuns: BridgeGlyphRun[];
  decisions: BridgeDecision[];
}

interface BridgeMetricsResponse {
  ascent: number;
  descent: number;
  leading: number;
  source: string;
  typoAscent: number;
  typoDescent: number;
}

interface BrowserFallbackBridge {
  shapeJson: (req: string) => string;
  metricsJson: () => string;
}

interface PrepareSnapshotSessionDescriptor {
  shapeJson: (req: string) => string;
  metricsJson: (req: string) => string;
}

interface SnapshotArgumentOverrides {
  paragraph?: {
    source: FakeElement;
    lowered: LoweredParagraph;
    lastMeasure: number | null;
  };
  options?: { firstLineIndentIc: number; emphasisDotGapEm: number | null };
  snapshotSession?: PrepareSnapshotSessionDescriptor | null;
  browserFallback?: { bridge: BrowserFallbackBridge } | null;
  widthOverride?: number | null;
  ignoreUnchangedMeasure?: boolean;
}

function saveGlobals(names: string[]): SavedGlobal[] {
  return names.map((name) => ({
    name,
    own: Object.prototype.hasOwnProperty.call(globalThis, name),
    value: globalThis[name as keyof typeof globalThis],
  }));
}

function restoreGlobals(entries: SavedGlobal[]): void {
  for (const { name, own, value } of entries) {
    if (own) {
      (globalThis as Record<string, unknown>)[name] = value;
    } else {
      delete (globalThis as Record<string, unknown>)[name];
    }
  }
}

function computedStyle(values: ComputedStyleValues = {}): FakeComputedStyle {
  const props: FakeComputedProps = {
    paddingLeft: "0px",
    paddingRight: "0px",
    borderLeftWidth: "0px",
    borderRightWidth: "0px",
    ...values,
  };
  const style: Record<string, string> = {};
  for (const key of Object.keys(props)) {
    style[key] = props[key as keyof FakeComputedProps]!;
  }
  const result: FakeComputedStyle = {
    ...style,
    getPropertyValue: (name: string): string => {
      const key = String(name).toLowerCase();
      return Object.prototype.hasOwnProperty.call(props, key)
        ? String(props[key])
        : "";
    },
  };
  return result;
}

function withEnv<T>(fn: () => T, overrides: Record<string, never> = {}): T {
  const saved = saveGlobals(["getComputedStyle"]);
  try {
    globalThis.getComputedStyle = ((target: Element | null, pseudo?: string | null): FakeComputedStyle =>
      target && (target as FakeElement)._computedValues
        ? computedStyle((target as FakeElement)._computedValues)
        : computedStyle()) as any;
    return fn();
  } finally {
    restoreGlobals(saved);
  }
}

function textStyle(overrides: Partial<TextStyle> = {}): TextStyle {
  return {
    fontFamilies: ["Noto Serif CJK SC"],
    fontSize: 19,
    fontWeight: 400,
    italic: false,
    baselineShift: 0,
    locale: "zh-Hans",
    ...overrides,
  } as TextStyle;
}

function span(overrides: SpanOverrides = {}): TextSpan {
  return {
    start: 0,
    end: 2,
    style: textStyle(),
    ...overrides,
  } as TextSpan;
}

function inlineBoxStyle(overrides: InlineBoxStyleOverrides = {}): DomInlineBoxStyle {
  return {
    inlineStart: 0,
    inlineEnd: 0,
    marginRight: 0,
    letterSpacing: 0,
    boxDecorationBreak: "slice",
    ...overrides,
  } as DomInlineBoxStyle;
}

function sourceSpan(overrides: SourceSpanOverrides = {}): DomSourceSpan {
  return {
    start: 0,
    end: 2,
    element: { tagName: "EM", attributes: [] },
    depth: 0,
    cjkStrongBaseWeight: null,
    computedColor: null,
    inlineBoxStyle: inlineBoxStyle(),
    ...overrides,
  } as unknown as DomSourceSpan;
}

function paragraph(overrides: ParagraphOverrides = {}): LoweredParagraph {
  return {
    text: "ab",
    textStyle: textStyle(),
    lineHeight: 28,
    spans: [],
    decorations: [],
    inlineBoxes: [],
    inlineObjects: [],
    domInlineObjects: [],
    sourceSpans: [],
    sourceBoundaries: [],
    lineBreakSpans: [],
    ...overrides,
  } as LoweredParagraph;
}

function element(tagName: string = "P", overrides: ElementOverrides = {}): FakeElement {
  return {
    tagName,
    getBoundingClientRect: (): { width: number } => ({ width: overrides.width ?? 320 }),
    getClientRects: (): DOMRectList => [] as unknown as DOMRectList,
    parentElement: null,
    ...overrides,
  } as FakeElement;
}

const RICH_LOWERED: LoweredParagraph = paragraph({
  text: "abcde",
  textStyle: textStyle({ fontFamilies: ["Serif A", "Serif B"] }),
  sourceBoundaries: [0, 2, 2, 5],
  spans: [
    span({
      start: 0,
      end: 2,
      style: textStyle({
        fontFamilies: ["A", "B"],
        fontSize: 12.5,
        fontWeight: 500,
        italic: true,
        baselineShift: 1.5,
      }),
    }),
    span({
      start: 2,
      end: 5,
      style: textStyle({
        fontFamilies: ["C"],
        fontSize: 13.25,
        fontWeight: 600,
        italic: false,
        baselineShift: 0,
      }),
    }),
  ],
  inlineBoxes: [{ start: 0, end: 2, inlineStart: 1.5, inlineEnd: 2.25 }],
  lineBreakSpans: [{ start: 1, end: 3, policy: "ProgressiveTechnical" }],
  inlineObjects: [{ start: 4, end: 5, advance: 6.5, ascent: 5, descent: 1.25 }],
  decorations: [
    { start: 0, end: 2, kind: "Emphasis" },
    { start: 3, end: 5, kind: "Mourning" },
  ],
});

const RICH_ELEMENT: FakeElement = element("P");

function makeBridge(): BrowserFallbackBridge {
  return {
    shapeJson(req: string): string {
      const parsed: BridgeShapeRequest = JSON.parse(req);
      const text = parsed.text;
      const start = parsed.range.start;
      const end = parsed.range.end;
      const size = parsed.style.fontSize;
      const clusters: BridgeCluster[] = [];
      const glyphs: BridgeGlyph[] = [];
      let x = 0;
      for (let i = start; i < end; i += 1) {
        const ch = text[i];
        clusters.push({
          range: { start: i, end: i + 1 },
          text: ch,
          displayText: ch,
          fontKey: "cjk-primary",
          advance: size,
          baselineShift: 0,
        });
        glyphs.push({
          id: 100 + i,
          clusterRange: { start: i, end: i + 1 },
          advance: size,
          x,
          y: 0,
          bounds: { left: 0, top: -size * 0.88, right: size, bottom: size * 0.12 },
        });
        x += size;
      }
      const response: BridgeShapeResponse = {
        clusters,
        glyphRuns: [{ range: { start, end }, fontKey: "cjk-primary", glyphs, advance: x, openTypeFeatures: [] }],
        decisions: [{ range: { start, end }, sourceText: text.substring(start, end), displayText: parsed.displayText, fontKey: "cjk-primary", glyphCount: end - start, advance: x, source: "Harness", reason: "harness" }],
      };
      return JSON.stringify(response);
    },
    metricsJson(): string {
      const response: BridgeMetricsResponse = { ascent: 21.2, descent: 5.3, leading: 0, source: "RawTables", typoAscent: 16.7, typoDescent: 2.3 };
      return JSON.stringify(response);
    },
  };
}

const RICH_BROWSER_FALLBACK = { bridge: makeBridge() };

type BackendWithCallbacks = {
  uninstall: () => void;
  shapeJson: (req: string) => string;
  metricsJson: (req: string) => string;
};

function snapshotSessionCallbacksOf(backend: BackendWithCallbacks): PrepareSnapshotSessionDescriptor {
  return { shapeJson: backend.shapeJson, metricsJson: backend.metricsJson };
}

function fixtureSnapshotSession(): PrepareSnapshotSessionDescriptor {
  return snapshotSessionCallbacksOf(installFixtureFontBackend() as BackendWithCallbacks);
}

function snapshotArgument(overrides: SnapshotArgumentOverrides = {}): any {
  const { snapshotSession = fixtureSnapshotSession(), ...rest } = overrides;
  return {
    paragraph: { source: RICH_ELEMENT as any, lowered: RICH_LOWERED, lastMeasure: null },
    options: { firstLineIndentIc: 2, emphasisDotGapEm: null },
    snapshotSession,
    browserFallback: RICH_BROWSER_FALLBACK as any,
    widthOverride: null,
    ignoreUnchangedMeasure: false,
    ...rest,
  };
}

const DEFAULT_MEASURE = effectiveLineMeasure(320, 19);

test("returns unchanged when lastMeasure matches the effective measure", () => {
  withEnv(() => {
    const result = prepareParagraphLayout(snapshotArgument({
      paragraph: { source: RICH_ELEMENT, lowered: RICH_LOWERED, lastMeasure: DEFAULT_MEASURE },
    }));
    assert.deepEqual(result, { kind: "unchanged" });
  });
});

test("ignoreUnchangedMeasure proceeds despite a matching lastMeasure", () => {
  const backend = installFixtureFontBackend() as BackendWithCallbacks;
  try {
    withEnv(() => {
      const result = prepareParagraphLayout(snapshotArgument({
        paragraph: { source: RICH_ELEMENT, lowered: RICH_LOWERED, lastMeasure: DEFAULT_MEASURE },
        ignoreUnchangedMeasure: true,
      }));
      assert.equal(result.kind, "ready");
    });
  } finally {
    backend.uninstall();
  }
});

test("widthOverride wins and ready.width is raw while ffi receives the measure", () => {
  const backend = installFixtureFontBackend() as BackendWithCallbacks;
  try {
    withEnv(() => {
      const expectedMeasure = effectiveLineMeasure(200, 19);
      const result = prepareParagraphLayout(snapshotArgument({ widthOverride: 200 }));
      assert.equal(result.kind, "ready");
      assert.equal(result.width, 200);
      assert.equal(result.measure, expectedMeasure);
      const wire = wireArguments(RICH_LOWERED);
      assert.equal(wire.text, "abcde");
    });
  } finally {
    backend.uninstall();
  }
});

test("SpanLocaleMismatchUnsupported uses the first mismatching span", () => {
  withEnv(() => {
    const lowered = paragraph({
      text: "abcde",
      spans: [
        span({ start: 2, end: 5, style: textStyle({ locale: "ja" }) }),
        span({ start: 0, end: 2, style: textStyle({ locale: "ko" }) }),
      ],
    });
    const result = prepareParagraphLayout(snapshotArgument({
      paragraph: { source: RICH_ELEMENT, lowered, lastMeasure: null },
    }));
    assert.equal(result.kind, "unsupported");
    assert.equal(result.name, "SpanLocaleMismatchUnsupported");
    assert.equal(result.detail, "spanRange=2..5; spanLocale=ja; paragraphLocale=zh-Hans");
  });
});

test("wire byte lock: wireArguments DTO carries the full structured argument", () => {
  withEnv(() => {
    const wire = wireArguments(RICH_LOWERED);
    assert.equal(wire.text, "abcde");
    assert.equal(wire.fontFamilies[0], "Serif A");
    assert.equal(wire.fontFamilies[1], "Serif B");
    assert.equal(wire.fontSizePx, 19);
    assert.equal(wire.lineHeightPx, 28);
    assert.equal(wire.locale, "zh-Hans");
    assert.equal(wire.fontWeight, 400);
    assert.equal(wire.italic, false);
    assert.equal(wire.firstLineIndentIc, 0);
    assert.equal(wire.lineLengthGridEnabled, true);
    assert.deepEqual(wire.sourceBoundaries, [0, 2, 5]);
    assert.equal(wire.textSpans.length, 2);
    assert.equal(wire.textSpans[0].start, 0);
    assert.equal(wire.textSpans[0].end, 2);
    assert.deepEqual(wire.textSpans[0].fontFamilies, ["A", "B"]);
    assert.equal(wire.textSpans[0].fontSize, 12.5);
    assert.equal(wire.textSpans[0].fontWeight, 500);
    assert.equal(wire.textSpans[0].italic, true);
    assert.equal(wire.textSpans[0].baselineShift, 1.5);
    assert.equal(wire.textSpans[1].start, 2);
    assert.equal(wire.textSpans[1].end, 5);
    assert.deepEqual(wire.textSpans[1].fontFamilies, ["C"]);
    assert.equal(wire.textSpans[1].fontSize, 13.25);
    assert.equal(wire.textSpans[1].fontWeight, 600);
    assert.equal(wire.textSpans[1].italic, false);
    assert.equal(wire.textSpans[1].baselineShift, 0);
    assert.equal(wire.inlineBoxes.length, 1);
    assert.equal(wire.inlineBoxes[0].start, 0);
    assert.equal(wire.inlineBoxes[0].end, 2);
    assert.equal(wire.inlineBoxes[0].inlineStart, 1.5);
    assert.equal(wire.inlineBoxes[0].inlineEnd, 2.25);
    assert.equal(wire.inlineBoxes[0].outerSpacing, "Narrow");
    assert.equal(wire.lineBreakSpans.length, 1);
    assert.equal(wire.lineBreakSpans[0].start, 1);
    assert.equal(wire.lineBreakSpans[0].end, 3);
    assert.equal(wire.lineBreakSpans[0].policy, "ProgressiveTechnical");
    assert.equal(wire.inlineObjects.length, 1);
    assert.equal(wire.inlineObjects[0].start, 4);
    assert.equal(wire.inlineObjects[0].end, 5);
    assert.equal(wire.inlineObjects[0].advance, 6.5);
    assert.equal(wire.inlineObjects[0].ascent, 5);
    assert.equal(wire.inlineObjects[0].descent, 1.25);
    assert.equal(wire.decorations.length, 2);
    assert.equal(wire.decorations[0].start, 0);
    assert.equal(wire.decorations[0].end, 2);
    assert.equal(wire.decorations[0].kind, "Emphasis");
    assert.equal(wire.decorations[1].start, 3);
    assert.equal(wire.decorations[1].end, 5);
    assert.equal(wire.decorations[1].kind, "Mourning");
    assert.equal(wire.emphasisDotGapEm, null);
    assert.equal(wire.renderEvidenceOverride, null);

    const backend = installFixtureFontBackend() as BackendWithCallbacks;
    try {
      const result = prepareParagraphLayout(snapshotArgument());
      assert.equal(result.kind, "ready");
      assert.equal(result.measure, DEFAULT_MEASURE);
    } finally {
      backend.uninstall();
    }
  });
});

test("render evidence override carries the six-collection verdict", () => {
  const backend = installFixtureFontBackend() as BackendWithCallbacks;
  try {
    withEnv(() => {
      const linkOnly = paragraph({
        text: "abcde",
        sourceSpans: [sourceSpan({ start: 0, end: 5 })],
      });
      const plain = paragraph({ text: "abcde" });
      const linkWire = wireArguments(linkOnly);
      const plainWire = wireArguments(plain);
      assert.equal(linkWire.renderEvidenceOverride, null);
      assert.equal(plainWire.renderEvidenceOverride, null);
    });
  } finally {
    backend.uninstall();
  }
});

test("firstLineIndentIc is zero for LI and the option value otherwise", () => {
  const backend = installFixtureFontBackend() as BackendWithCallbacks;
  try {
    withEnv(() => {
      const li = prepareParagraphLayout(snapshotArgument({
        paragraph: { source: element("LI"), lowered: RICH_LOWERED, lastMeasure: null },
        options: { firstLineIndentIc: 4, emphasisDotGapEm: null },
      }));
      assert.equal(li.kind, "ready");

      const nonLi = prepareParagraphLayout(snapshotArgument({
        options: { firstLineIndentIc: 4, emphasisDotGapEm: null },
      }));
      assert.equal(nonLi.kind, "ready");
    });
  } finally {
    backend.uninstall();
  }
});

test("capabilityIssues[0] produces an unsupported verdict with name and reason", () => {
  const backend = installThrowingFontBackend(new Error("NoSnapshotFontFace: session miss")) as BackendWithCallbacks;
  const bridge = makeBridge();
  const originalShapeJson = bridge.shapeJson;
  bridge.shapeJson = function (req: string): string {
    const parsed: BridgeShapeRequest = JSON.parse(req);
    const inner: BridgeShapeResponse = JSON.parse(originalShapeJson(req));
    inner.decisions = [{
      range: { start: parsed.range.start, end: parsed.range.end },
      sourceText: parsed.text.substring(parsed.range.start, parsed.range.end),
      displayText: parsed.displayText,
      fontKey: "cjk-primary",
      glyphCount: parsed.range.end - parsed.range.start,
      advance: parsed.range.end - parsed.range.start,
      source: "Harness",
      reason: "no dash face",
      capabilityIssue: "NoConformingCjkDashGlyph",
    }];
    return JSON.stringify(inner);
  };
  try {
    withEnv(() => {
      const result = prepareParagraphLayout(snapshotArgument({ snapshotSession: snapshotSessionCallbacksOf(backend), browserFallback: { bridge } }));
      assert.deepEqual(result, {
        kind: "unsupported",
        name: "NoConformingCjkDashGlyph",
        detail: "no dash face",
        element: RICH_ELEMENT,
      });
    });
  } finally {
    backend.uninstall();
  }
});

test("advance suspects skip empty and newline display text, then the first real suspect wins", () => {
  const bridge = makeBridge();
  const originalShapeJson = bridge.shapeJson;
  bridge.shapeJson = function (req: string): string {
    const parsed: BridgeShapeRequest = JSON.parse(req);
    const inner: BridgeShapeResponse = JSON.parse(originalShapeJson(req));
    const start = parsed.range.start;
    const end = parsed.range.end;
    const ch = parsed.text.substring(start, end);
    let decisionDisplay = parsed.displayText;
    let advance = end - start;
    let reason = "harness";
    if (ch === "\u200b") {
      decisionDisplay = "";
      advance = 0;
      reason = "empty";
    } else if (ch === "\u4e2d") {
      decisionDisplay = "a\nb";
      advance = 0;
      reason = "newline";
    } else if (ch === "\u2014") {
      advance = 0;
      reason = "zero advance";
    }
    inner.decisions = [{
      range: { start, end },
      sourceText: ch,
      displayText: decisionDisplay,
      fontKey: "cjk-primary",
      glyphCount: end - start,
      advance,
      source: "Harness",
      reason,
    }];
    return JSON.stringify(inner);
  };
  withEnv(() => {
    const lowered = paragraph({
      text: "\u200b\u4e2d\u2014",
      sourceSpans: [sourceSpan({ start: 0, end: 3 })],
    });
    const result = prepareParagraphLayout(snapshotArgument({
      paragraph: { source: RICH_ELEMENT, lowered, lastMeasure: null },
      snapshotSession: null,
      browserFallback: { bridge },
    }));
    assert.deepEqual(result, {
      kind: "unsupported",
      name: "InvalidWebShapingAdvance",
      detail: "text=\u2014; advance=0; zero advance",
      element: RICH_ELEMENT,
    });
  });
});

test("clone decoration crossed by two plan lines is unsupported with the lowercased tag", () => {
  const backend = installFixtureFontBackend() as BackendWithCallbacks;
  try {
    withEnv(() => {
      const lowered = paragraph({
        text: "abcde",
        sourceSpans: [
          sourceSpan({
            start: 1,
            end: 3,
            element: { tagName: "SPAN", attributes: [] },
            inlineBoxStyle: inlineBoxStyle({ boxDecorationBreak: "clone", inlineStart: 5 }),
          }),
        ],
      });
      const result = prepareParagraphLayout(snapshotArgument({
        paragraph: { source: RICH_ELEMENT, lowered, lastMeasure: null },
        widthOverride: 64,
      }));
      assert.deepEqual(result, {
        kind: "unsupported",
        name: "InlineCloneDecorationBreakUnsupported",
        detail: "span",
        element: RICH_ELEMENT,
      });
    });
  } finally {
    backend.uninstall();
  }
});

test("clone decoration on a single line does not trigger", () => {
  const backend = installFixtureFontBackend() as BackendWithCallbacks;
  try {
    withEnv(() => {
      const lowered = paragraph({
        text: "abcde",
        sourceSpans: [
          sourceSpan({
            start: 1,
            end: 3,
            element: { tagName: "SPAN", attributes: [] },
            inlineBoxStyle: inlineBoxStyle({ boxDecorationBreak: "clone", inlineStart: 5 }),
          }),
        ],
      });
      const result = prepareParagraphLayout(snapshotArgument({
        paragraph: { source: RICH_ELEMENT, lowered, lastMeasure: null },
      }));
      assert.equal(result.kind, "ready");
    });
  } finally {
    backend.uninstall();
  }
});

test("a non-clone span with edges never triggers the clone verdict", () => {
  const backend = installFixtureFontBackend() as BackendWithCallbacks;
  try {
    withEnv(() => {
      const lowered = paragraph({
        text: "abcde",
        sourceSpans: [
          sourceSpan({
            start: 1,
            end: 3,
            element: { tagName: "SPAN", attributes: [] },
            inlineBoxStyle: inlineBoxStyle({ boxDecorationBreak: "slice", inlineStart: 5 }),
          }),
        ],
      });
      const result = prepareParagraphLayout(snapshotArgument({
        paragraph: { source: RICH_ELEMENT, lowered, lastMeasure: null },
      }));
      assert.equal(result.kind, "ready");
    });
  } finally {
    backend.uninstall();
  }
});

test("a capability-failure throws retry through the browser metrics call", () => {
  const backend = installThrowingFontBackend(new Error("NoSnapshotFontFace: session miss")) as BackendWithCallbacks;
  try {
    withEnv(() => {
      const result = prepareParagraphLayout(snapshotArgument({ snapshotSession: snapshotSessionCallbacksOf(backend) }));
      assert.equal(result.kind, "ready");
      assert.equal(result.snapshotFontSessionUsed, false);
    });
  } finally {
    backend.uninstall();
  }
});

test("another capability-failure name triggers the retry", () => {
  const backend = installThrowingFontBackend(new Error("MissingServerShapingReplay: no replay")) as BackendWithCallbacks;
  try {
    withEnv(() => {
      const result = prepareParagraphLayout(snapshotArgument({ snapshotSession: snapshotSessionCallbacksOf(backend) }));
      assert.equal(result.kind, "ready");
      assert.equal(result.snapshotFontSessionUsed, false);
    });
  } finally {
    backend.uninstall();
  }
});

test("a non-matching error rethrows", () => {
  const backend = installThrowingFontBackend(new Error("some unrelated failure")) as BackendWithCallbacks;
  try {
    withEnv(() => {
      assert.throws(() => prepareParagraphLayout(snapshotArgument({ snapshotSession: snapshotSessionCallbacksOf(backend) })), /some unrelated failure/);
    });
  } finally {
    backend.uninstall();
  }
});

test("snapshotSession == null runs the browser metrics call directly without a sessionId", () => {
  withEnv(() => {
    const result = prepareParagraphLayout(snapshotArgument({
      snapshotSession: null,
      browserFallback: RICH_BROWSER_FALLBACK,
    }));
    assert.equal(result.kind, "ready");
    assert.equal(result.snapshotFontSessionUsed, false);
  });
});

test("snapshotSession == null with a missing browserFallback throws", () => {
  withEnv(() => {
    assert.throws(
      () => prepareParagraphLayout(snapshotArgument({ snapshotSession: null, browserFallback: null })),
      /missing browserFallback descriptor/,
    );
  });
});

test("ready shape carries the envelope pieces on the happy exact path", () => {
  const backend = installFixtureFontBackend() as BackendWithCallbacks;
  try {
    withEnv(() => {
      const result = prepareParagraphLayout(snapshotArgument({
        paragraph: { source: RICH_ELEMENT, lowered: RICH_LOWERED, lastMeasure: null },
      }));
      assert.equal(result.kind, "ready");
      assert.equal(result.snapshotFontSessionUsed, true);
      assert.equal(result.width, 320);
      assert.equal(result.measure, DEFAULT_MEASURE);
      assert.equal(result.plan.lines[0].rangeStart, 0);
      assert.equal(result.plan.lines[result.plan.lines.length - 1].rangeEnd, 5);
      assert.deepEqual(result.diagnostics, { capabilityIssues: [], advanceSuspects: [] });
      assert.equal(typeof result.rawEnvelope, "string");
      assert.equal(result.planJson, result.plan ? JSON.stringify(result.plan) : null);
    });
  } finally {
    backend.uninstall();
  }
});

test("emphasisDotGapEm passes through to the DTO", () => {
  const backend = installFixtureFontBackend() as BackendWithCallbacks;
  try {
    withEnv(() => {
      const wire = wireArguments(RICH_LOWERED);
      assert.equal(wire.emphasisDotGapEm, null);
    });
  } finally {
    backend.uninstall();
  }
});
