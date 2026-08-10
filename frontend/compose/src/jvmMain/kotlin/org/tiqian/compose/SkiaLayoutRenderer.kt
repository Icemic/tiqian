package org.tiqian.compose

import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import org.tiqian.core.LayoutResult
import org.tiqian.core.RichTextRole
import org.tiqian.core.RichTextBackgroundDrawStyle
import org.tiqian.core.RichTextLinePattern
import org.tiqian.core.RichTextLineSegment
import org.tiqian.core.TextSpan
import org.tiqian.core.ColorSpan
import org.tiqian.core.richTextDecorationLineY
import org.tiqian.shaping.skia.SkiaSystemTypefaces
import org.tiqian.shaping.skia.drawTiqianGlyphsWithPositions
import org.tiqian.shaping.skia.lineInkSkipIntervalsWithPositions
import org.tiqian.shaping.skia.shapeTextBlob
import org.tiqian.shaping.skia.vertGlyphIds
import org.jetbrains.skia.Font
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.PaintStrokeCap
import org.jetbrains.skia.PathEffect
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RRect
import org.jetbrains.skia.shaper.Shaper
import kotlin.math.max
import kotlin.math.min

/**
 * Draws a [LayoutResult] onto the Compose desktop canvas. Pure presentation:
 * x stepping comes from cluster advances the ENGINE resolved; glyphs come
 * from the shared language-tagged blob path ([shapeTextBlob]) so forms match
 * what the engine measured. The cluster walk (autospace strips, line-edge
 * gap suppression) is the same contract the playground raster implements.
 */
internal actual fun ContentDrawScope.drawParagraph(
    result: LayoutResult,
    replayIndex: LayoutResultReplayIndex,
    color: Int,
    colorSpans: List<ColorSpan>,
    spans: List<TextSpan>,
    selectionBoxes: List<org.tiqian.core.Rect>,
    selectionColor: Int?,
) {
    val fontSize = result.input.textStyle.fontSize
    val cjkFont = Font(SkiaSystemTypefaces.cjk, fontSize)
    val latinFont = Font(SkiaSystemTypefaces.latin, fontSize)
    val paint = Paint().apply { this.color = color }
    val shaper = Shaper.makeShaperDrivenWrapper()

    drawIntoCanvas { canvas ->
        val skCanvas = canvas.skiaCanvas
        drawSkiaRichTextBackgrounds(skCanvas, replayIndex.richTextBackgroundSegments)
        if (selectionColor != null) {
            val selectionPaint = Paint().apply {
                mode = PaintMode.FILL
                this.color = selectionColor
            }
            for (box in selectionBoxes) {
                skCanvas.drawRect(
                    Rect.makeLTRB(box.left, box.top, box.right, box.bottom),
                    selectionPaint,
                )
            }
        }

        // Shared cluster-walk (shaping/skia) — same path the playground
        // raster uses, so the role-containment / leading-shift handling can't
        // drift between the two.
        drawTiqianGlyphsWithPositions(
            skCanvas,
            result,
            cjkFont,
            latinFont,
            paint,
            shaper,
            colorSpans = colorSpans,
            spans = spans,
            positionedClusters = replayIndex.positionedClusters,
            fontRoleByClusterRange = replayIndex.fontRoleByClusterRange,
        )
        drawSkiaRichTextLines(
            skCanvas,
            result,
            replayIndex,
            color,
            colorSpans,
            replayIndex.richTextDecorationSegments,
            spans,
            cjkFont,
            latinFont,
            shaper,
        )

        // Emphasis dots (ADR 0018): paint the engine-owned final diameter at
        // the engine-owned ink-centre anchor.
        val decorationPaint = Paint()
        for (dot in result.debug.decorationDecisions) {
            if (dot.applied && dot.dotDiameter > 0f) {
                decorationPaint.color = colorAt(dot.clusterRange.start, color, colorSpans)
                skCanvas.drawCircle(
                    dot.anchorX,
                    dot.anchorY,
                    dot.dotDiameter / 2f,
                    decorationPaint,
                )
            }
        }

        // Decoration segments (ADR 0018/0024): 示亡号 frames (continuation
        // edges stay undrawn), 专名号 straight underlines, 书名号甲式 wavy
        // underlines.
        if (result.debug.decorationSegments.isNotEmpty()) {
            val framePaint = Paint().apply {
                this.color = color
                mode = org.jetbrains.skia.PaintMode.STROKE
                strokeWidth = (fontSize / 16f).coerceAtLeast(1f)
            }
            // text-decoration-skip-ink (Compose lacks it): break the 行间线 around
            // any glyph ink that crosses it, via Skia's getIntercepts. For pure
            // CJK the line sits below the face → no intercepts → continuous; it
            // matters for Western descenders inside a 专名号/书名号 span.
            val skipBandPad = framePaint.strokeWidth.coerceAtLeast(1f)
            val skipClearance = browserLikeSkipInkClearance(fontSize, framePaint.strokeWidth)
            for (seg in result.debug.decorationSegments) {
                framePaint.color = colorAt(seg.sourceRange.start, color, colorSpans)
                when (seg.kind) {
                    "ProperNoun" -> {
                        drawSkiaStraightInterlinearLine(
                            canvas = skCanvas,
                            result = result,
                            replayIndex = replayIndex,
                            lineIndex = seg.lineIndex,
                            left = seg.left,
                            right = seg.right,
                            lineY = seg.top,
                            paint = framePaint,
                            skipBandPad = skipBandPad,
                            skipClearance = skipClearance,
                            spans = spans,
                            cjkFont = cjkFont,
                            latinFont = latinFont,
                            shaper = shaper,
                        )
                    }
                    "BookTitle" -> {
                        val skips = result.lineInkSkipIntervalsWithPositions(
                            result.lines[seg.lineIndex],
                            cjkFont,
                            latinFont,
                            shaper,
                            seg.top - skipBandPad,
                            seg.top + skipBandPad,
                            spans,
                            replayIndex.positionedClusters,
                            replayIndex.fontRoleByClusterRange,
                        )
                        keptIntervals(seg.left, seg.right, skips, skipClearance) { x0, x1 ->
                            skCanvas.drawPath(org.tiqian.shaping.skia.wavyLinePath(x0, x1, seg.top, fontSize), framePaint)
                        }
                    }
                    else -> {
                        skCanvas.drawLine(seg.left, seg.top, seg.right, seg.top, framePaint)
                        skCanvas.drawLine(seg.left, seg.bottom, seg.right, seg.bottom, framePaint)
                        if (!seg.openStart) skCanvas.drawLine(seg.left, seg.top, seg.left, seg.bottom, framePaint)
                        if (!seg.openEnd) skCanvas.drawLine(seg.right, seg.top, seg.right, seg.bottom, framePaint)
                    }
                }
            }
        }

        // 行间注 (ruby, ADR 0032): 注文 shaped at its own size and centred over the
        // base x-span the engine computed. We measure the real注文 width here so a
        // 注文 wider than the base overhangs symmetrically (v1; 避让 is a follow-up).
        for (ruby in result.debug.rubyDecisions) {
            // 注文 uses its OWN font (注音 needs ㄅㄆㄇ glyphs; 拼音/释义 may differ) —
            // resolved via the shared resolver, defaulting to the Latin face.
            val rubyStyle = org.jetbrains.skia.FontStyle(ruby.fontWeight, org.jetbrains.skia.FontStyle.NORMAL.width, org.jetbrains.skia.FontSlant.UPRIGHT)
            val tf = SkiaSystemTypefaces.typeface(isLatin = true, family = ruby.fontFamilies.firstOrNull(), style = rubyStyle)
                ?: SkiaSystemTypefaces.latin
            val rubyFont = Font(tf, ruby.fontSize)
            val width = rubyFont.measureTextWidth(ruby.text)
            shapeTextBlob(shaper, ruby.text, rubyFont, ruby.locale)?.let { blob ->
                skCanvas.drawTextBlob(blob, ruby.centerX - width / 2f, ruby.baselineY, paint)
            }
        }

        // 注音 (ADR 0033): ㄅㄆㄇ symbols fill their 9×9 box; 调号 are ink-detected and
        // scaled so their ink WIDTH fills the box, then vertically centred. FORCED CJK
        // 注文 font (the optimized large tone glyphs live there, not in Western faces).
        for (z in result.debug.bopomofoDecisions) {
            val tf = (
                SkiaSystemTypefaces.typeface(
                    isLatin = false,
                    family = z.fontFamilies.firstOrNull(),
                    style = org.jetbrains.skia.FontStyle(z.fontWeight, org.jetbrains.skia.FontStyle.NORMAL.width, org.jetbrains.skia.FontSlant.UPRIGHT),
                )
                    ?: SkiaSystemTypefaces.cjk
                ) ?: continue
            for (p in z.placements) {
                when (p.role) {
                    org.tiqian.core.BopomofoGlyphRole.Symbol -> {
                        val f = Font(tf, p.height) // box height = symbol 字身框 (0.3em)
                        // Centre by the VERT glyph's advance (not the plain glyph's — they
                        // can differ, e.g. half- vs full-width), since we draw the vert form.
                        val gids = vertGlyphIds(tf, shaper, p.text, result.input.textStyle.locale)
                        val adv = if (gids.isEmpty()) f.measureTextWidth(p.text) else f.getWidths(gids).sum()
                        shapeTextBlob(shaper, p.text, f, result.input.textStyle.locale, vertical = true)?.let { blob ->
                            skCanvas.drawTextBlob(blob, p.left + (p.width - adv) / 2f, p.top + p.height * 0.88f, paint)
                        }
                    }
                    org.tiqian.core.BopomofoGlyphRole.Neutral -> {
                        // 轻声: full-width vert glyph at the COLUMN-WIDTH size (not scaled);
                        // h-centre by its vert advance, ink-position the dot into the box.
                        val gids = vertGlyphIds(tf, shaper, p.text, result.input.textStyle.locale)
                        if (gids.isEmpty()) continue
                        val f = Font(tf, p.width) // full-width em = column width (9 份)
                        val adv = f.getWidths(gids).sum()
                        val b = f.getBounds(gids).first()
                        shapeTextBlob(shaper, p.text, f, result.input.textStyle.locale, vertical = true)?.let { blob ->
                            val drawX = p.left + (p.width - adv) / 2f
                            val baselineY = p.top + p.height / 2f - (b.top + b.bottom) / 2f
                            skCanvas.drawTextBlob(blob, drawX, baselineY, paint)
                        }
                    }
                    org.tiqian.core.BopomofoGlyphRole.Tone -> {
                        // Ink-detect the `vert` glyph (the form actually drawn), so the
                        // scale-to-width + vertical-centre match what lands on screen.
                        val glyphs = vertGlyphIds(tf, shaper, p.text, result.input.textStyle.locale)
                        if (glyphs.isEmpty()) continue
                        val ref = Font(tf, p.height) // a reference size; rescale to ink width
                        val refBounds = ref.getBounds(glyphs).first()
                        if (refBounds.width <= 0f) continue
                        val scaled = Font(tf, p.height * (p.width / refBounds.width))
                        val b = scaled.getBounds(glyphs).first()
                        shapeTextBlob(shaper, p.text, scaled, result.input.textStyle.locale, vertical = true)?.let { blob ->
                            // ink left → box left; ink vertical centre → box vertical centre.
                            val drawX = p.left - b.left
                            val baselineY = p.top + p.height / 2f - (b.top + b.bottom) / 2f
                            skCanvas.drawTextBlob(blob, drawX, baselineY, paint)
                        }
                    }
                }
            }
        }
    }
}

private fun drawSkiaRichTextBackgrounds(
    canvas: org.jetbrains.skia.Canvas,
    segments: List<RichTextLineSegment>,
) {
    val paint = Paint()
    for (seg in segments) {
        val argb = when (seg.span.role) {
            RichTextRole.Background -> seg.span.paint.argb
            RichTextRole.InlineCode -> seg.span.paint.argb ?: INLINE_CODE_BACKGROUND_COLOR
            else -> null
        } ?: continue
        paint.color = argb
        val inset = when (val drawStyle = seg.span.paint.background.drawStyle) {
            RichTextBackgroundDrawStyle.Fill -> {
                paint.mode = PaintMode.FILL
                0f
            }
            is RichTextBackgroundDrawStyle.Border -> {
                paint.mode = PaintMode.STROKE
                paint.strokeWidth = drawStyle.strokeWidth
                drawStyle.strokeWidth / 2f
            }
        }
        val left = seg.left + inset
        val top = seg.top + inset
        val right = seg.right - inset
        val bottom = seg.bottom - inset
        if (right <= left || bottom <= top) continue
        val radius = minOf(
            (seg.span.paint.background.cornerRadius - inset).coerceAtLeast(0f),
            (right - left) / 2f,
            (bottom - top) / 2f,
        ).coerceAtLeast(0f)
        if (radius > 0f) {
            canvas.drawRRect(RRect.makeLTRB(left, top, right, bottom, radius), paint)
        } else {
            canvas.drawRect(Rect.makeLTRB(left, top, right, bottom), paint)
        }
    }
}

private fun drawSkiaRichTextLines(
    canvas: org.jetbrains.skia.Canvas,
    result: LayoutResult,
    replayIndex: LayoutResultReplayIndex,
    color: Int,
    colorSpans: List<ColorSpan>,
    segments: List<RichTextLineSegment>,
    spans: List<TextSpan>,
    cjkFont: Font,
    latinFont: Font,
    shaper: Shaper,
) {
    val paint = Paint().apply {
        mode = PaintMode.STROKE
    }
    // Skip-ink intervals re-shape the line's clusters — memoize per (line, band)
    // so several underline segments on one line pay for shaping once per draw.
    val skipCache = HashMap<Long, FloatArray>()
    for (seg in segments) {
        val role = seg.span.role
        if (role != RichTextRole.Underline && role != RichTextRole.LineThrough) continue
        when (val pattern = seg.span.paint.linePattern) {
            RichTextLinePattern.Solid -> {
                paint.strokeWidth = (result.input.textStyle.fontSize / 16f).coerceAtLeast(1f)
                paint.pathEffect = null
                paint.strokeCap = PaintStrokeCap.BUTT
            }
            is RichTextLinePattern.Dashed -> {
                paint.strokeWidth = pattern.strokeWidth
                paint.strokeCap = PaintStrokeCap.ROUND
                paint.pathEffect = PathEffect.makeDash(
                    floatArrayOf(pattern.dashLength, pattern.gapLength),
                    0f,
                )
            }
            is RichTextLinePattern.Dotted -> {
                paint.strokeWidth = pattern.dotDiameter
                paint.strokeCap = PaintStrokeCap.BUTT
                paint.pathEffect = null
            }
        }
        val skipBandPad = paint.strokeWidth.coerceAtLeast(1f)
        val style = spans.lastOrNull { seg.range.start >= it.range.start && seg.range.start < it.range.end }?.style
            ?: result.input.textStyle
        val lineY = result.richTextDecorationLineY(seg, paint.strokeWidth)
        paint.color = seg.span.paint.argb ?: colorAt(seg.range.start, color, colorSpans)
        if (role == RichTextRole.Underline) {
            drawSkiaStraightInterlinearLine(
                canvas = canvas,
                result = result,
                replayIndex = replayIndex,
                lineIndex = seg.lineIndex,
                left = seg.left,
                right = seg.right,
                lineY = lineY,
                paint = paint,
                skipBandPad = skipBandPad,
                skipClearance = browserLikeSkipInkClearance(style.fontSize, paint.strokeWidth),
                linePattern = seg.span.paint.linePattern,
                spans = spans,
                cjkFont = cjkFont,
                latinFont = latinFont,
                shaper = shaper,
                skipCache = skipCache,
            )
        } else {
            canvas.drawLine(seg.left, lineY, seg.right, lineY, paint)
        }
    }
    paint.pathEffect = null
    paint.strokeCap = PaintStrokeCap.BUTT
}

private fun drawSkiaStraightInterlinearLine(
    canvas: org.jetbrains.skia.Canvas,
    result: LayoutResult,
    replayIndex: LayoutResultReplayIndex,
    lineIndex: Int,
    left: Float,
    right: Float,
    lineY: Float,
    paint: Paint,
    skipBandPad: Float,
    skipClearance: Float,
    linePattern: RichTextLinePattern = RichTextLinePattern.Solid,
    spans: List<TextSpan>,
    cjkFont: Font,
    latinFont: Font,
    shaper: Shaper,
    skipCache: MutableMap<Long, FloatArray>? = null,
) {
    val line = result.lines.getOrNull(lineIndex) ?: return
    val cacheKey = (lineIndex.toLong() shl 32) xor
        (lineY.toRawBits().toLong() and 0xFFFFFFFFL) xor
        (skipBandPad.toRawBits().toLong() shl 1)
    val skips = skipCache?.getOrPut(cacheKey) {
        result.lineInkSkipIntervalsWithPositions(
            line,
            cjkFont,
            latinFont,
            shaper,
            lineY - skipBandPad,
            lineY + skipBandPad,
            spans,
            replayIndex.positionedClusters,
            replayIndex.fontRoleByClusterRange,
        )
    } ?: result.lineInkSkipIntervalsWithPositions(
        line,
        cjkFont,
        latinFont,
        shaper,
        lineY - skipBandPad,
        lineY + skipBandPad,
        spans,
        replayIndex.positionedClusters,
        replayIndex.fontRoleByClusterRange,
    )
    keptIntervals(left, right, skips, skipClearance) { x0, x1 ->
        when (linePattern) {
            RichTextLinePattern.Solid -> canvas.drawLine(x0, lineY, x1, lineY, paint)
            is RichTextLinePattern.Dashed -> {
                paint.pathEffect = null
                val saveCount = canvas.save()
                canvas.clipRect(x0, lineY - paint.strokeWidth, x1, lineY + paint.strokeWidth, false)
                val dashes = fittedDashedLineSegments(
                    spanLeft = left,
                    spanRight = right,
                    dashLength = linePattern.dashLength,
                    gapLength = linePattern.gapLength,
                )
                var index = 0
                while (index + 1 < dashes.size) {
                    val dashLeft = dashes[index]
                    val dashRight = dashes[index + 1]
                    if (dashRight > x0 && dashLeft < x1) {
                        val capInset = minOf(paint.strokeWidth / 2f, (dashRight - dashLeft) / 2f)
                        canvas.drawLine(
                            dashLeft + capInset,
                            lineY,
                            dashRight - capInset,
                            lineY,
                            paint,
                        )
                    }
                    index += 2
                }
                canvas.restoreToCount(saveCount)
            }
            is RichTextLinePattern.Dotted -> {
                paint.mode = PaintMode.FILL
                fittedDottedLineCenters(
                    spanLeft = left,
                    spanRight = right,
                    keptLeft = x0,
                    keptRight = x1,
                    dotDiameter = linePattern.dotDiameter,
                    gapLength = linePattern.gapLength,
                ).forEach { center ->
                    canvas.drawCircle(center, lineY, linePattern.dotDiameter / 2f, paint)
                }
                paint.mode = PaintMode.STROKE
            }
        }
    }
}

private fun colorAt(offset: Int, color: Int, colorSpans: List<ColorSpan>): Int =
    colorSpans.lastOrNull { offset >= it.start && offset < it.end }?.argb ?: color

/**
 * Draws the KEPT runs of `[left, right]` — i.e. the whole span minus the [skips]
 * intervals (flat `[s0,e0,…]`, each padded by [gap] and merged) — invoking
 * [draw] per kept run. This is the skip-ink break: ink intervals are the gaps.
 */
private inline fun keptIntervals(
    left: Float,
    right: Float,
    skips: FloatArray,
    gap: Float,
    draw: (Float, Float) -> Unit,
) {
    val merged = ArrayList<FloatArray>()
    var i = 0
    while (i + 1 < skips.size) {
        val s = (skips[i] - gap).coerceIn(left, right)
        val e = (skips[i + 1] + gap).coerceIn(left, right)
        if (e > s) merged += floatArrayOf(s, e)
        i += 2
    }
    merged.sortBy { it[0] }
    var cursor = left
    for (iv in merged) {
        if (iv[0] > cursor + 0.5f) draw(cursor, iv[0])
        cursor = maxOf(cursor, iv[1])
    }
    if (cursor < right - 0.5f) draw(cursor, right)
}

private const val INLINE_CODE_BACKGROUND_COLOR: Int = 0x1A000000
private const val BROWSER_LIKE_SKIP_INK_CLEARANCE_EM = 0.10f
private const val BROWSER_LIKE_SKIP_INK_CLEARANCE_MAX = 13f

private fun browserLikeSkipInkClearance(fontSize: Float, strokeWidth: Float): Float =
    min(max(strokeWidth, fontSize * BROWSER_LIKE_SKIP_INK_CLEARANCE_EM), BROWSER_LIKE_SKIP_INK_CLEARANCE_MAX)
