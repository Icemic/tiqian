// progressive-drivers (TsHost runtime port, Slice 5). Ports the
// enhanceProgressively / relayout progressive job drivers and the finishing
// reporting layer from WebEnhancer.kt and WebEnhancerProgressiveJob.kt into
// a pure TS module.
//
// Stateless module: enhanceProgressively(deps, ...), relayout(deps, ...),
// rejectMissingSharedRuntimeStyles(deps, ...) and startProgressiveJob(deps, ...)
// are named functions that receive the root-state, engine, copy-installer,
// progressive-job and collaborator deps as an explicit first parameter. The
// engine bootstrap wires the deps object once, then the engine instance is
// back-filled into its engine slot; the progressive-relayout-session and
// process-paragraph deps bundles ride inside. The stateless
// prepare-paragraph-layout, lifecycle and responsive-measure helpers are
// imported directly.
//
// Embedding constraint: the generator wraps this file in a Kotlin raw string,
// so the source must contain no dollar sign and no triple double-quote
// sequence. Use string concatenation, never template literals.

// Ambient global declarations pulled in via import type from owner modules.
import type {
  RootState,
  RootStateApi,
  PrepareArgument,
  TrackedParagraph,
  ProcessParagraphArgument,
} from "./root-state.js";
import type { EngineFfiFacade } from "./ffi-face.js";
import type {
  ProgressiveJobApi,
  ProgressiveJobSpec,
  ProgressiveJobFinishReport,
  ProgressiveJobFailureReport,
} from "./progressive-job.js";
import type {
  ProgressiveRelayoutSession,
  ProgressiveRelayoutSessionDeps,
} from "./progressive-relayout-session.js";
import { openProgressiveRelayoutSession } from "./progressive-relayout-session.js";
import type { PrepareLayoutResult } from "./prepare-paragraph-layout.js";
import type { CapabilityIssueRecord, EnhanceOptions } from "./lifecycle.js";
import { reportIssue, responsiveSourceMeasure } from "./lifecycle.js";
import type { TiqianEngineInstance } from "./engine-entry.js";
import type { ProcessParagraphDeps } from "./process-paragraph.js";
import { processParagraph } from "./process-paragraph.js";
import type { CopyInstaller } from "../utils/copy.js";
import { prepareParagraphLayout } from "./prepare-paragraph-layout.js";
import { sourceParagraphWidth } from "./responsive-measure.js";

export interface ProgressiveDriversDeps {
  rootState: RootStateApi;
  engine: TiqianEngineInstance | null;
  copyInstaller: CopyInstaller;
  progressiveJob: ProgressiveJobApi;
  progressiveRelayoutSession: ProgressiveRelayoutSessionDeps;
  processParagraph: ProcessParagraphDeps;
}

// One-argument view of RootStateApi.publishState: the two call sites that
// omit keepEmpty rely on the omitted flag defaulting to falsy, which the
// strict root-state signature does not admit.
type PublishRootState = (state: RootState, keepEmpty?: boolean) => void;

// Named shapes for the progressive job driver callbacks. Their home is this
// file; progressive-job.ts consumes the structurally identical inline slots
// of ProgressiveJobSpec.
type ProgressiveDriverProcessItem = (index: number) => void;
type ProgressiveDriverItemsFinished = () => void;
type ProgressiveDriverFailure = () => void;
type ProgressiveDriverStaleCheck = () => boolean;

// Capability gate issue built by rejectMissingSharedRuntimeStyles and pushed
// into state.issues before the lifecycle marker report.
interface CapabilityGateIssue {
  name: string;
  detail: string;
  element: Element;
  reportToConsole: boolean;
  markerCaptured: boolean;
}

// Summary event detail payloads dispatched on the root element.
interface RelayoutReadyDetail {
  enhancedCount: number;
  runtimeEnhancedCount: number;
  snapshotCount: number;
  issueCount: number;
  durationMs: number;
  maxSliceMs: number;
  relayout: true;
  failed: boolean;
  error: string | null;
  stale: boolean;
}

interface EnhanceReadyDetail {
  enhancedCount: number;
  runtimeEnhancedCount: number;
  snapshotCount: number;
  issueCount: number;
  durationMs: number;
  maxSliceMs: number;
  stale: boolean;
}

interface ProgressiveErrorDetail {
  kind: string;
  error: string;
  durationMs: number;
  maxSliceMs: number;
}

type ProgressiveDriverEventDetail =
  | RelayoutReadyDetail
  | EnhanceReadyDetail
  | ProgressiveErrorDetail;

const CAPABILITY_DETAIL_LIMIT: number = 512;
const WIDTH_DEPENDENT_CAPABILITY_ISSUES: string[] = ["InlineCloneDecorationBreakUnsupported"];

  // CssFragmentedBlockInlineMeasure: plain getBoundingClientRect().width -- for
  // a block fragmented by CSS columns this is the union of every fragment, not
  // a per-fragment measure. Every caller uses it only for coarse >=0.5px drift
  // detection, where the union error is dwarfed by the tolerance (see the ADR
  // 0039 fractional fragment-aware amendment). A caller that needs the widest
  // live fragment must use elementContentWidth from the responsive-measure.js
  // module instead.
  function elementFragmentBorderBoxInlineSize(element: Element | null): number {
    if (!element) return 0;
    return element.getBoundingClientRect ? element.getBoundingClientRect().width : 0;
  }

  // paragraphViewportDistance: returns 0 when the element is visible in the
  // viewport, or a positive pixel distance otherwise (negative of bottom for
  // above-viewport, top minus viewportHeight for below-viewport).
  function paragraphViewportDistance(element: Element | null): number {
    if (!element || !element.getBoundingClientRect) return 0;
    const rect = element.getBoundingClientRect();
    const viewportHeight: number = window.innerHeight || document.documentElement.clientHeight || 0;
    if (rect.bottom >= 0 && rect.top <= viewportHeight) return 0;
    return rect.bottom < 0 ? -rect.bottom : rect.top - viewportHeight;
  }

  // observableSnapshotCount: reads data-tiqian-snapshot-count attribute; safe
  // integer and > 0, else 0.
  function observableSnapshotCount(root: Element): number {
    const value: number = Number(root.getAttribute("data-tiqian-snapshot-count"));
    return Number.isSafeInteger(value) && value > 0 ? value : 0;
  }

  // computedStyle: reads a CSS property value from the element's computed
  // style.
  function computedStyle(element: Element, property: string): string {
    return window.getComputedStyle(element).getPropertyValue(property);
  }

  // SharedRuntimeStylesCapabilityGate: renderer-owned geometry depends on the
  // package stylesheet for its line strut, reset, and nowrap invariants. The
  // public ESM entry waits for that stylesheet; direct callers must do the
  // same instead of silently painting a second browser-owned layout.
  export function rejectMissingSharedRuntimeStyles(deps: ProgressiveDriversDeps, state: RootState, candidates: Element[]): boolean {
    const ready: string = computedStyle(state.root, "--tq-styles-ready").trim();
    if (ready === "1") return false;
    for (let i = 0; i < candidates.length; i += 1) {
      const issue: CapabilityGateIssue = {
        name: "MissingSharedRuntimeStyles",
        detail: "Load @tiqian/prose/styles.css before TiqianWeb.enhance",
        element: candidates[i],
        reportToConsole: true,
        markerCaptured: false,
      };
      state.issues.push(issue);
      reportIssue(issue as CapabilityIssueRecord);
    }
    (deps.rootState.publishState as PublishRootState)(state);
    return true;
  }

  // startProgressiveJob: mirrors WebEnhancerProgressiveJob.kt
  // startProgressiveJob. Builds the spec and hands it to the progressive job
  // module.
  export function startProgressiveJob(
    deps: ProgressiveDriversDeps,
    state: RootState,
    kind: string,
    itemCount: number,
    processItem: ProgressiveDriverProcessItem,
    onItemsFinished: ProgressiveDriverItemsFinished | null,
    onFailure: ProgressiveDriverFailure | null,
    stale: ProgressiveDriverStaleCheck | null,
    itemTierIndex: number[] | null,
    paragraphsByDoc: HTMLElement[] | null,
  ): void {
    state.root.removeAttribute("data-tiqian-relayout-error");
    const spec: ProgressiveJobSpec = {
      root: state.root,
      kind: kind,
      itemCount: itemCount,
      processItem: processItem,
      onItemsFinished: onItemsFinished || null,
      onFailure: onFailure || null,
      isStale: stale || null,
      onProgress: function () {
        deps.rootState.publishState(state, true);
      },
      onFinished: function (report) {
        finishProgressiveJob(deps, state, report);
      },
      onFailed: function (failure) {
        failProgressiveJob(deps, state, failure);
      },
      startedAt: Date.now(),
      itemTierIndex: itemTierIndex || null,
      paragraphsByDoc: paragraphsByDoc || null,
      coordinated: deps.progressiveJob.isAttached(state.root),
    };
    deps.progressiveJob.startJob(spec);
  }

  // finishProgressiveJob: mirrors WebEnhancerProgressiveJob.kt
  // finishProgressiveJob.
  function finishProgressiveJob(deps: ProgressiveDriversDeps, state: RootState, report: ProgressiveJobFinishReport): void {
    (deps.rootState.publishState as PublishRootState)(state);
    dispatchProgressiveSummary(
      state,
      report.kind,
      Date.now() - report.startedAt,
      report.maxSliceMs,
      false,
      null,
      report.stale
    );
  }

  // failProgressiveJob: mirrors WebEnhancerProgressiveJob.kt
  // failProgressiveJob. Truncates detail, sets error attribute, dispatches
  // error and summary events.
  function failProgressiveJob(deps: ProgressiveDriversDeps, state: RootState, failure: ProgressiveJobFailureReport): void {
    const detail: string = String(failure.detail).slice(0, CAPABILITY_DETAIL_LIMIT);
    state.root.setAttribute("data-tiqian-relayout-error", detail);
    deps.rootState.publishState(state, true);
    dispatchTiqianProgressiveError(
      state.root,
      failure.kind,
      detail,
      Date.now() - failure.startedAt,
      failure.maxSliceMs
    );
    dispatchProgressiveSummary(
      state,
      failure.kind,
      Date.now() - failure.startedAt,
      failure.maxSliceMs,
      true,
      detail,
      false
    );
  }

  // dispatchProgressiveSummary: mirrors WebEnhancerProgressiveJob.kt
  // dispatchProgressiveSummary. Emits tiqian:ready or tiqian:relayout-ready
  // with the full detail shape.
  function dispatchProgressiveSummary(
    state: RootState,
    kind: string,
    durationMs: number,
    maxSliceMs: number,
    failed: boolean,
    error: string | null,
    stale: boolean,
  ): void {
    const runtimeEnhancedCount: number = state.paragraphs.length;
    const snapshotCount: number = observableSnapshotCount(state.root);
    const enhancedCount: number = runtimeEnhancedCount + snapshotCount;
    const issueCount: number = state.issues.length;
    let detail: RelayoutReadyDetail | EnhanceReadyDetail;
    if (kind === "Relayout") {
      detail = {
        enhancedCount: enhancedCount,
        runtimeEnhancedCount: runtimeEnhancedCount,
        snapshotCount: snapshotCount,
        issueCount: issueCount,
        durationMs: durationMs,
        maxSliceMs: maxSliceMs,
        relayout: true,
        failed: failed,
        error: error,
        stale: stale,
      };
      dispatchCustomEvent(state.root, "tiqian:relayout-ready", detail);
    } else {
      detail = {
        enhancedCount: enhancedCount,
        runtimeEnhancedCount: runtimeEnhancedCount,
        snapshotCount: snapshotCount,
        issueCount: issueCount,
        durationMs: durationMs,
        maxSliceMs: maxSliceMs,
        stale: stale,
      };
      dispatchCustomEvent(state.root, "tiqian:ready", detail);
    }
  }

  // dispatchCustomEvent: defensive CustomEvent dispatch; skips if
  // root.dispatchEvent is missing.
  function dispatchCustomEvent(root: Element, kind: string, detail: ProgressiveDriverEventDetail): void {
    if (!root || typeof root.dispatchEvent !== "function") return;
    root.dispatchEvent(new CustomEvent(kind, { bubbles: true, composed: true, detail: detail }));
  }

  // dispatchTiqianProgressiveError: mirrors WebEnhancerSupport.kt
  // dispatchTiqianProgressiveError. Emits tiqian:relayout-error or
  // tiqian:error depending on kind.
  function dispatchTiqianProgressiveError(root: Element, kind: string, detail: string, durationMs: number, maxSliceMs: number): void {
    const eventName: string = kind === "Relayout" ? "tiqian:relayout-error" : "tiqian:error";
    const eventDetail: ProgressiveErrorDetail = {
      kind: kind,
      error: detail,
      durationMs: durationMs,
      maxSliceMs: maxSliceMs,
    };
    dispatchCustomEvent(root, eventName, eventDetail);
  }

  // ---------------------------------------------------------------------------
  // enhanceProgressively internal
  // ---------------------------------------------------------------------------

  // optionsFromJs consumes the public options bag, not the canonical options
  // this module stores in state.options. Relayout restarts arrive with the
  // canonical shape, so fromCanonical routes them through
  // createRootStateFromCanonical instead of re-resolving the bag.
  function enhanceProgressivelyCore(deps: ProgressiveDriversDeps, root: Element, optionsBag: Record<string, unknown> | null, kind: string, fromCanonical?: boolean): void {
    const RS = deps.rootState;

    // Kotlin's private enhanceProgressively installs the copy handler and
    // destroys the root before rebuilding state, and the relayout restarts
    // (branches 1 and 3) enter this function directly. Hosted worlds carry
    // __TiqianEngine, whose destroy cancels the job, restores every committed
    // paragraph, and clears the root attributes; the standalone unit-test
    // world drives this module without an engine entry and keeps the bare
    // job cancel.
    if (globalThis.document) deps.copyInstaller.install(globalThis.document);
    if (deps.engine) {
      deps.engine.destroy(root as HTMLElement);
    } else {
      deps.progressiveJob.cancelJob(root);
    }
    const state = fromCanonical
      ? RS.createRootStateFromCanonical(root, optionsBag as EnhanceOptions)
      : RS.createRootState(root, optionsBag as Record<string, unknown>);

    const sourceCandidates = RS.paragraphCandidates(root, state.options.paragraphSelector);

    // SharedRuntimeStylesCapabilityGate.
    if (rejectMissingSharedRuntimeStyles(deps, state, sourceCandidates)) return;

    // Work order sorts by viewport distance; itemTierIndex keeps the
    // document-order index of each work item, so a coordinator tier flip
    // arriving in document order gates its item in work order in O(1).
    const distances: number[] = new Array(sourceCandidates.length);
    for (let d = 0; d < sourceCandidates.length; d += 1) {
      distances[d] = paragraphViewportDistance(sourceCandidates[d]);
    }
    const itemTierIndex: number[] = new Array(sourceCandidates.length);
    for (let t = 0; t < sourceCandidates.length; t += 1) {
      itemTierIndex[t] = t;
    }
    // Explicit dual-key sort: (distance, index) ascending; does not rely on
    // Array.sort stability.
    itemTierIndex.sort(function (a, b) {
      if (distances[a] < distances[b]) return -1;
      if (distances[a] > distances[b]) return 1;
      return a < b ? -1 : a > b ? 1 : 0;
    });
    const candidates: Element[] = new Array(itemTierIndex.length);
    for (let c = 0; c < itemTierIndex.length; c += 1) {
      candidates[c] = sourceCandidates[itemTierIndex[c]];
    }

    // Capture responsive measures for staleness detection.
    const capturedMeasures: number[] = new Array(candidates.length);
    for (let m = 0; m < candidates.length; m += 1) {
      capturedMeasures[m] = responsiveSourceMeasure(
        candidates[m] as HTMLElement,
        state.options.fontSize
      );
    }
    let stale: boolean = false;

    function liveMeasure(index: number): number {
      return responsiveSourceMeasure(
        candidates[index] as HTMLElement,
        state.options.fontSize
      );
    }

    RS.setState(root, state);
    deps.rootState.publishState(state, true);

    startProgressiveJob(
      deps,
      state,
      kind,
      candidates.length,
      function (index) {
        // Per-item measure guard: refuse to commit a paragraph whose measure
        // drifted since capture.
        if (liveMeasure(index) !== capturedMeasures[index]) {
          stale = true;
        } else {
          processParagraph(
            deps.processParagraph,
            RS.processParagraphArgument(state, candidates[index])
          );
        }
      },
      function () {
        // StaleFinishKeepsCommittedParagraphs: the per-item guard already
        // refuses to commit a paragraph whose measure drifted, so the
        // committed ones were current when they landed. Rolling them back
        // here would tear the root to native source whenever a coordinated
        // job spans frames across a width change; the stale report
        // hands the follow-up to element.js, which dispatches one
        // latest-width relayout.
        for (let i = 0; i < candidates.length; i += 1) {
          if (liveMeasure(i) !== capturedMeasures[i]) {
            stale = true;
            break;
          }
        }
      },
      null,
      function () { return stale; },
      itemTierIndex,
      sourceCandidates as HTMLElement[]
    );
  }

  // ---------------------------------------------------------------------------
  // relayout
  // ---------------------------------------------------------------------------

  export function relayout(deps: ProgressiveDriversDeps, root: Element): void {
    const RS = deps.rootState;
    const PJ = deps.progressiveJob;

    // Branch 1: Enhance is running. Kotlin restarts the interrupted enhance
    // through the two-arg overload, so the kind stays Enhance and the finish
    // event stays tiqian:ready. Running.options is already canonical; route
    // it through the canonical state builder so the resolved options are
    // reused, not re-resolved.
    if (PJ.jobKind(root) === "Enhance") {
      const running = RS.getState(root);
      if (running != null) {
        enhanceProgressivelyCore(deps, root, running.options, "Enhance", true);
        return;
      }
    }

    // Branch 2: no state at all -- cold-start a Relayout with bag null.
    const state = RS.getState(root);
    if (state == null) {
      enhanceProgressivelyCore(deps, root, null, "Relayout");
      return;
    }

    // Branch 3: cancel current job; check for width-dependent capability
    // issues that require a full enhance restart.
    PJ.cancelJob(root);
    let hasWidthDependentIssue: boolean = false;
    for (let i = 0; i < state.issues.length; i += 1) {
      const issueName: string = ((state.issues[i] && state.issues[i].name) || "") as string;
      if (WIDTH_DEPENDENT_CAPABILITY_ISSUES.indexOf(issueName) !== -1) {
        hasWidthDependentIssue = true;
        break;
      }
    }
    if (hasWidthDependentIssue) {
      // WidthDependentCapabilityTransitionRetry: only named capabilities
      // whose eligibility depends on line count need to be lowered again at
      // the new width. Restore semantic source once, then let viewport-near
      // paragraphs take over atomically in bounded slices just like any other
      // source refresh. state.options is canonical.
      enhanceProgressivelyCore(deps, root, state.options, "Relayout", true);
      return;
    }

    // Main relayout path.
    const rendered = state.paragraphs;
    // StrandedEnhanceResume: a stale enhance finish leaves the paragraphs
    // it skipped in semantic source, and this follow-up relayout is the
    // only job that will reach them. Fold them into the work set at the
    // live width; the rendered ones keep the snapshot path below.
    const stranded = RS.strandedSourceParagraphs(root, state);
    const renderedCount: number = rendered.length;
    const count: number = renderedCount + stranded.length;

    // Work order: if root is in viewport process in document order; otherwise
    // sort by viewport distance.
    let workOrder: number[];
    if (paragraphViewportDistance(root) <= 0) {
      workOrder = new Array(count);
      for (let w = 0; w < count; w += 1) {
        workOrder[w] = w;
      }
    } else {
      const relayoutDistances: number[] = new Array(count);
      for (let r = 0; r < count; r += 1) {
        if (r < renderedCount) {
          relayoutDistances[r] = paragraphViewportDistance(rendered[r].source);
        } else {
          relayoutDistances[r] = paragraphViewportDistance(stranded[r - renderedCount]);
        }
      }
      workOrder = new Array(count);
      for (let wi = 0; wi < count; wi += 1) {
        workOrder[wi] = wi;
      }
      workOrder.sort(function (a, b) {
        if (relayoutDistances[a] < relayoutDistances[b]) return -1;
        if (relayoutDistances[a] > relayoutDistances[b]) return 1;
        return a < b ? -1 : a > b ? 1 : 0;
      });
    }

    // WidthSnapshotPerRelayoutJob: every paragraph is prepared against the
    // geometry seen when the job starts. If the host changes again while
    // slices are running, element.js schedules one latest-width follow-up
    // instead of allowing a queue of obsolete widths to replay.
    const widths: number[] = new Array(renderedCount);
    for (let p = 0; p < renderedCount; p += 1) {
      widths[p] = sourceParagraphWidth(rendered[p].source);
    }

    const commitSession = openProgressiveRelayoutSession(
      deps.progressiveRelayoutSession,
      RS.sessionArgument(state)
    );
    const rootWidth: number = elementFragmentBorderBoxInlineSize(root);

    // Build paragraphsByDoc: rendered sources in order, then stranded.
    const paragraphsByDoc: Element[] = new Array(count);
    for (let pb = 0; pb < renderedCount; pb += 1) {
      paragraphsByDoc[pb] = rendered[pb].source;
    }
    for (let ps = 0; ps < stranded.length; ps += 1) {
      paragraphsByDoc[renderedCount + ps] = stranded[ps];
    }

    startProgressiveJob(
      deps,
      state,
      "Relayout",
      count,
      function (index) {
        // Stale guard: once the session is stale, skip remaining items.
        if (commitSession.stale) return;
        const mixIndex = workOrder[index];
        if (mixIndex >= renderedCount) {
          // Stranded paragraph: process through the enhance path.
          processParagraph(
            deps.processParagraph,
            RS.processParagraphArgument(state as RootState, stranded[mixIndex - renderedCount])
          );
          return;
        }
        // Rendered paragraph: prepare and commit through the relayout session.
        const paragraph = rendered[mixIndex];
        const preparation = prepareParagraphLayout(
          deps.rootState.currentFfi() as EngineFfiFacade,
          RS.prepareArgument(
            state as RootState,
            paragraph,
            widths[mixIndex]
          )
        );
        commitSession.processItem(mixIndex, preparation);
      },
      function () {
        commitSession.finish();
      },
      function () {
        commitSession.rollback();
      },
      function () {
        // WidthSnapshotPerRelayoutJob: drift detection -- if root width has
        // changed since the snapshot, the session is stale.
        return commitSession.stale || Math.abs(elementFragmentBorderBoxInlineSize(root) - rootWidth) >= 0.5;
      },
      workOrder,
      paragraphsByDoc as HTMLElement[]
    );
  }

  // ---------------------------------------------------------------------------
  // public surface
  // ---------------------------------------------------------------------------

  // enhanceProgressively: raw host options bag (or null for a cold-start
  // relayout). The canonical entry enhanceProgressivelyFromCanonical accepts
  // already-resolved options and routes them through the canonical state
  // builder.
  export function enhanceProgressively(deps: ProgressiveDriversDeps, root: Element, optionsBag: Record<string, unknown> | null): void {
    enhanceProgressivelyCore(deps, root, optionsBag, "Enhance", false);
  }

  export function enhanceProgressivelyFromCanonical(deps: ProgressiveDriversDeps, root: Element, canonicalOptions: EnhanceOptions): void {
    enhanceProgressivelyCore(deps, root, canonicalOptions, "Enhance", true);
  }