// Raw-DOM backup relayout and destruction tests ported from TiqianWebProgressiveRelayoutTest.kt.
// Verifies DOM recovery, cancellation guarantees, and rollback semantics under node --test.

import assert from "node:assert/strict";
import test from "node:test";
import {
  attachWorker,
  cleanupMounted,
  clearSnapshotFontSessionFixture,
  detachViaChannel,
  dispatchRelayout,
  drainMicrotasks,
  snapshotTestOptions,
  flushAllTestAnimationFrames,
  grantWorkerSlice,
  installSnapshotFontSessionFixture,
  loadHostRuntime,
  mount,
  pendingTestAnimationFrameCount,
  runWorkerJobToCompletion,
  setElementRect,
  testOptions,
} from "./runtime-host.mjs";

test("rawDom_destroyRestoresOriginalChildrenAndHostAttributes", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(
    '<div data-tiqian-root="true"><p data-tq-rendered="host-owned" data-tq-canonical-source="host-owned" data-tq-copy-ignore="host-owned">需要<strong>增强</strong>。</p></div>',
  );
  const paragraph = root.querySelector("p");
  const originalHtml = paragraph.innerHTML;

  assert.equal(TiqianWeb.enhance(root, testOptions()), 1);
  assert.equal(paragraph.getAttribute("data-tq-copy-ignore"), "host-owned");
  assert.equal(paragraph.getAttribute("data-tq-rendered"), "true");

  TiqianWeb.destroy(root);

  assert.equal(paragraph.innerHTML, originalHtml);
  assert.equal(paragraph.getAttribute("data-tq-copy-ignore"), "host-owned");
  assert.equal(paragraph.getAttribute("data-tq-rendered"), "host-owned");
  assert.equal(paragraph.getAttribute("data-tq-canonical-source"), "host-owned");
  assert.equal(paragraph.getAttribute("data-tq-runtime-render-font"), null);
  assert.equal(paragraph.getAttribute("style"), null);
});

test("rawDom_destroyCancelsPendingWorkBeforeTouchingContent", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(
    '<div data-tiqian-root="true"><p>渐进增强尚未执行时仍然是原生正文。</p></div>',
  );
  const paragraph = root.querySelector("p");
  const originalHtml = paragraph.innerHTML;

  attachWorker(root);
  TiqianWeb.enhanceProgressively(root, testOptions());
  assert.equal(root.getAttribute("data-tiqian-enhanced-count"), "0");

  TiqianWeb.destroy(root);

  assert.equal(paragraph.innerHTML, originalHtml);
  assert.equal(root.getAttribute("data-tiqian-enhanced"), null);
  assert.equal(paragraph.getAttribute("data-tq-rendered"), null);
});

test("rawDom_detachKeepsRenderedDomUntilDestroyRestoresSource", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  TiqianWeb.install();
  const root = mount(
    '<div data-tiqian-root="true"><p>路由移除旧文章时不应该同步重建这一段。</p></div>',
  );
  const paragraph = root.querySelector("p");
  const originalHtml = paragraph.innerHTML;

  assert.equal(TiqianWeb.enhance(root, testOptions()), 1);
  const renderedHtml = paragraph.innerHTML;
  assert.notEqual(paragraph.innerHTML, originalHtml);

  detachViaChannel(root);

  assert.equal(paragraph.innerHTML, renderedHtml);
  assert.equal(paragraph.getAttribute("data-tq-rendered"), "true");

  TiqianWeb.destroy(root);

  assert.equal(paragraph.innerHTML, originalHtml);
  assert.equal(paragraph.getAttribute("data-tq-rendered"), null);
});

test("rawDom_destroyCancelsScheduledTailBeforeTouchingParagraphs", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(
    '<div data-tiqian-root="true" style="width: 180px"><p>离视口很远的第一段保持原生。</p><p>离视口很远的第二段也保持原生。</p></div>',
  );
  const paragraphs = root.querySelectorAll("p");
  const originalHtml = paragraphs.map((paragraph) => paragraph.innerHTML);
  for (const paragraph of paragraphs) {
    setElementRect(paragraph, 1_000_000, 180);
  }

  attachWorker(root);
  TiqianWeb.enhanceProgressively(root, testOptions());
  grantWorkerSlice(root);
  assert.ok(root.querySelectorAll("p[data-tq-rendered='true']").length < 2);

  TiqianWeb.destroy(root);
  runWorkerJobToCompletion(root);

  paragraphs.forEach((paragraph, index) => {
    assert.equal(paragraph.innerHTML, originalHtml[index]);
    assert.equal(paragraph.getAttribute("data-tq-rendered"), null);
  });
});

test("rawDom_commitFailureRollsBackNodesAndCompletesJob", async (t) => {
  t.after(cleanupMounted);
  t.after(() => clearSnapshotFontSessionFixture());
  const TiqianWeb = await loadHostRuntime();
  installSnapshotFontSessionFixture({ failShaping: false });
  const root = mount(
    "<div data-tiqian-root='true' style='width: 220px'><p data-tq-snapshot-key='plain' style=\"font-family: 'Fixture CJK'; font-size: 18px; line-height: 30px\">原节点必须在异常后原样回来。</p></div>",
  );
  TiqianWeb.install();
  assert.equal(TiqianWeb.enhance(root, snapshotTestOptions()), 1);

  const paragraph = root.querySelector("p");
  const renderedChild = paragraph.firstChild;
  const renderedHtml = paragraph.innerHTML;
  const renderedStyle = paragraph.getAttribute("style");
  assert.ok(renderedChild != null);

  let errorCount = 0;
  let readyCount = 0;
  root.addEventListener("tiqian:relayout-error", () => {
    errorCount += 1;
  });
  root.addEventListener("tiqian:relayout-ready", () => {
    readyCount += 1;
  });

  // Corrupt the snapshot font session's shape evidence: the relayout
  // re-shapes through this session, and the hard error must fail the layout
  // job closed, roll the session back, and report through the root channel.
  installSnapshotFontSessionFixture({
    failShaping: false,
    corruptShapeError: "SnapshotSessionShapeTableCorrupted:fixture-shape-table",
  });
  root.style.width = "180px";
  dispatchRelayout(root);
  await drainMicrotasks();
  flushAllTestAnimationFrames();
  await drainMicrotasks();
  flushAllTestAnimationFrames();

  assert.ok(paragraph.firstChild === renderedChild);
  assert.equal(paragraph.innerHTML, renderedHtml);
  assert.equal(paragraph.getAttribute("style"), renderedStyle);
  assert.equal(paragraph.getAttribute("data-tq-canonical-plain"), "true");
  assert.equal(paragraph.getAttribute("data-tq-canonical-source"), "true");
  assert.ok(root.getAttribute("data-tiqian-relayout-error")?.includes("SnapshotSessionShapeTableCorrupted") === true);
  assert.equal(errorCount, 1);
  assert.equal(readyCount, 1);
  assert.equal(pendingTestAnimationFrameCount(), 0);

  installSnapshotFontSessionFixture({ failShaping: false });
  root.style.width = "140px";
  dispatchRelayout(root);
  await drainMicrotasks();
  flushAllTestAnimationFrames();
  await drainMicrotasks();
  flushAllTestAnimationFrames();

  assert.equal(root.getAttribute("data-tiqian-relayout-error"), null);
  assert.equal(readyCount, 2);
  assert.ok(paragraph.firstChild !== renderedChild);
});

test("rawDom_destroyCancelsInFlightRelayoutAndRollsBack", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const markup = Array.from({ length: 10 }, () => "<p>取消 resize job 后必须保持原生正文。</p>").join("");
  const root = mount("<div data-tiqian-root='true' style='width: 260px'>" + markup + "</div>");
  const paragraphs = root.querySelectorAll("p");
  const originalHtmls = paragraphs.map((paragraph) => paragraph.innerHTML);

  TiqianWeb.install();
  assert.equal(TiqianWeb.enhance(root, testOptions()), 10);

  attachWorker(root);
  root.style.width = "100px";
  dispatchRelayout(root);
  grantWorkerSlice(root);

  assert.equal(TiqianWeb.workerHasJob(root), true);

  TiqianWeb.destroy(root);

  assert.equal(TiqianWeb.workerHasJob(root), false);
  paragraphs.forEach((paragraph, index) => {
    assert.equal(paragraph.innerHTML, originalHtmls[index]);
    assert.equal(paragraph.getAttribute("data-tq-rendered"), null);
  });
  assert.equal(root.getAttribute("data-tiqian-enhanced"), null);
});
