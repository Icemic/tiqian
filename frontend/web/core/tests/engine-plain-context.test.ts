// R10 verification surface (spec wc-s3 item 4, ruling R8 TS-ifies new
// tests; core-neutral wave): the dissolved engine entry is driven through a
// real EnhancedElementContext built by createEnhanceContext, with the
// driver-observable part surface (option resolvers, runtime options,
// candidate enumeration, publishState projection) wrapped by recording
// spies. The layout job pool is a plain object literal satisfying the
// LayoutJobPool contract by assignment, swapped into
// globalServices().coordination.layoutJobPool for the duration of each test.
// Per ruling 1 this direct-drive test assembles its own dependencies: no
// engine instance, no per-root registry (the registry dissolved into the
// context's contextState), and no browser globals beyond the documented
// reads (getComputedStyle for the shared-runtime-styles gate and root
// defaults, window.innerHeight for viewport distance, document/Node for the
// raw-DOM takeover).

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
import { rawDomBegin, rawDomCommit, rawDomTake } from "../core/engine/raw-dom.js";
import { globalServices, initializeGlobalServices } from "../core/services/global-services.js";
import type { LayoutJobPool, LayoutJobSpec } from "../core/engine/layout-job-pool.js";
import type { EnhancedElementContext } from "../core/engine/context/enhance-context.js";
import type {
  EnhanceOptions,
  ResolvedEnhanceOptions,
} from "../core/engine/lifecycle.js";
initializeGlobalServices();

// Minimal node tree for the raw-DOM lifecycle: the commit forwarding captures
// the prototype mutation verbs as its native layer, and the takeover moves
// children through fragments built by the fake document.
// Structural node surface shared by PlainNode and the null-prototype element
// fakes; named per G1 code standard rule 5 so annotations carry no any and no
// inline function types.
interface NodeSurface {
  nodeType: number;
  childNodes: NodeSurface[];
  parentNode: NodeSurface | null;
  readonly firstChild: NodeSurface | null;
  readonly nextSibling: NodeSurface | null;
  appendChild(node: NodeSurface): NodeSurface;
  removeChild(node: NodeSurface): NodeSurface;
  insertBefore(node: NodeSurface, reference: NodeSurface | null): NodeSurface;
  replaceChild(next: NodeSurface, previous: NodeSurface): NodeSurface;
}

class PlainNode implements NodeSurface {
  nodeType: number;
  textContent: string;
  childNodes: NodeSurface[];
  parentNode: NodeSurface | null;

  constructor(nodeType: number, textContent = "") {
    this.nodeType = nodeType;
    this.textContent = textContent;
    this.childNodes = [];
    this.parentNode = null;
  }

  get firstChild(): NodeSurface | null {
    return this.childNodes[0] ?? null;
  }

  get nextSibling(): NodeSurface | null {
    if (!this.parentNode) return null;
    const siblings = this.parentNode.childNodes;
    return siblings[siblings.indexOf(this) + 1] ?? null;
  }

  appendChild(node: NodeSurface): NodeSurface {
    if (node.nodeType === 11) {
      while (node.firstChild) PlainNode.prototype.appendChild.call(this, node.firstChild);
      return node;
    }
    if (node.parentNode) node.parentNode.removeChild(node);
    this.childNodes.push(node);
    node.parentNode = this;
    return node;
  }

  removeChild(node: NodeSurface): NodeSurface {
    const index = this.childNodes.indexOf(node);
    if (index >= 0) this.childNodes.splice(index, 1);
    node.parentNode = null;
    return node;
  }

  insertBefore(node: NodeSurface, reference: NodeSurface | null): NodeSurface {
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

  replaceChild(next: NodeSurface, previous: NodeSurface): NodeSurface {
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
  const childNodes: NodeSurface[] = [];
  const element = Object.assign(
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
      appendChild(node: NodeSurface) {
        if (node.nodeType === 11) {
          while (node.firstChild) element.appendChild(node.firstChild);
          return node;
        }
        if (node.parentNode) node.parentNode.removeChild(node);
        childNodes.push(node);
        node.parentNode = element;
        return node;
      },
      removeChild(node: NodeSurface) {
        const index = childNodes.indexOf(node);
        if (index >= 0) childNodes.splice(index, 1);
        node.parentNode = null;
        return node;
      },
      insertBefore(node: NodeSurface, reference: NodeSurface | null) {
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
      replaceChild(next: NodeSurface, previous: NodeSurface) {
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
  // Untyped like the lowered-dom-helpers fake factories: callers apply the
  // single boundary cast the product surface requires.
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

interface JobKindBox {
  value: string | null;
}

// One observed context run: the real EnhancedElementContext plus the
// recording surfaces the specs assert against. poolJobKind steers the fake
// pool's jobKind answer so relayout branch 1 can be reached.
interface PlainRuntime {
  context: EnhancedElementContext;
  ops: string[];
  jobs: LayoutJobSpec[];
  resolveBags: (Record<string, unknown> | null)[];
  canonicalBags: EnhanceOptions[];
  poolJobKind: string | null;
  realResolve(root: Element, optionsBag: Record<string, unknown>): ResolvedEnhanceOptions;
  restorePool(): void;
}

// The drivers reach the pool through the coordination service, so the fake
// pool is swapped in there for the duration of one test. The coordination
// service types the slot readonly; the swap rides one boundary cast into
// the writable view below.
type PoolSwapRestorer = () => void;

interface WritableCoordinationSlot {
  layoutJobPool: LayoutJobPool;
}

function swapLayoutJobPool(pool: LayoutJobPool): PoolSwapRestorer {
  const coordination = globalServices().coordination as WritableCoordinationSlot;
  const previous = coordination.layoutJobPool;
  coordination.layoutJobPool = pool;
  return () => {
    coordination.layoutJobPool = previous;
  };
}

function makeFakeLayoutJobPool(ops: string[], jobs: LayoutJobSpec[], jobKindBox: JobKindBox): LayoutJobPool {
  return {
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
}

// Builds a real EnhancedElementContext for the root and wraps its
// driver-observable part surface with recording spies: the two option
// resolvers dissolved from root-state's createRootState/
// createRootStateFromCanonical, the runtime-options setter (the dissolved
// setState), the candidate enumeration, and the publishState projection.
// The wrappers delegate to the real implementations; the fake layout job
// pool is swapped into the coordination service and restored through the
// handed-back closure.
function makePlainRuntime(root: Element): PlainRuntime {
  const ops: string[] = [];
  const jobs: LayoutJobSpec[] = [];
  const resolveBags: (Record<string, unknown> | null)[] = [];
  const canonicalBags: EnhanceOptions[] = [];
  const jobKindBox: JobKindBox = { value: null };

  const context = createEnhanceContext(root);
  const restorePool = swapLayoutJobPool(makeFakeLayoutJobPool(ops, jobs, jobKindBox));

  const ledger = context.optionsLedger;
  const realResolve = ledger.resolveEngineOptions;
  const realResolveCanonical = ledger.resolveEngineOptionsFromCanonical;
  ledger.resolveEngineOptions = (rootElement: Element, optionsBag: Record<string, unknown>) => {
    ops.push("ledger.resolveEngineOptions");
    resolveBags.push(optionsBag);
    return realResolve(rootElement, optionsBag);
  };
  ledger.resolveEngineOptionsFromCanonical = (rootElement: Element, canonicalOptions: EnhanceOptions) => {
    ops.push("ledger.resolveEngineOptionsFromCanonical");
    canonicalBags.push(canonicalOptions);
    return realResolveCanonical(rootElement, canonicalOptions);
  };

  const state = context.contextState;
  const realSetRuntimeOptions = state.setRuntimeOptions;
  state.setRuntimeOptions = (options: ResolvedEnhanceOptions | null) => {
    ops.push("contextState.setRuntimeOptions");
    realSetRuntimeOptions(options);
  };
  const realParagraphCandidates = state.paragraphCandidates;
  state.paragraphCandidates = (candidatesRoot: Element, selector: string) => {
    ops.push("contextState.paragraphCandidates");
    return realParagraphCandidates(candidatesRoot, selector);
  };

  const write = context.domWriteLayer;
  const realPublishState = write.publishState;
  write.publishState = (paragraphCount: number, issueCount: number, keepEmpty?: boolean) => {
    ops.push(keepEmpty ? "domWriteLayer.publishState:keepEmpty" : "domWriteLayer.publishState");
    realPublishState(paragraphCount, issueCount, keepEmpty);
  };

  return {
    context,
    ops,
    jobs,
    resolveBags,
    canonicalBags,
    realResolve,
    get poolJobKind() {
      return jobKindBox.value;
    },
    set poolJobKind(value: string | null) {
      jobKindBox.value = value;
    },
    restorePool,
  };
}

// Seeds the established steady state the specs need to reach the relayout
// main path and branch 1: resolved options through the unwrapped resolver,
// published through the wrapped setter (callers clear the recorded op), and
// the runtimeEstablished flag the registry presence test dissolved into.
function seedEstablishedRuntime(runtime: PlainRuntime, root: Element): ResolvedEnhanceOptions {
  const resolved = runtime.realResolve(root, {});
  runtime.context.contextState.setRuntimeOptions(resolved);
  runtime.context.contextState.setRuntimeEstablished(true);
  return resolved;
}

// Registers the paragraph with the context's raw-DOM bookkeeping exactly the
// way the enhance pass did, so restore/probe/match paths find the record.
function registerParagraph(context: EnhancedElementContext, source: Element) {
  rawDomBegin(context, source, null, null, null, null, null, null, "", "", "", "", "", "", null);
  rawDomTake(context, source, null);
  rawDomCommit(context, source, null);
}

test("the context part surface carries the dissolved registry verbs", () => {
  const root = fakeElement("tiqian-prose");
  const context = createEnhanceContext(root);
  assert.equal(typeof context.optionsLedger.resolveEngineOptions, "function");
  assert.equal(typeof context.optionsLedger.resolveEngineOptionsFromCanonical, "function");
  assert.equal(typeof context.contextState.setRuntimeOptions, "function");
  assert.equal(typeof context.contextState.paragraphCandidates, "function");
  assert.equal(typeof context.domWriteLayer.publishState, "function");
});

test("enhance installs the copy listener, tears down, then builds and publishes", () => {
  const rootDocument = fakeDocument();
  const root = fakeElement("tiqian-prose", { ownerDocument: rootDocument });
  const runtime = makePlainRuntime(root);
  const restoreGlobals = installDriveGlobals();
  try {
    const bag: Record<string, unknown> = { paragraphSelector: "p" };
    const enhancedCount = enhance(runtime.context, root, bag);
    assert.equal(enhancedCount, 0);
    assert.deepEqual(runtime.ops, [
      "pool.cancelJob",
      "ledger.resolveEngineOptions",
      "contextState.setRuntimeOptions",
      "contextState.paragraphCandidates",
      "domWriteLayer.publishState",
    ]);
    // The raw host bag reaches the option resolver by reference.
    assert.equal(runtime.resolveBags.length, 1);
    assert.equal(runtime.resolveBags[0], bag);
    // The resolved runtime options carry the bag's selector forward.
    assert.equal(runtime.context.contextState.runtimeOptions?.paragraphSelector, "p");
    // Observable enhancement attributes stay absent without snapshot count.
    assert.equal(root.hasAttribute("data-tiqian-enhanced"), false);
    assert.equal(root.hasAttribute("data-tiqian-enhanced-count"), false);
  } finally {
    restoreGlobals();
    runtime.restorePool();
  }
});

test("enhanceProgressively starts an Enhance job through the installed pool", () => {
  const root = fakeElement("tiqian-prose", { ownerDocument: fakeDocument() });
  const runtime = makePlainRuntime(root);
  const restoreGlobals = installDriveGlobals();
  try {
    const bag: Record<string, unknown> = { paragraphSelector: "p, li" };
    enhanceProgressively(runtime.context, root, bag);
    assert.deepEqual(runtime.ops, [
      "pool.cancelJob",
      "ledger.resolveEngineOptions",
      "contextState.setRuntimeOptions",
      "contextState.paragraphCandidates",
      "domWriteLayer.publishState:keepEmpty",
      "pool.startJob",
    ]);
    // The raw host bag reaches the option resolver by reference.
    assert.equal(runtime.resolveBags.length, 1);
    assert.equal(runtime.resolveBags[0], bag);
    assert.equal(runtime.jobs.length, 1);
    assert.equal(runtime.jobs[0].kind, "Enhance");
    assert.equal(runtime.jobs[0].itemCount, 0);
    assert.equal(runtime.jobs[0].root, root);
    assert.equal(runtime.jobs[0].coordinated, false);
    // startLayoutJob clears the relayout-error marker on dispatch.
    assert.equal(root.hasAttribute("data-tiqian-relayout-error"), false);
  } finally {
    restoreGlobals();
    runtime.restorePool();
  }
});

test("relayout cold-starts a Relayout job when the runtime is not established", () => {
  const root = fakeElement("tiqian-prose", { ownerDocument: fakeDocument() });
  const runtime = makePlainRuntime(root);
  const restoreGlobals = installDriveGlobals();
  try {
    relayout(runtime.context, root);
    assert.deepEqual(runtime.ops, [
      "pool.cancelJob",
      "ledger.resolveEngineOptions",
      "contextState.setRuntimeOptions",
      "contextState.paragraphCandidates",
      "domWriteLayer.publishState:keepEmpty",
      "pool.startJob",
    ]);
    assert.deepEqual(runtime.resolveBags, [null]);
    assert.equal(runtime.jobs[0].kind, "Relayout");
  } finally {
    restoreGlobals();
    runtime.restorePool();
  }
});

test("relayout restarts an interrupted Enhance through the canonical resolver", () => {
  const root = fakeElement("tiqian-prose", { ownerDocument: fakeDocument() });
  const runtime = makePlainRuntime(root);
  const restoreGlobals = installDriveGlobals();
  try {
    const seededOptions = seedEstablishedRuntime(runtime, root);
    runtime.ops.length = 0;
    runtime.poolJobKind = "Enhance";

    relayout(runtime.context, root);

    // Branch 1 reuses the running canonical options by reference and keeps
    // the Enhance kind so the finish event stays tiqian:ready.
    assert.equal(runtime.canonicalBags.length, 1);
    assert.equal(runtime.canonicalBags[0], seededOptions);
    assert.deepEqual(runtime.ops, [
      "pool.cancelJob",
      "ledger.resolveEngineOptionsFromCanonical",
      "contextState.setRuntimeOptions",
      "contextState.paragraphCandidates",
      "domWriteLayer.publishState:keepEmpty",
      "pool.startJob",
    ]);
    assert.equal(runtime.jobs.length, 1);
    assert.equal(runtime.jobs[0].kind, "Enhance");
  } finally {
    restoreGlobals();
    runtime.restorePool();
  }
});

test("relayout main path cancels the job and rebuilds a Relayout session", () => {
  const root = fakeElement("tiqian-prose", { ownerDocument: fakeDocument() });
  const runtime = makePlainRuntime(root);
  const restoreGlobals = installDriveGlobals();
  try {
    const seededOptions = seedEstablishedRuntime(runtime, root);
    runtime.ops.length = 0;

    relayout(runtime.context, root);

    const cancelIndex = runtime.ops.indexOf("pool.cancelJob");
    const jobIndex = runtime.ops.indexOf("pool.startJob");
    assert.notEqual(cancelIndex, -1);
    assert.notEqual(jobIndex, -1);
    assert.ok(cancelIndex < jobIndex);
    assert.equal(runtime.jobs.length, 1);
    assert.equal(runtime.jobs[0].kind, "Relayout");
    assert.equal(runtime.jobs[0].itemCount, 0);
    // The dissolved sessionArgument facade is observable only through the
    // session callbacks the job carries.
    assert.equal(typeof runtime.jobs[0].onItemsFinished, "function");
    assert.equal(typeof runtime.jobs[0].onFailure, "function");
    // The runtime options survive an in-place relayout.
    assert.equal(runtime.context.contextState.runtimeOptions, seededOptions);
  } finally {
    restoreGlobals();
    runtime.restorePool();
  }
});

test("destroyRoot restores tracked paragraphs, clears markers, rewrites attributes", () => {
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

  const runtime = makePlainRuntime(root);
  const enhanceContext = runtime.context;
  const restoreGlobals = installDriveGlobals();
  try {
    // Established steady state: two committed paragraphs and one captured
    // capability issue, the way the enhance pass would leave them.
    enhanceContext.contextState.setRuntimeEstablished(true);
    enhanceContext.contextState.paragraphs.push(
      { source: paragraphA, lowered: fakeOf({}), lastMeasure: null },
      { source: paragraphB, lowered: fakeOf({}), lastMeasure: null },
    );
    enhanceContext.diagnosis.issues.push(
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
    // Enhanced steady state: the takeover moved the original content into
    // the raw-DOM backup.
    registerParagraph(enhanceContext, paragraphA);
    registerParagraph(enhanceContext, paragraphB);
    assert.equal(paragraphA.childNodes.length, 0);
    assert.equal(paragraphB.childNodes.length, 0);

    destroyRoot(runtime.context, root);

    // The registry delete dissolved: cancelJob is the only pool touch, and
    // the teardown effects are read off the context state.
    assert.deepEqual(runtime.ops, ["pool.cancelJob"]);
    // The real restore handed the captured original content back to the hosts.
    assert.deepEqual(paragraphA.childNodes, [contentA]);
    assert.deepEqual(paragraphB.childNodes, [contentB]);
    assert.equal(enhanceContext.contextState.runtimeEstablished, false);
    assert.equal(enhanceContext.contextState.paragraphs.length, 0);
    assert.equal(enhanceContext.diagnosis.issues.length, 0);
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
    runtime.restorePool();
  }
});

test("destroyRoot keeps the snapshot-owned enhancement markers", () => {
  const root = fakeElement("tiqian-prose", { ownerDocument: fakeDocument() });
  root.setAttribute("data-tiqian-snapshot-count", "3");
  const runtime = makePlainRuntime(root);
  try {
    destroyRoot(runtime.context, root);

    assert.equal(root.getAttribute("data-tiqian-enhanced"), "true");
    assert.equal(root.getAttribute("data-tiqian-enhanced-count"), "3");
  } finally {
    runtime.restorePool();
  }
});

test("detachRoot cancels the job without touching the runtime state", () => {
  const root = fakeElement("tiqian-prose", { ownerDocument: fakeDocument() });
  const runtime = makePlainRuntime(root);
  const restoreGlobals = installDriveGlobals();
  try {
    const seededOptions = seedEstablishedRuntime(runtime, root);
    runtime.ops.length = 0;

    detachRoot(runtime.context, root);

    assert.deepEqual(runtime.ops, ["pool.cancelJob"]);
    assert.equal(runtime.context.contextState.runtimeEstablished, true);
    assert.equal(runtime.context.contextState.runtimeOptions, seededOptions);
  } finally {
    restoreGlobals();
    runtime.restorePool();
  }
});

test("probeRootContentDrift answers the probe verdict from the context state", () => {
  const root = fakeElement("tiqian-prose", { ownerDocument: fakeDocument() });
  const runtime = makePlainRuntime(root);
  try {
    // No established runtime: the whole root is unknown.
    assert.deepEqual(probeRootContentDrift(runtime.context, root), {
      unknown: 1,
      drifted: 0,
      dead: 0,
      rawDom: 0,
    });

    const restoreGlobals = installDriveGlobals();
    try {
      const clean = fakeElement("p");
      const drifted = fakeElement("p");
      const dead = fakeElement("p", { isConnected: false });
      runtime.context.contextState.setRuntimeEstablished(true);
      runtime.context.contextState.paragraphs.push(
        { source: clean, lowered: fakeOf({}), lastMeasure: null },
        { source: drifted, lowered: fakeOf({}), lastMeasure: null },
        { source: dead, lowered: fakeOf({}), lastMeasure: null },
      );
      registerParagraph(runtime.context, clean);
      registerParagraph(runtime.context, drifted);
      // A host edit through the native mutation path breaks the rendered
      // identity of the drifted paragraph.
      PlainNode.prototype.appendChild.call(drifted, new PlainNode(3, "host edit"));

      assert.deepEqual(probeRootContentDrift(runtime.context, root), {
        unknown: 0,
        drifted: 1,
        dead: 1,
        rawDom: 0,
      });
    } finally {
      restoreGlobals();
    }
  } finally {
    runtime.restorePool();
  }
});

test("reconcileRoot answers null without an established runtime and idle without job dispatch", () => {
  const root = fakeElement("tiqian-prose", { ownerDocument: fakeDocument() });
  const runtime = makePlainRuntime(root);
  try {
    assert.equal(reconcileRoot(runtime.context, root, []), null);

    const restoreGlobals = installDriveGlobals();
    try {
      const clean = fakeElement("p");
      const seededOptions = seedEstablishedRuntime(runtime, root);
      runtime.ops.length = 0;
      runtime.context.contextState.paragraphs.push(
        { source: clean, lowered: fakeOf({}), lastMeasure: null },
      );
      registerParagraph(runtime.context, clean);

      const result = reconcileRoot(runtime.context, root, []);
      assert.deepEqual(result, {
        outcome: "idle",
        drifted: 0,
        rawDom: 0,
        tainted: 0,
        stranded: 0,
        dead: 0,
      });
      // An idle verdict never schedules a reconcile job.
      assert.equal(runtime.jobs.length, 0);
      // The runtime options survive an idle reconcile.
      assert.equal(runtime.context.contextState.runtimeOptions, seededOptions);
    } finally {
      restoreGlobals();
    }
  } finally {
    runtime.restorePool();
  }
});
