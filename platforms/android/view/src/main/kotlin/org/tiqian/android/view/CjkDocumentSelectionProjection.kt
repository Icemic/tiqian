package org.tiqian.android.view

import org.tiqian.core.TextRange

/** One fragment-local projection of a selection in the continuous logical document. */
internal data class CjkDocumentSelectionProjection(
    val range: TextRange? = null,
    val selectedSeparatorBefore: String = "",
    val selectedSeparatorAfter: String = "",
) {
    val isEmpty: Boolean
        get() = range == null && selectedSeparatorBefore.isEmpty() && selectedSeparatorAfter.isEmpty()
}

internal data class CjkDocumentSelectionSlice(
    val startIndex: Int,
    val endIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
)

internal fun List<CjkSelectionDocumentFragment>.selectionSlice(
    start: CjkDocumentSelectionAnchor,
    end: CjkDocumentSelectionAnchor,
): CjkDocumentSelectionSlice? {
    val startIndex = indexOfFirst { it.key == start.key }
    val endIndex = indexOfFirst { it.key == end.key }
    if (startIndex < 0 || endIndex < startIndex) return null
    require(start.offset in 0..this[startIndex].text.length) {
        "start offset is outside its fragment"
    }
    require(end.offset in 0..this[endIndex].text.length) {
        "end offset is outside its fragment"
    }
    return CjkDocumentSelectionSlice(startIndex, endIndex, start.offset, end.offset)
}

/**
 * Preserves both sides of every selected fragment separator.
 *
 * A separator belongs to the continuous document even though it is not part of either paragraph's
 * engine input. The two adjacent projections let the Android frontend paint the line-end and
 * line-start portions of the same selection without adding separator text to either paragraph.
 */
internal fun List<CjkSelectionDocumentFragment>.selectionProjections(
    start: CjkDocumentSelectionAnchor,
    end: CjkDocumentSelectionAnchor,
): Map<Any, CjkDocumentSelectionProjection>? {
    val slice = selectionSlice(start, end) ?: return null
    if (slice.startIndex == slice.endIndex && slice.startOffset == slice.endOffset) {
        return emptyMap()
    }
    return buildMap {
        for (index in slice.startIndex..slice.endIndex) {
            val fragment = this@selectionProjections[index]
            val lower = if (index == slice.startIndex) slice.startOffset else 0
            val upper = if (index == slice.endIndex) slice.endOffset else fragment.text.length
            val projection = CjkDocumentSelectionProjection(
                range = TextRange(lower, upper).takeUnless(TextRange::isEmpty),
                selectedSeparatorBefore = if (index > slice.startIndex) {
                    this@selectionProjections[index - 1].separatorAfter
                } else {
                    ""
                },
                selectedSeparatorAfter = if (index < slice.endIndex) fragment.separatorAfter else "",
            )
            if (!projection.isEmpty) put(fragment.key, projection)
        }
    }
}

/** Builds only one attached fragment's projection instead of materializing the selected document. */
internal fun List<CjkSelectionDocumentFragment>.selectionProjectionAt(
    slice: CjkDocumentSelectionSlice,
    index: Int,
): CjkDocumentSelectionProjection? {
    if (index !in slice.startIndex..slice.endIndex) return null
    val fragment = this[index]
    val lower = if (index == slice.startIndex) slice.startOffset else 0
    val upper = if (index == slice.endIndex) slice.endOffset else fragment.text.length
    return CjkDocumentSelectionProjection(
        range = TextRange(lower, upper).takeUnless(TextRange::isEmpty),
        selectedSeparatorBefore = if (index > slice.startIndex) this[index - 1].separatorAfter else "",
        selectedSeparatorAfter = if (index < slice.endIndex) fragment.separatorAfter else "",
    ).takeUnless(CjkDocumentSelectionProjection::isEmpty)
}
