// Responsive measure tests ported from TiqianWebEnhancerTest.kt,
// TiqianWebExactSessionTest.kt, TiqianWebProgressiveRelayoutTest.kt and
// TiqianWebSourceFidelityTest.kt. Verifies how the runtime derives the line
// measure: shrink-to-fit hosts, multi-column fragmentainers, list padding,
// configured typography, the responsive grid quantization and the stale
// measure guards during relayout.

import assert from "node:assert/strict";
import test from "node:test";
import {
  attachWorker,
  cleanupMounted,
  computedStyleValue,
  copySelection,
  cssPx,
  elementFragmentWidths,
  elementWidth,
  exactWorkerRequestMaxWidth,
  flushAllTestAnimationFrames,
  dispatchRelayout,
  grantWorkerSlice,
  installTestAnimationFrames,
  loadHostRuntime,
  mount,
  pendingTestAnimationFrameCount,
  relayoutEventIsStale,
  renderedLineSignature,
  runWorkerJobToCompletion,
  testOptions,
} from "./runtime-host.mjs";

test("responsiveMeasure_preservesWidthDerivedThroughShrinkToFitAncestor", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(`
    <div data-tiqian-root="true" style="width: 576px">
      <figure style="display: inline-block; margin: 0; max-width: 100%">
        <div style="width: 500px"></div>
        <figcaption>
          <p style="margin: 0">ContentSizedParagraphWithoutNativeBreakOpportunitiesMustKeepTheHostMeasureWhileItsSourceNodesAreInRawDom</p>
        </figcaption>
      </figure>
    </div>
  `);
  const paragraph = root.querySelector("p");
  const sourceWidth = elementWidth(paragraph);
  let stale = false;
  root.addEventListener("tiqian:ready", (event) => {
    stale = relayoutEventIsStale(event);
  });
  installTestAnimationFrames();

  TiqianWeb.enhanceProgressively(root, testOptions());
  flushAllTestAnimationFrames();

  assert.equal(paragraph.getAttribute("data-tq-rendered"), "true");
  assert.equal(paragraph.getAttribute("data-tq-host-inline-size"), "true");
  assert.ok(Math.abs(elementWidth(paragraph) - sourceWidth) < 0.5);
  assert.equal(stale, false);

  TiqianWeb.destroy(root);
  assert.equal(paragraph.getAttribute("data-tq-host-inline-size"), null);
  assert.ok(Math.abs(elementWidth(paragraph) - sourceWidth) < 0.5);
});

test("responsiveMeasure_multiColumnFragmentsUseOneFragmentainerAsLineMeasure", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const source = "多栏正文的段落即使跨过多个栏片段，也始终只在单栏版心内完成断行。".repeat(10);
  const root = mount(`
    <div data-tiqian-root="true" style="width: 400px; height: 120px; columns: 180px auto; column-gap: 40px; column-fill: auto; font-size: 18px; line-height: 30px">
      <p style="margin: 0">${source}</p>
    </div>
  `);
  const paragraph = root.querySelector("p");
  const fragmentWidths = elementFragmentWidths(paragraph);

  assert.ok(fragmentWidths.length > 1, "fixture must fragment across CSS columns");
  assert.ok(elementWidth(paragraph) > Math.max(...fragmentWidths));
  assert.equal(TiqianWeb.enhance(root, testOptions()), 1);

  const firstLine = paragraph.querySelector(":scope > .tq-line");
  const lineMeasure = Number(firstLine.getAttribute("data-tq-line-width"));
  assert.ok(lineMeasure <= Math.max(...fragmentWidths) + 0.5);
  assert.equal(copySelection(paragraph), source);
});

test("responsiveMeasure_listItemPaddingExcludedFromLineMeasure", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(`
    <div data-tiqian-root="true" style="width: 216px; font-size: 18px; line-height: 30px">
      <style>
        ul { box-sizing: border-box; padding-inline-start: 36px; margin-inline: 0; }
        li { box-sizing: border-box; padding-inline-start: 7px; }
      </style>
      <ul><li id="padded">列表项自己的 padding 不能被正文版心重复占用。</li></ul>
    </div>
  `);

  assert.equal(TiqianWeb.enhance(root, testOptions()), 1);

  const item = root.querySelector("#padded");
  const line = item.querySelector(":scope > [data-tq-line-width]");
  const lineMeasure = Number(line.getAttribute("data-tq-line-width"));
  const contentWidth =
    item.clientWidth -
    cssPx(computedStyleValue(item, "padding-left")) -
    cssPx(computedStyleValue(item, "padding-right"));
  assert.ok(lineMeasure <= contentWidth + 0.5);
  assert.ok(
    Math.abs(lineMeasure - 162.0) < 0.5,
    `173px content box should expose nine 18px cells, was ${lineMeasure}`,
  );
  assert.equal(copySelection(item), "列表项自己的 padding 不能被正文版心重复占用。");
});

test("responsiveMeasure_typographyRefreshRelowersCurrentHostMetrics", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(`
    <div data-tiqian-root="true" style="width: 220px">
      <p style="font-size: 16px; line-height: 28px; font-weight: 400">宿主样式加载后需要重新度量。</p>
    </div>
  `);

  assert.equal(TiqianWeb.enhance(root), 1);
  let paragraph = root.querySelector("p");
  assert.equal(
    cssPx(paragraph.querySelector(".tq-line").style.getPropertyValue("--tq-line-height")),
    28,
  );

  paragraph.style.fontSize = "18px";
  paragraph.style.lineHeight = "32px";
  paragraph.style.fontWeight = "460";
  TiqianWeb.refresh(root, false);

  paragraph = root.querySelector("p");
  const line = paragraph.querySelector(".tq-line");
  assert.ok(line);
  assert.equal(cssPx(line.style.getPropertyValue("--tq-line-height")), 32);
  assert.equal(computedStyleValue(paragraph, "font-size"), "18px");
  assert.equal(computedStyleValue(paragraph, "font-weight"), "460");
});

test("responsiveMeasure_workerRequestsUseResponsiveLineLengthGrid", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(`
    <div data-tiqian-root="true" style="width: 220px">
      <p style="font-size: 18px; line-height: 30px">同一字数网格必须复用 Worker 请求。</p>
    </div>
  `);
  const paragraph = root.querySelector("p");
  TiqianWeb.install();

  const first = exactWorkerRequestMaxWidth(root, paragraph);
  root.style.width = "225px";
  const sameGrid = exactWorkerRequestMaxWidth(root, paragraph);
  root.style.width = "234px";
  const nextGrid = exactWorkerRequestMaxWidth(root, paragraph);

  assert.equal(first, 216);
  assert.equal(sameGrid, first);
  assert.equal(nextGrid, 234);
});

test("responsiveMeasure_configuredFontSizeMeasuresAndPaintsConsistently", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(`
    <div data-tiqian-root="true" style="width: 700px">
      <p style="display: inline-block; font-size: 16px; line-height: 25px">一二三四五六七八九十甲乙丙丁戊己<a href="/more">庚辛</a></p>
    </div>
  `);
  const paragraph = root.querySelector("p");

  assert.equal(
    TiqianWeb.enhance(root, testOptions({ fontSize: 19, lineHeight: 33.25 })),
    1,
  );

  const line = paragraph.querySelector(".tq-line");
  const link = paragraph.querySelector("a");
  assert.equal(computedStyleValue(paragraph, "font-size"), "19px");
  assert.equal(computedStyleValue(link, "font-size"), "19px");
  assert.equal(cssPx(line.style.getPropertyValue("--tq-line-height")), 33.25);
  assert.ok(Math.abs(Number(line.getAttribute("data-tq-line-width")) - 342) <= 0.5);

  TiqianWeb.destroy(root);
  assert.equal(computedStyleValue(paragraph, "font-size"), "16px");
  assert.ok(paragraph.querySelector("a[href='/more']"));
});

test("responsiveMeasure_staleMeasureGuardSkipsOneCellDrift", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const source = "任务执行中再次跨格时不能提交落后最终宽度的排版。".repeat(2);
  const markup = Array.from({ length: 10 }, () => `<p>${source}</p>`).join("");
  const root = mount(`<div data-tiqian-root='true' style='width: 320px'>${markup}</div>`);
  const intermediateRoot = mount(`<div data-tiqian-root='true' style='width: 180px'>${markup}</div>`);
  const finalRoot = mount(`<div data-tiqian-root='true' style='width: 162px'>${markup}</div>`);
  TiqianWeb.install();
  assert.equal(TiqianWeb.enhance(root, testOptions()), 10);
  assert.equal(TiqianWeb.enhance(intermediateRoot, testOptions()), 10);
  assert.equal(TiqianWeb.enhance(finalRoot, testOptions()), 10);
  const paragraphs = Array.from(root.querySelectorAll("p"));
  const initialChildren = paragraphs.map((paragraph) => {
    assert.ok(paragraph.firstChild);
    return paragraph.firstChild;
  });
  const initial = renderedLineSignature(paragraphs[0]);
  const intermediate = renderedLineSignature(intermediateRoot.querySelector("p"));
  const final = renderedLineSignature(finalRoot.querySelector("p"));
  assert.notEqual(initial, intermediate);
  assert.notEqual(intermediate, final);
  let readyCount = 0;
  let staleCount = 0;
  root.addEventListener("tiqian:relayout-ready", (event) => {
    readyCount += 1;
    if (relayoutEventIsStale(event)) staleCount += 1;
  });

  installTestAnimationFrames();
  attachWorker(root);
  root.style.width = "180px";
  dispatchRelayout(root);
  grantWorkerSlice(root);
  root.style.width = "162px";
  runWorkerJobToCompletion(root);

  const committed = paragraphs.filter(
    (paragraph, index) => paragraph.firstChild !== initialChildren[index],
  ).length;
  assert.ok(committed >= 1 && committed < paragraphs.length);
  for (const [index, paragraph] of paragraphs.entries()) {
    const expected =
      paragraph.firstChild === initialChildren[index] ? initial : intermediate;
    assert.equal(renderedLineSignature(paragraph), expected);
  }
  assert.equal(readyCount, 1);
  assert.equal(staleCount, 1);

  dispatchRelayout(root);
  runWorkerJobToCompletion(root);

  for (const paragraph of paragraphs) {
    assert.equal(renderedLineSignature(paragraph), final);
  }
  assert.equal(readyCount, 2);
  assert.equal(staleCount, 1);
});

test("responsiveMeasure_multiCellDriftDiscardsPreparedMeasure", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const source = "长文 resize 不能把相差多个字格的历史结果逐级播放出来。".repeat(2);
  const markup = Array.from({ length: 10 }, () => `<p>${source}</p>`).join("");
  const root = mount(`<div data-tiqian-root='true' style='width: 320px'>${markup}</div>`);
  const intermediateRoot = mount(`<div data-tiqian-root='true' style='width: 180px'>${markup}</div>`);
  TiqianWeb.install();
  assert.equal(TiqianWeb.enhance(root, testOptions()), 10);
  assert.equal(TiqianWeb.enhance(intermediateRoot, testOptions()), 10);
  const paragraphs = Array.from(root.querySelectorAll("p"));
  const initialChildren = paragraphs.map((paragraph) => {
    assert.ok(paragraph.firstChild);
    return paragraph.firstChild;
  });
  const initial = renderedLineSignature(paragraphs[0]);
  const intermediate = renderedLineSignature(intermediateRoot.querySelector("p"));
  assert.notEqual(initial, intermediate);
  let readyCount = 0;
  let staleCount = 0;
  root.addEventListener("tiqian:relayout-ready", (event) => {
    readyCount += 1;
    if (relayoutEventIsStale(event)) staleCount += 1;
  });

  installTestAnimationFrames();
  attachWorker(root);
  root.style.width = "180px";
  dispatchRelayout(root);
  grantWorkerSlice(root);
  root.style.width = "144px";
  runWorkerJobToCompletion(root);

  const committed = paragraphs.filter(
    (paragraph, index) => paragraph.firstChild !== initialChildren[index],
  ).length;
  assert.ok(committed >= 1 && committed < paragraphs.length);
  for (const [index, paragraph] of paragraphs.entries()) {
    const expected =
      paragraph.firstChild === initialChildren[index] ? initial : intermediate;
    assert.equal(renderedLineSignature(paragraph), expected);
  }
  assert.equal(readyCount, 1);
  assert.equal(staleCount, 1);
});

test("responsiveMeasure_overshootOrReversalDiscardsPreparedMeasure", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const source = "反向 resize 或越过当前目标时不能提交旧方向的排版。".repeat(2);
  const markup = Array.from({ length: 10 }, () => `<p>${source}</p>`).join("");
  TiqianWeb.install();
  installTestAnimationFrames();

  const assertStaleAt = (currentWidth, reason) => {
    const root = mount(`<div data-tiqian-root='true' style='width: 320px'>${markup}</div>`);
    t.after(() => cleanupMounted());
    assert.equal(TiqianWeb.enhance(root, testOptions()), 10);
    const paragraphs = Array.from(root.querySelectorAll("p"));
    const initialChildren = paragraphs.map((paragraph) => {
      assert.ok(paragraph.firstChild);
      return paragraph.firstChild;
    });
    const initial = renderedLineSignature(paragraphs[0]);
    let readyCount = 0;
    let staleCount = 0;
    root.addEventListener("tiqian:relayout-ready", (event) => {
      readyCount += 1;
      if (relayoutEventIsStale(event)) staleCount += 1;
    });

    attachWorker(root);
    root.style.width = "180px";
    dispatchRelayout(root);
    grantWorkerSlice(root);
    root.style.width = currentWidth;
    runWorkerJobToCompletion(root);

    const committed = paragraphs.filter(
      (paragraph, index) => paragraph.firstChild !== initialChildren[index],
    ).length;
    assert.ok(committed >= 1 && committed < paragraphs.length, reason);
    for (const [index, paragraph] of paragraphs.entries()) {
      if (paragraph.firstChild === initialChildren[index]) {
        assert.equal(renderedLineSignature(paragraph), initial, reason);
      }
    }
    assert.equal(readyCount, 1);
    assert.equal(staleCount, 1);
  };

  assertStaleAt("240px", "prepared measure overshot the current target");
  assertStaleAt("360px", "viewport reversed past the previously committed measure");
});

test("responsiveMeasure_fractionalWidthCrossingGridBoundaryRelayouts", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const source = "小数宽度跨字格边界不能被像素容差吞掉。".repeat(20);
  const root = mount(`<div data-tiqian-root='true' style='width: 305.98px'><p>${source}</p></div>`);
  const options = testOptions({ fontSize: 15.3, lineHeight: 22.95 });
  TiqianWeb.install();
  assert.equal(TiqianWeb.enhance(root, options), 1);
  const paragraph = root.querySelector("p");
  const nineteenCells = renderedLineSignature(paragraph);

  installTestAnimationFrames();
  root.style.width = "306.02px";
  dispatchRelayout(root);
  flushAllTestAnimationFrames();

  assert.notEqual(
    nineteenCells,
    renderedLineSignature(paragraph),
    "19→20 cells is a real measure change even though the raw width delta is below 0.5px",
  );
});

test("responsiveMeasure_stableIssueParagraphUntouchedDuringRelayout", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(`
    <div data-tiqian-root="true" style="width: 320px">
      <p class="issue" style="font-size: 0px">零 advance 是稳定 capability issue。</p>
      <p class="plain" style="font-size: 18px; line-height: 30px">普通正文仍应走 off-DOM 响应式重排。</p>
    </div>
  `);
  const issueParagraph = root.querySelector("p.issue");
  const plainParagraph = root.querySelector("p.plain");
  const issueSourceChild = issueParagraph.firstChild;
  assert.ok(issueSourceChild);
  TiqianWeb.install();

  assert.equal(TiqianWeb.enhance(root), 1);
  assert.equal(
    issueParagraph.getAttribute("data-tiqian-capability-issue"),
    "WebEnhancementFailure",
  );
  const renderedChild = plainParagraph.firstChild;
  assert.ok(renderedChild);
  const initial = renderedLineSignature(plainParagraph);
  let relayoutReadyCount = 0;
  root.addEventListener("tiqian:relayout-ready", () => {
    relayoutReadyCount += 1;
  });

  installTestAnimationFrames();
  root.style.width = "120px";
  dispatchRelayout(root);

  assert.notEqual(
    plainParagraph.firstChild,
    renderedChild,
    "relayout must commit its first slice synchronously",
  );
  assert.equal(issueParagraph.firstChild, issueSourceChild);
  assert.equal(pendingTestAnimationFrameCount(), 0);

  flushAllTestAnimationFrames();

  assert.notEqual(initial, renderedLineSignature(plainParagraph));
  assert.equal(issueParagraph.firstChild, issueSourceChild);
  assert.equal(issueParagraph.getAttribute("data-tq-rendered"), null);
  assert.equal(
    issueParagraph.getAttribute("data-tiqian-capability-issue"),
    "WebEnhancementFailure",
  );
  assert.equal(root.getAttribute("data-tiqian-enhanced-count"), "1");
  assert.equal(relayoutReadyCount, 1);
});
