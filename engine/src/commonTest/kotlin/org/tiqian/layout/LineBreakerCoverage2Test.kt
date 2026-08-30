package org.tiqian.layout

import org.tiqian.core.Cluster
import org.tiqian.core.LineEndReason
import org.tiqian.core.TextRange
import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.assertFailsWith
import org.tiqian.test.trace.assertFalse
import org.tiqian.test.trace.assertTrue
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

class LineBreakerCoverage2Test {
    private val testTrace = TestTraceRecorder("LineBreakerCoverage2Test")


    private fun cluster(index: Int, text: String = "中", advance: Float = 16.0f) = Cluster(
        range = TextRange(index, index + 1),
        text = text,
        displayText = text,
        fontKey = "test",
        advance = advance,
    )

    private fun hanClusters(count: Int, advance: Float = 16.0f): List<Cluster> =
        (0 until count).map { cluster(it, "中", advance) }

    @Test
    fun testLineBreakerStrategyNameDefault() {
        testTrace.section("testLineBreakerStrategyNameDefault")
        val breaker = object : LineBreaker {
            override fun breakLines(
                naturalClusters: List<Cluster>,
                adjustedClusters: List<Cluster>,
                maxWidth: Float,
                shrinkOpportunities: List<ShrinkOpportunity>,
                unbreakableRanges: UnbreakableRanges,
                firstLineIndent: Float,
                hangableClusters: Set<Int>,
                extendableHangRanges: List<IntRange>,
                forbiddenLineStartClusters: Set<Int>?,
                forbiddenLineEndClusters: Set<Int>,
                hyphenBreakClusters: Set<Int>,
                cjkInterCharBoundaries: Set<Int>,
                maxCjkStretchPerGap: Float,
                sinoWesternBoundaries: Set<Int>,
                sinoWesternStretchCap: Float,
                lineAdjustmentPushIn: Boolean,
                lineAdjustmentCompressBias: Float,
                hardBreakAfterClusters: Set<Int>,
                nonRenderingControlClusters: Set<Int>,
                progressiveBreakOpportunities: Map<Int, ProgressiveBreakOpportunity>,
            ): LineSolution = LineSolution(emptyList())
        }
        assertEquals("custom", breaker.strategyName)
    }

    @Test
    fun testLookaheadLineBreakerPreconditions() {
        testTrace.section("testLookaheadLineBreakerPreconditions")
        val clusters = hanClusters(2)
        // natural vs adjusted mismatch
        assertFailsWith<IllegalArgumentException> {
            LookaheadLineBreaker().breakLines(
                naturalClusters = hanClusters(1),
                adjustedClusters = clusters,
                maxWidth = 100.0f,
            )
        }

        // negative window
        assertFailsWith<IllegalArgumentException> {
            LookaheadLineBreaker(window = -1).breakLines(
                naturalClusters = clusters,
                adjustedClusters = clusters,
                maxWidth = 100.0f,
            )
        }

        // negative futureLineHorizon
        assertFailsWith<IllegalArgumentException> {
            LookaheadLineBreaker(futureLineHorizon = -1).breakLines(
                naturalClusters = clusters,
                adjustedClusters = clusters,
                maxWidth = 100.0f,
            )
        }
    }

    @Test
    fun testLookaheadCandidateFilteringWithNonRenderingControlClusters() {
        testTrace.section("testLookaheadCandidateFilteringWithNonRenderingControlClusters")
        // Cluster 0 is non-rendering; test candidate filter evaluation
        val clusters = listOf(
            cluster(0, "\u200B", 0.0f),
            cluster(1, "A", 20.0f),
            cluster(2, "B", 20.0f),
        )
        val solution = LookaheadLineBreaker(window = 2).breakLines(
            naturalClusters = clusters,
            adjustedClusters = clusters,
            maxWidth = 25.0f,
            nonRenderingControlClusters = setOf(0),
        )
        assertTrue(solution.lines.isNotEmpty())
        assertEquals(0..1, solution.lines.first().clusterRange)
    }

    @Test
    fun testLookaheadHardBreakAtEndAndMiddle() {
        testTrace.section("testLookaheadHardBreakAtEndAndMiddle")
        // Narrow width 20.0 with 2 clusters (16.0 each):
        // greedyEnd is 1 (< segmentEndExclusive 2), candidate is 1 == mandatoryEnd.
        // Triggers lines 524-527 emptyLineCandidate!
        val endSolution = LookaheadLineBreaker(window = 1).breakLines(
            naturalClusters = hanClusters(2),
            adjustedClusters = hanClusters(2),
            maxWidth = 20.0f,
            hardBreakAfterClusters = setOf(1),
        )
        assertEquals(2, endSolution.lines.size)
        assertEquals(0..1, endSolution.lines[0].clusterRange)
        assertEquals(LineEndReason.MandatoryBreak, endSolution.lines[0].endReason)
        assertEquals(1..0, endSolution.lines[1].clusterRange) // emptyLineCandidate
        assertEquals(LineEndReason.ParagraphEnd, endSolution.lines[1].endReason)

        // Hard break in middle (cluster 0) with maxWidth 20.0 on 3 clusters
        val middleSolution = LookaheadLineBreaker(window = 1).breakLines(
            naturalClusters = hanClusters(3),
            adjustedClusters = hanClusters(3),
            maxWidth = 20.0f,
            hardBreakAfterClusters = setOf(0),
        )
        assertEquals(3, middleSolution.lines.size)
        assertEquals(0..0, middleSolution.lines[0].clusterRange)
        assertEquals(LineEndReason.MandatoryBreak, middleSolution.lines[0].endReason)

        // Cluster 0 has advance > maxWidth with hard break at 0 (lineStart == committedEnd == 0)
        val oversizedHardBreakSolution = LookaheadLineBreaker(window = 1).breakLines(
            naturalClusters = listOf(cluster(0, "A", 50.0f), cluster(1, "B", 10.0f)),
            adjustedClusters = listOf(cluster(0, "A", 50.0f), cluster(1, "B", 10.0f)),
            maxWidth = 20.0f,
            hardBreakAfterClusters = setOf(0),
        )
        assertEquals(2, oversizedHardBreakSolution.lines.size)
    }

    @Test
    fun testLineCandidateEndsWithProgressiveBreak() {
        testTrace.section("testLineCandidateEndsWithProgressiveBreak")
        val candidate = LineCandidate(
            clusterRange = 0..1,
            sourceRange = TextRange(0, 2),
            naturalWidth = 32.0f,
            adjustedWidth = 32.0f,
            endReason = LineEndReason.AutoWrap,
        )
        val opp = ProgressiveBreakOpportunity(ProgressiveBreakTier.Syllable, TextRange(0, 4))
        val opps = mapOf(2 to opp)

        // AutoWrap + non-empty + in map -> true
        assertTrue(candidate.endsWithProgressiveBreak(opps))

        // ParagraphEnd -> false
        assertFalse(candidate.copy(endReason = LineEndReason.ParagraphEnd).endsWithProgressiveBreak(opps))

        // Empty clusterRange -> false
        assertFalse(candidate.copy(clusterRange = 1..0).endsWithProgressiveBreak(opps))

        // Not in map -> false
        assertFalse(candidate.endsWithProgressiveBreak(emptyMap()))
    }

    @Test
    fun testLineGapCount() {
        testTrace.section("testLineGapCount")
        assertEquals(0, lineGapCount(1..0, setOf(0, 1)))
        assertEquals(1, lineGapCount(0..2, setOf(1)))
        assertEquals(0, lineGapCount(0..2, setOf(2)))
    }

    @Test
    fun testRebuildLineEmptyRangeThrows() {
        testTrace.section("testRebuildLineEmptyRangeThrows")
        val clusters = hanClusters(2)
        assertFailsWith<IllegalArgumentException> {
            rebuildLine(1..0, clusters, clusters)
        }
    }

    @Test
    fun testFindGreedyEndDefaultArgs() {
        testTrace.section("testFindGreedyEndDefaultArgs")
        val clusters = hanClusters(5, 10.0f)
        // Using default endExclusive and nonRenderingControlClusters
        val end = findGreedyEnd(clusters, 0, 25.0f)
        assertEquals(2, end)
    }

    @Test
    fun testLookaheadOrphanAndSyntheticHyphenRuns() {
        testTrace.section("testLookaheadOrphanAndSyntheticHyphenRuns")
        val clusters = hanClusters(4, 20.0f)
        // maxWidth 25 forces 1 cluster per line -> orphan penalty on line 0, 1, 2
        val solution = LookaheadLineBreaker(futureLineHorizon = 2).breakLines(
            naturalClusters = clusters,
            adjustedClusters = clusters,
            maxWidth = 25.0f,
            hyphenBreakClusters = setOf(1, 2, 3),
        )
        assertEquals(4, solution.lines.size)
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
