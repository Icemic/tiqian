import assert from "node:assert/strict";
import test from "node:test";

import { precomputeParagraphWithBrowserMetrics, precomputeParagraphWithDiagnostics } from "@tiqian/ffi";

import { createFontFamilies } from "./core/engine/canvas-fonts.js";
import { clearMeasurementCache } from "./core/engine/canvas-shaping.js";
import { createBrowserMetricsBridge } from "./core/engine/browser-metrics-bridge.js";

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

function makeScriptedCanvasModelCallbacks() {
  let nextHandle = 1;
  const shapes = new Map();
  const metrics = new Map();
  return {
    shapeJson: (requestJson) => {
      const request = JSON.parse(requestJson);
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
      return JSON.stringify({
        clusters: [{
          range: request.range,
          text: request.text.substring(request.range.start, request.range.end),
          displayText: request.displayText,
          fontKey: request.fontDecision.candidateKey,
          advance: 18,
        }],
        glyphRuns: [{
          range: request.range,
          fontKey: request.fontDecision.candidateKey,
          glyphs: [{ id: 0, clusterRange: request.range, advance: 18, x: 0, y: 0, bounds: null }],
          advance: 18,
          openTypeFeatures: [],
        }],
        decisions: [{
          range: request.range,
          sourceText: request.text.substring(request.range.start, request.range.end),
          displayText: request.displayText,
          fontKey: request.fontDecision.candidateKey,
          glyphCount: 1,
          advance: 18,
          source: "HarfBuzz",
          reason: "test",
          glyphsWithoutInkBounds: 1,
          missingGlyphs: 1,
          resolvedFace: "",
          script: "",
          language: request.style.locale,
          featureEvidence: null,
        }],
      });
    },
    metricsJson: (requestJson) => {
      const request = JSON.parse(requestJson);
      const cjkBox = request.role === "CjkText" || request.role === "CjkPunctuation";
      const ideographicDescent = 12;
      const handle = nextHandle++;
      metrics.set(handle, cjkBox
        ? [30, 10, 0, Math.max(request.fontSize - ideographicDescent, 0), Math.max(ideographicDescent, 0)]
        : [22, 6, 0, Number.NaN, Number.NaN]);
      const m = metrics.get(handle);
      return JSON.stringify({
        ascent: m[0],
        descent: m[1],
        leading: m[2],
        source: "RawTables",
        typoAscent: Number.isNaN(m[3]) ? null : m[3],
        typoDescent: Number.isNaN(m[4]) ? null : m[4],
      });
    },
  };
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

test("API surface exposes createBrowserMetricsBridge", () => {
  assert.strictEqual(typeof createBrowserMetricsBridge, "function");
});

test("Shaping wire byte lock", () => {
  clearMeasurementCache();
  const fonts = createFontFamilies({
    cjk: '"Fixture CJK", sans-serif',
    latin: '"Fixture Latin", sans-serif',
  });
  const env = makeFakeEnv();
  const bridge = createBrowserMetricsBridge({
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
  clearMeasurementCache();
  const fonts = createFontFamilies({
    cjk: '"Fixture CJK", sans-serif',
    latin: '"Fixture Latin", sans-serif',
  });
  const env = makeFakeEnv();
  const bridge = createBrowserMetricsBridge({
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
  clearMeasurementCache();
  const fonts = createFontFamilies({
    cjk: '"Fixture CJK", sans-serif',
    latin: '"Fixture Latin", sans-serif',
  });
  const env = makeFakeEnv();
  const bridge = createBrowserMetricsBridge({
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
  clearMeasurementCache();
  const fonts = createFontFamilies({
    cjk: '"Fixture CJK", sans-serif',
    latin: '"Fixture Latin", sans-serif',
  });
  const env = makeFakeEnv();
  const bridge = createBrowserMetricsBridge({
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

  const { shapeJson: scriptedShapeJson, metricsJson: scriptedMetricsJson } = makeScriptedCanvasModelCallbacks();

  let planB;
  try {
    const rawScriptedEnvelope = precomputeParagraphWithDiagnostics(
      ...PARAGRAPH_ARGUMENTS,
      "",
      "",
      "",
      "",
      "",
      0.0,
      scriptedShapeJson,
      scriptedMetricsJson,
      "",
      null,
      null,
    );
    planB = JSON.parse(JSON.parse(rawScriptedEnvelope).plan);
  } finally {
    // no globals to restore
  }

  assert.deepEqual(
    planA.lines.map((line) => [line.rangeStart, line.rangeEnd]),
    planB.lines.map((line) => [line.rangeStart, line.rangeEnd]),
  );
  comparePlans(planA, planB, "$");
});

test("Dash capability passthrough", () => {
  clearMeasurementCache();
  const fonts = createFontFamilies({
    cjk: '"Fixture CJK", sans-serif',
    latin: '"Fixture Latin", sans-serif',
  });
  const env = makeFakeEnv();
  const bridge = createBrowserMetricsBridge({
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