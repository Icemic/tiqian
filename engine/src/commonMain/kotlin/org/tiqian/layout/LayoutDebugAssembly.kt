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

internal class LayoutDebugStageInput(
    val text: String,
    val fontDecisions: List<FontDecision>,
    val punctuationGlyphSubstitutor: ClreqPunctuationGlyphSubstitutor,
    val substitutionRollbacks: Map<TextRange, String>,
    val shapingDecisions: List<ShapingDecisionInfo>,
    val metricDecisions: List<ClusterMetricDecision>,
    val punctuationAtoms: List<PunctuationAtom>,
    val geometryDecisions: List<ClusterGeometryDecisionInfo>,
    val spacingPlan: PunctuationSpacingCompressionResult,
    val attachedPunctuationBoundary: AttachedInlinePunctuationBoundaryResult,
    val roleOverrideInfos: List<RoleOverrideInfo>,
    val laidOutLines: List<LineBox>,
    val lineSolution: LineSolution,
    val clusters: List<Cluster>,
    val justificationPlans: List<JustificationPlan?>,
    val autoSpaceDecisions: List<AutoSpaceDecisionInfo>,
    val edgeTrimDecisions: List<LineEdgeTrimDecisionInfo>,
    val decorationDecisions: List<DecorationDecisionInfo>,
    val decorationSegments: List<DecorationSegmentInfo>,
    val rubyDecisions: List<RubyDecisionInfo>,
    val bopomofoDecisions: List<BopomofoDecisionInfo>,
    val mandatoryBreakDecisions: List<MandatoryBreakDecisionInfo>,
    val maxLinesDecision: MaxLinesDecisionInfo?,
    val lineSpacingDecision: LineSpacingDecisionInfo?,
    val rubyLineHeightDecision: RubyLineHeightDecisionInfo?,
    val inlineObjectLineHeightDecision: InlineObjectLineHeightDecisionInfo?,
    val kinsokuDecision: KinsokuDecisionInfo,
    val contextualKinsokuDecisions: List<ContextualKinsokuDecisionInfo>,
    val lineLengthGridDecision: LineLengthGridDecisionInfo,
    val firstLineIndentDecision: FirstLineIndentDecisionInfo,
    val inlineBoxDecisions: List<InlineBoxDecisionInfo>,
    val inlineObjectDecisions: List<InlineObjectDecisionInfo>,
    val inlineObjectPunctuationAttachmentDecisions: List<InlineObjectPunctuationAttachmentDecisionInfo>,
    val zeroWidthBreakDecisions: List<ZeroWidthBreakDecisionInfo>,
    val breakOpportunityDecisions: List<BreakOpportunityDecisionInfo>,
    val emergencyTrackingEligibilityDecisions: List<EmergencyTrackingEligibilityDecisionInfo>,
    val progressiveBreakOpportunities: Map<Int, ProgressiveBreakOpportunity>,
)

/** Materializes the structured decision stream without owning layout policy. */
internal fun TiqianParagraphLayoutEngine.buildLayoutDebugInfo(stage: LayoutDebugStageInput): LayoutDebugInfo = with(stage) {
LayoutDebugInfo(
            fontDecisions = fontDecisions.map { decision ->
                val clusterText = text.substring(decision.range.start, decision.range.end)
                val substitution = punctuationGlyphSubstitutor.substitute(clusterText)
                val rollbackCause = substitutionRollbacks.entries.firstOrNull { it.key.isInside(decision.range) }?.value
                FontDecisionInfo(
                    range = decision.range,
                    sourceText = clusterText,
                    displayText = if (rollbackCause != null) clusterText else substitution.displayText,
                    role = decision.role.name,
                    fontKey = decision.candidate.key,
                    reason = decision.reason,
                    substitutionReason = if (rollbackCause != null) {
                        "${substitution.reason}:$rollbackCause"
                    } else {
                        substitution.reason
                    },
                )
            },
            shapingDecisions = shapingDecisions,
            metricDecisions = metricDecisions.map { decision ->
                MetricDecisionInfo(
                    range = decision.range,
                    sourceText = decision.sourceText,
                    role = decision.request.role.name,
                    fontKey = decision.request.fontKey,
                    rawAscent = decision.rawMetrics.ascent,
                    rawDescent = decision.rawMetrics.descent,
                    rawLeading = decision.rawMetrics.leading,
                    rawSource = decision.rawMetrics.source.name,
                    layoutAscent = decision.layoutMetrics.ascent,
                    layoutDescent = decision.layoutMetrics.descent,
                    baselineClass = decision.layoutMetrics.baselineClass.name,
                    metricBox = decision.layoutMetrics.metricBox.name,
                    layoutSource = decision.layoutMetrics.source.name,
                    reason = decision.layoutMetrics.reason,
                )
            },
            punctuationDecisions = punctuationAtoms.map { atom ->
                PunctuationDecisionInfo(
                    range = atom.range,
                    char = atom.char,
                    punctuationClass = atom.punctuationClass.name,
                    advance = atom.advance,
                    bodyWidth = atom.bodyWidth,
                    leadingGlueNatural = atom.leadingGlue.natural,
                    trailingGlueNatural = atom.trailingGlue.natural,
                    leadingGlueInitiallyConsumed = atom.leadingGlueInitiallyConsumed,
                    trailingGlueInitiallyConsumed = atom.trailingGlueInitiallyConsumed,
                    anchor = atom.anchor.name,
                    inkBounds = atom.inkBounds,
                    geometrySource = atom.geometrySource,
                    policyBodyFloor = atom.policyBodyFloor,
                    inkWidth = atom.inkWidth,
                    inkCenter = atom.inkCenter,
                    inkContainmentBodyFloor = atom.inkContainmentBodyFloor,
                    inkContainmentApplied = atom.inkContainmentApplied,
                    inkBoundsFallback = atom.inkBoundsFallback,
                    haltAdvance = atom.haltAdvance,
                    haltValidation = atom.haltValidation,
                    advanceExpansion = atom.advanceExpansion,
                    glyphInlineShift = atom.glyphInlineShift,
                    glyphPlacementReason = atom.glyphPlacementReason,
                )
            },
            geometryDecisions = geometryDecisions,
            spacingDecisions = spacingPlan.adjustments.map { adjustment ->
                SpacingDecisionInfo(
                    range = adjustment.range,
                    leftChar = adjustment.leftChar,
                    rightChar = adjustment.rightChar,
                    naturalInnerGlue = adjustment.naturalInnerGlue,
                    adjustedInnerGlue = adjustment.adjustedInnerGlue,
                    reduction = adjustment.reduction,
                    reductionTargetRange = adjustment.reductionTargetRange,
                    reason = adjustment.reason,
                )
            } + attachedPunctuationBoundary.decisions,
            roleOverrides = roleOverrideInfos,
            // Zip over ALL laid-out lines (not the maxLines-truncated boxes): the
            // dump records every committed line, the truncation names the cut.
            lineDecisions = laidOutLines.zip(lineSolution.lines).mapIndexed { lineIndex, (line, candidate) ->
                LineDecisionInfo(
                    range = line.range,
                    kind = lineBreaker.strategyName,
                    repair = candidate.repair?.let { "${it::class.simpleName}" },
                    repairPenalty = candidate.repair?.penalty ?: 0,
                    repairDecision = candidate.repair?.toDecisionInfo(clusters),
                    repairCandidates = candidate.repairCandidates.map { it.toDecisionInfo(clusters) },
                    notes = listOf(
                        "index:$lineIndex",
                        "end:${line.endReason}",
                        "natural:${line.naturalWidth}",
                        "adjusted:${line.adjustedWidth}",
                        "visual:${line.visualWidth}",
                    ) + listOfNotNull(
                        progressiveBreakOpportunities[candidate.clusterRange.last + 1]
                            ?.let { "technical-break:${it.tier.name}" },
                        candidate.repair?.let { "repair-reason:${it.reason}" },
                        justificationPlans.getOrNull(lineIndex)?.fallbackReason
                            ?.let { "justify-fallback:$it" },
                    ),
                )
            },
            justificationDecisions = justificationPlans.zip(lineSolution.lines)
                .mapNotNull { (plan, candidate) ->
                    plan
                        ?.takeIf { it.allocations.isNotEmpty() || it.deficitBefore > 0f }
                        ?.let {
                            JustificationDecisionInfo(
                                lineRange = candidate.sourceRange,
                                deficitBefore = it.deficitBefore,
                                deficitAfter = it.unfilledDeficit,
                                allocations = it.allocations.map { alloc ->
                                    JustificationAllocationInfo(
                                        clusterRange = clusters[alloc.targetClusterIndex].range,
                                        kind = alloc.kind.name,
                                        priority = alloc.priority,
                                        delta = alloc.delta,
                                        reason = alloc.reason,
                                    )
                                },
                            )
                        }
                },
            autoSpaceDecisions = autoSpaceDecisions,
            lineEdgeTrimDecisions = edgeTrimDecisions,
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
            inlineBoxDecisions = inlineBoxDecisions,
            inlineObjectDecisions = inlineObjectDecisions,
            inlineObjectPunctuationAttachmentDecisions = inlineObjectPunctuationAttachmentDecisions,
            zeroWidthBreakDecisions = zeroWidthBreakDecisions,
            breakOpportunityDecisions = breakOpportunityDecisions,
            emergencyTrackingEligibilityDecisions = emergencyTrackingEligibilityDecisions,
        )
}

internal fun List<QuoteRoleDecision>.toRoleOverrideInfos(
    text: String,
    baseClassifier: FontRoleClassifier,
    context: FontRoleContext,
): List<RoleOverrideInfo> =
    sortedBy { it.index }
        .map { decision ->
            val index = decision.index
            val sourceText = text.substring(index, (index + 1).coerceAtMost(text.length))
            val originalRole = baseClassifier
                .classify(text, TextRange(index, index + 1), context)
            RoleOverrideInfo(
                range = TextRange(index, index + 1),
                sourceText = sourceText,
                originalRole = originalRole.name,
                overriddenRole = decision.role.name,
                source = decision.source,
                reason = decision.reason,
            )
        }

private fun RepairCandidate.toDecisionInfo(clusters: List<Cluster>): LineRepairCandidateInfo =
    LineRepairCandidateInfo(
        kind = kind,
        reasonCode = reasonCode,
        offenderRange = clusters[offenderClusterIndex].range,
        penalty = penalty,
        accepted = accepted,
        rejectionReason = rejectionReason,
        targetClusterIndex = targetClusterIndex,
        carriedClusterIndex = carriedClusterIndex,
        shrink = shrink,
        requiredShrink = requiredShrink,
        availableCapacity = availableCapacity,
    )

private fun RepairOption.toDecisionInfo(clusters: List<Cluster>): LineRepairDecisionInfo =
    when (this) {
        is RepairOption.PushIn -> LineRepairDecisionInfo(
            // 避头尾 PushIn vs LineAdjustmentPushIn (ADR 0031) — the real
            // trigger lives in `reason`; don't hardcode it away.
            kind = "PushIn",
            reasonCode = reason.substringBefore(':'),
            offenderRange = clusters[offenderClusterIndex].range,
            penalty = penalty,
            targetClusterIndex = offenderClusterIndex,
            shrink = totalShrink,
            availableCapacity = totalAvailableCapacity,
            pushInAllocations = allocations.map { alloc ->
                LineRepairAllocationInfo(
                    clusterRange = clusters[alloc.clusterIndex].range,
                    shrink = alloc.shrink,
                    availableCapacity = alloc.availableCapacity,
                )
            },
        )

        is RepairOption.CarryPrevious -> LineRepairDecisionInfo(
            kind = "CarryPrevious",
            reasonCode = "ForbiddenAtLineStart",
            offenderRange = clusters[offenderClusterIndex].range,
            penalty = penalty,
            carriedClusterIndex = carriedClusterIndex,
        )

        is RepairOption.LeaveRagged -> LineRepairDecisionInfo(
            kind = "LeaveRagged",
            reasonCode = "ForbiddenAtLineStart",
            offenderRange = clusters[offenderClusterIndex].range,
            penalty = penalty,
        )

        is RepairOption.Hang -> LineRepairDecisionInfo(
            kind = "Hang",
            reasonCode = "ForbiddenAtLineStart",
            offenderRange = clusters[offenderClusterIndex].range,
            penalty = penalty,
        )

        is RepairOption.CarryNext -> LineRepairDecisionInfo(
            kind = "CarryNext",
            reasonCode = "ForbiddenAtLineEnd",
            offenderRange = clusters[movedClusterIndex].range,
            penalty = penalty,
            carriedClusterIndex = movedClusterIndex,
        )
    }
