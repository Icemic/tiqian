package org.tiqian.linebreak

import kotlin.test.Test
import kotlin.test.assertEquals

class UnicodePunctuationLineBreakTest {
    @Test
    fun exposesPinnedWesternAndCjkPunctuationClasses() {
        val expected = mapOf(
            '(' to UnicodePunctuationLineBreakClass.OpenPunctuation,
            ')' to UnicodePunctuationLineBreakClass.CloseParenthesis,
            '{' to UnicodePunctuationLineBreakClass.OpenPunctuation,
            '}' to UnicodePunctuationLineBreakClass.ClosePunctuation,
            '!' to UnicodePunctuationLineBreakClass.Exclamation,
            ',' to UnicodePunctuationLineBreakClass.InfixNumericSeparator,
            '/' to UnicodePunctuationLineBreakClass.SymbolsAllowingBreakAfter,
            '-' to UnicodePunctuationLineBreakClass.Hyphen,
            '…' to UnicodePunctuationLineBreakClass.Inseparable,
            '“' to UnicodePunctuationLineBreakClass.Quotation,
            '”' to UnicodePunctuationLineBreakClass.Quotation,
            '（' to UnicodePunctuationLineBreakClass.OpenPunctuation,
            '）' to UnicodePunctuationLineBreakClass.ClosePunctuation,
        )

        expected.forEach { (char, lineBreakClass) ->
            assertEquals(lineBreakClass, UnicodePunctuationLineBreak.classOf(char.code), char.toString())
        }
    }

    @Test
    fun ordinaryLettersAreOutsideThePunctuationSubset() {
        assertEquals(UnicodePunctuationLineBreakClass.Other, UnicodePunctuationLineBreak.classOf('A'.code))
        assertEquals(UnicodePunctuationLineBreakClass.Other, UnicodePunctuationLineBreak.classOf('中'.code))
    }
}
