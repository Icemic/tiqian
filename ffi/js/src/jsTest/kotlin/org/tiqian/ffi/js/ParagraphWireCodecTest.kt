package org.tiqian.ffi.js

import org.tiqian.core.ShapingDecisionInfo
import org.tiqian.font.StubFontMetricsResolver
import org.tiqian.shaping.ExplainableStubTextShaper
import org.tiqian.shaping.ShapingInput
import org.tiqian.shaping.ShapingResult
import org.tiqian.shaping.TextShaper
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ParagraphWireCodecTest {

    private val shaper = ExplainableStubTextShaper()
    private val metrics = StubFontMetricsResolver()
    private val codec = ParagraphWireCodec(textShaper = shaper, fontMetricsResolver = metrics)

    private fun plan(
        text: String,
        shaper: TextShaper = this.shaper,
        textSpans: String = "",
        renderEvidenceOverride: Boolean? = null,
    ): String =
        ParagraphWireCodec(textShaper = shaper, fontMetricsResolver = metrics).plan(
            text = text,
            maxWidthPx = 400.0,
            fontFamilies = "\u001fNoto Sans CJK SC",
            fontSizePx = 16.0,
            lineHeightPx = 24.0,
            locale = "zh-Hans",
            fontWeight = 400,
            italic = false,
            firstLineIndentIc = 2.0,
            lineLengthGridEnabled = false,
            sourceBoundaries = "",
            textSpans = textSpans,
            inlineBoxes = "",
            lineBreakSpans = "",
            renderEvidenceOverride = renderEvidenceOverride,
        )

    private fun planWithDiagnostics(
        text: String,
        zeroAdvanceEpsilonPx: Double,
        decorations: String = "",
        emphasisDotGapEm: Double? = null,
        shaper: TextShaper = this.shaper,
        textSpans: String = "",
        renderEvidenceOverride: Boolean? = null,
    ): String =
        ParagraphWireCodec(textShaper = shaper, fontMetricsResolver = metrics).planWithDiagnostics(
            text = text,
            maxWidthPx = 400.0,
            fontFamilies = "\u001fNoto Sans CJK SC",
            fontSizePx = 16.0,
            lineHeightPx = 24.0,
            locale = "zh-Hans",
            fontWeight = 400,
            italic = false,
            firstLineIndentIc = 2.0,
            lineLengthGridEnabled = false,
            sourceBoundaries = "",
            textSpans = textSpans,
            inlineBoxes = "",
            lineBreakSpans = "",
            zeroAdvanceEpsilonPx = zeroAdvanceEpsilonPx,
            decorations = decorations,
            emphasisDotGapEm = emphasisDotGapEm,
            renderEvidenceOverride = renderEvidenceOverride,
        )

    @Test
    fun emptyTextThrowsEmptyParagraph() {
        val e = assertFailsWith<IllegalArgumentException> {
            codec.plan(text = "", maxWidthPx = 400.0, fontFamilies = "\u001fNoto Sans CJK SC",
                fontSizePx = 16.0, lineHeightPx = 24.0, locale = "zh-Hans",
                fontWeight = 400, italic = false, firstLineIndentIc = 2.0,
                lineLengthGridEnabled = false, sourceBoundaries = "", textSpans = "",
                inlineBoxes = "", lineBreakSpans = "")
        }
        assertContains(e.message ?: "", "EmptyParagraph")
    }

    @Test
    fun textSpansRangeOutOfBoundsThrowsInvalidTextSpanRange() {
        val e = assertFailsWith<IllegalArgumentException> {
            codec.plan(
                text = "你好",
                maxWidthPx = 400.0,
                fontFamilies = "\u001fNoto Sans CJK SC",
                fontSizePx = 16.0,
                lineHeightPx = 24.0,
                locale = "zh-Hans",
                fontWeight = 400,
                italic = false,
                firstLineIndentIc = 2.0,
                lineLengthGridEnabled = false,
                sourceBoundaries = "",
                textSpans = "0\u001d5\u001d\u001fNoto Sans CJK SC\u001d16.0\u001d400\u001dfalse\u001d0.0",
                inlineBoxes = "",
                lineBreakSpans = "",
            )
        }
        assertContains(e.message ?: "", "InvalidTextSpanRange")
    }

    @Test
    fun normalChineseParagraphReturnsLayoutRevisionV2() {
        val result = codec.plan(
            text = "你好世界",
            maxWidthPx = 400.0,
            fontFamilies = "\u001fNoto Sans CJK SC",
            fontSizePx = 16.0,
            lineHeightPx = 24.0,
            locale = "zh-Hans",
            fontWeight = 400,
            italic = false,
            firstLineIndentIc = 2.0,
            lineLengthGridEnabled = false,
            sourceBoundaries = "",
            textSpans = "",
            inlineBoxes = "",
            lineBreakSpans = "",
        )
        assertContains(result, "\"layoutRevision\":\"tiqian-layout-v2\"")
        assertContains(result, "\"rangeStart\":0")
    }

    @Test
    fun lineBreakSpansFieldCountNotThreeThrowsInvalidLineBreakSpanWire() {
        val e = assertFailsWith<IllegalArgumentException> {
            codec.plan(
                text = "你好",
                maxWidthPx = 400.0,
                fontFamilies = "\u001fNoto Sans CJK SC",
                fontSizePx = 16.0,
                lineHeightPx = 24.0,
                locale = "zh-Hans",
                fontWeight = 400,
                italic = false,
                firstLineIndentIc = 2.0,
                lineLengthGridEnabled = false,
                sourceBoundaries = "",
                textSpans = "",
                inlineBoxes = "",
                lineBreakSpans = "0\u001d2\u001dhard\u001dextra",
            )
        }
        assertContains(e.message ?: "", "InvalidLineBreakSpanWire")
    }

    @Test
    fun inlineObjectsEnterLayoutInputAndPlanEvidence() {
        val result = codec.plan(
            text = "中文",
            maxWidthPx = 400.0,
            fontFamilies = "\u001fNoto Sans CJK SC",
            fontSizePx = 16.0,
            lineHeightPx = 24.0,
            locale = "zh-Hans",
            fontWeight = 400,
            italic = false,
            firstLineIndentIc = 2.0,
            lineLengthGridEnabled = false,
            sourceBoundaries = "",
            textSpans = "",
            inlineBoxes = "",
            lineBreakSpans = "",
            inlineObjects = "0\u001d1\u001d18.0\u001d14.4\u001d4.32",
        )
        assertContains(result, "\"inlineObject\":18")
    }

    @Test
    fun plainParagraphWithoutInlineObjectsStaysLegacyPlan() {
        val result = codec.plan(
            text = "你好世界",
            maxWidthPx = 400.0,
            fontFamilies = "\u001fNoto Sans CJK SC",
            fontSizePx = 16.0,
            lineHeightPx = 24.0,
            locale = "zh-Hans",
            fontWeight = 400,
            italic = false,
            firstLineIndentIc = 2.0,
            lineLengthGridEnabled = false,
            sourceBoundaries = "",
            textSpans = "",
            inlineBoxes = "",
            lineBreakSpans = "",
        )
        assertFalse(result.contains("\"inlineObject\":"))
    }

    @Test
    fun inlineObjectsFieldCountNotFiveThrowsInvalidInlineObjectWire() {
        val e = assertFailsWith<IllegalArgumentException> {
            codec.plan(
                text = "中文",
                maxWidthPx = 400.0,
                fontFamilies = "\u001fNoto Sans CJK SC",
                fontSizePx = 16.0,
                lineHeightPx = 24.0,
                locale = "zh-Hans",
                fontWeight = 400,
                italic = false,
                firstLineIndentIc = 2.0,
                lineLengthGridEnabled = false,
                sourceBoundaries = "",
                textSpans = "",
                inlineBoxes = "",
                lineBreakSpans = "",
                inlineObjects = "0\u001d1\u001d18.0\u001d14.4",
            )
        }
        assertContains(e.message ?: "", "InvalidInlineObjectWire")
    }

    @Test
    fun inlineObjectsRangeOutOfBoundsThrowsInvalidInlineObjectRange() {
        val e = assertFailsWith<IllegalArgumentException> {
            codec.plan(
                text = "中文",
                maxWidthPx = 400.0,
                fontFamilies = "\u001fNoto Sans CJK SC",
                fontSizePx = 16.0,
                lineHeightPx = 24.0,
                locale = "zh-Hans",
                fontWeight = 400,
                italic = false,
                firstLineIndentIc = 2.0,
                lineLengthGridEnabled = false,
                sourceBoundaries = "",
                textSpans = "",
                inlineBoxes = "",
                lineBreakSpans = "",
                inlineObjects = "1\u001d5\u001d18.0\u001d14.4\u001d4.32",
            )
        }
        assertContains(e.message ?: "", "InvalidInlineObjectRange")
    }

    @Test
    fun planWithDiagnosticsEmbedsTheExactPlanJson() {
        val text = "你好世界"
        val plan = plan(text)
        val envelope = planWithDiagnostics(text, zeroAdvanceEpsilonPx = 0.01)
        val expected = "{\"plan\":${plan.escapedAsJsonString()}," +
            "\"diagnostics\":{\"capabilityIssues\":[],\"advanceSuspects\":[]}}"
        assertEquals(expected, envelope)
    }

    @Test
    fun planWithDiagnosticsCarriesCapabilityIssueFacts() {
        val text = "你好世界"
        val wrapped = DiagnosticWrappingTextShaper(shaper) {
            it.copy(capabilityIssue = "UnverifiedDisplaySubstitutionCoverage")
        }
        val envelope = planWithDiagnostics(text, zeroAdvanceEpsilonPx = 0.01, shaper = wrapped)
        assertContains(
            envelope,
            "{\"name\":\"UnverifiedDisplaySubstitutionCoverage\"," +
                "\"reason\":\"ExplainableStubTextShaper:nominal-em-advance\"," +
                "\"rangeStart\":0,\"rangeEnd\":1",
        )
        assertContains(envelope, "\"advanceSuspects\":[]")
    }

    @Test
    fun planWithDiagnosticsCarriesNonFiniteAdvanceAsString() {
        val text = "你好世界"
        val wrapped = DiagnosticWrappingTextShaper(shaper) {
            it.copy(advance = Float.NaN)
        }
        val envelope = planWithDiagnostics(text, zeroAdvanceEpsilonPx = 0.01, shaper = wrapped)
        assertContains(
            envelope,
            "{\"displayText\":\"你\",\"advance\":\"NaN\"," +
                "\"reason\":\"ExplainableStubTextShaper:nominal-em-advance\"," +
                "\"rangeStart\":0,\"rangeEnd\":1",
        )
    }

    @Test
    fun planWithDiagnosticsThresholdFlagsSuspects() {
        val text = "你好世界"
        val allSuspects = planWithDiagnostics(text, zeroAdvanceEpsilonPx = 1e9)
        val parsedAll = kotlin.js.JSON.parse<dynamic>(allSuspects)
        val suspectCount = (parsedAll.diagnostics.advanceSuspects as Array<dynamic>).size
        assertEquals(4, suspectCount)
        val noneSuspects = planWithDiagnostics(text, zeroAdvanceEpsilonPx = 0.0)
        val parsedNone = kotlin.js.JSON.parse<dynamic>(noneSuspects)
        val noneCount = (parsedNone.diagnostics.advanceSuspects as Array<dynamic>).size
        assertEquals(0, noneCount)
    }

    @Test
    fun decorationsAloneEnableRenderEvidenceAndEmitEmphasisRanges() {
        val envelope = planWithDiagnostics(
            text = "你好世界",
            zeroAdvanceEpsilonPx = 0.01,
            decorations = "0\u001d2\u001dEmphasis",
        )
        assertContains(envelope, "\\\"emphasisRanges\\\":[[0,2]]")
        assertContains(envelope, "\\\"emphasisDots\\\":[")
    }

    @Test
    fun emphasisDotGapEmDefaultAndExplicitOverride() {
        val defaultEnvelope = planWithDiagnostics(
            text = "你好世界",
            zeroAdvanceEpsilonPx = 0.01,
            decorations = "0\u001d2\u001dEmphasis",
            emphasisDotGapEm = null,
        )
        val customEnvelope = planWithDiagnostics(
            text = "你好世界",
            zeroAdvanceEpsilonPx = 0.01,
            decorations = "0\u001d2\u001dEmphasis",
            emphasisDotGapEm = 0.5,
        )
        assertContains(defaultEnvelope, "\\\"emphasisRanges\\\":[[0,2]]")
        assertContains(customEnvelope, "\\\"emphasisRanges\\\":[[0,2]]")
        val defaultAnchorY = defaultEnvelope.substringAfter("\\\"anchorY\\\":").substringBefore(",")
        val customAnchorY = customEnvelope.substringAfter("\\\"anchorY\\\":").substringBefore(",")
        assertFalse(defaultEnvelope == customEnvelope)
        assertFalse(defaultAnchorY == customAnchorY)
    }

    @Test
    fun invalidEmphasisDotGapEmThrows() {
        val eNeg = assertFailsWith<IllegalArgumentException> {
            planWithDiagnostics(
                text = "你好世界",
                zeroAdvanceEpsilonPx = 0.01,
                emphasisDotGapEm = -0.1,
            )
        }
        assertContains(eNeg.message ?: "", "InvalidEmphasisDotGapEm")

        val eNan = assertFailsWith<IllegalArgumentException> {
            planWithDiagnostics(
                text = "你好世界",
                zeroAdvanceEpsilonPx = 0.01,
                emphasisDotGapEm = Double.NaN,
            )
        }
        assertContains(eNan.message ?: "", "InvalidEmphasisDotGapEm")
    }

    @Test
    fun invalidDecorationWireFieldCountThrowsInvalidDecorationWire() {
        val e = assertFailsWith<IllegalArgumentException> {
            planWithDiagnostics(
                text = "你好世界",
                zeroAdvanceEpsilonPx = 0.01,
                decorations = "0\u001d2",
            )
        }
        assertContains(e.message ?: "", "InvalidDecorationWire")
    }

    @Test
    fun invalidDecorationWireUnknownKindThrows() {
        assertFailsWith<IllegalArgumentException> {
            planWithDiagnostics(
                text = "你好世界",
                zeroAdvanceEpsilonPx = 0.01,
                decorations = "0\u001d2\u001dUnknownKind",
            )
        }
    }

    @Test
    fun invalidDecorationWireRangeOutOfBoundsThrowsInvalidDecorationRange() {
        val e = assertFailsWith<IllegalArgumentException> {
            planWithDiagnostics(
                text = "你好世界",
                zeroAdvanceEpsilonPx = 0.01,
                decorations = "0\u001d10\u001dEmphasis",
            )
        }
        assertContains(e.message ?: "", "InvalidDecorationRange")
    }

    // The span only swaps the font family, so the natural plan stays
    // byte-identical to the plain form; only the evidence sections differ.
    private val paintOnlySpan =
        "0\u001d4\u001d\u001fSource Han Sans SC\u001d16.0\u001d400\u001dfalse\u001d0.0"

    @Test
    fun planOmittedOverrideKeepsWireDerivedEvidence() {
        val plain = plan("你好世界")
        assertFalse(plain.contains("\"fontSize\":"))
        val styled = plan("你好世界", textSpans = paintOnlySpan)
        assertContains(styled, "\"fontSize\":16")
        assertContains(styled, "\"overlayWidth\"")
        assertContains(styled, "\"style\":{}")
    }

    @Test
    fun planOverrideTrueOnPlainInputAddsEvidenceSections() {
        val result = plan("你好世界", renderEvidenceOverride = true)
        assertContains(result, "\"fontSize\":16")
        assertContains(result, "\"overlayWidth\"")
    }

    @Test
    fun planOverrideFalseOnRichInputRemovesEvidenceSections() {
        val plain = plan("你好世界")
        val stripped = plan("你好世界", textSpans = paintOnlySpan, renderEvidenceOverride = false)
        assertEquals(plain, stripped)
        assertFalse(stripped.contains("\"style\":{"))
    }

    @Test
    fun planWithDiagnosticsOmittedOverrideKeepsWireDerivedEvidence() {
        val plain = planWithDiagnostics("你好世界", zeroAdvanceEpsilonPx = 0.01)
        assertFalse(plain.contains("\\\"fontSize\\\""))
        val styled = planWithDiagnostics("你好世界", zeroAdvanceEpsilonPx = 0.01, textSpans = paintOnlySpan)
        assertContains(styled, "\\\"fontSize\\\":16")
        assertContains(styled, "\\\"overlayWidth\\\"")
    }

    @Test
    fun planWithDiagnosticsOverrideTrueOnPlainInputAddsEvidenceSections() {
        val envelope = planWithDiagnostics("你好世界", zeroAdvanceEpsilonPx = 0.01, renderEvidenceOverride = true)
        assertContains(envelope, "\\\"fontSize\\\":16")
        assertContains(envelope, "\\\"overlayWidth\\\"")
    }

    @Test
    fun planWithDiagnosticsOverrideFalseOnRichInputRemovesEvidenceSections() {
        val plain = planWithDiagnostics("你好世界", zeroAdvanceEpsilonPx = 0.01)
        val stripped = planWithDiagnostics(
            "你好世界",
            zeroAdvanceEpsilonPx = 0.01,
            textSpans = paintOnlySpan,
            renderEvidenceOverride = false,
        )
        assertEquals(plain, stripped)
        assertFalse(stripped.contains("\\\"style\\\"{"))
    }
}

/**
 * Test double that runs [delegate] and rewrites every shaping decision so a
 * test can force a [capabilityIssue] or a non-finite/zero advance through the
 * real layout pipeline without inventing a shaper from scratch.
 */
private class DiagnosticWrappingTextShaper(
    private val delegate: ExplainableStubTextShaper,
    private val transform: (ShapingDecisionInfo) -> ShapingDecisionInfo,
) : TextShaper {
    override fun shape(input: ShapingInput): ShapingResult {
        val result = delegate.shape(input)
        return result.copy(decisions = result.decisions.map(transform))
    }
}

/** Local mirror of the wire codec's JSON string escaper for building expected envelopes. */
private fun String.escapedAsJsonString(): String = buildString {
    append('"')
    for (char in this@escapedAsJsonString) {
        when (char) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000c' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (char.code < 0x20) {
                append("\\u").append(char.code.toString(16).padStart(4, '0'))
            } else {
                append(char)
            }
        }
    }
    append('"')
}
