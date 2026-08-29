// The snapshot-table binary reader of ADR 0052: decodes the `TIQTBL03` byte
// file the Rust encoder produces into lazy accessors. Adopting a root reads
// only the rows the manifest references. The byte contract lives in the
// encoder; this file mirrors the region order and validates every offset
// against the byte length, so a damaged file fails with
// `SnapshotTablesInvalid` before any row is read.

const MAGIC = "TIQTBL03";
const HEADER_U32_COUNT = 12;
const METRIC_POOL_ROW_BYTES = 40;
const PROBE_STYLE_ROW_BYTES = 25;
/** `f64::NAN.to_bits()`; the encoder writes exactly these bits for absent. */
const ABSENT_METRIC_BITS = 0x7ff8000000000000n;

const decoder = new TextDecoder("utf-8", { fatal: true });

interface DeltaRegion {
  offsets: number[];
  bytesStart: number;
}

interface SnapshotTableLayout {
  replayStringCount: number;
  stringCount: number;
  metricCount: number;
  metricValuePoolCount: number;
  probeCount: number;
  stringOffsets: number[];
  stringBytesStart: number;
  metricFamiliesStart: number;
  metricWeightsStart: number;
  metricItalicsStart: number;
  metricRoleRefsStart: number;
  metricFaceSelRefsStart: number;
  metricPoolRefsStart: number;
  metricValuePoolStart: number;
  probeTextRefsStart: number;
  probeAdvanceRefsStart: number;
  probeStyleRefsStart: number;
  probeFeatureRefsStart: number;
  probeAdvancePoolStart: number;
  probeStylePoolStart: number;
  probeFeatures: DeltaRegion;
  faceText: DeltaRegion;
  typographyText: DeltaRegion;
  valueStyleText: DeltaRegion;
  fontPreloadText: DeltaRegion;
  revisionTextStart: number;
}

export interface SnapshotMetricRow {
  serializedFamilies: string;
  fontWeight: number;
  italic: boolean;
  role: string;
  faceSelectionText: string;
  valuesEm: (number | null)[];
}

export interface SnapshotProbe {
  text: string;
  advancePx: number;
  fontSizePx: number;
  fontWeight: number;
  italic: boolean;
  script: string;
  language: string;
  features: string[];
}

export interface SnapshotRevisions {
  backendRevision: string | null;
  harfbuzzVersion: string | null;
}

type SnapshotTableStringAtFn = (ref: number) => string;

type SnapshotTableMetricRowsFn = () => SnapshotMetricRow[];

type SnapshotTableProbeAtFn = (ref: number) => SnapshotProbe;

type SnapshotTableTypoAtFn = (ref: number) => unknown;

type SnapshotTableFaceAtFn = (ref: number) => unknown;

type SnapshotTableValueStylesFn = () => string[];

type SnapshotTableRevisionsFn = () => SnapshotRevisions;

export interface SnapshotTableBinaryView {
  binary: true;
  bytes: Uint8Array;
  stringAt: SnapshotTableStringAtFn;
  metricRows: SnapshotTableMetricRowsFn;
  probeAt: SnapshotTableProbeAtFn;
  typographyAt: SnapshotTableTypoAtFn;
  faceAt: SnapshotTableFaceAtFn;
  valueStyles: SnapshotTableValueStylesFn;
  revisions: SnapshotTableRevisionsFn;
}

function invalid(): Error {
  return new Error("SnapshotTablesInvalid");
}

/** True when the bytes start with the snapshot-table magic. */
export function isSnapshotTableBinary(bytes: unknown): boolean {
  if (!(bytes instanceof Uint8Array) || bytes.length < 8) return false;
  try {
    return decoder.decode(bytes.subarray(0, 8)) === MAGIC;
  } catch {
    return false;
  }
}

function readU32(bytes: Uint8Array, at: number): number {
  if (at < 0 || at + 4 > bytes.length) throw invalid();
  return (bytes[at] | (bytes[at + 1] << 8) | (bytes[at + 2] << 16)) + bytes[at + 3] * 0x1000000;
}

function readU16(bytes: Uint8Array, at: number): number {
  if (at < 0 || at + 2 > bytes.length) throw invalid();
  return bytes[at] | (bytes[at + 1] << 8);
}

const dataViewOf = (bytes: Uint8Array): DataView => new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);

function readF64(view: DataView, at: number): number {
  return view.getFloat64(at, true);
}

/**
 * One delta-coded offsets region: `count` u32 deltas summed from an implicit
 * zero. Any delta sequence decodes monotone; the running sum must stay
 * addressable within the file.
 */
function readDeltasRegion(bytes: Uint8Array, start: number, count: number): number[] {
  const offsets = new Array<number>(count + 1);
  offsets[0] = 0;
  let at = 0;
  for (let index = 0; index < count; index += 1) {
    at += readU32(bytes, start + index * 4);
    if (at > bytes.length) throw invalid();
    offsets[index + 1] = at;
  }
  return offsets;
}

/**
 * Decodes the header and region boundaries. Every region is walked in order,
 * so the returned layout proves the file holds every region it counts.
 */
function decodeLayout(bytes: Uint8Array): SnapshotTableLayout {
  if (!isSnapshotTableBinary(bytes)) throw invalid();
  const counts = new Array<number>(HEADER_U32_COUNT);
  for (let index = 0; index < HEADER_U32_COUNT; index += 1) {
    counts[index] = readU32(bytes, 8 + index * 4);
  }
  const [
    replayStringCount, stringCount, metricCount, metricValuePoolCount,
    probeCount, probeAdvancePoolCount, probeStylePoolCount, probeFeaturesPoolCount,
    faceCount, typographyCount, valueStyleCount, fontPreloadCount,
  ] = counts;
  let at = 8 + HEADER_U32_COUNT * 4;
  const take = (byteLength: number): number => {
    const start = at;
    at += byteLength;
    if (at > bytes.length) throw invalid();
    return start;
  };
  const takeDeltas = (count: number): DeltaRegion => {
    const start = take(count * 4);
    const offsets = readDeltasRegion(bytes, start, count);
    return { offsets, bytesStart: take(offsets[count]) };
  };
  const stringDeltasStart = take(stringCount * 4);
  const stringOffsets = readDeltasRegion(bytes, stringDeltasStart, stringCount);
  const stringBytesStart = take(stringOffsets[stringCount]);
  const metricFamiliesStart = take(metricCount * 4);
  const metricWeightsStart = take(metricCount * 8);
  const metricItalicsStart = take(metricCount);
  const metricRoleRefsStart = take(metricCount * 4);
  const metricFaceSelRefsStart = take(metricCount * 4);
  const metricPoolRefsStart = take(metricCount * 4);
  const metricValuePoolStart = take(metricValuePoolCount * METRIC_POOL_ROW_BYTES);
  const probeTextRefsStart = take(probeCount * 4);
  const probeAdvanceRefsStart = take(probeCount * 2);
  const probeStyleRefsStart = take(probeCount * 2);
  const probeFeatureRefsStart = take(probeCount * 2);
  const probeAdvancePoolStart = take(probeAdvancePoolCount * 8);
  const probeStylePoolStart = take(probeStylePoolCount * PROBE_STYLE_ROW_BYTES);
  const probeFeatures = takeDeltas(probeFeaturesPoolCount);
  const faceText = takeDeltas(faceCount);
  const typographyText = takeDeltas(typographyCount);
  const valueStyleText = takeDeltas(valueStyleCount);
  const fontPreloadText = takeDeltas(fontPreloadCount);
  const revisionTextStart = at;
  if (revisionTextStart > bytes.length) throw invalid();
  return {
    replayStringCount,
    stringCount,
    metricCount,
    metricValuePoolCount,
    probeCount,
    stringOffsets,
    stringBytesStart,
    metricFamiliesStart,
    metricWeightsStart,
    metricItalicsStart,
    metricRoleRefsStart,
    metricFaceSelRefsStart,
    metricPoolRefsStart,
    metricValuePoolStart,
    probeTextRefsStart,
    probeAdvanceRefsStart,
    probeStyleRefsStart,
    probeFeatureRefsStart,
    probeAdvancePoolStart,
    probeStylePoolStart,
    probeFeatures,
    faceText,
    typographyText,
    valueStyleText,
    fontPreloadText,
    revisionTextStart,
  };
}

function regionText(
  bytes: Uint8Array,
  start: number,
  offsets: number[],
  index: number,
  issue: string,
): string {
  if (!Number.isSafeInteger(index) || index < 0 || index >= offsets.length - 1) {
    throw new Error(issue);
  }
  const from = start + offsets[index];
  const to = start + offsets[index + 1];
  try {
    return decoder.decode(bytes.subarray(from, to));
  } catch {
    throw invalid();
  }
}

function parseRegionJson(
  bytes: Uint8Array,
  start: number,
  offsets: number[],
  index: number,
  issue: string,
): unknown {
  const text = regionText(bytes, start, offsets, index, issue);
  try {
    return JSON.parse(text);
  } catch {
    throw invalid();
  }
}

/** Parses the revision tail; called during decode and memoized per view. */
function readRevisionsOf(bytes: Uint8Array, layout: SnapshotTableLayout): SnapshotRevisions {
  try {
    const parsed = JSON.parse(decoder.decode(bytes.subarray(layout.revisionTextStart)));
    return {
      backendRevision: parsed.backendRevision ?? null,
      harfbuzzVersion: parsed.harfbuzzVersion ?? null,
    };
  } catch {
    throw invalid();
  }
}

/**
 * The binary table view: the same accessor surface the parsed-text reader
 * wraps, reading rows from the bytes on demand and caching each decoded row.
 */
export function decodeSnapshotTableBinary(bytes: Uint8Array): SnapshotTableBinaryView {
  const layout = decodeLayout(bytes);
  const view = dataViewOf(bytes);
  // The revision tail parses during decode, mirroring the Rust reader. The
  // tail has no declared length; this parse is what makes a truncated file
  // fail before any accessor hands out a row.
  readRevisionsOf(bytes, layout);
  const stringCache = new Array<string | undefined>(layout.stringCount).fill(undefined);

  const stringAt = (ref: number): string => {
    if (!Number.isSafeInteger(ref) || ref < 0 || ref >= layout.stringCount) {
      throw new Error("SnapshotFontReplayStringReferenceInvalid");
    }
    if (stringCache[ref] === undefined) {
      const from = layout.stringBytesStart + layout.stringOffsets[ref];
      const to = layout.stringBytesStart + layout.stringOffsets[ref + 1];
      try {
        stringCache[ref] = decoder.decode(bytes.subarray(from, to));
      } catch {
        throw invalid();
      }
    }
    return stringCache[ref] as string;
  };

  const metricValueAt = (poolRef: number, slot: number): number | null => {
    const at = layout.metricValuePoolStart + poolRef * METRIC_POOL_ROW_BYTES + slot * 8;
    if (at < 0 || at + 8 > bytes.length) throw invalid();
    const bits = view.getBigUint64(at, true);
    if (bits === ABSENT_METRIC_BITS) return null;
    const value = view.getFloat64(at, true);
    if (!Number.isFinite(value)) throw invalid();
    return value;
  };

  // Manifest expansion reads the metric rows once per expansion; the rows are
  // a pure function of the bytes, so the decoded form is memoized per view
  // and repeated expansions stop rescanning the whole table.
  let metricRowsCache: SnapshotMetricRow[] | null = null;
  const metricRows = (): SnapshotMetricRow[] => {
    if (metricRowsCache !== null) return metricRowsCache;
    const rows = new Array<SnapshotMetricRow>(layout.metricCount);
    for (let index = 0; index < layout.metricCount; index += 1) {
      const poolRef = readU32(bytes, layout.metricPoolRefsStart + index * 4);
      rows[index] = {
        serializedFamilies: stringAt(readU32(bytes, layout.metricFamiliesStart + index * 4)),
        fontWeight: readF64(view, layout.metricWeightsStart + index * 8),
        italic: bytes[layout.metricItalicsStart + index] === 1,
        role: stringAt(readU32(bytes, layout.metricRoleRefsStart + index * 4)),
        faceSelectionText: stringAt(readU32(bytes, layout.metricFaceSelRefsStart + index * 4)),
        valuesEm: [
          metricValueAt(poolRef, 0),
          metricValueAt(poolRef, 1),
          metricValueAt(poolRef, 2),
          metricValueAt(poolRef, 3),
          metricValueAt(poolRef, 4),
        ],
      };
    }
    metricRowsCache = rows;
    return rows;
  };

  const decodeProbe = (ref: number): SnapshotProbe => {
    if (!Number.isSafeInteger(ref) || ref < 0 || ref >= layout.probeCount) {
      throw new Error("SnapshotProbeReferenceInvalid");
    }
    const textRef = readU32(bytes, layout.probeTextRefsStart + ref * 4);
    const advancePoolRef = readU16(bytes, layout.probeAdvanceRefsStart + ref * 2);
    const stylePoolRef = readU16(bytes, layout.probeStyleRefsStart + ref * 2);
    const featuresPoolRef = readU16(bytes, layout.probeFeatureRefsStart + ref * 2);
    if (featuresPoolRef >= layout.probeFeatures.offsets.length - 1) throw invalid();
    const advanceAt = layout.probeAdvancePoolStart + advancePoolRef * 8;
    const styleAt = layout.probeStylePoolStart + stylePoolRef * PROBE_STYLE_ROW_BYTES;
    if (advanceAt < 0 || advanceAt + 8 > bytes.length) throw invalid();
    if (styleAt < 0 || styleAt + PROBE_STYLE_ROW_BYTES > bytes.length) throw invalid();
    const featuresAt =
      layout.probeFeatures.bytesStart + layout.probeFeatures.offsets[featuresPoolRef];
    const featureCount = readU16(bytes, featuresAt);
    const features = new Array<string>(featureCount);
    for (let index = 0; index < featureCount; index += 1) {
      features[index] = stringAt(readU32(bytes, featuresAt + 2 + index * 4));
    }
    return {
      text: stringAt(textRef),
      advancePx: readF64(view, advanceAt),
      fontSizePx: readF64(view, styleAt),
      fontWeight: readF64(view, styleAt + 8),
      italic: bytes[styleAt + 16] === 1,
      script: stringAt(readU32(bytes, styleAt + 17)),
      language: stringAt(readU32(bytes, styleAt + 21)),
      features,
    };
  };

  const probeCache = new Map<number, SnapshotProbe>();
  const probeAt = (ref: number): SnapshotProbe => {
    if (!probeCache.has(ref)) {
      probeCache.set(ref, decodeProbe(ref));
    }
    return probeCache.get(ref) as SnapshotProbe;
  };

  const typographyCache = new Map<number, unknown>();
  const typographyAt = (ref: number): unknown => {
    if (!typographyCache.has(ref)) {
      typographyCache.set(
        ref,
        parseRegionJson(
          bytes,
          layout.typographyText.bytesStart,
          layout.typographyText.offsets,
          ref,
          "SnapshotTypographyReferenceInvalid",
        ),
      );
    }
    return typographyCache.get(ref);
  };

  const faceCache = new Map<number, unknown>();
  const faceAt = (ref: number): unknown => {
    if (!faceCache.has(ref)) {
      faceCache.set(
        ref,
        parseRegionJson(
          bytes,
          layout.faceText.bytesStart,
          layout.faceText.offsets,
          ref,
          "SnapshotFontFaceReferenceInvalid",
        ),
      );
    }
    return faceCache.get(ref);
  };

  let valueStyles: string[] | null = null;
  const readValueStyles = (): string[] => {
    if (valueStyles === null) {
      valueStyles = [];
      const { offsets, bytesStart } = layout.valueStyleText;
      for (let index = 0; index < offsets.length - 1; index += 1) {
        valueStyles.push(regionText(bytes, bytesStart, offsets, index, "SnapshotTablesInvalid"));
      }
    }
    return valueStyles;
  };

  let revisions: SnapshotRevisions | null = null;
  const readRevisions = (): SnapshotRevisions => {
    if (revisions === null) revisions = readRevisionsOf(bytes, layout);
    return revisions;
  };

  return {
    binary: true,
    bytes,
    stringAt,
    metricRows,
    probeAt,
    typographyAt,
    faceAt,
    valueStyles: readValueStyles,
    revisions: readRevisions,
  };
}
