// Progressive relayout job tests ported from TiqianWebProgressiveRelayoutTest.kt
// and TiqianWebSourceFidelityTest.kt. Verifies the job state machine, tier
// gating, stale-measure guards, and width-dependent capability retry under
// node --test.

import assert from "node:assert/strict";
import test from "node:test";
import {
  attachWorker,
  cleanupMounted,
  dispatchRelayout,
  dispatchTestProgressiveScroll,
  eventDetailInt,
  FakeEvent,
  grantUnboundedSlice,
  grantWorkerSlice,
  HostElement,
  loadHostRuntime,
  mount,
  pendingTestAnimationFrameCount,
  flushAllTestAnimationFrames,
  relayoutEventIsStale,
  renderedLineSignature,
  runWorkerJobToCompletion,
  setElementRect,
  testOptions,
} from "./runtime-host.js";

test("layoutJobPool_mixedSnapshotProgressReportsObservableTotal", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(`
    <div data-tiqian-root="true" data-tiqian-snapshot-count="2">
      <p>只有这一段需要运行时补齐。</p>
    </div>
  `);
  let readyEnhancedCount = -1;
  let readyRuntimeCount = -1;
  let readySnapshotCount = -1;
  (root as unknown as HostElement).addEventListener("tiqian:ready", (event: FakeEvent) => {
    readyEnhancedCount = eventDetailInt(event, "enhancedCount");
    readyRuntimeCount = eventDetailInt(event, "runtimeEnhancedCount");
    readySnapshotCount = eventDetailInt(event, "snapshotCount");
  });
  attachWorker(root);

  TiqianWeb.enhanceProgressively(root as unknown as Element, testOptions());

  assert.equal(root.getAttribute("data-tiqian-enhanced-count"), "2");
  runWorkerJobToCompletion(root);
  assert.equal(root.getAttribute("data-tiqian-enhanced-count"), "3");
  assert.equal(readyEnhancedCount, 3);
  assert.equal(readyRuntimeCount, 1);
  assert.equal(readySnapshotCount, 2);

  TiqianWeb.destroy(root as unknown as Element);
  assert.equal(root.getAttribute("data-tiqian-enhanced"), "true");
  assert.equal(root.getAttribute("data-tiqian-enhanced-count"), "2");
});

test("layoutJobPool_longJobCommitsParagraphsAtomicallyAcrossFrames", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const markup = Array.from({ length: 18 }, (_, index) =>
    `<p>第${index}段在自己的准备帧中原子切换。</p>`,
  ).join("");
  const root = mount(`<div data-tiqian-root='true' style='width: 180px'>${markup}</div>`);
  const paragraphs = root.querySelectorAll("p");
  const sourceChildren = paragraphs.map((paragraph) => {
    assert.ok(paragraph.firstChild);
    return paragraph.firstChild;
  });
  let readyCount = 0;
  let stale = false;
  (root as unknown as HostElement).addEventListener("tiqian:ready", (event: FakeEvent) => {
    readyCount += 1;
    stale = relayoutEventIsStale(event);
  });
  attachWorker(root);

  TiqianWeb.enhanceProgressively(root as unknown as Element, testOptions());

  let progressiveSlices = 0;
  let previousRenderedCount = 0;
  while (TiqianWeb.workerHasJob(root as unknown as Element)) {
    grantWorkerSlice(root);
    const renderedCount = root.querySelectorAll("p[data-tq-rendered='true']").length;
    assert.ok(renderedCount >= previousRenderedCount);
    assert.ok(paragraphs.every((paragraph, index) =>
      paragraph.firstChild === sourceChildren[index] ||
      paragraph.getAttribute("data-tq-rendered") === "true",
    ), "each paragraph must be either intact source or a complete Tiqian result");
    if (TiqianWeb.workerHasJob(root as unknown as Element)) {
      progressiveSlices += 1;
      assert.ok(renderedCount >= 1 && renderedCount < paragraphs.length);
      assert.equal(root.getAttribute("data-tiqian-enhanced-count"), String(renderedCount));
      assert.equal(readyCount, 0);
    }
    previousRenderedCount = renderedCount;
  }

  assert.ok(progressiveSlices >= 2);
  assert.ok(paragraphs.every((p, i) => p.firstChild !== sourceChildren[i]));
  assert.equal(root.getAttribute("data-tiqian-enhanced-count"), "18");
  assert.equal(readyCount, 1);
  assert.equal(stale, false);
});

test("layoutJobPool_viewportParagraphsCommitFirst", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const markup = Array.from({ length: 18 }, (_, index) =>
    `<p>第${index}段用于验证视口优先顺序。</p>`,
  ).join("");
  const root = mount(`<div data-tiqian-root='true' style='width: 180px'>${markup}</div>`);
  const paragraphs = root.querySelectorAll("p");
  paragraphs.forEach((paragraph, index) => {
    setElementRect(paragraph, 1_000_000 - index * 1_000, 180);
  });
  setElementRect(paragraphs[paragraphs.length - 1], 0, 180);
  attachWorker(root);

  TiqianWeb.enhanceProgressively(root as unknown as Element, testOptions());
  grantWorkerSlice(root);

  assert.equal(paragraphs[paragraphs.length - 1].getAttribute("data-tq-rendered"), "true");
  assert.ok(root.querySelectorAll("p[data-tq-rendered='true']").length < paragraphs.length);
  runWorkerJobToCompletion(root);
  assert.equal(root.querySelectorAll("p[data-tq-rendered='true']").length, 18);
});

test("layoutJobPool_handledScrollDoesNotDelayCommits", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(`
    <div data-tiqian-root="true" style="width: 180px">
      <p>已处理的滚动不能再人为冻结可见段落提交。</p>
    </div>
  `);
  const paragraph = root.querySelector("p")!;
  setElementRect(paragraph, 0, 180);
  attachWorker(root);

  TiqianWeb.enhanceProgressively(root as unknown as Element, testOptions());
  dispatchTestProgressiveScroll();
  grantWorkerSlice(root);

  assert.equal(paragraph.getAttribute("data-tq-rendered"), "true");
});

test("layoutJobPool_staleFinishPreservesCommittedParagraphs", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const markup = Array.from({ length: 18 }, (_, index) =>
    `<p>第${index}段不能把旧宽度结果混入同一次整批提交。</p>`,
  ).join("");
  const root = mount(`<div data-tiqian-root='true' style='width: 320px'>${markup}</div>`);
  const paragraphs = root.querySelectorAll("p");
  const sourceChildren = paragraphs.map((paragraph) => {
    assert.ok(paragraph.firstChild);
    return paragraph.firstChild;
  });
  let readyCount = 0;
  let stale = false;
  (root as unknown as HostElement).addEventListener("tiqian:ready", (event: FakeEvent) => {
    readyCount += 1;
    stale = relayoutEventIsStale(event);
  });
  attachWorker(root);

  TiqianWeb.enhanceProgressively(root as unknown as Element, testOptions());
  grantWorkerSlice(root);
  root.style.setProperty("width", "120px");
  runWorkerJobToCompletion(root);

  const renderedCount = root.querySelectorAll("p[data-tq-rendered='true']").length;
  assert.ok(renderedCount >= 1 && renderedCount < 18);
  assert.ok(paragraphs.every((p, i) =>
    p.getAttribute("data-tq-rendered") === "true" ||
    p.firstChild === sourceChildren[i],
  ));
  assert.equal(readyCount, 1);
  assert.equal(stale, true);

  TiqianWeb.enhanceProgressively(root as unknown as Element, testOptions());
  runWorkerJobToCompletion(root);

  assert.equal(root.querySelectorAll("p[data-tq-rendered='true']").length, 18);
  assert.equal(readyCount, 2);
  assert.equal(stale, false);
});

test("layoutJobPool_relayoutDuringInitialWorkRestartsCleanly", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(`
    <div data-tiqian-root="true" style="width: 240px">
      <p>第一段必须在重启后增强。</p>
      <p>第二段也不能被旧 job 遗漏。</p>
    </div>
  `);
  let readyCount = 0;
  (root as unknown as HostElement).addEventListener("tiqian:ready", () => {
    readyCount += 1;
  });
  TiqianWeb.install();
  attachWorker(root);

  TiqianWeb.enhanceProgressively(root as unknown as Element, testOptions());
  root.style.setProperty("width", "120px");
  dispatchRelayout(root);

  assert.equal(root.getAttribute("data-tiqian-enhanced-count"), "0");

  runWorkerJobToCompletion(root);

  assert.equal(root.querySelectorAll("p[data-tq-rendered='true']").length, 2);
  assert.equal(root.getAttribute("data-tiqian-enhanced-count"), "2");
  assert.equal(readyCount, 1);
});

test("layoutJobPool_newerRelayoutSupersedesPendingWork", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const source = "连续 resize 只应提交最新宽度的分帧重排结果。".repeat(4);
  const markup = Array.from({ length: 10 }, () => `<p>${source}</p>`).join("");
  const root = mount(`<div data-tiqian-root='true' style='width: 320px'>${markup}</div>`);
  const expectedRoot = mount(`<div data-tiqian-root='true' style='width: 100px'>${markup}</div>`);
  TiqianWeb.install();
  assert.equal(TiqianWeb.enhance(root as unknown as Element, testOptions()), 10);
  assert.equal(TiqianWeb.enhance(expectedRoot as unknown as Element, testOptions()), 10);
  const paragraphs = root.querySelectorAll("p");
  const initialChildren = paragraphs.map((paragraph) => {
    assert.ok(paragraph.firstChild);
    return paragraph.firstChild;
  });
  const initial = renderedLineSignature(paragraphs[0]);
  const expected = renderedLineSignature(expectedRoot.querySelector("p")!);
  assert.notEqual(initial, expected);
  let relayoutReadyCount = 0;
  (root as unknown as HostElement).addEventListener("tiqian:relayout-ready", () => {
    relayoutReadyCount += 1;
  });

  attachWorker(root);
  root.style.setProperty("width", "180px");
  dispatchRelayout(root);
  grantWorkerSlice(root);
  root.style.setProperty("width", "100px");
  dispatchRelayout(root);

  const replacedAtLatestWidth = paragraphs.filter((p, i) => p.firstChild !== initialChildren[i]).length;
  assert.ok(replacedAtLatestWidth >= 1 && replacedAtLatestWidth < paragraphs.length);

  runWorkerJobToCompletion(root);

  for (const paragraph of paragraphs) {
    assert.equal(renderedLineSignature(paragraph), expected);
  }
  assert.equal(relayoutReadyCount, 1);
});

test("layoutJobPool_relayoutSwapsDomAtomicallyWithoutFrameDelay", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(
    "<div data-tiqian-root='true' style='width: 320px'>" +
      "<p>第一段在原子替换中直接换上新排版。</p>" +
      "<p>第二段也在同一个分片里完成交换。</p>" +
    "</div>",
  );
  TiqianWeb.install();
  assert.equal(TiqianWeb.enhance(root as unknown as Element, testOptions()), 2);
  const first = root.querySelectorAll("p")[0];
  const second = root.querySelectorAll("p")[1];
  const firstRenderedChild = first.firstChild;
  const secondRenderedChild = second.firstChild;
  assert.ok(firstRenderedChild != null);
  assert.ok(secondRenderedChild != null);
  let relayoutReadyCount = 0;
  (root as unknown as HostElement).addEventListener("tiqian:relayout-ready", () => {
    relayoutReadyCount += 1;
  });

  root.style.setProperty("width", "120px");
  dispatchRelayout(root);

  assert.ok(first.firstChild !== firstRenderedChild);
  assert.ok(second.firstChild !== secondRenderedChild);
  assert.equal(pendingTestAnimationFrameCount(), 0);
  assert.equal(relayoutReadyCount, 1);

  flushAllTestAnimationFrames();

  assert.ok(first.firstChild !== firstRenderedChild);
  assert.ok(second.firstChild !== secondRenderedChild);
  assert.equal(relayoutReadyCount, 1);
});

test("layoutJobPool_longRelayoutYieldsBetweenAtomicCommits", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const markup = Array.from({ length: 18 }, (_, index) =>
    `<p>第${index}段在分帧提交时必须一直保持上一份提椠排版。</p>`,
  ).join("");
  const root = mount(`<div data-tiqian-root='true' style='width: 320px'>${markup}</div>`);
  TiqianWeb.install();
  assert.equal(TiqianWeb.enhance(root as unknown as Element, testOptions()), 18);
  const paragraphs = root.querySelectorAll("p");
  const previousChildren = paragraphs.map((paragraph) => {
    assert.ok(paragraph.firstChild);
    return paragraph.firstChild;
  });
  let relayoutReadyCount = 0;
  (root as unknown as HostElement).addEventListener("tiqian:relayout-ready", () => {
    relayoutReadyCount += 1;
  });

  attachWorker(root);
  root.style.setProperty("width", "120px");
  dispatchRelayout(root);
  grantWorkerSlice(root);

  const committedBeforeAnyFrame = paragraphs.filter((p, i) => p.firstChild !== previousChildren[i]).length;
  assert.ok(committedBeforeAnyFrame >= 1 && committedBeforeAnyFrame < paragraphs.length);

  let progressiveSlices = 0;
  let previousUpdatedCount = committedBeforeAnyFrame;
  while (TiqianWeb.workerHasJob(root as unknown as Element)) {
    grantWorkerSlice(root);
    const updatedCount = paragraphs.filter((p, i) => p.firstChild !== previousChildren[i]).length;
    assert.ok(updatedCount >= previousUpdatedCount);
    if (TiqianWeb.workerHasJob(root as unknown as Element)) {
      progressiveSlices += 1;
      assert.ok(updatedCount >= 1 && updatedCount < paragraphs.length);
      assert.equal(relayoutReadyCount, 0);
    }
    previousUpdatedCount = updatedCount;
  }

  assert.ok(progressiveSlices >= 1, "a long root must still yield during relayout");
  assert.ok(paragraphs.every((p, i) => p.firstChild !== previousChildren[i]));
  assert.equal(relayoutReadyCount, 1);
});

test("layoutJobPool_tierGatedParagraphKeepsJobOpen", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const source = "拖动经过窄区后回宽，被门槛挡住的段落不能被当作完成遗弃。".repeat(3);
  const markup = Array.from({ length: 3 }, () => `<p>${source}</p>`).join("");
  const root = mount(`<div data-tiqian-root='true' style='width: 320px'>${markup}</div>`);
  TiqianWeb.install();
  assert.equal(TiqianWeb.enhance(root as unknown as Element, testOptions()), 3);
  const paragraphs = root.querySelectorAll("p");
  const wideSignatures = paragraphs.map((p) => renderedLineSignature(p));
  let relayoutReadyCount = 0;
  let staleReadyCount = 0;
  (root as unknown as HostElement).addEventListener("tiqian:relayout-ready", (event: FakeEvent) => {
    relayoutReadyCount += 1;
    if (relayoutEventIsStale(event)) staleReadyCount += 1;
  });

  attachWorker(root);

  root.style.setProperty("width", "120px");
  dispatchRelayout(root);
  runWorkerJobToCompletion(root);
  assert.equal(relayoutReadyCount, 1);
  const narrowChildren = paragraphs.map((p) => {
    assert.ok(p.firstChild);
    return p.firstChild;
  });
  assert.ok(paragraphs.every((p, i) => renderedLineSignature(p) !== wideSignatures[i]));

  root.style.setProperty("width", "320px");
  dispatchRelayout(root);
  assert.equal(TiqianWeb.workerSetParagraphTier(root as unknown as Element, 1, 3), true);

  const committed = grantUnboundedSlice(root, 1);
  assert.equal(committed, 2);

  assert.ok(
    TiqianWeb.workerHasJob(root as unknown as Element),
    "a tier-gated paragraph must keep its job open instead of being abandoned as finished",
  );
  assert.equal(staleReadyCount, 0);
  assert.equal(relayoutReadyCount, 1);
  assert.equal(TiqianWeb.workerPendingInTier(root as unknown as Element, 3), 1);
  assert.ok(paragraphs[1].firstChild === narrowChildren[1]);
  assert.ok(paragraphs[0].firstChild !== narrowChildren[0]);
  assert.ok(paragraphs[2].firstChild !== narrowChildren[2]);

  grantUnboundedSlice(root, 3);
  assert.equal(TiqianWeb.workerHasJob(root as unknown as Element), false);
  assert.equal(relayoutReadyCount, 2);
  assert.equal(staleReadyCount, 0);
  assert.ok(paragraphs[1].firstChild !== narrowChildren[1]);
  assert.equal(renderedLineSignature(paragraphs[1]), wideSignatures[1]);
  assert.ok(paragraphs.every((p, i) => renderedLineSignature(p) === wideSignatures[i]));
});

test("layoutJobPool_widthDependentCapabilityRetryRestartsFromNative", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(`
    <div data-tiqian-root="true" style="width: 520px">
      <p class="clone"><span style="box-decoration-break: clone; -webkit-box-decoration-break: clone; padding: 0 6px">跨行复制盒模型只在窄行失去保真能力</span></p>
      <p class="plain">普通段落在 capability retry 时不能跨帧暴露原生正文。</p>
    </div>
  `);
  const cloneParagraph = root.querySelector("p.clone")!;
  const plainParagraph = root.querySelector("p.plain")!;
  const originalHtml = cloneParagraph.innerHTML;
  let relayoutReadyCount = 0;
  (root as unknown as HostElement).addEventListener("tiqian:relayout-ready", () => {
    relayoutReadyCount += 1;
  });
  TiqianWeb.install();

  assert.equal(TiqianWeb.enhance(root as unknown as Element, testOptions()), 2);
  assert.equal(cloneParagraph.getAttribute("data-tq-rendered"), "true");
  assert.equal(plainParagraph.getAttribute("data-tq-rendered"), "true");

  attachWorker(root);
  root.style.setProperty("width", "90px");
  dispatchRelayout(root);
  runWorkerJobToCompletion(root);

  assert.equal(cloneParagraph.innerHTML, originalHtml);
  assert.equal(cloneParagraph.getAttribute("data-tq-rendered"), null);
  assert.equal(
    cloneParagraph.getAttribute("data-tiqian-capability-issue"),
    "InlineCloneDecorationBreakUnsupported",
  );
  assert.equal(plainParagraph.getAttribute("data-tq-rendered"), "true");
  assert.equal(root.getAttribute("data-tiqian-enhanced-count"), "1");
  assert.equal(relayoutReadyCount, 1);
  const narrowRenderedChild = plainParagraph.firstChild;
  assert.ok(narrowRenderedChild != null);

  root.style.setProperty("width", "520px");
  dispatchRelayout(root);

  assert.equal(relayoutReadyCount, 1);
  assert.equal(cloneParagraph.getAttribute("data-tq-rendered"), null);
  assert.equal(plainParagraph.getAttribute("data-tq-rendered"), null);
  assert.equal(cloneParagraph.innerHTML, originalHtml);
  assert.ok(plainParagraph.firstChild !== narrowRenderedChild);
  assert.equal(root.getAttribute("data-tiqian-enhanced-count"), "0");

  runWorkerJobToCompletion(root);

  assert.equal(relayoutReadyCount, 2);
  assert.equal(cloneParagraph.getAttribute("data-tq-rendered"), "true");
  assert.equal(plainParagraph.getAttribute("data-tq-rendered"), "true");
  assert.equal(cloneParagraph.getAttribute("data-tiqian-capability-issue"), null);
  assert.equal(root.getAttribute("data-tiqian-enhanced-count"), "2");
});
