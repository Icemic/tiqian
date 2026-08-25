import assert from "node:assert/strict";
import test from "node:test";

import {
  preparedSemanticReplayJson,
  preparedInlineObjectMetaJson,
  preparedCjkStrongSemanticsJson,
} from "../core/engine/prepared-metadata.js";

test("1. Empty lowered yields '[]' for all three metadata builders", () => {
  const lowered = {
    sourceSpans: [],
    domInlineObjects: [],
  };

  assert.equal(preparedSemanticReplayJson(lowered), "[]");
  assert.equal(preparedInlineObjectMetaJson(lowered), "[]");
  assert.equal(preparedCjkStrongSemanticsJson(lowered), "[]");
});

test("2. preparedSemanticReplayJson: sourceSpan pairs yield exact JSON string with lowercased tag names and depth as order", () => {
  const lowered = {
    sourceSpans: [
      {
        start: 0,
        end: 5,
        element: { tagName: "EM" },
        depth: 1,
      },
      {
        start: 5,
        end: 10,
        element: { tagName: "STRONG" },
        depth: 2,
      },
    ],
  };

  const expected =
    '[{"start":0,"end":5,"tagName":"em","sourceIndex":0,"order":1},' +
    '{"start":5,"end":10,"tagName":"strong","sourceIndex":1,"order":2}]';

  assert.equal(preparedSemanticReplayJson(lowered), expected);
});

test("3. preparedInlineObjectMetaJson: domInlineObjects yield exact JSON string", () => {
  const lowered = {
    domInlineObjects: [
      { start: 2, end: 3, marginRight: 8.5 },
      { start: 7, end: 8, marginRight: 0 },
    ],
  };

  const expected =
    '[{"start":2,"end":3,"marginRight":8.5},' +
    '{"start":7,"end":8,"marginRight":0}]';

  assert.equal(preparedInlineObjectMetaJson(lowered), expected);
});

test("4. preparedCjkStrongSemanticsJson: spans without cjkStrongBaseWeight are skipped while weighted spans are emitted", () => {
  const lowered = {
    sourceSpans: [
      {
        start: 0,
        end: 3,
        element: { tagName: "EM" },
        depth: 0,
      },
      {
        start: 3,
        end: 7,
        cjkStrongBaseWeight: 700,
        element: { tagName: "STRONG" },
        depth: 1,
      },
      {
        start: 7,
        end: 12,
        element: { tagName: "SPAN" },
        depth: 0,
      },
      {
        start: 12,
        end: 15,
        cjkStrongBaseWeight: 900,
        element: { tagName: "STRONG" },
        depth: 1,
      },
    ],
  };

  const expected =
    '[{"start":3,"end":7,"weight":700},' +
    '{"start":12,"end":15,"weight":900}]';

  assert.equal(preparedCjkStrongSemanticsJson(lowered), expected);
});