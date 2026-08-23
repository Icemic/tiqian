@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package org.tiqian.web

// Bridge to the JS progressive-job engine (npm/core/engine/progressive-job.js).
// The runtime bundle embeds that script via ProgressiveJobBridgeGenerated.kt;
// the dispatcher below installs it on first use and returns the installed API.

internal external interface ProgressiveJobSpecJs {
    var root: org.w3c.dom.HTMLElement
    var kind: String
    var itemCount: Int
    var processItem: (Int) -> Unit
    var onItemsFinished: (() -> Unit)?
    var onFailure: (() -> Unit)?
    var isStale: (() -> Boolean)?
    var onProgress: (() -> Unit)?
    var onFinished: (ProgressiveJobReportJs) -> Unit
    var onFailed: (ProgressiveJobFailureJs) -> Unit
    var startedAt: Double
    var itemTierIndex: IntArray?
    var paragraphsByDoc: kotlin.js.JsArray<org.w3c.dom.HTMLElement>?
    var coordinated: Boolean
}

internal external interface ProgressiveJobReportJs {
    val kind: String
    val startedAt: Double
    val maxSliceMs: Double
    val stale: Boolean
}

internal external interface ProgressiveJobFailureJs {
    val kind: String
    val detail: String
    val startedAt: Double
    val maxSliceMs: Double
}

internal external interface ProgressiveJobBridgeJs {
    fun startJob(spec: ProgressiveJobSpecJs)
    fun cancelJob(root: org.w3c.dom.HTMLElement)
    fun runSlice(controller: GrantController?, minTier: Int): Int
    fun hasJob(root: org.w3c.dom.HTMLElement): Boolean
    fun jobGeneration(root: org.w3c.dom.HTMLElement): Int
    fun jobKind(root: org.w3c.dom.HTMLElement): String?
    fun pendingInTier(root: org.w3c.dom.HTMLElement, tier: Int): Int
    fun paragraphCount(root: org.w3c.dom.HTMLElement): Int
    fun paragraphAt(root: org.w3c.dom.HTMLElement, index: Int): org.w3c.dom.HTMLElement?
    fun setParagraphTier(root: org.w3c.dom.HTMLElement, index: Int, tier: Int): Boolean
    fun attach(root: org.w3c.dom.HTMLElement): Boolean
    fun detach(root: org.w3c.dom.HTMLElement): Boolean
    fun isAttached(root: org.w3c.dom.HTMLElement): Boolean
}

@JsFun("(install) => (globalThis.__TiqianProgressiveJob || (install(), globalThis.__TiqianProgressiveJob))")
private external fun requireProgressiveJobBridgeJs(install: () -> Unit): ProgressiveJobBridgeJs

internal fun progressiveJobBridge(): ProgressiveJobBridgeJs =
    requireProgressiveJobBridgeJs { installEmbeddedProgressiveJobScript() }
