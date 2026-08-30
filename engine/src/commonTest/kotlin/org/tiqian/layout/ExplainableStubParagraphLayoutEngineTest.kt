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
import org.tiqian.core.sourceGraphemeBoundaries
import org.tiqian.font.FontRole
import org.tiqian.shaping.ExplainableStubTextShaper
import org.tiqian.shaping.ShapingInput
import org.tiqian.shaping.ShapingResult
import org.tiqian.shaping.TextShaper
import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.assertFailsWith
import org.tiqian.test.trace.assertTrue
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

class ExplainableStubParagraphLayoutEngineTest {
    private val testTrace = TestTraceRecorder("ExplainableStubParagraphLayoutEngineTest")

    @Test
    fun returnsDebuggableSingleLineResult() {
        testTrace.section("returnsDebuggableSingleLineResult")
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("提椠"),
                constraints = LayoutConstraints(maxWidth = 240f),
            ),
        )

        assertEquals(2, result.clusters.size)
        assertEquals(1, result.lines.size)
        assertEquals("greedy", result.debug.lineDecisions.single().kind)
    }

    @Test
    fun recordsInjectedLineBreakerStrategyInDebugDecisions() {
        testTrace.section("recordsInjectedLineBreakerStrategyInDebugDecisions")
        val result = ExplainableStubParagraphLayoutEngine(
            lineBreaker = LookaheadLineBreaker(),
        ).layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("提椠"),
                constraints = LayoutConstraints(maxWidth = 240f),
            ),
        )

        assertEquals("lookahead", result.debug.lineDecisions.single().kind)
    }

    @Test
    fun mandatoryLineBreakClustersAreZeroWidthAndNotShaped() {
        testTrace.section("mandatoryLineBreakClustersAreZeroWidthAndNotShaped")
        val result = ExplainableStubParagraphLayoutEngine(
            lineBreaker = LookaheadLineBreaker(),
        ).layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("第一行\n第二行"),
                constraints = LayoutConstraints(maxWidth = 240f),
            ),
        )

        assertEquals(2, result.lines.size)
        assertEquals(LineEndReason.MandatoryBreak, result.lines[0].endReason)
        assertEquals(LineEndReason.ParagraphEnd, result.lines[1].endReason)
        val breakCluster = result.clusters.single { it.text == "\n" }
        assertEquals("", breakCluster.displayText)
        assertEquals(0f, breakCluster.advance)
        assertTrue(result.glyphRuns.flatMap { it.glyphs }.none { it.clusterRange == breakCluster.range })
        assertEquals(listOf(TextRange(0, 3), TextRange(4, 7)), result.glyphRuns.map { it.range })
        assertEquals(breakCluster.range, result.debug.mandatoryBreakDecisions.single().range)
    }

    @Test
    fun consecutiveMandatoryLineBreaksCreateOneEmptyLineBox() {
        testTrace.section("consecutiveMandatoryLineBreaksCreateOneEmptyLineBox")
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("第一行\n\n第二行"),
                constraints = LayoutConstraints(maxWidth = 240f),
            ),
        )

        assertEquals(3, result.lines.size)
        assertEquals(LineEndReason.MandatoryBreak, result.lines[0].endReason)
        assertEquals(LineEndReason.MandatoryBreak, result.lines[1].endReason)
        assertEquals(LineEndReason.ParagraphEnd, result.lines[2].endReason)
        val emptyLineCluster = result.clusters[result.lines[1].clusterRange.single()]
        assertEquals("\n", emptyLineCluster.text)
        assertEquals("", emptyLineCluster.displayText)
        assertEquals(0f, emptyLineCluster.advance)
        val lineHeight = result.debug.lineSpacingDecision?.resolvedHeight ?: error("line height missing")
        assertEquals(lineHeight, result.lines[1].bottom - result.lines[1].top, 0.001f)
        assertEquals(lineHeight, result.lines[1].baseline - result.lines[0].baseline, 0.001f)
        assertEquals(lineHeight, result.lines[2].baseline - result.lines[1].baseline, 0.001f)
    }

    @Test
    fun singleMandatoryBreakAfterWrappedLineDoesNotCreateEmptyLine() {
        testTrace.section("singleMandatoryBreakAfterWrappedLineDoesNotCreateEmptyLine")
        val text = "很久以前，曾经有一个名叫小红帽的孩子，生活在大森林的边上，" +
            "大森林里充满了濒临灭绝的猫头鹰和珍稀植物，如果有人愿意花时间研究它们，" +
            "就会发现癌症的治疗方法。\n小红帽和一位称为母亲的养育者一起生活"
        val result = ExplainableStubParagraphLayoutEngine(
            lineBreaker = LookaheadLineBreaker(),
        ).layout(
            LayoutInput(
                textStyle = TextStyle(fontSize = 48f),
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent(text),
                constraints = LayoutConstraints(maxWidth = 1200f),
            ),
        )

        val debugLines = result.lines.joinToString(separator = "\n") { line ->
            val source = text.substring(line.range.start, line.range.end).replace("\n", "\\n")
            "${line.clusterRange} ${line.range} ${line.endReason} \"$source\""
        }
        assertTrue(result.lines.size >= 4)
        assertTrue(
            result.lines.none { line -> text.substring(line.range.start, line.range.end) == "\n" },
            debugLines,
        )
        assertEquals(LineEndReason.MandatoryBreak, result.lines.first { it.range.end == text.indexOf('\n') + 1 }.endReason)
        val lineHeight = result.debug.lineSpacingDecision?.resolvedHeight ?: error("line height missing")
        val gaps = result.lines.zipWithNext { a, b -> b.baseline - a.baseline }
        gaps.forEach { gap ->
            assertEquals(lineHeight, gap, 0.001f)
        }
    }

    @Test
    fun crlfIsOneMandatoryBreakCluster() {
        testTrace.section("crlfIsOneMandatoryBreakCluster")
        val result = ExplainableStubParagraphLayoutEngine(
            lineBreaker = LookaheadLineBreaker(),
        ).layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("甲\r\n乙"),
                constraints = LayoutConstraints(maxWidth = 240f),
            ),
        )

        assertEquals(2, result.lines.size)
        val breakCluster = result.clusters.single { it.text == "\r\n" }
        assertEquals(1, result.debug.mandatoryBreakDecisions.size)
        assertEquals(1, breakCluster.range.start)
        assertEquals(3, breakCluster.range.end)
    }

    @Test
    fun consecutiveAndTrailingMandatoryBreaksPreserveBlankLines() {
        testTrace.section("consecutiveAndTrailingMandatoryBreaksPreserveBlankLines")
        val result = ExplainableStubParagraphLayoutEngine(
            lineBreaker = LookaheadLineBreaker(),
        ).layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("甲\n\n乙\n"),
                constraints = LayoutConstraints(maxWidth = 240f),
            ),
        )

        assertEquals(4, result.lines.size)
        assertEquals(LineEndReason.MandatoryBreak, result.lines[0].endReason)
        assertEquals(LineEndReason.MandatoryBreak, result.lines[1].endReason)
        assertEquals(LineEndReason.MandatoryBreak, result.lines[2].endReason)
        assertEquals(LineEndReason.ParagraphEnd, result.lines[3].endReason)
        assertEquals(0f, result.lines[1].visualWidth)
        assertEquals(TextRange(5, 5), result.lines[3].range)
    }

    @Test
    fun mandatoryBreakLineIsNotJustified() {
        testTrace.section("mandatoryBreakLineIsNotJustified")
        val result = ExplainableStubParagraphLayoutEngine(
            lineBreaker = LookaheadLineBreaker(),
        ).layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("短\n中文中文中文中文中文"),
                constraints = LayoutConstraints(maxWidth = 128f),
            ),
        )

        val mandatoryLine = result.lines.first()
        assertEquals(LineEndReason.MandatoryBreak, mandatoryLine.endReason)
        assertEquals(mandatoryLine.naturalWidth, mandatoryLine.adjustedWidth)
        assertTrue(result.debug.justificationDecisions.none { it.lineRange == mandatoryLine.range })
    }

    @Test
    fun rejectsShaperClustersThatDoNotCoverFontDecisionRange() {
        testTrace.section("rejectsShaperClustersThatDoNotCoverFontDecisionRange")
        val engine = ExplainableStubParagraphLayoutEngine(
            textShaper = object : TextShaper {
                override fun shape(input: ShapingInput): ShapingResult =
                    ShapingResult(clusters = emptyList(), glyphRuns = emptyList())
            },
        )

        assertFailsWith<IllegalArgumentException> {
            engine.layout(
                LayoutInput(
                    paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                    content = TiqianTextContent("提椠"),
                    constraints = LayoutConstraints(maxWidth = 240f),
                ),
            )
        }
    }

    @Test
    fun preservesShaperGlyphBoundsInLayoutGlyphRuns() {
        testTrace.section("preservesShaperGlyphBoundsInLayoutGlyphRuns")
        val shapedBounds = Rect(left = 1f, top = -10f, right = 12f, bottom = 2f)
        val engine = ExplainableStubParagraphLayoutEngine(
            textShaper = object : TextShaper {
                override fun shape(input: ShapingInput): ShapingResult =
                    ShapingResult(
                        clusters = listOf(
                            Cluster(
                                range = input.range,
                                text = input.text.substring(input.range.start, input.range.end),
                                displayText = input.displayText,
                                fontKey = input.fontDecision.candidate.key,
                                advance = 20f,
                            ),
                        ),
                        glyphRuns = listOf(
                            GlyphRun(
                                range = input.range,
                                fontKey = input.fontDecision.candidate.key,
                                glyphs = listOf(
                                    Glyph(
                                        id = 42u,
                                        clusterRange = input.range,
                                        advance = 20f,
                                        bounds = shapedBounds,
                                    ),
                                ),
                                advance = 20f,
                            ),
                        ),
                    )
            },
        )

        val result = engine.layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("A"),
                constraints = LayoutConstraints(maxWidth = 240f),
            ),
        )

        val glyph = result.glyphRuns.single().glyphs.single()
        assertEquals(42u, glyph.id)
        assertEquals(shapedBounds, glyph.bounds)
        assertEquals(20f, glyph.advance)
    }

    @Test
    fun recordsFallbackDecisionsPerCluster() {
        testTrace.section("recordsFallbackDecisionsPerCluster")
        val result = ExplainableStubParagraphLayoutEngine(hyphenator = NoHyphenator).layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("提椠……English——世界。"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        assertTrue(
            result.debug.fontDecisions.any {
                it.sourceText == "……" &&
                    it.displayText == "⋯⋯" &&
                    it.role == FontRole.CjkPunctuation.name &&
                    it.fontKey == "cjk-primary"
            },
        )
        assertTrue(
            result.debug.fontDecisions.any {
                it.sourceText == "——" &&
                    it.displayText == "⸺" &&
                    it.role == FontRole.CjkPunctuation.name &&
                    it.fontKey == "cjk-primary"
            },
        )
        assertTrue(
            result.debug.shapingDecisions.any {
                it.sourceText == "——" &&
                    it.displayText == "⸺" &&
                    it.advance == 32f &&
                    it.source == "Stub"
            },
        )
        assertTrue(
            result.debug.fontDecisions.any {
                it.sourceText == "English" &&
                    it.role == FontRole.LatinText.name &&
                    it.fontKey == "latin-primary"
            },
        )
        assertEquals("English", result.clusters.first { it.text == "English" }.text)
    }

    @Test
    fun combiningMarksStayInTheirBaseShapingRuns() {
        testTrace.section("combiningMarksStayInTheirBaseShapingRuns")
        val result = ExplainableStubParagraphLayoutEngine(hyphenator = NoHyphenator).layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("༎ຶ Ỏ̷"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        assertTrue(result.debug.shapingDecisions.any { it.sourceText == "༎ຶ" })
        assertTrue(result.debug.shapingDecisions.any { it.sourceText == "Ỏ̷" })
        assertTrue(result.debug.shapingDecisions.none { it.sourceText == "ຶ" || it.sourceText == "̷" })
    }

    @Test
    fun complexEmojiGraphemesStayAtomicAcrossGeometryOnlyBoundaries() {
        testTrace.section("complexEmojiGraphemesStayAtomicAcrossGeometryOnlyBoundaries")
        val text = "👩🏽‍💻"
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent(text, sourceBoundaries = setOf(2)),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        assertEquals(
            listOf(TextRange(0, text.length)),
            result.debug.fontDecisions.filter { it.role == FontRole.Emoji.name }.map { it.range },
        )
        assertEquals(listOf(text), result.debug.shapingDecisions.map { it.sourceText })
    }

    @Test
    fun complexEmojiSequencesReachTheShaperAsCompleteEmojiRanges() {
        testTrace.section("complexEmojiSequencesReachTheShaperAsCompleteEmojiRanges")
        val text = "前👩🏽‍💻后🇨🇳与1️⃣和❤️。"
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent(text),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        assertEquals(
            listOf("👩🏽‍💻", "🇨🇳", "1️⃣", "❤️"),
            result.debug.fontDecisions
                .filter { it.role == FontRole.Emoji.name }
                .map { it.sourceText },
        )
        assertEquals(
            listOf("👩🏽‍💻", "🇨🇳", "1️⃣", "❤️"),
            result.debug.shapingDecisions
                .filter { it.fontKey == "symbol-fallback" }
                .map { it.sourceText },
        )
    }

    @Test
    fun emojiRoleMatrixSeparatesSupportedSequencesFromAdjacentAndUnrelatedText() {
        testTrace.section("emojiRoleMatrixSeparatesSupportedSequencesFromAdjacentAndUnrelatedText")
        data class Case(
            val text: String,
            val expectedRoles: List<Pair<String, FontRole>>,
        )

        val cases = listOf(
            Case(
                text = "a1️⃣",
                expectedRoles = listOf("a" to FontRole.LatinText, "1️⃣" to FontRole.Emoji),
            ),
            Case(
                text = "1️⃣a",
                expectedRoles = listOf("1️⃣" to FontRole.Emoji, "a" to FontRole.LatinText),
            ),
            Case(
                text = "a😀中",
                expectedRoles = listOf("a" to FontRole.LatinText, "😀" to FontRole.Emoji, "中" to FontRole.CjkText),
            ),
            Case(
                text = "a❤️中",
                expectedRoles = listOf("a" to FontRole.LatinText, "❤️" to FontRole.Emoji, "中" to FontRole.CjkText),
            ),
            Case(
                text = "a©️中",
                expectedRoles = listOf("a" to FontRole.LatinText, "©️" to FontRole.Emoji, "中" to FontRole.CjkText),
            ),
            Case(
                text = "a⌚︎中",
                expectedRoles = listOf("a" to FontRole.LatinText, "⌚︎" to FontRole.Emoji, "中" to FontRole.CjkText),
            ),
            Case(
                text = "a1⃣中",
                expectedRoles = listOf("a" to FontRole.LatinText, "1⃣" to FontRole.Emoji, "中" to FontRole.CjkText),
            ),
            Case(
                text = "a👍🏽中",
                expectedRoles = listOf("a" to FontRole.LatinText, "👍🏽" to FontRole.Emoji, "中" to FontRole.CjkText),
            ),
            Case(
                text = "a👩🏽‍💻中",
                expectedRoles = listOf("a" to FontRole.LatinText, "👩🏽‍💻" to FontRole.Emoji, "中" to FontRole.CjkText),
            ),
            Case(
                text = "a🏳️‍⚧️中",
                expectedRoles = listOf("a" to FontRole.LatinText, "🏳️‍⚧️" to FontRole.Emoji, "中" to FontRole.CjkText),
            ),
            Case(
                text = "a🇨🇳中",
                expectedRoles = listOf("a" to FontRole.LatinText, "🇨🇳" to FontRole.Emoji, "中" to FontRole.CjkText),
            ),
            Case(
                text = "a🏴\uDB40\uDC67\uDB40\uDC62\uDB40\uDC65\uDB40\uDC6E\uDB40\uDC67\uDB40\uDC7F中",
                expectedRoles = listOf(
                    "a" to FontRole.LatinText,
                    "🏴\uDB40\uDC67\uDB40\uDC62\uDB40\uDC65\uDB40\uDC6E\uDB40\uDC67\uDB40\uDC7F" to FontRole.Emoji,
                    "中" to FontRole.CjkText,
                ),
            ),
            Case(
                text = "中\uFE0F",
                expectedRoles = listOf("中\uFE0F" to FontRole.CjkText),
            ),
            Case(
                text = "a\uFE0F",
                expectedRoles = listOf("a\uFE0F" to FontRole.LatinText),
            ),
            Case(
                text = "a⃣中",
                expectedRoles = listOf("a⃣" to FontRole.LatinText, "中" to FontRole.CjkText),
            ),
            Case(
                text = "a1\uFE0F中",
                expectedRoles = listOf("a1\uFE0F" to FontRole.LatinText, "中" to FontRole.CjkText),
            ),
            Case(
                text = "中🏽",
                expectedRoles = listOf("中" to FontRole.CjkText, "🏽" to FontRole.Emoji),
            ),
            Case(
                text = "a👩‍中",
                expectedRoles = listOf("a" to FontRole.LatinText, "👩‍" to FontRole.Emoji, "中" to FontRole.CjkText),
            ),
            Case(
                text = "中‍👩a",
                expectedRoles = listOf(
                    "中" to FontRole.CjkText,
                    "‍" to FontRole.Unknown,
                    "👩" to FontRole.Emoji,
                    "a" to FontRole.LatinText,
                ),
            ),
        )

        val mismatches = cases.mapNotNull { case ->
            val result = ExplainableStubParagraphLayoutEngine().layout(
                LayoutInput(
                    paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                    content = TiqianTextContent(case.text),
                    constraints = LayoutConstraints(maxWidth = 320f),
                ),
            )

            val actualRoles = result.debug.fontDecisions.map { it.sourceText to FontRole.valueOf(it.role) }
            if (actualRoles == case.expectedRoles) {
                null
            } else {
                "${case.text}: expected=${case.expectedRoles}, actual=$actualRoles"
            }
        }
        assertEquals(emptyList(), mismatches)
    }

    @Test
    fun sourceGraphemeBoundariesDoNotJoinZwJWithOrdinaryText() {
        testTrace.section("sourceGraphemeBoundariesDoNotJoinZwJWithOrdinaryText")
        assertEquals(
            listOf(0, 3, 4),
            "👩‍中".sourceGraphemeBoundaries(TextRange(0, "👩‍中".length)),
        )
        assertEquals(
            listOf(0, 2, 4),
            "中‍👩".sourceGraphemeBoundaries(TextRange(0, "中‍👩".length)),
        )
    }

    @Test
    fun recordsUnicodeEmojiSequenceRolePromotions() {
        testTrace.section("recordsUnicodeEmojiSequenceRolePromotions")
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("❤️与1️⃣"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        assertEquals(
            listOf(
                "❤️" to "Symbol",
                "1️⃣" to "LatinText",
            ),
            result.debug.roleOverrides
                .filter { it.source == "UnicodeEmojiSequenceRolePromotion" }
                .map { it.sourceText to it.originalRole },
        )
        assertTrue(
            result.debug.roleOverrides
                .filter { it.source == "UnicodeEmojiSequenceRolePromotion" }
                .map { it.overriddenRole to it.reason }
                .all { it.first == FontRole.Emoji.name && it.second in setOf("EmojiStyleVariationSequence", "KeycapSequence") },
        )
    }

    @Test
    fun complexEmojiGraphemesHonorTextSpanStyleBoundaries() {
        testTrace.section("complexEmojiGraphemesHonorTextSpanStyleBoundaries")
        val text = "👩🏽‍💻"
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent(
                    text = text,
                    spans = listOf(TextSpan(TextRange(2, text.length), TextStyle(fontWeight = 700))),
                    sourceBoundaries = setOf(2),
                ),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        assertEquals(
            listOf(TextRange(0, 2), TextRange(2, text.length)),
            result.debug.fontDecisions.filter { it.role == FontRole.Emoji.name }.map { it.range },
        )
        assertEquals(listOf("👩", "🏽‍💻"), result.debug.shapingDecisions.map { it.sourceText })
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
