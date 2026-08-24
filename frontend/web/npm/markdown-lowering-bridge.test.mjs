// Unit tests for the markdown-lowering engine module installed by ts-runtime.
// npm-core/core/engine/markdown-lowering.js installs __TiqianMarkdownLowering; these
// tests drive that global directly.

import assert from "node:assert/strict";
import test from "node:test";
import "@tiqian/prose-core/core/engine/markdown-lowering.js";
import { cleanupMounted, loadHostRuntime, mount } from "./runtime-host.mjs";

// Controllable role stub: CJK ideographs are cjk-text, full-width punctuation
// is cjk-punctuation, everything else is a latin run. The engine only treats
// the first two as CJK.
function cjkRoleStub(text, start, end) {
  const slice = text.slice(start, end);
  if (/[一-鿿]/.test(slice)) return "cjk-text";
  if (/[，。、！？；：（）《》「」『』]/.test(slice)) return "cjk-punctuation";
  return "latin";
}

function lowerParagraph(html, options = {}, roleStub = cjkRoleStub) {
  const root = mount(`<div data-tiqian-root="true">${html}</div>`);
  const paragraph = root.querySelector("p");
  assert.ok(paragraph, "mount must produce a <p>");
  const result = globalThis.__TiqianMarkdownLowering.lower(paragraph, options, {
    classifyRole: roleStub,
  });
  return { root, paragraph, result };
}

// Mirrors InlineShapingStylePolicy.unsupportedInlineShapingProperties in
// font/src/commonMain (the order is part of the first-divergent decision).
const INLINE_SHAPING_PROPERTIES = [
  "font-feature-settings",
  "font-variation-settings",
  "font-stretch",
  "font-kerning",
  "font-optical-sizing",
  "font-variant-ligatures",
  "font-variant-alternates",
  "font-variant-east-asian",
  "font-variant-caps",
  "font-variant-numeric",
  "font-variant-position",
  "font-language-override",
  "font-size-adjust",
  "word-spacing",
  "text-transform",
  "text-rendering",
];

// Host-side stub of the Kotlin InlineShapingStylePolicy decision the facade
// installs: first index whose normalized values differ names the issue.
function inlineShapingDecisionStub(tag, elementValues, paragraphValues) {
  for (let i = 0; i < INLINE_SHAPING_PROPERTIES.length; i++) {
    if (elementValues[i] !== paragraphValues[i]) {
      return {
        name: "UnsupportedInlineShapingStyle",
        detail: tag + ":" + INLINE_SHAPING_PROPERTIES[i],
      };
    }
  }
  return null;
}

function shapingHelpers(decision = inlineShapingDecisionStub) {
  return {
    inlineShapingProperties: [...INLINE_SHAPING_PROPERTIES],
    inlineShapingDecision: decision,
  };
}

function lowerWithHelpers(paragraph, helpers, options = {}) {
  return globalThis.__TiqianMarkdownLowering.lower(paragraph, options, helpers);
}

function lowerParagraphWithShapingDecision(html, options = {}) {
  const root = mount(`<div data-tiqian-root="true">${html}</div>`);
  const paragraph = root.querySelector("p");
  assert.ok(paragraph, "mount must produce a <p>");
  const result = lowerWithHelpers(paragraph, {
    classifyRole: cjkRoleStub,
    ...shapingHelpers(),
  }, options);
  return { root, paragraph, result };
}

// Patch globalThis.getComputedStyle so getPropertyValue(name) answers the
// overrides map for the given lowercase property names; everything else keeps
// the host computed style. Mirrors the withGetComputedStyle precedent in
// responsive-measure-bridge.test.mjs.
function withComputedStyleOverride(overrides, fn) {
  return withComputedStyleOverrideFor(null, overrides, fn);
}

// Element-scoped variant: the overrides apply to one element only, so a
// paragraph and a styled child can carry different computed values.
function withComputedStyleOverrideFor(element, overrides, fn) {
  const real = globalThis.getComputedStyle;
  globalThis.getComputedStyle = (target, pseudo) => {
    const style = real(target, pseudo);
    if (element !== null && target !== element) return style;
    return new Proxy(style, {
      get(targetStyle, prop) {
        if (prop === "getPropertyValue") {
          return (name) => {
            const key = String(name).toLowerCase();
            if (Object.prototype.hasOwnProperty.call(overrides, key)) {
              return overrides[key];
            }
            return targetStyle.getPropertyValue(name);
          };
        }
        return Reflect.get(targetStyle, prop);
      },
    });
  };
  try {
    return fn();
  } finally {
    globalThis.getComputedStyle = real;
  }
}

test("markdownLoweringBridge_installedByScriptImport", async () => {
  await loadHostRuntime();
  const lowering = globalThis.__TiqianMarkdownLowering;
  assert.ok(
    lowering,
    "importing markdown-lowering.js must install globalThis.__TiqianMarkdownLowering",
  );
  assert.equal(typeof lowering.lower, "function");
});

test("markdownLoweringBridge_plainTextParagraphLoweredWithDefaults", async (t) => {
  t.after(cleanupMounted);
  await loadHostRuntime();
  const { result } = lowerParagraph("<p>中文正文。</p>");
  assert.equal(result.ok, true);
  const lowered = result.lowered;
  assert.equal(lowered.text, "中文正文。");
  assert.equal(lowered.textStyle.fontFamilies.join(","), "Fixture CJK");
  assert.equal(lowered.textStyle.fontWeight, 400);
  assert.equal(lowered.textStyle.italic, false);
  assert.equal(lowered.textStyle.baselineShift, 0);
  assert.equal(lowered.textStyle.locale, "zh-Hans");
  assert.equal(lowered.lineHeight, 27);
  assert.deepEqual(lowered.spans, []);
  assert.deepEqual(lowered.decorations, []);
  assert.deepEqual(lowered.inlineBoxes, []);
  assert.deepEqual(lowered.inlineObjects, []);
  assert.deepEqual(lowered.domInlineObjects, []);
  assert.deepEqual(lowered.sourceSpans, []);
  assert.deepEqual(lowered.sourceBoundaries, []);
  assert.deepEqual(lowered.lineBreakSpans, []);
});

test("markdownLoweringBridge_defaultFontSizeFallbackIs19Px", async (t) => {
  t.after(cleanupMounted);
  await loadHostRuntime();
  // buildWorld() re-installs the fake getComputedStyle on every mount, so the
  // patch must wrap only the lower call, not the mount.
  const root = mount('<div data-tiqian-root="true"><p>中文正文。</p></div>');
  const paragraph = root.querySelector("p");
  const result = withComputedStyleOverride(
    { "font-size": "normal" },
    () =>
      globalThis.__TiqianMarkdownLowering.lower(paragraph, {}, { classifyRole: cjkRoleStub }),
  );
  assert.equal(result.ok, true);
  const lowered = result.lowered;
  assert.equal(lowered.textStyle.fontSize, 19);
  assert.equal(lowered.textStyle.fontFamilies.join(","), "Fixture CJK");
  assert.equal(lowered.textStyle.fontWeight, 400);
  assert.equal(lowered.textStyle.italic, false);
});

test("markdownLoweringBridge_localeOptionThreadsThroughTextStyles", async (t) => {
  t.after(cleanupMounted);
  await loadHostRuntime();
  const { result } = lowerParagraph(
    "<p style=\"font-family: 'BaseFace'\"><em style=\"font-style: italic; font-family: 'StrongFace'\">斜</em>尾</p>",
    { locale: "zh-TW" },
  );
  assert.equal(result.ok, true);
  const lowered = result.lowered;
  assert.equal(lowered.textStyle.locale, "zh-TW");
  assert.equal(lowered.spans.length, 1);
  assert.equal(lowered.spans[0].style.locale, "zh-TW");
});

test("markdownLoweringBridge_strongAsEmphasisMarksSplitsCjkFromLatinRuns", async (t) => {
  t.after(cleanupMounted);
  await loadHostRuntime();
  const { result } = lowerParagraph(
    "<p style=\"font-family: 'BaseFace'\">中文<strong style=\"font-family: 'StrongFace'; font-weight: 700\">粗体👍🏽ab</strong>后</p>",
    { strongAsEmphasisMarks: true },
  );
  assert.equal(result.ok, true);
  const lowered = result.lowered;
  assert.equal(lowered.text, "中文粗体👍🏽ab后");
  assert.equal(lowered.spans.length, 2);
  const cjkSpan = lowered.spans[0];
  assert.equal(cjkSpan.start, 2);
  assert.equal(cjkSpan.end, 4);
  assert.equal(cjkSpan.style.fontWeight, 400);
  assert.deepEqual(cjkSpan.style.fontFamilies, ["StrongFace"]);
  const latinSpan = lowered.spans[1];
  assert.equal(latinSpan.start, 4);
  assert.equal(latinSpan.end, 10);
  assert.equal(latinSpan.style.fontWeight, 700);
  assert.deepEqual(latinSpan.style.fontFamilies, ["StrongFace"]);
  assert.deepEqual(lowered.decorations, [
    { start: 2, end: 4, kind: "Emphasis" },
  ]);
  assert.equal(lowered.text.slice(4, 8), "👍🏽");
});

test("markdownLoweringBridge_graphemeBoundariesViaIntlSegmenter", async (t) => {
  t.after(cleanupMounted);
  await loadHostRuntime();
  // U+FE0F attaches to the preceding CJK base as one grapheme; a code-point
  // traversal would split the base and the variation selector into two runs.
  const { result } = lowerParagraph(
    "<p style=\"font-family: 'BaseFace'\"><strong style=\"font-family: 'StrongFace'; font-weight: 700\">中\uFE0Fab</strong></p>",
    { strongAsEmphasisMarks: true },
  );
  assert.equal(result.ok, true);
  const lowered = result.lowered;
  assert.equal(lowered.text, "中\uFE0Fab");
  assert.equal(lowered.spans.length, 2);
  assert.equal(lowered.spans[0].start, 0);
  assert.equal(lowered.spans[0].end, 2);
  assert.equal(lowered.spans[1].start, 2);
  assert.equal(lowered.spans[1].end, 4);
  assert.deepEqual(lowered.decorations, [
    { start: 0, end: 2, kind: "Emphasis" },
  ]);
});

test("markdownLoweringBridge_whiteSpaceCollapseNormalMode", async (t) => {
  t.after(cleanupMounted);
  await loadHostRuntime();
  const { result } = lowerParagraph("<p style=\"white-space: normal\">  甲  乙 丙  </p>");
  assert.equal(result.ok, true);
  assert.equal(result.lowered.text, "甲 乙 丙");
});

test("markdownLoweringBridge_whiteSpacePrePreservesEverything", async (t) => {
  t.after(cleanupMounted);
  await loadHostRuntime();
  const { result } = lowerParagraph("<p style=\"white-space: pre\">  甲  乙\n丙  </p>");
  assert.equal(result.ok, true);
  assert.equal(result.lowered.text, "  甲  乙\n丙  ");
});

test("markdownLoweringBridge_whiteSpacePreLineCollapsesButKeepsBreaks", async (t) => {
  t.after(cleanupMounted);
  await loadHostRuntime();
  const { result } = lowerParagraph("<p style=\"white-space: pre-line\">  甲\n  乙  </p>");
  assert.equal(result.ok, true);
  assert.equal(result.lowered.text, "甲\n乙");
});

test("markdownLoweringBridge_structuralBreakAndCrLfProduceSingleNewline", async (t) => {
  t.after(cleanupMounted);
  await loadHostRuntime();
  const brCase = lowerParagraph("<p>甲<br>乙</p>");
  assert.equal(brCase.result.ok, true);
  assert.equal(brCase.result.lowered.text, "甲\n乙");
  const crlfCase = lowerParagraph("<p style=\"white-space: pre\">甲\r\n乙</p>");
  assert.equal(crlfCase.result.ok, true);
  assert.equal(crlfCase.result.lowered.text, "甲\n乙");
});

test("markdownLoweringBridge_projectedRangesShiftWithCollapse", async (t) => {
  t.after(cleanupMounted);
  await loadHostRuntime();
  const preCase = lowerParagraph(
    "<p style=\"white-space: pre\">x<code style=\"font-family: 'MonoFace'\">  cd  </code>y</p>",
  );
  assert.equal(preCase.result.ok, true);
  assert.equal(preCase.result.lowered.text, "x  cd  y");
  assert.equal(preCase.result.lowered.spans.length, 1);
  assert.equal(preCase.result.lowered.spans[0].start, 1);
  assert.equal(preCase.result.lowered.spans[0].end, 7);

  const collapseCase = lowerParagraph(
    "<p><code style=\"font-family: 'MonoFace'\">  cd  </code></p>",
  );
  assert.equal(collapseCase.result.ok, true);
  assert.equal(collapseCase.result.lowered.text, "cd");
  assert.equal(collapseCase.result.lowered.spans.length, 1);
  assert.equal(collapseCase.result.lowered.spans[0].start, 0);
  assert.equal(collapseCase.result.lowered.spans[0].end, 2);
});

test("markdownLoweringBridge_plainInlineEmitsNoTextSpanStyledInlineEmitsOne", async (t) => {
  t.after(cleanupMounted);
  await loadHostRuntime();
  const { result } = lowerParagraph(
    "<p>平<span>原样</span><em style=\"font-style: italic\">斜</em>尾</p>",
  );
  assert.equal(result.ok, true);
  const lowered = result.lowered;
  assert.equal(lowered.text, "平原样斜尾");
  assert.equal(lowered.spans.length, 1);
  assert.equal(lowered.spans[0].start, 3);
  assert.equal(lowered.spans[0].end, 4);
  assert.equal(lowered.spans[0].style.italic, true);
  assert.equal(lowered.sourceSpans.length, 2);
  assert.equal(lowered.sourceSpans[0].depth, 0);
  assert.equal(lowered.sourceSpans[1].depth, 0);
  assert.deepEqual(lowered.sourceBoundaries, [1, 3, 4]);
});

test("markdownLoweringBridge_nestedInlineDepthIncrements", async (t) => {
  t.after(cleanupMounted);
  await loadHostRuntime();
  const { result } = lowerParagraph(
    "<p><em style=\"font-style: italic\">外<strong style=\"font-weight: 700\">内</strong></em>尾</p>",
  );
  assert.equal(result.ok, true);
  const lowered = result.lowered;
  assert.equal(lowered.text, "外内尾");
  assert.equal(lowered.spans.length, 2);
  assert.equal(lowered.spans[0].start, 0);
  assert.equal(lowered.spans[0].end, 1);
  assert.equal(lowered.spans[1].start, 1);
  assert.equal(lowered.spans[1].end, 2);
  assert.equal(lowered.spans[1].style.fontWeight, 700);
  assert.equal(lowered.sourceSpans.length, 2);
  // WorkerSemanticHierarchyOrder: source spans are collected after their
  // children, so the deeper element precedes its parent in the list.
  assert.equal(lowered.sourceSpans[0].depth, 1);
  assert.equal(lowered.sourceSpans[1].depth, 0);
  assert.deepEqual(lowered.sourceBoundaries, [1, 2]);
});

test("markdownLoweringBridge_opaqueInlineObjectCarriesLiveElementAndGeometry", async (t) => {
  t.after(cleanupMounted);
  await loadHostRuntime();
  const { result, paragraph } = lowerParagraph(
    "<p><span data-tiqian-static-inline-object style=\"display:inline-block; width:42px; height:20px\">obj</span>后</p>",
  );
  assert.equal(result.ok, true);
  const lowered = result.lowered;
  assert.equal(lowered.text, "\uFFFC后");
  assert.equal(lowered.inlineObjects.length, 1);
  assert.equal(lowered.inlineObjects[0].start, 0);
  assert.equal(lowered.inlineObjects[0].end, 1);
  assert.equal(lowered.inlineObjects[0].advance, 42);
  assert.equal(lowered.inlineObjects[0].ascent, 30);
  assert.equal(lowered.inlineObjects[0].descent, 0);
  assert.equal(lowered.domInlineObjects.length, 1);
  assert.equal(lowered.domInlineObjects[0].start, 0);
  assert.equal(lowered.domInlineObjects[0].end, 1);
  assert.equal(lowered.domInlineObjects[0].marginRight, 0);
  const span = paragraph.querySelector("span");
  assert.ok(span);
  assert.equal(lowered.domInlineObjects[0].element, span);
});

test("markdownLoweringBridge_blockLevelDisplayFailsFormattingContext", async (t) => {
  t.after(cleanupMounted);
  await loadHostRuntime();
  const { result } = lowerParagraph("<p>前<div>块</div>后</p>");
  assert.equal(result.ok, false);
  assert.equal(result.issue.name, "UnsupportedInlineFormattingContext");
  assert.equal(result.issue.detail, "div:block");
});

test("markdownLoweringBridge_rootGeneratedContentFailsGeneratedInline", async (t) => {
  t.after(cleanupMounted);
  await loadHostRuntime();
  const { result } = lowerParagraph(
    "<div data-tiqian-root=\"true\"><style>.generated-root::before { content: \"※\"; }</style><p class=\"generated-root\">正文</p></div>",
  );
  assert.equal(result.ok, false);
  assert.equal(result.issue.name, "UnsupportedGeneratedInlineContent");
  assert.ok(result.issue.detail.startsWith("p::before:"));
});

test("markdownLoweringBridge_divergentInlineShapingStyleFails", async (t) => {
  t.after(cleanupMounted);
  await loadHostRuntime();
  // The downgrade decision lives Kotlin-side (InlineShapingStylePolicy); the
  // stub passed here mirrors it the way cjkRoleStub mirrors the classifier.
  const { result } = lowerParagraphWithShapingDecision(
    "<p><em style=\"font-kerning: none\">斜</em>尾</p>",
  );
  assert.equal(result.ok, false);
  assert.equal(result.issue.name, "UnsupportedInlineShapingStyle");
  assert.equal(result.issue.detail, "em:font-kerning");
});

test("markdownLoweringBridge_inlineShapingDecisionReceivesNormalizedValues", async (t) => {
  t.after(cleanupMounted);
  await loadHostRuntime();
  const root = mount('<div data-tiqian-root="true"><p><em style="font-kerning: none">斜</em>尾</p></div>');
  const paragraph = root.querySelector("p");
  let captured = null;
  const decision = (tag, elementValues, paragraphValues) => {
    captured = { tag, elementValues, paragraphValues };
    return null;
  };
  // The paragraph answers with whitespace and casing noise; the element keeps
  // its inline "none". Both must reach the callback normalized and equal.
  const result = withComputedStyleOverrideFor(
    paragraph,
    { "font-kerning": "  NonE\t" },
    () => lowerWithHelpers(paragraph, { classifyRole: cjkRoleStub, ...shapingHelpers(decision) }),
  );
  assert.equal(result.ok, true);
  assert.ok(captured, "the decision callback must run for a styled inline element");
  assert.equal(captured.tag, "em");
  assert.equal(captured.elementValues.length, INLINE_SHAPING_PROPERTIES.length);
  assert.equal(captured.paragraphValues.length, INLINE_SHAPING_PROPERTIES.length);
  const kerning = INLINE_SHAPING_PROPERTIES.indexOf("font-kerning");
  assert.equal(captured.elementValues[kerning], "none");
  assert.equal(captured.paragraphValues[kerning], "none");
});

test("markdownLoweringBridge_missingDecisionCallbackSkipsDowngrade", async (t) => {
  t.after(cleanupMounted);
  await loadHostRuntime();
  // Without the callback the host runs the reduced policy (same shape as the
  // classifyRole "other" default): the styled element lowers without a
  // downgrade issue.
  const { result } = lowerParagraph("<p><em style=\"font-kerning: none\">斜</em>尾</p>");
  assert.equal(result.ok, true);
});

test("markdownLoweringBridge_emptyParagraphFails", async (t) => {
  t.after(cleanupMounted);
  await loadHostRuntime();
  const { result } = lowerParagraph("<p>   </p>");
  assert.equal(result.ok, false);
  assert.equal(result.issue.name, "EmptyParagraph");
  assert.equal(result.issue.detail, "paragraph has no text");
});

test("markdownLoweringBridge_canonicalPreparedPlainSourceFastPath", async (t) => {
  t.after(cleanupMounted);
  await loadHostRuntime();
  const { result, paragraph } = lowerParagraph(
    "<p data-tq-rendered=\"true\" data-tq-canonical-plain=\"true\">" +
      "正文<span data-tq-src=\"替代\">丢弃</span>" +
      "<span data-tq-copy-ignore=\"true\">隐藏</span>" +
      "<span data-tq-hard-break=\"true\" data-tq-src=\"跳过\">对</span>" +
      "<br data-tq-engine-break=\"MandatoryBreak\">换行</p>",
    { fontSize: 18, lineHeight: 30 },
  );
  assert.equal(result.ok, true);
  const lowered = result.lowered;
  assert.equal(lowered.text, "正文替代\n换行");
  assert.ok(!lowered.text.includes("丢弃"));
  assert.ok(!lowered.text.includes("隐藏"));
  assert.ok(!lowered.text.includes("跳过"));
  assert.deepEqual(lowered.spans, []);
  assert.deepEqual(lowered.sourceSpans, []);
  assert.deepEqual(lowered.inlineObjects, []);
  assert.equal(lowered.lineHeight, 30);
  assert.equal(paragraph.getAttribute("data-tq-rendered"), "true");
  assert.equal(paragraph.getAttribute("style"), null);
});

test("markdownLoweringBridge_canonicalPreparedUnpairedHardBreakKeepsSource", async (t) => {
  t.after(cleanupMounted);
  await loadHostRuntime();
  const { result } = lowerParagraph(
    "<p data-tq-rendered=\"true\" data-tq-canonical-plain=\"true\">" +
      "<span data-tq-hard-break=\"true\" data-tq-src=\"未配对\">对</span></p>",
    { fontSize: 18, lineHeight: 30 },
  );
  assert.equal(result.ok, true);
  assert.equal(result.lowered.text, "未配对");
});