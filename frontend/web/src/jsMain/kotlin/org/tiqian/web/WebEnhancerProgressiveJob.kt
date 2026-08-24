@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package org.tiqian.web

import org.tiqian.web.TiqianWeb.RootState
import org.w3c.dom.HTMLElement

private fun <T> List<T>.toJsArray(): kotlin.js.JsArray<T> {
    val result = js("[]").unsafeCast<kotlin.js.JsArray<T>>()
    for (element in this) {
        result.asDynamic().push(element)
    }
    return result
}

internal fun TiqianWeb.startProgressiveJob(
    state: RootState,
    kind: String,
    itemCount: Int,
    processItem: (Int) -> Unit,
    onItemsFinished: (() -> Unit)? = null,
    onFailure: (() -> Unit)? = null,
    stale: (() -> Boolean)? = null,
    itemTierIndex: IntArray? = null,
    paragraphsByDoc: List<HTMLElement>? = null,
) {
    val root = state.root
    root.removeAttribute(RELAYOUT_ERROR_ATTRIBUTE)
    val spec: ProgressiveJobSpecJs = js("({})").unsafeCast<ProgressiveJobSpecJs>()
    spec.root = root
    spec.kind = kind
    spec.itemCount = itemCount
    spec.processItem = processItem
    spec.onItemsFinished = onItemsFinished
    spec.onFailure = onFailure
    spec.isStale = stale
    spec.onProgress = { publishState(state, keepEmpty = true) }
    spec.onFinished = { report -> finishProgressiveJob(state, report) }
    spec.onFailed = { failure -> failProgressiveJob(state, failure) }
    spec.startedAt = dateNow()
    spec.itemTierIndex = itemTierIndex
    spec.paragraphsByDoc = paragraphsByDoc?.toJsArray()
    spec.coordinated = progressiveJobBridge().isAttached(root)
    progressiveJobBridge().startJob(spec)
}

internal fun TiqianWeb.finishProgressiveJob(state: RootState, report: ProgressiveJobReportJs) {
    publishState(state)
    dispatchProgressiveSummary(
        state = state,
        kind = report.kind,
        durationMs = dateNow() - report.startedAt,
        maxSliceMs = report.maxSliceMs,
        failed = false,
        error = null,
        stale = report.stale,
    )
}

internal fun TiqianWeb.failProgressiveJob(state: RootState, failure: ProgressiveJobFailureJs) {
    val detail = failure.detail.take(CAPABILITY_DETAIL_LIMIT)
    state.root.setAttribute(RELAYOUT_ERROR_ATTRIBUTE, detail)
    publishState(state, keepEmpty = true)
    dispatchTiqianProgressiveError(
        root = state.root,
        kind = failure.kind,
        detail = detail,
        durationMs = dateNow() - failure.startedAt,
        maxSliceMs = failure.maxSliceMs,
    )
    dispatchProgressiveSummary(
        state = state,
        kind = failure.kind,
        durationMs = dateNow() - failure.startedAt,
        maxSliceMs = failure.maxSliceMs,
        failed = true,
        error = detail,
        stale = false,
    )
}

private fun TiqianWeb.dispatchProgressiveSummary(
    state: RootState,
    kind: String,
    durationMs: Double,
    maxSliceMs: Double,
    failed: Boolean,
    error: String?,
    stale: Boolean,
) {
    val runtimeEnhancedCount = state.paragraphs.length
    val snapshotCount = observableSnapshotCount(state.root)
    if (kind == "Relayout") {
        dispatchTiqianRelayoutReady(
            root = state.root,
            enhancedCount = runtimeEnhancedCount + snapshotCount,
            runtimeEnhancedCount = runtimeEnhancedCount,
            snapshotCount = snapshotCount,
            issueCount = state.issues.length,
            durationMs = durationMs,
            maxSliceMs = maxSliceMs,
            failed = failed,
            error = error,
            stale = stale,
        )
    } else {
        dispatchTiqianReady(
            root = state.root,
            enhancedCount = runtimeEnhancedCount + snapshotCount,
            runtimeEnhancedCount = runtimeEnhancedCount,
            snapshotCount = snapshotCount,
            issueCount = state.issues.length,
            durationMs = durationMs,
            maxSliceMs = maxSliceMs,
            stale = stale,
        )
    }
}
