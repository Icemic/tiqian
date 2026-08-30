package org.tiqian.shaping

import org.tiqian.core.TextRange
import org.tiqian.core.TextStyle
import org.tiqian.font.FontCandidate
import org.tiqian.font.FontDecision
import org.tiqian.font.FontRole
import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.assertFailsWith
import org.tiqian.test.trace.assertNotNull
import org.tiqian.test.trace.assertTrue
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

// A lone surrogate written inside a string literal is replaced with '?' when
// the JS test bundle re-serializes its sources, so inputs that carry one are
// built from char codes at runtime to keep the code unit intact everywhere.
private fun surrogateText(vararg codes: Int): String =
    CharArray(codes.size) { codes[it].toChar() }.concatToString()

class TextShaperCoverageTest {
    private val testTrace = TestTraceRecorder("TextShaperCoverageTest")

    private val shaper = ExplainableStubTextShaper()

    private fun testInput(
        text: String,
        role: FontRole = FontRole.LatinText,
        displayText: String = text,
        openTypeFeatures: List<String> = emptyList(),
    ): ShapingInput =
        ShapingInput(
            text = text,
            range = TextRange(0, text.length),
            style = TextStyle(fontSize = 16.0f),
            fontDecision = FontDecision(
                range = TextRange(0, text.length),
                candidate = FontCandidate(
                    key = "test-font",
                    family = "test-font",
                    role = role,
                ),
                role = role,
                reason = "coverage-test",
            ),
            displayText = displayText,
            openTypeFeatures = openTypeFeatures,
        )

    @Test
    fun coversAllShapingSourceEnumEntries() {
        testTrace.section("coversAllShapingSourceEnumEntries")
        val sources = ShapingSource.entries
        assertTrue(sources.contains(ShapingSource.Stub))
        assertTrue(sources.contains(ShapingSource.JvmAwt))
        assertTrue(sources.contains(ShapingSource.AndroidPaint))
        assertTrue(sources.contains(ShapingSource.Skia))
        assertTrue(sources.contains(ShapingSource.HarfBuzz))
        assertTrue(sources.contains(ShapingSource.CoreText))
        assertEquals(6, sources.size)

        for (source in ShapingSource.values()) {
            assertEquals(source, ShapingSource.valueOf(source.name))
        }
    }

    @Test
    fun unimplementedTextShaperThrowsOnShape() {
        testTrace.section("unimplementedTextShaperThrowsOnShape")
        val unimplemented = UnimplementedTextShaper()
        val input = testInput("test")
        val error = assertFailsWith<IllegalStateException> {
            unimplemented.shape(input)
        }
        assertTrue(error.message?.contains("platform-specific") == true)
    }

    @Test
    fun explainableStubNominalAdvanceBranches() {
        testTrace.section("explainableStubNominalAdvanceBranches")
        // 1. Two-em dash substitution (both this == "⸺" and displayText == "⸺")
        val dashSourceResult = shaper.shape(testInput("⸺", role = FontRole.CjkPunctuation))
        assertEquals(32.0f, dashSourceResult.clusters.single().advance)

        val dashDisplayResult = shaper.shape(testInput("——", role = FontRole.CjkPunctuation, displayText = "⸺"))
        assertEquals(32.0f, dashDisplayResult.clusters.single().advance)

        // 2. Space runs: isNotEmpty() && all { it == ' ' }
        val singleSpaceResult = shaper.shape(testInput(" "))
        assertEquals(8.0f, singleSpaceResult.clusters.single().advance) // 0.5 * 16.0 * 1

        val multipleSpacesResult = shaper.shape(testInput("   "))
        assertEquals(24.0f, multipleSpacesResult.clusters.single().advance) // 0.5 * 16.0 * 3

        // Empty string
        val emptyResult = shaper.shape(testInput(""))
        assertEquals(0.0f, emptyResult.clusters.single().advance)
        assertEquals(1, emptyResult.glyphRuns.single().glyphs.size) // coerced to at least 1 glyph

        // Mixed spaces and non-spaces
        val spacePrefix = shaper.shape(testInput(" a"))
        assertEquals(32.0f, spacePrefix.clusters.single().advance) // 2 code points * 16.0

        val spaceSuffix = shaper.shape(testInput("a "))
        assertEquals(32.0f, spaceSuffix.clusters.single().advance)
    }

    @Test
    fun surrogatePairHandlingInCodePointCount() {
        testTrace.section("surrogatePairHandlingInCodePointCount")
        // Valid surrogate pair (e.g. U+1F600 Grinning Face, or U+2000B CJK Unified Ideograph Extension B)
        val surrogatePair = "\uD83D\uDE00"
        val surrogateResult = shaper.shape(testInput(surrogatePair))
        assertEquals(1, surrogateResult.decisions.single().glyphCount)
        assertEquals(16.0f, surrogateResult.clusters.single().advance)

        // Multiple surrogate pairs
        val multiSurrogate = "\uD83D\uDE00\uD840\uDC0B"
        val multiResult = shaper.shape(testInput(multiSurrogate))
        assertEquals(2, multiResult.decisions.single().glyphCount)
        assertEquals(32.0f, multiResult.clusters.single().advance)

        // Lone high surrogate at end of string
        val loneHighSurrogateAtEnd = surrogateText(0xD83D)
        val loneHighResult = shaper.shape(testInput(loneHighSurrogateAtEnd))
        assertEquals(1, loneHighResult.decisions.single().glyphCount)
        assertEquals(16.0f, loneHighResult.clusters.single().advance)

        // Lone high surrogate followed by non-low surrogate (e.g. ASCII 'A')
        val highSurrogateFollowedByNonLow = surrogateText(0xD83D, 'A'.code)
        val invalidPairResult = shaper.shape(testInput(highSurrogateFollowedByNonLow))
        assertEquals(2, invalidPairResult.decisions.single().glyphCount)
        assertEquals(32.0f, invalidPairResult.clusters.single().advance)
    }

    @Test
    fun shapingInputWithFeaturesAndConstants() {
        testTrace.section("shapingInputWithFeaturesAndConstants")
        val inputWithFeatures = testInput(
            text = "Test",
            openTypeFeatures = listOf("fwid=1", "vert=1"),
        )
        assertEquals(listOf("fwid=1", "vert=1"), inputWithFeatures.openTypeFeatures)
        assertEquals("Test", inputWithFeatures.displayText)

        val result = shaper.shape(inputWithFeatures)
        assertEquals(4, result.decisions.single().glyphCount)
        assertEquals(4, result.decisions.single().glyphsWithoutInkBounds)
        assertEquals("ExplainableStubTextShaper:nominal-em-advance", result.decisions.single().reason)
        assertEquals(ShapingSource.Stub.name, result.decisions.single().source)

        assertNotNull(UNVERIFIED_DISPLAY_SUBSTITUTION_COVERAGE_ISSUE)
        assertNotNull(PLATFORM_MULTI_FACE_STRING_DRAW_ISSUE)
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
