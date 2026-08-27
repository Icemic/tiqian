// Engine API tests ported from the tiqian:enhance event-channel tests in
// TiqianWebEnhancerTest.kt. ADR 0053 C1 replaced the document event channel
// with the TiqianEngine JsExport facade, so the options bag, the no-options
// defaulting, and the imperative root scan are exercised through direct
// engine calls.
import { strict as assert } from "node:assert";
import test from "node:test";

import {
  cleanupMounted,
  cssPx,
  loadHostRuntime,
  mount,
} from "./runtime-host.mjs";

test("engineApi_jsOptionsMapStrongToEmphasisMarks", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(`
    <div data-tiqian-root="true" style="width: 320px">
      <p style="font-size: 18px; line-height: 30px">前<strong>强调</strong>后。</p>
    </div>
  `);

  TiqianWeb.enhance(root, { strongAsEmphasisMarks: true });

  const paragraph = root.querySelector("p");
  assert.ok(paragraph.querySelector("strong[data-tq-cjk-emphasis]"));
  assert.equal(paragraph.querySelectorAll("circle").length, 2);
});

test("engineApi_enhanceWithoutOptionsUsesComputedMetrics", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(`
    <div data-tiqian-root="true" style="width: 220px">
      <p style="font-size: 18px; line-height: 32px">无配置调用也必须继承宿主字号。</p>
    </div>
  `);

  TiqianWeb.enhance(root);

  const paragraph = root.querySelector("p");
  const line = paragraph.querySelector(".tq-line");
  assert.ok(line);
  assert.equal(cssPx(line.style.getPropertyValue("--tq-line-height")), 32);
  assert.equal(paragraph.getAttribute("data-tiqian-capability-issue"), null);
});

test("engineApi_enhanceFindsCustomElementRoots", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(`
    <tiqian-prose style="display: block; width: 220px">
      <p>命令式 API 也必须找到 custom element。</p>
    </tiqian-prose>
  `);

  TiqianWeb.enhance(root);
  assert.equal(root.getAttribute("data-tiqian-enhanced-count"), "1");
  assert.ok(root.querySelector(".tq-line"));
});
