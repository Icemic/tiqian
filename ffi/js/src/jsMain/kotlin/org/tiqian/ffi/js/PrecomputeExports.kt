@file:OptIn(kotlin.js.ExperimentalJsExport::class)

package org.tiqian.ffi.js

import kotlin.js.JsExport
import org.tiqian.font.FontMetricsResolver
import org.tiqian.layout.ParagraphWireFace
import org.tiqian.shaping.TextShaper

/**
 * Plan-plus-diagnostics envelope for the TsHost web-host prepare step. The
 * host passes its own ZERO_ADVANCE_EPSILON as [zeroAdvanceEpsilonPx] so the
 * layout module holds no host policy; the returned JSON embeds the plan plus
 * the capability-issue and suspicious-advance facts for the host-side checks.
 *
 * The exact-session path now receives shaping and metrics via callbacks
 * ([shapeJson], [metricsJson]) instead of a session id. This inverts control
 * so the engine has no dependency on environment globals. ADR 0053.
 */
@JsExport
fun precomputeParagraphWithDiagnostics(
    text: String,
    maxWidthPx: Double,
    fontFamilies: String,
    fontSizePx: Double,
    lineHeightPx: Double,
    locale: String,
    fontWeight: Int,
    italic: Boolean,
    firstLineIndentIc: Double,
    lineLengthGridEnabled: Boolean,
    sourceBoundaries: String,
    textSpans: String,
    inlineBoxes: String,
    lineBreakSpans: String,
    // Nullable so pre-inline-object / pre-decoration / pre-render-evidence-override
    // JS callers that omit trailing arguments (undefined) keep working across
    // package version skew.
    inlineObjects: String?,
    zeroAdvanceEpsilonPx: Double,
    shapeJson: (String) -> String,
    metricsJson: (String) -> String,
    decorations: String? = null,
    emphasisDotGapEm: Double? = null,
    renderEvidenceOverride: Boolean? = null,
): String {
    return ParagraphWireFace(
        textShaper = JsCallbackTextShaper(shapeJson),
        fontMetricsResolver = JsCallbackFontMetricsResolver(metricsJson),
    ).planWithDiagnostics(
        text = text,
        maxWidthPx = maxWidthPx,
        fontFamilies = fontFamilies,
        fontSizePx = fontSizePx,
        lineHeightPx = lineHeightPx,
        locale = locale,
        fontWeight = fontWeight,
        italic = italic,
        firstLineIndentIc = firstLineIndentIc,
        lineLengthGridEnabled = lineLengthGridEnabled,
        sourceBoundaries = sourceBoundaries,
        textSpans = textSpans,
        inlineBoxes = inlineBoxes,
        lineBreakSpans = lineBreakSpans,
        inlineObjects = inlineObjects ?: "",
        zeroAdvanceEpsilonPx = zeroAdvanceEpsilonPx,
        decorations = decorations ?: "",
        emphasisDotGapEm = emphasisDotGapEm,
        renderEvidenceOverride = renderEvidenceOverride,
    )
}

/**
 * Plan-plus-diagnostics envelope using host-provided browser measurement callbacks.
 *
 * The layout engine runs in Kotlin while text shaping and font metric resolution
 * are delegated to JavaScript callbacks ([shapeJson] and [metricsJson]). No native
 * font session is created. The callbacks run on the same synchronous call stack,
 * and every shape() request re-sends the segment text.
 */
@JsExport
fun precomputeParagraphWithBrowserMetrics(
    text: String,
    maxWidthPx: Double,
    fontFamilies: String,
    fontSizePx: Double,
    lineHeightPx: Double,
    locale: String,
    fontWeight: Int,
    italic: Boolean,
    firstLineIndentIc: Double,
    lineLengthGridEnabled: Boolean,
    sourceBoundaries: String,
    textSpans: String,
    inlineBoxes: String,
    lineBreakSpans: String,
    // Nullable so pre-inline-object / pre-decoration / pre-render-evidence-override
    // JS callers that omit trailing arguments (undefined) keep working across
    // package version skew.
    inlineObjects: String?,
    zeroAdvanceEpsilonPx: Double,
    shapeJson: (String) -> String,
    metricsJson: (String) -> String,
    decorations: String? = null,
    emphasisDotGapEm: Double? = null,
    renderEvidenceOverride: Boolean? = null,
): String {
    return ParagraphWireFace(
        textShaper = JsCallbackTextShaper(shapeJson),
        fontMetricsResolver = JsCallbackFontMetricsResolver(metricsJson),
    ).planWithDiagnostics(
        text = text,
        maxWidthPx = maxWidthPx,
        fontFamilies = fontFamilies,
        fontSizePx = fontSizePx,
        lineHeightPx = lineHeightPx,
        locale = locale,
        fontWeight = fontWeight,
        italic = italic,
        firstLineIndentIc = firstLineIndentIc,
        lineLengthGridEnabled = lineLengthGridEnabled,
        sourceBoundaries = sourceBoundaries,
        textSpans = textSpans,
        inlineBoxes = inlineBoxes,
        lineBreakSpans = lineBreakSpans,
        inlineObjects = inlineObjects ?: "",
        zeroAdvanceEpsilonPx = zeroAdvanceEpsilonPx,
        decorations = decorations ?: "",
        emphasisDotGapEm = emphasisDotGapEm,
        renderEvidenceOverride = renderEvidenceOverride,
    )
}

fun main() = Unit