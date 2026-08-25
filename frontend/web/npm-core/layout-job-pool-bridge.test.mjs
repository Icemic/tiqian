// Unit tests for the layout-job-pool engine (npm/core/engine/layout-job-pool.js).
// The module exports a createLayoutJobPool() factory; each test drives one
// instance.

import assert from "node:assert/strict";
import test from "node:test";
import { createLayoutJobPool } from "./core/engine/layout-job-pool.js";

test("layoutJobPoolBridge_installedByScriptImport", () => {
  const engine = createLayoutJobPool();
  assert.ok(engine, "createLayoutJobPool must return the job engine");
  for (const name of [
    "startJob",
    "cancelJob",
    "runSlice",
    "hasJob",
    "jobGeneration",
    "jobKind",
    "pendingInTier",
    "paragraphCount",
    "paragraphAt",
    "setParagraphTier",
    "attach",
    "detach",
    "isAttached",
  ]) {
    assert.equal(typeof engine[name], "function", "missing bridge method: " + name);
  }
});

test("layoutJobPoolBridge_startJobRegistrationAndCancel", () => {
  const engine = createLayoutJobPool();
  const root = { id: "root-1" };
  assert.equal(engine.hasJob(root), false);
  assert.equal(engine.jobGeneration(root), 0);
  assert.equal(engine.jobKind(root), null);

  engine.attach(root);
  engine.startJob({
    root,
    kind: "Enhance",
    itemCount: 5,
    processItem: () => {},
    onFinished: () => {},
    onFailed: () => {},
    startedAt: 1000,
    coordinated: true,
  });

  assert.equal(engine.hasJob(root), true);
  const gen = engine.jobGeneration(root);
  assert.ok(gen > 0);
  assert.equal(engine.jobKind(root), "Enhance");

  engine.cancelJob(root);
  assert.equal(engine.hasJob(root), false);
  assert.equal(engine.jobGeneration(root), 0);
  assert.equal(engine.jobKind(root), null);
  assert.equal(engine.runSlice({ root, generation: gen, shouldStop: () => false }, 3), 0);
  engine.detach(root);
});

test("layoutJobPoolBridge_zeroItemCountFinishesImmediately", () => {
  const engine = createLayoutJobPool();
  const root = { id: "root-zero" };
  const events = [];
  let finishReport = null;

  engine.startJob({
    root,
    kind: "Relayout",
    itemCount: 0,
    processItem: () => { events.push("process"); },
    onItemsFinished: () => { events.push("onItemsFinished"); },
    onFinished: (report) => {
      events.push("onFinished");
      finishReport = report;
    },
    onFailed: () => { events.push("onFailed"); },
    startedAt: 2000,
    coordinated: false,
  });

  assert.deepEqual(events, ["onItemsFinished", "onFinished"]);
  assert.equal(engine.hasJob(root), false);
  assert.ok(finishReport);
  assert.equal(finishReport.kind, "Relayout");
  assert.equal(finishReport.startedAt, 2000);
  assert.equal(finishReport.stale, false);
});

test("layoutJobPoolBridge_uncoordinatedJobRunsToCompletionSynchronously", () => {
  const engine = createLayoutJobPool();
  const root = { id: "root-uncoordinated" };
  const processed = [];
  let progressCount = 0;
  let finished = false;
  let finishReport = null;

  engine.startJob({
    root,
    kind: "Enhance",
    itemCount: 3,
    processItem: (i) => { processed.push(i); },
    onProgress: () => { progressCount++; },
    onFinished: (report) => {
      finished = true;
      finishReport = report;
    },
    onFailed: () => {},
    startedAt: 3000,
    coordinated: false,
  });

  assert.deepEqual(processed, [0, 1, 2]);
  assert.ok(progressCount >= 1);
  assert.equal(finished, true);
  assert.equal(finishReport.stale, false);
  assert.equal(engine.hasJob(root), false);
});

test("layoutJobPoolBridge_coordinatedJobSlicesAndGenerationGuard", () => {
  const engine = createLayoutJobPool();
  const root = { id: "root-coord" };
  const processed = [];
  let finished = false;

  engine.attach(root);
  assert.equal(engine.isAttached(root), true);

  engine.startJob({
    root,
    kind: "Enhance",
    itemCount: 4,
    processItem: (i) => { processed.push(i); },
    onFinished: () => { finished = true; },
    onFailed: () => {},
    startedAt: 4000,
    coordinated: true,
  });

  assert.deepEqual(processed, []);
  assert.equal(finished, false);
  assert.equal(engine.hasJob(root), true);

  // Null controller returns 0
  assert.equal(engine.runSlice(null, 3), 0);

  // Mismatched generation returns 0
  const gen = engine.jobGeneration(root);
  assert.equal(engine.runSlice({ root, generation: gen + 999, shouldStop: () => false }, 3), 0);

  // shouldStop always true: processes exactly 1 item per slice
  const controller = { root, generation: gen, shouldStop: () => true };
  const count1 = engine.runSlice(controller, 3);
  assert.equal(count1, 1);
  assert.deepEqual(processed, [0]);
  assert.equal(finished, false);

  // Run remaining slices with shouldStop false
  const runAllController = { root, generation: gen, shouldStop: () => false };
  const count2 = engine.runSlice(runAllController, 3);
  assert.equal(count2, 3);
  assert.deepEqual(processed, [0, 1, 2, 3]);
  assert.equal(finished, true);
  assert.equal(engine.hasJob(root), false);

  engine.detach(root);
});

test("layoutJobPoolBridge_tierGatingAndPendingCounts", () => {
  const engine = createLayoutJobPool();
  const root = { id: "root-tier" };
  const processed = [];
  let finished = false;
  // 3 items in work order; itemTierIndex maps item index to doc-order index:
  // item 0 -> doc 2, item 1 -> doc 0, item 2 -> doc 1
  const itemTierIndex = [2, 0, 1];
  const paragraphsByDoc = [{ id: "p0" }, { id: "p1" }, { id: "p2" }];

  engine.attach(root);
  engine.startJob({
    root,
    kind: "Relayout",
    itemCount: 3,
    processItem: (i) => { processed.push(i); },
    onFinished: () => { finished = true; },
    onFailed: () => {},
    startedAt: 5000,
    itemTierIndex,
    paragraphsByDoc,
    coordinated: true,
  });

  assert.equal(engine.paragraphCount(root), 3);
  assert.equal(engine.paragraphAt(root, 0), paragraphsByDoc[0]);
  assert.equal(engine.paragraphAt(root, 1), paragraphsByDoc[1]);
  assert.equal(engine.paragraphAt(root, 2), paragraphsByDoc[2]);
  assert.equal(engine.paragraphAt(root, 3), null);

  // All 3 in tier 1 initially
  assert.equal(engine.pendingInTier(root, 1), 3);
  assert.equal(engine.pendingInTier(root, 2), 0);
  assert.equal(engine.pendingInTier(root, 3), 0);

  // Move doc 2 (which corresponds to item 0) to tier 2
  assert.equal(engine.setParagraphTier(root, 2, 2), true);
  assert.equal(engine.pendingInTier(root, 1), 2);
  assert.equal(engine.pendingInTier(root, 2), 1);
  assert.equal(engine.pendingInTier(root, 3), 0);

  const gen = engine.jobGeneration(root);
  // minTier = 1 slice: should skip item 0 (doc 2, tier 2) and process item 1 and 2 (doc 0 and 1, tier 1)
  const count1 = engine.runSlice({ root, generation: gen, shouldStop: () => false }, 1);
  assert.equal(count1, 2);
  assert.deepEqual(processed, [1, 2]);
  assert.equal(finished, false);
  // TierGatedItemKeepsJobOpen: job must stay open and nextIndex parked on item 0
  assert.equal(engine.hasJob(root), true);
  assert.equal(engine.pendingInTier(root, 1), 0);
  assert.equal(engine.pendingInTier(root, 2), 1);

  // Run minTier = 2 slice: processes remaining item 0
  const count2 = engine.runSlice({ root, generation: gen, shouldStop: () => false }, 2);
  assert.equal(count2, 1);
  assert.deepEqual(processed, [1, 2, 0]);
  assert.equal(finished, true);
  assert.equal(engine.hasJob(root), false);

  engine.detach(root);
});

test("layoutJobPoolBridge_staleMeasureGuardSkipsRemaining", () => {
  const engine = createLayoutJobPool();
  const root = { id: "root-stale" };
  const processed = [];
  let finished = false;
  let finishReport = null;
  let isStale = false;

  engine.attach(root);
  engine.startJob({
    root,
    kind: "Relayout",
    itemCount: 4,
    processItem: (i) => { processed.push(i); },
    onFinished: (report) => {
      finished = true;
      finishReport = report;
    },
    onFailed: () => {},
    isStale: () => isStale,
    startedAt: 6000,
    coordinated: true,
  });

  const gen = engine.jobGeneration(root);
  // Process 1 item in slice 1
  engine.runSlice({ root, generation: gen, shouldStop: () => true }, 3);
  assert.deepEqual(processed, [0]);
  assert.equal(finished, false);

  // Mark stale before slice 2
  isStale = true;
  engine.runSlice({ root, generation: gen, shouldStop: () => false }, 3);

  // Remaining items skipped, processItem not called for 1, 2, 3
  assert.deepEqual(processed, [0]);
  assert.equal(finished, true);
  assert.equal(finishReport.stale, true);
  assert.equal(engine.hasJob(root), false);

  engine.detach(root);
});

test("layoutJobPoolBridge_processItemErrorTriggersOnFailureAndOnFailed", () => {
  const engine = createLayoutJobPool();
  const root = { id: "root-error" };
  const events = [];
  let failurePayload = null;

  engine.attach(root);
  engine.startJob({
    root,
    kind: "Enhance",
    itemCount: 3,
    processItem: (i) => {
      if (i === 1) throw new Error("Item boom");
    },
    onFailure: () => { events.push("onFailure"); },
    onFinished: () => { events.push("onFinished"); },
    onFailed: (failure) => {
      events.push("onFailed");
      failurePayload = failure;
    },
    startedAt: 7000,
    coordinated: true,
  });

  const gen = engine.jobGeneration(root);
  const processed = engine.runSlice({ root, generation: gen, shouldStop: () => false }, 3);

  assert.equal(processed, 1); // 1 item succeeded before error on item 1
  assert.deepEqual(events, ["onFailure", "onFailed"]);
  assert.ok(failurePayload);
  assert.equal(failurePayload.kind, "Enhance");
  assert.equal(failurePayload.detail, "Item boom");
  assert.equal(failurePayload.startedAt, 7000);
  assert.equal(engine.hasJob(root), false);

  engine.detach(root);
});

test("layoutJobPoolBridge_attachAndDetachStateTransitions", () => {
  const engine = createLayoutJobPool();
  const root = { id: "root-detach" };
  const processed = [];
  let finished = false;

  engine.attach(root);
  engine.startJob({
    root,
    kind: "Enhance",
    itemCount: 3,
    processItem: (i) => { processed.push(i); },
    onFinished: () => { finished = true; },
    onFailed: () => {},
    startedAt: 8000,
    coordinated: true,
  });

  const gen = engine.jobGeneration(root);
  // Run 1 item
  engine.runSlice({ root, generation: gen, shouldStop: () => true }, 3);
  assert.deepEqual(processed, [0]);
  assert.equal(finished, false);

  // Detaching root immediately runs remaining items to completion (RunToCompletionWithoutCoordinator)
  engine.detach(root);
  assert.deepEqual(processed, [0, 1, 2]);
  assert.equal(finished, true);
  assert.equal(engine.hasJob(root), false);
  assert.equal(engine.isAttached(root), false);
});