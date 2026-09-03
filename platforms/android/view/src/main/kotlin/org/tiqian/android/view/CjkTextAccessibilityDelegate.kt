package org.tiqian.android.view

import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import org.tiqian.core.TextRange
import org.tiqian.core.getBoundingBoxes
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

internal class CjkTextAccessibilityDelegate(
    private val host: CjkTextView,
) {
    private var accessibilitySelectionStart = UNDEFINED_SELECTION
    private var accessibilitySelectionEnd = UNDEFINED_SELECTION
    private var hasExplicitAccessibilitySelection = false
    private var applyingAccessibilitySelection = false
    private var applyingContentRevision = false
    private var applyingDocumentBinding = false
    private val accessibilityLinkSpans = CjkAccessibilityLinkSpans { host.activateLink(it) }

    val ownsSelectionTransition: Boolean
        get() = applyingAccessibilitySelection || applyingContentRevision || applyingDocumentBinding

    fun install() {
        // AccessibilityDelegateCompat serializes ClickableSpan identity into node extras and routes
        // TalkBack's span action back to ClickableSpan.onClick without inventing a second text tree.
        ViewCompat.setAccessibilityDelegate(host, AccessibilityDelegateCompat())
    }

    fun invalidateRoot() {
        sanitizeAccessibilitySelection()
        host.sendAccessibilityEventIfEnabled(
            android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
        )
    }

    fun onContentRevisionStarted(textChanged: Boolean) {
        if (textChanged) {
            applyingContentRevision = true
            clearAccessibilitySelectionState()
        }
        accessibilityLinkSpans.reset()
    }

    fun onContentRevisionFinished(textChanged: Boolean) {
        if (!textChanged) {
            invalidateRoot()
            return
        }
        applyingContentRevision = false
        host.sendAccessibilityEventIfEnabled(AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED)
        host.sendAccessibilityContentChangedIfEnabled(AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT)
    }

    fun onDocumentBindingStarted() {
        applyingDocumentBinding = true
    }

    fun onDocumentBindingFinished(
        contentChanged: Boolean,
        textChanged: Boolean,
        identityChanged: Boolean,
    ) {
        if (textChanged || identityChanged) clearAccessibilitySelectionState()
        if (contentChanged || identityChanged) accessibilityLinkSpans.reset()
        applyingDocumentBinding = false

        when {
            textChanged -> {
                host.sendAccessibilityEventIfEnabled(
                    AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
                )
                host.sendAccessibilityContentChangedIfEnabled(
                    AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT,
                )
            }

            identityChanged -> {
                host.sendAccessibilityEventIfEnabled(
                    AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
                )
                invalidateRoot()
            }

            contentChanged -> invalidateRoot()
        }
    }

    fun onDocumentBindingCancelled() {
        applyingDocumentBinding = false
    }

    fun onHostSelectionChanged() {
        if (applyingAccessibilitySelection) return
        val visual = host.selection
        val accessibility = orderedAccessibilitySelection()
        if (accessibility != visual) clearAccessibilitySelectionState()
    }

    fun populateHostNode(info: AccessibilityNodeInfo) {
        val text = host.content.content.text
        info.className = android.widget.TextView::class.java.name
        info.text = accessibilityText(text)
        info.isMultiLine = true
        val selection = reportedSelection()
        info.setTextSelection(selection.first, selection.second)
        if (text.isNotEmpty()) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_NEXT_AT_MOVEMENT_GRANULARITY)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY)
            info.movementGranularities = MOVEMENT_GRANULARITIES
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_SELECTION)
        }
        if (host.selectionOwnerHasFocus && host.selection != null) {
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

    fun populateHostEvent(event: AccessibilityEvent) {
        event.className = android.widget.TextView::class.java.name
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) return
        val text = host.content.content.text
        val selection = reportedSelection()
        event.itemCount = text.length
        event.fromIndex = selection.first
        event.toIndex = selection.second
    }

    fun performHostAction(action: Int, arguments: Bundle?): Boolean = when (action) {
        AccessibilityNodeInfo.ACTION_SET_SELECTION -> {
            val start = arguments?.getInt(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,
                UNDEFINED_SELECTION,
            ) ?: UNDEFINED_SELECTION
            val end = arguments?.getInt(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                UNDEFINED_SELECTION,
            ) ?: UNDEFINED_SELECTION
            setSelectionFromAction(start, end)
        }
        AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY ->
            traverse(arguments, forward = true)
        AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY ->
            traverse(arguments, forward = false)
        AccessibilityNodeInfo.ACTION_COPY -> host.selectionOwnerHasFocus && host.copySelection()
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
        val textLength = host.content.content.text.length
        if (start < 0 || start >= textLength || length <= 0) return
        val result = host.layoutResult ?: return
        val visibleOnScreen = Rect()
        val hasVisibleRegion = host.getGlobalVisibleRect(visibleOnScreen)
        val localToScreen = screenTransform()
        val screenLocation = IntArray(2).also(host::getLocationOnScreen)
        val windowLocation = IntArray(2).also(host::getLocationInWindow)
        val output = arrayOfNulls<Parcelable>(length)
        repeat(length) { index ->
            val offset = start + index
            if (offset !in 0 until textLength) return@repeat
            val boxes = result.getBoundingBoxes(TextRange(offset, offset + 1))
            if (boxes.isEmpty()) return@repeat
            val left = boxes.minOf { it.left }
            val top = boxes.minOf { it.top }
            val right = boxes.maxOf { it.right }
            val bottom = boxes.maxOf { it.bottom }
            val screenBounds = RectF(
                host.toVisibleX(left),
                host.toVisibleY(top),
                host.toVisibleX(right),
                host.toVisibleY(bottom),
            )
            localToScreen.mapRect(screenBounds)
            if (!hasVisibleRegion || !RectF.intersects(screenBounds, RectF(visibleOnScreen))) {
                return@repeat
            }
            if (coordinateSpace == CoordinateSpace.Window) {
                screenBounds.offset(
                    windowLocation[0] - screenLocation[0].toFloat(),
                    windowLocation[1] - screenLocation[1].toFloat(),
                )
            }
            output[index] = screenBounds
        }
        info.extras.putParcelableArray(key, output)
    }

    private fun setSelectionFromAction(start: Int, end: Int): Boolean {
        val textLength = host.content.content.text.length
        if (start == UNDEFINED_SELECTION && end == UNDEFINED_SELECTION) {
            if (reportedSelection() == (UNDEFINED_SELECTION to UNDEFINED_SELECTION)) return false
            accessibilitySelectionStart = UNDEFINED_SELECTION
            accessibilitySelectionEnd = UNDEFINED_SELECTION
            hasExplicitAccessibilitySelection = true
            applyingAccessibilitySelection = true
            try {
                host.clearSelection()
            } finally {
                applyingAccessibilitySelection = false
            }
            host.sendAccessibilityEventIfEnabled(AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED)
            return true
        }
        if (start < 0 || start > end || end > textLength) return false
        if (reportedSelection() == (start to end)) return false
        return applyAccessibilitySelection(start, end)
    }

    private fun traverse(arguments: Bundle?, forward: Boolean): Boolean {
        arguments ?: return false
        val granularity = arguments.getInt(
            AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
            0,
        )
        if (granularity and MOVEMENT_GRANULARITIES == 0) return false
        val extend = arguments.getBoolean(
            AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN,
            false,
        )
        val text = host.content.content.text
        if (text.isEmpty()) return false
        val visible = Rect()
        val visibleContentHeight = if (host.getGlobalVisibleRect(visible)) {
            (visible.height() - host.paddingTop - host.paddingBottom).coerceAtLeast(0).toFloat()
        } else {
            0f
        }
        val navigator = CjkAccessibilityTextNavigator(
            text = text,
            layout = host.layoutResult,
            locale = currentLocale(),
            visibleContentHeight = visibleContentHeight,
        )
        var current = reportedSelection().second
        if (current == UNDEFINED_SELECTION) current = if (forward) 0 else text.length
        val segment = if (forward) {
            navigator.following(granularity, current)
        } else {
            navigator.preceding(granularity, current)
        } ?: return false
        val nextStart: Int
        val nextEnd: Int
        if (extend) {
            nextStart = reportedSelection().first.takeUnless { it == UNDEFINED_SELECTION }
                ?: if (forward) segment.start else segment.end
            nextEnd = if (forward) segment.end else segment.start
        } else {
            nextStart = if (forward) segment.end else segment.start
            nextEnd = nextStart
        }
        if (!applyAccessibilitySelection(nextStart, nextEnd)) return false
        sendTraversedEvent(
            action = if (forward) {
                AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY
            } else {
                AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY
            },
            granularity = granularity,
            segment = segment,
        )
        return true
    }

    private fun applyAccessibilitySelection(start: Int, end: Int): Boolean {
        val textLength = host.content.content.text.length
        if (min(start, end) < 0 || max(start, end) > textLength) return false
        accessibilitySelectionStart = start
        accessibilitySelectionEnd = end
        hasExplicitAccessibilitySelection = true
        if (!host.textIsSelectable) {
            host.sendAccessibilityEventIfEnabled(AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED)
            return true
        }
        applyingAccessibilitySelection = true
        val applied = try {
            host.requestFocus()
            if (start == end) {
                host.clearSelection()
                true
            } else {
                host.setSelection(start, end)
            }
        } finally {
            applyingAccessibilitySelection = false
        }
        if (!applied) {
            clearAccessibilitySelectionState()
            return false
        }
        host.selection?.let { visual ->
            if (start <= end) {
                accessibilitySelectionStart = visual.start
                accessibilitySelectionEnd = visual.end
            } else {
                accessibilitySelectionStart = visual.end
                accessibilitySelectionEnd = visual.start
            }
        }
        host.sendAccessibilityEventIfEnabled(AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED)
        return true
    }

    private fun sendTraversedEvent(
        action: Int,
        granularity: Int,
        segment: CjkAccessibilityTextSegment,
    ) {
        val parent = host.parent ?: return
        @Suppress("DEPRECATION")
        val event = AccessibilityEvent.obtain(
            AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY,
        )
        host.onInitializeAccessibilityEvent(event)
        event.text.add(host.content.content.text)
        event.fromIndex = segment.start
        event.toIndex = segment.end
        event.action = action
        event.movementGranularity = granularity
        parent.requestSendAccessibilityEvent(host, event)
    }

    private fun reportedSelection(): Pair<Int, Int> {
        sanitizeAccessibilitySelection()
        if (hasExplicitAccessibilitySelection) {
            return accessibilitySelectionStart to accessibilitySelectionEnd
        }
        return host.selection?.let { it.start to it.end }
            ?: defaultSelection()
    }

    private fun defaultSelection(): Pair<Int, Int> =
        if (host.textIsSelectable && host.content.content.text.isNotEmpty()) {
            0 to 0
        } else {
            UNDEFINED_SELECTION to UNDEFINED_SELECTION
        }

    private fun orderedAccessibilitySelection(): TextRange? {
        sanitizeAccessibilitySelection()
        if (
            !hasExplicitAccessibilitySelection ||
            accessibilitySelectionStart == UNDEFINED_SELECTION ||
            accessibilitySelectionEnd == UNDEFINED_SELECTION ||
            accessibilitySelectionStart == accessibilitySelectionEnd
        ) {
            return null
        }
        return TextRange(
            min(accessibilitySelectionStart, accessibilitySelectionEnd),
            max(accessibilitySelectionStart, accessibilitySelectionEnd),
        )
    }

    private fun sanitizeAccessibilitySelection() {
        if (!hasExplicitAccessibilitySelection) return
        if (
            accessibilitySelectionStart == UNDEFINED_SELECTION &&
            accessibilitySelectionEnd == UNDEFINED_SELECTION
        ) {
            return
        }
        val length = host.content.content.text.length
        if (
            accessibilitySelectionStart !in 0..length ||
            accessibilitySelectionEnd !in 0..length
        ) {
            clearAccessibilitySelectionState()
        }
    }

    private fun clearAccessibilitySelectionState() {
        accessibilitySelectionStart = UNDEFINED_SELECTION
        accessibilitySelectionEnd = UNDEFINED_SELECTION
        hasExplicitAccessibilitySelection = false
    }

    private fun screenTransform(): ScreenTransform {
        val matrix = Matrix()
        if (Build.VERSION.SDK_INT >= 29) {
            host.transformMatrixToGlobal(matrix)
            return ScreenTransform(matrix, 0f, 0f)
        }
        transformMatrixToWindow(host, matrix)
        val mappedOrigin = floatArrayOf(0f, 0f).also(matrix::mapPoints)
        val screenOrigin = IntArray(2).also(host::getLocationOnScreen)
        return ScreenTransform(
            matrix = matrix,
            offsetX = screenOrigin[0] - mappedOrigin[0],
            offsetY = screenOrigin[1] - mappedOrigin[1],
        )
    }

    private fun transformMatrixToWindow(view: View, matrix: Matrix) {
        val parent = view.parent
        if (parent is View) {
            transformMatrixToWindow(parent, matrix)
            matrix.preTranslate(-parent.scrollX.toFloat(), -parent.scrollY.toFloat())
        }
        matrix.preTranslate(view.left.toFloat(), view.top.toFloat())
        if (!view.matrix.isIdentity) matrix.preConcat(view.matrix)
    }

    @Suppress("DEPRECATION")
    private fun currentLocale(): Locale = if (Build.VERSION.SDK_INT >= 24) {
        host.resources.configuration.locales[0]
    } else {
        host.resources.configuration.locale ?: Locale.getDefault()
    }

    private fun accessibilityText(source: String): CharSequence {
        return accessibilityLinkSpans.applyTo(source, host.links())
    }

    private enum class CoordinateSpace { Screen, Window }

    private data class ScreenTransform(
        val matrix: Matrix,
        val offsetX: Float,
        val offsetY: Float,
    ) {
        fun mapRect(rect: RectF) {
            matrix.mapRect(rect)
            rect.offset(offsetX, offsetY)
        }
    }

    private companion object {
        const val UNDEFINED_SELECTION = -1
        const val MOVEMENT_GRANULARITIES =
            AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER or
                AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD or
                AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE or
                AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PARAGRAPH or
                AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PAGE
    }
}
