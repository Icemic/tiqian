// progressive-drivers (TsHost runtime port, Slice 5). Ports the
// enhanceProgressively / relayout progressive job drivers and the finishing
// reporting layer from WebEnhancer.kt and WebEnhancerProgressiveJob.kt into
// a pure TS module.
//
// Stateless module: enhance, enhanceProgressively, relayout,
// rejectMissingSharedRuntimeStyles and startLayoutJob are named functions
// that receive the EnhancedElementContext as their first parameter; the
// layout job pool comes from globalServices().coordination, and the
// stateless prepare-paragraph-layout, lifecycle and responsive-measure
// helpers are imported directly. Root teardown inside the drivers runs
// through lifecycle's destroyRoot with the same context-first signature.
//
// Embedding constraint: the generator wraps this file in a Kotlin raw string,
// so the source must contain no dollar sign and no triple double-quote
// sequence. Use string concatenation, never template literals.

// Ambient global declarations pulled in via import type from owner modules.
import type {
  LayoutJobSpec,
  LayoutJobFinishReport,
  LayoutJobFailureReport,
} from "./layout-job-pool.js";
import type { RelayoutSession } from "./relayout-session.js";
import { openRelayoutSession } from "./relayout-session.js";
import type { PrepareLayoutResult } from "./prepare-paragraph-layout.js";
import type { CapabilityIssueRecord, EnhanceOptions, ResolvedEnhanceOptions } from "./lifecycle.js";
import { destroyRoot, reportIssue, responsiveSourceMeasure } from "./lifecycle.js";
import { processParagraph } from "./process-paragraph.js";
import { globalServices } from "../services/global-services.js";
import type { EnhancedElementContext } from "./context/enhance-context.js";
import { activeSnapshotSessionDescriptor } from "./enhance/snapshot-adoption.js";
import { prepareParagraphLayout } from "./prepare-paragraph-layout.js";
import { sourceParagraphWidth } from "./responsive-measure.js";

// Named shapes for the progressive job driver callbacks. Their home is this
// file; layout-job-pool.ts consumes the structurally identical inline slots
// of LayoutJobSpec.
type ProgressiveDriverProcessItem = (index: number) => void;
type ProgressiveDriverItemsFinished = () => void;
type ProgressiveDriverFailure = () => void;
type ProgressiveDriverStaleCheck = () => boolean;

// Capability gate issue built by rejectMissingSharedRuntimeStyles and pushed
// into the context's diagnosis issues before the lifecycle marker report.
interface CapabilityGateIssue {
  name: string;
  detail: string;
  element: Element;
  reportToConsole: boolean;
  markerCaptured: boolean;
}

// Summary event detail payloads dispatched on the root element. Type aliases
// on purpose: object literal types carry the implicit index signature that
// flows these details into the event channel's Record param.
type RelayoutReadyDetail = {
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

type EnhanceReadyDetail = {
  enhancedCount: number;
  runtimeEnhancedCount: number;
  snapshotCount: number;
  issueCount: number;
  durationMs: number;
  maxSliceMs: number;
  stale: boolean;
}

type ProgressiveErrorDetail = {
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

  // publishRootState: the DomWriteLayer projection of the former
  // root-state publishState; the paragraph and issue counts come from the
  // context's live arrays.
  function publishRootState(context: EnhancedElementContext, keepEmpty?: boolean): void {
    context.domWriteLayer.publishState(
      context.contextState.paragraphs.length,
      context.diagnosis.issues.length,
      keepEmpty
    );
  }

  // SharedRuntimeStylesCapabilityGate: renderer-owned geometry depends on the
  // package stylesheet for its line strut, reset, and nowrap invariants. The
  // public ESM entry waits for that stylesheet; direct callers must do the
  // same instead of silently painting a second browser-owned layout.
  export function rejectMissingSharedRuntimeStyles(context: EnhancedElementContext, candidates: Element[]): boolean {
    const ready: string = computedStyle(context.element, "--tq-styles-ready").trim();
    if (ready === "1") return false;
    for (let i = 0; i < candidates.length; i += 1) {
      const issue: CapabilityGateIssue = {
        name: "MissingSharedRuntimeStyles",
        detail: "Load @tiqian/core/styles.css before TiqianWeb.enhance",
        element: candidates[i],
        reportToConsole: true,
        markerCaptured: false,
      };
      context.diagnosis.issues.push(issue);
      reportIssue(issue as CapabilityIssueRecord);
    }
    publishRootState(context);
    return true;
  }

  // startLayoutJob: mirrors WebEnhancerProgressiveJob.kt. Builds the spec
  // and hands it to the layout job pool module.
  export function startLayoutJob(
    context: EnhancedElementContext,
    kind: string,
    itemCount: number,
    processItem: ProgressiveDriverProcessItem,
    onItemsFinished: ProgressiveDriverItemsFinished | null,
    onFailure: ProgressiveDriverFailure | null,
    stale: ProgressiveDriverStaleCheck | null,
    itemTierIndex: number[] | null,
    paragraphsByDoc: Element[] | null,
  ): void {
    const root = context.element;
    const layoutJobPool = globalServices().coordination.layoutJobPool;
    root.removeAttribute("data-tiqian-relayout-error");
    const spec: LayoutJobSpec = {
      root: root,
      kind: kind,
      itemCount: itemCount,
      processItem: processItem,
      onItemsFinished: onItemsFinished || null,
      onFailure: onFailure || null,
      isStale: stale || null,
      onProgress: function () {
        publishRootState(context, true);
      },
      onFinished: function (report) {
        finishLayoutJob(context, report);
      },
      onFailed: function (failure) {
        failLayoutJob(context, failure);
      },
      startedAt: Date.now(),
      itemTierIndex: itemTierIndex || null,
      paragraphsByDoc: paragraphsByDoc || null,
      coordinated: layoutJobPool.isAttached(root),
    };
    layoutJobPool.startJob(spec);
  }

  // finishLayoutJob: mirrors WebEnhancerProgressiveJob.kt.
  function finishLayoutJob(context: EnhancedElementContext, report: LayoutJobFinishReport): void {
    publishRootState(context);
    dispatchProgressiveSummary(
      context,
      report.kind,
      Date.now() - report.startedAt,
      report.maxSliceMs,
      false,
      null,
      report.stale
    );
  }

  // failLayoutJob: mirrors WebEnhancerProgressiveJob.kt. Truncates detail,
  // sets error attribute, dispatches error and summary events.
  function failLayoutJob(context: EnhancedElementContext, failure: LayoutJobFailureReport): void {
    const detail: string = String(failure.detail).slice(0, CAPABILITY_DETAIL_LIMIT);
    context.element.setAttribute("data-tiqian-relayout-error", detail);
    publishRootState(context, true);
    dispatchTiqianProgressiveError(
      context,
      failure.kind,
      detail,
      Date.now() - failure.startedAt,
      failure.maxSliceMs
    );
    dispatchProgressiveSummary(
      context,
      failure.kind,
      Date.now() - failure.startedAt,
      failure.maxSliceMs,
      true,
      detail,
      false
    );
  }

  // dispatchProgressiveSummary: mirrors WebEnhancerProgressiveJob.kt
  // dispatchProgressiveSummary. Reports through the context's event channel:
  // the funnel runs first, then the shell dispatcher (or the core synthesis
  // fallback) emits tiqian:ready / tiqian:relayout-ready with the full
  // detail shape.
  function dispatchProgressiveSummary(
    context: EnhancedElementContext,
    kind: string,
    durationMs: number,
    maxSliceMs: number,
    failed: boolean,
    error: string | null,
    stale: boolean,
  ): void {
    const root = context.element;
    const runtimeEnhancedCount: number = context.contextState.paragraphs.length;
    const snapshotCount: number = observableSnapshotCount(root);
    const enhancedCount: number = runtimeEnhancedCount + snapshotCount;
    const issueCount: number = context.diagnosis.issues.length;
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
      context.eventChannel.notify("tiqian:relayout-ready", detail);
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
      context.eventChannel.notify("tiqian:ready", detail);
    }
  }

  // dispatchTiqianProgressiveError: mirrors WebEnhancerSupport.kt
  // dispatchTiqianProgressiveError. Emits tiqian:relayout-error or
  // tiqian:error depending on kind through the event channel's plain
  // dispatch path (no completion funnel).
  function dispatchTiqianProgressiveError(context: EnhancedElementContext, kind: string, detail: string, durationMs: number, maxSliceMs: number): void {
    const eventName: string = kind === "Relayout" ? "tiqian:relayout-error" : "tiqian:error";
    const eventDetail: ProgressiveErrorDetail = {
      kind: kind,
      error: detail,
      durationMs: durationMs,
      maxSliceMs: maxSliceMs,
    };
    context.eventChannel.dispatch(eventName, eventDetail);
  }

  // ---------------------------------------------------------------------------
  // enhanceProgressively internal
  // ---------------------------------------------------------------------------

  // optionsFromJs consumes the public options bag, not the canonical options
  // this module stores as the runtime options. Relayout restarts arrive with
  // the canonical shape, so fromCanonical routes them through
  // resolveEngineOptionsFromCanonical instead of re-resolving the bag.
  function enhanceProgressivelyCore(
    context: EnhancedElementContext,
    root: Element,
    optionsBag: EnhanceOptions | Record<string, unknown> | null,
    kind: string,
    fromCanonical?: boolean,
  ): void {
    // Kotlin's private enhanceProgressively installs the copy handler and
    // destroys the root before rebuilding state, and the relayout restarts
    // (branches 1 and 3) enter this function directly. The teardown cancels
    // the job, restores every committed paragraph, and clears the root
    // attributes through lifecycle's destroyRoot.
    // TargetDocumentExplicit: install the copy listener on the document that
    // owns the enhanced root; the ambient fallback covers fake-DOM test
    // worlds whose roots carry no ownerDocument.
    const targetDocument = root.ownerDocument ?? globalThis.document;
    if (targetDocument) globalServices().clipboard.install(targetDocument);
    destroyRoot(context, root as HTMLElement);
    const resolved: ResolvedEnhanceOptions = fromCanonical
      ? context.optionsLedger.resolveEngineOptionsFromCanonical(root, optionsBag as EnhanceOptions)
      : context.optionsLedger.resolveEngineOptions(root, optionsBag as Record<string, unknown>);
    context.contextState.setRuntimeOptions(resolved);
    context.typography.establishRuntime(root, resolved);

    const sourceCandidates = context.contextState.paragraphCandidates(root, resolved.paragraphSelector);

    // SharedRuntimeStylesCapabilityGate.
    if (rejectMissingSharedRuntimeStyles(context, sourceCandidates)) return;

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
        resolved.fontSize
      );
    }
    let stale: boolean = false;

    function liveMeasure(index: number): number {
      return responsiveSourceMeasure(
        candidates[index] as HTMLElement,
        resolved.fontSize
      );
    }

    context.contextState.setRuntimeEstablished(true);
    publishRootState(context, true);

    startLayoutJob(
      context,
      kind,
      candidates.length,
      function (index) {
        // Per-item measure guard: refuse to commit a paragraph whose measure
        // drifted since capture.
        if (liveMeasure(index) !== capturedMeasures[index]) {
          stale = true;
        } else {
          processParagraph(context, candidates[index]);
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
      sourceCandidates
    );
  }

  // ---------------------------------------------------------------------------
  // relayout
  // ---------------------------------------------------------------------------

  export function relayout(
    context: EnhancedElementContext,
    root: Element,
  ): void {
    const PJ = globalServices().coordination.layoutJobPool;

    // Branch 1: Enhance is running. Kotlin restarts the interrupted enhance
    // through the two-arg overload, so the kind stays Enhance and the finish
    // event stays tiqian:ready. The runtime options are already canonical;
    // route them through the canonical resolver so the resolved options are
    // reused, not re-resolved.
    if (PJ.jobKind(root) === "Enhance") {
      const running = context.contextState.runtimeOptions;
      if (context.contextState.runtimeEstablished && running != null) {
        enhanceProgressivelyCore(context, root, running, "Enhance", true);
        return;
      }
    }

    // Branch 2: no established runtime at all -- cold-start a Relayout with
    // bag null.
    if (!context.contextState.runtimeEstablished) {
      enhanceProgressivelyCore(context, root, null, "Relayout");
      return;
    }

    // Branch 3: cancel current job; check for width-dependent capability
    // issues that require a full enhance restart.
    PJ.cancelJob(root);
    const issues = context.diagnosis.issues;
    let hasWidthDependentIssue: boolean = false;
    for (let i = 0; i < issues.length; i += 1) {
      const issueName: string = ((issues[i] && issues[i].name) || "") as string;
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
      // source refresh. The runtime options are canonical.
      enhanceProgressivelyCore(context, root, context.contextState.runtimeOptions, "Relayout", true);
      return;
    }

    // Main relayout path.
    const rendered = context.contextState.paragraphs;
    // StrandedEnhanceResume: a stale enhance finish leaves the paragraphs
    // it skipped in semantic source, and this follow-up relayout is the
    // only job that will reach them. Fold them into the work set at the
    // live width; the rendered ones keep the snapshot path below.
    const stranded = context.effectSync.strandedSourceParagraphs();
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

    const commitSession: RelayoutSession = openRelayoutSession(context);
    const rootWidth: number = elementFragmentBorderBoxInlineSize(root);

    // Build paragraphsByDoc: rendered sources in order, then stranded.
    const paragraphsByDoc: Element[] = new Array(count);
    for (let pb = 0; pb < renderedCount; pb += 1) {
      paragraphsByDoc[pb] = rendered[pb].source;
    }
    for (let ps = 0; ps < stranded.length; ps += 1) {
      paragraphsByDoc[renderedCount + ps] = stranded[ps];
    }

    const runtimeOptions = context.contextState.runtimeOptions as ResolvedEnhanceOptions;
    startLayoutJob(
      context,
      "Relayout",
      count,
      function (index) {
        // Stale guard: once the session is stale, skip remaining items.
        if (commitSession.stale) return;
        const mixIndex = workOrder[index];
        if (mixIndex >= renderedCount) {
          // Stranded paragraph: process through the enhance path.
          processParagraph(context, stranded[mixIndex - renderedCount]);
          return;
        }
        // Rendered paragraph: prepare and commit through the relayout session.
        const paragraph = rendered[mixIndex];
        const preparation: PrepareLayoutResult = prepareParagraphLayout(
          {
            paragraph: paragraph,
            options: runtimeOptions,
            snapshotSession: activeSnapshotSessionDescriptor(runtimeOptions),
            browserFallback: context.typography.browserFallback,
            widthOverride: widths[mixIndex] == null ? null : widths[mixIndex],
          }
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
      paragraphsByDoc
    );
  }

  // ---------------------------------------------------------------------------
  // public surface
  // ---------------------------------------------------------------------------

  // enhance: synchronous one-shot enhance (dissolves the former engine
  // facade enhance method, R10). The internal third param fromCanonical
  // controls the options resolution path; public entries pass false.
  export function enhance(
    context: EnhancedElementContext,
    root: Element,
    optionsBag: Record<string, unknown> | null,
    fromCanonical?: boolean,
  ): number {
    // TargetDocumentExplicit: install the copy listener on the document that
    // actually owns the enhanced root; the ambient fallback covers fake-DOM
    // test worlds whose roots carry no ownerDocument.
    const targetDocument = root.ownerDocument ?? globalThis.document;
    if (targetDocument) globalServices().clipboard.install(targetDocument);
    destroyRoot(context, root as HTMLElement);
    const resolved: ResolvedEnhanceOptions = fromCanonical
      ? context.optionsLedger.resolveEngineOptionsFromCanonical(root, optionsBag as EnhanceOptions)
      : context.optionsLedger.resolveEngineOptions(root, optionsBag as Record<string, unknown>);
    context.contextState.setRuntimeOptions(resolved);
    context.typography.establishRuntime(root, resolved);
    const candidates = context.contextState.paragraphCandidates(root, resolved.paragraphSelector);
    if (rejectMissingSharedRuntimeStyles(context, candidates)) return 0;
    for (let i = 0; i < candidates.length; i += 1) {
      processParagraph(context, candidates[i]);
    }
    publishRootState(context, false);
    return context.contextState.paragraphs.length;
  }

  // enhanceProgressively: raw host options bag (or null for a cold-start
  // relayout). The canonical entry enhanceProgressivelyFromCanonical accepts
  // already-resolved options and routes them through the canonical resolver.
  export function enhanceProgressively(
    context: EnhancedElementContext,
    root: Element,
    optionsBag: Record<string, unknown> | null,
  ): void {
    enhanceProgressivelyCore(context, root, optionsBag, "Enhance", false);
  }

  export function enhanceProgressivelyFromCanonical(
    context: EnhancedElementContext,
    root: Element,
    canonicalOptions: EnhanceOptions,
  ): void {
    enhanceProgressivelyCore(context, root, canonicalOptions, "Enhance", true);
  }
