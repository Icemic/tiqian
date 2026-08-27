// HostContentReconcile: classification, DOM preparation, and the root-level
// reconcile orchestration for live-DOM content changes on an enhanced root.
//
// Stateless module: probeContentDrift(rawDom, ...), classifyReconcile(
// rawDom, ...), prepareTrackedParagraphForRelowering(rawDom, ...) and
// stripEngineMarkupFromStrandedParagraph(rawDom, ...) are named functions
// that receive the raw-DOM collaborator as an explicit first parameter. The
// root-level entry points probeRootContentDrift and reconcileRoot receive the
// raw-DOM, root-state and layout-job-pool collaborators explicitly; they
// dissolve the former engine-instance facade methods probeContentDrift and
// reconcileContent (R10) and answer plain result objects instead of JSON
// strings.
//
// Embedding constraint: the generator wraps this file in a Kotlin raw
// string, so the source must contain no dollar sign and no triple
// double-quote sequence, and the Kotlin parser accepts only a classic JS
// subset: no optional chaining, no nullish coalescing, no spread arguments,
// no for-of loops, no bare catch. Use explicit conditionals, indexed loops
// and apply instead.

// Ambient global declarations pulled in via import type from owner modules.
import { releasePreparedParagraphStyles } from "../sampler/snapshot/prepared-dom.js";
import type { EnhancedElementContext } from "./context/enhance-context.js";
import {
  rawDomRenderedMatches,
  rawDomMatches,
  rawDomRestoreParagraph,
  rawDomRestoreShell,
  rawDomStampRendered,
} from "./raw-dom.js";
import type { RootState, RootStateApi } from "./root-state.js";
import type { LayoutJobPool } from "./layout-job-pool.js";
import { processParagraph } from "./process-paragraph.js";
import { startLayoutJob } from "./progressive-drivers.js";
import { computeCjkDashOutcome, needsCjkDashShaping } from "./loaders/cjk-dash.js";

export interface ReconcileSpec {
  trackedSources: Element[];
  tainted?: Element[];
  strandedCandidates?: Element[];
  rootSelector: string;
}

export interface ReconcileResult {
  outcome: "idle" | "work";
  drifted: Element[];
  rawDom: Element[];
  tainted: Element[];
  stranded: Element[];
  dead: number;
}

// Read-only drift probe answer (dissolved engine facade shape): unknown
// counts roots without runtime state, the remaining fields count tracked
// sources per classification.
export interface ContentDriftProbeResult {
  unknown: number;
  drifted: number;
  dead: number;
  rawDom: number;
}

// Reconcile verdict answer (dissolved engine facade shape): outcome plus one
// count per classification bucket.
export interface ContentReconcileResult {
  outcome: string;
  drifted: number;
  rawDom: number;
  tainted: number;
  stranded: number;
  dead: number;
}

// One reconcile work item: the affected paragraph and the closure that
// re-lowers it (Kotlin ReconcileAction equivalent).
type ReconcileActionRun = () => void;
export interface ReconcileAction {
  element: HTMLElement;
  run: ReconcileActionRun;
}

function releasePreparedStyles(element: Element, context: EnhancedElementContext): boolean {
  return releasePreparedParagraphStyles(element, context) === true;
}

// Read-only drift probe for captured in-flight jobs: answers the same
// per-paragraph classification question as classifyReconcile without
// touching the DOM, so element.js cancels only on real drift.
export function probeContentDrift(rawDomContext: EnhancedElementContext, trackedSources: Element[]): ContentDriftProbeResult {
  let drifted = 0;
  let dead = 0;
  let rawDomCount = 0;
  for (let index = 0; index < trackedSources.length; index++) {
    const source = trackedSources[index];
    if (!source.isConnected) {
      dead += 1;
    } else if (!rawDomRenderedMatches(rawDomContext, source)) {
      drifted += 1;
    } else if (!rawDomMatches(rawDomContext, source)) {
      rawDomCount += 1;
    }
  }
  return { unknown: 0, drifted: drifted, dead: dead, rawDom: rawDomCount };
}

// Per-paragraph classification, never per MutationRecord. DeadTrackedParagraphDrop
// counts tracked sources the host detached; the RenderedContentInvariant
// identity check flags drifted paragraphs; the raw-DOM backup identity check
// flags raw-DOM backup drift. A tainted host survives only when connected,
// inside a root, tracked, and not already classified as drifted. A
// stranded candidate is skipped when it already failed lowering with a
// capability marker and was never rendered (StrandedCapabilityNoRetry).
export function classifyReconcile(rawDomContext: EnhancedElementContext, spec: ReconcileSpec): ReconcileResult {
  const trackedSources = spec.trackedSources;
  const drifted: Element[] = [];
  const rawDomDrifted: Element[] = [];
  let dead = 0;
  const trackedSet = new Set<Element>();
  for (let trackIndex = 0; trackIndex < trackedSources.length; trackIndex++) {
    const trackedSource = trackedSources[trackIndex];
    trackedSet.add(trackedSource);
    if (!trackedSource.isConnected) {
      dead += 1;
    } else if (!rawDomRenderedMatches(rawDomContext, trackedSource)) {
      drifted.push(trackedSource);
    } else if (!rawDomMatches(rawDomContext, trackedSource)) {
      rawDomDrifted.push(trackedSource);
    }
  }
  const driftedSources = new Set<Element>();
  let driftedIndex;
  for (driftedIndex = 0; driftedIndex < drifted.length; driftedIndex++) {
    driftedSources.add(drifted[driftedIndex]);
  }
  for (driftedIndex = 0; driftedIndex < rawDomDrifted.length; driftedIndex++) {
    driftedSources.add(rawDomDrifted[driftedIndex]);
  }
  const tainted = spec.tainted || [];
  const taintedTracked: Element[] = [];
  for (let taintedIndex = 0; taintedIndex < tainted.length; taintedIndex++) {
    const host = tainted[taintedIndex];
    if (!host.isConnected) continue;
    if (!(host.closest && host.closest(spec.rootSelector))) continue;
    if (!trackedSet.has(host)) continue;
    if (driftedSources.has(host)) continue;
    taintedTracked.push(host);
  }
  const stranded: Element[] = [];
  const candidates = spec.strandedCandidates || [];
  for (let candidateIndex = 0; candidateIndex < candidates.length; candidateIndex++) {
    const candidate = candidates[candidateIndex];
    if (!candidate.hasAttribute("data-tiqian-capability-issue") ||
        candidate.hasAttribute("data-tq-rendered")) {
      stranded.push(candidate);
    }
  }
  const empty = drifted.length === 0 && rawDomDrifted.length === 0 &&
    taintedTracked.length === 0 && stranded.length === 0;
  return {
    outcome: empty ? "idle" : "work",
    drifted: drifted,
    rawDom: rawDomDrifted,
    tainted: taintedTracked,
    stranded: stranded,
    dead: dead,
  };
}

// HostEditRelowering: the host replaced or edited the live children of a
// rendered paragraph. Release prepared styles, restore the engine-owned
// shell, stamp the rendered marker, and let the caller re-lower the
// surviving live content as the new raw-DOM backup source.
export function prepareTrackedParagraphForRelowering(rawDomContext: EnhancedElementContext, element: HTMLElement): void {
  releasePreparedStyles(element, rawDomContext);
  rawDomRestoreShell(rawDomContext, element);
  rawDomStampRendered(rawDomContext, element);
}

// CloneDescaffoldEngineMarkup: innerHTML re-projection hands the runtime a
// clone that still carries engine scaffolding: line markers, copy-ignore
// spans, engine break elements, prepared value styles, and the paragraph
// takeover attributes. Remove exactly those engine-authored artifacts so
// the clone lowers as ordinary host content. Host elements and host
// inline styles survive untouched.
export function stripEngineMarkupFromStrandedParagraph(rawDomContext: EnhancedElementContext, paragraph: HTMLElement): void {
  releasePreparedStyles(paragraph, rawDomContext);
  // The hidden data-tq-hard-break span is the only place a cloned hard
  // break keeps its source form. Restore a bare br before removing
  // engine elements: a newline text node would be folded into a space by
  // collapse-mode re-lowering and lose the break.
  const hardBreaks = paragraph.querySelectorAll("[data-tq-hard-break]");
  for (let breakIndex = 0; breakIndex < hardBreaks.length; breakIndex++) {
    const hardBreak = hardBreaks[breakIndex];
    if (hardBreak.parentNode) {
      hardBreak.parentNode.replaceChild(document.createElement("br"), hardBreak);
    }
  }
  const artifacts = paragraph.querySelectorAll(
    "[data-tq-copy-ignore], [data-tq-engine-break], [data-tq-src], [data-tq-prepared-value-styles]",
  );
  for (let artifactIndex = 0; artifactIndex < artifacts.length; artifactIndex++) {
    const artifact = artifacts[artifactIndex];
    if (artifact.parentNode) artifact.parentNode.removeChild(artifact);
  }
  // Engine run spans position glyphs through --tq-* custom properties.
  // Those values are meaningless on host content and would survive
  // lowering, so strip them from every remaining descendant.
  const descendants = paragraph.querySelectorAll<HTMLElement>("*");
  for (let descIndex = 0; descIndex < descendants.length; descIndex++) {
    const element = descendants[descIndex];
    const engineProperties: string[] = [];
    for (let styleIndex = 0; styleIndex < element.style.length; styleIndex++) {
      const name = element.style.item(styleIndex);
      if (name.indexOf("--tq-") === 0) engineProperties.push(name);
    }
    for (let removeIndex = 0; removeIndex < engineProperties.length; removeIndex++) {
      element.style.removeProperty(engineProperties[removeIndex]);
    }
  }
  paragraph.removeAttribute("data-tq-rendered");
  paragraph.removeAttribute("data-tq-canonical-plain");
  paragraph.removeAttribute("data-tq-canonical-source");
  paragraph.removeAttribute("data-tq-snapshot-prepared-dom");
  paragraph.removeAttribute("data-tq-runtime-render-font");
  paragraph.removeAttribute("data-tq-host-inline-size");
  paragraph.removeAttribute("data-tiqian-capability-issue");
  paragraph.removeAttribute("data-tiqian-capability-detail");
  // EngineInlineStyleStrippingOnClone: takeover writes position,
  // inline-size and font-size with important priority. Originals are
  // unknown on a clone, so remove exactly those engine-signed writes.
  if (paragraph.style.getPropertyPriority("position") === "important" &&
      paragraph.style.getPropertyValue("position") === "relative") {
    paragraph.style.removeProperty("position");
  }
  if (paragraph.style.getPropertyPriority("inline-size") === "important") {
    paragraph.style.removeProperty("inline-size");
  }
  if (paragraph.style.getPropertyPriority("font-size") === "important") {
    paragraph.style.removeProperty("font-size");
  }
  const styleAttribute = paragraph.getAttribute("style");
  if (styleAttribute === null || styleAttribute.trim() === "") {
    paragraph.removeAttribute("style");
  }
}

// ---------------------------------------------------------------------------
// Root-level entry points (dissolved engine facade, R10)
// ---------------------------------------------------------------------------

// sourcesOf: maps state.paragraphs entries to their source elements
// (Kotlin sourcesToArray() equivalent, see WebEnhancerTsHost.kt:315).
function sourcesOf(state: RootState): HTMLElement[] {
  const result: HTMLElement[] = [];
  for (let i = 0; i < state.paragraphs.length; i += 1) {
    result.push(state.paragraphs[i].source as HTMLElement);
  }
  return result;
}

// removeEntryFor: in-place splice to remove the paragraph entry whose source
// === element from state.paragraphs (Kotlin removeAllMatching equivalent).
function removeEntryFor(state: RootState, element: HTMLElement): void {
  for (let i = state.paragraphs.length - 1; i >= 0; i -= 1) {
    if (state.paragraphs[i].source === element) {
      state.paragraphs.splice(i, 1);
      return;
    }
  }
}

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

// Root-level drift probe: a root without runtime state answers the unknown
// bucket (the former facade's '{"unknown":1,...}' answer), otherwise the
// tracked sources classify through the read-only probe.
export function probeRootContentDrift(rawDomContext: EnhancedElementContext, rootState: RootStateApi, root: Element): ContentDriftProbeResult {
  const state = rootState.getState(root);
  if (!state) return { unknown: 1, drifted: 0, dead: 0, rawDom: 0 };
  return probeContentDrift(rawDomContext, sourcesOf(state));
}

// Root-level reconcile orchestration (aligns WebEnhancerContentReconcile.kt
// 22-95). Answers null when the root carries no runtime state; otherwise
// classifies, refreshes the CJK dash capability evidence when needed, and
// schedules one layout job for every affected paragraph.
export function reconcileRoot(
  rawDomContext: EnhancedElementContext,
  rootState: RootStateApi,
  layoutJobPool: LayoutJobPool,
  root: HTMLElement,
  tainted: Element[],
): ContentReconcileResult | null {
  const state = rootState.getState(root);
  if (!state) return null;
  const spec: ReconcileSpec = {
    trackedSources: sourcesOf(state),
    tainted: tainted,
    strandedCandidates: rootState.strandedSourceParagraphs(root, state),
    rootSelector: "tiqian-prose, [data-tiqian-root]",
  };
  const verdict = classifyReconcile(rawDomContext, spec);
  const result: ContentReconcileResult = {
    outcome: verdict.outcome,
    drifted: verdict.drifted.length,
    rawDom: verdict.rawDom.length,
    tainted: verdict.tainted.length,
    stranded: verdict.stranded.length,
    dead: verdict.dead,
  };

  // DeadTrackedParagraphDrop: innerHTML re-projection orphans the runtime
  // onto detached originals. Drop them so re-projected clones are adopted as
  // fresh candidates.
  for (let d = state.paragraphs.length - 1; d >= 0; d -= 1) {
    if (!state.paragraphs[d].source.isConnected) {
      state.paragraphs.splice(d, 1);
    }
  }

  if (verdict.outcome === "idle") return result;

  // Refresh CJK dash capability evidence if any affected paragraph needs it.
  // The coordinated channel captures cjkDashCapability once at initial enhance
  // (element.ts) and bakes it into the root state's browserFallback. When the
  // DOM gains dash content after initial enhance, we must recompute against
  // the current root.textContent so the coordinated channel agrees with the
  // one-shot channel (which recomputes on every call via api.ts).
  const affectedParagraphs = verdict.drifted.concat(verdict.tainted, verdict.stranded);
  let needsDashRefresh = false;
  for (let pi = 0; pi < affectedParagraphs.length; pi += 1) {
    if (needsCjkDashShaping(affectedParagraphs[pi])) {
      needsDashRefresh = true;
      break;
    }
  }
  if (needsDashRefresh) {
    const freshOutcome = computeCjkDashOutcome(root, {
      snapshotFontSession: state.options.snapshotFontSession,
    });
    if (state.cjkDashCapability.status !== freshOutcome.status ||
        state.cjkDashCapability.detail !== freshOutcome.detail) {
      rootState.updateCjkDashCapability(state, freshOutcome);
    }
  }

  // Build action list: each entry is {element, run} closure (Kotlin
  // ReconcileAction equivalent).
  const actions: ReconcileAction[] = [];
  let vi: number;
  for (vi = 0; vi < verdict.drifted.length; vi += 1) {
    (function (element: HTMLElement) {
      actions.push({
        element: element,
        run: function () {
          removeEntryFor(state, element);
          prepareTrackedParagraphForRelowering(rawDomContext, element);
          processParagraph(rawDomContext, rootState.processParagraphArgument(state, element));
        },
      });
    })(verdict.drifted[vi] as HTMLElement);
  }
  for (vi = 0; vi < verdict.rawDom.length; vi += 1) {
    // RawDomDriftRerendersFromRawDom: a host edit inside the raw-DOM backup
    // fragment leaves the live paragraph matching the rendered invariant, so
    // only the raw-DOM backup identity check sees it. Restore hands it back to the
    // live DOM and processParagraph re-lowers the edited content.
    (function (element: HTMLElement) {
      actions.push({
        element: element,
        run: function () {
          removeEntryFor(state, element);
          rawDomRestoreParagraph(rawDomContext, element);
          processParagraph(rawDomContext, rootState.processParagraphArgument(state, element));
        },
      });
    })(verdict.rawDom[vi] as HTMLElement);
  }
  for (vi = 0; vi < verdict.tainted.length; vi += 1) {
    // TaintedEngineOutputRerendersFromRawDom: an in-place text edit inside
    // engine output does not change child identity. The edited node belongs
    // to the renderer, so the semantic truth stays in the raw-DOM backup and the
    // paragraph re-renders from it.
    (function (element: HTMLElement) {
      actions.push({
        element: element,
        run: function () {
          removeEntryFor(state, element);
          rawDomRestoreParagraph(rawDomContext, element);
          processParagraph(rawDomContext, rootState.processParagraphArgument(state, element));
        },
      });
    })(verdict.tainted[vi] as HTMLElement);
  }
  for (vi = 0; vi < verdict.stranded.length; vi += 1) {
    (function (element: HTMLElement) {
      actions.push({
        element: element,
        run: function () {
          stripEngineMarkupFromStrandedParagraph(rawDomContext, element);
          processParagraph(rawDomContext, rootState.processParagraphArgument(state, element));
        },
      });
    })(verdict.stranded[vi] as HTMLElement);
  }

  // WidthSnapshotPerReconcileJob: mirrors WidthSnapshotPerRelayoutJob -- a
  // mid-job width move reports stale and element.js schedules one
  // latest-width follow-up.
  const distances: number[] = new Array(actions.length);
  let ai: number;
  for (ai = 0; ai < actions.length; ai += 1) {
    distances[ai] = paragraphViewportDistance(actions[ai].element);
  }
  const itemTierIndex: number[] = new Array(actions.length);
  for (ai = 0; ai < actions.length; ai += 1) {
    itemTierIndex[ai] = ai;
  }
  itemTierIndex.sort(function (a: number, b: number): number {
    return distances[a] - distances[b] || a - b;
  });
  const rootWidth = elementFragmentBorderBoxInlineSize(root);
  startLayoutJob(
    rootState,
    layoutJobPool,
    state,
    "Relayout",
    actions.length,
    function (index: number) { actions[itemTierIndex[index]].run(); },
    null,
    null,
    function (): boolean { return Math.abs(elementFragmentBorderBoxInlineSize(root) - rootWidth) >= 0.5; },
    itemTierIndex,
    actions.map(function (a: ReconcileAction): HTMLElement { return a.element; })
  );
  return result;
}
