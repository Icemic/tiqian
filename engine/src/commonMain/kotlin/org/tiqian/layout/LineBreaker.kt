package org.tiqian.layout

import org.tiqian.core.Cluster
import org.tiqian.core.LineEndReason
import org.tiqian.core.TextRange

interface LineBreaker {
    val strategyName: String
        get() = "custom"

    fun breakLines(
        naturalClusters: List<Cluster>,
        adjustedClusters: List<Cluster>,
        maxWidth: Float,
        /**
         * Tiered in-line shrink resources for PushIn, ordered per CLREQ's
         * 挤压处理优先顺序 (ADR 0020). Lower tier = consumed first; the
         * offender's own trailing glue is promoted to tier 1 (行末标点
         * 调成半宽) at repair time.
         */
        shrinkOpportunities: List<ShrinkOpportunity> = emptyList(),
        /**
         * Cluster-index ranges that must stay on one line when they fit
         * (示亡号 spans, ADR 0018). `MourningSpanKeptUnbroken`: a break that
         * would land strictly inside a range moves to the range start
         * instead; a range wider than the measure falls back to splitting.
         */
        unbreakableRanges: UnbreakableRanges = UnbreakableRanges.Empty,
        /**
         * 段首缩进 in layout units: the line that starts at cluster 0 has
         * its usable measure reduced to `maxWidth - firstLineIndent`. All
         * other lines use the full [maxWidth].
         */
        firstLineIndent: Float = 0f,
        /**
         * `LineEndHangingPunctuation` (CLREQ 行尾点号悬挂, ADR 0006): cluster
         * indices of 顿/逗/句 that MAY hang past the measure when they would
         * otherwise land at line start. Empty = disabled (default). Tried
         * after PushIn, before CarryPrevious — the hung mark sits beyond
         * `maxWidth` instead of pulling a whole character down.
         */
        hangableClusters: Set<Int> = emptySet(),
        /**
         * Protected cluster ranges whose members share provenance for an
         * extendable trailing hang. A range can include the point-mark run's
         * base cluster; an offender must still belong to [hangableClusters].
         * Keeping ranges distinct prevents adjacent contextual groups from
         * accidentally chaining. Empty preserves the ordinary one-mark rule.
         */
        extendableHangRanges: List<IntRange> = emptyList(),
        /**
         * Forbidden-at-line-start cluster indices, resolved by the caller
         * from the profile's [org.tiqian.clreq.KinsokuLevel] plus applicable
         * Unicode boundary policies. When non-null this overrides the
         * breaker's own [KinsokuRule] (so the paragraph engine can carry the
         * fully resolved policy); null = fall back to the injected rule
         * (standalone breaker use / tests).
         */
        forbiddenLineStartClusters: Set<Int>? = null,
        /**
         * Forbidden-at-line-END cluster indices (开引号/开括号; GB·严格 的
         * 分隔号). These may come from CLREQ or a Unicode boundary policy.
         * A break that would end a line on one of these retreats
         * (`adjustBreakForLineEnd`), moving the mark to the next line's
         * start — recorded as [RepairOption.CarryNext]. Empty = no resolved
         * line-end prohibition.
         */
        forbiddenLineEndClusters: Set<Int> = emptySet(),
        /**
         * `LineEndHangingHyphen` (ADR 0029): cluster indices a break before which
         * is a Western syllable / hard-break continuation. The breaker prefers a
         * whole-word break and only takes one of these when the word is over-long
         * (mandatory) or the whole-word line would stretch 汉字间距 past
         * [maxCjkStretchPerGap] (last resort). Empty = no hyphenation.
         */
        hyphenBreakClusters: Set<Int> = emptySet(),
        /** CJK↔CJK boundary cluster indices — the stretchable gaps looseness is measured over. */
        cjkInterCharBoundaries: Set<Int> = emptySet(),
        /** Per-CJK-gap stretch above which a whole-word line counts as「太松」⇒ hyphenate. */
        maxCjkStretchPerGap: Float = Float.POSITIVE_INFINITY,
        /** CJK↔Latin boundary cluster indices — 中西间距 absorbs deficit before 汉字间距. */
        sinoWesternBoundaries: Set<Int> = emptySet(),
        /** Per-中西间距 stretch capacity (cap − natural); subtracted before the CJK looseness. */
        sinoWesternStretchCap: Float = 0f,
        /**
         * `LineAdjustmentPushIn` (ADR 0031): when true, a fill pass pulls an
         * over-the-edge cluster onto the previous line and compresses to fit
         * whenever 压缩 is the smaller (bias-weighted) deviation than stretching.
         * False (default) = 仅推出, the historical greedy-then-stretch behavior.
         */
        lineAdjustmentPushIn: Boolean = false,
        /** `Ws/Wc` — how much cheaper 压缩 is than 拉伸 (＞1 = 先挤压). See [applyFillPushIn]. */
        lineAdjustmentCompressBias: Float = 1f,
        /**
         * Cluster indices whose line must end immediately after that cluster
         * (ADR 0037 mandatory breaks). These boundaries are source-authored:
         * line breaking, kinsoku repair, fill PushIn, and justification must
         * not cross them.
         */
        hardBreakAfterClusters: Set<Int> = emptySet(),
        /**
         * Zero-advance structural controls that must not count as a line's
         * first visible content. This prevents a leading U+200B from becoming
         * its own empty auto-wrapped line before an over-wide token.
         */
        nonRenderingControlClusters: Set<Int> = emptySet(),
        /** Tiered clean break boundaries inside technical inline source spans. */
        progressiveBreakOpportunities: Map<Int, ProgressiveBreakOpportunity> = emptyMap(),
    ): LineSolution
}

/**
 * GreedyLineBreaker — fills each line until the next cluster would overflow,
 * then starts a new line. After the greedy pass, [kinsoku] is consulted to
 * detect breaks that would place a forbidden-at-line-start cluster at the
 * beginning of a line; such breaks try PushIn first, then CarryPrevious
 * (move the previous cluster onto the next line together with the offender).
 *
 * Repairs that cannot be applied without leaving a line empty fall back to
 * [RepairOption.LeaveRagged] — the unfortunate break is recorded but kept.
 *
 * Slice 4b scope: PushIn via punctuation glue, CarryPrevious, and LeaveRagged.
 * Hang remains profile opt-in and is not a default repair.
 */
class GreedyLineBreaker(
    private val kinsoku: KinsokuRule = ClreqKinsokuRule(),
    private val pushInPenalty: Int = 2,
    private val carryPreviousPenalty: Int = 10,
    private val leaveRaggedPenalty: Int = 20,
) : LineBreaker {
    override val strategyName: String = "greedy"

    override fun breakLines(
        naturalClusters: List<Cluster>,
        adjustedClusters: List<Cluster>,
        maxWidth: Float,
        shrinkOpportunities: List<ShrinkOpportunity>,
        unbreakableRanges: UnbreakableRanges,
        firstLineIndent: Float,
        hangableClusters: Set<Int>,
        extendableHangRanges: List<IntRange>,
        forbiddenLineStartClusters: Set<Int>?,
        forbiddenLineEndClusters: Set<Int>,
        hyphenBreakClusters: Set<Int>,
        cjkInterCharBoundaries: Set<Int>,
        maxCjkStretchPerGap: Float,
        sinoWesternBoundaries: Set<Int>,
        sinoWesternStretchCap: Float,
        lineAdjustmentPushIn: Boolean,
        lineAdjustmentCompressBias: Float,
        hardBreakAfterClusters: Set<Int>,
        nonRenderingControlClusters: Set<Int>,
        progressiveBreakOpportunities: Map<Int, ProgressiveBreakOpportunity>,
    ): LineSolution {
        if (adjustedClusters.isEmpty()) return LineSolution(emptyList())
        require(naturalClusters.size == adjustedClusters.size) {
            "naturalClusters and adjustedClusters must align cluster-for-cluster."
        }

        val greedy = greedyFill(
            naturalClusters, adjustedClusters, maxWidth, unbreakableRanges,
            firstLineIndent, forbiddenLineEndClusters,
            hyphenBreakClusters, cjkInterCharBoundaries, maxCjkStretchPerGap,
            sinoWesternBoundaries, sinoWesternStretchCap,
            hardBreakAfterClusters, nonRenderingControlClusters,
            progressiveBreakOpportunities,
        )
        val repaired = applyKinsokuRepairs(
            initial = greedy,
            naturalClusters = naturalClusters,
            adjustedClusters = adjustedClusters,
            maxWidth = maxWidth,
            kinsoku = kinsoku,
            shrinkOpportunities = shrinkOpportunities,
            pushInPenalty = pushInPenalty,
            carryPreviousPenalty = carryPreviousPenalty,
            leaveRaggedPenalty = leaveRaggedPenalty,
            unbreakableRanges = unbreakableRanges,
            firstLineIndent = firstLineIndent,
            hangableClusters = hangableClusters,
            extendableHangRanges = extendableHangRanges,
            forbiddenLineStartClusters = forbiddenLineStartClusters,
        )
        return repaired.withFillPushIn(
            lineAdjustmentPushIn, naturalClusters, adjustedClusters, maxWidth,
            shrinkOpportunities, firstLineIndent, lineAdjustmentCompressBias,
            forbiddenLineStartClusters, forbiddenLineEndClusters, unbreakableRanges, pushInPenalty,
            gapBoundaries = cjkInterCharBoundaries + sinoWesternBoundaries,
            progressiveBreakOpportunities = progressiveBreakOpportunities,
        )
    }

    private fun greedyFill(
        naturalClusters: List<Cluster>,
        adjustedClusters: List<Cluster>,
        maxWidth: Float,
        unbreakableRanges: UnbreakableRanges,
        firstLineIndent: Float,
        forbiddenLineEndClusters: Set<Int>,
        hyphenBreakClusters: Set<Int>,
        cjkInterCharBoundaries: Set<Int>,
        maxCjkStretchPerGap: Float,
        sinoWesternBoundaries: Set<Int>,
        sinoWesternStretchCap: Float,
        hardBreakAfterClusters: Set<Int>,
        nonRenderingControlClusters: Set<Int>,
        progressiveBreakOpportunities: Map<Int, ProgressiveBreakOpportunity>,
    ): List<LineCandidate> {
        val lines = mutableListOf<LineCandidate>()
        var lineStart = 0
        var adjustedAccum = 0f
        var naturalAccum = 0f
        var hasRenderingContent = false

        var i = 0
        while (i < adjustedClusters.size) {
            val nextAdjusted = adjustedAccum + adjustedClusters[i].advance
            val overflows = nextAdjusted > lineLimit(maxWidth, firstLineIndent, lineStart) && hasRenderingContent
            if (overflows) {
                val progressive = decideProgressiveBreak(
                    lineStart, i, progressiveBreakOpportunities,
                    adjustedClusters, lineLimit(maxWidth, firstLineIndent, lineStart),
                    cjkInterCharBoundaries, maxCjkStretchPerGap,
                    sinoWesternBoundaries, sinoWesternStretchCap,
                )
                val decided = decideHyphenBreak(
                    lineStart, progressive, adjustedClusters,
                    lineLimit(maxWidth, firstLineIndent, lineStart),
                    hyphenBreakClusters, cjkInterCharBoundaries, maxCjkStretchPerGap,
                    sinoWesternBoundaries, sinoWesternStretchCap,
                )
                val afterUnbreak = adjustBreakForUnbreakables(decided, lineStart, unbreakableRanges)
                val breakAt = adjustBreakForLineEnd(afterUnbreak, lineStart, forbiddenLineEndClusters)
                lines += closeFilledLine(
                    lineStart..(breakAt - 1), afterUnbreak, naturalClusters, adjustedClusters,
                )
                lineStart = breakAt
                adjustedAccum = adjustedClusters[breakAt].advance
                naturalAccum = naturalClusters[breakAt].advance
                hasRenderingContent = breakAt !in nonRenderingControlClusters
                i = breakAt + 1
            } else {
                adjustedAccum = nextAdjusted
                naturalAccum += naturalClusters[i].advance
                if (i !in nonRenderingControlClusters) hasRenderingContent = true
                if (i in hardBreakAfterClusters) {
                    lines += rebuildLine(
                        clusterRange = lineStart..i,
                        naturalClusters = naturalClusters,
                        adjustedClusters = adjustedClusters,
                        endReason = LineEndReason.MandatoryBreak,
                    )
                    lineStart = i + 1
                    adjustedAccum = 0f
                    naturalAccum = 0f
                    hasRenderingContent = false
                }
                i += 1
            }
        }

        if (lineStart < adjustedClusters.size) {
            lines += rebuildLine(
                clusterRange = lineStart..adjustedClusters.lastIndex,
                naturalClusters = naturalClusters,
                adjustedClusters = adjustedClusters,
                endReason = LineEndReason.ParagraphEnd,
            )
        } else if (adjustedClusters.lastIndex in hardBreakAfterClusters) {
            lines += emptyLineCandidate(
                sourceOffset = adjustedClusters.last().range.end,
                endReason = LineEndReason.ParagraphEnd,
            )
        }
        return lines
    }

}

/**
 * Builds a line for [range]; if the break retreated from [naturalBreakAt]
 * (line-end kinsoku), records [RepairOption.CarryNext] for the mark(s) moved
 * to the next line.
 */
internal fun closeFilledLine(
    range: IntRange,
    naturalBreakAt: Int,
    naturalClusters: List<Cluster>,
    adjustedClusters: List<Cluster>,
): LineCandidate {
    val line = rebuildLine(range, naturalClusters, adjustedClusters)
    if (range.last + 1 == naturalBreakAt) return line
    val moved = range.last + 1
    return line.copy(
        repair = RepairOption.CarryNext(
            penalty = 0,
            reason = "ForbiddenAtLineEnd:${adjustedClusters[moved].text}:moved-to-next-line",
            movedClusterIndex = moved,
        ),
    )
}

/**
 * LookaheadLineBreaker — runs greedy first, then for each line decision tries
 * shifting the break by [window] clusters on either side and scores each
 * candidate by simulating the next [futureLineHorizon] lines (greedy + kinsoku
 * applied to the splice). Picks the candidate with the lowest combined badness.
 *
 * Badness per line = raggedness * [raggednessWeight] + repair penalty.
 * Last line raggedness is not penalized (a short last line is expected).
 *
 * Defaults are tuned so that a single em of raggedness costs less than a
 * CarryPrevious repair (8 vs 10), and noticeably less than LeaveRagged (8 vs
 * 20), so kinsoku conflicts that can be sidestepped by a one-cluster shift are
 * preferred over leaving the conflict in place.
 *
 * Default [window] is 2 — a cost/benefit middle ground: window 1 already
 * captures most of the repair-avoidance value, larger windows occasionally
 * trade worst-line deficit at narrow measures, and window 3+ has not shown
 * consistent benefit. The numbers are corpus-dependent; re-evaluate with
 * `LookaheadWindowProbe` when the fixture corpus changes.
 */
class LookaheadLineBreaker(
    private val window: Int = 2,
    private val futureLineHorizon: Int = 2,
    private val raggednessWeight: Float = 0.5f,
    private val kinsoku: KinsokuRule = ClreqKinsokuRule(),
    private val pushInPenalty: Int = 2,
    private val carryPreviousPenalty: Int = 10,
    private val leaveRaggedPenalty: Int = 20,
    private val consecutiveSyntheticHyphenPenalty: Float = 12f,
) : LineBreaker {
    override val strategyName: String = "lookahead"

    override fun breakLines(
        naturalClusters: List<Cluster>,
        adjustedClusters: List<Cluster>,
        maxWidth: Float,
        shrinkOpportunities: List<ShrinkOpportunity>,
        unbreakableRanges: UnbreakableRanges,
        firstLineIndent: Float,
        hangableClusters: Set<Int>,
        extendableHangRanges: List<IntRange>,
        forbiddenLineStartClusters: Set<Int>?,
        forbiddenLineEndClusters: Set<Int>,
        hyphenBreakClusters: Set<Int>,
        cjkInterCharBoundaries: Set<Int>,
        maxCjkStretchPerGap: Float,
        sinoWesternBoundaries: Set<Int>,
        sinoWesternStretchCap: Float,
        lineAdjustmentPushIn: Boolean,
        lineAdjustmentCompressBias: Float,
        hardBreakAfterClusters: Set<Int>,
        nonRenderingControlClusters: Set<Int>,
        progressiveBreakOpportunities: Map<Int, ProgressiveBreakOpportunity>,
    ): LineSolution {
        if (adjustedClusters.isEmpty()) return LineSolution(emptyList())
        require(naturalClusters.size == adjustedClusters.size) {
            "naturalClusters and adjustedClusters must align cluster-for-cluster."
        }
        require(window >= 0) { "window must be non-negative." }
        require(futureLineHorizon >= 0) { "futureLineHorizon must be non-negative." }

        val committed = mutableListOf<LineCandidate>()
        var lineStart = 0
        // NeighborAmortizedAdjustment (ADR 0038): gaps that justification can
        // open — CJK 字距 + 中西间距 — price the per-line density; the last
        // committed line's density seeds the neighbor-difference term.
        val gapBoundaries = cjkInterCharBoundaries + sinoWesternBoundaries
        val dRef = maxCjkStretchPerGap
        var committedDensity = 0f
        var committedSyntheticHyphenRun = 0
        // Sorted once; `lineStart` only advances, so a monotonic cursor finds
        // the next mandatory break in amortized O(1) — newline-heavy text has
        // lines ≈ breaks, so a per-line set scan would be quadratic.
        val sortedBreaks = hardBreakAfterClusters.toIntArray().also { it.sort() }
        var breakCursor = 0
        while (lineStart < adjustedClusters.size) {
            while (breakCursor < sortedBreaks.size && sortedBreaks[breakCursor] < lineStart) breakCursor += 1
            val mandatoryEnd = if (breakCursor < sortedBreaks.size) sortedBreaks[breakCursor] else null
            val segmentEndExclusive = mandatoryEnd?.plus(1) ?: adjustedClusters.size
            // Line-end retreat is applied at commit (below), not here, so the
            // chosen break's pre-retreat position is known and CarryNext can
            // be labelled. decideHyphenBreak makes the greedy baseline obey the
            // last-resort hyphenation rule (whole-word unless over-long/太松).
            val rawGreedyEnd = findGreedyEnd(
                adjustedClusters,
                lineStart,
                lineLimit(maxWidth, firstLineIndent, lineStart),
                endExclusive = segmentEndExclusive,
                nonRenderingControlClusters = nonRenderingControlClusters,
            )
            val greedyEnd = adjustBreakForUnbreakables(
                breakAt = decideHyphenBreak(
                    lineStart = lineStart,
                    overflowAt = decideProgressiveBreak(
                        lineStart,
                        rawGreedyEnd,
                        progressiveBreakOpportunities,
                        adjustedClusters,
                        lineLimit(maxWidth, firstLineIndent, lineStart),
                        cjkInterCharBoundaries,
                        maxCjkStretchPerGap,
                        sinoWesternBoundaries,
                        sinoWesternStretchCap,
                    ),
                    adjustedClusters = adjustedClusters,
                    lineLimit = lineLimit(maxWidth, firstLineIndent, lineStart),
                    hyphenBreakClusters = hyphenBreakClusters,
                    cjkInterCharBoundaries = cjkInterCharBoundaries,
                    maxCjkStretchPerGap = maxCjkStretchPerGap,
                    sinoWesternBoundaries = sinoWesternBoundaries,
                    sinoWesternStretchCap = sinoWesternStretchCap,
                ),
                lineStart = lineStart,
                unbreakableRanges = unbreakableRanges,
            )
            if (greedyEnd >= segmentEndExclusive) {
                if (mandatoryEnd != null) {
                    committed += rebuildLine(
                        lineStart..mandatoryEnd,
                        naturalClusters,
                        adjustedClusters,
                        endReason = LineEndReason.MandatoryBreak,
                    )
                    committedDensity = 0f
                    committedSyntheticHyphenRun = 0
                    lineStart = mandatoryEnd + 1
                    if (lineStart == adjustedClusters.size) {
                        committed += emptyLineCandidate(
                            sourceOffset = adjustedClusters.last().range.end,
                            endReason = LineEndReason.ParagraphEnd,
                        )
                    }
                    continue
                }
                committed += rebuildLine(
                    lineStart..adjustedClusters.lastIndex,
                    naturalClusters,
                    adjustedClusters,
                    endReason = LineEndReason.ParagraphEnd,
                )
                break
            }

            // Candidates only shift earlier than greedy. PushIn is evaluated
            // during the repair pass below, where punctuation glue capacity is
            // known and the shrink can be recorded on the chosen line.
            // Breaks inside an unbreakable span are never candidates.
            val candidates = ((greedyEnd - window)..greedyEnd)
                .filter { it in (lineStart + 1)..adjustedClusters.size }
                .filter { it <= segmentEndExclusive }
                .filter { e -> !unbreakableRanges.containsBoundary(e) }
                .filter { e ->
                    progressiveCandidateAllowed(
                        lineStart, rawGreedyEnd, e, progressiveBreakOpportunities,
                        adjustedClusters, lineLimit(maxWidth, firstLineIndent, lineStart),
                        cjkInterCharBoundaries, maxCjkStretchPerGap,
                        sinoWesternBoundaries, sinoWesternStretchCap,
                    )
                }
                .filter { e ->
                    (lineStart until e).any { it !in nonRenderingControlClusters } ||
                        e == segmentEndExclusive
                }
                .distinct()
                // When every windowed candidate sits inside the unbreakable run, do not re-admit
                // the raw greedy break (that is how a `0.5|)` split slipped through): retreat it to
                // before the whole run so the illegal break never survives.
                .ifEmpty { listOf(adjustBreakForUnbreakables(greedyEnd, lineStart, unbreakableRanges)) }

            var bestEnd = greedyEnd
            var bestScore = Float.POSITIVE_INFINITY
            for (e in candidates) {
                val score = scoreCandidate(
                    s = lineStart,
                    e = e,
                    natural = naturalClusters,
                    adjusted = adjustedClusters,
                    maxWidth = maxWidth,
                    shrinkOpportunities = shrinkOpportunities,
                    firstLineIndent = firstLineIndent,
                    hangableClusters = hangableClusters,
                    extendableHangRanges = extendableHangRanges,
                    forbiddenLineStartClusters = forbiddenLineStartClusters,
                    hyphenBreakClusters = hyphenBreakClusters,
                    cjkInterCharBoundaries = cjkInterCharBoundaries,
                    maxCjkStretchPerGap = maxCjkStretchPerGap,
                    sinoWesternBoundaries = sinoWesternBoundaries,
                    sinoWesternStretchCap = sinoWesternStretchCap,
                    segmentEndExclusive = segmentEndExclusive,
                    prevCommittedDensity = committedDensity,
                    prevSyntheticHyphenRun = committedSyntheticHyphenRun,
                    gapBoundaries = gapBoundaries,
                    dRef = dRef,
                    unbreakableRanges = unbreakableRanges,
                    nonRenderingControlClusters = nonRenderingControlClusters,
                    progressiveBreakOpportunities = progressiveBreakOpportunities,
                )
                if (score < bestScore) {
                    bestScore = score
                    bestEnd = e
                }
            }

            // Line-end kinsoku may retreat the chosen break further; the
            // mark moves to the next line (cascade-free shorten).
            val committedEnd = adjustBreakForLineEnd(bestEnd, lineStart, forbiddenLineEndClusters)
            if (committedEnd in hardBreakAfterClusters && lineStart < committedEnd) {
                // MandatoryBreakBindsPreviousLine: a zero-width authored break
                // must terminate the preceding visual line. Lookahead may
                // prefer the width-identical candidate just before the break;
                // committing it literally would leave "\n" as a standalone
                // line and create a bogus blank row.
                committed += rebuildLine(
                    lineStart..committedEnd,
                    naturalClusters,
                    adjustedClusters,
                    endReason = LineEndReason.MandatoryBreak,
                )
                committedDensity = 0f
                committedSyntheticHyphenRun = 0
                lineStart = committedEnd + 1
                if (lineStart == adjustedClusters.size) {
                    committed += emptyLineCandidate(
                        sourceOffset = adjustedClusters.last().range.end,
                        endReason = LineEndReason.ParagraphEnd,
                    )
                }
                continue
            }
            committed += closeFilledLine(
                lineStart..(committedEnd - 1), bestEnd, naturalClusters, adjustedClusters,
            )
            committed.last().let { line ->
                val limit = lineLimit(maxWidth, firstLineIndent, line.clusterRange.first)
                committedDensity = lineAdjustmentDensity(line, limit, isLast = false, gapBoundaries)
                committedSyntheticHyphenRun = if (line.endsWithSyntheticHyphen(hyphenBreakClusters)) {
                    committedSyntheticHyphenRun + 1
                } else {
                    0
                }
            }
            lineStart = committedEnd
        }

        return applyKinsokuRepairs(
            initial = committed,
            naturalClusters = naturalClusters,
            adjustedClusters = adjustedClusters,
            maxWidth = maxWidth,
            kinsoku = kinsoku,
            shrinkOpportunities = shrinkOpportunities,
            pushInPenalty = pushInPenalty,
            carryPreviousPenalty = carryPreviousPenalty,
            leaveRaggedPenalty = leaveRaggedPenalty,
            unbreakableRanges = unbreakableRanges,
            firstLineIndent = firstLineIndent,
            hangableClusters = hangableClusters,
            extendableHangRanges = extendableHangRanges,
            forbiddenLineStartClusters = forbiddenLineStartClusters,
        ).withFillPushIn(
            lineAdjustmentPushIn, naturalClusters, adjustedClusters, maxWidth,
            shrinkOpportunities, firstLineIndent, lineAdjustmentCompressBias,
            forbiddenLineStartClusters, forbiddenLineEndClusters, unbreakableRanges, pushInPenalty,
            gapBoundaries = gapBoundaries,
            progressiveBreakOpportunities = progressiveBreakOpportunities,
        )
    }

    private fun scoreCandidate(
        s: Int,
        e: Int,
        natural: List<Cluster>,
        adjusted: List<Cluster>,
        maxWidth: Float,
        shrinkOpportunities: List<ShrinkOpportunity>,
        firstLineIndent: Float,
        hangableClusters: Set<Int>,
        extendableHangRanges: List<IntRange>,
        forbiddenLineStartClusters: Set<Int>?,
        hyphenBreakClusters: Set<Int>,
        cjkInterCharBoundaries: Set<Int>,
        maxCjkStretchPerGap: Float,
        sinoWesternBoundaries: Set<Int>,
        sinoWesternStretchCap: Float,
        segmentEndExclusive: Int = adjusted.size,
        prevCommittedDensity: Float = 0f,
        prevSyntheticHyphenRun: Int = 0,
        gapBoundaries: Set<Int> = emptySet(),
        dRef: Float = 1f,
        unbreakableRanges: UnbreakableRanges = UnbreakableRanges.Empty,
        nonRenderingControlClusters: Set<Int> = emptySet(),
        progressiveBreakOpportunities: Map<Int, ProgressiveBreakOpportunity> = emptyMap(),
    ): Float {
        val firstLine = rebuildLine(s..(e - 1), natural, adjusted)
        val future = rawGreedyLinesFrom(
            start = e,
            natural = natural,
            adjusted = adjusted,
            maxWidth = maxWidth,
            hyphenBreakClusters = hyphenBreakClusters,
            cjkInterCharBoundaries = cjkInterCharBoundaries,
            maxCjkStretchPerGap = maxCjkStretchPerGap,
            sinoWesternBoundaries = sinoWesternBoundaries,
            sinoWesternStretchCap = sinoWesternStretchCap,
            endExclusive = segmentEndExclusive,
            unbreakableRanges = unbreakableRanges,
            nonRenderingControlClusters = nonRenderingControlClusters,
            progressiveBreakOpportunities = progressiveBreakOpportunities,
            // BoundedLookaheadMaterialization: scoring observes only
            // futureLineHorizon lines. One additional line is sufficient for
            // adjacent kinsoku repair to modify the last scored line.
            maxLines = futureLineHorizon + 1,
        )
        // Apply kinsoku once across [firstLine] + future so both splice
        // conflicts and future-line conflicts are scored with the same PushIn
        // capacity map as the final repair pass.
        val spliced = applyKinsokuRepairs(
            initial = listOf(firstLine) + future,
            naturalClusters = natural,
            adjustedClusters = adjusted,
            maxWidth = maxWidth,
            kinsoku = kinsoku,
            shrinkOpportunities = shrinkOpportunities,
            pushInPenalty = pushInPenalty,
            carryPreviousPenalty = carryPreviousPenalty,
            leaveRaggedPenalty = leaveRaggedPenalty,
            unbreakableRanges = unbreakableRanges,
            firstLineIndent = firstLineIndent,
            hangableClusters = hangableClusters,
            extendableHangRanges = extendableHangRanges,
            forbiddenLineStartClusters = forbiddenLineStartClusters,
        ).lines

        val horizon = (1 + futureLineHorizon).coerceAtMost(spliced.size)
        var score = 0f
        var prevD = prevCommittedDensity
        var syntheticHyphenRun = prevSyntheticHyphenRun
        for (idx in 0 until horizon) {
            val line = spliced[idx]
            val isLast = (idx == spliced.lastIndex)
            score += badness(
                line, maxWidth, isLast, firstLineIndent, prevD, gapBoundaries, dRef,
            )
            // AvoidConsecutiveSyntheticHyphenBreaks: consecutive generated
            // hyphens read choppy. This is only a soft lookahead demerit and
            // only applies to `hyphenBreakClusters`; clean breaks at existing
            // '-' or CamelCase boundaries are intentionally unaffected.
            if (line.endsWithSyntheticHyphen(hyphenBreakClusters)) {
                score += consecutiveSyntheticHyphenPenalty * syntheticHyphenRun
                syntheticHyphenRun += 1
            } else {
                syntheticHyphenRun = 0
            }
            val limit = lineLimit(maxWidth, firstLineIndent, line.clusterRange.first)
            prevD = lineAdjustmentDensity(line, limit, isLast, gapBoundaries)
        }
        return score
    }

    private fun rawGreedyLinesFrom(
        start: Int,
        natural: List<Cluster>,
        adjusted: List<Cluster>,
        maxWidth: Float,
        hyphenBreakClusters: Set<Int>,
        cjkInterCharBoundaries: Set<Int>,
        maxCjkStretchPerGap: Float,
        sinoWesternBoundaries: Set<Int>,
        sinoWesternStretchCap: Float,
        endExclusive: Int = adjusted.size,
        unbreakableRanges: UnbreakableRanges = UnbreakableRanges.Empty,
        nonRenderingControlClusters: Set<Int> = emptySet(),
        maxLines: Int = Int.MAX_VALUE,
        progressiveBreakOpportunities: Map<Int, ProgressiveBreakOpportunity> = emptyMap(),
    ): List<LineCandidate> {
        if (start >= endExclusive) return emptyList()
        require(maxLines > 0) { "maxLines must be positive" }

        val lines = mutableListOf<LineCandidate>()
        var lineStart = start
        var adjustedAccum = 0f
        var hasRenderingContent = false

        var i = start
        while (i < endExclusive) {
            val nextAdjusted = adjustedAccum + adjusted[i].advance
            val overflows = nextAdjusted > maxWidth && hasRenderingContent
            if (overflows) {
                // Honest futures (ADR 0038): simulated lines obey the same
                // unbreakable groups as committed ones — a candidate must not
                // win by pretending 示亡号/数字组 can split downstream.
                val breakAt = adjustBreakForUnbreakables(
                    breakAt = decideHyphenBreak(
                        lineStart,
                        decideProgressiveBreak(
                            lineStart, i, progressiveBreakOpportunities,
                            adjusted, maxWidth,
                            cjkInterCharBoundaries, maxCjkStretchPerGap,
                            sinoWesternBoundaries, sinoWesternStretchCap,
                        ),
                        adjusted, maxWidth,
                        hyphenBreakClusters, cjkInterCharBoundaries, maxCjkStretchPerGap,
                        sinoWesternBoundaries, sinoWesternStretchCap,
                    ),
                    lineStart = lineStart,
                    unbreakableRanges = unbreakableRanges,
                )
                lines += rebuildLine(
                    clusterRange = lineStart..(breakAt - 1),
                    naturalClusters = natural,
                    adjustedClusters = adjusted,
                )
                if (lines.size >= maxLines) return lines
                lineStart = breakAt
                adjustedAccum = adjusted[breakAt].advance
                hasRenderingContent = breakAt !in nonRenderingControlClusters
                i = breakAt + 1
            } else {
                adjustedAccum = nextAdjusted
                if (i !in nonRenderingControlClusters) hasRenderingContent = true
                i += 1
            }
        }

        lines += rebuildLine(
            clusterRange = lineStart..(endExclusive - 1),
            naturalClusters = natural,
            adjustedClusters = adjusted,
            endReason = LineEndReason.ParagraphEnd,
        )
        return lines
    }

    private fun badness(
        line: LineCandidate,
        maxWidth: Float,
        isLast: Boolean,
        firstLineIndent: Float,
        prevDensity: Float,
        gapBoundaries: Set<Int>,
        dRef: Float,
    ): Float {
        // NeighborAmortizedAdjustment (ADR 0038): price the POST-JUSTIFY state.
        // A deficit on a line WITH stretchable gaps becomes spacing — priced by
        // the convex density term (+ neighbor difference), so spreading small
        // amounts over many gaps is near-free and concentration is punished.
        // A deficit on a gapless line CANNOT fill — it stays a ragged edge and
        // keeps the linear price (which is also the exact pre-0038 contract for
        // standalone breaker use, where no gap sets are provided).
        val limit = lineLimit(maxWidth, firstLineIndent, line.clusterRange.first)
        val ragged = if (isLast) 0f else (limit - line.adjustedWidth).coerceAtLeast(0f)
        val inMeasureRange = line.inMeasureClusterRange
        val gaps = lineGapCount(inMeasureRange, gapBoundaries)
        val residual = if (gaps == 0) ragged else 0f
        val d = lineAdjustmentDensity(line, limit, isLast, gapBoundaries)
        // SingleClusterLinePenalty: 孤字行(非末行单 cluster)是排版忌讳——在
        // 窄测下它可能只比「密拉伸」便宜几分,显式罚分让它只作最后手段。
        val orphan = if (!isLast && !inMeasureRange.isEmptyClusterRange() &&
            inMeasureRange.first == inMeasureRange.last
        ) {
            leaveRaggedPenalty.toFloat()
        } else {
            0f
        }
        return residual * raggednessWeight + orphan +
            amortizedAdjustmentCost(d, prevDensity, dRef) * raggednessWeight +
            (line.repair?.penalty ?: 0).toFloat()
    }
}

private fun LineCandidate.endsWithSyntheticHyphen(hyphenBreakClusters: Set<Int>): Boolean =
    endReason == LineEndReason.AutoWrap &&
        !clusterRange.isEmptyClusterRange() &&
        clusterRange.last + 1 in hyphenBreakClusters

internal fun LineCandidate.endsWithProgressiveBreak(
    opportunities: Map<Int, ProgressiveBreakOpportunity>,
): Boolean = endReason == LineEndReason.AutoWrap &&
    !clusterRange.isEmptyClusterRange() && clusterRange.last + 1 in opportunities

// NeighborAmortizedAdjustment (ADR 0038): per-line SIGNED adjustment density —
// stretch +, compression −, in px per justification gap. The visible quantity a
// reader perceives is per-gap spacing change, so costs are priced on it, convex
// (d²: two half-adjusted lines beat one fully-adjusted line) plus a neighbor
// difference term ((dᵢ−dᵢ₋₁)²: no tight line pressed against a loose line).
// 末行与 MandatoryBreak 行 d=0(段末自然收束,不参与均摊)。
internal fun lineGapCount(range: IntRange, gapBoundaries: Set<Int>): Int {
    if (range.isEmptyClusterRange()) return 0
    var n = 0
    for (i in range.first until range.last) if (i in gapBoundaries) n += 1
    return n
}

internal fun lineAdjustmentDensity(
    line: LineCandidate,
    limit: Float,
    isLast: Boolean,
    gapBoundaries: Set<Int>,
): Float {
    if (isLast || line.endReason != LineEndReason.AutoWrap) return 0f
    // Stretch density (justify fill). Compression does not surface here: pushed-in
    // lines sit at ~limit and carry their repair penalty; the fill GATE prices the
    // compression side explicitly (−overflow/gaps) when deciding the pull.
    // A line with ZERO stretchable gaps has density 0 — its deficit is plain
    // raggedness (priced linearly), not visible spacing; max(1,…) here would
    // fabricate a huge density that poisons neighbors into matching it.
    val gaps = lineGapCount(line.inMeasureClusterRange, gapBoundaries)
    if (gaps == 0) return 0f
    val delta = (limit - line.adjustedWidth).coerceAtLeast(0f)
    return delta / gaps
}

/** Convex fill term + neighbor-difference term, normalized back to px by [dRef]. */
internal fun amortizedAdjustmentCost(d: Float, prevD: Float, dRef: Float): Float {
    val ref = dRef.coerceAtLeast(1f)
    val diff = d - prevD
    return (d * d + diff * diff) / ref
}

internal fun rebuildLine(
    clusterRange: IntRange,
    naturalClusters: List<Cluster>,
    adjustedClusters: List<Cluster>,
    endReason: LineEndReason = LineEndReason.AutoWrap,
    repair: RepairOption? = null,
    repairCandidates: List<RepairCandidate> = emptyList(),
): LineCandidate {
    require(!clusterRange.isEmptyClusterRange()) { "Use emptyLineCandidate for an empty line." }
    var natural = 0f
    var adjusted = 0f
    for (idx in clusterRange) {
        natural += naturalClusters[idx].advance
        adjusted += adjustedClusters[idx].advance
    }
    return LineCandidate(
        clusterRange = clusterRange,
        sourceRange = TextRange(
            adjustedClusters[clusterRange.first].range.start,
            adjustedClusters[clusterRange.last].range.end,
        ),
        naturalWidth = natural,
        adjustedWidth = adjusted,
        endReason = endReason,
        repair = repair,
        repairCandidates = repairCandidates,
    )
}

internal fun emptyLineCandidate(
    sourceOffset: Int,
    endReason: LineEndReason = LineEndReason.ParagraphEnd,
): LineCandidate =
    LineCandidate(
        clusterRange = EMPTY_CLUSTER_RANGE,
        sourceRange = TextRange(sourceOffset, sourceOffset),
        naturalWidth = 0f,
        adjustedWidth = 0f,
        endReason = endReason,
    )

internal fun IntRange.isEmptyClusterRange(): Boolean = first > last

private val EMPTY_CLUSTER_RANGE: IntRange = 1..0

internal fun findGreedyEnd(
    adjustedClusters: List<Cluster>,
    start: Int,
    maxWidth: Float,
    endExclusive: Int = adjustedClusters.size,
    nonRenderingControlClusters: Set<Int> = emptySet(),
): Int {
    var accum = 0f
    var i = start
    var hasRenderingContent = false
    while (i < endExclusive) {
        val next = accum + adjustedClusters[i].advance
        if (next > maxWidth && hasRenderingContent) return i
        accum = next
        if (i !in nonRenderingControlClusters) hasRenderingContent = true
        i += 1
    }
    return endExclusive
}
