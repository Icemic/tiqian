import assert from "node:assert/strict";
import test from "node:test";

import { enhance, enhanceProgressively } from "../core/engine/progressive-drivers.js";
import { destroyRoot, detachRoot } from "../core/engine/lifecycle.js";
import { probeRootContentDrift, reconcileRoot } from "../core/engine/content-reconcile.js";
import { createEnhanceContext } from "../core/engine/context/enhance-context.js";
import { rawDomBegin, rawDomCommit, rawDomTake } from "../core/engine/raw-dom.js";
import { installFixtureFontBackend } from "../test-support/fixture-font-backend.mjs";
import { FakeElement, FakeFragment, FakeNode, FakeText } from "./snapshot-dom-fixtures.mjs";
import { globalServices, initializeGlobalServices } from "../core/services/global-services.js";
import type { EnhancedElementContext } from "../core/engine/context/enhance-context.js";
import type { LayoutJobPool, LayoutJobSpec } from "../core/engine/layout-job-pool.js";
import type { ResolvedEnhanceOptions } from "../core/engine/lifecycle.js";
initializeGlobalServices();

// Type aliases for bridging fake elements with DOM interfaces.
type FakeElementAsHTMLElement = FakeElement & HTMLElement;
type FakeElementAsElement = FakeElement & Element;

const ENV_GLOBALS = ["window", "document", "getComputedStyle", "Node"];

// The named engine functions (progressive-drivers, lifecycle destroy/detach,
// content-reconcile) take the per-element EnhancedElementContext as their
// first parameter; the layout job pool comes from
// globalServices().coordination.layoutJobPool, which withEnv swaps for a fake
// per test. Tests that observe the driver collaborations wrap the context's
// option resolvers, candidate/stranded enumeration and publishState
// projection; specs that drive processParagraph, commit and the raw-DOM
// helpers run the real pipeline and observe the context's raw-DOM records,
// the live paragraph/issue arrays and the live element consequences.

interface SavedGlobal {
  name: string;
  own: boolean;
  value: unknown;
}

interface FakeElementOptions {
  tagName?: string;
  text?: string;
  width?: number;
  isConnected?: boolean;
  childNodes?: Array<{ nodeType: number; textContent: string }>;
  closestTo?: FakeElement | null;
}

interface FakeStyleProps {
  [key: string]: string;
}

interface SetAttributeCall {
  name: string;
  value: string;
}

interface FakeRect {
  top: number;
  bottom: number;
  width: number;
  height: number;
  left: number;
  right: number;
}

type FakeElementFull = FakeElement & {
  nodeType: number;
  tagName: string;
  isConnected?: boolean;
  textContent: string;
  childNodes: Array<{ nodeType: number; textContent: string }>;
  getAttribute: (name: string) => string | null;
  setAttribute: (name: string, value: string) => void;
  removeAttribute: (name: string) => void;
  hasAttribute: (name: string) => boolean;
  closest: (selector: string) => FakeElement | null;
  querySelectorAll: () => [];
  querySelector: () => null;
  style: {
    getPropertyValue: (name: string) => string;
    getPropertyPriority: () => string;
    setProperty: (name: string, value: string) => void;
    removeProperty: (name: string) => void;
    item: () => string;
    length: number;
  };
  getBoundingClientRect: () => FakeRect;
  _rect: FakeRect;
  getClientRects: () => [];
  parentElement: null;
  parentNode: null;
  insertBefore: () => void;
  dispatchEvent: (event: unknown) => boolean;
  events: unknown[];
  attributes: Map<string, string>;
  setAttributes: SetAttributeCall[];
  removedAttributes: string[];
  _computedValues?: Record<string, string>;
};

interface PoolOverrides {
  startJob?: (spec: LayoutJobSpec) => void;
  cancelJob?: (root: Element) => void;
  jobKind?: (root: Element) => string | null;
}

interface FakeLayoutJobPool extends LayoutJobPool {
  _calls: {
    startJob: LayoutJobSpec[];
    cancelJob: Element[];
  };
}

interface WithEnvOverrides extends PoolOverrides {
  computedStyleValues?: Record<string, string>;
  document?: Document & { head: FakeElement };
  node?: typeof Node;
  layoutJobPool?: FakeLayoutJobPool;
}

interface TestContextCalls {
  resolveEngineOptions: Array<{ root: Element; optionsBag: Record<string, unknown> | null }>;
  resolveEngineOptionsFromCanonical: Array<{ root: Element; options: unknown }>;
  paragraphCandidates: Array<{ root: Element; selector: string }>;
  strandedSourceParagraphs: Array<Record<string, never>>;
  publishState: Array<{ paragraphCount: number; issueCount: number; keepEmpty: boolean }>;
}

interface MakeTestContextOverrides {
  candidates?: Element[];
  stranded?: Element[];
}

interface ObservedContext {
  context: EnhancedElementContext;
  calls: TestContextCalls;
  realResolve: (rootElement: Element, optionsBag: Record<string, unknown> | null) => ResolvedEnhanceOptions;
}

function makeElement(initialAttributes: Record<string, string> | undefined, options: FakeElementOptions = {}): FakeElementFull {
  const attrs = new Map(Object.entries(initialAttributes || {}));
  const setAttributes: SetAttributeCall[] = [];
  const removedAttributes: string[] = [];
  const styleProps = new Map<string, string>();
  const text = options.text ?? "hello world";
  const rect: FakeRect = { top: 0, bottom: 100, width: options.width ?? 300, height: 100, left: 0, right: options.width ?? 300 };
  const baseElement = new FakeElement(options.tagName ?? "P");
  for (const [name, value] of attrs) {
    baseElement.setAttribute(name, value);
  }
  (baseElement as any).isConnected = options.isConnected !== false;
  baseElement.width = options.width ?? 300;
  for (const child of (options.childNodes ?? [{ nodeType: 3, textContent: text }])) {
    if (child.nodeType === 3) {
      baseElement.appendChild(new FakeText(child.textContent));
    }
  }
  const element = baseElement as unknown as FakeElementFull;
  (element as any).setAttributes = setAttributes;
  (element as any).removedAttributes = removedAttributes;
  (element as any)._rect = rect;
  (element as any).events = [];
  const originalSetAttribute = element.setAttribute.bind(element);
  element.setAttribute = function (name: string, value: string): void {
    const strVal = String(value);
    originalSetAttribute(name, strVal);
    setAttributes.push({ name, value: strVal });
  };
  const originalRemoveAttribute = element.removeAttribute.bind(element);
  element.removeAttribute = function (name: string): void {
    originalRemoveAttribute(name);
    removedAttributes.push(name);
  };
  (element as any).dispatchEvent = function (event: unknown): boolean {
    (element as any).events.push(event);
    return true;
  };
  const originalGetBCR = element.getBoundingClientRect.bind(element);
  (element as any).getBoundingClientRect = function (): FakeRect {
    const r = (element as any)._rect || rect;
    return { top: r.top, bottom: r.bottom, width: r.width, height: r.bottom - r.top, left: 0, right: r.width };
  };
  (element as any).closest = function (selector: string): FakeElement | null {
    if (options.closestTo && selector === "tiqian-prose, [data-tiqian-root]") {
      return options.closestTo;
    }
    return null;
  };
  return element;
}

function saveEnv(): SavedGlobal[] {
  return ENV_GLOBALS.map((name) => ({
    name,
    own: Object.prototype.hasOwnProperty.call(globalThis, name),
    value: globalThis[name as keyof typeof globalThis],
  }));
}

function restoreEnv(entries: SavedGlobal[]): void {
  for (const { name, own, value } of entries) {
    if (own) (globalThis as Record<string, unknown>)[name] = value;
    else delete (globalThis as Record<string, unknown>)[name];
  }
}

function makeFakeLayoutJobPool(overrides: PoolOverrides = {}): FakeLayoutJobPool {
  const startJobCalls: LayoutJobSpec[] = [];
  const cancelJobCalls: Element[] = [];
  return {
    _calls: { startJob: startJobCalls, cancelJob: cancelJobCalls },
    startJob: function (spec: LayoutJobSpec): void {
      startJobCalls.push(spec);
      if (overrides.startJob) overrides.startJob(spec);
    },
    cancelJob: function (root: Element): void {
      cancelJobCalls.push(root);
      if (overrides.cancelJob) overrides.cancelJob(root);
    },
    jobKind: function (root: Element): string | null {
      return overrides.jobKind ? overrides.jobKind(root) : null;
    },
    isAttached: function (): boolean {
      return false;
    },
    hasJob: function (): boolean {
      return false;
    },
    jobGeneration: function (): number {
      return 0;
    },
    pendingInTier: function (): number {
      return 0;
    },
    paragraphCount: function (): number {
      return 0;
    },
    paragraphAt: function (): Element | null {
      return null;
    },
    setParagraphTier: function (): boolean {
      return false;
    },
    attach: function (): boolean {
      return false;
    },
    detach: function (): boolean {
      return false;
    },
    runSlice: function (): number {
      return 0;
    },
  };
}

// The drivers reach the pool through the coordination service, so the fake
// pool is installed there for the duration of one test.
function installFakePool(pool: FakeLayoutJobPool): () => void {
  const coordination = globalServices().coordination;
  const previous = coordination.layoutJobPool;
  (coordination as unknown as Record<string, unknown>).layoutJobPool = pool as LayoutJobPool;
  return function (): void {
    (coordination as unknown as Record<string, unknown>).layoutJobPool = previous;
  };
}

function withEnv<T>(fn: (pool: FakeLayoutJobPool) => T, overrides: WithEnvOverrides = {}): T {
  const saved = saveEnv();
  const pool = overrides.layoutJobPool ?? makeFakeLayoutJobPool(overrides);
  const restorePool = installFakePool(pool);
  try {
    const computed = (_el: Element, _pseudo?: string | null): CSSStyleDeclaration & { getPropertyValue: (name: string) => string } => {
      const props: Record<string, string> = {
        paddingLeft: "0px",
        paddingRight: "0px",
        borderLeftWidth: "0px",
        borderRightWidth: "0px",
        "line-height": "33px",
        "font-family": "Fixture CJK",
        ...(overrides.computedStyleValues || { "--tq-styles-ready": "1" }),
      };
      const styleObj: Record<string, unknown> = {};
      for (const key of Object.keys(props)) styleObj[key] = props[key];
      styleObj.getPropertyValue = (name: string): string => {
        const key = String(name).toLowerCase();
        return Object.prototype.hasOwnProperty.call(props, key)
          ? String(props[key])
          : "";
      };
      return styleObj as unknown as CSSStyleDeclaration & { getPropertyValue: (name: string) => string };
    };
    (globalThis as Record<string, unknown>).getComputedStyle = computed as typeof getComputedStyle;
    (globalThis as Record<string, unknown>).window = { innerHeight: 800, getComputedStyle: computed } as unknown as Window & typeof globalThis;
    (globalThis as Record<string, unknown>).document = (overrides.document || {
      querySelectorAll: function (): { length: number; item: () => undefined } {
        return { length: 0, item: function (): undefined { return undefined; } };
      },
    }) as unknown as Document & { head: FakeElement };
    if (overrides.node) (globalThis as Record<string, unknown>).Node = overrides.node;
    return fn(pool);
  } finally {
    restorePool();
    restoreEnv(saved);
  }
}

// Paragraph host on the fixture fake-DOM base for specs that drive the real
// pipeline: lowerable children, a measurable box, a parseable innerHTML, and
// a connected steady state.
function makeFixtureParagraphElement(text: string = "hello world"): FakeElement {
  const element = new FakeElement("p") as FakeElement & { width: number; isConnected: boolean };
  (element as any).width = 320;
  (element as any).isConnected = true;
  element.appendChild(new FakeText(text));
  return element;
}

// Fake document for the full pipeline: the fragment factory the raw-DOM
// takeover uses, lowering probe elements, an inert Range, the style head, and
// an inert event surface for the clipboard installer.
function makePipelineDocument(): Document & { head: FakeElement } {
  const documentObject = {
    documentElement: { clientHeight: 800 },
    createElement: (tagName: string): FakeElement => new FakeElement(tagName || "span"),
    createDocumentFragment: (): FakeFragment => new FakeFragment(),
    createRange: (): Range => ({
      selectNodeContents() {},
      getClientRects: (): DOMRectList => [] as unknown as DOMRectList,
    } as unknown as Range),
    addEventListener: (): void => {},
    removeEventListener: (): void => {},
  } as unknown as Document;
  (documentObject as any).head = new FakeElement("head");
  return documentObject as Document & { head: FakeElement };
}

// Registers the paragraph with the context's raw-DOM bookkeeping exactly the
// way the enhance pass did, so restore/probe/match paths find the record.
function registerParagraph(context: EnhancedElementContext, source: Element): void {
  rawDomBegin(context, source, null, null, null, null, null, null, "", "", "", "", "", "", null);
  rawDomTake(context, source, null);
  rawDomCommit(context, source, null);
}

// A real EnhancedElementContext whose driver-observable part surface is
// wrapped with recording spies: the two option resolvers dissolved from
// root-state's createRootState/createRootStateFromCanonical, the candidate
// and stranded enumerations, and the publishState projection. The wrappers
// delegate to the real implementations; overrides.candidates and
// overrides.stranded substitute fixed enumerations for the seeded steady
// states.
function makeTestContext(root: Element, overrides: MakeTestContextOverrides = {}): ObservedContext {
  const context = createEnhanceContext(root);
  const calls: TestContextCalls = {
    resolveEngineOptions: [],
    resolveEngineOptionsFromCanonical: [],
    paragraphCandidates: [],
    strandedSourceParagraphs: [],
    publishState: [],
  };

  const ledger = context.optionsLedger;
  const realResolve = ledger.resolveEngineOptions as (rootElement: Element, optionsBag: Record<string, unknown> | null) => ResolvedEnhanceOptions;
  const realResolveCanonical = ledger.resolveEngineOptionsFromCanonical;
  ledger.resolveEngineOptions = function (rootElement: Element, optionsBag: Record<string, unknown> | null): ResolvedEnhanceOptions {
    calls.resolveEngineOptions.push({ root: rootElement, optionsBag: optionsBag });
    return realResolve(rootElement, optionsBag ?? {});
  };
  ledger.resolveEngineOptionsFromCanonical = function (rootElement: Element, options: unknown): ResolvedEnhanceOptions {
    calls.resolveEngineOptionsFromCanonical.push({ root: rootElement, options });
    return realResolveCanonical(rootElement, options as any);
  };

  const state = context.contextState;
  const realCandidates = state.paragraphCandidates;
  state.paragraphCandidates = function (rootElement: Element, selector: string): Element[] {
    calls.paragraphCandidates.push({ root: rootElement, selector });
    return overrides.candidates ?? realCandidates(rootElement, selector);
  };

  const sync = context.effectSync;
  const realStranded = sync.strandedSourceParagraphs;
  sync.strandedSourceParagraphs = function (): Element[] {
    calls.strandedSourceParagraphs.push({});
    return overrides.stranded ?? realStranded();
  };

  const write = context.domWriteLayer;
  const realPublish = write.publishState;
  write.publishState = function (paragraphCount: number, issueCount: number, keepEmpty: boolean): void {
    calls.publishState.push({
      paragraphCount,
      issueCount,
      keepEmpty,
    });
    return realPublish(paragraphCount, issueCount, keepEmpty);
  };

  return { context, calls, realResolve };
}

// Seeds the enhanced steady state the former getState(root) branch read: the
// resolved runtime options on the context state, an established typography
// runtime, and the runtime-established flag. Uses the unwrapped resolver so
// seeding never pollutes the recorded calls.
function seedEstablishedRuntime(observed: ObservedContext, root: Element, optionsBag: Record<string, unknown> | null): ResolvedEnhanceOptions {
  const resolved = observed.realResolve(root, optionsBag);
  observed.context.contextState.setRuntimeOptions(resolved);
  observed.context.typography.establishRuntime(root, resolved);
  observed.context.contextState.setRuntimeEstablished(true);
  return resolved;
}

// The fixture font backend's synchronous callbacks are the shaping bridge the
// prepare step consumes through the typography browser-fallback descriptor.
// The descriptor lives behind a getter on the typography part, so the fixture
// override redefines the accessor.
function installFixtureBrowserFallback(context: EnhancedElementContext): { uninstall: () => void; shapeJson: (requestJson: string) => string; metricsJson: (requestJson: string) => string } {
  const backend = installFixtureFontBackend();
  Object.defineProperty(context.typography, "browserFallback", {
    configurable: true,
    get: function (): { bridge: { shapeJson: (requestJson: string) => string; metricsJson: (requestJson: string) => string } } {
      return { bridge: backend };
    },
  });
  return backend;
}

// ---------------------------------------------------------------------------
// 1. enhance: synchronous loop processes candidates, returns count, publishes
// ---------------------------------------------------------------------------

test("1. enhance: processes each candidate via the real processParagraph, returns paragraphs.length, calls publishState", function () {
  const c1 = makeFixtureParagraphElement() as unknown as Element;
  const c2 = makeFixtureParagraphElement() as unknown as Element;
  withEnv(() => {
    const root = makeElement(undefined) as unknown as FakeElementFull;
    const observed = makeTestContext((root as unknown as FakeElementFull) as unknown as Element as unknown as Element, { candidates: [c1, c2] });
    installFixtureBrowserFallback(observed.context);
    const result = enhance(observed.context, root as unknown as HTMLElement, { fontSize: 20 });
    assert.equal(observed.calls.resolveEngineOptions.length, 1);
    assert.equal(observed.calls.resolveEngineOptions[0].optionsBag!.fontSize, 20);
    // The real processParagraph ran once per candidate, observable through
    // the raw-DOM records registered on the enhance context.
    const record1 = observed.context.rawDomParagraphs.get(c1);
    const record2 = observed.context.rawDomParagraphs.get(c2);
    assert.ok(record1);
    assert.ok(record2);
    assert.equal(record1.originalContent!.textContent, "hello world");
    assert.equal(record2.originalContent!.textContent, "hello world");
    assert.equal(observed.calls.publishState.length, 1);
    assert.equal(observed.calls.publishState[0].paragraphCount, 2);
    assert.equal(result, 2);
  }, { document: makePipelineDocument(), node: FakeNode as unknown as typeof Node });
});

// ---------------------------------------------------------------------------
// 2. enhance styles gate: rejectMissingSharedRuntimeStyles returns true => 0
// ---------------------------------------------------------------------------

test("2. enhance: rejectMissingSharedRuntimeStyles returns true => returns 0, no processParagraph", function () {
  withEnv(() => {
    const root = makeElement(undefined) as unknown as FakeElementFull;
    const observed = makeTestContext((root as unknown as FakeElementFull) as unknown as Element as unknown as Element, { candidates: [makeElement(undefined) as unknown as FakeElementFull as unknown as Element] });
    const result = enhance(observed.context, root as unknown as HTMLElement, {});
    assert.equal(result, 0);
    assert.equal(observed.context.rawDomParagraphs.size, 0);
    assert.equal(observed.context.diagnosis.issues.length, 1);
    assert.equal(observed.context.diagnosis.issues[0].name, "MissingSharedRuntimeStyles");
  }, { computedStyleValues: { "--tq-styles-ready": "0" } });
});

// ---------------------------------------------------------------------------
// 3. enhance destroy-first: destroyRoot runs before the options resolution
// ---------------------------------------------------------------------------

test("3. enhance: destroyRoot runs before resolveEngineOptions (call order)", function () {
  const callOrder: string[] = [];
  withEnv((pool) => {
    const root = makeElement(undefined) as unknown as FakeElementFull;
    const observed = makeTestContext((root as unknown as FakeElementFull) as unknown as Element as unknown as Element, { candidates: [] });
    const wrappedResolve = observed.context.optionsLedger.resolveEngineOptions;
    observed.context.optionsLedger.resolveEngineOptions = function (rootElement: Element, bag: Record<string, unknown> | null): ResolvedEnhanceOptions {
      callOrder.push("ledger.resolveEngineOptions");
      return wrappedResolve(rootElement, bag ?? {});
    };
    enhance(observed.context, root as unknown as HTMLElement, {});
    assert.deepEqual(callOrder, ["pool.cancelJob", "ledger.resolveEngineOptions"]);
    assert.equal(pool._calls.cancelJob.length, 1);
  }, { cancelJob: () => callOrder.push("pool.cancelJob") });
});

// ---------------------------------------------------------------------------
// 4. enhanceProgressively: destroy, rebuild, one Enhance job
// ---------------------------------------------------------------------------

test("4. enhanceProgressively destroys, rebuilds the runtime options and starts one Enhance job", function () {
  withEnv((pool) => {
    const root = makeElement(undefined) as unknown as FakeElementFull;
    const observed = makeTestContext((root as unknown as FakeElementFull) as unknown as Element, { candidates: [] });
    const bag: Record<string, unknown> = { fontSize: 20 };
    enhanceProgressively(observed.context, root as unknown as Element, bag);
    // The drivers core cancels the job before rebuilding the runtime.
    assert.equal(pool._calls.cancelJob.length, 1);
    assert.equal(pool._calls.cancelJob[0], root);
    assert.equal(observed.calls.resolveEngineOptions.length, 1);
    assert.equal(observed.calls.resolveEngineOptions[0].optionsBag, bag);
    // The real startLayoutJob starts one Enhance job.
    assert.equal(pool._calls.startJob.length, 1);
    assert.equal(pool._calls.startJob[0].kind, "Enhance");
  });
});

// ---------------------------------------------------------------------------
// 5. destroyRoot: full steps -- restoreParagraph, clearIssue, releaseRoot,
//    snapshot-count branches, attribute cleanup
// ---------------------------------------------------------------------------

test("5. destroyRoot: restores paragraphs, clears issues, releases styles, sets/removes snapshot attrs, cleans 3 attrs", function () {
  const src1 = makeFixtureParagraphElement() as unknown as Element;
  const src2 = makeFixtureParagraphElement() as unknown as Element;
  const issue1 = {
    name: "X",
    element: src1,
    markerCaptured: true,
    originalNameAttribute: "orig-name",
    originalDetailAttribute: "orig-detail",
  };
  const issue2 = {
    name: "Y",
    element: src2,
    markerCaptured: true,
    originalNameAttribute: "orig-name-2",
    originalDetailAttribute: "orig-detail-2",
  };
  withEnv((pool) => {
    const root = makeElement({ "data-tiqian-snapshot-count": "5", "data-tiqian-issue-count": "2", "data-tiqian-relayout-error": "err", "data-tiqian-snapshot-layout-fallback": "fb" });
    const context = createEnhanceContext(root as unknown as Element);
    context.contextState.setRuntimeEstablished(true);
    context.contextState.paragraphs.push({ source: src1 } as unknown as import("../core/engine/enhance/context-state.js").TrackedParagraph, { source: src2 } as unknown as import("../core/engine/enhance/context-state.js").TrackedParagraph);
    context.diagnosis.issues.push(issue1 as any, issue2 as any);
    // Enhanced steady state: both paragraphs carry raw-DOM records and their
    // live hosts show rendered output.
    registerParagraph(context, src1 as unknown as Element);
    registerParagraph(context, src2 as unknown as Element);
    (src1 as unknown as Element).innerHTML = "rendered one";
    (src2 as unknown as Element).innerHTML = "rendered two";
    destroyRoot(context, root as unknown as HTMLElement);
    // The registry dissolution observes the teardown through the context:
    // cancelJob ran, the runtime-established flag reset, and both live arrays
    // were cleared.
    assert.equal(pool._calls.cancelJob.length, 1);
    assert.equal(pool._calls.cancelJob[0], root);
    assert.equal(context.contextState.runtimeEstablished, false);
    assert.equal(context.contextState.paragraphs.length, 0);
    assert.equal(context.diagnosis.issues.length, 0);
    // restoreParagraph handed the captured original content back to the hosts.
    assert.equal(src1.textContent, "hello world");
    assert.equal(src2.textContent, "hello world");
    // clearIssue restored the captured original attributes.
    assert.equal(issue1.markerCaptured, false);
    assert.equal(issue2.markerCaptured, false);
    assert.equal(src1.getAttribute("data-tiqian-capability-issue"), "orig-name");
    assert.equal(src2.getAttribute("data-tiqian-capability-detail"), "orig-detail-2");
    assert.equal(root.getAttribute("data-tiqian-enhanced"), "true");
    assert.equal(root.getAttribute("data-tiqian-enhanced-count"), "5");
    assert.equal(root.getAttribute("data-tiqian-issue-count"), null);
    assert.equal(root.getAttribute("data-tiqian-relayout-error"), null);
    assert.equal(root.getAttribute("data-tiqian-snapshot-layout-fallback"), null);
  }, { document: makePipelineDocument(), node: FakeNode as unknown as typeof Node });
});

// ---------------------------------------------------------------------------
// 6. destroyRoot no state: still cancelJob + attribute cleanup, no throw
// ---------------------------------------------------------------------------

test("6. destroyRoot: no established runtime => still cancelJob + attribute cleanup, no throw", function () {
  withEnv((pool) => {
    const root = makeElement({ "data-tiqian-relayout-error": "err" });
    const context = createEnhanceContext(root as unknown as Element);
    destroyRoot(context, root as unknown as HTMLElement);
    assert.equal(pool._calls.cancelJob.length, 1);
    assert.equal(context.rawDomParagraphs.size, 0);
    assert.equal(root.getAttribute("data-tiqian-relayout-error"), null);
    assert.equal(root.getAttribute("data-tiqian-enhanced"), null);
  });
});

// ---------------------------------------------------------------------------
// 7. detachRoot: minimal surface -- cancelJob only, context state untouched
// ---------------------------------------------------------------------------

test("7. detachRoot: cancelJob only, does not touch the context state", function () {
  withEnv((pool) => {
    const root = makeElement(undefined) as unknown as FakeElementFull;
    const observed = makeTestContext(root as unknown as Element);
    const runtimeOptions = seedEstablishedRuntime(observed, root as unknown as Element, { fontSize: 19 });
    const paragraph = { source: makeFixtureParagraphElement() as unknown as Element, lowered: {}, lastMeasure: null };
    observed.context.contextState.paragraphs.push(paragraph as any);

    detachRoot(observed.context, root as unknown as HTMLElement);

    assert.equal(pool._calls.cancelJob.length, 1);
    assert.equal(pool._calls.cancelJob[0], root);
    // The former getState/deleteState registry assertions dissolve into the
    // surviving context state: established flag, options and paragraphs stay.
    assert.equal(observed.context.contextState.runtimeEstablished, true);
    assert.equal(observed.context.contextState.runtimeOptions, runtimeOptions);
    assert.equal(observed.context.contextState.paragraphs.length, 1);
    assert.equal(observed.context.contextState.paragraphs[0], paragraph);
  });
});

// ---------------------------------------------------------------------------
// 8. probeRootContentDrift: not established => unknown result; established =>
//    the context's tracked sources classify through the real probe, which
//    reads the fake detached-fragment backup ledger
// ---------------------------------------------------------------------------

test("8. probeRootContentDrift: not established returns the unknown result; established classifies the context's sources", function () {
  withEnv(() => {
    const root = makeElement(undefined) as unknown as FakeElementFull;
    const observed = makeTestContext(root as unknown as Element);
    const result = probeRootContentDrift(observed.context, root as unknown as Element);
    assert.deepEqual(result, { unknown: 1, drifted: 0, dead: 0, rawDom: 0 });
  });

  const src1 = makeFixtureParagraphElement() as unknown as Element;
  const src2 = makeFixtureParagraphElement() as unknown as Element;
  withEnv(() => {
    const root = makeElement(undefined) as unknown as FakeElementFull;
    const observed = makeTestContext(root as unknown as Element);
    seedEstablishedRuntime(observed, root as unknown as Element, {});
    observed.context.contextState.paragraphs.push({ source: src1 } as unknown as import("../core/engine/enhance/context-state.js").TrackedParagraph, { source: src2 } as unknown as import("../core/engine/enhance/context-state.js").TrackedParagraph);
    registerParagraph(observed.context, src1);
    registerParagraph(observed.context, src2);
    const result2 = probeRootContentDrift(observed.context, root as unknown as Element);
    // The real probe classified both registered sources through the
    // context's raw-DOM records: their rendered and backup identities match,
    // so nothing drifted, died or fell out of the raw-DOM backup.
    assert.deepEqual(result2, { unknown: 0, drifted: 0, dead: 0, rawDom: 0 });
  }, { document: makePipelineDocument(), node: FakeNode as unknown as typeof Node });
});

// ---------------------------------------------------------------------------
// 9. reconcileRoot: not established => null; established + idle => result, no
//    job; with work verdict => actions per category + job
// ---------------------------------------------------------------------------

test("9a. reconcileRoot: not established returns null", function () {
  withEnv(() => {
    const root = makeElement(undefined) as unknown as FakeElementFull;
    const observed = makeTestContext(root as unknown as Element);
    const result = reconcileRoot(observed.context, root as unknown as HTMLElement, []);
    assert.equal(result, null);
  });
});

test("9b. reconcileRoot: established + idle verdict => returns the result, no startLayoutJob", function () {
  const source = makeFixtureParagraphElement() as unknown as Element;
  withEnv((pool) => {
    const root = makeElement(undefined) as unknown as FakeElementFull;
    const observed = makeTestContext((root as unknown as FakeElementFull) as unknown as Element as unknown as Element, { candidates: [] });
    seedEstablishedRuntime(observed, root as unknown as Element, {});
    observed.context.contextState.paragraphs.push({ source: source } as unknown as import("../core/engine/enhance/context-state.js").TrackedParagraph);
    registerParagraph(observed.context, source);
    const result = reconcileRoot(observed.context, root as unknown as HTMLElement, []);
    assert.deepEqual(result, { outcome: "idle", drifted: 0, rawDom: 0, tainted: 0, stranded: 0, dead: 0 });
    assert.equal(pool._calls.startJob.length, 0);
  }, { document: makePipelineDocument(), node: FakeNode as unknown as typeof Node });
});

test("9c. reconcileRoot: work verdict with drifted/rawDom/tainted/stranded + DeadTrackedParagraphDrop", function () {
  const deadEl = makeFixtureParagraphElement() as unknown as Element;
  (deadEl as any).isConnected = false;
  const driftedEl = makeFixtureParagraphElement() as unknown as Element;
  const rawDomEl = makeFixtureParagraphElement() as unknown as Element;
  const taintedEl = makeFixtureParagraphElement() as unknown as Element;
  // The tainted host stays only when connected inside a root.
  const proseRoot = new FakeElement("tiqian-prose");
  proseRoot.appendChild(taintedEl as unknown as FakeNode);
  const strandedEl = makeFixtureParagraphElement() as unknown as Element;
  // Engine scaffolding the stranded action must strip before re-lowering.
  strandedEl.setAttribute("data-tq-snapshot-prepared-dom", "true");
  withEnv((pool) => {
    const root = makeElement(undefined) as unknown as FakeElementFull;
    const observed = makeTestContext((root as unknown as FakeElementFull) as unknown as Element, {
      candidates: [],
      stranded: [strandedEl],
    });
    seedEstablishedRuntime(observed, root as unknown as Element, {});
    installFixtureBrowserFallback(observed.context);
    const context = observed.context;
    context.contextState.paragraphs.push(
      { source: deadEl } as unknown as import("../core/engine/enhance/context-state.js").TrackedParagraph,
      { source: driftedEl } as unknown as import("../core/engine/enhance/context-state.js").TrackedParagraph,
      { source: rawDomEl } as unknown as import("../core/engine/enhance/context-state.js").TrackedParagraph,
      { source: taintedEl } as unknown as import("../core/engine/enhance/context-state.js").TrackedParagraph,
    );
    registerParagraph(context, deadEl as unknown as Element);
    registerParagraph(context, driftedEl as unknown as Element);
    registerParagraph(context, rawDomEl as unknown as Element);
    registerParagraph(context, taintedEl as unknown as Element);
    // Host edits: the drifted paragraph's rendered children changed through
    // innerHTML (which bypasses the commit forwarding), and the raw-DOM
    // backup of the second gained content while its rendered output stayed.
    (driftedEl as unknown as Element).innerHTML = "edited live";
    context.rawDomParagraphs.get(rawDomEl as unknown as Element)!.originalContent!.appendChild(new FakeText(" host edit") as unknown as Node);

    const result = reconcileRoot(context, root as unknown as HTMLElement, [taintedEl]);
    // DeadTrackedParagraphDrop: deadEl removed from the context's paragraphs.
    assert.equal(context.contextState.paragraphs.length, 3);
    assert.equal(context.contextState.paragraphs[0].source, driftedEl);
    assert.equal(context.contextState.paragraphs[1].source, rawDomEl);
    assert.equal(context.contextState.paragraphs[2].source, taintedEl);
    // The real classifyReconcile produced the expected verdict.
    assert.deepEqual(result, { outcome: "work", drifted: 1, rawDom: 1, tainted: 1, stranded: 1, dead: 1 });
    // startLayoutJob called with kind Relayout and 4 actions.
    assert.equal(pool._calls.startJob.length, 1);
    const call = pool._calls.startJob[0];
    assert.equal(call.kind, "Relayout");
    assert.equal(call.itemCount, 4);
    // Execute processItem callbacks to verify action effects.
    for (let i = 0; i < call.itemTierIndex!.length; i += 1) {
      call.processItem(i);
    }
    // Every action re-processed its paragraph through the real pipeline, so
    // all four end up committed back into the context's paragraphs in tier
    // order.
    assert.equal(context.contextState.paragraphs.length, 4);
    assert.deepEqual(
      context.contextState.paragraphs.map(function (item) { return item.source; }),
      [driftedEl, rawDomEl, taintedEl, strandedEl],
    );
    for (const item of context.contextState.paragraphs) {
      assert.equal(typeof item.lastMeasure, "number");
      assert.ok(context.rawDomParagraphs.get(item.source));
    }
    // The drifted action re-lowered the live edit.
    assert.ok((driftedEl as unknown as Element).textContent.includes("edited live"));
    // The raw-DOM action restored the edited backup and re-lowered it.
    assert.ok((rawDomEl as unknown as Element).textContent.includes("host edit"));
    // stripEngineMarkupFromStrandedParagraph removed the scaffolding marker
    // before re-lowering, and the re-process rendered the paragraph again.
    assert.equal(strandedEl.getAttribute("data-tq-snapshot-prepared-dom"), null);
    assert.equal(strandedEl.getAttribute("data-tq-rendered"), "true");
  }, { document: makePipelineDocument(), node: FakeNode as unknown as typeof Node });
});

test("9d. reconcileRoot: itemTierIndex sorted by (distance, index), stale closure detects width drift >= 0.5", function () {
  const el1 = makeFixtureParagraphElement() as unknown as Element;
  (el1 as any).top = -200;
  (el1 as any).height = 100;
  const el2 = makeFixtureParagraphElement() as unknown as Element;
  withEnv((pool) => {
    const root = makeElement(undefined) as unknown as FakeElementFull;
    (root as any)._rect = { top: 0, bottom: 100, width: 300 };
    const observed = makeTestContext((root as unknown as FakeElementFull) as unknown as Element as unknown as Element, { candidates: [] });
    seedEstablishedRuntime(observed, root as unknown as Element, {});
    const context = observed.context;
    context.contextState.paragraphs.push({ source: el1 } as unknown as import("../core/engine/enhance/context-state.js").TrackedParagraph, { source: el2 } as unknown as import("../core/engine/enhance/context-state.js").TrackedParagraph);
    registerParagraph(context, el1 as unknown as Element);
    registerParagraph(context, el2 as unknown as Element);
    // Both paragraph drift: the host replaced their rendered children.
    (el1 as any).innerHTML = "edited one";
    (el2 as any).innerHTML = "edited two";
    reconcileRoot(context, root as unknown as HTMLElement, []);
    assert.equal(pool._calls.startJob.length, 1);
    const call = pool._calls.startJob[0];
    // el2 visible (distance 0) first, then el1 above viewport (distance 100).
    assert.deepEqual(call.itemTierIndex, [1, 0]);
    // stale closure: root width matches initially.
    assert.equal(call.isStale!(), false);
    // After root width drift of 1.0.
    (root as any)._rect.width = 301;
    assert.equal(call.isStale!(), true);
  }, { document: makePipelineDocument(), node: FakeNode as unknown as typeof Node });
});
