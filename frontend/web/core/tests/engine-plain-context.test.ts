// R10 verification surface (spec wc-s3 item 4, ruling R8 TS-ifies new
// tests): the dissolved engine entry is driven by a plain-data mock
// runtime-graph context. Per ruling 1 this direct-drive test assembles its
// own dependencies: the four graph products (rootState, copyInstaller,
// layoutJobPool, rawDom) are plain object literals that satisfy the product
// contracts by assignment, and the named entry functions receive them as
// explicit arguments. No engine instance, no registry, no browser globals
// beyond the two reads the drivers document (window.getComputedStyle for the
// shared-runtime-styles gate, window.innerHeight for viewport distance).

import assert from "node:assert/strict";
import test from "node:test";

import {
  enhance,
  enhanceProgressively,
  relayout,
} from "../core/engine/progressive-drivers.js";
import { destroyRoot, detachRoot } from "../core/engine/lifecycle.js";
import {
  probeRootContentDrift,
  reconcileRoot,
} from "../core/engine/content-reconcile.js";
import type { RootStateApi, RootState, EngineState } from "../core/engine/root-state.js";
import type { LayoutJobPool, LayoutJobSpec } from "../core/engine/layout-job-pool.js";
import type { RawDomApi } from "../core/engine/raw-dom.js";
import type { CopyInstaller } from "../core/utils/copy.js";
import type { EnhanceOptions } from "../core/engine/lifecycle.js";

// Plain fakes ride through an any-typed builder (Object.create(null)), the
// same shape the lowered-dom-helpers suite uses; the typed seam is the
// product contracts the context factory below annotates.
function fakeOf(members: Record<string, unknown>) {
  return Object.assign(Object.create(null), members);
}

function fakeDocument() {
  return fakeOf({
    nodeType: 9,
    addEventListener() {},
    removeEventListener() {},
  });
}

function fakeElement(tagName: string, extras: Record<string, unknown> = {}) {
  const attributes = new Map<string, string>();
  return Object.assign(
    Object.create(null),
    {
      nodeType: 1,
      tagName: tagName.toUpperCase(),
      isConnected: true,
      attributes,
      textContent: "",
      ownerDocument: null,
      getAttribute: (name: string) => attributes.get(name) ?? null,
      setAttribute: (name: string, value: string) => {
        attributes.set(name, String(value));
      },
      removeAttribute: (name: string) => {
        attributes.delete(name);
      },
      hasAttribute: (name: string) => attributes.has(name),
      closest: () => null,
      querySelectorAll: () => [],
      getBoundingClientRect: () => ({ width: 360, top: 0, bottom: 24 }),
    },
    extras,
  );
}

// The drivers read the shared-runtime-styles marker off window.getComputedStyle
// and the viewport height off window.innerHeight; install both and hand back
// a restore closure. Reflect keeps the global writes cast-free.
function installDriveGlobals() {
  const savedWindow = Reflect.get(globalThis, "window");
  Reflect.set(globalThis, "window", {
    innerHeight: 800,
    getComputedStyle: () => ({
      getPropertyValue: (name: string) => (name === "--tq-styles-ready" ? "1" : ""),
    }),
  });
  return () => {
    if (savedWindow === undefined) Reflect.deleteProperty(globalThis, "window");
    else Reflect.set(globalThis, "window", savedWindow);
  };
}

interface PlainContext {
  rootState: RootStateApi;
  copyInstaller: CopyInstaller;
  layoutJobPool: LayoutJobPool;
  rawDom: RawDomApi;
  ops: string[];
  jobs: LayoutJobSpec[];
  states: Map<unknown, RootState>;
  createdBags: (Record<string, unknown> | null)[];
  canonicalBags: EnhanceOptions[];
  installedDocuments: unknown[];
  restoredParagraphs: unknown[];
  poolJobKind: string | null;
}

function blankRootState(root: Element, options: EnhanceOptions): RootState {
  return {
    root,
    options: {
      ...options,
      fontFamilies: {
        cjk: "Fixture CJK",
        latin: "Fixture Latin",
        monospace: "Fixture Mono",
        cjkSerif: "Fixture CJK Serif",
        latinSerif: "Fixture Latin Serif",
      },
    },
    browserFallback: {
      bridge: {
        shapeJson: () => "{}",
        metricsJson: () => "{}",
      },
    },
    paragraphs: [],
    issues: [],
    preparedDomEnabled: false,
    preparedDomFallback: null,
    cjkDashCapability: { status: "unavailable", detail: null },
  };
}

function blankEngineState(state: RootState): EngineState {
  return {
    options: state.options,
    preparedDomEnabled: false,
    snapshotSession: null,
    browserFallback: null,
    onIssue() {},
    onParagraphCommitted() {},
    onDisableSnapshotPreparedDom() {},
    paragraphs: state.paragraphs,
    issues: state.issues,
  };
}

function canonicalOptions(): EnhanceOptions {
  return {
    fontFamilies: {
      cjk: null,
      latin: null,
      monospace: null,
      cjkSerif: null,
      latinSerif: null,
    },
    fontSize: null,
    lineHeight: null,
    firstLineIndentIc: 0,
    emphasisDotGapEm: 0.15,
    strongAsEmphasisMarks: false,
    paragraphSelector: "p, li",
    cjkDashCapability: null,
    snapshotFontSession: null,
    requireSnapshotLayoutWorker: false,
  };
}

interface JobKindBox {
  value: string | null;
}

function makePlainContext(): PlainContext {
  const ops: string[] = [];
  const jobs: LayoutJobSpec[] = [];
  const states = new Map<unknown, RootState>();
  const createdBags: (Record<string, unknown> | null)[] = [];
  const canonicalBags: EnhanceOptions[] = [];
  const installedDocuments: unknown[] = [];
  const restoredParagraphs: unknown[] = [];
  const jobKindBox: JobKindBox = { value: null };

  const rootState: RootStateApi = {
    createRootState(root, bag) {
      ops.push("rs.createRootState");
      createdBags.push(bag);
      const state = blankRootState(root, canonicalOptions());
      if (bag && typeof bag.paragraphSelector === "string") {
        state.options.paragraphSelector = bag.paragraphSelector;
      }
      states.set(root, state);
      return state;
    },
    createRootStateFromCanonical(root, options) {
      ops.push("rs.createRootStateFromCanonical");
      canonicalBags.push(options);
      const state = blankRootState(root, options);
      states.set(root, state);
      return state;
    },
    activeTsOptions(state) {
      return state.options;
    },
    activeSnapshotSessionDescriptor() {
      return null;
    },
    disableSnapshotPreparedDom() {},
    engineState(state) {
      return blankEngineState(state);
    },
    processParagraphArgument(state, paragraph) {
      return { paragraph, state: blankEngineState(state) };
    },
    sessionArgument(state) {
      ops.push("rs.sessionArgument");
      return { paragraphs: state.paragraphs, state: blankEngineState(state) };
    },
    prepareArgument(state, paragraph, widthOverride) {
      return {
        paragraph,
        options: state.options,
        snapshotSession: null,
        browserFallback: state.browserFallback,
        widthOverride,
      };
    },
    getState(root) {
      return states.get(root);
    },
    setState(root, state) {
      ops.push("rs.setState");
      states.set(root, state);
    },
    deleteState(root) {
      ops.push("rs.deleteState");
      states.delete(root);
    },
    paragraphCandidates() {
      ops.push("rs.paragraphCandidates");
      return [];
    },
    strandedSourceParagraphs() {
      ops.push("rs.strandedSourceParagraphs");
      return [];
    },
    publishState(state, keepEmpty) {
      ops.push(keepEmpty ? "rs.publishState:keepEmpty" : "rs.publishState");
    },
    updateCjkDashCapability() {},
  };

  const layoutJobPool: LayoutJobPool = {
    startJob(spec) {
      ops.push("pool.startJob");
      jobs.push(spec);
    },
    cancelJob() {
      ops.push("pool.cancelJob");
    },
    runSlice() {
      return 0;
    },
    hasJob() {
      return false;
    },
    jobGeneration() {
      return 0;
    },
    jobKind() {
      return jobKindBox.value;
    },
    pendingInTier() {
      return 0;
    },
    paragraphCount() {
      return 0;
    },
    paragraphAt() {
      return null;
    },
    setParagraphTier() {
      return false;
    },
    attach() {
      return true;
    },
    detach() {
      ops.push("pool.detach");
      return true;
    },
    isAttached() {
      return false;
    },
  };

  const rawDom: RawDomApi = {
    begin() {},
    take() {},
    commit() {},
    stampRendered() {},
    renderedMatches: () => true,
    rawDomMatches: () => true,
    captureLive: (source) => fakeOf({ source }),
    rollback: () => [],
    restoreParagraph(source) {
      ops.push("rawDom.restoreParagraph");
      restoredParagraphs.push(source);
    },
    restoreShell() {},
    ensureContainingBlock() {},
    suspendEngineWrites: (_source, action) => action(),
  };

  const copyInstaller: CopyInstaller = {
    install(documentObject) {
      ops.push("copyInstaller.install");
      installedDocuments.push(documentObject);
    },
  };

  return {
    rootState,
    copyInstaller,
    layoutJobPool,
    rawDom,
    ops,
    jobs,
    states,
    createdBags,
    canonicalBags,
    installedDocuments,
    restoredParagraphs,
    get poolJobKind() {
      return jobKindBox.value;
    },
    set poolJobKind(value: string | null) {
      jobKindBox.value = value;
    },
  };
}

function graphOf(context: PlainContext) {
  return {
    rootState: context.rootState,
    copyInstaller: context.copyInstaller,
    layoutJobPool: context.layoutJobPool,
    rawDom: context.rawDom,
  };
}

test("the plain context literals satisfy the runtime-graph product contracts", () => {
  const context = makePlainContext();
  const graph = graphOf(context);
  assert.equal(typeof graph.rootState.createRootState, "function");
  assert.equal(typeof graph.rootState.sessionArgument, "function");
  assert.equal(typeof graph.copyInstaller.install, "function");
  assert.equal(typeof graph.layoutJobPool.startJob, "function");
  assert.equal(typeof graph.layoutJobPool.cancelJob, "function");
  assert.equal(typeof graph.rawDom.restoreParagraph, "function");
  assert.equal(typeof graph.rawDom.renderedMatches, "function");
});

test("enhance installs the copy listener, tears down, then builds and publishes", () => {
  const context = makePlainContext();
  const rootDocument = fakeDocument();
  const root = fakeElement("tiqian-prose", { ownerDocument: rootDocument });
  const restoreGlobals = installDriveGlobals();
  try {
    const enhancedCount = enhance(
      context.rootState,
      context.copyInstaller,
      context.layoutJobPool,
      context.rawDom,
      root,
      { paragraphSelector: "p" },
    );
    assert.equal(enhancedCount, 0);
    assert.deepEqual(context.ops, [
      "copyInstaller.install",
      "pool.cancelJob",
      "rs.deleteState",
      "rs.createRootState",
      "rs.paragraphCandidates",
      "rs.publishState",
    ]);
    assert.equal(context.installedDocuments.length, 1);
    assert.equal(context.installedDocuments[0], rootDocument);
    assert.deepEqual(context.createdBags, [{ paragraphSelector: "p" }]);
    // The created state carries the bag's selector into the root state.
    assert.equal(context.states.get(root)?.options.paragraphSelector, "p");
    // Observable enhancement attributes stay absent without snapshot count.
    assert.equal(root.hasAttribute("data-tiqian-enhanced"), false);
    assert.equal(root.hasAttribute("data-tiqian-enhanced-count"), false);
  } finally {
    restoreGlobals();
  }
});

test("enhanceProgressively starts an Enhance job through the plain pool", () => {
  const context = makePlainContext();
  const root = fakeElement("tiqian-prose", { ownerDocument: fakeDocument() });
  const restoreGlobals = installDriveGlobals();
  try {
    const bag: Record<string, unknown> = { paragraphSelector: "p, li" };
    enhanceProgressively(
      context.rootState,
      context.copyInstaller,
      context.layoutJobPool,
      context.rawDom,
      root,
      bag,
    );
    assert.deepEqual(context.ops, [
      "copyInstaller.install",
      "pool.cancelJob",
      "rs.deleteState",
      "rs.createRootState",
      "rs.paragraphCandidates",
      "rs.setState",
      "rs.publishState:keepEmpty",
      "pool.startJob",
    ]);
    // The raw host bag reaches the state builder by reference.
    assert.equal(context.createdBags.length, 1);
    assert.equal(context.createdBags[0], bag);
    assert.equal(context.jobs.length, 1);
    assert.equal(context.jobs[0].kind, "Enhance");
    assert.equal(context.jobs[0].itemCount, 0);
    assert.equal(context.jobs[0].root, root);
    assert.equal(context.jobs[0].coordinated, false);
    // startLayoutJob clears the relayout-error marker on dispatch.
    assert.equal(root.hasAttribute("data-tiqian-relayout-error"), false);
  } finally {
    restoreGlobals();
  }
});

test("relayout cold-starts a Relayout job when the root carries no state", () => {
  const context = makePlainContext();
  const root = fakeElement("tiqian-prose", { ownerDocument: fakeDocument() });
  const restoreGlobals = installDriveGlobals();
  try {
    relayout(
      context.rootState,
      context.copyInstaller,
      context.layoutJobPool,
      context.rawDom,
      root,
    );
    assert.deepEqual(context.ops, [
      "copyInstaller.install",
      "pool.cancelJob",
      "rs.deleteState",
      "rs.createRootState",
      "rs.paragraphCandidates",
      "rs.setState",
      "rs.publishState:keepEmpty",
      "pool.startJob",
    ]);
    assert.deepEqual(context.createdBags, [null]);
    assert.equal(context.jobs[0].kind, "Relayout");
  } finally {
    restoreGlobals();
  }
});

test("relayout restarts an interrupted Enhance through the canonical builder", () => {
  const context = makePlainContext();
  const root = fakeElement("tiqian-prose", { ownerDocument: fakeDocument() });
  const restoreGlobals = installDriveGlobals();
  try {
    const seeded = blankRootState(root, canonicalOptions());
    context.states.set(root, seeded);
    context.poolJobKind = "Enhance";

    relayout(
      context.rootState,
      context.copyInstaller,
      context.layoutJobPool,
      context.rawDom,
      root,
    );

    // Branch 1 reuses the running canonical options by reference and keeps
    // the Enhance kind so the finish event stays tiqian:ready.
    assert.deepEqual(context.canonicalBags, [seeded.options]);
    assert.equal(context.ops.includes("rs.createRootStateFromCanonical"), true);
    assert.equal(context.jobs.length, 1);
    assert.equal(context.jobs[0].kind, "Enhance");
  } finally {
    restoreGlobals();
  }
});

test("relayout main path cancels the job and rebuilds a Relayout session", () => {
  const context = makePlainContext();
  const root = fakeElement("tiqian-prose", { ownerDocument: fakeDocument() });
  const restoreGlobals = installDriveGlobals();
  try {
    const seeded = blankRootState(root, canonicalOptions());
    context.states.set(root, seeded);

    relayout(
      context.rootState,
      context.copyInstaller,
      context.layoutJobPool,
      context.rawDom,
      root,
    );

    const cancelIndex = context.ops.indexOf("pool.cancelJob");
    const sessionIndex = context.ops.indexOf("rs.sessionArgument");
    const jobIndex = context.ops.indexOf("pool.startJob");
    assert.notEqual(cancelIndex, -1);
    assert.notEqual(sessionIndex, -1);
    assert.ok(cancelIndex < sessionIndex);
    assert.ok(sessionIndex < jobIndex);
    assert.equal(context.jobs.length, 1);
    assert.equal(context.jobs[0].kind, "Relayout");
    assert.equal(context.jobs[0].itemCount, 0);
    // The root state survives an in-place relayout.
    assert.equal(context.states.get(root), seeded);
  } finally {
    restoreGlobals();
  }
});

test("destroyRoot restores tracked paragraphs, clears markers, rewrites attributes", () => {
  const context = makePlainContext();
  const root = fakeElement("tiqian-prose", { ownerDocument: fakeDocument() });
  root.setAttribute("data-tiqian-enhanced", "true");
  root.setAttribute("data-tiqian-enhanced-count", "2");
  root.setAttribute("data-tiqian-issue-count", "1");
  root.setAttribute("data-tiqian-relayout-error", "boom");
  root.setAttribute("data-tiqian-snapshot-layout-fallback", "native");

  const paragraphA = fakeElement("p");
  const paragraphB = fakeElement("p");
  const issueElement = fakeElement("p");
  issueElement.setAttribute("data-tiqian-capability-issue", "LoweringFailed");
  issueElement.setAttribute("data-tiqian-capability-detail", "detail");

  const seeded = blankRootState(root, canonicalOptions());
  seeded.paragraphs.push(
    fakeOf({ source: paragraphA, lowered: fakeOf({}), lastMeasure: null }),
    fakeOf({ source: paragraphB, lowered: fakeOf({}), lastMeasure: null }),
  );
  seeded.issues.push(
    fakeOf({
      name: "LoweringFailed",
      detail: "detail",
      element: issueElement,
      reportToConsole: false,
      markerCaptured: true,
      originalNameAttribute: null,
      originalDetailAttribute: null,
    }),
  );
  context.states.set(root, seeded);

  destroyRoot(context.rootState, context.layoutJobPool, context.rawDom, root);

  assert.deepEqual(context.ops, [
    "pool.cancelJob",
    "rs.deleteState",
    "rawDom.restoreParagraph",
    "rawDom.restoreParagraph",
  ]);
  assert.deepEqual(context.restoredParagraphs, [paragraphA, paragraphB]);
  assert.equal(context.states.has(root), false);
  // clearIssue restored the capability marker attributes to their captured
  // (absent) originals.
  assert.equal(issueElement.hasAttribute("data-tiqian-capability-issue"), false);
  assert.equal(issueElement.hasAttribute("data-tiqian-capability-detail"), false);
  // Without a snapshot count the observable markers are removed outright.
  assert.equal(root.hasAttribute("data-tiqian-enhanced"), false);
  assert.equal(root.hasAttribute("data-tiqian-enhanced-count"), false);
  assert.equal(root.hasAttribute("data-tiqian-issue-count"), false);
  assert.equal(root.hasAttribute("data-tiqian-relayout-error"), false);
  assert.equal(root.hasAttribute("data-tiqian-snapshot-layout-fallback"), false);
});

test("destroyRoot keeps the snapshot-owned enhancement markers", () => {
  const context = makePlainContext();
  const root = fakeElement("tiqian-prose", { ownerDocument: fakeDocument() });
  root.setAttribute("data-tiqian-snapshot-count", "3");
  const seeded = blankRootState(root, canonicalOptions());
  context.states.set(root, seeded);

  destroyRoot(context.rootState, context.layoutJobPool, context.rawDom, root);

  assert.equal(root.getAttribute("data-tiqian-enhanced"), "true");
  assert.equal(root.getAttribute("data-tiqian-enhanced-count"), "3");
});

test("detachRoot cancels the job without touching the root state", () => {
  const context = makePlainContext();
  const root = fakeElement("tiqian-prose", { ownerDocument: fakeDocument() });
  const seeded = blankRootState(root, canonicalOptions());
  context.states.set(root, seeded);

  detachRoot(context.layoutJobPool, root);

  assert.deepEqual(context.ops, ["pool.cancelJob"]);
  assert.equal(context.states.get(root), seeded);
});

test("probeRootContentDrift answers the probe verdict as a plain object", () => {
  const context = makePlainContext();
  const root = fakeElement("tiqian-prose", { ownerDocument: fakeDocument() });

  // No runtime state: the whole root is unknown.
  assert.deepEqual(probeRootContentDrift(context.rawDom, context.rootState, root), {
    unknown: 1,
    drifted: 0,
    dead: 0,
    rawDom: 0,
  });

  const clean = fakeElement("p");
  const drifted = fakeElement("p");
  const dead = fakeElement("p", { isConnected: false });
  const seeded = blankRootState(root, canonicalOptions());
  seeded.paragraphs.push(
    fakeOf({ source: clean, lowered: fakeOf({}), lastMeasure: null }),
    fakeOf({ source: drifted, lowered: fakeOf({}), lastMeasure: null }),
    fakeOf({ source: dead, lowered: fakeOf({}), lastMeasure: null }),
  );
  context.states.set(root, seeded);
  context.rawDom.renderedMatches = (source: Element) => source !== drifted;

  assert.deepEqual(probeRootContentDrift(context.rawDom, context.rootState, root), {
    unknown: 0,
    drifted: 1,
    dead: 1,
    rawDom: 0,
  });
});

test("reconcileRoot answers null without state and idle without job dispatch", () => {
  const context = makePlainContext();
  const root = fakeElement("tiqian-prose", { ownerDocument: fakeDocument() });

  assert.equal(
    reconcileRoot(context.rawDom, context.rootState, context.layoutJobPool, root, []),
    null,
  );

  const clean = fakeElement("p");
  const seeded = blankRootState(root, canonicalOptions());
  seeded.paragraphs.push(fakeOf({ source: clean, lowered: fakeOf({}), lastMeasure: null }));
  context.states.set(root, seeded);

  const restoreGlobals = installDriveGlobals();
  try {
    const result = reconcileRoot(
      context.rawDom,
      context.rootState,
      context.layoutJobPool,
      root,
      [],
    );
    assert.deepEqual(result, {
      outcome: "idle",
      drifted: 0,
      rawDom: 0,
      tainted: 0,
      stranded: 0,
      dead: 0,
    });
    // An idle verdict never schedules a reconcile job.
    assert.equal(context.jobs.length, 0);
    assert.equal(context.states.get(root), seeded);
  } finally {
    restoreGlobals();
  }
});
