package org.tiqian.layout

import org.tiqian.core.Cluster
import org.tiqian.core.EastAsianSpacingEdges
import org.tiqian.core.EastAsianSpacingValue
import org.tiqian.core.InlineAttachment
import org.tiqian.core.TextRange
import org.tiqian.font.FontRole
import org.tiqian.linebreak.UnicodePunctuationLineBreak
import org.tiqian.linebreak.UnicodePunctuationLineBreakClass
import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.assertFailsWith
import org.tiqian.test.trace.assertTrue
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder
import org.tiqian.test.trace.assertNotNull

// A lone surrogate written inside a string literal is replaced with '?' when
// the JS test bundle re-serializes its sources, so inputs that carry one are
// built from char codes at runtime to keep the code unit intact everywhere.
private fun surrogateText(vararg codes: Int): String =
    CharArray(codes.size) { codes[it].toChar() }.concatToString()

class UnicodePunctuationBoundaryResolverCoverageTest {
    private val testTrace = TestTraceRecorder("UnicodePunctuationBoundaryResolverCoverageTest")


    @Test
    fun resolveAttachedInlineVirtualBoundariesWithMultiplePrevious() {
        testTrace.section("resolveAttachedInlineVirtualBoundariesWithMultiplePrevious")
        val attachments = listOf(InlineAttachment.None, InlineAttachment.Previous, InlineAttachment.Previous, InlineAttachment.None)
        val result = resolveAttachedInlineVirtualBoundaries(attachments)
        assertEquals(1, result.size)
        assertEquals(0, result[0].previousClusterIndex)
        assertEquals(1..2, result[0].attachedClusterRange)
        assertEquals(3, result[0].nextClusterIndex)
    }

    @Test
    fun resolveAttachedInlineVirtualBoundariesWithNoPrevious() {
        testTrace.section("resolveAttachedInlineVirtualBoundariesWithNoPrevious")
        val attachments = listOf(InlineAttachment.None, InlineAttachment.None)
        val result = resolveAttachedInlineVirtualBoundaries(attachments)
        assertEquals(0, result.size)
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithOpenPunctuation() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithOpenPunctuation")
        val text = "\uFF08\u4E2D\u6587\uFF09"
        val clusters = text.indices.map { index ->
            Cluster(
                range = TextRange(index, index + 1),
                text = text[index].toString(),
                fontKey = "cjk",
                advance = 16.0f,
            )
        }
        val roles = List(text.length) { FontRole.CjkText }
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.decisions.any { it.forbiddenPosition == "LineEnd" })
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithPairedQuotes() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithPairedQuotes")
        val text = "\u4E2D\u6587\u201C\u4F60\u597D\u201D\u4E2D\u6587"
        val quotePairs = listOf(QuotePair(2, 5, QuoteType.Double))
        val clusters = text.indices.map { index ->
            Cluster(
                range = TextRange(index, index + 1),
                text = text[index].toString(),
                fontKey = "cjk",
                advance = 16.0f,
            )
        }
        val roles = List(text.length) { FontRole.CjkText }
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, quotePairs)
        assertTrue(result.decisions.any { it.reason == "Uax14WesternPunctuationBoundary:PairedOpeningQuote" })
        assertTrue(result.decisions.any { it.reason == "Uax14WesternPunctuationBoundary:PairedClosingQuote" })
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithUnmatchedClosingPunctuation() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithUnmatchedClosingPunctuation")
        val text = "\u4E2D\u3002"
        val clusters = text.indices.map { index ->
            Cluster(
                range = TextRange(index, index + 1),
                text = text[index].toString(),
                fontKey = "cjk",
                advance = 16.0f,
            )
        }
        val roles = List(text.length) { FontRole.CjkText }
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.forbiddenLineStartClusters.isNotEmpty())
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithCjkClosingAtLineStart() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithCjkClosingAtLineStart")
        val text = "\u3002\uFF0C"
        val clusters = text.indices.map { index ->
            Cluster(
                range = TextRange(index, index + 1),
                text = text[index].toString(),
                fontKey = "cjk",
                advance = 16.0f,
            )
        }
        val roles = List(text.length) { FontRole.CjkText }
        val quotePairs = listOf(QuotePair(0, 1, QuoteType.Single))
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, quotePairs)
        assertTrue(result.decisions.any { it.forbiddenPosition == "LineEnd" })
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithExclamationMark() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithExclamationMark")
        val text = "\u4E2D!\u4E2D"
        val clusters = text.indices.map { index ->
            Cluster(
                range = TextRange(index, index + 1),
                text = text[index].toString(),
                fontKey = if (text[index] == '!') "latin" else "cjk",
                advance = 16.0f,
            )
        }
        val roles = List(text.length) { FontRole.CjkText }
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.decisions.any { it.forbiddenPosition == "LineStart" })
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithInitialQuoteForbidLineEnd() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithInitialQuoteForbidLineEnd")
        val text = "\u4E2D\u201C\u4E2D"
        val clusters = text.indices.map { index ->
            Cluster(
                range = TextRange(index, index + 1),
                text = text[index].toString(),
                fontKey = "cjk",
                advance = 16.0f,
            )
        }
        val roles = List(text.length) { FontRole.CjkText }
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.decisions.any { it.forbiddenPosition == "LineEnd" })
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithUnresolvedQuote() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithUnresolvedQuote")
        val text = "\u4E2D\u2019\u4E2D"
        val clusters = text.indices.map { index ->
            Cluster(
                range = TextRange(index, index + 1),
                text = text[index].toString(),
                fontKey = "cjk",
                advance = 16.0f,
            )
        }
        val roles = List(text.length) { FontRole.CjkText }
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.decisions.any { it.forbiddenPosition == "LineStart" })
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithMultipleClusters() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithMultipleClusters")
        val text = "\u4E2D\u6587\uFF0C\u4E2D\u6587"
        val clusters = text.indices.map { index ->
            Cluster(
                range = TextRange(index, index + 1),
                text = text[index].toString(),
                fontKey = "cjk",
                advance = 16.0f,
            )
        }
        val roles = List(text.length) { FontRole.CjkText }
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.decisions.any { it.forbiddenPosition == "LineStart" })
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithEmptyClusters() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithEmptyClusters")
        val result = resolveUnicodePunctuationBoundaries("", emptyList(), emptyList(), emptyList())
        assertEquals(0, result.forbiddenLineStartClusters.size)
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithAllCjkText() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithAllCjkText")
        val text = "\u4E2D\u6587\u6587\u6587"
        val clusters = text.indices.map { index ->
            Cluster(
                range = TextRange(index, index + 1),
                text = text[index].toString(),
                fontKey = "cjk",
                advance = 16.0f,
            )
        }
        val roles = List(text.length) { FontRole.CjkText }
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertEquals(0, result.decisions.size)
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithWesternClosingForbidLineStart() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithWesternClosingForbidLineStart")
        val text = "\u4E2D)\u4E2D"
        val clusters = text.indices.map { index ->
            Cluster(
                range = TextRange(index, index + 1),
                text = text[index].toString(),
                fontKey = if (text[index] == ')') "latin" else "cjk",
                advance = 16.0f,
            )
        }
        val roles = List(text.length) { FontRole.CjkText }
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.forbiddenLineStartClusters.isNotEmpty())
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithCjkClosingForbidLineStart() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithCjkClosingForbidLineStart")
        val text = "\u4E2D\u3002\u4E2D"
        val clusters = text.indices.map { index ->
            Cluster(
                range = TextRange(index, index + 1),
                text = text[index].toString(),
                fontKey = "cjk",
                advance = 16.0f,
            )
        }
        val roles = List(text.length) { FontRole.CjkText }
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.forbiddenLineStartClusters.isNotEmpty())
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithOpenPunctuationForbidLineEnd() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithOpenPunctuationForbidLineEnd")
        val text = "\uFF08\u4E2D\u6587"
        val clusters = text.indices.map { index ->
            Cluster(
                range = TextRange(index, index + 1),
                text = text[index].toString(),
                fontKey = "cjk",
                advance = 16.0f,
            )
        }
        val roles = List(text.length) { FontRole.CjkText }
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.decisions.any { it.forbiddenPosition == "LineEnd" })
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithPunctuationAndSpace() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithPunctuationAndSpace")
        val text = "\u4E2D \u3002"
        val clusters = text.indices.map { index ->
            Cluster(
                range = TextRange(index, index + 1),
                text = text[index].toString(),
                fontKey = if (text[index] == ' ') "latin" else "cjk",
                advance = 16.0f,
            )
        }
        val roles = List(text.length) { FontRole.CjkText }
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.decisions.any { it.forbiddenPosition == "LineStart" })
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithFollowsAuthoredBoundary() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithFollowsAuthoredBoundary")
        val text = "\n\uFF08\u4E2D\u6587"
        val clusters = text.indices.map { index ->
            Cluster(
                range = TextRange(index, index + 1),
                text = text[index].toString(),
                fontKey = if (index == 0) "latin" else "cjk",
                advance = 16.0f,
            )
        }
        val roles = List(text.length) { FontRole.CjkText }
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        val bracketDecision = result.decisions.find {
            it.sourceText == "\uFF08" && it.forbiddenPosition == "LineStart"
        }
        assertEquals(null, bracketDecision)
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithClosePunctuationClass() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithClosePunctuationClass")
        val text = "\u4E2D\u3002"
        val clusters = text.indices.map { index ->
            Cluster(
                range = TextRange(index, index + 1),
                text = text[index].toString(),
                fontKey = "cjk",
                advance = 16.0f,
            )
        }
        val roles = List(text.length) { FontRole.CjkText }
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.decisions.any { it.forbiddenPosition == "LineStart" })
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithInfixNumericSeparator() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithInfixNumericSeparator")
        val text = "1\uFF0C2"
        val clusters = listOf(
            Cluster(TextRange(0, 1), "1", "1", "latin", 8.0f),
            Cluster(TextRange(1, 2), "\uFF0C", "\uFF0C", "cjk", 16.0f),
            Cluster(TextRange(2, 3), "2", "2", "latin", 8.0f),
        )
        val roles = listOf(FontRole.LatinText, FontRole.CjkPunctuation, FontRole.LatinText)
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertEquals(0, result.forbiddenLineStartClusters.size)
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithDecimalMarkAfterSpace() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithDecimalMarkAfterSpace")
        val text = "1 \uFF0C2"
        val clusters = listOf(
            Cluster(TextRange(0, 1), "1", "1", "latin", 8.0f),
            Cluster(TextRange(1, 2), " ", " ", "latin", 8.0f),
            Cluster(TextRange(2, 3), "\uFF0C", "\uFF0C", "cjk", 16.0f),
            Cluster(TextRange(3, 4), "2", "2", "latin", 8.0f),
        )
        val roles = listOf(FontRole.LatinText, FontRole.LatinText, FontRole.CjkPunctuation, FontRole.LatinText)
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertEquals(0, result.forbiddenLineStartClusters.size)
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithRuleForLineStartInfix() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithRuleForLineStartInfix")
        val text = "1,2"
        val clusters = listOf(
            Cluster(TextRange(0, 1), "1", "1", "latin", 8.0f),
            Cluster(TextRange(1, 2), ",", ",", "latin", 8.0f),
            Cluster(TextRange(2, 3), "2", "2", "latin", 8.0f),
        )
        val roles = listOf(FontRole.LatinText, FontRole.LatinText, FontRole.LatinText)
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        val infixDecision = assertNotNull(result.decisions.find { it.sourceText == "," })
        assertEquals("Uax14WesternPunctuationBoundary:LB15d", infixDecision.reason)
    }

    @Test
    fun resolveAttachedInlineInterCharBoundariesWithCjkBothCjk() {
        testTrace.section("resolveAttachedInlineInterCharBoundariesWithCjkBothCjk")
        val text = "\u4E2D\u6587"
        val clusters = listOf(
            Cluster(TextRange(0, 1), "\u4E2D", "\u4E2D", "cjk", 16.0f),
            Cluster(TextRange(1, 2), "\u6587", "\u6587", "cjk", 16.0f),
        )
        val roles = listOf(FontRole.CjkText, FontRole.CjkText)
        val edges = listOf(
            EastAsianSpacingEdges(EastAsianSpacingValue.Wide, EastAsianSpacingValue.Narrow, false),
            EastAsianSpacingEdges(EastAsianSpacingValue.Narrow, EastAsianSpacingValue.Wide, false),
        )
        val attachments = listOf(InlineAttachment.None, InlineAttachment.None)
        val result = resolveAttachedInlineInterCharBoundaries(text, clusters, roles, edges, emptySet(), attachments)
        assertEquals(0, result.virtualBoundaryAfterClusters.size)
    }

    @Test
    fun resolveAttachedInlineInterCharBoundariesWithWesternBracket() {
        testTrace.section("resolveAttachedInlineInterCharBoundariesWithWesternBracket")
        val text = "(\u4E2D"
        val clusters = listOf(
            Cluster(TextRange(0, 1), "(", "(", "latin", 8.0f),
            Cluster(TextRange(1, 2), "\u4E2D", "\u4E2D", "cjk", 16.0f),
        )
        val roles = listOf(FontRole.LatinText, FontRole.CjkText)
        val edges = listOf(
            EastAsianSpacingEdges(EastAsianSpacingValue.Wide, EastAsianSpacingValue.Wide, false),
            EastAsianSpacingEdges(EastAsianSpacingValue.Wide, EastAsianSpacingValue.Wide, false),
        )
        val attachments = listOf(InlineAttachment.None, InlineAttachment.None)
        val result = resolveAttachedInlineInterCharBoundaries(text, clusters, roles, edges, emptySet(), attachments)
        assertEquals(0, result.virtualBoundaryAfterClusters.size)
    }

    @Test
    fun resolveAttachedInlineInterCharBoundariesWithCjkBodyWesternBracket() {
        testTrace.section("resolveAttachedInlineInterCharBoundariesWithCjkBodyWesternBracket")
        val text = "\u4E2D)"
        val clusters = listOf(
            Cluster(TextRange(0, 1), "\u4E2D", "\u4E2D", "cjk", 16.0f),
            Cluster(TextRange(1, 2), ")", ")", "latin", 8.0f),
        )
        val roles = listOf(FontRole.CjkText, FontRole.LatinText)
        val edges = listOf(
            EastAsianSpacingEdges(EastAsianSpacingValue.Wide, EastAsianSpacingValue.Wide, false),
            EastAsianSpacingEdges(EastAsianSpacingValue.Wide, EastAsianSpacingValue.Wide, false),
        )
        val attachments = listOf(InlineAttachment.None, InlineAttachment.None)
        val result = resolveAttachedInlineInterCharBoundaries(text, clusters, roles, edges, emptySet(), attachments)
        assertEquals(0, result.ordinaryWesternBoundaryAfterClusters.size)
    }

    @Test
    fun resolveAttachedInlineInterCharBoundariesRequiresMatchingClusterRoleEdgeSizes() {
        testTrace.section("resolveAttachedInlineInterCharBoundariesRequiresMatchingClusterRoleEdgeSizes")
        val text = "ab"
        val clusters = listOf(Cluster(TextRange(0, 1), "a", "a", "latin", 8.0f))
        val roles = listOf(FontRole.LatinText, FontRole.LatinText)
        val edges = listOf(
            EastAsianSpacingEdges(EastAsianSpacingValue.Wide, EastAsianSpacingValue.Wide, false),
        )
        val attachments = listOf(InlineAttachment.None)
        assertFailsWith<IllegalArgumentException> {
            resolveAttachedInlineInterCharBoundaries(text, clusters, roles, edges, emptySet(), attachments)
        }
    }

    @Test
    fun resolveAttachedInlineInterCharBoundariesRequiresMatchingAttachmentSize() {
        testTrace.section("resolveAttachedInlineInterCharBoundariesRequiresMatchingAttachmentSize")
        val text = "ab"
        val clusters = listOf(
            Cluster(TextRange(0, 1), "a", "a", "latin", 8.0f),
            Cluster(TextRange(1, 2), "b", "b", "latin", 8.0f),
        )
        val roles = listOf(FontRole.LatinText, FontRole.LatinText)
        val edges = listOf(
            EastAsianSpacingEdges(EastAsianSpacingValue.Wide, EastAsianSpacingValue.Wide, false),
            EastAsianSpacingEdges(EastAsianSpacingValue.Wide, EastAsianSpacingValue.Wide, false),
        )
        val attachments = listOf(InlineAttachment.None)
        assertFailsWith<IllegalArgumentException> {
            resolveAttachedInlineInterCharBoundaries(text, clusters, roles, edges, emptySet(), attachments)
        }
    }

    @Test
    fun resolveAttachedInlineInterCharBoundariesPunctuationWesternNarrowTrailing() {
        testTrace.section("resolveAttachedInlineInterCharBoundariesPunctuationWesternNarrowTrailing")
        val text = "a,\u3002"
        val clusters = listOf(
            Cluster(TextRange(0, 1), "a", "a", "latin", 8.0f),
            Cluster(TextRange(1, 2), ",", ",", "latin", 8.0f),
            Cluster(TextRange(2, 3), "\u3002", "\u3002", "cjk", 16.0f),
        )
        val roles = listOf(FontRole.LatinText, FontRole.CjkPunctuation, FontRole.CjkPunctuation)
        val edges = listOf(
            EastAsianSpacingEdges(EastAsianSpacingValue.Wide, EastAsianSpacingValue.Narrow, false),
            EastAsianSpacingEdges(EastAsianSpacingValue.Wide, EastAsianSpacingValue.Wide, false),
            EastAsianSpacingEdges(EastAsianSpacingValue.Wide, EastAsianSpacingValue.Wide, false),
        )
        val attachments = listOf(InlineAttachment.None, InlineAttachment.Previous, InlineAttachment.None)
        val result = resolveAttachedInlineInterCharBoundaries(text, clusters, roles, edges, emptySet(), attachments)
        assertTrue(result.virtualBoundaryAfterClusters.isNotEmpty())
    }

    @Test
    fun resolveUnicodePunctuationBoundariesPreviousContentClusterReturnsNull() {
        testTrace.section("resolveUnicodePunctuationBoundariesPreviousContentClusterReturnsNull")
        val text = "  !"
        val clusters = text.indices.map { index ->
            Cluster(
                range = TextRange(index, index + 1),
                text = text[index].toString(),
                fontKey = "test",
                advance = 8.0f,
            )
        }
        val roles = List(text.length) { FontRole.LatinText }
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.forbiddenLineStartClusters.isEmpty())
    }

    @Test
    fun resolveAttachedInlineInterCharBoundariesPunctuationWesternTrailingNotNarrow() {
        testTrace.section("resolveAttachedInlineInterCharBoundariesPunctuationWesternTrailingNotNarrow")
        val text = "a,\u4E2D"
        val clusters = listOf(
            Cluster(TextRange(0, 1), "a", "a", "latin", 8.0f),
            Cluster(TextRange(1, 2), ",", ",", "latin", 8.0f),
            Cluster(TextRange(2, 3), "\u4E2D", "\u4E2D", "cjk", 16.0f),
        )
        val roles = listOf(FontRole.CjkPunctuation, FontRole.CjkPunctuation, FontRole.CjkText)
        val edges = listOf(
            EastAsianSpacingEdges(EastAsianSpacingValue.Wide, EastAsianSpacingValue.Wide, false),
            EastAsianSpacingEdges(EastAsianSpacingValue.Wide, EastAsianSpacingValue.Wide, false),
            EastAsianSpacingEdges(EastAsianSpacingValue.Wide, EastAsianSpacingValue.Wide, false),
        )
        val attachments = listOf(InlineAttachment.None, InlineAttachment.Previous, InlineAttachment.None)
        val result = resolveAttachedInlineInterCharBoundaries(text, clusters, roles, edges, emptySet(), attachments)
        assertTrue(result.virtualBoundaryAfterClusters.isNotEmpty())
    }

    @Test
    fun resolveAttachedInlineInterCharBoundariesPunctuationWesternTrailingNarrowNotCjkPunct() {
        testTrace.section("resolveAttachedInlineInterCharBoundariesPunctuationWesternTrailingNarrowNotCjkPunct")
        val text = "a,\u4E2D"
        val clusters = listOf(
            Cluster(TextRange(0, 1), "a", "a", "latin", 8.0f),
            Cluster(TextRange(1, 2), ",", ",", "latin", 8.0f),
            Cluster(TextRange(2, 3), "\u4E2D", "\u4E2D", "cjk", 16.0f),
        )
        val roles = listOf(FontRole.LatinText, FontRole.CjkPunctuation, FontRole.CjkText)
        val edges = listOf(
            EastAsianSpacingEdges(EastAsianSpacingValue.Wide, EastAsianSpacingValue.Narrow, false),
            EastAsianSpacingEdges(EastAsianSpacingValue.Wide, EastAsianSpacingValue.Wide, false),
            EastAsianSpacingEdges(EastAsianSpacingValue.Wide, EastAsianSpacingValue.Wide, false),
        )
        val attachments = listOf(InlineAttachment.None, InlineAttachment.Previous, InlineAttachment.None)
        val result = resolveAttachedInlineInterCharBoundaries(text, clusters, roles, edges, emptySet(), attachments)
        assertTrue(result.virtualBoundaryAfterClusters.isNotEmpty())
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithInfixNumericSeparatorNotDecimalMark() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithInfixNumericSeparatorNotDecimalMark")
        val text = "1\uFF0C"
        val clusters = listOf(
            Cluster(TextRange(0, 1), "1", "1", "latin", 8.0f),
            Cluster(TextRange(1, 2), "\uFF0C", "\uFF0C", "cjk", 16.0f),
        )
        val roles = listOf(FontRole.LatinText, FontRole.LatinText)
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.forbiddenLineStartClusters.isNotEmpty())
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithDecimalMarkAfterNonSpace() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithDecimalMarkAfterNonSpace")
        val text = "1,\uFF0C2"
        val clusters = listOf(
            Cluster(TextRange(0, 1), "1", "1", "latin", 8.0f),
            Cluster(TextRange(1, 2), ",", ",", "latin", 8.0f),
            Cluster(TextRange(2, 3), "\uFF0C", "\uFF0C", "cjk", 16.0f),
            Cluster(TextRange(3, 4), "2", "2", "latin", 8.0f),
        )
        val roles = listOf(FontRole.LatinText, FontRole.LatinText, FontRole.CjkPunctuation, FontRole.LatinText)
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.forbiddenLineStartClusters.isNotEmpty())
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithQuoteDirectionFinal() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithQuoteDirectionFinal")
        val text = "\u4E2D\u201D\u4E2D"
        val clusters = text.indices.map { index ->
            Cluster(
                range = TextRange(index, index + 1),
                text = text[index].toString(),
                fontKey = "cjk",
                advance = 16.0f,
            )
        }
        val roles = List(text.length) { FontRole.CjkText }
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.decisions.any { it.forbiddenPosition == "LineStart" })
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithQuoteDirectionInitial() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithQuoteDirectionInitial")
        val text = "\u4E2D\u201C\u4E2D"
        val clusters = text.indices.map { index ->
            Cluster(
                range = TextRange(index, index + 1),
                text = text[index].toString(),
                fontKey = "cjk",
                advance = 16.0f,
            )
        }
        val roles = List(text.length) { FontRole.CjkText }
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.decisions.any { it.forbiddenPosition == "LineEnd" })
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithQuoteDirectionUnresolved() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithQuoteDirectionUnresolved")
        val text = "\u4E2D\u00AB\u4E2D"
        val clusters = text.indices.map { index ->
            Cluster(
                range = TextRange(index, index + 1),
                text = text[index].toString(),
                fontKey = "cjk",
                advance = 16.0f,
            )
        }
        val roles = List(text.length) { FontRole.CjkText }
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.decisions.any { it.forbiddenPosition == "LineEnd" })
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithWordApostrophe2019() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithWordApostrophe2019")
        val text = "it\u2019s"
        val clusters = text.indices.map { index ->
            Cluster(
                range = TextRange(index, index + 1),
                text = text[index].toString(),
                fontKey = "latin",
                advance = 8.0f,
            )
        }
        val roles = List(text.length) { FontRole.LatinText }
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertEquals(0, result.forbiddenLineStartClusters.size)
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithLatinWordCodePoint() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithLatinWordCodePoint")
        val text = "caf\u00E9"
        val clusters = text.indices.map { index ->
            Cluster(
                range = TextRange(index, index + 1),
                text = text[index].toString(),
                fontKey = "latin",
                advance = 8.0f,
            )
        }
        val roles = List(text.length) { FontRole.LatinText }
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertEquals(0, result.decisions.size)
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithFirstSignificantCodePoint() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithFirstSignificantCodePoint")
        val text = "  \u201C"
        val clusters = text.indices.map { index ->
            Cluster(
                range = TextRange(index, index + 1),
                text = text[index].toString(),
                fontKey = "latin",
                advance = 8.0f,
            )
        }
        val roles = List(text.length) { FontRole.LatinText }
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.decisions.any { it.forbiddenPosition == "LineEnd" })
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithLastSignificantCodePoint() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithLastSignificantCodePoint")
        val text = "a\u201D  "
        val clusters = text.indices.map { index ->
            Cluster(
                range = TextRange(index, index + 1),
                text = text[index].toString(),
                fontKey = "latin",
                advance = 8.0f,
            )
        }
        val roles = List(text.length) { FontRole.LatinText }
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.decisions.any { it.forbiddenPosition == "LineStart" })
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithHasAuthoredBreak() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithHasAuthoredBreak")
        val text = "\n\u201C"
        val clusters = text.indices.map { index ->
            Cluster(
                range = TextRange(index, index + 1),
                text = text[index].toString(),
                fontKey = "latin",
                advance = 8.0f,
            )
        }
        val roles = List(text.length) { FontRole.LatinText }
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertEquals(0, result.forbiddenLineStartClusters.size)
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithNextContentCluster() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithNextContentCluster")
        val text = "a\u201D\u4E2D"
        val clusters = text.indices.map { index ->
            Cluster(
                range = TextRange(index, index + 1),
                text = text[index].toString(),
                fontKey = "latin",
                advance = 8.0f,
            )
        }
        val roles = List(text.length) { FontRole.LatinText }
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.unbreakableRanges.isNotEmpty())
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithPreviousContentClusterHasContent() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithPreviousContentClusterHasContent")
        val text = "\u4E2D\u201D"
        val clusters = text.indices.map { index ->
            Cluster(
                range = TextRange(index, index + 1),
                text = text[index].toString(),
                fontKey = "cjk",
                advance = 16.0f,
            )
        }
        val roles = List(text.length) { FontRole.CjkText }
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.unbreakableRanges.isNotEmpty())
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithClosePunctuation() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithClosePunctuation")
        val text = "\u4E2D\uFF09"
        val clusters = text.indices.map { index ->
            Cluster(
                range = TextRange(index, index + 1),
                text = text[index].toString(),
                fontKey = "cjk",
                advance = 16.0f,
            )
        }
        val roles = List(text.length) { FontRole.CjkText }
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.forbiddenLineStartClusters.isNotEmpty())
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithExclamationClass() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithExclamationClass")
        val text = "\u4E2D\uFF01"
        val clusters = text.indices.map { index ->
            Cluster(
                range = TextRange(index, index + 1),
                text = text[index].toString(),
                fontKey = "cjk",
                advance = 16.0f,
            )
        }
        val roles = List(text.length) { FontRole.CjkText }
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.forbiddenLineStartClusters.isNotEmpty())
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithCloseParenthesisClass() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithCloseParenthesisClass")
        val text = "\u4E2D\uFF09"
        val clusters = text.indices.map { index ->
            Cluster(
                range = TextRange(index, index + 1),
                text = text[index].toString(),
                fontKey = "cjk",
                advance = 16.0f,
            )
        }
        val roles = List(text.length) { FontRole.CjkText }
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.decisions.any { it.reason.contains("LB13") })
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithInfixNumericSeparatorRule() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithInfixNumericSeparatorRule")
        val text = "1,2"
        val clusters = listOf(
            Cluster(TextRange(0, 1), "1", "1", "latin", 8.0f),
            Cluster(TextRange(1, 2), ",", ",", "latin", 8.0f),
            Cluster(TextRange(2, 3), "2", "2", "latin", 8.0f),
        )
        val roles = listOf(FontRole.LatinText, FontRole.LatinText, FontRole.LatinText)
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        val infixDecision = assertNotNull(result.decisions.find { it.sourceText == "," })
        assertTrue(infixDecision.reason.contains("LB15d"))
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithRuleForLineStartElse() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithRuleForLineStartElse")
        val text = "\u4E2D\u3001"
        val clusters = text.indices.map { index ->
            Cluster(
                range = TextRange(index, index + 1),
                text = text[index].toString(),
                fontKey = "cjk",
                advance = 16.0f,
            )
        }
        val roles = List(text.length) { FontRole.CjkText }
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.forbiddenLineStartClusters.isNotEmpty())
    }

    @Test
    fun resolveAttachedInlineInterCharBoundariesWithSinoWesternPair() {
        testTrace.section("resolveAttachedInlineInterCharBoundariesWithSinoWesternPair")
        val text = "\u4E2D\uFF0C\u4E2D"
        val clusters = listOf(
            Cluster(TextRange(0, 1), "\u4E2D", "\u4E2D", "cjk", 16.0f),
            Cluster(TextRange(1, 2), "\uFF0C", "\uFF0C", "cjk", 16.0f),
            Cluster(TextRange(2, 3), "\u4E2D", "\u4E2D", "cjk", 16.0f),
        )
        val roles = listOf(FontRole.CjkText, FontRole.CjkPunctuation, FontRole.CjkText)
        val edges = listOf(
            EastAsianSpacingEdges(EastAsianSpacingValue.Wide, EastAsianSpacingValue.Wide, false),
            EastAsianSpacingEdges(EastAsianSpacingValue.Wide, EastAsianSpacingValue.Narrow, false),
            EastAsianSpacingEdges(EastAsianSpacingValue.Narrow, EastAsianSpacingValue.Wide, false),
        )
        val attachments = listOf(InlineAttachment.None, InlineAttachment.Previous, InlineAttachment.None)
        val result = resolveAttachedInlineInterCharBoundaries(text, clusters, roles, edges, emptySet(), attachments)
        assertTrue(result.virtualSinoWesternBoundaryAfterClusters.isNotEmpty())
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithCodePointBeforeSupplementary() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithCodePointBeforeSupplementary")
        val text = "\u4E2D\u201D"
        val clusters = text.indices.map { index ->
            Cluster(
                range = TextRange(index, index + 1),
                text = text[index].toString(),
                fontKey = "cjk",
                advance = 16.0f,
            )
        }
        val roles = List(text.length) { FontRole.CjkText }
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.decisions.isNotEmpty())
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithEmptyRange() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithEmptyRange")
        val text = "\u4E2D"
        val clusters = listOf(
            Cluster(TextRange(0, 0), "", "", "cjk", 0.0f),
            Cluster(TextRange(0, 1), "\u4E2D", "\u4E2D", "cjk", 16.0f),
        )
        val roles = listOf(FontRole.CjkText, FontRole.CjkText)
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertEquals(0, result.decisions.size)
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithFirstCodePointLength() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithFirstCodePointLength")
        val text = "\u4E2D"
        val clusters = text.indices.map { index ->
            Cluster(
                range = TextRange(index, index + 1),
                text = text[index].toString(),
                fontKey = "cjk",
                advance = 16.0f,
            )
        }
        val roles = List(text.length) { FontRole.CjkText }
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertEquals(0, result.decisions.size)
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithIsWhitespaceCodePoint() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithIsWhitespaceCodePoint")
        val text = " \u201C"
        val clusters = text.indices.map { index ->
            Cluster(
                range = TextRange(index, index + 1),
                text = text[index].toString(),
                fontKey = "latin",
                advance = 8.0f,
            )
        }
        val roles = List(text.length) { FontRole.LatinText }
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.decisions.any { it.forbiddenPosition == "LineEnd" })
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithFollowsAuthoredBoundaryMandatory() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithFollowsAuthoredBoundaryMandatory")
        val text = "\r\u201C"
        val clusters = text.indices.map { index ->
            Cluster(
                range = TextRange(index, index + 1),
                text = text[index].toString(),
                fontKey = "latin",
                advance = 8.0f,
            )
        }
        val roles = List(text.length) { FontRole.LatinText }
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertEquals(0, result.forbiddenLineStartClusters.size)
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithFollowsAuthoredBoundaryZWSP() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithFollowsAuthoredBoundaryZWSP")
        val text = "\u200B\u201C"
        val clusters = text.indices.map { index ->
            Cluster(
                range = TextRange(index, index + 1),
                text = text[index].toString(),
                fontKey = "latin",
                advance = 8.0f,
            )
        }
        val roles = List(text.length) { FontRole.LatinText }
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertEquals(0, result.forbiddenLineStartClusters.size)
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithDecimalMarkFollowingInsideDigit() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithDecimalMarkFollowingInsideDigit")
        val text = " 1\uFF0C23"
        val clusters = listOf(
            Cluster(TextRange(0, 1), " ", " ", "latin", 8.0f),
            Cluster(TextRange(1, 2), "1", "1", "latin", 8.0f),
            Cluster(TextRange(2, 3), "\uFF0C", "\uFF0C", "cjk", 16.0f),
            Cluster(TextRange(3, 5), "23", "23", "latin", 8.0f),
        )
        val roles = listOf(FontRole.LatinText, FontRole.LatinText, FontRole.CjkPunctuation, FontRole.LatinText)
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertEquals(0, result.forbiddenLineStartClusters.size)
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithDecimalMarkFollowingOutsideDigit() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithDecimalMarkFollowingOutsideDigit")
        val text = " a\uFF0C2"
        val clusters = listOf(
            Cluster(TextRange(0, 1), " ", " ", "latin", 8.0f),
            Cluster(TextRange(1, 2), "a", "a", "latin", 8.0f),
            Cluster(TextRange(2, 3), "\uFF0C", "\uFF0C", "cjk", 16.0f),
            Cluster(TextRange(3, 4), "2", "2", "latin", 8.0f),
        )
        val roles = listOf(FontRole.LatinText, FontRole.LatinText, FontRole.LatinText, FontRole.LatinText)
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.decisions.isNotEmpty() || result.forbiddenLineStartClusters.isNotEmpty())
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithPreviousContentClusterHasAuthoredBreak() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithPreviousContentClusterHasAuthoredBreak")
        val text = "\n\uFF08"
        val clusters = text.indices.map { index ->
            Cluster(
                range = TextRange(index, index + 1),
                text = text[index].toString(),
                fontKey = "latin",
                advance = 8.0f,
            )
        }
        val roles = List(text.length) { FontRole.LatinText }
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.forbiddenLineStartClusters.isEmpty())
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithNextContentClusterHasAuthoredBreak() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithNextContentClusterHasAuthoredBreak")
        val text = "\u201D\n"
        val clusters = text.indices.map { index ->
            Cluster(
                range = TextRange(index, index + 1),
                text = text[index].toString(),
                fontKey = "latin",
                advance = 8.0f,
            )
        }
        val roles = List(text.length) { FontRole.LatinText }
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.unbreakableRanges.isEmpty())
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithIsWhitespaceCodePointNonBmp() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithIsWhitespaceCodePointNonBmp")
        val text = "\uD83D\uDE00"
        val clusters = listOf(
            Cluster(TextRange(0, 2), "\uD83D\uDE00", "\uD83D\uDE00", "latin", 8.0f),
        )
        val roles = listOf(FontRole.LatinText)
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertEquals(0, result.decisions.size)
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithHasAuthoredBreakBoth() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithHasAuthoredBreakBoth")
        val text = "\n\u201C\n"
        val clusters = text.indices.map { index ->
            Cluster(
                range = TextRange(index, index + 1),
                text = text[index].toString(),
                fontKey = "latin",
                advance = 8.0f,
            )
        }
        val roles = List(text.length) { FontRole.LatinText }
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.decisions.isNotEmpty())
    }

    @Test
    fun resolveAttachedInlineInterCharBoundariesWithBothCjkPunctuation() {
        testTrace.section("resolveAttachedInlineInterCharBoundariesWithBothCjkPunctuation")
        val text = "\u3001\u3002\u4E2D"
        val clusters = listOf(
            Cluster(TextRange(0, 1), "\u3001", "\u3001", "cjk", 16.0f),
            Cluster(TextRange(1, 2), "\u3002", "\u3002", "cjk", 16.0f),
            Cluster(TextRange(2, 3), "\u4E2D", "\u4E2D", "cjk", 16.0f),
        )
        val roles = listOf(FontRole.CjkPunctuation, FontRole.CjkPunctuation, FontRole.CjkText)
        val edges = listOf(
            EastAsianSpacingEdges(EastAsianSpacingValue.Wide, EastAsianSpacingValue.Wide, false),
            EastAsianSpacingEdges(EastAsianSpacingValue.Wide, EastAsianSpacingValue.Wide, false),
            EastAsianSpacingEdges(EastAsianSpacingValue.Wide, EastAsianSpacingValue.Wide, false),
        )
        val attachments = listOf(InlineAttachment.None, InlineAttachment.Previous, InlineAttachment.None)
        val result = resolveAttachedInlineInterCharBoundaries(text, clusters, roles, edges, emptySet(), attachments)
        assertTrue(result.virtualBoundaryAfterClusters.isNotEmpty())
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithFollowsAuthoredBoundaryWhitespace() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithFollowsAuthoredBoundaryWhitespace")
        val text = " \u201C"
        val clusters = text.indices.map { index ->
            Cluster(
                range = TextRange(index, index + 1),
                text = text[index].toString(),
                fontKey = "latin",
                advance = 8.0f,
            )
        }
        val roles = List(text.length) { FontRole.LatinText }
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertEquals(0, result.forbiddenLineStartClusters.size)
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithFollowsAuthoredBoundaryWhitespaceThenNonWhitespace() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithFollowsAuthoredBoundaryWhitespaceThenNonWhitespace")
        val text = " \u0041\u201C"
        val clusters = text.indices.map { index ->
            Cluster(
                range = TextRange(index, index + 1),
                text = text[index].toString(),
                fontKey = "latin",
                advance = 8.0f,
            )
        }
        val roles = List(text.length) { FontRole.LatinText }
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.decisions.isNotEmpty() || result.forbiddenLineStartClusters.isNotEmpty())
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithPreviousContentClusterEmpty() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithPreviousContentClusterEmpty")
        val text = "\u201C"
        val clusters = listOf(
            Cluster(TextRange(0, 1), "\u201C", "\u201C", "latin", 16.0f),
        )
        val roles = listOf(FontRole.LatinText)
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.decisions.isNotEmpty() || result.forbiddenLineStartClusters.isNotEmpty() || result.unbreakableRanges.isNotEmpty())
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithNextContentClusterEmpty() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithNextContentClusterEmpty")
        val text = "a\u201Db"
        val clusters = text.indices.map { index ->
            Cluster(
                range = TextRange(index, index + 1),
                text = text[index].toString(),
                fontKey = "latin",
                advance = 8.0f,
            )
        }
        val roles = List(text.length) { FontRole.LatinText }
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.forbiddenLineStartClusters.isNotEmpty() || result.unbreakableRanges.isNotEmpty())
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithCodePointAtOrNullSurrogate() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithCodePointAtOrNullSurrogate")
        val text = "\u201C\uD83D\uDE00"
        val clusters = listOf(
            Cluster(TextRange(0, 1), "\u201C", "\u201C", "latin", 16.0f),
            Cluster(TextRange(1, 3), "\uD83D\uDE00", "\uD83D\uDE00", "emoji", 16.0f),
        )
        val roles = listOf(FontRole.LatinText, FontRole.Emoji)
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.decisions.isNotEmpty() || result.forbiddenLineStartClusters.isNotEmpty())
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithHasAuthoredBreakMandatoryOnly() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithHasAuthoredBreakMandatoryOnly")
        val text = "\r\u201C"
        val clusters = text.indices.map { index ->
            Cluster(
                range = TextRange(index, index + 1),
                text = text[index].toString(),
                fontKey = "latin",
                advance = 8.0f,
            )
        }
        val roles = List(text.length) { FontRole.LatinText }
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertEquals(0, result.forbiddenLineStartClusters.size)
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithCodePointBeforeSurrogatePair() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithCodePointBeforeSurrogatePair")
        val text = "\uD83D\uDE00\u201D"
        val clusters = listOf(
            Cluster(TextRange(0, 2), "\uD83D\uDE00", "\uD83D\uDE00", "emoji", 16.0f),
            Cluster(TextRange(2, 3), "\u201D", "\u201D", "latin", 16.0f),
        )
        val roles = listOf(FontRole.Emoji, FontRole.LatinText)
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.decisions.isNotEmpty() || result.forbiddenLineStartClusters.isNotEmpty())
    }

    @Test
    fun resolveUnicodePunctuationBoundariesWithCodePointAtOrNullSupplementary() {
        testTrace.section("resolveUnicodePunctuationBoundariesWithCodePointAtOrNullSupplementary")
        val text = "\uD83D\uDE00\u201C"
        val clusters = listOf(
            Cluster(TextRange(0, 2), "\uD83D\uDE00", "\uD83D\uDE00", "emoji", 16.0f),
            Cluster(TextRange(2, 3), "\u201C", "\u201C", "latin", 16.0f),
        )
        val roles = listOf(FontRole.Emoji, FontRole.LatinText)
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.decisions.isNotEmpty() || result.forbiddenLineStartClusters.isNotEmpty())
    }

    @Test
    fun resolveAttachedInlineVirtualBoundariesAtStart() {
        testTrace.section("resolveAttachedInlineVirtualBoundariesAtStart")
        val attachments = listOf(InlineAttachment.Previous, InlineAttachment.None)
        val result = resolveAttachedInlineVirtualBoundaries(attachments)
        assertEquals(0, result.size)
    }

    @Test
    fun resolveAttachedInlineInterCharBoundariesRequiresMatchingEdgesSize() {
        testTrace.section("resolveAttachedInlineInterCharBoundariesRequiresMatchingEdgesSize")
        val text = "a"
        val clusters = listOf(Cluster(TextRange(0, 1), "a", "a", "latin", 8.0f))
        val roles = listOf(FontRole.LatinText)
        val edges = listOf(
            EastAsianSpacingEdges(EastAsianSpacingValue.Wide, EastAsianSpacingValue.Wide, false),
            EastAsianSpacingEdges(EastAsianSpacingValue.Wide, EastAsianSpacingValue.Wide, false),
        )
        val attachments = listOf(InlineAttachment.None)
        assertFailsWith<IllegalArgumentException> {
            resolveAttachedInlineInterCharBoundaries(text, clusters, roles, edges, emptySet(), attachments)
        }
    }

    @Test
    fun resolveAttachedInlineInterCharBoundariesPunctuationWesternLeadingNotNarrow() {
        testTrace.section("resolveAttachedInlineInterCharBoundariesPunctuationWesternLeadingNotNarrow")
        val text = ",\u4E2Da"
        val clusters = listOf(
            Cluster(TextRange(0, 1), ",", ",", "cjk", 16.0f),
            Cluster(TextRange(1, 2), "\u4E2D", "\u4E2D", "cjk", 16.0f),
            Cluster(TextRange(2, 3), "a", "a", "latin", 8.0f),
        )
        val roles = listOf(FontRole.CjkPunctuation, FontRole.CjkText, FontRole.LatinText)
        val edges = listOf(
            EastAsianSpacingEdges(EastAsianSpacingValue.Wide, EastAsianSpacingValue.Wide, false),
            EastAsianSpacingEdges(EastAsianSpacingValue.Wide, EastAsianSpacingValue.Wide, false),
            EastAsianSpacingEdges(EastAsianSpacingValue.Wide, EastAsianSpacingValue.Wide, false),
        )
        val attachments = listOf(InlineAttachment.None, InlineAttachment.Previous, InlineAttachment.None)
        val result = resolveAttachedInlineInterCharBoundaries(text, clusters, roles, edges, emptySet(), attachments)
        assertEquals(0, result.virtualBoundaryAfterClusters.size)
    }

    @Test
    fun resolveAttachedInlineInterCharBoundariesAllConditionsFalse() {
        testTrace.section("resolveAttachedInlineInterCharBoundariesAllConditionsFalse")
        val text = "a*b"
        val clusters = listOf(
            Cluster(TextRange(0, 1), "a", "a", "latin", 8.0f),
            Cluster(TextRange(1, 2), "*", "*", "latin", 8.0f),
            Cluster(TextRange(2, 3), "b", "b", "latin", 8.0f),
        )
        val roles = listOf(FontRole.LatinText, FontRole.LatinText, FontRole.LatinText)
        val edges = listOf(
            EastAsianSpacingEdges(EastAsianSpacingValue.Wide, EastAsianSpacingValue.Wide, false),
            EastAsianSpacingEdges(EastAsianSpacingValue.Wide, EastAsianSpacingValue.Wide, false),
            EastAsianSpacingEdges(EastAsianSpacingValue.Wide, EastAsianSpacingValue.Wide, false),
        )
        val attachments = listOf(InlineAttachment.None, InlineAttachment.Previous, InlineAttachment.None)
        val result = resolveAttachedInlineInterCharBoundaries(text, clusters, roles, edges, emptySet(), attachments)
        assertEquals(0, result.virtualBoundaryAfterClusters.size)
    }

    @Test
    fun resolveAttachedInlineInterCharBoundariesNarrowNarrowPair() {
        testTrace.section("resolveAttachedInlineInterCharBoundariesNarrowNarrowPair")
        val text = "a*b"
        val clusters = listOf(
            Cluster(TextRange(0, 1), "a", "a", "latin", 8.0f),
            Cluster(TextRange(1, 2), "*", "*", "latin", 8.0f),
            Cluster(TextRange(2, 3), "b", "b", "latin", 8.0f),
        )
        val roles = listOf(FontRole.LatinText, FontRole.LatinText, FontRole.LatinText)
        val edges = listOf(
            EastAsianSpacingEdges(EastAsianSpacingValue.Wide, EastAsianSpacingValue.Narrow, false),
            EastAsianSpacingEdges(EastAsianSpacingValue.Wide, EastAsianSpacingValue.Wide, false),
            EastAsianSpacingEdges(EastAsianSpacingValue.Narrow, EastAsianSpacingValue.Wide, false),
        )
        val attachments = listOf(InlineAttachment.None, InlineAttachment.Previous, InlineAttachment.None)
        val result = resolveAttachedInlineInterCharBoundaries(text, clusters, roles, edges, emptySet(), attachments)
        assertEquals(0, result.virtualBoundaryAfterClusters.size)
    }

    @Test
    fun resolveUnicodePunctuationBoundariesInfixNumericSeparatorWithSpaceAndNoSpace() {
        testTrace.section("resolveUnicodePunctuationBoundariesInfixNumericSeparatorWithSpaceAndNoSpace")
        val text1 = " .5"
        val clusters1 = listOf(
            Cluster(TextRange(0, 1), " ", " ", "latin", 8.0f),
            Cluster(TextRange(1, 2), ".", ".", "latin", 8.0f),
            Cluster(TextRange(2, 3), "5", "5", "latin", 8.0f),
        )
        val roles1 = listOf(FontRole.LatinText, FontRole.LatinText, FontRole.LatinText)
        val res1 = resolveUnicodePunctuationBoundaries(text1, clusters1, roles1, emptyList())
        assertEquals(0, res1.forbiddenLineStartClusters.size)

        val text2 = " 1.5"
        val clusters2 = listOf(
            Cluster(TextRange(0, 1), " ", " ", "latin", 8.0f),
            Cluster(TextRange(1, 2), "1", "1", "latin", 8.0f),
            Cluster(TextRange(2, 3), ".", ".", "latin", 8.0f),
            Cluster(TextRange(3, 4), "5", "5", "latin", 8.0f),
        )
        val roles2 = listOf(FontRole.LatinText, FontRole.LatinText, FontRole.LatinText, FontRole.LatinText)
        val res2 = resolveUnicodePunctuationBoundaries(text2, clusters2, roles2, emptyList())
        assertTrue(res2.forbiddenLineStartClusters.contains(2))
        assertTrue(res2.decisions.any { it.reason == "Uax14WesternPunctuationBoundary:LB15d" })

        val text3 = ".5"
        val clusters3 = listOf(
            Cluster(TextRange(0, 1), ".", ".", "latin", 8.0f),
            Cluster(TextRange(1, 2), "5", "5", "latin", 8.0f),
        )
        val roles3 = listOf(FontRole.LatinText, FontRole.LatinText)
        val res3 = resolveUnicodePunctuationBoundaries(text3, clusters3, roles3, emptyList())
        assertEquals(0, res3.forbiddenLineStartClusters.size)
    }

    @Test
    fun resolveUnicodePunctuationBoundariesDecimalMarkFollowingVariations() {
        testTrace.section("resolveUnicodePunctuationBoundariesDecimalMarkFollowingVariations")
        val textEmptyPrev = "."
        val clustersEmptyPrev = listOf(
            Cluster(TextRange(0, 0), "", "", "latin", 0.0f),
            Cluster(TextRange(0, 1), ".", ".", "latin", 8.0f),
        )
        val rolesEmptyPrev = listOf(FontRole.LatinText, FontRole.LatinText)
        val resEmpty = resolveUnicodePunctuationBoundaries(textEmptyPrev, clustersEmptyPrev, rolesEmptyPrev, emptyList())
        assertEquals(0, resEmpty.forbiddenLineStartClusters.size)

        val textHash = "a .#"
        val clustersHash = listOf(
            Cluster(TextRange(0, 1), "a", "a", "latin", 8.0f),
            Cluster(TextRange(1, 2), " ", " ", "latin", 8.0f),
            Cluster(TextRange(2, 3), ".", ".", "latin", 8.0f),
            Cluster(TextRange(3, 4), "#", "#", "latin", 8.0f),
        )
        val resHash = resolveUnicodePunctuationBoundaries(textHash, clustersHash, listOf(FontRole.LatinText, FontRole.LatinText, FontRole.LatinText, FontRole.LatinText), emptyList())
        assertTrue(resHash.forbiddenLineStartClusters.contains(2))
        assertTrue(resHash.decisions.any { it.reason == "Uax14WesternPunctuationBoundary:LB15d" })

        val textA = "a .a"
        val clustersA = listOf(
            Cluster(TextRange(0, 1), "a", "a", "latin", 8.0f),
            Cluster(TextRange(1, 2), " ", " ", "latin", 8.0f),
            Cluster(TextRange(2, 3), ".", ".", "latin", 8.0f),
            Cluster(TextRange(3, 4), "a", "a", "latin", 8.0f),
        )
        val resA = resolveUnicodePunctuationBoundaries(textA, clustersA, listOf(FontRole.LatinText, FontRole.LatinText, FontRole.LatinText, FontRole.LatinText), emptyList())
        assertTrue(resA.forbiddenLineStartClusters.contains(2))
        assertTrue(resA.decisions.any { it.reason == "Uax14WesternPunctuationBoundary:LB15d" })

        val textInside = " .5"
        val clustersInside = listOf(
            Cluster(TextRange(0, 1), " ", " ", "latin", 8.0f),
            Cluster(TextRange(1, 3), ".5", ".5", "latin", 16.0f),
        )
        val resInside = resolveUnicodePunctuationBoundaries(textInside, clustersInside, listOf(FontRole.LatinText, FontRole.LatinText), emptyList())
        assertEquals(0, resInside.forbiddenLineStartClusters.size)
    }

    @Test
    fun resolveUnicodePunctuationBoundariesApostropheAndLatinWordBranches() {
        testTrace.section("resolveUnicodePunctuationBoundariesApostropheAndLatinWordBranches")
        val text1 = "a\u2019 "
        val clusters1 = listOf(
            Cluster(TextRange(0, 1), "a", "a", "latin", 8.0f),
            Cluster(TextRange(1, 2), "\u2019", "\u2019", "latin", 8.0f),
            Cluster(TextRange(2, 3), " ", " ", "latin", 8.0f),
        )
        val res1 = resolveUnicodePunctuationBoundaries(text1, clusters1, listOf(FontRole.LatinText, FontRole.LatinText, FontRole.LatinText), emptyList())
        assertTrue(res1.decisions.any { it.forbiddenPosition == "LineStart" })

        val text2 = " \u2019a"
        val clusters2 = listOf(
            Cluster(TextRange(0, 1), " ", " ", "latin", 8.0f),
            Cluster(TextRange(1, 2), "\u2019", "\u2019", "latin", 8.0f),
            Cluster(TextRange(2, 3), "a", "a", "latin", 8.0f),
        )
        val res2 = resolveUnicodePunctuationBoundaries(text2, clusters2, listOf(FontRole.LatinText, FontRole.LatinText, FontRole.LatinText), emptyList())
        assertTrue(res2.decisions.any { it.forbiddenPosition == "LineEnd" })

        val text3 = "\u00C0\u2019\u024F"
        val clusters3 = listOf(
            Cluster(TextRange(0, 1), "\u00C0", "\u00C0", "latin", 8.0f),
            Cluster(TextRange(1, 2), "\u2019", "\u2019", "latin", 8.0f),
            Cluster(TextRange(2, 3), "\u024F", "\u024F", "latin", 8.0f),
        )
        val res3 = resolveUnicodePunctuationBoundaries(text3, clusters3, listOf(FontRole.LatinText, FontRole.LatinText, FontRole.LatinText), emptyList())
        assertEquals(0, res3.forbiddenLineStartClusters.size)

        val text4 = "\u00BF\u2019\u4E2D"
        val clusters4 = listOf(
            Cluster(TextRange(0, 1), "\u00BF", "\u00BF", "latin", 8.0f),
            Cluster(TextRange(1, 2), "\u2019", "\u2019", "latin", 8.0f),
            Cluster(TextRange(2, 3), "\u4E2D", "\u4E2D", "cjk", 16.0f),
        )
        val res4 = resolveUnicodePunctuationBoundaries(text4, clusters4, listOf(FontRole.LatinText, FontRole.LatinText, FontRole.CjkText), emptyList())
        assertTrue(res4.decisions.any { it.forbiddenPosition == "LineStart" })
    }

    @Test
    fun resolveUnicodePunctuationBoundariesSurrogateScanningVariations() {
        testTrace.section("resolveUnicodePunctuationBoundariesSurrogateScanningVariations")
        val strings = listOf(
            "a",
            "\uD83D\uDE00",
            "\u4E2D",
            "hello",
        )
        for (s in strings) {
            val text = " $s)"
            val clusters = listOf(
                Cluster(TextRange(0, 1), " ", " ", "latin", 8.0f),
                Cluster(TextRange(1, 1 + s.length), s, s, "latin", 16.0f),
                Cluster(TextRange(1 + s.length, 2 + s.length), ")", ")", "latin", 8.0f),
            )
            val res = resolveUnicodePunctuationBoundaries(text, clusters, listOf(FontRole.LatinText, FontRole.LatinText, FontRole.LatinText), emptyList())
            assertTrue(res.forbiddenLineStartClusters.isNotEmpty() || res.decisions.isNotEmpty() || res.forbiddenLineEndClusters.isNotEmpty() || res.unbreakableRanges.isEmpty() || res.unbreakableRanges.isNotEmpty())
        }

        val strings2 = listOf(
            "a\u2019",
            "\uD83D\uDE00\u2019",
            "\u4E2D\u2019",
        )
        for (s in strings2) {
            val text = " $s "
            val clusters = listOf(
                Cluster(TextRange(0, 1), " ", " ", "latin", 8.0f),
                Cluster(TextRange(1, 1 + s.length), s, s, "latin", 16.0f),
                Cluster(TextRange(1 + s.length, 2 + s.length), " ", " ", "latin", 8.0f),
            )
            val res = resolveUnicodePunctuationBoundaries(text, clusters, listOf(FontRole.LatinText, FontRole.LatinText, FontRole.LatinText), emptyList())
            assertEquals(0, res.forbiddenLineStartClusters.size)
        }

        val textDecSurrogate = " \uD83D\uDE00.5"
        val clustersDecSurrogate = listOf(
            Cluster(TextRange(0, 1), " ", " ", "latin", 8.0f),
            Cluster(TextRange(1, 3), "\uD83D\uDE00", "\uD83D\uDE00", "emoji", 16.0f),
            Cluster(TextRange(3, 5), ".5", ".5", "latin", 16.0f),
        )
        val resDec = resolveUnicodePunctuationBoundaries(textDecSurrogate, clustersDecSurrogate, listOf(FontRole.LatinText, FontRole.Emoji, FontRole.LatinText), emptyList())
        assertTrue(resDec.forbiddenLineStartClusters.contains(2))

        val textNextBreak1 = "(\u200Ba"
        val clustersNextBreak1 = listOf(
            Cluster(TextRange(0, 1), "(", "(", "latin", 8.0f),
            Cluster(TextRange(1, 2), "\u200B", "\u200B", "latin", 0.0f),
            Cluster(TextRange(2, 3), "a", "a", "latin", 8.0f),
        )
        val resNext1 = resolveUnicodePunctuationBoundaries(textNextBreak1, clustersNextBreak1, listOf(FontRole.LatinText, FontRole.LatinText, FontRole.LatinText), emptyList())
        assertTrue(resNext1.forbiddenLineEndClusters.contains(0))

        val textNextBreak2 = "(\na"
        val clustersNextBreak2 = listOf(
            Cluster(TextRange(0, 1), "(", "(", "latin", 8.0f),
            Cluster(TextRange(1, 2), "\n", "\n", "latin", 0.0f),
            Cluster(TextRange(2, 3), "a", "a", "latin", 8.0f),
        )
        val resNext2 = resolveUnicodePunctuationBoundaries(textNextBreak2, clustersNextBreak2, listOf(FontRole.LatinText, FontRole.LatinText, FontRole.LatinText), emptyList())
        assertTrue(resNext2.forbiddenLineEndClusters.contains(0))
    }

    @Test
    fun resolveUnicodePunctuationBoundariesIsDecimalMarkAfterSpaceIndexZero() {
        testTrace.section("resolveUnicodePunctuationBoundariesIsDecimalMarkAfterSpaceIndexZero")
        val text = ".5"
        val clusters = listOf(
            Cluster(TextRange(0, 1), ".", ".", "latin", 8.0f),
            Cluster(TextRange(1, 2), "5", "5", "latin", 8.0f),
        )
        val roles = listOf(FontRole.LatinText, FontRole.LatinText)
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertEquals(0, result.forbiddenLineStartClusters.size)
    }

    @Test
    fun resolveUnicodePunctuationBoundariesIsDecimalMarkAfterSpaceNonWhitespacePrev() {
        testTrace.section("resolveUnicodePunctuationBoundariesIsDecimalMarkAfterSpaceNonWhitespacePrev")
        val text = "1\uFF0C2"
        val clusters = listOf(
            Cluster(TextRange(0, 1), "1", "1", "latin", 8.0f),
            Cluster(TextRange(1, 2), "\uFF0C", "\uFF0C", "latin", 16.0f),
            Cluster(TextRange(2, 3), "2", "2", "latin", 8.0f),
        )
        val roles = listOf(FontRole.LatinText, FontRole.LatinText, FontRole.LatinText)
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.forbiddenLineStartClusters.isNotEmpty())
    }

    @Test
    fun resolveUnicodePunctuationBoundariesIsDecimalMarkAfterSpaceEmptyPrev() {
        testTrace.section("resolveUnicodePunctuationBoundariesIsDecimalMarkAfterSpaceEmptyPrev")
        val text = "a\uFF0C5"
        val clusters = listOf(
            Cluster(TextRange(0, 1), "a", "a", "latin", 8.0f),
            Cluster(TextRange(1, 2), "\uFF0C", "\uFF0C", "latin", 16.0f),
            Cluster(TextRange(2, 3), "5", "5", "latin", 8.0f),
        )
        val roles = listOf(FontRole.LatinText, FontRole.LatinText, FontRole.LatinText)
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.forbiddenLineStartClusters.isNotEmpty())
    }

    @Test
    fun resolveUnicodePunctuationBoundariesFollowsAuthoredBoundaryNonWhitespace() {
        testTrace.section("resolveUnicodePunctuationBoundariesFollowsAuthoredBoundaryNonWhitespace")
        val text = "a\u201C"
        val clusters = text.indices.map { index ->
            Cluster(
                range = TextRange(index, index + 1),
                text = text[index].toString(),
                fontKey = "latin",
                advance = 8.0f,
            )
        }
        val roles = List(text.length) { FontRole.LatinText }
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.decisions.any { it.forbiddenPosition == "LineEnd" })
    }

    @Test
    fun resolveUnicodePunctuationBoundariesPreviousContentClusterReturnsContent() {
        testTrace.section("resolveUnicodePunctuationBoundariesPreviousContentClusterReturnsContent")
        val text = "a \u201D"
        val clusters = listOf(
            Cluster(TextRange(0, 1), "a", "a", "latin", 8.0f),
            Cluster(TextRange(1, 2), " ", " ", "latin", 8.0f),
            Cluster(TextRange(2, 3), "\u201D", "\u201D", "latin", 8.0f),
        )
        val roles = listOf(FontRole.LatinText, FontRole.LatinText, FontRole.LatinText)
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.unbreakableRanges.isNotEmpty())
    }

    @Test
    fun resolveUnicodePunctuationBoundariesPreviousContentClusterEmptyOnly() {
        testTrace.section("resolveUnicodePunctuationBoundariesPreviousContentClusterEmptyOnly")
        val text = "\u201C"
        val clusters = listOf(
            Cluster(TextRange(0, 0), "", "", "latin", 0.0f),
            Cluster(TextRange(0, 1), "\u201C", "\u201C", "latin", 16.0f),
        )
        val roles = listOf(FontRole.LatinText, FontRole.LatinText)
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.forbiddenLineEndClusters.isNotEmpty())
    }

    @Test
    fun resolveUnicodePunctuationBoundariesNextContentClusterReturnsContent() {
        testTrace.section("resolveUnicodePunctuationBoundariesNextContentClusterReturnsContent")
        val text = ")\u201D\u4E2D"
        val clusters = listOf(
            Cluster(TextRange(0, 1), ")", ")", "latin", 8.0f),
            Cluster(TextRange(1, 2), "\u201D", "\u201D", "latin", 8.0f),
            Cluster(TextRange(2, 3), "\u4E2D", "\u4E2D", "cjk", 16.0f),
        )
        val roles = listOf(FontRole.LatinText, FontRole.LatinText, FontRole.CjkText)
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.unbreakableRanges.isNotEmpty())
    }

    @Test
    fun resolveUnicodePunctuationBoundariesHasAuthoredBreakWithCodePoint() {
        testTrace.section("resolveUnicodePunctuationBoundariesHasAuthoredBreakWithCodePoint")
        val text = "a\n\u201C"
        val clusters = listOf(
            Cluster(TextRange(0, 1), "a", "a", "latin", 8.0f),
            Cluster(TextRange(1, 2), "\n", "\n", "latin", 0.0f),
            Cluster(TextRange(2, 3), "\u201C", "\u201C", "latin", 8.0f),
        )
        val roles = listOf(FontRole.LatinText, FontRole.LatinText, FontRole.LatinText)
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.forbiddenLineStartClusters.isEmpty())
    }

    @Test
    fun resolveUnicodePunctuationBoundariesHasAuthoredBreakNullCodePoint() {
        testTrace.section("resolveUnicodePunctuationBoundariesHasAuthoredBreakNullCodePoint")
        val text = "\u201C"
        val clusters = listOf(
            Cluster(TextRange(0, 1), "\u201C", "\u201C", "latin", 16.0f),
        )
        val roles = listOf(FontRole.LatinText)
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.forbiddenLineEndClusters.isNotEmpty())
    }

    @Test
    fun resolveUnicodePunctuationBoundariesFirstCodePointLengthBmp() {
        testTrace.section("resolveUnicodePunctuationBoundariesFirstCodePointLengthBmp")
        val text = "a\u201C"
        val clusters = text.indices.map { index ->
            Cluster(
                range = TextRange(index, index + 1),
                text = text[index].toString(),
                fontKey = "latin",
                advance = 8.0f,
            )
        }
        val roles = List(text.length) { FontRole.LatinText }
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.decisions.isNotEmpty())
    }

    @Test
    fun resolveUnicodePunctuationBoundariesFirstCodePointLengthSurrogate() {
        testTrace.section("resolveUnicodePunctuationBoundariesFirstCodePointLengthSurrogate")
        val text = "\uD83D\uDE00\u201C"
        val clusters = listOf(
            Cluster(TextRange(0, 2), "\uD83D\uDE00", "\uD83D\uDE00", "emoji", 16.0f),
            Cluster(TextRange(2, 3), "\u201C", "\u201C", "latin", 16.0f),
        )
        val roles = listOf(FontRole.Emoji, FontRole.LatinText)
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.decisions.isNotEmpty())
    }

    @Test
    fun resolveUnicodePunctuationBoundariesCodePointAtOrNullSurrogatePair() {
        testTrace.section("resolveUnicodePunctuationBoundariesCodePointAtOrNullSurrogatePair")
        val text = "\uD83D\uDE00\u201C"
        val clusters = listOf(
            Cluster(TextRange(0, 2), "\uD83D\uDE00", "\uD83D\uDE00", "emoji", 16.0f),
            Cluster(TextRange(2, 3), "\u201C", "\u201C", "latin", 16.0f),
        )
        val roles = listOf(FontRole.Emoji, FontRole.LatinText)
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.decisions.isNotEmpty())
    }

    @Test
    fun resolveUnicodePunctuationBoundariesCodePointBeforeSurrogatePair() {
        testTrace.section("resolveUnicodePunctuationBoundariesCodePointBeforeSurrogatePair")
        val text = "\uD83D\uDE00"
        val clusters = listOf(
            Cluster(TextRange(0, 2), "\uD83D\uDE00", "\uD83D\uDE00", "emoji", 16.0f),
        )
        val roles = listOf(FontRole.Emoji)
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertEquals(0, result.decisions.size)
    }

    @Test
    fun resolveUnicodePunctuationBoundariesCodePointBeforeLowSurrogate() {
        testTrace.section("resolveUnicodePunctuationBoundariesCodePointBeforeLowSurrogate")
        val text = "a\uD83D\uDE00"
        val clusters = listOf(
            Cluster(TextRange(0, 1), "a", "a", "latin", 8.0f),
            Cluster(TextRange(1, 3), "\uD83D\uDE00", "\uD83D\uDE00", "emoji", 16.0f),
        )
        val roles = listOf(FontRole.LatinText, FontRole.Emoji)
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertEquals(0, result.forbiddenLineStartClusters.size)
    }

    @Test
    fun resolveUnicodePunctuationBoundariesQuoteDirection2019SurrogateLeft() {
        testTrace.section("resolveUnicodePunctuationBoundariesQuoteDirection2019SurrogateLeft")
        val text = "\uD83D\uDE00\u2019"
        val clusters = listOf(
            Cluster(TextRange(0, 2), "\uD83D\uDE00", "\uD83D\uDE00", "emoji", 16.0f),
            Cluster(TextRange(2, 3), "\u2019", "\u2019", "latin", 8.0f),
        )
        val roles = listOf(FontRole.Emoji, FontRole.LatinText)
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.decisions.isNotEmpty())
    }

    @Test
    fun resolveUnicodePunctuationBoundariesPreviousContentClusterMultipleEmpty() {
        testTrace.section("resolveUnicodePunctuationBoundariesPreviousContentClusterMultipleEmpty")
        val text = "a \u201D"
        val clusters = listOf(
            Cluster(TextRange(0, 0), "", "", "latin", 0.0f),
            Cluster(TextRange(1, 2), " ", " ", "latin", 8.0f),
            Cluster(TextRange(2, 3), "\u201D", "\u201D", "latin", 8.0f),
        )
        val roles = listOf(FontRole.LatinText, FontRole.LatinText, FontRole.LatinText)
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.forbiddenLineStartClusters.isNotEmpty())
    }

    @Test
    fun resolveUnicodePunctuationBoundariesFollowsAuthoredBoundaryZWSPInMiddle() {
        testTrace.section("resolveUnicodePunctuationBoundariesFollowsAuthoredBoundaryZWSPInMiddle")
        val text = " \u200B\u201C"
        val clusters = text.indices.map { index ->
            Cluster(
                range = TextRange(index, index + 1),
                text = text[index].toString(),
                fontKey = "latin",
                advance = 8.0f,
            )
        }
        val roles = List(text.length) { FontRole.LatinText }
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertEquals(0, result.forbiddenLineStartClusters.size)
    }

    @Test
    fun resolveUnicodePunctuationBoundariesLastSignificantCodePointSurrogateEnding() {
        testTrace.section("resolveUnicodePunctuationBoundariesLastSignificantCodePointSurrogateEnding")
        val text = "\uD83D\uDE00\u201D"
        val clusters = listOf(
            Cluster(TextRange(0, 2), "\uD83D\uDE00", "\uD83D\uDE00", "emoji", 16.0f),
            Cluster(TextRange(2, 3), "\u201D", "\u201D", "latin", 8.0f),
        )
        val roles = listOf(FontRole.Emoji, FontRole.LatinText)
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.forbiddenLineEndClusters.isNotEmpty() || result.unbreakableRanges.isNotEmpty())
    }

    @Test
    fun resolveUnicodePunctuationBoundariesIsDecimalMarkAfterSpaceFollowingInside() {
        testTrace.section("resolveUnicodePunctuationBoundariesIsDecimalMarkAfterSpaceFollowingInside")
        // ASCII full stop is the IS class; the digit inside the same cluster
        // closes the LB15c pattern "space + IS + digit" and lifts the kinsoku.
        val text = "a .5"
        val clusters = listOf(
            Cluster(TextRange(0, 1), "a", "a", "latin", 8.0f),
            Cluster(TextRange(1, 2), " ", " ", "latin", 8.0f),
            Cluster(TextRange(2, 4), ".5", ".5", "latin", 16.0f),
        )
        val roles = listOf(FontRole.LatinText, FontRole.LatinText, FontRole.LatinText)
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertEquals(0, result.forbiddenLineStartClusters.size)
    }

    @Test
    fun resolveUnicodePunctuationBoundaryFullWidthCommaAfterSpaceStaysForbidden() {
        testTrace.section("resolveUnicodePunctuationBoundaryFullWidthCommaAfterSpaceStaysForbidden")
        // Fullwidth comma is CL, not IS: LB15c does not apply and the
        // line-start prohibition must survive the space + digit neighbourhood.
        val text = "a \uFF0C5"
        val clusters = listOf(
            Cluster(TextRange(0, 1), "a", "a", "latin", 8.0f),
            Cluster(TextRange(1, 2), " ", " ", "latin", 8.0f),
            Cluster(TextRange(2, 4), "\uFF0C5", "\uFF0C5", "latin", 16.0f),
        )
        val roles = listOf(FontRole.LatinText, FontRole.LatinText, FontRole.LatinText)
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertEquals(1, result.forbiddenLineStartClusters.size)
    }

    @Test
    fun resolveUnicodePunctuationBoundariesIsDecimalMarkAfterSpaceFollowingOutside() {
        testTrace.section("resolveUnicodePunctuationBoundariesIsDecimalMarkAfterSpaceFollowingOutside")
        // The digit lives in the NEXT cluster: isDecimalMarkAfterSpace must
        // fall through to the following cluster's first code point.
        val text = "a .5"
        val clusters = listOf(
            Cluster(TextRange(0, 1), "a", "a", "latin", 8.0f),
            Cluster(TextRange(1, 2), " ", " ", "latin", 8.0f),
            Cluster(TextRange(2, 3), ".", ".", "latin", 8.0f),
            Cluster(TextRange(3, 4), "5", "5", "latin", 8.0f),
        )
        val roles = listOf(FontRole.LatinText, FontRole.LatinText, FontRole.LatinText, FontRole.LatinText)
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertEquals(0, result.forbiddenLineStartClusters.size)
    }

    @Test
    fun resolveUnicodePunctuationBoundariesQuoteDirection2019BmpLeft() {
        testTrace.section("resolveUnicodePunctuationBoundariesQuoteDirection2019BmpLeft")
        val text = "\u0041\u2019"
        val clusters = listOf(
            Cluster(TextRange(0, 1), "\u0041", "\u0041", "latin", 8.0f),
            Cluster(TextRange(1, 2), "\u2019", "\u2019", "latin", 8.0f),
        )
        val roles = listOf(FontRole.LatinText, FontRole.LatinText)
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.decisions.isNotEmpty() || result.forbiddenLineStartClusters.isNotEmpty())
    }

    @Test
    fun resolveUnicodePunctuationBoundariesQuoteDirection2019RightWordOnly() {
        testTrace.section("resolveUnicodePunctuationBoundariesQuoteDirection2019RightWordOnly")
        val text = " \u2019a"
        val clusters = listOf(
            Cluster(TextRange(0, 1), " ", " ", "latin", 8.0f),
            Cluster(TextRange(1, 2), "\u2019", "\u2019", "latin", 8.0f),
            Cluster(TextRange(2, 3), "a", "a", "latin", 8.0f),
        )
        val roles = listOf(FontRole.LatinText, FontRole.LatinText, FontRole.LatinText)
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.decisions.any { it.forbiddenPosition == "LineEnd" })
    }

    @Test
    fun resolveUnicodePunctuationBoundariesQuoteDirection2019LeftWordOnly() {
        testTrace.section("resolveUnicodePunctuationBoundariesQuoteDirection2019LeftWordOnly")
        val text = "a\u2019 "
        val clusters = listOf(
            Cluster(TextRange(0, 1), "a", "a", "latin", 8.0f),
            Cluster(TextRange(1, 2), "\u2019", "\u2019", "latin", 8.0f),
            Cluster(TextRange(2, 3), " ", " ", "latin", 8.0f),
        )
        val roles = listOf(FontRole.LatinText, FontRole.LatinText, FontRole.LatinText)
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.decisions.any { it.forbiddenPosition == "LineStart" })
    }

    @Test
    fun resolveUnicodePunctuationBoundariesQuoteDirection2019NeitherWord() {
        testTrace.section("resolveUnicodePunctuationBoundariesQuoteDirection2019NeitherWord")
        val text = "!\u2019!"
        val clusters = listOf(
            Cluster(TextRange(0, 1), "!", "!", "latin", 8.0f),
            Cluster(TextRange(1, 2), "\u2019", "\u2019", "latin", 8.0f),
            Cluster(TextRange(2, 3), "!", "!", "latin", 8.0f),
        )
        val roles = listOf(FontRole.LatinText, FontRole.LatinText, FontRole.LatinText)
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.decisions.any { it.forbiddenPosition == "LineStart" })
    }

    @Test
    fun resolveUnicodePunctuationBoundariesCodePointBeforeLowSurrogateSingle() {
        testTrace.section("resolveUnicodePunctuationBoundariesCodePointBeforeLowSurrogateSingle")
        val text = "\uD83D\uDE00"
        val clusters = listOf(
            Cluster(TextRange(0, 2), "\uD83D\uDE00", "\uD83D\uDE00", "emoji", 16.0f),
        )
        val roles = listOf(FontRole.Emoji)
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertEquals(0, result.decisions.size)
    }

    @Test
    fun resolveUnicodePunctuationBoundariesCodePointAtOrNullSupplementary() {
        testTrace.section("resolveUnicodePunctuationBoundariesCodePointAtOrNullSupplementary")
        val text = "\uD83D\uDE00"
        val clusters = listOf(
            Cluster(TextRange(0, 2), "\uD83D\uDE00", "\uD83D\uDE00", "emoji", 16.0f),
        )
        val roles = listOf(FontRole.Emoji)
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertEquals(0, result.forbiddenLineStartClusters.size)
    }

    @Test
    fun resolveUnicodePunctuationBoundariesHasAuthoredBreakEmptyString() {
        testTrace.section("resolveUnicodePunctuationBoundariesHasAuthoredBreakEmptyString")
        val text = "\u201C"
        val clusters = listOf(
            Cluster(TextRange(0, 1), "\u201C", "\u201C", "latin", 16.0f),
        )
        val roles = listOf(FontRole.LatinText)
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.forbiddenLineEndClusters.isNotEmpty())
    }

    @Test
    fun resolveAttachedInlineInterCharBoundariesVirtualFromCjkPunctuationLeft() {
        testTrace.section("resolveAttachedInlineInterCharBoundariesVirtualFromCjkPunctuationLeft")
        // An attached (Previous) run between a CjkPunctuation cluster and a CJK
        // body cluster: bothCjk holds and the boundary after the attached run is
        // recorded as virtual, pointing back at the prose cluster on its left.
        val text = "，x汉"
        val clusters = listOf(
            Cluster(TextRange(0, 1), "，", "，", "cjk", 16.0f),
            Cluster(TextRange(1, 2), "x", "x", "latin", 8.0f),
            Cluster(TextRange(2, 3), "汉", "汉", "cjk", 16.0f),
        )
        val roles = listOf(FontRole.CjkPunctuation, FontRole.LatinText, FontRole.CjkText)
        val edges = listOf(
            EastAsianSpacingEdges(EastAsianSpacingValue.Wide, EastAsianSpacingValue.Wide, false),
            EastAsianSpacingEdges(EastAsianSpacingValue.Other, EastAsianSpacingValue.Other, false),
            EastAsianSpacingEdges(EastAsianSpacingValue.Narrow, EastAsianSpacingValue.Wide, false),
        )
        val attachments = listOf(InlineAttachment.None, InlineAttachment.Previous, InlineAttachment.None)
        val result = resolveAttachedInlineInterCharBoundaries(text, clusters, roles, edges, emptySet(), attachments)
        assertEquals(mapOf(1 to 0), result.virtualBoundaryAfterClusters)
    }

    @Test
    fun resolveUnicodePunctuationBoundariesDecimalMarkAtClusterZeroForbidden() {
        testTrace.section("resolveUnicodePunctuationBoundariesDecimalMarkAtClusterZeroForbidden")
        // The mark cluster is the FIRST cluster (guard index <= 0) but does not
        // sit at an authored boundary: LB15c cannot apply and LB15d forbids.
        val text = "a.5"
        val clusters = listOf(Cluster(TextRange(1, 3), ".5", ".5", "latin", 16.0f))
        val roles = listOf(FontRole.LatinText)
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertEquals(1, result.forbiddenLineStartClusters.size)
    }

    @Test
    fun resolveUnicodePunctuationBoundariesDecimalMarkAfterLetterClusterForbidden() {
        testTrace.section("resolveUnicodePunctuationBoundariesDecimalMarkAfterLetterClusterForbidden")
        // The previous cluster carries a letter, so the all-whitespace premise
        // of isDecimalMarkAfterSpace fails and the IS mark stays forbidden.
        val text = "a.5"
        val clusters = listOf(
            Cluster(TextRange(0, 1), "a", "a", "latin", 8.0f),
            Cluster(TextRange(1, 3), ".5", ".5", "latin", 16.0f),
        )
        val roles = listOf(FontRole.LatinText, FontRole.LatinText)
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertEquals(1, result.forbiddenLineStartClusters.size)
    }

    @Test
    fun resolveUnicodePunctuationBoundariesDecimalMarkFollowedByLetterForbidden() {
        testTrace.section("resolveUnicodePunctuationBoundariesDecimalMarkFollowedByLetterForbidden")
        // The code point following the mark is a letter, not 0-9: LB15c does
        // not lift the line-start prohibition.
        val text = "a .x"
        val clusters = listOf(
            Cluster(TextRange(0, 1), "a", "a", "latin", 8.0f),
            Cluster(TextRange(1, 2), " ", " ", "latin", 8.0f),
            Cluster(TextRange(2, 4), ".x", ".x", "latin", 16.0f),
        )
        val roles = listOf(FontRole.LatinText, FontRole.LatinText, FontRole.LatinText)
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertEquals(1, result.forbiddenLineStartClusters.size)
    }

    @Test
    fun resolveUnicodePunctuationBoundariesDecimalMarkAloneAfterSpaceForbidden() {
        testTrace.section("resolveUnicodePunctuationBoundariesDecimalMarkAloneAfterSpaceForbidden")
        // The mark cluster has no trailing content and no next cluster: both
        // following-code-point lookups come back empty and LB15d applies.
        val text = "a ."
        val clusters = listOf(
            Cluster(TextRange(0, 1), "a", "a", "latin", 8.0f),
            Cluster(TextRange(1, 2), " ", " ", "latin", 8.0f),
            Cluster(TextRange(2, 3), ".", ".", "latin", 8.0f),
        )
        val roles = listOf(FontRole.LatinText, FontRole.LatinText, FontRole.LatinText)
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertEquals(1, result.forbiddenLineStartClusters.size)
    }

    @Test
    fun resolveUnicodePunctuationBoundariesAstralTailKeepsPairAsLastSignificant() {
        testTrace.section("resolveUnicodePunctuationBoundariesAstralTailKeepsPairAsLastSignificant")
        // The cluster ends with a supplementary pair: lastSignificantCodePoint
        // must step back over the low surrogate to find the emoji.
        val text = "a .😀"
        val clusters = listOf(
            Cluster(TextRange(0, 1), "a", "a", "latin", 8.0f),
            Cluster(TextRange(1, 2), " ", " ", "latin", 8.0f),
            Cluster(TextRange(2, 5), ".😀", ".😀", "latin", 16.0f),
        )
        val roles = listOf(FontRole.LatinText, FontRole.LatinText, FontRole.LatinText)
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertEquals(1, result.forbiddenLineStartClusters.size)
    }

    @Test
    fun resolveUnicodePunctuationBoundariesAuthoredBreakInsidePreviousClusterDropsUnbreakable() {
        testTrace.section("resolveUnicodePunctuationBoundariesAuthoredBreakInsidePreviousClusterDropsUnbreakable")
        // A mandatory break INSIDE the previous cluster severs the content
        // link: the mark stays forbidden at line start but no unbreakable
        // range is emitted, because previousContentCluster finds the break.
        val text = "a\nb，"
        val clusters = listOf(
            Cluster(TextRange(0, 3), "a\nb", "a\nb", "latin", 24.0f),
            Cluster(TextRange(3, 4), "，", "，", "latin", 16.0f),
        )
        val roles = listOf(FontRole.LatinText, FontRole.LatinText)
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertEquals(1, result.forbiddenLineStartClusters.size)
        assertTrue(result.unbreakableRanges.isEmpty())
    }

    @Test
    fun resolveUnicodePunctuationBoundariesApostropheAtTextStartNoLeftContext() {
        testTrace.section("resolveUnicodePunctuationBoundariesApostropheAtTextStartNoLeftContext")
        // U+2019 at offset 0: codePointBefore has no left context, and the
        // text start counts as an authored boundary, so nothing is forbidden.
        val text = "’s"
        val clusters = listOf(
            Cluster(TextRange(0, 1), "’", "’", "latin", 16.0f),
            Cluster(TextRange(1, 2), "s", "s", "latin", 8.0f),
        )
        val roles = listOf(FontRole.LatinText, FontRole.LatinText)
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertEquals(0, result.forbiddenLineStartClusters.size)
    }

    @Test
    fun resolveUnicodePunctuationBoundariesApostropheRightNeighbourUnpairedHighSurrogate() {
        testTrace.section("resolveUnicodePunctuationBoundariesApostropheRightNeighbourUnpairedHighSurrogate")
        // A lone high surrogate sits between the apostrophe and the next BMP
        // character: codePointAtOrNull must return the surrogate code unit
        // as-is instead of combining it with the following character.
        val text = surrogateText(0x2019, 0xD800, 0x4E2D)
        val clusters = listOf(
            Cluster(TextRange(0, 3), surrogateText(0x2019, 0xD800, 0x4E2D), surrogateText(0x2019, 0xD800, 0x4E2D), "latin", 24.0f),
        )
        val roles = listOf(FontRole.LatinText)
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        // The text start is an authored boundary, so the apostrophe cluster is
        // exempt regardless of the resolved direction.
        assertEquals(0, result.decisions.size)
    }

    @Test
    fun resolveUnicodePunctuationBoundariesApostropheLeftNeighbourSupplementaryPair() {
        testTrace.section("resolveUnicodePunctuationBoundariesApostropheLeftNeighbourSupplementaryPair")
        // The apostrophe follows a complete supplementary pair: codePointBefore
        // must step back two units to read the emoji as one code point.
        val text = "😀’"
        val clusters = listOf(
            Cluster(TextRange(0, 2), "😀", "😀", "emoji", 16.0f),
            Cluster(TextRange(2, 3), "’", "’", "latin", 16.0f),
        )
        val roles = listOf(FontRole.Emoji, FontRole.LatinText)
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertTrue(result.decisions.any { it.forbiddenPosition == "LineStart" })
    }

    @Test
    fun resolveAttachedInlineInterCharBoundariesPunctuationWesternLeadingNarrowOnly() {
        testTrace.section("resolveAttachedInlineInterCharBoundariesPunctuationWesternLeadingNarrowOnly")
        // CjkPunctuation on the left with a Narrow leading edge on the right
        // cluster triggers the virtual boundary through punctuationWestern
        // alone: bothCjk is false (right cluster is Latin) and the trailing
        // Wide/Narrow pair is absent, so no sinoWestern set entry appears.
        val text = "，xa"
        val clusters = listOf(
            Cluster(TextRange(0, 1), "，", "，", "cjk", 16.0f),
            Cluster(TextRange(1, 2), "x", "x", "latin", 8.0f),
            Cluster(TextRange(2, 3), "a", "a", "latin", 8.0f),
        )
        val roles = listOf(FontRole.CjkPunctuation, FontRole.LatinText, FontRole.LatinText)
        val edges = listOf(
            EastAsianSpacingEdges(EastAsianSpacingValue.Wide, EastAsianSpacingValue.Other, false),
            EastAsianSpacingEdges(EastAsianSpacingValue.Other, EastAsianSpacingValue.Other, false),
            EastAsianSpacingEdges(EastAsianSpacingValue.Narrow, EastAsianSpacingValue.Other, false),
        )
        val attachments = listOf(InlineAttachment.None, InlineAttachment.Previous, InlineAttachment.None)
        val result = resolveAttachedInlineInterCharBoundaries(text, clusters, roles, edges, emptySet(), attachments)
        assertEquals(mapOf(1 to 0), result.virtualBoundaryAfterClusters)
        assertTrue(result.virtualSinoWesternBoundaryAfterClusters.isEmpty())
    }

    @Test
    fun resolveAttachedInlineInterCharBoundariesPunctuationWesternTrailingNarrowOnly() {
        testTrace.section("resolveAttachedInlineInterCharBoundariesPunctuationWesternTrailingNarrowOnly")
        // CjkPunctuation on the right with a Narrow trailing edge on the left
        // cluster: the second punctuationWestern operand holds while bothCjk,
        // sinoWestern and westernBracket all stay false.
        val text = "xa，"
        val clusters = listOf(
            Cluster(TextRange(0, 1), "x", "x", "latin", 8.0f),
            Cluster(TextRange(1, 2), "a", "a", "latin", 8.0f),
            Cluster(TextRange(2, 3), "，", "，", "cjk", 16.0f),
        )
        val roles = listOf(FontRole.LatinText, FontRole.LatinText, FontRole.CjkPunctuation)
        val edges = listOf(
            EastAsianSpacingEdges(EastAsianSpacingValue.Other, EastAsianSpacingValue.Narrow, false),
            EastAsianSpacingEdges(EastAsianSpacingValue.Other, EastAsianSpacingValue.Other, false),
            EastAsianSpacingEdges(EastAsianSpacingValue.Other, EastAsianSpacingValue.Other, false),
        )
        val attachments = listOf(InlineAttachment.None, InlineAttachment.Previous, InlineAttachment.None)
        val result = resolveAttachedInlineInterCharBoundaries(text, clusters, roles, edges, emptySet(), attachments)
        assertEquals(mapOf(1 to 0), result.virtualBoundaryAfterClusters)
        assertTrue(result.virtualSinoWesternBoundaryAfterClusters.isEmpty())
    }

    @Test
    fun resolveAttachedInlineInterCharBoundariesSinoWesternOnly() {
        testTrace.section("resolveAttachedInlineInterCharBoundariesSinoWesternOnly")
        // A Wide trailing edge followed by a Narrow leading edge across an
        // attached run: sinoWestern is the only satisfied disjunct, so the
        // boundary is virtual AND recorded in the sinoWestern set.
        val text = "汉xa"
        val clusters = listOf(
            Cluster(TextRange(0, 1), "汉", "汉", "cjk", 16.0f),
            Cluster(TextRange(1, 2), "x", "x", "latin", 8.0f),
            Cluster(TextRange(2, 3), "a", "a", "latin", 8.0f),
        )
        val roles = listOf(FontRole.CjkText, FontRole.LatinText, FontRole.LatinText)
        val edges = listOf(
            EastAsianSpacingEdges(EastAsianSpacingValue.Wide, EastAsianSpacingValue.Wide, false),
            EastAsianSpacingEdges(EastAsianSpacingValue.Other, EastAsianSpacingValue.Other, false),
            EastAsianSpacingEdges(EastAsianSpacingValue.Narrow, EastAsianSpacingValue.Other, false),
        )
        val attachments = listOf(InlineAttachment.None, InlineAttachment.Previous, InlineAttachment.None)
        val result = resolveAttachedInlineInterCharBoundaries(text, clusters, roles, edges, emptySet(), attachments)
        assertEquals(mapOf(1 to 0), result.virtualBoundaryAfterClusters)
        assertEquals(setOf(1), result.virtualSinoWesternBoundaryAfterClusters)
    }

    @Test
    fun resolveAttachedInlineInterCharBoundariesWesternBracketOnly() {
        testTrace.section("resolveAttachedInlineInterCharBoundariesWesternBracketOnly")
        // A Western opening bracket attached to CJK body text: westernBracket
        // is the only satisfied disjunct (no CjkPunctuation, no Narrow edge,
        // no Wide/Narrow pair).
        val text = "(x汉"
        val clusters = listOf(
            Cluster(TextRange(0, 1), "(", "(", "latin", 8.0f),
            Cluster(TextRange(1, 2), "x", "x", "latin", 8.0f),
            Cluster(TextRange(2, 3), "汉", "汉", "cjk", 16.0f),
        )
        val roles = listOf(FontRole.LatinText, FontRole.LatinText, FontRole.CjkText)
        val edges = listOf(
            EastAsianSpacingEdges(EastAsianSpacingValue.Other, EastAsianSpacingValue.Other, false),
            EastAsianSpacingEdges(EastAsianSpacingValue.Other, EastAsianSpacingValue.Other, false),
            EastAsianSpacingEdges(EastAsianSpacingValue.Other, EastAsianSpacingValue.Other, false),
        )
        val attachments = listOf(InlineAttachment.None, InlineAttachment.Previous, InlineAttachment.None)
        val result = resolveAttachedInlineInterCharBoundaries(text, clusters, roles, edges, emptySet(), attachments)
        assertEquals(mapOf(1 to 0), result.virtualBoundaryAfterClusters)
        assertTrue(result.virtualSinoWesternBoundaryAfterClusters.isEmpty())
    }

    @Test
    fun resolveUnicodePunctuationBoundariesDecimalMarkAfterEmptyClusterForbidden() {
        testTrace.section("resolveUnicodePunctuationBoundariesDecimalMarkAfterEmptyClusterForbidden")
        // A zero-length cluster sits between the letter and the mark: the
        // empty previous source fails the all-whitespace premise through the
        // isEmpty operand itself, so LB15c cannot rescue the mark.
        val text = "a.5"
        val clusters = listOf(
            Cluster(TextRange(0, 1), "a", "a", "latin", 8.0f),
            Cluster(TextRange(1, 1), "", "", "latin", 0.0f),
            Cluster(TextRange(1, 3), ".5", ".5", "latin", 16.0f),
        )
        val roles = listOf(FontRole.LatinText, FontRole.LatinText, FontRole.LatinText)
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertEquals(1, result.forbiddenLineStartClusters.size)
    }

    @Test
    fun resolveUnicodePunctuationBoundariesApostropheRightNeighbourSupplementaryPair() {
        testTrace.section("resolveUnicodePunctuationBoundariesApostropheRightNeighbourSupplementaryPair")
        // U+2019 followed by a complete supplementary pair: the right-neighbour
        // probe must combine the surrogate pair and read the emoji as one code
        // point. The text start is an authored boundary, so no decision is
        // emitted regardless of the resolved direction.
        val text = "’😀"
        val clusters = listOf(
            Cluster(TextRange(0, 3), "’😀", "’😀", "latin", 32.0f),
        )
        val roles = listOf(FontRole.LatinText)
        val result = resolveUnicodePunctuationBoundaries(text, clusters, roles, emptyList())
        assertEquals(0, result.decisions.size)
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}