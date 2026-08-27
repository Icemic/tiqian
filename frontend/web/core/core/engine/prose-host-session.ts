// ProseHostSession — the per-root prose host orchestration session (wc-s5
// thin-shell ruling). One session per enhanced root; it owns the host state
// machine, the enhance/relayout pipeline, every observer and the completion
// events. The TiqianProseElement web component is a thin platform shell that
// delegates its lifecycle callbacks and attribute reflections to this
// session; framework hosts without a custom element build one directly
// through createProseHostSession(root, options).
//
// Event surface (ruling R6): ProseHostEvent is the two completion states
// "ready" and "relayout-ready". on()/onReady()/onRelayoutReady() subscribe
// plain TypeScript callbacks over those states. The DOM CustomEvents
// tiqian:ready and tiqian:relayout-ready stay the single observable event
// surface on the root (dispatched by the progressive drivers and the
// snapshot adoption paths); the session's own completion funnel notifies the
// callback subscribers from the same events. State outside the enum keeps
// flowing through ordinary DOM events and is not modeled here.
import { createEnhanceContext } from "./context/enhance-context.js";
import type { EnhancedElementContext, RawDomParagraphRecord } from "./context/enhance-context.js";
import { createProseHostStateMachine } from "./prose-host-state-machine.js";
import { InvalidationReason } from "./prose-host-state.js";
import { raceAbort } from "./abort-race.js";
import {
  loadTiqianRuntime,
} from "./loaders/runtime-loader.js";
import {
  awaitInitialTypographyFonts,
  createInitialFontRetryController,
  ensurePreparedDomBridge,
  fontLoadingAffectsTypography,
  loadSnapshotFontFallback,
} from "./loaders/font-loader.js";
import { needsCjkDashShaping, prepareCjkDashShapingIfNeeded } from "./loaders/cjk-dash.js";
import { hasStrongEmphasis } from "./eligibility.js";
import {
  rendererOwnedProgressiveStyleMutation,
  renderedRawDomParagraphs,
} from "./raw-dom.js";
import { hasHostInlineSizeParagraph } from "./responsive-measure.js";
import {
  detachLoadedSnapshot,
  isLoadedSnapshotAdopted,
  loadedAdoptedSnapshotLiveIssue,
  loadedSnapshotMaximumMeasureMatches,
  restoreLoadedSnapshot,
  tryAdoptRequestedSnapshot,
} from "../sampler/snapshot/loaded-snapshots.js";
import {
  createSnapshotFontSessionEntry,
  releaseSnapshotFontSession,
} from "./snapshot-font.js";
import { ensureTiqianStyles } from "./loaders/styles.js";
import {
  captureViewportAnchor,
  compensateViewportAnchor,
  releaseNativeScrollAnchoring,
} from "./coordination/viewport-anchor.js";
import { CoordinationService, type FrameTaskCallback, type RootSessionId } from "./coordination/coordination-service.js";
import { globalServices } from "../services/global-services.js";
import { enhanceProgressively, relayout } from "./progressive-drivers.js";
import { destroyRoot, detachRoot } from "./lifecycle.js";
import { createRootState } from "./root-state.js";
import type { RootStateApi } from "./root-state.js";
import { probeRootContentDrift, reconcileRoot } from "./content-reconcile.js";
import type { LayoutJobPool } from "./layout-job-pool.js";
import {
  fragmentedBorderBoxInlineSize,
  typographySignature,
  typographyElements,
  captureLayoutWorkViewportTypographyEntries,
  layoutWorkViewportTypographyChanged,
  paragraphWidthSignature,
  responsiveGeometrySignature,
  paragraphMeasureSignature,
  snapshotFontAttemptSignature,
} from "../sampler/signatures.js";
import {
  createParagraphGridMetricsState,
  seedParagraphGridMetrics,
  paragraphMeasureSignatureFromObserved,
} from "../sampler/grid-metrics.js";
import {
  belongsToRootScope,
  classifyContentMutationRecords,
  createTypographyInvalidationSource,
  createLayoutWorkTypographyInvalidationSource,
  createViewportResizeInvalidationSource,
  createContentInvalidationSource,
  createRootSizeObservation,
  createRootVisibilityObservation,
  rootScopedParagraphs,
} from "../sampler/observers.js";
import { snapshotCompletionSelector } from "../sampler/snapshot/snapshot-completion.js";
import type { BrowserFontSessionHandle } from "../measurement/browser-fonts.js";
import type { SnapshotFontSessionEntry } from "./snapshot-font.js";
import type {
  FontLoadingEventLike,
  InitialFontRetryController,
} from "./loaders/font-loader.js";
import type { SnapshotAdoptAnchors } from "../sampler/snapshot/precomputed.js";
import type {
  ContentInvalidationSource,
  RootSizeObservationSource,
  RootVisibilityObservationSource,
  TypographyInvalidationSource,
  ViewportResizeInvalidationSource,
} from "../sampler/observers.js";

// Attribute-reflected host options. The web component shell mirrors its four
// observed attributes through updateOptions(); programmatic hosts set the
// same values directly.
export interface ProseHostOptions {
  readonly disabled?: boolean;
  readonly emphasisDotGapEm?: number | null;
  readonly strongAsEmphasisMarks?: boolean;
  readonly snapshotRef?: string | null;
}

/** Resolved host options held by the session's applied-options ledger. */
interface AppliedProseHostOptions {
  disabled: boolean;
  emphasisDotGapEm: number | null;
  strongAsEmphasisMarks: boolean;
  snapshotRef: string | null;
}

// Completion event names (ruling R6). Every state-machine transition that
// carries an observer resolves into one of these two: the ready funnel
// classifies each tiqian:ready / tiqian:relayout-ready completion by its
// event type before notifying subscribers.
export type ProseHostEvent = "ready" | "relayout-ready";

export interface ProseHostDiagnostics {
  readonly enhanceMs?: number;
  readonly loadMs?: number;
  readonly relayoutMs?: number;
  readonly maxSliceMs?: number;
  readonly snapshotCount?: number;
  readonly enhancedCount?: number;
  readonly snapshot?: boolean;
}

export type ProseHostEventCallback = (diagnostics: ProseHostDiagnostics) => void;

/** Unsubscribe handle returned by the event subscription surface. */
export type ProseHostEventUnsubscribe = () => void;

// Enhance options bag built from the reflected host options; the progressive
// drivers consume it as a plain record.
interface HostEnhanceOptionsBag {
  emphasisDotGapEm?: number;
  strongAsEmphasisMarks?: boolean;
  paragraphSelector?: string;
}

export const OBSERVED_ATTRIBUTES: string[] = [
  "disabled",
  "emphasis-dot-gap-em",
  "strong-as-emphasis-marks",
  "snapshot-ref",
];

const SNAPSHOT_RENDER_FONT_ATTRIBUTE = "data-tiqian-snapshot-render-font";
const SNAPSHOT_PREPARED_FALLBACK_ATTRIBUTE = "data-tiqian-snapshot-layout-fallback";
const RESPONSIVE_SNAPSHOT_GEOMETRY_MISSES = new Set([
  "SnapshotWidthMismatch",
  "SnapshotWidthChangedDuringValidation",
]);

interface TiqianElementSnapshotFontMissCandidate {
  code?: string;
  detail?: string;
}

function snapshotFontMissDatasetValue(error: TiqianElementSnapshotFontMissCandidate): string {
  if (error?.code === "SnapshotFontContractMismatch" && typeof error?.detail === "string") {
    const pipeIndex = error.detail.indexOf("|");
    if (pipeIndex !== -1) {
      const detailSuffix = error.detail.slice(pipeIndex + 1);
      if (detailSuffix === "EmptyCandidateSet" || detailSuffix.startsWith("FieldMismatch|")) {
        return `${error.code}|${detailSuffix}`;
      }
    }
  }
  return error?.code ?? "SnapshotFontSessionUnavailable";
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

// HostCommitFrameYield: connectedCallback can fire inside a host framework's
// commit phase, before the framework's DOM writes for this frame settle. The
// style flush above updates computed style synchronously and yields no frame
// boundary, so a gate that never waits can schedule the initial enhance
// ahead of the host's first post-connect commit. Policy: HostCascadeReadyGate.
// Not disableable. Verified by demo/web framework-commit-conflict.test.mjs;
// removing the yield broke that suite at 0e46a072 and restoring it passed.
function nextHostFrame(): Promise<number> {
  return new Promise((resolve) => coordinationService().requestFrame((now) => resolve(now)));
}

function coordinationService(): CoordinationService {
  return globalServices().coordination;
}

// Root teardown through the session-owned root state (R10 dissolved the engine
// facade): the session holds the rootState and passes it to this helper.
function destroyRuntimeRoot(rootState: RootStateApi, context: EnhancedElementContext, root: HTMLElement): void {
  destroyRoot(rootState, coordinationService().layoutJobPool, context, root);
}

function detachRuntimeRoot(root: HTMLElement): void {
  detachRoot(coordinationService().layoutJobPool, root);
}

function cancelRootLayoutWork(root: HTMLElement): void {
  coordinationService().layoutJobPool.cancelJob(root);
}

interface TiqianParagraphTierInfo {
  index: number;
  tier: number;
}

type TiqianReadyEventDetail = {
  snapshot?: boolean;
  runtimeEnhancedCount: number;
  snapshotCount: number;
  enhancedCount: number;
  durationMs: number;
  maxSliceMs: number;
  relayout?: boolean;
  stale?: boolean;
};

type TiqianBoundResponsiveCommitFn = () => void;

type TiqianBeforeDispatchFn = () => void;

interface TiqianEnhanceDispatchOptions {
  beforeDispatch?: TiqianBeforeDispatchFn | null;
  paragraphSelector?: string | null;
  revalidateSnapshotFont?: boolean;
}

interface TiqianLayoutWorkOptions {
  usesCapturedMeasure?: boolean;
  captureSignatures?: boolean;
}

interface TiqianSnapshotInvalidateOptions {
  restoreBeforeLoad?: boolean;
}

interface TiqianSourceRefreshOptions {
  revalidateSnapshotFont?: boolean;
}

type TiqianSnapshotAdoptionOutcome =
  | { adopted: false; reason?: string }
  | { adopted: true; count: number };

type TiqianRootPausedCommitFn = () => void;

class ProseHostSession {
  readonly #root: HTMLElement;
  readonly #rootState: RootStateApi = createRootState();

  #boundResponsiveCommit: TiqianBoundResponsiveCommitFn = () => {
    if (this.#root.isConnected) this.#commitResponsiveGeometryChange();
  };
  #coordinationSession: RootSessionId = 0;
  #stateMachine = createProseHostStateMachine();
  #detachAttributeSnapshot: (string | null)[] | null = null;
  #lastCommittedParagraphMeasures = "";
  #contentInvalidation: ContentInvalidationSource | null = null;
  #contentProbeFrame: FrameTaskCallback | null = null;
  #contentTainted = new Set<Element>();
  #typographyInvalidation: TypographyInvalidationSource | null = null;
  #context: EnhancedElementContext;
  #initialFontRetry: InitialFontRetryController | null = null;
  #visibilityObservation: RootVisibilityObservationSource | null = null;
  #layoutWorkTypographyInvalidation: TypographyInvalidationSource | null = null;
  #snapshotFontRejectedAttempt = "";
  #snapshotFontSession: SnapshotFontSessionEntry | null = null;
  #lastObservedWidth = 0;
  #lastWidth = 0;
  #lastParagraphMeasures = "";
  #lastParagraphWidths = "";
  #lastTypography = "";
  #paragraphObserver: IntersectionObserver | null = null;
  #paragraphTierIndex = new Map<Element, TiqianParagraphTierInfo>();
  #readyListener: EventListener | null = null;
  #sizeObservation: RootSizeObservationSource | null = null;
  #gridMetricsState = createParagraphGridMetricsState();
  #pendingCommittedMeasures = "";
  #responsiveRetargetFrame: FrameTaskCallback | null = null;
  #snapshotEnhancedCount = 0;
  #typographyFrame: FrameTaskCallback | null = null;
  #viewportResizeInvalidation: ViewportResizeInvalidationSource | null = null;

  get disabled(): boolean {
    return this.#root.hasAttribute("disabled");
  }

  set disabled(value: boolean) {
    this.#root.toggleAttribute("disabled", Boolean(value));
  }

  get emphasisDotGapEm(): number | null {
    const value = Number.parseFloat(this.#root.getAttribute("emphasis-dot-gap-em") as string);
    return Number.isFinite(value) ? value : null;
  }

  set emphasisDotGapEm(value: number | null) {
    if (value == null) {
      this.#root.removeAttribute("emphasis-dot-gap-em");
    } else {
      this.#root.setAttribute("emphasis-dot-gap-em", String(value));
    }
  }

  get strongAsEmphasisMarks(): boolean {
    return this.#root.hasAttribute("strong-as-emphasis-marks");
  }

  set strongAsEmphasisMarks(value: boolean) {
    this.#root.toggleAttribute("strong-as-emphasis-marks", Boolean(value));
  }

  get snapshotRef(): string | null {
    return this.#root.getAttribute("snapshot-ref");
  }

  set snapshotRef(value: string | null) {
    if (value == null) {
      this.#root.removeAttribute("snapshot-ref");
    } else {
      this.#root.setAttribute("snapshot-ref", String(value));
    }
  }

  mount() {
    // AppliedLedgerMountSync: attribute changes made through property setters
    // or present before construction never passed through updateOptions; sync
    // the ledger from the live attributes so the next reflection diffs
    // against what the root actually carries.
    this.#syncAppliedOptions();
    if (!this.#coordinationSession) this.#coordinationSession = coordinationService().register();
    this.#observeIntersection();
    if (this.#canAdoptRawDomMoveReconnection()) {
      this.#adoptRawDomMoveReconnection();
      return;
    }
    // ReconnectedSourceReclamation: detached roots keep their source backing in
    // weak runtime/snapshot state so navigation can discard them without
    // rebuilding an invisible old article. A real reconnection is the one case
    // that needs to pay the restoration cost before starting a new lifecycle.
    if (!this.#stateMachine.connected) {
      if (isLoadedSnapshotAdopted(this.#root)) restoreLoadedSnapshot(this.#root);
      if (this.#stateMachine.runtimeActive) destroyRuntimeRoot(this.#rootState, this.#context, this.#root);
      this.#stateMachine.runtimeActive = false;
    }
    this.#stateMachine.connect(this.disabled);
    this.#clearLifecycleDiagnostics();
    // ReversibleDisabledEnhancement: the Boolean attribute is the complete
    // opt-out contract. Keep semantic SSR children live and avoid stylesheet,
    // font, snapshot, runtime and observer work until the host removes it.
    if (this.disabled) return;
    this.#snapshotFontRejectedAttempt = "";
    const generation = this.#context.update();
    this.#clearInitialFontRetry();
    this.#stateMachine.completionGateOpen = false;
    this.#stateMachine.dispatched = false;
    this.#stateMachine.snapshotAdopted = isLoadedSnapshotAdopted(this.#root);
    this.#snapshotEnhancedCount = 0;
    const loadStartedAt = Date.now();
    let initialReadyReported = false;
    let pendingLoadMs: number | null = null;
    // OptInStrongSnapshotExclusion: v1 snapshots contain only plain paragraphs,
    // so they cannot claim that a semantic <strong> was lowered to emphasis
    // marks. Keep the default bold path eligible for snapshots; an explicit
    // mapping request with actual <strong> content must enter the runtime.
    const strongEmphasisRuntimeRequired =
      this.strongAsEmphasisMarks && hasStrongEmphasis(this.#root);
    // SnapshotFirstInputBeforeRuntimeCompile: even a mixed root can prove and
    // display its keyed snapshot without Kotlin. Under Edge JITless, eagerly
    // importing the full runtime for one unkeyed paragraph delays the first
    // wheel event before adoption has even started. Load it only after a
    // successful snapshot reports that completion is still required.
    const runtimePromise = this.#root.hasAttribute("snapshot-ref") &&
        !strongEmphasisRuntimeRequired
      ? null
      : loadTiqianRuntime();
    runtimePromise?.catch(() => {});
    this.#removeReadyListener();
    this.#stopTypographyObservation();
    this.#readyListener = (event) => {
      if (
        generation !== this.#context.generation || !this.#stateMachine.dispatched ||
        !this.#stateMachine.completionGateOpen
      ) return;
      const detail = (event as CustomEvent<TiqianReadyEventDetail>).detail ?? {};
      if (this.#stateMachine.snapshotAdopted && this.#snapshotEnhancedCount > 0) {
        const snapshotCount = this.#snapshotEnhancedCount;
        const runtimeEnhancedCount = detail.snapshot
          ? 0
          : Number.isFinite(detail.runtimeEnhancedCount)
            ? detail.runtimeEnhancedCount
            : Number.isFinite(detail.snapshotCount)
              ? Math.max(0, (Number(detail.enhancedCount) || 0) - snapshotCount)
              : Math.max(0, Number(detail.enhancedCount) || 0);
        const enhancedCount = runtimeEnhancedCount + snapshotCount;
        this.#context.diagnosis.set("tiqianSnapshotCount", String(this.#snapshotEnhancedCount));
        this.#root.setAttribute("data-tiqian-enhanced-count", String(enhancedCount));
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
        if (Number.isFinite(durationMs)) this.#context.diagnosis.set("tiqianRelayoutMs", durationMs.toFixed(1));
        if (Number.isFinite(maxSliceMs)) {
          this.#context.diagnosis.set("tiqianRelayoutMaxSliceMs", maxSliceMs.toFixed(1));
        }
        // CommittedMeasureLedger: forced commits (viewport revalidation,
        // stale follow-ups) skip against what the last clean relayout
        // actually committed, never against dispatch-time bookkeeping. The
        // runtime reports content reconciles through this same event kind,
        // so only jobs this element dispatched as width relayouts may move
        // the ledger.
        if (this.#stateMachine.work.kind === "Relayout") {
          if (!stale) {
            this.#lastCommittedParagraphMeasures = this.#pendingCommittedMeasures;
          } else {
            // A stale finish leaves a mix of old- and new-measure
            // paragraphs, which no single signature describes — a ledger
            // still holding the pre-mix cell would let a forced convergence
            // pass skip and strand the mix. Invalidate so the next forced
            // pass always dispatches.
            this.#lastCommittedParagraphMeasures = "";
          }
        }
      } else {
        if (Number.isFinite(durationMs)) this.#context.diagnosis.set("tiqianEnhanceMs", durationMs.toFixed(1));
        if (Number.isFinite(maxSliceMs)) this.#context.diagnosis.set("tiqianMaxSliceMs", maxSliceMs.toFixed(1));
        if (!initialReadyReported) {
          initialReadyReported = true;
          pendingLoadMs = Date.now() - loadStartedAt;
          this.#context.diagnosis.set("tiqianLoadMs", (Date.now() - loadStartedAt).toFixed(1));
        }
      }
      // SnapshotPreparedDomFallbackSingleFlight: once browser replay proves that
      // the snapshot HarfBuzz result cannot be represented at this effective
      // measure, retain the readable browser-metric rendering without letting
      // font loading events start the same failed snapshot session indefinitely.
      // A route reconnect or a different line-length grid gets a fresh attempt.
      if (this.#root.hasAttribute(SNAPSHOT_PREPARED_FALLBACK_ATTRIBUTE)) {
        this.#snapshotFontRejectedAttempt = this.#snapshotFontAttemptSignature();
        // ResponsiveSnapshotFontSessionReuse: the server replay tables and host
        // font proof are still valid; only this line measure failed DOM replay.
        // Retain the session so a later grid can revalidate without rebuilding
        // the replay corpus. Disconnect and snapshot adoption remain the owners
        // of final release.
        this.#root.removeAttribute(SNAPSHOT_RENDER_FONT_ATTRIBUTE);
      }
      if (stale) this.#stateMachine.invalidate(InvalidationReason.ResponsiveCommit);
      if (stale) this.#stateMachine.invalidate(InvalidationReason.ResponsiveRelayout);
      // Completion funnel (ruling R6): every observed completion notifies the
      // session's callback subscribers. The DOM CustomEvent dispatches stay
      // the single observable event surface; this channel is the plain
      // TypeScript mirror of the two enum events.
      const completionEvent: ProseHostEvent =
        event.type === "tiqian:relayout-ready" ? "relayout-ready" : "ready";
      const diagnostics: ProseHostDiagnostics = {
        enhancedCount: Number.isFinite(detail.enhancedCount) ? detail.enhancedCount : undefined,
        snapshotCount: Number.isFinite(detail.snapshotCount) ? detail.snapshotCount : undefined,
        maxSliceMs: Number.isFinite(maxSliceMs) ? maxSliceMs : undefined,
        snapshot: detail.snapshot ? true : undefined,
        enhanceMs: !relayout && Number.isFinite(durationMs) ? durationMs : undefined,
        relayoutMs: relayout && Number.isFinite(durationMs) ? durationMs : undefined,
        loadMs: pendingLoadMs ?? undefined,
      };
      pendingLoadMs = null;
      this.#emitEvent(completionEvent, diagnostics);
      this.#finishLayoutWorkAndObserve();
    };
    this.#root.addEventListener("tiqian:ready", this.#readyListener);
    this.#root.addEventListener("tiqian:relayout-ready", this.#readyListener);
    this.#ensureViewportResizeListener();

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
    const enhanceAbortController = this.#beginEnhanceAbortController();
    this.#runHostCascadeGate(generation, strongEmphasisRuntimeRequired, runtimePromise, enhanceAbortController.signal)
      .catch((error) => this.#failInitialEnhance(generation, error));
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
  async #runHostCascadeGate(
    generation: number,
    strongEmphasisRuntimeRequired: boolean,
    runtimePromise: Promise<unknown> | null,
    signal: AbortSignal,
  ): Promise<void> {
    const styles = await raceAbort(signal, ensureTiqianStyles(this.#root.ownerDocument, this.#root));
    if (styles.aborted) return;
    forceTypographyStyleRecompute(this.#root);
    const cascadeFlush = await raceAbort(signal, nextHostFrame());
    if (cascadeFlush.aborted) return;
    const fontGate = await raceAbort(signal, awaitInitialTypographyFonts({
      generation,
      fonts: this.#root.ownerDocument?.fonts ?? null,
      isCurrent: () => this.#root.isConnected && generation === this.#context.generation,
      bypassesFontWait: () => this.#root.hasAttribute("snapshot-ref") &&
        !strongEmphasisRuntimeRequired,
      typographyElements: () => typographyElements(this.#root),
      deferUntilFontsSettle: (gateGeneration, completion) =>
        this.#deferInitialEnhancementUntilFontsSettle(gateGeneration, completion),
      diagnosis: this.#context.diagnosis,
    }));
    if (fontGate.aborted || !fontGate.value) return;
    forceTypographyStyleRecompute(this.#root);
    const hostCommit = await raceAbort(signal, nextHostFrame());
    if (hostCommit.aborted) return;
    if (!this.#root.isConnected || generation !== this.#context.generation || signal.aborted) return;
    coordinationService().requestFrame(() => {
      this.#runInitialEnhance(generation, strongEmphasisRuntimeRequired, runtimePromise, signal)
        .catch((error) => this.#failInitialEnhance(generation, error));
    }, this.#coordinationSession);
  }

  // EnhanceAbortControllerSlot: publishes the lifecycle's AbortController on
  // the state machine transaction slot and returns it. A controller still in
  // the slot belongs to a lifecycle that never reached its teardown owner, so
  // starting a new lifecycle aborts it first.
  #beginEnhanceAbortController(): AbortController {
    const transaction = this.#stateMachine.transaction;
    transaction.abortController?.abort();
    transaction.abortController = new AbortController();
    return transaction.abortController;
  }

  #abortEnhancePipeline(): void {
    const transaction = this.#stateMachine.transaction;
    const controller = transaction.abortController;
    transaction.abortController = null;
    controller?.abort();
  }

  #failInitialEnhance(generation: number, error: unknown): void {
    if (generation !== this.#context.generation) return;
    this.#stateMachine.failLayoutWork();
    this.#clearResponsiveRetarget();
    this.#releaseSnapshotFontSession();
    if (!isLoadedSnapshotAdopted(this.#root)) this.#root.removeAttribute(SNAPSHOT_RENDER_FONT_ATTRIBUTE);
    this.#removeReadyListener();
    this.#context.diagnosis.set("tiqianCapabilityIssue", "RuntimeLoadFailed");
    console.warn("Tiqian Web runtime failed to load", error);
  }

  async #runInitialEnhance(
    generation: number,
    strongEmphasisRuntimeRequired: boolean,
    runtimePromise: Promise<unknown> | null,
    signal: AbortSignal,
  ): Promise<void> {
    if (!this.#root.isConnected || generation !== this.#context.generation || signal.aborted) return;
    const enhanceStartedAt = Date.now();
    const operation = this.#beginLayoutWork({ captureSignatures: false });
    let snapshot: TiqianSnapshotAdoptionOutcome = { adopted: false };
    try {
      if (!strongEmphasisRuntimeRequired) {
        snapshot = await tryAdoptRequestedSnapshot(
          this.#root,
          this.#root.ownerDocument,
          () => this.#root.isConnected && generation === this.#context.generation &&
            operation === this.#stateMachine.transaction.layoutOperation && !signal.aborted,
          this.#snapshotAdoptionAnchors(),
        );
      }
    } catch (error) {
      this.#context.diagnosis.set("tiqianSnapshotMiss", "SnapshotValidationFailed");
      console.warn("Tiqian Web maximum-measure snapshot validation failed", error);
    }
    // The adoption commits are over; hand the scroller back to the
    // browser's own anchoring until the next commit path holds it.
    releaseNativeScrollAnchoring(this.#root);
    if (
      !this.#root.isConnected || generation !== this.#context.generation ||
      operation !== this.#stateMachine.transaction.layoutOperation || signal.aborted
    ) {
      if (snapshot.adopted) restoreLoadedSnapshot(this.#root);
      return;
    }
    if (snapshot.adopted) {
      this.#context.diagnosis.clear("tiqianSnapshotMiss");
      this.#stateMachine.snapshotAdopted = true;
      this.#snapshotEnhancedCount = snapshot.count;
      // MixedSnapshotRuntimeCompletion: the snapshot owns only keyed
      // paragraphs. Runtime-only prose remains semantic source and is
      // enhanced through the same Kotlin pipeline without discarding valid
      // server geometry for its keyed siblings.
      const completionSelector = snapshotCompletionSelector(this.#root);
      if (completionSelector) {
        const runtime = await raceAbort(signal, Promise.resolve(runtimePromise ?? loadTiqianRuntime()));
        if (runtime.aborted) return;
        if (!this.#root.isConnected || generation !== this.#context.generation || signal.aborted) {
          return;
        }
        this.#acceptValidatedSnapshotGeometry();
        await this.#dispatchProgressiveEnhance(generation, {
          paragraphSelector: completionSelector,
        });
        return;
      }
      if (!this.#stateMachine.runtimeActive) this.#releaseSnapshotFontSession();
      this.#stateMachine.dispatched = true;
      this.#stateMachine.completionGateOpen = true;
      this.#acceptValidatedSnapshotGeometry();
      this.#root.dispatchEvent(new CustomEvent("tiqian:ready", {
        bubbles: true,
        composed: true,
        detail: {
          enhancedCount: snapshot.count,
          issueCount: 0,
          durationMs: Date.now() - enhanceStartedAt,
          maxSliceMs: 0,
          snapshot: true,
        },
      }));
      return;
    }
    this.#context.diagnosis.set("tiqianSnapshotMiss", snapshot.reason ?? "SnapshotNotAdopted");
    const runtime = await raceAbort(signal, Promise.resolve(runtimePromise ?? loadTiqianRuntime()));
    if (runtime.aborted) return;
    if (!this.#root.isConnected || generation !== this.#context.generation || signal.aborted) return;
    if (!(await this.#dispatchProgressiveEnhance(generation))) return;
  }

  unmount() {
    // RawDomMoveTeardownDeferral: React, Svelte and other reconcilers move a
    // node by removing and re-inserting it inside one synchronous commit.
    // Settling the disconnection synchronously destroys a rendered article
    // that never left the host raw-DOM backup, so the settle runs one microtask later.
    // A same-task reconnection then re-enters the live lifecycle through
    // RawDomMoveAdoption. A real navigation settles exactly as before, still
    // before the next frame. The remount variant of
    // resize-destroy-transient.test.mjs holds this contract.
    this.#stateMachine.enterDeferredTeardown();
    this.#detachAttributeSnapshot = OBSERVED_ATTRIBUTES.map(
      (name) => this.#root.getAttribute(name),
    );
    queueMicrotask(() => {
      this.#stateMachine.closeDeferredTeardownWindow();
      this.#detachAttributeSnapshot = null;
      if (!this.#root.isConnected) this.#settleDisconnection();
    });
  }

  #settleDisconnection() {
    this.#abortEnhancePipeline();
    coordinationService().unregister(this.#coordinationSession);
    this.#coordinationSession = 0;
    coordinationService().cancelFrame(this.#boundResponsiveCommit);
    releaseNativeScrollAnchoring(this.#root);
    this.#stopIntersectionObservation();
    this.#stopParagraphTierObservation();
    this.#context.destroy();
    this.#stateMachine.settleDisconnection();
    this.#clearResponsiveRetarget();
    this.#clearInitialFontRetry();
    this.#context.diagnosis.clear("tiqianFontWait");
    this.#removeReadyListener();
    this.#stopTypographyObservation();
    this.#stopLayoutWorkInputObservation();
    this.#stopWidthObservation();
    this.#stopContentObservation();
    // DetachedNavigationDisposal: swup and other HTML routers remove an entire
    // old article synchronously. Reconstructing every source paragraph here
    // blocks their scroll handoff and can visibly change the outgoing page.
    // Keep the backing in weak state for a possible reconnection, but cancel all
    // work and release document-scoped styles without touching detached DOM.
    if (this.#stateMachine.snapshotAdopted || isLoadedSnapshotAdopted(this.#root)) {
      detachLoadedSnapshot(this.#root);
    }
    if (this.#stateMachine.runtimeActive) detachRuntimeRoot(this.#root);
    if (this.#stateMachine.workerAttached) {
      // tiqian:detach already cancelled the job, so the pool's detach has no
      // in-flight work to finish on this disconnected root.
      coordinationService().layoutJobPool.detach(this.#root);
      this.#stateMachine.workerAttached = false;
    }
    this.#releaseSnapshotFontSession();
    this.#root.removeAttribute(SNAPSHOT_RENDER_FONT_ATTRIBUTE);
  }

  #canAdoptRawDomMoveReconnection() {
    if (this.#stateMachine.connected || !this.#stateMachine.deferredTeardown) return false;
    if (!this.#stateMachine.runtimeActive || this.disabled) return false;
    if (this.#stateMachine.snapshotAdopted || isLoadedSnapshotAdopted(this.#root)) {
      // Snapshot-based raw-DOM backup keeps the restore and re-adopt path. Its backing is
      // cheap to rebuild and shares document-scoped styles with the runtime.
      return false;
    }
    const snapshot = this.#detachAttributeSnapshot;
    if (snapshot == null) return false;
    return OBSERVED_ATTRIBUTES.every(
      (name, index) => this.#root.getAttribute(name) === snapshot[index],
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
  #adoptRawDomMoveReconnection() {
    this.#detachAttributeSnapshot = null;
    this.#stateMachine.adoptRawDomMoveReconnection();
    this.#ensureViewportResizeListener();
    this.#observeWidth();
    this.#observeTypography();
    this.#observeContent();
    this.#lastObservedWidth = fragmentedBorderBoxInlineSize(this.#root);
    this.#scheduleResponsiveGeometryCommit();
    this.#scheduleTypographyCheck();
  }

  #attributeChanged(name: string, oldValue: string | null, newValue: string | null) {
    if (oldValue === newValue) return;
    if (name === "disabled") {
      // DisabledAttributeOwnsTeardown: adding the attribute uses the same
      // source restoration and cancellation path as a connected lifecycle
      // restart; connectedCallback then stops before any new work. Removing it
      // re-enters the complete snapshot/runtime lifecycle from semantic source.
      if (this.#stateMachine.connected) this.#restartConnectedLifecycle();
      return;
    }
    if (name === "snapshot-ref") {
      // UpgradeAttributeReactionGuard: when an SSR element is defined after it
      // was parsed, the platform reports its existing observed attributes
      // before connectedCallback. `isConnected` is already true at that point,
      // but this is not a client navigation and must not discard the server's
      // snapshot-font marker.
      if (this.#stateMachine.connected) this.#restartConnectedLifecycle();
      return;
    }
    if (
      name !== "emphasis-dot-gap-em" &&
      name !== "strong-as-emphasis-marks"
    ) return;
    if (!this.#root.isConnected) return;
    // LatestObservedAttributeGeneration: strong emphasis controls snapshot
    // eligibility, while all public options belong to the same connection
    // generation. An initial async gate must never commit captured old values.
    if (!this.#stateMachine.dispatched) {
      this.#restartConnectedLifecycle();
      return;
    }
    if (this.#stateMachine.snapshotAdopted || isLoadedSnapshotAdopted(this.#root)) {
      this.#invalidateSnapshotAndEnhance();
      return;
    }
    this.#refreshRuntimeFromSource();
  }

  #baseEnhanceOptions(): HostEnhanceOptionsBag | undefined {
    const emphasisDotGapEm = this.emphasisDotGapEm;
    const strongAsEmphasisMarks = this.strongAsEmphasisMarks;
    if (
      emphasisDotGapEm == null &&
      !strongAsEmphasisMarks
    ) {
      return undefined;
    }
    return {
      ...(emphasisDotGapEm == null ? {} : { emphasisDotGapEm }),
      ...(strongAsEmphasisMarks ? { strongAsEmphasisMarks: true } : {}),
    };
  }

  #deferInitialEnhancementUntilFontsSettle(generation: number, completion: Promise<unknown>) {
    this.#initialFontRetry ??= createInitialFontRetryController(this.#root, {
      fonts: this.#root.ownerDocument?.fonts ?? null,
      isGenerationCurrent: (candidate) => candidate === this.#context.generation,
      typographyElements: () => typographyElements(this.#root),
      restartConnectedLifecycle: () => this.#restartConnectedLifecycle(),
    });
    this.#initialFontRetry.deferUntilFontsSettle(generation, completion);
  }

  #clearInitialFontRetry() {
    this.#initialFontRetry?.clear();
  }

  #clearLifecycleDiagnostics() {
    this.#context.diagnosis.clear("tiqianCapabilityIssue");
    this.#context.diagnosis.clear("tiqianEnhanceMs");
    this.#context.diagnosis.clear("tiqianLoadMs");
    this.#context.diagnosis.clear("tiqianMaxSliceMs");
    this.#context.diagnosis.clear("tiqianRelayoutMs");
    this.#context.diagnosis.clear("tiqianRelayoutMaxSliceMs");
    this.#context.diagnosis.clear("tiqianFontWait");
    this.#context.diagnosis.clear("tiqianSnapshotLiveIssue");
    this.#context.diagnosis.clear("tiqianSnapshotCount");
    this.#context.diagnosis.clear("tiqianSnapshotMiss");
  }

  #restartConnectedLifecycle() {
    this.#abortEnhancePipeline();
    // Reconnect starts a fresh context: disconnect destroyed the previous
    // one and dropped it from the registry, so the constructor re-registers.
    this.#context = createEnhanceContext(this.#root);
    this.#stateMachine.bumpEnhanceRequest();
    this.#stateMachine.dispatched = false;
    this.#stateMachine.completionGateOpen = false;
    this.#stateMachine.snapshotAdopted = false;
    this.#snapshotEnhancedCount = 0;
    this.#removeReadyListener();
    this.#clearInitialFontRetry();
    this.#stopTypographyObservation();
    this.#stopLayoutWorkInputObservation();
    this.#stopWidthObservation();
    this.#stopContentObservation();
    restoreLoadedSnapshot(this.#root);
    if (this.#stateMachine.runtimeActive) destroyRuntimeRoot(this.#rootState, this.#context, this.#root);
    this.#stateMachine.runtimeActive = false;
    this.#releaseSnapshotFontSession();
    this.#root.removeAttribute(SNAPSHOT_RENDER_FONT_ATTRIBUTE);
    releaseNativeScrollAnchoring(this.#root);
    if (this.#root.isConnected) this.mount();
  }

  // SnapshotAdoptionAnchorCompensation adapter: the adoption loop in
  // precomputed.js commits one paragraph per cooperative slice; this feeds
  // its per-commit bracket from this element's anchor policy.
  #snapshotAdoptionAnchors(): SnapshotAdoptAnchors {
    return {
      capture: () => captureViewportAnchor(this.#root),
      compensate: (anchor) => compensateViewportAnchor(this.#root, anchor),
    };
  }

  async #dispatchProgressiveEnhance(
    generation: number,
    {
      beforeDispatch = null,
      paragraphSelector = null,
      revalidateSnapshotFont = true,
    }: TiqianEnhanceDispatchOptions = {},
  ): Promise<boolean> {
    // The dispatch runs under the lifecycle whose controller occupies the
    // transaction slot at entry; capturing the signal here keeps this dispatch
    // bound to its own lifecycle even if a restart replaces the slot later.
    const signal = this.#stateMachine.transaction.abortController?.signal ?? null;
    const request = this.#stateMachine.claimEnhanceRequest();
    // PlainHostPreparedBridge: the runtime lowers every paragraph through
    // the prepared-DOM bridge (ADR 0053 B8.3c), so a host without a snapshot
    // font session needs that bridge installed before layout starts. The
    // snapshot-session path installs it through loadSnapshotFontFallback; this
    // await covers the remaining hosts and leaves an already-installed
    // renderer (a snapshot session or a test fixture) untouched.
    const bridge = await raceAbort(signal, ensurePreparedDomBridge());
    if (bridge.aborted) return false;
    this.#beginLayoutWork();
    const baseOptions = {
      ...(this.#baseEnhanceOptions() ?? {}),
      ...(paragraphSelector ? { paragraphSelector } : {}),
    };
    const needsDash = needsCjkDashShaping(this.#root);
    let snapshotFontSession: BrowserFontSessionHandle | null = null;
    const snapshotFontSessionAlreadyPrepared = !revalidateSnapshotFont &&
      this.#snapshotFontSession?.reference === this.#root.getAttribute("snapshot-ref");
    try {
      const preparedSession = await raceAbort(signal, this.#prepareSnapshotFontSession(
        generation,
        request,
        revalidateSnapshotFont,
        signal,
      ));
      if (preparedSession.aborted) {
        this.#releaseSnapshotFontSession();
        return false;
      }
      snapshotFontSession = preparedSession.value;
      this.#context.diagnosis.clear("tiqianSnapshotFontMiss");
    } catch (error) {
      if (
        this.#root.isConnected && generation === this.#context.generation &&
        request === this.#stateMachine.transaction.enhanceRequest
      ) this.#releaseSnapshotFontSession();
      this.#context.diagnosis.set("tiqianSnapshotFontMiss", snapshotFontMissDatasetValue(error as TiqianElementSnapshotFontMissCandidate));
      console.warn("Tiqian Web snapshot font session unavailable; using browser metrics", error);
    }
    if (
      !this.#root.isConnected || generation !== this.#context.generation ||
      request !== this.#stateMachine.transaction.enhanceRequest || signal?.aborted
    ) {
      if (!this.#root.isConnected || generation !== this.#context.generation || signal?.aborted) {
        this.#releaseSnapshotFontSession();
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
    this.#context.diagnosis.clear("tiqianExactLayoutIssue");
    this.#root.removeAttribute(SNAPSHOT_PREPARED_FALLBACK_ATTRIBUTE);
    if (snapshotFontSession) {
      try {
        this.#snapshotFontSession!.installRenderFont(
          this.#root,
          snapshotFontSession.renderFontFamilies,
        );
        this.#root.setAttribute(SNAPSHOT_RENDER_FONT_ATTRIBUTE, "true");
        // HostRenderFontReadyBeforeCommit: server replay already owns the
        // layout metrics, but CSS must finish loading the proven host faces before the
        // first paragraph is committed. This avoids a second font-driven pass
        // and prevents progressive frames from painting a fallback face.
        // WidthOnlySnapshotFontSessionReuse: replay tables and loaded host faces do not change
        // when only the content-box measure changes. Typography/font observers
        // still take the validating path; a responsive retarget can start the
        // latest-width paragraph queue without repeating font probes first.
        if (!snapshotFontSessionAlreadyPrepared) {
          const renderFont = await raceAbort(signal, this.#snapshotFontSession!.prepareRenderFont(this.#root, snapshotFontSession));
          if (renderFont.aborted) {
            this.#releaseSnapshotFontSession();
            return false;
          }
        }
        if (
          !this.#root.isConnected || generation !== this.#context.generation ||
          request !== this.#stateMachine.transaction.enhanceRequest || signal?.aborted
        ) {
          this.#releaseSnapshotFontSession();
          return false;
        }
      } catch (error) {
        if (
          !this.#root.isConnected || generation !== this.#context.generation ||
          request !== this.#stateMachine.transaction.enhanceRequest
        ) {
          this.#releaseSnapshotFontSession();
          return false;
        }
        this.#releaseSnapshotFontSession();
        snapshotFontSession = null;
        this.#context.diagnosis.set("tiqianSnapshotFontMiss", "SnapshotRenderFontStyleUnavailable");
        console.warn("Tiqian Web snapshot render font style unavailable; using browser metrics", error);
      }
    }
    if (!snapshotFontSession) {
      this.#root.removeAttribute(SNAPSHOT_RENDER_FONT_ATTRIBUTE);
    }
    // BrowserDashCapabilityBeforeDispatch: the browser no longer starts an
    // asynchronous HarfBuzz probe. Resolve the immediate capability result
    // before the first layout so a dash paragraph is never laid out once as
    // pending and then redundantly retried. An exact server-replay session is
    // carried separately and remains the authoritative dash path.
    const dashCapability = needsDash
      ? await raceAbort(signal, prepareCjkDashShapingIfNeeded(this.#root, {
          ...baseOptions,
          ...(snapshotFontSession ? { snapshotFontSession } : {}),
        }))
      : { aborted: false as const, value: { status: "not-needed" as const } };
    if (dashCapability.aborted) {
      this.#releaseSnapshotFontSession();
      return false;
    }
    const cjkDashCapability = dashCapability.value;
    if (
      !this.#root.isConnected || generation !== this.#context.generation ||
      request !== this.#stateMachine.transaction.enhanceRequest || signal?.aborted
    ) {
      this.#releaseSnapshotFontSession();
      return false;
    }
    // Capture the input signature for cancellation. Kotlin reads the live width
    // again for each paragraph, while this coordinator cancels the remaining
    // job on the next frame if the effective line measure changes.
    const layoutOperation = this.#beginLayoutWork({ usesCapturedMeasure: true });
    this.#stateMachine.dispatched = true;
    this.#stateMachine.runtimeActive = true;
    this.#stateMachine.completionGateOpen = true;
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
        const channel = await raceAbort(signal, import("@tiqian/core/core/engine/web-worker/worker-channel.js"));
        if (channel.aborted) return false;
        const prepareJob = await raceAbort(signal, channel.value.createPrepareJob(
          this.#root,
          snapshotFontSession,
          preparedOptions,
          // AbortSignalStandardShell: the worker channel's generational
          // isCurrent predicate stays the cancellation kernel; the lifecycle
          // signal is its standard shell, consulted at every kernel check.
          () => this.#root.isConnected && generation === this.#context.generation &&
            request === this.#stateMachine.transaction.enhanceRequest &&
            layoutOperation === this.#stateMachine.transaction.layoutOperation &&
            !(signal?.aborted ?? false),
        ));
        if (prepareJob.aborted) return false;
        if (prepareJob.value) {
          const prepared = await raceAbort(signal, coordinationService().runPrepare(this.#coordinationSession, prepareJob.value));
          if (prepared.aborted) return false;
        }
      } catch (error) {
        // SnapshotWorkerFailureMustStayNative: synchronous Kotlin/JS fallback can
        // block scroll under JIT restrictions. Progressive enhancement will
        // retain source DOM for requests without a Worker plan.
        console.warn("Tiqian Web layout Worker unavailable; retaining native paragraphs", error);
      }
      if (
        !this.#root.isConnected || generation !== this.#context.generation ||
        request !== this.#stateMachine.transaction.enhanceRequest ||
        layoutOperation !== this.#stateMachine.transaction.layoutOperation || signal?.aborted
      ) {
        if (!this.#root.isConnected || generation !== this.#context.generation || signal?.aborted) {
          this.#releaseSnapshotFontSession();
        }
        return false;
      }
    }
    this.#ensureLayoutWorker();
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
    this.#context.diagnosis.set("tiqianEnhanceOptions", JSON.stringify(preparedOptions));
    const runAnchor = captureViewportAnchor(this.#root);
    try {
      enhanceProgressively(this.#rootState, coordinationService().layoutJobPool, this.#context, this.#root, preparedOptions);
    } finally {
      compensateViewportAnchor(this.#root, runAnchor);
      releaseNativeScrollAnchoring(this.#root);
    }
    this.#syncLayoutWorker();
    return true;
  }

  #ensureLayoutWorker() {
    // WorkerPolledScheduling: attach before dispatch so the job is built
    // coordinated from the start and every slice comes from a grant. The
    // dispatch task runs inside the coordinator frame, so the first polled
    // grant lands in the same frame under the shared budget.
    const pool = coordinationService().layoutJobPool;
    pool.attach(this.#root);
    this.#stateMachine.workerAttached = true;
    coordinationService().registerWorker(this.#coordinationSession, this.#root);
  }

  #syncLayoutWorker() {
    const pool = coordinationService().layoutJobPool;
    if (!this.#stateMachine.workerAttached) return;
    coordinationService().setWorkerActive(this.#coordinationSession, pool.hasJob(this.#root));
    this.#observeParagraphTiers(pool);
    coordinationService().requestWorkerFrame(this.#coordinationSession);
  }

  #deactivateLayoutWorker() {
    if (!this.#stateMachine.workerAttached) return;
    coordinationService().setWorkerActive(this.#coordinationSession, false);
  }

  #observeParagraphTiers(pool: LayoutJobPool) {
    const count = pool.paragraphCount(this.#root);
    if (count === 0) {
      this.#stopParagraphTierObservation();
      return;
    }
    if (!this.#paragraphObserver && typeof IntersectionObserver === "undefined") return;
    this.#paragraphObserver ??= new IntersectionObserver((entries) => {
      // The runtime graph can be rebuilt between dispatch and intersection;
      // read the pool live so tier flips always reach the current job.
      const live = coordinationService().layoutJobPool;
      for (let i = 0; i < entries.length; i++) {
        const entry = entries[i];
        const info = this.#paragraphTierIndex.get(entry.target);
        if (!info) continue;
        const tier = this.#paragraphTierFromEntry(entry);
        if (tier === info.tier) continue;
        info.tier = tier;
        // Tier flips go straight to the running job's pending counters, so
        // the next polled frame reorders the queue without rescanning.
        if (live && live.hasJob(this.#root)) {
          live.setParagraphTier(this.#root, info.index, tier);
        }
      }
    }, { rootMargin: "100% 0px" });
    // Paragraph hosts survive relayout; atomic swaps replace only their
    // children. The diff converges: a stable article adds and drops nothing
    // and the observer set stops churning.
    const live = new Set<Element>();
    for (let index = 0; index < count; index++) {
      const paragraph = pool.paragraphAt(this.#root, index);
      if (!paragraph) continue;
      live.add(paragraph);
      const info = this.#paragraphTierIndex.get(paragraph);
      if (!info) {
        this.#paragraphTierIndex.set(paragraph, { index, tier: 1 });
        this.#paragraphObserver.observe(paragraph);
      } else {
        info.index = index;
      }
    }
    for (const paragraph of this.#paragraphTierIndex.keys()) {
      if (live.has(paragraph)) continue;
      this.#paragraphObserver.unobserve(paragraph);
      this.#paragraphTierIndex.delete(paragraph);
    }
  }

  #paragraphTierFromEntry(entry: IntersectionObserverEntry): number {
    // ParagraphTierGating: the observer band spans one full viewport in each
    // direction via rootMargin 100%. A paragraph crossing the visible
    // viewport is tier 1; inside the band but off-screen is tier 2; beyond
    // the band is tier 3.
    if (!entry.isIntersecting) return 3;
    const rect = entry.boundingClientRect;
    if (!rect) return 2;
    const viewportHeight = globalThis.innerHeight || 0;
    return rect.bottom >= 0 && rect.top <= viewportHeight ? 1 : 2;
  }

  #stopParagraphTierObservation() {
    this.#paragraphObserver?.disconnect();
    this.#paragraphObserver = null;
    this.#paragraphTierIndex.clear();
  }

  async #prepareSnapshotFontSession(
    generation: number,
    request: number,
    revalidateExisting = true,
    signal: AbortSignal | null = null,
  ): Promise<BrowserFontSessionHandle | null> {
    const reference = this.#root.getAttribute("snapshot-ref");
    if (!reference) {
      if (generation === this.#context.generation && request === this.#stateMachine.transaction.enhanceRequest) {
        this.#releaseSnapshotFontSession();
      }
      return null;
    }
    if (this.#snapshotFontRejectedAttempt === this.#snapshotFontAttemptSignature(reference)) {
      return null;
    }
    // SnapshotFontValidationRenderProjection: the SSR marker owns first paint,
    // while this session owns runtime validation. Reassert the projection here
    // so a host hydrator cannot make snapshot-font validation depend on attribute
    // reconciliation timing. The caller removes it on every failed session.
    this.#root.setAttribute(SNAPSHOT_RENDER_FONT_ATTRIBUTE, "true");
    const loader = await loadSnapshotFontFallback();
    const existing = this.#snapshotFontSession;
    if (existing?.reference === reference) {
      // SnapshotFontSessionLiveRevalidation: reuse immutable server replay tables
      // only after the browser adapter revalidates every live snapshot input. A
      // caller that already proved this is a width-only retarget may reuse the
      // same live contract without repeating width-independent font probes.
      if (revalidateExisting) await existing.revalidate(this.#root, existing.handle);
      if (
        !this.#root.isConnected || generation !== this.#context.generation ||
        request !== this.#stateMachine.transaction.enhanceRequest ||
        this.#root.getAttribute("snapshot-ref") !== reference || signal?.aborted
      ) return null;
      return existing.handle;
    }
    const handle = await loader.prepareBrowserFontSession(this.#root);
    if (
      !this.#root.isConnected || generation !== this.#context.generation ||
      request !== this.#stateMachine.transaction.enhanceRequest ||
      this.#root.getAttribute("snapshot-ref") !== reference || signal?.aborted
    ) {
      loader.releaseBrowserFontSession(handle);
      return null;
    }
    const previous = this.#snapshotFontSession;
    const next = createSnapshotFontSessionEntry(reference, handle, loader);
    this.#snapshotFontSession = next;
    if (previous && previous !== next) previous.release(previous.handle);
    return handle;
  }

  #releaseSnapshotFontSession() {
    const entry = this.#snapshotFontSession;
    if (!entry) return false;
    this.#snapshotFontSession = null;
    return releaseSnapshotFontSession(entry, this.#root);
  }

  #snapshotFontAttemptSignature(reference: string | null = this.#root.getAttribute("snapshot-ref")) {
    return snapshotFontAttemptSignature(this.#root, reference);
  }

  #beginLayoutWork({ usesCapturedMeasure = false, captureSignatures = usesCapturedMeasure }: TiqianLayoutWorkOptions = {}): number {
    this.#clearResponsiveRetarget();
    const viewportTypographyEntries = captureSignatures
      ? captureLayoutWorkViewportTypographyEntries(this.#root)
      : [];
    let typographySignature = "";
    if (captureSignatures) {
      for (let i = 1; i < viewportTypographyEntries.length; i++) {
        if (i > 1) typographySignature += "\u001e";
        typographySignature += viewportTypographyEntries[i].signature;
      }
    }
    const operation = this.#stateMachine.beginLayoutWork({
      usesCapturedMeasure,
      signaturesCaptured: captureSignatures,
      geometrySignature: captureSignatures
        ? responsiveGeometrySignature(this.#root)
        : "",
      measureSignature: captureSignatures
        ? this.#paragraphMeasureSignature()
        : "",
      typographySignature,
      maximumMeasure: captureSignatures && this.#root.hasAttribute("snapshot-ref") &&
        loadedSnapshotMaximumMeasureMatches(this.#root),
      viewportTypographyEntries,
    });
    this.#pendingCommittedMeasures = "";
    this.#stopTypographyObservation();
    this.#observeContent();
    if (usesCapturedMeasure) this.#observeLayoutWorkInputs();
    return operation;
  }

  #finishLayoutWorkAndObserve(expectedOperation: number | null = null): boolean {
    const stateMachine = this.#stateMachine;
    const transaction = stateMachine.transaction;
    const work = stateMachine.work;
    if (expectedOperation != null && expectedOperation !== transaction.layoutOperation) return false;
    const signaturesCaptured = work.signaturesCaptured;
    const rawGeometryChangedDuringWork = stateMachine.workInFlight &&
      (transaction.geometryRevision !== transaction.layoutWorkRevision || stateMachine.isInvalidated(InvalidationReason.ResponsiveCommit) ||
        (signaturesCaptured &&
          responsiveGeometrySignature(this.#root) !== work.geometrySignature));
    // ObserverBaselineAfterUncapturedLayout: progressive enhancement mutates
    // the paragraph DOM while ResizeObserver is paused. Seed its committed
    // width, grid and typography baselines from that final DOM exactly once;
    // leaving the old values in place makes the observer's first delivery
    // schedule a redundant full-page layout and can immediately invalidate a
    // responsive snapshot that was just adopted.
    // FinishedTypographyBaselineRefresh: the finished DOM is the new stable
    // state, so the baseline must be re-read from it. Keeping a pre-job
    // baseline works only while nothing else compares a live signature
    // against it; the drag-time commit path does exactly that once the root
    // width settles, and a mixed native/rendered DOM after a cancelled job
    // would misread renderer output as a host typography change. Refreshing
    // here triggers no comparison of its own; the next one just starts from
    // the true current state.
    const currentTypography = typographySignature(this.#root);
    // ResponsiveFinishSkipsDoomedSignatureReads: a finish that returns through
    // the responsive-commit branch stores no paragraph baseline. Width
    // movement puts every relayout finish onto that branch, and relayout
    // jobs capture no measure signature, so the live paragraph signatures
    // the finish read decided nothing and were discarded. Each read cost one
    // gBCR and one computed style per paragraph on DOM the job had just
    // dirtied. A finish reads the signatures only when it compares them
    // against a captured signature or stores them on the unchanged path.
    const signaturesConsumedByFinish = !rawGeometryChangedDuringWork ||
      (work.usesCapturedMeasure &&
        work.measureSignature !== "");
    const currentParagraphWidths = signaturesConsumedByFinish &&
        !work.usesCapturedMeasure
      ? paragraphWidthSignature(this.#root)
      : this.#lastParagraphWidths;
    let currentMeasures: string;
    if (signaturesConsumedByFinish) {
      currentMeasures = work.usesCapturedMeasure && !rawGeometryChangedDuringWork
        ? this.#lastParagraphMeasures
        : this.#paragraphMeasureSignature();
    } else {
      currentMeasures = this.#lastParagraphMeasures;
    }
    const currentMaximumMeasure = this.#root.hasAttribute("snapshot-ref") &&
      loadedSnapshotMaximumMeasureMatches(this.#root);
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
    // changes paragraph line-height and positioning after it commits measured
    // line boxes. Comparing that output signature with the captured native
    // source signature schedules a redundant destroy-and-enhance pass. Real
    // font, style and viewport changes are observed while work is in flight and
    // cancel the captured job before ready; completion only needs to reconcile
    // geometry revisions that survived those observers.
    const layoutInputsChangedDuringWork = stateMachine.isInvalidated(InvalidationReason.ResponsiveCommit) || (
      rawGeometryChangedDuringWork &&
      (!work.usesCapturedMeasure || effectiveLayoutChangedDuringWork)
    );
    // FinishedTypographyBaselineRefresh also covers the changed-inputs branch:
    // a follow-up commit runs on the next frame and compares a live signature
    // against this baseline, so both branches must leave the baseline at the
    // finished DOM state. Skipping it on the changed branch leaves the
    // pre-job value (empty before the first completed job) and the follow-up
    // commit misreads renderer output as a host typography change.
    this.#lastTypography = currentTypography;
    stateMachine.completionGateOpen = false;
    stateMachine.finishLayoutWork();
    this.#clearResponsiveRetarget();
    this.#stopLayoutWorkInputObservation();
    if (layoutInputsChangedDuringWork) {
      // A non-atomic progressive job may have observed intermediate widths, so
      // it must force one latest-width pass. Captured-measure relayout can let
      // the normal final measure comparison decide on the next frame.
      stateMachine.invalidate(InvalidationReason.ResponsiveCommit);
      if (work.usesCapturedMeasure) stateMachine.clearInvalidation(InvalidationReason.ResponsiveRelayout);
      else stateMachine.invalidate(InvalidationReason.ResponsiveRelayout);
      this.#ensureViewportResizeListener();
      this.#scheduleResponsiveGeometryCommit();
      return true;
    }
    if (stateMachine.isInvalidated(InvalidationReason.ContentDrift) && !this.#contentProbeFrame) {
      // ContentOnlyFinishCommit: an uncaptured job may have raced a host
      // edit. Resolve the flag with the read-only probe, never with the
      // commit path: the records are usually this job's own output, and a
      // commit scheduled on them alone enters the offscreen deferred queue,
      // where it later fires a width commit inside the drag debounce window.
      // The probe clears an engine-owned flag without scheduling anything and
      // schedules the commit itself only on proven drift. The finish still
      // falls through to store its baselines, exactly like a finish without
      // the flag.
      this.#ensureViewportResizeListener();
      const operation = transaction.layoutOperation;
      const contentProbeFrame: FrameTaskCallback = () => {
        this.#contentProbeFrame = null;
        if (!this.#root.isConnected || operation !== this.#stateMachine.transaction.layoutOperation) return;
        this.#probeContentDrift();
      };
      this.#contentProbeFrame = contentProbeFrame;
      coordinationService().requestFrame(contentProbeFrame);
    }
    stateMachine.clearInvalidation(InvalidationReason.ResponsiveCommit);
    stateMachine.clearInvalidation(InvalidationReason.ResponsiveRelayout);
    this.#lastWidth = fragmentedBorderBoxInlineSize(this.#root);
    this.#lastParagraphMeasures = currentMeasures;
    this.#lastParagraphWidths = currentParagraphWidths;
    this.#observeWidth();
    this.#observeTypography();
    this.#observeContent();
    return true;
  }

  async #invalidateSnapshotAndEnhance({ restoreBeforeLoad = false }: TiqianSnapshotInvalidateOptions = {}) {
    if (!this.#stateMachine.snapshotAdopted && !isLoadedSnapshotAdopted(this.#root)) return;
    const generation = this.#context.generation;
    const signal = this.#stateMachine.transaction.abortController?.signal ?? null;
    this.#stateMachine.dispatched = false;
    let activeRequest = this.#stateMachine.claimEnhanceRequest();
    this.#beginLayoutWork();
    const restoreImmediatelyBeforeDispatch = () => {
      if (!restoreLoadedSnapshot(this.#root)) throw new Error("Adopted snapshot could not be restored");
      this.#stateMachine.snapshotAdopted = false;
      this.#snapshotEnhancedCount = 0;
      this.#context.diagnosis.clear("tiqianSnapshotCount");
      if (this.#stateMachine.runtimeActive) {
        destroyRuntimeRoot(this.#rootState, this.#context, this.#root);
        this.#stateMachine.runtimeActive = false;
      }
    };
    if (restoreBeforeLoad) restoreImmediatelyBeforeDispatch();
    try {
      const runtime = await raceAbort(signal, loadTiqianRuntime());
      if (runtime.aborted) return;
      if (
        !this.#root.isConnected || generation !== this.#context.generation ||
        activeRequest !== this.#stateMachine.transaction.enhanceRequest || signal?.aborted
      ) return;
      const enhancement = this.#dispatchProgressiveEnhance(generation, restoreBeforeLoad
        ? undefined
        : { beforeDispatch: restoreImmediatelyBeforeDispatch });
      // Async functions run synchronously through their first await, so this
      // captures the request generation claimed by #dispatchProgressiveEnhance.
      activeRequest = this.#stateMachine.transaction.enhanceRequest;
      await enhancement;
    } catch (error) {
      this.#recoverSnapshotEnhanceFailure(generation, activeRequest, error);
    }
  }

  #recoverSnapshotEnhanceFailure(generation: number, request: number, error: unknown) {
    if (
      !this.#root.isConnected || generation !== this.#context.generation ||
      request !== this.#stateMachine.transaction.enhanceRequest
    ) return;
    // Runtime/module failure must not strand the element in an unobserved
    // transition. Normally the adopted snapshot is still live because restore
    // is deferred until the successful dispatch task; retain it and resume the
    // responsive observers. If an exceptional synchronous restore already ran,
    // the readable runtime/SSR backing remains the fallback instead.
    const snapshotStillLive = isLoadedSnapshotAdopted(this.#root);
    this.#stateMachine.snapshotAdopted = snapshotStillLive;
    this.#stateMachine.dispatched = snapshotStillLive || this.#stateMachine.runtimeActive;
    this.#stateMachine.completionGateOpen = false;
    this.#finishLayoutWorkAndObserve();
    this.#context.diagnosis.set("tiqianCapabilityIssue", "RuntimeLoadFailed");
    console.warn("Tiqian Web runtime failed to load after snapshot invalidation", error);
  }

  #acceptValidatedSnapshotGeometry() {
    // SnapshotValidationConsumesObservedGeometry: adoption rechecks live width,
    // typography and rendered geometry immediately before its atomic commit.
    // Resize/observer notifications recorded while that validation was in
    // flight are therefore already represented by the adopted result. Reset
    // only the consumed bookkeeping here; a later browser event still arrives
    // after observation resumes and invalidates the snapshot normally.
    this.#stateMachine.consumeObservedGeometry();
  }

  async #tryReadoptSnapshotAtMaximumMeasure() {
    if (!this.#root.hasAttribute("snapshot-ref")) return;
    const generation = this.#context.generation;
    const signal = this.#stateMachine.transaction.abortController?.signal ?? null;
    const startedAt = Date.now();
    const operation = this.#beginLayoutWork();
    const runtimeSnapshotBackingRestored = this.#stateMachine.runtimeActive;
    if (runtimeSnapshotBackingRestored) {
      // RuntimeSnapshotBackingRestore: the first runtime enhancement retains
      // the exact server-rendered nodes as its teardown backing. Snapshot
      // validation must inspect that immutable SSR artifact, never the current
      // runtime rendering whose structure and digest are intentionally different.
      // DOM event dispatch is synchronous, so restoration and the validation
      // start stay in one task and cannot expose unvalidated SSR as a settled
      // state. A miss below immediately starts a fresh runtime enhancement.
      this.#stateMachine.dispatched = false;
      destroyRuntimeRoot(this.#rootState, this.#context, this.#root);
      this.#stateMachine.runtimeActive = false;
    }
    try {
      const snapshot = await tryAdoptRequestedSnapshot(
        this.#root,
        this.#root.ownerDocument,
        () => this.#root.isConnected && generation === this.#context.generation &&
          operation === this.#stateMachine.transaction.layoutOperation &&
          !(signal?.aborted ?? false),
        this.#snapshotAdoptionAnchors(),
      );
      // The adoption commits are over; hand the scroller back to the
      // browser's own anchoring until the next commit path holds it.
      releaseNativeScrollAnchoring(this.#root);
      if (
        !this.#root.isConnected || generation !== this.#context.generation ||
        operation !== this.#stateMachine.transaction.layoutOperation || signal?.aborted
      ) {
        if (snapshot.adopted) restoreLoadedSnapshot(this.#root);
        return;
      }
      if (!snapshot.adopted) {
        this.#context.diagnosis.set("tiqianSnapshotMiss", snapshot.reason ?? "SnapshotNotAdopted");
        // Full validation is intentionally fail-closed. The existing runtime
        // DOM stayed live throughout. It still carries the previous narrow
        // measure, so a maximum-measure miss must finish with a runtime
        // relayout instead of blessing stale lines as current geometry.
        this.#recoverRuntimeAfterSnapshotMiss(
          operation,
          snapshot.reason,
          runtimeSnapshotBackingRestored,
        );
        return;
      }
      this.#context.diagnosis.clear("tiqianSnapshotMiss");
      this.#stateMachine.snapshotAdopted = true;
      this.#snapshotEnhancedCount = snapshot.count;
      const completionSelector = snapshotCompletionSelector(this.#root);
      if (completionSelector) {
        const runtime = await raceAbort(signal, loadTiqianRuntime());
        if (runtime.aborted) return;
        if (
          !this.#root.isConnected || generation !== this.#context.generation ||
          operation !== this.#stateMachine.transaction.layoutOperation || signal?.aborted
        ) {
          return;
        }
        this.#acceptValidatedSnapshotGeometry();
        await this.#dispatchProgressiveEnhance(generation, {
          paragraphSelector: completionSelector,
        });
        return;
      }
      this.#releaseSnapshotFontSession();
      this.#stateMachine.dispatched = true;
      this.#stateMachine.completionGateOpen = true;
      this.#acceptValidatedSnapshotGeometry();
      this.#root.dispatchEvent(new CustomEvent("tiqian:relayout-ready", {
        bubbles: true,
        composed: true,
        detail: {
          enhancedCount: snapshot.count,
          issueCount: 0,
          durationMs: Date.now() - startedAt,
          maxSliceMs: 0,
          relayout: true,
          snapshot: true,
        },
      }));
    } catch (error) {
      if (
        !this.#root.isConnected || generation !== this.#context.generation ||
        operation !== this.#stateMachine.transaction.layoutOperation || signal?.aborted
      ) return;
      this.#context.diagnosis.set("tiqianSnapshotMiss", "SnapshotValidationFailed");
      console.warn("Tiqian Web responsive snapshot validation failed", error);
      this.#recoverRuntimeAfterSnapshotMiss(
        operation,
        "SnapshotValidationFailed",
        runtimeSnapshotBackingRestored,
      );
    }
  }

  #recoverRuntimeAfterSnapshotMiss(
    operation: number,
    reason: string,
    runtimeSnapshotBackingRestored = false,
  ) {
    if (operation !== this.#stateMachine.transaction.layoutOperation) return;
    if (runtimeSnapshotBackingRestored) {
      // Validation failed after the synchronous SSR backing restore. Rebuild
      // runtime state from that source for every miss category; a width-only
      // relayout cannot operate after the prior runtime instance was destroyed.
      const generation = this.#context.generation;
      this.#dispatchProgressiveEnhance(generation).catch((error) => {
        if (!this.#root.isConnected || generation !== this.#context.generation) return;
        this.#finishLayoutWorkAndObserve();
        this.#context.diagnosis.set("tiqianCapabilityIssue", "FontCapabilityPreparationFailed");
        console.warn("Tiqian Web snapshot miss recovery failed", error);
      });
      return;
    }
    if (RESPONSIVE_SNAPSHOT_GEOMETRY_MISSES.has(reason)) {
      this.#relayoutRuntimeAfterSnapshotMiss(operation);
      return;
    }
    if (!this.#stateMachine.runtimeActive) {
      // ReadoptionMissMustReclaimSource: a rapid resize can cancel the active
      // runtime job before a maximum-measure snapshot validation begins. If
      // that validation then misses, the DOM is readable native backing but no
      // owner remains to enhance it. Start a fresh latest-geometry job instead
      // of observing the permanently unclaimed source.
      const generation = this.#context.generation;
      this.#dispatchProgressiveEnhance(generation).catch((error) => {
        if (!this.#root.isConnected || generation !== this.#context.generation) return;
        this.#finishLayoutWorkAndObserve();
        this.#context.diagnosis.set("tiqianCapabilityIssue", "FontCapabilityPreparationFailed");
        console.warn("Tiqian Web unclaimed snapshot miss recovery failed", error);
      });
      return;
    }
    // Source, typography, font-contract and unknown validation failures make
    // the old lowered source or snapshot-font session untrustworthy. Re-lower and
    // rebuild the font session; a cheap width-only relayout is valid only for
    // the two explicit geometry miss reasons above.
    const generation = this.#context.generation;
    this.#dispatchProgressiveEnhance(generation).catch((error) => {
      if (!this.#root.isConnected || generation !== this.#context.generation) return;
      this.#finishLayoutWorkAndObserve();
      this.#context.diagnosis.set("tiqianCapabilityIssue", "FontCapabilityPreparationFailed");
      console.warn("Tiqian Web snapshot miss recovery failed", error);
    });
  }

  #dispatchRelayout(observedMeasures: string | null = null) {
    if (!this.#stateMachine.runtimeActive) {
      this.#finishLayoutWorkAndObserve();
      return;
    }
    this.#beginLayoutWork({ usesCapturedMeasure: true, captureSignatures: false });
    this.#stateMachine.markWorkAsRelayout();
    // Callers on the commit paths pass the signature they just computed;
    // recomputing here is reserved for dispatches that never went through a
    // commit pass (snapshot-miss recovery).
    this.#pendingCommittedMeasures = observedMeasures ?? this.#paragraphMeasureSignatureFromObserved();
    this.#stateMachine.dispatched = true;
    this.#stateMachine.completionGateOpen = true;
    this.#ensureLayoutWorker();
    // RunToCompletionAnchorBracket: relayout dispatches take the same bracket
    // as enhance dispatches; an uncoordinated relayout runs its whole job
    // synchronously inside this call.
    const relayoutAnchor = captureViewportAnchor(this.#root);
    try {
      relayout(this.#rootState, coordinationService().layoutJobPool, this.#context, this.#root);
    } finally {
      compensateViewportAnchor(this.#root, relayoutAnchor);
      releaseNativeScrollAnchoring(this.#root);
    }
    this.#syncLayoutWorker();
  }

  #relayoutRuntimeAfterSnapshotMiss(operation: number) {
    if (operation !== this.#stateMachine.transaction.layoutOperation) return;
    this.#dispatchRelayout();
  }

  #refreshRuntimeFromSource({ revalidateSnapshotFont = true }: TiqianSourceRefreshOptions = {}) {
    // A source refresh replaces the rendered paragraphs, so the seeded grid
    // metrics are for nodes about to leave the tree; drop them and let the
    // observer re-seed the rebuilt paragraphs.
    this.#gridMetricsState.metrics = null;
    const generation = this.#context.generation;
    if (this.#stateMachine.runtimeActive) {
      // ResponsiveNativeBacking: pre-broken Tiqian lines cannot reflow while a
      // new width or typography is being prepared. Restore the complete
      // semantic source first so every remaining paragraph responds through the
      // host cascade while viewport-near paragraphs are enhanced atomically.
      destroyRuntimeRoot(this.#rootState, this.#context, this.#root);
      this.#stateMachine.runtimeActive = false;
    }
    this.#dispatchProgressiveEnhance(generation, { revalidateSnapshotFont }).catch((error) => {
      if (!this.#root.isConnected || generation !== this.#context.generation) return;
      this.#finishLayoutWorkAndObserve();
      this.#context.diagnosis.set("tiqianCapabilityIssue", "FontCapabilityPreparationFailed");
      console.warn("Tiqian Web source refresh failed", error);
    });
  }

  #removeReadyListener() {
    if (!this.#readyListener) return;
    this.#root.removeEventListener("tiqian:ready", this.#readyListener);
    this.#root.removeEventListener("tiqian:relayout-ready", this.#readyListener);
    this.#readyListener = null;
  }

  #observeWidth() {
    if (this.#sizeObservation) {
      // AdoptedWidthObservation: content reconcile adopts paragraphs after
      // the observer already exists. Seed and observe targets it has not
      // seen, so an adopted paragraph responds to later width changes.
      const paragraphs = rootScopedParagraphs(this.#root);
      for (let i = 0; i < paragraphs.length; i++) {
        const paragraph = paragraphs[i];
        // Metrics seeding is decoupled from the width map: a source refresh
        // drops the seeds while surviving paragraph nodes stay in the width
        // map, and the width gate alone would then strand them on the
        // read-based fallback for every commit.
        if (!this.#gridMetricsState.metrics?.has(paragraph)) seedParagraphGridMetrics(this.#gridMetricsState, paragraph);
        if (this.#sizeObservation.widths.has(paragraph)) continue;
        this.#sizeObservation.widths.set(paragraph, fragmentedBorderBoxInlineSize(paragraph));
        this.#sizeObservation.observe(paragraph);
      }
      return;
    }
    // ResponsiveInlineSizeObservation: takeover intentionally changes block
    // height. Seed and compare only border-box inline sizes so those commits do
    // not trigger a redundant responsive pass. Persistent observation without
    // pausing ensures drag interactions and live geometry changes are never lost.
    const widths = new WeakMap<Element, number>();
    const targets = [
      this.#root,
      ...rootScopedParagraphs(this.#root),
    ];
    for (let i = 0; i < targets.length; i++) {
      const target = targets[i];
      widths.set(target, fragmentedBorderBoxInlineSize(target));
      if (target !== this.#root) seedParagraphGridMetrics(this.#gridMetricsState, target);
    }
    this.#sizeObservation = createRootSizeObservation({
      root: this.#root,
      widths,
      onRootEntry: ({ width, height }) => {
        this.#lastObservedWidth = width;
        coordinationService().update(this.#coordinationSession, { inlineSize: width, area: width * (height || width * 0.6) });
        if (!this.#stateMachine.inViewport && this.#stateMachine.workInFlight) {
          // A width change while the root stays off-screen keeps pushing the
          // worker's deferred wake-up, so only the final width is laid out.
          coordinationService().refreshWorkerDeferred(this.#coordinationSession);
        }
      },
      onWidthsChanged: () => {
        if (this.#commitResponsiveGeometryPrePaint()) return;
        this.#scheduleResponsiveGeometryCommit();
      },
    });
    this.#sizeObservation.start(targets);
    this.#ensureViewportResizeListener();
  }

  #ensureViewportResizeListener() {
    if (!this.#viewportResizeInvalidation) {
      this.#viewportResizeInvalidation = createViewportResizeInvalidationSource({
        onResize: () => {
          // ViewportResizeValidatesCapturedLayoutInputs: viewport resize is only a
          // signal that layout inputs may have changed. A fixed/max-width article
          // can receive the same event while every paragraph measure stays intact;
          // restoring native source before checking those inputs creates a visible
          // false rollback. Coalesce the live measure, maximum-snapshot and
          // typography comparison into the next pre-paint frame. A real change
          // still cancels the captured job there, while an equivalent grid keeps
          // both its committed paragraphs and remaining work.
          if (this.#stateMachine.workInFlight && this.#stateMachine.work.usesCapturedMeasure) {
            this.#stateMachine.bumpGeometryRevision();
            this.#stateMachine.invalidate(InvalidationReason.ResponsiveCommit);
            this.#scheduleResponsiveRetarget();
            return;
          }
          // Uncaptured snapshot/font preparation revalidates live geometry before
          // it commits or begins captured work. It is not bound to the pre-resize
          // measure, so a raw viewport signal alone must not invalidate it.
          if (this.#stateMachine.workInFlight) {
            return;
          }
          this.#handleResponsiveGeometryChange();
        },
      });
    }
    this.#viewportResizeInvalidation.start();
  }

  #handleResponsiveGeometryChange() {
    this.#stateMachine.bumpGeometryRevision();
    // ResponsiveNativeRetargetSingleFlight: once rendered/runtime work has
    // been rolled back to semantic source, further resize signals only move
    // the same next-frame target. Do not synchronously rescan the entire
    // article or start another snapshot-font preparation for every OS resize event.
    if (this.#stateMachine.isInvalidated(InvalidationReason.ResponsiveRelayout) && !this.#stateMachine.runtimeActive) {
      this.#stateMachine.invalidate(InvalidationReason.ResponsiveCommit);
      this.#scheduleResponsiveGeometryCommit();
      return;
    }
    const snapshotAdopted = this.#stateMachine.snapshotAdopted || isLoadedSnapshotAdopted(this.#root);
    const committedMeasureChanged = this.#stateMachine.dispatched && (
      this.#paragraphMeasureSignature() !== this.#lastParagraphMeasures ||
      (snapshotAdopted && !loadedSnapshotMaximumMeasureMatches(this.#root))
    );
    if (committedMeasureChanged) {
      if (this.#stateMachine.workInFlight && this.#stateMachine.work.usesCapturedMeasure) {
        this.#cancelCapturedLayoutForLatestGeometry();
        return;
      }
      if (snapshotAdopted) {
        // ResponsiveSnapshotRollbackAtFirstSafeSignal: a maximum-width
        // snapshot is stale when the live paragraph measure changes. Viewport
        // resize reaches this synchronously before paint; a container-only
        // ResizeObserver signal reaches it at the leading edge of the next
        // frame, outside the observer delivery loop.
        this.#invalidateSnapshotAndEnhance({ restoreBeforeLoad: true });
        return;
      }
      if (this.#stateMachine.runtimeActive) {
        // ResponsiveRuntimeDirectInPlaceRelayout: when typography is stable,
        // width changes do not tear down the rendered DOM to native text.
        // Direct single-frame in-place relayout computes the new line breaks
        // using WidthIndependentAnnotationCache and swaps DOM atomically.
        this.#stateMachine.invalidate(InvalidationReason.ResponsiveCommit);
        this.#scheduleResponsiveGeometryCommit();
        return;
      }
    }
    if (this.#stateMachine.workInFlight) {
      this.#stateMachine.invalidate(InvalidationReason.ResponsiveCommit);
      this.#scheduleResponsiveRetarget();
      return;
    }
    this.#scheduleResponsiveGeometryCommit();
  }

  #scheduleResponsiveGeometryCommit() {
    if (this.#stateMachine.workInFlight) {
      this.#stateMachine.invalidate(InvalidationReason.ResponsiveCommit);
      return;
    }
    coordinationService().requestFrame(this.#boundResponsiveCommit, this.#coordinationSession);
  }

  // PrePaintResponsiveCommit: ResizeObserver delivers after layout and
  // before paint, so a width-only commit that completes synchronously here
  // paints with the new width in the same frame; the scheduled commit paints
  // one frame of old lines first. Only the steady width-only case
  // qualifies — every other case keeps the scheduled commit's ordering
  // guarantees. Verified by demo/web/tests/resize-prepaint-commit.test.mjs.
  #commitResponsiveGeometryPrePaint() {
    if (!this.#root.isConnected || !this.#stateMachine.inViewport) return false;
    if (!this.#stateMachine.runtimeActive || !this.#stateMachine.dispatched) return false;
    if (this.#contentProbeFrame) return false;
    if (this.#stateMachine.snapshotAdopted || isLoadedSnapshotAdopted(this.#root)) return false;
    if (this.#root.ownerDocument?.fonts?.status === "loading") return false;
    if (this.#stateMachine.workInFlight) {
      // PreemptiveCrossingRelayout: without preemption only a drag's first
      // crossing reaches the pre-paint admission; later ones wait out the
      // scheduled cadence behind the in-flight job. A width-only relayout
      // is safe to replace — the runtime cancels it and rebuilds at the
      // latest width (WidthSnapshotPerRelayoutJob). Preempt only on a real
      // cell crossing; enhance and reconcile jobs are never replaced here.
      if (this.#stateMachine.work.kind !== "Relayout") return false;
      // ContentBeforeGeometry still rules: a pending reconcile keeps the
      // scheduled commit, whose pass re-lowers drifted content before any
      // width pass; a geometry-only preempt would relay stale text for the
      // rest of the drag.
      if (this.#stateMachine.isInvalidated(InvalidationReason.ContentDrift)) return false;
      const measures = this.#paragraphMeasureSignatureFromObserved();
      if (measures === this.#lastParagraphMeasures) return false;
      this.#lastWidth = this.#lastObservedWidth || fragmentedBorderBoxInlineSize(this.#root);
      this.#lastParagraphMeasures = measures;
      return this.#withRootObservationPaused(() => this.#dispatchRelayout(measures));
    }
    return this.#withRootObservationPaused(() => this.#commitResponsiveGeometryChange());
  }

  // One pause/resume protocol for both pre-paint admission paths: the root is
  // unobserved around the synchronous commit so its own height change
  // cannot queue a same-depth observation for the browser's ResizeObserver
  // loop guard to report, then re-observed with the original box option.
  #withRootObservationPaused(commit: TiqianRootPausedCommitFn): boolean {
    this.#sizeObservation?.unobserve(this.#root);
    try {
      commit();
      coordinationService().grantImmediate(this.#coordinationSession);
    } finally {
      this.#sizeObservation?.observe(this.#root);
    }
    return true;
  }

  #commitResponsiveGeometryChange() {
    if (!this.#root.isConnected) return;
    if (this.#stateMachine.workInFlight) {
      this.#stateMachine.invalidate(InvalidationReason.ResponsiveCommit);
      return;
    }
    if (!this.#stateMachine.inViewport && this.#lastObservedWidth != null) {
      // OffscreenTrailingWidthCheck: ResizeObserver delivers on animation
      // frames, so while the frame loop pauses mid-drag the observer goes
      // quiet and the off-screen debounce can expire although the width is
      // still moving. Read the live width before releasing the commit; a
      // moving width re-enters the trailing commit.
      const liveWidth = fragmentedBorderBoxInlineSize(this.#root);
      if (Math.abs(liveWidth - this.#lastObservedWidth) >= 0.5) {
        this.#lastObservedWidth = liveWidth;
        this.#stateMachine.invalidate(InvalidationReason.ResponsiveCommit);
        this.#scheduleResponsiveGeometryCommit();
        return;
      }
    }
    // Before the first snapshot/runtime commit there is no layout to update.
    // The initial job will read the latest live width once its font gate opens.
    const forceLatestWidth = this.#stateMachine.isInvalidated(InvalidationReason.ResponsiveRelayout) ||
      this.#stateMachine.isInvalidated(InvalidationReason.ResponsiveCommit);
    this.#stateMachine.clearInvalidation(InvalidationReason.ResponsiveCommit);
    this.#stateMachine.clearInvalidation(InvalidationReason.ResponsiveRelayout);
    if (!this.#stateMachine.dispatched) return;
    if (this.#stateMachine.isInvalidated(InvalidationReason.ContentDrift) && !this.#contentProbeFrame) {
      // ContentBeforeGeometry: one commit path serves ResizeObserver and
      // MutationObserver alike. Content goes first because re-lowering reads
      // the live width, so a concurrent width change is absorbed by the same
      // job; the reverse order would relayout stale text first. An idle
      // reconcile falls through so a width-only change still commits.
      this.#stateMachine.clearInvalidation(InvalidationReason.ContentDrift);
      const tainted = Array.from(this.#contentTainted);
      this.#contentTainted.clear();
      if (this.#stateMachine.snapshotAdopted || isLoadedSnapshotAdopted(this.#root)) {
        this.#invalidateSnapshotAndEnhance({ restoreBeforeLoad: true });
        return;
      }
      if (this.#dispatchContentReconcile(tainted)) {
        // ReconcileCommitPreservesWidthIntent: a work verdict returns before
        // the width pass runs, and the reconcile job re-lowers only drifted,
        // tainted and stranded paragraphs. A width change already pending at
        // this commit would die with the responsive bits beginLayoutWork
        // clears; the finish would then store the live width against stale
        // paragraphs and the change would never re-enter layout. Re-arm the
        // commit so the finish schedules one latest-width pass.
        const pendingWidth = this.#lastObservedWidth || fragmentedBorderBoxInlineSize(this.#root);
        if (forceLatestWidth || Math.abs(pendingWidth - this.#lastWidth) >= 0.5) {
          this.#stateMachine.invalidate(InvalidationReason.ResponsiveCommit);
        }
        return;
      }
    }
    const width = this.#lastObservedWidth || fragmentedBorderBoxInlineSize(this.#root);
    this.#lastObservedWidth = width;
    const widthsChanged = Math.abs(width - this.#lastWidth) >= 0.5;
    const paragraphWidths = widthsChanged ? this.#lastParagraphWidths : paragraphWidthSignature(this.#root);
    // LineLengthGridResponsiveInvalidation: the quantized measure signature
    // is computed on every commit, width changes included, so the same-named
    // gate below can skip in-cell width motion instead of dispatching a job
    // that reproduces identical paragraph DOM. Layout is clean at commit
    // time (the width read above already forced it), so the per-paragraph
    // reads here do not thrash.
    const paragraphMeasures = this.#paragraphMeasureSignatureFromObserved();
    const hostInlineSizeRefresh = widthsChanged && hasHostInlineSizeParagraph(this.#root);
    const measuresChanged = paragraphMeasures !== this.#lastParagraphMeasures;
    const signature = (widthsChanged && !this.#stateMachine.isInvalidated(InvalidationReason.TypographyRefreshForced))
      ? this.#lastTypography
      : typographySignature(this.#root);
    const typographyChanged = signature !== this.#lastTypography;
    if (!forceLatestWidth && !widthsChanged && !measuresChanged && !typographyChanged) {
      this.#observeWidth();
      return;
    }
    this.#lastWidth = width;
    this.#lastParagraphMeasures = paragraphMeasures;
    this.#lastParagraphWidths = paragraphWidths;

    const snapshotAdopted = this.#stateMachine.snapshotAdopted || isLoadedSnapshotAdopted(this.#root);
    const atMaximumMeasure = this.#root.hasAttribute("snapshot-ref") &&
      loadedSnapshotMaximumMeasureMatches(this.#root);
    if (snapshotAdopted) {
      if (atMaximumMeasure && !typographyChanged) {
        // MixedSnapshotCompletionResume: cancelling a captured runtime-only
        // job restores just its unkeyed source; the keyed snapshot remains
        // valid. Restart that partial job instead of treating the still-valid
        // snapshot as proof that every paragraph is settled.
        const completionSelector = snapshotCompletionSelector(this.#root);
        if (completionSelector && !this.#stateMachine.runtimeActive) {
          const generation = this.#context.generation;
          this.#dispatchProgressiveEnhance(generation, {
            paragraphSelector: completionSelector,
          }).catch((error) => {
            if (!this.#root.isConnected || generation !== this.#context.generation) return;
            this.#finishLayoutWorkAndObserve();
            this.#context.diagnosis.set("tiqianCapabilityIssue", "FontCapabilityPreparationFailed");
            console.warn("Tiqian Web snapshot completion restart failed", error);
          });
          return;
        }
        // A parent may keep growing after the paragraph has reached max-width.
        // The snapshot contract is still valid; do not churn the DOM.
        this.#lastTypography = signature;
        this.#observeWidth();
        this.#observeTypography();
      } else {
        this.#invalidateSnapshotAndEnhance();
      }
      return;
    }
    if (!this.#stateMachine.runtimeActive && atMaximumMeasure && !typographyChanged) {
      this.#tryReadoptSnapshotAtMaximumMeasure();
      return;
    }
    // A forced pass (viewport revalidation, stale follow-up) may only skip
    // against the CommittedMeasureLedger; a normal pass dedups against the
    // dispatch bookkeeping.
    const measureSettled = forceLatestWidth
      ? paragraphMeasures === this.#lastCommittedParagraphMeasures
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
      this.#lastTypography = signature;
      this.#observeWidth();
      this.#observeTypography();
      return;
    }
    // ResponsiveTypographyBeforeRebreak: a media query can change font
    // metrics in the same resize without mutating any class/style attribute.
    // Re-lower in that case; reserve the cheap width-only path for stable
    // typography.
    if (this.#root.ownerDocument?.fonts?.status === "loading") {
      this.#observeWidth();
      this.#observeTypography();
      this.#scheduleTypographyCheck(true);
      return;
    }
    if (typographyChanged) {
      this.#lastTypography = signature;
      this.#refreshRuntimeFromSource({ revalidateSnapshotFont: true });
      return;
    }
    if (this.#stateMachine.runtimeActive) {
      this.#dispatchRelayout(paragraphMeasures);
      return;
    }
    this.#refreshRuntimeFromSource({ revalidateSnapshotFont: false });
  }

  #removeViewportResizeListener() {
    this.#viewportResizeInvalidation?.stop();
    this.#viewportResizeInvalidation = null;
  }

  #stopWidthObservation() {
    this.#clearResponsiveRetarget();
    this.#sizeObservation?.stop();
    this.#sizeObservation = null;
    this.#gridMetricsState.metrics = null;
    this.#lastObservedWidth = 0;
    this.#removeViewportResizeListener();
  }

  #scheduleResponsiveRetarget() {
    const stateMachine = this.#stateMachine;
    if (!stateMachine.workInFlight || !stateMachine.work.usesCapturedMeasure) return;
    this.#clearResponsiveRetarget();
    const operation = stateMachine.transaction.layoutOperation;
    const responsiveRetargetFrame: FrameTaskCallback = () => {
      this.#responsiveRetargetFrame = null;
      const work = stateMachine.work;
      if (
        !this.#root.isConnected || !stateMachine.workInFlight ||
        !work.usesCapturedMeasure || operation !== stateMachine.transaction.layoutOperation
      ) return;
      if (layoutWorkViewportTypographyChanged(this.#root, work.viewportTypographyEntries)) {
        this.#cancelCapturedLayoutForTypographyChange();
        return;
      }
      const maximumMeasure = this.#root.hasAttribute("snapshot-ref") &&
        loadedSnapshotMaximumMeasureMatches(this.#root);
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
      const measureBaseline = work.measureSignature || this.#lastParagraphMeasures;
      if (
        this.#paragraphMeasureSignature() === measureBaseline &&
        maximumMeasure === work.maximumMeasure
      ) return;
      this.#cancelCapturedLayoutForLatestGeometry();
    };
    this.#responsiveRetargetFrame = responsiveRetargetFrame;
    coordinationService().requestFrame(responsiveRetargetFrame);
  }

  #clearResponsiveRetarget() {
    if (!this.#responsiveRetargetFrame) return;
    coordinationService().cancelFrame(this.#responsiveRetargetFrame);
    this.#responsiveRetargetFrame = null;
  }

  #observeTypography() {
    if (!this.#typographyInvalidation) {
      this.#typographyInvalidation = createTypographyInvalidationSource(this.#root, {
        onMutation: () => this.#scheduleTypographyCheck(),
        // Declared registry changes carry no FontFaceSetEvent; force past the
        // typography signature (declared sheets never enter the CSSOM it
        // reads) so the revalidate cycle re-collects the merged candidates.
        onDeclaredFacesChanged: () => this.#scheduleTypographyCheck(true),
        onFontEvent: async (event) => {
          const generation = this.#context.generation;
          const snapshotAdopted = this.#stateMachine.snapshotAdopted || isLoadedSnapshotAdopted(this.#root);
          let snapshotLiveIssue = null;
          if (snapshotAdopted) {
            try {
              snapshotLiveIssue = await loadedAdoptedSnapshotLiveIssue(
                this.#root,
                () => this.#root.isConnected && generation === this.#context.generation &&
                  (this.#stateMachine.snapshotAdopted || isLoadedSnapshotAdopted(this.#root)),
              );
            } catch {
              snapshotLiveIssue = "SnapshotLiveValidationFailed";
            }
          }
          if (!this.#root.isConnected || generation !== this.#context.generation ||
              snapshotLiveIssue === "superseded") return;
          if (snapshotAdopted && snapshotLiveIssue == null) {
            // SnapshotFontLoadCycleAlreadyValidated: snapshot adoption awaited
            // and probed every exact evidence face. The browser may dispatch the
            // corresponding loadingdone task only after observers resume; retain
            // the snapshot when its CSS face, typography and rendered geometry
            // contracts still hold instead of starting a redundant font cycle.
            return;
          }
          if (snapshotLiveIssue) this.#context.diagnosis.signal("tiqianSnapshotLiveIssue", snapshotLiveIssue);
          const relevantFaceLoaded = fontLoadingAffectsTypography(
            event as FontLoadingEventLike,
            typographyElements(this.#root),
          );
          const force = this.#stateMachine.isInvalidated(InvalidationReason.TypographyRefreshForced) || relevantFaceLoaded;
          if (this.#stateMachine.isInvalidated(InvalidationReason.DeferredTypographyCheck) || force) this.#scheduleTypographyCheck(force);
        },
      });
    }
    this.#typographyInvalidation.start();
  }

  #stopTypographyObservation() {
    this.#typographyInvalidation?.stop();
    if (this.#typographyFrame) coordinationService().cancelFrame(this.#typographyFrame);
    this.#typographyFrame = null;
    this.#stateMachine.clearInvalidation(InvalidationReason.TypographyRefreshForced);
    this.#stateMachine.clearInvalidation(InvalidationReason.DeferredTypographyCheck);
  }

  // HostContentSignal: childList and characterData mutations on the live DOM
  // are the only host content signals. Attributes and inline size already
  // have their own observers. This observer stays connected across layout
  // work on purpose: engine commits also produce records, and the drift
  // probe disproves those by identity instead of disconnecting and losing
  // host edits that land mid-flight.
  // Raw-dom records live on each paragraph's own enhance context (raw-dom
  // keys them by paragraph element), not on this root's context. This is the
  // pre-context enumeration order: rendered paragraphs come from the DOM
  // query (the original syncRawDom selector) and each record is read from
  // its paragraph's context — the state home that replaced the retired
  // __tqRawDomFragment DOM property.
  #renderedRawDomParagraphs(): Iterable<[Element, RawDomParagraphRecord]> {
    return renderedRawDomParagraphs(this.#root);
  }

  #observeContent() {
    if (!this.#contentInvalidation) {
      this.#contentInvalidation = createContentInvalidationSource(this.#root, {
        onRecords: (records) => this.#handleContentMutationRecords(records),
        belongsToRootScope,
        getRawDomParagraphs: () => this.#renderedRawDomParagraphs(),
      });
    }
    this.#contentInvalidation.start();
  }

  #stopContentObservation() {
    this.#contentInvalidation?.stop();
    this.#contentInvalidation = null;
    if (this.#contentProbeFrame) coordinationService().cancelFrame(this.#contentProbeFrame);
    this.#contentProbeFrame = null;
    this.#contentTainted.clear();
    this.#stateMachine.clearInvalidation(InvalidationReason.ContentDrift);
  }

  #handleContentMutationRecords(records: MutationRecord[]) {
    if (!this.#stateMachine.dispatched) return;
    const { taintedParagraphs, paragraphSignal, structureSignal } =
      classifyContentMutationRecords(records, {
        rawDomParagraphFor: (node) => this.#contentInvalidation?.paragraphFor(node) ?? null,
        belongsToRootScope,
        root: this.#root,
      });
    for (const paragraph of taintedParagraphs) this.#contentTainted.add(paragraph);
    if (!paragraphSignal && !structureSignal) return;
    this.#stateMachine.invalidate(InvalidationReason.ContentDrift);
    if (structureSignal && (!this.#stateMachine.workInFlight || this.#stateMachine.runtimeActive)) {
      // StructureChangesCommitDirectly: a childList record outside every
      // paragraph cannot be engine render output in the steady state, so no
      // probe is needed and waiting for one would only delay candidate
      // adoption. During initial enhancement the engine still installs its
      // own scaffolding at root level, so an in-flight signal there keeps
      // the probe path.
      this.#scheduleResponsiveGeometryCommit();
      return;
    }
    if (this.#stateMachine.workInFlight) {
      // MutationObserverDeliveryIsAsync: records land in a microtask after the
      // engine's synchronous commit batch, so a captured job may already be
      // rendering stale content. Probe drift read-only at the next frame; an
      // engine-owned batch is disproven there without cancelling anything.
      if (!this.#contentProbeFrame) {
        const operation = this.#stateMachine.transaction.layoutOperation;
        const contentProbeFrame: FrameTaskCallback = () => {
          this.#contentProbeFrame = null;
          if (!this.#root.isConnected || operation !== this.#stateMachine.transaction.layoutOperation) return;
          this.#probeContentDrift();
        };
        this.#contentProbeFrame = contentProbeFrame;
        coordinationService().requestFrame(contentProbeFrame);
      }
      return;
    }
    // EngineRecordsProvenIdleStayFree: a finished job's own records arrive in
    // this microtask. Scheduling a commit on them alone would fire the width
    // commit early and break the drag debounce, so prove host intent with the
    // read-only probe first. Only real drift, taint or dead tracking schedules
    // work; the probe clears the flag otherwise.
    this.#probeContentDrift();
  }

  #probeContentDrift() {
    // Mid-job takeovers publish fresh raw-DOM backup fragments; adopt them before
    // reading raw-DOM backup identity so a host edit made during enhancement is
    // already under observation when the probe runs.
    this.#contentInvalidation?.syncRawDom();
    const drift = probeRootContentDrift(this.#context, this.#rootState, this.#root);
    const drifted = (drift?.drifted || 0) + (drift?.dead || 0) + (drift?.unknown || 0) +
      (drift?.rawDom || 0);
    const tainted = this.#contentTainted.size;
    if (drifted === 0 && tainted === 0) {
      // Engine-owned output disproven; nothing host-authored is pending.
      this.#stateMachine.clearInvalidation(InvalidationReason.ContentDrift);
      return;
    }
    if (!this.#stateMachine.workInFlight) {
      this.#scheduleResponsiveGeometryCommit();
      return;
    }
    // MidFlightHostEditCancelsCapturedJob: only a captured job is bound to a
    // pre-edit snapshot. Uncaptured work lowers live content per slice and
    // the finish funnel picks the edit up.
    if (this.#stateMachine.work.usesCapturedMeasure) {
      this.#cancelCapturedLayoutForLatestGeometry();
    }
  }

  #dispatchContentReconcile(paragraphs: Element[]): boolean {
    if (!this.#stateMachine.runtimeActive) return false;
    this.#beginLayoutWork({ usesCapturedMeasure: true, captureSignatures: false });
    this.#stateMachine.dispatched = true;
    this.#stateMachine.completionGateOpen = true;
    this.#ensureLayoutWorker();
    const outcome = reconcileRoot(this.#context, this.#rootState, coordinationService().layoutJobPool, this.#root, paragraphs);
    if (outcome?.outcome !== "work") {
      // ReconcileIdleReleasesWorkSlot: the records were engine-owned output
      // or touched nothing tracked. Release the work slot without a ready
      // round-trip so the next signal starts clean.
      this.#finishLayoutWorkAndObserve();
      // ReconcileAbsorbsLiveGeometry: a reconcile renders at the live width,
      // and an idle verdict certifies the current DOM as settled output for
      // exactly this geometry. Earlier finishes that took responsive early
      // returns never stored a paragraph baseline, so the commit fall-through
      // would compare a stale signature and dispatch a phantom relayout.
      this.#lastParagraphMeasures = this.#paragraphMeasureSignature();
      this.#lastParagraphWidths = paragraphWidthSignature(this.#root);
      return false;
    }
    this.#syncLayoutWorker();
    return true;
  }

  #observeLayoutWorkInputs() {
    if (!this.#layoutWorkTypographyInvalidation) {
      this.#layoutWorkTypographyInvalidation = createLayoutWorkTypographyInvalidationSource(this.#root, {
        onMutation: (records) => {
          if (!this.#stateMachine.workInFlight || !this.#stateMachine.work.usesCapturedMeasure) return;
          // RendererOwnedProgressiveStyleMutation: paragraph takeover itself adds
          // the containing block and, for flex items, the captured inline size.
          // Those writes are output mechanics rather than a host typography
          // change; cancelling on them makes a valid mixed snapshot restart after
          // its first viewport-near paragraphs. Reverse only those exact deltas
          // against MutationRecord.oldValue, while any concurrent host style or
          // class change still reaches the full signature check below.
          let rendererOwnedOnly = true;
          for (let i = 0; i < records.length; i++) {
            const record = records[i];
            if (!rendererOwnedProgressiveStyleMutation(record, this.#root)) {
              rendererOwnedOnly = false;
              break;
            }
          }
          if (rendererOwnedOnly) {
            // ProgressiveOutputTypographyBaseline: rendered paragraphs intentionally
            // replace host line-height/font projection and install a containing
            // block. Advance the captured baseline after that verified renderer-only
            // mutation so a later viewport signal compares host changes against the
            // current mixed native/rendered state, not against the all-native DOM
            // from before the first commit. A batch containing any host mutation
            // still falls through to the invalidation check below.
            this.#stateMachine.work.typographySignature = typographySignature(this.#root);
            return;
          }
          if (typographySignature(this.#root) === this.#stateMachine.work.typographySignature) return;
          this.#cancelCapturedLayoutForTypographyChange();
        },
        onFontEvent: (event) => {
          if (
            this.#stateMachine.workInFlight && this.#stateMachine.work.usesCapturedMeasure &&
            fontLoadingAffectsTypography(event as FontLoadingEventLike, typographyElements(this.#root))
          ) this.#cancelCapturedLayoutForTypographyChange();
        },
      });
    }
    this.#layoutWorkTypographyInvalidation.start();
  }

  #stopLayoutWorkInputObservation() {
    this.#layoutWorkTypographyInvalidation?.stop();
  }

  #cancelCapturedLayoutForTypographyChange() {
    if (!this.#stateMachine.workInFlight || !this.#stateMachine.work.usesCapturedMeasure) return;
    this.#clearResponsiveRetarget();
    this.#stateMachine.abortLayoutWork();
    this.#advanceTypographyBaselineAfterCancellation();
    this.#stateMachine.invalidate(InvalidationReason.ResponsiveCommit);
    this.#stateMachine.invalidate(InvalidationReason.ResponsiveRelayout);
    // CommittedMeasureLedger: a cancelled captured job may have committed
    // part of its paragraphs; no single signature describes the mix, so the
    // forced follow-up must not be skippable against a stale ledger value.
    this.#lastCommittedParagraphMeasures = "";
    this.#stopLayoutWorkInputObservation();
    cancelRootLayoutWork(this.#root);
    this.#deactivateLayoutWorker();
    this.#ensureViewportResizeListener();
    this.#scheduleResponsiveGeometryCommit();
  }

  #cancelCapturedLayoutForLatestGeometry() {
    if (!this.#stateMachine.workInFlight || !this.#stateMachine.work.usesCapturedMeasure) return;
    this.#clearResponsiveRetarget();
    this.#stateMachine.abortLayoutWork();
    this.#stopLayoutWorkInputObservation();
    cancelRootLayoutWork(this.#root);
    this.#deactivateLayoutWorker();
    this.#advanceTypographyBaselineAfterCancellation();
    this.#stateMachine.invalidate(InvalidationReason.ResponsiveCommit);
    this.#stateMachine.invalidate(InvalidationReason.ResponsiveRelayout);
    this.#lastCommittedParagraphMeasures = "";
    this.#ensureViewportResizeListener();
    this.#scheduleResponsiveGeometryCommit();
  }

  // CancelledTypographyBaselineAdvance: cancelling a captured job keeps every
  // already committed paragraph in its rendered state, but no ready event will
  // refresh the baseline the way a finished job would. The typography baseline
  // would stay at the all-native pre-job signature while the live DOM mixes
  // rendered and native paragraphs, so the next style-driven check compares a
  // mixed-state signature against the native one, misreads renderer output as
  // a host typography change and tears the whole root down. Advance the
  // baseline to the current mixed state here; a later real host change still
  // differs from it.
  #advanceTypographyBaselineAfterCancellation() {
    this.#lastTypography = typographySignature(this.#root);
  }

  #restoreRuntimeSourceForRetarget() {
    // ResponsiveRetargetNativeRollback: cancellation runs before the next
    // paint. Restore every already committed paragraph in the same callback so
    // no frame can display geometry captured for the superseded measure. The
    // next responsive commit starts viewport-priority enhancement from this
    // responsive semantic backing.
    if (this.#stateMachine.runtimeActive) {
      destroyRuntimeRoot(this.#rootState, this.#context, this.#root);
      this.#stateMachine.runtimeActive = false;
    } else {
      cancelRootLayoutWork(this.#root);
    }
  }

  #scheduleTypographyCheck(force = false) {
    if (force) this.#stateMachine.invalidate(InvalidationReason.TypographyRefreshForced);
    if (this.#typographyFrame) return;
    const typographyFrame: FrameTaskCallback = () => {
      this.#typographyFrame = null;
      if (!this.#root.isConnected) return;
      // A loading font would immediately invalidate another measurement. Its
      // loadingdone event will schedule the authoritative check.
      if (this.#root.ownerDocument?.fonts?.status === "loading") {
        this.#stateMachine.invalidate(InvalidationReason.DeferredTypographyCheck);
        return;
      }
      this.#stateMachine.clearInvalidation(InvalidationReason.DeferredTypographyCheck);
      const signature = typographySignature(this.#root);
      const changed = signature !== this.#lastTypography;
      const shouldRefresh = changed || this.#stateMachine.isInvalidated(InvalidationReason.TypographyRefreshForced);
      this.#stateMachine.clearInvalidation(InvalidationReason.TypographyRefreshForced);
      if (!shouldRefresh) return;
      this.#lastTypography = signature;
      if (this.#stateMachine.snapshotAdopted || isLoadedSnapshotAdopted(this.#root)) {
        this.#invalidateSnapshotAndEnhance();
        return;
      }
      this.#refreshRuntimeFromSource();
    };
    this.#typographyFrame = typographyFrame;
    coordinationService().requestFrame(typographyFrame);
  }

  #observeIntersection() {
    if (this.#visibilityObservation || typeof IntersectionObserver === "undefined") return;
    this.#visibilityObservation = createRootVisibilityObservation(this.#root, {
      onRootEntry: (fact) => {
        const wasInViewport = this.#stateMachine.inViewport;
        this.#stateMachine.inViewport = fact.isIntersecting;
        coordinationService().update(this.#coordinationSession, {
          inViewport: this.#stateMachine.inViewport,
          intersectionRatio: fact.intersectionRatio,
          visibleArea: fact.visibleArea,
          inlineSize: fact.inlineSize,
          area: fact.area,
        });
        if (wasInViewport && !this.#stateMachine.inViewport) {
          // OffscreenWorkerDebounce: an off-screen root stops receiving
          // grants immediately; its pending layout work waits out the same
          // trailing window as off-screen frame tasks and replays once the
          // drag settles or the root returns. Already committed paragraphs
          // stay committed.
          coordinationService().refreshWorkerDeferred(this.#coordinationSession);
        }
        if (!wasInViewport && this.#stateMachine.inViewport) {
          coordinationService().clearWorkerDeferred(this.#coordinationSession);
          if (
            this.#stateMachine.isInvalidated(InvalidationReason.ResponsiveCommit) ||
            this.#stateMachine.isInvalidated(InvalidationReason.ResponsiveRelayout)
          ) {
            this.#scheduleResponsiveGeometryCommit();
          }
        }
      },
    });
    this.#visibilityObservation.start();
  }

  #stopIntersectionObservation() {
    this.#visibilityObservation?.stop();
    this.#visibilityObservation = null;
  }

  #paragraphMeasureSignature(): string {
    return paragraphMeasureSignature(this.#root, Boolean(this.#snapshotFontSession));
  }

  #paragraphMeasureSignatureFromObserved(): string {
    return paragraphMeasureSignatureFromObserved(
      this.#root,
      this.#gridMetricsState,
      this.#sizeObservation?.widths ?? null,
      Boolean(this.#snapshotFontSession),
      () => this.#paragraphMeasureSignature(),
    );
  }


  constructor(root: HTMLElement) {
    this.#root = root;
    this.#context = createEnhanceContext(root);
  }

  get root(): HTMLElement {
    return this.#root;
  }

  get isConnected(): boolean {
    return this.#root.isConnected;
  }

  get diagnostics(): ProseHostDiagnostics {
    return this.#lastDiagnostics;
  }

  // Host configuration changes: the web component shell reflects each of the
  // four observed attributes through this entry, and programmatic hosts call
  // it directly. Every value that differs from the session's applied ledger
  // is synced onto the root attributes and runs the same reaction as a
  // custom-element attribute change. The ledger diff (not the live attribute)
  // keeps the custom-element path faithful: the platform already applied the
  // attribute before the shell reports it.
  updateOptions(options: ProseHostOptions): void {
    const root = this.#root;
    const applied = this.#appliedOptions;
    const apply = (name: string, oldApplied: string | null, next: string | null): void => {
      if (root.getAttribute(name) !== next) {
        if (next == null) root.removeAttribute(name);
        else root.setAttribute(name, next);
      }
      this.#attributeChanged(name, oldApplied, next);
    };
    if (options.disabled !== undefined && options.disabled !== applied.disabled) {
      apply("disabled", applied.disabled ? "" : null, options.disabled ? "" : null);
    }
    if (options.emphasisDotGapEm !== undefined && options.emphasisDotGapEm !== applied.emphasisDotGapEm) {
      const next = options.emphasisDotGapEm == null ? null : String(options.emphasisDotGapEm);
      // AttributeAlreadyEquivalent: the custom-element path arrives after the
      // platform wrote the attribute, and the author string may carry a
      // non-canonical form ("0.50"). Compare parsed values so the reflection
      // never rewrites the author's attribute text, only absent or
      // numerically different ones.
      const currentParsed = Number.parseFloat(root.getAttribute("emphasis-dot-gap-em") ?? "");
      const attributeAlreadyEquivalent = options.emphasisDotGapEm == null
        ? !Number.isFinite(currentParsed)
        : currentParsed === options.emphasisDotGapEm;
      if (!attributeAlreadyEquivalent) {
        if (next == null) root.removeAttribute("emphasis-dot-gap-em");
        else root.setAttribute("emphasis-dot-gap-em", next);
      }
      this.#attributeChanged(
        "emphasis-dot-gap-em",
        applied.emphasisDotGapEm == null ? null : String(applied.emphasisDotGapEm),
        next,
      );
    }
    if (options.strongAsEmphasisMarks !== undefined && options.strongAsEmphasisMarks !== applied.strongAsEmphasisMarks) {
      apply(
        "strong-as-emphasis-marks",
        applied.strongAsEmphasisMarks ? "" : null,
        options.strongAsEmphasisMarks ? "" : null,
      );
    }
    if (options.snapshotRef !== undefined && options.snapshotRef !== applied.snapshotRef) {
      apply("snapshot-ref", applied.snapshotRef ?? null, options.snapshotRef ?? null);
    }
    this.#syncAppliedOptions();
  }

  // Host-driven relayout (a container size change or an external style
  // refresh): mark both responsive bits so the next commit treats the
  // geometry as unsettled, then schedule it through the coordinator frame.
  relayout(): void {
    if (!this.#root.isConnected || !this.#stateMachine.dispatched) return;
    this.#stateMachine.invalidate(InvalidationReason.ResponsiveCommit);
    this.#stateMachine.invalidate(InvalidationReason.ResponsiveRelayout);
    this.#scheduleResponsiveGeometryCommit();
  }

  // Manual orthogonal invalidation for programmatic hosts. Typography bits
  // route through the typography check; responsive bits route through the
  // geometry commit.
  invalidate(reason: number): void {
    this.#stateMachine.invalidate(reason);
    if (!this.#root.isConnected || !this.#stateMachine.dispatched) return;
    if (
      reason & (InvalidationReason.DeferredTypographyCheck | InvalidationReason.TypographyRefreshForced)
    ) {
      this.#scheduleTypographyCheck(true);
      return;
    }
    this.#scheduleResponsiveGeometryCommit();
  }

  #eventListeners: Map<ProseHostEvent, ProseHostEventCallback[]> = new Map();
  #lastDiagnostics: ProseHostDiagnostics = {};
  #appliedOptions: AppliedProseHostOptions = { disabled: false, emphasisDotGapEm: null, strongAsEmphasisMarks: false, snapshotRef: null };

  #syncAppliedOptions(): void {
    const root = this.#root;
    const gap = Number.parseFloat(root.getAttribute("emphasis-dot-gap-em") ?? "");
    this.#appliedOptions = {
      disabled: root.hasAttribute("disabled"),
      emphasisDotGapEm: Number.isFinite(gap) ? gap : null,
      strongAsEmphasisMarks: root.hasAttribute("strong-as-emphasis-marks"),
      snapshotRef: root.getAttribute("snapshot-ref"),
    };
  }

  on(event: ProseHostEvent, callback: ProseHostEventCallback): ProseHostEventUnsubscribe {
    let list = this.#eventListeners.get(event);
    if (!list) {
      list = [];
      this.#eventListeners.set(event, list);
    }
    const subscribers = list;
    subscribers.push(callback);
    return () => {
      const index = subscribers.indexOf(callback);
      if (index >= 0) subscribers.splice(index, 1);
    };
  }

  onReady(callback: ProseHostEventCallback): ProseHostEventUnsubscribe {
    return this.on("ready", callback);
  }

  onRelayoutReady(callback: ProseHostEventCallback): ProseHostEventUnsubscribe {
    return this.on("relayout-ready", callback);
  }

  #emitEvent(event: ProseHostEvent, diagnostics: ProseHostDiagnostics): void {
    this.#lastDiagnostics = diagnostics;
    const list = this.#eventListeners.get(event);
    if (!list) return;
    for (const callback of [...list]) callback(diagnostics);
  }
}

// Public construction entry: one session per root. Options supplied here run
// through updateOptions, so each reflected attribute that changes takes its
// standard reaction before the caller mounts.
function createProseHostSession(root: HTMLElement, options?: ProseHostOptions): ProseHostSession {
  const session = new ProseHostSession(root);
  if (options) session.updateOptions(options);
  return session;
}

export { ProseHostSession, createProseHostSession };
