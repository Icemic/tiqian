// EffectSync — the host content signal for one enhanced element
// (core-neutral parts ruling). Owns the content invalidation source, the
// tainted-paragraph set, the read-only drift probe frame and the content
// reconcile dispatch. HostContentSignal: childList and characterData
// mutations on the live DOM are the only host content signals; attributes
// and inline size already have their own observers. The observer stays
// connected across layout work on purpose: engine commits also produce
// records, and the drift probe disproves those by identity instead of
// disconnecting and losing host edits that land mid-flight.

import {
  createContentInvalidationSource,
  classifyContentMutationRecords,
  belongsToRootScope,
} from "../../sampler/observers.js";
import type { ContentInvalidationSource } from "../../sampler/observers.js";
import type { ContentReconcileResult } from "../content-reconcile.js";
import { paragraphWidthSignature } from "../../sampler/signatures.js";
import type { RawDomParagraphRecord } from "../context/enhance-context.js";
import type { FrameTaskCallback } from "../coordination/coordination-service.js";
import { InvalidationReason } from "./state.js";
import type { EnhancementStateMachine } from "./state-machine.js";
import type { SchedulerRegistration } from "./scheduler-registration.js";
import type { LayoutWorkOptions } from "./context-state.js";

// Drift report shape returned by the content-reconcile probe.
interface ContentDriftReport {
  drifted?: number;
  dead?: number;
  unknown?: number;
  rawDom?: number;
}

export interface EffectSyncHooks {
  /** DomWriteLayer: the rendered raw-DOM paragraph records in DOM order. */
  renderedRawDomParagraphs(): Iterable<[Element, RawDomParagraphRecord]>;
  /** Composition root: the read-only drift probe with context. */
  probeRootContentDrift(): ContentDriftReport | null;
  /** Composition root: the reconcile driver with context and pool. */
  reconcileRoot(paragraphs: Element[]): ContentReconcileResult | null;
  /** ContextState: paragraph candidate enumeration for the runtime options. */
  paragraphCandidates(): Element[];
  /** ContextState: the tracked paragraphs' source elements. */
  trackedParagraphSources(): Element[];
  /** ContextState: layout-work drive surfaces. */
  beginLayoutWork(options?: LayoutWorkOptions): number;
  finishLayoutWorkAndObserve(): boolean;
  ensureLayoutWorker(): void;
  syncLayoutWorker(): void;
  cancelCapturedLayoutForLatestGeometry(): void;
  /** ResponsiveManager: geometry commit + measure baselines. */
  scheduleResponsiveGeometryCommit(): void;
  paragraphMeasureSignature(): string;
  setLastParagraphMeasures(value: string): void;
  setLastParagraphWidths(value: string): void;
}

export interface EffectSync {
  observeContent(): void;
  stopContentObservation(): void;
  dispatchContentReconcile(paragraphs: Element[]): boolean;
  scheduleContentDriftProbeFrame(operation: number): void;
  contentProbeFramePending(): boolean;
  takeContentTainted(): Element[];
  /** Candidates the runtime never reached: candidates minus rendered sources. */
  strandedSourceParagraphs(): Element[];
}

function createEffectSync(
  root: HTMLElement,
  stateMachine: EnhancementStateMachine,
  scheduler: SchedulerRegistration,
  hooks: EffectSyncHooks,
): EffectSync {
  let contentInvalidation: ContentInvalidationSource | null = null;
  let contentProbeFrame: FrameTaskCallback | null = null;
  const contentTainted = new Set<Element>();

  function observeContent(): void {
    if (!contentInvalidation) {
      contentInvalidation = createContentInvalidationSource(root, {
        onRecords: (records) => handleContentMutationRecords(records),
        belongsToRootScope,
        getRawDomParagraphs: () => hooks.renderedRawDomParagraphs(),
      });
    }
    contentInvalidation.start();
  }

  function stopContentObservation(): void {
    contentInvalidation?.stop();
    contentInvalidation = null;
    if (contentProbeFrame) scheduler.cancelFrame(contentProbeFrame);
    contentProbeFrame = null;
    contentTainted.clear();
    stateMachine.clearInvalidation(InvalidationReason.ContentDrift);
  }

  function handleContentMutationRecords(records: MutationRecord[]): void {
    if (!stateMachine.dispatched) return;
    const { taintedParagraphs, paragraphSignal, structureSignal } =
      classifyContentMutationRecords(records, {
        rawDomParagraphFor: (node) => contentInvalidation?.paragraphFor(node) ?? null,
        belongsToRootScope,
        root,
      });
    for (const paragraph of taintedParagraphs) contentTainted.add(paragraph);
    if (!paragraphSignal && !structureSignal) return;
    stateMachine.invalidate(InvalidationReason.ContentDrift);
    if (structureSignal && (!stateMachine.workInFlight || stateMachine.runtimeActive)) {
      // StructureChangesCommitDirectly: a childList record outside every
      // paragraph cannot be engine render output in the steady state, so no
      // probe is needed and waiting for one would only delay candidate
      // adoption. During initial enhancement the engine still installs its
      // own scaffolding at root level, so an in-flight signal there keeps
      // the probe path.
      hooks.scheduleResponsiveGeometryCommit();
      return;
    }
    if (stateMachine.workInFlight) {
      // MutationObserverDeliveryIsAsync: records land in a microtask after the
      // engine's synchronous commit batch, so a captured job may already be
      // rendering stale content. Probe drift read-only at the next frame; an
      // engine-owned batch is disproven there without cancelling anything.
      scheduleContentDriftProbeFrame(stateMachine.transaction.layoutOperation);
      return;
    }
    // EngineRecordsProvenIdleStayFree: a finished job's own records arrive in
    // this microtask. Scheduling a commit on them alone would fire the width
    // commit early and break the drag debounce, so prove host intent with the
    // read-only probe first. Only real drift, taint or dead tracking schedules
    // work; the probe clears the flag otherwise.
    probeContentDrift();
  }

  function probeContentDrift(): void {
    // Mid-job takeovers publish fresh raw-DOM backup fragments; adopt them before
    // reading raw-DOM backup identity so a host edit made during enhancement is
    // already under observation when the probe runs.
    contentInvalidation?.syncRawDom();
    const drift = hooks.probeRootContentDrift();
    const drifted = (drift?.drifted || 0) + (drift?.dead || 0) + (drift?.unknown || 0) +
      (drift?.rawDom || 0);
    const tainted = contentTainted.size;
    if (drifted === 0 && tainted === 0) {
      // Engine-owned output disproven; nothing host-authored is pending.
      stateMachine.clearInvalidation(InvalidationReason.ContentDrift);
      return;
    }
    if (!stateMachine.workInFlight) {
      hooks.scheduleResponsiveGeometryCommit();
      return;
    }
    // MidFlightHostEditCancelsCapturedJob: only a captured job is bound to a
    // pre-edit snapshot. Uncaptured work lowers live content per slice and
    // the finish funnel picks the edit up.
    if (stateMachine.work.usesCapturedMeasure) {
      hooks.cancelCapturedLayoutForLatestGeometry();
    }
  }

  function dispatchContentReconcile(paragraphs: Element[]): boolean {
    if (!stateMachine.runtimeActive) return false;
    hooks.beginLayoutWork({ usesCapturedMeasure: true, captureSignatures: false });
    stateMachine.dispatched = true;
    stateMachine.completionGateOpen = true;
    hooks.ensureLayoutWorker();
    const outcome = hooks.reconcileRoot(paragraphs);
    if (outcome?.outcome !== "work") {
      // ReconcileIdleReleasesWorkSlot: the records were engine-owned output
      // or touched nothing tracked. Release the work slot without a ready
      // round-trip so the next signal starts clean.
      hooks.finishLayoutWorkAndObserve();
      // ReconcileAbsorbsLiveGeometry: a reconcile renders at the live width,
      // and an idle verdict certifies the current DOM as settled output for
      // exactly this geometry. Earlier finishes that took responsive early
      // returns never stored a paragraph baseline, so the commit fall-through
      // would compare a stale signature and dispatch a phantom relayout.
      hooks.setLastParagraphMeasures(hooks.paragraphMeasureSignature());
      hooks.setLastParagraphWidths(paragraphWidthSignature(root));
      return false;
    }
    hooks.syncLayoutWorker();
    return true;
  }

  function scheduleContentDriftProbeFrame(operation: number): void {
    if (contentProbeFrame) return;
    const frame: FrameTaskCallback = () => {
      contentProbeFrame = null;
      if (!root.isConnected || operation !== stateMachine.transaction.layoutOperation) return;
      probeContentDrift();
    };
    contentProbeFrame = frame;
    scheduler.requestFrame(frame);
  }

  // StrandedEnhanceResume difference (dissolved from root-state.ts): the
  // candidates the runtime never reached, keyed against the live tracked
  // paragraph sources.
  function strandedSourceParagraphs(): Element[] {
    const candidates = hooks.paragraphCandidates();
    const sources = hooks.trackedParagraphSources();
    if (sources.length === 0) return candidates;
    const renderedSources = new Set<Element>(sources);
    const result: Element[] = [];
    for (let j = 0; j < candidates.length; j += 1) {
      if (!renderedSources.has(candidates[j])) result.push(candidates[j]);
    }
    return result;
  }

  return {
    observeContent,
    stopContentObservation,
    dispatchContentReconcile,
    scheduleContentDriftProbeFrame,
    contentProbeFramePending() {
      return contentProbeFrame != null;
    },
    takeContentTainted() {
      const tainted = Array.from(contentTainted);
      contentTainted.clear();
      return tainted;
    },
    strandedSourceParagraphs,
  };
}

export { createEffectSync };
