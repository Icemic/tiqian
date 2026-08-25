import { globalServices } from "../services/global-services.js";
// RootState maintenance for the enhance pipeline (TsHost runtime port,
// Slice 5). Ports the Kotlin RootState data class and its state methods from
// WebEnhancer.kt (lines 206-272, 454-489) together with the engine-state and
// argument descriptors from WebEnhancerTsHost.kt (lines 225-270).
//
// Stateful module: createRootState(deps) closes over the font-families and
// browser-metrics-bridge factories and calls the eligibility
// shouldTryParagraph predicate directly. The engine bootstrap constructs one
// instance. The per-root state registries (WeakMap) are instance state, never
// module state.

import type { BrowserMetricsBridgeInstance } from "./browser-metrics-bridge.js";
import type { LoweredParagraph } from "./lowered-paragraph.js";
import type { CanvasContextLike } from "./canvas-metrics.js";
import type { CanvasShapingEnv, ProbeElementLike } from "./canvas-shaping.js";
import type { EnhanceOptions, ResolvedEnhanceOptions } from "./lifecycle.js";
import {
  allowsSnapshotLayout,
  conformingSnapshotFontSessionId,
  optionsFromJs,
  withoutSnapshotFontSession,
  withRootDefaults,
} from "./lifecycle.js";
import { createFontFamilies } from "./canvas-fonts.js";
import { createBrowserMetricsBridge } from "./browser-metrics-bridge.js";
import { shouldTryParagraph } from "./eligibility.js";
import { snapshotSessionCallbacks } from "../../browser-font-replay.js";
import type { MetricsJsonFn, ShapeJsonFn } from "../../browser-font-replay.js";

// Descriptor returned by activeSnapshotSessionDescriptor: the shaping callbacks
// of a conforming snapshot session, or null when the active options lower the
// session. ffi takes the callbacks as call parameters; no session id crosses
// the boundary.
export type SnapshotSessionDescriptor = {
  shapeJson: ShapeJsonFn;
  metricsJson: MetricsJsonFn;
};

// Browser fallback descriptor built once per root and embedded in every lane
// argument. Type alias (not interface) so it stays assignable to the loose
// Record<string, unknown> slots in the orchestrator globals.
export type BrowserFallbackDescriptor = { bridge: BrowserMetricsBridgeInstance };

// One tracked semantic paragraph in engine state: the raw-DOM backup source element,
// its lowered markdown tree, and the last applied measure.
export type TrackedParagraph = {
  source: Element;
  lowered: LoweredParagraph;
  lastMeasure: number | null;
};

// Issue record stored in state.issues and reported through lifecycle
// reportIssue/clearIssue. Producers fill name/detail/element; the lifecycle
// layer owns marker bookkeeping.
export type RootStateIssueRecord = {
  kind?: string;
  name?: string;
  detail?: string;
  element?: Element;
  measure?: number;
  reportToConsole?: boolean;
};

export type RootState = {
  root: Element;
  options: ResolvedEnhanceOptions;
  browserFallback: BrowserFallbackDescriptor;
  paragraphs: TrackedParagraph[];
  issues: RootStateIssueRecord[];
  preparedDomEnabled: boolean;
  preparedDomFallback: string | null;
};

type RootStateOnIssueFn = (issue: RootStateIssueRecord) => void;
type RootStateOnParagraphCommittedFn = (item: TrackedParagraph) => void;
type RootStateOnDisableSnapshotPreparedDomFn = (detail: unknown) => void;

// Live view handed to the embedded TS orchestrators: callbacks splice/push the
// same arrays the host mutates.
export type EngineState = {
  options: EnhanceOptions;
  preparedDomEnabled: boolean;
  snapshotSession: SnapshotSessionDescriptor | null;
  browserFallback: BrowserFallbackDescriptor | null;
  onIssue: RootStateOnIssueFn;
  onParagraphCommitted: RootStateOnParagraphCommittedFn;
  onDisableSnapshotPreparedDom: RootStateOnDisableSnapshotPreparedDomFn;
  paragraphs: TrackedParagraph[];
  issues: RootStateIssueRecord[];
};

export type ProcessParagraphArgument = {
  paragraph: Element;
  state: EngineState;
};

export type SessionArgument = {
  paragraphs: TrackedParagraph[];
  state: EngineState;
};

export type PrepareArgument = {
  paragraph: TrackedParagraph;
  options: EnhanceOptions;
  snapshotSession: SnapshotSessionDescriptor | null;
  browserFallback: BrowserFallbackDescriptor;
  widthOverride: number | null;
};

type RootStateCreateFn = (root: Element, optionsBag: Record<string, unknown>) => RootState;
type RootStateCreateFromCanonicalFn = (root: Element, canonicalOptions: EnhanceOptions) => RootState;
type RootStateActiveTsOptionsFn = (state: RootState) => EnhanceOptions;
type RootStateActiveSnapshotSessionDescriptorFn = (state: RootState) => SnapshotSessionDescriptor | null;
type RootStateDisableSnapshotPreparedDomFn = (state: RootState, detail: unknown) => void;
type RootStateEngineStateFn = (state: RootState) => EngineState;
type RootStateProcessParagraphArgumentFn = (
  state: RootState,
  paragraph: Element,
) => ProcessParagraphArgument;
type RootStateSessionArgumentFn = (state: RootState) => SessionArgument;
type RootStatePrepareArgumentFn = (
  state: RootState,
  paragraph: TrackedParagraph,
  widthOverride: number | null,
) => PrepareArgument;
type RootStateGetFn = (root: Element) => RootState | undefined;
type RootStateSetFn = (root: Element, state: RootState) => void;
type RootStateDeleteFn = (root: Element) => void;
type RootStateParagraphCandidatesFn = (root: Element, selector: string) => Element[];
type RootStateStrandedSourceParagraphsFn = (root: Element, state: RootState) => Element[];
type RootStatePublishFn = (state: RootState, keepEmpty?: boolean) => void;

export type RootStateApi = {
  createRootState: RootStateCreateFn;
  createRootStateFromCanonical: RootStateCreateFromCanonicalFn;
  activeTsOptions: RootStateActiveTsOptionsFn;
  activeSnapshotSessionDescriptor: RootStateActiveSnapshotSessionDescriptorFn;
  disableSnapshotPreparedDom: RootStateDisableSnapshotPreparedDomFn;
  engineState: RootStateEngineStateFn;
  processParagraphArgument: RootStateProcessParagraphArgumentFn;
  sessionArgument: RootStateSessionArgumentFn;
  prepareArgument: RootStatePrepareArgumentFn;
  getState: RootStateGetFn;
  setState: RootStateSetFn;
  deleteState: RootStateDeleteFn;
  paragraphCandidates: RootStateParagraphCandidatesFn;
  strandedSourceParagraphs: RootStateStrandedSourceParagraphsFn;
  publishState: RootStatePublishFn;
};

export function createRootState(): RootStateApi {
  const SNAPSHOT_PREPARED_FALLBACK_ATTRIBUTE: string = "data-tiqian-exact-layout-fallback";
  const ROOT_SELECTOR: string = "tiqian-prose, [data-tiqian-root]";
  const CAPABILITY_DETAIL_LIMIT: number = 512;

  // DetachedRootWeakOwnership: navigation can discard a rendered article
  // without reconstructing its semantic DOM. Weak ownership retains the
  // source fragments only if a host later reconnects that exact element.
  const states = new WeakMap<Element, RootState>();

  // belongsToRootScope: a candidate belongs to this root when its nearest
  // scope-owning ancestor is absent, is the root itself, or lives outside the
  // root. Mirror of the belongsToRootScope @JsFun in WebEnhancerSupport.kt;
  // the closest guard keeps fake elements honest.
  function belongsToRootScope(paragraph: Element, root: Element, selector: string): boolean {
    if (!paragraph.closest) return true;
    const owner = paragraph.closest(selector);
    return !owner || owner === root || !root.contains(owner);
  }

  // RuntimeEligibleMeasureSet: progressive staleness compares the
  // same leaf paragraphs that can actually enter the pipeline.
  // Measuring a host-owned outer <li> and later rendering its
  // child <p> changes the container's live width/measure, which
  // used to roll back every valid child as a false stale job.
  function paragraphCandidates(root: Element, selector: string): Element[] {
    const nodes = root.querySelectorAll(selector);
    const result = [];
    for (let i = 0; i < nodes.length; i += 1) {
      const paragraph = nodes[i];
      if (belongsToRootScope(paragraph, root, ROOT_SELECTOR) &&
          shouldTryParagraph(paragraph)) {
        result.push(paragraph);
      }
    }
    return result;
  }

  // The canvas modules own their probe nodes; attachProbe keeps the probe in
  // the document without duplicating it across measures.
  function browserMetricsEnv(): CanvasShapingEnv {
    return {
      createCanvasContext: function (): CanvasContextLike {
        return document.createElement("canvas").getContext("2d") as CanvasContextLike;
      },
      createProbeElement: function (): ProbeElementLike {
        return document.createElement("span") as ProbeElementLike;
      },
      attachProbe: function (node: ProbeElementLike): void {
        if (!node.parentNode) document.body.appendChild(node as HTMLElement);
      },
    };
  }

  // The {bridge} descriptor every TS layout lane consumes. The inner bridge
  // adapts the canvas shaper and metrics resolver to the two JSON callbacks
  // of precomputeParagraphWithBrowserMetrics. Built once per root.
  function buildBrowserFallbackDescriptor(resolved: ResolvedEnhanceOptions): BrowserFallbackDescriptor {
    const fontFamilies = resolved.fontFamilies;
    // buildFontFamiliesConfigJs renames the resolved monospace family to the
    // latinMonospace key that canvas-fonts.js reads for the LatinText role.
    const fonts = createFontFamilies({
      cjk: fontFamilies.cjk,
      latin: fontFamilies.latin,
      latinMonospace: fontFamilies.monospace,
      cjkSerif: fontFamilies.cjkSerif,
      latinSerif: fontFamilies.latinSerif,
    });
    const bridge = createBrowserMetricsBridge({
      fonts: fonts,
      cjkDashCapability: resolved.cjkDashCapability,
      env: browserMetricsEnv(),
    });
    return { bridge: bridge };
  }

  function createRootState(root: Element, optionsBag: Record<string, unknown>): RootState {
    root.removeAttribute(SNAPSHOT_PREPARED_FALLBACK_ATTRIBUTE);
    const canonical = optionsFromJs(optionsBag);
    // allowsSnapshotLayout ? options : options.copy(snapshotFontSession =
    // null): an exact snapshot only reproduces the host with root defaults,
    // so configured typography lowers the snapshot font session.
    const snapshotEligible = allowsSnapshotLayout(canonical)
      ? canonical
      : withoutSnapshotFontSession(canonical);
    const resolved = withRootDefaults(snapshotEligible, root);
    if (resolved.trace) globalServices().coordination.traceConfig = resolved.trace;
    return newRootState(root, resolved);
  }

  function createRootStateFromCanonical(root: Element, canonicalOptions: EnhanceOptions): RootState {
    // Re-entry path for relayout/refresh: the canonical options already came
    // from optionsFromJs output shape, so the snapshot gate is skipped.
    root.removeAttribute(SNAPSHOT_PREPARED_FALLBACK_ATTRIBUTE);
    const resolved = withRootDefaults(canonicalOptions, root);
    if (resolved.trace) globalServices().coordination.traceConfig = resolved.trace;
    return newRootState(root, resolved);
  }

  // Canonical TS options and the browser fallback descriptor are built
  // once per root and consumed by every embedded TS orchestrator. Live JS
  // arrays: the TS session module splices and pushes these by reference,
  // so the host mutates the same storage.
  // PreparedDomLane: every paragraph renders through the prepared DOM,
  // including roots that never configured an snapshot font session. After
  // a replay fails geometry validation the flag distrusts the exact
  // session metrics for the whole root; paragraphs keep rendering
  // through the prepared bridge with browser metrics, and the
  // per-paragraph validator still guards every render.
  function newRootState(root: Element, resolved: ResolvedEnhanceOptions): RootState {
    return {
      root: root,
      options: resolved,
      browserFallback: buildBrowserFallbackDescriptor(resolved),
      paragraphs: [],
      issues: [],
      preparedDomEnabled: true,
      preparedDomFallback: null,
    };
  }

  function getState(root: Element): RootState | undefined {
    return states.get(root);
  }

  function setState(root: Element, state: RootState): void {
    states.set(root, state);
  }

  function deleteState(root: Element): void {
    states.delete(root);
  }

  function activeTsOptions(state: RootState): EnhanceOptions {
    if (state.preparedDomEnabled) return state.options;
    return withoutSnapshotFontSession(state.options);
  }

  // Kotlin resolves the descriptor off activeOptions().conformingSnapshotFont
  // SessionId(), so once prepared DOM is disabled the session is always null;
  // the TS options lane reads the same active options here.
  function activeSnapshotSessionDescriptor(state: RootState): SnapshotSessionDescriptor | null {
    const sessionId = conformingSnapshotFontSessionId(activeTsOptions(state));
    if (sessionId == null) return null;
    return snapshotSessionCallbacks(sessionId);
  }

  function disableSnapshotPreparedDom(state: RootState, detail: unknown): void {
    if (!state.preparedDomEnabled) return;
    state.preparedDomEnabled = false;
    state.preparedDomFallback = String(detail).slice(0, CAPABILITY_DETAIL_LIMIT);
    state.root.setAttribute(SNAPSHOT_PREPARED_FALLBACK_ATTRIBUTE, state.preparedDomFallback);
  }

  function engineState(state: RootState): EngineState {
    return {
      options: state.options,
      preparedDomEnabled: state.preparedDomEnabled,
      snapshotSession: activeSnapshotSessionDescriptor(state),
      browserFallback: state.browserFallback,
      onIssue: function (issue: RootStateIssueRecord): void { state.issues.push(issue); },
      onParagraphCommitted: function (item: TrackedParagraph): void { state.paragraphs.push(item); },
      onDisableSnapshotPreparedDom: function (detail: unknown): void { disableSnapshotPreparedDom(state, detail); },
      paragraphs: state.paragraphs,
      issues: state.issues,
    };
  }

  function processParagraphArgument(state: RootState, paragraph: Element): ProcessParagraphArgument {
    return { paragraph: paragraph, state: engineState(state) };
  }

  function sessionArgument(state: RootState): SessionArgument {
    return { paragraphs: state.paragraphs, state: engineState(state) };
  }

  function prepareArgument(state: RootState, paragraph: TrackedParagraph, widthOverride: number | null): PrepareArgument {
    return {
      paragraph: paragraph,
      options: activeTsOptions(state),
      snapshotSession: activeSnapshotSessionDescriptor(state),
      browserFallback: state.browserFallback,
      widthOverride: widthOverride == null ? null : widthOverride,
    };
  }

  function strandedSourceParagraphs(root: Element, state: RootState): Element[] {
    const candidates = paragraphCandidates(root, state.options.paragraphSelector);
    if (state.paragraphs.length === 0) return candidates;
    const renderedSources = new Set();
    for (let i = 0; i < state.paragraphs.length; i += 1) {
      renderedSources.add(state.paragraphs[i].source);
    }
    const result = [];
    for (let j = 0; j < candidates.length; j += 1) {
      if (!renderedSources.has(candidates[j])) result.push(candidates[j]);
    }
    return result;
  }

  function observableSnapshotCount(root: Element): number {
    const value = Number(root.getAttribute("data-tiqian-snapshot-count"));
    return Number.isSafeInteger(value) && value > 0 ? value : 0;
  }

  function publishState(state: RootState, keepEmpty?: boolean): void {
    const hasWork = state.paragraphs.length > 0 || state.issues.length > 0;
    if (!hasWork && !keepEmpty) {
      deleteState(state.root);
      state.root.removeAttribute("data-tiqian-enhanced");
      state.root.removeAttribute("data-tiqian-enhanced-count");
      state.root.removeAttribute("data-tiqian-issue-count");
      return;
    }
    setState(state.root, state);
    state.root.setAttribute("data-tiqian-enhanced", "true");
    state.root.setAttribute(
      "data-tiqian-enhanced-count",
      String(state.paragraphs.length + observableSnapshotCount(state.root)),
    );
    if (state.issues.length === 0) {
      state.root.removeAttribute("data-tiqian-issue-count");
    } else {
      state.root.setAttribute("data-tiqian-issue-count", String(state.issues.length));
    }
  }

  return {
    createRootState: createRootState,
    createRootStateFromCanonical: createRootStateFromCanonical,
    activeTsOptions: activeTsOptions,
    activeSnapshotSessionDescriptor: activeSnapshotSessionDescriptor,
    disableSnapshotPreparedDom: disableSnapshotPreparedDom,
    engineState: engineState,
    processParagraphArgument: processParagraphArgument,
    sessionArgument: sessionArgument,
    prepareArgument: prepareArgument,
    getState: getState,
    setState: setState,
    deleteState: deleteState,
    paragraphCandidates: paragraphCandidates,
    strandedSourceParagraphs: strandedSourceParagraphs,
    publishState: publishState,
  };
}