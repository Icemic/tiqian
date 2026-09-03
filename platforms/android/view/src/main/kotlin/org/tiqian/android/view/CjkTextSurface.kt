package org.tiqian.android.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.ActionMode
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

/**
 * A generic Android View selection and paint-overhang viewport for Tiqian paragraphs.
 *
 * Descendant [CjkTextView] instances register automatically, including views nested in a
 * RecyclerView. Without [document], attached paragraphs are ordered by visible geometry. With a
 * logical [CjkSelectionDocument], stable fragment keys preserve selection and clipboard text while
 * item views recycle; only attached paragraphs contribute geometry.
 */
class CjkTextSurface @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    /** Engine-authorized paint is replayed by each actual clipping ancestor's overlay. */
    private val overhangRegistry = CjkOverhangRegistry(this)
    private val selectionController = CjkDocumentSelectionController(this)
    private val attachedSelectables = LinkedHashSet<CjkTextView>()

    /** Logical document used for virtualized selection. Null selects attached paragraphs only. */
    var document: CjkSelectionDocument? = null
        set(value) {
            if (field === value) return
            selectionController.validateDocument(value, attachedSelectables)
            selectionController.updateDocument(value, attachedSelectables)
            field = value
        }

    /**
     * Optional host-neutral scrolling capability for virtualized or nested reader surfaces.
     *
     * The capability owns both scroll consumption and viewport geometry. When null, selection
     * auto-scroll discovers the nearest scrollable ordinary [View] ancestor for the active
     * paragraph and adapts that same View for both operations.
     */
    var selectionScrollHost: CjkSelectionScrollHost? = null

    /** Optional keep-alive capability used only while an endpoint gesture is active. */
    var selectionRetentionHost: CjkSelectionRetentionHost? = null

    /** Width of the top and bottom automatic-scroll bands, in dp. */
    var selectionAutoScrollEdgeSizeDp: Float = 48f
        set(value) {
            require(value.isFinite() && value > 0f)
            field = value
        }

    /** Maximum selection-owned automatic-scroll speed, in dp per second. */
    var selectionAutoScrollMaxVelocityDpPerSecond: Float = 1_200f
        set(value) {
            require(value.isFinite() && value > 0f)
            field = value
        }

    /** TextView-compatible extension point for this document's system selection ActionMode. */
    var customSelectionActionModeCallback: ActionMode.Callback? = null
        set(value) {
            if (field === value) return
            field = value
            selectionController.onCustomSelectionActionModeCallbackChanged()
        }

    val hasSelection: Boolean get() = selectionController.hasSelection
    val selectedText: String? get() = selectionController.selectedSourceText

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = false
        clipToPadding = false
        setWillNotDraw(false)
    }

    fun clearSelection() = selectionController.clearSelection()

    fun selectAll(): Boolean = selectionController.selectAll(showToolbar = false)

    fun copySelection(): Boolean = selectionController.copySelection()

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (selectionController.ownsWordGesture) {
            return selectionController.onContainerTouchEvent(event)
        }
        if (selectionController.shouldTakeGesture(event)) {
            selectionController.takeGestureOwnership()
            // Clear ViewGroup's original child touch target exactly once. The child controller
            // observes container ownership and therefore treats this as a routing hand-off, not
            // cancellation of the logical selection gesture.
            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
            try {
                super.dispatchTouchEvent(cancel)
            } finally {
                cancel.recycle()
            }
            return selectionController.onContainerTouchEvent(event)
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (selectionController.shouldTakeGesture(event)) {
            selectionController.takeGestureOwnership()
            return true
        }
        return super.onInterceptTouchEvent(event)
    }

    // Clicks remain owned by the paragraph View; this surface only continues intercepted drags.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean =
        selectionController.onContainerTouchEvent(event) || super.onTouchEvent(event)

    override fun dispatchKeyShortcutEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.hasModifiers(KeyEvent.META_CTRL_ON)) {
            val handled = when (event.keyCode) {
                KeyEvent.KEYCODE_A -> selectAll()
                KeyEvent.KEYCODE_C -> copySelection()
                else -> false
            }
            if (handled) return true
        }
        return super.dispatchKeyShortcutEvent(event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE && hasSelection) {
            clearSelection()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onFocusChanged(
        gainFocus: Boolean,
        direction: Int,
        previouslyFocusedRect: Rect?,
    ) {
        if (!gainFocus) selectionController.clearSelection()
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        selectionController.onHostVisibilityOrFocusChanged()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        selectionController.onHostVisibilityOrFocusChanged()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        selectionController.onHostVisibilityOrFocusChanged()
    }

    override fun onDetachedFromWindow() {
        selectionController.dispose()
        overhangRegistry.dispose()
        attachedSelectables.clear()
        super.onDetachedFromWindow()
    }

    internal fun registerSelectable(view: CjkTextView) {
        attachedSelectables += view
        selectionController.register(view)
    }

    internal fun unregisterSelectable(view: CjkTextView) {
        attachedSelectables -= view
        selectionController.unregister(view)
    }

    internal fun registerPaintOverhang(view: CjkTextView) = overhangRegistry.register(view)

    internal fun unregisterPaintOverhang(view: CjkTextView) = overhangRegistry.unregister(view)

    internal fun onPaintOverhangChanged(view: CjkTextView) = overhangRegistry.onChildStateChanged(view)

    internal fun requiresPaintOverhangReplay(view: CjkTextView): Boolean =
        overhangRegistry.requiresReplay(view)

    /** Package-private geometry hook for platform tests; no handle ownership leaks publicly. */
    internal fun selectionHandleBoundsOnScreen(
        handle: CjkSelectionHandle,
        outBounds: Rect,
    ): Boolean = selectionController.handleBoundsOnScreen(handle, outBounds)

    internal fun isSelectionHandleDragging(handle: CjkSelectionHandle): Boolean =
        selectionController.isHandleDragging(handle)

    internal fun onSelectableEligibilityChanged(view: CjkTextView) =
        selectionController.onSelectableEligibilityChanged(view)

    internal fun validateSelectableContent(view: CjkTextView, content: CjkTextContent) =
        selectionController.validateContent(view, content)

    internal fun rebindSelectable(
        view: CjkTextView,
        key: Any,
        retentionKey: Any,
        content: CjkTextContent,
    ) = selectionController.rebind(view, key, retentionKey, content)

    internal fun unbindSelectable(view: CjkTextView) {
        selectionController.unbind(view)
    }

    internal val densityValue: Float get() = resources.displayMetrics.density

    internal fun resolvedScrollHost(from: CjkTextView): CjkSelectionScrollHost? {
        selectionScrollHost?.let { return it }
        var current = from.parent
        while (current is View) {
            if (
                current !== this &&
                (current.canScrollVertically(-1) || current.canScrollVertically(1))
            ) {
                return CjkSelectionScrollHost.forView(current)
            }
            current = current.parent
        }
        return null
    }
}
