import assert from "node:assert/strict";
import test from "node:test";

import {
  enhanceProgressively,
  enhanceProgressivelyFromCanonical,
  rejectMissingSharedRuntimeStyles,
  relayout,
  startLayoutJob,
} from "../core/engine/progressive-drivers.js";

// The drivers functions take fake rootState/engine/layoutJobPool deps; the
// relayout-session and process-paragraph deps bundles carry a fake
// detached-fragment backup. The real openRelayoutSession and the real
// processParagraph run against those fakes, so the relayout main path and the
// enhance processItem path are observable through the fake detached-fragment backup ledger. The
// direct prepare step, the lifecycle helpers and sourceParagraphWidth also run
// for real, so the relayout main path needs measurable source elements and the
// getComputedStyle global.

function saveGlobals(names) {
  return names.map((name) => ({
    name,
    own: Object.prototype.hasOwnProperty.call(globalThis, name),
    value: globalThis[name],
  }));
}

function restoreGlobals(entries) {
  for (const { name, own, value } of entries) {
    if (own) globalThis[name] = value;
    else delete globalThis[name];
  }
}

function makeComputedStyle(values = {}) {
  const props = {
    paddingLeft: "0px",
    paddingRight: "0px",
    borderLeftWidth: "0px",
    borderRightWidth: "0px",
    ...values,
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
}

function withEnv(fn, overrides = {}) {
  const saved = saveGlobals(["window", "document", "getComputedStyle", "CustomEvent"]);
  try {
    const values = overrides.computedStyleValues ?? { "--tq-styles-ready": "1" };
    const computed = (el, pseudo) => makeComputedStyle(values);
    globalThis.getComputedStyle = computed;
    globalThis.window = {
      innerHeight: 800,
      getComputedStyle: computed,
    };
    globalThis.document = { documentElement: { clientHeight: 800 } };
    globalThis.CustomEvent = function (type, init) {
      this.type = type;
      this.bubbles = init && init.bubbles;
      this.composed = init && init.composed;
      this.detail = init && init.detail;
    };
    // Tests now use the real prepared-dom renderer directly.
    // Validator injection removed per spec.
    return fn();
  } finally {
    restoreGlobals(saved);
  }
}

// Live paragraph double: doubles as an eligible source element (closest,
// textContent, querySelectorAll), a lowerable DOM (childNodes, style), and a
// measurable element (getBoundingClientRect/getClientRects). The tests mutate
// _rect to simulate viewport distance and width drift.
function makeElement(initialAttributes, options = {}) {
  const attrs = new Map(Object.entries(initialAttributes || {}));
  const setAttributes = [];
  const removedAttributes = [];
  const styleProps = new Map();
  const text = options.text ?? "hello world";
  const rect = { top: 0, bottom: 100, width: options.width ?? 300 };
  return {
    tagName: options.tagName ?? "P",
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
    style: {
      getPropertyValue: (name) => styleProps.get(name) ?? "",
      getPropertyPriority: () => "",
      setProperty: (name, value) => styleProps.set(name, String(value)),
      removeProperty: (name) => styleProps.delete(name),
    },
    closest: () => null,
    querySelectorAll: () => [],
    querySelector: () => null,
    getBoundingClientRect: function () {
      const r = this._rect || rect;
      return { top: r.top, bottom: r.bottom, width: r.width };
    },
    getClientRects: function () {
      return [];
    },
    parentElement: null,
    insertBefore: () => {},
    dispatchEvent: function (event) {
      this.events.push(event);
      return true;
    },
    events: [],
    attributes: attrs,
    setAttributes,
    removedAttributes,
    _rect: rect,
  };
}

function makeParagraph(overrides = {}) {
  overrides = overrides || {};
  const source = overrides.source || makeElement();
  const lowered = overrides.lowered || {
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

function makeState(overrides = {}, root, optionsOverride) {
  return {
    root: root,
    options: optionsOverride ||
      overrides.stateOptions || {
        paragraphSelector: "p",
        fontSize: 19,
      },
    paragraphs: overrides.paragraphs || [],
    issues: overrides.issues || [],
    preparedDomEnabled: true,
    snapshotSession: overrides.snapshotSession || null,
    browserFallback: overrides.browserFallback || null,
    onIssue: overrides.onIssue || function () {},
    onParagraphCommitted: overrides.onParagraphCommitted || function () {},
    onDisableSnapshotPreparedDom: overrides.onDisableSnapshotPreparedDom || function () {},
  };
}

function makeFakeRootState(overrides = {}) {
  const calls = {
    createRootState: [],
    createRootStateFromCanonical: [],
    getState: [],
    setState: [],
    deleteState: [],
    publishState: [],
    processParagraphArgument: [],
    sessionArgument: [],
    prepareArgument: [],
    paragraphCandidates: [],
    strandedSourceParagraphs: [],
  };
  return {
    _calls: calls,
    createRootState: function (root, optionsBag) {
      calls.createRootState.push({ root: root, optionsBag: optionsBag });
      return makeState(overrides, root);
    },
    createRootStateFromCanonical: function (root, options) {
      calls.createRootStateFromCanonical.push({ root: root, options: options });
      return makeState(overrides, root, options);
    },
    getState: function (root) {
      calls.getState.push(root);
      return overrides.getStateValue !== undefined ? overrides.getStateValue : null;
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
    processParagraphArgument: function (state, paragraph) {
      calls.processParagraphArgument.push({ state: state, paragraph: paragraph });
      return {
        paragraph: paragraph,
        state: state,
      };
    },
    sessionArgument: function (state) {
      calls.sessionArgument.push({ state: state });
      return { paragraphs: state.paragraphs, state: state };
    },
    prepareArgument: function (state, paragraph, widthOverride) {
      calls.prepareArgument.push({
        state: state,
        paragraph: paragraph,
        widthOverride: widthOverride,
      });
      return {
        paragraph: paragraph,
        options: state.options || {},
        snapshotSession: state.snapshotSession || null,
        browserFallback: state.browserFallback || null,
        widthOverride: widthOverride,
      };
    },
    paragraphCandidates: function (root, selector) {
      calls.paragraphCandidates.push({ root: root, selector: selector });
      return overrides.candidates || [];
    },
    strandedSourceParagraphs: function (root, state) {
      calls.strandedSourceParagraphs.push({ root: root, state: state });
      return overrides.stranded || [];
    },
  };
}

function makeFakeRawDom() {
  const calls = {
    begin: [],
    take: [],
    commit: [],
    restoreParagraph: [],
    captureLive: [],
    rollback: [],
    stampRendered: [],
    restoreShell: [],
  };
  return {
    _calls: calls,
    begin: function (...args) {
      calls.begin.push(args);
    },
    take: function (el, applied) {
      calls.take.push({ el: el, applied: applied });
    },
    commit: function (el, applied) {
      calls.commit.push({ el: el, applied: applied });
    },
    restoreParagraph: function (el) {
      calls.restoreParagraph.push(el);
    },
    captureLive: function (source, lastMeasure) {
      calls.captureLive.push({ source: source, lastMeasure: lastMeasure });
      return { source: source, lastMeasure: lastMeasure, snapshot: true };
    },
    rollback: function (snapshots) {
      calls.rollback.push(snapshots);
      return [];
    },
    stampRendered: function (el) {
      calls.stampRendered.push(el);
    },
    restoreShell: function (el) {
      calls.restoreShell.push(el);
    },
  };
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

function makeDrivers(overrides = {}) {
  const rawDom = overrides.rawDom || makeFakeRawDom();
  const rootState = overrides.rootState || makeFakeRootState(overrides);
  const layoutJobPool = overrides.layoutJobPool || makeFakeLayoutJobPool(overrides);
  return {
    rootState: rootState,
    layoutJobPool: layoutJobPool,
    rawDom: rawDom,
  };
}

// Collaborator argument list for the driver entry functions in the unit-test
// world: the three runtime-graph products, in the public signature order.
function driverArgs(ctx) {
  return [ctx.rootState, ctx.layoutJobPool, ctx.rawDom];
}

// ---------------------------------------------------------------------------
// 1. enhanceProgressively
// ---------------------------------------------------------------------------

test("1a. cancelJob is called before createRootState", function () {
  withEnv(() => {
    const ctx = makeDrivers();
    const root = makeElement();
    enhanceProgressively(...driverArgs(ctx), root, { fontSize: 20 });

    assert.equal(ctx.layoutJobPool._calls.cancelJob.length, 1);
    assert.equal(ctx.layoutJobPool._calls.cancelJob[0], root);
    assert.equal(ctx.rootState._calls.createRootState.length, 1);
    assert.equal(ctx.rootState._calls.createRootState[0].optionsBag.fontSize, 20);
    // cancelJob happens before createRootState
    assert.ok(ctx.layoutJobPool._calls.cancelJob.length > 0);
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

  withEnv(() => {
    const ctx = makeDrivers({
      candidates: [p1, p2, p3],
    });
    const root = makeElement();
    enhanceProgressively(...driverArgs(ctx), root, {});

    assert.equal(ctx.layoutJobPool._calls.startJob.length, 1);
    const spec = ctx.layoutJobPool._calls.startJob[0];
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

  withEnv(() => {
    const ctx = makeDrivers({
      candidates: [p1.source, p2.source],
    });
    const root = makeElement();
    enhanceProgressively(...driverArgs(ctx), root, {});

    assert.equal(ctx.layoutJobPool._calls.startJob.length, 1);
    const spec = ctx.layoutJobPool._calls.startJob[0];
    assert.equal(spec.kind, "Enhance");
    assert.equal(spec.itemCount, 2);
    // itemTierIndex should be [0, 1] for two elements at distance 0
    assert.deepEqual(spec.itemTierIndex, [0, 1]);
    // paragraphsByDoc should be the source candidates (original order)
    assert.equal(spec.paragraphsByDoc.length, 2);
    assert.equal(spec.paragraphsByDoc[0], p1.source);
    assert.equal(spec.paragraphsByDoc[1], p2.source);
  });
});

test("1d. processItem calls processParagraphArgument and processParagraph for non-stale items", function () {
  const p1 = makeParagraph();
  const p2 = makeParagraph();

  withEnv(() => {
    const ctx = makeDrivers({
      candidates: [p1.source, p2.source],
    });
    const root = makeElement();
    enhanceProgressively(...driverArgs(ctx), root, {});

    const spec = ctx.layoutJobPool._calls.startJob[0];
    assert.ok(spec.processItem);

    // Call processItem for index 0: live measure matches captured => the real
    // processParagraph runs and reaches detached-fragment backup.begin.
    spec.processItem(0);
    assert.equal(ctx.rootState._calls.processParagraphArgument.length, 1);
    assert.equal(ctx.rootState._calls.processParagraphArgument[0].paragraph, p1.source);
    assert.equal(ctx.rawDom._calls.begin.length, 1);
    assert.equal(ctx.rawDom._calls.begin[0][0], p1.source);
  });
});

test("1e. processItem sets stale when measure drifts and does not process", function () {
  const p1 = makeParagraph();
  const p2 = makeParagraph();

  withEnv(() => {
    const ctx = makeDrivers({
      candidates: [p1.source, p2.source],
    });
    const root = makeElement();
    enhanceProgressively(...driverArgs(ctx), root, {});

    const spec = ctx.layoutJobPool._calls.startJob[0];
    const isStaleFn = spec.isStale;
    assert.ok(spec.processItem);

    // Live measure for index 0 drifts from the captured measure: widening the
    // element changes the responsive grid cell, so the item is stale and no
    // paragraph is processed.
    p1.source._rect.width = 600;
    spec.processItem(0);
    assert.equal(ctx.rawDom._calls.begin.length, 0);
    assert.equal(isStaleFn(), true);
  });
});

test("1f. onItemsFinished aggregates stale across all items", function () {
  const p1 = makeParagraph();
  const p2 = makeParagraph();

  withEnv(() => {
    const ctx = makeDrivers({
      candidates: [p1.source, p2.source],
    });
    const root = makeElement();
    enhanceProgressively(...driverArgs(ctx), root, {});

    const spec = ctx.layoutJobPool._calls.startJob[0];
    assert.ok(spec.onItemsFinished);
    // p2's live re-measure drifts from its captured measure, so the finish
    // pass reports the job stale.
    p2.source._rect.width = 600;
    spec.onItemsFinished();
    assert.equal(spec.isStale(), true);
  });
});

test("1g. SharedRuntimeStylesCapabilityGate: --tq-styles-ready != 1 reports MissingSharedRuntimeStyles and does not startJob", function () {
  const reportedIssues = [];
  const p1 = makeParagraph();

  withEnv(() => {
    const ctx = makeDrivers({
      candidates: [p1.source],
      issues: reportedIssues,
    });
    const root = makeElement();
    enhanceProgressively(...driverArgs(ctx), root, {});

    // Should not start a job
    assert.equal(ctx.layoutJobPool._calls.startJob.length, 0);
    // The gate issue is pushed into state.issues (the reportedIssues array)
    // before the lifecycle marker is written.
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
  const runningState = {
    options: { fontSize: 22, paragraphSelector: "p" },
    paragraphs: [],
    issues: [],
  };

  withEnv(() => {
    const ctx = makeDrivers({
      getStateValue: runningState,
      candidates: [makeParagraph().source],
      layoutJobPool: makeFakeLayoutJobPool({ jobKind: () => "Enhance" }),
    });
    const root = makeElement();
    relayout(...driverArgs(ctx), root);

    // Should restart with the running state's canonical options. Kotlin's
    // two-arg overload restarts the interrupted enhance, so the kind stays
    // Enhance and the finish event stays tiqian:ready. Canonical options
    // must go through createRootStateFromCanonical; feeding them to
    // createRootState would re-resolve them through optionsFromJs.
    assert.equal(ctx.layoutJobPool._calls.startJob.length, 1);
    assert.equal(ctx.layoutJobPool._calls.startJob[0].kind, "Enhance");
    assert.equal(ctx.rootState._calls.createRootStateFromCanonical.length, 1);
    assert.equal(ctx.rootState._calls.createRootStateFromCanonical[0].options, runningState.options);
    assert.equal(ctx.rootState._calls.createRootState.length, 0);
  });
});

// ---------------------------------------------------------------------------
// 3. relayout branch 2: no state => cold-start Relayout with bag null
// ---------------------------------------------------------------------------

test("3. relayout branch 2: no state => cold-start Relayout with bag null", function () {
  withEnv(() => {
    const ctx = makeDrivers({
      getStateValue: null,
      candidates: [makeParagraph().source],
    });
    const root = makeElement();
    relayout(...driverArgs(ctx), root);

    assert.equal(ctx.layoutJobPool._calls.startJob.length, 1);
    assert.equal(ctx.layoutJobPool._calls.startJob[0].kind, "Relayout");
    // createRootState was called with bag null
    assert.equal(ctx.rootState._calls.createRootState.length, 1);
    assert.equal(ctx.rootState._calls.createRootState[0].optionsBag, null);
  });
});

// ---------------------------------------------------------------------------
// 4. relayout branch 3: width-dependent issue => enhance path
// ---------------------------------------------------------------------------

test("4. relayout branch 3: InlineCloneDecorationBreakUnsupported issue => enhance path", function () {
  const root = makeElement();

  const stateWithIssue = {
    root: root,
    options: { fontSize: 19 },
    paragraphs: [],
    issues: [{ name: "InlineCloneDecorationBreakUnsupported" }],
  };

  withEnv(() => {
    const ctx = makeDrivers({
      getStateValue: stateWithIssue,
      candidates: [makeParagraph().source],
    });
    relayout(...driverArgs(ctx), root);

    // cancelJob ran twice: branch 3 cancels explicitly, then the restart's
    // engine-less fallback (unit world) cancels again. Both are idempotent;
    // hosted worlds see the same double through engine.destroy.
    assert.equal(ctx.layoutJobPool._calls.cancelJob.length, 2);
    // Then restarts with enhance path using the state's canonical options
    assert.equal(ctx.layoutJobPool._calls.startJob.length, 1);
    assert.equal(ctx.layoutJobPool._calls.startJob[0].kind, "Relayout");
    assert.equal(ctx.rootState._calls.createRootStateFromCanonical.length, 1);
    assert.equal(ctx.rootState._calls.createRootStateFromCanonical[0].options, stateWithIssue.options);
  });
});

// ---------------------------------------------------------------------------
// 5. relayout main path: session, processItem, stale threshold, rollback, finish
// ---------------------------------------------------------------------------

test("5a. relayout main path: sessionArgument creates session, processItem dispatches stranded and rendered", function () {
  const root = makeElement();
  root._rect = { top: 0, bottom: 100, width: 300 };

  const renderedP = makeParagraph();
  const strandedP = makeParagraph();
  const state = {
    root: root,
    options: { fontSize: 19, paragraphSelector: "p" },
    paragraphs: [renderedP],
    issues: [],
    ffi: null,
    snapshotSession: null,
    browserFallback: null,
    onIssue: function () {},
    onParagraphCommitted: function () {},
    onDisableSnapshotPreparedDom: function () {},
  };

  withEnv(() => {
    const ctx = makeDrivers({
      getStateValue: state,
      stranded: [strandedP.source],
      candidates: [],

      layoutJobPool: makeFakeLayoutJobPool(),
    });
    relayout(...driverArgs(ctx), root);

    assert.equal(ctx.layoutJobPool._calls.startJob.length, 1);
    const spec = ctx.layoutJobPool._calls.startJob[0];
    assert.equal(spec.kind, "Relayout");
    // count = rendered(1) + stranded(1) = 2
    assert.equal(spec.itemCount, 2);

    // Process rendered item (mixIndex 0): the real prepare runs (returns
    // PreparedDomBridgeUnavailable without a renderer), the real session
    // captures a live detached-fragment backup snapshot for the unsupported verdict.
    spec.processItem(0);
    assert.equal(ctx.rawDom._calls.captureLive.length, 1);
    assert.equal(ctx.rawDom._calls.captureLive[0].source, renderedP.source);

    // Process stranded item (mixIndex 1): the real processParagraph runs and
    // reaches detached-fragment backup.begin.
    spec.processItem(1);
    assert.equal(ctx.rawDom._calls.begin.length, 1);
    assert.equal(ctx.rawDom._calls.begin[0][0], strandedP.source);
  });
});

test("5b. relayout main path: prepareArgument includes widths", function () {
  const root = makeElement();

  const renderedP = makeParagraph();
  renderedP.source._rect.width = 250;
  const state = {
    root: root,
    options: { fontSize: 19 },
    paragraphs: [renderedP],
    issues: [],
    ffi: null,
    snapshotSession: null,
    browserFallback: null,
  };

  withEnv(() => {
    const ctx = makeDrivers({
      getStateValue: state,
      candidates: [],

      layoutJobPool: makeFakeLayoutJobPool(),
    });

    const prepareArgCalls = [];
    const origPrepareArg = ctx.rootState.prepareArgument;
    ctx.rootState.prepareArgument = function (st, paragraph, widthOverride) {
      prepareArgCalls.push({ paragraph: paragraph, widthOverride: widthOverride });
      return origPrepareArg(st, paragraph, widthOverride);
    };

    relayout(...driverArgs(ctx), root);

    const spec = ctx.layoutJobPool._calls.startJob[0];
    spec.processItem(0);

    assert.equal(prepareArgCalls.length, 1);
    assert.equal(prepareArgCalls[0].paragraph, renderedP);
    // width should come from the measured source element
    assert.equal(prepareArgCalls[0].widthOverride, 250);
  });
});

test("5c. relayout main path: stale when root width drifts >= 0.5", function () {
  const root = makeElement();
  root._rect = { top: 0, bottom: 100, width: 300 };

  const state = {
    root: root,
    options: { fontSize: 19 },
    paragraphs: [],
    issues: [],
  };

  withEnv(() => {
    const ctx = makeDrivers({
      getStateValue: state,
      candidates: [],

      layoutJobPool: makeFakeLayoutJobPool(),
    });
    relayout(...driverArgs(ctx), root);

    const spec = ctx.layoutJobPool._calls.startJob[0];
    const staleFn = spec.isStale;

    // Initially root width matches (300) => not stale from width drift
    // But session.stale is also false
    assert.equal(staleFn(), false);

    // Simulate root width drift
    root._rect.width = 301;
    assert.equal(staleFn(), true);
  });
});

test("5d. relayout main path: onFailure calls rollback", function () {
  const root = makeElement();

  const renderedP = makeParagraph();
  const state = {
    root: root,
    options: { fontSize: 19 },
    paragraphs: [renderedP],
    issues: [],
    ffi: null,
    snapshotSession: null,
    browserFallback: null,
  };

  withEnv(() => {
    const ctx = makeDrivers({
      getStateValue: state,
      candidates: [],

      layoutJobPool: makeFakeLayoutJobPool(),
    });
    relayout(...driverArgs(ctx), root);

    const spec = ctx.layoutJobPool._calls.startJob[0];
    assert.ok(spec.onFailure);
    spec.onFailure();
    // The real session rollback hands the captured snapshots to detached-fragment backup.
    assert.equal(ctx.rawDom._calls.rollback.length, 1);
  });
});

test("5e. relayout main path: onItemsFinished calls finish which ejects unsupported paragraphs", function () {
  const root = makeElement();

  const renderedP = makeParagraph();
  const state = {
    root: root,
    options: { fontSize: 19 },
    paragraphs: [renderedP],
    issues: [],
    ffi: null,
    snapshotSession: null,
    browserFallback: null,
  };

  withEnv(() => {
    const ctx = makeDrivers({
      getStateValue: state,
      candidates: [],

      layoutJobPool: makeFakeLayoutJobPool(),
    });
    relayout(...driverArgs(ctx), root);

    const spec = ctx.layoutJobPool._calls.startJob[0];
    assert.ok(spec.onItemsFinished);
    // The rendered item prepares to PreparedDomBridgeUnavailable (no
    // renderer), so the real session marks it unsupported; finish ejects it
    // from state.paragraphs and reports the issue.
    spec.processItem(0);
    spec.onItemsFinished();
    assert.equal(state.paragraphs.length, 0);
    assert.equal(state.issues.length, 1);
    assert.equal(state.issues[0].name, "PreparedDomBridgeUnavailable");
  });
});

// ---------------------------------------------------------------------------
// 6. finishing reporting layer
// ---------------------------------------------------------------------------

test("6a. finish: dispatches tiqian:ready with correct detail fields", function () {
  // Snapshot count attribute
  const root = makeElement({ "data-tiqian-snapshot-count": "5" });

  withEnv(() => {
    const ctx = makeDrivers({
      candidates: [makeParagraph().source],
    });
    const publishCalls = [];
    ctx.rootState.publishState = function (state, keepEmpty) {
      publishCalls.push({ state: state, keepEmpty: keepEmpty });
    };

    enhanceProgressively(...driverArgs(ctx), root, {});

    const spec = ctx.layoutJobPool._calls.startJob[0];
    assert.ok(spec.onFinished);
    spec.onFinished({
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
  });
});

test("6b. relayout finish: dispatches tiqian:relayout-ready with relayout: true", function () {
  const root = makeElement({ "data-tiqian-snapshot-count": "3" });

  const state = {
    root: root,
    options: { fontSize: 19 },
    paragraphs: [],
    issues: [],
  };

  withEnv(() => {
    const ctx = makeDrivers({
      getStateValue: state,
      candidates: [],

      layoutJobPool: makeFakeLayoutJobPool(),
    });
    ctx.rootState.publishState = function () {};

    relayout(...driverArgs(ctx), root);

    const spec = ctx.layoutJobPool._calls.startJob[0];
    assert.ok(spec.onFinished);
    spec.onFinished({
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
  });
});

test("6c. fail: sets data-tiqian-relayout-error attribute, dispatches error and summary events", function () {
  const root = makeElement();

  const state = {
    root: root,
    options: { fontSize: 19 },
    paragraphs: [],
    issues: [],
  };

  withEnv(() => {
    const ctx = makeDrivers({
      getStateValue: state,
      candidates: [],

      layoutJobPool: makeFakeLayoutJobPool(),
    });
    ctx.rootState.publishState = function () {};

    relayout(...driverArgs(ctx), root);

    const spec = ctx.layoutJobPool._calls.startJob[0];
    assert.ok(spec.onFailed);
    spec.onFailed({
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
  });
});

test("6d. fail: detail truncated to 512 chars", function () {
  const root = makeElement();

  const state = {
    root: root,
    options: { fontSize: 19 },
    paragraphs: [],
    issues: [],
  };

  withEnv(() => {
    const ctx = makeDrivers({
      getStateValue: state,
      candidates: [],

      layoutJobPool: makeFakeLayoutJobPool(),
    });
    ctx.rootState.publishState = function () {};

    relayout(...driverArgs(ctx), root);

    const spec = ctx.layoutJobPool._calls.startJob[0];
    const longDetail = "X".repeat(1024);
    spec.onFailed({
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
  });
});

test("6e. fail for Enhance kind dispatches tiqian:error (not tiqian:relayout-error)", function () {
  const root = makeElement();

  const state = {
    root: root,
    options: { fontSize: 19 },
    paragraphs: [],
    issues: [],
  };

  withEnv(() => {
    const ctx = makeDrivers({
      getStateValue: state,
      candidates: [],

      layoutJobPool: makeFakeLayoutJobPool(),
    });
    ctx.rootState.publishState = function () {};

    relayout(...driverArgs(ctx), root);

    const spec = ctx.layoutJobPool._calls.startJob[0];
    spec.onFailed({
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

test("7b. startLayoutJob has the 11-arg rootState+layoutJobPool-first signature", function () {
  assert.equal(typeof startLayoutJob, "function");
  assert.equal(startLayoutJob.length, 11);
});

test("7c. enhanceProgressivelyFromCanonical calls enhanceProgressively with kind Enhance and fromCanonical true", function () {
  withEnv(() => {
    const ctx = makeDrivers({
      candidates: [],
    });
    const root = makeElement();
    const canonicalOpts = { fontSize: 22 };
    enhanceProgressivelyFromCanonical(...driverArgs(ctx), root, canonicalOpts);
    // Should use createRootStateFromCanonical (not createRootState) because
    // fromCanonical is true.
    assert.equal(ctx.rootState._calls.createRootStateFromCanonical.length, 1);
    assert.equal(ctx.rootState._calls.createRootStateFromCanonical[0].options, canonicalOpts);
    assert.equal(ctx.rootState._calls.createRootState.length, 0);
    // Should start a job with kind Enhance (not Relayout)
    assert.equal(ctx.layoutJobPool._calls.startJob.length, 1);
    assert.equal(ctx.layoutJobPool._calls.startJob[0].kind, "Enhance");
  });
});