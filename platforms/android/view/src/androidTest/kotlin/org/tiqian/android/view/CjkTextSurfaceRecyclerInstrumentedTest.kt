package org.tiqian.android.view

import android.os.SystemClock
import android.os.Build
import android.graphics.Rect
import android.view.ActionMode
import android.view.InputDevice
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import org.tiqian.core.TextRange
import org.tiqian.core.TextStyle
import org.tiqian.core.getBoundingBoxes
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class CjkTextSurfaceRecyclerInstrumentedTest {
    @Test
    fun rejectedRebindAndDocumentReplacementLeaveTheOldStateIntact() {
        ActivityScenario.launch(CjkTextViewTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val firstContent = CjkTextContent("甲乙丙丁", TextStyle(fontSize = 30f))
                val secondContent = CjkTextContent("戊己庚辛", TextStyle(fontSize = 30f))
                val originalDocument = CjkSelectionDocument(
                    listOf(
                        CjkSelectionDocumentFragment("first", firstContent.content.text),
                        CjkSelectionDocumentFragment("second", secondContent.content.text),
                    ),
                )
                val container = CjkTextSurface(activity).apply { document = originalDocument }
                val first = CjkTextView(activity).apply {
                    bindSelectionFragment("first", firstContent)
                }
                val second = CjkTextView(activity).apply {
                    bindSelectionFragment("second", secondContent)
                }
                container.addView(first)
                container.addView(second)
                activity.setContentView(container)
                container.measure(
                    View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(420, View.MeasureSpec.EXACTLY),
                )
                container.layout(0, 0, container.measuredWidth, container.measuredHeight)
                assertTrue(first.setSelection(1, 3))

                assertFailsWith<IllegalStateException> {
                    first.bindSelectionFragment("missing", firstContent)
                }
                assertFailsWith<IllegalArgumentException> {
                    first.bindSelectionFragment("second", secondContent)
                }
                assertFailsWith<IllegalArgumentException> {
                    container.document = CjkSelectionDocument(
                        listOf(
                            CjkSelectionDocumentFragment("first", "内容已漂移"),
                            CjkSelectionDocumentFragment("second", secondContent.content.text),
                        ),
                    )
                }

                assertSame(originalDocument, container.document)
                assertEquals("first", first.selectionDocumentKey)
                assertEquals(firstContent, first.content)
                assertEquals(TextRange(1, 3), first.selection)
                assertEquals("乙丙", container.selectedText)
                assertEquals("second", second.selectionDocumentKey)
            }
        }
    }

    @Test
    fun logicalSelectionSurvivesRealHolderRecyclingAndRestoresProjection() {
        lateinit var container: CjkTextSurface
        lateinit var recycler: RecyclerView
        lateinit var paragraphAdapter: ParagraphAdapter
        val selected = TextRange(0, 4)

        ActivityScenario.launch(CjkTextViewTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val contents = paragraphContents(48)
                paragraphAdapter = ParagraphAdapter(contents)
                recycler = RecyclerView(activity).apply {
                    layoutManager = LinearLayoutManager(activity)
                    adapter = paragraphAdapter
                    setItemViewCacheSize(0)
                }
                container = CjkTextSurface(activity).apply {
                    document = contents.selectionDocument()
                    addView(
                        recycler,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            420,
                        ),
                    )
                }
                activity.setContentView(container)
            }
            waitForIdle()

            scenario.onActivity {
                val first = adapterViewAt(recycler, 0)
                assertTrue(first.setSelection(selected.start, selected.end))
                assertEquals(contentsText(0).substring(selected.start, selected.end), container.selectedText)
                assertTrue(
                    container.selectionHandleBoundsOnScreen(CjkSelectionHandle.Start, Rect()),
                )
                assertTrue(
                    container.selectionHandleBoundsOnScreen(CjkSelectionHandle.End, Rect()),
                )
            }

            scenario.onActivity { recycler.scrollToPosition(40) }
            waitForIdle()
            scenario.onActivity {
                assertNull(recycler.findViewHolderForAdapterPosition(0))
                assertTrue(paragraphAdapter.recycledCount > 0, "the fixture must exercise RecyclerView recycling")
                assertEquals(contentsText(0).substring(selected.start, selected.end), container.selectedText)
                assertFalse(
                    container.selectionHandleBoundsOnScreen(CjkSelectionHandle.Start, Rect()),
                )
                assertFalse(
                    container.selectionHandleBoundsOnScreen(CjkSelectionHandle.End, Rect()),
                )
            }

            scenario.onActivity { recycler.scrollToPosition(0) }
            waitForIdle()
            scenario.onActivity {
                assertEquals(selected, adapterViewAt(recycler, 0).selection)
                assertEquals(contentsText(0).substring(selected.start, selected.end), container.selectedText)
                assertTrue(
                    container.selectionHandleBoundsOnScreen(CjkSelectionHandle.Start, Rect()),
                )
                assertTrue(
                    container.selectionHandleBoundsOnScreen(CjkSelectionHandle.End, Rect()),
                )
                assertTrue(
                    paragraphAdapter.reboundViewCount > 0,
                    "at least one holder must be rebound to a new stable key",
                )
            }
        }
    }

    @Test
    fun edgeDragUsesConsumedScrollKeepsBoundaryLoopAndReverses() {
        lateinit var container: CjkTextSurface
        lateinit var recycler: RecyclerView
        lateinit var paragraphAdapter: ParagraphAdapter
        val retained = mutableListOf<Any>()
        var released = 0
        val consumed = mutableListOf<Float>()
        var remainingForwardConsumptions = 2
        var zeroConsumptionCount = 0
        var viewportQueries = 0
        val actionMode = RecordingActionModeCallback()
        val start = FloatArray(2)
        val bottomEdge = FloatArray(2)
        val topEdge = FloatArray(2)

        ActivityScenario.launch(CjkTextViewTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val contents = paragraphContents(64)
                paragraphAdapter = ParagraphAdapter(contents)
                recycler = RecyclerView(activity).apply {
                    layoutManager = LinearLayoutManager(activity)
                    adapter = paragraphAdapter
                    setItemViewCacheSize(0)
                }
                container = CjkTextSurface(activity).apply {
                    document = contents.selectionDocument()
                    customSelectionActionModeCallback = actionMode
                    selectionAutoScrollEdgeSizeDp = 64f
                    selectionAutoScrollMaxVelocityDpPerSecond = 6_000f
                    selectionScrollHost = object : CjkSelectionScrollHost {
                        override fun scrollBy(deltaPx: Float): Float {
                            if (deltaPx > 0f && remainingForwardConsumptions == 0) {
                                zeroConsumptionCount++
                                consumed += 0f
                                return 0f
                            }
                            val before = recycler.computeVerticalScrollOffset()
                            recycler.scrollBy(0, deltaPx.roundToInt())
                            val actual = (recycler.computeVerticalScrollOffset() - before).toFloat()
                            consumed += actual
                            if (deltaPx > 0f && actual > 0f) remainingForwardConsumptions--
                            return actual
                        }

                        override fun viewportBoundsOnScreen(outBounds: Rect): Boolean {
                            viewportQueries++
                            return recycler.getGlobalVisibleRect(outBounds)
                        }
                    }
                    selectionRetentionHost = CjkSelectionRetentionHost { key ->
                        paragraphAdapter.retain(recycler, key) { released++ }
                            .also { retained += key }
                    }
                    addView(
                        recycler,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            420,
                        ),
                    )
                }
                activity.setContentView(container)
            }
            waitForIdle()

            scenario.onActivity {
                val first = adapterViewAt(recycler, 0)
                selectionCenterOnScreen(first, TextRange(0, 1), start)
                val location = IntArray(2).also(recycler::getLocationOnScreen)
                bottomEdge[0] = start[0]
                bottomEdge[1] = location[1] + recycler.height - 3f
                topEdge[0] = start[0]
                topEdge[1] = location[1] + 3f
            }

            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val downTime = SystemClock.uptimeMillis()
            sendPointer(instrumentation, downTime, downTime, MotionEvent.ACTION_DOWN, start)
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 120L)
            scenario.onActivity {
                assertEquals(listOf<Any>(0L), retained, "long press must retain the starting holder")
                assertTrue(container.hasSelection)
            }
            sendPointer(
                instrumentation,
                downTime,
                SystemClock.uptimeMillis(),
                MotionEvent.ACTION_MOVE,
                bottomEdge,
            )
            awaitCondition(
                scenario,
                2_000L,
                "bottom-edge drag did not consume forward scroll",
                diagnostic = {
                    "offset=${recycler.computeVerticalScrollOffset()}, consumed=$consumed, " +
                        "selected=${container.selectedText}, recycled=${paragraphAdapter.recycledCount}"
                },
            ) {
                recycler.computeVerticalScrollOffset() > 0 && consumed.any { it > 0f }
            }
            val forwardOffset = intArrayOf(0)
            scenario.onActivity {
                forwardOffset[0] = recycler.computeVerticalScrollOffset()
                assertTrue(forwardOffset[0] > 0, "bottom-edge drag must consume forward scroll")
                assertTrue(container.hasSelection)
                assertTrue(viewportQueries > 0, "auto-scroll must query the capability viewport")
                assertTrue(
                    requireNotNull(container.selectedText).length > contentsText(0).length,
                    "the moving endpoint must cross at least one logical fragment",
                )
                assertTrue(paragraphAdapter.recycledCount > 0)
            }
            awaitCondition(
                scenario,
                1_000L,
                "the host boundary was not observed",
                diagnostic = {
                    "offset=${recycler.computeVerticalScrollOffset()}, consumed=$consumed, " +
                        "remaining=$remainingForwardConsumptions, selected=${container.selectedText}"
                },
            ) {
                zeroConsumptionCount > 0
            }

            // Zero actual consumption stops the frame loop. A new pointer move at the opposite
            // edge must re-arm it, scroll backward and shrink the same logical selection.
            sendPointer(
                instrumentation,
                downTime,
                SystemClock.uptimeMillis(),
                MotionEvent.ACTION_MOVE,
                topEdge,
            )
            awaitCondition(scenario, 2_000L, "top-edge drag did not resume the retained loop") {
                recycler.computeVerticalScrollOffset() < forwardOffset[0] && consumed.any { it < 0f }
            }
            sendPointer(
                instrumentation,
                downTime,
                SystemClock.uptimeMillis(),
                MotionEvent.ACTION_UP,
                topEdge,
            )
            waitForIdle()

            scenario.onActivity {
                val reversedOffset = recycler.computeVerticalScrollOffset()
                assertTrue(reversedOffset < forwardOffset[0], "top-edge drag must reverse scrolling")
                assertTrue(consumed.any { it > 0f })
                assertTrue(consumed.any { it < 0f })
                assertEquals(listOf<Any>(0L), retained)
                assertEquals(1, released)
                assertTrue(container.hasSelection)
                assertEquals(1, actionMode.createdCount, "one gesture must own one floating ActionMode")
                assertNotNull(actionMode.mode)
                assertEquals(0, paragraphAdapter.childActionModeCreatedCount)
            }
        }
    }

    @Test
    fun handleCoordinatesOutsideRecyclerViewportContinueSelectionAndAutoScroll() {
        lateinit var container: CjkTextSurface
        lateinit var recycler: RecyclerView
        lateinit var paragraphAdapter: ParagraphAdapter
        lateinit var listener: CjkSelectionHandleListener
        lateinit var scrollHost: CjkSelectionScrollHost
        val outsideViewport = FloatArray(2)

        ActivityScenario.launch(CjkTextViewTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val contents = paragraphContents(64)
                paragraphAdapter = ParagraphAdapter(contents)
                recycler = RecyclerView(activity).apply {
                    layoutManager = LinearLayoutManager(activity)
                    adapter = paragraphAdapter
                    setItemViewCacheSize(0)
                }
                container = CjkTextSurface(activity).apply {
                    document = contents.selectionDocument()
                    selectionAutoScrollEdgeSizeDp = 64f
                    selectionAutoScrollMaxVelocityDpPerSecond = 6_000f
                    selectionScrollHost = CjkSelectionScrollHost.forView(recycler).also {
                        scrollHost = it
                    }
                    addView(
                        recycler,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            420,
                        ),
                    )
                }
                activity.setContentView(container)
            }
            waitForIdle()

            scenario.onActivity {
                val beforeProbe = recycler.computeVerticalScrollOffset()
                val reportedConsumption = scrollHost.scrollBy(37f)
                val actualConsumption = recycler.computeVerticalScrollOffset() - beforeProbe
                assertTrue(actualConsumption > 0)
                assertEquals(actualConsumption.toFloat(), reportedConsumption)
                recycler.scrollBy(0, -actualConsumption)

                val first = adapterViewAt(recycler, 0)
                assertTrue(first.setSelection(0, 1))
                listener = documentSelectionHandleListener(container)
                val recyclerLocation = IntArray(2).also(recycler::getLocationOnScreen)
                outsideViewport[0] = recyclerLocation[0] + recycler.width / 2f
                outsideViewport[1] = recyclerLocation[1] + recycler.height + 96f
                listener.onHandleDragStarted(CjkSelectionHandle.End)
                listener.onHandleDragMoved(
                    CjkSelectionHandle.End,
                    Float.NaN,
                    Float.NaN,
                    outsideViewport[0],
                    outsideViewport[1],
                    true,
                )
            }

            awaitCondition(
                scenario,
                2_000L,
                "coordinates below the viewport stopped the handle-owned scroll loop",
                diagnostic = {
                    "offset=${recycler.computeVerticalScrollOffset()}, selected=${container.selectedText}, " +
                        "recycled=${paragraphAdapter.recycledCount}"
                },
            ) {
                recycler.computeVerticalScrollOffset() > 0 &&
                    requireNotNull(container.selectedText).length > contentsText(0).length
            }
            scenario.onActivity {
                listener.onHandleDragFinished(CjkSelectionHandle.End, null, cancelled = false)
                assertTrue(container.hasSelection)
            }
        }
    }

    @Test
    fun endHandlePopupKeepsItsStreamOutsideRecyclerViewportAndAutoScrolls() {
        lateinit var container: CjkTextSurface
        lateinit var recycler: RecyclerView
        lateinit var paragraphAdapter: ParagraphAdapter
        val selectionStart = FloatArray(2)
        val handleBounds = Rect()
        val target = FloatArray(2)
        val consumed = mutableListOf<Float>()

        ActivityScenario.launch(CjkTextViewTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val contents = paragraphContents(64)
                paragraphAdapter = ParagraphAdapter(contents)
                recycler = RecyclerView(activity).apply {
                    layoutManager = LinearLayoutManager(activity)
                    adapter = paragraphAdapter
                    setItemViewCacheSize(0)
                }
                container = CjkTextSurface(activity).apply {
                    document = contents.selectionDocument()
                    selectionAutoScrollEdgeSizeDp = 64f
                    selectionAutoScrollMaxVelocityDpPerSecond = 6_000f
                    selectionScrollHost = object : CjkSelectionScrollHost {
                        override fun scrollBy(deltaPx: Float): Float {
                            val before = recycler.computeVerticalScrollOffset()
                            recycler.scrollBy(0, deltaPx.roundToInt())
                            return (recycler.computeVerticalScrollOffset() - before).toFloat()
                                .also(consumed::add)
                        }

                        override fun viewportBoundsOnScreen(outBounds: Rect): Boolean =
                            recycler.getGlobalVisibleRect(outBounds)
                    }
                    selectionRetentionHost = CjkSelectionRetentionHost { key ->
                        paragraphAdapter.retain(recycler, key) {}
                    }
                    addView(
                        recycler,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            420,
                        ),
                    )
                }
                activity.setContentView(container)
            }
            waitForIdle()

            // Put both the initial paragraph and the viewport-exterior target inside the app's
            // visible frame. The test must never turn a selection gesture into a system-bar drag.
            scenario.onActivity { activity ->
                val safeWindow = Rect().also(activity.window.decorView::getWindowVisibleDisplayFrame)
                val containerLocation = IntArray(2).also(container::getLocationOnScreen)
                val slop = ViewConfiguration.get(activity).scaledTouchSlop
                (recycler.layoutParams as FrameLayout.LayoutParams).apply {
                    topMargin = (safeWindow.top - containerLocation[1] + 4 * slop).coerceAtLeast(0)
                    recycler.layoutParams = this
                }
            }
            waitForIdle()
            scenario.onActivity { activity ->
                val first = adapterViewAt(recycler, 0)
                selectionCenterOnScreen(first, TextRange(0, 1), selectionStart)
                val safeWindow = Rect().also(activity.window.decorView::getWindowVisibleDisplayFrame)
                val recyclerRect = Rect().also { assertTrue(recycler.getGlobalVisibleRect(it)) }
                val slop = ViewConfiguration.get(activity).scaledTouchSlop
                target[0] = recyclerRect.exactCenterX()
                target[1] = (recyclerRect.bottom + 2 * slop).toFloat()
                assertTrue(safeWindow.contains(selectionStart[0].toInt(), selectionStart[1].toInt()))
                assertTrue(safeWindow.contains(target[0].toInt(), target[1].toInt()))
                assertTrue(target[1] > recyclerRect.bottom)
            }

            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val selectionDownTime = SystemClock.uptimeMillis()
            sendPointer(
                instrumentation,
                selectionDownTime,
                selectionDownTime,
                MotionEvent.ACTION_DOWN,
                selectionStart,
            )
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 120L)
            sendPointer(
                instrumentation,
                selectionDownTime,
                SystemClock.uptimeMillis(),
                MotionEvent.ACTION_UP,
                selectionStart,
            )
            awaitCondition(scenario, 1_000L, "the long press did not present an end handle") {
                container.selectionHandleBoundsOnScreen(CjkSelectionHandle.End, handleBounds)
            }
            scenario.onActivity {
                assertFalse(handleBounds.contains(target[0].toInt(), target[1].toInt()))
            }

            val handleDownTime = SystemClock.uptimeMillis()
            sendPointer(
                instrumentation,
                handleDownTime,
                handleDownTime,
                MotionEvent.ACTION_DOWN,
                floatArrayOf(handleBounds.exactCenterX(), handleBounds.exactCenterY()),
            )
            scenario.onActivity {
                assertTrue(container.isSelectionHandleDragging(CjkSelectionHandle.End))
            }
            repeat(24) { index ->
                sendPointer(
                    instrumentation,
                    handleDownTime,
                    handleDownTime + 16L * (index + 1),
                    MotionEvent.ACTION_MOVE,
                    target,
                )
                SystemClock.sleep(16L)
            }
            awaitCondition(
                scenario,
                2_000L,
                "the popup lost its stream or stopped scrolling outside the viewport",
                diagnostic = {
                    "dragging=${container.isSelectionHandleDragging(CjkSelectionHandle.End)}, " +
                        "offset=${recycler.computeVerticalScrollOffset()}, consumed=$consumed"
                },
            ) {
                container.isSelectionHandleDragging(CjkSelectionHandle.End) &&
                    recycler.computeVerticalScrollOffset() > 0 &&
                    consumed.count { it > 0f } > 1
            }
            sendPointer(
                instrumentation,
                handleDownTime,
                SystemClock.uptimeMillis(),
                MotionEvent.ACTION_UP,
                target,
            )
            waitForIdle()
            scenario.onActivity {
                assertFalse(container.isSelectionHandleDragging(CjkSelectionHandle.End))
                assertTrue(container.hasSelection)
            }
        }
    }

    private fun paragraphContents(count: Int): List<CjkTextContent> = List(count) { index ->
        CjkTextContent(contentsText(index), TextStyle(fontSize = 30f))
    }

    private fun List<CjkTextContent>.selectionDocument(): CjkSelectionDocument =
        CjkSelectionDocument(
            mapIndexed { index, content ->
                CjkSelectionDocumentFragment(index.toLong(), content.content.text)
            },
        )

    private fun contentsText(index: Int): String =
        "第${index.toString().padStart(2, '0')}段：天地玄黄宇宙洪荒，日月盈昃辰宿列张。"

    private fun adapterViewAt(recycler: RecyclerView, position: Int): CjkTextView =
        (requireNotNull(recycler.findViewHolderForAdapterPosition(position)) as ParagraphHolder).view

    private fun documentSelectionHandleListener(
        container: CjkTextSurface,
    ): CjkSelectionHandleListener {
        val field = CjkTextSurface::class.java.getDeclaredField("selectionController")
        field.isAccessible = true
        return field.get(container) as CjkSelectionHandleListener
    }

    private fun waitForIdle() = InstrumentationRegistry.getInstrumentation().waitForIdleSync()

    private fun awaitCondition(
        scenario: ActivityScenario<CjkTextViewTestActivity>,
        timeoutMillis: Long,
        message: String,
        diagnostic: () -> String = { "" },
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            var satisfied = false
            scenario.onActivity { satisfied = condition() }
            if (satisfied) return
            SystemClock.sleep(32L)
        }
        var satisfied = false
        var detail = ""
        scenario.onActivity {
            satisfied = condition()
            detail = diagnostic()
        }
        assertTrue(satisfied, "$message; $detail")
    }

    private fun selectionCenterOnScreen(view: CjkTextView, range: TextRange, out: FloatArray) {
        val box = requireNotNull(view.layoutResult?.getBoundingBoxes(range)?.firstOrNull())
        out[0] = view.toVisibleX((box.left + box.right) / 2f)
        out[1] = view.toVisibleY((box.top + box.bottom) / 2f)
        if (Build.VERSION.SDK_INT >= 29) {
            val matrix = android.graphics.Matrix()
            view.transformMatrixToGlobal(matrix)
            matrix.mapPoints(out)
        } else {
            val location = IntArray(2).also(view::getLocationOnScreen)
            out[0] += location[0]
            out[1] += location[1]
        }
    }

    private fun sendPointer(
        instrumentation: android.app.Instrumentation,
        downTime: Long,
        eventTime: Long,
        action: Int,
        point: FloatArray,
    ) {
        val event = MotionEvent.obtain(downTime, eventTime, action, point[0], point[1], 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        try {
            if (Build.VERSION.SDK_INT >= 36) {
                assertTrue(
                    instrumentation.uiAutomation.injectInputEvent(event, true),
                    "UiAutomation must inject the pointer into the active selection window",
                )
            } else {
                instrumentation.sendPointerSync(event)
            }
        } finally {
            event.recycle()
        }
    }

}

private class ParagraphAdapter(
    private val contents: List<CjkTextContent>,
) : RecyclerView.Adapter<ParagraphHolder>() {
    private val keysByView = mutableMapOf<CjkTextView, MutableSet<Long>>()
    var recycledCount = 0
        private set

    val reboundViewCount: Int get() = keysByView.values.count { it.size > 1 }
    var childActionModeCreatedCount = 0
        private set

    init {
        setHasStableIds(true)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ParagraphHolder =
        ParagraphHolder(
            CjkTextView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                textIsSelectable = true
                customSelectionActionModeCallback = object : ActionMode.Callback {
                    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                        childActionModeCreatedCount++
                        return true
                    }

                    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = true

                    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = false

                    override fun onDestroyActionMode(mode: ActionMode) = Unit
                }
            },
        )

    override fun onBindViewHolder(holder: ParagraphHolder, position: Int) {
        val key = getItemId(position)
        keysByView.getOrPut(holder.view) { mutableSetOf() } += key
        holder.boundKey = key
        holder.view.bindSelectionFragment(key, contents[position], retentionKey = key)
    }

    override fun onViewRecycled(holder: ParagraphHolder) {
        recycledCount++
        holder.view.unbindSelectionFragment()
        holder.boundKey = null
    }

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getItemCount(): Int = contents.size

    fun retain(
        recycler: RecyclerView,
        key: Any,
        onRelease: () -> Unit,
    ): CjkSelectionRetentionHandle {
        val holder = recycler.findViewHolderForItemId(key as Long) as? ParagraphHolder
            ?: error("Active endpoint holder must be attached: $key")
        holder.setIsRecyclable(false)
        check(!holder.isRecyclable)
        var released = false
        return CjkSelectionRetentionHandle {
            if (!released) {
                released = true
                holder.setIsRecyclable(true)
                onRelease()
            }
        }
    }
}

private class ParagraphHolder(val view: CjkTextView) : RecyclerView.ViewHolder(view) {
    var boundKey: Long? = null
}

private class RecordingActionModeCallback : ActionMode.Callback {
    var createdCount = 0
    var mode: ActionMode? = null

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        createdCount++
        this.mode = mode
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = true

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = false

    override fun onDestroyActionMode(mode: ActionMode) {
        if (this.mode === mode) this.mode = null
    }
}
