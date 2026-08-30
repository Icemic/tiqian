package org.tiqian.ffi.js

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FontExportsTest {

    @Test
    fun loweringRoleBatchUsesCompleteParagraphContext() {
        val text = "A——B中文……下句"
        val starts = arrayOf(1, 2, 6, 7)
        val ends = arrayOf(2, 3, 7, 8)

        assertContentEquals(
            arrayOf("other", "other", "cjk-punctuation", "cjk-punctuation"),
            classifyFontRoles(text, starts, ends, "zh-Hans"),
        )
        assertEquals("other", classifyFontRole("A——B", 1, 2, "zh-Hans"))
    }

    @Test
    fun loweringRoleUsesStructuralQuotePairResolution() {
        val text = "word“中文”word"

        assertEquals("other", classifyFontRole(text, 4, 5, "zh-Hans"))
        assertEquals("other", classifyFontRole(text, 7, 8, "zh-Hans"))
        assertContentEquals(
            arrayOf("other", "cjk-text", "other"),
            classifyFontRoles(text, arrayOf(4, 5, 7), arrayOf(5, 6, 8), "zh-Hans"),
        )
    }

    @Test
    fun fontMetricsResolveReturnsRawMetricsJsonForCjkRole() {
        val requestJson =
            """{"fontKey":"cjk-key","fontSize":18,"role":"CjkText","locale":"zh-Hans","fontFamilies":["Source Han Sans"],"fontWeight":400,"italic":false,"faceSelectionText":"中"}"""

        val json = fontMetricsResolve(requestJson)
        assertEquals(
            """{"ascent":20.88,"descent":5.184000015258789,"leading":0,"source":"RawTables","typoAscent":15.84,"typoDescent":2.16}""",
            json,
        )

        val parsed = kotlin.js.JSON.parse<dynamic>(json)
        assertEquals("RawTables", parsed.source as String)
        assertEquals(20.88, parsed.ascent as Double, 1e-6)
        assertEquals(5.184, parsed.descent as Double, 1e-6)
        assertEquals(15.84, parsed.typoAscent as Double, 1e-6)
        assertEquals(2.16, parsed.typoDescent as Double, 1e-6)
    }

    @Test
    fun fontMetricsResolveOmitsTypoPairWhenAbsent() {
        val requestJson =
            """{"fontKey":"latin-key","fontSize":18,"role":"LatinText","locale":"en","fontFamilies":[],"fontWeight":400,"italic":false,"faceSelectionText":"Hi"}"""

        val json = fontMetricsResolve(requestJson)
        assertEquals(
            """{"ascent":14.4,"descent":3.6,"leading":0,"source":"RawTables"}""",
            json,
        )

        val parsed = kotlin.js.JSON.parse<dynamic>(json)
        assertNull(parsed.typoAscent)
        assertNull(parsed.typoDescent)
    }

    @Test
    fun fontFallbackResolveReturnsFontDecisionJson() {
        val cjkRequest = """{"preferredFamilies":["Source Han Sans"],"locale":"zh-Hans","role":"CjkText"}"""
        val cjkJson = fontFallbackResolve("中文", 0, 1, cjkRequest)
        val cjk = kotlin.js.JSON.parse<dynamic>(cjkJson)
        assertEquals(0.0, cjk.range.start as Double)
        assertEquals(1.0, cjk.range.end as Double)
        assertEquals("cjk-primary", cjk.candidate.key as String)
        assertEquals("Source Han Sans", cjk.candidate.family as String)
        assertEquals("CjkText", cjk.candidate.role as String)
        assertEquals("CjkText", cjk.role as String)
        assertEquals(
            "PreferCjkForAmbiguousPunctuationResolver:CjkText",
            cjk.reason as String,
        )
        assertContains(cjkJson, "\"candidate\":{\"key\":\"cjk-primary\"")

        val latinRequest = """{"preferredFamilies":[],"locale":"en","role":"LatinText"}"""
        val latin = kotlin.js.JSON.parse<dynamic>(fontFallbackResolve("Hi", 0, 2, latinRequest))
        assertEquals("latin-primary", latin.candidate.key as String)
        assertEquals("latin-primary", latin.candidate.family as String)
        assertEquals("LatinText", latin.role as String)
    }
}
