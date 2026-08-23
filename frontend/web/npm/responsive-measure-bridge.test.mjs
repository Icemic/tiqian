// Unit tests for the responsive measure engine embedded in the Kotlin runtime
// bundle. The generator embeds npm/core/engine/responsive-measure.js into
// tiqian-web.js; these tests drive the installed globalThis.__TiqianResponsiveMeasure
// copy directly. The runtime installs the bridge lazily on first use, so the
// source is imported here for side-effect installation (the same double
// installation guard the Kotlin bundle relies on).

import assert from "node:assert/strict";
import test from "node:test";
import "./core/engine/responsive-measure.js";
import { loadHostRuntime } from "./runtime-host.mjs";

function rect(width) {
  return { width };
}

function stubElement({ rects = [], fallbackWidth = 0, parentElement = null } = {}) {
  return {
    getClientRects: () => rects,
    getBoundingClientRect: () => ({ width: fallbackWidth }),
    parentElement,
  };
}

function styleStub(values = {}) {
  return {
    paddingLeft: values.paddingLeft ?? "0px",
    paddingRight: values.paddingRight ?? "0px",
    borderLeftWidth: values.borderLeftWidth ?? "0px",
    borderRightWidth: values.borderRightWidth ?? "0px",
    getPropertyValue(name) {
      return this[name] ?? "";
    },
  };
}

function withGetComputedStyle(styleFactory, fn) {
  const real = globalThis.getComputedStyle;
  globalThis.getComputedStyle = () => styleFactory();
  try {
    return fn();
  } finally {
    globalThis.getComputedStyle = real;
  }
}

test("responsiveMeasureBridge_installedByScriptImport", async () => {
  await loadHostRuntime();
  const responsive = globalThis.__TiqianResponsiveMeasure;
  assert.ok(
    responsive,
    "importing responsive-measure.js must install globalThis.__TiqianResponsiveMeasure",
  );
  for (const name of [
    "effectiveLineMeasure",
    "elementContentWidth",
    "sourceParagraphWidth",
    "isCurrentResponsiveMeasure",
  ]) {
    assert.equal(typeof responsive[name], "function", "missing bridge method: " + name);
  }
});

test("responsiveMeasureBridge_effectiveLineMeasureQuantizesToGrid", async () => {
  await loadHostRuntime();
  const responsive = globalThis.__TiqianResponsiveMeasure;
  // 320 / 15.5 -> floor(20.6) = 20 cells -> 20 * 15.5 = 310.
  assert.equal(responsive.effectiveLineMeasure(320, 15.5), 310);
  // Exact division returns the width itself.
  assert.equal(responsive.effectiveLineMeasure(300, 15), 300);
  // InvalidTypographyPreservesCapabilityDiagnosis: non-positive and
  // non-finite font sizes keep the host width untouched.
  assert.equal(responsive.effectiveLineMeasure(200, 0), 200);
  assert.equal(responsive.effectiveLineMeasure(200, -4), 200);
  assert.equal(responsive.effectiveLineMeasure(200, NaN), 200);
  // gridCells is at least 1 and the result is capped at the width: with
  // width < fontSize the effective measure collapses back to the width
  // (coerceAtMost semantics) instead of growing past the available box.
  assert.equal(responsive.effectiveLineMeasure(10, 20), 10);
  assert.equal(responsive.effectiveLineMeasure(15, 20), 15);
});

test("responsiveMeasureBridge_elementContentWidthTakesWidestFragmentMinusChrome", async () => {
  await loadHostRuntime();
  const responsive = globalThis.__TiqianResponsiveMeasure;
  // Three fragments (100/300/200) plus a 600px union bounding box: the
  // widest live fragment wins, then computed padding and borders subtract.
  const element = stubElement({
    rects: [rect(100), rect(300), rect(200)],
    fallbackWidth: 600,
  });
  const result = withGetComputedStyle(
    () => styleStub({
      paddingLeft: "8px",
      paddingRight: "12px",
      borderLeftWidth: "1px",
      borderRightWidth: "2px",
    }),
    () => responsive.elementContentWidth(element),
  );
  assert.equal(result, 277);
});

test("responsiveMeasureBridge_elementContentWidthEmptyRectsFallBackToBoundingBox", async () => {
  await loadHostRuntime();
  const responsive = globalThis.__TiqianResponsiveMeasure;
  const element = stubElement({ rects: [], fallbackWidth: 400 });
  const result = withGetComputedStyle(
    () => styleStub(),
    () => responsive.elementContentWidth(element),
  );
  assert.equal(result, 400);
  assert.equal(responsive.elementContentWidth(null), 0);
});

test("responsiveMeasureBridge_sourceParagraphWidthThreeLevelFallback", async () => {
  await loadHostRuntime();
  const responsive = globalThis.__TiqianResponsiveMeasure;
  // Level one: the paragraph's own content width is positive.
  const own = stubElement({ rects: [rect(320)], fallbackWidth: 320 });
  assert.equal(
    withGetComputedStyle(() => styleStub(), () => responsive.sourceParagraphWidth(own)),
    320,
  );
  // Level two: the paragraph measures zero, so its parent's width wins.
  const parent = stubElement({ rects: [rect(200)], fallbackWidth: 200 });
  const child = stubElement({ rects: [], fallbackWidth: 0, parentElement: parent });
  assert.equal(
    withGetComputedStyle(() => styleStub(), () => responsive.sourceParagraphWidth(child)),
    200,
  );
  // Level three: paragraph and parent both measure zero, so 320 applies.
  const orphan = stubElement({
    rects: [],
    fallbackWidth: 0,
    parentElement: stubElement({ rects: [], fallbackWidth: 0 }),
  });
  assert.equal(
    withGetComputedStyle(() => styleStub(), () => responsive.sourceParagraphWidth(orphan)),
    320,
  );
});

test("responsiveMeasureBridge_isCurrentResponsiveMeasureWithinOneGridCell", async () => {
  await loadHostRuntime();
  const responsive = globalThis.__TiqianResponsiveMeasure;
  // 310 and 311 both quantize to 20 cells at 15.5px: same layout input.
  assert.equal(responsive.isCurrentResponsiveMeasure(310, 311, 15.5), true);
  // 330 crosses into 21 cells: a real measure change.
  assert.equal(responsive.isCurrentResponsiveMeasure(310, 330, 15.5), false);
});