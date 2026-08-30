package org.tiqian.layout

import org.tiqian.core.AutoSpaceDecisionInfo
import org.tiqian.core.Cluster
import org.tiqian.core.ClusterGeometryDecisionInfo
import org.tiqian.core.DecorationKind
import org.tiqian.core.DecorationSpan
import org.tiqian.core.Glyph
import org.tiqian.core.GlyphRun
import org.tiqian.core.InlineBoxOuterSpacing
import org.tiqian.core.InlineBoxSpan
import org.tiqian.core.InlineObjectBoundaryAdjustment
import org.tiqian.core.InlineObjectPreferredStretch
import org.tiqian.core.InlineObjectPreferredStretchKind
import org.tiqian.core.InlineObjectSpan
import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LayoutInput
import org.tiqian.core.LineBox
import org.tiqian.core.LineEndReason
import org.tiqian.core.ParagraphStyle
import org.tiqian.core.Rect
import org.tiqian.core.RubyKind
import org.tiqian.core.RubySpan
import org.tiqian.core.ShapingDecisionInfo
import org.tiqian.core.TextRange
import org.tiqian.core.TextStyle
import org.tiqian.core.TiqianTextContent
import org.tiqian.font.BaselinePolicy
import org.tiqian.font.FontMetricsPolicy
import org.tiqian.font.FontMetricsRequest
import org.tiqian.font.FontRole
import org.tiqian.font.LayoutFontMetrics
import org.tiqian.font.RawFontMetrics
import org.tiqian.shaping.ExplainableStubTextShaper
import org.tiqian.shaping.ShapingInput
import org.tiqian.shaping.ShapingResult
import org.tiqian.shaping.TextShaper
import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.assertNotNull
import org.tiqian.test.trace.assertTrue
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

class AnnotationGeometryStageCoverageTest {
    private val testTrace = TestTraceRecorder("AnnotationGeometryStageCoverageTest")


    private class InkBoundsTextShaper(private val delegate: TextShaper = ExplainableStubTextShaper()) : TextShaper {
        override fun shape(input: ShapingInput): ShapingResult {
            val res = delegate.shape(input)
            val runsWithBounds = res.glyphRuns.map { run ->
                val glyphsWithBounds = run.glyphs.map { g ->
                    g.copy(bounds = Rect(left = 1.0f, top = 2.0f, right = 9.0f, bottom = 10.0f))
                }
                run.copy(glyphs = glyphsWithBounds)
            }
            return res.copy(glyphRuns = runsWithBounds)
        }
    }

    @Test
    fun inlineObjectDecisionsWithPreferredStretchAndFixed() {
        testTrace.section("inlineObjectDecisionsWithPreferredStretchAndFixed")
        val engine = ExplainableStubParagraphLayoutEngine()
        val text = "前置文本【嵌入对象】后置文本"
        val objWithStretch = InlineObjectSpan(
            range = TextRange(4, 5),
            advance = 30.0f,
            ascent = 12.0f,
            descent = 4.0f,
            leadingBoundary = InlineObjectBoundaryAdjustment(
                participatesInUniformStretch = true,
                preferredStretch = InlineObjectPreferredStretch(
                    naturalWidth = 10.0f,
                    targetWidth = 15.0f,
                    kind = InlineObjectPreferredStretchKind.PunctuationTrailing,
                ),
                preventsLineBreak = true,
            ),
            trailingBoundary = InlineObjectBoundaryAdjustment(
                participatesInUniformStretch = true,
                preferredStretch = InlineObjectPreferredStretch(
                    naturalWidth = 10.0f,
                    targetWidth = 20.0f,
                    kind = InlineObjectPreferredStretchKind.Relation,
                ),
                preventsLineBreak = false,
                shrinkCapacity = 3.0f,
                lineEndDiscardableAdvance = 2.0f,
            ),
        )
        val objFixed = InlineObjectSpan(
            range = TextRange(6, 7),
            advance = 20.0f,
            ascent = 10.0f,
            descent = 2.0f,
            leadingBoundary = InlineObjectBoundaryAdjustment.Fixed,
            trailingBoundary = InlineObjectBoundaryAdjustment.Fixed,
        )

        val input = LayoutInput(
            content = TiqianTextContent(text),
            inlineObjects = listOf(objWithStretch, objFixed),
            constraints = LayoutConstraints(maxWidth = 300.0f),
        )
        val result = engine.layout(input)
        assertNotNull(result)
        assertTrue(result.lines.isNotEmpty())
    }

    @Test
    fun decorationDecisionsEmphasisOnHanPunctuationAndWestern() {
        testTrace.section("decorationDecisionsEmphasisOnHanPunctuationAndWestern")
        val engine = ExplainableStubParagraphLayoutEngine()
        val text = "汉字，。English"
        val input = LayoutInput(
            content = TiqianTextContent(text),
            decorations = listOf(
                DecorationSpan(range = TextRange(0, text.length), kind = DecorationKind.Emphasis),
            ),
            paragraphStyle = ParagraphStyle(emphasisDotGapEm = 0.2f),
            constraints = LayoutConstraints(maxWidth = 300.0f),
        )
        val result = engine.layout(input)
        assertNotNull(result)
    }

    @Test
    fun decorationSegmentsMourningProperNounBookTitleAndShortening() {
        testTrace.section("decorationSegmentsMourningProperNounBookTitleAndShortening")
        val engine = ExplainableStubParagraphLayoutEngine()
        val text = "张三李四王五赵六钱七孙八周吴郑王"
        val input = LayoutInput(
            content = TiqianTextContent(text),
            decorations = listOf(
                DecorationSpan(range = TextRange(0, 2), kind = DecorationKind.ProperNoun),
                DecorationSpan(range = TextRange(2, 4), kind = DecorationKind.ProperNoun),
                DecorationSpan(range = TextRange(4, 8), kind = DecorationKind.BookTitle),
                DecorationSpan(range = TextRange(8, 12), kind = DecorationKind.Mourning),
                DecorationSpan(range = TextRange(0, 16), kind = DecorationKind.Mourning),
            ),
            constraints = LayoutConstraints(maxWidth = 120.0f),
        )
        val result = engine.layout(input)
        assertNotNull(result)
    }

    @Test
    fun decorationSegmentsLeadingAndTrailingBlanks() {
        testTrace.section("decorationSegmentsLeadingAndTrailingBlanks")
        val engine = ExplainableStubParagraphLayoutEngine()
        val text = "「开头」中文 English 混排【结束】"
        val input = LayoutInput(
            content = TiqianTextContent(text),
            decorations = listOf(
                DecorationSpan(range = TextRange(0, text.length), kind = DecorationKind.ProperNoun),
            ),
            constraints = LayoutConstraints(maxWidth = 150.0f),
        )
        val result = engine.layout(input)
        assertNotNull(result)
    }

    @Test
    fun rubyDecisionsPinyinSingleAndSplitLines() {
        testTrace.section("rubyDecisionsPinyinSingleAndSplitLines")
        val engine = ExplainableStubParagraphLayoutEngine()
        val text = "这是一个很长很长的段落用于测试拼音行间注跨行"
        val input = LayoutInput(
            content = TiqianTextContent(text),
            rubySpans = listOf(
                RubySpan(baseRange = TextRange(0, 2), text = "zhèshì", kind = RubyKind.Pinyin, locale = "zh-Latn"),
                RubySpan(baseRange = TextRange(2, 6), text = "yīgehěncháng", kind = RubyKind.Pinyin),
                RubySpan(baseRange = TextRange(6, 12), text = "chángdeduànluò", kind = RubyKind.Pinyin),
            ),
            constraints = LayoutConstraints(maxWidth = 100.0f),
        )
        val result = engine.layout(input)
        assertNotNull(result)
    }

    @Test
    fun bopomofoDecisionsAllTonesAndSymbolCounts() {
        testTrace.section("bopomofoDecisionsAllTonesAndSymbolCounts")
        val engine = ExplainableStubParagraphLayoutEngine(textShaper = InkBoundsTextShaper())
        val text = "一二三四五六七八九十甲乙丙丁戊己庚辛"
        val rubySpans = listOf(
            RubySpan(baseRange = TextRange(0, 1), text = "˙ㄅ", kind = RubyKind.Bopomofo, locale = "zh-Bopo"),
            RubySpan(baseRange = TextRange(1, 2), text = "˙ㄅㄆ", kind = RubyKind.Bopomofo),
            RubySpan(baseRange = TextRange(2, 3), text = "˙ㄅㄆㄇ", kind = RubyKind.Bopomofo),
            RubySpan(baseRange = TextRange(3, 4), text = "ㄅˊ", kind = RubyKind.Bopomofo),
            RubySpan(baseRange = TextRange(4, 5), text = "ㄅㄆˊ", kind = RubyKind.Bopomofo),
            RubySpan(baseRange = TextRange(5, 6), text = "ㄅㄆㄇˊ", kind = RubyKind.Bopomofo),
            RubySpan(baseRange = TextRange(6, 7), text = "ㄅˇ", kind = RubyKind.Bopomofo),
            RubySpan(baseRange = TextRange(7, 8), text = "ㄅㄆˇ", kind = RubyKind.Bopomofo),
            RubySpan(baseRange = TextRange(8, 9), text = "ㄅㄆㄇˇ", kind = RubyKind.Bopomofo),
            RubySpan(baseRange = TextRange(9, 10), text = "ㄅˋ", kind = RubyKind.Bopomofo),
            RubySpan(baseRange = TextRange(10, 11), text = "ㄅㄆˋ", kind = RubyKind.Bopomofo),
            RubySpan(baseRange = TextRange(11, 12), text = "ㄅㄆㄇˋ", kind = RubyKind.Bopomofo),
            RubySpan(baseRange = TextRange(12, 13), text = "ㄅ", kind = RubyKind.Bopomofo),
            RubySpan(baseRange = TextRange(13, 14), text = "ㄅㄆ", kind = RubyKind.Bopomofo),
            RubySpan(baseRange = TextRange(14, 15), text = "ㄅㄆㄇ", kind = RubyKind.Bopomofo),
        )

        val input = LayoutInput(
            content = TiqianTextContent(text),
            rubySpans = rubySpans,
            constraints = LayoutConstraints(maxWidth = 300.0f),
        )
        val result = engine.layout(input)
        assertNotNull(result)
    }

    @Test
    fun directResolveAnnotationGeometryFallbackBranches() {
        testTrace.section("directResolveAnnotationGeometryFallbackBranches")
        val engine = ExplainableStubParagraphLayoutEngine()
        val text = "汉字，测试English"
        val input = LayoutInput(
            content = TiqianTextContent(text),
            decorations = listOf(
                DecorationSpan(range = TextRange(0, 2), kind = DecorationKind.Emphasis), // CjkText
                DecorationSpan(range = TextRange(2, 3), kind = DecorationKind.Emphasis), // CjkPunctuation
                DecorationSpan(range = TextRange(5, 12), kind = DecorationKind.Emphasis), // LatinText
                DecorationSpan(range = TextRange(0, 4), kind = DecorationKind.ProperNoun),
            ),
            constraints = LayoutConstraints(maxWidth = 300.0f),
        )
        val clusters = listOf(
            Cluster(range = TextRange(0, 2), text = "汉字", displayText = "汉字", fontKey = "k", advance = 32.0f),
            Cluster(range = TextRange(2, 3), text = "，", displayText = "，", fontKey = "k", advance = 16.0f),
            Cluster(range = TextRange(3, 5), text = "测试", displayText = "测试", fontKey = "k", advance = 32.0f),
            Cluster(range = TextRange(5, 12), text = "English", displayText = "English", fontKey = "k", advance = 56.0f),
        )
        val lineBoxes = listOf(
            LineBox(
                range = TextRange(0, 5),
                clusterRange = 0..2,
                baseline = 16.0f,
                top = 0.0f,
                bottom = 20.0f,
                naturalWidth = 80.0f,
                adjustedWidth = 80.0f,
                visualWidth = 80.0f,
                indent = 0.0f,
                endReason = LineEndReason.AutoWrap,
            ),
            LineBox(
                range = TextRange(5, 12),
                clusterRange = 3..3,
                baseline = 36.0f,
                top = 20.0f,
                bottom = 40.0f,
                naturalWidth = 56.0f,
                adjustedWidth = 56.0f,
                visualWidth = 56.0f,
                indent = 0.0f,
                endReason = LineEndReason.MandatoryBreak,
            ),
        )
        val lineSolution = LineSolution(
            lines = listOf(
                LineCandidate(
                    clusterRange = 0..2,
                    sourceRange = TextRange(0, 5),
                    naturalWidth = 80.0f,
                    adjustedWidth = 80.0f,
                    endReason = LineEndReason.AutoWrap,
                ),
                LineCandidate(
                    clusterRange = 3..3,
                    sourceRange = TextRange(5, 12),
                    naturalWidth = 56.0f,
                    adjustedWidth = 56.0f,
                    endReason = LineEndReason.MandatoryBreak,
                ),
            ),
        )
        val clreqProfile = engine.clreqProfileResolver.resolve(input.profileId)

        val inlineObject1 = InlineObjectSpan(
            range = TextRange(0, 2),
            advance = 32.0f,
            ascent = 12.0f,
            descent = 4.0f,
            leadingBoundary = InlineObjectBoundaryAdjustment(
                preferredStretch = InlineObjectPreferredStretch(
                    naturalWidth = 10.0f,
                    targetWidth = 15.0f,
                    kind = InlineObjectPreferredStretchKind.PunctuationTrailing,
                ),
            ),
        )
        val inlineObject2 = InlineObjectSpan(
            range = TextRange(5, 12),
            advance = 56.0f,
            ascent = 12.0f,
            descent = 4.0f,
            trailingBoundary = InlineObjectBoundaryAdjustment(
                preferredStretch = InlineObjectPreferredStretch(
                    naturalWidth = 10.0f,
                    targetWidth = 20.0f,
                    kind = InlineObjectPreferredStretchKind.Relation,
                ),
            ),
        )
        val inlineObjectNotInLine = InlineObjectSpan(
            range = TextRange(99, 100),
            advance = 10.0f,
            ascent = 8.0f,
            descent = 2.0f,
        )

        val geomDecision = ClusterGeometryDecisionInfo(
            range = TextRange(0, 2),
            sourceText = "汉字",
            displayText = "汉字",
            baseAdvance = 32.0f,
            bodyWidth = 32.0f,
            leadingGlueNatural = 4.0f,
            leadingGlueConsumed = 2.0f,
            trailingGlueNatural = 4.0f,
            trailingGlueConsumed = 2.0f,
            justificationDelta = 0.0f,
            resolvedAdvance = 32.0f,
            source = "test",
            reason = "test",
        )

        // 1. Call with empty metricDecisions to trigger fallback faceDescent (line 317) and fallbackBaseAscent (line 538)
        val res1 = engine.resolveAnnotationGeometry(
            input = input,
            fontSize = 16.0f,
            inlineObjectByClusterIndex = mapOf(0 to inlineObject1, 3 to inlineObject2, 99 to inlineObjectNotInLine),
            lineSolution = lineSolution,
            clreqProfile = clreqProfile,
            geometryDecisions = listOf(geomDecision),
            autoSpaceDecisions = listOf(
                AutoSpaceDecisionInfo(
                    clusterRange = TextRange(0, 2),
                    side = "leading",
                    boundaryRole = "Wide",
                    mode = "Normal",
                    charactersAffected = 1,
                    reductionPerChar = 0.0f,
                    totalReduction = 0.0f,
                    reason = "test",
                ),
                AutoSpaceDecisionInfo(
                    clusterRange = TextRange(2, 3),
                    side = "leading",
                    boundaryRole = "Wide",
                    mode = "Normal",
                    charactersAffected = 1,
                    reductionPerChar = 0.0f,
                    totalReduction = 0.0f,
                    reason = "test",
                ),
                AutoSpaceDecisionInfo(
                    clusterRange = TextRange(3, 5),
                    side = "trailing",
                    boundaryRole = "Wide",
                    mode = "Normal",
                    charactersAffected = 1,
                    reductionPerChar = 0.0f,
                    totalReduction = 0.0f,
                    reason = "test",
                ),
                AutoSpaceDecisionInfo(
                    clusterRange = TextRange(5, 12),
                    side = "trailing",
                    boundaryRole = "Wide",
                    mode = "Normal",
                    charactersAffected = 1,
                    reductionPerChar = 0.0f,
                    totalReduction = 0.0f,
                    reason = "test",
                ),
            ),
            visibleLineRanges = listOf(0..2, 3..3),
            lines = lineBoxes,
            finalClusters = clusters,
            clusterRoles = listOf(FontRole.CjkText, FontRole.CjkPunctuation, FontRole.CjkText, FontRole.LatinText),
            justifyDeltaByCluster = mapOf(0 to 2.0f),
            rubyAndBopomofoSpread = mapOf(0 to 4.0f),
            metricDecisions = emptyList(),
            pinyinSpans = listOf(
                RubySpan(baseRange = TextRange(0, 2), text = "hànzì", kind = RubyKind.Pinyin, locale = "zh-Latn"),
                RubySpan(baseRange = TextRange(3, 5), text = "cèshì", kind = RubyKind.Pinyin),
            ),
            naturalClusters = clusters,
            rubyFontGeometryBySpan = mapOf(
                RubySpan(baseRange = TextRange(0, 2), text = "hànzì", kind = RubyKind.Pinyin, locale = "zh-Latn") to RubyFontGeometry(
                    width = 20.0f,
                    ascent = 8.0f,
                    descent = 2.0f,
                    requiredExtent = 10.0f,
                    glyphs = emptyList(),
                ),
                RubySpan(baseRange = TextRange(3, 5), text = "cèshì", kind = RubyKind.Pinyin) to RubyFontGeometry(
                    width = 20.0f,
                    ascent = 8.0f,
                    descent = 2.0f,
                    requiredExtent = 10.0f,
                    glyphs = emptyList(),
                ),
            ),
            rubyStackGap = 0.0f,
            baseAscent = 16.0f,
            rubyFontSize = 8.0f,
            rubyFontWeight = 400,
            baseDescent = 4.0f,
            bopomofoFontWeightAt = { 400 },
        )
        assertNotNull(res1)
        assertEquals(3, res1.inlineObjectDecisions.size)
        assertEquals(-1, res1.inlineObjectDecisions.last().lineIndex)

        // 2. Call with matching metricDecisions having layoutMetrics
        val metricDecision = ClusterMetricDecision(
            range = TextRange(0, 2),
            sourceText = "汉字",
            request = FontMetricsRequest(fontKey = "k", fontSize = 16.0f, role = FontRole.CjkText, locale = "zh-Hans"),
            rawMetrics = RawFontMetrics(ascent = 14.0f, descent = 4.0f),
            layoutMetrics = LayoutFontMetrics(
                ascent = 14.0f,
                descent = 4.0f,
                baselineOffset = 0.0f,
                policy = FontMetricsPolicy.Raw,
                baselinePolicy = BaselinePolicy.Alphabetic,
            ),
        )
        val res2 = engine.resolveAnnotationGeometry(
            input = input,
            fontSize = 16.0f,
            inlineObjectByClusterIndex = emptyMap(),
            lineSolution = lineSolution,
            clreqProfile = clreqProfile,
            geometryDecisions = emptyList(),
            autoSpaceDecisions = emptyList(),
            visibleLineRanges = listOf(0..2, 3..3),
            lines = lineBoxes,
            finalClusters = clusters,
            clusterRoles = listOf(FontRole.CjkText, FontRole.CjkPunctuation, FontRole.CjkText, FontRole.LatinText),
            justifyDeltaByCluster = emptyMap(),
            rubyAndBopomofoSpread = emptyMap(),
            metricDecisions = listOf(metricDecision),
            pinyinSpans = listOf(
                RubySpan(baseRange = TextRange(0, 2), text = "hànzì", kind = RubyKind.Pinyin),
            ),
            naturalClusters = clusters,
            rubyFontGeometryBySpan = mapOf(
                RubySpan(baseRange = TextRange(0, 2), text = "hànzì", kind = RubyKind.Pinyin) to RubyFontGeometry(
                    width = 20.0f,
                    ascent = 8.0f,
                    descent = 2.0f,
                    requiredExtent = 10.0f,
                    glyphs = emptyList(),
                ),
            ),
            rubyStackGap = 0.0f,
            baseAscent = 16.0f,
            rubyFontSize = 8.0f,
            rubyFontWeight = 400,
            baseDescent = 4.0f,
            bopomofoFontWeightAt = { 400 },
        )
        assertNotNull(res2)
    }

    @Test
    fun bopomofoDecisionsMultiGlyphMinMaxAndEmptyPlacements() {
        testTrace.section("bopomofoDecisionsMultiGlyphMinMaxAndEmptyPlacements")
        val multiGlyphShaper = object : TextShaper {
            val delegate = ExplainableStubTextShaper()
            override fun shape(input: ShapingInput): ShapingResult {
                val res = delegate.shape(input)
                val runsWithBounds = res.glyphRuns.map { run ->
                    val g1 = Glyph(
                        id = 1u,
                        clusterRange = input.range,
                        advance = 4.0f,
                        x = 0.0f,
                        bounds = Rect(left = 5.0f, top = 5.0f, right = 5.0f, bottom = 5.0f),
                    )
                    val g2 = Glyph(
                        id = 2u,
                        clusterRange = input.range,
                        advance = 4.0f,
                        x = 4.0f,
                        bounds = Rect(left = 0.0f, top = 0.0f, right = 10.0f, bottom = 10.0f),
                    )
                    val g3 = Glyph(
                        id = 3u,
                        clusterRange = input.range,
                        advance = 4.0f,
                        x = 8.0f,
                        bounds = Rect(left = 10.0f, top = 10.0f, right = 0.0f, bottom = 0.0f),
                    )
                    run.copy(glyphs = listOf(g1, g2, g3))
                }
                return res.copy(glyphRuns = runsWithBounds)
            }
        }
        val engine = ExplainableStubParagraphLayoutEngine(textShaper = multiGlyphShaper)
        val text = "一二三四五六七八"
        val rubySpans = listOf(
            RubySpan(baseRange = TextRange(0, 2), text = "ㄅㄆˊ", kind = RubyKind.Bopomofo, locale = "zh-Bopo"), // Multi-cluster base & locale
            RubySpan(baseRange = TextRange(2, 3), text = " ", kind = RubyKind.Bopomofo), // Empty placements
            RubySpan(baseRange = TextRange(3, 4), text = "ㄅ", kind = RubyKind.Bopomofo), // Yinping
            RubySpan(baseRange = TextRange(4, 5), text = "˙ㄅ", kind = RubyKind.Bopomofo), // Neutral
            RubySpan(baseRange = TextRange(5, 6), text = "ㄅˇ", kind = RubyKind.Bopomofo), // Shang
            RubySpan(baseRange = TextRange(6, 7), text = "ㄅˋ", kind = RubyKind.Bopomofo), // Qu
        )
        val input = LayoutInput(
            content = TiqianTextContent(text),
            rubySpans = rubySpans,
            constraints = LayoutConstraints(maxWidth = 300.0f),
        )
        val result = engine.layout(input)
        assertNotNull(result)
    }

    @Test
    fun directResolveAnnotationGeometryEmptyLineRangesAndGapAtLineEdges() {
        testTrace.section("directResolveAnnotationGeometryEmptyLineRangesAndGapAtLineEdges")
        val engine = ExplainableStubParagraphLayoutEngine()
        val text = "汉字，测试English"
        val input = LayoutInput(
            content = TiqianTextContent(text),
            decorations = listOf(
                DecorationSpan(range = TextRange(0, 2), kind = DecorationKind.Emphasis),
                DecorationSpan(range = TextRange(0, 5), kind = DecorationKind.ProperNoun),
            ),
            constraints = LayoutConstraints(maxWidth = 300.0f),
        )
        val clusters = listOf(
            Cluster(range = TextRange(0, 2), text = "汉字", displayText = "汉字", fontKey = "k", advance = 32.0f),
            Cluster(range = TextRange(2, 3), text = "，", displayText = "，", fontKey = "k", advance = 16.0f),
            Cluster(range = TextRange(3, 5), text = "测试", displayText = "测试", fontKey = "k", advance = 32.0f),
            Cluster(range = TextRange(5, 12), text = "English", displayText = "English", fontKey = "k", advance = 56.0f),
        )
        val lineBoxes = listOf(
            LineBox(
                range = TextRange(0, 0),
                clusterRange = IntRange.EMPTY,
                baseline = 0.0f,
                top = 0.0f,
                bottom = 20.0f,
                naturalWidth = 0.0f,
                adjustedWidth = 0.0f,
                visualWidth = 0.0f,
                indent = 0.0f,
                endReason = LineEndReason.AutoWrap,
            ),
            LineBox(
                range = TextRange(0, 5),
                clusterRange = 0..2,
                baseline = 16.0f,
                top = 0.0f,
                bottom = 20.0f,
                naturalWidth = 80.0f,
                adjustedWidth = 80.0f,
                visualWidth = 80.0f,
                indent = 0.0f,
                endReason = LineEndReason.AutoWrap,
            ),
        )
        val lineSolution = LineSolution(
            lines = listOf(
                LineCandidate(
                    clusterRange = IntRange.EMPTY,
                    sourceRange = TextRange(0, 0),
                    naturalWidth = 0.0f,
                    adjustedWidth = 0.0f,
                    endReason = LineEndReason.AutoWrap,
                ),
                LineCandidate(
                    clusterRange = 0..2,
                    sourceRange = TextRange(0, 5),
                    naturalWidth = 80.0f,
                    adjustedWidth = 80.0f,
                    endReason = LineEndReason.AutoWrap,
                ),
            ),
        )
        val clreqProfile = engine.clreqProfileResolver.resolve(input.profileId)

        val geomDecision = ClusterGeometryDecisionInfo(
            range = TextRange(0, 2),
            sourceText = "汉字",
            displayText = "汉字",
            baseAdvance = 32.0f,
            bodyWidth = 32.0f,
            leadingGlueNatural = 4.0f,
            leadingGlueConsumed = 2.0f,
            trailingGlueNatural = 4.0f,
            trailingGlueConsumed = 2.0f,
            justificationDelta = 0.0f,
            resolvedAdvance = 32.0f,
            source = "test",
            reason = "test",
        )

        val metricDecision1 = ClusterMetricDecision(
            range = TextRange(0, 2),
            sourceText = "汉字",
            request = FontMetricsRequest(fontKey = "k", fontSize = 24.0f, role = FontRole.CjkText, locale = "zh-Hans"),
            rawMetrics = RawFontMetrics(ascent = 18.0f, descent = 6.0f),
            layoutMetrics = LayoutFontMetrics(
                ascent = 18.0f,
                descent = 6.0f,
                baselineOffset = 0.0f,
                policy = FontMetricsPolicy.Raw,
                baselinePolicy = BaselinePolicy.Alphabetic,
            ),
        )
        val metricDecision2 = ClusterMetricDecision(
            range = TextRange(2, 3),
            sourceText = "，",
            request = FontMetricsRequest(fontKey = "k", fontSize = 24.0f, role = FontRole.CjkPunctuation, locale = "zh-Hans"),
            rawMetrics = RawFontMetrics(ascent = 18.0f, descent = 6.0f),
            layoutMetrics = LayoutFontMetrics(
                ascent = 18.0f,
                descent = 6.0f,
                baselineOffset = 0.0f,
                policy = FontMetricsPolicy.Raw,
                baselinePolicy = BaselinePolicy.Alphabetic,
            ),
        )

        val inlineObject1 = InlineObjectSpan(
            range = TextRange(0, 2),
            advance = 32.0f,
            ascent = 16.0f,
            descent = 4.0f,
            leadingBoundary = InlineObjectBoundaryAdjustment(preferredStretch = InlineObjectPreferredStretch(kind = InlineObjectPreferredStretchKind.Relation, naturalWidth = 5.0f, targetWidth = 10.0f)),
            trailingBoundary = InlineObjectBoundaryAdjustment(preferredStretch = null),
        )
        val inlineObject2 = InlineObjectSpan(
            range = TextRange(15, 17),
            advance = 32.0f,
            ascent = 16.0f,
            descent = 4.0f,
            leadingBoundary = InlineObjectBoundaryAdjustment(preferredStretch = null),
            trailingBoundary = InlineObjectBoundaryAdjustment(preferredStretch = InlineObjectPreferredStretch(kind = InlineObjectPreferredStretchKind.BinaryOperator, naturalWidth = 5.0f, targetWidth = 10.0f)),
        )

        val res = engine.resolveAnnotationGeometry(
            input = input,
            fontSize = 16.0f,
            inlineObjectByClusterIndex = mapOf(0 to inlineObject1, 99 to inlineObject2),
            lineSolution = lineSolution,
            clreqProfile = clreqProfile,
            geometryDecisions = listOf(geomDecision),
            autoSpaceDecisions = listOf(
                // Leading gap on cluster 0 (which is line start: atLineStart = true)
                AutoSpaceDecisionInfo(
                    clusterRange = TextRange(0, 2),
                    side = "leading",
                    boundaryRole = "Wide",
                    mode = "Normal",
                    charactersAffected = 1,
                    reductionPerChar = 0.0f,
                    totalReduction = 0.0f,
                    reason = "test",
                ),
                // Leading gap on cluster 1 (which is NOT line start: atLineStart = false)
                AutoSpaceDecisionInfo(
                    clusterRange = TextRange(2, 3),
                    side = "leading",
                    boundaryRole = "Wide",
                    mode = "Normal",
                    charactersAffected = 1,
                    reductionPerChar = 0.0f,
                    totalReduction = 0.0f,
                    reason = "test",
                ),
                // Trailing gap on cluster 1 (which is NOT line end: atLineEnd = false)
                AutoSpaceDecisionInfo(
                    clusterRange = TextRange(2, 3),
                    side = "trailing",
                    boundaryRole = "Wide",
                    mode = "Normal",
                    charactersAffected = 1,
                    reductionPerChar = 0.0f,
                    totalReduction = 0.0f,
                    reason = "test",
                ),
                // Trailing gap on cluster 2 (which is line end: atLineEnd = true)
                AutoSpaceDecisionInfo(
                    clusterRange = TextRange(3, 5),
                    side = "trailing",
                    boundaryRole = "Wide",
                    mode = "Normal",
                    charactersAffected = 1,
                    reductionPerChar = 0.0f,
                    totalReduction = 0.0f,
                    reason = "test",
                ),
            ),
            visibleLineRanges = listOf(IntRange.EMPTY, 0..2),
            lines = lineBoxes,
            finalClusters = clusters,
            clusterRoles = listOf(FontRole.CjkText, FontRole.CjkPunctuation, FontRole.CjkText, FontRole.LatinText),
            justifyDeltaByCluster = emptyMap(),
            rubyAndBopomofoSpread = emptyMap(),
            metricDecisions = listOf(metricDecision1, metricDecision2),
            pinyinSpans = listOf(
                RubySpan(baseRange = TextRange(0, 2), text = "hànzì", kind = RubyKind.Pinyin),
                RubySpan(baseRange = TextRange(2, 3), text = "chù", kind = RubyKind.Pinyin),
                RubySpan(baseRange = TextRange(3, 5), text = "cèshì", kind = RubyKind.Pinyin),
            ),
            naturalClusters = clusters,
            rubyFontGeometryBySpan = mapOf(
                RubySpan(baseRange = TextRange(0, 2), text = "hànzì", kind = RubyKind.Pinyin) to RubyFontGeometry(
                    width = 20.0f,
                    ascent = 8.0f,
                    descent = 2.0f,
                    requiredExtent = 10.0f,
                    glyphs = emptyList(),
                ),
                RubySpan(baseRange = TextRange(2, 3), text = "chù", kind = RubyKind.Pinyin) to RubyFontGeometry(
                    width = 10.0f,
                    ascent = 8.0f,
                    descent = 2.0f,
                    requiredExtent = 10.0f,
                    glyphs = emptyList(),
                ),
                RubySpan(baseRange = TextRange(3, 5), text = "cèshì", kind = RubyKind.Pinyin) to RubyFontGeometry(
                    width = 20.0f,
                    ascent = 8.0f,
                    descent = 2.0f,
                    requiredExtent = 10.0f,
                    glyphs = emptyList(),
                ),
            ),
            rubyStackGap = 0.0f,
            baseAscent = 16.0f,
            rubyFontSize = 8.0f,
            rubyFontWeight = 400,
            baseDescent = 4.0f,
            bopomofoFontWeightAt = { 400 },
        )
        assertNotNull(res)
    }

    @Test
    fun bopomofoAndDecorationLeadingBlankExhaustiveBranches() {
        testTrace.section("bopomofoAndDecorationLeadingBlankExhaustiveBranches")
        val multiGlyphShaper = object : TextShaper {
            var callCount = 0
            override fun shape(input: ShapingInput): ShapingResult {
                callCount += 1
                val cluster = Cluster(
                    range = input.range,
                    text = input.text.substring(input.range.start, input.range.end),
                    displayText = input.displayText,
                    fontKey = "test",
                    advance = 16.0f,
                )
                val glyphs = when (callCount % 2) {
                    0 -> listOf(
                        // Glyph 1: initial baseline
                        Glyph(id = 1u, clusterRange = input.range, advance = 5.0f, x = 0.0f, bounds = Rect(10.0f, 10.0f, 20.0f, 20.0f)),
                        // Glyph 2: smaller left & top, larger right & bottom (triggers minOf/maxOf update true)
                        Glyph(id = 2u, clusterRange = input.range, advance = 5.0f, x = 5.0f, bounds = Rect(5.0f, 5.0f, 25.0f, 25.0f)),
                        // Glyph 3: larger left & top, smaller right & bottom (triggers minOf/maxOf update false)
                        Glyph(id = 3u, clusterRange = input.range, advance = 6.0f, x = 10.0f, bounds = Rect(15.0f, 15.0f, 15.0f, 15.0f)),
                    )
                    else -> emptyList() // bounds.isEmpty() branch
                }
                return ShapingResult(
                    clusters = listOf(cluster),
                    glyphRuns = listOf(GlyphRun(range = input.range, fontKey = "test", glyphs = glyphs, advance = 16.0f)),
                )
            }
        }
        val engine = ExplainableStubParagraphLayoutEngine(textShaper = multiGlyphShaper)
        val text = "中文English"
        val input = LayoutInput(
            content = TiqianTextContent(text),
            decorations = listOf(
                // ProperNoun covering CJK and Latin: at Line start vs line middle
                DecorationSpan(kind = DecorationKind.ProperNoun, range = TextRange(0, 7)),
                DecorationSpan(kind = DecorationKind.ProperNoun, range = TextRange(2, 7)),
            ),
            rubySpans = listOf(
                // Bopomofo with locale = null (Line 592 null arm) and multi-glyph bounds (Lines 695-698)
                RubySpan(baseRange = TextRange(0, 1), text = "ㄅ", kind = RubyKind.Bopomofo, locale = null),
                // Bopomofo with locale != null (Line 592 non-null arm)
                RubySpan(baseRange = TextRange(1, 2), text = "ㄆ", kind = RubyKind.Bopomofo, locale = "zh-TW"),
                // Bopomofo with empty text -> placements.isEmpty() (Line 650 false arm)
                RubySpan(baseRange = TextRange(0, 1), text = "", kind = RubyKind.Bopomofo),
            ),
            constraints = LayoutConstraints(maxWidth = 500.0f),
        )
        val res = engine.layout(input)
        assertNotNull(res)

        // Narrow measure to force line break at CJK/Latin boundary so Latin cluster is atLineStart = true
        val inputNarrow = input.copy(constraints = LayoutConstraints(maxWidth = 30.0f))
        val resNarrow = engine.layout(inputNarrow)
        assertNotNull(resNarrow)
    }

    @Test
    fun bopomofoOverLatinClustersCoversCrossMetricLookup() {
        testTrace.section("bopomofoOverLatinClustersCoversCrossMetricLookup")
        val engine = ExplainableStubParagraphLayoutEngine()
        // A bopomofo ruby over the Latin half: its clusters enter the metric
        // scan against the CJK decision first, so the containment predicate
        // misses on the trailing edge before matching the Latin decision.
        val text = "中文English"
        val input = LayoutInput(
            content = TiqianTextContent(text),
            rubySpans = listOf(
                RubySpan(baseRange = TextRange(2, 3), text = "ㄅ", kind = RubyKind.Bopomofo, locale = null),
                RubySpan(baseRange = TextRange(3, 4), text = "ㄆ", kind = RubyKind.Bopomofo, locale = "zh-TW"),
            ),
            constraints = LayoutConstraints(maxWidth = 500.0f),
        )
        val res = engine.layout(input)
        assertNotNull(res)
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
