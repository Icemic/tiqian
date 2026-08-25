import assert from "node:assert/strict";
import test from "node:test";

import { openRelayoutSession } from "./core/engine/relayout-session.js";
import {
  preparedCjkStrongSemanticsJson,
  preparedInlineObjectMetaJson,
  preparedSemanticReplayJson,
} from "./core/engine/prepared-metadata.js";

// The session factory takes fake custody/commit deps; the lifecycle helpers
// and the metadata JSON builders run for real.

function makeElement(initialAttributes = {}) {
  const attributes = new Map(Object.entries(initialAttributes));
  return {
    tagName: "P",
    getAttribute: (name) => attributes.get(name) ?? null,
    setAttribute: (name, value) => {
      attributes.set(name, String(value));
    },
    removeAttribute: (name) => {
      attributes.delete(name);
    },
    attributes,
  };
}

function makeParagraph(overrides = {}) {
  const source = overrides.source ?? makeElement();
  const lowered = {
    text: "hello world",
    textStyle: {
      fontFamilies: ["Noto Serif CJK SC"],
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
    ...(overrides.lowered ?? {}),
  };
  return {
    source,
    lowered,
    lastMeasure: overrides.lastMeasure ?? null,
  };
}

function makeState(overrides = {}) {
  const paragraphs = overrides.paragraphs ?? [];
  const issues = overrides.issues ?? [];
  return {
    ffi: overrides.ffi ?? {
      classifyFontRole: () => "Cjk",
      firstDivergentInlineShapingProperty: () => null,
      unsupportedInlineShapingProperties: () => [],
    },
    options: overrides.options !== undefined ? overrides.options : { fontSize: 19 },
    preparedDomEnabled: overrides.preparedDomEnabled ?? true,
    exactSession: overrides.exactSession ?? { sessionId: "session-1" },
    browserFallback: overrides.browserFallback ?? null,
    onIssue: overrides.onIssue ?? ((issue) => {}),
    onParagraphCommitted: overrides.onParagraphCommitted ?? ((item) => {}),
    onDisableExactPreparedDom: overrides.onDisableExactPreparedDom ?? ((detail) => {}),
    paragraphs,
    issues,
  };
}

function makeSession(deps) {
  return {
    create: (argument) => openRelayoutSession(deps, argument),
  };
}

test("1. unchanged verdict: no custody call, no state change", () => {
  const captureLiveCalls = [];
  const restoreParagraphCalls = [];
  const custody = {
    captureLive: (...args) => {
      captureLiveCalls.push(args);
      return { snap: true };
    },
    restoreParagraph: (source) => {
      restoreParagraphCalls.push(source);
    },
  };
  const commitPreparedParagraph = {
    commitPreparedParagraph: () => ({ kind: "success", measure: 250 }),
  };
  const session = makeSession({ custody, commitPreparedParagraph });

  const p1 = makeParagraph({ lastMeasure: 100 });
  const state = makeState({ paragraphs: [p1] });
  const active = session.create({ paragraphs: [p1], state });

  active.processItem(0, { kind: "unchanged" });
  active.finish();

  assert.equal(captureLiveCalls.length, 0);
  assert.equal(restoreParagraphCalls.length, 0);
  assert.equal(state.paragraphs.length, 1);
  assert.equal(state.paragraphs[0], p1);
  assert.equal(state.issues.length, 0);
  assert.equal(p1.lastMeasure, 100);
});

test("2. unsupported verdict: captureLive + restoreParagraph called, finish() removes the paragraph from state.paragraphs, pushes the issue, reports it", () => {
  const captureLiveCalls = [];
  const restoreParagraphCalls = [];
  const custody = {
    captureLive: (source, lastMeasure) => {
      captureLiveCalls.push({ source, lastMeasure });
      return { source, snapshot: true };
    },
    restoreParagraph: (source) => {
      restoreParagraphCalls.push(source);
    },
  };
  const session = makeSession({ custody, commitPreparedParagraph: {} });

  const p1 = makeParagraph({ lastMeasure: 120 });
  const state = makeState({ paragraphs: [p1] });
  const active = session.create({ paragraphs: [p1], state });

  const unsupportedVerdict = {
    kind: "unsupported",
    name: "UnsupportedStyle",
    detail: "font size too large",
  };

  active.processItem(0, unsupportedVerdict);

  assert.equal(captureLiveCalls.length, 1);
  assert.equal(captureLiveCalls[0].source, p1.source);
  assert.equal(captureLiveCalls[0].lastMeasure, 120);
  assert.equal(restoreParagraphCalls.length, 1);
  assert.equal(restoreParagraphCalls[0], p1.source);

  active.finish();

  assert.equal(state.paragraphs.length, 0);
  assert.equal(state.issues.length, 1);
  const issue = state.issues[0];
  assert.equal(issue.name, "UnsupportedStyle");
  assert.equal(issue.detail, "font size too large");
  assert.equal(issue.element, p1.source);
  assert.equal(issue.reportToConsole, true);
  // The lifecycle marker was written onto the source element.
  assert.equal(p1.source.getAttribute("data-tiqian-capability-issue"), "UnsupportedStyle");
  assert.equal(p1.source.getAttribute("data-tiqian-capability-detail"), "font size too large");
});

test("3. ready + commit success: lastMeasure set from the commit measure, item stays in state.paragraphs, no restoreParagraph", () => {
  const captureLiveCalls = [];
  const restoreParagraphCalls = [];
  const custody = {
    captureLive: (...args) => {
      captureLiveCalls.push(args);
      return { snap: true };
    },
    restoreParagraph: (source) => {
      restoreParagraphCalls.push(source);
    },
  };
  const commitPreparedParagraph = {
    commitPreparedParagraph: () => ({
      kind: "success",
      measure: 250,
    }),
  };
  const session = makeSession({ custody, commitPreparedParagraph });

  const p1 = makeParagraph({ lastMeasure: 100 });
  const state = makeState({ paragraphs: [p1] });
  const active = session.create({ paragraphs: [p1], state });

  active.processItem(0, { kind: "ready", planJson: "{}" });

  assert.equal(captureLiveCalls.length, 1);
  assert.equal(restoreParagraphCalls.length, 0);
  assert.equal(p1.lastMeasure, 250);

  active.finish();

  assert.equal(p1.lastMeasure, 250);
  assert.equal(state.paragraphs.length, 1);
  assert.equal(state.paragraphs[0], p1);
  assert.equal(state.issues.length, 0);
});

test("4. ready + commit unsupported: restoreParagraph called, finish() removes and reports", () => {
  const restoreParagraphCalls = [];
  const custody = {
    captureLive: () => ({ snap: true }),
    restoreParagraph: (source) => {
      restoreParagraphCalls.push(source);
    },
  };
  const commitPreparedParagraph = {
    commitPreparedParagraph: () => ({
      kind: "unsupported",
      name: "PreparedDomRejection",
      detail: "DOM mismatch",
    }),
  };
  const session = makeSession({ custody, commitPreparedParagraph });

  const p1 = makeParagraph({ lastMeasure: 100 });
  const state = makeState({ paragraphs: [p1] });
  const active = session.create({ paragraphs: [p1], state });

  active.processItem(0, { kind: "ready", planJson: "{}" });

  assert.equal(restoreParagraphCalls.length, 1);
  assert.equal(restoreParagraphCalls[0], p1.source);

  active.finish();

  assert.equal(state.paragraphs.length, 0);
  assert.equal(state.issues.length, 1);
  assert.equal(state.issues[0].name, "PreparedDomRejection");
  assert.equal(state.issues[0].element, p1.source);
  assert.equal(state.issues[0].reportToConsole, true);
  // The lifecycle marker was written onto the source element.
  assert.equal(p1.source.getAttribute("data-tiqian-capability-issue"), "PreparedDomRejection");
});

test("5. ready path passes the exact metadata JSON strings and raw state.options and browserFallback to commitPreparedParagraph (assert argument fields)", () => {
  const commitCalls = [];
  const custody = {
    captureLive: () => ({ snap: true }),
    restoreParagraph: () => {},
  };
  const commitPreparedParagraph = {
    commitPreparedParagraph: (deps, arg) => {
      commitCalls.push(arg);
      return { kind: "success", measure: 310 };
    },
  };
  const session = makeSession({ custody, commitPreparedParagraph });

  const lowered = {
    text: "test",
    domInlineObjects: [{ start: 0, end: 1, marginRight: 6 }],
    sourceSpans: [
      { start: 0, end: 2, cjkStrongBaseWeight: 700, depth: 0, element: { tagName: "STRONG" } },
    ],
  };
  const p1 = makeParagraph({ lowered });
  const rawOptions = { fontSize: 22, custom: "yes" };
  const rawBrowserFallback = { fallbackEngine: true };
  const disableExact = () => {};
  const state = makeState({
    paragraphs: [p1],
    options: rawOptions,
    browserFallback: rawBrowserFallback,
    onDisableExactPreparedDom: disableExact,
  });

  const active = session.create({ paragraphs: [p1], state });

  const preparation = { kind: "ready", planJson: '{"plan":true}' };
  active.processItem(0, preparation);

  assert.equal(commitCalls.length, 1);
  const callArg = commitCalls[0];
  assert.equal(callArg.ffi, state.ffi);
  assert.equal(callArg.paragraph, p1);
  assert.equal(callArg.preparation, preparation);
  assert.equal(callArg.options, rawOptions);
  assert.equal(callArg.browserFallback, rawBrowserFallback);
  assert.equal(callArg.onExactPreparedDomFallback, disableExact);
  assert.equal(
    callArg.semanticReplayJson,
    preparedSemanticReplayJson(lowered)
  );
  assert.equal(
    callArg.inlineObjectMetaJson,
    preparedInlineObjectMetaJson(lowered)
  );
  assert.equal(
    callArg.cjkStrongSemanticsJson,
    preparedCjkStrongSemanticsJson(lowered)
  );
});

test("6. rollback(): state lists restored to before, custody.rollback receives the captured snapshots in insertion order, lastMeasure patched from a result", () => {
  let rollbackSnapshots = null;
  const custody = {
    captureLive: (source, lastMeasure) => ({ source, lastMeasure, snapshot: true }),
    restoreParagraph: () => {},
    rollback: (snapshots) => {
      rollbackSnapshots = snapshots;
      return [
        { source: p1.source, lastMeasure: 150 },
        { source: p2.source, lastMeasure: 220 },
      ];
    },
  };
  const commitPreparedParagraph = {
    commitPreparedParagraph: () => ({ kind: "success", measure: 300 }),
  };
  const session = makeSession({ custody, commitPreparedParagraph });

  const p1 = makeParagraph({ lastMeasure: 100 });
  const p2 = makeParagraph({ lastMeasure: 200 });
  const initialIssue = { name: "InitialIssue" };
  const state = makeState({
    paragraphs: [p1, p2],
    issues: [initialIssue],
  });

  const active = session.create({ paragraphs: [p1, p2], state });

  active.processItem(0, { kind: "unsupported", name: "Unsupported" });
  active.processItem(1, { kind: "ready", planJson: "{}" });

  // Mutate state lists to simulate mid-session modifications
  state.paragraphs.length = 0;
  state.issues.push({ name: "AnotherIssue" });

  active.rollback();

  assert.equal(state.paragraphs.length, 2);
  assert.equal(state.paragraphs[0], p1);
  assert.equal(state.paragraphs[1], p2);
  assert.equal(state.issues.length, 1);
  assert.equal(state.issues[0], initialIssue);

  assert.notEqual(rollbackSnapshots, null);
  assert.equal(rollbackSnapshots.length, 2);
  assert.equal(rollbackSnapshots[0].source, p1.source);
  assert.equal(rollbackSnapshots[1].source, p2.source);

  assert.equal(p1.lastMeasure, 150);
  assert.equal(p2.lastMeasure, 220);
});

test("7. rollback() with a result whose source is not in the session paragraphs: skipped without throwing", () => {
  const unknownElement = makeElement();
  const custody = {
    captureLive: (source, lastMeasure) => ({ source, lastMeasure, snapshot: true }),
    restoreParagraph: () => {},
    rollback: () => [
      { source: p1.source, lastMeasure: 160 },
      { source: unknownElement, lastMeasure: 999 },
    ],
  };
  const session = makeSession({ custody, commitPreparedParagraph: {} });

  const p1 = makeParagraph({ lastMeasure: 100 });
  const state = makeState({ paragraphs: [p1] });

  const active = session.create({ paragraphs: [p1], state });

  active.processItem(0, { kind: "unsupported", name: "Issue" });

  assert.doesNotThrow(() => {
    active.rollback();
  });

  assert.equal(p1.lastMeasure, 160);
});

test("8. stale starts false and is assignable", () => {
  const session = makeSession({ custody: {}, commitPreparedParagraph: {} });
  const p1 = makeParagraph();
  const state = makeState({ paragraphs: [p1] });
  const active = session.create({ paragraphs: [p1], state });

  assert.equal(active.stale, false);
  active.stale = true;
  assert.equal(active.stale, true);
});