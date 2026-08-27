import { getOrCreateEnhanceContext } from "../core/engine/context/enhance-context.js";
import assert from "node:assert/strict";
import test from "node:test";

import { commitPreparedParagraph, commitWorkerPreparedParagraph } from "../core/engine/commit-prepared-paragraph.js";
import { effectiveLineMeasure } from "../core/engine/responsive-measure.js";
import { installFixtureFontBackend } from "../test-support/fixture-font-backend.mjs";

// The commit functions run for real; only the detached-fragment backup dep and the
// host-installed prepared-DOM renderer/validator globals are fakes. The direct
// distrust-retry path drives the real prepareParagraphLayout, so the planted
// fixture font backend must answer the snapshot-session prepare and the
// browserFallback bridge must answer the browser-metrics re-prepare.

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

function withEnv(fn, overrides = {}) {
  const saved = saveGlobals([
    "getComputedStyle",
  ]);
  try {
    globalThis.getComputedStyle = (target, pseudo) =>
      target && target._computedValues
        ? computedStyle(target._computedValues)
        : computedStyle();
    // Tests now use the real prepared-dom renderer directly.
    // Validator injection removed per spec: production degradation only by
    // renderer exceptions and capability failures.
    return fn();
  } finally {
    restoreGlobals(saved);
  }
}

function makeElement(initialAttributes = {}, options = {}) {
  const attributes = new Map(Object.entries(initialAttributes));
  const removedAttributes = [];
  const setAttributes = [];
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
    attributes,
    setAttributes,
    removedAttributes,
    getBoundingClientRect: () => ({ width: options.width ?? 320 }),
    getClientRects: () => [],
    parentElement: null,
  };
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

function installFakeRawDom(overrides = {}) {
  const stamped = [];
  return {
    stampRendered: (el) => {
      stamped.push(el);
      if (overrides.stampRendered) overrides.stampRendered(el);
    },
    stamped,
    suspendEngineWrites: (source, action) => {
      const context = getOrCreateEnhanceContext(source);
      let record = context.rawDomParagraphs.get(source);
      if (!record) {
        record = { fragment: null, engineWriteDepth: 0, forwarding: false };
        context.rawDomParagraphs.set(source, record);
      }
      record.engineWriteDepth += 1;
      try {
        return action();
      } finally {
        record.engineWriteDepth -= 1;
      }
    },
  };
}

// A browserFallback bridge whose callbacks answer every shape/metrics request
// with a valid, full-coverage cluster response on the real wire. The direct
// distrust-retry re-prepares through these callbacks.
function makeValidBridge() {
  return {
    shapeJson(req) {
      const parsed = JSON.parse(req);
      const text = parsed.text;
      const start = parsed.range.start;
      const end = parsed.range.end;
      const size = parsed.style.fontSize;
      const clusters = [];
      const glyphs = [];
      let x = 0;
      for (let i = start; i < end; i += 1) {
        const ch = text[i];
        clusters.push({
          range: { start: i, end: i + 1 },
          text: ch,
          displayText: ch,
          fontKey: "cjk-primary",
          advance: size,
          baselineShift: 0,
        });
        glyphs.push({
          id: 100 + i,
          clusterRange: { start: i, end: i + 1 },
          advance: size,
          x,
          y: 0,
          bounds: { left: 0, top: -size * 0.88, right: size, bottom: size * 0.12 },
        });
        x += size;
      }
      return JSON.stringify({
        clusters,
        glyphRuns: [{ range: { start, end }, fontKey: "cjk-primary", glyphs, advance: x, openTypeFeatures: [] }],
        decisions: [{ range: { start, end }, sourceText: text.substring(start, end), displayText: parsed.displayText, fontKey: "cjk-primary", glyphCount: end - start, advance: x, source: "Harness", reason: "harness" }],
      });
    },
    metricsJson() {
      return JSON.stringify({ ascent: 21.2, descent: 5.3, leading: 0, source: "RawTables", typoAscent: 16.7, typoDescent: 2.3 });
    },
  };
}

test("worker happy path: sets four attributes, invokes renderer with options, sets lastMeasure, stamps rawDom, returns null", () => {
  withEnv(() => {
    const source = makeElement({}, { width: 300 });
    const rawDom = installFakeRawDom();


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
      plan: '{"lines":[{"rangeStart":0,"rangeEnd":4}]}',
      semantics: [{ start: 0, end: 2 }],
      inlineBoxes: [{ start: 0, end: 1 }],
    };
    const workerPlan = JSON.stringify(record);
    const inlineObjectMetaJson = JSON.stringify([{ start: 2, end: 3, marginRight: 6 }]);
    const cjkStrongSemanticsJson = JSON.stringify([{ start: 0, end: 1 }]);

    let fallbackCalled = false;
    const result = commitWorkerPreparedParagraph(rawDom, {
      paragraph,
      workerPlan,
      onSnapshotPreparedDomFallback: () => {
        fallbackCalled = true;
      },
      inlineObjectMetaJson,
      cjkStrongSemanticsJson,
    });

    assert.equal(result, null);
    assert.equal(fallbackCalled, false);
    assert.equal(paragraph.source.getAttribute("lang"), "zh-Hans");
    assert.equal(paragraph.source.getAttribute("data-tq-canonical-plain"), null); // not plain due to domInlineObjects
    assert.equal(paragraph.source.getAttribute("data-tq-snapshot-prepared-dom"), "true");
    assert.equal(paragraph.source.getAttribute("data-tq-canonical-source"), "true");
    assert.equal(paragraph.lastMeasure, effectiveLineMeasure(300, 19));

    const renderer = preparedDomRendererModule();
    assert.equal(renderer.renders.length, 1);
    const renderCall = renderer.renders[0];
    assert.equal(renderCall.host, paragraph.source);
    assert.equal(renderCall.plan, record.plan);
    assert.equal(renderCall.locale, "zh-Hans");
    assert.equal(renderCall.rawDomCounterDuringRender, 1);
    assert.equal(getOrCreateEnhanceContext(paragraph.source).rawDomParagraphs.get(paragraph.source)?.engineWriteDepth, 0);

    assert.deepEqual(renderCall.options, {
      sourceText: "hello world",
      semanticReplay: "snapshot-safe",
      semantics: [{ start: 0, end: 2 }],
      inlineBoxes: [{ start: 0, end: 1 }],
      liveSemanticElements: [],
      inlineObjects: [{ start: 2, end: 3, marginRight: 6, element: domObjElement }],
      cjkStrongSemantics: [{ start: 0, end: 1 }],
    });

    assert.equal(rawDom.stamped.length, 1);
    assert.equal(rawDom.stamped[0], paragraph.source);
  }, { validator: () => null });
});

test("worker mismatch: validator issue triggers fallback callback, releases styles, strips attributes, returns unsupported", () => {
  withEnv(() => {
    const rawDom = installFakeRawDom();


    const paragraph = makeParagraph();
    let fallbackIssue = null;

    const result = commitWorkerPreparedParagraph(rawDom, {
      paragraph,
      workerPlan: JSON.stringify({ plan: "{}" }),
      onSnapshotPreparedDomFallback: (issue) => {
        fallbackIssue = issue;
      },
      inlineObjectMetaJson: "[]",
      cjkStrongSemanticsJson: "[]",
    });

    assert.equal(fallbackIssue, "LineHeightMismatch");
    const renderer = preparedDomRendererModule();
    assert.equal(renderer.releases.length, 1);
    assert.equal(renderer.releases[0], paragraph.source);

    assert.equal(paragraph.source.getAttribute("data-tq-snapshot-prepared-dom"), null);
    assert.equal(paragraph.source.getAttribute("data-tq-canonical-plain"), null);
    assert.equal(paragraph.source.getAttribute("data-tq-canonical-source"), null);
    assert.equal(paragraph.source.getAttribute("lang"), null);

    assert.deepEqual(result, {
      kind: "unsupported",
      name: "WorkerPreparedDomContractMismatch",
      detail: "LineHeightMismatch",
      element: paragraph.source,
    });
  }, { validator: () => "LineHeightMismatch" });
});

test("worker rich lowered: removes canonical-plain attribute for rich lowered", () => {
  withEnv(() => {
    const rawDom = installFakeRawDom();


    const source = makeElement({ "data-tq-canonical-plain": "true" });
    const paragraph = makeParagraph({
      source,
      lowered: {
        spans: [{ start: 0, end: 2, style: textStyle() }],
      },
    });

    const result = commitWorkerPreparedParagraph(rawDom, {
      paragraph,
      workerPlan: JSON.stringify({ plan: "{}" }),
      inlineObjectMetaJson: "[]",
      cjkStrongSemanticsJson: "[]",
    });

    assert.equal(result, null);
    assert.equal(paragraph.source.getAttribute("data-tq-canonical-plain"), null);
    assert.ok(paragraph.source.removedAttributes.includes("data-tq-canonical-plain"));
  }, { validator: () => null });
});

test("direct happy path, no live sources: renders with undefined options, sets canonical-plain and canonical-source, stamps rawDom, returns success", () => {
  withEnv(() => {
    const rawDom = installFakeRawDom();


    const paragraph = makeParagraph();
    const preparation = {
      planJson: '{"lines":[]}',
      width: 320,
      measure: 339,
      snapshotFontSessionUsed: false,
    };

    const result = commitPreparedParagraph(rawDom, {
      ffi: {},
      paragraph,
      preparation,
      options: {},
      browserFallback: null,
      onSnapshotPreparedDomFallback: () => {},
      semanticReplayJson: "[]",
      inlineObjectMetaJson: "[]",
      cjkStrongSemanticsJson: "[]",
    });

    assert.deepEqual(result, { kind: "success", measure: 339 });
    assert.equal(paragraph.source.getAttribute("data-tq-canonical-plain"), "true");
    assert.equal(paragraph.source.getAttribute("data-tq-canonical-source"), "true");
    assert.equal(paragraph.source.getAttribute("lang"), "zh-Hans");

    const renderer = preparedDomRendererModule();
    assert.equal(renderer.renders.length, 1);
    assert.equal(renderer.renders[0].options, undefined);

    assert.equal(rawDom.stamped.length, 1);
    assert.equal(rawDom.stamped[0], paragraph.source);
  }, { validator: () => null });
});

test("direct rich path with sourceSpans elements: renders with live-source replay options", () => {
  withEnv(() => {
    const rawDom = installFakeRawDom();


    const spanElement = makeElement();
    const objElement = makeElement();
    const paragraph = makeParagraph({
      lowered: {
        text: "hello world",
        sourceSpans: [{ element: spanElement }],
        domInlineObjects: [{ element: objElement }],
      },
    });

    const preparation = {
      planJson: '{"lines":[]}',
      width: 320,
      measure: 339,
      snapshotFontSessionUsed: false,
    };

    const semanticReplayJson = JSON.stringify([{ sourceIndex: 0, tag: "em" }]);
    const inlineObjectMetaJson = JSON.stringify([{ start: 1, end: 2, marginRight: 5 }]);
    const cjkStrongSemanticsJson = JSON.stringify([{ start: 0, end: 1 }]);

    const result = commitPreparedParagraph(rawDom, {
      ffi: {},
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

    const renderer = preparedDomRendererModule();
    assert.equal(renderer.renders.length, 1);
    assert.deepEqual(renderer.renders[0].options, {
      sourceText: "hello world",
      semanticReplay: "live-source",
      semantics: [{ sourceIndex: 0, tag: "em" }],
      liveSemanticElements: [spanElement],
      inlineObjects: [{ start: 1, end: 2, marginRight: 5, element: objElement }],
      cjkStrongSemantics: [{ start: 0, end: 1 }],
    });
  }, { validator: () => null });
});

test("direct mismatch, snapshotFontSessionUsed: false: three attributes removed, exact-prepared-dom never set, returns PreparedDomRenderMismatch", () => {
  withEnv(() => {
    const rawDom = installFakeRawDom();


    const paragraph = makeParagraph();
    const preparation = {
      planJson: '{"lines":[]}',
      width: 320,
      measure: 339,
      snapshotFontSessionUsed: false,
    };

    let fallbackCalled = false;
    const result = commitPreparedParagraph(rawDom, {
      ffi: {},
      paragraph,
      preparation,
      options: {},
      browserFallback: { bridge: {} },
      onSnapshotPreparedDomFallback: (issue) => {
        fallbackCalled = issue;
      },
    });

    assert.equal(fallbackCalled, "GeometryMismatch");
    assert.equal(
      paragraph.source.setAttributes.some((a) => a.name === "data-tq-snapshot-prepared-dom"),
      false,
    );
    assert.equal(paragraph.source.getAttribute("data-tq-canonical-plain"), null);
    assert.equal(paragraph.source.getAttribute("data-tq-canonical-source"), null);
    assert.equal(paragraph.source.getAttribute("lang"), null);

    assert.deepEqual(result, {
      kind: "unsupported",
      name: "PreparedDomRenderMismatch",
      detail: "GeometryMismatch",
      element: paragraph.source,
    });
  }, { validator: () => "GeometryMismatch" });
});

test("direct mismatch with distrust retry: prepares with browser metrics fallback and commits second plan on success", () => {
  let validateCount = 0;
  const validator = () => {
    validateCount += 1;
    return validateCount === 1 ? "SnapshotSessionMismatch" : null;
  };
  withEnv(() => {
    const rawDom = installFakeRawDom();


    const paragraph = makeParagraph();
    const preparation = {
      planJson: '{"plan":"first"}',
      width: 320,
      measure: 320,
      snapshotFontSessionUsed: true,
    };

    const browserFallback = { bridge: makeValidBridge() };
    const originalOptions = {
      snapshotFontSession: { session: 1 },
      firstLineIndentIc: 2,
    };

    let fallbackReported = null;

    const result = commitPreparedParagraph(rawDom, {
      paragraph,
      preparation,
      options: originalOptions,
      browserFallback,
      onSnapshotPreparedDomFallback: (issue) => {
        fallbackReported = issue;
      },
    });

    assert.equal(fallbackReported, "SnapshotSessionMismatch");

    const renderer = preparedDomRendererModule();
    assert.equal(renderer.renders.length, 2);
    assert.equal(renderer.renders[0].plan, '{"plan":"first"}');
    // The retry re-prepared through the real browser-metrics path over the
    // valid bridge, so the second render carries a real "hello" plan.
    const secondPlan = JSON.parse(renderer.renders[1].plan);
    assert.equal(secondPlan.layoutRevision, "tiqian-layout-v2");
    assert.equal(secondPlan.lines[0].rangeEnd, 5);

    assert.deepEqual(result, { kind: "success", measure: effectiveLineMeasure(320, 19) });
  }, { validator });
});

test("distrust retry returning unsupported: propagated as the final unsupported verdict", () => {
  withEnv(() => {
    const rawDom = installFakeRawDom();


    const paragraph = makeParagraph();
    const preparation = {
      planJson: '{"plan":"first"}',
      width: 320,
      measure: 320,
      snapshotFontSessionUsed: true,
    };

    const bridge = makeValidBridge();
    const originalShapeJson = bridge.shapeJson;
    bridge.shapeJson = function (req) {
      const parsed = JSON.parse(req);
      const inner = JSON.parse(originalShapeJson(req));
      inner.decisions = [{
        range: { start: parsed.range.start, end: parsed.range.end },
        sourceText: parsed.text.substring(parsed.range.start, parsed.range.end),
        displayText: parsed.displayText,
        fontKey: "cjk-primary",
        glyphCount: parsed.range.end - parsed.range.start,
        advance: 0,
        source: "Harness",
        reason: "unsupported glyph",
        capabilityIssue: "BrowserFallbackUnsupported",
      }];
      return JSON.stringify(inner);
    };

    const result = commitPreparedParagraph(rawDom, {
      paragraph,
      preparation,
      options: { snapshotFontSession: {} },
      browserFallback: { bridge },
    });

    assert.deepEqual(result, {
      kind: "unsupported",
      name: "BrowserFallbackUnsupported",
      detail: "unsupported glyph",
      element: paragraph.source,
    });
  }, { validator: () => "SnapshotSessionMismatch" });
});

test("recursion passes browserFallback null: validator fails both renders, prepareParagraphLayout called once, returns PreparedDomRenderMismatch", () => {
  withEnv(() => {
    const rawDom = installFakeRawDom();


    const paragraph = makeParagraph();
    const preparation = {
      planJson: '{"plan":"first"}',
      width: 320,
      measure: 320,
      snapshotFontSessionUsed: true,
    };

    const result = commitPreparedParagraph(rawDom, {
      paragraph,
      preparation,
      options: { snapshotFontSession: {} },
      browserFallback: { bridge: makeValidBridge() },
    });

    const renderer = preparedDomRendererModule();
    assert.equal(renderer.renders.length, 2);

    assert.deepEqual(result, {
      kind: "unsupported",
      name: "PreparedDomRenderMismatch",
      detail: "PersistentMismatch",
      element: paragraph.source,
    });
  }, { validator: () => "PersistentMismatch" });
});