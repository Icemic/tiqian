@file:OptIn(kotlin.js.ExperimentalJsExport::class)

package org.tiqian.web

import org.w3c.dom.HTMLElement

/**
 * JsExport facade for [org.tiqian.web.TiqianWeb]'s worker protocol. The npm
 * runtime loader mounts these functions on the existing `globalThis.TiqianWeb`
 * bridge. A polled frame crosses the boundary with primitives and, per grant,
 * one plain controller object carrying value-copied stop terms; no live
 * coordinator state crosses (ADR 0039).
 *
 * WorkerPolledScheduling: the page coordinator owns every layout slice.
 *
 * A custom element attaches its root before dispatching a progressive job.
 * The coordinator polls these functions each frame: it reads pending counts per
 * paragraph tier and the current job generation, grants one bounded slice
 * through [runSlice] by passing a grant controller, and pushes
 * IntersectionObserver tier flips through [setParagraphTier]. Roots
 * that never attach run their job to completion in one go.
 *
 * Arguments and return values are primitives except the grant controller,
 * one plain object per grant: recipient root, job generation, a deadline
 * already converted into the Date.now() domain, a paragraph quota, and a
 * shouldStop closure capturing only those numbers. The controller is the
 * grant itself; the coordinator's multi-root state never crosses.
 */
@JsExport
object TiqianWebWorkers {
    public fun attach(root: HTMLElement): Boolean = progressiveJobBridge().attach(root)

    public fun detach(root: HTMLElement): Boolean = progressiveJobBridge().detach(root)

    public fun hasJob(root: HTMLElement): Boolean = progressiveJobBridge().hasJob(root)

    public fun jobGeneration(root: HTMLElement): Int = progressiveJobBridge().jobGeneration(root)

    public fun runSlice(controller: GrantController?, minTier: Int): Int =
        progressiveJobBridge().runSlice(controller, minTier.coerceIn(1, 3))

    public fun pendingInTier(root: HTMLElement, tier: Int): Int =
        progressiveJobBridge().pendingInTier(root, tier)

    public fun paragraphCount(root: HTMLElement): Int = progressiveJobBridge().paragraphCount(root)

    public fun paragraphAt(root: HTMLElement, index: Int): HTMLElement? =
        progressiveJobBridge().paragraphAt(root, index)

    public fun setParagraphTier(root: HTMLElement, index: Int, tier: Int): Boolean =
        progressiveJobBridge().setParagraphTier(root, index, tier)
}
