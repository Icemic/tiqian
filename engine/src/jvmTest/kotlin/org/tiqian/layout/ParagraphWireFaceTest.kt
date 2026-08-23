package org.tiqian.layout

import org.tiqian.font.StubFontMetricsResolver
import org.tiqian.shaping.ExplainableStubTextShaper
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class ParagraphWireFaceTest {

    private val shaper = ExplainableStubTextShaper()
    private val metrics = StubFontMetricsResolver()
    private val face = ParagraphWireFace(textShaper = shaper, fontMetricsResolver = metrics)

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
}
