@file:OptIn(kotlin.js.ExperimentalJsExport::class)

package org.tiqian.ffi.js

import kotlin.js.JsExport
import org.tiqian.font.FontMetricsResolver
import org.tiqian.layout.ParagraphWireFace
import org.tiqian.shaping.HarfBuzzSessionFontMetricsResolver
import org.tiqian.shaping.HarfBuzzSessionTextShaper
import org.tiqian.shaping.TextShaper

/**
 * Stable, narrow JSON ABI consumed by `@tiqian/precompute`.
 *
 * The caller has already prepared an immutable exact-font session. Keeping the
 * exported values primitive avoids exposing the core model through the JavaScript
 * ABI while the returned plan remains inspectable and versioned. Parsing and
 * the layout call live in the layout module's [ParagraphWireFace]. ADR 0050.
 */

/** Platform-provided shaping/metrics pair behind a session id. */
internal class PrecomputeBackends(
    val textShaper: TextShaper,
    val fontMetricsResolver: FontMetricsResolver,
)

@JsExport
fun precomputePlainParagraph(
    fontSessionId: String,
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
): String = precomputeParagraph(
    fontSessionId = fontSessionId,
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
    sourceBoundaries = "",
    textSpans = "",
    inlineBoxes = "",
    lineBreakSpans = "",
    inlineObjects = "",
)

/** Structured paragraph ABI: semantics stay in JS; metric spans enter the real layout pipeline. */
@JsExport
fun precomputeParagraph(
    fontSessionId: String,
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
    // Nullable so pre-inline-object / pre-render-evidence-override JS callers
    // that omit the trailing argument (undefined) keep working across package
    // version skew.
    inlineObjects: String?,
    renderEvidenceOverride: Boolean? = null,
): String {
    val backends = buildPrecomputeBackends(fontSessionId)
    return ParagraphWireFace(
        textShaper = backends.textShaper,
        fontMetricsResolver = backends.fontMetricsResolver,
    ).plan(
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
        renderEvidenceOverride = renderEvidenceOverride,
    )
}

/**
 * Plan-plus-diagnostics envelope for the TsHost web-host prepare step. The
 * host passes its own ZERO_ADVANCE_EPSILON as [zeroAdvanceEpsilonPx] so the
 * layout module holds no host policy; the returned JSON embeds the plan plus
 * the capability-issue and suspicious-advance facts for the host-side checks.
 */
@JsExport
fun precomputeParagraphWithDiagnostics(
    fontSessionId: String,
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
    decorations: String? = null,
    emphasisDotGapEm: Double? = null,
    renderEvidenceOverride: Boolean? = null,
): String {
    val backends = buildPrecomputeBackends(fontSessionId)
    return ParagraphWireFace(
        textShaper = backends.textShaper,
        fontMetricsResolver = backends.fontMetricsResolver,
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

internal fun buildPrecomputeBackends(fontSessionId: String): PrecomputeBackends =
    PrecomputeBackends(
        textShaper = HarfBuzzSessionTextShaper(fontSessionId),
        fontMetricsResolver = HarfBuzzSessionFontMetricsResolver(fontSessionId),
    )

fun main() = Unit
