package org.tiqian.android.view

import android.app.Instrumentation
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.ActionMode
import android.view.InputDevice
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.Magnifier
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import org.tiqian.android.rendering.AndroidParagraphMeasurer
import org.tiqian.android.rendering.AndroidParagraphMeasurementSession
import org.tiqian.clreq.ClreqProfile
import org.tiqian.core.InlineObjectSpan
import org.tiqian.core.RichTextRole
import org.tiqian.core.RichTextSpan
import org.tiqian.core.TextRange
import org.tiqian.core.TextStyle
import org.tiqian.core.cursorRect
import org.tiqian.core.getBoundingBoxes
import org.tiqian.core.selectionWordRangeForPosition
import org.tiqian.font.FontRole
import org.tiqian.shaping.ShapingInput
import org.tiqian.shaping.android.AndroidTypefaceResolver
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal fun ctrlKey(keyCode: Int): KeyEvent {
    val now = SystemClock.uptimeMillis()
    return KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, KeyEvent.META_CTRL_ON)
}


// The framework translates the canvas by -scroll before onDraw/dispatchDraw run.
internal fun scrollContractInkTop(view: CjkTextView): Int {
    val bitmap = Bitmap.createBitmap(view.width, view.height + 80, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.translate(-view.scrollX.toFloat(), -view.scrollY.toFloat())
    view.draw(canvas)
    for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
        if (Color.alpha(bitmap.getPixel(x, y)) > 0) return y
    }
    return -1
}

@Suppress("DEPRECATION")
internal fun characterLocations(extras: Bundle): Array<out android.os.Parcelable>? =
    if (Build.VERSION.SDK_INT >= 33) {
        extras.getParcelableArray(
            AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY,
            RectF::class.java,
        )
    } else {
        extras.getParcelableArray(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY)
    }

internal fun launchView(text: String, block: (CjkTextView) -> Unit) =
    launchView(CjkTextContent(text, TextStyle(fontSize = 32f)), block)

internal fun launchView(content: CjkTextContent, block: (CjkTextView) -> Unit) {
    ActivityScenario.launch(CjkTextViewTestActivity::class.java).use { scenario ->
        scenario.onActivity { activity ->
            val view = CjkTextView(activity).apply {
                setPadding(10, 8, 10, 8)
                this.content = content
                if (content.inlineObjects.isNotEmpty()) {
                    inlineViewAdapter = object : CjkInlineViewAdapter {
                        override fun createView(
                            parent: android.view.ViewGroup,
                            content: CjkTextContent,
                            span: InlineObjectSpan,
                        ): View = View(parent.context).apply { setBackgroundColor(Color.MAGENTA) }
                    }
                }
            }
            activity.setContentView(FrameLayout(activity).apply { addView(view) })
            measureAndLayout(view, 300)
            block(view)
        }
    }
}

internal fun measureAndLayout(view: CjkTextView, width: Int) {
    view.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(1_000, View.MeasureSpec.AT_MOST),
    )
    view.layout(0, 0, view.measuredWidth, view.measuredHeight)
}

internal fun measureAndLayout(view: TextView, width: Int) {
    view.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(1_000, View.MeasureSpec.AT_MOST),
    )
    view.layout(0, 0, view.measuredWidth, view.measuredHeight)
}

internal fun measureAndLayout(root: FrameLayout, width: Int) {
    root.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(1_000, View.MeasureSpec.AT_MOST),
    )
    root.layout(0, 0, root.measuredWidth, root.measuredHeight)
}

internal fun visibleCenter(view: CjkTextView, range: TextRange): Pair<Float, Float> {
    val box = assertNotNull(view.layoutResult?.getBoundingBoxes(range)?.firstOrNull())
    return view.toVisibleX((box.left + box.right) / 2f) to
        view.toVisibleY((box.top + box.bottom) / 2f)
}

internal fun nativeVisibleCenter(view: TextView, offset: Int): Pair<Float, Float> {
    val layout = assertNotNull(view.layout)
    val line = layout.getLineForOffset(offset)
    val left = layout.getPrimaryHorizontal(offset)
    val right = layout.getPrimaryHorizontal(offset + 1)
    return (left + right) / 2f to
        (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f
}

internal fun runSiblingNativeSelectionScenario(): SiblingSelectionOutcome {
    lateinit var first: TextView
    lateinit var second: TextView
    lateinit var firstPoint: Pair<Float, Float>
    lateinit var secondPoint: Pair<Float, Float>
    lateinit var outcome: SiblingSelectionOutcome

    ActivityScenario.launch(CjkTextViewTestActivity::class.java).use { scenario ->
        scenario.onActivity { activity ->
            first = TextView(activity).apply {
                text = "alpha first"
                textSize = 42f
                setTextIsSelectable(true)
            }
            second = TextView(activity).apply {
                text = "beta second"
                textSize = 42f
                setTextIsSelectable(true)
            }
            val root = FrameLayout(activity)
            root.addView(
                first,
                FrameLayout.LayoutParams(360, FrameLayout.LayoutParams.WRAP_CONTENT),
            )
            root.addView(
                second,
                FrameLayout.LayoutParams(360, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = SIBLING_TOP_MARGIN
                },
            )
            activity.setContentView(root)
            measureAndLayout(root, 360)
            firstPoint = nativeVisibleCenter(first, 0)
            secondPoint = nativeVisibleCenter(second, 0)
        }

        completeWindowLongPress(scenario, first, firstPoint)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        scenario.onActivity {
            assertNotNull(
                selectionRange(first),
                "native TextView must establish a selection before the sibling gesture",
            )
            assertTrue(first.isFocused, "the first native TextView must own focus initially")
        }

        completeWindowLongPress(scenario, second, secondPoint)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        scenario.onActivity {
            outcome = SiblingSelectionOutcome(
                firstSelection = selectionRange(first),
                secondSelection = selectionRange(second),
                firstFocused = first.isFocused,
                secondFocused = second.isFocused,
            )
        }
    }
    return outcome
}

internal fun runSiblingCjkSelectionScenario(): SiblingSelectionOutcome {
    lateinit var first: CjkTextView
    lateinit var second: CjkTextView
    lateinit var firstPoint: Pair<Float, Float>
    lateinit var secondPoint: Pair<Float, Float>
    lateinit var outcome: SiblingSelectionOutcome
    var firstSelectionColorPixelsAfterFirst = 0

    ActivityScenario.launch(CjkTextViewTestActivity::class.java).use { scenario ->
        scenario.onActivity { activity ->
            first = CjkTextView(activity).apply {
                content = CjkTextContent("alpha first", TextStyle(fontSize = 42f))
                selectionColor = SELECTION_TEST_COLOR
            }
            second = CjkTextView(activity).apply {
                content = CjkTextContent("beta second", TextStyle(fontSize = 42f))
            }
            val root = FrameLayout(activity)
            root.addView(
                first,
                FrameLayout.LayoutParams(360, FrameLayout.LayoutParams.WRAP_CONTENT),
            )
            root.addView(
                second,
                FrameLayout.LayoutParams(360, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = SIBLING_TOP_MARGIN
                },
            )
            activity.setContentView(root)
            measureAndLayout(root, 360)
            firstPoint = visibleCenter(first, TextRange(0, 1))
            secondPoint = visibleCenter(second, TextRange(0, 1))
        }

        completeWindowLongPress(scenario, first, firstPoint)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        scenario.onActivity {
            assertNotNull(
                first.selection,
                "the first CjkTextView must establish a selection before the sibling gesture",
            )
            assertTrue(first.isFocused, "the first CjkTextView must own focus initially")
            assertTrue(first.selectionHandlesShowing, "the first CjkTextView must show handles initially")
            firstSelectionColorPixelsAfterFirst = selectionColorPixelCount(first)
        }

        completeWindowLongPress(scenario, second, secondPoint)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        scenario.onActivity {
            outcome = SiblingSelectionOutcome(
                firstSelection = first.selection,
                secondSelection = second.selection,
                firstFocused = first.isFocused,
                secondFocused = second.isFocused,
                firstHandlesShowing = first.selectionHandlesShowing,
                secondHandlesShowing = second.selectionHandlesShowing,
                firstSelectionColorPixelsAfterFirst = firstSelectionColorPixelsAfterFirst,
                firstSelectionColorPixelsAfterSecond = selectionColorPixelCount(first),
            )
        }
    }
    return outcome
}

internal fun selectionColorPixelCount(view: CjkTextView): Int {
    val bitmap = Bitmap.createBitmap(
        view.width.coerceAtLeast(1),
        view.height.coerceAtLeast(1),
        Bitmap.Config.ARGB_8888,
    )
    view.draw(Canvas(bitmap))
    var count = 0
    for (y in 0 until bitmap.height) {
        for (x in 0 until bitmap.width) {
            if (bitmap.getPixel(x, y) == SELECTION_TEST_COLOR) count++
        }
    }
    bitmap.recycle()
    return count
}

internal fun selectionRange(view: TextView): TextRange? {
    val start = view.selectionStart
    val end = view.selectionEnd
    return if (start >= 0 && end > start) TextRange(start, end) else null
}

internal fun localizedContext(base: Context, locale: Locale): Context = base.createConfigurationContext(
    Configuration(base.resources.configuration).apply {
        if (Build.VERSION.SDK_INT >= 24) {
            setLocale(locale)
        } else {
            @Suppress("DEPRECATION")
            this.locale = locale
        }
    },
)

internal fun completeLongPress(
    scenario: ActivityScenario<CjkTextViewTestActivity>,
    view: View,
    point: Pair<Float, Float>,
) {
    val downTime = SystemClock.uptimeMillis()
    scenario.onActivity {
        assertTrue(view.dispatchTouchEvent(pointerEvent(downTime, downTime, MotionEvent.ACTION_DOWN, point)))
    }
    SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 150L)
    scenario.onActivity {
        assertTrue(
            view.dispatchTouchEvent(
                pointerEvent(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, point),
            ),
        )
    }
}

internal fun completeNativeLongPress(
    scenario: ActivityScenario<CjkTextViewTestActivity>,
    view: TextView,
    point: Pair<Float, Float>,
) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val location = IntArray(2)
    scenario.onActivity {
        assertTrue(view.requestFocus(), "native TextView oracle must be focusable")
        view.getLocationOnScreen(location)
        assertTrue(view.isShown && view.isAttachedToWindow)
    }
    val x = location[0] + point.first
    val y = location[1] + point.second
    val downTime = SystemClock.uptimeMillis()
    sendPointerSync(instrumentation, downTime, downTime, MotionEvent.ACTION_DOWN, x, y)
    SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 150L)
    sendPointerSync(instrumentation, downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y)
    instrumentation.waitForIdleSync()
}

internal fun completeWindowLongPress(
    scenario: ActivityScenario<CjkTextViewTestActivity>,
    view: View,
    point: Pair<Float, Float>,
) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val location = IntArray(2)
    scenario.onActivity {
        view.getLocationOnScreen(location)
        assertTrue(view.isShown && view.isAttachedToWindow)
    }
    val x = location[0] + point.first
    val y = location[1] + point.second
    val downTime = SystemClock.uptimeMillis()
    sendPointerSync(instrumentation, downTime, downTime, MotionEvent.ACTION_DOWN, x, y)
    SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 150L)
    sendPointerSync(instrumentation, downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y)
    instrumentation.waitForIdleSync()
}

internal fun awaitActionMode(callback: RecordingSelectionActionModeCallback): ActionMode {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    repeat(50) {
        callback.mode?.let { return it }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(100L)
    }
    return assertNotNull(callback.mode, "selection ActionMode did not start before timeout")
}

internal fun menuItems(menu: Menu): List<ActionModeMenuItem> =
    (0 until menu.size()).map { index ->
        val item = menu.getItem(index)
        ActionModeMenuItem(
            id = item.itemId,
            title = item.title?.toString(),
            action = item.intent?.action,
            order = item.order,
        )
    }

internal fun assertDefaultSelectionItems(items: List<ActionModeMenuItem>, owner: String) {
    val ids = items.map { it.id }.toSet()
    assertTrue(android.R.id.copy in ids, "$owner must expose Copy: $items")
    assertTrue(android.R.id.selectAll in ids, "$owner must expose Select all: $items")
}

internal fun List<ActionModeMenuItem>.defaultItems(): List<ActionModeMenuItem> =
    filter {
        it.id == android.R.id.copy ||
            it.id == android.R.id.selectAll ||
            it.id == android.R.id.shareText
    }

internal fun List<ActionModeMenuItem>.processTextItems(): List<Pair<String?, Int>> =
    filter { it.action == Intent.ACTION_PROCESS_TEXT }.map { it.title to it.order }

internal fun List<ActionModeMenuItem>.titleOf(id: Int): String? =
    firstOrNull { it.id == id }?.title

internal fun expectedDefaultLabels(locale: Locale, includeShare: Boolean): Map<Int, String?> = when (locale.language) {
    "fr" -> mapOf(
        android.R.id.copy to "Copier",
        android.R.id.selectAll to "Tout sélectionner",
    ) + if (includeShare) mapOf(android.R.id.shareText to "Partager") else emptyMap()

    "zh" -> mapOf(
        android.R.id.copy to "复制",
        android.R.id.selectAll to "全选",
    ) + if (includeShare) mapOf(android.R.id.shareText to "分享") else emptyMap()

    else -> error("unexpected test locale: $locale")
}

/**
 * ActionMode has no public getter on View. Read the controller's live mode only to inspect the
 * menu that a user can see; the assertion itself remains on the public ActionMode/Menu API.
 */
internal fun actionModeForTesting(view: CjkTextView): ActionMode? {
    val controller = readPrivateField(view, "selectionController")
    val owners = listOfNotNull(
        readPrivateField(view, "selectionActionMode"),
        controller,
        controller?.let { readPrivateField(it, "selectionActionMode") },
    )
    owners.forEach { owner ->
        readPrivateField(owner, "actionMode")?.let { return it as? ActionMode }
    }
    return null
}

internal fun readPrivateField(target: Any, name: String): Any? {
    var type: Class<*>? = target.javaClass
    while (type != null) {
        val currentType = type
        val value = runCatching {
            currentType.getDeclaredField(name).apply { isAccessible = true }.get(target)
        }.getOrNull()
        if (value != null) return value
        type = currentType.superclass
    }
    return null
}

internal fun pointerEvent(
    downTime: Long,
    eventTime: Long,
    action: Int,
    point: Pair<Float, Float>,
): MotionEvent = pointerEvent(downTime, eventTime, action, point.first, point.second)

internal fun pointerEvent(
    downTime: Long,
    eventTime: Long,
    action: Int,
    x: Float,
    y: Float,
): MotionEvent = MotionEvent.obtain(downTime, eventTime, action, x, y, 0).apply {
    source = InputDevice.SOURCE_TOUCHSCREEN
}

internal fun sendPointerSync(
    instrumentation: Instrumentation,
    downTime: Long,
    eventTime: Long,
    action: Int,
    x: Float,
    y: Float,
) {
    val event = pointerEvent(downTime, eventTime, action, x, y)
    try {
        if (Build.VERSION.SDK_INT >= 36) {
            assertTrue(
                instrumentation.uiAutomation.injectInputEvent(event, true),
                "UiAutomation must inject the pointer into the popup window",
            )
        } else {
            instrumentation.sendPointerSync(event)
        }
    } finally {
        event.recycle()
    }
}

internal const val HOST_ACTION_ID = 0x544951

// Keep the second target outside the first selection's floating toolbar/handle windows; the test
// must exercise sibling focus transfer, not send its second DOWN into a popup.
internal const val SIBLING_TOP_MARGIN = 600
internal const val SELECTION_TEST_COLOR = 0xFFFF00FF.toInt()

internal data class SiblingSelectionOutcome(
    val firstSelection: TextRange?,
    val secondSelection: TextRange?,
    val firstFocused: Boolean,
    val secondFocused: Boolean,
    val firstHandlesShowing: Boolean = false,
    val secondHandlesShowing: Boolean = false,
    val firstSelectionColorPixelsAfterFirst: Int = 0,
    val firstSelectionColorPixelsAfterSecond: Int = 0,
)

internal data class ActionModeMenuItem(
    val id: Int,
    val title: String?,
    val action: String?,
    val order: Int,
)

internal fun snapshotMenuItems(menu: Menu): List<ActionModeMenuItem> =
    (0 until menu.size()).map { index ->
        val item = menu.getItem(index)
        ActionModeMenuItem(
            id = item.itemId,
            title = item.title?.toString(),
            action = item.intent?.action,
            order = item.order,
        )
    }

internal class RecordingSelectionActionModeCallback(
    private val allowCreate: Boolean = true,
    private val addCustomItem: Boolean = false,
    private val customItemId: Int = 0,
) : ActionMode.Callback {
    var createCalls: Int = 0
        private set
    var prepareCalls: Int = 0
        private set
    var destroyCalls: Int = 0
        private set
    var customActionClicks: Int = 0
        private set
    @Volatile
    var mode: ActionMode? = null
        private set
    var createItems: List<ActionModeMenuItem> = emptyList()
        private set
    var preparedItems: List<ActionModeMenuItem> = emptyList()
        private set
    val clickedIds: MutableList<Int> = mutableListOf()
    val events: MutableList<String> = mutableListOf()

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        createCalls++
        events += "create"
        this.mode = mode
        createItems = snapshotMenuItems(menu)
        if (addCustomItem) {
            menu.add(Menu.NONE, customItemId, 1_000, "宿主操作")
        }
        return allowCreate
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        prepareCalls++
        events += "prepare"
        preparedItems = snapshotMenuItems(menu)
        return true
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        events += "action:${item.itemId}"
        clickedIds += item.itemId
        if (item.itemId == customItemId && customItemId != 0) {
            customActionClicks++
            return true
        }
        // Returning false is the TextView contract: the framework/front-end keeps ownership of
        // Copy, Select all, Share and PROCESS_TEXT actions.
        return false
    }

    override fun onDestroyActionMode(mode: ActionMode) {
        destroyCalls++
        events += "destroy"
    }

    fun finalItems(): List<ActionModeMenuItem> =
        preparedItems.ifEmpty { createItems }
}

internal class RecordingTypefaceResolver : AndroidTypefaceResolver {
    var shapingCalls = 0
    var roleCalls = 0
    val requestedFamilies = mutableListOf<List<String>>()

    override fun resolve(input: ShapingInput): Typeface {
        shapingCalls++
        requestedFamilies += input.style.fontFamilies
        return Typeface.MONOSPACE
    }

    override fun resolve(
        role: FontRole,
        fontFamilies: List<String>,
        fontWeight: Int,
        italic: Boolean,
    ): Typeface {
        roleCalls++
        requestedFamilies += fontFamilies
        return Typeface.MONOSPACE
    }
}
