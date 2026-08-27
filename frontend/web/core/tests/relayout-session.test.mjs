import assert from "node:assert/strict";
import test from "node:test";

import { openRelayoutSession } from "../core/engine/relayout-session.js";
import {
  preparedCjkStrongSemanticsJson,
  preparedInlineObjectMetaJson,
  preparedSemanticReplayJson,
} from "../core/engine/prepared-metadata.js";

// The session factory takes fake detached-fragment backup; the lifecycle helpers and the
// metadata JSON builders run for real. The 'ready' path exercises the real
// commitPreparedParagraph through the test-world prepared-DOM bridge, so the
// renderer/validator globals are planted per test.

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
    options: overrides.options !== undefined ? overrides.options : { fontSize: 19 },
    preparedDomEnabled: overrides.preparedDomEnabled ?? true,
    snapshotSession: overrides.snapshotSession ?? { sessionId: "session-1" },
    browserFallback: overrides.browserFallback ?? null,
    onIssue: overrides.onIssue ?? ((issue) => {}),
    onParagraphCommitted: overrides.onParagraphCommitted ?? ((item) => {}),
    onDisableSnapshotPreparedDom: overrides.onDisableSnapshotPreparedDom ?? ((detail) => {}),
    paragraphs,
    issues,
  };
}

function makeSession(rawDom) {
  return {
    create: (argument) => openRelayoutSession(rawDom, argument),
  };
}

function withPreparedBridge(fn, overrides = {}) {
  const names = ["__TiqianPreparedDomRenderer"];
  const saved = names.map((name) => ({
    name,
    own: Object.prototype.hasOwnProperty.call(globalThis, name),
    value: globalThis[name],
  }));
  try {
    // Tests now use the real prepared-dom renderer directly.
    // Validator injection removed per spec.
    return fn();
  } finally {
    for (const entry of saved) {
      if (entry.own) globalThis[entry.name] = entry.value;
      else delete globalThis[entry.name];
    }
  }
}

test("1. unchanged verdict: no rawDom call, no state change", () => {
  const captureLiveCalls = [];
  const restoreParagraphCalls = [];
  const rawDom = { suspendEngineWrites: (s, a) => a(),
    captureLive: (...args) => {
      captureLiveCalls.push(args);
      return { snap: true };
    },
    restoreParagraph: (source) => {
      restoreParagraphCalls.push(source);
    },
  };
  const session = makeSession(rawDom);

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
  const rawDom = { suspendEngineWrites: (s, a) => a(),
    captureLive: (source, lastMeasure) => {
      captureLiveCalls.push({ source, lastMeasure });
      return { source, snapshot: true };
    },
    restoreParagraph: (source) => {
      restoreParagraphCalls.push(source);
    },
  };
  const session = makeSession(rawDom);

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

test("3. ready + commit success: lastMeasure copies preparation.measure, rawDom.stampRendered called, item stays in state.paragraphs, no restoreParagraph", () => {
  const captureLiveCalls = [];
  const restoreParagraphCalls = [];
  const stampRenderedCalls = [];
  const rawDom = { suspendEngineWrites: (s, a) => a(),
    captureLive: (...args) => {
      captureLiveCalls.push(args);
      return { snap: true };
    },
    restoreParagraph: (source) => {
      restoreParagraphCalls.push(source);
    },
    stampRendered: (source) => {
      stampRenderedCalls.push(source);
    },
  };
  const session = makeSession(rawDom);

  const p1 = makeParagraph({ lastMeasure: 100 });
  const state = makeState({ paragraphs: [p1] });
  const active = session.create({ paragraphs: [p1], state });

  const preparation = { kind: "ready", planJson: "{}", measure: 250, width: 300 };
  withPreparedBridge(() => {
    active.processItem(0, preparation);
  });

  assert.equal(captureLiveCalls.length, 1);
  assert.equal(restoreParagraphCalls.length, 0);
  assert.equal(p1.lastMeasure, preparation.measure);
  assert.equal(stampRenderedCalls.length, 1);
  assert.equal(stampRenderedCalls[0], p1.source);

  active.finish();

  assert.equal(p1.lastMeasure, preparation.measure);
  assert.equal(state.paragraphs.length, 1);
  assert.equal(state.paragraphs[0], p1);
  assert.equal(state.issues.length, 0);
});

test("4. ready + commit unsupported: real validator rejects, restoreParagraph called, finish() removes and reports", () => {
  const restoreParagraphCalls = [];
  const rawDom = { suspendEngineWrites: (s, a) => a(),
    captureLive: () => ({ snap: true }),
    restoreParagraph: (source) => {
      restoreParagraphCalls.push(source);
    },
  };
  const session = makeSession(rawDom);

  const p1 = makeParagraph({ lastMeasure: 100 });
  const state = makeState({ paragraphs: [p1] });
  const active = session.create({ paragraphs: [p1], state });

  const preparation = { kind: "ready", planJson: "{}", measure: 250, width: 300 };
  withPreparedBridge(() => {
    setCommitValidatorForTesting({
      issue: function () { return "DOM mismatch"; },
    });
    active.processItem(0, preparation);
  });

  assert.equal(restoreParagraphCalls.length, 1);
  assert.equal(restoreParagraphCalls[0], p1.source);

  active.finish();

  assert.equal(state.paragraphs.length, 0);
  assert.equal(state.issues.length, 1);
  assert.equal(state.issues[0].name, "PreparedDomRenderMismatch");
  assert.equal(state.issues[0].detail, "DOM mismatch");
  assert.equal(state.issues[0].element, p1.source);
  assert.equal(state.issues[0].reportToConsole, true);
  // The lifecycle marker was written onto the source element.
  assert.equal(p1.source.getAttribute("data-tiqian-capability-issue"), "PreparedDomRenderMismatch");
});

test("5. ready path passes the exact metadata JSON strings to the prepared-DOM renderer (assert captured render options)", () => {
  const renderCalls = [];
  const rawDom = { suspendEngineWrites: (s, a) => a(),
    captureLive: () => ({ snap: true }),
    restoreParagraph: () => {},
    stampRendered: () => {},
  };
  const session = makeSession(rawDom);

  const lowered = {
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
    domInlineObjects: [{ start: 0, end: 1, marginRight: 6 }],
    sourceSpans: [
      { start: 0, end: 2, cjkStrongBaseWeight: 700, depth: 0, element: { tagName: "STRONG" } },
    ],
    sourceBoundaries: [],
    lineBreakSpans: [],
  };
  const p1 = makeParagraph({ lowered });
  const rawOptions = { fontSize: 22, custom: "yes" };
  const rawBrowserFallback = { fallbackEngine: true };
  const disableSnapshot = () => {};
  const state = makeState({
    paragraphs: [p1],
    options: rawOptions,
    browserFallback: rawBrowserFallback,
    onDisableSnapshotPreparedDom: disableSnapshot,
  });

  const active = session.create({ paragraphs: [p1], state });

  const preparation = { kind: "ready", planJson: '{"plan":true}', measure: 310, width: 300 };
  withPreparedBridge(() => {
    setPreparedDomRendererForTesting({
      render: function (host, planJson, locale, options) {
        renderCalls.push({ host, planJson, locale, options });
      },
      release: function () { return true; },
      releaseRoot: function () { return true; },
    });
    active.processItem(0, preparation);
  });

  assert.equal(renderCalls.length, 1);
  const call = renderCalls[0];
  assert.equal(call.host, p1.source);
  assert.equal(call.planJson, preparation.planJson);
  assert.equal(call.locale, lowered.textStyle.locale);
  // The live-source replay options derive from the metadata JSON builders.
  assert.equal(call.options.sourceText, lowered.text);
  assert.equal(call.options.semanticReplay, "live-source");
  assert.deepEqual(call.options.semantics, JSON.parse(preparedSemanticReplayJson(lowered)));
  assert.equal(call.options.liveSemanticElements.length, 1);
  assert.equal(call.options.liveSemanticElements[0], lowered.sourceSpans[0].element);
  assert.deepEqual(call.options.cjkStrongSemantics, JSON.parse(preparedCjkStrongSemanticsJson(lowered)));
  // inlineObjects carries the inline-object meta entries derived from the
  // prepared-inline-object-metadata builder.
  const inlineMeta = JSON.parse(preparedInlineObjectMetaJson(lowered));
  assert.equal(call.options.inlineObjects.length, inlineMeta.length);
  assert.equal(call.options.inlineObjects[0].start, inlineMeta[0].start);
  assert.equal(call.options.inlineObjects[0].end, inlineMeta[0].end);
  assert.equal(call.options.inlineObjects[0].marginRight, inlineMeta[0].marginRight);
});

test("6. rollback(): state lists restored to before, rawDom.rollback receives the captured snapshots in insertion order, lastMeasure patched from a result", () => {
  let rollbackSnapshots = null;
  const rawDom = { suspendEngineWrites: (s, a) => a(),
    captureLive: (source, lastMeasure) => ({ source, lastMeasure, snapshot: true }),
    restoreParagraph: () => {},
    stampRendered: () => {},
    rollback: (snapshots) => {
      rollbackSnapshots = snapshots;
      return [
        { source: p1.source, lastMeasure: 150 },
        { source: p2.source, lastMeasure: 220 },
      ];
    },
  };
  const session = makeSession(rawDom);

  const p1 = makeParagraph({ lastMeasure: 100 });
  const p2 = makeParagraph({ lastMeasure: 200 });
  const initialIssue = { name: "InitialIssue" };
  const state = makeState({
    paragraphs: [p1, p2],
    issues: [initialIssue],
  });

  const active = session.create({ paragraphs: [p1, p2], state });

  active.processItem(0, { kind: "unsupported", name: "Unsupported" });
  withPreparedBridge(() => {
    active.processItem(1, { kind: "ready", planJson: "{}", measure: 250, width: 300 });
  });

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
  const rawDom = { suspendEngineWrites: (s, a) => a(),
    captureLive: (source, lastMeasure) => ({ source, lastMeasure, snapshot: true }),
    restoreParagraph: () => {},
    rollback: () => [
      { source: p1.source, lastMeasure: 160 },
      { source: unknownElement, lastMeasure: 999 },
    ],
  };
  const session = makeSession(rawDom);

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
  const session = makeSession({});
  const p1 = makeParagraph();
  const state = makeState({ paragraphs: [p1] });
  const active = session.create({ paragraphs: [p1], state });

  assert.equal(active.stale, false);
  active.stale = true;
  assert.equal(active.stale, true);
});
