@file:Suppress("DEPRECATION")

package org.tiqian.compose

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.tiqian.core.SourceBoundaryBias
import org.tiqian.core.TextRange
import kotlin.math.abs

/**
 * Observable state for [CjkSelectionContainer]. [selectedText] preserves the source
 * [AnnotatedString] and inserts a newline between separately composed `CjkText` surfaces, matching
 * Compose's static multi-widget copy contract.
 */
@Stable
class CjkSelectionState internal constructor() {
    var selectedText: AnnotatedString? by mutableStateOf(null)
        private set

    private val selectables = mutableListOf<CjkSelectable>()
    private var orderedSelectablesCache: List<CjkSelectable>? = null
    private var selectionRanges: Map<CjkSelectable, TextRange> = emptyMap()
    private var selection: CjkSelection? = null
    private var clipboardManager: ClipboardManager? = null
    private var textToolbar: TextToolbar? = null
    private var systemContextMenu: CjkSystemContextMenu? = null
    private var hapticFeedback: HapticFeedback? = null
    private var containerCoordinates: LayoutCoordinates? = null
    private var gestureInitialSelection: CjkSelection? = null
    private var handleFixedAnchor: CjkSelectionAnchor? = null
    private var handleDragPosition: Offset = Offset.Zero
    private var activeDragIsStart: Boolean? = null
    private var activeGestureAdjustment: CjkSelectionAdjustment = CjkSelectionAdjustment.None
    internal val focusRequester = FocusRequester()
    internal var isTouchSelection by mutableStateOf(false)
        private set
    internal var hasSelection by mutableStateOf(false)
        private set
    internal var startHandlePosition by mutableStateOf<Offset?>(null)
        private set
    internal var endHandlePosition by mutableStateOf<Offset?>(null)
        private set
    internal var startHandleLineHeight by mutableStateOf(0f)
        private set
    internal var endHandleLineHeight by mutableStateOf(0f)
        private set
    internal var handlesCrossed by mutableStateOf(false)
        private set
    internal var currentDragPosition by mutableStateOf(Offset.Unspecified)
        private set
    internal var selectionBackgroundArgb: Int by mutableStateOf(0)
        private set
    internal var selectionViewportHeightPx by mutableStateOf(0f)
        private set
    private var selectionToolbarHandleClearancePx = 0f
    internal var isSelectionAutoScrollArmed by mutableStateOf(false)
        private set
    private var contextMenuPositionEpoch by mutableStateOf(0)
    private var contextMenuToolbarRequested = false
    internal val isSelectionGestureInProgress: Boolean
        get() = gestureInitialSelection != null || handleFixedAnchor != null

    /** Clears the active selection and hides its toolbar/handles. */
    fun clearSelection() {
        val ownedSelectionOrGesture =
            selection != null || gestureInitialSelection != null || handleFixedAnchor != null
        val previouslySelected = selectionRanges.keys.toList()
        // Finish ActionMode before publishing an empty selection. Otherwise Android can rebuild the
        // still-visible menu from the intermediate state and briefly show only "Select all".
        if (ownedSelectionOrGesture) hideContextMenuToolbar()
        selection = null
        selectionRanges = emptyMap()
        selectedText = null
        hasSelection = false
        startHandlePosition = null
        endHandlePosition = null
        startHandleLineHeight = 0f
        endHandleLineHeight = 0f
        handlesCrossed = false
        gestureInitialSelection = null
        handleFixedAnchor = null
        activeDragIsStart = null
        activeGestureAdjustment = CjkSelectionAdjustment.None
        currentDragPosition = Offset.Unspecified
        isSelectionAutoScrollArmed = false
        isTouchSelection = false
        previouslySelected.forEach(CjkSelectable::invalidateSelection)
    }

    /**
     * Copies the Web-compatible plain-text projection of the current selection. Selection state
     * and accessibility semantics stay source-faithful; only fully selected ruby / 注音 readings
     * are appended to the clipboard text.
     */
    fun copySelection(): Boolean {
        if (!hasSelection) return false
        val selected = buildClipboardText(orderedSelectables(), selectionRanges)
            ?.takeIf { it.isNotEmpty() }
            ?: return false
        @Suppress("DEPRECATION")
        clipboardManager?.setText(selected) ?: return false
        return true
    }

    /** Selects every currently composed `CjkText` in geometric reading order. */
    fun selectAll(): Boolean {
        val ordered = orderedSelectables()
        val first = ordered.firstOrNull() ?: return false
        val last = ordered.last()
        setSelection(
            CjkSelectionAnchor(first, 0),
            CjkSelectionAnchor(last, last.selectionText.length),
            touch = isTouchSelection,
        )
        showContextMenuToolbarIfTouch()
        return true
    }

    internal fun attach(
        clipboardManager: ClipboardManager,
        textToolbar: TextToolbar,
        hapticFeedback: HapticFeedback,
        selectionBackgroundArgb: Int,
        selectionToolbarHandleClearancePx: Float,
    ) {
        this.clipboardManager = clipboardManager
        this.textToolbar = textToolbar
        this.hapticFeedback = hapticFeedback
        this.selectionBackgroundArgb = selectionBackgroundArgb
        this.selectionToolbarHandleClearancePx = selectionToolbarHandleClearancePx
    }

    internal fun detach() {
        clearSelection()
        containerCoordinates = null
        clipboardManager = null
        textToolbar = null
        hapticFeedback = null
    }

    internal fun attachSystemContextMenu(menu: CjkSystemContextMenu) {
        systemContextMenu = menu
        if (contextMenuToolbarRequested) menu.show()
    }

    internal fun detachSystemContextMenu(menu: CjkSystemContextMenu) {
        if (systemContextMenu === menu) {
            menu.hide()
            systemContextMenu = null
            contextMenuToolbarRequested = false
        }
    }

    internal fun updateContainerCoordinates(coordinates: LayoutCoordinates) {
        containerCoordinates = coordinates
        selectionViewportHeightPx = coordinates.size.height.toFloat()
        orderedSelectablesCache = null
        contextMenuPositionEpoch++
        updateDerivedSelection()
    }

    internal fun register(selectable: CjkSelectable) {
        if (selectable !in selectables) {
            selectables += selectable
            orderedSelectablesCache = null
        }
    }

    internal fun unregister(selectable: CjkSelectable) {
        selectables -= selectable
        orderedSelectablesCache = null
        val current = selection
        if (current?.anchor?.selectable === selectable || current?.extent?.selectable === selectable) {
            clearSelection()
        } else if (current != null) {
            updateDerivedSelection()
        }
    }

    internal fun selectableChanged(selectable: CjkSelectable, textChanged: Boolean = false) {
        orderedSelectablesCache = null
        contextMenuPositionEpoch++
        val current = selection
        if (
            textChanged &&
            (current?.anchor?.selectable === selectable || current?.extent?.selectable === selectable)
        ) {
            clearSelection()
        } else if (current != null) {
            updateDerivedSelection()
        }
    }

    internal fun beginGestureSelection(
        selectable: CjkSelectable,
        localPosition: Offset,
        adjustment: CjkSelectionAdjustment,
        touch: Boolean,
    ): Boolean {
        hideContextMenuToolbar()
        focusRequester.requestFocus()
        val initial = selectionAt(selectable, localPosition, adjustment) ?: return false
        gestureInitialSelection = initial
        activeGestureAdjustment = adjustment
        activeDragIsStart = if (touch) false else null
        updateCurrentDragPosition(selectable, localPosition)
        setSelection(initial.anchor, initial.extent, touch)
        return true
    }

    internal fun extendGestureSelection(
        selectable: CjkSelectable,
        localPosition: Offset,
    ): Boolean {
        val current = selection ?: return false
        hideContextMenuToolbar()
        focusRequester.requestFocus()
        val target = anchorAt(selectable, localPosition) ?: return false
        gestureInitialSelection = CjkSelection(current.anchor, current.anchor)
        setSelection(current.anchor, target, touch = false)
        return true
    }

    internal fun updateGestureSelection(
        origin: CjkSelectable,
        localPosition: Offset,
        adjustment: CjkSelectionAdjustment,
    ): Boolean {
        val initial = gestureInitialSelection ?: selection ?: return false
        val container = containerCoordinates ?: return false
        val originCoordinates = origin.selectionCoordinates ?: return false
        if (!container.isAttached || !originCoordinates.isAttached) return false
        val containerPosition = container.localPositionOf(originCoordinates, localPosition)
        currentDragPosition = containerPosition
        activeGestureAdjustment = adjustment
        return updateGestureSelectionAtContainerPosition(containerPosition, adjustment)
    }

    private fun updateGestureSelectionAtContainerPosition(
        containerPosition: Offset,
        adjustment: CjkSelectionAdjustment,
    ): Boolean {
        val initial = gestureInitialSelection ?: selection ?: return false
        val container = containerCoordinates ?: return false
        val target = selectableAt(containerPosition) ?: return false
        val targetCoordinates = target.selectionCoordinates ?: return false
        val targetPosition = targetCoordinates.localPositionOf(container, containerPosition)
        val targetSelection = selectionAt(target, targetPosition, adjustment) ?: return false
        val next = when (adjustment) {
            CjkSelectionAdjustment.Word,
            CjkSelectionAdjustment.Paragraph,
            -> adjustedDragSelection(initial, targetSelection)
            CjkSelectionAdjustment.None,
            CjkSelectionAdjustment.CharacterWithWordAccelerate,
            -> CjkSelection(initial.anchor, targetSelection.extent)
        }
        setSelection(next.anchor, next.extent, isTouchSelection)
        return true
    }

    internal fun armSelectionAutoScroll() {
        if (gestureInitialSelection != null || handleFixedAnchor != null) {
            isSelectionAutoScrollArmed = true
        }
    }

    internal fun refreshSelectionAfterAutoScroll() {
        if (!isSelectionAutoScrollArmed || !currentDragPosition.isSpecified) return
        val handleIsStart = activeDragIsStart
        if (handleFixedAnchor != null && handleIsStart != null) {
            updateHandleSelectionAtContainerPosition(handleIsStart, currentDragPosition)
        } else if (gestureInitialSelection != null) {
            updateGestureSelectionAtContainerPosition(currentDragPosition, activeGestureAdjustment)
        }
    }

    internal fun finishSelection() {
        gestureInitialSelection = null
        activeGestureAdjustment = CjkSelectionAdjustment.None
        activeDragIsStart = null
        currentDragPosition = Offset.Unspecified
        isSelectionAutoScrollArmed = false
        showContextMenuToolbarIfTouch()
    }

    internal fun rangeFor(selectable: CjkSelectable): TextRange? {
        return selectionRanges[selectable]
    }

    internal fun setSelectionFromSemantics(
        selectable: CjkSelectable,
        start: Int,
        end: Int,
    ): Boolean {
        val safeStart = selectable.coerceSelectionOffset(start, SourceBoundaryBias.Backward)
        val safeEnd = selectable.coerceSelectionOffset(end, SourceBoundaryBias.Forward)
        setSelection(
            CjkSelectionAnchor(selectable, safeStart),
            CjkSelectionAnchor(selectable, safeEnd),
            touch = false,
        )
        return true
    }

    internal fun beginHandleDrag(isStart: Boolean) {
        val current = selection ?: return
        handleFixedAnchor = if (isStart) current.extent else current.anchor
        handleDragPosition = (if (isStart) startHandlePosition else endHandlePosition) ?: return
        handleDragPosition += Offset(0f, -1f)
        activeDragIsStart = isStart
        currentDragPosition = handleDragPosition
        hideContextMenuToolbar()
    }

    internal fun dragHandle(isStart: Boolean, delta: Offset) {
        val fixed = handleFixedAnchor ?: return
        handleDragPosition += delta
        currentDragPosition = handleDragPosition
        updateHandleSelectionAtContainerPosition(isStart, handleDragPosition, fixed)
    }

    private fun updateHandleSelectionAtContainerPosition(
        isStart: Boolean,
        position: Offset,
        fixed: CjkSelectionAnchor? = handleFixedAnchor,
    ) {
        val fixedAnchor = fixed ?: return
        val target = anchorAtContainerPosition(position) ?: return
        if (isStart) {
            setSelection(target, fixedAnchor, touch = true)
        } else {
            setSelection(fixedAnchor, target, touch = true)
        }
    }

    internal fun endHandleDrag() {
        handleFixedAnchor = null
        activeDragIsStart = null
        currentDragPosition = Offset.Unspecified
        isSelectionAutoScrollArmed = false
        showContextMenuToolbarIfTouch()
    }

    internal fun handleCopyKeyEvent(event: KeyEvent): Boolean {
        if (
            event.type == KeyEventType.KeyDown && event.key == Key.C &&
            (event.isCtrlPressed || event.isMetaPressed)
        ) {
            return copySelection()
        }
        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape && hasSelection) {
            clearSelection()
            return true
        }
        return false
    }

    private fun setSelection(
        anchor: CjkSelectionAnchor,
        extent: CjkSelectionAnchor,
        touch: Boolean,
    ) {
        val next = CjkSelection(anchor, extent)
        val changed = selection != next
        val touchModeChanged = isTouchSelection != touch
        if (!changed && !touchModeChanged) return
        selection = next
        isTouchSelection = touch
        if (changed && touch) {
            hapticFeedback?.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        if (changed) updateDerivedSelection()
    }

    private fun selectionAt(
        selectable: CjkSelectable,
        localPosition: Offset,
        adjustment: CjkSelectionAdjustment,
    ): CjkSelection? = when (adjustment) {
        CjkSelectionAdjustment.Word ->
            selectable.selectionWordRangeAt(localPosition)?.let { range ->
                CjkSelection(
                    CjkSelectionAnchor(selectable, range.start),
                    CjkSelectionAnchor(selectable, range.end),
                )
            }
        CjkSelectionAdjustment.Paragraph ->
            selectable.selectionParagraphRangeAt(localPosition)?.let { range ->
                CjkSelection(
                    CjkSelectionAnchor(selectable, range.start),
                    CjkSelectionAnchor(selectable, range.end),
                )
            }
        CjkSelectionAdjustment.None,
        CjkSelectionAdjustment.CharacterWithWordAccelerate,
        -> anchorAt(selectable, localPosition)?.let { CjkSelection(it, it) }
    }

    /**
     * Foundation keeps the originally adjusted word/paragraph selected while the moving endpoint
     * crosses it, then expands from the correct outer boundary in either direction.
     */
    private fun adjustedDragSelection(
        initial: CjkSelection,
        target: CjkSelection,
    ): CjkSelection = when {
        compareAnchors(target.extent, initial.anchor) < 0 ->
            CjkSelection(initial.extent, target.anchor)
        compareAnchors(target.anchor, initial.extent) > 0 ->
            CjkSelection(initial.anchor, target.extent)
        else -> initial
    }

    private fun updateCurrentDragPosition(
        selectable: CjkSelectable,
        localPosition: Offset,
    ) {
        val container = containerCoordinates ?: return
        val coordinates = selectable.selectionCoordinates ?: return
        if (!container.isAttached || !coordinates.isAttached) return
        currentDragPosition = container.localPositionOf(coordinates, localPosition)
    }

    private fun updateDerivedSelection() {
        contextMenuPositionEpoch++
        val previousRanges = selectionRanges
        val ordered = orderedSelectables()
        selectionRanges = buildSelectionRanges(ordered)
        selectedText = buildSelectedText(ordered, selectionRanges)
        hasSelection = selectedText != null
        updateHandlePositions()
        if (contextMenuToolbarRequested && systemContextMenu == null) {
            showLegacyTextToolbar()
        }
        val affected = LinkedHashSet<CjkSelectable>()
        affected += previousRanges.keys
        affected += selectionRanges.keys
        affected.forEach { selectable ->
            if (previousRanges[selectable] != selectionRanges[selectable]) {
                selectable.invalidateSelection()
            }
        }
    }

    private fun buildSelectionRanges(
        ordered: List<CjkSelectable>,
    ): Map<CjkSelectable, TextRange> {
        val normalized = normalizedSelection() ?: return emptyMap()
        if (normalized.first == normalized.second) return emptyMap()
        val startIndex = ordered.indexOf(normalized.first.selectable)
        val endIndex = ordered.indexOf(normalized.second.selectable)
        if (startIndex < 0 || endIndex < startIndex) return emptyMap()
        val ranges = LinkedHashMap<CjkSelectable, TextRange>()
        for (index in startIndex..endIndex) {
            val selectable = ordered[index]
            val start = if (index == startIndex) normalized.first.offset else 0
            val end = if (index == endIndex) normalized.second.offset else selectable.selectionText.length
            TextRange(start.coerceAtMost(end), end.coerceAtLeast(start))
                .takeUnless { it.isEmpty }
                ?.let { ranges[selectable] = it }
        }
        return ranges
    }

    private fun buildSelectedText(
        ordered: List<CjkSelectable>,
        ranges: Map<CjkSelectable, TextRange>,
    ): AnnotatedString? {
        if (ranges.isEmpty()) return null
        return buildAnnotatedString {
            var first = true
            for (selectable in ordered) {
                val range = ranges[selectable] ?: continue
                if (!first) append('\n')
                first = false
                append(selectable.selectionText, range.start, range.end)
            }
        }.takeIf { it.isNotEmpty() }
    }

    private fun buildClipboardText(
        ordered: List<CjkSelectable>,
        ranges: Map<CjkSelectable, TextRange>,
    ): AnnotatedString? {
        if (ranges.isEmpty()) return null
        return buildAnnotatedString {
            var first = true
            for (selectable in ordered) {
                val range = ranges[selectable] ?: continue
                if (!first) append('\n')
                first = false
                append(selectable.selectionTextForCopy(range))
            }
        }.takeIf { it.isNotEmpty() }
    }

    private fun normalizedSelection(): Pair<CjkSelectionAnchor, CjkSelectionAnchor>? {
        val current = selection ?: return null
        return if (compareAnchors(current.anchor, current.extent) <= 0) {
            current.anchor to current.extent
        } else {
            current.extent to current.anchor
        }
    }

    private fun compareAnchors(left: CjkSelectionAnchor, right: CjkSelectionAnchor): Int {
        if (left.selectable === right.selectable) return left.offset.compareTo(right.offset)
        val ordered = orderedSelectables()
        return ordered.indexOf(left.selectable).compareTo(ordered.indexOf(right.selectable))
    }

    private fun orderedSelectables(): List<CjkSelectable> {
        orderedSelectablesCache?.let { return it }
        val container = containerCoordinates ?: return selectables.toList().also {
            orderedSelectablesCache = it
        }
        return selectables.filter { it.selectionCoordinates?.isAttached == true }.sortedWith { a, b ->
            val aCoordinates = a.selectionCoordinates ?: return@sortedWith -1
            val bCoordinates = b.selectionCoordinates ?: return@sortedWith 1
            val aPosition = container.localPositionOf(aCoordinates, Offset.Zero)
            val bPosition = container.localPositionOf(bCoordinates, Offset.Zero)
            val verticalOverlap = minOf(
                aPosition.y + aCoordinates.size.height,
                bPosition.y + bCoordinates.size.height,
            ) - maxOf(aPosition.y, bPosition.y)
            if (verticalOverlap > minOf(aCoordinates.size.height, bCoordinates.size.height) / 2f) {
                aPosition.x.compareTo(bPosition.x)
            } else {
                aPosition.y.compareTo(bPosition.y)
            }
        }.also { orderedSelectablesCache = it }
    }

    private fun selectableAt(containerPosition: Offset): CjkSelectable? {
        val container = containerCoordinates ?: return null
        val ordered = orderedSelectables()
        if (ordered.isEmpty()) return null
        val containing = ordered.firstOrNull { selectable ->
            val coordinates = selectable.selectionCoordinates ?: return@firstOrNull false
            val topLeft = container.localPositionOf(coordinates, Offset.Zero)
            containerPosition.x >= topLeft.x && containerPosition.x <= topLeft.x + coordinates.size.width &&
                containerPosition.y >= topLeft.y && containerPosition.y <= topLeft.y + coordinates.size.height
        }
        if (containing != null) return containing
        return ordered.minBy { selectable ->
            val coordinates = selectable.selectionCoordinates ?: return@minBy Float.POSITIVE_INFINITY
            val topLeft = container.localPositionOf(coordinates, Offset.Zero)
            val right = topLeft.x + coordinates.size.width
            val bottom = topLeft.y + coordinates.size.height
            val dx = when {
                containerPosition.x < topLeft.x -> topLeft.x - containerPosition.x
                containerPosition.x > right -> containerPosition.x - right
                else -> 0f
            }
            val dy = when {
                containerPosition.y < topLeft.y -> topLeft.y - containerPosition.y
                containerPosition.y > bottom -> containerPosition.y - bottom
                else -> 0f
            }
            dx * dx + dy * dy
        }
    }

    private fun anchorAt(selectable: CjkSelectable, localPosition: Offset): CjkSelectionAnchor? =
        selectable.selectionOffsetAt(localPosition)?.let { CjkSelectionAnchor(selectable, it) }

    private fun anchorAtContainerPosition(position: Offset): CjkSelectionAnchor? {
        val container = containerCoordinates ?: return null
        val selectable = selectableAt(position) ?: return null
        val coordinates = selectable.selectionCoordinates ?: return null
        return anchorAt(selectable, coordinates.localPositionOf(container, position))
    }

    private fun updateHandlePositions() {
        val container = containerCoordinates
        val current = selection
        if (container == null || current == null || !hasSelection) {
            startHandlePosition = null
            endHandlePosition = null
            startHandleLineHeight = 0f
            endHandleLineHeight = 0f
            handlesCrossed = false
            return
        }
        val visibleBounds = container.visibleBoundsForSelection()
        fun position(anchor: CjkSelectionAnchor): Offset? {
            val coordinates = anchor.selectable.selectionCoordinates ?: return null
            val local = anchor.selectable.selectionCursorPosition(anchor.offset) ?: return null
            return container.localPositionOf(coordinates, local)
        }
        startHandlePosition = position(current.anchor)?.takeIf {
            activeDragIsStart == true || visibleBounds.containsInclusive(it)
        }
        endHandlePosition = position(current.extent)?.takeIf {
            activeDragIsStart == false || visibleBounds.containsInclusive(it)
        }
        startHandleLineHeight = current.anchor.selectable.selectionLineHeight(current.anchor.offset)
        endHandleLineHeight = current.extent.selectable.selectionLineHeight(current.extent.offset)
        handlesCrossed = compareAnchors(current.anchor, current.extent) > 0
    }

    /** Adaptation of Foundation's Android magnifier clamp using Tiqian line geometry. */
    internal fun magnifierCenter(magnifierWidthPx: Int): Offset {
        if (!isTouchSelection || !currentDragPosition.isSpecified || !hasSelection) {
            return Offset.Unspecified
        }
        val current = selection ?: return Offset.Unspecified
        val anchor = if (activeDragIsStart == true) current.anchor else current.extent
        val container = containerCoordinates ?: return Offset.Unspecified
        val selectableCoordinates = anchor.selectable.selectionCoordinates ?: return Offset.Unspecified
        if (!container.isAttached || !selectableCoordinates.isAttached) return Offset.Unspecified

        val localDragPosition = selectableCoordinates.localPositionOf(container, currentDragPosition)
        val lineRange = anchor.selectable.selectionLineRange(anchor.offset)
            ?: return Offset.Unspecified
        val lineStartX = anchor.selectable.selectionLineLeft(lineRange.start)
        val lineEndX = anchor.selectable.selectionLineRight((lineRange.end - 1).coerceAtLeast(lineRange.start))
        val constrainedX = localDragPosition.x.coerceIn(
            minOf(lineStartX, lineEndX),
            maxOf(lineStartX, lineEndX),
        )
        if (
            magnifierWidthPx > 0 &&
            abs(localDragPosition.x - constrainedX) > magnifierWidthPx / 2f
        ) {
            return Offset.Unspecified
        }
        val centerY = anchor.selectable.selectionLineCenterY(anchor.offset)
        return container.localPositionOf(
            selectableCoordinates,
            Offset(constrainedX, centerY),
        )
    }

    private fun showContextMenuToolbarIfTouch() {
        if (!isTouchSelection || !hasSelection) return
        contextMenuToolbarRequested = true
        systemContextMenu?.show() ?: showLegacyTextToolbar()
    }

    private fun hideContextMenuToolbar() {
        val requested = contextMenuToolbarRequested
        contextMenuToolbarRequested = false
        systemContextMenu?.hide()
        if (requested && systemContextMenu == null) textToolbar?.hide()
    }

    private fun showLegacyTextToolbar() {
        val rect = selectionContentRectInWindow() ?: return
        textToolbar?.showMenu(
            rect = rect,
            onCopyRequested = {
                if (copySelection()) clearSelection()
            },
            onSelectAllRequested = { selectAll() },
        )
    }

    internal fun selectionContentRectInRoot(): Rect? {
        // Android's platform ActionMode observes snapshot reads made while computing its content
        // rect. LayoutCoordinates themselves are not snapshot state, so this named epoch makes
        // descendant movement during verticalScroll invalidate the system toolbar position.
        contextMenuPositionEpoch
        val container = containerCoordinates?.takeIf { it.isAttached } ?: return null
        val visible = container.visibleBoundsForSelection()
        val selected = selectionRectInContainer(container) ?: return null
        val clipped = visible.intersect(selected)
        if (clipped.width < 0f || clipped.height < 0f) return null
        val topLeft = container.localToRoot(clipped.topLeft)
        val bottomRight = container.localToRoot(clipped.bottomRight)
        return Rect(topLeft, bottomRight).copy(
            bottom = bottomRight.y + selectionToolbarHandleClearancePx,
        )
    }

    internal fun contextMenuContainerCoordinates(): LayoutCoordinates? =
        containerCoordinates?.takeIf { it.isAttached }

    private fun selectionContentRectInWindow(): Rect? {
        val container = containerCoordinates?.takeIf { it.isAttached } ?: return null
        val visible = container.visibleBoundsForSelection()
        val selected = selectionRectInContainer(container) ?: return null
        val clipped = visible.intersect(selected)
        if (clipped.width < 0f || clipped.height < 0f) return null
        val topLeft = container.localToWindow(clipped.topLeft)
        val bottomRight = container.localToWindow(clipped.bottomRight)
        return Rect(topLeft, bottomRight).copy(
            bottom = bottomRight.y + selectionToolbarHandleClearancePx,
        )
    }

    private fun selectionRectInContainer(container: LayoutCoordinates): Rect? {
        var union: Rect? = null
        for (selectable in orderedSelectables()) {
            val range = rangeFor(selectable) ?: continue
            val coordinates = selectable.selectionCoordinates ?: continue
            if (!coordinates.isAttached) continue
            for (box in selectable.selectionBoxes(range)) {
                val topLeft = container.localPositionOf(coordinates, Offset(box.left, box.top))
                val bottomRight = container.localPositionOf(coordinates, Offset(box.right, box.bottom))
                val current = Rect(topLeft, bottomRight)
                union = union?.let {
                    Rect(
                        minOf(it.left, current.left),
                        minOf(it.top, current.top),
                        maxOf(it.right, current.right),
                        maxOf(it.bottom, current.bottom),
                    )
                } ?: current
            }
        }
        return union
    }

    internal fun contextTextAndSelection(): Pair<AnnotatedString, androidx.compose.ui.text.TextRange>? {
        contextMenuPositionEpoch
        val ordered = orderedSelectables().filter(selectionRanges::containsKey)
        if (ordered.isEmpty()) return null
        var selectionStart = -1
        var selectionEnd = -1
        val context = buildAnnotatedString {
            ordered.forEachIndexed { index, selectable ->
                val range = selectionRanges.getValue(selectable)
                if (index == 0) {
                    selectionStart = range.start
                    append(selectable.selectionText, 0, range.start)
                }
                append(selectable.selectionText, range.start, range.end)
                if (index < ordered.lastIndex) {
                    append('\n')
                } else {
                    selectionEnd = length
                    append(selectable.selectionText, range.end, selectable.selectionText.length)
                }
            }
        }
        if (selectionStart < 0 || selectionEnd < selectionStart) return null
        return context to androidx.compose.ui.text.TextRange(selectionStart, selectionEnd)
    }

    internal fun isEntireContainerSelected(): Boolean {
        contextMenuPositionEpoch
        val ordered = orderedSelectables()
        if (ordered.isEmpty()) return true
        return ordered.all { selectable ->
            val range = selectionRanges[selectable]
            selectable.selectionText.isEmpty() ||
                (range?.start == 0 && range.end == selectable.selectionText.length)
        }
    }

    internal fun copyFromContextMenu() {
        if (copySelection() && isTouchSelection) clearSelection()
    }

    internal fun selectWordAtContainerPositionIfOutsideSelection(position: Offset) {
        val container = containerCoordinates?.takeIf { it.isAttached } ?: return
        val selectable = selectableAt(position) ?: return
        val coordinates = selectable.selectionCoordinates?.takeIf { it.isAttached } ?: return
        val local = coordinates.localPositionOf(container, position)
        val selectedRange = selectionRanges[selectable]
        val insideSelection = selectedRange != null && selectable.selectionBoxes(selectedRange).any { box ->
            local.x in box.left..box.right && local.y in box.top..box.bottom
        }
        if (insideSelection) return
        if (beginGestureSelection(selectable, local, CjkSelectionAdjustment.Word, touch = false)) {
            finishSelection()
        }
    }

}

internal class CjkSystemContextMenu(
    val show: () -> Unit,
    val hide: () -> Unit,
)

/** Remembers state for one [CjkSelectionContainer]. */
@Composable
fun rememberCjkSelectionState(): CjkSelectionState = remember { CjkSelectionState() }

/**
 * Enables source-faithful static-text selection for descendant `CjkText` surfaces. Unlike
 * Compose's `SelectionContainer`, this container consumes Tiqian [org.tiqian.core.LayoutResult]
 * geometry directly, so line breaks, punctuation glue, ruby expansion, and copied source ranges do
 * not pass through a hidden second text layout.
 *
 * When [content] uses `Modifier.verticalScroll`, pass that modifier's [scrollState] here as well.
 * A mouse, touch, or handle drag then scrolls inside the edge bands after the gesture has crossed
 * touch slop. `null` disables auto-scroll. Virtualized lazy layouts need a separate selection
 * contract because selected `CjkText` nodes may leave composition.
 */
@Composable
fun CjkSelectionContainer(
    modifier: Modifier = Modifier,
    state: CjkSelectionState = rememberCjkSelectionState(),
    scrollState: ScrollState? = null,
    autoScrollEdgeSize: Dp = 48.dp,
    autoScrollMaxVelocity: Dp = 1_200.dp,
    content: @Composable () -> Unit,
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val textToolbar = LocalTextToolbar.current
    val hapticFeedback = LocalHapticFeedback.current
    val density = LocalDensity.current
    val colors = LocalTextSelectionColors.current
    SideEffect {
        state.attach(
            clipboardManager,
            textToolbar,
            hapticFeedback,
            colors.backgroundColor.toArgb(),
            foundationSelectionToolbarHandleClearancePx(density),
        )
    }
    CjkSelectionAutoScrollEffect(
        state = state,
        scrollState = scrollState,
        edgeSize = autoScrollEdgeSize,
        maxVelocity = autoScrollMaxVelocity,
    )

    Box(
        modifier = modifier
            .foundationClearSelectionOnTap(state)
            .foundationSelectionMagnifier(state)
            .onGloballyPositioned(state::updateContainerCoordinates)
            .focusRequester(state.focusRequester)
            .onFocusChanged { focus ->
                if (!focus.hasFocus && state.hasSelection) state.clearSelection()
            }
            .onKeyEvent(state::handleCopyKeyEvent)
            // A low-level focus target is enough for keyboard copy/Escape. `focusable()` also
            // publishes an otherwise empty accessibility node around every descendant text node.
            .focusTarget(),
    ) {
        FoundationSelectionContextMenuArea(state) {
            CompositionLocalProvider(LocalCjkSelectionState provides state) {
                content()
            }
            if (state.isTouchSelection && state.hasSelection) {
                CjkSelectionHandle(state, isStart = true)
                CjkSelectionHandle(state, isStart = false)
            }
        }
    }

    DisposableEffect(state) {
        onDispose(state::detach)
    }
}

/** Prevents descendant `CjkText` nodes from registering in an outer [CjkSelectionContainer]. */
@Composable
fun CjkDisableSelection(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalCjkSelectionState provides null, content = content)
}

@Composable
private fun CjkSelectionHandle(
    state: CjkSelectionState,
    isStart: Boolean,
) {
    FoundationSelectionHandle(
        offsetProvider = {
            (if (isStart) state.startHandlePosition else state.endHandlePosition)
                ?: Offset.Unspecified
        },
        isStartHandle = isStart,
        handlesCrossed = state.handlesCrossed,
        lineHeight = if (isStart) state.startHandleLineHeight else state.endHandleLineHeight,
        modifier = Modifier.foundationSelectionHandleGestures(state, isStart),
    )
}

internal val LocalCjkSelectionState = compositionLocalOf<CjkSelectionState?> { null }

internal interface CjkSelectable {
    val selectionText: AnnotatedString
    val selectionCoordinates: LayoutCoordinates?
    fun selectionTextForCopy(range: TextRange): String
    fun updateSelectionCoordinates(coordinates: LayoutCoordinates)
    fun selectionOffsetAt(localPosition: Offset): Int?
    fun selectionWordRangeAt(localPosition: Offset): TextRange?
    fun selectionParagraphRangeAt(localPosition: Offset): TextRange?
    fun coerceSelectionOffset(offset: Int, bias: SourceBoundaryBias): Int
    fun selectionCursorPosition(offset: Int): Offset?
    fun selectionLineRange(offset: Int): TextRange?
    fun selectionLineLeft(offset: Int): Float
    fun selectionLineRight(offset: Int): Float
    fun selectionLineCenterY(offset: Int): Float
    fun selectionLineHeight(offset: Int): Float
    fun selectionBoxes(range: TextRange): List<org.tiqian.core.Rect>
    fun invalidateSelection()
}

private data class CjkSelectionAnchor(
    val selectable: CjkSelectable,
    val offset: Int,
)

private data class CjkSelection(
    val anchor: CjkSelectionAnchor,
    val extent: CjkSelectionAnchor,
)
