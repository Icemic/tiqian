import assert from "node:assert/strict";
import test from "node:test";

import { createEngineEntry } from "./core/engine/engine-entry.js";
import { optionsFromJs } from "./core/engine/lifecycle.js";
import { installFixtureFontBackend } from "./test-support/fixture-font-backend.mjs";

const ENV_GLOBALS = ["window", "document", "getComputedStyle", "__TiqianPreparedDomRenderer", "__TiqianFontBackend", "__TiqianPreparedDomValidator"];

// The engine entry runs the real process-paragraph, content-reconcile and
// progressive-drivers functions against fake ledgers (detached-fragment backup, root-state,
// layout-job-pool, copy-installer). The process-paragraph and commit helpers
// are direct imports, so a ready commit path only proceeds through an
// installed prepared-DOM bridge; without one the prepare step returns
// PreparedDomBridgeUnavailable first.

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
    if (overrides.preparedDom !== false) {
      globalThis.__TiqianPreparedDomRenderer = {
        render: function () {},
        release: function () { return true; },
        releaseRoot: function () { return true; },
        schema: 1,
        layoutRevision: "tiqian-layout-v2",
      };
    }
    if (overrides.validator !== undefined) {
      globalThis.__TiqianPreparedDomValidator = { issue: overrides.validator };
    }
    return fn();
  } finally {
    if (backend) backend.uninstall();
    restoreEnv(saved);
  }
}

function makeStateWithCallbacks(overrides = {}) {
  const state = {
    root: overrides.root ?? null,
    options: overrides.options ?? { paragraphSelector: "p", fontSize: 19 },
    paragraphs: overrides.paragraphs ?? [],
    issues: overrides.issues ?? [],
    preparedDomEnabled: overrides.preparedDomEnabled ?? true,
    exactSession: overrides.exactSession ?? { sessionId: "session-1" },
    browserFallback: overrides.browserFallback ?? null,
  };
  state.onIssue = overrides.onIssue ?? function (issue) { state.issues.push(issue); };
  state.onParagraphCommitted = overrides.onParagraphCommitted ?? function (item) { state.paragraphs.push(item); };
  state.onDisableExactPreparedDom = overrides.onDisableExactPreparedDom ?? function () {};
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

function makeFakeCopyInstaller() {
  const calls = { install: [] };
  return {
    _calls: calls,
    install: function (doc) { calls.install.push(doc); },
  };
}

function makeEngine(opts) {
  opts = opts || {};
  const rs = opts.rs || makeFakeRootState(opts.rsOpts || {});
  const job = opts.job || makeFakeJob();
  const rawDom = opts.rawDom || makeFakeRawDom();
  const copyInstaller = opts.copyInstaller || makeFakeCopyInstaller();
  const entry = createEngineEntry(rawDom, copyInstaller, rs, job);
  return {
    engine: entry.engine,
    workers: entry.workers,
    rs: rs,
    job: job,
    rawDom: rawDom,
    copyInstaller: copyInstaller,
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
    const ctx = makeEngine({ rs: rs });
    const root = makeElement();
    const result = ctx.engine.enhance(root, { fontSize: 20 });
    assert.equal(rs._calls.createRootState.length, 1);
    assert.equal(rs._calls.createRootState[0].bag.fontSize, 20);
    // The real processParagraph ran once per candidate, observable on the
    // detached-fragment backup ledger.
    assert.equal(ctx.rawDom._calls.begin.length, 2);
    assert.equal(ctx.rawDom._calls.begin[0][0], c1);
    assert.equal(ctx.rawDom._calls.begin[1][0], c2);
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
    const ctx = makeEngine({ rs: rs });
    const result = ctx.engine.enhance(makeElement(), {});
    assert.equal(result, 0);
    assert.equal(ctx.rawDom._calls.begin.length, 0);
  }, { computedStyleValues: { "--tq-styles-ready": "0" } });
});

// ---------------------------------------------------------------------------
// 3. enhance destroy-first: destroy runs before createRootState
// ---------------------------------------------------------------------------

test("3. enhance: destroy runs before createRootState (call order)", function () {
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
    const ctx = makeEngine({ rs: rs, job: fakeJob });
    ctx.engine.enhance(makeElement(), {});
    assert.deepEqual(callOrder, ["destroy", "createRootState"]);
  });
});

// ---------------------------------------------------------------------------
// 4. enhanceAll: scans tiqian-prose and [data-tiqian-root], sums counts
// ---------------------------------------------------------------------------

test("4. enhanceAll: scans tiqian-prose and [data-tiqian-root] roots, sums counts", function () {
  const el1 = makeElement({ "data-tiqian-root": "" });
  const el2 = makeElement();
  el2.tagName = "TIQIAN-PROSE";
  const fakeRoots = [el1, el2];
  fakeRoots.item = function (i) { return fakeRoots[i]; };
  fakeRoots.length = 2;
  const doc = { querySelectorAll: function () { return fakeRoots; } };

  let callCount = 0;
  const rs = makeFakeRootState({
    candidates: [],
    state: makeStateWithCallbacks({ root: null }),
  });
  const origCreate = rs.createRootState;
  rs.createRootState = function (root, bag) {
    callCount += 1;
    return origCreate(root, bag);
  };
  withEnv(() => {
    const ctx = makeEngine({ rs: rs, document: doc });
    const result = ctx.engine.enhanceAll({});
    assert.equal(callCount, 2);
    assert.equal(result, 0);
  }, { document: doc });
});

// ---------------------------------------------------------------------------
// 5. enhanceProgressively delegates to the drivers entry
// ---------------------------------------------------------------------------

test("5. enhanceProgressively delegates to the drivers entry, which owns the copy handler and destroy", function () {
  const job = makeFakeJob();
  const copyInstaller = makeFakeCopyInstaller();
  withEnv(() => {
    const ctx = makeEngine({
      job: job,
      copyInstaller: copyInstaller,
      rsOpts: {
        state: makeStateWithCallbacks({ root: null }),
        candidates: [],
      },
    });
    const root = makeElement();
    const bag = { fontSize: 20 };
    ctx.engine.enhanceProgressively(root, bag);
    // The real drivers entry installs the copy handler and cancels the job
    // before rebuilding state.
    assert.equal(copyInstaller._calls.install.length, 1);
    assert.equal(job._calls.cancelJob.length, 1);
    assert.equal(job._calls.cancelJob[0], root);
    assert.equal(ctx.rs._calls.createRootState.length, 1);
    assert.equal(ctx.rs._calls.createRootState[0].bag.fontSize, 20);
    // The real startLayoutJob starts one Enhance job.
    assert.equal(job._calls.startJob.length, 1);
    assert.equal(job._calls.startJob[0].kind, "Enhance");
  });
});

// ---------------------------------------------------------------------------
// 6. destroy: full steps -- restoreParagraph, clearIssue, releaseRoot,
//    snapshot-count branches, attribute cleanup
// ---------------------------------------------------------------------------

test("6. destroy: restores paragraphs, clears issues, releases styles, sets/removes snapshot attrs, cleans 3 attrs", function () {
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
    const ctx = makeEngine({ rs: rs, rawDom: rawDom });
    const root = makeElement({ "data-tiqian-snapshot-count": "5", "data-tiqian-issue-count": "2", "data-tiqian-relayout-error": "err", "data-tiqian-exact-layout-fallback": "fb" });
    ctx.engine.destroy(root);
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
    assert.equal(root.getAttribute("data-tiqian-exact-layout-fallback"), null);
  });
});

// ---------------------------------------------------------------------------
// 7. destroy no state: still cancelJob + attribute cleanup, no throw
// ---------------------------------------------------------------------------

test("7. destroy: no state => still cancelJob + attribute cleanup, no throw", function () {
  const rawDom = makeFakeRawDom();
  const rs = makeFakeRootState({ getStateValue: null });
  withEnv(() => {
    const ctx = makeEngine({ rs: rs, rawDom: rawDom });
    const root = makeElement({ "data-tiqian-relayout-error": "err" });
    ctx.engine.destroy(root);
    assert.equal(rawDom._calls.restoreParagraph.length, 0);
    assert.equal(root.getAttribute("data-tiqian-relayout-error"), null);
    assert.equal(root.getAttribute("data-tiqian-enhanced"), null);
  });
});

// ---------------------------------------------------------------------------
// 8. detach: minimal surface -- cancelJob + releaseRoot, no state touch
// ---------------------------------------------------------------------------

test("8. detach: cancelJob + releaseRoot only, does not touch paragraphs/issues/state", function () {
  const rs = makeFakeRootState();
  withEnv(() => {
    const ctx = makeEngine({ rs: rs });
    const root = makeElement();
    ctx.engine.detach(root);
    assert.equal(rs._calls.getState.length, 0);
    assert.equal(rs._calls.deleteState.length, 0);
    assert.equal(ctx.job._calls.cancelJob.length, 1);
    assert.equal(ctx.job._calls.cancelJob[0], root);
  });
});

// ---------------------------------------------------------------------------
// 9. refresh progressive: no state => no-op; with state => destroy + canonical
// ---------------------------------------------------------------------------

test("9. refresh: no state is no-op; with state progressively => enhanceProgressivelyFromCanonical with state.options", function () {
  const rsNoState = makeFakeRootState({ getStateValue: null });
  withEnv(() => {
    const ctx = makeEngine({ rs: rsNoState });
    ctx.engine.refresh(makeElement(), true);
    assert.equal(rsNoState._calls.deleteState.length, 0);
  });

  const state = makeStateWithCallbacks({ root: null, options: { fontSize: 22 } });
  const fakeJob = makeFakeJob();
  const rs = makeFakeRootState({ getStateValue: state });
  withEnv(() => {
    const ctx = makeEngine({ rs: rs, job: fakeJob });
    ctx.engine.refresh(makeElement(), true);
    // The real drivers canonical entry re-enters through
    // createRootStateFromCanonical with state.options and starts an Enhance job.
    assert.equal(rs._calls.createRootStateFromCanonical.length, 1);
    assert.equal(rs._calls.createRootStateFromCanonical[0].options, state.options);
    assert.equal(fakeJob._calls.startJob.length, 1);
    assert.equal(fakeJob._calls.startJob[0].kind, "Enhance");
  });
});

// ---------------------------------------------------------------------------
// 10. refresh synchronous: calls enhance(root, state.options, true) -- canonical
//     re-entry path; optionsFromJs not called
// ---------------------------------------------------------------------------

test("10. refresh synchronous: enhance canonical re-entry (fromCanonical=true), optionsFromJs not called", function () {
  const state = makeStateWithCallbacks({ root: null, options: { fontSize: 19 } });
  const rs = makeFakeRootState({
    getStateValue: state,
    canonicalState: makeStateWithCallbacks({ root: null, options: state.options }),
    candidates: [],
  });
  withEnv(() => {
    const ctx = makeEngine({ rs: rs });
    // optionsFromJs is only exercised through engine.workerLayoutRequest; a
    // synchronous refresh re-enters enhance with the canonical options, so no
    // options bag is decoded.
    ctx.engine.refresh(makeElement(), false);
    assert.equal(rs._calls.createRootStateFromCanonical.length, 1);
    assert.equal(rs._calls.createRootStateFromCanonical[0].options, state.options);
    assert.equal(rs._calls.createRootState.length, 0);
  });
});

// ---------------------------------------------------------------------------
// 11. cancelLayoutWork: delegates to PJ.cancelJob
// ---------------------------------------------------------------------------

test("11. cancelLayoutWork: delegates to ProgressiveJob.cancelJob", function () {
  const fakeJob = makeFakeJob();
  withEnv(() => {
    const ctx = makeEngine({ job: fakeJob });
    const root = makeElement();
    ctx.engine.cancelLayoutWork(root);
    assert.equal(fakeJob._calls.cancelJob.length, 1);
    assert.equal(fakeJob._calls.cancelJob[0], root);
  });
});

// ---------------------------------------------------------------------------
// 12. probeContentDrift: no state => unknown JSON; with state => sources to
//     the real probe, which reads the fake detached-fragment backup ledger
// ---------------------------------------------------------------------------

test("12. probeContentDrift: no state returns unknown JSON; with state passes sources to the real probe", function () {
  const rsNoState = makeFakeRootState({ getStateValue: null });
  withEnv(() => {
    const ctx = makeEngine({ rs: rsNoState });
    const result = ctx.engine.probeContentDrift(makeElement());
    assert.equal(result, '{"unknown":1,"drifted":0,"dead":0,"rawDom":0}');
  });

  const src1 = makeElement();
  const src2 = makeElement();
  const state = { root: null, options: {}, paragraphs: [{ source: src1 }, { source: src2 }], issues: [] };
  const rawDom = makeFakeRawDom();
  const rs = makeFakeRootState({ getStateValue: state });
  withEnv(() => {
    const ctx = makeEngine({ rs: rs, rawDom: rawDom });
    const result2 = ctx.engine.probeContentDrift(makeElement());
    // The real probe classified both matching sources through the detached-fragment backup
    // ledger, so nothing drifted, died or fell out of detached-fragment backup.
    assert.deepEqual(rawDom._calls.renderedMatches, [src1, src2]);
    assert.deepEqual(rawDom._calls.rawDomMatches, [src1, src2]);
    assert.equal(result2, '{"unknown":0,"drifted":0,"dead":0,"rawDom":0}');
  });
});

// ---------------------------------------------------------------------------
// 13. reconcileContent: no state => idle JSON; with state + idle => returns
//     json, no job; with work verdict => actions per category + job
// ---------------------------------------------------------------------------

test("13a. reconcileContent: no state returns idle JSON", function () {
  const rs = makeFakeRootState({ getStateValue: null });
  withEnv(() => {
    const ctx = makeEngine({ rs: rs });
    const result = ctx.engine.reconcileContent(makeElement(), []);
    assert.equal(result, '{"outcome":"idle","drifted":0,"rawDom":0,"tainted":0,"stranded":0,"dead":0}');
  });
});

test("13b. reconcileContent: state + idle verdict => returns json, no startLayoutJob", function () {
  const state = { root: null, options: {}, paragraphs: [{ source: makeElement() }], issues: [] };
  withEnv(() => {
    const ctx = makeEngine({ rs: makeFakeRootState({ getStateValue: state, candidates: [] }) });
    const result = ctx.engine.reconcileContent(makeElement(), []);
    assert.equal(result, '{"outcome":"idle","drifted":0,"rawDom":0,"tainted":0,"stranded":0,"dead":0}');
    assert.equal(ctx.job._calls.startJob.length, 0);
  });
});

test("13c. reconcileContent: work verdict with drifted/rawDom/tainted/stranded + DeadTrackedParagraphDrop", function () {
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
  withEnv(() => {
    const ctx = makeEngine({
      rs: makeFakeRootState({ getStateValue: state, candidates: [], stranded: [strandedEl] }),
      rawDom: rawDom,
    });
    const result = ctx.engine.reconcileContent(root, [taintedEl]);
    // DeadTrackedParagraphDrop: deadEl removed from state.paragraphs.
    assert.equal(state.paragraphs.length, 3);
    assert.equal(state.paragraphs[0].source, driftedEl);
    assert.equal(state.paragraphs[1].source, rawDomEl);
    assert.equal(state.paragraphs[2].source, taintedEl);
    // The real classifyReconcile produced the expected verdict.
    assert.equal(result, '{"outcome":"work","drifted":1,"rawDom":1,"tainted":1,"stranded":1,"dead":1}');
    // startLayoutJob called with kind Relayout and 4 actions.
    assert.equal(ctx.job._calls.startJob.length, 1);
    const call = ctx.job._calls.startJob[0];
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

test("13d. reconcileContent: itemTierIndex sorted by (distance, index), stale closure detects width drift >= 0.5", function () {
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
  withEnv(() => {
    const ctx = makeEngine({
      rs: makeFakeRootState({ getStateValue: state, candidates: [] }),
      rawDom: rawDom,
    });
    ctx.engine.reconcileContent(root, []);
    assert.equal(ctx.job._calls.startJob.length, 1);
    const call = ctx.job._calls.startJob[0];
    // el2 visible (distance 0) first, then el1 above viewport (distance 100).
    assert.deepEqual(call.itemTierIndex, [1, 0]);
    // stale closure: root width matches initially.
    assert.equal(call.isStale(), false);
    // After root width drift of 1.0.
    root._rect.width = 301;
    assert.equal(call.isStale(), true);
  });
});

// ---------------------------------------------------------------------------
// 14. workerLayoutRequest: forwards to workerLayoutRequestForRoot with
//     optionsFromJs pre-processing
// ---------------------------------------------------------------------------

test("14. workerLayoutRequest: forwards to workerLayoutRequestForRoot, options pre-processed by optionsFromJs", function () {
  withEnv(() => {
    const ctx = makeEngine();
    const root = makeElement();
    const para = makeElement();
    // Without a conforming exact font session in the bag, optionsFromJs yields
    // a null session and workerLayoutRequestForRoot returns null.
    assert.equal(ctx.engine.workerLayoutRequest(root, para, {}), null);
    // With a conforming session in the bag, the request builds: the bag's
    // exactFontSession reached the real workerLayoutRequestForRoot through
    // optionsFromJs, which is the pre-processing this method owns.
    const bag = { exactFontSession: { status: "conforming", sessionId: "s1" } };
    const result = ctx.engine.workerLayoutRequest(root, para, bag);
    assert.notEqual(result, null);
    const parsed = JSON.parse(result);
    const expected = optionsFromJs(bag);
    assert.equal(parsed.firstLineIndentIc, expected.firstLineIndentIc);
    assert.equal(parsed.sourceTag, "p");
  });
});

// ---------------------------------------------------------------------------
// 15. workers panel: 9 methods forward to ProgressiveJob
// ---------------------------------------------------------------------------

test("15. workers: 9 methods forward to ProgressiveJob", function () {
  const fakeJob = makeFakeJob();
  withEnv(() => {
    const ctx = makeEngine({ job: fakeJob });
    const root = makeElement();
    assert.equal(ctx.workers.workerAttach(root), "attached-" + root);
    assert.equal(ctx.workers.workerDetach(root), "detached-" + root);
    assert.equal(ctx.workers.workerHasJob(root), "hasJob-" + root);
    assert.equal(ctx.workers.workerJobGeneration(root), 42);
    assert.equal(ctx.workers.workerRunSlice({}, 0), "ran");
    assert.equal(ctx.workers.workerPendingInTier(root, 1), 7);
    assert.equal(ctx.workers.workerParagraphCount(root), 3);
    assert.equal(ctx.workers.workerParagraphAt(root, 0), "p-0");
    assert.equal(ctx.workers.workerSetParagraphTier(root, 0, 2), "set");
  });
});