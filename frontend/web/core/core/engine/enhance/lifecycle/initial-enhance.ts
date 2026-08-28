// InitialEnhance — the one-time initial enhancement lifecycle of one
// enhanced element (core-neutral parts ruling). Mechanical port of the
// former session's host cascade gate, abort-controller slot, failure
// handler and initial enhance run. Lifecycle files may hold the
// EnhancedElementContext, so the context-bound snapshot restore is called
// directly.

import { raceAbort } from "../../abort-race.js";
import { ensureTiqianStyles } from "../../loaders/styles.js";
import { awaitInitialTypographyFonts } from "../../loaders/font-loader.js";
import { typographyElements } from "../../../sampler/signatures.js";
import { isLoadedSnapshotAdopted, restoreLoadedSnapshot } from "../../../sampler/snapshot/loaded-snapshots.js";
import { releaseNativeScrollAnchoring } from "../../coordination/viewport-anchor.js";
import { snapshotCompletionSelector } from "../../../sampler/snapshot/snapshot-completion.js";
import { globalServices } from "../../../services/global-services.js";
import { SNAPSHOT_RENDER_FONT_ATTRIBUTE } from "../snapshot-adoption.js";
import type { SnapshotAdoptionOutcome } from "../snapshot-adoption.js";
import type { EnhancementStateMachine } from "../state-machine.js";
import type { ContextState } from "../context-state.js";
import type { SnapshotAdoption } from "../snapshot-adoption.js";
import type { TypographyManager } from "../typography.js";
import type { EventChannel } from "../event-channel.js";
import type { SchedulerRegistration } from "../scheduler-registration.js";
import type { ProgressiveDispatch } from "./progressive-dispatch.js";
import type { EnhancedElementContext } from "../../context/enhance-context.js";

export interface InitialEnhanceEnv {
  stateMachine: EnhancementStateMachine;
  contextState: ContextState;
  snapshotAdoption: SnapshotAdoption;
  typography: TypographyManager;
  eventChannel: EventChannel;
  scheduler: SchedulerRegistration;
  progressiveDispatch: ProgressiveDispatch;
  /** Context-bound snapshot adoption wired by the composition root. */
  adoptRequestedSnapshot(isCurrent: () => boolean): Promise<SnapshotAdoptionOutcome>;
  /** ResponsiveManager retarget-frame cleanup, shared with the failure path. */
  clearResponsiveRetarget(): void;
}

export interface InitialEnhance {
  beginEnhanceAbortController(): AbortController;
  abortEnhancePipeline(): void;
  failInitialEnhance(generation: number, error: unknown): void;
  runHostCascadeGate(generation: number, strongEmphasisRuntimeRequired: boolean, signal: AbortSignal): Promise<void>;
  runInitialEnhance(generation: number, strongEmphasisRuntimeRequired: boolean, signal: AbortSignal): Promise<void>;
}

// HostCommitFrameYield: connectedCallback can fire inside a host framework's
// commit phase, before the framework's DOM writes for this frame settle. The
// style flush above updates computed style synchronously and yields no frame
// boundary, so a gate that never waits can schedule the initial enhance
// ahead of the host's first post-connect commit. Policy: HostCascadeReadyGate.
// Not disableable. Verified by demo/web framework-commit-conflict.test.mjs;
// removing the yield broke that suite at 0e46a072 and restoring it passed.
function nextHostFrame(): Promise<number> {
  return new Promise((resolve) => {
    // Frame yield through the global coordinator's raw requestFrame path is
    // session-less: the gate runs before any root session fact matters.
    globalServices().coordination.requestFrame((now) => resolve(now));
  });
}

// CausalFontReadiness (ruling R4): reading computed typography styles forces
// the document to flush pending style work — a newly registered stylesheet
// reaches the cascade and loaded faces bind to the prose — a causal signal
// that replaces counting frames.
function forceTypographyStyleRecompute(root: HTMLElement): void {
  const win = root.ownerDocument?.defaultView;
  if (!win) return;
  win.getComputedStyle(root).getPropertyValue("font-family");
  for (const element of typographyElements(root)) {
    win.getComputedStyle(element).getPropertyValue("font-family");
  }
}

function createInitialEnhance(
  root: HTMLElement,
  context: EnhancedElementContext,
  env: InitialEnhanceEnv,
): InitialEnhance {
  const { stateMachine, contextState, snapshotAdoption, typography, eventChannel, scheduler, progressiveDispatch } = env;

  // EnhanceAbortControllerSlot: publishes the lifecycle's AbortController on
  // the state machine transaction slot and returns it. A controller still in
  // the slot belongs to a lifecycle that never reached its teardown owner, so
  // starting a new lifecycle aborts it first.
  function beginEnhanceAbortController(): AbortController {
    const transaction = stateMachine.transaction;
    transaction.abortController?.abort();
    transaction.abortController = new AbortController();
    return transaction.abortController;
  }

  function abortEnhancePipeline(): void {
    const transaction = stateMachine.transaction;
    const controller = transaction.abortController;
    transaction.abortController = null;
    controller?.abort();
  }

  function failInitialEnhance(generation: number, error: unknown): void {
    if (generation !== context.generation) return;
    stateMachine.failLayoutWork();
    env.clearResponsiveRetarget();
    snapshotAdoption.releaseSnapshotFontSession();
    if (!isLoadedSnapshotAdopted(root)) root.removeAttribute(SNAPSHOT_RENDER_FONT_ATTRIBUTE);
    eventChannel.setInternalHandler(null);
    context.diagnosis.set("tiqianCapabilityIssue", "RuntimeLoadFailed");
    // The one-time work finished, albeit failed: the marker above is the
    // observable outcome, and the promise must settle for awaiting hosts.
    eventChannel.finishCompletion();
    console.warn("Tiqian Web runtime failed to load", error);
  }

  // HostCascadeReadyGate: connectedCallback may run before an app's
  // module-loaded styles have reached the cascade. Once Tiqian's own
  // stylesheet is registered, one forced style recompute flushes the
  // cascade; then only the faces used by the prose load through
  // document.fonts.load to their concrete families, and a second forced
  // recompute binds the loaded faces to the prose before the first commit.
  // Waiting for global DOMContentLoaded or document.fonts.ready would stall
  // prose on unrelated scripts, icon fonts, code fonts, or widgets.
  // CausalFontReadiness (ruling R4): both waits use causal style signals.
  // HostCommitFrameYield (R4 revision, FCC evidence): each stage also yields
  // one frame so the host framework's first post-connect commit lands ahead
  // of the initial enhance.
  async function runHostCascadeGate(
    generation: number,
    strongEmphasisRuntimeRequired: boolean,
    signal: AbortSignal,
  ): Promise<void> {
    const styles = await raceAbort(signal, ensureTiqianStyles(root.ownerDocument, root));
    if (styles.aborted) return;
    forceTypographyStyleRecompute(root);
    const cascadeFlush = await raceAbort(signal, nextHostFrame());
    if (cascadeFlush.aborted) return;
    const fontGate = await raceAbort(signal, awaitInitialTypographyFonts({
      generation,
      fonts: root.ownerDocument?.fonts ?? null,
      isCurrent: () => root.isConnected && generation === context.generation,
      bypassesFontWait: () => root.hasAttribute("snapshot-ref") &&
        !strongEmphasisRuntimeRequired,
      typographyElements: () => typographyElements(root),
      deferUntilFontsSettle: (gateGeneration, completion) =>
        typography.deferInitialEnhancementUntilFontsSettle(gateGeneration, completion),
      diagnosis: context.diagnosis,
    }));
    if (fontGate.aborted || !fontGate.value) return;
    forceTypographyStyleRecompute(root);
    const hostCommit = await raceAbort(signal, nextHostFrame());
    if (hostCommit.aborted) return;
    if (!root.isConnected || generation !== context.generation || signal.aborted) return;
    scheduler.requestFrame(() => {
      runInitialEnhance(generation, strongEmphasisRuntimeRequired, signal)
        .catch((error) => failInitialEnhance(generation, error));
    });
  }

  async function runInitialEnhance(
    generation: number,
    strongEmphasisRuntimeRequired: boolean,
    signal: AbortSignal,
  ): Promise<void> {
    if (!root.isConnected || generation !== context.generation || signal.aborted) return;
    const enhanceStartedAt = Date.now();
    const operation = contextState.beginLayoutWork({ captureSignatures: false });
    let snapshot: SnapshotAdoptionOutcome = { adopted: false };
    try {
      if (!strongEmphasisRuntimeRequired) {
        snapshot = await env.adoptRequestedSnapshot(
          () => root.isConnected && generation === context.generation &&
            operation === stateMachine.transaction.layoutOperation && !signal.aborted,
        );
      }
    } catch (error) {
      context.diagnosis.set("tiqianSnapshotMiss", "SnapshotValidationFailed");
      console.warn("Tiqian Web maximum-measure snapshot validation failed", error);
    }
    // The adoption commits are over; hand the scroller back to the
    // browser's own anchoring until the next commit path holds it.
    releaseNativeScrollAnchoring(root);
    if (
      !root.isConnected || generation !== context.generation ||
      operation !== stateMachine.transaction.layoutOperation || signal.aborted
    ) {
      if (snapshot.adopted) restoreLoadedSnapshot(root, context);
      return;
    }
    if (snapshot.adopted) {
      context.diagnosis.clear("tiqianSnapshotMiss");
      stateMachine.snapshotAdopted = true;
      snapshotAdoption.setSnapshotEnhancedCount(snapshot.count);
      // MixedSnapshotRuntimeCompletion: the snapshot owns only keyed
      // paragraphs. Runtime-only prose remains semantic source and is
      // enhanced through the same Kotlin pipeline without discarding valid
      // server geometry for its keyed siblings.
      const completionSelector = snapshotCompletionSelector(root);
      if (completionSelector) {
        if (!root.isConnected || generation !== context.generation || signal.aborted) {
          return;
        }
        snapshotAdoption.acceptValidatedSnapshotGeometry();
        await progressiveDispatch.dispatchProgressiveEnhance(generation, {
          paragraphSelector: completionSelector,
        });
        return;
      }
      if (!stateMachine.runtimeActive) snapshotAdoption.releaseSnapshotFontSession();
      stateMachine.dispatched = true;
      stateMachine.completionGateOpen = true;
      snapshotAdoption.acceptValidatedSnapshotGeometry();
      eventChannel.notify("tiqian:ready", {
        enhancedCount: snapshot.count,
        issueCount: 0,
        durationMs: Date.now() - enhanceStartedAt,
        maxSliceMs: 0,
        snapshot: true,
      });
      return;
    }
    context.diagnosis.set("tiqianSnapshotMiss", snapshot.reason ?? "SnapshotNotAdopted");
    if (!root.isConnected || generation !== context.generation || signal.aborted) return;
    if (!(await progressiveDispatch.dispatchProgressiveEnhance(generation))) return;
  }

  return {
    beginEnhanceAbortController,
    abortEnhancePipeline,
    failInitialEnhance,
    runHostCascadeGate,
    runInitialEnhance,
  };
}

export { createInitialEnhance };
