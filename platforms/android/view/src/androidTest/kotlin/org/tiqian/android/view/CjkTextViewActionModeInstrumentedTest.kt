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

@RunWith(AndroidJUnit4::class)
class CjkTextViewActionModeInstrumentedTest {
    @Test
    fun builtInSelectionActionsMatchNativeTextViewForHostLocalesAndProcessText() {
        val source = "alpha beta"
        val locales = listOf(
            Locale.forLanguageTag("fr-FR"),
            Locale.forLanguageTag("zh-CN"),
        )

        locales.forEach { locale ->
            CjkTextViewTestActivity.localeOverride = locale
            try {
                ActivityScenario.launch(CjkTextViewTestActivity::class.java).use { scenario ->
                    lateinit var nativeView: TextView
                    lateinit var cjkView: CjkTextView
                    lateinit var nativePoint: Pair<Float, Float>
                    lateinit var cjkPoint: Pair<Float, Float>
                    val nativeCallback = RecordingSelectionActionModeCallback()
                    val cjkCallback = RecordingSelectionActionModeCallback()

                    scenario.onActivity { activity ->
                        nativeView = TextView(activity).apply {
                            id = View.generateViewId()
                            text = source
                            textSize = 42f
                            setTextIsSelectable(true)
                            setCustomSelectionActionModeCallback(nativeCallback)
                        }
                        activity.setContentView(nativeView)
                        measureAndLayout(nativeView, 360)
                        nativePoint = nativeVisibleCenter(nativeView, 0)
                    }
                    completeNativeLongPress(scenario, nativeView, nativePoint)
                    awaitActionMode(nativeCallback)
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    scenario.onActivity {
                        assertNotNull(
                            nativeCallback.mode,
                            "native TextView must create selection ActionMode for $locale",
                        )
                        nativeCallback.mode?.finish()
                    }

                    scenario.onActivity { activity ->
                        cjkView = CjkTextView(activity).apply {
                            id = View.generateViewId()
                            content = CjkTextContent(source, TextStyle(fontSize = 42f))
                            customSelectionActionModeCallback = cjkCallback
                        }
                        activity.setContentView(cjkView)
                        measureAndLayout(cjkView, 360)
                        cjkPoint = visibleCenter(cjkView, TextRange(0, 1))
                    }
                    completeLongPress(scenario, cjkView, cjkPoint)
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()

                    scenario.onActivity {
                        val nativeCreateMenu = nativeCallback.createItems
                        val cjkCreateMenu = cjkCallback.createItems
                        assertDefaultSelectionItems(nativeCreateMenu, "native TextView onCreate ($locale)")
                        assertDefaultSelectionItems(cjkCreateMenu, "CjkTextView onCreate ($locale)")
                        val nativeHasShare = nativeCreateMenu.any { it.id == android.R.id.shareText }
                        val cjkHasShare = cjkCreateMenu.any { it.id == android.R.id.shareText }
                        assertEquals(
                            nativeHasShare,
                            cjkHasShare,
                            "Share availability must follow the native TextView on $locale",
                        )
                        assertEquals(
                            nativeCreateMenu.defaultItems(),
                            cjkCreateMenu.defaultItems(),
                            "CjkTextView must replay the native default menu before the custom callback and PROCESS_TEXT for $locale",
                        )

                        val nativeMenu = nativeCallback.finalItems()
                        val cjkMenu = cjkCallback.finalItems()
                        assertEquals(
                            nativeMenu.processTextItems(),
                            cjkMenu.processTextItems(),
                            "PROCESS_TEXT availability and labels must match native TextView for $locale",
                        )
                        assertEquals(
                            nativeMenu.defaultItems().map { it.id to it.title },
                            cjkMenu.defaultItems().map { it.id to it.title },
                            "host locale must localize CjkTextView labels exactly like TextView for $locale",
                        )
                        assertEquals(
                            nativeMenu.defaultItems().map { it.id to it.order },
                            cjkMenu.defaultItems().map { it.id to it.order },
                            "default menu item order must match TextView for $locale",
                        )
                        assertEquals(
                            expectedDefaultLabels(locale, nativeHasShare),
                            nativeCreateMenu.defaultItems().associate { it.id to it.title },
                            "native TextView must resolve default labels from the host locale",
                        )
                        cjkCallback.mode?.finish()
                    }
                }
            } finally {
                CjkTextViewTestActivity.localeOverride = null
            }
        }
    }

    @Test
    fun customSelectionActionModeCallbackDecoratesDefaultsAndCanBeRemoved() {
        lateinit var view: CjkTextView
        lateinit var point: Pair<Float, Float>
        val callback = RecordingSelectionActionModeCallback(
            addCustomItem = true,
            customItemId = HOST_ACTION_ID,
        )
        ActivityScenario.launch(CjkTextViewTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                view = CjkTextView(activity).apply {
                    content = CjkTextContent("alpha beta", TextStyle(fontSize = 42f))
                    customSelectionActionModeCallback = callback
                }
                activity.setContentView(view)
                measureAndLayout(view, 360)
                point = visibleCenter(view, TextRange(0, 1))
                assertSame(callback, view.customSelectionActionModeCallback)
            }
            completeLongPress(scenario, view, point)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            scenario.onActivity {
                val mode = assertNotNull(callback.mode, "custom callback must observe ActionMode")
                assertDefaultSelectionItems(callback.finalItems(), "CjkTextView custom callback")
                assertTrue(
                    callback.createItems.any { it.id == android.R.id.copy },
                    "onCreate must see native default items before customization",
                )
                assertTrue(mode.menu.findItem(HOST_ACTION_ID) != null)
                assertTrue(mode.menu.performIdentifierAction(HOST_ACTION_ID, 0))
                assertEquals(1, callback.customActionClicks)
                assertEquals(listOf(HOST_ACTION_ID), callback.clickedIds)
                assertTrue(callback.events.indexOfFirst { it == "create" } >= 0)
                assertTrue(callback.events.indexOfFirst { it == "prepare" } > callback.events.indexOfFirst { it == "create" })
                assertTrue(callback.events.lastIndexOf("action:$HOST_ACTION_ID") > callback.events.indexOfFirst { it == "prepare" })
                mode.finish()

                view.customSelectionActionModeCallback = null
                assertNull(view.customSelectionActionModeCallback)
                view.clearSelection()
                point = visibleCenter(view, TextRange(0, 1))
            }

            completeLongPress(scenario, view, point)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                val mode = assertNotNull(
                    actionModeForTesting(view),
                    "removing custom callback must restore the default ActionMode",
                )
                assertNull(mode.menu.findItem(HOST_ACTION_ID))
                assertDefaultSelectionItems(menuItems(mode.menu), "CjkTextView after callback removal")
                mode.finish()
            }
        }
    }

    @Test
    fun customSelectionActionModeCallbackCanRejectCreationWithoutFallback() {
        lateinit var view: CjkTextView
        lateinit var point: Pair<Float, Float>
        val callback = RecordingSelectionActionModeCallback(allowCreate = false)
        ActivityScenario.launch(CjkTextViewTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                view = CjkTextView(activity).apply {
                    content = CjkTextContent("alpha beta", TextStyle(fontSize = 42f))
                    customSelectionActionModeCallback = callback
                }
                activity.setContentView(view)
                measureAndLayout(view, 360)
                point = visibleCenter(view, TextRange(0, 1))
            }
            completeLongPress(scenario, view, point)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                assertEquals(1, callback.createCalls)
                assertNull(actionModeForTesting(view), "false onCreate must reject ActionMode creation")
                assertNull(view.selection, "rejected TextView selection mode collapses to a caret")
                assertTrue(callback.events.firstOrNull() == "create")
                assertFalse(callback.events.any { it == "prepare" })
            }
        }
    }

    @Test
    fun defaultCopyAndSelectAllRemainFrontendHandledWhenCallbackDeclines() {
        lateinit var view: CjkTextView
        lateinit var point: Pair<Float, Float>
        val source = "alpha beta"
        val callback = RecordingSelectionActionModeCallback()
        ActivityScenario.launch(CjkTextViewTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                view = CjkTextView(activity).apply {
                    content = CjkTextContent(source, TextStyle(fontSize = 42f))
                    customSelectionActionModeCallback = callback
                }
                activity.setContentView(view)
                measureAndLayout(view, 360)
                point = visibleCenter(view, TextRange(0, 1))
            }
            completeLongPress(scenario, view, point)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                val mode = assertNotNull(callback.mode)
                assertTrue(mode.menu.performIdentifierAction(android.R.id.copy, 0))
                val clipboard = view.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                assertEquals("alpha", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
                mode.finish()
                view.clearSelection()
                point = visibleCenter(view, TextRange(0, 1))
            }

            completeLongPress(scenario, view, point)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                val mode = assertNotNull(callback.mode)
                assertTrue(mode.menu.performIdentifierAction(android.R.id.selectAll, 0))
                assertEquals(TextRange(0, source.length), view.selection)
                mode.finish()
            }
        }
    }

}
