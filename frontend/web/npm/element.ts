import { getContextForElement, createEnhanceContext } from "@tiqian/core/core/engine/context/enhance-context.js";
import type { RawDomParagraphRecord } from "@tiqian/core/core/engine/context/enhance-context.js";
import {
  copyInstaller,
  loadTiqianRuntime,
} from "@tiqian/core/core/engine/loaders/runtime-loader.js";
import {
  awaitInitialTypographyFonts,
  createInitialFontRetryController,
  ensurePreparedDomBridge,
  fontLoadingAffectsTypography,
  loadSnapshotFontFallback,
} from "@tiqian/core/core/engine/loaders/font-loader.js";
import { needsCjkDashShaping, prepareCjkDashShapingIfNeeded } from "@tiqian/core/core/engine/loaders/cjk-dash.js";
import { lineLengthGridMeasure } from "@tiqian/core/core/sampler/grid-metrics.js";
import {
  detachLoadedSnapshot,
  isLoadedSnapshotAdopted,
  loadedAdoptedSnapshotLiveIssue,
  loadedSnapshotMaximumMeasureMatches,
  restoreLoadedSnapshot,
  tryAdoptRequestedSnapshot,
} from "@tiqian/core/core/sampler/snapshot/loaded-snapshots.js";
import {
  createSnapshotFontSessionEntry,
  releaseSnapshotFontSession,
} from "@tiqian/core/core/engine/snapshot-font.js";
import { ensureTiqianStyles } from "@tiqian/core/core/engine/loaders/styles.js";
import { prefetchSnapshotTables } from "@tiqian/core/core/sampler/snapshot/snapshot-tables.js";
import {
  captureViewportAnchor,
  compensateViewportAnchor,
  releaseNativeScrollAnchoring,
} from "@tiqian/core/core/engine/coordination/viewport-anchor.js";
import { CoordinationService } from "@tiqian/core/core/engine/coordination/coordination-service.js";
import { globalServices } from "@tiqian/core/core/services/global-services.js";
import * as engineFace from "@tiqian/core/core/engine/face.js";
import {
  DEFAULT_PARAGRAPH_SELECTOR,
  fragmentedBorderBoxInlineSize,
  TYPOGRAPHY_PROPERTIES,
  typographySignature,
  elementTypographySignature,
  typographyElements,
  captureLayoutWorkViewportTypographyEntries,
  layoutWorkViewportTypographyChanged,
  paragraphWidthSignature,
  responsiveGeometrySignature,
  paragraphMeasureSignature,
  paragraphMeasureEntry,
} from "@tiqian/core/core/sampler/signatures.js";
import {
  createParagraphGridMetricsState,
  seedParagraphGridMetrics,
  paragraphMeasureSignatureFromObserved,
} from "@tiqian/core/core/sampler/grid-metrics.js";
import {
  classifyContentMutationRecords,
  createTypographyInvalidationSource,
  createLayoutWorkTypographyInvalidationSource,
  createViewportResizeInvalidationSource,
  createContentInvalidationSource,
  createRootSizeObservation,
  createRootVisibilityObservation,
} from "@tiqian/core/core/sampler/observers.js";
import type { TiqianEngineWorkersInstance } from "@tiqian/core/core/engine/engine-entry.js";
import type { BrowserFontSessionHandle } from "@tiqian/core/core/measurement/browser-fonts.js";
import type { SnapshotFontSessionEntry } from "@tiqian/core/core/engine/snapshot-font.js";
import type {
  FontLoadingEventLike,
  GetComputedStyleFn,
  InitialFontRetryController,
} from "@tiqian/core/core/engine/loaders/font-loader.js";
import type { SnapshotAdoptAnchors } from "@tiqian/core/core/sampler/snapshot/precomputed.js";
import type { TypographyViewportEntry } from "@tiqian/core/core/sampler/signatures.js";
import type {
  ContentInvalidationSource,
  RootSizeObservationSource,
  RootVisibilityObservationSource,
  TypographyInvalidationSource,
  ViewportResizeInvalidationSource,
} from "@tiqian/core/core/sampler/observers.js";
import type { TiqianWebOptions } from "./api.js";

const ELEMENT_NAME = "tiqian-prose";
const ROOT_SELECTOR = `${ELEMENT_NAME}, [data-tiqian-root]`;
const SKIPPED_ANCESTOR_SELECTOR =
  ".not-prose, pre, table, .katex, .katex-display, .expressive-code, .tq-paragraph, [data-tiqian-skip]";
const SNAPSHOT_RENDER_FONT_ATTRIBUTE = "data-tiqian-snapshot-render-font";
const SNAPSHOT_PREPARED_FALLBACK_ATTRIBUTE = "data-tiqian-snapshot-layout-fallback";
const RESPONSIVE_SNAPSHOT_GEOMETRY_MISSES = new Set([
  "SnapshotWidthMismatch",
  "SnapshotWidthChangedDuringValidation",
]);
type DomElementCtor = typeof HTMLElement;
const HTMLElementBase: DomElementCtor =
  typeof globalThis.HTMLElement === "function"
    ? globalThis.HTMLElement
    : class TiqianSsrElement {} as DomElementCtor;
copyInstaller().install(globalThis.document);
// Snapshot-table loads start at module evaluation, ahead of the first root
// hydrating (ADR 0052 `TableTransport`); the scan is document-guarded and a
// no-op in non-browser entry points.
prefetchSnapshotTables();

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

function nextFrame(): Promise<number> {
  return new Promise((resolve) => requestAnimationFrame(resolve));
}

function belongsToRootScope(element: Element, root: Element): boolean {
  return element.closest(ROOT_SELECTOR) === root;
}

function isPureBlockImageParagraph(element: Element): boolean {
  if (element.tagName !== "P" || (element.textContent ?? "").trim() !== "") return false;
  const children = Array.from(element.querySelectorAll(":scope > *"));
  if (children.length === 0) return false;
  const view = element.ownerDocument?.defaultView;
  const getStyle: GetComputedStyleFn = view?.getComputedStyle ?? globalThis.getComputedStyle;
  if (typeof getStyle !== "function") return false;
  return children.every((child) =>
    child.tagName === "IMG" && getStyle.call(view, child).display.trim().toLowerCase() === "block"
  );
}

function rendererOwnedProgressiveStyleMutation(record: MutationRecord, root: HTMLElement): boolean {
  if (record.attributeName !== "style") return false;
  const target = record.target;
  if (
    !(target instanceof HTMLElement) || !target.matches("p[data-tq-rendered=true], li[data-tq-rendered=true]") ||
    !belongsToRootScope(target, root)
  ) return false;

  const previous = document.createElement(target.tagName);
  if (record.oldValue != null) previous.setAttribute("style", record.oldValue);
  const projected = document.createElement(target.tagName);
  const current = target.getAttribute("style");
  if (current != null) projected.setAttribute("style", current);
  let rendererPropertyFound = false;
  if (
    projected.style.getPropertyValue("position") === "relative" &&
    projected.style.getPropertyPriority("position") === "important"
  ) {
    rendererPropertyFound = true;
    const value = previous.style.getPropertyValue("position");
    if (value) {
      projected.style.setProperty("position", value, previous.style.getPropertyPriority("position"));
    } else {
      projected.style.removeProperty("position");
    }
  }
  if (
    target.getAttribute("data-tq-host-inline-size") === "true" &&
    projected.style.getPropertyPriority("inline-size") === "important"
  ) {
    rendererPropertyFound = true;
    const value = previous.style.getPropertyValue("inline-size");
    if (value) {
      projected.style.setProperty(
        "inline-size",
        value,
        previous.style.getPropertyPriority("inline-size"),
      );
    } else {
      projected.style.removeProperty("inline-size");
    }
  }
  return rendererPropertyFound && projected.style.cssText === previous.style.cssText;
}

function isRuntimeCompletionCandidate(element: Element, root: Element): boolean {
  if (!belongsToRootScope(element, root)) return false;
  if (element.closest(SKIPPED_ANCESTOR_SELECTOR)) return false;
  // PureBlockImageParagraphExclusion must match the Kotlin runtime candidate
  // set so an image-only root does not load layout code merely to do no work.
  if (isPureBlockImageParagraph(element)) return false;
  if (
    element.tagName === "LI" &&
    element.querySelector(":scope > p, :scope > ul, :scope > ol, :scope > blockquote, :scope > pre, :scope > table")
  ) return false;
  return true;
}

function snapshotCompletionSelector(root: HTMLElement): string {
  const selector = ":is(p, li):not([data-tq-snapshot-key])";
  return Array.from(root.querySelectorAll(selector))
    .some((paragraph) => isRuntimeCompletionCandidate(paragraph, root))
    ? selector
    : "";
}

function coordinationService(): CoordinationService {
  return globalServices().coordination;
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

class TiqianProseElement extends HTMLElementBase {
  static observedAttributes: string[] = [
    "disabled",
    "emphasis-dot-gap-em",
    "strong-as-emphasis-marks",
    "snapshot-ref",
  ];

  #forceTypographyRefresh = false;
  #acceptLayoutCompletion = false;
  #boundResponsiveCommit: TiqianBoundResponsiveCommitFn = () => {
    if (this.isConnected) this.#commitResponsiveGeometryChange();
  };
  #connected = false;
  #rawDomReentry = false;
  #detachAttributeSnapshot: (string | null)[] | null = null;
  #layoutWorkIsRelayout = false;
  #lastCommittedParagraphMeasures = "";
  #contentInvalidation: ContentInvalidationSource | null = null;
  #contentProbeFrame = 0;
  #contentReconcileRequired = false;
  #contentTainted = new Set<Element>();
  #deferredTypographyCheck = false;
  #typographyInvalidation: TypographyInvalidationSource | null = null;
  #geometryRevision = 0;
  #context = createEnhanceContext(this);
  #hasDispatched = false;
  #inViewport = true;
  #initialFontRetry: InitialFontRetryController | null = null;
  #visibilityObservation: RootVisibilityObservationSource | null = null;
  #layoutWorkInFlight = false;
  #layoutWorkerAttached = false;
  #layoutWorkSignaturesCaptured = false;
  #layoutWorkGeometrySignature = "";
  #layoutWorkMaximumMeasure = false;
  #layoutWorkMeasureSignature = "";
  #layoutWorkTypographySignature = "";
  #layoutWorkViewportTypographyEntries: TypographyViewportEntry[] = [];
  #layoutWorkTypographyInvalidation: TypographyInvalidationSource | null = null;
  #layoutWorkUsesCapturedMeasure = false;
  #layoutOperation = 0;
  #layoutWorkRevision = 0;
  #enhanceRequest = 0;
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
  #resizeFrame = 0;
  #resizeObserverFrame = 0;
  #sizeObservation: RootSizeObservationSource | null = null;
  #gridMetricsState = createParagraphGridMetricsState();
  #pendingCommittedMeasures = "";
  #responsiveCommitRequired = false;
  #responsiveRetargetFrame = 0;
  #responsiveRelayoutRequired = false;
  #runtimeStateActive = false;
  #snapshotAdopted = false;
  #snapshotEnhancedCount = 0;
  #typographyFrame = 0;
  #viewportResizeInvalidation: ViewportResizeInvalidationSource | null = null;

  get disabled(): boolean {
    return this.hasAttribute("disabled");
  }

  set disabled(value: boolean) {
    this.toggleAttribute("disabled", Boolean(value));
  }

  get emphasisDotGapEm(): number | null {
    const value = Number.parseFloat(this.getAttribute("emphasis-dot-gap-em") as string);
    return Number.isFinite(value) ? value : null;
  }

  set emphasisDotGapEm(value: number | null) {
    if (value == null) {
      this.removeAttribute("emphasis-dot-gap-em");
    } else {
      this.setAttribute("emphasis-dot-gap-em", String(value));
    }
  }

  get strongAsEmphasisMarks(): boolean {
    return this.hasAttribute("strong-as-emphasis-marks");
  }

  set strongAsEmphasisMarks(value: boolean) {
    this.toggleAttribute("strong-as-emphasis-marks", Boolean(value));
  }

  get snapshotRef(): string | null {
    return this.getAttribute("snapshot-ref");
  }

  set snapshotRef(value: string | null) {
    if (value == null) {
      this.removeAttribute("snapshot-ref");
    } else {
      this.setAttribute("snapshot-ref", String(value));
    }
  }

  connectedCallback() {
    coordinationService().register(this);
    this.#observeIntersection();
    if (this.#canAdoptRawDomMoveReconnection()) {
      this.#adoptRawDomMoveReconnection();
      return;
    }
    // ReconnectedSourceReclamation: detached roots keep their source backing in
    // weak runtime/snapshot state so navigation can discard them without
    // rebuilding an invisible old article. A real reconnection is the one case
    // that needs to pay the restoration cost before starting a new lifecycle.
    if (!this.#connected) {
      if (isLoadedSnapshotAdopted(this)) restoreLoadedSnapshot(this);
      if (this.#runtimeStateActive) engineFace.destroy(this);
      this.#runtimeStateActive = false;
    }
    this.#connected = true;
    this.#clearLifecycleDiagnostics();
    // ReversibleDisabledEnhancement: the Boolean attribute is the complete
    // opt-out contract. Keep semantic SSR children live and avoid stylesheet,
    // font, snapshot, runtime and observer work until the host removes it.
    if (this.disabled) return;
    this.#snapshotFontRejectedAttempt = "";
    const generation = this.#context.update();
    this.#clearInitialFontRetry();
    this.#acceptLayoutCompletion = false;
    this.#hasDispatched = false;
    this.#snapshotAdopted = isLoadedSnapshotAdopted(this);
    this.#snapshotEnhancedCount = 0;
    const loadStartedAt = Date.now();
    let initialReadyReported = false;
    // OptInStrongSnapshotExclusion: v1 snapshots contain only plain paragraphs,
    // so they cannot claim that a semantic <strong> was lowered to emphasis
    // marks. Keep the default bold path eligible for snapshots; an explicit
    // mapping request with actual <strong> content must enter the runtime.
    const strongEmphasisRuntimeRequired =
      this.strongAsEmphasisMarks && this.querySelector("strong") !== null;
    // SnapshotFirstInputBeforeRuntimeCompile: even a mixed root can prove and
    // display its keyed snapshot without Kotlin. Under Edge JITless, eagerly
    // importing the full runtime for one unkeyed paragraph delays the first
    // wheel event before adoption has even started. Load it only after a
    // successful snapshot reports that completion is still required.
    const runtimePromise = this.hasAttribute("snapshot-ref") &&
        !strongEmphasisRuntimeRequired
      ? null
      : loadTiqianRuntime();
    runtimePromise?.catch(() => {});
    this.#removeReadyListener();
    this.#stopTypographyObservation();
    this.#readyListener = (event) => {
      if (
        generation !== this.#context.generation || !this.#hasDispatched ||
        !this.#acceptLayoutCompletion
      ) return;
      const detail = (event as CustomEvent<TiqianReadyEventDetail>).detail ?? {};
      if (this.#snapshotAdopted && this.#snapshotEnhancedCount > 0) {
        const snapshotCount = this.#snapshotEnhancedCount;
        const runtimeEnhancedCount = detail.snapshot
          ? 0
          : Number.isFinite(detail.runtimeEnhancedCount)
            ? detail.runtimeEnhancedCount
            : Number.isFinite(detail.snapshotCount)
              ? Math.max(0, (Number(detail.enhancedCount) || 0) - snapshotCount)
              : Math.max(0, Number(detail.enhancedCount) || 0);
        const enhancedCount = runtimeEnhancedCount + snapshotCount;
        this.dataset.tiqianSnapshotCount = String(this.#snapshotEnhancedCount);
        this.setAttribute("data-tiqian-enhanced-count", String(enhancedCount));
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
        if (Number.isFinite(durationMs)) this.dataset.tiqianRelayoutMs = durationMs.toFixed(1);
        if (Number.isFinite(maxSliceMs)) {
          this.dataset.tiqianRelayoutMaxSliceMs = maxSliceMs.toFixed(1);
        }
        // CommittedMeasureLedger: forced commits (viewport revalidation,
        // stale follow-ups) skip against what the last clean relayout
        // actually committed, never against dispatch-time bookkeeping. The
        // runtime reports content reconciles through this same event kind,
        // so only jobs this element dispatched as width relayouts may move
        // the ledger.
        if (this.#layoutWorkIsRelayout) {
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
        if (Number.isFinite(durationMs)) this.dataset.tiqianEnhanceMs = durationMs.toFixed(1);
        if (Number.isFinite(maxSliceMs)) this.dataset.tiqianMaxSliceMs = maxSliceMs.toFixed(1);
        if (!initialReadyReported) {
          initialReadyReported = true;
          this.dataset.tiqianLoadMs = (Date.now() - loadStartedAt).toFixed(1);
        }
      }
      // SnapshotPreparedDomFallbackSingleFlight: once browser replay proves that
      // the snapshot HarfBuzz result cannot be represented at this effective
      // measure, retain the readable browser-metric rendering without letting
      // font loading events start the same failed snapshot session indefinitely.
      // A route reconnect or a different line-length grid gets a fresh attempt.
      if (this.hasAttribute(SNAPSHOT_PREPARED_FALLBACK_ATTRIBUTE)) {
        this.#snapshotFontRejectedAttempt = this.#snapshotFontAttemptSignature();
        // ResponsiveSnapshotFontSessionReuse: the server replay tables and host
        // font proof are still valid; only this line measure failed DOM replay.
        // Retain the session so a later grid can revalidate without rebuilding
        // the replay corpus. Disconnect and snapshot adoption remain the owners
        // of final release.
        this.removeAttribute(SNAPSHOT_RENDER_FONT_ATTRIBUTE);
      }
      if (stale) this.#responsiveCommitRequired = true;
      if (stale) this.#responsiveRelayoutRequired = true;
      this.#finishLayoutWorkAndObserve();
    };
    this.addEventListener("tiqian:ready", this.#readyListener);
    this.addEventListener("tiqian:relayout-ready", this.#readyListener);
    this.#ensureViewportResizeListener();

    // DeferredEnhanceErrorContract: one failure handler serves the load chain
    // below and the frame task it queues. The coordinator's frame loop guards
    // its callbacks with a synchronous try/catch, which cannot observe an
    // async task's rejection, and the chain's own .catch resolved the moment
    // the task was queued — so without routing the task's rejection here, a
    // runtime import or enhance failure inside the frame task became an
    // unhandled rejection: no RuntimeLoadFailed marker, the ready listener
    // left attached, and consumers awaiting tiqian:ready hanging forever.
    const failInitialEnhance = (error: unknown) => {
      if (generation !== this.#context.generation) return;
      this.#acceptLayoutCompletion = false;
      this.#layoutWorkInFlight = false;
      this.#layoutWorkViewportTypographyEntries = [];
      this.#clearResponsiveRetarget();
      this.#releaseSnapshotFontSession();
      if (!isLoadedSnapshotAdopted(this)) this.removeAttribute(SNAPSHOT_RENDER_FONT_ATTRIBUTE);
      this.#removeReadyListener();
      this.dataset.tiqianCapabilityIssue = "RuntimeLoadFailed";
      console.warn("Tiqian Web runtime failed to load", error);
    };
    // HostCascadeReadyGate: connectedCallback may run before an app's
    // module-loaded styles have reached the cascade. Once Tiqian's own stylesheet
    // is ready, one frame lets the parser and host cascade settle; then load only
    // the faces used by the prose and wait one painted frame. Waiting for global
    // DOMContentLoaded or document.fonts.ready would stall prose on unrelated
    // scripts, icon fonts, code fonts, or widgets.
    ensureTiqianStyles(this)
      .then(nextFrame)
      .then(() => awaitInitialTypographyFonts(this, {
        generation,
        isCurrent: () => this.isConnected && generation === this.#context.generation,
        bypassesFontWait: () => this.hasAttribute("snapshot-ref") &&
          !strongEmphasisRuntimeRequired,
        typographyElements: () => this.#typographyElements(),
        deferUntilFontsSettle: (gateGeneration, completion) =>
          this.#deferInitialEnhancementUntilFontsSettle(gateGeneration, completion),
      }))
      .then((fontGateOpen) => fontGateOpen ? nextFrame().then(() => true) : false)
      .then(async (fontGateOpen) => {
        if (!fontGateOpen) return;
        if (!this.isConnected || generation !== this.#context.generation) return;
        const runInitialEnhance = async () => {
          if (!this.isConnected || generation !== this.#context.generation) return;
          const enhanceStartedAt = Date.now();
          const operation = this.#beginLayoutWork({ captureSignatures: false });
          let snapshot: TiqianSnapshotAdoptionOutcome = { adopted: false };
          try {
            if (!strongEmphasisRuntimeRequired) {
              snapshot = await tryAdoptRequestedSnapshot(
                this,
                () => this.isConnected && generation === this.#context.generation &&
                  operation === this.#layoutOperation,
                this.#snapshotAdoptionAnchors(),
              );
            }
          } catch (error) {
            this.dataset.tiqianSnapshotMiss = "SnapshotValidationFailed";
            console.warn("Tiqian Web maximum-measure snapshot validation failed", error);
          }
          // The adoption commits are over; hand the scroller back to the
          // browser's own anchoring until the next commit path holds it.
          releaseNativeScrollAnchoring(this);
          if (
            !this.isConnected || generation !== this.#context.generation ||
            operation !== this.#layoutOperation
          ) {
            if (snapshot.adopted) restoreLoadedSnapshot(this);
            return;
          }
          if (snapshot.adopted) {
            delete this.dataset.tiqianSnapshotMiss;
            this.#snapshotAdopted = true;
            this.#snapshotEnhancedCount = snapshot.count;
            // MixedSnapshotRuntimeCompletion: the snapshot owns only keyed
            // paragraphs. Runtime-only prose remains semantic source and is
            // enhanced through the same Kotlin pipeline without discarding valid
            // server geometry for its keyed siblings.
            const completionSelector = snapshotCompletionSelector(this);
            if (completionSelector) {
              await (runtimePromise ?? loadTiqianRuntime());
              if (!this.isConnected || generation !== this.#context.generation) {
                return;
              }
              this.#acceptValidatedSnapshotGeometry();
              await this.#dispatchProgressiveEnhance(generation, {
                paragraphSelector: completionSelector,
              });
              return;
            }
            if (!this.#runtimeStateActive) this.#releaseSnapshotFontSession();
            this.#hasDispatched = true;
            this.#acceptLayoutCompletion = true;
            this.#acceptValidatedSnapshotGeometry();
            this.dispatchEvent(new CustomEvent("tiqian:ready", {
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
          this.dataset.tiqianSnapshotMiss = snapshot.reason ?? "SnapshotNotAdopted";
          await (runtimePromise ?? loadTiqianRuntime());
          if (!this.isConnected || generation !== this.#context.generation) return;
          if (!(await this.#dispatchProgressiveEnhance(generation))) return;
        };
        coordinationService().requestFrame(() => {
          runInitialEnhance().catch(failInitialEnhance);
        }, this);
      })
      .catch(failInitialEnhance);
  }

  disconnectedCallback() {
    this.#connected = false;
    // RawDomMoveTeardownDeferral: React, Svelte and other reconcilers move a
    // node by removing and re-inserting it inside one synchronous commit.
    // Settling the disconnection synchronously destroys a rendered article
    // that never left the host raw-DOM backup, so the settle runs one microtask later.
    // A same-task reconnection then re-enters the live lifecycle through
    // RawDomMoveAdoption. A real navigation settles exactly as before, still
    // before the next frame. The remount variant of
    // resize-destroy-transient.test.mjs holds this contract.
    this.#rawDomReentry = true;
    this.#detachAttributeSnapshot = TiqianProseElement.observedAttributes.map(
      (name) => this.getAttribute(name),
    );
    queueMicrotask(() => {
      this.#rawDomReentry = false;
      this.#detachAttributeSnapshot = null;
      if (!this.isConnected) this.#settleDisconnection();
    });
  }

  #settleDisconnection() {
    coordinationService().unregister(this);
    coordinationService().cancelFrame(this.#boundResponsiveCommit);
    releaseNativeScrollAnchoring(this);
    this.#stopIntersectionObservation();
    this.#stopParagraphTierObservation();
    this.#context.destroy();
    this.#enhanceRequest += 1;
    this.#layoutOperation += 1;
    this.#acceptLayoutCompletion = false;
    this.#hasDispatched = false;
    this.#layoutWorkInFlight = false;
    this.#layoutWorkViewportTypographyEntries = [];
    this.#responsiveCommitRequired = false;
    this.#responsiveRelayoutRequired = false;
    this.#clearResponsiveRetarget();
    this.#clearInitialFontRetry();
    delete this.dataset.tiqianFontWait;
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
    if (this.#snapshotAdopted || isLoadedSnapshotAdopted(this)) {
      detachLoadedSnapshot(this);
    }
    if (this.#runtimeStateActive) engineFace.detach(this);
    if (this.#layoutWorkerAttached) {
      // tiqian:detach already cancelled the job, so workerDetach has no
      // in-flight work to finish on this disconnected root.
      engineFace.workerRuntime()?.workerDetach?.(this);
      this.#layoutWorkerAttached = false;
    }
    this.#releaseSnapshotFontSession();
    this.removeAttribute(SNAPSHOT_RENDER_FONT_ATTRIBUTE);
  }

  #canAdoptRawDomMoveReconnection() {
    if (this.#connected || !this.#rawDomReentry) return false;
    if (!this.#runtimeStateActive || this.disabled) return false;
    if (this.#snapshotAdopted || isLoadedSnapshotAdopted(this)) {
      // Snapshot-based raw-DOM backup keeps the restore and re-adopt path. Its backing is
      // cheap to rebuild and shares document-scoped styles with the runtime.
      return false;
    }
    const snapshot = this.#detachAttributeSnapshot;
    if (snapshot == null) return false;
    return TiqianProseElement.observedAttributes.every(
      (name, index) => this.getAttribute(name) === snapshot[index],
    );
  }

  // RawDomMoveAdoption: a reconnection inside the deferred settle window is
  // a host raw-DOM backup move. The committed LayoutResult, the snapshot font session
  // and any in-flight job stayed valid through the move, so only the
  // observers and the geometry baseline need re-entry. A width change from
  // the move routes through the responsive commit lane and relayouts in
  // place; a changed font context routes through the typography check and
  // refreshes from source. Observed attribute edits during the gap reject
  // adoption and take the full restart path instead.
  #adoptRawDomMoveReconnection() {
    this.#rawDomReentry = false;
    this.#detachAttributeSnapshot = null;
    this.#connected = true;
    this.#ensureViewportResizeListener();
    this.#observeWidth();
    this.#observeTypography();
    this.#observeContent();
    this.#lastObservedWidth = fragmentedBorderBoxInlineSize(this);
    this.#scheduleResponsiveGeometryCommit();
    this.#scheduleTypographyCheck();
  }

  attributeChangedCallback(name: string, oldValue: string | null, newValue: string | null) {
    if (oldValue === newValue) return;
    if (name === "disabled") {
      // DisabledAttributeOwnsTeardown: adding the attribute uses the same
      // source restoration and cancellation path as a connected lifecycle
      // restart; connectedCallback then stops before any new work. Removing it
      // re-enters the complete snapshot/runtime lifecycle from semantic source.
      if (this.#connected) this.#restartConnectedLifecycle();
      return;
    }
    if (name === "snapshot-ref") {
      // UpgradeAttributeReactionGuard: when an SSR element is defined after it
      // was parsed, the platform reports its existing observed attributes
      // before connectedCallback. `isConnected` is already true at that point,
      // but this is not a client navigation and must not discard the server's
      // snapshot-font marker.
      if (this.#connected) this.#restartConnectedLifecycle();
      return;
    }
    if (
      name !== "emphasis-dot-gap-em" &&
      name !== "strong-as-emphasis-marks"
    ) return;
    if (!this.isConnected) return;
    // LatestObservedAttributeGeneration: strong emphasis controls snapshot
    // eligibility, while all public options belong to the same connection
    // generation. An initial async gate must never commit captured old values.
    if (!this.#hasDispatched) {
      this.#restartConnectedLifecycle();
      return;
    }
    if (this.#snapshotAdopted || isLoadedSnapshotAdopted(this)) {
      this.#invalidateSnapshotAndEnhance();
      return;
    }
    this.#refreshRuntimeFromSource();
  }

  #baseEnhanceOptions(): TiqianWebOptions | undefined {
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
    this.#initialFontRetry ??= createInitialFontRetryController(this, {
      isGenerationCurrent: (candidate) => candidate === this.#context.generation,
      typographyElements: () => this.#typographyElements(),
      restartConnectedLifecycle: () => this.#restartConnectedLifecycle(),
    });
    this.#initialFontRetry.deferUntilFontsSettle(generation, completion);
  }

  #clearInitialFontRetry() {
    this.#initialFontRetry?.clear();
  }

  #clearLifecycleDiagnostics() {
    delete this.dataset.tiqianCapabilityIssue;
    delete this.dataset.tiqianEnhanceMs;
    delete this.dataset.tiqianLoadMs;
    delete this.dataset.tiqianMaxSliceMs;
    delete this.dataset.tiqianRelayoutMs;
    delete this.dataset.tiqianRelayoutMaxSliceMs;
    delete this.dataset.tiqianFontWait;
    delete this.dataset.tiqianSnapshotLiveIssue;
    delete this.dataset.tiqianSnapshotCount;
    delete this.dataset.tiqianSnapshotMiss;
  }

  #restartConnectedLifecycle() {
    // Reconnect starts a fresh context: disconnect destroyed the previous
    // one and dropped it from the registry, so the constructor re-registers.
    this.#context = createEnhanceContext(this);
    this.#enhanceRequest += 1;
    this.#hasDispatched = false;
    this.#acceptLayoutCompletion = false;
    this.#snapshotAdopted = false;
    this.#snapshotEnhancedCount = 0;
    this.#removeReadyListener();
    this.#clearInitialFontRetry();
    this.#stopTypographyObservation();
    this.#stopLayoutWorkInputObservation();
    this.#stopWidthObservation();
    this.#stopContentObservation();
    restoreLoadedSnapshot(this);
    if (this.#runtimeStateActive) engineFace.destroy(this);
    this.#runtimeStateActive = false;
    this.#releaseSnapshotFontSession();
    this.removeAttribute(SNAPSHOT_RENDER_FONT_ATTRIBUTE);
    releaseNativeScrollAnchoring(this);
    if (this.isConnected) this.connectedCallback();
  }

  // SnapshotAdoptionAnchorCompensation adapter: the adoption loop in
  // precomputed.js commits one paragraph per cooperative slice; this feeds
  // its per-commit bracket from this element's anchor policy.
  #snapshotAdoptionAnchors(): SnapshotAdoptAnchors {
    return {
      capture: () => captureViewportAnchor(this),
      compensate: (anchor) => compensateViewportAnchor(this, anchor),
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
    const request = ++this.#enhanceRequest;
    // PlainHostPreparedBridge: the runtime lowers every paragraph through
    // the prepared-DOM bridge (ADR 0053 B8.3c), so a host without a snapshot
    // font session needs that bridge installed before layout starts. The
    // snapshot-session path installs it through loadSnapshotFontFallback; this
    // await covers the remaining hosts and leaves an already-installed
    // renderer (a snapshot session or a test fixture) untouched.
    await ensurePreparedDomBridge();
    this.#beginLayoutWork();
    const baseOptions = {
      ...(this.#baseEnhanceOptions() ?? {}),
      ...(paragraphSelector ? { paragraphSelector } : {}),
    };
    const needsDash = needsCjkDashShaping(this);
    let snapshotFontSession: BrowserFontSessionHandle | null = null;
    const snapshotFontSessionAlreadyPrepared = !revalidateSnapshotFont &&
      this.#snapshotFontSession?.reference === this.getAttribute("snapshot-ref");
    try {
      snapshotFontSession = await this.#prepareSnapshotFontSession(
        generation,
        request,
        revalidateSnapshotFont,
      );
      delete this.dataset.tiqianSnapshotFontMiss;
    } catch (error) {
      if (
        this.isConnected && generation === this.#context.generation &&
        request === this.#enhanceRequest
      ) this.#releaseSnapshotFontSession();
      this.dataset.tiqianSnapshotFontMiss = snapshotFontMissDatasetValue(error as TiqianElementSnapshotFontMissCandidate);
      console.warn("Tiqian Web snapshot font session unavailable; using browser metrics", error);
    }
    if (!this.isConnected || generation !== this.#context.generation || request !== this.#enhanceRequest) {
      if (!this.isConnected || generation !== this.#context.generation) this.#releaseSnapshotFontSession();
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
    delete this.dataset.tiqianExactLayoutIssue;
    this.removeAttribute(SNAPSHOT_PREPARED_FALLBACK_ATTRIBUTE);
    if (snapshotFontSession) {
      try {
        this.#snapshotFontSession!.installRenderFont(
          this,
          snapshotFontSession.renderFontFamilies as string[],
        );
        this.setAttribute(SNAPSHOT_RENDER_FONT_ATTRIBUTE, "true");
        // HostRenderFontReadyBeforeCommit: server replay already owns the
        // layout metrics, but CSS must finish loading the proven host faces before the
        // first paragraph is committed. This avoids a second font-driven pass
        // and prevents progressive frames from painting a fallback face.
        // WidthOnlySnapshotFontSessionReuse: replay tables and loaded host faces do not change
        // when only the content-box measure changes. Typography/font observers
        // still take the validating path; a responsive retarget can start the
        // latest-width paragraph queue without repeating font probes first.
        if (!snapshotFontSessionAlreadyPrepared) {
          await this.#snapshotFontSession!.prepareRenderFont(this, snapshotFontSession);
        }
        if (
          !this.isConnected || generation !== this.#context.generation ||
          request !== this.#enhanceRequest
        ) {
          this.#releaseSnapshotFontSession();
          return false;
        }
      } catch (error) {
        if (
          !this.isConnected || generation !== this.#context.generation ||
          request !== this.#enhanceRequest
        ) {
          this.#releaseSnapshotFontSession();
          return false;
        }
        this.#releaseSnapshotFontSession();
        snapshotFontSession = null;
        this.dataset.tiqianSnapshotFontMiss = "SnapshotRenderFontStyleUnavailable";
        console.warn("Tiqian Web snapshot render font style unavailable; using browser metrics", error);
      }
    }
    if (!snapshotFontSession) {
      this.removeAttribute(SNAPSHOT_RENDER_FONT_ATTRIBUTE);
    }
    // BrowserDashCapabilityBeforeDispatch: the browser no longer starts an
    // asynchronous HarfBuzz probe. Resolve the immediate capability result
    // before the first layout so a dash paragraph is never laid out once as
    // pending and then redundantly retried. An exact server-replay session is
    // carried separately and remains the authoritative dash path.
    const cjkDashCapability = needsDash
      ? await prepareCjkDashShapingIfNeeded(this, {
          ...baseOptions,
          ...(snapshotFontSession ? { snapshotFontSession } : {}),
        })
      : { status: "not-needed" };
    if (!this.isConnected || generation !== this.#context.generation || request !== this.#enhanceRequest) {
      this.#releaseSnapshotFontSession();
      return false;
    }
    // Capture the input signature for cancellation. Kotlin reads the live width
    // again for each paragraph, while this coordinator cancels the remaining
    // job on the next frame if the effective line measure changes.
    const layoutOperation = this.#beginLayoutWork({ usesCapturedMeasure: true });
    this.#hasDispatched = true;
    this.#runtimeStateActive = true;
    this.#acceptLayoutCompletion = true;
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
        const { createPrepareJob } = await import("@tiqian/core/core/engine/web-worker/worker-channel.js");
        const prepareJob = await createPrepareJob(
          this,
          snapshotFontSession,
          preparedOptions,
          () => this.isConnected && generation === this.#context.generation &&
            request === this.#enhanceRequest && layoutOperation === this.#layoutOperation,
        );
        if (prepareJob) await coordinationService().runPrepare(this, prepareJob);
      } catch (error) {
        // SnapshotWorkerFailureMustStayNative: synchronous Kotlin/JS fallback can
        // block scroll under JIT restrictions. Progressive enhancement will
        // retain source DOM for requests without a Worker plan.
        console.warn("Tiqian Web layout Worker unavailable; retaining native paragraphs", error);
      }
      if (
        !this.isConnected || generation !== this.#context.generation ||
        request !== this.#enhanceRequest || layoutOperation !== this.#layoutOperation
      ) {
        if (!this.isConnected || generation !== this.#context.generation) {
          this.#releaseSnapshotFontSession();
        }
        return false;
      }
    }
    this.#ensureLayoutWorker();
    // RunToCompletionAnchorBracket: without an attached coordinator the whole
    // progressive job runs synchronously inside this call, outside every
    // grant lane's capture/compensate bracket. Bracket it here with one
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
    this.dataset.tiqianEnhanceOptions = JSON.stringify(preparedOptions);
    const runAnchor = captureViewportAnchor(this);
    try {
      engineFace.enhanceProgressively(this, preparedOptions);
    } finally {
      compensateViewportAnchor(this, runAnchor);
      releaseNativeScrollAnchoring(this);
    }
    this.#syncLayoutWorker();
    return true;
  }

  #ensureLayoutWorker() {
    // WorkerPolledScheduling: attach before dispatch so the job is built
    // coordinated from the start and every slice comes from a grant. The
    // dispatch task runs inside the coordinator frame, so the first polled
    // grant lands in the same frame under the shared budget.
    const runtime = engineFace.workerRuntime();
    if (typeof runtime?.workerAttach !== "function") return;
    runtime.workerAttach(this);
    this.#layoutWorkerAttached = true;
    coordinationService().registerWorker(this, runtime);
  }

  #syncLayoutWorker() {
    const runtime = engineFace.workerRuntime();
    if (!this.#layoutWorkerAttached || typeof runtime?.workerHasJob !== "function") return;
    coordinationService().setWorkerActive(this, runtime.workerHasJob(this));
    this.#observeParagraphTiers(runtime);
    coordinationService().requestWorkerFrame(this);
  }

  #deactivateLayoutWorker() {
    if (!this.#layoutWorkerAttached) return;
    coordinationService().setWorkerActive(this, false);
  }

  #observeParagraphTiers(runtime: TiqianEngineWorkersInstance) {
    const count = runtime.workerParagraphCount(this);
    if (count === 0) {
      this.#stopParagraphTierObservation();
      return;
    }
    if (!this.#paragraphObserver && typeof IntersectionObserver === "undefined") return;
    this.#paragraphObserver ??= new IntersectionObserver((entries) => {
      const live = engineFace.workerRuntime();
      for (let i = 0; i < entries.length; i++) {
        const entry = entries[i];
        const info = this.#paragraphTierIndex.get(entry.target);
        if (!info) continue;
        const tier = this.#paragraphTierFromEntry(entry);
        if (tier === info.tier) continue;
        info.tier = tier;
        // Tier flips go straight to the running job's pending counters, so
        // the next polled frame reorders the queue without rescanning.
        if (typeof live?.workerSetParagraphTier === "function" && live.workerHasJob(this)) {
          live.workerSetParagraphTier(this, info.index, tier);
        }
      }
    }, { rootMargin: "100% 0px" });
    // Paragraph hosts survive relayout; atomic swaps replace only their
    // children. The diff converges: a stable article adds and drops nothing
    // and the observer set stops churning.
    const live = new Set<Element>();
    for (let index = 0; index < count; index++) {
      const paragraph = runtime.workerParagraphAt(this, index);
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
  ): Promise<BrowserFontSessionHandle | null> {
    const reference = this.getAttribute("snapshot-ref");
    if (!reference) {
      if (generation === this.#context.generation && request === this.#enhanceRequest) {
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
    this.setAttribute(SNAPSHOT_RENDER_FONT_ATTRIBUTE, "true");
    const loader = await loadSnapshotFontFallback();
    const existing = this.#snapshotFontSession;
    if (existing?.reference === reference) {
      // SnapshotFontSessionLiveRevalidation: reuse immutable server replay tables
      // only after the browser adapter revalidates every live snapshot input. A
      // caller that already proved this is a width-only retarget may reuse the
      // same live contract without repeating width-independent font probes.
      if (revalidateExisting) await existing.revalidate(this, existing.handle);
      if (
        !this.isConnected || generation !== this.#context.generation ||
        request !== this.#enhanceRequest || this.getAttribute("snapshot-ref") !== reference
      ) return null;
      return existing.handle;
    }
    const handle = await loader.prepareBrowserFontSession(this);
    if (
      !this.isConnected || generation !== this.#context.generation ||
      request !== this.#enhanceRequest || this.getAttribute("snapshot-ref") !== reference
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
    return releaseSnapshotFontSession(entry, this);
  }

  #snapshotFontAttemptSignature(reference: string | null = this.getAttribute("snapshot-ref")) {
    if (!reference) return "";
    const paragraph = this.querySelector("p[data-tq-snapshot-key], p, li");
    if (!paragraph) return `${reference}\u0000missing`;
    const style = getComputedStyle(paragraph);
    const fontSize = Number.parseFloat(style.fontSize);
    const width = fragmentedBorderBoxInlineSize(paragraph);
    const measure = lineLengthGridMeasure(width, fontSize);
    return `${reference}\u0000${Math.fround(fontSize)}\u0000${measure ?? `invalid:${width.toFixed(3)}`}`;
  }

  #beginLayoutWork({ usesCapturedMeasure = false, captureSignatures = usesCapturedMeasure }: TiqianLayoutWorkOptions = {}): number {
    this.#clearResponsiveRetarget();
    const operation = ++this.#layoutOperation;
    this.#layoutWorkInFlight = true;
    this.#layoutWorkIsRelayout = false;
    this.#pendingCommittedMeasures = "";
    this.#layoutWorkRevision = this.#geometryRevision;
    this.#layoutWorkSignaturesCaptured = captureSignatures;
    this.#layoutWorkGeometrySignature = captureSignatures
      ? this.#responsiveGeometrySignature()
      : "";
    this.#layoutWorkMeasureSignature = captureSignatures
      ? this.#paragraphMeasureSignature()
      : "";
    this.#layoutWorkViewportTypographyEntries = captureSignatures
      ? this.#captureLayoutWorkViewportTypographyEntries()
      : [];
    this.#layoutWorkTypographySignature = "";
    if (captureSignatures) {
      const entries = this.#layoutWorkViewportTypographyEntries;
      let signature = "";
      for (let i = 1; i < entries.length; i++) {
        if (i > 1) signature += "\u001e";
        signature += entries[i].signature;
      }
      this.#layoutWorkTypographySignature = signature;
    }
    this.#layoutWorkMaximumMeasure = captureSignatures && this.hasAttribute("snapshot-ref") &&
      loadedSnapshotMaximumMeasureMatches(this);
    this.#layoutWorkUsesCapturedMeasure = usesCapturedMeasure;
    this.#responsiveCommitRequired = false;
    this.#responsiveRelayoutRequired = false;
    this.#acceptLayoutCompletion = false;
    this.#stopTypographyObservation();
    this.#observeContent();
    if (usesCapturedMeasure) this.#observeLayoutWorkInputs();
    return operation;
  }

  #finishLayoutWorkAndObserve(expectedOperation: number | null = null): boolean {
    if (expectedOperation != null && expectedOperation !== this.#layoutOperation) return false;
    const signaturesCaptured = this.#layoutWorkSignaturesCaptured;
    const rawGeometryChangedDuringWork = this.#layoutWorkInFlight &&
      (this.#geometryRevision !== this.#layoutWorkRevision || this.#responsiveCommitRequired ||
        (signaturesCaptured &&
          this.#responsiveGeometrySignature() !== this.#layoutWorkGeometrySignature));
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
    const currentTypography = this.#typographySignature();
    // ResponsiveFinishSkipsDoomedSignatureReads: a finish that returns through
    // the responsive-commit branch stores no paragraph baseline. Width
    // movement puts every relayout finish onto that branch, and relayout
    // jobs capture no measure signature, so the live paragraph signatures
    // the finish read decided nothing and were discarded. Each read cost one
    // gBCR and one computed style per paragraph on DOM the job had just
    // dirtied. A finish reads the signatures only when it compares them
    // against a captured signature or stores them on the unchanged path.
    const signaturesConsumedByFinish = !rawGeometryChangedDuringWork ||
      (this.#layoutWorkUsesCapturedMeasure &&
        this.#layoutWorkMeasureSignature !== "");
    const currentParagraphWidths = signaturesConsumedByFinish &&
        !this.#layoutWorkUsesCapturedMeasure
      ? this.#paragraphWidthSignature()
      : this.#lastParagraphWidths;
    let currentMeasures: string;
    if (signaturesConsumedByFinish) {
      currentMeasures = this.#layoutWorkUsesCapturedMeasure && !rawGeometryChangedDuringWork
        ? this.#lastParagraphMeasures
        : this.#paragraphMeasureSignature();
    } else {
      currentMeasures = this.#lastParagraphMeasures;
    }
    const currentMaximumMeasure = this.hasAttribute("snapshot-ref") &&
      loadedSnapshotMaximumMeasureMatches(this);
    // CapturedMeasureFollowUpCoalescing: atomic relayout prepares every
    // paragraph from a width snapshot taken when the job starts. If resize
    // activity stays in the same N×fontSize measure and does not cross the
    // exact maximum-snapshot boundary, that result is already valid for the
    // final geometry and a second job would reproduce identical DOM.
    const effectiveLayoutChangedDuringWork = signaturesConsumedByFinish
      ? (currentMeasures !== this.#layoutWorkMeasureSignature ||
        currentMaximumMeasure !== this.#layoutWorkMaximumMeasure)
      : true;
    // RenderOutputTypographyIsNotAnInputChange: the renderer intentionally
    // changes paragraph line-height and positioning after it commits measured
    // line boxes. Comparing that output signature with the captured native
    // source signature schedules a redundant destroy-and-enhance pass. Real
    // font, style and viewport changes are observed while work is in flight and
    // cancel the captured job before ready; completion only needs to reconcile
    // geometry revisions that survived those observers.
    const layoutInputsChangedDuringWork = this.#responsiveCommitRequired || (
      rawGeometryChangedDuringWork &&
      (!this.#layoutWorkUsesCapturedMeasure || effectiveLayoutChangedDuringWork)
    );
    // FinishedTypographyBaselineRefresh also covers the changed-inputs branch:
    // a follow-up commit runs on the next frame and compares a live signature
    // against this baseline, so both branches must leave the baseline at the
    // finished DOM state. Skipping it on the changed branch leaves the
    // pre-job value (empty before the first completed job) and the follow-up
    // commit misreads renderer output as a host typography change.
    this.#lastTypography = currentTypography;
    this.#acceptLayoutCompletion = false;
    this.#layoutWorkInFlight = false;
    this.#layoutWorkSignaturesCaptured = false;
    this.#layoutWorkViewportTypographyEntries = [];
    this.#clearResponsiveRetarget();
    this.#stopLayoutWorkInputObservation();
    if (layoutInputsChangedDuringWork) {
      // A non-atomic progressive job may have observed intermediate widths, so
      // it must force one latest-width pass. Captured-measure relayout can let
      // the normal final measure comparison decide on the next frame.
      this.#responsiveCommitRequired = true;
      this.#responsiveRelayoutRequired = !this.#layoutWorkUsesCapturedMeasure;
      this.#ensureViewportResizeListener();
      this.#scheduleResponsiveGeometryCommit();
      return true;
    }
    if (this.#contentReconcileRequired && !this.#contentProbeFrame) {
      // ContentOnlyFinishCommit: an uncaptured job may have raced a host
      // edit. Resolve the flag with the read-only probe, never with the
      // commit lane: the records are usually this job's own output, and a
      // commit scheduled on them alone enters the offscreen deferred lane,
      // where it later fires a width commit inside the drag debounce window.
      // The probe clears an engine-owned flag without scheduling anything and
      // schedules the commit itself only on proven drift. The finish still
      // falls through to store its baselines, exactly like a finish without
      // the flag.
      this.#ensureViewportResizeListener();
      const operation = this.#layoutOperation;
      this.#contentProbeFrame = requestAnimationFrame(() => {
        this.#contentProbeFrame = 0;
        if (!this.isConnected || operation !== this.#layoutOperation) return;
        this.#probeContentDrift();
      });
    }
    this.#responsiveCommitRequired = false;
    this.#responsiveRelayoutRequired = false;
    this.#lastWidth = fragmentedBorderBoxInlineSize(this);
    this.#lastParagraphMeasures = currentMeasures;
    this.#lastParagraphWidths = currentParagraphWidths;
    this.#observeWidth();
    this.#observeTypography();
    this.#observeContent();
    return true;
  }

  #invalidateSnapshotAndEnhance({ restoreBeforeLoad = false }: TiqianSnapshotInvalidateOptions = {}) {
    if (!this.#snapshotAdopted && !isLoadedSnapshotAdopted(this)) return;
    const generation = this.#context.generation;
    this.#hasDispatched = false;
    let activeRequest = ++this.#enhanceRequest;
    this.#beginLayoutWork();
    const restoreImmediatelyBeforeDispatch = () => {
      if (!restoreLoadedSnapshot(this)) throw new Error("Adopted snapshot could not be restored");
      this.#snapshotAdopted = false;
      this.#snapshotEnhancedCount = 0;
      delete this.dataset.tiqianSnapshotCount;
      if (this.#runtimeStateActive) {
        engineFace.destroy(this);
        this.#runtimeStateActive = false;
      }
    };
    if (restoreBeforeLoad) restoreImmediatelyBeforeDispatch();
    loadTiqianRuntime()
      .then(() => {
        if (
          !this.isConnected || generation !== this.#context.generation ||
          activeRequest !== this.#enhanceRequest
        ) return false;
        const enhancement = this.#dispatchProgressiveEnhance(generation, restoreBeforeLoad
          ? undefined
          : { beforeDispatch: restoreImmediatelyBeforeDispatch });
        // Async functions run synchronously through their first await, so this
        // captures the request generation claimed by #dispatchProgressiveEnhance.
        activeRequest = this.#enhanceRequest;
        return enhancement;
      })
      .catch((error) => {
        this.#recoverSnapshotEnhanceFailure(generation, activeRequest, error);
      });
  }

  #recoverSnapshotEnhanceFailure(generation: number, request: number, error: unknown) {
    if (
      !this.isConnected || generation !== this.#context.generation ||
      request !== this.#enhanceRequest
    ) return;
    // Runtime/module failure must not strand the element in an unobserved
    // transition. Normally the adopted snapshot is still live because restore
    // is deferred until the successful dispatch task; retain it and resume the
    // responsive observers. If an exceptional synchronous restore already ran,
    // the readable runtime/SSR backing remains the fallback instead.
    const snapshotStillLive = isLoadedSnapshotAdopted(this);
    this.#snapshotAdopted = snapshotStillLive;
    this.#hasDispatched = snapshotStillLive || this.#runtimeStateActive;
    this.#acceptLayoutCompletion = false;
    this.#finishLayoutWorkAndObserve();
    this.dataset.tiqianCapabilityIssue = "RuntimeLoadFailed";
    console.warn("Tiqian Web runtime failed to load after snapshot invalidation", error);
  }

  #acceptValidatedSnapshotGeometry() {
    // SnapshotValidationConsumesObservedGeometry: adoption rechecks live width,
    // typography and rendered geometry immediately before its atomic commit.
    // Resize/observer notifications recorded while that validation was in
    // flight are therefore already represented by the adopted result. Reset
    // only the consumed bookkeeping here; a later browser event still arrives
    // after observation resumes and invalidates the snapshot normally.
    this.#layoutWorkRevision = this.#geometryRevision;
    this.#responsiveCommitRequired = false;
    this.#responsiveRelayoutRequired = false;
  }

  #tryReadoptSnapshotAtMaximumMeasure() {
    if (!this.hasAttribute("snapshot-ref")) return;
    const generation = this.#context.generation;
    const startedAt = Date.now();
    const operation = this.#beginLayoutWork();
    const runtimeSnapshotBackingRestored = this.#runtimeStateActive;
    if (runtimeSnapshotBackingRestored) {
      // RuntimeSnapshotBackingRestore: the first runtime enhancement retains
      // the exact server-rendered nodes as its teardown backing. Snapshot
      // validation must inspect that immutable SSR artifact, never the current
      // runtime rendering whose structure and digest are intentionally different.
      // DOM event dispatch is synchronous, so restoration and the validation
      // start stay in one task and cannot expose unvalidated SSR as a settled
      // state. A miss below immediately starts a fresh runtime enhancement.
      this.#hasDispatched = false;
      engineFace.destroy(this);
      this.#runtimeStateActive = false;
    }
    tryAdoptRequestedSnapshot(
      this,
      () => this.isConnected && generation === this.#context.generation &&
        operation === this.#layoutOperation,
      this.#snapshotAdoptionAnchors(),
    ).then(async (snapshot) => {
      // The adoption commits are over; hand the scroller back to the
      // browser's own anchoring until the next commit path holds it.
      releaseNativeScrollAnchoring(this);
      if (
        !this.isConnected || generation !== this.#context.generation ||
        operation !== this.#layoutOperation
      ) {
        if (snapshot.adopted) restoreLoadedSnapshot(this);
        return;
      }
      if (!snapshot.adopted) {
        this.dataset.tiqianSnapshotMiss = snapshot.reason ?? "SnapshotNotAdopted";
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
      delete this.dataset.tiqianSnapshotMiss;
      this.#snapshotAdopted = true;
      this.#snapshotEnhancedCount = snapshot.count;
      const completionSelector = snapshotCompletionSelector(this);
      if (completionSelector) {
        await loadTiqianRuntime();
        if (
          !this.isConnected || generation !== this.#context.generation ||
          operation !== this.#layoutOperation
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
      this.#hasDispatched = true;
      this.#acceptLayoutCompletion = true;
      this.#acceptValidatedSnapshotGeometry();
      this.dispatchEvent(new CustomEvent("tiqian:relayout-ready", {
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
    }).catch((error) => {
      if (
        !this.isConnected || generation !== this.#context.generation ||
        operation !== this.#layoutOperation
      ) return;
      this.dataset.tiqianSnapshotMiss = "SnapshotValidationFailed";
      console.warn("Tiqian Web responsive snapshot validation failed", error);
      this.#recoverRuntimeAfterSnapshotMiss(
        operation,
        "SnapshotValidationFailed",
        runtimeSnapshotBackingRestored,
      );
    });
  }

  #recoverRuntimeAfterSnapshotMiss(
    operation: number,
    reason: string,
    runtimeSnapshotBackingRestored = false,
  ) {
    if (operation !== this.#layoutOperation) return;
    if (runtimeSnapshotBackingRestored) {
      // Validation failed after the synchronous SSR backing restore. Rebuild
      // runtime state from that source for every miss category; a width-only
      // relayout cannot operate after the prior runtime instance was destroyed.
      const generation = this.#context.generation;
      this.#dispatchProgressiveEnhance(generation).catch((error) => {
        if (!this.isConnected || generation !== this.#context.generation) return;
        this.#finishLayoutWorkAndObserve();
        this.dataset.tiqianCapabilityIssue = "FontCapabilityPreparationFailed";
        console.warn("Tiqian Web snapshot miss recovery failed", error);
      });
      return;
    }
    if (RESPONSIVE_SNAPSHOT_GEOMETRY_MISSES.has(reason)) {
      this.#relayoutRuntimeAfterSnapshotMiss(operation);
      return;
    }
    if (!this.#runtimeStateActive) {
      // ReadoptionMissMustReclaimSource: a rapid resize can cancel the active
      // runtime job before a maximum-measure snapshot validation begins. If
      // that validation then misses, the DOM is readable native backing but no
      // owner remains to enhance it. Start a fresh latest-geometry job instead
      // of observing the permanently unclaimed source.
      const generation = this.#context.generation;
      this.#dispatchProgressiveEnhance(generation).catch((error) => {
        if (!this.isConnected || generation !== this.#context.generation) return;
        this.#finishLayoutWorkAndObserve();
        this.dataset.tiqianCapabilityIssue = "FontCapabilityPreparationFailed";
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
      if (!this.isConnected || generation !== this.#context.generation) return;
      this.#finishLayoutWorkAndObserve();
      this.dataset.tiqianCapabilityIssue = "FontCapabilityPreparationFailed";
      console.warn("Tiqian Web snapshot miss recovery failed", error);
    });
  }

  #dispatchRelayout(observedMeasures: string | null = null) {
    if (!this.#runtimeStateActive) {
      this.#finishLayoutWorkAndObserve();
      return;
    }
    this.#beginLayoutWork({ usesCapturedMeasure: true, captureSignatures: false });
    this.#layoutWorkIsRelayout = true;
    // Callers on the commit paths pass the signature they just computed;
    // recomputing here is reserved for dispatches that never went through a
    // commit pass (snapshot-miss recovery).
    this.#pendingCommittedMeasures = observedMeasures ?? this.#paragraphMeasureSignatureFromObserved();
    this.#hasDispatched = true;
    this.#acceptLayoutCompletion = true;
    this.#ensureLayoutWorker();
    // RunToCompletionAnchorBracket: relayout dispatches take the same bracket
    // as enhance dispatches; an uncoordinated relayout runs its whole job
    // synchronously inside this call.
    const relayoutAnchor = captureViewportAnchor(this);
    try {
      engineFace.relayout(this);
    } finally {
      compensateViewportAnchor(this, relayoutAnchor);
      releaseNativeScrollAnchoring(this);
    }
    this.#syncLayoutWorker();
  }

  #relayoutRuntimeAfterSnapshotMiss(operation: number) {
    if (operation !== this.#layoutOperation) return;
    this.#dispatchRelayout();
  }

  #refreshRuntimeFromSource({ revalidateSnapshotFont = true }: TiqianSourceRefreshOptions = {}) {
    // A source refresh replaces the rendered paragraphs, so the seeded grid
    // metrics are for nodes about to leave the tree; drop them and let the
    // observer re-seed the rebuilt paragraphs.
    this.#gridMetricsState.metrics = null;
    const generation = this.#context.generation;
    if (this.#runtimeStateActive) {
      // ResponsiveNativeBacking: pre-broken Tiqian lines cannot reflow while a
      // new width or typography is being prepared. Restore the complete
      // semantic source first so every remaining paragraph responds through the
      // host cascade while viewport-near paragraphs are enhanced atomically.
      engineFace.destroy(this);
      this.#runtimeStateActive = false;
    }
    this.#dispatchProgressiveEnhance(generation, { revalidateSnapshotFont }).catch((error) => {
      if (!this.isConnected || generation !== this.#context.generation) return;
      this.#finishLayoutWorkAndObserve();
      this.dataset.tiqianCapabilityIssue = "FontCapabilityPreparationFailed";
      console.warn("Tiqian Web source refresh failed", error);
    });
  }

  #removeReadyListener() {
    if (!this.#readyListener) return;
    this.removeEventListener("tiqian:ready", this.#readyListener);
    this.removeEventListener("tiqian:relayout-ready", this.#readyListener);
    this.#readyListener = null;
  }

  #observeWidth() {
    if (this.#sizeObservation) {
      // AdoptedWidthObservation: content reconcile adopts paragraphs after
      // the observer already exists. Seed and observe targets it has not
      // seen, so an adopted paragraph responds to later width changes.
      const paragraphs = this.querySelectorAll(DEFAULT_PARAGRAPH_SELECTOR);
      for (let i = 0; i < paragraphs.length; i++) {
        const paragraph = paragraphs[i];
        if (!belongsToRootScope(paragraph, this)) continue;
        // Metrics seeding is decoupled from the width map: a source refresh
        // drops the seeds while surviving paragraph nodes stay in the width
        // map, and the width gate alone would then strand them on the
        // read-based fallback for every commit.
        if (!this.#gridMetricsState.metrics?.has(paragraph)) this.#seedParagraphGridMetrics(paragraph);
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
      this,
      ...Array.from(this.querySelectorAll(DEFAULT_PARAGRAPH_SELECTOR))
        .filter((paragraph) => belongsToRootScope(paragraph, this)),
    ];
    for (let i = 0; i < targets.length; i++) {
      const target = targets[i];
      widths.set(target, fragmentedBorderBoxInlineSize(target));
      if (target !== this) this.#seedParagraphGridMetrics(target);
    }
    this.#sizeObservation = createRootSizeObservation({
      root: this,
      widths,
      onRootEntry: ({ width, height }) => {
        this.#lastObservedWidth = width;
        coordinationService().update(this, { inlineSize: width, area: width * (height || width * 0.6) });
        if (!this.#inViewport && this.#layoutWorkInFlight) {
          // A width change while the root stays off-screen keeps pushing the
          // worker's deferred wake-up, so only the final width is laid out.
          coordinationService().refreshWorkerDeferred(this);
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
          if (this.#layoutWorkInFlight && this.#layoutWorkUsesCapturedMeasure) {
            this.#geometryRevision += 1;
            this.#responsiveCommitRequired = true;
            this.#scheduleResponsiveRetarget();
            return;
          }
          // Uncaptured snapshot/font preparation revalidates live geometry before
          // it commits or begins captured work. It is not bound to the pre-resize
          // measure, so a raw viewport signal alone must not invalidate it.
          if (this.#layoutWorkInFlight) {
            return;
          }
          this.#handleResponsiveGeometryChange();
        },
      });
    }
    this.#viewportResizeInvalidation.start();
  }

  #handleResponsiveGeometryChange() {
    this.#geometryRevision += 1;
    // ResponsiveNativeRetargetSingleFlight: once rendered/runtime work has
    // been rolled back to semantic source, further resize signals only move
    // the same next-frame target. Do not synchronously rescan the entire
    // article or start another snapshot-font preparation for every OS resize event.
    if (this.#responsiveRelayoutRequired && !this.#runtimeStateActive) {
      this.#responsiveCommitRequired = true;
      this.#scheduleResponsiveGeometryCommit();
      return;
    }
    const snapshotAdopted = this.#snapshotAdopted || isLoadedSnapshotAdopted(this);
    const committedMeasureChanged = this.#hasDispatched && (
      this.#paragraphMeasureSignature() !== this.#lastParagraphMeasures ||
      (snapshotAdopted && !loadedSnapshotMaximumMeasureMatches(this))
    );
    if (committedMeasureChanged) {
      if (this.#layoutWorkInFlight && this.#layoutWorkUsesCapturedMeasure) {
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
      if (this.#runtimeStateActive) {
        // ResponsiveRuntimeDirectInPlaceRelayout: when typography is stable,
        // width changes do not tear down the rendered DOM to native text.
        // Direct single-frame in-place relayout computes the new line breaks
        // using WidthIndependentAnnotationCache and swaps DOM atomically.
        this.#responsiveCommitRequired = true;
        this.#scheduleResponsiveGeometryCommit();
        return;
      }
    }
    if (this.#layoutWorkInFlight) {
      this.#responsiveCommitRequired = true;
      this.#scheduleResponsiveRetarget();
      return;
    }
    this.#scheduleResponsiveGeometryCommit();
  }

  #scheduleResponsiveGeometryCommit() {
    if (this.#layoutWorkInFlight) {
      this.#responsiveCommitRequired = true;
      return;
    }
    coordinationService().requestFrame(this.#boundResponsiveCommit, this);
  }

  // PrePaintResponsiveCommit: ResizeObserver delivers after layout and
  // before paint, so a width-only commit that completes synchronously here
  // paints with the new width in the same frame; the scheduled lane paints
  // one frame of old lines first. Only the steady width-only case
  // qualifies — every other case keeps the scheduled lane's ordering
  // guarantees. Verified by demo/web/tests/resize-prepaint-commit.test.mjs.
  #commitResponsiveGeometryPrePaint() {
    if (!this.isConnected || !this.#inViewport) return false;
    if (!this.#runtimeStateActive || !this.#hasDispatched) return false;
    if (this.#contentProbeFrame) return false;
    if (this.#snapshotAdopted || isLoadedSnapshotAdopted(this)) return false;
    if (document.fonts?.status === "loading") return false;
    if (this.#layoutWorkInFlight) {
      // PreemptiveCrossingRelayout: without preemption only a drag's first
      // crossing reaches the pre-paint lane; later ones wait out the
      // scheduled cadence behind the in-flight job. A width-only relayout
      // is safe to replace — the runtime cancels it and rebuilds at the
      // latest width (WidthSnapshotPerRelayoutJob). Preempt only on a real
      // cell crossing; enhance and reconcile jobs are never replaced here.
      if (!this.#layoutWorkIsRelayout) return false;
      // ContentBeforeGeometry still rules: a pending reconcile keeps the
      // scheduled lane, whose commit re-lowers drifted content before any
      // width pass; a geometry-only preempt would relay stale text for the
      // rest of the drag.
      if (this.#contentReconcileRequired) return false;
      const measures = this.#paragraphMeasureSignatureFromObserved();
      if (measures === this.#lastParagraphMeasures) return false;
      this.#lastWidth = this.#lastObservedWidth || fragmentedBorderBoxInlineSize(this);
      this.#lastParagraphMeasures = measures;
      return this.#withRootObservationPaused(() => this.#dispatchRelayout(measures));
    }
    return this.#withRootObservationPaused(() => this.#commitResponsiveGeometryChange());
  }

  // One pause/resume protocol for both pre-paint lanes: the root is
  // unobserved around the synchronous commit so its own height change
  // cannot queue a same-depth observation for the browser's ResizeObserver
  // loop guard to report, then re-observed with the original box option.
  #withRootObservationPaused(commit: TiqianRootPausedCommitFn): boolean {
    this.#sizeObservation?.unobserve(this);
    try {
      commit();
      coordinationService().grantImmediate(this);
    } finally {
      this.#sizeObservation?.observe(this);
    }
    return true;
  }

  #commitResponsiveGeometryChange() {
    if (!this.isConnected) return;
    if (this.#layoutWorkInFlight) {
      this.#responsiveCommitRequired = true;
      return;
    }
    if (!this.#inViewport && this.#lastObservedWidth != null) {
      // OffscreenTrailingWidthCheck: ResizeObserver delivers on animation
      // frames, so while the frame loop pauses mid-drag the observer goes
      // quiet and the off-screen debounce can expire although the width is
      // still moving. Read the live width before releasing the commit; a
      // moving width re-enters the trailing lane.
      const liveWidth = fragmentedBorderBoxInlineSize(this);
      if (Math.abs(liveWidth - this.#lastObservedWidth) >= 0.5) {
        this.#lastObservedWidth = liveWidth;
        this.#responsiveCommitRequired = true;
        this.#scheduleResponsiveGeometryCommit();
        return;
      }
    }
    // Before the first snapshot/runtime commit there is no layout to update.
    // The initial job will read the latest live width once its font gate opens.
    const forceLatestWidth = this.#responsiveRelayoutRequired || this.#responsiveCommitRequired;
    this.#responsiveCommitRequired = false;
    this.#responsiveRelayoutRequired = false;
    if (!this.#hasDispatched) return;
    if (this.#contentReconcileRequired && !this.#contentProbeFrame) {
      // ContentBeforeGeometry: one commit lane serves ResizeObserver and
      // MutationObserver alike. Content goes first because re-lowering reads
      // the live width, so a concurrent width change is absorbed by the same
      // job; the reverse order would relayout stale text first. An idle
      // reconcile falls through so a width-only change still commits.
      this.#contentReconcileRequired = false;
      const tainted = Array.from(this.#contentTainted);
      this.#contentTainted.clear();
      if (this.#snapshotAdopted || isLoadedSnapshotAdopted(this)) {
        this.#invalidateSnapshotAndEnhance({ restoreBeforeLoad: true });
        return;
      }
      if (this.#dispatchContentReconcile(tainted)) {
        // ReconcileCommitPreservesWidthIntent: a work verdict returns before
        // the width lane runs, and the reconcile job re-lowers only drifted,
        // tainted and stranded paragraphs. A width change already pending at
        // this commit would die with the flags beginLayoutWork cleared; the
        // finish would then store the live width against stale paragraphs and
        // the change would never re-enter layout. Re-arm the commit so the
        // finish schedules one latest-width pass.
        const pendingWidth = this.#lastObservedWidth || fragmentedBorderBoxInlineSize(this);
        if (forceLatestWidth || Math.abs(pendingWidth - this.#lastWidth) >= 0.5) {
          this.#responsiveCommitRequired = true;
        }
        return;
      }
    }
    const width = this.#lastObservedWidth || fragmentedBorderBoxInlineSize(this);
    this.#lastObservedWidth = width;
    const widthsChanged = Math.abs(width - this.#lastWidth) >= 0.5;
    const paragraphWidths = widthsChanged ? this.#lastParagraphWidths : this.#paragraphWidthSignature();
    // LineLengthGridResponsiveInvalidation: the quantized measure signature
    // is computed on every commit, width changes included, so the same-named
    // gate below can skip in-cell width motion instead of dispatching a job
    // that reproduces identical paragraph DOM. Layout is clean at commit
    // time (the width read above already forced it), so the per-paragraph
    // reads here do not thrash.
    const paragraphMeasures = this.#paragraphMeasureSignatureFromObserved();
    const hostInlineSizeRefresh = widthsChanged &&
      this.querySelector("[data-tq-host-inline-size]") !== null;
    const measuresChanged = paragraphMeasures !== this.#lastParagraphMeasures;
    const signature = (widthsChanged && !this.#forceTypographyRefresh)
      ? this.#lastTypography
      : this.#typographySignature();
    const typographyChanged = signature !== this.#lastTypography;
    if (!forceLatestWidth && !widthsChanged && !measuresChanged && !typographyChanged) {
      this.#observeWidth();
      return;
    }
    this.#lastWidth = width;
    this.#lastParagraphMeasures = paragraphMeasures;
    this.#lastParagraphWidths = paragraphWidths;

    const snapshotAdopted = this.#snapshotAdopted || isLoadedSnapshotAdopted(this);
    const atMaximumMeasure = this.hasAttribute("snapshot-ref") &&
      loadedSnapshotMaximumMeasureMatches(this);
    if (snapshotAdopted) {
      if (atMaximumMeasure && !typographyChanged) {
        // MixedSnapshotCompletionResume: cancelling a captured runtime-only
        // job restores just its unkeyed source; the keyed snapshot remains
        // valid. Restart that partial job instead of treating the still-valid
        // snapshot as proof that every paragraph is settled.
        const completionSelector = snapshotCompletionSelector(this);
        if (completionSelector && !this.#runtimeStateActive) {
          const generation = this.#context.generation;
          this.#dispatchProgressiveEnhance(generation, {
            paragraphSelector: completionSelector,
          }).catch((error) => {
            if (!this.isConnected || generation !== this.#context.generation) return;
            this.#finishLayoutWorkAndObserve();
            this.dataset.tiqianCapabilityIssue = "FontCapabilityPreparationFailed";
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
    if (!this.#runtimeStateActive && atMaximumMeasure && !typographyChanged) {
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
    if (document.fonts?.status === "loading") {
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
    if (this.#runtimeStateActive) {
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
    if (this.#resizeObserverFrame) cancelAnimationFrame(this.#resizeObserverFrame);
    this.#resizeObserverFrame = 0;
    if (this.#resizeFrame) cancelAnimationFrame(this.#resizeFrame);
    this.#resizeFrame = 0;
    this.#removeViewportResizeListener();
  }

  #scheduleResponsiveRetarget() {
    if (!this.#layoutWorkInFlight || !this.#layoutWorkUsesCapturedMeasure) return;
    this.#clearResponsiveRetarget();
    const operation = this.#layoutOperation;
    this.#responsiveRetargetFrame = requestAnimationFrame(() => {
      this.#responsiveRetargetFrame = 0;
      if (
        !this.isConnected || !this.#layoutWorkInFlight ||
        !this.#layoutWorkUsesCapturedMeasure || operation !== this.#layoutOperation
      ) return;
      if (this.#layoutWorkViewportTypographyChanged()) {
        this.#cancelCapturedLayoutForTypographyChange();
        return;
      }
      const maximumMeasure = this.hasAttribute("snapshot-ref") &&
        loadedSnapshotMaximumMeasureMatches(this);
      // SameGridRetargetWithoutRestart: a responsive relayout dispatch uses
      // captureSignatures:false and reads its measure live inside the layout
      // job, so #layoutWorkMeasureSignature is empty here. Comparing against
      // that empty signature cancelled the in-flight job on every width
      // event. This guard compares against the measure of the last completed
      // job instead. While the width stays inside the same N×fontSize grid
      // cell, the committed DOM is already correct and unchanged paragraphs
      // are skipped at zero cost, so the in-flight job keeps running. When
      // the width crosses into a new cell, or when no completed measure
      // exists yet, the guard cancels the job and restarts it at the latest
      // width.
      const measureBaseline = this.#layoutWorkMeasureSignature || this.#lastParagraphMeasures;
      if (
        this.#paragraphMeasureSignature() === measureBaseline &&
        maximumMeasure === this.#layoutWorkMaximumMeasure
      ) return;
      this.#cancelCapturedLayoutForLatestGeometry();
    });
  }

  #clearResponsiveRetarget() {
    if (!this.#responsiveRetargetFrame) return;
    cancelAnimationFrame(this.#responsiveRetargetFrame);
    this.#responsiveRetargetFrame = 0;
  }

  #observeTypography() {
    if (!this.#typographyInvalidation) {
      this.#typographyInvalidation = createTypographyInvalidationSource(this, {
        onMutation: () => this.#scheduleTypographyCheck(),
        // Declared registry changes carry no FontFaceSetEvent; force past the
        // typography signature (declared sheets never enter the CSSOM it
        // reads) so the revalidate cycle re-collects the merged candidates.
        onDeclaredFacesChanged: () => this.#scheduleTypographyCheck(true),
        onFontEvent: async (event) => {
          const generation = this.#context.generation;
          const snapshotAdopted = this.#snapshotAdopted || isLoadedSnapshotAdopted(this);
          let snapshotLiveIssue = null;
          if (snapshotAdopted) {
            try {
              snapshotLiveIssue = await loadedAdoptedSnapshotLiveIssue(
                this,
                () => this.isConnected && generation === this.#context.generation &&
                  (this.#snapshotAdopted || isLoadedSnapshotAdopted(this)),
              );
            } catch {
              snapshotLiveIssue = "SnapshotLiveValidationFailed";
            }
          }
          if (!this.isConnected || generation !== this.#context.generation ||
              snapshotLiveIssue === "superseded") return;
          if (snapshotAdopted && snapshotLiveIssue == null) {
            // SnapshotFontLoadCycleAlreadyValidated: snapshot adoption awaited
            // and probed every exact evidence face. The browser may dispatch the
            // corresponding loadingdone task only after observers resume; retain
            // the snapshot when its CSS face, typography and rendered geometry
            // contracts still hold instead of starting a redundant font cycle.
            delete this.dataset.tiqianSnapshotLiveIssue;
            return;
          }
          if (snapshotLiveIssue) this.dataset.tiqianSnapshotLiveIssue = snapshotLiveIssue;
          const relevantFaceLoaded = fontLoadingAffectsTypography(
            event as FontLoadingEventLike,
            this.#typographyElements(),
          );
          const force = this.#forceTypographyRefresh || relevantFaceLoaded;
          if (this.#deferredTypographyCheck || force) this.#scheduleTypographyCheck(force);
        },
      });
    }
    this.#typographyInvalidation.start();
  }

  #stopTypographyObservation() {
    this.#typographyInvalidation?.stop();
    if (this.#typographyFrame) cancelAnimationFrame(this.#typographyFrame);
    this.#typographyFrame = 0;
    this.#forceTypographyRefresh = false;
    this.#deferredTypographyCheck = false;
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
    const pairs: [Element, RawDomParagraphRecord][] = [];
    const paragraphs = this.querySelectorAll(
      `:is(${DEFAULT_PARAGRAPH_SELECTOR})[data-tq-rendered="true"]`,
    );
    for (const paragraph of paragraphs) {
      const record = getContextForElement(paragraph)?.rawDomParagraphs.get(paragraph);
      if (record) pairs.push([paragraph, record]);
    }
    return pairs;
  }

  #observeContent() {
    if (!this.#contentInvalidation) {
      this.#contentInvalidation = createContentInvalidationSource(this, {
        onRecords: (records) => this.#handleContentMutationRecords(records),
        belongsToRootScope,
        getRawDomParagraphs: () => this.#renderedRawDomParagraphs(),
      });
    }
    this.#contentInvalidation.start();
  }

  #syncRawDomObservation() {
    this.#contentInvalidation?.syncRawDom();
  }

  #rawDomParagraphFor(node: Node) {
    return this.#contentInvalidation?.paragraphFor(node) ?? null;
  }

  #stopContentObservation() {
    this.#contentInvalidation?.stop();
    this.#contentInvalidation = null;
    if (this.#contentProbeFrame) cancelAnimationFrame(this.#contentProbeFrame);
    this.#contentProbeFrame = 0;
    this.#contentTainted.clear();
    this.#contentReconcileRequired = false;
  }

  #handleContentMutationRecords(records: MutationRecord[]) {
    if (!this.#hasDispatched) return;
    const { taintedParagraphs, paragraphSignal, structureSignal } =
      classifyContentMutationRecords(records, {
        rawDomParagraphFor: (node) => this.#rawDomParagraphFor(node),
        belongsToRootScope,
        root: this,
      });
    for (const paragraph of taintedParagraphs) this.#contentTainted.add(paragraph);
    if (!paragraphSignal && !structureSignal) return;
    this.#contentReconcileRequired = true;
    if (structureSignal && (!this.#layoutWorkInFlight || this.#runtimeStateActive)) {
      // StructureChangesCommitDirectly: a childList record outside every
      // paragraph cannot be engine render output in the steady state, so no
      // probe is needed and waiting for one would only delay candidate
      // adoption. During initial enhancement the engine still installs its
      // own scaffolding at root level, so an in-flight signal there keeps
      // the probe path.
      this.#scheduleResponsiveGeometryCommit();
      return;
    }
    if (this.#layoutWorkInFlight) {
      // MutationObserverDeliveryIsAsync: records land in a microtask after the
      // engine's synchronous commit batch, so a captured job may already be
      // rendering stale content. Probe drift read-only at the next frame; an
      // engine-owned batch is disproven there without cancelling anything.
      if (!this.#contentProbeFrame) {
        const operation = this.#layoutOperation;
        this.#contentProbeFrame = requestAnimationFrame(() => {
          this.#contentProbeFrame = 0;
          if (!this.isConnected || operation !== this.#layoutOperation) return;
          this.#probeContentDrift();
        });
      }
      return;
    }
    // EngineRecordsProvenIdleStayFree: a finished job's own records arrive in
    // this microtask. Scheduling a commit on them alone would fire the width
    // lane early and break the drag debounce, so prove host intent with the
    // read-only probe first. Only real drift, taint or dead tracking schedules
    // work; the probe clears the flag otherwise.
    this.#probeContentDrift();
  }

  #probeContentDrift() {
    // Mid-job takeovers publish fresh raw-DOM backup fragments; adopt them before
    // reading raw-DOM backup identity so a host edit made during enhancement is
    // already under observation when the probe runs.
    this.#syncRawDomObservation();
    const drift = engineFace.probeContentDrift(this);
    const drifted = (drift?.drifted || 0) + (drift?.dead || 0) + (drift?.unknown || 0) +
      (drift?.rawDom || 0);
    const tainted = this.#contentTainted.size;
    if (drifted === 0 && tainted === 0) {
      // Engine-owned output disproven; nothing host-authored is pending.
      this.#contentReconcileRequired = false;
      return;
    }
    if (!this.#layoutWorkInFlight) {
      this.#scheduleResponsiveGeometryCommit();
      return;
    }
    // MidFlightHostEditCancelsCapturedJob: only a captured job is bound to a
    // pre-edit snapshot. Uncaptured work lowers live content per slice and
    // the finish funnel picks the edit up.
    if (this.#layoutWorkUsesCapturedMeasure) {
      this.#cancelCapturedLayoutForLatestGeometry();
    }
  }

  #dispatchContentReconcile(paragraphs: Element[]): boolean {
    if (!this.#runtimeStateActive) return false;
    this.#beginLayoutWork({ usesCapturedMeasure: true, captureSignatures: false });
    this.#hasDispatched = true;
    this.#acceptLayoutCompletion = true;
    this.#ensureLayoutWorker();
    const outcome = engineFace.reconcileContent(this, paragraphs);
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
      this.#lastParagraphWidths = this.#paragraphWidthSignature();
      return false;
    }
    this.#syncLayoutWorker();
    return true;
  }

  #observeLayoutWorkInputs() {
    if (!this.#layoutWorkTypographyInvalidation) {
      this.#layoutWorkTypographyInvalidation = createLayoutWorkTypographyInvalidationSource(this, {
        onMutation: (records) => {
          if (!this.#layoutWorkInFlight || !this.#layoutWorkUsesCapturedMeasure) return;
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
            if (!rendererOwnedProgressiveStyleMutation(record, this)) {
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
            this.#layoutWorkTypographySignature = this.#typographySignature();
            return;
          }
          if (this.#typographySignature() === this.#layoutWorkTypographySignature) return;
          this.#cancelCapturedLayoutForTypographyChange();
        },
        onFontEvent: (event) => {
          if (
            this.#layoutWorkInFlight && this.#layoutWorkUsesCapturedMeasure &&
            fontLoadingAffectsTypography(event as FontLoadingEventLike, this.#typographyElements())
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
    if (!this.#layoutWorkInFlight || !this.#layoutWorkUsesCapturedMeasure) return;
    this.#clearResponsiveRetarget();
    ++this.#layoutOperation;
    this.#acceptLayoutCompletion = false;
    this.#layoutWorkInFlight = false;
    this.#layoutWorkViewportTypographyEntries = [];
    this.#advanceTypographyBaselineAfterCancellation();
    this.#responsiveCommitRequired = true;
    this.#responsiveRelayoutRequired = true;
    // CommittedMeasureLedger: a cancelled captured job may have committed
    // part of its paragraphs; no single signature describes the mix, so the
    // forced follow-up must not be skippable against a stale ledger value.
    this.#lastCommittedParagraphMeasures = "";
    this.#stopLayoutWorkInputObservation();
    engineFace.cancelLayoutWork(this);
    this.#deactivateLayoutWorker();
    this.#ensureViewportResizeListener();
    this.#scheduleResponsiveGeometryCommit();
  }

  #cancelCapturedLayoutForLatestGeometry() {
    if (!this.#layoutWorkInFlight || !this.#layoutWorkUsesCapturedMeasure) return;
    this.#clearResponsiveRetarget();
    ++this.#layoutOperation;
    this.#acceptLayoutCompletion = false;
    this.#layoutWorkInFlight = false;
    this.#layoutWorkViewportTypographyEntries = [];
    this.#stopLayoutWorkInputObservation();
    engineFace.cancelLayoutWork(this);
    this.#deactivateLayoutWorker();
    this.#advanceTypographyBaselineAfterCancellation();
    this.#responsiveCommitRequired = true;
    this.#responsiveRelayoutRequired = true;
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
    this.#lastTypography = this.#typographySignature();
  }

  #restoreRuntimeSourceForRetarget() {
    // ResponsiveRetargetNativeRollback: cancellation runs before the next
    // paint. Restore every already committed paragraph in the same callback so
    // no frame can display geometry captured for the superseded measure. The
    // next responsive commit starts viewport-priority enhancement from this
    // responsive semantic backing.
    if (this.#runtimeStateActive) {
      engineFace.destroy(this);
      this.#runtimeStateActive = false;
    } else {
      engineFace.cancelLayoutWork(this);
    }
  }

  #scheduleTypographyCheck(force = false) {
    this.#forceTypographyRefresh ||= force;
    if (this.#typographyFrame) return;
    this.#typographyFrame = requestAnimationFrame(() => {
      this.#typographyFrame = 0;
      if (!this.isConnected) return;
      // A loading font would immediately invalidate another measurement. Its
      // loadingdone event will schedule the authoritative check.
      if (document.fonts?.status === "loading") {
        this.#deferredTypographyCheck = true;
        return;
      }
      this.#deferredTypographyCheck = false;
      const signature = this.#typographySignature();
      const changed = signature !== this.#lastTypography;
      const shouldRefresh = changed || this.#forceTypographyRefresh;
      this.#forceTypographyRefresh = false;
      if (!shouldRefresh) return;
      this.#lastTypography = signature;
      if (this.#snapshotAdopted || isLoadedSnapshotAdopted(this)) {
        this.#invalidateSnapshotAndEnhance();
        return;
      }
      this.#refreshRuntimeFromSource();
    });
  }

  #typographySignature(includeGenerated = true): string {
    return typographySignature(this, includeGenerated);
  }

  #elementTypographySignature(element: Element, includeGenerated = true, properties = TYPOGRAPHY_PROPERTIES): string {
    return elementTypographySignature(element, includeGenerated, properties);
  }

  #captureLayoutWorkViewportTypographyEntries(): TypographyViewportEntry[] {
    return captureLayoutWorkViewportTypographyEntries(this);
  }

  #layoutWorkViewportTypographyChanged(): boolean {
    return layoutWorkViewportTypographyChanged(this, this.#layoutWorkViewportTypographyEntries);
  }

  #typographyElements(): Element[] {
    return typographyElements(this);
  }

  #observeIntersection() {
    if (this.#visibilityObservation || typeof IntersectionObserver === "undefined") return;
    this.#visibilityObservation = createRootVisibilityObservation(this, {
      onRootEntry: (fact) => {
        const wasInViewport = this.#inViewport;
        this.#inViewport = fact.isIntersecting;
        coordinationService().update(this, {
          inViewport: this.#inViewport,
          intersectionRatio: fact.intersectionRatio,
          visibleArea: fact.visibleArea,
          inlineSize: fact.inlineSize,
          area: fact.area,
        });
        if (wasInViewport && !this.#inViewport) {
          // OffscreenWorkerDebounce: an off-screen root stops receiving
          // grants immediately; its pending layout work waits out the same
          // trailing window as off-screen frame tasks and replays once the
          // drag settles or the root returns. Already committed paragraphs
          // stay committed.
          coordinationService().refreshWorkerDeferred(this);
        }
        if (!wasInViewport && this.#inViewport) {
          coordinationService().clearWorkerDeferred(this);
          if (this.#responsiveCommitRequired || this.#responsiveRelayoutRequired) {
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

  #paragraphWidthSignature(): string {
    return paragraphWidthSignature(this);
  }

  #responsiveGeometrySignature(): string {
    return responsiveGeometrySignature(this);
  }

  #paragraphMeasureSignature(): string {
    return paragraphMeasureSignature(this, Boolean(this.#snapshotFontSession));
  }

  #paragraphMeasureEntry(paragraph: Element, snapshotFontLayout: boolean): string {
    return paragraphMeasureEntry(paragraph, snapshotFontLayout);
  }

  #paragraphMeasureSignatureFromObserved(): string {
    return paragraphMeasureSignatureFromObserved(
      this,
      this.#gridMetricsState,
      this.#sizeObservation?.widths ?? null,
      Boolean(this.#snapshotFontSession),
      () => this.#paragraphMeasureSignature(),
    );
  }

  #seedParagraphGridMetrics(paragraph: Element) {
    seedParagraphGridMetrics(this.#gridMetricsState, paragraph);
  }
}

declare global {
  interface HTMLElementTagNameMap {
    "tiqian-prose": TiqianProseElement;
  }
}

const registry = globalThis.customElements;
if (
  typeof globalThis.HTMLElement === "function" &&
  typeof registry?.get === "function" &&
  typeof registry?.define === "function" &&
  !registry.get(ELEMENT_NAME)
) {
  registry.define(ELEMENT_NAME, TiqianProseElement);
}

export { TiqianProseElement, CoordinationService };
