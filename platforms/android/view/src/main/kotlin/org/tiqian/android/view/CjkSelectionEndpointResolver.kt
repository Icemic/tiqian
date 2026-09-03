package org.tiqian.android.view

import org.tiqian.core.LayoutResult
import org.tiqian.core.SourceBoundaryBias
import org.tiqian.core.TextRange
import org.tiqian.core.coerceSelectionOffset
import org.tiqian.core.cursorRect
import org.tiqian.core.getLineForOffset
import org.tiqian.core.getSelectionWordBoundary
import org.tiqian.core.selectionOffsetForPosition
import kotlin.math.abs
import kotlin.math.max

/** Logical position used only to compare endpoint motion across paragraph layouts. */
internal data class CjkSelectionEndpointPosition(
    val key: Any,
    val order: Int,
    val offset: Int,
)

internal data class HandleHit(
    val view: CjkTextView,
    val key: Any,
    val order: Int,
    val snapshot: CjkTextView.LayoutSnapshot,
    val contentX: Float,
    val queryY: Float,
    val rawOffset: Int,
) {
    val anchor: CjkDocumentSelectionAnchor
        get() = CjkDocumentSelectionAnchor(key, rawOffset)
}

/**
 * Shared AOSP-style handle adjustment for standalone and document selection.
 *
 * Callers own ordering, hit testing and the non-crossing constraint. This state machine owns the
 * interaction semantics that must not change when the same paragraph is placed in a document:
 * line-change slop, word expansion, character shrinking and the touch-to-word delta.
 */
internal class CjkSelectionEndpointResolver(
    private val density: Float,
) {
    private var queryKey: Any? = null
    private var queryOrder = Int.MIN_VALUE
    private var handleLine = -1
    private var previousLine = -1
    private var previousX = Float.NaN
    private var touchWordDelta = 0f

    fun begin(position: CjkSelectionEndpointPosition, line: Int) {
        queryKey = position.key
        queryOrder = position.order
        handleLine = line
        previousLine = line
        previousX = Float.NaN
        touchWordDelta = 0f
    }

    fun reset() {
        queryKey = null
        queryOrder = Int.MIN_VALUE
        handleLine = -1
        previousLine = -1
        previousX = Float.NaN
        touchWordDelta = 0f
    }

    /** AOSP Editor#getCurrentLineAdjustedForSlop replayed against Tiqian line boxes. */
    fun lineSlopAdjustedY(
        key: Any,
        order: Int,
        result: LayoutResult,
        contentY: Float,
    ): Float {
        if (result.lines.isEmpty()) return contentY
        val trueLine = nearestLineForY(result, contentY)
        if (queryKey != key || queryOrder != order) {
            queryKey = key
            queryOrder = order
            handleLine = trueLine
        } else if (handleLine !in result.lines.indices || abs(trueLine - handleLine) >= 2) {
            handleLine = trueLine
        } else {
            val previous = result.lines[handleLine]
            val lineHeight = (previous.bottom - previous.top).coerceAtLeast(0f)
            val totalHeight = (lineHeight * (1f + LINE_CHANGE_SLOP_RATIO)).coerceIn(
                LINE_CHANGE_SLOP_MIN_DP * density,
                LINE_CHANGE_SLOP_MAX_DP * density,
            )
            val slop = max(0f, totalHeight - lineHeight)
            if (
                trueLine > handleLine && contentY >= previous.bottom + slop ||
                trueLine < handleLine && contentY <= previous.top - slop
            ) {
                handleLine = trueLine
            }
        }
        val selectedLine = result.lines[handleLine.coerceIn(0, result.lines.lastIndex)]
        return (selectedLine.top + selectedLine.bottom) / 2f
    }

    fun forceLine(key: Any, order: Int, line: Int) {
        queryKey = key
        queryOrder = order
        handleLine = line
    }

    fun resolve(
        snapshot: CjkTextView.LayoutSnapshot,
        isStart: Boolean,
        candidatePosition: CjkSelectionEndpointPosition,
        currentPosition: CjkSelectionEndpointPosition,
        rawOffset: Int,
        contentX: Float,
        queryY: Float,
    ): Int {
        val result = snapshot.result
        val safeRaw = result.coerceSelectionOffset(rawOffset, SourceBoundaryBias.Nearest)
        if (previousX.isNaN()) previousX = contentX
        val sameFragment = candidatePosition.key == currentPosition.key
        val xDifference = contentX - previousX
        val orderDirection = comparePositions(candidatePosition.copy(offset = safeRaw), currentPosition)
        val crossedLine = if (sameFragment) {
            if (isStart) handleLine < previousLine else handleLine > previousLine
        } else {
            if (isStart) orderDirection < 0 else orderDirection > 0
        }
        val expanding = if (sameFragment) {
            crossedLine || if (isStart) xDifference < 0f else xDifference > 0f
        } else {
            crossedLine
        }
        val currentOffset = currentPosition.offset
        var candidate = if (sameFragment) currentOffset else safeRaw
        if (expanding) {
            val word = selectionWordBoundaryAt(result, safeRaw)
            val currentIsInsideWord = sameFragment && currentOffset > word.start && currentOffset < word.end
            if (!currentIsInsideWord || crossedLine) {
                var wordBoundary = if (isStart) word.start else word.end
                if (result.getLineForOffset(wordBoundary) != handleLine) {
                    val touchedLine = result.lines[handleLine]
                    wordBoundary = if (isStart) touchedLine.range.start else touchedLine.range.end
                }
                val threshold = if (isStart) {
                    word.end - (word.end - wordBoundary) / 2
                } else {
                    word.start + (wordBoundary - word.start) / 2
                }
                candidate = when {
                    isStart && (safeRaw <= threshold || crossedLine) -> word.start
                    !isStart && (safeRaw >= threshold || crossedLine) -> word.end
                    else -> if (sameFragment) currentOffset else safeRaw
                }
                touchWordDelta = if (
                    isStart && candidate < safeRaw || !isStart && candidate > safeRaw
                ) {
                    contentX - snapshot.replayIndex.cursorRect(result, candidate).left
                } else {
                    0f
                }
            } else {
                candidate = safeRaw
            }
        } else {
            val adjustedOffset = snapshot.replayIndex.selectionOffsetForPosition(
                result,
                contentX - touchWordDelta,
                queryY,
            )
            val shrinking = if (sameFragment) {
                if (isStart) {
                    adjustedOffset > currentOffset || handleLine > previousLine
                } else {
                    adjustedOffset < currentOffset || handleLine < previousLine
                }
            } else {
                if (isStart) orderDirection > 0 else orderDirection < 0
            }
            if (shrinking) {
                candidate = if (sameFragment && handleLine == previousLine) {
                    adjustedOffset
                } else {
                    val word = selectionWordBoundaryAt(result, adjustedOffset)
                    if (isStart) word.start else word.end
                }
                touchWordDelta = if (
                    isStart && candidate < safeRaw || !isStart && candidate > safeRaw
                ) {
                    contentX - snapshot.replayIndex.cursorRect(result, candidate).left
                } else {
                    0f
                }
            } else if (
                sameFragment && (
                    isStart && adjustedOffset < currentOffset ||
                        !isStart && adjustedOffset > currentOffset
                    )
            ) {
                touchWordDelta = contentX - snapshot.replayIndex.cursorRect(result, currentOffset).left
            }
        }
        return candidate
    }

    fun commit(position: CjkSelectionEndpointPosition, line: Int, contentX: Float) {
        previousLine = line
        previousX = contentX
        queryKey = position.key
        queryOrder = position.order
        handleLine = line
    }

    private fun selectionWordBoundaryAt(result: LayoutResult, offset: Int): TextRange {
        val length = result.input.content.text.length
        val unitOffset = if (offset == length && length > 0) length - 1 else offset
        return result.getSelectionWordBoundary(unitOffset)
    }

    private fun comparePositions(
        left: CjkSelectionEndpointPosition,
        right: CjkSelectionEndpointPosition,
    ): Int = if (left.key == right.key) {
        left.offset.compareTo(right.offset)
    } else {
        left.order.compareTo(right.order)
    }

    private companion object {
        const val LINE_CHANGE_SLOP_RATIO = 0.5f
        const val LINE_CHANGE_SLOP_MIN_DP = 8f
        const val LINE_CHANGE_SLOP_MAX_DP = 45f
    }
}

private fun nearestLineForY(result: LayoutResult, y: Float): Int =
    result.lines.indices.minBy { index ->
        val line = result.lines[index]
        when {
            y < line.top -> line.top - y
            y > line.bottom -> y - line.bottom
            else -> 0f
        }
    }
