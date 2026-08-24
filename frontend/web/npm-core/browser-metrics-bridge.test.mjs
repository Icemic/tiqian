import assert from "node:assert/strict";
import test from "node:test";

import { precomputeParagraphWithBrowserMetrics, precomputePlainParagraph } from "@tiqian/ffi";

import "./core/engine/canvas-fonts.js";
import "./core/engine/canvas-metrics.js";
import "./core/engine/canvas-shaping.js";
import "./core/engine/browser-metrics-bridge.js";

const canvasFonts = globalThis.__TiqianCanvasFonts;
const canvasShaping = globalThis.__TiqianCanvasShaping;
const browserMetricsBridge = globalThis.__TiqianBrowserMetricsBridge;

const EXPECTED_FIRST_SHAPING_REQUEST =
  '{"text":"\u4e2d\u6587\u4e2d\u6587","range":{"start":0,"end":1},"style":{"fontFamilies":["Fixture CJK"],"fontSize":18,"fontWeight":400,"italic":false,"locale":"zh-Hans"},"fontDecision":{"role":"CjkText","candidateKey":"cjk-primary"},"displayText":"\u4e2d","openTypeFeatures":[]}';

const EXPECTED_FIRST_METRICS_REQUEST =
  '{"fontKey":"cjk-primary","fontSize":18,"role":"CjkText","locale":"zh-Hans","fontFamilies":["Fixture CJK"],"fontWeight":400,"italic":false,"faceSelectionText":"\u4e2d"}';

const PARAGRAPH_ARGUMENTS = ["\u4e2d\u6587\u4e2d\u6587", 36, "Fixture CJK", 18, 27, "zh-Hans", 400, false, 0, true];

function fakeCanvasMeasurement(text) {
  if (text === "Hg") {
    return {
      width: 18,
      fontBoundingBoxAscent: 22,
      fontBoundingBoxDescent: 6,
      actualBoundingBoxLeft: 0,
      actualBoundingBoxAscent: 22,
      actualBoundingBoxRight: 18,
      actualBoundingBoxDescent: 6,
    };
  }
  return {
    width: 18,
    fontBoundingBoxAscent: 30,
    fontBoundingBoxDescent: 10,
    ideographicBaseline: -12,
    actualBoundingBoxLeft: -0.1,
    actualBoundingBoxAscent: 18,
    actualBoundingBoxRight: 18.1,
    actualBoundingBoxDescent: 4,
  };
}

function makeFakeEnv(customMeasure) {
  const measureFn = customMeasure || fakeCanvasMeasurement;
  function createCanvasContext() {
    return {
      canvas: { width: 0, height: 0 },
      font: "",
      measureText(text) {
        return measureFn(text, this.font);
      },
      setTransform() {},
      clearRect() {},
      fillText() {},
      getImageData(x, y, w, h) {
        return { data: new Uint8ClampedArray(w * h * 4) };
      },
    };
  }
  function createProbeElement() {
    return {
      parentNode: null,
      textContent: "",
      setAttribute() {},
      style: {
        setProperty() {},
      },
      getBoundingClientRect() {
        return { width: 0 };
      },
    };
  }
  function attachProbe(element) {
    if (element.parentNode == null) element.parentNode = {};
  }
  return { createCanvasContext, createProbeElement, attachProbe };
}

function installScriptedCanvasModelBackend() {
  let nextHandle = 1;
  const shapes = new Map();
  const metrics = new Map();
  globalThis.__TiqianFontBackend = {
    shape(_sessionId, _displayText) {
      const handle = nextHandle++;
      shapes.set(handle, {
        faceId: "",
        fontInstanceId: "",
        script: "",
        features: [],
        unsafeBreakCount: 0,
        advance: 18,
        glyphs: [{ id: 0, advance: 18, x: 0, y: 0, bounds: Number.NaN }],
      });
      return handle;
    },
    shapeGlyphCount: (handle) => shapes.get(handle)?.glyphs.length ?? 0,
    shapeGlyphId: (handle, index) => shapes.get(handle)?.glyphs[index]?.id ?? 0,
    shapeGlyphAdvance: (handle, index) => shapes.get(handle)?.glyphs[index]?.advance ?? 0,
    shapeGlyphX: (handle, index) => shapes.get(handle)?.glyphs[index]?.x ?? 0,
    shapeGlyphY: (handle, index) => shapes.get(handle)?.glyphs[index]?.y ?? 0,
    shapeGlyphBound: (handle) => shapes.get(handle)?.glyphs[0]?.bounds ?? Number.NaN,
    shapeAdvance: (handle) => shapes.get(handle)?.advance ?? 0,
    shapeFaceId: (handle) => shapes.get(handle)?.faceId ?? "",
    shapeFontInstanceId: (handle) => shapes.get(handle)?.fontInstanceId ?? "",
    shapeScript: (handle) => shapes.get(handle)?.script ?? "",
    shapeFeatureCount: (handle) => shapes.get(handle)?.features.length ?? 0,
    shapeFeature: () => "",
    shapeUnsafeBreakCount: (handle) => shapes.get(handle)?.unsafeBreakCount ?? 0,
    releaseShape: (handle) => shapes.delete(handle),
    metrics(_sessionId, _families, fontSize, _fontWeight, _italic, role, _faceSelectionText) {
      const cjkBox = role === "CjkText" || role === "CjkPunctuation";
      const ideographicDescent = 12;
      const handle = nextHandle++;
      metrics.set(handle, cjkBox
        ? [30, 10, 0, Math.max(fontSize - ideographicDescent, 0), Math.max(ideographicDescent, 0)]
        : [22, 6, 0, Number.NaN, Number.NaN]);
      return handle;
    },
    metricValue: (handle, index) => metrics.get(handle)?.[index] ?? Number.NaN,
    releaseMetrics: (handle) => metrics.delete(handle),
  };
}

function restoreGlobals() {
  delete globalThis.__TiqianFontBackend;
  delete globalThis.__TiqianFontBackendReplayRegistry;
  delete globalThis.__TiqianFontBackendRevision;
}

const PLAN_NUMBER_TOLERANCE = 1e-9;
const toleranceHits = [];

function comparePlans(left, right, path) {
  if (typeof left === "number" && typeof right === "number") {
    if (Object.is(left, right)) return;
    const delta = Math.abs(left - right);
    if (delta <= PLAN_NUMBER_TOLERANCE * Math.max(1, Math.abs(right))) {
      toleranceHits.push(path);
      return;
    }
    throw new Error(`plan number mismatch at ${path}: ${left} != ${right}`);
  }
  if (Array.isArray(left) && Array.isArray(right)) {
    assert.equal(left.length, right.length, `${path}.length`);
    left.forEach((value, index) => comparePlans(value, right[index], `${path}[${index}]`));
    return;
  }
  if (left !== null && right !== null && typeof left === "object" && typeof right === "object") {
    const leftKeys = Object.keys(left).sort();
    const rightKeys = Object.keys(right).sort();
    assert.deepEqual(leftKeys, rightKeys, `${path} keys`);
    for (const key of leftKeys) comparePlans(left[key], right[key], `${path}.${key}`);
    return;
  }
  assert.strictEqual(left, right, path);
}

test("API surface exposes exactly createBrowserMetricsBridge", () => {
  assert.ok(browserMetricsBridge);
  assert.deepEqual(Object.keys(browserMetricsBridge), ["createBrowserMetricsBridge"]);
  assert.strictEqual(typeof browserMetricsBridge.createBrowserMetricsBridge, "function");
});

test("Shaping wire byte lock", () => {
  canvasShaping.clearMeasurementCache();
  const fonts = canvasFonts.createFontFamilies({
    cjk: '"Fixture CJK", sans-serif',
    latin: '"Fixture Latin", sans-serif',
  });
  const env = makeFakeEnv();
  const bridge = browserMetricsBridge.createBrowserMetricsBridge({
    fonts,
    cjkDashCapability: null,
    env,
  });

  const capturedShapeRequests = [];

  precomputeParagraphWithBrowserMetrics(
    "中文中文",
    36,
    "Fixture CJK",
    18,
    27,
    "zh-Hans",
    400,
    false,
    0,
    true,
    "",
    "",
    "",
    "",
    "",
    0.01,
    (req) => {
      capturedShapeRequests.push(req);
      return bridge.shapeJson(req);
    },
    (req) => bridge.metricsJson(req),
  );

  assert.ok(capturedShapeRequests.length > 0);
  assert.strictEqual(capturedShapeRequests[0], EXPECTED_FIRST_SHAPING_REQUEST);
});

test("Metrics wire byte lock", () => {
  canvasShaping.clearMeasurementCache();
  const fonts = canvasFonts.createFontFamilies({
    cjk: '"Fixture CJK", sans-serif',
    latin: '"Fixture Latin", sans-serif',
  });
  const env = makeFakeEnv();
  const bridge = browserMetricsBridge.createBrowserMetricsBridge({
    fonts,
    cjkDashCapability: null,
    env,
  });

  const capturedMetricsRequests = [];

  precomputeParagraphWithBrowserMetrics(
    "中文中文",
    36,
    "Fixture CJK",
    18,
    27,
    "zh-Hans",
    400,
    false,
    0,
    true,
    "",
    "",
    "",
    "",
    "",
    0.01,
    (req) => bridge.shapeJson(req),
    (req) => {
      capturedMetricsRequests.push(req);
      return bridge.metricsJson(req);
    },
  );

  assert.ok(capturedMetricsRequests.length > 0);
  assert.strictEqual(capturedMetricsRequests[0], EXPECTED_FIRST_METRICS_REQUEST);
});

test("End-to-end plan", () => {
  canvasShaping.clearMeasurementCache();
  const fonts = canvasFonts.createFontFamilies({
    cjk: '"Fixture CJK", sans-serif',
    latin: '"Fixture Latin", sans-serif',
  });
  const env = makeFakeEnv();
  const bridge = browserMetricsBridge.createBrowserMetricsBridge({
    fonts,
    cjkDashCapability: null,
    env,
  });

  const rawEnvelope = precomputeParagraphWithBrowserMetrics(
    "中文中文",
    36,
    "Fixture CJK",
    18,
    27,
    "zh-Hans",
    400,
    false,
    0,
    true,
    "",
    "",
    "",
    "",
    "",
    0.01,
    bridge.shapeJson,
    bridge.metricsJson,
  );

  const envelope = JSON.parse(rawEnvelope);
  const plan = JSON.parse(envelope.plan);

  assert.ok(plan.lines.length > 0);
  assert.strictEqual(plan.lines[0].rangeStart, 0);
  assert.strictEqual(plan.lines[plan.lines.length - 1].rangeEnd, 4);
  assert.deepEqual(envelope.diagnostics.capabilityIssues, []);
  assert.deepEqual(envelope.diagnostics.advanceSuspects, []);
});

test("Parity against the scripted canvas-model backend", () => {
  canvasShaping.clearMeasurementCache();
  const fonts = canvasFonts.createFontFamilies({
    cjk: '"Fixture CJK", sans-serif',
    latin: '"Fixture Latin", sans-serif',
  });
  const env = makeFakeEnv();
  const bridge = browserMetricsBridge.createBrowserMetricsBridge({
    fonts,
    cjkDashCapability: null,
    env,
  });

  const rawEnvelope = precomputeParagraphWithBrowserMetrics(
    ...PARAGRAPH_ARGUMENTS,
    "",
    "",
    "",
    "",
    "",
    0.01,
    bridge.shapeJson,
    bridge.metricsJson,
  );
  const envelope = JSON.parse(rawEnvelope);
  const planA = JSON.parse(envelope.plan);

  restoreGlobals();
  installScriptedCanvasModelBackend();

  let planB;
  try {
    planB = JSON.parse(precomputePlainParagraph("canvas-model", ...PARAGRAPH_ARGUMENTS));
  } finally {
    restoreGlobals();
  }

  assert.deepEqual(
    planA.lines.map((line) => [line.rangeStart, line.rangeEnd]),
    planB.lines.map((line) => [line.rangeStart, line.rangeEnd]),
  );
  comparePlans(planA, planB, "$");
});

test("Dash capability passthrough", () => {
  canvasShaping.clearMeasurementCache();
  const fonts = canvasFonts.createFontFamilies({
    cjk: '"Fixture CJK", sans-serif',
    latin: '"Fixture Latin", sans-serif',
  });
  const env = makeFakeEnv();
  const bridge = browserMetricsBridge.createBrowserMetricsBridge({
    fonts,
    cjkDashCapability: { status: "partial", detail: "probe-detail" },
    env,
  });

  const rawEnvelope = precomputeParagraphWithBrowserMetrics(
    "\u2014\u2014",
    36,
    "Fixture CJK",
    18,
    27,
    "zh-Hans",
    400,
    false,
    0,
    true,
    "",
    "",
    "",
    "",
    "",
    0.01,
    bridge.shapeJson,
    bridge.metricsJson,
  );

  const envelope = JSON.parse(rawEnvelope);
  const issue = envelope.diagnostics.capabilityIssues.find(
    (item) => item.name === "NoConformingCjkDashGlyph",
  );
  assert.ok(issue, "Expected NoConformingCjkDashGlyph capability issue");
  assert.ok(
    issue.reason.includes("; status=partial; probe-detail"),
    `Expected reason to contain '; status=partial; probe-detail', got: ${issue.reason}`,
  );
});
