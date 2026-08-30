package org.tiqian.linebreak

import org.tiqian.core.TextRange
import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.assertFailsWith
import org.tiqian.test.trace.assertFalse
import org.tiqian.test.trace.assertNotNull
import org.tiqian.test.trace.assertTrue
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

class LineBreakCoverageTest {
    private val testTrace = TestTraceRecorder("LineBreakCoverageTest")


    @Test
    fun testBundledHyphenationResource() {
        testTrace.section("testBundledHyphenationResource")
        val patterns = loadBundledEnglishHyphenationPatterns()
        assertTrue(patterns.isNotEmpty())
        assertTrue(patterns.contains("\\patterns"))
    }

    @Test
    fun testLineBreakModelsAndEnums() {
        testTrace.section("testLineBreakModelsAndEnums")
        val opportunity = BreakOpportunity(
            index = 5,
            kind = BreakKind.Allowed,
            penalty = 10,
            reason = "TestReason",
        )
        assertEquals(5, opportunity.index)
        assertEquals(BreakKind.Allowed, opportunity.kind)
        assertEquals(10, opportunity.penalty)
        assertEquals("TestReason", opportunity.reason)

        val copyOp = opportunity.copy(kind = BreakKind.Problematic)
        assertEquals(BreakKind.Problematic, copyOp.kind)
        assertEquals(opportunity, opportunity.copy())
        assertTrue(opportunity.hashCode() == opportunity.copy().hashCode())
        assertTrue(opportunity.toString().contains("BreakOpportunity"))

        for (kind in BreakKind.entries) {
            assertNotNull(BreakKind.valueOf(kind.name))
        }

        val forbidden = ForbiddenBreak(
            range = TextRange(2, 6),
            reason = "ForbiddenReason",
        )
        assertEquals(TextRange(2, 6), forbidden.range)
        assertEquals("ForbiddenReason", forbidden.reason)
        assertEquals(forbidden, forbidden.copy())
        assertTrue(forbidden.hashCode() == forbidden.copy().hashCode())
        assertTrue(forbidden.toString().contains("ForbiddenBreak"))
    }

    @Test
    fun testMandatoryBreakAndZeroWidthSpaceCodePoints() {
        testTrace.section("testMandatoryBreakAndZeroWidthSpaceCodePoints")
        val mandatory = intArrayOf(0x000A, 0x000B, 0x000C, 0x000D, 0x0085, 0x2028, 0x2029)
        for (cp in mandatory) {
            assertTrue(isMandatoryBreakCodePoint(cp), "Code point $cp should be mandatory break")
        }
        val nonMandatory = intArrayOf(0x0020, 0x0041, 0x0000, 0x200B, 0x202A)
        for (cp in nonMandatory) {
            assertFalse(isMandatoryBreakCodePoint(cp), "Code point $cp should not be mandatory break")
        }

        assertTrue(isZeroWidthSpaceCodePoint(0x200B))
        assertFalse(isZeroWidthSpaceCodePoint(0x200C))
        assertFalse(isZeroWidthSpaceCodePoint(0x2060))
        assertFalse(isZeroWidthSpaceCodePoint(0xFEFF))
        assertFalse(isZeroWidthSpaceCodePoint(0x0020))
    }

    @Test
    fun testSimpleCharacterLineBreakAnalyzer() {
        testTrace.section("testSimpleCharacterLineBreakAnalyzer")
        val analyzer = SimpleCharacterLineBreakAnalyzer()

        // Empty text
        assertEquals(emptyList(), analyzer.analyze(""))

        // Single character
        val single = analyzer.analyze("A")
        assertEquals(1, single.size)
        assertEquals(1, single[0].index)
        assertEquals(BreakKind.Required, single[0].kind)
        assertEquals("SimpleCharacterLineBreakAnalyzer", single[0].reason)

        // Multiple characters
        val multi = analyzer.analyze("abc")
        assertEquals(3, multi.size)
        assertEquals(BreakKind.Allowed, multi[0].kind)
        assertEquals(BreakKind.Allowed, multi[1].kind)
        assertEquals(BreakKind.Required, multi[2].kind)

        // LF mandatory break
        val withLf = analyzer.analyze("a\nb")
        assertEquals(3, withLf.size)
        assertEquals(BreakKind.Allowed, withLf[0].kind)
        assertEquals(BreakKind.Required, withLf[1].kind)
        assertEquals("MandatoryBreak", withLf[1].reason)
        assertEquals(BreakKind.Required, withLf[2].kind)

        // CRLF pair - CR should not be treated as mandatory break because following char is LF
        val withCrlf = analyzer.analyze("a\r\nb")
        assertEquals(4, withCrlf.size)
        assertEquals(BreakKind.Allowed, withCrlf[0].kind)
        assertEquals(BreakKind.Allowed, withCrlf[1].kind) // CR before LF is Allowed
        assertEquals("SimpleCharacterLineBreakAnalyzer", withCrlf[1].reason)
        assertEquals(BreakKind.Required, withCrlf[2].kind) // LF is Required
        assertEquals("MandatoryBreak", withCrlf[2].reason)
        assertEquals(BreakKind.Required, withCrlf[3].kind)

        // CR followed by non-LF
        val withCrOther = analyzer.analyze("a\rb")
        assertEquals(3, withCrOther.size)
        assertEquals(BreakKind.Allowed, withCrOther[0].kind)
        assertEquals(BreakKind.Required, withCrOther[1].kind) // CR before 'b' is mandatory
        assertEquals("MandatoryBreak", withCrOther[1].reason)
        assertEquals(BreakKind.Required, withCrOther[2].kind)

        // CR at end of string (index == text.length)
        val withCrEnd = analyzer.analyze("a\r")
        assertEquals(2, withCrEnd.size)
        assertEquals(BreakKind.Allowed, withCrEnd[0].kind)
        assertEquals(BreakKind.Required, withCrEnd[1].kind) // CR at end is mandatory & required
        assertEquals("MandatoryBreak", withCrEnd[1].reason)
    }

    @Test
    fun testHyphenationComponents() {
        testTrace.section("testHyphenationComponents")
        assertEquals(emptyList(), NoHyphenator.hyphenate("word"))

        val patterns = mapOf(
            "hyp" to intArrayOf(0, 0, 1, 0),
            "phen" to intArrayOf(0, 0, 2, 0, 0),
        )
        val exceptions = mapOf(
            "specialword" to listOf(1, 4, 10), // 1 is < leftMin (2), 10 is > word.length - rightMin (8)
        )
        val hyphenator = LiangHyphenator(
            patterns = patterns,
            exceptions = exceptions,
            leftMin = 2,
            rightMin = 3,
        )

        // Too short word (< leftMin + rightMin = 5)
        assertEquals(emptyList(), hyphenator.hyphenate("test"))

        // Exception word: 1 (<2) and 10 (>8) must be filtered out, only 4 remains
        assertEquals(listOf(4), hyphenator.hyphenate("SpecialWord"))

        // Pattern without match
        assertEquals(emptyList(), hyphenator.hyphenate("zzzzzz"))

        // Tex pattern parsing edge cases
        val emptyTex = parseTexHyphenationPatterns("% only comments\n   ")
        assertEquals(0, emptyTex.first.size)
        assertEquals(0, emptyTex.second.size)

        val missingBracesTex = parseTexHyphenationPatterns("\\patterns no braces \\hyphenation no braces")
        assertEquals(0, missingBracesTex.first.size)
        assertEquals(0, missingBracesTex.second.size)

        val unclosedBracesTex = parseTexHyphenationPatterns("\\patterns { abc \n\\hyphenation { def")
        assertEquals(0, unclosedBracesTex.first.size)
        assertEquals(0, unclosedBracesTex.second.size)

        val validTex = parseTexHyphenationPatterns(
            """
            % Comment line
            \patterns{
                .ab3cd.
                e1f
            }
            \hyphenation{
                as-so-ciate
                dis-allow-
            }
            """.trimIndent(),
        )
        assertTrue(validTex.first.containsKey(".abcd."))
        assertTrue(validTex.second.containsKey("associate"))
        assertEquals(listOf(2, 4), validTex.second["associate"])
    }

    @Test
    fun testUnicodePunctuationLineBreak() {
        testTrace.section("testUnicodePunctuationLineBreak")
        assertEquals("17.0.0", UnicodePunctuationLineBreak.DATA_REVISION)
        assertTrue(UnicodePunctuationLineBreak.DATA_SOURCE.isNotEmpty())
        assertTrue(UnicodePunctuationLineBreak.DATA_SHA256.isNotEmpty())

        for (item in UnicodePunctuationLineBreakClass.entries) {
            assertNotNull(UnicodePunctuationLineBreakClass.valueOf(item.name))
        }

        // Invalid code points
        assertFailsWith<IllegalArgumentException> {
            UnicodePunctuationLineBreak.classOf(-1)
        }
        assertFailsWith<IllegalArgumentException> {
            UnicodePunctuationLineBreak.classOf(0x110000)
        }
        assertFailsWith<IllegalArgumentException> {
            UnicodePunctuationLineBreak.classOf(0xD800)
        }
        assertFailsWith<IllegalArgumentException> {
            UnicodePunctuationLineBreak.classOf(0xDFFF)
        }

        // Test every class mapping from classOf
        assertEquals(UnicodePunctuationLineBreakClass.BreakAfter, UnicodePunctuationLineBreak.classOf(0x0009)) // TAB
        assertEquals(UnicodePunctuationLineBreakClass.BreakBoth, UnicodePunctuationLineBreak.classOf(0x2014)) // EM DASH
        assertEquals(UnicodePunctuationLineBreakClass.ClosePunctuation, UnicodePunctuationLineBreak.classOf(0x007D)) // '}'
        assertEquals(UnicodePunctuationLineBreakClass.CloseParenthesis, UnicodePunctuationLineBreak.classOf(0x0029)) // ')'
        assertEquals(UnicodePunctuationLineBreakClass.Exclamation, UnicodePunctuationLineBreak.classOf(0x0021)) // '!'
        assertEquals(UnicodePunctuationLineBreakClass.HyphenHH, UnicodePunctuationLineBreak.classOf(0x058A)) // ARMENIAN HYPHEN
        assertEquals(UnicodePunctuationLineBreakClass.Hyphen, UnicodePunctuationLineBreak.classOf(0x002D)) // '-'
        assertEquals(UnicodePunctuationLineBreakClass.Inseparable, UnicodePunctuationLineBreak.classOf(0x2025)) // TWO DOT LEADER
        assertEquals(UnicodePunctuationLineBreakClass.InfixNumericSeparator, UnicodePunctuationLineBreak.classOf(0x002C)) // ','
        assertEquals(UnicodePunctuationLineBreakClass.Nonstarter, UnicodePunctuationLineBreak.classOf(0x3005)) // IDEOGRAPHIC ITERATION MARK
        assertEquals(UnicodePunctuationLineBreakClass.OpenPunctuation, UnicodePunctuationLineBreak.classOf(0x0028)) // '('
        assertEquals(UnicodePunctuationLineBreakClass.Quotation, UnicodePunctuationLineBreak.classOf(0x0022)) // '"'
        assertEquals(UnicodePunctuationLineBreakClass.SymbolsAllowingBreakAfter, UnicodePunctuationLineBreak.classOf(0x002F)) // '/'
        assertEquals(UnicodePunctuationLineBreakClass.Other, UnicodePunctuationLineBreak.classOf(0x0041)) // 'A'
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
