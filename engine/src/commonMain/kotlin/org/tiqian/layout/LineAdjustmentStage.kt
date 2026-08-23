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

/**
 * Post-break finishing: PushIn consumption, line-edge trims, justification,
 * technical-tier rejection replay, final geometry, glyph runs, line boxes,
 * annotations and debug assembly. Pure move of the corresponding pipeline
 * segment out of [ExplainableStubParagraphLayoutEngine].
 */
internal fun ExplainableStubParagraphLayoutEngine.finishParagraphLayout(
    prep: ParagraphLayoutPrep,
    plan: LineBreakPlanningStageResult,
): LayoutResult = with(prep) {
    with(plan) {
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
            bopomofoFontWeightAt = bopomofoFontWeightAt,
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
}

/**
 * A retained clean technical break may not create tracking. Both the break-tier estimate and the
 * real post-justification check use zero; a rejected clean tier is replayed as Emergency so the
 * terminal technical span, rather than CJK body or an unrelated opaque token, absorbs the residual.
 */
private const val CURRENT_LINE_TECHNICAL_BODY_STRETCH_LIMIT_EM = 0f

/** Float tolerance for `CurrentLineTechnicalTierRejection` threshold comparisons. */
private const val TECHNICAL_STRETCH_EPSILON_PX = 0.001f
