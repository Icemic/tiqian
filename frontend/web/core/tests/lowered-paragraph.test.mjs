import assert from "node:assert/strict";
import test from "node:test";

import {
  isCanonicalPlainParagraph,
  isRuntimeSnapshotPreparedDomEligible,
} from "../core/engine/lowered-paragraph.js";

function textStyle(overrides = {}) {
  return {
    fontFamilies: ["Noto Serif CJK SC"],
    fontSize: 16,
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

test("isCanonicalPlainParagraph classifies the empty wire shape as canonical plain", () => {
  assert.equal(isCanonicalPlainParagraph(paragraph()), true);
});

test("isCanonicalPlainParagraph rejects a non-empty spans collection", () => {
  assert.equal(isCanonicalPlainParagraph(paragraph({ spans: [span()] })), false);
});

test("isCanonicalPlainParagraph rejects a non-empty decorations collection", () => {
  assert.equal(
    isCanonicalPlainParagraph(paragraph({ decorations: [{ start: 0, end: 2, kind: "Emphasis" }] })),
    false,
  );
});

test("isCanonicalPlainParagraph rejects a non-empty inlineBoxes collection", () => {
  assert.equal(
    isCanonicalPlainParagraph(paragraph({ inlineBoxes: [{ start: 0, end: 2, inlineStart: 0, inlineEnd: 4 }] })),
    false,
  );
});

test("isCanonicalPlainParagraph rejects a non-empty inlineObjects collection", () => {
  assert.equal(
    isCanonicalPlainParagraph(paragraph({ inlineObjects: [{ start: 0, end: 2, advance: 4, ascent: 3, descent: 1 }] })),
    false,
  );
});

test("isCanonicalPlainParagraph rejects a non-empty domInlineObjects collection", () => {
  assert.equal(
    isCanonicalPlainParagraph(paragraph({ domInlineObjects: [{ start: 0, end: 2, element: {}, marginRight: 0 }] })),
    false,
  );
});

test("isCanonicalPlainParagraph rejects a non-empty sourceSpans collection", () => {
  assert.equal(
    isCanonicalPlainParagraph(paragraph({
      sourceSpans: [{
        start: 0,
        end: 2,
        element: {},
        depth: 0,
        cjkStrongBaseWeight: null,
        computedColor: null,
        inlineBoxStyle: {
          inlineStart: 0,
          inlineEnd: 0,
          marginRight: 0,
          letterSpacing: 0,
          boxDecorationBreak: "slice",
        },
      }],
    })),
    false,
  );
});

test("isRuntimeSnapshotPreparedDomEligible accepts an empty spans collection", () => {
  assert.equal(isRuntimeSnapshotPreparedDomEligible(paragraph()), true);
});

test("isRuntimeSnapshotPreparedDomEligible accepts spans matching the paragraph locale", () => {
  const lowered = paragraph({
    spans: [
      span({ start: 0, end: 2 }),
      span({ start: 2, end: 4 }),
    ],
  });
  assert.equal(isRuntimeSnapshotPreparedDomEligible(lowered), true);
});

test("isRuntimeSnapshotPreparedDomEligible fails closed on a locale-mismatching span", () => {
  const lowered = paragraph({
    spans: [
      span({ start: 0, end: 2 }),
      span({ start: 2, end: 4, style: textStyle({ locale: "ja" }) }),
    ],
  });
  assert.equal(isRuntimeSnapshotPreparedDomEligible(lowered), false);
});