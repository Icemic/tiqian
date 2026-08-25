import assert from "node:assert/strict";
import test from "node:test";

import { precomputeParagraphWithDiagnostics } from "@tiqian/ffi";
import { createProbeBootstrapFontSession } from "./session-bootstrap.js";

/**
 * End-to-end plan parity (ADR 0053 A5c): the same paragraph laid out through
 * the probe-bootstrapped worker session and through a scripted backend that
 * mirrors the main-thread canvas model must produce identical plans, up to
 * the em round-trip float tolerance listed per field path.
 */

const PLAN_NUMBER_TOLERANCE = 1e-9;
const PARAGRAPH_ARGUMENTS = ["中文中文", 36, "Fixture CJK", 18, 27, "zh-Hans", 400, false, 0, true];

function fakeCanvasMeasurement(text) {
  if (text === "Hg") {
    return { width: 18, fontBoundingBoxAscent: 22, fontBoundingBoxDescent: 6 };
  }
  return {
    width: 18,
    fontBoundingBoxAscent: 30,
    fontBoundingBoxDescent: 10,
    ideographicBaseline: -12,
  };
}

function makeScriptedCanvasModelCallbacks() {
  let nextHandle = 1;
  const shapes = new Map();
  const metrics = new Map();
  return {
    shapeJson: (requestJson) => {
      const request = JSON.parse(requestJson);
      // Canvas-model shape: one glyph per cluster, id 0, x 0, no ink bounds.
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
      // WebCanvasFontMetricsResolver in px: CJK boxes derive the 字身框 from
      // ideographicBaseline (-12), Latin roles leave typo values unset.
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

test("probe bootstrap plan matches the canvas model plan end to end", async () => {
  const measureCalls = [];
  const probeSession = await createProbeBootstrapFontSession("parity-a", {
    measureAdapter: (cssFont, text) => {
      measureCalls.push(`${cssFont} :: ${text}`);
      return fakeCanvasMeasurement(text);
    },
  });

  let planA;
  try {
    const { shapeJson, metricsJson } = probeSession;
    const envelope = precomputeParagraphWithDiagnostics(
      ...PARAGRAPH_ARGUMENTS,
      "", // sourceBoundaries
      "", // textSpans
      "", // inlineBoxes
      "", // lineBreakSpans
      "", // inlineObjects
      0.0, // zeroAdvanceEpsilonPx
      shapeJson,
      metricsJson,
      "", // decorations
      null, // emphasisDotGapEm
      null, // renderEvidenceOverride: wire-derived verdict
    );
    planA = JSON.parse(JSON.parse(envelope).plan);
  } finally {
    probeSession.close();
  }

  // Use scripted canvas model callbacks
  const { shapeJson: scriptedShapeJson, metricsJson: scriptedMetricsJson } = makeScriptedCanvasModelCallbacks();

  let planB;
  try {
    const envelope = precomputeParagraphWithDiagnostics(
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
    planB = JSON.parse(JSON.parse(envelope).plan);
  } finally {
    // no globals to restore
  }

  assert.ok(measureCalls.length > 0);
  assert.deepEqual(
    planA.lines.map((line) => [line.rangeStart, line.rangeEnd]),
    planB.lines.map((line) => [line.rangeStart, line.rangeEnd]),
  );
  comparePlans(planA, planB, "$");
  console.log(
    "tolerance-hit field paths:",
    toleranceHits.length === 0 ? "(none)" : JSON.stringify(toleranceHits),
  );
});