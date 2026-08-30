package org.tiqian.layout

import org.tiqian.core.Cluster
import org.tiqian.core.LineEndReason
import org.tiqian.core.TextRange

internal fun applyKinsokuRepairs(
    initial: List<LineCandidate>,
    naturalClusters: List<Cluster>,
    adjustedClusters: List<Cluster>,
    maxWidth: Float,
    kinsoku: KinsokuRule,
    shrinkOpportunities: List<ShrinkOpportunity> = emptyList(),
    pushInPenalty: Int,
    carryPreviousPenalty: Int,
    leaveRaggedPenalty: Int,
    unbreakableRanges: UnbreakableRanges = UnbreakableRanges.Empty,
    firstLineIndent: Float = 0f,
    hangableClusters: Set<Int> = emptySet(),
    extendableHangRanges: List<IntRange> = emptyList(),
    hangPenalty: Int = 5,
    forbiddenLineStartClusters: Set<Int>? = null,
): LineSolution {
    if (initial.size < 2) return LineSolution(initial)

    val mutable = initial.toMutableList()
    var i = 1
    while (i < mutable.size) {
        val curr = mutable[i]
        val firstIndex = curr.clusterRange.first
        val prev = mutable[i - 1]
        if (prev.endReason == LineEndReason.MandatoryBreak || curr.clusterRange.isEmptyClusterRange()) {
            i += 1
            continue
        }
        val firstCluster = adjustedClusters[firstIndex]
        // KinsokuLevel: the engine resolves the forbidden set from the
        // profile level; standalone breaker use falls back to the rule.
        val forbidden = forbiddenLineStartClusters?.contains(firstIndex)
            ?: kinsoku.forbiddenAtLineStart(firstCluster)
        if (!forbidden) {
            i += 1
            continue
        }

        val repairCandidates = mutableListOf<RepairCandidate>()
        val pushIn = tryPushIn(
            prev = prev,
            curr = curr,
            naturalClusters = naturalClusters,
            adjustedClusters = adjustedClusters,
            // The merged line keeps prev's start — a first-line PushIn must
            // fit inside the indented measure.
            maxWidth = lineLimit(maxWidth, firstLineIndent, prev.clusterRange.first),
            shrinkOpportunities = shrinkOpportunities,
            pushInPenalty = pushInPenalty,
        )
        repairCandidates += pushIn.candidate
        if (pushIn.candidate.accepted) {
            mutable[i - 1] = pushIn.previous
            if (pushIn.current == null) {
                mutable.removeAt(i)
            } else {
                mutable[i] = pushIn.current
                continue
            }
            continue
        }

        // LineEndHangingPunctuation (CLREQ 行尾点号悬挂, ADR 0006): when
        // PushIn cannot fit the 顿/逗/句 offender, hang it past the measure
        // on the previous line instead of carrying a whole character down.
        // 普通 profile 仍只悬挂一个；具名的不可能宽度点号 run
        // 才可通过 extendableHangRanges 延伸已有 hang。
        val offenderIndex = curr.clusterRange.first
        val existingHanging = prev.hangingClusterIndices
        val extendsContextualHang =
            existingHanging.isNotEmpty() &&
                prev.clusterRange.last + 1 == offenderIndex &&
                extendableHangRanges.any { protectedGroup ->
                    offenderIndex in protectedGroup && existingHanging.all { it in protectedGroup }
                }
        if (
            offenderIndex in hangableClusters &&
            (existingHanging.isEmpty() || extendsContextualHang)
        ) {
            val mergeEndIndex = mandatoryBreakTailEnd(curr, offenderIndex, adjustedClusters)
            val hangCandidate = RepairCandidate(
                kind = "Hang",
                reasonCode = "ForbiddenAtLineStart",
                offenderClusterIndex = offenderIndex,
                penalty = hangPenalty,
                accepted = true,
            )
            repairCandidates += hangCandidate
            val mergedRange = prev.clusterRange.first..mergeEndIndex
            mutable[i - 1] = LineCandidate(
                clusterRange = mergedRange,
                sourceRange = TextRange(
                    adjustedClusters[mergedRange.first].range.start,
                    adjustedClusters[mergeEndIndex].range.end,
                ),
                // The hung mark sits BEYOND the measure: it is excluded from
                // the line's measure-fill width (content fills to maxWidth,
                // the mark overflows).
                naturalWidth = prev.naturalWidth +
                    (prev.clusterRange.last + 1..mergeEndIndex)
                        .sumOf { naturalClusters[it].advance.toDouble() }
                        .toFloat(),
                adjustedWidth = prev.adjustedWidth,
                repair = RepairOption.Hang(
                    penalty = hangPenalty,
                    reason = "ForbiddenAtLineStart:${firstCluster.text}:hang",
                    offenderClusterIndex = offenderIndex,
                ),
                repairCandidates = prev.repairCandidates + pushIn.candidate + hangCandidate,
                // Keep the out-of-measure suffix contiguous. A source-authored
                // mandatory-break control immediately after the mark has zero
                // advance/display but still belongs to this line's trailing
                // structural suffix.
                hangingClusterIndices = existingHanging + (offenderIndex..mergeEndIndex),
                endReason = if (mergeEndIndex == curr.clusterRange.last) curr.endReason else prev.endReason,
            )
            if (mergeEndIndex == curr.clusterRange.last) {
                mutable.removeAt(i)
            } else {
                mutable[i] = rebuildLine(
                    (mergeEndIndex + 1)..curr.clusterRange.last,
                    naturalClusters,
                    adjustedClusters,
                    endReason = curr.endReason,
                )
                continue
            }
            continue
        }

        val canCarry = prev.clusterRange.first < prev.clusterRange.last
        if (!canCarry) {
            repairCandidates += RepairCandidate(
                kind = "CarryPrevious",
                reasonCode = "ForbiddenAtLineStart",
                offenderClusterIndex = curr.clusterRange.first,
                penalty = carryPreviousPenalty,
                accepted = false,
                rejectionReason = "no-room-to-carry",
            )
            repairCandidates += RepairCandidate(
                kind = "LeaveRagged",
                reasonCode = "ForbiddenAtLineStart",
                offenderClusterIndex = curr.clusterRange.first,
                penalty = leaveRaggedPenalty,
                accepted = true,
            )
            mutable[i] = curr.copy(
                repair = RepairOption.LeaveRagged(
                    penalty = leaveRaggedPenalty,
                    reason = "ForbiddenAtLineStart:${firstCluster.text}:no-room-to-carry",
                    offenderClusterIndex = curr.clusterRange.first,
                ),
                repairCandidates = repairCandidates,
            )
            i += 1
            continue
        }

        val carriedIndex = prev.clusterRange.last
        // CarryPrevious must not split an unbreakable span: carrying any
        // cluster other than the span's first would leave part of the span
        // behind on the previous line.
        val splitsUnbreakable = unbreakableRanges.containsBoundary(carriedIndex)
        if (splitsUnbreakable) {
            repairCandidates += RepairCandidate(
                kind = "CarryPrevious",
                reasonCode = "ForbiddenAtLineStart",
                offenderClusterIndex = curr.clusterRange.first,
                penalty = carryPreviousPenalty,
                accepted = false,
                rejectionReason = "carry-would-split-mourning-span",
                carriedClusterIndex = carriedIndex,
            )
            repairCandidates += RepairCandidate(
                kind = "LeaveRagged",
                reasonCode = "ForbiddenAtLineStart",
                offenderClusterIndex = curr.clusterRange.first,
                penalty = leaveRaggedPenalty,
                accepted = true,
            )
            mutable[i] = curr.copy(
                repair = RepairOption.LeaveRagged(
                    penalty = leaveRaggedPenalty,
                    reason = "ForbiddenAtLineStart:${firstCluster.text}:carry-would-split-mourning-span",
                    offenderClusterIndex = curr.clusterRange.first,
                ),
                repairCandidates = repairCandidates,
            )
            i += 1
            continue
        }
        val newPrevRange = prev.clusterRange.first..(carriedIndex - 1)
        val newCurrRange = carriedIndex..curr.clusterRange.last
        val carriedCurrent = rebuildLine(
            newCurrRange,
            naturalClusters,
            adjustedClusters,
            endReason = curr.endReason,
        )
        if (carriedCurrent.adjustedWidth > maxWidth) {
            // CLREQ 推出 may not overflow maxWidth — that would be effectively
            // hanging punctuation, which is opt-in per ADR 0006. When the
            // receiving line is already at capacity, this fallback leaves
            // the offender at line start with a LeaveRagged marker. The
            // lookahead breaker is expected to avoid hitting this case by
            // picking a break that has room downstream.
            repairCandidates += RepairCandidate(
                kind = "CarryPrevious",
                reasonCode = "ForbiddenAtLineStart",
                offenderClusterIndex = curr.clusterRange.first,
                penalty = carryPreviousPenalty,
                accepted = false,
                rejectionReason = "carry-overflows",
                carriedClusterIndex = carriedIndex,
            )
            repairCandidates += RepairCandidate(
                kind = "LeaveRagged",
                reasonCode = "ForbiddenAtLineStart",
                offenderClusterIndex = curr.clusterRange.first,
                penalty = leaveRaggedPenalty,
                accepted = true,
            )
            mutable[i] = curr.copy(
                repair = RepairOption.LeaveRagged(
                    penalty = leaveRaggedPenalty,
                    reason = "ForbiddenAtLineStart:${firstCluster.text}:carry-overflows",
                    offenderClusterIndex = curr.clusterRange.first,
                ),
                repairCandidates = repairCandidates,
            )
            i += 1
            continue
        }

        repairCandidates += RepairCandidate(
            kind = "CarryPrevious",
            reasonCode = "ForbiddenAtLineStart",
            offenderClusterIndex = curr.clusterRange.first,
            penalty = carryPreviousPenalty,
            accepted = true,
            carriedClusterIndex = carriedIndex,
        )
        mutable[i - 1] = rebuildLine(
            newPrevRange,
            naturalClusters,
            adjustedClusters,
            endReason = prev.endReason,
        )
        mutable[i] = carriedCurrent.copy(
            repair = RepairOption.CarryPrevious(
                penalty = carryPreviousPenalty,
                reason = "ForbiddenAtLineStart:${firstCluster.text}:carried=${adjustedClusters[carriedIndex].text}",
                offenderClusterIndex = curr.clusterRange.first,
                carriedClusterIndex = carriedIndex,
            ),
            repairCandidates = repairCandidates,
        )
        i += 1
    }

    val totalBadness = mutable.sumOf { (it.repair?.penalty ?: 0).toDouble() }.toFloat()
    return LineSolution(mutable, totalBadness = totalBadness)
}

internal data class PushInResult(
    val previous: LineCandidate,
    val current: LineCandidate?,
    val candidate: RepairCandidate,
)

/**
 * CLREQ 推入 — compress IN-LINE glue (across every cluster on the merged
 * line) to fit the offender. The offender's own trailing glue is one of
 * many possible contributors; the previous line's `、`, `，`, etc. all
 * count.
 *
 * Single-source contract:
 *   `totalShrink` is the canonical amount of glue this PushIn consumes
 *   across the whole line. `allocations` records per-cluster shrink so the
 *   engine can subtract from each cluster's advance independently.
 *   - [LineCandidate.adjustedWidth] is recomputed here as
 *     `expanded.adjustedWidth - totalShrink` to keep ADR 0005's drawable-
 *     cluster invariant: the line candidate already reflects the post-
 *     shrink geometry the breaker decided. The engine MUST NOT subtract
 *     allocation shrink from cluster advance and ALSO subtract it from
 *     `adjustedWidth` — pick one consumer per derived field.
 *   - Today `totalShrink == overflow`. If a future partial-PushIn lands
 *     (`totalShrink < overflow`), update it here and rely on it as the
 *     only knob; do not reintroduce a second `overflow`-based path.
 */
internal fun tryPushIn(
    prev: LineCandidate,
    curr: LineCandidate,
    naturalClusters: List<Cluster>,
    adjustedClusters: List<Cluster>,
    maxWidth: Float,
    shrinkOpportunities: List<ShrinkOpportunity>,
    pushInPenalty: Int,
    mergeThroughClusterIndex: Int? = null,
    /**
     * Why this PushIn fired — `ForbiddenAtLineStart` for 避头尾 repair, or
     * `LineAdjustmentPushIn` for the `LineAdjustmentStrategy` fill pass
     * (ADR 0031). Surfaces in the dump so the two callers stay distinguishable.
     */
    reasonCode: String = "ForbiddenAtLineStart",
): PushInResult {
    val offenderIndex = mergeThroughClusterIndex ?: curr.clusterRange.first
    require(offenderIndex in curr.clusterRange) { "PushIn merge-through cluster must belong to the current line." }
    val mergeEndIndex = mandatoryBreakTailEnd(curr, offenderIndex, adjustedClusters)
    val expandedRange = prev.clusterRange.first..mergeEndIndex
    val expanded = rebuildLine(expandedRange, naturalClusters, adjustedClusters)
    val overflow = expanded.adjustedWidth - maxWidth

    // Tiered shrink resources across the merged line (CLREQ 挤压处理优先
    // 顺序, ADR 0020). The offender will sit at the merged line's END, so
    // its removable outer frame IS the 行末标点削半宽 step — promote it to
    // tier 1. A centred glyph owns a paired frame, not a trailing-only one.
    val inLine = shrinkOpportunities
        .filter { it.clusterIndex in expandedRange && it.capacity > 0f }
        .filter { !it.lineEndOnly || it.clusterIndex == offenderIndex }
        .map { opp ->
            if (
                opp.clusterIndex == offenderIndex &&
                (
                    opp.channel == ShrinkChannel.TrailingGlue ||
                        opp.channel == ShrinkChannel.LeadingAndTrailingGlue
                )
            ) {
                opp.copy(tier = 1)
            } else {
                opp
            }
        }
    val totalCapacity = inLine.sumOf { it.capacity.toDouble() }.toFloat()

    if (overflow > totalCapacity) {
        return PushInResult(
            previous = prev,
            current = curr,
            candidate = RepairCandidate(
                kind = "PushIn",
                reasonCode = reasonCode,
                offenderClusterIndex = offenderIndex,
                penalty = pushInPenalty,
                accepted = false,
                rejectionReason = "insufficient-capacity",
                targetClusterIndex = offenderIndex,
                requiredShrink = overflow.coerceAtLeast(0f),
                availableCapacity = totalCapacity,
            ),
        )
    }

    // `overflow <= 0` is NOT a rejection: upstream repairs in the chain
    // (a PushIn / CarryPrevious on earlier lines) can shorten the previous
    // line after the break was placed, so the offender simply fits now.
    // That is a zero-shrink merge. Refusing it cascaded into
    // carry-overflows → LeaveRagged and left `、` / `」` at line start.
    val shrink = overflow.coerceAtLeast(0f)
    val allocations = if (shrink > 0f) distributePushInShrink(inLine, shrink) else emptyList()
    val offender = adjustedClusters[offenderIndex]
    val candidate = RepairCandidate(
        kind = "PushIn",
        reasonCode = reasonCode,
        offenderClusterIndex = offenderIndex,
        penalty = pushInPenalty,
        accepted = true,
        targetClusterIndex = offenderIndex,
        shrink = shrink,
        requiredShrink = shrink,
        availableCapacity = totalCapacity,
    )
    val repairedPrevious = expanded.copy(
        adjustedWidth = expanded.adjustedWidth - shrink,
        endReason = if (mergeEndIndex == curr.clusterRange.last) curr.endReason else prev.endReason,
        repair = RepairOption.PushIn(
            penalty = pushInPenalty,
            reason = if (shrink > 0f) {
                "$reasonCode:${offender.text}:pushed-in=${shrink.toPortableDebugString()}/${totalCapacity.toPortableDebugString()}"
            } else {
                "$reasonCode:${offender.text}:fits-no-shrink"
            },
            offenderClusterIndex = offenderIndex,
            allocations = allocations,
            totalShrink = shrink,
            totalAvailableCapacity = totalCapacity,
        ),
        // Preserve any repair history the receiving line already carries
        // (e.g. its own start was repaired earlier in the chain) — the
        // PushIn marker for the absorbed offender must not erase it.
        repairCandidates = prev.repairCandidates + candidate,
    )
    val repairedCurrent = if (mergeEndIndex == curr.clusterRange.last) {
        null
    } else {
        rebuildLine(
            (mergeEndIndex + 1)..curr.clusterRange.last,
            naturalClusters,
            adjustedClusters,
            endReason = curr.endReason,
        )
    }
    return PushInResult(repairedPrevious, repairedCurrent, candidate)
}

private fun Float.toPortableDebugString(): String {
    val text = toString()
    return if ('.' !in text && !text.contains('e', ignoreCase = true)) "$text.0" else text
}

private fun mandatoryBreakTailEnd(
    curr: LineCandidate,
    mergeThroughClusterIndex: Int,
    adjustedClusters: List<Cluster>,
): Int {
    if (curr.endReason != LineEndReason.MandatoryBreak) return mergeThroughClusterIndex
    if (mergeThroughClusterIndex >= curr.clusterRange.last) return mergeThroughClusterIndex
    val tail = (mergeThroughClusterIndex + 1)..curr.clusterRange.last
    val tailIsZeroWidthBreak = tail.all { idx ->
        adjustedClusters[idx].displayText.isEmpty() && adjustedClusters[idx].advance == 0f
    }
    return if (tailIsZeroWidthBreak) curr.clusterRange.last else mergeThroughClusterIndex
}

/**
 * `LineAdjustmentPushIn` (ADR 0031) — the fill counterpart of 避头尾 PushIn.
 * After greedy/lookahead + 避头尾 repair, every non-last line is otherwise
 * STRETCHED to 行长; this pass instead pulls the next line's leading cluster UP
 * and COMPRESSES the line to fit, whenever压缩 is the smaller deviation from
 * 自然密排 (CLREQ §6.2.2「先挤进、后推出」+「先挤压、后拉伸」).
 *
 * Per boundary: leaving the next safe cluster/group stretches the line by
 * `deficit` (cost `Ws·deficit`); pulling it in compresses by
 * `overflow = groupAdvance − deficit` (cost `Wc·overflow`). Pull iff
 * `Wc·overflow < Ws·deficit`, i.e. `overflow < deficit × [compressBias]`
 * (= `Ws/Wc`), AND [tryPushIn] finds the room. `compressBias`大 → 先推入；
 * 小 → 先推出；`PushOutOnly` never calls this.
 *
 * Reuses [tryPushIn] so line-end 削半 / glue-pool / capacity reconcile exactly
 * as 避头尾 PushIn does. To avoid double-consuming glue it skips lines already
 * carrying a non-fill repair (避头尾 PushIn/Hang/CarryNext). A zero-shrink fill
 * PushIn may continue cascading: it has not consumed glue yet, and stopping
 * there leaves the repaired line visibly loose for no model reason.
 */
internal fun applyFillPushIn(
    lines: List<LineCandidate>,
    naturalClusters: List<Cluster>,
    adjustedClusters: List<Cluster>,
    maxWidth: Float,
    shrinkOpportunities: List<ShrinkOpportunity>,
    firstLineIndent: Float,
    compressBias: Float,
    forbiddenLineStartClusters: Set<Int>?,
    forbiddenLineEndClusters: Set<Int>,
    unbreakableRanges: UnbreakableRanges,
    pushInPenalty: Int,
    gapBoundaries: Set<Int> = emptySet(),
    progressiveBreakOpportunities: Map<Int, ProgressiveBreakOpportunity> = emptyMap(),
): List<LineCandidate> {
    if (lines.size < 2 || compressBias <= 0f) return lines
    val out = lines.toMutableList()
    var i = 0
    while (i < out.size - 1) {
        val prev = out[i]
        val curr = out[i + 1]
        val canExtendZeroShrinkFill = prev.repair.isContinuableZeroShrinkFillPushIn()
        if ((prev.repair != null && !canExtendZeroShrinkFill) ||
            prev.hangingClusterIndex != null ||
            prev.endReason != LineEndReason.AutoWrap
        ) {
            i += 1
            continue
        }
        val limit = lineLimit(maxWidth, firstLineIndent, prev.clusterRange.first)
        val deficit = limit - prev.adjustedWidth
        if (deficit <= 0f) {
            i += 1
            continue
        }
        val curr0 = curr.clusterRange.first
        var groupEnd = fillPushInGroupEnd(
            curr = curr,
            forbiddenLineStartClusters = forbiddenLineStartClusters,
            forbiddenLineEndClusters = forbiddenLineEndClusters,
            unbreakableRanges = unbreakableRanges,
        )
        if (groupEnd == null) {
            i += 1
            continue
        }
        val currentBreak = progressiveBreakOpportunities[prev.clusterRange.last + 1]
        var resultingBreak = progressiveBreakOpportunities[groupEnd + 1]
        var addedAdvance = (curr0..groupEnd).sumOf { adjustedClusters[it].advance.toDouble() }.toFloat()
        var promotesProgressiveTier =
            currentBreak != null && resultingBreak != null &&
                currentBreak.spanRange == resultingBreak.spanRange &&
                resultingBreak.tier.priority < currentBreak.tier.priority
        if (
            promotesProgressiveTier &&
            addedAdvance < deficit - PROGRESSIVE_TIER_PROMOTION_FILL_EPSILON
        ) {
            // promotesProgressiveTier is only true with a non-null currentBreak.
            val activeBreak = currentBreak!!
            // `ProgressiveTechnicalFillRefillSkipsIntermediateCleanerTier`: an upstream repair can
            // move this line's start forward while leaving its old technical end untouched. If the
            // first pulled grapheme happens to land on a cleaner boundary but still leaves the line
            // short, cross that intermediate boundary and refill to the next boundary of the
            // already-selected tier. Stopping at the cleaner label would create a large deficit;
            // refusing the pull entirely would strand the stale pre-repair break.
            val searchStart = groupEnd + 2
            val searchEnd = curr.clusterRange.last + 1
            val matchingTierBoundary = if (searchStart > searchEnd) {
                null
            } else {
                (searchStart..searchEnd).firstOrNull { boundary ->
                    progressiveBreakOpportunities[boundary]?.let { opportunity ->
                        opportunity.spanRange == activeBreak.spanRange &&
                            opportunity.tier == activeBreak.tier
                    } == true
                }
            }
            if (matchingTierBoundary != null) {
                groupEnd = matchingTierBoundary - 1
                resultingBreak = progressiveBreakOpportunities[matchingTierBoundary]
                addedAdvance = (curr0..groupEnd)
                    .sumOf { adjustedClusters[it].advance.toDouble() }
                    .toFloat()
                promotesProgressiveTier = false
            }
        }
        if (
            currentBreak != null && resultingBreak != null &&
            currentBreak.spanRange == resultingBreak.spanRange &&
            resultingBreak.tier.priority > currentBreak.tier.priority
        ) {
            // `ProgressiveTechnicalFillPushInTierPromotion`: compare with the break actually
            // selected for this line. Earlier structural candidates may already have been rejected
            // as visibly too loose, so their nominal priority must not block Emergency → Syllable.
            // Only a real degradation from the current boundary is forbidden.
            i += 1
            continue
        }
        val overflow = addedAdvance - deficit
        if (promotesProgressiveTier && overflow < -PROGRESSIVE_TIER_PROMOTION_FILL_EPSILON) {
            // `ProgressiveTechnicalTierPromotionRequiresFullLine`: with the technical tier
            // fallback threshold at zero, pulling one cluster merely to rename Emergency as a
            // cleaner tier must not reopen a positive line deficit. That would override the
            // breaker's rightmost hard cut and then justify the resulting short prefix.
            i += 1
            continue
        }
        // 方向档位 (bias = Ws/Wc): PushOutFirst 下拉入依旧罕见;PushInFirst 下
        // 该闸恒通,由下面的均摊闸决定。
        if (overflow >= deficit * compressBias) {
            i += 1
            continue
        }
        // NeighborAmortizedAdjustment (ADR 0038), fill side: the pull may not
        // introduce a compression DENSITY worse than the stretch density it
        // cures (per-gap normalized; the fill is a cascade — curr refills from
        // ITS next line, so its deficit is not priced here). overflow ≤ 0 means
        // the cluster fits without compressing: always a win.
        if (overflow > 0f && !promotesProgressiveTier) {
            val prevGaps = lineGapCount(prev.clusterRange, gapBoundaries)
            val dStretchCured = if (prevGaps == 0) 0f else deficit / prevGaps
            val dCompressionIntroduced =
                overflow / maxOf(1, lineGapCount(prev.clusterRange.first..groupEnd, gapBoundaries))
            if (dCompressionIntroduced > dStretchCured) {
                i += 1
                continue
            }
        }
        val result = tryPushIn(
            prev = prev,
            curr = curr,
            naturalClusters = naturalClusters,
            adjustedClusters = adjustedClusters,
            maxWidth = limit,
            shrinkOpportunities = shrinkOpportunities,
            pushInPenalty = pushInPenalty,
            mergeThroughClusterIndex = groupEnd,
            reasonCode = if (promotesProgressiveTier) {
                "ProgressiveTechnicalTierPromotion"
            } else {
                "LineAdjustmentPushIn"
            },
        )
        if (result.candidate.accepted) {
            out[i] = result.previous
            if (result.current == null) out.removeAt(i + 1) else out[i + 1] = result.current
            if (result.previous.repair.isContinuableZeroShrinkFillPushIn() && result.current != null) {
                continue
            }
        }
        i += 1
    }
    return out
}

private fun RepairOption?.isContinuableZeroShrinkFillPushIn(): Boolean =
    this is RepairOption.PushIn &&
        totalShrink <= 0.001f &&
        reason.startsWith("LineAdjustmentPushIn:")

private const val PROGRESSIVE_TIER_PROMOTION_FILL_EPSILON = 0.001f

private fun fillPushInGroupEnd(
    curr: LineCandidate,
    forbiddenLineStartClusters: Set<Int>?,
    forbiddenLineEndClusters: Set<Int>,
    unbreakableRanges: UnbreakableRanges,
): Int? {
    var groupEnd = curr.clusterRange.first
    while (groupEnd <= curr.clusterRange.last) {
        val containing = unbreakableRanges.containingFromClosedStartOrNull(groupEnd)
        if (containing != null) {
            groupEnd = containing.last
            if (groupEnd > curr.clusterRange.last) return null
            // containing.last may begin the next contiguous unbreakable range: re-check so the
            // pulled group ends past the WHOLE run, never inside it (closure, matching
            // adjustBreakForUnbreakables). Without this, a per-atom formula's fill pass refills the
            // break back inside the chain (`10^{34}|x^3`).
            continue
        }
        if (groupEnd in forbiddenLineEndClusters) {
            groupEnd += 1
            continue
        }
        val nextHead = groupEnd + 1
        if (nextHead <= curr.clusterRange.last && forbiddenLineStartClusters?.contains(nextHead) == true) {
            groupEnd = nextHead
            continue
        }
        return groupEnd
    }
    return null
}

/** Gated [applyFillPushIn] over a [LineSolution] — no-op when not [enabled]. */
internal fun LineSolution.withFillPushIn(
    enabled: Boolean,
    naturalClusters: List<Cluster>,
    adjustedClusters: List<Cluster>,
    maxWidth: Float,
    shrinkOpportunities: List<ShrinkOpportunity>,
    firstLineIndent: Float,
    compressBias: Float,
    forbiddenLineStartClusters: Set<Int>?,
    forbiddenLineEndClusters: Set<Int>,
    unbreakableRanges: UnbreakableRanges,
    pushInPenalty: Int,
    gapBoundaries: Set<Int> = emptySet(),
    progressiveBreakOpportunities: Map<Int, ProgressiveBreakOpportunity> = emptyMap(),
): LineSolution =
    if (!enabled) {
        this
    } else {
        LineSolution(
            lines = applyFillPushIn(
                lines, naturalClusters, adjustedClusters, maxWidth,
                shrinkOpportunities, firstLineIndent, compressBias,
                forbiddenLineStartClusters, forbiddenLineEndClusters, unbreakableRanges, pushInPenalty,
                gapBoundaries,
                progressiveBreakOpportunities,
            ),
            totalBadness = totalBadness,
        )
    }

/**
 * Distribute [totalShrink] across [opportunities] in STRICT TIER ORDER
 * (CLREQ 挤压处理优先顺序): tier k is exhausted before tier k+1 is touched.
 * Within a tier, shrink is shared proportionally to capacity (equal caps →
 * equal amounts, the CLREQ「同时、同等量」rule); rounding remainder lands on
 * the tier's last entry. Allocations carry the consumption channel so the
 * engine knows whether to consume glue (one- or two-sided) or raw advance.
 */
private fun distributePushInShrink(
    opportunities: List<ShrinkOpportunity>,
    totalShrink: Float,
): List<PushInAllocation> {
    if (opportunities.isEmpty() || totalShrink <= 0f) return emptyList()

    val allocations = mutableListOf<PushInAllocation>()
    var remaining = totalShrink
    val byTier = opportunities.groupBy { it.tier }
    for (tier in byTier.keys.sorted()) {
        val tierOpps = byTier.getValue(tier)
        if (remaining <= 0f) break
        val tierCapacity = tierOpps.sumOf { it.capacity.toDouble() }.toFloat()
        if (tierCapacity <= 0f) continue
        val tierShrink = remaining.coerceAtMost(tierCapacity)
        var tierRemaining = tierShrink
        val ordered = tierOpps.sortedBy { it.clusterIndex }
        ordered.forEachIndexed { i, opp ->
            val isLast = (i == ordered.lastIndex)
            val share = if (isLast) {
                tierRemaining.coerceAtMost(opp.capacity)
            } else {
                (tierShrink * opp.capacity / tierCapacity).coerceAtMost(opp.capacity)
            }
            if (share > 0f) {
                allocations += PushInAllocation(
                    clusterIndex = opp.clusterIndex,
                    shrink = share,
                    availableCapacity = opp.capacity,
                    channel = opp.channel,
                )
                tierRemaining -= share
            }
        }
        remaining -= (tierShrink - tierRemaining.coerceAtLeast(0f))
    }
    return allocations
}
