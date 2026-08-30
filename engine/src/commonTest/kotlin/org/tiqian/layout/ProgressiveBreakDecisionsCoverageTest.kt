package org.tiqian.layout

import org.tiqian.core.Cluster
import org.tiqian.core.TextRange
import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.assertFalse
import org.tiqian.test.trace.assertTrue
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

/**
 * Coverage for ProgressiveBreakDecisions.kt: the optional-argument defaults
 * of decideProgressiveBreak / progressiveCandidateAllowed, the empty scan
 * range, the rightmost-same-tier selection, the candidate guard's
 * out-of-map and outside-span arms, and every decideHyphenBreak outcome.
 */
class ProgressiveBreakDecisionsCoverageTest {
    private val testTrace = TestTraceRecorder("ProgressiveBreakDecisionsCoverageTest")


    private fun cluster(index: Int, text: String = "中", advance: Float = 16.0f) = Cluster(
        range = TextRange(index, index + 1),
        text = text,
        displayText = text,
        fontKey = "test",
        advance = advance,
    )

    private val span = TextRange(0, 5)

    @Test
    fun defaultsAdmitTheCleanTierWithoutGeometryInputs() {
        testTrace.section("defaultsAdmitTheCleanTierWithoutGeometryInputs")
        // No clusters / infinite limits: the priority helper bails to the
        // cleanest tier and the scan picks its rightmost boundary.
        val opportunities = mapOf(
            1 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Whitespace, span),
            2 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Emergency, span),
        )
        assertEquals(1, decideProgressiveBreak(lineStart = 0, overflowAt = 2, opportunities = opportunities))
        assertTrue(
            progressiveCandidateAllowed(lineStart = 0, rawGreedy = 2, candidateEnd = 3, opportunities = opportunities),
        )
    }

    @Test
    fun lineStartAtTheOverflowBoundaryScansAnEmptyRange() {
        testTrace.section("lineStartAtTheOverflowBoundaryScansAnEmptyRange")
        val opportunities = mapOf(
            2 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Emergency, span),
        )
        assertEquals(2, decideProgressiveBreak(lineStart = 2, overflowAt = 2, opportunities = opportunities))
    }

    @Test
    fun twoSameTierBoundariesPickTheRightmost() {
        testTrace.section("twoSameTierBoundariesPickTheRightmost")
        val clusters = List(5) { cluster(it) }
        val opportunities = mapOf(
            2 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Whitespace, span),
            4 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Whitespace, span),
        )
        assertEquals(
            4,
            decideProgressiveBreak(
                lineStart = 0,
                overflowAt = 4,
                opportunities = opportunities,
                adjustedClusters = clusters,
                lineLimit = 64.0f,
                maxCjkStretchPerGap = 8.0f,
            ),
        )
    }

    @Test
    fun visiblyLooseCleanTiersFallThroughToEmergency() {
        testTrace.section("visiblyLooseCleanTiersFallThroughToEmergency")
        // Every clean tier is far too loose at a 200px limit, and the
        // Emergency boundary is at least as far right, so the Emergency
        // priority wins and the scan returns boundary 4.
        val clusters = List(5) { cluster(it) }
        val opportunities = mapOf(
            2 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Whitespace, span),
            4 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Emergency, span),
        )
        assertEquals(
            4,
            decideProgressiveBreak(
                lineStart = 0,
                overflowAt = 4,
                opportunities = opportunities,
                adjustedClusters = clusters,
                lineLimit = 200.0f,
                maxCjkStretchPerGap = 8.0f,
            ),
        )
    }

    @Test
    fun aLeftwardEmergencyBoundaryKeepsTheBestCleanTier() {
        testTrace.section("aLeftwardEmergencyBoundaryKeepsTheBestCleanTier")
        // Emergency sits LEFT of the clean boundary, so the clean tier is
        // returned even though it is visibly loose.
        val clusters = List(5) { cluster(it) }
        val opportunities = mapOf(
            2 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Emergency, span),
            4 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Whitespace, span),
        )
        assertEquals(
            4,
            decideProgressiveBreak(
                lineStart = 0,
                overflowAt = 4,
                opportunities = opportunities,
                adjustedClusters = clusters,
                lineLimit = 200.0f,
                maxCjkStretchPerGap = 8.0f,
            ),
        )
    }

    @Test
    fun spanEdgeAndWhitespaceClustersDoNotCountAsTechnicalUnits() {
        testTrace.section("spanEdgeAndWhitespaceClustersDoNotCountAsTechnicalUnits")
        // The span covers clusters 1..3; cluster 0 is outside it and
        // cluster 1 is whitespace, so only clusters 2 and 3 count as
        // terminal technical source units in the density estimate.
        val clusters = listOf(
            cluster(0, "中"),
            cluster(1, " "),
            cluster(2, "a"),
            cluster(3, "b"),
        )
        val innerSpan = TextRange(1, 4)
        val opportunities = mapOf(
            2 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Whitespace, innerSpan),
            3 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Emergency, innerSpan),
        )
        // Two technical units remain, so the density path divides by the
        // technical gap count instead of falling to the CJK-gap branch.
        assertEquals(
            3,
            decideProgressiveBreak(
                lineStart = 0,
                overflowAt = 3,
                opportunities = opportunities,
                adjustedClusters = clusters,
                lineLimit = 200.0f,
                maxCjkStretchPerGap = 8.0f,
            ),
        )
    }

    @Test
    fun singleTechnicalUnitFallsBackToTheCjkGapDensity() {
        testTrace.section("singleTechnicalUnitFallsBackToTheCjkGapDensity")
        // Boundary 1 has only ONE technical unit inside the span (gap count
        // 0), so the density is the plain CJK deficit when no CJK boundary
        // exists, and deficit/gap when one does.
        val clusters = listOf(cluster(0, "a"), cluster(1, "b"), cluster(2, "c"))
        val oneUnitSpan = TextRange(0, 1)
        val opportunities = mapOf(
            1 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Whitespace, oneUnitSpan),
            2 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Emergency, oneUnitSpan),
        )
        assertEquals(
            2,
            decideProgressiveBreak(
                lineStart = 0,
                overflowAt = 2,
                opportunities = opportunities,
                adjustedClusters = clusters,
                lineLimit = 200.0f,
                maxCjkStretchPerGap = 8.0f,
                cjkInterCharBoundaries = setOf(1),
            ),
        )
    }

    @Test
    fun candidateOutsideTheClusterListIsAllowed() {
        testTrace.section("candidateOutsideTheClusterListIsAllowed")
        val opportunities = mapOf(
            1 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Emergency, span),
        )
        assertTrue(
            progressiveCandidateAllowed(
                lineStart = 0,
                rawGreedy = 1,
                candidateEnd = 5,
                opportunities = opportunities,
                adjustedClusters = listOf(cluster(0)),
            ),
        )
    }

    @Test
    fun candidatesOutsideTheActiveSpanAreAllowed() {
        testTrace.section("candidatesOutsideTheActiveSpanAreAllowed")
        val activeSpan = TextRange(5, 10)
        val opportunities = mapOf(
            1 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Emergency, activeSpan),
        )
        val clusters = List(4) { cluster(it) }
        // Source offset 2 <= span start 5.
        assertTrue(
            progressiveCandidateAllowed(
                lineStart = 0, rawGreedy = 1, candidateEnd = 2,
                opportunities = opportunities, adjustedClusters = clusters,
            ),
        )
        val trailingSpan = TextRange(0, 2)
        val trailingOpportunities = mapOf(
            1 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Emergency, trailingSpan),
        )
        // Source offset 2 >= span end 2.
        assertTrue(
            progressiveCandidateAllowed(
                lineStart = 0, rawGreedy = 1, candidateEnd = 2,
                opportunities = trailingOpportunities, adjustedClusters = clusters,
            ),
        )
        // Strictly inside the active span and not in the map: rejected.
        val innerSpan = TextRange(0, 4)
        val innerOpportunities = mapOf(
            1 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Emergency, innerSpan),
        )
        assertFalse(
            progressiveCandidateAllowed(
                lineStart = 0, rawGreedy = 1, candidateEnd = 2,
                opportunities = innerOpportunities, adjustedClusters = clusters,
            ),
        )
    }

    @Test
    fun candidatesOfADifferentSpanAreAllowed() {
        testTrace.section("candidatesOfADifferentSpanAreAllowed")
        val opportunities = mapOf(
            1 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Emergency, TextRange(0, 2)),
            3 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Whitespace, TextRange(2, 6)),
        )
        assertTrue(
            progressiveCandidateAllowed(0, 1, 3, opportunities),
        )
    }

    @Test
    fun sameTierPastTheRawGreedyIsAllowedAndWorseTiersAreNot() {
        testTrace.section("sameTierPastTheRawGreedyIsAllowedAndWorseTiersAreNot")
        val opportunities = mapOf(
            2 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Whitespace, span),
            3 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Whitespace, span),
            4 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Emergency, span),
        )
        assertTrue(progressiveCandidateAllowed(0, 2, 3, opportunities))
        assertFalse(progressiveCandidateAllowed(0, 2, 4, opportunities))
    }

    @Test
    fun candidatesBeforeTheRawGreedyMustMatchTheSelectedBoundary() {
        testTrace.section("candidatesBeforeTheRawGreedyMustMatchTheSelectedBoundary")
        val opportunities = mapOf(
            1 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Whitespace, span),
            2 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Whitespace, span),
            3 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Emergency, span),
        )
        // The selected boundary at rawGreedy 3 is the rightmost Whitespace, 2.
        assertTrue(progressiveCandidateAllowed(0, 3, 2, opportunities))
        assertFalse(progressiveCandidateAllowed(0, 3, 1, opportunities))
    }

    @Test
    fun hyphenBreakReturnsOverflowAtPlainWordBoundaries() {
        testTrace.section("hyphenBreakReturnsOverflowAtPlainWordBoundaries")
        val clusters = List(3) { cluster(it) }
        assertEquals(
            1,
            decideHyphenBreak(
                lineStart = 0, overflowAt = 1, adjustedClusters = clusters, lineLimit = 16.0f,
                hyphenBreakClusters = emptySet(), cjkInterCharBoundaries = emptySet(),
                maxCjkStretchPerGap = 8.0f,
            ),
        )
    }

    @Test
    fun overLongWordsMustHyphenateFromTheLineStart() {
        testTrace.section("overLongWordsMustHyphenateFromTheLineStart")
        val clusters = List(3) { cluster(it) }
        assertEquals(
            2,
            decideHyphenBreak(
                lineStart = 0, overflowAt = 2, adjustedClusters = clusters, lineLimit = 48.0f,
                hyphenBreakClusters = setOf(0, 1, 2), cjkInterCharBoundaries = emptySet(),
                maxCjkStretchPerGap = 8.0f,
            ),
        )
    }

    @Test
    fun aFittingWholeWordBreaksThere() {
        testTrace.section("aFittingWholeWordBreaksThere")
        val clusters = List(3) { cluster(it) }
        assertEquals(
            1,
            decideHyphenBreak(
                lineStart = 0, overflowAt = 2, adjustedClusters = clusters, lineLimit = 16.0f,
                hyphenBreakClusters = setOf(2), cjkInterCharBoundaries = emptySet(),
                maxCjkStretchPerGap = 8.0f,
            ),
        )
    }

    @Test
    fun sinoWesternGapsAbsorbingTheDeficitKeepTheWholeWord() {
        testTrace.section("sinoWesternGapsAbsorbingTheDeficitKeepTheWholeWord")
        // wholeWordEnd 2 leaves one interior boundary, whose 中西间距 cap of
        // 8 absorbs the whole 8px deficit before 汉字间距 is consulted.
        val clusters = List(4) { cluster(it) }
        assertEquals(
            2,
            decideHyphenBreak(
                lineStart = 0, overflowAt = 3, adjustedClusters = clusters, lineLimit = 40.0f,
                hyphenBreakClusters = setOf(3), cjkInterCharBoundaries = setOf(1),
                maxCjkStretchPerGap = 8.0f, sinoWesternBoundaries = setOf(1),
                sinoWesternStretchCap = 8.0f,
            ),
        )
    }

    @Test
    fun gaplessOrTooLooseLinesHyphenateInstead() {
        testTrace.section("gaplessOrTooLooseLinesHyphenateInstead")
        val clusters = List(4) { cluster(it) }
        // No CJK gaps between the line start and the whole-word end.
        assertEquals(
            3,
            decideHyphenBreak(
                lineStart = 0, overflowAt = 3, adjustedClusters = clusters, lineLimit = 60.0f,
                hyphenBreakClusters = setOf(3), cjkInterCharBoundaries = setOf(2),
                maxCjkStretchPerGap = 8.0f,
            ),
        )
        // One gap, 68px/gap far over the ceiling.
        assertEquals(
            3,
            decideHyphenBreak(
                lineStart = 0, overflowAt = 3, adjustedClusters = clusters, lineLimit = 100.0f,
                hyphenBreakClusters = setOf(3), cjkInterCharBoundaries = setOf(1),
                maxCjkStretchPerGap = 8.0f,
            ),
        )
        // One gap, 4px/gap under the ceiling: whole word.
        assertEquals(
            2,
            decideHyphenBreak(
                lineStart = 0, overflowAt = 3, adjustedClusters = clusters, lineLimit = 36.0f,
                hyphenBreakClusters = setOf(3), cjkInterCharBoundaries = setOf(1),
                maxCjkStretchPerGap = 8.0f,
            ),
        )
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
