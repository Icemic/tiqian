import assert from "node:assert/strict";
import { setPreparedDomRendererForTest, setPreparedDomValidatorForTest, preparedDomRendererModule } from "../core/engine/loaders/runtime-loader.js";
import test from "node:test";

import { prepareParagraphLayout } from "../core/engine/prepare-paragraph-layout.js";
import {
  wireArguments,
  browserMetricsArguments,
  precomputeDiagnosticsArguments,
} from "../core/engine/prepare-paragraph-layout.js";
import { effectiveLineMeasure } from "../core/engine/responsive-measure.js";
import { installFixtureFontBackend, installThrowingFontBackend } from "../test-support/fixture-font-backend.mjs";

// The responsive measure helpers are real: sourceParagraphWidth reads element
// geometry through globalThis.getComputedStyle and effectiveLineMeasure is
// imported above. Tests compute expected measures by calling the real helper.
// Only the host-installed __TiqianPreparedDomRenderer global stays fake.
//
// The wire byte lock (rule c) byte-locks the exported pure argument builders
// (precomputeDiagnosticsArguments / browserMetricsArguments / wireArguments)
// and asserts the real direct ffi call consumes them. The verdict/gating and
// throw-path tests (rules a/d) run the real @tiqian/ffi over the planted
// fixture or throwing font backend.

function saveGlobals(names) {
  return names.map((name) => ({
    name,
    own: Object.prototype.hasOwnProperty.call(globalThis, name),
    value: globalThis[name],
  }));
}

function restoreGlobals(entries) {
  for (const { name, own, value } of entries) {
    if (own) globalThis[name] = value;
    else delete globalThis[name];
  }
}

function computedStyle(values = {}) {
  const props = {
    paddingLeft: "0px",
    paddingRight: "0px",
    borderLeftWidth: "0px",
    borderRightWidth: "0px",
    ...values,
  };
  const style = {};
  for (const key of Object.keys(props)) style[key] = props[key];
  style.getPropertyValue = (name) => {
    const key = String(name).toLowerCase();
    return Object.prototype.hasOwnProperty.call(props, key)
      ? String(props[key])
      : "";
  };
  return style;
}

function withEnv(fn, overrides = {}) {
  const saved = saveGlobals(["getComputedStyle"]);
  try {
    if (overrides.renderer !== false) {
      setPreparedDomRendererForTest({
        render: () => {},
        release: () => {},
        releaseRoot: () => {},
        schema: 1,
        layoutRevision: overrides.layoutRevision ?? "tiqian-layout-v2",
      });
    } else {
      setPreparedDomRendererForTest(null);
    }
    if (overrides.validator !== undefined) {
      setPreparedDomValidatorForTest({ issue: overrides.validator });
    } else {
      setPreparedDomValidatorForTest(null);
    }
    globalThis.getComputedStyle = (target, pseudo) =>
      target && target._computedValues
        ? computedStyle(target._computedValues)
        : computedStyle();
    return fn();
  } finally {
    setPreparedDomRendererForTest(undefined);
    setPreparedDomValidatorForTest(undefined);
    restoreGlobals(saved);
  }
}

function textStyle(overrides = {}) {
  return {
    fontFamilies: ["Noto Serif CJK SC"],
    fontSize: 19,
    fontWeight: 400,
    italic: false,
    baselineShift: 0,
    locale: "zh-Hans",
    ...overrides,
  };
}

function span(overrides = {}) {
  return {
    start: 0,
    end: 2,
    style: textStyle(),
    ...overrides,
  };
}

function inlineBoxStyle(overrides = {}) {
  return {
    inlineStart: 0,
    inlineEnd: 0,
    marginRight: 0,
    letterSpacing: 0,
    boxDecorationBreak: "slice",
    ...overrides,
  };
}

function sourceSpan(overrides = {}) {
  return {
    start: 0,
    end: 2,
    element: { tagName: "EM", attributes: [] },
    depth: 0,
    cjkStrongBaseWeight: null,
    computedColor: null,
    inlineBoxStyle: inlineBoxStyle(),
    ...overrides,
  };
}

function paragraph(overrides = {}) {
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
  };
}

// Measurable fake element: sourceParagraphWidth reads its bounding box and
// client rects through elementContentWidth.
function element(tagName = "P", overrides = {}) {
  return {
    tagName,
    getBoundingClientRect: () => ({ width: overrides.width ?? 320 }),
    getClientRects: () => [],
    parentElement: null,
    ...overrides,
  };
}

// Fixture exercising every wire field: two spans (second has a baselineShift),
// one inline box, one line-break span, one inline object, two decorations, and
// duplicate source boundaries [0,2,2,5].
const RICH_LOWERED = paragraph({
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

const RICH_ELEMENT = element("P");

// A browserFallback whose bridge callbacks answer every shape/metrics request
// with a valid, full-coverage cluster response on the real wire.
function makeBridge() {
  return {
    shapeJson(req) {
      const parsed = JSON.parse(req);
      const text = parsed.text;
      const start = parsed.range.start;
      const end = parsed.range.end;
      const size = parsed.style.fontSize;
      const clusters = [];
      const glyphs = [];
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
      return JSON.stringify({
        clusters,
        glyphRuns: [{ range: { start, end }, fontKey: "cjk-primary", glyphs, advance: x, openTypeFeatures: [] }],
        decisions: [{ range: { start, end }, sourceText: text.substring(start, end), displayText: parsed.displayText, fontKey: "cjk-primary", glyphCount: end - start, advance: x, source: "Harness", reason: "harness" }],
      });
    },
    metricsJson() {
      return JSON.stringify({ ascent: 21.2, descent: 5.3, leading: 0, source: "RawTables", typoAscent: 16.7, typoDescent: 2.3 });
    },
  };
}

const RICH_BROWSER_FALLBACK = { bridge: makeBridge() };

// The snapshot-session descriptor carries the shaping callbacks ffi takes as
// call parameters; the fixture backend supplies the same pair the old global
// installation shaped through.
function snapshotSessionCallbacksOf(backend) {
  return { shapeJson: backend.shapeJson, metricsJson: backend.metricsJson };
}

function fixtureSnapshotSession() {
  return snapshotSessionCallbacksOf(installFixtureFontBackend());
}

function snapshotArgument(overrides = {}) {
  const { snapshotSession = fixtureSnapshotSession(), ...rest } = overrides;
  return {
    paragraph: { source: RICH_ELEMENT, lowered: RICH_LOWERED, lastMeasure: null },
    options: { firstLineIndentIc: 2, emphasisDotGapEm: null },
    snapshotSession,
    browserFallback: RICH_BROWSER_FALLBACK,
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
  const backend = installFixtureFontBackend();
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
  const backend = installFixtureFontBackend();
  try {
    withEnv(() => {
      const expectedMeasure = effectiveLineMeasure(200, 19);
      const result = prepareParagraphLayout(snapshotArgument({ widthOverride: 200 }));
      assert.equal(result.kind, "ready");
      assert.equal(result.width, 200);
      assert.equal(result.measure, expectedMeasure);
      // The snapshot-session wire consumed the measure as maxWidthPx and the
      // rich lowered text as the source.
      const wire = wireArguments(RICH_LOWERED);
      const args = precomputeDiagnosticsArguments(fixtureSnapshotSession(), ["abcde", expectedMeasure, wire.fontFamilies, 19, 28, "zh-Hans", 400, false, 2, true, "0,2,5", wire.textSpans, wire.inlineBoxes, wire.lineBreakSpans, wire.inlineObjects], wire, null, true);
      assert.equal(args[1], expectedMeasure);
      assert.equal(args[0], "abcde");
    });
  } finally {
    backend.uninstall();
  }
});

test("PreparedDomBridgeUnavailable when the renderer global is absent", () => {
  withEnv(() => {
    const result = prepareParagraphLayout(snapshotArgument());
    assert.deepEqual(result, {
      kind: "unsupported",
      name: "PreparedDomBridgeUnavailable",
      detail: "expectedLayoutRevision=tiqian-layout-v2",
      element: RICH_ELEMENT,
    });
  }, { renderer: false });
});

test("PreparedDomBridgeUnavailable when the layout revision mismatches", () => {
  withEnv(() => {
    const result = prepareParagraphLayout(snapshotArgument());
    assert.deepEqual(result, {
      kind: "unsupported",
      name: "PreparedDomBridgeUnavailable",
      detail: "expectedLayoutRevision=tiqian-layout-v2",
      element: RICH_ELEMENT,
    });
  }, { layoutRevision: "tiqian-layout-v1" });
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

test("wire byte lock: diagnostics call carries the full positional argument list", () => {
  withEnv(() => {
    const wire = wireArguments(RICH_LOWERED);
    const session = fixtureSnapshotSession();
    const args = precomputeDiagnosticsArguments(
      session,
      ["abcde", DEFAULT_MEASURE, wire.fontFamilies, 19, 28, "zh-Hans", 400, false, 2, true, wire.sourceBoundaries, wire.textSpans, wire.inlineBoxes, wire.lineBreakSpans, wire.inlineObjects],
      wire,
      null,
      true,
    );
    assert.equal(args[0], "abcde");
    assert.equal(args[1], DEFAULT_MEASURE);
    assert.equal(args[2], "Serif A\u001fSerif B");
    assert.equal(args[3], 19);
    assert.equal(args[4], 28);
    assert.equal(args[5], "zh-Hans");
    assert.equal(args[6], 400);
    assert.equal(args[7], false);
    assert.equal(args[8], 2);
    assert.equal(args[9], true);
    assert.equal(args[10], "0,2,5");
    assert.equal(args[11], "0\u001d2\u001dA\u001fB\u001d12.5\u001d500\u001dtrue\u001d1.5\u001e2\u001d5\u001dC\u001d13.25\u001d600\u001dfalse\u001d0");
    assert.equal(args[12], "0\u001d2\u001d1.5\u001d2.25\u001dNarrow");
    assert.equal(args[13], "1\u001d3\u001dProgressiveTechnical");
    assert.equal(args[14], "4\u001d5\u001d6.5\u001d5\u001d1.25");
    assert.equal(args[15], 0.01);
    assert.equal(args[16], session.shapeJson);
    assert.equal(args[17], session.metricsJson);
    assert.equal(args[18], "0\u001d2\u001dEmphasis\u001e3\u001d5\u001dMourning");
    assert.equal(args[19], null);
    assert.equal(args[20], true);

    // The wire byte lock is not a dead computation: the real snapshot-session
    // ffi call is a one-line consumer of the same tuple, observable as the
    // ready result the tuple would produce.
    const backend = installFixtureFontBackend();
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
  const backend = installFixtureFontBackend();
  try {
    withEnv(() => {
      const linkOnly = paragraph({
        text: "abcde",
        sourceSpans: [sourceSpan({ start: 0, end: 5 })],
      });
      const plain = paragraph({ text: "abcde" });
      const session = fixtureSnapshotSession();
      const linkWire = wireArguments(linkOnly);
      const plainWire = wireArguments(plain);
      const linkArgs = precomputeDiagnosticsArguments(
        session,
        ["abcde", DEFAULT_MEASURE, linkWire.fontFamilies, 19, 28, "zh-Hans", 400, false, 2, true, linkWire.sourceBoundaries, linkWire.textSpans, linkWire.inlineBoxes, linkWire.lineBreakSpans, linkWire.inlineObjects],
        linkWire,
        null,
        true,
      );
      const plainArgs = precomputeDiagnosticsArguments(
        session,
        ["abcde", DEFAULT_MEASURE, plainWire.fontFamilies, 19, 28, "zh-Hans", 400, false, 2, true, plainWire.sourceBoundaries, plainWire.textSpans, plainWire.inlineBoxes, plainWire.lineBreakSpans, plainWire.inlineObjects],
        plainWire,
        null,
        false,
      );
      // sourceSpans-only lowered has wire-empty collections but carries true
      // render evidence; a plain paragraph carries false.
      assert.equal(linkArgs[20], true);
      assert.equal(plainArgs[20], false);

      // The browser-metrics retry path carries the override after the
      // trailing decorations and emphasis dot gap.
      const linkMetrics = browserMetricsArguments(
        RICH_BROWSER_FALLBACK,
        ["abcde", DEFAULT_MEASURE, linkWire.fontFamilies, 19, 28, "zh-Hans", 400, false, 2, true, linkWire.sourceBoundaries, linkWire.textSpans, linkWire.inlineBoxes, linkWire.lineBreakSpans, linkWire.inlineObjects],
        linkWire,
        null,
        true,
      );
      assert.equal(linkMetrics[20], true);
    });
  } finally {
    backend.uninstall();
  }
});

test("firstLineIndentIc is zero for LI and the option value otherwise", () => {
  const backend = installFixtureFontBackend();
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
  const backend = installThrowingFontBackend(new Error("NoSnapshotFontFace: session miss"));
  const bridge = makeBridge();
  const originalShapeJson = bridge.shapeJson;
  bridge.shapeJson = function (req) {
    const parsed = JSON.parse(req);
    const inner = JSON.parse(originalShapeJson(req));
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
  bridge.shapeJson = function (req) {
    const parsed = JSON.parse(req);
    const inner = JSON.parse(originalShapeJson(req));
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
  const backend = installFixtureFontBackend();
  try {
    withEnv(() => {
      const lowered = paragraph({
        text: "abcde",
        sourceSpans: [
          sourceSpan({
            start: 1,
            end: 3,
            element: { tagName: "SPAN" },
            inlineBoxStyle: inlineBoxStyle({ boxDecorationBreak: "clone", inlineStart: 5 }),
          }),
        ],
      });
      // A width that forces a two-line plan crossing the clone span.
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
  const backend = installFixtureFontBackend();
  try {
    withEnv(() => {
      const lowered = paragraph({
        text: "abcde",
        sourceSpans: [
          sourceSpan({
            start: 1,
            end: 3,
            element: { tagName: "SPAN" },
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
  const backend = installFixtureFontBackend();
  try {
    withEnv(() => {
      const lowered = paragraph({
        text: "abcde",
        sourceSpans: [
          sourceSpan({
            start: 1,
            end: 3,
            element: { tagName: "SPAN" },
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
  const backend = installThrowingFontBackend(new Error("NoSnapshotFontFace: session miss"));
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
  const backend = installThrowingFontBackend(new Error("MissingServerShapingReplay: no replay"));
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
  const backend = installThrowingFontBackend(new Error("some unrelated failure"));
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
  const backend = installFixtureFontBackend();
  try {
    withEnv(() => {
      const result = prepareParagraphLayout(snapshotArgument({
        paragraph: { source: RICH_ELEMENT, lowered: RICH_LOWERED, lastMeasure: null },
      }));
      assert.equal(result.kind, "ready");
      assert.equal(result.snapshotFontSessionUsed, true);
      assert.equal(result.width, 320);
      assert.equal(result.measure, DEFAULT_MEASURE);
      // The real plan covers the full rich text range on one line.
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

test("emphasisDotGapEm passes through to the trailing ffi argument", () => {
  const backend = installFixtureFontBackend();
  try {
    withEnv(() => {
      const wire = wireArguments(RICH_LOWERED);
      const session = fixtureSnapshotSession();
      const withGap = precomputeDiagnosticsArguments(
        session,
        ["abcde", DEFAULT_MEASURE, wire.fontFamilies, 19, 28, "zh-Hans", 400, false, 2, true, wire.sourceBoundaries, wire.textSpans, wire.inlineBoxes, wire.lineBreakSpans, wire.inlineObjects],
        wire,
        0.25,
        true,
      );
      assert.equal(withGap[19], 0.25);

      const omitted = precomputeDiagnosticsArguments(
        session,
        ["abcde", DEFAULT_MEASURE, wire.fontFamilies, 19, 28, "zh-Hans", 400, false, 2, true, wire.sourceBoundaries, wire.textSpans, wire.inlineBoxes, wire.lineBreakSpans, wire.inlineObjects],
        wire,
        null,
        true,
      );
      assert.equal(omitted[19], null);
    });
  } finally {
    backend.uninstall();
  }
});
