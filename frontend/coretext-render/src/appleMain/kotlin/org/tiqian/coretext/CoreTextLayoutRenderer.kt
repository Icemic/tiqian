package org.tiqian.coretext

import kotlinx.cinterop.CValue
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cValue
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import org.tiqian.core.BopomofoGlyphRole
import org.tiqian.core.ColorSpan
import org.tiqian.core.LayoutResult
import org.tiqian.core.TextRange
import org.tiqian.core.TextSpan
import org.tiqian.core.TextStyle
import org.tiqian.core.positionedClusters
import org.tiqian.font.fontRoleNameUsesLatinFace
import org.tiqian.shaping.coretext.CoreTextSupport
import platform.CoreFoundation.CFArrayGetCount
import platform.CoreFoundation.CFArrayGetValueAtIndex
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.CoreGraphics.CGContextFillEllipseInRect
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGContextRef
import platform.CoreGraphics.CGContextRestoreGState
import platform.CoreGraphics.CGContextSaveGState
import platform.CoreGraphics.CGContextSetRGBFillColor
import platform.CoreGraphics.CGGlyphVar
import platform.CoreGraphics.CGPoint
import platform.CoreGraphics.CGRect
import platform.CoreText.CTFontDrawGlyphs
import platform.CoreText.CTFontGetBoundingRectsForGlyphs
import platform.CoreText.CTFontRef
import platform.CoreText.CTLineGetGlyphRuns
import platform.CoreText.CTLineGetTypographicBounds
import platform.CoreText.CTRunGetGlyphCount
import platform.CoreText.CTRunGetGlyphs
import platform.CoreText.CTRunGetPositions
import platform.CoreText.CTRunRef
import platform.CoreText.kCTFontOrientationHorizontal
import kotlin.math.PI
import kotlin.math.sin

/** 注音 ㄅㄆㄇ symbol baseline as a fraction of its box height (mirrors SkiaLayoutRenderer). */
private const val BOPOMOFO_SYMBOL_BASELINE_FACTOR = 0.88

/**
 * Draws a Tiqian [LayoutResult] into a `CGContext` via `CTFontDrawGlyphs`.
 *
 * Base text is drawn by re-shaping each *positioned* cluster (post-line-break) with the
 * same CTFont the engine measured — the Core Text peer of what `SkiaLayoutRenderer` does.
 * Positions come from [positionedClusters] (per-cluster `drawX` + line baseline), NOT from
 * the flat pre-line-break glyph runs. On top of the base run it paints the engine-computed
 * decoration geometry: 着重号 dots, 专名号/书名号/示亡号 lines, and the shape-once 拼音 /
 * 注音 annotation glyphs (`RubyDecisionInfo.glyphs` / `BopomofoGlyphPlacement.glyphs`).
 *
 * Rich text (ADR 0030): [draw] takes the same per-span [TextSpan] styles and render-only
 * [ColorSpan] colors the Skia frontend does. Each base cluster is drawn with the font its span
 * asks for (family / size / weight / synthetic-oblique italic) — the same face the shaper
 * measured — and clusters inside a [ColorSpan] are painted in that color; everything else
 * inherits the context's fill color. 拼音 ruby is drawn at its engine-computed [RubyDecisionInfo.fontWeight].
 *
 * Fonts are borrowed from [CoreTextSupport]'s process cache (never CFRelease'd here) — the
 * same cache the shaper and metrics resolver use, so `measure == draw` (AGENTS.md #5) holds
 * and the 注音 hot path no longer rebuilds a `CTFontDescriptor`/`CTFont` per glyph.
 *
 * Coordinates: `LayoutResult` is top-left / y-down; the CGContext is native bottom-left /
 * y-up, so a layout y maps to `canvasHeight - y`. Glyphs are drawn with the default text
 * matrix (upright in y-up). The renderer does NOT set a base fill color — it inherits the
 * context's current fill color, so callers control light/dark text color.
 */
class CoreTextLayoutRenderer(
    private val cjkFamily: String = CoreTextSupport.DEFAULT_CJK_FAMILY,
    private val latinFamily: String = CoreTextSupport.DEFAULT_LATIN_FAMILY,
) {
    fun draw(
        result: LayoutResult,
        context: CGContextRef,
        canvasHeight: Double,
        spans: List<TextSpan> = emptyList(),
        colorSpans: List<ColorSpan> = emptyList(),
    ) {
        if (canvasHeight <= 0.0) return
        val fontSize = result.input.textStyle.fontSize.toDouble()
        drawBaseText(result, context, canvasHeight, spans, colorSpans)
        drawLineEndHyphens(result, context, canvasHeight)
        drawEmphasisDots(result, context, canvasHeight)
        drawDecorationSegments(result, context, canvasHeight, fontSize)
        drawRuby(result, context, canvasHeight)
        drawBopomofo(result, context, canvasHeight)
    }

    // --- base text -----------------------------------------------------------

    private fun drawBaseText(
        result: LayoutResult,
        context: CGContextRef,
        canvasHeight: Double,
        spans: List<TextSpan>,
        colorSpans: List<ColorSpan>,
    ) {
        val baseStyle = result.input.textStyle
        for (pc in result.positionedClusters()) {
            val cluster = result.clusters.getOrNull(pc.clusterIndex) ?: continue
            if (cluster.displayText.isEmpty()) continue
            // Per-span style (rich text): the face the shaper measured this run with — family from
            // the span (else the role default), plus weight + synthetic-oblique italic.
            val style = styleAt(spans, cluster.range.start) ?: baseStyle
            // Latin-vs-CJK face from the engine's authoritative font decision + the shared
            // `usesLatinFace` rule (font/FontPolicy.kt) — the SAME rule shaping and metrics use — so
            // the drawn face can't drift from the measured one (a .notdef sized in the wrong face
            // would overflow its slot). Parsing `fontKey` would re-implement that rule and risk drift.
            val isLatin = result.usesLatinFaceAt(cluster.range)
            val family = style.fontFamilies.firstOrNull() ?: if (isLatin) latinFamily else cjkFamily
            val font = CoreTextSupport.font(family, style.fontSize.toDouble(), style.fontWeight, style.italic) ?: continue
            val baselineY = pc.baseline + cluster.baselineShift
            val argb = colorAt(colorSpans, cluster.range.start)
            if (argb != null) {
                CGContextSaveGState(context)
                setFill(context, argb)
                drawShapedText(context, font, cluster.displayText, pc.drawX.toDouble(), baselineY.toDouble(), canvasHeight, language = style.locale)
                CGContextRestoreGState(context)
            } else {
                drawShapedText(context, font, cluster.displayText, pc.drawX.toDouble(), baselineY.toDouble(), canvasHeight, language = style.locale)
            }
        }
    }

    /**
     * Draws the synthetic line-end hyphen for Western auto-hyphenation. The engine reserves
     * [LineBox.hyphenAdvance] just past `indent + visualWidth` (ADR 0029) but leaves drawing to the
     * frontend; without this, a hyphenated word breaks with hyphen geometry yet shows no hyphen. Drawn
     * by re-shaping "-" (the same character the engine measured) in the Latin face at the body size.
     */
    private fun drawLineEndHyphens(result: LayoutResult, context: CGContextRef, canvasHeight: Double) {
        val baseStyle = result.input.textStyle
        val font = CoreTextSupport.font(latinFamily, baseStyle.fontSize.toDouble(), baseStyle.fontWeight, baseStyle.italic)
            ?: return
        for (line in result.lines) {
            if (line.hyphenAdvance <= 0f) continue
            drawShapedText(context, font, "-", (line.indent + line.visualWidth).toDouble(), line.baseline.toDouble(), canvasHeight)
        }
    }

    /** The last [TextSpan] whose source range covers [offset] (later spans win), or null. */
    private fun styleAt(spans: List<TextSpan>, offset: Int): TextStyle? =
        spans.lastOrNull { offset >= it.range.start && offset < it.range.end }?.style

    /** The last [ColorSpan] (ARGB) covering [offset], or null to inherit the context fill color. */
    private fun colorAt(colorSpans: List<ColorSpan>, offset: Int): Int? =
        colorSpans.lastOrNull { offset >= it.start && offset < it.end }?.argb

    /**
     * Whether the cluster at [range] draws in the Latin face, from the engine's own font decision
     * (`debug.fontDecisions`) resolved through the shared `usesLatinFace` rule — the same source and
     * rule the shaper and metrics use. Mirrors the Compose frontend (`LayoutResultReplayIndex`).
     */
    private fun LayoutResult.usesLatinFaceAt(range: TextRange): Boolean {
        val role = debug.fontDecisions
            .firstOrNull { range.start >= it.range.start && range.end <= it.range.end }
            ?.role
        return fontRoleNameUsesLatinFace(role)
    }

    private fun setFill(context: CGContextRef, argb: Int) {
        val a = ((argb ushr 24) and 0xFF) / 255.0
        val r = ((argb ushr 16) and 0xFF) / 255.0
        val g = ((argb ushr 8) and 0xFF) / 255.0
        val b = (argb and 0xFF) / 255.0
        CGContextSetRGBFillColor(context, r, g, b, a)
    }

    /**
     * Shape [text] with [font] and draw it with its glyph origin at ([penX], baseline
     * [baselineY] in layout space). Uses each Core Text run's own font so a character the
     * base font lacks still draws with the fallback face Core Text selected.
     */
    private fun drawShapedText(
        context: CGContextRef,
        font: CTFontRef,
        text: String,
        penX: Double,
        baselineY: Double,
        canvasHeight: Double,
        vertical: Boolean = false,
        language: String? = null,
    ) {
        val penYUp = canvasHeight - baselineY
        // Borrowed from the shared shaping cache — the SAME `line()` the shaper measured with, keyed by
        // [language] too, so replay selects the same `locl` glyphs it measured (measure == draw). Do
        // NOT CFRelease — the cache owns it, and the line is replayed cheaply on every repaint.
        val line = CoreTextSupport.line(text, font, vertical, language) ?: return
        val runs = CTLineGetGlyphRuns(line) ?: return
        val runCount = CFArrayGetCount(runs).toInt()
        for (r in 0 until runCount) {
            val run: CTRunRef = CFArrayGetValueAtIndex(runs, r.convert())!!.reinterpret()
            drawRun(context, run, font, penX, penYUp)
        }
    }

    private fun drawRun(context: CGContextRef, run: CTRunRef, fallbackFont: CTFontRef, penX: Double, penYUp: Double) {
        val n = CTRunGetGlyphCount(run).toInt()
        if (n <= 0) return
        val runFont = CoreTextSupport.runFontOf(run) ?: fallbackFont
        memScoped {
            val glyphs = allocArray<CGGlyphVar>(n)
            val src = allocArray<CGPoint>(n)
            CTRunGetGlyphs(run, CoreTextSupport.cfRange(0, 0), glyphs)
            CTRunGetPositions(run, CoreTextSupport.cfRange(0, 0), src)
            val dst = allocArray<CGPoint>(n)
            for (i in 0 until n) {
                // CTRun positions are already in Core Graphics (y-up, baseline-relative) space.
                dst[i].x = penX + src[i].x
                dst[i].y = penYUp + src[i].y
            }
            CTFontDrawGlyphs(runFont, glyphs, dst, n.convert(), context)
        }
    }

    // --- annotation drawing (拼音 / 注音) ------------------------------------

    private fun drawRuby(result: LayoutResult, context: CGContextRef, canvasHeight: Double) {
        for (ruby in result.debug.rubyDecisions) {
            if (ruby.text.isEmpty()) continue
            // Re-shape 拼音 with the resolved font (run's own face) rather than replaying the
            // engine's stored glyph ids: replaying ids against a fresh family font misdraws
            // when the reading triggered a Core Text fallback face — the same protection the
            // 注音 path uses (its ids would otherwise belong to a different face). Weight is the
            // engine-computed 注文字重 (heavier than 基文 at small sizes for legibility, ADR 0032) —
            // the 拼音 peer of what the 注音 path already does. Font is borrowed — do NOT CFRelease.
            val font = CoreTextSupport.font(ruby.fontFamilies.firstOrNull() ?: latinFamily, ruby.fontSize.toDouble(), ruby.fontWeight) ?: continue
            drawShapedText(context, font, ruby.text, (ruby.centerX - ruby.width / 2f).toDouble(), ruby.baselineY.toDouble(), canvasHeight)
        }
    }

    /**
     * 注音 (ADR 0033) — the engine's pre-shaped glyphs are horizontal, so the renderer
     * re-shapes each placement with the font's VERTICAL forms (`vert`) into the box the
     * engine placed, per role (mirrors `SkiaLayoutRenderer`). Measurement showed locale /
     * SC-vs-TC don't change these glyphs; only vertical forms do.
     */
    private fun drawBopomofo(result: LayoutResult, context: CGContextRef, canvasHeight: Double) {
        for (z in result.debug.bopomofoDecisions) {
            val family = z.fontFamilies.firstOrNull() ?: cjkFamily
            val weight = z.fontWeight // 注文字重 = 基文 + 300 (engine-computed, BopomofoLegibilityWeightBoost)
            for (p in z.placements) {
                if (p.text.isEmpty()) continue
                val left = p.left.toDouble()
                val top = p.top.toDouble()
                val boxW = p.width.toDouble()
                val boxH = p.height.toDouble()
                when (p.role) {
                    // ㄅㄆㄇ: vertical form at the box size, horizontally centred by its advance.
                    BopomofoGlyphRole.Symbol -> {
                        val m = measureFirstVert(family, boxH, weight, p.text) ?: continue
                        drawGlyphWithFont(context, m.runFont, m.id, left + (boxW - m.advance) / 2.0, top + boxH * BOPOMOFO_SYMBOL_BASELINE_FACTOR, canvasHeight)
                        CFRelease(m.runFont)
                    }
                    // 调号: its ink is tiny at box size — scale so ink WIDTH fills the box, ink-centre it.
                    BopomofoGlyphRole.Tone -> {
                        val ref = measureFirstVert(family, boxH, weight, p.text) ?: continue
                        val inkWidth = ref.inkWidth
                        CFRelease(ref.runFont)
                        if (inkWidth <= 0.0) continue
                        val drawSize = boxH * (boxW / inkWidth)
                        val m = measureFirstVert(family, drawSize, weight, p.text) ?: continue
                        drawGlyphWithFont(context, m.runFont, m.id, left - m.inkLeft, top + boxH / 2.0 + (m.inkBottom + m.inkHeight / 2.0), canvasHeight)
                        CFRelease(m.runFont)
                    }
                    // 轻声: full-width vertical form at the column width, ink vertically centred.
                    BopomofoGlyphRole.Neutral -> {
                        val m = measureFirstVert(family, boxW, weight, p.text) ?: continue
                        drawGlyphWithFont(context, m.runFont, m.id, left + (boxW - m.advance) / 2.0, top + boxH / 2.0 + (m.inkBottom + m.inkHeight / 2.0), canvasHeight)
                        CFRelease(m.runFont)
                    }
                }
            }
        }
    }

    private class VertGlyph(
        val runFont: CTFontRef,
        val id: UShort,
        val inkLeft: Double,
        val inkBottom: Double,
        val inkWidth: Double,
        val inkHeight: Double,
        val advance: Double,
    )

    /**
     * Shape [text] with vertical forms at [size] and [weight]. Returns the first glyph's id +
     * ink bounds (CG y-up, baseline-relative) + advance, plus the RUN'S OWN font (retained —
     * caller must [CFRelease] it). The measuring font is borrowed from [CoreTextSupport]'s cache
     * and is NOT released here; the run's font is retained so it survives `CFRelease(line)`, and
     * the caller's balancing `CFRelease` returns a cached run-font to the cache's own count (or
     * drops the extra ref on a fallback face). 注音 vert forms often resolve from a fallback face,
     * so the glyph MUST be drawn with that face, not a fresh family font (else the id maps to garbage).
     */
    private fun measureFirstVert(family: String, size: Double, weight: Int, text: String): VertGlyph? {
        // Font + shaped (vertical-forms) line both borrowed from the shared caches — do NOT CFRelease.
        val font = CoreTextSupport.font(family, size, weight) ?: return null
        val line = CoreTextSupport.line(text, font, vertical = true) ?: return null
        val advance = CTLineGetTypographicBounds(line, null, null, null)
        val runs = CTLineGetGlyphRuns(line) ?: return null
        if (CFArrayGetCount(runs).toInt() == 0) return null
        val run: CTRunRef = CFArrayGetValueAtIndex(runs, 0.convert())!!.reinterpret()
        val n = CTRunGetGlyphCount(run).toInt()
        if (n <= 0) return null
        val runFont: CTFontRef = CoreTextSupport.runFontOf(run) ?: font
        CFRetain(runFont) // caller CFReleases; kept independent of the cached line's eviction
        return memScoped {
            val g = allocArray<CGGlyphVar>(n)
            val rects = allocArray<CGRect>(n)
            CTRunGetGlyphs(run, CoreTextSupport.cfRange(0, 0), g)
            CTFontGetBoundingRectsForGlyphs(runFont, kCTFontOrientationHorizontal, g, rects, n.convert())
            val r = rects[0]
            VertGlyph(runFont, g[0], r.origin.x, r.origin.y, r.size.width, r.size.height, advance)
        }
    }

    /** Draw a single glyph [id] with [font] (the run's own face) at ([penX], baseline [baselineY] in layout space). */
    private fun drawGlyphWithFont(context: CGContextRef, font: CTFontRef, id: UShort, penX: Double, baselineY: Double, canvasHeight: Double) {
        memScoped {
            val g = allocArray<CGGlyphVar>(1)
            g[0] = id
            val pt = allocArray<CGPoint>(1)
            pt[0].x = penX
            pt[0].y = canvasHeight - baselineY
            CTFontDrawGlyphs(font, g, pt, 1.convert(), context)
        }
    }

    // --- 着重号 / 专名号 / 书名号 / 示亡号 -----------------------------------

    private fun drawEmphasisDots(result: LayoutResult, context: CGContextRef, canvasHeight: Double) {
        for (d in result.debug.decorationDecisions) {
            if (!d.applied || d.dotDiameter <= 0f) continue
            val radius = d.dotDiameter.toDouble() / 2.0
            val cx = d.anchorX.toDouble()
            val cy = canvasHeight - d.anchorY.toDouble()
            CGContextFillEllipseInRect(context, rect(cx - radius, cy - radius, radius * 2, radius * 2))
        }
    }

    private fun drawDecorationSegments(result: LayoutResult, context: CGContextRef, canvasHeight: Double, fontSize: Double) {
        val stroke = (fontSize / 16.0).coerceAtLeast(1.0)
        for (s in result.debug.decorationSegments) {
            when (s.kind) {
                // 专名号: a straight underline.
                "ProperNoun" ->
                    hLine(context, s.left.toDouble(), s.right.toDouble(), s.top.toDouble(), stroke, canvasHeight)
                // 书名号甲式: a wavy underline (matches Compose) — NOT the same straight line as 专名号,
                // so the two decorations stay visually distinct instead of silently collapsing to one.
                "BookTitle" ->
                    wavyLine(context, s.left.toDouble(), s.right.toDouble(), s.top.toDouble(), stroke, canvasHeight, fontSize)
                // 示亡号: a frame; continuation edges (openStart/openEnd) stay undrawn.
                else -> {
                    hLine(context, s.left.toDouble(), s.right.toDouble(), s.top.toDouble(), stroke, canvasHeight)
                    hLine(context, s.left.toDouble(), s.right.toDouble(), s.bottom.toDouble(), stroke, canvasHeight)
                    if (!s.openStart) vLine(context, s.left.toDouble(), s.top.toDouble(), s.bottom.toDouble(), stroke, canvasHeight)
                    if (!s.openEnd) vLine(context, s.right.toDouble(), s.top.toDouble(), s.bottom.toDouble(), stroke, canvasHeight)
                }
            }
        }
    }

    private fun hLine(context: CGContextRef, left: Double, right: Double, y: Double, stroke: Double, canvasHeight: Double) {
        val cgY = canvasHeight - y
        CGContextFillRect(context, rect(left, cgY - stroke / 2.0, right - left, stroke))
    }

    /**
     * 书名号甲式 wavy underline. Filled as a run of small squares stepped along a sine curve so it uses
     * the inherited fill colour (like [hLine]/the dots), avoiding a separate stroke-colour state. The
     * amplitude/wavelength scale with the font size so the wave reads at any body size.
     */
    private fun wavyLine(context: CGContextRef, left: Double, right: Double, y: Double, stroke: Double, canvasHeight: Double, fontSize: Double) {
        val amplitude = (fontSize / 18.0).coerceAtLeast(1.0)
        val wavelength = (fontSize / 2.0).coerceAtLeast(4.0)
        val cgYBase = canvasHeight - y
        var x = left
        while (x <= right) {
            val cy = cgYBase + amplitude * sin((x - left) / wavelength * 2.0 * PI)
            CGContextFillRect(context, rect(x - stroke / 2.0, cy - stroke / 2.0, stroke, stroke))
            x += 1.0
        }
    }

    private fun vLine(context: CGContextRef, x: Double, top: Double, bottom: Double, stroke: Double, canvasHeight: Double) {
        val cgTop = canvasHeight - top
        val cgBottom = canvasHeight - bottom
        val lo = minOf(cgTop, cgBottom)
        CGContextFillRect(context, rect(x - stroke / 2.0, lo, stroke, maxOf(cgTop, cgBottom) - lo))
    }

    // --- helpers -------------------------------------------------------------

    private fun rect(x: Double, y: Double, w: Double, h: Double): CValue<CGRect> =
        cValue { origin.x = x; origin.y = y; size.width = w; size.height = h }
}
