package org.tiqian.android.view

import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewTreeObserver
import org.tiqian.core.TextRange
import org.tiqian.core.cursorRect
import kotlin.math.max

/**
 * Owns the two popup handles for a standalone [CjkTextView] selection.
 *
 * The controller remains the source of selection and drag state. This class only projects that
 * state into window-level popups and keeps their geometry current across pre-draw passes.
 */
internal class CjkStandaloneSelectionHandles(
    private val host: CjkTextView,
    private val listener: CjkSelectionHandleListener,
    private val selection: () -> TextRange?,
    private val isSuppressed: () -> Boolean,
) {
    private val density = host.resources.displayMetrics.density
    private val startDrawable = themedDrawable(android.R.attr.textSelectHandleLeft)
    private val endDrawable = themedDrawable(android.R.attr.textSelectHandleRight)
    private var startPopup: CjkSelectionHandlePopup? = null
    private var endPopup: CjkSelectionHandlePopup? = null
    private var repositionerInstalled = false
    private val repositioner = ViewTreeObserver.OnPreDrawListener {
        update()
        true
    }

    val isShowing: Boolean
        get() = startPopup?.isShowing == true && endPopup?.isShowing == true

    val height: Float
        get() = max(
            startDrawable?.minimumHeight ?: 0,
            endDrawable?.minimumHeight ?: 0,
        ).takeIf { it > 0 }?.toFloat() ?: FALLBACK_HANDLE_HEIGHT_DP * density

    fun update() {
        val snapshot = host.layoutSnapshot
        val currentSelection = selection()
        if (
            isSuppressed() || currentSelection == null || !host.textIsSelectable ||
            !host.isAttachedToWindow || host.visibility != View.VISIBLE ||
            host.windowVisibility != View.VISIBLE || !host.hasWindowFocus() ||
            !host.isFocused || snapshot == null
        ) {
            dismiss()
            return
        }
        val startCursor = snapshot.replayIndex.cursorRect(snapshot.result, currentSelection.start)
        val endCursor = snapshot.replayIndex.cursorRect(snapshot.result, currentSelection.end)
        position(
            popup = startPopup(),
            viewX = host.toVisibleX(startCursor.left),
            viewY = host.toVisibleY(startCursor.bottom),
        )
        position(
            popup = endPopup(),
            viewX = host.toVisibleX(endCursor.left),
            viewY = host.toVisibleY(endCursor.bottom),
        )
        if (!repositionerInstalled) {
            host.viewTreeObserver.addOnPreDrawListener(repositioner)
            repositionerInstalled = true
        }
    }

    fun dismiss() {
        startPopup?.dismiss()
        endPopup?.dismiss()
        if (repositionerInstalled) {
            if (host.viewTreeObserver.isAlive) {
                host.viewTreeObserver.removeOnPreDrawListener(repositioner)
            }
            repositionerInstalled = false
        }
    }

    fun boundsOnScreen(handle: CjkSelectionHandle, outBounds: Rect): Boolean =
        when (handle) {
            CjkSelectionHandle.Start -> startPopup?.boundsOnScreen(outBounds) == true
            CjkSelectionHandle.End -> endPopup?.boundsOnScreen(outBounds) == true
        }

    private fun startPopup(): CjkSelectionHandlePopup =
        startPopup ?: CjkSelectionHandlePopup(
            host,
            CjkSelectionHandle.Start,
            startDrawable ?: fallbackDrawable(),
            listener,
        ).also { startPopup = it }

    private fun endPopup(): CjkSelectionHandlePopup =
        endPopup ?: CjkSelectionHandlePopup(
            host,
            CjkSelectionHandle.End,
            endDrawable ?: fallbackDrawable(),
            listener,
        ).also { endPopup = it }

    private fun position(popup: CjkSelectionHandlePopup, viewX: Float, viewY: Float) {
        if (popup.isDragging || host.isSelectionHotspotVisible(viewX, viewY)) {
            popup.showAtCaret(viewX, viewY)
        } else {
            popup.dismiss()
        }
    }

    private fun fallbackDrawable(): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(host.selectionColor)
        setSize(
            (FALLBACK_HANDLE_WIDTH_DP * density).toInt(),
            (FALLBACK_HANDLE_HEIGHT_DP * density).toInt(),
        )
    }

    private fun themedDrawable(attribute: Int): Drawable? {
        val values = host.context.obtainStyledAttributes(intArrayOf(attribute))
        return try {
            values.getDrawable(0)?.mutate()
        } finally {
            values.recycle()
        }
    }

    private companion object {
        const val FALLBACK_HANDLE_WIDTH_DP = 22f
        const val FALLBACK_HANDLE_HEIGHT_DP = 24f
    }
}
