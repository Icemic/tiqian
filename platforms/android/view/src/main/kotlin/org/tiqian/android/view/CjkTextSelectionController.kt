package org.tiqian.android.view

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Magnifier
import androidx.annotation.RequiresApi
import org.tiqian.core.SourceBoundaryBias
import org.tiqian.core.TextRange
import org.tiqian.core.coerceSelectionOffset
import org.tiqian.core.cursorRect
import org.tiqian.core.getLineForOffset
import org.tiqian.core.getTextForCopy
import org.tiqian.core.selectionBoxes
import org.tiqian.core.selectionOffsetForPosition
import org.tiqian.core.selectionWordRangeForPosition
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Android View selection state and interaction policy for one Tiqian paragraph.
 *
 * The controller deliberately owns no layout policy. Every position, line and source boundary is
 * read from the immutable [CjkTextView.LayoutSnapshot]; the native View layer only projects that
 * geometry into Android's touch, handle and magnifier surfaces. The floating ActionMode is
 * delegated to [CjkTextSelectionActionMode].
 */
@SuppressLint("InlinedApi")
internal class CjkTextSelectionController(
    private val host: CjkTextView,
) : CjkSelectionHandleListener {
    private enum class DraggedHandle {
        Start,
        End,
    }

    private data class WordDragState(
        val initialRange: TextRange,
    )

    private val density = host.resources.displayMetrics.density

    /** Selection endpoints are always safe, ordered source offsets. */
    private var selectionStart = -1
    private var selectionEnd = -1
    private var draggedHandle: DraggedHandle? = null
    private val endpointResolver = CjkSelectionEndpointResolver(density)
    private var magnifier: Magnifier? = null
    private var pressedLink: CjkTextView.LinkHit? = null
    private var handledGesture = false
    private var wordDrag: WordDragState? = null
    private var documentOwner: CjkDocumentSelectionController? = null
    private var documentProjection: CjkDocumentSelectionProjection? = null
    private var cachedBoxes: List<org.tiqian.core.Rect> = emptyList()
    private val handles = CjkStandaloneSelectionHandles(
        host = host,
        listener = this,
        selection = { range },
        isSuppressed = { documentOwner != null || wordDrag != null },
    )
    private val selectionActionMode = CjkTextSelectionActionMode(
        host = host,
        customCallback = { host.customSelectionActionModeCallback },
        delegate = object : CjkTextSelectionActionMode.Delegate {
            override val hasSelection: Boolean
                get() = this@CjkTextSelectionController.hasSelection

            override val canSelectAll: Boolean
                get() = this@CjkTextSelectionController.canSelectAll

            override fun copySelection(): Boolean = this@CjkTextSelectionController.copySelection()

            override fun selectAll(): Boolean =
                this@CjkTextSelectionController.selectAll(showToolbar = false)

            override fun selectedText(): String? = this@CjkTextSelectionController.selectedText()

            override fun selectionContentRect(outRect: Rect) =
                this@CjkTextSelectionController.selectionContentRect(outRect)

            override fun onActionModeCreationRejected() {
                this@CjkTextSelectionController.clearSelection()
            }

            override fun onActionModeDestroyed(preserveSelection: Boolean) {
                if (!preserveSelection) this@CjkTextSelectionController.clearSelection()
            }

            override fun performAssistAction(item: MenuItem): Boolean {
                // TextClassifier smart actions need a real platform classifier/session bridge;
                // do not fabricate assist items or claim support until that capability exists.
                return false
            }
        },
    )

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
                beginWordDrag(event.x, event.y)

            override fun onLongPress(event: MotionEvent) {
                if (host.textIsSelectable && beginWordDrag(event.x, event.y)) {
                    host.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
            }
        },
    )

    val hasSelection: Boolean
        get() = selectionStart >= 0 && selectionEnd > selectionStart

    val range: TextRange?
        get() = if (hasSelection) TextRange(selectionStart, selectionEnd) else null

    private val canSelectAll: Boolean
        get() = hasSelection && (
            selectionStart > 0 || selectionEnd < host.content.content.text.length
        )

    val boxes: List<org.tiqian.core.Rect>
        get() = cachedBoxes

    val shouldDrawSelection: Boolean
        get() = (hasSelection || cachedBoxes.isNotEmpty()) &&
            (documentOwner != null || host.isFocused || host.isPressed)

    /** Focus belongs to the logical document owner when this paragraph is attached to one. */
    internal val selectionOwnerHasFocus: Boolean
        get() = documentOwner?.selectionOwnerHasFocus ?: host.isFocused

    internal val hasDocumentOwner: Boolean
        get() = documentOwner != null

    internal val documentCustomSelectionActionModeCallback: android.view.ActionMode.Callback?
        get() = documentOwner?.customSelectionActionModeCallback

    internal fun setDocumentCustomSelectionActionModeCallback(
        value: android.view.ActionMode.Callback?,
    ) {
        documentOwner?.setCustomSelectionActionModeCallback(value)
    }

    /**
     * Sets a source selection using the same ordered, safe-boundary contract as the engine's
     * selection queries. Public callers may provide reversed or cluster-interior offsets; neither
     * representation is retained by the controller.
     */
    fun setSelection(start: Int, end: Int, showToolbar: Boolean = false): Boolean {
        documentOwner?.let { return it.setSelection(host, start, end, showToolbar) }
        return setStandaloneSelection(start, end, showToolbar)
    }

    private fun setStandaloneSelection(start: Int, end: Int, showToolbar: Boolean): Boolean {
        val result = host.layoutSnapshot?.result ?: return false
        val lower = min(start, end)
        val upper = max(start, end)
        val safeStart = result.coerceSelectionOffset(lower, SourceBoundaryBias.Backward)
        val safeEnd = result.coerceSelectionOffset(upper, SourceBoundaryBias.Forward)
        if (safeStart >= safeEnd) {
            clearSelection()
            return false
        }
        val changed = publishSelection(safeStart, safeEnd)
        if (showToolbar) selectionActionMode.show()
        else if (changed) selectionActionMode.invalidateContentRect()
        return true
    }

    fun selectAll(showToolbar: Boolean = true): Boolean {
        documentOwner?.let { return it.selectAll(showToolbar) }
        val textLength = host.content.content.text.length
        if (textLength == 0) return false
        return setSelection(0, textLength, showToolbar)
    }

    fun clearSelection() {
        documentOwner?.let {
            it.clearSelection()
            return
        }
        clearLocalSelection()
    }

    private fun clearLocalSelection() {
        if (
            !hasSelection && selectionStart < 0 && selectionEnd < 0 &&
            documentProjection == null && cachedBoxes.isEmpty()
        ) {
            selectionActionMode.onSelectionCleared()
            return
        }
        selectionStart = -1
        selectionEnd = -1
        documentProjection = null
        cachedBoxes = emptyList()
        wordDrag = null
        draggedHandle = null
        endpointResolver.reset()
        host.parent?.requestDisallowInterceptTouchEvent(false)
        dismissMagnifier()
        handles.dismiss()
        selectionActionMode.finish()
        selectionActionMode.onSelectionCleared()
        host.onSelectionGeometryChanged()
    }

    fun onTextOrGeometryChanged(textChanged: Boolean = false) {
        documentOwner?.let {
            it.onSelectableTextOrGeometryChanged(host, textChanged)
            return
        }
        if (textChanged) {
            clearLocalSelection()
            return
        }
        if (!hasSelection) return
        val result = host.layoutSnapshot?.result ?: return
        val length = result.input.content.text.length
        if (length == 0) {
            clearSelection()
            return
        }
        val currentStart = result.coerceSelectionOffset(
            selectionStart.coerceIn(0, length),
            SourceBoundaryBias.Backward,
        )
        val currentEnd = result.coerceSelectionOffset(
            selectionEnd.coerceIn(0, length),
            SourceBoundaryBias.Forward,
        )
        if (currentStart >= currentEnd) {
            clearSelection()
            return
        }
        if (currentStart != selectionStart || currentEnd != selectionEnd) {
            publishSelection(currentStart, currentEnd)
            selectionActionMode.invalidateContentRect()
        } else {
            val previousBoxes = cachedBoxes
            updateCachedBoxes()
            if (previousBoxes != cachedBoxes) {
                host.onSelectionGeometryChanged()
                selectionActionMode.invalidateContentRect()
            }
        }
    }

    /** Receives text/link gestures; selection-handle streams belong only to their popup Views. */
    fun onTouchEvent(event: MotionEvent): Boolean {
        documentOwner?.let { return it.onSelectableTouchEvent(host, event) }
        if (wordDrag != null) {
            dispatchWordDragEvent(event)
            return true
        }
        val handled = gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) pressedLink = null
        return handled || handledGesture
    }

    /** Replays standalone handles from the current engine caret geometry. */
    fun updateHandles() = handles.update()

    internal val handlesShowing: Boolean
        get() = handles.isShowing

    /** Exposes popup bounds to the host for platform-level tests and accessibility hit routing. */
    internal fun handleBoundsOnScreen(handle: CjkSelectionHandle, outBounds: Rect): Boolean =
        handles.boundsOnScreen(handle, outBounds)

    /**
     * Mirrors TextView's focus/visibility lifecycle: preserve the logical range, hide transient
     * surfaces while the host cannot present them, and recreate the floating toolbar/handles when
     * the host becomes visible and focused again.
     */
    internal fun onHostVisibilityOrFocusChanged() {
        documentOwner?.let {
            handles.dismiss()
            dismissMagnifier()
            it.onSelectableGeometryChanged(host)
            return
        }
        val visible = host.isAttachedToWindow && host.isShown &&
            host.visibility == View.VISIBLE && host.windowVisibility == View.VISIBLE
        if (!visible || !host.hasWindowFocus() || !host.isFocused) {
            handles.dismiss()
            dismissMagnifier()
            selectionActionMode.onHostVisibilityOrFocusChanged()
            return
        }
        if (hasSelection && draggedHandle == null && wordDrag == null) {
            updateHandles()
            selectionActionMode.onHostVisibilityOrFocusChanged()
            selectionActionMode.invalidateContentRect()
            selectionActionMode.show()
        }
    }

    /**
     * Mirrors TextView.Editor's View-focus ownership rather than treating window focus as enough.
     * Losing window focus only hides transient surfaces; losing View focus ends active selection
     * UI and suppresses the highlight until this View owns focus again.
     */
    internal fun onHostFocusChanged(focused: Boolean) {
        if (documentOwner != null) {
            handles.dismiss()
            dismissMagnifier()
            host.invalidate()
            return
        }
        if (focused) {
            onHostVisibilityOrFocusChanged()
        } else {
            handles.dismiss()
            dismissMagnifier()
            // Editor.onFocusChanged(false) stops the current text ActionMode. Its destruction
            // collapses an interactive selection, while a programmatic range with no ActionMode
            // remains stored and merely stops drawing until this View owns focus again.
            selectionActionMode.finish()
            host.invalidate()
        }
    }

    override fun currentPosition(handle: CjkSelectionHandle): CjkSelectionHandlePosition =
        CjkStandaloneHandlePosition(
            when (handle) {
                CjkSelectionHandle.Start -> selectionStart
                CjkSelectionHandle.End -> selectionEnd
            },
        )

    override fun onHandleDragStarted(handle: CjkSelectionHandle) {
        if (!host.textIsSelectable || !hasSelection) return
        draggedHandle = handle.toDraggedHandle()
        val offset = currentOffset(draggedHandle!!)
        endpointResolver.begin(
            CjkSelectionEndpointPosition(STANDALONE_KEY, 0, offset),
            currentLineForEndpoint(draggedHandle!!),
        )
        wordDrag = null
        selectionActionMode.hide()
        dismissMagnifier()
        host.parent?.requestDisallowInterceptTouchEvent(true)
    }

    override fun onHandleDragMoved(
        handle: CjkSelectionHandle,
        viewX: Float,
        viewY: Float,
        rawX: Float,
        rawY: Float,
        fromTouchScreen: Boolean,
    ): CjkSelectionHandlePosition {
        val snapshot = host.layoutSnapshot ?: return currentPosition(handle)
        if (!hasSelection || !viewX.isFinite() || !viewY.isFinite()) {
            return currentPosition(handle)
        }
        val dragged = handle.toDraggedHandle()
        if (draggedHandle != dragged) onHandleDragStarted(handle)
        selectionActionMode.hide()

        val contentX = host.toContentX(viewX)
        val contentY = host.toContentY(viewY)
        var queryY = endpointResolver.lineSlopAdjustedY(
            STANDALONE_KEY,
            0,
            snapshot.result,
            contentY,
        )
        var rawOffset = snapshot.replayIndex.selectionOffsetForPosition(
            snapshot.result,
            contentX,
            queryY,
        )
        val fixedOffset = when (dragged) {
            DraggedHandle.Start -> selectionEnd
            DraggedHandle.End -> selectionStart
        }
        val crossedFixedEndpoint = when (dragged) {
            DraggedHandle.Start -> rawOffset >= fixedOffset
            DraggedHandle.End -> rawOffset <= fixedOffset
        }
        if (crossedFixedEndpoint && snapshot.result.lines.isNotEmpty()) {
            // Editor.SelectionHandleView does not keep resolving a crossed pointer on the line
            // under the finger. It projects x onto the fixed endpoint's line first, then applies
            // normal word/character adjustment and the one-unit crossing guard. Without this
            // step a handle feels frozen as soon as the finger enters an earlier/later line.
            val fixedLineIndex = snapshot.result.getLineForOffset(fixedOffset)
                .coerceIn(0, snapshot.result.lines.lastIndex)
            endpointResolver.forceLine(STANDALONE_KEY, 0, fixedLineIndex)
            val fixedLine = snapshot.result.lines[fixedLineIndex]
            queryY = (fixedLine.top + fixedLine.bottom) / 2f
            rawOffset = snapshot.replayIndex.selectionOffsetForPosition(
                snapshot.result,
                contentX,
                queryY,
            )
        }
        val accepted = endpointResolver.resolve(
            snapshot = snapshot,
            isStart = dragged == DraggedHandle.Start,
            candidatePosition = CjkSelectionEndpointPosition(STANDALONE_KEY, 0, rawOffset),
            currentPosition = CjkSelectionEndpointPosition(
                STANDALONE_KEY,
                0,
                currentOffset(dragged),
            ),
            rawOffset = rawOffset,
            contentX = contentX,
            queryY = queryY,
        ).let { constrainEndpoint(snapshot.result, dragged, it) }
        val previous = currentOffset(dragged)
        val actual = applyHandleOffset(snapshot.result, dragged, accepted)
        endpointResolver.commit(
            CjkSelectionEndpointPosition(STANDALONE_KEY, 0, actual),
            snapshot.result.getLineForOffset(actual),
            contentX,
        )
        if (fromTouchScreen && actual != previous) {
            host.performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
        }
        if (fromTouchScreen) showMagnifier(snapshot, dragged, actual, rawX, rawY)
        return CjkStandaloneHandlePosition(actual)
    }

    override fun onHandleDragFinished(
        handle: CjkSelectionHandle,
        filteredPosition: CjkSelectionHandlePosition?,
        cancelled: Boolean,
    ) {
        val dragged = handle.toDraggedHandle()
        val filteredOffset = (filteredPosition as? CjkStandaloneHandlePosition)?.offset
        if (!cancelled && filteredOffset != null) {
            host.layoutSnapshot?.result?.let { result ->
                applyHandleOffset(result, dragged, filteredOffset)
            }
        }
        if (draggedHandle == dragged) {
            draggedHandle = null
            endpointResolver.reset()
        }
        host.parent?.requestDisallowInterceptTouchEvent(false)
        dismissMagnifier()
        if (!cancelled && hasSelection) {
            selectionActionMode.showAfterSelectionGesture()
        }
    }

    fun copySelection(): Boolean {
        documentOwner?.let { return it.copySelection() }
        val currentRange = range ?: return false
        val result = host.layoutSnapshot?.result ?: return false
        val selected = result.getTextForCopy(currentRange)
        if (selected.isEmpty()) return false
        val clipboard = host.context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        return runCatching {
            clipboard.setPrimaryClip(ClipData.newPlainText(null, selected))
        }.isSuccess
    }

    fun dispose() {
        wordDrag = null
        draggedHandle = null
        endpointResolver.reset()
        host.parent?.requestDisallowInterceptTouchEvent(false)
        selectionActionMode.dispose()
        dismissMagnifier()
        handles.dismiss()
    }

    internal fun attachDocumentOwner(owner: CjkDocumentSelectionController) {
        if (documentOwner === owner) return
        check(documentOwner == null) { "CjkTextView is already attached to another selection container" }
        clearLocalSelection()
        documentOwner = owner
    }

    internal fun detachDocumentOwner(owner: CjkDocumentSelectionController) {
        if (documentOwner !== owner) return
        documentOwner = null
        clearLocalSelection()
    }

    /** Applies one visible projection of a document selection without starting local interaction UI. */
    internal fun applyDocumentSelection(projection: CjkDocumentSelectionProjection?) {
        check(documentOwner != null) { "document selection requires an attached owner" }
        selectionActionMode.finish(preserveSelection = true)
        handles.dismiss()
        dismissMagnifier()
        if (projection == null || projection.isEmpty) {
            if (selectionStart >= 0 || selectionEnd >= 0 || cachedBoxes.isNotEmpty()) {
                selectionStart = -1
                selectionEnd = -1
                documentProjection = null
                cachedBoxes = emptyList()
                host.onSelectionGeometryChanged()
            }
            return
        }
        val result = host.layoutSnapshot?.result
        if (result == null) {
            val changed = documentProjection != projection ||
                selectionStart >= 0 || selectionEnd >= 0 || cachedBoxes.isNotEmpty()
            // Keep the logical projection so the next completed layout can restore it, but do not
            // expose its offsets against a holder whose old LayoutResult has just been discarded.
            // The new layout calls onTextOrGeometryChanged(), which asks the document owner to
            // publish this projection again against the matching paragraph geometry.
            documentProjection = projection
            selectionStart = -1
            selectionEnd = -1
            cachedBoxes = emptyList()
            if (changed) host.onSelectionGeometryChanged()
            return
        }
        val safeRange = projection.range?.let { range ->
            val safeStart = result.coerceSelectionOffset(range.start, SourceBoundaryBias.Backward)
            val safeEnd = result.coerceSelectionOffset(range.end, SourceBoundaryBias.Forward)
            TextRange(safeStart, safeEnd).takeUnless(TextRange::isEmpty)
        }
        val next = projection.copy(range = safeRange)
        val changed = documentProjection != next ||
            selectionStart != (safeRange?.start ?: -1) ||
            selectionEnd != (safeRange?.end ?: -1)
        documentProjection = next
        selectionStart = safeRange?.start ?: -1
        selectionEnd = safeRange?.end ?: -1
        updateCachedBoxes()
        if (changed) host.onSelectionGeometryChanged()
    }

    internal fun onViewportSizeChanged() {
        if (documentProjection == null && !hasSelection) return
        val previous = cachedBoxes
        updateCachedBoxes()
        if (previous != cachedBoxes) host.onSelectionGeometryChanged()
    }

    internal fun onHostLayoutChanged() {
        documentOwner?.onSelectableGeometryChanged(host)
    }

    private fun beginWordDrag(x: Float, y: Float): Boolean {
        if (!host.textIsSelectable) return false
        // TextView.Editor#checkField claims View focus before starting a user selection. Focus is
        // the window-local ownership mechanism: the previously focused selectable View receives
        // onFocusChanged(false), finishes its ActionMode, and collapses its old selection.
        if (!host.requestFocus()) return false
        val snapshot = host.layoutSnapshot ?: return false
        val word = snapshot.replayIndex.selectionWordRangeForPosition(
            snapshot.result,
            host.toContentX(x),
            host.toContentY(y),
        ) ?: return false
        val start = snapshot.result.coerceSelectionOffset(word.start, SourceBoundaryBias.Backward)
        val end = snapshot.result.coerceSelectionOffset(word.end, SourceBoundaryBias.Forward)
        if (start >= end) return false

        // Publish the gesture state before the range so onSelectionGeometryChanged cannot flash
        // handles between the long-press callback and its first selection frame.
        wordDrag = WordDragState(TextRange(start, end))
        // This is an internal selection transition, not a user-requested ActionMode dismissal;
        // retain the range until the new word range is published below.
        selectionActionMode.finish(preserveSelection = true)
        handles.dismiss()
        dismissMagnifier()
        host.parent?.requestDisallowInterceptTouchEvent(true)
        publishSelection(start, end)
        return true
    }

    fun onCustomSelectionActionModeCallbackChanged() {
        selectionActionMode.onCustomCallbackChanged()
    }

    private fun dispatchWordDragEvent(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> updateWordDrag(event.x, event.y)
            MotionEvent.ACTION_UP -> {
                updateWordDrag(event.x, event.y)
                finishWordDrag()
            }
            MotionEvent.ACTION_CANCEL -> cancelWordDrag()
        }
    }

    private fun updateWordDrag(x: Float, y: Float) {
        val state = wordDrag ?: return
        val snapshot = host.layoutSnapshot ?: return
        val target = snapshot.replayIndex.selectionWordRangeForPosition(
            snapshot.result,
            host.toContentX(x),
            host.toContentY(y),
        ) ?: return
        val initial = state.initialRange
        val next = when {
            target.end <= initial.start -> TextRange(target.start, initial.end)
            target.start >= initial.end -> TextRange(initial.start, target.end)
            else -> initial
        }
        if (next.start != selectionStart || next.end != selectionEnd) {
            publishSelection(
                snapshot.result.coerceSelectionOffset(next.start, SourceBoundaryBias.Backward),
                snapshot.result.coerceSelectionOffset(next.end, SourceBoundaryBias.Forward),
            )
        }
    }

    private fun finishWordDrag() {
        wordDrag = null
        host.parent?.requestDisallowInterceptTouchEvent(false)
        if (hasSelection) {
            updateHandles()
            selectionActionMode.showAfterSelectionGesture()
        }
    }

    private fun cancelWordDrag() {
        wordDrag = null
        host.parent?.requestDisallowInterceptTouchEvent(false)
        if (hasSelection) updateHandles()
    }

    private fun applyHandleOffset(
        result: org.tiqian.core.LayoutResult,
        handle: DraggedHandle,
        candidate: Int,
    ): Int {
        if (!hasSelection) return currentOffset(handle)
        val safeCandidate = constrainEndpoint(
            result,
            handle,
            result.coerceSelectionOffset(candidate, SourceBoundaryBias.Nearest),
        )
        val current = currentOffset(handle)
        if (safeCandidate == current) return current
        when (handle) {
            DraggedHandle.Start -> selectionStart = safeCandidate
            DraggedHandle.End -> selectionEnd = safeCandidate
        }
        check(selectionStart < selectionEnd) {
            "selection endpoint update must preserve a non-empty ordered range"
        }
        updateCachedBoxes()
        host.onSelectionGeometryChanged()
        return safeCandidate
    }

    /**
     * Prevents the moving endpoint from crossing its fixed counterpart. The minimum distance is
     * one engine interaction unit, not one UTF-16 code unit, so surrogate pairs/inline objects
     * cannot leave an invalid or empty native-looking selection behind.
     */
    private fun constrainEndpoint(
        result: org.tiqian.core.LayoutResult,
        handle: DraggedHandle,
        candidate: Int,
    ): Int {
        val safe = result.coerceSelectionOffset(
            candidate,
            when (handle) {
                DraggedHandle.Start -> SourceBoundaryBias.Backward
                DraggedHandle.End -> SourceBoundaryBias.Forward
            },
        )
        return when (handle) {
            DraggedHandle.Start -> {
                val maximum = previousInteractionBoundary(result, selectionEnd)
                safe.coerceAtMost(maximum)
            }
            DraggedHandle.End -> {
                val minimum = nextInteractionBoundary(result, selectionStart)
                safe.coerceAtLeast(minimum)
            }
        }
    }

    private fun previousInteractionBoundary(
        result: org.tiqian.core.LayoutResult,
        offset: Int,
    ): Int {
        if (offset <= 0) return offset
        val previous = result.coerceSelectionOffset(offset - 1, SourceBoundaryBias.Backward)
        return if (previous < offset) previous else offset
    }

    private fun nextInteractionBoundary(
        result: org.tiqian.core.LayoutResult,
        offset: Int,
    ): Int {
        val length = result.input.content.text.length
        if (offset >= length) return offset
        val next = result.coerceSelectionOffset(offset + 1, SourceBoundaryBias.Forward)
        return if (next > offset) next else offset
    }

    private fun currentLineForEndpoint(handle: DraggedHandle): Int {
        val result = host.layoutSnapshot?.result ?: return -1
        return result.getLineForOffset(currentOffset(handle))
    }

    private fun publishSelection(start: Int, end: Int): Boolean {
        if (start < 0 || end <= start) return false
        val changed = selectionStart != start || selectionEnd != end
        selectionStart = start
        selectionEnd = end
        if (changed) {
            updateCachedBoxes()
            host.onSelectionGeometryChanged()
        }
        return changed
    }

    /**
     * Magnifier x follows the finger smoothly but is clamped to the accepted caret's engine line
     * and stationary endpoint; y stays on that line's center, as in Editor.HandleView.
     */
    @SuppressLint("NewApi")
    private fun showMagnifier(
        snapshot: CjkTextView.LayoutSnapshot,
        handle: DraggedHandle,
        offset: Int,
        rawX: Float,
        rawY: Float,
    ) {
        if (Build.VERSION.SDK_INT < 28 || snapshot.result.lines.isEmpty()) return
        val scale = unrotatedAncestorScale() ?: run {
            dismissMagnifier()
            return
        }
        val lineIndex = snapshot.result.getLineForOffset(offset).coerceIn(0, snapshot.result.lines.lastIndex)
        val line = snapshot.result.lines[lineIndex]
        val positioned = snapshot.replayIndex.positionedClustersByLine
            .getOrElse(lineIndex) { emptyList() }
        val lineLeft = positioned.firstOrNull()?.left ?: line.indent
        val lineRight = positioned.lastOrNull()?.right ?: line.indent
        val hostOnScreen = IntArray(2)
        host.getLocationOnScreen(hostOnScreen)
        val touchViewX = rawX - hostOnScreen[0]
        val touchViewY = rawY - hostOnScreen[1]
        var leftBound = host.toVisibleX(lineLeft)
        var rightBound = host.toVisibleX(lineRight)
        val fixed = when (handle) {
            DraggedHandle.Start -> selectionEnd
            DraggedHandle.End -> selectionStart
        }
        if (fixed >= 0 && snapshot.result.getLineForOffset(fixed) == lineIndex) {
            val fixedX = host.toVisibleX(snapshot.replayIndex.cursorRect(snapshot.result, fixed).left)
            when (handle) {
                DraggedHandle.Start -> rightBound = min(rightBound, fixedX)
                DraggedHandle.End -> leftBound = max(leftBound, fixedX)
            }
        }
        if (leftBound > rightBound) return
        val lineHeight = line.bottom - line.top
        val lineTop = host.toVisibleY(line.top)
        val lineBottom = host.toVisibleY(line.bottom)
        if (touchViewY < lineTop - lineHeight || touchViewY > lineBottom + lineHeight) {
            dismissMagnifier()
            return
        }
        val value = magnifier ?: createTextDefaultMagnifier(host).also { magnifier = it }
        val magnifierContentWidth = value.width / value.zoom
        if (
            touchViewX < leftBound - magnifierContentWidth / 2f ||
            touchViewX > rightBound + magnifierContentWidth / 2f ||
            lineHeight * scale.second > value.height / value.zoom
        ) {
            dismissMagnifier()
            return
        }
        value.show(touchViewX.coerceIn(leftBound, rightBound), (lineTop + lineBottom) / 2f)
    }

    private fun unrotatedAncestorScale(): Pair<Float, Float>? {
        if (host.rotation != 0f || host.rotationX != 0f || host.rotationY != 0f) return null
        var scaleX = host.scaleX
        var scaleY = host.scaleY
        var parent = host.parent
        while (parent is View) {
            if (parent.rotation != 0f || parent.rotationX != 0f || parent.rotationY != 0f) return null
            scaleX *= parent.scaleX
            scaleY *= parent.scaleY
            parent = parent.parent
        }
        return scaleX to scaleY
    }

    private fun dismissMagnifier() {
        if (Build.VERSION.SDK_INT >= 28) magnifier?.dismiss()
        magnifier = null
    }

    /** Projects engine selection boxes into the host-local ActionMode content rectangle. */
    private fun selectionContentRect(outRect: Rect) {
        val geometry = boxes
        if (geometry.isEmpty()) {
            outRect.set(0, 0, host.width, host.height)
            return
        }
        val left = geometry.minOf { host.toVisibleX(it.left) }
        val top = geometry.minOf { host.toVisibleY(it.top) }
        val right = geometry.maxOf { host.toVisibleX(it.right) }
        val bottom = geometry.maxOf { host.toVisibleY(it.bottom) } + handles.height
        outRect.set(
            floor(left).toInt(),
            floor(top).toInt(),
            ceil(right).toInt(),
            ceil(bottom).toInt(),
        )
    }

    fun selectedText(): String? {
        documentOwner?.let { return it.selectedSourceText }
        val currentRange = range ?: return null
        return host.layoutSnapshot?.result?.getTextForCopy(currentRange)?.takeIf { it.isNotEmpty() }
    }

    private fun updateCachedBoxes() {
        val snapshot = host.layoutSnapshot
        val currentRange = range
        cachedBoxes = when {
            snapshot == null -> emptyList()
            documentOwner != null && documentProjection != null -> nativeSelectionBoxes(
                result = snapshot.result,
                replayIndex = snapshot.replayIndex,
                projection = documentProjection!!,
                viewportWidth = (host.width - host.paddingLeft - host.paddingRight).toFloat(),
            )
            currentRange != null -> nativeSelectionBoxes(
                result = snapshot.result,
                replayIndex = snapshot.replayIndex,
                projection = CjkDocumentSelectionProjection(range = currentRange),
                viewportWidth = (host.width - host.paddingLeft - host.paddingRight).toFloat(),
            )
            else -> emptyList()
        }
    }

    private fun currentOffset(handle: DraggedHandle): Int = when (handle) {
        DraggedHandle.Start -> selectionStart
        DraggedHandle.End -> selectionEnd
    }

    private fun CjkSelectionHandle.toDraggedHandle(): DraggedHandle = when (this) {
        CjkSelectionHandle.Start -> DraggedHandle.Start
        CjkSelectionHandle.End -> DraggedHandle.End
    }

    private fun DraggedHandle.toPublicHandle(): CjkSelectionHandle = when (this) {
        DraggedHandle.Start -> CjkSelectionHandle.Start
        DraggedHandle.End -> CjkSelectionHandle.End
    }

    private companion object {
        val STANDALONE_KEY = Any()

    }
}

/**
 * Uses Android's public text-magnifier preset. Despite being deprecated, this constructor is still
 * the only public API which applies the platform text shape; a bare [Magnifier.Builder] creates the
 * generic rectangular magnifier instead. Compose's `useTextDefault` path makes the same choice.
 */
@Suppress("DEPRECATION")
@RequiresApi(28)
internal fun createTextDefaultMagnifier(host: View): Magnifier = Magnifier(host)
