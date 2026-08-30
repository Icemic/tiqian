package org.tiqian.layout

import org.tiqian.clreq.GlueSide
import org.tiqian.clreq.InteriorPunctuationStyle
import org.tiqian.clreq.PunctuationGluePlacement
import org.tiqian.clreq.PunctuationWidthPolicy
import org.tiqian.clreq.glueSideFor
import org.tiqian.clreq.PunctuationClass
import org.tiqian.core.Rect
import org.tiqian.core.TextRange
import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.assertFailsWith
import org.tiqian.test.trace.assertFalse
import org.tiqian.test.trace.assertNotNull
import org.tiqian.test.trace.assertNull
import org.tiqian.test.trace.assertTrue
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

/**
 * Coverage for PunctuationModel.kt: Glue validation, the spacing compressor's
 * named heuristics (CollapseAdjacentPunctuationInnerGlue,
 * CollapseCjkClosingBeforeAsciiPointMark) and every geometry source of
 * PunctuationAtomBuilder (font halt, ink bounds, synthesized full-width
 * placement, profile fallback). All expectations are derived from the policy
 * tables at em = 16: full-width marks advance 1.0em with a 0.5em body.
 */
class PunctuationModelCoverageTest {
    private val testTrace = TestTraceRecorder("PunctuationModelCoverageTest")


    private val em = 16.0f
    private val builder = PunctuationAtomBuilder()

    private fun glue(natural: Float): Glue = Glue(
        kind = GlueKind.PunctuationTrailing,
        min = 0.0f,
        natural = natural,
        max = natural,
        priority = 0,
        penalty = 0,
    )

    private fun atomFor(
        char: Char,
        startIndex: Int = 0,
        inkInput: PunctuationInkInput? = null,
    ): PunctuationAtom? = builder.build(
        char = char,
        range = TextRange(startIndex, startIndex + 1),
        em = em,
        inkInput = inkInput,
    )

    @Test
    fun glueRejectsInvertedBounds() {
        testTrace.section("glueRejectsInvertedBounds")
        assertFailsWith<IllegalArgumentException> {
            Glue(GlueKind.PunctuationTrailing, min = 2.0f, natural = 1.0f, max = 3.0f, priority = 0, penalty = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            Glue(GlueKind.PunctuationTrailing, min = 0.0f, natural = 3.0f, max = 1.0f, priority = 0, penalty = 0)
        }
    }

    @Test
    fun adjustmentOpportunityCarriesRangeAndGlue() {
        testTrace.section("adjustmentOpportunityCarriesRangeAndGlue")
        val opportunity = AdjustmentOpportunity(TextRange(1, 2), glue(4.0f))
        assertEquals(TextRange(1, 2), opportunity.range)
        assertEquals(4.0f, opportunity.glue.natural)
    }

    @Test
    fun compressionResultSumsAdjustmentReductions() {
        testTrace.section("compressionResultSumsAdjustmentReductions")
        val result = PunctuationSpacingCompressionResult(
            listOf(
                PunctuationSpacingAdjustment(
                    range = TextRange(0, 2),
                    reductionTargetRange = TextRange(0, 1),
                    leftChar = '。',
                    rightChar = '「',
                    naturalInnerGlue = 16.0f,
                    adjustedInnerGlue = 8.0f,
                    reduction = 8.0f,
                    reason = "test-a",
                ),
                PunctuationSpacingAdjustment(
                    range = TextRange(2, 4),
                    reductionTargetRange = TextRange(2, 3),
                    leftChar = '，',
                    rightChar = '「',
                    naturalInnerGlue = 8.0f,
                    adjustedInnerGlue = 4.0f,
                    reduction = 4.0f,
                    reason = "test-b",
                ),
            ),
        )
        assertEquals(12.0f, result.totalReduction)
        assertEquals(0.0f, PunctuationSpacingCompressionResult(emptyList()).totalReduction)
    }

    @Test
    fun adjacentPunctuationInnerGlueCollapsesByHalfEm() {
        testTrace.section("adjacentPunctuationInnerGlueCollapsesByHalfEm")
        val stop = atomFor('。', 0)!!
        val opening = atomFor('「', 1)!!
        // 。 trailing glue 8 + 「 leading glue 8 = 16 natural inner glue.
        // One half em (8) is removed; the wider side is the reduction target.
        val result = PunctuationSpacingCompressor().compress(listOf(stop, opening), em)
        assertEquals(1, result.adjustments.size)
        val adjustment = result.adjustments.single()
        assertEquals(16.0f, adjustment.naturalInnerGlue)
        assertEquals(8.0f, adjustment.adjustedInnerGlue)
        assertEquals(8.0f, adjustment.reduction)
        assertEquals(stop.range, adjustment.reductionTargetRange)
        assertEquals('。', adjustment.leftChar)
        assertEquals('「', adjustment.rightChar)
        assertEquals("collapse-adjacent-punctuation-inner-glue", adjustment.reason)
        assertEquals(TextRange(0, 2), adjustment.range)
    }

    @Test
    fun adjacentPunctuationTargetsTheWiderSide() {
        testTrace.section("adjacentPunctuationTargetsTheWiderSide")
        val stop = atomFor('。', 0)!!.copy(trailingGlueInitiallyConsumed = 8.0f)
        val opening = atomFor('「', 1)!!
        // 0 remaining trailing on the left, 8 leading on the right: the right
        // side is the wider side, so the reduction targets it.
        val result = PunctuationSpacingCompressor().compress(listOf(stop, opening), em)
        assertEquals(opening.range, result.adjustments.single().reductionTargetRange)
    }

    @Test
    fun adjacentPunctuationSkipsNonAdjacentZeroGlueAndZeroEm() {
        testTrace.section("adjacentPunctuationSkipsNonAdjacentZeroGlueAndZeroEm")
        val stop = atomFor('。', 0)!!
        val opening = atomFor('「', 2)!!
        // Ranges (0,1) and (2,3) are not adjacent: no adjustment.
        assertTrue(
            PunctuationSpacingCompressor().compress(listOf(stop, opening), em).adjustments.isEmpty(),
        )

        // Initially consumed glue on both sides removes the natural inner glue.
        val consumed = stop.copy(trailingGlueInitiallyConsumed = 8.0f)
        val adjacentOpening = atomFor('「', 1)!!.copy(leadingGlueInitiallyConsumed = 8.0f)
        assertTrue(
            PunctuationSpacingCompressor().compress(listOf(consumed, adjacentOpening), em).adjustments.isEmpty(),
        )

        // Fewer than two atoms produce no adjustments.
        assertTrue(PunctuationSpacingCompressor().compress(listOf(stop), em).adjustments.isEmpty())

        // em = 0 removes the collapse amount, so every reduction is zero.
        val liveStop = atomFor('。', 0)!!
        val liveOpening = atomFor('「', 1)!!
        assertTrue(
            PunctuationSpacingCompressor().compress(listOf(liveStop, liveOpening), 0.0f).adjustments.isEmpty(),
        )
    }

    @Test
    fun cjkClosingBeforeAsciiPointMarkCollapsesTrailingGlue() {
        testTrace.section("cjkClosingBeforeAsciiPointMarkCollapsesTrailingGlue")
        val closing = atomFor('」', 0)!!
        val result = PunctuationSpacingCompressor()
            .compressCjkClosingBeforeAsciiPointMark(listOf(closing), "」, rest", em)
        val adjustment = result.adjustments.single()
        assertEquals(TextRange(0, 2), adjustment.range)
        assertEquals(closing.range, adjustment.reductionTargetRange)
        assertEquals(8.0f, adjustment.naturalInnerGlue)
        assertEquals(0.0f, adjustment.adjustedInnerGlue)
        assertEquals('」', adjustment.leftChar)
        assertEquals(',', adjustment.rightChar)
        assertEquals("collapse-cjk-closing-before-ascii-point-mark", adjustment.reason)
    }

    @Test
    fun cjkClosingCompressionRejectsNonMatchingNeighbours() {
        testTrace.section("cjkClosingCompressionRejectsNonMatchingNeighbours")
        val compressor = PunctuationSpacingCompressor()
        // Opening marks are not Closing: skipped.
        val opening = atomFor('「', 0)!!
        assertTrue(compressor.compressCjkClosingBeforeAsciiPointMark(listOf(opening), "「, x", em).adjustments.isEmpty())
        // A closing mark at text end has no right character.
        val closingAtEnd = atomFor('」', 0)!!
        assertTrue(compressor.compressCjkClosingBeforeAsciiPointMark(listOf(closingAtEnd), "」", em).adjustments.isEmpty())
        // A CJK right character is not an ASCII point mark.
        assertTrue(
            compressor.compressCjkClosingBeforeAsciiPointMark(listOf(closingAtEnd), "」中", em).adjustments.isEmpty(),
        )
        // Fully consumed trailing glue has no natural inner glue left.
        val consumed = closingAtEnd.copy(trailingGlueInitiallyConsumed = 8.0f)
        assertTrue(
            compressor.compressCjkClosingBeforeAsciiPointMark(listOf(consumed), "」,x", em).adjustments.isEmpty(),
        )
        // em = 0 makes the reduction zero.
        assertTrue(
            compressor.compressCjkClosingBeforeAsciiPointMark(listOf(closingAtEnd), "」,x", 0.0f).adjustments.isEmpty(),
        )
    }

    @Test
    fun indexedBuildRejectsOutOfRangeIndex() {
        testTrace.section("indexedBuildRejectsOutOfRangeIndex")
        assertNull(builder.build("，", 5, em))
        val atom = builder.build("，", 0, em)
        assertNotNull(atom)
        assertEquals(TextRange(0, 1), atom!!.range)
        assertEquals('，', atom.char)
    }

    @Test
    fun nonPunctuationCharactersProduceNoAtom() {
        testTrace.section("nonPunctuationCharactersProduceNoAtom")
        assertNull(atomFor('中'))
        assertNull(atomFor('a'))
    }

    @Test
    fun policyFallbackSplitsGlueByClassSide() {
        testTrace.section("policyFallbackSplitsGlueByClassSide")
        val stop = atomFor('，')!!
        // PauseOrStop under MainlandSimplified is trailing-only.
        assertEquals(0.0f, stop.leadingGlue.natural)
        assertEquals(8.0f, stop.trailingGlue.natural)
        assertEquals(PunctuationAnchor.Leading, stop.anchor)
        assertEquals(8.0f, stop.bodyWidth)
        assertEquals("ProfileGlueFallbackWithoutFontGeometry", stop.geometrySource)
        assertNull(stop.haltAdvance)
        assertNull(stop.inkContainmentBodyFloor)
        assertFalse(stop.inkContainmentApplied)
        assertNull(stop.inkBoundsFallback)
        assertNull(stop.haltValidation)

        val opening = atomFor('「')!!
        assertEquals(8.0f, opening.leadingGlue.natural)
        assertEquals(0.0f, opening.trailingGlue.natural)
        assertEquals(PunctuationAnchor.Trailing, opening.anchor)

        // Traditional placement moves every class to both sides.
        val traditional = builder.build(
            char = '，',
            range = TextRange(0, 1),
            em = em,
            inkInput = null,
            gluePlacement = PunctuationGluePlacement.Traditional,
        )!!
        assertEquals(4.0f, traditional.leadingGlue.natural)
        assertEquals(4.0f, traditional.trailingGlue.natural)
        assertEquals(PunctuationAnchor.Center, traditional.anchor)
    }

    @Test
    fun underwidthGlyphsExpandIntoFullWidthCellByClassSide() {
        testTrace.section("underwidthGlyphsExpandIntoFullWidthCellByClassSide")
        // Opening marks receive the missing width before their box.
        val opening = atomFor('「', inkInput = PunctuationInkInput(advance = 8.0f))!!
        assertEquals(8.0f, opening.glyphInlineShift)
        assertEquals("UnderwidthPunctuationFullWidthBoxPlacement", opening.glyphPlacementReason)
        assertEquals(8.0f, opening.advanceExpansion)
        assertEquals(16.0f, opening.advance)

        // Both-side classes split the expansion; the atom records the full
        // expansion while the shift places only the leading half.
        val middleDot = atomFor('·', inkInput = PunctuationInkInput(advance = 8.0f))!!
        assertEquals(4.0f, middleDot.glyphInlineShift)
        assertEquals(8.0f, middleDot.advanceExpansion)

        // Trailing-side classes keep the glyph at the pen.
        val closing = atomFor('」', inkInput = PunctuationInkInput(advance = 8.0f))!!
        assertEquals(0.0f, closing.glyphInlineShift)
        assertNull(closing.glyphPlacementReason)
        assertEquals(8.0f, closing.advanceExpansion)

        // A full-width shaped advance has nothing to synthesize.
        val exact = atomFor('「', inkInput = PunctuationInkInput(advance = 16.0f))!!
        assertEquals(0.0f, exact.glyphInlineShift)
        assertNull(exact.glyphPlacementReason)
        assertEquals(0.0f, exact.advanceExpansion)
    }

    @Test
    fun haltFittedCompressionUsesFontMeasurements() {
        testTrace.section("haltFittedCompressionUsesFontMeasurements")
        // -2.0em/8 halt placement: 2 leading, 6 trailing requested and both
        // fit inside the ink, so the font's halt metrics are accepted as-is.
        val atom = atomFor(
            '·',
            inkInput = PunctuationInkInput(
                advance = 16.0f,
                inkBounds = Rect(2.0f, 4.0f, 10.0f, 12.0f),
                haltAdvance = 8.0f,
                haltPlacementX = -2.0f,
            ),
        )!!
        assertEquals("FontHaltFittedBodyCompression", atom.geometrySource)
        assertEquals(2.0f, atom.leadingGlue.natural)
        assertEquals(6.0f, atom.trailingGlue.natural)
        assertEquals(8.0f, atom.bodyWidth)
        assertEquals(PunctuationAnchor.Center, atom.anchor)
        assertEquals(8.0f, atom.haltAdvance)
        assertNull(atom.haltValidation)
        assertFalse(atom.inkContainmentApplied)
        assertNotNull(atom.inkContainmentBodyFloor)
    }

    @Test
    fun haltTrimIsLimitedByInkBoundsAndRecordsWhy() {
        testTrace.section("haltTrimIsLimitedByInkBoundsAndRecordsWhy")
        // The ink reaches to 14.0 but halt asks for 6 trailing: the trailing
        // trim is limited and the NamedDecision records the limitation.
        val atom = atomFor(
            '·',
            inkInput = PunctuationInkInput(
                advance = 16.0f,
                inkBounds = Rect(2.0f, 4.0f, 14.0f, 12.0f),
                haltAdvance = 8.0f,
                haltPlacementX = -2.0f,
            ),
        )!!
        assertEquals(2.0f, atom.leadingGlue.natural)
        assertEquals(2.0f, atom.trailingGlue.natural)
        assertTrue(atom.inkContainmentApplied)
        assertEquals("halt-trim-limited-by-default-ink-bounds", atom.haltValidation)
    }

    @Test
    fun haltAdvanceWithoutPlacementFallsBackToFittedInkOrProfile() {
        testTrace.section("haltAdvanceWithoutPlacementFallsBackToFittedInkOrProfile")
        // halt advance + ink bounds but no halt placement: the fitted frame
        // places the body by ink instead of the font's halt shift.
        val withInk = atomFor(
            '·',
            inkInput = PunctuationInkInput(
                advance = 16.0f,
                inkBounds = Rect(8.0f, 4.0f, 16.0f, 12.0f),
                haltAdvance = 8.0f,
            ),
        )!!
        assertEquals("FontHaltAdvanceWithInkBoundsFittedPlacement", withInk.geometrySource)

        // halt advance without ink bounds: the profile fallback splits it.
        val withoutInk = atomFor(
            '，',
            inkInput = PunctuationInkInput(advance = 16.0f, haltAdvance = 8.0f),
        )!!
        assertEquals("FontHaltAdvanceWithProfileFallback", withoutInk.geometrySource)
        assertEquals(0.0f, withoutInk.leadingGlue.natural)
        assertEquals(8.0f, withoutInk.trailingGlue.natural)
    }

    @Test
    fun haltFromProportionalGlyphIsRejected() {
        testTrace.section("haltFromProportionalGlyphIsRejected")
        // The shaped advance (8) is narrower than the policy cell (16), so a
        // halt measured on that glyph is not a half-width form of the cell.
        val atom = atomFor(
            '「',
            inkInput = PunctuationInkInput(advance = 8.0f, haltAdvance = 4.0f, haltPlacementX = -2.0f),
        )!!
        assertNull(atom.haltAdvance)
        assertEquals(8.0f, atom.glyphInlineShift)
        assertEquals("UnderwidthPunctuationFullWidthBoxPlacement", atom.glyphPlacementReason)
    }

    @Test
    fun inkBoundsFittedFramePicksTheNarrowestContainingAnchor() {
        testTrace.section("inkBoundsFittedFramePicksTheNarrowestContainingAnchor")
        // Ink hugging the right edge: the body keeps its trailing edge, so
        // all removable glue sits before it.
        val rightInk = atomFor('」', inkInput = PunctuationInkInput(advance = 16.0f, inkBounds = Rect(8.0f, 4.0f, 16.0f, 12.0f)))!!
        assertEquals(PunctuationAnchor.Trailing, rightInk.anchor)
        assertEquals(8.0f, rightInk.leadingGlue.natural)
        assertEquals(0.0f, rightInk.trailingGlue.natural)
        assertEquals("InkBoundsFittedBodyCompression", rightInk.geometrySource)
        assertFalse(rightInk.inkContainmentApplied)

        // Ink hugging the left edge: the body keeps its leading edge, so the
        // removable glue trails it.
        val leftInk = atomFor('「', inkInput = PunctuationInkInput(advance = 16.0f, inkBounds = Rect(0.0f, 4.0f, 8.0f, 12.0f)))!!
        assertEquals(PunctuationAnchor.Leading, leftInk.anchor)
        assertEquals(0.0f, leftInk.leadingGlue.natural)
        assertEquals(8.0f, leftInk.trailingGlue.natural)

        // Ink wider than the target body on both sides: the centred frame is
        // the narrowest that still contains it, and the containment floor is
        // recorded.
        val wideInk = atomFor('」', inkInput = PunctuationInkInput(advance = 16.0f, inkBounds = Rect(1.0f, 4.0f, 15.0f, 12.0f)))!!
        assertTrue(wideInk.inkContainmentApplied)
        assertEquals(14.0f, wideInk.inkContainmentBodyFloor)
    }

    @Test
    fun forcedHalfWidthConnectorsConsumeGlueUpFront() {
        testTrace.section("forcedHalfWidthConnectorsConsumeGlueUpFront")
        // Short hyphens already occupy half an em in the policy tables, so
        // the fixed-half body has no glue to consume up front.
        val hyphen = atomFor('-')!!
        assertEquals(8.0f, hyphen.advance)
        assertEquals(8.0f, hyphen.bodyWidth)
        assertTrue(hyphen.geometrySource.endsWith("FixedHalfWidth"))
        assertEquals(0.0f, hyphen.leadingGlueInitiallyConsumed)
        assertEquals(0.0f, hyphen.trailingGlueInitiallyConsumed)

        // GB fixed separators force a full-width separator down to half width;
        // the removed half em becomes glue consumed before line breaking.
        val dot = builder.build(
            char = '·',
            range = TextRange(0, 1),
            em = em,
            inkInput = null,
            widthPolicy = PunctuationWidthPolicy(gbFixedSeparators = true),
        )!!
        assertTrue(dot.geometrySource.endsWith("FixedHalfWidth"))
        assertEquals(4.0f, dot.leadingGlueInitiallyConsumed)
        assertEquals(4.0f, dot.trailingGlueInitiallyConsumed)

        // Kaiming style halves the interior marks but keeps sentence stops.
        val kaiming = builder.build(
            char = '，',
            range = TextRange(0, 1),
            em = em,
            inkInput = null,
            widthPolicy = PunctuationWidthPolicy(interior = InteriorPunctuationStyle.Kaiming),
        )!!
        assertTrue(kaiming.geometrySource.endsWith("FixedHalfWidth"))
        assertEquals(8.0f, kaiming.trailingGlueInitiallyConsumed)
        val kaimingStop = builder.build(
            char = '。',
            range = TextRange(0, 1),
            em = em,
            inkInput = null,
            widthPolicy = PunctuationWidthPolicy(interior = InteriorPunctuationStyle.Kaiming),
        )!!
        assertFalse(kaimingStop.geometrySource.endsWith("FixedHalfWidth"))
    }

    @Test
    fun inkInputRecordsWhyBoundsAreMissing() {
        testTrace.section("inkInputRecordsWhyBoundsAreMissing")
        // The stage's ink resolver sets the reason; the builder records it
        // verbatim whenever ink bounds are absent.
        val noInk = atomFor(
            '，',
            inkInput = PunctuationInkInput(advance = 16.0f, boundsFallbackReason = "shaper-no-ink-bounds"),
        )!!
        assertEquals("shaper-no-ink-bounds", noInk.inkBoundsFallback)
        assertNull(noInk.inkBounds)

        val ambiguous = atomFor('，', inkInput = PunctuationInkInput(advance = 0.0f, boundsFallbackReason = "glyph-cluster-mapping-ambiguous"))!!
        assertEquals("glyph-cluster-mapping-ambiguous", ambiguous.inkBoundsFallback)
        // Zero advance keeps the policy advance.
        assertEquals(16.0f, ambiguous.advance)
    }

    @Test
    fun glueSideForMainlandSimplifiedMapsClassesToSides() {
        testTrace.section("glueSideForMainlandSimplifiedMapsClassesToSides")
        assertEquals(GlueSide.LeadingOnly, PunctuationGluePlacement.MainlandSimplified.glueSideFor(PunctuationClass.Opening))
        assertEquals(GlueSide.TrailingOnly, PunctuationGluePlacement.MainlandSimplified.glueSideFor(PunctuationClass.Closing))
        assertEquals(GlueSide.TrailingOnly, PunctuationGluePlacement.MainlandSimplified.glueSideFor(PunctuationClass.PauseOrStop))
        assertEquals(GlueSide.BothSides, PunctuationGluePlacement.MainlandSimplified.glueSideFor(PunctuationClass.MiddleDot))
        assertEquals(GlueSide.BothSides, PunctuationGluePlacement.Traditional.glueSideFor(PunctuationClass.Opening))
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
