package org.tiqian.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.tiqian.core.InlineObjectBoundaryAdjustment
import org.tiqian.core.InlineObjectPreferredStretch
import org.tiqian.core.InlineObjectPreferredStretchKind
import org.tiqian.core.InlineObjectSpan

enum class CjkInlineObjectPreferredStretchKind {
    PunctuationTrailing,
    Relation,
    BinaryOperator,
}

@Immutable
data class CjkInlineObjectPreferredStretch(
    val kind: CjkInlineObjectPreferredStretchKind,
    val naturalWidth: Dp,
    val targetWidth: Dp,
) {
    val capacity: Dp get() = targetWidth - naturalWidth

    init {
        require(naturalWidth.value.isFinite() && naturalWidth.value >= 0f) {
            "preferred stretch natural width must be finite and non-negative"
        }
        require(targetWidth.value.isFinite() && targetWidth > naturalWidth) {
            "preferred stretch target must be finite and exceed its natural width"
        }
    }
}

/** Paragraph adjustment allowed at one edge of a [CjkInlineObject]. */
@Immutable
data class CjkInlineObjectBoundary(
    val participatesInUniformStretch: Boolean = false,
    val preferredStretch: CjkInlineObjectPreferredStretch? = null,
    /** Trailing blank already included in the object's advance that may be removed as last resort. */
    val shrinkCapacity: Dp = 0.dp,
    /** Trailing blank removed only when this boundary is chosen as an automatic line end. */
    val lineEndDiscardableAdvance: Dp = 0.dp,
    /** Keep this adjustment-only edge closed to paragraph line breaking. */
    val preventsLineBreak: Boolean = false,
) {
    init {
        require(shrinkCapacity.value.isFinite() && shrinkCapacity.value >= 0f) {
            "shrinkCapacity must be finite and non-negative"
        }
        require(lineEndDiscardableAdvance.value.isFinite() && lineEndDiscardableAdvance.value >= 0f) {
            "lineEndDiscardableAdvance must be finite and non-negative"
        }
    }

    companion object {
        val Fixed = CjkInlineObjectBoundary()
    }
}

/**
 * One measured Compose object embedded in a [CjkText] paragraph.
 *
 * [ascent] and [descent] are measured from the object's own baseline. Tiqian feeds this
 * geometry into line breaking and line-box construction. Existing inter-line space is consumed
 * while retaining the paragraph's configured minimum visible-content clearance before the baseline
 * grid expands; [content] is then placed so that its baseline coincides with
 * the final line baseline. The covered source range remains available to
 * copying and accessibility, so callers should prefer non-empty alternate text over U+FFFC when
 * the object has a meaningful textual representation.
 */
@Immutable
data class CjkInlineObject(
    val range: TextRange,
    val advance: Dp,
    val ascent: Dp,
    val descent: Dp,
    val leadingBoundary: CjkInlineObjectBoundary = CjkInlineObjectBoundary.Fixed,
    val trailingBoundary: CjkInlineObjectBoundary = CjkInlineObjectBoundary.Fixed,
    val content: @Composable () -> Unit,
) {
    init {
        require(range.length > 0) { "CjkInlineObject must cover a non-empty source range" }
        require(advance.value.isFinite() && advance.value > 0f) { "advance must be finite and positive" }
        require(ascent.value.isFinite() && ascent.value >= 0f) { "ascent must be finite and non-negative" }
        require(descent.value.isFinite() && descent.value >= 0f) { "descent must be finite and non-negative" }
        require(leadingBoundary.shrinkCapacity == 0.dp) {
            "leadingBoundary cannot shrink because that would move the inline object's paint origin"
        }
        require(leadingBoundary.lineEndDiscardableAdvance == 0.dp) {
            "leadingBoundary cannot discard advance because that would move the inline object's paint origin"
        }
        require(trailingBoundary.lineEndDiscardableAdvance <= advance) {
            "trailingBoundary cannot discard more than the inline object's advance"
        }
    }
}

internal fun CjkInlineObject.toCore(density: Density): InlineObjectSpan = InlineObjectSpan(
    range = org.tiqian.core.TextRange(range.start, range.end),
    advance = with(density) { advance.toPx() },
    ascent = with(density) { ascent.toPx() },
    descent = with(density) { descent.toPx() },
    leadingBoundary = leadingBoundary.toCore(density),
    trailingBoundary = trailingBoundary.toCore(density),
)

private fun CjkInlineObjectBoundary.toCore(density: Density): InlineObjectBoundaryAdjustment =
    InlineObjectBoundaryAdjustment(
        participatesInUniformStretch = participatesInUniformStretch,
        preferredStretch = preferredStretch?.let {
            InlineObjectPreferredStretch(
                kind = when (it.kind) {
                    CjkInlineObjectPreferredStretchKind.PunctuationTrailing ->
                        InlineObjectPreferredStretchKind.PunctuationTrailing
                    CjkInlineObjectPreferredStretchKind.Relation ->
                        InlineObjectPreferredStretchKind.Relation
                    CjkInlineObjectPreferredStretchKind.BinaryOperator ->
                        InlineObjectPreferredStretchKind.BinaryOperator
                },
                naturalWidth = with(density) { it.naturalWidth.toPx() },
                targetWidth = with(density) { it.targetWidth.toPx() },
            )
        },
        shrinkCapacity = with(density) { shrinkCapacity.toPx() },
        lineEndDiscardableAdvance = with(density) { lineEndDiscardableAdvance.toPx() },
        preventsLineBreak = preventsLineBreak,
    )
