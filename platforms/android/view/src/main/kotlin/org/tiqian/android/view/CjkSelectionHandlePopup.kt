/*
 * Selection-handle interaction follows Android's Editor.HandleView behavior. Android's source is
 * licensed under Apache 2.0; this implementation is independently expressed against Tiqian's
 * replay geometry and public framework APIs only.
 *
 * https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android17-release/core/java/android/widget/Editor.java
 */
package org.tiqian.android.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.PopupWindow
import kotlin.math.max

internal enum class CjkSelectionHandle {
    Start,
    End,
}

/**
 * Opaque endpoint identity exchanged with a handle popup.
 *
 * The popup only compares and retains these values for the short AOSP release-filter window. The
 * listener owns the implementation and may encode a paragraph-local offset or a logical document
 * anchor without flattening it into a global integer.
 */
internal interface CjkSelectionHandlePosition

internal interface CjkSelectionHandleListener {
    fun currentPosition(handle: CjkSelectionHandle): CjkSelectionHandlePosition

    fun onHandleDragStarted(handle: CjkSelectionHandle)

    /** Returns the endpoint actually accepted after snapping and crossing prevention. */
    fun onHandleDragMoved(
        handle: CjkSelectionHandle,
        viewX: Float,
        viewY: Float,
        rawX: Float,
        rawY: Float,
        fromTouchScreen: Boolean,
    ): CjkSelectionHandlePosition

    fun onHandleDragFinished(
        handle: CjkSelectionHandle,
        filteredPosition: CjkSelectionHandlePosition?,
        cancelled: Boolean,
    )
}

/**
 * A real, touchable window-level selection handle. Its popup may paint beyond every ancestor's
 * bounds, while its hotspot remains attached to a caret emitted by Tiqian.
 */
internal class CjkSelectionHandlePopup(
    private val host: View,
    private val handle: CjkSelectionHandle,
    drawable: Drawable,
    private val listener: CjkSelectionHandleListener,
) {
    private val content = HandleContentView(host.context, handle, drawable, ::onTouchEvent)
    private val popup = PopupWindow(
        host.context,
        null,
        android.R.attr.textSelectHandleWindowStyle,
    ).apply {
        contentView = content
        width = ViewGroup.LayoutParams.WRAP_CONTENT
        height = ViewGroup.LayoutParams.WRAP_CONTENT
        isTouchable = true
        isFocusable = false
        isOutsideTouchable = false
        isSplitTouchEnabled = true
        isClippingEnabled = false
        inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        windowLayoutType = WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL
        animationStyle = 0
    }
    private val touchUpFilter = CjkSelectionTouchUpFilter()
    private val hostInWindow = IntArray(2)
    private val hostOnScreen = IntArray(2)

    private var positionViewX = 0f
    private var positionViewY = 0f
    private var touchToWindowOffsetX = 0f
    private var touchToWindowOffsetY = 0f
    private var lastHostWindowX = 0
    private var lastHostWindowY = 0
    private var lastHostScreenX = 0
    private var lastHostScreenY = 0
    private var lastRecordedPosition: CjkSelectionHandlePosition? = null
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var dragging = false

    val isShowing: Boolean get() = popup.isShowing
    val isDragging: Boolean get() = dragging

    fun showAtCaret(viewX: Float, viewY: Float) {
        val cursorX = (viewX - CURSOR_WINDOW_BIAS_PX).toInt()
        positionViewX = cursorX - content.hotspotInView.toFloat()
        positionViewY = viewY.toInt().toFloat()
        host.getLocationInWindow(hostInWindow)
        val windowCaret = caretInWindow(cursorX.toFloat(), positionViewY)
        val left = windowCaret.first.toInt() - content.hotspotInView
        val top = windowCaret.second.toInt()
        if (popup.isShowing) {
            popup.update(left, top, -1, -1)
        } else {
            popup.showAtLocation(host, Gravity.NO_GRAVITY, left, top)
        }
    }

    fun dismiss() {
        if (dragging) {
            dragging = false
            activePointerId = MotionEvent.INVALID_POINTER_ID
            listener.onHandleDragFinished(handle, filteredPosition = null, cancelled = true)
        }
        popup.dismiss()
    }

    internal fun boundsOnScreen(outBounds: Rect): Boolean {
        if (!popup.isShowing) return false
        content.getLocationOnScreen(hostOnScreen)
        outBounds.set(
            hostOnScreen[0],
            hostOnScreen[1],
            hostOnScreen[0] + content.width,
            hostOnScreen[1] + content.height,
        )
        return true
    }

    private fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(event.actionIndex)
                listener.onHandleDragStarted(handle)
                lastRecordedPosition = listener.currentPosition(handle)
                touchUpFilter.start(lastRecordedPosition, SystemClock.uptimeMillis())
                rememberHostLocations()
                host.getLocationOnScreen(hostOnScreen)
                val xInWindow = event.rawX - lastHostScreenX + lastHostWindowX
                val yInWindow = event.rawY - lastHostScreenY + lastHostWindowY
                touchToWindowOffsetX = xInWindow - positionViewX
                touchToWindowOffsetY = yInWindow - positionViewY
                dragging = true
            }

            MotionEvent.ACTION_MOVE -> {
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex < 0) return true
                updateHostLocationsAndTouchOffsets()
                val rawX = event.rawX + event.getX(pointerIndex) - event.x
                val rawY = event.rawY + event.getY(pointerIndex) - event.y
                val xInWindow = rawX - lastHostScreenX + lastHostWindowX
                val yInWindow = rawY - lastHostScreenY + lastHostWindowY

                // Android's vertical hysteresis lets a downward-moving finger converge on the
                // ideal 0.7-handle-height contact point without making the caret jump abruptly.
                val previousVerticalOffset = touchToWindowOffsetY - lastHostWindowY
                val currentVerticalOffset = yInWindow - positionViewY - lastHostWindowY
                val ideal = content.idealVerticalOffset
                val newVerticalOffset = if (previousVerticalOffset < ideal) {
                    currentVerticalOffset.coerceIn(previousVerticalOffset, ideal)
                } else {
                    currentVerticalOffset.coerceIn(ideal, previousVerticalOffset)
                }
                touchToWindowOffsetY = newVerticalOffset + lastHostWindowY

                val acceptedPosition = listener.onHandleDragMoved(
                    handle = handle,
                    viewX = xInWindow - touchToWindowOffsetX + content.hotspotInView,
                    viewY = yInWindow - touchToWindowOffsetY + content.touchOffsetY,
                    rawX = rawX,
                    rawY = rawY,
                    fromTouchScreen = event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN),
                )
                if (acceptedPosition != lastRecordedPosition) {
                    lastRecordedPosition = acceptedPosition
                    touchUpFilter.add(acceptedPosition, SystemClock.uptimeMillis())
                }
            }

            MotionEvent.ACTION_UP -> {
                if (event.getPointerId(event.actionIndex) != activePointerId) return true
                val filtered = touchUpFilter.positionForTouchUp(SystemClock.uptimeMillis())
                dragging = false
                activePointerId = MotionEvent.INVALID_POINTER_ID
                listener.onHandleDragFinished(handle, filtered, cancelled = false)
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == activePointerId) {
                    val filtered = touchUpFilter.positionForTouchUp(SystemClock.uptimeMillis())
                    dragging = false
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    listener.onHandleDragFinished(handle, filtered, cancelled = false)
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                activePointerId = MotionEvent.INVALID_POINTER_ID
                listener.onHandleDragFinished(handle, filteredPosition = null, cancelled = true)
            }
        }
        return true
    }

    private fun rememberHostLocations() {
        host.getLocationInWindow(hostInWindow)
        host.getLocationOnScreen(hostOnScreen)
        lastHostWindowX = hostInWindow[0]
        lastHostWindowY = hostInWindow[1]
        lastHostScreenX = hostOnScreen[0]
        lastHostScreenY = hostOnScreen[1]
    }

    private fun updateHostLocationsAndTouchOffsets() {
        host.getLocationInWindow(hostInWindow)
        host.getLocationOnScreen(hostOnScreen)
        if (hostInWindow[0] != lastHostWindowX || hostInWindow[1] != lastHostWindowY) {
            touchToWindowOffsetX += hostInWindow[0] - lastHostWindowX
            touchToWindowOffsetY += hostInWindow[1] - lastHostWindowY
        }
        lastHostWindowX = hostInWindow[0]
        lastHostWindowY = hostInWindow[1]
        lastHostScreenX = hostOnScreen[0]
        lastHostScreenY = hostOnScreen[1]
    }

    private fun caretInWindow(viewX: Float, viewY: Float): Pair<Float, Float> {
        if (Build.VERSION.SDK_INT < 29) {
            return hostInWindow[0] + viewX to hostInWindow[1] + viewY
        }
        val matrix = Matrix()
        host.transformMatrixToGlobal(matrix)
        val point = floatArrayOf(viewX, viewY)
        matrix.mapPoints(point)
        host.getLocationOnScreen(hostOnScreen)
        val screenToWindowX = hostOnScreen[0] - hostInWindow[0]
        val screenToWindowY = hostOnScreen[1] - hostInWindow[1]
        return point[0] - screenToWindowX to point[1] - screenToWindowY
    }
}

@SuppressLint("ViewConstructor")
private class HandleContentView(
    context: Context,
    private val handle: CjkSelectionHandle,
    private val drawable: Drawable,
    private val touchListener: (MotionEvent) -> Boolean,
) : View(context) {
    private val minimumHandleSize = resolveFrameworkHandleMinimumSize(context)
    private val drawWidth = drawable.intrinsicWidth.coerceAtLeast(1)
    private val drawHeight = drawable.intrinsicHeight.coerceAtLeast(1)
    private val preferredWidth = max(drawWidth, minimumHandleSize)
    private val preferredHeight = max(drawHeight, minimumHandleSize)
    private val horizontalDrawableOffset = when (handle) {
        CjkSelectionHandle.Start -> preferredWidth - drawWidth
        CjkSelectionHandle.End -> 0
    }

    val hotspotInView: Int = horizontalDrawableOffset + selectionHandleHotspot(drawWidth, handle)
    val touchOffsetY: Float = -HANDLE_TOUCH_OFFSET_RATIO * preferredHeight
    val idealVerticalOffset: Float = HANDLE_IDEAL_VERTICAL_OFFSET_RATIO * preferredHeight

    init {
        drawable.callback = this
        isClickable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(preferredWidth, preferredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        drawable.setBounds(
            horizontalDrawableOffset,
            0,
            horizontalDrawableOffset + drawWidth,
            drawHeight,
        )
        drawable.draw(canvas)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (Build.VERSION.SDK_INT >= 29) {
            systemGestureExclusionRects = listOf(Rect(0, 0, width, height))
        }
    }

    // This popup surface represents a draggable endpoint, not an independently clickable action.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = touchListener(event)

    override fun verifyDrawable(who: Drawable): Boolean = who === drawable || super.verifyDrawable(who)

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        if (drawable.isStateful && drawable.setState(drawableState)) invalidate()
    }

    override fun jumpDrawablesToCurrentState() {
        super.jumpDrawablesToCurrentState()
        drawable.jumpToCurrentState()
    }
}

/** AOSP Editor.HandleView's five-sample release filter, separated for deterministic tests. */
internal class CjkSelectionTouchUpFilter {
    private val positions = arrayOfNulls<CjkSelectionHandlePosition>(HISTORY_SIZE)
    private val times = LongArray(HISTORY_SIZE)
    private var previousIndex = 0
    private var count = 0

    fun start(position: CjkSelectionHandlePosition?, timeMillis: Long) {
        count = 0
        add(position, timeMillis)
    }

    fun add(position: CjkSelectionHandlePosition?, timeMillis: Long) {
        previousIndex = (previousIndex + 1) % HISTORY_SIZE
        positions[previousIndex] = position
        times[previousIndex] = timeMillis
        count++
    }

    fun positionForTouchUp(nowMillis: Long): CjkSelectionHandlePosition? {
        var examined = 0
        var index = previousIndex
        val maximum = minOf(count, HISTORY_SIZE)
        while (examined < maximum && nowMillis - times[index] < TOUCH_UP_FILTER_DELAY_AFTER_MS) {
            examined++
            index = (previousIndex - examined + HISTORY_SIZE) % HISTORY_SIZE
        }
        return positions[index].takeIf {
            examined > 0 && examined < maximum &&
                nowMillis - times[index] > TOUCH_UP_FILTER_DELAY_BEFORE_MS
        }
    }

    private companion object {
        const val HISTORY_SIZE = 5
        const val TOUCH_UP_FILTER_DELAY_AFTER_MS = 150L
        const val TOUCH_UP_FILTER_DELAY_BEFORE_MS = 350L
    }
}

internal fun selectionHandleHotspot(drawableWidth: Int, handle: CjkSelectionHandle): Int =
    when (handle) {
        CjkSelectionHandle.Start -> drawableWidth * 3 / 4
        CjkSelectionHandle.End -> drawableWidth / 4
    }

private fun resolveFrameworkHandleMinimumSize(context: Context): Int =
    AndroidTextFrameworkCompat.selectionHandleMinimumSize(context)

private const val HANDLE_TOUCH_OFFSET_RATIO = 0.3f
private const val HANDLE_IDEAL_VERTICAL_OFFSET_RATIO = 0.7f
private const val CURSOR_WINDOW_BIAS_PX = 0.5f
