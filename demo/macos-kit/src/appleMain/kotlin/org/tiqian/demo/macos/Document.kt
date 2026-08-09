package org.tiqian.demo.macos

import kotlinx.cinterop.ExperimentalForeignApi
import org.tiqian.core.ColorSpan
import org.tiqian.core.LayoutResult
import org.tiqian.core.TextSpan
import org.tiqian.coretext.CoreTextLayoutRenderer
import platform.CoreGraphics.CGContextRef
import platform.CoreGraphics.CGContextRestoreGState
import platform.CoreGraphics.CGContextSaveGState
import platform.CoreGraphics.CGContextTranslateCTM

/** One laid-out block placed at ([x], [yTop]) within a [Document] (layout px, y-down from doc top). */
internal class PlacedBlock(
    val result: LayoutResult,
    val spans: List<TextSpan>,
    val colorSpans: List<ColorSpan>,
    val x: Double,
    val yTop: Double,
)

/**
 * A laid-out, drawable multi-block document (paragraphs, lists, section breaks) — the result of
 * `DocBuilder.layout(width:)`. Each block is a real engine [LayoutResult]; the document only
 * stacks them (uniform cross-paragraph line spacing) and offsets list bodies into their marker
 * column. Read [height] to size the view, then [draw]. Content is authored by the app, not here.
 */
@OptIn(ExperimentalForeignApi::class)
class Document internal constructor(
    private val blocks: List<PlacedBlock>,
    val width: Double,
    val height: Double,
    private val renderer: CoreTextLayoutRenderer,
) {
    /** Number of laid-out blocks (paragraphs + list marker/body rows), for diagnostics/tests. */
    val blockCount: Int get() = blocks.size

    /**
     * The document's plain SOURCE text — every block's source text joined by newlines. The renderer
     * only paints glyphs, so the host view exposes this as its accessibility value (VoiceOver) to
     * keep the source/copy/accessibility fidelity the engine promises (AGENTS.md #4).
     */
    val text: String
        get() = blocks.joinToString("\n") { it.result.input.content.text }.trim()

    /**
     * Draw the whole document into [context]; [canvasHeight] is the CGContext pixel height with the
     * document's top mapped to it (the caller folds in any top inset, exactly as for a single
     * paragraph). Each block draws at its own `canvasHeight - yTop`, and list bodies translate the
     * context by their marker-column width.
     *
     * [context] crosses the Kotlin/Native boundary as an opaque pointer (`CGContextRef` cannot be a
     * typed Swift `CGContext`); Swift passes `Unmanaged.passUnretained(cgContext).toOpaque()`. The
     * renderer expects a y-up context and never sets a base fill color (it inherits the current one,
     * so light/dark text color is the caller's; colored spans override per cluster).
     */
    fun draw(context: CGContextRef, canvasHeight: Double) {
        for (b in blocks) {
            val blockCanvasHeight = canvasHeight - b.yTop
            if (blockCanvasHeight <= 0.0) continue
            if (b.x != 0.0) {
                CGContextSaveGState(context)
                CGContextTranslateCTM(context, b.x, 0.0)
                renderer.draw(b.result, context, blockCanvasHeight, b.spans, b.colorSpans)
                CGContextRestoreGState(context)
            } else {
                renderer.draw(b.result, context, blockCanvasHeight, b.spans, b.colorSpans)
            }
        }
    }
}
