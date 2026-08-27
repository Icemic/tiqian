// R10 verification surface (spec wc-s3 item 4, ruling R8 TS-ifies new
// tests): the dissolved engine entry is driven by a plain-data mock
// runtime-graph context. Per ruling 1 this direct-drive test assembles its
// own dependencies: the rootState and layoutJobPool collaborators are plain
// object literals that satisfy the product contracts by assignment, and the
// named entry functions receive them as explicit arguments. The third
// parameter is a real EnhancedElementContext built through
// createEnhanceContext; the raw-DOM lifecycle runs for real against the
// context's paragraph records, so no module-level stubbing is involved. No
// engine instance, no registry beyond the plain states map the wrapper
// keeps, and no browser globals beyond the documented reads
// (getComputedStyle for the shared-runtime-styles gate and root defaults,
// window.innerHeight for viewport distance, document/Node for the raw-DOM
// takeover).

import assert from "node:assert/strict";
import test from "node:test";

import {
  enhance,
  enhanceProgressively,
  relayout,
} from "../core/engine/progressive-drivers.js";
import { destroyRoot, detachRoot } from "../core/engine/lifecycle.js";
import { createEnhanceContext } from "../core/engine/context/enhance-context.js";
import {
  probeRootContentDrift,
  reconcileRoot,
} from "../core/engine/content-reconcile.js";
import { createRootState } from "../core/engine/root-state.js";
import { rawDomBegin, rawDomCommit, rawDomTake } from "../core/engine/raw-dom.js";
import type { RootStateApi, RootState, EngineState } from "../core/engine/root-state.js";
import type { LayoutJobPool, LayoutJobSpec } from "../core/engine/layout-job-pool.js";
import type { EnhancedElementContext } from "../core/engine/context/enhance-context.js";
import type { EnhanceOptions } from "../core/engine/lifecycle.js";
import { initializeGlobalServices } from "../core/services/global-services.js";
initializeGlobalServices();

// Minimal node tree for the raw-DOM lifecycle: the commit forwarding captures
// the prototype mutation verbs as its native layer, and the takeover moves
// children through fragments built by the fake document.
class PlainNode {
  nodeType: number;
  textContent: string;
  childNodes: PlainNode[];
  parentNode: PlainNode | any;

  constructor(nodeType: number, textContent = "") {
    this.nodeType = nodeType;
    this.textContent = textContent;
    this.childNodes = [];
    this.parentNode = null;
  }

  get firstChild(): PlainNode | null {
    return this.childNodes[0] ?? null;
  }

  get nextSibling(): PlainNode | null {
    if (!this.parentNode) return null;
    const siblings = this.parentNode.childNodes;
    return siblings[siblings.indexOf(this) + 1] ?? null;
  }

  appendChild(node: any): any {
    if (node.nodeType === 11) {
      while (node.firstChild) PlainNode.prototype.appendChild.call(this, node.firstChild);
      return node;
    }
    if (node.parentNode) node.parentNode.removeChild(node);
    this.childNodes.push(node);
    node.parentNode = this;
    return node;
  }

  removeChild(node: any): any {
    const index = this.childNodes.indexOf(node);
    if (index >= 0) this.childNodes.splice(index, 1);
    node.parentNode = null;
    return node;
  }

  insertBefore(node: any, reference: any): any {
    if (node.nodeType === 11) {
      while (node.firstChild) PlainNode.prototype.insertBefore.call(this, node.firstChild, reference);
      return node;
    }
    const index = reference == null ? this.childNodes.length : this.childNodes.indexOf(reference);
    if (node.parentNode) node.parentNode.removeChild(node);
    this.childNodes.splice(index, 0, node);
    node.parentNode = this;
    return node;
  }

  replaceChild(next: any, previous: any): any {
    this.insertBefore(next, previous);
    return this.removeChild(previous);
  }
}

class PlainFragment extends PlainNode {
  constructor() {
    super(11);
  }
}

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
    createDocumentFragment: () => new PlainFragment(),
  });
}

function fakeElement(tagName: string, extras: Record<string, unknown> = {}) {
  const attributes = new Map<string, string>();
  const childNodes: any[] = [];
  const element: any = Object.assign(
    Object.create(null),
    {
      nodeType: 1,
      tagName: tagName.toUpperCase(),
      isConnected: true,
      attributes,
      childNodes,
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
      appendChild(node: any) {
        if (node.nodeType === 11) {
          while (node.firstChild) element.appendChild(node.firstChild);
          return node;
        }
        if (node.parentNode) node.parentNode.removeChild(node);
        childNodes.push(node);
        node.parentNode = element;
        return node;
      },
      removeChild(node: any) {
        const index = childNodes.indexOf(node);
        if (index >= 0) childNodes.splice(index, 1);
        node.parentNode = null;
        return node;
      },
      insertBefore(node: any, reference: any) {
        if (node.nodeType === 11) {
          while (node.firstChild) element.insertBefore(node.firstChild, reference);
          return node;
        }
        const index = reference == null ? childNodes.length : childNodes.indexOf(reference);
        if (node.parentNode) node.parentNode.removeChild(node);
        childNodes.splice(index, 0, node);
        node.parentNode = element;
        return node;
      },
      replaceChild(next: any, previous: any) {
        element.insertBefore(next, previous);
        return element.removeChild(previous);
      },
    },
    extras,
  );
  Object.defineProperty(element, "firstChild", {
    enumerable: true,
    get: () => childNodes[0] ?? null,
  });
  return element;
}

// The drivers read the shared-runtime-styles marker and the root defaults
// through getComputedStyle, the viewport height off window.innerHeight, and
// the raw-DOM takeover reads the fragment factory and the Node prototype;
// install all four and hand back a restore closure. Reflect keeps the global
// writes cast-free.
function installDriveGlobals() {
  const saved = ["window", "getComputedStyle", "document", "Node"].map((name) => ({
    name,
    own: Object.prototype.hasOwnProperty.call(globalThis, name),
    value: Reflect.get(globalThis, name),
  }));
  const computedStyle = () => ({
    getPropertyValue: (name: string) => (name === "--tq-styles-ready" ? "1" : ""),
  });
  Reflect.set(globalThis, "window", {
    innerHeight: 800,
    getComputedStyle: computedStyle,
  });
  Reflect.set(globalThis, "getComputedStyle", computedStyle);
  Reflect.set(globalThis, "document", fakeDocument());
  Reflect.set(globalThis, "Node", PlainNode);
  return () => {
    for (const entry of saved) {
      if (entry.own) Reflect.set(globalThis, entry.name, entry.value);
      else Reflect.deleteProperty(globalThis, entry.name);
    }
  };
}

interface PlainContext {
  rootState: RootStateApi;
  layoutJobPool: LayoutJobPool;
  ops: string[];
  jobs: LayoutJobSpec[];
  states: Map<unknown, RootState>;
  createdBags: (Record<string, unknown> | null)[];
  canonicalBags: EnhanceOptions[];
  poolJobKind: string | null;
}

interface JobKindBox {
  value: string | null;
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
    cjkDashCapability: { status: "unavailable", detail: null },
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

function makePlainContext(): PlainContext {
  const ops: string[] = [];
  const jobs: LayoutJobSpec[] = [];
  const states = new Map<unknown, RootState>();
  const createdBags: (Record<string, unknown> | null)[] = [];
  const canonicalBags: EnhanceOptions[] = [];
  const jobKindBox: JobKindBox = { value: null };

  // Create a real rootState API instance and wrap its methods to track
  // calls. The state registry lives on the plain states map so tests can
  // seed enhanced steady states by assignment.
  const realRootState = createRootState();
  const rootState: RootStateApi = {
    createRootState(root, bag) {
      ops.push("rs.createRootState");
      createdBags.push(bag);
      const state = realRootState.createRootState(root, bag);
      states.set(root, state);
      return state;
    },
    createRootStateFromCanonical(root, options) {
      ops.push("rs.createRootStateFromCanonical");
      canonicalBags.push(options);
      const state = realRootState.createRootStateFromCanonical(root, options);
      states.set(root, state);
      return state;
    },
    activeTsOptions(state) {
      return realRootState.activeTsOptions(state);
    },
    activeSnapshotSessionDescriptor(state) {
      return realRootState.activeSnapshotSessionDescriptor(state);
    },
    engineState(state) {
      return realRootState.engineState(state);
    },
    processParagraphArgument(state, paragraph) {
      return realRootState.processParagraphArgument(state, paragraph);
    },
    sessionArgument(state) {
      ops.push("rs.sessionArgument");
      return realRootState.sessionArgument(state);
    },
    prepareArgument(state, paragraph, widthOverride) {
      return realRootState.prepareArgument(state, paragraph, widthOverride);
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
    paragraphCandidates(root, selector) {
      ops.push("rs.paragraphCandidates");
      return realRootState.paragraphCandidates(root, selector);
    },
    strandedSourceParagraphs(root, state) {
      ops.push("rs.strandedSourceParagraphs");
      return realRootState.strandedSourceParagraphs(root, state);
    },
    publishState(state, keepEmpty) {
      ops.push(keepEmpty ? "rs.publishState:keepEmpty" : "rs.publishState");
      realRootState.publishState(state, keepEmpty);
    },
    updateCjkDashCapability(state, outcome) {
      realRootState.updateCjkDashCapability(state, outcome);
    },
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

  return {
    rootState,
    layoutJobPool,
    ops,
    jobs,
    states,
    createdBags,
    canonicalBags,
    get poolJobKind() {
      return jobKindBox.value;
    },
    set poolJobKind(value: string | null) {
      jobKindBox.value = value;
    },
  };
}

// Registers the paragraph with the context's raw-DOM bookkeeping exactly the
// way the enhance pass did, so restore/probe/match paths find the record.
function registerParagraph(context: EnhancedElementContext, source: any) {
  rawDomBegin(context, source, null, null, null, null, null, null, "", "", "", "", "", "", null);
  rawDomTake(context, source, null);
  rawDomCommit(context, source, null);
}

test("the plain context rootState satisfies the product contracts", () => {
  const context = makePlainContext();
  assert.equal(typeof context.rootState.createRootState, "function");
  assert.equal(typeof context.rootState.sessionArgument, "function");
});

test("enhance installs the copy listener, tears down, then builds and publishes", () => {
  const context = makePlainContext();
  const rootDocument = fakeDocument();
  const root = fakeElement("tiqian-prose", { ownerDocument: rootDocument });
  const restoreGlobals = installDriveGlobals();
  try {
    const enhancedCount = enhance(
      context.rootState,
      context.layoutJobPool,
      createEnhanceContext(root),
      root,
      { paragraphSelector: "p" },
    );
    assert.equal(enhancedCount, 0);
    assert.deepEqual(context.ops, [
      "pool.cancelJob",
      "rs.deleteState",
      "rs.createRootState",
      "rs.paragraphCandidates",
      "rs.publishState",
    ]);
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
      context.layoutJobPool,
      createEnhanceContext(root),
      root,
      bag,
    );
    assert.deepEqual(context.ops, [
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
      context.layoutJobPool,
      createEnhanceContext(root),
      root,
    );
    assert.deepEqual(context.ops, [
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
      context.layoutJobPool,
      createEnhanceContext(root),
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
      context.layoutJobPool,
      createEnhanceContext(root),
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
  const contentA = new PlainNode(3, "content a");
  const contentB = new PlainNode(3, "content b");
  paragraphA.appendChild(contentA);
  paragraphB.appendChild(contentB);
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

  const restoreGlobals = installDriveGlobals();
  try {
    const enhanceContext = createEnhanceContext(root);
    // Enhanced steady state: the takeover moved the original content into
    // the raw-DOM backup.
    registerParagraph(enhanceContext, paragraphA);
    registerParagraph(enhanceContext, paragraphB);
    assert.equal(paragraphA.childNodes.length, 0);
    assert.equal(paragraphB.childNodes.length, 0);

    destroyRoot(context.rootState, context.layoutJobPool, enhanceContext, root);

    assert.deepEqual(context.ops, ["pool.cancelJob", "rs.deleteState"]);
    // The real restore handed the captured original content back to the hosts.
    assert.deepEqual(paragraphA.childNodes, [contentA]);
    assert.deepEqual(paragraphB.childNodes, [contentB]);
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
  } finally {
    restoreGlobals();
  }
});

test("destroyRoot keeps the snapshot-owned enhancement markers", () => {
  const context = makePlainContext();
  const root = fakeElement("tiqian-prose", { ownerDocument: fakeDocument() });
  root.setAttribute("data-tiqian-snapshot-count", "3");
  const seeded = blankRootState(root, canonicalOptions());
  context.states.set(root, seeded);

  destroyRoot(context.rootState, context.layoutJobPool, createEnhanceContext(root), root);

  assert.equal(root.getAttribute("data-tiqian-enhanced"), "true");
  assert.equal(root.getAttribute("data-tiqian-enhanced-count"), "3");
});

test("detachRoot cancels the job without touching the root state", () => {
  const context = makePlainContext();
  const root = fakeElement("tiqian-prose", { ownerDocument: fakeDocument() });
  const seeded = blankRootState(root, canonicalOptions());
  context.states.set(root, seeded);
  const enhanceContext = createEnhanceContext(root);

  detachRoot(context.layoutJobPool, root, enhanceContext);

  assert.deepEqual(context.ops, ["pool.cancelJob"]);
  assert.equal(context.states.get(root), seeded);
});

test("probeRootContentDrift answers the probe verdict as a plain object", () => {
  const context = makePlainContext();
  const root = fakeElement("tiqian-prose", { ownerDocument: fakeDocument() });

  // No runtime state: the whole root is unknown.
  assert.deepEqual(probeRootContentDrift(createEnhanceContext(root), context.rootState, root), {
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

  const restoreGlobals = installDriveGlobals();
  try {
    const enhanceContext = createEnhanceContext(root);
    registerParagraph(enhanceContext, clean);
    registerParagraph(enhanceContext, drifted);
    // A host edit through the native mutation path breaks the rendered
    // identity of the drifted paragraph.
    PlainNode.prototype.appendChild.call(drifted, new PlainNode(3, "host edit"));

    assert.deepEqual(probeRootContentDrift(enhanceContext, context.rootState, root), {
      unknown: 0,
      drifted: 1,
      dead: 1,
      rawDom: 0,
    });
  } finally {
    restoreGlobals();
  }
});

test("reconcileRoot answers null without state and idle without job dispatch", () => {
  const context = makePlainContext();
  const root = fakeElement("tiqian-prose", { ownerDocument: fakeDocument() });

  assert.equal(
    reconcileRoot(createEnhanceContext(root), context.rootState, context.layoutJobPool, root, []),
    null,
  );

  const clean = fakeElement("p");
  const seeded = blankRootState(root, canonicalOptions());
  seeded.paragraphs.push(fakeOf({ source: clean, lowered: fakeOf({}), lastMeasure: null }));
  context.states.set(root, seeded);

  const restoreGlobals = installDriveGlobals();
  try {
    const enhanceContext = createEnhanceContext(root);
    registerParagraph(enhanceContext, clean);
    const result = reconcileRoot(
      enhanceContext,
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
