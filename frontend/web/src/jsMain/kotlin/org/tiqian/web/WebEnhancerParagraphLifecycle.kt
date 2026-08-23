package org.tiqian.web

import org.tiqian.core.DEFAULT_EMPHASIS_DOT_GAP_EM
import org.tiqian.shaping.web.WebCjkDashCapability
import org.tiqian.web.TiqianWeb.CapabilityIssue
import org.tiqian.web.TiqianWeb.EnhanceOptions
import org.tiqian.web.TiqianWeb.EnhancedParagraph
import org.tiqian.web.TiqianWeb.ExactFontSessionCapability
import org.tiqian.web.TiqianWeb.FontFamilyOptions
import org.tiqian.web.TiqianWeb.SourceInlineSize
import org.w3c.dom.HTMLElement

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
