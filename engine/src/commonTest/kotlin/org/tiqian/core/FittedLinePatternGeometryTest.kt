package org.tiqian.core

import kotlin.test.Test
import kotlin.test.assertContentEquals

class FittedLinePatternGeometryTest {
    @Test
    fun dashedRemainderIsSharedBetweenFullEdgeAnchoredDashes() {
        assertContentEquals(
            floatArrayOf(0f, 2f, 4.5f, 6.5f, 9f, 11f),
            fittedDashedLineSegments(0f, 11f, 2f, 2f),
        )
    }

    @Test
    fun shortDashedSpanBecomesOneVisibleDash() {
        assertContentEquals(
            floatArrayOf(0f, 3f),
            fittedDashedLineSegments(0f, 3f, 2f, 2f),
        )
    }

    @Test
    fun remainderIsSharedSoBothSpanEdgesHaveCompleteDots() {
        assertContentEquals(
            floatArrayOf(1f, 5.5f, 10f),
            fittedDottedLineCenters(0f, 11f, 0f, 11f, 2f, 2f),
        )
    }

    @Test
    fun skipInkIntervalsKeepTheFittedSpanPatternWithoutCutDots() {
        assertContentEquals(
            floatArrayOf(5f),
            fittedDottedLineCenters(0f, 10f, 2f, 8f, 2f, 2f),
        )
    }

    @Test
    fun aSpanShorterThanOneDotStillPaintsOneCenteredDot() {
        assertContentEquals(
            floatArrayOf(0.5f),
            fittedDottedLineCenters(0f, 1f, 0f, 1f, 2f, 2f),
        )
    }
}
