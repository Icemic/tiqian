@file:OptIn(kotlin.js.ExperimentalJsExport::class)

package org.tiqian.web

import org.w3c.dom.HTMLElement

/**
 * JsExport facade for [org.tiqian.web.TiqianWeb]'s worker protocol. The npm
 * runtime loader mounts these functions on the existing `globalThis.TiqianWeb`
 * bridge. A polled frame crosses the boundary with primitives and, per grant,
 * one plain controller object carrying value-copied stop terms; no live
 * coordinator state crosses (ADR 0039).
 */
@JsExport
object TiqianWebWorkers {
    public fun attach(root: HTMLElement): Boolean = TiqianWeb.workerAttach(root)

    public fun detach(root: HTMLElement): Boolean = TiqianWeb.workerDetach(root)

    public fun hasJob(root: HTMLElement): Boolean = TiqianWeb.workerHasJob(root)

    public fun jobGeneration(root: HTMLElement): Int = TiqianWeb.workerJobGeneration(root)

    public fun runSlice(controller: GrantController?, minTier: Int): Int =
        TiqianWeb.workerRunSlice(controller, minTier)

    public fun pendingInTier(root: HTMLElement, tier: Int): Int =
        TiqianWeb.workerPendingInTier(root, tier)

    public fun paragraphCount(root: HTMLElement): Int = TiqianWeb.workerParagraphCount(root)

    public fun paragraphAt(root: HTMLElement, index: Int): HTMLElement? =
        TiqianWeb.workerParagraphAt(root, index)

    public fun setParagraphTier(root: HTMLElement, index: Int, tier: Int): Boolean =
        TiqianWeb.workerSetParagraphTier(root, index, tier)
}

/**
 * WorkerPolledScheduling: the page coordinator owns every layout slice.
 *
 * A custom element attaches its root before dispatching a progressive job.
 * The coordinator polls these functions each frame: it reads pending counts per
 * paragraph tier and the current job generation, grants one bounded slice
 * through [workerRunSlice] by passing a grant controller, and pushes
 * IntersectionObserver tier flips through [workerSetParagraphTier]. Roots
 * that never attach run their job to completion in one go.
 *
 * Arguments and return values are primitives except the grant controller,
 * one plain object per grant: recipient root, job generation, a deadline
 * already converted into the Date.now() domain, a paragraph quota, and a
 * shouldStop closure capturing only those numbers. The controller is the
 * grant itself; the coordinator's multi-root state never crosses.
 */
fun TiqianWeb.workerAttach(root: HTMLElement): Boolean {
    workerRoots.add(root)
    val job = progressiveJobs[root]
    if (job != null) job.coordinated = true
    return true
}

fun TiqianWeb.workerDetach(root: HTMLElement): Boolean {
    workerRoots.delete(root)
    val job = progressiveJobs[root]
    if (job != null && job.coordinated) {
        // RunToCompletionWithoutCoordinator: with the coordinator gone nobody
        // polls this root anymore, so a job still in flight finishes now.
        job.coordinated = false
        while (progressiveJobs[root] === job && job.nextIndex < job.itemCount) {
            runProgressiveSlice(job)
        }
    }
    return true
}

internal fun TiqianWeb.workerIsAttached(root: HTMLElement): Boolean =
    (workerRoots.has(root) as? Boolean) == true

fun TiqianWeb.workerHasJob(root: HTMLElement): Boolean =
    progressiveJobs.containsKey(root)

fun TiqianWeb.workerJobGeneration(root: HTMLElement): Int =
    progressiveJobs[root]?.generation ?: 0

/**
 * Runs one slice governed by [controller]. The grant is rejected when the
 * root has no coordinated job or when its generation no longer matches the
 * job, so a grant addressed to a job that was replaced never runs against
 * the new job. [minTier] gates items: only paragraphs whose live tier is
 * <= minTier are processed, so tier 1 (in viewport) work always drains
 * before tier 2 (near viewport) and tier 3 (far). Returns the number of
 * items committed in this slice.
 */
fun TiqianWeb.workerRunSlice(controller: GrantController?, minTier: Int): Int {
    if (controller == null) return 0
    val job = progressiveJobs[controller.root] ?: return 0
    if (!job.coordinated) return 0
    if (job.generation != controller.generation) return 0
    val admission = GrantAdmission { processed ->
        controller.shouldStop(processed)
    }
    return runProgressiveSlice(job, admission, minTier.coerceIn(1, PROGRESSIVE_TIER_COUNT))
}

fun TiqianWeb.workerPendingInTier(root: HTMLElement, tier: Int): Int {
    if (tier !in 1..PROGRESSIVE_TIER_COUNT) return 0
    return progressiveJobs[root]?.tierPending?.get(tier - 1) ?: 0
}

/**
 * Paragraph list of the running job in document order. The coordinator
 * observes these hosts with its shared IntersectionObserver and maps each
 * entry back to [workerSetParagraphTier] through this index.
 */
fun TiqianWeb.workerParagraphCount(root: HTMLElement): Int =
    progressiveJobs[root]?.paragraphsByDoc?.size ?: 0

fun TiqianWeb.workerParagraphAt(root: HTMLElement, index: Int): HTMLElement? =
    progressiveJobs[root]?.paragraphsByDoc?.getOrNull(index)

/**
 * ParagraphTierGating: the coordinator pushes a live tier flip (1 in
 * viewport, 2 near, 3 far) for a document-order paragraph index. Pending
 * counters move with the flip, so the next polled frame reorders the queue
 * without rescanning the job.
 */
fun TiqianWeb.workerSetParagraphTier(root: HTMLElement, index: Int, tier: Int): Boolean {
    if (tier !in 1..PROGRESSIVE_TIER_COUNT) return false
    val job = progressiveJobs[root] ?: return false
    val tiers = job.paragraphTiers ?: return false
    if (index !in tiers.indices) return false
    val previous = tiers[index].coerceIn(1, PROGRESSIVE_TIER_COUNT)
    if (previous == tier) return true
    tiers[index] = tier
    val pending = job.tierPending ?: return true
    val item = job.docToItem?.get(index) ?: -1
    if (item >= 0 && job.itemDone?.get(item) != true) {
        pending[previous - 1] = maxOf(0, pending[previous - 1] - 1)
        pending[tier - 1] += 1
    }
    return true
}
