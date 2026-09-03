package org.tiqian.android.view

import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import org.tiqian.core.cursorRect
import kotlin.math.max

/** Popup ownership and caret projection for the two document selection handles. */
internal class CjkDocumentSelectionHandles(
    private val host: CjkTextSurface,
    private val listener: CjkSelectionHandleListener,
    private val selection: () -> Pair<CjkDocumentSelectionAnchor, CjkDocumentSelectionAnchor>?,
    private val anchorView: (CjkDocumentSelectionAnchor) -> CjkTextView?,
    private val canPresent: () -> Boolean,
) {
    private val startDrawable = themedDrawable(android.R.attr.textSelectHandleLeft)
    private val endDrawable = themedDrawable(android.R.attr.textSelectHandleRight)
    private var startPopup: CjkSelectionHandlePopup? = null
    private var endPopup: CjkSelectionHandlePopup? = null
    private var startFallbackColor: Int? = null
    private var endFallbackColor: Int? = null

    val height: Float
        get() = max(
            startDrawable?.minimumHeight ?: 0,
            endDrawable?.minimumHeight ?: 0,
        ).takeIf { it > 0 }?.toFloat() ?: 24f * host.densityValue

    fun update() {
        if (!canPresent()) return dismiss()
        val normalized = selection() ?: return dismiss()
        position(CjkSelectionHandle.Start, anchorView(normalized.first), normalized.first)
        position(CjkSelectionHandle.End, anchorView(normalized.second), normalized.second)
    }

    fun dismiss() {
        startPopup?.dismiss()
        endPopup?.dismiss()
    }

    fun boundsOnScreen(handle: CjkSelectionHandle, outBounds: Rect): Boolean = when (handle) {
        CjkSelectionHandle.Start -> startPopup?.boundsOnScreen(outBounds) == true
        CjkSelectionHandle.End -> endPopup?.boundsOnScreen(outBounds) == true
    }

    fun isDragging(handle: CjkSelectionHandle): Boolean = popup(handle)?.isDragging == true

    private fun position(
        handle: CjkSelectionHandle,
        view: CjkTextView?,
        anchor: CjkDocumentSelectionAnchor,
    ) {
        if (view == null || !view.isShown || !view.isAttachedToWindow) {
            popup(handle)?.let { if (!it.isDragging) it.dismiss() }
            return
        }
        val popup = popup(handle, view.selectionColor)
        val snapshot = view.layoutSnapshot ?: return
        val caret = snapshot.replayIndex.cursorRect(snapshot.result, anchor.offset)
        val localX = view.toVisibleX(caret.left)
        val localY = view.toVisibleY(caret.bottom)
        val localVisible = Rect()
        val point = viewPointToHost(view, localX, localY)
        if (
            popup.isDragging ||
            view.getLocalVisibleRect(localVisible) &&
            localX in localVisible.left.toFloat()..localVisible.right.toFloat() &&
            localY in localVisible.top.toFloat()..localVisible.bottom.toFloat()
        ) {
            popup.showAtCaret(point.first, point.second)
        } else {
            popup.dismiss()
        }
    }

    private fun popup(handle: CjkSelectionHandle): CjkSelectionHandlePopup? = when (handle) {
        CjkSelectionHandle.Start -> startPopup
        CjkSelectionHandle.End -> endPopup
    }

    private fun popup(handle: CjkSelectionHandle, selectionColor: Int): CjkSelectionHandlePopup {
        val themed = if (handle == CjkSelectionHandle.Start) startDrawable else endDrawable
        if (themed == null) {
            val previousColor = if (handle == CjkSelectionHandle.Start) {
                startFallbackColor
            } else {
                endFallbackColor
            }
            if (previousColor != null && previousColor != selectionColor) {
                popup(handle)?.dismiss()
                if (handle == CjkSelectionHandle.Start) startPopup = null else endPopup = null
            }
        }
        popup(handle)?.let { return it }
        return CjkSelectionHandlePopup(
            host,
            handle,
            themed ?: fallbackDrawable(selectionColor),
            listener,
        ).also { popup ->
            if (handle == CjkSelectionHandle.Start) {
                startPopup = popup
                if (themed == null) startFallbackColor = selectionColor
            } else {
                endPopup = popup
                if (themed == null) endFallbackColor = selectionColor
            }
        }
    }

    private fun fallbackDrawable(selectionColor: Int): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(selectionColor)
        val density = host.densityValue
        setSize((22f * density).toInt(), (24f * density).toInt())
    }

    private fun themedDrawable(attribute: Int): Drawable? {
        val values = host.context.obtainStyledAttributes(intArrayOf(attribute))
        return try {
            values.getDrawable(0)?.mutate()
        } finally {
            values.recycle()
        }
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
