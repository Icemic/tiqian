// A test-only encoder of the `TIQTBL03` snapshot-table bytes. It mirrors the
// Rust encoder (region order, metric sort, pool assignment, string intern
// order); re-encoding a decoded file reproduces the input bytes exactly. The
// tests use it to pin the byte contract from the consumer side.

import type {
  SnapshotMetricRow,
  SnapshotProbe,
  SnapshotRevisions,
} from "./snapshot-table-binary.js";

const encoder = new TextEncoder();

export interface BinaryTableInput {
  replayStrings?: readonly string[];
  metrics?: readonly SnapshotMetricRow[];
  probes?: readonly SnapshotProbe[];
  faces?: readonly unknown[];
  typographies?: readonly unknown[];
  valueStyles?: readonly string[];
  fontPreloads?: readonly string[];
  revisions?: SnapshotRevisions | Record<string, unknown>;
}

interface InternalMetricRow {
  familiesRef: number;
  weight: number;
  italic: number;
  roleRef: number;
  faceSelectionRef: number;
  values: readonly (number | null)[];
  valuePoolRef?: number;
}

interface ValuePoolEntry {
  key: string;
  values: readonly (number | null)[];
}

interface StylePoolEntry {
  key: string;
  fontSizePx: number;
  fontWeight: number;
  italic: boolean;
  scriptRef: number;
  languageRef: number;
}

interface FeaturesPoolEntry {
  key: string;
  refs: number[];
}

interface ProbeRow {
  textRef: number;
  advanceRef: number;
  styleRef: number;
  featuresRef: number;
}

function writeU32(parts: number[], value: number): void {
  if (!Number.isInteger(value) || value < 0 || value > 0xffffffff) {
    throw new Error("TableWriterU32OutOfRange");
  }
  parts.push(value & 0xff, (value >>> 8) & 0xff, (value >>> 16) & 0xff, (value >>> 24) & 0xff);
}

function writeU16(parts: number[], value: number): void {
  if (!Number.isInteger(value) || value < 0 || value > 0xffff) {
    throw new Error("TableWriterU16OutOfRange");
  }
  parts.push(value & 0xff, (value >>> 8) & 0xff);
}

const f64View = new DataView(new ArrayBuffer(8));

function writeF64(parts: number[], value: number): void {
  f64View.setFloat64(0, value, true);
  for (const byte of new Uint8Array(f64View.buffer)) parts.push(byte);
}

const ABSENT_BITS = 0x7ff8000000000000n;

function writeAbsentF64(parts: number[]): void {
  f64View.setBigUint64(0, ABSENT_BITS, true);
  for (const byte of new Uint8Array(f64View.buffer)) parts.push(byte);
}

/**
 * One deltas region followed by the concatenated bytes of every row. Each
 * delta equals its row's byte length; the offsets start at an implicit zero.
 */
function writeDeltasAndBytes(parts: number[], byteRows: Array<Uint8Array | number[]>): void {
  for (const row of byteRows) writeU32(parts, row.length);
  for (const row of byteRows) parts.push(...row);
}

function textRows(texts: readonly string[]): Uint8Array[] {
  return texts.map((text: string): Uint8Array => encoder.encode(text));
}

export function writeBinaryTable(table: BinaryTableInput): Uint8Array {
  const replayStrings: readonly string[] = table.replayStrings ?? [];
  const metrics: readonly SnapshotMetricRow[] = table.metrics ?? [];
  const probes: readonly SnapshotProbe[] = table.probes ?? [];
  const faces: readonly unknown[] = table.faces ?? [];
  const typographies: readonly unknown[] = table.typographies ?? [];
  const valueStyles: readonly string[] = table.valueStyles ?? [];
  const fontPreloads: readonly string[] = table.fontPreloads ?? [];
  const revisions: SnapshotRevisions | Record<string, unknown> = table.revisions ?? {};

  const strings: string[] = [...replayStrings];
  const stringRefs = new Map<string, number>(strings.map((text, index) => [text, index]));
  const intern = (text: string | undefined): number => {
    const key = text ?? "";
    const existing = stringRefs.get(key);
    if (existing != null) return existing;
    const ref = strings.length;
    stringRefs.set(key, ref);
    strings.push(key);
    return ref;
  };

  const metricRows: InternalMetricRow[] = metrics.map((row) => ({
    familiesRef: intern(row.serializedFamilies),
    weight: row.fontWeight,
    italic: row.italic ? 1 : 0,
    roleRef: intern(row.role),
    faceSelectionRef: intern(row.faceSelectionText),
    values: row.valuesEm,
  }));
  metricRows.sort((left, right) =>
    left.familiesRef - right.familiesRef ||
    left.weight - right.weight ||
    left.italic - right.italic ||
    left.roleRef - right.roleRef ||
    left.faceSelectionRef - right.faceSelectionRef);
  const valuePool: ValuePoolEntry[] = [];
  const poolIndexOf = (values: readonly (number | null)[]): number => {
    const key = JSON.stringify(values.map((value) => (value == null ? "absent" : value)));
    const existing = valuePool.findIndex((row) => row.key === key);
    if (existing >= 0) return existing;
    valuePool.push({ key, values });
    return valuePool.length - 1;
  };
  for (const row of metricRows) row.valuePoolRef = poolIndexOf(row.values);

  const advancePool: number[] = [];
  const stylePool: StylePoolEntry[] = [];
  const featuresPool: FeaturesPoolEntry[] = [];
  const probeRows: ProbeRow[] = probes.map((probe) => {
    // String intern order per probe mirrors the encoder: text, script,
    // language, then features.
    const textRef = intern(probe.text);
    const scriptRef = intern(probe.script);
    const languageRef = intern(probe.language);
    const featureRefs = (probe.features ?? []).map(intern);
    const advance = probe.advancePx ?? 0;
    let advanceRef = advancePool.indexOf(advance);
    if (advanceRef < 0) {
      advancePool.push(advance);
      advanceRef = advancePool.length - 1;
    }
    const fontSizePx = probe.fontSizePx ?? 0;
    const fontWeight = probe.fontWeight ?? 400;
    const italic = probe.italic ?? false;
    const styleKey = JSON.stringify([
      fontSizePx, fontWeight, italic, probe.script ?? "", probe.language ?? "",
    ]);
    let styleRef = stylePool.findIndex((row) => row.key === styleKey);
    if (styleRef < 0) {
      stylePool.push({
        key: styleKey,
        fontSizePx,
        fontWeight,
        italic,
        scriptRef,
        languageRef,
      });
      styleRef = stylePool.length - 1;
    }
    const featuresKey = JSON.stringify(featureRefs);
    let featuresRef = featuresPool.findIndex((row) => row.key === featuresKey);
    if (featuresRef < 0) {
      featuresPool.push({ key: featuresKey, refs: featureRefs });
      featuresRef = featuresPool.length - 1;
    }
    return {
      textRef,
      advanceRef,
      styleRef,
      featuresRef,
    };
  });

  const faceTexts: string[] = faces.map((face) => JSON.stringify(face));
  const typographyTexts: string[] = typographies.map((row) => JSON.stringify(row));
  const revisionText = JSON.stringify(revisions);

  const parts: number[] = [];
  parts.push(...encoder.encode("TIQTBL03"));
  writeU32(parts, replayStrings.length);
  writeU32(parts, strings.length);
  writeU32(parts, metricRows.length);
  writeU32(parts, valuePool.length);
  writeU32(parts, probeRows.length);
  writeU32(parts, advancePool.length);
  writeU32(parts, stylePool.length);
  writeU32(parts, featuresPool.length);
  writeU32(parts, faceTexts.length);
  writeU32(parts, typographyTexts.length);
  writeU32(parts, valueStyles.length);
  writeU32(parts, fontPreloads.length);

  writeDeltasAndBytes(parts, textRows(strings));

  for (const row of metricRows) writeU32(parts, row.familiesRef);
  for (const row of metricRows) writeF64(parts, row.weight);
  for (const row of metricRows) parts.push(row.italic);
  for (const row of metricRows) writeU32(parts, row.roleRef);
  for (const row of metricRows) writeU32(parts, row.faceSelectionRef);
  for (const row of metricRows) writeU32(parts, row.valuePoolRef ?? 0);
  for (const pool of valuePool) {
    for (const value of pool.values) {
      if (value == null) writeAbsentF64(parts);
      else writeF64(parts, value);
    }
  }

  for (const row of probeRows) writeU32(parts, row.textRef);
  for (const row of probeRows) writeU16(parts, row.advanceRef);
  for (const row of probeRows) writeU16(parts, row.styleRef);
  for (const row of probeRows) writeU16(parts, row.featuresRef);
  for (const advance of advancePool) writeF64(parts, advance);
  for (const style of stylePool) {
    writeF64(parts, style.fontSizePx);
    writeF64(parts, style.fontWeight);
    parts.push(style.italic ? 1 : 0);
    writeU32(parts, style.scriptRef);
    writeU32(parts, style.languageRef);
  }
  writeDeltasAndBytes(parts, featuresPool.map((pool) => {
    const row: number[] = [];
    writeU16(row, pool.refs.length);
    for (const ref of pool.refs) writeU32(row, ref);
    return row;
  }));
  writeDeltasAndBytes(parts, textRows(faceTexts));
  writeDeltasAndBytes(parts, textRows(typographyTexts));
  writeDeltasAndBytes(parts, textRows(valueStyles));
  writeDeltasAndBytes(parts, textRows(fontPreloads));
  parts.push(...encoder.encode(revisionText));
  return new Uint8Array(parts);
}
