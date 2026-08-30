import { createServerReplayFontSession } from "../../measurement/browser-font-replay.js";
import type { ServerReplayFontSession } from "../../measurement/browser-font-replay.js";
import { createOffscreenCanvasMeasureAdapter } from "../../measurement/replay-probe.js";
import type { ProbeMeasure } from "../../measurement/replay-probe.js";
import { parseSnapshotManifest } from "../../sampler/snapshot/snapshot-manifest.js";
import type { SnapshotManifestFace } from "../../sampler/snapshot/snapshot-manifest.js";
import { FONT_REPLAY_REVISION } from "../../sampler/snapshot/snapshot-schema.js";
import { snapshotTablesFromBytes } from "../../sampler/snapshot/snapshot-tables.js";
import type { SnapshotTableBinaryView } from "../../sampler/snapshot/snapshot-table-binary.js";

/**
 * Worker session bootstrap (ADR 0053 A5c).
 *
 * `createManifestFontSession` is the manifest-driven builder moved verbatim
 * from layout-worker.js: baked worker sessions keep their behavior unchanged.
 * `createProbeBootstrapFontSession` adds the no-bake session form: the session
 * starts from empty replay tables and every table entry is backfilled at
 * runtime through the CanvasMeasureReplayProbe (ADR 0053 A5b), so no snapshot
 * bytes are required up front.
 */

export interface ProbeBootstrapOptions {
  measureAdapter?: ProbeMeasure | null;
}

export function createManifestFontSession(
  manifestText: string,
  tablesBytes: Uint8Array | null | undefined,
  sessionKey: string,
): Promise<ServerReplayFontSession> {
  // The coordinator verified the table bytes against the manifest pin before
  // handing them over; decoding revalidates the shape for the worker context.
  const tables: SnapshotTableBinaryView | null = tablesBytes instanceof Uint8Array && tablesBytes.length > 0
    ? snapshotTablesFromBytes(tablesBytes)
    : null;
  const manifest = parseSnapshotManifest(manifestText, tables);
  const entries = [...(manifest.entries ?? []), ...(manifest.fontContractEntries ?? [])];
  const evidence = entries.flatMap((entry) => entry?.fontEvidence?.faces ?? []);
  if (evidence.length === 0 || !manifest.fontReplay) {
    throw new Error("LayoutWorkerFontContractInvalid");
  }
  const faces: SnapshotManifestFace[] = [];
  const seen: Set<string> = new Set();
  for (const face of evidence) {
    const key = JSON.stringify([
      face.sfntSha256,
      face.faceIndex,
      face.sourceOrder,
      face.family,
      face.style,
      face.weight,
      face.unicodeRange,
      face.publicUrl,
    ]);
    if (seen.has(key)) continue;
    seen.add(key);
    faces.push(face);
  }
  faces.sort((left, right) => Number(left.sourceOrder) - Number(right.sourceOrder));
  const first = entries.find((entry) => entry?.fontEvidence)?.fontEvidence;
  return createServerReplayFontSession(
    faces.map(() => ({})),
    {
      sessionPrefix: `tq-worker-${sessionKey}`,
      replay: manifest.fontReplay,
      faceMetadata: faces,
      harfbuzzVersion: first?.harfbuzzVersion ?? "",
    },
  );
}

export function createProbeBootstrapFontSession(
  sessionKey: string,
  options: ProbeBootstrapOptions = {},
): Promise<ServerReplayFontSession> {
  const adapter = options.measureAdapter === undefined
    ? createOffscreenCanvasMeasureAdapter()
    : options.measureAdapter;
  if (adapter == null || typeof adapter !== "function") {
    throw new Error("LayoutWorkerProbeUnavailable");
  }
  return createServerReplayFontSession([], {
    sessionPrefix: `tq-worker-nobake-${sessionKey}`,
    replay: { revision: FONT_REPLAY_REVISION, shapes: [], metrics: [] },
    faceMetadata: [],
    harfbuzzVersion: "",
    probe: { measure: adapter },
  });
}

