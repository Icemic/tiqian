// Unit tests for the layout-job-pool engine (npm/core/engine/layout-job-pool.js).
// The module exports a createLayoutJobPool() factory; each test drives one
// instance.

import assert from "node:assert/strict";
import test from "node:test";
import { createLayoutJobPool } from "../core/engine/layout-job-pool.js";
import type { LayoutJobPool, LayoutJobSpec, LayoutJobFinishReport, LayoutJobFailureReport } from "../core/engine/layout-job-pool.js";

interface TestRoot {
  id: string;
}

interface ProcessedTracker {
  processed: number[];
  finished: boolean;
  finishReport: LayoutJobFinishReport | null;
  failurePayload: LayoutJobFailureReport | null;
  events: string[];
  progressCount: number;
}

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
    assert.equal(typeof engine[name as keyof LayoutJobPool], "function", "missing bridge method: " + name);
  }
});

test("layoutJobPoolBridge_startJobRegistrationAndCancel", () => {
  const engine = createLayoutJobPool();
  const rootStub: TestRoot = { id: "root-1" };
  const root = rootStub as TestRoot & Element;
  assert.equal(engine.hasJob(root), false);
  assert.equal(engine.jobGeneration(root), 0);
  assert.equal(engine.jobKind(root), null);

  engine.attach(root);
  engine.startJob({
    root,
    kind: "Enhance",
    itemCount: 5,
    processItem: (): void => {},
    onFinished: (): void => {},
    onFailed: (): void => {},
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
  const rootAsHt = root as TestRoot & HTMLElement;
  assert.equal(engine.runSlice({ root: rootAsHt, generation: gen, shouldStop: (): boolean => false, admissionClass: "grant", deadline: Date.now(), quota: 16 }, 3), 0);
  engine.detach(root);
});

test("layoutJobPoolBridge_zeroItemCountFinishesImmediately", () => {
  const engine = createLayoutJobPool();
  const rootStub: TestRoot = { id: "root-zero" };
  const root = rootStub as TestRoot & Element;
  const events: string[] = [];
  let finishReport: LayoutJobFinishReport | null = null;

  engine.startJob({
    root,
    kind: "Relayout",
    itemCount: 0,
    processItem: (): void => { events.push("process"); },
    onItemsFinished: (): void => { events.push("onItemsFinished"); },
    onFinished: (report: LayoutJobFinishReport): void => {
      events.push("onFinished");
      finishReport = report;
    },
    onFailed: (): void => { events.push("onFailed"); },
    startedAt: 2000,
    coordinated: false,
  });

  assert.deepEqual(events, ["onItemsFinished", "onFinished"]);
  assert.equal(engine.hasJob(root), false);
  assert.ok(finishReport);
  const report = finishReport as LayoutJobFinishReport;
  assert.equal(report.kind, "Relayout");
  assert.equal(report.startedAt, 2000);
  assert.equal(report.stale, false);
});

test("layoutJobPoolBridge_uncoordinatedJobRunsToCompletionSynchronously", () => {
  const engine = createLayoutJobPool();
  const rootStub: TestRoot = { id: "root-uncoordinated" };
  const root = rootStub as TestRoot & Element;
  const tracker: ProcessedTracker = {
    processed: [],
    finished: false,
    finishReport: null,
    failurePayload: null,
    events: [],
    progressCount: 0,
  };

  engine.startJob({
    root,
    kind: "Enhance",
    itemCount: 3,
    processItem: (i: number): void => { tracker.processed.push(i); },
    onProgress: (): void => { tracker.progressCount++; },
    onFinished: (report: LayoutJobFinishReport): void => {
      tracker.finished = true;
      tracker.finishReport = report;
    },
    onFailed: (): void => {},
    startedAt: 3000,
    coordinated: false,
  });

  assert.deepEqual(tracker.processed, [0, 1, 2]);
  assert.ok(tracker.progressCount >= 1);
  assert.equal(tracker.finished, true);
  assert.equal(tracker.finishReport!.stale, false);
  assert.equal(engine.hasJob(root), false);
});

test("layoutJobPoolBridge_coordinatedJobSlicesAndGenerationGuard", () => {
  const engine = createLayoutJobPool();
  const rootStub: TestRoot = { id: "root-coord" };
  const root = rootStub as TestRoot & Element;
  const processed: number[] = [];
  let finished = false;

  engine.attach(root);
  assert.equal(engine.isAttached(root), true);

  engine.startJob({
    root,
    kind: "Enhance",
    itemCount: 4,
    processItem: (i: number): void => { processed.push(i); },
    onFinished: (): void => { finished = true; },
    onFailed: (): void => {},
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
  const rootHt = root as TestRoot & HTMLElement;
  assert.equal(engine.runSlice({ root: rootHt, generation: gen + 999, shouldStop: (): boolean => false, admissionClass: "grant", deadline: Date.now(), quota: 16 }, 3), 0);

  // shouldStop always true: processes exactly 1 item per slice
  const controller = { root: rootHt, generation: gen, shouldStop: (): boolean => true, admissionClass: "grant" as const, deadline: Date.now(), quota: 16 };
  const count1 = engine.runSlice(controller, 3);
  assert.equal(count1, 1);
  assert.deepEqual(processed, [0]);
  assert.equal(finished, false);

  // Run remaining slices with shouldStop false
  const runAllController = { root: rootHt, generation: gen, shouldStop: (): boolean => false, admissionClass: "grant" as const, deadline: Date.now(), quota: 16 };
  const count2 = engine.runSlice(runAllController, 3);
  assert.equal(count2, 3);
  assert.deepEqual(processed, [0, 1, 2, 3]);
  assert.equal(finished, true);
  assert.equal(engine.hasJob(root), false);

  engine.detach(root);
});

test("layoutJobPoolBridge_tierGatingAndPendingCounts", () => {
  const engine = createLayoutJobPool();
  const rootStub: TestRoot = { id: "root-tier" };
  const root = rootStub as TestRoot & Element;
  const processed: number[] = [];
  let finished = false;
  // 3 items in work order; itemTierIndex maps item index to doc-order index:
  // item 0 -> doc 2, item 1 -> doc 0, item 2 -> doc 1
  const itemTierIndex: number[] = [2, 0, 1];
  const p0: TestRoot = { id: "p0" };
  const p1: TestRoot = { id: "p1" };
  const p2: TestRoot = { id: "p2" };
  const paragraphsByDoc: Element[] = [
    p0 as TestRoot & Element,
    p1 as TestRoot & Element,
    p2 as TestRoot & Element,
  ];

  engine.attach(root);
  engine.startJob({
    root,
    kind: "Relayout",
    itemCount: 3,
    processItem: (i: number): void => { processed.push(i); },
    onFinished: (): void => { finished = true; },
    onFailed: (): void => {},
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
  const rootHt = root as TestRoot & HTMLElement;
  // minTier = 1 slice: should skip item 0 (doc 2, tier 2) and process item 1 and 2 (doc 0 and 1, tier 1)
  const count1 = engine.runSlice({ root: rootHt, generation: gen, shouldStop: (): boolean => false, admissionClass: "grant" as const, deadline: Date.now(), quota: 16 }, 1);
  assert.equal(count1, 2);
  assert.deepEqual(processed, [1, 2]);
  assert.equal(finished, false);
  // TierGatedItemKeepsJobOpen: job must stay open and nextIndex parked on item 0
  assert.equal(engine.hasJob(root), true);
  assert.equal(engine.pendingInTier(root, 1), 0);
  assert.equal(engine.pendingInTier(root, 2), 1);

  // Run minTier = 2 slice: processes remaining item 0
  const count2 = engine.runSlice({ root: rootHt, generation: gen, shouldStop: (): boolean => false, admissionClass: "grant" as const, deadline: Date.now(), quota: 16 }, 2);
  assert.equal(count2, 1);
  assert.deepEqual(processed, [1, 2, 0]);
  assert.equal(finished, true);
  assert.equal(engine.hasJob(root), false);

  engine.detach(root);
});

test("layoutJobPoolBridge_staleMeasureGuardSkipsRemaining", () => {
  const engine = createLayoutJobPool();
  const rootStub: TestRoot = { id: "root-stale" };
  const root = rootStub as TestRoot & Element;
  const processed: number[] = [];
  let finished = false;
  let finishReport: LayoutJobFinishReport | null = null;
  let isStale = false;

  engine.attach(root);
  engine.startJob({
    root,
    kind: "Relayout",
    itemCount: 4,
    processItem: (i: number): void => { processed.push(i); },
    onFinished: (report: LayoutJobFinishReport): void => {
      finished = true;
      finishReport = report;
    },
    onFailed: (): void => {},
    isStale: (): boolean => isStale,
    startedAt: 6000,
    coordinated: true,
  });

  const gen = engine.jobGeneration(root);
  const rootHt = root as TestRoot & HTMLElement;
  // Process 1 item in slice 1
  engine.runSlice({ root: rootHt, generation: gen, shouldStop: (): boolean => true, admissionClass: "grant" as const, deadline: Date.now(), quota: 16 }, 3);
  assert.deepEqual(processed, [0]);
  assert.equal(finished, false);

  // Mark stale before slice 2
  isStale = true;
  engine.runSlice({ root: rootHt, generation: gen, shouldStop: (): boolean => false, admissionClass: "grant" as const, deadline: Date.now(), quota: 16 }, 3);

  // Remaining items skipped, processItem not called for 1, 2, 3
  assert.deepEqual(processed, [0]);
  assert.equal(finished, true);
  assert.equal(finishReport!.stale, true);
  assert.equal(engine.hasJob(root), false);

  engine.detach(root);
});

test("layoutJobPoolBridge_processItemErrorTriggersOnFailureAndOnFailed", () => {
  const engine = createLayoutJobPool();
  const rootStub: TestRoot = { id: "root-error" };
  const root = rootStub as TestRoot & Element;
  const events: string[] = [];
  let failurePayload: LayoutJobFailureReport | null = null;

  engine.attach(root);
  engine.startJob({
    root,
    kind: "Enhance",
    itemCount: 3,
    processItem: (i: number): void => {
      if (i === 1) throw new Error("Item boom");
    },
    onFailure: (): void => { events.push("onFailure"); },
    onFinished: (): void => { events.push("onFinished"); },
    onFailed: (failure: LayoutJobFailureReport): void => {
      events.push("onFailed");
      failurePayload = failure;
    },
    startedAt: 7000,
    coordinated: true,
  });

  const gen = engine.jobGeneration(root);
  const rootHt = root as TestRoot & HTMLElement;
  const processed = engine.runSlice({ root: rootHt, generation: gen, shouldStop: (): boolean => false, admissionClass: "grant" as const, deadline: Date.now(), quota: 16 }, 3);

  assert.equal(processed, 1); // 1 item succeeded before error on item 1
  assert.deepEqual(events, ["onFailure", "onFailed"]);
  assert.ok(failurePayload);
  const failure = failurePayload as LayoutJobFailureReport;
  assert.equal(failure.kind, "Enhance");
  assert.equal(failure.detail, "Item boom");
  assert.equal(failure.startedAt, 7000);
  assert.equal(engine.hasJob(root), false);

  engine.detach(root);
});

test("layoutJobPoolBridge_attachAndDetachStateTransitions", () => {
  const engine = createLayoutJobPool();
  const rootStub: TestRoot = { id: "root-detach" };
  const root = rootStub as TestRoot & Element;
  const processed: number[] = [];
  let finished = false;

  engine.attach(root);
  engine.startJob({
    root,
    kind: "Enhance",
    itemCount: 3,
    processItem: (i: number): void => { processed.push(i); },
    onFinished: (): void => { finished = true; },
    onFailed: (): void => {},
    startedAt: 8000,
    coordinated: true,
  });

  const gen = engine.jobGeneration(root);
  const rootHt = root as TestRoot & HTMLElement;
  // Run 1 item
  engine.runSlice({ root: rootHt, generation: gen, shouldStop: (): boolean => true, admissionClass: "grant" as const, deadline: Date.now(), quota: 16 }, 3);
  assert.deepEqual(processed, [0]);
  assert.equal(finished, false);

  // Detaching root immediately runs remaining items to completion (RunToCompletionWithoutCoordinator)
  engine.detach(root);
  assert.deepEqual(processed, [0, 1, 2]);
  assert.equal(finished, true);
  assert.equal(engine.hasJob(root), false);
  assert.equal(engine.isAttached(root), false);
});
