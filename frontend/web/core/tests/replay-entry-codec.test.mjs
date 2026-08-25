import assert from "node:assert/strict";
import test from "node:test";

import {
  decodeMetricReplayRow,
  decodeShapeReplayRow,
  normalizeReplayNumber,
  scaleMetricReplayItem,
  scaleShapeReplayItem,
} from "../core/measurement/replay-entry-codec.js";
import { metricReplayKey, shapeReplayKey } from "../core/sampler/snapshot/snapshot-schema.js";

const strings = [
  "你好",
  "A\u001fB",
  "zh-Hans",
  "CjkText",
  "fixture-face",
  "fixture-instance",
  "Hani",
  "lnum",
];
const stringAt = (ref) => strings[ref];

function throwsFieldError(fn, field) {
  assert.throws(fn, (error) => error instanceof Error &&
    error.message === `InvalidServerShapingReplay:${field}`);
}

function throwsSnapshotError(fn, name) {
  assert.throws(fn, (error) => error instanceof Error && error.message === name);
}

function shapeItem(overrides = {}) {
  return {
    result: {
      faceId: "fixture-face",
      fontInstanceId: "fixture-instance",
      script: "Hani",
      features: ["lnum"],
      unsafeBreakCount: 2,
      advanceEm: 0.5,
      glyphs: [{ id: 3, advanceEm: 0.25, xEm: 0.125, yEm: -0.0625, boundsEm: [0, 0, 0.25, 0.125] }],
      ...overrides,
    },
  };
}

function glyph(overrides = {}) {
  return {
    id: 3,
    advanceEm: 0.25,
    xEm: 0.125,
    yEm: -0.0625,
    boundsEm: [0, 0, 0.25, 0.125],
    ...overrides,
  };
}

test("normalizeReplayNumber mirrors replay.rs normalized_replay_number vectors", () => {
  assert.equal(normalizeReplayNumber(16, 16), 1);
  assert.equal(normalizeReplayNumber(15.5, 15.5), 1);
  assert.equal(normalizeReplayNumber(3.3000000000000003, 3.3), 1);
  assert.equal(normalizeReplayNumber(-0, 16), 0);
  assert.equal(normalizeReplayNumber(Number.NaN, 16), null);
  assert.equal(normalizeReplayNumber(Infinity, 16), null);
  assert.equal(normalizeReplayNumber(-Infinity, 16), null);
  assert.equal(normalizeReplayNumber(1e-13 * 16, 16), 0);
});

test("normalizeReplayNumber -0 input maps to positive zero", () => {
  const em = normalizeReplayNumber(-0, 16);
  assert.equal(em, 0);
  assert.ok(Object.is(em, 0));
});

test("shape roundtrip stores px as em and scales back to the same px", () => {
  const px = 16;
  const fontSize = 32;
  const em = normalizeReplayNumber(px, fontSize);
  assert.equal(em, 0.5);
  const item = shapeItem({
    advanceEm: em,
    glyphs: [{ id: 1, advanceEm: em, xEm: em, yEm: em, boundsEm: [em, em, em, em] }],
  });
  const scaled = scaleShapeReplayItem(item, fontSize);
  assert.equal(scaled.advance, px);
  assert.equal(scaled.glyphs[0].advance, px);
  assert.equal(scaled.glyphs[0].x, px);
  assert.equal(scaled.glyphs[0].y, px);
  assert.deepEqual(scaled.glyphs[0].bounds, [px, px, px, px]);
});

test("metric roundtrip stores px as em and scales back to the same px", () => {
  const em = normalizeReplayNumber(3.3, 3.3);
  assert.equal(em, 1);
  const scaled = scaleMetricReplayItem({ valuesEm: [em, em, em, em, em] }, 3.3);
  assert.deepEqual(scaled, [3.3, 3.3, 3.3, 3.3, 3.3]);
});

test("sub-12-decimal px truncates to zero em and reads back zero", () => {
  const em = normalizeReplayNumber(1e-13 * 16, 16);
  assert.equal(em, 0);
  const scaled = scaleMetricReplayItem({ valuesEm: [em, em, em, em, em] }, 16);
  assert.deepEqual(scaled, [0, 0, 0, 0, 0]);
});

test("decodeShapeReplayRow expands a normal null-bounds empty-features row", () => {
  const row = [0, 1, 400, 0, 2, 3, 0, 4, 5, 6, [], 0, 1, [1, 1, 0, 0, null, null, null, null]];
  const decoded = decodeShapeReplayRow(row, stringAt);
  assert.deepEqual(decoded.key, shapeReplayKey(
    "你好",
    "A\u001fB",
    400,
    false,
    "zh-Hans",
    "CjkText",
    "你好",
  ));
  assert.deepEqual(decoded.result, {
    faceId: "fixture-face",
    fontInstanceId: "fixture-instance",
    script: "Hani",
    features: [],
    unsafeBreakCount: 0,
    advanceEm: 1,
    glyphs: [{ id: 1, advanceEm: 1, xEm: 0, yEm: 0, boundsEm: null }],
  });
});

test("decodeShapeReplayRow expands a normal row with features and non-null bounds", () => {
  const row = [0, 1, 700, 1, 2, 3, 0, 4, 5, 6, [7], 2, 0.5,
    [2, 0.25, 0.125, -0.5, 0, 0.1, 0.2, 0.3]];
  const decoded = decodeShapeReplayRow(row, stringAt);
  assert.deepEqual(decoded.key, shapeReplayKey(
    "你好",
    "A\u001fB",
    700,
    true,
    "zh-Hans",
    "CjkText",
    "你好",
  ));
  assert.deepEqual(decoded.result, {
    faceId: "fixture-face",
    fontInstanceId: "fixture-instance",
    script: "Hani",
    features: ["lnum"],
    unsafeBreakCount: 2,
    advanceEm: 0.5,
    glyphs: [{ id: 2, advanceEm: 0.25, xEm: 0.125, yEm: -0.5, boundsEm: [0, 0.1, 0.2, 0.3] }],
  });
});

test("decodeShapeReplayRow rejects damaged transport rows", () => {
  const refusingStringAt = () => {
    throw new Error("stringAt must not run on invalid rows");
  };
  const transportRow = [0, 1, 400, 0, 2, 3, 0, 4, 5, 6, [], 0, 1,
    [1, 1, 0, 0, null, null, null, null]];
  throwsSnapshotError(
    () => decodeShapeReplayRow("not-a-row", refusingStringAt),
    "SnapshotFontReplayShapeTransportInvalid",
  );
  throwsSnapshotError(
    () => decodeShapeReplayRow([0, 1], refusingStringAt),
    "SnapshotFontReplayShapeTransportInvalid",
  );
  const nonArrayFeatures = [...transportRow];
  nonArrayFeatures[10] = "not-an-array";
  throwsSnapshotError(
    () => decodeShapeReplayRow(nonArrayFeatures, refusingStringAt),
    "SnapshotFontReplayShapeTransportInvalid",
  );
  const shortGlyphs = [...transportRow];
  shortGlyphs[13] = [1, 1, 0, 0];
  throwsSnapshotError(
    () => decodeShapeReplayRow(shortGlyphs, refusingStringAt),
    "SnapshotFontReplayShapeTransportInvalid",
  );
  const badItalic = [...transportRow];
  badItalic[3] = 2;
  throwsSnapshotError(
    () => decodeShapeReplayRow(badItalic, refusingStringAt),
    "SnapshotFontReplayShapeTransportInvalid",
  );
});

test("decodeShapeReplayRow rejects partially-null glyph bounds", () => {
  const row = [0, 1, 400, 0, 2, 3, 0, 4, 5, 6, [], 0, 1, [1, 1, 0, 0, 1, 1, 1, null]];
  throwsSnapshotError(
    () => decodeShapeReplayRow(row, () => {
      throw new Error("stringAt must not run on invalid rows");
    }),
    "SnapshotFontReplayGlyphBoundsInvalid",
  );
});

test("scaleShapeReplayItem scales a normal entry exactly", () => {
  const scaled = scaleShapeReplayItem(shapeItem(), 16);
  assert.equal(scaled.faceId, "fixture-face");
  assert.equal(scaled.fontInstanceId, "fixture-instance");
  assert.equal(scaled.script, "Hani");
  assert.deepEqual(scaled.features, ["lnum"]);
  assert.equal(scaled.unsafeBreakCount, 2);
  assert.equal(scaled.advance, 8);
  assert.equal(scaled.glyphs.length, 1);
  assert.deepEqual(scaled.glyphs[0], { id: 3, advance: 4, x: 2, y: -1, bounds: [0, 0, 4, 2] });
});

test("scaleShapeReplayItem names glyph field failures", () => {
  throwsFieldError(() => scaleShapeReplayItem(shapeItem({ glyphs: [glyph({ id: -1 })] }), 16), "glyph");
  throwsFieldError(() => scaleShapeReplayItem(shapeItem({ glyphs: [glyph({ id: 1.5 })] }), 16), "glyph");
  throwsFieldError(() => scaleShapeReplayItem(shapeItem({ glyphs: [glyph({ advanceEm: NaN })] }), 16), "glyph-advance");
  throwsFieldError(() => scaleShapeReplayItem(shapeItem({ glyphs: [glyph({ xEm: Infinity })] }), 16), "glyph-x");
  throwsFieldError(() => scaleShapeReplayItem(shapeItem({ glyphs: [glyph({ yEm: -Infinity })] }), 16), "glyph-y");
  throwsFieldError(() => scaleShapeReplayItem(shapeItem({ glyphs: [glyph({ boundsEm: [0, NaN, 0, 0] })] }), 16), "glyph-bound-1");
  throwsFieldError(() => scaleShapeReplayItem(shapeItem({ glyphs: [glyph({ boundsEm: [0, 0, 0] })] }), 16), "glyph-bounds");
});

test("scaleShapeReplayItem names shape-result, features, unsafe-break-count and shape-advance failures", () => {
  throwsFieldError(() => scaleShapeReplayItem({}, 16), "shape-result");
  throwsFieldError(() => scaleShapeReplayItem({ result: { glyphs: {} } }, 16), "shape-result");
  throwsFieldError(() => scaleShapeReplayItem(shapeItem({ features: [5] }), 16), "features");
  throwsFieldError(() => scaleShapeReplayItem(shapeItem({ unsafeBreakCount: -1 }), 16), "unsafe-break-count");
  throwsFieldError(() => scaleShapeReplayItem(shapeItem({ unsafeBreakCount: 1.5 }), 16), "unsafe-break-count");
  throwsFieldError(() => scaleShapeReplayItem(shapeItem({ advanceEm: NaN }), 16), "shape-advance");
});

test("scaleShapeReplayItem rejects non-positive or non-number font sizes", () => {
  throwsFieldError(() => scaleShapeReplayItem(shapeItem(), 0), "font-size");
  throwsFieldError(() => scaleShapeReplayItem(shapeItem(), -16), "font-size");
  throwsFieldError(() => scaleShapeReplayItem(shapeItem(), Number.NaN), "font-size");
  throwsFieldError(() => scaleShapeReplayItem(shapeItem(), "abc"), "font-size");
});

test("scaleMetricReplayItem scales a normal five-tuple exactly", () => {
  const scaled = scaleMetricReplayItem({ valuesEm: [0.5, 0.25, 0.125, 0.75, -0.5] }, 16);
  assert.deepEqual(scaled, [8, 4, 2, 12, -8]);
});

test("scaleMetricReplayItem passes null values through as NaN", () => {
  const scaled = scaleMetricReplayItem({ valuesEm: [1, null, 3, null, 5] }, 2);
  assert.deepEqual(scaled, [2, NaN, 6, NaN, 10]);
});

test("scaleMetricReplayItem names metrics-result, metric-index and font-size failures", () => {
  throwsFieldError(() => scaleMetricReplayItem({ valuesEm: [1, 2] }), "metrics-result");
  throwsFieldError(() => scaleMetricReplayItem({}), "metrics-result");
  throwsFieldError(() => scaleMetricReplayItem({ valuesEm: [1, NaN, 3, 4, 5] }, 16), "metric-1");
  throwsFieldError(() => scaleMetricReplayItem({ valuesEm: [1, 2, 3, 4, Infinity] }, 16), "metric-4");
  throwsFieldError(() => scaleMetricReplayItem({ valuesEm: [1, 2, 3, 4, 5] }, 0), "font-size");
});

test("decodeMetricReplayRow mirrors table rows without validation", () => {
  const row = {
    serializedFamilies: "A\u001fB",
    fontWeight: 400,
    italic: false,
    role: "CjkText",
    faceSelectionText: "fixture-face",
    valuesEm: [0.5, 0.25, 0.125, 0.75, -0.5],
  };
  const decoded = decodeMetricReplayRow(row);
  assert.deepEqual(decoded.key, metricReplayKey("A\u001fB", 400, false, "CjkText", "fixture-face"));
  assert.equal(decoded.valuesEm, row.valuesEm);
});

test("decodeMetricReplayRow keeps sparse rows non-throwing like replayMetricsOf", () => {
  const sparse = { valuesEm: [1, 2, 3, 4, 5] };
  const decoded = decodeMetricReplayRow(sparse);
  assert.equal(decoded.valuesEm, sparse.valuesEm);
  assert.equal(typeof decoded.key, "string");
  const empty = decodeMetricReplayRow({});
  assert.equal(empty.valuesEm, undefined);
  assert.equal(typeof empty.key, "string");
});