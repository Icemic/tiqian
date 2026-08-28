// Eligibility tests ported from TiqianWebEnhancerTest.kt,
// TiqianWebProgressiveRelayoutTest.kt and TiqianWebSourceFidelityTest.kt.
// Verifies paragraph eligibility policy: list containers, shared styles,
// nested roots, stateful inline objects, block images, zero-advance glyphs
// and unmodelable inline features.

import { strict as assert } from "node:assert";
import test from "node:test";
import {
  cleanupMounted,
  computedStyleValue,
  copySelection,
  cssPx,
  elementWidth,
  flushAllTestAnimationFrames,
  installTestAnimationFrames,
  loadHostRuntime,
  mount,
  relayoutEventIsStale,
  testOptions,
} from "./runtime-host.js";
import type { FakeElement } from "./snapshot-dom-fixtures.js";

test("eligibility_enhancesLeafListItemsWithoutReplacingListContainers", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(`
    <div data-tiqian-root="true" style="width: 220px">
      <ul>
        <li id="outer">外层<ul><li id="inner">内层<strong>正文</strong>。</li></ul></li>
        <li id="plain">普通列表项。</li>
      </ul>
    </div>
  `);

  const count = TiqianWeb.enhance(root as FakeElement & Element, testOptions());

  assert.equal(count, 2);
  const outer = root.querySelector("#outer")!;
  const inner = root.querySelector("#inner")!;
  const plain = root.querySelector("#plain")!;
  const outerList = root.querySelector("ul")!;
  const innerList = outer.querySelector(":scope > ul")!;
  assert.equal(outer.getAttribute("data-tq-rendered"), null);
  assert.ok(outer.querySelector(":scope > ul"));
  assert.equal(outerList.getAttribute("data-tq-list-layout"), null);
  assert.equal(innerList.getAttribute("data-tq-list-layout"), null);
  assert.equal(inner.getAttribute("data-tq-rendered"), "true");
  assert.equal(plain.getAttribute("data-tq-rendered"), "true");
  assert.ok(inner.querySelector("strong"));
  assert.equal(computedStyleValue(inner, "display"), "list-item");
  assert.equal(copySelection(inner), "内层正文。");
});

test("eligibility_progressiveEnhancementSkipsAutoSizedListContainers", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  for (const display of ["flex", "grid"]) {
    const root = mount(`
      <div data-tiqian-root="true" style="width: 220px">
        <ol>
          <li id="outer" style="display: ${display}">
            <p id="child">脚注正文应由客户端接管，而且接管前后不能改变 auto-sized item 的宿主宽度。</p>
            <a href="#note">↩</a>
          </li>
        </ol>
      </div>
    `);
    t.after(() => cleanupMounted());
    const outer = root.querySelector("#outer")!;
    const child = root.querySelector("#child")!;
    const sourceWidth = elementWidth(child);
    let stale = false;
    (root as unknown as { addEventListener(type: string, listener: (event: unknown) => void): void }).addEventListener("tiqian:ready", (event) => {
      stale = relayoutEventIsStale(event as Parameters<typeof relayoutEventIsStale>[0]);
    });
    installTestAnimationFrames();

    TiqianWeb.enhanceProgressively(root as FakeElement & Element, testOptions());
    flushAllTestAnimationFrames();

    assert.equal(outer.getAttribute("data-tq-rendered"), null);
    assert.equal(child.getAttribute("data-tq-rendered"), "true");
    assert.ok(Math.abs(elementWidth(child) - sourceWidth) < 0.5);
    assert.equal(child.getAttribute("data-tq-host-inline-size"), "true");
    assert.equal(root.getAttribute("data-tiqian-enhanced-count"), "1");
    assert.equal(stale, false);

    TiqianWeb.destroy(root as FakeElement & Element);
    assert.equal(child.getAttribute("data-tq-host-inline-size"), null);
    assert.ok(Math.abs(elementWidth(child) - sourceWidth) < 0.5);
  }
});

test("eligibility_missingSharedStylesKeepsSourceNativeWithIssue", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(
    "<div data-tiqian-root='true' style='width: 220px'><p>没有共享样式时不能静默接管断行。</p></div>",
    { sharedStylesReady: false },
  );
  const paragraph = root.querySelector("p")!;
  const original = paragraph.innerHTML;

  assert.equal(TiqianWeb.enhance(root as FakeElement & Element, testOptions()), 0);

  assert.equal(paragraph.innerHTML, original);
  assert.equal(paragraph.getAttribute("data-tq-rendered"), null);
  assert.equal(
    paragraph.getAttribute("data-tiqian-capability-issue"),
    "MissingSharedRuntimeStyles",
  );
});

test("eligibility_nestedRootsOwnOnlyDirectParagraphScope", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(`
    <div data-tiqian-root="true" style="width: 220px">
      <p class="outer">外层正文。</p>
      <div data-tiqian-root="true"><p class="inner">内层正文。</p></div>
    </div>
  `);

  const innerRoot = root.querySelector("[data-tiqian-root]")!;
  assert.ok(innerRoot);
  TiqianWeb.enhance(innerRoot as FakeElement & Element, testOptions());
  TiqianWeb.enhance(root as FakeElement & Element, testOptions());
  assert.equal(root.getAttribute("data-tiqian-enhanced-count"), "1");
  assert.equal(innerRoot.getAttribute("data-tiqian-enhanced-count"), "1");
  assert.equal(root.querySelectorAll("p.outer[data-tq-rendered='true']").length, 1);
  assert.equal(root.querySelectorAll("p.inner[data-tq-rendered='true']").length, 1);
  TiqianWeb.destroy(innerRoot as FakeElement & Element);
});

test("eligibility_statefulInlineObjectKeepsParagraphOriginal", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(`
    <div data-tiqian-root="true">
      <p>中文<button style="display: inline-block">unsupported</button>。</p>
    </div>
  `);
  const original = root.querySelector("p")!.innerHTML;

  const count = TiqianWeb.enhance(root as FakeElement & Element, testOptions());

  assert.equal(count, 0);
  const paragraph = root.querySelector("p")!;
  assert.equal(paragraph.innerHTML, original);
  assert.equal(
    paragraph.getAttribute("data-tiqian-capability-issue"),
    "UnsupportedStatefulInlineObject",
  );
});

test("eligibility_blockImageOnlyParagraphIgnoredQuietly", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(`
    <div data-tiqian-root="true" style="width: 220px">
      <p><img src="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='24' height='20'/%3E" alt="sample" width="24" height="20" style="display:block"></p>
    </div>
  `);
  const paragraph = root.querySelector("p")!;
  const original = paragraph.innerHTML;

  assert.equal(TiqianWeb.enhance(root as FakeElement & Element, testOptions()), 0);

  assert.equal(paragraph.innerHTML, original);
  assert.equal(paragraph.getAttribute("data-tiqian-capability-issue"), null);
  assert.equal(paragraph.getAttribute("data-tiqian-capability-detail"), null);
  assert.equal(paragraph.getAttribute("data-tq-rendered"), null);
  assert.equal(root.getAttribute("data-tiqian-issue-count"), null);
});

test("eligibility_textWithBlockImageFallsBackAtomically", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(`
    <div data-tiqian-root="true" style="width: 220px">
      <p>图片说明<img src="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='24' height='20'/%3E" alt="sample" width="24" height="20" style="display:block"></p>
    </div>
  `);
  const paragraph = root.querySelector("p")!;
  const original = paragraph.innerHTML;

  assert.equal(TiqianWeb.enhance(root as FakeElement & Element, testOptions()), 0);

  assert.equal(paragraph.innerHTML, original);
  assert.equal(
    paragraph.getAttribute("data-tiqian-capability-issue"),
    "UnsupportedInlineFormattingContext",
  );
  assert.equal(paragraph.getAttribute("data-tiqian-capability-detail"), "img:block");
  assert.equal(paragraph.getAttribute("data-tq-rendered"), null);
});

test("eligibility_zeroAdvanceGlyphsKeepParagraphNative", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(`
    <div data-tiqian-root="true">
      <p style="font-size: 0px">不可生成零宽行盒。</p>
    </div>
  `);
  const paragraph = root.querySelector("p")!;
  const original = paragraph.innerHTML;

  const count = TiqianWeb.enhance(root as FakeElement & Element);

  // Since the Slice 4d-2b host switchover (ADR 0053) the wire face rejects a
  // computed font size of zero up front with InvalidFontSize; the paragraph
  // stays native and fail-closed exactly as the zero-advance report did.
  assert.equal(count, 0);
  assert.equal(paragraph.innerHTML, original);
  assert.equal(
    paragraph.getAttribute("data-tiqian-capability-issue"),
    "WebEnhancementFailure",
  );
  assert.ok(
    String(paragraph.getAttribute("data-tiqian-capability-detail") ?? "").includes("InvalidFontSize"),
  );
});

test("eligibility_unmodelableInlineFeatureStaysParagraphNative", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(`
    <div data-tiqian-root="true" style="width: 320px">
      <p>阅读 <span style="font-feature-settings: 'hwid'; font-variant-east-asian: proportional-width">Font size</span> 以了解更多。</p>
    </div>
  `);
  const paragraph = root.querySelector("p")!;
  const originalHtml = paragraph.innerHTML;

  assert.equal(TiqianWeb.enhance(root as FakeElement & Element, testOptions()), 0);
  assert.equal(paragraph.innerHTML, originalHtml);
  assert.equal(paragraph.getAttribute("data-tq-rendered"), null);
  assert.equal(
    paragraph.getAttribute("data-tiqian-capability-issue"),
    "UnsupportedInlineShapingStyle",
  );
  assert.equal(
    paragraph.getAttribute("data-tiqian-capability-detail"),
    "span:font-feature-settings",
  );
});
