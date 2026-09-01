package org.tiqian.android.view

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.ActionMode
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.Magnifier
import android.widget.PopupWindow
import org.tiqian.core.SourceBoundaryBias
import org.tiqian.core.TextRange
import org.tiqian.core.coerceSelectionOffset
import org.tiqian.core.cursorRect
import org.tiqian.core.getTextForCopy
import org.tiqian.core.selectionBoxes
import org.tiqian.core.selectionOffsetForPosition
import org.tiqian.core.selectionWordRangeForPosition
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

internal class CjkTextSelectionController(
    private val host: CjkTextView,
) {
    private enum class DraggedHandle { Anchor, Extent }

    private val density = host.resources.displayMetrics.density
    private val handleHitRadius = max(24f * density, ViewConfiguration.get(host.context).scaledTouchSlop * 2f)
    private val startHandle: Drawable? = themedDrawable(android.R.attr.textSelectHandleLeft)
    private val endHandle: Drawable? = themedDrawable(android.R.attr.textSelectHandleRight)
    private var anchor = -1
    private var extent = -1
    private var draggedHandle: DraggedHandle? = null
    private var actionMode: ActionMode? = null
    private var magnifier: Magnifier? = null
    private var pressedLink: CjkTextView.LinkHit? = null
    private var handledGesture = false
    private var cachedBoxes: List<org.tiqian.core.Rect> = emptyList()
    private var startHandlePopupInstance: SelectionHandlePopup? = null
    private var endHandlePopupInstance: SelectionHandlePopup? = null
    private var handleRepositionerInstalled = false
    private val handleRepositioner = ViewTreeObserver.OnPreDrawListener {
        updateHandles()
        true
    }

    private val gestures = GestureDetector(
        host.context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean {
                pressedLink = host.linkAt(event.x, event.y)
                handledGesture = pressedLink != null || host.textIsSelectable || hasSelection
                return handledGesture
            }

            override fun onSingleTapUp(event: MotionEvent): Boolean {
                host.performClick()
                val link = pressedLink
                pressedLink = null
                if (link != null && link == host.linkAt(event.x, event.y) && !hasSelection) {
                    host.activateLink(link)
                    return true
                }
                if (hasSelection) {
                    clearSelection()
                    return true
                }
                return link != null
            }

            override fun onDoubleTap(event: MotionEvent): Boolean =
                selectWordAt(event.x, event.y, showToolbar = true)

            override fun onLongPress(event: MotionEvent) {
                if (host.textIsSelectable && selectWordAt(event.x, event.y, showToolbar = true)) {
                    host.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
            }
        },
    )

    val hasSelection: Boolean get() = anchor >= 0 && extent >= 0 && anchor != extent

    val range: TextRange?
        get() = if (hasSelection) TextRange(min(anchor, extent), max(anchor, extent)) else null

    val boxes: List<org.tiqian.core.Rect> get() = cachedBoxes

    fun setSelection(start: Int, end: Int, showToolbar: Boolean = false): Boolean {
        val result = host.layoutSnapshot?.result ?: return false
        val newAnchor = result.coerceSelectionOffset(start, SourceBoundaryBias.Nearest)
        val newExtent = result.coerceSelectionOffset(end, SourceBoundaryBias.Nearest)
        if (newAnchor == newExtent) {
            clearSelection()
            return false
        }
        val changed = anchor != newAnchor || extent != newExtent
        anchor = newAnchor
        extent = newExtent
        if (changed) {
            updateCachedBoxes()
            host.onSelectionGeometryChanged()
        }
        if (showToolbar) showActionMode() else actionMode?.invalidateContentRect()
        return true
    }

    fun selectAll(showToolbar: Boolean = true): Boolean {
        val textLength = host.content.content.text.length
        if (textLength == 0) return false
        return setSelection(0, textLength, showToolbar)
    }

    fun clearSelection() {
        if (!hasSelection && anchor < 0 && extent < 0) return
        anchor = -1
        extent = -1
        cachedBoxes = emptyList()
        draggedHandle = null
        dismissMagnifier()
        actionMode?.finish()
        actionMode = null
        host.onSelectionGeometryChanged()
    }

    fun onContentOrLayoutChanged() {
        if (!hasSelection) return
        val length = host.content.content.text.length
        if (length == 0) {
            clearSelection()
            return
        }
        val currentAnchor = anchor.coerceIn(0, length)
        val currentExtent = extent.coerceIn(0, length)
        if (currentAnchor == currentExtent) clearSelection() else {
            anchor = currentAnchor
            extent = currentExtent
            updateCachedBoxes()
            actionMode?.invalidateContentRect()
            host.onSelectionGeometryChanged()
        }
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && hasSelection && host.textIsSelectable) {
            draggedHandle = handleAt(event.x, event.y)
            if (draggedHandle != null) {
                host.parent?.requestDisallowInterceptTouchEvent(true)
                actionMode?.finish()
                actionMode = null
                updateDraggedHandle(event)
                return true
            }
        }
        if (draggedHandle != null) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> updateDraggedHandle(event)
                MotionEvent.ACTION_UP -> {
                    updateDraggedHandle(event)
                    finishHandleDrag()
                }
                MotionEvent.ACTION_CANCEL -> finishHandleDrag()
            }
            return true
        }
        val handled = gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) pressedLink = null
        return handled || handledGesture
    }

    /**
     * Selection handles are window-level popups, like the platform text stack's: drawn above every
     * sibling and never clipped by host container bounds. The popups are visual only; drag
     * hit-testing stays in [onTouchEvent].
     */
    fun updateHandles() {
        val snapshot = host.layoutSnapshot
        if (!hasSelection || !host.textIsSelectable || !host.isAttachedToWindow || snapshot == null) {
            dismissHandles()
            return
        }
        val origin = IntArray(2)
        host.getLocationInWindow(origin)
        positionHandle(startHandlePopup(), min(anchor, extent), isStart = true, origin, snapshot)
        positionHandle(endHandlePopup(), max(anchor, extent), isStart = false, origin, snapshot)
        if (!handleRepositionerInstalled) {
            host.viewTreeObserver.addOnPreDrawListener(handleRepositioner)
            handleRepositionerInstalled = true
        }
    }

    internal val handlesShowing: Boolean
        get() = startHandlePopupInstance?.isShowing == true && endHandlePopupInstance?.isShowing == true

    private fun positionHandle(
        popup: SelectionHandlePopup,
        offset: Int,
        isStart: Boolean,
        origin: IntArray,
        snapshot: CjkTextView.LayoutSnapshot,
    ) {
        val cursor = snapshot.replayIndex.cursorRect(snapshot.result, offset)
        val x = origin[0] + host.toVisibleX(cursor.left).toInt()
        val y = origin[1] + host.toVisibleY(cursor.bottom).toInt()
        popup.showAt(selectionHandleLeft(x, popup.width, isStart), y)
    }

    private fun dismissHandles() {
        startHandlePopupInstance?.dismiss()
        endHandlePopupInstance?.dismiss()
        if (handleRepositionerInstalled) {
            host.viewTreeObserver.removeOnPreDrawListener(handleRepositioner)
            handleRepositionerInstalled = false
        }
    }

    fun copySelection(): Boolean {
        val range = range ?: return false
        val result = host.layoutSnapshot?.result ?: return false
        val selected = result.getTextForCopy(range)
        if (selected.isEmpty()) return false
        (host.context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.setPrimaryClip(ClipData.newPlainText(null, selected))
        return true
    }

    fun dispose() {
        actionMode?.finish()
        actionMode = null
        dismissMagnifier()
        dismissHandles()
    }

    private fun selectWordAt(x: Float, y: Float, showToolbar: Boolean): Boolean {
        if (!host.textIsSelectable) return false
        val snapshot = host.layoutSnapshot ?: return false
        val range = snapshot.replayIndex.selectionWordRangeForPosition(
            snapshot.result,
            host.toContentX(x),
            host.toContentY(y),
        ) ?: return false
        return setSelection(range.start, range.end, showToolbar)
    }

    private fun handleAt(x: Float, y: Float): DraggedHandle? {
        val snapshot = host.layoutSnapshot ?: return null
        val anchorRect = snapshot.replayIndex.cursorRect(snapshot.result, anchor)
        val extentRect = snapshot.replayIndex.cursorRect(snapshot.result, extent)
        val anchorDistance = distanceSquared(
            x, y,
            host.toVisibleX(anchorRect.left), host.toVisibleY(anchorRect.bottom),
        )
        val extentDistance = distanceSquared(
            x, y,
            host.toVisibleX(extentRect.left), host.toVisibleY(extentRect.bottom),
        )
        val hitDistance = handleHitRadius * handleHitRadius
        return when {
            anchorDistance > hitDistance && extentDistance > hitDistance -> null
            anchorDistance <= extentDistance -> DraggedHandle.Anchor
            else -> DraggedHandle.Extent
        }
    }

    private fun updateDraggedHandle(event: MotionEvent) {
        val snapshot = host.layoutSnapshot ?: return
        val offset = snapshot.replayIndex.selectionOffsetForPosition(
            snapshot.result,
            host.toContentX(event.x),
            host.toContentY(event.y - handleHitRadius * HANDLE_DRAG_TOUCH_LIFT_FACTOR),
        )
        when (draggedHandle) {
            DraggedHandle.Anchor -> anchor = offset
            DraggedHandle.Extent -> extent = offset
            null -> return
        }
        if (anchor == extent) {
            val length = snapshot.result.input.content.text.length
            when {
                extent < length -> extent = snapshot.result.coerceSelectionOffset(
                    extent + 1, SourceBoundaryBias.Forward,
                )
                anchor > 0 -> anchor = snapshot.result.coerceSelectionOffset(
                    anchor - 1, SourceBoundaryBias.Backward,
                )
            }
        }
        updateCachedBoxes()
        host.onSelectionGeometryChanged()
        showMagnifier(event.x, event.y)
    }

    private fun finishHandleDrag() {
        draggedHandle = null
        host.parent?.requestDisallowInterceptTouchEvent(false)
        dismissMagnifier()
        if (hasSelection) showActionMode()
    }

    private fun startHandlePopup(): SelectionHandlePopup =
        startHandlePopupInstance ?: SelectionHandlePopup(host, startHandle ?: fallbackHandleDrawable())
            .also { startHandlePopupInstance = it }

    private fun endHandlePopup(): SelectionHandlePopup =
        endHandlePopupInstance ?: SelectionHandlePopup(host, endHandle ?: fallbackHandleDrawable())
            .also { endHandlePopupInstance = it }

    private fun fallbackHandleDrawable(): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(host.selectionColor)
        setSize((22f * density).toInt(), (24f * density).toInt())
    }

    @SuppressLint("NewApi")
    private fun showMagnifier(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT < 28) return
        val value = magnifier ?: Magnifier.Builder(host).build().also { magnifier = it }
        value.show(x.coerceIn(0f, host.width.toFloat()), y.coerceIn(0f, host.height.toFloat()))
    }

    private fun dismissMagnifier() {
        if (Build.VERSION.SDK_INT >= 28) magnifier?.dismiss()
        magnifier = null
    }

    private fun showActionMode() {
        if (!hasSelection || !host.isAttachedToWindow) return
        actionMode?.invalidate()
        if (actionMode == null) {
            actionMode = host.startActionMode(ActionModeCallback(), ActionMode.TYPE_FLOATING)
        }
    }

    private inner class ActionModeCallback : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.add(Menu.NONE, MENU_COPY, 10, R.string.tiqian_copy)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            menu.add(Menu.NONE, MENU_SELECT_ALL, 20, R.string.tiqian_select_all)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            menu.add(Menu.NONE, MENU_SHARE, 30, R.string.tiqian_share)
            addProcessTextItems(menu)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.findItem(MENU_SELECT_ALL)?.isVisible = range?.let {
                it.start > 0 || it.end < host.content.content.text.length
            } ?: false
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = when (item.itemId) {
            MENU_COPY -> copySelection().also { if (it) mode.finish() }
            MENU_SELECT_ALL -> selectAll(showToolbar = false).also { mode.invalidateContentRect() }
            MENU_SHARE -> shareSelection().also { if (it) mode.finish() }
            else -> item.intent?.let { launchProcessText(it).also { launched -> if (launched) mode.finish() } }
                ?: false
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            if (actionMode === mode) actionMode = null
        }

        override fun onGetContentRect(mode: ActionMode, view: android.view.View, outRect: Rect) {
            val geometry = boxes
            if (geometry.isEmpty()) {
                outRect.set(0, 0, host.width, host.height)
                return
            }
            val left = geometry.minOf { host.toVisibleX(it.left) }
            val top = geometry.minOf { host.toVisibleY(it.top) }
            val right = geometry.maxOf { host.toVisibleX(it.right) }
            val bottom = geometry.maxOf { host.toVisibleY(it.bottom) }
            outRect.set(left.toInt(), top.toInt(), ceil(right).toInt(), ceil(bottom).toInt())
        }
    }

    @Suppress("DEPRECATION")
    private fun addProcessTextItems(menu: Menu) {
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
        host.context.packageManager.queryIntentActivities(intent, 0).forEachIndexed { index, info ->
            val activityInfo = info.activityInfo ?: return@forEachIndexed
            val samePackage = activityInfo.packageName == host.context.packageName
            val permissionGranted = activityInfo.permission?.let { permission ->
                host.context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            } ?: true
            if (!samePackage && (!activityInfo.exported || !permissionGranted)) return@forEachIndexed
            val processIntent = Intent(intent)
                .setClassName(activityInfo.packageName, activityInfo.name)
                .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
            menu.add(PROCESS_TEXT_GROUP, PROCESS_TEXT_ID_START + index, 100 + index, info.loadLabel(host.context.packageManager))
                .setIntent(processIntent)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
    }

    private fun launchProcessText(intent: Intent): Boolean {
        val selected = selectedText() ?: return false
        return runCatching {
            host.context.startActivity(
                Intent(intent)
                    .putExtra(Intent.EXTRA_PROCESS_TEXT, selected)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
    }

    private fun shareSelection(): Boolean {
        val selected = selectedText() ?: return false
        return runCatching {
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, selected)
            host.context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }

    private fun selectedText(): String? {
        val currentRange = range ?: return null
        return host.layoutSnapshot?.result?.getTextForCopy(currentRange)?.takeIf { it.isNotEmpty() }
    }

    private fun themedDrawable(attribute: Int): Drawable? {
        val values = host.context.obtainStyledAttributes(intArrayOf(attribute))
        return try {
            values.getDrawable(0)?.mutate()
        } finally {
            values.recycle()
        }
    }

    private fun distanceSquared(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return dx * dx + dy * dy
    }

    private fun updateCachedBoxes() {
        val snapshot = host.layoutSnapshot
        val currentRange = range
        cachedBoxes = if (snapshot != null && currentRange != null) {
            snapshot.replayIndex.selectionBoxes(snapshot.result, currentRange)
        } else {
            emptyList()
        }
    }

    private companion object {
        const val MENU_COPY = 1
        const val MENU_SELECT_ALL = 2
        const val MENU_SHARE = 3
        const val PROCESS_TEXT_GROUP = 100
        const val PROCESS_TEXT_ID_START = 1_000

        /**
         * Lifts a handle-drag touch sample above the finger, as a fraction of [handleHitRadius],
         * so the sampled offset stays on the handle's own text line instead of the line below.
         */
        const val HANDLE_DRAG_TOUCH_LIFT_FACTOR = 0.35f
    }
}

private class SelectionHandlePopup(private val anchor: CjkTextView, drawable: Drawable) {
    val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: drawable.minimumWidth
    private val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: drawable.minimumHeight
    private val popup = PopupWindow(
        ImageView(anchor.context).apply { setImageDrawable(drawable) },
        width,
        height,
    ).apply {
        isTouchable = false
        animationStyle = 0
    }

    val isShowing: Boolean get() = popup.isShowing

    fun showAt(x: Int, y: Int) {
        if (popup.isShowing) {
            popup.update(x, y, -1, -1)
        } else {
            popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
        }
    }

    fun dismiss() = popup.dismiss()
}

/**
 * Aligns the actual hotspot used by Android's horizontal selection-handle assets with the caret.
 * The transparent drawable bounds extend past that hotspot, so anchoring either outer edge makes
 * both handles visibly drift away from the selected text.
 */
internal fun selectionHandleLeft(cursorX: Int, drawableWidth: Int, isStart: Boolean): Int {
    val hotspotX = if (isStart) drawableWidth * 3 / 4 else drawableWidth / 4
    return cursorX - hotspotX
}
