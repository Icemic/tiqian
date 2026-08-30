package org.tiqian.layout

import org.tiqian.core.Ic
import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LayoutInput
import org.tiqian.core.ParagraphStyle
import org.tiqian.core.TextRange
import org.tiqian.core.TextSpan
import org.tiqian.core.TextStyle
import org.tiqian.core.TiqianTextContent
import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

class BaselineAlignmentTest {
    private val testTrace = TestTraceRecorder("BaselineAlignmentTest")


    @Test
    fun latinInsideCjkUsesSharedRomanBaseline() {
        testTrace.section("latinInsideCjkUsesSharedRomanBaseline")
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                content = TiqianTextContent("中A文"),
                constraints = LayoutConstraints(maxWidth = 400f),
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
            ),
        )

        val latin = result.clusters.single { it.text == "A" }
        assertEquals(0f, latin.baselineShift, "Latin mixed into CJK should use the shared Roman baseline")
    }

    @Test
    fun explicitBaselineShiftAppliesToRomanClusters() {
        testTrace.section("explicitBaselineShiftAppliesToRomanClusters")
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                content = TiqianTextContent(
                    text = "中A文",
                    spans = listOf(TextSpan(TextRange(1, 2), TextStyle(baselineShift = -6f))),
                ),
                constraints = LayoutConstraints(maxWidth = 400f),
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
            ),
        )

        val latin = result.clusters.single { it.text == "A" }
        assertEquals(-6f, latin.baselineShift, 0.001f)
    }

    @Test
    fun cjkPunctuationProvidesIdeographicReferenceWithoutHanBody() {
        testTrace.section("cjkPunctuationProvidesIdeographicReferenceWithoutHanBody")
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                content = TiqianTextContent("MacBook。"),
                constraints = LayoutConstraints(maxWidth = 400f),
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
            ),
        )

        val punctuation = result.clusters.single { it.text == "。" }
        assertEquals(
            0f,
            punctuation.baselineShift,
            "CJK punctuation carries an IdeographicEmBox and must not be aligned to Latin raw descent",
        )
    }

    @Test
    fun cjkMixedSizesAlignByIdeographicBoxBottom() {
        testTrace.section("cjkMixedSizesAlignByIdeographicBoxBottom")
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                content = TiqianTextContent(
                    text = "中小大",
                    spans = listOf(
                        TextSpan(TextRange(1, 2), TextStyle(fontSize = 12f)),
                        TextSpan(TextRange(2, 3), TextStyle(fontSize = 20f)),
                    ),
                ),
                constraints = LayoutConstraints(maxWidth = 400f),
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
            ),
        )

        val base = result.clusters.single { it.text == "中" }
        val small = result.clusters.single { it.text == "小" }
        val large = result.clusters.single { it.text == "大" }
        assertEquals(0f, base.baselineShift)
        assertEquals(0.48f, small.baselineShift, 0.01f)
        assertEquals(-0.48f, large.baselineShift, 0.01f)
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
