// ResponsiveManager — the geometry signal for one enhanced element
// (core-neutral parts ruling). Owns the root size observation with the
// paragraph width map and the seeded grid metrics, the viewport resize
// invalidation source, the responsive retarget frame, the responsive
// commit pair (pre-paint admission and the scheduled commit), and the
// seven geometry baselines (lastWidth, lastObservedWidth,
// lastParagraphMeasures, lastParagraphWidths,
// lastCommittedParagraphMeasures, pendingCommittedMeasures,
// gridMetricsState). The lastTypography baseline stays with the
// TypographyManager per the sole-owner ruling; this part reads and writes
// it through hooks.

import {
  fragmentedBorderBoxInlineSize,
  typographySignature,
  paragraphWidthSignature,
  paragraphMeasureSignature,
  layoutWorkViewportTypographyChanged,
} from "../../sampler/signatures.js";
import {
  createParagraphGridMetricsState,
  seedParagraphGridMetrics,
  paragraphMeasureSignatureFromObserved,
} from "../../sampler/grid-metrics.js";
import { hasHostInlineSizeParagraph } from "../responsive-measure.js";
import {
  isLoadedSnapshotAdopted,
  loadedSnapshotMaximumMeasureMatches,
} from "../../sampler/snapshot/loaded-snapshots.js";
import { snapshotCompletionSelector } from "../../sampler/snapshot/snapshot-completion.js";
import {
  createRootSizeObservation,
  createViewportResizeInvalidationSource,
  rootScopedParagraphs,
} from "../../sampler/observers.js";
import type {
  RootSizeObservationSource,
  ViewportResizeInvalidationSource,
} from "../../sampler/observers.js";
import type { FrameTaskCallback } from "../coordination/coordination-service.js";
import { InvalidationReason } from "./state.js";
import type { EnhancementStateMachine } from "./state-machine.js";
import type { SchedulerRegistration } from "./scheduler-registration.js";

export interface ResponsiveHooks {
  currentGeneration(): number;
  /** SnapshotAdoption: true while a snapshot font session is held. */
  snapshotFontSessionActive(): boolean;
  /** TypographyManager: steady observation + scheduled check verbs. */
  observeTypography(): void;
  scheduleTypographyCheck(force?: boolean): void;
  typographyBaseline(): string;
  setTypographyBaseline(value: string): void;
  /** EffectSync: content observation + drift probe state. */
  observeContent(): void;
  contentProbeFramePending(): boolean;
  takeContentTainted(): Element[];
  dispatchContentReconcile(paragraphs: Element[]): boolean;
  /** ContextState: layout-work drive surfaces. */
  cancelCapturedLayoutForLatestGeometry(): void;
  dispatchRelayout(observedMeasures: string | null): void;
  finishLayoutWorkAndObserve(): boolean;
  refreshRuntimeFromSource(options?: { revalidateSnapshotFont?: boolean }): void;
  /** TypographyManager: captured-job typography cancellation. */
  cancelCapturedLayoutForTypographyChange(): void;
  /** Lifecycle: the progressive enhance dispatch (completion restart). */
  dispatchProgressiveEnhance(generation: number, options?: { paragraphSelector?: string | null }): Promise<boolean>;
  /** SnapshotAdoption: invalidation and maximum-measure re-adoption. */
  snapshotInvalidateAndEnhance(options?: { restoreBeforeLoad?: boolean }): void;
  tryReadoptSnapshotAtMaximumMeasure(): void;
  /** DiagnosisManager + console path for a failed completion restart. */
  reportRefreshFailure(message: string, error: unknown): void;
}

export interface ResponsiveManager {
  observeWidth(): void;
  ensureViewportResizeListener(): void;
  scheduleResponsiveGeometryCommit(): void;
  stopWidthObservation(): void;
  clearResponsiveRetarget(): void;
  dropGridMetrics(): void;
  settleFinishedWork(currentMeasures: string, currentParagraphWidths: string): void;
  paragraphMeasureSignature(): string;
  paragraphMeasureSignatureFromObserved(): string;
  maximumMeasureActive(): boolean;
  lastWidth(): number;
  lastObservedWidth(): number;
  setLastObservedWidth(value: number): void;
  lastParagraphMeasures(): string;
  setLastParagraphMeasures(value: string): void;
  lastParagraphWidths(): string;
  setLastParagraphWidths(value: string): void;
  pendingCommittedMeasures(): string;
  setPendingCommittedMeasures(value: string): void;
  committedMeasureLedger(): string;
  setCommittedMeasureLedger(value: string): void;
  /** The frame callback the settle path cancels by identity. */
  responsiveCommitCallback(): FrameTaskCallback;
}

function createResponsiveManager(
  root: HTMLElement,
  stateMachine: EnhancementStateMachine,
  scheduler: SchedulerRegistration,
  hooks: ResponsiveHooks,
): ResponsiveManager {
  let sizeObservation: RootSizeObservationSource | null = null;
  const gridMetricsState = createParagraphGridMetricsState();
  let viewportResizeInvalidation: ViewportResizeInvalidationSource | null = null;
  let responsiveRetargetFrame: FrameTaskCallback | null = null;
  let lastObservedWidth = 0;
  let lastWidth = 0;
  let lastParagraphMeasures = "";
  let lastParagraphWidths = "";
  let lastCommittedParagraphMeasures = "";
  let pendingCommittedMeasures = "";

  const boundResponsiveCommit: FrameTaskCallback = () => {
    if (root.isConnected) commitResponsiveGeometryChange();
  };

  function paragraphMeasureSignatureFn(): string {
    return paragraphMeasureSignature(root, hooks.snapshotFontSessionActive());
  }

  function paragraphMeasureSignatureFromObservedFn(): string {
    return paragraphMeasureSignatureFromObserved(
      root,
      gridMetricsState,
      sizeObservation?.widths ?? null,
      hooks.snapshotFontSessionActive(),
      () => paragraphMeasureSignatureFn(),
    );
  }

  function maximumMeasureActive(): boolean {
    return root.hasAttribute("snapshot-ref") &&
      loadedSnapshotMaximumMeasureMatches(root);
  }

  function observeWidth(): void {
    if (sizeObservation) {
      // AdoptedWidthObservation: content reconcile adopts paragraphs after
      // the observer already exists. Seed and observe targets it has not
      // seen, so an adopted paragraph responds to later width changes.
      const paragraphs = rootScopedParagraphs(root);
      for (let i = 0; i < paragraphs.length; i++) {
        const paragraph = paragraphs[i];
        // Metrics seeding is decoupled from the width map: a source refresh
        // drops the seeds while surviving paragraph nodes stay in the width
        // map, and the width gate alone would then strand them on the
        // read-based fallback for every commit.
        if (!gridMetricsState.metrics?.has(paragraph)) seedParagraphGridMetrics(gridMetricsState, paragraph);
        if (sizeObservation.widths.has(paragraph)) continue;
        sizeObservation.widths.set(paragraph, fragmentedBorderBoxInlineSize(paragraph));
        sizeObservation.observe(paragraph);
      }
      return;
    }
    // ResponsiveInlineSizeObservation: takeover intentionally changes block
    // height. Seed and compare only border-box inline sizes so those commits do
    // not trigger a redundant responsive pass. Persistent observation without
    // pausing ensures drag interactions and live geometry changes are never lost.
    const widths = new WeakMap<Element, number>();
    const targets = [
      root,
      ...rootScopedParagraphs(root),
    ];
    for (let i = 0; i < targets.length; i++) {
      const target = targets[i];
      widths.set(target, fragmentedBorderBoxInlineSize(target));
      if (target !== root) seedParagraphGridMetrics(gridMetricsState, target);
    }
    sizeObservation = createRootSizeObservation({
      root,
      widths,
      onRootEntry: ({ width, height }) => {
        lastObservedWidth = width;
        scheduler.update({ inlineSize: width, area: width * (height || width * 0.6) });
        if (!stateMachine.inViewport && stateMachine.workInFlight) {
          // A width change while the root stays off-screen keeps pushing the
          // worker's deferred wake-up, so only the final width is laid out.
          scheduler.refreshWorkerDeferred();
        }
      },
      onWidthsChanged: () => {
        if (commitResponsiveGeometryPrePaint()) return;
        scheduleResponsiveGeometryCommit();
      },
    });
    sizeObservation.start(targets);
    ensureViewportResizeListener();
  }

  function ensureViewportResizeListener(): void {
    if (!viewportResizeInvalidation) {
      viewportResizeInvalidation = createViewportResizeInvalidationSource({
        onResize: () => {
          // ViewportResizeValidatesCapturedLayoutInputs: viewport resize is only a
          // signal that layout inputs may have changed. A fixed/max-width article
          // can receive the same event while every paragraph measure stays intact;
          // restoring native source before checking those inputs creates a visible
          // false rollback. Coalesce the live measure, maximum-snapshot and
          // typography comparison into the next pre-paint frame. A real change
          // still cancels the captured job there, while an equivalent grid keeps
          // both its committed paragraphs and remaining work.
          if (stateMachine.workInFlight && stateMachine.work.usesCapturedMeasure) {
            stateMachine.bumpGeometryRevision();
            stateMachine.invalidate(InvalidationReason.ResponsiveCommit);
            scheduleResponsiveRetarget();
            return;
          }
          // Uncaptured snapshot/font preparation revalidates live geometry before
          // it commits or begins captured work. It is not bound to the pre-resize
          // measure, so a raw viewport signal alone must not invalidate it.
          if (stateMachine.workInFlight) {
            return;
          }
          handleResponsiveGeometryChange();
        },
      });
    }
    viewportResizeInvalidation.start();
  }

  function handleResponsiveGeometryChange(): void {
    stateMachine.bumpGeometryRevision();
    // ResponsiveNativeRetargetSingleFlight: once rendered/runtime work has
    // been rolled back to semantic source, further resize signals only move
    // the same next-frame target. Do not synchronously rescan the entire
    // article or start another snapshot-font preparation for every OS resize event.
    if (stateMachine.isInvalidated(InvalidationReason.ResponsiveRelayout) && !stateMachine.runtimeActive) {
      stateMachine.invalidate(InvalidationReason.ResponsiveCommit);
      scheduleResponsiveGeometryCommit();
      return;
    }
    const snapshotAdopted = stateMachine.snapshotAdopted || isLoadedSnapshotAdopted(root);
    const committedMeasureChanged = stateMachine.dispatched && (
      paragraphMeasureSignatureFn() !== lastParagraphMeasures ||
      (snapshotAdopted && !loadedSnapshotMaximumMeasureMatches(root))
    );
    if (committedMeasureChanged) {
      if (stateMachine.workInFlight && stateMachine.work.usesCapturedMeasure) {
        hooks.cancelCapturedLayoutForLatestGeometry();
        return;
      }
      if (snapshotAdopted) {
        // ResponsiveSnapshotRollbackAtFirstSafeSignal: a maximum-width
        // snapshot is stale when the live paragraph measure changes. Viewport
        // resize reaches this synchronously before paint; a container-only
        // ResizeObserver signal reaches it at the leading edge of the next
        // frame, outside the observer delivery loop.
        hooks.snapshotInvalidateAndEnhance({ restoreBeforeLoad: true });
        return;
      }
      if (stateMachine.runtimeActive) {
        // ResponsiveRuntimeDirectInPlaceRelayout: when typography is stable,
        // width changes do not tear down the rendered DOM to native text.
        // Direct single-frame in-place relayout computes the new line breaks
        // using WidthIndependentAnnotationCache and swaps DOM atomically.
        stateMachine.invalidate(InvalidationReason.ResponsiveCommit);
        scheduleResponsiveGeometryCommit();
        return;
      }
    }
    if (stateMachine.workInFlight) {
      stateMachine.invalidate(InvalidationReason.ResponsiveCommit);
      scheduleResponsiveRetarget();
      return;
    }
    scheduleResponsiveGeometryCommit();
  }

  function scheduleResponsiveGeometryCommit(): void {
    if (stateMachine.workInFlight) {
      stateMachine.invalidate(InvalidationReason.ResponsiveCommit);
      return;
    }
    scheduler.requestFrame(boundResponsiveCommit);
  }

  // PrePaintResponsiveCommit: ResizeObserver delivers after layout and
  // before paint, so a width-only commit that completes synchronously here
  // paints with the new width in the same frame; the scheduled commit paints
  // one frame of old lines first. Only the steady width-only case
  // qualifies — every other case keeps the scheduled commit's ordering
  // guarantees. Verified by demo/web/tests/resize-prepaint-commit.test.mjs.
  function commitResponsiveGeometryPrePaint(): boolean {
    if (!root.isConnected || !stateMachine.inViewport) return false;
    if (!stateMachine.runtimeActive || !stateMachine.dispatched) return false;
    if (hooks.contentProbeFramePending()) return false;
    if (stateMachine.snapshotAdopted || isLoadedSnapshotAdopted(root)) return false;
    if (root.ownerDocument?.fonts?.status === "loading") return false;
    if (stateMachine.workInFlight) {
      // PreemptiveCrossingRelayout: without preemption only a drag's first
      // crossing reaches the pre-paint admission; later ones wait out the
      // scheduled cadence behind the in-flight job. A width-only relayout
      // is safe to replace — the runtime cancels it and rebuilds at the
      // latest width (WidthSnapshotPerRelayoutJob). Preempt only on a real
      // cell crossing; enhance and reconcile jobs are never replaced here.
      if (stateMachine.work.kind !== "Relayout") return false;
      // ContentBeforeGeometry still rules: a pending reconcile keeps the
      // scheduled commit, whose pass re-lowers drifted content before any
      // width pass; a geometry-only preempt would relay stale text for the
      // rest of the drag.
      if (stateMachine.isInvalidated(InvalidationReason.ContentDrift)) return false;
      const measures = paragraphMeasureSignatureFromObservedFn();
      if (measures === lastParagraphMeasures) return false;
      lastWidth = lastObservedWidth || fragmentedBorderBoxInlineSize(root);
      lastParagraphMeasures = measures;
      return withRootObservationPaused(() => hooks.dispatchRelayout(measures));
    }
    return withRootObservationPaused(() => commitResponsiveGeometryChange());
  }

  // One pause/resume protocol for both pre-paint admission paths: the root is
  // unobserved around the synchronous commit so its own height change
  // cannot queue a same-depth observation for the browser's ResizeObserver
  // loop guard to report, then re-observed with the original box option.
  function withRootObservationPaused(commit: () => void): boolean {
    sizeObservation?.unobserve(root);
    try {
      commit();
      scheduler.grantImmediate();
    } finally {
      sizeObservation?.observe(root);
    }
    return true;
  }

  function commitResponsiveGeometryChange(): void {
    if (!root.isConnected) return;
    if (stateMachine.workInFlight) {
      stateMachine.invalidate(InvalidationReason.ResponsiveCommit);
      return;
    }
    if (!stateMachine.inViewport && lastObservedWidth != null) {
      // OffscreenTrailingWidthCheck: ResizeObserver delivers on animation
      // frames, so while the frame loop pauses mid-drag the observer goes
      // quiet and the off-screen debounce can expire although the width is
      // still moving. Read the live width before releasing the commit; a
      // moving width re-enters the trailing commit.
      const liveWidth = fragmentedBorderBoxInlineSize(root);
      if (Math.abs(liveWidth - lastObservedWidth) >= 0.5) {
        lastObservedWidth = liveWidth;
        stateMachine.invalidate(InvalidationReason.ResponsiveCommit);
        scheduleResponsiveGeometryCommit();
        return;
      }
    }
    // Before the first snapshot/runtime commit there is no layout to update.
    // The initial job will read the latest live width once its font gate opens.
    const forceLatestWidth = stateMachine.isInvalidated(InvalidationReason.ResponsiveRelayout) ||
      stateMachine.isInvalidated(InvalidationReason.ResponsiveCommit);
    stateMachine.clearInvalidation(InvalidationReason.ResponsiveCommit);
    stateMachine.clearInvalidation(InvalidationReason.ResponsiveRelayout);
    if (!stateMachine.dispatched) return;
    if (stateMachine.isInvalidated(InvalidationReason.ContentDrift) && !hooks.contentProbeFramePending()) {
      // ContentBeforeGeometry: one commit path serves ResizeObserver and
      // MutationObserver alike. Content goes first because re-lowering reads
      // the live width, so a concurrent width change is absorbed by the same
      // job; the reverse order would relayout stale text first. An idle
      // reconcile falls through so a width-only change still commits.
      stateMachine.clearInvalidation(InvalidationReason.ContentDrift);
      const tainted = hooks.takeContentTainted();
      if (stateMachine.snapshotAdopted || isLoadedSnapshotAdopted(root)) {
        hooks.snapshotInvalidateAndEnhance({ restoreBeforeLoad: true });
        return;
      }
      if (hooks.dispatchContentReconcile(tainted)) {
        // ReconcileCommitPreservesWidthIntent: a work verdict returns before
        // the width pass runs, and the reconcile job re-lowers only drifted,
        // tainted and stranded paragraphs. A width change already pending at
        // this commit would die with the responsive bits beginLayoutWork
        // clears; the finish would then store the live width against stale
        // paragraphs and the change would never re-enter layout. Re-arm the
        // commit so the finish schedules one latest-width pass.
        const pendingWidth = lastObservedWidth || fragmentedBorderBoxInlineSize(root);
        if (forceLatestWidth || Math.abs(pendingWidth - lastWidth) >= 0.5) {
          stateMachine.invalidate(InvalidationReason.ResponsiveCommit);
        }
        return;
      }
    }
    const width = lastObservedWidth || fragmentedBorderBoxInlineSize(root);
    lastObservedWidth = width;
    const widthsChanged = Math.abs(width - lastWidth) >= 0.5;
    const paragraphWidths = widthsChanged ? lastParagraphWidths : paragraphWidthSignature(root);
    // LineLengthGridResponsiveInvalidation: the quantized measure signature
    // is computed on every commit, width changes included, so the same-named
    // gate below can skip in-cell width motion instead of dispatching a job
    // that reproduces identical paragraph DOM. Layout is clean at commit
    // time (the width read above already forced it), so the per-paragraph
    // reads here do not thrash.
    const paragraphMeasures = paragraphMeasureSignatureFromObservedFn();
    const hostInlineSizeRefresh = widthsChanged && hasHostInlineSizeParagraph(root);
    const measuresChanged = paragraphMeasures !== lastParagraphMeasures;
    const signature = (widthsChanged && !stateMachine.isInvalidated(InvalidationReason.TypographyRefreshForced))
      ? hooks.typographyBaseline()
      : typographySignature(root);
    const typographyChanged = signature !== hooks.typographyBaseline();
    if (!forceLatestWidth && !widthsChanged && !measuresChanged && !typographyChanged) {
      observeWidth();
      return;
    }
    lastWidth = width;
    lastParagraphMeasures = paragraphMeasures;
    lastParagraphWidths = paragraphWidths;

    const snapshotAdopted = stateMachine.snapshotAdopted || isLoadedSnapshotAdopted(root);
    const atMaximumMeasure = root.hasAttribute("snapshot-ref") &&
      loadedSnapshotMaximumMeasureMatches(root);
    if (snapshotAdopted) {
      if (atMaximumMeasure && !typographyChanged) {
        // MixedSnapshotCompletionResume: cancelling a captured runtime-only
        // job restores just its unkeyed source; the keyed snapshot remains
        // valid. Restart that partial job instead of treating the still-valid
        // snapshot as proof that every paragraph is settled.
        const completionSelector = snapshotCompletionSelector(root);
        if (completionSelector && !stateMachine.runtimeActive) {
          const generation = hooks.currentGeneration();
          hooks.dispatchProgressiveEnhance(generation, {
            paragraphSelector: completionSelector,
          }).catch((error) => {
            if (!root.isConnected || generation !== hooks.currentGeneration()) return;
            hooks.finishLayoutWorkAndObserve();
            hooks.reportRefreshFailure("Tiqian Web snapshot completion restart failed", error);
          });
          return;
        }
        // A parent may keep growing after the paragraph has reached max-width.
        // The snapshot contract is still valid; do not churn the DOM.
        hooks.setTypographyBaseline(signature);
        observeWidth();
        hooks.observeTypography();
      } else {
        hooks.snapshotInvalidateAndEnhance();
      }
      return;
    }
    if (!stateMachine.runtimeActive && atMaximumMeasure && !typographyChanged) {
      hooks.tryReadoptSnapshotAtMaximumMeasure();
      return;
    }
    // A forced pass (viewport revalidation, stale follow-up) may only skip
    // against the CommittedMeasureLedger; a normal pass dedups against the
    // dispatch bookkeeping.
    const measureSettled = forceLatestWidth
      ? paragraphMeasures === lastCommittedParagraphMeasures
      : !measuresChanged;
    if (!typographyChanged && !hostInlineSizeRefresh && measureSettled) {
      // LineLengthGridResponsiveInvalidation: Web currently exposes the
      // engine's Start-aligned body only. Within one N×fontSize cell count,
      // the measure, line breaks, placements, and body offset are unchanged.
      // Keep observing exact geometry for snapshot evidence, but do not ask
      // the engine to reproduce identical paragraph DOM. A forced pass
      // (viewport revalidation, stale follow-up) skips only against the
      // CommittedMeasureLedger: dispatch-time bookkeeping is optimistic and
      // a stale-died job must still get its convergence pass, but a ledger
      // hit proves the committed layout already matches this cell — during
      // a window drag nearly every viewport-forced pass lands here.
      hooks.setTypographyBaseline(signature);
      observeWidth();
      hooks.observeTypography();
      return;
    }
    // ResponsiveTypographyBeforeRebreak: a media query can change font
    // metrics in the same resize without mutating any class/style attribute.
    // Re-lower in that case; reserve the cheap width-only path for stable
    // typography.
    if (root.ownerDocument?.fonts?.status === "loading") {
      observeWidth();
      hooks.observeTypography();
      hooks.scheduleTypographyCheck(true);
      return;
    }
    if (typographyChanged) {
      hooks.setTypographyBaseline(signature);
      hooks.refreshRuntimeFromSource({ revalidateSnapshotFont: true });
      return;
    }
    if (stateMachine.runtimeActive) {
      hooks.dispatchRelayout(paragraphMeasures);
      return;
    }
    hooks.refreshRuntimeFromSource({ revalidateSnapshotFont: false });
  }

  function removeViewportResizeListener(): void {
    viewportResizeInvalidation?.stop();
    viewportResizeInvalidation = null;
  }

  function stopWidthObservation(): void {
    clearResponsiveRetarget();
    sizeObservation?.stop();
    sizeObservation = null;
    gridMetricsState.metrics = null;
    lastObservedWidth = 0;
    removeViewportResizeListener();
  }

  function scheduleResponsiveRetarget(): void {
    if (!stateMachine.workInFlight || !stateMachine.work.usesCapturedMeasure) return;
    clearResponsiveRetarget();
    const operation = stateMachine.transaction.layoutOperation;
    const retargetFrame: FrameTaskCallback = () => {
      responsiveRetargetFrame = null;
      const work = stateMachine.work;
      if (
        !root.isConnected || !stateMachine.workInFlight ||
        !work.usesCapturedMeasure || operation !== stateMachine.transaction.layoutOperation
      ) return;
      if (layoutWorkViewportTypographyChanged(root, work.viewportTypographyEntries)) {
        hooks.cancelCapturedLayoutForTypographyChange();
        return;
      }
      const maximumMeasure = root.hasAttribute("snapshot-ref") &&
        loadedSnapshotMaximumMeasureMatches(root);
      // SameGridRetargetWithoutRestart: a responsive relayout dispatch uses
      // captureSignatures:false and reads its measure live inside the layout
      // job, so the work record's measure signature is empty here. Comparing
      // against that empty signature cancelled the in-flight job on every width
      // event. This guard compares against the measure of the last completed
      // job instead. While the width stays inside the same N×fontSize grid
      // cell, the committed DOM is already correct and unchanged paragraphs
      // are skipped at zero cost, so the in-flight job keeps running. When
      // the width crosses into a new cell, or when no completed measure
      // exists yet, the guard cancels the job and restarts it at the latest
      // width.
      const measureBaseline = work.measureSignature || lastParagraphMeasures;
      if (
        paragraphMeasureSignatureFn() === measureBaseline &&
        maximumMeasure === work.maximumMeasure
      ) return;
      hooks.cancelCapturedLayoutForLatestGeometry();
    };
    responsiveRetargetFrame = retargetFrame;
    scheduler.requestFrame(retargetFrame);
  }

  function clearResponsiveRetarget(): void {
    if (!responsiveRetargetFrame) return;
    scheduler.cancelFrame(responsiveRetargetFrame);
    responsiveRetargetFrame = null;
  }

  function settleFinishedWork(currentMeasures: string, currentParagraphWidths: string): void {
    lastWidth = fragmentedBorderBoxInlineSize(root);
    lastParagraphMeasures = currentMeasures;
    lastParagraphWidths = currentParagraphWidths;
    observeWidth();
    hooks.observeTypography();
    hooks.observeContent();
  }

  return {
    observeWidth,
    ensureViewportResizeListener,
    scheduleResponsiveGeometryCommit,
    stopWidthObservation,
    clearResponsiveRetarget,
    dropGridMetrics() {
      gridMetricsState.metrics = null;
    },
    settleFinishedWork,
    paragraphMeasureSignature: paragraphMeasureSignatureFn,
    paragraphMeasureSignatureFromObserved: paragraphMeasureSignatureFromObservedFn,
    maximumMeasureActive,
    lastWidth() {
      return lastWidth;
    },
    lastObservedWidth() {
      return lastObservedWidth;
    },
    setLastObservedWidth(value: number) {
      lastObservedWidth = value;
    },
    lastParagraphMeasures() {
      return lastParagraphMeasures;
    },
    setLastParagraphMeasures(value: string) {
      lastParagraphMeasures = value;
    },
    lastParagraphWidths() {
      return lastParagraphWidths;
    },
    setLastParagraphWidths(value: string) {
      lastParagraphWidths = value;
    },
    pendingCommittedMeasures() {
      return pendingCommittedMeasures;
    },
    setPendingCommittedMeasures(value: string) {
      pendingCommittedMeasures = value;
    },
    committedMeasureLedger() {
      return lastCommittedParagraphMeasures;
    },
    setCommittedMeasureLedger(value: string) {
      lastCommittedParagraphMeasures = value;
    },
    responsiveCommitCallback() {
      return boundResponsiveCommit;
    },
  };
}

export { createResponsiveManager };
