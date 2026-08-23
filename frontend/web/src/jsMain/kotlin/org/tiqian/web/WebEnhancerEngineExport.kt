@file:OptIn(kotlin.js.ExperimentalJsExport::class)

package org.tiqian.web

import org.w3c.dom.HTMLElement

/**
 * JsExport facade for [org.tiqian.web.TiqianWeb]'s engine entry points. The
 * host runtime loader reads these functions straight off the module instead of
 * routing through document-level CustomEvents, so the event channel stays a
 * private detail of this module (ADR 0053). Options arrive as the opaque host
 * bag and go through [optionsFromJs] before reaching the engine.
 */
@JsExport
object TiqianEngine {
    public fun enhance(root: HTMLElement, options: EnhanceOptionsJs?): Int =
        TiqianWeb.enhance(root, TiqianWeb.optionsFromJs(options))

    public fun enhanceProgressively(root: HTMLElement, options: EnhanceOptionsJs?) {
        TiqianWeb.enhanceProgressively(root, TiqianWeb.optionsFromJs(options))
    }

    public fun enhanceAll(options: EnhanceOptionsJs?): Int =
        TiqianWeb.enhanceAll(TiqianWeb.optionsFromJs(options))

    public fun destroy(root: HTMLElement) {
        TiqianWeb.destroy(root)
    }

    public fun detach(root: HTMLElement) {
        TiqianWeb.detach(root)
    }

    public fun relayout(root: HTMLElement) {
        TiqianWeb.relayout(root)
    }

    public fun refresh(root: HTMLElement, progressively: Boolean) {
        TiqianWeb.refresh(root, progressively)
    }

    public fun cancelLayoutWork(root: HTMLElement) {
        TiqianWeb.cancelProgressiveJob(root)
    }

    public fun probeContentDrift(root: HTMLElement): String = TiqianWeb.probeContentDrift(root)

    public fun reconcileContent(root: HTMLElement, tainted: Array<HTMLElement>): String =
        TiqianWeb.reconcileContent(root, tainted)

    public fun workerLayoutRequest(
        root: HTMLElement,
        paragraph: HTMLElement,
        options: EnhanceOptionsJs?,
    ): String? = TiqianWeb.workerLayoutRequest(root, paragraph, TiqianWeb.optionsFromJs(options))
}
