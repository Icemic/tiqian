package org.tiqian.layout

import org.tiqian.font.FontRole
import org.tiqian.font.FontRoleContext
import kotlin.test.Test
import org.tiqian.test.trace.assertFailsWith
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.assertTrue
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

// A lone surrogate written inside a string literal is replaced with '?' when
// the JS test bundle re-serializes its sources, so inputs that carry one are
// built from char codes at runtime to keep the code unit intact everywhere.
private fun surrogateText(vararg codes: Int): String =
    CharArray(codes.size) { codes[it].toChar() }.concatToString()

/**
 * Resolver paths the western fixtures cannot reach: a nested pair whose
 * enclosing level holds only neutral characters inherits the resolved outer
 * quotation instead of the enclosing-level script; an unmatched quote after a
 * space with a non-Latin right role falls through the delimited rule; and the
 * leftward surrogate scan-start arms, which the script classifier rejects
 * after the walker has run.
 */
class ContextualQuoteRoleResolverNestedAndSurrogateTest {
    private val testTrace = TestTraceRecorder("ContextualQuoteRoleResolverNestedAndSurrogateTest")


    private fun decisions(text: String): List<QuoteRoleDecision> =
        ContextualQuoteRoleResolver(
            text = text,
            pairs = QuotePairAnalyzer().analyze(text),
            context = FontRoleContext(),
        ).resolve()

    @Test
    fun nestedPairInsideNeutralEnclosingInheritsTheOuterQuotation() {
        testTrace.section("nestedPairInsideNeutralEnclosingInheritsTheOuterQuotation")
        // The inner pair's enclosing level (inside the outer quotes, outside
        // the inner ones) holds only em dashes, so no enclosing-level script
        // or enclosing quotation script rule fires: the inner pair reads its
        // role from the already-resolved outer pair.
        val text = "“—‘文’—”"
        val decisions = decisions(text)
        val outer = decisions.first { it.index == 0 }
        val inner = decisions.first { it.index == 2 }
        assertEquals("PairedPunctuationEnclosingQuoteContext", inner.source)
        assertEquals("quote-pair-inherits-enclosing-quotation", inner.reason)
        assertEquals(outer.role, inner.role)
        assertTrue(decisions.none { it.source == "DelimitedWesternQuotationRun" })
    }

    @Test
    fun spaceBeforeUnmatchedQuoteWithCjkRightSkipsTheDelimitedRule() {
        testTrace.section("spaceBeforeUnmatchedQuoteWithCjkRightSkipsTheDelimitedRule")
        // The unmatched apostrophe has a space before it, but its right role
        // is CJK, so the whitespace-delimited western rule fails on its
        // second conjunct and the surrounding-script rule resolves to the
        // CJK right role.
        val text = " ’中"
        val decision = decisions(text).first { it.index == 1 }
        assertEquals("UnmatchedQuoteSurroundingScriptContext", decision.source)
        assertEquals(FontRole.CjkPunctuation, decision.role)
    }

    @Test
    fun leftwardScanFromALowSurrogateWalksEveryBacktrackArm() {
        testTrace.section("leftwardScanFromALowSurrogateWalksEveryBacktrackArm")
        // The unmatched quote sits right after a low surrogate: the scan
        // starts on the low half. All three backtrack shapes reject the lone
        // half in the script classifier after the walker arms run.
        assertFailsWith<IllegalArgumentException> { decisions(surrogateText(0xDC00, 0x201C)) }
        assertFailsWith<IllegalArgumentException> { decisions(surrogateText('a'.code, 0xDC00, 0x201C)) }
        assertFailsWith<IllegalArgumentException> { decisions(surrogateText('x'.code, 0xDC00, 0xDC00, 0x201C)) }
    }

    @Test
    fun tabBeforeAWhollyWesternPairDelimitsLikeASpace() {
        testTrace.section("tabBeforeAWhollyWesternPairDelimitsLikeASpace")
        // The tab arm of the space-or-tab check: a tab-delimited western
        // quotation takes the same delimited run as a space-delimited one.
        val decision = decisions("\t“a”").first { it.index == 1 }
        assertEquals("DelimitedWesternQuotationRun", decision.source)
    }

    @Test
    fun spaceBeforeAPairWithNonWesternContentSkipsTheDelimitedRule() {
        testTrace.section("spaceBeforeAPairWithNonWesternContentSkipsTheDelimitedRule")
        // Space-delimited requires wholly western content: with CJK content
        // the second conjunct fails and the content script decides.
        val decision = decisions(" “中”").first { it.index == 1 }
        assertEquals("PairedPunctuationContentScriptContext", decision.source)
        assertEquals(FontRole.CjkPunctuation, decision.role)
    }

    @Test
    fun spaceBeforeAMixedContentPairReportsMixedContent() {
        testTrace.section("spaceBeforeAMixedContentPairReportsMixedContent")
        // Western plus CJK inside the quotes: the delimited rule fails on
        // its third conjunct, the unambiguous-role check finds both scripts
        // and falls through, and the pair reports mixed quoted content.
        val decision = decisions(" “a中”").first { it.index == 1 }
        assertEquals("ParagraphLanguageQuoteContext", decision.source)
        assertTrue(decision.reason.contains("mixed-quoted-content"), decision.reason)
    }

    @Test
    fun mixedEnclosingLevelFallsBackToParagraphLanguage() {
        testTrace.section("mixedEnclosingLevelFallsBackToParagraphLanguage")
        // Latin on one side of the pair and CJK on the other: the enclosing
        // level is mixed, so the pair resolves by paragraph language.
        val decision = decisions("a“中”文").first { it.index == 1 }
        assertEquals("ParagraphLanguageQuoteContext", decision.source)
        assertTrue(decision.reason.contains("mixed-enclosing-level-script"), decision.reason)
    }

    @Test
    fun nonChineseLocaleResolvesNeutralContextToLatinText() {
        testTrace.section("nonChineseLocaleResolvesNeutralContextToLatinText")
        // The paragraph-language fallback answers by locale: a lone
        // unmatched apostrophe in an English context takes the Latin role.
        val decisions = ContextualQuoteRoleResolver(
            text = "’",
            pairs = emptyList(),
            context = FontRoleContext(locale = "en-US"),
        ).resolve()
        assertEquals(FontRole.LatinText, decisions.single().role)
        assertTrue(decisions.single().reason.contains("paragraph-language=en-US"))
    }

    @Test
    fun privateUseCharBeforeAQuoteFailsTheLowSurrogateRangeAbove() {
        testTrace.section("privateUseCharBeforeAQuoteFailsTheLowSurrogateRangeAbove")
        // The scan char above the low-surrogate range fails the range test
        // on its upper comparison, walks as one neutral unit, and the
        // surrounding CJK script decides.
        val decision = decisions("\uE000“中").first { it.index == 1 }
        assertEquals("UnmatchedQuoteSurroundingScriptContext", decision.source)
        assertEquals(FontRole.CjkPunctuation, decision.role)
    }

    @Test
    fun highSurrogateAtTheContentEndHasNoRoomAndThrows() {
        testTrace.section("highSurrogateAtTheContentEndHasNoRoomAndThrows")
        // The high surrogate is the last content char: the length walk has
        // no room for a low half, the compat reader returns the lone high,
        // and the classifier rejects it.
        assertFailsWith<IllegalArgumentException> { decisions(surrogateText(0x201C, 0xD83D, 0x201D)) }
    }

    @Test
    fun siblingPairsInsideOneQuotationEachInheritTheOuterRole() {
        testTrace.section("siblingPairsInsideOneQuotationEachInheritTheOuterRole")
        // Two single pairs inside the same double pair: the parent search
        // for the second pair visits the first as a candidate that opens
        // earlier but closes earlier too, and both pairs inherit the
        // resolved outer quotation.
        val text = "“‘a’‘b’”"
        val decisions = decisions(text)
        val first = decisions.first { it.index == 1 }
        val second = decisions.first { it.index == 4 }
        assertEquals("PairedPunctuationEnclosingQuoteContext", first.source)
        assertEquals("PairedPunctuationEnclosingQuoteContext", second.source)
        assertEquals(first.role, second.role)
    }

    @Test
    fun plainFollowerOfAHighSurrogateCountsAsOneUnit() {
        testTrace.section("plainFollowerOfAHighSurrogateCountsAsOneUnit")
        // A BMP letter after a high surrogate fails the low-surrogate range
        // on its lower comparison, so the walk takes one unit and the
        // classifier rejects the lone high half.
        assertFailsWith<IllegalArgumentException> { decisions(surrogateText(0x201C, 0xD83D, 'x'.code, 0x201D)) }
    }

    @Test
    fun privateUseFollowerOfAHighSurrogateCountsAsOneUnit() {
        testTrace.section("privateUseFollowerOfAHighSurrogateCountsAsOneUnit")
        // Inside the quoted content a high surrogate is followed by a
        // private-use char above the low-surrogate range: the length walk
        // answers one unit on its upper comparison, and the compat reader
        // then rejects the lone high half in the classifier.
        assertFailsWith<IllegalArgumentException> { decisions(surrogateText(0x201C, 0xD83D, 0xE000, 0x201D)) }
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
