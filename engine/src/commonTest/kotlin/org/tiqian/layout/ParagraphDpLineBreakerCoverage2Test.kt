package org.tiqian.layout

import org.tiqian.core.Cluster
import org.tiqian.core.LineEndReason
import org.tiqian.core.TextRange
import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.assertFalse
import org.tiqian.test.trace.assertTrue
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

class ParagraphDpLineBreakerCoverage2Test {
    private val testTrace = TestTraceRecorder("ParagraphDpLineBreakerCoverage2Test")


    private fun cluster(index: Int, text: String = "中", advance: Float = 16.0f) = Cluster(
        range = TextRange(index, index + 1),
        text = text,
        displayText = text,
        fontKey = "test",
        advance = advance,
    )

    private fun hanClusters(count: Int, advance: Float = 16.0f): List<Cluster> =
        (0 until count).map { cluster(it, "中", advance) }

    private fun latinClusters(): List<Cluster> = listOf(
        cluster(0, "a", 30.0f),
        cluster(1, "/", 30.0f),
        cluster(2, "b", 25.0f),
        cluster(3, "c", 30.0f),
        cluster(4, "d", 30.0f),
    )

    @Test
    fun testShrinkOpportunitiesNegativeAndOutOfRange() {
        testTrace.section("testShrinkOpportunitiesNegativeAndOutOfRange")
        val clusters = hanClusters(3)
        // negative capacity, out of range index, lineEndOnly true and false
        val solution = ParagraphDpLineBreaker().breakLines(
            naturalClusters = clusters,
            adjustedClusters = clusters,
            maxWidth = 100.0f,
            shrinkOpportunities = listOf(
                ShrinkOpportunity(clusterIndex = -1, tier = 1, capacity = 10.0f, channel = ShrinkChannel.RawAdvance),
                ShrinkOpportunity(clusterIndex = 0, tier = 1, capacity = -5.0f, channel = ShrinkChannel.RawAdvance),
                ShrinkOpportunity(clusterIndex = 5, tier = 1, capacity = 10.0f, channel = ShrinkChannel.RawAdvance),
                ShrinkOpportunity(clusterIndex = 1, tier = 1, capacity = 4.0f, channel = ShrinkChannel.RawAdvance, lineEndOnly = false),
                ShrinkOpportunity(clusterIndex = 2, tier = 1, capacity = 4.0f, channel = ShrinkChannel.TrailingGlue, lineEndOnly = true),
            ),
        )
        assertEquals(1, solution.lines.size)
    }

    @Test
    fun testCandidateWindowBoundsCompressionEdges() {
        testTrace.section("testCandidateWindowBoundsCompressionEdges")
        // candidateWindow = 1 with 4 clusters
        val clusters = hanClusters(4, 20.0f)
        // maxWidth 25: rawGreedy is 1 (cluster 0).
        // clusters 1 and 2 both have capacity to shrink, but candidateWindow = 1 caps compressed list at 1
        val solution = ParagraphDpLineBreaker(candidateWindow = 1).breakLines(
            naturalClusters = clusters,
            adjustedClusters = clusters,
            maxWidth = 25.0f,
            shrinkOpportunities = listOf(
                ShrinkOpportunity(clusterIndex = 0, tier = 1, capacity = 10.0f, channel = ShrinkChannel.RawAdvance),
                ShrinkOpportunity(clusterIndex = 1, tier = 1, capacity = 10.0f, channel = ShrinkChannel.RawAdvance),
                ShrinkOpportunity(clusterIndex = 2, tier = 1, capacity = 10.0f, channel = ShrinkChannel.RawAdvance),
            ),
            lineAdjustmentPushIn = true,
        )
        assertTrue(solution.lines.isNotEmpty())
    }

    @Test
    fun testProgressiveTierPromotionBranches() {
        testTrace.section("testProgressiveTierPromotionBranches")
        val clusters = latinClusters()
        val span = TextRange(0, clusters.size)
        val otherSpan = TextRange(1, 3)

        // 1. Resulting tier priority >= current tier priority (no promotion)
        val noPromotionSolution = ParagraphDpLineBreaker().breakLines(
            naturalClusters = clusters,
            adjustedClusters = clusters,
            maxWidth = 80.0f,
            shrinkOpportunities = listOf(
                ShrinkOpportunity(clusterIndex = 2, tier = 2, capacity = 5.0f, channel = ShrinkChannel.RawAdvance),
            ),
            lineAdjustmentPushIn = true,
            progressiveBreakOpportunities = mapOf(
                2 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Whitespace, span),
                3 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Emergency, span),
            ),
        )
        assertTrue(noPromotionSolution.lines.isNotEmpty())

        // 2. Different span ranges
        val diffSpanSolution = ParagraphDpLineBreaker().breakLines(
            naturalClusters = clusters,
            adjustedClusters = clusters,
            maxWidth = 80.0f,
            shrinkOpportunities = listOf(
                ShrinkOpportunity(clusterIndex = 2, tier = 2, capacity = 5.0f, channel = ShrinkChannel.RawAdvance),
            ),
            lineAdjustmentPushIn = true,
            progressiveBreakOpportunities = mapOf(
                2 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Emergency, span),
                3 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Whitespace, otherSpan),
            ),
        )
        assertTrue(diffSpanSolution.lines.isNotEmpty())

        // 3. Pool filtering with promotions and non-promotions (and opportunity == null)
        val mixedPromotionSolution = ParagraphDpLineBreaker(candidateWindow = 4).breakLines(
            naturalClusters = clusters,
            adjustedClusters = clusters,
            maxWidth = 80.0f,
            shrinkOpportunities = listOf(
                ShrinkOpportunity(clusterIndex = 2, tier = 2, capacity = 5.0f, channel = ShrinkChannel.RawAdvance),
            ),
            lineAdjustmentPushIn = true,
            progressiveBreakOpportunities = mapOf(
                // 1 is omitted -> opportunity == null in tierPreferredPool filtering
                2 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Emergency, span),
                3 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Whitespace, span),
            ),
        )
        assertTrue(mixedPromotionSolution.lines.isNotEmpty())
    }

    @Test
    fun testCommitSegmentOriginalBreakNotNullResultingBreakNull() {
        testTrace.section("testCommitSegmentOriginalBreakNotNullResultingBreakNull")
        val clusters = latinClusters()
        val span = TextRange(0, clusters.size)
        // originalBreak at 2 has opportunity, but resultingBreak at 3 has none
        val solution = ParagraphDpLineBreaker().breakLines(
            naturalClusters = clusters,
            adjustedClusters = clusters,
            maxWidth = 80.0f,
            shrinkOpportunities = listOf(
                ShrinkOpportunity(clusterIndex = 2, tier = 2, capacity = 5.0f, channel = ShrinkChannel.RawAdvance),
            ),
            lineAdjustmentPushIn = true,
            progressiveBreakOpportunities = mapOf(
                2 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Whitespace, span),
            ),
        )
        assertTrue(solution.lines.isNotEmpty())
    }

    @Test
    fun testTierPreferredPoolEmptyFallback() {
        testTrace.section("testTierPreferredPoolEmptyFallback")
        val clusters = hanClusters(4, 20.0f)
        // unbreakable range covers whole line, forces candidateEnds .ifEmpty fallback
        val solution = ParagraphDpLineBreaker().breakLines(
            naturalClusters = clusters,
            adjustedClusters = clusters,
            maxWidth = 30.0f,
            unbreakableRanges = UnbreakableRanges(listOf(0..3)),
        )
        assertTrue(solution.lines.isNotEmpty())
    }

    @Test
    fun testHardBreakAfterClustersInDpCommit() {
        testTrace.section("testHardBreakAfterClustersInDpCommit")
        val clusters = hanClusters(4, 20.0f)
        // Hard break in middle
        val solution = ParagraphDpLineBreaker().breakLines(
            naturalClusters = clusters,
            adjustedClusters = clusters,
            maxWidth = 50.0f,
            hardBreakAfterClusters = setOf(1),
        )
        assertEquals(2, solution.lines.size)
        assertEquals(LineEndReason.MandatoryBreak, solution.lines[0].endReason)
        assertEquals(LineEndReason.ParagraphEnd, solution.lines[1].endReason)
    }

    @Test
    fun testCandidateEndsWindowBelowLineStart() {
        testTrace.section("testCandidateEndsWindowBelowLineStart")
        // Test where (rawGreedy - candidateWindow) < start
        val clusters = hanClusters(3, 20.0f)
        val solution = ParagraphDpLineBreaker(candidateWindow = 5).breakLines(
            naturalClusters = clusters,
            adjustedClusters = clusters,
            maxWidth = 25.0f,
        )
        assertEquals(3, solution.lines.size)
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
