// Copy fidelity tests ported from TiqianWebCopyTest.kt and the copy-related
// cases in TiqianWebProgressiveRelayoutTest.kt / TiqianWebSourceFidelityTest.kt.
// Verifies the clipboard pipeline: text/plain restores source text, text/html
// keeps semantic elements with host styles, and engine artifacts (geometry
// spans, paint-only nodes, soft-wrap breaks, hyphen glyphs, data-tq-* marks)
// never reach the clipboard.

import assert from "node:assert/strict";
import test from "node:test";
import {
  cleanupMounted,
  copiedData,
  copiedNodeData,
  copySelection,
  copySelectionWasIntercepted,
  copyWasIntercepted,
  loadHostRuntime,
  mount,
  testOptions,
} from "./runtime-host.js";
import type { FakeElement } from "./snapshot-dom-fixtures.js";

test("copyFidelity_singleParagraphClipboardRestoresSourceAndSemanticHtml", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(
    "<div><p data-tq-rendered='true' " +
      "style='position: relative !important; white-space-collapse: preserve !important'>" +
      "前<strong data-tq-source-semantic='true' data-tq-cjk-emphasis='true' " +
      "style='font-weight: 400 !important; color: red'>" +
      "<span data-tq-geometry='true' style='all: unset !important'>强调</span></strong>" +
      "<a data-tq-source-semantic='true' class='host-link' href='/target/'>" +
      "<span data-tq-geometry='true'>链接</span></a>" +
      "<span data-tq-geometry='true'><span data-tq-src='原文'>显示</span></span>" +
      "<span data-tq-src='&#10;' data-tq-hard-break='true' style='display:none'></span>" +
      "<br data-tq-engine-break='MandatoryBreak'>后" +
      "<span data-tq-copy-ignore='true'>paint-only</span></p></div>",
  );
  TiqianWeb.install();
  const paragraph = root.querySelector("p")!;

  assert.equal(copiedData(paragraph, "text/plain"), "前强调链接原文\n后");
  const html = copiedData(paragraph, "text/html");

  assert.ok(html.includes("<strong"), html);
  assert.ok(html.includes("color: red"), html);
  assert.ok(!html.includes("font-weight"), html);
  assert.ok(html.includes('class="host-link"'), html);
  assert.ok(html.includes('href="/target/"'), html);
  assert.ok(html.includes("原文"), html);
  assert.ok(!html.includes("显示"), html);
  assert.ok(!html.includes("paint-only"), html);
  assert.ok(!html.includes("data-tq-"), html);
  assert.ok(!html.includes("all: unset"), html);
  assert.equal((html.match(/<br\b/g) || []).length, 1, html);
});

test("copyFidelity_partialRangeOfMandatoryBreakCopiesNewlineOrBr", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(
    "<div><p data-tq-rendered='true'>前" +
      "<span data-tq-src='&#10;' data-tq-hard-break='true' style='display:none'></span>" +
      "<br data-tq-engine-break='MandatoryBreak'>后</p></div>",
  );
  TiqianWeb.install();
  const marker = root.querySelector("[data-tq-hard-break]")!;
  const semanticBreak = root.querySelector("br[data-tq-engine-break='MandatoryBreak']")!;

  assert.equal(copiedNodeData(marker, "text/plain"), "\n");
  assert.equal(copiedNodeData(semanticBreak, "text/plain"), "\n");
  assert.equal(copiedNodeData(marker, "text/html"), "<br>");
  assert.equal(copiedNodeData(semanticBreak, "text/html"), "<br>");
});

test("copyFidelity_crossParagraphClipboardKeepsOnlySourceBoundaries", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(
    "<div data-tiqian-root='true' style='width:90px'>" +
      "<p>第一段很长会产生软折行，<strong>重点仍然保留</strong>。</p>" +
      "<p>第二段也会折行，<a class='host-link' href='/target/'>链接仍然保留</a>。</p>" +
      "</div>",
  );
  assert.equal(TiqianWeb.enhance(root as FakeElement & Element, testOptions()), 2);

  assert.equal(
    copiedData(root, "text/plain"),
    "第一段很长会产生软折行，重点仍然保留。\n第二段也会折行，链接仍然保留。",
  );
  const html = copiedData(root, "text/html");

  assert.equal((html.match(/<p(?:\s|>)/g) || []).length, 2, html);
  assert.ok(html.includes("<strong"), html);
  assert.ok(html.includes('class="host-link"'), html);
  assert.ok(html.includes('href="/target/"'), html);
  assert.ok(!html.includes("data-tq-"), html);
  assert.ok(!html.includes("tq-line"), html);
  assert.ok(!html.includes("data-tq-engine-break"), html);
});

test("copyFidelity_copyOutsideRenderedParagraphRemainsNative", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  TiqianWeb.install();
  const root = mount("<div><p>普通站点文本不属于提椠。</p></div>");
  const paragraph = root.querySelector("p")!;

  assert.equal(copyWasIntercepted(paragraph), false);
  assert.equal(copiedData(paragraph, "text/plain"), "");
  assert.equal(copiedData(paragraph, "text/html"), "");
});

test("copyFidelity_copyHandlerIgnoresNonRenderedParagraphs", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount("<div><p>普通站点文本不属于 Tiqian。</p></div>");
  TiqianWeb.install();
  const paragraph = root.querySelector("p")!;

  assert.equal(copySelection(paragraph), "普通站点文本不属于 Tiqian。");
  assert.equal(copySelectionWasIntercepted(paragraph), false);
});

test("copyFidelity_hardBreakCopiedSoftWrapsOmitted", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const source = "第一行中文需要自动换行。第二段内容继续占满宽度。";
  const root = mount(
    "<div data-tiqian-root='true' style='width: 120px'>" +
      `<p>${source}<br>显式换行之后。</p>` +
      "</div>",
  );
  assert.equal(TiqianWeb.enhance(root as FakeElement & Element, testOptions()), 1);
  const paragraph = root.querySelector("p")!;
  assert.ok(paragraph.querySelectorAll(".tq-line").length > 2);

  const visualBreaks = paragraph.querySelectorAll(
    "br[data-tq-engine-break]:not([data-tq-engine-break='MandatoryBreak'])",
  );
  assert.ok(visualBreaks.length > 0);
  for (const visualBreak of visualBreaks) {
    assert.equal(visualBreak.getAttribute("aria-hidden"), "true");
    assert.equal(visualBreak.getAttribute("data-tq-copy-ignore"), "true");
  }
  const sourceBreak = paragraph.querySelector("br[data-tq-engine-break='MandatoryBreak']")!;
  assert.equal(sourceBreak.getAttribute("aria-hidden"), null);
  assert.equal(sourceBreak.getAttribute("data-tq-copy-ignore"), null);

  assert.equal(copySelection(paragraph), `${source}\n显式换行之后。`);
});

test("copyFidelity_engineHyphenGlyphsOmittedFromCopy", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const source = "中Network";
  const root = mount(
    "<div data-tiqian-root='true' style='width: 64px'><p>" + source + "</p></div>",
  );
  assert.equal(TiqianWeb.enhance(root as FakeElement & Element, testOptions()), 1);

  const paragraph = root.querySelector("p")!;
  // The prepared renderer marks engine hyphens explicitly; the line-end
  // sentinels share the copy-ignore/aria-hidden pair with empty text.
  const hyphen = paragraph.querySelector("span[data-tq-engine-hyphen]");
  assert.ok(hyphen);
  assert.equal(hyphen.getAttribute("aria-hidden"), "true");
  assert.equal(hyphen.getAttribute("data-tq-copy-ignore"), "true");
  assert.equal(hyphen.textContent, "-");
  assert.equal(copySelection(paragraph), source);
});
