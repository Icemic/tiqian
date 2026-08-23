import { strict as assert } from "node:assert";
import test from "node:test";

import {
  cleanupMounted,
  cssPx,
  loadHostRuntime,
  mount,
  testOptions,
} from "./runtime-host.mjs";

function dispatchEnhanceWithoutOptions(root) {
  globalThis.document.dispatchEvent(
    new globalThis.CustomEvent("tiqian:enhance", { detail: { root } }),
  );
}

function dispatchEnhanceWithStrongAsEmphasisMarks(root) {
  globalThis.document.dispatchEvent(
    new globalThis.CustomEvent("tiqian:enhance", {
      detail: { root, options: { strongAsEmphasisMarks: true } },
    }),
  );
}

test("eventChannel_jsOptionsMapStrongToEmphasisMarks", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(`
    <div data-tiqian-root="true" style="width: 320px">
      <p style="font-size: 18px; line-height: 30px">前<strong>强调</strong>后。</p>
    </div>
  `);
  TiqianWeb.install();

  dispatchEnhanceWithStrongAsEmphasisMarks(root);

  const paragraph = root.querySelector("p");
  assert.ok(paragraph.querySelector("strong[data-tq-cjk-emphasis]"));
  assert.equal(paragraph.querySelectorAll("circle").length, 2);
});

test("eventChannel_enhanceEventWithoutOptionsUsesComputedMetrics", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(`
    <div data-tiqian-root="true" style="width: 220px">
      <p style="font-size: 18px; line-height: 32px">无配置事件也必须继承宿主字号。</p>
    </div>
  `);
  TiqianWeb.install();

  dispatchEnhanceWithoutOptions(root);

  const paragraph = root.querySelector("p");
  const line = paragraph.querySelector(".tq-line");
  assert.ok(line);
  assert.equal(cssPx(line.style.getPropertyValue("--tq-line-height")), 32);
  assert.equal(paragraph.getAttribute("data-tiqian-capability-issue"), null);
});

test("eventChannel_enhanceAllFindsCustomElementRoots", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(`
    <tiqian-prose style="display: block; width: 220px">
      <p>命令式 API 也必须找到 custom element。</p>
    </tiqian-prose>
  `);

  assert.equal(TiqianWeb.enhanceAll(testOptions()), 1);
  assert.equal(root.getAttribute("data-tiqian-enhanced-count"), "1");
  assert.ok(root.querySelector(".tq-line"));
});
