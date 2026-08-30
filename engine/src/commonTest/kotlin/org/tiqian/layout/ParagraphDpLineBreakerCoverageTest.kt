package org.tiqian.layout

import org.tiqian.core.Cluster
import org.tiqian.core.LineEndReason
import org.tiqian.core.TextRange
import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.assertFailsWith
import org.tiqian.test.trace.assertTrue
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

/**
 * Coverage for ParagraphDpLineBreaker.kt entry guards, the shrink-prefix
 * construction (zero-capacity, out-of-range, lineEndOnly opportunities),
 * compressed candidate ends reaching the segment end, the tier-promotion
 * pool filter, mandatory-segment candidate filtering, and the compressed
 * final line of both segment kinds — plus the LineBreaker.interface default
 * strategy name.
 */
class ParagraphDpLineBreakerCoverageTest {
    private val testTrace = TestTraceRecorder("ParagraphDpLineBreakerCoverageTest")


    private fun cluster(index: Int, text: String, advance: Float) = Cluster(
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
    fun emptyClustersReturnAnEmptySolution() {
        testTrace.section("emptyClustersReturnAnEmptySolution")
        val solution = ParagraphDpLineBreaker().breakLines(
            naturalClusters = emptyList(),
            adjustedClusters = emptyList(),
            maxWidth = 100.0f,
        )
        assertTrue(solution.lines.isEmpty(), solution.lines.toString())
    }

    @Test
    fun mismatchedNaturalAndAdjustedSizesAreRejected() {
        testTrace.section("mismatchedNaturalAndAdjustedSizesAreRejected")
        val error = assertFailsWith<IllegalArgumentException> {
            ParagraphDpLineBreaker().breakLines(
                naturalClusters = hanClusters(2),
                adjustedClusters = hanClusters(1),
                maxWidth = 100.0f,
            )
        }
        assertTrue(error.message!!.contains("cluster-for-cluster"), error.message)
    }

    @Test
    fun negativeCandidateWindowIsRejected() {
        testTrace.section("negativeCandidateWindowIsRejected")
        val error = assertFailsWith<IllegalArgumentException> {
            ParagraphDpLineBreaker(candidateWindow = -1).breakLines(
                naturalClusters = hanClusters(2),
                adjustedClusters = hanClusters(2),
                maxWidth = 100.0f,
            )
        }
        assertTrue(error.message!!.contains("non-negative"), error.message)
    }

    @Test
    fun shrinkPrefixSkipsNonPositiveAndOutOfRangeOpportunities() {
        testTrace.section("shrinkPrefixSkipsNonPositiveAndOutOfRangeOpportunities")
        // capacity 0 and clusterIndex 4 (out of range for 4 clusters) must be
        // skipped without touching the prefix; the valid opportunity at 1
        // still lands in the always-usable prefix.
        val clusters = hanClusters(4)
        val solution = ParagraphDpLineBreaker().breakLines(
            naturalClusters = clusters,
            adjustedClusters = clusters,
            maxWidth = 100.0f,
            shrinkOpportunities = listOf(
                ShrinkOpportunity(clusterIndex = 1, tier = 2, capacity = 0.0f, channel = ShrinkChannel.RawAdvance),
                ShrinkOpportunity(clusterIndex = 4, tier = 2, capacity = 8.0f, channel = ShrinkChannel.RawAdvance),
                ShrinkOpportunity(clusterIndex = 1, tier = 2, capacity = 8.0f, channel = ShrinkChannel.RawAdvance),
            ),
        )
        assertEquals(1, solution.lines.size, solution.lines.toString())
        assertEquals(0..3, solution.lines[0].clusterRange)
    }

    @Test
    fun lineEndOnlyCapacityFeedsTheCompressedEdgeAtTheLineEnd() {
        testTrace.section("lineEndOnlyCapacityFeedsTheCompressedEdgeAtTheLineEnd")
        // Width 44 with 16px clusters: the natural fit ends at cluster 1, but
        // the tier-1 line-end-half-width capacity at cluster 2 absorbs the
        // 4px overflow, so the DP pulls cluster 2 in through tryPushIn.
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
        )
        val first = solution.lines.first()
        assertEquals(0..2, first.clusterRange, solution.lines.toString())
        assertTrue(
            (first.repair as? RepairOption.PushIn)?.reason?.startsWith("LineAdjustmentPushIn") == true,
            solution.lines.map { it.repair }.toString(),
        )
    }

    @Test
    fun compressedEndsMayReachTheSegmentEnd() {
        testTrace.section("compressedEndsMayReachTheSegmentEnd")
        // The compressed-candidate loop only stops on its condition once an
        // end eats the whole remaining segment (e exceeds segmentEndExclusive).
        val clusters = hanClusters(3)
        val solution = ParagraphDpLineBreaker().breakLines(
            naturalClusters = clusters,
            adjustedClusters = clusters,
            maxWidth = 44.0f,
            shrinkOpportunities = listOf(
                ShrinkOpportunity(clusterIndex = 1, tier = 2, capacity = 12.0f, channel = ShrinkChannel.RawAdvance),
            ),
            cjkInterCharBoundaries = setOf(1),
            lineAdjustmentPushIn = true,
        )
        val first = solution.lines.first()
        assertEquals(0..2, first.clusterRange, solution.lines.toString())
        assertTrue(
            (first.repair as? RepairOption.PushIn)?.reason?.startsWith("LineAdjustmentPushIn") == true,
            solution.lines.map { it.repair }.toString(),
        )
        assertEquals(LineEndReason.ParagraphEnd, solution.lines.last().endReason)
    }

    @Test
    fun compressedFinalMandatoryLineUsesTheCompressedCommitBranch() {
        testTrace.section("compressedFinalMandatoryLineUsesTheCompressedCommitBranch")
        // hardBreak at 2 with width 44: the segment [0,3) fits best as ONE
        // compressed mandatory line (0..2) whose tier-1 line-end glue at the
        // mandatory-end cluster absorbs the 4px overflow.
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
            hardBreakAfterClusters = setOf(2),
            lineAdjustmentPushIn = true,
        )
        assertEquals(0..2, solution.lines[0].clusterRange, solution.lines.toString())
        assertEquals(LineEndReason.MandatoryBreak, solution.lines[0].endReason)
        assertTrue(
            (solution.lines[0].repair as? RepairOption.PushIn)?.reason?.startsWith("LineAdjustmentPushIn") == true,
            solution.lines.map { it.repair }.toString(),
        )
    }

    @Test
    fun tierPromotionRoutesTheRepairReasonThroughThePromotionCode() {
        testTrace.section("tierPromotionRoutesTheRepairReasonThroughThePromotionCode")
        // Whitespace@3 is a strictly better tier than the Emergency@2 the
        // greedy would take: the promotion pool keeps only tier-0-or-better
        // ends of that span, and the commit realizes the push-in under the
        // ProgressiveTechnicalTierPromotion reason.
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
                2 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Emergency, span),
                3 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Whitespace, span),
            ),
        )
        val first = solution.lines.first()
        assertEquals(0..2, first.clusterRange, solution.lines.toString())
        assertTrue(
            (first.repair as RepairOption.PushIn).reason.startsWith("ProgressiveTechnicalTierPromotion"),
            solution.lines.map { it.repair }.toString(),
        )
    }

    @Test
    fun promotionCheckReturnsFalseWhenTheCandidateEndHasNoOpportunity() {
        testTrace.section("promotionCheckReturnsFalseWhenTheCandidateEndHasNoOpportunity")
        // The compressed end 3 is not in the opportunity map, so the
        // promotion check fails at `resulting == null` and the push-in (if
        // any) keeps the ordinary LineAdjustmentPushIn reason. progressive-
        // CandidateAllowed then filters end 3 out of the pool entirely.
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
                2 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Emergency, span),
            ),
        )
        assertEquals(0..1, solution.lines.first().clusterRange, solution.lines.toString())
        assertTrue(
            solution.lines.first().repair == null,
            solution.lines.map { it.repair }.toString(),
        )
    }

    @Test
    fun mandatorySegmentFiltersTheControlBoundaryFromCandidates() {
        testTrace.section("mandatorySegmentFiltersTheControlBoundaryFromCandidates")
        // Width 32 over 6 clusters with hardBreak at 2: the pool filter drops
        // end 2 (== segmentEndExclusive-1) from the candidate ends, but the
        // baseline concat re-adds it and the commit binds it to the hard
        // break, so the segment commits as ONE mandatory line 0..2.
        val clusters = hanClusters(6)
        val solution = ParagraphDpLineBreaker().breakLines(
            naturalClusters = clusters,
            adjustedClusters = clusters,
            maxWidth = 32.0f,
            hardBreakAfterClusters = setOf(2),
        )
        assertEquals(0..2, solution.lines[0].clusterRange, solution.lines.toString())
        assertEquals(LineEndReason.MandatoryBreak, solution.lines[0].endReason)
        assertEquals(5, solution.lines.last().clusterRange.last, solution.lines.toString())
    }

    @Test
    fun narrowWindowsDropEndsAtOrBelowTheLineStart() {
        testTrace.section("narrowWindowsDropEndsAtOrBelowTheLineStart")
        // Width 20 against 16px clusters makes rawGreedy 1, so the window
        // (rawGreedy-8)..rawGreedy contains the out-of-range end 0 which the
        // range filter must drop; every line is exactly one cluster.
        val clusters = hanClusters(4)
        val solution = ParagraphDpLineBreaker().breakLines(
            naturalClusters = clusters,
            adjustedClusters = clusters,
            maxWidth = 20.0f,
        )
        assertEquals(4, solution.lines.size, solution.lines.toString())
        assertTrue(
            solution.lines.all { it.clusterRange.first == it.clusterRange.last },
            solution.lines.map { it.clusterRange }.toString(),
        )
    }

    @Test
    fun interfaceDefaultStrategyNameIsCustom() {
        testTrace.section("interfaceDefaultStrategyNameIsCustom")
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

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
