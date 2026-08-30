package org.tiqian.layout

import org.tiqian.core.TextRange
import org.tiqian.font.CjkFontRoleClassifier
import org.tiqian.font.FontRole
import org.tiqian.font.FontRoleContext
import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.assertTrue
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

class QuotePairAnalyzerTest {
    private val testTrace = TestTraceRecorder("QuotePairAnalyzerTest")

    private val analyzer = QuotePairAnalyzer()
    private val classifier = CjkFontRoleClassifier()

    // -- Pair matching --

    @Test
    fun matchesDoubleQuotePair() {
        testTrace.section("matchesDoubleQuotePair")
        // 他说"你好"
        val pairs = analyzer.analyze("\u4ED6\u8BF4\u201C\u4F60\u597D\u201D")
        assertEquals(1, pairs.size)
        assertEquals(QuotePair(2, 5, QuoteType.Double), pairs[0])
    }

    @Test
    fun matchesSingleQuotePair() {
        testTrace.section("matchesSingleQuotePair")
        // 他说'你好'
        val pairs = analyzer.analyze("\u4ED6\u8BF4\u2018\u4F60\u597D\u2019")
        assertEquals(1, pairs.size)
        assertEquals(QuotePair(2, 5, QuoteType.Single), pairs[0])
    }

    @Test
    fun matchesNestedQuotePairs() {
        testTrace.section("matchesNestedQuotePairs")
        // 他说："她说'你好'。"
        val text = "\u4ED6\u8BF4\uFF1A\u201C\u5979\u8BF4\u2018\u4F60\u597D\u2019\u3002\u201D"
        val pairs = analyzer.analyze(text)
        assertEquals(2, pairs.size)
        // Inner pair matched first (stack order)
        assertTrue(pairs.any { it == QuotePair(6, 9, QuoteType.Single) })
        assertTrue(pairs.any { it == QuotePair(3, 11, QuoteType.Double) })
    }

    @Test
    fun unmatchedQuotesProduceNoPairs() {
        testTrace.section("unmatchedQuotesProduceNoPairs")
        // it's — unmatched right single quote (apostrophe)
        val pairs = analyzer.analyze("it\u2019s")
        assertEquals(0, pairs.size)
    }

    @Test
    fun contractionApostropheDoesNotCloseOuterSingleQuote() {
        testTrace.section("contractionApostropheDoesNotCloseOuterSingleQuote")
        val text = "\u2018that\u2019s\u2019"

        assertEquals(
            listOf(QuotePair(0, text.lastIndex, QuoteType.Single)),
            analyzer.analyze(text),
        )
    }

    @Test
    fun contractionInsideCjkSingleQuotesKeepsApostropheLatin() {
        testTrace.section("contractionInsideCjkSingleQuotesKeepsApostropheLatin")
        val text = "\u4E2D\u2018that\u2019s\u2019\u4E2D"
        val pairs = analyzer.analyze(text)
        val roles = analyzer.classifyPairs(text, pairs)

        assertEquals(FontRole.CjkPunctuation, roles[1])
        assertEquals(FontRole.CjkPunctuation, roles[text.lastIndex - 1])
        assertEquals(FontRole.LatinText, roles[6])
        assertEquals(FontRole.LatinText, classifier.classify(text, TextRange(6, 7)))
    }

    @Test
    fun inWordApostropheMatrixDoesNotConsumeOuterQuotePairs() {
        testTrace.section("inWordApostropheMatrixDoesNotConsumeOuterQuotePairs")
        for (word in listOf("that’s", "l’été", "rock’n’roll", "version2’s", "α’β", "а’б", "e\u0301’s")) {
            assertTrue(analyzer.analyze(word).isEmpty(), word)
            val decisions = analyzer.classifyQuoteRoles(word, emptyList())
            assertTrue(decisions.all { it.role == FontRole.LatinText }, "$word: $decisions")
            assertTrue(
                decisions.all { it.source == "NonCjkInWordApostrophe" },
                "$word: $decisions",
            )

            val quoted = "‘$word’"
            val pair = analyzer.analyze(quoted)
            assertEquals(listOf(QuotePair(0, quoted.lastIndex, QuoteType.Single)), pair, quoted)
            val roles = analyzer.classifyPairs(quoted, pair)
            assertEquals("L".repeat(quoted.count { it.isCurlyQuote() }), roles.toRoleSignature(quoted), quoted)
        }
    }

    @Test
    fun unmatchedCurlyQuotesUseDirectionalContext() {
        testTrace.section("unmatchedCurlyQuotesUseDirectionalContext")
        val cases = listOf(
            QuoteRoleCase("leading elision at text start", "’90s", "L"),
            QuoteRoleCase("leading elision after CJK and Western space", "中文 ’90s", "L"),
            QuoteRoleCase("trailing possessive", "James’ book", "L"),
            QuoteRoleCase("truncated Latin opening quote", "“Hello", "L"),
            QuoteRoleCase("truncated Latin closing quote", "Hello”", "L"),
            QuoteRoleCase("unspaced CJK opening quote", "中文“Hello", "C"),
            QuoteRoleCase("unmatched CJK closing quote", "中文”", "C"),
            QuoteRoleCase("context-free quote", "”", "C"),
        )

        for (case in cases) assertRoleSignature(case)
    }

    @Test
    fun mismatchedNestingLeavesQuotesUnmatched() {
        testTrace.section("mismatchedNestingLeavesQuotesUnmatched")
        // "hello' — double open, single close, no match
        val pairs = analyzer.analyze("\u201Chello\u2019")
        assertEquals(0, pairs.size)
    }

    // -- Pair classification --

    @Test
    fun classifiesPairAsCjkWhenOuterContextIsCjk() {
        testTrace.section("classifiesPairAsCjkWhenOuterContextIsCjk")
        // 他说"你好"
        val text = "\u4ED6\u8BF4\u201C\u4F60\u597D\u201D"
        val pairs = analyzer.analyze(text)
        val roles = analyzer.classifyPairs(text, pairs)
        assertEquals(FontRole.CjkPunctuation, roles[2]) // opening "
        assertEquals(FontRole.CjkPunctuation, roles[5]) // closing "
    }

    @Test
    fun classifiesPairAsLatinWhenOuterContextIsLatin() {
        testTrace.section("classifiesPairAsLatinWhenOuterContextIsLatin")
        // he said "hello" world
        val text = "he said \u201Chello\u201D world"
        val pairs = analyzer.analyze(text)
        val roles = analyzer.classifyPairs(text, pairs)
        assertEquals(FontRole.LatinText, roles[8])  // opening "
        assertEquals(FontRole.LatinText, roles[14]) // closing "
    }

    @Test
    fun classifiesBothQuotesAsCjkForCjkQuotedLatinContent() {
        testTrace.section("classifiesBothQuotesAsCjkForCjkQuotedLatinContent")
        // 他说"hello" — opening quote's outer context is CJK
        val text = "\u4ED6\u8BF4\u201Chello\u201D"
        val pairs = analyzer.analyze(text)
        val roles = analyzer.classifyPairs(text, pairs)
        assertEquals(FontRole.CjkPunctuation, roles[2]) // opening "
        assertEquals(FontRole.CjkPunctuation, roles[8]) // closing " — same as opening
    }

    @Test
    fun whitespaceDelimitedLatinQuotePairOverridesCjkOuterContext() {
        testTrace.section("whitespaceDelimitedLatinQuotePairOverridesCjkOuterContext")
        val text = "（如 ‘O’, ‘Q’）"
        val decisions = analyzer.classifyQuoteRoles(
            text,
            analyzer.analyze(text),
        )

        assertEquals(listOf(3, 5, 8, 10), decisions.map { it.index }.sorted())
        assertTrue(decisions.all { it.role == FontRole.LatinText }, decisions.toString())
        assertTrue(
            decisions.all { it.source == "DelimitedWesternQuotationRun" },
            decisions.toString(),
        )
    }

    @Test
    fun unspacedCjkQuotationOfLatinTextRemainsCjk() {
        testTrace.section("unspacedCjkQuotationOfLatinTextRemainsCjk")
        val text = "他说‘hello’"
        val roles = analyzer.classifyPairs(text, analyzer.analyze(text))

        assertEquals(FontRole.CjkPunctuation, roles[2])
        assertEquals(FontRole.CjkPunctuation, roles[text.lastIndex])
    }

    @Test
    fun adjacentQuotedListItemsDoNotUsePreviousItemContentAsOuterContext() {
        testTrace.section("adjacentQuotedListItemsDoNotUsePreviousItemContentAsOuterContext")
        val cases = listOf(
            QuoteRoleCase(
                "CJK list item after mixed-script item",
                "便延伸出了“乃子”“大波”“大灯”“大雷”“大扎”“对A”“波霸”这些词",
                "CCCCCCCCCCCCCC",
            ),
            QuoteRoleCase(
                "Latin list item after Latin item in CJK prose",
                "这些太直白了是吧，\n “欧派”“double”“double may”呢",
                "CCCCCC",
            ),
        )

        for (case in cases) assertRoleSignature(case)

        for (text in cases.map { it.text }) {
            val finalOpen = text.lastIndexOf('“')
            val finalClose = text.lastIndexOf('”')
            val finalPairDecisions = analyzer
                .classifyQuoteRoles(text, analyzer.analyze(text))
                .filter { it.index == finalOpen || it.index == finalClose }
            assertEquals(2, finalPairDecisions.size, text)
            assertTrue(
                finalPairDecisions.all { it.source == "PairedPunctuationOuterScriptContext" },
                "$text: $finalPairDecisions",
            )
        }
    }

    @Test
    fun spacedCjkQuotedContentRemainsCjk() {
        testTrace.section("spacedCjkQuotedContentRemainsCjk")
        val text = "他说 ‘你好’"
        val roles = analyzer.classifyPairs(text, analyzer.analyze(text))

        assertEquals(FontRole.CjkPunctuation, roles[3])
        assertEquals(FontRole.CjkPunctuation, roles[text.lastIndex])
    }

    @Test
    fun classifiesPairAsCjkAtTextBoundary() {
        testTrace.section("classifiesPairAsCjkAtTextBoundary")
        // "你好" — no outer context, defaults to CJK
        val text = "\u201C\u4F60\u597D\u201D"
        val pairs = analyzer.analyze(text)
        val roles = analyzer.classifyPairs(text, pairs)
        assertEquals(FontRole.CjkPunctuation, roles[0]) // opening "
        assertEquals(FontRole.CjkPunctuation, roles[3]) // closing "
    }

    @Test
    fun classifiesTextStartLatinPairFromQuotedContent() {
        testTrace.section("classifiesTextStartLatinPairFromQuotedContent")
        val text = "\u201CHello\u201D world"
        val pairs = analyzer.analyze(text)
        val roles = analyzer.classifyPairs(text, pairs)
        assertEquals(FontRole.LatinText, roles[0])
        assertEquals(FontRole.LatinText, roles[6])
    }

    @Test
    fun mixedChineseQuestionAtParagraphStartUsesParagraphLanguage() {
        testTrace.section("mixedChineseQuestionAtParagraphStartUsesParagraphLanguage")
        val text = "“Json是谁？”"
        val decisions = analyzer.classifyQuoteRoles(text, analyzer.analyze(text))

        assertEquals(listOf(0, text.lastIndex), decisions.map { it.index })
        assertTrue(decisions.all { it.role == FontRole.CjkPunctuation }, decisions.toString())
        assertTrue(decisions.all { it.source == "ParagraphLanguageQuoteContext" }, decisions.toString())
    }

    @Test
    fun explicitEnglishParagraphLanguageWinsForMixedQuotation() {
        testTrace.section("explicitEnglishParagraphLanguageWinsForMixedQuotation")
        val text = "“Json是谁？”"
        val decisions = analyzer.classifyQuoteRoles(
            text,
            analyzer.analyze(text),
            FontRoleContext(locale = "en"),
        )

        assertTrue(decisions.all { it.role == FontRole.LatinText }, decisions.toString())
        assertTrue(decisions.all { it.source == "ParagraphLanguageQuoteContext" }, decisions.toString())
    }

    @Test
    fun commonDigitsDoNotChooseTheQuoteRole() {
        testTrace.section("commonDigitsDoNotChooseTheQuoteRole")
        val text = "“2024”"
        val pairs = analyzer.analyze(text)

        val chinese = analyzer.classifyQuoteRoles(text, pairs)
        assertTrue(chinese.all { it.role == FontRole.CjkPunctuation }, chinese.toString())
        assertTrue(chinese.all { it.source == "ParagraphLanguageQuoteContext" }, chinese.toString())

        val english = analyzer.classifyQuoteRoles(text, pairs, FontRoleContext(locale = "en"))
        assertTrue(english.all { it.role == FontRole.LatinText }, english.toString())
        assertTrue(english.all { it.source == "ParagraphLanguageQuoteContext" }, english.toString())
    }

    @Test
    fun nonLatinWesternScriptsParticipateAsStrongScriptEvidence() {
        testTrace.section("nonLatinWesternScriptsParticipateAsStrongScriptEvidence")
        val cases = listOf(
            QuoteRoleCase("standalone Cyrillic quotation", "“Привет”", "LL"),
            QuoteRoleCase("mixed Greek and Chinese quotation", "“π是谁？”", "CC"),
            QuoteRoleCase("CJK prose quoting Cyrillic", "他说“Привет”", "CC"),
        )

        for (case in cases) assertRoleSignature(case)
    }

    @Test
    fun numberedCjkQuotePrefixUsesQuotedContent() {
        testTrace.section("numberedCjkQuotePrefixUsesQuotedContent")
        val text = "1.\u201C\u4F60\u77E5\u9053\u674E\u767D\u662F\u600E\u4E48\u6B7B\u7684\u5417\uFF1F\u201D"
        val pairs = analyzer.analyze(text)
        val decisions = analyzer.classifyQuoteRoles(text, pairs)

        assertEquals(FontRole.CjkPunctuation, decisions.single { it.index == 2 }.role)
        assertEquals(FontRole.CjkPunctuation, decisions.single { it.index == text.lastIndex }.role)
        assertEquals("PairedPunctuationContentScriptContext", decisions.single { it.index == 2 }.source)
    }

    @Test
    fun numberedLatinQuotePrefixStillUsesLatinContent() {
        testTrace.section("numberedLatinQuotePrefixStillUsesLatinContent")
        val text = "1.\u201CHello\u201D"
        val pairs = analyzer.analyze(text)
        val roles = analyzer.classifyPairs(text, pairs)

        assertEquals(FontRole.LatinText, roles[2])
        assertEquals(FontRole.LatinText, roles[8])
    }

    @Test
    fun classifiesNestedPairsByOutermostContext() {
        testTrace.section("classifiesNestedPairsByOutermostContext")
        // 他说："她说'你好'。"
        val text = "\u4ED6\u8BF4\uFF1A\u201C\u5979\u8BF4\u2018\u4F60\u597D\u2019\u3002\u201D"
        val pairs = analyzer.analyze(text)
        val roles = analyzer.classifyPairs(text, pairs)
        // Outer double quotes — left of " is ： (CJK punctuation)
        assertEquals(FontRole.CjkPunctuation, roles[3])
        assertEquals(FontRole.CjkPunctuation, roles[11])
        // Inner single quotes — skips " and ：, sees 说 (CJK text)
        assertEquals(FontRole.CjkPunctuation, roles[6])
        assertEquals(FontRole.CjkPunctuation, roles[9])
    }

    @Test
    fun classifiesLatinNestedQuotesByOuterContext() {
        testTrace.section("classifiesLatinNestedQuotesByOuterContext")
        // She said "he said 'hello' today" end
        val text = "She said \u201Che said \u2018hello\u2019 today\u201D end"
        val pairs = analyzer.analyze(text)
        val roles = analyzer.classifyPairs(text, pairs)
        // All quotes should be Latin
        for ((_, role) in roles) {
            assertEquals(FontRole.LatinText, role)
        }
    }

    @Test
    fun skipsAsciiPunctuationWhenResolvingContext() {
        testTrace.section("skipsAsciiPunctuationWhenResolvingContext")
        // English: "hello" — colon and space before quote
        val text = "English: \u201Chello\u201D"
        val pairs = analyzer.analyze(text)
        val roles = analyzer.classifyPairs(text, pairs)
        // : is Unknown, space is Unknown, but 'h' in "English" is Latin
        assertEquals(FontRole.LatinText, roles[9])  // opening "
        assertEquals(FontRole.LatinText, roles[15]) // closing "
    }

    @Test
    fun skipsNeutralDashWhenResolvingContext() {
        testTrace.section("skipsNeutralDashWhenResolvingContext")
        val text = "English \u2014 \u201Chello\u201D"
        val pairs = analyzer.analyze(text)
        val roles = analyzer.classifyPairs(text, pairs)
        assertEquals(FontRole.LatinText, roles[10])
        assertEquals(FontRole.LatinText, roles[16])
    }

    @Test
    fun endOfTextQuotePairClassifiedByOuterContext() {
        testTrace.section("endOfTextQuotePairClassifiedByOuterContext")
        // he said "hello"
        val text = "he said \u201Chello\u201D"
        val pairs = analyzer.analyze(text)
        val roles = analyzer.classifyPairs(text, pairs)
        assertEquals(FontRole.LatinText, roles[8])  // opening "
        assertEquals(FontRole.LatinText, roles[14]) // closing " at end of text
    }

    @Test
    fun representativeQuoteContextMatrixRemainsStable() {
        testTrace.section("representativeQuoteContextMatrixRemainsStable")
        val cases = listOf(
            QuoteRoleCase("Latin content at text start", "“Hello”", "LL"),
            QuoteRoleCase("CJK content at text start", "“你好”", "CC"),
            QuoteRoleCase("mixed Chinese question at text start", "“Json是谁？”", "CC"),
            QuoteRoleCase("Cyrillic content at text start", "“Привет”", "LL"),
            QuoteRoleCase("CJK prose quoting Latin", "他说“hello”", "CC"),
            QuoteRoleCase("Latin prose quoting CJK", "He said “你好”", "LL"),
            QuoteRoleCase("spaced Western initials in CJK", "（如 ‘O’, ‘Q’）", "LLLL"),
            QuoteRoleCase("spaced CJK quotation", "他说 ‘你好’", "CC"),
            QuoteRoleCase("empty pair before Latin", "“”English", "LL"),
            QuoteRoleCase("empty pair before CJK", "“”中文", "CC"),
            QuoteRoleCase("context-free empty pair", "“”", "CC"),
            QuoteRoleCase("numbered CJK quotation", "1.“中文”", "CC"),
            QuoteRoleCase("numbered Latin quotation", "1.“Hello”", "LL"),
            QuoteRoleCase("mixed CJK outer Latin inner", "他说：“She said ‘hello’.”", "CLLC"),
            QuoteRoleCase("mixed Latin outer CJK inner", "English “他说‘你好’” end", "LCCL"),
            QuoteRoleCase("CJK outer with contraction", "中文‘don’t’", "CLC"),
            QuoteRoleCase("spaced Latin outer with contraction", "中文 ‘don’t’", "LLL"),
            QuoteRoleCase("pair across mandatory break", "他说：“第一行\n第二行。”", "CC"),
            QuoteRoleCase("tab-delimited Western quote", "（如\t‘O’）", "LL"),
        )

        for (case in cases) assertRoleSignature(case)
    }

    @Test
    fun roleDecisionSourcesStayExplainableAcrossFallbackPaths() {
        testTrace.section("roleDecisionSourcesStayExplainableAcrossFallbackPaths")
        val cases = listOf(
            "“Hello”" to "PairedPunctuationContentScriptContext",
            "“Json是谁？”" to "ParagraphLanguageQuoteContext",
            "English—“Hello”" to "PairedPunctuationOuterScriptContext",
            "（如 ‘O’）" to "DelimitedWesternQuotationRun",
            "1.“中文”" to "PairedPunctuationContentScriptContext",
            "“”English" to "PairedPunctuationOuterScriptContext",
            "“”" to "ParagraphLanguageQuoteContext",
            "that’s" to "NonCjkInWordApostrophe",
            "中文 ’90s" to "DelimitedUnmatchedWesternQuote",
            "James’" to "UnmatchedQuoteSurroundingScriptContext",
            "’90s" to "UnmatchedQuoteSurroundingScriptContext",
            "”" to "ParagraphLanguageQuoteContext",
        )

        for ((text, expectedSource) in cases) {
            val decisions = analyzer.classifyQuoteRoles(text, analyzer.analyze(text))
            assertTrue(decisions.isNotEmpty(), text)
            assertTrue(decisions.all { it.source == expectedSource }, "$text: $decisions")
        }
    }

    private data class QuoteRoleCase(
        val label: String,
        val text: String,
        val expectedSignature: String,
    )

    private fun assertRoleSignature(case: QuoteRoleCase) {
        val roles = analyzer.classifyPairs(case.text, analyzer.analyze(case.text))
        assertEquals(case.expectedSignature, roles.toRoleSignature(case.text), case.label)
    }

    private fun Map<Int, FontRole>.toRoleSignature(text: String): String =
        text.indices
            .filter { text[it].isCurlyQuote() }
            .joinToString(separator = "") { index ->
                when (this[index]) {
                    FontRole.LatinText -> "L"
                    FontRole.CjkPunctuation -> "C"
                    else -> "?"
                }
            }

    private fun Char.isCurlyQuote(): Boolean =
        this == '\u2018' || this == '\u2019' || this == '\u201C' || this == '\u201D'

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
