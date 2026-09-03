package org.tiqian.android.view

import android.graphics.Color
import org.tiqian.core.ColorSpan
import org.tiqian.core.DecorationSpan
import org.tiqian.core.InlineBoxSpan
import org.tiqian.core.InlineObjectSpan
import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LayoutInput
import org.tiqian.core.ParagraphStyle
import org.tiqian.core.RichTextSpan
import org.tiqian.core.RubySpan
import org.tiqian.core.TextStyle
import org.tiqian.core.TiqianTextContent
import org.tiqian.core.layoutAutoSpaceSuppressedRanges
import org.tiqian.core.layoutInlineBoxes
import org.tiqian.core.layoutLineBreakSpans
import org.tiqian.core.sourceBoundariesForPresentation

/**
 * Immutable, source-faithful paragraph model consumed by [CjkTextView].
 *
 * Layout-affecting input and render-only spans are submitted atomically so a View never displays
 * paint ranges from a different source revision than its [org.tiqian.core.LayoutResult].
 */
data class CjkTextContent(
    val content: TiqianTextContent,
    val textStyle: TextStyle = TextStyle(),
    val paragraphStyle: ParagraphStyle = ParagraphStyle(),
    val textColor: Int = Color.BLACK,
    val colorSpans: List<ColorSpan> = emptyList(),
    val richTextSpans: List<RichTextSpan> = emptyList(),
    val decorations: List<DecorationSpan> = emptyList(),
    val rubySpans: List<RubySpan> = emptyList(),
    val inlineBoxes: List<InlineBoxSpan> = emptyList(),
    val inlineObjects: List<InlineObjectSpan> = emptyList(),
) {
    /** Builds the exact engine input used by [CjkTextView] for background precomputation. */
    @JvmOverloads
    fun layoutInput(
        maxWidth: Float,
        maxHeight: Float = Float.POSITIVE_INFINITY,
        maxLines: Int = Int.MAX_VALUE,
    ): LayoutInput {
        val derivedBoundaries = sourceBoundariesForPresentation(
            textLength = content.text.length,
            decorations = decorations,
            colorSpans = colorSpans,
            richTextSpans = richTextSpans,
            textSpans = content.spans,
            rubySpans = rubySpans,
        )
        return LayoutInput(
            content = content.copy(
                sourceBoundaries = content.sourceBoundaries + derivedBoundaries,
                lineBreakSpans = (content.lineBreakSpans + richTextSpans.layoutLineBreakSpans(content.text))
                    .distinctBy { it.range to it.policy }
                    .sortedWith(compareBy({ it.range.start }, { it.range.end })),
                autoSpaceSuppressedRanges =
                    (content.autoSpaceSuppressedRanges + richTextSpans.layoutAutoSpaceSuppressedRanges())
                        .distinct()
                        .sortedWith(compareBy({ it.start }, { it.end })),
            ),
            textStyle = textStyle,
            paragraphStyle = paragraphStyle,
            constraints = LayoutConstraints(
                maxWidth = maxWidth,
                maxHeight = maxHeight,
                maxLines = maxLines,
            ),
            decorations = decorations,
            rubySpans = rubySpans,
            inlineBoxes = (inlineBoxes + richTextSpans.layoutInlineBoxes()).distinct(),
            inlineObjects = inlineObjects,
        )
    }
}

/** Overflow policies that preserve the engine's source text. */
enum class CjkTextOverflow {
    Clip,
    Visible,
}
