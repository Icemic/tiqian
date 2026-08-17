package org.tiqian.layout

import org.tiqian.clreq.AutoSpaceMode
import org.tiqian.clreq.AutoSpacePolicy
import org.tiqian.clreq.BuiltInClreqProfileResolver
import org.tiqian.clreq.ClreqProfile
import org.tiqian.clreq.ClreqProfileResolver
import org.tiqian.clreq.ClreqPunctuationPolicies
import org.tiqian.clreq.HangingPunctuationStyle
import org.tiqian.clreq.KinsokuLevel
import org.tiqian.clreq.LineAdjustmentStrategy
import org.tiqian.clreq.LineEndPunctuationStyle
import org.tiqian.clreq.NumberSymbolCohesion
import org.tiqian.clreq.PunctuationClass
import org.tiqian.clreq.PunctuationGluePlacement
import org.tiqian.clreq.ClreqPunctuationGlyphSubstitutor
import org.tiqian.core.AutoSpaceDecisionInfo
import org.tiqian.core.BreakOpportunityDecisionInfo
import org.tiqian.core.Cluster
import org.tiqian.core.EmergencyTrackingEligibilityDecisionInfo
import org.tiqian.core.EastAsianSpacingEdges
import org.tiqian.core.EastAsianSpacingValue
import org.tiqian.core.UnicodeEastAsianSpacing
import org.tiqian.core.ClusterGeometryDecisionInfo
import org.tiqian.core.ContextualKinsokuDecisionInfo
import org.tiqian.core.DecorationDecisionInfo
import org.tiqian.core.RubyDecisionInfo
import org.tiqian.clreq.BopomofoParser
import org.tiqian.clreq.BopomofoTone
import org.tiqian.core.RubyKind
import org.tiqian.core.RubyLineHeightDecisionInfo
import org.tiqian.core.RubyLineHeightMode
import org.tiqian.core.RubySpan
import org.tiqian.core.BopomofoDecisionInfo
import org.tiqian.core.BopomofoGlyphPlacement
import org.tiqian.core.BopomofoGlyphRole
import org.tiqian.core.DecorationKind
import org.tiqian.core.DecorationSegmentInfo
import org.tiqian.core.DecorationSpan
import org.tiqian.core.FontDecisionInfo
import org.tiqian.core.LineEdgeTrimDecisionInfo
import org.tiqian.core.Glyph
import org.tiqian.core.GlyphRun
import org.tiqian.core.JustificationAllocationInfo
import org.tiqian.core.JustificationDecisionInfo
import org.tiqian.core.LayoutDebugInfo
import org.tiqian.core.LayoutInput
import org.tiqian.core.LayoutResult
import org.tiqian.core.LineBreakPolicy
import org.tiqian.core.InlineBoxDecisionInfo
import org.tiqian.core.InlineBoxOuterSpacing
import org.tiqian.core.InlineBoxSpan
import org.tiqian.core.InlineAttachment
import org.tiqian.core.InlineObjectBoundaryAdjustment
import org.tiqian.core.InlineObjectDecisionInfo
import org.tiqian.core.InlineObjectLineHeightDecisionInfo
import org.tiqian.core.InlineObjectPunctuationAttachmentDecisionInfo
import org.tiqian.core.InlineObjectPreferredStretch
import org.tiqian.core.InlineObjectSpan
import org.tiqian.core.LineBox
import org.tiqian.core.LineDebugInfo
import org.tiqian.core.LineDecisionInfo
import org.tiqian.core.LineEndReason
import org.tiqian.core.LineRepairAllocationInfo
import org.tiqian.core.LineRepairCandidateInfo
import org.tiqian.core.LineRepairDecisionInfo
import org.tiqian.core.MandatoryBreakDecisionInfo
import org.tiqian.core.ZeroWidthBreakDecisionInfo
import org.tiqian.core.MaxLinesDecisionInfo
import org.tiqian.core.MetricDecisionInfo
import org.tiqian.core.PunctuationDecisionInfo
import org.tiqian.core.Rect
import org.tiqian.core.RoleOverrideInfo
import org.tiqian.core.Size
import org.tiqian.core.SpacingDecisionInfo
import org.tiqian.core.ShapingDecisionInfo
import org.tiqian.core.LastLineAlignment
import org.tiqian.core.KinsokuDecisionInfo
import org.tiqian.core.LineLengthGridDecisionInfo
import org.tiqian.core.FirstLineIndentDecisionInfo
import kotlin.math.floor
import kotlin.text.CharCategory
import org.tiqian.core.LineSpacingDecisionInfo
import org.tiqian.core.TextRange
import org.tiqian.core.TextStyle
import org.tiqian.core.sourceGraphemeBoundaries
import org.tiqian.font.CjkFontRoleClassifier
import org.tiqian.font.FallbackResolver
import org.tiqian.font.FontMetricsNormalizationInput
import org.tiqian.font.FontMetricsNormalizer
import org.tiqian.font.FontMetricsRequest
import org.tiqian.font.FontMetricsResolver
import org.tiqian.font.FontDecision
import org.tiqian.font.FontRequest
import org.tiqian.font.FontRole
import org.tiqian.font.BaselineClass
import org.tiqian.font.MetricBox
import org.tiqian.font.FontRoleClassifier
import org.tiqian.font.FontRoleContext
import org.tiqian.font.LayoutFontMetrics
import org.tiqian.font.PreferCjkForAmbiguousPunctuationResolver
import org.tiqian.font.RawFontMetrics
import org.tiqian.font.ScriptAwareFontMetricsNormalizer
import org.tiqian.font.StubFontMetricsResolver
import org.tiqian.linebreak.Hyphenator
import org.tiqian.linebreak.isMandatoryBreakCodePoint
import org.tiqian.linebreak.isZeroWidthSpaceCodePoint
import org.tiqian.linebreak.NoHyphenator
import org.tiqian.shaping.ExplainableStubTextShaper
import org.tiqian.shaping.ShapingInput
import org.tiqian.shaping.ShapingResult
import org.tiqian.shaping.TextShaper
import org.tiqian.shaping.UNVERIFIED_DISPLAY_SUBSTITUTION_COVERAGE_ISSUE

/** Monotonic interval join for source-ordered clusters and source-ordered decisions. */
internal fun <T> List<Cluster>.containingItems(
    items: List<T>,
    rangeOf: (T) -> TextRange,
): List<T?> {
    var itemIndex = 0
    return map { cluster ->
        while (itemIndex < items.size && rangeOf(items[itemIndex]).end <= cluster.range.start) {
            itemIndex += 1
        }
        items.getOrNull(itemIndex)?.takeIf { item ->
            val range = rangeOf(item)
            cluster.range.start >= range.start && cluster.range.end <= range.end
        }
    }
}

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

/** 行间注 (ruby, ADR 0032): 注文常用基文 1/2 字号 (CLREQ 振假名惯例). */
private const val RUBY_FONT_EM = 0.5f

/** `RubyLegibilityWeightBoost`: 拼音注文默认比基文重 100，保持轻而清楚. */
private const val RUBY_FONT_WEIGHT_BOOST = 100

/** `BopomofoLegibilityWeightBoost`: 注音 ㄅㄆㄇ 更小，默认比基文重 300. */
private const val BOPOMOFO_FONT_WEIGHT_BOOST = 300

internal const val MANDATORY_BREAK_FONT_KEY = "mandatory-break"

/**
 * 避让 最小间距 (CLREQ §罗马拼音「相邻注文的间距不应小于西文词间空格」): one 注文
 * word space ≈ 1/4 of the 注文 em. Measured in 注文 units, NOT base 字宽.
 */
private const val RUBY_MIN_GAP_EM_OF_RUBY = 0.25f

/**
 * Extra clearance between the注文 Latin font box and the base 字身框顶 — **default 0**.
 * The base glyph has its own internal top margin; bump only if a style wants
 * visibly looser ruby.
 */
private const val RUBY_STACK_GAP_EM = 0f

/** CLREQ 挤压第②档：西文词距最小压至四分之一汉字宽. */
private const val WORD_SPACE_MIN_EM = 0.25f

/** CLREQ 挤压⑥：行内中西间距「最小挤为八分之一汉字宽」. */
private const val SINO_WESTERN_GAP_MIN_EM = 0.125f

/** CLREQ 挤压第④档对象：「位于行内的句号、问号、感叹号」. */
private val INLINE_STOPS = setOf('。', '！', '？', '．')
