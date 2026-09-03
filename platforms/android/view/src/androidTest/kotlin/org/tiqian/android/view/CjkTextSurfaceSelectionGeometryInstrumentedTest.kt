package org.tiqian.android.view

import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.tiqian.core.TextStyle
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class CjkTextSurfaceSelectionGeometryInstrumentedTest {
    @Test
    fun selectedParagraphSeparatorExtendsHighlightAcrossBothLineEdges() {
        ActivityScenario.launch(CjkTextViewTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val firstContent = CjkTextContent("甲乙", TextStyle(fontSize = 40f))
                val secondContent = CjkTextContent("丙丁", TextStyle(fontSize = 40f))
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
                    addView(LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(
                            first,
                            LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            ),
                        )
                        addView(
                            second,
                            LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            ),
                        )
                    })
                }
                activity.setContentView(surface)
                measureAndLayout(surface, 360)

                assertTrue(surface.selectAll())
                val firstBox = first.currentSelectionBoxes.single()
                val secondBox = second.currentSelectionBoxes.single()
                val viewportWidth = (first.width - first.paddingLeft - first.paddingRight).toFloat()
                val firstLine = requireNotNull(first.layoutResult).lines.single()
                val secondLine = requireNotNull(second.layoutResult).lines.single()

                assertEquals(viewportWidth, firstBox.right)
                assertTrue(
                    firstBox.right > firstLine.indent + firstLine.visualWidth,
                    "the selected separator must paint the previous line tail",
                )
                assertEquals(0f, secondBox.left)
                assertTrue(
                    secondBox.left < secondLine.indent,
                    "the selected separator must paint the next paragraph's leading indent",
                )
            }
        }
    }
}
