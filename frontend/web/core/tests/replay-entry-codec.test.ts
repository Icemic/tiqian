import assert from "node:assert/strict";
import test from "node:test";

import {
  decodeMetricReplayRow,
  decodeShapeReplayRow,
  normalizeReplayNumber,
  scaleMetricReplayItem,
  scaleShapeReplayItem,
} from "../core/measurement/replay-entry-codec.js";
import type { ReplayShapeGlyph, ReplayShapeItem, ReplayMetricItem } from "../core/measurement/replay-entry-codec.js";
import type { ShapeReplayWireRow } from "../core/measurement/replay-entry-codec.js";
import type { SnapshotMetricRow } from "../core/sampler/snapshot/snapshot-table-binary.js";
import { metricReplayKey, shapeReplayKey } from "../core/sampler/snapshot/snapshot-schema.js";

type StringAt = (ref: number) => string;

function createShapeWireRow(
  col0: number, col1: number, col2: number, col3: number,
  col4: number, col5: number, col6: number, col7: number,
  col8: number, col9: number, col10: number[], col11: number,
  col12: number, col13Data: Array<number | null>,
): ShapeReplayWireRow {
  // Wire format accepts nulls in glyph bounds; type definition uses number[]
  const result = Object.assign([], {
    0: col0, 1: col1, 2: col2, 3: col3,
    4: col4, 5: col5, 6: col6, 7: col7,
    8: col8, 9: col9, 10: col10, 11: col11,
    12: col12, 13: col13Data,
    length: 14,
  });
  return result as ShapeReplayWireRow;
}

const strings: readonly string[] = [
  "你好",
  "A\u001fB",
  "zh-Hans",
  "CjkText",
  "fixture-face",
  "fixture-instance",
  "Hani",
  "lnum",
];

const stringAt: StringAt = (ref: number): string => strings[ref];

interface ThrowsFieldErrorFn {
  (): void;
}

function throwsFieldError(fn: ThrowsFieldErrorFn, field: string): void {
  assert.throws(
    fn,
    (error: unknown): boolean => error instanceof Error && error.message === `InvalidServerShapingReplay:${field}`,
  );
}

interface ThrowsSnapshotErrorFn {
  (): void;
}

function throwsSnapshotError(fn: ThrowsSnapshotErrorFn, name: string): void {
  assert.throws(
    fn,
    (error: unknown): boolean => error instanceof Error && error.message === name,
  );
}

interface ShapeItemOverrides {
  key?: string;
  faceId?: string;
  fontInstanceId?: string;
  script?: string;
  features?: readonly string[];
  unsafeBreakCount?: number;
  advanceEm?: number;
  glyphs?: readonly ReplayShapeGlyph[];
}

interface ShapeResultFields {
  faceId: string;
  fontInstanceId: string;
  script: string;
  features: readonly string[];
  unsafeBreakCount: number;
  advanceEm: number;
  glyphs: readonly ReplayShapeGlyph[];
}

interface ShapeItemResult {
  key: string;
  result: ShapeResultFields;
}

function shapeItem(overrides: ShapeItemOverrides = {}): ShapeItemResult {
  return {
    key: overrides.key ?? "test-key",
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

interface GlyphOverrides {
  id?: number;
  advanceEm?: number;
  xEm?: number;
  yEm?: number;
  boundsEm?: readonly number[] | null;
}

function glyph(overrides: GlyphOverrides = {}): ReplayShapeGlyph {
  const result: ReplayShapeGlyph = {
    id: 3,
    advanceEm: 0.25,
    xEm: 0.125,
    yEm: -0.0625,
    boundsEm: [0, 0, 0.25, 0.125],
  };
  if (overrides.id !== undefined) result.id = overrides.id;
  if (overrides.advanceEm !== undefined) result.advanceEm = overrides.advanceEm;
  if (overrides.xEm !== undefined) result.xEm = overrides.xEm;
  if (overrides.yEm !== undefined) result.yEm = overrides.yEm;
  if (overrides.boundsEm !== undefined) result.boundsEm = overrides.boundsEm as number[] | null;
  return result;
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
  const em: number | null = normalizeReplayNumber(-0, 16);
  assert.equal(em, 0);
  assert.ok(Object.is(em, 0));
});

test("shape roundtrip stores px as em and scales back to the same px", () => {
  const px: number = 16;
  const fontSize: number = 32;
  const em: number | null = normalizeReplayNumber(px, fontSize);
  assert.equal(em, 0.5);
  if (em == null) {
    throw new Error("Expected non-null em value");
  }
  const item: ShapeItemResult = shapeItem({
    advanceEm: em,
    glyphs: [{ id: 1, advanceEm: em, xEm: em, yEm: em, boundsEm: [em, em, em, em] }],
  });
  const scaled = scaleShapeReplayItem(item as ReplayShapeItem, fontSize);
  assert.equal(scaled.advance, px);
  assert.equal(scaled.glyphs[0].advance, px);
  assert.equal(scaled.glyphs[0].x, px);
  assert.equal(scaled.glyphs[0].y, px);
  assert.deepEqual(scaled.glyphs[0].bounds, [px, px, px, px]);
});

test("metric roundtrip stores px as em and scales back to the same px", () => {
  const em: number | null = normalizeReplayNumber(3.3, 3.3);
  assert.equal(em, 1);
  if (em == null) {
    throw new Error("Expected non-null em value");
  }
  const item: MetricItemResult = metricItem({ valuesEm: [em, em, em, em, em] });
  const scaled: number[] = scaleMetricReplayItem(item as ReplayMetricItem, 3.3);
  assert.deepEqual(scaled, [3.3, 3.3, 3.3, 3.3, 3.3]);
});

test("sub-12-decimal px truncates to zero em and reads back zero", () => {
  const em: number | null = normalizeReplayNumber(1e-13 * 16, 16);
  assert.equal(em, 0);
  if (em == null) {
    throw new Error("Expected non-null em value");
  }
  const item: MetricItemResult = metricItem({ valuesEm: [em, em, em, em, em] });
  const scaled: number[] = scaleMetricReplayItem(item as ReplayMetricItem, 16);
  assert.deepEqual(scaled, [0, 0, 0, 0, 0]);
});

test("decodeShapeReplayRow expands a normal null-bounds empty-features row", () => {
  // Wire format allows null in bounds positions; type definition doesn't reflect this
  const glyphDataWithNulls: Array<number | null> = [1, 1, 0, 0, null, null, null, null];
  const row: ShapeReplayWireRow = createShapeWireRow(0, 1, 400, 0, 2, 3, 0, 4, 5, 6, [], 0, 1, glyphDataWithNulls);
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
  const row: ShapeReplayWireRow = [0, 1, 700, 1, 2, 3, 0, 4, 5, 6, [7], 2, 0.5,
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

interface DamagedRowString {
  value: unknown;
}

interface DamagedRowShortArray {
  value: readonly number[];
}

function assertShapeReplayWireRow(value: unknown): ShapeReplayWireRow {
  return value as ShapeReplayWireRow;
}

test("decodeShapeReplayRow rejects damaged transport rows", () => {
  const refusingStringAt: StringAt = () => {
    throw new Error("stringAt must not run on invalid rows");
  };
  const glyphDataWithNulls: Array<number | null> = [1, 1, 0, 0, null, null, null, null];
  const transportRowValues: unknown[] = [0, 1, 400, 0, 2, 3, 0, 4, 5, 6, [], 0, 1, glyphDataWithNulls];
  const stringInput: DamagedRowString = { value: "not-a-row" };
  throwsSnapshotError(
    () => decodeShapeReplayRow(assertShapeReplayWireRow(stringInput.value), refusingStringAt),
    "SnapshotFontReplayShapeTransportInvalid",
  );
  const shortArrayInput: DamagedRowShortArray = { value: [0, 1] };
  throwsSnapshotError(
    () => decodeShapeReplayRow(assertShapeReplayWireRow(shortArrayInput.value), refusingStringAt),
    "SnapshotFontReplayShapeTransportInvalid",
  );
  const nonArrayFeatures: unknown[] = [...transportRowValues];
  nonArrayFeatures[10] = "not-an-array";
  throwsSnapshotError(
    () => decodeShapeReplayRow(assertShapeReplayWireRow(nonArrayFeatures), refusingStringAt),
    "SnapshotFontReplayShapeTransportInvalid",
  );
  const shortGlyphs: unknown[] = [...transportRowValues];
  shortGlyphs[13] = [1, 1, 0, 0];
  throwsSnapshotError(
    () => decodeShapeReplayRow(assertShapeReplayWireRow(shortGlyphs), refusingStringAt),
    "SnapshotFontReplayShapeTransportInvalid",
  );
  const badItalic: unknown[] = [...transportRowValues];
  badItalic[3] = 2;
  throwsSnapshotError(
    () => decodeShapeReplayRow(assertShapeReplayWireRow(badItalic), refusingStringAt),
    "SnapshotFontReplayShapeTransportInvalid",
  );
});

test("decodeShapeReplayRow rejects partially-null glyph bounds", () => {
  const glyphDataWithPartialNulls: Array<number | null> = [1, 1, 0, 0, 1, 1, 1, null];
  const row: ShapeReplayWireRow = createShapeWireRow(0, 1, 400, 0, 2, 3, 0, 4, 5, 6, [], 0, 1, glyphDataWithPartialNulls);
  throwsSnapshotError(
    () => decodeShapeReplayRow(row, () => {
      throw new Error("stringAt must not run on invalid rows");
    }),
    "SnapshotFontReplayGlyphBoundsInvalid",
  );
});

test("scaleShapeReplayItem scales a normal entry exactly", () => {
  const scaled = scaleShapeReplayItem(shapeItem() as ReplayShapeItem, 16);
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
  throwsFieldError(() => scaleShapeReplayItem(shapeItem({ glyphs: [glyph({ id: -1 })] }) as ReplayShapeItem, 16), "glyph");
  throwsFieldError(() => scaleShapeReplayItem(shapeItem({ glyphs: [glyph({ id: 1.5 })] }) as ReplayShapeItem, 16), "glyph");
  throwsFieldError(() => scaleShapeReplayItem(shapeItem({ glyphs: [glyph({ advanceEm: Number.NaN })] }) as ReplayShapeItem, 16), "glyph-advance");
  throwsFieldError(() => scaleShapeReplayItem(shapeItem({ glyphs: [glyph({ xEm: Infinity })] }) as ReplayShapeItem, 16), "glyph-x");
  throwsFieldError(() => scaleShapeReplayItem(shapeItem({ glyphs: [glyph({ yEm: -Infinity })] }) as ReplayShapeItem, 16), "glyph-y");
  throwsFieldError(() => scaleShapeReplayItem(shapeItem({ glyphs: [glyph({ boundsEm: [0, Number.NaN, 0, 0] })] }) as ReplayShapeItem, 16), "glyph-bound-1");
  throwsFieldError(() => scaleShapeReplayItem(shapeItem({ glyphs: [glyph({ boundsEm: [0, 0, 0] })] }) as ReplayShapeItem, 16), "glyph-bounds");
});

test("scaleShapeReplayItem names shape-result, features, unsafe-break-count and shape-advance failures", () => {
  // For testing error handling with completely wrong structure, pass invalid data through unknown
  const brokenRaw: unknown = { result: { glyphs: {} } };
  throwsFieldError(() => scaleShapeReplayItem(brokenRaw as ReplayShapeItem, 16), "shape-result");
  throwsFieldError(() => scaleShapeReplayItem(shapeItem({ unsafeBreakCount: -1 }) as ReplayShapeItem, 16), "unsafe-break-count");
  throwsFieldError(() => scaleShapeReplayItem(shapeItem({ unsafeBreakCount: 1.5 }) as ReplayShapeItem, 16), "unsafe-break-count");
  throwsFieldError(() => scaleShapeReplayItem(shapeItem({ advanceEm: Number.NaN }) as ReplayShapeItem, 16), "shape-advance");
});

interface InvalidFontSize {
  value: string;
}

test("scaleShapeReplayItem rejects non-positive or non-number font sizes", () => {
  throwsFieldError(() => scaleShapeReplayItem(shapeItem() as ReplayShapeItem, 0), "font-size");
  throwsFieldError(() => scaleShapeReplayItem(shapeItem() as ReplayShapeItem, -16), "font-size");
  throwsFieldError(() => scaleShapeReplayItem(shapeItem() as ReplayShapeItem, Number.NaN), "font-size");
  const badFontSize: unknown = "abc";
  throwsFieldError(() => scaleShapeReplayItem(shapeItem() as ReplayShapeItem, badFontSize as number), "font-size");
});

test("scaleMetricReplayItem scales a normal five-tuple exactly", () => {
  const item: MetricItemResult = metricItem({ valuesEm: [0.5, 0.25, 0.125, 0.75, -0.5] });
  const scaled: number[] = scaleMetricReplayItem(item as ReplayMetricItem, 16);
  assert.deepEqual(scaled, [8, 4, 2, 12, -8]);
});

test("scaleMetricReplayItem passes null values through as NaN", () => {
  const item: MetricItemResult = metricItem({ valuesEm: [1, null, 3, null, 5] });
  const scaled: number[] = scaleMetricReplayItem(item as ReplayMetricItem, 2);
  assert.deepEqual(scaled, [2, Number.NaN, 6, Number.NaN, 10]);
});

interface MetricItemOverrides {
  key?: string;
  valuesEm?: readonly (number | null)[];
}

interface MetricItemResult {
  key: string;
  valuesEm: readonly (number | null)[];
}

function metricItem(overrides: MetricItemOverrides = {}): MetricItemResult {
  return {
    key: overrides.key ?? "test-metric-key",
    valuesEm: overrides.valuesEm ?? [1, 2, 3, 4, 5],
  };
}

test("scaleMetricReplayItem names metrics-result, metric-index and font-size failures", () => {
  throwsFieldError(() => scaleMetricReplayItem(metricItem({ valuesEm: [1, 2] }) as ReplayMetricItem, 16), "metrics-result");
  const emptyItem: unknown = {};
  throwsFieldError(() => scaleMetricReplayItem(emptyItem as ReplayMetricItem, 16), "metrics-result");
  throwsFieldError(() => scaleMetricReplayItem(metricItem({ valuesEm: [1, Number.NaN, 3, 4, 5] }) as ReplayMetricItem, 16), "metric-1");
  throwsFieldError(() => scaleMetricReplayItem(metricItem({ valuesEm: [1, 2, 3, 4, Infinity] }) as ReplayMetricItem, 16), "metric-4");
  throwsFieldError(() => scaleMetricReplayItem(metricItem() as ReplayMetricItem, 0), "font-size");
});

test("decodeMetricReplayRow mirrors table rows without validation", () => {
  const row: SnapshotMetricRow = {
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
  const sparse: SnapshotMetricRow = {
    serializedFamilies: "",
    fontWeight: 400,
    italic: false,
    role: "",
    faceSelectionText: "",
    valuesEm: [1, 2, 3, 4, 5],
  };
  const decoded = decodeMetricReplayRow(sparse);
  assert.equal(decoded.valuesEm, sparse.valuesEm);
  assert.equal(typeof decoded.key, "string");
  // Test that undefined valuesEm passes through as undefined at runtime
  const emptyWithUndefined = {
    serializedFamilies: "",
    fontWeight: 400,
    italic: false,
    role: "",
    faceSelectionText: "",
  };
  const emptyDecoded = decodeMetricReplayRow(emptyWithUndefined as SnapshotMetricRow);
  assert.equal(emptyDecoded.valuesEm, undefined);
  assert.equal(typeof emptyDecoded.key, "string");
});
