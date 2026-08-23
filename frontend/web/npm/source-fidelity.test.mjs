// Source fidelity tests ported from TiqianWebSourceFidelityTest.kt and the
// related cases in TiqianWebEnhancerTest.kt / TiqianWebProgressiveRelayoutTest.kt.
// Verifies that shaping input keeps source semantics: variation selectors and
// combining marks stay with their bases, whitespace collapse matches browser
// innerText, host fonts drive measure and paint, and host inline styles
// survive on semantic tags.

import assert from "node:assert/strict";
import test from "node:test";
import {
  cleanupMounted,
  computedStyleValue,
  copySelection,
  cssPx,
  dispatchRelayout,
  emptyRenderedLineCount,
  flushAllTestAnimationFrames,
  installTestAnimationFrames,
  loadHostRuntime,
  mount,
  nativeInnerText,
  pendingTestAnimationFrameCount,
  renderedLineSignature,
  testOptions,
} from "./runtime-host.mjs";

test("sourceFidelity_variationSelectorStaysWithItsVisibleBase", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const source = "返回正文 ↩︎";
  const root = mount(
    `<div data-tiqian-root='true' style='width: 220px'><p>${source}</p></div>`,
  );

  assert.equal(TiqianWeb.enhance(root, testOptions()), 1);
  const paragraph = root.querySelector("p");
  assert.equal(paragraph.getAttribute("data-tiqian-capability-issue"), null);
  assert.equal(copySelection(paragraph), source);
});

test("sourceFidelity_shapingBoundariesStayInNativeSelectionFlow", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const source = "这里的 Powershell 与 pwsh7 都保持连续选择。";
  const root = mount(`
    <div data-tiqian-root="true" style="width: 700px">
      <p>${source}</p>
    </div>
  `);

  assert.equal(TiqianWeb.enhance(root, testOptions()), 1);

  const paragraph = root.querySelector("p");
  const boundaries = paragraph.querySelectorAll("[data-tq-shaping-boundary]");
  assert.ok(boundaries.length > 0, paragraph.innerHTML);
  for (const boundary of boundaries) {
    assert.equal(
      computedStyleValue(boundary, "display"),
      "inline",
      `a shaping run must not become an atomic selection island: ${boundary.outerHTML}`,
    );
  }
  assert.equal(copySelection(paragraph), source);
});

test("sourceFidelity_combiningMarksShapedWithTheirBases", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const source = "合法组合标记༎ຶ与螺丝Ỏ̷仍应保留在正文中。";
  const root = mount(
    `<div data-tiqian-root='true' style='width: 320px'><p>${source}</p></div>`,
  );

  assert.equal(TiqianWeb.enhance(root, testOptions()), 1);

  const paragraph = root.querySelector("p");
  assert.equal(paragraph.getAttribute("data-tq-rendered"), "true");
  assert.equal(paragraph.getAttribute("data-tiqian-capability-issue"), null);
  assert.equal(copySelection(paragraph), source);
});

test("sourceFidelity_unverifiedEllipsisKeepsSourceCodepoint", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(`
    <div data-tiqian-root="true">
      <p>中文……中文。</p>
    </div>
  `);

  const count = TiqianWeb.enhance(root, testOptions());

  assert.equal(count, 1);
  const paragraph = root.querySelector("p");
  assert.ok(paragraph.textContent.includes("……"));
  assert.ok(!paragraph.textContent.includes("⋯⋯"));
  assert.equal(copySelection(paragraph), "中文……中文。");
});

test("sourceFidelity_whitespaceCollapseProjectionMatchesInnerText", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const expected = "第一句。 第二句。 第三句。\n第四句。";
  const root = mount(
    "<div data-tiqian-root='true' style='width: 220px'>" +
      "<p>第一句。\n<strong>第二句。\n第三句。</strong><br>\n第四句。</p>" +
      "</div>",
  );
  const paragraph = root.querySelector("p");
  assert.equal(nativeInnerText(paragraph), expected);

  TiqianWeb.install();
  assert.equal(TiqianWeb.enhance(root, testOptions()), 1);
  assert.equal(copySelection(paragraph), expected);
  assert.equal(paragraph.querySelectorAll("[data-tq-hard-break]").length, 1);
  assert.equal(emptyRenderedLineCount(paragraph), 0);
  assert.ok(paragraph.querySelector("strong[data-tq-source-semantic]"));

  const initial = renderedLineSignature(paragraph);
  installTestAnimationFrames();
  root.style.width = "120px";
  dispatchRelayout(root);
  // SyncFirstSlice: the relayout commits inside the dispatch task. The
  // narrow result is already live with no frame delay, and there is no
  // intermediate state where the old line boxes are gone but the new
  // ones are not attached yet.
  const narrow = renderedLineSignature(paragraph);
  assert.notEqual(initial, narrow, "narrow width must exercise a real reflow");
  assert.equal(pendingTestAnimationFrameCount(), 0);
  flushAllTestAnimationFrames();
  assert.equal(narrow, renderedLineSignature(paragraph));

  root.style.width = "220px";
  dispatchRelayout(root);
  flushAllTestAnimationFrames();
  assert.equal(initial, renderedLineSignature(paragraph));
  assert.equal(copySelection(paragraph), expected);
  assert.equal(emptyRenderedLineCount(paragraph), 0);
});

test("sourceFidelity_preservedCrLfNormalizesToOneBreak", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(
    "<div data-tiqian-root='true' style='width: 220px'><p style='white-space: pre-wrap'></p></div>",
  );
  const paragraph = root.querySelector("p");
  paragraph.textContent = "前\r\n后";

  assert.equal(TiqianWeb.enhance(root, testOptions()), 1);

  assert.equal(copySelection(paragraph), "前\n后");
  assert.equal(paragraph.querySelectorAll("[data-tq-hard-break]").length, 1);
  assert.equal(emptyRenderedLineCount(paragraph), 0);
});

test("sourceFidelity_zeroWidthSpaceCopiesFaithfully", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const source = "A.​.​.Complete？AaFont？";
  const root = mount(
    `<div data-tiqian-root='true' style='width: 120px'><p>${source}</p></div>`,
  );

  assert.equal(TiqianWeb.enhance(root, testOptions()), 1);

  const paragraph = root.querySelector("p");
  assert.equal(paragraph.getAttribute("data-tq-rendered"), "true");
  assert.equal(paragraph.getAttribute("data-tiqian-capability-issue"), null);
  assert.ok(paragraph.querySelectorAll(".tq-line").length > 1);
  assert.equal(copySelection(paragraph), source);
});

test("sourceFidelity_hostFontFamiliesDriveMeasureAndPaint", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(`
    <div data-tiqian-root="true">
      <p style='font-family: "CP-hashed", "HostFace", sans-serif; font-size: 21px; line-height: 33px; font-weight: 460; font-style: italic;'>中<a href="/target/" style='font-family: "LinkFace", sans-serif; font-size: 22px; font-weight: 520; font-style: normal;'>链接</a><code style='font-family: "CodeFace", monospace; font-size: 13px; font-weight: 430; font-style: normal;'>code</code></p>
    </div>
  `);

  const count = TiqianWeb.enhance(root, {
    fontFamilies: {
      cjk: "ConfiguredCjk, sans-serif",
      latin: "ConfiguredLatin, sans-serif",
      monospace: "ConfiguredMono, monospace",
    },
  });

  assert.equal(count, 1);
  const paragraph = root.querySelector("p");
  assert.ok(paragraph.style.fontFamily.includes("CP-hashed"), paragraph.style.fontFamily);
  assert.ok(paragraph.style.fontFamily.includes("HostFace"), paragraph.style.fontFamily);
  const line = paragraph.querySelector(".tq-line");
  assert.ok(line);
  assert.equal(cssPx(line.style.getPropertyValue("--tq-line-height")), 33);
  assert.equal(
    computedStyleValue(paragraph, "font-family"),
    computedStyleValue(line, "font-family"),
  );
  assert.equal(computedStyleValue(line, "font-size"), "21px");

  const link = paragraph.querySelector("a");
  assert.ok(link.style.fontFamily.includes("LinkFace"), link.style.fontFamily);
  assert.equal(link.style.fontSize, "22px");
  assert.equal(link.style.fontWeight, "520");

  const code = paragraph.querySelector("code");
  assert.ok(code.style.fontFamily.includes("CodeFace"), code.style.fontFamily);
  assert.equal(code.style.fontSize, "13px");
  assert.equal(code.style.fontWeight, "430");
});

test("sourceFidelity_hostInlineRenderStylesPreservedOnStrong", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(`
    <div data-tiqian-root="true">
      <p>中<strong class="host-strong" style='color: rgb(1, 2, 3); text-decoration-line: underline; text-decoration-color: rgb(4, 5, 6); text-decoration-style: dotted; text-decoration-thickness: 2px; text-underline-offset: 3px;'>强调</strong></p>
    </div>
  `);

  const count = TiqianWeb.enhance(root, testOptions());

  assert.equal(count, 1);
  const strong = root.querySelector("p strong.host-strong");
  assert.ok(strong);
  assert.equal(strong.style.getPropertyValue("color"), "rgb(1, 2, 3)");
  assert.equal(strong.style.getPropertyValue("text-decoration-line"), "underline");
  assert.equal(strong.style.getPropertyValue("text-decoration-color"), "rgb(4, 5, 6)");
  assert.equal(strong.style.getPropertyValue("text-decoration-style"), "dotted");
  assert.equal(strong.style.getPropertyValue("text-decoration-thickness"), "2px");
  assert.equal(strong.style.getPropertyValue("text-underline-offset"), "3px");
});
