import {
  FONT_REPLAY_REVISION,
  FONT_REPLAY_TRANSPORT,
} from "./snapshot-schema.js";
import {
  decodeMetricReplayRow,
  decodeShapeReplayRow,
} from "./replay-entry-codec.js";

function expandReplayShapes(shapes, stringAt) {
  return shapes.map((row) => decodeShapeReplayRow(row, stringAt));
}

/** Expands entry rows against the snapshot-table accessors. */
function expandManifestEntries(
  entries,
  typographyAt,
  faceAt,
  evidenceVersions,
  resolveProbe,
) {
  return entries.map((entry) => {
    const typography = typographyAt(entry?.typographyRef);
    if (!typography || typeof typography.sha256 !== "string" || !typography.value) {
      throw new Error("SnapshotTypographyTableInvalid");
    }
    if (!Array.isArray(entry.fontFaceEvidence) || entry.fontFaceEvidence.length === 0) {
      throw new Error("SnapshotFontEvidenceReferenceInvalid");
    }
    const faces = entry.fontFaceEvidence.map((evidence) => ({
      ...faceAt(evidence?.faceRef),
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
const replayMetricsByView = new WeakMap();

function replayMetricsOf(view) {
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
function tableViewOf(tables) {
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
export function expandSnapshotManifest(manifest, tables = null) {
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
  const fontReplay = replay == null
    ? undefined
    : {
      revision: replay.revision,
      shapes: expandReplayShapes(replay.shapes, view.stringAt),
      metrics: replayMetricsOf(view),
    };
  const evidenceVersions = {
    backendRevision: view.revisions().backendRevision,
    harfbuzzVersion: view.revisions().harfbuzzVersion,
  };
  const resolveProbe = (evidence) => evidence?.probeRef == null
    ? undefined
    : view.probeAt(evidence.probeRef);
  const expandEntries = (entries) => expandManifestEntries(
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
  return {
    ...manifest,
    ...(fontReplay ? { fontReplay } : {}),
    valueStyles: view.valueStyles(),
    entries,
    ...(fontContractEntries ? { fontContractEntries } : {}),
  };
}

export function parseSnapshotManifest(text, tables = null) {
  return expandSnapshotManifest(JSON.parse(text), tables);
}
