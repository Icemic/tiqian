package org.tiqian.layout

import org.tiqian.clreq.ClreqProfile
import org.tiqian.core.Cluster
import org.tiqian.core.InlineObjectSpan
import org.tiqian.core.TextRange
import org.tiqian.font.CjkFontRoleClassifier
import org.tiqian.font.FontCandidate
import org.tiqian.font.FontDecision
import org.tiqian.font.FontRole
import org.tiqian.font.FontRoleContext
import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.assertFailsWith
import org.tiqian.test.trace.assertNotNull
import org.tiqian.test.trace.assertTrue
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder
import org.tiqian.test.trace.assertDoesNotThrow

// A lone surrogate written inside a string literal is replaced with '?' when
// the JS test bundle re-serializes its sources, so inputs that carry one are
// built from char codes at runtime to keep the code unit intact everywhere.
private fun surrogateText(vararg codes: Int): String =
    CharArray(codes.size) { codes[it].toChar() }.concatToString()

class ClusterRoleResolutionCoverageTest {
    private val testTrace = TestTraceRecorder("ClusterRoleResolutionCoverageTest")


    private val classifier = CjkFontRoleClassifier()
    private val context = FontRoleContext()
    private val profile = ClreqProfile.MainlandHorizontal

    private fun cluster(index: Int, text: String = "中", advance: Float = 16.0f) = Cluster(
        range = TextRange(index, index + 1),
        text = text,
        displayText = text,
        fontKey = "test",
        advance = advance,
    )

    @Test
    fun clusterRoleRangesWithSimpleText() {
        testTrace.section("clusterRoleRangesWithSimpleText")
        val text = "中文"
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), emptySet())
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun clusterRoleRangesWithEmoji() {
        testTrace.section("clusterRoleRangesWithEmoji")
        val text = "\uD83D\uDE00"
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), emptySet())
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun clusterRoleRangesWithCrlfMandatoryBreak() {
        testTrace.section("clusterRoleRangesWithCrlfMandatoryBreak")
        val text = "line1\r\nline2"
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), emptySet())
        assertTrue(result.any { it.mandatoryBreak })
    }

    @Test
    fun clusterRoleRangesWithLfOnly() {
        testTrace.section("clusterRoleRangesWithLfOnly")
        val text = "line1\nline2"
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), emptySet())
        assertTrue(result.any { it.mandatoryBreak })
    }

    @Test
    fun clusterRoleRangesWithZeroWidthSpace() {
        testTrace.section("clusterRoleRangesWithZeroWidthSpace")
        val text = "ab\u200Bcd"
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), emptySet())
        assertTrue(result.any { it.zeroWidthSoftBreak })
    }

    @Test
    fun clusterRoleRangesWithInlineObject() {
        testTrace.section("clusterRoleRangesWithInlineObject")
        val text = "abxcd"
        val inlineObjects = mapOf(2 to InlineObjectSpan(TextRange(2, 3), 8.0f, 8.0f, 8.0f))
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), emptySet(), inlineObjects)
        assertEquals(1, result.size)
    }

    @Test
    fun clusterRoleRangesWithSpanBoundaries() {
        testTrace.section("clusterRoleRangesWithSpanBoundaries")
        val text = "abcd"
        val result = clusterRoleRanges(text, classifier, context, profile, setOf(2), emptySet())
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun clusterRoleRangesWithEmojiShapingBoundaries() {
        testTrace.section("clusterRoleRangesWithEmojiShapingBoundaries")
        val text = "\u0023\uFE0F"
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), setOf(1))
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun clusterRoleRangesWithAsciiPointMark() {
        testTrace.section("clusterRoleRangesWithAsciiPointMark")
        val text = "a,b"
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), emptySet())
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun clusterRoleRangesWithCoalesceRepeatablePunctuation() {
        testTrace.section("clusterRoleRangesWithCoalesceRepeatablePunctuation")
        val text = "，，"
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), emptySet())
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun requireCoveredByWithContiguousClusters() {
        testTrace.section("requireCoveredByWithContiguousClusters")
        val clusters = listOf(
            Cluster(TextRange(0, 2), "ab", "ab", "latin", 8.0f),
            Cluster(TextRange(2, 4), "cd", "cd", "latin", 8.0f),
        )
        val candidate = FontCandidate("test", "test", FontRole.LatinText)
        val decisions = listOf(
            FontDecision(TextRange(0, 2), candidate, FontRole.LatinText, "test"),
            FontDecision(TextRange(2, 4), candidate, FontRole.LatinText, "test"),
        )
        assertDoesNotThrow { clusters.requireCoveredBy(decisions) }
    }

    @Test
    fun requireCoveredByWithSingleCluster() {
        testTrace.section("requireCoveredByWithSingleCluster")
        val clusters = listOf(
            Cluster(TextRange(0, 1), "a", "a", "latin", 8.0f),
            Cluster(TextRange(1, 2), "b", "b", "latin", 8.0f),
        )
        val candidate = FontCandidate("test", "test", FontRole.LatinText)
        val decisions = listOf(
            FontDecision(TextRange(0, 2), candidate, FontRole.LatinText, "test"),
        )
        assertDoesNotThrow { clusters.requireCoveredBy(decisions) }
    }

    @Test
    fun requireCoveredByWithMultipleDecisions() {
        testTrace.section("requireCoveredByWithMultipleDecisions")
        val clusters = listOf(
            Cluster(TextRange(0, 1), "a", "a", "latin", 8.0f),
            Cluster(TextRange(1, 2), "b", "b", "latin", 8.0f),
            Cluster(TextRange(2, 3), "c", "c", "latin", 8.0f),
        )
        val candidate = FontCandidate("test", "test", FontRole.LatinText)
        val decisions = listOf(
            FontDecision(TextRange(0, 1), candidate, FontRole.LatinText, "test"),
            FontDecision(TextRange(1, 2), candidate, FontRole.LatinText, "test"),
            FontDecision(TextRange(2, 3), candidate, FontRole.LatinText, "test"),
        )
        assertDoesNotThrow { clusters.requireCoveredBy(decisions) }
    }

    @Test
    fun clusterRoleRangesWithGraphemeExtend() {
        testTrace.section("clusterRoleRangesWithGraphemeExtend")
        val text = "a\u0300"
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), emptySet())
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun clusterRoleRangesWithVariationSelector() {
        testTrace.section("clusterRoleRangesWithVariationSelector")
        val text = "\u0041\uFE0F"
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), emptySet())
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun clusterRoleRangesWithKeycapSequence() {
        testTrace.section("clusterRoleRangesWithKeycapSequence")
        val text = "1\u20E3"
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), emptySet())
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun clusterRoleRangesWithEmojiModifierSequence() {
        testTrace.section("clusterRoleRangesWithEmojiModifierSequence")
        val text = "\uD83C\uDFFB"
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), emptySet())
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun clusterRoleRangesWithEmojiStyleVariation() {
        testTrace.section("clusterRoleRangesWithEmojiStyleVariation")
        val text = "\u2600\uFE0F"
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), emptySet())
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun clusterRoleRangesWithEmptyText() {
        testTrace.section("clusterRoleRangesWithEmptyText")
        val text = ""
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), emptySet())
        assertEquals(0, result.size)
    }

    @Test
    fun clusterRoleRangesWithOnlyWhitespace() {
        testTrace.section("clusterRoleRangesWithOnlyWhitespace")
        val text = "  "
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), emptySet())
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun clusterRoleRangesModifierBaseWithVariationSelectorAndModifier() {
        testTrace.section("clusterRoleRangesModifierBaseWithVariationSelectorAndModifier")
        val text = "\u270A\uFE0F\uD83C\uDFFB"
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), emptySet())
        assertTrue(result.isNotEmpty())
        assertEquals(FontRole.Emoji, result[0].role)
    }

    @Test
    fun requireCoveredByFailsWhenClusterCrossesDecisionRange() {
        testTrace.section("requireCoveredByFailsWhenClusterCrossesDecisionRange")
        val clusters = listOf(
            Cluster(TextRange(0, 3), "abc", "abc", "latin", 8.0f),
        )
        val candidate = FontCandidate("test", "test", FontRole.LatinText)
        val decisions = listOf(
            FontDecision(TextRange(0, 2), candidate, FontRole.LatinText, "test"),
        )
        assertFailsWith<IllegalArgumentException> {
            clusters.requireCoveredBy(decisions)
        }
    }

    @Test
    fun requireCoveredByFailsWhenClustersAreNonContiguous() {
        testTrace.section("requireCoveredByFailsWhenClustersAreNonContiguous")
        val clusters = listOf(
            Cluster(TextRange(0, 1), "a", "a", "latin", 8.0f),
            Cluster(TextRange(2, 3), "c", "c", "latin", 8.0f),
        )
        val candidate = FontCandidate("test", "test", FontRole.LatinText)
        val decisions = listOf(
            FontDecision(TextRange(0, 3), candidate, FontRole.LatinText, "test"),
        )
        assertFailsWith<IllegalArgumentException> {
            clusters.requireCoveredBy(decisions)
        }
    }

    @Test
    fun requireCoveredByFailsWhenClustersDoNotCoverEnd() {
        testTrace.section("requireCoveredByFailsWhenClustersDoNotCoverEnd")
        val clusters = listOf(
            Cluster(TextRange(0, 1), "a", "a", "latin", 8.0f),
        )
        val candidate = FontCandidate("test", "test", FontRole.LatinText)
        val decisions = listOf(
            FontDecision(TextRange(0, 3), candidate, FontRole.LatinText, "test"),
        )
        assertFailsWith<IllegalArgumentException> {
            clusters.requireCoveredBy(decisions)
        }
    }

    @Test
    fun clusterRoleRangesWithCrlfOnly() {
        testTrace.section("clusterRoleRangesWithCrlfOnly")
        val text = "\r\n"
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), emptySet())
        assertTrue(result.any { it.mandatoryBreak })
    }

    @Test
    fun clusterRoleRangesWithLfInsideCrlf() {
        testTrace.section("clusterRoleRangesWithLfInsideCrlf")
        val text = "a\r\nb"
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), emptySet())
        assertTrue(result.any { it.mandatoryBreak })
    }

    @Test
    fun clusterRoleRangesWithCrOnly() {
        testTrace.section("clusterRoleRangesWithCrOnly")
        val text = "a\rb"
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), emptySet())
        assertTrue(result.any { it.mandatoryBreak })
    }

    @Test
    fun clusterRoleRangesWithEmojiShapingBoundaryInside() {
        testTrace.section("clusterRoleRangesWithEmojiShapingBoundaryInside")
        val text = "\u0023\uFE0F\u0041"
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), setOf(2))
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun clusterRoleRangesWithGraphemeExtendAfterEmoji() {
        testTrace.section("clusterRoleRangesWithGraphemeExtendAfterEmoji")
        val text = "\uD83D\uDE00\u0300"
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), emptySet())
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun clusterRoleRangesWithVariationSelectorAfterLatin() {
        testTrace.section("clusterRoleRangesWithVariationSelectorAfterLatin")
        val text = "A\uFE0F"
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), emptySet())
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun clusterRoleRangesWithCjkPunctuationCoalesce() {
        testTrace.section("clusterRoleRangesWithCjkPunctuationCoalesce")
        val text = "\u3001\u3001\u3001"
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), emptySet())
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun clusterRoleRangesWithRoleOverride() {
        testTrace.section("clusterRoleRangesWithRoleOverride")
        val text = "\u2600\uFE0F"
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), emptySet())
        assertTrue(result.any { it.role == FontRole.Emoji })
    }

    @Test
    fun clusterRoleRangesWithAsciiPointMarkAttached() {
        testTrace.section("clusterRoleRangesWithAsciiPointMarkAttached")
        val text = ",abc"
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), emptySet())
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun clusterRoleRangesWithEmojiModifierBaseCombiningMark() {
        testTrace.section("clusterRoleRangesWithEmojiModifierBaseCombiningMark")
        val text = "\u270B\u0300\uD83C\uDFFB"
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), emptySet())
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun clusterRoleRangesWithKeycapBaseAndKeycap() {
        testTrace.section("clusterRoleRangesWithKeycapBaseAndKeycap")
        val text = "1\uFE0F\u20E3"
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), emptySet())
        assertTrue(result.isNotEmpty())
        assertEquals(FontRole.Emoji, result[0].role)
    }

    @Test
    fun clusterRoleRangesWithEmojiStyleVariationNoFE0F() {
        testTrace.section("clusterRoleRangesWithEmojiStyleVariationNoFE0F")
        val text = "\u2600"
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), emptySet())
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun clusterRoleRangesWithMultipleSpanBoundaries() {
        testTrace.section("clusterRoleRangesWithMultipleSpanBoundaries")
        val text = "abcdef"
        val result = clusterRoleRanges(text, classifier, context, profile, setOf(2, 4), emptySet())
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun requireCoveredByWithGapBetweenDecisions() {
        testTrace.section("requireCoveredByWithGapBetweenDecisions")
        val clusters = listOf(
            Cluster(TextRange(0, 1), "a", "a", "latin", 8.0f),
            Cluster(TextRange(1, 2), "b", "b", "latin", 8.0f),
        )
        val candidate = FontCandidate("test", "test", FontRole.LatinText)
        val decisions = listOf(
            FontDecision(TextRange(0, 1), candidate, FontRole.LatinText, "test"),
            FontDecision(TextRange(1, 2), candidate, FontRole.LatinText, "test"),
            FontDecision(TextRange(2, 3), candidate, FontRole.LatinText, "test"),
        )
        assertFailsWith<IllegalArgumentException> {
            clusters.requireCoveredBy(decisions)
        }
    }

    @Test
    fun requireCoveredByWithEmptyDecisions() {
        testTrace.section("requireCoveredByWithEmptyDecisions")
        val clusters = listOf(
            Cluster(TextRange(0, 1), "a", "a", "latin", 8.0f),
        )
        assertDoesNotThrow { clusters.requireCoveredBy(emptyList()) }
    }

    @Test
    fun clusterRoleRangesWithNonCjkPunctuation() {
        testTrace.section("clusterRoleRangesWithNonCjkPunctuation")
        val text = ".,;"
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), emptySet())
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun clusterRoleRangesWithEmojiVariationAndModifier() {
        testTrace.section("clusterRoleRangesWithEmojiVariationAndModifier")
        val text = "\u270A\uFE0F\uD83C\uDFFB"
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), emptySet())
        assertTrue(result.isNotEmpty())
        assertEquals(FontRole.Emoji, result[0].role)
    }

    @Test
    fun clusterRoleRangesWithLoneSurrogate() {
        testTrace.section("clusterRoleRangesWithLoneSurrogate")
        val text = surrogateText(0xD800)
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), emptySet())
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun clusterRoleRangesWithZWJSequence() {
        testTrace.section("clusterRoleRangesWithZWJSequence")
        val text = "\uD83D\uDE00\u200D\uD83D\uDEBB"
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), emptySet())
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun clusterRoleRangesWithLfAtStart() {
        testTrace.section("clusterRoleRangesWithLfAtStart")
        val text = "\nabc"
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), emptySet())
        assertTrue(result.any { it.mandatoryBreak })
    }

    @Test
    fun clusterRoleRangesWithCrNotFollowedByLf() {
        testTrace.section("clusterRoleRangesWithCrNotFollowedByLf")
        val text = "a\rb"
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), emptySet())
        assertTrue(result.any { it.mandatoryBreak })
    }

    @Test
    fun clusterRoleRangesWithCrAtEnd() {
        testTrace.section("clusterRoleRangesWithCrAtEnd")
        val text = "a\r"
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), emptySet())
        assertTrue(result.any { it.mandatoryBreak })
    }

    @Test
    fun clusterRoleRangesWithSingleGrapheme() {
        testTrace.section("clusterRoleRangesWithSingleGrapheme")
        val text = "a"
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), emptySet())
        assertEquals(1, result.size)
    }

    @Test
    fun clusterRoleRangesWithEmojiShapingBoundaryAtGraphemeEnd() {
        testTrace.section("clusterRoleRangesWithEmojiShapingBoundaryAtGraphemeEnd")
        val text = "\u0023\uFE0F"
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), setOf(2))
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun clusterRoleRangesWithAttachedAsciiPointMarkAtStart() {
        testTrace.section("clusterRoleRangesWithAttachedAsciiPointMarkAtStart")
        val text = ",a"
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), emptySet())
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun clusterRoleRangesWithAttachedAsciiPointMarkFollowedByLatin() {
        testTrace.section("clusterRoleRangesWithAttachedAsciiPointMarkFollowedByLatin")
        val text = ",ab"
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), emptySet())
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun clusterRoleRangesWithLoneSurrogateHighOnly() {
        testTrace.section("clusterRoleRangesWithLoneSurrogateHighOnly")
        val text = surrogateText('a'.code, 0xD800, 'b'.code)
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), emptySet())
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun clusterRoleRangesWithSupplementaryCharacter() {
        testTrace.section("clusterRoleRangesWithSupplementaryCharacter")
        val text = "\uD83D\uDE00"
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), emptySet())
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun clusterRoleRangesWithCjkPunctuationAndCoalesce() {
        testTrace.section("clusterRoleRangesWithCjkPunctuationAndCoalesce")
        val text = "\u3001\u3002\u3001"
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), emptySet())
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun clusterRoleRangesWithMultipleEmojiShapingBoundaries() {
        testTrace.section("clusterRoleRangesWithMultipleEmojiShapingBoundaries")
        val text = "\u0023\uFE0F\u002A\uFE0F"
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), setOf(2, 4))
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun clusterRoleRangesWithVariationSelectorAfterEmoji() {
        testTrace.section("clusterRoleRangesWithVariationSelectorAfterEmoji")
        val text = "\uD83D\uDE00\uFE0F"
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), emptySet())
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun clusterRoleRangesWithKeycapBaseNoKeycap() {
        testTrace.section("clusterRoleRangesWithKeycapBaseNoKeycap")
        val text = "1\uFE0F"
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), emptySet())
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun requireCoveredByWithOverlappingDecisions() {
        testTrace.section("requireCoveredByWithOverlappingDecisions")
        val clusters = listOf(
            Cluster(TextRange(0, 2), "ab", "ab", "latin", 8.0f),
            Cluster(TextRange(2, 4), "cd", "cd", "latin", 8.0f),
        )
        val candidate = FontCandidate("test", "test", FontRole.LatinText)
        val decisions = listOf(
            FontDecision(TextRange(0, 3), candidate, FontRole.LatinText, "test"),
        )
        assertFailsWith<IllegalArgumentException> {
            clusters.requireCoveredBy(decisions)
        }
    }

    @Test
    fun clusterRoleRangesWithCrlfPairProducesSingleCluster() {
        testTrace.section("clusterRoleRangesWithCrlfPairProducesSingleCluster")
        val text = "a\r\nb"
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), emptySet())
        val crlfCluster = result.find { it.range.start == 1 && it.range.end == 3 }
        assertNotNull(crlfCluster)
        assertTrue(crlfCluster!!.mandatoryBreak)
    }

    @Test
    fun clusterRoleRangesWithEmojiRolePromotionNull() {
        testTrace.section("clusterRoleRangesWithEmojiRolePromotionNull")
        val text = "\uD83D\uDE00"
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), emptySet())
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun clusterRoleRangesWithSurrogatePairNonLow() {
        testTrace.section("clusterRoleRangesWithSurrogatePairNonLow")
        val text = surrogateText(0xD800, 'A'.code)
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), emptySet())
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun clusterRoleRangesWithNonVariationSelector() {
        testTrace.section("clusterRoleRangesWithNonVariationSelector")
        val text = "\u0041\u0042"
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), emptySet())
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun clusterRoleRangesWithNonCombiningMark() {
        testTrace.section("clusterRoleRangesWithNonCombiningMark")
        val text = "A\u0300B"
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), emptySet())
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun clusterRoleRangesWithNonAsciiPointMark() {
        testTrace.section("clusterRoleRangesWithNonAsciiPointMark")
        val text = "A\u0021B"
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), emptySet())
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun clusterRoleRangesWithEmojiShapingBoundaryInsideAndOutsideRange() {
        testTrace.section("clusterRoleRangesWithEmojiShapingBoundaryInsideAndOutsideRange")
        val text = "\uD83D\uDE00\uFE0F\uD83D\uDE01"
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), setOf(4))
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun clusterRoleRangesWithAttachedAsciiPointMarkNotAdjacent() {
        testTrace.section("clusterRoleRangesWithAttachedAsciiPointMarkNotAdjacent")
        val text = "a.\u0021b"
        val result = clusterRoleRanges(text, classifier, context, profile, emptySet(), emptySet())
        assertTrue(result.isNotEmpty())
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}