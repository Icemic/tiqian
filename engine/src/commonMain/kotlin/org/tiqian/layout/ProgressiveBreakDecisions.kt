package org.tiqian.layout

import org.tiqian.core.Cluster
import org.tiqian.core.LineEndReason
import org.tiqian.core.TextRange

/** Ordered fallback tier for a break inside one progressive technical span. */
enum class ProgressiveBreakTier(val priority: Int) {
    Whitespace(0),
    Structural(1),
    Syllable(2),
    WholeToken(3),
    Emergency(4),
}

/** One cluster boundary exposed by a [org.tiqian.core.LineBreakSpan]. */
data class ProgressiveBreakOpportunity(
    val tier: ProgressiveBreakTier,
    val spanRange: TextRange,
    /** Bounded positive glue owned by the source whitespace immediately before this boundary. */
    val precedingWhitespaceStretchCapacity: Float = 0f,
)

/**
 * `ProgressiveTechnicalBreakSelection`: while overflow is inside one technical
 * span, choose the rightmost fitting boundary from its best available tier.
 */
internal fun decideProgressiveBreak(
    lineStart: Int,
    overflowAt: Int,
    opportunities: Map<Int, ProgressiveBreakOpportunity>,
    adjustedClusters: List<Cluster>? = null,
    lineLimit: Float = Float.POSITIVE_INFINITY,
    cjkInterCharBoundaries: Set<Int> = emptySet(),
    maxCjkStretchPerGap: Float = Float.POSITIVE_INFINITY,
    sinoWesternBoundaries: Set<Int> = emptySet(),
    sinoWesternStretchCap: Float = 0f,
): Int {
    val active = opportunities[overflowAt] ?: return overflowAt
    val bestPriority = progressiveBreakPriorityForLine(
        lineStart = lineStart,
        overflowAt = overflowAt,
        active = active,
        opportunities = opportunities,
        adjustedClusters = adjustedClusters,
        lineLimit = lineLimit,
        cjkInterCharBoundaries = cjkInterCharBoundaries,
        maxCjkStretchPerGap = maxCjkStretchPerGap,
        sinoWesternBoundaries = sinoWesternBoundaries,
        sinoWesternStretchCap = sinoWesternStretchCap,
    )
    var bestBoundary: Int? = null
    for (boundary in (lineStart + 1)..overflowAt) {
        val opportunity = opportunities[boundary] ?: continue
        if (opportunity.spanRange != active.spanRange) continue
        if (opportunity.tier.priority == bestPriority && (bestBoundary == null || boundary > bestBoundary)) {
            bestBoundary = boundary
        }
    }
    return bestBoundary ?: overflowAt
}

/**
 * Technical text has lower-tier clean breaks that ordinary Western words do not. A clean tier is
 * retained only when bounded spacing resources fill the line without tracking; otherwise continue
 * through syllable to the rightmost emergency cut before adding any letter spacing.
 */
internal const val PROGRESSIVE_TECHNICAL_VISIBLE_STRETCH_FRACTION = 0f

internal fun progressiveCandidateAllowed(
    lineStart: Int,
    rawGreedy: Int,
    candidateEnd: Int,
    opportunities: Map<Int, ProgressiveBreakOpportunity>,
    adjustedClusters: List<Cluster>? = null,
    lineLimit: Float = Float.POSITIVE_INFINITY,
    cjkInterCharBoundaries: Set<Int> = emptySet(),
    maxCjkStretchPerGap: Float = Float.POSITIVE_INFINITY,
    sinoWesternBoundaries: Set<Int> = emptySet(),
    sinoWesternStretchCap: Float = 0f,
): Boolean {
    val active = opportunities[rawGreedy] ?: return true
    val candidate = opportunities[candidateEnd] ?: run {
        val sourceOffset = adjustedClusters?.getOrNull(candidateEnd)?.range?.start ?: return true
        return sourceOffset <= active.spanRange.start || sourceOffset >= active.spanRange.end
    }
    if (candidate.spanRange != active.spanRange) return true
    if (candidateEnd > rawGreedy) {
        // Paragraph-DP contributes these only as measured compression edges. Reaching a same-tier
        // or cleaner boundary past natural fit fills the line rather than creating the early-break
        // tracking this guard prevents.
        return candidate.tier.priority <= active.tier.priority
    }
    // `ProgressiveTechnicalRightmostTierBoundary`: lookahead/paragraph-DP may compare a whole-token
    // wrap before the active span, but once a line breaks inside that span they must replay the one
    // boundary selected by the tier policy. Letting them score earlier boundaries from the same
    // tier can trade a huge current-line tracking deficit for a cosmetically smoother next line.
    val selectedBoundary = decideProgressiveBreak(
        lineStart = lineStart,
        overflowAt = rawGreedy,
        opportunities = opportunities,
        adjustedClusters = adjustedClusters,
        lineLimit = lineLimit,
        cjkInterCharBoundaries = cjkInterCharBoundaries,
        maxCjkStretchPerGap = maxCjkStretchPerGap,
        sinoWesternBoundaries = sinoWesternBoundaries,
        sinoWesternStretchCap = sinoWesternStretchCap,
    )
    return candidateEnd == selectedBoundary
}

/**
 * `ProgressiveTechnicalStretchBoundedTierFallback`: source whitespace is considered first, then
 * structural/camel boundaries; a tier may not force the line's normal paragraph opportunities past the same per-gap
 * stretch ceiling used by Western last-resort hyphenation. In that case the next clean tier is
 * admitted. The final line still uses the ordinary Justifier; this helper only chooses a break.
 */
private fun progressiveBreakPriorityForLine(
    lineStart: Int,
    overflowAt: Int,
    active: ProgressiveBreakOpportunity,
    opportunities: Map<Int, ProgressiveBreakOpportunity>,
    adjustedClusters: List<Cluster>?,
    lineLimit: Float,
    cjkInterCharBoundaries: Set<Int>,
    maxCjkStretchPerGap: Float,
    sinoWesternBoundaries: Set<Int>,
    sinoWesternStretchCap: Float,
): Int {
    val priorities = (lineStart + 1..overflowAt)
        .mapNotNull { opportunities[it] }
        .filter { it.spanRange == active.spanRange }
        .map { it.tier.priority }
        .distinct()
        .sorted()
    if (priorities.isEmpty()) return active.tier.priority
    if (adjustedClusters == null || !lineLimit.isFinite() || !maxCjkStretchPerGap.isFinite()) {
        return priorities.first()
    }
    val progressiveStretchLimit =
        maxCjkStretchPerGap * PROGRESSIVE_TECHNICAL_VISIBLE_STRETCH_FRACTION
    var leastLoosePriority = priorities.first()
    var leastLooseDensity = Float.POSITIVE_INFINITY
    var leastLooseBoundary = lineStart + 1
    for (priority in priorities) {
        val boundary = (lineStart + 1..overflowAt).lastOrNull { candidate ->
            opportunities[candidate]?.let {
                it.spanRange == active.spanRange && it.tier.priority == priority
            } == true
        } ?: continue
        val density = progressiveCandidateStretchDensity(
            lineStart = lineStart,
            boundary = boundary,
            opportunities = opportunities,
            adjustedClusters = adjustedClusters,
            lineLimit = lineLimit,
            cjkInterCharBoundaries = cjkInterCharBoundaries,
            sinoWesternBoundaries = sinoWesternBoundaries,
            sinoWesternStretchCap = sinoWesternStretchCap,
        )
        if (density < leastLooseDensity) {
            leastLooseDensity = density
            leastLoosePriority = priority
            leastLooseBoundary = boundary
        }
        if (density <= progressiveStretchLimit) return priority
    }
    // Every clean tier is visibly loose. Prefer Emergency immediately when its exposed boundary is
    // at least as far right as the best clean boundary. If the clean boundary itself is farther
    // right, return it for this pass so the exact post-trim plan can reject that tier; shaping then
    // re-exposes the same physical boundary as Emergency. This avoids retreating to an earlier hard
    // cut merely because clean endpoints were excluded from the initial Emergency pieces.
    val emergencyBoundary = (lineStart + 1..overflowAt).lastOrNull { candidate ->
        opportunities[candidate]?.let {
            it.spanRange == active.spanRange && it.tier == ProgressiveBreakTier.Emergency
        } == true
    }
    return if (emergencyBoundary != null && emergencyBoundary >= leastLooseBoundary) {
        ProgressiveBreakTier.Emergency.priority
    } else {
        leastLoosePriority
    }
}

private fun progressiveCandidateStretchDensity(
    lineStart: Int,
    boundary: Int,
    opportunities: Map<Int, ProgressiveBreakOpportunity>,
    adjustedClusters: List<Cluster>,
    lineLimit: Float,
    cjkInterCharBoundaries: Set<Int>,
    sinoWesternBoundaries: Set<Int>,
    sinoWesternStretchCap: Float,
): Float {
    var width = 0f
    for (index in lineStart until boundary) width += adjustedClusters[index].advance
    val deficit = (lineLimit - width).coerceAtLeast(0f)
    // `ProgressiveTechnicalWhitespaceBreakPricing`: a Whitespace opportunity at offset k owns
    // the real source-space cluster k - 1. It can fill a later candidate line, but not a line that
    // ends at k (where that space is collapsed as trailing line-edge whitespace).
    val technicalWhitespaceCapacity = (lineStart + 1 until boundary).sumOf { candidate ->
        opportunities[candidate]
            ?.takeIf { it.tier == ProgressiveBreakTier.Whitespace }
            ?.precedingWhitespaceStretchCapacity
            ?.toDouble()
            ?: 0.0
    }.toFloat()
    val sinoWesternGapCount = (lineStart + 1 until boundary).count { it in sinoWesternBoundaries }
    val cjkDeficit = (
        deficit - technicalWhitespaceCapacity - sinoWesternGapCount * sinoWesternStretchCap
        ).coerceAtLeast(0f)
    val activeSpan = opportunities[boundary]?.spanRange
    val terminalTechnicalSourceUnits = if (activeSpan == null) {
        0
    } else {
        (lineStart until boundary).sumOf { index ->
            val cluster = adjustedClusters[index]
            if (
                cluster.range.start >= activeSpan.start && cluster.range.end <= activeSpan.end &&
                cluster.text.none(Char::isWhitespace)
            ) {
                cluster.text.length
            } else {
                0
            }
        }
    }
    // `TerminalTechnicalTrackingDensityEstimate`: once a technical prefix is present at line end,
    // its source-unit gaps—not the unrelated CJK body gaps—are the eventual bounded tracking
    // resource. Progressive technical segmentation is currently Latin/ASCII-oriented, so source
    // UTF-16 units coincide with the grapheme cuts exposed by this policy.
    val terminalTechnicalGapCount = (terminalTechnicalSourceUnits - 1).coerceAtLeast(0)
    if (terminalTechnicalGapCount > 0) return cjkDeficit / terminalTechnicalGapCount
    val cjkGapCount = (lineStart + 1 until boundary).count { it in cjkInterCharBoundaries }
    return if (cjkGapCount == 0) {
        cjkDeficit
    } else {
        cjkDeficit / cjkGapCount
    }
}

/**
 * Where to break given a greedy overflow at [overflowAt] (the first cluster that
 * does not fit). Prefers the last whole-word boundary; takes the hyphenation
 * break (returns [overflowAt]) only when the word is over-long (fills from
 * [lineStart]) or wrapping it whole would stretch the line's 汉字间距 past
 * [maxCjkStretchPerGap]. With no [hyphenBreakClusters] this is a no-op
 * (returns [overflowAt]).
 */
internal fun decideHyphenBreak(
    lineStart: Int,
    overflowAt: Int,
    adjustedClusters: List<Cluster>,
    lineLimit: Float,
    hyphenBreakClusters: Set<Int>,
    cjkInterCharBoundaries: Set<Int>,
    maxCjkStretchPerGap: Float,
    sinoWesternBoundaries: Set<Int> = emptySet(),
    sinoWesternStretchCap: Float = 0f,
): Int {
    if (overflowAt !in hyphenBreakClusters) return overflowAt // overflow at a word boundary
    var wholeWordEnd = overflowAt
    while (wholeWordEnd > lineStart && wholeWordEnd in hyphenBreakClusters) wholeWordEnd -= 1
    if (wholeWordEnd <= lineStart) return overflowAt // over-long word fills from lineStart: must hyphenate
    var width = 0f
    for (k in lineStart until wholeWordEnd) width += adjustedClusters[k].advance
    val deficit = lineLimit - width
    if (deficit <= 0f) return wholeWordEnd
    // CLREQ 拉伸顺序：中西间距先于汉字间距吸收 deficit。扣掉中西间距能吸收的
    // 部分，剩下的才是真正落到汉字间距上的增量（词距是二分空、已在 cap，不吸收）。
    val sinoWestern = (lineStart + 1 until wholeWordEnd).count { it in sinoWesternBoundaries }
    val cjkDeficit = (deficit - sinoWestern * sinoWesternStretchCap).coerceAtLeast(0f)
    if (cjkDeficit <= 0f) return wholeWordEnd
    val gaps = (lineStart + 1 until wholeWordEnd).count { it in cjkInterCharBoundaries }
    val tooLoose = gaps == 0 || cjkDeficit / gaps > maxCjkStretchPerGap
    return if (tooLoose) overflowAt else wholeWordEnd
}

/**
 * CLREQ 行尾禁则 break retreat: returns a break ≤ [breakAt] such that the
 * line `[lineStart, result)` does not end on a forbidden-at-line-end mark.
 * Retreats past consecutive trailing marks; never empties the line (keeps
 * ≥1 cluster), so the rare all-forbidden tail keeps the violation.
 */
internal fun adjustBreakForLineEnd(
    breakAt: Int,
    lineStart: Int,
    forbiddenLineEndClusters: Set<Int>,
): Int {
    var b = breakAt
    while (b - 1 > lineStart && (b - 1) in forbiddenLineEndClusters) b -= 1
    return b
}

/** Usable measure of a line starting at [lineStartCluster] (段首缩进). */
internal fun lineLimit(maxWidth: Float, firstLineIndent: Float, lineStartCluster: Int): Float =
    if (lineStartCluster == 0) maxWidth - firstLineIndent else maxWidth

/**
 * One shrinkable resource on a cluster (ADR 0020 + 2026-06-13 amendment,
 * CLREQ 挤压处理优先顺序七档):
 *
 * - tier 1 — 行末标点削半宽（offender 自身 trailing glue，repair 时晋升）
 * - tier 2 — 西文词距，最小压至 1/4em
 * - tier 3 — 间隔号/居中类，两侧同时等量，压至 0
 * - tier 4 — 夹注符号外侧：开括号/开引号前侧、闭括号/闭引号后侧
 * - tier 5 — 行内逗、顿、分号 trailing glue（冒号等未尽列者同档兜底）
 * - tier 6 — 中西间距，最小压至 1/8em（风格开关可禁）
 * - tier 7 — 行内句号/问号/感叹号 trailing glue（风格开关可禁）
 * - tier 8 — inline-object provider 明确暴露的边界空白（例如公式内部运算符断点处的数学间距）
 */
data class ShrinkOpportunity(
    val clusterIndex: Int,
    val tier: Int,
    val capacity: Float,
    val channel: ShrinkChannel,
    /**
     * Usable only when this cluster becomes the merged line's END (tier-1
     * promotion). Used when `allowInlineStopCompression` is off: 行内句问叹
     * keep full width, but 行末削半 (a different CLREQ rule) still applies.
     */
    val lineEndOnly: Boolean = false,
)

enum class ShrinkChannel {
    /** Consume the punctuation atom's trailing glue. */
    TrailingGlue,

    /**
     * Consume the punctuation atom's LEADING glue (开夹注符号前侧，CLREQ
     * 挤压④). Renderers shift the glyph origin left by the consumed amount
     * (ADR 0017 amendment).
     */
    LeadingGlue,

    /** Consume a centred punctuation frame's leading and trailing glue equally. */
    LeadingAndTrailingGlue,

    /** Reduce the cluster's raw advance (word spaces, gap clusters). */
    RawAdvance,
}

/**
 * Moves [breakAt] out of the unbreakable span it falls strictly inside of, retreating to before
 * the whole **contiguous run** of unbreakable ranges (their closure), provided the line keeps at
 * least one cluster. A long inline object — e.g. per-atom math split into many adjacent `C..C+1`
 * ranges — forms such a chain, and stepping back a single range at a time can land inside the
 * previous one. Returns [breakAt] unchanged in the give-up case where the run reaches the line
 * start (the run is wider than the line; split fallback).
 */
/**
 * Unbreakable cluster ranges from all sources (mourning, pinyin, attached inline, number-symbol,
 * punctuation kinsoku, ...). Containment answers through a sorted-start / prefix-max-last index
 * in O(log n); a contained candidate falls back to the original list-order scan. Consumers
 * re-scan to a fixed point, so list order only decides which containing range is seen first,
 * never the converged result. A per-break full scan made line breaking quadratic on
 * punctuation-dense pathological paragraphs.
 */
class UnbreakableRanges(val ranges: List<IntRange>) {
    private val byStart = ranges.sortedBy { it.first }
    private val startsSorted = IntArray(byStart.size) { byStart[it].first }
    private val prefixMaxLast = IntArray(byStart.size).also { maxLast ->
        var running = Int.MIN_VALUE
        byStart.forEachIndexed { index, range ->
            running = maxOf(running, range.last)
            maxLast[index] = running
        }
    }

    /** Whether any range contains the boundary: `candidate > first && candidate <= last`. */
    fun containsBoundary(candidate: Int): Boolean {
        var low = 0
        var high = startsSorted.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (startsSorted[mid] < candidate) low = mid + 1 else high = mid
        }
        return low > 0 && prefixMaxLast[low - 1] >= candidate
    }

    /** First containing range in source priority order, or null. */
    fun containingOrNull(candidate: Int): IntRange? {
        if (!containsBoundary(candidate)) return null
        return ranges.firstOrNull { candidate > it.first && candidate <= it.last }
    }

    /** First range with `first <= index && last > index` in source priority order, or null. */
    fun containingFromClosedStartOrNull(index: Int): IntRange? {
        var low = 0
        var high = startsSorted.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (startsSorted[mid] <= index) low = mid + 1 else high = mid
        }
        if (low == 0 || prefixMaxLast[low - 1] <= index) return null
        return ranges.firstOrNull { index in it && it.last > index }
    }

    companion object {
        val Empty = UnbreakableRanges(emptyList())
    }
}

internal fun adjustBreakForUnbreakables(
    breakAt: Int,
    lineStart: Int,
    unbreakableRanges: UnbreakableRanges,
): Int {
    var candidate = breakAt
    while (true) {
        val containing = unbreakableRanges.containingOrNull(candidate)
            ?: return candidate
        if (containing.first <= lineStart) return breakAt
        candidate = containing.first
    }
}
