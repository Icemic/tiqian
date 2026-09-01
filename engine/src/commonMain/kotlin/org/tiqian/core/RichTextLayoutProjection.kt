package org.tiqian.core

/**
 * Projects render-only range edges into source cluster boundaries required by geometry queries.
 *
 * Frontends must use this shared projection instead of deriving their own boundary set: color,
 * link and decoration ranges do not change line layout, but their exact source edges must survive
 * shaping so hit testing and paint geometry do not slice a larger cluster proportionally.
 */
fun sourceBoundariesForPresentation(
    textLength: Int,
    decorations: List<DecorationSpan> = emptyList(),
    colorSpans: List<ColorSpan> = emptyList(),
    richTextSpans: List<RichTextSpan> = emptyList(),
    textSpans: List<TextSpan> = emptyList(),
    rubySpans: List<RubySpan> = emptyList(),
): Set<Int> = buildSet {
    fun addBoundary(offset: Int) {
        if (offset > 0 && offset < textLength) add(offset)
    }

    fun addRange(start: Int, end: Int) {
        addBoundary(start)
        addBoundary(end)
    }

    decorations.forEach { addRange(it.range.start, it.range.end) }
    colorSpans.forEach { addRange(it.start, it.end) }
    richTextSpans.forEach { addRange(it.range.start, it.range.end) }
    textSpans.forEach { addRange(it.range.start, it.range.end) }
    rubySpans.forEach { addRange(it.baseRange.start, it.baseRange.end) }
}

/** Shared rich-text projection for named technical line-breaking policies. */
fun List<RichTextSpan>.layoutLineBreakSpans(text: String): List<LineBreakSpan> =
    asSequence()
        .filter { span ->
            when (val role = span.role) {
                is RichTextRole.Link -> LinkAddressDisplay.displaysAddress(
                    display = text.substring(span.range.start, span.range.end),
                    target = role.target,
                )

                RichTextRole.InlineCode, RichTextRole.TechnicalInline -> true
                else -> false
            }
        }
        .map { LineBreakSpan(it.range, LineBreakPolicy.ProgressiveTechnical) }
        .distinctBy { it.range to it.policy }
        .sortedWith(compareBy<LineBreakSpan>({ it.range.start }, { it.range.end }))
        .toList()

/** Inline-code and technical ranges preserve source while suppressing internal auto spacing. */
fun List<RichTextSpan>.layoutAutoSpaceSuppressedRanges(): List<TextRange> =
    asSequence()
        .filter { it.role == RichTextRole.InlineCode || it.role == RichTextRole.TechnicalInline }
        .map { it.range }
        .distinct()
        .sortedWith(compareBy({ it.start }, { it.end }))
        .toList()

/** Reserves renderer-owned horizontal padding in the engine's line-breaking geometry. */
fun List<RichTextSpan>.layoutInlineBoxes(): List<InlineBoxSpan> = mapNotNull { span ->
    val horizontalPadding = span.paint.background.horizontalPadding
    if (
        horizontalPadding == 0f ||
        (span.role != RichTextRole.Background && span.role != RichTextRole.InlineCode)
    ) {
        null
    } else {
        InlineBoxSpan(
            range = span.range,
            inlineStart = horizontalPadding,
            inlineEnd = horizontalPadding,
        )
    }
}
