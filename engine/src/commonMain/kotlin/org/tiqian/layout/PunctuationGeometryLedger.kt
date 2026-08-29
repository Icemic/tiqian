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

internal data class PunctuationGeometryLedger(
    private val naturalClusters: List<Cluster>,
    private val geometries: Map<Int, PunctuationClusterGeometry>,
    private val budgets: Map<Int, GlueBudget>,
    private val justificationDeltaByCluster: Map<Int, Float> = emptyMap(),
    /**
     * Raw advance reductions that are NOT punctuation glue — currently only
     * `TextAutoSpaceLineEdgeTrim` (the autospace replacement gap baked into a
     * Latin cluster's advance, removed again when the boundary lands on a
     * line edge). Applied unconditionally in [resolvedAdvance].
     */
    private val rawEdgeTrimByCluster: Map<Int, Float> = emptyMap(),
    /**
     * 行间注 避让 (ADR 0032): trailing advance ADDED to a base cluster so adjacent
     * 注文 keep ≥ one 注文 word-space between them (CLREQ §罗马拼音). STRUCTURAL —
     * applied unconditionally, BEFORE breaking, and survives the chain (so the
     * breaker + final render both see it). Distinct from justify deltas (those
     * are post-break and get replaced).
     */
    private val rubySpreadByCluster: Map<Int, Float> = emptyMap(),
    /** Structural inline box edges are never punctuation compression budget. */
    private val inlineBoxAdvanceByCluster: Map<Int, Float> = emptyMap(),
    /** Virtual prose-boundary glue physically owned by an attached run's trailing edge. */
    private val attachedInlineTrailingGlueByCluster: Map<Int, Float> = emptyMap(),
) {
    companion object {
        fun from(
            naturalClusters: List<Cluster>,
            punctuationAtoms: List<PunctuationAtom>,
            spacingPlan: PunctuationSpacingCompressionResult,
        ): PunctuationGeometryLedger {
            val geometries = buildPunctuationClusterGeometries(
                naturalClusters = naturalClusters,
                punctuationAtoms = punctuationAtoms,
            )
            val budgets = geometries.mapValues { (_, geometry) ->
                GlueBudget(
                    leadingNatural = geometry.leadingGlueNatural,
                    leadingConsumed = geometry.leadingGlueInitiallyConsumed,
                    trailingNatural = geometry.trailingGlueNatural,
                    trailingConsumed = geometry.trailingGlueInitiallyConsumed,
                )
            }
            return PunctuationGeometryLedger(
                naturalClusters = naturalClusters,
                geometries = geometries,
                budgets = budgets,
            ).consumeSpacing(spacingPlan)
        }

        private fun buildPunctuationClusterGeometries(
            naturalClusters: List<Cluster>,
            punctuationAtoms: List<PunctuationAtom>,
        ): Map<Int, PunctuationClusterGeometry> {
            if (punctuationAtoms.isEmpty()) return emptyMap()

            return naturalClusters.mapIndexedNotNull { index, cluster ->
                val atomsForCluster = punctuationAtoms.filter { it.range.isInside(cluster.range) }
                if (atomsForCluster.isEmpty()) return@mapIndexedNotNull null
                index to PunctuationClusterGeometry(
                    range = cluster.range,
                    sourceText = cluster.text,
                    displayText = cluster.displayText,
                    baseAdvance = cluster.advance,
                    bodyWidth = atomsForCluster.sumOf { it.bodyWidth.toDouble() }.toFloat(),
                    leadingGlueNatural = atomsForCluster.first().leadingGlue.natural,
                    trailingGlueNatural = atomsForCluster.last().trailingGlue.natural,
                    leadingGlueInitiallyConsumed = atomsForCluster.first().leadingGlueInitiallyConsumed,
                    trailingGlueInitiallyConsumed = atomsForCluster.last().trailingGlueInitiallyConsumed,
                    glyphInlineShift = atomsForCluster.singleOrNull()?.glyphInlineShift ?: 0f,
                    glyphPlacementReason = atomsForCluster.singleOrNull()?.glyphPlacementReason,
                    anchor = atomsForCluster.singleOrNull()?.anchor,
                    reason = atomsForCluster.first().geometrySource,
                )
            }.toMap()
        }
    }

    fun resolveClusters(): List<Cluster> =
        naturalClusters.mapIndexed { index, cluster ->
            val resolved = resolvedAdvance(index, cluster)
            val glyphInlineShift = geometries[index]?.glyphInlineShift ?: 0f
            if (resolved == cluster.advance && glyphInlineShift == 0f) {
                cluster
            } else {
                cluster.copy(
                    advance = resolved,
                    glyphInlineShift = cluster.glyphInlineShift + glyphInlineShift,
                )
            }
        }

    fun withInlineBoxAdvances(advanceByCluster: Map<Int, Float>): PunctuationGeometryLedger =
        if (advanceByCluster.isEmpty()) this else copy(inlineBoxAdvanceByCluster = advanceByCluster)

    fun consumeTrailingByCluster(consumptionByCluster: Map<Int, Float>): PunctuationGeometryLedger =
        copy(
            budgets = budgets.consume(consumptionByCluster) { budget, amount ->
                budget.copy(
                    trailingConsumed = (budget.trailingConsumed + amount)
                        .coerceAtMost(budget.trailingNatural),
                )
            },
        )

    fun consumeLeadingByCluster(consumptionByCluster: Map<Int, Float>): PunctuationGeometryLedger =
        copy(
            budgets = budgets.consume(consumptionByCluster) { budget, amount ->
                budget.copy(
                    leadingConsumed = (budget.leadingConsumed + amount)
                        .coerceAtMost(budget.leadingNatural),
                )
            },
        )

    /** Remaining leading/trailing glue per punctuation cluster index. */
    fun glueCapacities(): Map<Int, GlueCapacity> =
        budgets.mapNotNull { (index, budget) ->
            val leading = budget.leadingRemaining
            val trailing = budget.trailingRemaining
            if (leading > 0f || trailing > 0f) {
                index to GlueCapacity(
                    leading = leading,
                    trailing = trailing,
                    paired = geometries[index]?.anchor == PunctuationAnchor.Center,
                )
            } else {
                null
            }
        }.toMap()

    fun addJustificationDeltas(deltaByCluster: Map<Int, Float>): PunctuationGeometryLedger =
        copy(justificationDeltaByCluster = deltaByCluster)

    /** 行间注 避让 structural spread (ADR 0032) — applied before breaking, kept through the chain. */
    fun withRubySpread(spreadByCluster: Map<Int, Float>): PunctuationGeometryLedger =
        if (spreadByCluster.isEmpty()) this else copy(rubySpreadByCluster = spreadByCluster)

    fun withRawEdgeTrims(trimByCluster: Map<Int, Float>): PunctuationGeometryLedger =
        if (trimByCluster.isEmpty()) {
            this
        } else {
            copy(
                rawEdgeTrimByCluster = HashMap(rawEdgeTrimByCluster).apply {
                    trimByCluster.forEach { (index, amount) -> mergeValue(index, amount) { a, b -> a + b } }
                },
            )
        }

    /**
     * `AttachedInlineVirtualPunctuationBoundary`: ignore the attached run while
     * deciding punctuation spacing. Both sides are recomputed as if the prose
     * clusters were adjacent; this is not a transfer of the left-side glue.
     *
     * The right punctuation keeps as much of its own leading glue as the virtual
     * boundary needs. Any remainder is owned by the attached run's trailing edge.
     * At paragraph end the virtual boundary has zero width.
     */
    fun resolveAttachedInlinePunctuationBoundaries(
        inlineAttachments: List<InlineAttachment>,
        punctuationAtoms: List<PunctuationAtom>,
        em: Float,
    ): AttachedInlinePunctuationBoundaryResult {
        require(inlineAttachments.size == naturalClusters.size) {
            "Inline attachments must align with punctuation geometry clusters."
        }
        if (budgets.isEmpty() || inlineAttachments.none { it == InlineAttachment.Previous }) {
            return AttachedInlinePunctuationBoundaryResult(this, emptyMap(), emptyList())
        }

        val updatedBudgets = budgets.toMutableMap()
        val trailingGlue = mutableMapOf<Int, Float>()
        val decisions = mutableListOf<SpacingDecisionInfo>()
        resolveAttachedInlineVirtualBoundaries(inlineAttachments).forEach { boundary ->
            val previousIndex = boundary.previousClusterIndex
            val end = boundary.attachedClusterRange.last
            val previousBudget = updatedBudgets[previousIndex]
            val leftTrailing = previousBudget?.trailingRemaining ?: 0f
            val nextIndex = boundary.nextClusterIndex?.takeUnless {
                naturalClusters[it].fontKey == MANDATORY_BREAK_FONT_KEY &&
                    naturalClusters[it].displayText.isEmpty()
            }
            val nextBudget = nextIndex?.let(updatedBudgets::get)
            val rightLeading = nextBudget?.leadingRemaining ?: 0f
            val leftAtom = punctuationAtoms.lastOrNull { atom ->
                atom.range.isInside(naturalClusters[previousIndex].range)
            }
            val rightAtom = nextIndex?.let { index ->
                punctuationAtoms.firstOrNull { atom -> atom.range.isInside(naturalClusters[index].range) }
            }
            val nextChar = nextIndex?.let { naturalClusters[it].text.firstOrNull() }
            val naturalVirtualGlue = leftTrailing + rightLeading
            val adjustedVirtualGlue = when {
                nextIndex == null -> 0f
                leftAtom != null && rightAtom != null ->
                    (naturalVirtualGlue - em / 2f).coerceAtLeast(0f)

                leftAtom?.punctuationClass == PunctuationClass.Closing &&
                    nextChar?.let(ClreqPunctuationPolicies::isAsciiPointMark) == true ->
                    (naturalVirtualGlue - em / 2f).coerceAtLeast(0f)

                else -> naturalVirtualGlue
            }

            if (previousBudget != null && leftTrailing > 0f) {
                updatedBudgets[previousIndex] = previousBudget.copy(
                    trailingConsumed = previousBudget.trailingNatural,
                )
            }
            val keptRightLeading = minOf(rightLeading, adjustedVirtualGlue)
            if (nextIndex != null && nextBudget != null && keptRightLeading < rightLeading) {
                updatedBudgets[nextIndex] = nextBudget.copy(
                    leadingConsumed = nextBudget.leadingNatural - keptRightLeading,
                )
            }
            val targetGlue = (adjustedVirtualGlue - keptRightLeading).coerceAtLeast(0f)
            if (targetGlue > 0f) trailingGlue[end] = targetGlue

            if (leftTrailing > 0f || rightLeading != adjustedVirtualGlue) {
                val previous = naturalClusters[previousIndex]
                val next = nextIndex?.let(naturalClusters::get)
                decisions += SpacingDecisionInfo(
                    range = TextRange(previous.range.start, next?.range?.end ?: naturalClusters[end].range.end),
                    leftChar = previous.text.lastOrNull() ?: '\u0000',
                    rightChar = next?.text?.firstOrNull() ?: '\u0000',
                    naturalInnerGlue = naturalVirtualGlue,
                    adjustedInnerGlue = adjustedVirtualGlue,
                    reduction = naturalVirtualGlue - adjustedVirtualGlue,
                    reductionTargetRange = previous.range,
                    reason = when {
                        nextIndex == null -> "AttachedInlineVirtualPunctuationBoundary:line-end"
                        leftAtom != null && rightAtom != null ->
                            "AttachedInlineVirtualPunctuationBoundary:adjacent-punctuation"
                        leftAtom?.punctuationClass == PunctuationClass.Closing &&
                            nextChar?.let(ClreqPunctuationPolicies::isAsciiPointMark) == true ->
                            "AttachedInlineVirtualPunctuationBoundary:ascii-point-mark"
                        else -> "AttachedInlineVirtualPunctuationBoundary:natural"
                    },
                )
            }
        }

        val geometry = copy(
            budgets = updatedBudgets,
            attachedInlineTrailingGlueByCluster = HashMap(attachedInlineTrailingGlueByCluster).apply {
                trailingGlue.forEach { (cluster, amount) ->
                    mergeValue(cluster, amount) { a, b -> maxOf(a, b) }
                }
            },
        )
        return AttachedInlinePunctuationBoundaryResult(geometry, trailingGlue, decisions)
    }

    fun consumeLineEdgeGlue(
        lines: List<LineCandidate>,
        forceLineEndHalfWidth: Boolean = true,
    ): LineEdgeTrimResult {
        if (lines.isEmpty() || budgets.isEmpty()) {
            return LineEdgeTrimResult(this, emptyList())
        }

        val decisions = mutableListOf<LineEdgeTrimDecisionInfo>()
        val leadingConsumptionByCluster = HashMap<Int, Float>()
        val trailingConsumptionByCluster = HashMap<Int, Float>()

        fun consumeAtEdge(
            line: LineCandidate,
            clusterIndex: Int,
            edge: PunctuationLineEdge,
        ) {
            val budget = budgets[clusterIndex] ?: return
            // A one-cluster line reaches this helper twice. Subtract the first
            // edge's scheduled amount so a centred frame is consumed once.
            val leadingRemaining = (
                budget.leadingRemaining -
                    (leadingConsumptionByCluster[clusterIndex] ?: 0f)
                ).coerceAtLeast(0f)
            val trailingRemaining = (
                budget.trailingRemaining -
                    (trailingConsumptionByCluster[clusterIndex] ?: 0f)
                ).coerceAtLeast(0f)
            val paired = geometries[clusterIndex]?.anchor == PunctuationAnchor.Center
            val pairedPerSide = if (paired) minOf(leadingRemaining, trailingRemaining) else 0f
            val leadingAmount = when {
                paired -> pairedPerSide
                edge == PunctuationLineEdge.Start -> leadingRemaining
                else -> 0f
            }
            val trailingAmount = when {
                paired -> pairedPerSide
                edge == PunctuationLineEdge.End -> trailingRemaining
                else -> 0f
            }
            val total = leadingAmount + trailingAmount
            if (total <= 0f) return

            if (leadingAmount > 0f) {
                leadingConsumptionByCluster.mergeValue(clusterIndex, leadingAmount) { a, b -> a + b }
            }
            if (trailingAmount > 0f) {
                trailingConsumptionByCluster.mergeValue(clusterIndex, trailingAmount) { a, b -> a + b }
            }
            decisions += LineEdgeTrimDecisionInfo(
                lineRange = line.sourceRange,
                clusterRange = naturalClusters[clusterIndex].range,
                side = if (paired) "both" else edge.side,
                trimAmount = total,
                consumedBefore = if (paired) {
                    budget.leadingConsumed + budget.trailingConsumed
                } else if (edge == PunctuationLineEdge.Start) {
                    budget.leadingConsumed
                } else {
                    budget.trailingConsumed
                },
                naturalGlue = if (paired) {
                    budget.leadingNatural + budget.trailingNatural
                } else if (edge == PunctuationLineEdge.Start) {
                    budget.leadingNatural
                } else {
                    budget.trailingNatural
                },
                reason = if (paired) {
                    "Line${edge.reasonPart}CenteredPunctuationPairedCompression"
                } else {
                    "Line${edge.reasonPart}HalfWidthPunctuation"
                },
            )
        }

        lines.forEach { line ->
            if (line.clusterRange.isEmptyClusterRange()) return@forEach
            // 宽松风格 (AllowFullWidth): the unconditional line-end half-width
            // trim is skipped; the blank was only available as on-demand
            // shrink capacity during PushIn.
            if (forceLineEndHalfWidth) {
                consumeAtEdge(line, line.clusterRange.last, PunctuationLineEdge.End)
            }
            consumeAtEdge(line, line.clusterRange.first, PunctuationLineEdge.Start)
        }

        val updated = copy(
            budgets = budgets
                .consume(leadingConsumptionByCluster) { budget, amount ->
                    budget.copy(
                        leadingConsumed = (budget.leadingConsumed + amount)
                            .coerceAtMost(budget.leadingNatural),
                    )
                }
                .consume(trailingConsumptionByCluster) { budget, amount ->
                    budget.copy(
                        trailingConsumed = (budget.trailingConsumed + amount)
                            .coerceAtMost(budget.trailingNatural),
                    )
                },
        )
        return LineEdgeTrimResult(updated, decisions)
    }

    fun toDecisionInfo(): List<ClusterGeometryDecisionInfo> =
        geometries.map { (index, geometry) ->
            val budget = budgets.getValue(index)
            val delta = justificationDeltaByCluster[index] ?: 0f
            ClusterGeometryDecisionInfo(
                range = geometry.range,
                sourceText = geometry.sourceText,
                displayText = geometry.displayText,
                baseAdvance = geometry.baseAdvance,
                bodyWidth = geometry.bodyWidth,
                leadingGlueNatural = budget.leadingNatural,
                leadingGlueConsumed = budget.leadingConsumed,
                trailingGlueNatural = budget.trailingNatural,
                trailingGlueConsumed = budget.trailingConsumed,
                justificationDelta = delta,
                rubySpread = rubySpreadByCluster[index] ?: 0f,
                glyphInlineShift = geometry.glyphInlineShift,
                glyphPlacementReason = geometry.glyphPlacementReason,
                resolvedAdvance = resolvedAdvance(index, naturalClusters[index]),
                source = "PunctuationGeometryLedger",
                reason = geometry.reason,
            )
        }

    private fun consumeSpacing(
        spacingPlan: PunctuationSpacingCompressionResult,
    ): PunctuationGeometryLedger =
        copy(
            budgets = budgets.consumeByRange(
                clusters = naturalClusters,
                geometries = geometries,
                adjustments = spacingPlan.adjustments,
            ),
        )

    private fun resolvedAdvance(index: Int, cluster: Cluster): Float {
        val rawTrim = rawEdgeTrimByCluster[index] ?: 0f
        val spread = rubySpreadByCluster[index] ?: 0f
        val geometry = geometries[index] ?: run {
            val delta = justificationDeltaByCluster[index] ?: 0f
            val attachedGlue = attachedInlineTrailingGlueByCluster[index] ?: 0f
            return (cluster.advance + delta + spread + attachedGlue - rawTrim).coerceAtLeast(0f)
        }
        val inlineBoxAdvance = inlineBoxAdvanceByCluster[index] ?: 0f
        val budget = budgets[index]
            ?: return (
                geometry.bodyWidth + inlineBoxAdvance +
                    (justificationDeltaByCluster[index] ?: 0f) + spread - rawTrim
                ).coerceAtLeast(0f)
        val delta = justificationDeltaByCluster[index] ?: 0f
        val attachedGlue = attachedInlineTrailingGlueByCluster[index] ?: 0f
        return (
            geometry.bodyWidth +
                budget.leadingRemaining +
                budget.trailingRemaining +
                delta +
                spread -
                rawTrim +
                inlineBoxAdvance +
                attachedGlue
            ).coerceAtLeast(0f)
    }
}

internal data class AttachedInlinePunctuationBoundaryResult(
    val geometry: PunctuationGeometryLedger,
    val trailingGlueByCluster: Map<Int, Float>,
    val decisions: List<SpacingDecisionInfo>,
)

internal data class PunctuationClusterGeometry(
    val range: TextRange,
    val sourceText: String,
    val displayText: String,
    val baseAdvance: Float,
    val bodyWidth: Float,
    val leadingGlueNatural: Float,
    val trailingGlueNatural: Float,
    val leadingGlueInitiallyConsumed: Float,
    val trailingGlueInitiallyConsumed: Float,
    val glyphInlineShift: Float,
    val glyphPlacementReason: String?,
    val anchor: PunctuationAnchor?,
    val reason: String,
)

internal data class GlueBudget(
    val leadingNatural: Float,
    val leadingConsumed: Float,
    val trailingNatural: Float,
    val trailingConsumed: Float,
) {
    val leadingRemaining: Float get() = (leadingNatural - leadingConsumed).coerceAtLeast(0f)
    val trailingRemaining: Float get() = (trailingNatural - trailingConsumed).coerceAtLeast(0f)
}

internal data class LineEdgeTrimResult(
    val geometry: PunctuationGeometryLedger,
    val decisions: List<LineEdgeTrimDecisionInfo>,
)

private enum class PunctuationLineEdge(
    val side: String,
    val reasonPart: String,
) {
    Start(side = "leading", reasonPart = "Start"),
    End(side = "trailing", reasonPart = "End"),
}

/** Remaining glue per side, input to the tiered shrink model (ADR 0020). */
internal data class GlueCapacity(
    val leading: Float,
    val trailing: Float,
    /** True when resolved punctuation geometry selected a centred body frame. */
    val paired: Boolean,
)

/**
 * Contiguous cluster-index range whose clusters are fully covered by
 * [sourceRange]; null when no cluster is covered.
 *
 * Clusters are source ordered and non-overlapping (the shaping stage emits them in source
 * order and nothing re-sorts them), so the covered set is one contiguous run; both edges
 * binary-search instead of scanning the paragraph (a per-range full scan made planning
 * quadratic on pathological long tokens). A source that violates the ordering would return
 * a wrong range silently — new cluster producers must preserve it.
 */
internal fun List<Cluster>.clusterIndexRangeFor(sourceRange: TextRange): IntRange? {
    if (isEmpty()) return null
    var low = 0
    var high = size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (this[mid].range.start < sourceRange.start) low = mid + 1 else high = mid
    }
    val first = low
    low = first
    high = size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (this[mid].range.end <= sourceRange.end) low = mid + 1 else high = mid
    }
    val lastExclusive = low
    return if (first < lastExclusive) first until lastExclusive else null
}

private fun Map<Int, GlueBudget>.consume(
    consumptionByCluster: Map<Int, Float>,
    apply: (GlueBudget, Float) -> GlueBudget,
): Map<Int, GlueBudget> {
    if (consumptionByCluster.isEmpty()) return this

    return toMutableMap().also { updated ->
        consumptionByCluster.forEach { (index, amount) ->
            if (amount <= 0f) return@forEach
            updated[index]?.let { budget -> updated[index] = apply(budget, amount) }
        }
    }
}

private fun Map<Int, GlueBudget>.consumeByRange(
    clusters: List<Cluster>,
    geometries: Map<Int, PunctuationClusterGeometry>,
    adjustments: List<PunctuationSpacingAdjustment>,
): Map<Int, GlueBudget> {
    if (adjustments.isEmpty()) return this

    return toMutableMap().also { updated ->
        adjustments.forEach { adjustment ->
            val targetIdx = clusters.indexOfFirst { adjustment.reductionTargetRange.isInside(it.range) }
            if (targetIdx < 0) return@forEach
            updated[targetIdx]?.let { current ->
                val leadingRemaining = current.leadingRemaining
                val trailingRemaining = current.trailingRemaining
                if (
                    geometries[targetIdx]?.anchor == PunctuationAnchor.Center
                ) {
                    val perSide = minOf(
                        adjustment.reduction / 2f,
                        leadingRemaining,
                        trailingRemaining,
                    )
                    updated[targetIdx] = current.copy(
                        leadingConsumed = current.leadingConsumed + perSide,
                        trailingConsumed = current.trailingConsumed + perSide,
                    )
                    return@let
                }
                // Consume reduction from whichever side has remaining capacity.
                // With single-sided font geometry or the profile fallback, all
                // glue may be on one side (e.g. PauseOrStop → trailing only).
                updated[targetIdx] = if (trailingRemaining >= leadingRemaining) {
                    current.copy(
                        trailingConsumed = (current.trailingConsumed + adjustment.reduction)
                            .coerceAtMost(current.trailingNatural),
                    )
                } else {
                    current.copy(
                        leadingConsumed = (current.leadingConsumed + adjustment.reduction)
                            .coerceAtMost(current.leadingNatural),
                    )
                }
            }
        }
    }
}

internal fun TextRange.isInside(other: TextRange): Boolean =
    start >= other.start && end <= other.end

/**
 * Common-stdlib port of `java.util.Map.merge` (absent from the JS common
 * stdlib and only present on Android from API 24): absent key → [value];
 * present → `remap(old, value)`. The distinct name prevents the JVM member
 * from winning overload resolution in API 23 artifacts.
 */
internal fun <K, V : Any> MutableMap<K, V>.mergeValue(key: K, value: V, remap: (V, V) -> V) {
    this[key] = this[key]?.let { remap(it, value) } ?: value
}
