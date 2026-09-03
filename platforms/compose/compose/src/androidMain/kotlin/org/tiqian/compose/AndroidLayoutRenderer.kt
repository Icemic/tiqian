package org.tiqian.compose

import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import org.tiqian.android.rendering.AndroidParagraphRenderer
import org.tiqian.core.ColorSpan
import org.tiqian.core.LayoutResult
import org.tiqian.core.LayoutResultReplayIndex
import org.tiqian.core.Rect
import org.tiqian.core.TextSpan

private class ComposeAndroidParagraphDrawCache : ParagraphDrawCache {
    val renderer = AndroidParagraphRenderer()

    override fun invalidateGeometry() {
        renderer.invalidateGeometry()
    }

    override fun dispose() {
        renderer.close()
    }
}

internal actual fun createParagraphDrawCache(): ParagraphDrawCache = ComposeAndroidParagraphDrawCache()

internal actual fun ContentDrawScope.drawParagraph(
    result: LayoutResult,
    replayIndex: LayoutResultReplayIndex,
    color: Int,
    colorSpans: List<ColorSpan>,
    spans: List<TextSpan>,
    selectionBoxes: List<Rect>,
    selectionColor: Int?,
    drawCache: ParagraphDrawCache,
) {
    check(spans == result.input.content.spans) {
        "Android renderer spans must be the spans retained by LayoutResult"
    }
    val renderer = (drawCache as ComposeAndroidParagraphDrawCache).renderer
    drawIntoCanvas { canvas ->
        renderer.draw(
            canvas = canvas.nativeCanvas,
            result = result,
            replayIndex = replayIndex,
            color = color,
            colorSpans = colorSpans,
            selectionBoxes = selectionBoxes,
            selectionColor = selectionColor,
        )
    }
}
