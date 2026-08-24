import assert from "node:assert/strict";
import test from "node:test";

import "./core/engine/prepared-metadata.js";
import "./core/engine/process-paragraph.js";
import { setMarkdownLoweringForTest } from "./core/engine/markdown-lowering.js";

const processParagraph = globalThis.__TiqianProcessParagraph.processParagraph;

const PROCESS_GLOBALS = [
  "__TiqianProcessParagraph",
  "__TiqianEligibility",
    "__TiqianLifecycle",
  "__TiqianCustody",
  "__TiqianWorkerRequest",
  "__TiqianLayoutWorker",
  "__TiqianPrepareParagraphLayout",
  "__TiqianCommitPreparedParagraph",
];

// The lowerer stub goes through setMarkdownLoweringForTest because
// process-paragraph.js imports lower() as a module binding from
// markdown-lowering.js; every finally resets it beside the globals.

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

function makeElement(initialAttributes = {}, initialStyle = {}) {
  const attributes = new Map(Object.entries(initialAttributes));
  const removedAttributes = [];
  const setAttributes = [];
  const styleProps = new Map(Object.entries(initialStyle));
  return {
    tagName: "P",
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
    },
    attributes,
    setAttributes,
    removedAttributes,
  };
}

function makeLowered(overrides = {}) {
  return {
    text: "你好世界",
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
    ...overrides,
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

function makeFakeFfi() {
  return {
    classifyFontRole: (family) => (family.includes("Noto") ? "Cjk" : "Latin"),
    firstDivergentInlineShapingProperty: () => null,
    unsupportedInlineShapingProperties: () => ["font-weight", "font-style"],
  };
}

test("1. Direct happy path: lowering ok, custody begin called with 14 args, prepare ready, commit success", () => {
  const saved = preserveGlobals(PROCESS_GLOBALS);
  try {
    const custodyBeginArgs = [];
    let custodyTakeCalled = false;
    let custodyCommitCalled = false;
    let custodyRestoreCalled = false;
    const reportedIssues = [];

    globalThis.__TiqianEligibility = {
      shouldTryParagraph: () => true,
    };
    setMarkdownLoweringForTest(() => ({ ok: true, lowered: makeLowered() }));

    globalThis.__TiqianLifecycle = {
      applyConfiguredHostFontSize: () => true,
      captureSourceInlineSize: () => ({ borderBoxWidth: 320, contentBoxWidth: 300 }),
      withoutExactFontSession: (opt) => opt,
      conformingExactFontSessionId: () => null,
      stabilizeContentSizedItemInlineSize: () => true,
      reportIssue: (issue) => reportedIssues.push(issue),
    };
    globalThis.__TiqianCustody = {
      begin: (...args) => custodyBeginArgs.push(args),
      take: () => { custodyTakeCalled = true; },
      commit: () => { custodyCommitCalled = true; },
      restoreParagraph: () => { custodyRestoreCalled = true; },
    };
    globalThis.__TiqianWorkerRequest = {
      workerLayoutRequest: () => null,
    };
    globalThis.__TiqianLayoutWorker = {
      take: () => null,
      issue: () => null,
    };
    globalThis.__TiqianPrepareParagraphLayout = {
      prepareParagraphLayout: () => ({
        kind: "ready",
        planJson: "{}",
        width: 320,
        measure: 300,
        exactFontSessionUsed: true,
      }),
    };
    globalThis.__TiqianCommitPreparedParagraph = {
      commitPreparedParagraph: () => ({
        kind: "success",
        measure: 300,
      }),
      commitWorkerPreparedParagraph: () => null,
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
    const ffi = makeFakeFfi();

    processParagraph({ ffi, paragraph, state });

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
  } finally {
    restoreGlobals(saved);
    setMarkdownLoweringForTest(null);
  }
});

test("2. Worker happy path: worker request built, layout worker take returns a plan, worker commit called with plan and metadata JSONs", () => {
  const saved = preserveGlobals(PROCESS_GLOBALS);
  try {
    const workerCommitCalls = [];

    globalThis.__TiqianEligibility = {
      shouldTryParagraph: () => true,
    };
    const lowered = makeLowered({
      domInlineObjects: [{ start: 0, end: 1, marginRight: 4 }],
      sourceSpans: [{ start: 0, end: 2, cjkStrongBaseWeight: 700, depth: 0, element: { tagName: "STRONG" } }],
    });
    setMarkdownLoweringForTest(() => ({ ok: true, lowered }));

    globalThis.__TiqianLifecycle = {
      applyConfiguredHostFontSize: () => false,
      captureSourceInlineSize: () => ({ borderBoxWidth: 320, contentBoxWidth: 300 }),
      withoutExactFontSession: (opt) => opt,
      conformingExactFontSessionId: () => "session-1",
      stabilizeContentSizedItemInlineSize: () => null,
      reportIssue: () => {},
    };
    globalThis.__TiqianCustody = {
      begin: () => {},
      take: () => {},
      commit: () => {},
      restoreParagraph: () => {},
    };
    globalThis.__TiqianWorkerRequest = {
      workerLayoutRequest: () => '{"text":"worker-req"}',
    };
    globalThis.__TiqianLayoutWorker = {
      take: (el, sessionKey, req) => '{"plan":"{}"}',
      issue: () => null,
    };
    globalThis.__TiqianCommitPreparedParagraph = {
      commitWorkerPreparedParagraph: (arg) => {
        workerCommitCalls.push(arg);
        arg.paragraph.lastMeasure = 300;
        return null;
      },
      commitPreparedParagraph: () => {
        throw new Error("Direct commit should not be called in worker path");
      },
    };

    const paragraph = makeElement();
    const state = makeState();
    const ffi = makeFakeFfi();

    processParagraph({ ffi, paragraph, state });

    assert.equal(workerCommitCalls.length, 1);
    const callArg = workerCommitCalls[0];
    assert.equal(callArg.workerPlan, '{"plan":"{}"}');
    assert.equal(callArg.onExactPreparedDomFallback, state.onDisableExactPreparedDom);
    assert.equal(
      callArg.inlineObjectMetaJson,
      '[{"start":0,"end":1,"marginRight":4}]'
    );
    assert.equal(
      callArg.cjkStrongSemanticsJson,
      '[{"start":0,"end":2,"weight":700}]'
    );

    assert.equal(state.paragraphs.length, 1);
    assert.equal(state.paragraphs[0].lastMeasure, 300);
    assert.equal(state.issues.length, 0);
  } finally {
    restoreGlobals(saved);
    setMarkdownLoweringForTest(null);
  }
});

test("3. Lowering throw -> DomLoweringFailure reported, nothing after it runs (custody begin never called)", () => {
  const saved = preserveGlobals(PROCESS_GLOBALS);
  try {
    let custodyBeginCalled = false;
    const reportedLifecycleIssues = [];

    globalThis.__TiqianEligibility = {
      shouldTryParagraph: () => true,
    };
    setMarkdownLoweringForTest(() => {
      throw new Error("lowering syntax error");
    });

    globalThis.__TiqianLifecycle = {
      reportIssue: (issue) => reportedLifecycleIssues.push(issue),
    };
    globalThis.__TiqianCustody = {
      begin: () => { custodyBeginCalled = true; },
    };

    const paragraph = makeElement();
    const state = makeState();
    const ffi = makeFakeFfi();

    processParagraph({ ffi, paragraph, state });

    assert.equal(custodyBeginCalled, false);
    assert.equal(state.issues.length, 1);
    assert.equal(state.issues[0].name, "DomLoweringFailure");
    assert.equal(state.issues[0].detail, "lowering syntax error");
    assert.equal(state.issues[0].element, paragraph);
    assert.equal(state.issues[0].reportToConsole, true);
    assert.equal(reportedLifecycleIssues.length, 1);
    assert.equal(reportedLifecycleIssues[0].name, "DomLoweringFailure");
  } finally {
    restoreGlobals(saved);
    setMarkdownLoweringForTest(null);
  }
});

test("4. Lowering ok false with an issue -> that issue reported", () => {
  const saved = preserveGlobals(PROCESS_GLOBALS);
  try {
    let custodyBeginCalled = false;
    const reportedLifecycleIssues = [];

    globalThis.__TiqianEligibility = {
      shouldTryParagraph: () => true,
    };
    setMarkdownLoweringForTest(() => ({
      ok: false,
      issue: {
        name: "UnsupportedInlineTag",
        detail: "TAG:DIV",
      },
    }));

    globalThis.__TiqianLifecycle = {
      reportIssue: (issue) => reportedLifecycleIssues.push(issue),
    };
    globalThis.__TiqianCustody = {
      begin: () => { custodyBeginCalled = true; },
    };

    const paragraph = makeElement();
    const state = makeState();
    const ffi = makeFakeFfi();

    processParagraph({ ffi, paragraph, state });

    assert.equal(custodyBeginCalled, false);
    assert.equal(state.issues.length, 1);
    assert.equal(state.issues[0].name, "UnsupportedInlineTag");
    assert.equal(state.issues[0].detail, "TAG:DIV");
    assert.equal(state.issues[0].element, paragraph);
    assert.equal(state.issues[0].reportToConsole, true);
    assert.equal(reportedLifecycleIssues.length, 1);
  } finally {
    restoreGlobals(saved);
    setMarkdownLoweringForTest(null);
  }
});

test("5. Lowering ok false without an issue -> UnsupportedParagraph", () => {
  const saved = preserveGlobals(PROCESS_GLOBALS);
  try {
    let custodyBeginCalled = false;
    const reportedLifecycleIssues = [];

    globalThis.__TiqianEligibility = {
      shouldTryParagraph: () => true,
    };
    setMarkdownLoweringForTest(() => ({ ok: false }));

    globalThis.__TiqianLifecycle = {
      reportIssue: (issue) => reportedLifecycleIssues.push(issue),
    };
    globalThis.__TiqianCustody = {
      begin: () => { custodyBeginCalled = true; },
    };

    const paragraph = makeElement();
    const state = makeState();
    const ffi = makeFakeFfi();

    processParagraph({ ffi, paragraph, state });

    assert.equal(custodyBeginCalled, false);
    assert.equal(state.issues.length, 1);
    assert.equal(state.issues[0].name, "UnsupportedParagraph");
    assert.equal(state.issues[0].detail, "paragraph could not be lowered");
    assert.equal(state.issues[0].element, paragraph);
    assert.equal(state.issues[0].reportToConsole, true);
    assert.equal(reportedLifecycleIssues.length, 1);
  } finally {
    restoreGlobals(saved);
    setMarkdownLoweringForTest(null);
  }
});

test("6. Exact worker gate: requireExactLayoutWorker true, worker request built, plan null, rich fallback not applicable -> style attribute restored, ExactLayoutWorkerPlanUnavailable", () => {
  const saved = preserveGlobals(PROCESS_GLOBALS);
  try {
    let custodyTakeCalled = false;
    const reportedLifecycleIssues = [];

    globalThis.__TiqianEligibility = {
      shouldTryParagraph: () => true,
    };
    setMarkdownLoweringForTest(() => ({ ok: true, lowered: makeLowered() })); // plain lowered

    globalThis.__TiqianLifecycle = {
      applyConfiguredHostFontSize: () => false,
      captureSourceInlineSize: () => ({ borderBoxWidth: 320, contentBoxWidth: 300 }),
      withoutExactFontSession: (opt) => opt,
      conformingExactFontSessionId: () => "session-1",
      reportIssue: (issue) => reportedLifecycleIssues.push(issue),
    };
    globalThis.__TiqianCustody = {
      begin: () => {},
      take: () => { custodyTakeCalled = true; },
      commit: () => {},
      restoreParagraph: () => {},
    };
    globalThis.__TiqianWorkerRequest = {
      workerLayoutRequest: () => '{"text":"worker-req"}',
    };
    globalThis.__TiqianLayoutWorker = {
      take: () => null,
      issue: () => "No worker available in this context",
    };

    const paragraph = makeElement({ style: "margin: 10px;" });
    const state = makeState({
      options: { requireExactLayoutWorker: true },
    });
    const ffi = makeFakeFfi();

    processParagraph({ ffi, paragraph, state });

    assert.equal(paragraph.getAttribute("style"), "margin: 10px;");
    assert.equal(custodyTakeCalled, false);
    assert.equal(state.issues.length, 1);
    assert.equal(state.issues[0].name, "ExactLayoutWorkerPlanUnavailable");
    assert.equal(state.issues[0].detail, "No worker available in this context");
    assert.equal(state.issues[0].element, paragraph);
    assert.equal(state.issues[0].reportToConsole, true);
    assert.equal(reportedLifecycleIssues.length, 1);
  } finally {
    restoreGlobals(saved);
    setMarkdownLoweringForTest(null);
  }
});

test("7. canUseRichBrowserFallback: rich lowered plus a capability-failure worker issue -> gate NOT taken, processing continues", () => {
  const saved = preserveGlobals(PROCESS_GLOBALS);
  try {
    let custodyTakeCalled = false;
    let commitPreparedCalled = false;

    globalThis.__TiqianEligibility = {
      shouldTryParagraph: () => true,
    };
    const richLowered = makeLowered({
      spans: [{ start: 0, end: 2, style: { fontFamilies: ["CodeFont"], fontSize: 19, fontWeight: 400, italic: false, baselineShift: 0, locale: "zh-Hans" } }],
    });
    setMarkdownLoweringForTest(() => ({ ok: true, lowered: richLowered }));

    globalThis.__TiqianLifecycle = {
      applyConfiguredHostFontSize: () => false,
      captureSourceInlineSize: () => ({ borderBoxWidth: 320, contentBoxWidth: 300 }),
      withoutExactFontSession: (opt) => opt,
      conformingExactFontSessionId: () => "session-1",
      stabilizeContentSizedItemInlineSize: () => null,
      reportIssue: () => {},
    };
    globalThis.__TiqianCustody = {
      begin: () => {},
      take: () => { custodyTakeCalled = true; },
      commit: () => {},
      restoreParagraph: () => {},
    };
    globalThis.__TiqianWorkerRequest = {
      workerLayoutRequest: () => '{"text":"worker-req"}',
    };
    globalThis.__TiqianLayoutWorker = {
      take: () => null,
      issue: () => "MissingServerShapingReplay for CodeFont",
    };
    globalThis.__TiqianPrepareParagraphLayout = {
      prepareParagraphLayout: () => ({
        kind: "ready",
        planJson: "{}",
        width: 320,
        measure: 300,
        exactFontSessionUsed: false,
      }),
    };
    globalThis.__TiqianCommitPreparedParagraph = {
      commitPreparedParagraph: () => {
        commitPreparedCalled = true;
        return { kind: "success", measure: 300 };
      },
    };

    const paragraph = makeElement();
    const state = makeState({
      options: { requireExactLayoutWorker: true },
    });
    const ffi = makeFakeFfi();

    processParagraph({ ffi, paragraph, state });

    assert.equal(custodyTakeCalled, true);
    assert.equal(commitPreparedCalled, true);
    assert.equal(state.paragraphs.length, 1);
    assert.equal(state.issues.length, 0);
  } finally {
    restoreGlobals(saved);
    setMarkdownLoweringForTest(null);
  }
});

test("8. prepare unchanged -> item committed, no commit call", () => {
  const saved = preserveGlobals(PROCESS_GLOBALS);
  try {
    let commitPreparedCalled = false;

    globalThis.__TiqianEligibility = {
      shouldTryParagraph: () => true,
    };
    setMarkdownLoweringForTest(() => ({ ok: true, lowered: makeLowered() }));

    globalThis.__TiqianLifecycle = {
      applyConfiguredHostFontSize: () => false,
      captureSourceInlineSize: () => ({ borderBoxWidth: 320, contentBoxWidth: 300 }),
      withoutExactFontSession: (opt) => opt,
      conformingExactFontSessionId: () => null,
      stabilizeContentSizedItemInlineSize: () => null,
      reportIssue: () => {},
    };
    globalThis.__TiqianCustody = {
      begin: () => {},
      take: () => {},
      commit: () => {},
      restoreParagraph: () => {},
    };
    globalThis.__TiqianWorkerRequest = {
      workerLayoutRequest: () => null,
    };
    globalThis.__TiqianPrepareParagraphLayout = {
      prepareParagraphLayout: () => ({ kind: "unchanged" }),
    };
    globalThis.__TiqianCommitPreparedParagraph = {
      commitPreparedParagraph: () => {
        commitPreparedCalled = true;
        return { kind: "success", measure: 300 };
      },
    };

    const paragraph = makeElement();
    const state = makeState();
    const ffi = makeFakeFfi();

    processParagraph({ ffi, paragraph, state });

    assert.equal(commitPreparedCalled, false);
    assert.equal(state.paragraphs.length, 1);
    assert.equal(state.issues.length, 0);
  } finally {
    restoreGlobals(saved);
    setMarkdownLoweringForTest(null);
  }
});

test("9. prepare unsupported -> issue reported, custody restored", () => {
  const saved = preserveGlobals(PROCESS_GLOBALS);
  try {
    let custodyRestored = false;
    const reportedLifecycleIssues = [];

    globalThis.__TiqianEligibility = {
      shouldTryParagraph: () => true,
    };
    setMarkdownLoweringForTest(() => ({ ok: true, lowered: makeLowered() }));

    globalThis.__TiqianLifecycle = {
      applyConfiguredHostFontSize: () => false,
      captureSourceInlineSize: () => ({ borderBoxWidth: 320, contentBoxWidth: 300 }),
      withoutExactFontSession: (opt) => opt,
      conformingExactFontSessionId: () => null,
      stabilizeContentSizedItemInlineSize: () => null,
      reportIssue: (issue) => reportedLifecycleIssues.push(issue),
    };
    const paragraph = makeElement();
    globalThis.__TiqianCustody = {
      begin: () => {},
      take: () => {},
      commit: () => {},
      restoreParagraph: (el) => {
        if (el === paragraph) custodyRestored = true;
      },
    };
    globalThis.__TiqianWorkerRequest = {
      workerLayoutRequest: () => null,
    };
    globalThis.__TiqianPrepareParagraphLayout = {
      prepareParagraphLayout: () => ({
        kind: "unsupported",
        name: "SpanLocaleMismatchUnsupported",
        detail: "spanRange=0..2",
        element: paragraph,
      }),
    };

    const state = makeState();
    const ffi = makeFakeFfi();

    processParagraph({ ffi, paragraph, state });

    assert.equal(custodyRestored, true);
    assert.equal(state.paragraphs.length, 0);
    assert.equal(state.issues.length, 1);
    assert.equal(state.issues[0].name, "SpanLocaleMismatchUnsupported");
    assert.equal(state.issues[0].detail, "spanRange=0..2");
    assert.equal(reportedLifecycleIssues.length, 1);
  } finally {
    restoreGlobals(saved);
    setMarkdownLoweringForTest(null);
  }
});

test("10. commit unsupported -> issue reported, custody restored", () => {
  const saved = preserveGlobals(PROCESS_GLOBALS);
  try {
    let custodyRestored = false;
    const reportedLifecycleIssues = [];

    globalThis.__TiqianEligibility = {
      shouldTryParagraph: () => true,
    };
    setMarkdownLoweringForTest(() => ({ ok: true, lowered: makeLowered() }));

    globalThis.__TiqianLifecycle = {
      applyConfiguredHostFontSize: () => false,
      captureSourceInlineSize: () => ({ borderBoxWidth: 320, contentBoxWidth: 300 }),
      withoutExactFontSession: (opt) => opt,
      conformingExactFontSessionId: () => null,
      stabilizeContentSizedItemInlineSize: () => null,
      reportIssue: (issue) => reportedLifecycleIssues.push(issue),
    };
    const paragraph = makeElement();
    globalThis.__TiqianCustody = {
      begin: () => {},
      take: () => {},
      commit: () => {},
      restoreParagraph: (el) => {
        if (el === paragraph) custodyRestored = true;
      },
    };
    globalThis.__TiqianWorkerRequest = {
      workerLayoutRequest: () => null,
    };
    globalThis.__TiqianPrepareParagraphLayout = {
      prepareParagraphLayout: () => ({
        kind: "ready",
        planJson: "{}",
        width: 320,
        measure: 300,
        exactFontSessionUsed: true,
      }),
    };
    globalThis.__TiqianCommitPreparedParagraph = {
      commitPreparedParagraph: () => ({
        kind: "unsupported",
        name: "PreparedDomRenderMismatch",
        detail: "height mismatch",
        element: paragraph,
      }),
    };

    const state = makeState();
    const ffi = makeFakeFfi();

    processParagraph({ ffi, paragraph, state });

    assert.equal(custodyRestored, true);
    assert.equal(state.paragraphs.length, 0);
    assert.equal(state.issues.length, 1);
    assert.equal(state.issues[0].name, "PreparedDomRenderMismatch");
    assert.equal(state.issues[0].detail, "height mismatch");
    assert.equal(reportedLifecycleIssues.length, 1);
  } finally {
    restoreGlobals(saved);
    setMarkdownLoweringForTest(null);
  }
});

test("11. Dispatch throw -> WebEnhancementFailure, custody restored", () => {
  const saved = preserveGlobals(PROCESS_GLOBALS);
  try {
    let custodyRestored = false;
    const reportedLifecycleIssues = [];

    globalThis.__TiqianEligibility = {
      shouldTryParagraph: () => true,
    };
    setMarkdownLoweringForTest(() => ({ ok: true, lowered: makeLowered() }));

    globalThis.__TiqianLifecycle = {
      applyConfiguredHostFontSize: () => false,
      captureSourceInlineSize: () => ({ borderBoxWidth: 320, contentBoxWidth: 300 }),
      withoutExactFontSession: (opt) => opt,
      conformingExactFontSessionId: () => null,
      stabilizeContentSizedItemInlineSize: () => null,
      reportIssue: (issue) => reportedLifecycleIssues.push(issue),
    };
    const paragraph = makeElement();
    globalThis.__TiqianCustody = {
      begin: () => {},
      take: () => {},
      commit: () => {},
      restoreParagraph: (el) => {
        if (el === paragraph) custodyRestored = true;
      },
    };
    globalThis.__TiqianWorkerRequest = {
      workerLayoutRequest: () => null,
    };
    globalThis.__TiqianPrepareParagraphLayout = {
      prepareParagraphLayout: () => {
        throw new Error("unexpected engine crash");
      },
    };

    const state = makeState();
    const ffi = makeFakeFfi();

    processParagraph({ ffi, paragraph, state });

    assert.equal(custodyRestored, true);
    assert.equal(state.paragraphs.length, 0);
    assert.equal(state.issues.length, 1);
    assert.equal(state.issues[0].name, "WebEnhancementFailure");
    assert.equal(state.issues[0].detail, "unexpected engine crash");
    assert.equal(reportedLifecycleIssues.length, 1);
  } finally {
    restoreGlobals(saved);
    setMarkdownLoweringForTest(null);
  }
});

test("12. preparedDomEnabled false -> active options come from withoutExactFontSession", () => {
  const saved = preserveGlobals(PROCESS_GLOBALS);
  try {
    let withoutExactCalledWith = null;

    globalThis.__TiqianEligibility = {
      shouldTryParagraph: () => true,
    };
    setMarkdownLoweringForTest(() => ({ ok: true, lowered: makeLowered() }));

    globalThis.__TiqianLifecycle = {
      applyConfiguredHostFontSize: () => false,
      captureSourceInlineSize: () => ({ borderBoxWidth: 320, contentBoxWidth: 300 }),
      withoutExactFontSession: (rawOpt) => {
        withoutExactCalledWith = rawOpt;
        return { ...rawOpt, exactFontSession: null };
      },
      conformingExactFontSessionId: () => null,
      stabilizeContentSizedItemInlineSize: () => null,
      reportIssue: () => {},
    };
    globalThis.__TiqianCustody = {
      begin: () => {},
      take: () => {},
      commit: () => {},
      restoreParagraph: () => {},
    };
    globalThis.__TiqianWorkerRequest = {
      workerLayoutRequest: () => null,
    };
    globalThis.__TiqianPrepareParagraphLayout = {
      prepareParagraphLayout: () => ({ kind: "unchanged" }),
    };

    const paragraph = makeElement();
    const rawOptions = { fontSize: 20, exactFontSession: { sessionId: "sess-abc" } };
    const state = makeState({
      options: rawOptions,
      preparedDomEnabled: false,
    });
    const ffi = makeFakeFfi();

    processParagraph({ ffi, paragraph, state });

    assert.equal(withoutExactCalledWith, rawOptions);
    assert.equal(state.paragraphs.length, 1);
  } finally {
    restoreGlobals(saved);
    setMarkdownLoweringForTest(null);
  }
});

test("13. absent layout worker channel reads as no reusable plan and the direct lane proceeds", () => {
  const saved = preserveGlobals(PROCESS_GLOBALS);
  try {
    const prepareCalls = [];

    globalThis.__TiqianEligibility = {
      shouldTryParagraph: () => true,
    };
    setMarkdownLoweringForTest(() => ({ ok: true, lowered: makeLowered() }));

    globalThis.__TiqianLifecycle = {
      applyConfiguredHostFontSize: () => false,
      captureSourceInlineSize: () => ({ borderBoxWidth: 320, contentBoxWidth: 300 }),
      withoutExactFontSession: (opt) => opt,
      conformingExactFontSessionId: () => "session-1",
      stabilizeContentSizedItemInlineSize: () => null,
      reportIssue: () => {},
    };
    globalThis.__TiqianCustody = {
      begin: () => {},
      take: () => {},
      commit: () => {},
      restoreParagraph: () => {},
    };
    globalThis.__TiqianWorkerRequest = {
      workerLayoutRequest: () => '{"text":"req"}',
    };
    delete globalThis.__TiqianLayoutWorker;
    globalThis.__TiqianPrepareParagraphLayout = {
      prepareParagraphLayout: (ffiArg, arg) => {
        prepareCalls.push(arg);
        return { kind: "unchanged" };
      },
    };

    const paragraph = makeElement();
    const state = makeState();
    const ffi = makeFakeFfi();

    processParagraph({ ffi, paragraph, state });

    assert.equal(prepareCalls.length, 1);
    assert.equal(state.paragraphs.length, 1);
    assert.equal(state.issues.length, 0);
  } finally {
    restoreGlobals(saved);
    setMarkdownLoweringForTest(null);
  }
});
