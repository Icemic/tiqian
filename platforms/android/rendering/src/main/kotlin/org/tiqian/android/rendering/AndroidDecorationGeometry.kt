package org.tiqian.android.rendering

import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.text.TextPaint
import org.tiqian.core.LayoutResult
import org.tiqian.core.LayoutResultReplayIndex
import org.tiqian.core.LineBox
import org.tiqian.core.TextSpan
import org.tiqian.shaping.android.AndroidTypefaceResolver
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

internal fun LayoutResult.androidLineInkSkipIntervals(
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
    forEachAndroidPositionedCluster(replayIndex, spans) { currentLine, cluster, drawX, baselineY, run ->
        if (currentLine !== line) return@forEachAndroidPositionedCluster
        // AndroidOutlineBandSkipInk: derive Skia-like underline intercepts from the real Android
        // glyph outline, and remove only ink that crosses the decoration's vertical band.
        paint.textSize = run.style.fontSize
        paint.fontFeatureSettings = run.openTypeFeatures.toAndroidFontFeatureSettings()
        path.reset()
        paint.typeface = typefaces.resolve(run.role, run.style.fontFamilies, run.style.fontWeight, run.style.italic)
        paint.isFakeBoldText = false
        paint.textSkewX = 0f
        paint.getTextPath(cluster.displayText, 0, cluster.displayText.length, drawX, baselineY, path)
        if (path.isEmpty) return@forEachAndroidPositionedCluster
        path.computeBounds(bounds, true)
        if (bounds.bottom < bandTop || bounds.top > bandBottom) return@forEachAndroidPositionedCluster
        path.horizontalBandIntercepts(bandTop, bandBottom).forEach { out += it }
    }
    return out.toFloatArray()
}

internal fun skipInkCacheKey(lineIndex: Int, lineY: Float, skipBandPad: Float): Long =
    (lineIndex.toLong() shl 32) xor
        (lineY.toRawBits().toLong() and 0xFFFFFFFFL) xor
        (skipBandPad.toRawBits().toLong() shl 1)

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

internal inline fun keptIntervals(
    left: Float,
    right: Float,
    skips: FloatArray,
    gap: Float,
    draw: (Float, Float) -> Unit,
) {
    val merged = ArrayList<FloatArray>()
    var index = 0
    while (index + 1 < skips.size) {
        val start = (skips[index] - gap).coerceIn(left, right)
        val end = (skips[index + 1] + gap).coerceIn(left, right)
        if (end > start) merged += floatArrayOf(start, end)
        index += 2
    }
    merged.sortBy { it[0] }
    var cursor = left
    for (interval in merged) {
        if (interval[0] > cursor + 0.5f) draw(cursor, interval[0])
        cursor = max(cursor, interval[1])
    }
    if (cursor < right - 0.5f) draw(cursor, right)
}

internal fun wavyLinePath(left: Float, right: Float, y: Float, fontSize: Float): Path {
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

internal fun browserLikeSkipInkClearance(fontSize: Float, strokeWidth: Float): Float =
    min(max(strokeWidth, fontSize * BROWSER_LIKE_SKIP_INK_CLEARANCE_EM), BROWSER_LIKE_SKIP_INK_CLEARANCE_MAX)

private const val BROWSER_LIKE_SKIP_INK_CLEARANCE_EM = 0.10f
private const val BROWSER_LIKE_SKIP_INK_CLEARANCE_MAX = 13f
private const val WAVY_ENDPOINT_EPSILON_PX = 0.01f
