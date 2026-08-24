import assert from "node:assert/strict";
import test from "node:test";

import "./core/engine/root-state.js";

const rootState = globalThis.__TiqianRootState;

const ROOT_STATE_GLOBALS = [
  "__TiqianRootState",
  "__TiqianLifecycle",
  "__TiqianEligibility",
  "__TiqianCanvasFonts",
  "__TiqianBrowserMetricsBridge",
];

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

function makeLifecycle() {
  const calls = {
    optionsFromJs: [],
    allowsSnapshotExactLayout: [],
    withoutExactFontSession: [],
    withRootDefaults: [],
    conformingExactFontSessionId: [],
  };
  const lifecycle = {
    optionsFromJs(bag) {
      calls.optionsFromJs.push(bag);
      return {
        fontFamilies: {
          cjk: bag && bag.cjkFontFamily != null ? String(bag.cjkFontFamily) : null,
          latin: bag && bag.latinFontFamily != null ? String(bag.latinFontFamily) : null,
          monospace: bag && bag.monospaceFontFamily != null ? String(bag.monospaceFontFamily) : null,
          cjkSerif: bag && bag.cjkSerifFontFamily != null ? String(bag.cjkSerifFontFamily) : null,
          latinSerif: bag && bag.latinSerifFontFamily != null ? String(bag.latinSerifFontFamily) : null,
        },
        fontSize: bag && bag.fontSize != null ? Number(bag.fontSize) : null,
        lineHeight: bag && bag.lineHeight != null ? Number(bag.lineHeight) : null,
        firstLineIndentIc: bag && bag.firstLineIndentIc != null ? Number(bag.firstLineIndentIc) : 0,
        emphasisDotGapEm: bag && bag.emphasisDotGapEm != null ? Number(bag.emphasisDotGapEm) : 0.1,
        strongAsEmphasisMarks: !!(bag && bag.strongAsEmphasisMarks),
        paragraphSelector: bag && bag.paragraphSelector != null ? String(bag.paragraphSelector) : "p, li",
        requireExactLayoutWorker: !!(bag && bag.requireExactLayoutWorker),
        cjkDashCapability: bag && bag.cjkDashCapability ? bag.cjkDashCapability : null,
        exactFontSession:
          bag && bag.exactFontSession
            ? {
                status: bag.exactFontSession.status != null ? String(bag.exactFontSession.status) : "unavailable",
                sessionId: bag.exactFontSession.sessionId != null ? String(bag.exactFontSession.sessionId) : null,
                detail: bag.exactFontSession.detail != null ? String(bag.exactFontSession.detail) : null,
              }
            : null,
      };
    },
    allowsSnapshotExactLayout(options) {
      calls.allowsSnapshotExactLayout.push(options);
      return (
        options.fontSize == null &&
        options.lineHeight == null &&
        options.firstLineIndentIc === 0 &&
        options.fontFamilies.cjk == null &&
        options.fontFamilies.latin == null &&
        options.fontFamilies.monospace == null &&
        options.fontFamilies.cjkSerif == null &&
        options.fontFamilies.latinSerif == null
      );
    },
    withoutExactFontSession(options) {
      calls.withoutExactFontSession.push(options);
      const copy = Object.assign({}, options);
      copy.exactFontSession = null;
      return copy;
    },
    withRootDefaults(options, root) {
      calls.withRootDefaults.push({ options, root });
      const families = options.fontFamilies || {};
      return Object.assign({}, options, {
        fontFamilies: {
          cjk: families.cjk != null ? families.cjk : "DEFAULT_CJK",
          latin: families.latin != null ? families.latin : "DEFAULT_LATIN",
          monospace: families.monospace != null ? families.monospace : "DEFAULT_MONO",
          cjkSerif: families.cjkSerif != null ? families.cjkSerif : "DEFAULT_SERIF",
          latinSerif: families.latinSerif != null ? families.latinSerif : "DEFAULT_LATIN_SERIF",
        },
      });
    },
    conformingExactFontSessionId(options) {
      calls.conformingExactFontSessionId.push(options);
      const session = options && options.exactFontSession;
      if (
        !session ||
        session.status !== "conforming" ||
        typeof session.sessionId !== "string" ||
        session.sessionId.trim().length === 0
      ) {
        return null;
      }
      return session.sessionId;
    },
  };
  return { lifecycle, calls };
}

function makeCanvasFonts() {
  const calls = { createFontFamilies: [] };
  const instances = [];
  const fonts = {
    createFontFamilies(config) {
      calls.createFontFamilies.push(config);
      const instance = { config, kind: "fonts" };
      instances.push(instance);
      return instance;
    },
  };
  return { fonts, calls, instances };
}

function makeBridge() {
  const calls = { createBrowserMetricsBridge: [] };
  const instances = [];
  const bridge = {
    createBrowserMetricsBridge(config) {
      calls.createBrowserMetricsBridge.push(config);
      const instance = { shapeJson: () => "{}", metricsJson: () => "{}" };
      instances.push(instance);
      return instance;
    },
  };
  return { bridge, calls, instances };
}

function installGlobals({ lifecycle, fonts, bridge, eligibility }) {
  globalThis.__TiqianLifecycle = lifecycle;
  globalThis.__TiqianCanvasFonts = fonts;
  globalThis.__TiqianBrowserMetricsBridge = bridge;
  globalThis.__TiqianEligibility = eligibility || { shouldTryParagraph: () => true };
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
  const saved = preserveGlobals(ROOT_STATE_GLOBALS);
  try {
    const lifecycle = makeLifecycle();
    const fonts = makeCanvasFonts();
    const bridge = makeBridge();
    installGlobals({ lifecycle: lifecycle.lifecycle, fonts: fonts.fonts, bridge: bridge.bridge });

    const root = new FakeElement({ attributes: { "data-tq-exact-layout-fallback": "stale" } });

    // fontSize bag: the snapshot gate routes through withoutExactFontSession.
    const state = rootState.createRootState(root, { fontSize: 19 });

    assert.equal(lifecycle.calls.optionsFromJs.length, 1);
    assert.equal(lifecycle.calls.allowsSnapshotExactLayout.length, 1);
    assert.equal(lifecycle.calls.withoutExactFontSession.length, 1);
    assert.equal(lifecycle.calls.withRootDefaults.length, 1);

    assert.equal(root.getAttribute("data-tq-exact-layout-fallback"), null);
    assert.equal(state.root, root);
    assert.equal(state.options.fontSize, 19);
    assert.equal(state.options.exactFontSession, null);
    assert.equal(state.options.fontFamilies.cjk, "DEFAULT_CJK");
    assert.equal(state.options.fontFamilies.latin, "DEFAULT_LATIN");
    assert.equal(state.options.fontFamilies.monospace, "DEFAULT_MONO");
    assert.equal(state.options.fontFamilies.cjkSerif, "DEFAULT_SERIF");
    assert.equal(state.options.fontFamilies.latinSerif, "DEFAULT_LATIN_SERIF");
    assert.deepEqual(state.paragraphs, []);
    assert.deepEqual(state.issues, []);
    assert.equal(state.preparedDomEnabled, true);
    assert.equal(state.preparedDomFallback, null);
    assert.equal(state.browserFallback.bridge, bridge.instances[0]);

    // The five families feed createFontFamilies from resolved.fontFamilies.
    assert.equal(fonts.calls.createFontFamilies.length, 1);
    const fontsConfig = fonts.calls.createFontFamilies[0];
    assert.equal(fontsConfig.cjk, "DEFAULT_CJK");
    assert.equal(fontsConfig.latin, "DEFAULT_LATIN");
    assert.equal(fontsConfig.latinMonospace, "DEFAULT_MONO");
    assert.equal(fontsConfig.cjkSerif, "DEFAULT_SERIF");
    assert.equal(fontsConfig.latinSerif, "DEFAULT_LATIN_SERIF");

    // Bridge config carries fonts, cjkDashCapability and the lazy env factory.
    assert.equal(bridge.calls.createBrowserMetricsBridge.length, 1);
    const bridgeConfig = bridge.calls.createBrowserMetricsBridge[0];
    assert.equal(bridgeConfig.fonts, fonts.instances[0]);
    assert.equal(bridgeConfig.cjkDashCapability, null);
    assert.equal(typeof bridgeConfig.env.createCanvasContext, "function");
    assert.equal(typeof bridgeConfig.env.createProbeElement, "function");
    assert.equal(typeof bridgeConfig.env.attachProbe, "function");
    // env factories stay lazy: document is never touched during createRootState.
    assert.equal(typeof document, "undefined");

    // All-default bag: the snapshot gate passes straight through.
    rootState.createRootState(root, {});
    assert.equal(lifecycle.calls.withoutExactFontSession.length, 1);

    // null bag also works.
    rootState.createRootState(root, null);
    assert.equal(lifecycle.calls.optionsFromJs.length, 3);
    assert.equal(lifecycle.calls.withoutExactFontSession.length, 1);
  } finally {
    restoreGlobals(saved);
  }
});

test("2. createRootStateFromCanonical skips optionsFromJs and the snapshot gate but runs withRootDefaults", () => {
  const saved = preserveGlobals(ROOT_STATE_GLOBALS);
  try {
    const lifecycle = makeLifecycle();
    const fonts = makeCanvasFonts();
    const bridge = makeBridge();
    installGlobals({ lifecycle: lifecycle.lifecycle, fonts: fonts.fonts, bridge: bridge.bridge });

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
      exactFontSession: { status: "conforming", sessionId: "canonical-session" },
      requireExactLayoutWorker: false,
    };

    const state = rootState.createRootStateFromCanonical(root, canonical);

    assert.equal(lifecycle.calls.optionsFromJs.length, 0);
    assert.equal(lifecycle.calls.allowsSnapshotExactLayout.length, 0);
    assert.equal(lifecycle.calls.withoutExactFontSession.length, 0);
    assert.equal(lifecycle.calls.withRootDefaults.length, 1);
    assert.equal(state.root, root);
    assert.equal(state.options.fontFamilies.cjk, "CJK");
    assert.equal(state.options.exactFontSession.sessionId, "canonical-session");
    assert.deepEqual(state.paragraphs, []);
    assert.deepEqual(state.issues, []);
    assert.equal(state.preparedDomEnabled, true);
    assert.equal(state.preparedDomFallback, null);
  } finally {
    restoreGlobals(saved);
  }
});

test("3. preparedDom toggle: active options, exact session descriptor, attribute write, truncation, idempotence", () => {
  const saved = preserveGlobals(ROOT_STATE_GLOBALS);
  try {
    const lifecycle = makeLifecycle();
    const fonts = makeCanvasFonts();
    const bridge = makeBridge();
    installGlobals({ lifecycle: lifecycle.lifecycle, fonts: fonts.fonts, bridge: bridge.bridge });

    const root = new FakeElement();
    const state = rootState.createRootState(root, {
      exactFontSession: { status: "conforming", sessionId: "sess-1" },
    });

    // While prepared DOM is enabled the active options are state.options and
    // the conforming session id is visible.
    assert.equal(rootState.activeTsOptions(state), state.options);
    assert.deepEqual(rootState.activeExactSessionDescriptor(state), { sessionId: "sess-1" });

    const detail = "x".repeat(600);
    rootState.disableExactPreparedDom(state, detail);
    assert.equal(state.preparedDomEnabled, false);
    assert.equal(state.preparedDomFallback, "x".repeat(512));
    assert.equal(root.getAttribute("data-tq-exact-layout-fallback"), "x".repeat(512));
    assert.equal(
      root.setAttributeCalls.filter((call) => call[0] === "data-tq-exact-layout-fallback").length,
      1
    );

    // After disable: active options drop the exact font session and the
    // descriptor becomes null.
    const active = rootState.activeTsOptions(state);
    assert.notEqual(active, state.options);
    assert.equal(active.exactFontSession, null);
    assert.equal(rootState.activeExactSessionDescriptor(state), null);

    // Idempotent: a second call changes nothing and does not rewrite the attribute.
    rootState.disableExactPreparedDom(state, "second-detail");
    assert.equal(state.preparedDomFallback, "x".repeat(512));
    assert.equal(root.getAttribute("data-tq-exact-layout-fallback"), "x".repeat(512));
    assert.equal(
      root.setAttributeCalls.filter((call) => call[0] === "data-tq-exact-layout-fallback").length,
      1
    );
  } finally {
    restoreGlobals(saved);
  }
});

test("4. engineState cross-section: bound ffi, live arrays, callback wiring", () => {
  const saved = preserveGlobals(ROOT_STATE_GLOBALS);
  try {
    const lifecycle = makeLifecycle();
    const fonts = makeCanvasFonts();
    const bridge = makeBridge();
    installGlobals({ lifecycle: lifecycle.lifecycle, fonts: fonts.fonts, bridge: bridge.bridge });

    const ffi = { classifyFontRole: () => "Cjk" };
    rootState.bindFfi(ffi);
    assert.equal(rootState.currentFfi(), ffi);

    const root = new FakeElement();
    const state = rootState.createRootState(root, {
      exactFontSession: { status: "conforming", sessionId: "sess-1" },
    });
    const engine = rootState.engineState(state);

    assert.equal(engine.ffi, ffi);
    assert.equal(engine.options, state.options);
    assert.equal(engine.preparedDomEnabled, true);
    assert.deepEqual(engine.exactSession, { sessionId: "sess-1" });
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

    engine.onDisableExactPreparedDom("replay-mismatch");
    assert.equal(state.preparedDomEnabled, false);
    assert.equal(root.getAttribute("data-tq-exact-layout-fallback"), "replay-mismatch");
  } finally {
    restoreGlobals(saved);
  }
});

test("5. publishState: work branch, keepEmpty branch, delete branch, snapshot count participation", () => {
  const saved = preserveGlobals(ROOT_STATE_GLOBALS);
  try {
    const lifecycle = makeLifecycle();
    const fonts = makeCanvasFonts();
    const bridge = makeBridge();
    installGlobals({ lifecycle: lifecycle.lifecycle, fonts: fonts.fonts, bridge: bridge.bridge });

    // Work branch: attributes written, WeakMap retains the state, and the
    // observable snapshot count participates in the enhanced count.
    const rootWork = new FakeElement();
    const stateWork = rootState.createRootState(rootWork, {});
    stateWork.paragraphs.push({ source: rootWork, lowered: null, lastMeasure: null });
    rootWork.setAttribute("data-tiqian-snapshot-count", "3");
    rootState.publishState(stateWork);
    assert.equal(rootWork.getAttribute("data-tiqian-enhanced"), "true");
    assert.equal(rootWork.getAttribute("data-tiqian-enhanced-count"), "4");
    assert.equal(rootWork.getAttribute("data-tiqian-issue-count"), null);
    assert.equal(rootState.getState(rootWork), stateWork);

    // keepEmpty: no work but the state is retained and attributes still write.
    const rootKeep = new FakeElement();
    const stateKeep = rootState.createRootState(rootKeep, {});
    rootState.publishState(stateKeep, true);
    assert.equal(rootState.getState(rootKeep), stateKeep);
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
    const stateDelete = rootState.createRootState(rootDelete, {});
    rootState.setState(rootDelete, stateDelete);
    rootState.publishState(stateDelete);
    assert.equal(rootState.getState(rootDelete), undefined);
    assert.equal(rootDelete.getAttribute("data-tiqian-enhanced"), null);
    assert.equal(rootDelete.getAttribute("data-tiqian-enhanced-count"), null);
    assert.equal(rootDelete.getAttribute("data-tiqian-issue-count"), null);

    // Issue count branch: issues present -> issue-count written.
    const rootIssue = new FakeElement();
    const stateIssue = rootState.createRootState(rootIssue, {});
    stateIssue.issues.push({ name: "MissingSharedRuntimeStyles" });
    rootState.publishState(stateIssue);
    assert.equal(rootIssue.getAttribute("data-tiqian-issue-count"), "1");
  } finally {
    restoreGlobals(saved);
  }
});

test("6. strandedSourceParagraphs: empty paragraphs returns all candidates; rendered sources subtracted", () => {
  const saved = preserveGlobals(ROOT_STATE_GLOBALS);
  try {
    const lifecycle = makeLifecycle();
    const fonts = makeCanvasFonts();
    const bridge = makeBridge();
    installGlobals({ lifecycle: lifecycle.lifecycle, fonts: fonts.fonts, bridge: bridge.bridge });

    const c0 = new FakeElement();
    const c1 = new FakeElement();
    const c2 = new FakeElement();
    const root = new FakeElement({ children: [c0, c1, c2] });

    const stateEmpty = { options: { paragraphSelector: "p, li" }, paragraphs: [] };
    assert.deepEqual(rootState.strandedSourceParagraphs(root, stateEmpty), [c0, c1, c2]);

    const stateRendered = {
      options: { paragraphSelector: "p, li" },
      paragraphs: [{ source: c0 }, { source: c2 }],
    };
    assert.deepEqual(rootState.strandedSourceParagraphs(root, stateRendered), [c1]);
  } finally {
    restoreGlobals(saved);
  }
});

test("7. paragraphCandidates: root-scope and eligibility filtering", () => {
  const saved = preserveGlobals(ROOT_STATE_GLOBALS);
  try {
    const lifecycle = makeLifecycle();
    const fonts = makeCanvasFonts();
    const bridge = makeBridge();
    const eligibilityCalls = [];
    installGlobals({
      lifecycle: lifecycle.lifecycle,
      fonts: fonts.fonts,
      bridge: bridge.bridge,
      eligibility: {
        shouldTryParagraph(paragraph) {
          eligibilityCalls.push(paragraph);
          return paragraph !== p3;
        },
      },
    });

    const root = new FakeElement();
    const innerEl = new FakeElement({ parent: root });
    const outsideEl = new FakeElement();
    // closest null -> owner absent, in scope, eligible.
    const p1 = new FakeElement({ parent: root });
    // owner lives outside the root -> in scope, eligible.
    const p2 = new FakeElement({ parent: root, closestFn: () => outsideEl });
    // owner is the root -> in scope but ineligible.
    const p3 = new FakeElement({ parent: root, closestFn: () => root });
    // owner is a descendant inside the root -> out of scope even when eligible.
    const p4 = new FakeElement({ parent: root, closestFn: () => innerEl });
    // no closest method -> defensive in-scope, eligible.
    const p5 = { tagName: "P", parent: root, textContent: "text" };
    root.querySelectorAllResult = [p1, p2, p3, p4, p5];

    const candidates = rootState.paragraphCandidates(root, "p, li");
    assert.deepEqual(candidates, [p1, p2, p5]);
    // p4 is rejected by the root-scope gate before eligibility is consulted.
    assert.equal(eligibilityCalls.length, 4);
  } finally {
    restoreGlobals(saved);
  }
});