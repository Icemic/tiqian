import assert from "node:assert/strict";
import test from "node:test";

import {
  normalizeLiveSemantics,
  normalizeSnapshotSemantics,
  SnapshotSemanticError,
  snapshotSemanticMetricContractIssue,
  snapshotSourceArtifactString,
} from "../core/sampler/snapshot/snapshot-source.js";

interface SemanticSpanInput {
  start?: unknown;
  end?: unknown;
  tagName?: unknown;
  attributes?: unknown;
}

test("snapshot source semantics are nested deterministically and include behavior attributes", () => {
  const semantics = normalizeSnapshotSemantics("前链接后", [{
    start: 1,
    end: 3,
    tagName: "a",
    attributes: { title: "入口", href: "/first" },
  }, {
    start: 1,
    end: 3,
    tagName: "strong",
    attributes: {},
  }]);

  const tagNames = semantics.map((span) => span.tagName);
  assert.deepEqual(tagNames, ["a", "strong"]);
  assert.notEqual(
    snapshotSourceArtifactString("前链接后", [{
      start: 1,
      end: 3,
      tagName: "a",
      attributes: { title: "入口", href: "/second" },
    }, {
      start: 1,
      end: 3,
      tagName: "strong",
      attributes: {},
    }]),
    snapshotSourceArtifactString("前链接后", [{
      start: 1,
      end: 3,
      tagName: "a",
      attributes: { title: "入口", href: "/first" },
    }, {
      start: 1,
      end: 3,
      tagName: "strong",
      attributes: {},
    }]),
  );
});

test("snapshot semantics reject crossing ranges and active content attributes", () => {
  assert.throws(() => normalizeSnapshotSemantics("中文正文", [
    { start: 0, end: 3, tagName: "a", attributes: { href: "/a" } },
    { start: 2, end: 4, tagName: "em", attributes: {} },
  ]), /CrossingSnapshotSemanticRanges/u);
  assert.throws(() => normalizeSnapshotSemantics("链接", [
    { start: 0, end: 2, tagName: "a", attributes: { onclick: "alert(1)" } },
  ]), /UnsupportedSnapshotSemanticAttribute/u);
});

test("live semantics validate structure without serializing host tags or attributes", () => {
  let snapshotError: SnapshotSemanticError | undefined;
  try {
    normalizeSnapshotSemantics("前秘密后", [{
      start: 1,
      end: 3,
      tagName: "spoiler",
      attributes: { style: "padding:4px", onclick: "reveal()" },
    }]);
  } catch (error) {
    if (!(error instanceof SnapshotSemanticError)) throw new Error("Expected SnapshotSemanticError");
    snapshotError = error;
  }
  if (snapshotError == null) throw new Error("Expected error to be thrown");
  assert.ok(snapshotError instanceof SnapshotSemanticError);
  assert.equal(snapshotError.code, "UnsupportedSnapshotSemanticTag");
  assert.equal(snapshotError.detail, "spoiler");

  const liveResult = normalizeLiveSemantics("前秘密后", [{
    start: 1,
    end: 3,
    tagName: "spoiler",
    attributes: { style: "padding:4px", onclick: "reveal()" },
  }]);
  assert.deepEqual(liveResult, [{
    start: 1,
    end: 3,
    tagName: "spoiler",
    sourceIndex: 0,
  }]);
  assert.throws(() => normalizeLiveSemantics("中文正文", [
    { start: 0, end: 3, tagName: "spoiler" },
    { start: 2, end: 4, tagName: "span" },
  ]), /CrossingSnapshotSemanticRanges/u);
});

test("live semantics keep hierarchy order separate from live source indices", () => {
  const result = normalizeLiveSemantics("秘密", [{
    start: 0,
    end: 2,
    tagName: "em",
    sourceIndex: 0,
    order: 1,
  }, {
    start: 0,
    end: 2,
    tagName: "spoiler",
    sourceIndex: 1,
    order: 0,
  }]);
  assert.deepEqual(result, [{
    start: 0,
    end: 2,
    tagName: "spoiler",
    sourceIndex: 1,
  }, {
    start: 0,
    end: 2,
    tagName: "em",
    sourceIndex: 0,
  }]);
});

interface TextSpan {
  start: number;
  end: number;
  fontFamilies?: string[];
  fontSizePx?: number;
  fontWeight?: number;
  italic?: boolean;
  baselineShiftPx?: number;
}

interface InlineBox {
  start: number;
  end: number;
  inlineStartPx?: number;
  inlineEndPx?: number;
}

test("inline code requires an explicit snapshot-font and box metric contract", () => {
  const semantics = normalizeSnapshotSemantics("中code文", [{
    start: 1,
    end: 5,
    tagName: "code",
    attributes: {},
  }]);

  assert.equal(
    snapshotSemanticMetricContractIssue(Array.from(semantics), [], []),
    "InlineCodeFontContractUnavailable",
  );
  const textSpans: TextSpan[] = [{
    start: 1,
    end: 5,
    fontFamilies: ["Host Exact Mono"],
    fontSizePx: 14,
    fontWeight: 400,
    italic: false,
    baselineShiftPx: 0,
  }];
  assert.equal(
    snapshotSemanticMetricContractIssue(Array.from(semantics), textSpans, []),
    "InlineCodeBoxContractUnavailable",
  );
  const inlineBoxes: InlineBox[] = [{
    start: 1,
    end: 5,
    inlineStartPx: 5.6,
    inlineEndPx: 5.6,
  }];
  assert.equal(snapshotSemanticMetricContractIssue(Array.from(semantics), textSpans, inlineBoxes), null);
});
