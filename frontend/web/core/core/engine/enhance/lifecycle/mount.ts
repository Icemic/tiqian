// Mount — the connection lifecycle of one enhanced element (core-neutral
// parts ruling). Mechanical port of the former session's mount verb, its
// per-mount completion funnel, the deferred-teardown unmount, the settle
// sequence, the raw-DOM-move re-adoption and the connected-lifecycle
// restart. The detach attribute snapshot lives here because unmount writes
// it and the re-adoption check reads it. Lifecycle files may hold the
// EnhancedElementContext, so the context-bound snapshot restore is called
// directly; engine teardown helpers arrive through env hooks built by the
// composition root.

import { isLoadedSnapshotAdopted, restoreLoadedSnapshot } from "../../../sampler/snapshot/loaded-snapshots.js";
import { releaseNativeScrollAnchoring } from "../../coordination/viewport-anchor.js";
import { fragmentedBorderBoxInlineSize } from "../../../sampler/signatures.js";
import { hasStrongEmphasis } from "../../eligibility.js";
import { InvalidationReason } from "../state.js";
import { OBSERVED_ATTRIBUTES } from "../options-ledger.js";
import { SNAPSHOT_RENDER_FONT_ATTRIBUTE } from "../snapshot-adoption.js";
import type { EnhancementStateMachine } from "../state-machine.js";
import type { ContextState } from "../context-state.js";
import type { OptionsLedger } from "../options-ledger.js";
import type { SnapshotAdoption } from "../snapshot-adoption.js";
import type { TypographyManager } from "../typography.js";
import type { VisibilityManager } from "../visibility.js";
import type { ResponsiveManager } from "../responsive.js";
import type { EffectSync } from "../effect-sync.js";
import type { EventChannel, CompletionEventHandler, EnhancementDiagnostics, EnhancementEvent } from "../event-channel.js";
import type { SchedulerRegistration } from "../scheduler-registration.js";
import type { ForeignGuard } from "./foreign-guard.js";
import type { InitialEnhance } from "./initial-enhance.js";
import type { EnhancedElementContext } from "../../context/enhance-context.js";

// Completion event detail the drivers report through the EventChannel.
interface ReadyEventDetail {
  snapshot?: boolean;
  runtimeEnhancedCount: number;
  snapshotCount: number;
  enhancedCount: number;
  durationMs: number;
  maxSliceMs: number;
  relayout?: boolean;
  stale?: boolean;
}

export interface MountEnv {
  stateMachine: EnhancementStateMachine;
  contextState: ContextState;
  optionsLedger: OptionsLedger;
  snapshotAdoption: SnapshotAdoption;
  typography: TypographyManager;
  visibility: VisibilityManager;
  responsive: ResponsiveManager;
  effectSync: EffectSync;
  eventChannel: EventChannel;
  scheduler: SchedulerRegistration;
  foreignGuard: ForeignGuard;
  initialEnhance: InitialEnhance;
  /** Engine destroyRoot through the shared layout job pool. */
  destroyRuntimeRoot(): void;
  /** Engine detachRoot through the shared layout job pool. */
  detachRuntimeRoot(): void;
  /** Context-bound detachLoadedSnapshot. */
  detachLoadedSnapshot(): void;
  /** Context-bound releasePreparedValueStyleRoot sweep. */
  releasePreparedValueStyleRoot(): void;
  /** Shared pool detach for a disconnected worker-attached root. */
  detachRootFromPool(): void;
}

export interface Mount {
  mount(): Promise<void>;
  unmount(): void;
  restartConnectedLifecycle(): void;
}

function createMount(
  root: HTMLElement,
  context: EnhancedElementContext,
  env: MountEnv,
): Mount {
  const {
    stateMachine, contextState, optionsLedger, snapshotAdoption, typography,
    visibility, responsive, effectSync, eventChannel, scheduler, foreignGuard,
    initialEnhance,
  } = env;

  // Detach attribute snapshot: captured by unmount, compared by the
  // raw-DOM-move re-adoption check, cleared by the settle microtask.
  let detachAttributeSnapshot: (string | null)[] | null = null;

  function removeReadyListener(): void {
    eventChannel.setInternalHandler(null);
    eventChannel.detachCompletionListeners();
  }

  function clearLifecycleDiagnostics(): void {
    context.diagnosis.clear("tiqianCapabilityIssue");
    context.diagnosis.clear("tiqianEnhanceMs");
    context.diagnosis.clear("tiqianLoadMs");
    context.diagnosis.clear("tiqianMaxSliceMs");
    context.diagnosis.clear("tiqianRelayoutMs");
    context.diagnosis.clear("tiqianRelayoutMaxSliceMs");
    context.diagnosis.clear("tiqianFontWait");
    context.diagnosis.clear("tiqianSnapshotLiveIssue");
    context.diagnosis.clear("tiqianSnapshotCount");
    context.diagnosis.clear("tiqianSnapshotMiss");
  }

  // Per-mount completion funnel: classifies each completion event before the
  // callback subscribers are notified. Ported from the former session's
  // ready listener body.
  function buildFunnel(generation: number, loadStartedAt: number): CompletionEventHandler {
    let initialReadyReported = false;
    let pendingLoadMs: number | null = null;
    return (event) => {
      if (
        generation !== context.generation || !stateMachine.dispatched ||
        !stateMachine.completionGateOpen
      ) return;
      const detail = (event.detail ?? {}) as Partial<ReadyEventDetail>;
      if (stateMachine.snapshotAdopted && snapshotAdoption.snapshotEnhancedCount() > 0) {
        const snapshotCount = snapshotAdoption.snapshotEnhancedCount();
        const runtimeEnhancedCount = detail.snapshot
          ? 0
          : Number.isFinite(detail.runtimeEnhancedCount)
            ? detail.runtimeEnhancedCount as number
            : Number.isFinite(detail.snapshotCount)
              ? Math.max(0, (Number(detail.enhancedCount) || 0) - snapshotCount)
              : Math.max(0, Number(detail.enhancedCount) || 0);
        const enhancedCount = runtimeEnhancedCount + snapshotCount;
        context.diagnosis.set("tiqianSnapshotCount", String(snapshotAdoption.snapshotEnhancedCount()));
        root.setAttribute("data-tiqian-enhanced-count", String(enhancedCount));
        try {
          detail.runtimeEnhancedCount = runtimeEnhancedCount;
          detail.snapshotCount = snapshotCount;
          detail.enhancedCount = enhancedCount;
        } catch {
          // The root attributes remain the stable observable count contract if a
          // host supplied a frozen CustomEvent detail object.
        }
      }
      const { durationMs, maxSliceMs, relayout, stale } = detail;
      if (relayout) {
        if (Number.isFinite(durationMs)) context.diagnosis.set("tiqianRelayoutMs", (durationMs as number).toFixed(1));
        if (Number.isFinite(maxSliceMs)) {
          context.diagnosis.set("tiqianRelayoutMaxSliceMs", (maxSliceMs as number).toFixed(1));
        }
        // CommittedMeasureLedger: forced commits (viewport revalidation,
        // stale follow-ups) skip against what the last clean relayout
        // actually committed, never against dispatch-time bookkeeping. The
        // runtime reports content reconciles through this same event kind,
        // so only jobs this element dispatched as width relayouts may move
        // the ledger.
        if (stateMachine.work.kind === "Relayout") {
          if (!stale) {
            responsive.setCommittedMeasureLedger(responsive.pendingCommittedMeasures());
          } else {
            // A stale finish leaves a mix of old- and new-measure
            // paragraphs, which no single signature describes — a ledger
            // still holding the pre-mix cell would let a forced convergence
            // pass skip and strand the mix. Invalidate so the next forced
            // pass always dispatches.
            responsive.setCommittedMeasureLedger("");
          }
        }
      } else {
        if (Number.isFinite(durationMs)) context.diagnosis.set("tiqianEnhanceMs", (durationMs as number).toFixed(1));
        if (Number.isFinite(maxSliceMs)) context.diagnosis.set("tiqianMaxSliceMs", (maxSliceMs as number).toFixed(1));
        if (!initialReadyReported) {
          initialReadyReported = true;
          pendingLoadMs = Date.now() - loadStartedAt;
          context.diagnosis.set("tiqianLoadMs", (Date.now() - loadStartedAt).toFixed(1));
          // The first non-relayout ready event is the one-time work's
          // completion: runtime loading, snapshot adoption and the initial
          // enhance all landed.
          eventChannel.finishCompletion();
        }
      }
      if (stale) stateMachine.invalidate(InvalidationReason.ResponsiveCommit);
      if (stale) stateMachine.invalidate(InvalidationReason.ResponsiveRelayout);
      // Completion funnel (ruling R6): every observed completion notifies the
      // callback subscribers. The DOM CustomEvent dispatches stay the single
      // observable event surface; this channel is the plain TypeScript mirror
      // of the two enum events.
      const completionEvent: EnhancementEvent =
        event.type === "tiqian:relayout-ready" ? "relayout-ready" : "ready";
      const diagnostics: EnhancementDiagnostics = {
        enhancedCount: Number.isFinite(detail.enhancedCount) ? detail.enhancedCount : undefined,
        snapshotCount: Number.isFinite(detail.snapshotCount) ? detail.snapshotCount : undefined,
        maxSliceMs: Number.isFinite(maxSliceMs) ? maxSliceMs : undefined,
        snapshot: detail.snapshot ? true : undefined,
        enhanceMs: !relayout && Number.isFinite(durationMs) ? durationMs : undefined,
        relayoutMs: relayout && Number.isFinite(durationMs) ? durationMs : undefined,
        loadMs: pendingLoadMs ?? undefined,
      };
      pendingLoadMs = null;
      eventChannel.emit(completionEvent, diagnostics);
      contextState.finishLayoutWorkAndObserve();
    };
  }

  // MountCompletionPromise: the returned promise tracks the one-time work
  // of this mount, runtime loading included. It only ever resolves; failures
  // surface through the capability-issue markers, so hosts and custom-element
  // callbacks can await it without handling rejections.
  function mount(): Promise<void> {
    // ForeignEnhancedRootMountNoOp: one context owns one root's lifecycle. A
    // fresh context mounting over a root a foreign context already drove to
    // the terminal state would re-lower the rendered DOM (producing invalid
    // inline-object geometry markers) while the owning context's
    // identity-based drift reconcile restores its own records back, and the
    // two contexts rewrite the root forever. Stay inert instead: no ledger
    // sync, no page-global registration, no intersection observation. The
    // owning context keeps observing the root, and its attribute reflection
    // answers option changes written onto the root.
    if (foreignGuard.rootIsForeignEnhanced()) {
      return Promise.resolve();
    }
    // AppliedLedgerMountSync: attribute changes made through property setters
    // or present before construction never passed through updateOptions; sync
    // the ledger from the live attributes so the next reflection diffs
    // against what the root actually carries.
    optionsLedger.syncFromAttributes();
    scheduler.register();
    visibility.observeIntersection();
    if (canAdoptRawDomMoveReconnection()) {
      adoptRawDomMoveReconnection();
      return Promise.resolve();
    }
    // ReconnectedSourceReclamation: detached roots keep their source backing in
    // weak runtime/snapshot state so navigation can discard them without
    // rebuilding an invisible old article. A real reconnection is the one case
    // that needs to pay the restoration cost before starting a new lifecycle.
    if (!stateMachine.connected) {
      if (isLoadedSnapshotAdopted(root)) restoreLoadedSnapshot(root, context);
      if (stateMachine.runtimeActive) env.destroyRuntimeRoot();
      stateMachine.runtimeActive = false;
    }
    stateMachine.connect(optionsLedger.disabled);
    clearLifecycleDiagnostics();
    // ReversibleDisabledEnhancement: the Boolean attribute is the complete
    // opt-out contract. Keep semantic SSR children live and avoid stylesheet,
    // font, snapshot, runtime and observer work until the host removes it.
    if (optionsLedger.disabled) return Promise.resolve();
    const completion = eventChannel.beginCompletion();
    snapshotAdoption.setSnapshotFontRejectedAttempt("");
    const generation = context.update();
    typography.clearInitialFontRetry();
    stateMachine.completionGateOpen = false;
    stateMachine.dispatched = false;
    stateMachine.snapshotAdopted = isLoadedSnapshotAdopted(root);
    snapshotAdoption.setSnapshotEnhancedCount(0);
    const loadStartedAt = Date.now();
    // OptInStrongSnapshotExclusion: v1 snapshots contain only plain paragraphs,
    // so they cannot claim that a semantic <strong> was lowered to emphasis
    // marks. Keep the default bold path eligible for snapshots; an explicit
    // mapping request with actual <strong> content must enter the runtime.
    const strongEmphasisRuntimeRequired =
      optionsLedger.strongAsEmphasisMarks && hasStrongEmphasis(root);
    // SnapshotFirstInputBeforeRuntimeCompile: even a mixed root can prove and
    // display its keyed snapshot without Kotlin. Under Edge JITless, eagerly
    // importing the full runtime for one unkeyed paragraph delays the first
    // wheel event before adoption has even started. The runtime is no longer
    // loaded here; the context state owns its own runtime record.
    removeReadyListener();
    typography.stopTypographyObservation();
    eventChannel.setInternalHandler(buildFunnel(generation, loadStartedAt));
    eventChannel.attachCompletionListeners();
    responsive.ensureViewportResizeListener();

    // DeferredEnhanceErrorContract: one failure handler serves the gate
    // below and the frame task it queues. The coordinator's frame loop guards
    // its callbacks with a synchronous try/catch, which cannot observe an
    // async task's rejection, and the gate's own catch resolved the moment
    // the task was queued — so without routing the task's rejection here, a
    // runtime import or enhance failure inside the frame task became an
    // unhandled rejection: no RuntimeLoadFailed marker, the ready listener
    // left attached, and consumers awaiting tiqian:ready hanging forever.
    // EnhanceAbortControllerSlot: one AbortController per connected lifecycle,
    // published on the state machine transaction slot. Disconnect and restart
    // abort it; every pipeline await below races against its signal.
    const enhanceAbortController = initialEnhance.beginEnhanceAbortController();
    initialEnhance.runHostCascadeGate(generation, strongEmphasisRuntimeRequired, enhanceAbortController.signal)
      .catch((error) => initialEnhance.failInitialEnhance(generation, error));
    return completion;
  }

  function unmount(): void {
    // RawDomMoveTeardownDeferral: React, Svelte and other reconcilers move a
    // node by removing and re-inserting it inside one synchronous commit.
    // Settling the disconnection synchronously destroys a rendered article
    // that never left the host raw-DOM backup, so the settle runs one microtask later.
    // A same-task reconnection then re-enters the live lifecycle through
    // RawDomMoveAdoption. A real navigation settles exactly as before, still
    // before the next frame. The remount variant of
    // resize-destroy-transient.test.mjs holds this contract.
    stateMachine.enterDeferredTeardown();
    detachAttributeSnapshot = OBSERVED_ATTRIBUTES.map(
      (name) => root.getAttribute(name),
    );
    queueMicrotask(() => {
      stateMachine.closeDeferredTeardownWindow();
      detachAttributeSnapshot = null;
      if (!root.isConnected) settleDisconnection();
    });
  }

  function settleDisconnection(): void {
    initialEnhance.abortEnhancePipeline();
    // A disconnection ends the one-time work: no ready event will follow for
    // an aborted pipeline, so settle the mount promise here.
    eventChannel.finishCompletion();
    scheduler.unregister();
    scheduler.cancelFrame(responsive.responsiveCommitCallback());
    releaseNativeScrollAnchoring(root);
    visibility.stopIntersectionObservation();
    visibility.stopParagraphTierObservation();
    // DetachedNavigationDisposal: the settle is a detach, not a destroy. The
    // rawDom paragraph backups stay on the context so a reconnection can
    // reclaim the source (ReconnectedSourceReclamation); destroy() would
    // clear them and strand the rendered DOM. Only the diagnosis listeners
    // drop here; the detach calls below release the prepared-style state.
    context.diagnosis.dispose();
    stateMachine.settleDisconnection();
    responsive.clearResponsiveRetarget();
    typography.clearInitialFontRetry();
    context.diagnosis.clear("tiqianFontWait");
    removeReadyListener();
    typography.stopTypographyObservation();
    typography.stopLayoutWorkInputObservation();
    responsive.stopWidthObservation();
    effectSync.stopContentObservation();
    // DetachedNavigationDisposal: swup and other HTML routers remove an entire
    // old article synchronously. Reconstructing every source paragraph here
    // blocks their scroll handoff and can visibly change the outgoing page.
    // Keep the backing in weak state for a possible reconnection, but cancel all
    // work and release document-scoped styles without touching detached DOM.
    if (stateMachine.snapshotAdopted || isLoadedSnapshotAdopted(root)) {
      env.detachLoadedSnapshot();
    }
    if (stateMachine.runtimeActive) env.detachRuntimeRoot();
    // The detach calls above release the prepared styles for their own
    // backing; this idempotent sweep covers any remainder so the settle
    // releases document-scoped styles as completely as a destroy would.
    env.releasePreparedValueStyleRoot();
    if (stateMachine.workerAttached) {
      // tiqian:detach already cancelled the job, so the pool's detach has no
      // in-flight work to finish on this disconnected root.
      env.detachRootFromPool();
      stateMachine.workerAttached = false;
    }
    snapshotAdoption.releaseSnapshotFontSession();
    root.removeAttribute(SNAPSHOT_RENDER_FONT_ATTRIBUTE);
  }

  function canAdoptRawDomMoveReconnection(): boolean {
    if (stateMachine.connected || !stateMachine.deferredTeardown) return false;
    if (!stateMachine.runtimeActive || optionsLedger.disabled) return false;
    if (stateMachine.snapshotAdopted || isLoadedSnapshotAdopted(root)) {
      // Snapshot-based raw-DOM backup keeps the restore and re-adopt path. Its backing is
      // cheap to rebuild and shares document-scoped styles with the runtime.
      return false;
    }
    const snapshot = detachAttributeSnapshot;
    if (snapshot == null) return false;
    return OBSERVED_ATTRIBUTES.every(
      (name, index) => root.getAttribute(name) === snapshot[index],
    );
  }

  // RawDomMoveAdoption: a reconnection inside the deferred settle window is
  // a host raw-DOM backup move. The committed LayoutResult, the snapshot font session
  // and any in-flight job stayed valid through the move, so only the
  // observers and the geometry baseline need re-entry. A width change from
  // the move routes through the responsive commit path and relayouts in
  // place; a changed font context routes through the typography check and
  // refreshes from source. Observed attribute edits during the gap reject
  // adoption and take the full restart path instead.
  function adoptRawDomMoveReconnection(): void {
    detachAttributeSnapshot = null;
    stateMachine.adoptRawDomMoveReconnection();
    responsive.ensureViewportResizeListener();
    responsive.observeWidth();
    typography.observeTypography();
    effectSync.observeContent();
    responsive.setLastObservedWidth(fragmentedBorderBoxInlineSize(root));
    responsive.scheduleResponsiveGeometryCommit();
    typography.scheduleTypographyCheck();
  }

  function restartConnectedLifecycle(): void {
    initialEnhance.abortEnhancePipeline();
    // The per-root context outlives every connection cycle: its rawDom
    // records are the source backing the teardown below, so the restart
    // restores through the same context. The mount that follows bumps the
    // generation and supersedes any in-flight work.
    stateMachine.bumpEnhanceRequest();
    stateMachine.dispatched = false;
    stateMachine.completionGateOpen = false;
    stateMachine.snapshotAdopted = false;
    snapshotAdoption.setSnapshotEnhancedCount(0);
    removeReadyListener();
    typography.clearInitialFontRetry();
    typography.stopTypographyObservation();
    typography.stopLayoutWorkInputObservation();
    responsive.stopWidthObservation();
    effectSync.stopContentObservation();
    restoreLoadedSnapshot(root, context);
    if (stateMachine.runtimeActive) env.destroyRuntimeRoot();
    stateMachine.runtimeActive = false;
    snapshotAdoption.releaseSnapshotFontSession();
    root.removeAttribute(SNAPSHOT_RENDER_FONT_ATTRIBUTE);
    releaseNativeScrollAnchoring(root);
    if (root.isConnected) mount();
  }

  return {
    mount,
    unmount,
    restartConnectedLifecycle,
  };
}

export { createMount };
