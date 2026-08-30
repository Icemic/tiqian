package org.tiqian.layout

import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.assertFalse
import org.tiqian.test.trace.assertTrue
import org.tiqian.core.Cluster
import org.tiqian.core.TextRange
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

class ProgressiveTechnicalBreakTest {
    private val testTrace = TestTraceRecorder("ProgressiveTechnicalBreakTest")

    private fun cluster(index: Int, text: String, advance: Float) = Cluster(
        range = TextRange(index, index + 1),
        text = text,
        displayText = text,
        fontKey = "test",
        advance = advance,
    )

    @Test
    fun sourceWhitespaceCapacityKeepsStructuralTierAheadOfSyllable() {
        testTrace.section("sourceWhitespaceCapacityKeepsStructuralTierAheadOfSyllable")
        val span = TextRange(0, 6)
        val clusters = listOf(
            cluster(0, "a", 20f),
            cluster(1, " ", 4f),
            cluster(2, "b", 28f),
            cluster(3, "/", 28f),
            cluster(4, "c", 2f),
            cluster(5, "d", 20f),
        )
        val opportunities = mapOf(
            2 to ProgressiveBreakOpportunity(
                tier = ProgressiveBreakTier.Whitespace,
                spanRange = span,
                precedingWhitespaceStretchCapacity = 4f,
            ),
            4 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Structural, span),
            5 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Syllable, span),
        )

        assertEquals(
            4,
            decideProgressiveBreak(
                lineStart = 0,
                overflowAt = 5,
                opportunities = opportunities,
                adjustedClusters = clusters,
                lineLimit = 84f,
                maxCjkStretchPerGap = 8f,
            ),
        )
    }

    @Test
    fun lookaheadMayNotReplaceSelectedEmergencyBoundaryWithEarlierSameTierCut() {
        testTrace.section("lookaheadMayNotReplaceSelectedEmergencyBoundaryWithEarlierSameTierCut")
        val span = TextRange(0, 5)
        val clusters = List(5) { index -> cluster(index, ('a' + index).toString(), 20f) }
        val opportunities = mapOf(
            3 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Emergency, span),
            4 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Emergency, span),
        )

        fun allowed(candidateEnd: Int) = progressiveCandidateAllowed(
            lineStart = 0,
            rawGreedy = 4,
            candidateEnd = candidateEnd,
            opportunities = opportunities,
            adjustedClusters = clusters,
            lineLimit = 90f,
            maxCjkStretchPerGap = 8f,
        )

        assertFalse(allowed(3))
        assertTrue(allowed(4))
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
