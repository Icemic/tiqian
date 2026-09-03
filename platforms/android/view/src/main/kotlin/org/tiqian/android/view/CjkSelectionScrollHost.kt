package org.tiqian.android.view

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import kotlin.math.roundToInt

/**
 * Host-owned scrolling capability used while a selection gesture remains in an edge band.
 *
 * Scrolling and viewport geometry intentionally live in one capability. A virtualized reader
 * must not configure a consumer and a separately configured viewport which can drift apart while
 * a selection endpoint is being dragged.
 */
interface CjkSelectionScrollHost {
    /**
     * Requests a vertical scroll and returns the signed distance accepted by this host.
     *
     * A custom host should report the actual consumed distance. A positive result keeps the
     * endpoint refresh loop alive; zero means that the host reached a boundary or rejected the
     * request.
     */
    fun scrollBy(deltaPx: Float): Float

    /**
     * Writes the current scroll viewport in screen coordinates and returns whether it is visible.
     *
     * The rectangle must describe the same viewport that [scrollBy] moves. It may be reused by
     * the caller and therefore must be overwritten on every successful call.
     */
    fun viewportBoundsOnScreen(outBounds: Rect): Boolean

    companion object {
        /**
         * Adapts an ordinary scrollable [View] for the automatic-discovery path.
         *
         * This adapter deliberately stays in the public Android View layer. It does not know
         * about a particular application reader or virtualization type. Views whose scrolling
         * changes [View.scrollY] report that measured delta. Layout-managed [ViewGroup] instances
         * are measured from attached child movement. If neither signal exposes actual
         * consumption, this adapter returns zero instead of fabricating progress; such a host
         * should implement this capability directly.
         */
        @JvmStatic
        fun forView(view: View): CjkSelectionScrollHost = ViewSelectionScrollHost(view)
    }
}

private class ViewSelectionScrollHost(
    private val view: View,
) : CjkSelectionScrollHost {
    private val probeViews = arrayOfNulls<View>(MAX_CHILD_PROBES)
    private val probePositions = FloatArray(MAX_CHILD_PROBES)

    override fun scrollBy(deltaPx: Float): Float {
        if (!deltaPx.isFinite() || deltaPx == 0f) return 0f
        val requestedPx = deltaPx.roundToInt()
        if (requestedPx == 0) return 0f
        val direction = if (requestedPx > 0) 1 else -1
        if (!view.canScrollVertically(direction)) return 0f

        val probeCount = captureChildProbes()
        val before = view.scrollY
        view.scrollBy(0, requestedPx)
        val directConsumption = (view.scrollY - before).toFloat()
        if (directConsumption != 0f) return directConsumption
        for (index in 0 until probeCount) {
            val child = probeViews[index] ?: continue
            if (child.parent === view) {
                val childConsumption = probePositions[index] - childVisualTop(child)
                if (childConsumption != 0f) return childConsumption
            }
        }
        return 0f
    }

    override fun viewportBoundsOnScreen(outBounds: Rect): Boolean =
        view.getGlobalVisibleRect(outBounds)

    private fun captureChildProbes(): Int {
        probeViews.fill(null)
        val group = view as? ViewGroup ?: return 0
        if (group.childCount == 0) return 0
        var count = 0
        count = captureChildProbe(group, 0, count)
        count = captureChildProbe(group, group.childCount / 2, count)
        count = captureChildProbe(group, group.childCount - 1, count)
        return count
    }

    private fun captureChildProbe(group: ViewGroup, childIndex: Int, count: Int): Int {
        val child = group.getChildAt(childIndex)
        var existingIndex = 0
        while (existingIndex < count) {
            if (probeViews[existingIndex] === child) return count
            existingIndex++
        }
        probeViews[count] = child
        probePositions[count] = childVisualTop(child)
        return count + 1
    }

    private fun childVisualTop(child: View): Float = child.top + child.translationY

    private companion object {
        const val MAX_CHILD_PROBES = 3
    }
}
