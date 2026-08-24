import assert from "node:assert/strict";
import test from "node:test";

import "./core/engine/commit-prepared-paragraph.js";
import "./core/engine/prepare-paragraph-layout.js";

const commit = globalThis.__TiqianCommitPreparedParagraph;

const COMMIT_GLOBALS = [
  "__TiqianCommitPreparedParagraph",
  "__TiqianPrepareParagraphLayout",
  "__TiqianResponsiveMeasure",
  "__TiqianPreparedDomRenderer",
  "__TiqianPreparedDomValidator",
  "__TiqianCustody",
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

function makeElement(initialAttributes = {}) {
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
    text: "你好",
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

function installFakeResponsiveMeasure(overrides = {}) {
  const config = {
    sourceParagraphWidth: 320,
    effectiveLineMeasure: (width, fontSize) => width + fontSize,
    ...overrides,
  };
  globalThis.__TiqianResponsiveMeasure = {
    effectiveLineMeasure: config.effectiveLineMeasure,
    sourceParagraphWidth: () => config.sourceParagraphWidth,
  };
}

function installFakeCustody(overrides = {}) {
  const stamped = [];
  globalThis.__TiqianCustody = {
    stampRendered: (el) => {
      stamped.push(el);
      if (overrides.stampRendered) overrides.stampRendered(el);
    },
    stamped,
  };
}

function installFakePreparedDomRenderer(overrides = {}) {
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
      if (overrides.render) return overrides.render(host, plan, locale, options);
    },
    release: (host) => {
      releases.push(host);
      if (overrides.release) return overrides.release(host);
      return true;
    },
    releaseRoot: () => true,
    schema: 1,
    layoutRevision: "tiqian-layout-v2",
    renders,
    releases,
    ...overrides,
  };
}

function installFakeValidator(issueFn) {
  globalThis.__TiqianPreparedDomValidator = {
    issue: issueFn || (() => null),
  };
}

test("worker happy path: sets four attributes, invokes renderer with options, sets lastMeasure, stamps custody, returns null", () => {
  const saved = preserveGlobals(COMMIT_GLOBALS);
  try {
    installFakeResponsiveMeasure({
      sourceParagraphWidth: 300,
      effectiveLineMeasure: (w, f) => w + f,
    });
    installFakeCustody();
    installFakePreparedDomRenderer();
    installFakeValidator(() => null);

    const domObjElement = makeElement();
    const paragraph = makeParagraph({
      lowered: {
        text: "你好世界",
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
    const result = commit.commitWorkerPreparedParagraph({
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
    assert.equal(paragraph.lastMeasure, 319);

    const renderer = globalThis.__TiqianPreparedDomRenderer;
    assert.equal(renderer.renders.length, 1);
    const renderCall = renderer.renders[0];
    assert.equal(renderCall.host, paragraph.source);
    assert.equal(renderCall.plan, record.plan);
    assert.equal(renderCall.locale, "zh-Hans");
    assert.equal(renderCall.custodyCounterDuringRender, 1);
    assert.equal(paragraph.source.__tqCustodyEngineWrites, 0);

    assert.deepEqual(renderCall.options, {
      sourceText: "你好世界",
      semanticReplay: "snapshot-safe",
      semantics: [{ start: 0, end: 2 }],
      inlineBoxes: [{ start: 0, end: 1 }],
      liveSemanticElements: [],
      inlineObjects: [{ start: 2, end: 3, marginRight: 6, element: domObjElement }],
      cjkStrongSemantics: [{ start: 0, end: 1 }],
    });

    const custody = globalThis.__TiqianCustody;
    assert.equal(custody.stamped.length, 1);
    assert.equal(custody.stamped[0], paragraph.source);
  } finally {
    restoreGlobals(saved);
  }
});

test("worker mismatch: validator issue triggers fallback callback, releases styles, strips attributes, returns unsupported", () => {
  const saved = preserveGlobals(COMMIT_GLOBALS);
  try {
    installFakeResponsiveMeasure({ sourceParagraphWidth: 320 });
    installFakeCustody();
    installFakePreparedDomRenderer();
    installFakeValidator(() => "LineHeightMismatch");

    const paragraph = makeParagraph();
    let fallbackIssue = null;

    const result = commit.commitWorkerPreparedParagraph({
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
  } finally {
    restoreGlobals(saved);
  }
});

test("worker rich lowered: removes canonical-plain attribute for rich lowered", () => {
  const saved = preserveGlobals(COMMIT_GLOBALS);
  try {
    installFakeResponsiveMeasure({ sourceParagraphWidth: 320 });
    installFakeCustody();
    installFakePreparedDomRenderer();
    installFakeValidator(() => null);

    const source = makeElement({ "data-tq-canonical-plain": "true" });
    const paragraph = makeParagraph({
      source,
      lowered: {
        spans: [{ start: 0, end: 2, style: textStyle() }],
      },
    });

    const result = commit.commitWorkerPreparedParagraph({
      paragraph,
      workerPlan: JSON.stringify({ plan: "{}" }),
      inlineObjectMetaJson: "[]",
      cjkStrongSemanticsJson: "[]",
    });

    assert.equal(result, null);
    assert.equal(paragraph.source.getAttribute("data-tq-canonical-plain"), null);
    assert.ok(paragraph.source.removedAttributes.includes("data-tq-canonical-plain"));
  } finally {
    restoreGlobals(saved);
  }
});

test("direct happy path, no live sources: renders with undefined options, sets canonical-plain and canonical-source, stamps custody, returns success", () => {
  const saved = preserveGlobals(COMMIT_GLOBALS);
  try {
    installFakeResponsiveMeasure();
    installFakeCustody();
    installFakePreparedDomRenderer();
    installFakeValidator(() => null);

    const paragraph = makeParagraph();
    const preparation = {
      planJson: '{"lines":[]}',
      width: 320,
      measure: 339,
      exactFontSessionUsed: false,
    };

    const result = commit.commitPreparedParagraph({
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

    const custody = globalThis.__TiqianCustody;
    assert.equal(custody.stamped.length, 1);
    assert.equal(custody.stamped[0], paragraph.source);
  } finally {
    restoreGlobals(saved);
  }
});

test("direct rich path with sourceSpans elements: renders with live-source replay options", () => {
  const saved = preserveGlobals(COMMIT_GLOBALS);
  try {
    installFakeResponsiveMeasure();
    installFakeCustody();
    installFakePreparedDomRenderer();
    installFakeValidator(() => null);

    const spanElement = makeElement();
    const objElement = makeElement();
    const paragraph = makeParagraph({
      lowered: {
        text: "你好世界",
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

    const result = commit.commitPreparedParagraph({
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
      sourceText: "你好世界",
      semanticReplay: "live-source",
      semantics: [{ sourceIndex: 0, tag: "em" }],
      liveSemanticElements: [spanElement],
      inlineObjects: [{ start: 1, end: 2, marginRight: 5, element: objElement }],
      cjkStrongSemantics: [{ start: 0, end: 1 }],
    });
  } finally {
    restoreGlobals(saved);
  }
});

test("direct mismatch, exactFontSessionUsed: false: three attributes removed, exact-prepared-dom never set, returns PreparedDomRenderMismatch", () => {
  const saved = preserveGlobals(COMMIT_GLOBALS);
  try {
    installFakeResponsiveMeasure();
    installFakeCustody();
    installFakePreparedDomRenderer();
    installFakeValidator(() => "GeometryMismatch");

    const paragraph = makeParagraph();
    const preparation = {
      planJson: '{"lines":[]}',
      width: 320,
      measure: 339,
      exactFontSessionUsed: false,
    };

    let fallbackCalled = false;
    const result = commit.commitPreparedParagraph({
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
  } finally {
    restoreGlobals(saved);
  }
});

test("direct mismatch with distrust retry: prepares with browser metrics fallback and commits second plan on success", () => {
  const saved = preserveGlobals(COMMIT_GLOBALS);
  try {
    installFakeResponsiveMeasure();
    installFakeCustody();
    installFakePreparedDomRenderer();

    let validateCount = 0;
    installFakeValidator(() => {
      validateCount += 1;
      return validateCount === 1 ? "ExactSessionMismatch" : null;
    });

    const paragraph = makeParagraph();
    const preparation = {
      planJson: '{"plan":"first"}',
      width: 320,
      measure: 320,
      exactFontSessionUsed: true,
    };

    const browserFallback = { bridge: { shapeJson: "{}", metricsJson: "{}" } };
    const originalOptions = {
      exactFontSession: { session: 1 },
      firstLineIndentIc: 2,
    };

    let prepareCalledWith = null;
    globalThis.__TiqianPrepareParagraphLayout = {
      prepareParagraphLayout: (ffi, arg) => {
        prepareCalledWith = { ffi, arg };
        return {
          kind: "ready",
          planJson: '{"plan":"second"}',
          width: arg.widthOverride,
          measure: 345,
          exactFontSessionUsed: false,
        };
      },
    };

    const ffiStub = { name: "ffiStub" };
    let fallbackReported = null;

    const result = commit.commitPreparedParagraph({
      ffi: ffiStub,
      paragraph,
      preparation,
      options: originalOptions,
      browserFallback,
      onExactPreparedDomFallback: (issue) => {
        fallbackReported = issue;
      },
    });

    assert.equal(fallbackReported, "ExactSessionMismatch");
    assert.ok(prepareCalledWith);
    assert.equal(prepareCalledWith.ffi, ffiStub);
    assert.equal(prepareCalledWith.arg.paragraph, paragraph);
    assert.equal(prepareCalledWith.arg.widthOverride, 320);
    assert.equal(prepareCalledWith.arg.ignoreUnchangedMeasure, true);
    assert.equal(prepareCalledWith.arg.exactSession, null);
    assert.equal(prepareCalledWith.arg.browserFallback, browserFallback);
    assert.equal(prepareCalledWith.arg.options.exactFontSession, null);
    assert.equal(prepareCalledWith.arg.options.firstLineIndentIc, 2);

    const renderer = globalThis.__TiqianPreparedDomRenderer;
    assert.equal(renderer.renders.length, 2);
    assert.equal(renderer.renders[0].plan, '{"plan":"first"}');
    assert.equal(renderer.renders[1].plan, '{"plan":"second"}');

    assert.deepEqual(result, { kind: "success", measure: 345 });
  } finally {
    restoreGlobals(saved);
  }
});

test("distrust retry returning unsupported: propagated as the final unsupported verdict", () => {
  const saved = preserveGlobals(COMMIT_GLOBALS);
  try {
    installFakeResponsiveMeasure();
    installFakeCustody();
    installFakePreparedDomRenderer();
    installFakeValidator(() => "ExactSessionMismatch");

    const paragraph = makeParagraph();
    const preparation = {
      planJson: '{"plan":"first"}',
      width: 320,
      measure: 320,
      exactFontSessionUsed: true,
    };

    globalThis.__TiqianPrepareParagraphLayout = {
      prepareParagraphLayout: () => ({
        kind: "unsupported",
        name: "BrowserFallbackUnsupported",
        detail: "unsupported glyph",
        element: paragraph.source,
      }),
    };

    const result = commit.commitPreparedParagraph({
      ffi: {},
      paragraph,
      preparation,
      options: { exactFontSession: {} },
      browserFallback: { bridge: {} },
    });

    assert.deepEqual(result, {
      kind: "unsupported",
      name: "BrowserFallbackUnsupported",
      detail: "unsupported glyph",
      element: paragraph.source,
    });
  } finally {
    restoreGlobals(saved);
  }
});

test("distrust retry returning unchanged: throws exact error message", () => {
  const saved = preserveGlobals(COMMIT_GLOBALS);
  try {
    installFakeResponsiveMeasure();
    installFakeCustody();
    installFakePreparedDomRenderer();
    installFakeValidator(() => "ExactSessionMismatch");

    const paragraph = makeParagraph();
    const preparation = {
      planJson: '{"plan":"first"}',
      width: 320,
      measure: 320,
      exactFontSessionUsed: true,
    };

    globalThis.__TiqianPrepareParagraphLayout = {
      prepareParagraphLayout: () => ({
        kind: "unchanged",
      }),
    };

    assert.throws(
      () => {
        commit.commitPreparedParagraph({
          ffi: {},
          paragraph,
          preparation,
          options: { exactFontSession: {} },
          browserFallback: { bridge: {} },
        });
      },
      {
        name: "Error",
        message: "Exact prepared DOM fallback unexpectedly skipped relayout",
      },
    );
  } finally {
    restoreGlobals(saved);
  }
});

test("recursion passes browserFallback null: validator fails both renders, prepareParagraphLayout called once, returns PreparedDomRenderMismatch", () => {
  const saved = preserveGlobals(COMMIT_GLOBALS);
  try {
    installFakeResponsiveMeasure();
    installFakeCustody();
    installFakePreparedDomRenderer();
    installFakeValidator(() => "PersistentMismatch");

    const paragraph = makeParagraph();
    const preparation = {
      planJson: '{"plan":"first"}',
      width: 320,
      measure: 320,
      exactFontSessionUsed: true,
    };

    let prepareCalls = 0;
    globalThis.__TiqianPrepareParagraphLayout = {
      prepareParagraphLayout: () => {
        prepareCalls += 1;
        return {
          kind: "ready",
          planJson: '{"plan":"second"}',
          width: 320,
          measure: 345,
          exactFontSessionUsed: true,
        };
      },
    };

    const result = commit.commitPreparedParagraph({
      ffi: {},
      paragraph,
      preparation,
      options: { exactFontSession: {} },
      browserFallback: { bridge: {} },
    });

    assert.equal(prepareCalls, 1);
    const renderer = globalThis.__TiqianPreparedDomRenderer;
    assert.equal(renderer.renders.length, 2);

    assert.deepEqual(result, {
      kind: "unsupported",
      name: "PreparedDomRenderMismatch",
      detail: "PersistentMismatch",
      element: paragraph.source,
    });
  } finally {
    restoreGlobals(saved);
  }
});
