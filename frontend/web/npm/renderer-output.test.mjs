// Renderer output tests ported from TiqianWebEnhancerTest.kt and
// TiqianWebProgressiveRelayoutTest.kt. Covers plain-flow text nodes, emergency
// breaks for long tokens, native list markers on a two-ic body indent, strong
// bold/emphasis-mark behavior, selectable spacing carriers, and sparse runs.

import assert from "node:assert/strict";
import test from "node:test";
import {
  cleanupMounted,
  copySelection,
  computedStyleValue,
  cssPx,
  directTextContent,
  dispatchRelayout,
  elementWidth,
  flushAllTestAnimationFrames,
  installTestAnimationFrames,
  loadHostRuntime,
  mount,
  renderedLineSignature,
  selectionCoversElement,
  testOptions,
} from "./runtime-host.mjs";

test("rendererOutput_plainFlowUsesTextNodesUntilGeometryNeedsSpan", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const source = "很长时间没写";
  const root = mount(`<div data-tiqian-root='true' style='width: 320px'><p>${source}</p></div>`);

  assert.equal(TiqianWeb.enhance(root, testOptions()), 1);

  const paragraph = root.querySelector("p");
  assert.equal(directTextContent(paragraph), source, paragraph.innerHTML);
  const selectionEnd = paragraph.querySelector("span[data-tq-selection-end='true']");
  assert.ok(selectionEnd);
  assert.equal(selectionEnd.textContent, "​");
  assert.equal(selectionEnd.getAttribute("data-tq-copy-ignore"), "true");
  assert.equal(selectionEnd.getAttribute("aria-hidden"), "true");
  assert.equal(paragraph.querySelectorAll(
    "span[data-tq-geometry]:not(.tq-line):not([data-tq-line-end-sentinel])",
  ).length, 0);
  const generated = paragraph.querySelectorAll("[data-tq-geometry][style]");
  for (let index = 0; index < generated.length; index += 1) {
    const style = generated.item(index)?.getAttribute("style") ?? "";
    assert.ok(!style.includes("all:"), style);
    assert.ok(!style.includes("text-spacing-trim:"), style);
  }
  const paragraphStyle = paragraph.getAttribute("style") ?? "";
  assert.ok(!paragraphStyle.includes("white-space"));
  assert.ok(!paragraphStyle.includes("text-autospace"));
  assert.equal(copySelection(paragraph), source);
});

test("rendererOutput_longInlineCodeTokenUsesEmergencyBreaksWithoutOverflow", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const token = "eeeeeeeebad9a5e4b24e74cb55e829fb82c8244c0a5a3bae585179575af33bb0";
  const root = mount(`
    <div data-tiqian-root="true" style="width: 240px">
      <p style="font-size: 18px; line-height: 30px">域名是 <code style="padding-left: 4px; padding-right: 4px">${token}</code>，它不会消失。</p>
    </div>
  `);

  assert.equal(TiqianWeb.enhance(root, testOptions()), 1);

  const paragraph = root.querySelector("p");
  assert.equal(paragraph.getAttribute("data-tq-rendered"), "true");
  const code = paragraph.querySelector("code");
  assert.ok(code);
  assert.equal(paragraph.querySelectorAll("code").length, 1);
  assert.equal(computedStyleValue(code, "box-decoration-break"), "slice");
  assert.equal(code.getAttribute("data-tq-inline-open-start"), null);
  assert.equal(code.getAttribute("data-tq-inline-open-end"), null);
  assert.ok(paragraph.querySelectorAll(".tq-line").length > 1, "Expected more than 1 tq-line");
  assert.ok(
    paragraph.scrollWidth <= paragraph.clientWidth + 1,
    `scrollWidth ${paragraph.scrollWidth} should be <= clientWidth ${paragraph.clientWidth} + 1`,
  );
  assert.ok(
    paragraph.querySelector("span[data-tq-copy-ignore][aria-hidden='true']:not(.tq-line)")
      ?.textContent !== "-",
    "Should not have hyphen",
  );
  assert.equal(paragraph.getAttribute("data-tiqian-capability-issue"), null);
  assert.equal(copySelection(paragraph), `域名是 ${token}，它不会消失。`);
});

test("rendererOutput_longLinkTokenSharesCleanEmergencyBreakPolicy", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const token = "eeeeeeeebad9a5e4b24e74cb55e829fb82c8244c0a5a3bae585179575af33bb0";
  const root = mount(`
    <div data-tiqian-root="true" style="width: 220px">
      <p style="font-size: 18px; line-height: 30px">详情见 <a href="/docs/${token}">${token}</a>，请核对。</p>
    </div>
  `);

  assert.equal(TiqianWeb.enhance(root, testOptions()), 1);

  const paragraph = root.querySelector("p");
  assert.equal(paragraph.getAttribute("data-tq-rendered"), "true");
  assert.ok(paragraph.querySelector("a"));
  assert.ok(paragraph.querySelectorAll(".tq-line").length > 1);
  assert.ok(paragraph.scrollWidth <= paragraph.clientWidth + 1);
  assert.ok(
    paragraph.querySelector("span[data-tq-copy-ignore][aria-hidden='true']:not(.tq-line)")
      ?.textContent !== "-",
  );
  assert.equal(copySelection(paragraph), `详情见 ${token}，请核对。`);
});

test("rendererOutput_orderedListKeepsNativeMarkersOnTwoIcBodyIndent", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const tenthSource = "第十项正文足够长，换行以后仍然沿着同一正文列继续排列。";
  const root = mount(`
    <div data-tiqian-root="true" style="width: 230px; font-size: 18px; line-height: 30px">
      <style>
        ol { box-sizing: border-box; padding-inline-start: 36px; margin-inline: 0; }
      </style>
      <p id="body">正文足够长，第一行应当填满与列表相同的版心网格，然后继续换行。</p>
      <ol start="8">
        <li id="eight">第八项。</li>
        <li id="nine">第九项。</li>
        <li id="ten">${tenthSource}</li>
      </ol>
    </div>
  `);

  assert.equal(TiqianWeb.enhance(root, testOptions()), 4);

  const list = root.querySelector("ol");
  const body = root.querySelector("#body");
  const tenth = root.querySelector("#ten");
  assert.equal(tenth.querySelector(":scope > [data-tq-list-marker]"), null);
  assert.equal(list.getAttribute("data-tq-list-layout"), null);
  assert.equal(list.getAttribute("data-tq-list-gutter-ic"), null);
  assert.equal(list.getAttribute("role"), null);
  assert.ok(!(tenth.textContent ?? "").includes("10."));
  assert.equal(computedStyleValue(list, "padding-inline-start"), "36px");
  assert.equal(computedStyleValue(tenth, "display"), "list-item");

  const proseLine = body.querySelector("[data-tq-line-width]");
  const listLine = tenth.querySelector(":scope > [data-tq-line-width]");
  assert.ok(proseLine);
  assert.ok(listLine);
  const proseMeasure = parseFloat(proseLine.getAttribute("data-tq-line-width"));
  const listMeasure = parseFloat(listLine.getAttribute("data-tq-line-width"));
  assert.ok(Number.isFinite(proseMeasure));
  assert.ok(Number.isFinite(listMeasure));
  assert.ok(Math.abs((36.0 + listMeasure) - proseMeasure) < 0.5);
  assert.equal(copySelection(tenth), tenthSource);

  const wideLines = renderedLineSignature(tenth);
  TiqianWeb.install();
  installTestAnimationFrames();
  root.style.width = "176px";
  dispatchRelayout(root);
  flushAllTestAnimationFrames();
  assert.notEqual(wideLines, renderedLineSignature(tenth));
  assert.equal(tenth.querySelector(":scope > [data-tq-list-marker]"), null);
  assert.equal(copySelection(tenth), tenthSource);

  TiqianWeb.destroy(root);
  assert.equal(list.getAttribute("data-tq-list-layout"), null);
  assert.equal(list.getAttribute("data-tq-list-gutter-ic"), null);
  assert.equal(list.getAttribute("role"), null);
  assert.equal(tenth.getAttribute("data-tq-list-item"), null);
  assert.equal(tenth.querySelector("[data-tq-list-marker]"), null);
  assert.equal(tenth.textContent, tenthSource);
});

test("rendererOutput_unorderedListUsesNativeMarkerColumnWithoutParagraphIndent", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(`
    <div data-tiqian-root="true" style="width: 216px; font-size: 18px; line-height: 30px">
      <style>
        ul { box-sizing: border-box; padding-inline-start: 36px; margin-inline: 0; }
      </style>
      <p>普通正文保留显式的两字段首缩进，用于证明列表没有继承它。</p>
      <ul><li id="bullet">项目正文不会再叠加段首缩进，续行只服从共享标记列。</li></ul>
    </div>
  `);
  const options = { fontSize: 18, lineHeight: 30, firstLineIndentIc: 2 };

  assert.equal(TiqianWeb.enhance(root, options), 2);

  const list = root.querySelector("ul");
  const paragraph = root.querySelector("p");
  const item = root.querySelector("#bullet");
  const paragraphLine = paragraph.querySelector(":scope > .tq-line");
  const itemLine = item.querySelector(":scope > .tq-line");
  assert.ok(paragraphLine);
  assert.ok(itemLine);
  assert.equal(list.getAttribute("data-tq-list-gutter-ic"), null);
  assert.equal(item.querySelector(":scope > [data-tq-list-marker]"), null);
  assert.equal(computedStyleValue(list, "padding-inline-start"), "36px");
  assert.equal(computedStyleValue(item, "display"), "list-item");
  assert.equal(paragraphLine.style.getPropertyValue("margin-left"), "36px");
  assert.ok(itemLine.style.getPropertyValue("margin-left").length === 0);
});

test("rendererOutput_strongStaysBoldByDefault", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(`
    <div data-tiqian-root="true" style="width: 320px">
      <p style="font-weight: 430">前<strong style="font-weight: 700">强调，CSharp</strong>后。</p>
    </div>
  `);

  assert.equal(TiqianWeb.enhance(root, testOptions()), 1);

  const paragraph = root.querySelector("p");
  const strong = paragraph.querySelector("strong");
  assert.ok(strong);
  assert.equal(strong.getAttribute("data-tq-cjk-emphasis"), null);
  assert.equal(paragraph.querySelectorAll("circle").length, 0);
  assert.equal(computedStyleValue(strong, "font-weight"), "700");
  assert.equal(copySelection(paragraph), "前强调，CSharp后。");
});

test("rendererOutput_onlyCjkContentInStrongGetsEmphasisMarks", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(`
    <div data-tiqian-root="true" style="width: 320px">
      <p style="font-weight: 430">前<strong style="font-weight: 700; color: rgb(1, 2, 3)">强调，CSharp 42🙂</strong>后。</p>
    </div>
  `);

  assert.equal(
    TiqianWeb.enhance(root, { ...testOptions(), strongAsEmphasisMarks: true }),
    1,
  );

  const paragraph = root.querySelector("p");
  const strong = paragraph.querySelector("strong[data-tq-cjk-emphasis]");
  assert.ok(strong);
  assert.equal(computedStyleValue(strong, "font-weight"), "430");
  assert.equal(paragraph.querySelectorAll("circle").length, 2);
  const overlay = paragraph.querySelector("svg[data-tq-geometry='true']");
  assert.ok(overlay);
  const firstDot = paragraph.querySelector("circle");
  assert.ok(firstDot);
  assert.equal(firstDot.getAttribute("fill"), "rgb(1, 2, 3)");
  assert.ok(!(overlay.getAttribute("style") ?? "").includes("position:absolute"));
  assert.ok((overlay.getAttribute("style") ?? "").startsWith("--tq-overlay-width:"));
  assert.equal(firstDot.getAttribute("style"), "--tq-decoration-color:rgb(1, 2, 3)");

  const descendants = strong.querySelectorAll("span");
  let cjkRun = null;
  let latinRun = null;
  for (let index = 0; index < descendants.length; index += 1) {
    const element = descendants.item(index);
    if (element?.nodeType !== 1) continue;
    const content = element.textContent;
    if (!content) continue;
    if (content.includes("强调")) cjkRun = element;
    if (content.includes("CSharp")) latinRun = element;
  }
  assert.ok(cjkRun);
  assert.ok(latinRun);
  assert.equal(computedStyleValue(cjkRun, "font-weight"), "430");
  assert.equal(computedStyleValue(latinRun, "font-weight"), "700");
  assert.equal(copySelection(paragraph), "前强调，CSharp 42🙂后。");
});

test("rendererOutput_emphasisDotGapShiftsDotCenterByConfiguredEm", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const enhanceWithGap = (gap) => {
    const root = mount(`
      <div data-tiqian-root="true" style="width: 320px">
        <p style="font-size: 18px">前<strong>强调</strong>后。</p>
      </div>
    `);
    assert.equal(
      TiqianWeb.enhance(
        root,
        { ...testOptions(), emphasisDotGapEm: gap, strongAsEmphasisMarks: true },
      ),
      1,
    );
    return parseFloat(root.querySelector("circle").getAttribute("cy"));
  };

  const defaultCenter = enhanceWithGap(0.10);
  const adjustedCenter = enhanceWithGap(0.25);
  assert.ok(Math.abs(18 * 0.15 - (adjustedCenter - defaultCenter)) <= 0.01);
});

test("rendererOutput_positiveGapUsesSelectableZeroHeightCarrier", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(`
    <div data-tiqian-root="true" style="width: 700px">
      <p>中文<a href="/target/" style="padding: 4px; margin: -4px">bug</a>中文。</p>
    </div>
  `);

  assert.equal(TiqianWeb.enhance(root, testOptions()), 1);

  const link = root.querySelector("p a");
  assert.ok(link);
  assert.equal(cssPx(computedStyleValue(link, "padding-right")), 4);
  assert.equal(cssPx(computedStyleValue(link, "margin-right")), -4);
  const fragments = link.querySelectorAll(":scope > span");
  let spacingFragment = null;
  for (let index = 0; index < fragments.length; index += 1) {
    const fragment = fragments.item(index);
    const carrier = fragment?.querySelector?.("[data-tq-spacing-carrier]");
    if (carrier && elementWidth(carrier) > 0.1) {
      spacingFragment = fragment;
    }
  }
  assert.ok(spacingFragment);
  const carrier = spacingFragment.querySelector("[data-tq-spacing-carrier]");
  assert.ok(carrier);
  assert.equal(spacingFragment.firstChild?.textContent, "bug");
  assert.equal(spacingFragment.getAttribute("data-tq-shaping-boundary"), "");
  assert.equal(carrier.textContent, " ");
  assert.equal(carrier.getAttribute("data-tq-copy-ignore"), "true");
  assert.equal(carrier.getAttribute("aria-hidden"), "true");
  assert.equal(computedStyleValue(carrier, "display"), "inline-block");
  assert.equal(cssPx(computedStyleValue(carrier, "height")), 0);
  assert.equal(cssPx(computedStyleValue(carrier, "line-height")), 0);
  assert.equal(cssPx(computedStyleValue(spacingFragment, "padding-right")), 0);
  assert.ok(
    selectionCoversElement(spacingFragment, carrier),
    `engine spacing must remain inside the native Range selection: ${spacingFragment.outerHTML}`,
  );
  assert.equal(copySelection(link), "bug");
});

test("rendererOutput_plainBodyTextRendersSparseRuns", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const text = "中文排版需要保留语义与宿主样式，同时由引擎负责断行和标点几何。".repeat(8);
  const root = mount(`<div data-tiqian-root='true' style='width: 320px'><p>${text}</p></div>`);

  assert.equal(TiqianWeb.enhance(root, testOptions()), 1);

  const paragraph = root.querySelector("p");
  const renderedNodes = paragraph.querySelectorAll("*").length;
  assert.ok(renderedNodes < text.length / 2, `renderedNodes=${renderedNodes} chars=${text.length}`);
  assert.ok(paragraph.querySelectorAll(".tq-line").length > 1);
  assert.equal(paragraph.getAttribute("data-tq-canonical-source"), "true");
});

test("rendererOutput_negativeGapResolvesToOverlapCarrier", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(`
    <div data-tiqian-root="true" style="width: 220px">
      <p style="font-size: 18px; line-height: 30px">你想要开发一个小软件（单文件），那么你现在应该选择C++（MFC）、Rust（Winio）。</p>
    </div>
  `);

  assert.equal(TiqianWeb.enhance(root, testOptions()), 1);

  // A multi-character run keeps a negative trailing gap as a negative
  // margin-right overlap instead of dropping it or using letter-spacing.
  const run = Array.from(root.querySelector("p").querySelectorAll("[data-tq-geometry]"))
    .find((element) => element.textContent === "C++");
  assert.ok(run);
  assert.equal(cssPx(run.style.getPropertyValue("margin-right")), -9);
  assert.equal(run.style.getPropertyValue("letter-spacing"), "");
});
