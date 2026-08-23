import assert from "node:assert/strict";
import test from "node:test";

import { precomputePlainParagraph } from "@tiqian/ffi";
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

function installScriptedCanvasModelBackend() {
  let nextHandle = 1;
  const shapes = new Map();
  const metrics = new Map();
  globalThis.__TiqianFontBackend = {
    shape(_sessionId, _displayText) {
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
      // WebCanvasFontMetricsResolver in px: CJK boxes derive the 字身框 from
      // ideographicBaseline (-12), Latin roles leave typo values unset.
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
    planA = JSON.parse(precomputePlainParagraph(probeSession.id, ...PARAGRAPH_ARGUMENTS));
  } finally {
    probeSession.close();
  }

  // Drop the replay backend before installing the scripted canvas model so
  // FontBackendGlobalCollision cannot fire.
  restoreGlobals();
  installScriptedCanvasModelBackend();

  let planB;
  try {
    planB = JSON.parse(precomputePlainParagraph("canvas-model", ...PARAGRAPH_ARGUMENTS));
  } finally {
    restoreGlobals();
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
