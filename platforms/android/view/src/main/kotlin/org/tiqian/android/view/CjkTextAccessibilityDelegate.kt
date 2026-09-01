package org.tiqian.android.view

import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import org.tiqian.core.TextRange
import org.tiqian.core.getBoundingBoxes

internal class CjkTextAccessibilityDelegate(
    private val host: CjkTextView,
) {
    fun install() {
        // AccessibilityDelegateCompat serializes ClickableSpan identity into node extras and routes
        // TalkBack's span action back to ClickableSpan.onClick without inventing a second text tree.
        ViewCompat.setAccessibilityDelegate(host, AccessibilityDelegateCompat())
    }

    fun invalidateRoot() {
        host.sendAccessibilityEventIfEnabled(
            android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
        )
    }

    fun populateHostNode(info: AccessibilityNodeInfo) {
        val text = host.content.content.text
        info.className = android.widget.TextView::class.java.name
        info.text = accessibilityText(text)
        info.isMultiLine = true
        info.isClickable = host.links().isNotEmpty()
        val selection = host.selection
        info.setTextSelection(selection?.start ?: -1, selection?.end ?: -1)
        if (host.textIsSelectable && text.isNotEmpty()) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_SELECTION)
        }
        if (selection != null) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_COPY)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            info.isTextSelectable = host.textIsSelectable
        }
        if (Build.VERSION.SDK_INT >= 26) {
            info.availableExtraData = buildList {
                add(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY)
                if (Build.VERSION.SDK_INT >= 36) {
                    add(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_IN_WINDOW_KEY)
                }
            }
        }
    }

    fun performHostAction(action: Int, arguments: Bundle?): Boolean = when (action) {
        AccessibilityNodeInfo.ACTION_SET_SELECTION -> {
            if (!host.textIsSelectable) return false
            val start = arguments?.getInt(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,
                -1,
            ) ?: -1
            val end = arguments?.getInt(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                -1,
            ) ?: -1
            if (start < 0 || end < 0) {
                host.clearSelection()
                true
            } else {
                host.setSelection(start, end)
            }
        }
        AccessibilityNodeInfo.ACTION_COPY -> host.copySelection()
        else -> false
    }

    fun addExtraData(info: AccessibilityNodeInfo, key: String, arguments: Bundle?) {
        if (Build.VERSION.SDK_INT < 26) return
        val coordinateSpace = when {
            key == AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY -> CoordinateSpace.Screen
            Build.VERSION.SDK_INT >= 36 &&
                key == AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_IN_WINDOW_KEY ->
                CoordinateSpace.Window
            else -> return
        }
        val start = arguments?.getInt(
            AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX,
            -1,
        ) ?: -1
        val length = arguments?.getInt(
            AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH,
            -1,
        ) ?: -1
        if (start < 0 || length < 0 || length > MAX_CHARACTER_LOCATION_REQUEST) return
        val result = host.layoutResult ?: return
        val origin = IntArray(2)
        when (coordinateSpace) {
            CoordinateSpace.Screen -> host.getLocationOnScreen(origin)
            CoordinateSpace.Window -> host.getLocationInWindow(origin)
        }
        val output = arrayOfNulls<Parcelable>(length)
        repeat(length) { index ->
            val offset = start + index
            if (offset !in result.input.content.text.indices) return@repeat
            val boxes = result.getBoundingBoxes(TextRange(offset, offset + 1))
            if (boxes.isEmpty()) return@repeat
            val left = boxes.minOf { it.left }
            val top = boxes.minOf { it.top }
            val right = boxes.maxOf { it.right }
            val bottom = boxes.maxOf { it.bottom }
            output[index] = RectF(
                origin[0] + host.toVisibleX(left),
                origin[1] + host.toVisibleY(top),
                origin[0] + host.toVisibleX(right),
                origin[1] + host.toVisibleY(bottom),
            )
        }
        info.extras.putParcelableArray(key, output)
    }

    private fun accessibilityText(source: String): CharSequence {
        if (source.isEmpty()) return source
        val result = SpannableString(source)
        host.links().forEach { link ->
            val start = link.range.start.coerceIn(0, source.length)
            val end = link.range.end.coerceIn(start, source.length)
            if (start == end) return@forEach
            result.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        host.activateLink(link)
                    }
                },
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return result
    }

    private enum class CoordinateSpace { Screen, Window }

    private companion object {
        const val MAX_CHARACTER_LOCATION_REQUEST = 10_000
    }
}
