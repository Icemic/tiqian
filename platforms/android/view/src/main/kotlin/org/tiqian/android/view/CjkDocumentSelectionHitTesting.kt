package org.tiqian.android.view

import android.graphics.Matrix
import android.os.Build

/** Document hit testing that remains independent from selection state and presentation. */
internal fun selectableAtRaw(
    candidates: Collection<CjkTextView>,
    rawX: Float,
    rawY: Float,
): CjkTextView? {
    val views = candidates.filter { it.isAttachedToWindow && it.isShown }
    if (views.isEmpty()) return null
    views.firstOrNull { view ->
        val local = rawToView(view, rawX, rawY)
        local.first in 0f..view.width.toFloat() && local.second in 0f..view.height.toFloat()
    }?.let { return it }
    return views.minBy { view ->
        val local = rawToView(view, rawX, rawY)
        val dx = when {
            local.first < 0f -> -local.first
            local.first > view.width -> local.first - view.width
            else -> 0f
        }
        val dy = when {
            local.second < 0f -> -local.second
            local.second > view.height -> local.second - view.height
            else -> 0f
        }
        dx * dx + dy * dy
    }
}

internal fun rawToView(view: CjkTextView, rawX: Float, rawY: Float): Pair<Float, Float> {
    if (Build.VERSION.SDK_INT >= 29) {
        val global = Matrix()
        view.transformMatrixToGlobal(global)
        val inverse = Matrix()
        if (global.invert(inverse)) {
            val point = floatArrayOf(rawX, rawY)
            inverse.mapPoints(point)
            return point[0] to point[1]
        }
    }
    val location = IntArray(2).also(view::getLocationOnScreen)
    return rawX - location[0] to rawY - location[1]
}
