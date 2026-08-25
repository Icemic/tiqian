import assert from "node:assert/strict";
import test from "node:test";

import { prepareParagraphLayout } from "./core/engine/prepare-paragraph-layout.js";
import { effectiveLineMeasure } from "./core/engine/responsive-measure.js";

// The responsive measure helpers are real: sourceParagraphWidth reads element
// geometry through globalThis.getComputedStyle and effectiveLineMeasure is
// imported above. Tests compute expected measures by calling the real helper.
// Only the host-installed __TiqianPreparedDomRenderer global stays fake.

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
  const saved = saveGlobals(["getComputedStyle", "__TiqianPreparedDomRenderer"]);
  try {
    if (overrides.renderer !== false) {
      globalThis.__TiqianPreparedDomRenderer = {
        render: () => {},
        release: () => {},
        releaseRoot: () => {},
        schema: 1,
        layoutRevision: overrides.layoutRevision ?? "tiqian-layout-v2",
      };
    }
    globalThis.getComputedStyle = (target, pseudo) =>
      target && target._computedValues
        ? computedStyle(target._computedValues)
        : computedStyle();
    return fn();
  } finally {
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

// Build a canned envelope. planJson is a real escaped string because the
// envelope is produced through JSON.stringify.
function makeEnvelope(plan, diagnostics) {
  return JSON.stringify({
    plan: JSON.stringify(plan),
    diagnostics: diagnostics || { capabilityIssues: [], advanceSuspects: [] },
  });
}

const PASSING_ENVELOPE = makeEnvelope(
  { lines: [{ rangeStart: 0, rangeEnd: 5 }] },
  { capabilityIssues: [], advanceSuspects: [] },
);

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

const RICH_BROWSER_FALLBACK = {
  bridge: {
    shapeJson: () => "{}",
    metricsJson: () => "{}",
  },
};

// Stub ffi recording every call as an argument array.
function stubFfi(overrides = {}) {
  const calls = { diagnostics: [], browserMetrics: [] };
  const ffi = {
    precomputeParagraphWithDiagnostics: function () {
      calls.diagnostics.push(Array.from(arguments));
      if (overrides.diagnosticsThrow) throw overrides.diagnosticsThrow;
      return overrides.diagnosticsEnvelope || PASSING_ENVELOPE;
    },
    precomputeParagraphWithBrowserMetrics: function () {
      calls.browserMetrics.push(Array.from(arguments));
      return overrides.browserMetricsEnvelope || PASSING_ENVELOPE;
    },
  };
  return { ffi, calls };
}

function exactArgument(overrides = {}) {
  return {
    paragraph: { source: RICH_ELEMENT, lowered: RICH_LOWERED, lastMeasure: null },
    options: { firstLineIndentIc: 2, emphasisDotGapEm: null },
    exactSession: { sessionId: "s1" },
    browserFallback: RICH_BROWSER_FALLBACK,
    widthOverride: null,
    ignoreUnchangedMeasure: false,
    ...overrides,
  };
}

const DEFAULT_MEASURE = effectiveLineMeasure(320, 19);

test("returns unchanged when lastMeasure matches the effective measure", () => {
  withEnv(() => {
    const { ffi, calls } = stubFfi();
    const result = prepareParagraphLayout(ffi, exactArgument({
      paragraph: { source: RICH_ELEMENT, lowered: RICH_LOWERED, lastMeasure: DEFAULT_MEASURE },
    }));
    assert.deepEqual(result, { kind: "unchanged" });
    assert.equal(calls.diagnostics.length, 0);
    assert.equal(calls.browserMetrics.length, 0);
  });
});

test("ignoreUnchangedMeasure proceeds despite a matching lastMeasure", () => {
  withEnv(() => {
    const { ffi, calls } = stubFfi();
    const result = prepareParagraphLayout(ffi, exactArgument({
      paragraph: { source: RICH_ELEMENT, lowered: RICH_LOWERED, lastMeasure: DEFAULT_MEASURE },
      ignoreUnchangedMeasure: true,
    }));
    assert.equal(result.kind, "ready");
    assert.equal(calls.diagnostics.length, 1);
  });
});

test("widthOverride wins and ready.width is raw while ffi receives the measure", () => {
  withEnv(() => {
    const { ffi, calls } = stubFfi();
    const expectedMeasure = effectiveLineMeasure(200, 19);
    const result = prepareParagraphLayout(ffi, exactArgument({ widthOverride: 200 }));
    assert.equal(result.kind, "ready");
    assert.equal(result.width, 200);
    assert.equal(result.measure, expectedMeasure);
    assert.equal(calls.diagnostics[0][2], expectedMeasure);
    assert.equal(calls.diagnostics[0][1], RICH_LOWERED.text);
  });
});

test("PreparedDomBridgeUnavailable when the renderer global is absent", () => {
  withEnv(() => {
    const { ffi } = stubFfi();
    const result = prepareParagraphLayout(ffi, exactArgument());
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
    const { ffi } = stubFfi();
    const result = prepareParagraphLayout(ffi, exactArgument());
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
    const { ffi } = stubFfi();
    const lowered = paragraph({
      text: "abcde",
      spans: [
        span({ start: 2, end: 5, style: textStyle({ locale: "ja" }) }),
        span({ start: 0, end: 2, style: textStyle({ locale: "ko" }) }),
      ],
    });
    const result = prepareParagraphLayout(ffi, exactArgument({
      paragraph: { source: RICH_ELEMENT, lowered, lastMeasure: null },
    }));
    assert.equal(result.kind, "unsupported");
    assert.equal(result.name, "SpanLocaleMismatchUnsupported");
    assert.equal(result.detail, "spanRange=2..5; spanLocale=ja; paragraphLocale=zh-Hans");
  });
});

test("wire byte lock: diagnostics call carries the full positional argument list", () => {
  withEnv(() => {
    const { ffi, calls } = stubFfi();
    const result = prepareParagraphLayout(ffi, exactArgument({
      paragraph: { source: RICH_ELEMENT, lowered: RICH_LOWERED, lastMeasure: null },
      options: { firstLineIndentIc: 2, emphasisDotGapEm: null },
    }));
    assert.equal(result.kind, "ready");
    const args = calls.diagnostics[0];
    assert.equal(args[0], "s1");
    assert.equal(args[1], "abcde");
    assert.equal(args[2], DEFAULT_MEASURE);
    assert.equal(args[3], "Serif A\u001fSerif B");
    assert.equal(args[4], 19);
    assert.equal(args[5], 28);
    assert.equal(args[6], "zh-Hans");
    assert.equal(args[7], 400);
    assert.equal(args[8], false);
    assert.equal(args[9], 2);
    assert.equal(args[10], true);
    assert.equal(args[11], "0,2,5");
    assert.equal(args[12], "0\u001d2\u001dA\u001fB\u001d12.5\u001d500\u001dtrue\u001d1.5\u001e2\u001d5\u001dC\u001d13.25\u001d600\u001dfalse\u001d0");
    assert.equal(args[13], "0\u001d2\u001d1.5\u001d2.25\u001dNarrow");
    assert.equal(args[14], "1\u001d3\u001dProgressiveTechnical");
    assert.equal(args[15], "4\u001d5\u001d6.5\u001d5\u001d1.25");
    assert.equal(args[16], 0.01);
    assert.equal(args[17], "0\u001d2\u001dEmphasis\u001e3\u001d5\u001dMourning");
    assert.equal(args[18], null);
    assert.equal(args[19], true);
  });
});

test("render evidence override carries the six-collection verdict", () => {
  withEnv(() => {
    const { ffi, calls } = stubFfi();
    // A paragraph with ONLY sourceSpans (a plain unstyled link) has wire-empty
    // collections; the host verdict is still true because the commit path
    // counts sourceSpans and domInlineObjects.
    const linkOnly = paragraph({
      text: "abcde",
      sourceSpans: [sourceSpan({ start: 0, end: 5 })],
    });
    const plain = paragraph({ text: "abcde" });
    prepareParagraphLayout(ffi, exactArgument({
      paragraph: { source: RICH_ELEMENT, lowered: linkOnly, lastMeasure: null },
    }));
    assert.equal(calls.diagnostics[calls.diagnostics.length - 1][19], true);

    prepareParagraphLayout(ffi, exactArgument({
      paragraph: { source: RICH_ELEMENT, lowered: plain, lastMeasure: null },
    }));
    assert.equal(calls.diagnostics[calls.diagnostics.length - 1][19], false);

    // The browser-metrics retry path carries the override after the trailing
    // decorations and emphasis dot gap.
    const retry = stubFfi({ diagnosticsThrow: new Error("NoExactFontFace: miss") });
    prepareParagraphLayout(retry.ffi, exactArgument({
      paragraph: { source: RICH_ELEMENT, lowered: linkOnly, lastMeasure: null },
    }));
    assert.equal(retry.calls.browserMetrics[0][20], true);
  });
});

test("firstLineIndentIc is zero for LI and the option value otherwise", () => {
  withEnv(() => {
    const { ffi, calls } = stubFfi();
    const li = prepareParagraphLayout(ffi, exactArgument({
      paragraph: { source: element("LI"), lowered: RICH_LOWERED, lastMeasure: null },
      options: { firstLineIndentIc: 4, emphasisDotGapEm: null },
    }));
    assert.equal(li.kind, "ready");
    assert.equal(calls.diagnostics[calls.diagnostics.length - 1][9], 0);

    const nonLi = prepareParagraphLayout(ffi, exactArgument({
      options: { firstLineIndentIc: 4, emphasisDotGapEm: null },
    }));
    assert.equal(nonLi.kind, "ready");
    assert.equal(calls.diagnostics[calls.diagnostics.length - 1][9], 4);
  });
});

test("capabilityIssues[0] produces an unsupported verdict with name and reason", () => {
  withEnv(() => {
    const { ffi } = stubFfi({
      diagnosticsEnvelope: makeEnvelope(
        { lines: [] },
        { capabilityIssues: [{ name: "NoConformingCjkDashGlyph", reason: "no dash face", rangeStart: 0, rangeEnd: 1 }], advanceSuspects: [] },
      ),
    });
    const result = prepareParagraphLayout(ffi, exactArgument());
    assert.deepEqual(result, {
      kind: "unsupported",
      name: "NoConformingCjkDashGlyph",
      detail: "no dash face",
      element: RICH_ELEMENT,
    });
  });
});

test("advance suspects skip empty and newline display text, then the first real suspect wins", () => {
  withEnv(() => {
    const { ffi } = stubFfi({
      diagnosticsEnvelope: makeEnvelope(
        { lines: [] },
        {
          capabilityIssues: [],
          advanceSuspects: [
            { displayText: "", advance: "NaN", reason: "empty", rangeStart: 0, rangeEnd: 1 },
            { displayText: "a\nb", advance: "Infinity", reason: "newline", rangeStart: 0, rangeEnd: 2 },
            { displayText: "\u2014", advance: "0", reason: "zero advance", rangeStart: 0, rangeEnd: 3 },
          ],
        },
      ),
    });
    const result = prepareParagraphLayout(ffi, exactArgument());
    assert.deepEqual(result, {
      kind: "unsupported",
      name: "InvalidWebShapingAdvance",
      detail: "text=\u2014; advance=0; zero advance",
      element: RICH_ELEMENT,
    });
  });
});

test("clone decoration crossed by two plan lines is unsupported with the lowercased tag", () => {
  withEnv(() => {
    const { ffi } = stubFfi({
      diagnosticsEnvelope: makeEnvelope(
        { lines: [{ rangeStart: 0, rangeEnd: 2 }, { rangeStart: 2, rangeEnd: 5 }] },
        { capabilityIssues: [], advanceSuspects: [] },
      ),
    });
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
    const result = prepareParagraphLayout(ffi, exactArgument({
      paragraph: { source: RICH_ELEMENT, lowered, lastMeasure: null },
    }));
    assert.deepEqual(result, {
      kind: "unsupported",
      name: "InlineCloneDecorationBreakUnsupported",
      detail: "span",
      element: RICH_ELEMENT,
    });
  });
});

test("clone decoration on a single line does not trigger", () => {
  withEnv(() => {
    const { ffi, calls } = stubFfi();
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
    const result = prepareParagraphLayout(ffi, exactArgument({
      paragraph: { source: RICH_ELEMENT, lowered, lastMeasure: null },
    }));
    assert.equal(result.kind, "ready");
    assert.equal(calls.diagnostics.length, 1);
  });
});

test("a non-clone span with edges never triggers the clone verdict", () => {
  withEnv(() => {
    const { ffi, calls } = stubFfi();
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
    const result = prepareParagraphLayout(ffi, exactArgument({
      paragraph: { source: RICH_ELEMENT, lowered, lastMeasure: null },
    }));
    assert.equal(result.kind, "ready");
    assert.equal(calls.diagnostics.length, 1);
  });
});

test("a capability-failure throws retry through the browser metrics call", () => {
  withEnv(() => {
    const { ffi, calls } = stubFfi({
      diagnosticsThrow: new Error("NoExactFontFace: session miss"),
    });
    const result = prepareParagraphLayout(ffi, exactArgument());
    assert.equal(result.kind, "ready");
    assert.equal(result.exactFontSessionUsed, false);
    assert.equal(calls.diagnostics.length, 1);
    assert.equal(calls.browserMetrics.length, 1);
    const args = calls.browserMetrics[0];
    assert.equal(args[0], "abcde");
    assert.equal(args[15], 0.01);
    assert.equal(typeof args[16], "function");
    assert.equal(typeof args[17], "function");
    assert.equal(args[18], "0\u001d2\u001dEmphasis\u001e3\u001d5\u001dMourning");
  });
});

test("another capability-failure name triggers the retry", () => {
  withEnv(() => {
    const { ffi, calls } = stubFfi({
      diagnosticsThrow: new Error("MissingServerShapingReplay: no replay"),
    });
    const result = prepareParagraphLayout(ffi, exactArgument());
    assert.equal(result.kind, "ready");
    assert.equal(result.exactFontSessionUsed, false);
    assert.equal(calls.browserMetrics.length, 1);
  });
});

test("a non-matching error rethrows", () => {
  withEnv(() => {
    const { ffi, calls } = stubFfi({
      diagnosticsThrow: new Error("some unrelated failure"),
    });
    assert.throws(() => prepareParagraphLayout(ffi, exactArgument()), /some unrelated failure/);
    assert.equal(calls.browserMetrics.length, 0);
  });
});

test("exactSession == null runs the browser metrics call directly without a sessionId", () => {
  withEnv(() => {
    const { ffi, calls } = stubFfi();
    const result = prepareParagraphLayout(ffi, exactArgument({
      exactSession: null,
      browserFallback: RICH_BROWSER_FALLBACK,
    }));
    assert.equal(result.kind, "ready");
    assert.equal(result.exactFontSessionUsed, false);
    assert.equal(calls.diagnostics.length, 0);
    assert.equal(calls.browserMetrics.length, 1);
    assert.equal(typeof calls.browserMetrics[0][17], "function");
  });
});

test("exactSession == null with a missing browserFallback throws", () => {
  withEnv(() => {
    const { ffi } = stubFfi();
    assert.throws(
      () => prepareParagraphLayout(ffi, exactArgument({ exactSession: null, browserFallback: null })),
      /missing browserFallback descriptor/,
    );
  });
});

test("ready shape carries the envelope pieces on the happy exact path", () => {
  withEnv(() => {
    const { ffi, calls } = stubFfi();
    const result = prepareParagraphLayout(ffi, exactArgument({
      paragraph: { source: RICH_ELEMENT, lowered: RICH_LOWERED, lastMeasure: null },
    }));
    assert.equal(result.kind, "ready");
    assert.equal(result.rawEnvelope, PASSING_ENVELOPE);
    assert.equal(result.planJson, JSON.stringify({ lines: [{ rangeStart: 0, rangeEnd: 5 }] }));
    assert.deepEqual(result.plan, { lines: [{ rangeStart: 0, rangeEnd: 5 }] });
    assert.deepEqual(result.diagnostics, { capabilityIssues: [], advanceSuspects: [] });
    assert.equal(result.width, 320);
    assert.equal(result.measure, DEFAULT_MEASURE);
    assert.equal(result.exactFontSessionUsed, true);
    assert.equal(calls.diagnostics.length, 1);
  });
});

test("emphasisDotGapEm passes through to the trailing ffi argument", () => {
  withEnv(() => {
    const { ffi, calls } = stubFfi();
    const result = prepareParagraphLayout(ffi, exactArgument({
      options: { firstLineIndentIc: 2, emphasisDotGapEm: 0.25 },
    }));
    assert.equal(result.kind, "ready");
    assert.equal(calls.diagnostics[0][18], 0.25);

    const omitted = prepareParagraphLayout(ffi, exactArgument({
      options: { firstLineIndentIc: 2, emphasisDotGapEm: null },
    }));
    assert.equal(omitted.kind, "ready");
    assert.equal(calls.diagnostics[1][18], null);
  });
});