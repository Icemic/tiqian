// Tests for the canvas text shaping adapter (TsHost runtime port, Slice 4a
// part 2). Uses the real canvas-fonts module for font stack resolution
// and a fake DOM injection surface (env) for measurement.
import assert from "node:assert/strict";
import test from "node:test";

import { createFontFamilies } from "../core/engine/canvas-fonts.js";
import {
  createTextShaper,
  installFontLoadInvalidation,
  clearMeasurementCache,
  measurementCacheSize,
} from "../core/engine/canvas-shaping.js";

const PARITY_PROBE_TEXT = "Benjamini-Hochberg WAVE fjord, 0x7f.";
const DEGENERATE_PROBE_TEXT = "\u3002";

// A fresh, programmable env and fonts instance per call. clearMeasurementCache
// isolates the shared measurement/verdict caches from earlier tests.
function makeHarness(program) {
  clearMeasurementCache();
  const cfg = program;
  const measureCount = {};
  const probes = [];

  function makeProbe() {
    const styleEntries = {};
    const textMeasureCalls = {};
    const element = {
      parentNode: null,
      textContent: "",
      setAttribute() {},
      style: {
        setProperty(name, value, priority) {
          styleEntries[name] = { value, priority };
        },
      },
      getBoundingClientRect() {
        const key = element.textContent;
        textMeasureCalls[key] = (textMeasureCalls[key] || 0) + 1;
        return { width: cfg.probeWidth ? cfg.probeWidth(key) : 0 };
      },
      styleEntries,
      textMeasureCalls,
    };
    probes.push(element);
    return element;
  }

  function createCanvasContext() {
    const canvas = { width: 0, height: 0 };
    return {
      canvas,
      font: "",
      measureText(text) {
        measureCount[text] = (measureCount[text] || 0) + 1;
        return cfg.measureText(text, this.font);
      },
      setTransform() {
        if (cfg.record) cfg.record.push(["setTransform", Array.from(arguments)]);
      },
      clearRect() {
        if (cfg.record) cfg.record.push(["clearRect", Array.from(arguments)]);
      },
      fillText() {
        if (cfg.record) cfg.record.push(["fillText", Array.from(arguments)]);
      },
      getImageData(x, y, w, h) {
        return cfg.getImageData ? cfg.getImageData(x, y, w, h) : { data: new Uint8ClampedArray(w * h * 4) };
      },
    };
  }

  function createProbeElement() {
    return makeProbe();
  }

  function attachProbe(element) {
    if (element.parentNode == null) element.parentNode = {};
  }

  const fonts = createFontFamilies({
    cjk: '"CJK", sans-serif',
    latin: '"Latin", sans-serif',
  });
  const env = { createCanvasContext, createProbeElement, attachProbe, probes };
  const shaper = createTextShaper(fonts, cfg.cjkDashCapability, env);
  return { env, fonts, shaper, measureCount };
}

function findParityProbe(env) {
  return env.probes.find((p) => !p.styleEntries.border);
}

function findFeatureProbe(env) {
  return env.probes.find((p) => p.styleEntries.border);
}

function defaultMetrics() {
  return {
    width: 19,
    actualBoundingBoxLeft: -0.1,
    actualBoundingBoxAscent: -19,
    actualBoundingBoxRight: 19.1,
    actualBoundingBoxDescent: 4,
    fontBoundingBoxAscent: 19,
    fontBoundingBoxDescent: 4,
    ideographicBaseline: 0,
  };
}

function input(overrides = {}) {
  return {
    text: "中",
    range: { start: 0, end: 1 },
    style: { fontSize: 19, fontWeight: 400, italic: false, fontFamilies: [] },
    fontDecision: { role: "CjkText", candidate: { key: "k1" } },
    displayText: "中",
    ...overrides,
  };
}

function buildInkData(width, height, ink) {
  const data = new Uint8ClampedArray(width * height * 4);
  for (let y = ink.minY; y <= ink.maxY; y += 1) {
    for (let x = ink.minX; x <= ink.maxX; x += 1) {
      data[(y * width + x) * 4 + 3] = 255;
    }
  }
  return { data };
}

test("plain CJK measure: advance from width, bounds from the actualBoundingBox quadruple, exact reason", () => {
  const { shaper } = makeHarness({
    measureText: () => defaultMetrics(),
  });
  const result = shaper.shape(input({ text: "中", displayText: "中" }));
  const decision = result.decisions[0];
  assert.equal(decision.source, "OffscreenMeasureTextShaping");
  assert.equal(decision.advance, 19);
  assert.deepEqual(result.glyphRuns[0].glyphs[0].bounds, {
    left: 0.1,
    top: 19,
    right: 19.1,
    bottom: 4,
  });
  assert.equal(decision.glyphsWithoutInkBounds, 0);
  assert.equal(decision.capabilityIssue, null);
  assert.equal(decision.featureEvidence, null);
  assert.equal(
    decision.reason,
    'web-canvas-measureText; stackIndex=0; requestedFont=normal 400 19px "CJK", sans-serif; actualFont=normal 400 19px "CJK", sans-serif',
  );
});

test("stack iteration: first stack zero advance routes to the second stack", () => {
  const { shaper, measureCount } = makeHarness({
    measureText: (text, font) => {
      if (font.indexOf("FamilyA") !== -1) return { ...defaultMetrics(), width: 0 };
      return { ...defaultMetrics(), width: 30 };
    },
  });
  const result = shaper.shape(
    input({
      text: "中",
      displayText: "中",
      style: { fontSize: 19, fontWeight: 400, italic: false, fontFamilies: ["FamilyA", "FamilyB"] },
    }),
  );
  const decision = result.decisions[0];
  assert.equal(decision.advance, 30);
  assert.ok(decision.reason.includes("; stackIndex=1;"));
  assert.ok(decision.reason.includes('requestedFont=normal 400 19px "FamilyB"'));
});

test("requiresAdvance false: zero advance accepted, no stack iteration", () => {
  const { shaper, measureCount } = makeHarness({
    measureText: () => ({ ...defaultMetrics(), width: 0 }),
  });
  const result = shaper.shape(
    input({
      text: "a\nb",
      displayText: "a\nb",
      style: { fontSize: 19, fontWeight: 400, italic: false, fontFamilies: ["FamilyA", "FamilyB"] },
    }),
  );
  const decision = result.decisions[0];
  assert.equal(decision.advance, 0);
  assert.ok(decision.reason.includes("; stackIndex=0;"));
  // Only the first stack was measured; no iteration occurred.
  assert.equal(measureCount["a\nb"], 1);
});

test("CanvasDomAdvanceParityGate: Latin divergence uses hidden-DOM advance; CJK exempt", () => {
  // LatinText with a divergent canvas-vs-DOM parity probe.
  const latin = makeHarness({
    measureText: (text) => {
      if (text === PARITY_PROBE_TEXT) return { ...defaultMetrics(), width: 100 };
      return { ...defaultMetrics(), width: 9 };
    },
    probeWidth: (text) => (text === PARITY_PROBE_TEXT ? 200 : 50),
  });
  const latinResult = latin.shaper.shape(
    input({
      text: "hello",
      displayText: "hello",
      fontDecision: { role: "LatinText", candidate: { key: "k" } },
    }),
  );
  const latinDecision = latinResult.decisions[0];
  assert.equal(latinDecision.advance, 50); // hidden-DOM width
  assert.equal(latinResult.glyphRuns[0].glyphs[0].bounds, null);
  assert.ok(latinDecision.reason.includes("; inkBounds=CanvasDomAdvanceParityGate"));
  assert.equal(latinDecision.glyphsWithoutInkBounds, 1);

  // CJK role: exempt from the parity gate even with the same divergent probe.
  const cjk = makeHarness({
    measureText: (text) => {
      if (text === PARITY_PROBE_TEXT) return { ...defaultMetrics(), width: 100 };
      return { ...defaultMetrics(), width: 9 };
    },
    probeWidth: (text) => (text === PARITY_PROBE_TEXT ? 200 : 50),
  });
  const cjkResult = cjk.shaper.shape(input({ text: "中", displayText: "中" }));
  const cjkDecision = cjkResult.decisions[0];
  assert.equal(cjkDecision.advance, 9); // canvas width, no gate
  assert.ok(!cjkDecision.reason.includes("CanvasDomAdvanceParityGate"));
  const parityProbe = findParityProbe(cjk.env);
  assert.equal(parityProbe, undefined); // parity probe never created for CJK
});

test("parity verdict cached per font: hidden-DOM probe runs once", () => {
  const { env, shaper } = makeHarness({
    measureText: (text) => {
      if (text === PARITY_PROBE_TEXT) return { ...defaultMetrics(), width: 100 };
      return { ...defaultMetrics(), width: 9 };
    },
    probeWidth: (text) => (text === PARITY_PROBE_TEXT ? 200 : 50),
  });
  const base = {
    style: { fontSize: 19, fontWeight: 400, italic: false, fontFamilies: [] },
    fontDecision: { role: "LatinText", candidate: { key: "k" } },
  };
  shaper.shape(input({ text: "one", displayText: "one", ...base }));
  shaper.shape(input({ text: "two", displayText: "two", ...base }));
  const parityProbe = findParityProbe(env);
  assert.equal(parityProbe.textMeasureCalls[PARITY_PROBE_TEXT], 1);
});

test("curly quote: features pwid,palt with advance from the feature probe", () => {
  const { shaper, env } = makeHarness({
    measureText: () => defaultMetrics(),
    probeWidth: (text) => (text === "\u2018" ? 12 : 0),
  });
  const result = shaper.shape(
    input({
      text: "‘",
      displayText: "‘",
      fontDecision: { role: "LatinText", candidate: { key: "k" } },
    }),
  );
  const decision = result.decisions[0];
  assert.deepEqual(result.glyphRuns[0].openTypeFeatures, ["pwid", "palt"]);
  assert.equal(decision.featureEvidence, "pwid,palt");
  assert.equal(decision.advance, 12);
  assert.ok(
    decision.reason.includes("; features=pwid,palt; featureMeasure=HiddenDomRange"),
  );
  const featureProbe = findFeatureProbe(env);
  assert.equal(featureProbe.styleEntries["font-feature-settings"].value, '"palt" 1');
  assert.equal(featureProbe.styleEntries["font-variant-east-asian"].value, "proportional-width");
});

test("degenerate ink probe: CjkPunctuation rasterizes ink bounds and matches hand-computed back-transform", () => {
  const scale = 4;
  const margin = 19;
  const baseline = 19 * 1.25;
  const ink = { minX: 100, maxX: 120, minY: 40, maxY: 50 };
  const { shaper } = makeHarness({
    measureText: (text) => {
      if (text === DEGENERATE_PROBE_TEXT) {
        return { ...defaultMetrics(), width: 19, actualBoundingBoxLeft: 0, actualBoundingBoxRight: 19 };
      }
      return { ...defaultMetrics(), width: 19 };
    },
    getImageData: (x, y, w, h) => buildInkData(w, h, ink),
  });
  const result = shaper.shape(
    input({
      text: "，",
      displayText: "，",
      fontDecision: { role: "CjkPunctuation", candidate: { key: "k" } },
    }),
  );
  const decision = result.decisions[0];
  const expected = {
    left: ink.minX / scale - margin,
    top: ink.minY / scale - baseline,
    right: (ink.maxX + 1) / scale - margin,
    bottom: (ink.maxY + 1) / scale - baseline,
  };
  assert.deepEqual(result.glyphRuns[0].glyphs[0].bounds, expected);
  assert.ok(decision.reason.includes("; inkBounds=DegenerateCanvasInkBoundsProbe;RasterizedInkBoundsMeasure"));
});

test("degenerate verdict cached per font: probe text measured once", () => {
  const { shaper, measureCount } = makeHarness({
    measureText: (text) => {
      if (text === DEGENERATE_PROBE_TEXT) {
        return { ...defaultMetrics(), width: 19, actualBoundingBoxLeft: 0, actualBoundingBoxRight: 19 };
      }
      return { ...defaultMetrics(), width: 19 };
    },
    getImageData: () => buildInkData(228, 152, { minX: 100, maxX: 120, minY: 40, maxY: 50 }),
  });
  const base = {
    style: { fontSize: 19, fontWeight: 400, italic: false, fontFamilies: [] },
    fontDecision: { role: "CjkPunctuation", candidate: { key: "k" } },
  };
  shaper.shape(input({ text: "，", displayText: "，", ...base }));
  shaper.shape(input({ text: "、", displayText: "、", ...base }));
  assert.equal(measureCount[DEGENERATE_PROBE_TEXT], 1);
});

test("subpixel clamp: overhangs under 1px clamp to the advance box, over 1px kept", () => {
  // overhang 0.4px each side -> clamp
  const clamped = makeHarness({
    measureText: (text) => {
      if (text === DEGENERATE_PROBE_TEXT) return { ...defaultMetrics(), width: 0 };
      return {
        ...defaultMetrics(),
        width: 19,
        actualBoundingBoxLeft: 0.4,
        actualBoundingBoxRight: 19.4,
      };
    },
  }).shaper.shape(
    input({
      text: "，",
      displayText: "，",
      fontDecision: { role: "CjkPunctuation", candidate: { key: "k" } },
    }),
  );
  const clampedBounds = clamped.glyphRuns[0].glyphs[0].bounds;
  assert.deepEqual(clampedBounds, { left: 0, top: 19, right: 19, bottom: 4 });
  assert.ok(
    clamped.decisions[0].reason.includes(
      "; inkBounds=SubpixelCanvasInkOverhangClamp(left=0.4)",
    ),
  );

  // overhang 1.2px each side -> kept
  const keptHarness = makeHarness({
    measureText: (text) => {
      if (text === DEGENERATE_PROBE_TEXT) return { ...defaultMetrics(), width: 0 };
      return {
        ...defaultMetrics(),
        width: 19,
        actualBoundingBoxLeft: 1.2,
        actualBoundingBoxRight: 20.2,
      };
    },
  });
  const kept = keptHarness.shaper.shape(
    input({
      text: "，",
      displayText: "，",
      fontDecision: { role: "CjkPunctuation", candidate: { key: "k" } },
    }),
  );
  assert.deepEqual(kept.glyphRuns[0].glyphs[0].bounds, { left: -1.2, top: 19, right: 20.2, bottom: 4 });
  assert.ok(!kept.decisions[0].reason.includes("SubpixelCanvasInkOverhangClamp"));
});

test("dash: CjkDashCapabilityPolicy issue name and detail variants", () => {
  const conforming = makeHarness({
    cjkDashCapability: { status: "conforming", detail: null },
    measureText: () => ({ ...defaultMetrics(), width: 38 }),
  });
  const cResult = conforming.shaper.shape(
    input({ text: "\u2014\u2014", range: { start: 0, end: 2 }, displayText: "\u2014\u2014" }),
  );
  assert.equal(cResult.decisions[0].capabilityIssue, "ConformingCjkDashRequiresSnapshotFontSession");
  assert.ok(cResult.decisions[0].reason.includes("; status=conforming"));

  const partial = makeHarness({
    cjkDashCapability: { status: "partial", detail: "probe-detail" },
    measureText: () => ({ ...defaultMetrics(), width: 38 }),
  });
  const pResult = partial.shaper.shape(
    input({ text: "\u2014\u2014", range: { start: 0, end: 2 }, displayText: "\u2014\u2014" }),
  );
  assert.equal(pResult.decisions[0].capabilityIssue, "NoConformingCjkDashGlyph");
  assert.ok(pResult.decisions[0].reason.includes("; status=partial; probe-detail"));

  const none = makeHarness({
    cjkDashCapability: null,
    measureText: () => ({ ...defaultMetrics(), width: 38 }),
  });
  const nResult = none.shaper.shape(
    input({ text: "\u2014\u2014", range: { start: 0, end: 2 }, displayText: "\u2014\u2014" }),
  );
  assert.equal(nResult.decisions[0].capabilityIssue, "NoConformingCjkDashGlyph");
  assert.ok(nResult.decisions[0].reason.includes("; CjkDashFontShapingNotPrepared"));
});

test("ellipsis: unverified display substitution carries the U+22EF issue", () => {
  const { shaper } = makeHarness({
    measureText: () => defaultMetrics(),
  });
  const result = shaper.shape(
    input({
      text: "\u2026\u2026",
      range: { start: 0, end: 2 },
      displayText: "\u22ef\u22ef",
    }),
  );
  const decision = result.decisions[0];
  assert.equal(decision.capabilityIssue, "UnverifiedDisplaySubstitutionCoverage");
  assert.ok(decision.reason.includes("; CanvasCannotVerifySameFaceU+22EFCoverage"));
});

test("shared LRU cache: shared across shapers, bounded, and re-touched entries survive", () => {
  clearMeasurementCache();

  function makeSharer() {
    const program = {
      measureText: () => defaultMetrics(),
    };
    const fonts = createFontFamilies({
      cjk: '"CJK", sans-serif',
      latin: '"Latin", sans-serif',
    });
    const measureCount = {};
    const env = {
      createCanvasContext() {
        return {
          canvas: { width: 0, height: 0 },
          font: "",
          measureText(text) {
            measureCount[text] = (measureCount[text] || 0) + 1;
            return program.measureText(text);
          },
          setTransform() {},
          clearRect() {},
          fillText() {},
          getImageData(x, y, w, h) {
            return { data: new Uint8ClampedArray(w * h * 4) };
          },
        };
      },
      createProbeElement() {
        return {
          parentNode: null,
          textContent: "",
          setAttribute() {},
          style: { setProperty() {} },
          getBoundingClientRect() {
            return { width: 0 };
          },
        };
      },
      attachProbe() {},
    };
    const shaper = createTextShaper(fonts, null, env);
    return { shaper, measureCount };
  }

  function shapeText(shaper, text) {
    shaper.shape(
      input({ text, displayText: text, style: { fontSize: 19, fontWeight: 400, italic: false, fontFamilies: [] } }),
    );
  }

  // Two shapers share one cache.
  const a = makeSharer();
  const b = makeSharer();
  shapeText(a.shaper, "shared");
  shapeText(b.shaper, "shared");
  assert.equal(a.measureCount["shared"], 1);

  // Bounded eviction with LRU touch.
  const c = makeSharer();
  shapeText(c.shaper, "elder");
  shapeText(c.shaper, "keep");
  shapeText(c.shaper, "keep"); // touch -> most recent
  for (let i = 0; i < 2047; i += 1) {
    shapeText(c.shaper, "e" + String(i).padStart(4, "0"));
  }
  assert.equal(measurementCacheSize(), 2048);
  // "keep" survived (re-touched); "elder" was evicted.
  shapeText(c.shaper, "keep");
  assert.equal(c.measureCount["keep"], 1); // cache hit, no re-measure
  shapeText(c.shaper, "elder");
  assert.equal(c.measureCount["elder"], 2); // re-measured after eviction
});

test("clearMeasurementCache and installFontLoadInvalidation", () => {
  clearMeasurementCache();

  const fontSet = {
    listeners: {},
    addEventListener(name, fn) {
      this.listeners[name] = fn;
    },
  };
  installFontLoadInvalidation(fontSet);
  // once-guard: a second call with a different fontSet adds no listener.
  const fontSet2 = {
    listeners: {},
    addEventListener(name, fn) {
      this.listeners[name] = fn;
    },
  };
  installFontLoadInvalidation(fontSet2);
  assert.equal(Object.keys(fontSet2.listeners).length, 0);
  assert.equal(typeof fontSet.listeners["loadingdone"], "function");

  // Populate the measurement cache and a verdict cache.
  const program = {
    measureText: (text) => {
      if (text === PARITY_PROBE_TEXT) return { ...defaultMetrics(), width: 100 };
      return { ...defaultMetrics(), width: 9 };
    },
    probeWidth: (text) => (text === PARITY_PROBE_TEXT ? 200 : 50),
  };
  const fonts = createFontFamilies({ cjk: '"CJK", sans-serif', latin: '"Latin", sans-serif' });
  const probes = [];
  const env = {
    createCanvasContext() {
      return {
        canvas: { width: 0, height: 0 },
        font: "",
        measureText(text) {
          return program.measureText(text, this.font);
        },
        setTransform() {},
        clearRect() {},
        fillText() {},
        getImageData(x, y, w, h) {
          return { data: new Uint8ClampedArray(w * h * 4) };
        },
      };
    },
    createProbeElement() {
      const textMeasureCalls = {};
      const styleEntries = {};
      const element = {
        parentNode: null,
        textContent: "",
        setAttribute() {},
        style: { setProperty(name, value, priority) { styleEntries[name] = { value, priority }; } },
        getBoundingClientRect() {
          textMeasureCalls[element.textContent] = (textMeasureCalls[element.textContent] || 0) + 1;
          return { width: program.probeWidth(element.textContent) };
        },
        styleEntries,
        textMeasureCalls,
      };
      probes.push(element);
      return element;
    },
    attachProbe() {},
  };
  const shaper = createTextShaper(fonts, null, env);
  shaper.shape(
    input({ text: "hello", displayText: "hello", fontDecision: { role: "LatinText", candidate: { key: "k" } } }),
  );
  assert.ok(measurementCacheSize() >= 1);
  const parityProbe = probes.find((p) => !p.styleEntries.border);
  assert.equal(parityProbe.textMeasureCalls[PARITY_PROBE_TEXT], 1);

  // loadingdone callback clears the cache and both verdict caches.
  fontSet.listeners["loadingdone"]();
  assert.equal(measurementCacheSize(), 0);
  shaper.shape(
    input({ text: "hello", displayText: "hello", fontDecision: { role: "LatinText", candidate: { key: "k" } } }),
  );
  assert.equal(parityProbe.textMeasureCalls[PARITY_PROBE_TEXT], 2); // verdict re-probed
});
