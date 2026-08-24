import {
  FONT_BACKEND_REVISION,
  FONT_REPLAY_REVISION,
  metricReplayKey,
  shapeReplayKey,
} from "./snapshot-schema.js";
import {
  scaleMetricReplayItem,
  scaleShapeReplayItem,
} from "./replay-entry-codec.js";
import type {
  ReplayMetricItem,
  ReplayShapeItem,
  ScaledShapeResult,
} from "./replay-entry-codec.js";
import {
  probeMetricReplayValues,
  probeShapeReplayResult,
} from "./replay-probe.js";
import type { ProbeMeasure } from "./replay-probe.js";
import type { SnapshotManifestFace } from "./snapshot-manifest.js";
import type { SnapshotProbe } from "./snapshot-table-binary.js";

const REGISTRY_KEY = Symbol.for(`org.tiqian.web.font-replay.${FONT_REPLAY_REVISION}`);

export interface ReplayProbe {
  measure: ProbeMeasure;
}

export interface ServerReplayFontReplay {
  revision: string;
  shapes: ReplayShapeItem[];
  metrics: ReplayMetricItem[];
}

export interface ServerReplayFontSessionOptions {
  replay?: ServerReplayFontReplay;
  probe?: ReplayProbe | null;
  faceMetadata?: SnapshotManifestFace[];
  sessionPrefix?: string;
  harfbuzzVersion?: string;
}

export interface ReplayFontSessionFace {
  family: string;
  style: string;
  weight: readonly number[];
  unicodeRange: string;
  publicUrl: string;
  sourceSha256: string;
  sfntSha256: string;
  faceIndex: number;
  sourceOrder: number;
  axes: Record<string, unknown>;
  localNames: readonly string[];
  coverageText?: string;
  probe?: SnapshotProbe | undefined;
}

export interface ServerReplayFontSession {
  id: string;
  backendRevision: string;
  harfbuzzVersion: string;
  faces: ReplayFontSessionFace[];
  close: SessionCloser;
}

interface ReplaySession {
  shapes: Map<string, ReplayShapeItem>;
  metrics: Map<string, ReplayMetricItem>;
  probe: ReplayProbe | null;
}

interface ReplayRegistry {
  sessions: Map<string, ReplaySession>;
  shapeResults: Map<number, ScaledShapeResult>;
  metricResults: Map<number, number[]>;
  nextSessionId: number;
  nextResultId: number;
}

type ReplayHandleNumberQuery = (handle: number) => number;
type ReplayHandleStringQuery = (handle: number) => string;
type ReplaySlotNumberQuery = (handle: number, index: number) => number;
type ReplaySlotStringQuery = (handle: number, index: number) => string;
type ReplayEdgeNumberQuery = (handle: number, index: number, edge: number) => number;
type ReplayHandleRelease = (handle: number) => void;
type SessionCloser = () => void;

export interface ServerReplayFontBackend {
  shape(
    sessionId: string,
    displayText: string,
    serializedFamilies: string,
    fontSize: number,
    fontWeight: number,
    italic: boolean,
    locale: string,
    role: string,
    sourceText?: string,
  ): number;
  shapeGlyphCount: ReplayHandleNumberQuery;
  shapeGlyphId: ReplaySlotNumberQuery;
  shapeGlyphAdvance: ReplaySlotNumberQuery;
  shapeGlyphX: ReplaySlotNumberQuery;
  shapeGlyphY: ReplaySlotNumberQuery;
  shapeGlyphBound: ReplayEdgeNumberQuery;
  shapeAdvance: ReplayHandleNumberQuery;
  shapeFaceId: ReplayHandleStringQuery;
  shapeFontInstanceId: ReplayHandleStringQuery;
  shapeScript: ReplayHandleStringQuery;
  shapeFeatureCount: ReplayHandleNumberQuery;
  shapeFeature: ReplaySlotStringQuery;
  shapeUnsafeBreakCount: ReplayHandleNumberQuery;
  releaseShape: ReplayHandleRelease;
  metrics(
    sessionId: string,
    serializedFamilies: string,
    fontSize: number,
    fontWeight: number,
    italic: boolean,
    role: string,
    faceSelectionText: string,
  ): number;
  metricValue: ReplaySlotNumberQuery;
  releaseMetrics: ReplayHandleRelease;
}

declare global {
  var __TiqianFontBackend: ServerReplayFontBackend;
  var __TiqianFontBackendReplayRegistry: ReplayRegistry;
  var __TiqianFontBackendRevision: string;
}

const registry: ReplayRegistry =
  (globalThis as Record<symbol, ReplayRegistry | undefined>)[REGISTRY_KEY] ??= {
    sessions: new Map(),
    shapeResults: new Map(),
    metricResults: new Map(),
    nextSessionId: 1,
    nextResultId: 1,
  };

function replayIndex<T extends { key: string }>(items: T[], kind: string): Map<string, T> {
  if (!Array.isArray(items)) throw new Error(`InvalidServerShapingReplay:${kind}`);
  const index = new Map<string, T>();
  for (const item of items) {
    if (!item || typeof item !== "object" || typeof item.key !== "string" || !item.key) {
      throw new Error(`InvalidServerShapingReplay:${kind}`);
    }
    if (index.has(item.key)) throw new Error(`ConflictingServerShapingReplay:${kind}`);
    index.set(item.key, item);
  }
  return index;
}

function installReplayBackend() {
  if (globalThis.__TiqianFontBackend) {
    if (globalThis.__TiqianFontBackendReplayRegistry === registry) return;
    throw new Error("FontBackendGlobalCollision");
  }
  globalThis.__TiqianFontBackendRevision = FONT_BACKEND_REVISION;
  globalThis.__TiqianFontBackendReplayRegistry = registry;
  globalThis.__TiqianFontBackend = {
    shape(
      sessionId: string,
      displayText: string,
      serializedFamilies: string,
      fontSize: number,
      fontWeight: number,
      italic: boolean,
      locale: string,
      role: string,
      sourceText: string = displayText,
    ): number {
      const session = registry.sessions.get(sessionId);
      if (!session) throw new Error(`UnknownFontSession:${sessionId}`);
      const key = shapeReplayKey(
        displayText,
        serializedFamilies,
        fontWeight,
        italic,
        locale,
        role,
        sourceText,
      );
      let item = session.shapes.get(key);
      if (!item && session.probe) {
        const probedResult = probeShapeReplayResult(
          { displayText, serializedFamilies, fontSize, fontWeight, italic },
          session.probe.measure,
        );
        if (probedResult) {
          item = { key, result: probedResult };
          session.shapes.set(key, item);
        }
      }
      if (!item) throw new Error(`MissingServerShapingReplay:shape:${key}`);
      const handle = registry.nextResultId++;
      registry.shapeResults.set(handle, scaleShapeReplayItem(item, fontSize));
      return handle;
    },
    shapeGlyphCount: (handle: number): number =>
      registry.shapeResults.get(handle)?.glyphs.length ?? 0,
    shapeGlyphId: (handle: number, index: number): number =>
      registry.shapeResults.get(handle)?.glyphs[index]?.id ?? 0,
    shapeGlyphAdvance: (handle: number, index: number): number =>
      registry.shapeResults.get(handle)?.glyphs[index]?.advance ?? 0,
    shapeGlyphX: (handle: number, index: number): number =>
      registry.shapeResults.get(handle)?.glyphs[index]?.x ?? 0,
    shapeGlyphY: (handle: number, index: number): number =>
      registry.shapeResults.get(handle)?.glyphs[index]?.y ?? 0,
    shapeGlyphBound(handle: number, index: number, edge: number): number {
      return registry.shapeResults.get(handle)?.glyphs[index]?.bounds?.[edge] ?? Number.NaN;
    },
    shapeAdvance: (handle: number): number => registry.shapeResults.get(handle)?.advance ?? 0,
    shapeFaceId: (handle: number): string => registry.shapeResults.get(handle)?.faceId ?? "",
    shapeFontInstanceId: (handle: number): string =>
      registry.shapeResults.get(handle)?.fontInstanceId ?? "",
    shapeScript: (handle: number): string => registry.shapeResults.get(handle)?.script ?? "",
    shapeFeatureCount: (handle: number): number =>
      registry.shapeResults.get(handle)?.features.length ?? 0,
    shapeFeature: (handle: number, index: number): string =>
      registry.shapeResults.get(handle)?.features[index] ?? "",
    shapeUnsafeBreakCount: (handle: number): number =>
      registry.shapeResults.get(handle)?.unsafeBreakCount ?? 0,
    releaseShape: (handle: number) => registry.shapeResults.delete(handle),
    metrics(
      sessionId: string,
      serializedFamilies: string,
      fontSize: number,
      fontWeight: number,
      italic: boolean,
      role: string,
      faceSelectionText: string,
    ): number {
      const session = registry.sessions.get(sessionId);
      if (!session) throw new Error(`UnknownFontSession:${sessionId}`);
      const key = metricReplayKey(
        serializedFamilies,
        fontWeight,
        italic,
        role,
        faceSelectionText,
      );
      let item = session.metrics.get(key);
      if (!item && session.probe) {
        const valuesEm = probeMetricReplayValues(
          { serializedFamilies, fontSize, fontWeight, italic, role },
          session.probe.measure,
        );
        if (valuesEm) {
          item = { key, valuesEm };
          session.metrics.set(key, item);
        }
      }
      if (!item) throw new Error(`MissingServerShapingReplay:metrics:${key}`);
      const handle = registry.nextResultId++;
      registry.metricResults.set(handle, scaleMetricReplayItem(item, fontSize));
      return handle;
    },
    metricValue: (handle: number, index: number): number =>
      registry.metricResults.get(handle)?.[index] ?? Number.NaN,
    releaseMetrics: (handle: number) => registry.metricResults.delete(handle),
  };
}

export async function createServerReplayFontSession(
  faceSpecs: unknown[],
  options: ServerReplayFontSessionOptions = {},
): Promise<ServerReplayFontSession> {
  const replay = options.replay;
  if (!replay || replay.revision !== FONT_REPLAY_REVISION) {
    throw new Error("ServerShapingReplayRevisionMismatch");
  }
  const shapes = replayIndex(replay.shapes, "shapes");
  const metrics = replayIndex(replay.metrics, "metrics");
  const probe = options.probe ?? null;
  if (probe !== null && (typeof probe !== "object" || typeof probe.measure !== "function")) {
    throw new Error("ServerShapingReplayProbeInvalid");
  }
  // ProbeBootstrapEmptyTableAllowance (ADR 0053 A5c): a probe-equipped session
  // may start from empty tables because the probe backfill is the only entry
  // source. Without a probe, empty tables stay a hard failure.
  if ((shapes.size === 0 || metrics.size === 0) && !probe) {
    throw new Error("ServerShapingReplayEmpty");
  }
  const faceMetadata = options.faceMetadata;
  if (!Array.isArray(faceSpecs) || !Array.isArray(faceMetadata) ||
      faceSpecs.length !== faceMetadata.length) {
    throw new Error("ServerShapingReplayFaceMismatch");
  }
  installReplayBackend();
  const prefix = String(options.sessionPrefix ?? "tq-browser-replay").trim() || "tq-browser-replay";
  const sessionId = `${prefix}-${registry.nextSessionId++}`;
  registry.sessions.set(sessionId, { shapes, metrics, probe });
  let closed = false;
  return Object.freeze({
    id: sessionId,
    backendRevision: FONT_BACKEND_REVISION,
    harfbuzzVersion: String(options.harfbuzzVersion ?? ""),
    faces: faceMetadata.map((face) => Object.freeze({
      ...face,
      weight: Object.freeze([...face.weight]),
      axisTags: Object.freeze(Object.keys(face.axes ?? {}).sort()),
      localNames: Object.freeze([...face.localNames]),
    })),
    close() {
      if (closed) return;
      closed = true;
      registry.sessions.delete(sessionId);
    },
  });
}
