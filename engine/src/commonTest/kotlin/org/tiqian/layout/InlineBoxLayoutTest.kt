package org.tiqian.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.tiqian.core.Ic
import org.tiqian.core.InlineBoxSpan
import org.tiqian.core.InlineBoxOuterSpacing
import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LayoutInput
import org.tiqian.core.LineLengthGrid
import org.tiqian.core.ParagraphStyle
import org.tiqian.core.TextRange
import org.tiqian.core.TiqianTextContent
import org.tiqian.core.positionedClusters

class InlineBoxLayoutTest {
    @Test
    fun inlineEdgesReserveAdvanceAndMoveTheGlyphOrigin() {
        val engine = ExplainableStubParagraphLayoutEngine()
        val paragraphStyle = ParagraphStyle(
            firstLineIndent = Ic(0f),
            lineLengthGrid = LineLengthGrid(enabled = false),
        )
        val plain = engine.layout(
            LayoutInput(
                content = TiqianTextContent("中。"),
                constraints = LayoutConstraints(maxWidth = 400f),
                paragraphStyle = paragraphStyle,
            ),
        )
        val boxed = engine.layout(
            LayoutInput(
                content = TiqianTextContent("中。"),
                constraints = LayoutConstraints(maxWidth = 400f),
                paragraphStyle = paragraphStyle,
                inlineBoxes = listOf(
                    InlineBoxSpan(
                        TextRange(1, 2),
                        inlineStart = 3f,
                        inlineEnd = 5f,
                        outerSpacing = InlineBoxOuterSpacing.Source,
                    ),
                ),
            ),
        )

        val plainStop = plain.clusters.single { it.range == TextRange(1, 2) }
        val boxedStop = boxed.clusters.single { it.range == TextRange(1, 2) }
        assertEquals(plainStop.advance + 8f, boxedStop.advance, 0.001f)
        assertEquals(3f, boxedStop.leadingLayoutAdvance, 0.001f)

        val positioned = boxed.positionedClusters().single { it.range == TextRange(1, 2) }
        assertEquals(positioned.left + 3f, positioned.drawX, 0.001f)
        assertEquals(1, boxed.debug.inlineBoxDecisions.size)
        assertEquals("InlineBoxBoundaryAdvance", boxed.debug.inlineBoxDecisions.single().reason)
    }

    @Test
    fun everyNarrowInlineBoxGetsOuterAutospaceWithoutRoleSpecificCode() {
        val engine = ExplainableStubParagraphLayoutEngine()
        val paragraphStyle = ParagraphStyle(
            firstLineIndent = Ic(0f),
            lineLengthGrid = LineLengthGrid(enabled = false),
        )
        fun layout(outerSpacing: InlineBoxOuterSpacing) = engine.layout(
            LayoutInput(
                content = TiqianTextContent("中./中"),
                constraints = LayoutConstraints(maxWidth = 400f),
                paragraphStyle = paragraphStyle,
                inlineBoxes = listOf(
                    InlineBoxSpan(
                        TextRange(1, 3),
                        inlineStart = 3f,
                        inlineEnd = 5f,
                        outerSpacing = outerSpacing,
                    ),
                ),
            ),
        )

        val boxed = layout(InlineBoxOuterSpacing.Narrow)
        assertEquals(
            setOf(
                "InlineBoxOuterAutoSpace:leading-W-N",
                "InlineBoxOuterAutoSpace:trailing-N-W",
            ),
            boxed.debug.autoSpaceDecisions.map { it.reason }.toSet(),
        )
        assertTrue(boxed.debug.autoSpaceDecisions.all { it.boundaryRole == "InlineBox.Narrow" })
        assertEquals("Narrow", boxed.debug.inlineBoxDecisions.single().outerSpacing)

        val sourceWrapped = layout(InlineBoxOuterSpacing.Source)
        assertTrue(sourceWrapped.debug.autoSpaceDecisions.isEmpty())
        assertEquals("Source", sourceWrapped.debug.inlineBoxDecisions.single().outerSpacing)
    }
}
