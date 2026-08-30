package org.tiqian.layout

import org.tiqian.clreq.ClreqProfile
import org.tiqian.core.InlineObjectSpan
import org.tiqian.core.TextRange
import org.tiqian.font.CjkFontRoleClassifier
import org.tiqian.font.FontRole
import org.tiqian.font.FontRoleContext
import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.assertFalse
import org.tiqian.test.trace.assertTrue
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

// A lone surrogate written inside a string literal is replaced with '?' when
// the JS test bundle re-serializes its sources, so inputs that carry one are
// built from char codes at runtime to keep the code unit intact everywhere.
private fun surrogateText(vararg codes: Int): String =
    CharArray(codes.size) { codes[it].toChar() }.concatToString()

/**
 * Walker arms the corpus fixtures cannot reach: a supplementary variation
 * selector feeding the mark and selector helpers through their astral
 * bounds, the low-surrogate range test on the char that follows a lone
 * high surrogate, a span boundary between a Latin run and a following
 * point mark so the whitespace-neighbour arm of the attached-mark check
 * runs, a ZWJ member inside a modifier-base cluster so the modifier walk
 * breaks below the modifier range, and an inline object over the CR of a
 * CRLF pair, which walks the mandatory-break helper onto the LF while a
 * CR still sits behind it.
 */
class ClusterRoleResolutionSurrogateAndExtenderEdgeTest {
    private val testTrace = TestTraceRecorder("ClusterRoleResolutionSurrogateAndExtenderEdgeTest")


    private val classifier = CjkFontRoleClassifier()
    private val context = FontRoleContext()
    private val profile = ClreqProfile.MainlandHorizontal

    @Test
    fun astralVariationSelectorExtendsTheRunBeforeIt() {
        testTrace.section("astralVariationSelectorExtendsTheRunBeforeIt")
        // U+E0100 fails the combining-mark helper through its BMP bound and
        // passes the variation-selector helper through its supplementary
        // range, so the CJK run swallows both surrogate halves.
        val result = clusterRoleRanges(
            "中\uDB40\uDD00中",
            classifier,
            context,
            profile,
            emptySet(),
            emptySet(),
        )
        assertEquals(2, result.size, result.toString())
        assertEquals(TextRange(0, 3), result[0].range)
        assertEquals(FontRole.CjkText, result[0].role)
        assertEquals(TextRange(3, 4), result[1].range)
    }

    @Test
    fun astralVariationSelectorAfterAnAttachedPointMarkEndsTheRun() {
        testTrace.section("astralVariationSelectorAfterAnAttachedPointMarkEndsTheRun")
        // The comma cluster qualifies as an attached ASCII point mark, so
        // its point-mark extension loop feeds U+E0100 to the point-mark
        // helper, whose BMP bound rejects the astral selector and ends the
        // point-mark run there; the generic extender loop then absorbs the
        // selector into the same cluster.
        val result = clusterRoleRanges(
            "中,\uDB40\uDD00中",
            classifier,
            context,
            profile,
            emptySet(),
            emptySet(),
        )
        assertEquals(3, result.size, result.toString())
        assertEquals(TextRange(1, 4), result[1].range)
        assertEquals(FontRole.LatinText, result[1].role)
    }

    @Test
    fun astralVariationSelectorBetweenBaseAndModifierKeepsTheSequence() {
        testTrace.section("astralVariationSelectorBetweenBaseAndModifierKeepsTheSequence")
        // Inside the emoji modifier walk the supplementary selector is not
        // a combining mark, so the walk only stays on it through the
        // selector test and then breaks at the real modifier.
        val result = clusterRoleRanges(
            "✊\uDB40\uDD00🏻",
            classifier,
            context,
            profile,
            emptySet(),
            emptySet(),
        )
        assertEquals(1, result.size, result.toString())
        assertEquals(TextRange(0, 5), result[0].range)
        assertEquals(FontRole.Emoji, result[0].role)
    }

    @Test
    fun codePointAboveTheSupplementarySelectorRangeStandsAlone() {
        testTrace.section("codePointAboveTheSupplementarySelectorRangeStandsAlone")
        // U+E01F0 sits one past the supplementary selector range's end: the
        // selector helper fails it on the upper comparison, so unlike the
        // in-range selector it never extends the run before it.
        val result = clusterRoleRanges(
            "中\uDB40\uDDF0中",
            classifier,
            context,
            profile,
            emptySet(),
            emptySet(),
        )
        assertEquals(3, result.size, result.toString())
        assertEquals(TextRange(0, 1), result[0].range)
        assertEquals(TextRange(1, 3), result[1].range)
        assertEquals(TextRange(3, 4), result[2].range)
    }

    @Test
    fun modifierBaseWithABmpSelectorWalksTheSelectorTrueArm() {
        testTrace.section("modifierBaseWithABmpSelectorWalksTheSelectorTrueArm")
        // The BMP selector U+FE0F is the only code point family for which the
        // selector helper answers through its BMP range: the walk consumes it
        // and then reads the real modifier behind it.
        val result = clusterRoleRanges(
            "\u270A\uFE0F🏻",
            classifier,
            context,
            profile,
            emptySet(),
            emptySet(),
        )
        assertEquals(1, result.size, result.toString())
        assertEquals(TextRange(0, 4), result[0].range)
        assertEquals(FontRole.Emoji, result[0].role)
    }

    @Test
    fun modifierBaseWithOnlyASelectorEndsTheWalkAtTheClusterEnd() {
        testTrace.section("modifierBaseWithOnlyASelectorEndsTheWalkAtTheClusterEnd")
        // The walk skips the supplementary selector and then runs out of
        // cluster with no modifier behind it, so the end check answers
        // false before the modifier range is ever read.
        val result = clusterRoleRanges(
            "✊\uDB40\uDD00",
            classifier,
            context,
            profile,
            emptySet(),
            emptySet(),
        )
        assertEquals(1, result.size, result.toString())
        assertEquals(TextRange(0, 3), result[0].range)
    }

    @Test
    fun zwjMemberInsideAModifierBaseClusterBreaksTheWalkBelowTheRange() {
        testTrace.section("zwjMemberInsideAModifierBaseClusterBreaksTheWalkBelowTheRange")
        // The gender ZWJ sequence starts from a modifier base, so the walk
        // runs and breaks at the joiner, whose code point sits below the
        // modifier range instead of inside it.
        val result = clusterRoleRanges(
            "✊\u200D♀️",
            classifier,
            context,
            profile,
            emptySet(),
            emptySet(),
        )
        assertEquals(1, result.size, result.toString())
        assertEquals(TextRange(0, 4), result[0].range)
    }

    @Test
    fun highSurrogateBeforePrivateUseKeepsTheLoneHalf() {
        testTrace.section("highSurrogateBeforePrivateUseKeepsTheLoneHalf")
        // The char after the high surrogate sits above the low-surrogate
        // range, so the compat reader answers the lone half and the next
        // cluster starts at the private-use char itself.
        val result = clusterRoleRanges(
            surrogateText(0xD83D, 0xE000, 0x4E2D),
            classifier,
            context,
            profile,
            emptySet(),
            emptySet(),
        )
        assertEquals(3, result.size, result.toString())
        assertEquals(TextRange(0, 1), result[0].range)
        assertEquals(TextRange(1, 2), result[1].range)
    }

    @Test
    fun highSurrogateBeforePlainBmpKeepsTheLoneHalf() {
        testTrace.section("highSurrogateBeforePlainBmpKeepsTheLoneHalf")
        // A BMP char after the high surrogate fails the low-surrogate range
        // on its lower comparison: the lone half answers again.
        val result = clusterRoleRanges(
            surrogateText(0xD83D, 0x4E2D),
            classifier,
            context,
            profile,
            emptySet(),
            emptySet(),
        )
        assertEquals(2, result.size, result.toString())
        assertEquals(TextRange(0, 1), result[0].range)
        assertEquals(TextRange(1, 2), result[1].range)
    }

    @Test
    fun spanBoundaryAfterASpaceLetThePointMarkSeeItsWhitespaceNeighbour() {
        testTrace.section("spanBoundaryAfterASpaceLetThePointMarkSeeItsWhitespaceNeighbour")
        // Without the boundary the Latin run would swallow the comma. The
        // span cut ends the run on the space, so the attached-mark check
        // for the comma reads a whitespace char as the previous run's last
        // char and rejects the attachment.
        val result = clusterRoleRanges(
            "a ,",
            classifier,
            context,
            profile,
            setOf(2),
            emptySet(),
        )
        assertEquals(2, result.size, result.toString())
        assertEquals(TextRange(0, 2), result[0].range)
        assertEquals(TextRange(2, 3), result[1].range)
    }

    @Test
    fun inlineObjectOverTheCrWalksTheLfWithACrBehindIt() {
        testTrace.section("inlineObjectOverTheCrWalksTheLfWithACrBehindIt")
        // The object consumes the CR, so the walker lands on the LF and the
        // mandatory-break helper sees a CR at the previous index: the guard
        // suppresses the second break the CRLF pair would otherwise cause.
        val result = clusterRoleRanges(
            "\r\n",
            classifier,
            context,
            profile,
            emptySet(),
            emptySet(),
            inlineObjectsByStart = mapOf(
                0 to InlineObjectSpan(TextRange(0, 1), 8.0f, 8.0f, 8.0f),
            ),
        )
        assertEquals(2, result.size, result.toString())
        assertEquals(TextRange(0, 1), result[0].range)
        assertEquals(TextRange(1, 2), result[1].range)
        assertFalse(result[1].mandatoryBreak, result.toString())
        assertTrue(result.none { it.mandatoryBreak }, result.toString())
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
