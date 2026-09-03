package org.tiqian.android.view

/** Quadratic edge ramp shared by View hosts and deterministic unit tests. */
internal fun cjkSelectionAutoScrollVelocity(
    armed: Boolean,
    pointerY: Float,
    viewportTop: Float,
    viewportBottom: Float,
    edgeSize: Float,
    maxVelocity: Float,
): Float {
    val viewportHeight = viewportBottom - viewportTop
    if (
        !armed || !pointerY.isFinite() || viewportHeight <= 0f ||
        edgeSize <= 0f || maxVelocity <= 0f
    ) {
        return 0f
    }
    val effectiveEdge = edgeSize.coerceAtMost(viewportHeight / 2f)
    val top = ((viewportTop + effectiveEdge - pointerY) / effectiveEdge).coerceIn(0f, 1f)
    if (top > 0f) return -maxVelocity * top * top
    val bottom = ((pointerY - (viewportBottom - effectiveEdge)) / effectiveEdge).coerceIn(0f, 1f)
    return maxVelocity * bottom * bottom
}
