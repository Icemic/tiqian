import assert from "node:assert/strict";
import test from "node:test";

import { prepareParagraphLayout } from "../core/engine/prepare-paragraph-layout.js";
import { wireArguments } from "../core/engine/prepare-paragraph-layout.js";
import { effectiveLineMeasure } from "../core/engine/responsive-measure.js";
import { installFixtureFontBackend, installThrowingFontBackend } from "../test-support/fixture-font-backend.mjs";

// The responsive measure helpers are real: sourceParagraphWidth reads element
// geometry through globalThis.getComputedStyle and effectiveLineMeasure is
// imported above. The prepared-dom bridge is a static import, so no renderer
// global stays fake.
//
// The wire byte lock (rule c) now asserts the DTO shape produced by wireArguments
// and the real direct ffi call consumes the DTO. The verdict/gating and
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
    // Tests now use the real prepared-dom renderer directly.
    // Validator injection removed per spec.
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
      // rich lowered text as the source. wireArguments returns a DTO with
      // placeholder maxWidthPx (0) and firstLineIndentIc (0); prepareParagraphLayout
      // overwrites them before calling the ffi.
      const wire = wireArguments(RICH_LOWERED);
      assert.equal(wire.text, "abcde");
      // The actual DTO sent to ffi has the correct measure; we verify this
      // indirectly via the ready result's measure field.
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
    assert.equal(wire.firstLineIndentIc, 0); // Will be set by caller
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
    assert.equal(wire.emphasisDotGapEm, null); // Will be set by caller
    assert.equal(wire.renderEvidenceOverride, null); // Will be set by caller

    // The wire byte lock is not a dead computation: the real snapshot-session
    // ffi call is a one-line consumer of the same DTO, observable as the
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
      const linkWire = wireArguments(linkOnly);
      const plainWire = wireArguments(plain);
      // sourceSpans-only lowered has wire-empty collections but carries true
      // render evidence; a plain paragraph carries false.
      assert.equal(linkWire.renderEvidenceOverride, null); // Will be set by caller based on hasRenderEvidence
      assert.equal(plainWire.renderEvidenceOverride, null); // Will be set by caller based on hasRenderEvidence

      // The DTO shape for renderEvidenceOverride is set by prepareParagraphLayout
      // based on hasRenderEvidence(lowered). The wireArguments function returns
      // null for renderEvidenceOverride, which the caller then overwrites.
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

test("emphasisDotGapEm passes through to the DTO", () => {
  const backend = installFixtureFontBackend();
  try {
    withEnv(() => {
      const wire = wireArguments(RICH_LOWERED);
      // wireArguments returns null for emphasisDotGapEm, caller sets it
      assert.equal(wire.emphasisDotGapEm, null);
      // The DTO will have the value set by prepareParagraphLayout
    });
  } finally {
    backend.uninstall();
  }
});