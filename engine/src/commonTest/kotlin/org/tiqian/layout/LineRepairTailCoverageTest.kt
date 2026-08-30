package org.tiqian.layout

import org.tiqian.core.Cluster
import org.tiqian.core.LineEndReason
import org.tiqian.core.TextRange
import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

/**
 * Coverage for the fill-pull tier comparisons when the break that opened the
 * previous line and the break after the pulled group belong to different
 * technical spans: both the promotion and the degradation guards require
 * equal span ranges, so neither fires and the plain pull proceeds.
 */
class LineRepairTailCoverageTest {
    private val testTrace = TestTraceRecorder("LineRepairTailCoverageTest")


    private fun c(text: String, index: Int, advance: Float = 16.0f): Cluster = Cluster(
        range = TextRange(index, index + 1),
        text = text,
        displayText = text,
        fontKey = "k",
        advance = advance,
    )

    @Test
    fun fillPullAcrossDifferentTechnicalSpansSkipsTierComparisons() {
        testTrace.section("fillPullAcrossDifferentTechnicalSpansSkipsTierComparisons")
        val natural = (0 until 8).map { c("X", it) }
        val lines = listOf(
            rebuildLine(0..3, natural, natural, LineEndReason.AutoWrap, null),
            rebuildLine(4..7, natural, natural, LineEndReason.AutoWrap, null),
        )
        val result = applyFillPushIn(
            lines, natural, natural, 100.0f,
            emptyList(),
            0.0f,
            1.0f,
            null,
            emptySet(),
            UnbreakableRanges(listOf(4..5)),
            10,
            emptySet(),
            mapOf(
                4 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Structural, TextRange(0, 4)),
                6 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Syllable, TextRange(4, 8)),
            ),
        )
        assertEquals(0..5, result[0].clusterRange)
        assertEquals(6..7, result[1].clusterRange)
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
