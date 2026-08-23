@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package org.tiqian.web

import org.w3c.dom.HTMLElement

// Bridge to the content reconcile script (npm/core/engine/content-reconcile.js).
// The runtime bundle embeds that script via ContentReconcileBridgeGenerated.kt;
// the accessor below installs it on first use and returns the installed API.

internal external interface ReconcileSpecJs {
    var trackedSources: Array<HTMLElement>
    var tainted: Array<HTMLElement>
    var strandedCandidates: Array<HTMLElement>
    var rootSelector: String
}

internal external interface ReconcileVerdictJs {
    val outcome: String
    val drifted: Array<HTMLElement>
    val custody: Array<HTMLElement>
    val tainted: Array<HTMLElement>
    val stranded: Array<HTMLElement>
    val dead: Int
    val json: String
}

internal external interface ContentReconcileBridgeJs {
    fun probeContentDrift(trackedSources: Array<HTMLElement>): String
    fun classifyReconcile(spec: ReconcileSpecJs): ReconcileVerdictJs
    fun prepareTrackedParagraphForRelowering(element: HTMLElement)
    fun stripEngineMarkupFromStrandedParagraph(element: HTMLElement)
}

@JsFun("(install) => (globalThis.__TiqianContentReconcile || (install(), globalThis.__TiqianContentReconcile))")
private external fun requireContentReconcileBridgeJs(install: () -> Unit): ContentReconcileBridgeJs

internal fun contentReconcileBridge(): ContentReconcileBridgeJs =
    requireContentReconcileBridgeJs { installEmbeddedContentReconcileScript() }
