package org.tiqian.layout

import org.tiqian.core.InlineBoxSpan
import org.tiqian.core.InlineObjectBoundaryAdjustment
import org.tiqian.core.InlineObjectSpan
import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LayoutInput
import org.tiqian.core.LineBreakPolicy
import org.tiqian.core.LineBreakSpan
import org.tiqian.core.ParagraphStyle
import org.tiqian.core.TextRange
import org.tiqian.core.TiqianTextContent
import kotlin.test.Test
import org.tiqian.test.trace.assertFailsWith
import org.tiqian.test.trace.assertTrue
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

/**
 * Covers every rejection arm of [ExplainableStubParagraphLayoutEngine]'s input
 * validation. Each test drives one `require` through its failure branch and
 * checks the message names the offending field.
 */
class ParagraphLayoutEngineValidationCoverageTest {
    private val testTrace = TestTraceRecorder("ParagraphLayoutEngineValidationCoverageTest")


    private val engine = ExplainableStubParagraphLayoutEngine()

    private fun input(
        paragraphStyle: ParagraphStyle = ParagraphStyle(),
        inlineBoxes: List<InlineBoxSpan> = emptyList(),
        inlineObjects: List<InlineObjectSpan> = emptyList(),
        content: TiqianTextContent = TiqianTextContent("甲乙"),
    ): LayoutInput = LayoutInput(
        content = content,
        paragraphStyle = paragraphStyle,
        constraints = LayoutConstraints(maxWidth = 100.0f),
        inlineBoxes = inlineBoxes,
        inlineObjects = inlineObjects,
    )

    private fun inlineObject(
        range: TextRange = TextRange(0, 1),
        advance: Float = 10.0f,
        ascent: Float = 8.0f,
        descent: Float = 2.0f,
        leading: InlineObjectBoundaryAdjustment = InlineObjectBoundaryAdjustment.Fixed,
        trailing: InlineObjectBoundaryAdjustment = InlineObjectBoundaryAdjustment.Fixed,
    ): InlineObjectSpan = InlineObjectSpan(range, advance, ascent, descent, leading, trailing)

    private fun expectRejection(bad: LayoutInput, fragment: String) {
        val error = assertFailsWith<IllegalArgumentException> { engine.layout(bad) }
        assertTrue(error.message!!.contains(fragment), error.message)
    }

    @Test
    fun emphasisDotGapEmMustBeFiniteAndNonNegative() {
        testTrace.section("emphasisDotGapEmMustBeFiniteAndNonNegative")
        expectRejection(
            input(paragraphStyle = ParagraphStyle(emphasisDotGapEm = Float.NaN)),
            "emphasisDotGapEm",
        )
        expectRejection(
            input(paragraphStyle = ParagraphStyle(emphasisDotGapEm = -0.1f)),
            "emphasisDotGapEm",
        )
    }

    @Test
    fun inlineObjectMinimumClearanceEmMustBeFiniteAndNonNegative() {
        testTrace.section("inlineObjectMinimumClearanceEmMustBeFiniteAndNonNegative")
        expectRejection(
            input(paragraphStyle = ParagraphStyle(inlineObjectMinimumClearanceEm = Float.NaN)),
            "inlineObjectMinimumClearanceEm",
        )
        expectRejection(
            input(paragraphStyle = ParagraphStyle(inlineObjectMinimumClearanceEm = -1.0f)),
            "inlineObjectMinimumClearanceEm",
        )
    }

    @Test
    fun sourceTextMustNotContainUnpairedSurrogates() {
        testTrace.section("sourceTextMustNotContainUnpairedSurrogates")
        // A lone surrogate written inside a string literal is replaced with
        // '?' when the JS test bundle re-serializes its sources, so the
        // malformed texts are assembled from char codes at runtime.
        fun unpairedText(vararg codes: Int): TiqianTextContent =
            TiqianTextContent(CharArray(codes.size) { codes[it].toChar() }.concatToString())
        expectRejection(input(content = unpairedText(0x4E2D, 0xD800)), "unpaired high surrogate")
        expectRejection(input(content = unpairedText(0x4E2D, 0xDC00)), "unpaired low surrogate")
        // A high surrogate followed by another high surrogate and one
        // followed by a regular char above the low-surrogate range take the
        // two different exits of the range check.
        expectRejection(input(content = unpairedText(0xD800, 0xD800, 0xDC00)), "unpaired high surrogate")
        expectRejection(input(content = unpairedText(0xD800, 0xF900)), "unpaired high surrogate")
    }

    @Test
    fun inlineBoxSpanMustBeANonEmptyInBoundsRange() {
        testTrace.section("inlineBoxSpanMustBeANonEmptyInBoundsRange")
        expectRejection(
            input(inlineBoxes = listOf(InlineBoxSpan(TextRange(0, 0)))),
            "non-empty source range",
        )
        expectRejection(
            input(inlineBoxes = listOf(InlineBoxSpan(TextRange(1, 9)))),
            "non-empty source range",
        )
    }

    @Test
    fun inlineBoxSpanMustHaveFiniteInlineEdges() {
        testTrace.section("inlineBoxSpanMustHaveFiniteInlineEdges")
        expectRejection(
            input(inlineBoxes = listOf(InlineBoxSpan(TextRange(0, 1), inlineStart = Float.NaN))),
            "finite inline edges",
        )
        expectRejection(
            input(inlineBoxes = listOf(InlineBoxSpan(TextRange(0, 1), inlineEnd = Float.POSITIVE_INFINITY))),
            "finite inline edges",
        )
    }

    @Test
    fun lineBreakSpansMustBeNonEmptyInBoundsRanges() {
        testTrace.section("lineBreakSpansMustBeNonEmptyInBoundsRanges")
        expectRejection(
            input(
                content = TiqianTextContent(
                    "甲乙",
                    lineBreakSpans = listOf(LineBreakSpan(TextRange(0, 0), LineBreakPolicy.ProgressiveTechnical)),
                ),
            ),
            "LineBreakSpan",
        )
        expectRejection(
            input(
                content = TiqianTextContent(
                    "甲乙",
                    lineBreakSpans = listOf(LineBreakSpan(TextRange(2, 3), LineBreakPolicy.ProgressiveTechnical)),
                ),
            ),
            "LineBreakSpan",
        )
    }

    @Test
    fun autoSpaceSuppressedRangesMustBeNonEmptyInBounds() {
        testTrace.section("autoSpaceSuppressedRangesMustBeNonEmptyInBounds")
        expectRejection(
            input(
                content = TiqianTextContent("甲乙", autoSpaceSuppressedRanges = listOf(TextRange(1, 1))),
            ),
            "Auto-space suppressed range",
        )
        expectRejection(
            input(
                content = TiqianTextContent("甲乙", autoSpaceSuppressedRanges = listOf(TextRange(0, 8))),
            ),
            "Auto-space suppressed range",
        )
    }

    @Test
    fun inlineObjectRangesMustBeUnique() {
        testTrace.section("inlineObjectRangesMustBeUnique")
        expectRejection(
            input(
                inlineObjects = listOf(
                    inlineObject(TextRange(0, 1)),
                    inlineObject(TextRange(0, 1)),
                ),
            ),
            "unique",
        )
    }

    @Test
    fun inlineObjectRangesMustNotOverlap() {
        testTrace.section("inlineObjectRangesMustNotOverlap")
        expectRejection(
            input(
                inlineObjects = listOf(
                    inlineObject(TextRange(0, 2)),
                    inlineObject(TextRange(1, 2)),
                ),
            ),
            "overlap",
        )
    }

    @Test
    fun inlineObjectMustCoverANonEmptyInBoundsRange() {
        testTrace.section("inlineObjectMustCoverANonEmptyInBoundsRange")
        expectRejection(
            input(inlineObjects = listOf(inlineObject(TextRange(1, 1)))),
            "non-empty source range",
        )
        expectRejection(
            input(inlineObjects = listOf(inlineObject(TextRange(0, 9)))),
            "non-empty source range",
        )
    }

    @Test
    fun inlineObjectMustHaveFinitePositiveGeometry() {
        testTrace.section("inlineObjectMustHaveFinitePositiveGeometry")
        expectRejection(
            input(inlineObjects = listOf(inlineObject(advance = 0.0f))),
            "finite positive geometry",
        )
        expectRejection(
            input(inlineObjects = listOf(inlineObject(advance = Float.NaN))),
            "finite positive geometry",
        )
        expectRejection(
            input(inlineObjects = listOf(inlineObject(ascent = -1.0f))),
            "finite positive geometry",
        )
        expectRejection(
            input(inlineObjects = listOf(inlineObject(ascent = Float.NaN))),
            "finite positive geometry",
        )
        expectRejection(
            input(inlineObjects = listOf(inlineObject(descent = Float.NaN))),
            "finite positive geometry",
        )
        expectRejection(
            input(inlineObjects = listOf(inlineObject(descent = -1.0f))),
            "finite positive geometry",
        )
    }

    @Test
    fun inlineObjectLeadingBoundaryMustBeFixed() {
        testTrace.section("inlineObjectLeadingBoundaryMustBeFixed")
        expectRejection(
            input(
                inlineObjects = listOf(
                    inlineObject(
                        leading = InlineObjectBoundaryAdjustment(shrinkCapacity = 0.5f),
                    ),
                ),
            ),
            "cannot shrink its leading boundary",
        )
        expectRejection(
            input(
                inlineObjects = listOf(
                    inlineObject(
                        leading = InlineObjectBoundaryAdjustment(lineEndDiscardableAdvance = 0.5f),
                    ),
                ),
            ),
            "cannot discard advance at its leading boundary",
        )
    }

    @Test
    fun inlineObjectTrailingBoundaryMustNotExceedAdvance() {
        testTrace.section("inlineObjectTrailingBoundaryMustNotExceedAdvance")
        expectRejection(
            input(
                inlineObjects = listOf(
                    inlineObject(
                        trailing = InlineObjectBoundaryAdjustment(shrinkCapacity = 10.5f),
                    ),
                ),
            ),
            "trailing shrink capacity",
        )
        expectRejection(
            input(
                inlineObjects = listOf(
                    inlineObject(
                        trailing = InlineObjectBoundaryAdjustment(lineEndDiscardableAdvance = 10.5f),
                    ),
                ),
            ),
            "trailing line-end discard",
        )
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
