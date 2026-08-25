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
 * 禁则（kinsoku）、行尾悬挂与数字符号 cohesion 的断行修复行为，
 * 自 LineBreakRepairEngineTest 按主题拆出；引擎与断言方式不变。
 */
class KinsokuAndCohesionRepairEngineTest {
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
