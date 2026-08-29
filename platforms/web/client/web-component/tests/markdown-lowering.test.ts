// Markdown lowering tests ported from TiqianWebEnhancerTest.kt and
// TiqianWebSourceFidelityTest.kt. Covers in-place enhancement of markdown
// inline paragraphs, opaque inline objects (span/img/svg), formatting-context
// lowering of textual inline tags, pseudo-element generated content measured
// into line width, host inline box edges, and sup/sub participation.

import assert from "node:assert/strict";
import test from "node:test";
import {
  cleanupMounted,
  computedPseudoContent,
  computedStyleValue,
  copySelection,
  cssPx,
  loadHostRuntime,
  mount,
  preparedValueStyleProperty,
  probe,
  testOptions,
} from "./runtime-host.js";

test("markdownLowering_enhancesSupportedMarkdownInlineParagraphInPlace", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(`
    <div data-tiqian-root="true" style="width: 220px">
      <p>中文<strong>粗体</strong><em>italic</em><code>code</code><a class="host-link" href="/target/">链接</a><br>换行。</p>
    </div>
  `);

  const count = TiqianWeb.enhance(probe<Element>(root), testOptions());

  assert.equal(count, 1);
  const paragraph = root.querySelector("p")!;
  assert.ok(paragraph);
  assert.equal(paragraph.getAttribute("data-tq-rendered"), "true");
  assert.equal(root.querySelector(".tq-paragraph"), null);
  assert.equal(copySelection(paragraph), "中文粗体italiccode链接\n换行。");
  assert.ok(paragraph.querySelector("strong"));
  assert.ok(paragraph.querySelector("em"));
  assert.ok(paragraph.querySelector("code"));
  assert.ok(paragraph.querySelector("a.host-link[href='/target/']"));
  assert.equal(paragraph.style.getPropertyValue("display"), "");
  assert.equal(paragraph.getAttribute("data-tq-copy-ignore"), null);
});

test("markdownLowering_enhancesInlineCodeParagraphWithBrowserResolvedMonospaceFont", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(`
    <div data-tiqian-root="true" style="width: 220px">
      <p>中文<code style='font-family: "MissingFixtureMono", monospace'>code</code>正文。</p>
    </div>
  `);

  const count = TiqianWeb.enhance(probe<Element>(root), { fontSize: 18, lineHeight: 30 });

  assert.equal(count, 1);
  const paragraph = root.querySelector("p")!;
  assert.equal(paragraph.getAttribute("data-tq-rendered"), "true");
  assert.equal(paragraph.getAttribute("data-tiqian-capability-issue"), null);
  assert.ok(paragraph.querySelector("code"));
  assert.equal(copySelection(paragraph), "中文code正文。");
});

test("markdownLowering_measurableUnknownInlineElementsBecomeOpaqueObjects", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(`
    <div data-tiqian-root="true" style="width: 260px">
      <p style="font-size: 18px; line-height: 30px">前<span class="badge" style="display:inline-block;width:42px;height:20px">badge</span><img class="icon" src="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='20' height='20'/%3E" alt="icon" width="20" height="20" style="display:inline-block;padding-bottom:4px"><svg class="raw-svg" width="18" height="20" viewBox="0 0 18 20" style="display:inline-block"><circle cx="9" cy="10" r="8"></circle></svg>后。</p>
    </div>
  `);

  assert.equal(TiqianWeb.enhance(probe<Element>(root), testOptions()), 1);

  const paragraph = root.querySelector("p")!;
  assert.equal(paragraph.querySelectorAll("[data-tq-inline-object]").length, 3);
  assert.ok(paragraph.querySelector("span.badge[data-tq-inline-object]"));
  assert.ok(paragraph.querySelector("img.icon[data-tq-inline-object][alt='icon']"));
  assert.ok(paragraph.querySelector("svg.raw-svg[data-tq-inline-object] circle"));
  assert.equal(copySelection(paragraph), "前badge后。");
  assert.ok(!paragraph.textContent.includes("￼"));
  assert.equal(paragraph.getAttribute("data-tiqian-capability-issue"), null);

  const objectLine = paragraph.querySelector(".tq-line")!;
  assert.ok(objectLine);
  assert.ok(cssPx(preparedValueStyleProperty(objectLine, "--tq-line-height")) >= 30);
});

test("markdownLowering_paragraphOfOnlyInlineObjectEnhances", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(`
    <div data-tiqian-root="true" style="width: 220px">
      <p><svg class="only-object" width="24" height="20" viewBox="0 0 24 20" style="display:inline-block"><circle cx="12" cy="10" r="8"></circle></svg></p>
    </div>
  `);

  assert.equal(TiqianWeb.enhance(probe<Element>(root), testOptions()), 1);

  const paragraph = root.querySelector("p")!;
  assert.equal(paragraph.getAttribute("data-tq-rendered"), "true");
  assert.ok(paragraph.querySelector("svg.only-object[data-tq-inline-object] circle"));
  assert.equal(paragraph.getAttribute("data-tiqian-capability-issue"), null);
});

test("markdownLowering_loweringDecidesByTextualFormattingContext", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(`
    <div data-tiqian-root="true" style="width: 220px">
      <p>中<span class="host-span">span</span><mark>mark</mark><del>delete</del><spoiler>秘密</spoiler>文。</p>
    </div>
  `);

  assert.equal(TiqianWeb.enhance(probe<Element>(root), testOptions()), 1);

  const paragraph = root.querySelector("p")!;
  assert.equal(paragraph.getAttribute("data-tq-rendered"), "true");
  assert.ok(paragraph.querySelector("span.host-span[data-tq-source-semantic]"));
  assert.ok(paragraph.querySelector("mark[data-tq-source-semantic]"));
  assert.ok(paragraph.querySelector("del[data-tq-source-semantic]"));
  assert.ok(paragraph.querySelector("spoiler[data-tq-source-semantic]"));
  assert.equal(paragraph.getAttribute("data-tiqian-capability-issue"), null);
});

test("markdownLowering_generatedContentOnSemanticsUsesMeasuredBox", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(`
    <div data-tiqian-root="true" style="width: 320px">
      <style>
        .generated-footnote::before { content: "["; }
        .generated-footnote::after { content: "]"; }
        .absolute-decoration::before { content: "•"; position: absolute; }
      </style>
      <p class="generated">正文<a class="generated-footnote" href="#note">1</a>继续。</p>
      <p class="plain">正文<a href="#plain">1</a>继续。</p>
      <p class="decorated">正文<span class="absolute-decoration">装饰</span>继续。</p>
    </div>
  `);
  const paragraph = root.querySelector("p.generated")!;

  assert.equal(TiqianWeb.enhance(probe<Element>(root), testOptions()), 3);
  assert.equal(paragraph.getAttribute("data-tq-rendered"), "true");
  assert.equal(paragraph.getAttribute("data-tiqian-capability-issue"), null);
  const footnote = paragraph.querySelector("a.generated-footnote")!;
  assert.ok(footnote);
  assert.equal(computedPseudoContent(footnote, "::before"), "[");
  assert.equal(computedPseudoContent(footnote, "::after"), "]");
  assert.equal(copySelection(paragraph), "正文1继续。");
  const plain = root.querySelector("p.plain")!;
  const generatedWidth = parseFloat(
    paragraph.querySelector(".tq-line")?.getAttribute("data-tq-line-width") ?? "",
  );
  const plainWidth = parseFloat(
    plain.querySelector(".tq-line")?.getAttribute("data-tq-line-width") ?? "",
  );
  assert.ok(Number.isFinite(generatedWidth));
  assert.ok(Number.isFinite(plainWidth));
  assert.ok(generatedWidth > plainWidth + 1);
  const decorated = root.querySelector("p.decorated")!;
  assert.equal(decorated.getAttribute("data-tq-rendered"), "true");
  assert.equal(decorated.getAttribute("data-tiqian-capability-issue"), null);
});

test("markdownLowering_rootGeneratedContentKeepsParagraphNative", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(`
    <div data-tiqian-root="true" style="width: 320px">
      <style>.generated-root::before { content: "※"; }</style>
      <p class="generated-root">正文保持原生。</p>
    </div>
  `);
  const paragraph = root.querySelector("p")!;
  const original = paragraph.innerHTML;

  assert.equal(TiqianWeb.enhance(probe<Element>(root), testOptions()), 0);

  assert.equal(paragraph.innerHTML, original);
  assert.equal(paragraph.getAttribute("data-tq-rendered"), null);
  assert.equal(
    paragraph.getAttribute("data-tiqian-capability-issue"),
    "UnsupportedGeneratedInlineContent",
  );
  assert.ok(
    (paragraph.getAttribute("data-tiqian-capability-detail") ?? "").startsWith("p::before:"),
  );
});

test("markdownLowering_hostInlineBoxEdgesMeasuredIntoLayout", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(`
    <div data-tiqian-root="true" style="width: 220px">
      <p>中<code style="padding-left: 4px; padding-right: 4px">code</code>文。</p>
    </div>
  `);

  const count = TiqianWeb.enhance(probe<Element>(root), testOptions());

  assert.equal(count, 1);
  const paragraph = root.querySelector("p")!;
  const code = paragraph.querySelector("code");
  assert.ok(code);
  assert.equal(computedStyleValue(code, "padding-left"), "4px");
  assert.equal(computedStyleValue(code, "padding-right"), "4px");
  assert.equal(paragraph.getAttribute("data-tiqian-capability-issue"), null);
});

test("markdownLowering_superscriptAndSubscriptParticipateInEnhancement", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(`
    <div data-tiqian-root="true" style="width: 220px">
      <p>公式 x<sup>2</sup> 与 H<sub>2</sub>O 仍然参与中文段落排版。</p>
    </div>
  `);

  assert.equal(TiqianWeb.enhance(probe<Element>(root), testOptions()), 1);

  const paragraph = root.querySelector("p")!;
  assert.equal(paragraph.getAttribute("data-tq-rendered"), "true");
  assert.ok(paragraph.querySelector("sup"));
  assert.ok(paragraph.querySelector("sub"));
  assert.equal(paragraph.getAttribute("data-tiqian-capability-issue"), null);
  assert.equal(copySelection(paragraph), "公式 x2 与 H2O 仍然参与中文段落排版。");
});

test("markdownLowering_superscriptGeneratedContentKeepsUniqueId", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(`
    <div data-tiqian-root="true" style="width: 520px">
      <style>
        .fn { padding-left: 4px; padding-right: 4px; margin-left: -4px; margin-right: -4px; }
        .fn::before { content: "["; }
        .fn::after { content: "]"; }
      </style>
      <p>这里有脚注<sup style="position: relative; top: -5px; font-size: 12px; line-height: 0"><a class="fn" id="fnref-1" href="#fn-1">1</a></sup>并继续正文。</p>
    </div>
  `);

  assert.equal(TiqianWeb.enhance(probe<Element>(root), testOptions()), 1);

  const paragraph = root.querySelector("p")!;
  const sup = paragraph.querySelector("sup");
  assert.ok(sup);
  assert.equal(computedStyleValue(sup, "top"), "-5px");
  assert.equal(paragraph.querySelectorAll("#fnref-1").length, 1);
  assert.ok(paragraph.querySelector("a.fn[href='#fn-1']"));
  assert.equal(paragraph.getAttribute("data-tq-rendered"), "true");
  assert.equal(paragraph.getAttribute("data-tiqian-capability-issue"), null);
  assert.equal(copySelection(paragraph), "这里有脚注1并继续正文。");
  assert.equal(paragraph.querySelectorAll(".tq-line").length, 1);
  // Same width self-check as the karma twin: the declared line width and the
  // line's flow width come from the plan and must agree. The inline sup clone
  // re-measures at its own 12px font in the DOM, so the flow-width invariant
  // is asserted on the marker attributes the engine itself declares.
  const lineMarker = paragraph.querySelector(".tq-line")!;
  const declaredWidth = parseFloat(lineMarker.getAttribute("data-tq-line-width") ?? "");
  const flowWidth = parseFloat(lineMarker.getAttribute("data-tq-line-flow-width") ?? "");
  assert.ok(Number.isFinite(declaredWidth));
  assert.ok(Number.isFinite(flowWidth));
  assert.ok(
    Math.abs(declaredWidth - flowWidth) <= 0.01,
    `declared ${declaredWidth} vs flow ${flowWidth}`,
  );
});
