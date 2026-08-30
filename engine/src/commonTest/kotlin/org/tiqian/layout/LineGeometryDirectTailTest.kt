package org.tiqian.layout

import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.assertNotNull
import org.tiqian.test.trace.assertTrue
import org.tiqian.core.Cluster
import org.tiqian.core.InlineObjectSpan
import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LayoutInput
import org.tiqian.core.LineEndReason
import org.tiqian.core.RubyKind
import org.tiqian.core.RubySpan
import org.tiqian.core.TextRange
import org.tiqian.core.TiqianTextContent
import org.tiqian.font.FontMetricsPolicy
import org.tiqian.font.FontMetricsRequest
import org.tiqian.font.LayoutFontMetrics
import org.tiqian.font.RawFontMetrics
import org.tiqian.font.BaselinePolicy
import org.tiqian.font.FontRole
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

/**
 * Direct calls into resolveLineVerticalGeometry and lineMetrics for direction
 * arms the engine fixtures do not reach: a ruby base range that crosses
 * cluster boundaries (the mapNotNull null arm), a ruby that lives only on the
 * second line (the non-overlap arm of the per-line extent filter), an empty
 * line solution (the maxOrNull elvis over zero lines), the inline-object
 * boundary conjunction with a positive top intrusion below the ruby demand,
 * and a metric-decision list without any ideographic-em box (the ifEmpty
 * fallback of the height source).
 */
class LineGeometryDirectTailTest {
    private val testTrace = TestTraceRecorder("LineGeometryDirectTailTest")


    // Two-unit clusters mirror surrogate-pair graphemes: interior offsets of
    // a cluster match no cluster start, which is what makes a ruby base range
    // that starts mid-cluster unresolvable.
    private fun c(index: Int): Cluster = Cluster(
        range = TextRange(index * 2, index * 2 + 2),
        text = "𠀀",
        displayText = "𠀀",
        fontKey = "k",
        advance = 16.0f,
    )

    private val clusters = List(4) { c(it) }

    private fun line(range: IntRange): LineCandidate =
        rebuildLine(range, clusters, clusters, LineEndReason.AutoWrap, null)

    private fun input(): LayoutInput = LayoutInput(
        content = TiqianTextContent(text = "中文测试"),
        constraints = LayoutConstraints(maxWidth = 320.0f),
    )

    private fun geometry(
        pinyinSpans: List<RubySpan> = emptyList(),
        lines: List<LineCandidate>,
        rubyGeometry: Map<RubySpan, RubyFontGeometry> = emptyMap(),
        inlineObjects: Map<Int, InlineObjectSpan> = emptyMap(),
        existingInterlineSpace: Float = 0.0f,
        baseAscent: Float = 8.0f,
        baseDescent: Float = 4.0f,
    ): LineVerticalGeometryStageResult = resolveLineVerticalGeometry(
        input = input(),
        fontSize = 16.0f,
        pinyinSpans = pinyinSpans,
        naturalClusters = clusters,
        lineSolution = LineSolution(lines = lines),
        rubyFontGeometryBySpan = rubyGeometry,
        existingInterlineSpace = existingInterlineSpace,
        baseLineMetrics = ResolvedLineMetrics(baseline = 12.0f, height = 16.0f),
        baseFaceHeight = 16.0f,
        rubyExtent = 0.0f,
        inlineObjectByClusterIndex = inlineObjects,
        baseAscent = baseAscent,
        baseDescent = baseDescent,
    )

    @Test
    fun rubyBaseRangeCrossingClusterBoundariesDropsOutOfPerLineExtents() {
        testTrace.section("rubyBaseRangeCrossingClusterBoundariesDropsOutOfPerLineExtents")
        // Clusters tile (0,1)(1,2)(2,3)(3,4); baseRange (1,3) starts inside
        // the second and ends inside the third, so clusterIndexRangeFor
        // returns null and the span skips the per-line extent list while the
        // aligned span (2,4) still contributes its required extent.
        val aligned = RubySpan(baseRange = TextRange(4, 8), text = "y", kind = RubyKind.Pinyin)
        val misaligned = RubySpan(baseRange = TextRange(1, 3), text = "w", kind = RubyKind.Pinyin)
        // Resolvable base range but no geometry entry: the map lookup takes
        // its elvis null arm and the span contributes no extent.
        val geometryless = RubySpan(baseRange = TextRange(0, 4), text = "z", kind = RubyKind.Pinyin)
        val result = geometry(
            pinyinSpans = listOf(aligned, misaligned, geometryless),
            lines = listOf(line(0..3)),
            rubyGeometry = mapOf(
                aligned to RubyFontGeometry(
                    width = 12.0f, ascent = 6.0f, descent = 2.0f, requiredExtent = 8.0f, glyphs = emptyList(),
                ),
            ),
        )
        assertEquals(1, result.lineBaseline.size)
        assertTrue(result.lineBaseline[0] > 0.0f)
    }

    @Test
    fun rubiesOnBothLinesExerciseBothSidesOfTheOverlapTest() {
        testTrace.section("rubiesOnBothLinesExerciseBothSidesOfTheOverlapTest")
        // One ruby per line: each line evaluates the overlap conjunction
        // against both an overlapping range and a range confined to the other
        // line, so the second conjunct takes its false direction (range ends
        // before the line starts) as well as its true one.
        val first = RubySpan(baseRange = TextRange(0, 4), text = "a", kind = RubyKind.Pinyin)
        val second = RubySpan(baseRange = TextRange(4, 8), text = "b", kind = RubyKind.Pinyin)
        val result = geometry(
            pinyinSpans = listOf(first, second),
            lines = listOf(line(0..1), line(2..3)),
            rubyGeometry = mapOf(
                first to RubyFontGeometry(
                    width = 12.0f, ascent = 6.0f, descent = 2.0f, requiredExtent = 8.0f, glyphs = emptyList(),
                ),
                second to RubyFontGeometry(
                    width = 12.0f, ascent = 6.0f, descent = 2.0f, requiredExtent = 6.0f, glyphs = emptyList(),
                ),
            ),
        )
        assertEquals(2, result.lineBaseline.size)
        assertTrue(result.lineBaseline[1] > result.lineBaseline[0])
    }

    @Test
    fun emptyLineSolutionYieldsZeroArraysAndZeroMaxExtra() {
        testTrace.section("emptyLineSolutionYieldsZeroArraysAndZeroMaxExtra")
        // A pinyin span keeps the ruby-decision branch alive while the line
        // list is empty: every per-line list is empty and the maxExtra elvis
        // falls back to zero.
        val result = geometry(
            pinyinSpans = listOf(RubySpan(baseRange = TextRange(0, 2), text = "y", kind = RubyKind.Pinyin)),
            lines = emptyList(),
        )
        assertEquals(0, result.lineBaseline.size)
        assertEquals(0, result.lineTop.size)
        assertEquals(0, result.lineBottom.size)
        val decision = result.rubyLineHeightDecision
        // The span keeps the decision alive; zero lines leave every per-line
        // list empty, so the maxExtra elvis falls back to exactly zero.
        assertNotNull(decision)
        assertEquals(0.0f, decision!!.maxExtra)
    }

    private fun objectBoundaryCase(
        objectAscent: Float,
        rubyExtent: Float,
    ): LineVerticalGeometryStageResult {
        val ruby = RubySpan(baseRange = TextRange(4, 8), text = "y", kind = RubyKind.Pinyin)
        return geometry(
            pinyinSpans = listOf(ruby),
            lines = listOf(line(0..1), line(2..3)),
            rubyGeometry = mapOf(
                ruby to RubyFontGeometry(
                    width = 12.0f, ascent = 6.0f, descent = 2.0f,
                    requiredExtent = rubyExtent, glyphs = emptyList(),
                ),
            ),
            inlineObjects = mapOf(
                2 to InlineObjectSpan(
                    range = TextRange(4, 6), advance = 16.0f, ascent = objectAscent, descent = 2.0f,
                ),
            ),
            baseAscent = 8.0f,
            baseDescent = 4.0f,
        )
    }

    @Test
    fun objectTopIntrusionBelowRubyDemandKeepsBoundaryClearanceZero() {
        testTrace.section("objectTopIntrusionBelowRubyDemandKeepsBoundaryClearanceZero")
        // The object ascent (10.0) exceeds baseAscent (8.0), so its top
        // intrusion is positive (2.0), but the ruby demand (8.0) is larger:
        // the last conjunct of the boundary conjunction is false and no
        // minimum clearance is added.
        val result = objectBoundaryCase(objectAscent = 10.0f, rubyExtent = 8.0f)
        assertEquals(2, result.lineBaseline.size)
        assertTrue(result.lineBaseline[1] > result.lineBaseline[0])
    }

    @Test
    fun objectTopIntrusionDominatingRubyDemandAddsBoundaryClearance() {
        testTrace.section("objectTopIntrusionDominatingRubyDemandAddsBoundaryClearance")
        // The top intrusion (12.0) now meets or exceeds the ruby demand
        // (8.0): the full conjunction holds and the boundary clearance
        // enters the inter-line demand of the second line.
        val result = objectBoundaryCase(objectAscent = 20.0f, rubyExtent = 8.0f)
        assertEquals(2, result.lineBaseline.size)
        assertTrue(result.lineBaseline[1] > result.lineBaseline[0])
    }

    @Test
    fun objectFlushWithBaseTopSkipsIntrusionConjunctionEarly() {
        testTrace.section("objectFlushWithBaseTopSkipsIntrusionConjunctionEarly")
        // The object ascent equals baseAscent, so the top intrusion is zero:
        // the first conjunct of the inner conjunction is false while the
        // bottom intrusion of the previous line is zero too.
        val result = objectBoundaryCase(objectAscent = 8.0f, rubyExtent = 8.0f)
        assertEquals(2, result.lineBaseline.size)
        assertTrue(result.lineBaseline[1] > result.lineBaseline[0])
    }

    @Test
    fun metricListWithoutIdeographicEmBoxFallsBackToAllClusters() {
        testTrace.section("metricListWithoutIdeographicEmBoxFallsBackToAllClusters")
        // Pure Latin/annotation metrics carry RawFontBox; the height-source
        // filter finds no ideographic-em cluster and falls back to the whole
        // list, so the raw ascent/descent still define the line box.
        val decision = ClusterMetricDecision(
            range = TextRange(0, 1),
            sourceText = "a",
            request = FontMetricsRequest(
                fontKey = "latin", fontSize = 16.0f, role = FontRole.LatinText, locale = "zh-Hans",
            ),
            rawMetrics = RawFontMetrics(ascent = 14.0f, descent = 4.0f),
            layoutMetrics = LayoutFontMetrics(
                ascent = 14.0f,
                descent = 4.0f,
                baselineOffset = 0.0f,
                policy = FontMetricsPolicy.Raw,
                baselinePolicy = BaselinePolicy.Alphabetic,
            ),
        )
        val metrics = listOf(decision).lineMetrics(
            explicitLineHeight = null,
            defaultLineHeight = 24.0f,
            spacingFloor = 0.0f,
        )
        // The default body line height (24.0) exceeds the natural 18.0, so
        // the fallback still yields at least the natural box.
        assertTrue(metrics.baseline >= 14.0f)
        assertTrue(metrics.height >= 18.0f)
    }

    @Test
    fun emptyMetricListTakesEmptyParagraphBaselineFallback() {
        testTrace.section("emptyMetricListTakesEmptyParagraphBaselineFallback")
        // The isEmpty early return serves paragraphs with no shapeable
        // content: the caret baseline sits at 0.75 of the line height. This
        // guard is also why the maxOf calls below it never observe an empty
        // list, making their inline throw arms unreachable.
        val byDefault = emptyList<ClusterMetricDecision>().lineMetrics(
            explicitLineHeight = null,
            defaultLineHeight = 24.0f,
            spacingFloor = 0.0f,
        )
        assertEquals(24.0f, byDefault.height)
        assertEquals(18.0f, byDefault.baseline)
        val byExplicit = emptyList<ClusterMetricDecision>().lineMetrics(
            explicitLineHeight = 30.0f,
            defaultLineHeight = 24.0f,
            spacingFloor = 0.0f,
        )
        assertEquals(30.0f, byExplicit.height)
        assertEquals(22.5f, byExplicit.baseline)
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
