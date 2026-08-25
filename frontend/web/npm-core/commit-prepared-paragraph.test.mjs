import assert from "node:assert/strict";
import test from "node:test";

import { commitPreparedParagraph, commitWorkerPreparedParagraph } from "./core/engine/commit-prepared-paragraph.js";
import { effectiveLineMeasure } from "./core/engine/responsive-measure.js";

// The commit functions run for real; only the custody dep, the host-installed
// prepared-DOM renderer/validator globals and the ffi are fakes. The direct
// distrust-retry path drives the real prepareParagraphLayout, so the ffi must
// answer the browser-metrics envelope.

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
    "__TiqianPreparedDomRenderer",
    "__TiqianPreparedDomValidator",
  ]);
  try {
    globalThis.getComputedStyle = (target, pseudo) =>
      target && target._computedValues
        ? computedStyle(target._computedValues)
        : computedStyle();
    if (overrides.renderer !== false) {
      const renders = [];
      const releases = [];
      globalThis.__TiqianPreparedDomRenderer = {
        render: (host, plan, locale, options) => {
          renders.push({
            host,
            plan,
            locale,
            options,
            custodyCounterDuringRender: host.__tqCustodyEngineWrites,
          });
        },
        release: (host) => {
          releases.push(host);
          return true;
        },
        releaseRoot: () => true,
        schema: 1,
        layoutRevision: "tiqian-layout-v2",
        renders,
        releases,
      };
    }
    if (overrides.validator !== undefined) {
      globalThis.__TiqianPreparedDomValidator = {
        issue: overrides.validator,
      };
    }
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

function installFakeCustody(overrides = {}) {
  const stamped = [];
  return {
    stampRendered: (el) => {
      stamped.push(el);
      if (overrides.stampRendered) overrides.stampRendered(el);
    },
    stamped,
  };
}

function makeFakeFfi(overrides = {}) {
  const calls = { diagnostics: [], browserMetrics: [] };
  return {
    _calls: calls,
    precomputeParagraphWithDiagnostics: function () {
      calls.diagnostics.push(Array.from(arguments));
      if (overrides.diagnosticsThrow) throw overrides.diagnosticsThrow;
      return overrides.diagnosticsEnvelope;
    },
    precomputeParagraphWithBrowserMetrics: function () {
      calls.browserMetrics.push(Array.from(arguments));
      return overrides.browserMetricsEnvelope;
    },
  };
}

function browserEnvelope(planJson) {
  return JSON.stringify({
    plan: planJson,
    diagnostics: { capabilityIssues: [], advanceSuspects: [] },
  });
}

function browserEnvelopeWithIssue(name, reason) {
  return JSON.stringify({
    plan: "{}",
    diagnostics: {
      capabilityIssues: [{ name, reason }],
      advanceSuspects: [],
    },
  });
}

test("worker happy path: sets four attributes, invokes renderer with options, sets lastMeasure, stamps custody, returns null", () => {
  withEnv(() => {
    const source = makeElement({}, { width: 300 });
    const custody = installFakeCustody();
    const deps = { custody };

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
    const result = commitWorkerPreparedParagraph(deps, {
      paragraph,
      workerPlan,
      onExactPreparedDomFallback: () => {
        fallbackCalled = true;
      },
      inlineObjectMetaJson,
      cjkStrongSemanticsJson,
    });

    assert.equal(result, null);
    assert.equal(fallbackCalled, false);
    assert.equal(paragraph.source.getAttribute("lang"), "zh-Hans");
    assert.equal(paragraph.source.getAttribute("data-tq-canonical-plain"), null); // not plain due to domInlineObjects
    assert.equal(paragraph.source.getAttribute("data-tq-exact-prepared-dom"), "true");
    assert.equal(paragraph.source.getAttribute("data-tq-canonical-source"), "true");
    assert.equal(paragraph.lastMeasure, effectiveLineMeasure(300, 19));

    const renderer = globalThis.__TiqianPreparedDomRenderer;
    assert.equal(renderer.renders.length, 1);
    const renderCall = renderer.renders[0];
    assert.equal(renderCall.host, paragraph.source);
    assert.equal(renderCall.plan, record.plan);
    assert.equal(renderCall.locale, "zh-Hans");
    assert.equal(renderCall.custodyCounterDuringRender, 1);
    assert.equal(paragraph.source.__tqCustodyEngineWrites, 0);

    assert.deepEqual(renderCall.options, {
      sourceText: "hello world",
      semanticReplay: "snapshot-safe",
      semantics: [{ start: 0, end: 2 }],
      inlineBoxes: [{ start: 0, end: 1 }],
      liveSemanticElements: [],
      inlineObjects: [{ start: 2, end: 3, marginRight: 6, element: domObjElement }],
      cjkStrongSemantics: [{ start: 0, end: 1 }],
    });

    assert.equal(custody.stamped.length, 1);
    assert.equal(custody.stamped[0], paragraph.source);
  }, { validator: () => null });
});

test("worker mismatch: validator issue triggers fallback callback, releases styles, strips attributes, returns unsupported", () => {
  withEnv(() => {
    const custody = installFakeCustody();
    const deps = { custody };

    const paragraph = makeParagraph();
    let fallbackIssue = null;

    const result = commitWorkerPreparedParagraph(deps, {
      paragraph,
      workerPlan: JSON.stringify({ plan: "{}" }),
      onExactPreparedDomFallback: (issue) => {
        fallbackIssue = issue;
      },
      inlineObjectMetaJson: "[]",
      cjkStrongSemanticsJson: "[]",
    });

    assert.equal(fallbackIssue, "LineHeightMismatch");
    const renderer = globalThis.__TiqianPreparedDomRenderer;
    assert.equal(renderer.releases.length, 1);
    assert.equal(renderer.releases[0], paragraph.source);

    assert.equal(paragraph.source.getAttribute("data-tq-exact-prepared-dom"), null);
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
    const custody = installFakeCustody();
    const deps = { custody };

    const source = makeElement({ "data-tq-canonical-plain": "true" });
    const paragraph = makeParagraph({
      source,
      lowered: {
        spans: [{ start: 0, end: 2, style: textStyle() }],
      },
    });

    const result = commitWorkerPreparedParagraph(deps, {
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

test("direct happy path, no live sources: renders with undefined options, sets canonical-plain and canonical-source, stamps custody, returns success", () => {
  withEnv(() => {
    const custody = installFakeCustody();
    const deps = { custody };

    const paragraph = makeParagraph();
    const preparation = {
      planJson: '{"lines":[]}',
      width: 320,
      measure: 339,
      exactFontSessionUsed: false,
    };

    const result = commitPreparedParagraph(deps, {
      ffi: {},
      paragraph,
      preparation,
      options: {},
      browserFallback: null,
      onExactPreparedDomFallback: () => {},
      semanticReplayJson: "[]",
      inlineObjectMetaJson: "[]",
      cjkStrongSemanticsJson: "[]",
    });

    assert.deepEqual(result, { kind: "success", measure: 339 });
    assert.equal(paragraph.source.getAttribute("data-tq-canonical-plain"), "true");
    assert.equal(paragraph.source.getAttribute("data-tq-canonical-source"), "true");
    assert.equal(paragraph.source.getAttribute("lang"), "zh-Hans");

    const renderer = globalThis.__TiqianPreparedDomRenderer;
    assert.equal(renderer.renders.length, 1);
    assert.equal(renderer.renders[0].options, undefined);

    assert.equal(custody.stamped.length, 1);
    assert.equal(custody.stamped[0], paragraph.source);
  }, { validator: () => null });
});

test("direct rich path with sourceSpans elements: renders with live-source replay options", () => {
  withEnv(() => {
    const custody = installFakeCustody();
    const deps = { custody };

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
      exactFontSessionUsed: false,
    };

    const semanticReplayJson = JSON.stringify([{ sourceIndex: 0, tag: "em" }]);
    const inlineObjectMetaJson = JSON.stringify([{ start: 1, end: 2, marginRight: 5 }]);
    const cjkStrongSemanticsJson = JSON.stringify([{ start: 0, end: 1 }]);

    const result = commitPreparedParagraph(deps, {
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

    const renderer = globalThis.__TiqianPreparedDomRenderer;
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

test("direct mismatch, exactFontSessionUsed: false: three attributes removed, exact-prepared-dom never set, returns PreparedDomRenderMismatch", () => {
  withEnv(() => {
    const custody = installFakeCustody();
    const deps = { custody };

    const paragraph = makeParagraph();
    const preparation = {
      planJson: '{"lines":[]}',
      width: 320,
      measure: 339,
      exactFontSessionUsed: false,
    };

    let fallbackCalled = false;
    const result = commitPreparedParagraph(deps, {
      ffi: {},
      paragraph,
      preparation,
      options: {},
      browserFallback: { bridge: {} },
      onExactPreparedDomFallback: (issue) => {
        fallbackCalled = issue;
      },
    });

    assert.equal(fallbackCalled, "GeometryMismatch");
    assert.equal(
      paragraph.source.setAttributes.some((a) => a.name === "data-tq-exact-prepared-dom"),
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
    return validateCount === 1 ? "ExactSessionMismatch" : null;
  };
  withEnv(() => {
    const custody = installFakeCustody();
    const deps = { custody };

    const paragraph = makeParagraph();
    const preparation = {
      planJson: '{"plan":"first"}',
      width: 320,
      measure: 320,
      exactFontSessionUsed: true,
    };

    const browserFallback = { bridge: { shapeJson: () => "{}", metricsJson: () => "{}" } };
    const originalOptions = {
      exactFontSession: { session: 1 },
      firstLineIndentIc: 2,
    };

    const ffi = makeFakeFfi({
      browserMetricsEnvelope: browserEnvelope('{"plan":"second"}'),
    });
    let fallbackReported = null;

    const result = commitPreparedParagraph(deps, {
      ffi,
      paragraph,
      preparation,
      options: originalOptions,
      browserFallback,
      onExactPreparedDomFallback: (issue) => {
        fallbackReported = issue;
      },
    });

    assert.equal(fallbackReported, "ExactSessionMismatch");
    // The retry re-prepared through the real browser-metrics lane, so the ffi
    // saw one browser-metrics call (no exact-session call for the fallback).
    assert.equal(ffi._calls.diagnostics.length, 0);
    assert.equal(ffi._calls.browserMetrics.length, 1);

    const renderer = globalThis.__TiqianPreparedDomRenderer;
    assert.equal(renderer.renders.length, 2);
    assert.equal(renderer.renders[0].plan, '{"plan":"first"}');
    assert.equal(renderer.renders[1].plan, '{"plan":"second"}');

    assert.deepEqual(result, { kind: "success", measure: effectiveLineMeasure(320, 19) });
  }, { validator });
});

test("distrust retry returning unsupported: propagated as the final unsupported verdict", () => {
  withEnv(() => {
    const custody = installFakeCustody();
    const deps = { custody };

    const paragraph = makeParagraph();
    const preparation = {
      planJson: '{"plan":"first"}',
      width: 320,
      measure: 320,
      exactFontSessionUsed: true,
    };

    const ffi = makeFakeFfi({
      browserMetricsEnvelope: browserEnvelopeWithIssue("BrowserFallbackUnsupported", "unsupported glyph"),
    });

    const result = commitPreparedParagraph(deps, {
      ffi,
      paragraph,
      preparation,
      options: { exactFontSession: {} },
      browserFallback: { bridge: { shapeJson: () => "{}", metricsJson: () => "{}" } },
    });

    assert.deepEqual(result, {
      kind: "unsupported",
      name: "BrowserFallbackUnsupported",
      detail: "unsupported glyph",
      element: paragraph.source,
    });
  }, { validator: () => "ExactSessionMismatch" });
});

test("recursion passes browserFallback null: validator fails both renders, prepareParagraphLayout called once, returns PreparedDomRenderMismatch", () => {
  withEnv(() => {
    const custody = installFakeCustody();
    const deps = { custody };

    const paragraph = makeParagraph();
    const preparation = {
      planJson: '{"plan":"first"}',
      width: 320,
      measure: 320,
      exactFontSessionUsed: true,
    };

    const ffi = makeFakeFfi({
      browserMetricsEnvelope: browserEnvelope('{"plan":"second"}'),
    });

    const result = commitPreparedParagraph(deps, {
      ffi,
      paragraph,
      preparation,
      options: { exactFontSession: {} },
      browserFallback: { bridge: { shapeJson: () => "{}", metricsJson: () => "{}" } },
    });

    // One exact-session prepare (the first commit uses the given preparation,
    // so none) and one browser-metrics prepare for the distrust retry.
    assert.equal(ffi._calls.diagnostics.length, 0);
    assert.equal(ffi._calls.browserMetrics.length, 1);
    const renderer = globalThis.__TiqianPreparedDomRenderer;
    assert.equal(renderer.renders.length, 2);

    assert.deepEqual(result, {
      kind: "unsupported",
      name: "PreparedDomRenderMismatch",
      detail: "PersistentMismatch",
      element: paragraph.source,
    });
  }, { validator: () => "PersistentMismatch" });
});