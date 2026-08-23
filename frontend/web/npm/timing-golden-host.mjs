// Fake host and S1-S4 element timeline shared by the element-driven
// timing-golden journeys (timing-golden.test.mjs, decomposition report
// section 11) and the declared-face wake drive (element.test.mjs, ADR 0053
// E2). The drive runs the real element.js module against a hand-grafted
// fake DOM under the shared fake clock (test-clock.mjs) and records the
// JS-observable anchors: element and document event dispatches, dataset and
// attribute writes, ResizeObserver lifecycles, fetches, and per-phase
// paragraph state.
//
// Freeze boundary: frame counts and clock-derived durations are not stable
// across processes. element.js lazily imports its collaborators, those
// imports perform real I/O, and the interleaving between that I/O and the
// fake-clock pump varies per run; the number of frames an adoption chain
// needs therefore varies, and every duration the code computes from clock
// readings varies with it. The recorders below normalize those numbers away
// (durations dropped from event details, duration dataset values nulled) so
// the golden freezes structure: dispatch order, write order, keys, phases,
// and derived verdicts.

import {
  FakeElement,
  FakeFragment,
  FakeText,
  canonicalFixtureNode,
  fixtureComputedStyle,
  sha256,
  styleDeclaration,
} from "./snapshot-dom-fixtures.mjs";
import { FONT_REPLAY_REVISION, stableStringify } from "./snapshot-schema.js";
import { writeBinaryTable } from "./table-binary-writer.mjs";
import { setEngineOverride } from "./core/engine/loaders/runtime-loader.js";

// ADR 0053 C1 removed the internal document-level event channel, so the
// timing drive observes the engine call face directly. The stub records each
// call in phase order and answers like an engine with no work, matching the
// drive's previous world where the fake document dropped every listener. It
// is installed only for the duration of driveElementTimeline; sibling
// journeys in the same process (worker messages) keep the real engine.
let activeEngineRecord = null;
let activeEnginePhase = "";
const engineCall = (method, answer) => () => {
  if (activeEngineRecord) activeEngineRecord.push({ phase: activeEnginePhase, method });
  return answer;
};
const driveEngineStub = {
  enhance: engineCall("enhance", 0),
  enhanceProgressively: engineCall("enhanceProgressively"),
  enhanceAll: engineCall("enhanceAll", 0),
  destroy: engineCall("destroy"),
  detach: engineCall("detach"),
  relayout: engineCall("relayout"),
  refresh: engineCall("refresh"),
  cancelLayoutWork: engineCall("cancelLayoutWork"),
  probeContentDrift: engineCall("probeContentDrift", "null"),
  reconcileContent: engineCall("reconcileContent", "null"),
  workerLayoutRequest: engineCall("workerLayoutRequest", null),
};

export const FRAME_STEP_MS = 16;

// Globals the drive replaces beyond the fake clock's own set; the journey
// wrapper preserves them so every journey starts from a pristine host.
export const ELEMENT_DRIVE_GLOBALS = [
  "document", "HTMLElement", "customElements", "getComputedStyle",
  "MutationObserver", "window", "CustomEvent", "ResizeObserver",
  "TiqianWeb", "fetch", "__tiqianCopyHandlerInstalled", "IntersectionObserver",
];

const typography = {
  fontFamilies: ["Fixture CJK"],
  fontSizePx: 18,
  lineHeightPx: 27,
  locale: "zh-Hans",
  fontWeight: 400,
  italic: false,
  firstLineIndentIc: 0,
  lineLengthGridEnabled: true,
  letterSpacingPx: 0,
  fontFeatureSettings: "normal",
  fontVariationSettings: "normal",
  fontVariantNumeric: "normal",
};
const probe = {
  text: "中国",
  advancePx: 36,
  fontSizePx: 18,
  fontWeight: 400,
  italic: false,
  script: "Hani",
  language: "zh-Hans",
  features: [],
};
const evidence = {
  family: "Fixture CJK",
  style: "normal",
  weight: [400, 400],
  unicodeRange: "U+4E00-9FFF",
  publicUrl: "/assets/fixture-deadbeef.woff2",
  sourceSha256: "a".repeat(64),
  sfntSha256: "b".repeat(64),
  faceIndex: 0,
  sourceOrder: 0,
  axes: {},
  localNames: ["Fixture CJK", "FixtureCJK"],
  coverageText: "中国",
};

function buildWorld() {
  const tableBytesByUrl = new Map();
  const fetchCalls = [];
  const documentObject = {
    baseURI: "https://example.test/post/",
    elements: new Map(),
    styleSheets: [],
    listeners: new Map(),
    fonts: {
      load: async () => [{}],
      addEventListener() {},
      removeEventListener() {},
    },
    createDocumentFragment: () => new FakeFragment(),
    createElement(tagName) {
      const element = new FakeElement(tagName);
      element.ownerDocument = documentObject;
      element._fixtureProbeWidth = 36;
      return element;
    },
    createRange() {
      let selectedNode = null;
      return {
        selectNodeContents(node) { selectedNode = node; },
        getBoundingClientRect() { return { width: 36 }; },
      };
    },
    getElementById(id) { return documentObject.elements.get(id) ?? null; },
    querySelector() { return null; },
    querySelectorAll() { return []; },
    addEventListener() {},
    removeEventListener() {},
    dispatchEvent(event) {
      const listener = documentObject.listeners.get(event.type);
      if (listener) listener(event);
      return true;
    },
  };
  documentObject.body = documentObject.createElement("body");
  documentObject.head = documentObject.createElement("head");

  const realFetch = globalThis.fetch;
  globalThis.fetch = async (url, init) => {
    fetchCalls.push(String(url));
    const bytes = tableBytesByUrl.get(String(url));
    if (bytes != null) return { ok: true, arrayBuffer: async () => bytes };
    return realFetch(url, init);
  };

  return { documentObject, tableBytesByUrl, fetchCalls };
}

function buildSnapshot(world) {
  const { documentObject, tableBytesByUrl } = world;
  const root = documentObject.createElement("tiqian-prose");
  root.setAttribute("snapshot-ref", "tq-page");
  const paragraph = documentObject.createElement("p");
  paragraph.setAttribute("data-tq-snapshot-key", "p-1");
  paragraph.width = 360;
  paragraph.height = 27;
  paragraph.innerText = "中国";
  const originalText = new FakeText("中国");
  paragraph.appendChild(originalText);
  root.appendChild(paragraph);

  const template = documentObject.createElement("template");
  template.content = new FakeFragment();
  const entry = documentObject.createElement("div");
  entry.setAttribute("data-tq-entry", "p-1");
  const marker = documentObject.createElement("span");
  marker.setAttribute("data-tq-geometry", "true");
  marker.setAttribute("data-tq-line-flow-width", "36");
  marker.setAttribute("data-tq-line-width", "36");
  marker.setAttribute("data-tq-line-top", "0");
  marker.setAttribute("data-tq-line-bottom", "27");
  marker.setAttribute("data-tq-line-baseline", "20");
  marker.setAttribute("data-tq-paragraph-height", "27");
  marker.left = 0;
  marker.top = 0;
  marker.height = 27;
  const rendered = documentObject.createElement("span");
  rendered.setAttribute("data-tq-advance", "36");
  rendered.setAttribute("data-tq-geometry", "true");
  rendered.setAttribute("data-tq-shaping-boundary", "current-segment");
  rendered.setAttribute("data-tq-x", "0");
  rendered.width = 36;
  rendered.left = 0;
  rendered.textContent = "中国";
  const sentinel = documentObject.createElement("span");
  sentinel.setAttribute("data-tq-geometry", "true");
  sentinel.setAttribute("data-tq-line-end-sentinel", "0");
  sentinel.left = 36;
  sentinel.top = 20;
  entry.append(marker, rendered, sentinel);

  const tableBytes = writeBinaryTable({
    replayStrings: [],
    metrics: [],
    probes: [probe],
    typographies: [{
      sha256: sha256(stableStringify(typography)),
      value: typography,
    }],
    faces: [{
      ...Object.fromEntries(Object.entries(evidence).filter(([key]) =>
        key !== "coverageText")),
    }],
    valueStyles: [],
    fontPreloads: ["/assets/fixture-deadbeef.woff2"],
    revisions: { backendRevision: "tiqian-shared-harfbuzz-v5", harfbuzzVersion: "fixture" },
  });
  const tableUrl = "https://tables.test/timing-golden-1.tiqtbl";
  tableBytesByUrl.set(tableUrl, tableBytes);
  root.setAttribute("tq-tables", tableUrl);
  const manifest = {
    schema: 2,
    tables: { snapshot: sha256(tableBytes) },
    layoutRevision: "tiqian-layout-v2",
    renderRevision: "prebroken-dom-v15",
    fontSourcePolicy: "host-compatible-stylesheet-v1",
    renderFontFamilies: ["Fixture CJK"],
    paragraphSelector: "p[data-tq-snapshot-key]",
    fontReplay: { revision: FONT_REPLAY_REVISION, encoding: "shared-strings-v1", shapes: [] },
    entries: [{
      key: "p-1",
      sourceSha256: sha256("中国"),
      typographyRef: 0,
      maxWidthPx: 360,
      fontFaceEvidence: [{ faceRef: 0, coverageText: "中国", probeRef: 0 }],
      renderArtifactSha256: sha256(stableStringify(entry.childNodes.map(canonicalFixtureNode))),
    }],
  };
  const script = documentObject.createElement("script");
  script.setAttribute("data-tq-snapshot-manifest", "");
  script.textContent = JSON.stringify(manifest);
  template.content.append(script, entry);
  documentObject.elements.set("tq-page", template);

  const fontFaceStyle = styleDeclaration({
    "font-family": "\"Fixture CJK\"",
    "font-style": "normal",
    "font-weight": "400",
    "font-display": "block",
    "unicode-range": "U+4E00-9FFF",
    src: "url(\"/assets/fixture-deadbeef.woff2\")",
  });
  const sheet = { href: "https://example.test/fonts.css" };
  documentObject.styleSheets.push({
    href: sheet.href,
    cssRules: [{ type: 5, style: fontFaceStyle, parentStyleSheet: sheet }],
  });
  return { root, paragraph, originalText };
}

// Event-detail keys whose values are frame-derived durations (see the freeze
// boundary note at the top of this file); dropped before the detail is frozen.
const FRAME_DERIVED_DETAIL_KEYS = new Set(["durationMs", "maxSliceMs"]);

// Stable JSON-safe view of an event detail: numbers, booleans, strings only,
// minus the frame-derived duration keys.
function stableDetail(detail) {
  const out = {};
  for (const [key, value] of Object.entries(detail ?? {})) {
    if (FRAME_DERIVED_DETAIL_KEYS.has(key)) continue;
    if (typeof value === "number" && Number.isFinite(value)) out[key] = value;
    else if (typeof value === "boolean") out[key] = value;
    else if (typeof value === "string") out[key] = value;
  }
  return out;
}

class FakeCustomEvent {
  constructor(type, init = {}) {
    this.type = type;
    this.detail = init.detail;
    this.bubbles = init.bubbles;
    this.composed = init.composed;
  }
}

// Registry of ResizeObserver instances for the cache-invalidation projection.
// The class is module-level because element.js grabs it from globalThis; the
// registry is reset at each drive start so a journey records only its own
// observer activity.
let nextObserverId = 0;
const observerInstances = [];

class FakeResizeObserver {
  constructor(callback) {
    this.id = ++nextObserverId;
    this.callback = callback;
    this.log = [];
    this.observed = new Set();
    observerInstances.push(this);
  }
  observe(target) {
    this.log.push({ op: "observe", target });
    this.observed.add(target);
    const rect = target.getBoundingClientRect();
    queueMicrotask(() => this.callback([{
      target,
      contentRect: { width: rect.width, height: rect.height },
    }]));
  }
  unobserve(target) {
    this.log.push({ op: "unobserve", target });
    this.observed.delete(target);
  }
  disconnect() {
    this.log.push({ op: "disconnect" });
    this.observed.clear();
  }
}

// Drive the full S1-S4 element timeline and return the complete record:
// S1 connect + initial snapshot adoption, S2 width shrink past the snapshot
// table's only width, S3 mid-flight disconnect while the adoption chain is
// suspended, S4 reconnect at the original width.
// Shared element-drive setup: builds the fake world, grafts the real element
// onto the fixture root, installs every recorder, warms the lazy imports,
// and runs the S1 connect through "tiqian:ready". The timing-golden S1-S4
// journey and the declared-face wake drive both start from this state, so
// recorder semantics and the pump stay identical between them.
async function startElementDrive(clock, journeyKey) {
  nextObserverId = 0;
  observerInstances.length = 0;

  const world = buildWorld();
  const { root, paragraph } = buildSnapshot(world);
  const record = {
    engineCalls: [],
    elementEvents: [],
    documentEvents: [],
    datasetWrites: [],
    attributeWrites: [],
    fetchCalls: [],
    observerActivity: [],
    frameAdvanceCounts: {},
    paragraphStates: {},
  };
  let currentPhase = "s1-adopt";
  setEngineOverride(driveEngineStub);
  activeEngineRecord = record.engineCalls;
  activeEnginePhase = currentPhase;

  class FakeHostElement extends FakeElement {
    constructor() { super("tiqian-prose"); }
  }
  globalThis.HTMLElement = FakeHostElement;
  globalThis.customElements = { define() {}, get() { return undefined; } };
  globalThis.document = world.documentObject;
  let hostElement = null;
  globalThis.getComputedStyle = (element, pseudo) => {
    const values = fixtureComputedStyle(element, pseudo,
      element === hostElement ? { "--tq-styles-ready": "1" } : {});
    return { ...values, getPropertyValue: (name) => values[name] ?? "" };
  };
  globalThis.MutationObserver = class {
    observe() {}
    disconnect() {}
    takeRecords() { return []; }
  };
  globalThis.CustomEvent = FakeCustomEvent;
  globalThis.ResizeObserver = FakeResizeObserver;
  globalThis.window = { addEventListener() {}, removeEventListener() {} };
  delete globalThis.TiqianWeb;
  delete globalThis.__tiqianCopyHandlerInstalled;
  delete globalThis.IntersectionObserver;

  // rAF accounting wrapper so we can detect frame-loop quiescence.
  const origRaf = globalThis.requestAnimationFrame;
  const origCancel = globalThis.cancelAnimationFrame;
  const activeFrames = new Set();
  globalThis.requestAnimationFrame = (callback) => {
    const id = origRaf((now) => {
      activeFrames.delete(id);
      callback(now);
    });
    activeFrames.add(id);
    return id;
  };
  globalThis.cancelAnimationFrame = (id) => {
    activeFrames.delete(id);
    origCancel(id);
  };

  // The query string busts the module cache per journey: under the fake clock
  // a Date.now()-based string would be identical across journeys and the
  // second drive would silently reuse the first drive's module state.
  const module = await import(`./element.js?timing-golden=${journeyKey}`);
  const element = new module.TiqianProseElement();
  hostElement = element;
  element.attributes = root.attributes;
  element.childNodes = root.childNodes;
  element.ownerDocument = world.documentObject;
  paragraph.parentNode = element;
  paragraph.parentElement = element;
  element.width = 360;
  element.height = 27;
  element.isConnected = true;

  // Comma-aware querySelectorAll on the instance: the single-selector path
  // keeps FakeElement semantics, the multi-selector path walks the graft.
  const singleQuerySelectorAll = FakeElement.prototype.querySelectorAll;
  element.querySelectorAll = (selector) => {
    const parts = String(selector).split(",").map((part) => part.trim());
    if (parts.length === 1) return singleQuerySelectorAll.call(element, selector);
    const out = [];
    const visit = (node) => {
      for (const child of node.childNodes) {
        if (child.nodeType === 1) {
          for (const part of parts) {
            if (child.closest && child.closest(part) === child) { out.push(child); break; }
          }
        }
        visit(child);
      }
    };
    visit(element);
    return out;
  };

  const listeners = new Map();
  element.addEventListener = (name, listener) => { listeners.set(name, listener); };
  element.removeEventListener = (name, listener) => {
    if (listeners.get(name) === listener) listeners.delete(name);
  };
  element.dispatchEvent = (event) => {
    record.elementEvents.push({
      phase: currentPhase,
      type: event.type,
      detail: stableDetail(event.detail),
    });
    listeners.get(event.type)?.(event);
    return true;
  };

  const realDocDispatch = world.documentObject.dispatchEvent.bind(world.documentObject);
  world.documentObject.dispatchEvent = (event) => {
    record.documentEvents.push({ phase: currentPhase, type: event.type });
    return realDocDispatch(event);
  };

  const datasetTarget = {};
  const datasetWrites = record.datasetWrites;
  element.dataset = new Proxy(datasetTarget, {
    set(target, key, value) {
      if (String(key).startsWith("tiqian")) {
        // Duration-valued dataset keys share the frame-count instability;
        // keep the write itself (key, order) and drop the number.
        const stableValue = String(key).endsWith("Ms") ? null : String(value);
        datasetWrites.push({ phase: currentPhase, op: "set", key: String(key), value: stableValue });
      }
      return Reflect.set(target, key, value);
    },
    deleteProperty(target, key) {
      if (String(key).startsWith("tiqian")) {
        datasetWrites.push({ phase: currentPhase, op: "delete", key: String(key) });
      }
      return Reflect.deleteProperty(target, key);
    },
  });

  const realSetAttribute = FakeElement.prototype.setAttribute;
  const realRemoveAttribute = FakeElement.prototype.removeAttribute;
  element.setAttribute = (name, value) => {
    if (String(name).startsWith("data-tiqian-") || String(name).startsWith("data-tq-")) {
      record.attributeWrites.push({ phase: currentPhase, name: String(name), value: String(value) });
    }
    return realSetAttribute.call(element, name, value);
  };
  element.removeAttribute = (name) => {
    if (String(name).startsWith("data-tiqian-") || String(name).startsWith("data-tq-")) {
      record.attributeWrites.push({ phase: currentPhase, name: String(name), value: null });
    }
    return realRemoveAttribute.call(element, name);
  };

  const paragraphState = () => ({
    rendered: paragraph.getAttribute("data-tq-rendered"),
    canonicalSource: paragraph.getAttribute("data-tq-canonical-source"),
    canonicalPlain: paragraph.getAttribute("data-tq-canonical-plain"),
    firstChildNodeType: paragraph.firstChild?.nodeType ?? null,
    firstChildText: paragraph.firstChild?.textContent ?? null,
  });

  // The pump cap only guards against a hung drive; frame counts are excluded
  // from every golden projection, and node:test runs sibling files in
  // parallel, so a lazy-import compile can easily need more loop turns than
  // sixty setImmediate yields while the process is under load. A cap of a
  // few thousand turns keeps the hang guard without starving the import.
  const pumpUntil = async (predicate, cap = 2000) => {
    let frames = 0;
    while (frames < cap) {
      await new Promise((resolve) => setImmediate(resolve));
      clock.advance(FRAME_STEP_MS);
      frames += 1;
      if (predicate()) break;
    }
    return frames;
  };

  const pumpQuiescent = async (cap = 2000) => {
    let frames = 0;
    let quietStreak = 0;
    while (frames < cap) {
      await new Promise((resolve) => setImmediate(resolve));
      clock.advance(FRAME_STEP_MS);
      frames += 1;
      quietStreak = activeFrames.size === 0 ? quietStreak + 1 : 0;
      if (quietStreak >= 2) break;
    }
    return frames;
  };

  const widthObserver = () => {
    for (let i = observerInstances.length - 1; i >= 0; i -= 1) {
      if (observerInstances[i].observed.has(element)) return observerInstances[i];
    }
    return null;
  };

  // ---- S1: connect + initial snapshot adoption ----
  // Warm the dynamic imports element.js performs (worker-channel.js and the
  // browser-fonts module, both on the exact-session path) before the drive
  // starts. Cold, their disk reads race the fake-clock pump under parallel
  // test load and shift observer creation order per run; warm, the cache hits
  // keep the S1 tail deterministic. The warm imports run inside the journey's
  // fake-clock window, so any module top-level captures see the same doubles.
  await import("./core/engine/web-worker/worker-channel.js");
  await import("./core/measurement/browser-fonts.js");
  element.connectedCallback();
  record.frameAdvanceCounts.s1 = await pumpUntil(
    () => record.elementEvents.some((e) => e.type === "tiqian:ready" && e.phase === "s1-adopt"),
  );
  record.paragraphStates.s1 = paragraphState();

  const setPhase = (phase) => {
    currentPhase = phase;
    activeEnginePhase = phase;
  };

  return {
    world,
    record,
    element,
    paragraph,
    setPhase,
    pumpUntil,
    pumpQuiescent,
    widthObserver,
    paragraphState,
  };
}

export async function driveElementTimeline(clock, journeyKey) {
  const drive = await startElementDrive(clock, journeyKey);
  const {
    record, element, paragraph, setPhase,
    pumpUntil, pumpQuiescent, widthObserver, paragraphState,
  } = drive;

  // ---- S2: width shrink to 340, RO delivery, commit lane ----
  setPhase("s2-resize");
  element.width = 340;
  paragraph.width = 340;
  const observer = widthObserver();
  if (observer) {
    observer.callback([
      { target: element, contentRect: { width: 340, height: 27 } },
      { target: paragraph, contentRect: { width: 340, height: 27 } },
    ]);
  }
  record.frameAdvanceCounts.s2 = await pumpQuiescent();
  record.paragraphStates.s2 = paragraphState();

  // ---- S3: width 320, freeze the clock, disconnect ----
  setPhase("s3-midflight-disconnect");
  element.width = 320;
  paragraph.width = 320;
  const observer3 = widthObserver();
  if (observer3) {
    observer3.callback([
      { target: element, contentRect: { width: 320, height: 27 } },
      { target: paragraph, contentRect: { width: 320, height: 27 } },
    ]);
  }
  await new Promise((resolve) => setImmediate(resolve));
  element.isConnected = false;
  element.disconnectedCallback();
  await new Promise((resolve) => setImmediate(resolve));
  record.frameAdvanceCounts.s3 = await pumpQuiescent();
  record.paragraphStates.s3 = paragraphState();

  // ---- S4: reconnect at max width ----
  setPhase("s4-reconnect");
  element.width = 360;
  paragraph.width = 360;
  element.isConnected = true;
  element.connectedCallback();
  record.frameAdvanceCounts.s4 = await pumpUntil(
    () => record.elementEvents.some((e) => e.type === "tiqian:ready" && e.phase === "s4-reconnect"),
  );
  if (!record.elementEvents.some((e) => e.type === "tiqian:ready" && e.phase === "s4-reconnect")) {
    record.frameAdvanceCounts.s4 = await pumpQuiescent();
  }
  record.paragraphStates.s4 = paragraphState();

  // Observer activity projection (targets serialized by identity kind).
  record.observerActivity = observerInstances.map((instance) => ({
    id: instance.id,
    ops: instance.log.map((entry) => ({
      op: entry.op,
      target: entry.target === element ? "root" : entry.target === paragraph ? "paragraph" : "other",
    })),
  }));

  record.fetchCalls = drive.world.fetchCalls;
  activeEngineRecord = null;
  setEngineOverride(null);

  return record;
}

// Declared-face wake drive (ADR 0053 E2): the real element settles through
// S1, then DeclaredFaceEvidence registrations wake it. The engine stub
// records every revalidate the wakes produce, so the assertions read the
// element's own forced-check path end to end: registry notify, source
// subscription, scheduleTypographyCheck(true) through rAF dedup, snapshot
// invalidation, and the enhance dispatch that follows. Declared sheets
// never enter the CSSOM the signature reads, so the wake must force past
// the signature comparison; a wake that only scheduled an unforced check
// would leave the phase with no engine calls at all.
export async function driveDeclaredFaceWakeTimeline(clock, journeyKey) {
  const drive = await startElementDrive(clock, journeyKey);
  const { record, element, setPhase, pumpQuiescent } = drive;

  const revalidateCallsIn = (phase) =>
    record.engineCalls.filter((call) => call.phase === phase);

  // Executed-check observable: every typography-check signature read walks
  // root "p, li". Counting those queries per phase shows whether a wake
  // reached a scheduled check even when the refresh decision dedups against
  // in-flight work and produces no engine call.
  const paragraphQueriesByPhase = new Map();
  const realQuerySelectorAll = element.querySelectorAll;
  let queryPhase = "pre-wake";
  element.querySelectorAll = (selector) => {
    if (String(selector).includes("p") && String(selector).includes("li")) {
      paragraphQueriesByPhase.set(queryPhase, (paragraphQueriesByPhase.get(queryPhase) ?? 0) + 1);
    }
    return realQuerySelectorAll.call(element, selector);
  };

  const { declareTiqianFontFaces } = await import(
    "./core/sampler/snapshot/declared-faces.js"
  );

  const setWakePhase = (phase) => {
    setPhase(phase);
    queryPhase = phase;
  };

  // ---- W1: two same-tick declarations merge into one revalidate ----
  setWakePhase("w1-declared-merge");
  const unregisterA = declareTiqianFontFaces(
    "@font-face { font-family: Declared Wake A; src: url('wake-a.woff2'); }",
    { baseUrl: "https://declared.test/wake-a.css" },
  );
  const unregisterB = declareTiqianFontFaces(
    "@font-face { font-family: Declared Wake B; src: url('wake-b.woff2'); }",
    { baseUrl: "https://declared.test/wake-b.css" },
  );
  record.frameAdvanceCounts.w1 = await pumpQuiescent();
  record.declaredWake = { w1RevalidateCalls: revalidateCallsIn("w1-declared-merge").length };

  // ---- W2: a later declaration revalidates again ----
  // The W1 wake opened a layout job; typography observation pauses until the
  // work finishes. The stub engine never runs the job to completion, so
  // deliver the completion event the real runtime dispatches on the root.
  // The ready listener accepts it (#acceptLayoutCompletion was armed by the
  // W1 dispatch), finishes the layout work and re-observes. W2's declare
  // then meets an idle, observed root, exactly like a later declaration in
  // production.
  setWakePhase("w2-job-complete");
  element.dispatchEvent(new FakeCustomEvent("tiqian:relayout-ready", {
    bubbles: true,
    composed: true,
    detail: { enhancedCount: 1, issueCount: 0 },
  }));
  record.frameAdvanceCounts.w2JobComplete = await pumpQuiescent();

  setWakePhase("w2-declared-later");
  const unregisterC = declareTiqianFontFaces(
    "@font-face { font-family: Declared Wake C; src: url('wake-c.woff2'); }",
    { baseUrl: "https://declared.test/wake-c.css" },
  );
  record.frameAdvanceCounts.w2 = await pumpQuiescent();
  record.declaredWake.w2RevalidateCalls = revalidateCallsIn("w2-declared-later").length;

  // ---- W3: a disabled element unsubscribes and stops waking ----
  setWakePhase("w3-disabled");
  element.setAttribute("disabled", "");
  element.attributeChangedCallback("disabled", null, "");
  await pumpQuiescent();
  // Disabling itself reaches the engine (destroy, detach); snapshot the
  // record length after that settles so only wake-driven calls count.
  const engineCallsAtStop = record.engineCalls.length;
  const unregisterD = declareTiqianFontFaces(
    "@font-face { font-family: Declared Wake D; src: url('wake-d.woff2'); }",
    { baseUrl: "https://declared.test/wake-d.css" },
  );
  record.frameAdvanceCounts.w3 = await pumpQuiescent();
  record.declaredWake.w3RevalidateCalls = record.engineCalls.length - engineCallsAtStop;
  record.declaredWake.paragraphQueries = Object.fromEntries(paragraphQueriesByPhase);

  unregisterA();
  unregisterB();
  unregisterC();
  unregisterD();
  element.isConnected = false;
  element.disconnectedCallback();
  await new Promise((resolve) => setImmediate(resolve));

  record.fetchCalls = drive.world.fetchCalls;
  activeEngineRecord = null;
  setEngineOverride(null);

  return record;
}
