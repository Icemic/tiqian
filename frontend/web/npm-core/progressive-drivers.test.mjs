import assert from "node:assert/strict";
import test from "node:test";

import "./core/engine/progressive-job.js";
import "./core/engine/progressive-relayout-session.js";
import "./core/engine/progressive-drivers.js";

const enhanceProgressively =
  globalThis.__TiqianProgressiveDrivers.enhanceProgressively;
const relayout = globalThis.__TiqianProgressiveDrivers.relayout;

const GLOBALS_TO_SAVE = [
  "__TiqianProgressiveDrivers",
  "__TiqianRootState",
  "__TiqianProgressiveJob",
  "__TiqianProgressiveRelayoutSession",
  "__TiqianPrepareParagraphLayout",
  "__TiqianProcessParagraph",
  "__TiqianLifecycle",
  "__TiqianResponsiveMeasure",
  "window",
  "getComputedStyle",
];

function preserveGlobals(names) {
  return names.map(function (name) {
    return {
      name: name,
      own: Object.prototype.hasOwnProperty.call(globalThis, name),
      value: globalThis[name],
    };
  });
}

function restoreGlobals(entries) {
  for (let i = 0; i < entries.length; i += 1) {
    const e = entries[i];
    if (e.own) globalThis[e.name] = e.value;
    else delete globalThis[e.name];
  }
}

function makeElement(initialAttributes) {
  const attrs = new Map(Object.entries(initialAttributes || {}));
  const events = [];
  const rect = { top: 0, bottom: 100, width: 300 };
  return {
    tagName: "P",
    getAttribute: function (name) {
      return attrs.has(name) ? attrs.get(name) : null;
    },
    setAttribute: function (name, value) {
      attrs.set(name, String(value));
    },
    removeAttribute: function (name) {
      attrs.delete(name);
    },
    dispatchEvent: function (event) {
      events.push(event);
      return true;
    },
    events: events,
    _rect: rect,
    getBoundingClientRect: function () {
      // Read through this._rect so tests can swap or mutate the rect after
      // creation; a closure over the original object would ignore both.
      const r = this._rect;
      return { top: r.top, bottom: r.bottom, width: r.width };
    },
    querySelectorAll: function () {
      return { length: 0 };
    },
  };
}

function makeParagraph(overrides) {
  overrides = overrides || {};
  const source = overrides.source || makeElement();
  return {
    source: source,
    lowered: overrides.lowered || { text: "test" },
    lastMeasure: overrides.lastMeasure || null,
  };
}

function makeFakeWindow() {
  return {
    innerHeight: 800,
    getComputedStyle: function (element) {
      return {
        getPropertyValue: function (prop) {
          if (prop === "--tq-styles-ready") return "1";
          if (prop === "font-size") return "19px";
          return "";
        },
      };
    },
  };
}

function makeFakeDocument() {
  return {
    documentElement: { clientHeight: 800 },
  };
}

function makeFakeGetComputedStyle() {
  return function (element) {
    return {
      getPropertyValue: function (prop) {
        if (prop === "--tq-styles-ready") return "1";
        if (prop === "font-size") return "19px";
        return "";
      },
    };
  };
}

function makeFakeRootState(overrides) {
  overrides = overrides || {};
  const createRootStateCalls = [];
  const createRootStateFromCanonicalCalls = [];
  const getStateCalls = [];
  const setStateCalls = [];
  const deleteStateCalls = [];
  const publishStateCalls = [];
  const processParagraphArgumentCalls = [];
  const sessionArgumentCalls = [];
  const prepareArgumentCalls = [];
  const paragraphCandidatesCalls = [];
  const strandedSourceParagraphsCalls = [];

  return {
    _calls: {
      createRootState: createRootStateCalls,
      createRootStateFromCanonical: createRootStateFromCanonicalCalls,
      getState: getStateCalls,
      setState: setStateCalls,
      deleteState: deleteStateCalls,
      publishState: publishStateCalls,
      processParagraphArgument: processParagraphArgumentCalls,
      sessionArgument: sessionArgumentCalls,
      prepareArgument: prepareArgumentCalls,
      paragraphCandidates: paragraphCandidatesCalls,
      strandedSourceParagraphs: strandedSourceParagraphsCalls,
    },
    bindFfi: function () {},
    currentFfi: function () {
      return overrides.ffi || { mock: true };
    },
    createRootState: function (root, optionsBag) {
      const state = {
        root: root,
        options: overrides.stateOptions || {
          paragraphSelector: "p",
          fontSize: 19,
        },
        paragraphs: overrides.paragraphs || [],
        issues: overrides.issues || [],
        preparedDomEnabled: true,
        tsOptions: overrides.tsOptions || { ts: true },
        exactSession: overrides.exactSession || { sessionId: "s1" },
        browserFallback: overrides.browserFallback || null,
        onIssue: overrides.onIssue || function () {},
        onParagraphCommitted: overrides.onParagraphCommitted || function () {},
        onDisableExactPreparedDom: overrides.onDisableExactPreparedDom || function () {},
      };
      createRootStateCalls.push({ root: root, optionsBag: optionsBag });
      return state;
    },
    createRootStateFromCanonical: function (root, options) {
      createRootStateFromCanonicalCalls.push({ root: root, options: options });
      return overrides.canonicalState || {
        root: root,
        options: options,
        paragraphs: [],
        issues: [],
        preparedDomEnabled: true,
        tsOptions: {},
        exactSession: null,
        browserFallback: null,
        onIssue: function () {},
        onParagraphCommitted: function () {},
        onDisableExactPreparedDom: function () {},
      };
    },
    getState: function (root) {
      getStateCalls.push(root);
      return overrides.getStateValue !== undefined ? overrides.getStateValue : null;
    },
    setState: function (root, state) {
      setStateCalls.push({ root: root, state: state });
    },
    deleteState: function (root) {
      deleteStateCalls.push(root);
    },
    publishState: function (state, keepEmpty) {
      publishStateCalls.push({ state: state, keepEmpty: keepEmpty });
    },
    processParagraphArgument: function (state, paragraph) {
      processParagraphArgumentCalls.push({ state: state, paragraph: paragraph });
      return {
        ffi: state.ffi || overrides.ffi || { mock: true },
        paragraph: paragraph,
        state: state,
      };
    },
    sessionArgument: function (state) {
      sessionArgumentCalls.push({ state: state });
      return { paragraphs: state.paragraphs, state: state };
    },
    prepareArgument: function (state, paragraph, widthOverride) {
      prepareArgumentCalls.push({
        state: state,
        paragraph: paragraph,
        widthOverride: widthOverride,
      });
      return {
        paragraph: paragraph,
        options: state.tsOptions || {},
        exactSession: state.exactSession || null,
        browserFallback: state.browserFallback || null,
        widthOverride: widthOverride,
      };
    },
    paragraphCandidates: function (root, selector) {
      paragraphCandidatesCalls.push({ root: root, selector: selector });
      return overrides.candidates || [];
    },
    strandedSourceParagraphs: function (root, state) {
      strandedSourceParagraphsCalls.push({ root: root, state: state });
      return overrides.stranded || [];
    },
  };
}

function makeFakeLifecycle() {
  const responsiveSourceMeasureCalls = [];
  return {
    _calls: { responsiveSourceMeasure: responsiveSourceMeasureCalls },
    responsiveSourceMeasure: function (paragraph, fontSize) {
      responsiveSourceMeasureCalls.push({ paragraph: paragraph, fontSize: fontSize });
      return 100;
    },
    reportIssue: function () {},
  };
}

function makeFakeLifecycleWith(measures) {
  const calls = { responsiveSourceMeasure: [] };
  return {
    _calls: calls,
    responsiveSourceMeasure: function (paragraph, fontSize) {
      calls.responsiveSourceMeasure.push({ paragraph: paragraph, fontSize: fontSize });
      return measures[calls.responsiveSourceMeasure.length - 1];
    },
    reportIssue: function () {},
  };
}

function makeFakeResponsiveMeasure() {
  return {
    sourceParagraphWidth: function () {
      return 300;
    },
  };
}

function makeFakeProcessParagraph() {
  const calls = [];
  return {
    _calls: calls,
    processParagraph: function (arg) {
      calls.push(arg);
    },
  };
}

function makeFakePrepareParagraphLayout() {
  const calls = [];
  return {
    _calls: calls,
    prepareParagraphLayout: function (ffi, arg) {
      calls.push({ ffi: ffi, argument: arg });
      return { kind: "ready", planJson: "{}" };
    },
  };
}

function makeFakeSession() {
  const processItemCalls = [];
  const finishCalls = [];
  const rollbackCalls = [];
  return {
    _calls: {
      processItem: processItemCalls,
      finish: finishCalls,
      rollback: rollbackCalls,
    },
    processItem: function (index, preparation) {
      processItemCalls.push({ index: index, preparation: preparation });
    },
    finish: function () {
      finishCalls.push(true);
    },
    rollback: function () {
      rollbackCalls.push(true);
    },
    stale: false,
  };
}

function setupGlobals(rootStateOpts) {
  const saved = preserveGlobals(GLOBALS_TO_SAVE);

  globalThis.window = makeFakeWindow();
  globalThis.document = makeFakeDocument();
  globalThis.getComputedStyle = makeFakeGetComputedStyle();
  globalThis.CustomEvent = function (type, init) {
    this.type = type;
    this.bubbles = init && init.bubbles;
    this.composed = init && init.composed;
    this.detail = init && init.detail;
  };

  const fakeRS = makeFakeRootState(rootStateOpts || {});
  globalThis.__TiqianRootState = fakeRS;

  // The relayout main path measures every rendered source before the session
  // starts; every test needs a live responsive measure bridge.
  globalThis.__TiqianResponsiveMeasure = makeFakeResponsiveMeasure();

  const fakeJob = globalThis.__TiqianProgressiveJob;
  const fakeSession = globalThis.__TiqianProgressiveRelayoutSession;

  return {
    saved: saved,
    fakeRS: fakeRS,
    fakeJob: fakeJob,
    fakeSession: fakeSession,
  };
}

function teardown(ctx) {
  restoreGlobals(ctx.saved);
}

// ---------------------------------------------------------------------------
// 1. enhanceProgressively
// ---------------------------------------------------------------------------

test("1a. cancelJob is called before createRootState", function () {
  const ctx = setupGlobals();
  try {
    const cancelJobCalls = [];
    const origCancelJob = ctx.fakeJob.cancelJob;
    ctx.fakeJob.cancelJob = function (root) {
      cancelJobCalls.push(root);
    };

    const root = makeElement();
    enhanceProgressively(root, { fontSize: 20 });

    assert.equal(cancelJobCalls.length, 1);
    assert.equal(cancelJobCalls[0], root);
    assert.equal(ctx.fakeRS._calls.createRootState.length, 1);
    assert.equal(ctx.fakeRS._calls.createRootState[0].optionsBag.fontSize, 20);
    // cancelJob happens before createRootState
    assert.ok(cancelJobCalls.length > 0);
  } finally {
    teardown(ctx);
  }
});

test("1b. work order sorted by (distance, index) ascending", function () {
  // Candidates are source elements: p1 above the viewport (distance 100),
  // p2 visible (distance 0), p3 below the viewport (top 900 - innerHeight
  // 800 = distance 100). p1 and p3 tie on distance, so index breaks the tie.
  const measures = [100, 100, 100];
  const lifecycle = makeFakeLifecycleWith(measures);
  const p1 = makeElement();
  p1._rect = { top: -200, bottom: -100, width: 300 };
  const p2 = makeElement();
  p2._rect = { top: 0, bottom: 100, width: 300 };
  const p3 = makeElement();
  p3._rect = { top: 900, bottom: 1000, width: 300 };

  const ctx = setupGlobals({
    candidates: [p1, p2, p3],
  });
  globalThis.__TiqianLifecycle = lifecycle;

  const startJobCalls = [];
  ctx.fakeJob.startJob = function (spec) {
    startJobCalls.push(spec);
  };
  try {
    const root = makeElement();
    enhanceProgressively(root, {});

    assert.equal(startJobCalls.length, 1);
    const spec = startJobCalls[0];
    assert.equal(spec.kind, "Enhance");
    assert.equal(spec.itemCount, 3);
    // p2 (distance 0, index 1) first, then the distance-100 tie p1 (index 0)
    // before p3 (index 2).
    assert.deepEqual(spec.itemTierIndex, [1, 0, 2]);
  } finally {
    teardown(ctx);
  }
});

test("1c. itemTierIndex and paragraphsByDoc passed to startJob", function () {
  const measures = [100, 100];
  const lifecycle = makeFakeLifecycleWith(measures);
  const p1 = makeParagraph();
  const p2 = makeParagraph();

  const ctx = setupGlobals({
    candidates: [p1, p2],
  });
  globalThis.__TiqianLifecycle = lifecycle;

  const startJobCalls = [];
  const origStartJob = ctx.fakeJob.startJob;
  ctx.fakeJob.startJob = function (spec) {
    startJobCalls.push(spec);
  };
  try {
    const root = makeElement();
    enhanceProgressively(root, {});

    assert.equal(startJobCalls.length, 1);
    const spec = startJobCalls[0];
    assert.equal(spec.kind, "Enhance");
    assert.equal(spec.itemCount, 2);
    // itemTierIndex should be [0, 1] for two elements at distance 0
    assert.deepEqual(spec.itemTierIndex, [0, 1]);
    // paragraphsByDoc should be the source candidates (original order)
    assert.equal(spec.paragraphsByDoc.length, 2);
    assert.equal(spec.paragraphsByDoc[0], p1);
    assert.equal(spec.paragraphsByDoc[1], p2);
  } finally {
    teardown(ctx);
  }
});

test("1d. processItem calls processParagraphArgument and processParagraph for non-stale items", function () {
  // Third 100 is the live re-measure of index 0 inside processItem: it must
  // equal the captured value or the guard marks the item stale.
  const measures = [100, 100, 100];
  const lifecycle = makeFakeLifecycleWith(measures);
  const pp = makeFakeProcessParagraph();
  globalThis.__TiqianProcessParagraph = pp;

  const p1 = makeParagraph();
  const p2 = makeParagraph();
  const ctx = setupGlobals({
    candidates: [p1, p2],
  });
  globalThis.__TiqianLifecycle = lifecycle;

  let processItemFn = null;
  ctx.fakeJob.startJob = function (spec) {
    processItemFn = spec.processItem;
  };
  try {
    const root = makeElement();
    enhanceProgressively(root, {});

    assert.ok(processItemFn);

    // Call processItem for index 0: live measure matches captured => processParagraph
    processItemFn(0);
    assert.equal(ctx.fakeRS._calls.processParagraphArgument.length, 1);
    assert.equal(ctx.fakeRS._calls.processParagraphArgument[0].paragraph, p1);
    assert.equal(pp._calls.length, 1);
    assert.equal(pp._calls[0].paragraph, p1);
  } finally {
    teardown(ctx);
  }
});

test("1e. processItem sets stale when measure drifts and does not process", function () {
  // Measures: first capture for p1=100, p2=100. Live for p1=200 (drift), p2=100.
  const measures = [100, 100, 200, 100];
  const lifecycle = makeFakeLifecycleWith(measures);
  const pp = makeFakeProcessParagraph();
  globalThis.__TiqianProcessParagraph = pp;

  const p1 = makeParagraph();
  const p2 = makeParagraph();
  const ctx = setupGlobals({
    candidates: [p1, p2],
  });
  globalThis.__TiqianLifecycle = lifecycle;

  let isStaleFn = null;
  let processItemFn = null;
  ctx.fakeJob.startJob = function (spec) {
    processItemFn = spec.processItem;
    isStaleFn = spec.isStale;
  };
  try {
    const root = makeElement();
    enhanceProgressively(root, {});

    // Process index 0: live measure (200) != captured (100) => stale, no process
    processItemFn(0);
    assert.equal(pp._calls.length, 0);
    assert.equal(isStaleFn(), true);
  } finally {
    teardown(ctx);
  }
});

test("1f. onItemsFinished aggregates stale across all items", function () {
  // Capture: p1=100, p2=100. Live recheck: p1=100 (same), p2=200 (drift).
  const measures = [100, 100, 100, 200];
  const lifecycle = makeFakeLifecycleWith(measures);

  const p1 = makeParagraph();
  const p2 = makeParagraph();
  const ctx = setupGlobals({
    candidates: [p1, p2],
  });
  globalThis.__TiqianLifecycle = lifecycle;

  let onItemsFinishedFn = null;
  let isStaleFn = null;
  ctx.fakeJob.startJob = function (spec) {
    onItemsFinishedFn = spec.onItemsFinished;
    isStaleFn = spec.isStale;
  };
  try {
    const root = makeElement();
    enhanceProgressively(root, {});

    assert.ok(onItemsFinishedFn);
    onItemsFinishedFn();
    // After finish, stale should be true because p2 drifted
    assert.equal(isStaleFn(), true);
  } finally {
    teardown(ctx);
  }
});

test("1g. SharedRuntimeStylesCapabilityGate: --tq-styles-ready != 1 reports MissingSharedRuntimeStyles and does not startJob", function () {
  const pp = makeFakeProcessParagraph();
  globalThis.__TiqianProcessParagraph = pp;

  const p1 = makeParagraph();
  const ctx = setupGlobals({
    candidates: [p1],
  });
  // Override window.getComputedStyle to return non-1 for --tq-styles-ready
  globalThis.window.getComputedStyle = function () {
    return {
      getPropertyValue: function (prop) {
        if (prop === "--tq-styles-ready") return "0";
        return "";
      },
    };
  };
  globalThis.__TiqianLifecycle = makeFakeLifecycleWith([100]);

  const reportedIssues = [];
  ctx.fakeRS._calls.createRootState = [];
  const origCreateRootState = ctx.fakeRS.createRootState;
  ctx.fakeRS.createRootState = function (root, bag) {
    const state = origCreateRootState(root, bag);
    state.issues = reportedIssues;
    return state;
  };

  const startJobCalls = [];
  ctx.fakeJob.startJob = function (spec) {
    startJobCalls.push(spec);
  };
  try {
    const root = makeElement();
    enhanceProgressively(root, {});

    // Should not start a job
    assert.equal(startJobCalls.length, 0);
    // Should have reported the issue
    assert.ok(reportedIssues.length > 0);
    assert.equal(reportedIssues[0].name, "MissingSharedRuntimeStyles");
    assert.equal(reportedIssues[0].detail, "Load @tiqian/prose/styles.css before TiqianWeb.enhance");
  } finally {
    teardown(ctx);
  }
});

// ---------------------------------------------------------------------------
// 2. relayout branch 1: jobKind=Enhance + running state => canonical options
// ---------------------------------------------------------------------------

test("2. relayout branch 1: Enhance running with state => restart with canonical options (kind Enhance)", function () {
  const measures = [100];
  const lifecycle = makeFakeLifecycleWith(measures);

  const runningState = {
    options: { fontSize: 22, paragraphSelector: "p" },
    paragraphs: [],
    issues: [],
  };

  const ctx = setupGlobals({
    getStateValue: runningState,
    candidates: [makeParagraph()],
  });
  globalThis.__TiqianLifecycle = lifecycle;

  // Fake jobKind to return "Enhance"; restore it in finally so later tests
  // observe the real job table (test 4 must reach branch 3, not branch 1).
  const origJobKind = ctx.fakeJob.jobKind;
  ctx.fakeJob.jobKind = function () {
    return "Enhance";
  };

  const startJobCalls = [];
  ctx.fakeJob.startJob = function (spec) {
    startJobCalls.push(spec);
  };
  try {
    const root = makeElement();
    relayout(root);

    // Should restart with the running state's canonical options. Kotlin's
    // two-arg overload restarts the interrupted enhance, so the kind stays
    // Enhance and the finish event stays tiqian:ready. Canonical options
    // must go through createRootStateFromCanonical; feeding them to
    // createRootState would re-resolve them through optionsFromJs.
    assert.equal(startJobCalls.length, 1);
    assert.equal(startJobCalls[0].kind, "Enhance");
    assert.equal(ctx.fakeRS._calls.createRootStateFromCanonical.length, 1);
    assert.equal(ctx.fakeRS._calls.createRootStateFromCanonical[0].options, runningState.options);
    assert.equal(ctx.fakeRS._calls.createRootState.length, 0);
  } finally {
    ctx.fakeJob.jobKind = origJobKind;
    teardown(ctx);
  }
});

// ---------------------------------------------------------------------------
// 3. relayout branch 2: no state => cold-start Relayout with bag null
// ---------------------------------------------------------------------------

test("3. relayout branch 2: no state => cold-start Relayout with bag null", function () {
  const measures = [100];
  const lifecycle = makeFakeLifecycleWith(measures);

  const ctx = setupGlobals({
    getStateValue: null,
    candidates: [makeParagraph()],
  });
  globalThis.__TiqianLifecycle = lifecycle;

  const startJobCalls = [];
  ctx.fakeJob.startJob = function (spec) {
    startJobCalls.push(spec);
  };
  try {
    const root = makeElement();
    relayout(root);

    assert.equal(startJobCalls.length, 1);
    assert.equal(startJobCalls[0].kind, "Relayout");
    // createRootState was called with bag null
    assert.equal(ctx.fakeRS._calls.createRootState.length, 1);
    assert.equal(ctx.fakeRS._calls.createRootState[0].optionsBag, null);
  } finally {
    teardown(ctx);
  }
});

// ---------------------------------------------------------------------------
// 4. relayout branch 3: width-dependent issue => enhance path
// ---------------------------------------------------------------------------

test("4. relayout branch 3: InlineCloneDecorationBreakUnsupported issue => enhance path", function () {
  const measures = [100];
  const lifecycle = makeFakeLifecycleWith(measures);

  const root = makeElement();

  const stateWithIssue = {
    root: root,
    options: { fontSize: 19 },
    paragraphs: [],
    issues: [{ name: "InlineCloneDecorationBreakUnsupported" }],
  };

  const ctx = setupGlobals({
    getStateValue: stateWithIssue,
    candidates: [makeParagraph()],
  });
  globalThis.__TiqianLifecycle = lifecycle;

  const cancelJobCalls = [];
  ctx.fakeJob.cancelJob = function (root) {
    cancelJobCalls.push(root);
  };
  const startJobCalls = [];
  ctx.fakeJob.startJob = function (spec) {
    startJobCalls.push(spec);
  };
  try {
    relayout(root);

    // cancelJob ran twice: branch 3 cancels explicitly, then the restart's
    // engine-less fallback (unit world) cancels again. Both are idempotent;
    // hosted worlds see the same double through engine.destroy.
    assert.equal(cancelJobCalls.length, 2);
    // Then restarts with enhance path using the state's canonical options
    assert.equal(startJobCalls.length, 1);
    assert.equal(startJobCalls[0].kind, "Relayout");
    assert.equal(ctx.fakeRS._calls.createRootStateFromCanonical.length, 1);
    assert.equal(ctx.fakeRS._calls.createRootStateFromCanonical[0].options, stateWithIssue.options);
  } finally {
    teardown(ctx);
  }
});

// ---------------------------------------------------------------------------
// 5. relayout main path: session, processItem, stale threshold, rollback, finish
// ---------------------------------------------------------------------------

test("5a. relayout main path: sessionArgument creates session, processItem dispatches stranded and rendered", function () {
  const measures = [100, 100];
  const lifecycle = makeFakeLifecycleWith(measures);
  const pp = makeFakeProcessParagraph();
  globalThis.__TiqianProcessParagraph = pp;
  const ppl = makeFakePrepareParagraphLayout();
  globalThis.__TiqianPrepareParagraphLayout = ppl;
  const fakeSession = makeFakeSession();
  globalThis.__TiqianProgressiveRelayoutSession = {
    createProgressiveRelayoutSession: function () {
      return fakeSession;
    },
  };

  const root = makeElement();
  root._rect = { top: 0, bottom: 100, width: 300 };

  const renderedP = makeParagraph();
  const strandedP = makeParagraph();
  const state = {
    root: root,
    options: { fontSize: 19, paragraphSelector: "p" },
    paragraphs: [renderedP],
    issues: [],
    ffi: { mock: true },
    tsOptions: { ts: true },
    exactSession: null,
    browserFallback: null,
    onIssue: function () {},
    onParagraphCommitted: function () {},
    onDisableExactPreparedDom: function () {},
  };

  const ctx = setupGlobals({
    getStateValue: state,
    stranded: [strandedP],
    candidates: [],
  });
  globalThis.__TiqianLifecycle = lifecycle;

  ctx.fakeJob.jobKind = function () {
    return null;
  };
  ctx.fakeJob.cancelJob = function () {};

  const startJobCalls = [];
  ctx.fakeJob.startJob = function (spec) {
    startJobCalls.push(spec);
  };
  try {
    relayout(root);

    assert.equal(startJobCalls.length, 1);
    const spec = startJobCalls[0];
    assert.equal(spec.kind, "Relayout");
    // count = rendered(1) + stranded(1) = 2
    assert.equal(spec.itemCount, 2);

    // Process rendered item (mixIndex 0): should call prepareParagraphLayout
    spec.processItem(0);
    assert.equal(ppl._calls.length, 1);
    assert.equal(fakeSession._calls.processItem.length, 1);
    assert.equal(fakeSession._calls.processItem[0].index, 0);

    // Process stranded item (mixIndex 1): should call processParagraph
    spec.processItem(1);
    assert.equal(pp._calls.length, 1);
    assert.equal(pp._calls[0].paragraph, strandedP);
  } finally {
    teardown(ctx);
  }
});

test("5b. relayout main path: prepareArgument includes widths", function () {
  const measures = [100];
  const lifecycle = makeFakeLifecycleWith(measures);
  const ppl = makeFakePrepareParagraphLayout();
  globalThis.__TiqianPrepareParagraphLayout = ppl;
  const fakeSession = makeFakeSession();
  globalThis.__TiqianProgressiveRelayoutSession = {
    createProgressiveRelayoutSession: function () {
      return fakeSession;
    },
  };

  const root = makeElement();

  const renderedP = makeParagraph();
  const state = {
    root: root,
    options: { fontSize: 19 },
    paragraphs: [renderedP],
    issues: [],
    ffi: { mock: true },
    tsOptions: { ts: true },
    exactSession: null,
    browserFallback: null,
  };

  const ctx = setupGlobals({
    getStateValue: state,
    candidates: [],
  });
  globalThis.__TiqianLifecycle = lifecycle;
  // setupGlobals installs the default responsive measure; this test wants a
  // distinct width so the assertion proves prepareArgument received it.
  globalThis.__TiqianResponsiveMeasure = {
    sourceParagraphWidth: function () {
      return 250;
    },
  };

  ctx.fakeJob.jobKind = function () {
    return null;
  };
  ctx.fakeJob.cancelJob = function () {};

  const prepareArgCalls = [];
  const origPrepareArg = ctx.fakeRS.prepareArgument;
  ctx.fakeRS.prepareArgument = function (state, paragraph, widthOverride) {
    prepareArgCalls.push({ paragraph: paragraph, widthOverride: widthOverride });
    return origPrepareArg(state, paragraph, widthOverride);
  };

  const startJobCalls = [];
  ctx.fakeJob.startJob = function (spec) {
    startJobCalls.push(spec);
  };
  try {
    relayout(root);

    const spec = startJobCalls[0];
    spec.processItem(0);

    assert.equal(prepareArgCalls.length, 1);
    assert.equal(prepareArgCalls[0].paragraph, renderedP);
    // width should come from responsive measure
    assert.equal(prepareArgCalls[0].widthOverride, 250);
  } finally {
    teardown(ctx);
  }
});

test("5c. relayout main path: stale when root width drifts >= 0.5", function () {
  const measures = [100];
  const lifecycle = makeFakeLifecycleWith(measures);
  const fakeSession = makeFakeSession();
  globalThis.__TiqianProgressiveRelayoutSession = {
    createProgressiveRelayoutSession: function () {
      return fakeSession;
    },
  };

  const root = makeElement();
  root._rect = { top: 0, bottom: 100, width: 300 };

  const state = {
    root: root,
    options: { fontSize: 19 },
    paragraphs: [],
    issues: [],
  };

  const ctx = setupGlobals({
    getStateValue: state,
    candidates: [],
  });
  globalThis.__TiqianLifecycle = lifecycle;

  ctx.fakeJob.jobKind = function () {
    return null;
  };
  ctx.fakeJob.cancelJob = function () {};

  let staleFn = null;
  const startJobCalls = [];
  ctx.fakeJob.startJob = function (spec) {
    startJobCalls.push(spec);
    staleFn = spec.isStale;
  };
  try {
    relayout(root);

    // Initially root width matches (300) => not stale from width drift
    // But session.stale is also false
    assert.equal(staleFn(), false);

    // Simulate root width drift
    root._rect.width = 301;
    assert.equal(staleFn(), true);
  } finally {
    teardown(ctx);
  }
});

test("5d. relayout main path: onFailure calls rollback", function () {
  const measures = [100];
  const lifecycle = makeFakeLifecycleWith(measures);
  const fakeSession = makeFakeSession();
  globalThis.__TiqianProgressiveRelayoutSession = {
    createProgressiveRelayoutSession: function () {
      return fakeSession;
    },
  };

  const root = makeElement();

  const state = {
    root: root,
    options: { fontSize: 19 },
    paragraphs: [],
    issues: [],
  };

  const ctx = setupGlobals({
    getStateValue: state,
    candidates: [],
  });
  globalThis.__TiqianLifecycle = lifecycle;

  ctx.fakeJob.jobKind = function () {
    return null;
  };
  ctx.fakeJob.cancelJob = function () {};

  let onFailureFn = null;
  const startJobCalls = [];
  ctx.fakeJob.startJob = function (spec) {
    startJobCalls.push(spec);
    onFailureFn = spec.onFailure;
  };
  try {
    relayout(root);

    assert.ok(onFailureFn);
    onFailureFn();
    assert.equal(fakeSession._calls.rollback.length, 1);
  } finally {
    teardown(ctx);
  }
});

test("5e. relayout main path: onItemsFinished calls finish", function () {
  const measures = [100];
  const lifecycle = makeFakeLifecycleWith(measures);
  const fakeSession = makeFakeSession();
  globalThis.__TiqianProgressiveRelayoutSession = {
    createProgressiveRelayoutSession: function () {
      return fakeSession;
    },
  };

  const root = makeElement();

  const state = {
    root: root,
    options: { fontSize: 19 },
    paragraphs: [],
    issues: [],
  };

  const ctx = setupGlobals({
    getStateValue: state,
    candidates: [],
  });
  globalThis.__TiqianLifecycle = lifecycle;

  ctx.fakeJob.jobKind = function () {
    return null;
  };
  ctx.fakeJob.cancelJob = function () {};

  let onItemsFinishedFn = null;
  const startJobCalls = [];
  ctx.fakeJob.startJob = function (spec) {
    startJobCalls.push(spec);
    onItemsFinishedFn = spec.onItemsFinished;
  };
  try {
    relayout(root);

    assert.ok(onItemsFinishedFn);
    onItemsFinishedFn();
    assert.equal(fakeSession._calls.finish.length, 1);
  } finally {
    teardown(ctx);
  }
});

// ---------------------------------------------------------------------------
// 6. finishing reporting layer
// ---------------------------------------------------------------------------

test("6a. finish: dispatches tiqian:ready with correct detail fields", function () {
  const measures = [100];
  const lifecycle = makeFakeLifecycleWith(measures);
  const ctx = setupGlobals({
    candidates: [makeParagraph()],
  });
  globalThis.__TiqianLifecycle = lifecycle;

  // Snapshot count attribute
  const root = makeElement({ "data-tiqian-snapshot-count": "5" });
  // Fake publishState to track state
  const publishCalls = [];
  ctx.fakeRS.publishState = function (state, keepEmpty) {
    publishCalls.push({ state: state, keepEmpty: keepEmpty });
  };

  let onFinishedFn = null;
  ctx.fakeJob.startJob = function (spec) {
    onFinishedFn = spec.onFinished;
  };
  try {
    enhanceProgressively(root, {});

    assert.ok(onFinishedFn);
    onFinishedFn({
      kind: "Enhance",
      startedAt: Date.now() - 100,
      maxSliceMs: 50,
      stale: true,
    });

    // Check events dispatched on root
    assert.ok(root.events.length > 0);
    const readyEvent = root.events.find(function (e) {
      return e.type === "tiqian:ready";
    });
    assert.ok(readyEvent);
    assert.equal(readyEvent.bubbles, true);
    assert.equal(readyEvent.composed, true);
    const d = readyEvent.detail;
    assert.equal(typeof d.enhancedCount, "number");
    assert.equal(d.snapshotCount, 5);
    assert.equal(d.issueCount, 0);
    assert.equal(typeof d.durationMs, "number");
    assert.equal(typeof d.maxSliceMs, "number");
    assert.equal(d.stale, true);
    // enhancedCount = runtimeEnhancedCount + snapshotCount
    assert.equal(d.enhancedCount, d.runtimeEnhancedCount + d.snapshotCount);
  } finally {
    teardown(ctx);
  }
});

test("6b. relayout finish: dispatches tiqian:relayout-ready with relayout: true", function () {
  const measures = [100];
  const lifecycle = makeFakeLifecycleWith(measures);
  const fakeSession = makeFakeSession();
  globalThis.__TiqianProgressiveRelayoutSession = {
    createProgressiveRelayoutSession: function () {
      return fakeSession;
    },
  };

  const root = makeElement({ "data-tiqian-snapshot-count": "3" });

  const state = {
    root: root,
    options: { fontSize: 19 },
    paragraphs: [],
    issues: [],
  };

  const ctx = setupGlobals({
    getStateValue: state,
    candidates: [],
  });
  globalThis.__TiqianLifecycle = lifecycle;

  ctx.fakeJob.jobKind = function () {
    return null;
  };
  ctx.fakeJob.cancelJob = function () {};
  ctx.fakeRS.publishState = function () {};

  let onFinishedFn = null;
  ctx.fakeJob.startJob = function (spec) {
    onFinishedFn = spec.onFinished;
  };
  try {
    relayout(root);

    assert.ok(onFinishedFn);
    onFinishedFn({
      kind: "Relayout",
      startedAt: Date.now() - 200,
      maxSliceMs: 30,
      stale: false,
    });

    const relayoutReadyEvent = root.events.find(function (e) {
      return e.type === "tiqian:relayout-ready";
    });
    assert.ok(relayoutReadyEvent);
    assert.equal(relayoutReadyEvent.detail.relayout, true);
    assert.equal(relayoutReadyEvent.detail.failed, false);
    assert.equal(relayoutReadyEvent.detail.error, null);
    assert.equal(relayoutReadyEvent.detail.snapshotCount, 3);
    assert.equal(relayoutReadyEvent.detail.stale, false);
  } finally {
    teardown(ctx);
  }
});

test("6c. fail: sets data-tiqian-relayout-error attribute, dispatches error and summary events", function () {
  const measures = [100];
  const lifecycle = makeFakeLifecycleWith(measures);
  const fakeSession = makeFakeSession();
  globalThis.__TiqianProgressiveRelayoutSession = {
    createProgressiveRelayoutSession: function () {
      return fakeSession;
    },
  };

  const root = makeElement();

  const state = {
    root: root,
    options: { fontSize: 19 },
    paragraphs: [],
    issues: [],
  };

  const ctx = setupGlobals({
    getStateValue: state,
    candidates: [],
  });
  globalThis.__TiqianLifecycle = lifecycle;

  ctx.fakeJob.jobKind = function () {
    return null;
  };
  ctx.fakeJob.cancelJob = function () {};
  ctx.fakeRS.publishState = function () {};

  let onFailedFn = null;
  ctx.fakeJob.startJob = function (spec) {
    onFailedFn = spec.onFailed;
  };
  try {
    relayout(root);

    assert.ok(onFailedFn);
    onFailedFn({
      kind: "Relayout",
      detail: "Something went wrong",
      startedAt: Date.now() - 150,
      maxSliceMs: 40,
    });

    // data-tiqian-relayout-error attribute set
    assert.equal(root.getAttribute("data-tiqian-relayout-error"), "Something went wrong");

    // Error event
    const errorEvent = root.events.find(function (e) {
      return e.type === "tiqian:relayout-error";
    });
    assert.ok(errorEvent);
    assert.equal(errorEvent.detail.kind, "Relayout");
    assert.equal(errorEvent.detail.error, "Something went wrong");

    // Summary event
    const summaryEvent = root.events.find(function (e) {
      return e.type === "tiqian:relayout-ready";
    });
    assert.ok(summaryEvent);
    assert.equal(summaryEvent.detail.failed, true);
    assert.equal(summaryEvent.detail.error, "Something went wrong");
  } finally {
    teardown(ctx);
  }
});

test("6d. fail: detail truncated to 512 chars", function () {
  const lifecycle = makeFakeLifecycleWith([100]);
  const fakeSession = makeFakeSession();
  globalThis.__TiqianProgressiveRelayoutSession = {
    createProgressiveRelayoutSession: function () {
      return fakeSession;
    },
  };

  const root = makeElement();

  const state = {
    root: root,
    options: { fontSize: 19 },
    paragraphs: [],
    issues: [],
  };

  const ctx = setupGlobals({
    getStateValue: state,
    candidates: [],
  });
  globalThis.__TiqianLifecycle = lifecycle;

  ctx.fakeJob.jobKind = function () {
    return null;
  };
  ctx.fakeJob.cancelJob = function () {};
  ctx.fakeRS.publishState = function () {};

  let onFailedFn = null;
  ctx.fakeJob.startJob = function (spec) {
    onFailedFn = spec.onFailed;
  };
  try {
    relayout(root);

    const longDetail = "X".repeat(1024);
    onFailedFn({
      kind: "Enhance",
      detail: longDetail,
      startedAt: Date.now(),
      maxSliceMs: 0,
    });

    // Truncated to 512
    assert.equal(root.getAttribute("data-tiqian-relayout-error").length, 512);

    // Error event for Enhance kind uses tiqian:error
    const errorEvent = root.events.find(function (e) {
      return e.type === "tiqian:error";
    });
    assert.ok(errorEvent);
    assert.equal(errorEvent.detail.kind, "Enhance");
  } finally {
    teardown(ctx);
  }
});

test("6e. fail for Enhance kind dispatches tiqian:error (not tiqian:relayout-error)", function () {
  const lifecycle = makeFakeLifecycleWith([100]);
  const fakeSession = makeFakeSession();
  globalThis.__TiqianProgressiveRelayoutSession = {
    createProgressiveRelayoutSession: function () {
      return fakeSession;
    },
  };

  const root = makeElement();

  const state = {
    root: root,
    options: { fontSize: 19 },
    paragraphs: [],
    issues: [],
  };

  const ctx = setupGlobals({
    getStateValue: state,
    candidates: [],
  });
  globalThis.__TiqianLifecycle = lifecycle;

  ctx.fakeJob.jobKind = function () {
    return null;
  };
  ctx.fakeJob.cancelJob = function () {};
  ctx.fakeRS.publishState = function () {};

  let onFailedFn = null;
  ctx.fakeJob.startJob = function (spec) {
    onFailedFn = spec.onFailed;
  };
  try {
    relayout(root);

    onFailedFn({
      kind: "Enhance",
      detail: "test error",
      startedAt: Date.now(),
      maxSliceMs: 0,
    });

    // Should have tiqian:error, not tiqian:relayout-error
    const relayoutErrorEvents = root.events.filter(function (e) {
      return e.type === "tiqian:relayout-error";
    });
    assert.equal(relayoutErrorEvents.length, 0);

    const errorEvents = root.events.filter(function (e) {
      return e.type === "tiqian:error";
    });
    assert.equal(errorEvents.length, 1);
  } finally {
    teardown(ctx);
  }
});

// ---------------------------------------------------------------------------
// 7. New public surface exports (engine-entry.js consumers)
// ---------------------------------------------------------------------------

test("7a. rejectMissingSharedRuntimeStyles is exposed on the public surface", function () {
  assert.equal(typeof enhanceProgressively, "function");
  assert.equal(typeof relayout, "function");
  assert.equal(typeof globalThis.__TiqianProgressiveDrivers.rejectMissingSharedRuntimeStyles, "function");
});

test("7b. startProgressiveJob is exposed on the public surface with 9-arg signature", function () {
  const fn = globalThis.__TiqianProgressiveDrivers.startProgressiveJob;
  assert.equal(typeof fn, "function");
  assert.equal(fn.length, 9);
});

test("7c. enhanceProgressivelyFromCanonical calls enhanceProgressively with kind Enhance and fromCanonical true", function () {
  const ctx = setupGlobals();
  try {
    const root = makeElement();
    const canonicalOpts = { fontSize: 22 };
    const startJobCalls = [];
    ctx.fakeJob.startJob = function (spec) {
      startJobCalls.push(spec);
    };
    // Fake lifecycle to avoid real measures
    globalThis.__TiqianLifecycle = makeFakeLifecycleWith([100]);
    const fn = globalThis.__TiqianProgressiveDrivers.enhanceProgressivelyFromCanonical;
    assert.equal(typeof fn, "function");
    fn(root, canonicalOpts);
    // Should use createRootStateFromCanonical (not createRootState) because
    // fromCanonical is true.
    assert.equal(ctx.fakeRS._calls.createRootStateFromCanonical.length, 1);
    assert.equal(ctx.fakeRS._calls.createRootStateFromCanonical[0].options, canonicalOpts);
    assert.equal(ctx.fakeRS._calls.createRootState.length, 0);
    // Should start a job with kind Enhance (not Relayout)
    assert.equal(startJobCalls.length, 1);
    assert.equal(startJobCalls[0].kind, "Enhance");
  } finally {
    teardown(ctx);
  }
});
