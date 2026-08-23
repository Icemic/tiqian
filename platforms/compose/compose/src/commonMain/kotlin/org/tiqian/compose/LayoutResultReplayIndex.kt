package org.tiqian.compose

import org.tiqian.core.Glyph
import org.tiqian.core.LayoutResult
import org.tiqian.core.PositionedCluster
import org.tiqian.core.Rect
import org.tiqian.core.RichTextLineSegment
import org.tiqian.core.RichTextSpan
import org.tiqian.core.SourceBoundaryBias
import org.tiqian.core.TextRange
import org.tiqian.core.coerceSelectionOffset
import org.tiqian.core.getLineForOffset
import org.tiqian.core.getSelectionWordBoundary
import org.tiqian.core.positionedClusters
import org.tiqian.core.positionedRichTextSegments
import org.tiqian.core.richTextBackgroundSegments
import org.tiqian.core.trimmedRichTextDecorationSegments
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Immutable lookup data derived exclusively from one [LayoutResult].
 *
 * `LayoutResultReplayIndex` does not make layout decisions or alter geometry. It retains the core
 * query results and glyph grouping that Compose renderers otherwise rebuilt on every draw. A new
 * index is created whenever the owning layout result or rich-text ranges change.
 */
internal data class LayoutResultReplayIndex(
    val positionedClusters: List<PositionedCluster>,
    val positionedClustersByLine: List<List<PositionedCluster>>,
    val richTextSegments: List<RichTextLineSegment>,
    val richTextBackgroundSegments: List<RichTextLineSegment>,
    val richTextDecorationSegments: List<RichTextLineSegment>,
    val glyphsByClusterRange: Map<TextRange, List<Glyph>>,
    val openTypeFeaturesByClusterRange: Map<TextRange, List<String>>,
    val fontRoleByClusterRange: Map<TextRange, String?>,
)

internal fun LayoutResult.toReplayIndex(richTextSpans: List<RichTextSpan>): LayoutResultReplayIndex {
    val positionedClusters = positionedClusters()
    val positionedClustersByLine = List(lines.size) { mutableListOf<PositionedCluster>() }
    positionedClusters.forEach { positioned ->
        positionedClustersByLine.getOrNull(positioned.lineIndex)?.add(positioned)
    }
    val richTextSegments = positionedRichTextSegments(richTextSpans)
    val openTypeFeaturesByClusterRange = buildMap {
        var runIndex = 0
        for (cluster in clusters) {
            while (runIndex < glyphRuns.size && glyphRuns[runIndex].range.end <= cluster.range.start) {
                runIndex += 1
            }
            val run = glyphRuns.getOrNull(runIndex)
            put(
                cluster.range,
                run?.takeIf {
                    cluster.range.start >= it.range.start && cluster.range.end <= it.range.end
                }?.openTypeFeatures.orEmpty(),
            )
        }
    }
    val fontRoleByClusterRange = buildMap {
        var decisionIndex = 0
        for (positioned in positionedClusters) {
            val range = clusters[positioned.clusterIndex].range
            while (
                decisionIndex < debug.fontDecisions.size &&
                debug.fontDecisions[decisionIndex].range.end <= range.start
            ) {
                decisionIndex += 1
            }
            val decision = debug.fontDecisions.getOrNull(decisionIndex)
            put(
                range,
                decision?.takeIf {
                    range.start >= it.range.start && range.end <= it.range.end
                }?.role,
            )
        }
    }
    return LayoutResultReplayIndex(
        positionedClusters = positionedClusters,
        positionedClustersByLine = positionedClustersByLine,
        richTextSegments = richTextSegments,
        richTextBackgroundSegments = richTextBackgroundSegments(richTextSegments),
        richTextDecorationSegments = trimmedRichTextDecorationSegments(richTextSegments),
        glyphsByClusterRange = glyphRuns.asSequence()
            .flatMap { it.glyphs.asSequence() }
            .groupBy { it.clusterRange },
        openTypeFeaturesByClusterRange = openTypeFeaturesByClusterRange,
        fontRoleByClusterRange = fontRoleByClusterRange,
    )
}

/** Hot-path selection hit test over the immutable replay geometry. */
internal fun LayoutResultReplayIndex.selectionOffsetForPosition(
    result: LayoutResult,
    x: Float,
    y: Float,
): Int {
    if (result.lines.isEmpty()) return 0
    val lineIndex = result.lines.indices.minBy { index ->
        val line = result.lines[index]
        when {
            y < line.top -> line.top - y
            y > line.bottom -> y - line.bottom
            else -> 0f
        }
    }
    val positioned = positionedClustersByLine.getOrElse(lineIndex) { emptyList() }
    if (positioned.isEmpty()) {
        return result.coerceSelectionOffset(
            result.lines[lineIndex].range.start,
            SourceBoundaryBias.Nearest,
        )
    }
    if (x <= positioned.first().left) {
        return result.coerceSelectionOffset(positioned.first().range.start, SourceBoundaryBias.Nearest)
    }
    if (x >= positioned.last().right) {
        return result.coerceSelectionOffset(positioned.last().range.end, SourceBoundaryBias.Nearest)
    }
    val cluster = positioned.firstOrNull { x >= it.left && x <= it.right }
        ?: positioned.minBy { minOf(abs(x - it.left), abs(x - it.right)) }
    val rawOffset = cluster.offsetForX(x)
    val backward = result.coerceSelectionOffset(rawOffset, SourceBoundaryBias.Backward)
    val forward = result.coerceSelectionOffset(rawOffset, SourceBoundaryBias.Forward)
    if (backward == forward) return backward
    val backwardDistance = abs(cursorRect(result, backward).left - x)
    val forwardDistance = abs(cursorRect(result, forward).left - x)
    return if (backwardDistance < forwardDistance) backward else forward
}

internal fun LayoutResultReplayIndex.selectionWordRangeForPosition(
    result: LayoutResult,
    x: Float,
    y: Float,
): TextRange? {
    if (result.lines.isEmpty() || result.input.content.text.isEmpty()) return null
    val lineIndex = result.lines.indices.minBy { index ->
        val line = result.lines[index]
        when {
            y < line.top -> line.top - y
            y > line.bottom -> y - line.bottom
            else -> 0f
        }
    }
    val positioned = positionedClustersByLine.getOrElse(lineIndex) { emptyList() }
    if (positioned.isEmpty()) return null
    val cluster = positioned.firstOrNull { x >= it.left && x <= it.right }
        ?: positioned.minBy { minOf(abs(x - it.left), abs(x - it.right)) }
    if (cluster.range.isEmpty) return null
    val sourceUnitOffset = cluster.offsetForX(x)
        .coerceIn(cluster.range.start, cluster.range.end - 1)
    return result.getSelectionWordBoundary(sourceUnitOffset)
}

internal fun LayoutResultReplayIndex.cursorRect(result: LayoutResult, offset: Int): Rect {
    if (result.lines.isEmpty()) return Rect(0f, 0f, 0f, 0f)
    val clamped = offset.coerceIn(0, result.input.content.text.length)
    val lineIndex = result.getLineForOffset(clamped).coerceAtLeast(0)
    val line = result.lines[lineIndex]
    val positioned = positionedClustersByLine.getOrElse(lineIndex) { emptyList() }
    val x = when {
        positioned.isEmpty() -> line.indent
        clamped <= positioned.first().range.start -> positioned.first().left
        clamped >= positioned.last().range.end -> positioned.last().right
        else -> {
            val cluster = positioned.first { clamped >= it.range.start && clamped <= it.range.end }
            cluster.xForOffset(clamped)
        }
    }
    return Rect(x, line.top, x + 1f, line.bottom)
}

internal fun LayoutResultReplayIndex.selectionBoxes(
    result: LayoutResult,
    range: TextRange,
): List<Rect> {
    if (range.isEmpty || result.lines.isEmpty()) return emptyList()
    val textLength = result.input.content.text.length
    val start = range.start.coerceIn(0, textLength)
    val end = range.end.coerceIn(start, textLength)
    if (start == end) return emptyList()
    // One continuous rect per line, from the selection's start caret to its end caret. Interior
    // lines fill to the visual edges so autospace / punctuation glue and 两端对齐 gaps inside the
    // selection are painted through (涂) instead of leaving slivers; the two endpoints stay
    // ink-accurate via [cursorRect].
    val firstLine = result.getLineForOffset(start).coerceIn(0, result.lines.lastIndex)
    val lastLine = result.getLineForOffset(end).coerceIn(firstLine, result.lines.lastIndex)
    val boxes = ArrayList<Rect>()
    for (lineIndex in firstLine..lastLine) {
        val line = result.lines[lineIndex]
        val positioned = positionedClustersByLine.getOrElse(lineIndex) { emptyList() }
        if (positioned.isEmpty()) continue
        val lineStart = maxOf(start, positioned.first().range.start)
        val lineEnd = minOf(end, positioned.last().range.end)
        if (lineStart >= lineEnd) continue
        val leftX = if (lineStart > positioned.first().range.start) {
            cursorRect(result, lineStart).left
        } else {
            positioned.first().left
        }
        val rightX = if (lineEnd < positioned.last().range.end) {
            cursorRect(result, lineEnd).left
        } else {
            positioned.last().right
        }
        if (rightX > leftX) boxes += Rect(leftX, line.top, rightX, line.bottom)
    }
    return boxes
}

// Per-source glyph-boundary mapping, identical to core `LayoutQueries`: an interior offset in a
// proportional Latin word lands on the real letter edge via [PositionedCluster.sourceStops], while
// the outer boundaries stay the occupied box so compressed punctuation / 两端对齐 stretch never
// overshoot a caret or selection endpoint. The cached replay path and the core queries agree.
private fun PositionedCluster.xForOffset(offset: Int): Float {
    if (range.length <= 0) return left
    val i = (offset - range.start).coerceIn(0, range.length)
    sourceStops?.let { return it[i] }
    return left + width * (i.toFloat() / range.length.toFloat())
}

private fun PositionedCluster.offsetForX(x: Float): Int {
    if (range.length <= 0) return range.start
    sourceStops?.let { stops ->
        var best = 0
        var bestDistance = Float.MAX_VALUE
        for (i in stops.indices) {
            val distance = abs(x - stops[i])
            if (distance < bestDistance) {
                bestDistance = distance
                best = i
            }
        }
        return (range.start + best).coerceIn(range.start, range.end)
    }
    if (width <= 0f) return range.start
    val ratio = ((x - left) / width).coerceIn(0f, 1f)
    return (range.start + (ratio * range.length).roundToInt()).coerceIn(range.start, range.end)
}
