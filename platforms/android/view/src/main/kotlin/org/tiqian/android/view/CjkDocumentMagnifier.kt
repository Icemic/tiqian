package org.tiqian.android.view

import android.annotation.SuppressLint
import android.graphics.Matrix
import android.os.Build
import android.view.View
import android.widget.Magnifier
import org.tiqian.core.cursorRect
import org.tiqian.core.getLineForOffset
import kotlin.math.max
import kotlin.math.min

/** Text-default magnifier projection for a handle inside one attached document fragment. */
internal class CjkDocumentMagnifier(
    private val host: CjkTextSurface,
) {
    private var magnifier: Magnifier? = null

    @SuppressLint("NewApi")
    fun show(
        view: CjkTextView,
        snapshot: CjkTextView.LayoutSnapshot,
        offset: Int,
        fixedOffsetOnSameFragment: Int?,
        isStart: Boolean,
        rawX: Float,
        rawY: Float,
    ) {
        if (Build.VERSION.SDK_INT < 28 || snapshot.result.lines.isEmpty()) return
        val scale = unrotatedAncestorScale(view) ?: return dismiss()
        val lineIndex = snapshot.result.getLineForOffset(offset)
            .coerceIn(0, snapshot.result.lines.lastIndex)
        val line = snapshot.result.lines[lineIndex]
        val positioned = snapshot.replayIndex.positionedClustersByLine
            .getOrElse(lineIndex) { emptyList() }
        var left = view.toVisibleX(positioned.firstOrNull()?.left ?: line.indent)
        var right = view.toVisibleX(positioned.lastOrNull()?.right ?: line.indent)
        if (
            fixedOffsetOnSameFragment != null &&
            snapshot.result.getLineForOffset(fixedOffsetOnSameFragment) == lineIndex
        ) {
            val fixedX = view.toVisibleX(
                snapshot.replayIndex.cursorRect(snapshot.result, fixedOffsetOnSameFragment).left,
            )
            if (isStart) right = min(right, fixedX) else left = max(left, fixedX)
        }
        if (left > right) return dismiss()
        val touch = rawToView(view, rawX, rawY)
        val lineHeight = line.bottom - line.top
        val lineTop = view.toVisibleY(line.top)
        val lineBottom = view.toVisibleY(line.bottom)
        if (touch.second < lineTop - lineHeight || touch.second > lineBottom + lineHeight) {
            return dismiss()
        }
        val value = magnifier ?: createTextDefaultMagnifier(host).also { magnifier = it }
        val contentWidth = value.width / value.zoom
        if (
            touch.first < left - contentWidth / 2f ||
            touch.first > right + contentWidth / 2f ||
            lineHeight * scale.second > value.height / value.zoom
        ) {
            return dismiss()
        }
        val source = viewPointToHost(
            view,
            touch.first.coerceIn(left, right),
            (lineTop + lineBottom) / 2f,
        )
        value.show(source.first, source.second)
    }

    fun dismiss() {
        if (Build.VERSION.SDK_INT >= 28) magnifier?.dismiss()
        magnifier = null
    }

    private fun unrotatedAncestorScale(view: View): Pair<Float, Float>? {
        var scaleX = view.scaleX
        var scaleY = view.scaleY
        var current: View? = view
        while (current != null && current !== host) {
            if (current.rotation != 0f || current.rotationX != 0f || current.rotationY != 0f) return null
            val parent = current.parent as? View ?: break
            if (parent !== host) {
                scaleX *= parent.scaleX
                scaleY *= parent.scaleY
            }
            current = parent
        }
        return scaleX to scaleY
    }

    private fun rawToView(view: View, rawX: Float, rawY: Float): Pair<Float, Float> {
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

    private fun viewPointToHost(view: View, x: Float, y: Float): Pair<Float, Float> {
        if (Build.VERSION.SDK_INT >= 29) {
            val viewToGlobal = Matrix()
            view.transformMatrixToGlobal(viewToGlobal)
            val hostToGlobal = Matrix()
            host.transformMatrixToGlobal(hostToGlobal)
            val globalToHost = Matrix()
            if (hostToGlobal.invert(globalToHost)) {
                val point = floatArrayOf(x, y)
                viewToGlobal.mapPoints(point)
                globalToHost.mapPoints(point)
                return point[0] to point[1]
            }
        }
        val viewLocation = IntArray(2).also(view::getLocationOnScreen)
        val hostLocation = IntArray(2).also(host::getLocationOnScreen)
        return viewLocation[0] - hostLocation[0] + x to viewLocation[1] - hostLocation[1] + y
    }
}
