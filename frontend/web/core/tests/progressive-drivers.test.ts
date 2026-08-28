import assert from "node:assert/strict";
import test from "node:test";

import {
  enhanceProgressively,
  enhanceProgressivelyFromCanonical,
  rejectMissingSharedRuntimeStyles,
  relayout,
  startLayoutJob,
} from "../core/engine/progressive-drivers.js";
import { createEnhanceContext } from "../core/engine/context/enhance-context.js";
import type { EnhancedElementContext } from "../core/engine/context/enhance-context.js";
import { rawDomBegin, rawDomCommit, rawDomTake } from "../core/engine/raw-dom.js";
import { installFixtureFontBackend } from "../test-support/fixture-font-backend.mjs";
import { FakeElement, FakeFragment, FakeNode, FakeText, FakeInlineStyle } from "./snapshot-dom-fixtures.mjs";
import { globalServices, initializeGlobalServices } from "../core/services/global-services.js";
import type { CoordinationService } from "../core/engine/coordination/coordination-service.js";
import type { LayoutJobPool } from "../core/engine/layout-job-pool.js";
import type { LayoutJobSpec } from "../core/engine/layout-job-pool.js";
import type { EnhanceOptions, ResolvedEnhanceOptions } from "../core/engine/lifecycle.js";
initializeGlobalServices();

// Type aliases for bridging fake elements with DOM interfaces.
type FakeElementAsHTMLElement = FakeElement & HTMLElement;
type FakeElementAsElement = FakeElement & Element;

// The driver functions take the per-element EnhancedElementContext as their
// first parameter; the layout job pool comes from
// globalServices().coordination.layoutJobPool, which withEnv swaps for a fake
// per test. Tests that only observe the job spec wrap the context's option
// resolvers, candidate enumeration, stranded enumeration and publishState
// projection; tests that drive the processItem paths run the real
// processParagraph, prepareParagraphLayout, relayout session and prepared-DOM
// renderer, and observe the consequences on the context's raw-DOM records,
// the live elements and the context state.

interface SavedGlobal {
  name: string;
  own: boolean;
  value: unknown;
}

function saveGlobals(names: string[]): SavedGlobal[] {
  return names.map((name) => ({
    name,
    own: Object.prototype.hasOwnProperty.call(globalThis, name),
    value: globalThis[name as keyof typeof globalThis],
  }));
}

function restoreGlobals(entries: SavedGlobal[]): void {
  for (const { name, own, value } of entries) {
    if (own) (globalThis as Record<string, unknown>)[name] = value;
    else delete (globalThis as Record<string, unknown>)[name];
  }
}

interface ComputedStyleValues {
  [key: string]: string;
}

interface MakeComputedStyleValues {
  paddingLeft?: string;
  paddingRight?: string;
  borderLeftWidth?: string;
  borderRightWidth?: string;
  position?: string;
  transform?: string;
  float?: string;
  marginLeft?: string;
  marginRight?: string;
  marginTop?: string;
  marginBottom?: string;
  "line-height"?: string;
  "font-family"?: string;
  "--tq-styles-ready"?: string;
}

function makeComputedStyle(values: MakeComputedStyleValues = {}): CSSStyleDeclaration & { getPropertyValue(name: string): string } {
  const props: ComputedStyleValues = {
    paddingLeft: "0px",
    paddingRight: "0px",
    borderLeftWidth: "0px",
    borderRightWidth: "0px",
    position: "static",
    transform: "none",
    float: "none",
    marginLeft: "0px",
    marginRight: "0px",
    marginTop: "0px",
    marginBottom: "0px",
    "line-height": "33px",
    "font-family": "Fixture CJK",
    ...values,
  };
  const style: Partial<CSSStyleDeclaration> & { getPropertyValue(name: string): string } = {} as Partial<CSSStyleDeclaration> & { getPropertyValue(name: string): string };
  for (const key of Object.keys(props)) (style as Record<string, unknown>)[key] = props[key];
  style.getPropertyValue = (name: string): string => {
    const key = String(name).toLowerCase();
    return Object.prototype.hasOwnProperty.call(props, key)
      ? String(props[key])
      : "";
  };
  return style as CSSStyleDeclaration & { getPropertyValue(name: string): string };
}

interface FakeLayoutJobPoolCalls {
  startJob: LayoutJobSpec[];
  cancelJob: Element[];
}

interface FakeLayoutJobPool extends Omit<LayoutJobPool, "startJob" | "cancelJob" | "jobKind"> {
  _calls: FakeLayoutJobPoolCalls;
  startJob(spec: LayoutJobSpec): void;
  cancelJob(root: Element): void;
  jobKind(root: Element): string | null;
  runSlice(): number;
  hasJob(): boolean;
  jobGeneration(): number;
  pendingInTier(): number;
  paragraphCount(): number;
  paragraphAt(): null;
  setParagraphTier(): boolean;
  attach(): boolean;
  detach(): boolean;
}

function makeFakeLayoutJobPool(overrides: {
  startJob?: (spec: LayoutJobSpec) => void;
  cancelJob?: (root: Element) => void;
  jobKind?: (root: Element) => string | null;
} = {}): FakeLayoutJobPool {
  const startJobCalls: LayoutJobSpec[] = [];
  const cancelJobCalls: Element[] = [];
  const pool: FakeLayoutJobPool = {
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
    runSlice: function (): number {
      return 0;
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
    paragraphAt: function (): null {
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
  };
  return pool;
}

// The drivers reach the pool through the coordination service, so the fake
// pool is installed there for the duration of one test.
function installFakePool(pool: FakeLayoutJobPool): () => void {
  const coordination: CoordinationService = globalServices().coordination;
  const previous: LayoutJobPool = coordination.layoutJobPool;
  Object.defineProperty(coordination, "layoutJobPool", {
    value: pool as unknown as LayoutJobPool,
    writable: true,
    configurable: true,
  });
  return function (): void {
    Object.defineProperty(coordination, "layoutJobPool", {
      value: previous,
      writable: true,
      configurable: true,
    });
  };
}

interface WithEnvOverrides {
  layoutJobPool?: FakeLayoutJobPool;
  computedStyleValues?: MakeComputedStyleValues;
  document?: Document;
  node?: typeof Node;
  cancelJob?: (root: Element) => void;
  jobKind?: (root: Element) => string | null;
  startJob?: (spec: LayoutJobSpec) => void;
}

function withEnv<T>(fn: (pool: FakeLayoutJobPool) => T, overrides: WithEnvOverrides = {}): T {
  const saved = saveGlobals(["window", "document", "getComputedStyle", "CustomEvent", "Node"]);
  const fakePoolOptions = {
    startJob: overrides.startJob,
    cancelJob: overrides.cancelJob,
    jobKind: overrides.jobKind,
  };
  const pool = overrides.layoutJobPool ?? makeFakeLayoutJobPool(fakePoolOptions);
  const restorePool = installFakePool(pool);
  try {
    const values = overrides.computedStyleValues ?? { "--tq-styles-ready": "1" };
    const computed = (_el: Element, _pseudo?: string | null): CSSStyleDeclaration => makeComputedStyle(values) as CSSStyleDeclaration;
    Object.defineProperty(globalThis, "getComputedStyle", { value: computed, writable: true, configurable: true });
    Object.defineProperty(globalThis, "window", {
      value: {
        innerHeight: 800,
        getComputedStyle: computed,
      },
      writable: true,
      configurable: true,
    });
    Object.defineProperty(globalThis, "document", {
      value: overrides.document ?? { documentElement: { clientHeight: 800 } },
      writable: true,
      configurable: true,
    });
    if (overrides.node) Object.defineProperty(globalThis, "Node", { value: overrides.node, writable: true, configurable: true });
    Object.defineProperty(globalThis, "CustomEvent", {
      value: class {
        type: string;
        bubbles: boolean;
        composed: boolean;
        detail: unknown;
        constructor(type: string, init?: { bubbles?: boolean; composed?: boolean; detail?: unknown }) {
          this.type = type;
          this.bubbles = Boolean(init && init.bubbles);
          this.composed = Boolean(init && init.composed);
          this.detail = init && init.detail;
        }
      },
      writable: true,
      configurable: true,
    });
    return fn(pool);
  } finally {
    restorePool();
    restoreGlobals(saved);
  }
}

interface ParagraphLowered {
  text: string;
  textStyle: {
    fontFamilies: string[];
    fontSize: number;
    fontWeight: number;
    italic: boolean;
    baselineShift: number;
    locale: string;
  };
  lineHeight: number;
  spans: Array<{ start: number; end: number; style: { locale: string } }>;
  decorations: unknown[];
  inlineBoxes: unknown[];
  inlineObjects: unknown[];
  domInlineObjects: unknown[];
  sourceSpans: unknown[];
  sourceBoundaries: unknown[];
  lineBreakSpans: unknown[];
}

type ParagraphElement = FakeElement & {
  getAttribute(name: string): string | null;
  setAttribute(name: string, value: string): void;
  removeAttribute(name: string): void;
  style: FakeInlineStyle & {
    getPropertyValue: (name: string) => string;
    getPropertyPriority: () => string;
    setProperty: (name: string, value: string) => void;
    removeProperty: (name: string) => void;
  };
  closest(): null;
  querySelectorAll(): never[];
  querySelector(): null;
  getBoundingClientRect(): { top: number; bottom: number; width: number } & ClientRect;
  getClientRects(): never[];
  insertBefore(): void;
  dispatchEvent(event: Event): boolean;
  events: Event[];
  setAttributes: Array<{ name: string; value: string }>;
  removedAttributes: string[];
  _rect: { top: number; bottom: number; width: number };
};

interface TrackedParagraph {
  source: ParagraphElement;
  lowered: ParagraphLowered;
  lastMeasure: number | null;
}

interface ObservedContextCalls {
  resolveEngineOptions: Array<{ root: Element; optionsBag: Record<string, unknown> | null }>;
  resolveEngineOptionsFromCanonical: Array<{ root: Element; options: EnhanceOptions }>;
  paragraphCandidates: Array<{ root: Element; selector: string }>;
  strandedSourceParagraphs: Array<Record<string, never>>;
  publishState: Array<{ paragraphCount: number; issueCount: number; keepEmpty?: boolean }>;
}

interface ObservedContext {
  context: EnhancedElementContext;
  calls: ObservedContextCalls;
  realResolve: (root: Element, optionsBag: Record<string, unknown>) => ResolvedEnhanceOptions;
}

function makeElement(initialAttributes: Record<string, string> = {}, options: {
  tagName?: string;
  text?: string;
  width?: number;
  childNodes?: Array<{ nodeType: number; textContent: string }>;
} = {}): ParagraphElement {
  const attrs = new Map(Object.entries(initialAttributes || {}));
  const setAttributes: Array<{ name: string; value: string }> = [];
  const removedAttributes: string[] = [];
  const styleProps = new Map<string, string>();
  const text = options.text ?? "hello world";
  const rect = { top: 0, bottom: 100, width: options.width ?? 300 };
  const childNodes = options.childNodes ?? [{ nodeType: 3, textContent: text }];
  const baseElement = new FakeElement(options.tagName ?? "P");
  for (const [name, value] of attrs) {
    baseElement.setAttribute(name, value);
  }
  baseElement.width = rect.width;
  for (const child of childNodes) {
    if (child.nodeType === 3) {
      baseElement.appendChild(new FakeText(child.textContent));
    }
  }
  const element = baseElement as ParagraphElement;
  element.events = [] as Event[];
  element.setAttributes = setAttributes;
  element.removedAttributes = removedAttributes;
  element._rect = rect;
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
  element.dispatchEvent = function (event: Event): boolean {
    element.events.push(event);
    return true;
  };
  const originalGetBCR = element.getBoundingClientRect.bind(element);
  element.getBoundingClientRect = function (): { top: number; bottom: number; width: number } & ClientRect {
    const r = element._rect || rect;
    return { top: r.top, bottom: r.bottom, width: r.width, height: r.bottom - r.top, left: 0, right: r.width } as { top: number; bottom: number; width: number } & ClientRect;
  };
  element.getClientRects = function (): never[] {
    return [];
  };
  element.closest = function (): null {
    return null;
  };
  element.querySelectorAll = function (): never[] {
    return [];
  };
  element.querySelector = function (): null {
    return null;
  };
  return element;
}

// Paragraph host on the fixture fake-DOM base for tests that drive the real
// processParagraph/prepare pipeline: lowerable children, a measurable box, and
// a parseable innerHTML for the prepared-DOM renderer.
function makeFixtureParagraphElement(text: string = "hello world"): FakeElement {
  const element = new FakeElement("p");
  element.width = 320;
  element.appendChild(new FakeText(text));
  return element;
}

// Fake document for the full pipeline: the fragment factory the raw-DOM
// takeover uses, lowering probe elements, an inert Range, the style head, and
// the documentElement the viewport distance reads.
function makePipelineDocument(): Document {
  const documentObject = {
    documentElement: { clientHeight: 800 },
    createElement: (tagName: string): FakeElement => new FakeElement(tagName || "span"),
    createDocumentFragment: (): FakeFragment => new FakeFragment(),
    createRange: (): Range => ({
      selectNodeContents() {},
      getClientRects: (): DOMRectList => [] as unknown as DOMRectList,
    } as unknown as Range),
  } as unknown as Document;
  (documentObject as unknown as { head: FakeElement }).head = new FakeElement("head");
  return documentObject;
}

// Registers the paragraph with the context's raw-DOM bookkeeping exactly the
// way the enhance pass did, so the relayout session's captureLive and
// rollback find the record.
function registerParagraph(context: EnhancedElementContext, source: Element): void {
  rawDomBegin(context, source, null, null, null, null, null, null, "", "", "", "", "", "", null);
  rawDomTake(context, source, null);
  rawDomCommit(context, source, null);
}

function makeParagraph(overrides: {
  source?: ParagraphElement;
  lowered?: ParagraphLowered;
  lastMeasure?: number | null;
} = {}): TrackedParagraph {
  const source = overrides.source || makeElement();
  const lowered: ParagraphLowered = overrides.lowered || {
    text: "test",
    textStyle: {
      fontFamilies: ["Fixture CJK"],
      fontSize: 19,
      fontWeight: 400,
      italic: false,
      baselineShift: 0,
      locale: "zh-Hans",
    },
    lineHeight: 28,
    spans: [],
    decorations: [],
    inlineBoxes: [],
    inlineObjects: [],
    domInlineObjects: [],
    sourceSpans: [],
    sourceBoundaries: [],
    lineBreakSpans: [],
  };
  return {
    source: source,
    lowered: lowered,
    lastMeasure: overrides.lastMeasure || null,
  };
}

// A real EnhancedElementContext whose driver-observable part surface is
// wrapped with recording spies: the two option resolvers dissolved from
// root-state's createRootState/createRootStateFromCanonical, the candidate
// and stranded enumerations, and the publishState projection. The wrappers
// delegate to the real implementations, so seeded steady states and driven
// processItem paths run the genuine pipeline.
function makeObservedContext(root: Element, overrides: {
  candidates?: Element[];
  stranded?: Element[];
} = {}): ObservedContext {
  const context = createEnhanceContext(root);
  const calls: ObservedContextCalls = {
    resolveEngineOptions: [],
    resolveEngineOptionsFromCanonical: [],
    paragraphCandidates: [],
    strandedSourceParagraphs: [],
    publishState: [],
  };

  const ledger = context.optionsLedger;
  const realResolve = ledger.resolveEngineOptions;
  const realResolveCanonical = ledger.resolveEngineOptionsFromCanonical;
  ledger.resolveEngineOptions = function (rootElement: Element, optionsBag: Record<string, unknown> | null): ResolvedEnhanceOptions {
    calls.resolveEngineOptions.push({ root: rootElement, optionsBag: optionsBag });
    return realResolve(rootElement, optionsBag ?? {});
  };
  ledger.resolveEngineOptionsFromCanonical = function (rootElement: Element, options: EnhanceOptions): ResolvedEnhanceOptions {
    calls.resolveEngineOptionsFromCanonical.push({ root: rootElement, options: options });
    return realResolveCanonical(rootElement, options);
  };

  const state = context.contextState;
  const realCandidates = state.paragraphCandidates;
  state.paragraphCandidates = function (rootElement: Element, selector: string): Element[] {
    calls.paragraphCandidates.push({ root: rootElement, selector: selector });
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
  write.publishState = function (paragraphCount: number, issueCount: number, keepEmpty?: boolean): void {
    calls.publishState.push({
      paragraphCount: paragraphCount,
      issueCount: issueCount,
      keepEmpty: keepEmpty,
    });
    return realPublish(paragraphCount, issueCount, keepEmpty);
  };

  return { context: context, calls: calls, realResolve: realResolve };
}

// Seeds the enhanced steady state the former getState(root) branch read: the
// resolved runtime options on the context state, an established typography
// runtime, and the runtime-established flag. Uses the unwrapped resolver so
// seeding never pollutes the recorded calls.
function seedEstablishedRuntime(observed: ObservedContext, root: Element, optionsBag: Record<string, unknown>): ResolvedEnhanceOptions {
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
function installFixtureBrowserFallback(context: EnhancedElementContext): { uninstall(): void; shapeJson: (requestJson: string) => string; metricsJson: (requestJson: string) => string } {
  const backend = installFixtureFontBackend();
  Object.defineProperty(context.typography, "browserFallback", {
    configurable: true,
    get: function (): { bridge: { shapeJson: (requestJson: string) => string; metricsJson: (requestJson: string) => string } } {
      return { bridge: backend };
    },
  });
  return backend;
}

// effectiveLineMeasure twin (responsive-measure.js): the measure the fixture
// shaping produces for a width at the configured font size.
function effectiveLineMeasureOf(width: number, fontSize: number): number {
  return Math.min(Math.max(1, Math.floor(width / fontSize)) * fontSize, width);
}

// ---------------------------------------------------------------------------
// 1. enhanceProgressively
// ---------------------------------------------------------------------------

test("1a. cancelJob is called before resolveEngineOptions", function () {
  withEnv((pool) => {
    const root = makeElement();
    const observed = makeObservedContext(root as unknown as Element);
    enhanceProgressively(observed.context, root as unknown as Element, { fontSize: 20 });

    assert.equal(pool._calls.cancelJob.length, 1);
    assert.equal(pool._calls.cancelJob[0], root);
    assert.equal(observed.calls.resolveEngineOptions.length, 1);
    assert.equal(observed.calls.resolveEngineOptions[0].optionsBag!.fontSize, 20);
    // cancelJob happens before the options resolution
    assert.ok(pool._calls.cancelJob.length > 0);
  });
});

test("1b. work order sorted by (distance, index) ascending", function () {
  // Candidates are source elements: p1 above the viewport (distance 100),
  // p2 visible (distance 0), p3 below the viewport (top 900 - innerHeight
  // 800 = distance 100). p1 and p3 tie on distance, so index breaks the tie.
  const p1 = makeElement();
  p1._rect = { top: -200, bottom: -100, width: 300 };
  const p2 = makeElement();
  p2._rect = { top: 0, bottom: 100, width: 300 };
  const p3 = makeElement();
  p3._rect = { top: 900, bottom: 1000, width: 300 };

  withEnv((pool) => {
    const root = makeElement();
    const observed = makeObservedContext(root as unknown as Element, { candidates: [p1 as unknown as Element, p2 as unknown as Element, p3 as unknown as Element] });
    enhanceProgressively(observed.context, root as unknown as Element, {});

    assert.equal(pool._calls.startJob.length, 1);
    const spec = pool._calls.startJob[0];
    assert.equal(spec.kind, "Enhance");
    assert.equal(spec.itemCount, 3);
    // p2 (distance 0, index 1) first, then the distance-100 tie p1 (index 0)
    // before p3 (index 2).
    assert.deepEqual(spec.itemTierIndex, [1, 0, 2]);
  });
});

test("1c. itemTierIndex and paragraphsByDoc passed to startJob", function () {
  const p1 = makeParagraph();
  const p2 = makeParagraph();

  withEnv((pool) => {
    const root = makeElement();
    const observed = makeObservedContext(root as unknown as Element, {
      candidates: [p1.source as unknown as Element, p2.source as unknown as Element],
    });
    enhanceProgressively(observed.context, root as unknown as Element, {});

    assert.equal(pool._calls.startJob.length, 1);
    const spec = pool._calls.startJob[0];
    assert.equal(spec.kind, "Enhance");
    assert.equal(spec.itemCount, 2);
    // itemTierIndex should be [0, 1] for two elements at distance 0
    assert.deepEqual(spec.itemTierIndex, [0, 1]);
    // paragraphsByDoc should be the source candidates (original order)
    assert.equal(spec.paragraphsByDoc!.length, 2);
    assert.equal(spec.paragraphsByDoc![0], p1.source);
    assert.equal(spec.paragraphsByDoc![1], p2.source);
  });
});

test("1d. processItem runs the real processParagraph for non-stale items", function () {
  const p1 = makeParagraph({ source: makeFixtureParagraphElement() as unknown as ParagraphElement });
  const p2 = makeParagraph({ source: makeFixtureParagraphElement() as unknown as ParagraphElement });

  withEnv((pool) => {
    const root = makeElement();
    const observed = makeObservedContext(root as unknown as Element, {
      candidates: [p1.source as unknown as Element, p2.source as unknown as Element],
    });
    installFixtureBrowserFallback(observed.context);
    enhanceProgressively(observed.context, root as unknown as Element, {});

    const spec = pool._calls.startJob[0];
    assert.ok(spec.processItem);

    // Call processItem for index 0: live measure matches captured => the real
    // processParagraph runs and registers its raw-DOM record on the enhance
    // context. The dissolved processParagraphArgument facade is observable
    // through that outcome: the tracked paragraph lands on the context state.
    spec.processItem!(0);
    const record = observed.context.rawDomParagraphs.get(p1.source as unknown as Element);
    assert.ok(record);
    assert.equal(record!.originalContent!.textContent, "hello world");
    assert.equal(observed.context.contextState.paragraphs.length, 1);
    assert.equal(observed.context.contextState.paragraphs[0].source, p1.source);
  }, { document: makePipelineDocument(), node: FakeNode as unknown as typeof Node });
});

test("1e. processItem sets stale when measure drifts and does not process", function () {
  const p1 = makeParagraph();
  const p2 = makeParagraph();

  withEnv((pool) => {
    const root = makeElement();
    const observed = makeObservedContext(root as unknown as Element, {
      candidates: [p1.source as unknown as Element, p2.source as unknown as Element],
    });
    enhanceProgressively(observed.context, root as unknown as Element, {});

    const spec = pool._calls.startJob[0];
    const isStaleFn = spec.isStale;
    assert.ok(spec.processItem);

    // Live measure for index 0 drifts from the captured measure: widening the
    // element changes the responsive grid cell, so the item is stale and no
    // paragraph is processed.
    p1.source._rect.width = 600;
    spec.processItem!(0);
    assert.equal(observed.context.rawDomParagraphs.size, 0);
    assert.equal(observed.context.contextState.paragraphs.length, 0);
    assert.equal(isStaleFn!(), true);
  });
});

test("1f. onItemsFinished aggregates stale across all items", function () {
  const p1 = makeParagraph();
  const p2 = makeParagraph();

  withEnv((pool) => {
    const root = makeElement();
    const observed = makeObservedContext(root as unknown as Element, {
      candidates: [p1.source as unknown as Element, p2.source as unknown as Element],
    });
    enhanceProgressively(observed.context, root as unknown as Element, {});

    const spec = pool._calls.startJob[0];
    assert.ok(spec.onItemsFinished);
    // p2's live re-measure drifts from its captured measure, so the finish
    // pass reports the job stale.
    p2.source._rect.width = 600;
    spec.onItemsFinished!();
    assert.equal(spec.isStale!(), true);
  });
});

test("1g. SharedRuntimeStylesCapabilityGate: --tq-styles-ready != 1 reports MissingSharedRuntimeStyles and does not startJob", function () {
  const p1 = makeParagraph();

  withEnv((pool) => {
    const root = makeElement();
    const observed = makeObservedContext(root as unknown as Element, { candidates: [p1.source as unknown as Element] });
    enhanceProgressively(observed.context, root as unknown as Element, {});

    // Should not start a job
    assert.equal(pool._calls.startJob.length, 0);
    // The gate issue is pushed into the context's diagnosis issues before the
    // lifecycle marker is written.
    const reportedIssues = observed.context.diagnosis.issues;
    assert.ok(reportedIssues.length > 0);
    assert.equal(reportedIssues[0].name, "MissingSharedRuntimeStyles");
    assert.equal(reportedIssues[0].detail, "Load @tiqian/core/styles.css before TiqianWeb.enhance");
    assert.equal(p1.source.getAttribute("data-tiqian-capability-issue"), "MissingSharedRuntimeStyles");
  }, { computedStyleValues: { "--tq-styles-ready": "0" } });
});

// ---------------------------------------------------------------------------
// 2. relayout branch 1: jobKind=Enhance + running state => canonical options
// ---------------------------------------------------------------------------

test("2. relayout branch 1: Enhance running with state => restart with canonical options (kind Enhance)", function () {
  withEnv((pool) => {
    const root = makeElement();
    const observed = makeObservedContext(root as unknown as Element, {
      candidates: [makeParagraph().source as unknown as Element],
    });
    const runningOptions = seedEstablishedRuntime(observed, root as unknown as Element, {
      fontSize: 22,
      paragraphSelector: "p",
    });
    relayout(observed.context, root as unknown as Element);

    // Should restart with the running state's canonical options. Kotlin's
    // two-arg overload restarts the interrupted enhance, so the kind stays
    // Enhance and the finish event stays tiqian:ready. Canonical options
    // must go through resolveEngineOptionsFromCanonical; feeding them to
    // resolveEngineOptions would re-resolve them through optionsFromJs.
    assert.equal(pool._calls.startJob.length, 1);
    assert.equal(pool._calls.startJob[0].kind, "Enhance");
    assert.equal(observed.calls.resolveEngineOptionsFromCanonical.length, 1);
    assert.equal(observed.calls.resolveEngineOptionsFromCanonical[0].options, runningOptions);
    assert.equal(observed.calls.resolveEngineOptions.length, 0);
  }, { jobKind: () => "Enhance" });
});

// ---------------------------------------------------------------------------
// 3. relayout branch 2: no state => cold-start Relayout with bag null
// ---------------------------------------------------------------------------

test("3. relayout branch 2: no state => cold-start Relayout with bag null", function () {
  withEnv((pool) => {
    const root = makeElement();
    const observed = makeObservedContext(root as unknown as Element, {
      candidates: [makeParagraph().source as unknown as Element],
    });
    relayout(observed.context, root as unknown as Element);

    assert.equal(pool._calls.startJob.length, 1);
    assert.equal(pool._calls.startJob[0].kind, "Relayout");
    // resolveEngineOptions was called with bag null
    assert.equal(observed.calls.resolveEngineOptions.length, 1);
    assert.equal(observed.calls.resolveEngineOptions[0].optionsBag, null);
  });
});

// ---------------------------------------------------------------------------
// 4. relayout branch 3: width-dependent issue => enhance path
// ---------------------------------------------------------------------------

test("4. relayout branch 3: InlineCloneDecorationBreakUnsupported issue => enhance path", function () {
  withEnv((pool) => {
    const root = makeElement();
    const observed = makeObservedContext(root as unknown as Element, {
      candidates: [makeParagraph().source as unknown as Element],
    });
    const runningOptions = seedEstablishedRuntime(observed, root as unknown as Element, { fontSize: 19 });
    observed.context.diagnosis.issues.push({ name: "InlineCloneDecorationBreakUnsupported" });
    relayout(observed.context, root as unknown as Element);

    // cancelJob ran twice: branch 3 cancels explicitly, then the restart's
    // destroyRoot cancels again. Both are idempotent; hosted worlds see the
    // same double through engine.destroy.
    assert.equal(pool._calls.cancelJob.length, 2);
    // Then restarts with enhance path using the state's canonical options
    assert.equal(pool._calls.startJob.length, 1);
    assert.equal(pool._calls.startJob[0].kind, "Relayout");
    assert.equal(observed.calls.resolveEngineOptionsFromCanonical.length, 1);
    assert.equal(observed.calls.resolveEngineOptionsFromCanonical[0].options, runningOptions);
  });
});

// ---------------------------------------------------------------------------
// 5. relayout main path: session, processItem, stale threshold, rollback, finish
// ---------------------------------------------------------------------------

test("5a. relayout main path: openRelayoutSession dispatches stranded and rendered through processItem", function () {
  const root = makeElement();
  root._rect = { top: 0, bottom: 100, width: 300 };

  const renderedP = makeParagraph({ source: makeFixtureParagraphElement() as unknown as ParagraphElement });
  const strandedSource = makeFixtureParagraphElement();

  withEnv((pool) => {
    const observed = makeObservedContext(root as unknown as Element, {
      stranded: [strandedSource as unknown as Element],
      candidates: [],
    });
    seedEstablishedRuntime(observed, root as unknown as Element, { fontSize: 19, paragraphSelector: "p" });
    installFixtureBrowserFallback(observed.context);
    // The rendered paragraph already carries its enhance-time raw-DOM record.
    registerParagraph(observed.context, renderedP.source as unknown as Element);
    observed.context.contextState.paragraphs.push(renderedP as unknown as import("../core/engine/enhance/context-state.js").TrackedParagraph);
    relayout(observed.context, root as unknown as Element);

    assert.equal(pool._calls.startJob.length, 1);
    const spec = pool._calls.startJob[0];
    assert.equal(spec.kind, "Relayout");
    // count = rendered(1) + stranded(1) = 2
    assert.equal(spec.itemCount, 2);

    // Process rendered item (mixIndex 0): the real prepare succeeds on the
    // fixture bridge, and the session commits through the real renderer.
    spec.processItem!(0);
    assert.equal(renderedP.source.getAttribute("data-tq-canonical-source"), "true");
    assert.equal(typeof renderedP.lastMeasure, "number");

    // Process stranded item (mixIndex 1): the real processParagraph runs and
    // registers its raw-DOM record on the context.
    spec.processItem!(1);
    const strandedRecord = observed.context.rawDomParagraphs.get(strandedSource as unknown as Element);
    assert.ok(strandedRecord);
    assert.equal(strandedRecord!.originalContent!.textContent, "hello world");
  }, { document: makePipelineDocument(), node: FakeNode as unknown as typeof Node });
});

test("5b. relayout main path: preparation carries the measured source width", function () {
  const root = makeElement();

  const renderedSource = makeFixtureParagraphElement();
  renderedSource.width = 250;
  const renderedP = makeParagraph({ source: renderedSource as unknown as ParagraphElement });

  withEnv((pool) => {
    const observed = makeObservedContext(root as unknown as Element, { candidates: [] });
    seedEstablishedRuntime(observed, root as unknown as Element, { fontSize: 19 });
    installFixtureBrowserFallback(observed.context);
    registerParagraph(observed.context, renderedSource as unknown as Element);
    observed.context.contextState.paragraphs.push(renderedP as unknown as import("../core/engine/enhance/context-state.js").TrackedParagraph);

    relayout(observed.context, root as unknown as Element);

    const spec = pool._calls.startJob[0];
    spec.processItem!(0);

    // The dissolved prepareArgument facade surfaced the width override; the
    // surviving observable is the committed measure the session copies from
    // the preparation built at the measured source width.
    assert.equal(observed.context.contextState.paragraphs[0], renderedP);
    assert.equal(renderedP.lastMeasure, effectiveLineMeasureOf(250, 19));
  }, { document: makePipelineDocument(), node: FakeNode as unknown as typeof Node });
});

test("5c. relayout main path: stale when root width drifts >= 0.5", function () {
  const root = makeElement();
  root._rect = { top: 0, bottom: 100, width: 300 };

  withEnv((pool) => {
    const observed = makeObservedContext(root as unknown as Element, { candidates: [] });
    seedEstablishedRuntime(observed, root as unknown as Element, { fontSize: 19 });
    relayout(observed.context, root as unknown as Element);

    const spec = pool._calls.startJob[0];
    const staleFn = spec.isStale;

    // Initially root width matches (300) => not stale from width drift
    // But session.stale is also false
    assert.equal(staleFn!(), false);

    // Simulate root width drift
    root._rect.width = 301;
    assert.equal(staleFn!(), true);
  });
});

test("5d. relayout main path: onFailure calls rollback", function () {
  const root = makeElement();

  const renderedSource = makeFixtureParagraphElement();
  const renderedP = makeParagraph({ source: renderedSource as unknown as ParagraphElement });

  withEnv((pool) => {
    const observed = makeObservedContext(root as unknown as Element, { candidates: [] });
    seedEstablishedRuntime(observed, root as unknown as Element, { fontSize: 19 });
    installFixtureBrowserFallback(observed.context);
    registerParagraph(observed.context, renderedSource as unknown as Element);
    observed.context.contextState.paragraphs.push(renderedP as unknown as import("../core/engine/enhance/context-state.js").TrackedParagraph);
    // Enhanced steady state: the renderer wrote the prepared DOM through
    // innerHTML before the relayout session opens.
    (renderedSource as FakeElement).innerHTML = "rendered v1";
    relayout(observed.context, root as unknown as Element);

    const spec = pool._calls.startJob[0];

    // The successful item captured the live content before its commit
    // replaced it.
    spec.processItem!(0);
    assert.equal(typeof renderedP.lastMeasure, "number");
    assert.notEqual(renderedSource.textContent, "rendered v1");

    // The real session rollback restores the captured live content and the
    // pre-session measure.
    assert.ok(spec.onFailure);
    spec.onFailure!();
    assert.equal(renderedSource.textContent, "rendered v1");
    assert.equal(renderedP.lastMeasure, null);
    assert.equal(observed.context.contextState.paragraphs.length, 1);
  }, { document: makePipelineDocument(), node: FakeNode as unknown as typeof Node });
});

test("5e. relayout main path: onItemsFinished calls finish which ejects unsupported paragraphs", function () {
  const root = makeElement();

  const renderedSource = makeFixtureParagraphElement();
  // A span whose locale diverges from the paragraph locale makes the real
  // prepare return the SpanLocaleMismatchUnsupported verdict.
  const renderedP = makeParagraph({
    source: renderedSource as unknown as ParagraphElement,
    lowered: {
      text: "test",
      textStyle: {
        fontFamilies: [],
        fontSize: 19,
        fontWeight: 400,
        italic: false,
        baselineShift: 0,
        locale: "zh-Hans",
      },
      lineHeight: 28,
      spans: [{ start: 0, end: 4, style: { locale: "en" } }],
      decorations: [],
      inlineBoxes: [],
      inlineObjects: [],
      domInlineObjects: [],
      sourceSpans: [],
      sourceBoundaries: [],
      lineBreakSpans: [],
    },
  });

  withEnv((pool) => {
    const observed = makeObservedContext(root as unknown as Element, { candidates: [] });
    seedEstablishedRuntime(observed, root as unknown as Element, { fontSize: 19 });
    installFixtureBrowserFallback(observed.context);
    registerParagraph(observed.context, renderedSource as unknown as Element);
    observed.context.contextState.paragraphs.push(renderedP as unknown as import("../core/engine/enhance/context-state.js").TrackedParagraph);
    relayout(observed.context, root as unknown as Element);

    const spec = pool._calls.startJob[0];
    assert.ok(spec.onItemsFinished);
    // The session marks the unsupported paragraph and finish ejects it from
    // the context's paragraphs and reports the issue.
    spec.processItem!(0);
    spec.onItemsFinished!();
    assert.equal(observed.context.contextState.paragraphs.length, 0);
    assert.equal(observed.context.diagnosis.issues.length, 1);
    assert.equal(observed.context.diagnosis.issues[0].name, "SpanLocaleMismatchUnsupported");
  }, { document: makePipelineDocument(), node: FakeNode as unknown as typeof Node });
});

// ---------------------------------------------------------------------------
// 6. finishing reporting layer
// ---------------------------------------------------------------------------

test("6a. finish: dispatches tiqian:ready with correct detail fields", function () {
  // Snapshot count attribute
  const root = makeElement({ "data-tiqian-snapshot-count": "5" });

  withEnv((pool) => {
    const observed = makeObservedContext(root as unknown as Element, {
      candidates: [makeParagraph().source as unknown as Element],
    });
    enhanceProgressively(observed.context, root as unknown as Element, {});

    const spec = pool._calls.startJob[0];
    assert.ok(spec.onFinished);
    spec.onFinished!({
      kind: "Enhance",
      startedAt: Date.now() - 100,
      maxSliceMs: 50,
      stale: true,
    });

    // Check events dispatched on root
    assert.ok(root.events.length > 0);
    const readyEvent = root.events.find(function (e: Event) {
      return e.type === "tiqian:ready";
    });
    assert.ok(readyEvent);
    assert.equal((readyEvent as CustomEvent).bubbles, true);
    assert.equal((readyEvent as CustomEvent).composed, true);
    const d = (readyEvent as CustomEvent).detail as {
      enhancedCount: number;
      snapshotCount: number;
      issueCount: number;
      durationMs: number;
      maxSliceMs: number;
      stale: boolean;
      runtimeEnhancedCount: number;
    };
    assert.equal(typeof d.enhancedCount, "number");
    assert.equal(d.snapshotCount, 5);
    assert.equal(d.issueCount, 0);
    assert.equal(typeof d.durationMs, "number");
    assert.equal(typeof d.maxSliceMs, "number");
    assert.equal(d.stale, true);
    // enhancedCount = runtimeEnhancedCount + snapshotCount
    assert.equal(d.enhancedCount, d.runtimeEnhancedCount + d.snapshotCount);
  });
});

test("6b. relayout finish: dispatches tiqian:relayout-ready with relayout: true", function () {
  const root = makeElement({ "data-tiqian-snapshot-count": "3" });

  withEnv((pool) => {
    const observed = makeObservedContext(root as unknown as Element, { candidates: [] });
    seedEstablishedRuntime(observed, root as unknown as Element, { fontSize: 19 });
    relayout(observed.context, root as unknown as Element);

    const spec = pool._calls.startJob[0];
    assert.ok(spec.onFinished);
    spec.onFinished!({
      kind: "Relayout",
      startedAt: Date.now() - 200,
      maxSliceMs: 30,
      stale: false,
    });

    const relayoutReadyEvent = root.events.find(function (e: Event) {
      return e.type === "tiqian:relayout-ready";
    });
    assert.ok(relayoutReadyEvent);
    assert.equal((relayoutReadyEvent as CustomEvent).detail.relayout, true);
    assert.equal((relayoutReadyEvent as CustomEvent).detail.failed, false);
    assert.equal((relayoutReadyEvent as CustomEvent).detail.error, null);
    assert.equal((relayoutReadyEvent as CustomEvent).detail.snapshotCount, 3);
    assert.equal((relayoutReadyEvent as CustomEvent).detail.stale, false);
  });
});

test("6c. fail: sets data-tiqian-relayout-error attribute, dispatches error and summary events", function () {
  const root = makeElement();

  withEnv((pool) => {
    const observed = makeObservedContext(root as unknown as Element, { candidates: [] });
    seedEstablishedRuntime(observed, root as unknown as Element, { fontSize: 19 });
    relayout(observed.context, root as unknown as Element);

    const spec = pool._calls.startJob[0];
    assert.ok(spec.onFailed);
    spec.onFailed!({
      kind: "Relayout",
      detail: "Something went wrong",
      startedAt: Date.now() - 150,
      maxSliceMs: 40,
    });

    // data-tiqian-relayout-error attribute set
    assert.equal(root.getAttribute("data-tiqian-relayout-error"), "Something went wrong");

    // Error event
    const errorEvent = root.events.find(function (e: Event) {
      return e.type === "tiqian:relayout-error";
    });
    assert.ok(errorEvent);
    assert.equal((errorEvent as CustomEvent).detail.kind, "Relayout");
    assert.equal((errorEvent as CustomEvent).detail.error, "Something went wrong");

    // Summary event
    const summaryEvent = root.events.find(function (e: Event) {
      return e.type === "tiqian:relayout-ready";
    });
    assert.ok(summaryEvent);
    assert.equal((summaryEvent as CustomEvent).detail.failed, true);
    assert.equal((summaryEvent as CustomEvent).detail.error, "Something went wrong");
  });
});

test("6d. fail: detail truncated to 512 chars", function () {
  const root = makeElement();

  withEnv((pool) => {
    const observed = makeObservedContext(root as unknown as Element, { candidates: [] });
    seedEstablishedRuntime(observed, root as unknown as Element, { fontSize: 19 });
    relayout(observed.context, root as unknown as Element);

    const spec = pool._calls.startJob[0];
    const longDetail = "X".repeat(1024);
    spec.onFailed!({
      kind: "Enhance",
      detail: longDetail,
      startedAt: Date.now(),
      maxSliceMs: 0,
    });

    // Truncated to 512
    assert.equal(root.getAttribute("data-tiqian-relayout-error")!.length, 512);

    // Error event for Enhance kind uses tiqian:error
    const errorEvent = root.events.find(function (e: Event) {
      return e.type === "tiqian:error";
    });
    assert.ok(errorEvent);
    assert.equal((errorEvent as CustomEvent).detail.kind, "Enhance");
  });
});

test("6e. fail for Enhance kind dispatches tiqian:error (not tiqian:relayout-error)", function () {
  const root = makeElement();

  withEnv((pool) => {
    const observed = makeObservedContext(root as unknown as Element, { candidates: [] });
    seedEstablishedRuntime(observed, root as unknown as Element, { fontSize: 19 });
    relayout(observed.context, root as unknown as Element);

    const spec = pool._calls.startJob[0];
    spec.onFailed!({
      kind: "Enhance",
      detail: "test error",
      startedAt: Date.now(),
      maxSliceMs: 0,
    });

    // Should have tiqian:error, not tiqian:relayout-error
    const relayoutErrorEvents = root.events.filter(function (e: Event) {
      return e.type === "tiqian:relayout-error";
    });
    assert.equal(relayoutErrorEvents.length, 0);

    const errorEvents = root.events.filter(function (e: Event) {
      return e.type === "tiqian:error";
    });
    assert.equal(errorEvents.length, 1);
  });
});

// ---------------------------------------------------------------------------
// 7. Public surface exports of the named driver functions
// ---------------------------------------------------------------------------

test("7a. named functions are exposed on the module surface", function () {
  assert.equal(typeof enhanceProgressively, "function");
  assert.equal(typeof relayout, "function");
  assert.equal(typeof rejectMissingSharedRuntimeStyles, "function");
});

test("7b. startLayoutJob has the 9-arg context-first signature", function () {
  assert.equal(typeof startLayoutJob, "function");
  assert.equal(startLayoutJob.length, 9);
});

test("7c. enhanceProgressivelyFromCanonical resolves through the canonical resolver with kind Enhance", function () {
  withEnv((pool) => {
    const root = makeElement();
    const observed = makeObservedContext(root as unknown as Element, { candidates: [] });
    const canonicalOpts: EnhanceOptions = {
      fontFamilies: {
        cjk: "Fixture CJK",
        latin: null,
        monospace: null,
        cjkSerif: null,
        latinSerif: null,
      },
      fontSize: 22,
      lineHeight: null,
      firstLineIndentIc: 0,
      emphasisDotGapEm: 0.25,
      strongAsEmphasisMarks: false,
      paragraphSelector: "p",
      cjkDashCapability: null,
      snapshotFontSession: null,
      requireSnapshotLayoutWorker: false,
    };
    enhanceProgressivelyFromCanonical(observed.context, root as unknown as Element, canonicalOpts);
    // Should use resolveEngineOptionsFromCanonical (not resolveEngineOptions)
    // because fromCanonical is true.
    assert.equal(observed.calls.resolveEngineOptionsFromCanonical.length, 1);
    assert.equal(observed.calls.resolveEngineOptionsFromCanonical[0].options, canonicalOpts);
    assert.equal(observed.calls.resolveEngineOptions.length, 0);
    // Should start a job with kind Enhance (not Relayout)
    assert.equal(pool._calls.startJob.length, 1);
    assert.equal(pool._calls.startJob[0].kind, "Enhance");
  });
});
