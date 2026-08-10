package org.tiqian.compose

import kotlin.test.Test
import kotlin.test.assertContentEquals

class FittedLinePatternGeometryTest {
    @Test
    fun dashedRemainderIsSharedBetweenFullEdgeAnchoredDashes() {
        assertContentEquals(
            floatArrayOf(0f, 2f, 4.5f, 6.5f, 9f, 11f),
            fittedDashedLineSegments(
                spanLeft = 0f,
                spanRight = 11f,
                dashLength = 2f,
                gapLength = 2f,
            ),
        )
    }

    @Test
    fun shortDashedSpanBecomesOneVisibleDash() {
        assertContentEquals(
            floatArrayOf(0f, 3f),
            fittedDashedLineSegments(
                spanLeft = 0f,
                spanRight = 3f,
                dashLength = 2f,
                gapLength = 2f,
            ),
        )
    }

    @Test
    fun remainderIsSharedSoBothSpanEdgesHaveCompleteDots() {
        assertContentEquals(
            floatArrayOf(1f, 5.5f, 10f),
            fittedDottedLineCenters(
                spanLeft = 0f,
                spanRight = 11f,
                keptLeft = 0f,
                keptRight = 11f,
                dotDiameter = 2f,
                gapLength = 2f,
            ),
        )
    }

    @Test
    fun skipInkIntervalsKeepTheFittedSpanPatternWithoutCutDots() {
        assertContentEquals(
            floatArrayOf(5f),
            fittedDottedLineCenters(
                spanLeft = 0f,
                spanRight = 10f,
                keptLeft = 2f,
                keptRight = 8f,
                dotDiameter = 2f,
                gapLength = 2f,
            ),
        )
    }

    @Test
    fun aSpanShorterThanOneDotStillPaintsOneCenteredDot() {
        assertContentEquals(
            floatArrayOf(0.5f),
            fittedDottedLineCenters(
                spanLeft = 0f,
                spanRight = 1f,
                keptLeft = 0f,
                keptRight = 1f,
                dotDiameter = 2f,
                gapLength = 2f,
            ),
        )
    }
}
