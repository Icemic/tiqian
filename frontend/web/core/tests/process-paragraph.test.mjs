import assert from "node:assert/strict";
import test from "node:test";

import { globalServices, initializeGlobalServices } from "../core/services/global-services.js";
import { processParagraph } from "../core/engine/process-paragraph.js";
import { createEnhanceContext } from "../core/engine/context/enhance-context.js";
import { effectiveLineMeasure } from "../core/engine/responsive-measure.js";
import { LAYOUT_REVISION, SNAPSHOT_SCHEMA } from "../core/sampler/snapshot/snapshot-schema.js";
import { installFixtureFontBackend, installThrowingFontBackend } from "../test-support/fixture-font-backend.mjs";
import { FakeElement, FakeFragment, FakeNode, FakeText } from "./snapshot-dom-fixtures.mjs";
initializeGlobalServices();

// The pipeline runs for real: eligibility, markdown lowering, the lifecycle
// helpers, the worker request serializer, the prepared-metadata builders, the
// direct prepare step and both commit functions (including the real
// prepared-DOM renderer). The first parameter is the per-element
// EnhancedElementContext; the raw-DOM record it carries is the observation
// point for begin/take/commit/restore. The fake world supplies the document
// (fragment factory, style head, lowering probes) and the Node prototype the
// raw-DOM commit forwarding captures.

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

// The fake document supplies the fragment factory the raw-DOM takeover uses,
// a style-carrying head for the prepared-value style state, lowering probes
// whose baseline answers probeBottom, and an inert Range for the inline-edge
// measurement.
function makeFakeDocument(probeBottom = 0) {
  const documentObject = {
    createElement: (tagName) => {
      const probe = new FakeElement(tagName || "span");
      probe.top = probeBottom;
      return probe;
    },
    createDocumentFragment: () => new FakeFragment(),
    createRange: () => ({
      selectNodeContents() {},
      getClientRects: () => [],
    }),
  };
  documentObject.head = new FakeElement("head");
  return documentObject;
}

// Runs fn with the environment globals the real pipeline reads. The fixture
// font backend is installed by default so the real prepare step can shape;
// a test that must not reach ffi passes fontBackend: false.
function withEnv(fn, overrides = {}) {
  const saved = saveGlobals([
    "getComputedStyle",
    "document",
    "Node",
  ]);
  const savedLayoutWorker = globalServices().coordination.layoutWorker;
  const backend = overrides.fontBackend === false ? null : installFixtureFontBackend();
  try {
    globalThis.document = overrides.document ?? makeFakeDocument();
    globalThis.Node = FakeNode;
    if (overrides.layoutWorker !== undefined) {
      globalServices().coordination.layoutWorker = overrides.layoutWorker;
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
    globalServices().coordination.layoutWorker = savedLayoutWorker;
    restoreGlobals(saved);
  }
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
  return {
    options: overrides.options !== undefined ? overrides.options : { fontSize: 19 },
    preparedDomEnabled: overrides.preparedDomEnabled ?? true,
    snapshotSession: overrides.snapshotSession ?? fixtureSnapshotSession(),
    browserFallback: overrides.browserFallback ?? null,
    onIssue: (issue) => {
      issues.push(issue);
    },
    onParagraphCommitted: (item) => {
      paragraphs.push(item);
    },
    issues,
    paragraphs,
  };
}

// Paragraph host element on the fixture fake-DOM base: a measurable P whose
// children survive the raw-DOM takeover and whose innerHTML carries the
// renderer output.
function makeParagraphElement(options = {}) {
  const element = new FakeElement("p");
  element.width = options.width ?? 320;
  element.ownerDocument = globalThis.document;
  for (const [name, value] of Object.entries(options.attributes ?? {})) {
    element.setAttribute(name, value);
  }
  for (const child of options.childNodes ?? [new FakeText("hello world")]) {
    element.appendChild(child);
  }
  return element;
}

// A latin STRONG with strongAsEmphasisMarks lowers into a sourceSpan carrying
// a non-null cjkStrongBaseWeight and no Emphasis decoration.
function strongChild(text) {
  const element = new FakeElement("strong");
  element.appendChild(new FakeText(text));
  element._computedValues = { display: "inline", "font-weight": "normal" };
  return element;
}

// A block-level child fails lowering with UnsupportedInlineFormattingContext.
function blockChild(tagName, text) {
  const element = new FakeElement(tagName);
  element.appendChild(new FakeText(text));
  element._computedValues = { display: "block" };
  return element;
}

// A static inline object child: the geometry probe measures it against the
// fake document's probe baseline.
function inlineObjectSpan(width, height) {
  const element = new FakeElement("span");
  element.setAttribute("data-tiqian-static-inline-object", "");
  element.appendChild(new FakeText("obj"));
  element.width = width;
  element.height = height;
  element.top = 10;
  element._computedValues = { display: "inline-block" };
  return element;
}

// The minimal schema-conforming worker plan: the renderer accepts it and
// renders no lines, so the worker commit path runs without line geometry.
const EMPTY_PLAN_JSON = JSON.stringify({
  schema: SNAPSHOT_SCHEMA,
  layoutRevision: LAYOUT_REVISION,
  height: 0,
  lines: [],
});

test("1. Direct happy path: lowering ok, rawDom record captures the original shell, prepare ready, commit success", () => {
  withEnv(() => {
    const paragraph = makeParagraphElement({
      attributes: {
        style: "color: blue;",
        "data-tq-rendered": "false",
        "data-tq-host-inline-size": "300px",
      },
    });
    paragraph.style.setProperty("position", "relative");
    paragraph.style.setProperty("inline-size", "300px");
    paragraph.style.setProperty("font-size", "19px");

    const context = createEnhanceContext(paragraph);
    const state = makeState();

    processParagraph(context, { paragraph, state });

    // The raw-DOM record captured the original shell values begin received.
    const record = context.rawDomParagraphs.get(paragraph);
    assert.ok(record);
    assert.equal(record.originalRenderedAttribute, "false");
    assert.equal(record.originalStyleAttribute, "color: blue;");
    assert.equal(record.originalHostInlineSizeAttribute, "300px");
    assert.equal(record.originalPosition, "relative");
    assert.equal(record.originalInlineSize, "300px");
    assert.equal(record.originalFontSize, "19px");
    // Take moved the original children into the backup fragment and commit
    // published it; the host now carries the rendered replay.
    assert.ok(record.originalContent);
    assert.ok(record.fragment);
    assert.equal(record.originalContent.textContent, "hello world");
    assert.ok(paragraph.textContent.includes("hello world"));

    assert.equal(paragraph.getAttribute("data-tq-rendered"), "true");
    assert.equal(paragraph.getAttribute("data-tq-runtime-render-font"), "true");

    assert.equal(state.paragraphs.length, 1);
    const item = state.paragraphs[0];
    assert.equal(item.source, paragraph);
    // The real direct commit records the effective line measure, not a canned
    // 300.
    assert.equal(item.lastMeasure, effectiveLineMeasure(320, 19));
    assert.equal(state.issues.length, 0);
  });
});

test("2. Worker happy path: worker request built, layout worker take returns a plan, worker commit commits it", () => {
  const takeCalls = [];
  const layoutWorker = {
    take: (element, sessionKey, request) => {
      takeCalls.push({ element, sessionKey, request });
      return JSON.stringify({ plan: EMPTY_PLAN_JSON });
    },
    issue: () => null,
  };
  withEnv(() => {
    const objSpan = inlineObjectSpan(42, 20);
    // Children: latin strong (sourceSpan with cjkStrongBaseWeight), a space,
    // then the opaque inline object (domInlineObject). No Emphasis
    // decoration, so the paragraph stays Worker-eligible.
    const paragraph = makeParagraphElement({
      childNodes: [strongChild("hello"), new FakeText(" "), objSpan],
    });
    paragraph._computedValues = { "font-weight": "normal" };

    const context = createEnhanceContext(paragraph);
    const state = makeState({
      options: {
        fontSize: 19,
        strongAsEmphasisMarks: true,
        snapshotFontSession: { status: "conforming", sessionId: "session-1" },
      },
    });

    processParagraph(context, { paragraph, state });

    // The worker request was built from the lowered paragraph and sent with
    // the conforming session key; the opaque inline object lowers to the
    // object replacement character.
    assert.equal(takeCalls.length, 1);
    assert.equal(takeCalls[0].element, paragraph);
    assert.equal(takeCalls[0].sessionKey, "session-1");
    assert.equal(JSON.parse(takeCalls[0].request).text, "hello \ufffc");

    // The worker commit set the prepared-dom and canonical attributes and
    // cached the effective line measure.
    assert.equal(paragraph.getAttribute("data-tq-snapshot-prepared-dom"), "true");
    assert.equal(paragraph.getAttribute("data-tq-canonical-source"), "true");
    assert.equal(paragraph.getAttribute("lang"), "zh-Hans");

    assert.equal(state.paragraphs.length, 1);
    assert.equal(state.paragraphs[0].lastMeasure, effectiveLineMeasure(320, 19));
    assert.equal(state.issues.length, 0);
  }, { layoutWorker, document: makeFakeDocument(30) });
});

test("3. Lowering throw -> DomLoweringFailure reported, nothing after it runs (rawDom never registered)", () => {
  const throwError = new Error("lowering syntax error");
  withEnv(() => {
    const paragraph = makeParagraphElement();
    const context = createEnhanceContext(paragraph);
    const state = makeState();
    processParagraph(context, { paragraph, state });

    assert.equal(context.rawDomParagraphs.has(paragraph), false);
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
    const paragraph = makeParagraphElement({
      childNodes: [blockChild("div", "blocked")],
    });
    const context = createEnhanceContext(paragraph);
    const state = makeState();
    processParagraph(context, { paragraph, state });

    assert.equal(context.rawDomParagraphs.has(paragraph), false);
    assert.equal(state.issues.length, 1);
    assert.equal(state.issues[0].name, "UnsupportedInlineFormattingContext");
    assert.equal(state.issues[0].detail, "div:block");
    assert.equal(state.issues[0].element, paragraph);
    assert.equal(state.issues[0].reportToConsole, true);
    // The lifecycle marker was written onto the paragraph element.
    assert.equal(paragraph.getAttribute("data-tiqian-capability-issue"), "UnsupportedInlineFormattingContext");
  });
});

test("6. Snapshot worker gate: requireSnapshotLayoutWorker true, plan null, rich fallback not applicable -> style attribute restored, SnapshotLayoutWorkerPlanUnavailable", () => {
  const layoutWorker = {
    take: () => null,
    issue: () => "No worker available in this context",
  };
  withEnv(() => {
    const paragraph = makeParagraphElement({
      attributes: { style: "margin: 10px;" },
    });
    const context = createEnhanceContext(paragraph);
    const state = makeState({
      options: {
        requireSnapshotLayoutWorker: true,
        snapshotFontSession: { status: "conforming", sessionId: "session-1" },
      },
    });
    processParagraph(context, { paragraph, state });

    assert.equal(paragraph.getAttribute("style"), "margin: 10px;");
    // Begin recorded the shell but the gate tripped before the takeover.
    const record = context.rawDomParagraphs.get(paragraph);
    assert.ok(record);
    assert.equal(record.originalContent, null);
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
    // The opaque inline object makes the lowered paragraph rich without
    // adding live semantic sources, so the direct commit stays on the
    // inline-object branch.
    const paragraph = makeParagraphElement({
      childNodes: [new FakeText("hello "), inlineObjectSpan(42, 20)],
    });
    const context = createEnhanceContext(paragraph);
    const state = makeState({
      options: {
        requireSnapshotLayoutWorker: true,
        snapshotFontSession: { status: "conforming", sessionId: "session-1" },
      },
    });

    processParagraph(context, { paragraph, state });

    // The rich fallback bypassed the worker gate: the takeover ran and the
    // direct path committed.
    const record = context.rawDomParagraphs.get(paragraph);
    assert.ok(record);
    assert.ok(record.originalContent);
    assert.equal(state.paragraphs.length, 1);
    assert.equal(state.issues.length, 0);
  }, { layoutWorker, document: makeFakeDocument(30) });
});

test("9. Dispatch throw -> WebEnhancementFailure, rawDom restored", () => {
  const backend = installThrowingFontBackend(new Error("unexpected engine crash"));
  try {
    withEnv(() => {
      const paragraph = makeParagraphElement();
      const context = createEnhanceContext(paragraph);
      const state = makeState({ snapshotSession: snapshotSessionCallbacksOf(backend) });

      processParagraph(context, { paragraph, state });

      // The restore returned the original children and shell to the host.
      assert.equal(paragraph.textContent, "hello world");
      assert.equal(paragraph.getAttribute("data-tq-rendered"), null);
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

test("10. preparedDomEnabled false -> the snapshot font session option is dropped and the direct path proceeds", () => {
  withEnv(() => {
    const paragraph = makeParagraphElement();
    const context = createEnhanceContext(paragraph);
    const rawOptions = { fontSize: 19, snapshotFontSession: { status: "conforming", sessionId: "sess-abc" } };
    const state = makeState({
      options: rawOptions,
      preparedDomEnabled: false,
    });

    processParagraph(context, { paragraph, state });

    // With the snapshot font session dropped from the active options the
    // worker channel is skipped and the direct path committed.
    assert.equal(state.paragraphs.length, 1);
    assert.equal(state.issues.length, 0);
  });
});

test("11. absent layout worker channel reads as no reusable plan and the direct path proceeds", () => {
  withEnv(() => {
    const paragraph = makeParagraphElement();
    const context = createEnhanceContext(paragraph);
    const state = makeState({
      options: {
        fontSize: 19,
        snapshotFontSession: { status: "conforming", sessionId: "session-1" },
      },
    });

    processParagraph(context, { paragraph, state });

    // No layout worker channel is installed, so the direct snapshot-session
    // path ran the real prepare and commit.
    assert.ok(!globalServices().coordination.layoutWorker);
    assert.equal(state.paragraphs.length, 1);
    assert.equal(state.issues.length, 0);
  });
});
