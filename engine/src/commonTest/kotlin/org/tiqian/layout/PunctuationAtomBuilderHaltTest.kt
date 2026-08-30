package org.tiqian.layout

import org.tiqian.clreq.InteriorPunctuationStyle
import org.tiqian.clreq.PunctuationGluePlacement
import org.tiqian.clreq.PunctuationWidthPolicy
import org.tiqian.core.Rect
import org.tiqian.core.TextRange
import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.assertNull
import org.tiqian.test.trace.assertTrue
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

/** Font-evidence punctuation compression contract (ADR 0014 amendment). */
class PunctuationAtomBuilderHaltTest {
    private val testTrace = TestTraceRecorder("PunctuationAtomBuilderHaltTest")


    private val builder = PunctuationAtomBuilder()
    private val em = 16f

    @Test
    fun haltAdvanceWithoutPlacementUsesNamedProfileFallback() {
        testTrace.section("haltAdvanceWithoutPlacementUsesNamedProfileFallback")
        val atom = builder.build(
            char = '。',
            range = TextRange(0, 1),
            em = em,
            inkInput = PunctuationInkInput(advance = 16f, haltAdvance = 7.5f),
        )!!

        assertEquals(7.5f, atom.bodyWidth)
        assertEquals(7.5f, atom.haltAdvance)
        assertEquals(0f, atom.leadingGlue.natural)
        assertEquals(8.5f, atom.trailingGlue.natural)
        assertEquals("FontHaltAdvanceWithProfileFallback", atom.geometrySource)
    }

    @Test
    fun haltPlacementDirectlyDefinesBothCompressionSides() {
        testTrace.section("haltPlacementDirectlyDefinesBothCompressionSides")
        val atom = builder.build(
            char = '（',
            range = TextRange(0, 1),
            em = em,
            inkInput = PunctuationInkInput(
                advance = 16f,
                inkBounds = Rect(left = 5f, top = -12f, right = 11f, bottom = 2f),
                haltAdvance = 8f,
                haltPlacementX = -4f,
            ),
        )!!

        assertEquals(4f, atom.leadingGlue.natural)
        assertEquals(4f, atom.trailingGlue.natural)
        assertEquals(8f, atom.bodyWidth)
        assertEquals(PunctuationAnchor.Center, atom.anchor)
        assertEquals("FontHaltFittedBodyCompression", atom.geometrySource)
        assertNull(atom.haltValidation)
    }

    @Test
    fun haltPlacementOverridesRegionalProfileDirection() {
        testTrace.section("haltPlacementOverridesRegionalProfileDirection")
        val traditional = PunctuationAtomBuilder(PunctuationGluePlacement.Traditional)
        val atom = traditional.build(
            char = '。',
            range = TextRange(0, 1),
            em = em,
            inkInput = PunctuationInkInput(
                advance = 16f,
                inkBounds = Rect(left = 1f, top = -4f, right = 7f, bottom = 1f),
                haltAdvance = 8f,
                haltPlacementX = 0f,
            ),
        )!!

        assertEquals(0f, atom.leadingGlue.natural)
        assertEquals(8f, atom.trailingGlue.natural)
        assertNull(atom.haltValidation)
    }

    @Test
    fun defaultInkCapsAHaltTrimThatWouldCutIntoThePaintedGlyph() {
        testTrace.section("defaultInkCapsAHaltTrimThatWouldCutIntoThePaintedGlyph")
        val atom = builder.build(
            char = '（',
            range = TextRange(0, 1),
            em = em,
            inkInput = PunctuationInkInput(
                advance = 16f,
                inkBounds = Rect(left = 2f, top = -12f, right = 15f, bottom = 2f),
                haltAdvance = 8f,
                haltPlacementX = -8f,
            ),
        )!!

        assertEquals(2f, atom.leadingGlue.natural)
        assertEquals(0f, atom.trailingGlue.natural)
        assertEquals(14f, atom.bodyWidth)
        assertEquals("halt-trim-limited-by-default-ink-bounds", atom.haltValidation)
        assertTrue(atom.inkContainmentApplied)
    }

    @Test
    fun equalHaltAdvanceFallsThroughToInkBounds() {
        testTrace.section("equalHaltAdvanceFallsThroughToInkBounds")
        val atom = builder.build(
            char = '，',
            range = TextRange(0, 1),
            em = em,
            inkInput = PunctuationInkInput(
                advance = 16f,
                inkBounds = Rect(left = 6f, top = -4f, right = 10f, bottom = 1f),
                haltAdvance = 16f,
            ),
        )!!

        assertNull(atom.haltAdvance)
        assertEquals(4f, atom.leadingGlue.natural)
        assertEquals(4f, atom.trailingGlue.natural)
        assertEquals("InkBoundsFittedBodyCompression", atom.geometrySource)
    }

    @Test
    fun microsoftYaheiCentredCommaCompressesFromBothSides() {
        testTrace.section("microsoftYaheiCentredCommaCompressesFromBothSides")
        // Microsoft YaHei Vista: advance=2048, comma ink x=821..1130.
        val atom = fontUnitAtom('，', 2048f, 821f, 1130f)

        assertEquals(8f, atom.bodyWidth, 0.001f)
        assertEquals(4f, atom.leadingGlue.natural, 0.01f)
        assertEquals(4f, atom.trailingGlue.natural, 0.01f)
        assertEquals(PunctuationAnchor.Center, atom.anchor)
        assertEquals(0f, atom.glyphInlineShift)
    }

    @Test
    fun microsoftYaheiBottomLeftStopKeepsItsLeadingSafetyMargin() {
        testTrace.section("microsoftYaheiBottomLeftStopKeepsItsLeadingSafetyMargin")
        // Microsoft YaHei Vista: advance=2048, ideographic full stop ink x=131..632.
        val atom = fontUnitAtom('。', 2048f, 131f, 632f)

        assertEquals(8f, atom.bodyWidth)
        assertEquals(0f, atom.leadingGlue.natural)
        assertEquals(8f, atom.trailingGlue.natural)
        assertEquals(PunctuationAnchor.Leading, atom.anchor)
        // Fully compressed drawX is unchanged, so the font's left safety margin survives.
        assertEquals(0f, atom.glyphInlineShift)
    }

    @Test
    fun founderHeitiCentredParenthesesStayMirrorImages() {
        testTrace.section("founderHeitiCentredParenthesesStayMirrorImages")
        // Founder Heiti: （ ink=456..647, ） ink=353..544, advance=1000.
        val opening = fontUnitAtom('（', 1000f, 456f, 647f)
        val closing = fontUnitAtom('）', 1000f, 353f, 544f)

        assertEquals(opening.leadingGlue.natural, closing.trailingGlue.natural, 0.001f)
        assertEquals(opening.trailingGlue.natural, closing.leadingGlue.natural, 0.001f)
        assertTrue(opening.leadingGlue.natural > 0f && opening.trailingGlue.natural > 0f)
        assertEquals(0f, opening.glyphInlineShift)
        assertEquals(0f, closing.glyphInlineShift)
    }

    @Test
    fun underwidthOpeningQuoteCompletesTheLeadingSideOfItsFullWidthCell() {
        testTrace.section("underwidthOpeningQuoteCompletesTheLeadingSideOfItsFullWidthCell")
        val atom = builder.build(
            char = '“',
            range = TextRange(0, 1),
            em = em,
            inkInput = PunctuationInkInput(
                advance = 6f,
                inkBounds = Rect(left = 1f, top = -10f, right = 5f, bottom = 0f),
            ),
        )!!

        assertEquals(16f, atom.advance)
        assertEquals(10f, atom.advanceExpansion)
        assertEquals(8f, atom.bodyWidth, 0.001f)
        assertEquals(8f, atom.leadingGlue.natural)
        assertEquals(0f, atom.trailingGlue.natural)
        assertEquals(10f, atom.glyphInlineShift)
        assertEquals("UnderwidthPunctuationFullWidthBoxPlacement", atom.glyphPlacementReason)
    }

    @Test
    fun fixedHalfConsumesMeasuredSidebearingsInsteadOfApplyingAProfileShift() {
        testTrace.section("fixedHalfConsumesMeasuredSidebearingsInsteadOfApplyingAProfileShift")
        val atom = builder.build(
            char = '《',
            range = TextRange(0, 1),
            em = em,
            inkInput = PunctuationInkInput(
                advance = 16f,
                inkBounds = Rect(left = 6.5f, top = -12f, right = 15.5f, bottom = 2f),
            ),
            widthPolicy = PunctuationWidthPolicy(interior = InteriorPunctuationStyle.Kaiming),
        )!!

        assertEquals(16f, atom.advance)
        assertEquals(9.5f, atom.bodyWidth)
        assertEquals(6.5f, atom.leadingGlueInitiallyConsumed)
        assertEquals(0f, atom.trailingGlueInitiallyConsumed)
        assertEquals(0f, atom.glyphInlineShift)
        assertEquals("InkBoundsFittedBodyCompressionFixedHalfWidth", atom.geometrySource)
    }

    @Test
    fun overhangReducesCompressionCapacityWithoutMovingInk() {
        testTrace.section("overhangReducesCompressionCapacityWithoutMovingInk")
        val atom = builder.build(
            char = '《',
            range = TextRange(0, 1),
            em = em,
            inkInput = PunctuationInkInput(
                advance = 16f,
                inkBounds = Rect(left = 6.5f, top = -12f, right = 17f, bottom = 2f),
            ),
        )!!

        assertEquals(17f, atom.advance)
        assertEquals(10.5f, atom.bodyWidth)
        assertEquals(6.5f, atom.leadingGlue.natural)
        assertEquals(0f, atom.trailingGlue.natural)
        assertEquals(0f, atom.glyphInlineShift)
        assertTrue(atom.inkContainmentApplied)
    }

    private fun fontUnitAtom(char: Char, unitsPerEm: Float, left: Float, right: Float): PunctuationAtom =
        builder.build(
            char = char,
            range = TextRange(0, 1),
            em = em,
            inkInput = PunctuationInkInput(
                advance = em,
                inkBounds = Rect(
                    left = left / unitsPerEm * em,
                    top = -12f,
                    right = right / unitsPerEm * em,
                    bottom = 2f,
                ),
            ),
        )!!

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
