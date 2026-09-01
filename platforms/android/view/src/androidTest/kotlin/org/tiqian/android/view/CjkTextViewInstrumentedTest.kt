package org.tiqian.android.view

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import org.tiqian.core.getBoundingBoxes
import org.tiqian.font.FontRole
import org.tiqian.shaping.ShapingInput
import org.tiqian.shaping.android.AndroidTypefaceResolver
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class CjkTextViewInstrumentedTest {
    @Test
    fun viewMeasuresAndPaintsFromOneLayoutResult() {
        launchView("提椠的 Android View 前端会复用同一份排版结果。") { view ->
            assertNotNull(view.layoutResult)
            assertEquals(280f, view.layoutResult?.input?.constraints?.maxWidth)

            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))

            var painted = 0
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 0) painted++
            }
            assertTrue(painted > 100, "expected native Canvas glyph pixels, got $painted")
        }
    }

    @Test
    fun measurementSessionTypefaceResolverDrivesMeasureAndReplay() {
        val resolver = RecordingTypefaceResolver()
        val session = AndroidParagraphMeasurementSession(typefaceResolver = resolver)
        ActivityScenario.launch(CjkTextViewTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = CjkTextView(activity).apply {
                    setMeasurementSession(session)
                    content = CjkTextContent(
                        text = "宿主字体 Host font",
                        textStyle = TextStyle(
                            fontFamilies = listOf("host-reader-font"),
                            fontSize = 32f,
                        ),
                    )
                }
                activity.setContentView(view)
                measureAndLayout(view, 280)

                assertTrue(resolver.shapingCalls > 0)
                assertTrue(resolver.requestedFamilies.all { it == listOf("host-reader-font") })
                val roleCallsAfterMeasure = resolver.roleCalls
                view.draw(Canvas(Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)))
                assertTrue(resolver.roleCalls > roleCallsAfterMeasure)
            }
        }
    }

    @Test
    fun paintOnlyUpdateKeepsLayoutIdentityButFontUpdateInvalidatesIt() {
        launchView("甲乙丙丁") { view ->
            val first = assertNotNull(view.layoutResult)

            view.content = view.content.copy(textColor = Color.RED)
            assertSame(first, view.layoutResult)

            view.content = view.content.copy(
                textStyle = view.content.textStyle.copy(fontSize = view.content.textStyle.fontSize + 2f),
            )
            assertEquals(null, view.layoutResult)
            measureAndLayout(view, 280)
            assertTrue(first !== view.layoutResult)
        }
    }

    @Test
    fun precomputedLayoutRequiresExactProfileAndConstraints() {
        val content = CjkTextContent("简繁区域策略不能靠字形猜测", TextStyle(fontSize = 32f))
        val input = content.layoutInput(maxWidth = 280f)
        val mainland = AndroidParagraphMeasurer(ClreqProfile.MainlandHorizontal).precompute(input)
        val taiwan = AndroidParagraphMeasurer(ClreqProfile.TaiwanHorizontal).precompute(input)
        ActivityScenario.launch(CjkTextViewTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = CjkTextView(activity).apply { this.content = content }

                assertTrue(view.submitPrecomputedLayout(mainland))
                view.clreqProfile = ClreqProfile.TaiwanHorizontal
                assertFalse(view.submitPrecomputedLayout(mainland))
                assertTrue(view.submitPrecomputedLayout(taiwan))
                activity.setContentView(view)
                measureAndLayout(view, 280)
                assertSame(taiwan.result, view.layoutResult)
            }
        }
    }

    @Test
    fun accessibilityUsesSourceTextSelectionAndCharacterGeometry() {
        launchView("提椠原文") { view ->
            assertTrue(view.setSelection(0, 2))
            val node = view.createAccessibilityNodeInfo()
            assertEquals("提椠原文", node.text.toString())
            assertEquals(0, node.textSelectionStart)
            assertEquals(2, node.textSelectionEnd)
            assertTrue(
                node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_COPY },
                "non-empty source selection must publish copy",
            )

            if (Build.VERSION.SDK_INT >= 26) {
                val arguments = Bundle().apply {
                    putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 0)
                    putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, 2)
                }
                view.addExtraDataToAccessibilityNodeInfo(
                    node,
                    AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY,
                    arguments,
                )
                val locations = characterLocations(node.extras)
                assertEquals(2, locations?.size)
                assertTrue(locations?.all { it is RectF } == true)
            }
        }
    }

    @Test
    fun hostsCannotInjectChildrenPastTheInlineAdapter() {
        launchView("段落子树只属于行内对象") { view ->
            assertFailsWith<IllegalStateException> { view.addView(View(view.context)) }
        }
    }

    @Test
    fun linksAndInlineChildrenUseEngineOccupiedGeometry() {
        val source = "前链后\uFFFC"
        val linkRange = TextRange(1, 2)
        val objectRange = TextRange(3, 4)
        val content = CjkTextContent(
            content = org.tiqian.core.TiqianTextContent(source),
            textStyle = TextStyle(fontSize = 32f),
            richTextSpans = listOf(RichTextSpan(linkRange, RichTextRole.Link("tiqian://link"))),
            inlineObjects = listOf(InlineObjectSpan(objectRange, advance = 24f, ascent = 18f, descent = 6f)),
        )
        launchView(content) { view ->
            var target: String? = null
            view.onLinkClickListener = CjkLinkClickListener { _, value, _, _ ->
                target = value
                true
            }
            val link = view.layoutResult?.getBoundingBoxes(linkRange)?.firstOrNull()
            assertNotNull(link)
            val hit = view.linkAt(view.toVisibleX(link.left + 1f), view.toVisibleY(link.top + 1f))
            assertNotNull(hit)
            assertTrue(view.activateLink(hit))
            assertEquals("tiqian://link", target)

            assertEquals(1, view.childCount)
            val child = view.getChildAt(0)
            assertEquals(24, child.measuredWidth)
            assertEquals(24, child.measuredHeight)
            assertEquals(View.VISIBLE, child.visibility)
        }
    }

    @Test
    fun longPressUsesEngineWordBoundaryAndStartsTouchSelection() {
        lateinit var view: CjkTextView
        lateinit var point: Pair<Float, Float>
        ActivityScenario.launch(CjkTextViewTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                view = CjkTextView(activity).apply {
                    content = CjkTextContent("提椠原生选择", TextStyle(fontSize = 42f))
                }
                activity.setContentView(view)
                measureAndLayout(view, 360)
                val box = assertNotNull(view.layoutResult?.getBoundingBoxes(TextRange(0, 1))?.firstOrNull())
                point = view.toVisibleX((box.left + box.right) / 2f) to
                    view.toVisibleY((box.top + box.bottom) / 2f)
            }
            val (x, y) = point
            val downTime = SystemClock.uptimeMillis()
            scenario.onActivity {
                assertTrue(view.dispatchTouchEvent(pointerEvent(downTime, downTime, MotionEvent.ACTION_DOWN, x, y)))
            }
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 150L)
            scenario.onActivity {
                assertTrue(
                    view.dispatchTouchEvent(
                        pointerEvent(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y),
                    ),
                )
                assertEquals(TextRange(0, 1), view.selection)
                assertTrue(view.selectionHandlesShowing, "touch selection must float window-level handles")
            }
        }
    }

    @Test
    fun keyboardShortcutsSelectAllAndCopyUseEngineSelection() {
        launchView("硬键盘复制") { view ->
            assertTrue(view.requestFocus())
            assertTrue(view.dispatchKeyShortcutEvent(ctrlKey(KeyEvent.KEYCODE_A)))
            assertEquals(TextRange(0, 5), view.selection)
            assertTrue(view.dispatchKeyShortcutEvent(ctrlKey(KeyEvent.KEYCODE_C)))

            view.textIsSelectable = false
            assertFalse(view.dispatchKeyShortcutEvent(ctrlKey(KeyEvent.KEYCODE_A)))
        }
    }

    private fun ctrlKey(keyCode: Int): KeyEvent {
        val now = SystemClock.uptimeMillis()
        return KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, KeyEvent.META_CTRL_ON)
    }

    @Test
    fun scrollAppliesOnceAcrossDrawLayoutAndHitTesting() {
        val source = "滚动回归\uFFFC"
        val linkRange = TextRange(0, 2)
        val objectRange = TextRange(4, 5)
        val content = CjkTextContent(
            content = org.tiqian.core.TiqianTextContent(source),
            textStyle = TextStyle(fontSize = 32f),
            richTextSpans = listOf(RichTextSpan(linkRange, RichTextRole.Link("tiqian://scroll"))),
            inlineObjects = listOf(InlineObjectSpan(objectRange, advance = 24f, ascent = 18f, descent = 6f)),
        )
        launchView(content) { view ->
            val scrollY = -40
            val baseline = view.baseline
            val childTop = view.getChildAt(0).top
            val restingInkTop = scrollContractInkTop(view)

            view.scrollTo(0, scrollY)
            view.requestLayout()
            measureAndLayout(view, 300)

            assertEquals(baseline, view.baseline)
            assertEquals(childTop, view.getChildAt(0).top)
            assertEquals(restingInkTop - scrollY, scrollContractInkTop(view))

            val link = assertNotNull(view.layoutResult?.getBoundingBoxes(linkRange)?.firstOrNull())
            val hit = view.linkAt(
                link.left + 1f + view.paddingLeft,
                link.top + 1f + view.paddingTop - scrollY,
            )
            assertEquals(linkRange, assertNotNull(hit).range)
        }
    }

    // The framework translates the canvas by -scroll before onDraw/dispatchDraw run.
    private fun scrollContractInkTop(view: CjkTextView): Int {
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
    private fun characterLocations(extras: Bundle): Array<out android.os.Parcelable>? =
        if (Build.VERSION.SDK_INT >= 33) {
            extras.getParcelableArray(
                AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY,
                RectF::class.java,
            )
        } else {
            extras.getParcelableArray(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY)
        }

    private fun launchView(text: String, block: (CjkTextView) -> Unit) =
        launchView(CjkTextContent(text, TextStyle(fontSize = 32f)), block)

    private fun launchView(content: CjkTextContent, block: (CjkTextView) -> Unit) {
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

    private fun measureAndLayout(view: CjkTextView, width: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1_000, View.MeasureSpec.AT_MOST),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun pointerEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float,
    ): MotionEvent = MotionEvent.obtain(downTime, eventTime, action, x, y, 0).apply {
        source = InputDevice.SOURCE_TOUCHSCREEN
    }
}

private class RecordingTypefaceResolver : AndroidTypefaceResolver {
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
