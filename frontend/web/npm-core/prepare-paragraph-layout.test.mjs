import assert from "node:assert/strict";
import test from "node:test";

import "./core/engine/prepare-paragraph-layout.js";

const prepare = globalThis.__TiqianPrepareParagraphLayout;

const MEASURE_GLOBALS = ["__TiqianResponsiveMeasure", "__TiqianPreparedDomRenderer"];

function preserveGlobals(names) {
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

function installFakeResponsiveMeasure(overrides = {}) {
  const config = {
    sourceParagraphWidth: 320,
    effectiveLineMeasure: (width, fontSize) => width,
    ...overrides,
  };
  globalThis.__TiqianResponsiveMeasure = {
    effectiveLineMeasure: config.effectiveLineMeasure,
    sourceParagraphWidth: () => config.sourceParagraphWidth,
  };
}

function installFakePreparedDomRenderer(overrides = {}) {
  const config = {
    render: () => {},
    release: () => {},
    releaseRoot: () => {},
    schema: 1,
    layoutRevision: "tiqian-layout-v2",
    ...overrides,
  };
  globalThis.__TiqianPreparedDomRenderer = {
    render: config.render,
    release: config.release,
    releaseRoot: config.releaseRoot,
    schema: config.schema,
    layoutRevision: config.layoutRevision,
  };
}

function installFakeEnv() {
  installFakeResponsiveMeasure();
  installFakePreparedDomRenderer();
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
    text: "你好",
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

function element(tagName = "P") {
  return { tagName };
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

test("returns unchanged when lastMeasure matches the effective measure", () => {
  const globals = preserveGlobals(MEASURE_GLOBALS);
  const sourceWidths = [];
  installFakeEnv();
  const { ffi, calls } = stubFfi();
  installFakeResponsiveMeasure({
    sourceParagraphWidth: 320,
    effectiveLineMeasure: () => 777,
  });
  try {
    const result = prepare.prepareParagraphLayout(ffi, exactArgument({
      paragraph: { source: RICH_ELEMENT, lowered: RICH_LOWERED, lastMeasure: 777 },
    }));
    assert.deepEqual(result, { kind: "unchanged" });
    assert.equal(calls.diagnostics.length, 0);
    assert.equal(calls.browserMetrics.length, 0);
  } finally {
    restoreGlobals(globals);
  }
});

test("ignoreUnchangedMeasure proceeds despite a matching lastMeasure", () => {
  const globals = preserveGlobals(MEASURE_GLOBALS);
  installFakeEnv();
  const { ffi, calls } = stubFfi();
  installFakeResponsiveMeasure({ effectiveLineMeasure: () => 777 });
  try {
    const result = prepare.prepareParagraphLayout(ffi, exactArgument({
      paragraph: { source: RICH_ELEMENT, lowered: RICH_LOWERED, lastMeasure: 777 },
      ignoreUnchangedMeasure: true,
    }));
    assert.equal(result.kind, "ready");
    assert.equal(calls.diagnostics.length, 1);
  } finally {
    restoreGlobals(globals);
  }
});

test("widthOverride wins and ready.width is raw while ffi receives the measure", () => {
  const globals = preserveGlobals(MEASURE_GLOBALS);
  installFakeEnv();
  const { ffi, calls } = stubFfi();
  installFakeResponsiveMeasure({
    sourceParagraphWidth: 999,
    effectiveLineMeasure: (width) => width * 0.5,
  });
  try {
    const result = prepare.prepareParagraphLayout(ffi, exactArgument({ widthOverride: 200 }));
    assert.equal(result.kind, "ready");
    assert.equal(result.width, 200);
    assert.equal(result.measure, 100);
    assert.equal(calls.diagnostics[0][2], 100);
    assert.equal(calls.diagnostics[0][1], RICH_LOWERED.text);
  } finally {
    restoreGlobals(globals);
  }
});

test("PreparedDomBridgeUnavailable when the renderer global is absent", () => {
  const globals = preserveGlobals(MEASURE_GLOBALS);
  installFakeResponsiveMeasure();
  delete globalThis.__TiqianPreparedDomRenderer;
  const { ffi } = stubFfi();
  try {
    const result = prepare.prepareParagraphLayout(ffi, exactArgument());
    assert.deepEqual(result, {
      kind: "unsupported",
      name: "PreparedDomBridgeUnavailable",
      detail: "expectedLayoutRevision=tiqian-layout-v2",
      element: RICH_ELEMENT,
    });
  } finally {
    restoreGlobals(globals);
  }
});

test("PreparedDomBridgeUnavailable when the layout revision mismatches", () => {
  const globals = preserveGlobals(MEASURE_GLOBALS);
  installFakeResponsiveMeasure();
  installFakePreparedDomRenderer({ layoutRevision: "tiqian-layout-v1" });
  const { ffi } = stubFfi();
  try {
    const result = prepare.prepareParagraphLayout(ffi, exactArgument());
    assert.deepEqual(result, {
      kind: "unsupported",
      name: "PreparedDomBridgeUnavailable",
      detail: "expectedLayoutRevision=tiqian-layout-v2",
      element: RICH_ELEMENT,
    });
  } finally {
    restoreGlobals(globals);
  }
});

test("SpanLocaleMismatchUnsupported uses the first mismatching span", () => {
  const globals = preserveGlobals(MEASURE_GLOBALS);
  installFakeEnv();
  const { ffi } = stubFfi();
  const lowered = paragraph({
    text: "abcde",
    spans: [
      span({ start: 2, end: 5, style: textStyle({ locale: "ja" }) }),
      span({ start: 0, end: 2, style: textStyle({ locale: "ko" }) }),
    ],
  });
  try {
    const result = prepare.prepareParagraphLayout(ffi, exactArgument({
      paragraph: { source: RICH_ELEMENT, lowered, lastMeasure: null },
    }));
    assert.equal(result.kind, "unsupported");
    assert.equal(result.name, "SpanLocaleMismatchUnsupported");
    assert.equal(result.detail, "spanRange=2..5; spanLocale=ja; paragraphLocale=zh-Hans");
  } finally {
    restoreGlobals(globals);
  }
});

test("wire byte lock: diagnostics call carries the full positional argument list", () => {
  const globals = preserveGlobals(MEASURE_GLOBALS);
  installFakeEnv();
  const { ffi, calls } = stubFfi();
  installFakeResponsiveMeasure({ effectiveLineMeasure: () => 777 });
  try {
    const result = prepare.prepareParagraphLayout(ffi, exactArgument({
      paragraph: { source: RICH_ELEMENT, lowered: RICH_LOWERED, lastMeasure: null },
      options: { firstLineIndentIc: 2, emphasisDotGapEm: null },
    }));
    assert.equal(result.kind, "ready");
    const args = calls.diagnostics[0];
    assert.equal(args[0], "s1");
    assert.equal(args[1], "abcde");
    assert.equal(args[2], 777);
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
  } finally {
    restoreGlobals(globals);
  }
});

test("firstLineIndentIc is zero for LI and the option value otherwise", () => {
  const globals = preserveGlobals(MEASURE_GLOBALS);
  installFakeEnv();
  const { ffi, calls } = stubFfi();
  try {
    const li = prepare.prepareParagraphLayout(ffi, exactArgument({
      paragraph: { source: element("LI"), lowered: RICH_LOWERED, lastMeasure: null },
      options: { firstLineIndentIc: 4, emphasisDotGapEm: null },
    }));
    assert.equal(li.kind, "ready");
    assert.equal(calls.diagnostics[calls.diagnostics.length - 1][9], 0);

    const nonLi = prepare.prepareParagraphLayout(ffi, exactArgument({
      options: { firstLineIndentIc: 4, emphasisDotGapEm: null },
    }));
    assert.equal(nonLi.kind, "ready");
    assert.equal(calls.diagnostics[calls.diagnostics.length - 1][9], 4);
  } finally {
    restoreGlobals(globals);
  }
});

test("capabilityIssues[0] produces an unsupported verdict with name and reason", () => {
  const globals = preserveGlobals(MEASURE_GLOBALS);
  installFakeEnv();
  const { ffi } = stubFfi({
    diagnosticsEnvelope: makeEnvelope(
      { lines: [] },
      { capabilityIssues: [{ name: "NoConformingCjkDashGlyph", reason: "no dash face", rangeStart: 0, rangeEnd: 1 }], advanceSuspects: [] },
    ),
  });
  try {
    const result = prepare.prepareParagraphLayout(ffi, exactArgument());
    assert.deepEqual(result, {
      kind: "unsupported",
      name: "NoConformingCjkDashGlyph",
      detail: "no dash face",
      element: RICH_ELEMENT,
    });
  } finally {
    restoreGlobals(globals);
  }
});

test("advance suspects skip empty and newline display text, then the first real suspect wins", () => {
  const globals = preserveGlobals(MEASURE_GLOBALS);
  installFakeEnv();
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
  try {
    const result = prepare.prepareParagraphLayout(ffi, exactArgument());
    assert.deepEqual(result, {
      kind: "unsupported",
      name: "InvalidWebShapingAdvance",
      detail: "text=\u2014; advance=0; zero advance",
      element: RICH_ELEMENT,
    });
  } finally {
    restoreGlobals(globals);
  }
});

test("clone decoration crossed by two plan lines is unsupported with the lowercased tag", () => {
  const globals = preserveGlobals(MEASURE_GLOBALS);
  installFakeEnv();
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
  try {
    const result = prepare.prepareParagraphLayout(ffi, exactArgument({
      paragraph: { source: RICH_ELEMENT, lowered, lastMeasure: null },
    }));
    assert.deepEqual(result, {
      kind: "unsupported",
      name: "InlineCloneDecorationBreakUnsupported",
      detail: "span",
      element: RICH_ELEMENT,
    });
  } finally {
    restoreGlobals(globals);
  }
});

test("clone decoration on a single line does not trigger", () => {
  const globals = preserveGlobals(MEASURE_GLOBALS);
  installFakeEnv();
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
  try {
    const result = prepare.prepareParagraphLayout(ffi, exactArgument({
      paragraph: { source: RICH_ELEMENT, lowered, lastMeasure: null },
    }));
    assert.equal(result.kind, "ready");
    assert.equal(calls.diagnostics.length, 1);
  } finally {
    restoreGlobals(globals);
  }
});

test("a non-clone span with edges never triggers the clone verdict", () => {
  const globals = preserveGlobals(MEASURE_GLOBALS);
  installFakeEnv();
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
  try {
    const result = prepare.prepareParagraphLayout(ffi, exactArgument({
      paragraph: { source: RICH_ELEMENT, lowered, lastMeasure: null },
    }));
    assert.equal(result.kind, "ready");
    assert.equal(calls.diagnostics.length, 1);
  } finally {
    restoreGlobals(globals);
  }
});

test("a capability-failure throws retry through the browser metrics call", () => {
  const globals = preserveGlobals(MEASURE_GLOBALS);
  installFakeEnv();
  const { ffi, calls } = stubFfi({
    diagnosticsThrow: new Error("NoExactFontFace: session miss"),
  });
  try {
    const result = prepare.prepareParagraphLayout(ffi, exactArgument());
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
  } finally {
    restoreGlobals(globals);
  }
});

test("another capability-failure name triggers the retry", () => {
  const globals = preserveGlobals(MEASURE_GLOBALS);
  installFakeEnv();
  const { ffi, calls } = stubFfi({
    diagnosticsThrow: new Error("MissingServerShapingReplay: no replay"),
  });
  try {
    const result = prepare.prepareParagraphLayout(ffi, exactArgument());
    assert.equal(result.kind, "ready");
    assert.equal(result.exactFontSessionUsed, false);
    assert.equal(calls.browserMetrics.length, 1);
  } finally {
    restoreGlobals(globals);
  }
});

test("a non-matching error rethrows", () => {
  const globals = preserveGlobals(MEASURE_GLOBALS);
  installFakeEnv();
  const { ffi, calls } = stubFfi({
    diagnosticsThrow: new Error("some unrelated failure"),
  });
  try {
    assert.throws(() => prepare.prepareParagraphLayout(ffi, exactArgument()), /some unrelated failure/);
    assert.equal(calls.browserMetrics.length, 0);
  } finally {
    restoreGlobals(globals);
  }
});

test("exactSession == null runs the browser metrics call directly without a sessionId", () => {
  const globals = preserveGlobals(MEASURE_GLOBALS);
  installFakeEnv();
  const { ffi, calls } = stubFfi();
  try {
    const result = prepare.prepareParagraphLayout(ffi, exactArgument({
      exactSession: null,
      browserFallback: RICH_BROWSER_FALLBACK,
    }));
    assert.equal(result.kind, "ready");
    assert.equal(result.exactFontSessionUsed, false);
    assert.equal(calls.diagnostics.length, 0);
    assert.equal(calls.browserMetrics.length, 1);
    assert.equal(typeof calls.browserMetrics[0][17], "function");
  } finally {
    restoreGlobals(globals);
  }
});

test("exactSession == null with a missing browserFallback throws", () => {
  const globals = preserveGlobals(MEASURE_GLOBALS);
  installFakeEnv();
  const { ffi } = stubFfi();
  try {
    assert.throws(
      () => prepare.prepareParagraphLayout(ffi, exactArgument({ exactSession: null, browserFallback: null })),
      /missing browserFallback descriptor/,
    );
  } finally {
    restoreGlobals(globals);
  }
});

test("ready shape carries the envelope pieces on the happy exact path", () => {
  const globals = preserveGlobals(MEASURE_GLOBALS);
  installFakeEnv();
  const { ffi, calls } = stubFfi();
  installFakeResponsiveMeasure({
    sourceParagraphWidth: 320,
    effectiveLineMeasure: () => 777,
  });
  try {
    const result = prepare.prepareParagraphLayout(ffi, exactArgument({
      paragraph: { source: RICH_ELEMENT, lowered: RICH_LOWERED, lastMeasure: null },
    }));
    assert.equal(result.kind, "ready");
    assert.equal(result.rawEnvelope, PASSING_ENVELOPE);
    assert.equal(result.planJson, JSON.stringify({ lines: [{ rangeStart: 0, rangeEnd: 5 }] }));
    assert.deepEqual(result.plan, { lines: [{ rangeStart: 0, rangeEnd: 5 }] });
    assert.deepEqual(result.diagnostics, { capabilityIssues: [], advanceSuspects: [] });
    assert.equal(result.width, 320);
    assert.equal(result.measure, 777);
    assert.equal(result.exactFontSessionUsed, true);
    assert.equal(calls.diagnostics.length, 1);
  } finally {
    restoreGlobals(globals);
  }
});

test("emphasisDotGapEm passes through to the trailing ffi argument", () => {
  const globals = preserveGlobals(MEASURE_GLOBALS);
  installFakeEnv();
  const { ffi, calls } = stubFfi();
  try {
    const result = prepare.prepareParagraphLayout(ffi, exactArgument({
      options: { firstLineIndentIc: 2, emphasisDotGapEm: 0.25 },
    }));
    assert.equal(result.kind, "ready");
    assert.equal(calls.diagnostics[0][18], 0.25);

    const omitted = prepare.prepareParagraphLayout(ffi, exactArgument({
      options: { firstLineIndentIc: 2, emphasisDotGapEm: null },
    }));
    assert.equal(omitted.kind, "ready");
    assert.equal(calls.diagnostics[1][18], null);
  } finally {
    restoreGlobals(globals);
  }
});
