import assert from "node:assert/strict";
import test from "node:test";

import { globalServices, initializeGlobalServices } from "../core/services/global-services.js";
import { processParagraph } from "../core/engine/process-paragraph.js";
import { createEnhanceContext } from "../core/engine/context/enhance-context.js";
import { optionsFromJs } from "../core/engine/lifecycle.js";
import { effectiveLineMeasure } from "../core/engine/responsive-measure.js";
import { LAYOUT_REVISION, SNAPSHOT_SCHEMA } from "../core/sampler/snapshot/snapshot-schema.js";
import { installThrowingFontBackend } from "../test-support/fixture-font-backend.mjs";
import { FakeElement, FakeFragment, FakeNode, FakeText } from "./snapshot-dom-fixtures.mjs";
initializeGlobalServices();

// The pipeline runs for real: eligibility, markdown lowering, the lifecycle
// helpers, the worker request serializer, the prepared-metadata builders, the
// direct prepare step and both commit functions (including the real
// prepared-DOM renderer). The unit under test receives the per-element
// EnhancedElementContext; its contextState.paragraphs and diagnosis.issues
// live arrays are the commit/issue observation points, and the raw-DOM
// record it carries is the observation point for begin/take/commit/restore.
// The fake world supplies the document (fragment factory, style head,
// lowering probes, canvas for the browser-fallback bridge) and the Node
// prototype the raw-DOM commit forwarding captures.

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

// Canvas double for the browser-fallback bridge: measureText answers one
// fontSize advance per code point parsed out of the current font shorthand,
// and the TextMetrics fields carry the fixture box proportions, so the real
// canvas shaper and metrics resolver produce a complete layout envelope.
function scriptedCanvasContext() {
  const context = {
    font: "16px sans-serif",
    canvas: { width: 0, height: 0 },
    measureText(text) {
      const match = /(\d+(?:\.\d+)?)px/.exec(String(context.font));
      const fontSize = match ? Number(match[1]) : 16;
      const width = Array.from(String(text)).length * fontSize;
      return {
        width,
        actualBoundingBoxLeft: 0,
        actualBoundingBoxAscent: fontSize * 0.88,
        actualBoundingBoxRight: width,
        actualBoundingBoxDescent: fontSize * 0.12,
        fontBoundingBoxAscent: fontSize * 0.88,
        fontBoundingBoxDescent: fontSize * 0.12,
        ideographicBaseline: -fontSize * 0.12,
      };
    },
    setTransform() {},
    clearRect() {},
    fillText() {},
    getImageData: (sx, sy, sw, sh) => ({ data: new Uint8ClampedArray(Math.max(sw, 0) * Math.max(sh, 0) * 4) }),
  };
  return context;
}

// The fake document supplies the fragment factory the raw-DOM takeover uses,
// a style-carrying head for the prepared-value style state, lowering probes
// whose baseline answers probeBottom, an inert Range for the inline-edge
// measurement, a body the canvas shaper's hidden-DOM probe attaches to, and
// a canvas whose 2d context is the scripted double above.
function makeFakeDocument(probeBottom = 0) {
  const documentObject = {
    createElement: (tagName) => {
      if (String(tagName || "").toLowerCase() === "canvas") {
        return {
          width: 0,
          height: 0,
          getContext: () => scriptedCanvasContext(),
        };
      }
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
  documentObject.body = new FakeElement("body");
  return documentObject;
}

// Runs fn with the environment globals the real pipeline reads.
function withEnv(fn, overrides = {}) {
  const saved = saveGlobals([
    "getComputedStyle",
    "document",
    "Node",
  ]);
  const savedLayoutWorker = globalServices().coordination.layoutWorker;
  try {
    globalThis.document = overrides.document ?? makeFakeDocument();
    globalThis.Node = FakeNode;
    if (overrides.layoutWorker !== undefined) {
      globalServices().coordination.layoutWorker = overrides.layoutWorker;
    }
    globalThis.getComputedStyle = (target, pseudo) =>
      target && target._computedValues
        ? computedStyle(target._computedValues)
        : computedStyle();
    return fn();
  } finally {
    globalServices().coordination.layoutWorker = savedLayoutWorker;
    restoreGlobals(saved);
  }
}

// The snapshot-session descriptor is derived inside the pipeline from the
// runtime options' snapshotFontSession id through the coordination replay
// registry; tests seed that registry slot directly. An empty table makes the
// session's callbacks report MissingServerShapingReplay, which the exact
// path treats as a font capability failure and retries through the browser
// fallback bridge.
function registerReplaySession(sessionId, record) {
  const registry = globalServices().coordination.fonts.replayRegistry;
  registry.sessions.set(sessionId, record);
  return () => registry.sessions.delete(sessionId);
}

function registerEmptyReplaySession(sessionId) {
  return registerReplaySession(sessionId, { shapes: new Map(), metrics: new Map(), probe: null });
}

// Standard core-neutral seeding: one EnhancedElementContext for the element,
// resolved engine options on the context state, and an established runtime
// for the browser-fallback descriptor, exactly the driver order. The ledger
// resolver applies the snapshot gate (configured typography lowers the
// snapshot font session), so tests that need configured typography AND a
// conforming session together enter through the canonical re-entry verb,
// which skips the gate.
function seedContext(paragraph, optionsBag, { canonical = false } = {}) {
  const context = createEnhanceContext(paragraph);
  const resolved = canonical
    ? context.optionsLedger.resolveEngineOptionsFromCanonical(paragraph, optionsFromJs(optionsBag))
    : context.optionsLedger.resolveEngineOptions(paragraph, optionsBag);
  context.contextState.setRuntimeOptions(resolved);
  context.typography.establishRuntime(paragraph, resolved);
  return context;
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

    const context = seedContext(paragraph, { fontSize: 19 });

    processParagraph(context, paragraph);

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

    assert.equal(context.contextState.paragraphs.length, 1);
    const item = context.contextState.paragraphs[0];
    assert.equal(item.source, paragraph);
    // The real direct commit records the effective line measure, not a canned
    // 300.
    assert.equal(item.lastMeasure, effectiveLineMeasure(320, 19));
    assert.equal(context.diagnosis.issues.length, 0);
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

    // Configured typography and the conforming session coexist through the
    // canonical re-entry verb, which skips the snapshot gate.
    const context = seedContext(paragraph, {
      fontSize: 19,
      strongAsEmphasisMarks: true,
      snapshotFontSession: { status: "conforming", sessionId: "session-1" },
    }, { canonical: true });

    processParagraph(context, paragraph);

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

    assert.equal(context.contextState.paragraphs.length, 1);
    assert.equal(context.contextState.paragraphs[0].lastMeasure, effectiveLineMeasure(320, 19));
    assert.equal(context.diagnosis.issues.length, 0);
  }, { layoutWorker, document: makeFakeDocument(30) });
});

test("3. Lowering throw -> DomLoweringFailure reported, nothing after it runs (rawDom never registered)", () => {
  const throwError = new Error("lowering syntax error");
  withEnv(() => {
    const paragraph = makeParagraphElement();
    const context = seedContext(paragraph, { fontSize: 19 });
    // Seeding resolved its options through the normal computed style; the
    // lowering probe is the first consumer that must see the throw.
    globalThis.getComputedStyle = () => {
      throw throwError;
    };
    processParagraph(context, paragraph);

    assert.equal(context.rawDomParagraphs.has(paragraph), false);
    assert.equal(context.diagnosis.issues.length, 1);
    assert.equal(context.diagnosis.issues[0].name, "DomLoweringFailure");
    assert.equal(context.diagnosis.issues[0].detail, "lowering syntax error");
    assert.equal(context.diagnosis.issues[0].element, paragraph);
    assert.equal(context.diagnosis.issues[0].reportToConsole, true);
    // The lifecycle marker was written onto the paragraph element.
    assert.equal(paragraph.getAttribute("data-tiqian-capability-issue"), "DomLoweringFailure");
  });
});

test("4. Lowering ok false with an issue -> that issue reported", () => {
  withEnv(() => {
    const paragraph = makeParagraphElement({
      childNodes: [blockChild("div", "blocked")],
    });
    const context = seedContext(paragraph, { fontSize: 19 });
    processParagraph(context, paragraph);

    assert.equal(context.rawDomParagraphs.has(paragraph), false);
    assert.equal(context.diagnosis.issues.length, 1);
    assert.equal(context.diagnosis.issues[0].name, "UnsupportedInlineFormattingContext");
    assert.equal(context.diagnosis.issues[0].detail, "div:block");
    assert.equal(context.diagnosis.issues[0].element, paragraph);
    assert.equal(context.diagnosis.issues[0].reportToConsole, true);
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
    const context = seedContext(paragraph, {
      requireSnapshotLayoutWorker: true,
      snapshotFontSession: { status: "conforming", sessionId: "session-1" },
    });
    processParagraph(context, paragraph);

    assert.equal(paragraph.getAttribute("style"), "margin: 10px;");
    // Begin recorded the shell but the gate tripped before the takeover.
    const record = context.rawDomParagraphs.get(paragraph);
    assert.ok(record);
    assert.equal(record.originalContent, null);
    assert.equal(context.diagnosis.issues.length, 1);
    assert.equal(context.diagnosis.issues[0].name, "SnapshotLayoutWorkerPlanUnavailable");
    assert.equal(context.diagnosis.issues[0].detail, "No worker available in this context");
    assert.equal(context.diagnosis.issues[0].element, paragraph);
    assert.equal(context.diagnosis.issues[0].reportToConsole, true);
    // The lifecycle marker was written onto the paragraph element.
    assert.equal(paragraph.getAttribute("data-tiqian-capability-issue"), "SnapshotLayoutWorkerPlanUnavailable");
  }, { layoutWorker });
});

test("7. canUseRichBrowserFallback: rich lowered plus a capability-failure worker issue -> gate NOT taken, processing continues", () => {
  const layoutWorker = {
    take: () => null,
    issue: () => "MissingServerShapingReplay for CodeFont",
  };
  // The registered replay session stays empty, so the exact snapshot-session
  // layout misses its shaping supply and retries through the browser
  // fallback bridge.
  const unregister = registerEmptyReplaySession("session-1");
  try {
    withEnv(() => {
      // The opaque inline object makes the lowered paragraph rich without
      // adding live semantic sources, so the direct commit stays on the
      // inline-object branch.
      const paragraph = makeParagraphElement({
        childNodes: [new FakeText("hello "), inlineObjectSpan(42, 20)],
      });
      const context = seedContext(paragraph, {
        requireSnapshotLayoutWorker: true,
        snapshotFontSession: { status: "conforming", sessionId: "session-1" },
      });

      processParagraph(context, paragraph);

      // The rich fallback bypassed the worker gate: the takeover ran and the
      // direct path committed.
      const record = context.rawDomParagraphs.get(paragraph);
      assert.ok(record);
      assert.ok(record.originalContent);
      assert.equal(context.contextState.paragraphs.length, 1);
      assert.equal(context.diagnosis.issues.length, 0);
    }, { layoutWorker, document: makeFakeDocument(30) });
  } finally {
    unregister();
  }
});

test("9. Dispatch throw -> WebEnhancementFailure, rawDom restored", () => {
  const backend = installThrowingFontBackend(new Error("unexpected engine crash"));
  // The throwing backend answers the registered session's shaping lookups,
  // so the snapshot-session descriptor derived from the runtime options
  // throws exactly like the old injected session did.
  const unregister = registerReplaySession("session-throw", {
    shapes: { get: () => backend.shapeJson("") },
    metrics: { get: () => backend.metricsJson("") },
    probe: null,
  });
  try {
    withEnv(() => {
      const paragraph = makeParagraphElement();
      const context = seedContext(paragraph, {
        fontSize: 19,
        snapshotFontSession: { status: "conforming", sessionId: "session-throw" },
      }, { canonical: true });

      processParagraph(context, paragraph);

      // The restore returned the original children and shell to the host.
      assert.equal(paragraph.textContent, "hello world");
      assert.equal(paragraph.getAttribute("data-tq-rendered"), null);
      assert.equal(context.contextState.paragraphs.length, 0);
      assert.equal(context.diagnosis.issues.length, 1);
      assert.equal(context.diagnosis.issues[0].name, "WebEnhancementFailure");
      assert.equal(context.diagnosis.issues[0].detail, "unexpected engine crash");
      // The lifecycle marker was written onto the paragraph element.
      assert.equal(paragraph.getAttribute("data-tiqian-capability-issue"), "WebEnhancementFailure");
    });
  } finally {
    backend.uninstall();
    unregister();
  }
});

test("10. preparedDomEnabled gate dissolved (2026-08-27 core-neutral wave): without a snapshotFontSession option the worker channel is skipped and the direct path commits", () => {
  const takeCalls = [];
  const layoutWorker = {
    take: (element, sessionKey, request) => {
      takeCalls.push({ element, sessionKey, request });
      return null;
    },
    issue: () => null,
  };
  withEnv(() => {
    const paragraph = makeParagraphElement();
    const context = seedContext(paragraph, { fontSize: 19 });

    processParagraph(context, paragraph);

    // The preparedDomEnabled flag was dissolved: the snapshotFontSession
    // option's presence alone gates the worker channel. Runtime options
    // without one never reach an installed worker, and the direct path
    // commits.
    assert.equal(takeCalls.length, 0);
    assert.equal(context.contextState.paragraphs.length, 1);
    assert.equal(context.diagnosis.issues.length, 0);
  }, { layoutWorker });
});

test("11. absent layout worker channel reads as no reusable plan and the direct path proceeds", () => {
  // The registered replay session stays empty, so after the absent worker
  // channel reads as no plan the snapshot-session layout misses its shaping
  // supply and completes through the browser fallback retry.
  const unregister = registerEmptyReplaySession("session-1");
  try {
    withEnv(() => {
      const paragraph = makeParagraphElement();
      const context = seedContext(paragraph, {
        fontSize: 19,
        snapshotFontSession: { status: "conforming", sessionId: "session-1" },
      }, { canonical: true });

      processParagraph(context, paragraph);

      // No layout worker channel is installed, so the direct path ran the
      // real prepare and commit.
      assert.ok(!globalServices().coordination.layoutWorker);
      assert.equal(context.contextState.paragraphs.length, 1);
      assert.equal(context.diagnosis.issues.length, 0);
    });
  } finally {
    unregister();
  }
});
