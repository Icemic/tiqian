package org.tiqian.font

/**
 * `InlineShapingStyleParityContract`: TextStyle only models family, size, weight,
 * italic and baseline shift. The renderer preserves semantic wrappers, so an
 * inherited shaping property that changes only inside such a wrapper would
 * otherwise make browser glyph advances diverge from LayoutResult. The first
 * divergent property is what makes lowering fail.
 */
object InlineShapingStylePolicy {
    /**
     * The sixteen inherited shaping properties the lowering engine compares
     * between an inline element and its paragraph baseline. Order matters:
     * the first divergent property is the one reported, so this list must
     * not be reordered.
     */
    val unsupportedInlineShapingProperties: List<String> = listOf(
        "font-feature-settings",
        "font-variation-settings",
        "font-stretch",
        "font-kerning",
        "font-optical-sizing",
        "font-variant-ligatures",
        "font-variant-alternates",
        "font-variant-east-asian",
        "font-variant-caps",
        "font-variant-numeric",
        "font-variant-position",
        "font-language-override",
        "font-size-adjust",
        "word-spacing",
        "text-transform",
        "text-rendering",
    )

    /**
     * Returns the name of the first property whose element value differs from
     * the paragraph value, comparing [elementValues] and [paragraphValues] by
     * index. A length mismatch is treated as a common-prefix comparison as a
     * defensive measure; both sides are normally equal length. Returns null
     * when every compared pair matches.
     */
    fun firstDivergentProperty(elementValues: List<String>, paragraphValues: List<String>): String? {
        val commonLength = minOf(
            elementValues.size,
            paragraphValues.size,
            unsupportedInlineShapingProperties.size,
        )
        for (i in 0 until commonLength) {
            if (elementValues[i] != paragraphValues[i]) return unsupportedInlineShapingProperties[i]
        }
        return null
    }
}