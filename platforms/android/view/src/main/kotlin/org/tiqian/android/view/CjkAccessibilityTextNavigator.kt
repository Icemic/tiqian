package org.tiqian.android.view

import android.view.accessibility.AccessibilityNodeInfo
import org.tiqian.core.LayoutResult
import org.tiqian.core.getLineForOffset
import java.text.BreakIterator
import java.util.Locale

internal data class CjkAccessibilityTextSegment(
    val start: Int,
    val end: Int,
)

/** Source-text movement granularities matching the traversal contract exposed by TextView. */
internal class CjkAccessibilityTextNavigator(
    private val text: String,
    private val layout: LayoutResult?,
    private val locale: Locale,
    private val visibleContentHeight: Float,
) {
    fun following(granularity: Int, offset: Int): CjkAccessibilityTextSegment? = when (granularity) {
        AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER -> breakFollowing(
            BreakIterator.getCharacterInstance(locale),
            offset,
        )
        AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD -> wordFollowing(offset)
        AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE -> lineFollowing(offset)
        AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PARAGRAPH -> paragraphFollowing(offset)
        AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PAGE -> pageFollowing(offset)
        else -> null
    }

    fun preceding(granularity: Int, offset: Int): CjkAccessibilityTextSegment? = when (granularity) {
        AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER -> breakPreceding(
            BreakIterator.getCharacterInstance(locale),
            offset,
        )
        AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD -> wordPreceding(offset)
        AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE -> linePreceding(offset)
        AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PARAGRAPH -> paragraphPreceding(offset)
        AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PAGE -> pagePreceding(offset)
        else -> null
    }

    private fun breakFollowing(iterator: BreakIterator, offset: Int): CjkAccessibilityTextSegment? {
        if (text.isEmpty() || offset >= text.length) return null
        iterator.setText(text)
        var start = offset.coerceAtLeast(0)
        while (!iterator.isBoundary(start)) {
            start = iterator.following(start)
            if (start == BreakIterator.DONE) return null
        }
        val end = iterator.following(start)
        return segment(start, end)
    }

    private fun breakPreceding(iterator: BreakIterator, offset: Int): CjkAccessibilityTextSegment? {
        if (text.isEmpty() || offset <= 0) return null
        iterator.setText(text)
        var end = offset.coerceAtMost(text.length)
        while (!iterator.isBoundary(end)) {
            end = iterator.preceding(end)
            if (end == BreakIterator.DONE) return null
        }
        return segment(iterator.preceding(end), end)
    }

    private fun wordFollowing(offset: Int): CjkAccessibilityTextSegment? {
        if (text.isEmpty() || offset >= text.length) return null
        val iterator = BreakIterator.getWordInstance(locale).apply {
            setText(this@CjkAccessibilityTextNavigator.text)
        }
        var start = offset.coerceAtLeast(0)
        while (!isLetterOrDigit(start) && !isWordStart(start)) {
            start = iterator.following(start)
            if (start == BreakIterator.DONE) return null
        }
        val end = iterator.following(start)
        return if (end != BreakIterator.DONE && isWordEnd(end)) segment(start, end) else null
    }

    private fun wordPreceding(offset: Int): CjkAccessibilityTextSegment? {
        if (text.isEmpty() || offset <= 0) return null
        val iterator = BreakIterator.getWordInstance(locale).apply {
            setText(this@CjkAccessibilityTextNavigator.text)
        }
        var end = offset.coerceAtMost(text.length)
        while (end > 0 && !isLetterOrDigit(end - 1) && !isWordEnd(end)) {
            end = iterator.preceding(end)
            if (end == BreakIterator.DONE) return null
        }
        val start = iterator.preceding(end)
        return if (start != BreakIterator.DONE && isWordStart(start)) segment(start, end) else null
    }

    private fun lineFollowing(offset: Int): CjkAccessibilityTextSegment? {
        val result = layout ?: return null
        if (text.isEmpty() || offset >= text.length || result.lines.isEmpty()) return null
        val currentLine = if (offset < 0) 0 else result.getLineForOffset(offset)
        val targetLine = if (offset < 0 || result.lines[currentLine].range.start == offset) {
            currentLine
        } else {
            currentLine + 1
        }
        return result.lines.getOrNull(targetLine)?.range?.let { segment(it.start, it.end) }
    }

    private fun linePreceding(offset: Int): CjkAccessibilityTextSegment? {
        val result = layout ?: return null
        if (text.isEmpty() || offset <= 0 || result.lines.isEmpty()) return null
        val clamped = offset.coerceAtMost(text.length)
        val currentLine = result.getLineForOffset(clamped)
        val targetLine = if (result.lines[currentLine].range.end == clamped) {
            currentLine
        } else {
            currentLine - 1
        }
        return result.lines.getOrNull(targetLine)?.range?.let { segment(it.start, it.end) }
    }

    private fun paragraphFollowing(offset: Int): CjkAccessibilityTextSegment? {
        if (text.isEmpty() || offset >= text.length) return null
        var start = offset.coerceAtLeast(0)
        while (start < text.length && text[start] == '\n' && !isParagraphStart(start)) start++
        if (start >= text.length) return null
        var end = start + 1
        while (end < text.length && !isParagraphEnd(end)) end++
        return segment(start, end)
    }

    private fun paragraphPreceding(offset: Int): CjkAccessibilityTextSegment? {
        if (text.isEmpty() || offset <= 0) return null
        var end = offset.coerceAtMost(text.length)
        while (end > 0 && text[end - 1] == '\n' && !isParagraphEnd(end)) end--
        if (end <= 0) return null
        var start = end - 1
        while (start > 0 && !isParagraphStart(start)) start--
        return segment(start, end)
    }

    private fun pageFollowing(offset: Int): CjkAccessibilityTextSegment? {
        val result = layout ?: return null
        if (text.isEmpty() || offset >= text.length || result.lines.isEmpty() || visibleContentHeight <= 0f) {
            return null
        }
        val start = offset.coerceAtLeast(0)
        val currentLine = result.getLineForOffset(start)
        val targetY = result.lines[currentLine].top + visibleContentHeight
        val lastLine = if (targetY < result.lines.last().top) {
            (lineForVertical(result, targetY) - 1).coerceAtLeast(currentLine)
        } else {
            result.lines.lastIndex
        }
        return segment(start, result.lines[lastLine].range.end)
    }

    private fun pagePreceding(offset: Int): CjkAccessibilityTextSegment? {
        val result = layout ?: return null
        if (text.isEmpty() || offset <= 0 || result.lines.isEmpty() || visibleContentHeight <= 0f) {
            return null
        }
        val end = offset.coerceAtMost(text.length)
        val currentLine = result.getLineForOffset(end)
        val targetY = result.lines[currentLine].top - visibleContentHeight
        var firstLine = if (targetY > 0f) lineForVertical(result, targetY) else 0
        if (end == text.length && firstLine < currentLine) firstLine++
        return segment(result.lines[firstLine.coerceAtMost(currentLine)].range.start, end)
    }

    private fun lineForVertical(result: LayoutResult, y: Float): Int =
        result.lines.indexOfLast { it.top <= y }.coerceAtLeast(0)

    private fun isWordStart(index: Int): Boolean =
        isLetterOrDigit(index) && (index == 0 || !isLetterOrDigit(index - 1))

    private fun isWordEnd(index: Int): Boolean =
        index > 0 && isLetterOrDigit(index - 1) &&
            (index == text.length || !isLetterOrDigit(index))

    private fun isLetterOrDigit(index: Int): Boolean =
        index in text.indices && Character.isLetterOrDigit(text.codePointAt(index))

    private fun isParagraphStart(index: Int): Boolean =
        text[index] != '\n' && (index == 0 || text[index - 1] == '\n')

    private fun isParagraphEnd(index: Int): Boolean =
        index > 0 && text[index - 1] != '\n' &&
            (index == text.length || text[index] == '\n')

    private fun segment(start: Int, end: Int): CjkAccessibilityTextSegment? =
        if (start >= 0 && end >= 0 && start < end) CjkAccessibilityTextSegment(start, end) else null
}
