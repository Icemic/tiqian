package org.tiqian.demo.macos

import kotlinx.cinterop.ExperimentalForeignApi
import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LayoutInput
import org.tiqian.core.LayoutResult
import org.tiqian.core.TextStyle
import org.tiqian.core.TiqianTextContent
import org.tiqian.coretext.CoreTextLayoutRenderer
import org.tiqian.coretext.appleParagraphEngine
import org.tiqian.shaping.coretext.CoreTextSupport
import platform.CoreGraphics.CGContextRef

/**
 * The single entry point a Swift/AppKit/UIKit/SwiftUI app needs to typeset CJK text with Tiqian's
 * Core Text pipeline. Construct once (it holds the engine + renderer, both of which cache font
 * handles), then either [layout] plain text into a [Paragraph], or [documentBuilder] to author a
 * rich, multi-block document (paragraphs / lists / indents / 拼音 / 注音 / decorations) fluently.
 *
 * Everything CLREQ — 避头尾 / 两端对齐 / 中西文间距 / 标点挤压 — runs inside layout, driven by real
 * PingFang SC measurements. The app owns the *content*; this kit owns the *typesetting*. The Swift
 * side only measures ([Paragraph.height] / [Document.height]) and draws.
 */
@OptIn(ExperimentalForeignApi::class)
class Typesetter(
    private val fontSize: Float = 18f,
    cjkFamily: String = CoreTextSupport.DEFAULT_CJK_FAMILY,
    latinFamily: String = CoreTextSupport.DEFAULT_LATIN_FAMILY,
) {
    // Same families to BOTH engine (measure) and renderer (draw) — measure==draw.
    private val engine = appleParagraphEngine(cjkFamily, latinFamily)
    private val renderer = CoreTextLayoutRenderer(cjkFamily, latinFamily)

    /** Lay plain [text] out into a column [width] engine-pixels wide. */
    fun layout(text: String, width: Float): Paragraph =
        Paragraph(
            engine.layout(
                LayoutInput(
                    content = TiqianTextContent(text),
                    textStyle = TextStyle(fontSize = fontSize),
                    constraints = LayoutConstraints(maxWidth = width),
                ),
            ),
            renderer,
        )

    /**
     * A fresh [DocBuilder] bound to this typesetter's engine + renderer + [fontSize]. Add
     * paragraphs / lists / section breaks fluently (the app supplies the content), then
     * `layout(width:)` to get a drawable [Document]. (Named `documentBuilder`, not
     * `newDocument`, because Kotlin/Native renames `new*` methods to `doNew*` in the ObjC export.)
     */
    fun documentBuilder(): DocBuilder = DocBuilder(engine::layout, renderer, fontSize)
}

/** One laid-out paragraph. Read [height] to size the view, then [draw] it. */
@OptIn(ExperimentalForeignApi::class)
class Paragraph internal constructor(
    private val result: LayoutResult,
    private val renderer: CoreTextLayoutRenderer,
) {
    /** Total laid-out height in engine pixels — use to size the view / scroll content. */
    val height: Double get() = result.size.height.toDouble()

    /** Total laid-out width in engine pixels. */
    val width: Double get() = result.size.width.toDouble()

    /** Number of lines the text broke into at the requested width. */
    val lineCount: Int get() = result.lines.size

    /**
     * Draw into [context]; [canvasHeight] is the CGContext's pixel height. The renderer flips
     * layout's top-left/y-down coordinates into Core Graphics' y-up space and inherits the
     * context's current fill color (set it before calling to control light/dark text color).
     *
     * [context] crosses the Kotlin/Native boundary as an opaque pointer (`CGContextRef` cannot
     * be exported as a typed Swift `CGContext`). Swift callers pass the raw pointer, e.g.
     * `paragraph.draw(context: Unmanaged.passUnretained(cgContext).toOpaque(), canvasHeight: h)`.
     * The pointer is used synchronously and never retained.
     */
    fun draw(context: CGContextRef, canvasHeight: Double) {
        renderer.draw(result, context, canvasHeight)
    }
}
