package org.tiqian.layout

import org.tiqian.core.Cluster
import org.tiqian.core.LineEndReason
import org.tiqian.core.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParagraphDpLineBreakerTest {

    private fun cluster(index: Int, text: String, advance: Float) = Cluster(
        range = TextRange(index, index + 1),
        text = text,
        displayText = text,
        fontKey = "test",
        advance = advance,
    )

    private fun hanClusters(count: Int, advance: Float = 16f, punctuationAt: Set<Int> = emptySet()): List<Cluster> =
        (0 until count).map { i -> cluster(i, if (i in punctuationAt) "。" else "中", advance) }

    private fun breakLines(
        clusters: List<Cluster>,
        maxWidth: Float,
        breaker: ParagraphDpLineBreaker = ParagraphDpLineBreaker(),
        unbreakableRanges: UnbreakableRanges = UnbreakableRanges.Empty,
        forbiddenLineStartClusters: Set<Int>? = null,
        hardBreakAfterClusters: Set<Int> = emptySet(),
        cjkInterCharBoundaries: Set<Int> = (1 until clusters.size).toSet(),
        maxCjkStretchPerGap: Float = 8f,
        shrinkOpportunities: List<ShrinkOpportunity> = emptyList(),
        lineAdjustmentPushIn: Boolean = false,
        progressiveBreakOpportunities: Map<Int, ProgressiveBreakOpportunity> = emptyMap(),
    ): LineSolution = breaker.breakLines(
        naturalClusters = clusters,
        adjustedClusters = clusters,
        maxWidth = maxWidth,
        shrinkOpportunities = shrinkOpportunities,
        unbreakableRanges = unbreakableRanges,
        forbiddenLineStartClusters = forbiddenLineStartClusters,
        hardBreakAfterClusters = hardBreakAfterClusters,
        cjkInterCharBoundaries = cjkInterCharBoundaries,
        maxCjkStretchPerGap = maxCjkStretchPerGap,
        lineAdjustmentPushIn = lineAdjustmentPushIn,
        progressiveBreakOpportunities = progressiveBreakOpportunities,
    )

    private fun assertTiles(solution: LineSolution, clusterCount: Int) {
        var expected = 0
        for (line in solution.lines) {
            if (line.clusterRange.isEmptyClusterRange()) continue
            assertEquals(expected, line.clusterRange.first, "lines must tile clusters in order")
            expected = line.clusterRange.last + 1
        }
        assertEquals(clusterCount, expected, "lines must cover every cluster")
    }

    @Test
    fun compressedSameTierBoundaryIsNotReportedAsPromotion() {
        val clusters = listOf(
            cluster(0, "a", 30f),
            cluster(1, "/", 30f),
            cluster(2, "b", 25f),
            cluster(3, "c", 30f),
            cluster(4, "d", 30f),
        )
        val span = TextRange(0, clusters.size)
        val solution = breakLines(
            clusters = clusters,
            maxWidth = 80f,
            cjkInterCharBoundaries = emptySet(),
            shrinkOpportunities = listOf(
                ShrinkOpportunity(
                    clusterIndex = 2,
                    tier = 2,
                    capacity = 5f,
                    channel = ShrinkChannel.RawAdvance,
                ),
            ),
            lineAdjustmentPushIn = true,
            progressiveBreakOpportunities = mapOf(
                2 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Emergency, span),
                3 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Emergency, span),
            ),
        )

        assertEquals(0..2, solution.lines.first().clusterRange)
        assertTrue(
            (solution.lines.first().repair as RepairOption.PushIn).reason
                .startsWith("LineAdjustmentPushIn"),
        )
    }

    @Test
    fun tilesAllClustersInOrder() {
        val clusters = hanClusters(23)
        val solution = breakLines(clusters, maxWidth = 100f)
        assertTiles(solution, clusters.size)
        assertEquals(LineEndReason.ParagraphEnd, solution.lines.last().endReason)
    }

    @Test
    fun singleLineWhenEverythingFits() {
        val clusters = hanClusters(4)
        val solution = breakLines(clusters, maxWidth = 400f)
        assertEquals(1, solution.lines.size)
        assertEquals(LineEndReason.ParagraphEnd, solution.lines.single().endReason)
    }

    @Test
    fun mandatoryBreakBindsControlToPreviousLine() {
        // 中中\n中中 — the "\n" control must ride line 1 (no bogus blank row).
        val clusters = listOf(
            cluster(0, "中", 16f),
            cluster(1, "中", 16f),
            cluster(2, "\n", 0f),
            cluster(3, "中", 16f),
            cluster(4, "中", 16f),
        )
        val solution = breakLines(
            clusters, maxWidth = 200f,
            hardBreakAfterClusters = setOf(2),
        )
        assertTiles(solution, clusters.size)
        assertEquals(2, solution.lines.size)
        assertEquals(LineEndReason.MandatoryBreak, solution.lines[0].endReason)
        assertEquals(0..2, solution.lines[0].clusterRange)
        assertEquals(LineEndReason.ParagraphEnd, solution.lines[1].endReason)
    }

    @Test
    fun trailingMandatoryBreakEmitsParagraphEndLine() {
        val clusters = listOf(
            cluster(0, "中", 16f),
            cluster(1, "\n", 0f),
        )
        val solution = breakLines(clusters, maxWidth = 200f, hardBreakAfterClusters = setOf(1))
        assertEquals(LineEndReason.MandatoryBreak, solution.lines.first().endReason)
        assertEquals(LineEndReason.ParagraphEnd, solution.lines.last().endReason)
        assertTrue(solution.lines.last().clusterRange.isEmptyClusterRange())
    }

    @Test
    fun neverBreaksInsideUnbreakableRange() {
        val clusters = hanClusters(10)
        val solution = breakLines(
            clusters, maxWidth = 64f,
            unbreakableRanges = UnbreakableRanges(listOf(3..6)),
        )
        assertTiles(solution, clusters.size)
        for (line in solution.lines) {
            if (line.clusterRange.isEmptyClusterRange()) continue
            val startsInside = line.clusterRange.first in 4..6
            assertTrue(!startsInside, "break inside unbreakable range: ${line.clusterRange}")
        }
    }

    @Test
    fun kinsokuAvoidanceRoutesAroundForbiddenLineStart() {
        // 7 clusters, width fits 3; cluster 6 (。) may not start a line. The DP
        // should end up with 。 bound to a line without needing LeaveRagged.
        val clusters = hanClusters(7, punctuationAt = setOf(6))
        val solution = breakLines(
            clusters, maxWidth = 48f,
            forbiddenLineStartClusters = setOf(6),
        )
        assertTiles(solution, clusters.size)
        for (line in solution.lines) {
            if (line.clusterRange.isEmptyClusterRange()) continue
            assertTrue(
                line.clusterRange.first != 6 || line.repair != null,
                "。 must not start a line without a recorded repair",
            )
        }
    }

    @Test
    fun compressionEdgeRecordsPushInRepair() {
        // Width fits 3 naturally; cluster 3 is a comma whose glue can shrink
        // 8px. Pulling it up (4 clusters, overflow 8 == capacity) must record
        // a LineAdjustmentPushIn repair, and the line must sit at the limit.
        val clusters = listOf(
            cluster(0, "中", 16f),
            cluster(1, "中", 16f),
            cluster(2, "中", 16f),
            cluster(3, "，", 16f),
            cluster(4, "中", 16f),
            cluster(5, "中", 16f),
            cluster(6, "中", 16f),
        )
        val solution = breakLines(
            clusters, maxWidth = 56f,
            shrinkOpportunities = listOf(
                ShrinkOpportunity(clusterIndex = 3, tier = 5, capacity = 8f, channel = ShrinkChannel.TrailingGlue),
            ),
            lineAdjustmentPushIn = true,
        )
        assertTiles(solution, clusters.size)
        val compressed = kotlin.test.assertNotNull(
            solution.lines.firstOrNull { it.repair is RepairOption.PushIn },
            "expected a PushIn-compressed line, got ${solution.lines.map { it.repair }}",
        )
        assertEquals(0..3, compressed.clusterRange)
        assertTrue(compressed.adjustedWidth <= 56f + 0.01f, "compressed line must fit the measure")
        assertTrue(
            (compressed.repair as RepairOption.PushIn).reason.startsWith("LineAdjustmentPushIn"),
            "compression must be recorded as the fill-pass reason code",
        )
    }

    @Test
    fun compressionDisabledWithoutPushInFlag() {
        val clusters = listOf(
            cluster(0, "中", 16f),
            cluster(1, "中", 16f),
            cluster(2, "中", 16f),
            cluster(3, "，", 16f),
            cluster(4, "中", 16f),
        )
        val solution = breakLines(
            clusters, maxWidth = 56f,
            shrinkOpportunities = listOf(
                ShrinkOpportunity(clusterIndex = 3, tier = 5, capacity = 8f, channel = ShrinkChannel.TrailingGlue),
            ),
            lineAdjustmentPushIn = false,
        )
        assertTiles(solution, clusters.size)
        assertTrue(
            solution.lines.none { it.repair is RepairOption.PushIn && it.repair.reason.startsWith("LineAdjustmentPushIn") },
            "PushOutOnly must not produce fill push-ins",
        )
    }

    @Test
    fun overWideSingleClusterStillProgresses() {
        val clusters = listOf(
            cluster(0, "中", 16f),
            cluster(1, "Ｗ", 300f),
            cluster(2, "中", 16f),
        )
        val solution = breakLines(clusters, maxWidth = 48f)
        assertTiles(solution, clusters.size)
    }
}
