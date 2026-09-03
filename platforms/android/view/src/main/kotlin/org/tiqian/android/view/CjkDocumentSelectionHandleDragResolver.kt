package org.tiqian.android.view

import org.tiqian.core.SourceBoundaryBias
import org.tiqian.core.coerceSelectionOffset
import org.tiqian.core.cursorRect
import org.tiqian.core.getLineForOffset
import org.tiqian.core.selectionOffsetForPosition

/**
 * Resolves a document handle against attached paragraph geometry.
 *
 * The document controller owns selection state and lifecycle. This class owns only the
 * handle-specific projection and interaction-boundary lookup, so a logical anchor remains a
 * `(key, offset)` value throughout a drag.
 */
internal class CjkDocumentSelectionHandleDragResolver(
    private val ordering: CjkDocumentSelectionOrder,
    private val endpointResolver: CjkSelectionEndpointResolver,
    private val anchorView: (CjkDocumentSelectionAnchor) -> CjkTextView?,
) {
    /**
     * Mirrors Editor.SelectionHandleView's crossed-line projection for a logical document. The
     * selection remains ordered and non-empty, but x keeps controlling the moving endpoint on the
     * fixed endpoint's line after the finger has crossed into an earlier/later fragment.
     */
    fun projectCrossedPointerOntoFixedLine(
        handle: CjkSelectionHandle,
        candidate: HandleHit,
        fixedEndpoint: CjkDocumentSelectionAnchor,
        rawX: Float,
        rawY: Float,
    ): HandleHit {
        val crossed = when (handle) {
            CjkSelectionHandle.Start -> ordering.compareAnchors(candidate.anchor, fixedEndpoint) >= 0
            CjkSelectionHandle.End -> ordering.compareAnchors(candidate.anchor, fixedEndpoint) <= 0
        }
        if (!crossed) return candidate
        val fixedView = anchorView(fixedEndpoint) ?: return candidate
        val snapshot = fixedView.layoutSnapshot ?: return candidate
        if (snapshot.result.lines.isEmpty()) return candidate
        val lineIndex = snapshot.result.getLineForOffset(fixedEndpoint.offset)
            .coerceIn(0, snapshot.result.lines.lastIndex)
        endpointResolver.forceLine(fixedEndpoint.key, ordering.orderOf(fixedEndpoint.key), lineIndex)
        val line = snapshot.result.lines[lineIndex]
        val local = rawToView(fixedView, rawX, rawY)
        val contentX = fixedView.toContentX(local.first)
        val queryY = (line.top + line.bottom) / 2f
        val rawOffset = snapshot.replayIndex.selectionOffsetForPosition(
            snapshot.result,
            contentX,
            queryY,
        )
        val offset = snapshot.result.coerceSelectionOffset(rawOffset, SourceBoundaryBias.Nearest)
        return HandleHit(
            fixedView,
            fixedEndpoint.key,
            ordering.orderOf(fixedEndpoint.key),
            snapshot,
            contentX,
            queryY,
            offset,
        )
    }

    fun previousBoundary(anchor: CjkDocumentSelectionAnchor): CjkDocumentSelectionAnchor? {
        anchorView(anchor)?.layoutSnapshot?.result?.let { result ->
            val previous = result.coerceSelectionOffset(
                (anchor.offset - 1).coerceAtLeast(0),
                SourceBoundaryBias.Backward,
            )
            if (previous < anchor.offset) return CjkDocumentSelectionAnchor(anchor.key, previous)
        }
        val index = ordering.orderOf(anchor.key)
        val previous = ordering.logicalFragments().subList(0, index.coerceAtLeast(0))
            .asReversed()
            .firstOrNull { it.text.isNotEmpty() }
            ?: return null
        val offset = previous.coerceSelectionOffset(
            (previous.text.length - 1).coerceAtLeast(0),
            SourceBoundaryBias.Backward,
        )
        return CjkDocumentSelectionAnchor(previous.key, offset)
    }

    fun nextBoundary(anchor: CjkDocumentSelectionAnchor): CjkDocumentSelectionAnchor? {
        anchorView(anchor)?.layoutSnapshot?.result?.let { result ->
            val next = result.coerceSelectionOffset(anchor.offset + 1, SourceBoundaryBias.Forward)
            if (next > anchor.offset) return CjkDocumentSelectionAnchor(anchor.key, next)
        }
        val index = ordering.orderOf(anchor.key)
        val next = ordering.logicalFragments().drop(index + 1)
            .firstOrNull { it.text.isNotEmpty() }
            ?: return null
        val offset = next.coerceSelectionOffset(
            1.coerceAtMost(next.text.length),
            SourceBoundaryBias.Forward,
        )
        return CjkDocumentSelectionAnchor(next.key, offset)
    }
}
