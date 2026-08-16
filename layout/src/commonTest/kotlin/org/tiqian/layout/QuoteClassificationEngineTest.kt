package org.tiqian.layout

import org.tiqian.core.Ic

import org.tiqian.clreq.CjkPunctuationGlyphPolicy
import org.tiqian.clreq.ClreqProfile
import org.tiqian.clreq.ClreqProfileResolver
import org.tiqian.clreq.LineAdjustmentStrategy
import org.tiqian.core.Cluster
import org.tiqian.core.Glyph
import org.tiqian.core.GlyphRun
import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LayoutResult
import org.tiqian.core.LineBreakPolicy
import org.tiqian.core.LineBreakSpan
import org.tiqian.core.LineLengthGrid
import org.tiqian.core.LineEndReason
import org.tiqian.linebreak.Hyphenator
import org.tiqian.linebreak.NoHyphenator
import org.tiqian.core.LayoutInput
import org.tiqian.core.ParagraphStyle
import org.tiqian.core.Rect
import org.tiqian.core.TextRange
import org.tiqian.core.TextSpan
import org.tiqian.core.TextStyle
import org.tiqian.core.TiqianTextContent
import org.tiqian.core.positionedClusters
import org.tiqian.font.FontRole
import org.tiqian.shaping.ExplainableStubTextShaper
import org.tiqian.shaping.ShapingInput
import org.tiqian.shaping.ShapingResult
import org.tiqian.shaping.TextShaper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QuoteClassificationEngineTest {
    @Test
    fun keepsLatinTechnicalPunctuationInLatinRun() {
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("well-known/path"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        // Technical punctuation stays in the Latin run (latin-primary, not the
        // symbol fallback). CY/T §9.3 splits the compound at its '-' so it can
        // wrap there, but every piece is still Latin.
        assertEquals("well-known/path", result.clusters.joinToString("") { it.text })
        assertTrue(result.clusters.all { it.fontKey == "latin-primary" })
        assertTrue(result.clusters.any { it.text == "well-" }) // split at the existing hyphen
    }

    @Test
    fun classifiesAsciiBracketsAsLatinRegardlessOfSurroundingContext() {
        // ASCII parens/brackets do NOT share a code point with CJK fullwidth
        // forms (（）「」 etc), so they are always Latin by typed intent.
        // (English) joins the surrounding Latin run and renders in latin font;
        // the CJK text on either side is unaffected.
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("中文(English)中文"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        val latinCluster = result.clusters.single { it.text == "(English)" }
        assertEquals("latin-primary", latinCluster.fontKey)
        assertEquals(
            "LatinText",
            result.debug.fontDecisions.single { it.sourceText == "(English)" }.role,
        )
    }

    @Test
    fun classifiesAsciiBracketsAsLatinInsidePureCjkContent() {
        // Even with CJK on both sides AND inside, ASCII brackets stay Latin —
        // the author chose ASCII; if they wanted fullwidth they would type
        // U+FF08/FF09 (which is already CjkPunctuation by code point).
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("中文(中文)"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        val openParen = result.clusters.single { it.text == "(" }
        val closeParen = result.clusters.single { it.text == ")" }
        assertEquals("latin-primary", openParen.fontKey)
        assertEquals("latin-primary", closeParen.fontKey)
    }

    @Test
    fun asciiClosingBracketWithCjkInteriorIsForbiddenAtLineStart() {
        val text = "如今已占据超七成份额(国产品牌)，互联网大厂排队抢购？"
        val result = ExplainableStubParagraphLayoutEngine(
            lineBreaker = LookaheadLineBreaker(),
        ).layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent(text),
                constraints = LayoutConstraints(maxWidth = 232f),
            ),
        )

        val debugLines = result.lines.joinToString(separator = "\n") { line ->
            val source = text.substring(line.range.start, line.range.end)
            "${line.clusterRange} ${line.range} ${line.endReason} \"$source\""
        }
        assertTrue(
            result.lines.none { line -> text.substring(line.range.start, line.range.end).startsWith(")") },
            debugLines,
        )
        val closeParen = result.clusters.single { it.text == ")" }
        assertEquals("latin-primary", closeParen.fontKey)
    }

    @Test
    fun asciiOpeningBracketWithCjkInteriorIsForbiddenAtLineEnd() {
        val text = "如今已占据超七成份额(国产品牌)，互联网大厂排队抢购？"
        val result = ExplainableStubParagraphLayoutEngine(
            lineBreaker = LookaheadLineBreaker(),
        ).layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent(text),
                constraints = LayoutConstraints(maxWidth = 168f),
            ),
        )

        val debugLines = result.lines.joinToString(separator = "\n") { line ->
            val source = text.substring(line.range.start, line.range.end)
            "${line.clusterRange} ${line.range} ${line.endReason} \"$source\""
        }
        assertTrue(
            result.lines.none { line -> text.substring(line.range.start, line.range.end).endsWith("(") },
            debugLines,
        )
        val openParen = result.clusters.single { it.text == "(" }
        assertEquals("latin-primary", openParen.fontKey)
    }

    @Test
    fun keepsTextStartLatinQuotePairInLatinRun() {
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("“Hello” world"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        // With U+0020 classified as Latin (ADR 0009) the run is one font
        // decision; LatinWordSegmentation then splits it into word/space
        // clusters, all still latin-primary.
        assertEquals(3, result.clusters.size)
        val quoted = result.clusters.first()
        assertEquals("“Hello”", quoted.text)
        assertEquals("latin-primary", quoted.fontKey)
        assertTrue(
            result.debug.fontDecisions.any {
                it.sourceText == "“Hello” world" && it.role == FontRole.LatinText.name
            },
        )
    }

    @Test
    fun mixedQuoteContextsReachTheFontAndPunctuationPipeline() {
        val text = "中“文”中；that’s；（如 ‘O’, ‘Q’）；他说：“She said ‘hello’.”"
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent(text),
                constraints = LayoutConstraints(maxWidth = 1_000f),
            ),
        )

        val cjkQuoteIndices = setOf(
            text.indexOf('“'),
            text.indexOf('”'),
            text.lastIndexOf('“'),
            text.lastIndexOf('”'),
        )
        val allQuoteIndices = text.indices.filterTo(mutableSetOf()) { text[it].isCurlyQuoteForTest() }
        val latinQuoteIndices = allQuoteIndices - cjkQuoteIndices

        fun roleAt(index: Int): String = result.debug.fontDecisions
            .single { index >= it.range.start && index < it.range.end }
            .role

        assertTrue(cjkQuoteIndices.all { roleAt(it) == FontRole.CjkPunctuation.name })
        assertTrue(latinQuoteIndices.all { roleAt(it) == FontRole.LatinText.name })
        assertEquals(
            cjkQuoteIndices,
            result.debug.punctuationDecisions
                .filter { it.char.isCurlyQuoteForTest() }
                .mapTo(mutableSetOf()) { it.range.start },
        )
        assertEquals(
            allQuoteIndices.associateWith { if (it in cjkQuoteIndices) "CjkPunctuation" else "LatinText" },
            result.debug.roleOverrides.associate { it.range.start to it.overriddenRole },
        )
        assertEquals(text, result.input.content.text)
    }

    @Test
    fun quoteRolesSurviveStyleAndSourceBoundaries() {
        val text = "中‘that’s’中"
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent(
                    text = text,
                    spans = listOf(
                        TextSpan(TextRange(2, 7), TextStyle(fontWeight = 700)),
                    ),
                    sourceBoundaries = setOf(1, 2, 6, 7, 8, 9),
                ),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        val rolesByIndex = result.debug.roleOverrides.associate { it.range.start to it.overriddenRole }
        assertEquals("CjkPunctuation", rolesByIndex[1])
        assertEquals("LatinText", rolesByIndex[6])
        assertEquals("CjkPunctuation", rolesByIndex[8])
        assertEquals("latin-primary", result.clusters.single { it.range.start == 6 }.fontKey)
        assertEquals(text, result.clusters.joinToString(separator = "") { it.text })
    }

    @Test
    fun adjacentQuotedListItemsKeepCjkQuoteGeometryAcrossMixedContent() {
        val texts = listOf(
            "便延伸出了“乃子”“大波”“大灯”“大雷”“大扎”“对A”“波霸”这些词",
            "这些太直白了是吧，\n “欧派”“double”“double may”呢",
        )

        for (text in texts) {
            val result = ExplainableStubParagraphLayoutEngine().layout(
                LayoutInput(
                    paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                    content = TiqianTextContent(text),
                    constraints = LayoutConstraints(maxWidth = 1_000f),
                ),
            )
            val quoteIndices = text.indices.filterTo(mutableSetOf()) { text[it].isCurlyQuoteForTest() }

            assertEquals(
                quoteIndices,
                result.debug.fontDecisions
                    .filter { it.role == FontRole.CjkPunctuation.name && it.range.start in quoteIndices }
                    .mapTo(mutableSetOf()) { it.range.start },
                text,
            )
            assertEquals(
                quoteIndices,
                result.debug.punctuationDecisions
                    .filter { it.char.isCurlyQuoteForTest() }
                    .mapTo(mutableSetOf()) { it.range.start },
                text,
            )
            val finalQuoteIndices = setOf(text.lastIndexOf('“'), text.lastIndexOf('”'))
            val finalQuoteOverrides = result.debug.roleOverrides
                .filter { it.range.start in finalQuoteIndices }
            assertEquals(finalQuoteIndices, finalQuoteOverrides.mapTo(mutableSetOf()) { it.range.start }, text)
            assertTrue(
                finalQuoteOverrides.all { it.source == "PairedPunctuationOuterScriptContext" },
                text,
            )
            assertEquals(text, result.input.content.text)
        }
    }

    @Test
    fun mi10sAdjacentLatinTranscriptionsKeepTheFinalQuotePairInCjkContext() {
        val text = "所以这个和 “骑ji” “说shui”“斜xiá”不一样，港台是从众的，大陆读音大多数源自韵书。"
        val result = ExplainableStubParagraphLayoutEngine(
            lineBreaker = LookaheadLineBreaker(),
            hyphenator = NoHyphenator,
        ).layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic.Zero),
                content = TiqianTextContent(text),
                constraints = LayoutConstraints(maxWidth = 160f),
            ),
        )

        val finalOpen = text.lastIndexOf('“')
        val finalClose = text.lastIndexOf('”')
        val finalOverrides = result.debug.roleOverrides.filter { it.range.start == finalOpen || it.range.start == finalClose }
        assertEquals(setOf(finalOpen, finalClose), finalOverrides.mapTo(mutableSetOf()) { it.range.start })
        assertTrue(finalOverrides.all { it.overriddenRole == FontRole.CjkPunctuation.name })
        assertTrue(finalOverrides.all { it.source == "PairedPunctuationOuterScriptContext" })
        assertTrue(
            result.lines.none { line -> text.substring(line.range.start, line.range.end).startsWith('”') },
            result.lines.joinToString { line -> text.substring(line.range.start, line.range.end) },
        )
    }

    @Test
    fun skipsNeutralDashBeforeLatinQuotePairInLayout() {
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("English — “hello”"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        // With autospace, the trailing space before — joins "English", and
        // the leading space after — joins " “hello”". The em-dash sits between
        // these two Latin clusters as a CJK punctuation cluster of its own.
        val quoted = result.clusters.first { it.text.contains("“hello”") }
        assertEquals("latin-primary", quoted.fontKey)
    }

    @Test
    fun keepsSlashLedLatinTechnicalRunOutOfCjkPunctuationGeometry() {
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("恐跨/TERFism。如果"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        val latinRun = result.debug.fontDecisions.single { it.sourceText == "/TERFism" }
        assertEquals(FontRole.LatinText.name, latinRun.role)
        assertTrue(result.debug.punctuationDecisions.none { it.range == latinRun.range })
        val cluster = result.clusters.single { it.text == "/TERFism" }
        assertEquals("latin-primary", cluster.fontKey)
        assertTrue(cluster.advance > 16f)
    }

    @Test
    fun recordsRoleOverridesForResolvedQuotePairs() {
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("“Hello” world"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        // QuotePair resolves both quotes to LatinText. Without the override the
        // standalone classifier would label "“" at position 0 as CjkPunctuation
        // (text boundary, no Latin context).
        val openQuoteOverride = result.debug.roleOverrides.firstOrNull { it.range.start == 0 }
        val closeQuoteOverride = result.debug.roleOverrides.firstOrNull { it.range.start == 6 }
        assertEquals("LatinText", openQuoteOverride?.overriddenRole)
        assertEquals("CjkPunctuation", openQuoteOverride?.originalRole)
        assertEquals("PairedPunctuationOuterScriptContext", openQuoteOverride?.source)
        assertEquals("LatinText", closeQuoteOverride?.overriddenRole)
    }

    @Test
    fun mixedChineseQuestionAtParagraphStartKeepsCjkQuoteGeometry() {
        val text = "“Json是谁？”"
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic.Zero),
                content = TiqianTextContent(text),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        val quoteIndices = setOf(0, text.lastIndex)
        val overrides = result.debug.roleOverrides.filter { it.range.start in quoteIndices }
        assertEquals(quoteIndices, overrides.mapTo(mutableSetOf()) { it.range.start })
        assertTrue(overrides.all { it.overriddenRole == FontRole.CjkPunctuation.name })
        assertTrue(overrides.all { it.source == "ParagraphLanguageQuoteContext" })
        assertEquals(
            quoteIndices,
            result.debug.punctuationDecisions
                .filter { it.char == '“' || it.char == '”' }
                .mapTo(mutableSetOf()) { it.range.start },
        )
        assertEquals(text, result.clusters.joinToString(separator = "") { it.text })
    }

    @Test
    fun keepsNumberedCjkQuotePairOnCjkFace() {
        val text = "1.\u201C\u4F60\u77E5\u9053\u674E\u767D\u662F\u600E\u4E48\u6B7B\u7684\u5417\uFF1F\u201D"
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent(text),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        val openQuote = result.debug.fontDecisions.single { it.range.start == 2 }
        assertEquals(FontRole.CjkPunctuation.name, openQuote.role)
        assertEquals("cjk-primary", openQuote.fontKey)

        val openQuoteOverride = result.debug.roleOverrides.single { it.range.start == 2 }
        assertEquals("PairedPunctuationContentScriptContext", openQuoteOverride.source)
        assertEquals("quoted-content-script", openQuoteOverride.reason)
        assertEquals(FontRole.CjkPunctuation.name, openQuoteOverride.overriddenRole)
    }

    @Test
    fun requestsFullWidthCjkQuotesAndSynthesizesTheCellWhenTheFontStaysProportional() {
        // MiSans-like metrics: curly quotes are proportional (0.375em) even
        // after `fwid`. Layout keeps the source glyph box intact while placing
        // that box on the correct side of a synthesized 1em punctuation cell.
        val engine = ExplainableStubParagraphLayoutEngine(
            textShaper = object : TextShaper {
                private val delegate = ExplainableStubTextShaper()

                override fun shape(input: ShapingInput): ShapingResult {
                    val result = delegate.shape(input)
                    if (input.displayText != "“" && input.displayText != "”") return result
                    assertEquals(listOf("fwid=1"), input.openTypeFeatures)
                    val advance = 6f
                    return result.copy(
                        clusters = result.clusters.map { it.copy(advance = advance) },
                        glyphRuns = result.glyphRuns.map { run ->
                            run.copy(
                                advance = advance,
                                glyphs = run.glyphs.map {
                                    it.copy(
                                        advance = advance,
                                        bounds = Rect(1f, -10f, 5f, 0f),
                                    )
                                },
                            )
                        },
                        decisions = result.decisions.map { it.copy(advance = advance) },
                    )
                }
            },
        )

        val result = engine.layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("中“文”中"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        val opening = result.clusters.single { it.text == "“" }
        val closing = result.clusters.single { it.text == "”" }
        assertEquals(16f, opening.advance)
        assertEquals(16f, closing.advance)
        assertEquals(10f, opening.glyphInlineShift)
        assertEquals(0f, closing.glyphInlineShift)

        val openingDecision = result.debug.punctuationDecisions.single { it.char == '“' }
        val closingDecision = result.debug.punctuationDecisions.single { it.char == '”' }
        assertEquals(10f, openingDecision.advanceExpansion)
        assertEquals("UnderwidthPunctuationFullWidthBoxPlacement", openingDecision.glyphPlacementReason)
        assertEquals(null, closingDecision.glyphPlacementReason)
        assertEquals("InkBoundsFittedBodyCompression", openingDecision.geometrySource)
        assertEquals("InkBoundsFittedBodyCompression", closingDecision.geometrySource)

        val positioned = result.positionedClusters().associateBy { it.range }
        assertEquals(
            positioned.getValue(opening.range).left + 10f,
            positioned.getValue(opening.range).drawX,
        )
        assertEquals(
            positioned.getValue(closing.range).left,
            positioned.getValue(closing.range).drawX,
        )

        val lineStart = engine.layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("“文"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )
        val lineStartQuote = lineStart.clusters.first()
        val lineStartPositioned = lineStart.positionedClusters()
        assertEquals(8f, lineStartQuote.advance)
        // Leading half-cell was removed; the 6px proportional box retains its
        // 1px internal bearing at x=2..8, and the following Han starts at x=8.
        assertEquals(2f, lineStartPositioned[0].drawX)
        assertEquals(8f, lineStartPositioned[1].left)
    }

    @Test
    fun leavesLatinContextCurlyQuotesOutsideCjkPunctuationGeometry() {
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("“Hello” world"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        assertTrue(result.debug.punctuationDecisions.none { it.char == '“' || it.char == '”' })
        assertTrue(result.clusters.all { it.glyphInlineShift == 0f })
    }

    @Test
    fun keepsContractionApostropheLatinInsideCjkSingleQuotes() {
        val text = "中‘that’s’中"
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent(text),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        val opening = result.debug.fontDecisions.single { it.range == TextRange(1, 2) }
        val contraction = result.debug.fontDecisions.single { it.range == TextRange(2, 8) }
        val closing = result.debug.fontDecisions.single { it.range == TextRange(8, 9) }
        assertEquals(FontRole.CjkPunctuation.name, opening.role)
        assertEquals(FontRole.LatinText.name, contraction.role)
        assertEquals("that’s", contraction.sourceText)
        assertEquals("latin-primary", contraction.fontKey)
        assertEquals(FontRole.CjkPunctuation.name, closing.role)

        val latinCluster = result.clusters.single { it.text == "that’s" }
        assertEquals("latin-primary", latinCluster.fontKey)
        assertTrue(result.debug.punctuationDecisions.none { it.range == TextRange(6, 7) })
    }
}

private fun Char.isCurlyQuoteForTest(): Boolean =
    this == '\u2018' || this == '\u2019' || this == '\u201C' || this == '\u201D'
