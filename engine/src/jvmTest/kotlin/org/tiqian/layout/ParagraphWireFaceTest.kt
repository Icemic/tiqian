package org.tiqian.layout

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

class ParagraphWireFaceTest {

    private val shaper = ExplainableStubTextShaper()
    private val metrics = StubFontMetricsResolver()
    private val face = ParagraphWireFace(textShaper = shaper, fontMetricsResolver = metrics)

    private fun plan(
        text: String,
        shaper: TextShaper = this.shaper,
    ): String =
        ParagraphWireFace(textShaper = shaper, fontMetricsResolver = metrics).plan(
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
            textSpans = "",
            inlineBoxes = "",
            lineBreakSpans = "",
        )

    private fun planWithDiagnostics(
        text: String,
        zeroAdvanceEpsilonPx: Double,
        shaper: TextShaper = this.shaper,
    ): String =
        ParagraphWireFace(textShaper = shaper, fontMetricsResolver = metrics).planWithDiagnostics(
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
            textSpans = "",
            inlineBoxes = "",
            lineBreakSpans = "",
            zeroAdvanceEpsilonPx = zeroAdvanceEpsilonPx,
        )

    @Test
    fun emptyTextThrowsEmptyParagraph() {
        val e = assertFailsWith<IllegalArgumentException> {
            face.plan(text = "", maxWidthPx = 400.0, fontFamilies = "\u001fNoto Sans CJK SC",
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
            face.plan(
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
        val result = face.plan(
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
            face.plan(
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
        val result = face.plan(
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
        val result = face.plan(
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
            face.plan(
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
            face.plan(
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
        val suspectCount = allSuspects.split("\"advance\":\"16.0\"").size - 1
        assertEquals(4, suspectCount)
        val noneSuspects = planWithDiagnostics(text, zeroAdvanceEpsilonPx = 0.0)
        assertContains(noneSuspects, "\"advanceSuspects\":[]")
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

/** Local mirror of the wire face's JSON string escaper for building expected envelopes. */
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
