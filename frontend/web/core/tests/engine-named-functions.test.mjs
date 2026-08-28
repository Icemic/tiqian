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

function makeElement(initialAttributes, options = {}) {
  const attrs = new Map(Object.entries(initialAttributes || {}));
  const setAttributes = [];
  const removedAttributes = [];
  const styleProps = new Map();
  const text = options.text ?? "hello world";
  const rect = { top: 0, bottom: 100, width: options.width ?? 300 };
  return {
    nodeType: 1,
    tagName: options.tagName ?? "P",
    isConnected: options.isConnected !== false,
    textContent: text,
    childNodes: options.childNodes ?? [{ nodeType: 3, textContent: text }],
    getAttribute: function (name) {
      return attrs.has(name) ? attrs.get(name) : null;
    },
    setAttribute: function (name, value) {
      const strVal = String(value);
      attrs.set(name, strVal);
      setAttributes.push({ name, value: strVal });
    },
    removeAttribute: function (name) {
      attrs.delete(name);
      removedAttributes.push(name);
    },
    hasAttribute: function (name) {
      return attrs.has(name);
    },
    closest: function (selector) {
      if (options.closestTo && selector === "tiqian-prose, [data-tiqian-root]") {
        return options.closestTo;
      }
      return null;
    },
    querySelectorAll: function () {
      return [];
    },
    querySelector: function () {
      return null;
    },
    style: {
      getPropertyValue: (name) => styleProps.get(name) ?? "",
      getPropertyPriority: () => "",
      setProperty: (name, value) => styleProps.set(name, String(value)),
      removeProperty: (name) => styleProps.delete(name),
      item: () => "",
      length: 0,
    },
    getBoundingClientRect: function () {
      const r = this._rect || rect;
      return { top: r.top, bottom: r.bottom, width: r.width };
    },
    _rect: rect,
    getClientRects: function () {
      return [];
    },
    parentElement: null,
    parentNode: null,
    insertBefore: function () {},
    dispatchEvent: function (event) {
      this.events.push(event);
      return true;
    },
    events: [],
    attributes: attrs,
    setAttributes,
    removedAttributes,
  };
}

function saveEnv() {
  return ENV_GLOBALS.map((name) => ({
    name,
    own: Object.prototype.hasOwnProperty.call(globalThis, name),
    value: globalThis[name],
  }));
}

function restoreEnv(entries) {
  for (const { name, own, value } of entries) {
    if (own) globalThis[name] = value;
    else delete globalThis[name];
  }
}

function makeFakeLayoutJobPool(overrides = {}) {
  const startJobCalls = [];
  const cancelJobCalls = [];
  return {
    _calls: { startJob: startJobCalls, cancelJob: cancelJobCalls },
    startJob: function (spec) {
      startJobCalls.push(spec);
      if (overrides.startJob) overrides.startJob(spec);
    },
    cancelJob: function (root) {
      cancelJobCalls.push(root);
      if (overrides.cancelJob) overrides.cancelJob(root);
    },
    jobKind: function (root) {
      return overrides.jobKind ? overrides.jobKind(root) : null;
    },
    isAttached: function () {
      return false;
    },
  };
}

// The drivers reach the pool through the coordination service, so the fake
// pool is installed there for the duration of one test.
function installFakePool(pool) {
  const coordination = globalServices().coordination;
  const previous = coordination.layoutJobPool;
  coordination.layoutJobPool = pool;
  return function () {
    coordination.layoutJobPool = previous;
  };
}

function withEnv(fn, overrides = {}) {
  const saved = saveEnv();
  const pool = overrides.layoutJobPool ?? makeFakeLayoutJobPool(overrides);
  const restorePool = installFakePool(pool);
  try {
    const computed = (el, pseudo) => {
      const props = {
        paddingLeft: "0px",
        paddingRight: "0px",
        borderLeftWidth: "0px",
        borderRightWidth: "0px",
        "line-height": "33px",
        "font-family": "Fixture CJK",
        ...(overrides.computedStyleValues || { "--tq-styles-ready": "1" }),
      };
      const style = {};
      for (const key of Object.keys(props)) style[key] = props[key];
      style.getPropertyValue = (name) => {
        const key = String(name).toLowerCase();
        return Object.prototype.hasOwnProperty.call(props, key)
          ? String(props[key])
          : "";
      };
      return style;
    };
    globalThis.getComputedStyle = computed;
    globalThis.window = { innerHeight: 800, getComputedStyle: computed };
    globalThis.document = overrides.document || {
      querySelectorAll: function () {
        return { length: 0, item: function () { return undefined; } };
      },
    };
    if (overrides.node) globalThis.Node = overrides.node;
    return fn(pool);
  } finally {
    restorePool();
    restoreEnv(saved);
  }
}

// Paragraph host on the fixture fake-DOM base for specs that drive the real
// pipeline: lowerable children, a measurable box, a parseable innerHTML, and
// a connected steady state.
function makeFixtureParagraphElement(text = "hello world") {
  const element = new FakeElement("p");
  element.width = 320;
  element.isConnected = true;
  element.appendChild(new FakeText(text));
  return element;
}

// Fake document for the full pipeline: the fragment factory the raw-DOM
// takeover uses, lowering probe elements, an inert Range, the style head, and
// an inert event surface for the clipboard installer.
function makePipelineDocument() {
  const documentObject = {
    documentElement: { clientHeight: 800 },
    createElement: (tagName) => new FakeElement(tagName || "span"),
    createDocumentFragment: () => new FakeFragment(),
    createRange: () => ({
      selectNodeContents() {},
      getClientRects: () => [],
    }),
    addEventListener: () => {},
    removeEventListener: () => {},
  };
  documentObject.head = new FakeElement("head");
  return documentObject;
}

// Registers the paragraph with the context's raw-DOM bookkeeping exactly the
// way the enhance pass did, so restore/probe/match paths find the record.
function registerParagraph(context, source) {
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
function makeTestContext(root, overrides = {}) {
  const context = createEnhanceContext(root);
  const calls = {
    resolveEngineOptions: [],
    resolveEngineOptionsFromCanonical: [],
    paragraphCandidates: [],
    strandedSourceParagraphs: [],
    publishState: [],
  };

  const ledger = context.optionsLedger;
  const realResolve = ledger.resolveEngineOptions;
  const realResolveCanonical = ledger.resolveEngineOptionsFromCanonical;
  ledger.resolveEngineOptions = function (rootElement, optionsBag) {
    calls.resolveEngineOptions.push({ root: rootElement, optionsBag: optionsBag });
    return realResolve(rootElement, optionsBag);
  };
  ledger.resolveEngineOptionsFromCanonical = function (rootElement, options) {
    calls.resolveEngineOptionsFromCanonical.push({ root: rootElement, options: options });
    return realResolveCanonical(rootElement, options);
  };

  const state = context.contextState;
  const realCandidates = state.paragraphCandidates;
  state.paragraphCandidates = function (rootElement, selector) {
    calls.paragraphCandidates.push({ root: rootElement, selector: selector });
    return overrides.candidates ?? realCandidates(rootElement, selector);
  };

  const sync = context.effectSync;
  const realStranded = sync.strandedSourceParagraphs;
  sync.strandedSourceParagraphs = function () {
    calls.strandedSourceParagraphs.push({});
    return overrides.stranded ?? realStranded();
  };

  const write = context.domWriteLayer;
  const realPublish = write.publishState;
  write.publishState = function (paragraphCount, issueCount, keepEmpty) {
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
function seedEstablishedRuntime(observed, root, optionsBag) {
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
function installFixtureBrowserFallback(context) {
  const backend = installFixtureFontBackend();
  Object.defineProperty(context.typography, "browserFallback", {
    configurable: true,
    get: function () {
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
    const root = makeElement();
    const observed = makeTestContext(root, { candidates: [c1, c2] });
    installFixtureBrowserFallback(observed.context);
    const result = enhance(observed.context, root, { fontSize: 20 });
    assert.equal(observed.calls.resolveEngineOptions.length, 1);
    assert.equal(observed.calls.resolveEngineOptions[0].optionsBag.fontSize, 20);
    // The real processParagraph ran once per candidate, observable through
    // the raw-DOM records registered on the enhance context.
    const record1 = observed.context.rawDomParagraphs.get(c1);
    const record2 = observed.context.rawDomParagraphs.get(c2);
    assert.ok(record1);
    assert.ok(record2);
    assert.equal(record1.originalContent.textContent, "hello world");
    assert.equal(record2.originalContent.textContent, "hello world");
    assert.equal(observed.calls.publishState.length, 1);
    assert.equal(observed.calls.publishState[0].paragraphCount, 2);
    assert.equal(result, 2);
  }, { document: makePipelineDocument(), node: FakeNode });
});

// ---------------------------------------------------------------------------
// 2. enhance styles gate: rejectMissingSharedRuntimeStyles returns true => 0
// ---------------------------------------------------------------------------

test("2. enhance: rejectMissingSharedRuntimeStyles returns true => returns 0, no processParagraph", function () {
  withEnv(() => {
    const root = makeElement();
    const observed = makeTestContext(root, { candidates: [makeElement()] });
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
  const callOrder = [];
  withEnv((pool) => {
    const root = makeElement();
    const observed = makeTestContext(root, { candidates: [] });
    const wrappedResolve = observed.context.optionsLedger.resolveEngineOptions;
    observed.context.optionsLedger.resolveEngineOptions = function (rootElement, bag) {
      callOrder.push("ledger.resolveEngineOptions");
      return wrappedResolve(rootElement, bag);
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
    const root = makeElement();
    const observed = makeTestContext(root, { candidates: [] });
    const bag = { fontSize: 20 };
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
    const context = createEnhanceContext(root);
    context.contextState.setRuntimeEstablished(true);
    context.contextState.paragraphs.push({ source: src1 }, { source: src2 });
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
  }, { document: makePipelineDocument(), node: FakeNode });
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
    const root = makeElement();
    const observed = makeTestContext(root);
    const runtimeOptions = seedEstablishedRuntime(observed, root, { fontSize: 19 });
    const paragraph = { source: makeFixtureParagraphElement(), lowered: {}, lastMeasure: null };
    observed.context.contextState.paragraphs.push(paragraph);

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
    const root = makeElement();
    const observed = makeTestContext(root);
    const result = probeRootContentDrift(observed.context, root);
    assert.deepEqual(result, { unknown: 1, drifted: 0, dead: 0, rawDom: 0 });
  });

  const src1 = makeFixtureParagraphElement();
  const src2 = makeFixtureParagraphElement();
  withEnv(() => {
    const root = makeElement();
    const observed = makeTestContext(root);
    seedEstablishedRuntime(observed, root, {});
    observed.context.contextState.paragraphs.push({ source: src1 }, { source: src2 });
    registerParagraph(observed.context, src1);
    registerParagraph(observed.context, src2);
    const result2 = probeRootContentDrift(observed.context, root);
    // The real probe classified both registered sources through the
    // context's raw-DOM records: their rendered and backup identities match,
    // so nothing drifted, died or fell out of the raw-DOM backup.
    assert.deepEqual(result2, { unknown: 0, drifted: 0, dead: 0, rawDom: 0 });
  }, { document: makePipelineDocument(), node: FakeNode });
});

// ---------------------------------------------------------------------------
// 9. reconcileRoot: not established => null; established + idle => result, no
//    job; with work verdict => actions per category + job
// ---------------------------------------------------------------------------

test("9a. reconcileRoot: not established returns null", function () {
  withEnv(() => {
    const root = makeElement();
    const observed = makeTestContext(root);
    const result = reconcileRoot(observed.context, root, []);
    assert.equal(result, null);
  });
});

test("9b. reconcileRoot: established + idle verdict => returns the result, no startLayoutJob", function () {
  const source = makeFixtureParagraphElement();
  withEnv((pool) => {
    const root = makeElement();
    const observed = makeTestContext(root, { candidates: [] });
    seedEstablishedRuntime(observed, root, {});
    observed.context.contextState.paragraphs.push({ source: source });
    registerParagraph(observed.context, source);
    const result = reconcileRoot(observed.context, root, []);
    assert.deepEqual(result, { outcome: "idle", drifted: 0, rawDom: 0, tainted: 0, stranded: 0, dead: 0 });
    assert.equal(pool._calls.startJob.length, 0);
  }, { document: makePipelineDocument(), node: FakeNode });
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
    const root = makeElement();
    const observed = makeTestContext(root, {
      candidates: [],
      stranded: [strandedEl],
    });
    seedEstablishedRuntime(observed, root, {});
    installFixtureBrowserFallback(observed.context);
    const context = observed.context;
    context.contextState.paragraphs.push(
      { source: deadEl },
      { source: driftedEl },
      { source: rawDomEl },
      { source: taintedEl },
    );
    registerParagraph(context, deadEl);
    registerParagraph(context, driftedEl);
    registerParagraph(context, rawDomEl);
    registerParagraph(context, taintedEl);
    // Host edits: the drifted paragraph's rendered children changed through
    // innerHTML (which bypasses the commit forwarding), and the raw-DOM
    // backup of the second gained content while its rendered output stayed.
    driftedEl.innerHTML = "edited live";
    context.rawDomParagraphs.get(rawDomEl).originalContent.appendChild(new FakeText(" host edit"));

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
    for (let i = 0; i < call.itemTierIndex.length; i += 1) {
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
  }, { document: makePipelineDocument(), node: FakeNode });
});

test("9d. reconcileRoot: itemTierIndex sorted by (distance, index), stale closure detects width drift >= 0.5", function () {
  const el1 = makeFixtureParagraphElement();
  el1.top = -200;
  el1.height = 100;
  const el2 = makeFixtureParagraphElement();
  withEnv((pool) => {
    const root = makeElement();
    root._rect = { top: 0, bottom: 100, width: 300 };
    const observed = makeTestContext(root, { candidates: [] });
    seedEstablishedRuntime(observed, root, {});
    const context = observed.context;
    context.contextState.paragraphs.push({ source: el1 }, { source: el2 });
    registerParagraph(context, el1);
    registerParagraph(context, el2);
    // Both paragraphs drift: the host replaced their rendered children.
    el1.innerHTML = "edited one";
    el2.innerHTML = "edited two";
    reconcileRoot(context, root, []);
    assert.equal(pool._calls.startJob.length, 1);
    const call = pool._calls.startJob[0];
    // el2 visible (distance 0) first, then el1 above viewport (distance 100).
    assert.deepEqual(call.itemTierIndex, [1, 0]);
    // stale closure: root width matches initially.
    assert.equal(call.isStale(), false);
    // After root width drift of 1.0.
    root._rect.width = 301;
    assert.equal(call.isStale(), true);
  }, { document: makePipelineDocument(), node: FakeNode });
});
