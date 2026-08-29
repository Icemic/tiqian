import assert from "node:assert/strict";
import test from "node:test";

import type { LoweredParagraph } from "../src/engine/lowered-paragraph.js";
import {
  isCanonicalPlainParagraph,
  isRuntimeSnapshotPreparedDomEligible,
} from "../src/engine/lowered-paragraph.js";

interface TextStyleRecord {
  fontFamilies: string[];
  fontSize: number;
  fontWeight: number;
  italic: boolean;
  baselineShift: number;
  locale: string;
}

interface TextSpanRecord {
  start: number;
  end: number;
  style: TextStyleRecord;
}

interface DecorationRecord {
  start: number;
  end: number;
  kind: string;
}

interface InlineBoxRecord {
  start: number;
  end: number;
  inlineStart: number;
  inlineEnd: number;
}

interface InlineObjectRecord {
  start: number;
  end: number;
  advance: number;
  ascent: number;
  descent: number;
}

interface FakeElementLike {
  tagName?: string;
}

interface DomInlineObjectFake {
  start: number;
  end: number;
  element: FakeElementLike;
  marginRight: number;
}

interface InlineBoxStyleRecord {
  inlineStart: number;
  inlineEnd: number;
  marginRight: number;
  letterSpacing: number;
  boxDecorationBreak: string;
}

interface SourceSpanRecord {
  start: number;
  end: number;
  element: FakeElementLike;
  depth: number;
  cjkStrongBaseWeight: number | null;
  computedColor: string | null;
  inlineBoxStyle: InlineBoxStyleRecord;
}

interface LineBreakSpanRecord {
  start: number;
  end: number;
  policy: string;
}

type LoweredParagraphWire = {
  text: string;
  textStyle: TextStyleRecord;
  lineHeight: number;
  spans: TextSpanRecord[];
  decorations: DecorationRecord[];
  inlineBoxes: InlineBoxRecord[];
  inlineObjects: InlineObjectRecord[];
  domInlineObjects: DomInlineObjectFake[];
  sourceSpans: SourceSpanRecord[];
  sourceBoundaries: number[];
  lineBreakSpans: LineBreakSpanRecord[];
};

function makeLowered(overrides: Partial<LoweredParagraphWire>): LoweredParagraph {
  const wire: LoweredParagraphWire = {
    text: "你好",
    textStyle: {
      fontFamilies: ["Noto Serif CJK SC"],
      fontSize: 16,
      fontWeight: 400,
      italic: false,
      baselineShift: 0,
      locale: "zh-Hans",
    },
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
  return wire as LoweredParagraph;
}

function makeTextSpan(overrides: Partial<TextSpanRecord>): TextSpanRecord {
  return {
    start: 0,
    end: 2,
    style: {
      fontFamilies: ["Noto Serif CJK SC"],
      fontSize: 16,
      fontWeight: 400,
      italic: false,
      baselineShift: 0,
      locale: "zh-Hans",
    },
    ...overrides,
  };
}

test("isCanonicalPlainParagraph classifies the empty wire shape as canonical plain", () => {
  assert.equal(isCanonicalPlainParagraph(makeLowered({})), true);
});

test("isCanonicalPlainParagraph rejects a non-empty spans collection", () => {
  assert.equal(isCanonicalPlainParagraph(makeLowered({ spans: [makeTextSpan({})] })), false);
});

test("isCanonicalPlainParagraph rejects a non-empty decorations collection", () => {
  assert.equal(
    isCanonicalPlainParagraph(makeLowered({ decorations: [{ start: 0, end: 2, kind: "Emphasis" }] })),
    false,
  );
});

test("isCanonicalPlainParagraph rejects a non-empty inlineBoxes collection", () => {
  assert.equal(
    isCanonicalPlainParagraph(makeLowered({ inlineBoxes: [{ start: 0, end: 2, inlineStart: 0, inlineEnd: 4 }] })),
    false,
  );
});

test("isCanonicalPlainParagraph rejects a non-empty inlineObjects collection", () => {
  assert.equal(
    isCanonicalPlainParagraph(makeLowered({ inlineObjects: [{ start: 0, end: 2, advance: 4, ascent: 3, descent: 1 }] })),
    false,
  );
});

test("isCanonicalPlainParagraph rejects a non-empty domInlineObjects collection", () => {
  assert.equal(
    isCanonicalPlainParagraph(makeLowered({ domInlineObjects: [{ start: 0, end: 2, element: {}, marginRight: 0 }] })),
    false,
  );
});

test("isCanonicalPlainParagraph rejects a non-empty sourceSpans collection", () => {
  assert.equal(
    isCanonicalPlainParagraph(makeLowered({
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
  assert.equal(isRuntimeSnapshotPreparedDomEligible(makeLowered({})), true);
});

test("isRuntimeSnapshotPreparedDomEligible accepts spans matching the paragraph locale", () => {
  const lowered = makeLowered({
    spans: [
      makeTextSpan({ start: 0, end: 2 }),
      makeTextSpan({ start: 2, end: 4 }),
    ],
  });
  assert.equal(isRuntimeSnapshotPreparedDomEligible(lowered), true);
});

test("isRuntimeSnapshotPreparedDomEligible fails closed on a locale-mismatching span", () => {
  const lowered = makeLowered({
    spans: [
      makeTextSpan({ start: 0, end: 2 }),
      makeTextSpan({ start: 2, end: 4, style: {
        fontFamilies: ["Noto Serif CJK SC"],
        fontSize: 16,
        fontWeight: 400,
        italic: false,
        baselineShift: 0,
        locale: "ja",
      } }),
    ],
  });
  assert.equal(isRuntimeSnapshotPreparedDomEligible(lowered), false);
});
