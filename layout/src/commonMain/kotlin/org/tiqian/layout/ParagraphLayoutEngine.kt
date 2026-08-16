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
private fun <T> List<Cluster>.containingItems(
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
    private val fontMetricsResolver: FontMetricsResolver = StubFontMetricsResolver(),
    private val fontMetricsNormalizer: FontMetricsNormalizer = ScriptAwareFontMetricsNormalizer(),
    private val punctuationAtomBuilder: PunctuationAtomBuilder = PunctuationAtomBuilder(),
    private val punctuationSpacingCompressor: PunctuationSpacingCompressor = PunctuationSpacingCompressor(),
    private val quotePairAnalyzer: QuotePairAnalyzer = QuotePairAnalyzer(),
    internal val lineBreaker: LineBreaker = GreedyLineBreaker(),
    private val justifier: Justifier = Justifier(),
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

    private fun layoutWithRejectedTechnicalTiers(
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

        // `FontMetricFaceSelectionTextJoin`: font decisions and shaped clusters
        // are both source ordered. Walk them once instead of filtering the whole
        // paragraph for every decision (which made layout quadratic on long text).
        var metricClusterIndex = 0
        val metricDecisions = fontDecisions.map { decision ->
            while (
                metricClusterIndex < naturalClusters.size &&
                naturalClusters[metricClusterIndex].range.end <= decision.range.start
            ) {
                metricClusterIndex += 1
            }
            val displayedFaceSelectionText = buildString {
                while (
                    metricClusterIndex < naturalClusters.size &&
                    naturalClusters[metricClusterIndex].range.start < decision.range.end
                ) {
                    val cluster = naturalClusters[metricClusterIndex]
                    require(cluster.range.isInside(decision.range)) {
                        "Shaped cluster ${cluster.range} crosses font decision ${decision.range}"
                    }
                    append(cluster.displayText)
                    metricClusterIndex += 1
                }
            }.ifEmpty { text.substring(decision.range.start, decision.range.end) }
            val request = FontMetricsRequest(
                fontKey = decision.candidate.key,
                fontSize = fontSizeAt(decision.range.start),
                role = decision.role,
                locale = input.textStyle.locale,
                fontWeight = styleAt(decision.range.start).fontWeight,
                italic = styleAt(decision.range.start).italic,
                faceSelectionText = displayedFaceSelectionText,
                fontFamilies = styleAt(decision.range.start).fontFamilies,
            )
            val rawMetrics = fontMetricsResolver.resolve(request)
            val layoutMetrics = fontMetricsNormalizer.normalize(
                FontMetricsNormalizationInput(
                    request = request,
                    rawMetrics = rawMetrics,
                ),
            )
            ClusterMetricDecision(
                range = decision.range,
                sourceText = text.substring(decision.range.start, decision.range.end),
                request = request,
                rawMetrics = rawMetrics,
                layoutMetrics = layoutMetrics,
            )
        }

        // 行间注 vertical placement (ADR 0032): use the ideographic base face,
        // then seat the ruby font's declared Latin box above it. Glyph ink does
        // not participate, so reading content cannot change the baseline grid.
        val baseMetricDecisions = metricDecisions
            .filter { it.layoutMetrics.metricBox == MetricBox.IdeographicEmBox }
            .ifEmpty { metricDecisions }
        val baseAscent = baseMetricDecisions.maxOfOrNull { it.layoutMetrics.ascent }
            ?: (fontSize * CJK_FACE_ASCENT_FALLBACK_EM)
        val baseDescent = baseMetricDecisions.maxOfOrNull { it.layoutMetrics.descent }
            ?: (fontSize * CJK_FACE_DESCENT_FALLBACK_EM)
        // BaseIdeographicMetricReference (ADR 0002/0030): CJK body text AND CJK
        // punctuation both normalize to an IdeographicEmBox on the shared Roman
        // baseline. The reference therefore comes from any base-size ideographic
        // metric, not only FontRole.CjkText; otherwise "MacBook。" has an
        // ideographic line box but the punctuation is shifted toward Latin raw
        // descent because no Han body cluster appears.
        val baseRefMetrics = metricDecisions
            .firstOrNull {
                it.layoutMetrics.metricBox == MetricBox.IdeographicEmBox &&
                    it.request.fontSize == fontSize
            }
            ?.layoutMetrics
        val baseBoxDescent = baseRefMetrics?.descent ?: baseDescent
        // Required vertical space comes from the resolved Latin face's declared
        // ascent/descent. It is stable for every reading in the same font; glyph
        // bounds are deliberately irrelevant to line-height and placement.
        val rubyExtent = rubyFontGeometryBySpan.values.maxOfOrNull { it.requiredExtent } ?: 0f

        // InterlinearMarkLineSpacingFloor (CLREQ 5.6.1.1): with 行间标点
        // (着重号、示亡号 etc.) present, line spacing (height − 字身高) must not
        // drop below 1/2 字号 — a tight line height would collide the marks with
        // the next line. (双面装 5/8 is print-only — show-through — deferred to a
        // print backend, like 竖排.)
        val interlinearSpacingFloor = if (input.decorations.isEmpty()) 0f else 0.5f * fontSize
        val defaultBodyLineHeight = fontSize * DEFAULT_BODY_LINE_HEIGHT_EM
        // Resolve the unannotated baseline grid first. Ruby can consume its full
        // inter-line gap (not merely this line box's upper half-leading); only the
        // measured shortfall is added below on a per-line or paragraph-wide basis.
        val baseLineMetrics = metricDecisions.lineMetrics(
            explicitLineHeight = input.paragraphStyle.lineHeight,
            defaultLineHeight = defaultBodyLineHeight,
            spacingFloor = interlinearSpacingFloor,
        )
        val metricDecisionByRange: Map<TextRange, ClusterMetricDecision> = naturalClusters
            .zip(naturalClusters.containingItems(metricDecisions, ClusterMetricDecision::range))
            .mapNotNull { (cluster, decision) -> decision?.let { cluster.range to it } }
            .toMap()
        val baseFaceHeight = baseAscent + baseDescent
        val existingInterlineSpace = (baseLineMetrics.height - baseFaceHeight).coerceAtLeast(0f)
        val lineSpacingDecision = if (baseLineMetrics.height <= 0f) {
            null
        } else {
            val natural = baseLineMetrics.height - baseLineMetrics.extraLeading
            val requested = input.paragraphStyle.lineHeight
            // Did the mark floor raise the line above what the explicit/default
            // height alone would give? (The 0.5em floor is subsumed by the 1.5em
            // body default, so it only binds against an explicit tight lineHeight.)
            val markFloorBinds = interlinearSpacingFloor > 0f &&
                natural + interlinearSpacingFloor > (requested ?: defaultBodyLineHeight) + 0.001f
            LineSpacingDecisionInfo(
                naturalHeight = natural,
                requestedLineHeight = requested,
                resolvedHeight = baseLineMetrics.height,
                spacingFloor = interlinearSpacingFloor,
                floorApplied = markFloorBinds,
                reason = when {
                    requested != null && !markFloorBinds -> "ExplicitLineHeight"
                    markFloorBinds -> "InterlinearMarkLineSpacingFloor"
                    else -> "CjkBodyLineHeightDefault"
                },
            )
        }
        // ParagraphFirstLineIndent (CLREQ 段首缩排): the first line's usable
        // measure shrinks by the indent; rendering shifts its start edge.
        // MeasureAdaptiveFirstLineIndent: the indent default narrows to 1 字 on
        // short measures (< shortBelowEm 字); an explicit firstLineIndent (ic)
        // overrides. Threshold defaults to 14 字 like MeasureAdaptiveKinsoku's
        // hanging but is an INDEPENDENT knob (ADR 0021 amendment).
        val explicitIndentEm = input.paragraphStyle.firstLineIndent?.count
        val indentPolicy = input.paragraphStyle.firstLineIndentPolicy
        // 段落缩排 (block indent) insets EVERY line; 段首缩进 (firstLine) stacks on
        // top, relative to the block, and MAY be negative (凸排：首行退回字头).
        // Adaptive default is ≥0; an explicit value flows through as-is (incl.
        // negative). The effective per-line indent is clamped ≥0 at use.
        // `ic` resolves against the paragraph base 字身框 = fontSize (ADR 0034 段级锚点).
        val blockIndent = input.paragraphStyle.blockIndent.toPx(fontSize)
        val resolvedIndentEm = explicitIndentEm ?: indentPolicy.resolveEm(measureEm)
        val firstLineIndent = (blockIndent + resolvedIndentEm * fontSize).coerceAtLeast(0f)
        val firstLineIndentDecision = FirstLineIndentDecisionInfo(
            source = if (explicitIndentEm != null) "Explicit" else "MeasureAdaptiveFirstLineIndent",
            measureEm = measureEm,
            thresholdEm = indentPolicy.shortBelowEm,
            resolvedEm = resolvedIndentEm,
        )
        // Resolve 禁则档 + 悬挂 from the kinsoku mode and the measure in 字
        // (MeasureAdaptiveKinsoku default keys on measure/fontSize).
        val kinsokuDecision = KinsokuDecisionInfo(
            measureEm = measureEm,
            level = resolvedKinsoku.level.name,
            hanging = resolvedKinsoku.hanging.name,
            reason = resolvedKinsoku.reason,
        )
        // LineEndHangingPunctuation (CLREQ 行尾点号悬挂, ADR 0006): which
        // clusters may hang past the measure. 顿/逗/句 only.
        val hangableClusters: Set<Int> = when (resolvedKinsoku.hanging) {
            HangingPunctuationStyle.Disabled -> emptySet()
            HangingPunctuationStyle.PauseStops -> naturalClusters.indices.filterTo(mutableSetOf()) { idx ->
                naturalClusters[idx].displayText.singleOrNull() in HANGABLE_PUNCTUATION
            }
        }
        // 行首/行尾禁则按解析出的 KinsokuLevel（CLREQ 四档）；空集 = 不处理档.
        val asciiPointMarkKinsoku = naturalClusters.attachedAsciiPointMarkKinsoku(
            clusterRoles = clusterRoles,
            lineBreakClusters = clusters,
            level = resolvedKinsoku.level,
            bodyLineWidth = measure - blockIndent,
            firstLineWidth = measure - firstLineIndent,
        )
        val inlineObjectKinsoku = naturalClusters.inlineObjectAttachedKinsoku(
            attachments = inlineObjectAttachedMarks,
            lineBreakClusters = clusters,
            level = resolvedKinsoku.level,
            bodyLineWidth = measure - blockIndent,
            firstLineWidth = measure - firstLineIndent,
        )
        val resolvedHangableClusters =
            hangableClusters +
                asciiPointMarkKinsoku.impossibleMeasureHangEligibleClusters +
                inlineObjectKinsoku.impossibleMeasureHangEligibleClusters
        val unicodePunctuationBoundaries = resolveUnicodePunctuationBoundaries(
            text = text,
            clusters = naturalClusters,
            clusterRoles = clusterRoles,
            quotePairs = quotePairs,
        )
        val inlineAttachments = naturalInlineAttachments
        val westernBracketBoundaries = resolveWesternBracketCjkInterCharBoundaries(
                text = text,
                clusters = naturalClusters,
                clusterRoles = clusterRoles,
            )
        val attachedInlineInterCharBoundaries = resolveAttachedInlineInterCharBoundaries(
            text = text,
            clusters = naturalClusters,
            clusterRoles = clusterRoles,
            eastAsianSpacingEdges = eastAsianSpacingEdges,
            westernBoundaryAfterClusters = westernBracketBoundaries,
            inlineAttachments = inlineAttachments,
        )
        val westernBracketCjkInterCharBoundaryAfterClusters =
            attachedInlineInterCharBoundaries.ordinaryWesternBoundaryAfterClusters
        val attachedInlinePhysicalBoundaryAfterClusters =
            attachedInlineInterCharBoundaries.suppressedPhysicalBoundaryAfterClusters
        val attachedInlineVirtualBoundaryAfterClusters =
            attachedInlineInterCharBoundaries.virtualBoundaryAfterClusters
        val attachedInlineVirtualSinoWesternBoundaryAfterClusters =
            attachedInlineInterCharBoundaries.virtualSinoWesternBoundaryAfterClusters
        val attachedInlineForbiddenLineStartClusters = inlineAttachments.indices.filterTo(mutableSetOf()) {
            inlineAttachments[it] == InlineAttachment.Previous
        }
        val forbiddenLineStartClusters: Set<Int> = naturalClusters.indices.filterTo(mutableSetOf()) { idx ->
            idx in attachedInlineForbiddenLineStartClusters ||
                idx in zeroWidthBreakClusters ||
                (
                clusterRoles.getOrNull(idx).isCjkKinsokuRole() &&
                    kinsokuRule.forbiddenAtLineStart(naturalClusters[idx])
                ) ||
                idx in unicodePunctuationBoundaries.forbiddenLineStartClusters ||
                idx in asciiPointMarkKinsoku.forbiddenLineStartClusters ||
                idx in inlineObjectKinsoku.forbiddenLineStartClusters
        }
        val forbiddenLineEndClusters: Set<Int> = naturalClusters.indices.filterTo(mutableSetOf()) { idx ->
            (
                clusterRoles.getOrNull(idx).isCjkKinsokuRole() &&
                    kinsokuRule.forbiddenAtLineEnd(naturalClusters[idx])
                ) ||
                idx in unicodePunctuationBoundaries.forbiddenLineEndClusters
        }
        // LineEndHangingHyphen as a LAST resort (ADR 0029 amendment): a break
        // before one of these clusters is a syllable/hard-break continuation —
        // the breaker prefers whole-word wrap + justification and only takes it
        // when the line would otherwise stretch 汉字间距 past the threshold.
        val hyphenBreakClusters: Set<Int> = if (hyphenOffsets.isEmpty()) {
            emptySet()
        } else {
            naturalClusters.indices.filterTo(mutableSetOf()) {
                naturalClusters[it].range.start in hyphenOffsets
            }
        }
        val clusterIndexBySourceStart = naturalClusters.indices.associateBy { naturalClusters[it].range.start }
        val progressiveTechnicalWhitespaceStretchCapacity =
            justifier.progressiveTechnicalWhitespaceStretchCapacity(fontSize)
        val progressiveBreakOpportunities: Map<Int, ProgressiveBreakOpportunity> =
            progressiveBreakOffsets.mapNotNull { (sourceOffset, opportunity) ->
                clusterIndexBySourceStart[sourceOffset]?.let { clusterIndex ->
                    clusterIndex to if (opportunity.tier == ProgressiveBreakTier.Whitespace) {
                        opportunity.copy(
                            precedingWhitespaceStretchCapacity =
                                progressiveTechnicalWhitespaceStretchCapacity,
                        )
                    } else {
                        opportunity
                    }
                }
            }.toMap()
        val progressiveTechnicalRanges = input.content.lineBreakSpans
            .filter { it.policy == LineBreakPolicy.ProgressiveTechnical }
            .map { it.range }
        val numberSymbolClusterRanges = NumberSymbolCohesion.unbreakableRanges(text)
            // `ProgressiveTechnicalOverridesNumberSymbolCohesion`: CLREQ's number/unit cohesion
            // describes prose numbers such as `37℃` and `¥100`. Digits inside an explicitly
            // technical URL/hash/code span belong to that span's Structural → Syllable →
            // Emergency policy; treating a long digit run as unbreakable can retreat a rightmost
            // Emergency cut hundreds of pixels and then fill the gap with letter tracking.
            .filterNot { sourceRange ->
                progressiveTechnicalRanges.any { technicalRange ->
                    sourceRange.first < technicalRange.end &&
                        sourceRange.last + 1 > technicalRange.start
                }
            }
            .mapNotNull { r ->
                naturalClusters.clusterIndexRangeFor(TextRange(r.first, r.last + 1))
            }
        val numberSymbolUnbreakableRanges = numberSymbolClusterRanges
            .filter { idxRange ->
                idxRange.sumOf { naturalClusters[it].advance.toDouble() } <= measure
            }
        // CLREQ 拉伸限制②：连接号、分隔号与其左右字符之间不拉伸。
        // 原子长标号也封住两侧，避免制造看似源文本空格的间距。
        val noStretchBoundaryClusters: Set<Int> = naturalClusters.indices.filterTo(mutableSetOf()) { idx ->
            when (atomClassByRange[naturalClusters[idx].range]) {
                PunctuationClass.Connector,
                PunctuationClass.Solidus,
                PunctuationClass.Dash,
                PunctuationClass.Ellipsis,
                -> true
                else -> false
            }
        }
        val noStretchBoundaryAfterClusters = numberSymbolClusterRanges
            .flatMapTo(mutableSetOf()) { range -> range.first until range.last }
            .apply {
                addAll(inlineObjectAttachmentNoStretchBoundaries)
            }
        val technicalBoundaryAfterClusters = progressiveBreakOpportunities
            .filterValues { it.tier == ProgressiveBreakTier.Whitespace }
            .mapKeys { (rightIndex, _) -> rightIndex - 1 }
            .mapValues { (_, opportunity) -> opportunity.tier }
        // `ExplicitEmergencyTrackingEligibility`: only ranges named by the shaping
        // stage may open intra-token tracking. The map is cluster-indexed so the
        // Justifier replays source-grapheme boundaries without reclassifying text.
        val emergencyTrackingBoundaryAfterClusters = buildMap<Int, String> {
            for (leftIndex in 0 until naturalClusters.lastIndex) {
                val rightIndex = leftIndex + 1
                val left = naturalClusters[leftIndex]
                val right = naturalClusters[rightIndex]
                if (left.range.end != right.range.start) continue
                if (
                    leftIndex in inlineObjectByClusterIndex || rightIndex in inlineObjectByClusterIndex ||
                    leftIndex in zeroWidthBreakClusters || rightIndex in zeroWidthBreakClusters ||
                    leftIndex in mandatoryBreakClusters || rightIndex in mandatoryBreakClusters ||
                    left.text.isEmpty() || right.text.isEmpty() ||
                    left.text.all(Char::isWhitespace) || right.text.all(Char::isWhitespace)
                ) {
                    continue
                }
                val eligibility = emergencyTrackingEligibilityDecisions.firstOrNull { decision ->
                    left.range.start >= decision.range.start && right.range.end <= decision.range.end
                } ?: continue
                put(leftIndex, eligibility.reason)
            }
        }
        // The breaker's looseness estimate keeps its established two-class
        // approximation. The exact final allocation, including repeated
        // participation of word and sino-western gaps, belongs to Justifier.
        val adjustableInlineBoundaryRightClusters = uniformInlineObjectBoundaryAfterClusters
            .mapNotNullTo(mutableSetOf()) { leftIndex ->
                val rightIndex = leftIndex + 1
                if (
                    leftIndex in noStretchBoundaryAfterClusters ||
                    leftIndex in noStretchBoundaryClusters ||
                    rightIndex in noStretchBoundaryClusters
                ) {
                    null
                } else {
                    rightIndex
                }
            }
        val cjkInterCharBoundaries: Set<Int> = buildSet {
            addAll((1 until naturalClusters.size).filter {
                it - 1 !in attachedInlinePhysicalBoundaryAfterClusters &&
                    it - 1 !in noStretchBoundaryAfterClusters &&
                    clusterRoles[it - 1] == FontRole.CjkText && clusterRoles[it] == FontRole.CjkText
            })
            // Paragraph-global and lookahead breakers price the same safe
            // inline-object gaps that the final justifier can actually use.
            addAll(adjustableInlineBoundaryRightClusters)
            // `WesternBracketCjkInterChar`: the breaker must price the same
            // proportional-bracket gaps that final tier-3 justification uses.
            addAll(westernBracketCjkInterCharBoundaryAfterClusters.map { it + 1 })
            addAll(attachedInlineVirtualBoundaryAfterClusters.keys.map { it + 1 })
        }
        val sinoWesternBoundaries: Set<Int> = buildSet {
            addAll((1 until naturalClusters.size).filter {
                it - 1 !in attachedInlinePhysicalBoundaryAfterClusters &&
                    it - 1 !in noStretchBoundaryAfterClusters &&
                    isEastAsianSpacingBoundaryAt(
                        rightIndex = it,
                        clusters = naturalClusters,
                        spacingEdges = eastAsianSpacingEdges,
                    )
            })
            addAll(attachedInlineVirtualSinoWesternBoundaryAfterClusters.map { it + 1 })
        }
        val attachedInlineUnbreakableRanges =
            resolveAttachedInlineVirtualBoundaries(inlineAttachments).map { boundary ->
                boundary.previousClusterIndex..boundary.attachedClusterRange.last
            }
        val unbreakableRanges =
            input.decorations
                .filter { it.kind == DecorationKind.Mourning }
                .mapNotNull { span -> naturalClusters.clusterIndexRangeFor(span.range) } +
                // 行间注 (ADR 0032): 基文+注文不可拆 (CLREQ §注释符号).
                pinyinSpans.mapNotNull { naturalClusters.clusterIndexRangeFor(it.baseRange) } +
                // `AttachedInlineAvoidLineStart`: the forbidden-line-start set
                // drives kinsoku repair; this structural range also prevents the
                // initial breaker from proposing a split inside base+reference.
                attachedInlineUnbreakableRanges +
                numberSymbolUnbreakableRanges +
                // `Uax14WesternPunctuationBoundary`: express punctuation
                // protection as a closed boundary up front, not only as a
                // post-break repair. This allows ordinary reflow to move the
                // following suffix instead of leaving a closing mark at line start.
                unicodePunctuationBoundaries.unbreakableRanges +
                // `AttachedAsciiPointMarkKinsoku`: the preceding visible
                // cluster and its attached point mark form a hard no-break boundary.
                asciiPointMarkKinsoku.unbreakableRanges +
                // `InlineObjectAttachedKinsoku`: formula/widget objects are
                // visibly present even though their displayText is empty.
                inlineObjectKinsoku.unbreakableRanges +
                // Adjustment-only formula boundaries do not become accidental line breaks.
                inlineObjectBoundaryUnbreakableRanges
        val lineSolution = if (text.isEmpty()) {
            LineSolution(emptyList())
        } else {
            lineBreaker.breakLines(
                naturalClusters = naturalClusters,
                adjustedClusters = clusters,
                // The breaker only needs per-line USABLE widths (via lineLimit):
                // feed it the body width (measure − blockIndent) and a first-line
                // indent relative to it. Rest lines then get the body width, line 0
                // gets `measure − firstLineIndent`. Identical to before when
                // blockIndent = 0; enables 段落缩排/凸排 with zero breaker changes.
                maxWidth = measure - blockIndent,
                firstLineIndent = firstLineIndent - blockIndent,
                shrinkOpportunities = shrinkOpportunities,
                // MourningSpanKeptUnbroken: 示亡号 spans stay on one line
                // whenever they fit (ADR 0018). NumberSymbolCohesion: CLREQ
                // 符号分离禁则 keeps 数字 + 前后缀符号/货币 on one line — but only
                // when the group actually fits the measure; a number wider than
                // the column can't be kept whole, so it falls back to normal
                // breaking instead of forcing an impossible constraint.
                unbreakableRanges = unbreakableRanges,
                hangableClusters = resolvedHangableClusters,
                extendableHangRanges =
                    asciiPointMarkKinsoku.extendableHangRanges + inlineObjectKinsoku.extendableHangRanges,
                forbiddenLineStartClusters = forbiddenLineStartClusters,
                forbiddenLineEndClusters = forbiddenLineEndClusters,
                hyphenBreakClusters = hyphenBreakClusters,
                cjkInterCharBoundaries = cjkInterCharBoundaries,
                maxCjkStretchPerGap = HYPHEN_LAST_RESORT_CJK_STRETCH_EM * fontSize,
                sinoWesternBoundaries = sinoWesternBoundaries,
                sinoWesternStretchCap = HYPHEN_SINO_WESTERN_STRETCH_CAP_EM * fontSize,
                // LineAdjustmentStrategy (ADR 0031 修订): 推入/推出 是固定顺序,
                // 不再有「偏差最小化」折中。PushInFirst = 能压就压(bias→∞),
                // PushOutFirst = 先断行拉伸, PushOutOnly = 从不推入(旧行为)。
                lineAdjustmentPushIn = adjustmentStyle.lineAdjustment != LineAdjustmentStrategy.PushOutOnly,
                lineAdjustmentCompressBias = when (adjustmentStyle.lineAdjustment) {
                    LineAdjustmentStrategy.PushInFirst -> 1_000_000f
                    LineAdjustmentStrategy.PushOutFirst -> 0.5f
                    LineAdjustmentStrategy.PushOutOnly -> 0f
                },
                hardBreakAfterClusters = mandatoryBreakClusters,
                nonRenderingControlClusters = zeroWidthBreakClusters,
                progressiveBreakOpportunities = progressiveBreakOpportunities,
            )
        }
        val appliedHangingClusters = lineSolution.lines
            .flatMap { it.hangingClusterIndices }
            .toSet()
        val impossibleMeasureContextualHangClusters =
            asciiPointMarkKinsoku.impossibleMeasureHangEligibleClusters +
                inlineObjectKinsoku.impossibleMeasureHangEligibleClusters
        val contextualKinsokuDecisions =
            (
                asciiPointMarkKinsoku.decisions +
                    inlineObjectKinsoku.decisions +
                    unicodePunctuationBoundaries.decisions
                )
                .distinctBy { decision -> decision.range to decision.forbiddenPosition }
                .map { decision ->
            if (
                decision.clusterIndex in impossibleMeasureContextualHangClusters &&
                decision.clusterIndex in appliedHangingClusters
            ) {
                decision.copy(
                    impossibleMeasureFallback = when (decision.reason) {
                        "AttachedAsciiPointMarkKinsoku" -> "AttachedAsciiPointMarkImpossibleMeasureHang"
                        else -> "InlineObjectAttachedMarkImpossibleMeasureHang"
                    },
                )
            } else {
                decision
            }
        }
        val pushInAllocations = lineSolution.lines
            .mapNotNull { it.repair as? RepairOption.PushIn }
            .flatMap { it.allocations }
        val pushInTrailing = HashMap<Int, Float>()
        val pushInLeading = HashMap<Int, Float>()
        val pushInRawTrims = HashMap<Int, Float>()
        for (alloc in pushInAllocations) {
            when (alloc.channel) {
                ShrinkChannel.TrailingGlue ->
                    pushInTrailing.mergeValue(alloc.clusterIndex, alloc.shrink) { a, b -> a + b }
                ShrinkChannel.LeadingGlue ->
                    // 开夹注符号前侧（CLREQ 挤压④）；渲染层按 consumed
                    // leading 左移字形原点（ADR 0017 amendment）。
                    pushInLeading.mergeValue(alloc.clusterIndex, alloc.shrink) { a, b -> a + b }
                ShrinkChannel.LeadingAndTrailingGlue -> {
                    // CLREQ: 间隔号挤压必须同时从字面两侧、同等量处理.
                    pushInLeading.mergeValue(alloc.clusterIndex, alloc.shrink / 2f) { a, b -> a + b }
                    pushInTrailing.mergeValue(alloc.clusterIndex, alloc.shrink / 2f) { a, b -> a + b }
                }
                ShrinkChannel.RawAdvance ->
                    pushInRawTrims.mergeValue(alloc.clusterIndex, alloc.shrink) { a, b -> a + b }
            }
        }
        // LineEndHangingHyphen 标点挤压 (ADR 0029 amend): a reserved hyphen that
        // would overflow the measure first squeezes the line's compressible glue
        // (the same `shrinkOpportunities`, in CLREQ 挤压 tier order, minus what
        // PushIn already took); only the residual it cannot recover hangs past
        // the edge. Augments the PushIn consume maps so the geometry applies both.
        fun lineHyphenAdvanceAt(lineIndex: Int): Float {
            if (hyphenOffsets.isEmpty() || lineIndex >= lineSolution.lines.lastIndex) return 0f
            val next = lineSolution.lines[lineIndex + 1]
            if (next.clusterRange.isEmptyClusterRange()) return 0f
            val nextFirst = next.clusterRange.first
            return if (naturalClusters[nextFirst].range.start in hyphenOffsets) hyphenAdvance else 0f
        }
        if (hyphenOffsets.isNotEmpty()) {
            lineSolution.lines.forEachIndexed { lineIndex, line ->
                if (line.clusterRange.isEmptyClusterRange()) return@forEachIndexed
                val hyphen = lineHyphenAdvanceAt(lineIndex)
                if (hyphen <= 0f) return@forEachIndexed
                val lineLimit = if (line.clusterRange.first == 0) measure - firstLineIndent else measure - blockIndent
                val content = line.clusterRange.sumOf { clusters[it].advance.toDouble() }.toFloat()
                var shortfall = content + hyphen - lineLimit
                if (shortfall <= 0.001f) return@forEachIndexed
                for (opp in shrinkOpportunities.filter { it.clusterIndex in line.clusterRange && !it.lineEndOnly }.sortedBy { it.tier }) {
                    if (shortfall <= 0.001f) break
                    val used = when (opp.channel) {
                        ShrinkChannel.TrailingGlue -> pushInTrailing[opp.clusterIndex] ?: 0f
                        ShrinkChannel.LeadingGlue -> pushInLeading[opp.clusterIndex] ?: 0f
                        ShrinkChannel.RawAdvance -> pushInRawTrims[opp.clusterIndex] ?: 0f
                        ShrinkChannel.LeadingAndTrailingGlue ->
                            (pushInLeading[opp.clusterIndex] ?: 0f) + (pushInTrailing[opp.clusterIndex] ?: 0f)
                    }
                    val take = minOf(shortfall, (opp.capacity - used).coerceAtLeast(0f))
                    if (take <= 0f) continue
                    when (opp.channel) {
                        ShrinkChannel.TrailingGlue -> pushInTrailing.mergeValue(opp.clusterIndex, take) { a, b -> a + b }
                        ShrinkChannel.LeadingGlue -> pushInLeading.mergeValue(opp.clusterIndex, take) { a, b -> a + b }
                        ShrinkChannel.LeadingAndTrailingGlue -> {
                            pushInLeading.mergeValue(opp.clusterIndex, take / 2f) { a, b -> a + b }
                            pushInTrailing.mergeValue(opp.clusterIndex, take / 2f) { a, b -> a + b }
                        }
                        ShrinkChannel.RawAdvance -> pushInRawTrims.mergeValue(opp.clusterIndex, take) { a, b -> a + b }
                    }
                    shortfall -= take
                }
            }
        }
        val pushInGeometry = baseGeometry
            .consumeTrailingByCluster(pushInTrailing)
            .consumeLeadingByCluster(pushInLeading)
        val pushInClusters = pushInGeometry.resolveClusters()
        val edgeTrimResult = pushInGeometry.consumeLineEdgeGlue(
            lines = lineSolution.lines,
            forceLineEndHalfWidth = adjustmentStyle.lineEndPunctuation ==
                LineEndPunctuationStyle.ForceHalfWidth,
        )
        // TextAutoSpaceLineEdgeTrim: the autospace replacement gap lives in
        // the Latin cluster's advance, not in punctuation glue, so the edge
        // trim above can't see it. A typed-space boundary gap landing on a
        // line edge must disappear like any other line-edge blank — without
        // this, justified lines stop one gap short of the right edge.
        val autoSpaceGap = clreqProfile.autoSpace.gapEm * fontSize
        val autoSpaceEdgeTrims = HashMap<Int, Float>()
        val autoSpaceEdgeDecisions = mutableListOf<LineEdgeTrimDecisionInfo>()
        lineSolution.lines.forEach { line ->
            if (line.clusterRange.isEmptyClusterRange()) return@forEach
            fun trimEdge(clusterIdx: Int, side: String) {
                val decision = autoSpaceDecisions.firstOrNull {
                    it.clusterRange == naturalClusters[clusterIdx].range && it.side == side
                } ?: return
                autoSpaceEdgeTrims.mergeValue(clusterIdx, autoSpaceGap) { a, b -> a + b }
                autoSpaceEdgeDecisions += LineEdgeTrimDecisionInfo(
                    lineRange = line.sourceRange,
                    clusterRange = decision.clusterRange,
                    side = side,
                    trimAmount = autoSpaceGap,
                    consumedBefore = 0f,
                    naturalGlue = autoSpaceGap,
                    reason = "TextAutoSpaceLineEdgeTrim",
                )
            }
            trimEdge(line.clusterRange.last, "trailing")
            trimEdge(line.clusterRange.first, "leading")

            // LineEdgeWordSpaceCollapse: a space-run cluster landing on a
            // line edge collapses entirely (CSS-like line-edge space
            // removal; also CLREQ — no sino-western gap at line edges).
            fun collapseEdgeSpace(clusterIdx: Int, side: String) {
                val cluster = naturalClusters[clusterIdx]
                if (!cluster.isSpaceRun()) return
                if (clusterIdx in inlineObjectSeparatorSpaceTrims) return
                val advance = naturalClusters[clusterIdx].advance
                if (advance <= 0f) return
                autoSpaceEdgeTrims.mergeValue(clusterIdx, advance) { a, b -> a + b }
                autoSpaceEdgeDecisions += LineEdgeTrimDecisionInfo(
                    lineRange = line.sourceRange,
                    clusterRange = cluster.range,
                    side = side,
                    trimAmount = advance,
                    consumedBefore = 0f,
                    naturalGlue = advance,
                    reason = "LineEdgeWordSpaceCollapse",
                )
            }
            collapseEdgeSpace(line.clusterRange.last, "trailing")
            collapseEdgeSpace(line.clusterRange.first, "leading")

            val attachedGlueCluster = line.clusterRange.last
            val attachedGlue = attachedPunctuationTrailingGlueByCluster[attachedGlueCluster] ?: 0f
            if (attachedGlue > 0f) {
                autoSpaceEdgeTrims.mergeValue(attachedGlueCluster, attachedGlue) { a, b -> a + b }
                autoSpaceEdgeDecisions += LineEdgeTrimDecisionInfo(
                    lineRange = line.sourceRange,
                    clusterRange = naturalClusters[attachedGlueCluster].range,
                    side = "trailing",
                    trimAmount = attachedGlue,
                    consumedBefore = 0f,
                    naturalGlue = attachedGlue,
                    reason = "AttachedInlineVirtualBoundaryLineEndTrim",
                )
            }

            // InlineObjectLineEndDiscardableGlue: a formula fragment includes its natural
            // post-operator math spacing when it stays in the line. If the paragraph actually
            // breaks at that boundary, the space is line-edge glue rather than visible content.
            // Remove only the part not already consumed by PushIn; the following fragment has no
            // corresponding leading advance, so both the old line end and new line start stay flush.
            if (line.endReason == LineEndReason.AutoWrap) {
                val clusterIdx = line.clusterRange.last
                val discardable = inlineObjectByClusterIndex[clusterIdx]
                    ?.trailingBoundary
                    ?.lineEndDiscardableAdvance
                    ?: 0f
                val consumedBefore = minOf(pushInRawTrims[clusterIdx] ?: 0f, discardable)
                val remaining = (discardable - consumedBefore).coerceAtLeast(0f)
                if (remaining > 0f) {
                    autoSpaceEdgeTrims.mergeValue(clusterIdx, remaining) { a, b -> a + b }
                    autoSpaceEdgeDecisions += LineEdgeTrimDecisionInfo(
                        lineRange = line.sourceRange,
                        clusterRange = naturalClusters[clusterIdx].range,
                        side = "trailing",
                        trimAmount = remaining,
                        consumedBefore = consumedBefore,
                        naturalGlue = discardable,
                        reason = "InlineObjectLineEndDiscardableGlue",
                    )
                }
            }
        }
        val rawTrims = HashMap<Int, Float>(autoSpaceEdgeTrims)
        pushInRawTrims.forEach { (idx, amount) -> rawTrims.mergeValue(idx, amount) { a, b -> a + b } }
        val trimmedGeometry = edgeTrimResult.geometry.withRawEdgeTrims(rawTrims)
        val trimmedClusters = trimmedGeometry.resolveClusters()
        val edgeTrimDecisions = edgeTrimResult.decisions + autoSpaceEdgeDecisions

        // LineEndHangingHyphen reserved width (ADR 0029 amend): a line that ends
        // mid-word at a hyphenation point gives the trailing hyphen real width
        // inside the measure — like a line-end punctuation mark, NOT hung by
        // default. The content therefore fills only `measure − hyphen`; when the
        // content can't be squeezed that far (over-long words with no room) the
        // hyphen falls past the edge (hangs) as a last resort, automatically.
        // CLREQ:「中文排版特别是书籍正文排版极少使用左齐右不齐，原则上
        // 应该进行两端对齐」— justification is the baseline, not an option:
        // every non-last line goes through the justify chain. The last line
        // is positioned by ParagraphStyle.lastLineAlignment instead.
        val justificationPlans: List<JustificationPlan?> = lineSolution.lines.mapIndexed { lineIndex, lineCandidate ->
            val isLast = lineIndex == lineSolution.lines.lastIndex
            if (isLast || lineCandidate.clusterRange.isEmptyClusterRange() || lineCandidate.endReason != LineEndReason.AutoWrap) {
                null
            } else {
                val selectedTechnicalBreak =
                    progressiveBreakOpportunities[lineCandidate.clusterRange.last + 1]
                val preferredTrackingSpan = selectedTechnicalBreak
                    ?.spanRange
                    ?.takeIf { selectedTechnicalBreak.tier == ProgressiveBreakTier.Emergency }
                val preferredEmergencyTrackingBoundaries = if (preferredTrackingSpan == null) {
                    emptyMap()
                } else {
                    emergencyTrackingBoundaryAfterClusters.filterKeys { leftIndex ->
                        val rightIndex = leftIndex + 1
                        naturalClusters[leftIndex].range.start >= preferredTrackingSpan.start &&
                            naturalClusters[rightIndex].range.end <= preferredTrackingSpan.end
                    }
                }
                // A hung mark sits beyond the measure: justify fills the
                // CONTENT (range minus the hanging mark) to maxWidth.
                justifier.justify(
                    adjustedClusters = trimmedClusters,
                    clusterRoles = clusterRoles,
                    eastAsianSpacingEdges = eastAsianSpacingEdges,
                    lineClusterRange = lineCandidate.inMeasureClusterRange,
                    maxWidth = (if (lineCandidate.clusterRange.first == 0) {
                        measure - firstLineIndent
                    } else {
                        measure - blockIndent
                    }) - lineHyphenAdvanceAt(lineIndex),
                    fontSize = fontSize,
                    skip = false,
                    allowSinoWesternGapStretch = adjustmentStyle.allowSinoWesternGapAdjustment,
                    cjkLatinSpaceBaseEm = clreqProfile.autoSpace.gapEm,
                    cjkLatinSpaceMaxEm = clreqProfile.autoSpace.stretchMaxEm,
                    noStretchBoundaryClusters = noStretchBoundaryClusters,
                    noStretchBoundaryAfterClusters = noStretchBoundaryAfterClusters,
                    westernBracketCjkInterCharBoundaryAfterClusters =
                        westernBracketCjkInterCharBoundaryAfterClusters,
                    attachedInlinePhysicalBoundaryAfterClusters =
                        attachedInlinePhysicalBoundaryAfterClusters,
                    attachedInlineVirtualBoundaryAfterClusters =
                        attachedInlineVirtualBoundaryAfterClusters,
                    attachedInlineVirtualSinoWesternBoundaryAfterClusters =
                        attachedInlineVirtualSinoWesternBoundaryAfterClusters,
                    uniformInlineObjectBoundaryAfterClusters = uniformInlineObjectBoundaryAfterClusters,
                    preferredInlineObjectBoundaryAfterClusters = preferredInlineObjectBoundaryAfterClusters,
                    technicalBoundaryAfterClusters = technicalBoundaryAfterClusters,
                    emergencyTrackingBoundaryAfterClusters = emergencyTrackingBoundaryAfterClusters,
                    preferredEmergencyTrackingBoundaryAfterClusters = preferredEmergencyTrackingBoundaries,
                )
            }
        }
        // `CurrentLineTechnicalTierRejection`: whether a complete technical token could fit some
        // other line is irrelevant to this line's decision. If a non-Emergency tier still requires
        // unbounded body or grapheme tracking after real trimming and justification, reject that
        // exact tier for the span and replay the hierarchy. The retry exposes Emergency candidates
        // but still gives every not-yet-rejected cleaner tier its normal chance. Since every retry
        // adds at least one of the finite tiers, recursion is bounded and monotonic.
        val currentLineTechnicalBodyStretchLimit =
            CURRENT_LINE_TECHNICAL_BODY_STRETCH_LIMIT_EM * fontSize
        val newlyRejectedTechnicalTiers = mutableMapOf<TextRange, MutableSet<ProgressiveBreakTier>>()
        lineSolution.lines.indices.forEach { lineIndex ->
                val line = lineSolution.lines[lineIndex]
                if (line.endReason != LineEndReason.AutoWrap || line.clusterRange.isEmptyClusterRange()) {
                    return@forEach
                }
                val selectedTechnicalBreak = progressiveBreakOpportunities[line.clusterRange.last + 1]
                    ?.takeUnless { it.tier == ProgressiveBreakTier.Emergency }
                    ?: return@forEach
                val rejectedForSpan = rejectedTechnicalTiersBySpan[selectedTechnicalBreak.spanRange].orEmpty()
                if (selectedTechnicalBreak.tier in rejectedForSpan) return@forEach
                val currentLinePlan = justificationPlans.getOrNull(lineIndex) ?: return@forEach
                val currentLineUsesUnboundedTracking = currentLinePlan.allocations.any { allocation ->
                    (allocation.kind == GlueKind.CjkInterChar ||
                        allocation.kind == GlueKind.EmergencyGraphemeTracking) &&
                        allocation.delta >
                        currentLineTechnicalBodyStretchLimit + TECHNICAL_STRETCH_EPSILON_PX
                }
                if (currentLineUsesUnboundedTracking) {
                    newlyRejectedTechnicalTiers
                        .getOrPut(selectedTechnicalBreak.spanRange) { mutableSetOf() }
                        .add(selectedTechnicalBreak.tier)
                }
            }
        if (newlyRejectedTechnicalTiers.isNotEmpty()) {
            val updatedRejectedTiers = rejectedTechnicalTiersBySpan
                .mapValues { (_, tiers) -> tiers.toMutableSet() }
                .toMutableMap()
            newlyRejectedTechnicalTiers.forEach { (span, tiers) ->
                updatedRejectedTiers.getOrPut(span) { mutableSetOf() }.addAll(tiers)
            }
            return layoutWithRejectedTechnicalTiers(
                input,
                updatedRejectedTiers,
            )
        }
        val justifyDeltaByCluster = HashMap<Int, Float>().apply {
            justificationPlans.filterNotNull()
                .flatMap { it.allocations }
                .forEach { alloc -> mergeValue(alloc.targetClusterIndex, alloc.delta) { a, b -> a + b } }
        }
        val finalGeometry = trimmedGeometry.addJustificationDeltas(justifyDeltaByCluster)
        val finalClusters = finalGeometry.resolveClusters().map { c ->
            // 字身框 bottom alignment: shift so this cluster's ideographic box
            // bottom meets the base 字身框 bottom (0 for base font/size).
            // ExplicitBaselineShiftSpan then stacks author intent (sup/subscript)
            // on top of that metric alignment; Roman clusters still keep metric
            // shift 0 but may receive the explicit style shift.
            val m = metricDecisionByRange[c.range]?.layoutMetrics ?: return@map c
            val metricShift = if (m.baselineClass == BaselineClass.Roman) 0f else baseBoxDescent - m.descent
            val shift = c.baselineShift + metricShift + styleAt(c.range.start).baselineShift
            if (shift > -0.01f && shift < 0.01f) c else c.copy(baselineShift = shift)
        }
        val geometryDecisions = finalGeometry.toDecisionInfo()

        // DashInkCentering: a 破折号 body is TWO EM by model (grid), but some
        // platform fonts draw their dash rule ≈1.6em of ink left-aligned in the
        // box (Pixel's Noto CJK — both its `⸺` and its `——` ligature share that
        // narrow rule). Centering the ink turns a one-sided ~0.35em hole into
        // symmetric side bearings. Only when the shaper reported ink bounds.
        fun List<Glyph>.centerDashInk(cluster: Cluster): List<Glyph> {
            if (atomClassByRange[cluster.range] != PunctuationClass.Dash) return this
            val glyph = singleOrNull() ?: return this
            val ink = glyph.bounds ?: return this
            val inset = (cluster.advance - (ink.right - ink.left)) / 2f - ink.left
            if (inset <= 0.5f) return this
            return listOf(glyph.copy(x = glyph.x + inset))
        }
        val glyphRuns = finalClusters
            .renderableGlyphRunClusters(openTypeFeaturesByClusterRange)
            .map { runClusters ->
                val openTypeFeatures = openTypeFeaturesByClusterRange[runClusters.first().range].orEmpty()
                GlyphRun(
                    range = TextRange(runClusters.first().range.start, runClusters.last().range.end),
                    fontKey = runClusters.first().fontKey,
                    glyphs = runClusters.flatMapIndexed { fallbackGlyphId, cluster ->
                        shapedGlyphsByClusterRange[cluster.range]
                            ?.mapToClusterRange(cluster)
                            ?.centerDashInk(cluster)
                            ?: listOf(
                                Glyph(
                                    id = fallbackGlyphId.toUInt(),
                                    clusterRange = cluster.range,
                                    advance = cluster.advance,
                                ),
                            )
                    },
                    advance = runClusters.sumOf { it.advance.toDouble() }.toFloat(),
                    openTypeFeatures = openTypeFeatures,
                )
            }

        val verticalGeometry = resolveLineVerticalGeometry(
            input = input,
            fontSize = fontSize,
            pinyinSpans = pinyinSpans,
            naturalClusters = naturalClusters,
            lineSolution = lineSolution,
            rubyFontGeometryBySpan = rubyFontGeometryBySpan,
            existingInterlineSpace = existingInterlineSpace,
            baseLineMetrics = baseLineMetrics,
            baseFaceHeight = baseFaceHeight,
            rubyExtent = rubyExtent,
            inlineObjectByClusterIndex = inlineObjectByClusterIndex,
            baseAscent = baseAscent,
            baseDescent = baseDescent,
        )
        val rubyLineHeightDecision = verticalGeometry.rubyLineHeightDecision
        val inlineObjectLineHeightDecision = verticalGeometry.inlineObjectLineHeightDecision
        val lineBaseline = verticalGeometry.lineBaseline
        val lineTop = verticalGeometry.lineTop
        val lineBottom = verticalGeometry.lineBottom

        val lineBoxes = buildLineBoxes(
            input = input,
            lineSolution = lineSolution,
            trimmedClusters = trimmedClusters,
            finalClusters = finalClusters,
            firstLineIndent = firstLineIndent,
            blockIndent = blockIndent,
            measure = measure,
            gridBodyOffset = gridBodyOffset,
            lineBaseline = lineBaseline,
            lineTop = lineTop,
            lineBottom = lineBottom,
            lineHyphenAdvanceAt = ::lineHyphenAdvanceAt,
            hyphenGlyphs = hyphenGlyphs,
            justificationPlans = justificationPlans,
        )
        val laidOutLines = lineBoxes.laidOutLines
        val lines = lineBoxes.visibleLines
        val maxLinesDecision = lineBoxes.maxLinesDecision
        val visibleLineRanges = lineBoxes.visibleLineRanges
        val annotationGeometry = resolveAnnotationGeometry(
            input = input,
            fontSize = fontSize,
            inlineObjectByClusterIndex = inlineObjectByClusterIndex,
            lineSolution = lineSolution,
            clreqProfile = clreqProfile,
            geometryDecisions = geometryDecisions,
            autoSpaceDecisions = autoSpaceDecisions,
            visibleLineRanges = visibleLineRanges,
            lines = lines,
            finalClusters = finalClusters,
            clusterRoles = clusterRoles,
            justifyDeltaByCluster = justifyDeltaByCluster,
            rubyAndBopomofoSpread = rubyAndBopomofoSpread,
            metricDecisions = metricDecisions,
            pinyinSpans = pinyinSpans,
            naturalClusters = naturalClusters,
            rubyFontGeometryBySpan = rubyFontGeometryBySpan,
            rubyStackGap = rubyStackGap,
            baseAscent = baseAscent,
            rubyFontSize = rubyFontSize,
            rubyFontWeight = rubyFontWeight,
            baseDescent = baseDescent,
            bopomofoFontWeightAt = ::bopomofoFontWeightAt,
        )
        val inlineObjectDecisions = annotationGeometry.inlineObjectDecisions
        val decorationDecisions = annotationGeometry.decorationDecisions
        val decorationSegments = annotationGeometry.decorationSegments
        val rubyDecisions = annotationGeometry.rubyDecisions
        val bopomofoDecisions = annotationGeometry.bopomofoDecisions

        val widestLine = lines.maxOfOrNull { it.indent + it.visualWidth + it.hyphenAdvance } ?: 0f
        val totalHeight = lines.lastOrNull()?.bottom ?: if (text.isEmpty()) 0f else baseLineMetrics.height
        val resultWidth = widestLine.coerceAtMost(input.constraints.maxWidth)

        return LayoutResult(
            input = input,
            size = Size(
                width = resultWidth,
                height = totalHeight,
            ),
            clusters = finalClusters,
            glyphRuns = glyphRuns,
            lines = lines,
            debug = buildLayoutDebugInfo(
                LayoutDebugStageInput(
                    text = text,
                    fontDecisions = fontDecisions,
                    punctuationGlyphSubstitutor = punctuationGlyphSubstitutor,
                    substitutionRollbacks = substitutionRollbacks,
                    shapingDecisions = shapingDecisions,
                    metricDecisions = metricDecisions,
                    punctuationAtoms = punctuationAtoms,
                    geometryDecisions = geometryDecisions,
                    spacingPlan = spacingPlan,
                    attachedPunctuationBoundary = attachedPunctuationBoundary,
                    roleOverrideInfos = roleOverrideInfos,
                    laidOutLines = laidOutLines,
                    lineSolution = lineSolution,
                    clusters = clusters,
                    justificationPlans = justificationPlans,
                    autoSpaceDecisions = autoSpaceDecisions,
                    edgeTrimDecisions = edgeTrimDecisions,
                    decorationDecisions = decorationDecisions,
                    decorationSegments = decorationSegments,
                    rubyDecisions = rubyDecisions,
                    bopomofoDecisions = bopomofoDecisions,
                    mandatoryBreakDecisions = mandatoryBreakDecisions,
                    maxLinesDecision = maxLinesDecision,
                    lineSpacingDecision = lineSpacingDecision,
                    rubyLineHeightDecision = rubyLineHeightDecision,
                    inlineObjectLineHeightDecision = inlineObjectLineHeightDecision,
                    kinsokuDecision = kinsokuDecision,
                    contextualKinsokuDecisions = contextualKinsokuDecisions,
                    lineLengthGridDecision = lineLengthGridDecision,
                    firstLineIndentDecision = firstLineIndentDecision,
                    inlineBoxDecisions = inlineBoxResult.decisions,
                    inlineObjectDecisions = inlineObjectDecisions,
                    inlineObjectPunctuationAttachmentDecisions = inlineObjectPunctuationAttachmentDecisions,
                    zeroWidthBreakDecisions = zeroWidthBreakDecisions,
                    breakOpportunityDecisions = breakOpportunityDecisions,
                    emergencyTrackingEligibilityDecisions = emergencyTrackingEligibilityDecisions,
                    progressiveBreakOpportunities = progressiveBreakOpportunities,
                ),
            ),
        )
    }

    private fun TextRange.isInside(other: TextRange): Boolean =
        start >= other.start && end <= other.end
}

private const val CJK_FACE_ASCENT_FALLBACK_EM = 0.88f

internal const val CJK_FACE_DESCENT_FALLBACK_EM = 0.12f

/**
 * `CjkBodyLineHeightDefault`: 中文正文默认行高 1.5em(行距约 0.5em),无显式
 * [ParagraphStyle.lineHeight] 时生效。1.0em 实贴会让真墨迹(ascent≈0.94em)与
 * 相邻行碰头,且正文常规需要行距呼吸。CLREQ 的标点 floor 是「有行间标点时的
 * 下限」,与本默认取 max:单面装 0.5em floor 正好被本默认吸收,双面装 0.625em
 * 仍可顶高。显式 lineHeight 可向下覆盖本默认(仍不低于不重叠下限)。
 */
private const val DEFAULT_BODY_LINE_HEIGHT_EM = 1.5f

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

/**
 * 连字作为最后一档（ADR 0029 amendment）：整词换行后，若填满版心需要给每个汉字
 * 间距加超过此值（半个字宽）才回头连字；以下则宁可拉伸汉字间距、不连字。
 */
private const val HYPHEN_LAST_RESORT_CJK_STRETCH_EM = 0.5f

/** 中西间距可拉伸余量（justify CjkLatinSpace cap 0.5em − 自然 0.25em），算松紧时先扣它. */
private const val HYPHEN_SINO_WESTERN_STRETCH_CAP_EM = 0.25f

/**
 * A retained clean technical break may not create tracking. Both the break-tier estimate and the
 * real post-justification check use zero; a rejected clean tier is replayed as Emergency so the
 * terminal technical span, rather than CJK body or an unrelated opaque token, absorbs the residual.
 */
private const val CURRENT_LINE_TECHNICAL_BODY_STRETCH_LIMIT_EM = 0f

/** Float tolerance for `CurrentLineTechnicalTierRejection` threshold comparisons. */
private const val TECHNICAL_STRETCH_EPSILON_PX = 0.001f

/** CLREQ 挤压第②档：西文词距最小压至四分之一汉字宽. */
private const val WORD_SPACE_MIN_EM = 0.25f

/** CLREQ 挤压⑥：行内中西间距「最小挤为八分之一汉字宽」. */
private const val SINO_WESTERN_GAP_MIN_EM = 0.125f

/** CLREQ 行尾悬挂适配标点：顿号、逗号、句号. */
internal val HANGABLE_PUNCTUATION = setOf('、', '，', '。')

/** CLREQ 挤压第④档对象：「位于行内的句号、问号、感叹号」. */
private val INLINE_STOPS = setOf('。', '！', '？', '．')
