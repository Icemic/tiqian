import assert from "node:assert/strict";
import test from "node:test";

import type { LoweredParagraph } from "../core/engine/lowered-paragraph.js";
import {
  preparedSemanticReplayJson,
  preparedInlineObjectMetaJson,
  preparedCjkStrongSemanticsJson,
} from "../core/engine/prepared-metadata.js";

interface ElementRecord {
  tagName: string;
}

interface SourceSpanRecord {
  start: number;
  end: number;
  element: ElementRecord;
  depth?: number;
  cjkStrongBaseWeight?: number | null;
}

interface DomInlineObjectRecord {
  start: number;
  end: number;
  marginRight: number;
}

interface LoweredParagraphRecord {
  sourceSpans: SourceSpanRecord[];
  domInlineObjects: DomInlineObjectRecord[];
}

test("1. Empty lowered yields '[]' for all three metadata builders", () => {
  const lowered: LoweredParagraphRecord = {
    sourceSpans: [],
    domInlineObjects: [],
  };

  assert.equal(preparedSemanticReplayJson(lowered as LoweredParagraph), "[]");
  assert.equal(preparedInlineObjectMetaJson(lowered as LoweredParagraph), "[]");
  assert.equal(preparedCjkStrongSemanticsJson(lowered as LoweredParagraph), "[]");
});

test("2. preparedSemanticReplayJson: sourceSpan pairs yield exact JSON string with lowercased tag names and depth as order", () => {
  const lowered: LoweredParagraphRecord = {
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
    domInlineObjects: [],
  };

  const expected: string =
    '[{"start":0,"end":5,"tagName":"em","sourceIndex":0,"order":1},' +
    '{"start":5,"end":10,"tagName":"strong","sourceIndex":1,"order":2}]';

  assert.equal(preparedSemanticReplayJson(lowered as LoweredParagraph), expected);
});

test("3. preparedInlineObjectMetaJson: domInlineObjects yield exact JSON string", () => {
  const lowered: LoweredParagraphRecord = {
    domInlineObjects: [
      { start: 2, end: 3, marginRight: 8.5 },
      { start: 7, end: 8, marginRight: 0 },
    ],
    sourceSpans: [],
  };

  const expected: string =
    '[{"start":2,"end":3,"marginRight":8.5},' +
    '{"start":7,"end":8,"marginRight":0}]';

  assert.equal(preparedInlineObjectMetaJson(lowered as LoweredParagraph), expected);
});

test("4. preparedCjkStrongSemanticsJson: spans without cjkStrongBaseWeight are skipped while weighted spans are emitted", () => {
  const lowered: LoweredParagraphRecord = {
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
    domInlineObjects: [],
  };

  const expected: string =
    '[{"start":3,"end":7,"weight":700},' +
    '{"start":12,"end":15,"weight":900}]';

  assert.equal(preparedCjkStrongSemanticsJson(lowered as LoweredParagraph), expected);
});
