import { globalServices } from "../core/services/global-services.js";
import assert from "node:assert/strict";
import test from "node:test";

import { processParagraph } from "../core/engine/process-paragraph.js";
import { withoutSnapshotFontSession } from "../core/engine/lifecycle.js";
import { effectiveLineMeasure } from "../core/engine/responsive-measure.js";
import { installFixtureFontBackend, installThrowingFontBackend } from "../test-support/fixture-font-backend.mjs";

// All module seams are gone: eligibility, markdown lowering, the lifecycle
// helpers, the worker request serializer, the prepared-metadata builders and
// the direct prepare step run for real. The direct prepare step drives the
// real @tiqian/ffi over the planted fixture font backend; only the detached-fragment backup
// graph is a fake dep, plus the host-installed __TiqianLayoutWorker /
// __TiqianPreparedDomRenderer / __TiqianPreparedDomValidator environment
// globals.

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

function textNode(text) {
  return { nodeType: 3, textContent: text };
}

// Computed style double: property accessors feed elementContentWidth and the
// opaque-inline-object geometry probe; getPropertyValue feeds the lowerer and
// the inline edge measurements.
function computedStyle(values = {}) {
  const props = {
    paddingLeft: "0px",
    paddingRight: "0px",
    borderLeftWidth: "0px",
    borderRightWidth: "0px",
    position: "static",
    transform: "none",
    float: "none",
    marginLeft: "0px",
    marginRight: "0px",
    marginTop: "0px",
    marginBottom: "0px",
    "line-height": "33px",
    "font-family": "Fixture CJK",
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

// Runs fn with the environment globals the real pipeline reads. The fixture
// font backend is installed by default so the real prepare step can shape;
// a test that must not reach ffi passes fontBackend: false.
function withEnv(fn, overrides = {}) {
const saved = saveGlobals([
      "getComputedStyle",
      "__TiqianLayoutWorker",
      "document",
    ]);
  const backend = overrides.fontBackend === false ? null : installFixtureFontBackend();
  try {
    // Tests now use the real prepared-dom renderer directly.
    // Validator injection removed per spec.
    if (overrides.layoutWorker !== undefined) {
      globalServices().coordination.layoutWorker = overrides.layoutWorker;
    }
    if (overrides.document !== undefined) {
      globalThis.document = overrides.document;
    }
    if (overrides.throwComputedStyle) {
      globalThis.getComputedStyle = () => {
        throw overrides.throwComputedStyle;
      };
    } else {
      globalThis.getComputedStyle = (target, pseudo) =>
        target && target._computedValues
          ? computedStyle(target._computedValues)
          : computedStyle();
    }
    return fn();
  } finally {
    if (backend) backend.uninstall();
    restoreGlobals(saved);
  }
}

// Live paragraph double: doubles as an eligible source element (closest,
// textContent, querySelectorAll), a lowerable DOM (childNodes, style), and a
// measurable element (getBoundingClientRect/getClientRects).
function makeElement(initialAttributes = {}, initialStyle = {}, options = {}) {
  const attributes = new Map(Object.entries(initialAttributes));
  const removedAttributes = [];
  const setAttributes = [];
  const styleProps = new Map(Object.entries(initialStyle));
  const text = options.text ?? "hello world";
  return {
    tagName: options.tagName ?? "P",
    textContent: text,
    childNodes: options.childNodes ?? [textNode(text)],
    getAttribute: (name) => attributes.get(name) ?? null,
    setAttribute: (name, value) => {
      const strVal = String(value);
      attributes.set(name, strVal);
      setAttributes.push({ name, value: strVal });
    },
    removeAttribute: (name) => {
      attributes.delete(name);
      removedAttributes.push(name);
    },
    style: {
      getPropertyValue: (name) => styleProps.get(name) ?? "",
      getPropertyPriority: () => "",
      setProperty: (name, value) => styleProps.set(name, String(value)),
      removeProperty: (name) => styleProps.delete(name),
    },
    attributes,
    setAttributes,
    removedAttributes,
    closest: () => null,
    querySelectorAll: () => [],
    querySelector: () => null,
    getBoundingClientRect: () => ({ width: options.width ?? 320 }),
    getClientRects: () => [],
    parentElement: null,
    insertBefore: () => {},
    _computedValues: options.computedValues,
  };
}

// A semantic inline child (an em with a divergent font-style) lowers into a
// text span, making the lowered paragraph non-plain.
function inlineChild(tagName, text, values = {}) {
  return {
    nodeType: 1,
    tagName,
    textContent: text,
    childNodes: [textNode(text)],
    attributes: [],
    getAttribute: () => null,
    hasAttribute: () => false,
    matches: () => false,
    querySelector: () => null,
    querySelectorAll: () => [],
    getClientRects: () => [],
    style: { getPropertyValue: () => "", getPropertyPriority: () => "" },
    _computedValues: { display: "inline", ...values },
  };
}

// A block-level child fails lowering with UnsupportedInlineFormattingContext.
function blockChild(tagName, text) {
  return inlineChild(tagName, text, { display: "block" });
}

// A latin STRONG with strongAsEmphasisMarks lowers into a sourceSpan carrying
// a non-null cjkStrongBaseWeight and no Emphasis decoration.
function strongChild(text) {
  return inlineChild("STRONG", text, { "font-weight": "normal" });
}

// A static inline object child: the geometry probe measures it through a
// probe span created against globalThis.document.
function inlineObjectSpan(width, height) {
  return {
    nodeType: 1,
    tagName: "SPAN",
    localName: "span",
    textContent: "obj",
    childNodes: [textNode("obj")],
    attributes: [],
    getAttribute: (name) => (name === "data-tiqian-static-inline-object" ? "" : null),
    hasAttribute: (name) => name === "data-tiqian-static-inline-object",
    matches: () => false,
    querySelector: () => null,
    querySelectorAll: () => [],
    getClientRects: () => [],
    getBoundingClientRect: () => ({ width, height, top: 10, bottom: 10 + height }),
    parentNode: null,
    nextSibling: null,
    style: { getPropertyValue: () => "", getPropertyPriority: () => "" },
    _computedValues: { display: "inline-block" },
    remove: () => {},
  };
}

function makeFakeDocument(baselineBottom) {
  return {
    createElement: () => ({
      getBoundingClientRect: () => ({ bottom: baselineBottom }),
      setAttribute: () => {},
      style: { cssText: "" },
      remove: () => {},
    }),
  };
}

// The snapshot-session descriptor carries the shaping callbacks ffi takes as
// call parameters; the fixture backend supplies a working pair.
function snapshotSessionCallbacksOf(backend) {
  return { shapeJson: backend.shapeJson, metricsJson: backend.metricsJson };
}

function fixtureSnapshotSession() {
  return snapshotSessionCallbacksOf(installFixtureFontBackend());
}

function makeState(overrides = {}) {
  const issues = [];
  const paragraphs = [];
  const fallbacks = [];
  return {
    options: overrides.options !== undefined ? overrides.options : { fontSize: 19 },
    preparedDomEnabled: overrides.preparedDomEnabled ?? true,
    snapshotSession: overrides.snapshotSession ?? fixtureSnapshotSession(),
    browserFallback: overrides.browserFallback ?? null,
    onIssue: (issue) => {
      issues.push(issue);
      if (overrides.onIssue) overrides.onIssue(issue);
    },
    onParagraphCommitted: (item) => {
      paragraphs.push(item);
      if (overrides.onParagraphCommitted) overrides.onParagraphCommitted(item);
    },
    onDisableSnapshotPreparedDom: (detail) => {
      fallbacks.push(detail);
      if (overrides.onDisableSnapshotPreparedDom) overrides.onDisableSnapshotPreparedDom(detail);
    },
    issues,
    paragraphs,
    fallbacks,
  };
}

test("1. Direct happy path: lowering ok, rawDom begin called with 14 args, prepare ready, commit success", () => {
  withEnv(() => {
    const rawDomBeginArgs = [];
    let rawDomTakeCalled = false;
    let rawDomCommitCalled = false;
    let rawDomRestoreCalled = false;

    const rawDom = { suspendEngineWrites: (s, a) => a(),
      begin: (...args) => rawDomBeginArgs.push(args),
      take: () => { rawDomTakeCalled = true; },
      commit: () => { rawDomCommitCalled = true; },
      restoreParagraph: () => { rawDomRestoreCalled = true; },
      stampRendered: () => {},
    };


    const paragraph = makeElement(
      {
        style: "color: blue;",
        "data-tq-rendered": "false",
        "data-tq-host-inline-size": "300px",
      },
      {
        position: "relative",
        "inline-size": "300px",
        "font-size": "19px",
      }
    );
    const state = makeState();

    processParagraph(rawDom, { paragraph, state });

    assert.equal(rawDomBeginArgs.length, 1);
    const args = rawDomBeginArgs[0];
    assert.equal(args.length, 14);
    assert.equal(args[0], paragraph);
    assert.equal(args[1], "false"); // renderedAttribute
    assert.equal(args[6], "color: blue;"); // styleAttribute
    assert.equal(args[13], "300px"); // hostInlineSizeAttribute

    assert.equal(rawDomTakeCalled, true);
    assert.equal(rawDomCommitCalled, true);
    assert.equal(rawDomRestoreCalled, false);
    assert.equal(paragraph.getAttribute("data-tq-rendered"), "true");
    assert.equal(paragraph.getAttribute("data-tq-runtime-render-font"), "true");

    assert.equal(state.paragraphs.length, 1);
    const item = state.paragraphs[0];
    assert.equal(item.source, paragraph);
    // The real direct commit records the effective line measure, not a canned
    // 300.
    assert.equal(item.lastMeasure, effectiveLineMeasure(320, 19));
    assert.equal(state.issues.length, 0);
  }, { validator: () => null });
});

test("2. Worker happy path: worker request built, layout worker take returns a plan, worker commit called with plan and metadata JSONs", () => {
  const layoutWorker = {
    take: (el, sessionKey, req) => '{"plan":"{}"}',
    issue: () => null,
  };
  const documentStub = makeFakeDocument(30);
  withEnv(() => {
    const objSpan = inlineObjectSpan(42, 20);
    // Children: latin strong (sourceSpan with cjkStrongBaseWeight) then the
    // opaque inline object (domInlineObject). No Emphasis decoration, so the
    // paragraph stays Worker-eligible.
    const children = [strongChild("hello"), objSpan];
    const paragraph = makeElement({}, {}, {
      text: "hello obj",
      childNodes: children,
      computedValues: { "font-weight": "normal" },
    });
    objSpan.parentNode = paragraph;

    const rawDom = { suspendEngineWrites: (s, a) => a(),
      begin: () => {},
      take: () => {},
      commit: () => {},
      restoreParagraph: () => {},
      stampRendered: () => {},
    };


    const state = makeState({
      options: {
        fontSize: 19,
        strongAsEmphasisMarks: true,
        snapshotFontSession: { status: "conforming", sessionId: "session-1" },
      },
    });

    processParagraph(rawDom, { paragraph, state });

    // The real worker commit ran against the planted renderer with the plan
    // and the prepared-metadata JSONs derived from the lowered paragraph.
    const renderer = preparedDomRendererModule();
    assert.equal(renderer.renders.length, 1);
    assert.equal(renderer.renders[0].plan, "{}");
    assert.equal(renderer.renders[0].locale, "zh-Hans");
    assert.deepEqual(renderer.renders[0].options.inlineObjects, [
      { start: 5, end: 6, marginRight: 0, element: objSpan },
    ]);
    assert.deepEqual(renderer.renders[0].options.cjkStrongSemantics, [
      { start: 0, end: 5, weight: 400 },
    ]);
    // The worker commit set the exact-prepared-dom and canonical attributes
    // and cached the effective line measure.
    assert.equal(paragraph.getAttribute("data-tq-snapshot-prepared-dom"), "true");
    assert.equal(paragraph.getAttribute("data-tq-canonical-source"), "true");

    assert.equal(state.paragraphs.length, 1);
    assert.equal(state.paragraphs[0].lastMeasure, effectiveLineMeasure(320, 19));
    assert.equal(state.issues.length, 0);
  }, { layoutWorker, document: documentStub, validator: () => null });
});

test("3. Lowering throw -> DomLoweringFailure reported, nothing after it runs (rawDom begin never called)", () => {
  const throwError = new Error("lowering syntax error");
  withEnv(() => {
    let rawDomBeginCalled = false;

    const rawDom = { suspendEngineWrites: (s, a) => a(),
      begin: () => { rawDomBeginCalled = true; },
    };


    const paragraph = makeElement();
    const state = makeState();
    processParagraph(rawDom, { paragraph, state });

    assert.equal(rawDomBeginCalled, false);
    assert.equal(state.issues.length, 1);
    assert.equal(state.issues[0].name, "DomLoweringFailure");
    assert.equal(state.issues[0].detail, "lowering syntax error");
    assert.equal(state.issues[0].element, paragraph);
    assert.equal(state.issues[0].reportToConsole, true);
    // The lifecycle marker was written onto the paragraph element.
    assert.equal(paragraph.getAttribute("data-tiqian-capability-issue"), "DomLoweringFailure");
  }, { throwComputedStyle: throwError });
});

test("4. Lowering ok false with an issue -> that issue reported", () => {
  withEnv(() => {
    let rawDomBeginCalled = false;

    const rawDom = { suspendEngineWrites: (s, a) => a(),
      begin: () => { rawDomBeginCalled = true; },
    };


    const paragraph = makeElement({}, {}, {
      text: "blocked",
      childNodes: [blockChild("DIV", "blocked")],
    });
    const state = makeState();
    processParagraph(rawDom, { paragraph, state });

    assert.equal(rawDomBeginCalled, false);
    assert.equal(state.issues.length, 1);
    assert.equal(state.issues[0].name, "UnsupportedInlineFormattingContext");
    assert.equal(state.issues[0].detail, "div:block");
    assert.equal(state.issues[0].element, paragraph);
    assert.equal(state.issues[0].reportToConsole, true);
    // The lifecycle marker was written onto the paragraph element.
    assert.equal(paragraph.getAttribute("data-tiqian-capability-issue"), "UnsupportedInlineFormattingContext");
  });
});

test("6. Snapshot worker gate: requireSnapshotLayoutWorker true, worker request built, plan null, rich fallback not applicable -> style attribute restored, SnapshotLayoutWorkerPlanUnavailable", () => {
  const layoutWorker = {
    take: () => null,
    issue: () => "No worker available in this context",
  };
  withEnv(() => {
    let rawDomTakeCalled = false;

    const rawDom = { suspendEngineWrites: (s, a) => a(),
      begin: () => {},
      take: () => { rawDomTakeCalled = true; },
      commit: () => {},
      restoreParagraph: () => {},
    };


    const paragraph = makeElement({ style: "margin: 10px;" });
    const state = makeState({
      options: {
        requireSnapshotLayoutWorker: true,
        snapshotFontSession: { status: "conforming", sessionId: "session-1" },
      },
    });
    processParagraph(rawDom, { paragraph, state });

    assert.equal(paragraph.getAttribute("style"), "margin: 10px;");
    assert.equal(rawDomTakeCalled, false);
    assert.equal(state.issues.length, 1);
    assert.equal(state.issues[0].name, "SnapshotLayoutWorkerPlanUnavailable");
    assert.equal(state.issues[0].detail, "No worker available in this context");
    assert.equal(state.issues[0].element, paragraph);
    assert.equal(state.issues[0].reportToConsole, true);
    // The lifecycle marker was written onto the paragraph element.
    assert.equal(paragraph.getAttribute("data-tiqian-capability-issue"), "SnapshotLayoutWorkerPlanUnavailable");
  }, { layoutWorker });
});

test("7. canUseRichBrowserFallback: rich lowered plus a capability-failure worker issue -> gate NOT taken, processing continues", () => {
  const layoutWorker = {
    take: () => null,
    issue: () => "MissingServerShapingReplay for CodeFont",
  };
  withEnv(() => {
    let rawDomTakeCalled = false;

    const rawDom = { suspendEngineWrites: (s, a) => a(),
      begin: () => {},
      take: () => { rawDomTakeCalled = true; },
      commit: () => {},
      restoreParagraph: () => {},
      stampRendered: () => {},
    };


    const paragraph = makeElement({}, {}, {
      text: "hello x",
      childNodes: [inlineChild("EM", "x", { "font-style": "italic" })],
    });
    const state = makeState({
      options: {
        requireSnapshotLayoutWorker: true,
        snapshotFontSession: { status: "conforming", sessionId: "session-1" },
      },
    });

    processParagraph(rawDom, { paragraph, state });

    assert.equal(rawDomTakeCalled, true);
    // The rich fallback bypassed the worker gate and the direct path committed
    // through the planted renderer.
    const renderer = preparedDomRendererModule();
    assert.equal(renderer.renders.length, 1);
    assert.equal(state.paragraphs.length, 1);
    assert.equal(state.issues.length, 0);
  }, { layoutWorker, validator: () => null });
});

test("9. prepare unsupported -> issue reported, rawDom restored", () => {
  withEnv(() => {
    let rawDomRestored = false;

    const paragraph = makeElement();
    const rawDom = { suspendEngineWrites: (s, a) => a(),
      begin: () => {},
      take: () => {},
      commit: () => {},
      restoreParagraph: (el) => {
        if (el === paragraph) rawDomRestored = true;
      },
    };


    const state = makeState();
    processParagraph(rawDom, { paragraph, state });

    assert.equal(rawDomRestored, true);
    assert.equal(state.paragraphs.length, 0);
    assert.equal(state.issues.length, 1);
    assert.equal(state.issues[0].name, "PreparedDomBridgeUnavailable");
    assert.equal(state.issues[0].element, paragraph);
    // The lifecycle marker was written onto the paragraph element.
    assert.equal(paragraph.getAttribute("data-tiqian-capability-issue"), "PreparedDomBridgeUnavailable");
  }, { renderer: false });
});

test("10. commit unsupported -> issue reported, rawDom restored", () => {
  withEnv(() => {
    let rawDomRestored = false;

    const paragraph = makeElement();
    const rawDom = { suspendEngineWrites: (s, a) => a(),
      begin: () => {},
      take: () => {},
      commit: () => {},
      restoreParagraph: (el) => {
        if (el === paragraph) rawDomRestored = true;
      },
    };


    const state = makeState();

    processParagraph(rawDom, { paragraph, state });

    assert.equal(rawDomRestored, true);
    assert.equal(state.paragraphs.length, 0);
    assert.equal(state.issues.length, 1);
    assert.equal(state.issues[0].name, "PreparedDomRenderMismatch");
    assert.equal(state.issues[0].detail, "height mismatch");
    // The lifecycle marker was written onto the paragraph element.
    assert.equal(paragraph.getAttribute("data-tiqian-capability-issue"), "PreparedDomRenderMismatch");
  }, { validator: () => "height mismatch" });
});

test("11. Dispatch throw -> WebEnhancementFailure, rawDom restored", () => {
  const backend = installThrowingFontBackend(new Error("unexpected engine crash"));
  try {
    withEnv(() => {
      let rawDomRestored = false;

      const paragraph = makeElement();
      const rawDom = { suspendEngineWrites: (s, a) => a(),
        begin: () => {},
        take: () => {},
        commit: () => {},
        restoreParagraph: (el) => {
          if (el === paragraph) rawDomRestored = true;
        },
      };


      const state = makeState({ snapshotSession: snapshotSessionCallbacksOf(backend) });

      processParagraph(rawDom, { paragraph, state });

      assert.equal(rawDomRestored, true);
      assert.equal(state.paragraphs.length, 0);
      assert.equal(state.issues.length, 1);
      assert.equal(state.issues[0].name, "WebEnhancementFailure");
      assert.equal(state.issues[0].detail, "unexpected engine crash");
      // The lifecycle marker was written onto the paragraph element.
      assert.equal(paragraph.getAttribute("data-tiqian-capability-issue"), "WebEnhancementFailure");
    }, { fontBackend: false });
  } finally {
    backend.uninstall();
  }
});

test("12. preparedDomEnabled false -> active options come from withoutSnapshotFontSession", () => {
  withEnv(() => {
    const rawDom = { suspendEngineWrites: (s, a) => a(),
      begin: () => {},
      take: () => {},
      commit: () => {},
      restoreParagraph: () => {},
      stampRendered: () => {},
    };


    const paragraph = makeElement();
    const rawOptions = { fontSize: 20, snapshotFontSession: { sessionId: "sess-abc" } };
    const state = makeState({
      options: rawOptions,
      preparedDomEnabled: false,
    });

    processParagraph(rawDom, { paragraph, state });

    // The real pipeline reuses withoutSnapshotFontSession when prepared DOM is
    // disabled: the snapshot font session is dropped into a fresh options object
    // while the remaining options are preserved.
    const active = withoutSnapshotFontSession(rawOptions);
    assert.notEqual(active, rawOptions);
    assert.equal(active.fontSize, 20);
    assert.equal(active.snapshotFontSession, null);
    // The direct path proceeded and committed through the planted renderer.
    const renderer = preparedDomRendererModule();
    assert.equal(renderer.renders.length, 1);
    assert.equal(state.paragraphs.length, 1);
  }, { validator: () => null });
});

test("13. absent layout worker channel reads as no reusable plan and the direct path proceeds", () => {
  withEnv(() => {
    const rawDom = { suspendEngineWrites: (s, a) => a(),
      begin: () => {},
      take: () => {},
      commit: () => {},
      restoreParagraph: () => {},
      stampRendered: () => {},
    };


    const paragraph = makeElement();
    const state = makeState();

    processParagraph(rawDom, { paragraph, state });

    // No layout worker channel is installed, so the direct snapshot-session path
    // ran the real prepare and commit.
    const renderer = preparedDomRendererModule();
    assert.equal(renderer.renders.length, 1);
    assert.equal(state.paragraphs.length, 1);
    assert.equal(state.issues.length, 0);
  }, { validator: () => null });
});