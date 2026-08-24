// Progressive layout and enhance job state machine.
//
// Plain script, no exports: running it installs globalThis.__TiqianProgressiveJob.
// Two consumers share this file as the single source of truth: the npm host
// (importing it for the side effect) and the Kotlin runtime bundle, into
// which the generateProgressiveJobBridge gradle task embeds this source verbatim.
// Double installation is guarded.
//
// Embedding constraint: the generator wraps this file in a Kotlin raw string,
// so the source must contain no dollar sign and no triple double-quote
// sequence. Use string concatenation, never template literals.

import type { GrantController, GrantStopPredicate } from "./coordinator/coordinator.js";

// Finish event payload reported through onFinished when a job completes.
// stale reports the stale-measure guard result; the old Kotlin job's
// commitSkipped flag was dropped because no writer ever set it.
export type ProgressiveJobFinishReport = {
  kind: string;
  startedAt: number;
  maxSliceMs: number;
  stale: boolean;
};

// Failure event payload reported through onFailed when an item or the
// finish path throws.
export type ProgressiveJobFailureReport = {
  kind: string;
  detail: string;
  startedAt: number;
  maxSliceMs: number;
};

// Catch-boundary shape: a thrown value may or may not carry a message. The
// cast happens once at the catch sites; the failJob body reads message when
// present and falls back to String(error).
interface CaughtError {
  message?: string;
}

type ProgressiveJobProcessItemFn = (index: number) => void;
type ProgressiveJobVoidCallbackFn = () => void;
type ProgressiveJobStaleCheckFn = () => boolean;
type ProgressiveJobFinishHandlerFn = (report: ProgressiveJobFinishReport) => void;
type ProgressiveJobFailureHandlerFn = (failure: ProgressiveJobFailureReport) => void;

// Spec handed to startJob. itemTierIndex maps item index to doc-order index;
// paragraphsByDoc lists the source elements in doc order.
export type ProgressiveJobSpec = {
  root: Element;
  kind: string;
  itemCount: number;
  processItem: ProgressiveJobProcessItemFn;
  onItemsFinished?: ProgressiveJobVoidCallbackFn | null;
  onFailure?: ProgressiveJobVoidCallbackFn | null;
  isStale?: ProgressiveJobStaleCheckFn | null;
  onProgress?: ProgressiveJobVoidCallbackFn | null;
  onFinished: ProgressiveJobFinishHandlerFn;
  onFailed: ProgressiveJobFailureHandlerFn;
  startedAt: number;
  itemTierIndex?: number[] | null;
  paragraphsByDoc?: HTMLElement[] | null;
  coordinated: boolean;
};

// Live job record keyed by root. The tier-tracking arrays are null until
// installTierTracking runs for a spec with an itemTierIndex.
type ProgressiveJob = {
  root: Element;
  kind: string;
  itemCount: number;
  processItem: ProgressiveJobProcessItemFn;
  onItemsFinished: ProgressiveJobVoidCallbackFn | null;
  onFailure: ProgressiveJobVoidCallbackFn | null;
  isStale: ProgressiveJobStaleCheckFn | null;
  onProgress: ProgressiveJobVoidCallbackFn | null;
  onFinished: ProgressiveJobFinishHandlerFn;
  onFailed: ProgressiveJobFailureHandlerFn;
  startedAt: number;
  itemTierIndex: number[] | null;
  paragraphsByDoc: HTMLElement[] | null;
  coordinated: boolean;
  generation: number;
  nextIndex: number;
  maxSliceDuration: number;
  paragraphTiers: number[] | null;
  tierPending: number[] | null;
  itemDone: boolean[] | null;
  docToItem: number[] | null;
};

type ProgressiveJobStartFn = (spec: ProgressiveJobSpec) => void;
type ProgressiveJobCancelFn = (root: Element) => void;
type ProgressiveJobRunSliceFn = (controller: GrantController | null, minTier: number) => number;
type ProgressiveJobHasFn = (root: Element) => boolean;
type ProgressiveJobGenerationFn = (root: Element) => number;
type ProgressiveJobKindFn = (root: Element) => string | null;
type ProgressiveJobPendingInTierFn = (root: Element, tier: number) => number;
type ProgressiveJobParagraphCountFn = (root: Element) => number;
type ProgressiveJobParagraphAtFn = (root: Element, index: number) => HTMLElement | null;
type ProgressiveJobSetTierFn = (root: Element, index: number, tier: number) => boolean;
type ProgressiveJobAttachFn = (root: Element) => boolean;
type ProgressiveJobDetachFn = (root: Element) => boolean;
type ProgressiveJobIsAttachedFn = (root: Element) => boolean;

export type ProgressiveJobApi = {
  startJob: ProgressiveJobStartFn;
  cancelJob: ProgressiveJobCancelFn;
  runSlice: ProgressiveJobRunSliceFn;
  hasJob: ProgressiveJobHasFn;
  jobGeneration: ProgressiveJobGenerationFn;
  jobKind: ProgressiveJobKindFn;
  pendingInTier: ProgressiveJobPendingInTierFn;
  paragraphCount: ProgressiveJobParagraphCountFn;
  paragraphAt: ProgressiveJobParagraphAtFn;
  setParagraphTier: ProgressiveJobSetTierFn;
  attach: ProgressiveJobAttachFn;
  detach: ProgressiveJobDetachFn;
  isAttached: ProgressiveJobIsAttachedFn;
};

declare global {
  var __TiqianProgressiveJob: ProgressiveJobApi | undefined;
}

(function () {
  if (globalThis.__TiqianProgressiveJob) return;

  // ParagraphTierGating: three priority bands, tier 1 in viewport, 2 near,
  // 3 far. A gate of TIER_COUNT admits every tier; run-to-completion jobs
  // use it as their default gate.
  var TIER_COUNT: number = 3;
  var TIER_IN_VIEWPORT: number = 1;

  // Job registry: one job per root. Grants address jobs by generation:
  // every started job increments the counter and carries the value, so a
  // grant built for an older job is rejected once the root's job has been
  // replaced.
  var jobs = new Map<Element, ProgressiveJob>();
  var attachedRoots = new WeakSet<Element>();
  var jobGenerationCounter: number = 0;

  function installTierTracking(job: ProgressiveJob): void {
    var itemTierIndex = job.itemTierIndex;
    if (!itemTierIndex) return;
    var count = itemTierIndex.length;
    job.paragraphTiers = new Array(count).fill(TIER_IN_VIEWPORT);
    job.tierPending = [count, 0, 0];
    job.itemDone = new Array(job.itemCount).fill(false);
    job.docToItem = new Array(count).fill(-1);
    for (var item = 0; item < count; item++) {
      job.docToItem[itemTierIndex[item]] = item;
    }
  }

  function markItemDone(job: ProgressiveJob, item: number): void {
    var done = job.itemDone;
    if (!done) return;
    if (done[item]) return;
    done[item] = true;
    var pending = job.tierPending;
    if (!pending) return;
    var tier = (job.paragraphTiers as number[])[(job.itemTierIndex as number[])[item]];
    if (tier < 1) tier = 1;
    if (tier > TIER_COUNT) tier = TIER_COUNT;
    if (pending[tier - 1] > 0) pending[tier - 1] -= 1;
  }

  function skipRemainingItems(job: ProgressiveJob): void {
    var done = job.itemDone;
    if (!done) {
      job.nextIndex = job.itemCount;
      return;
    }
    for (var item = job.nextIndex; item < job.itemCount; item++) {
      markItemDone(job, item);
    }
    job.nextIndex = job.itemCount;
  }

  function finishJob(job: ProgressiveJob): void {
    if (jobs.get(job.root) !== job) return;
    jobs.delete(job.root);
    job.onFinished({
      kind: job.kind,
      startedAt: job.startedAt,
      maxSliceMs: job.maxSliceDuration,
      // CommitSkippedRemoved: the old Kotlin job carried a commitSkipped
      // flag that no writer ever set, so the stale report read as
      // commitSkipped || stale(); the constant-false arm is dropped here.
      stale: !!(job.isStale && job.isStale()),
    });
  }

  function failJob(job: ProgressiveJob, error: CaughtError): void {
    if (jobs.get(job.root) !== job) return;
    jobs.delete(job.root);
    job.onFailed({
      kind: job.kind,
      detail: String((error && error.message) ? error.message : error),
      startedAt: job.startedAt,
      maxSliceMs: job.maxSliceDuration,
    });
  }

  function runSliceInternal(job: ProgressiveJob, controller: GrantController | null, gate: number): number {
    if (jobs.get(job.root) !== job) return 0;
    // The admission question bounds one grant: a coordinated slice receives
    // the coordinator's controller. A slice without a grant belongs to the
    // RunToCompletionWithoutCoordinator path and carries no stop terms; the
    // per-item measure guard inside processItem is the only boundary, so
    // the whole job runs in this one pass.
    var shouldStop: GrantStopPredicate = controller
      ? function (processed: number) { return controller.shouldStop(processed); }
      : function (): boolean { return false; };
    var sliceStartedAt = Date.now();
    var processedInSlice: number = 0;
    // StaleMeasureGuardPerSlice: a relayout job prepares every paragraph
    // against the width snapshot taken when the job started. When the host
    // width has drifted since the snapshot, the remaining items are skipped
    // and the finish event reports the job as stale. The guard runs once at
    // the head of each slice, before the slice's DOM writes.
    if (job.isStale && job.isStale()) skipRemainingItems(job);
    var done = job.itemDone;
    var tiers = job.paragraphTiers;
    var itemTierIndex = job.itemTierIndex;
    try {
      var sliceStartIndex = job.nextIndex;
      var index = job.nextIndex;
      while (index < job.itemCount) {
        if (done) {
          if (done[index]) { index += 1; continue; }
          if (tiers && itemTierIndex && tiers[itemTierIndex[index]] > gate) {
            index += 1;
            continue;
          }
        }
        job.processItem(index);
        if (done) markItemDone(job, index);
        processedInSlice += 1;
        index += 1;
        // At least one paragraph per slice: the question runs after an
        // item, so a grant always commits before it can be told to stop.
        if (shouldStop(processedInSlice)) break;
      }
      job.nextIndex = index;
      if (done) {
        // TierGatedItemKeepsJobOpen: the tier gate advances the cursor past
        // items it declined to run, so a slice that walks to itemCount
        // without breaking would otherwise finish the job over those items.
        // Park on the first not-done item by scanning back from where the
        // slice started, not forward from where the cursor stopped.
        var parked = sliceStartIndex;
        while (parked < job.itemCount && done[parked]) parked += 1;
        job.nextIndex = parked;
      }
    } catch (error) {
      if (job.onFailure) job.onFailure();
      failJob(job, error as CaughtError);
      return processedInSlice;
    }
    var sliceDuration = Date.now() - sliceStartedAt;
    if (sliceDuration > job.maxSliceDuration) job.maxSliceDuration = sliceDuration;
    if (job.onProgress) job.onProgress();
    if (job.nextIndex >= job.itemCount) {
      try {
        if (job.onItemsFinished) job.onItemsFinished();
        var finishDuration = Date.now() - sliceStartedAt;
        if (finishDuration > job.maxSliceDuration) {
          job.maxSliceDuration = finishDuration;
        }
        finishJob(job);
      } catch (error) {
        if (job.onFailure) job.onFailure();
        failJob(job, error as CaughtError);
      }
    }
    return processedInSlice;
  }

  globalThis.__TiqianProgressiveJob = {
    startJob: function (spec: ProgressiveJobSpec): void {
      var root = spec.root;
      cancelJob(root);
      jobGenerationCounter += 1;
      var job: ProgressiveJob = {
        root: root,
        kind: spec.kind,
        itemCount: spec.itemCount,
        processItem: spec.processItem,
        onItemsFinished: spec.onItemsFinished || null,
        onFailure: spec.onFailure || null,
        isStale: spec.isStale || null,
        onProgress: spec.onProgress || null,
        onFinished: spec.onFinished,
        onFailed: spec.onFailed,
        startedAt: spec.startedAt,
        itemTierIndex: spec.itemTierIndex || null,
        paragraphsByDoc: spec.paragraphsByDoc || null,
        coordinated: !!spec.coordinated,
        generation: jobGenerationCounter,
        nextIndex: 0,
        maxSliceDuration: 0,
        paragraphTiers: null,
        tierPending: null,
        itemDone: null,
        docToItem: null,
      };
      installTierTracking(job);
      jobs.set(root, job);
      if (job.itemCount === 0) {
        try {
          if (job.onItemsFinished) job.onItemsFinished();
          finishJob(job);
        } catch (error) {
          if (job.onFailure) job.onFailure();
          failJob(job, error as CaughtError);
        }
        return;
      }
      if (job.coordinated) {
        // WorkerPolledScheduling: the coordinator grants every slice of an
        // attached root. The job waits here; the first grant may land in
        // the same frame as the dispatch task and stays inside the shared
        // frame budget.
      } else {
        // RunToCompletionWithoutCoordinator: without an attached
        // coordinator nobody polls this root, so the job runs to completion
        // right here.
        while (jobs.get(root) === job && job.nextIndex < job.itemCount) {
          runSliceInternal(job, null, TIER_COUNT);
        }
      }
    },
    cancelJob: cancelJob,
    runSlice: function (controller: GrantController | null, minTier: number): number {
      if (!controller) return 0;
      var job = jobs.get(controller.root);
      if (!job) return 0;
      if (!job.coordinated) return 0;
      if (job.generation !== controller.generation) return 0;
      var gate = minTier < 1 ? 1 : (minTier > TIER_COUNT ? TIER_COUNT : minTier);
      return runSliceInternal(job, controller, gate);
    },
    hasJob: function (root: Element): boolean { return jobs.has(root); },
    jobGeneration: function (root: Element): number {
      var job = jobs.get(root);
      return job ? job.generation : 0;
    },
    jobKind: function (root: Element): string | null {
      var job = jobs.get(root);
      return job ? job.kind : null;
    },
    pendingInTier: function (root: Element, tier: number): number {
      if (tier < 1 || tier > TIER_COUNT) return 0;
      var job = jobs.get(root);
      return job && job.tierPending ? job.tierPending[tier - 1] : 0;
    },
    paragraphCount: function (root: Element): number {
      var job = jobs.get(root);
      return job && job.paragraphsByDoc ? job.paragraphsByDoc.length : 0;
    },
    paragraphAt: function (root: Element, index: number): HTMLElement | null {
      var job = jobs.get(root);
      if (!job || !job.paragraphsByDoc) return null;
      return index >= 0 && index < job.paragraphsByDoc.length
        ? job.paragraphsByDoc[index]
        : null;
    },
    setParagraphTier: function (root: Element, index: number, tier: number): boolean {
      if (tier < 1 || tier > TIER_COUNT) return false;
      var job = jobs.get(root);
      if (!job) return false;
      var tiers = job.paragraphTiers;
      if (!tiers) return false;
      if (index < 0 || index >= tiers.length) return false;
      var previous = tiers[index] < 1 ? 1 : (tiers[index] > TIER_COUNT ? TIER_COUNT : tiers[index]);
      if (previous === tier) return true;
      tiers[index] = tier;
      var pending = job.tierPending;
      if (!pending) return true;
      var item = job.docToItem ? job.docToItem[index] : -1;
      if (item >= 0 && job.itemDone && !job.itemDone[item]) {
        if (pending[previous - 1] > 0) pending[previous - 1] -= 1;
        pending[tier - 1] += 1;
      }
      return true;
    },
    attach: function (root: Element): boolean {
      attachedRoots.add(root);
      var job = jobs.get(root);
      if (job) job.coordinated = true;
      return true;
    },
    detach: function (root: Element): boolean {
      attachedRoots.delete(root);
      var job = jobs.get(root);
      if (job && job.coordinated) {
        // RunToCompletionWithoutCoordinator: with the coordinator gone
        // nobody polls this root anymore, so a job still in flight finishes
        // now.
        job.coordinated = false;
        while (jobs.get(root) === job && job.nextIndex < job.itemCount) {
          runSliceInternal(job, null, TIER_COUNT);
        }
      }
      return true;
    },
    isAttached: function (root: Element): boolean { return attachedRoots.has(root); },
  };

  function cancelJob(root: Element): void {
    jobs.delete(root);
  }
})();
