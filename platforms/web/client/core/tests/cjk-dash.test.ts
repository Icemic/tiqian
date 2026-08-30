import assert from "node:assert/strict";
import test from "node:test";

import {
  needsCjkDashShaping,
  prepareCjkDashShapingIfNeeded,
} from "../src/engine/loaders/cjk-dash.js";
import { isLoadedSnapshotAdopted } from "../src/sampler/snapshot/loaded-snapshots.js";

interface TextBearingRoot {
  textContent: string | null;
}

type CjkDashOutcome = { status: string; detail: string | null };

type GlobalWithTiqian = typeof globalThis & {
  __TiqianWebFontShaping?: unknown;
};

const globalWithTiqian = globalThis as GlobalWithTiqian;

type GetAttributeFn = (name: string) => string | null;

interface FakeHTMLElement extends TextBearingRoot {
  getAttribute?: GetAttributeFn;
}

test("plain roots do not load optional snapshot or dash modules", async () => {
  delete globalWithTiqian.__TiqianWebFontShaping;
  const root: FakeHTMLElement = { textContent: "普通中文正文。" };

  assert.equal(needsCjkDashShaping(root), false);
  assert.equal(isLoadedSnapshotAdopted(root as HTMLElement), false);
  assert.deepEqual(await prepareCjkDashShapingIfNeeded(root), { status: "not-needed", detail: null });
  assert.equal(globalWithTiqian.__TiqianWebFontShaping, undefined);
});

test("dash detection covers paired and two-em source forms", () => {
  assert.equal(needsCjkDashShaping({ textContent: "甲——乙" }), true);
  assert.equal(needsCjkDashShaping({ textContent: "甲⸺乙" }), true);
  assert.equal(needsCjkDashShaping({ textContent: "甲—乙" }), false);
});

test("dash capability fails closed without loading browser HarfBuzz", async () => {
  delete globalWithTiqian.__TiqianWebFontShaping;

  assert.deepEqual(
    await prepareCjkDashShapingIfNeeded({ textContent: "甲——乙" }),
    {
      status: "unavailable",
      detail: "BrowserHarfBuzzDisabled",
    },
  );
  assert.equal(globalWithTiqian.__TiqianWebFontShaping, undefined);
});
