// Engine entry (TsHost runtime port, Slice 6). Constructs the TypeScript host
// engine entry object that replaces Kotlin @JsExport object TiqianEngine
// (WebEnhancerEngineExport.kt) and TiqianWebWorkers
// (WebEnhancerWorkerProtocol.kt), and moves the Kotlin
// WebEnhancerContentReconcile.kt reconcile orchestration into TS.
//
// Stateful module: createEngineEntry(rawDom, copyInstaller, rootState,
// layoutJobPool) receives the four stateful collaborators from the
// composition root and wires them into the 11-method engine facade and the
// 9-method worker facade. The engine bootstrap (ts-runtime) constructs one
import { preparedDomRendererModule } from "./loaders/runtime-loader.js";
import type { GrantController } from "./coordination/coordination-service.js";
import type { RootState, RootStateApi } from "./root-state.js";
import type { EnhanceOptions } from "./lifecycle.js";
import { clearIssue, optionsFromJs } from "./lifecycle.js";
import type { ReconcileSpec } from "./content-reconcile.js";
import {
  classifyReconcile,
  prepareTrackedParagraphForRelowering,
  probeContentDrift,
  stripEngineMarkupFromStrandedParagraph,
} from "./content-reconcile.js";
import type { RawDomApi } from "./raw-dom.js";
import type { CopyInstaller } from "../utils/copy.js";
import type { LayoutJobPool } from "./layout-job-pool.js";
import {
  enhanceProgressively,
  enhanceProgressivelyFromCanonical,
  rejectMissingSharedRuntimeStyles,
  relayout,
  startLayoutJob,
} from "./progressive-drivers.js";
import { processParagraph } from "./process-paragraph.js";
import { workerLayoutRequestForRoot } from "./worker-request.js";

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

export interface EngineEntryHandle {
  engine: TiqianEngineInstance;
  workers: TiqianEngineWorkersInstance;
}

export function createEngineEntry(
  rawDom: RawDomApi,
  copyInstaller: CopyInstaller,
  rootState: RootStateApi,
  layoutJobPool: LayoutJobPool,
): EngineEntryHandle {
  const RS = rootState;
  const PJ = layoutJobPool;

  // ---------------------------------------------------------------------------
  // Internal helpers (from WebEnhancerSupport.kt @JsFun bodies)
  // ---------------------------------------------------------------------------

  function ensureCopyHandler(): void {
    if (globalThis.document) copyInstaller.install(globalThis.document);
  }

  function releasePreparedRootDomStyles(root: HTMLElement): boolean {
    const r = preparedDomRendererModule();
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
  // live fragment must use elementContentWidth from the responsive-measure.js
  // module instead.
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
    processParagraph(
      rawDom,
      RS.processParagraphArgument(state, element)
    );
  }

  // ---------------------------------------------------------------------------
  // Engine facade (11 methods)
  // ---------------------------------------------------------------------------

  const engine: Partial<TiqianEngineInstance> = {};

  // 1. enhance(root, optionsBag) -> number
  // Synchronous one-shot enhance. Internal third param fromCanonical controls
  // the root-state creation path; public entry passes false.
  engine.enhance = function enhance(root: HTMLElement, optionsBag?: unknown, fromCanonical?: boolean): number {
    ensureCopyHandler();
    engine.destroy!(root);
    const state = fromCanonical
      ? RS.createRootStateFromCanonical(root, optionsBag as EnhanceOptions)
      : RS.createRootState(root, optionsBag as Record<string, unknown>);
    const candidates = RS.paragraphCandidates(root, state.options.paragraphSelector);
    if (rejectMissingSharedRuntimeStyles(rootState, state, candidates)) return 0;
    for (let i = 0; i < candidates.length; i += 1) {
      processParagraph(
        rawDom,
        RS.processParagraphArgument(state, candidates[i])
      );
    }
    RS.publishState(state);
    return state.paragraphs.length;
  };

  // 2. enhanceProgressively(root, optionsBag)
  // The copy handler install and destroy run inside the drivers entry, so
  // relayout restarts that enter the drivers directly destroy too.
  engine.enhanceProgressively = function (root: HTMLElement, optionsBag?: unknown): void {
    enhanceProgressively(rootState, engine as TiqianEngineInstance, copyInstaller, layoutJobPool, rawDom, root, optionsBag as Record<string, unknown> | null);
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
    PJ.cancelJob(root);
    const state = RS.getState(root);
    RS.deleteState(root);
    if (state != null) {
      let j: number;
      for (j = 0; j < state.paragraphs.length; j += 1) {
        rawDom.restoreParagraph(state.paragraphs[j].source);
      }
      for (j = 0; j < state.issues.length; j += 1) {
        clearIssue(state.issues[j]);
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
    root.removeAttribute("data-tiqian-snapshot-layout-fallback");
  };

  // 5. detach(root)
  // DetachedRootWeakOwnership: cancel job and release styles; weak table state
  // stays for reconnection on the same node.
  engine.detach = function detach(root: HTMLElement): void {
    PJ.cancelJob(root);
    releasePreparedRootDomStyles(root);
  };

  // 6. relayout(root) -- delegates to drivers.
  engine.relayout = function (root: HTMLElement): void {
    relayout(rootState, engine as TiqianEngineInstance, copyInstaller, layoutJobPool, rawDom, root);
  };

  // 7. refresh(root, progressively)
  engine.refresh = function refresh(root: HTMLElement, progressively?: boolean): void {
    const state = RS.getState(root);
    if (!state) return;
    if (progressively) {
      enhanceProgressivelyFromCanonical(
        rootState,
        engine as TiqianEngineInstance,
        copyInstaller,
        layoutJobPool,
        rawDom,
        root,
        state.options
      );
    } else {
      engine.enhance!(root, state.options, true);
    }
  };

  // 8. cancelLayoutWork(root)
  engine.cancelLayoutWork = function cancelLayoutWork(root: HTMLElement): void {
    PJ.cancelJob(root);
  };

  // 9. probeContentDrift(root) -> string
  engine.probeContentDrift = function (root: HTMLElement): string {
    const state = RS.getState(root);
    if (!state) return '{"unknown":1,"drifted":0,"dead":0,"rawDom":0}';
    return probeContentDrift(rawDom, sourcesOf(state));
  };

  // 10. reconcileContent(root, tainted) -> string
  // Aligns WebEnhancerContentReconcile.kt 22-95.
  engine.reconcileContent = function reconcileContent(root: HTMLElement, tainted: HTMLElement[]): string {
    const state = RS.getState(root);
    if (!state) {
      return '{"outcome":"idle","drifted":0,"rawDom":0,"tainted":0,"stranded":0,"dead":0}';
    }
    const spec: ReconcileSpec = {
      trackedSources: sourcesOf(state),
      tainted: tainted,
      strandedCandidates: RS.strandedSourceParagraphs(root, state),
      rootSelector: "tiqian-prose, [data-tiqian-root]",
    };
    const verdict = classifyReconcile(rawDom, spec);

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
            prepareTrackedParagraphForRelowering(rawDom, element);
            processParagraphOf(element, state!);
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
            removeEntryFor(state!, element);
            rawDom.restoreParagraph(element);
            processParagraphOf(element, state!);
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
            removeEntryFor(state!, element);
            rawDom.restoreParagraph(element);
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
            stripEngineMarkupFromStrandedParagraph(rawDom, element);
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
    return verdict.json;
  };

  // 11. workerLayoutRequest(root, paragraph, optionsBag) -> string|null
  engine.workerLayoutRequest = function workerLayoutRequest(root: HTMLElement, paragraph: HTMLElement, optionsBag?: unknown): string | null {
    return workerLayoutRequestForRoot(
      root,
      paragraph,
      optionsFromJs(optionsBag as Record<string, unknown>)
    );
  };

  // ---------------------------------------------------------------------------
  // Worker facade (9 worker-prefixed methods)
  //
  // WorkerPolledScheduling: the coordination service (coordination-service.js
  // 438-479) and element.js (939-991) consume worker-prefixed names. F2a
  // unpacking means Kotlin TiqianWebWorkers attach and friends have no
  // producer-side mapping for these names; this module exposes them directly
  // under the consumption name so the coordination service contract is
  // satisfied.
  // ---------------------------------------------------------------------------

  const workers: Partial<TiqianEngineWorkersInstance> = {};

  workers.workerAttach = function (root: HTMLElement): boolean {
    return PJ.attach(root);
  };

  workers.workerDetach = function (root: HTMLElement): boolean {
    return PJ.detach(root);
  };

  workers.workerHasJob = function (root: HTMLElement): boolean {
    return PJ.hasJob(root);
  };

  workers.workerJobGeneration = function (root: HTMLElement): number {
    return PJ.jobGeneration(root);
  };

  workers.workerRunSlice = function (controller: GrantController, minTier: number): number {
    return PJ.runSlice(controller, minTier);
  };

  workers.workerPendingInTier = function (root: HTMLElement, tier: number): number {
    return PJ.pendingInTier(root, tier);
  };

  workers.workerParagraphCount = function (root: HTMLElement): number {
    return PJ.paragraphCount(root);
  };

  workers.workerParagraphAt = function (root: HTMLElement, index: number): HTMLElement | null {
    return PJ.paragraphAt(root, index);
  };

  workers.workerSetParagraphTier = function (root: HTMLElement, index: number, tier: number): boolean {
    return PJ.setParagraphTier(root, index, tier);
  };

  return {
    engine: engine as TiqianEngineInstance,
    workers: workers as TiqianEngineWorkersInstance,
  };
}