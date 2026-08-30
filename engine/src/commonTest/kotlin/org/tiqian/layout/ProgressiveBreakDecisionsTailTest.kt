package org.tiqian.layout

import org.tiqian.core.Cluster
import org.tiqian.core.TextRange
import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

/**
 * Coverage for the two middle guard directions of
 * progressiveBreakPriorityForLine: clusters present with an infinite line
 * limit, and a finite line limit with an infinite per-gap stretch ceiling.
 * Both must short-circuit to the cleanest tier instead of running the
 * density estimate.
 */
class ProgressiveBreakDecisionsTailTest {
    private val testTrace = TestTraceRecorder("ProgressiveBreakDecisionsTailTest")


    private fun cluster(index: Int) = Cluster(
        range = TextRange(index, index + 1),
        text = "中",
        displayText = "中",
        fontKey = "test",
        advance = 16.0f,
    )

    private val span = TextRange(0, 5)

    private val opportunities = mapOf(
        2 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Whitespace, span),
        4 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Emergency, span),
    )

    @Test
    fun infiniteLineLimitWithClustersAdmitsTheCleanestTier() {
        testTrace.section("infiniteLineLimitWithClustersAdmitsTheCleanestTier")
        // adjustedClusters is non-null but lineLimit stays infinite: the
        // guard fires at its second disjunct and the cleanest tier (the
        // Whitespace boundary 2) wins without any density comparison.
        assertEquals(
            2,
            decideProgressiveBreak(
                lineStart = 0,
                overflowAt = 4,
                opportunities = opportunities,
                adjustedClusters = List(5) { cluster(it) },
            ),
        )
    }

    @Test
    fun infiniteStretchCeilingWithFiniteLineLimitAdmitsTheCleanestTier() {
        testTrace.section("infiniteStretchCeilingWithFiniteLineLimitAdmitsTheCleanestTier")
        // A finite line limit with an infinite maxCjkStretchPerGap fires the
        // third disjunct: same cleanest-tier result even though the 200px
        // limit would otherwise hand the line to the Emergency boundary 4
        // (as visiblyLooseCleanTiersFallThroughToEmergency pins).
        assertEquals(
            2,
            decideProgressiveBreak(
                lineStart = 0,
                overflowAt = 4,
                opportunities = opportunities,
                adjustedClusters = List(5) { cluster(it) },
                lineLimit = 200.0f,
            ),
        )
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
