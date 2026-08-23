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

class SpacingAndLineGeometryEngineTest {
    @Test
    fun autoSpaceReplacesTypedSpaceAtCjkLatinBoundary() {
        // " CJK " becomes one Latin cluster (5 chars * 16 = 80px nominal).
        // At maxWidth large enough, default AutoSpacePolicy.Replace shrinks
        // each boundary space from 二分空 0.5em (8) to gapEm 0.125em (2).
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("中文 CJK 段落"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        // LatinWordSegmentation: [ ][CJK][ ] — each CJK-adjacent space
        // cluster IS the gap and normalises from 二分空 0.5em to 0.25em.
        val spaces = result.clusters.filter { it.text == " " }
        assertEquals(2, spaces.size)
        assertTrue(spaces.all { it.advance == 2f })
        assertEquals(2, result.debug.autoSpaceDecisions.size)
        assertTrue(
            result.debug.autoSpaceDecisions.all {
                it.mode == "Replace" && it.side == "gap" && it.totalReduction == 6f
            },
        )
    }

    @Test
    fun autoSpaceDoesNotShrinkSpacesBetweenLatinWords() {
        // "Hello world" — space between two Latin words, no CJK boundary.
        // AutoSpace.Replace only applies at CJK boundaries; word-internal
        // spaces stay at their nominal 二分空 0.5em.
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("Hello world"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        // LatinWordSegmentation: [Hello][ ][world]. The space between two
        // Latin words is a WORD SPACE — untouched by autospace, stretchable
        // by justification.
        assertEquals(3, result.clusters.size)
        val wordSpace = result.clusters.single { it.text == " " }
        assertEquals(8f, wordSpace.advance)
        assertEquals(0, result.debug.autoSpaceDecisions.size)
    }

    @Test
    fun autoSpaceDisabledKeepsTypedSpacesAtHalfEm() {
        val engine = ExplainableStubParagraphLayoutEngine(
            clreqProfileResolver = ClreqProfileResolver {
                ClreqProfile.MainlandHorizontal.copy(
                    autoSpace = org.tiqian.clreq.AutoSpacePolicy.Disabled,
                )
            },
        )

        val result = engine.layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("中文 CJK 段落"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        // Disabled: space clusters keep their nominal 二分空 0.5em.
        val spaces = result.clusters.filter { it.text == " " }
        assertEquals(2, spaces.size)
        assertTrue(spaces.all { it.advance == 8f })
        assertEquals(0, result.debug.autoSpaceDecisions.size)
    }

    @Test
    fun usesFontDeclaredTypoBoxForCjkLineBox() {
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("提椠"),
                constraints = LayoutConstraints(maxWidth = 240f),
            ),
        )

        val line = result.lines.single()
        // ADR 0002 amendment: real baseline at the typo ascent (0.88em), not the
        // em centre. Box height = CjkBodyLineHeightDefault 1.5em = 24f; the
        // 0.5em leading splits evenly, so baseline = 4 + 14.08. The resolved
        // baseline is carried in a FloatArray, which Kotlin/JS rounds to true
        // Float precision, so compare with the same tolerance as RubyLayoutTest.
        assertEquals(18.08f, line.baseline, 0.001f)
        assertEquals(24f, line.bottom)
        val cjk = result.debug.metricDecisions.first { it.role == "CjkText" }
        assertEquals(14.08f, cjk.layoutAscent)
        assertEquals(1.92f, cjk.layoutDescent)
        assertEquals("IdeographicLow", cjk.baselineClass)
        assertEquals("IdeographicEmBox", cjk.metricBox)
    }

    @Test
    fun autoSpaceGapAtLineEndIsTrimmedLikeAnyLineEdgeBlank() {
        // text = "中文 AB 中文中文中文" segments to 中 文 [ ] [AB] [ ] 中….
        // Both spaces are CJK-adjacent gaps (advance 2). maxWidth=80 →
        // greedy line 0 = [中 文 ' ' AB ' '] (16+16+2+32+2=68); the trailing
        // space cluster sits at the line END and collapses entirely:
        // line adjusted width 68 → 66.
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("中文 AB 中文中文中文"),
                constraints = LayoutConstraints(maxWidth = 80f),
            ),
        )

        assertEquals(66f, result.lines.first().adjustedWidth)

        val collapse = result.debug.lineEdgeTrimDecisions
            .single { it.reason == "LineEdgeWordSpaceCollapse" }
        assertEquals("trailing", collapse.side)
        assertEquals(2f, collapse.trimAmount)
        assertEquals(5, collapse.clusterRange.start)
        assertEquals(6, collapse.clusterRange.end)
    }

    @Test
    fun emphasisSpanProducesDotAnchorsForHanAndSkipsPunctuation() {
        // "他强调：豆子新鲜最要紧，烘焙其次。" with emphasis over 4..16
        // (豆子新鲜最要紧，烘焙其次). Stub advances: every cluster 16f, no
        // justification → anchors at glyph centres; ， inside the span is
        // skipped per CLREQ; 。 is outside the span entirely.
        // maxWidth=128 wraps at 8 clusters/line.
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("他强调：豆子新鲜最要紧，烘焙其次。"),
                constraints = LayoutConstraints(maxWidth = 128f),
                decorations = listOf(
                    org.tiqian.core.DecorationSpan(
                        range = org.tiqian.core.TextRange(4, 16),
                        kind = org.tiqian.core.DecorationKind.Emphasis,
                    ),
                ),
            ),
        )

        val decisions = result.debug.decorationDecisions
        assertEquals(12, decisions.size)

        val applied = decisions.filter { it.applied }
        assertEquals(11, applied.size)
        assertTrue(applied.all { it.reason == "EmphasisDotOnHanText" })

        val comma = decisions.single { it.sourceText == "，" }
        assertEquals(false, comma.applied)
        assertEquals("clreq-no-dot-on-punctuation", comma.reason)

        // 。 (15-16) is outside the span — no decision at all.
        assertTrue(decisions.none { it.sourceText == "。" })

        // Anchor maths for 豆 (4-5): line 0 holds clusters 0..7, x offset of
        // index 4 = 4×16 = 64, glyph centre 64+8 = 72. Vertically, the
        // dot starts after the real CJK face descent (0.12em) plus the explicit
        // 0.1em gap; the anchor is another radius down. The 0.19em diameter is
        // final paint geometry, not a renderer-side approximation.
        val first = decisions.single { it.sourceText == "豆" }
        assertEquals(72f, first.anchorX)
        assertEquals(16f * 0.19f, first.dotDiameter, 0.01f)
        val line0Baseline = result.lines.first().baseline
        assertEquals(
            line0Baseline + 16f * 0.12f + 16f * 0.1f + first.dotDiameter / 2f,
            first.anchorY,
            0.01f,
        )
    }

    @Test
    fun emphasisDotGapIsExplicitAndIndependentOfLineHeight() {
        fun layout(lineHeight: Float) = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(
                    firstLineIndent = Ic(0f),
                    lineHeight = lineHeight,
                    emphasisDotGapEm = 0.25f,
                ),
                content = TiqianTextContent("着重"),
                constraints = LayoutConstraints(maxWidth = 128f),
                decorations = listOf(
                    org.tiqian.core.DecorationSpan(
                        range = org.tiqian.core.TextRange(0, 2),
                        kind = org.tiqian.core.DecorationKind.Emphasis,
                    ),
                ),
            ),
        )

        for (result in listOf(layout(24f), layout(48f))) {
            val first = result.debug.decorationDecisions.first { it.applied }
            assertEquals(
                result.lines.first().baseline + 16f * 0.12f + 16f * 0.25f + first.dotDiameter / 2f,
                first.anchorY,
                0.01f,
            )
        }
    }

    @Test
    fun mourningSpanIsKeptUnbrokenAndFramedPerLine() {
        // "悼念：王小明同志、张大同同志。" maxWidth=72: greedy would break at
        // cluster 4 (inside 王小明 3..5) — MourningSpanKeptUnbroken moves the
        // break to 3. Both names end up whole on single lines with one frame
        // segment each.
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                // Pin the exact measure (72 ∤ 16); this test is about the
                // mourning-span unbroken break, not the grid.
                paragraphStyle = ParagraphStyle(
                    firstLineIndent = Ic(0f),
                    lineLengthGrid = LineLengthGrid(enabled = false),
                ),
                content = TiqianTextContent("悼念：王小明同志、张大同同志。"),
                constraints = LayoutConstraints(maxWidth = 72f),
                decorations = listOf(
                    org.tiqian.core.DecorationSpan(
                        range = org.tiqian.core.TextRange(3, 6),
                        kind = org.tiqian.core.DecorationKind.Mourning,
                    ),
                    org.tiqian.core.DecorationSpan(
                        range = org.tiqian.core.TextRange(9, 12),
                        kind = org.tiqian.core.DecorationKind.Mourning,
                    ),
                ),
            ),
        )

        // Line 0 ends BEFORE the span (悼念： only) — the break moved.
        assertEquals(3, result.lines[0].range.end)

        val segments = result.debug.decorationSegments
        assertEquals(2, segments.size)
        for (seg in segments) {
            assertEquals("MourningSpanKeptUnbroken", seg.reason)
            assertEquals(false, seg.openStart)
            assertEquals(false, seg.openEnd)
        }
        // 王小明 starts its line: left edge at 0. The line is justified
        // (双齐 baseline): 3 boundaries share the 8px deficit (+8/3 each),
        // so the frame's right edge follows the spread glyphs — the last
        // cluster's trailing justify delta stays OUTSIDE the frame.
        val first = segments.single { it.sourceRange.start == 3 }
        assertEquals(0f, first.left)
        assertEquals(160f / 3f, first.right, 0.01f)
        // Frame hugs the CJK character face (字面, no margin):
        // baseline - 0.88em .. baseline + 0.12em.
        val line = result.lines[1]
        assertEquals(line.baseline - 14.08f, first.top, 0.01f)
        assertEquals(line.baseline + 1.92f, first.bottom, 0.01f)
    }

    @Test
    fun mourningSpanWiderThanMeasureSplitsWithOpenEdges() {
        // A 5-character name span at maxWidth=64 cannot fit one line: the
        // split fallback produces open-ended segments.
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("王小明大同先生"),
                constraints = LayoutConstraints(maxWidth = 64f),
                decorations = listOf(
                    org.tiqian.core.DecorationSpan(
                        range = org.tiqian.core.TextRange(0, 5),
                        kind = org.tiqian.core.DecorationKind.Mourning,
                    ),
                ),
            ),
        )

        val segments = result.debug.decorationSegments
        assertEquals(2, segments.size)
        assertTrue(segments.all { it.reason == "mourning-span-split-across-lines" })
        assertEquals(false, segments[0].openStart)
        assertEquals(true, segments[0].openEnd)
        assertEquals(true, segments[1].openStart)
        assertEquals(false, segments[1].openEnd)
    }

    @Test
    fun halfEmWordSpacesDoNotStretchUnderJustification() {
        // A 二分空 (0.5em) word space is already at CLREQ's word-space max
        // (≤0.5em final), so it does NOT stretch — the deficit fills via the
        // sino-western gap (CjkLatinSpace) and even CjkInterChar instead.
        // (A finer proportional space from a real font WOULD stretch; the
        // deterministic stub models U+0020 as 二分空.)
        // Pinned to PushOutOnly: this asserts the STRETCH tier behaviour, which
        // Auto would replace with 推入压缩 on this short line (ADR 0031).
        val result = ExplainableStubParagraphLayoutEngine(
            clreqProfileResolver = {
                ClreqProfile.MainlandHorizontal.let { p ->
                    p.copy(adjustment = p.adjustment.copy(lineAdjustment = LineAdjustmentStrategy.PushOutOnly))
                }
            },
        ).layout(
            LayoutInput(
                content = TiqianTextContent("AB CD EF中文中文中"),
                constraints = LayoutConstraints(maxWidth = 160f),
                paragraphStyle = org.tiqian.core.ParagraphStyle(
                    firstLineIndent = Ic(0f),
                ),
            ),
        )

        assertTrue(result.lines.size >= 2)
        val decision = result.debug.justificationDecisions.first()
        assertEquals(0f, decision.deficitAfter)
        // The 二分空 word spaces are at the cap → no WordSpace allocation.
        assertTrue(
            decision.allocations.none { it.kind == "WordSpace" },
            "二分空 word spaces must not stretch: ${decision.allocations}",
        )
        assertTrue(decision.allocations.isNotEmpty())
        assertEquals(160f, result.lines.first().visualWidth)
    }

    @Test
    fun justifyStretchesPunctuationLatinBoundaryInTierThree() {
        // CLREQ tier ③「剩余所有字符间距」includes 标点↔西文 (only 不可断标点 +
        // 连接号/分隔号 excluded). Line 0 = 中文中文话：The — the ：|The boundary
        // takes a tier-③ share like every other 字符间距.
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("中文中文话：The quick brown fox jumps"),
                constraints = LayoutConstraints(maxWidth = 160f),
            ),
        )

        assertTrue(result.lines.size > 1)
        val line0 = result.debug.justificationDecisions
            .first { it.lineRange.start == 0 }
        val colonRange = org.tiqian.core.TextRange(5, 6)
        assertTrue(
            line0.allocations.any { it.clusterRange == colonRange && it.kind == "CjkInterChar" },
            "：|The boundary must stretch in tier ③: ${line0.allocations}",
        )
        assertEquals(0f, line0.deficitAfter)
    }

    @Test
    fun blockIndentInsetsEveryLine() {
        // 段落缩排 (CLREQ §6.2.1.2): blockIndent insets ALL lines (引用/诗词块).
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                content = TiqianTextContent("中文中文中文中文中文中文"),
                constraints = LayoutConstraints(maxWidth = 100f),
                paragraphStyle = ParagraphStyle(
                    blockIndent = Ic(2f),
                    firstLineIndent = Ic(0f),
                    lineLengthGrid = org.tiqian.core.LineLengthGrid(enabled = false),
                ),
            ),
        )
        assertTrue(result.lines.size >= 2)
        assertTrue(result.lines.all { it.indent == 32f }, "every line inset 2em: ${result.lines.map { it.indent }}")
    }

    @Test
    fun hangingIndentFlushesFirstLineAndInsetsRest() {
        // 凸排 (CLREQ §6.2.1.1): blockIndent=2, firstLineIndent=-2 → 首行齐头、
        // 次行起缩 2 字（对话/列表/法条）。
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                content = TiqianTextContent("中文中文中文中文中文中文"),
                constraints = LayoutConstraints(maxWidth = 100f),
                paragraphStyle = ParagraphStyle(
                    blockIndent = Ic(2f),
                    firstLineIndent = Ic(-2f),
                    lineLengthGrid = org.tiqian.core.LineLengthGrid(enabled = false),
                ),
            ),
        )
        assertTrue(result.lines.size >= 2)
        assertEquals(0f, result.lines.first().indent)
        assertTrue(result.lines.drop(1).all { it.indent == 32f }, "rest inset 2em: ${result.lines.map { it.indent }}")
    }

    @Test
    fun justifyFillsSaturatedLineWithUncappedEvenShare() {
        // CLREQ 平均拉大字距 has no upper bound: when word spaces and
        // sino-western gaps are exhausted, the remaining deficit spreads
        // evenly over hanzi boundaries past the old 0.25em cap — a justified
        // line must reach maxWidth exactly, never stop short.
        // Stub: 4 hanzi (64) then a 7-char Latin word "Network" (112) that fits
        // the measure (≤160, NOT hard-broken) but overflows after the hanzi and
        // wraps whole — line 0 deficit = 160 - 64 = 96 over 3 hanzi boundaries =
        // 32 each, far past the old 4px cap.
        val result = ExplainableStubParagraphLayoutEngine(hyphenator = NoHyphenator).layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("中文中文Network中文"),
                constraints = LayoutConstraints(maxWidth = 160f),
            ),
        )

        val decision = result.debug.justificationDecisions.first { it.lineRange.start == 0 }
        assertEquals(0f, decision.deficitAfter)
        assertEquals(160f, result.lines[0].visualWidth)
        // Even share: all CjkInterChar deltas equal, beyond the old cap.
        val deltas = decision.allocations.filter { it.kind == "CjkInterChar" }.map { it.delta }
        assertEquals(3, deltas.size)
        assertTrue(deltas.all { kotlin.math.abs(it - 32f) < 0.01f }, "deltas=$deltas")
    }

    @Test
    fun autoSpaceDigitModeIsWiredIndependentlyOfLetterMode() {
        // CLREQ distinguishes 字母 from 数字: cjkDigit gates CJK↔digit gaps
        // separately. cjkLatin=Insert, cjkDigit=Disabled → 中A gets a gap,
        // 中5 does not (mode keyed on the boundary-adjacent char).
        val result = ExplainableStubParagraphLayoutEngine(
            clreqProfileResolver = ClreqProfileResolver {
                ClreqProfile.MainlandHorizontal.copy(
                    autoSpace = org.tiqian.clreq.AutoSpacePolicy(
                        cjkLatin = org.tiqian.clreq.AutoSpaceMode.Insert,
                        cjkDigit = org.tiqian.clreq.AutoSpaceMode.Disabled,
                    ),
                )
            },
        ).layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("中A中5中"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        val letterGap = result.debug.autoSpaceDecisions.filter {
            result.clusters.any { c -> c.range == it.clusterRange && c.text == "A" }
        }
        val digitGap = result.debug.autoSpaceDecisions.filter {
            result.clusters.any { c -> c.range == it.clusterRange && c.text == "5" }
        }
        assertTrue(letterGap.isNotEmpty(), "中↔letter must still gap")
        assertTrue(digitGap.isEmpty(), "中↔digit must NOT gap when cjkDigit=Disabled")
    }

    @Test
    fun lineLengthGridFloorsMeasureToWholeCharsAndOffsetsBody() {
        // 8 hanzi (128px) at maxWidth=104 (6.5 字, fontSize 16). Grid floors
        // the measure to 6 字 = 96; greedy then breaks 6 + 2.
        fun layoutWith(grid: LineLengthGrid) =
            ExplainableStubParagraphLayoutEngine().layout(
                LayoutInput(
                    paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f), lineLengthGrid = grid),
                    content = TiqianTextContent("中文中文中文中文"),
                    constraints = LayoutConstraints(maxWidth = 104f),
                ),
            )

        // Default: enabled, body at start (follows default Start last-line).
        val start = layoutWith(LineLengthGrid())
        val g = start.debug.lineLengthGridDecision!!
        assertTrue(g.enabled)
        assertEquals(6, g.cells)
        assertEquals(96f, g.measure)
        assertEquals(8f, g.slack)
        assertEquals(0f, g.bodyOffset)
        assertEquals(2, start.lines.size)
        assertEquals(96f, start.lines[0].visualWidth) // justified to the floored measure
        assertEquals(0f, start.lines[0].indent)

        // bodyAlignment override (Center) shifts the WHOLE body by slack/2,
        // independently of the (Start) last-line alignment.
        val centered = layoutWith(LineLengthGrid(bodyAlignment = org.tiqian.core.LastLineAlignment.Center))
        assertEquals(4f, centered.debug.lineLengthGridDecision!!.bodyOffset)
        assertEquals(4f, centered.lines[0].indent)
        assertEquals(4f, centered.lines[1].indent) // last line, Start within body → only the body offset
    }

    @Test
    fun lineLengthGridCanBeBypassedForExactWidths() {
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(
                    firstLineIndent = Ic(0f),
                    lineLengthGrid = LineLengthGrid(enabled = false),
                ),
                content = TiqianTextContent("中文中文中文中文"),
                constraints = LayoutConstraints(maxWidth = 104f),
            ),
        )
        val g = result.debug.lineLengthGridDecision!!
        assertEquals(false, g.enabled)
        assertEquals(104f, g.measure) // raw container, no flooring
        assertEquals(0f, g.bodyOffset)
        assertEquals(104f, result.lines[0].visualWidth) // justified to the full 104
    }

    @Test
    fun interlinearLinesGetPerItemSegmentsWithAdjacentShortening() {
        // 行间线 (ADR 0024): one segment per annotated item, length =
        // the text's outer frame, hugging the face below the baseline
        // (+0.18em). 顾炎武|王夫之 are adjacent: each ADJACENT edge pulls
        // back 1/16em (=1px @16), outer edges stay.
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("屈原写下离骚，顾炎武王夫之并称。"),
                constraints = LayoutConstraints(maxWidth = 224f),
                decorations = listOf(
                    org.tiqian.core.DecorationSpan(
                        range = org.tiqian.core.TextRange(0, 2),
                        kind = org.tiqian.core.DecorationKind.ProperNoun,
                    ),
                    org.tiqian.core.DecorationSpan(
                        range = org.tiqian.core.TextRange(4, 6),
                        kind = org.tiqian.core.DecorationKind.BookTitle,
                    ),
                    org.tiqian.core.DecorationSpan(
                        range = org.tiqian.core.TextRange(7, 10),
                        kind = org.tiqian.core.DecorationKind.ProperNoun,
                    ),
                    org.tiqian.core.DecorationSpan(
                        range = org.tiqian.core.TextRange(10, 13),
                        kind = org.tiqian.core.DecorationKind.ProperNoun,
                    ),
                ),
            ),
        )

        val segments = result.debug.decorationSegments
        assertEquals(4, segments.size)
        val baseline = result.lines[0].baseline
        val lineY = baseline + 16f * 0.18f

        val quyuan = segments.single { it.sourceRange.start == 0 }
        assertEquals("ProperNoun", quyuan.kind)
        assertEquals(0f, quyuan.left)
        assertEquals(32f, quyuan.right)
        assertEquals(lineY, quyuan.top, 0.01f)
        assertEquals(lineY, quyuan.bottom, 0.01f)
        assertEquals("InterlinearLinePerAnnotatedItem", quyuan.reason)

        val lisao = segments.single { it.sourceRange.start == 4 }
        assertEquals("BookTitle", lisao.kind)
        assertEquals(64f, lisao.left)
        assertEquals(96f, lisao.right)
        assertEquals(baseline + 16f * 0.24f, lisao.top, 0.01f)

        // Adjacent pair: 顾炎武 right edge −1, 王夫之 left edge +1; outer
        // edges keep the text's outer frame.
        val guyanwu = segments.single { it.sourceRange.start == 7 }
        assertEquals(112f, guyanwu.left)
        assertEquals(159f, guyanwu.right)
        assertTrue(guyanwu.reason.endsWith("AdjacentInterlinearLineShortening"))
        val wangfuzhi = segments.single { it.sourceRange.start == 10 }
        assertEquals(161f, wangfuzhi.left)
        assertEquals(208f, wangfuzhi.right)

        // At the default 0.1em face gap, 先线后点 holds structurally:
        // line y = +0.18em; dot ink starts at face bottom + gap = +0.22em.
        // The spacing floor applies (no explicit lineHeight).
        assertEquals(24f, result.lines[0].bottom - result.lines[0].top)
    }

    @Test
    fun interlinearMarksRaiseAutoLineHeightToSpacingFloor() {
        // InterlinearMarkLineSpacingFloor (CLREQ 5.6.1.1): with 着重号 present, the
        // line spacing can't drop below 1/2 字号. The 0.5em floor (→24) coincides
        // with the 1.5em body default (→24), so an auto/generous height already
        // clears it; only an explicit lineHeight tighter than the floor is clamped.
        fun layoutWith(lineHeight: Float?) =
            ExplainableStubParagraphLayoutEngine().layout(
                LayoutInput(
                    paragraphStyle = ParagraphStyle(
                        firstLineIndent = Ic(0f),
                        lineHeight = lineHeight,
                    ),
                    content = TiqianTextContent("豆子新鲜"),
                    constraints = LayoutConstraints(maxWidth = 240f),
                    decorations = listOf(
                        org.tiqian.core.DecorationSpan(
                            range = org.tiqian.core.TextRange(0, 4),
                            kind = org.tiqian.core.DecorationKind.Emphasis,
                        ),
                    ),
                ),
            )

        // Auto height: the 1.5em body default (→24) already provides the floor.
        val auto = layoutWith(null)
        assertEquals(24f, auto.lines.single().bottom)
        assertEquals(false, auto.debug.lineSpacingDecision?.floorApplied)

        // Explicit 20 < the no-overlap minimum (16+8) → clamped up by the floor.
        val clamped = layoutWith(20f)
        assertEquals(24f, clamped.lines.single().bottom)
        assertEquals(true, clamped.debug.lineSpacingDecision?.floorApplied)

        val generous = layoutWith(28f)
        assertEquals(28f, generous.lines.single().bottom)
        assertEquals(false, generous.debug.lineSpacingDecision?.floorApplied)

        // No marks → CjkBodyLineHeightDefault 1.5em (24f); the decision records it.
        val plain = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("豆子新鲜"),
                constraints = LayoutConstraints(maxWidth = 240f),
            ),
        )
        assertEquals(24f, plain.lines.single().bottom)
        assertEquals("CjkBodyLineHeightDefault", plain.debug.lineSpacingDecision?.reason)
        assertEquals(false, plain.debug.lineSpacingDecision?.floorApplied)
    }

    @Test
    fun firstLineIndentShrinksFirstLineMeasureOnly() {
        // ParagraphFirstLineIndent: 12 hanzi at maxWidth 160, indent pinned to
        // 2em (32) — 10 字 is below the adaptive threshold, so pin explicitly to
        // exercise the inset mechanism at 2 字. Line 0 measure = 128 → 8 chars;
        // line 1 uses the full 160. LineBox carries the inset; width fields
        // exclude it; result width accounts for indent + visual.
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(2f)),
                content = TiqianTextContent("中文中文中文中文中文中文"),
                constraints = LayoutConstraints(maxWidth = 160f),
            ),
        )

        assertEquals(2, result.lines.size)
        assertEquals(32f, result.lines[0].indent)
        assertEquals(0f, result.lines[1].indent)
        assertEquals(8, result.lines[0].range.end - result.lines[0].range.start)
        assertEquals(128f, result.lines[0].visualWidth)
        // Widest extent = indent + first line visual = 160.
        assertEquals(160f, result.size.width)
    }

    @Test
    fun firstLineIndentAdaptsToMeasureAndCanBeOverridden() {
        // MeasureAdaptiveFirstLineIndent (ADR 0021 amendment): default narrows
        // to 1 字 on short measures (< 14 字), 2 字 otherwise. CLREQ:「段首缩排
        // 以两个汉字的空间为标准」（宽行）；窄栏常缩一字.
        fun indentOf(engine: ExplainableStubParagraphLayoutEngine, width: Float, style: ParagraphStyle? = null) =
            engine.layout(
                LayoutInput(
                    paragraphStyle = style ?: ParagraphStyle(),
                    content = TiqianTextContent("中文"),
                    constraints = LayoutConstraints(maxWidth = width),
                ),
            )

        // Long line (15 字 ≥ 14): default 2 字.
        val long = indentOf(ExplainableStubParagraphLayoutEngine(), 240f)
        assertEquals(32f, long.lines.single().indent)
        assertEquals("MeasureAdaptiveFirstLineIndent", long.debug.firstLineIndentDecision!!.source)
        assertEquals(2f, long.debug.firstLineIndentDecision!!.resolvedEm)

        // Short line (10 字 < 14): default narrows to 1 字.
        val short = indentOf(ExplainableStubParagraphLayoutEngine(), 160f)
        assertEquals(16f, short.lines.single().indent)
        assertEquals(1f, short.debug.firstLineIndentDecision!!.resolvedEm)

        // Decoupled from hanging: still adapts under KinsokuMode.Fixed.
        assertEquals(16f, indentOf(fixedBasicEngine(), 160f).lines.single().indent)

        // Explicit firstLineIndent overrides the adaptive default both ways.
        assertEquals(
            0f,
            indentOf(ExplainableStubParagraphLayoutEngine(), 240f, ParagraphStyle(firstLineIndent = Ic(0f)))
                .lines.single().indent,
        )
        val pinned = indentOf(ExplainableStubParagraphLayoutEngine(), 160f, ParagraphStyle(firstLineIndent = Ic(2f)))
        assertEquals(32f, pinned.lines.single().indent) // 2 字 even on the short line
        assertEquals("Explicit", pinned.debug.firstLineIndentDecision!!.source)
    }
}
