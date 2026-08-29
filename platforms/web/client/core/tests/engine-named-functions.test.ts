import assert from "node:assert/strict";
import test from "node:test";

import { enhance, enhanceProgressively } from "../src/engine/progressive-drivers.js";
import { destroyRoot, detachRoot } from "../src/engine/lifecycle.js";
import { probeRootContentDrift, reconcileRoot } from "../src/engine/content-reconcile.js";
import { createEnhanceContext } from "../src/engine/context/enhance-context.js";
import { rawDomBegin, rawDomCommit, rawDomTake } from "../src/engine/raw-dom.js";
import { installFixtureFontBackend } from "./fixture-font-backend.js";
import { FakeElement, FakeFragment, FakeNode, FakeText, asNode, asNodeConstructor, emptyDomRectList } from "./snapshot-dom-fixtures.js";
import { globalServices, initializeGlobalServices } from "../src/services/global-services.js";
import type { EnhancedElementContext } from "../src/engine/context/enhance-context.js";
import type { LayoutJobPool, LayoutJobSpec } from "../src/engine/layout-job-pool.js";
import type { EnhanceOptions, ResolvedEnhanceOptions } from "../src/engine/lifecycle.js";
import type { BrowserFallbackDescriptor } from "../src/engine/enhance/typography.js";
import type { DiagnosisIssueRecord } from "../src/engine/context/diagnosis-manager.js";
import type { TrackedParagraph } from "../src/engine/enhance/context-state.js";
import type { FixtureFontBackend } from "./fixture-font-backend.js";
import type { CoordinationPoolSlot, GlobalEntry, Thunk } from "./types.js";
initializeGlobalServices();

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

interface FakeElementOptions {
  tagName?: string;
  text?: string;
  width?: number;
  isConnected?: boolean;
  childNodes?: FakeChildDescriptor[];
  closestTo?: FakeElement | null;
}

interface FakeChildDescriptor {
  nodeType: number;
  textContent: string;
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

// The rect fields the instrumented root's recording getBoundingClientRect
// reads; tests may seed a partial rect (top/bottom/width).
interface InstrumentedRect {
  top: number;
  bottom: number;
  width: number;
}

// The recording additions the instrumented root carries beyond the fake
// element surface: connection state, scriptable rect, event log, attribute
// bookkeeping and a fixture dispatchEvent.
interface InstrumentedExtras {
  isConnected: boolean;
  _rect: InstrumentedRect;
  events: unknown[];
  setAttributes: SetAttributeCall[];
  removedAttributes: string[];
  dispatchEvent(event: unknown): boolean;
}

type InstrumentedBase = FakeElement & InstrumentedExtras;
type InstrumentedElement = InstrumentedBase & HTMLElement;

interface PoolOverrides {
  startJob?(spec: LayoutJobSpec): void;
  cancelJob?(root: Element): void;
  jobKind?(root: Element): string | null;
}

interface FakePoolCalls {
  startJob: LayoutJobSpec[];
  cancelJob: Element[];
}

interface FakeLayoutJobPool extends LayoutJobPool {
  _calls: FakePoolCalls;
}

// The fixture document replaces the real style head with a fake element.
interface FakeHeadSlot {
  head: FakeElement;
}

type PipelineDocument = Document & FakeHeadSlot;

interface WithEnvOverrides extends PoolOverrides {
  computedStyleValues?: Record<string, string>;
  document?: PipelineDocument;
  node?: typeof Node;
  layoutJobPool?: FakeLayoutJobPool;
}

interface PoolTestFn<T> {
  (pool: FakeLayoutJobPool): T;
}

interface ResolveEngineOptionsCall {
  root: Element;
  optionsBag: Record<string, unknown> | null;
}

interface ResolveCanonicalOptionsCall {
  root: Element;
  options: unknown;
}

interface ParagraphCandidateCall {
  root: Element;
  selector: string;
}

interface PublishStateCall {
  paragraphCount: number;
  issueCount: number;
  keepEmpty: boolean;
}

interface TestContextCalls {
  resolveEngineOptions: ResolveEngineOptionsCall[];
  resolveEngineOptionsFromCanonical: ResolveCanonicalOptionsCall[];
  paragraphCandidates: ParagraphCandidateCall[];
  strandedSourceParagraphs: Record<string, never>[];
  publishState: PublishStateCall[];
}

interface MakeTestContextOverrides {
  candidates?: Element[];
  stranded?: Element[];
}

// The resolver view tests seed through: the real ledger resolver takes a
// non-null bag, seeding passes the bag through nullable parameters.
interface NullableOptionsResolver {
  (rootElement: Element, optionsBag: Record<string, unknown> | null): ResolvedEnhanceOptions;
}

interface ObservedContext {
  context: EnhancedElementContext;
  calls: TestContextCalls;
  realResolve: NullableOptionsResolver;
}

function makeElement(initialAttributes: Record<string, string> | undefined, options: FakeElementOptions = {}): InstrumentedElement {
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
  const element = baseElement as InstrumentedBase;
  element.isConnected = options.isConnected !== false;
  element.width = options.width ?? 300;
  for (const child of (options.childNodes ?? [{ nodeType: 3, textContent: text }])) {
    if (child.nodeType === 3) {
      element.appendChild(new FakeText(child.textContent));
    }
  }
  element.setAttributes = setAttributes;
  element.removedAttributes = removedAttributes;
  element._rect = rect;
  element.events = [];
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
  element.dispatchEvent = function (event: unknown): boolean {
    element.events.push(event);
    return true;
  };
  const originalGetBCR = element.getBoundingClientRect.bind(element);
  element.getBoundingClientRect = function (): FakeRect {
    const r = element._rect || rect;
    return { top: r.top, bottom: r.bottom, width: r.width, height: r.bottom - r.top, left: 0, right: r.width };
  };
  element.closest = function (selector: string): FakeElement | null {
    if (options.closestTo && selector === "tiqian-prose, [data-tiqian-root]") {
      return options.closestTo;
    }
    return null;
  };
  return element as InstrumentedElement;
}

function saveEnv(): GlobalEntry[] {
  return ENV_GLOBALS.map((name) => ({
    name,
    own: Object.prototype.hasOwnProperty.call(globalThis, name),
    value: (globalThis as Record<string, unknown>)[name],
  }));
}

function restoreEnv(entries: GlobalEntry[]): void {
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
function installFakePool(pool: FakeLayoutJobPool): Thunk<void> {
  const coordination = globalServices().coordination as CoordinationPoolSlot;
  const previous = coordination.layoutJobPool;
  coordination.layoutJobPool = pool;
  return function (): void {
    coordination.layoutJobPool = previous;
  };
}

interface EmptyQueryResult {
  length: number;
  item(): undefined;
}

interface MinimalDocumentView {
  querySelectorAll(): unknown;
}

function withEnv<T>(fn: PoolTestFn<T>, overrides: WithEnvOverrides = {}): T {
  const saved = saveEnv();
  const pool = overrides.layoutJobPool ?? makeFakeLayoutJobPool(overrides);
  const restorePool = installFakePool(pool);
  try {
    const computed = (_el: Element, _pseudo?: string | null): CSSStyleDeclaration => {
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
      const styleValue: unknown = styleObj;
      return styleValue as CSSStyleDeclaration;
    };
    (globalThis as Record<string, unknown>).getComputedStyle = computed;
    const fakeWindow: Pick<Window, "innerHeight" | "getComputedStyle"> = { innerHeight: 800, getComputedStyle: computed };
    (globalThis as Record<string, unknown>).window = fakeWindow as Window & typeof globalThis;
    const defaultDocument: MinimalDocumentView = {
      querySelectorAll: function (): EmptyQueryResult {
        return { length: 0, item: function (): undefined { return undefined; } };
      },
    };
    (globalThis as Record<string, unknown>).document = (overrides.document || defaultDocument) as PipelineDocument;
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
interface ConnectedState {
  isConnected: boolean;
}

type FixtureParagraphElement = FakeElement & Element & ConnectedState;
type FixtureParagraphBase = FakeElement & ConnectedState;

function makeFixtureParagraphElement(text: string = "hello world"): FixtureParagraphElement {
  const element = new FakeElement("p") as FixtureParagraphBase;
  element.width = 320;
  element.isConnected = true;
  element.appendChild(new FakeText(text));
  return element as FixtureParagraphElement;
}

// Fake document for the full pipeline: the fragment factory the raw-DOM
// takeover uses, lowering probe elements, an inert Range, the style head, and
// an inert event surface for the clipboard installer.
interface RootMetrics {
  clientHeight: number;
}

interface InertRange {
  selectNodeContents(): void;
  getClientRects(): DOMRectList;
}

interface PipelineDocumentLiteral {
  documentElement: RootMetrics;
  createElement(tagName: string): unknown;
  createDocumentFragment(): unknown;
  createRange(): unknown;
  addEventListener(type: string, listener: unknown): void;
  removeEventListener(type: string, listener: unknown): void;
  head: FakeElement;
}

function makePipelineDocument(): PipelineDocument {
  const documentObject: PipelineDocumentLiteral = {
    documentElement: { clientHeight: 800 },
    createElement: (tagName: string): FakeElement => new FakeElement(tagName || "span"),
    createDocumentFragment: (): FakeFragment => new FakeFragment(),
    createRange: (): InertRange => ({
      selectNodeContents: function (): void {},
      getClientRects: function (): DOMRectList { return emptyDomRectList(); },
    }),
    addEventListener: (): void => {},
    removeEventListener: (): void => {},
    head: new FakeElement("head"),
  };
  return documentObject as PipelineDocument;
}

// Registers the paragraph with the context's raw-DOM bookkeeping exactly the
// way the enhance pass did, so restore/probe/match paths find the record.
function registerParagraph(context: EnhancedElementContext, source: Element): void {
  rawDomBegin(context, source, null, null, null, null, null, null, "", "", "", "", "", "", null);
  rawDomTake(context, source, null);
  rawDomCommit(context, source, null);
}

// Source-only TrackedParagraph stub: the lifecycle and reconcile reads these
// seeded records resolve through the source; the remaining fields stay
// unread inside the live array.
function trackedParagraphStub(source: Element): TrackedParagraph {
  const stub: Pick<TrackedParagraph, "source"> = { source };
  return stub as TrackedParagraph;
}

// Test 7's minimal paragraph seed: its lowered payload is never built before
// the detach the spec verifies.
interface SeededParagraph {
  source: Element;
  lowered: unknown;
  lastMeasure: number | null;
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
  const realResolve = ledger.resolveEngineOptions as NullableOptionsResolver;
  const realResolveCanonical = ledger.resolveEngineOptionsFromCanonical;
  ledger.resolveEngineOptions = function (rootElement: Element, optionsBag: Record<string, unknown> | null): ResolvedEnhanceOptions {
    calls.resolveEngineOptions.push({ root: rootElement, optionsBag: optionsBag });
    return realResolve(rootElement, optionsBag ?? {});
  };
  ledger.resolveEngineOptionsFromCanonical = function (rootElement: Element, options: unknown): ResolvedEnhanceOptions {
    calls.resolveEngineOptionsFromCanonical.push({ root: rootElement, options });
    return realResolveCanonical(rootElement, options as EnhanceOptions);
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
function installFixtureBrowserFallback(context: EnhancedElementContext): FixtureFontBackend {
  const backend = installFixtureFontBackend();
  Object.defineProperty(context.typography, "browserFallback", {
    configurable: true,
    get: function (): BrowserFallbackDescriptor {
      return { bridge: backend };
    },
  });
  return backend;
}

// ---------------------------------------------------------------------------
// 1. enhance: synchronous loop processes candidates, returns count, publishes
// ---------------------------------------------------------------------------

test("1. enhance: processes each candidate via the real processParagraph, returns paragraphs.length, calls publishState", function () {
  const c1 = makeFixtureParagraphElement();
  const c2 = makeFixtureParagraphElement();
  withEnv(() => {
    const root = makeElement(undefined);
    const observed = makeTestContext(root, { candidates: [c1, c2] });
    installFixtureBrowserFallback(observed.context);
    const result = enhance(observed.context, root, { fontSize: 20 });
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
  }, { document: makePipelineDocument(), node: asNodeConstructor(FakeNode) });
});

// ---------------------------------------------------------------------------
// 2. enhance styles gate: rejectMissingSharedRuntimeStyles returns true => 0
// ---------------------------------------------------------------------------

test("2. enhance: rejectMissingSharedRuntimeStyles returns true => returns 0, no processParagraph", function () {
  withEnv(() => {
    const root = makeElement(undefined);
    const observed = makeTestContext(root, { candidates: [makeElement(undefined)] });
    const result = enhance(observed.context, root, {});
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
    const root = makeElement(undefined);
    const observed = makeTestContext(root, { candidates: [] });
    const wrappedResolve = observed.context.optionsLedger.resolveEngineOptions;
    observed.context.optionsLedger.resolveEngineOptions = function (rootElement: Element, bag: Record<string, unknown> | null): ResolvedEnhanceOptions {
      callOrder.push("ledger.resolveEngineOptions");
      return wrappedResolve(rootElement, bag ?? {});
    };
    enhance(observed.context, root, {});
    assert.deepEqual(callOrder, ["pool.cancelJob", "ledger.resolveEngineOptions"]);
    assert.equal(pool._calls.cancelJob.length, 1);
  }, { cancelJob: () => callOrder.push("pool.cancelJob") });
});

// ---------------------------------------------------------------------------
// 4. enhanceProgressively: destroy, rebuild, one Enhance job
// ---------------------------------------------------------------------------

test("4. enhanceProgressively destroys, rebuilds the runtime options and starts one Enhance job", function () {
  withEnv((pool) => {
    const root = makeElement(undefined);
    const observed = makeTestContext(root, { candidates: [] });
    const bag: Record<string, unknown> = { fontSize: 20 };
    enhanceProgressively(observed.context, root, bag);
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
  const src1 = makeFixtureParagraphElement();
  const src2 = makeFixtureParagraphElement();
  const issue1: DiagnosisIssueRecord = {
    name: "X",
    element: src1,
    markerCaptured: true,
    originalNameAttribute: "orig-name",
    originalDetailAttribute: "orig-detail",
  };
  const issue2: DiagnosisIssueRecord = {
    name: "Y",
    element: src2,
    markerCaptured: true,
    originalNameAttribute: "orig-name-2",
    originalDetailAttribute: "orig-detail-2",
  };
  withEnv((pool) => {
    const root = makeElement({ "data-tiqian-snapshot-count": "5", "data-tiqian-issue-count": "2", "data-tiqian-relayout-error": "err", "data-tiqian-snapshot-layout-fallback": "fb" });
    const context = createEnhanceContext(root);
    context.contextState.setRuntimeEstablished(true);
    context.contextState.paragraphs.push(trackedParagraphStub(src1), trackedParagraphStub(src2));
    context.diagnosis.issues.push(issue1, issue2);
    // Enhanced steady state: both paragraphs carry raw-DOM records and their
    // live hosts show rendered output.
    registerParagraph(context, src1);
    registerParagraph(context, src2);
    src1.innerHTML = "rendered one";
    src2.innerHTML = "rendered two";
    destroyRoot(context, root);
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
  }, { document: makePipelineDocument(), node: asNodeConstructor(FakeNode) });
});

// ---------------------------------------------------------------------------
// 6. destroyRoot no state: still cancelJob + attribute cleanup, no throw
// ---------------------------------------------------------------------------

test("6. destroyRoot: no established runtime => still cancelJob + attribute cleanup, no throw", function () {
  withEnv((pool) => {
    const root = makeElement({ "data-tiqian-relayout-error": "err" });
    const context = createEnhanceContext(root);
    destroyRoot(context, root);
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
    const root = makeElement(undefined);
    const observed = makeTestContext(root);
    const runtimeOptions = seedEstablishedRuntime(observed, root, { fontSize: 19 });
    const paragraph: SeededParagraph = { source: makeFixtureParagraphElement(), lowered: {}, lastMeasure: null };
    observed.context.contextState.paragraphs.push(paragraph as TrackedParagraph);

    detachRoot(observed.context, root);

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
    const root = makeElement(undefined);
    const observed = makeTestContext(root);
    const result = probeRootContentDrift(observed.context, root);
    assert.deepEqual(result, { unknown: 1, drifted: 0, dead: 0, rawDom: 0 });
  });

  const src1 = makeFixtureParagraphElement();
  const src2 = makeFixtureParagraphElement();
  withEnv(() => {
    const root = makeElement(undefined);
    const observed = makeTestContext(root);
    seedEstablishedRuntime(observed, root, {});
    observed.context.contextState.paragraphs.push(trackedParagraphStub(src1), trackedParagraphStub(src2));
    registerParagraph(observed.context, src1);
    registerParagraph(observed.context, src2);
    const result2 = probeRootContentDrift(observed.context, root);
    // The real probe classified both registered sources through the
    // context's raw-DOM records: their rendered and backup identities match,
    // so nothing drifted, died or fell out of the raw-DOM backup.
    assert.deepEqual(result2, { unknown: 0, drifted: 0, dead: 0, rawDom: 0 });
  }, { document: makePipelineDocument(), node: asNodeConstructor(FakeNode) });
});

// ---------------------------------------------------------------------------
// 9. reconcileRoot: not established => null; established + idle => result, no
//    job; with work verdict => actions per category + job
// ---------------------------------------------------------------------------

test("9a. reconcileRoot: not established returns null", function () {
  withEnv(() => {
    const root = makeElement(undefined);
    const observed = makeTestContext(root);
    const result = reconcileRoot(observed.context, root, []);
    assert.equal(result, null);
  });
});

test("9b. reconcileRoot: established + idle verdict => returns the result, no startLayoutJob", function () {
  const source = makeFixtureParagraphElement();
  withEnv((pool) => {
    const root = makeElement(undefined);
    const observed = makeTestContext(root, { candidates: [] });
    seedEstablishedRuntime(observed, root, {});
    observed.context.contextState.paragraphs.push(trackedParagraphStub(source));
    registerParagraph(observed.context, source);
    const result = reconcileRoot(observed.context, root, []);
    assert.deepEqual(result, { outcome: "idle", drifted: 0, rawDom: 0, tainted: 0, stranded: 0, dead: 0 });
    assert.equal(pool._calls.startJob.length, 0);
  }, { document: makePipelineDocument(), node: asNodeConstructor(FakeNode) });
});

test("9c. reconcileRoot: work verdict with drifted/rawDom/tainted/stranded + DeadTrackedParagraphDrop", function () {
  const deadEl = makeFixtureParagraphElement();
  deadEl.isConnected = false;
  const driftedEl = makeFixtureParagraphElement();
  const rawDomEl = makeFixtureParagraphElement();
  const taintedEl = makeFixtureParagraphElement();
  // The tainted host stays only when connected inside a root.
  const proseRoot = new FakeElement("tiqian-prose");
  proseRoot.appendChild(taintedEl);
  const strandedEl = makeFixtureParagraphElement();
  // Engine scaffolding the stranded action must strip before re-lowering.
  strandedEl.setAttribute("data-tq-snapshot-prepared-dom", "true");
  withEnv((pool) => {
    const root = makeElement(undefined);
    const observed = makeTestContext(root, {
      candidates: [],
      stranded: [strandedEl],
    });
    seedEstablishedRuntime(observed, root, {});
    installFixtureBrowserFallback(observed.context);
    const context = observed.context;
    context.contextState.paragraphs.push(
      trackedParagraphStub(deadEl),
      trackedParagraphStub(driftedEl),
      trackedParagraphStub(rawDomEl),
      trackedParagraphStub(taintedEl),
    );
    registerParagraph(context, deadEl);
    registerParagraph(context, driftedEl);
    registerParagraph(context, rawDomEl);
    registerParagraph(context, taintedEl);
    // Host edits: the drifted paragraph's rendered children changed through
    // innerHTML (which bypasses the commit forwarding), and the raw-DOM
    // backup of the second gained content while its rendered output stayed.
    driftedEl.innerHTML = "edited live";
    context.rawDomParagraphs.get(rawDomEl)!.originalContent!.appendChild(asNode(new FakeText(" host edit")));

    const result = reconcileRoot(context, root, [taintedEl]);
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
    assert.ok(driftedEl.textContent.includes("edited live"));
    // The raw-DOM action restored the edited backup and re-lowered it.
    assert.ok(rawDomEl.textContent.includes("host edit"));
    // stripEngineMarkupFromStrandedParagraph removed the scaffolding marker
    // before re-lowering, and the re-process rendered the paragraph again.
    assert.equal(strandedEl.getAttribute("data-tq-snapshot-prepared-dom"), null);
    assert.equal(strandedEl.getAttribute("data-tq-rendered"), "true");
  }, { document: makePipelineDocument(), node: asNodeConstructor(FakeNode) });
});

test("9d. reconcileRoot: itemTierIndex sorted by (distance, index), stale closure detects width drift >= 0.5", function () {
  const el1 = makeFixtureParagraphElement();
  el1.top = -200;
  el1.height = 100;
  const el2 = makeFixtureParagraphElement();
  withEnv((pool) => {
    const root = makeElement(undefined);
    root._rect = { top: 0, bottom: 100, width: 300 };
    const observed = makeTestContext(root, { candidates: [] });
    seedEstablishedRuntime(observed, root, {});
    const context = observed.context;
    context.contextState.paragraphs.push(trackedParagraphStub(el1), trackedParagraphStub(el2));
    registerParagraph(context, el1);
    registerParagraph(context, el2);
    // Both paragraph drift: the host replaced their rendered children.
    el1.innerHTML = "edited one";
    el2.innerHTML = "edited two";
    reconcileRoot(context, root, []);
    assert.equal(pool._calls.startJob.length, 1);
    const call = pool._calls.startJob[0];
    // el2 visible (distance 0) first, then el1 above viewport (distance 100).
    assert.deepEqual(call.itemTierIndex, [1, 0]);
    // stale closure: root width matches initially.
    assert.equal(call.isStale!(), false);
    // After root width drift of 1.0.
    root._rect.width = 301;
    assert.equal(call.isStale!(), true);
  }, { document: makePipelineDocument(), node: asNodeConstructor(FakeNode) });
});
