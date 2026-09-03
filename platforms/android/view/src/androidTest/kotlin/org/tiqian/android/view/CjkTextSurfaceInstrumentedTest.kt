package org.tiqian.android.view

import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.graphics.Matrix
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.os.SystemClock
import android.view.ActionMode
import android.view.InputDevice
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import org.tiqian.core.TextRange
import org.tiqian.core.TextStyle
import org.tiqian.core.RubySpan
import org.tiqian.core.TiqianTextContent
import org.tiqian.core.cursorRect
import org.tiqian.core.getBoundingBoxes
import org.tiqian.core.selectionWordRangeForPosition
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class CjkTextSurfaceInstrumentedTest {
    @Test
    fun documentChildUsesSurfaceAsSelectionOwnerForAccessibilityCopyAndPublicQueries() {
        ActivityScenario.launch(CjkTextViewTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val paragraph = CjkTextContent("甲乙丙", TextStyle(fontSize = 32f))
                val childCallback = object : ActionMode.Callback {
                    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean = true
                    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = true
                    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = false
                    override fun onDestroyActionMode(mode: ActionMode) = Unit
                }
                val surface = CjkTextSurface(activity).apply {
                    document = CjkSelectionDocument(
                        listOf(CjkSelectionDocumentFragment("body", paragraph)),
                    )
                    addView(
                        CjkTextView(activity).apply {
                            bindSelectionFragment("body", paragraph)
                            customSelectionActionModeCallback = childCallback
                        },
                    )
                }
                val child = surface.getChildAt(0) as CjkTextView
                activity.setContentView(surface)
                measureAndLayout(surface, 320)

                assertTrue(child.setSelection(0, 2))
                assertTrue(surface.isFocused, "the surface must own document selection focus")
                assertEquals("甲乙", child.selectedText)
                assertEquals("甲乙", surface.selectedText)
                assertSame(childCallback, surface.customSelectionActionModeCallback)
                assertSame(childCallback, child.customSelectionActionModeCallback)

                val node = child.createAccessibilityNodeInfo()
                assertTrue(
                    node.actionList.any { it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_COPY },
                    "a document child must expose copy while its surface owns focus",
                )
                assertTrue(
                    child.performAccessibilityAction(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_COPY,
                        null,
                    ),
                )
                val clipboard = activity.getSystemService(ClipboardManager::class.java)
                assertEquals("甲乙", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
            }
        }
    }

    @Test
    fun disablingAnUnrelatedFragmentDoesNotClearTheLogicalDocumentSelection() {
        ActivityScenario.launch(CjkTextViewTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val firstContent = CjkTextContent("甲乙", TextStyle(fontSize = 32f))
                val secondContent = CjkTextContent("丙丁", TextStyle(fontSize = 32f))
                val first = CjkTextView(activity).apply {
                    bindSelectionFragment("first", firstContent)
                }
                val second = CjkTextView(activity).apply {
                    bindSelectionFragment("second", secondContent)
                }
                val surface = CjkTextSurface(activity).apply {
                    document = CjkSelectionDocument(
                        listOf(
                            CjkSelectionDocumentFragment("first", firstContent),
                            CjkSelectionDocumentFragment("second", secondContent),
                        ),
                    )
                    addView(
                        LinearLayout(activity).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(first)
                            addView(second)
                        },
                    )
                }
                activity.setContentView(surface)
                measureAndLayout(surface, 320)

                assertTrue(first.setSelection(0, 1))
                assertEquals("甲", surface.selectedText)

                second.textIsSelectable = false

                assertTrue(surface.hasSelection)
                assertEquals("甲", surface.selectedText)
                assertEquals(TextRange(0, 1), first.selection)
                assertNull(second.selection)
            }
        }
    }

    @Test
    fun documentHandleUsesTheSameWordAdjustmentAsStandaloneView() {
        ActivityScenario.launch(CjkTextViewTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val source = "alpha beta gamma"
                val initial = TextRange(6, 10)
                val target = TextRange(12, 13)

                val standalone = CjkTextView(activity).apply {
                    content = CjkTextContent(source, TextStyle(fontSize = 42f))
                }
                activity.setContentView(standalone)
                measureAndLayout(standalone, 520)
                assertTrue(standalone.requestFocus())
                assertTrue(standalone.setSelection(initial.start, initial.end))
                val localTarget = requireNotNull(
                    standalone.layoutResult?.getBoundingBoxes(target)?.firstOrNull(),
                )
                val standaloneListener = readPrivateField(standalone, "selectionController")
                    as CjkSelectionHandleListener
                standaloneListener.onHandleDragStarted(CjkSelectionHandle.End)
                standaloneListener.onHandleDragMoved(
                    CjkSelectionHandle.End,
                    standalone.toVisibleX((localTarget.left + localTarget.right) / 2f),
                    standalone.toVisibleY((localTarget.top + localTarget.bottom) / 2f),
                    Float.NaN,
                    Float.NaN,
                    false,
                )
                standaloneListener.onHandleDragFinished(
                    CjkSelectionHandle.End,
                    null,
                    cancelled = true,
                )
                val standaloneRange = requireNotNull(standalone.selection)

                val content = CjkTextContent(source, TextStyle(fontSize = 42f))
                val documentView = CjkTextView(activity).apply {
                    bindSelectionFragment("body", content)
                }
                val surface = CjkTextSurface(activity).apply {
                    document = CjkSelectionDocument(
                        listOf(CjkSelectionDocumentFragment("body", content)),
                    )
                    addView(documentView)
                }
                activity.setContentView(surface)
                measureAndLayout(surface, 520)
                assertTrue(documentView.setSelection(initial.start, initial.end))
                val documentTarget = requireNotNull(
                    documentView.layoutResult?.getBoundingBoxes(target)?.firstOrNull(),
                )
                val rawTarget = rawPointOnScreen(
                    documentView,
                    documentView.toVisibleX((documentTarget.left + documentTarget.right) / 2f),
                    documentView.toVisibleY((documentTarget.top + documentTarget.bottom) / 2f),
                )
                val documentListener = documentSelectionHandleListener(surface)
                documentListener.onHandleDragStarted(CjkSelectionHandle.End)
                documentListener.onHandleDragMoved(
                    CjkSelectionHandle.End,
                    Float.NaN,
                    Float.NaN,
                    rawTarget[0],
                    rawTarget[1],
                    false,
                )
                documentListener.onHandleDragFinished(
                    CjkSelectionHandle.End,
                    null,
                    cancelled = true,
                )

                assertEquals(
                    standaloneRange,
                    documentView.selection,
                    "placing a CjkTextView in a document must not change handle word adjustment",
                )
            }
        }
    }

    @Test
    fun logicalSelectAllAndCopyDoNotDependOnAttachedFragments() {
        ActivityScenario.launch(CjkTextViewTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val firstContent = CjkTextContent(
                    content = TiqianTextContent("第一段"),
                    textStyle = TextStyle(fontSize = 32f),
                    rubySpans = listOf(RubySpan(TextRange(0, 3), "注")),
                )
                val secondContent = content("第二段")
                val document = CjkSelectionDocument(
                    listOf(
                        CjkSelectionDocumentFragment("first", firstContent),
                        CjkSelectionDocumentFragment("second", secondContent),
                    ),
                )
                val container = CjkTextSurface(activity).apply { this.document = document }
                val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                val title = CjkTextView(activity).apply { content = content("未绑定标题") }
                val first = CjkTextView(activity).apply {
                    bindSelectionFragment("first", firstContent)
                }
                val second = CjkTextView(activity).apply {
                    bindSelectionFragment("second", secondContent)
                }
                column.addView(title)
                column.addView(first)
                column.addView(second)
                container.addView(column)
                activity.setContentView(container)
                measureAndLayout(container, 360)

                assertTrue(container.selectAll())
                assertEquals("第一段\n第二段", container.selectedText)
                assertEquals(TextRange(0, 3), first.selection)
                assertEquals(TextRange(0, 3), second.selection)
                assertNull(title.selection, "an unbound host paragraph must not enter the document")

                column.removeView(first)
                column.removeView(second)
                assertEquals("第一段\n第二段", container.selectedText)
                assertTrue(container.copySelection())
                val clipboard = activity.getSystemService(ClipboardManager::class.java)
                assertEquals("第一段（注）\n第二段", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
            }
        }
    }

    @Test
    fun recycledViewRebindIsAtomicAndRestoresLogicalProjection() {
        ActivityScenario.launch(CjkTextViewTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val first = content("甲乙")
                val second = content("丙丁")
                val container = CjkTextSurface(activity).apply {
                    document = CjkSelectionDocument(
                        listOf(
                            CjkSelectionDocumentFragment("first", "甲乙"),
                            CjkSelectionDocumentFragment("second", "丙丁"),
                        ),
                    )
                }
                val recycled = CjkTextView(activity).apply {
                    bindSelectionFragment("first", first, retentionKey = "holder:first")
                }
                container.addView(recycled)
                activity.setContentView(container)
                measureAndLayout(container, 320)
                assertTrue(container.selectAll())
                assertEquals(TextRange(0, 2), recycled.selection)

                recycled.bindSelectionFragment("second", second, retentionKey = "holder:second")
                assertNull(
                    recycled.selection,
                    "a rebound holder must not expose selection boxes from its previous layout",
                )
                measureAndLayout(container, 320)
                assertEquals("second", recycled.selectionDocumentKey)
                assertEquals(TextRange(0, 2), recycled.selection)
                assertEquals("甲乙\n丙丁", container.selectedText)

                recycled.unbindSelectionFragment()
                assertNull(recycled.selectionDocumentKey)
                assertNull(recycled.selection)
                assertEquals("甲乙\n丙丁", container.selectedText)
            }
        }
    }

    @Test
    fun boundContentCannotDriftFromLogicalDocument() {
        ActivityScenario.launch(CjkTextViewTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val original = content("正文")
                val container = CjkTextSurface(activity).apply {
                    document = CjkSelectionDocument(
                        listOf(CjkSelectionDocumentFragment("body", "正文")),
                    )
                }
                val view = CjkTextView(activity).apply { bindSelectionFragment("body", original) }
                container.addView(view)
                activity.setContentView(container)
                measureAndLayout(container, 320)

                assertFailsWith<IllegalArgumentException> { view.content = content("漂移") }
                assertEquals("正文", view.content.content.text)
                assertEquals("body", view.selectionDocumentKey)
            }
        }
    }

    @Test
    fun longPressDragSelectsAcrossParagraphViews() {
        lateinit var container: CjkTextSurface
        lateinit var first: CjkTextView
        lateinit var second: CjkTextView
        val startOnScreen = FloatArray(2)
        val endOnScreen = FloatArray(2)
        val retainedKeys = mutableListOf<Any>()
        var releasedRetentionCount = 0
        ActivityScenario.launch(CjkTextViewTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                container = CjkTextSurface(activity).apply {
                    document = CjkSelectionDocument(
                        listOf(
                            CjkSelectionDocumentFragment("first", "甲乙"),
                            CjkSelectionDocumentFragment("second", "丙丁"),
                        ),
                    )
                    selectionRetentionHost = CjkSelectionRetentionHost { key ->
                        retainedKeys += key
                        CjkSelectionRetentionHandle { releasedRetentionCount++ }
                    }
                }
                val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                first = CjkTextView(activity).apply { bindSelectionFragment("first", content("甲乙")) }
                second = CjkTextView(activity).apply { bindSelectionFragment("second", content("丙丁")) }
                column.addView(first)
                column.addView(second)
                container.addView(column)
                activity.setContentView(container)
                measureAndLayout(container, 360)
            }

            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                selectionCenterOnScreen(first, TextRange(0, 1), startOnScreen)
                selectionCenterOnScreen(second, TextRange(1, 2), endOnScreen)
                val box = requireNotNull(second.layoutResult?.getBoundingBoxes(TextRange(1, 2))?.firstOrNull())
                val snapshot = requireNotNull(second.layoutSnapshot)
                assertEquals(
                    TextRange(1, 2),
                    snapshot.replayIndex.selectionWordRangeForPosition(
                        snapshot.result,
                        (box.left + box.right) / 2f,
                        (box.top + box.bottom) / 2f,
                    ),
                    "the engine-local endpoint must identify the intended second glyph",
                )
            }

            val downTime = SystemClock.uptimeMillis()
            sendPointer(instrumentation, downTime, downTime, MotionEvent.ACTION_DOWN, startOnScreen)
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 120L)
            scenario.onActivity {
                assertEquals(listOf<Any>("first"), retainedKeys)
                assertEquals(0, releasedRetentionCount)
            }
            sendPointer(
                instrumentation,
                downTime,
                SystemClock.uptimeMillis(),
                MotionEvent.ACTION_MOVE,
                endOnScreen,
            )
            sendPointer(
                instrumentation,
                downTime,
                SystemClock.uptimeMillis(),
                MotionEvent.ACTION_UP,
                endOnScreen,
            )
            instrumentation.waitForIdleSync()

            scenario.onActivity {
                assertEquals("甲乙\n丙丁", container.selectedText)
                assertEquals(TextRange(0, 2), first.selection)
                assertEquals(TextRange(0, 2), second.selection)
                assertTrue(container.hasSelection)
                assertEquals(1, releasedRetentionCount)
            }
        }
    }

    @Test
    fun crossItemHandlesClampToEngineInteractionBoundaries() {
        ActivityScenario.launch(CjkTextViewTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val firstContent = content("甲")
                val secondContent = content("乙😀")
                val container = CjkTextSurface(activity).apply {
                    document = CjkSelectionDocument(
                        listOf(
                            CjkSelectionDocumentFragment("first", firstContent.content.text),
                            CjkSelectionDocumentFragment("second", secondContent.content.text),
                        ),
                    )
                }
                val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                val first = CjkTextView(activity).apply {
                    bindSelectionFragment("first", firstContent)
                }
                val second = CjkTextView(activity).apply {
                    bindSelectionFragment("second", secondContent)
                }
                column.addView(first)
                column.addView(second)
                container.addView(column)
                activity.setContentView(container)
                measureAndLayout(container, 360)
                val listener = documentSelectionHandleListener(container)

                assertTrue(container.selectAll())
                val afterEnd = rawPointOnScreen(second, second.width - 1f, second.height / 2f)
                listener.onHandleDragStarted(CjkSelectionHandle.Start)
                listener.onHandleDragMoved(
                    CjkSelectionHandle.Start,
                    Float.NaN,
                    Float.NaN,
                    afterEnd[0],
                    afterEnd[1],
                    true,
                )
                listener.onHandleDragFinished(CjkSelectionHandle.Start, null, cancelled = false)
                assertEquals("😀", container.selectedText)

                assertTrue(container.selectAll())
                val beforeStart = rawPointOnScreen(first, 0f, first.height / 2f)
                listener.onHandleDragStarted(CjkSelectionHandle.End)
                listener.onHandleDragMoved(
                    CjkSelectionHandle.End,
                    Float.NaN,
                    Float.NaN,
                    beforeStart[0],
                    beforeStart[1],
                    true,
                )
                listener.onHandleDragFinished(CjkSelectionHandle.End, null, cancelled = false)
                assertEquals("甲", container.selectedText)
            }
        }
    }

    @Test
    fun crossedDocumentEndHandleProjectsXOntoTheFixedFragmentsLine() {
        ActivityScenario.launch(CjkTextViewTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val firstContent = content("天地玄黄")
                val secondContent = content("宇宙洪荒")
                val thirdContent = content("日月盈昃")
                val container = CjkTextSurface(activity).apply {
                    document = CjkSelectionDocument(
                        listOf(
                            CjkSelectionDocumentFragment("first", firstContent.content.text),
                            CjkSelectionDocumentFragment("second", secondContent.content.text),
                            CjkSelectionDocumentFragment("third", thirdContent.content.text),
                        ),
                    )
                }
                val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                val first = CjkTextView(activity).apply {
                    bindSelectionFragment("first", firstContent)
                }
                val second = CjkTextView(activity).apply {
                    bindSelectionFragment("second", secondContent)
                }
                val third = CjkTextView(activity).apply {
                    bindSelectionFragment("third", thirdContent)
                }
                column.addView(first)
                column.addView(second)
                column.addView(third)
                container.addView(column)
                activity.setContentView(container)
                measureAndLayout(container, 360)
                val listener = documentSelectionHandleListener(container)

                assertTrue(second.setSelection(0, 2))
                val thirdEnd = rawPointOnScreen(third, third.width - 1f, third.height / 2f)
                listener.onHandleDragStarted(CjkSelectionHandle.End)
                listener.onHandleDragMoved(
                    CjkSelectionHandle.End,
                    Float.NaN,
                    Float.NaN,
                    thirdEnd[0],
                    thirdEnd[1],
                    false,
                )
                listener.onHandleDragFinished(CjkSelectionHandle.End, null, cancelled = true)
                assertEquals(TextRange(0, 4), third.selection)

                val secondSnapshot = requireNotNull(second.layoutSnapshot)
                val projectedOffset = 3
                val projectedCharacter = requireNotNull(
                    secondSnapshot.result.getBoundingBoxes(
                        TextRange(projectedOffset - 1, projectedOffset),
                    ).firstOrNull(),
                )
                val entryCharacter = requireNotNull(
                    secondSnapshot.result.getBoundingBoxes(
                        TextRange(projectedOffset, projectedOffset + 1),
                    ).firstOrNull(),
                )
                // Enter the fixed line from its final character so Editor's first crossed-line
                // adjustment lands at the word end, then continue left. Use an interior point,
                // not the exact caret tie between adjacent source units.
                val entryX = second.toVisibleX(
                    (entryCharacter.left + entryCharacter.right) / 2f,
                )
                val projectedX = second.toVisibleX(projectedCharacter.left)
                val crossedEntry = rawPointOnScreen(first, entryX, first.height / 2f)
                listener.onHandleDragStarted(CjkSelectionHandle.End)
                listener.onHandleDragMoved(
                    CjkSelectionHandle.End,
                    Float.NaN,
                    Float.NaN,
                    crossedEntry[0],
                    crossedEntry[1],
                    false,
                )
                // A physical drag supplies a continuous stream rather than teleporting between
                // two samples. The trajectory must visit the exact intermediate source boundary;
                // skipping from 4 directly to 2 remains a failure.
                val visitedOffsets = mutableListOf<Int>()
                for (step in 1..32) {
                    val fraction = step / 32f
                    val x = entryX + (projectedX - entryX) * fraction
                    val target = rawPointOnScreen(first, x, first.height / 2f)
                    listener.onHandleDragMoved(
                        CjkSelectionHandle.End,
                        Float.NaN,
                        Float.NaN,
                        target[0],
                        target[1],
                        false,
                    )
                    val offset = second.selection?.end ?: continue
                    visitedOffsets += offset
                    if (offset == projectedOffset) break
                }
                assertTrue(
                    projectedOffset in visitedOffsets,
                    "continuous crossed-line drag skipped $projectedOffset: $visitedOffsets",
                )
                listener.onHandleDragFinished(CjkSelectionHandle.End, null, cancelled = true)

                assertNull(first.selection)
                assertEquals(TextRange(0, projectedOffset), second.selection)
                assertNull(third.selection)
                assertEquals("宇宙洪", container.selectedText)
            }
        }
    }

    @Test
    fun integratedHandleKeepsSelectingOutsideParagraphBounds() {
        lateinit var container: CjkTextSurface
        lateinit var first: CjkTextView
        lateinit var second: CjkTextView
        val endHandle = RectF()
        val outsideParagraphs = FloatArray(2)
        ActivityScenario.launch(CjkTextViewTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val firstContent = content("甲乙丙丁")
                val secondContent = content("戊己庚辛")
                container = CjkTextSurface(activity).apply {
                    document = CjkSelectionDocument(
                        listOf(
                            CjkSelectionDocumentFragment("first", firstContent.content.text),
                            CjkSelectionDocumentFragment("second", secondContent.content.text),
                        ),
                    )
                }
                val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                first = CjkTextView(activity).apply {
                    bindSelectionFragment("first", firstContent)
                }
                second = CjkTextView(activity).apply {
                    bindSelectionFragment("second", secondContent)
                }
                column.addView(first)
                column.addView(second)
                container.addView(column)
                activity.setContentView(container)
                measureAndLayout(container, 360)
                assertTrue(first.setSelection(0, 1))
            }

            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val bounds = android.graphics.Rect()
                assertTrue(container.selectionHandleBoundsOnScreen(CjkSelectionHandle.End, bounds))
                endHandle.set(bounds)
                val secondLocation = IntArray(2).also(second::getLocationOnScreen)
                val safeWindow = android.graphics.Rect().also {
                    activity.window.decorView.getWindowVisibleDisplayFrame(it)
                }
                val safetyInset = 2 * ViewConfiguration.get(activity).scaledTouchSlop
                outsideParagraphs[0] = safeWindow.exactCenterX()
                outsideParagraphs[1] =
                    (secondLocation[1] + second.height + safetyInset).toFloat()
                assertTrue(outsideParagraphs[1] > secondLocation[1] + second.height)
                assertTrue(
                    safeWindow.contains(outsideParagraphs[0].toInt(), outsideParagraphs[1].toInt()),
                )
            }

            val downTime = SystemClock.uptimeMillis()
            sendPointer(
                instrumentation,
                downTime,
                downTime,
                MotionEvent.ACTION_DOWN,
                floatArrayOf(endHandle.centerX(), endHandle.centerY()),
            )
            sendPointer(
                instrumentation,
                downTime,
                downTime + 32L,
                MotionEvent.ACTION_MOVE,
                outsideParagraphs,
            )
            sendPointer(
                instrumentation,
                downTime,
                downTime + 64L,
                MotionEvent.ACTION_UP,
                outsideParagraphs,
            )
            instrumentation.waitForIdleSync()

            scenario.onActivity {
                assertEquals("甲乙丙丁\n戊己庚辛", container.selectedText)
                assertEquals(TextRange(0, 4), first.selection)
                assertEquals(TextRange(0, 4), second.selection)
            }
        }
    }

    @Test
    fun clippedAncestorReplaysLegalRubyOverhangWithoutDirectChildDuplication() {
        ActivityScenario.launch(CjkTextViewTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val rubyContent = CjkTextContent(
                    content = TiqianTextContent("提椠"),
                    textStyle = TextStyle(fontSize = 52f),
                    rubySpans = listOf(RubySpan(TextRange(0, 2), "tíqiàn annotation")),
                )
                val container = CjkTextSurface(activity)
                val clippingParent = FrameLayout(activity).apply {
                    clipChildren = true
                    clipToPadding = true
                    translationX = 11f
                    scaleX = 1.05f
                    scaleY = 1.05f
                }
                val view = CjkTextView(activity).apply {
                    overflow = CjkTextOverflow.Visible
                    textIsSelectable = false
                    content = rubyContent
                }
                clippingParent.addView(
                    view,
                    FrameLayout.LayoutParams(220, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                        // The clipping ancestor is a viewport, so legal ruby may escape the
                        // paragraph item while remaining inside the viewport itself.
                        topMargin = 48
                    },
                )
                container.addView(
                    clippingParent,
                    FrameLayout.LayoutParams(260, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                        leftMargin = 70
                        topMargin = 90
                    },
                )
                activity.setContentView(container)
                measureAndLayout(container, 360)

                val legal = RectF().also(view::legalPaintBounds)
                assertTrue(
                    legal.left < 0f || legal.top < 0f || legal.right > view.width || legal.bottom > view.height,
                    "the fixture must contain engine-authorized paint outside the paragraph viewport: $legal",
                )
                assertTrue(container.requiresPaintOverhangReplay(view))

                val bitmap = Bitmap.createBitmap(container.width, container.height, Bitmap.Config.ARGB_8888)
                container.draw(Canvas(bitmap))
                val viewToGlobal = Matrix().also(view::transformMatrixToGlobal)
                val containerToGlobal = Matrix().also(container::transformMatrixToGlobal)
                val globalToContainer = Matrix().also { assertTrue(containerToGlobal.invert(it)) }
                val viewToContainer = Matrix().apply { setConcat(globalToContainer, viewToGlobal) }
                val viewport = RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()).also {
                    viewToContainer.mapRect(it)
                }
                val legalInContainer = RectF(legal).also(viewToContainer::mapRect)
                val paintedOutsideViewport = countPaintedOutside(
                    bitmap = bitmap,
                    viewport = viewport,
                    legal = legalInContainer,
                )
                assertTrue(paintedOutsideViewport > 0, "legal ruby ink must survive the clipping ancestor")

                val replayed = Bitmap.createBitmap(
                    container.width,
                    container.height,
                    Bitmap.Config.ARGB_8888,
                )
                container.draw(Canvas(replayed))
                assertEquals(
                    paintedOutsideViewport,
                    countPaintedOutside(replayed, viewport, legalInContainer),
                    "a retained overhang recording must preserve the original legal ink",
                )

                // Paint-only state changes must replace the retained recording without requiring
                // a new LayoutResult or leaking pixels from the previous text color.
                view.textColors = ColorStateList.valueOf(Color.RED)
                val recolored = Bitmap.createBitmap(
                    container.width,
                    container.height,
                    Bitmap.Config.ARGB_8888,
                )
                container.draw(Canvas(recolored))
                assertTrue(
                    countRedPaintedOutside(recolored, viewport, legalInContainer) > 0,
                    "the overhang recording must be rebuilt for paint-only state changes",
                )

                clippingParent.removeView(view)
                container.clipChildren = true
                container.addView(view, FrameLayout.LayoutParams(220, FrameLayout.LayoutParams.WRAP_CONTENT))
                measureAndLayout(container, 360)
                assertTrue(
                    container.requiresPaintOverhangReplay(view),
                    "a clipping surface must replay its direct child's legal overhang",
                )
            }
        }
    }

    @Test
    fun nestedNonClippingParentDoesNotDoublePaintSurvivingOverhang() {
        ActivityScenario.launch(CjkTextViewTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val content = CjkTextContent(
                    content = TiqianTextContent("提椠"),
                    textStyle = TextStyle(fontSize = 52f),
                    rubySpans = listOf(RubySpan(TextRange(0, 2), "tíqiàn annotation")),
                )
                val container = CjkTextSurface(activity)
                val clippingParent = FrameLayout(activity).apply {
                    clipChildren = true
                    clipToPadding = false
                }
                val nonClippingParent = FrameLayout(activity).apply {
                    clipChildren = false
                    clipToPadding = false
                }
                val view = CjkTextView(activity).apply {
                    overflow = CjkTextOverflow.Visible
                    textIsSelectable = false
                    this.content = content
                }
                nonClippingParent.addView(
                    view,
                    FrameLayout.LayoutParams(220, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = 48
                    },
                )
                clippingParent.addView(
                    nonClippingParent,
                    FrameLayout.LayoutParams(240, 220).apply {
                        leftMargin = 10
                        topMargin = 10
                    },
                )
                container.addView(
                    clippingParent,
                    FrameLayout.LayoutParams(260, 240).apply {
                        leftMargin = 50
                        topMargin = 60
                    },
                )
                activity.setContentView(container)
                measureAndLayout(container, 360)
                assertTrue(container.requiresPaintOverhangReplay(view))

                val normalPass = Bitmap.createBitmap(
                    nonClippingParent.width,
                    nonClippingParent.height,
                    Bitmap.Config.ARGB_8888,
                )
                nonClippingParent.draw(Canvas(normalPass))
                val composed = Bitmap.createBitmap(
                    container.width,
                    container.height,
                    Bitmap.Config.ARGB_8888,
                )
                container.draw(Canvas(composed))

                val originX = clippingParent.left + nonClippingParent.left
                val originY = clippingParent.top + nonClippingParent.top
                var comparedInk = 0
                for (y in 0 until normalPass.height) for (x in 0 until normalPass.width) {
                    val expected = normalPass.getPixel(x, y)
                    if (Color.alpha(expected) > 0) comparedInk++
                    assertEquals(
                        expected,
                        composed.getPixel(originX + x, originY + y),
                        "overlay changed a pixel already preserved by the ordinary pass at ($x, $y)",
                    )
                }
                assertTrue(comparedInk > 0, "the reference pass must contain text ink")
            }
        }
    }

    private fun content(text: String): CjkTextContent =
        CjkTextContent(text, TextStyle(fontSize = 32f))

    private fun measureAndLayout(view: View, width: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1_200, View.MeasureSpec.AT_MOST),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun selectionCenterOnScreen(view: CjkTextView, range: TextRange, out: FloatArray) {
        val box = requireNotNull(view.layoutResult?.getBoundingBoxes(range)?.firstOrNull())
        out[0] = view.toVisibleX((box.left + box.right) / 2f)
        out[1] = view.toVisibleY((box.top + box.bottom) / 2f)
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val matrix = Matrix()
            view.transformMatrixToGlobal(matrix)
            matrix.mapPoints(out)
        } else {
            val location = IntArray(2).also(view::getLocationOnScreen)
            out[0] += location[0]
            out[1] += location[1]
        }
    }

    private fun rawPointOnScreen(view: View, x: Float, y: Float): FloatArray =
        floatArrayOf(x, y).also { point ->
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val matrix = Matrix()
                view.transformMatrixToGlobal(matrix)
                matrix.mapPoints(point)
            } else {
                val location = IntArray(2).also(view::getLocationOnScreen)
                point[0] += location[0]
                point[1] += location[1]
            }
        }

    private fun documentSelectionHandleListener(
        container: CjkTextSurface,
    ): CjkSelectionHandleListener {
        val field = CjkTextSurface::class.java.getDeclaredField("selectionController")
        field.isAccessible = true
        return field.get(container) as CjkSelectionHandleListener
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
            instrumentation.sendPointerSync(event)
        } finally {
            event.recycle()
        }
    }

    private fun countPaintedOutside(
        bitmap: Bitmap,
        viewport: RectF,
        legal: RectF,
    ): Int {
        val minX = kotlin.math.floor(legal.left).toInt().coerceIn(0, bitmap.width)
        val maxX = kotlin.math.ceil(legal.right).toInt().coerceIn(0, bitmap.width)
        val minY = kotlin.math.floor(legal.top).toInt().coerceIn(0, bitmap.height)
        val maxY = kotlin.math.ceil(legal.bottom).toInt().coerceIn(0, bitmap.height)
        var count = 0
        for (y in minY until maxY) for (x in minX until maxX) {
            val outside = x < viewport.left || x >= viewport.right || y < viewport.top || y >= viewport.bottom
            if (outside && Color.alpha(bitmap.getPixel(x, y)) > 0) count++
        }
        return count
    }

    private fun countRedPaintedOutside(
        bitmap: Bitmap,
        viewport: RectF,
        legal: RectF,
    ): Int {
        val minX = kotlin.math.floor(legal.left).toInt().coerceIn(0, bitmap.width)
        val maxX = kotlin.math.ceil(legal.right).toInt().coerceIn(0, bitmap.width)
        val minY = kotlin.math.floor(legal.top).toInt().coerceIn(0, bitmap.height)
        val maxY = kotlin.math.ceil(legal.bottom).toInt().coerceIn(0, bitmap.height)
        var count = 0
        for (y in minY until maxY) for (x in minX until maxX) {
            val outside = x < viewport.left || x >= viewport.right || y < viewport.top || y >= viewport.bottom
            val color = bitmap.getPixel(x, y)
            if (
                outside && Color.alpha(color) > 0 &&
                Color.red(color) > Color.green(color) + 32 &&
                Color.red(color) > Color.blue(color) + 32
            ) {
                count++
            }
        }
        return count
    }
}
