import assert from "node:assert/strict";
import test from "node:test";

import { enhance, enhanceProgressively } from "../core/engine/progressive-drivers.js";
import { destroyRoot, detachRoot } from "../core/engine/lifecycle.js";
import { probeRootContentDrift, reconcileRoot } from "../core/engine/content-reconcile.js";
import { createEnhanceContext } from "../core/engine/context/enhance-context.js";
import { rawDomBegin, rawDomCommit, rawDomTake } from "../core/engine/raw-dom.js";
import { installFixtureFontBackend } from "../test-support/fixture-font-backend.mjs";
import { FakeElement, FakeFragment, FakeNode, FakeText } from "./snapshot-dom-fixtures.mjs";
import { initializeGlobalServices } from "../core/services/global-services.js";
initializeGlobalServices();


const ENV_GLOBALS = ["window", "document", "getComputedStyle", "Node"];

// The named engine functions (progressive-drivers, lifecycle destroy/detach,
// content-reconcile) run here against fake root-state/layout-job-pool
// collaborators; their third parameter is the per-element
// EnhancedElementContext. The process-paragraph, commit and raw-DOM helpers
// are direct imports, so specs that drive them run the real pipeline and
// observe the context's raw-DOM records and the live element consequences.

function makeElement(initialAttributes, options = {}) {
  const attrs = new Map(Object.entries(initialAttributes || {}));
  const setAttributes = [];
  const removedAttributes = [];
  const styleProps = new Map();
  const text = options.text ?? "hello world";
  const rect = { top: 0, bottom: 100, width: 300 };
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

function withEnv(fn, overrides = {}) {
  const saved = saveEnv();
  const backend = overrides.fontBackend === false ? null : installFixtureFontBackend();
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
    // Tests now use the real prepared-dom renderer directly.
    // Validator injection removed per spec.
    return fn();
  } finally {
    if (backend) backend.uninstall();
    restoreEnv(saved);
  }
}

// The snapshot-session descriptor carries the shaping callbacks ffi takes as
// call parameters; the fixture backend supplies a working pair.
function fixtureSnapshotSession() {
  const backend = installFixtureFontBackend();
  return { shapeJson: backend.shapeJson, metricsJson: backend.metricsJson };
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

function makeStateWithCallbacks(overrides = {}) {
  const state = {
    root: overrides.root ?? null,
    options: overrides.options ?? { paragraphSelector: "p", fontSize: 19 },
    paragraphs: overrides.paragraphs ?? [],
    issues: overrides.issues ?? [],
    preparedDomEnabled: overrides.preparedDomEnabled ?? true,
    snapshotSession: overrides.snapshotSession ?? fixtureSnapshotSession(),
    browserFallback: overrides.browserFallback ?? null,
  };
  state.onIssue = overrides.onIssue ?? function (issue) { state.issues.push(issue); };
  state.onParagraphCommitted = overrides.onParagraphCommitted ?? function (item) { state.paragraphs.push(item); };
  state.onDisableSnapshotPreparedDom = overrides.onDisableSnapshotPreparedDom ?? function () {};
  return state;
}

function makeFakeRootState(opts) {
  opts = opts || {};
  const calls = {
    createRootState: [],
    createRootStateFromCanonical: [],
    getState: [],
    setState: [],
    deleteState: [],
    publishState: [],
    paragraphCandidates: [],
    strandedSourceParagraphs: [],
    processParagraphArgument: [],
  };
  return {
    _calls: calls,
    createRootState: function (root, bag) {
      calls.createRootState.push({ root: root, bag: bag });
      if (opts.state) {
        // The real createRootState records the enhanced root on the state.
        opts.state.root = root;
        return opts.state;
      }
      return makeStateWithCallbacks({
        root: root,
        options: opts.stateOptions || { paragraphSelector: "p" },
        paragraphs: opts.paragraphs || [],
        issues: opts.issues || [],
      });
    },
    createRootStateFromCanonical: function (root, options) {
      calls.createRootStateFromCanonical.push({ root: root, options: options });
      return opts.canonicalState || makeStateWithCallbacks({
        root: root,
        options: options,
        paragraphs: opts.paragraphs || [],
        issues: opts.issues || [],
      });
    },
    getState: function (root) {
      calls.getState.push(root);
      return opts.getStateValue !== undefined ? opts.getStateValue : null;
    },
    setState: function (root, state) {
      calls.setState.push({ root: root, state: state });
    },
    deleteState: function (root) {
      calls.deleteState.push(root);
    },
    publishState: function (state, keepEmpty) {
      calls.publishState.push({ state: state, keepEmpty: keepEmpty });
    },
    paragraphCandidates: function (root, selector) {
      calls.paragraphCandidates.push({ root: root, selector: selector });
      return opts.candidates || [];
    },
    strandedSourceParagraphs: function (root, state) {
      calls.strandedSourceParagraphs.push({ root: root, state: state });
      return opts.stranded || [];
    },
    processParagraphArgument: function (state, paragraph) {
      calls.processParagraphArgument.push({ state: state, paragraph: paragraph });
      return { paragraph: paragraph, state: state };
    },
  };
}

function makeFakeJob() {
  const calls = { cancelJob: [], startJob: [] };
  return {
    _calls: calls,
    cancelJob: function (root) { calls.cancelJob.push(root); },
    startJob: function (spec) { calls.startJob.push(spec); },
    isAttached: function () { return false; },
    attach: function (root) { return "attached-" + root; },
    detach: function (root) { return "detached-" + root; },
    hasJob: function (root) { return "hasJob-" + root; },
    jobGeneration: function (root) { return 42; },
    runSlice: function (ctrl, tier) { return "ran"; },
    pendingInTier: function (root, tier) { return 7; },
    paragraphCount: function (root) { return 3; },
    paragraphAt: function (root, index) { return "p-" + index; },
    setParagraphTier: function (root, index, tier) { return "set"; },
  };
}

function makeFakeRawDom(overrides = {}) {
  const calls = {
    restoreParagraph: [],
    restoreShell: [],
    stampRendered: [],
    renderedMatches: [],
    rawDomMatches: [],
    begin: [],
    take: [],
    commit: [],
    suspendEngineWrites: [],
  };
  return {
    _calls: calls,
    restoreParagraph: function (el) { calls.restoreParagraph.push(el); },
    restoreShell: function (el) { calls.restoreShell.push(el); },
    stampRendered: function (el) { calls.stampRendered.push(el); },
    suspendEngineWrites: function (el, action) {
      calls.suspendEngineWrites.push(el);
      return action();
    },
    renderedMatches: function (el) {
      calls.renderedMatches.push(el);
      return overrides.renderedMatches ? overrides.renderedMatches(el) : true;
    },
    rawDomMatches: function (el) {
      calls.rawDomMatches.push(el);
      return overrides.rawDomMatches ? overrides.rawDomMatches(el) : true;
    },
    begin: function (...args) { calls.begin.push(args); },
    take: function () {},
    commit: function () {},
  };
}

function makeGraph(opts) {
  opts = opts || {};
  const layoutJobPool = opts.job || makeFakeJob();
  const rawDom = opts.rawDom || makeFakeRawDom();
  return {
    layoutJobPool: layoutJobPool,
    rawDom: rawDom,
  };
}

// ---------------------------------------------------------------------------
// 1. enhance: synchronous loop processes candidates, returns count, publishes
// ---------------------------------------------------------------------------

test("1. enhance: processes each candidate via the real processParagraph, returns paragraphs.length, calls publishState", function () {
  const c1 = makeFixtureParagraphElement();
  const c2 = makeFixtureParagraphElement();
  const fakeState = makeStateWithCallbacks({ root: null });
  const rs = makeFakeRootState({ state: fakeState, candidates: [c1, c2] });
  withEnv(() => {
    const job = makeFakeJob();
    const root = makeElement();
    const context = createEnhanceContext(root);
    const result = enhance(rs, job, context, root, { fontSize: 20 });
    assert.equal(rs._calls.createRootState.length, 1);
    assert.equal(rs._calls.createRootState[0].bag.fontSize, 20);
    // The real processParagraph ran once per candidate, observable through
    // the raw-DOM records registered on the enhance context.
    const record1 = context.rawDomParagraphs.get(c1);
    const record2 = context.rawDomParagraphs.get(c2);
    assert.ok(record1);
    assert.ok(record2);
    assert.equal(record1.originalContent.textContent, "hello world");
    assert.equal(record2.originalContent.textContent, "hello world");
    assert.equal(rs._calls.publishState.length, 1);
    assert.equal(result, 2);
  }, { document: makePipelineDocument(), node: FakeNode });
});

// ---------------------------------------------------------------------------
// 2. enhance styles gate: rejectMissingSharedRuntimeStyles returns true => 0
// ---------------------------------------------------------------------------

test("2. enhance: rejectMissingSharedRuntimeStyles returns true => returns 0, no processParagraph", function () {
  const rs = makeFakeRootState({ candidates: [makeElement()] });
  withEnv(() => {
    const graph = makeGraph({});
    const result = enhance(rs, graph.layoutJobPool, graph.rawDom, makeElement(), {});
    assert.equal(result, 0);
    assert.equal(graph.rawDom._calls.begin.length, 0);
  }, { computedStyleValues: { "--tq-styles-ready": "0" } });
});

// ---------------------------------------------------------------------------
// 3. enhance destroy-first: destroyRoot runs before createRootState
// ---------------------------------------------------------------------------

test("3. enhance: destroyRoot runs before createRootState (call order)", function () {
  const callOrder = [];
  const fakeJob = makeFakeJob();
  const origCancel = fakeJob.cancelJob;
  fakeJob.cancelJob = function (root) {
    callOrder.push("destroy");
    origCancel(root);
  };
  const rs = makeFakeRootState({
    candidates: [],
    state: makeStateWithCallbacks({ root: null }),
  });
  const origCreate = rs.createRootState;
  rs.createRootState = function (root, bag) {
    callOrder.push("createRootState");
    return origCreate(root, bag);
  };
  withEnv(() => {
    const graph = makeGraph({ job: fakeJob });
    enhance(rs, graph.layoutJobPool, graph.rawDom, makeElement(), {});
    assert.deepEqual(callOrder, ["destroy", "createRootState"]);
  });
});

// ---------------------------------------------------------------------------
// 4. enhanceProgressively: copy handler, destroy, rebuild, one Enhance job
// ---------------------------------------------------------------------------

test("4. enhanceProgressively installs the copy handler, destroys, rebuilds state and starts one Enhance job", function () {
  const job = makeFakeJob();
  const rs = makeFakeRootState({
    state: makeStateWithCallbacks({ root: null }),
    candidates: [],
  });
  withEnv(() => {
    const graph = makeGraph({ job: job });
    const root = makeElement();
    const bag = { fontSize: 20 };
    enhanceProgressively(rs, graph.layoutJobPool, graph.rawDom, root, bag);
    // The drivers core cancels the job before rebuilding state.
    assert.equal(job._calls.cancelJob.length, 1);
    assert.equal(job._calls.cancelJob[0], root);
    assert.equal(rs._calls.createRootState.length, 1);
    assert.equal(rs._calls.createRootState[0].bag.fontSize, 20);
    // The real startLayoutJob starts one Enhance job.
    assert.equal(job._calls.startJob.length, 1);
    assert.equal(job._calls.startJob[0].kind, "Enhance");
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
  const state = {
    root: null,
    options: {},
    paragraphs: [{ source: src1 }, { source: src2 }],
    issues: [issue1, issue2],
  };
  const rs = makeFakeRootState({ getStateValue: state });
  withEnv(() => {
    const job = makeFakeJob();
    const root = makeElement({ "data-tiqian-snapshot-count": "5", "data-tiqian-issue-count": "2", "data-tiqian-relayout-error": "err", "data-tiqian-snapshot-layout-fallback": "fb" });
    const context = createEnhanceContext(root);
    // Enhanced steady state: both paragraphs carry raw-DOM records and their
    // live hosts show rendered output.
    registerParagraph(context, src1);
    registerParagraph(context, src2);
    src1.innerHTML = "rendered one";
    src2.innerHTML = "rendered two";
    destroyRoot(rs, job, context, root);
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

test("6. destroyRoot: no state => still cancelJob + attribute cleanup, no throw", function () {
  const rawDom = makeFakeRawDom();
  const rs = makeFakeRootState({ getStateValue: null });
  withEnv(() => {
    const graph = makeGraph({ rawDom: rawDom });
    const root = makeElement({ "data-tiqian-relayout-error": "err" });
    destroyRoot(rs, graph.layoutJobPool, graph.rawDom, root);
    assert.equal(rawDom._calls.restoreParagraph.length, 0);
    assert.equal(root.getAttribute("data-tiqian-relayout-error"), null);
    assert.equal(root.getAttribute("data-tiqian-enhanced"), null);
  });
});

// ---------------------------------------------------------------------------
// 7. detachRoot: minimal surface -- cancelJob + releaseRoot, no state touch
// ---------------------------------------------------------------------------

test("7. detachRoot: cancelJob + releaseRoot only, does not touch paragraphs/issues/state", function () {
  const rs = makeFakeRootState();
  withEnv(() => {
    const job = makeFakeJob();
    const root = makeElement();
    detachRoot(job, root, createEnhanceContext(root));
    assert.equal(rs._calls.getState.length, 0);
    assert.equal(rs._calls.deleteState.length, 0);
    assert.equal(job._calls.cancelJob.length, 1);
    assert.equal(job._calls.cancelJob[0], root);
  });
});

// ---------------------------------------------------------------------------
// 8. probeRootContentDrift: no state => unknown result; with state => sources
//    to the real probe, which reads the fake detached-fragment backup ledger
// ---------------------------------------------------------------------------

test("8. probeRootContentDrift: no state returns the unknown result; with state passes sources to the real probe", function () {
  const rsNoState = makeFakeRootState({ getStateValue: null });
  withEnv(() => {
    const result = probeRootContentDrift(createEnhanceContext(makeElement()), rsNoState, makeElement());
    assert.deepEqual(result, { unknown: 1, drifted: 0, dead: 0, rawDom: 0 });
  });

  const src1 = makeFixtureParagraphElement();
  const src2 = makeFixtureParagraphElement();
  const state = { root: null, options: {}, paragraphs: [{ source: src1 }, { source: src2 }], issues: [] };
  const rs = makeFakeRootState({ getStateValue: state });
  withEnv(() => {
    const context = createEnhanceContext(makeElement());
    registerParagraph(context, src1);
    registerParagraph(context, src2);
    const result2 = probeRootContentDrift(context, rs, makeElement());
    // The real probe classified both registered sources through the
    // context's raw-DOM records: their rendered and backup identities match,
    // so nothing drifted, died or fell out of the raw-DOM backup.
    assert.deepEqual(result2, { unknown: 0, drifted: 0, dead: 0, rawDom: 0 });
  }, { document: makePipelineDocument(), node: FakeNode });
});

// ---------------------------------------------------------------------------
// 9. reconcileRoot: no state => null; with state + idle => result, no job;
//    with work verdict => actions per category + job
// ---------------------------------------------------------------------------

test("9a. reconcileRoot: no state returns null", function () {
  const rs = makeFakeRootState({ getStateValue: null });
  withEnv(() => {
    const graph = makeGraph({});
    const result = reconcileRoot(graph.rawDom, rs, graph.layoutJobPool, makeElement(), []);
    assert.equal(result, null);
  });
});

test("9b. reconcileRoot: state + idle verdict => returns the result, no startLayoutJob", function () {
  const source = makeFixtureParagraphElement();
  const state = { root: null, options: {}, paragraphs: [{ source: source }], issues: [] };
  const rs = makeFakeRootState({ getStateValue: state, candidates: [] });
  withEnv(() => {
    const job = makeFakeJob();
    const root = makeElement();
    const context = createEnhanceContext(root);
    registerParagraph(context, source);
    const result = reconcileRoot(context, rs, job, root, []);
    assert.deepEqual(result, { outcome: "idle", drifted: 0, rawDom: 0, tainted: 0, stranded: 0, dead: 0 });
    assert.equal(job._calls.startJob.length, 0);
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
  const root = makeElement();
  const state = makeStateWithCallbacks({
    root: root,
    options: {},
    paragraphs: [
      { source: deadEl },
      { source: driftedEl },
      { source: rawDomEl },
      { source: taintedEl },
    ],
  });
  const rs = makeFakeRootState({ getStateValue: state, candidates: [], stranded: [strandedEl] });
  withEnv(() => {
    const job = makeFakeJob();
    const context = createEnhanceContext(root);
    registerParagraph(context, deadEl);
    registerParagraph(context, driftedEl);
    registerParagraph(context, rawDomEl);
    registerParagraph(context, taintedEl);
    // Host edits: the drifted paragraph's rendered children changed through
    // innerHTML (which bypasses the commit forwarding), and the raw-DOM
    // backup of the second gained content while its rendered output stayed.
    driftedEl.innerHTML = "edited live";
    context.rawDomParagraphs.get(rawDomEl).originalContent.appendChild(new FakeText(" host edit"));

    const result = reconcileRoot(context, rs, job, root, [taintedEl]);
    // DeadTrackedParagraphDrop: deadEl removed from state.paragraphs.
    assert.equal(state.paragraphs.length, 3);
    assert.equal(state.paragraphs[0].source, driftedEl);
    assert.equal(state.paragraphs[1].source, rawDomEl);
    assert.equal(state.paragraphs[2].source, taintedEl);
    // The real classifyReconcile produced the expected verdict.
    assert.deepEqual(result, { outcome: "work", drifted: 1, rawDom: 1, tainted: 1, stranded: 1, dead: 1 });
    // startLayoutJob called with kind Relayout and 4 actions.
    assert.equal(job._calls.startJob.length, 1);
    const call = job._calls.startJob[0];
    assert.equal(call.kind, "Relayout");
    assert.equal(call.itemCount, 4);
    // Execute processItem callbacks to verify action effects.
    for (let i = 0; i < call.itemTierIndex.length; i += 1) {
      call.processItem(i);
    }
    // Every action re-processed its paragraph through the real pipeline, so
    // all four end up committed back into state.paragraphs in tier order.
    assert.equal(state.paragraphs.length, 4);
    assert.deepEqual(
      state.paragraphs.map(function (item) { return item.source; }),
      [driftedEl, rawDomEl, taintedEl, strandedEl],
    );
    for (const item of state.paragraphs) {
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
  const root = makeElement();
  root._rect = { top: 0, bottom: 100, width: 300 };
  const state = {
    root: root, options: {},
    paragraphs: [{ source: el1 }, { source: el2 }],
    issues: [],
  };
  const rs = makeFakeRootState({ getStateValue: state, candidates: [] });
  withEnv(() => {
    const job = makeFakeJob();
    const context = createEnhanceContext(root);
    registerParagraph(context, el1);
    registerParagraph(context, el2);
    // Both paragraphs drift: the host replaced their rendered children.
    el1.innerHTML = "edited one";
    el2.innerHTML = "edited two";
    reconcileRoot(context, rs, job, root, []);
    assert.equal(job._calls.startJob.length, 1);
    const call = job._calls.startJob[0];
    // el2 visible (distance 0) first, then el1 above viewport (distance 100).
    assert.deepEqual(call.itemTierIndex, [1, 0]);
    // stale closure: root width matches initially.
    assert.equal(call.isStale(), false);
    // After root width drift of 1.0.
    root._rect.width = 301;
    assert.equal(call.isStale(), true);
  }, { document: makePipelineDocument(), node: FakeNode });
});
