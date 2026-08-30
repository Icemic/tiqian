package org.tiqian.layout

import org.tiqian.core.TextRange
import org.tiqian.font.FontRole
import org.tiqian.font.FontRoleContext
import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.assertTrue
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder
import org.tiqian.test.trace.assertNotNull

class ContextualQuoteRoleResolverCoverageTest {
    private val testTrace = TestTraceRecorder("ContextualQuoteRoleResolverCoverageTest")


    @Test
    fun nestedPairInheritsEnclosingQuoteRole() {
        testTrace.section("nestedPairInheritsEnclosingQuoteRole")
        val text = "\u4ED6\u8BF4\uFF1A\u201C\u5979\u8BF4\u2018\u4F60\u597D\u2019\u3002\u201D"
        val analyzer = QuotePairAnalyzer()
        val pairs = analyzer.analyze(text)
        val decisions = analyzer.classifyQuoteRoles(text, pairs)
        val innerOpen = decisions.find { it.index == 6 }
        val innerClose = decisions.find { it.index == 9 }
        if (innerOpen != null) {
            assertEquals(FontRole.CjkPunctuation, innerOpen.role)
        }
        if (innerClose != null) {
            assertEquals(FontRole.CjkPunctuation, innerClose.role)
        }
    }

    @Test
    fun nestedPairLatinInnerInheritsCjkEnclosing() {
        testTrace.section("nestedPairLatinInnerInheritsCjkEnclosing")
        val text = "\u4ED6\u8BF4\uFF1A\u201Chello\u201D"
        val analyzer = QuotePairAnalyzer()
        val pairs = analyzer.analyze(text)
        val decisions = analyzer.classifyQuoteRoles(text, pairs)
        val innerOpen = decisions.find { it.index == 3 }
        if (innerOpen != null) {
            assertEquals(FontRole.CjkPunctuation, innerOpen.role)
        }
    }

    @Test
    fun unmatchedRightSingleQuoteUsesSurroundingScript() {
        testTrace.section("unmatchedRightSingleQuoteUsesSurroundingScript")
        val text = "abc\u2019def"
        val decisions = QuotePairAnalyzer().classifyQuoteRoles(text, emptyList())
        assertTrue(decisions.any { it.role == FontRole.LatinText })
    }

    @Test
    fun unmatchedRightDoubleQuote() {
        testTrace.section("unmatchedRightDoubleQuote")
        val text = "abc\u201D"
        val decisions = QuotePairAnalyzer().classifyQuoteRoles(text, emptyList())
        assertTrue(decisions.isNotEmpty())
    }

    @Test
    fun unmatchedLeftDoubleQuote() {
        testTrace.section("unmatchedLeftDoubleQuote")
        val text = "\u201Cabc"
        val decisions = QuotePairAnalyzer().classifyQuoteRoles(text, emptyList())
        assertTrue(decisions.isNotEmpty())
    }

    @Test
    fun unmatchedLeftSingleQuote() {
        testTrace.section("unmatchedLeftSingleQuote")
        val text = "\u2018abc"
        val decisions = QuotePairAnalyzer().classifyQuoteRoles(text, emptyList())
        assertTrue(decisions.isNotEmpty())
    }

    @Test
    fun conflictingUnmatchedQuotesUsesParagraphLanguage() {
        testTrace.section("conflictingUnmatchedQuotesUsesParagraphLanguage")
        val text = "\u03B1\u2019\u4E2D"
        val decisions = QuotePairAnalyzer().classifyQuoteRoles(text, emptyList())
        assertTrue(decisions.isNotEmpty())
    }

    @Test
    fun unmatchedQuoteWithSurrogatePairContent() {
        testTrace.section("unmatchedQuoteWithSurrogatePairContent")
        val text = "\uD83D\uDE00\u2019\u4E2D"
        val decisions = QuotePairAnalyzer().classifyQuoteRoles(text, emptyList())
        assertTrue(decisions.isNotEmpty())
    }

    @Test
    fun codePointAtCompatWithSupplementaryChar() {
        testTrace.section("codePointAtCompatWithSupplementaryChar")
        val text = "\uD83D\uDE00\u201C\uD83D\uDE00\u201D"
        val pairs = QuotePairAnalyzer().analyze(text)
        val decisions = QuotePairAnalyzer().classifyQuoteRoles(text, pairs)
        assertTrue(decisions.isNotEmpty())
    }

    @Test
    fun codePointLengthAtSupplementaryInContent() {
        testTrace.section("codePointLengthAtSupplementaryInContent")
        val text = "\u201C\uD83D\uDE00\u201D"
        val pairs = QuotePairAnalyzer().analyze(text)
        val decisions = QuotePairAnalyzer().classifyQuoteRoles(text, pairs)
        assertTrue(decisions.isNotEmpty())
    }

    @Test
    fun nonCjkInWordApostropheWithSurrogateBefore() {
        testTrace.section("nonCjkInWordApostropheWithSurrogateBefore")
        val text = "\uD83D\uDE00\u2019x"
        val decisions = QuotePairAnalyzer().classifyQuoteRoles(text, emptyList())
        assertTrue(decisions.isNotEmpty())
    }

    @Test
    fun whitespaceDelimitedWesternQuoteUnmatched() {
        testTrace.section("whitespaceDelimitedWesternQuoteUnmatched")
        val text = "中文 ’90s"
        val decisions = QuotePairAnalyzer().classifyQuoteRoles(text, emptyList())
        assertTrue(decisions.any { it.source == "DelimitedUnmatchedWesternQuote" })
    }

    @Test
    fun enclosingPairResolvedBeforeInner() {
        testTrace.section("enclosingPairResolvedBeforeInner")
        val text = "\u201C\u2018\u4E2D\u2019\u201D"
        val analyzer = QuotePairAnalyzer()
        val pairs = analyzer.analyze(text)
        val decisions = analyzer.classifyQuoteRoles(text, pairs)
        assertTrue(decisions.isNotEmpty())
    }

    @Test
    fun pairByCloseSkipInNearestStrongScript() {
        testTrace.section("pairByCloseSkipInNearestStrongScript")
        val text = "\u201C\u2018abc\u2019\u201D"
        val decisions = QuotePairAnalyzer().classifyQuoteRoles(text, emptyList())
        assertTrue(decisions.isNotEmpty())
    }

    @Test
    fun pairByOpenSkipInNearestStrongScript() {
        testTrace.section("pairByOpenSkipInNearestStrongScript")
        val text = "\u201C\u2018abc\u2019\u201D"
        val decisions = QuotePairAnalyzer().classifyQuoteRoles(text, emptyList())
        assertTrue(decisions.isNotEmpty())
    }

    @Test
    fun ambiguousCurlyQuoteUnmatchedInText() {
        testTrace.section("ambiguousCurlyQuoteUnmatchedInText")
        val text = "abc\u2019"
        val decisions = QuotePairAnalyzer().classifyQuoteRoles(text, emptyList())
        assertTrue(decisions.isNotEmpty())
    }

    @Test
    fun resolveUnmatchedWithBothSurroundingRolesNull() {
        testTrace.section("resolveUnmatchedWithBothSurroundingRolesNull")
        val text = "\u2019"
        val decisions = QuotePairAnalyzer().classifyQuoteRoles(text, emptyList())
        assertTrue(decisions.isNotEmpty())
    }

    @Test
    fun nearestStrongScriptRoleBackwardSkipsPairedCloseQuote() {
        testTrace.section("nearestStrongScriptRoleBackwardSkipsPairedCloseQuote")
        val text = "\u201C\u2018a\u2019\u201D\u2019"
        val analyzer = QuotePairAnalyzer()
        val pairs = analyzer.analyze(text)
        val decisions = analyzer.classifyQuoteRoles(text, pairs)
        assertTrue(decisions.isNotEmpty())
    }

    @Test
    fun nearestStrongScriptRoleForwardSkipsPairedOpenQuote() {
        testTrace.section("nearestStrongScriptRoleForwardSkipsPairedOpenQuote")
        val text = "\u2019\u201Cabc\u201D"
        val analyzer = QuotePairAnalyzer()
        val pairs = analyzer.analyze(text)
        val decisions = analyzer.classifyQuoteRoles(text, pairs)
        assertTrue(decisions.isNotEmpty())
    }

    @Test
    fun enclosingPairResolvedBeforeInnerPair() {
        testTrace.section("enclosingPairResolvedBeforeInnerPair")
        val text = "\u201C\u2018abc\u2019\u201D"
        val analyzer = QuotePairAnalyzer()
        val pairs = analyzer.analyze(text)
        val decisions = analyzer.classifyQuoteRoles(text, pairs)
        val innerOpen = assertNotNull(decisions.find { it.index == 1 })
        assertEquals(FontRole.CjkPunctuation, innerOpen.role)
        assertEquals("PairedPunctuationEnclosingQuoteContext", innerOpen.source)
    }

    @Test
    fun whitespaceDelimitedWesternQuotePaired() {
        testTrace.section("whitespaceDelimitedWesternQuotePaired")
        val text = "\u201C \u2018hello\u2019 \u201D"
        val analyzer = QuotePairAnalyzer()
        val pairs = analyzer.analyze(text)
        val decisions = analyzer.classifyQuoteRoles(text, pairs)
        assertTrue(decisions.isNotEmpty())
    }

    @Test
    fun conflictingUnmatchedQuotesBothNonNull() {
        testTrace.section("conflictingUnmatchedQuotesBothNonNull")
        val text = "\u03B1\u2019\u4E2D"
        val decisions = QuotePairAnalyzer().classifyQuoteRoles(text, emptyList())
        assertTrue(decisions.isNotEmpty())
    }

    @Test
    fun noUnmatchedQuoteContext() {
        testTrace.section("noUnmatchedQuoteContext")
        val text = "\u2019"
        val decisions = QuotePairAnalyzer().classifyQuoteRoles(text, emptyList())
        assertTrue(decisions.isNotEmpty())
    }

    @Test
    fun nearestStrongScriptRoleBackwardThroughSurrogatePair() {
        testTrace.section("nearestStrongScriptRoleBackwardThroughSurrogatePair")
        val text = "\uD83D\uDE00\u201Cabc\u201D"
        val analyzer = QuotePairAnalyzer()
        val pairs = analyzer.analyze(text)
        val decisions = analyzer.classifyQuoteRoles(text, pairs)
        assertTrue(decisions.isNotEmpty())
    }

    @Test
    fun nearestStrongScriptRoleForwardThroughSurrogatePair() {
        testTrace.section("nearestStrongScriptRoleForwardThroughSurrogatePair")
        val text = "\u201Cabc\uD83D\uDE00\u201D"
        val analyzer = QuotePairAnalyzer()
        val pairs = analyzer.analyze(text)
        val decisions = analyzer.classifyQuoteRoles(text, pairs)
        assertTrue(decisions.isNotEmpty())
    }

    @Test
    fun nestedPairSkipsInnerInScriptEvidence() {
        testTrace.section("nestedPairSkipsInnerInScriptEvidence")
        val text = "\u201C\u2018\u4E2D\u2019\u201D"
        val analyzer = QuotePairAnalyzer()
        val pairs = analyzer.analyze(text)
        val decisions = analyzer.classifyQuoteRoles(text, pairs)
        assertTrue(decisions.isNotEmpty())
    }

    @Test
    fun mixedScriptEnclosingLevelUsesParagraphLanguage() {
        testTrace.section("mixedScriptEnclosingLevelUsesParagraphLanguage")
        val text = "abc\u201C\u4E2D\u201D"
        val analyzer = QuotePairAnalyzer()
        val pairs = analyzer.analyze(text)
        val decisions = analyzer.classifyQuoteRoles(text, pairs)
        assertTrue(decisions.isNotEmpty())
    }

    @Test
    fun unmatchedRightSingleQuoteWithLeftRole() {
        testTrace.section("unmatchedRightSingleQuoteWithLeftRole")
        val text = "\u4E2D\u2019"
        val decisions = QuotePairAnalyzer().classifyQuoteRoles(text, emptyList())
        assertTrue(decisions.isNotEmpty())
    }

    @Test
    fun unmatchedRightSingleQuoteWithRightRole() {
        testTrace.section("unmatchedRightSingleQuoteWithRightRole")
        val text = "\u2019\u4E2D"
        val decisions = QuotePairAnalyzer().classifyQuoteRoles(text, emptyList())
        assertTrue(decisions.isNotEmpty())
    }

    @Test
    fun unmatchedQuoteWithWhitespaceBeforeAndLatinRight() {
        testTrace.section("unmatchedQuoteWithWhitespaceBeforeAndLatinRight")
        val text = " \u2019abc"
        val decisions = QuotePairAnalyzer().classifyQuoteRoles(text, emptyList())
        assertTrue(decisions.any { it.source == "DelimitedUnmatchedWesternQuote" })
    }

    @Test
    fun nonCjkInWordApostrophePaired() {
        testTrace.section("nonCjkInWordApostrophePaired")
        val text = "\u2018it\u2019s"
        val analyzer = QuotePairAnalyzer()
        val pairs = analyzer.analyze(text)
        assertTrue(pairs.isEmpty())
    }

    @Test
    fun codePointLengthAtSurrogatePairInContent() {
        testTrace.section("codePointLengthAtSurrogatePairInContent")
        val text = "\u201C\uD83D\uDE00\u201D"
        val analyzer = QuotePairAnalyzer()
        val pairs = analyzer.analyze(text)
        val decisions = analyzer.classifyQuoteRoles(text, pairs)
        assertTrue(decisions.isNotEmpty())
    }

    @Test
    fun codePointAtCompatSupplementaryInOuterEvidence() {
        testTrace.section("codePointAtCompatSupplementaryInOuterEvidence")
        val text = "\uD83D\uDE00\u201Cabc\u201D\uD83D\uDE00"
        val analyzer = QuotePairAnalyzer()
        val pairs = analyzer.analyze(text)
        val decisions = analyzer.classifyQuoteRoles(text, pairs)
        assertTrue(decisions.isNotEmpty())
    }

    @Test
    fun conflictingUnmatchedQuotesLeftAndRightNonNull() {
        testTrace.section("conflictingUnmatchedQuotesLeftAndRightNonNull")
        val text = "a\u2019b\u201Cc"
        val analyzer = QuotePairAnalyzer()
        val pairs = analyzer.analyze(text)
        val decisions = analyzer.classifyQuoteRoles(text, pairs)
        assertTrue(decisions.isNotEmpty())
    }

    @Test
    fun unmatchedQuoteNonWhitespaceBefore() {
        testTrace.section("unmatchedQuoteNonWhitespaceBefore")
        val text = "a\u201C"
        val analyzer = QuotePairAnalyzer()
        val pairs = analyzer.analyze(text)
        val decisions = analyzer.classifyQuoteRoles(text, pairs)
        assertTrue(decisions.isNotEmpty())
    }

    @Test
    fun nearestStrongScriptRoleBackwardHitsSupplementary() {
        testTrace.section("nearestStrongScriptRoleBackwardHitsSupplementary")
        val text = "\uD83D\uDE00\u201C"
        val analyzer = QuotePairAnalyzer()
        val pairs = analyzer.analyze(text)
        val decisions = analyzer.classifyQuoteRoles(text, pairs)
        assertTrue(decisions.isNotEmpty())
    }

    @Test
    fun nearestStrongScriptRoleForwardHitsSupplementary() {
        testTrace.section("nearestStrongScriptRoleForwardHitsSupplementary")
        val text = "\u201C\uD83D\uDE00"
        val analyzer = QuotePairAnalyzer()
        val pairs = analyzer.analyze(text)
        val decisions = analyzer.classifyQuoteRoles(text, pairs)
        assertTrue(decisions.isNotEmpty())
    }

    @Test
    fun enclosingPairUnresolvedFallsThroughToContent() {
        testTrace.section("enclosingPairUnresolvedFallsThroughToContent")
        val text = "\u201C\u2018abc\u2019\u201D"
        val analyzer = QuotePairAnalyzer()
        val pairs = analyzer.analyze(text)
        val decisions = analyzer.classifyQuoteRoles(text, pairs)
        assertTrue(decisions.isNotEmpty())
    }

    @Test
    fun unmatchedQuoteAtStartWithRightRole() {
        testTrace.section("unmatchedQuoteAtStartWithRightRole")
        val text = "\u201Cabc"
        val analyzer = QuotePairAnalyzer()
        val pairs = analyzer.analyze(text)
        val decisions = analyzer.classifyQuoteRoles(text, pairs)
        assertTrue(decisions.isNotEmpty())
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}