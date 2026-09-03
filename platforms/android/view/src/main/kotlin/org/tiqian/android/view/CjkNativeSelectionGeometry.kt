package org.tiqian.android.view

import org.tiqian.core.LayoutResult
import org.tiqian.core.LayoutResultReplayIndex
import org.tiqian.core.LineBox
import org.tiqian.core.Rect
import org.tiqian.core.getLineForOffset
import org.tiqian.core.selectionBoxes

/**
 * Expands engine-owned character boxes with Android's native multi-line selection fill.
 *
 * Android Layout excludes the newline from glyph selection, then fills the remainder of the first
 * line, every interior line, and the leading part of the last line. Fragment separators use the
 * same continuation flags, so paragraph boundaries do not need a second layout engine.
 */
internal fun nativeSelectionBoxes(
    result: LayoutResult,
    replayIndex: LayoutResultReplayIndex,
    projection: CjkDocumentSelectionProjection,
    viewportWidth: Float,
): List<Rect> {
    if (projection.isEmpty || result.lines.isEmpty() || viewportWidth <= 0f) return emptyList()
    val range = projection.range
    val characterBoxes = range?.let { replayIndex.selectionBoxes(result, it) }.orEmpty()
    val firstLine = when {
        range != null -> result.getLineForOffset(range.start)
        projection.selectedSeparatorBefore.isNotEmpty() -> 0
        else -> result.lines.lastIndex
    }.coerceIn(0, result.lines.lastIndex)
    val lastLine = when {
        range != null -> result.getLineForOffset(range.end)
        projection.selectedSeparatorAfter.isNotEmpty() -> result.lines.lastIndex
        else -> 0
    }.coerceIn(firstLine, result.lines.lastIndex)

    return expandNativeSelectionBoxes(
        lines = result.lines,
        characterBoxes = characterBoxes,
        firstLine = firstLine,
        lastLine = lastLine,
        continuesFromPreviousFragment = projection.selectedSeparatorBefore.isNotEmpty(),
        continuesOnNextFragment = projection.selectedSeparatorAfter.isNotEmpty(),
        viewportWidth = viewportWidth,
    )
}

internal fun expandNativeSelectionBoxes(
    lines: List<LineBox>,
    characterBoxes: List<Rect>,
    firstLine: Int,
    lastLine: Int,
    continuesFromPreviousFragment: Boolean,
    continuesOnNextFragment: Boolean,
    viewportWidth: Float,
): List<Rect> = buildList {
    require(firstLine in lines.indices)
    require(lastLine in firstLine..lines.lastIndex)
    require(viewportWidth > 0f)
    var characterBoxIndex = 0
    for (lineIndex in firstLine..lastLine) {
        val line = lines[lineIndex]
        while (
            characterBoxIndex < characterBoxes.size &&
            characterBoxes[characterBoxIndex].top < line.top
        ) {
            characterBoxIndex++
        }
        val characterBox = characterBoxes.getOrNull(characterBoxIndex)?.takeIf {
            it.top == line.top && it.bottom == line.bottom
        }?.also { characterBoxIndex++ }
        val continuesFromPreviousLine = lineIndex > firstLine ||
            lineIndex == firstLine && continuesFromPreviousFragment
        val continuesOnNextLine = lineIndex < lastLine ||
            lineIndex == lastLine && continuesOnNextFragment
        if (characterBox == null && !continuesFromPreviousLine && !continuesOnNextLine) continue

        val lineStart = line.indent
        val lineEnd = line.indent + line.visualWidth + line.hyphenAdvance
        val left = if (continuesFromPreviousLine) {
            minOf(0f, characterBox?.left ?: lineStart)
        } else {
            characterBox?.left ?: lineEnd
        }
        val right = if (continuesOnNextLine) {
            maxOf(viewportWidth, characterBox?.right ?: lineEnd)
        } else {
            characterBox?.right ?: lineStart
        }
        if (right > left) add(Rect(left, line.top, right, line.bottom))
    }
}
