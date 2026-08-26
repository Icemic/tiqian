import assert from "node:assert/strict";
import test from "node:test";

import {
  needsCjkDashShaping,
  prepareCjkDashShapingIfNeeded,
} from "../core/engine/loaders/cjk-dash.js";
import { isLoadedSnapshotAdopted } from "../core/sampler/snapshot/loaded-snapshots.js";

test("plain roots do not load optional snapshot or dash modules", async () => {
  delete globalThis.__TiqianWebFontShaping;
  const root = { textContent: "普通中文正文。" };

  assert.equal(needsCjkDashShaping(root), false);
  assert.equal(isLoadedSnapshotAdopted(root), false);
  assert.deepEqual(await prepareCjkDashShapingIfNeeded(root), { status: "not-needed", detail: null });
  assert.equal(globalThis.__TiqianWebFontShaping, undefined);
});

test("dash detection covers paired and two-em source forms", () => {
  assert.equal(needsCjkDashShaping({ textContent: "甲——乙" }), true);
  assert.equal(needsCjkDashShaping({ textContent: "甲⸺乙" }), true);
  assert.equal(needsCjkDashShaping({ textContent: "甲—乙" }), false);
});

test("dash capability fails closed without loading browser HarfBuzz", async () => {
  delete globalThis.__TiqianWebFontShaping;

  assert.deepEqual(
    await prepareCjkDashShapingIfNeeded({ textContent: "甲——乙" }),
    {
      status: "unavailable",
      detail: "BrowserHarfBuzzDisabled",
    },
  );
  assert.equal(globalThis.__TiqianWebFontShaping, undefined);
});
