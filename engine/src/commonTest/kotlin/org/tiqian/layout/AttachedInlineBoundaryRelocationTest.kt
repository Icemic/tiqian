package org.tiqian.layout

import org.tiqian.core.InlineAttachment
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
import org.tiqian.test.trace.assertNull
import org.tiqian.test.trace.assertTrue
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

class AttachedInlineVirtualAdjacencyTest {
    private val testTrace = TestTraceRecorder("AttachedInlineVirtualAdjacencyTest")

    @Test
    fun attachedRunExposesTheProseClustersOnItsTwoSides() {
        testTrace.section("attachedRunExposesTheProseClustersOnItsTwoSides")
        val result = resolveAttachedInlineVirtualBoundaries(
            inlineAttachments = listOf(
                InlineAttachment.None,
                InlineAttachment.None,
                InlineAttachment.Previous,
                InlineAttachment.Previous,
                InlineAttachment.Previous,
                InlineAttachment.None,
            ),
        )

        assertEquals(1, result.single().previousClusterIndex)
        assertEquals(2..4, result.single().attachedClusterRange)
        assertEquals(5, result.single().nextClusterIndex)
    }

    @Test
    fun attachedRunAtParagraphEndHasNoVirtualRightNeighbor() {
        testTrace.section("attachedRunAtParagraphEndHasNoVirtualRightNeighbor")
        val result = resolveAttachedInlineVirtualBoundaries(
            inlineAttachments = listOf(
                InlineAttachment.None,
                InlineAttachment.None,
                InlineAttachment.Previous,
                InlineAttachment.Previous,
                InlineAttachment.Previous,
            ),
        )

        assertNull(result.single().nextClusterIndex)
    }

    @Test
    fun punctuationAfterFootnoteIsJudgedAgainstThePrecedingPunctuation() {
        testTrace.section("punctuationAfterFootnoteIsJudgedAgainstThePrecedingPunctuation")
        val result = layoutAttachedReference("正文：“内容。”[1]，后文")
        val virtualBoundary = result.debug.spacingDecisions.single {
            it.reason.startsWith("AttachedInlineVirtualPunctuationBoundary")
        }

        assertEquals("AttachedInlineVirtualPunctuationBoundary:adjacent-punctuation", virtualBoundary.reason)
        assertTrue(virtualBoundary.naturalInnerGlue > 0f)
        assertEquals(0f, virtualBoundary.adjustedInnerGlue)
    }

    @Test
    fun closingQuoteBeforeFootnoteAndBodyKeepsItsNaturalTrailingGlue() {
        testTrace.section("closingQuoteBeforeFootnoteAndBodyKeepsItsNaturalTrailingGlue")
        val result = layoutAttachedReference("正文：“内容。”[1]后文")
        val virtualBoundary = result.debug.spacingDecisions.single {
            it.reason.startsWith("AttachedInlineVirtualPunctuationBoundary")
        }

        assertEquals("AttachedInlineVirtualPunctuationBoundary:natural", virtualBoundary.reason)
        assertEquals(virtualBoundary.naturalInnerGlue, virtualBoundary.adjustedInnerGlue)
        assertTrue(virtualBoundary.adjustedInnerGlue > 0f)
    }

    @Test
    fun closingQuoteBeforeParagraphEndFootnoteHasNoTrailingGlue() {
        testTrace.section("closingQuoteBeforeParagraphEndFootnoteHasNoTrailingGlue")
        val result = layoutAttachedReference("正文：“内容。”[1]")
        val virtualBoundary = result.debug.spacingDecisions.single {
            it.reason.startsWith("AttachedInlineVirtualPunctuationBoundary")
        }

        assertEquals("AttachedInlineVirtualPunctuationBoundary:line-end", virtualBoundary.reason)
        assertEquals(0f, virtualBoundary.adjustedInnerGlue)
    }

    @Test
    fun attachedReferenceNeverStartsAWrappedLine() {
        testTrace.section("attachedReferenceNeverStartsAWrappedLine")
        val text = "甲乙1丙"
        val referenceRange = TextRange(2, 3)
        for (lineBreaker in listOf(GreedyLineBreaker(), LookaheadLineBreaker(), ParagraphDpLineBreaker())) {
            val result = ExplainableStubParagraphLayoutEngine(lineBreaker = lineBreaker).layout(
                LayoutInput(
                    paragraphStyle = ParagraphStyle(firstLineIndent = Ic.Zero),
                    content = TiqianTextContent(
                        text = text,
                        spans = listOf(
                            TextSpan(
                                range = referenceRange,
                                style = TextStyle(inlineAttachment = InlineAttachment.Previous),
                            ),
                        ),
                    ),
                    constraints = LayoutConstraints(maxWidth = 32f),
                ),
            )

            assertTrue(result.lines.size > 1, "${lineBreaker.strategyName}: test must wrap: ${result.lines}")
            assertTrue(
                result.lines.none { it.range.start in referenceRange.start until referenceRange.end },
                "${lineBreaker.strategyName}: attached reference started a line: ${result.lines.map { it.range }}",
            )
            assertTrue(
                result.lines.any { line ->
                    line.range.start < referenceRange.start && line.range.end >= referenceRange.end
                },
                "${lineBreaker.strategyName}: reference detached from prose: ${result.lines.map { it.range }}",
            )
        }
    }

    private fun layoutAttachedReference(text: String) =
        ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic.Zero),
                content = TiqianTextContent(
                    text = text,
                    spans = listOf(
                        TextSpan(
                            range = text.indexOf("[1]").let { TextRange(it, it + 3) },
                            style = TextStyle(inlineAttachment = InlineAttachment.Previous),
                        ),
                    ),
                ),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
