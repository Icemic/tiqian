import assert from "node:assert/strict";
import test from "node:test";

import { createRootState } from "../core/engine/root-state.js";
import { initializeGlobalServices } from "../core/services/global-services.js";
initializeGlobalServices();


// Real lifecycle defaults resolved by withRootDefaults when the root inherits
// nothing.
const DEFAULT_CJK_FONT_FAMILY = '"MiSans VF", "PingFang SC", "Noto Sans CJK SC", sans-serif';
const DEFAULT_LATIN_FONT_FAMILY = '"InterVariable", "Inter", "MiSans VF", sans-serif';
const DEFAULT_MONOSPACE_FONT_FAMILY =
  '"JetBrains Mono Variable", "SFMono-Regular", Menlo, Consolas, "MiSans VF", monospace';
const DEFAULT_CJK_SERIF_FONT_FAMILY = '"MetroSungPlus-SC", "Songti SC", serif';
const DEFAULT_LATIN_SERIF_FONT_FAMILY = 'Georgia, "Times New Roman", serif';

function preserveGlobals(names) {
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

// withRootDefaults reads the root's computed font-family; an empty answer
// makes every family fall back to the built-in default stack.
function withComputedStyle(fn) {
  const saved = preserveGlobals(["getComputedStyle"]);
  globalThis.getComputedStyle = () => ({
    getPropertyValue: () => "",
  });
  try {
    return fn();
  } finally {
    restoreGlobals(saved);
  }
}

function makeRootState() {
  const rs = createRootState();
  return { rs };
}

class FakeElement {
  constructor(overrides = {}) {
    this.tagName = overrides.tagName ?? "P";
    this.parent = overrides.parent ?? null;
    this.children = overrides.children ?? [];
    this.textContent = overrides.textContent ?? "text";
    this.closestFn = overrides.closestFn ?? null;
    this.querySelectorAllResult = overrides.querySelectorAllResult ?? null;
    this.attributes = new Map(Object.entries(overrides.attributes ?? {}));
    this.setAttributeCalls = [];
    for (const child of this.children) child.parent = this;
  }
  getAttribute(name) {
    return this.attributes.get(name) ?? null;
  }
  setAttribute(name, value) {
    this.setAttributeCalls.push([name, String(value)]);
    this.attributes.set(name, String(value));
  }
  removeAttribute(name) {
    this.attributes.delete(name);
  }
  closest(selector) {
    if (this.closestFn) return this.closestFn(selector);
    return null;
  }
  contains(other) {
    let node = other;
    while (node) {
      if (node === this) return true;
      node = node.parent;
    }
    return false;
  }
  querySelectorAll(selector) {
    if (this.querySelectorAllResult) return this.querySelectorAllResult;
    return this.children.slice();
  }
}

test("1. createRootState: optionsBag -> optionsFromJs -> snapshot gate -> withRootDefaults; full field set", () => {
  withComputedStyle(() => {
    const { rs } = makeRootState();

    const root = new FakeElement({ attributes: { "data-tiqian-snapshot-layout-fallback": "stale" } });

    // fontSize bag: the snapshot gate routes through withoutSnapshotFontSession.
    const state = rs.createRootState(root, { fontSize: 19 });

    assert.equal(root.getAttribute("data-tiqian-snapshot-layout-fallback"), null);
    assert.equal(state.root, root);
    assert.equal(state.options.fontSize, 19);
    assert.equal(state.options.snapshotFontSession, null);
    assert.equal(state.options.fontFamilies.cjk, DEFAULT_CJK_FONT_FAMILY);
    assert.equal(state.options.fontFamilies.latin, DEFAULT_LATIN_FONT_FAMILY);
    assert.equal(state.options.fontFamilies.monospace, DEFAULT_MONOSPACE_FONT_FAMILY);
    assert.equal(state.options.fontFamilies.cjkSerif, DEFAULT_CJK_SERIF_FONT_FAMILY);
    assert.equal(state.options.fontFamilies.latinSerif, DEFAULT_LATIN_SERIF_FONT_FAMILY);
    assert.deepEqual(state.paragraphs, []);
    assert.deepEqual(state.issues, []);
    assert.equal(state.preparedDomEnabled, true);
    assert.equal(state.preparedDomFallback, null);
    assert.ok(state.browserFallback.bridge);
    assert.equal(typeof state.browserFallback.bridge.shapeJson, "function");
    assert.equal(typeof state.browserFallback.bridge.metricsJson, "function");

    // All-default bag: the snapshot gate passes straight through, so the
    // withoutSnapshotFontSession path is never taken.
    const allDefault = rs.createRootState(root, {});
    assert.equal(allDefault.options.snapshotFontSession, null);

    // null bag also works.
    const nullBag = rs.createRootState(root, null);
    assert.equal(nullBag.options.fontSize, null);
  });
});

test("2. createRootStateFromCanonical skips optionsFromJs and the snapshot gate but runs withRootDefaults", () => {
  withComputedStyle(() => {
    const { rs } = makeRootState();

    const root = new FakeElement();
    const canonical = {
      fontFamilies: { cjk: "CJK", latin: "Latin", monospace: "Mono", cjkSerif: "Serif", latinSerif: "LatinSerif" },
      fontSize: 19,
      lineHeight: null,
      firstLineIndentIc: 0,
      emphasisDotGapEm: 0.1,
      strongAsEmphasisMarks: false,
      paragraphSelector: "p, li",
      cjkDashCapability: null,
      snapshotFontSession: { status: "conforming", sessionId: "canonical-session" },
      requireSnapshotLayoutWorker: false,
    };

    const state = rs.createRootStateFromCanonical(root, canonical);

    assert.equal(state.root, root);
    // Explicit families pass through unchanged; the snapshot gate is skipped.
    assert.equal(state.options.fontFamilies.cjk, "CJK");
    assert.equal(state.options.snapshotFontSession.sessionId, "canonical-session");
    assert.deepEqual(state.paragraphs, []);
    assert.deepEqual(state.issues, []);
    assert.equal(state.preparedDomEnabled, true);
    assert.equal(state.preparedDomFallback, null);
  });
});

test("3. preparedDom toggle: active options, snapshot session descriptor, attribute write, truncation, idempotence", () => {
  withComputedStyle(() => {
    const { rs } = makeRootState();

    const root = new FakeElement();
    const state = rs.createRootState(root, {
      snapshotFontSession: { status: "conforming", sessionId: "sess-1" },
    });

    // While prepared DOM is enabled the active options are state.options and
    // the descriptor resolves the session id into the callback pair ffi takes
    // as call parameters; an unregistered id only fails at call time.
    assert.equal(rs.activeTsOptions(state), state.options);
    const descriptor = rs.activeSnapshotSessionDescriptor(state);
    assert.equal(typeof descriptor.shapeJson, "function");
    assert.equal(typeof descriptor.metricsJson, "function");

    const detail = "x".repeat(600);
    rs.disableSnapshotPreparedDom(state, detail);
    assert.equal(state.preparedDomEnabled, false);
    assert.equal(state.preparedDomFallback, "x".repeat(512));
    assert.equal(root.getAttribute("data-tiqian-snapshot-layout-fallback"), "x".repeat(512));
    assert.equal(
      root.setAttributeCalls.filter((call) => call[0] === "data-tiqian-snapshot-layout-fallback").length,
      1
    );

    // After disable: active options drop the snapshot font session and the
    // descriptor becomes null.
    const active = rs.activeTsOptions(state);
    assert.notEqual(active, state.options);
    assert.equal(active.snapshotFontSession, null);
    assert.equal(rs.activeSnapshotSessionDescriptor(state), null);

    // Idempotent: a second call changes nothing and does not rewrite the attribute.
    rs.disableSnapshotPreparedDom(state, "second-detail");
    assert.equal(state.preparedDomFallback, "x".repeat(512));
    assert.equal(root.getAttribute("data-tiqian-snapshot-layout-fallback"), "x".repeat(512));
    assert.equal(
      root.setAttributeCalls.filter((call) => call[0] === "data-tiqian-snapshot-layout-fallback").length,
      1
    );
  });
});

test("4. engineState cross-section: live arrays, callback wiring", () => {
  withComputedStyle(() => {
    const { rs } = makeRootState();

    const root = new FakeElement();
    const state = rs.createRootState(root, {
      snapshotFontSession: { status: "conforming", sessionId: "sess-1" },
    });
    const engine = rs.engineState(state);

    assert.equal(engine.options, state.options);
    assert.equal(engine.preparedDomEnabled, true);
    assert.equal(typeof engine.snapshotSession.shapeJson, "function");
    assert.equal(typeof engine.snapshotSession.metricsJson, "function");
    assert.equal(engine.browserFallback, state.browserFallback);
    assert.equal(engine.paragraphs, state.paragraphs);
    assert.equal(engine.issues, state.issues);

    const issue = { name: "MissingSharedRuntimeStyles" };
    engine.onIssue(issue);
    assert.equal(state.issues.length, 1);
    assert.equal(state.issues[0], issue);

    const item = { source: root, lowered: null, lastMeasure: null };
    engine.onParagraphCommitted(item);
    assert.equal(state.paragraphs.length, 1);
    assert.equal(state.paragraphs[0], item);

    engine.onDisableSnapshotPreparedDom("replay-mismatch");
    assert.equal(state.preparedDomEnabled, false);
    assert.equal(root.getAttribute("data-tiqian-snapshot-layout-fallback"), "replay-mismatch");
  });
});

test("5. publishState: work branch, keepEmpty branch, delete branch, snapshot count participation", () => {
  withComputedStyle(() => {
    const { rs } = makeRootState();

    // Work branch: attributes written, WeakMap retains the state, and the
    // observable snapshot count participates in the enhanced count.
    const rootWork = new FakeElement();
    const stateWork = rs.createRootState(rootWork, {});
    stateWork.paragraphs.push({ source: rootWork, lowered: null, lastMeasure: null });
    rootWork.setAttribute("data-tiqian-snapshot-count", "3");
    rs.publishState(stateWork);
    assert.equal(rootWork.getAttribute("data-tiqian-enhanced"), "true");
    assert.equal(rootWork.getAttribute("data-tiqian-enhanced-count"), "4");
    assert.equal(rootWork.getAttribute("data-tiqian-issue-count"), null);
    assert.equal(rs.getState(rootWork), stateWork);

    // keepEmpty: no work but the state is retained and attributes still write.
    const rootKeep = new FakeElement();
    const stateKeep = rs.createRootState(rootKeep, {});
    rs.publishState(stateKeep, true);
    assert.equal(rs.getState(rootKeep), stateKeep);
    assert.equal(rootKeep.getAttribute("data-tiqian-enhanced"), "true");
    assert.equal(rootKeep.getAttribute("data-tiqian-enhanced-count"), "0");
    assert.equal(rootKeep.getAttribute("data-tiqian-issue-count"), null);

    // delete branch: no work and not keepEmpty -> state deleted and the three
    // attributes are removed.
    const rootDelete = new FakeElement({
      attributes: {
        "data-tiqian-enhanced": "true",
        "data-tiqian-enhanced-count": "2",
        "data-tiqian-issue-count": "1",
      },
    });
    const stateDelete = rs.createRootState(rootDelete, {});
    rs.setState(rootDelete, stateDelete);
    rs.publishState(stateDelete);
    assert.equal(rs.getState(rootDelete), undefined);
    assert.equal(rootDelete.getAttribute("data-tiqian-enhanced"), null);
    assert.equal(rootDelete.getAttribute("data-tiqian-enhanced-count"), null);
    assert.equal(rootDelete.getAttribute("data-tiqian-issue-count"), null);

    // Issue count branch: issues present -> issue-count written.
    const rootIssue = new FakeElement();
    const stateIssue = rs.createRootState(rootIssue, {});
    stateIssue.issues.push({ name: "MissingSharedRuntimeStyles" });
    rs.publishState(stateIssue);
    assert.equal(rootIssue.getAttribute("data-tiqian-issue-count"), "1");
  });
});

test("6. strandedSourceParagraphs: empty paragraphs returns all candidates; rendered sources subtracted", () => {
  const { rs } = makeRootState();

  const c0 = new FakeElement();
  const c1 = new FakeElement();
  const c2 = new FakeElement();
  const root = new FakeElement({ children: [c0, c1, c2] });

  const stateEmpty = { options: { paragraphSelector: "p, li" }, paragraphs: [] };
  assert.deepEqual(rs.strandedSourceParagraphs(root, stateEmpty), [c0, c1, c2]);

  const stateRendered = {
    options: { paragraphSelector: "p, li" },
    paragraphs: [{ source: c0 }, { source: c2 }],
  };
  assert.deepEqual(rs.strandedSourceParagraphs(root, stateRendered), [c1]);
});

test("7. paragraphCandidates: root-scope and real eligibility filtering", () => {
  const { rs } = makeRootState();
  const root = new FakeElement();
  const innerEl = new FakeElement({ parent: root });
  const outsideEl = new FakeElement();
  // closest answers the root-scope owner for the root selector and stays null
  // for every other selector (including eligibility's skip-list ancestor
  // selector), so the real shouldTryParagraph predicate is exercised.
  const rootScopedClosest = (owner) => (selector) =>
    selector === "tiqian-prose, [data-tiqian-root]" ? owner : null;
  // closest null -> owner absent, in scope, eligible.
  const p1 = new FakeElement({ parent: root });
  // owner lives outside the root -> in scope, eligible.
  const p2 = new FakeElement({ parent: root, closestFn: rootScopedClosest(outsideEl) });
  // owner is the root itself -> the root owns the paragraph, in scope.
  const p3 = new FakeElement({ parent: root, closestFn: rootScopedClosest(root) });
  // owner is a descendant inside the root -> out of scope even when eligible.
  const p4 = new FakeElement({ parent: root, closestFn: rootScopedClosest(innerEl) });
  // a plain in-scope paragraph is eligible.
  const p5 = new FakeElement({ parent: root });
  // data-tiqian-skip marks an in-scope paragraph ineligible.
  const p6 = new FakeElement({ parent: root, attributes: { "data-tiqian-skip": "" } });
  root.querySelectorAllResult = [p1, p2, p3, p4, p5, p6];

  const candidates = rs.paragraphCandidates(root, "p, li");
  assert.deepEqual(candidates, [p1, p2, p3, p5]);
  // p4 is rejected by the root-scope gate (its scope owner is a nested
  // element inside the root); p6 by the real eligibility predicate
  // (data-tiqian-skip). p3 stays a candidate because its nearest scope
  // owner is the root being enhanced.
});