package org.tiqian.compose

import android.annotation.TargetApi
import android.graphics.Paint
import android.graphics.DashPathEffect
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.text.TextPaint
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import org.tiqian.core.Glyph
import org.tiqian.core.Cluster
import org.tiqian.core.ColorSpan
import org.tiqian.core.DecorationKind
import org.tiqian.core.LayoutResult
import org.tiqian.core.LineBox
import org.tiqian.core.RichTextLineSegment
import org.tiqian.core.RichTextRole
import org.tiqian.core.RichTextBackgroundDrawStyle
import org.tiqian.core.RichTextLinePattern
import org.tiqian.core.TextSpan
import org.tiqian.core.TextStyle
import org.tiqian.core.BopomofoGlyphRole
import org.tiqian.core.richTextDecorationLineY
import org.tiqian.font.FontRole
import org.tiqian.shaping.android.AndroidPositionedGlyphFontRegistry
import org.tiqian.shaping.android.AndroidTypefaceResolver
import org.tiqian.shaping.android.SystemAndroidTypefaceResolver
import org.tiqian.shaping.nativefont.AndroidNativeGlyphReplay
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

private val AndroidRendererTypefaces by lazy(LazyThreadSafetyMode.PUBLICATION) {
    SystemAndroidTypefaceResolver()
}

private class AndroidParagraphDrawCache : ParagraphDrawCache {
    internal val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    internal val glyphPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    internal val hyphenPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    internal val richTextBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    internal val richTextLinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    internal val decorationFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    internal val decorationStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    internal val rubyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    internal val skipInkPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    internal val glyphReplayPath = Path()
    internal val skipInkPath = Path()
    internal val skipInkBounds = RectF()
    internal val skipInkIntervals = HashMap<Long, FloatArray>()
    internal val dashEffects = HashMap<RichTextLinePattern.Dashed, DashPathEffect>()
    internal val platformFonts31 = HashMap<String, Any?>()
    // Kept as Any so loading the cache itself remains safe on API 23-30, where Canvas.drawGlyphs
    // is unavailable. The API 31 helper creates and owns the typed batch lazily.
    internal var glyphBatch31: Any? = null

    override fun invalidateGeometry() {
        skipInkIntervals.clear()
    }

    override fun dispose() {
        skipInkIntervals.clear()
        dashEffects.clear()
        platformFonts31.clear()
        glyphBatch31 = null
    }
}

internal actual fun createParagraphDrawCache(): ParagraphDrawCache = AndroidParagraphDrawCache()

internal actual fun ContentDrawScope.drawParagraph(
    result: LayoutResult,
    replayIndex: LayoutResultReplayIndex,
    color: Int,
    colorSpans: List<ColorSpan>,
    spans: List<TextSpan>,
    selectionBoxes: List<org.tiqian.core.Rect>,
    selectionColor: Int?,
    drawCache: ParagraphDrawCache,
) {
    val drawCache = drawCache as AndroidParagraphDrawCache
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        drawAndroidRichTextBackgrounds(
            native, replayIndex.richTextBackgroundSegments, drawCache.richTextBackgroundPaint,
        )
        if (selectionColor != null) {
            val selectionPaint = drawCache.selectionPaint
            selectionPaint.style = Paint.Style.FILL
            selectionPaint.color = selectionColor
            for (box in selectionBoxes) {
                native.drawRect(box.left, box.top, box.right, box.bottom, selectionPaint)
            }
        }
        drawAndroidGlyphs(
            native, result, replayIndex, color, colorSpans, spans, AndroidRendererTypefaces,
            drawCache,
        )
        drawAndroidRichTextLines(
            native, result, replayIndex, color, colorSpans, replayIndex.richTextDecorationSegments,
            spans, AndroidRendererTypefaces, drawCache,
        )
        drawAndroidDecorations(
            native, result, replayIndex, color, colorSpans, spans, AndroidRendererTypefaces, drawCache,
        )
        drawAndroidRuby(
            native, result, color, AndroidRendererTypefaces, drawCache.rubyPaint, drawCache.glyphReplayPath,
        )
        drawAndroidBopomofo(native, result, color, AndroidRendererTypefaces)
    }
}

private fun drawAndroidGlyphs(
    canvas: android.graphics.Canvas,
    result: LayoutResult,
    replayIndex: LayoutResultReplayIndex,
    color: Int,
    colorSpans: List<ColorSpan>,
    spans: List<TextSpan>,
    typefaces: AndroidTypefaceResolver,
    drawCache: AndroidParagraphDrawCache,
) {
    val paint = drawCache.glyphPaint
    val glyphReplayPath = drawCache.glyphReplayPath
    paint.color = color
    paint.textLocale = Locale.forLanguageTag(result.input.textStyle.locale)
    if (Build.VERSION.SDK_INT >= 31) {
        drawAndroidPositionedClusters31(
            canvas = canvas,
            result = result,
            replayIndex = replayIndex,
            color = color,
            colorSpans = colorSpans,
            spans = spans,
            typefaces = typefaces,
            paint = paint,
            glyphReplayPath = glyphReplayPath,
            drawCache = drawCache,
        )
    } else {
        drawAndroidPositionedClustersWithPaths(
            canvas = canvas,
            result = result,
            replayIndex = replayIndex,
            color = color,
            colorSpans = colorSpans,
            spans = spans,
            typefaces = typefaces,
            paint = paint,
            glyphReplayPath = glyphReplayPath,
        )
    }

    val hyphenPaint = drawCache.hyphenPaint
    hyphenPaint.set(paint)
    hyphenPaint.color = color
    hyphenPaint.textSize = result.input.textStyle.fontSize
    hyphenPaint.typeface = result.input.textStyle.let { style ->
        typefaces.resolve(FontRole.LatinText, style.fontFamilies, style.fontWeight, style.italic)
    }
    for (line in result.lines) {
        if (line.hyphenAdvance > 0f) {
            val originX = line.indent + line.visualWidth
            hyphenPaint.isFakeBoldText = AndroidNativeGlyphReplay.requiresPlatformSyntheticBold(line.hyphenGlyphs)
            hyphenPaint.textSkewX = if (AndroidNativeGlyphReplay.usesSyntheticItalic(line.hyphenGlyphs)) -0.25f else 0f
            val platformDrawn = Build.VERSION.SDK_INT >= 31 &&
                drawPositionedGlyphs31(
                    canvas, line.hyphenGlyphs, originX, line.baseline, hyphenPaint, drawCache,
                )
            if (platformDrawn) continue

            // Native outline replay owns its synthetic italic transform and cannot accept
            // Android's fake-bold paint. Reset both before trying that exact-glyph path.
            hyphenPaint.isFakeBoldText = false
            hyphenPaint.textSkewX = 0f
            if (!AndroidNativeGlyphReplay.drawGlyphs(
                    canvas,
                    line.hyphenGlyphs,
                    originX,
                    line.baseline,
                    result.input.textStyle.fontSize,
                    hyphenPaint,
                    glyphReplayPath,
                )
            ) {
                requireNativeReplayDidNotFail(line.hyphenGlyphs)
                hyphenPaint.isFakeBoldText =
                    AndroidNativeGlyphReplay.requiresPlatformSyntheticBold(line.hyphenGlyphs)
                hyphenPaint.textSkewX =
                    if (AndroidNativeGlyphReplay.usesSyntheticItalic(line.hyphenGlyphs)) -0.25f else 0f
                drawContextShapedText(canvas, "-", originX, line.baseline, FontRole.LatinText, hyphenPaint)
            }
        }
    }
}

private fun drawAndroidPositionedClustersWithPaths(
    canvas: android.graphics.Canvas,
    result: LayoutResult,
    replayIndex: LayoutResultReplayIndex,
    color: Int,
    colorSpans: List<ColorSpan>,
    spans: List<TextSpan>,
    typefaces: AndroidTypefaceResolver,
    paint: TextPaint,
    glyphReplayPath: Path,
) {
    result.forEachAndroidPositionedCluster(replayIndex, spans) { _, cluster, drawX, baselineY, run ->
        prepareAndroidGlyphPaint(paint, cluster, run, color, colorSpans, typefaces)
        val glyphs = replayIndex.glyphsByClusterRange[cluster.range].orEmpty()
        if (AndroidNativeGlyphReplay.drawGlyphs(
                canvas, glyphs, drawX, baselineY, run.style.fontSize, paint, glyphReplayPath,
            )
        ) {
            return@forEachAndroidPositionedCluster
        }
        drawAndroidClusterFallback(canvas, cluster, glyphs, drawX, baselineY, run, paint)
    }
}

/**
 * AndroidPlatformGlyphBatch: API 31+ replays the exact LayoutResult glyph ids and absolute
 * placements through the retained platform Font, combining adjacent equal-style glyphs into one
 * Canvas.drawGlyphs call. A face without a retained platform Font falls back per cluster to the
 * API 23 FreeType-outline path, so this optimization cannot change font selection or layout truth.
 */
@TargetApi(31)
private fun drawAndroidPositionedClusters31(
    canvas: android.graphics.Canvas,
    result: LayoutResult,
    replayIndex: LayoutResultReplayIndex,
    color: Int,
    colorSpans: List<ColorSpan>,
    spans: List<TextSpan>,
    typefaces: AndroidTypefaceResolver,
    paint: TextPaint,
    glyphReplayPath: Path,
    drawCache: AndroidParagraphDrawCache,
) {
    val batch = (drawCache.glyphBatch31 as? AndroidGlyphBatch31)
        ?: AndroidGlyphBatch31().also { drawCache.glyphBatch31 = it }

    result.forEachAndroidPositionedCluster(replayIndex, spans) { _, cluster, drawX, baselineY, run ->
        prepareAndroidGlyphPaint(paint, cluster, run, color, colorSpans, typefaces)
        val glyphs = replayIndex.glyphsByClusterRange[cluster.range].orEmpty()
        val canUsePlatformGlyphs = glyphs.isNotEmpty() && glyphs.all { glyph ->
            platformFontFor(glyph, drawCache) != null
        }
        if (canUsePlatformGlyphs) {
            paint.isFakeBoldText = AndroidNativeGlyphReplay.requiresPlatformSyntheticBold(glyphs)
            paint.textSkewX = if (AndroidNativeGlyphReplay.usesSyntheticItalic(glyphs)) -0.25f else 0f
            for (glyph in glyphs) {
                batch.append(
                    canvas = canvas,
                    paint = paint,
                    font = checkNotNull(platformFontFor(glyph, drawCache)),
                    glyphId = glyph.id.toInt(),
                    x = drawX + glyph.x,
                    y = baselineY + glyph.y,
                )
            }
            return@forEachAndroidPositionedCluster
        }

        batch.flush(canvas, paint)
        // FreeType outline replay already applies the synthetic italic transform itself.
        paint.isFakeBoldText = false
        paint.textSkewX = 0f
        if (AndroidNativeGlyphReplay.drawGlyphs(
                canvas, glyphs, drawX, baselineY, run.style.fontSize, paint, glyphReplayPath,
            )
        ) {
            return@forEachAndroidPositionedCluster
        }
        drawAndroidClusterFallback(canvas, cluster, glyphs, drawX, baselineY, run, paint)
    }
    batch.flush(canvas, paint)
}

private fun prepareAndroidGlyphPaint(
    paint: TextPaint,
    cluster: Cluster,
    run: AndroidClusterRun,
    color: Int,
    colorSpans: List<ColorSpan>,
    typefaces: AndroidTypefaceResolver,
) {
    paint.color = colorSpans.lastOrNull {
        cluster.range.start >= it.start && cluster.range.start < it.end
    }?.argb ?: color
    paint.textSize = run.style.fontSize
    paint.typeface = typefaces.resolve(run.role, run.style.fontFamilies, run.style.fontWeight, run.style.italic)
    paint.fontFeatureSettings = null
    paint.isFakeBoldText = false
    paint.textSkewX = 0f
}

private fun drawAndroidClusterFallback(
    canvas: android.graphics.Canvas,
    cluster: Cluster,
    glyphs: List<Glyph>,
    drawX: Float,
    baselineY: Float,
    run: AndroidClusterRun,
    paint: TextPaint,
) {
    paint.isFakeBoldText = AndroidNativeGlyphReplay.requiresPlatformSyntheticBold(glyphs)
    paint.textSkewX = if (AndroidNativeGlyphReplay.usesSyntheticItalic(glyphs)) -0.25f else 0f
    requireNativeReplayDidNotFail(glyphs)
    // CjkPunctuation clusters need the full-buffer clipped draw (context GSUB);
    // plain 汉字 are context-independent and keep the cheaper sub-range draw.
    // Italic punctuation uses target-only drawing so context glyph overhang cannot leak.
    val clipToContext = run.role == FontRole.CjkPunctuation && !run.style.italic
    drawContextShapedText(canvas, cluster.displayText, drawX, baselineY, run.role, paint, clipToContext)
}

@TargetApi(31)
private fun platformFontFor(
    glyph: Glyph,
    drawCache: AndroidParagraphDrawCache,
): android.graphics.fonts.Font? {
    val key = glyph.renderFontKey ?: return null
    if (drawCache.platformFonts31.containsKey(key)) {
        return drawCache.platformFonts31[key] as? android.graphics.fonts.Font
    }
    val font = AndroidPositionedGlyphFontRegistry.fontFor(key)
        ?: AndroidNativeGlyphReplay.platformFontFor(key)
    drawCache.platformFonts31[key] = font
    return font
}

@TargetApi(31)
private class AndroidGlyphBatch31 {
    private var glyphIds = IntArray(32)
    private var positions = FloatArray(64)
    private var count = 0
    private var font: android.graphics.fonts.Font? = null
    private var color: Int = 0
    private var textSizeBits: Int = 0
    private var fakeBold: Boolean = false
    private var textSkewBits: Int = 0

    fun append(
        canvas: android.graphics.Canvas,
        paint: Paint,
        font: android.graphics.fonts.Font,
        glyphId: Int,
        x: Float,
        y: Float,
    ) {
        val sameRun = count > 0 &&
            this.font === font &&
            color == paint.color &&
            textSizeBits == paint.textSize.toRawBits() &&
            fakeBold == paint.isFakeBoldText &&
            textSkewBits == paint.textSkewX.toRawBits()
        if (!sameRun) {
            flush(canvas, paint)
            this.font = font
            color = paint.color
            textSizeBits = paint.textSize.toRawBits()
            fakeBold = paint.isFakeBoldText
            textSkewBits = paint.textSkewX.toRawBits()
        }
        ensureCapacity(count + 1)
        glyphIds[count] = glyphId
        positions[count * 2] = x
        positions[count * 2 + 1] = y
        count += 1
    }

    fun flush(canvas: android.graphics.Canvas, paint: Paint) {
        if (count == 0) return
        paint.color = color
        paint.textSize = Float.fromBits(textSizeBits)
        paint.isFakeBoldText = fakeBold
        paint.textSkewX = Float.fromBits(textSkewBits)
        canvas.drawGlyphs(glyphIds, 0, positions, 0, count, checkNotNull(font), paint)
        count = 0
        font = null
    }

    private fun ensureCapacity(required: Int) {
        if (required <= glyphIds.size) return
        val capacity = max(required, glyphIds.size * 2)
        glyphIds = glyphIds.copyOf(capacity)
        positions = positions.copyOf(capacity * 2)
    }
}

@TargetApi(31)
private fun drawPositionedGlyphs31(
    canvas: android.graphics.Canvas,
    glyphs: List<Glyph>,
    originX: Float,
    originY: Float,
    paint: Paint,
    drawCache: AndroidParagraphDrawCache,
): Boolean {
    if (glyphs.isEmpty()) return false
    val fonts = glyphs.map { glyph ->
        platformFontFor(glyph, drawCache) ?: return false
    }

    var start = 0
    while (start < glyphs.size) {
        val font = fonts[start]
        var end = start + 1
        while (end < glyphs.size && fonts[end] === font) {
            end += 1
        }
        val count = end - start
        val ids = IntArray(count) { index -> glyphs[start + index].id.toInt() }
        val positions = FloatArray(count * 2) { index ->
            val glyph = glyphs[start + index / 2]
            if (index % 2 == 0) originX + glyph.x else originY + glyph.y
        }
        canvas.drawGlyphs(ids, 0, positions, 0, count, font, paint)
        start = end
    }
    return true
}

private fun drawAndroidDecorations(
    canvas: android.graphics.Canvas,
    result: LayoutResult,
    replayIndex: LayoutResultReplayIndex,
    color: Int,
    colorSpans: List<ColorSpan>,
    spans: List<TextSpan>,
    typefaces: AndroidTypefaceResolver,
    drawCache: AndroidParagraphDrawCache,
) {
    val fontSize = result.input.textStyle.fontSize
    val fillPaint = drawCache.decorationFillPaint
    fillPaint.color = color
    fillPaint.style = Paint.Style.FILL
    for (dot in result.debug.decorationDecisions) {
        if (dot.applied && dot.dotDiameter > 0f) {
            fillPaint.color = colorAt(dot.clusterRange.start, color, colorSpans)
            canvas.drawCircle(dot.anchorX, dot.anchorY, dot.dotDiameter / 2f, fillPaint)
        }
    }

    if (result.debug.decorationSegments.isEmpty()) return
    val strokePaint = drawCache.decorationStrokePaint
    strokePaint.color = color
    strokePaint.style = Paint.Style.STROKE
    strokePaint.strokeWidth = (fontSize / 16f).coerceAtLeast(1f)
    val skipBandPad = strokePaint.strokeWidth.coerceAtLeast(1f)
    val skipClearance = browserLikeSkipInkClearance(fontSize, strokePaint.strokeWidth)
    for (seg in result.debug.decorationSegments) {
        strokePaint.color = colorAt(seg.sourceRange.start, color, colorSpans)
        when (seg.kind) {
            DecorationKind.ProperNoun.name -> {
                drawAndroidStraightInterlinearLine(
                    canvas = canvas,
                    result = result,
                    replayIndex = replayIndex,
                    lineIndex = seg.lineIndex,
                    left = seg.left,
                    right = seg.right,
                    lineY = seg.top,
                    paint = strokePaint,
                    skipBandPad = skipBandPad,
                    skipClearance = skipClearance,
                    spans = spans,
                    typefaces = typefaces,
                    skipCache = drawCache.skipInkIntervals,
                    skipInkPaint = drawCache.skipInkPaint,
                    skipInkPath = drawCache.skipInkPath,
                    skipInkBounds = drawCache.skipInkBounds,
                )
            }
            DecorationKind.BookTitle.name -> {
                val cacheKey = skipInkCacheKey(seg.lineIndex, seg.top, skipBandPad)
                val skips = drawCache.skipInkIntervals.getOrPut(cacheKey) {
                    result.androidLineInkSkipIntervals(
                        replayIndex,
                        result.lines[seg.lineIndex],
                        seg.top - skipBandPad,
                        seg.top + skipBandPad,
                        spans,
                        typefaces,
                        drawCache.skipInkPaint,
                        drawCache.skipInkPath,
                        drawCache.skipInkBounds,
                    )
                }
                keptIntervals(seg.left, seg.right, skips, skipClearance) { x0, x1 ->
                    canvas.drawPath(wavyLinePath(x0, x1, seg.top, fontSize), strokePaint)
                }
            }
            else -> {
                canvas.drawLine(seg.left, seg.top, seg.right, seg.top, strokePaint)
                canvas.drawLine(seg.left, seg.bottom, seg.right, seg.bottom, strokePaint)
                if (!seg.openStart) canvas.drawLine(seg.left, seg.top, seg.left, seg.bottom, strokePaint)
                if (!seg.openEnd) canvas.drawLine(seg.right, seg.top, seg.right, seg.bottom, strokePaint)
            }
        }
    }
}

private fun drawAndroidRichTextBackgrounds(
    canvas: android.graphics.Canvas,
    segments: List<RichTextLineSegment>,
    paint: Paint,
) {
    for (seg in segments) {
        val argb = when (seg.span.role) {
            RichTextRole.Background -> seg.span.paint.argb
            RichTextRole.InlineCode -> seg.span.paint.argb ?: INLINE_CODE_BACKGROUND_COLOR
            else -> null
        } ?: continue
        paint.color = argb
        val inset = when (val drawStyle = seg.span.paint.background.drawStyle) {
            RichTextBackgroundDrawStyle.Fill -> {
                paint.style = Paint.Style.FILL
                0f
            }
            is RichTextBackgroundDrawStyle.Border -> {
                paint.style = Paint.Style.STROKE
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
            canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint)
        } else {
            canvas.drawRect(left, top, right, bottom, paint)
        }
    }
}

private fun drawAndroidRichTextLines(
    canvas: android.graphics.Canvas,
    result: LayoutResult,
    replayIndex: LayoutResultReplayIndex,
    color: Int,
    colorSpans: List<ColorSpan>,
    segments: List<RichTextLineSegment>,
    spans: List<TextSpan>,
    typefaces: AndroidTypefaceResolver,
    drawCache: AndroidParagraphDrawCache,
) {
    val strokePaint = drawCache.richTextLinePaint
    strokePaint.style = Paint.Style.STROKE
    for (seg in segments) {
        val role = seg.span.role
        if (role != RichTextRole.Underline && role != RichTextRole.LineThrough) continue
        when (val pattern = seg.span.paint.linePattern) {
            RichTextLinePattern.Solid -> {
                strokePaint.strokeWidth = (result.input.textStyle.fontSize / 16f).coerceAtLeast(1f)
                strokePaint.pathEffect = null
                strokePaint.strokeCap = Paint.Cap.BUTT
            }
            is RichTextLinePattern.Dashed -> {
                strokePaint.strokeWidth = pattern.strokeWidth
                strokePaint.strokeCap = Paint.Cap.ROUND
                strokePaint.pathEffect = drawCache.dashEffects.getOrPut(pattern) {
                    DashPathEffect(floatArrayOf(pattern.dashLength, pattern.gapLength), 0f)
                }
            }
            is RichTextLinePattern.Dotted -> {
                strokePaint.strokeWidth = pattern.dotDiameter
                strokePaint.strokeCap = Paint.Cap.BUTT
                strokePaint.pathEffect = null
            }
        }
        val skipBandPad = strokePaint.strokeWidth.coerceAtLeast(1f)
        val style = spans.lastOrNull { seg.range.start >= it.range.start && seg.range.start < it.range.end }?.style
            ?: result.input.textStyle
        val lineY = result.richTextDecorationLineY(seg, strokePaint.strokeWidth)
        strokePaint.color = seg.span.paint.argb ?: colorAt(seg.range.start, color, colorSpans)
        if (role == RichTextRole.Underline) {
            drawAndroidStraightInterlinearLine(
                canvas = canvas,
                result = result,
                replayIndex = replayIndex,
                lineIndex = seg.lineIndex,
                left = seg.left,
                right = seg.right,
                lineY = lineY,
                paint = strokePaint,
                skipBandPad = skipBandPad,
                skipClearance = browserLikeSkipInkClearance(style.fontSize, strokePaint.strokeWidth),
                linePattern = seg.span.paint.linePattern,
                spans = spans,
                typefaces = typefaces,
                skipCache = drawCache.skipInkIntervals,
                skipInkPaint = drawCache.skipInkPaint,
                skipInkPath = drawCache.skipInkPath,
                skipInkBounds = drawCache.skipInkBounds,
            )
        } else {
            canvas.drawLine(seg.left, lineY, seg.right, lineY, strokePaint)
        }
    }
    strokePaint.pathEffect = null
    strokePaint.strokeCap = Paint.Cap.BUTT
}

private fun drawAndroidStraightInterlinearLine(
    canvas: android.graphics.Canvas,
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
    typefaces: AndroidTypefaceResolver,
    skipCache: MutableMap<Long, FloatArray>,
    skipInkPaint: TextPaint,
    skipInkPath: Path,
    skipInkBounds: RectF,
) {
    val line = result.lines.getOrNull(lineIndex) ?: return
    val cacheKey = skipInkCacheKey(lineIndex, lineY, skipBandPad)
    val skips = skipCache.getOrPut(cacheKey) {
        result.androidLineInkSkipIntervals(
            replayIndex, line, lineY - skipBandPad, lineY + skipBandPad, spans, typefaces,
            skipInkPaint, skipInkPath, skipInkBounds,
        )
    }
    keptIntervals(left, right, skips, skipClearance) { x0, x1 ->
        when (linePattern) {
            RichTextLinePattern.Solid -> canvas.drawLine(x0, lineY, x1, lineY, paint)
            is RichTextLinePattern.Dashed -> {
                paint.pathEffect = null
                val saveCount = canvas.save()
                canvas.clipRect(x0, lineY - paint.strokeWidth, x1, lineY + paint.strokeWidth)
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
                paint.style = Paint.Style.FILL
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
                paint.style = Paint.Style.STROKE
            }
        }
    }
}

private fun colorAt(offset: Int, color: Int, colorSpans: List<ColorSpan>): Int =
    colorSpans.lastOrNull { offset >= it.start && offset < it.end }?.argb ?: color

private fun drawAndroidRuby(
    canvas: android.graphics.Canvas,
    result: LayoutResult,
    color: Int,
    typefaces: AndroidTypefaceResolver,
    paint: TextPaint,
    glyphReplayPath: Path,
) {
    paint.color = color
    paint.textLocale = Locale.forLanguageTag(result.input.textStyle.locale)
    for (ruby in result.debug.rubyDecisions) {
        paint.textLocale = Locale.forLanguageTag(ruby.locale)
        paint.textSize = ruby.fontSize
        paint.fontFeatureSettings = null
        val originX = ruby.centerX - ruby.width / 2f
        if (!AndroidNativeGlyphReplay.drawGlyphs(
                canvas,
                ruby.glyphs,
                originX,
                ruby.baselineY,
                ruby.fontSize,
                paint,
                glyphReplayPath,
            )
        ) {
            requireNativeReplayDidNotFail(ruby.glyphs)
            paint.typeface = typefaces.resolve(FontRole.LatinText, ruby.fontFamilies, ruby.fontWeight, italic = false)
            val width = paint.measureText(ruby.text)
            drawContextShapedText(canvas, ruby.text, ruby.centerX - width / 2f, ruby.baselineY, FontRole.LatinText, paint)
        }
    }
}

private fun drawAndroidBopomofo(
    canvas: android.graphics.Canvas,
    result: LayoutResult,
    color: Int,
    typefaces: AndroidTypefaceResolver,
) {
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textLocale = Locale.forLanguageTag(result.input.textStyle.locale)
        fontFeatureSettings = "'vert' on"
    }
    val bounds = Rect()
    for (z in result.debug.bopomofoDecisions) {
        paint.typeface = typefaces.resolve(FontRole.CjkText, z.fontFamilies, z.fontWeight, italic = false)
        for (p in z.placements) {
            if (AndroidNativeGlyphReplay.drawGlyphs(
                    canvas,
                    p.glyphs,
                    p.drawX,
                    p.baselineY,
                    p.fontSize,
                    paint,
                )
            ) {
                continue
            }
            requireNativeReplayDidNotFail(p.glyphs)
            when (p.role) {
                BopomofoGlyphRole.Symbol -> {
                    // ㄅㄆㄇ are full-em CJK glyphs → sit on the 字身框 baseline (0.88),
                    // like body CJK; 轻声/声调 below ink-center because they are small marks.
                    paint.textSize = p.height
                    val drawX = p.left + (p.width - paint.measureText(p.text)) / 2f
                    canvas.drawTextRun(p.text, 0, p.text.length, 0, p.text.length, drawX, p.top + p.height * 0.88f, false, paint)
                }
                BopomofoGlyphRole.Neutral -> {
                    paint.textSize = p.width
                    paint.getTextBounds(p.text, 0, p.text.length, bounds)
                    val drawX = p.left + (p.width - paint.measureText(p.text)) / 2f
                    val baselineY = p.top + p.height / 2f - (bounds.top + bounds.bottom) / 2f
                    canvas.drawTextRun(p.text, 0, p.text.length, 0, p.text.length, drawX, baselineY, false, paint)
                }
                BopomofoGlyphRole.Tone -> {
                    paint.textSize = p.height
                    paint.getTextBounds(p.text, 0, p.text.length, bounds)
                    val scale = if (bounds.width() > 0) p.width / bounds.width() else 1f
                    paint.textSize = p.height * scale
                    paint.getTextBounds(p.text, 0, p.text.length, bounds)
                    val baselineY = p.top + p.height / 2f - (bounds.top + bounds.bottom) / 2f
                    canvas.drawTextRun(p.text, 0, p.text.length, 0, p.text.length, p.left - bounds.left, baselineY, false, paint)
                }
            }
        }
    }
}

private data class AndroidClusterRun(
    val role: FontRole,
    val style: TextStyle,
)

private inline fun LayoutResult.forEachAndroidPositionedCluster(
    replayIndex: LayoutResultReplayIndex,
    spans: List<TextSpan>,
    action: (line: LineBox, cluster: Cluster, drawX: Float, baselineY: Float, run: AndroidClusterRun) -> Unit,
) {
    val baseStyle = input.textStyle
    val emphasisRanges = input.decorations
        .filter { it.kind == DecorationKind.Emphasis }
        .map { it.range }

    for (positioned in replayIndex.positionedClusters) {
        val line = lines[positioned.lineIndex]
        val cluster = clusters[positioned.clusterIndex]
        val role = replayIndex.fontRoleByClusterRange[cluster.range].toFontRole()
        val isLatin = role == FontRole.LatinText
        val spanStyle = spans.lastOrNull { cluster.range.start >= it.range.start && cluster.range.start < it.range.end }?.style
        val italic = (spanStyle?.italic ?: false) ||
            (isLatin && emphasisRanges.any { cluster.range.start >= it.start && cluster.range.start < it.end })
        val style = (spanStyle ?: baseStyle).copy(italic = italic)
        if (cluster.displayText.isNotEmpty()) {
            action(
                line,
                cluster,
                positioned.drawX,
                line.baseline + cluster.baselineShift,
                AndroidClusterRun(role, style),
            )
        }
    }
}

private fun String?.toFontRole(): FontRole =
    runCatching { if (this == null) null else FontRole.valueOf(this) }.getOrNull() ?: FontRole.CjkText

private fun drawContextShapedText(
    canvas: android.graphics.Canvas,
    text: String,
    x: Float,
    y: Float,
    role: FontRole,
    paint: TextPaint,
    clipToContext: Boolean = false,
) {
    if (text.isEmpty()) return
    val useHanContext = role == FontRole.CjkText || role == FontRole.CjkPunctuation
    if (useHanContext && clipToContext) {
        // FullBufferClippedPunctuationDraw: drawTextRun keeps context-driven GSUB
        // (locl 2em dash, zh quote forms…) only for glyphs INSIDE the drawn range —
        // a sub-range draw of `中<cluster>中` renders the context-free narrow form
        // (measured on Pixel: 1.55em vs 1.84em for `⸺`). Draw the WHOLE buffer with
        // the pen shifted so the cluster lands at [x], and clip to the cluster's
        // NATURAL pen span inside the buffer — the context 中s sit exactly outside
        // that span. The RESOLVED cluster advance must NOT be the clip: justify can
        // stretch it past the trailing 中 (its left stroke bled in as a phantom
        // vertical bar), and glue compression can shrink it into the glyph's own
        // ink (opening quotes got their face cut off).
        val buffer = "中${text}中"
        val penOrigin = paint.getRunAdvance(buffer, 0, buffer.length, 0, buffer.length, false, 1)
        val penEnd = paint.getRunAdvance(buffer, 0, buffer.length, 0, buffer.length, false, 1 + text.length)
        canvas.save()
        canvas.clipRect(x, y - paint.textSize * 2f, x + (penEnd - penOrigin), y + paint.textSize)
        canvas.drawTextRun(buffer, 0, buffer.length, 0, buffer.length, x - penOrigin, y, false, paint)
        canvas.restore()
    } else if (useHanContext) {
        val buffer = "中${text}中"
        canvas.drawTextRun(buffer, 1, 1 + text.length, 0, buffer.length, x, y, false, paint)
    } else {
        canvas.drawTextRun(text, 0, text.length, 0, text.length, x, y, false, paint)
    }
}

private fun LayoutResult.androidLineInkSkipIntervals(
    replayIndex: LayoutResultReplayIndex,
    line: LineBox,
    bandTop: Float,
    bandBottom: Float,
    spans: List<TextSpan>,
    typefaces: AndroidTypefaceResolver,
    paint: TextPaint,
    path: Path,
    bounds: RectF,
): FloatArray {
    val out = mutableListOf<Float>()
    paint.textLocale = Locale.forLanguageTag(input.textStyle.locale)
    forEachAndroidPositionedCluster(replayIndex, spans) { l, cluster, drawX, baselineY, run ->
        if (l !== line) return@forEachAndroidPositionedCluster
        // AndroidOutlineBandSkipInk: TextBlob.getIntercepts is Skia-only, so the
        // Android renderer derives equivalent intervals from the real outline
        // path and the underline's vertical band. This skips only the ink slice
        // that touches the line, not the whole glyph or text cluster.
        paint.textSize = run.style.fontSize
        paint.fontFeatureSettings = null
        path.reset()
        val glyphs = replayIndex.glyphsByClusterRange[cluster.range].orEmpty()
        val nativePath = AndroidNativeGlyphReplay.glyphPath(
            glyphs = glyphs,
            originX = drawX,
            originY = baselineY,
            fontSize = run.style.fontSize,
            reusablePath = path,
        )
        if (nativePath == null) {
            val syntheticBold = AndroidNativeGlyphReplay.requiresPlatformSyntheticBold(glyphs)
            if (!syntheticBold) requireNativeReplayDidNotFail(glyphs)
            paint.typeface = typefaces.resolve(run.role, run.style.fontFamilies, run.style.fontWeight, run.style.italic)
            paint.isFakeBoldText = syntheticBold
            paint.textSkewX = if (AndroidNativeGlyphReplay.usesSyntheticItalic(glyphs)) -0.25f else 0f
            paint.getTextPath(cluster.displayText, 0, cluster.displayText.length, drawX, baselineY, path)
        }
        if (path.isEmpty) return@forEachAndroidPositionedCluster
        path.computeBounds(bounds, true)
        if (bounds.bottom < bandTop || bounds.top > bandBottom) return@forEachAndroidPositionedCluster
        path.horizontalBandIntercepts(bandTop, bandBottom).forEach { out += it }
    }
    return out.toFloatArray()
}

private fun skipInkCacheKey(lineIndex: Int, lineY: Float, skipBandPad: Float): Long =
    (lineIndex.toLong() shl 32) xor
        (lineY.toRawBits().toLong() and 0xFFFFFFFFL) xor
        (skipBandPad.toRawBits().toLong() shl 1)

private fun requireNativeReplayDidNotFail(glyphs: List<Glyph>) {
    check(!AndroidNativeGlyphReplay.ownsGlyphs(glyphs)) {
        "NativeGlyphReplayUnavailable: a retained native font face could not replay its shaped glyph outline"
    }
}

private data class PathPoint(val x: Float, val y: Float)

private fun Path.horizontalBandIntercepts(bandTop: Float, bandBottom: Float): FloatArray {
    if (isEmpty) return FloatArray(0)
    val contours = flattenedContours(errorPx = 0.4f)
    if (contours.isEmpty()) return FloatArray(0)
    val out = mutableListOf<Float>()
    val bandHeight = (bandBottom - bandTop).coerceAtLeast(0f)
    val samples = max(1, ceil(bandHeight / 0.5f).toInt())
    for (sample in 0..samples) {
        val y = bandTop + bandHeight * (sample.toFloat() / samples)
        val xs = mutableListOf<Float>()
        for (contour in contours) {
            for (index in 0 until contour.lastIndex) {
                val a = contour[index]
                val b = contour[index + 1]
                if ((a.y <= y && y < b.y) || (b.y <= y && y < a.y)) {
                    val t = (y - a.y) / (b.y - a.y)
                    xs += a.x + (b.x - a.x) * t
                }
            }
        }
        xs.sort()
        var index = 0
        while (index + 1 < xs.size) {
            val left = xs[index]
            val right = xs[index + 1]
            if (right > left + 0.25f) {
                out += left
                out += right
            }
            index += 2
        }
    }
    return out.toFloatArray()
}

private fun Path.flattenedContours(errorPx: Float): List<List<PathPoint>> {
    val contours = mutableListOf<List<PathPoint>>()
    val measure = PathMeasure(this, false)
    val step = errorPx.coerceAtLeast(0.25f)
    do {
        val length = measure.length
        if (length <= 0f) continue
        val count = ceil(length / step).toInt().coerceAtLeast(1)
        val points = ArrayList<PathPoint>(count + 2)
        val position = FloatArray(2)
        for (index in 0..count) {
            val distance = length * (index.toFloat() / count)
            if (measure.getPosTan(distance, position, null)) {
                val point = PathPoint(position[0], position[1])
                if (points.lastOrNull() != point) points += point
            }
        }
        val first = points.firstOrNull()
        val last = points.lastOrNull()
        if (first != null && last != null && first != last) points += first
        if (points.size >= 3) contours += points
    } while (measure.nextContour())
    return contours
}

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
        cursor = max(cursor, iv[1])
    }
    if (cursor < right - 0.5f) draw(cursor, right)
}

private fun wavyLinePath(left: Float, right: Float, y: Float, fontSize: Float): Path {
    val path = Path()
    val halfWave = (fontSize * 0.2f).coerceAtLeast(1f)
    val amplitude = fontSize * 0.06f
    path.moveTo(left, y)
    var x = left
    var up = true
    while (x < right - WAVY_ENDPOINT_EPSILON_PX) {
        val rawNextX = x + halfWave
        val nextX = if (rawNextX >= right - WAVY_ENDPOINT_EPSILON_PX) right else rawNextX
        val controlY = if (up) y - amplitude * 2f else y + amplitude * 2f
        path.quadTo((x + nextX) / 2f, controlY, nextX, y)
        x = nextX
        up = !up
    }
    return path
}

private const val INLINE_CODE_BACKGROUND_COLOR: Int = 0x1A000000
private const val BROWSER_LIKE_SKIP_INK_CLEARANCE_EM = 0.10f
private const val BROWSER_LIKE_SKIP_INK_CLEARANCE_MAX = 13f
private const val WAVY_ENDPOINT_EPSILON_PX = 0.01f

private fun browserLikeSkipInkClearance(fontSize: Float, strokeWidth: Float): Float =
    min(max(strokeWidth, fontSize * BROWSER_LIKE_SKIP_INK_CLEARANCE_EM), BROWSER_LIKE_SKIP_INK_CLEARANCE_MAX)
