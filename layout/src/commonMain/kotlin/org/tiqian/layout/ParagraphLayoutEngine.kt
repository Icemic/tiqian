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

/** Monotonic inverse interval join when one cluster may contain several ordered items. */
private fun <T> List<Cluster>.firstContainedItem(
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
            range.start >= cluster.range.start && range.end <= cluster.range.end
        }
    }
}

interface ParagraphLayoutEngine {
    fun layout(input: LayoutInput): LayoutResult
}

class ExplainableStubParagraphLayoutEngine(
    private val fontRoleClassifier: FontRoleClassifier = CjkFontRoleClassifier(),
    internal val fallbackResolver: FallbackResolver = PreferCjkForAmbiguousPunctuationResolver(),
    private val clreqProfileResolver: ClreqProfileResolver = BuiltInClreqProfileResolver,
    internal val fontMetricsResolver: FontMetricsResolver = StubFontMetricsResolver(),
    internal val fontMetricsNormalizer: FontMetricsNormalizer = ScriptAwareFontMetricsNormalizer(),
    private val punctuationAtomBuilder: PunctuationAtomBuilder = PunctuationAtomBuilder(),
    private val punctuationSpacingCompressor: PunctuationSpacingCompressor = PunctuationSpacingCompressor(),
    private val quotePairAnalyzer: QuotePairAnalyzer = QuotePairAnalyzer(),
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
) : ParagraphLayoutEngine {
    override fun layout(input: LayoutInput): LayoutResult =
        layoutWithRejectedTechnicalTiers(input, emptyMap())

    internal fun layoutWithRejectedTechnicalTiers(
        input: LayoutInput,
        rejectedTechnicalTiersBySpan: Map<TextRange, Set<ProgressiveBreakTier>>,
    ): LayoutResult {
        val text = input.content.text
        val fontSize = input.textStyle.fontSize
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
        val inlineObjectByRange = input.inlineObjects.associateBy { it.range }
        // Rich-text per-span style (ADR 0030 B 档): a cluster covered by a span
        // SHAPES at that span's size + weight + slant, and is MEASURED at its
        // size; the paragraph base still owns the structural em decisions (grid /
        // 段首缩进) per the mixed-size 归属 rule. The boundary em decisions
        // (中西间距、标点 glue) stay at base for now — per-owner is the follow-up.
        // Each span's TextStyle is the FULLY-RESOLVED style (base + overrides),
        // so unset fields already equal base.
        val sizedSpans = input.content.spans.filter { it.range.start < it.range.end }
        fun styleAt(offset: Int) =
            sizedSpans.lastOrNull { offset >= it.range.start && offset < it.range.end }?.style ?: input.textStyle
        fun fontSizeAt(offset: Int): Float = styleAt(offset).fontSize
        // BilingualEmphasisWesternItalic (ADR 0030/着重号惯例): 着重号 marks Han with
        // dots, but Western emphasis is ITALIC, not dots. A Latin run inside an
        // Emphasis span shapes italic (and gets no dot — see computeDecorationDecisions).
        val emphasisRanges = input.decorations.filter { it.kind == DecorationKind.Emphasis }.map { it.range }
        fun emphasisItalicAt(offset: Int): Boolean =
            emphasisRanges.any { offset >= it.start && offset < it.end }
        // 行间注 (ruby, ADR 0032):注文 first uses the existing inter-line area.
        // Only a measured deficit expands line boxes according to rubyLineHeightMode;
        // advance is still handled by 避让 before breaking.
        val rubyFontSize = fontSize * RUBY_FONT_EM
        val rubyStackGap = fontSize * RUBY_STACK_GAP_EM
        // `RubyLegibilityWeightBoost`: small above-base pinyin stays one weight
        // step heavier than the base; Android gallery shows this is enough.
        val rubyFontWeight = (input.textStyle.fontWeight + RUBY_FONT_WEIGHT_BOOST).coerceIn(1, 900)
        // `BopomofoLegibilityWeightBoost`: right-side ㄅㄆㄇ is smaller and thinner
        // on Android CJK fonts, so it gets a stronger boost than pinyin ruby.
        // It follows the annotated base glyph's effective weight, not only the
        // paragraph default: a bold base carries bold 注音 too.
        fun bopomofoFontWeightAt(offset: Int): Int =
            (styleAt(offset).fontWeight + BOPOMOFO_FONT_WEIGHT_BOOST).coerceIn(1, 900)
        // 拼音 (above-base) ruby only; 注音 (RubyKind.Bopomofo, right-side) is parsed
        // separately below because its geometry/advance/weight follow ADR 0033.
        val pinyinSpans = input.rubySpans.filter { it.kind == RubyKind.Pinyin }
        // SourceRangeBoundaryClusterSplit: span / annotation edges force cluster
        // splits so no cluster straddles a source range whose geometry is later
        // queried. Without this, a link ending before a trailing period in
        // `template.` would be sliced by UTF-16 ratio rather than by real cluster
        // advance, visibly shortening its underline.
        val spanBoundaries: Set<Int> = buildSet {
            fun addBoundary(offset: Int) {
                if (offset > 0 && offset < text.length) add(offset)
            }
            fun addRange(range: TextRange) {
                addBoundary(range.start)
                addBoundary(range.end)
            }
            sizedSpans.forEach { addRange(it.range) }
            input.decorations.forEach { addRange(it.range) }
            input.rubySpans.forEach { addRange(it.baseRange) }
            input.inlineBoxes.forEach { addRange(it.range) }
            input.inlineObjects.forEach { addRange(it.range) }
            input.content.lineBreakSpans.forEach { addRange(it.range) }
            input.content.sourceBoundaries.forEach(::addBoundary)
        }
        val clreqProfile = clreqProfileResolver.resolve(input.profileId)
        val context = FontRoleContext(
            locale = input.textStyle.locale,
            regionHint = clreqProfile.region.name,
        )
        val punctuationGlyphSubstitutor = ClreqPunctuationGlyphSubstitutor(
            policy = clreqProfile.punctuationGlyphPolicy,
        )

        // LineLengthGridQuantization (grid-first, ADR 0028): floor the
        // container to an integer number of 字 (N×fontSize) so the body lands
        // on the grid; the sub-字 leftover places the whole body within the
        // container by bodyAlignment. Bypassable for known-exact widths.
        // Computed up front because LatinForcedHyphenBreak (below) needs the
        // measure to decide which Latin pieces can never fit on a line.
        val grid = input.paragraphStyle.lineLengthGrid
        val containerWidth = input.constraints.maxWidth
        val gridCells = floor(containerWidth / fontSize).toInt().coerceAtLeast(1)
        val measure = if (grid.enabled) {
            (gridCells * fontSize).coerceAtMost(containerWidth)
        } else {
            containerWidth
        }
        val gridSlack = containerWidth - measure
        val gridBodyAlignment = grid.bodyAlignment ?: input.paragraphStyle.lastLineAlignment
        val gridBodyOffset = if (!grid.enabled) {
            0f
        } else {
            when (gridBodyAlignment) {
                LastLineAlignment.Start -> 0f
                LastLineAlignment.Center -> gridSlack / 2f
                LastLineAlignment.End -> gridSlack
            }
        }
        val lineLengthGridDecision = LineLengthGridDecisionInfo(
            enabled = grid.enabled,
            containerWidth = containerWidth,
            fontSize = fontSize,
            cells = if (grid.enabled) gridCells else (measure / fontSize).toInt(),
            measure = measure,
            slack = gridSlack,
            bodyAlignment = gridBodyAlignment.name,
            bodyOffset = gridBodyOffset,
            reason = if (grid.enabled) "LineLengthGridQuantization" else "GridBypassed",
        )
        val measureEm = measure / fontSize

        val quotePairs = quotePairAnalyzer.analyze(text)
        val quoteRoleDecisions = quotePairAnalyzer.classifyQuoteRoles(text, quotePairs, context)
        val quoteRoleOverrides = quoteRoleDecisions.associate { it.index to it.role }
        val roleOverrideInfos = quoteRoleDecisions.toRoleOverrideInfos(
            text = text,
            baseClassifier = fontRoleClassifier,
            context = context,
        )
        val effectiveClassifier: FontRoleClassifier = if (quoteRoleOverrides.isNotEmpty()) {
            QuotePairAwareFontRoleClassifier(fontRoleClassifier, quoteRoleOverrides)
        } else {
            fontRoleClassifier
        }

        val clusterRanges = clusterRoleRanges(
            text,
            effectiveClassifier,
            context,
            clreqProfile,
            spanBoundaries,
            input.inlineObjects.associateBy { it.range.start },
        )
        val shapeableRanges = clusterRanges.filterNot {
            it.mandatoryBreak || it.zeroWidthSoftBreak || inlineObjectByRange.containsKey(it.range)
        }
        val fontDecisions = shapeableRanges.map { resolvedRange ->
            fallbackResolver.resolve(
                text = text,
                range = resolvedRange.range,
                request = FontRequest(
                    preferredFamilies = input.textStyle.fontFamilies,
                    locale = input.textStyle.locale,
                    role = resolvedRange.role,
                ),
            )
        }
        val fontDecisionByRange = shapeableRanges.zip(fontDecisions).associate { (resolved, decision) ->
            resolved.range to decision
        }

        val shapingStage = shapeParagraph(
            input = input,
            text = text,
            fontSize = fontSize,
            measure = measure,
            clusterRanges = clusterRanges,
            fontDecisionByRange = fontDecisionByRange,
            inlineObjectByRange = inlineObjectByRange,
            punctuationGlyphSubstitutor = punctuationGlyphSubstitutor,
            styleAt = ::styleAt,
            emphasisItalicAt = ::emphasisItalicAt,
            rejectedTechnicalTiersBySpan = rejectedTechnicalTiersBySpan,
        )
        val shapingResults = shapingStage.shapingResults
        val hyphenOffsets = shapingStage.hyphenOffsets
        val hyphenAdvance = shapingStage.hyphenAdvance
        val hyphenGlyphs = shapingStage.hyphenGlyphs
        val substitutionRollbacks = shapingStage.substitutionRollbacks
        val breakOpportunityDecisions = shapingStage.breakOpportunityDecisions
        val emergencyTrackingEligibilityDecisions = shapingStage.emergencyTrackingEligibilityDecisions
        val progressiveBreakOffsets = shapingStage.progressiveBreakOffsets
        val rawNaturalClusters = shapingResults.flatMap { it.clusters }
        val shapedGlyphsByClusterRange = shapingResults
            .flatMap { it.glyphRuns }
            .flatMap { it.glyphs }
            .groupBy { it.clusterRange }
        val openTypeFeaturesByClusterRange = buildMap<TextRange, List<String>> {
            shapingResults.flatMap { it.glyphRuns }.forEach { run ->
                run.glyphs.map { it.clusterRange }.distinct().forEach { range ->
                    val previous = put(range, run.openTypeFeatures)
                    require(previous == null || previous == run.openTypeFeatures) {
                        "Conflicting OpenType features for shaped cluster $range"
                    }
                }
            }
        }
        val shapingDecisions = shapingResults.flatMap { it.decisions }
        rawNaturalClusters.requireCoveredBy(fontDecisions)

        val inlineObjectRanges = input.inlineObjects.map { it.range }
        // `InlineBoxOuterAutoSpace`: every independent inline box presents one Narrow boundary
        // contract to adjacent CJK text. The decision is owned here, before line breaking; source
        // punctuation inside the box and frontend roles do not create separate special cases.
        val narrowInlineBoxRanges = input.inlineBoxes
            .filter { it.outerSpacing == InlineBoxOuterSpacing.Narrow }
            .mapTo(mutableSetOf()) { it.range }
        val narrowInlineBoxLeadingClusters = rawNaturalClusters.indices
            .filterTo(mutableSetOf()) { index ->
                narrowInlineBoxRanges.any { it.start == rawNaturalClusters[index].range.start }
            }
        val narrowInlineBoxTrailingClusters = rawNaturalClusters.indices
            .filterTo(mutableSetOf()) { index ->
                narrowInlineBoxRanges.any { it.end == rawNaturalClusters[index].range.end }
            }
        val eastAsianSpacingEdges = rawNaturalClusters.mapIndexed { index, cluster ->
            if (
                inlineObjectRanges.any { cluster.range.isInside(it) } ||
                (rawNaturalClusters.isAttachedAsciiPointMarkAt(index) &&
                    index !in narrowInlineBoxLeadingClusters)
            ) {
                EastAsianSpacingEdges(
                    leading = EastAsianSpacingValue.Other,
                    trailing = EastAsianSpacingValue.Other,
                    containsWide = false,
                )
            } else {
                val resolved = UnicodeEastAsianSpacing.resolvedEdges(
                    text = cluster.text,
                    locale = input.textStyle.locale,
                )
                resolved.copy(
                    leading = if (index in narrowInlineBoxLeadingClusters) {
                        EastAsianSpacingValue.Narrow
                    } else {
                        resolved.leading
                    },
                    trailing = if (index in narrowInlineBoxTrailingClusters) {
                        EastAsianSpacingValue.Narrow
                    } else {
                        resolved.trailing
                    },
                )
            }
        }

        val autoSpaceResult = rawNaturalClusters.applyAutoSpacePolicy(
            eastAsianSpacingEdges = eastAsianSpacingEdges,
            inlineAttachments = rawNaturalClusters.map { styleAt(it.range.start).inlineAttachment },
            policy = clreqProfile.autoSpace,
            fontSize = fontSize,
            narrowInlineBoxLeadingClusters = narrowInlineBoxLeadingClusters,
            narrowInlineBoxTrailingClusters = narrowInlineBoxTrailingClusters,
        )
        val inlineBoxResult = autoSpaceResult.clusters.applyInlineBoxSpans(input.inlineBoxes)
        val naturalClusters = inlineBoxResult.clusters
        val inlineObjectByClusterIndex = buildMap {
            for (inlineObject in input.inlineObjects) {
                val clusterIndex = naturalClusters.indexOfFirst {
                    it.range == inlineObject.range && it.isInlineObjectCluster()
                }
                require(clusterIndex >= 0) {
                    "Inline object ${inlineObject.range} did not produce a layout cluster"
                }
                put(clusterIndex, inlineObject)
            }
        }
        val inlineObjectBoundaryAfterClusters = mutableMapOf<Int, InlineObjectBoundaryAdjustment>()
        fun registerInlineObjectBoundary(
            leftClusterIndex: Int,
            boundary: InlineObjectBoundaryAdjustment,
        ) {
            val previous = inlineObjectBoundaryAfterClusters[leftClusterIndex]
            if (previous == null) {
                inlineObjectBoundaryAfterClusters[leftClusterIndex] = boundary
                return
            }
            val preferredKinds = listOfNotNull(previous.preferredStretch, boundary.preferredStretch)
                .map { it.kind }
                .distinct()
            require(preferredKinds.size <= 1) {
                "Conflicting inline-object stretch classes at cluster boundary $leftClusterIndex"
            }
            val preferred = listOfNotNull(previous.preferredStretch, boundary.preferredStretch)
                .maxByOrNull { it.capacity }
            inlineObjectBoundaryAfterClusters[leftClusterIndex] = InlineObjectBoundaryAdjustment(
                participatesInUniformStretch =
                    previous.participatesInUniformStretch || boundary.participatesInUniformStretch,
                preferredStretch = preferred,
                shrinkCapacity = maxOf(previous.shrinkCapacity, boundary.shrinkCapacity),
                lineEndDiscardableAdvance = maxOf(
                    previous.lineEndDiscardableAdvance,
                    boundary.lineEndDiscardableAdvance,
                ),
                preventsLineBreak = previous.preventsLineBreak || boundary.preventsLineBreak,
            )
        }
        inlineObjectByClusterIndex.forEach { (clusterIndex, inlineObject) ->
            if (clusterIndex > 0 && inlineObject.leadingBoundary != InlineObjectBoundaryAdjustment.Fixed) {
                registerInlineObjectBoundary(clusterIndex - 1, inlineObject.leadingBoundary)
            }
            if (
                clusterIndex < naturalClusters.lastIndex &&
                inlineObject.trailingBoundary != InlineObjectBoundaryAdjustment.Fixed
            ) {
                registerInlineObjectBoundary(clusterIndex, inlineObject.trailingBoundary)
            }
        }
        val uniformInlineObjectBoundaryAfterClusters = inlineObjectBoundaryAfterClusters
            .filterValues { it.participatesInUniformStretch }
            .keys
        val preferredInlineObjectBoundaryAfterClusters: Map<Int, InlineObjectPreferredStretch> =
            inlineObjectBoundaryAfterClusters.mapNotNull { (clusterIndex, boundary) ->
                boundary.preferredStretch?.let { clusterIndex to it }
            }.toMap()
        val inlineObjectBoundaryUnbreakableRanges = inlineObjectBoundaryAfterClusters
            .filterValues { it.preventsLineBreak }
            .keys
            .map { leftClusterIndex -> leftClusterIndex..(leftClusterIndex + 1) }
        val autoSpaceDecisions = autoSpaceResult.decisions
        val clusterRoles = naturalClusters
            .containingItems(fontDecisions, FontDecision::range)
            .map { decision -> decision?.role ?: FontRole.Unknown }
        val resolvedKinsoku = clreqProfile.kinsokuMode.resolve(measureEm)
        val kinsokuRule = ClreqKinsokuRule(resolvedKinsoku.level)
        val inlineObjectAttachedMarks = naturalClusters.inlineObjectAttachedMarks(
            clusterRoles = clusterRoles,
            level = resolvedKinsoku.level,
            kinsokuRule = kinsokuRule,
        )
        val inlineObjectSeparatorSpaceTrims = buildMap {
            inlineObjectAttachedMarks.forEach { attachment ->
                attachment.separatorClusterIndices.forEach { clusterIndex ->
                    put(clusterIndex, naturalClusters[clusterIndex].advance)
                }
            }
        }
        val inlineObjectAttachmentNoStretchBoundaries = inlineObjectAttachedMarks
            .flatMapTo(mutableSetOf()) { attachment ->
                attachment.objectClusterIndex until attachment.markClusterIndex
            }
        val inlineObjectPunctuationAttachmentDecisions = inlineObjectAttachedMarks
            .filter { it.separatorClusterIndices.isNotEmpty() }
            .map { attachment ->
                val separatorFirst = naturalClusters[attachment.separatorClusterIndices.first()]
                val separatorLast = naturalClusters[attachment.separatorClusterIndices.last()]
                val mark = naturalClusters[attachment.markClusterIndex]
                InlineObjectPunctuationAttachmentDecisionInfo(
                    objectRange = naturalClusters[attachment.objectClusterIndex].range,
                    separatorRange = TextRange(separatorFirst.range.start, separatorLast.range.end),
                    punctuationRange = mark.range,
                    punctuationText = mark.text,
                    protectedRange = TextRange(
                        naturalClusters[attachment.objectClusterIndex].range.start,
                        mark.range.end,
                    ),
                    collapsedAdvance = attachment.separatorClusterIndices
                        .sumOf { naturalClusters[it].advance.toDouble() }
                        .toFloat(),
                )
            }
        val mandatoryBreakClusters = naturalClusters.indices
            .filterTo(mutableSetOf()) { idx -> naturalClusters[idx].isMandatoryBreakCluster() }
        val zeroWidthBreakClusters = naturalClusters.indices
            .filterTo(mutableSetOf()) { idx -> naturalClusters[idx].isZeroWidthSoftBreakCluster() }
        val mandatoryBreakDecisions = naturalClusters.mapIndexedNotNull { idx, cluster ->
            if (!cluster.isMandatoryBreakCluster()) return@mapIndexedNotNull null
            MandatoryBreakDecisionInfo(
                range = cluster.range,
                sourceText = cluster.text,
                breakAfterClusterIndex = idx,
                reason = "MandatoryBreakNoShape",
            )
        }
        val zeroWidthBreakDecisions = zeroWidthBreakClusters.sorted().map { clusterIndex ->
            val cluster = naturalClusters[clusterIndex]
            ZeroWidthBreakDecisionInfo(
                range = cluster.range,
                sourceText = cluster.text,
                clusterIndex = clusterIndex,
            )
        }

        // Punctuation atoms are a CJK-text concern: a LatinText cluster's ASCII
        // punctuation ('-', '/', ',') is part of the Latin glyph run (an English
        // hyphen, not a CJK 连接号), so it must NOT get a 短横线/标点 atom that
        // would collapse the cluster to half-width.
        val punctuationAtoms = naturalClusters.mapIndexedNotNull { idx, cluster ->
            if (clusterRoles[idx] == FontRole.LatinText) null else cluster
        }.flatMap { cluster ->
            cluster.punctuationAtoms(
                em = fontSize,
                builder = punctuationAtomBuilder,
                shapedGlyphs = shapedGlyphsByClusterRange[cluster.range].orEmpty(),
                gluePlacement = clreqProfile.gluePlacement,
                widthPolicy = clreqProfile.punctuationWidth,
            )
        }
        val adjacentPunctuationSpacingPlan =
            punctuationSpacingCompressor.compress(punctuationAtoms, em = fontSize)
        val cjkClosingBeforeAsciiPointMarkPlan =
            punctuationSpacingCompressor.compressCjkClosingBeforeAsciiPointMark(
                atoms = punctuationAtoms,
                text = text,
                em = fontSize,
            )
        val spacingPlan = PunctuationSpacingCompressionResult(
            adjustments = adjacentPunctuationSpacingPlan.adjustments +
                cjkClosingBeforeAsciiPointMarkPlan.adjustments,
        )
        // Shape each注文 once in ITS OWN font for horizontal width. Vertical fit
        // deliberately uses that Latin face's declared ascent/descent, not glyph
        // ink: changing hé to p or g must not change the line-height decision.
        val rubyFontGeometryBySpan = pinyinSpans.associateWith { ruby ->
            val metricText = ruby.text.ifEmpty { "x" }
            val range = TextRange(0, metricText.length)
            val preferredFamilies = ruby.fontFamilies
            val rubyLocale = ruby.locale ?: input.textStyle.locale
            val decision = fallbackResolver.resolve(
                text = metricText,
                range = range,
                request = FontRequest(
                    preferredFamilies = preferredFamilies,
                    locale = rubyLocale,
                    role = FontRole.LatinText,
                ),
            )
            val raw = fontMetricsResolver.resolve(
                FontMetricsRequest(
                    fontKey = decision.candidate.key,
                    fontSize = rubyFontSize,
                    role = FontRole.LatinText,
                    locale = rubyLocale,
                    fontWeight = rubyFontWeight,
                    italic = input.textStyle.italic,
                    faceSelectionText = metricText,
                    fontFamilies = preferredFamilies,
                ),
            )
            val declaredAscent = raw.typoAscent ?: raw.ascent
            val declaredDescent = raw.typoDescent ?: raw.descent
            val shaped = if (ruby.text.isEmpty()) {
                null
            } else {
                textShaper.shape(
                    ShapingInput(
                        text = ruby.text,
                        range = TextRange(0, ruby.text.length),
                        style = input.textStyle.copy(
                            fontSize = rubyFontSize,
                            fontFamilies = ruby.fontFamilies,
                            fontWeight = rubyFontWeight,
                            locale = rubyLocale,
                        ),
                        fontDecision = decision,
                        displayText = ruby.text,
                    ),
                )
            }
            RubyFontGeometry(
                width = shaped?.clusters.orEmpty().sumOf { it.advance.toDouble() }.toFloat(),
                ascent = if (ruby.text.isEmpty()) 0f else declaredAscent,
                descent = if (ruby.text.isEmpty()) 0f else declaredDescent,
                requiredExtent = if (ruby.text.isEmpty()) {
                    0f
                } else {
                    declaredAscent + declaredDescent + rubyStackGap
                },
                glyphs = shaped?.glyphRuns.orEmpty().flatMap { it.glyphs },
            )
        }
        // 避让: left→right, push a 注文 (and everything after) right by the MINIMAL
        // amount that restores the word-space gap to the previous 注文; record it as
        // trailing 字距 on the cluster just before the span. Narrow 注文 (gap already
        // ok) get nothing → they overhang freely (CLREQ「只要不侵犯最小间距，可允许
        // 注文伸展到相邻基字上方」). The first span is never pushed.
        fun computeRubySpread(natural: List<Cluster>, rubySize: Float): Map<Int, Float> {
            if (pinyinSpans.isEmpty()) return emptyMap()
            val wordSpace = rubySize * RUBY_MIN_GAP_EM_OF_RUBY
            val leftX = FloatArray(natural.size)
            var acc = 0f
            for (i in natural.indices) { leftX[i] = acc; acc += natural[i].advance }
            val measures = pinyinSpans.mapNotNull { ruby ->
                val idxRange = natural.clusterIndexRangeFor(ruby.baseRange) ?: return@mapNotNull null
                val center = (leftX[idxRange.first] + leftX[idxRange.last] + natural[idxRange.last].advance) / 2f
                Triple(idxRange.first, center, rubyFontGeometryBySpan.getValue(ruby).width)
            }.sortedBy { it.first }
            val spread = HashMap<Int, Float>()
            var shift = 0f
            var prevRight = Float.NEGATIVE_INFINITY
            for ((firstCluster, centerNatural, rw) in measures) {
                var center = centerNatural + shift
                val needed = prevRight + wordSpace - (center - rw / 2f)
                if (needed > 0f && firstCluster > 0) {
                    val key = firstCluster - 1
                    spread[key] = (spread[key] ?: 0f) + needed
                    shift += needed
                    center += needed
                }
                prevRight = center + rw / 2f
            }
            return spread
        }
        // 行间注 避让 (ADR 0032): adjacent 注文 keep ≥ one 注文 word-space — add the
        // MINIMAL trailing 字距 where they'd crowd (narrower 注文 just overhang).
        // STRUCTURAL spread baked into baseGeometry so the breaker + final geometry
        // both see the widened advances.
        val rubySpread = computeRubySpread(naturalClusters, rubyFontSize)
        // 注音 (ADR 0033): reserve the 0.5em 注音 column ONLY on each annotated base
        // char's right side (its last cluster). The uniform every-char reservation is
        // 繁体中文 纵横对齐 — not built yet — so it stays OUT: the 注音 sits in its own
        // base's trailing space and adjacent unannotated text keeps normal spacing.
        val bopomofoSpans = input.rubySpans.filter { it.kind == RubyKind.Bopomofo }
        val rubyAndBopomofoSpread = if (bopomofoSpans.isEmpty()) {
            rubySpread
        } else {
            HashMap(rubySpread).apply {
                bopomofoSpans.forEach { z ->
                    val r = naturalClusters.clusterIndexRangeFor(z.baseRange) ?: return@forEach
                    mergeValue(r.last, 0.5f * fontSize) { a, b -> a + b }
                }
            }
        }
        val naturalInlineAttachments = naturalClusters.map { styleAt(it.range.start).inlineAttachment }
        val punctuationBaseGeometry = PunctuationGeometryLedger.from(
            naturalClusters = naturalClusters,
            punctuationAtoms = punctuationAtoms,
            spacingPlan = spacingPlan,
        ).withInlineBoxAdvances(inlineBoxResult.advanceByCluster)
            .withRubySpread(rubyAndBopomofoSpread)
            .withRawEdgeTrims(inlineObjectSeparatorSpaceTrims)
        val attachedPunctuationBoundary =
            punctuationBaseGeometry.resolveAttachedInlinePunctuationBoundaries(
                inlineAttachments = naturalInlineAttachments,
                punctuationAtoms = punctuationAtoms,
                em = fontSize,
            )
        val baseGeometry = attachedPunctuationBoundary.geometry
        val attachedPunctuationTrailingGlueByCluster =
            attachedPunctuationBoundary.trailingGlueByCluster
        val clusters = baseGeometry.resolveClusters()
        // CLREQ 挤压处理优先顺序 (ADR 0020): tiered shrink resources for
        // PushIn. Punctuation classes map to tiers; style knobs gate the
        // inline-stop and sino-western tiers.
        val adjustmentStyle = clreqProfile.adjustment
        val glueCaps = baseGeometry.glueCapacities()
        val gapClusterRanges = autoSpaceDecisions
            .filter { it.side == "gap" }
            .map { it.clusterRange }
            .toSet()
        // Keyed by the CLUSTER's range. An atom's own range is per DISPLAY char —
        // for a rolled-back `——` (two display chars, ADR 0003) that is two per-char
        // ranges, and a lookup by the coalesced cluster range would silently miss
        // (device bug: the dash dropped out of noStretch/centering after rollback).
        val atomClassByRange: Map<TextRange, PunctuationClass> = naturalClusters
            .zip(naturalClusters.firstContainedItem(punctuationAtoms, PunctuationAtom::range))
            .mapNotNull { (cluster, atom) -> atom?.punctuationClass?.let { cluster.range to it } }
            .toMap()
        val shrinkOpportunities = buildList {
            naturalClusters.forEachIndexed { idx, cluster ->
                val caps = glueCaps[idx]
                if (caps != null) {
                    val cls = atomClassByRange[cluster.range]
                    fun addGeometryAwareOpportunity(
                        tier: Int,
                        lineEndOnly: Boolean = false,
                    ) {
                        if (caps.paired) {
                            val pairedCapacity = 2f * minOf(caps.leading, caps.trailing)
                            if (pairedCapacity > 0f) {
                                add(
                                    ShrinkOpportunity(
                                        clusterIndex = idx,
                                        tier = tier,
                                        capacity = pairedCapacity,
                                        channel = ShrinkChannel.LeadingAndTrailingGlue,
                                        lineEndOnly = lineEndOnly,
                                    ),
                                )
                            }
                        } else {
                            if (caps.leading > 0f) {
                                add(
                                    ShrinkOpportunity(
                                        clusterIndex = idx,
                                        tier = tier,
                                        capacity = caps.leading,
                                        channel = ShrinkChannel.LeadingGlue,
                                        lineEndOnly = lineEndOnly,
                                    ),
                                )
                            }
                            if (caps.trailing > 0f) {
                                add(
                                    ShrinkOpportunity(
                                        clusterIndex = idx,
                                        tier = tier,
                                        capacity = caps.trailing,
                                        channel = ShrinkChannel.TrailingGlue,
                                        lineEndOnly = lineEndOnly,
                                    ),
                                )
                            }
                        }
                    }
                    when (cls) {
                        PunctuationClass.Interpunct,
                        PunctuationClass.MiddleDot,
                        -> {
                            // CLREQ ③ 间隔号：字体几何通常给出居中框，因此
                            // 双侧同时、同等量；异常的单侧字体仍忠实消费其实际空白。
                            addGeometryAwareOpportunity(tier = 3)
                        }

                        // CLREQ ④ 夹注符号：开始夹注的前侧、结束夹注的
                        // 后侧，最小挤到半个汉字字宽（= glue 全部可压）。
                        // Quote 经 pair 分析后开/闭各持一侧 glue，两个分支
                        // 自然各取其有的一侧。
                        PunctuationClass.Opening,
                        PunctuationClass.Closing,
                        PunctuationClass.Quote,
                        -> {
                            addGeometryAwareOpportunity(tier = 4)
                        }

                        PunctuationClass.PauseOrStop -> {
                            // CLREQ ⑤ 行内逗、顿、分号（冒号原文未尽列，
                            // 按同档处理）；⑦ 行内句问叹排最后，且部分
                            // 风格禁止（knob）。
                            val isStop = cluster.displayText.firstOrNull() in INLINE_STOPS
                            val tier = if (isStop) 7 else 5
                            // Knob off: 行内句问叹 keep full width — their glue
                            // is only reachable via the tier-1 line-end
                            // promotion (行末削半 is a separate rule).
                            val lineEndOnly = isStop && !adjustmentStyle.allowInlineStopCompression
                            addGeometryAwareOpportunity(tier = tier, lineEndOnly = lineEndOnly)
                        }

                        // CLREQ 未列其余带 glue 的标点：按 ⑤ 档兜底。
                        else -> addGeometryAwareOpportunity(tier = 5)
                    }
                } else if (cluster.isSpaceRun() && idx !in inlineObjectSeparatorSpaceTrims) {
                    if (cluster.range in gapClusterRanges) {
                        // CLREQ ⑥ 中西间距：最小挤为八分之一汉字宽（不是 0）；
                        // 部分风格禁止（knob）。
                        val capacity = cluster.advance - SINO_WESTERN_GAP_MIN_EM * fontSize
                        if (adjustmentStyle.allowSinoWesternGapAdjustment && capacity > 0f) {
                            add(ShrinkOpportunity(idx, tier = 6, capacity = capacity, channel = ShrinkChannel.RawAdvance))
                        }
                    } else {
                        // CLREQ ② 西文词距：最小挤到 1/4em。Technical inline only changes
                        // break-tier selection; its source spaces use this same prose floor.
                        val capacity = cluster.advance - WORD_SPACE_MIN_EM * fontSize
                        if (capacity > 0f) {
                            add(ShrinkOpportunity(idx, tier = 2, capacity = capacity, channel = ShrinkChannel.RawAdvance))
                        }
                    }
                }
            }
            // `InlineObjectBoundaryCompression`: an object provider may expose
            // measured blank at an edge as a last-resort compression resource.
            // It follows every CLREQ text tier and never scales object glyphs.
            inlineObjectByClusterIndex.forEach { (idx, inlineObject) ->
                val trailing = inlineObject.trailingBoundary.shrinkCapacity
                if (trailing > 0f) {
                    add(ShrinkOpportunity(idx, tier = 8, capacity = trailing, channel = ShrinkChannel.RawAdvance))
                }
            }
        }

        val prep = ParagraphLayoutPrep(
            input = input,
            rejectedTechnicalTiersBySpan = rejectedTechnicalTiersBySpan,
            text = text,
            fontSize = fontSize,
            styleAt = ::styleAt,
            fontSizeAt = ::fontSizeAt,
            bopomofoFontWeightAt = ::bopomofoFontWeightAt,
            rubyFontSize = rubyFontSize,
            rubyStackGap = rubyStackGap,
            rubyFontWeight = rubyFontWeight,
            pinyinSpans = pinyinSpans,
            clreqProfile = clreqProfile,
            punctuationGlyphSubstitutor = punctuationGlyphSubstitutor,
            measure = measure,
            measureEm = measureEm,
            gridBodyOffset = gridBodyOffset,
            lineLengthGridDecision = lineLengthGridDecision,
            quotePairs = quotePairs,
            roleOverrideInfos = roleOverrideInfos,
            fontDecisions = fontDecisions,
            hyphenOffsets = hyphenOffsets,
            hyphenAdvance = hyphenAdvance,
            hyphenGlyphs = hyphenGlyphs,
            substitutionRollbacks = substitutionRollbacks,
            breakOpportunityDecisions = breakOpportunityDecisions,
            emergencyTrackingEligibilityDecisions = emergencyTrackingEligibilityDecisions,
            progressiveBreakOffsets = progressiveBreakOffsets,
            shapedGlyphsByClusterRange = shapedGlyphsByClusterRange,
            openTypeFeaturesByClusterRange = openTypeFeaturesByClusterRange,
            shapingDecisions = shapingDecisions,
            eastAsianSpacingEdges = eastAsianSpacingEdges,
            autoSpaceDecisions = autoSpaceDecisions,
            inlineBoxResult = inlineBoxResult,
            naturalClusters = naturalClusters,
            inlineObjectByClusterIndex = inlineObjectByClusterIndex,
            uniformInlineObjectBoundaryAfterClusters = uniformInlineObjectBoundaryAfterClusters,
            preferredInlineObjectBoundaryAfterClusters = preferredInlineObjectBoundaryAfterClusters,
            inlineObjectBoundaryUnbreakableRanges = inlineObjectBoundaryUnbreakableRanges,
            clusterRoles = clusterRoles,
            resolvedKinsoku = resolvedKinsoku,
            kinsokuRule = kinsokuRule,
            inlineObjectAttachedMarks = inlineObjectAttachedMarks,
            inlineObjectSeparatorSpaceTrims = inlineObjectSeparatorSpaceTrims,
            inlineObjectAttachmentNoStretchBoundaries = inlineObjectAttachmentNoStretchBoundaries,
            inlineObjectPunctuationAttachmentDecisions = inlineObjectPunctuationAttachmentDecisions,
            mandatoryBreakClusters = mandatoryBreakClusters,
            zeroWidthBreakClusters = zeroWidthBreakClusters,
            mandatoryBreakDecisions = mandatoryBreakDecisions,
            zeroWidthBreakDecisions = zeroWidthBreakDecisions,
            punctuationAtoms = punctuationAtoms,
            spacingPlan = spacingPlan,
            rubyFontGeometryBySpan = rubyFontGeometryBySpan,
            rubyAndBopomofoSpread = rubyAndBopomofoSpread,
            naturalInlineAttachments = naturalInlineAttachments,
            attachedPunctuationBoundary = attachedPunctuationBoundary,
            baseGeometry = baseGeometry,
            attachedPunctuationTrailingGlueByCluster = attachedPunctuationTrailingGlueByCluster,
            clusters = clusters,
            adjustmentStyle = adjustmentStyle,
            atomClassByRange = atomClassByRange,
            shrinkOpportunities = shrinkOpportunities,
        )
        return finishParagraphLayout(prep, planParagraphLines(prep))
    }

    private fun TextRange.isInside(other: TextRange): Boolean =
        start >= other.start && end <= other.end
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
