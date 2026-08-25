import assert from "node:assert/strict";
import test from "node:test";

import {
  preparedCjkStrongSemanticsJson,
  preparedInlineObjectMetaJson,
  preparedSemanticReplayJson,
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

function sourceSpan(overrides = {}) {
  return {
    start: 0,
    end: 2,
    element: { tagName: "SPAN" },
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
    ...overrides,
  };
}

function domInlineObject(overrides = {}) {
  return {
    start: 0,
    end: 1,
    element: { tagName: "IMG" },
    marginRight: 0,
    ...overrides,
  };
}

test("preparedSemanticReplayJson produces [] for empty sourceSpans", () => {
  assert.equal(preparedSemanticReplayJson(paragraph()), "[]");
});

test("preparedSemanticReplayJson lowercases tagNames and assigns sourceIndex and order", () => {
  const lowered = paragraph({
    sourceSpans: [
      sourceSpan({ start: 0, end: 4, element: { tagName: "STRONG" }, depth: 1 }),
      sourceSpan({ start: 4, end: 8, element: { tagName: "EM" }, depth: 2 }),
    ],
  });
  const expected = '[{"start":0,"end":4,"tagName":"strong","sourceIndex":0,"order":1},{"start":4,"end":8,"tagName":"em","sourceIndex":1,"order":2}]';
  assert.equal(preparedSemanticReplayJson(lowered), expected);
});

test("preparedSemanticReplayJson escapes quotes and backslashes in tagName", () => {
  const lowered = paragraph({
    sourceSpans: [
      sourceSpan({ start: 0, end: 2, element: { tagName: 'FOO"BAR\\BAZ' }, depth: 0 }),
    ],
  });
  const expected = '[{"start":0,"end":2,"tagName":"foo\\"bar\\\\baz","sourceIndex":0,"order":0}]';
  assert.equal(preparedSemanticReplayJson(lowered), expected);
});

test("preparedSemanticReplayJson escapes control characters as \\uXXXX hex", () => {
  const lowered = paragraph({
    sourceSpans: [
      sourceSpan({ start: 0, end: 2, element: { tagName: "CUSTOM\u0001TAG" }, depth: 0 }),
    ],
  });
  const expected = '[{"start":0,"end":2,"tagName":"custom\\u0001tag","sourceIndex":0,"order":0}]';
  assert.equal(preparedSemanticReplayJson(lowered), expected);
});

test("preparedSemanticReplayJson escapes standard control sequences", () => {
  const lowered = paragraph({
    sourceSpans: [
      sourceSpan({ start: 0, end: 1, element: { tagName: "A\bB\fC\nD\rE\tF\u001f" }, depth: 0 }),
    ],
  });
  const expected = '[{"start":0,"end":1,"tagName":"a\\bb\\fc\\nd\\re\\tf\\u001f","sourceIndex":0,"order":0}]';
  assert.equal(preparedSemanticReplayJson(lowered), expected);
});

test("preparedSemanticReplayJson matches literal expected string for two elements", () => {
  const lowered = paragraph({
    sourceSpans: [
      sourceSpan({ start: 0, end: 5, element: { tagName: "STRONG" }, depth: 0 }),
      sourceSpan({ start: 5, end: 12, element: { tagName: "EM" }, depth: 1 }),
    ],
  });
  const expected = '[{"start":0,"end":5,"tagName":"strong","sourceIndex":0,"order":0},{"start":5,"end":12,"tagName":"em","sourceIndex":1,"order":1}]';
  assert.equal(preparedSemanticReplayJson(lowered), expected);
});

test("preparedInlineObjectMetaJson produces [] for empty domInlineObjects", () => {
  assert.equal(preparedInlineObjectMetaJson(paragraph()), "[]");
});

test("preparedInlineObjectMetaJson formats marginRight 0 as 0", () => {
  const lowered = paragraph({
    domInlineObjects: [
      domInlineObject({ start: 0, end: 1, marginRight: 0 }),
    ],
  });
  assert.equal(preparedInlineObjectMetaJson(lowered), '[{"start":0,"end":1,"marginRight":0}]');
});

test("preparedInlineObjectMetaJson passes the marginRight wire value through unchanged", () => {
  // The compiled Kotlin/JS Float append is n.toString() with no fround
  // anywhere in the runtime bundle, so 0.1 prints as "0.1".
  const lowered = paragraph({
    domInlineObjects: [
      domInlineObject({ start: 0, end: 1, marginRight: 0.1 }),
    ],
  });
  assert.equal(
    preparedInlineObjectMetaJson(lowered),
    '[{"start":0,"end":1,"marginRight":0.1}]',
  );
});

test("preparedInlineObjectMetaJson matches literal expected string for two elements", () => {
  const lowered = paragraph({
    domInlineObjects: [
      domInlineObject({ start: 2, end: 3, marginRight: 4.5 }),
      domInlineObject({ start: 7, end: 8, marginRight: 0 }),
    ],
  });
  const expected = '[{"start":2,"end":3,"marginRight":4.5},{"start":7,"end":8,"marginRight":0}]';
  assert.equal(preparedInlineObjectMetaJson(lowered), expected);
});

test("preparedCjkStrongSemanticsJson produces [] when all weights are null", () => {
  const lowered = paragraph({
    sourceSpans: [
      sourceSpan({ start: 0, end: 2, cjkStrongBaseWeight: null }),
      sourceSpan({ start: 2, end: 4, cjkStrongBaseWeight: null }),
    ],
  });
  assert.equal(preparedCjkStrongSemanticsJson(lowered), "[]");
});

test("preparedCjkStrongSemanticsJson skips unweighted spans and places commas correctly", () => {
  const lowered = paragraph({
    sourceSpans: [
      sourceSpan({ start: 0, end: 2, cjkStrongBaseWeight: null }),
      sourceSpan({ start: 2, end: 4, cjkStrongBaseWeight: 700 }),
      sourceSpan({ start: 4, end: 6, cjkStrongBaseWeight: null }),
      sourceSpan({ start: 6, end: 8, cjkStrongBaseWeight: 600 }),
      sourceSpan({ start: 8, end: 10, cjkStrongBaseWeight: null }),
    ],
  });
  const expected = '[{"start":2,"end":4,"weight":700},{"start":6,"end":8,"weight":600}]';
  assert.equal(preparedCjkStrongSemanticsJson(lowered), expected);
});

test("preparedCjkStrongSemanticsJson matches literal expected string for two elements", () => {
  const lowered = paragraph({
    sourceSpans: [
      sourceSpan({ start: 0, end: 5, cjkStrongBaseWeight: 700 }),
      sourceSpan({ start: 5, end: 10, cjkStrongBaseWeight: 600 }),
    ],
  });
  const expected = '[{"start":0,"end":5,"weight":700},{"start":5,"end":10,"weight":600}]';
  assert.equal(preparedCjkStrongSemanticsJson(lowered), expected);
});
