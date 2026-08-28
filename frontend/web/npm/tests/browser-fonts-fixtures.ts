// Shared browser-font-session fixtures: manifest and face-evidence builders,
// a stub snapshot root, and a loader harness. Extracted verbatim from
// browser-fonts.test.mjs so the timing-golden suite can prepare real session
// handles for the worker message-order journeys.

import { createHash } from "node:crypto";

import { createBrowserFontSessionLoader, type BrowserFontSessionLoaderOptions } from "@tiqian/core/core/measurement/browser-fonts.js";
import {
  FONT_BACKEND_REVISION,
  FONT_REPLAY_REVISION,
} from "@tiqian/core/snapshot-schema.js";
import { writeBinaryTable } from "@tiqian/core/table-binary-writer.mjs";

interface FixtureBrowserFontSessionLoaderOptions extends BrowserFontSessionLoaderOptions {
  fetch: (url: string | URL, init?: RequestInit) => Promise<Response>;
  sha256: (value: string | Uint8Array) => Promise<string>;
  createRenderFontFace: (family: string, source: string, descriptors: Record<string, unknown>) => Promise<Record<string, unknown>>;
  createRenderFontSource: (source: string) => { source: string; release: () => boolean };
}

export function digest(bytes: Uint8Array | string): string {
  return createHash("sha256").update(bytes).digest("hex");
}

/**
 * Snapshot tables of the manifests the fixtures build. Each manifest pins its
 * own table; the global fetch stub serves the bytes by URL so the transport
 * walks the same path a host page uses.
 */
let tableCounter = 0;
let currentTable: { url: string; bytes: Uint8Array; sha256: string } | null = null;
const tableBytesByUrl = new Map<string, Uint8Array>();
const chainFetch = globalThis.fetch;
globalThis.fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
  const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url;
  const bytes = tableBytesByUrl.get(url);
  if (bytes != null) {
    const arrayBuffer = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer;
    return new Response(new Blob([new Uint8Array(arrayBuffer)]), { status: 200, statusText: "OK" });
  }
  return chainFetch(input, init);
};

export function getCurrentTable(): { url: string; bytes: Uint8Array; sha256: string } | null {
  return currentTable;
}

interface FaceEvidenceOverrides {
  weight?: number[];
  fontWeight?: number;
  [key: string]: unknown;
}

export function faceEvidence(sourceSha256: string, overrides: FaceEvidenceOverrides = {}): Record<string, unknown> {
  const weight = overrides.weight ?? [100, 900];
  const fontWeight = overrides.fontWeight ?? 400;
  return {
    family: "Fixture CJK",
    style: "normal",
    weight,
    unicodeRange: "U+0000-9FFF",
    publicUrl: "/assets/fixture-deadbeef.woff2",
    sourceSha256,
    sfntSha256: "b".repeat(64),
    faceIndex: 0,
    sourceOrder: 0,
    axes: { wght: fontWeight },
    localNames: ["Fixture CJK", "FixtureCJK"],
    coverageText: "中国",
    probe: {
      text: "中国",
      advancePx: 36,
      fontSizePx: 18,
      fontWeight,
      italic: false,
      script: "Hani",
      language: "zh-Hans",
      features: [],
    },
    ...overrides,
  };
}

interface ManifestWithFacesOptions {
  versions?: string[];
  typography?: Record<string, unknown>;
  extras?: {
    replayShapes?: Array<{ key: string; result: Record<string, unknown> }>;
    replayMetrics?: Array<{ key: string; valuesEm: Record<string, unknown> }>;
    backendRevision?: string;
  };
}

export function manifestWithFaces(
  facesByEntry: Record<string, unknown>[][],
  options: ManifestWithFacesOptions = {}
): Record<string, unknown> {
  const { versions, typography = {}, extras = {} } = options;
  const descriptorIndexes = new Map<string, number>();
  const descriptors: Record<string, unknown>[] = [];
  const probes: Array<{
    text: string;
    advancePx: number;
    fontSizePx: number;
    fontWeight: number;
    italic: boolean;
    script: string;
    language: string;
    features: string[];
  }> = [];
  const fontFaceEvidence = facesByEntry.map((faces) =>
    faces.map((face) => {
      const descriptor = Object.fromEntries(
        Object.entries(face).filter(([key]) => key !== "coverageText" && key !== "probe")
      );
      const signature = JSON.stringify(descriptor);
      let faceRef = descriptorIndexes.get(signature);
      if (faceRef == null) {
        faceRef = descriptors.length;
        descriptors.push(descriptor);
        descriptorIndexes.set(signature, faceRef);
      }
      const probe = {
        features: [] as string[],
        ...(face.probe as {
          text: string;
          advancePx: number;
          fontSizePx: number;
          fontWeight: number;
          italic: boolean;
          script: string;
          language: string;
          features?: string[];
        }),
      };
      const probeSignature = JSON.stringify(probe);
      let probeRef = probes.findIndex((existing) => JSON.stringify(existing) === probeSignature);
      if (probeRef < 0) {
        probeRef = probes.length;
        probes.push(probe);
      }
      return { faceRef, coverageText: face.coverageText, probeRef };
    })
  );

  // Replay and metric strings intern at the head of the table strings, the
  // order the encoder writes; probe strings follow through the writer.
  const replayStrings: string[] = [];
  const replayStringRef = (text: string): number => {
    const existing = replayStrings.indexOf(text);
    if (existing >= 0) return existing;
    replayStrings.push(text);
    return replayStrings.length - 1;
  };

  const shapeRows = (extras.replayShapes ?? []).map((shape) => {
    const [
      displayText,
      serializedFamilies,
      fontWeight,
      italic,
      locale,
      role,
      sourceText,
    ] = JSON.parse(shape.key) as [string, string, number, number, string, string, string];
    return [
      replayStringRef(displayText),
      replayStringRef(serializedFamilies),
      fontWeight,
      italic ? 1 : 0,
      replayStringRef(locale),
      replayStringRef(role),
      replayStringRef(sourceText),
      replayStringRef(shape.result.faceId as string),
      replayStringRef(shape.result.fontInstanceId as string),
      replayStringRef(shape.result.script as string),
      (shape.result.features as string[]).map(replayStringRef),
      shape.result.unsafeBreakCount as number,
      shape.result.advanceEm as number,
      (shape.result.glyphs as Array<Record<string, unknown>>).flatMap((glyph) => [
        glyph.id,
        glyph.advanceEm,
        glyph.xEm,
        glyph.yEm,
        ...(glyph.boundsEm as [unknown, unknown, unknown, unknown] ?? [null, null, null, null]),
      ]),
    ];
  });

  const metricRows = (extras.replayMetrics ?? []).map((metric) => {
    const [
      serializedFamilies,
      fontWeight,
      italic,
      role,
      faceSelectionText,
    ] = JSON.parse(metric.key) as [string, number, number, string, string];
    return {
      serializedFamilies,
      fontWeight,
      italic: italic !== 0,
      role,
      faceSelectionText,
      valuesEm: Array.isArray(metric.valuesEm) ? metric.valuesEm : Object.values(metric.valuesEm) as (number | null)[],
    };
  });

  for (const row of metricRows) {
    replayStringRef(row.serializedFamilies);
    replayStringRef(row.role);
    replayStringRef(row.faceSelectionText);
  }

  const tableBytes = writeBinaryTable({
    replayStrings,
    metrics: metricRows,
    probes,
    typographies: [{ sha256: "fixture", value: typography }],
    faces: descriptors,
    valueStyles: [],
    fontPreloads: [],
    revisions: {
      backendRevision: extras.backendRevision ?? FONT_BACKEND_REVISION,
      harfbuzzVersion: versions?.[0] ?? "fixture-hb",
    },
  });

  const url = `https://tables.test/fixture-${(tableCounter += 1)}.tiqtbl`;
  tableBytesByUrl.set(url, tableBytes);
  currentTable = { url, bytes: tableBytes, sha256: digest(tableBytes) };

  return {
    schema: 2,
    tables: { snapshot: currentTable.sha256 },
    layoutRevision: "tiqian-layout-v2",
    renderRevision: "prebroken-dom-v16",
    fontSourcePolicy: "host-compatible-stylesheet-v1",
    renderFontFamilies: ["Fixture CJK"],
    paragraphSelector: "p[data-tq-snapshot-key]",
    fontReplay: {
      revision: FONT_REPLAY_REVISION,
      encoding: "shared-strings-v1",
      shapes: shapeRows,
    },
    entries: facesByEntry.map((_faces, index) => ({
      key: `p-${index + 1}`,
      typographyRef: 0,
      fontFaceEvidence: fontFaceEvidence[index],
    })),
  };
}

interface SnapshotRootOptions {
  documentOverrides?: Record<string, unknown> & {
    fonts?: {
      load(descriptor: unknown, text: string): Promise<unknown[]>;
    };
  };
}

export function snapshotRoot(
  manifest: Record<string, unknown>,
  options: SnapshotRootOptions = {}
): {
  ownerDocument: {
    baseURI: string;
    getElementById(id: string): { content: { querySelector(selector: string): { textContent: string } | null } } | null;
  };
  getAttribute(name: string): string | null;
} {
  const { documentOverrides = {} } = options;
  const script = { textContent: JSON.stringify(manifest) };
  const template = {
    content: {
      querySelector(selector: string) {
        return selector === "[data-tq-snapshot-manifest]" ? script : null;
      },
    },
  };
  const documentObject = {
    baseURI: "https://example.test/blog/post/",
    getElementById(id: string) {
      return id === "tq-page" ? template : null;
    },
    ...documentOverrides,
  };
  return {
    ownerDocument: documentObject,
    getAttribute(name: string) {
      if (name === "snapshot-ref") return "tq-page";
      if (name === "tq-tables" && currentTable) return currentTable.url;
      return null;
    },
  };
}

interface HarnessOptions {
  bytes?: Uint8Array;
  fetchError?: Error;
  fetchErrors?: Error[];
  responseOk?: boolean;
  responseStatus?: number;
  createError?: Error;
  mutateSession?: (session: Record<string, unknown>) => void;
  documentOverrides?: {
    fonts?: {
      load(descriptor: unknown, text: string): Promise<unknown[]>;
    };
  };
  renderFaceCreateError?: Error;
  renderFaceLoadError?: Error;
  createRenderFontFace?: (family: string, source: string, descriptors: Record<string, unknown>) => Promise<Record<string, unknown>>;
  createRenderFontSource?: (source: string) => { source: string; release: () => boolean };
  contractResults?: Array<{ matches: boolean; reason: string | null }>;
  preparedContractResults?: Array<{ matches: boolean; reason: string | null }>;
  useDefaultSession?: boolean;
  backendRevision?: string;
  harfbuzzVersion?: string;
}

export function harness(
  manifest: Record<string, unknown>,
  options: HarnessOptions = {}
): {
  loader: ReturnType<typeof createBrowserFontSessionLoader>;
  root: ReturnType<typeof snapshotRoot>;
  requests: Array<{ url: string | URL; init?: RequestInit }>;
  createCalls: Array<{ specs: unknown; options: unknown }>;
  sessions: Array<Record<string, unknown>>;
  contractCalls: Array<unknown>;
  preparedContractCalls: Array<unknown>;
  renderFaceCreates: Array<Record<string, unknown>>;
  renderFaceAdds: Array<unknown>;
  renderFaceDeletes: Array<unknown>;
  renderFontSourceCreates: Array<string>;
  renderFontSourceReleases: Array<string>;
  fontLoads: Array<{ descriptor: unknown; text: string }>;
  closeCount: () => number;
} {
  const bytes = options.bytes ?? new TextEncoder().encode("fixture-font-source");
  const requests: Array<{ url: string | URL; init?: RequestInit }> = [];
  const createCalls: Array<{ specs: unknown; options: unknown }> = [];
  const sessions: Array<Record<string, unknown>> = [];
  const contractCalls: Array<unknown> = [];
  const preparedContractCalls: Array<unknown> = [];
  const renderFaceCreates: Array<Record<string, unknown>> = [];
  const renderFaceAdds: Array<unknown> = [];
  const renderFaceDeletes: Array<unknown> = [];
  const renderFontSourceCreates: Array<string> = [];
  const renderFontSourceReleases: Array<string> = [];
  const fontLoads: Array<{ descriptor: unknown; text: string }> = [];
  let closeCount = 0;

  const fetch = async (url: string | URL, init?: RequestInit) => {
    requests.push({ url, init });
    const sequencedError = options.fetchErrors?.shift?.();
    if (sequencedError) throw sequencedError;
    if (options.fetchError) throw options.fetchError;
    return {
      ok: options.responseOk ?? true,
      status: options.responseStatus ?? 200,
      async arrayBuffer() {
        return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
      },
    };
  };

  const createFontSession = async (specs: unknown, createOptions: Record<string, unknown>) => {
    createCalls.push({ specs, options: createOptions });
    if (options.createError) throw options.createError;
    const session = {
      id: `browser-session-${createCalls.length}`,
      backendRevision: options.backendRevision ?? FONT_BACKEND_REVISION,
      harfbuzzVersion: options.harfbuzzVersion ?? "fixture-hb",
      faces: (createOptions.faceMetadata as Array<Record<string, unknown>>).map((face) => ({
        ...face,
        weight: [...(face.weight as number[])],
        axisTags: Object.keys(face.axes ?? {}).sort(),
        localNames: [...(face.localNames as string[])],
      })),
      close() {
        closeCount += 1;
      },
    };
    options.mutateSession?.(session);
    sessions.push(session);
    return session;
  };

  const fontSet = options.documentOverrides?.fonts ?? {
    async load(descriptor: unknown, text: string) {
      fontLoads.push({ descriptor, text });
      return [{}];
    },
  };

  const createRenderFontFace = options.createRenderFontFace ?? ((family: string, source: string, descriptors: Record<string, unknown>) => {
    if (options.renderFaceCreateError) throw options.renderFaceCreateError;
    const face = {
      family,
      source,
      descriptors,
      status: "unloaded",
      async load() {
        if (options.renderFaceLoadError) throw options.renderFaceLoadError;
        face.status = "loaded";
        return face;
      },
    };
    renderFaceCreates.push(face);
    return face;
  });

  const createRenderFontSource = options.createRenderFontSource ?? ((source: string) => {
    renderFontSourceCreates.push(source);
    const url = `blob:fixture-font-${renderFontSourceCreates.length}`;
    let released = false;
    return {
      source: `url("${url}")`,
      release() {
        if (released) return false;
        released = true;
        renderFontSourceReleases.push(url);
        return true;
      },
    };
  });

  const loader = createBrowserFontSessionLoader({
    ...(options.useDefaultSession ? {} : { createFontSession }),
    fetch,
    sha256: async (value: string | Uint8Array) => digest(value),
    createRenderFontFace,
    createRenderFontSource,
    validateContract: async (root: unknown) => {
      contractCalls.push(root);
      const result = options.contractResults?.shift?.();
      return result ?? { matches: true, reason: null };
    },
    ...(options.preparedContractResults
      ? {
          validatePreparedContract: async (root: unknown) => {
            preparedContractCalls.push(root);
            return options.preparedContractResults!.shift() ?? { matches: true, reason: null };
          },
        }
      : {}),
  } as FixtureBrowserFontSessionLoaderOptions);

  return {
    loader,
    root: snapshotRoot(manifest, { ...options.documentOverrides, fonts: fontSet } as SnapshotRootOptions),
    requests,
    createCalls,
    sessions,
    contractCalls,
    preparedContractCalls,
    renderFaceCreates,
    renderFaceAdds,
    renderFaceDeletes,
    renderFontSourceCreates,
    renderFontSourceReleases,
    fontLoads,
    closeCount: () => closeCount,
  };
}