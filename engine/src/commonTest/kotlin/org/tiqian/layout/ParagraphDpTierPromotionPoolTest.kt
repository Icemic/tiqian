package org.tiqian.layout

import org.tiqian.core.Cluster
import org.tiqian.core.TextRange
import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.assertTrue
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

/**
 * The promotion-pool tier preference and the commit-time promotion check:
 * a pool candidate whose opportunity lives on a foreign span survives the
 * same-span purge, and a committed compressed line whose original and
 * resulting opportunities sit on different spans keeps the ordinary
 * LineAdjustmentPushIn reason.
 */
class ParagraphDpTierPromotionPoolTest {
    private val testTrace = TestTraceRecorder("ParagraphDpTierPromotionPoolTest")


    private fun cluster(index: Int, text: String, advance: Float) = Cluster(
        range = TextRange(index, index + 1),
        text = text,
        displayText = text,
        fontKey = "test",
        advance = advance,
    )

    private fun hanClusters(count: Int): List<Cluster> =
        (0 until count).map { cluster(it, "中", 16.0f) }

    private fun latinClusters(): List<Cluster> = listOf(
        cluster(0, "a", 30.0f),
        cluster(1, "/", 30.0f),
        cluster(2, "b", 25.0f),
        cluster(3, "c", 30.0f),
        cluster(4, "d", 30.0f),
    )

    @Test
    fun foreignSpanCandidateSurvivesThePromotionPoolPurge() {
        testTrace.section("foreignSpanCandidateSurvivesThePromotionPoolPurge")
        // Same construction as the tier-promotion fixture, plus an
        // opportunity at end 1 whose span (0,1) differs from the promoted
        // span (0,5): the same-span purge keeps it because the span compare
        // fails before the priority compare, while the same-span Emergency
        // end 2 is dropped as the worse tier of the promoted span.
        val clusters = latinClusters()
        val span = TextRange(0, clusters.size)
        val solution = ParagraphDpLineBreaker().breakLines(
            naturalClusters = clusters,
            adjustedClusters = clusters,
            maxWidth = 80.0f,
            shrinkOpportunities = listOf(
                ShrinkOpportunity(clusterIndex = 2, tier = 2, capacity = 5.0f, channel = ShrinkChannel.RawAdvance),
            ),
            lineAdjustmentPushIn = true,
            progressiveBreakOpportunities = mapOf(
                1 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Emergency, TextRange(0, 1)),
                2 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Emergency, span),
                3 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Whitespace, span),
            ),
        )
        val first = solution.lines.first()
        // The promotion still routes through the promoted end: the foreign
        // candidate merely stays in the pool as a scored alternative.
        assertTrue(
            (first.repair as RepairOption.PushIn).reason.startsWith("ProgressiveTechnicalTierPromotion"),
            solution.lines.map { it.repair }.toString(),
        )
    }

    @Test
    fun committedCompressedLineWithForeignSpanOpportunitiesKeepsPlainPushInReason() {
        testTrace.section("committedCompressedLineWithForeignSpanOpportunitiesKeepsPlainPushInReason")
        // Width 44 with 16px clusters: the committed line pulls cluster 2 in
        // through the line-end tier-1 glue. The chosen end 3 carries a
        // Whitespace opportunity on span (2,4) while the recomputed greedy
        // end 2 carries an Emergency opportunity on span (0,2): the
        // commit-time promotion conjunction fails on the span compare, so
        // the repair keeps the ordinary LineAdjustmentPushIn reason.
        val clusters = hanClusters(4)
        val solution = ParagraphDpLineBreaker().breakLines(
            naturalClusters = clusters,
            adjustedClusters = clusters,
            maxWidth = 44.0f,
            shrinkOpportunities = listOf(
                ShrinkOpportunity(
                    clusterIndex = 2,
                    tier = 1,
                    capacity = 4.0f,
                    channel = ShrinkChannel.TrailingGlue,
                    lineEndOnly = true,
                ),
            ),
            cjkInterCharBoundaries = setOf(1),
            lineAdjustmentPushIn = true,
            progressiveBreakOpportunities = mapOf(
                2 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Emergency, TextRange(0, 2)),
                3 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Whitespace, TextRange(2, 4)),
            ),
        )
        val first = solution.lines.first()
        assertEquals(0..2, first.clusterRange, solution.lines.toString())
        assertTrue(
            (first.repair as? RepairOption.PushIn)?.reason?.startsWith("LineAdjustmentPushIn") == true,
            solution.lines.map { it.repair }.toString(),
        )
    }

    @Test
    fun committedCompressedEndWithoutOpportunityKeepsPlainPushInReason() {
        testTrace.section("committedCompressedEndWithoutOpportunityKeepsPlainPushInReason")
        // The same line-end glue pull, but only the greedy end carries an
        // opportunity: the commit-time conjunction passes its first null
        // check (originalBreak at the recomputed greedy end 2) and stops at
        // the second (the chosen compressed end 3 has no opportunity), the
        // one direction a map without any opportunity cannot reach because
        // its first check already fails.
        val clusters = hanClusters(4)
        val solution = ParagraphDpLineBreaker().breakLines(
            naturalClusters = clusters,
            adjustedClusters = clusters,
            maxWidth = 44.0f,
            shrinkOpportunities = listOf(
                ShrinkOpportunity(
                    clusterIndex = 2,
                    tier = 1,
                    capacity = 4.0f,
                    channel = ShrinkChannel.TrailingGlue,
                    lineEndOnly = true,
                ),
            ),
            cjkInterCharBoundaries = setOf(1),
            lineAdjustmentPushIn = true,
            progressiveBreakOpportunities = mapOf(
                2 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Emergency, TextRange(0, 2)),
            ),
        )
        val first = solution.lines.first()
        assertEquals(0..2, first.clusterRange, solution.lines.toString())
        assertTrue(
            (first.repair as? RepairOption.PushIn)?.reason?.startsWith("LineAdjustmentPushIn") == true,
            solution.lines.map { it.repair }.toString(),
        )
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
