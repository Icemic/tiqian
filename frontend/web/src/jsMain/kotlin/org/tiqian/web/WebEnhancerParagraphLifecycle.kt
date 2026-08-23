package org.tiqian.web

import org.tiqian.core.DEFAULT_EMPHASIS_DOT_GAP_EM
import org.tiqian.shaping.web.WebCjkDashCapability
import org.tiqian.web.TiqianWeb.CapabilityIssue
import org.tiqian.web.TiqianWeb.EnhanceOptions
import org.tiqian.web.TiqianWeb.EnhancedParagraph
import org.tiqian.web.TiqianWeb.ExactFontSessionCapability
import org.tiqian.web.TiqianWeb.FontFamilyOptions
import org.tiqian.web.TiqianWeb.ProgressiveJob
import org.tiqian.web.TiqianWeb.ProgressiveJobKind
import org.tiqian.web.TiqianWeb.SourceInlineSize
import org.w3c.dom.HTMLElement

internal fun TiqianWeb.startProgressiveJob(job: ProgressiveJob) {
    cancelProgressiveJob(job.state.root)
    job.generation = ++progressiveJobGeneration
    job.state.root.removeAttribute(RELAYOUT_ERROR_ATTRIBUTE)
    installParagraphTierTracking(job)
    progressiveJobs[job.state.root] = job
    if (job.itemCount == 0) {
        try {
            job.onItemsFinished?.invoke()
            finishProgressiveJob(job)
        } catch (error: Throwable) {
            job.onFailure?.invoke()
            failProgressiveJob(job, error)
        }
    } else if (job.coordinated) {
        // WorkerPolledScheduling: the coordinator grants every slice of an
        // attached root. The job waits here; the first grant may land in the
        // same frame as the dispatch task and stays inside the shared frame
        // budget.
    } else {
        // RunToCompletionWithoutCoordinator: without an attached coordinator
        // nobody polls this root, so the job runs to completion right here.
        while (progressiveJobs[job.state.root] === job && job.nextIndex < job.itemCount) {
            runProgressiveSlice(job)
        }
    }
}

internal fun TiqianWeb.installParagraphTierTracking(job: ProgressiveJob) {
    val itemTierIndex = job.itemTierIndex ?: return
    val count = itemTierIndex.size
    job.paragraphTiers = IntArray(count) { PROGRESSIVE_TIER_IN_VIEWPORT }
    job.tierPending = IntArray(PROGRESSIVE_TIER_COUNT).also { it[0] = count }
    job.itemDone = BooleanArray(job.itemCount)
    job.docToItem = IntArray(count) { -1 }
    itemTierIndex.forEachIndexed { item, doc -> job.docToItem!![doc] = item }
}

internal fun TiqianWeb.markProgressiveItemDone(job: ProgressiveJob, item: Int) {
    val done = job.itemDone ?: return
    if (done[item]) return
    done[item] = true
    val pending = job.tierPending ?: return
    val tier = job.paragraphTiers!![job.itemTierIndex!![item]].coerceIn(1, PROGRESSIVE_TIER_COUNT)
    pending[tier - 1] = maxOf(0, pending[tier - 1] - 1)
}

internal fun TiqianWeb.skipRemainingProgressiveItems(job: ProgressiveJob) {
    val done = job.itemDone
    if (done == null) {
        job.nextIndex = job.itemCount
        return
    }
    for (item in job.nextIndex until job.itemCount) {
        markProgressiveItemDone(job, item)
    }
    job.nextIndex = job.itemCount
}

internal fun TiqianWeb.cancelProgressiveJob(root: HTMLElement) {
    progressiveJobs.remove(root)
}

internal fun TiqianWeb.runProgressiveSlice(
    job: ProgressiveJob,
    admission: GrantAdmission? = null,
    minTier: Int = PROGRESSIVE_TIER_COUNT,
): Int {
    if (progressiveJobs[job.state.root] !== job) return 0
    // The admission question bounds one grant: a coordinated slice receives
    // the coordinator's controller, a standalone slice builds its own (see
    // standaloneGrantAdmission). Either way the loop body below holds no
    // clock, no policy, and no identity; it asks after each paragraph.
    val shouldStop = admission ?: standaloneGrantAdmission()
    val sliceStartedAt = dateNow()
    var processedInSlice = 0
    // StaleMeasureGuardPerSlice: a relayout job prepares every paragraph
    // against the width snapshot taken when the job started, per
    // WidthSnapshotPerRelayoutJob. ADR 0039 forbids committing a result
    // even one grid cell behind the live width. So when the host width has
    // drifted since the snapshot, the remaining items in this job are
    // skipped and the finish event reports the job as stale; element.js
    // then schedules one follow-up job at the latest width. The guard runs
    // once at the head of each slice, before the slice's DOM writes, and
    // costs one layout read per slice.
    if (job.stale?.invoke() == true) {
        skipRemainingProgressiveItems(job)
    }
    val done = job.itemDone
    val tiers = job.paragraphTiers
    val itemTierIndex = job.itemTierIndex
    val gate = if (job.coordinated) minTier.coerceIn(1, PROGRESSIVE_TIER_COUNT) else PROGRESSIVE_TIER_COUNT
    try {
        val sliceStartIndex = job.nextIndex
        var index = job.nextIndex
        while (index < job.itemCount) {
            if (done != null) {
                if (done[index]) {
                    index += 1
                    continue
                }
                if (tiers != null && itemTierIndex != null &&
                    tiers[itemTierIndex[index]] > gate
                ) {
                    index += 1
                    continue
                }
            }
            job.processItem(index)
            if (done != null) markProgressiveItemDone(job, index)
            processedInSlice += 1
            index += 1
            // At least one paragraph per slice: the question runs after an
            // item, so a grant always commits before it can be told to stop.
            if (shouldStop.shouldStop(processedInSlice)) {
                break
            }
        }
        // With done tracking, nextIndex only has to lead the first not-done
        // item; keeping it tight shortens the next slice's scan. A gated item
        // is not done, so nextIndex parks on it and the next slice rechecks
        // its tier. Jobs without done tracking advance monotonically; the
        // skip loop below does not run for them.
        //
        // TierGatedItemKeepsJobOpen: the tier gate advances the cursor past
        // items it declined to run, so a slice that walks to itemCount
        // without breaking would otherwise finish the job over those items.
        // They were never committed, yet the ready event would report a
        // complete non-stale relayout and no follow-up job would ever come.
        // Park on the first not-done item by scanning back from where the
        // slice started, not forward from where the cursor stopped.
        job.nextIndex = index
        if (done != null) {
            var parked = sliceStartIndex
            while (parked < job.itemCount && done[parked]) parked += 1
            job.nextIndex = parked
        }
    } catch (error: Throwable) {
        job.onFailure?.invoke()
        failProgressiveJob(job, error)
        return processedInSlice
    }
    val sliceDuration = dateNow() - sliceStartedAt
    job.maxSliceDuration = maxOf(job.maxSliceDuration, sliceDuration)
    publishState(job.state, keepEmpty = true)
    if (job.nextIndex >= job.itemCount) {
        try {
            job.onItemsFinished?.invoke()
            job.maxSliceDuration = maxOf(
                job.maxSliceDuration,
                dateNow() - sliceStartedAt,
            )
            finishProgressiveJob(job)
        } catch (error: Throwable) {
            job.onFailure?.invoke()
            failProgressiveJob(job, error)
        }
    }
    return processedInSlice
}

internal fun TiqianWeb.finishProgressiveJob(job: ProgressiveJob) {
    if (progressiveJobs.remove(job.state.root) !== job) return
    job.state.root.removeAttribute(RELAYOUT_ERROR_ATTRIBUTE)
    publishState(job.state)
    val runtimeEnhancedCount = job.state.paragraphs.size
    val snapshotCount = observableSnapshotCount(job.state.root)
    if (job.kind == ProgressiveJobKind.Relayout) {
        dispatchTiqianRelayoutReady(
            root = job.state.root,
            enhancedCount = runtimeEnhancedCount + snapshotCount,
            runtimeEnhancedCount = runtimeEnhancedCount,
            snapshotCount = snapshotCount,
            issueCount = job.state.issues.size,
            durationMs = dateNow() - job.startedAt,
            maxSliceMs = job.maxSliceDuration,
            failed = false,
            error = null,
            stale = job.commitSkipped || job.stale?.invoke() == true,
        )
    } else {
        dispatchTiqianReady(
            root = job.state.root,
            enhancedCount = runtimeEnhancedCount + snapshotCount,
            runtimeEnhancedCount = runtimeEnhancedCount,
            snapshotCount = snapshotCount,
            issueCount = job.state.issues.size,
            durationMs = dateNow() - job.startedAt,
            maxSliceMs = job.maxSliceDuration,
            stale = job.commitSkipped || job.stale?.invoke() == true,
        )
    }
}

internal fun TiqianWeb.failProgressiveJob(job: ProgressiveJob, error: Throwable) {
    if (progressiveJobs.remove(job.state.root) !== job) return
    val detail = (error.message ?: error.toString()).take(CAPABILITY_DETAIL_LIMIT)
    job.state.root.setAttribute(RELAYOUT_ERROR_ATTRIBUTE, detail)
    publishState(job.state, keepEmpty = true)
    dispatchTiqianProgressiveError(
        root = job.state.root,
        kind = job.kind.name,
        detail = detail,
        durationMs = dateNow() - job.startedAt,
        maxSliceMs = job.maxSliceDuration,
    )
    val runtimeEnhancedCount = job.state.paragraphs.size
    val snapshotCount = observableSnapshotCount(job.state.root)
    if (job.kind == ProgressiveJobKind.Relayout) {
        dispatchTiqianRelayoutReady(
            root = job.state.root,
            enhancedCount = runtimeEnhancedCount + snapshotCount,
            runtimeEnhancedCount = runtimeEnhancedCount,
            snapshotCount = snapshotCount,
            issueCount = job.state.issues.size,
            durationMs = dateNow() - job.startedAt,
            maxSliceMs = job.maxSliceDuration,
            failed = true,
            error = detail,
            stale = false,
        )
    } else {
        dispatchTiqianReady(
            root = job.state.root,
            enhancedCount = runtimeEnhancedCount + snapshotCount,
            runtimeEnhancedCount = runtimeEnhancedCount,
            snapshotCount = snapshotCount,
            issueCount = job.state.issues.size,
            durationMs = dateNow() - job.startedAt,
            maxSliceMs = job.maxSliceDuration,
            stale = false,
        )
    }
}



internal fun TiqianWeb.reportIssue(issue: CapabilityIssue) {
    if (!issue.markerCaptured) {
        issue.originalNameAttribute = issue.element.getAttribute("data-tiqian-capability-issue")
        issue.originalDetailAttribute = issue.element.getAttribute("data-tiqian-capability-detail")
        issue.markerCaptured = true
    }
    issue.element.setAttribute("data-tiqian-capability-issue", issue.name)
    issue.element.setAttribute("data-tiqian-capability-detail", issue.detail.take(CAPABILITY_DETAIL_LIMIT))
    // PendingCapabilityIsObservableNotTerminal: the semantic paragraph is
    // intentionally kept native while the asynchronous dash-face probe is
    // in flight. Keep the DOM marker for the targeted retry, but reserve a
    // console warning for the retry's final unavailable/mismatch result.
    if (issue.reportToConsole) {
        consoleWarn("TiqianWeb skipped paragraph: ${issue.name} (${issue.detail})")
    }
}

internal fun TiqianWeb.optionsFromJs(options: EnhanceOptionsJs?): EnhanceOptions {
    val cjk = optionString(options, "cjkFontFamily")
    val latin = optionString(options, "latinFontFamily")
    val monospace = optionString(options, "monospaceFontFamily")
    val cjkSerif = optionString(options, "cjkSerifFontFamily")
    val latinSerif = optionString(options, "latinSerifFontFamily")
    val fontSize = optionFloat(options, "fontSize")
    val lineHeight = optionFloat(options, "lineHeight")
    val firstLineIndent = optionFloat(options, "firstLineIndentIc") ?: 0f
    val emphasisDotGapEm = optionFloat(options, "emphasisDotGapEm")
        ?: DEFAULT_EMPHASIS_DOT_GAP_EM
    val strongAsEmphasisMarks = optionBoolean(options, "strongAsEmphasisMarks") ?: false
    val paragraphSelector = optionString(options, "paragraphSelector") ?: DEFAULT_PARAGRAPH_SELECTOR
    val requireExactLayoutWorker = optionBoolean(options, "requireExactLayoutWorker") ?: false
    val dashCapabilityObject = optionObject(options, "cjkDashCapability")
    val dashCapability = dashCapabilityObject?.let { capability ->
        WebCjkDashCapability(
            status = optionString(capability, "status") ?: "unavailable",
            detail = optionString(capability, "detail"),
        )
    }
    val exactFontSessionObject = optionObject(options, "exactFontSession")
    val exactFontSession = exactFontSessionObject?.let { capability ->
        ExactFontSessionCapability(
            status = optionString(capability, "status") ?: "unavailable",
            sessionId = optionString(capability, "sessionId"),
            detail = optionString(capability, "detail"),
        )
    }
    return EnhanceOptions(
        fontFamilies = FontFamilyOptions(cjk, latin, monospace, cjkSerif, latinSerif),
        fontSize = fontSize,
        lineHeight = lineHeight,
        firstLineIndentIc = firstLineIndent,
        emphasisDotGapEm = emphasisDotGapEm,
        strongAsEmphasisMarks = strongAsEmphasisMarks,
        paragraphSelector = paragraphSelector,
        cjkDashCapability = dashCapability,
        exactFontSession = exactFontSession,
        requireExactLayoutWorker = requireExactLayoutWorker,
    )
}

internal fun TiqianWeb.optionFloat(options: EnhanceOptionsJs?, name: String): Float? {
    val value = optionNumber(options, name)
    return if (value.isFinite()) value.toFloat() else null
}

internal fun TiqianWeb.captureSourceInlineSize(paragraph: HTMLElement): SourceInlineSize =
    SourceInlineSize(
        borderBoxWidth = elementFragmentBorderBoxInlineSize(paragraph),
        contentBoxWidth = elementContentWidth(paragraph),
        borderBoxSizing =
            computedStyle(paragraph, "box-sizing").trim().lowercase() == "border-box",
    )

internal fun TiqianWeb.applyConfiguredHostFontSize(paragraph: HTMLElement, fontSize: Float?): String? {
    if (fontSize == null) return null
    paragraph.style.setProperty("font-size", "${fontSize}px", "important")
    return paragraph.style.getPropertyValue("font-size")
}

internal fun TiqianWeb.responsiveSourceMeasure(paragraph: HTMLElement, configuredFontSize: Float?): Float {
    if (configuredFontSize == null) {
        val computedFontSize = parseCssPx(computedStyle(paragraph, "font-size"))
            ?: DEFAULT_FONT_SIZE
        return effectiveLineMeasure(sourceParagraphWidth(paragraph), computedFontSize)
    }
    val originalStyle = paragraph.getAttribute("style")
    paragraph.style.setProperty("font-size", "${configuredFontSize}px", "important")
    return try {
        effectiveLineMeasure(sourceParagraphWidth(paragraph), configuredFontSize)
    } finally {
        if (originalStyle == null) {
            paragraph.removeAttribute("style")
        } else {
            paragraph.setAttribute("style", originalStyle)
        }
    }
}

internal fun TiqianWeb.stabilizeContentSizedItemInlineSize(
    paragraph: HTMLElement,
    source: SourceInlineSize,
): String? {
    val empty = captureSourceInlineSize(paragraph)
    val sourceUsedInlineSize = if (source.borderBoxSizing) {
        source.borderBoxWidth
    } else {
        source.contentBoxWidth
    }
    val emptyUsedInlineSize = if (source.borderBoxSizing) {
        empty.borderBoxWidth
    } else {
        empty.contentBoxWidth
    }
    // SourceMeasureBeforeCustodyTransfer: flex/grid items and descendants
    // of shrink-to-fit ancestors can derive their used inline size from the
    // semantic children that Tiqian moves into source custody. Detect that
    // real dependency from the before/after used size rather than guessing
    // a finite set of parent display modes. Ordinary blocks keep their host
    // auto sizing; only a custody-induced width change is stabilized.
    if (
        !sourceUsedInlineSize.isFinite() || sourceUsedInlineSize <= 0.0 ||
        !emptyUsedInlineSize.isFinite() ||
        kotlin.math.abs(sourceUsedInlineSize - emptyUsedInlineSize) < 0.5
    ) return null
    val usedInlineSize = sourceUsedInlineSize
    if (!usedInlineSize.isFinite() || usedInlineSize <= 0.0) return null
    val serialized = "${usedInlineSize}px"
    paragraph.style.setProperty("inline-size", serialized, "important")
    paragraph.setAttribute(HOST_INLINE_SIZE_ATTRIBUTE, "true")
    return serialized
}

internal fun TiqianWeb.clearIssue(issue: CapabilityIssue) {
    if (!issue.markerCaptured) return
    restoreAttribute(issue.element, "data-tiqian-capability-issue", issue.originalNameAttribute)
    restoreAttribute(issue.element, "data-tiqian-capability-detail", issue.originalDetailAttribute)
    issue.markerCaptured = false
}

internal fun TiqianWeb.restoreAttribute(element: HTMLElement, name: String, value: String?) {
    if (value == null) {
        element.removeAttribute(name)
    } else {
        element.setAttribute(name, value)
    }
}
