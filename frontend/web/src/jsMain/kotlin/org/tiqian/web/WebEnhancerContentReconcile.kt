package org.tiqian.web

import org.tiqian.web.TiqianWeb.RootState
import org.w3c.dom.HTMLElement

/**
 * HostContentReconcile: a live-DOM content change on an enhanced root
 * (framework re-render, textContent write, node insertion or removal) must
 * re-enter the layout pipeline. Classification, the read-only drift probe
 * and both DOM preparation helpers live in
 * npm/core/engine/content-reconcile.js, embedded via
 * WebEnhancerContentReconcileBridge.kt. This file assembles the reconcile
 * job from the verdict and keeps the engine-side actions: paragraph
 * tracking, custody restore and processParagraph.
 */
internal fun TiqianWeb.probeContentDrift(root: HTMLElement): String {
    val state = states.get(root) as? RootState
        ?: return """{"unknown":1,"drifted":0,"dead":0,"custody":0}"""
    return contentReconcileBridge().probeContentDrift(state.paragraphs.sourcesToArray())
}

internal fun TiqianWeb.reconcileContent(root: HTMLElement, tainted: Array<HTMLElement>): String {
    val state = states.get(root) as? RootState
        ?: return """{"outcome":"idle","drifted":0,"custody":0,"tainted":0,"stranded":0,"dead":0}"""
    val spec: ReconcileSpecJs = js("({})").unsafeCast<ReconcileSpecJs>()
    spec.trackedSources = state.paragraphs.sourcesToArray()
    spec.tainted = tainted
    spec.strandedCandidates = strandedSourceParagraphs(root, state).toTypedArray()
    spec.rootSelector = ROOT_SELECTOR
    val verdict = contentReconcileBridge().classifyReconcile(spec)
    // DeadTrackedParagraphDrop: innerHTML re-projection orphans the runtime
    // onto detached originals. Such entries can never render again; drop
    // them so the re-projected clones are adopted as fresh candidates.
    state.paragraphs.removeAllMatching { !it.source.isConnected }
    if (verdict.outcome == "idle") return verdict.json
    class ReconcileAction(val element: HTMLElement, val run: () -> Unit)
    val actions = mutableListOf<ReconcileAction>()
    for (element in verdict.drifted) {
        actions += ReconcileAction(element) {
            state.paragraphs.removeAllMatching { it.source === element }
            contentReconcileBridge().prepareTrackedParagraphForRelowering(element)
            processParagraphTs(element, state)
        }
    }
    for (element in verdict.custody) {
        // CustodyDriftRerendersFromCustody: a host edit inside the custody
        // fragment leaves the live paragraph matching the rendered
        // invariant, so only the custody identity check sees it. The action
        // matches the tainted path: the semantic truth lives in custody, so
        // restore hands it back to the live DOM and processParagraph
        // re-lowers the edited content.
        actions += ReconcileAction(element) {
            state.paragraphs.removeAllMatching { it.source === element }
            custodyBridge().restoreParagraph(element)
            processParagraphTs(element, state)
        }
    }
    for (element in verdict.tainted) {
        // TaintedEngineOutputRerendersFromCustody: an in-place text edit
        // inside engine output does not change child identity. The edited
        // node belongs to the renderer, so the semantic truth stays in
        // custody and the paragraph re-renders from it.
        actions += ReconcileAction(element) {
            state.paragraphs.removeAllMatching { it.source === element }
            custodyBridge().restoreParagraph(element)
            processParagraphTs(element, state)
        }
    }
    for (element in verdict.stranded) {
        actions += ReconcileAction(element) {
            contentReconcileBridge().stripEngineMarkupFromStrandedParagraph(element)
            processParagraphTs(element, state)
        }
    }
    // WidthSnapshotPerReconcileJob mirrors WidthSnapshotPerRelayoutJob: a
    // mid-job width move reports stale and element.js schedules one
    // latest-width follow-up.
    val distances = DoubleArray(actions.size) { paragraphViewportDistance(actions[it].element) }
    val itemTierIndex = Array(actions.size) { it }.apply {
        sortWith(compareBy<Int> { distances[it] }.thenBy { it })
    }.toIntArray()
    val rootWidth = elementFragmentBorderBoxInlineSize(root)
    startProgressiveJob(
        state = state,
        kind = "Relayout",
        itemCount = actions.size,
        processItem = { index -> actions[itemTierIndex[index]].run() },
        stale = {
            kotlin.math.abs(elementFragmentBorderBoxInlineSize(root) - rootWidth) >= 0.5f
        },
        itemTierIndex = itemTierIndex,
        paragraphsByDoc = actions.map { it.element },
    )
    return verdict.json
}
