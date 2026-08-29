package org.tiqian.ffi.js

import kotlin.test.Test
import kotlin.test.assertEquals

class LineBreakExportsTest {

    @Test
    fun liangHyphenateReturnsBreakOffsetsAsJson() {
        // Pattern "1c": level 1 (odd) in the gap before any 'c'. With
        // leftMin/rightMin = 1, "abc" breaks before the final c (ab-c).
        assertEquals("[2]", liangHyphenate("abc", """{"c":[1,0]}""", "{}", leftMin = 1, rightMin = 1))
        assertEquals("[]", liangHyphenate("cab", """{"c":[1,0]}""", "{}", leftMin = 1, rightMin = 1))

        // Default margins make short words unbreakable.
        assertEquals("[]", liangHyphenate("abc", """{"c":[1,0]}""", "{}"))
    }

    @Test
    fun liangHyphenateAppliesCaseInsensitiveExceptions() {
        val exceptionsJson = """{"table":[2]}"""
        assertEquals("[2]", liangHyphenate("table", "{}", exceptionsJson, leftMin = 1, rightMin = 1))
        assertEquals("[2]", liangHyphenate("Table", "{}", exceptionsJson, leftMin = 1, rightMin = 1))
    }

    @Test
    fun unicodePunctuationLineBreakClassOfReturnsClassName() {
        assertEquals("OpenPunctuation", unicodePunctuationLineBreakClassOf('（'.code))
        assertEquals("CloseParenthesis", unicodePunctuationLineBreakClassOf(')'.code))
        assertEquals("ClosePunctuation", unicodePunctuationLineBreakClassOf('）'.code))
        assertEquals("Quotation", unicodePunctuationLineBreakClassOf('“'.code))
        assertEquals("Other", unicodePunctuationLineBreakClassOf('中'.code))
    }
}