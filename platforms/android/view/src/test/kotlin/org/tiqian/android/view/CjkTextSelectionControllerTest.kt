package org.tiqian.android.view

import kotlin.test.Test
import kotlin.test.assertEquals

class CjkTextSelectionControllerTest {
    @Test
    fun horizontalHandleHotspotsAlignWithCaret() {
        val cursorX = 100
        val drawableWidth = 80

        val startLeft = selectionHandleLeft(cursorX, drawableWidth, isStart = true)
        val endLeft = selectionHandleLeft(cursorX, drawableWidth, isStart = false)

        assertEquals(cursorX, startLeft + drawableWidth * 3 / 4)
        assertEquals(cursorX, endLeft + drawableWidth / 4)
        assertEquals(40, startLeft)
        assertEquals(80, endLeft)
    }
}
