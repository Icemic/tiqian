package org.tiqian.android.view

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.AttributeSet
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import org.tiqian.android.rendering.AndroidParagraphMeasurementSession
import org.tiqian.android.rendering.AndroidParagraphMeasurer
import org.tiqian.android.rendering.AndroidParagraphRenderer
import org.tiqian.android.rendering.AndroidPrecomputedParagraph
import org.tiqian.clreq.ClreqProfile
import org.tiqian.core.LayoutInput
import org.tiqian.core.LayoutResult
import org.tiqian.core.LayoutResultReplayIndex
import org.tiqian.core.RichTextRole
import org.tiqian.core.TextRange
import org.tiqian.core.TextStyle
import org.tiqian.core.legalHangingPunctuationClipEdge
import org.tiqian.core.toReplayIndex
import org.tiqian.core.visiblePaintOverhang
import org.tiqian.shaping.android.AndroidTypefaceResolver
import org.tiqian.shaping.android.SystemAndroidTypefaceResolver
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * First-class Android View frontend for one Tiqian paragraph.
 *
 * This View measures through the Tiqian engine and replays the returned [LayoutResult] on a native
 * Canvas. Android never performs a second text layout. Layout-affecting updates call
 * [requestLayout]; paint-only updates keep the existing result and only invalidate replay state.
 */
class CjkTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0,
) : ViewGroup(context, attrs, defStyleAttr, defStyleRes) {
    internal data class LayoutSnapshot(
        val result: LayoutResult,
        val replayIndex: LayoutResultReplayIndex,
    )

    internal data class LinkHit(
        val target: String,
        val range: TextRange,
    )

    private data class InlineChild(
        val id: Any,
        val spanIndex: Int,
        val view: View,
    )

    private var measurementSession: AndroidParagraphMeasurementSession? = null
    private var typefaceResolver: AndroidTypefaceResolver = SystemAndroidTypefaceResolver()
    private var measurer = AndroidParagraphMeasurer(typefaceResolver = typefaceResolver)

    /** Follows the attach/detach window; null whenever the View is detached. */
    private var renderer: AndroidParagraphRenderer? = null
    private var submittedPrecomputedLayout: AndroidPrecomputedParagraph? = null
    private var inlineChildren: List<InlineChild> = emptyList()
    private var clipLeft = 0f
    private var clipTop = 0f
    private var clipRight = 0f
    private var clipBottom = 0f
    private val selectionController = CjkTextSelectionController(this)
    private val accessibilityDelegate = CjkTextAccessibilityDelegate(this)

    internal var layoutSnapshot: LayoutSnapshot? = null
        private set

    internal val selectionHandlesShowing: Boolean
        get() = selectionController.handlesShowing

    /** Immutable content revision displayed by this View. */
    var content: CjkTextContent = CjkTextContent(
        text = "",
        textStyle = TextStyle(
            fontSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                16f,
                resources.displayMetrics,
            ),
        ),
    )
        set(value) {
            if (field == value) return
            val layoutChanged = !field.hasSameLayoutContract(value, maxLines)
            val oldText = field.content.text
            field = value
            submittedPrecomputedLayout = null
            syncInlineChildren()
            if (layoutChanged) {
                clearLayout()
                requestLayout()
            } else {
                layoutSnapshot = layoutSnapshot?.let { snapshot ->
                    LayoutSnapshot(snapshot.result, snapshot.result.toReplayIndex(value.richTextSpans))
                }
                renderer?.invalidateGeometry()
                invalidate()
            }
            if (oldText != value.content.text) {
                selectionController.clearSelection()
            } else {
                selectionController.onContentOrLayoutChanged()
            }
            accessibilityDelegate.invalidateRoot()
            notifyTextChanged(oldText, value.content.text)
        }

    /** Maximum emitted line count. */
    var maxLines: Int = Int.MAX_VALUE
        set(value) {
            require(value > 0) { "maxLines must be positive" }
            if (field == value) return
            field = value
            submittedPrecomputedLayout = null
            clearLayout()
            requestLayout()
        }

    /** Minimum line-height reservation. It never invents hidden layout lines. */
    var minLines: Int = 1
        set(value) {
            require(value > 0) { "minLines must be positive" }
            if (field == value) return
            field = value
            requestLayout()
        }

    var overflow: CjkTextOverflow = CjkTextOverflow.Clip
        set(value) {
            if (field == value) return
            field = value
            clipChildren = value == CjkTextOverflow.Clip
            invalidate()
        }

    var textIsSelectable: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            isLongClickable = value
            isFocusableInTouchMode = value
            if (!value) selectionController.clearSelection()
            accessibilityDelegate.invalidateRoot()
        }

    /** Optional state-list override; null uses [CjkTextContent.textColor]. */
    var textColors: ColorStateList? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var selectionColor: Int = resolveThemeColor(android.R.attr.textColorHighlight, 0x6633B5E5)
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var clreqProfile: ClreqProfile = ClreqProfile.MainlandHorizontal
        set(value) {
            if (field == value) return
            field = value
            rebuildMeasurer()
        }

    var inlineViewAdapter: CjkInlineViewAdapter? = null
        set(value) {
            if (field === value) return
            recycleInlineChildren()
            field = value
            syncInlineChildren()
            requestLayout()
        }

    var onLinkClickListener: CjkLinkClickListener? = null
    var onTextLayout: ((LayoutResult) -> Unit)? = null

    val layoutResult: LayoutResult? get() = layoutSnapshot?.result
    val selection: TextRange? get() = selectionController.range

    init {
        setWillNotDraw(false)
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isLongClickable = textIsSelectable
        isFocusableInTouchMode = textIsSelectable
        clipToPadding = false
        clipChildren = true
        accessibilityDelegate.install()
        readAttributes(attrs, defStyleAttr, defStyleRes)
    }

    /** Shares width-independent shaping and metrics across a document's View surfaces. */
    fun setMeasurementSession(session: AndroidParagraphMeasurementSession?) {
        if (measurementSession === session) return
        measurementSession = session
        typefaceResolver = session?.typefaceResolver ?: SystemAndroidTypefaceResolver()
        renderer?.close()
        renderer = if (isAttachedToWindow) AndroidParagraphRenderer(typefaceResolver) else null
        rebuildMeasurer()
    }

    /**
     * Submits a background-computed result. Its content/style contract is checked immediately and
     * its exact width/max-lines constraints are checked in [onMeasure]; a mismatch falls back to a
     * normal foreground measurement rather than displaying stale geometry.
     */
    fun submitPrecomputedLayout(precomputed: AndroidPrecomputedParagraph): Boolean {
        if (precomputed.profile != clreqProfile) return false
        val result = precomputed.result
        val expected = content.layoutInput(
            maxWidth = result.input.constraints.maxWidth,
            maxHeight = result.input.constraints.maxHeight,
            maxLines = maxLines,
        )
        if (expected != result.input) return false
        submittedPrecomputedLayout = precomputed
        requestLayout()
        // A same-size content revision can keep identical measured bounds. Explicit invalidation
        // prevents Android from replaying the old hardware display list after the new layout is
        // accepted.
        invalidate()
        return true
    }

    fun setSelection(start: Int, end: Int): Boolean =
        selectionController.setSelection(start, end, showToolbar = false)

    fun selectAll(): Boolean = selectionController.selectAll(showToolbar = false)

    fun clearSelection() = selectionController.clearSelection()

    fun copySelection(): Boolean = selectionController.copySelection()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val horizontalPadding = paddingLeft + paddingRight
        val layoutWidth = when (widthMode) {
            MeasureSpec.EXACTLY, MeasureSpec.AT_MOST -> (widthSize - horizontalPadding).coerceAtLeast(1).toFloat()
            else -> DEFAULT_UNBOUNDED_WIDTH
        }
        val input = content.layoutInput(layoutWidth, Float.POSITIVE_INFINITY, maxLines)
        val snapshot = obtainLayout(input)

        val lineHeight = snapshot.result.debug.lineSpacingDecision?.resolvedHeight
            ?: snapshot.result.lines.firstOrNull()?.let { it.bottom - it.top }
            ?: content.textStyle.fontSize * EMPTY_PARAGRAPH_LINE_HEIGHT_FACTOR
        val desiredWidth = ceil(snapshot.result.size.width).toInt() + horizontalPadding
        val desiredHeight = ceil(max(snapshot.result.size.height, lineHeight * minLines)).toInt() +
            paddingTop + paddingBottom
        val measuredWidth = resolveSizeAndState(max(desiredWidth, suggestedMinimumWidth), widthMeasureSpec, 0)
        val measuredHeight = resolveSizeAndState(max(desiredHeight, suggestedMinimumHeight), heightMeasureSpec, 0)
        setMeasuredDimension(measuredWidth, measuredHeight)
        updateLegalPaintBounds(snapshot)
        measureInlineChildren()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val positions = layoutSnapshot?.replayIndex?.positionedClusters
            ?.associateBy { it.range }
            .orEmpty()
        for (child in inlineChildren) {
            val span = content.inlineObjects.getOrNull(child.spanIndex)
            val positioned = span?.let { positions[it.range] }
            if (span == null || positioned == null) {
                child.view.visibility = INVISIBLE
                continue
            }
            child.view.visibility = VISIBLE
            val childLeft = floor(toDrawX(positioned.drawX)).toInt()
            val childTop = floor(toDrawY(positioned.baseline - span.ascent)).toInt()
            child.view.layout(
                childLeft,
                childTop,
                childLeft + child.view.measuredWidth,
                childTop + child.view.measuredHeight,
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val snapshot = layoutSnapshot ?: return
        val renderer = renderer ?: return
        val save = canvas.save()
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        if (overflow == CjkTextOverflow.Clip) {
            canvas.clipRect(clipLeft, clipTop, clipRight, clipBottom)
        }
        renderer.draw(
            canvas = canvas,
            result = snapshot.result,
            replayIndex = snapshot.replayIndex,
            color = currentTextColor(),
            colorSpans = content.colorSpans,
            selectionBoxes = selectionController.boxes,
            selectionColor = selectionController.range?.let { selectionColor },
        )
        canvas.restoreToCount(save)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean =
        selectionController.onTouchEvent(event) || super.onTouchEvent(event)

    override fun onKeyShortcut(keyCode: Int, event: KeyEvent): Boolean {
        if (event.hasModifiers(KeyEvent.META_CTRL_ON)) {
            val handled = when (keyCode) {
                KeyEvent.KEYCODE_A -> textIsSelectable && selectAll()
                KeyEvent.KEYCODE_C -> copySelection()
                else -> false
            }
            if (handled) return true
        }
        return super.onKeyShortcut(keyCode, event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        if (textColors?.isStateful == true) invalidate()
    }

    override fun getBaseline(): Int =
        layoutSnapshot?.result?.lines?.firstOrNull()?.baseline?.let {
            ceil(it).toInt() + paddingTop
        } ?: super.getBaseline()

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        accessibilityDelegate.populateHostNode(info)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean =
        accessibilityDelegate.performHostAction(action, arguments) ||
            super.performAccessibilityAction(action, arguments)

    override fun addExtraDataToAccessibilityNodeInfo(
        info: AccessibilityNodeInfo,
        extraDataKey: String,
        arguments: Bundle?,
    ) {
        super.addExtraDataToAccessibilityNodeInfo(info, extraDataKey, arguments)
        accessibilityDelegate.addExtraData(info, extraDataKey, arguments)
    }

    override fun onDetachedFromWindow() {
        selectionController.dispose()
        renderer?.close()
        renderer = null
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (renderer == null) renderer = AndroidParagraphRenderer(typefaceResolver)
    }

    override fun onRtlPropertiesChanged(layoutDirection: Int) {
        super.onRtlPropertiesChanged(layoutDirection)
        accessibilityDelegate.invalidateRoot()
    }

    internal fun toContentX(viewX: Float): Float = viewX - paddingLeft + scrollX
    internal fun toContentY(viewY: Float): Float = viewY - paddingTop + scrollY

    /** Content → canvas/child-layout coordinates. The framework applies the scroll translation. */
    internal fun toDrawX(contentX: Float): Float = contentX + paddingLeft
    internal fun toDrawY(contentY: Float): Float = contentY + paddingTop

    /** Content → scroll-adjusted visible position, for touch comparison and reported geometry. */
    internal fun toVisibleX(contentX: Float): Float = contentX + paddingLeft - scrollX
    internal fun toVisibleY(contentY: Float): Float = contentY + paddingTop - scrollY

    internal fun linkAt(viewX: Float, viewY: Float): LinkHit? {
        val snapshot = layoutSnapshot ?: return null
        val x = toContentX(viewX)
        val y = toContentY(viewY)
        return content.richTextSpans.asSequence()
            .mapNotNull { span ->
                val link = span.role as? RichTextRole.Link ?: return@mapNotNull null
                val hit = snapshot.replayIndex.richTextSegments.any { segment ->
                    segment.span.range == span.range &&
                        x >= segment.left && x <= segment.right &&
                        y >= segment.top && y <= segment.bottom
                }
                if (hit) LinkHit(link.target, span.range) else null
            }
            .firstOrNull()
    }

    internal fun activateLink(link: LinkHit): Boolean {
        if (onLinkClickListener?.onLinkClick(this, link.target, link.range.start, link.range.end) == true) {
            return true
        }
        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(link.target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
    }

    internal fun onSelectionGeometryChanged() {
        invalidate()
        selectionController.updateHandles()
        accessibilityDelegate.invalidateRoot()
        if (!canSendAccessibilityEvents) return
        @Suppress("DEPRECATION")
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED)
        event.className = android.widget.TextView::class.java.name
        event.packageName = context.packageName
        event.itemCount = content.content.text.length
        event.fromIndex = selection?.start ?: -1
        event.toIndex = selection?.end ?: -1
        sendAccessibilityEventUnchecked(event)
    }

    internal fun sendAccessibilityEventIfEnabled(eventType: Int) {
        if (canSendAccessibilityEvents) sendAccessibilityEvent(eventType)
    }

    internal fun links(): List<LinkHit> = content.richTextSpans.mapNotNull { span ->
        (span.role as? RichTextRole.Link)?.let { LinkHit(it.target, span.range) }
    }

    private fun obtainLayout(input: LayoutInput): LayoutSnapshot {
        layoutSnapshot?.takeIf { it.result.input == input }?.let { return it }
        val result = submittedPrecomputedLayout?.result?.takeIf { it.input == input }
            ?: measurer.measure(input)
        if (submittedPrecomputedLayout != null && submittedPrecomputedLayout?.result !== result) {
            submittedPrecomputedLayout = null
        }
        return LayoutSnapshot(result, result.toReplayIndex(content.richTextSpans)).also { snapshot ->
            layoutSnapshot = snapshot
            renderer?.invalidateGeometry()
            selectionController.onContentOrLayoutChanged()
            accessibilityDelegate.invalidateRoot()
            onTextLayout?.invoke(result)
        }
    }

    private fun clearLayout() {
        layoutSnapshot = null
        renderer?.invalidateGeometry()
        accessibilityDelegate.invalidateRoot()
        // requestLayout alone does not guarantee a View's display list is re-recorded when the
        // replacement paragraph resolves to the same dimensions.
        invalidate()
    }

    private fun rebuildMeasurer() {
        measurer = AndroidParagraphMeasurer(clreqProfile, measurementSession, typefaceResolver)
        submittedPrecomputedLayout = null
        clearLayout()
        requestLayout()
    }

    private fun updateLegalPaintBounds(snapshot: LayoutSnapshot) {
        val viewportWidth = (measuredWidth - paddingLeft - paddingRight).coerceAtLeast(0).toFloat()
        val viewportHeight = (measuredHeight - paddingTop - paddingBottom).coerceAtLeast(0).toFloat()
        val positions = snapshot.replayIndex.positionedClusters
        val overhang = snapshot.result.visiblePaintOverhang(viewportWidth, viewportHeight, positions)
        val legalRight = snapshot.result.lines.maxOfOrNull { line ->
            val punctuation = line.legalHangingPunctuationClipEdge(viewportWidth)
            val hyphen = minOf(viewportWidth, line.indent + line.visualWidth) + line.hyphenAdvance
            maxOf(viewportWidth, punctuation, hyphen)
        } ?: viewportWidth
        val legalLeft = positions.minOfOrNull { it.drawX } ?: 0f
        clipLeft = minOf(0f, legalLeft, -overhang.left)
        clipTop = -overhang.top
        clipRight = maxOf(viewportWidth, legalRight, viewportWidth + overhang.right)
        clipBottom = viewportHeight + overhang.bottom
    }

    private fun measureInlineChildren() {
        inlineChildren.forEach { child ->
            val span = content.inlineObjects[child.spanIndex]
            child.view.measure(
                MeasureSpec.makeMeasureSpec(ceil(span.advance).toInt().coerceAtLeast(1), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(
                    ceil(span.ascent + span.descent).toInt().coerceAtLeast(1),
                    MeasureSpec.EXACTLY,
                ),
            )
        }
    }

    /** Children are inline objects managed by [CjkInlineViewAdapter]; hosts may not add views. */
    override fun addView(child: View, index: Int, params: LayoutParams?) {
        check(mutatingInlineChildren) {
            "CjkTextView children are managed by CjkInlineViewAdapter; addView is not supported"
        }
        super.addView(child, index, params)
    }

    override fun removeView(view: View) {
        check(mutatingInlineChildren) {
            "CjkTextView children are managed by CjkInlineViewAdapter; removeView is not supported"
        }
        super.removeView(view)
    }

    private var mutatingInlineChildren = false

    private inline fun mutateInlineChildren(block: () -> Unit) {
        mutatingInlineChildren = true
        try {
            block()
        } finally {
            mutatingInlineChildren = false
        }
    }

    private fun syncInlineChildren() {
        val adapter = inlineViewAdapter ?: run {
            recycleInlineChildren()
            return
        }
        val oldById = inlineChildren.associateBy { it.id }.toMutableMap()
        val ids = HashSet<Any>()
        mutateInlineChildren {
            val updated = content.inlineObjects.mapIndexed { index, span ->
                val id = adapter.getItemId(content, span)
                require(ids.add(id)) { "CjkInlineViewAdapter item ids must be unique: $id" }
                val existing = oldById.remove(id)
                val child = existing?.view ?: adapter.createView(this, content, span).also(::addView)
                adapter.bindView(child, content, span)
                InlineChild(id, index, child)
            }
            oldById.values.forEach { child ->
                adapter.recycleView(child.view)
                removeView(child.view)
            }
            inlineChildren = updated
        }
    }

    private fun recycleInlineChildren() {
        val adapter = inlineViewAdapter
        mutateInlineChildren {
            inlineChildren.forEach { child ->
                adapter?.recycleView(child.view)
                removeView(child.view)
            }
        }
        inlineChildren = emptyList()
    }

    private fun currentTextColor(): Int =
        textColors?.getColorForState(drawableState, textColors?.defaultColor ?: content.textColor)
            ?: content.textColor

    private fun notifyTextChanged(oldText: String, newText: String) {
        if (oldText == newText || !canSendAccessibilityEvents) return
        @Suppress("DEPRECATION")
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
        event.className = android.widget.TextView::class.java.name
        event.packageName = context.packageName
        event.beforeText = oldText
        event.text.add(newText)
        event.fromIndex = 0
        event.removedCount = oldText.length
        event.addedCount = newText.length
        sendAccessibilityEventUnchecked(event)
    }

    internal val canSendAccessibilityEvents: Boolean
        get() = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
            .let { it as? AccessibilityManager }
            ?.isEnabled == true && isAttachedToWindow

    private fun readAttributes(attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) {
        if (attrs == null) return
        val values = context.obtainStyledAttributes(attrs, R.styleable.CjkTextView, defStyleAttr, defStyleRes)
        try {
            maxLines = values.getInt(R.styleable.CjkTextView_android_maxLines, maxLines)
                .coerceAtLeast(1)
            minLines = values.getInt(R.styleable.CjkTextView_android_minLines, minLines)
                .coerceAtLeast(1)
            textIsSelectable = values.getBoolean(
                R.styleable.CjkTextView_android_textIsSelectable,
                textIsSelectable,
            )
            overflow = when (values.getInt(R.styleable.CjkTextView_cjkOverflow, 0)) {
                1 -> CjkTextOverflow.Visible
                else -> CjkTextOverflow.Clip
            }
            clreqProfile = when (values.getInt(R.styleable.CjkTextView_cjkProfile, 0)) {
                1 -> ClreqProfile.TaiwanHorizontal
                2 -> ClreqProfile.HongKongHorizontal
                else -> ClreqProfile.MainlandHorizontal
            }
            val current = content
            val text = values.getText(R.styleable.CjkTextView_android_text)?.toString()
                ?: current.content.text
            val fontSize = values.getDimension(
                R.styleable.CjkTextView_android_textSize,
                current.textStyle.fontSize,
            )
            val lineHeight = if (values.hasValue(R.styleable.CjkTextView_android_lineHeight)) {
                values.getDimension(R.styleable.CjkTextView_android_lineHeight, 0f)
            } else {
                current.paragraphStyle.lineHeight
            }
            textColors = values.getColorStateList(R.styleable.CjkTextView_android_textColor)
            content = current.copy(
                content = current.content.copy(text = text),
                textStyle = current.textStyle.copy(fontSize = fontSize),
                paragraphStyle = current.paragraphStyle.copy(lineHeight = lineHeight),
            )
        } finally {
            values.recycle()
        }
    }

    private fun resolveThemeColor(attribute: Int, fallback: Int): Int {
        val value = TypedValue()
        return if (context.theme.resolveAttribute(attribute, value, true)) {
            if (value.resourceId != 0) {
                runCatching { context.getColor(value.resourceId) }.getOrDefault(fallback)
            } else value.data
        } else fallback
    }

    private fun CjkTextContent.hasSameLayoutContract(
        other: CjkTextContent,
        maxLines: Int,
    ): Boolean = layoutInput(1f, Float.POSITIVE_INFINITY, maxLines) ==
        other.layoutInput(1f, Float.POSITIVE_INFINITY, maxLines)

    private companion object {
        const val DEFAULT_UNBOUNDED_WIDTH = 65_536f

        /** Last-resort minLines row height when the engine reports no lines and no spacing decision. */
        const val EMPTY_PARAGRAPH_LINE_HEIGHT_FACTOR = 1.5f
    }
}
