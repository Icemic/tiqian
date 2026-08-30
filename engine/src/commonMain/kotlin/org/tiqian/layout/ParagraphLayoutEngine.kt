package org.tiqian.layout

import org.tiqian.clreq.BuiltInClreqProfileResolver
import org.tiqian.clreq.ClreqProfileResolver
import org.tiqian.core.LayoutInput
import org.tiqian.core.LayoutResult
import org.tiqian.core.TextRange
import org.tiqian.font.CjkFontRoleClassifier
import org.tiqian.font.FallbackResolver
import org.tiqian.font.FontMetricsNormalizer
import org.tiqian.font.FontMetricsResolver
import org.tiqian.font.FontRoleClassifier
import org.tiqian.font.PreferCjkForAmbiguousPunctuationResolver
import org.tiqian.font.ScriptAwareFontMetricsNormalizer
import org.tiqian.font.StubFontMetricsResolver
import org.tiqian.linebreak.Hyphenator
import org.tiqian.shaping.ExplainableStubTextShaper
import org.tiqian.shaping.TextShaper

internal const val MANDATORY_BREAK_FONT_KEY = "mandatory-break"

interface ParagraphLayoutEngine {
    fun layout(input: LayoutInput): LayoutResult
}

class ExplainableStubParagraphLayoutEngine(
    internal val fontRoleClassifier: FontRoleClassifier = CjkFontRoleClassifier(),
    internal val fallbackResolver: FallbackResolver = PreferCjkForAmbiguousPunctuationResolver(),
    internal val clreqProfileResolver: ClreqProfileResolver = BuiltInClreqProfileResolver,
    internal val fontMetricsResolver: FontMetricsResolver = StubFontMetricsResolver(),
    internal val fontMetricsNormalizer: FontMetricsNormalizer = ScriptAwareFontMetricsNormalizer(),
    internal val punctuationAtomBuilder: PunctuationAtomBuilder = PunctuationAtomBuilder(),
    internal val punctuationSpacingCompressor: PunctuationSpacingCompressor = PunctuationSpacingCompressor(),
    internal val quotePairAnalyzer: QuotePairAnalyzer = QuotePairAnalyzer(),
    internal val lineBreaker: LineBreaker = GreedyLineBreaker(),
    internal val justifier: Justifier = Justifier(),
    internal val textShaper: TextShaper = ExplainableStubTextShaper(),
    /**
     * Western syllable hyphenation source (CLREQ「可使用连字符处」). Defaults to
     * the platform hyphenator ([defaultHyphenator]: en-US on JVM) so
     * `LineEndHangingHyphen` is ON by default; pass [NoHyphenator] to opt out.
     * (`LatinForcedHyphenBreak` over-long hard-break fires regardless.)
     */
    internal val hyphenator: Hyphenator = defaultHyphenator(),
    val annotationCache: WidthIndependentAnnotationCache = LruWidthIndependentAnnotationCache(),
) : ParagraphLayoutEngine {
    override fun layout(input: LayoutInput): LayoutResult =
        layoutWithRejectedTechnicalTiers(input, emptyMap())

    internal fun layoutWithRejectedTechnicalTiers(
        input: LayoutInput,
        rejectedTechnicalTiersBySpan: Map<TextRange, Set<ProgressiveBreakTier>>,
    ): LayoutResult {
        validateLayoutInput(input)
        val cacheKey = input.toWidthIndependentAnnotationKey(rejectedTechnicalTiersBySpan)
        val annotation = (annotationCache.get(cacheKey) as? WidthIndependentParagraphAnnotation)
            ?: prepareWidthIndependentAnnotation(input, rejectedTechnicalTiersBySpan).also {
                annotationCache.put(cacheKey, it)
            }
        val prep = buildParagraphLayoutPrep(input, annotation, rejectedTechnicalTiersBySpan)
        return finishParagraphLayout(prep, planParagraphLines(prep))
    }

    private fun validateLayoutInput(input: LayoutInput) {
        val text = input.content.text
        require(input.paragraphStyle.emphasisDotGapEm.isFinite() && input.paragraphStyle.emphasisDotGapEm >= 0f) {
            "ParagraphStyle.emphasisDotGapEm must be finite and non-negative"
        }
        require(
            input.paragraphStyle.inlineObjectMinimumClearanceEm.isFinite() &&
                input.paragraphStyle.inlineObjectMinimumClearanceEm >= 0f,
        ) {
            "ParagraphStyle.inlineObjectMinimumClearanceEm must be finite and non-negative"
        }
        // Downstream code-point scans rely on well-formed UTF-16: a surrogate
        // must always be part of a complete pair.
        var surrogateScan = 0
        while (surrogateScan < text.length) {
            val code = text[surrogateScan].code
            if (code in 0xD800..0xDBFF) {
                require(surrogateScan + 1 < text.length && text[surrogateScan + 1].code in 0xDC00..0xDFFF) {
                    "SourceText has an unpaired high surrogate at char $surrogateScan"
                }
                surrogateScan += 2
            } else {
                require(code !in 0xDC00..0xDFFF) {
                    "SourceText has an unpaired low surrogate at char $surrogateScan"
                }
                surrogateScan += 1
            }
        }
        input.inlineBoxes.forEach { inlineBox ->
            require(
                inlineBox.range.start >= 0 &&
                    inlineBox.range.start < inlineBox.range.end &&
                    inlineBox.range.end <= text.length,
            ) {
                "InlineBoxSpan ${inlineBox.range} must be a non-empty source range"
            }
            require(inlineBox.inlineStart.isFinite() && inlineBox.inlineEnd.isFinite()) {
                "InlineBoxSpan ${inlineBox.range} must have finite inline edges"
            }
        }
        input.content.lineBreakSpans.forEach { span ->
            require(
                span.range.start >= 0 && span.range.start < span.range.end && span.range.end <= text.length,
            ) {
                "LineBreakSpan ${span.range} must be a non-empty source range"
            }
        }
        input.content.autoSpaceSuppressedRanges.forEach { range ->
            require(range.start >= 0 && range.start < range.end && range.end <= text.length) {
                "Auto-space suppressed range $range must be a non-empty source range"
            }
        }
        require(input.inlineObjects.distinctBy { it.range }.size == input.inlineObjects.size) {
            "InlineObjectSpan ranges must be unique"
        }
        input.inlineObjects.sortedBy { it.range.start }.zipWithNext().forEach { (previous, next) ->
            require(previous.range.end <= next.range.start) {
                "InlineObjectSpan ranges must not overlap: ${previous.range} and ${next.range}"
            }
        }
        input.inlineObjects.forEach { inlineObject ->
            require(
                inlineObject.range.start >= 0 &&
                    inlineObject.range.start < inlineObject.range.end &&
                    inlineObject.range.end <= text.length,
            ) {
                "InlineObjectSpan ${inlineObject.range} must cover a non-empty source range"
            }
            require(
                inlineObject.advance.isFinite() && inlineObject.advance > 0f &&
                    inlineObject.ascent.isFinite() && inlineObject.ascent >= 0f &&
                    inlineObject.descent.isFinite() && inlineObject.descent >= 0f,
            ) {
                "InlineObjectSpan ${inlineObject.range} must have finite positive geometry"
            }
            require(inlineObject.leadingBoundary.shrinkCapacity == 0f) {
                "InlineObjectSpan ${inlineObject.range} cannot shrink its leading boundary"
            }
            require(inlineObject.leadingBoundary.lineEndDiscardableAdvance == 0f) {
                "InlineObjectSpan ${inlineObject.range} cannot discard advance at its leading boundary"
            }
            require(inlineObject.trailingBoundary.shrinkCapacity <= inlineObject.advance) {
                "InlineObjectSpan ${inlineObject.range} trailing shrink capacity must not exceed its advance"
            }
            require(inlineObject.trailingBoundary.lineEndDiscardableAdvance <= inlineObject.advance) {
                "InlineObjectSpan ${inlineObject.range} trailing line-end discard must not exceed its advance"
            }
        }
    }
}
