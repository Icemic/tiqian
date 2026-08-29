import {
  metricReplayKey,
  shapeReplayKey,
} from "../sampler/snapshot/snapshot-schema.js";
import type { SnapshotMetricRow } from "../sampler/snapshot/snapshot-table-binary.js";

export type StringAt = (ref: number) => string;

export interface ReplayShapeGlyph {
  id: number;
  advanceEm: number;
  xEm: number;
  yEm: number;
  boundsEm: number[] | null;
}

export interface ReplayShapeResult {
  faceId: string;
  fontInstanceId: string;
  script: string;
  features: string[];
  unsafeBreakCount: number;
  advanceEm: number;
  glyphs: ReplayShapeGlyph[];
}

export interface ReplayShapeItem {
  key: string;
  result: ReplayShapeResult;
}

export interface ReplayMetricItem {
  key: string;
  valuesEm: (number | null)[];
}

export interface ShapeReplayWireRow {
  0: number;
  1: number;
  2: number;
  3: number;
  4: number;
  5: number;
  6: number;
  7: number;
  8: number;
  9: number;
  10: number[];
  11: number;
  12: number;
  13: number[];
  length: 14;
}

export interface ScaledShapeGlyph {
  id: number;
  advance: number;
  x: number;
  y: number;
  bounds: number[] | null;
}

export interface ScaledShapeResult {
  faceId: string;
  fontInstanceId: string;
  script: string;
  features: string[];
  unsafeBreakCount: number;
  advance: number;
  glyphs: ScaledShapeGlyph[];
}

/**
 * Shape and metric replay entry codec (ADR 0053 A5a). One module owns row
 * expansion, read-side scaling, and the px→em canonicalization A5b needs when
 * probing entries back into a session. Keys and revision constants stay in
 * snapshot-schema.js; the Rust side of the precompute port keeps its parallel
 * implementations in replay.rs and stays aligned through shared tests.
 */

/**
 * `finiteNumber`: read-side guard shared by both scaling functions. Non-finite
 * values name the field in `InvalidServerShapingReplay:<field>` (moved
 * verbatim from browser-font-replay.js).
 */
function finiteNumber(value: number, field: string): number {
  if (typeof value !== "number" || !Number.isFinite(value)) {
    throw new Error(`InvalidServerShapingReplay:${field}`);
  }
  return value;
}

/**
 * `decodeShapeReplayRow`: expands one 14-column wire row of `fontReplay.shapes`
 * into a `{key, result}` entry (moved verbatim from snapshot-manifest.js's
 * `expandReplayShapes`). Transport shape, feature-array shape, glyph-array
 * length and italic flag are validated first; each 8-slot glyph quad then
 * either carries four non-null bounds values or a whole null `boundsEm`.
 */
export function decodeShapeReplayRow(row: ShapeReplayWireRow, stringAt: StringAt): ReplayShapeItem {
  if (!Array.isArray(row) || row.length !== 14 || !Array.isArray(row[10]) ||
      !Array.isArray(row[13]) || row[13].length % 8 !== 0 ||
      (row[3] !== 0 && row[3] !== 1)) {
    throw new Error("SnapshotFontReplayShapeTransportInvalid");
  }
  const glyphs: ReplayShapeGlyph[] = [];
  for (let index = 0; index < row[13].length; index += 8) {
    const bounds = row[13].slice(index + 4, index + 8);
    const allNull = bounds.every((value) => value == null);
    if (!allNull && bounds.some((value) => value == null)) {
      throw new Error("SnapshotFontReplayGlyphBoundsInvalid");
    }
    glyphs.push({
      id: row[13][index],
      advanceEm: row[13][index + 1],
      xEm: row[13][index + 2],
      yEm: row[13][index + 3],
      boundsEm: allNull ? null : bounds,
    });
  }
  const displayText = stringAt(row[0]);
  const serializedFamilies = stringAt(row[1]);
  const fontWeight = row[2];
  const italic = row[3] === 1;
  const locale = stringAt(row[4]);
  const role = stringAt(row[5]);
  const sourceText = stringAt(row[6]);
  return {
    key: shapeReplayKey(
      displayText,
      serializedFamilies,
      fontWeight,
      italic,
      locale,
      role,
      sourceText,
    ),
    result: {
      faceId: stringAt(row[7]),
      fontInstanceId: stringAt(row[8]),
      script: stringAt(row[9]),
      features: row[10].map(stringAt),
      unsafeBreakCount: row[11],
      advanceEm: row[12],
      glyphs,
    },
  };
}

/**
 * `decodeMetricReplayRow`: maps one `metricRows()` view row straight to a
 * `{key, valuesEm}` entry (moved verbatim from snapshot-manifest.js's
 * `replayMetricsOf`). No validation: the table view already decoded the binary
 * bytes, so missing fields are mirrored without throwing.
 */
export function decodeMetricReplayRow(row: SnapshotMetricRow): ReplayMetricItem {
  return {
    key: metricReplayKey(
      row.serializedFamilies,
      row.fontWeight,
      row.italic,
      row.role,
      row.faceSelectionText,
    ),
    valuesEm: row.valuesEm,
  };
}

/**
 * `scaleShapeReplayItem`: scales one shape entry's em fields to px by
 * `fontSize` (moved verbatim from browser-font-replay.js's `scaledShape`).
 * Validation order, `InvalidServerShapingReplay:<field>` names and NaN
 * semantics are unchanged; the shape result is re-created, so callers own the
 * returned object.
 */
export function scaleShapeReplayItem(item: ReplayShapeItem, fontSize: number): ScaledShapeResult {
  const result = item?.result;
  if (!result || typeof result !== "object" || !Array.isArray(result.glyphs)) {
    throw new Error("InvalidServerShapingReplay:shape-result");
  }
  const scale = finiteNumber(Number(fontSize), "font-size");
  if (scale <= 0) throw new Error("InvalidServerShapingReplay:font-size");
  const glyphs = result.glyphs.map((glyph) => {
    if (!glyph || typeof glyph !== "object" || !Number.isSafeInteger(glyph.id) || glyph.id < 0) {
      throw new Error("InvalidServerShapingReplay:glyph");
    }
    const bounds = glyph.boundsEm == null
      ? null
      : glyph.boundsEm.map((value, index) => finiteNumber(value, `glyph-bound-${index}`) * scale);
    if (bounds != null && bounds.length !== 4) {
      throw new Error("InvalidServerShapingReplay:glyph-bounds");
    }
    return {
      id: glyph.id,
      advance: finiteNumber(glyph.advanceEm, "glyph-advance") * scale,
      x: finiteNumber(glyph.xEm, "glyph-x") * scale,
      y: finiteNumber(glyph.yEm, "glyph-y") * scale,
      bounds,
    };
  });
  if (!Array.isArray(result.features) || result.features.some((value) => typeof value !== "string")) {
    throw new Error("InvalidServerShapingReplay:features");
  }
  const unsafeBreakCount = Number(result.unsafeBreakCount ?? 0);
  if (!Number.isSafeInteger(unsafeBreakCount) || unsafeBreakCount < 0) {
    throw new Error("InvalidServerShapingReplay:unsafe-break-count");
  }
  return {
    faceId: String(result.faceId ?? ""),
    fontInstanceId: String(result.fontInstanceId ?? ""),
    script: String(result.script ?? ""),
    features: [...result.features],
    unsafeBreakCount,
    advance: finiteNumber(result.advanceEm, "shape-advance") * scale,
    glyphs,
  };
}

/**
 * `scaleMetricReplayItem`: scales one metric entry's `valuesEm` five-tuple to
 * px by `fontSize` (moved verbatim from browser-font-replay.js's
 * `scaledMetrics`). Null values pass through as `Number.NaN`, matching the
 * Kotlin side mapping non-finite typo values to null.
 */
export function scaleMetricReplayItem(item: ReplayMetricItem, fontSize: number): number[] {
  if (!Array.isArray(item?.valuesEm) || item.valuesEm.length !== 5) {
    throw new Error("InvalidServerShapingReplay:metrics-result");
  }
  const scale = finiteNumber(Number(fontSize), "font-size");
  if (scale <= 0) throw new Error("InvalidServerShapingReplay:font-size");
  return item.valuesEm.map((value, index) => value == null
    ? Number.NaN
    : finiteNumber(value, `metric-${index}`) * scale);
}

/**
 * `normalizeReplayNumber`: px→em canonicalization mirroring replay.rs's
 * `normalized_replay_number` (FontSizeIndependentReplayCanonicalization).
 * Non-finite values become null; finite values divide by the font size and
 * round to 12 decimals via `toFixed`, the `Number.prototype` sibling of Rust's
 * `{:.12}` formatter, so probe-backfilled entries share the write-side bytes.
 * A normalized `-0` reads back as `0`.
 */
export function normalizeReplayNumber(value: number, fontSize: number): number | null {
  if (!Number.isFinite(value)) return null;
  const normalized = Number((value / fontSize).toFixed(12));
  return normalized === 0 ? 0 : normalized;
}
