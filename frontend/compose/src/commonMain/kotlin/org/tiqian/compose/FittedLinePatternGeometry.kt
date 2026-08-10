package org.tiqian.compose

import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * End-anchored dash fitting for a dotted line.
 *
 * The first and last dots meet the span edges, while any remainder is shared by all intervening
 * gaps. [keptLeft] and [keptRight] only apply skip-ink clipping; they do not restart the pattern.
 */
internal fun fittedDottedLineCenters(
    spanLeft: Float,
    spanRight: Float,
    keptLeft: Float,
    keptRight: Float,
    dotDiameter: Float,
    gapLength: Float,
): FloatArray {
    require(
        spanLeft.isFinite() && spanRight.isFinite() &&
            keptLeft.isFinite() && keptRight.isFinite(),
    )
    require(dotDiameter.isFinite() && dotDiameter > 0f)
    require(gapLength.isFinite() && gapLength >= 0f)
    if (spanRight <= spanLeft || keptRight <= keptLeft) return FloatArray(0)

    val radius = dotDiameter / 2f
    val spanWidth = spanRight - spanLeft
    val targetPitch = dotDiameter + gapLength
    val fittedCount = ((spanWidth + gapLength) / targetPitch).roundToInt().coerceAtLeast(1)
    val nonOverlappingCount = floor(spanWidth / dotDiameter).toInt().coerceAtLeast(1)
    val count = min(fittedCount, nonOverlappingCount)

    if (count == 1) {
        val center = (spanLeft + spanRight) / 2f
        val completeDotFits =
            center - radius >= keptLeft - DOTTED_CENTER_EPSILON &&
                center + radius <= keptRight + DOTTED_CENTER_EPSILON
        val shortSpanIsFullyKept =
            spanWidth < dotDiameter &&
                keptLeft <= spanLeft + DOTTED_CENTER_EPSILON &&
                keptRight >= spanRight - DOTTED_CENTER_EPSILON
        return if (completeDotFits || shortSpanIsFullyKept) floatArrayOf(center) else FloatArray(0)
    }

    val firstCenter = spanLeft + radius
    val fittedPitch = (spanWidth - dotDiameter) / (count - 1)
    return FloatArray(count) { index -> firstCenter + index * fittedPitch }
        .filter { center ->
            center - radius >= keptLeft - DOTTED_CENTER_EPSILON &&
                center + radius <= keptRight + DOTTED_CENTER_EPSILON
        }
        .toFloatArray()
}

private const val DOTTED_CENTER_EPSILON = 0.001f

/**
 * End-anchored dash fitting expressed as flat visible `[left, right]` pairs.
 *
 * Dash length stays fixed whenever at least two dashes fit. The remainder is shared by the
 * intervening gaps, so the first and last dashes meet the span edges. A shorter span becomes one
 * dash instead of disappearing or leaving an unmatched end gap.
 */
internal fun fittedDashedLineSegments(
    spanLeft: Float,
    spanRight: Float,
    dashLength: Float,
    gapLength: Float,
): FloatArray {
    require(spanLeft.isFinite() && spanRight.isFinite())
    require(dashLength.isFinite() && dashLength > 0f)
    require(gapLength.isFinite() && gapLength >= 0f)
    if (spanRight <= spanLeft) return FloatArray(0)

    val spanWidth = spanRight - spanLeft
    if (spanWidth < dashLength * 2f) return floatArrayOf(spanLeft, spanRight)

    val fittedCount = ((spanWidth + gapLength) / (dashLength + gapLength))
        .roundToInt()
        .coerceAtLeast(2)
    val nonOverlappingCount = floor(spanWidth / dashLength).toInt().coerceAtLeast(2)
    val count = min(fittedCount, nonOverlappingCount)
    val fittedGap = (spanWidth - count * dashLength) / (count - 1)

    return FloatArray(count * 2) { coordinateIndex ->
        val dashIndex = coordinateIndex / 2
        val dashLeft = spanLeft + dashIndex * (dashLength + fittedGap)
        if (coordinateIndex % 2 == 0) dashLeft else dashLeft + dashLength
    }
}
