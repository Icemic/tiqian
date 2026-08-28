// Behavior tests for the content reconcile path. jsTest never covered this
// area, so these are new assertions derived from the runtime implementation:
// drift classification, raw-DOM backup restore, tainted engine edits, dead paragraph
// drops and stranded clone adoption all re-enter the layout pipeline through
// the tiqian:reconcile-content / tiqian:probe-content-drift events.

import assert from "node:assert/strict";
import test from "node:test";
import {
  attachWorker,
  cleanupMounted,
  copySelection,
  flushAllTestAnimationFrames,
  installTestAnimationFrames,
  loadHostRuntime,
  mount,
  probeContentDrift,
  reconcileContent,
  relayoutEventIsStale,
  renderedLineSignature,
  runWorkerJobToCompletion,
  testOptions,
} from "./runtime-host.js";
import type { FakeEvent } from "./runtime-host.js";
import type { FakeNode } from "./snapshot-dom-fixtures.js";

test("contentReconcile_idleWhenNoHostChangeHappened", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(`
    <div data-tiqian-root="true" style="width: 320px">
      <p style="font-size: 18px; line-height: 30px">宿主未做任何改动。</p>
    </div>
  `);
  assert.equal(TiqianWeb.enhance(root as unknown as Element, testOptions()), 1);

  assert.deepEqual(reconcileContent(root), {
    outcome: "idle",
    drifted: 0,
    rawDom: 0,
    tainted: 0,
    stranded: 0,
    dead: 0,
  });
  assert.deepEqual(probeContentDrift(root), {
    unknown: 0,
    drifted: 0,
    dead: 0,
    rawDom: 0,
  });
});

test("contentReconcile_probeClassifiesDriftWithoutMutating", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const unknownRoot = mount(`
    <div data-tiqian-root="true" style="width: 320px">
      <p>未增强的根按 unknown 报告。</p>
    </div>
  `);
  assert.deepEqual(probeContentDrift(unknownRoot), {
    unknown: 1,
    drifted: 0,
    dead: 0,
    rawDom: 0,
  });

  const root = mount(`
    <div data-tiqian-root="true" style="width: 320px">
      <p style="font-size: 18px; line-height: 30px">分类探针不得改动文档。</p>
    </div>
  `);
  assert.equal(TiqianWeb.enhance(root as unknown as Element, testOptions()), 1);
  const paragraph = root.querySelector("p")!;
  const renderedChild = paragraph.firstChild;
  assert.ok(renderedChild);

  paragraph.removeChild(renderedChild);
  const liveChildren = Array.from(paragraph.childNodes);

  assert.deepEqual(probeContentDrift(root), {
    unknown: 0,
    drifted: 1,
    dead: 0,
    rawDom: 0,
  });
  assert.deepEqual(
    Array.from(paragraph.childNodes),
    liveChildren,
    "probe must stay read-only",
  );

  const rawDomRoot = mount(`
    <div data-tiqian-root="true" style="width: 320px">
      <p style="font-size: 18px; line-height: 30px">宿主经转发写入托管。</p>
    </div>
  `);
  assert.equal(TiqianWeb.enhance(rawDomRoot as unknown as Element, testOptions()), 1);
  const rawDomParagraph = rawDomRoot.querySelector("p")!;
  rawDomParagraph.appendChild(
    globalThis.document.createTextNode("托管内新增") as unknown as FakeNode,
  );

  assert.deepEqual(probeContentDrift(rawDomRoot), {
    unknown: 0,
    drifted: 0,
    dead: 0,
    rawDom: 1,
  });
});

test("contentReconcile_hostEditReLowersSurvivingLiveContent", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const source = "宿主在活子节点前插入内容。";
  const root = mount(`
    <div data-tiqian-root="true" style="width: 320px">
      <p style="font-size: 18px; line-height: 30px">${source}</p>
    </div>
  `);
  assert.equal(TiqianWeb.enhance(root as unknown as Element, testOptions()), 1);
  const paragraph = root.querySelector("p")!;
  installTestAnimationFrames();

  paragraph.insertBefore(
    globalThis.document.createTextNode("宿主补充") as unknown as FakeNode,
    paragraph.firstChild,
  );

  assert.deepEqual(reconcileContent(root), {
    outcome: "work",
    drifted: 1,
    rawDom: 0,
    tainted: 0,
    stranded: 0,
    dead: 0,
  });
  flushAllTestAnimationFrames();

  assert.equal(paragraph.getAttribute("data-tq-rendered"), "true");
  assert.equal(root.getAttribute("data-tiqian-enhanced-count"), "1");
  const copied = copySelection(paragraph);
  assert.ok(copied.includes("宿主补充"), `re-lowered copy keeps host edit: ${copied}`);
  assert.ok(copied.includes(source), `re-lowered copy keeps surviving content: ${copied}`);
});

test("contentReconcile_rawDomEditRestoresAndRelowersFromRawDom", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const source = "托管里的语义真值要能回来。";
  const root = mount(`
    <div data-tiqian-root="true" style="width: 320px">
      <p style="font-size: 18px; line-height: 30px">${source}</p>
    </div>
  `);
  assert.equal(TiqianWeb.enhance(root as unknown as Element, testOptions()), 1);
  const paragraph = root.querySelector("p")!;
  installTestAnimationFrames();

  paragraph.appendChild(globalThis.document.createTextNode("托管内新增") as unknown as FakeNode);

  assert.deepEqual(reconcileContent(root), {
    outcome: "work",
    drifted: 0,
    rawDom: 1,
    tainted: 0,
    stranded: 0,
    dead: 0,
  });
  flushAllTestAnimationFrames();

  assert.equal(paragraph.getAttribute("data-tq-rendered"), "true");
  const copied = copySelection(paragraph);
  assert.ok(copied.includes(source), `rawDom restore keeps source truth: ${copied}`);
  assert.ok(copied.includes("托管内新增"), `rawDom restore keeps host edit: ${copied}`);
});

test("contentReconcile_taintedEngineEditRerendersFromRawDom", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const source = "原地改引擎输出会从托管重渲。";
  const root = mount(`
    <div data-tiqian-root="true" style="width: 320px">
      <p style="font-size: 18px; line-height: 30px">${source}</p>
    </div>
  `);
  assert.equal(TiqianWeb.enhance(root as unknown as Element, testOptions()), 1);
  const paragraph = root.querySelector("p")!;
  const engineText = (function findText(node: FakeNode): FakeNode | null {
    for (const child of node.childNodes) {
      if (child.nodeType === 3) return child;
      const nested = findText(child);
      if (nested) return nested;
    }
    return null;
  })(paragraph);
  assert.ok(engineText, "rendered output must contain a text node");
  installTestAnimationFrames();

  (engineText as unknown as { data: string }).data = "被改坏的文本";

  assert.deepEqual(reconcileContent(root, [paragraph]), {
    outcome: "work",
    drifted: 0,
    rawDom: 0,
    tainted: 1,
    stranded: 0,
    dead: 0,
  });
  flushAllTestAnimationFrames();

  assert.equal(paragraph.getAttribute("data-tq-rendered"), "true");
  assert.equal(copySelection(paragraph), source);
});

test("contentReconcile_deadParagraphDroppedAndFreshCloneAdopted", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const source = "框架重投影后克隆要被重新收养。";
  const root = mount(`
    <div data-tiqian-root="true" style="width: 320px">
      <p style="font-size: 18px; line-height: 30px">${source}</p>
    </div>
  `);
  assert.equal(TiqianWeb.enhance(root as unknown as Element, testOptions()), 1);
  const original = root.querySelector("p")!;
  installTestAnimationFrames();

  root.innerHTML = `<p style="font-size: 18px; line-height: 30px">${source}</p>`;
  assert.equal((original as unknown as Record<string, unknown>).isConnected, false);

  assert.deepEqual(reconcileContent(root), {
    outcome: "work",
    drifted: 0,
    rawDom: 0,
    tainted: 0,
    stranded: 1,
    dead: 1,
  });
  flushAllTestAnimationFrames();

  const clone = root.querySelector("p")!;
  assert.notEqual(clone, original);
  assert.equal(clone.getAttribute("data-tq-rendered"), "true");
  assert.equal(root.getAttribute("data-tiqian-enhanced-count"), "1");
  assert.equal(copySelection(clone), source);
});

test("contentReconcile_reprojectedCloneKeepsHardBreakThroughDescaffold", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(`
    <div data-tiqian-root="true" style="width: 320px">
      <p style="font-size: 18px; line-height: 30px">上半句<br>下半句</p>
    </div>
  `);
  assert.equal(TiqianWeb.enhance(root as unknown as Element, testOptions()), 1);
  const paragraph = root.querySelector("p")!;
  const hardBreak = paragraph.querySelector("[data-tq-hard-break]")!;
  assert.ok(hardBreak, "rendered output marks hard breaks");
  assert.equal(hardBreak.getAttribute("data-tq-src"), "\n");
  installTestAnimationFrames();

  root.innerHTML = root.innerHTML;

  assert.deepEqual(reconcileContent(root), {
    outcome: "work",
    drifted: 0,
    rawDom: 0,
    tainted: 0,
    stranded: 1,
    dead: 1,
  });
  flushAllTestAnimationFrames();

  const clone = root.querySelector("p")!;
  assert.equal(clone.getAttribute("data-tq-rendered"), "true");
  assert.equal(copySelection(clone), "上半句\n下半句");
});

test("contentReconcile_strandedCapabilityIssueIsNotRetried", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(`
    <div data-tiqian-root="true" style="width: 320px">
      <p style="font-size: 18px; line-height: 30px">已渲染段落照常工作。</p>
    </div>
  `);
  assert.equal(TiqianWeb.enhance(root as unknown as Element, testOptions()), 1);
  installTestAnimationFrames();

  const strandedIssue = globalThis.document.createElement("p");
  strandedIssue.setAttribute("data-tiqian-capability-issue", "InvalidWebShapingAdvance");
  strandedIssue.textContent = "带能力标记的段落不重试。";
  root.appendChild(strandedIssue as unknown as FakeNode);

  assert.deepEqual(reconcileContent(root), {
    outcome: "idle",
    drifted: 0,
    rawDom: 0,
    tainted: 0,
    stranded: 0,
    dead: 0,
  });
  flushAllTestAnimationFrames();
  assert.equal(strandedIssue.getAttribute("data-tq-rendered"), null);
  assert.equal(
    strandedIssue.getAttribute("data-tiqian-capability-issue"),
    "InvalidWebShapingAdvance",
  );

  const strandedRendered = globalThis.document.createElement("p");
  strandedRendered.setAttribute("data-tq-rendered", "true");
  strandedRendered.setAttribute("data-tiqian-capability-issue", "InvalidWebShapingAdvance");
  strandedRendered.textContent = "带渲染标记的克隆仍要收养。";
  root.appendChild(strandedRendered as unknown as FakeNode);

  assert.deepEqual(reconcileContent(root), {
    outcome: "work",
    drifted: 0,
    rawDom: 0,
    tainted: 0,
    stranded: 1,
    dead: 0,
  });
  flushAllTestAnimationFrames();

  assert.equal(strandedRendered.getAttribute("data-tq-rendered"), "true");
  assert.equal(strandedRendered.getAttribute("data-tiqian-capability-issue"), null);
  assert.equal(root.getAttribute("data-tiqian-enhanced-count"), "2");
});

test("contentReconcile_widthSnapshotStaleGuardAbortsReconcileJob", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const markup = Array.from(
    { length: 10 },
    () => `<p style="font-size: 18px; line-height: 30px">重排任务中途宽度变化要作废。</p>`,
  ).join("");
  const root = mount(`<div data-tiqian-root='true' style='width: 320px'>${markup}</div>`);
  assert.equal(TiqianWeb.enhance(root as unknown as Element, testOptions()), 10);
  const paragraphs = Array.from(root.querySelectorAll("p"));
  for (const paragraph of paragraphs) {
    paragraph.removeChild(paragraph.firstChild!);
  }
  let readyCount = 0;
  let staleCount = 0;
  (root as unknown as { addEventListener(type: string, listener: (e: FakeEvent) => void): void }).addEventListener("tiqian:relayout-ready", (event: FakeEvent) => {
    readyCount += 1;
    if (relayoutEventIsStale(event)) staleCount += 1;
  });

  installTestAnimationFrames();
  attachWorker(root);
  assert.deepEqual(reconcileContent(root), {
    outcome: "work",
    drifted: 10,
    rawDom: 0,
    tainted: 0,
    stranded: 0,
    dead: 0,
  });
  root.style.setProperty("width", "180px");
  runWorkerJobToCompletion(root);

  assert.equal(readyCount, 1);
  assert.equal(staleCount, 1);

  const initial = renderedLineSignature(paragraphs[0]);
  const finalRoot = mount(
    `<div data-tiqian-root='true' style='width: 180px'><p style="font-size: 18px; line-height: 30px">重排任务中途宽度变化要作废。</p></div>`,
  );
  t.after(cleanupMounted);
  TiqianWeb.enhance(finalRoot as unknown as Element, testOptions());
  const finalSignature = renderedLineSignature(finalRoot.querySelector("p")!);
  assert.notEqual(initial, finalSignature);

  for (const paragraph of paragraphs) {
    assert.equal(paragraph.getAttribute("data-tq-rendered"), "true");
  }
});
