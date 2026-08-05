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
import org.tiqian.core.trimmedRichTextDecorationSegments
import kotlin.math.abs
import kotlin.math.max
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
    val richTextDecorationSegments: List<RichTextLineSegment>,
    val glyphsByClusterRange: Map<TextRange, List<Glyph>>,
    val fontRoleByClusterRange: Map<TextRange, String?>,
)

internal fun LayoutResult.toReplayIndex(richTextSpans: List<RichTextSpan>): LayoutResultReplayIndex {
    val positionedClusters = positionedClusters()
    val positionedClustersByLine = List(lines.size) { mutableListOf<PositionedCluster>() }
    positionedClusters.forEach { positioned ->
        positionedClustersByLine.getOrNull(positioned.lineIndex)?.add(positioned)
    }
    val richTextSegments = positionedRichTextSegments(richTextSpans)
    return LayoutResultReplayIndex(
        positionedClusters = positionedClusters,
        positionedClustersByLine = positionedClustersByLine,
        richTextSegments = richTextSegments,
        richTextDecorationSegments = trimmedRichTextDecorationSegments(richTextSegments),
        glyphsByClusterRange = glyphRuns.asSequence()
            .flatMap { it.glyphs.asSequence() }
            .groupBy { it.clusterRange },
        fontRoleByClusterRange = positionedClusters.associate { positioned ->
            val range = clusters[positioned.clusterIndex].range
            range to debug.fontDecisions.firstOrNull { decision ->
                range.start >= decision.range.start && range.end <= decision.range.end
            }?.role
        },
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
        else -> positioned.first { clamped >= it.range.start && clamped <= it.range.end }
            .xForOffset(clamped)
    }
    return Rect(x, line.top, x + 1f, line.bottom)
}

internal fun LayoutResultReplayIndex.selectionBoxes(
    result: LayoutResult,
    range: TextRange,
): List<Rect> {
    if (range.isEmpty || result.lines.isEmpty()) return emptyList()
    val start = range.start.coerceIn(0, result.input.content.text.length)
    val end = range.end.coerceIn(start, result.input.content.text.length)
    if (start == end) return emptyList()
    val boxes = ArrayList<Rect>()
    var index = positionedClusters.binarySearchBy(start) { it.range.end }
    if (index < 0) index = (-index - 1).coerceAtLeast(0)
    while (index < positionedClusters.size) {
        val cluster = positionedClusters[index++]
        if (cluster.range.start >= end) break
        val sliceStart = max(start, cluster.range.start)
        val sliceEnd = minOf(end, cluster.range.end)
        if (sliceStart < sliceEnd) boxes += cluster.sliceRect(sliceStart, sliceEnd)
    }
    return boxes
}

private fun PositionedCluster.xForOffset(offset: Int): Float {
    if (range.length <= 0 || width <= 0f) return left
    val ratio = (offset - range.start).toFloat() / range.length.toFloat()
    return left + width * ratio.coerceIn(0f, 1f)
}

private fun PositionedCluster.offsetForX(x: Float): Int {
    if (range.length <= 0 || width <= 0f) return range.start
    val ratio = ((x - left) / width).coerceIn(0f, 1f)
    return (range.start + (ratio * range.length).roundToInt()).coerceIn(range.start, range.end)
}

private fun PositionedCluster.sliceRect(start: Int, end: Int): Rect =
    Rect(xForOffset(start), top, xForOffset(end), bottom)
