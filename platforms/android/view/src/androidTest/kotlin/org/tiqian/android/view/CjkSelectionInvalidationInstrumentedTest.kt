package org.tiqian.android.view

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.tiqian.core.TextStyle
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class CjkSelectionInvalidationInstrumentedTest {
    @Test
    fun attachedOnlyDocumentOrderTracksChildRelayout() {
        ActivityScenario.launch(CjkTextViewTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val first = CjkTextView(activity).apply {
                    content = CjkTextContent("甲", TextStyle(fontSize = 32f))
                }
                val second = CjkTextView(activity).apply {
                    content = CjkTextContent("乙", TextStyle(fontSize = 32f))
                }
                val surface = CjkTextSurface(activity)
                surface.addView(first, paragraphParams(topMargin = 0))
                surface.addView(second, paragraphParams(topMargin = 100))
                activity.setContentView(surface)
                measureAndLayout(surface, 320)

                assertTrue(surface.selectAll())
                assertEquals("甲\n乙", surface.selectedText)
                surface.clearSelection()

                first.layoutParams = paragraphParams(topMargin = 100)
                second.layoutParams = paragraphParams(topMargin = 0)
                measureAndLayout(surface, 320)

                assertTrue(surface.selectAll())
                assertEquals("乙\n甲", surface.selectedText)
            }
        }
    }

    @Test
    fun paintOnlyContentChangeDoesNotReprojectDocumentSelection() {
        ActivityScenario.launch(CjkTextViewTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val content = CjkTextContent("甲乙丙丁", TextStyle(fontSize = 32f))
                val child = CjkTextView(activity).apply {
                    bindSelectionFragment("body", content)
                }
                val surface = CjkTextSurface(activity).apply {
                    document = CjkSelectionDocument(
                        listOf(CjkSelectionDocumentFragment("body", content)),
                    )
                    addView(child)
                }
                activity.setContentView(surface)
                measureAndLayout(surface, 320)
                assertTrue(child.setSelection(0, 2))

                val originalLayout = assertNotNull(child.layoutResult)
                val originalBoxes = selectionBoxes(child)
                child.content = child.content.copy(textColor = Color.RED)

                assertSame(originalLayout, child.layoutResult)
                assertSame(
                    originalBoxes,
                    selectionBoxes(child),
                    "paint-only state must not rebuild engine-owned selection geometry",
                )
            }
        }
    }

    @Test
    fun resolvedLayoutRefreshesStandaloneSelectionAtTheSameViewSize() {
        launchView("甲乙丙丁") { view ->
            measureExactly(view, width = 300, height = 180)
            assertTrue(view.requestFocus())
            assertTrue(view.setSelection(0, 2))
            val originalBoxes = selectionBoxes(view)

            view.content = view.content.copy(
                textStyle = view.content.textStyle.copy(
                    fontSize = view.content.textStyle.fontSize + 12f,
                ),
            )
            measureExactly(view, width = 300, height = 180)
            val refreshedBoxes = selectionBoxes(view)

            assertNotSame(originalBoxes, refreshedBoxes)
            assertNotEquals(originalBoxes, refreshedBoxes)
        }
    }

    private fun selectionBoxes(view: CjkTextView): List<org.tiqian.core.Rect> {
        val controller = assertNotNull(
            readPrivateField(view, "selectionController") as? CjkTextSelectionController,
        )
        return controller.boxes
    }

    private fun measureExactly(view: CjkTextView, width: Int, height: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
    }

    private fun paragraphParams(topMargin: Int) = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        this.topMargin = topMargin
    }
}
