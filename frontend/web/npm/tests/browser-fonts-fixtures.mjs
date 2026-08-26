// Shared browser-font-session fixtures: manifest and face-evidence builders,
// a stub snapshot root, and a loader harness. Extracted verbatim from
// browser-fonts.test.mjs so the timing-golden suite can prepare real session
// handles for the worker message-order journeys.

import { createHash } from "node:crypto";

import { createBrowserFontSessionLoader } from "@tiqian/core/core/measurement/browser-fonts.js";
import {
  FONT_BACKEND_REVISION,
  FONT_REPLAY_REVISION,
} from "@tiqian/core/snapshot-schema.js";
import { writeBinaryTable } from "@tiqian/core/table-binary-writer.mjs";

export function digest(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

/**
 * Snapshot tables of the manifests the fixtures build. Each manifest pins its
 * own table; the global fetch stub serves the bytes by URL so the transport
 * walks the same path a host page uses.
 */
let tableCounter = 0;
let currentTable = null;
const tableBytesByUrl = new Map();
const chainFetch = globalThis.fetch;
globalThis.fetch = async (url, init) => {
  const bytes = tableBytesByUrl.get(String(url));
  if (bytes != null) return { ok: true, arrayBuffer: async () => bytes };
  return chainFetch(url, init);
};

export function getCurrentTable() {
  return currentTable;
}

export function faceEvidence(sourceSha256, overrides = {}) {
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
    },
    ...overrides,
  };
}

export function manifestWithFaces(
  facesByEntry,
  versions = facesByEntry.map(() => "fixture-hb"),
  typography = {},
  extras = {},
) {
  const descriptors = [];
  const descriptorIndexes = new Map();
  const probes = [];
  const fontFaceEvidence = facesByEntry.map((faces) => faces.map((face) => {
    const descriptor = Object.fromEntries(Object.entries(face).filter(([key]) =>
      key !== "coverageText" && key !== "probe"));
    const signature = JSON.stringify(descriptor);
    let faceRef = descriptorIndexes.get(signature);
    if (faceRef == null) {
      faceRef = descriptors.length;
      descriptors.push(descriptor);
      descriptorIndexes.set(signature, faceRef);
    }
    const probe = { features: [], ...face.probe };
    const probeSignature = JSON.stringify(probe);
    let probeRef = probes.findIndex((existing) => JSON.stringify(existing) === probeSignature);
    if (probeRef < 0) {
      probeRef = probes.length;
      probes.push(probe);
    }
    return { faceRef, coverageText: face.coverageText, probeRef };
  }));
  // Replay and metric strings intern at the head of the table strings, the
  // order the encoder writes; probe strings follow through the writer.
  const replayStrings = [];
  const replayStringRef = (text) => {
    const existing = replayStrings.indexOf(text);
    if (existing >= 0) return existing;
    replayStrings.push(text);
    return replayStrings.length - 1;
  };
  const shapeRows = (extras.replayShapes ?? []).map((shape) => {
    const [displayText, serializedFamilies, fontWeight, italic, locale, role, sourceText] =
      JSON.parse(shape.key);
    return [
      replayStringRef(displayText),
      replayStringRef(serializedFamilies),
      fontWeight,
      italic ? 1 : 0,
      replayStringRef(locale),
      replayStringRef(role),
      replayStringRef(sourceText),
      replayStringRef(shape.result.faceId),
      replayStringRef(shape.result.fontInstanceId),
      replayStringRef(shape.result.script),
      shape.result.features.map(replayStringRef),
      shape.result.unsafeBreakCount,
      shape.result.advanceEm,
      shape.result.glyphs.flatMap((glyph) => [
        glyph.id, glyph.advanceEm, glyph.xEm, glyph.yEm,
        ...(glyph.boundsEm ?? [null, null, null, null]),
      ]),
    ];
  });
  const metricRows = (extras.replayMetrics ?? []).map((metric) => {
    const [serializedFamilies, fontWeight, italic, role, faceSelectionText] =
      JSON.parse(metric.key);
    return {
      serializedFamilies,
      fontWeight,
      italic,
      role,
      faceSelectionText,
      valuesEm: metric.valuesEm,
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
      harfbuzzVersion: versions[0],
    },
  });
  const url = `https://tables.test/fixture-${tableCounter += 1}.tiqtbl`;
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

export function snapshotRoot(manifest, documentOverrides = {}) {
  const script = { textContent: JSON.stringify(manifest) };
  const template = {
    content: {
      querySelector(selector) {
        return selector === "[data-tq-snapshot-manifest]" ? script : null;
      },
    },
  };
  const documentObject = {
    baseURI: "https://example.test/blog/post/",
    getElementById(id) {
      return id === "tq-page" ? template : null;
    },
    ...documentOverrides,
  };
  return {
    ownerDocument: documentObject,
    getAttribute(name) {
      if (name === "snapshot-ref") return "tq-page";
      if (name === "tq-tables" && currentTable) return currentTable.url;
      return null;
    },
  };
}

export function harness(manifest, options = {}) {
  const bytes = options.bytes ?? new TextEncoder().encode("fixture-font-source");
  const requests = [];
  const createCalls = [];
  const sessions = [];
  const contractCalls = [];
  const preparedContractCalls = [];
  const renderFaceCreates = [];
  const renderFaceAdds = [];
  const renderFaceDeletes = [];
  const renderFontSourceCreates = [];
  const renderFontSourceReleases = [];
  const fontLoads = [];
  let closeCount = 0;
  const fetch = async (url, init) => {
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
  const createFontSession = async (specs, createOptions) => {
    createCalls.push({ specs, options: createOptions });
    if (options.createError) throw options.createError;
    const session = {
      id: `browser-session-${createCalls.length}`,
      backendRevision: options.backendRevision ?? FONT_BACKEND_REVISION,
      harfbuzzVersion: options.harfbuzzVersion ?? "fixture-hb",
      faces: createOptions.faceMetadata.map((face) => ({
        ...face,
        weight: [...face.weight],
        axisTags: Object.keys(face.axes ?? {}).sort(),
        localNames: [...face.localNames],
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
    async load(descriptor, text) {
      fontLoads.push({ descriptor, text });
      return [{}];
    },
  };
  const createRenderFontFace = options.createRenderFontFace ?? ((family, source, descriptors) => {
    if (options.renderFaceCreateError) throw options.renderFaceCreateError;
    const face = {
      family,
      source,
      descriptors,
      status: "unloaded",
      async load() {
        if (options.renderFaceLoadError) throw options.renderFaceLoadError;
        this.status = "loaded";
        return this;
      },
    };
    renderFaceCreates.push(face);
    return face;
  });
  const createRenderFontSource = options.createRenderFontSource ?? ((source) => {
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
    sha256: async (value) => digest(value),
    createRenderFontFace,
    createRenderFontSource,
    validateContract: async (root) => {
      contractCalls.push(root);
      const result = options.contractResults?.shift?.();
      return result ?? { matches: true, reason: null };
    },
    ...(options.preparedContractResults ? {
      validatePreparedContract: async (root) => {
        preparedContractCalls.push(root);
        return options.preparedContractResults.shift() ?? { matches: true, reason: null };
      },
    } : {}),
  });
  return {
    loader,
    root: snapshotRoot(manifest, { ...options.documentOverrides, fonts: fontSet }),
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
