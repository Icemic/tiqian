package org.tiqian.layout

import org.tiqian.clreq.ClreqProfile
import org.tiqian.clreq.ClreqPunctuationGlyphSubstitutor
import org.tiqian.core.BreakOpportunityDecisionInfo
import org.tiqian.core.Cluster
import org.tiqian.core.EmergencyTrackingEligibilityDecisionInfo
import org.tiqian.core.Glyph
import org.tiqian.core.GlyphRun
import org.tiqian.core.InlineObjectSpan
import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LayoutInput
import org.tiqian.core.LineBreakPolicy
import org.tiqian.core.LineBreakSpan
import org.tiqian.core.Rect
import org.tiqian.core.ShapingDecisionInfo
import org.tiqian.core.TextRange
import org.tiqian.core.TextSpan
import org.tiqian.core.TextStyle
import org.tiqian.core.TiqianTextContent
import org.tiqian.font.FontCandidate
import org.tiqian.font.FontDecision
import org.tiqian.font.FontRole
import org.tiqian.linebreak.Hyphenator
import org.tiqian.shaping.ExplainableStubTextShaper
import org.tiqian.shaping.ShapingInput
import org.tiqian.shaping.ShapingResult
import org.tiqian.shaping.TextShaper
import org.tiqian.shaping.UNVERIFIED_DISPLAY_SUBSTITUTION_COVERAGE_ISSUE
import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.assertFalse
import org.tiqian.test.trace.assertNotNull
import org.tiqian.test.trace.assertTrue
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

class ParagraphShapingStageCoverageTest {
    private val testTrace = TestTraceRecorder("ParagraphShapingStageCoverageTest")


    @Test
    fun dashSubstitutionRollbackAndCoverageBranches() {
        testTrace.section("dashSubstitutionRollbackAndCoverageBranches")
        // 1. Deficient dash ink coverage (< 85% of 2em)
        val deficientShaper = object : TextShaper {
            override fun shape(input: ShapingInput): ShapingResult {
                val cluster = Cluster(
                    range = input.range,
                    text = input.text.substring(input.range.start, input.range.end),
                    displayText = input.displayText,
                    fontKey = "test",
                    advance = 32.0f,
                )
                val glyph = Glyph(
                    id = 1u,
                    clusterRange = input.range,
                    advance = 32.0f,
                    x = 0.0f,
                    bounds = Rect(left = 0.0f, top = 0.0f, right = 20.0f, bottom = 10.0f), // 20.0 < 32.0 * 0.85 (27.2)
                )
                return ShapingResult(
                    clusters = listOf(cluster),
                    glyphRuns = listOf(GlyphRun(range = input.range, fontKey = "test", glyphs = listOf(glyph), advance = 32.0f)),
                )
            }
        }
        val engine1 = ExplainableStubParagraphLayoutEngine(textShaper = deficientShaper)
        val input1 = LayoutInput(
            content = TiqianTextContent("——"),
            constraints = LayoutConstraints(maxWidth = 300.0f),
        )
        val result1 = engine1.layout(input1)
        assertNotNull(result1)

        // 2. Sufficient dash ink coverage (>= 85% of 2em)
        val sufficientShaper = object : TextShaper {
            override fun shape(input: ShapingInput): ShapingResult {
                val cluster = Cluster(
                    range = input.range,
                    text = input.text.substring(input.range.start, input.range.end),
                    displayText = input.displayText,
                    fontKey = "test",
                    advance = 32.0f,
                )
                val glyph = Glyph(
                    id = 1u,
                    clusterRange = input.range,
                    advance = 32.0f,
                    x = 0.0f,
                    bounds = Rect(left = 0.0f, top = 0.0f, right = 30.0f, bottom = 10.0f), // 30.0 >= 27.2
                )
                return ShapingResult(
                    clusters = listOf(cluster),
                    glyphRuns = listOf(GlyphRun(range = input.range, fontKey = "test", glyphs = listOf(glyph), advance = 32.0f)),
                )
            }
        }
        val engine2 = ExplainableStubParagraphLayoutEngine(textShaper = sufficientShaper)
        val input2 = LayoutInput(
            content = TiqianTextContent("——"),
            constraints = LayoutConstraints(maxWidth = 300.0f),
        )
        val result2 = engine2.layout(input2)
        assertNotNull(result2)

        // 3. Unverified display substitution and missing glyph rollback
        val rollbackShaper = object : TextShaper {
            var call = 0
            override fun shape(input: ShapingInput): ShapingResult {
                call += 1
                val cluster = Cluster(
                    range = input.range,
                    text = input.text.substring(input.range.start, input.range.end),
                    displayText = input.displayText,
                    fontKey = "test",
                    advance = 16.0f,
                )
                val decision = ShapingDecisionInfo(
                    range = input.range,
                    sourceText = input.text.substring(input.range.start, input.range.end),
                    displayText = input.displayText,
                    fontKey = "test",
                    glyphCount = 1,
                    advance = 16.0f,
                    source = "Test",
                    reason = "test",
                    capabilityIssue = if (call == 1) UNVERIFIED_DISPLAY_SUBSTITUTION_COVERAGE_ISSUE else null,
                    missingGlyphs = if (call == 2) 1 else 0,
                )
                return ShapingResult(
                    clusters = listOf(cluster),
                    glyphRuns = listOf(GlyphRun(range = input.range, fontKey = "test", glyphs = emptyList(), advance = 16.0f)),
                    decisions = listOf(decision),
                )
            }
        }
        val engine3 = ExplainableStubParagraphLayoutEngine(textShaper = rollbackShaper)
        val input3 = LayoutInput(
            content = TiqianTextContent("……"),
            constraints = LayoutConstraints(maxWidth = 300.0f),
        )
        val result3 = engine3.layout(input3)
        assertNotNull(result3)

        // 4. Dash with zero glyphs, multiple glyphs, and null bounds
        val multiAndNullGlyphShaper = object : TextShaper {
            var count = 0
            override fun shape(input: ShapingInput): ShapingResult {
                count += 1
                val cluster = Cluster(
                    range = input.range,
                    text = input.text.substring(input.range.start, input.range.end),
                    displayText = input.displayText,
                    fontKey = "test",
                    advance = 32.0f,
                )
                val glyphs = when (count % 3) {
                    0 -> emptyList()
                    1 -> listOf(
                        Glyph(id = 1u, clusterRange = input.range, advance = 16.0f, x = 0.0f, bounds = null),
                        Glyph(id = 2u, clusterRange = input.range, advance = 16.0f, x = 16.0f, bounds = null),
                    )
                    else -> listOf(
                        Glyph(id = 1u, clusterRange = input.range, advance = 32.0f, x = 0.0f, bounds = null),
                    )
                }
                return ShapingResult(
                    clusters = listOf(cluster),
                    glyphRuns = listOf(GlyphRun(range = input.range, fontKey = "test", glyphs = glyphs, advance = 32.0f)),
                )
            }
        }
        val engine4 = ExplainableStubParagraphLayoutEngine(textShaper = multiAndNullGlyphShaper)
        for (i in 0..3) {
            val res = engine4.layout(
                LayoutInput(
                    content = TiqianTextContent("——"),
                    constraints = LayoutConstraints(maxWidth = 300.0f),
                ),
            )
            assertNotNull(res)
        }
    }

    @Test
    fun latinSegmentationAndCutsBranches() {
        testTrace.section("latinSegmentationAndCutsBranches")
        val customHyphenator = object : Hyphenator {
            override fun hyphenate(word: String): List<Int> {
                return if (word.contains("hyphen")) {
                    listOf(2, 4)
                } else {
                    emptyList()
                }
            }
        }
        val engine = ExplainableStubParagraphLayoutEngine(hyphenator = customHyphenator)
        // 1. Point mark prefix e.g. ",Hello", camelCase, alpha-numeric, solidus, URLs, domain-like dots, long all caps
        val text = "Text with ,Hello Machine2Machine XMLHttp HTTPServer TeX/LaTeX /start end/ /a a/ a/b https://example.com/path www.test.org sub.domain.co .com a. a..b a.b --.com test.-com test.c test.123 test.co123 12(3):45 12(3):45. 12(3):45-50 12(3):45\u201350 12(3):45\u201450 (1):2 a(1):2 1():2 1(2)a:3 1(2): 1(2):a-b 1(2):-5 1(2):5- 1(2):a 12():34 12(34): a(b):c-d 12(3):. 12a(3):45 12(3a):45 12(3):-45 12(3):45- 12(3):45-6a 12(3):4a-65 12(3):abc hyphenatedword VERYLONGALLCAPSWORDTHATISNOTANABBREVIATIONANDSHOULDBEOPAQ"
        val input = LayoutInput(
            content = TiqianTextContent(text),
            constraints = LayoutConstraints(maxWidth = 80.0f),
        )
        val result = engine.layout(input)
        assertNotNull(result)

        // 2. Over-measure forced hyphen cut with short and long pieces (lo <= hi and lo > hi)
        val longWord = "antidisestablishmentarianism abc def xyz"
        val inputLong = LayoutInput(
            content = TiqianTextContent(longWord),
            constraints = LayoutConstraints(maxWidth = 30.0f),
        )
        val resultLong = engine.layout(inputLong)
        assertNotNull(resultLong)

        // 3. Existing hyphen cuts (>= 2 letters each side vs < 2 letters)
        val hyphens = "semi-conductor co-19 a-b 3-4 COVID-19 cross-module-link"
        val inputHyphens = LayoutInput(
            content = TiqianTextContent(hyphens),
            constraints = LayoutConstraints(maxWidth = 80.0f),
        )
        val resultHyphens = engine.layout(inputHyphens)
        assertNotNull(resultHyphens)

        // 4. Strong non-lexical reason strings: repeated letters, hex identity, mixed alpha-numeric
        val nonLexical = "aaaaaaaaaaaaaaaa 0123456789abcdef a1b2c3d4e5f6g7h8 aaaaaa111111 aaaaaaaaaaaa1 a1"
        val inputNonLexical = LayoutInput(
            content = TiqianTextContent(nonLexical),
            constraints = LayoutConstraints(maxWidth = 100.0f),
        )
        val resultNonLexical = engine.layout(inputNonLexical)
        assertNotNull(resultNonLexical)

        // 5. Short camel case bounds (e.g. aB, ABc, abC)
        val shortCamel = "aBc ABc abC myIdentifier XML fooBAR aBC XMLHTTP"
        val inputShortCamel = LayoutInput(
            content = TiqianTextContent(shortCamel),
            constraints = LayoutConstraints(maxWidth = 100.0f),
        )
        val resultShortCamel = engine.layout(inputShortCamel)
        assertNotNull(resultShortCamel)
    }

    @Test
    fun hyphenAdvanceFallbackWhenShaperReturnsEmptyClusters() {
        testTrace.section("hyphenAdvanceFallbackWhenShaperReturnsEmptyClusters")
        val noClusterHyphenShaper = object : TextShaper {
            val delegate = ExplainableStubTextShaper()
            override fun shape(input: ShapingInput): ShapingResult {
                if (input.text == "-" || input.displayText == "-") {
                    return ShapingResult(clusters = emptyList(), glyphRuns = emptyList())
                }
                return delegate.shape(input)
            }
        }
        val engine = ExplainableStubParagraphLayoutEngine(textShaper = noClusterHyphenShaper)
        val input = LayoutInput(
            content = TiqianTextContent("supercalifragilisticexpialidocious"),
            constraints = LayoutConstraints(maxWidth = 50.0f),
        )
        val result = engine.layout(input)
        assertNotNull(result)
    }

    @Test
    fun progressiveTechnicalSpanBreaksAndTiers() {
        testTrace.section("progressiveTechnicalSpanBreaksAndTiers")
        val customHyphenator = object : Hyphenator {
            override fun hyphenate(word: String): List<Int> =
                if (word.contains("Machine")) listOf(-1, 0, 3, word.length, word.length + 1) else listOf(2)
        }
        val engine = ExplainableStubParagraphLayoutEngine(hyphenator = customHyphenator)
        val text = "Machine2Machine /v2.0_alpha=beta&gamma supercalifragilisticexpialidocious short"
        val input = LayoutInput(
            content = TiqianTextContent(
                text = text,
                lineBreakSpans = listOf(
                    LineBreakSpan(range = TextRange(0, text.length), policy = LineBreakPolicy.ProgressiveTechnical),
                    LineBreakSpan(range = TextRange(5, 10), policy = LineBreakPolicy.ProgressiveTechnical),
                ),
            ),
            constraints = LayoutConstraints(maxWidth = 80.0f),
        )

        for (tier in listOf(ProgressiveBreakTier.Structural, ProgressiveBreakTier.Syllable, ProgressiveBreakTier.Emergency)) {
            val key = TextRange(0, text.length)
            val annotation = engine.prepareWidthIndependentAnnotation(input, mapOf(key to setOf(tier)))
            val prep = engine.buildParagraphLayoutPrep(input, annotation, mapOf(key to setOf(tier)))
            assertNotNull(prep)
        }

        // Test tier priority collisions
        val prepMultiTier = engine.buildParagraphLayoutPrep(
            input,
            engine.prepareWidthIndependentAnnotation(input, emptyMap()),
            mapOf(TextRange(0, text.length) to setOf(ProgressiveBreakTier.Structural, ProgressiveBreakTier.Syllable)),
        )
        assertNotNull(prepMultiTier)
    }

    @Test
    fun multiClusterShaperForWordCutsAndOpaqueHardCuts() {
        testTrace.section("multiClusterShaperForWordCutsAndOpaqueHardCuts")
        val multiClusterShaper = object : TextShaper {
            val delegate = ExplainableStubTextShaper()
            var toggle = false
            override fun shape(input: ShapingInput): ShapingResult {
                val res = delegate.shape(input)
                if (input.range.length <= 1) return res
                toggle = !toggle
                return if (toggle) {
                    val mid = (input.range.start + input.range.end) / 2
                    res.copy(
                        clusters = listOf(
                            Cluster(range = TextRange(input.range.start, mid), text = "a", displayText = "a", fontKey = "k", advance = 100.0f),
                            Cluster(range = TextRange(mid, input.range.end), text = "b", displayText = "b", fontKey = "k", advance = 100.0f),
                        ),
                    )
                } else {
                    res
                }
            }
        }
        val engine = ExplainableStubParagraphLayoutEngine(
            textShaper = multiClusterShaper,
            hyphenator = object : Hyphenator {
                override fun hyphenate(word: String): List<Int> = listOf(1, 2, 3)
            },
        )
        val input = LayoutInput(
            content = TiqianTextContent("antidisestablishmentarianism some_opaque_token_with_separators/and/more"),
            constraints = LayoutConstraints(maxWidth = 20.0f),
        )
        val result = engine.layout(input)
        assertNotNull(result)
    }

    @Test
    fun directShapeParagraphEdgeCases() {
        testTrace.section("directShapeParagraphEdgeCases")
        val customHyphenator = object : Hyphenator {
            override fun hyphenate(word: String): List<Int> =
                when (word) {
                    "abcdef" -> listOf(1)
                    "abcdeg" -> listOf(2)
                    "antidisestablishmentarianism" -> emptyList()
                    "Machine" -> listOf(-1, 0, 2, word.length, word.length + 2)
                    else -> listOf(2)
                }
        }
        val emptyClusterShaper = object : TextShaper {
            val delegate = ExplainableStubTextShaper()
            override fun shape(input: ShapingInput): ShapingResult {
                val res = delegate.shape(input)
                if (input.text == "singlecluster" || input.displayText == "singlecluster") {
                    return res.copy(clusters = emptyList())
                }
                return res
            }
        }
        val engine = ExplainableStubParagraphLayoutEngine(hyphenator = customHyphenator, textShaper = emptyClusterShaper)
        val text = "abcdef abcdeg antidisestablishmentarianism singlecluster Machine2Machine /a/b/c 12(3):. 12a(3):45 12(3a):45 12(3):-45 12(3):45- 12(3):45-6a 12(3):4a-65 12(3):abc aaaaaa111111 a1b2c3d4e5f6 http://example.com/foo https://example.com/foo?a=1&b=2#x%20~y abc.d abc.12 abc.de abc.de12 --.com foo.-bar /start end/ a/b a//b"
        val input = LayoutInput(
            content = TiqianTextContent(
                text = text,
                lineBreakSpans = listOf(
                    LineBreakSpan(range = TextRange(0, 10), policy = LineBreakPolicy.ProgressiveTechnical),
                ),
            ),
            constraints = LayoutConstraints(maxWidth = 1.0f),
        )
        val candidate = FontCandidate(key = "k", family = "f", role = FontRole.LatinText)
        val latinDecision = FontDecision(
            range = TextRange(0, text.length),
            role = FontRole.LatinText,
            candidate = candidate,
            reason = "r",
        )
        val cjkCandidate = FontCandidate(key = "k", family = "f", role = FontRole.CjkText)
        val cjkDecision = FontDecision(
            range = TextRange(0, text.length),
            role = FontRole.CjkText,
            candidate = cjkCandidate,
            reason = "r",
        )

        val substitutor = ClreqPunctuationGlyphSubstitutor()

        // 1. shapeParagraph with Latin decision and measure = 1.0 (triggers latinWordCuts with lo > hi and empty/non-empty ranges)
        val res1 = engine.shapeParagraph(
            input = input,
            text = text,
            fontSize = 16.0f,
            measure = 1.0f,
            clusterRanges = listOf(
                ResolvedClusterRange(range = TextRange(0, text.length), role = FontRole.LatinText, mandatoryBreak = false, zeroWidthSoftBreak = false),
            ),
            fontDecisionByRange = mapOf(TextRange(0, text.length) to latinDecision),
            inlineObjectByRange = emptyMap(),
            punctuationGlyphSubstitutor = substitutor,
            styleAt = { TextStyle(fontSize = 16.0f) },
            emphasisItalicAt = { true },
            rejectedTechnicalTiersBySpan = emptyMap(),
        )
        assertNotNull(res1)

        // 2. shapeParagraph with CjkDecision
        val res2 = engine.shapeParagraph(
            input = input,
            text = text,
            fontSize = 16.0f,
            measure = 40.0f,
            clusterRanges = listOf(
                ResolvedClusterRange(range = TextRange(0, text.length), role = FontRole.CjkText, mandatoryBreak = false, zeroWidthSoftBreak = false),
            ),
            fontDecisionByRange = mapOf(TextRange(0, text.length) to cjkDecision),
            inlineObjectByRange = emptyMap(),
            punctuationGlyphSubstitutor = substitutor,
            styleAt = { TextStyle(fontSize = 16.0f) },
            emphasisItalicAt = { false },
            rejectedTechnicalTiersBySpan = emptyMap(),
        )
        assertNotNull(res2)

        // 3. shapeParagraph with single space and single CJK character
        val spaceInput = LayoutInput(content = TiqianTextContent(" "), constraints = LayoutConstraints(maxWidth = 100.0f))
        val spaceLatinDecision = FontDecision(range = TextRange(0, 1), role = FontRole.LatinText, candidate = candidate, reason = "r")
        val resSpace = engine.shapeParagraph(
            input = spaceInput,
            text = " ",
            fontSize = 16.0f,
            measure = 100.0f,
            clusterRanges = listOf(
                ResolvedClusterRange(range = TextRange(0, 1), role = FontRole.LatinText, mandatoryBreak = false, zeroWidthSoftBreak = false),
            ),
            fontDecisionByRange = mapOf(TextRange(0, 1) to spaceLatinDecision),
            inlineObjectByRange = emptyMap(),
            punctuationGlyphSubstitutor = substitutor,
            styleAt = { TextStyle(fontSize = 16.0f) },
            emphasisItalicAt = { false },
            rejectedTechnicalTiersBySpan = emptyMap(),
        )
        assertNotNull(resSpace)
    }

    @Test
    fun mapToClusterRangeWithZeroAndPositiveAdvance() {
        testTrace.section("mapToClusterRangeWithZeroAndPositiveAdvance")
        val cluster = Cluster(range = TextRange(0, 4), text = "test", displayText = "test", fontKey = "k", advance = 20.0f)

        // 1. Glyphs with advance <= 0.0
        val zeroGlyphs = listOf(
            Glyph(id = 1u, clusterRange = TextRange(0, 2), advance = 0.0f, x = 0.0f),
            Glyph(id = 2u, clusterRange = TextRange(2, 4), advance = 0.0f, x = 0.0f),
        )
        val mappedZero = zeroGlyphs.mapToClusterRange(cluster)
        assertEquals(2, mappedZero.size)
        assertEquals(10.0f, mappedZero[0].advance)
        assertEquals(10.0f, mappedZero[1].advance)
        assertEquals(TextRange(0, 4), mappedZero[0].clusterRange)

        // 2. Glyphs with advance > 0.0
        val normalGlyphs = listOf(
            Glyph(id = 1u, clusterRange = TextRange(0, 2), advance = 8.0f, x = 0.0f),
            Glyph(id = 2u, clusterRange = TextRange(2, 4), advance = 12.0f, x = 8.0f),
        )
        val mappedNormal = normalGlyphs.mapToClusterRange(cluster)
        assertEquals(2, mappedNormal.size)
        assertEquals(8.0f, mappedNormal[0].advance)
        assertEquals(12.0f, mappedNormal[1].advance)
    }

    @Test
    fun clusterPredicatesAndCurlyQuoteFeatures() {
        testTrace.section("clusterPredicatesAndCurlyQuoteFeatures")
        val mandatoryCluster = Cluster(range = TextRange(0, 1), text = "\n", displayText = "", fontKey = "mandatory-break", advance = 0.0f)
        assertTrue(mandatoryCluster.isMandatoryBreakCluster())
        assertFalse(mandatoryCluster.isZeroWidthSoftBreakCluster())
        assertFalse(mandatoryCluster.isInlineObjectCluster())

        val zeroWidthCluster = Cluster(range = TextRange(0, 1), text = "\u200B", displayText = "", fontKey = "zero-width-space", advance = 0.0f)
        assertTrue(zeroWidthCluster.isZeroWidthSoftBreakCluster())
        assertFalse(zeroWidthCluster.isMandatoryBreakCluster())

        val inlineObjectCluster = Cluster(range = TextRange(0, 1), text = "x", displayText = "", fontKey = "inline-object", advance = 20.0f)
        assertTrue(inlineObjectCluster.isInlineObjectCluster())
        assertFalse(inlineObjectCluster.isMandatoryBreakCluster())

        val normalCluster = Cluster(range = TextRange(0, 1), text = "中", displayText = "中", fontKey = "font", advance = 16.0f)
        assertFalse(normalCluster.isMandatoryBreakCluster())
        assertFalse(normalCluster.isZeroWidthSoftBreakCluster())
        assertFalse(normalCluster.isInlineObjectCluster())

        val engine = ExplainableStubParagraphLayoutEngine()
        val quoteInput = LayoutInput(
            content = TiqianTextContent("“双引号”与‘单引号’"),
            constraints = LayoutConstraints(maxWidth = 300.0f),
        )
        val result = engine.layout(quoteInput)
        assertNotNull(result)
    }

    @Test
    fun latinWordCutsLoHiAndEmptyBranches() {
        testTrace.section("latinWordCutsLoHiAndEmptyBranches")
        val wordShaper = object : TextShaper {
            val delegate = ExplainableStubTextShaper()
            override fun shape(input: ShapingInput): ShapingResult {
                val res = delegate.shape(input)
                if (input.range.length == 2 && input.text.substring(input.range.start, input.range.end) == "em") {
                    val c1 = Cluster(range = TextRange(input.range.start, input.range.start + 1), text = "e", displayText = "e", fontKey = "k", advance = 10.0f)
                    val c2 = Cluster(range = TextRange(input.range.start + 1, input.range.end), text = "m", displayText = "m", fontKey = "k", advance = 10.0f)
                    return res.copy(clusters = listOf(c1, c2))
                }
                return res
            }
        }
        val customHyphenator = object : Hyphenator {
            override fun hyphenate(word: String): List<Int> =
                when (word) {
                    "abcdef" -> listOf(1) // a=0, b=1 (lo > hi, empty 1..0), a=1, b=6 (lo <= hi, 3..4)
                    "ghijkl" -> listOf(2) // a=0, b=2 (lo > hi, 1..1)
                    "mnopqr" -> listOf(3) // a=0, b=3 (lo > hi, 1..2)
                    "empty" -> listOf(2) // clusters.singleOrNull() == null
                    else -> emptyList()
                }
        }
        val engine = ExplainableStubParagraphLayoutEngine(hyphenator = customHyphenator, textShaper = wordShaper)
        val text = "abcdef ghijkl mnopqr empty"
        val input = LayoutInput(
            content = TiqianTextContent(text),
            constraints = LayoutConstraints(maxWidth = 1.0f), // very small measure so pieceAdvance > measure
        )
        val result = engine.layout(input)
        assertNotNull(result)
    }

    @Test
    fun latinSeparatorCutsAndSolidusBranches() {
        testTrace.section("latinSeparatorCutsAndSolidusBranches")
        val engine = ExplainableStubParagraphLayoutEngine()
        // 1. keepUrlScheme = true (measure large) vs keepUrlScheme = false (measure small)
        val text = "http://example.com/path a/b /start end/ a//b foo_bar"
        val inputLarge = LayoutInput(content = TiqianTextContent(text), constraints = LayoutConstraints(maxWidth = 500.0f))
        val resLarge = engine.layout(inputLarge)
        assertNotNull(resLarge)

        val inputSmall = LayoutInput(content = TiqianTextContent(text), constraints = LayoutConstraints(maxWidth = 1.0f))
        val resSmall = engine.layout(inputSmall)
        assertNotNull(resSmall)
    }

    @Test
    fun progressiveTechnicalTierPriorityAndFalseBranches() {
        testTrace.section("progressiveTechnicalTierPriorityAndFalseBranches")
        val customHyphenator = object : Hyphenator {
            override fun hyphenate(word: String): List<Int> =
                when (word) {
                    "abcdef" -> listOf(2, 4)
                    else -> listOf(-1, 0, 1, 2, word.length, word.length + 2)
                }
        }
        val engine = ExplainableStubParagraphLayoutEngine(hyphenator = customHyphenator)
        val substitutor = ClreqPunctuationGlyphSubstitutor()
        val candidate = FontCandidate(key = "k", family = "f", role = FontRole.LatinText)

        // 1. Line 656 & Line 637 branches:
        // Range 1 (0..7, "abcdef/") has technicalStructuralCuts=[7], technicalSyllableCuts=[2, 4].
        // With rejectedTiers containing Structural AND Syllable:
        // technicalEmergencyCuts gets both Structural(7) and Syllable(2, 4).
        // For Syllable(2, 4) -> rejected, skipped.
        // For Emergency(4) -> offset 2, 4, 7:
        // When offset 7 was previously recorded with Structural(1), Emergency(4) checks 4 < 1 (FALSE).
        // Range 2 (2..7, "cdef/") starts at 2 (non-whitespace before it -> boundaryTier = WholeToken priority 3).
        // At start 2: current is Emergency(4), WholeToken(3) checks 3 < 4 (TRUE -> overwrites).
        // Range 3 (2..7) starts at 2 with Structural(1) previously set -> 3 < 1 (FALSE).
        val text1 = "abcdef/ghijkl"
        val progSpan1 = LineBreakSpan(range = TextRange(0, 13), policy = LineBreakPolicy.ProgressiveTechnical)
        val input1 = LayoutInput(
            content = TiqianTextContent(
                text = text1,
                lineBreakSpans = listOf(
                    LineBreakSpan(range = TextRange(0, 2), policy = LineBreakPolicy.ProgressiveTechnical), // before
                    progSpan1,
                    LineBreakSpan(range = TextRange(10, 13), policy = LineBreakPolicy.ProgressiveTechnical), // after/partial
                ),
            ),
            constraints = LayoutConstraints(maxWidth = 10.0f),
        )
        val res1 = engine.shapeParagraph(
            input = input1,
            text = text1,
            fontSize = 16.0f,
            measure = 10.0f,
            clusterRanges = listOf(
                ResolvedClusterRange(range = TextRange(0, 7), role = FontRole.LatinText, mandatoryBreak = false, zeroWidthSoftBreak = false),
                ResolvedClusterRange(range = TextRange(2, 7), role = FontRole.LatinText, mandatoryBreak = false, zeroWidthSoftBreak = false),
                ResolvedClusterRange(range = TextRange(0, 0), role = FontRole.LatinText, mandatoryBreak = false, zeroWidthSoftBreak = false), // length == 0 (Line 477)
            ),
            fontDecisionByRange = mapOf(
                TextRange(0, 7) to FontDecision(range = TextRange(0, 7), role = FontRole.LatinText, candidate = candidate, reason = "r"),
                TextRange(2, 7) to FontDecision(range = TextRange(2, 7), role = FontRole.LatinText, candidate = candidate, reason = "r"),
                TextRange(0, 0) to FontDecision(range = TextRange(0, 0), role = FontRole.LatinText, candidate = candidate, reason = "r"),
            ),
            inlineObjectByRange = emptyMap(),
            punctuationGlyphSubstitutor = substitutor,
            styleAt = { TextStyle(fontSize = 16.0f) },
            emphasisItalicAt = { false },
            rejectedTechnicalTiersBySpan = mapOf(
                progSpan1.range to setOf(ProgressiveBreakTier.Structural, ProgressiveBreakTier.Syllable),
            ),
        )
        assertNotNull(res1)
    }

    @Test
    fun latinSeparatorCutsExhaustiveBranches() {
        testTrace.section("latinSeparatorCutsExhaustiveBranches")
        val customHyphenator = object : Hyphenator {
            override fun hyphenate(word: String): List<Int> =
                if (word == "hyphenated") listOf(3, 6) else emptyList()
        }
        val engine = ExplainableStubParagraphLayoutEngine(hyphenator = customHyphenator)
        // Bibliographic locators covering all separator variants: -, \u2013, \u2014, and invalid empty / non-digit parts
        // and Line 819 pages.isEmpty() after removeSuffix(".") -> "12(3):."
        val biblioText = "12(3):45-67 12(3):45\u201367 12(3):45\u201467 12(3):45 12(3):. 12():45 12(3): :(3):45 12(3):- 12(3):45- 12(3):4a-65 12(3):45-6a 12(3):abc " +
            "http://example.com/a/b/c https://test.org:8080/foo?bar=1&baz=2#frag%20~val+1*2|3;4,5.6-7_8 http:/test /a a/ a//b a/b " +
            "ABC CamelCase aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa hyphenated-word clean/solidus hyphenated"
        // Large measure (tokenAdvance <= measure, forceOpaqueBreaks = false)
        val inputLarge = LayoutInput(
            content = TiqianTextContent(biblioText),
            constraints = LayoutConstraints(maxWidth = 500.0f),
        )
        val resLarge = engine.layout(inputLarge)
        assertNotNull(resLarge)

        // Small measure (tokenAdvance > measure, forceOpaqueBreaks = true)
        val inputSmall = LayoutInput(
            content = TiqianTextContent(biblioText),
            constraints = LayoutConstraints(maxWidth = 10.0f),
        )
        val resSmall = engine.layout(inputSmall)
        assertNotNull(resSmall)
    }

    @Test
    fun progressiveTierLoopRevisitsOffsetsWithLowerPriorityTiers() {
        testTrace.section("progressiveTierLoopRevisitsOffsetsWithLowerPriorityTiers")
        val customHyphenator = object : Hyphenator {
            override fun hyphenate(word: String): List<Int> =
                when (word) {
                    "abcdef" -> listOf(2, 4)
                    "cdef" -> listOf(1)
                    else -> emptyList()
                }
        }
        val engine = ExplainableStubParagraphLayoutEngine(hyphenator = customHyphenator)
        val substitutor = ClreqPunctuationGlyphSubstitutor()
        val candidate = FontCandidate(key = "k", family = "f", role = FontRole.LatinText)
        val text = "abcdef/"
        // Segment 1 (0..7) stores Syllable cuts at 2/4, then over-measure
        // emergency cuts at 1/3/5/6. Segment 2 (2..7) computes its own
        // syllable cut at 3, where segment 1 stored Emergency (priority 4):
        // 2 < 4 overwrites, covering the loop's lower-priority revisit arm.
        val input = LayoutInput(
            content = TiqianTextContent(
                text = text,
                lineBreakSpans = listOf(
                    LineBreakSpan(range = TextRange(0, 7), policy = LineBreakPolicy.ProgressiveTechnical),
                ),
            ),
            constraints = LayoutConstraints(maxWidth = 4.0f),
        )
        val res = engine.shapeParagraph(
            input = input,
            text = text,
            fontSize = 16.0f,
            measure = 4.0f,
            clusterRanges = listOf(
                ResolvedClusterRange(range = TextRange(0, 7), role = FontRole.LatinText, mandatoryBreak = false, zeroWidthSoftBreak = false),
                ResolvedClusterRange(range = TextRange(2, 7), role = FontRole.LatinText, mandatoryBreak = false, zeroWidthSoftBreak = false),
            ),
            fontDecisionByRange = mapOf(
                TextRange(0, 7) to FontDecision(range = TextRange(0, 7), role = FontRole.LatinText, candidate = candidate, reason = "r"),
                TextRange(2, 7) to FontDecision(range = TextRange(2, 7), role = FontRole.LatinText, candidate = candidate, reason = "r"),
            ),
            inlineObjectByRange = emptyMap(),
            punctuationGlyphSubstitutor = substitutor,
            styleAt = { TextStyle(fontSize = 16.0f) },
            emphasisItalicAt = { false },
            rejectedTechnicalTiersBySpan = emptyMap(),
        )
        assertNotNull(res)
    }

    @Test
    fun latinSeparatorTokensCoverUrlLeadingSlashAndDashLocators() {
        testTrace.section("latinSeparatorTokensCoverUrlLeadingSlashAndDashLocators")
        val engine = ExplainableStubParagraphLayoutEngine()
        val substitutor = ClreqPunctuationGlyphSubstitutor()
        val candidate = FontCandidate(key = "k", family = "f", role = FontRole.LatinText)
        for (token in listOf("//example.com/a", "12(3):45–67", "12(3):45—67")) {
            val input = LayoutInput(
                content = TiqianTextContent(token),
                constraints = LayoutConstraints(maxWidth = 500.0f),
            )
            // The large measure keeps the URL scheme (so the '/' arm at index
            // 0 reads getOrNull(-1) = null); the small measure drops it.
            for (measure in listOf(500.0f, 8.0f)) {
                val res = engine.shapeParagraph(
                    input = input,
                    text = token,
                    fontSize = 16.0f,
                    measure = measure,
                    clusterRanges = listOf(
                        ResolvedClusterRange(
                            range = TextRange(0, token.length),
                            role = FontRole.LatinText,
                            mandatoryBreak = false,
                            zeroWidthSoftBreak = false,
                        ),
                    ),
                    fontDecisionByRange = mapOf(
                        TextRange(0, token.length) to FontDecision(
                            range = TextRange(0, token.length),
                            role = FontRole.LatinText,
                            candidate = candidate,
                            reason = "r",
                        ),
                    ),
                    inlineObjectByRange = emptyMap(),
                    punctuationGlyphSubstitutor = substitutor,
                    styleAt = { TextStyle(fontSize = 16.0f) },
                    emphasisItalicAt = { false },
                    rejectedTechnicalTiersBySpan = emptyMap(),
                )
                assertNotNull(res)
            }
        }
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
