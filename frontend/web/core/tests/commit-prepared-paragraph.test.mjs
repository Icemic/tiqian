import assert from "node:assert/strict";
import test from "node:test";

import { commitPreparedParagraph, commitWorkerPreparedParagraph } from "../core/engine/commit-prepared-paragraph.js";
import { createEnhanceContext } from "../core/engine/context/enhance-context.js";
import { effectiveLineMeasure } from "../core/engine/responsive-measure.js";
import { LAYOUT_REVISION, SNAPSHOT_SCHEMA } from "../core/sampler/snapshot/snapshot-schema.js";

// The commit functions run for real, including the real prepared-DOM
// renderer. The former injected validator and its mismatch/fallback/retry
// machinery were dissolved; renderer contract failures now surface as thrown
// errors (UnsupportedPreparedLayoutRevision, InvalidPreparedParagraphHost)
// that the commit functions propagate to the caller.

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

function computedStyle(values = {}) {
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

function withEnv(fn) {
  const saved = saveGlobals([
    "getComputedStyle",
  ]);
  try {
    globalThis.getComputedStyle = (target, pseudo) =>
      target && target._computedValues
        ? computedStyle(target._computedValues)
        : computedStyle();
    return fn();
  } finally {
    restoreGlobals(saved);
  }
}

function makeElement(initialAttributes = {}, options = {}) {
  const attributes = new Map(Object.entries(initialAttributes));
  const removedAttributes = [];
  const setAttributes = [];
  const element = {
    tagName: options.tagName ?? "P",
    innerHTML: "",
    style: { setProperty: () => {} },
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
    attributes,
    setAttributes,
    removedAttributes,
    getBoundingClientRect: () => ({ width: options.width ?? 320 }),
    getClientRects: () => [],
    parentElement: null,
    closest: () => null,
    // An empty-lines plan renders no markup, so the renderer's marker and
    // placeholder queries all answer empty.
    querySelectorAll: () => [],
    cloneNode: () => makeElement({}, options),
  };
  return element;
}

function textStyle(overrides = {}) {
  return {
    fontFamilies: ["Noto Serif CJK SC"],
    fontSize: 19,
    fontWeight: 400,
    italic: false,
    baselineShift: 0,
    locale: "zh-Hans",
    ...overrides,
  };
}

function makeParagraph(overrides = {}) {
  const source = overrides.source ?? makeElement();
  const lowered = {
    text: "hello",
    textStyle: textStyle(),
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

function createTestContext(source) {
  return createEnhanceContext(source);
}

// The minimal schema-conforming plan: the renderer accepts it and renders no
// lines, so the commit contract is exercised without a shaping fixture.
const EMPTY_PLAN_JSON = JSON.stringify({
  schema: SNAPSHOT_SCHEMA,
  layoutRevision: LAYOUT_REVISION,
  height: 0,
  lines: [],
});

test("worker happy path: sets four attributes, renders the plan, sets lastMeasure, stamps rawDom, returns null", () => {
  withEnv(() => {
    const source = makeElement({}, { width: 300 });
    const context = createTestContext(source);

    const domObjElement = makeElement();
    const paragraph = makeParagraph({
      source,
      lowered: {
        text: "hello world",
        textStyle: textStyle({ locale: "zh-Hans", fontSize: 19 }),
        domInlineObjects: [{ element: domObjElement }],
      },
    });

    const record = {
      plan: EMPTY_PLAN_JSON,
      semantics: [{ start: 0, end: 2, tagName: "em" }],
      inlineBoxes: [{ start: 0, end: 1 }],
    };
    const workerPlan = JSON.stringify(record);
    const inlineObjectMetaJson = JSON.stringify([{ start: 2, end: 3, marginRight: 6 }]);
    const cjkStrongSemanticsJson = JSON.stringify([{ start: 0, end: 1 }]);

    const result = commitWorkerPreparedParagraph(context, {
      paragraph,
      workerPlan,
      inlineObjectMetaJson,
      cjkStrongSemanticsJson,
    });

    assert.equal(result, null);
    assert.equal(paragraph.source.getAttribute("lang"), "zh-Hans");
    assert.equal(paragraph.source.getAttribute("data-tq-canonical-plain"), null); // not plain due to domInlineObjects
    assert.equal(paragraph.source.getAttribute("data-tq-snapshot-prepared-dom"), "true");
    assert.equal(paragraph.source.getAttribute("data-tq-canonical-source"), "true");
    assert.equal(paragraph.lastMeasure, effectiveLineMeasure(300, 19));

    // The real renderer ran against the host: the empty plan lowered to empty
    // markup, and the rawDom stamp registered the paragraph with the engine
    // write suspension closed.
    assert.equal(paragraph.source.innerHTML, "");
    assert.ok(context.rawDomParagraphs.has(paragraph.source));
    assert.equal(context.rawDomParagraphs.get(paragraph.source)?.engineWriteDepth, 0);
  });
});

test("worker commit propagates the renderer layout-revision rejection", () => {
  withEnv(() => {
    const paragraph = makeParagraph();
    const context = createTestContext(paragraph.source);

    // A plan without the schema/revision envelope is rejected by the real
    // renderer; the commit layer no longer swallows or re-verdicts it.
    assert.throws(
      () => commitWorkerPreparedParagraph(context, {
        paragraph,
        workerPlan: JSON.stringify({ plan: "{}" }),
        inlineObjectMetaJson: "[]",
        cjkStrongSemanticsJson: "[]",
      }),
      { message: "UnsupportedPreparedLayoutRevision" },
    );
  });
});

test("worker rich lowered: removes canonical-plain attribute for rich lowered", () => {
  withEnv(() => {
    const source = makeElement({ "data-tq-canonical-plain": "true" });
    const context = createTestContext(source);

    const paragraph = makeParagraph({
      source,
      lowered: {
        spans: [{ start: 0, end: 2, style: textStyle() }],
      },
    });

    const result = commitWorkerPreparedParagraph(context, {
      paragraph,
      workerPlan: JSON.stringify({ plan: EMPTY_PLAN_JSON }),
      inlineObjectMetaJson: "[]",
      cjkStrongSemanticsJson: "[]",
    });

    assert.equal(result, null);
    assert.equal(paragraph.source.getAttribute("data-tq-canonical-plain"), null);
    assert.ok(paragraph.source.removedAttributes.includes("data-tq-canonical-plain"));
  });
});

test("direct happy path, no live sources: sets canonical-plain and canonical-source, stamps rawDom, returns success", () => {
  withEnv(() => {
    const paragraph = makeParagraph();
    const context = createTestContext(paragraph.source);

    const preparation = {
      planJson: EMPTY_PLAN_JSON,
      width: 320,
      measure: 339,
      snapshotFontSessionUsed: false,
    };

    const result = commitPreparedParagraph(context, {
      paragraph,
      preparation,
      options: {},
      browserFallback: null,
      semanticReplayJson: "[]",
      inlineObjectMetaJson: "[]",
      cjkStrongSemanticsJson: "[]",
    });

    assert.deepEqual(result, { kind: "success", measure: 339 });
    assert.equal(paragraph.source.getAttribute("data-tq-canonical-plain"), "true");
    assert.equal(paragraph.source.getAttribute("data-tq-canonical-source"), "true");
    assert.equal(paragraph.source.getAttribute("lang"), "zh-Hans");

    // Plain paragraphs render through the real renderer with no live-source
    // options; the empty plan lowered to empty markup on the host.
    assert.equal(paragraph.source.innerHTML, "");
    assert.ok(context.rawDomParagraphs.has(paragraph.source));
  });
});

test("direct rich path with sourceSpans elements: renders without canonical-plain and returns success", () => {
  withEnv(() => {
    const spanElement = makeElement({}, { tagName: "EM" });
    const objElement = makeElement();
    const paragraph = makeParagraph({
      lowered: {
        text: "hello world",
        sourceSpans: [{ element: spanElement }],
        domInlineObjects: [{ element: objElement }],
      },
    });
    const context = createTestContext(paragraph.source);

    const preparation = {
      planJson: EMPTY_PLAN_JSON,
      width: 320,
      measure: 339,
      snapshotFontSessionUsed: false,
    };

    // The rich lowered (sourceSpans + domInlineObjects) drives the live-source
    // branch with an empty semantic replay: no semantic placeholders render in
    // the empty plan, but the commit still takes the hasLiveSources path.
    const semanticReplayJson = "[]";
    const inlineObjectMetaJson = JSON.stringify([{ start: 1, end: 2, marginRight: 5 }]);
    const cjkStrongSemanticsJson = JSON.stringify([{ start: 0, end: 1 }]);

    const result = commitPreparedParagraph(context, {
      paragraph,
      preparation,
      options: {},
      browserFallback: null,
      semanticReplayJson,
      inlineObjectMetaJson,
      cjkStrongSemanticsJson,
    });

    assert.deepEqual(result, { kind: "success", measure: 339 });
    assert.equal(paragraph.source.getAttribute("data-tq-canonical-plain"), null);
    assert.equal(paragraph.source.getAttribute("data-tq-canonical-source"), "true");
    assert.equal(paragraph.source.getAttribute("lang"), "zh-Hans");
  });
});

test("direct commit propagates the renderer layout-revision rejection", () => {
  withEnv(() => {
    const paragraph = makeParagraph();
    const context = createTestContext(paragraph.source);

    const preparation = {
      planJson: '{"lines":[]}',
      width: 320,
      measure: 339,
      snapshotFontSessionUsed: false,
    };

    // The former GeometryMismatch fallback verdict is dissolved; a plan the
    // renderer rejects surfaces as the thrown renderer error.
    assert.throws(
      () => commitPreparedParagraph(context, {
        paragraph,
        preparation,
        options: {},
        browserFallback: { bridge: {} },
      }),
      { message: "UnsupportedPreparedLayoutRevision" },
    );

    assert.equal(
      paragraph.source.setAttributes.some((a) => a.name === "data-tq-snapshot-prepared-dom"),
      false,
    );
  });
});
