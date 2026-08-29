// ProgressiveDispatch — the progressive enhance dispatch of one enhanced
// element's lifecycle (core-neutral parts ruling). Mechanical port of the
// former session's dispatchProgressiveEnhance: snapshot-font session
// preparation, render-font gate, CJK dash capability, snapshot layout
// worker preparation, the layout worker attach and the anchor-bracketed
// driver call. Lifecycle files are the one part cluster that may hold the
// EnhancedElementContext, so the context-first driver is imported
// directly.

import { raceAbort } from "../../abort-race.js";
import { needsCjkDashShaping, prepareCjkDashShapingIfNeeded } from "../../loaders/cjk-dash.js";
import {
  captureViewportAnchor,
  compensateViewportAnchor,
  releaseNativeScrollAnchoring,
} from "../../coordination/viewport-anchor.js";
import { enhanceProgressively } from "../../progressive-drivers.js";
import {
  formatSnapshotFontMissDatasetValue,
  type TiqianElementSnapshotFontMissCandidate,
} from "../../snapshot-font.js";
import type { BrowserFontSessionHandle } from "../../../measurement/browser-fonts.js";
import { SNAPSHOT_RENDER_FONT_ATTRIBUTE } from "../snapshot-adoption.js";
import type { EnhanceDispatchOptions } from "../snapshot-adoption.js";
import type { EnhancementStateMachine } from "../state-machine.js";
import type { ContextState } from "../context-state.js";
import type { OptionsLedger } from "../options-ledger.js";
import type { SnapshotAdoption } from "../snapshot-adoption.js";
import type { SchedulerRegistration } from "../scheduler-registration.js";
import type { EnhancedElementContext } from "../../context/enhance-context.js";

export interface ProgressiveDispatchEnv {
  stateMachine: EnhancementStateMachine;
  contextState: ContextState;
  optionsLedger: OptionsLedger;
  snapshotAdoption: SnapshotAdoption;
  scheduler: SchedulerRegistration;
}

export interface ProgressiveDispatch {
  dispatchProgressiveEnhance(generation: number, options?: EnhanceDispatchOptions): Promise<boolean>;
}

function createProgressiveDispatch(
  root: HTMLElement,
  context: EnhancedElementContext,
  env: ProgressiveDispatchEnv,
): ProgressiveDispatch {
  const { stateMachine, contextState, optionsLedger, snapshotAdoption, scheduler } = env;

  async function dispatchProgressiveEnhance(
    generation: number,
    {
      beforeDispatch = null,
      paragraphSelector = null,
      revalidateSnapshotFont = true,
    }: EnhanceDispatchOptions = {},
  ): Promise<boolean> {
    // The dispatch runs under the lifecycle whose controller occupies the
    // transaction slot at entry; capturing the signal here keeps this dispatch
    // bound to its own lifecycle even if a restart replaces the slot later.
    const signal = stateMachine.transaction.abortController?.signal ?? null;
    const request = stateMachine.claimEnhanceRequest();
    contextState.beginLayoutWork();
    const baseOptions = {
      ...(optionsLedger.baseEnhanceOptions() ?? {}),
      ...(paragraphSelector ? { paragraphSelector } : {}),
    };
    const needsDash = needsCjkDashShaping(root);
    let snapshotFontSession: BrowserFontSessionHandle | null = null;
    const snapshotFontSessionAlreadyPrepared = !revalidateSnapshotFont &&
      snapshotAdoption.snapshotFontSessionReference() === root.getAttribute("snapshot-ref");
    try {
      const preparedSession = await raceAbort(signal, snapshotAdoption.prepareSnapshotFontSession(
        generation,
        request,
        revalidateSnapshotFont,
        signal,
      ));
      if (preparedSession.aborted) {
        snapshotAdoption.releaseSnapshotFontSession();
        return false;
      }
      snapshotFontSession = preparedSession.value;
      context.diagnosis.clear("tiqianSnapshotFontMiss");
    } catch (error) {
      if (
        root.isConnected && generation === context.generation &&
        request === stateMachine.transaction.enhanceRequest
      ) snapshotAdoption.releaseSnapshotFontSession();
      context.diagnosis.set("tiqianSnapshotFontMiss", formatSnapshotFontMissDatasetValue(error as TiqianElementSnapshotFontMissCandidate));
      console.warn("Tiqian Web snapshot font session unavailable; using browser metrics", error);
    }
    if (
      !root.isConnected || generation !== context.generation ||
      request !== stateMachine.transaction.enhanceRequest || signal?.aborted
    ) {
      if (!root.isConnected || generation !== context.generation || signal?.aborted) {
        snapshotAdoption.releaseSnapshotFontSession();
      }
      return false;
    }
    // PreparedSnapshotTransition: callers leaving a precomputed snapshot keep
    // that rendered DOM live while the runtime and snapshot-font session load. The
    // semantic source is restored immediately before dispatch. Viewport-near
    // paragraphs are prepared in bounded frames and replaced atomically; source
    // paragraphs not reached yet remain responsive through the same exact root
    // font and host line-height contract.
    beforeDispatch?.();
    // LatestSnapshotLayoutDiagnostics: source DOM is live at this point, so stale
    // replay diagnostics can be cleared without briefly re-enabling exact CSS
    // on geometry from the previous measure. The current run will set them
    // again if its own prepared DOM cannot be represented.
    context.diagnosis.clear("tiqianExactLayoutIssue");
    if (snapshotFontSession) {
      try {
        root.setAttribute(SNAPSHOT_RENDER_FONT_ATTRIBUTE, "true");
        // HostRenderFontReadyBeforeCommit: server replay already owns the
        // layout metrics, but CSS must finish loading the proven host faces before the
        // first paragraph is committed. This avoids a second font-driven pass
        // and prevents progressive frames from painting a fallback face.
        // WidthOnlySnapshotFontSessionReuse: replay tables and loaded host faces do not change
        // when only the content-box measure changes. Typography/font observers
        // still take the validating path; a responsive retarget can start the
        // latest-width paragraph queue without repeating font probes first.
        if (!snapshotFontSessionAlreadyPrepared) {
          const renderFont = await raceAbort(signal, snapshotAdoption.prepareSnapshotRenderFont(snapshotFontSession));
          if (renderFont.aborted) {
            snapshotAdoption.releaseSnapshotFontSession();
            return false;
          }
        }
        if (
          !root.isConnected || generation !== context.generation ||
          request !== stateMachine.transaction.enhanceRequest || signal?.aborted
        ) {
          snapshotAdoption.releaseSnapshotFontSession();
          return false;
        }
      } catch (error) {
        if (
          !root.isConnected || generation !== context.generation ||
          request !== stateMachine.transaction.enhanceRequest
        ) {
          snapshotAdoption.releaseSnapshotFontSession();
          return false;
        }
        snapshotAdoption.releaseSnapshotFontSession();
        snapshotFontSession = null;
        context.diagnosis.set("tiqianSnapshotFontMiss", "SnapshotRenderFontStyleUnavailable");
        console.warn("Tiqian Web snapshot render font style unavailable; using browser metrics", error);
      }
    }
    if (!snapshotFontSession) {
      root.removeAttribute(SNAPSHOT_RENDER_FONT_ATTRIBUTE);
    }
    // BrowserDashCapabilityBeforeDispatch: the browser no longer starts an
    // asynchronous HarfBuzz probe. Resolve the immediate capability result
    // before the first layout so a dash paragraph is never laid out once as
    // pending and then redundantly retried. An exact server-replay session is
    // carried separately and remains the authoritative dash path.
    const dashCapability = needsDash
      ? await raceAbort(signal, prepareCjkDashShapingIfNeeded(root, {
          ...baseOptions,
          ...(snapshotFontSession ? { snapshotFontSession } : {}),
        }))
      : { aborted: false as const, value: { status: "not-needed" as const } };
    if (dashCapability.aborted) {
      snapshotAdoption.releaseSnapshotFontSession();
      return false;
    }
    const cjkDashCapability = dashCapability.value;
    if (
      !root.isConnected || generation !== context.generation ||
      request !== stateMachine.transaction.enhanceRequest || signal?.aborted
    ) {
      snapshotAdoption.releaseSnapshotFontSession();
      return false;
    }
    // Capture the input signature for cancellation. Kotlin reads the live width
    // again for each paragraph, while this coordinator cancels the remaining
    // job on the next frame if the effective line measure changes.
    const layoutOperation = contextState.beginLayoutWork({ usesCapturedMeasure: true });
    stateMachine.dispatched = true;
    stateMachine.runtimeActive = true;
    stateMachine.completionGateOpen = true;
    const preparedOptions = {
      ...baseOptions,
      cjkDashCapability,
      ...(snapshotFontSession ? {
        requireSnapshotLayoutWorker: true,
        snapshotFontSession: {
          status: "conforming",
          sessionId: snapshotFontSession.id,
          detail: "SnapshotFontBytes",
        },
      } : {}),
    };
    if (snapshotFontSession) {
      try {
        const channel = await raceAbort(signal, import("@tiqian/core/src/engine/web-worker/worker-channel.js"));
        if (channel.aborted) return false;
        const prepareJob = await raceAbort(signal, channel.value.createPrepareJob(
          root,
          snapshotFontSession,
          preparedOptions,
          // AbortSignalStandardShell: the worker channel's generational
          // isCurrent predicate stays the cancellation kernel; the lifecycle
          // signal is its standard shell, consulted at every kernel check.
          () => root.isConnected && generation === context.generation &&
            request === stateMachine.transaction.enhanceRequest &&
            layoutOperation === stateMachine.transaction.layoutOperation &&
            !(signal?.aborted ?? false),
        ));
        if (prepareJob.aborted) return false;
        if (prepareJob.value) {
          const prepared = await raceAbort(signal, scheduler.runPrepare(prepareJob.value));
          if (prepared.aborted) return false;
        }
      } catch (error) {
        // SnapshotWorkerFailureMustStayNative: synchronous Kotlin/JS fallback can
        // block scroll under JIT restrictions. Progressive enhancement will
        // retain source DOM for requests without a Worker plan.
        console.warn("Tiqian Web layout Worker unavailable; retaining native paragraphs", error);
      }
      if (
        !root.isConnected || generation !== context.generation ||
        request !== stateMachine.transaction.enhanceRequest ||
        layoutOperation !== stateMachine.transaction.layoutOperation || signal?.aborted
      ) {
        if (!root.isConnected || generation !== context.generation || signal?.aborted) {
          snapshotAdoption.releaseSnapshotFontSession();
        }
        return false;
      }
    }
    contextState.ensureLayoutWorker();
    // RunToCompletionAnchorBracket: without an attached coordinator the whole
    // progressive job runs synchronously inside this call, outside every
    // grant round's capture/compensate bracket. Bracket it here with one
    // same-task pair and the native-anchoring hold, so the correction sees
    // only the pass's layout displacement and the browser's own anchoring
    // cannot re-anchor under a running entrance animation. A coordinated job
    // only registers inside this call; the pair measures a zero delta and
    // the first grant re-establishes both ends of the bracket.
    // EnhanceOptionsOracle: publish the fully resolved options for this
    // coordination run on the root dataset so one-shot replay tests can read
    // them back after parcel bundling removes direct module re-import. This is
    // the public successor of the retired document event channel (ADR 0053
    // C1); no event is dispatched here.
    context.diagnosis.set("tiqianEnhanceOptions", JSON.stringify(preparedOptions));
    const runAnchor = captureViewportAnchor(root);
    try {
      enhanceProgressively(context, root, preparedOptions);
    } finally {
      compensateViewportAnchor(root, runAnchor);
      releaseNativeScrollAnchoring(root);
    }
    contextState.syncLayoutWorker();
    return true;
  }

  return { dispatchProgressiveEnhance };
}

export { createProgressiveDispatch };
