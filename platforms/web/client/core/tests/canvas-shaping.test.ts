import assert from "node:assert/strict";
import test from "node:test";

import { createFontFamilies } from "../src/engine/canvas-fonts.js";
import type { WebFontFamiliesInstance } from "../src/engine/canvas-fonts.js";
import {
  createTextShaper,
  installFontLoadInvalidation,
  clearMeasurementCache,
  measurementCacheSize,
} from "../src/engine/canvas-shaping.js";
import type {
  CanvasTextShaperInstance,
  CanvasShapingEnv,
  CanvasShapingResult,
  ProbeElementLike,
  CjkDashCapability,
  ShapeInput,
} from "../src/engine/canvas-shaping.js";
import type { CanvasContextLike, CanvasTextMetricsLike } from "../src/engine/canvas-metrics.js";
import { initializeGlobalServices } from "../src/services/global-services.js";
initializeGlobalServices();


const PARITY_PROBE_TEXT: string = "Benjamini-Hochberg WAVE fjord, 0x7f.";
const DEGENERATE_PROBE_TEXT: string = "\u3002";

interface HarnessProgram {
  measureText: HarnessMeasureFn;
  probeWidth?: HarnessProbeWidthFn;
  cjkDashCapability?: CjkDashCapability | null;
  record?: HarnessRecordEntry[];
  getImageData?: HarnessGetImageDataFn;
}

type HarnessMeasureFn = (text: string, font: string) => CanvasTextMetricsLike;
type HarnessProbeWidthFn = (text: string) => number;

interface ShapingImageDataLike {
  data: Uint8ClampedArray;
}

type HarnessGetImageDataFn = (x: number, y: number, w: number, h: number) => ShapingImageDataLike;

interface ShapingProbeRectWidth {
  width: number;
}

interface HarnessRecordEntry {
  name: string;
  args: unknown[];
}

interface HarnessStyleEntry {
  value: string;
  priority: string | undefined;
}

interface HarnessProbeElement extends ProbeElementLike {
  styleEntries: Record<string, HarnessStyleEntry>;
  textMeasureCalls: Record<string, number>;
}

interface Harness {
  env: HarnessEnv;
  fonts: WebFontFamiliesInstance;
  shaper: CanvasTextShaperInstance;
  measureCount: Record<string, number>;
}

interface HarnessEnv extends CanvasShapingEnv {
  probes: HarnessProbeElement[];
}

type HarnessVoidCallbackFn = () => void;

interface FontSetLike {
  listeners: Record<string, HarnessVoidCallbackFn>;
  addEventListener(name: string, fn: HarnessVoidCallbackFn): void;
}

interface CanvasSizeLike {
  width: number;
  height: number;
}

function makeHarness(program: HarnessProgram): Harness {
  clearMeasurementCache();
  const cfg: HarnessProgram = program;
  const measureCount: Record<string, number> = {};
  const probes: HarnessProbeElement[] = [];

  function makeProbe(): HarnessProbeElement {
    const styleEntries: Record<string, HarnessStyleEntry> = {};
    const textMeasureCalls: Record<string, number> = {};
    const element: HarnessProbeElement = {
      parentNode: null,
      textContent: "",
      setAttribute(_name: string, _value: string): void {},
      style: {
        setProperty(name: string, value: string, priority?: string): void {
          styleEntries[name] = { value, priority };
        },
      },
      getBoundingClientRect(): ShapingProbeRectWidth {
        const key: string = element.textContent;
        textMeasureCalls[key] = (textMeasureCalls[key] || 0) + 1;
        return { width: cfg.probeWidth ? cfg.probeWidth(key) : 0 };
      },
      styleEntries,
      textMeasureCalls,
    };
    probes.push(element);
    return element;
  }

  function createCanvasContext(): CanvasContextLike {
    const canvas: CanvasSizeLike = { width: 0, height: 0 };
    return {
      canvas,
      font: "",
      measureText(text: string): CanvasTextMetricsLike {
        measureCount[text] = (measureCount[text] || 0) + 1;
        return cfg.measureText(text, this.font);
      },
      setTransform(...args: number[]): void {
        if (cfg.record) cfg.record.push({ name: "setTransform", args });
      },
      clearRect(...args: number[]): void {
        if (cfg.record) cfg.record.push({ name: "clearRect", args });
      },
      fillText(...args: unknown[]): void {
        if (cfg.record) cfg.record.push({ name: "fillText", args });
      },
      getImageData(x: number, y: number, w: number, h: number): ShapingImageDataLike {
        return cfg.getImageData ? cfg.getImageData(x, y, w, h) : { data: new Uint8ClampedArray(w * h * 4) };
      },
    };
  }

  function createProbeElement(): HarnessProbeElement {
    return makeProbe();
  }

  function attachProbe(element: ProbeElementLike): void {
    if (element.parentNode == null) element.parentNode = {};
  }

  const fonts: WebFontFamiliesInstance = createFontFamilies({
    cjk: '"CJK", sans-serif',
    latin: '"Latin", sans-serif',
  });
  const env: HarnessEnv = { createCanvasContext, createProbeElement, attachProbe, probes };
  const shaper: CanvasTextShaperInstance = createTextShaper(fonts, cfg.cjkDashCapability ?? null, env);
  return { env, fonts, shaper, measureCount };
}

function findParityProbe(env: HarnessEnv): HarnessProbeElement | undefined {
  return env.probes.find((p: HarnessProbeElement): boolean => !p.styleEntries["border"]);
}

function findFeatureProbe(env: HarnessEnv): HarnessProbeElement | undefined {
  return env.probes.find((p: HarnessProbeElement): boolean => !!p.styleEntries["border"]);
}

function defaultMetrics(): CanvasTextMetricsLike {
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

function input(overrides: Partial<ShapeInput> = {}): ShapeInput {
  return {
    text: "\u4e2d",
    range: { start: 0, end: 1 },
    style: { fontSize: 19, fontWeight: 400, italic: false, fontFamilies: [] },
    fontDecision: { role: "CjkText", candidate: { key: "k1" } },
    displayText: "\u4e2d",
    ...overrides,
  };
}

interface InkRect {
  minX: number;
  maxX: number;
  minY: number;
  maxY: number;
}

function buildInkData(width: number, height: number, ink: InkRect): ShapingImageDataLike {
  const data: Uint8ClampedArray = new Uint8ClampedArray(width * height * 4);
  for (let y: number = ink.minY; y <= ink.maxY; y += 1) {
    for (let x: number = ink.minX; x <= ink.maxX; x += 1) {
      data[(y * width + x) * 4 + 3] = 255;
    }
  }
  return { data };
}

test("plain CJK measure: advance from width, bounds from the actualBoundingBox quadruple, exact reason", () => {
  const { shaper } = makeHarness({
    measureText: (): CanvasTextMetricsLike => defaultMetrics(),
  });
  const result: CanvasShapingResult = shaper.shape(input({ text: "\u4e2d", displayText: "\u4e2d" }));
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
    measureText: (_text: string, font: string): CanvasTextMetricsLike => {
      if (font.indexOf("FamilyA") !== -1) return { ...defaultMetrics(), width: 0 };
      return { ...defaultMetrics(), width: 30 };
    },
  });
  const result: CanvasShapingResult = shaper.shape(
    input({
      text: "\u4e2d",
      displayText: "\u4e2d",
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
    measureText: (): CanvasTextMetricsLike => ({ ...defaultMetrics(), width: 0 }),
  });
  const result: CanvasShapingResult = shaper.shape(
    input({
      text: "a\nb",
      displayText: "a\nb",
      style: { fontSize: 19, fontWeight: 400, italic: false, fontFamilies: ["FamilyA", "FamilyB"] },
    }),
  );
  const decision = result.decisions[0];
  assert.equal(decision.advance, 0);
  assert.ok(decision.reason.includes("; stackIndex=0;"));
  assert.equal(measureCount["a\nb"], 1);
});

test("CanvasDomAdvanceParityGate: Latin divergence uses hidden-DOM advance; CJK exempt", () => {
  const latin: Harness = makeHarness({
    measureText: (text: string): CanvasTextMetricsLike => {
      if (text === PARITY_PROBE_TEXT) return { ...defaultMetrics(), width: 100 };
      return { ...defaultMetrics(), width: 9 };
    },
    probeWidth: (text: string): number => (text === PARITY_PROBE_TEXT ? 200 : 50),
  });
  const latinResult: CanvasShapingResult = latin.shaper.shape(
    input({
      text: "hello",
      displayText: "hello",
      fontDecision: { role: "LatinText", candidate: { key: "k" } },
    }),
  );
  const latinDecision = latinResult.decisions[0];
  assert.equal(latinDecision.advance, 50);
  assert.equal(latinResult.glyphRuns[0].glyphs[0].bounds, null);
  assert.ok(latinDecision.reason.includes("; inkBounds=CanvasDomAdvanceParityGate"));
  assert.equal(latinDecision.glyphsWithoutInkBounds, 1);

  const cjk: Harness = makeHarness({
    measureText: (text: string): CanvasTextMetricsLike => {
      if (text === PARITY_PROBE_TEXT) return { ...defaultMetrics(), width: 100 };
      return { ...defaultMetrics(), width: 9 };
    },
    probeWidth: (text: string): number => (text === PARITY_PROBE_TEXT ? 200 : 50),
  });
  const cjkResult: CanvasShapingResult = cjk.shaper.shape(input({ text: "\u4e2d", displayText: "\u4e2d" }));
  const cjkDecision = cjkResult.decisions[0];
  assert.equal(cjkDecision.advance, 9);
  assert.ok(!cjkDecision.reason.includes("CanvasDomAdvanceParityGate"));
  const parityProbe = findParityProbe(cjk.env);
  assert.equal(parityProbe, undefined);
});

test("parity verdict cached per font: hidden-DOM probe runs once", () => {
  const { env, shaper } = makeHarness({
    measureText: (text: string): CanvasTextMetricsLike => {
      if (text === PARITY_PROBE_TEXT) return { ...defaultMetrics(), width: 100 };
      return { ...defaultMetrics(), width: 9 };
    },
    probeWidth: (text: string): number => (text === PARITY_PROBE_TEXT ? 200 : 50),
  });
  const base: Partial<ShapeInput> = {
    style: { fontSize: 19, fontWeight: 400, italic: false, fontFamilies: [] },
    fontDecision: { role: "LatinText", candidate: { key: "k" } },
  };
  shaper.shape(input({ text: "one", displayText: "one", ...base }));
  shaper.shape(input({ text: "two", displayText: "two", ...base }));
  const parityProbe = findParityProbe(env);
  assert.equal(parityProbe!.textMeasureCalls[PARITY_PROBE_TEXT], 1);
});

test("curly quote: features pwid,palt with advance from the feature probe", () => {
  const { shaper, env } = makeHarness({
    measureText: (): CanvasTextMetricsLike => defaultMetrics(),
    probeWidth: (text: string): number => (text === "\u2018" ? 12 : 0),
  });
  const result: CanvasShapingResult = shaper.shape(
    input({
      text: "\u2018",
      displayText: "\u2018",
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
  assert.equal(featureProbe!.styleEntries["font-feature-settings"].value, '"palt" 1');
  assert.equal(featureProbe!.styleEntries["font-variant-east-asian"].value, "proportional-width");
});

test("degenerate ink probe: CjkPunctuation rasterizes ink bounds and matches hand-computed back-transform", () => {
  const scale: number = 4;
  const margin: number = 19;
  const baseline: number = 19 * 1.25;
  const ink: InkRect = { minX: 100, maxX: 120, minY: 40, maxY: 50 };
  const { shaper } = makeHarness({
    measureText: (text: string): CanvasTextMetricsLike => {
      if (text === DEGENERATE_PROBE_TEXT) {
        return { ...defaultMetrics(), width: 19, actualBoundingBoxLeft: 0, actualBoundingBoxRight: 19 };
      }
      return { ...defaultMetrics(), width: 19 };
    },
    getImageData: (x: number, y: number, w: number, h: number): ShapingImageDataLike => buildInkData(w, h, ink),
  });
  const result: CanvasShapingResult = shaper.shape(
    input({
      text: "\uff0c",
      displayText: "\uff0c",
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
    measureText: (text: string): CanvasTextMetricsLike => {
      if (text === DEGENERATE_PROBE_TEXT) {
        return { ...defaultMetrics(), width: 19, actualBoundingBoxLeft: 0, actualBoundingBoxRight: 19 };
      }
      return { ...defaultMetrics(), width: 19 };
    },
    getImageData: (): ShapingImageDataLike => buildInkData(228, 152, { minX: 100, maxX: 120, minY: 40, maxY: 50 }),
  });
  const base: Partial<ShapeInput> = {
    style: { fontSize: 19, fontWeight: 400, italic: false, fontFamilies: [] },
    fontDecision: { role: "CjkPunctuation", candidate: { key: "k" } },
  };
  shaper.shape(input({ text: "\uff0c", displayText: "\uff0c", ...base }));
  shaper.shape(input({ text: "\u3001", displayText: "\u3001", ...base }));
  assert.equal(measureCount[DEGENERATE_PROBE_TEXT], 1);
});

test("subpixel clamp: overhangs under 1px clamp to the advance box, over 1px kept", () => {
  const clamped: CanvasShapingResult = makeHarness({
    measureText: (text: string): CanvasTextMetricsLike => {
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
      text: "\uff0c",
      displayText: "\uff0c",
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

  const keptHarness: Harness = makeHarness({
    measureText: (text: string): CanvasTextMetricsLike => {
      if (text === DEGENERATE_PROBE_TEXT) return { ...defaultMetrics(), width: 0 };
      return {
        ...defaultMetrics(),
        width: 19,
        actualBoundingBoxLeft: 1.2,
        actualBoundingBoxRight: 20.2,
      };
    },
  });
  const kept: CanvasShapingResult = keptHarness.shaper.shape(
    input({
      text: "\uff0c",
      displayText: "\uff0c",
      fontDecision: { role: "CjkPunctuation", candidate: { key: "k" } },
    }),
  );
  assert.deepEqual(kept.glyphRuns[0].glyphs[0].bounds, { left: -1.2, top: 19, right: 20.2, bottom: 4 });
  assert.ok(!kept.decisions[0].reason.includes("SubpixelCanvasInkOverhangClamp"));
});

test("dash: CjkDashCapabilityPolicy issue name and detail variants", () => {
  const conforming: Harness = makeHarness({
    cjkDashCapability: { status: "conforming", detail: null },
    measureText: (): CanvasTextMetricsLike => ({ ...defaultMetrics(), width: 38 }),
  });
  const cjkDashInput = (): ShapeInput => input({
    text: "\u2014\u2014",
    range: { start: 0, end: 2 },
    displayText: "\u2014\u2014",
    fontDecision: { role: "CjkPunctuation", candidate: { key: "k1" } },
  });
  const cResult: CanvasShapingResult = conforming.shaper.shape(cjkDashInput());
  assert.equal(cResult.decisions[0].capabilityIssue, "ConformingCjkDashRequiresSnapshotFontSession");
  assert.ok(cResult.decisions[0].reason.includes("; status=conforming"));

  const partial: Harness = makeHarness({
    cjkDashCapability: { status: "partial", detail: "probe-detail" },
    measureText: (): CanvasTextMetricsLike => ({ ...defaultMetrics(), width: 38 }),
  });
  const pResult: CanvasShapingResult = partial.shaper.shape(cjkDashInput());
  assert.equal(pResult.decisions[0].capabilityIssue, "NoConformingCjkDashGlyph");
  assert.ok(pResult.decisions[0].reason.includes("; status=partial; probe-detail"));

  const none: Harness = makeHarness({
    cjkDashCapability: null,
    measureText: (): CanvasTextMetricsLike => ({ ...defaultMetrics(), width: 38 }),
  });
  const nResult: CanvasShapingResult = none.shaper.shape(cjkDashInput());
  assert.equal(nResult.decisions[0].capabilityIssue, "NoConformingCjkDashGlyph");
  assert.ok(nResult.decisions[0].reason.includes("; CjkDashFontShapingNotPrepared"));
});

test("dash: a Western-resolved source \u2014\u2014 carries no CJK dash capability issue", () => {
  const { shaper } = makeHarness({
    cjkDashCapability: null,
    measureText: (): CanvasTextMetricsLike => ({ ...defaultMetrics(), width: 38 }),
  });
  const result: CanvasShapingResult = shaper.shape(
    input({
      text: "\u2014\u2014",
      range: { start: 0, end: 2 },
      displayText: "\u2014\u2014",
      fontDecision: { role: "LatinText", candidate: { key: "k1" } },
    }),
  );
  assert.equal(result.decisions[0].capabilityIssue, null);
});

test("ellipsis: unverified display substitution carries the U+22EF issue", () => {
  const { shaper } = makeHarness({
    measureText: (): CanvasTextMetricsLike => defaultMetrics(),
  });
  const result: CanvasShapingResult = shaper.shape(
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

  interface Sharer {
    shaper: CanvasTextShaperInstance;
    measureCount: Record<string, number>;
  }

  function makeSharer(): Sharer {
    const program = {
      measureText: (_text: string): CanvasTextMetricsLike => defaultMetrics(),
    };
    const fonts: WebFontFamiliesInstance = createFontFamilies({
      cjk: '"CJK", sans-serif',
      latin: '"Latin", sans-serif',
    });
    const measureCount: Record<string, number> = {};
    const env: CanvasShapingEnv = {
      createCanvasContext(): CanvasContextLike {
        return {
          canvas: { width: 0, height: 0 },
          font: "",
          measureText(text: string): CanvasTextMetricsLike {
            measureCount[text] = (measureCount[text] || 0) + 1;
            return program.measureText(text);
          },
          setTransform(_a: number, _b: number, _c: number, _d: number, _e: number, _f: number): void {},
          clearRect(_x: number, _y: number, _w: number, _h: number): void {},
          fillText(_text: string, _x: number, _y: number): void {},
          getImageData(_x: number, _y: number, w: number, h: number): ShapingImageDataLike {
            return { data: new Uint8ClampedArray(w * h * 4) };
          },
        };
      },
      createProbeElement(): ProbeElementLike {
        return {
          parentNode: null,
          textContent: "",
          setAttribute(_name: string, _value: string): void {},
          style: { setProperty(_name: string, _value: string, _priority?: string): void {} },
          getBoundingClientRect(): ShapingProbeRectWidth {
            return { width: 0 };
          },
        };
      },
      attachProbe(_element: ProbeElementLike): void {},
    };
    const shaper: CanvasTextShaperInstance = createTextShaper(fonts, null, env);
    return { shaper, measureCount };
  }

  function shapeText(shaper: CanvasTextShaperInstance, text: string): void {
    shaper.shape(
      input({ text, displayText: text, style: { fontSize: 19, fontWeight: 400, italic: false, fontFamilies: [] } }),
    );
  }

  const a: Sharer = makeSharer();
  const b: Sharer = makeSharer();
  shapeText(a.shaper, "shared");
  shapeText(b.shaper, "shared");
  assert.equal(a.measureCount["shared"], 1);

  const c: Sharer = makeSharer();
  shapeText(c.shaper, "elder");
  shapeText(c.shaper, "keep");
  shapeText(c.shaper, "keep");
  for (let i: number = 0; i < 2047; i += 1) {
    shapeText(c.shaper, "e" + String(i).padStart(4, "0"));
  }
  assert.equal(measurementCacheSize(), 2048);
  shapeText(c.shaper, "keep");
  assert.equal(c.measureCount["keep"], 1);
  shapeText(c.shaper, "elder");
  assert.equal(c.measureCount["elder"], 2);
});

test("clearMeasurementCache and installFontLoadInvalidation", () => {
  clearMeasurementCache();

  const fontSet: FontSetLike = {
    listeners: {},
    addEventListener(name: string, fn: HarnessVoidCallbackFn): void {
      this.listeners[name] = fn;
    },
  };
  installFontLoadInvalidation(fontSet);
  const fontSet2: FontSetLike = {
    listeners: {},
    addEventListener(name: string, fn: HarnessVoidCallbackFn): void {
      this.listeners[name] = fn;
    },
  };
  installFontLoadInvalidation(fontSet2);
  assert.equal(Object.keys(fontSet2.listeners).length, 0);
  assert.equal(typeof fontSet.listeners["loadingdone"], "function");

  const program = {
    measureText: (text: string, _font: string): CanvasTextMetricsLike => {
      if (text === PARITY_PROBE_TEXT) return { ...defaultMetrics(), width: 100 };
      return { ...defaultMetrics(), width: 9 };
    },
    probeWidth: (text: string): number => (text === PARITY_PROBE_TEXT ? 200 : 50),
  };
  const fonts: WebFontFamiliesInstance = createFontFamilies({ cjk: '"CJK", sans-serif', latin: '"Latin", sans-serif' });
  const probes: HarnessProbeElement[] = [];
  const env: HarnessEnv = {
    createCanvasContext(): CanvasContextLike {
      return {
        canvas: { width: 0, height: 0 },
        font: "",
        measureText(text: string): CanvasTextMetricsLike {
          return program.measureText(text, this.font);
        },
        setTransform(_a: number, _b: number, _c: number, _d: number, _e: number, _f: number): void {},
        clearRect(_x: number, _y: number, _w: number, _h: number): void {},
        fillText(_text: string, _x: number, _y: number): void {},
        getImageData(_x: number, _y: number, w: number, h: number): ShapingImageDataLike {
          return { data: new Uint8ClampedArray(w * h * 4) };
        },
      };
    },
    createProbeElement(): HarnessProbeElement {
      const textMeasureCalls: Record<string, number> = {};
      const styleEntries: Record<string, HarnessStyleEntry> = {};
      const element: HarnessProbeElement = {
        parentNode: null,
        textContent: "",
        setAttribute(_name: string, _value: string): void {},
        style: { setProperty(name: string, value: string, priority?: string): void { styleEntries[name] = { value, priority }; } },
        getBoundingClientRect(): ShapingProbeRectWidth {
          textMeasureCalls[element.textContent] = (textMeasureCalls[element.textContent] || 0) + 1;
          return { width: program.probeWidth(element.textContent) };
        },
        styleEntries,
        textMeasureCalls,
      };
      probes.push(element);
      return element;
    },
    attachProbe(_element: ProbeElementLike): void {},
    probes,
  };
  const shaper: CanvasTextShaperInstance = createTextShaper(fonts, null, env);
  shaper.shape(
    input({ text: "hello", displayText: "hello", fontDecision: { role: "LatinText", candidate: { key: "k" } } }),
  );
  assert.ok(measurementCacheSize() >= 1);
  const parityProbe = probes.find((p: HarnessProbeElement): boolean => !p.styleEntries["border"]);
  assert.equal(parityProbe!.textMeasureCalls[PARITY_PROBE_TEXT], 1);

  fontSet.listeners["loadingdone"]();
  assert.equal(measurementCacheSize(), 0);
  shaper.shape(
    input({ text: "hello", displayText: "hello", fontDecision: { role: "LatinText", candidate: { key: "k" } } }),
  );
  assert.equal(parityProbe!.textMeasureCalls[PARITY_PROBE_TEXT], 2);
});
