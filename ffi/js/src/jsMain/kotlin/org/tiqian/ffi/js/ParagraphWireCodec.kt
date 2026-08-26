package org.tiqian.ffi.js

import org.tiqian.core.DEFAULT_EMPHASIS_DOT_GAP_EM
import org.tiqian.core.DecorationKind
import org.tiqian.core.DecorationSpan
import org.tiqian.core.Ic
import org.tiqian.core.InlineBoxOuterSpacing
import org.tiqian.core.InlineBoxSpan
import org.tiqian.core.InlineObjectSpan
import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LayoutInput
import org.tiqian.core.LayoutResult
import org.tiqian.core.LineBreakPolicy
import org.tiqian.core.LineBreakSpan
import org.tiqian.core.LineLengthGrid
import org.tiqian.core.ParagraphStyle
import org.tiqian.core.TextRange
import org.tiqian.core.TextSpan
import org.tiqian.core.TextStyle
import org.tiqian.core.TiqianTextContent
import org.tiqian.font.FontMetricsResolver
import org.tiqian.layout.ExplainableStubParagraphLayoutEngine
import org.tiqian.layout.LookaheadLineBreaker
import org.tiqian.layout.toPlanWithDiagnosticsJson
import org.tiqian.layout.toPreparedParagraphJson
import org.tiqian.shaping.TextShaper

/**
 * Separator wire codec of the layout engine (ADR 0053 SingleEngineFace).
 * Wire decoding, validation and [LayoutInput] assembly live with the engine
 * so every JS host consumes one entry; ffi layers only transport strings.
 * The encoding itself is the ADR 0050 js ABI: record, field and family
 * separators, flat primitive parameters in, one plan JSON string out.
 *
 * This file now also provides DTO-based entry points (corrective wave 5/#106).
 * The legacy string-based entry points are retained temporarily for tests;
 * they are deleted when the last test migrates to DTO fixtures.
 */

private const val RECORD_SEPARATOR = "\u001e"
private const val FIELD_SEPARATOR = "\u001d"
private const val FAMILY_SEPARATOR = "\u001f"

private fun parseBoundaries(value: String, textLength: Int): Set<Int> =
    value.split(',')
        .filter(String::isNotBlank)
        .map { it.toInt() }
        .onEach { require(it in 0..textLength) { "InvalidSourceBoundary" } }
        .toSet()

private fun parseDecorations(value: String, textLength: Int): List<DecorationSpan> =
    value.split(RECORD_SEPARATOR)
        .filter(String::isNotBlank)
        .map { record ->
            val fields = record.split(FIELD_SEPARATOR)
            require(fields.size == 3) { "InvalidDecorationWire" }
            val start = fields[0].toInt()
            val end = fields[1].toInt()
            require(start in 0 until end && end <= textLength) { "InvalidDecorationRange" }
            DecorationSpan(
                range = TextRange(start, end),
                kind = DecorationKind.valueOf(fields[2]),
            )
        }

private fun parseTextSpans(value: String, locale: String, textLength: Int): List<TextSpan> =
    value.split(RECORD_SEPARATOR)
        .filter(String::isNotBlank)
        .map { record ->
            val fields = record.split(FIELD_SEPARATOR)
            require(fields.size == 7) { "InvalidTextSpanWire" }
            val start = fields[0].toInt()
            val end = fields[1].toInt()
            require(start in 0 until end && end <= textLength) { "InvalidTextSpanRange" }
            val families = fields[2].split(FAMILY_SEPARATOR).filter(String::isNotBlank)
            require(families.isNotEmpty()) { "MissingTextSpanFontFamilies" }
            val fontSize = fields[3].toFloat()
            val fontWeight = fields[4].toInt()
            val italic = when (fields[5]) {
                "true" -> true
                "false" -> false
                else -> error("InvalidTextSpanItalic")
            }
            val baselineShift = fields[6].toFloat()
            require(fontSize.isFinite() && fontSize > 0f) { "InvalidTextSpanFontSize" }
            require(fontWeight in 1..1000) { "InvalidTextSpanFontWeight" }
            require(baselineShift.isFinite()) { "InvalidTextSpanBaselineShift" }
            TextSpan(
                range = TextRange(start, end),
                style = TextStyle(
                    fontFamilies = families,
                    fontSize = fontSize,
                    locale = locale,
                    fontWeight = fontWeight,
                    italic = italic,
                    baselineShift = baselineShift,
                ),
            )
        }

private fun parseInlineBoxes(value: String, textLength: Int): List<InlineBoxSpan> =
    value.split(RECORD_SEPARATOR)
        .filter(String::isNotBlank)
        .map { record ->
            val fields = record.split(FIELD_SEPARATOR)
            require(fields.size == 4 || fields.size == 5) { "InvalidInlineBoxWire" }
            val start = fields[0].toInt()
            val end = fields[1].toInt()
            val inlineStart = fields[2].toFloat()
            val inlineEnd = fields[3].toFloat()
            require(start in 0 until end && end <= textLength) { "InvalidInlineBoxRange" }
            require(inlineStart.isFinite() && inlineEnd.isFinite()) { "InvalidInlineBoxGeometry" }
            val outerSpacing = fields.getOrNull(4)
                ?.let(InlineBoxOuterSpacing::valueOf)
                ?: InlineBoxOuterSpacing.Narrow
            InlineBoxSpan(TextRange(start, end), inlineStart, inlineEnd, outerSpacing)
        }

private fun parseLineBreakSpans(value: String, textLength: Int): List<LineBreakSpan> =
    value.split(RECORD_SEPARATOR)
        .filter(String::isNotBlank)
        .map { record ->
            val fields = record.split(FIELD_SEPARATOR)
            require(fields.size == 3) { "InvalidLineBreakSpanWire" }
            val start = fields[0].toInt()
            val end = fields[1].toInt()
            require(start in 0 until end && end <= textLength) { "InvalidLineBreakSpanRange" }
            LineBreakSpan(TextRange(start, end), LineBreakPolicy.valueOf(fields[2]))
        }

private fun parseInlineObjects(value: String, textLength: Int): List<InlineObjectSpan> =
    value.split(RECORD_SEPARATOR)
        .filter(String::isNotBlank)
        .map { record ->
            val fields = record.split(FIELD_SEPARATOR)
            require(fields.size == 5) { "InvalidInlineObjectWire" }
            val start = fields[0].toInt()
            val end = fields[1].toInt()
            val advance = fields[2].toFloat()
            val ascent = fields[3].toFloat()
            val descent = fields[4].toFloat()
            require(start in 0 until end && end <= textLength) { "InvalidInlineObjectRange" }
            require(advance.isFinite() && advance >= 0f) { "InvalidInlineObjectAdvance" }
            require(ascent.isFinite() && descent.isFinite()) { "InvalidInlineObjectVerticalGeometry" }
            InlineObjectSpan(TextRange(start, end), advance, ascent, descent)
        }

class ParagraphWireCodec(
    private val textShaper: TextShaper,
    private val fontMetricsResolver: FontMetricsResolver,
) {
    fun plan(
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
        inlineObjects: String = "",
        renderEvidenceOverride: Boolean? = null,
    ): String {
        val result = layout(
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
            inlineObjects = inlineObjects,
        )
        // WorkerRichPlanEvidence: the Worker runs the pure exact session, so
        // evidence exists for exactly the non-plain wire shapes the runtime
        // path also evidences (inline objects and styled/boxed runs); plain
        // plans stay byte-identical to the evidence-free form.
        // The wire derives evidence from the wire-visible collections. The host
        // passes the six-collection verdict as the override because sourceSpans
        // and domInlineObjects never travel the wire.
        return result.toPreparedParagraphJson(
            renderEvidence = renderEvidenceOverride ?: (textSpans.isNotBlank() ||
                inlineBoxes.isNotBlank() ||
                result.input.inlineObjects.isNotEmpty()),
        )
    }

    /**
     * Plan-plus-diagnostics envelope for the TsHost worker/precompute path.
     * [zeroAdvanceEpsilonPx] is the host threshold (ZERO_ADVANCE_EPSILON on
     * the web host), passed in so the layout module holds no host policy.
     * Diagnostics carry facts only — the verdicts for the web pipeline's
     * named capability checks stay host-side.
     */
    fun planWithDiagnostics(
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
        inlineObjects: String = "",
        zeroAdvanceEpsilonPx: Double,
        decorations: String = "",
        emphasisDotGapEm: Double? = null,
        renderEvidenceOverride: Boolean? = null,
    ): String {
        val result = layout(
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
            inlineObjects = inlineObjects,
            decorations = decorations,
            emphasisDotGapEm = emphasisDotGapEm,
        )
        return result.toPlanWithDiagnosticsJson(
            // The wire derives evidence from the wire-visible collections. The
            // host passes the six-collection verdict as the override because
            // sourceSpans and domInlineObjects never travel the wire.
            renderEvidence = renderEvidenceOverride ?: (textSpans.isNotBlank() ||
                inlineBoxes.isNotBlank() ||
                decorations.isNotBlank() ||
                result.input.inlineObjects.isNotEmpty()),
            zeroAdvanceEpsilonPx = zeroAdvanceEpsilonPx.toFloat(),
        )
    }

    private fun layout(
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
        inlineObjects: String = "",
        decorations: String = "",
        emphasisDotGapEm: Double? = null,
    ): LayoutResult {
        require(text.isNotBlank()) { "EmptyParagraph" }
        require(maxWidthPx.isFinite() && maxWidthPx > 0.0) { "InvalidMaximumMeasure" }
        require(fontSizePx.isFinite() && fontSizePx > 0.0) { "InvalidFontSize" }
        require(lineHeightPx.isFinite() && lineHeightPx > 0.0) { "InvalidLineHeight" }
        require(firstLineIndentIc.isFinite()) { "InvalidFirstLineIndent" }
        require(fontWeight in 1..1000) { "InvalidFontWeight" }

        val gapEm = emphasisDotGapEm ?: DEFAULT_EMPHASIS_DOT_GAP_EM.toDouble()
        require(gapEm.isFinite() && gapEm >= 0.0) { "InvalidEmphasisDotGapEm" }

        val families = fontFamilies.split(FAMILY_SEPARATOR).filter(String::isNotBlank)
        require(families.isNotEmpty()) { "MissingExplicitFontFamilies" }

        val textStyle = TextStyle(
            fontFamilies = families,
            fontSize = fontSizePx.toFloat(),
            locale = locale,
            fontWeight = fontWeight,
            italic = italic,
        )
        val parsedInlineObjects = parseInlineObjects(inlineObjects, text.length)
        val parsedDecorations = parseDecorations(decorations, text.length)
        val input = LayoutInput(
            content = TiqianTextContent(
                text = text,
                spans = parseTextSpans(textSpans, locale, text.length),
                sourceBoundaries = parseBoundaries(sourceBoundaries, text.length),
                lineBreakSpans = parseLineBreakSpans(lineBreakSpans, text.length),
            ),
            textStyle = textStyle,
            paragraphStyle = ParagraphStyle(
                lineHeight = lineHeightPx.toFloat(),
                firstLineIndent = Ic(firstLineIndentIc.toFloat()),
                lineLengthGrid = LineLengthGrid(enabled = lineLengthGridEnabled),
                emphasisDotGapEm = gapEm.toFloat(),
            ),
            constraints = LayoutConstraints(maxWidth = maxWidthPx.toFloat()),
            decorations = parsedDecorations,
            inlineBoxes = parseInlineBoxes(inlineBoxes, text.length),
            inlineObjects = parsedInlineObjects,
        )
        return ExplainableStubParagraphLayoutEngine(
            lineBreaker = LookaheadLineBreaker(),
            fontMetricsResolver = fontMetricsResolver,
            textShaper = textShaper,
        ).layout(input)
    }

    // DTO-based entry points (corrective wave 5/#106)
    // These replace the legacy string-based wire format.

    fun plan(request: WorkerLayoutRequestDto): String {
        val result = layout(request)
        return result.toPreparedParagraphJson(
            renderEvidence = request.renderEvidence,
        )
    }

    fun planWithDiagnostics(
        request: PrepareParagraphRequestDto,
        zeroAdvanceEpsilonPx: Double,
    ): String {
        val result = layout(request)
        return result.toPlanWithDiagnosticsJson(
            renderEvidence = request.renderEvidenceOverride ?: (request.textSpans.isNotEmpty() ||
                request.inlineBoxes.isNotEmpty() ||
                request.decorations.isNotEmpty() ||
                result.input.inlineObjects.isNotEmpty()),
            zeroAdvanceEpsilonPx = zeroAdvanceEpsilonPx.toFloat(),
        )
    }

    private fun layout(request: WorkerLayoutRequestDto): LayoutResult {
        return layout(
            text = request.text,
            maxWidthPx = request.maxWidthPx,
            fontFamilies = request.fontFamilies.joinToString(FAMILY_SEPARATOR),
            fontSizePx = request.fontSizePx,
            lineHeightPx = request.lineHeightPx,
            locale = request.locale,
            fontWeight = request.fontWeight,
            italic = request.italic,
            firstLineIndentIc = request.firstLineIndentIc,
            lineLengthGridEnabled = request.lineLengthGridEnabled,
            sourceBoundaries = request.sourceBoundaries.joinToString(","),
            textSpans = request.textSpans.joinToString(RECORD_SEPARATOR) { span ->
                "${span.start}${FIELD_SEPARATOR}${span.end}${FIELD_SEPARATOR}" +
                "${span.fontFamilies.joinToString(FAMILY_SEPARATOR)}${FIELD_SEPARATOR}" +
                "${span.fontSize}${FIELD_SEPARATOR}${span.fontWeight}${FIELD_SEPARATOR}" +
                "${span.italic}${FIELD_SEPARATOR}${span.baselineShift}"
            },
            inlineBoxes = request.inlineBoxes.joinToString(RECORD_SEPARATOR) { box ->
                "${box.start}${FIELD_SEPARATOR}${box.end}${FIELD_SEPARATOR}" +
                "${box.inlineStart}${FIELD_SEPARATOR}${box.inlineEnd}${FIELD_SEPARATOR}" +
                "${box.outerSpacing}"
            },
            lineBreakSpans = request.lineBreakSpans.joinToString(RECORD_SEPARATOR) { span ->
                "${span.start}${FIELD_SEPARATOR}${span.end}${FIELD_SEPARATOR}${span.policy}"
            },
            inlineObjects = request.inlineObjects.joinToString(RECORD_SEPARATOR) { obj ->
                "${obj.start}${FIELD_SEPARATOR}${obj.end}${FIELD_SEPARATOR}" +
                "${obj.advance}${FIELD_SEPARATOR}${obj.ascent}${FIELD_SEPARATOR}${obj.descent}"
            },
        )
    }

    private fun layout(request: PrepareParagraphRequestDto): LayoutResult {
        require(request.text.isNotBlank()) { "EmptyParagraph" }
        require(request.maxWidthPx.isFinite() && request.maxWidthPx > 0.0) { "InvalidMaximumMeasure" }
        require(request.fontSizePx.isFinite() && request.fontSizePx > 0.0) { "InvalidFontSize" }
        require(request.lineHeightPx.isFinite() && request.lineHeightPx > 0.0) { "InvalidLineHeight" }
        require(request.firstLineIndentIc.isFinite()) { "InvalidFirstLineIndent" }
        require(request.fontWeight in 1..1000) { "InvalidFontWeight" }

        val gapEm = request.emphasisDotGapEm ?: DEFAULT_EMPHASIS_DOT_GAP_EM.toDouble()
        require(gapEm.isFinite() && gapEm >= 0.0) { "InvalidEmphasisDotGapEm" }

        val families = request.fontFamilies.filter(String::isNotBlank)
        require(families.isNotEmpty()) { "MissingExplicitFontFamilies" }

        val textStyle = TextStyle(
            fontFamilies = families,
            fontSize = request.fontSizePx.toFloat(),
            locale = request.locale,
            fontWeight = request.fontWeight,
            italic = request.italic,
        )
        val parsedInlineObjects = request.inlineObjects.map { obj ->
            InlineObjectSpan(
                TextRange(obj.start, obj.end),
                obj.advance.toFloat(),
                obj.ascent.toFloat(),
                obj.descent.toFloat(),
            )
        }
        val parsedDecorations = request.decorations.map { deco ->
            DecorationSpan(
                range = TextRange(deco.start, deco.end),
                kind = DecorationKind.valueOf(deco.kind),
            )
        }
        val input = LayoutInput(
            content = TiqianTextContent(
                text = request.text,
                spans = request.textSpans.map { span ->
                    TextSpan(
                        range = TextRange(span.start, span.end),
                        style = TextStyle(
                            fontFamilies = span.fontFamilies.filter(String::isNotBlank).toList(),
                            fontSize = span.fontSize.toFloat(),
                            locale = request.locale,
                            fontWeight = span.fontWeight,
                            italic = span.italic,
                            baselineShift = span.baselineShift.toFloat(),
                        ),
                    )
                },
                sourceBoundaries = request.sourceBoundaries.toSet(),
                lineBreakSpans = request.lineBreakSpans.map { span ->
                    LineBreakSpan(TextRange(span.start, span.end), LineBreakPolicy.valueOf(span.policy))
                },
            ),
            textStyle = textStyle,
            paragraphStyle = ParagraphStyle(
                lineHeight = request.lineHeightPx.toFloat(),
                firstLineIndent = Ic(request.firstLineIndentIc.toFloat()),
                lineLengthGrid = LineLengthGrid(enabled = request.lineLengthGridEnabled),
                emphasisDotGapEm = gapEm.toFloat(),
            ),
            constraints = LayoutConstraints(maxWidth = request.maxWidthPx.toFloat()),
            decorations = parsedDecorations,
            inlineBoxes = request.inlineBoxes.map { box ->
                InlineBoxSpan(
                    TextRange(box.start, box.end),
                    box.inlineStart.toFloat(),
                    box.inlineEnd.toFloat(),
                    InlineBoxOuterSpacing.valueOf(box.outerSpacing),
                )
            },
            inlineObjects = parsedInlineObjects,
        )
        return ExplainableStubParagraphLayoutEngine(
            lineBreaker = LookaheadLineBreaker(),
            fontMetricsResolver = fontMetricsResolver,
            textShaper = textShaper,
        ).layout(input)
    }
}