@file:OptIn(kotlin.js.ExperimentalJsExport::class)

package org.tiqian.ffi.js

import kotlin.js.JsExport

/**
 * Plan-plus-diagnostics envelope for the TsHost web-host prepare step. The
 * host passes its own ZERO_ADVANCE_EPSILON as [zeroAdvanceEpsilonPx] so the
 * layout module holds no host policy; the returned JSON embeds the plan plus
 * the capability-issue and suspicious-advance facts for the host-side checks.
 *
 * The exact-session path now receives shaping and metrics via callbacks
 * ([shapeJson], [metricsJson]) instead of a session id. This inverts control
 * so the engine has no dependency on environment globals. ADR 0053.
 *
 * Corrective wave 5 (#106): entry signatures now take declared DTO interfaces
 * instead of flat separator-joined strings.
 */
@JsExport
fun precomputeParagraphWithDiagnostics(
    request: PrepareParagraphRequest,
    zeroAdvanceEpsilonPx: Double,
    shapeJson: (String) -> String,
    metricsJson: (String) -> String,
): String {
    val internalRequest = toInternalPrepareRequest(request)
    return ParagraphWireCodec(
        textShaper = JsCallbackTextShaper(shapeJson),
        fontMetricsResolver = JsCallbackFontMetricsResolver(metricsJson),
    ).planWithDiagnostics(internalRequest, zeroAdvanceEpsilonPx)
}

/**
 * Plan-plus-diagnostics envelope using host-provided browser measurement callbacks.
 *
 * The layout engine runs in Kotlin while text shaping and font metric resolution
 * are delegated to JavaScript callbacks via [BrowserMetricsCallbacks]. No native
 * font session is created. The callbacks run on the same synchronous call stack,
 * and every shape() request re-sends the segment text.
 *
 * Corrective wave 5 (#106): the typed [BrowserMetricsCallbacks] DTO replaces
 * the previous adapter classes and individual function parameters.
 */
@JsExport
fun precomputeParagraphWithBrowserMetrics(
    request: PrepareParagraphRequest,
    zeroAdvanceEpsilonPx: Double,
    callbacks: BrowserMetricsCallbacks,
): String {
    val internalRequest = toInternalPrepareRequest(request)
    return ParagraphWireCodec(
        textShaper = JsCallbackTextShaper { requestJson -> callbacks.shapeJson(requestJson) },
        fontMetricsResolver = JsCallbackFontMetricsResolver { requestJson -> callbacks.metricsJson(requestJson) },
    ).planWithDiagnostics(internalRequest, zeroAdvanceEpsilonPx)
}

private fun toInternalPrepareRequest(request: PrepareParagraphRequest): PrepareParagraphRequestDto {
    return PrepareParagraphRequestDto(
        text = request.text,
        maxWidthPx = request.maxWidthPx,
        fontFamilies = request.fontFamilies,
        fontSizePx = request.fontSizePx,
        lineHeightPx = request.lineHeightPx,
        locale = request.locale,
        fontWeight = request.fontWeight,
        italic = request.italic,
        firstLineIndentIc = request.firstLineIndentIc,
        lineLengthGridEnabled = request.lineLengthGridEnabled,
        sourceBoundaries = request.sourceBoundaries,
        textSpans = request.textSpans.map { toInternalTextSpan(it) }.toTypedArray(),
        inlineBoxes = request.inlineBoxes.map { toInternalInlineBox(it) }.toTypedArray(),
        lineBreakSpans = request.lineBreakSpans.map { toInternalLineBreakSpan(it) }.toTypedArray(),
        inlineObjects = request.inlineObjects.map { toInternalInlineObject(it) }.toTypedArray(),
        decorations = request.decorations.map { toInternalDecoration(it) }.toTypedArray(),
        emphasisDotGapEm = request.emphasisDotGapEm,
        renderEvidenceOverride = request.renderEvidenceOverride,
    )
}

private fun toInternalTextSpan(span: TextSpanWire): TextSpanWireDto = TextSpanWireDto(
    start = span.start,
    end = span.end,
    fontFamilies = span.fontFamilies,
    fontSize = span.fontSize,
    fontWeight = span.fontWeight,
    italic = span.italic,
    baselineShift = span.baselineShift,
)

private fun toInternalInlineBox(box: InlineBoxWire): InlineBoxWireDto = InlineBoxWireDto(
    start = box.start,
    end = box.end,
    inlineStart = box.inlineStart,
    inlineEnd = box.inlineEnd,
    outerSpacing = box.outerSpacing,
)

private fun toInternalLineBreakSpan(span: LineBreakSpanWire): LineBreakSpanWireDto = LineBreakSpanWireDto(
    start = span.start,
    end = span.end,
    policy = span.policy,
)

private fun toInternalInlineObject(obj: InlineObjectWire): InlineObjectWireDto = InlineObjectWireDto(
    start = obj.start,
    end = obj.end,
    advance = obj.advance,
    ascent = obj.ascent,
    descent = obj.descent,
)

private fun toInternalDecoration(deco: DecorationWire): DecorationWireDto = DecorationWireDto(
    start = deco.start,
    end = deco.end,
    kind = deco.kind,
)

fun main() = Unit