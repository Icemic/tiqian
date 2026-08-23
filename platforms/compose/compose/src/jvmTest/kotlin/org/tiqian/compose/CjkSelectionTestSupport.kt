@file:Suppress("DEPRECATION")
@file:OptIn(ExperimentalComposeUiApi::class)

package org.tiqian.compose

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import org.tiqian.core.LayoutResult
import org.tiqian.core.getCursorRect
import org.tiqian.core.getLineForOffset

internal fun cursor(result: LayoutResult, offset: Int): Offset {
    val caret = result.getCursorRect(offset)
    val line = result.lines[result.getLineForOffset(offset)]
    return Offset(caret.left, (line.top + line.bottom) / 2f)
}

internal fun drag(scene: ImageComposeScene, start: Offset, end: Offset, startTime: Long) {
    val pressed = PointerButtons(isPrimaryPressed = true)
    scene.sendPointerEvent(
        PointerEventType.Press, start, timeMillis = startTime,
        buttons = pressed, button = PointerButton.Primary,
    )
    scene.sendPointerEvent(
        PointerEventType.Move, end, timeMillis = startTime + 50,
        buttons = pressed,
    )
    scene.sendPointerEvent(
        PointerEventType.Release, end, timeMillis = startTime + 100,
        buttons = PointerButtons(), button = PointerButton.Primary,
    )
}

internal fun tap(
    scene: ImageComposeScene,
    position: Offset,
    pressTime: Long,
    releaseTime: Long,
) {
    scene.sendPointerEvent(
        PointerEventType.Press, position, timeMillis = pressTime,
        buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary,
    )
    scene.sendPointerEvent(
        PointerEventType.Release, position, timeMillis = releaseTime,
        buttons = PointerButtons(), button = PointerButton.Primary,
    )
}
