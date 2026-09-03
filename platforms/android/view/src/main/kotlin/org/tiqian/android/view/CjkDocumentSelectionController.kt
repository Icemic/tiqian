package org.tiqian.android.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Build
import android.view.ActionMode
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewParent
import android.view.ViewTreeObserver
import org.tiqian.core.SourceBoundaryBias
import org.tiqian.core.TextRange
import org.tiqian.core.coerceSelectionOffset
import org.tiqian.core.cursorRect
import org.tiqian.core.getLineForOffset
import org.tiqian.core.selectionOffsetForPosition
import org.tiqian.core.selectionWordRangeForPosition
import kotlin.math.max
import kotlin.math.min

private object NoDocumentSelectionKey

@SuppressLint("InlinedApi")
internal class CjkDocumentSelectionController(
    private val container: CjkTextSurface,
) : CjkSelectionHandleListener {
    private val noSelectionPosition = CjkDocumentHandlePosition(
        CjkDocumentSelectionAnchor(NoDocumentSelectionKey, 0),
    )

    private val registrations = LinkedHashMap<CjkTextView, Any>()
    private val retentionKeys = LinkedHashMap<CjkTextView, Any>()
    private val ordering = CjkDocumentSelectionOrder(container, registrations)
    private var selection: CjkDocumentSelection? = null
    private var projections: Map<CjkTextView, CjkDocumentSelectionProjection> = emptyMap()
    private var selectedSourceTextCache: String? = null
    private var clipboardTextCache: String? = null
    private var selectedSourceTextCacheValid = false
    private var clipboardTextCacheValid = false
    internal val selectedSourceText: String?
        get() {
            if (!selectedSourceTextCacheValid) {
                selectedSourceTextCache = buildSelectedText(copyProjection = false)
                selectedSourceTextCacheValid = true
            }
            return selectedSourceTextCache
        }
    private var eventHost: CjkTextView? = null
    private var pressedLink: CjkTextView.LinkHit? = null
    private var handledGesture = false
    private var wordDrag: WordDrag? = null
    private var containerOwnsGesture = false
    private var activeHandle: CjkSelectionHandle? = null
    private var isTouchSelection = false
    private var retentionHandle: CjkSelectionRetentionHandle? = null
    private var retainedView: CjkTextView? = null
    private var gestureInterceptParent: ViewParent? = null
    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        if (hasSelection) {
            handles.update()
            updateActionModeGeometryVisibility()
        }
        true
    }
    private var preDrawInstalled = false
    private var selectionGeometryVisible = false
    private val lastSelectionContentRect = Rect()
    private val touchSlop = ViewConfiguration.get(container.context).scaledTouchSlop.toFloat()
    private val endpointResolver = CjkSelectionEndpointResolver(container.densityValue)
    private val handleDragResolver = CjkDocumentSelectionHandleDragResolver(
        ordering = ordering,
        endpointResolver = endpointResolver,
        anchorView = ::anchorView,
    )
    private val documentMagnifier = CjkDocumentMagnifier(container)
    private val handles = CjkDocumentSelectionHandles(
        host = container,
        listener = this,
        selection = ::normalizedSelection,
        anchorView = ::anchorView,
        canPresent = ::canPresentSelectionUi,
    )
    private val autoScroller = CjkSelectionAutoScrollDriver(
        host = container,
        isArmed = { wordDrag?.autoScrollArmed == true || activeHandle != null },
        refreshEndpoint = { rawX, rawY ->
            if (wordDrag != null) {
                updateWordDrag(rawX, rawY)
            } else {
                activeHandle?.let { handle ->
                    onHandleDragMoved(
                        handle,
                        Float.NaN,
                        Float.NaN,
                        rawX,
                        rawY,
                        true,
                    )
                }
            }
        },
    )

    val hasSelection: Boolean get() = selection?.let { it.anchor != it.extent } == true

    /** The surface owns focus while it owns a document selection. */
    internal val selectionOwnerHasFocus: Boolean
        get() = container.isFocused

    internal val customSelectionActionModeCallback: ActionMode.Callback?
        get() = container.customSelectionActionModeCallback

    internal fun setCustomSelectionActionModeCallback(value: ActionMode.Callback?) {
        container.customSelectionActionModeCallback = value
    }

    val ownsWordGesture: Boolean get() = wordDrag != null && containerOwnsGesture

    private val actionMode = CjkTextSelectionActionMode(
        host = container,
        customCallback = { container.customSelectionActionModeCallback },
        delegate = object : CjkTextSelectionActionMode.Delegate {
            override val hasSelection: Boolean
                get() = this@CjkDocumentSelectionController.hasSelection
            override val canSelectAll: Boolean
                get() = !isEntireDocumentSelected()

            override fun copySelection(): Boolean = this@CjkDocumentSelectionController.copySelection()
            override fun selectAll(): Boolean = this@CjkDocumentSelectionController.selectAll(false)
            override fun selectedText(): String? = selectedSourceText
            override fun selectionContentRect(outRect: Rect) = this@CjkDocumentSelectionController
                .selectionContentRect(outRect)
            override fun onActionModeCreationRejected() = clearSelection()
            override fun onActionModeDestroyed(preserveSelection: Boolean) {
                if (!preserveSelection) clearSelection()
            }
            override fun performAssistAction(item: android.view.MenuItem): Boolean = false
        },
    )

    private val gestures = GestureDetector(
        container.context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean {
                val host = eventHost ?: return false
                pressedLink = host.linkAt(event.x, event.y)
                handledGesture = pressedLink != null || host.textIsSelectable || hasSelection
                return handledGesture
            }

            override fun onSingleTapUp(event: MotionEvent): Boolean {
                val host = eventHost ?: return false
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
                eventHost?.let { beginWordDrag(it, event.x, event.y, event.rawX, event.rawY) } == true

            override fun onLongPress(event: MotionEvent) {
                val host = eventHost ?: return
                if (beginWordDrag(host, event.x, event.y, event.rawX, event.rawY)) {
                    container.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
            }
        },
    )

    fun updateDocument(next: CjkSelectionDocument?, candidates: Collection<CjkTextView>) {
        clearSelection()
        registrations.keys.toList().forEach { it.detachDocumentSelection(this) }
        registrations.clear()
        retentionKeys.clear()
        ordering.setDocument(next)
        candidates.forEach(::register)
    }

    fun validateDocument(next: CjkSelectionDocument?, candidates: Collection<CjkTextView>) {
        ordering.validateDocument(next, candidates)
    }

    fun rebind(
        view: CjkTextView,
        key: Any,
        retentionKey: Any,
        content: CjkTextContent,
    ) {
        ordering.validateProspectiveBinding(view, key, content)
        val wasRegistered = view in registrations
        if (
            retainedView === view &&
            (registrations[view] != key || retentionKeys[view] != retentionKey)
        ) {
            releaseRetention()
        }
        val oldKey = view.selectionDocumentKey
        val oldRetentionKey = view.selectionRetentionKey
        val oldContent = view.content
        val bindingChange = try {
            view.applySelectionFragmentBinding(key, retentionKey, content)
        } catch (failure: Throwable) {
            try {
                if (oldKey != null && oldRetentionKey != null) {
                    runCatching {
                        view.applySelectionFragmentBinding(oldKey, oldRetentionKey, oldContent)
                    }.exceptionOrNull()?.let(failure::addSuppressed)
                } else {
                    view.clearSelectionFragmentBinding()
                }
            } finally {
                view.cancelSelectionFragmentBinding()
            }
            throw failure
        }
        val projectionChanged = !wasRegistered || oldKey != key ||
            bindingChange.textChanged || bindingChange.geometryChanged
        try {
            registrations[view] = key
            retentionKeys[view] = retentionKey
            if (projectionChanged) ordering.invalidate()
            if (!wasRegistered) view.attachDocumentSelection(this)
            if (projectionChanged) updateDerivedSelection()
        } finally {
            view.completeSelectionFragmentBinding(bindingChange)
        }
    }

    fun unbind(view: CjkTextView) {
        val wasRegistered = registrations.remove(view) != null
        retentionKeys.remove(view)
        ordering.invalidate()
        if (retainedView === view) releaseRetention()
        val identityChanged = view.clearSelectionFragmentBinding()
        val bindingChange = CjkTextView.SelectionFragmentBindingChange(
            contentChanged = false,
            textChanged = false,
            geometryChanged = false,
            identityChanged = identityChanged,
        )
        try {
            if (ordering.document == null && view.textIsSelectable) {
                registrations[view] = view
                retentionKeys[view] = view
                if (!wasRegistered) view.attachDocumentSelection(this)
                updateDerivedSelection()
                return
            }
            if (wasRegistered) {
                updateDerivedSelection()
                view.detachDocumentSelection(this)
            }
        } finally {
            view.completeSelectionFragmentBinding(bindingChange)
        }
    }

    fun register(view: CjkTextView) {
        if (!view.textIsSelectable || registrations.containsKey(view)) return
        val key = ordering.keyFor(view) ?: return
        ordering.validateRegistration(view)
        require(registrations.none { (other, otherKey) -> other !== view && otherKey == key }) {
            "Only one attached CjkTextView may expose document fragment key: $key"
        }
        registrations[view] = key
        retentionKeys[view] = view.selectionRetentionKey ?: key
        ordering.invalidate()
        view.attachDocumentSelection(this)
        updateDerivedSelection()
    }

    fun unregister(view: CjkTextView) {
        val key = registrations.remove(view) ?: return
        retentionKeys.remove(view)
        ordering.invalidate()
        if (retainedView === view) releaseRetention()
        if (
            ordering.document == null &&
            selection?.let { it.anchor.key == key || it.extent.key == key } == true
        ) {
            clearSelection()
        } else {
            updateDerivedSelection()
        }
        // updateDerivedSelection clears this View's previous projection. Keep the owner attached
        // until that publication has completed; reversing the order crashes during holder recycle
        // and window teardown because a detached standalone controller rejects document state.
        view.detachDocumentSelection(this)
    }

    fun onSelectableEligibilityChanged(view: CjkTextView) {
        if (view.textIsSelectable) register(view) else unregister(view)
    }

    fun validateContent(view: CjkTextView, content: CjkTextContent) {
        ordering.validateContent(view, content)
    }

    fun onSelectableTextOrGeometryChanged(view: CjkTextView, textChanged: Boolean) {
        if (view !in registrations) return
        ordering.validateRegistration(view)
        if (textChanged) invalidateSelectedTextCaches()
        if (textChanged && ordering.document == null) {
            val key = registrations.getValue(view)
            if (selection?.let { it.anchor.key == key || it.extent.key == key } == true) {
                clearSelection()
                return
            }
        }
        updateDerivedSelection()
    }

    fun onSelectableGeometryChanged(view: CjkTextView) {
        if (view !in registrations) return
        ordering.invalidate()
        if (!hasSelection) return
        handles.update()
        actionMode.invalidateContentRect()
    }

    fun onHostVisibilityOrFocusChanged() {
        if (!canPresentSelectionUi()) {
            handles.dismiss()
            dismissMagnifier()
        } else if (hasSelection && wordDrag == null && activeHandle == null) {
            handles.update()
        }
        actionMode.onHostVisibilityOrFocusChanged()
    }

    fun onCustomSelectionActionModeCallbackChanged() = actionMode.onCustomCallbackChanged()

    fun setSelection(view: CjkTextView, start: Int, end: Int, showToolbar: Boolean): Boolean {
        val key = registrations[view] ?: return false
        val snapshot = view.layoutSnapshot ?: return false
        val lower = min(start, end)
        val upper = max(start, end)
        val safeStart = snapshot.result.coerceSelectionOffset(lower, SourceBoundaryBias.Backward)
        val safeEnd = snapshot.result.coerceSelectionOffset(upper, SourceBoundaryBias.Forward)
        if (safeStart >= safeEnd) {
            clearSelection()
            return false
        }
        if (!container.requestFocus()) return false
        setSelection(
            CjkDocumentSelectionAnchor(key, safeStart),
            CjkDocumentSelectionAnchor(key, safeEnd),
            touch = showToolbar,
        )
        if (showToolbar) actionMode.show() else actionMode.invalidateContentRect()
        return true
    }

    fun selectAll(showToolbar: Boolean): Boolean {
        val ordered = ordering.logicalFragments()
        val first = ordered.firstOrNull() ?: return false
        val last = ordered.last()
        if (!container.requestFocus()) return false
        setSelection(
            CjkDocumentSelectionAnchor(first.key, 0),
            CjkDocumentSelectionAnchor(last.key, last.text.length),
            touch = showToolbar,
        )
        if (showToolbar) actionMode.show()
        return true
    }

    fun clearSelection() {
        actionMode.finish(preserveSelection = true)
        val affected = projections.keys.toList()
        selection = null
        projections = emptyMap()
        invalidateSelectedTextCaches()
        wordDrag = null
        activeHandle = null
        isTouchSelection = false
        selectionGeometryVisible = false
        lastSelectionContentRect.setEmpty()
        containerOwnsGesture = false
        autoScroller.stop()
        releaseRetention()
        dismissMagnifier()
        handles.dismiss()
        actionMode.onSelectionCleared()
        affected.forEach { it.applyDocumentSelection(null) }
        removePreDrawListener()
        releaseGestureInterception()
    }

    fun copySelection(): Boolean {
        if (!clipboardTextCacheValid) {
            clipboardTextCache = buildSelectedText(copyProjection = true)
            clipboardTextCacheValid = true
        }
        val text = clipboardTextCache?.takeIf { it.isNotEmpty() } ?: return false
        val clipboard = container.context.getSystemService(Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager ?: return false
        return runCatching {
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(null, text))
        }.isSuccess
    }

    fun onSelectableTouchEvent(view: CjkTextView, event: MotionEvent): Boolean {
        eventHost = view
        if (wordDrag != null && !containerOwnsGesture) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> updateWordDrag(event.rawX, event.rawY)
                MotionEvent.ACTION_UP -> {
                    updateWordDrag(event.rawX, event.rawY)
                    finishWordDrag()
                }
                MotionEvent.ACTION_CANCEL -> if (!containerOwnsGesture) cancelWordDrag()
            }
            return true
        }
        val handled = gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) pressedLink = null
        return handled || handledGesture
    }

    fun shouldTakeGesture(event: MotionEvent): Boolean =
        wordDrag != null && !containerOwnsGesture && event.actionMasked != MotionEvent.ACTION_DOWN

    fun takeGestureOwnership() {
        if (wordDrag == null) return
        containerOwnsGesture = true
    }

    fun onContainerTouchEvent(event: MotionEvent): Boolean {
        if (wordDrag == null || !containerOwnsGesture) return false
        val global = containerEventToGlobal(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> updateWordDrag(global.first, global.second)
            MotionEvent.ACTION_UP -> {
                updateWordDrag(global.first, global.second)
                finishWordDrag()
            }
            MotionEvent.ACTION_CANCEL -> cancelWordDrag()
        }
        return true
    }

    private fun containerEventToGlobal(event: MotionEvent): Pair<Float, Float> {
        if (Build.VERSION.SDK_INT >= 29) {
            val matrix = Matrix()
            container.transformMatrixToGlobal(matrix)
            val point = floatArrayOf(event.x, event.y)
            matrix.mapPoints(point)
            return point[0] to point[1]
        }
        return event.rawX to event.rawY
    }

    private fun beginWordDrag(
        view: CjkTextView,
        x: Float,
        y: Float,
        rawX: Float,
        rawY: Float,
    ): Boolean {
        val key = registrations[view] ?: return false
        if (!view.textIsSelectable || !container.requestFocus()) return false
        val snapshot = view.layoutSnapshot ?: return false
        val word = snapshot.replayIndex.selectionWordRangeForPosition(
            snapshot.result,
            view.toContentX(x),
            view.toContentY(y),
        ) ?: return false
        val start = snapshot.result.coerceSelectionOffset(word.start, SourceBoundaryBias.Backward)
        val end = snapshot.result.coerceSelectionOffset(word.end, SourceBoundaryBias.Forward)
        if (start >= end) return false
        val initial = CjkDocumentSelection(
            CjkDocumentSelectionAnchor(key, start),
            CjkDocumentSelectionAnchor(key, end),
        )
        actionMode.finish(preserveSelection = true)
        handles.dismiss()
        wordDrag = WordDrag(initial, rawX, rawY)
        containerOwnsGesture = false
        autoScroller.scrollHost = container.resolvedScrollHost(view)
        retainEndpoint(view)
        autoScroller.updatePointer(rawX, rawY)
        // The selectable can sit below an intercepting surface such as RecyclerView. Block from
        // its immediate parent so the request propagates through every ancestor and the original
        // touch target continues receiving moves outside its bounds. Starting at the container's
        // parent misses interceptors nested inside the container.
        releaseGestureInterception()
        gestureInterceptParent = view.parent
        gestureInterceptParent?.requestDisallowInterceptTouchEvent(true)
        setSelection(initial.anchor, initial.extent, touch = true)
        return true
    }

    private fun updateWordDrag(rawX: Float, rawY: Float) {
        val initial = wordDrag?.initial ?: return
        wordDrag?.let { drag ->
            val dx = rawX - drag.originRawX
            val dy = rawY - drag.originRawY
            if (!drag.autoScrollArmed && dx * dx + dy * dy >= touchSlop * touchSlop) {
                drag.autoScrollArmed = true
            }
        }
        autoScroller.updatePointer(rawX, rawY)
        val target = wordSelectionAtRaw(rawX, rawY)
        if (target != null) {
            val next = when {
                ordering.compareAnchors(target.extent, initial.anchor) <= 0 ->
                    CjkDocumentSelection(target.anchor, initial.extent)
                ordering.compareAnchors(target.anchor, initial.extent) >= 0 ->
                    CjkDocumentSelection(initial.anchor, target.extent)
                else -> initial
            }
            setSelection(next.anchor, next.extent, touch = true)
            val moving = if (ordering.compareAnchors(target.extent, initial.anchor) <= 0) {
                target.anchor
            } else {
                target.extent
            }
            showMagnifier(moving, rawX, rawY)
        } else {
            dismissMagnifier()
        }
        autoScroller.schedule()
    }

    private fun finishWordDrag() {
        wordDrag = null
        containerOwnsGesture = false
        autoScroller.stop()
        releaseRetention()
        dismissMagnifier()
        releaseGestureInterception()
        if (hasSelection) {
            handles.update()
            actionMode.showAfterSelectionGesture()
        }
    }

    private fun cancelWordDrag() {
        wordDrag = null
        containerOwnsGesture = false
        autoScroller.stop()
        releaseRetention()
        dismissMagnifier()
        releaseGestureInterception()
        if (hasSelection) handles.update()
    }

    private fun setSelection(
        anchor: CjkDocumentSelectionAnchor,
        extent: CjkDocumentSelectionAnchor,
        touch: Boolean,
    ) {
        val normalized = ordering.normalize(anchor, extent) ?: return
        val next = CjkDocumentSelection(normalized.first, normalized.second)
        if (selection == next) return
        if (selection == null) isTouchSelection = touch else if (touch) isTouchSelection = true
        selection = next
        invalidateSelectedTextCaches()
        if (touch) container.performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
        updateDerivedSelection()
    }

    private fun updateDerivedSelection() {
        val previous = projections.keys.toSet()
        projections = buildVisibleProjections()
        val affected = LinkedHashSet<CjkTextView>().apply {
            addAll(previous)
            addAll(projections.keys)
        }
        affected.forEach { it.applyDocumentSelection(projections[it]) }
        if (hasSelection) {
            installPreDrawListener()
            if (wordDrag == null) handles.update()
            updateActionModeGeometryVisibility()
        } else {
            handles.dismiss()
            removePreDrawListener()
        }
    }

    private fun buildVisibleProjections(): Map<CjkTextView, CjkDocumentSelectionProjection> {
        val normalized = normalizedSelection() ?: return emptyMap()
        if (normalized.first == normalized.second) return emptyMap()
        val logical = ordering.logicalFragments()
        val slice = logical.selectionSlice(normalized.first, normalized.second) ?: return emptyMap()
        return buildMap {
            registrations.forEach { (view, key) ->
                val index = ordering.orderOf(key)
                logical.selectionProjectionAt(slice, index)?.let { put(view, it) }
            }
        }
    }

    private fun invalidateSelectedTextCaches() {
        selectedSourceTextCache = null
        clipboardTextCache = null
        selectedSourceTextCacheValid = false
        clipboardTextCacheValid = false
    }

    private fun buildSelectedText(copyProjection: Boolean): String? {
        val normalized = normalizedSelection() ?: return null
        if (normalized.first == normalized.second) return null
        val fragments = ordering.logicalFragments()
        return fragments.projectSelectionText(normalized.first, normalized.second, copyProjection)
    }

    private fun normalizedSelection(): Pair<CjkDocumentSelectionAnchor, CjkDocumentSelectionAnchor>? =
        selection?.let { ordering.normalize(it.anchor, it.extent) }


    private fun wordSelectionAtRaw(rawX: Float, rawY: Float): CjkDocumentSelection? {
        val view = selectableAtRaw(registrations.keys, rawX, rawY) ?: return null
        val key = registrations.getValue(view)
        val local = rawToView(view, rawX, rawY)
        val snapshot = view.layoutSnapshot ?: return null
        val contentX = view.toContentX(local.first)
        val contentY = view.toContentY(local.second)
        val word = snapshot.replayIndex.selectionWordRangeForPosition(
            snapshot.result,
            contentX,
            contentY,
        ) ?: return null
        val start = snapshot.result.coerceSelectionOffset(word.start, SourceBoundaryBias.Backward)
        val end = snapshot.result.coerceSelectionOffset(word.end, SourceBoundaryBias.Forward)
        if (start >= end) return null
        return CjkDocumentSelection(
            CjkDocumentSelectionAnchor(key, start),
            CjkDocumentSelectionAnchor(key, end),
        )
    }

    private fun handleHitAtRaw(rawX: Float, rawY: Float): HandleHit? {
        val view = selectableAtRaw(registrations.keys, rawX, rawY) ?: return null
        val snapshot = view.layoutSnapshot ?: return null
        val key = registrations.getValue(view)
        val local = rawToView(view, rawX, rawY)
        val contentX = view.toContentX(local.first)
        val queryY = endpointResolver.lineSlopAdjustedY(
            key,
            ordering.orderOf(key),
            snapshot.result,
            view.toContentY(local.second),
        )
        val rawOffset = snapshot.replayIndex.selectionOffsetForPosition(
            snapshot.result,
            contentX,
            queryY,
        )
        val offset = snapshot.result.coerceSelectionOffset(rawOffset, SourceBoundaryBias.Nearest)
        return HandleHit(view, key, ordering.orderOf(key), snapshot, contentX, queryY, offset)
    }

    private fun anchorView(anchor: CjkDocumentSelectionAnchor): CjkTextView? =
        registrations.entries.firstOrNull { it.value == anchor.key }?.key

    fun handleBoundsOnScreen(handle: CjkSelectionHandle, outBounds: Rect): Boolean =
        handles.boundsOnScreen(handle, outBounds)

    fun isHandleDragging(handle: CjkSelectionHandle): Boolean = handles.isDragging(handle)

    override fun currentPosition(handle: CjkSelectionHandle): CjkSelectionHandlePosition {
        val normalized = normalizedSelection() ?: return noSelectionPosition
        val endpoint = if (handle == CjkSelectionHandle.Start) normalized.first else normalized.second
        return CjkDocumentHandlePosition(endpoint)
    }

    override fun onHandleDragStarted(handle: CjkSelectionHandle) {
        activeHandle = handle
        actionMode.hide()
        dismissMagnifier()
        container.parent?.requestDisallowInterceptTouchEvent(true)
        val endpoint = normalizedSelection()?.let {
            if (handle == CjkSelectionHandle.Start) it.first else it.second
        }
        val endpointView = endpoint?.let(::anchorView)
        val snapshot = endpointView?.layoutSnapshot
        if (endpoint != null && snapshot != null) {
            endpointResolver.begin(
                CjkSelectionEndpointPosition(endpoint.key, ordering.orderOf(endpoint.key), endpoint.offset),
                snapshot.result.getLineForOffset(endpoint.offset),
            )
        } else {
            endpointResolver.reset()
        }
        autoScroller.scrollHost = endpointView?.let(container::resolvedScrollHost)
        endpointView?.let(::retainEndpoint)
    }

    override fun onHandleDragMoved(
        handle: CjkSelectionHandle,
        viewX: Float,
        viewY: Float,
        rawX: Float,
        rawY: Float,
        fromTouchScreen: Boolean,
    ): CjkSelectionHandlePosition {
        autoScroller.updatePointer(rawX, rawY)
        val normalized = normalizedSelection() ?: return currentPosition(handle)
        // ActionMode.hide() is a time-bounded lease. Renew it on every MOVE like
        // Editor.updateFloatingToolbarVisibility() so a long drag cannot reveal the toolbar over
        // the handle magnifier.
        actionMode.hide()
        val pointerHit = handleHitAtRaw(rawX, rawY)
        if (pointerHit == null) {
            dismissMagnifier()
            autoScroller.schedule()
            return currentPosition(handle)
        }
        val fixedEndpoint = when (handle) {
            CjkSelectionHandle.Start -> normalized.second
            CjkSelectionHandle.End -> normalized.first
        }
        val hit = handleDragResolver.projectCrossedPointerOntoFixedLine(
            handle = handle,
            candidate = pointerHit,
            fixedEndpoint = fixedEndpoint,
            rawX = rawX,
            rawY = rawY,
        )
        val currentEndpoint = when (handle) {
            CjkSelectionHandle.Start -> normalized.first
            CjkSelectionHandle.End -> normalized.second
        }
        val resolvedOffset = endpointResolver.resolve(
            snapshot = hit.snapshot,
            isStart = handle == CjkSelectionHandle.Start,
            candidatePosition = CjkSelectionEndpointPosition(hit.key, hit.order, hit.rawOffset),
            currentPosition = CjkSelectionEndpointPosition(
                currentEndpoint.key,
                ordering.orderOf(currentEndpoint.key),
                currentEndpoint.offset,
            ),
            rawOffset = hit.rawOffset,
            contentX = hit.contentX,
            queryY = hit.queryY,
        )
        val candidate = CjkDocumentSelectionAnchor(hit.key, resolvedOffset)
        val accepted = when (handle) {
            CjkSelectionHandle.Start -> clampStart(candidate, normalized.first, normalized.second)
            CjkSelectionHandle.End -> clampEnd(candidate, normalized.first, normalized.second)
        }
        if (handle == CjkSelectionHandle.Start) {
            setSelection(accepted, normalized.second, touch = true)
        } else {
            setSelection(normalized.first, accepted, touch = true)
        }
        anchorView(accepted)?.layoutSnapshot?.let { acceptedSnapshot ->
            val acceptedX = if (accepted.key == hit.key) {
                hit.contentX
            } else {
                acceptedSnapshot.replayIndex.cursorRect(acceptedSnapshot.result, accepted.offset).left
            }
            endpointResolver.commit(
                CjkSelectionEndpointPosition(accepted.key, ordering.orderOf(accepted.key), accepted.offset),
                acceptedSnapshot.result.getLineForOffset(accepted.offset),
                acceptedX,
            )
        }
        if (fromTouchScreen) showMagnifier(accepted, rawX, rawY)
        autoScroller.schedule()
        return CjkDocumentHandlePosition(accepted)
    }

    override fun onHandleDragFinished(
        handle: CjkSelectionHandle,
        filteredPosition: CjkSelectionHandlePosition?,
        cancelled: Boolean,
    ) {
        val filtered = (filteredPosition as? CjkDocumentHandlePosition)?.anchor
            ?.takeUnless { it.key == NoDocumentSelectionKey }
            ?.takeIf { ordering.orderOf(it.key) != Int.MAX_VALUE }
        if (!cancelled && filtered != null) {
            val normalized = normalizedSelection()
            if (normalized != null) {
                if (handle == CjkSelectionHandle.Start) {
                    setSelection(
                        clampStart(filtered, normalized.first, normalized.second),
                        normalized.second,
                        touch = true,
                    )
                } else {
                    setSelection(
                        normalized.first,
                        clampEnd(filtered, normalized.first, normalized.second),
                        touch = true,
                    )
                }
            }
        }
        activeHandle = null
        endpointResolver.reset()
        autoScroller.stop()
        releaseRetention()
        dismissMagnifier()
        container.parent?.requestDisallowInterceptTouchEvent(false)
        handles.update()
        if (!cancelled) actionMode.showAfterSelectionGesture()
    }

    private fun clampStart(
        candidate: CjkDocumentSelectionAnchor,
        currentStart: CjkDocumentSelectionAnchor,
        fixedEnd: CjkDocumentSelectionAnchor,
    ): CjkDocumentSelectionAnchor {
        if (ordering.compareAnchors(candidate, fixedEnd) < 0) return candidate
        return handleDragResolver.previousBoundary(fixedEnd) ?: currentStart
    }

    private fun clampEnd(
        candidate: CjkDocumentSelectionAnchor,
        fixedStart: CjkDocumentSelectionAnchor,
        currentEnd: CjkDocumentSelectionAnchor,
    ): CjkDocumentSelectionAnchor {
        if (ordering.compareAnchors(candidate, fixedStart) > 0) return candidate
        return handleDragResolver.nextBoundary(fixedStart) ?: currentEnd
    }


    private fun selectionContentRect(outRect: Rect) {
        if (!calculateSelectionContentRect(outRect)) outRect.setEmpty()
    }

    private fun updateActionModeGeometryVisibility() {
        val contentRect = Rect()
        val visible = calculateSelectionContentRect(contentRect)
        if (visible) {
            if (!selectionGeometryVisible || contentRect != lastSelectionContentRect) {
                lastSelectionContentRect.set(contentRect)
                actionMode.invalidateContentRect()
            }
            if (
                !selectionGeometryVisible && isTouchSelection &&
                wordDrag == null && activeHandle == null
            ) {
                actionMode.show()
            }
        } else if (selectionGeometryVisible) {
            lastSelectionContentRect.setEmpty()
            actionMode.hide()
        }
        selectionGeometryVisible = visible
    }

    private fun calculateSelectionContentRect(outRect: Rect): Boolean {
        return CjkDocumentSelectionGeometry.calculate(
            container,
            projections.keys,
            handles.height,
            outRect,
        )
    }

    private fun showMagnifier(anchor: CjkDocumentSelectionAnchor, rawX: Float, rawY: Float) {
        val view = anchorView(anchor) ?: return dismissMagnifier()
        val snapshot = view.layoutSnapshot ?: return dismissMagnifier()
        val normalized = normalizedSelection()
        val fixed = normalized?.let {
            if (anchor == it.first) it.second else it.first
        }
        documentMagnifier.show(
            view,
            snapshot,
            anchor.offset,
            fixed?.offset?.takeIf { fixed.key == anchor.key },
            normalized?.first == anchor,
            rawX,
            rawY,
        )
    }

    private fun dismissMagnifier() {
        documentMagnifier.dismiss()
    }

    private fun isEntireDocumentSelected(): Boolean {
        val normalized = normalizedSelection() ?: return false
        val fragments = ordering.logicalFragments()
        val first = fragments.firstOrNull() ?: return true
        val last = fragments.last()
        return normalized.first.key == first.key && normalized.first.offset == 0 &&
            normalized.second.key == last.key && normalized.second.offset == last.text.length
    }

    private fun installPreDrawListener() {
        if (preDrawInstalled) return
        container.viewTreeObserver.addOnPreDrawListener(preDrawListener)
        preDrawInstalled = true
    }

    private fun removePreDrawListener() {
        if (!preDrawInstalled) return
        if (container.viewTreeObserver.isAlive) {
            container.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
        }
        preDrawInstalled = false
    }

    private fun releaseGestureInterception() {
        gestureInterceptParent?.requestDisallowInterceptTouchEvent(false)
        gestureInterceptParent = null
    }

    private fun retainEndpoint(view: CjkTextView) {
        releaseRetention()
        val key = retentionKeys[view] ?: return
        retentionHandle = container.selectionRetentionHost?.retain(key)
        if (retentionHandle != null) retainedView = view
    }

    private fun releaseRetention() {
        retentionHandle?.release()
        retentionHandle = null
        retainedView = null
    }

    private fun canPresentSelectionUi(): Boolean =
        container.isAttachedToWindow &&
            container.isShown &&
            container.visibility == View.VISIBLE &&
            container.windowVisibility == View.VISIBLE &&
            container.hasWindowFocus() &&
            container.isFocused

    fun dispose() {
        clearSelection()
        actionMode.dispose()
        registrations.keys.toList().forEach(::unregister)
        selection = null
    }

}
