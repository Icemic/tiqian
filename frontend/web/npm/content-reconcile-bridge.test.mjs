// Unit tests for the content-reconcile engine module installed by ts-runtime.
// npm-core/core/engine/content-reconcile.js installs __TiqianContentReconcile; these
// tests drive that global directly.

import assert from "node:assert/strict";
import test from "node:test";
import "@tiqian/prose-core/core/engine/content-reconcile.js";
import {
  cleanupMounted,
  loadHostRuntime,
  mount,
  testOptions,
} from "./runtime-host.mjs";

test("contentReconcileBridge_installedByRuntimeBoot", async () => {
  await loadHostRuntime();
  const bridge = globalThis.__TiqianContentReconcile;
  assert.ok(bridge, "runtime boot must install globalThis.__TiqianContentReconcile");
  for (const name of [
    "probeContentDrift",
    "classifyReconcile",
    "prepareTrackedParagraphForRelowering",
    "stripEngineMarkupFromStrandedParagraph",
  ]) {
    assert.equal(typeof bridge[name], "function", "missing bridge method: " + name);
  }
});

test("contentReconcileProbe_countsDeadDriftAndCustody", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const bridge = globalThis.__TiqianContentReconcile;

  const unenhancedRoot = mount(`
    <div data-tiqian-root="true" style="width: 320px">
      <p style="font-size: 18px; line-height: 30px">未增强根</p>
    </div>
  `);
  assert.equal(
    bridge.probeContentDrift([]),
    '{"unknown":0,"drifted":0,"dead":0,"custody":0}',
  );

  const enhancedRoot = mount(`
    <div data-tiqian-root="true" style="width: 320px">
      <p style="font-size: 18px; line-height: 30px">增强根后测试漂移</p>
    </div>
  `);
  assert.equal(TiqianWeb.enhance(enhancedRoot, testOptions()), 1);
  const paragraph = enhancedRoot.querySelector("p");
  const firstChild = paragraph.firstChild;
  assert.ok(firstChild);
  paragraph.removeChild(firstChild);

  const result = JSON.parse(bridge.probeContentDrift([paragraph]));
  assert.deepEqual(result, {
    unknown: 0,
    drifted: 1,
    dead: 0,
    custody: 0,
  });
});

test("contentReconcileProbe_staysReadOnly", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const bridge = globalThis.__TiqianContentReconcile;

  const root = mount(`
    <div data-tiqian-root="true" style="width: 320px">
      <p style="font-size: 18px; line-height: 30px">探针只读测试段落</p>
    </div>
  `);
  assert.equal(TiqianWeb.enhance(root, testOptions()), 1);
  const paragraph = root.querySelector("p");
  const beforeNodes = Array.from(paragraph.childNodes);

  bridge.probeContentDrift([paragraph]);

  const afterNodes = Array.from(paragraph.childNodes);
  assert.equal(afterNodes.length, beforeNodes.length);
  for (let i = 0; i < beforeNodes.length; i++) {
    assert.equal(afterNodes[i], beforeNodes[i]);
  }
});

test("contentReconcileClassify_jsonVerdicts", async (t) => {
  t.after(cleanupMounted);
  await loadHostRuntime();
  const bridge = globalThis.__TiqianContentReconcile;

  const emptySpec = {
    trackedSources: [],
    tainted: [],
    strandedCandidates: [],
    rootSelector: "tiqian-prose, [data-tiqian-root]",
  };
  const emptyVerdict = bridge.classifyReconcile(emptySpec);
  assert.equal(emptyVerdict.outcome, "idle");
  assert.deepEqual(emptyVerdict.drifted, []);
  assert.deepEqual(emptyVerdict.custody, []);
  assert.deepEqual(emptyVerdict.tainted, []);
  assert.deepEqual(emptyVerdict.stranded, []);
  assert.equal(emptyVerdict.dead, 0);
  assert.equal(
    emptyVerdict.json,
    '{"outcome":"idle","drifted":0,"custody":0,"tainted":0,"stranded":0,"dead":0}',
  );
  assert.deepEqual(JSON.parse(emptyVerdict.json), {
    outcome: "idle",
    drifted: 0,
    custody: 0,
    tainted: 0,
    stranded: 0,
    dead: 0,
  });

  const root = mount(`
    <div data-tiqian-root="true">
      <p id="p-stranded">滞留候选段落</p>
      <p id="p-skipped" data-tiqian-capability-issue="true">跳过候选段落</p>
    </div>
  `);
  const pStranded = root.querySelector("#p-stranded");
  const pSkipped = root.querySelector("#p-skipped");

  const spec = {
    trackedSources: [],
    tainted: [],
    strandedCandidates: [pStranded, pSkipped],
    rootSelector: "tiqian-prose, [data-tiqian-root]",
  };
  const verdict = bridge.classifyReconcile(spec);
  assert.equal(verdict.outcome, "work");
  assert.deepEqual(verdict.drifted, []);
  assert.deepEqual(verdict.custody, []);
  assert.deepEqual(verdict.tainted, []);
  assert.deepEqual(verdict.stranded, [pStranded]);
  assert.equal(verdict.dead, 0);
  assert.equal(
    verdict.json,
    '{"outcome":"work","drifted":0,"custody":0,"tainted":0,"stranded":1,"dead":0}',
  );
});

test("contentReconcilePrepare_restoresShellAndStamps", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const bridge = globalThis.__TiqianContentReconcile;
  const custody = globalThis.__TiqianCustody;

  const root = mount(`
    <div data-tiqian-root="true" style="width: 320px">
      <p style="font-size: 18px; line-height: 30px">准备重新 lowering 测试</p>
    </div>
  `);
  assert.equal(TiqianWeb.enhance(root, testOptions()), 1);
  const paragraph = root.querySelector("p");

  assert.ok(paragraph.firstChild);
  paragraph.removeChild(paragraph.firstChild);
  assert.equal(custody.renderedMatches(paragraph), false);

  bridge.prepareTrackedParagraphForRelowering(paragraph);
  assert.equal(paragraph.getAttribute("data-tq-rendered"), null);
  assert.equal(custody.renderedMatches(paragraph), true);
});

test("contentReconcileStrip_removesEngineMarkup", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const bridge = globalThis.__TiqianContentReconcile;

  const root = mount(`
    <div data-tiqian-root="true" style="width: 320px">
      <p style="font-size: 18px; line-height: 30px">第一行<br>第二行</p>
    </div>
  `);
  assert.equal(TiqianWeb.enhance(root, testOptions()), 1);
  root.innerHTML = root.innerHTML;
  const clonedParagraph = root.querySelector("p");
  assert.ok(clonedParagraph);

  bridge.stripEngineMarkupFromStrandedParagraph(clonedParagraph);

  assert.equal(clonedParagraph.querySelectorAll("[data-tq-hard-break]").length, 0);
  assert.ok(clonedParagraph.querySelector("br"), "bare br must exist");
  assert.equal(clonedParagraph.querySelectorAll("[data-tq-copy-ignore]").length, 0);
  assert.equal(clonedParagraph.querySelectorAll("[data-tq-engine-break]").length, 0);
  assert.equal(clonedParagraph.getAttribute("data-tq-rendered"), null);
});
