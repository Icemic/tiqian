// Layout job pool: the per-root layout job registry for the progressive
// layout and enhance passes.
//
// Stateful module: createLayoutJobPool() owns the per-root layout job
// registry (item batches, viewport tiers, generations) and the attachment
// set. The engine bootstrap constructs one instance and hands it to its
// consumers; tests construct one per file.
//
// Embedding constraint: the generator wraps this file in a Kotlin raw string,
// so the source must contain no dollar sign and no triple double-quote
// sequence. Use string concatenation, never template literals.

import type { GrantController, GrantStopPredicate } from "./coordination/coordination-service.js";

// Finish event payload reported through onFinished when a job completes.
// stale reports the stale-measure guard result; the old Kotlin job's
// commitSkipped flag was dropped because no writer ever set it.
export type LayoutJobFinishReport = {
  kind: string;
  startedAt: number;
  maxSliceMs: number;
  stale: boolean;
};

// Failure event payload reported through onFailed when an item or the
// finish path throws.
export type LayoutJobFailureReport = {
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

type LayoutJobProcessItemFn = (index: number) => void;
type LayoutJobVoidCallbackFn = () => void;
type LayoutJobStaleCheckFn = () => boolean;
type LayoutJobFinishHandlerFn = (report: LayoutJobFinishReport) => void;
type LayoutJobFailureHandlerFn = (failure: LayoutJobFailureReport) => void;

// Spec handed to startJob. itemTierIndex maps item index to doc-order index;
// paragraphsByDoc lists the source elements in doc order.
export type LayoutJobSpec = {
  root: Element;
  kind: string;
  itemCount: number;
  processItem: LayoutJobProcessItemFn;
  onItemsFinished?: LayoutJobVoidCallbackFn | null;
  onFailure?: LayoutJobVoidCallbackFn | null;
  isStale?: LayoutJobStaleCheckFn | null;
  onProgress?: LayoutJobVoidCallbackFn | null;
  onFinished: LayoutJobFinishHandlerFn;
  onFailed: LayoutJobFailureHandlerFn;
  startedAt: number;
  itemTierIndex?: number[] | null;
  paragraphsByDoc?: Element[] | null;
  coordinated: boolean;
};

// Live job record keyed by root. The tier-tracking arrays are null until
// installTierTracking runs for a spec with an itemTierIndex.
type LayoutJob = {
  root: Element;
  kind: string;
  itemCount: number;
  processItem: LayoutJobProcessItemFn;
  onItemsFinished: LayoutJobVoidCallbackFn | null;
  onFailure: LayoutJobVoidCallbackFn | null;
  isStale: LayoutJobStaleCheckFn | null;
  onProgress: LayoutJobVoidCallbackFn | null;
  onFinished: LayoutJobFinishHandlerFn;
  onFailed: LayoutJobFailureHandlerFn;
  startedAt: number;
  itemTierIndex: number[] | null;
  paragraphsByDoc: Element[] | null;
  coordinated: boolean;
  generation: number;
  nextIndex: number;
  maxSliceDuration: number;
  paragraphTiers: number[] | null;
  tierPending: number[] | null;
  itemDone: boolean[] | null;
  docToItem: number[] | null;
};

type LayoutJobStartFn = (spec: LayoutJobSpec) => void;
type LayoutJobCancelFn = (root: Element) => void;
type LayoutJobRunSliceFn = (controller: GrantController | null, minTier: number) => number;
type LayoutJobHasFn = (root: Element) => boolean;
type LayoutJobGenerationFn = (root: Element) => number;
type LayoutJobKindFn = (root: Element) => string | null;
type LayoutJobPendingInTierFn = (root: Element, tier: number) => number;
type LayoutJobParagraphCountFn = (root: Element) => number;
type LayoutJobParagraphAtFn = (root: Element, index: number) => Element | null;
type LayoutJobSetTierFn = (root: Element, index: number, tier: number) => boolean;
type LayoutJobAttachFn = (root: Element) => boolean;
type LayoutJobDetachFn = (root: Element) => boolean;
type LayoutJobIsAttachedFn = (root: Element) => boolean;

export type LayoutJobPool = {
  startJob: LayoutJobStartFn;
  cancelJob: LayoutJobCancelFn;
  runSlice: LayoutJobRunSliceFn;
  hasJob: LayoutJobHasFn;
  jobGeneration: LayoutJobGenerationFn;
  jobKind: LayoutJobKindFn;
  pendingInTier: LayoutJobPendingInTierFn;
  paragraphCount: LayoutJobParagraphCountFn;
  paragraphAt: LayoutJobParagraphAtFn;
  setParagraphTier: LayoutJobSetTierFn;
  attach: LayoutJobAttachFn;
  detach: LayoutJobDetachFn;
  isAttached: LayoutJobIsAttachedFn;
};

export function createLayoutJobPool(): LayoutJobPool {
  // ParagraphTierGating: three priority bands, tier 1 in viewport, 2 near,
  // 3 far. A gate of TIER_COUNT admits every tier; run-to-completion jobs
  // use it as their default gate.
  const TIER_COUNT: number = 3;
  const TIER_IN_VIEWPORT: number = 1;

  // Job registry: one job per root. Grants address jobs by generation:
  // every started job increments the counter and carries the value, so a
  // grant built for an older job is rejected once the root's job has been
  // replaced.
  const jobs = new Map<Element, LayoutJob>();
  const attachedRoots = new WeakSet<Element>();
  let jobGenerationCounter: number = 0;

  function installTierTracking(job: LayoutJob): void {
    const itemTierIndex = job.itemTierIndex;
    if (!itemTierIndex) return;
    const count = itemTierIndex.length;
    job.paragraphTiers = new Array(count).fill(TIER_IN_VIEWPORT);
    job.tierPending = [count, 0, 0];
    job.itemDone = new Array(job.itemCount).fill(false);
    job.docToItem = new Array(count).fill(-1);
    for (let item = 0; item < count; item++) {
      job.docToItem[itemTierIndex[item]] = item;
    }
  }

  function markItemDone(job: LayoutJob, item: number): void {
    const done = job.itemDone;
    if (!done) return;
    if (done[item]) return;
    done[item] = true;
    const pending = job.tierPending;
    if (!pending) return;
    let tier = (job.paragraphTiers as number[])[(job.itemTierIndex as number[])[item]];
    if (tier < 1) tier = 1;
    if (tier > TIER_COUNT) tier = TIER_COUNT;
    if (pending[tier - 1] > 0) pending[tier - 1] -= 1;
  }

  function skipRemainingItems(job: LayoutJob): void {
    const done = job.itemDone;
    if (!done) {
      job.nextIndex = job.itemCount;
      return;
    }
    for (let item = job.nextIndex; item < job.itemCount; item++) {
      markItemDone(job, item);
    }
    job.nextIndex = job.itemCount;
  }

  function finishJob(job: LayoutJob): void {
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

  function failJob(job: LayoutJob, error: CaughtError): void {
    if (jobs.get(job.root) !== job) return;
    jobs.delete(job.root);
    job.onFailed({
      kind: job.kind,
      detail: String((error && error.message) ? error.message : error),
      startedAt: job.startedAt,
      maxSliceMs: job.maxSliceDuration,
    });
  }

  function runSliceInternal(job: LayoutJob, controller: GrantController | null, gate: number): number {
    if (jobs.get(job.root) !== job) return 0;
    // The admission question bounds one grant: a coordinated slice receives
    // the coordinator's controller. A slice without a grant belongs to the
    // RunToCompletionWithoutCoordinator path and carries no stop terms; the
    // per-item measure guard inside processItem is the only boundary, so
    // the whole job runs in this one pass.
    const shouldStop: GrantStopPredicate = controller
      ? function (processed: number) { return controller.shouldStop(processed); }
      : function (): boolean { return false; };
    const sliceStartedAt = Date.now();
    let processedInSlice: number = 0;
    // StaleMeasureGuardPerSlice: a relayout job prepares every paragraph
    // against the width snapshot taken when the job started. When the host
    // width has drifted since the snapshot, the remaining items are skipped
    // and the finish event reports the job as stale. The guard runs once at
    // the head of each slice, before the slice's DOM writes.
    if (job.isStale && job.isStale()) skipRemainingItems(job);
    const done = job.itemDone;
    const tiers = job.paragraphTiers;
    const itemTierIndex = job.itemTierIndex;
    try {
      const sliceStartIndex = job.nextIndex;
      let index = job.nextIndex;
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
        let parked = sliceStartIndex;
        while (parked < job.itemCount && done[parked]) parked += 1;
        job.nextIndex = parked;
      }
    } catch (error) {
      if (job.onFailure) job.onFailure();
      failJob(job, error as CaughtError);
      return processedInSlice;
    }
    const sliceDuration = Date.now() - sliceStartedAt;
    if (sliceDuration > job.maxSliceDuration) job.maxSliceDuration = sliceDuration;
    if (job.onProgress) job.onProgress();
    if (job.nextIndex >= job.itemCount) {
      try {
        if (job.onItemsFinished) job.onItemsFinished();
        const finishDuration = Date.now() - sliceStartedAt;
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

  return {
    startJob: function (spec: LayoutJobSpec): void {
      const root = spec.root;
      cancelJob(root);
      jobGenerationCounter += 1;
      const job: LayoutJob = {
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
      const job = jobs.get(controller.root);
      if (!job) return 0;
      if (!job.coordinated) return 0;
      if (job.generation !== controller.generation) return 0;
      const gate = minTier < 1 ? 1 : (minTier > TIER_COUNT ? TIER_COUNT : minTier);
      return runSliceInternal(job, controller, gate);
    },
    hasJob: function (root: Element): boolean { return jobs.has(root); },
    jobGeneration: function (root: Element): number {
      const job = jobs.get(root);
      return job ? job.generation : 0;
    },
    jobKind: function (root: Element): string | null {
      const job = jobs.get(root);
      return job ? job.kind : null;
    },
    pendingInTier: function (root: Element, tier: number): number {
      if (tier < 1 || tier > TIER_COUNT) return 0;
      const job = jobs.get(root);
      return job && job.tierPending ? job.tierPending[tier - 1] : 0;
    },
    paragraphCount: function (root: Element): number {
      const job = jobs.get(root);
      return job && job.paragraphsByDoc ? job.paragraphsByDoc.length : 0;
    },
    paragraphAt: function (root: Element, index: number): Element | null {
      const job = jobs.get(root);
      if (!job || !job.paragraphsByDoc) return null;
      return index >= 0 && index < job.paragraphsByDoc.length
        ? job.paragraphsByDoc[index]
        : null;
    },
    setParagraphTier: function (root: Element, index: number, tier: number): boolean {
      if (tier < 1 || tier > TIER_COUNT) return false;
      const job = jobs.get(root);
      if (!job) return false;
      const tiers = job.paragraphTiers;
      if (!tiers) return false;
      if (index < 0 || index >= tiers.length) return false;
      const previous = tiers[index] < 1 ? 1 : (tiers[index] > TIER_COUNT ? TIER_COUNT : tiers[index]);
      if (previous === tier) return true;
      tiers[index] = tier;
      const pending = job.tierPending;
      if (!pending) return true;
      const item = job.docToItem ? job.docToItem[index] : -1;
      if (item >= 0 && job.itemDone && !job.itemDone[item]) {
        if (pending[previous - 1] > 0) pending[previous - 1] -= 1;
        pending[tier - 1] += 1;
      }
      return true;
    },
    attach: function (root: Element): boolean {
      attachedRoots.add(root);
      const job = jobs.get(root);
      if (job) job.coordinated = true;
      return true;
    },
    detach: function (root: Element): boolean {
      attachedRoots.delete(root);
      const job = jobs.get(root);
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
}
