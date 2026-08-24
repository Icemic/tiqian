// engine-entry (TsHost runtime port, Slice 6). Constructs the TypeScript host
// engine entry object that replaces Kotlin @JsExport object TiqianEngine
// (WebEnhancerEngineExport.kt) and TiqianWebWorkers
// (WebEnhancerWorkerProtocol.kt), and moves the Kotlin
// WebEnhancerContentReconcile.kt reconcile orchestration into TS.
//
// Consumes __TiqianRootState, __TiqianProgressiveDrivers,
// __TiqianProgressiveJob, __TiqianCustody, __TiqianLifecycle,
// __TiqianContentReconcile, __TiqianProcessParagraph,
// __TiqianWorkerRequest, __TiqianPreparedDomRenderer,
// __TiqianInstallCopyHandler.
//
// Plain script, no exports: running it installs globalThis.__TiqianEngine
// and globalThis.__TiqianEngineWorkers. Triple installation is guarded.
//
// Embedding constraint: the generator wraps this file in a Kotlin raw string,
// so the source must contain no dollar sign and no triple double-quote
// sequence. Use string concatenation, never template literals. Use var
// declarations.

// Ambient global declarations pulled in via import type from owner modules.
import type { GrantController } from "./coordinator/coordinator.js";
import type { RootState, RootStateApi } from "./root-state.js";
import type { ProgressiveDriversApi } from "./progressive-drivers.js";
import type { ProgressiveJobApi } from "./progressive-job.js";
import type { CustodyApi } from "./custody.js";
import type { EnhanceOptions, LifecycleApi } from "./lifecycle.js";
import type { ReconcileGlobal, ReconcileSpec } from "./content-reconcile.js";
import type { TiqianProcessParagraphGlobal } from "./process-paragraph.js";
import type { TiqianWorkerRequestGlobal } from "./worker-request.js";
import type { EngineFfiFacade } from "./ffi-face.js";
import type { PreparedDomRendererApi } from "../sampler/snapshot/prepared-dom.js";
import type {} from "../utils/copy.js";

export type ActionRunFn = () => void;

export interface ReconcileAction {
  element: HTMLElement;
  run: ActionRunFn;
}

export interface TiqianEngineInstance {
  enhance(root: HTMLElement, optionsBag?: unknown, fromCanonical?: boolean): number;
  enhanceProgressively(root: HTMLElement, optionsBag?: unknown): void;
  enhanceAll(optionsBag?: unknown): number;
  destroy(root: HTMLElement): void;
  detach(root: HTMLElement): void;
  relayout(root: HTMLElement): void;
  refresh(root: HTMLElement, progressively?: boolean): void;
  cancelLayoutWork(root: HTMLElement): void;
  probeContentDrift(root: HTMLElement): string;
  reconcileContent(root: HTMLElement, tainted: HTMLElement[]): string;
  workerLayoutRequest(root: HTMLElement, paragraph: HTMLElement, optionsBag?: unknown): string | null;
}

export interface TiqianEngineWorkersInstance {
  workerAttach(root: HTMLElement): boolean;
  workerDetach(root: HTMLElement): boolean;
  workerHasJob(root: HTMLElement): boolean;
  workerJobGeneration(root: HTMLElement): number;
  workerRunSlice(controller: GrantController, minTier: number): number;
  workerPendingInTier(root: HTMLElement, tier: number): number;
  workerParagraphCount(root: HTMLElement): number;
  workerParagraphAt(root: HTMLElement, index: number): HTMLElement | null;
  workerSetParagraphTier(root: HTMLElement, index: number, tier: number): boolean;
}

declare global {
  var __TiqianEngine: TiqianEngineInstance | undefined;
  var __TiqianEngineWorkers: TiqianEngineWorkersInstance | undefined;
}

(function () {
  if (globalThis.__TiqianEngine) return;

  // ---------------------------------------------------------------------------
  // Internal helpers (from WebEnhancerSupport.kt @JsFun bodies)
  // ---------------------------------------------------------------------------

  function ensureCopyHandler(): void {
    const installer = globalThis.__TiqianInstallCopyHandler;
    if (installer && globalThis.document) installer(globalThis.document);
  }

  function releasePreparedRootDomStyles(root: HTMLElement): boolean {
    const r = globalThis.__TiqianPreparedDomRenderer;
    return !!(r && r.releaseRoot && r.releaseRoot(root) === true);
  }

  // observableSnapshotCount: reads data-tiqian-snapshot-count attribute; safe
  // integer and > 0, else 0.
  function observableSnapshotCount(root: HTMLElement): number {
    const raw = root.getAttribute("data-tiqian-snapshot-count");
    const value = Number(raw);
    if (Number.isSafeInteger(value) && value > 0) return value;
    return 0;
  }

  // CssFragmentedBlockInlineMeasure: plain getBoundingClientRect().width -- for
  // a block fragmented by CSS columns this is the union of every fragment, not
  // a per-fragment measure. Every caller uses it only for coarse >=0.5px drift
  // detection, where the union error is dwarfed by the tolerance (see the ADR
  // 0039 fractional fragment-aware amendment). A caller that needs the widest
  // live fragment must use elementContentWidth from
  // npm/core/engine/responsive-measure.js (installed as the responsive measure
  // bridge) instead.
  function elementFragmentBorderBoxInlineSize(element: HTMLElement | null): number {
    if (!element) return 0;
    return element.getBoundingClientRect ? element.getBoundingClientRect().width : 0;
  }

  // paragraphViewportDistance: returns 0 when the element is visible in the
  // viewport, or a positive pixel distance otherwise (negative of bottom for
  // above-viewport, top minus viewportHeight for below-viewport).
  function paragraphViewportDistance(element: HTMLElement | null): number {
    if (!element || !element.getBoundingClientRect) return 0;
    const rect = element.getBoundingClientRect();
    const viewportHeight = window.innerHeight || document.documentElement.clientHeight || 0;
    if (rect.bottom >= 0 && rect.top <= viewportHeight) return 0;
    return rect.bottom < 0 ? -rect.bottom : rect.top - viewportHeight;
  }

  function isElement(node: Node | null): boolean {
    return !!(node && node.nodeType === 1);
  }

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

  // processParagraphOf: process a single element through the layout pipeline.
  function processParagraphOf(element: HTMLElement, state: RootState): void {
    const RS = globalThis.__TiqianRootState!;
    globalThis.__TiqianProcessParagraph!.processParagraph(
      RS.processParagraphArgument(state, element)
    );
  }

  // ---------------------------------------------------------------------------
  // __TiqianEngine (11 methods)
  // ---------------------------------------------------------------------------

  const engine: Partial<TiqianEngineInstance> = {};

  // 1. enhance(root, optionsBag) -> number
  // Synchronous one-shot enhance. Internal third param fromCanonical controls
  // the root-state creation path; public entry passes false.
  engine.enhance = function enhance(root: HTMLElement, optionsBag?: unknown, fromCanonical?: boolean): number {
    const RS = globalThis.__TiqianRootState!;
    const DRIVERS = globalThis.__TiqianProgressiveDrivers!;
    ensureCopyHandler();
    engine.destroy!(root);
    const state = fromCanonical
      ? RS.createRootStateFromCanonical(root, optionsBag as EnhanceOptions)
      : RS.createRootState(root, optionsBag as Record<string, unknown>);
    const candidates = RS.paragraphCandidates(root, state.options.paragraphSelector);
    if (DRIVERS.rejectMissingSharedRuntimeStyles(state, candidates)) return 0;
    for (let i = 0; i < candidates.length; i += 1) {
      globalThis.__TiqianProcessParagraph!.processParagraph(
        RS.processParagraphArgument(state, candidates[i])
      );
    }
    RS.publishState(state);
    return state.paragraphs.length;
  };

  // 2. enhanceProgressively(root, optionsBag)
  // The copy handler install and destroy run inside the drivers entry, so
  // relayout restarts that enter the drivers directly destroy too.
  engine.enhanceProgressively = function enhanceProgressively(root: HTMLElement, optionsBag?: unknown): void {
    globalThis.__TiqianProgressiveDrivers!.enhanceProgressively(root, optionsBag as Record<string, unknown> | null);
  };

  // 3. enhanceAll(optionsBag) -> number
  engine.enhanceAll = function enhanceAll(optionsBag?: unknown): number {
    const doc = globalThis.document;
    if (!doc) return 0;
    const roots = doc.querySelectorAll("tiqian-prose, [data-tiqian-root]");
    let count = 0;
    for (let i = 0; i < roots.length; i += 1) {
      const node = roots[i];
      // NodeList in real browsers returns Element, but fake DOM may expose
      // item() so guard non-element nodes.
      const el = typeof roots.item === "function" ? (roots.item(i) as HTMLElement) : (node as HTMLElement);
      if (!isElement(el)) continue;
      count += engine.enhance!(el, optionsBag);
    }
    return count;
  };

  // 4. destroy(root) -- aligns WebEnhancer.kt 167-194
  engine.destroy = function destroy(root: HTMLElement): void {
    const RS = globalThis.__TiqianRootState!;
    const PJ = globalThis.__TiqianProgressiveJob!;
    PJ.cancelJob(root);
    const state = RS.getState(root);
    RS.deleteState(root);
    if (state != null) {
      let j: number;
      for (j = 0; j < state.paragraphs.length; j += 1) {
        globalThis.__TiqianCustody!.restoreParagraph(state.paragraphs[j].source);
      }
      for (j = 0; j < state.issues.length; j += 1) {
        globalThis.__TiqianLifecycle!.clearIssue(state.issues[j]);
      }
      // SnapshotCompactValueCSS: a precomputed snapshot may be live without a
      // Kotlin runtime state while list-only enhancement starts. Its compact
      // value CSS belongs to the snapshot owner and must survive that no-op
      // destroy.
      releasePreparedRootDomStyles(root);
    }
    const snapshotCount = observableSnapshotCount(root);
    if (snapshotCount > 0) {
      root.setAttribute("data-tiqian-enhanced", "true");
      root.setAttribute("data-tiqian-enhanced-count", String(snapshotCount));
    } else {
      root.removeAttribute("data-tiqian-enhanced");
      root.removeAttribute("data-tiqian-enhanced-count");
    }
    root.removeAttribute("data-tiqian-issue-count");
    root.removeAttribute("data-tiqian-relayout-error");
    root.removeAttribute("data-tiqian-exact-layout-fallback");
  };

  // 5. detach(root)
  // DetachedRootWeakOwnership: cancel job and release styles; weak table state
  // stays for reconnection on the same node.
  engine.detach = function detach(root: HTMLElement): void {
    const PJ = globalThis.__TiqianProgressiveJob!;
    PJ.cancelJob(root);
    releasePreparedRootDomStyles(root);
  };

  // 6. relayout(root) -- delegates to drivers.
  engine.relayout = function relayout(root: HTMLElement): void {
    const DRIVERS = globalThis.__TiqianProgressiveDrivers!;
    DRIVERS.relayout(root);
  };

  // 7. refresh(root, progressively)
  engine.refresh = function refresh(root: HTMLElement, progressively?: boolean): void {
    const RS = globalThis.__TiqianRootState!;
    const state = RS.getState(root);
    if (!state) return;
    if (progressively) {
      globalThis.__TiqianProgressiveDrivers!.enhanceProgressivelyFromCanonical(
        root,
        state.options
      );
    } else {
      engine.enhance!(root, state.options, true);
    }
  };

  // 8. cancelLayoutWork(root)
  engine.cancelLayoutWork = function cancelLayoutWork(root: HTMLElement): void {
    const PJ = globalThis.__TiqianProgressiveJob!;
    PJ.cancelJob(root);
  };

  // 9. probeContentDrift(root) -> string
  engine.probeContentDrift = function probeContentDrift(root: HTMLElement): string {
    const RS = globalThis.__TiqianRootState!;
    const state = RS.getState(root);
    if (!state) return '{"unknown":1,"drifted":0,"dead":0,"custody":0}';
    return globalThis.__TiqianContentReconcile!.probeContentDrift(sourcesOf(state));
  };

  // 10. reconcileContent(root, tainted) -> string
  // Aligns WebEnhancerContentReconcile.kt 22-95.
  engine.reconcileContent = function reconcileContent(root: HTMLElement, tainted: HTMLElement[]): string {
    const RS = globalThis.__TiqianRootState!;
    const DRIVERS = globalThis.__TiqianProgressiveDrivers!;
    const state = RS.getState(root);
    if (!state) {
      return '{"outcome":"idle","drifted":0,"custody":0,"tainted":0,"stranded":0,"dead":0}';
    }
    const spec: ReconcileSpec = {
      trackedSources: sourcesOf(state),
      tainted: tainted,
      strandedCandidates: RS.strandedSourceParagraphs(root, state),
      rootSelector: "tiqian-prose, [data-tiqian-root]",
    };
    const verdict = globalThis.__TiqianContentReconcile!.classifyReconcile(spec);

    // DeadTrackedParagraphDrop: innerHTML re-projection orphans the runtime
    // onto detached originals. Drop them so re-projected clones are adopted as
    // fresh candidates.
    for (let d = state.paragraphs.length - 1; d >= 0; d -= 1) {
      if (!state.paragraphs[d].source.isConnected) {
        state.paragraphs.splice(d, 1);
      }
    }

    if (verdict.outcome === "idle") return verdict.json;

    // Build action list: each entry is {element, run} closure (Kotlin
    // ReconcileAction equivalent).
    const actions: ReconcileAction[] = [];
    let vi: number;
    for (vi = 0; vi < verdict.drifted.length; vi += 1) {
      (function (element: HTMLElement) {
        actions.push({
          element: element,
          run: function () {
            removeEntryFor(state!, element);
            globalThis.__TiqianContentReconcile!.prepareTrackedParagraphForRelowering(element);
            processParagraphOf(element, state!);
          },
        });
      })(verdict.drifted[vi] as HTMLElement);
    }
    for (vi = 0; vi < verdict.custody.length; vi += 1) {
      // CustodyDriftRerendersFromCustody: a host edit inside the custody
      // fragment leaves the live paragraph matching the rendered invariant, so
      // only the custody identity check sees it. Restore hands it back to the
      // live DOM and processParagraph re-lowers the edited content.
      (function (element: HTMLElement) {
        actions.push({
          element: element,
          run: function () {
            removeEntryFor(state!, element);
            globalThis.__TiqianCustody!.restoreParagraph(element);
            processParagraphOf(element, state!);
          },
        });
      })(verdict.custody[vi] as HTMLElement);
    }
    for (vi = 0; vi < verdict.tainted.length; vi += 1) {
      // TaintedEngineOutputRerendersFromCustody: an in-place text edit inside
      // engine output does not change child identity. The edited node belongs
      // to the renderer, so the semantic truth stays in custody and the
      // paragraph re-renders from it.
      (function (element: HTMLElement) {
        actions.push({
          element: element,
          run: function () {
            removeEntryFor(state!, element);
            globalThis.__TiqianCustody!.restoreParagraph(element);
            processParagraphOf(element, state!);
          },
        });
      })(verdict.tainted[vi] as HTMLElement);
    }
    for (vi = 0; vi < verdict.stranded.length; vi += 1) {
      (function (element: HTMLElement) {
        actions.push({
          element: element,
          run: function () {
            globalThis.__TiqianContentReconcile!.stripEngineMarkupFromStrandedParagraph(element);
            processParagraphOf(element, state!);
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
    DRIVERS.startProgressiveJob(
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
    return verdict.json;
  };

  // 11. workerLayoutRequest(root, paragraph, optionsBag) -> string|null
  engine.workerLayoutRequest = function workerLayoutRequest(root: HTMLElement, paragraph: HTMLElement, optionsBag?: unknown): string | null {
    const RS = globalThis.__TiqianRootState!;
    const lifecycle = globalThis.__TiqianLifecycle!;
    return globalThis.__TiqianWorkerRequest!.workerLayoutRequestForRoot(
      RS.currentFfi() as EngineFfiFacade,
      root,
      paragraph,
      lifecycle.optionsFromJs(optionsBag as Record<string, unknown>)
    );
  };

  globalThis.__TiqianEngine = engine as TiqianEngineInstance;

  // ---------------------------------------------------------------------------
  // __TiqianEngineWorkers (9 worker-prefixed methods)
  //
  // WorkerPolledScheduling: the coordinator (coordinator.js 438-479) and
  // element.js (939-991) consume worker-prefixed names. F2a unpacking means
  // Kotlin TiqianWebWorkers attach and friends have no producer-side mapping
  // for these names; this module exposes them directly under the consumption
  // name so the coordinator contract is satisfied.
  // ---------------------------------------------------------------------------

  const workers: Partial<TiqianEngineWorkersInstance> = {};

  workers.workerAttach = function (root: HTMLElement): boolean {
    return globalThis.__TiqianProgressiveJob!.attach(root);
  };

  workers.workerDetach = function (root: HTMLElement): boolean {
    return globalThis.__TiqianProgressiveJob!.detach(root);
  };

  workers.workerHasJob = function (root: HTMLElement): boolean {
    return globalThis.__TiqianProgressiveJob!.hasJob(root);
  };

  workers.workerJobGeneration = function (root: HTMLElement): number {
    return globalThis.__TiqianProgressiveJob!.jobGeneration(root);
  };

  workers.workerRunSlice = function (controller: GrantController, minTier: number): number {
    return globalThis.__TiqianProgressiveJob!.runSlice(controller, minTier);
  };

  workers.workerPendingInTier = function (root: HTMLElement, tier: number): number {
    return globalThis.__TiqianProgressiveJob!.pendingInTier(root, tier);
  };

  workers.workerParagraphCount = function (root: HTMLElement): number {
    return globalThis.__TiqianProgressiveJob!.paragraphCount(root);
  };

  workers.workerParagraphAt = function (root: HTMLElement, index: number): HTMLElement | null {
    return globalThis.__TiqianProgressiveJob!.paragraphAt(root, index);
  };

  workers.workerSetParagraphTier = function (root: HTMLElement, index: number, tier: number): boolean {
    return globalThis.__TiqianProgressiveJob!.setParagraphTier(root, index, tier);
  };

  globalThis.__TiqianEngineWorkers = workers as TiqianEngineWorkersInstance;
})();
