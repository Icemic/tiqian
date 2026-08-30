package org.tiqian.layout

import org.tiqian.core.Ic
import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LayoutInput
import org.tiqian.core.ParagraphStyle
import org.tiqian.core.TextRange
import org.tiqian.core.TiqianTextContent
import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

/**
 * `VerbatimRangeAutoSpace` (issue #5): CJK↔Western boundaries strictly inside a verbatim
 * range (inline code, technical text) receive no automatic spacing, while the range's
 * outer edges keep the normal prose autospace contract.
 */
class VerbatimRangeAutoSpaceTest {
    private val testTrace = TestTraceRecorder("VerbatimRangeAutoSpaceTest")


    private fun layout(text: String, suppressed: List<TextRange>) =
        ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent(
                    text = text,
                    autoSpaceSuppressedRanges = suppressed,
                ),
                constraints = LayoutConstraints(maxWidth = 640f),
            ),
        )

    @Test
    fun internalBoundariesAreSuppressedAndOuterEdgesKeepTheGap() {
        testTrace.section("internalBoundariesAreSuppressedAndOuterEdgesKeepTheGap")
        val text = "跑print你好print跑"
        val control = layout(text, suppressed = emptyList())
        assertEquals(
            4,
            control.debug.autoSpaceDecisions.count {
                it.reason == "TextAutoSpaceInsert:east-asian-spacing-W-N"
            },
            "$control.debug.autoSpaceDecisions",
        )

        val result = layout(text, suppressed = listOf(TextRange(1, 13)))
        val decisions = result.debug.autoSpaceDecisions
        assertEquals(
            2,
            decisions.count { it.reason == "TextAutoSpaceInsert:east-asian-spacing-W-N" },
            "$decisions",
        )
        assertEquals(
            2,
            decisions.count {
                it.reason == "VerbatimRangeAutoSpace:east-asian-spacing-W-N-suppressed"
            },
            "$decisions",
        )
    }

    @Test
    fun typedSpaceInsideAVerbatimRangeIsNotNormalised() {
        testTrace.section("typedSpaceInsideAVerbatimRangeIsNotNormalised")
        val text = "跑a 你b跑"
        val control = layout(text, suppressed = emptyList())
        assertEquals(
            1,
            control.debug.autoSpaceDecisions.count {
                it.reason == "TextAutoSpaceReplace:east-asian-spacing-W-space-N"
            },
            "$control.debug.autoSpaceDecisions",
        )

        val result = layout(text, suppressed = listOf(TextRange(1, 5)))
        assertEquals(
            0,
            result.debug.autoSpaceDecisions.count {
                it.reason == "TextAutoSpaceReplace:east-asian-spacing-W-space-N"
            },
            "$result.debug.autoSpaceDecisions",
        )
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
