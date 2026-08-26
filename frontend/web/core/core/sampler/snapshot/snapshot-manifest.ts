import {
  FONT_REPLAY_REVISION,
  FONT_REPLAY_TRANSPORT,
} from "./snapshot-schema.js";
import {
  decodeMetricReplayRow,
  decodeShapeReplayRow,
} from "../../measurement/replay-entry-codec.js";
import type {
  ReplayMetricItem,
  ReplayShapeItem,
  ShapeReplayWireRow,
  StringAt,
} from "../../measurement/replay-entry-codec.js";
import type {
  SnapshotProbe,
  SnapshotTableBinaryView,
} from "./snapshot-table-binary.js";

export interface SnapshotManifestTypographyValue {
  fontFamilies?: string[];
  fontSizePx?: number;
  lineHeightPx?: number;
  locale?: string;
  fontWeight?: number;
  italic?: boolean;
  lineLengthGridEnabled?: boolean;
  letterSpacingPx?: number;
  fontFeatureSettings?: string;
  fontVariationSettings?: string;
  fontVariantNumeric?: string;
  // firstLineIndentIc is digest-protected passthrough; no reader at this
  // boundary (inventory D59).
  firstLineIndentIc?: number;
}

export interface SnapshotTypographyRecord {
  sha256: string;
  value: SnapshotManifestTypographyValue;
}

export interface SnapshotManifestFace {
  family: string;
  style: string;
  weight: number[];
  unicodeRange: string;
  publicUrl: string;
  sourceSha256: string;
  sfntSha256: string;
  faceIndex: number;
  sourceOrder: number;
  axes: Record<string, unknown>;
  localNames: string[];
  coverageText?: string;
  probe?: SnapshotProbe | undefined;
}

export interface SnapshotManifestFontEvidence {
  backendRevision: string | null;
  harfbuzzVersion: string | null;
  faces: SnapshotManifestFace[];
}

export interface SnapshotManifestEvidenceVersions {
  backendRevision: string | null;
  harfbuzzVersion: string | null;
}

export interface SnapshotManifestEntry {
  key: string;
  sourceSha256: string;
  sourceArtifactSha256?: string;
  semantic?: true;
  typographySha256: string;
  typography: SnapshotManifestTypographyValue;
  maxWidthPx: number;
  fontEvidence: SnapshotManifestFontEvidence;
  renderArtifactSha256: string;
}

export interface SnapshotManifestWireEvidence {
  faceRef: number;
  probeRef?: number;
  coverageText?: string;
}

export interface SnapshotManifestWireEntry {
  key: string;
  sourceSha256: string;
  sourceArtifactSha256?: string;
  semantic?: boolean;
  typographyRef: number;
  maxWidthPx: number;
  fontFaceEvidence: SnapshotManifestWireEvidence[];
  renderArtifactSha256: string;
}

export interface SnapshotFontReplayWire {
  revision: string;
  encoding: string;
  shapes: ShapeReplayWireRow[];
}

export interface SnapshotFontReplay {
  revision: string;
  shapes: ReplayShapeItem[];
  metrics: ReplayMetricItem[];
}

export interface SnapshotTablesPin {
  snapshot: string;
}

export interface SnapshotManifestWire {
  schema: number;
  tables: SnapshotTablesPin;
  layoutRevision?: string;
  renderRevision?: string;
  fontSourcePolicy?: string;
  paragraphSelector?: string;
  renderFontFamilies?: string[];
  entrySource?: string;
  fontReplay?: SnapshotFontReplayWire | null;
  entries: SnapshotManifestWireEntry[];
  fontContractEntries?: SnapshotManifestWireEntry[];
}

export interface ExpandedSnapshotManifest {
  schema: number;
  tables: SnapshotTablesPin;
  layoutRevision?: string;
  renderRevision?: string;
  fontSourcePolicy?: string;
  paragraphSelector?: string;
  renderFontFamilies?: string[];
  entrySource?: string;
  fontReplay?: SnapshotFontReplay;
  valueStyles: string[];
  entries: SnapshotManifestEntry[];
  fontContractEntries?: SnapshotManifestEntry[];
}

type ResolveProbe = (evidence: SnapshotManifestWireEvidence) => SnapshotProbe | undefined;

function expandReplayShapes(shapes: ShapeReplayWireRow[], stringAt: StringAt): ReplayShapeItem[] {
  return shapes.map((row) => decodeShapeReplayRow(row, stringAt));
}

/** Expands entry rows against the snapshot-table accessors. */
function expandManifestEntries(
  entries: SnapshotManifestWireEntry[],
  typographyAt: SnapshotTableBinaryView["typographyAt"],
  faceAt: SnapshotTableBinaryView["faceAt"],
  evidenceVersions: SnapshotManifestEvidenceVersions,
  resolveProbe: ResolveProbe,
): SnapshotManifestEntry[] {
  return entries.map((entry) => {
    const typography = typographyAt(entry?.typographyRef) as SnapshotTypographyRecord;
    if (!typography || typeof typography.sha256 !== "string" || !typography.value) {
      throw new Error("SnapshotTypographyTableInvalid");
    }
    if (!Array.isArray(entry.fontFaceEvidence) || entry.fontFaceEvidence.length === 0) {
      throw new Error("SnapshotFontEvidenceReferenceInvalid");
    }
    const faces = entry.fontFaceEvidence.map((evidence) => ({
      ...faceAt(evidence?.faceRef) as SnapshotManifestFace,
      coverageText: evidence.coverageText,
      probe: resolveProbe(evidence),
    }));
    return {
      key: entry.key,
      sourceSha256: entry.sourceSha256,
      ...(typeof entry.sourceArtifactSha256 === "string"
        ? { sourceArtifactSha256: entry.sourceArtifactSha256 }
        : {}),
      ...(entry.semantic === true ? { semantic: true } : {}),
      typographySha256: typography.sha256,
      typography: typography.value,
      maxWidthPx: entry.maxWidthPx,
      fontEvidence: { ...evidenceVersions, faces },
      renderArtifactSha256: entry.renderArtifactSha256,
    };
  });
}

/** View to its replay-metric rows; one build's expansions share the mapping. */
const replayMetricsByView = new WeakMap<SnapshotTableBinaryView, ReplayMetricItem[]>();

function replayMetricsOf(view: SnapshotTableBinaryView): ReplayMetricItem[] {
  let metrics = replayMetricsByView.get(view);
  if (metrics === undefined) {
    metrics = view.metricRows().map((row) => decodeMetricReplayRow(row));
    replayMetricsByView.set(view, metrics);
  }
  return metrics;
}

/**
 * The table view the expansion reads: the accessor surface
 * `snapshotTablesFromBytes` builds from the binary file. Any other shape
 * fails closed instead of failing on a missing method later.
 */
function tableViewOf(tables: SnapshotTableBinaryView): SnapshotTableBinaryView {
  if (typeof tables.stringAt !== "function" || typeof tables.metricRows !== "function" ||
      typeof tables.probeAt !== "function" || typeof tables.typographyAt !== "function" ||
      typeof tables.faceAt !== "function" || typeof tables.valueStyles !== "function" ||
      typeof tables.revisions !== "function") {
    throw new Error("SnapshotTablesInvalid");
  }
  return tables;
}

/**
 * Expands the compact transport into the canonical runtime manifest shape.
 * Integer references resolve through the snapshot table the transport loaded
 * and verified against `manifest.tables.snapshot`; a manifest without the
 * tables pin is not a shape this build reads. Replay shapes pick up the table
 * string region, metrics come from the table, and value styles splice in so
 * the style-installation site reads one shape.
 */
export function expandSnapshotManifest(
  manifest: SnapshotManifestWire,
  tables: SnapshotTableBinaryView | null = null,
): ExpandedSnapshotManifest {
  if (!manifest || typeof manifest !== "object" || Array.isArray(manifest)) {
    throw new Error("SnapshotManifestInvalid");
  }
  if (manifest.tables == null) throw new Error("SnapshotManifestTablesInvalid");
  if (tables == null) throw new Error("SnapshotTablesMissing");
  const view = tableViewOf(tables);
  if (typeof manifest.tables !== "object" || Array.isArray(manifest.tables) ||
      typeof manifest.tables.snapshot !== "string") {
    throw new Error("SnapshotManifestTablesInvalid");
  }
  const replay = manifest.fontReplay;
  if (replay != null &&
      (replay.revision !== FONT_REPLAY_REVISION ||
       replay.encoding !== FONT_REPLAY_TRANSPORT || !Array.isArray(replay.shapes))) {
    throw new Error("SnapshotFontReplayInvalid");
  }
  const fontReplay: SnapshotFontReplay | undefined = replay == null
    ? undefined
    : {
      revision: replay.revision,
      shapes: expandReplayShapes(replay.shapes, view.stringAt),
      metrics: replayMetricsOf(view),
    };
  const evidenceVersions: SnapshotManifestEvidenceVersions = {
    backendRevision: view.revisions().backendRevision,
    harfbuzzVersion: view.revisions().harfbuzzVersion,
  };
  const resolveProbe: ResolveProbe = (evidence) => evidence?.probeRef == null
    ? undefined
    : view.probeAt(evidence.probeRef);
  const expandEntries = (entries: SnapshotManifestWireEntry[]): SnapshotManifestEntry[] =>
    expandManifestEntries(
      entries,
      view.typographyAt,
      view.faceAt,
      evidenceVersions,
      resolveProbe,
    );
  const entries = expandEntries(manifest.entries);
  const fontContractEntries = Array.isArray(manifest.fontContractEntries)
    ? expandEntries(manifest.fontContractEntries)
    : undefined;
  // Split off the wire-typed fields so only the expanded versions reach the
  // result; the remaining wire fields share the target shapes.
  const {
    entries: wireEntries,
    fontContractEntries: wireFontContractEntries,
    fontReplay: wireFontReplay,
    ...sharedManifestFields
  } = manifest;
  return {
    ...sharedManifestFields,
    ...(fontReplay ? { fontReplay } : {}),
    valueStyles: view.valueStyles(),
    entries,
    ...(fontContractEntries ? { fontContractEntries } : {}),
  };
}

export function parseSnapshotManifest(
  text: string,
  tables: SnapshotTableBinaryView | null = null,
): ExpandedSnapshotManifest {
  return expandSnapshotManifest(JSON.parse(text) as SnapshotManifestWire, tables);
}
