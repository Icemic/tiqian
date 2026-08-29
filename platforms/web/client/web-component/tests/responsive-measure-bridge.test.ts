// Unit tests for the responsive measure engine module behind ts-runtime.
// core/src/engine/responsive-measure.js exports the measure helpers as
// plain named functions; these tests drive them directly.

import assert from "node:assert/strict";
import test from "node:test";
import {
  effectiveLineMeasure,
  elementContentWidth,
  sourceParagraphWidth,
  isCurrentResponsiveMeasure,
} from "@tiqian/core/src/engine/responsive-measure.js";
import { probe } from "./runtime-host.js";

interface RectLike {
  width: number;
}

type GetClientRectsFn = () => RectLike[];
type GetBoundingClientRectFn = () => RectLike;

interface StubElement {
  getClientRects: GetClientRectsFn;
  getBoundingClientRect: GetBoundingClientRectFn;
  parentElement: StubElement | null;
}

function rect(width: number): RectLike {
  return { width };
}

interface StubElementOptions {
  rects?: RectLike[];
  fallbackWidth?: number;
  parentElement?: StubElement | null;
}

function stubElement(
  { rects = [], fallbackWidth = 0, parentElement = null }: StubElementOptions = {},
): StubElement {
  return {
    getClientRects: () => rects,
    getBoundingClientRect: () => ({ width: fallbackWidth }),
    parentElement,
  };
}

function styleStub(values: Record<string, string> = {}): CSSStyleDeclaration {
  const styles: Record<string, string> = {
    paddingLeft: values.paddingLeft ?? "0px",
    paddingRight: values.paddingRight ?? "0px",
    borderLeftWidth: values.borderLeftWidth ?? "0px",
    borderRightWidth: values.borderRightWidth ?? "0px",
  };
  return probe<CSSStyleDeclaration>({
    ...styles,
    getPropertyValue(name: string): string {
      return styles[name] ?? "";
    },
  });
}

type ActionFn<T> = () => T;
type StyleFactoryFn = () => CSSStyleDeclaration;

function withGetComputedStyle<T>(styleFactory: StyleFactoryFn, fn: ActionFn<T>): T {
  const real = globalThis.getComputedStyle;
  globalThis.getComputedStyle = (() => styleFactory()) as typeof getComputedStyle;
  try {
    return fn();
  } finally {
    globalThis.getComputedStyle = real;
  }
}

test("responsiveMeasureBridge_exportsFullApiSurface", () => {
  for (const helper of [
    effectiveLineMeasure,
    elementContentWidth,
    sourceParagraphWidth,
    isCurrentResponsiveMeasure,
  ]) {
    assert.equal(typeof helper, "function", "missing responsive measure helper");
  }
});

test("responsiveMeasureBridge_effectiveLineMeasureQuantizesToGrid", () => {
  // 320 / 15.5 -> floor(20.6) = 20 cells -> 20 * 15.5 = 310.
  assert.equal(effectiveLineMeasure(320, 15.5), 310);
  // Exact division returns the width itself.
  assert.equal(effectiveLineMeasure(300, 15), 300);
  // InvalidTypographyPreservesCapabilityDiagnosis: non-positive and
  // non-finite font sizes keep the host width untouched.
  assert.equal(effectiveLineMeasure(200, 0), 200);
  assert.equal(effectiveLineMeasure(200, -4), 200);
  assert.equal(effectiveLineMeasure(200, NaN), 200);
  // gridCells is at least 1 and the result is capped at the width: with
  // width < fontSize the effective measure collapses back to the width
  // (coerceAtMost semantics) instead of growing past the available box.
  assert.equal(effectiveLineMeasure(10, 20), 10);
  assert.equal(effectiveLineMeasure(15, 20), 15);
});

test("responsiveMeasureBridge_elementContentWidthTakesWidestFragmentMinusChrome", () => {
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
    () => elementContentWidth(probe<Element>(element)),
  );
  assert.equal(result, 277);
});

test("responsiveMeasureBridge_elementContentWidthEmptyRectsFallBackToBoundingBox", () => {
  const element = stubElement({ rects: [], fallbackWidth: 400 });
  const result = withGetComputedStyle(
    () => styleStub(),
    () => elementContentWidth(probe<Element>(element)),
  );
  assert.equal(result, 400);
  assert.equal(elementContentWidth(null), 0);
});

test("responsiveMeasureBridge_sourceParagraphWidthThreeLevelFallback", () => {
  // Level one: the paragraph's own content width is positive.
  const own = stubElement({ rects: [rect(320)], fallbackWidth: 320 });
  assert.equal(
    withGetComputedStyle(() => styleStub(), () => sourceParagraphWidth(probe<Element>(own))),
    320,
  );
  // Level two: the paragraph measures zero, so its parent's width wins.
  const parent = stubElement({ rects: [rect(200)], fallbackWidth: 200 });
  const child = stubElement({ rects: [], fallbackWidth: 0, parentElement: parent });
  assert.equal(
    withGetComputedStyle(() => styleStub(), () => sourceParagraphWidth(probe<Element>(child))),
    200,
  );
  // Level three: paragraph and parent both measure zero, so 320 applies.
  const orphan = stubElement({
    rects: [],
    fallbackWidth: 0,
    parentElement: stubElement({ rects: [], fallbackWidth: 0 }),
  });
  assert.equal(
    withGetComputedStyle(() => styleStub(), () => sourceParagraphWidth(probe<Element>(orphan))),
    320,
  );
});

test("responsiveMeasureBridge_isCurrentResponsiveMeasureWithinOneGridCell", () => {
  // 310 and 311 both quantize to 20 cells at 15.5px: same layout input.
  assert.equal(isCurrentResponsiveMeasure(310, 311, 15.5), true);
  // 330 crosses into 21 cells: a real measure change.
  assert.equal(isCurrentResponsiveMeasure(310, 330, 15.5), false);
});
