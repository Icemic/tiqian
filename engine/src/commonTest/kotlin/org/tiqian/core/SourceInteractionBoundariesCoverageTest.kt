package org.tiqian.core

import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

// A lone surrogate written inside a string literal is replaced with '?' when
// the JS test bundle re-serializes its sources, so inputs that carry one are
// built from char codes at runtime to keep the code unit intact everywhere.
private fun surrogateText(vararg codes: Int): String =
    CharArray(codes.size) { codes[it].toChar() }.concatToString()

/**
 * Coverage for SourceInteractionBoundaries.kt: every grapheme-grouping rule
 * (CRLF, regional indicators, Hangul jamo runs, extender sequences, emoji
 * modifiers, ZWJ chains), the surrogate helpers, and the three coercion biases.
 * All non-ASCII inputs are written as escapes so the file stays deterministic.
 */
class SourceInteractionBoundariesCoverageTest {
    private val testTrace = TestTraceRecorder("SourceInteractionBoundariesCoverageTest")


    private fun boundaries(text: String): List<Int> = text.interactionBoundaries(TextRange(0, text.length))

    @Test
    fun crlfStaysOneUnit() {
        testTrace.section("crlfStaysOneUnit")
        assertEquals(listOf(0, 2), boundaries("\r\n"))
        // A lone CR is its own unit; LF after plain text is separate too.
        assertEquals(listOf(0, 1), boundaries("\r"))
        assertEquals(listOf(0, 1, 2), boundaries("a\n"))
    }

    @Test
    fun regionalIndicatorsPairUp() {
        testTrace.section("regionalIndicatorsPairUp")
        // U+1F1E6 U+1F1E8: two indicators merge into one flag unit.
        assertEquals(listOf(0, 4), boundaries("🇦🇨"))
        // A third indicator left over starts a second unit.
        assertEquals(listOf(0, 4, 6), boundaries("🇦🇦🇦"))
        // A single indicator followed by a non-indicator does not pair.
        assertEquals(listOf(0, 2, 3), boundaries("🇦A"))
        assertEquals(listOf(0, 2), boundaries("🇦"))
    }

    @Test
    fun hangulJamoRunsMergeIntoSyllableBlocks() {
        testTrace.section("hangulJamoRunsMergeIntoSyllableBlocks")
        // L* run then V* run then T* run forms one unit.
        assertEquals(listOf(0, 3), boundaries("ᄀ가"))
        assertEquals(listOf(0, 3), boundaries("각"))
        assertEquals(listOf(0, 4), boundaries("각ᆨ"))
        // An L run not followed by V closes the unit at the last L.
        assertEquals(listOf(0, 1, 2), boundaries("ᄀA"))
        // A T-less LV jamo block ends after the V run.
        assertEquals(listOf(0, 2, 3), boundaries("가A"))
        // The supplementary jamo ranges behave like their BMP counterparts.
        // All these jamo are BMP single-unit code points.
        assertEquals(listOf(0, 2), boundaries("ꥠᅡ"))
        assertEquals(listOf(0, 2), boundaries("ᄀힰ"))
        assertEquals(listOf(0, 3), boundaries("가ퟋ"))
    }

    @Test
    fun precomposedHangulSyllablesAbsorbJamo() {
        testTrace.section("precomposedHangulSyllablesAbsorbJamo")
        // U+AC00 (LV): the V loop runs only when V jamo follow. Precomposed
        // syllables are single BMP units, so counts are one per character.
        assertEquals(listOf(0, 3), boundaries("가ᅡᆨ"))
        // U+AC01 (LVT): no V loop, trailing T jamo still merge.
        assertEquals(listOf(0, 2), boundaries("각ᆨ"))
        assertEquals(listOf(0, 1, 2), boundaries("각A"))
        // An LV syllable plus T without any V jamo.
        assertEquals(listOf(0, 2), boundaries("각"))
    }

    @Test
    fun extendersAttachToThePrecedingUnit() {
        testTrace.section("extendersAttachToThePrecedingUnit")
        assertEquals(listOf(0, 2), boundaries("á"))
        assertEquals(listOf(0, 2), boundaries("a️"))
        assertEquals(listOf(0, 3), boundaries("a󠄀"))
        // Black flag base plus a five-tag block: the whole tag run is one unit.
        val scotland = "🏴󠁧󠁢󠁥󠁮󠁧"
        assertEquals(listOf(0, 12), boundaries(scotland))
        // ZWNJ is an extender: it sticks to the previous syllable (one BMP
        // unit each).
        assertEquals(listOf(0, 2), boundaries("가‌"))
        // A plain letter stops the extender scan.
        assertEquals(listOf(0, 1, 2), boundaries("aA"))
    }

    @Test
    fun bandEdgesAndGapsExerciseEveryRangeArm() {
        testTrace.section("bandEdgesAndGapsExerciseEveryRangeArm")
        // CR before a non-LF character keeps the CR as its own unit.
        assertEquals(listOf(0, 1, 2), boundaries("\rA"))
        // An L jamo as the final unit exits both scans on next < end.
        assertEquals(listOf(0, 1, 2), boundaries("aᄀ"))
        // A private-use code point above the T band fails the T scan's
        // upper bound after entering it through the lower bound.
        assertEquals(listOf(0, 2, 3), boundaries("\u1100\u1161\uE000"))
        // A high surrogate before a non-low character above the surrogate
        // block: the low-range check fails on its upper bound.
        assertEquals(listOf(0, 1, 2), boundaries(surrogateText(0xD800, 0xE000)))
        // U+E01F0 sits above the supplementary variation selector band.
        assertEquals(listOf(0, 1, 3), boundaries("a\uDB40\uDDF0"))
        // U+E00A0 sits above the emoji tag block.
        assertEquals(listOf(0, 1, 3), boundaries("a\uDB40\uDCA0"))
        // An emoji modifier base followed by a non-modifier character.
        assertEquals(listOf(0, 2, 3), boundaries("👍甲"))
        // A supplementary code point above the modifier band enters the
        // range check but fails the upper bound.
        assertEquals(listOf(0, 2, 4), boundaries("👍😀"))
    }

    @Test
    fun emojiModifiersOnlyAttachToBases() {
        testTrace.section("emojiModifiersOnlyAttachToBases")
        // U+1F44D (thumbs-up, a modifier base) + U+1F3FB (skin tone).
        assertEquals(listOf(0, 4), boundaries("👍🏻"))
        // Extenders after the modifier stay in the same unit.
        assertEquals(listOf(0, 5), boundaries("👍🏻️"))
        // A modifier after a non-base is its own unit.
        assertEquals(listOf(0, 1, 3), boundaries("a🏻"))
        // A base without a following modifier keeps the plain unit.
        assertEquals(listOf(0, 2), boundaries("👍"))
    }

    @Test
    fun zwjChainsJoinOnlyExtendedPictographic() {
        testTrace.section("zwjChainsJoinOnlyExtendedPictographic")
        // Woman ZWJ woman ZWJ boy: one family unit of 8 UTF-16 units.
        assertEquals(listOf(0, 8), boundaries("👩‍👩‍👦"))
        // A trailing ZWJ with nothing after it stays with the previous unit.
        assertEquals(listOf(0, 2), boundaries("a‍"))
        // A pictographic before ZWJ followed by a plain letter breaks.
        assertEquals(listOf(0, 3, 4), boundaries("👩‍a"))
        // A plain letter before ZWJ never starts a joined sequence.
        assertEquals(listOf(0, 2, 3), boundaries("a‍a"))
        // A skin-tone modifier on the joined member also merges into the unit.
        assertEquals(listOf(0, 7), boundaries("👍‍👍🏻"))
    }

    @Test
    fun unpairedSurrogatesFallBackToSingleUnits() {
        testTrace.section("unpairedSurrogatesFallBackToSingleUnits")
        // High surrogate at the very end has no low to pair with.
        assertEquals(listOf(0, 1, 2), boundaries(surrogateText('a'.code, 0xD800)))
        // High surrogate followed by a non-low character.
        assertEquals(listOf(0, 1, 2, 3), boundaries(surrogateText('a'.code, 0xD800, 'A'.code)))
        // A proper pair is one code point and one unit.
        assertEquals(listOf(0, 2, 3), boundaries("😀A"))
    }

    @Test
    fun codePointAtCompatCoversEverySurrogateCase() {
        testTrace.section("codePointAtCompatCoversEverySurrogateCase")
        assertEquals('a'.code, "a".codePointAtCompat(0, 1))
        assertEquals(0x1F600, "😀".codePointAtCompat(0, 2))
        assertEquals(0xD800, surrogateText('a'.code, 0xD800).codePointAtCompat(1, 2))
        assertEquals(0xD800, surrogateText('a'.code, 0xD800, 'A'.code).codePointAtCompat(1, 3))
    }

    @Test
    fun rangeBoundariesRespectTheRequestedWindow() {
        testTrace.section("rangeBoundariesRespectTheRequestedWindow")
        assertEquals(listOf(1, 2, 3), "abcd".interactionBoundaries(TextRange(1, 3)))
        // A range beyond the text coerces onto the empty endpoint.
        assertEquals(listOf(2), "ab".interactionBoundaries(TextRange(5, 9)))
        assertEquals(listOf(0, 2, 3), "😀b".sourceGraphemeBoundaries(TextRange(0, 3)))
    }

    @Test
    fun coercionHonoursEveryBiasAndEdgeCase() {
        testTrace.section("coercionHonoursEveryBiasAndEdgeCase")
        // The four-member family emoji is one 11-unit interaction unit.
        val family = "👨‍👩‍👧‍👧"
        assertEquals(11, family.length)
        // Offset 2 sits inside the unit, nearer its start.
        assertEquals(
            0,
            family.coerceToInteractionBoundary(2, TextRange(0, family.length), SourceBoundaryBias.Nearest),
        )
        assertEquals(
            0,
            family.coerceToInteractionBoundary(2, TextRange(0, family.length), SourceBoundaryBias.Backward),
        )
        assertEquals(
            family.length,
            family.coerceToInteractionBoundary(2, TextRange(0, family.length), SourceBoundaryBias.Forward),
        )
        // An offset already on a boundary is returned unchanged.
        assertEquals(
            2,
            "😀b".coerceToInteractionBoundary(2, TextRange(0, 3), SourceBoundaryBias.Nearest),
        )
        // Range endpoints are returned before any boundary scan.
        assertEquals(
            3,
            "😀b".coerceToInteractionBoundary(9, TextRange(0, 3), SourceBoundaryBias.Backward),
        )
        assertEquals(
            0,
            "😀b".coerceToInteractionBoundary(-1, TextRange(0, 3), SourceBoundaryBias.Forward),
        )
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
