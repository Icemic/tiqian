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
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.assertFailsWith
import org.tiqian.test.trace.assertTrue
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

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
    private val testTrace = TestTraceRecorder("LineBreakRepairEngineTest")

    @Test
    fun greedyBreakerProducesMultipleLinesWhenWidthOverflows() {
        testTrace.section("greedyBreakerProducesMultipleLinesWhenWidthOverflows")
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
        testTrace.section("camelCaseTokenBreaksAtTheHumpWithoutAHyphen")
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
        testTrace.section("allCapsAbbreviationIsNeverBroken")
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
        testTrace.section("hyphenatedCompoundBreaksAtExistingHyphenWithoutAddingOne")
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
        testTrace.section("latinSolidusBreaksAfterSlashWithoutAddingHyphen")
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
        testTrace.section("overlongLatinWordHardBreaksWithAHangingHyphen")
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
        testTrace.section("urlLikeLatinTokenBreaksAtSeparatorsWithoutSyntheticHyphen")
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
        testTrace.section("progressiveTechnicalBreakKeepsCjkBodyUnstretchedInEveryStrategy")
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
        testTrace.section("progressiveTechnicalStructuralBreakFallsThroughToEmergencyBeforeTracking")
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
        testTrace.section("progressiveTechnicalHardBreakOverridesNumberRunCohesion")
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
        testTrace.section("progressiveTechnicalCleanBreakMayNotStretchEarlierOpaqueToken")
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
        testTrace.section("progressiveTechnicalBreakFallsThroughStructuralTierBeforeOverstretchingOutsideText")
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
        testTrace.section("progressiveTechnicalEmergencyIsExposedByCurrentLineStretchNotFullMeasure")
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
        testTrace.section("unbrokenProgressiveSpanUsesSourceSpaceThenKeepsBodyOpportunitiesAvailable")
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
        testTrace.section("overlongOpaqueLatinTokenHardBreaksWithoutSyntheticHyphen")
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
        testTrace.section("longAllCapsOpaqueTokenHardBreaksWithoutSyntheticHyphen")
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
        testTrace.section("opaqueLatinTokenAfterCjkPullsPrefixOntoLooseLine")
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
        testTrace.section("nonLexicalLetterRunAfterCjkPullsPrefixOntoLooseLineWithoutSyntheticHyphen")
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
        testTrace.section("longLetterBlobStaysOpaqueEvenWhenTailLooksHyphenatable")
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
        testTrace.section("longOpaqueTokenCanBreakEvenWhenItFitsAloneButNotAfterCjkPrefix")
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

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}

internal fun LayoutResult.lineText(index: Int): String {
    val line = lines[index]
    return line.clusterRange.joinToString("") { clusters[it].text }
}
