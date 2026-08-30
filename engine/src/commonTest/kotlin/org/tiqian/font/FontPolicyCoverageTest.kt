package org.tiqian.font

import org.tiqian.core.TextRange
import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.assertFalse
import org.tiqian.test.trace.assertNotNull
import org.tiqian.test.trace.assertTrue
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

// A lone surrogate written inside a string literal is replaced with '?' when
// the JS test bundle re-serializes its sources, so inputs that carry one are
// built from char codes at runtime to keep the code unit intact everywhere.
private fun surrogateText(vararg codes: Int): String =
    CharArray(codes.size) { codes[it].toChar() }.concatToString()

class FontPolicyCoverageTest {
    private val testTrace = TestTraceRecorder("FontPolicyCoverageTest")


    @Test
    fun testFontRequestAndRoles() {
        testTrace.section("testFontRequestAndRoles")
        val request = FontRequest(
            preferredFamilies = listOf("Source Han Sans"),
            locale = "zh-Hans",
            role = FontRole.CjkText,
        )
        assertEquals(listOf("Source Han Sans"), request.preferredFamilies)
        assertEquals("zh-Hans", request.locale)
        assertEquals(FontRole.CjkText, request.role)
        assertEquals(request, request.copy())
        assertTrue(request.hashCode() == request.copy().hashCode())

        for (role in FontRole.entries) {
            assertNotNull(FontRole.valueOf(role.name))
        }

        assertTrue(FontRole.LatinText.usesLatinFace())
        assertFalse(FontRole.CjkText.usesLatinFace())
        assertFalse(FontRole.CjkPunctuation.usesLatinFace())
        assertFalse(FontRole.Symbol.usesLatinFace())
        assertFalse(FontRole.Emoji.usesLatinFace())
        assertFalse(FontRole.Unknown.usesLatinFace())

        assertTrue(fontRoleNameUsesLatinFace("LatinText"))
        assertFalse(fontRoleNameUsesLatinFace("CjkText"))
        assertFalse(fontRoleNameUsesLatinFace("Unknown"))
        assertFalse(fontRoleNameUsesLatinFace(null))
        assertFalse(fontRoleNameUsesLatinFace("NotARole"))

        val candidate = FontCandidate("cjk-key", "Source Han Sans", FontRole.CjkText)
        assertEquals("cjk-key", candidate.key)
        assertEquals("Source Han Sans", candidate.family)
        assertEquals(FontRole.CjkText, candidate.role)
        assertEquals(candidate, candidate.copy())
        assertTrue(candidate.hashCode() == candidate.copy().hashCode())

        val decision = FontDecision(TextRange(0, 1), candidate, FontRole.CjkText, "reason")
        assertEquals(TextRange(0, 1), decision.range)
        assertEquals(candidate, decision.candidate)
        assertEquals(FontRole.CjkText, decision.role)
        assertEquals("reason", decision.reason)
        assertEquals(decision, decision.copy())
        assertTrue(decision.hashCode() == decision.copy().hashCode())

        val context = FontRoleContext(locale = "zh-TW", regionHint = "TW")
        assertEquals("zh-TW", context.locale)
        assertEquals("TW", context.regionHint)
        assertEquals(context, context.copy())
        assertTrue(context.hashCode() == context.copy().hashCode())
    }

    @Test
    fun testCjkFontRoleClassifierAllRanges() {
        testTrace.section("testCjkFontRoleClassifierAllRanges")
        val classifier = CjkFontRoleClassifier()

        // Bopomofo and CJK blocks
        assertEquals(FontRole.CjkText, classifier.classify("ㄅ", TextRange(0, 1))) // 0x3105
        assertEquals(FontRole.CjkText, classifier.classify("ㆠ", TextRange(0, 1))) // 0x31A0
        assertEquals(FontRole.CjkText, classifier.classify("㐀", TextRange(0, 1))) // 0x3400
        assertEquals(FontRole.CjkText, classifier.classify("一", TextRange(0, 1))) // 0x4E00
        assertEquals(FontRole.CjkText, classifier.classify("豈", TextRange(0, 1))) // 0xF900
        assertEquals(FontRole.CjkText, classifier.classify("\uD840\uDC00", TextRange(0, 2))) // 0x20000 (Ext B)
        assertEquals(FontRole.CjkText, classifier.classify("\uD869\uDF00", TextRange(0, 2))) // 0x2A700 (Ext C)
        assertEquals(FontRole.CjkText, classifier.classify("\uD86D\uDF40", TextRange(0, 2))) // 0x2B740 (Ext D)
        assertEquals(FontRole.CjkText, classifier.classify("\uD86E\uDC20", TextRange(0, 2))) // 0x2B820 (Ext E)
        assertEquals(FontRole.CjkText, classifier.classify("\uD880\uDC00", TextRange(0, 2))) // 0x30000 (Ext G)
        // Code point in SIP > 0x3134F (Ext G upper bound: 0x32000 = \uD888\uDC00) -> not CJK
        assertEquals(FontRole.Unknown, classifier.classify("\uD888\uDC00", TextRange(0, 2)))

        // CJK Punctuation
        val cjkPunctChars = listOf(
            "\u3000", "—", "–", "‼", "⁇", "…", "‧", "⋯", "・", "⸺", "·", "•",
            "！", "？", "，", "．", "／", "：", "；", "（", "）", "～",
        )
        for (p in cjkPunctChars) {
            assertEquals(
                FontRole.CjkPunctuation,
                classifier.classify(p, TextRange(0, p.length)),
                "Expected CjkPunctuation for $p",
            )
        }

        // Latin curly quote vs CJK curly quote
        assertEquals(FontRole.LatinText, classifier.classify("a’b", TextRange(1, 2))) // U+2019
        assertEquals(FontRole.LatinText, classifier.classify("a”b", TextRange(1, 2))) // U+201D
        assertEquals(FontRole.CjkPunctuation, classifier.classify("’b", TextRange(0, 1))) // at start -> null before
        assertEquals(FontRole.CjkPunctuation, classifier.classify("a’", TextRange(1, 2))) // at end -> null after
        assertEquals(FontRole.CjkPunctuation, classifier.classify("中’文", TextRange(1, 2))) // non-Latin surround

        // Quote with surrogate before/after in previousCodePointBefore
        assertEquals(FontRole.CjkPunctuation, classifier.classify("\uD83D\uDE00’b", TextRange(2, 3))) // surrogate pair before
        assertEquals(FontRole.CjkPunctuation, classifier.classify(surrogateText('A'.code, 0xDC00, 0x2019, 'b'.code), TextRange(2, 3))) // high < D800 before low surrogate
        assertEquals(FontRole.CjkPunctuation, classifier.classify(surrogateText(0xE000, 0xDC00, 0x2019, 'b'.code), TextRange(2, 3))) // high > DBFF before low surrogate
        assertEquals(FontRole.CjkPunctuation, classifier.classify(surrogateText(0xDC00, 0x2019, 'b'.code), TextRange(1, 2))) // index - 2 < 0 before
        assertEquals(FontRole.CjkPunctuation, classifier.classify("\uE000’b", TextRange(1, 2))) // low > DFFF before quote
        assertEquals(FontRole.CjkPunctuation, classifier.classify("a’\uD83D\uDE00", TextRange(1, 2))) // surrogate after

        // codePointAtCompat variations at start
        assertEquals(FontRole.Unknown, classifier.classify("\uE000", TextRange(0, 1))) // high > DBFF
        assertEquals(FontRole.Unknown, classifier.classify(surrogateText(0xD800), TextRange(0, 1))) // high without low (at end)
        assertEquals(FontRole.Unknown, classifier.classify(surrogateText(0xD800, 'A'.code), TextRange(0, 2))) // low < DC00
        assertEquals(FontRole.Unknown, classifier.classify(surrogateText(0xD800, 0xE000), TextRange(0, 2))) // low > DFFF

        // Supplementary code point > 0xFFFF hitting toCharOrNull() -> null
        assertEquals(FontRole.Unknown, classifier.classify("\uD804\uDC00", TextRange(0, 2))) // U+11000 Brahmi (not CJK, not emoji, > 0xFFFF)

        // Latin code points
        assertEquals(FontRole.LatinText, classifier.classify("A", TextRange(0, 1)))
        assertEquals(FontRole.LatinText, classifier.classify("z", TextRange(0, 1)))
        assertEquals(FontRole.LatinText, classifier.classify("0", TextRange(0, 1)))
        assertEquals(FontRole.LatinText, classifier.classify(" ", TextRange(0, 1)))
        assertEquals(FontRole.LatinText, classifier.classify("+", TextRange(0, 1))) // ASCII is typed Latin
        assertEquals(FontRole.LatinText, classifier.classify("\u00C0", TextRange(0, 1))) // Latin-1
        assertEquals(FontRole.LatinText, classifier.classify("\u0150", TextRange(0, 1))) // Latin Extended

        // Emoji
        assertEquals(FontRole.Emoji, classifier.classify("\uD83D\uDE00", TextRange(0, 2)))

        // Non-ASCII Symbols
        assertEquals(FontRole.Symbol, classifier.classify("≠", TextRange(0, 1))) // Math (U+2260)
        assertEquals(FontRole.Symbol, classifier.classify("€", TextRange(0, 1))) // Currency (U+20AC)
        assertEquals(FontRole.Symbol, classifier.classify("˘", TextRange(0, 1))) // Modifier symbol (U+02D8)
        assertEquals(FontRole.Symbol, classifier.classify("©", TextRange(0, 1))) // Other symbol (U+00A9)

        // Unknown
        assertEquals(FontRole.Unknown, classifier.classify("\u0001", TextRange(0, 1)))
    }

    @Test
    fun testPreferCjkForAmbiguousPunctuationResolver() {
        testTrace.section("testPreferCjkForAmbiguousPunctuationResolver")
        val resolver = PreferCjkForAmbiguousPunctuationResolver(
            cjkFontKey = "cjk-key",
            latinFontKey = "latin-key",
            symbolFontKey = "symbol-key",
        )

        val cjkDecision = resolver.resolve(
            "中",
            TextRange(0, 1),
            FontRequest(listOf("CustomCjk"), "zh-Hans", FontRole.CjkText),
        )
        assertEquals("cjk-key", cjkDecision.candidate.key)
        assertEquals("CustomCjk", cjkDecision.candidate.family)

        val cjkDefaultFamily = resolver.resolve(
            "中",
            TextRange(0, 1),
            FontRequest(emptyList(), "zh-Hans", FontRole.CjkPunctuation),
        )
        assertEquals("cjk-key", cjkDefaultFamily.candidate.family)

        val latinDecision = resolver.resolve(
            "A",
            TextRange(0, 1),
            FontRequest(emptyList(), "en", FontRole.LatinText),
        )
        assertEquals("latin-key", latinDecision.candidate.key)

        val symbolDecision = resolver.resolve(
            "©",
            TextRange(0, 1),
            FontRequest(emptyList(), "en", FontRole.Symbol),
        )
        assertEquals("symbol-key", symbolDecision.candidate.key)

        val emojiDecision = resolver.resolve(
            "\uD83D\uDE00",
            TextRange(0, 2),
            FontRequest(emptyList(), "en", FontRole.Emoji),
        )
        assertEquals("symbol-key", emojiDecision.candidate.key)

        val unknownDecision = resolver.resolve(
            "\u0001",
            TextRange(0, 1),
            FontRequest(emptyList(), "en", FontRole.Unknown),
        )
        assertEquals("symbol-key", unknownDecision.candidate.key)
    }

    @Test
    fun testFontEnumsAndModels() {
        testTrace.section("testFontEnumsAndModels")
        for (policy in FontMetricsPolicy.entries) {
            assertNotNull(FontMetricsPolicy.valueOf(policy.name))
        }
        for (policy in BaselinePolicy.entries) {
            assertNotNull(BaselinePolicy.valueOf(policy.name))
        }
        for (policy in PunctuationFontPolicy.entries) {
            assertNotNull(PunctuationFontPolicy.valueOf(policy.name))
        }

        val rawMetrics = RawFontMetrics(
            ascent = 16.0f,
            descent = 4.0f,
            leading = 2.0f,
            source = FontMetricSource.RawTables,
            typoAscent = 14.0f,
            typoDescent = 2.0f,
        )
        assertEquals(16.0f, rawMetrics.ascent)
        assertEquals(4.0f, rawMetrics.descent)
        assertEquals(2.0f, rawMetrics.leading)
        assertEquals(14.0f, rawMetrics.typoAscent)
        assertEquals(2.0f, rawMetrics.typoDescent)
        assertEquals(rawMetrics, rawMetrics.copy())
        assertTrue(rawMetrics.hashCode() == rawMetrics.copy().hashCode())

        val layoutMetrics = LayoutFontMetrics(
            ascent = 14.0f,
            descent = 2.0f,
            baselineOffset = 0.0f,
            policy = FontMetricsPolicy.IdeographicBox,
            baselinePolicy = BaselinePolicy.Ideographic,
            baselineClass = BaselineClass.IdeographicLow,
            metricBox = MetricBox.IdeographicEmBox,
            source = FontMetricSource.RawTables,
            reason = "test",
        )
        assertEquals(14.0f, layoutMetrics.ascent)
        assertEquals(layoutMetrics, layoutMetrics.copy())
        assertTrue(layoutMetrics.hashCode() == layoutMetrics.copy().hashCode())
    }

    @Test
    fun testFontMetricsRequestAndResolvers() {
        testTrace.section("testFontMetricsRequestAndResolvers")
        val request = FontMetricsRequest(
            fontKey = "key1",
            fontSize = 16.0f,
            role = FontRole.CjkText,
            locale = "zh-Hans",
            fontFamilies = listOf("FontA"),
            fontWeight = 700,
            italic = true,
            faceSelectionText = "测试",
        )
        assertEquals("key1", request.fontKey)
        assertEquals(16.0f, request.fontSize)
        assertEquals(FontRole.CjkText, request.role)
        assertEquals("zh-Hans", request.locale)
        assertEquals(listOf("FontA"), request.fontFamilies)
        assertEquals(700, request.fontWeight)
        assertTrue(request.italic)
        assertEquals("测试", request.faceSelectionText)
        assertEquals(request, request.copy())
        assertTrue(request.hashCode() == request.copy().hashCode())

        val stubResolver = StubFontMetricsResolver()
        val cjkRaw = stubResolver.resolve(request)
        assertEquals(16.0f * 1.16f, cjkRaw.ascent)
        assertEquals(16.0f * 0.88f, cjkRaw.typoAscent)

        val punctRaw = stubResolver.resolve(request.copy(role = FontRole.CjkPunctuation))
        assertEquals(16.0f * 1.16f, punctRaw.ascent)

        val latinRaw = stubResolver.resolve(request.copy(role = FontRole.LatinText))
        assertEquals(16.0f * 0.8f, latinRaw.ascent)

        val symbolRaw = stubResolver.resolve(request.copy(role = FontRole.Symbol))
        assertEquals(16.0f * 0.9f, symbolRaw.ascent)

        val emojiRaw = stubResolver.resolve(request.copy(role = FontRole.Emoji))
        assertEquals(16.0f * 0.9f, emojiRaw.ascent)

        val unknownRaw = stubResolver.resolve(request.copy(role = FontRole.Unknown))
        assertEquals(16.0f * 0.9f, unknownRaw.ascent)
    }

    @Test
    fun testScriptAwareFontMetricsNormalizerBranches() {
        testTrace.section("testScriptAwareFontMetricsNormalizerBranches")
        val normalizer = ScriptAwareFontMetricsNormalizer()
        val baseRequest = FontMetricsRequest(
            fontKey = "key",
            fontSize = 16.0f,
            role = FontRole.CjkText,
            locale = "zh-Hans",
        )

        // 1. CJK with both typoAscent and typoDescent present
        val inputWithTypo = FontMetricsNormalizationInput(
            request = baseRequest,
            rawMetrics = RawFontMetrics(ascent = 18.0f, descent = 5.0f, typoAscent = 14.0f, typoDescent = 2.0f),
        )
        val resTypo = normalizer.normalize(inputWithTypo)
        assertEquals(14.0f, resTypo.ascent)
        assertEquals(2.0f, resTypo.descent)
        assertEquals(FontMetricsPolicy.IdeographicBox, resTypo.policy)
        assertTrue(resTypo.reason.contains("font-typo-box"))

        // 2. CJK with typoAscent present but typoDescent null
        val inputPartialTypo1 = FontMetricsNormalizationInput(
            request = baseRequest,
            rawMetrics = RawFontMetrics(ascent = 18.0f, descent = 5.0f, typoAscent = 14.0f, typoDescent = null),
        )
        val resPartialTypo1 = normalizer.normalize(inputPartialTypo1)
        assertEquals(14.0f, resPartialTypo1.ascent)
        assertEquals(5.0f, resPartialTypo1.descent)
        assertEquals(FontMetricsPolicy.Raw, resPartialTypo1.policy)
        assertTrue(resPartialTypo1.reason.contains("hhea-fallback-no-os2"))

        // 3. CJK with typoAscent null but typoDescent present
        val inputPartialTypo2 = FontMetricsNormalizationInput(
            request = baseRequest,
            rawMetrics = RawFontMetrics(ascent = 18.0f, descent = 5.0f, typoAscent = null, typoDescent = 2.0f),
        )
        val resPartialTypo2 = normalizer.normalize(inputPartialTypo2)
        assertEquals(18.0f, resPartialTypo2.ascent)
        assertEquals(2.0f, resPartialTypo2.descent)
        assertEquals(FontMetricsPolicy.Raw, resPartialTypo2.policy)

        // 4. CJK with both typoAscent and typoDescent null
        val inputNoTypo = FontMetricsNormalizationInput(
            request = baseRequest,
            rawMetrics = RawFontMetrics(ascent = 18.0f, descent = 5.0f, typoAscent = null, typoDescent = null),
        )
        val resNoTypo = normalizer.normalize(inputNoTypo)
        assertEquals(18.0f, resNoTypo.ascent)
        assertEquals(5.0f, resNoTypo.descent)
        assertEquals(FontMetricsPolicy.Raw, resNoTypo.policy)

        // 5. LatinText
        val inputLatin = FontMetricsNormalizationInput(
            request = baseRequest.copy(role = FontRole.LatinText),
            rawMetrics = RawFontMetrics(ascent = 13.0f, descent = 3.0f),
        )
        val resLatin = normalizer.normalize(inputLatin)
        assertEquals(13.0f, resLatin.ascent)
        assertEquals(3.0f, resLatin.descent)
        assertEquals(FontMetricsPolicy.Raw, resLatin.policy)
        assertEquals(BaselinePolicy.Alphabetic, resLatin.baselinePolicy)

        // 6. Symbol / Emoji / Unknown
        val inputSymbol = FontMetricsNormalizationInput(
            request = baseRequest.copy(role = FontRole.Symbol),
            rawMetrics = RawFontMetrics(ascent = 14.0f, descent = 4.0f),
        )
        val resSymbol = normalizer.normalize(inputSymbol)
        assertEquals(14.0f, resSymbol.ascent)
        assertEquals(FontMetricsPolicy.Raw, resSymbol.policy)

        assertEquals(inputWithTypo, inputWithTypo.copy())
        assertTrue(inputWithTypo.hashCode() == inputWithTypo.copy().hashCode())
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
