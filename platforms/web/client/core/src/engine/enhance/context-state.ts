// ContextState — the layout-work drive of one enhanced element context
// (core-neutral parts ruling, 2026-08-27 user naming; replaces the
// discarded name WorkRounds). Owns the begin/finish layout-work verbs,
// the relayout dispatch, the source refresh, the captured-layout
// cancellation for geometry drift, the layout worker attach/sync verbs,
// the paragraph candidate enumeration dissolved from root-state.ts and
// the tracked-paragraph accumulation (the live paragraphs array the
// drivers and the relayout session splice by reference).
//
// The runtime-established flag is the registry dissolution's substitute
// for root-state's WeakMap getState(root) check: one context per
// element, so the element identity is the key and a boolean answers the
// former "does this root carry runtime state" question.
//
// The part holds the StateMachine and the SchedulerRegistration per the
// holding table; every other out-edge is an injected hook built by the
// composition root (settle callback, onWorkerAttached callback and the
// cross-part surfaces), so the dependency graph stays single-directional.

import type { LoweredParagraph } from "../lowered-paragraph.js";
import type { ResolvedEnhanceOptions } from "../lifecycle.js";
import {
  captureLayoutWorkViewportTypographyEntries,
  responsiveGeometrySignature,
  typographySignature,
} from "../../sampler/signatures.js";
import { shouldTryParagraph } from "../eligibility.js";
import { captureViewportAnchor, compensateViewportAnchor, releaseNativeScrollAnchoring } from "../coordination/viewport-anchor.js";
import { globalServices } from "../../services/global-services.js";
import { InvalidationReason } from "./state.js";
import type { EnhancementStateMachine } from "./state-machine.js";
import type { SchedulerRegistration } from "./scheduler-registration.js";
import type { EnhanceDispatchOptions } from "./snapshot-adoption.js";

export const ROOT_SELECTOR = "tiqian-prose, [data-tiqian-root]";

// One tracked semantic paragraph in engine state: the raw-DOM backup source
// element, its lowered markdown tree, and the last applied measure.
// Surviving type dissolved from root-state.ts; the name is kept.
export type TrackedParagraph = {
  source: Element;
  lowered: LoweredParagraph;
  lastMeasure: number | null;
};

export interface LayoutWorkOptions {
  usesCapturedMeasure?: boolean;
  captureSignatures?: boolean;
}

export interface SourceRefreshOptions {
  revalidateSnapshotFont?: boolean;
}

export interface ContextStateHooks {
  currentGeneration(): number;
  /** ResponsiveManager: drop any pending responsive retarget frame. */
  clearResponsiveRetarget(): void;
  /** ResponsiveManager: live paragraph measure signature. */
  paragraphMeasureSignature(): string;
  /** ResponsiveManager: observed-grid paragraph measure signature. */
  paragraphMeasureSignatureFromObserved(): string;
  /** ResponsiveManager: per-paragraph width signature. */
  paragraphWidthSignature(): string;
  /** ResponsiveManager: stored paragraph baselines. */
  lastParagraphWidths(): string;
  lastParagraphMeasures(): string;
  /** TypographyManager: write the lastTypography baseline. */
  setTypographyBaseline(value: string): void;
  /** ResponsiveManager: the dispatch-time committed-measure ledger. */
  setPendingCommittedMeasures(value: string): void;
  /** ResponsiveManager: the clean-relayout committed-measure ledger. */
  setCommittedMeasureLedger(value: string): void;
  /** OptionsLedger + loaded-snapshots composed maximum-measure view. */
  maximumMeasureActive(): boolean;
  /** Composition-root settle callback: finish baselines + observer re-attach. */
  settleFinishedWork(currentMeasures: string, currentParagraphWidths: string): void;
  /** EffectSync: schedule the read-only content drift probe frame. */
  scheduleContentDriftProbeFrame(operation: number): void;
  /** EffectSync: true while a content drift probe frame is queued. */
  contentProbeFramePending(): boolean;
  /** ResponsiveManager surfaces used by the finish branches. */
  ensureViewportResizeListener(): void;
  scheduleResponsiveGeometryCommit(): void;
  /** Observation verbs used at begin/finish. */
  stopTypographyObservation(): void;
  observeContent(): void;
  observeLayoutWorkInputs(): void;
  stopLayoutWorkInputObservation(): void;
  /** TypographyManager: advance the baseline after a captured cancel. */
  advanceTypographyBaselineAfterCancellation(): void;
  /** Composition root: cancel the root's layout job on the shared pool. */
  cancelRootLayoutWork(): void;
  /** Composition root: deactivate the worker through the scheduler. */
  deactivateWorkerRegistration(): void;
  /** VisibilityManager hook: tier observation after a worker attach. */
  onWorkerAttached(): void;
  /** Lifecycle: the progressive enhance dispatch (promise). */
  dispatchProgressiveEnhance(generation: number, options?: EnhanceDispatchOptions): Promise<boolean>;
  /** Lifecycle: teardown through the engine destroyRoot. */
  destroyRuntimeRoot(): void;
  /** ResponsiveManager: drop the seeded grid metrics on a source refresh. */
  dropGridMetrics(): void;
  /** DiagnosisManager + console path for a failed source refresh. */
  reportRefreshFailure(message: string, error: unknown): void;
  /** Composition root: the relayout driver on the shared pool. */
  runRelayoutDriver(): void;
}

export interface ContextState {
  /** Live tracked-paragraph array; drivers splice it by reference. */
  readonly paragraphs: TrackedParagraph[];
  readonly runtimeEstablished: boolean;
  setRuntimeEstablished(value: boolean): void;
  readonly runtimeOptions: ResolvedEnhanceOptions | null;
  setRuntimeOptions(options: ResolvedEnhanceOptions | null): void;
  pushParagraph(item: TrackedParagraph): void;
  paragraphCandidates(root: Element, selector: string): Element[];
  beginLayoutWork(options?: LayoutWorkOptions): number;
  finishLayoutWorkAndObserve(expectedOperation?: number | null): boolean;
  dispatchRelayout(observedMeasures?: string | null): void;
  refreshRuntimeFromSource(options?: SourceRefreshOptions): void;
  relayout(): void;
  cancelCapturedLayoutForLatestGeometry(): void;
  ensureLayoutWorker(): void;
  syncLayoutWorker(): void;
  deactivateLayoutWorker(): void;
}

// belongsToRootScope: a candidate belongs to this root when its nearest
// scope-owning ancestor is absent, is the root itself, or lives outside the
// root. Mirror of the belongsToRootScope @JsFun in WebEnhancerSupport.kt;
// the closest guard keeps fake elements honest.
function belongsToRootScope(paragraph: Element, root: Element, selector: string): boolean {
  if (!paragraph.closest) return true;
  const owner = paragraph.closest(selector);
  return !owner || owner === root || !root.contains(owner);
}

function createContextState(
  root: HTMLElement,
  stateMachine: EnhancementStateMachine,
  scheduler: SchedulerRegistration,
  hooks: ContextStateHooks,
): ContextState {
  const paragraphs: TrackedParagraph[] = [];
  let runtimeEstablished = false;
  let runtimeOptions: ResolvedEnhanceOptions | null = null;

  // RuntimeEligibleMeasureSet: progressive staleness compares the
  // same leaf paragraphs that can actually enter the pipeline.
  // Measuring a host-owned outer <li> and later rendering its
  // child <p> changes the container's live width/measure, which
  // used to roll back every valid child as a false stale job.
  function paragraphCandidates(candidatesRoot: Element, selector: string): Element[] {
    const nodes = candidatesRoot.querySelectorAll(selector);
    const result: Element[] = [];
    for (let i = 0; i < nodes.length; i += 1) {
      const paragraph = nodes[i];
      if (belongsToRootScope(paragraph, candidatesRoot, ROOT_SELECTOR) &&
          shouldTryParagraph(paragraph)) {
        result.push(paragraph);
      }
    }
    return result;
  }

  function beginLayoutWork({ usesCapturedMeasure = false, captureSignatures = usesCapturedMeasure }: LayoutWorkOptions = {}): number {
    hooks.clearResponsiveRetarget();
    const viewportTypographyEntries = captureSignatures
      ? captureLayoutWorkViewportTypographyEntries(root)
      : [];
    let typographySig = "";
    if (captureSignatures) {
      for (let i = 1; i < viewportTypographyEntries.length; i++) {
        if (i > 1) typographySig += "\u001e";
        typographySig += viewportTypographyEntries[i].signature;
      }
    }
    const operation = stateMachine.beginLayoutWork({
      usesCapturedMeasure,
      signaturesCaptured: captureSignatures,
      geometrySignature: captureSignatures
        ? responsiveGeometrySignature(root)
        : "",
      measureSignature: captureSignatures
        ? hooks.paragraphMeasureSignature()
        : "",
      typographySignature: typographySig,
      maximumMeasure: captureSignatures && hooks.maximumMeasureActive(),
      viewportTypographyEntries,
    });
    hooks.setPendingCommittedMeasures("");
    hooks.stopTypographyObservation();
    hooks.observeContent();
    if (usesCapturedMeasure) hooks.observeLayoutWorkInputs();
    return operation;
  }

  function finishLayoutWorkAndObserve(expectedOperation: number | null = null): boolean {
    const transaction = stateMachine.transaction;
    const work = stateMachine.work;
    if (expectedOperation != null && expectedOperation !== transaction.layoutOperation) return false;
    const signaturesCaptured = work.signaturesCaptured;
    const rawGeometryChangedDuringWork = stateMachine.workInFlight &&
      (transaction.geometryRevision !== transaction.layoutWorkRevision || stateMachine.isInvalidated(InvalidationReason.ResponsiveCommit) ||
        (signaturesCaptured &&
          responsiveGeometrySignature(root) !== work.geometrySignature));
    // ObserverBaselineAfterUncapturedLayout: progressive enhancement mutates
    // the paragraph DOM while ResizeObserver is paused. Seed its committed
    // width, grid and typography baselines from that final DOM exactly once;
    // leaving the old values in place makes the observer's first delivery
    // schedule a redundant full-page layout and can immediately invalidate
    // a responsive snapshot that was just adopted.
    // FinishedTypographyBaselineRefresh: the finished DOM is the new stable
    // state, so the baseline must be re-read from it. Keeping a pre-job
    // baseline works only while nothing else compares a live signature
    // against it; the drag-time commit path does exactly that once the root
    // width settles, and a mixed native/rendered DOM after a cancelled job
    // would misread renderer output as a host typography change. Refreshing
    // here triggers no comparison of its own; the next one just starts from
    // the true current state.
    const currentTypography = typographySignature(root);
    // ResponsiveFinishSkipsDoomedSignatureReads: a finish that returns
    // through the responsive-commit branch stores no paragraph baseline.
    // Width movement puts every relayout finish onto that branch, and
    // relayout jobs capture no measure signature, so the live paragraph
    // signatures the finish read decided nothing and were discarded. Each
    // read cost one gBCR and one computed style per paragraph on DOM the
    // job had just dirtied. A finish reads the signatures only when it
    // compares them against a captured signature or stores them on the
    // unchanged path.
    const signaturesConsumedByFinish = !rawGeometryChangedDuringWork ||
      (work.usesCapturedMeasure &&
        work.measureSignature !== "");
    const currentParagraphWidths = signaturesConsumedByFinish &&
        !work.usesCapturedMeasure
      ? hooks.paragraphWidthSignature()
      : hooks.lastParagraphWidths();
    let currentMeasures: string;
    if (signaturesConsumedByFinish) {
      currentMeasures = work.usesCapturedMeasure && !rawGeometryChangedDuringWork
        ? hooks.lastParagraphMeasures()
        : hooks.paragraphMeasureSignature();
    } else {
      currentMeasures = hooks.lastParagraphMeasures();
    }
    const currentMaximumMeasure = hooks.maximumMeasureActive();
    // CapturedMeasureFollowUpCoalescing: atomic relayout prepares every
    // paragraph from a width snapshot taken when the job starts. If resize
    // activity stays in the same N×fontSize measure and does not cross the
    // exact maximum-snapshot boundary, that result is already valid for the
    // final geometry and a second job would reproduce identical DOM.
    const effectiveLayoutChangedDuringWork = signaturesConsumedByFinish
      ? (currentMeasures !== work.measureSignature ||
        currentMaximumMeasure !== work.maximumMeasure)
      : true;
    // RenderOutputTypographyIsNotAnInputChange: the renderer intentionally
    // changes paragraph line-height and positioning after it commits
    // measured line boxes. Comparing that output signature with the
    // captured native source signature schedules a redundant
    // destroy-and-enhance pass. Real font, style and viewport changes are
    // observed while work is in flight and cancel the captured job before
    // ready; completion only needs to reconcile geometry revisions that
    // survived those observers.
    const layoutInputsChangedDuringWork = stateMachine.isInvalidated(InvalidationReason.ResponsiveCommit) || (
      rawGeometryChangedDuringWork &&
      (!work.usesCapturedMeasure || effectiveLayoutChangedDuringWork)
    );
    // FinishedTypographyBaselineRefresh also covers the changed-inputs
    // branch: a follow-up commit runs on the next frame and compares a live
    // signature against this baseline, so both branches must leave the
    // baseline at the finished DOM state. Skipping it on the changed branch
    // leaves the pre-job value (empty before the first completed job) and
    // the follow-up commit misreads renderer output as a host typography
    // change.
    hooks.setTypographyBaseline(currentTypography);
    stateMachine.completionGateOpen = false;
    stateMachine.finishLayoutWork();
    hooks.clearResponsiveRetarget();
    hooks.stopLayoutWorkInputObservation();
    if (layoutInputsChangedDuringWork) {
      // A non-atomic progressive job may have observed intermediate widths,
      // so it must force one latest-width pass. Captured-measure relayout
      // can let the normal final measure comparison decide on the next
      // frame.
      stateMachine.invalidate(InvalidationReason.ResponsiveCommit);
      if (work.usesCapturedMeasure) stateMachine.clearInvalidation(InvalidationReason.ResponsiveRelayout);
      else stateMachine.invalidate(InvalidationReason.ResponsiveRelayout);
      hooks.ensureViewportResizeListener();
      hooks.scheduleResponsiveGeometryCommit();
      return true;
    }
    if (stateMachine.isInvalidated(InvalidationReason.ContentDrift) && !hooks.contentProbeFramePending()) {
      // ContentOnlyFinishCommit: an uncaptured job may have raced a host
      // edit. Resolve the flag with the read-only probe, never with the
      // commit path: the records are usually this job's own output, and a
      // commit scheduled on them alone enters the offscreen deferred queue,
      // where it later fires a width commit inside the drag debounce
      // window. The probe clears an engine-owned flag without scheduling
      // anything and schedules the commit itself only on proven drift. The
      // finish still falls through to store its baselines, exactly like a
      // finish without the flag.
      hooks.ensureViewportResizeListener();
      hooks.scheduleContentDriftProbeFrame(transaction.layoutOperation);
    }
    stateMachine.clearInvalidation(InvalidationReason.ResponsiveCommit);
    stateMachine.clearInvalidation(InvalidationReason.ResponsiveRelayout);
    hooks.settleFinishedWork(currentMeasures, currentParagraphWidths);
    return true;
  }

  function ensureLayoutWorker(): void {
    // WorkerPolledScheduling: attach before dispatch so the job is built
    // coordinated from the start and every slice comes from a grant. The
    // dispatch task runs inside the coordinator frame, so the first polled
    // grant lands in the same frame under the shared budget.
    const pool = globalServices().coordination.layoutJobPool;
    pool.attach(root);
    stateMachine.workerAttached = true;
    scheduler.registerWorker(root);
  }

  function syncLayoutWorker(): void {
    const pool = globalServices().coordination.layoutJobPool;
    if (!stateMachine.workerAttached) return;
    scheduler.setWorkerActive(pool.hasJob(root));
    hooks.onWorkerAttached();
    scheduler.requestWorkerFrame();
  }

  function deactivateLayoutWorker(): void {
    if (!stateMachine.workerAttached) return;
    hooks.deactivateWorkerRegistration();
  }

  function dispatchRelayout(observedMeasures: string | null = null): void {
    if (!stateMachine.runtimeActive) {
      finishLayoutWorkAndObserve();
      return;
    }
    beginLayoutWork({ usesCapturedMeasure: true, captureSignatures: false });
    stateMachine.markWorkAsRelayout();
    // Callers on the commit paths pass the signature they just computed;
    // recomputing here is reserved for dispatches that never went through
    // a commit pass (snapshot-miss recovery).
    hooks.setPendingCommittedMeasures(observedMeasures ?? hooks.paragraphMeasureSignatureFromObserved());
    stateMachine.dispatched = true;
    stateMachine.completionGateOpen = true;
    ensureLayoutWorker();
    // RunToCompletionAnchorBracket: relayout dispatches take the same
    // bracket as enhance dispatches; an uncoordinated relayout runs its
    // whole job synchronously inside this call.
    const relayoutAnchor = captureViewportAnchor(root);
    try {
      hooks.runRelayoutDriver();
    } finally {
      compensateViewportAnchor(root, relayoutAnchor);
      releaseNativeScrollAnchoring(root);
    }
    syncLayoutWorker();
  }

  function refreshRuntimeFromSource({ revalidateSnapshotFont = true }: SourceRefreshOptions = {}): void {
    // A source refresh replaces the rendered paragraphs, so the seeded
    // grid metrics are for nodes about to leave the tree; drop them and
    // let the observer re-seed the rebuilt paragraphs.
    hooks.dropGridMetrics();
    const generation = hooks.currentGeneration();
    if (stateMachine.runtimeActive) {
      // ResponsiveNativeBacking: pre-broken Tiqian lines cannot reflow
      // while a new width or typography is being prepared. Restore the
      // complete semantic source first so every remaining paragraph
      // responds through the host cascade while viewport-near paragraphs
      // are enhanced atomically.
      hooks.destroyRuntimeRoot();
      stateMachine.runtimeActive = false;
    }
    hooks.dispatchProgressiveEnhance(generation, { revalidateSnapshotFont }).catch((error) => {
      if (!root.isConnected || generation !== hooks.currentGeneration()) return;
      finishLayoutWorkAndObserve();
      hooks.reportRefreshFailure("Tiqian Web source refresh failed", error);
    });
  }

  // Host-driven relayout (a container size change or an external style
  // refresh): mark both responsive bits so the next commit treats the
  // geometry as unsettled, then schedule it through the coordinator frame.
  function relayout(): void {
    if (!root.isConnected || !stateMachine.dispatched) return;
    stateMachine.invalidate(InvalidationReason.ResponsiveCommit);
    stateMachine.invalidate(InvalidationReason.ResponsiveRelayout);
    hooks.scheduleResponsiveGeometryCommit();
  }

  function cancelCapturedLayoutForLatestGeometry(): void {
    if (!stateMachine.workInFlight || !stateMachine.work.usesCapturedMeasure) return;
    hooks.clearResponsiveRetarget();
    stateMachine.abortLayoutWork();
    hooks.stopLayoutWorkInputObservation();
    hooks.cancelRootLayoutWork();
    deactivateLayoutWorker();
    hooks.advanceTypographyBaselineAfterCancellation();
    stateMachine.invalidate(InvalidationReason.ResponsiveCommit);
    stateMachine.invalidate(InvalidationReason.ResponsiveRelayout);
    hooks.setCommittedMeasureLedger("");
    hooks.ensureViewportResizeListener();
    hooks.scheduleResponsiveGeometryCommit();
  }

  return {
    paragraphs,
    get runtimeEstablished() {
      return runtimeEstablished;
    },
    setRuntimeEstablished(value: boolean) {
      runtimeEstablished = value;
    },
    get runtimeOptions() {
      return runtimeOptions;
    },
    setRuntimeOptions(options: ResolvedEnhanceOptions | null) {
      runtimeOptions = options;
    },
    pushParagraph(item: TrackedParagraph) {
      paragraphs.push(item);
    },
    paragraphCandidates,
    beginLayoutWork,
    finishLayoutWorkAndObserve,
    dispatchRelayout,
    refreshRuntimeFromSource,
    relayout,
    cancelCapturedLayoutForLatestGeometry,
    ensureLayoutWorker,
    syncLayoutWorker,
    deactivateLayoutWorker,
  };
}

export { createContextState };
