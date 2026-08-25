import assert from "node:assert/strict";
import test from "node:test";

import {
  workerLayoutRequest,
  workerLayoutRequestForRoot,
  workerLayoutRequestJson,
} from "./core/engine/worker-request.js";
import { effectiveLineMeasure } from "./core/engine/responsive-measure.js";
import { firstDivergentInlineShapingProperty, unsupportedInlineShapingProperties } from "@tiqian/ffi";

const ROOT_SELECTOR = "tiqian-prose, [data-tiqian-root]";

// The responsive measure helpers, the eligibility predicate and the lifecycle
// helpers are all real now: sourceParagraphWidth reads element geometry and
// globalThis.getComputedStyle, effectiveLineMeasure is imported above,
// shouldTryParagraph reads plain element properties, and the snapshot gate,
// withRootDefaults and conformingSnapshotFontSessionId run from the stateless
// lifecycle module. No module seam exists anymore.

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
    text: "ab",
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

// Fake element used by workerLayoutRequest (sourceParagraphWidth reads
// getBoundingClientRect/getClientRects) and by workerLayoutRequestJson
// (sourceTag reads tagName).
function element(tagName = "P", overrides = {}) {
  return {
    tagName,
    getBoundingClientRect: () => ({ width: overrides.width ?? 320 }),
    getClientRects: () => [],
    parentElement: null,
    ...overrides,
  };
}

// Canonical EnhanceOptions decoded by hand (the real optionsFromJs lives in
// the stateless lifecycle module).
function canonicalOptions(overrides = {}) {
  return {
    fontFamilies: {
      cjk: null,
      latin: null,
      monospace: null,
      cjkSerif: null,
      latinSerif: null,
    },
    fontSize: null,
    lineHeight: null,
    firstLineIndentIc: 0,
    emphasisDotGapEm: 0.1,
    strongAsEmphasisMarks: false,
    paragraphSelector: "p, li",
    cjkDashCapability: null,
    snapshotFontSession: { status: "conforming", sessionId: "s1", detail: null },
    requireSnapshotLayoutWorker: false,
    ...overrides,
  };
}

// Computed style double: property accessors feed elementContentWidth, the
// getPropertyValue callback feeds the lowerer's computedStyle reads.
function computedStyle(values = {}) {
  const style = {
    paddingLeft: "0px",
    paddingRight: "0px",
    borderLeftWidth: "0px",
    borderRightWidth: "0px",
    position: "static",
    transform: "none",
    marginLeft: "0px",
    marginRight: "0px",
    marginTop: "0px",
    marginBottom: "0px",
  };
  style.getPropertyValue = (name) => {
    const key = String(name).toLowerCase();
    return Object.prototype.hasOwnProperty.call(values, key)
      ? String(values[key])
      : "";
  };
  return style;
}

function withComputedStyle(fn) {
  const real = globalThis.getComputedStyle;
  globalThis.getComputedStyle = (target, pseudo) =>
    target && target._computedValues
      ? computedStyle(target._computedValues)
      : computedStyle();
  try {
    return fn();
  } finally {
    globalThis.getComputedStyle = real;
  }
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
  '"renderEvidence":true,' +
  '"semantics":[{"start":0,"end":2,"tagName":"em","attributes":[["class","x"]],"sourceIndex":0,"order":1},' +
  '{"start":2,"end":4,"tagName":"span","attributes":[["data-x","y"],["title","t"]],"sourceIndex":1,"order":2}],' +
  '"renderInlineBoxes":[{"start":8,"end":10,"inlineStartPx":1.5,"inlineEndPx":2.25,"outerSpacing":"Narrow"}],' +
  '"sourceTag":"p"}';

test("workerLayoutRequestJson emits the whole wire request for a rich fixture", () => {
  const actual = workerLayoutRequestJson(
    RICH_PARAGRAPH_ELEMENT,
    RICH_LOWERED,
    678.9,
    2,
  );
  assert.equal(actual, RICH_EXPECTED);
});

test("workerLayoutRequestJson emits the four separator joins as exact substrings", () => {
  const actual = workerLayoutRequestJson(
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
  const actual = workerLayoutRequestJson(
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
  const actual = workerLayoutRequestJson(
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
    renderEvidence: true,
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

test("workerLayoutRequestJson carries true render evidence for a sourceSpans-only lowered", () => {
  const lowered = paragraph({
    sourceSpans: [sourceSpan()],
  });
  const actual = workerLayoutRequestJson(RICH_PARAGRAPH_ELEMENT, lowered, 678.9, 2);
  assert.equal(JSON.parse(actual).renderEvidence, true);
});

test("workerLayoutRequestJson render evidence: spans-only yields true, plain yields false", () => {
  const styled = paragraph({ spans: [span()] });
  const styledActual = workerLayoutRequestJson(RICH_PARAGRAPH_ELEMENT, styled, 678.9, 2);
  assert.equal(JSON.parse(styledActual).renderEvidence, true);

  const plain = paragraph();
  const plainActual = workerLayoutRequestJson(RICH_PARAGRAPH_ELEMENT, plain, 678.9, 2);
  assert.equal(JSON.parse(plainActual).renderEvidence, false);
});

test("workerLayoutRequest returns null without a conforming snapshot font session", () => {
  const nonConforming = canonicalOptions({
    snapshotFontSession: { status: "unavailable", sessionId: "s1", detail: null },
  });
  assert.equal(workerLayoutRequest(element(), paragraph(), nonConforming), null);
  const omitted = canonicalOptions({ snapshotFontSession: null });
  assert.equal(workerLayoutRequest(element(), paragraph(), omitted), null);
});

test("workerLayoutRequest returns null for a decorated paragraph", () => {
  withComputedStyle(() => {
    const lowered = paragraph({
      decorations: [{ start: 0, end: 2, kind: "Emphasis" }],
    });
    assert.equal(workerLayoutRequest(element(), lowered, canonicalOptions()), null);
  });
});

test("workerLayoutRequest returns null for a clone edge at the inclusive epsilon", () => {
  withComputedStyle(() => {
    const lowered = paragraph({
      sourceSpans: [
        sourceSpan({ inlineBoxStyle: inlineBoxStyle({ boxDecorationBreak: "clone", inlineStart: 0.01 }) }),
      ],
    });
    assert.equal(workerLayoutRequest(element(), lowered, canonicalOptions()), null);
  });
});

test("workerLayoutRequest builds for clone boxes below the epsilon", () => {
  withComputedStyle(() => {
    const lowered = paragraph({
      sourceSpans: [
        sourceSpan({
          inlineBoxStyle: inlineBoxStyle({ boxDecorationBreak: "clone", inlineStart: 0.005, inlineEnd: -0.005 }),
        }),
      ],
    });
    assert.notEqual(workerLayoutRequest(element(), lowered, canonicalOptions()), null);
  });
});

test("workerLayoutRequest builds for a non-clone box with large edges", () => {
  withComputedStyle(() => {
    const lowered = paragraph({
      sourceSpans: [
        sourceSpan({ inlineBoxStyle: inlineBoxStyle({ boxDecorationBreak: "slice", inlineStart: 5, inlineEnd: -5 }) }),
      ],
    });
    assert.notEqual(workerLayoutRequest(element(), lowered, canonicalOptions()), null);
  });
});

test("workerLayoutRequest returns null for a locale-mismatching span", () => {
  withComputedStyle(() => {
    const lowered = paragraph({
      spans: [span({ style: textStyle({ locale: "ja" }) })],
    });
    assert.equal(workerLayoutRequest(element(), lowered, canonicalOptions()), null);
  });
});

// The real sourceParagraphWidth falls back to 320 whenever both the paragraph
// and its parent measure non-positive, so raw widths of 0 or negative are
// unreachable through the public API. A non-finite geometry (Infinity) is
// reachable and still trips the same guard.
test("workerLayoutRequest returns null for a non-finite raw width", () => {
  withComputedStyle(() => {
    const infinite = element("P", { width: Number.POSITIVE_INFINITY });
    assert.equal(workerLayoutRequest(infinite, paragraph(), canonicalOptions()), null);
  });
});

test("workerLayoutRequest emits 0 first-line indent for LI and the option value otherwise", () => {
  withComputedStyle(() => {
    const li = workerLayoutRequest(
      element("LI"),
      paragraph(),
      canonicalOptions({ firstLineIndentIc: 2 }),
    );
    assert.equal(JSON.parse(li).firstLineIndentIc, 0);

    const nonLi = workerLayoutRequest(
      element("P"),
      paragraph(),
      canonicalOptions({ firstLineIndentIc: 2 }),
    );
    assert.equal(JSON.parse(nonLi).firstLineIndentIc, 2);
  });
});

test("workerLayoutRequest emits the effective line measure as maxWidthPx", () => {
  withComputedStyle(() => {
    const expected = effectiveLineMeasure(320, 19);
    const result = workerLayoutRequest(
      element("P", { width: 320 }),
      paragraph(),
      canonicalOptions(),
    );
    const parsed = JSON.parse(result);
    assert.equal(parsed.maxWidthPx, expected);
    // The measure is the fontSize-grid quantized cell, not the raw width.
    assert.notEqual(parsed.maxWidthPx, 320);
  });
});

// --- Root overload (workerLayoutRequestForRoot) ---

// The lowering bridge is real now, so the fake paragraph doubles as a
// lowerable DOM: text-only children lower into a plain paragraph, a block
// child fails the formatting context, and an inline child exercises the
// inline-shaping decision callback.
function textNode(text) {
  return { nodeType: 3, textContent: text };
}

function rootParagraph(overrides = {}) {
  const text = overrides.text ?? "hello";
  const owner = overrides.owner ?? null;
  return {
    tagName: overrides.tagName ?? "P",
    textContent: text,
    childNodes: overrides.childNodes ?? [textNode(text)],
    getAttribute: () => null,
    setAttribute: () => {},
    removeAttribute: () => {},
    style: {
      setProperty: () => {},
      removeProperty: () => {},
      getPropertyValue: () => "",
      getPropertyPriority: () => "",
    },
    closest: (selector) => (selector === ROOT_SELECTOR ? owner : null),
    querySelectorAll: () => [],
    querySelector: () => null,
    getBoundingClientRect: () => ({ width: overrides.width ?? 320 }),
    getClientRects: () => [],
    parentElement: null,
    _computedValues: overrides.computedValues,
  };
}

// A block-level child makes the real lowerer fail the formatting context
// with an UnsupportedInlineFormattingContext issue, which the root overload
// must discard and report as null.
function blockChild(tagName, text) {
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
    _computedValues: { display: "block" },
  };
}

// An inline child with an empty client rect list lowers into a sourceSpan
// without needing a Range/document double: measuredInlineEdge returns the
// margin (0) early when the element has no boxes.
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

function scopeRoot(containsResult = true) {
  return {
    tagName: "DIV",
    contains: () => containsResult,
  };
}

test("workerLayoutRequestForRoot returns null when closest resolves to a nested owner under the root", () => {
  const owner = blockChild("SECTION", "nested");
  const root = scopeRoot(true);
  const paragraphEl = rootParagraph({ owner });
  assert.equal(
    workerLayoutRequestForRoot(root, paragraphEl, canonicalOptions()),
    null,
  );
});

test("workerLayoutRequestForRoot passes the root gate when owner is the root", () => {
  withComputedStyle(() => {
    const root = scopeRoot();
    const paragraphEl = rootParagraph({ owner: root });
    assert.notEqual(
      workerLayoutRequestForRoot(root, paragraphEl, canonicalOptions()),
      null,
    );
  });
});

test("workerLayoutRequestForRoot passes the root gate when no owner is found", () => {
  withComputedStyle(() => {
    const root = scopeRoot();
    const paragraphEl = rootParagraph();
    assert.notEqual(
      workerLayoutRequestForRoot(root, paragraphEl, canonicalOptions()),
      null,
    );
  });
});

test("workerLayoutRequestForRoot returns null when shouldTryParagraph is false", () => {
  const blank = rootParagraph({ text: "   ", childNodes: [] });
  assert.equal(
    workerLayoutRequestForRoot(scopeRoot(), blank, canonicalOptions()),
    null,
  );
});

test("workerLayoutRequestForRoot returns null when snapshot exact layout is disallowed", () => {
  const paragraphEl = rootParagraph();
  const options = canonicalOptions({ fontSize: 20 });
  assert.equal(
    workerLayoutRequestForRoot(scopeRoot(), paragraphEl, options),
    null,
  );
});

test("workerLayoutRequestForRoot returns null when the lowering bridge throws", () => {
  withComputedStyle(() => {
    // A child node list whose iterator throws makes the real lowerer throw
    // while walking children; the root overload reports null.
    const paragraphEl = rootParagraph({
      childNodes: {
        [Symbol.iterator]() {
          throw new Error("lowering walk boom");
        },
      },
    });
    assert.equal(
      workerLayoutRequestForRoot(scopeRoot(), paragraphEl, canonicalOptions()),
      null,
    );
  });
});

test("workerLayoutRequestForRoot returns null when lowering fails and never reads the issue", () => {
  withComputedStyle(() => {
    // A block child fails lowering with UnsupportedInlineFormattingContext.
    // The root overload discards the issue result and reports null.
    const paragraphEl = rootParagraph({ childNodes: [blockChild("DIV", "blocked")] });
    assert.equal(
      workerLayoutRequestForRoot(scopeRoot(), paragraphEl, canonicalOptions()),
      null,
    );
  });
});

test("workerLayoutRequestForRoot lowers with the fixed zh-Hans locale", () => {
  withComputedStyle(() => {
    const paragraphEl = rootParagraph({ text: "hello world" });
    const result = workerLayoutRequestForRoot(
      scopeRoot(),
      paragraphEl,
      canonicalOptions(),
    );
    assert.notEqual(result, null);
    assert.equal(JSON.parse(result).locale, "zh-Hans");
  });
});

test("workerLayoutRequestForRoot inlineShapingDecision wraps the ffi divergence result", () => {
  withComputedStyle(() => {
    // The divergence decision feeds the real firstDivergentInlineShapingProperty
    // over the element and paragraph shaping-value arrays, one value per
    // unsupportedInlineShapingProperties() position. text-transform is the
    // 15th property (index 14), so a divergent element value there fails the
    // inline element, the paragraph lowers with ok !== true, and the root
    // overload reports null.
    const paragraphValues = Array(unsupportedInlineShapingProperties().length).fill("");
    const elementValues = Array(unsupportedInlineShapingProperties().length).fill("");
    elementValues[14] = "uppercase";
    assert.equal(firstDivergentInlineShapingProperty(elementValues, paragraphValues), "text-transform");
    const paragraphEl = rootParagraph({
      childNodes: [inlineChild("EM", "x", { "text-transform": "uppercase" })],
    });
    assert.equal(
      workerLayoutRequestForRoot(scopeRoot(), paragraphEl, canonicalOptions()),
      null,
    );
  });
});

test("workerLayoutRequestForRoot inlineShapingDecision returns null for a null divergence property", () => {
  withComputedStyle(() => {
    const paragraphEl = rootParagraph({
      childNodes: [inlineChild("EM", "x", { "font-style": "italic" })],
    });
    const result = workerLayoutRequestForRoot(
      scopeRoot(),
      paragraphEl,
      canonicalOptions(),
    );
    assert.notEqual(result, null);
  });
});

test("workerLayoutRequestForRoot serializes the lowered paragraph into a Worker request", () => {
  withComputedStyle(() => {
    const paragraphEl = rootParagraph({ text: "hello world" });
    const result = workerLayoutRequestForRoot(
      scopeRoot(),
      paragraphEl,
      canonicalOptions(),
    );
    assert.notEqual(result, null);
    const parsed = JSON.parse(result);
    assert.equal(parsed.text, "hello world");
    assert.equal(parsed.firstLineIndentIc, 0);
    assert.equal(parsed.sourceTag, "p");
  });
});

test("workerLayoutRequestForRoot feeds the withRootDefaults result into lowering and the request", () => {
  withComputedStyle(() => {
    // The snapshot-eligible bag resolves through the real withRootDefaults
    // against the root; the paragraph's computed typography then flows into
    // lowering and onto the request wire.
    const root = scopeRoot();
    root._computedValues = { "font-family": "Root Inherited, sans-serif" };
    const paragraphEl = rootParagraph({
      text: "hello world",
      computedValues: { "font-size": "21px" },
    });
    const result = workerLayoutRequestForRoot(
      root,
      paragraphEl,
      canonicalOptions(),
    );
    assert.notEqual(result, null);
    const parsed = JSON.parse(result);
    assert.equal(parsed.fontSizePx, 21);
    assert.equal(parsed.firstLineIndentIc, 0);
  });
});