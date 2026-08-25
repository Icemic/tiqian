import assert from "node:assert/strict";
import test from "node:test";

import { processParagraph } from "./core/engine/process-paragraph.js";

// All module seams are gone: eligibility, markdown lowering, the lifecycle
// helpers, the worker request serializer, the prepared-metadata builders and
// the direct prepare step run for real. Only the custody graph and the
// commit-prepared-paragraph graph are fake deps, plus the host-installed
// __TiqianLayoutWorker / __TiqianPreparedDomRenderer environment globals.

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

// Runs fn with the environment globals the real pipeline reads. The renderer
// is installed by default (the direct prepare path needs a live bridge); a
// test that wants the bridge-unavailable verdict passes renderer: false.
function withEnv(fn, overrides = {}) {
  const saved = saveGlobals([
    "getComputedStyle",
    "__TiqianPreparedDomRenderer",
    "__TiqianLayoutWorker",
    "document",
  ]);
  try {
    if (overrides.renderer !== false) {
      globalThis.__TiqianPreparedDomRenderer = {
        render: () => {},
        release: () => {},
        releaseRoot: () => {},
        schema: 1,
        layoutRevision: "tiqian-layout-v2",
      };
    }
    if (overrides.layoutWorker !== undefined) {
      globalThis.__TiqianLayoutWorker = overrides.layoutWorker;
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

const PASSING_ENVELOPE = JSON.stringify({
  plan: JSON.stringify({ lines: [{ rangeStart: 0, rangeEnd: 10 }] }),
  diagnostics: { capabilityIssues: [], advanceSuspects: [] },
});

function makeFakeFfi(overrides = {}) {
  const calls = { diagnostics: [], browserMetrics: [] };
  const envelope = overrides.envelope ?? PASSING_ENVELOPE;
  return {
    _calls: calls,
    classifyFontRole: (text, start, end, locale) => "latin",
    firstDivergentInlineShapingProperty: () => null,
    unsupportedInlineShapingProperties: () => ["font-weight", "font-style"],
    precomputeParagraphWithDiagnostics: function () {
      calls.diagnostics.push(Array.from(arguments));
      if (overrides.diagnosticsThrow) throw overrides.diagnosticsThrow;
      return envelope;
    },
    precomputeParagraphWithBrowserMetrics: function () {
      calls.browserMetrics.push(Array.from(arguments));
      return envelope;
    },
  };
}

function makeState(overrides = {}) {
  const issues = [];
  const paragraphs = [];
  const fallbacks = [];
  return {
    options: overrides.options !== undefined ? overrides.options : { fontSize: 19 },
    preparedDomEnabled: overrides.preparedDomEnabled ?? true,
    exactSession: overrides.exactSession ?? { sessionId: "session-1" },
    browserFallback: overrides.browserFallback ?? null,
    onIssue: (issue) => {
      issues.push(issue);
      if (overrides.onIssue) overrides.onIssue(issue);
    },
    onParagraphCommitted: (item) => {
      paragraphs.push(item);
      if (overrides.onParagraphCommitted) overrides.onParagraphCommitted(item);
    },
    onDisableExactPreparedDom: (detail) => {
      fallbacks.push(detail);
      if (overrides.onDisableExactPreparedDom) overrides.onDisableExactPreparedDom(detail);
    },
    issues,
    paragraphs,
    fallbacks,
  };
}

test("1. Direct happy path: lowering ok, custody begin called with 14 args, prepare ready, commit success", () => {
  withEnv(() => {
    const custodyBeginArgs = [];
    let custodyTakeCalled = false;
    let custodyCommitCalled = false;
    let custodyRestoreCalled = false;

    const custody = {
      begin: (...args) => custodyBeginArgs.push(args),
      take: () => { custodyTakeCalled = true; },
      commit: () => { custodyCommitCalled = true; },
      restoreParagraph: () => { custodyRestoreCalled = true; },
    };
    const commitPreparedParagraph = {
      commitWorkerPreparedParagraph: () => null,
      commitPreparedParagraph: () => ({ kind: "success", measure: 300 }),
    };
    const processParagraphDeps = { custody, commitPreparedParagraph };

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
    const ffi = makeFakeFfi();

    processParagraph(processParagraphDeps, { ffi, paragraph, state });

    assert.equal(custodyBeginArgs.length, 1);
    const args = custodyBeginArgs[0];
    assert.equal(args.length, 14);
    assert.equal(args[0], paragraph);
    assert.equal(args[1], "false"); // renderedAttribute
    assert.equal(args[6], "color: blue;"); // styleAttribute
    assert.equal(args[13], "300px"); // hostInlineSizeAttribute

    assert.equal(custodyTakeCalled, true);
    assert.equal(custodyCommitCalled, true);
    assert.equal(custodyRestoreCalled, false);
    assert.equal(paragraph.getAttribute("data-tq-rendered"), "true");
    assert.equal(paragraph.getAttribute("data-tq-runtime-render-font"), "true");

    assert.equal(state.paragraphs.length, 1);
    const item = state.paragraphs[0];
    assert.equal(item.source, paragraph);
    assert.equal(item.lastMeasure, 300);
    assert.equal(state.issues.length, 0);
  });
});

test("2. Worker happy path: worker request built, layout worker take returns a plan, worker commit called with plan and metadata JSONs", () => {
  const layoutWorker = {
    take: (el, sessionKey, req) => '{"plan":"{}"}',
    issue: () => null,
  };
  const documentStub = makeFakeDocument(30);
  withEnv(() => {
    const workerCommitCalls = [];

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

    const custody = {
      begin: () => {},
      take: () => {},
      commit: () => {},
      restoreParagraph: () => {},
    };
    const commitPreparedParagraph = {
      commitWorkerPreparedParagraph: (deps, argument) => {
        workerCommitCalls.push(argument);
        argument.paragraph.lastMeasure = 300;
        return null;
      },
      commitPreparedParagraph: () => {
        throw new Error("Direct commit should not be called in worker path");
      },
    };
    const processParagraphDeps = { custody, commitPreparedParagraph };

    const state = makeState({
      options: {
        fontSize: 19,
        strongAsEmphasisMarks: true,
        exactFontSession: { status: "conforming", sessionId: "session-1" },
      },
    });
    const ffi = makeFakeFfi();

    processParagraph(processParagraphDeps, { ffi, paragraph, state });

    assert.equal(workerCommitCalls.length, 1);
    const callArg = workerCommitCalls[0];
    assert.equal(callArg.workerPlan, '{"plan":"{}"}');
    assert.equal(callArg.onExactPreparedDomFallback, state.onDisableExactPreparedDom);
    assert.equal(
      callArg.inlineObjectMetaJson,
      '[{"start":5,"end":6,"marginRight":0}]'
    );
    assert.equal(
      callArg.cjkStrongSemanticsJson,
      '[{"start":0,"end":5,"weight":400}]'
    );

    assert.equal(state.paragraphs.length, 1);
    assert.equal(state.paragraphs[0].lastMeasure, 300);
    assert.equal(state.issues.length, 0);
  }, { layoutWorker, document: documentStub });
});

test("3. Lowering throw -> DomLoweringFailure reported, nothing after it runs (custody begin never called)", () => {
  const throwError = new Error("lowering syntax error");
  withEnv(() => {
    let custodyBeginCalled = false;

    const custody = {
      begin: () => { custodyBeginCalled = true; },
    };
    const processParagraphDeps = { custody, commitPreparedParagraph: {} };

    const paragraph = makeElement();
    const state = makeState();
    const ffi = makeFakeFfi();

    processParagraph(processParagraphDeps, { ffi, paragraph, state });

    assert.equal(custodyBeginCalled, false);
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
    let custodyBeginCalled = false;

    const custody = {
      begin: () => { custodyBeginCalled = true; },
    };
    const processParagraphDeps = { custody, commitPreparedParagraph: {} };

    const paragraph = makeElement({}, {}, {
      text: "blocked",
      childNodes: [blockChild("DIV", "blocked")],
    });
    const state = makeState();
    const ffi = makeFakeFfi();

    processParagraph(processParagraphDeps, { ffi, paragraph, state });

    assert.equal(custodyBeginCalled, false);
    assert.equal(state.issues.length, 1);
    assert.equal(state.issues[0].name, "UnsupportedInlineFormattingContext");
    assert.equal(state.issues[0].detail, "div:block");
    assert.equal(state.issues[0].element, paragraph);
    assert.equal(state.issues[0].reportToConsole, true);
    // The lifecycle marker was written onto the paragraph element.
    assert.equal(paragraph.getAttribute("data-tiqian-capability-issue"), "UnsupportedInlineFormattingContext");
  });
});

test("6. Exact worker gate: requireExactLayoutWorker true, worker request built, plan null, rich fallback not applicable -> style attribute restored, ExactLayoutWorkerPlanUnavailable", () => {
  const layoutWorker = {
    take: () => null,
    issue: () => "No worker available in this context",
  };
  withEnv(() => {
    let custodyTakeCalled = false;

    const custody = {
      begin: () => {},
      take: () => { custodyTakeCalled = true; },
      commit: () => {},
      restoreParagraph: () => {},
    };
    const processParagraphDeps = { custody, commitPreparedParagraph: {} };

    const paragraph = makeElement({ style: "margin: 10px;" });
    const state = makeState({
      options: {
        requireExactLayoutWorker: true,
        exactFontSession: { status: "conforming", sessionId: "session-1" },
      },
    });
    const ffi = makeFakeFfi();

    processParagraph(processParagraphDeps, { ffi, paragraph, state });

    assert.equal(paragraph.getAttribute("style"), "margin: 10px;");
    assert.equal(custodyTakeCalled, false);
    assert.equal(state.issues.length, 1);
    assert.equal(state.issues[0].name, "ExactLayoutWorkerPlanUnavailable");
    assert.equal(state.issues[0].detail, "No worker available in this context");
    assert.equal(state.issues[0].element, paragraph);
    assert.equal(state.issues[0].reportToConsole, true);
    // The lifecycle marker was written onto the paragraph element.
    assert.equal(paragraph.getAttribute("data-tiqian-capability-issue"), "ExactLayoutWorkerPlanUnavailable");
  }, { layoutWorker });
});

test("7. canUseRichBrowserFallback: rich lowered plus a capability-failure worker issue -> gate NOT taken, processing continues", () => {
  const layoutWorker = {
    take: () => null,
    issue: () => "MissingServerShapingReplay for CodeFont",
  };
  withEnv(() => {
    let custodyTakeCalled = false;
    let commitPreparedCalled = false;

    const custody = {
      begin: () => {},
      take: () => { custodyTakeCalled = true; },
      commit: () => {},
      restoreParagraph: () => {},
    };
    const commitPreparedParagraph = {
      commitWorkerPreparedParagraph: () => null,
      commitPreparedParagraph: () => {
        commitPreparedCalled = true;
        return { kind: "success", measure: 300 };
      },
    };
    const processParagraphDeps = { custody, commitPreparedParagraph };

    const paragraph = makeElement({}, {}, {
      text: "hello x",
      childNodes: [inlineChild("EM", "x", { "font-style": "italic" })],
    });
    const state = makeState({
      options: {
        requireExactLayoutWorker: true,
        exactFontSession: { status: "conforming", sessionId: "session-1" },
      },
    });
    const ffi = makeFakeFfi();

    processParagraph(processParagraphDeps, { ffi, paragraph, state });

    assert.equal(custodyTakeCalled, true);
    assert.equal(commitPreparedCalled, true);
    assert.equal(state.paragraphs.length, 1);
    assert.equal(state.issues.length, 0);
  }, { layoutWorker });
});

test("9. prepare unsupported -> issue reported, custody restored", () => {
  withEnv(() => {
    let custodyRestored = false;

    const paragraph = makeElement();
    const custody = {
      begin: () => {},
      take: () => {},
      commit: () => {},
      restoreParagraph: (el) => {
        if (el === paragraph) custodyRestored = true;
      },
    };
    const processParagraphDeps = { custody, commitPreparedParagraph: {} };

    const state = makeState();
    const ffi = makeFakeFfi();

    processParagraph(processParagraphDeps, { ffi, paragraph, state });

    assert.equal(custodyRestored, true);
    assert.equal(state.paragraphs.length, 0);
    assert.equal(state.issues.length, 1);
    assert.equal(state.issues[0].name, "PreparedDomBridgeUnavailable");
    assert.equal(state.issues[0].element, paragraph);
    // The lifecycle marker was written onto the paragraph element.
    assert.equal(paragraph.getAttribute("data-tiqian-capability-issue"), "PreparedDomBridgeUnavailable");
  }, { renderer: false });
});

test("10. commit unsupported -> issue reported, custody restored", () => {
  withEnv(() => {
    let custodyRestored = false;

    const paragraph = makeElement();
    const custody = {
      begin: () => {},
      take: () => {},
      commit: () => {},
      restoreParagraph: (el) => {
        if (el === paragraph) custodyRestored = true;
      },
    };
    const commitPreparedParagraph = {
      commitWorkerPreparedParagraph: () => null,
      commitPreparedParagraph: () => ({
        kind: "unsupported",
        name: "PreparedDomRenderMismatch",
        detail: "height mismatch",
        element: paragraph,
      }),
    };
    const processParagraphDeps = { custody, commitPreparedParagraph };

    const state = makeState();
    const ffi = makeFakeFfi();

    processParagraph(processParagraphDeps, { ffi, paragraph, state });

    assert.equal(custodyRestored, true);
    assert.equal(state.paragraphs.length, 0);
    assert.equal(state.issues.length, 1);
    assert.equal(state.issues[0].name, "PreparedDomRenderMismatch");
    assert.equal(state.issues[0].detail, "height mismatch");
    // The lifecycle marker was written onto the paragraph element.
    assert.equal(paragraph.getAttribute("data-tiqian-capability-issue"), "PreparedDomRenderMismatch");
  });
});

test("11. Dispatch throw -> WebEnhancementFailure, custody restored", () => {
  withEnv(() => {
    let custodyRestored = false;

    const paragraph = makeElement();
    const custody = {
      begin: () => {},
      take: () => {},
      commit: () => {},
      restoreParagraph: (el) => {
        if (el === paragraph) custodyRestored = true;
      },
    };
    const processParagraphDeps = { custody, commitPreparedParagraph: {} };

    const state = makeState();
    const ffi = makeFakeFfi({ diagnosticsThrow: new Error("unexpected engine crash") });

    processParagraph(processParagraphDeps, { ffi, paragraph, state });

    assert.equal(custodyRestored, true);
    assert.equal(state.paragraphs.length, 0);
    assert.equal(state.issues.length, 1);
    assert.equal(state.issues[0].name, "WebEnhancementFailure");
    assert.equal(state.issues[0].detail, "unexpected engine crash");
    // The lifecycle marker was written onto the paragraph element.
    assert.equal(paragraph.getAttribute("data-tiqian-capability-issue"), "WebEnhancementFailure");
  });
});

test("12. preparedDomEnabled false -> active options come from withoutExactFontSession", () => {
  withEnv(() => {
    let committedOptions = null;

    const custody = {
      begin: () => {},
      take: () => {},
      commit: () => {},
      restoreParagraph: () => {},
    };
    const commitPreparedParagraph = {
      commitWorkerPreparedParagraph: () => null,
      commitPreparedParagraph: (deps, argument) => {
        committedOptions = argument.options;
        return { kind: "success", measure: 300 };
      },
    };
    const processParagraphDeps = { custody, commitPreparedParagraph };

    const paragraph = makeElement();
    const rawOptions = { fontSize: 20, exactFontSession: { sessionId: "sess-abc" } };
    const state = makeState({
      options: rawOptions,
      preparedDomEnabled: false,
    });
    const ffi = makeFakeFfi();

    processParagraph(processParagraphDeps, { ffi, paragraph, state });

    // Active options dropped the exact font session through
    // withoutExactFontSession before the request and commit were prepared.
    assert.notEqual(committedOptions, rawOptions);
    assert.equal(committedOptions.fontSize, 20);
    assert.equal(committedOptions.exactFontSession, null);
    assert.equal(state.paragraphs.length, 1);
  });
});

test("13. absent layout worker channel reads as no reusable plan and the direct lane proceeds", () => {
  withEnv(() => {
    const custody = {
      begin: () => {},
      take: () => {},
      commit: () => {},
      restoreParagraph: () => {},
    };
    const commitPreparedParagraph = {
      commitWorkerPreparedParagraph: () => null,
      commitPreparedParagraph: () => ({ kind: "success", measure: 300 }),
    };
    const processParagraphDeps = { custody, commitPreparedParagraph };

    const paragraph = makeElement();
    const state = makeState();
    const ffi = makeFakeFfi();

    processParagraph(processParagraphDeps, { ffi, paragraph, state });

    assert.equal(ffi._calls.diagnostics.length, 1);
    assert.equal(state.paragraphs.length, 1);
    assert.equal(state.issues.length, 0);
  });
});