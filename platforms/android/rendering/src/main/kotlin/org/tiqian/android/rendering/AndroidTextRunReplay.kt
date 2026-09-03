package org.tiqian.android.rendering

import android.graphics.Canvas
import android.text.TextPaint
import org.tiqian.core.Cluster
import org.tiqian.core.DecorationKind
import org.tiqian.core.LayoutResult
import org.tiqian.core.LayoutResultReplayIndex
import org.tiqian.core.LineBox
import org.tiqian.core.TextSpan
import org.tiqian.core.TextStyle
import org.tiqian.font.FontRole
import org.tiqian.shaping.android.requiresHanShapingContext

internal data class AndroidClusterRun(
    val role: FontRole,
    val style: TextStyle,
    val openTypeFeatures: List<String>,
)

internal inline fun LayoutResult.forEachAndroidPositionedCluster(
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
        val spanStyle = spans.lastOrNull {
            cluster.range.start >= it.range.start && cluster.range.start < it.range.end
        }?.style
        val italic = (spanStyle?.italic ?: false) ||
            (isLatin && emphasisRanges.any {
                cluster.range.start >= it.start && cluster.range.start < it.end
            })
        val style = (spanStyle ?: baseStyle).copy(italic = italic)
        if (cluster.displayText.isNotEmpty()) {
            action(
                line,
                cluster,
                positioned.drawX,
                line.baseline + cluster.baselineShift,
                AndroidClusterRun(
                    role,
                    style,
                    replayIndex.openTypeFeaturesByClusterRange[cluster.range].orEmpty(),
                ),
            )
        }
    }
}

private fun String?.toFontRole(): FontRole =
    runCatching { if (this == null) null else FontRole.valueOf(this) }.getOrNull() ?: FontRole.CjkText

internal fun drawContextShapedText(
    canvas: Canvas,
    text: String,
    x: Float,
    y: Float,
    role: FontRole,
    paint: TextPaint,
    clipToContext: Boolean = false,
) {
    if (text.isEmpty()) return
    val useHanContext = requiresHanShapingContext(text, role)
    if (useHanContext && clipToContext) {
        // Preserve context-driven substitutions while clipping the surrounding context glyphs to
        // the cluster's natural pen span. Resolved advances cannot be used here because
        // justification and punctuation compression deliberately change them.
        val buffer = "中${text}中"
        val penOrigin = paint.getRunAdvance(buffer, 0, buffer.length, 0, buffer.length, false, 1)
        val penEnd = paint.getRunAdvance(
            buffer,
            0,
            buffer.length,
            0,
            buffer.length,
            false,
            1 + text.length,
        )
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

internal fun List<String>.toAndroidFontFeatureSettings(): String? {
    if (isEmpty()) return null
    return joinToString(",") { feature ->
        val pieces = feature.split('=', limit = 2)
        val tag = pieces[0].trim().take(4)
        val value = pieces.getOrNull(1)?.trim()?.toIntOrNull() ?: 1
        "'$tag' $value"
    }
}
