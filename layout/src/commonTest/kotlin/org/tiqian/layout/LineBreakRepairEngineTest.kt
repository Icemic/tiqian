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

/**
 * Engine pinned to Fixed(Basic, no hang) for narrow-width repair tests —
 * the MeasureAdaptive default would enable hanging below 14 字 and
 * replace the specific repair under test.
 */
internal fun fixedBasicEngine(
    adjustment: org.tiqian.clreq.AdjustmentStylePolicy = org.tiqian.clreq.AdjustmentStylePolicy(),
    autoSpace: org.tiqian.clreq.AutoSpacePolicy = org.tiqian.clreq.AutoSpacePolicy.Default,
) = ExplainableStubParagraphLayoutEngine(
    clreqProfileResolver = ClreqProfileResolver {
        ClreqProfile.MainlandHorizontal.copy(
            kinsokuMode = org.tiqian.clreq.KinsokuMode.Fixed(
                org.tiqian.clreq.KinsokuLevel.Basic,
            ),
            adjustment = adjustment,
            autoSpace = autoSpace,
        )
    },
    // Repair/kinsoku fixtures stay deterministic: no default hyphenation.
    hyphenator = NoHyphenator,
)

class LineBreakRepairEngineTest {
    @Test
    fun greedyBreakerProducesMultipleLinesWhenWidthOverflows() {
        // 8 CJK clusters * 16f = 128f natural; maxWidth=64f -> 4 clusters per line -> 2 lines.
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("中文排版引擎测试"),
                constraints = LayoutConstraints(maxWidth = 64f),
            ),
        )

        assertEquals(2, result.lines.size)
        assertEquals(8, result.clusters.size)

        val first = result.lines[0]
        val second = result.lines[1]
        assertEquals(0, first.range.start)
        assertEquals(4, first.range.end)
        assertEquals(64f, first.adjustedWidth)
        assertEquals(0f, first.top)
        // CjkBodyLineHeightDefault: 1.5em = 24f per line.
        assertEquals(24f, first.bottom)

        assertEquals(4, second.range.start)
        assertEquals(8, second.range.end)
        assertEquals(64f, second.adjustedWidth)
        assertEquals(24f, second.top)
        assertEquals(48f, second.bottom)

        assertEquals(2, result.debug.lineDecisions.size)
        assertTrue(result.debug.lineDecisions.all { it.kind == "greedy" })
        assertEquals(48f, result.size.height)
    }

    @Test
    fun camelCaseTokenBreaksAtTheHumpWithoutAHyphen() {
        // camelCase product names break at the hump — no hyphen added (the
        // capital signals the break). "Power" + "Point".
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("PowerPoint"),
                constraints = LayoutConstraints(maxWidth = 128f),
            ),
        )

        assertEquals(2, result.lines.size)
        assertTrue(result.lines.all { it.hyphenAdvance == 0f }) // no hyphen at a camel break
        assertTrue(result.clusters.any { it.text == "Power" })
        assertTrue(result.clusters.any { it.text == "Point" })
    }

    @Test
    fun allCapsAbbreviationIsNeverBroken() {
        // CY/T §9.4: an all-caps abbreviation is not broken even when over-long.
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("INTERNATIONALIZATION中"),
                constraints = LayoutConstraints(maxWidth = 128f),
            ),
        )

        assertTrue(result.clusters.any { it.text == "INTERNATIONALIZATION" }) // stays one cluster
    }

    @Test
    fun hyphenatedCompoundBreaksAtExistingHyphenWithoutAddingOne() {
        // CY/T 154-2017 §9.3: "out-of-the-way" wraps AT an existing '-' — the
        // existing hyphen sits at the line end and NO new hyphen is added.
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("out-of-the-way"),
                constraints = LayoutConstraints(maxWidth = 128f),
            ),
        )

        assertEquals(2, result.lines.size)
        assertTrue(result.lines.all { it.hyphenAdvance == 0f }) // no synthetic hyphen added
        assertTrue(result.clusters.any { it.text == "out-" })
        assertTrue(result.clusters.any { it.text == "way" })
        // Line 0 ends on the existing hyphen.
        val line0Last = result.clusters.last { it.range.end <= result.lines[0].range.end }
        assertTrue(line0Last.text.endsWith("-"), "line 0 should end at the existing hyphen: ${line0Last.text}")
    }

    @Test
    fun latinSolidusBreaksAfterSlashWithoutAddingHyphen() {
        // LatinStructuralSolidusBreak: `TeX/LaTeX` exposes a clean separator
        // boundary even though the whole token can fit a fresh line. The slash
        // stays with the previous piece; it must not start the next line.
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(
                    firstLineIndent = Ic(0f),
                    lineLengthGrid = LineLengthGrid(enabled = false),
                ),
                content = TiqianTextContent("TeX/LaTeX"),
                constraints = LayoutConstraints(maxWidth = 80f),
            ),
        )

        assertTrue(result.clusters.any { it.text == "TeX/" })
        assertTrue(result.clusters.any { it.text == "LaTeX" })
        assertEquals("TeX/", result.lineText(0))
        assertEquals("LaTeX", result.lineText(1))
        assertTrue(result.lines.all { it.hyphenAdvance == 0f })
    }

    @Test
    fun overlongLatinWordHardBreaksWithAHangingHyphen() {
        // LatinForcedHyphenBreak (ADR 0029): "English" (112) > measure 80 and has
        // no syllable points (NoHyphenator default), so it hard-breaks at a
        // character boundary with a hanging hyphen, keeping 前二后三 — "En" head,
        // "ish" tail.
        val result = ExplainableStubParagraphLayoutEngine(hyphenator = NoHyphenator).layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("中English"),
                constraints = LayoutConstraints(maxWidth = 80f),
            ),
        )

        assertTrue(result.clusters.none { it.text == "English" })
        assertTrue(result.clusters.any { it.text == "En" }) // ≥2 chars kept at head
        assertTrue(result.clusters.any { it.text == "ish" }) // ≥3 chars kept at tail
        assertEquals(2, result.lines.size)
        assertTrue(result.lines[0].hyphenAdvance > 0f) // hyphen hangs at the break
    }

    @Test
    fun urlLikeLatinTokenBreaksAtSeparatorsWithoutSyntheticHyphen() {
        // LatinOpaqueTokenBreak: URL-looking text is not an English word. It
        // exposes clean breakpoints at URL separators and never invents a
        // display hyphen inside the source link.
        val url = "https://example.com/path/to/abc123def456ghi789"
        val result = ExplainableStubParagraphLayoutEngine(hyphenator = NoHyphenator).layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent(url),
                constraints = LayoutConstraints(maxWidth = 128f),
            ),
        )

        assertTrue(result.lines.size > 1)
        assertTrue(result.lines.all { it.hyphenAdvance == 0f })
        assertTrue(result.clusters.none { it.text == url })
        assertTrue(result.clusters.any { it.text.endsWith("/") })
        assertTrue(result.clusters.any { it.text == "example." })
        assertTrue(
            result.debug.lineDecisions.none { it.repairDecision?.reasonCode == "ForbiddenAtLineStart" },
            "URL separators are LatinText and must not trigger CJK line-start kinsoku",
        )
    }

    @Test
    fun progressiveTechnicalBreakKeepsCjkBodyUnstretchedInEveryStrategy() {
        val text = "中文abcdefghij"
        val technical = LineBreakSpan(TextRange(2, text.length), LineBreakPolicy.ProgressiveTechnical)
        val syllables = object : Hyphenator {
            override fun hyphenate(word: String): List<Int> = listOf(4, 7)
        }
        val breakers: List<LineBreaker> = listOf(
            GreedyLineBreaker(),
            LookaheadLineBreaker(),
            ParagraphDpLineBreaker(),
        )

        breakers.forEach { breaker ->
            val result = ExplainableStubParagraphLayoutEngine(
                lineBreaker = breaker,
                hyphenator = syllables,
            ).layout(
                LayoutInput(
                    paragraphStyle = ParagraphStyle(
                        firstLineIndent = Ic(0f),
                        lineLengthGrid = LineLengthGrid(enabled = false),
                    ),
                    content = TiqianTextContent(text, lineBreakSpans = listOf(technical)),
                    constraints = LayoutConstraints(maxWidth = 104f),
                ),
            )

            assertEquals(6, result.lines.first().range.end, breaker.strategyName)
            assertEquals(0f, result.lines.first().hyphenAdvance, breaker.strategyName)
            assertTrue(
                result.debug.lineDecisions.first().notes.contains("technical-break:Emergency"),
                "${breaker.strategyName}: ${result.debug.lineDecisions.first().notes}",
            )
            val adjustment = result.debug.justificationDecisions.first { it.lineRange == result.lines.first().range }
            assertTrue(adjustment.allocations.isNotEmpty(), breaker.strategyName)
            assertTrue(
                adjustment.allocations.none { it.kind == "CjkInterChar" },
                "${breaker.strategyName}: ${adjustment.allocations}",
            )
            assertTrue(
                adjustment.allocations.any { allocation ->
                    allocation.kind == "EmergencyGraphemeTracking" &&
                        allocation.clusterRange.start >= technical.range.start
                },
                "${breaker.strategyName}: ${adjustment.allocations}",
            )
            assertEquals(0f, adjustment.deficitAfter, 0.001f, breaker.strategyName)
        }
    }

    @Test
    fun progressiveTechnicalStructuralBreakFallsThroughToEmergencyBeforeTracking() {
        val text = "中文ab.cdEfghij"
        val result = ExplainableStubParagraphLayoutEngine(
            lineBreaker = LookaheadLineBreaker(),
            hyphenator = object : Hyphenator {
                override fun hyphenate(word: String): List<Int> = listOf(2, 4, 6)
            },
        ).layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(
                    firstLineIndent = Ic(0f),
                    lineLengthGrid = LineLengthGrid(enabled = false),
                ),
                content = TiqianTextContent(
                    text,
                    lineBreakSpans = listOf(
                        LineBreakSpan(TextRange(2, text.length), LineBreakPolicy.ProgressiveTechnical),
                    ),
                ),
                constraints = LayoutConstraints(maxWidth = 124f),
            ),
        )

        assertEquals("中文ab.cd", result.lineText(0))
        assertTrue(
            result.debug.lineDecisions.first().notes.contains("technical-break:Emergency"),
            "lines=${result.lines.indices.map(result::lineText)} decisions=${result.debug.lineDecisions} " +
                "adjustments=${result.debug.justificationDecisions}",
        )
        assertTrue(result.lines.all { it.hyphenAdvance == 0f })
        val firstLineAdjustment = result.debug.justificationDecisions.first()
        assertTrue(firstLineAdjustment.allocations.none { it.kind == "CjkInterChar" })
        assertTrue(
            firstLineAdjustment.allocations.any {
                it.kind == "EmergencyGraphemeTracking" &&
                    it.reason.startsWith("TerminalTechnicalEmergencyTracking")
            },
        )
    }

    @Test
    fun progressiveTechnicalHardBreakOverridesNumberRunCohesion() {
        val text = "aaaaa1234567890bbbb"
        val technical = LineBreakSpan(TextRange(0, text.length), LineBreakPolicy.ProgressiveTechnical)
        val breakers: List<LineBreaker> = listOf(
            GreedyLineBreaker(),
            LookaheadLineBreaker(),
            ParagraphDpLineBreaker(),
        )

        breakers.forEach { breaker ->
            val result = ExplainableStubParagraphLayoutEngine(
                lineBreaker = breaker,
                hyphenator = NoHyphenator,
            ).layout(
                LayoutInput(
                    paragraphStyle = ParagraphStyle(
                        firstLineIndent = Ic(0f),
                        lineLengthGrid = LineLengthGrid(enabled = false),
                    ),
                    content = TiqianTextContent(text, lineBreakSpans = listOf(technical)),
                    constraints = LayoutConstraints(maxWidth = 160f),
                ),
            )

            assertEquals("aaaaa12345", result.lineText(0), breaker.strategyName)
            assertTrue(
                result.debug.lineDecisions.first().notes.contains("technical-break:Emergency"),
                "${breaker.strategyName}: ${result.debug.lineDecisions}",
            )
        }
    }

    @Test
    fun progressiveTechnicalCleanBreakMayNotStretchEarlierOpaqueToken() {
        val text = "deadbeef1234deadbeef1234 ab.cdEfghijklmnop"
        val terminalTechnicalRange = TextRange(25, text.length)
        val result = ExplainableStubParagraphLayoutEngine(
            lineBreaker = LookaheadLineBreaker(),
            hyphenator = object : Hyphenator {
                override fun hyphenate(word: String): List<Int> = listOf(2, 4, 6)
            },
        ).layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(
                    firstLineIndent = Ic(0f),
                    lineLengthGrid = LineLengthGrid(enabled = false),
                ),
                content = TiqianTextContent(
                    text,
                    lineBreakSpans = listOf(
                        LineBreakSpan(terminalTechnicalRange, LineBreakPolicy.ProgressiveTechnical),
                    ),
                ),
                constraints = LayoutConstraints(maxWidth = 300f),
            ),
        )

        val affectedLineIndex = result.debug.lineDecisions.indexOfFirst {
            it.notes.any { note -> note.startsWith("technical-break:") }
        }
        assertTrue(affectedLineIndex >= 0, result.debug.lineDecisions.toString())
        assertTrue(
            result.debug.lineDecisions[affectedLineIndex].notes.contains("technical-break:Emergency"),
            "lines=${result.lines.indices.map(result::lineText)} decisions=${result.debug.lineDecisions} " +
                "adjustments=${result.debug.justificationDecisions}",
        )
        val affectedLine = result.lines[affectedLineIndex]
        val affectedLineAdjustment = result.debug.justificationDecisions
            .first { it.lineRange == affectedLine.range }
        val emergencyTracking = affectedLineAdjustment.allocations
            .filter { it.kind == "EmergencyGraphemeTracking" }
        assertTrue(emergencyTracking.isNotEmpty(), affectedLineAdjustment.toString())
        assertTrue(
            emergencyTracking.all { it.clusterRange.start >= terminalTechnicalRange.start },
            "a later clean break borrowed tracking from the earlier hash: $emergencyTracking",
        )
    }

    @Test
    fun progressiveTechnicalBreakFallsThroughStructuralTierBeforeOverstretchingOutsideText() {
        val text = "中 ab/cdefghijk"
        val technical = LineBreakSpan(TextRange(2, text.length), LineBreakPolicy.ProgressiveTechnical)
        val syllables = object : Hyphenator {
            override fun hyphenate(word: String): List<Int> = listOf(2, 4, 6)
        }

        listOf(
            GreedyLineBreaker(),
            LookaheadLineBreaker(),
            ParagraphDpLineBreaker(),
        ).forEach { breaker ->
            val result = ExplainableStubParagraphLayoutEngine(
                lineBreaker = breaker,
                hyphenator = syllables,
            ).layout(
                LayoutInput(
                    paragraphStyle = ParagraphStyle(
                        firstLineIndent = Ic(0f),
                        lineLengthGrid = LineLengthGrid(enabled = false),
                    ),
                    content = TiqianTextContent(text, lineBreakSpans = listOf(technical)),
                    constraints = LayoutConstraints(maxWidth = 100f),
                ),
            )

            assertEquals(7, result.lines.first().range.end, breaker.strategyName)
            assertEquals(0f, result.lines.first().hyphenAdvance, breaker.strategyName)
            assertTrue(
                result.debug.lineDecisions.first().notes.contains("technical-break:Syllable"),
                "${breaker.strategyName}: ${result.debug.lineDecisions.first().notes}",
            )
            val adjustment = result.debug.justificationDecisions.first {
                it.lineRange == result.lines.first().range
            }
            assertTrue(
                adjustment.allocations.none { allocation ->
                    allocation.clusterRange.end > technical.range.start &&
                        allocation.clusterRange.end < technical.range.end
                },
                "${breaker.strategyName}: ${adjustment.allocations}",
            )
        }
    }

    @Test
    fun progressiveTechnicalEmergencyIsExposedByCurrentLineStretchNotFullMeasure() {
        val text = "Swift 这边是我最有体感的。JSONDecoder 慢是个老问题，" +
            "SR-6252[36] 那个 issue 里挖出的根因是底层走 NSJSONSerialization " +
            "再桥接回 Objective-C，swift_dynamicCast 吃掉大量时间。"
        val swiftRange = TextRange(104, 121)
        val result = ExplainableStubParagraphLayoutEngine(
            lineBreaker = LookaheadLineBreaker(),
            hyphenator = NoHyphenator,
        ).layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(
                    firstLineIndent = Ic(0f),
                    lineLengthGrid = LineLengthGrid(enabled = false),
                ),
                content = TiqianTextContent(
                    text,
                    lineBreakSpans = listOf(
                        LineBreakSpan(TextRange(16, 27), LineBreakPolicy.ProgressiveTechnical),
                        LineBreakSpan(TextRange(67, 86), LineBreakPolicy.ProgressiveTechnical),
                        LineBreakSpan(swiftRange, LineBreakPolicy.ProgressiveTechnical),
                    ),
                ),
                constraints = LayoutConstraints(maxWidth = 579f),
            ),
        )

        val lineTexts = result.lines.indices.map(result::lineText)
        val affectedLineIndex = lineTexts.indexOfFirst { it.contains("Objective-C") }
        assertTrue(affectedLineIndex >= 0, lineTexts.toString())
        val affectedLine = result.lines[affectedLineIndex]
        assertEquals(
            "erialization 再桥接回 Objective-C，swift_dy",
            result.lineText(affectedLineIndex),
        )
        assertTrue(
            result.debug.lineDecisions[affectedLineIndex]
                .notes.contains("technical-break:Emergency"),
        )
        val affectedLineAdjustment = result.debug.justificationDecisions
            .firstOrNull { it.lineRange == affectedLine.range }
        val cjkStretch = affectedLineAdjustment
            ?.allocations
            .orEmpty()
            .filter { it.kind == "CjkInterChar" }
            .maxOfOrNull { it.delta } ?: 0f
        assertTrue(cjkStretch <= 0.001f, "current line still stretched CJK body: $cjkStretch")
        assertTrue(
            result.debug.breakOpportunityDecisions.any {
                it.range == swiftRange &&
                    it.tier == "Emergency" &&
                    it.reason == "CurrentLineTechnicalEmergencyBreak"
            },
        )
        assertTrue(
            result.debug.emergencyTrackingEligibilityDecisions.any {
                it.range == swiftRange && it.reason.startsWith("CurrentLineTechnicalTierRejection:")
            },
        )
    }

    @Test
    fun unbrokenProgressiveSpanUsesSourceSpaceThenKeepsBodyOpportunitiesAvailable() {
        val text = "甲乙ab cd丙丁戊己"
        val technical = LineBreakSpan(TextRange(2, 7), LineBreakPolicy.ProgressiveTechnical)
        val result = ExplainableStubParagraphLayoutEngine(hyphenator = NoHyphenator).layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(
                    firstLineIndent = Ic(0f),
                    lineLengthGrid = LineLengthGrid(enabled = false),
                ),
                content = TiqianTextContent(
                    text,
                    lineBreakSpans = listOf(technical),
                ),
                constraints = LayoutConstraints(maxWidth = 129f),
            ),
        )
        val baseline = ExplainableStubParagraphLayoutEngine(hyphenator = NoHyphenator).layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(
                    firstLineIndent = Ic(0f),
                    lineLengthGrid = LineLengthGrid(enabled = false),
                ),
                content = TiqianTextContent(text),
                constraints = LayoutConstraints(maxWidth = 129f),
            ),
        )

        assertTrue(result.debug.lineDecisions.first().notes.none { it.startsWith("technical-break:") })
        val adjustment = result.debug.justificationDecisions.first()
        assertTrue(adjustment.allocations.isNotEmpty())
        assertEquals(baseline.lines.map { it.range }, result.lines.map { it.range })
        assertEquals(0f, adjustment.deficitAfter, 0.001f)
        assertTrue(
            adjustment.allocations.any {
                it.clusterRange == TextRange(4, 5) &&
                    it.kind == "ProgressiveTechnical" &&
                    it.reason == "ProgressiveTechnicalWhitespaceStretch"
            },
            adjustment.allocations.toString(),
        )
        assertTrue(
            adjustment.allocations.any {
                it.clusterRange.end <= technical.range.start || it.clusterRange.start >= technical.range.end
            },
            "bounded technical whitespace must not freeze the remaining body opportunities: ${adjustment.allocations}",
        )
    }

    @Test
    fun overlongOpaqueLatinTokenHardBreaksWithoutSyntheticHyphen() {
        // Mixed alpha/digit identifiers and hashes are not words either. If no
        // separator can rescue an over-wide piece, hard-break it cleanly without
        // a synthetic hyphen.
        val result = ExplainableStubParagraphLayoutEngine(hyphenator = NoHyphenator).layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("abc123def456ghi789"),
                constraints = LayoutConstraints(maxWidth = 96f),
            ),
        )

        assertTrue(result.lines.size > 1)
        assertTrue(result.lines.all { it.hyphenAdvance == 0f })
        assertTrue(result.clusters.none { it.text == "abc123def456ghi789" })
        assertTrue(result.lines.all { it.visualWidth <= 96f })
    }

    @Test
    fun longAllCapsOpaqueTokenHardBreaksWithoutSyntheticHyphen() {
        // Long all-caps blobs can be base64/hash-like data, not abbreviations.
        // A short NASA-style abbreviation stays protected elsewhere; this
        // over-threshold token falls back to opaque no-hyphen cuts.
        val token = "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVo"
        val result = ExplainableStubParagraphLayoutEngine(hyphenator = NoHyphenator).layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent(token),
                constraints = LayoutConstraints(maxWidth = 96f),
            ),
        )

        assertTrue(result.lines.size > 1)
        assertTrue(result.lines.all { it.hyphenAdvance == 0f })
        assertTrue(result.clusters.none { it.text == token })
    }

    @Test
    fun opaqueLatinTokenAfterCjkPullsPrefixOntoLooseLine() {
        val prefix = "为什么历史是 "
        val result = ExplainableStubParagraphLayoutEngine(
            lineBreaker = LookaheadLineBreaker(),
            hyphenator = NoHyphenator,
        ).layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent(prefix + "abc123def456ghi789"),
                constraints = LayoutConstraints(maxWidth = 160f),
            ),
        )

        val firstLineText = result.clusters
            .filterIndexed { index, _ -> index in result.lines.first().clusterRange }
            .joinToString(separator = "") { it.text }
        assertTrue(
            firstLineText.length > prefix.length,
            "first line should carry part of the opaque token instead of stretching only '$prefix': $firstLineText",
        )
        assertTrue(result.lines.first().hyphenAdvance == 0f)
    }

    @Test
    fun nonLexicalLetterRunAfterCjkPullsPrefixOntoLooseLineWithoutSyntheticHyphen() {
        val prefix = "为什么历史是 "
        val token = "s".repeat(40) + "herstory"
        val result = ExplainableStubParagraphLayoutEngine(
            lineBreaker = LookaheadLineBreaker(),
            hyphenator = NoHyphenator,
        ).layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent(prefix + token),
                constraints = LayoutConstraints(maxWidth = 160f),
            ),
        )

        val firstLineText = result.clusters
            .filterIndexed { index, _ -> index in result.lines.first().clusterRange }
            .joinToString(separator = "") { it.text }
        assertTrue(
            firstLineText.length > prefix.length,
            "first line should carry part of the non-lexical letter run: $firstLineText",
        )
        assertTrue(result.lines.all { it.hyphenAdvance == 0f })
    }

    @Test
    fun longLetterBlobStaysOpaqueEvenWhenTailLooksHyphenatable() {
        val prefix = "为什么历史是 "
        val token = "s".repeat(40) + "herstory"
        val tailHyphenator = object : Hyphenator {
            override fun hyphenate(word: String): List<Int> = listOf(word.length - "story".length)
        }
        val result = ExplainableStubParagraphLayoutEngine(
            lineBreaker = LookaheadLineBreaker(),
            hyphenator = tailHyphenator,
        ).layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent(prefix + token),
                constraints = LayoutConstraints(maxWidth = 160f),
            ),
        )

        val firstLineText = result.clusters
            .filterIndexed { index, _ -> index in result.lines.first().clusterRange }
            .joinToString(separator = "") { it.text }
        assertTrue(
            firstLineText.length > prefix.length,
            "first line should carry part of the opaque letter blob: $firstLineText",
        )
        assertTrue(result.lines.all { it.hyphenAdvance == 0f })
        assertTrue(result.clusters.none { it.text == token })
    }

    @Test
    fun longOpaqueTokenCanBreakEvenWhenItFitsAloneButNotAfterCjkPrefix() {
        val prefix = "为什么历史是 "
        val token = "s".repeat(40) + "herstory"
        val tailHyphenator = object : Hyphenator {
            override fun hyphenate(word: String): List<Int> = listOf(word.length - "story".length)
        }
        val result = ExplainableStubParagraphLayoutEngine(
            lineBreaker = LookaheadLineBreaker(),
            hyphenator = tailHyphenator,
        ).layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent(prefix + token),
                constraints = LayoutConstraints(maxWidth = 800f),
            ),
        )

        val firstLineText = result.clusters
            .filterIndexed { index, _ -> index in result.lines.first().clusterRange }
            .joinToString(separator = "") { it.text }
        assertTrue(
            firstLineText.length > prefix.length,
            "first line should carry part of the long opaque token instead of stretching only '$prefix': $firstLineText",
        )
        assertTrue(result.clusters.none { it.text == token })
        assertTrue(result.lines.all { it.hyphenAdvance == 0f })
    }

    @Test
    fun kinsokuCarriesPreviousClusterWhenLineWouldStartWithForbiddenPunctuation() {
        // Pure greedy at maxWidth=64 -> line 0: 中文中文 (clusters 0..3), line 1: 。
        // 。 is PauseOrStop, forbidden at line start, so CarryPrevious pulls
        // 文 (cluster 3) to line 1: line 0 = 中文中, line 1 = 文。.
        val result = fixedBasicEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("中文中文。"),
                constraints = LayoutConstraints(maxWidth = 64f),
            ),
        )

        assertEquals(2, result.lines.size)
        assertEquals(0, result.lines[0].range.start)
        assertEquals(3, result.lines[0].range.end)
        assertEquals(3, result.lines[1].range.start)
        assertEquals(5, result.lines[1].range.end)
        assertEquals(48f, result.lines[0].adjustedWidth)
        // Line 1 ends with 。 → LineEndGlueTrim takes full trailing=8.
        // 文(16) + 。(16-8) = 24.
        assertEquals(24f, result.lines[1].adjustedWidth)

        assertEquals(null, result.debug.lineDecisions[0].repair)
        assertEquals("CarryPrevious", result.debug.lineDecisions[1].repair)
        assertEquals(10, result.debug.lineDecisions[1].repairPenalty)
        val repairDecision = result.debug.lineDecisions[1].repairDecision
        assertEquals("CarryPrevious", repairDecision?.kind)
        assertEquals("ForbiddenAtLineStart", repairDecision?.reasonCode)
        assertEquals(4, repairDecision?.offenderRange?.start)
        assertEquals(5, repairDecision?.offenderRange?.end)
        assertEquals(3, repairDecision?.carriedClusterIndex)
        val repairCandidates = result.debug.lineDecisions[1].repairCandidates
        assertEquals(2, repairCandidates.size)
        assertEquals("PushIn", repairCandidates[0].kind)
        assertEquals(false, repairCandidates[0].accepted)
        assertEquals("insufficient-capacity", repairCandidates[0].rejectionReason)
        assertEquals("CarryPrevious", repairCandidates[1].kind)
        assertEquals(true, repairCandidates[1].accepted)
        assertTrue(
            result.debug.lineDecisions[1].notes.any {
                it.contains("ForbiddenAtLineStart:。") && it.contains("carried=文")
            },
        )
    }

    @Test
    fun kinsokuPushesLineStartPunctuationIntoPreviousLineWhenTrailingGlueCanShrink() {
        // Pure greedy at maxWidth=60 -> line 0: 中文中 (48f), line 1: 。
        // 。 can be pushed into previous line by shrinking its trailing glue.
        // PushIn shrinks 4f (overflow), then edge trim takes remaining 4f.
        // 。 trailing=8, PushIn uses 4, edge trim uses 4 → 。 advance=8.
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                // Pin the exact measure (60 ∤ 16); this test is about PushIn +
                // edge-trim geometry, not the grid.
                paragraphStyle = ParagraphStyle(
                    firstLineIndent = Ic(0f),
                    lineLengthGrid = LineLengthGrid(enabled = false),
                ),
                content = TiqianTextContent("中文中。"),
                constraints = LayoutConstraints(maxWidth = 60f),
            ),
        )

        assertEquals(1, result.lines.size)
        val line = result.lines.single()
        assertEquals(0, line.range.start)
        assertEquals(4, line.range.end)
        assertEquals(64f, line.naturalWidth)
        // PushIn shrinks 4, edge trim shrinks 4 more → 64 - 8 = 56.
        assertEquals(56f, line.adjustedWidth)
        assertEquals(56f, line.visualWidth)
        assertEquals(56f, result.clusters.sumOf { it.advance.toDouble() }.toFloat())
        assertEquals(56f, result.glyphRuns.sumOf { it.advance.toDouble() }.toFloat())

        val stop = result.clusters.single { it.text == "。" }
        assertEquals(8f, stop.advance)
        val stopGeometry = result.debug.geometryDecisions.single { it.sourceText == "。" }
        assertEquals(8f, stopGeometry.trailingGlueConsumed)
        assertEquals(8f, stopGeometry.resolvedAdvance)
        assertEquals(1, result.debug.lineEdgeTrimDecisions.size)
        assertEquals("PushIn", result.debug.lineDecisions.single().repair)
        assertEquals(2, result.debug.lineDecisions.single().repairPenalty)
        val repairDecision = result.debug.lineDecisions.single().repairDecision
        assertEquals("PushIn", repairDecision?.kind)
        assertEquals("ForbiddenAtLineStart", repairDecision?.reasonCode)
        assertEquals(3, repairDecision?.offenderRange?.start)
        assertEquals(4, repairDecision?.offenderRange?.end)
        assertEquals(3, repairDecision?.targetClusterIndex)
        assertEquals(4f, repairDecision?.shrink)
        assertEquals(8f, repairDecision?.availableCapacity)
        val repairCandidates = result.debug.lineDecisions.single().repairCandidates
        assertEquals(1, repairCandidates.size)
        assertEquals("PushIn", repairCandidates.single().kind)
        assertEquals(true, repairCandidates.single().accepted)
        assertEquals(4f, repairCandidates.single().requiredShrink)
        assertEquals(8f, repairCandidates.single().availableCapacity)
        assertTrue(
            result.debug.lineDecisions.single().notes.any {
                it.contains("ForbiddenAtLineStart:。") && it.contains("pushed-in=4.0")
            },
        )
    }

    @Test
    fun kinsokuLeavesGreedyBreakAloneWhenNoForbiddenPunctAtLineStart() {
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("中文中文哈哈"),
                constraints = LayoutConstraints(maxWidth = 64f),
            ),
        )

        assertEquals(2, result.lines.size)
        assertEquals(0, result.lines[0].range.start)
        assertEquals(4, result.lines[0].range.end)
        assertEquals(4, result.lines[1].range.start)
        assertEquals(6, result.lines[1].range.end)
        assertEquals(null, result.debug.lineDecisions[0].repair)
        assertEquals(null, result.debug.lineDecisions[1].repair)
    }

    @Test
    fun kinsokuFallsBackToLeaveRaggedWhenPreviousLineCannotSpareACluster() {
        // "Coffee" is one cluster (6 chars, 96f = measure, so NOT hard-broken).
        // At maxWidth=96 greedy fills line 0 with it alone and pushes 。 to line 1.
        // Previous line has only one cluster, so CarryPrevious cannot apply ->
        // LeaveRagged.
        val result = fixedBasicEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("Coffee。"),
                constraints = LayoutConstraints(maxWidth = 96f),
            ),
        )

        assertEquals(2, result.lines.size)
        assertEquals("Coffee", result.clusters.first { it.text == "Coffee" }.text)
        assertEquals("LeaveRagged", result.debug.lineDecisions[1].repair)
        assertEquals(20, result.debug.lineDecisions[1].repairPenalty)
        assertTrue(
            result.debug.lineDecisions[1].notes.any {
                it.contains("ForbiddenAtLineStart:。") && it.contains("no-room-to-carry")
            },
        )
    }

    @Test
    fun longLatinSentenceWrapsAtWordBoundaries() {
        // The headline LatinWordSegmentation capability: a Latin sentence
        // longer than the measure breaks BETWEEN words (previously a Latin
        // run was one unbreakable cluster and simply overflowed).
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("The quick brown fox"),
                constraints = LayoutConstraints(maxWidth = 160f),
            ),
        )

        assertTrue(result.lines.size > 1, "long Latin must wrap at word boundaries")
        // No line may begin or end with visible space width.
        for (line in result.lines) {
            val lineClusters = result.clusters.filter {
                it.range.start >= line.range.start && it.range.end <= line.range.end
            }
            val first = lineClusters.first()
            val last = lineClusters.last()
            if (first.text.all { ch -> ch == ' ' }) assertEquals(0f, first.advance)
            if (last.text.all { ch -> ch == ' ' }) assertEquals(0f, last.advance)
        }
    }

    @Test
    fun numberWithSuffixSymbolNeverSplitsAcrossLines() {
        // CLREQ 符号分离禁则: 数字 + 后缀 % 不可拆行. At maxWidth 120 the natural
        // greedy break falls between 50 and %; NumberSymbolCohesion moves the
        // whole 50% group to the next line instead of orphaning % at line start.
        val text = "销量增长了50%呢"
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                content = TiqianTextContent(text),
                constraints = LayoutConstraints(maxWidth = 120f),
                paragraphStyle = ParagraphStyle(
                    firstLineIndent = Ic(0f),
                    lineLengthGrid = org.tiqian.core.LineLengthGrid(enabled = false),
                ),
            ),
        )
        val lineTexts = result.lines.map { text.substring(it.range.start, it.range.end) }
        assertTrue(lineTexts.any { it.contains("50%") }, "50% must stay together: $lineTexts")
        assertTrue(lineTexts.none { it.endsWith("50") }, "no line may end mid-number: $lineTexts")
    }

    @Test
    fun bibliographicNumericLocatorExposesStructuralBreaks() {
        val text = "中文中文中文44(10):21-38."
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                content = TiqianTextContent(text),
                constraints = LayoutConstraints(maxWidth = 224f),
                paragraphStyle = ParagraphStyle(
                    firstLineIndent = Ic(0f),
                    lineLengthGrid = org.tiqian.core.LineLengthGrid(enabled = false),
                ),
            ),
        )

        val locatorStart = text.indexOf("44")
        val decision = result.debug.breakOpportunityDecisions.single()
        assertEquals(org.tiqian.core.TextRange(locatorStart, text.length), decision.range)
        assertEquals("44(10):21-38.", decision.sourceText)
        assertEquals(
            listOf(text.indexOf('('), text.indexOf(':') + 1),
            decision.breakOffsets,
        )
        assertEquals("BibliographicNumericLocatorBreak", decision.reason)

        val lineTexts = result.lines.map { text.substring(it.range.start, it.range.end) }
        assertTrue(lineTexts.first().endsWith("44(10):"), "locator should fill the preceding line: $lineTexts")
        assertEquals("21-38.", lineTexts.last())
        assertTrue(lineTexts.none { it.endsWith("(") }, "opening bracket cannot end a line: $lineTexts")
        assertTrue(lineTexts.none { it.startsWith(")") }, "closing bracket cannot start a line: $lineTexts")
    }

    @Test
    fun ordinaryNumericFormsDoNotBecomeBibliographicLocators() {
        for (token in listOf("3.14", "1,000", "12:34", "2023-08-11")) {
            val result = ExplainableStubParagraphLayoutEngine().layout(
                LayoutInput(
                    content = TiqianTextContent("中文$token"),
                    constraints = LayoutConstraints(maxWidth = 320f),
                    paragraphStyle = ParagraphStyle(
                        firstLineIndent = Ic(0f),
                        lineLengthGrid = org.tiqian.core.LineLengthGrid(enabled = false),
                    ),
                ),
            )
            assertTrue(
                result.debug.breakOpportunityDecisions.isEmpty(),
                "$token must keep its existing numeric/token policy: ${result.debug.breakOpportunityDecisions}",
            )
        }
    }

    @Test
    fun kinsokuLevelNoneLeavesForbiddenMarksAtLineStart() {
        // 不处理 (CLREQ): no line-start prohibition — 。 may begin a line, so
        // no repair fires even when greedy puts it there.
        fun engineAt(level: org.tiqian.clreq.KinsokuLevel) =
            ExplainableStubParagraphLayoutEngine(
                clreqProfileResolver = ClreqProfileResolver {
                    ClreqProfile.MainlandHorizontal.copy(kinsokuMode = org.tiqian.clreq.KinsokuMode.Fixed(level))
                },
            )
        val input = LayoutInput(
            paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
            content = TiqianTextContent("中文中。中"),
            constraints = LayoutConstraints(maxWidth = 48f),
        )

        val none = engineAt(org.tiqian.clreq.KinsokuLevel.None).layout(input)
        assertTrue(none.debug.lineDecisions.all { it.repair == null })
        // The 。 sits at a line start, untouched.
        assertTrue(none.lines.any { it.range.start == 3 })

        // Basic (default) repairs it (PushIn/Carry) so no line begins with 。
        val basic = engineAt(org.tiqian.clreq.KinsokuLevel.Basic).layout(input)
        assertTrue(basic.debug.lineDecisions.any { it.repair != null })
    }

    @Test
    fun kinsokuLevelStrictForbidsDashAtLineStart() {
        // 严格处理 追加破折号不得居行首；基本处理允许.
        fun layoutAt(level: org.tiqian.clreq.KinsokuLevel) =
            ExplainableStubParagraphLayoutEngine(
                clreqProfileResolver = ClreqProfileResolver {
                    ClreqProfile.MainlandHorizontal.copy(kinsokuMode = org.tiqian.clreq.KinsokuMode.Fixed(level))
                },
            ).layout(
                LayoutInput(
                    paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                    content = TiqianTextContent("中文中——文"),
                    constraints = LayoutConstraints(maxWidth = 48f),
                ),
            )

        // Basic: —— (2em dash) may begin a line — no repair.
        val basic = layoutAt(org.tiqian.clreq.KinsokuLevel.Basic)
        assertTrue(basic.debug.lineDecisions.all { it.repair == null })

        // Strict: the dash is now forbidden at line start → a repair fires.
        val strict = layoutAt(org.tiqian.clreq.KinsokuLevel.Strict)
        assertTrue(strict.debug.lineDecisions.any { it.repair != null })
    }

    @Test
    fun lineEndKinsokuMovesDanglingOpenerToNextLine() {
        // CLREQ 行尾禁则 (Basic): 开括号不得居行尾. 中中中（中中）中 @maxWidth
        // 64: greedy would end line 0 on （ — the engine derives the
        // forbidden-end set from the kinsoku level and retreats the break so
        // （ starts line 1. No line ends on an opener.
        val result = fixedBasicEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("中中中（中中）中"),
                constraints = LayoutConstraints(maxWidth = 64f),
            ),
        )

        for (line in result.lines) {
            val lastCluster = result.clusters.last { it.range.end <= line.range.end }
            assertTrue(
                lastCluster.text != "（",
                "line must not end on 开括号: ${result.clusters}",
            )
        }
        assertTrue(result.debug.lineDecisions.any { it.repair == "CarryNext" })
    }

    @Test
    fun hangingPunctuationFillsLineToMeasureAndOverflowsVisual() {
        // LineEndHangingPunctuation (CLREQ 行尾点号悬挂, ADR 0006): with the
        // PauseStops style, a 句号 that would land at line start hangs past
        // the measure. 中文中文，中文。 @maxWidth 64: the first 逗号 hangs at
        // line 0 end — content (中文中文 = 64) fills the measure exactly,
        // visualWidth overflows by the hung mark's half-width.
        val engine = ExplainableStubParagraphLayoutEngine(
            clreqProfileResolver = ClreqProfileResolver {
                ClreqProfile.MainlandHorizontal.copy(
                    kinsokuMode = org.tiqian.clreq.KinsokuMode.Fixed(
                        level = org.tiqian.clreq.KinsokuLevel.Basic,
                        hanging = org.tiqian.clreq.HangingPunctuationStyle.PauseStops,
                    ),
                )
            },
        )
        val result = engine.layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("中文中文，中文。"),
                constraints = LayoutConstraints(maxWidth = 64f),
            ),
        )

        assertTrue(result.lines.size >= 2)
        val line0 = result.lines[0]
        // The 逗号 (index 4) is on line 0, hanging.
        assertEquals(0, line0.range.start)
        assertEquals(5, line0.range.end)
        // Content fills the measure; the hung mark overflows it.
        assertEquals(64f, line0.adjustedWidth)
        assertTrue(line0.visualWidth > 64f, "hung mark must overflow: ${line0.visualWidth}")
        assertEquals(line0.visualWidth - line0.adjustedWidth, line0.hangingPunctuationAdvance)
        assertEquals("Hang", result.debug.lineDecisions[0].repair)

        // Fixed Disabled → no hang; the 逗号 wraps via CarryPrevious.
        val plain = ExplainableStubParagraphLayoutEngine(
            clreqProfileResolver = ClreqProfileResolver {
                ClreqProfile.MainlandHorizontal.copy(
                    kinsokuMode = org.tiqian.clreq.KinsokuMode.Fixed(
                        level = org.tiqian.clreq.KinsokuLevel.Basic,
                        hanging = org.tiqian.clreq.HangingPunctuationStyle.Disabled,
                    ),
                )
            },
        ).layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("中文中文，中文。"),
                constraints = LayoutConstraints(maxWidth = 64f),
            ),
        )
        assertTrue(plain.lines.none { it.visualWidth > 64f })
        assertTrue(plain.debug.lineDecisions.none { it.repair == "Hang" })
    }
}

private fun LayoutResult.lineText(index: Int): String {
    val line = lines[index]
    return line.clusterRange.joinToString("") { clusters[it].text }
}
