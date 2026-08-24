import assert from "node:assert/strict";
import test from "node:test";

import "./core/engine/progressive-job.js";
import "./core/engine/progressive-drivers.js";
import "./core/engine/engine-entry.js";

const ENGINE = globalThis.__TiqianEngine;
const WORKERS = globalThis.__TiqianEngineWorkers;

const GLOBALS_TO_DELETE = [
  "__TiqianEngine",
  "__TiqianEngineWorkers",
  "__TiqianRootState",
  "__TiqianProgressiveDrivers",
  "__TiqianProgressiveJob",
  "__TiqianCustody",
  "__TiqianLifecycle",
  "__TiqianContentReconcile",
  "__TiqianProcessParagraph",
  "__TiqianWorkerRequest",
  "__TiqianPreparedDomRenderer",
  "__TiqianInstallCopyHandler",
  "window",
  "document",
];

function makeElement(initialAttributes) {
  var attrs = new Map(Object.entries(initialAttributes || {}));
  var rect = { top: 0, bottom: 100, width: 300 };
  return {
    nodeType: 1,
    tagName: "P",
    isConnected: true,
    getAttribute: function (name) {
      return attrs.has(name) ? attrs.get(name) : null;
    },
    setAttribute: function (name, value) {
      attrs.set(name, String(value));
    },
    removeAttribute: function (name) {
      attrs.delete(name);
    },
    getBoundingClientRect: function () {
      var r = this._rect;
      return { top: r.top, bottom: r.bottom, width: r.width };
    },
    _rect: rect,
  };
}

function makeFakeRootState(opts) {
  opts = opts || {};
  var calls = {
    createRootState: [],
    createRootStateFromCanonical: [],
    getState: [],
    deleteState: [],
    publishState: [],
    paragraphCandidates: [],
    strandedSourceParagraphs: [],
    processParagraphArgument: [],
    currentFfi: [],
  };
  return {
    _calls: calls,
    currentFfi: function () {
      calls.currentFfi.push(true);
      return opts.ffi || { mock: true };
    },
    createRootState: function (root, bag) {
      calls.createRootState.push({ root: root, bag: bag });
      return opts.state || {
        root: root,
        options: opts.stateOptions || { paragraphSelector: "p" },
        paragraphs: opts.paragraphs || [],
        issues: opts.issues || [],
      };
    },
    createRootStateFromCanonical: function (root, options) {
      calls.createRootStateFromCanonical.push({ root: root, options: options });
      return opts.canonicalState || {
        root: root,
        options: options,
        paragraphs: opts.paragraphs || [],
        issues: opts.issues || [],
      };
    },
    getState: function (root) {
      calls.getState.push(root);
      return opts.getStateValue !== undefined ? opts.getStateValue : null;
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
      return { ffi: opts.ffi || { mock: true }, paragraph: paragraph, state: state };
    },
  };
}

function makeFakeDrivers() {
  var calls = { enhanceProgressively: [], relayout: [], startProgressiveJob: [] };
  return {
    _calls: calls,
    rejectMissingSharedRuntimeStyles: function () { return false; },
    enhanceProgressively: function (root, optionsBag) {
      calls.enhanceProgressively.push({ root: root, optionsBag: optionsBag });
    },
    enhanceProgressivelyFromCanonical: function (root, options) {
      calls.enhanceProgressively.push({ root: root, optionsBag: options, canonical: true });
    },
    relayout: function (root) {
      calls.relayout.push(root);
    },
    startProgressiveJob: function () {
      calls.startProgressiveJob.push(Array.prototype.slice.call(arguments));
    },
  };
}

function makeFakeJob() {
  var calls = { cancelJob: [] };
  return {
    _calls: calls,
    cancelJob: function (root) { calls.cancelJob.push(root); },
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

function makeFakeCustody() {
  var calls = { restoreParagraph: [] };
  return {
    _calls: calls,
    restoreParagraph: function (el) { calls.restoreParagraph.push(el); },
  };
}

function makeFakeLifecycle() {
  var calls = { clearIssue: [], optionsFromJs: [] };
  return {
    _calls: calls,
    clearIssue: function (issue) { calls.clearIssue.push(issue); },
    optionsFromJs: function (bag) {
      calls.optionsFromJs.push(bag);
      return bag || {};
    },
  };
}

function makeFakeContentReconcile() {
  var calls = { probeContentDrift: [], classifyReconcile: [], prepareTrackedParagraphForRelowering: [], stripEngineMarkupFromStrandedParagraph: [] };
  return {
    _calls: calls,
    probeContentDrift: function (sources) {
      calls.probeContentDrift.push(sources);
      return '{"unknown":0,"drifted":0,"dead":0,"custody":0}';
    },
    classifyReconcile: function (spec) {
      calls.classifyReconcile.push(spec);
      return { outcome: "idle", drifted: [], custody: [], tainted: [], stranded: [], dead: 0, json: '{"outcome":"idle","drifted":0,"custody":0,"tainted":0,"stranded":0,"dead":0}' };
    },
    prepareTrackedParagraphForRelowering: function (el) { calls.prepareTrackedParagraphForRelowering.push(el); },
    stripEngineMarkupFromStrandedParagraph: function (el) { calls.stripEngineMarkupFromStrandedParagraph.push(el); },
  };
}

function makeFakeProcessParagraph() {
  var calls = [];
  return {
    _calls: calls,
    processParagraph: function (arg) { calls.push(arg); },
  };
}

function makeFakeWorkerRequest() {
  var calls = [];
  return {
    _calls: calls,
    workerLayoutRequestForRoot: function (ffi, root, paragraph, options) {
      calls.push({ ffi: ffi, root: root, paragraph: paragraph, options: options });
      return "worker-result";
    },
  };
}

function setupEngine(opts) {
  opts = opts || {};
  globalThis.window = { innerHeight: 800 };
  globalThis.document = opts.document || { querySelectorAll: function () { return { length: 0, item: function () { return undefined; } }; } };
  globalThis.__TiqianRootState = opts.rs || makeFakeRootState(opts.rsOpts || {});
  globalThis.__TiqianProgressiveDrivers = opts.drivers || makeFakeDrivers();
  globalThis.__TiqianProgressiveJob = opts.job || makeFakeJob();
  globalThis.__TiqianCustody = opts.custody || makeFakeCustody();
  globalThis.__TiqianLifecycle = opts.lifecycle || makeFakeLifecycle();
  globalThis.__TiqianContentReconcile = opts.reconcile || makeFakeContentReconcile();
  globalThis.__TiqianProcessParagraph = opts.pp || makeFakeProcessParagraph();
  globalThis.__TiqianWorkerRequest = opts.workerReq || makeFakeWorkerRequest();
  globalThis.__TiqianPreparedDomRenderer = opts.preparedDom || { releaseRoot: function () { return true; } };
  globalThis.__TiqianInstallCopyHandler = opts.installCopyHandler || function () {};
}

function cleanupEngine() {
  for (var i = 0; i < GLOBALS_TO_DELETE.length; i += 1) {
    delete globalThis[GLOBALS_TO_DELETE[i]];
  }
}

// ---------------------------------------------------------------------------
// 1. enhance: synchronous loop processes candidates, returns count, publishes
// ---------------------------------------------------------------------------

test("1. enhance: processes each candidate via processParagraph, returns paragraphs.length, calls publishState", function () {
  var pp = makeFakeProcessParagraph();
  var fakeState = { root: null, options: { paragraphSelector: "p" }, paragraphs: [{ source: "s1" }], issues: [] };
  var rs = makeFakeRootState({ state: fakeState, candidates: ["c1", "c2"] });
  setupEngine({ rs: rs, pp: pp });
  try {
    var root = makeElement();
    var result = ENGINE.enhance(root, { fontSize: 20 });
    assert.equal(rs._calls.createRootState.length, 1);
    assert.equal(rs._calls.createRootState[0].bag.fontSize, 20);
    assert.equal(pp._calls.length, 2);
    assert.equal(pp._calls[0].paragraph, "c1");
    assert.equal(pp._calls[1].paragraph, "c2");
    assert.equal(rs._calls.publishState.length, 1);
    assert.equal(result, 1);
  } finally {
    cleanupEngine();
  }
});

// ---------------------------------------------------------------------------
// 2. enhance styles gate: rejectMissingSharedRuntimeStyles returns true => 0
// ---------------------------------------------------------------------------

test("2. enhance: rejectMissingSharedRuntimeStyles returns true => returns 0, no processParagraph", function () {
  var pp = makeFakeProcessParagraph();
  var drivers = makeFakeDrivers();
  drivers.rejectMissingSharedRuntimeStyles = function () { return true; };
  var rs = makeFakeRootState({ candidates: ["c1"] });
  setupEngine({ rs: rs, drivers: drivers, pp: pp });
  try {
    var result = ENGINE.enhance(makeElement(), {});
    assert.equal(result, 0);
    assert.equal(pp._calls.length, 0);
  } finally {
    cleanupEngine();
  }
});

// ---------------------------------------------------------------------------
// 3. enhance destroy-first: destroy runs before createRootState
// ---------------------------------------------------------------------------

test("3. enhance: destroy runs before createRootState (call order)", function () {
  var callOrder = [];
  var fakeJob = makeFakeJob();
  var origCancel = fakeJob.cancelJob;
  fakeJob.cancelJob = function (root) {
    callOrder.push("destroy");
    origCancel(root);
  };
  var rs = makeFakeRootState({
    candidates: [],
    state: { root: null, options: { paragraphSelector: "p" }, paragraphs: [], issues: [] },
  });
  var origCreate = rs.createRootState;
  rs.createRootState = function (root, bag) {
    callOrder.push("createRootState");
    return origCreate(root, bag);
  };
  setupEngine({ rs: rs, job: fakeJob });
  try {
    ENGINE.enhance(makeElement(), {});
    assert.deepEqual(callOrder, ["destroy", "createRootState"]);
  } finally {
    cleanupEngine();
  }
});

// ---------------------------------------------------------------------------
// 4. enhanceAll: scans tiqian-prose and [data-tiqian-root], sums counts
// ---------------------------------------------------------------------------

test("4. enhanceAll: scans tiqian-prose and [data-tiqian-root] roots, sums counts", function () {
  var el1 = makeElement({ "data-tiqian-root": "" });
  var el2 = makeElement();
  el2.tagName = "TIQIAN-PROSE";
  var fakeRoots = [el1, el2];
  fakeRoots.item = function (i) { return fakeRoots[i]; };
  fakeRoots.length = 2;
  var doc = { querySelectorAll: function () { return fakeRoots; } };

  var callCount = 0;
  var rs = makeFakeRootState({
    candidates: [],
    state: { root: null, options: { paragraphSelector: "p" }, paragraphs: [{ source: "x" }], issues: [] },
  });
  var origCreate = rs.createRootState;
  rs.createRootState = function (root, bag) {
    callCount += 1;
    return origCreate(root, bag);
  };
  setupEngine({ rs: rs, document: doc });
  try {
    var result = ENGINE.enhanceAll({});
    assert.equal(callCount, 2);
    assert.equal(result, 2);
  } finally {
    cleanupEngine();
  }
});

// ---------------------------------------------------------------------------
// 5. enhanceProgressively delegates to the drivers entry
// ---------------------------------------------------------------------------

test("5. enhanceProgressively delegates to the drivers entry, which owns the copy handler and destroy", function () {
  var drivers = makeFakeDrivers();
  var job = makeFakeJob();
  var copyHandlerInstalled = false;
  setupEngine({
    drivers: drivers,
    job: job,
    installCopyHandler: function () { copyHandlerInstalled = true; },
    rsOpts: {
      state: { root: null, options: {}, paragraphs: [], issues: [] },
      candidates: [],
    },
  });
  try {
    var root = makeElement();
    var bag = { fontSize: 20 };
    ENGINE.enhanceProgressively(root, bag);
    // The wrapper only delegates. The drivers entry (progressive-drivers.js)
    // installs the copy handler and destroys before rebuilding state, so the
    // relayout restarts that enter the drivers directly destroy too.
    assert.equal(drivers._calls.enhanceProgressively.length, 1);
    assert.equal(drivers._calls.enhanceProgressively[0].root, root);
    assert.equal(drivers._calls.enhanceProgressively[0].optionsBag, bag);
    assert.equal(copyHandlerInstalled, false);
    assert.equal(job._calls.cancelJob.length, 0);
  } finally {
    cleanupEngine();
  }
});

// ---------------------------------------------------------------------------
// 6. destroy: full steps -- restoreParagraph, clearIssue, releaseRoot,
//    snapshot-count branches, attribute cleanup
// ---------------------------------------------------------------------------

test("6. destroy: restores paragraphs, clears issues, releases styles, sets/removes snapshot attrs, cleans 3 attrs", function () {
  var src1 = makeElement();
  var src2 = makeElement();
  var issue1 = { name: "X" };
  var issue2 = { name: "Y" };
  var state = {
    root: null,
    options: {},
    paragraphs: [{ source: src1 }, { source: src2 }],
    issues: [issue1, issue2],
  };
  var custody = makeFakeCustody();
  var lifecycle = makeFakeLifecycle();
  var preparedDom = { releaseRoot: function () { return true; } };
  var rs = makeFakeRootState({ getStateValue: state });
  setupEngine({ rs: rs, custody: custody, lifecycle: lifecycle, preparedDom: preparedDom });
  try {
    var root = makeElement({ "data-tiqian-snapshot-count": "5", "data-tiqian-issue-count": "2", "data-tiqian-relayout-error": "err", "data-tiqian-exact-layout-fallback": "fb" });
    ENGINE.destroy(root);
    assert.deepEqual(custody._calls.restoreParagraph, [src1, src2]);
    assert.deepEqual(lifecycle._calls.clearIssue, [issue1, issue2]);
    assert.equal(root.getAttribute("data-tiqian-enhanced"), "true");
    assert.equal(root.getAttribute("data-tiqian-enhanced-count"), "5");
    assert.equal(root.getAttribute("data-tiqian-issue-count"), null);
    assert.equal(root.getAttribute("data-tiqian-relayout-error"), null);
    assert.equal(root.getAttribute("data-tiqian-exact-layout-fallback"), null);
  } finally {
    cleanupEngine();
  }
});

// ---------------------------------------------------------------------------
// 7. destroy no state: still cancelJob + attribute cleanup, no throw
// ---------------------------------------------------------------------------

test("7. destroy: no state => still cancelJob + attribute cleanup, no throw", function () {
  var custody = makeFakeCustody();
  var lifecycle = makeFakeLifecycle();
  var rs = makeFakeRootState({ getStateValue: null });
  setupEngine({ rs: rs, custody: custody, lifecycle: lifecycle });
  try {
    var root = makeElement({ "data-tiqian-relayout-error": "err" });
    ENGINE.destroy(root);
    assert.equal(custody._calls.restoreParagraph.length, 0);
    assert.equal(lifecycle._calls.clearIssue.length, 0);
    assert.equal(root.getAttribute("data-tiqian-relayout-error"), null);
    assert.equal(root.getAttribute("data-tiqian-enhanced"), null);
  } finally {
    cleanupEngine();
  }
});

// ---------------------------------------------------------------------------
// 8. detach: minimal surface -- cancelJob + releaseRoot, no state touch
// ---------------------------------------------------------------------------

test("8. detach: cancelJob + releaseRoot only, does not touch paragraphs/issues/state", function () {
  var rs = makeFakeRootState();
  setupEngine({ rs: rs });
  try {
    var root = makeElement();
    ENGINE.detach(root);
    assert.equal(rs._calls.getState.length, 0);
    assert.equal(rs._calls.deleteState.length, 0);
    assert.equal(globalThis.__TiqianProgressiveJob._calls.cancelJob.length, 1);
    assert.equal(globalThis.__TiqianProgressiveJob._calls.cancelJob[0], root);
  } finally {
    cleanupEngine();
  }
});

// ---------------------------------------------------------------------------
// 9. refresh progressive: no state => no-op; with state => destroy + canonical
// ---------------------------------------------------------------------------

test("9. refresh: no state is no-op; with state progressively => enhanceProgressivelyFromCanonical with state.options", function () {
  var rsNoState = makeFakeRootState({ getStateValue: null });
  setupEngine({ rs: rsNoState });
  try {
    ENGINE.refresh(makeElement(), true);
    assert.equal(rsNoState._calls.deleteState.length, 0);
  } finally {
    cleanupEngine();
  }

  var state = { root: null, options: { fontSize: 22 }, paragraphs: [], issues: [] };
  var drivers = makeFakeDrivers();
  var fakeJob = makeFakeJob();
  fakeJob.cancelJob = function () {};
  var rs = makeFakeRootState({ getStateValue: state });
  setupEngine({ rs: rs, drivers: drivers, job: fakeJob });
  try {
    ENGINE.refresh(makeElement(), true);
    assert.equal(drivers._calls.enhanceProgressively.length, 1);
    assert.equal(drivers._calls.enhanceProgressively[0].canonical, true);
    assert.equal(drivers._calls.enhanceProgressively[0].optionsBag, state.options);
  } finally {
    cleanupEngine();
  }
});

// ---------------------------------------------------------------------------
// 10. refresh synchronous: calls enhance(root, state.options, true) -- canonical
//     re-entry path; optionsFromJs not called
// ---------------------------------------------------------------------------

test("10. refresh synchronous: enhance canonical re-entry (fromCanonical=true), optionsFromJs not called", function () {
  var state = { root: null, options: { fontSize: 19 }, paragraphs: [], issues: [] };
  var rs = makeFakeRootState({
    getStateValue: state,
    canonicalState: { root: null, options: state.options, paragraphs: [], issues: [] },
    candidates: [],
  });
  var lifecycle = makeFakeLifecycle();
  setupEngine({ rs: rs, lifecycle: lifecycle });
  try {
    ENGINE.refresh(makeElement(), false);
    assert.equal(rs._calls.createRootStateFromCanonical.length, 1);
    assert.equal(rs._calls.createRootStateFromCanonical[0].options, state.options);
    assert.equal(lifecycle._calls.optionsFromJs.length, 0);
    assert.equal(rs._calls.createRootState.length, 0);
  } finally {
    cleanupEngine();
  }
});

// ---------------------------------------------------------------------------
// 11. cancelLayoutWork: delegates to PJ.cancelJob
// ---------------------------------------------------------------------------

test("11. cancelLayoutWork: delegates to ProgressiveJob.cancelJob", function () {
  var fakeJob = makeFakeJob();
  setupEngine({ job: fakeJob });
  try {
    var root = makeElement();
    ENGINE.cancelLayoutWork(root);
    assert.equal(fakeJob._calls.cancelJob.length, 1);
    assert.equal(fakeJob._calls.cancelJob[0], root);
  } finally {
    cleanupEngine();
  }
});

// ---------------------------------------------------------------------------
// 12. probeContentDrift: no state => unknown JSON; with state => sources to
//     content-reconcile, returns its JSON
// ---------------------------------------------------------------------------

test("12. probeContentDrift: no state returns unknown JSON; with state passes sources and returns reconcile JSON", function () {
  var rsNoState = makeFakeRootState({ getStateValue: null });
  setupEngine({ rs: rsNoState });
  try {
    var result = ENGINE.probeContentDrift(makeElement());
    assert.equal(result, '{"unknown":1,"drifted":0,"dead":0,"custody":0}');
  } finally {
    cleanupEngine();
  }

  var src1 = makeElement();
  var src2 = makeElement();
  var state = { root: null, options: {}, paragraphs: [{ source: src1 }, { source: src2 }], issues: [] };
  var reconcile = makeFakeContentReconcile();
  reconcile.probeContentDrift = function (sources) {
    reconcile._calls.probeContentDrift.push(sources);
    return '{"unknown":0,"drifted":1,"dead":0,"custody":0}';
  };
  var rs = makeFakeRootState({ getStateValue: state });
  setupEngine({ rs: rs, reconcile: reconcile });
  try {
    var result2 = ENGINE.probeContentDrift(makeElement());
    assert.equal(reconcile._calls.probeContentDrift.length, 1);
    assert.deepEqual(reconcile._calls.probeContentDrift[0], [src1, src2]);
    assert.equal(result2, '{"unknown":0,"drifted":1,"dead":0,"custody":0}');
  } finally {
    cleanupEngine();
  }
});

// ---------------------------------------------------------------------------
// 13. reconcileContent: no state => idle JSON; with state + idle => returns
//     json, no job; with work verdict => actions per category + job
// ---------------------------------------------------------------------------

test("13a. reconcileContent: no state returns idle JSON", function () {
  var rs = makeFakeRootState({ getStateValue: null });
  setupEngine({ rs: rs });
  try {
    var result = ENGINE.reconcileContent(makeElement(), []);
    assert.equal(result, '{"outcome":"idle","drifted":0,"custody":0,"tainted":0,"stranded":0,"dead":0}');
  } finally {
    cleanupEngine();
  }
});

test("13b. reconcileContent: state + idle verdict => returns json, no startProgressiveJob", function () {
  var state = { root: null, options: {}, paragraphs: [{ source: makeElement() }], issues: [] };
  var reconcile = makeFakeContentReconcile();
  var drivers = makeFakeDrivers();
  setupEngine({ rs: makeFakeRootState({ getStateValue: state, candidates: [] }), reconcile: reconcile, drivers: drivers });
  try {
    var result = ENGINE.reconcileContent(makeElement(), []);
    assert.equal(result, '{"outcome":"idle","drifted":0,"custody":0,"tainted":0,"stranded":0,"dead":0}');
    assert.equal(drivers._calls.startProgressiveJob.length, 0);
  } finally {
    cleanupEngine();
  }
});

test("13c. reconcileContent: work verdict with drifted/custody/tainted/stranded + DeadTrackedParagraphDrop", function () {
  var deadEl = makeElement();
  deadEl.isConnected = false;
  var driftedEl = makeElement();
  var custodyEl = makeElement();
  var taintedEl = makeElement();
  var strandedEl = makeElement();
  var state = {
    root: null, options: {},
    paragraphs: [
      { source: deadEl },
      { source: driftedEl },
      { source: custodyEl },
    ],
    issues: [],
  };
  var reconcile = makeFakeContentReconcile();
  reconcile.classifyReconcile = function (spec) {
    reconcile._calls.classifyReconcile.push(spec);
    return {
      outcome: "work",
      drifted: [driftedEl],
      custody: [custodyEl],
      tainted: [taintedEl],
      stranded: [strandedEl],
      dead: 1,
      json: '{"outcome":"work","drifted":1,"custody":1,"tainted":1,"stranded":1,"dead":1}',
    };
  };
  var pp = makeFakeProcessParagraph();
  var custody = makeFakeCustody();
  var drivers = makeFakeDrivers();
  var startJobCalls = [];
  setupEngine({
    rs: makeFakeRootState({ getStateValue: state, candidates: [] }),
    reconcile: reconcile, pp: pp, custody: custody, drivers: drivers,
  });
  try {
    var result = ENGINE.reconcileContent(makeElement(), [taintedEl]);
    // DeadTrackedParagraphDrop: deadEl removed from state.paragraphs
    assert.equal(state.paragraphs.length, 2);
    assert.equal(state.paragraphs[0].source, driftedEl);
    assert.equal(state.paragraphs[1].source, custodyEl);
    // classifyReconcile received spec
    assert.equal(reconcile._calls.classifyReconcile.length, 1);
    assert.deepEqual(reconcile._calls.classifyReconcile[0].trackedSources, [deadEl, driftedEl, custodyEl]);
    assert.equal(reconcile._calls.classifyReconcile[0].tainted[0], taintedEl);
    // startProgressiveJob called with kind Relayout and 4 actions
    assert.equal(drivers._calls.startProgressiveJob.length, 1);
    assert.equal(drivers._calls.startProgressiveJob[0][1], "Relayout");
    assert.equal(drivers._calls.startProgressiveJob[0][2], 4);
    assert.equal(result, '{"outcome":"work","drifted":1,"custody":1,"tainted":1,"stranded":1,"dead":1}');
    // Execute processItem callbacks to verify action effects
    var processItem = drivers._calls.startProgressiveJob[0][3];
    var tierIndex = drivers._calls.startProgressiveJob[0][7];
    for (var i = 0; i < tierIndex.length; i += 1) {
      processItem(i);
    }
    // prepareTrackedParagraphForRelowering for drifted
    assert.equal(reconcile._calls.prepareTrackedParagraphForRelowering.length, 1);
    assert.equal(reconcile._calls.prepareTrackedParagraphForRelowering[0], driftedEl);
    // restoreParagraph for custody and tainted
    assert.equal(custody._calls.restoreParagraph.length, 2);
    assert.equal(custody._calls.restoreParagraph[0], custodyEl);
    assert.equal(custody._calls.restoreParagraph[1], taintedEl);
    // stripEngineMarkup for stranded
    assert.equal(reconcile._calls.stripEngineMarkupFromStrandedParagraph.length, 1);
    assert.equal(reconcile._calls.stripEngineMarkupFromStrandedParagraph[0], strandedEl);
    // processParagraph for each action (4 total)
    assert.equal(pp._calls.length, 4);
  } finally {
    cleanupEngine();
  }
});

test("13d. reconcileContent: itemTierIndex sorted by (distance, index), stale closure detects width drift >= 0.5", function () {
  var el1 = makeElement();
  el1._rect = { top: -200, bottom: -100, width: 300 };
  var el2 = makeElement();
  el2._rect = { top: 0, bottom: 100, width: 300 };
  var root = makeElement();
  root._rect = { top: 0, bottom: 100, width: 300 };
  var state = {
    root: null, options: {},
    paragraphs: [{ source: el1 }],
    issues: [],
  };
  var reconcile = makeFakeContentReconcile();
  reconcile.classifyReconcile = function (spec) {
    reconcile._calls.classifyReconcile.push(spec);
    return {
      outcome: "work",
      drifted: [el1, el2],
      custody: [], tainted: [], stranded: [],
      dead: 0,
      json: '{"outcome":"work","drifted":2}',
    };
  };
  var drivers = makeFakeDrivers();
  setupEngine({
    rs: makeFakeRootState({ getStateValue: state, candidates: [] }),
    reconcile: reconcile, drivers: drivers,
  });
  try {
    ENGINE.reconcileContent(root, []);
    assert.equal(drivers._calls.startProgressiveJob.length, 1);
    var call = drivers._calls.startProgressiveJob[0];
    // el2 visible (distance 0) first, then el1 above viewport (distance 100)
    assert.deepEqual(call[7], [1, 0]);
    // stale closure: root width matches initially
    assert.equal(call[6](), false);
    // After root width drift of 1.0
    root._rect.width = 301;
    assert.equal(call[6](), true);
  } finally {
    cleanupEngine();
  }
});

// ---------------------------------------------------------------------------
// 14. workerLayoutRequest: forwards to workerLayoutRequestForRoot with
//     optionsFromJs pre-processing
// ---------------------------------------------------------------------------

test("14. workerLayoutRequest: forwards to workerLayoutRequestForRoot, options pre-processed by optionsFromJs", function () {
  var workerReq = makeFakeWorkerRequest();
  var lifecycle = makeFakeLifecycle();
  var ffiObj = { mock: true };
  var rs = makeFakeRootState({ ffi: ffiObj });
  setupEngine({ rs: rs, workerReq: workerReq, lifecycle: lifecycle });
  try {
    var root = makeElement();
    var para = makeElement();
    var bag = { fontSize: 19 };
    var result = ENGINE.workerLayoutRequest(root, para, bag);
    assert.equal(workerReq._calls.length, 1);
    assert.equal(workerReq._calls[0].ffi, ffiObj);
    assert.equal(workerReq._calls[0].root, root);
    assert.equal(workerReq._calls[0].paragraph, para);
    assert.equal(lifecycle._calls.optionsFromJs.length, 1);
    assert.equal(lifecycle._calls.optionsFromJs[0], bag);
    assert.equal(result, "worker-result");
  } finally {
    cleanupEngine();
  }
});

// ---------------------------------------------------------------------------
// 15. workers panel: 9 methods forward to ProgressiveJob
// ---------------------------------------------------------------------------

test("15. workers: 9 methods forward to ProgressiveJob", function () {
  var fakeJob = makeFakeJob();
  setupEngine({ job: fakeJob });
  try {
    var root = makeElement();
    assert.equal(WORKERS.workerAttach(root), "attached-" + root);
    assert.equal(WORKERS.workerDetach(root), "detached-" + root);
    assert.equal(WORKERS.workerHasJob(root), "hasJob-" + root);
    assert.equal(WORKERS.workerJobGeneration(root), 42);
    assert.equal(WORKERS.workerRunSlice({}, 0), "ran");
    assert.equal(WORKERS.workerPendingInTier(root, 1), 7);
    assert.equal(WORKERS.workerParagraphCount(root), 3);
    assert.equal(WORKERS.workerParagraphAt(root, 0), "p-0");
    assert.equal(WORKERS.workerSetParagraphTier(root, 0, 2), "set");
  } finally {
    cleanupEngine();
  }
});
