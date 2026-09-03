package org.tiqian.android.view

import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.View
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Visible ActionMode anchor geometry derived solely from attached selection projections. */
internal object CjkDocumentSelectionGeometry {
    fun calculate(
        host: CjkTextSurface,
        views: Collection<CjkTextView>,
        handleHeight: Float,
        outRect: Rect,
    ): Boolean {
        var left = Float.POSITIVE_INFINITY
        var top = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        var bottom = Float.NEGATIVE_INFINITY
        views.forEach { view ->
            if (!view.isShown || !view.isAttachedToWindow) return@forEach
            view.layoutSnapshot ?: return@forEach
            val localVisible = Rect()
            if (!view.getLocalVisibleRect(localVisible)) return@forEach
            view.currentSelectionBoxes.forEach { box ->
                val local = RectF(
                    view.toVisibleX(box.left),
                    view.toVisibleY(box.top),
                    view.toVisibleX(box.right),
                    view.toVisibleY(box.bottom),
                )
                if (!local.intersect(RectF(localVisible))) return@forEach
                val mapped = mapViewRect(host, view, local)
                left = min(left, mapped.left)
                top = min(top, mapped.top)
                right = max(right, mapped.right)
                bottom = max(bottom, mapped.bottom)
            }
        }
        if (!left.isFinite()) return false
        val visible = Rect()
        host.getLocalVisibleRect(visible)
        outRect.set(
            max(visible.left, floor(left).toInt()),
            max(visible.top, floor(top).toInt()),
            min(visible.right, ceil(right).toInt()),
            min(visible.bottom, ceil(bottom + handleHeight).toInt()),
        )
        return !outRect.isEmpty
    }

    private fun mapViewRect(host: View, view: View, rect: RectF): RectF {
        if (Build.VERSION.SDK_INT >= 29) {
            val viewToGlobal = Matrix()
            view.transformMatrixToGlobal(viewToGlobal)
            val hostToGlobal = Matrix()
            host.transformMatrixToGlobal(hostToGlobal)
            val globalToHost = Matrix()
            if (hostToGlobal.invert(globalToHost)) {
                return RectF(rect).also { mapped ->
                    viewToGlobal.mapRect(mapped)
                    globalToHost.mapRect(mapped)
                }
            }
        }
        val viewLocation = IntArray(2).also(view::getLocationOnScreen)
        val hostLocation = IntArray(2).also(host::getLocationOnScreen)
        return RectF(rect).apply {
            offset(
                (viewLocation[0] - hostLocation[0]).toFloat(),
                (viewLocation[1] - hostLocation[1]).toFloat(),
            )
        }
    }
}
