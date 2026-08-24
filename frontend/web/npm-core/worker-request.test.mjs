import assert from "node:assert/strict";
import test from "node:test";

import "./core/engine/lifecycle.js";
import "./core/engine/eligibility.js";
import "./core/engine/worker-request.js";

const lifecycle = globalThis.__TiqianLifecycle;
const eligibility = globalThis.__TiqianEligibility;
const workerRequest = globalThis.__TiqianWorkerRequest;

const MEASURE_GLOBALS = ["__TiqianResponsiveMeasure"];

// Root-overload tests also install or replace the eligibility, markdown
// lowering, and lifecycle bridges, plus a fake getComputedStyle for
// withRootDefaults, so they must preserve and restore those.
const ROOT_GLOBALS = [
  "__TiqianResponsiveMeasure",
  "__TiqianEligibility",
  "__TiqianMarkdownLowering",
  "__TiqianLifecycle",
  "getComputedStyle",
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

// Fake measure bridge: sourceParagraphWidth is fixed per test through config;
// effectiveLineMeasure is overridable as a function.
function installFakeResponsiveMeasure(overrides = {}) {
  const config = {
    sourceParagraphWidth: 320,
    effectiveLineMeasure: (width, fontSize) => width,
    ...overrides,
  };
  globalThis.__TiqianResponsiveMeasure = {
    effectiveLineMeasure: config.effectiveLineMeasure,
    sourceParagraphWidth: () => config.sourceParagraphWidth,
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

function span(overrides = {}) {
  return {
    start: 0,
    end: 2,
    style: textStyle(),
    ...overrides,
  };
}

function inlineBoxStyle(overrides = {}) {
  return {
    inlineStart: 0,
    inlineEnd: 0,
    marginRight: 0,
    letterSpacing: 0,
    boxDecorationBreak: "slice",
    ...overrides,
  };
}

function sourceSpan(overrides = {}) {
  return {
    start: 0,
    end: 2,
    element: { tagName: "EM", attributes: [] },
    depth: 0,
    cjkStrongBaseWeight: null,
    computedColor: null,
    inlineBoxStyle: inlineBoxStyle(),
    ...overrides,
  };
}

function paragraph(overrides = {}) {
  return {
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
    ...overrides,
  };
}

function element(tagName = "P") {
  return { tagName };
}

// Real options object through the lifecycle decoder; override fields like a
// host options bag. The default session is the conforming happy path.
function buildOptions(overrides = {}) {
  return lifecycle.optionsFromJs({
    exactFontSession: { status: "conforming", sessionId: "s1" },
    ...overrides,
  });
}

// Rich fixture exercising every wire field: quoted text with a control char,
// two multi-family spans with non-integral floats, one inline box, one inline
// object, a bogus line-break policy, two source spans with attributes, and
// unsorted duplicate source boundaries.
const RICH_PARAGRAPH_ELEMENT = element("P");

const RICH_LOWERED = paragraph({
  text: 'a"b\u0001c',
  textStyle: textStyle({ fontFamilies: ["Serif A", "Serif B"] }),
  sourceBoundaries: [4, 2, 8, 2, 4],
  spans: [
    span({
      start: 0,
      end: 4,
      style: textStyle({
        fontFamilies: ["A", "B"],
        fontSize: 12.5,
        fontWeight: 500,
        italic: true,
        baselineShift: 1.5,
      }),
    }),
    span({
      start: 4,
      end: 8,
      style: textStyle({
        fontFamilies: ["C"],
        fontSize: 13.25,
        fontWeight: 600,
        italic: false,
        baselineShift: 0,
      }),
    }),
  ],
  inlineBoxes: [{ start: 8, end: 10, inlineStart: 1.5, inlineEnd: 2.25 }],
  lineBreakSpans: [{ start: 2, end: 6, policy: "BOGUS" }],
  inlineObjects: [{ start: 10, end: 11, advance: 6.5, ascent: 5, descent: 1.25 }],
  sourceSpans: [
    sourceSpan({
      start: 0,
      end: 2,
      element: { tagName: "EM", attributes: [{ name: "class", value: "x" }] },
      depth: 1,
    }),
    sourceSpan({
      start: 2,
      end: 4,
      element: {
        tagName: "SPAN",
        attributes: [
          { name: "data-x", value: "y" },
          { name: "title", value: "t" },
        ],
      },
      depth: 2,
    }),
  ],
});

const RICH_EXPECTED =
  "{" +
  '"text":"a\\"b\\u0001c",' +
  '"maxWidthPx":678.9,' +
  '"fontFamilies":"Serif A\\u001fSerif B",' +
  '"fontSizePx":19,' +
  '"lineHeightPx":28,' +
  '"locale":"zh-Hans",' +
  '"fontWeight":400,' +
  '"italic":false,' +
  '"firstLineIndentIc":2,' +
  '"sourceBoundaries":"2,4,8",' +
  '"textSpans":"0\\u001d4\\u001dA\\u001fB\\u001d12.5\\u001d500\\u001dtrue\\u001d1.5\\u001e4\\u001d8\\u001dC\\u001d13.25\\u001d600\\u001dfalse\\u001d0",' +
  '"inlineBoxes":"8\\u001d10\\u001d1.5\\u001d2.25\\u001dNarrow",' +
  '"lineBreakSpans":"2\\u001d6\\u001dProgressiveTechnical",' +
  '"inlineObjects":"10\\u001d11\\u001d6.5\\u001d5\\u001d1.25",' +
  '"semantics":[{"start":0,"end":2,"tagName":"em","attributes":[["class","x"]],"sourceIndex":0,"order":1},' +
  '{"start":2,"end":4,"tagName":"span","attributes":[["data-x","y"],["title","t"]],"sourceIndex":1,"order":2}],' +
  '"renderInlineBoxes":[{"start":8,"end":10,"inlineStartPx":1.5,"inlineEndPx":2.25,"outerSpacing":"Narrow"}],' +
  '"sourceTag":"p"}';

test("workerLayoutRequestJson emits the whole wire request for a rich fixture", () => {
  const actual = workerRequest.workerLayoutRequestJson(
    RICH_PARAGRAPH_ELEMENT,
    RICH_LOWERED,
    678.9,
    2,
  );
  assert.equal(actual, RICH_EXPECTED);
});

test("workerLayoutRequestJson emits the four separator joins as exact substrings", () => {
  const actual = workerRequest.workerLayoutRequestJson(
    RICH_PARAGRAPH_ELEMENT,
    RICH_LOWERED,
    678.9,
    2,
  );
  assert.ok(actual.includes(
    "0\\u001d4\\u001dA\\u001fB\\u001d12.5\\u001d500\\u001dtrue\\u001d1.5\\u001e4\\u001d8\\u001dC\\u001d13.25\\u001d600\\u001dfalse\\u001d0",
  ));
  assert.ok(actual.includes("8\\u001d10\\u001d1.5\\u001d2.25\\u001dNarrow"));
  assert.ok(actual.includes("2\\u001d6\\u001dProgressiveTechnical"));
  assert.ok(actual.includes("10\\u001d11\\u001d6.5\\u001d5\\u001d1.25"));
});

test("workerLayoutRequestJson emits semantics attributes verbatim and lowercases the source tag", () => {
  const actual = workerRequest.workerLayoutRequestJson(
    RICH_PARAGRAPH_ELEMENT,
    RICH_LOWERED,
    678.9,
    2,
  );
  assert.ok(actual.includes('"attributes":[["class","x"]]'));
  assert.ok(actual.includes('"attributes":[["data-x","y"],["title","t"]]'));
  assert.ok(actual.includes('"tagName":"em"'));
  assert.ok(actual.includes('"sourceTag":"p"'));
});

test("workerLayoutRequestJson output round-trips through JSON.parse into the structured shape", () => {
  const actual = workerRequest.workerLayoutRequestJson(
    RICH_PARAGRAPH_ELEMENT,
    RICH_LOWERED,
    678.9,
    2,
  );
  assert.deepEqual(JSON.parse(actual), {
    text: 'a"b\u0001c',
    maxWidthPx: 678.9,
    fontFamilies: "Serif A\u001fSerif B",
    fontSizePx: 19,
    lineHeightPx: 28,
    locale: "zh-Hans",
    fontWeight: 400,
    italic: false,
    firstLineIndentIc: 2,
    sourceBoundaries: "2,4,8",
    textSpans:
      "0\u001d4\u001dA\u001fB\u001d12.5\u001d500\u001dtrue\u001d1.5\u001e4\u001d8\u001dC\u001d13.25\u001d600\u001dfalse\u001d0",
    inlineBoxes: "8\u001d10\u001d1.5\u001d2.25\u001dNarrow",
    lineBreakSpans: "2\u001d6\u001dProgressiveTechnical",
    inlineObjects: "10\u001d11\u001d6.5\u001d5\u001d1.25",
    semantics: [
      { start: 0, end: 2, tagName: "em", attributes: [["class", "x"]], sourceIndex: 0, order: 1 },
      { start: 2, end: 4, tagName: "span", attributes: [["data-x", "y"], ["title", "t"]], sourceIndex: 1, order: 2 },
    ],
    renderInlineBoxes: [
      { start: 8, end: 10, inlineStartPx: 1.5, inlineEndPx: 2.25, outerSpacing: "Narrow" },
    ],
    sourceTag: "p",
  });
});

test("workerLayoutRequest returns null without a conforming exact font session", () => {
  const globals = preserveGlobals(MEASURE_GLOBALS);
  installFakeResponsiveMeasure();
  try {
    const nonConforming = lifecycle.optionsFromJs({
      exactFontSession: { status: "unavailable", sessionId: "s1" },
    });
    assert.equal(workerRequest.workerLayoutRequest(element(), paragraph(), nonConforming), null);
    const omitted = lifecycle.optionsFromJs({});
    assert.equal(workerRequest.workerLayoutRequest(element(), paragraph(), omitted), null);
  } finally {
    restoreGlobals(globals);
  }
});

test("workerLayoutRequest returns null for a decorated paragraph", () => {
  const globals = preserveGlobals(MEASURE_GLOBALS);
  installFakeResponsiveMeasure();
  try {
    const lowered = paragraph({
      decorations: [{ start: 0, end: 2, kind: "Emphasis" }],
    });
    assert.equal(workerRequest.workerLayoutRequest(element(), lowered, buildOptions()), null);
  } finally {
    restoreGlobals(globals);
  }
});

test("workerLayoutRequest returns null for a clone edge at the inclusive epsilon", () => {
  const globals = preserveGlobals(MEASURE_GLOBALS);
  installFakeResponsiveMeasure();
  try {
    const lowered = paragraph({
      sourceSpans: [
        sourceSpan({ inlineBoxStyle: inlineBoxStyle({ boxDecorationBreak: "clone", inlineStart: 0.01 }) }),
      ],
    });
    assert.equal(workerRequest.workerLayoutRequest(element(), lowered, buildOptions()), null);
  } finally {
    restoreGlobals(globals);
  }
});

test("workerLayoutRequest builds for clone boxes below the epsilon", () => {
  const globals = preserveGlobals(MEASURE_GLOBALS);
  installFakeResponsiveMeasure();
  try {
    const lowered = paragraph({
      sourceSpans: [
        sourceSpan({
          inlineBoxStyle: inlineBoxStyle({ boxDecorationBreak: "clone", inlineStart: 0.005, inlineEnd: -0.005 }),
        }),
      ],
    });
    assert.notEqual(workerRequest.workerLayoutRequest(element(), lowered, buildOptions()), null);
  } finally {
    restoreGlobals(globals);
  }
});

test("workerLayoutRequest builds for a non-clone box with large edges", () => {
  const globals = preserveGlobals(MEASURE_GLOBALS);
  installFakeResponsiveMeasure();
  try {
    const lowered = paragraph({
      sourceSpans: [
        sourceSpan({ inlineBoxStyle: inlineBoxStyle({ boxDecorationBreak: "slice", inlineStart: 5, inlineEnd: -5 }) }),
      ],
    });
    assert.notEqual(workerRequest.workerLayoutRequest(element(), lowered, buildOptions()), null);
  } finally {
    restoreGlobals(globals);
  }
});

test("workerLayoutRequest returns null for a locale-mismatching span", () => {
  const globals = preserveGlobals(MEASURE_GLOBALS);
  installFakeResponsiveMeasure();
  try {
    const lowered = paragraph({
      spans: [span({ style: textStyle({ locale: "ja" }) })],
    });
    assert.equal(workerRequest.workerLayoutRequest(element(), lowered, buildOptions()), null);
  } finally {
    restoreGlobals(globals);
  }
});

test("workerLayoutRequest returns null for non-finite or non-positive raw widths", () => {
  const globals = preserveGlobals(MEASURE_GLOBALS);
  try {
    for (const width of [0, Number.NaN, -3]) {
      installFakeResponsiveMeasure({ sourceParagraphWidth: width });
      assert.equal(workerRequest.workerLayoutRequest(element(), paragraph(), buildOptions()), null);
    }
  } finally {
    restoreGlobals(globals);
  }
});

test("workerLayoutRequest emits 0 first-line indent for LI and the option value otherwise", () => {
  const globals = preserveGlobals(MEASURE_GLOBALS);
  installFakeResponsiveMeasure();
  try {
    const li = workerRequest.workerLayoutRequest(
      element("LI"),
      paragraph(),
      buildOptions({ firstLineIndentIc: 2 }),
    );
    assert.equal(JSON.parse(li).firstLineIndentIc, 0);

    const nonLi = workerRequest.workerLayoutRequest(
      element("P"),
      paragraph(),
      buildOptions({ firstLineIndentIc: 2 }),
    );
    assert.equal(JSON.parse(nonLi).firstLineIndentIc, 2);
  } finally {
    restoreGlobals(globals);
  }
});

test("workerLayoutRequest emits the effective line measure as maxWidthPx", () => {
  const globals = preserveGlobals(MEASURE_GLOBALS);
  const fontSizeArguments = [];
  installFakeResponsiveMeasure({
    sourceParagraphWidth: 320,
    effectiveLineMeasure: (width, fontSize) => {
      fontSizeArguments.push(fontSize);
      return 777.25;
    },
  });
  try {
    const result = workerRequest.workerLayoutRequest(element(), paragraph(), buildOptions());
    assert.equal(JSON.parse(result).maxWidthPx, 777.25);
    assert.deepEqual(fontSizeArguments, [19]);
  } finally {
    restoreGlobals(globals);
  }
});

// --- Root overload (workerLayoutRequestForRoot) ---

// Install an eligible, snapshot-friendly, happy-path environment: real
// lifecycle (with a fake getComputedStyle for withRootDefaults), permissive
// eligibility, responsive measure, and a stub lowering. Returns the stubbed
// lower function for per-test assertions.
function installRootHappyPath(lowerImpl = null) {
  installFakeResponsiveMeasure();
  globalThis.getComputedStyle = () => ({ getPropertyValue: () => "" });
  globalThis.__TiqianEligibility = { shouldTryParagraph: () => true };
  const lowerCalls = [];
  const lowerStub = lowerImpl ||
    (() => ({ ok: true, lowered: paragraph() }));
  globalThis.__TiqianMarkdownLowering = {
    lower: (element, options, helpers) => {
      lowerCalls.push({ element, options, helpers });
      return lowerStub(element, options, helpers);
    },
  };
  return { lowerCalls, lowerStub };
}

// A fake element whose closest() returns the given owner. contains() on the
// root is configurable to model whether the owner lives under the root.
function scopeParagraph(closestResult, tagName = "P") {
  return {
    tagName,
    closest: (selector) => closestResult,
    getAttribute: () => null,
  };
}

function scopeRoot(containsResult = true) {
  return {
    tagName: "DIV",
    contains: () => containsResult,
  };
}

const ROOT_FFI = {
  classifyFontRole: (text, start, end, locale) => "other",
  unsupportedInlineShapingProperties: () => ["font-style"],
  firstDivergentInlineShapingProperty: (elementValues, paragraphValues) => null,
};

function rootOptions(overrides = {}) {
  return buildOptions(overrides);
}

test("workerLayoutRequestForRoot returns null when closest resolves to a nested owner under the root", () => {
  const globals = preserveGlobals(ROOT_GLOBALS);
  try {
    const owner = scopeParagraph(null, "SECTION");
    const root = scopeRoot(true);
    const paragraphEl = scopeParagraph(owner);
    installRootHappyPath();
    assert.equal(
      workerRequest.workerLayoutRequestForRoot(ROOT_FFI, root, paragraphEl, rootOptions()),
      null,
    );
  } finally {
    restoreGlobals(globals);
  }
});

test("workerLayoutRequestForRoot passes the root gate when owner is the root", () => {
  const globals = preserveGlobals(ROOT_GLOBALS);
  try {
    const root = scopeRoot();
    const paragraphEl = scopeParagraph(root);
    installRootHappyPath();
    assert.notEqual(
      workerRequest.workerLayoutRequestForRoot(ROOT_FFI, root, paragraphEl, rootOptions()),
      null,
    );
  } finally {
    restoreGlobals(globals);
  }
});

test("workerLayoutRequestForRoot passes the root gate when no owner is found", () => {
  const globals = preserveGlobals(ROOT_GLOBALS);
  try {
    const root = scopeRoot();
    const paragraphEl = scopeParagraph(null);
    installRootHappyPath();
    assert.notEqual(
      workerRequest.workerLayoutRequestForRoot(ROOT_FFI, root, paragraphEl, rootOptions()),
      null,
    );
  } finally {
    restoreGlobals(globals);
  }
});

test("workerLayoutRequestForRoot returns null when shouldTryParagraph is false", () => {
  const globals = preserveGlobals(ROOT_GLOBALS);
  try {
    const root = scopeRoot();
    const paragraphEl = scopeParagraph(root);
    installFakeResponsiveMeasure();
    globalThis.__TiqianEligibility = { shouldTryParagraph: () => false };
    globalThis.__TiqianMarkdownLowering = { lower: () => ({ ok: true, lowered: paragraph() }) };
    assert.equal(
      workerRequest.workerLayoutRequestForRoot(ROOT_FFI, root, paragraphEl, rootOptions()),
      null,
    );
  } finally {
    restoreGlobals(globals);
  }
});

test("workerLayoutRequestForRoot returns null when snapshot exact layout is disallowed", () => {
  const globals = preserveGlobals(ROOT_GLOBALS);
  try {
    const root = scopeRoot();
    const paragraphEl = scopeParagraph(root);
    installRootHappyPath();
    const options = rootOptions({ fontSize: 20 });
    assert.equal(
      workerRequest.workerLayoutRequestForRoot(ROOT_FFI, root, paragraphEl, options),
      null,
    );
  } finally {
    restoreGlobals(globals);
  }
});

test("workerLayoutRequestForRoot returns null when the lowering bridge throws", () => {
  const globals = preserveGlobals(ROOT_GLOBALS);
  try {
    const root = scopeRoot();
    const paragraphEl = scopeParagraph(root);
    installRootHappyPath(() => {
      throw new Error("boom");
    });
    assert.equal(
      workerRequest.workerLayoutRequestForRoot(ROOT_FFI, root, paragraphEl, rootOptions()),
      null,
    );
  } finally {
    restoreGlobals(globals);
  }
});

test("workerLayoutRequestForRoot returns null when lowering reports ok !== true and never reads the issue", () => {
  const globals = preserveGlobals(ROOT_GLOBALS);
  try {
    const root = scopeRoot();
    const paragraphEl = scopeParagraph(root);
    // A getter records access to result.issue: the root overload must not read
    // it, only processParagraph reports lowering issues.
    let issueRead = false;
    const issue = {
      get name() {
        issueRead = true;
        return "UnsupportedParagraph";
      },
      get detail() {
        issueRead = true;
        return "unsupported";
      },
    };
    installRootHappyPath(() => ({ ok: false, issue }));
    assert.equal(
      workerRequest.workerLayoutRequestForRoot(ROOT_FFI, root, paragraphEl, rootOptions()),
      null,
    );
    assert.equal(issueRead, false);
  } finally {
    restoreGlobals(globals);
  }
});

test("workerLayoutRequestForRoot builds the lowering options and helpers shape", () => {
  const globals = preserveGlobals(ROOT_GLOBALS);
  try {
    const root = scopeRoot();
    const paragraphEl = scopeParagraph(root);
    const { lowerCalls } = installRootHappyPath();
    const options = rootOptions();
    const result = workerRequest.workerLayoutRequestForRoot(ROOT_FFI, root, paragraphEl, options);
    assert.notEqual(result, null);
    assert.equal(lowerCalls.length, 1);
    const call = lowerCalls[0];
    assert.equal(call.element, paragraphEl);
    // Options mirror loweringOptionsJs: fontSize/lineHeight nullable,
    // strongAsEmphasisMarks boolean, locale fixed to LOWERING_DEFAULT_LOCALE.
    assert.equal(call.options.fontSize, options.fontSize);
    assert.equal(call.options.lineHeight, options.lineHeight);
    assert.equal(call.options.strongAsEmphasisMarks, options.strongAsEmphasisMarks);
    assert.equal(call.options.locale, "zh-Hans");
    // Helpers mirror loweringHelpersJs.
    assert.equal(call.helpers.classifyRole, ROOT_FFI.classifyFontRole);
    assert.deepEqual(call.helpers.inlineShapingProperties, ["font-style"]);
  } finally {
    restoreGlobals(globals);
  }
});

test("workerLayoutRequestForRoot inlineShapingDecision wraps the ffi divergence result", () => {
  const globals = preserveGlobals(ROOT_GLOBALS);
  try {
    const root = scopeRoot();
    const paragraphEl = scopeParagraph(root);
    const ffi = {
      classifyFontRole: (t, s, e, l) => "other",
      unsupportedInlineShapingProperties: () => ["font-style"],
      firstDivergentInlineShapingProperty: (elementValues, paragraphValues) => "fontStyle",
    };
    const { lowerCalls } = installRootHappyPath();
    const result = workerRequest.workerLayoutRequestForRoot(ffi, root, paragraphEl, rootOptions());
    assert.notEqual(result, null);
    const decision = lowerCalls[0].helpers.inlineShapingDecision("em", [], []);
    assert.deepEqual(decision, {
      name: "UnsupportedInlineShapingStyle",
      detail: "em:fontStyle",
    });
  } finally {
    restoreGlobals(globals);
  }
});

test("workerLayoutRequestForRoot inlineShapingDecision returns null for a null divergence property", () => {
  const globals = preserveGlobals(ROOT_GLOBALS);
  try {
    const root = scopeRoot();
    const paragraphEl = scopeParagraph(root);
    const { lowerCalls } = installRootHappyPath();
    const result = workerRequest.workerLayoutRequestForRoot(ROOT_FFI, root, paragraphEl, rootOptions());
    assert.notEqual(result, null);
    const decision = lowerCalls[0].helpers.inlineShapingDecision("em", [], []);
    assert.equal(decision, null);
  } finally {
    restoreGlobals(globals);
  }
});

test("workerLayoutRequestForRoot serializes the lowered paragraph into a Worker request", () => {
  const globals = preserveGlobals(ROOT_GLOBALS);
  try {
    const root = scopeRoot();
    const paragraphEl = scopeParagraph(root, "P");
    const lowered = paragraph({ text: "你好世界" });
    installRootHappyPath(() => ({ ok: true, lowered }));
    const options = rootOptions();
    const result = workerRequest.workerLayoutRequestForRoot(ROOT_FFI, root, paragraphEl, options);
    assert.notEqual(result, null);
    const parsed = JSON.parse(result);
    assert.equal(parsed.text, "你好世界");
    assert.equal(parsed.firstLineIndentIc, 0);
    assert.equal(parsed.sourceTag, "p");
  } finally {
    restoreGlobals(globals);
  }
});

test("workerLayoutRequestForRoot feeds the withRootDefaults result to both lower and the request", () => {
  const globals = preserveGlobals(ROOT_GLOBALS);
  // The stub replaces a property on the shared real lifecycle object, so the
  // original must be restored beside the globals.
  const originalWithRootDefaults = lifecycle.withRootDefaults;
  try {
    const root = scopeRoot();
    const paragraphEl = scopeParagraph(root, "P");
    // Stub withRootDefaults to pin that its returned resolved object is the one
    // feeding both the lowering options and the final request wiring. The raw
    // options passed in must stay snapshot-eligible so the gate passes.
    const resolved = rootOptions({ firstLineIndentIc: 7, fontSize: 21 });
    lifecycle.withRootDefaults = (options, theRoot) => resolved;
    const { lowerCalls } = installRootHappyPath(() => ({ ok: true, lowered: paragraph() }));
    const result = workerRequest.workerLayoutRequestForRoot(ROOT_FFI, root, paragraphEl, rootOptions());
    assert.notEqual(result, null);
    // lower read fontSize from the resolved options.
    assert.equal(lowerCalls[0].options.fontSize, 21);
    // the request read firstLineIndentIc from the resolved options.
    assert.equal(JSON.parse(result).firstLineIndentIc, 7);
  } finally {
    lifecycle.withRootDefaults = originalWithRootDefaults;
    restoreGlobals(globals);
  }
});