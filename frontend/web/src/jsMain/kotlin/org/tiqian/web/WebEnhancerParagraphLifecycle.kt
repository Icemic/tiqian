@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package org.tiqian.web

import kotlin.js.JsAny
import kotlinx.browser.document
import org.tiqian.core.DEFAULT_EMPHASIS_DOT_GAP_EM
import org.tiqian.shaping.web.WebCjkDashCapability
import org.tiqian.web.TiqianWeb.CapabilityIssue
import org.tiqian.web.TiqianWeb.EnhanceOptions
import org.tiqian.web.TiqianWeb.EnhancedParagraph
import org.tiqian.web.TiqianWeb.ExactFontSessionCapability
import org.tiqian.web.TiqianWeb.FontFamilyOptions
import org.tiqian.web.TiqianWeb.LiveParagraphSnapshot
import org.tiqian.web.TiqianWeb.ProgressiveJob
import org.tiqian.web.TiqianWeb.ProgressiveJobKind
import org.tiqian.web.TiqianWeb.SourceInlineSize
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement

internal fun TiqianWeb.scheduleProgressiveSlice(job: ProgressiveJob) {
    val idle = job.shouldScheduleIdle(job.nextIndex)
    job.scheduledSliceToken = scheduleProgressiveCallback(
        callback = { runProgressiveSlice(job, idle) },
        idle = idle,
    )
}

internal fun TiqianWeb.startProgressiveJob(job: ProgressiveJob) {
    cancelProgressiveJob(job.state.root)
    job.state.root.removeAttribute(RELAYOUT_ERROR_ATTRIBUTE)
    progressiveJobs[job.state.root] = job
    if (job.itemCount == 0) {
        try {
            job.onItemsFinished?.invoke()
            finishProgressiveJob(job)
        } catch (error: Throwable) {
            job.onFailure?.invoke()
            failProgressiveJob(job, error)
        }
    } else if (job.kind == ProgressiveJobKind.Relayout) {
        // SynchronousForegroundRelayoutSlice: when WidthIndependentAnnotationCache
        // is active, relayout is a pure arithmetic lookahead break. Execute the
        // foreground slice synchronously in the same frame so the viewport
        // updates with zero frame delay.
        runProgressiveSlice(job, idleSlice = false)
    } else {
        scheduleProgressiveSlice(job)
    }
}

internal fun TiqianWeb.cancelProgressiveJob(root: HTMLElement) {
    progressiveJobs.remove(root)?.scheduledSliceToken?.let(::cancelProgressiveCallback)
}

internal fun TiqianWeb.runProgressiveSlice(job: ProgressiveJob, idleSlice: Boolean) {
    if (progressiveJobs[job.state.root] !== job) return
    job.scheduledSliceToken = null
    val sliceStartedAt = performanceNow()
    var processedInSlice = 0
    val budgetMs = job.state.options.sliceBudgetMs ?: MAX_PROGRESSIVE_SLICE_MS
    try {
        do {
            job.processItem(job.nextIndex)
            job.nextIndex += 1
            processedInSlice += 1
        } while (
            job.nextIndex < job.itemCount &&
            processedInSlice < MAX_PROGRESSIVE_ITEMS_PER_SLICE &&
            performanceNow() - sliceStartedAt < budgetMs &&
            (!idleSlice || processedInSlice < MAX_PROGRESSIVE_IDLE_ITEMS_PER_SLICE) &&
            !job.shouldScheduleIdle(job.nextIndex) &&
            !progressiveInputIsPending()
        )
    } catch (error: Throwable) {
        job.onFailure?.invoke()
        failProgressiveJob(job, error)
        return
    }
    val sliceDuration = performanceNow() - sliceStartedAt
    job.maxSliceDuration = maxOf(job.maxSliceDuration, sliceDuration)
    publishState(job.state, keepEmpty = true)
    if (job.nextIndex >= job.itemCount) {
        try {
            job.onItemsFinished?.invoke()
            job.maxSliceDuration = maxOf(
                job.maxSliceDuration,
                performanceNow() - sliceStartedAt,
            )
            finishProgressiveJob(job)
        } catch (error: Throwable) {
            job.onFailure?.invoke()
            failProgressiveJob(job, error)
        }
    } else {
        scheduleProgressiveSlice(job)
    }
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
            durationMs = performanceNow() - job.startedAt,
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
            durationMs = performanceNow() - job.startedAt,
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
        durationMs = performanceNow() - job.startedAt,
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
            durationMs = performanceNow() - job.startedAt,
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
            durationMs = performanceNow() - job.startedAt,
            maxSliceMs = job.maxSliceDuration,
            stale = false,
        )
    }
}

internal fun TiqianWeb.captureLiveParagraph(paragraph: EnhancedParagraph): LiveParagraphSnapshot {
    val content = document.createDocumentFragment()
    val snapshot = LiveParagraphSnapshot(
        paragraph = paragraph,
        content = content,
        renderedAttribute = paragraph.source.getAttribute("data-tq-rendered"),
        preparedFlowAttribute = paragraph.source.getAttribute("data-tq-canonical-plain"),
        canonicalSourceAttribute = paragraph.source.getAttribute(CANONICAL_SOURCE_ATTRIBUTE),
        exactPreparedDomAttribute = paragraph.source.getAttribute(EXACT_PREPARED_DOM_ATTRIBUTE),
        langAttribute = paragraph.source.getAttribute("lang"),
        styleAttribute = paragraph.source.getAttribute("style"),
        capabilityNameAttribute = paragraph.source.getAttribute("data-tiqian-capability-issue"),
        capabilityDetailAttribute = paragraph.source.getAttribute("data-tiqian-capability-detail"),
        lastMeasure = paragraph.lastMeasure,
        containingBlockApplied = paragraph.containingBlockApplied,
        hostInlineSizeApplied = paragraph.hostInlineSizeApplied,
        hostInlineSizeAttribute = paragraph.source.getAttribute(HOST_INLINE_SIZE_ATTRIBUTE),
        originalContentHadChildren = paragraph.originalContent.firstChild != null,
    )
    while (paragraph.source.firstChild != null) {
        content.appendChild(paragraph.source.firstChild!!)
    }
    return snapshot
}

internal fun TiqianWeb.rollbackRelayoutSnapshots(snapshots: List<LiveParagraphSnapshot>) {
    for (snapshot in snapshots.asReversed()) {
        val paragraph = snapshot.paragraph
        if (snapshot.originalContentHadChildren && paragraph.originalContent.firstChild == null) {
            // restoreParagraph() handed the semantic source fragment back
            // to the live DOM; move those exact nodes into source custody
            // again before replaying the previous rendered fragment.
            while (paragraph.source.firstChild != null) {
                paragraph.originalContent.appendChild(paragraph.source.firstChild!!)
            }
        } else {
            while (paragraph.source.firstChild != null) {
                paragraph.source.removeChild(paragraph.source.firstChild!!)
            }
        }
        paragraph.source.appendChild(snapshot.content)
        restoreAttribute(paragraph.source, "data-tq-rendered", snapshot.renderedAttribute)
        restoreAttribute(
            paragraph.source,
            "data-tq-canonical-plain",
            snapshot.preparedFlowAttribute,
        )
        restoreAttribute(
            paragraph.source,
            CANONICAL_SOURCE_ATTRIBUTE,
            snapshot.canonicalSourceAttribute,
        )
        restoreAttribute(
            paragraph.source,
            EXACT_PREPARED_DOM_ATTRIBUTE,
            snapshot.exactPreparedDomAttribute,
        )
        restoreAttribute(paragraph.source, "lang", snapshot.langAttribute)
        restoreAttribute(paragraph.source, "style", snapshot.styleAttribute)
        restoreAttribute(
            paragraph.source,
            "data-tiqian-capability-issue",
            snapshot.capabilityNameAttribute,
        )
        restoreAttribute(
            paragraph.source,
            "data-tiqian-capability-detail",
            snapshot.capabilityDetailAttribute,
        )
        paragraph.lastMeasure = snapshot.lastMeasure
        paragraph.containingBlockApplied = snapshot.containingBlockApplied
        paragraph.hostInlineSizeApplied = snapshot.hostInlineSizeApplied
        restoreAttribute(
            paragraph.source,
            HOST_INLINE_SIZE_ATTRIBUTE,
            snapshot.hostInlineSizeAttribute,
        )
    }
}

internal fun TiqianWeb.shouldTryParagraph(paragraph: HTMLElement): Boolean {
    if (hasClosest(paragraph, SKIPPED_ANCESTOR_SELECTOR)) return false
    if (paragraph.getAttribute("data-tiqian-skip") != null) return false
    // `LeafListItemParagraph`: Markdown commonly emits list text directly
    // inside <li>, so a list item is a paragraph-shaped flow owner and must
    // enter the same pipeline. An outer item that owns a nested block stays
    // native as a container; its leaf descendants are still independent
    // candidates. This avoids replacing a nested <ul>/<ol> while preserving
    // list markers and host list semantics.
    if (
        paragraph.tagName.uppercase() == "LI" &&
        paragraph.querySelector(":scope > p, :scope > ul, :scope > ol, :scope > blockquote, :scope > pre, :scope > table") != null
    ) {
        return false
    }
    // PureBlockImageParagraphExclusion: Markdown commonly wraps a
    // standalone image in <p>. A block image owns no inline text flow for
    // Tiqian to lay out, so leave the host wrapper native without reporting
    // a capability issue. Text mixed with a block image still enters the
    // lowerer and fails atomically as an unsupported formatting context.
    if (isPureBlockImageParagraph(paragraph)) return false
    if (paragraph.textContent?.isBlank() != false && !hasOpaqueInlineCandidate(paragraph)) return false
    return true
}

internal fun TiqianWeb.isPureBlockImageParagraph(paragraph: HTMLElement): Boolean {
    if (paragraph.tagName.uppercase() != "P" || paragraph.textContent?.isBlank() != true) return false
    val children = paragraph.querySelectorAll(":scope > *")
    if (children.length == 0) return false
    for (index in 0 until children.length) {
        val child = children.item(index) as? Element ?: return false
        if (
            child.tagName.uppercase() != "IMG" ||
            computedStyle(child, "display").trim().lowercase() != "block"
        ) return false
    }
    return true
}

internal fun TiqianWeb.hasOpaqueInlineCandidate(paragraph: HTMLElement): Boolean {
    val descendants = paragraph.querySelectorAll("*")
    for (index in 0 until descendants.length) {
        val element = descendants.item(index) as? Element ?: continue
        val tag = element.tagName.uppercase()
        val display = computedStyle(element, "display").trim().lowercase()
        if (tag in NON_TEXT_INLINE_TAGS || tag.contains('-') || display in OPAQUE_INLINE_DISPLAYS) {
            return true
        }
    }
    return false
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

internal fun TiqianWeb.optionsFromJs(options: JsAny?): EnhanceOptions {
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
    val sliceBudgetMs = optionFloat(options, "sliceBudgetMs")?.toDouble()
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
        sliceBudgetMs = sliceBudgetMs,
    )
}

internal fun TiqianWeb.optionFloat(options: JsAny?, name: String): Float? {
    val value = optionNumber(options, name)
    return if (value.isFinite()) value.toFloat() else null
}

internal fun TiqianWeb.ensureContainingBlock(paragraph: EnhancedParagraph) {
    if (paragraph.containingBlockApplied) return
    if (computedStyle(paragraph.source, "position").trim().lowercase() != "static") return
    paragraph.source.style.setProperty("position", "relative", "important")
    paragraph.containingBlockApplied = true
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

internal fun TiqianWeb.restoreParagraph(paragraph: EnhancedParagraph) {
    releasePreparedParagraphDomStyles(paragraph.source)
    while (paragraph.source.firstChild != null) {
        paragraph.source.removeChild(paragraph.source.firstChild!!)
    }
    paragraph.source.appendChild(paragraph.originalContent)
    restoreAttribute(paragraph.source, "data-tq-rendered", paragraph.originalRenderedAttribute)
    restoreAttribute(
        paragraph.source,
        "data-tq-canonical-plain",
        paragraph.originalPreparedFlowAttribute,
    )
    restoreAttribute(
        paragraph.source,
        CANONICAL_SOURCE_ATTRIBUTE,
        paragraph.originalCanonicalSourceAttribute,
    )
    restoreAttribute(
        paragraph.source,
        EXACT_PREPARED_DOM_ATTRIBUTE,
        paragraph.originalExactPreparedDomAttribute,
    )
    paragraph.source.removeAttribute(RUNTIME_RENDER_FONT_ATTRIBUTE)
    restoreAttribute(paragraph.source, "lang", paragraph.originalLangAttribute)
    if (paragraph.containingBlockApplied &&
        paragraph.source.style.getPropertyValue("position") == "relative" &&
        paragraph.source.style.getPropertyPriority("position") == "important"
    ) {
        if (paragraph.originalPosition.isEmpty()) {
            paragraph.source.style.removeProperty("position")
        } else {
            paragraph.source.style.setProperty(
                "position",
                paragraph.originalPosition,
                paragraph.originalPositionPriority,
            )
        }
    }
    val appliedInlineSize = paragraph.hostInlineSizeApplied
    if (
        appliedInlineSize != null &&
        paragraph.source.getAttribute(HOST_INLINE_SIZE_ATTRIBUTE) == "true" &&
        paragraph.source.style.getPropertyValue("inline-size") == appliedInlineSize &&
        paragraph.source.style.getPropertyPriority("inline-size") == "important"
    ) {
        if (paragraph.originalInlineSize.isEmpty()) {
            paragraph.source.style.removeProperty("inline-size")
        } else {
            paragraph.source.style.setProperty(
                "inline-size",
                paragraph.originalInlineSize,
                paragraph.originalInlineSizePriority,
            )
        }
    }
    val appliedFontSize = paragraph.hostFontSizeApplied
    if (
        appliedFontSize != null &&
        paragraph.source.style.getPropertyValue("font-size") == appliedFontSize &&
        paragraph.source.style.getPropertyPriority("font-size") == "important"
    ) {
        if (paragraph.originalFontSize.isEmpty()) {
            paragraph.source.style.removeProperty("font-size")
        } else {
            paragraph.source.style.setProperty(
                "font-size",
                paragraph.originalFontSize,
                paragraph.originalFontSizePriority,
            )
        }
    }
    restoreAttribute(
        paragraph.source,
        HOST_INLINE_SIZE_ATTRIBUTE,
        paragraph.originalHostInlineSizeAttribute,
    )
    if (paragraph.originalStyleAttribute == null) {
        if (paragraph.source.getAttribute("style")?.isBlank() != false) {
            paragraph.source.removeAttribute("style")
        }
    }
    paragraph.containingBlockApplied = false
    paragraph.hostInlineSizeApplied = null
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
