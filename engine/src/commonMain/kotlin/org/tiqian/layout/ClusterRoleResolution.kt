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
import org.tiqian.font.UnicodeEmojiPresentationData
import org.tiqian.linebreak.Hyphenator
import org.tiqian.linebreak.isMandatoryBreakCodePoint
import org.tiqian.linebreak.isZeroWidthSpaceCodePoint
import org.tiqian.linebreak.NoHyphenator
import org.tiqian.shaping.ExplainableStubTextShaper
import org.tiqian.shaping.ShapingInput
import org.tiqian.shaping.ShapingResult
import org.tiqian.shaping.TextShaper
import org.tiqian.shaping.UNVERIFIED_DISPLAY_SUBSTITUTION_COVERAGE_ISSUE

private val COMBINING_MARK_CATEGORIES = setOf(
    CharCategory.NON_SPACING_MARK,
    CharCategory.COMBINING_SPACING_MARK,
    CharCategory.ENCLOSING_MARK,
)

internal fun clusterRoleRanges(
    text: String,
    classifier: FontRoleClassifier,
    context: FontRoleContext,
    profile: ClreqProfile,
    spanBoundaries: Set<Int>,
    emojiShapingBoundaries: Set<Int>,
    inlineObjectsByStart: Map<Int, InlineObjectSpan> = emptyMap(),
): List<ResolvedClusterRange> {
    val sourceGraphemeBoundaries = text.sourceGraphemeBoundaries(TextRange(0, text.length))
    val coalesceSet = profile.coalesceRepeatablePunctuation
    val ranges = mutableListOf<ResolvedClusterRange>()
    var index = 0
    var graphemeBoundaryIndex = if (text.isEmpty()) 0 else 1
    var graphemeStart = sourceGraphemeBoundaries.first()
    var graphemeEnd = sourceGraphemeBoundaries.getOrElse(graphemeBoundaryIndex) { text.length }
    while (index < text.length) {
        while (index >= graphemeEnd && graphemeBoundaryIndex < sourceGraphemeBoundaries.lastIndex) {
            graphemeStart = graphemeEnd
            graphemeBoundaryIndex += 1
            graphemeEnd = sourceGraphemeBoundaries[graphemeBoundaryIndex]
        }
        val inlineObject = inlineObjectsByStart[index]
        if (inlineObject != null) {
            ranges.add(
                ResolvedClusterRange(
                    range = inlineObject.range,
                    role = FontRole.Unknown,
                ),
            )
            index = inlineObject.range.end
            continue
        }
        val codePoint = text.codePointAtCompat(index)
        val charCount = codePoint.charCount()
        val start = index
        if (codePoint.isMandatoryBreakCodePointAt(text, index)) {
            val end = if (codePoint == 0x000D && index + 1 < text.length && text[index + 1] == '\n') {
                index + 2
            } else {
                index + charCount
            }
            ranges.add(
                ResolvedClusterRange(
                    range = TextRange(start, end),
                    role = FontRole.Unknown,
                    mandatoryBreak = true,
                ),
            )
            index = end
            continue
        }
        if (isZeroWidthSpaceCodePoint(codePoint)) {
            val end = index + charCount
            ranges.add(
                ResolvedClusterRange(
                    range = TextRange(start, end),
                    role = FontRole.Unknown,
                    zeroWidthSoftBreak = true,
                ),
            )
            index = end
            continue
        }
        val firstRange = TextRange(start, start + charCount)
        val classifiedRole = classifier.classify(text, firstRange, context)
        val role = if (
            classifiedRole == FontRole.Emoji ||
            text.hasEmojiPresentationSignal(graphemeStart, graphemeEnd)
        ) {
            FontRole.Emoji
        } else {
            classifiedRole
        }
        val previousRange = ranges.lastOrNull()
        val attachedAsciiPointMark =
            role == FontRole.LatinText &&
                codePoint.isAsciiPointMarkCodePoint() &&
                previousRange != null &&
                previousRange.role != FontRole.Unknown &&
                text.getOrNull(previousRange.range.end - 1)?.isWhitespace() == false &&
                previousRange.range.end == start

        index += charCount
        if (role == FontRole.Emoji) {
            // `EmojiGraphemeShapingAtomicity`: source graphemes preserve
            // modifier, variation-selector, keycap, RI-pair, tag, and ZWJ
            // shaping context. A real layout style/object edge still wins:
            // ShapingInput has one TextStyle, so silently crossing that edge
            // would discard author intent. Geometry-only source boundaries
            // deliberately do not participate here.
            index = emojiShapingBoundaries
                .asSequence()
                .filter { it > start && it < graphemeEnd }
                .minOrNull()
                ?: graphemeEnd
        } else if (role == FontRole.LatinText) {
            if (attachedAsciiPointMark) {
                // `AttachedAsciiPointMarkSegmentation`: keep the leading
                // point-mark run independent from following Latin text so
                // kinsoku never has to move an entire `,anyway` token.
                while (index < text.length && index !in spanBoundaries) {
                    val nextCodePoint = text.codePointAtCompat(index)
                    if (!nextCodePoint.isAsciiPointMarkCodePoint()) break
                    index += nextCodePoint.charCount()
                }
            } else {
                // A sized-span edge inside a Latin run / coalesced 标点 run ends the
                // cluster there so each cluster carries a single font size (ADR 0030).
                while (index < text.length && index !in spanBoundaries) {
                    val nextCodePoint = text.codePointAtCompat(index)
                    val nextCharCount = nextCodePoint.charCount()
                    val nextRange = TextRange(index, index + nextCharCount)
                    if (classifier.classify(text, nextRange, context) != FontRole.LatinText) break
                    index += nextCharCount
                }
            }
        } else if (role == FontRole.CjkPunctuation && codePoint in coalesceSet) {
            while (index < text.length && index !in spanBoundaries) {
                val nextCodePoint = text.codePointAtCompat(index)
                if (nextCodePoint != codePoint) break
                index += nextCodePoint.charCount()
            }
        }

        // `GraphemeExtendStaysWithBaseCluster`: common code can classify
        // BMP Mn/Mc/Me marks through Char.category; BMP and supplementary
        // variation selectors are covered explicitly. Shaping one of those
        // extenders as an independent Unknown run loses its base context and
        // produces a legitimate zero advance which web capability validation
        // then mistakes for a broken visible glyph. Keep the source range
        // intact and send the base plus every covered extending mark through
        // one font decision and shaping call. Other supplementary combining
        // categories remain outside this deliberately narrow helper.
        while (index < text.length && index !in spanBoundaries) {
            val extender = text.codePointAtCompat(index)
            if (!extender.isCombiningMarkCodePoint() && !extender.isVariationSelectorCodePoint()) break
            index += extender.charCount()
        }

        val range = TextRange(start, index)
        ranges.add(
            ResolvedClusterRange(
                range = range,
                role = role,
                roleOverride = if (role == FontRole.Emoji && classifiedRole != FontRole.Emoji) {
                    RoleOverrideInfo(
                        range = range,
                        sourceText = text.substring(range.start, range.end),
                        originalRole = classifiedRole.name,
                        overriddenRole = role.name,
                        source = "EmojiPresentationSignalRolePromotion",
                        reason = "GraphemeContainsEmojiPresentationSignal",
                    )
                } else {
                    null
                },
            ),
        )
    }
    return ranges
}

private fun Int.isMandatoryBreakCodePointAt(text: String, index: Int): Boolean =
    isMandatoryBreakCodePoint(this) &&
        !(this == 0x000A && index > 0 && text[index - 1] == '\r')

private fun String.codePointAtCompat(index: Int): Int {
    val high = this[index].code
    if (high !in 0xD800..0xDBFF || index + 1 >= length) return high

    val low = this[index + 1].code
    if (low !in 0xDC00..0xDFFF) return high

    return 0x10000 + ((high - 0xD800) shl 10) + (low - 0xDC00)
}

private fun Int.charCount(): Int =
    if (this > 0xFFFF) 2 else 1

private fun Int.isVariationSelectorCodePoint(): Boolean =
    this in 0xFE00..0xFE0F || this in 0xE0100..0xE01EF

private fun Int.isCombiningMarkCodePoint(): Boolean =
    this in 0..0xFFFF && toChar().category in COMBINING_MARK_CATEGORIES

private fun Int.isAsciiPointMarkCodePoint(): Boolean =
    this in 0..0xFFFF && ClreqPunctuationPolicies.isAsciiPointMark(toChar())

/** Promotes text-default emoji and emoji presentation sequences to one role. */
private fun String.hasEmojiPresentationSignal(start: Int, end: Int): Boolean {
    var index = start
    while (index < end) {
        val codePoint = codePointAtCompat(index)
        if (
            codePoint == EMOJI_VARIATION_SELECTOR ||
            codePoint == COMBINING_ENCLOSING_KEYCAP ||
            UnicodeEmojiPresentationData.contains(codePoint)
        ) {
            return true
        }
        index += codePoint.charCount()
    }
    return false
}

private const val EMOJI_VARIATION_SELECTOR = 0xFE0F
private const val COMBINING_ENCLOSING_KEYCAP = 0x20E3

internal fun List<Cluster>.requireCoveredBy(fontDecisions: List<FontDecision>) {
    var clusterIndex = 0
    fontDecisions.forEach { decision ->
        while (clusterIndex < size && this[clusterIndex].range.end <= decision.range.start) {
            clusterIndex += 1
        }
        var cursor = decision.range.start
        while (clusterIndex < size && this[clusterIndex].range.start < decision.range.end) {
            val cluster = this[clusterIndex]
            require(cluster.range.isInside(decision.range)) {
                "TextShaper returned cluster ${cluster.range} crossing ${decision.range}"
            }
            require(cluster.range.start == cursor) {
                "TextShaper returned non-contiguous clusters for ${decision.range}; " +
                    "expected start=$cursor, actual=${cluster.range}"
            }
            cursor = cluster.range.end
            clusterIndex += 1
        }
        require(cursor == decision.range.end) {
            "TextShaper must return clusters covering ${decision.range}; coveredUntil=$cursor"
        }
    }
}

internal data class ResolvedClusterRange(
    val range: TextRange,
    val role: FontRole,
    val mandatoryBreak: Boolean = false,
    val zeroWidthSoftBreak: Boolean = false,
    val roleOverride: RoleOverrideInfo? = null,
)
