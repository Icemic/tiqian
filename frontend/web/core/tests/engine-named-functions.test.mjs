import assert from "node:assert/strict";
import test from "node:test";

import { enhance, enhanceProgressively } from "../core/engine/progressive-drivers.js";
import { destroyRoot, detachRoot } from "../core/engine/lifecycle.js";
import { probeRootContentDrift, reconcileRoot } from "../core/engine/content-reconcile.js";
import { installFixtureFontBackend } from "../test-support/fixture-font-backend.mjs";

const ENV_GLOBALS = ["window", "document", "getComputedStyle"];

// The named engine functions (progressive-drivers, lifecycle destroy/detach,
// content-reconcile) run here against fake ledgers (detached-fragment backup,
// root-state, layout-job-pool, copy-installer). The process-paragraph and
// commit helpers are direct imports, so a ready commit path only proceeds
// through an installed prepared-DOM bridge; without one the prepare step
// returns PreparedDomBridgeUnavailable first.

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
  const c1 = makeElement();
  const c2 = makeElement();
  const fakeState = makeStateWithCallbacks({ root: null });
  const rs = makeFakeRootState({ state: fakeState, candidates: [c1, c2] });
  withEnv(() => {
    const graph = makeGraph({});
    const root = makeElement();
    const result = enhance(rs, graph.layoutJobPool, graph.rawDom, root, { fontSize: 20 });
    assert.equal(rs._calls.createRootState.length, 1);
    assert.equal(rs._calls.createRootState[0].bag.fontSize, 20);
    // The real processParagraph ran once per candidate, observable on the
    // detached-fragment backup ledger.
    assert.equal(graph.rawDom._calls.begin.length, 2);
    assert.equal(graph.rawDom._calls.begin[0][0], c1);
    assert.equal(graph.rawDom._calls.begin[1][0], c2);
    assert.equal(rs._calls.publishState.length, 1);
    assert.equal(result, 2);
  });
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
  const src1 = makeElement();
  const src2 = makeElement();
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
  const rawDom = makeFakeRawDom();
  const rs = makeFakeRootState({ getStateValue: state });
  withEnv(() => {
    const graph = makeGraph({ rawDom: rawDom });
    const root = makeElement({ "data-tiqian-snapshot-count": "5", "data-tiqian-issue-count": "2", "data-tiqian-relayout-error": "err", "data-tiqian-snapshot-layout-fallback": "fb" });
    destroyRoot(rs, graph.layoutJobPool, graph.rawDom, root);
    assert.deepEqual(rawDom._calls.restoreParagraph, [src1, src2]);
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
  });
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
    const graph = makeGraph({});
    const root = makeElement();
    detachRoot(graph.layoutJobPool, root);
    assert.equal(rs._calls.getState.length, 0);
    assert.equal(rs._calls.deleteState.length, 0);
    assert.equal(graph.layoutJobPool._calls.cancelJob.length, 1);
    assert.equal(graph.layoutJobPool._calls.cancelJob[0], root);
  });
});

// ---------------------------------------------------------------------------
// 8. probeRootContentDrift: no state => unknown result; with state => sources
//    to the real probe, which reads the fake detached-fragment backup ledger
// ---------------------------------------------------------------------------

test("8. probeRootContentDrift: no state returns the unknown result; with state passes sources to the real probe", function () {
  const rsNoState = makeFakeRootState({ getStateValue: null });
  withEnv(() => {
    const graph = makeGraph({});
    const result = probeRootContentDrift(graph.rawDom, rsNoState, makeElement());
    assert.deepEqual(result, { unknown: 1, drifted: 0, dead: 0, rawDom: 0 });
  });

  const src1 = makeElement();
  const src2 = makeElement();
  const state = { root: null, options: {}, paragraphs: [{ source: src1 }, { source: src2 }], issues: [] };
  const rawDom = makeFakeRawDom();
  const rs = makeFakeRootState({ getStateValue: state });
  withEnv(() => {
    const graph = makeGraph({ rawDom: rawDom });
    const result2 = probeRootContentDrift(graph.rawDom, rs, makeElement());
    // The real probe classified both matching sources through the
    // detached-fragment backup ledger, so nothing drifted, died or fell out
    // of detached-fragment backup.
    assert.deepEqual(rawDom._calls.renderedMatches, [src1, src2]);
    assert.deepEqual(rawDom._calls.rawDomMatches, [src1, src2]);
    assert.deepEqual(result2, { unknown: 0, drifted: 0, dead: 0, rawDom: 0 });
  });
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
  const state = { root: null, options: {}, paragraphs: [{ source: makeElement() }], issues: [] };
  const rs = makeFakeRootState({ getStateValue: state, candidates: [] });
  withEnv(() => {
    const graph = makeGraph({});
    const result = reconcileRoot(graph.rawDom, rs, graph.layoutJobPool, makeElement(), []);
    assert.deepEqual(result, { outcome: "idle", drifted: 0, rawDom: 0, tainted: 0, stranded: 0, dead: 0 });
    assert.equal(graph.layoutJobPool._calls.startJob.length, 0);
  });
});

test("9c. reconcileRoot: work verdict with drifted/rawDom/tainted/stranded + DeadTrackedParagraphDrop", function () {
  const deadEl = makeElement({}, { isConnected: false });
  const driftedEl = makeElement();
  const rawDomEl = makeElement();
  const taintedEl = makeElement({}, { closestTo: { tagName: "TIQIAN-PROSE" } });
  const strandedEl = makeElement();
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
  const rawDom = makeFakeRawDom({
    renderedMatches: (el) => el !== driftedEl,
    rawDomMatches: (el) => el !== rawDomEl,
  });
  const rs = makeFakeRootState({ getStateValue: state, candidates: [], stranded: [strandedEl] });
  withEnv(() => {
    const graph = makeGraph({ rawDom: rawDom });
    const result = reconcileRoot(graph.rawDom, rs, graph.layoutJobPool, root, [taintedEl]);
    // DeadTrackedParagraphDrop: deadEl removed from state.paragraphs.
    assert.equal(state.paragraphs.length, 3);
    assert.equal(state.paragraphs[0].source, driftedEl);
    assert.equal(state.paragraphs[1].source, rawDomEl);
    assert.equal(state.paragraphs[2].source, taintedEl);
    // The real classifyReconcile produced the expected verdict.
    assert.deepEqual(result, { outcome: "work", drifted: 1, rawDom: 1, tainted: 1, stranded: 1, dead: 1 });
    // startLayoutJob called with kind Relayout and 4 actions.
    assert.equal(graph.layoutJobPool._calls.startJob.length, 1);
    const call = graph.layoutJobPool._calls.startJob[0];
    assert.equal(call.kind, "Relayout");
    assert.equal(call.itemCount, 4);
    // Execute processItem callbacks to verify action effects.
    const processItem = call.processItem;
    const tierIndex = call.itemTierIndex;
    for (let i = 0; i < tierIndex.length; i += 1) {
      processItem(i);
    }
    // prepareTrackedParagraphForRelowering for drifted stamps it once before
    // re-lowering, and the re-processing commit stamps it again; every other
    // action stamps once on its successful commit, all in tier order.
    assert.deepEqual(rawDom._calls.restoreShell, [driftedEl]);
    assert.deepEqual(
      rawDom._calls.stampRendered,
      [driftedEl, driftedEl, rawDomEl, taintedEl, strandedEl],
    );
    // restoreParagraph for detached-fragment backup and tainted.
    assert.deepEqual(rawDom._calls.restoreParagraph, [rawDomEl, taintedEl]);
    // stripEngineMarkupFromStrandedParagraph ran inside the stranded action
    // (removals recorded on the fixture), then the re-process stamped the
    // paragraph rendered again through the real pipeline.
    assert.ok(strandedEl.removedAttributes.includes("data-tq-rendered"));
    assert.ok(strandedEl.removedAttributes.includes("data-tq-canonical-plain"));
    assert.equal(strandedEl.getAttribute("data-tq-rendered"), "true");
    // The real processParagraph ran once per action (4 total).
    assert.equal(rawDom._calls.begin.length, 4);
  });
});

test("9d. reconcileRoot: itemTierIndex sorted by (distance, index), stale closure detects width drift >= 0.5", function () {
  const el1 = makeElement();
  el1._rect = { top: -200, bottom: -100, width: 300 };
  const el2 = makeElement();
  el2._rect = { top: 0, bottom: 100, width: 300 };
  const root = makeElement();
  root._rect = { top: 0, bottom: 100, width: 300 };
  const state = {
    root: root, options: {},
    paragraphs: [{ source: el1 }, { source: el2 }],
    issues: [],
  };
  const rawDom = makeFakeRawDom({ renderedMatches: () => false });
  const rs = makeFakeRootState({ getStateValue: state, candidates: [] });
  withEnv(() => {
    const graph = makeGraph({ rawDom: rawDom });
    reconcileRoot(graph.rawDom, rs, graph.layoutJobPool, root, []);
    assert.equal(graph.layoutJobPool._calls.startJob.length, 1);
    const call = graph.layoutJobPool._calls.startJob[0];
    // el2 visible (distance 0) first, then el1 above viewport (distance 100).
    assert.deepEqual(call.itemTierIndex, [1, 0]);
    // stale closure: root width matches initially.
    assert.equal(call.isStale(), false);
    // After root width drift of 1.0.
    root._rect.width = 301;
    assert.equal(call.isStale(), true);
  });
});
