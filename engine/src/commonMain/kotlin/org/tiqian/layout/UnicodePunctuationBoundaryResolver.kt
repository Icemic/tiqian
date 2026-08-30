package org.tiqian.layout

import org.tiqian.core.Cluster
import org.tiqian.core.ContextualKinsokuDecisionInfo
import org.tiqian.core.EastAsianSpacingEdges
import org.tiqian.core.EastAsianSpacingValue
import org.tiqian.core.InlineAttachment
import org.tiqian.font.FontRole
import org.tiqian.linebreak.UnicodePunctuationLineBreak
import org.tiqian.linebreak.UnicodePunctuationLineBreakClass
import org.tiqian.linebreak.isMandatoryBreakCodePoint
import org.tiqian.linebreak.isZeroWidthSpaceCodePoint

/**
 * Font-independent Western punctuation boundaries from the tailorable part of
 * UAX #14. CJK punctuation continues through the selected CLREQ profile; this
 * resolver closes the previous hole where a shared quote or ASCII bracket lost
 * all boundary semantics merely because it used a Latin face.
 */
internal data class UnicodePunctuationBoundaries(
    val forbiddenLineStartClusters: Set<Int>,
    val forbiddenLineEndClusters: Set<Int>,
    val unbreakableRanges: List<IntRange>,
    val decisions: List<ContextualKinsokuDecisionInfo>,
)

/**
 * Named policy: `WesternBracketCjkInterChar`.
 *
 * ASCII and other Western brackets retain their Latin face and proportional
 * advance, but a bracket directly touching CJK body text still forms an
 * ordinary character-spacing position for CLREQ tier-3 equal expansion. This
 * is deliberately independent of line breaking: [resolveUnicodePunctuationBoundaries]
 * continues to keep opening brackets off line ends and closing brackets off
 * line starts.
 *
 * The returned indices identify the cluster on the left of each boundary.
 */
internal fun resolveWesternBracketCjkInterCharBoundaries(
    text: String,
    clusters: List<Cluster>,
    clusterRoles: List<FontRole>,
): Set<Int> = buildSet {
    for (leftIndex in 0 until clusters.lastIndex) {
        val rightIndex = leftIndex + 1
        if (
            isWesternBracketCjkInterCharBoundary(
                text = text,
                clusters = clusters,
                clusterRoles = clusterRoles,
                leftIndex = leftIndex,
                rightIndex = rightIndex,
            )
        ) {
            add(leftIndex)
        }
    }
}

internal data class AttachedInlineVirtualBoundary(
    val previousClusterIndex: Int,
    val attachedClusterRange: IntRange,
    val nextClusterIndex: Int?,
)

/**
 * `AttachedInlineVirtualAdjacency`: an attached run is ignored only while deciding
 * the boundary spacing of the prose around it. Source order, shaping and glyph
 * geometry stay untouched. The resulting boundary is physically owned by the end
 * of the attached run so no blank is left before the attachment.
 */
internal fun resolveAttachedInlineVirtualBoundaries(
    inlineAttachments: List<InlineAttachment>,
): List<AttachedInlineVirtualBoundary> = buildList {
    var index = 0
    while (index < inlineAttachments.size) {
        if (inlineAttachments[index] != InlineAttachment.Previous) {
            index += 1
            continue
        }
        val start = index
        var end = start
        while (
            end + 1 < inlineAttachments.size &&
            inlineAttachments[end + 1] == InlineAttachment.Previous
        ) {
            end += 1
        }
        if (start > 0) {
            add(
                AttachedInlineVirtualBoundary(
                    previousClusterIndex = start - 1,
                    attachedClusterRange = start..end,
                    nextClusterIndex = (end + 1).takeIf { it < inlineAttachments.size },
                ),
            )
        }
        index = end + 1
    }
}

internal data class AttachedInlineInterCharBoundaries(
    val ordinaryWesternBoundaryAfterClusters: Set<Int>,
    /** Both physical edges touching an attached run; neither is a prose boundary. */
    val suppressedPhysicalBoundaryAfterClusters: Set<Int>,
    /** Target edge after the attached run -> virtual prose cluster on its left. */
    val virtualBoundaryAfterClusters: Map<Int, Int>,
    /** Subset of [virtualBoundaryAfterClusters] that is a virtual W/N boundary. */
    val virtualSinoWesternBoundaryAfterClusters: Set<Int>,
)

/**
 * Resolves final-tier inter-character opportunities from the prose that would be
 * adjacent if attached runs were absent. This deliberately does not infer a target
 * merely because a physical edge before the attachment happened to be stretchable.
 */
internal fun resolveAttachedInlineInterCharBoundaries(
    text: String,
    clusters: List<Cluster>,
    clusterRoles: List<FontRole>,
    eastAsianSpacingEdges: List<EastAsianSpacingEdges>,
    westernBoundaryAfterClusters: Set<Int>,
    inlineAttachments: List<InlineAttachment>,
): AttachedInlineInterCharBoundaries {
    require(clusters.size == clusterRoles.size && clusters.size == eastAsianSpacingEdges.size) {
        "Clusters, roles and East_Asian_Spacing edges must align."
    }
    require(clusters.size == inlineAttachments.size) {
        "Inline attachments must align with clusters."
    }

    val virtualBoundaries = resolveAttachedInlineVirtualBoundaries(inlineAttachments)
    val suppressedPhysical = buildSet {
        virtualBoundaries.forEach { boundary ->
            add(boundary.previousClusterIndex)
            if (boundary.nextClusterIndex != null) add(boundary.attachedClusterRange.last)
        }
    }
    val ordinaryWestern = westernBoundaryAfterClusters - suppressedPhysical
    val virtual = mutableMapOf<Int, Int>()
    val virtualSinoWestern = mutableSetOf<Int>()
    virtualBoundaries.forEach { boundary ->
        val nextIndex = boundary.nextClusterIndex ?: return@forEach
        val previousIndex = boundary.previousClusterIndex
        val leftRole = clusterRoles[previousIndex]
        val rightRole = clusterRoles[nextIndex]
        val bothCjk = leftRole.isCjkLike() && rightRole.isCjkLike()
        val punctuationWestern =
            (leftRole == FontRole.CjkPunctuation &&
                eastAsianSpacingEdges[nextIndex].leading == EastAsianSpacingValue.Narrow) ||
                (eastAsianSpacingEdges[previousIndex].trailing == EastAsianSpacingValue.Narrow &&
                    rightRole == FontRole.CjkPunctuation)
        val sinoWestern = eastAsianSpacingEdges[previousIndex].trailing
            .isWideNarrowPairWith(eastAsianSpacingEdges[nextIndex].leading)
        val westernBracket = isWesternBracketCjkInterCharBoundary(
            text = text,
            clusters = clusters,
            clusterRoles = clusterRoles,
            leftIndex = previousIndex,
            rightIndex = nextIndex,
        )
        if (bothCjk || punctuationWestern || sinoWestern || westernBracket) {
            virtual[boundary.attachedClusterRange.last] = previousIndex
        }
        if (sinoWestern) virtualSinoWestern += boundary.attachedClusterRange.last
    }
    return AttachedInlineInterCharBoundaries(
        ordinaryWesternBoundaryAfterClusters = ordinaryWestern,
        suppressedPhysicalBoundaryAfterClusters = suppressedPhysical,
        virtualBoundaryAfterClusters = virtual,
        virtualSinoWesternBoundaryAfterClusters = virtualSinoWestern,
    )
}

private fun FontRole.isCjkLike(): Boolean =
    this == FontRole.CjkText || this == FontRole.CjkPunctuation

private fun EastAsianSpacingValue.isWideNarrowPairWith(other: EastAsianSpacingValue): Boolean =
    (this == EastAsianSpacingValue.Wide && other == EastAsianSpacingValue.Narrow) ||
        (this == EastAsianSpacingValue.Narrow && other == EastAsianSpacingValue.Wide)

private fun isWesternBracketCjkInterCharBoundary(
    text: String,
    clusters: List<Cluster>,
    clusterRoles: List<FontRole>,
    leftIndex: Int,
    rightIndex: Int,
): Boolean {
    val leftIsWesternBracket =
        clusterRoles.getOrNull(leftIndex) != FontRole.CjkPunctuation &&
            text.substring(clusters[leftIndex].range.start, clusters[leftIndex].range.end)
                .lastSignificantCodePoint()
                ?.codePoint
                ?.isWesternBracketCodePoint() == true
    val rightIsWesternBracket =
        clusterRoles.getOrNull(rightIndex) != FontRole.CjkPunctuation &&
            text.substring(clusters[rightIndex].range.start, clusters[rightIndex].range.end)
                .firstSignificantCodePoint()
                ?.codePoint
                ?.isWesternBracketCodePoint() == true
    val leftIsCjkBody = clusterRoles.getOrNull(leftIndex) == FontRole.CjkText
    val rightIsCjkBody = clusterRoles.getOrNull(rightIndex) == FontRole.CjkText
    return (leftIsWesternBracket && rightIsCjkBody) || (leftIsCjkBody && rightIsWesternBracket)
}

private fun Int.isWesternBracketCodePoint(): Boolean =
    UnicodePunctuationLineBreak.classOf(this) in WESTERN_BRACKET_LINE_BREAK_CLASSES

private val WESTERN_BRACKET_LINE_BREAK_CLASSES = setOf(
    UnicodePunctuationLineBreakClass.OpenPunctuation,
    UnicodePunctuationLineBreakClass.ClosePunctuation,
    UnicodePunctuationLineBreakClass.CloseParenthesis,
)

/**
 * Named policy: `Uax14WesternPunctuationBoundary`.
 *
 * Implemented UAX #14 punctuation rules:
 * - LB13 / LB15d: Western closing, terminal and infix punctuation cannot
 *   begin a wrapped line. SY/BA/HY/NS/IN stay with the existing URL, Western
 *   token and CJK-tailoring policies rather than receiving a second truth here.
 * - LB14: Western opening punctuation cannot end a wrapped line.
 * - LB15a/LB15b/LB19 tailoring: structurally paired quotes and Unicode Pi/Pf quote
 *   direction keep their respective following/preceding content. Unresolved
 *   straight/ornamental quotes keep both sides. U+2019 is resolved as an
 *   initial elision, final apostrophe, or in-word apostrophe from its neighbors.
 *
 * The policy is intentionally narrower than the complete Unicode Line Breaking
 * Algorithm. Numeric, word, combining-mark and script-specific rules remain in
 * their existing pipeline stages.
 */
internal fun resolveUnicodePunctuationBoundaries(
    text: String,
    clusters: List<Cluster>,
    clusterRoles: List<FontRole>,
    quotePairs: List<QuotePair>,
): UnicodePunctuationBoundaries {
    val pairedOpenOffsets = quotePairs.mapTo(mutableSetOf()) { it.openIndex }
    val pairedCloseOffsets = quotePairs.mapTo(mutableSetOf()) { it.closeIndex }
    val forbiddenLineStart = mutableSetOf<Int>()
    val forbiddenLineEnd = mutableSetOf<Int>()
    val unbreakableRanges = mutableListOf<IntRange>()
    val decisions = mutableListOf<ContextualKinsokuDecisionInfo>()

    clusters.forEachIndexed { index, cluster ->
        // CLREQ owns CJK punctuation tailoring, including KinsokuLevel.None.
        // Western/shared punctuation must not lose its own UAX boundary rules
        // merely because font selection resolved it to another face.
        if (clusterRoles.getOrNull(index) == FontRole.CjkPunctuation) return@forEachIndexed
        if (cluster.range.isEmpty) return@forEachIndexed

        val source = text.substring(cluster.range.start, cluster.range.end)
        // Shaping may keep adjacent Western spaces in the same cluster as the
        // punctuation (`(  ` / `  )`). UAX rules are expressed with SP*, so
        // inspect the first/last non-space code point instead of the raw range
        // edges or the boundary disappears precisely in those standard forms.
        val firstSignificant = source.firstSignificantCodePoint() ?: return@forEachIndexed
        val lastSignificant = source.lastSignificantCodePoint() ?: return@forEachIndexed
        val firstCodePoint = firstSignificant.codePoint
        val lastCodePoint = lastSignificant.codePoint
        val firstOffset = cluster.range.start + firstSignificant.offset
        val lastOffset = cluster.range.start + lastSignificant.offset
        val firstClass = UnicodePunctuationLineBreak.classOf(firstCodePoint)
        val lastClass = UnicodePunctuationLineBreak.classOf(lastCodePoint)
        val firstQuoteDirection = text.quoteDirectionAt(firstOffset, firstCodePoint, firstClass)
        val lastQuoteDirection = text.quoteDirectionAt(lastOffset, lastCodePoint, lastClass)

        val pairedClosingQuote = firstOffset in pairedCloseOffsets
        val followsAuthoredBoundary = text.followsAuthoredBoundary(firstOffset)
        val forbidsLineStart = when {
            followsAuthoredBoundary -> false
            pairedClosingQuote -> true
            firstQuoteDirection == ResolvedQuoteDirection.Final -> true
            firstQuoteDirection == ResolvedQuoteDirection.Unresolved -> true
            firstClass == UnicodePunctuationLineBreakClass.InfixNumericSeparator &&
                clusters.isDecimalMarkAfterSpace(index, text) -> false
            firstClass in UAX14_FORBIDDEN_LINE_START_CLASSES -> true
            else -> false
        }
        if (forbidsLineStart) {
            forbiddenLineStart += index
            clusters.previousContentCluster(index, text)?.let { previous ->
                unbreakableRanges += previous..index
            }
            decisions += ContextualKinsokuDecisionInfo(
                range = cluster.range,
                sourceText = source,
                clusterIndex = index,
                forbiddenPosition = "LineStart",
                reason = when {
                    pairedClosingQuote -> "Uax14WesternPunctuationBoundary:PairedClosingQuote"
                    firstQuoteDirection == ResolvedQuoteDirection.Final ||
                        firstQuoteDirection == ResolvedQuoteDirection.Unresolved ->
                        "Uax14WesternPunctuationBoundary:LB19"
                    else -> "Uax14WesternPunctuationBoundary:${firstClass.ruleForLineStart()}"
                },
            )
        }

        val pairedOpeningQuote = lastOffset in pairedOpenOffsets
        val forbidsLineEnd = when {
            pairedOpeningQuote -> true
            lastQuoteDirection == ResolvedQuoteDirection.Initial -> true
            lastQuoteDirection == ResolvedQuoteDirection.Unresolved -> true
            lastClass == UnicodePunctuationLineBreakClass.OpenPunctuation -> true
            else -> false
        }
        if (forbidsLineEnd) {
            forbiddenLineEnd += index
            clusters.nextContentCluster(index, text)?.let { next ->
                unbreakableRanges += index..next
            }
            decisions += ContextualKinsokuDecisionInfo(
                range = cluster.range,
                sourceText = source,
                clusterIndex = index,
                forbiddenPosition = "LineEnd",
                reason = when {
                    pairedOpeningQuote -> "Uax14WesternPunctuationBoundary:PairedOpeningQuote"
                    lastQuoteDirection == ResolvedQuoteDirection.Initial ||
                        lastQuoteDirection == ResolvedQuoteDirection.Unresolved ->
                        "Uax14WesternPunctuationBoundary:LB19"
                    else -> "Uax14WesternPunctuationBoundary:LB14"
                },
            )
        }
    }

    return UnicodePunctuationBoundaries(
        forbiddenLineStartClusters = forbiddenLineStart,
        forbiddenLineEndClusters = forbiddenLineEnd,
        unbreakableRanges = unbreakableRanges.distinct(),
        decisions = decisions,
    )
}

private val UAX14_FORBIDDEN_LINE_START_CLASSES = setOf(
    UnicodePunctuationLineBreakClass.ClosePunctuation,
    UnicodePunctuationLineBreakClass.CloseParenthesis,
    UnicodePunctuationLineBreakClass.Exclamation,
    UnicodePunctuationLineBreakClass.InfixNumericSeparator,
)

private fun UnicodePunctuationLineBreakClass.ruleForLineStart(): String = when (this) {
    UnicodePunctuationLineBreakClass.ClosePunctuation,
    UnicodePunctuationLineBreakClass.CloseParenthesis,
    UnicodePunctuationLineBreakClass.Exclamation,
    -> "LB13"
    UnicodePunctuationLineBreakClass.InfixNumericSeparator -> "LB15d"
    else -> error("No line-start rule for $this")
}

/** UAX #14 LB15c permits a spaced decimal mark before a number. */
private fun List<Cluster>.isDecimalMarkAfterSpace(index: Int, sourceText: String): Boolean {
    if (index <= 0) return false
    val previousSource = sourceText.substring(this[index - 1].range.start, this[index - 1].range.end)
    if (previousSource.isEmpty() || previousSource.any { !it.isWhitespace() }) return false
    val current = this[index]
    val currentSource = sourceText.substring(current.range.start, current.range.end)
    // The cluster's first significant code point is the IS mark, or a space
    // before it; both occupy one char, so the next code point starts at 1.
    val followingInside = currentSource.codePointAtOrNull(1)
    val following = followingInside ?: getOrNull(index + 1)?.let { next ->
        sourceText.substring(next.range.start, next.range.end).codePointAtOrNull(0)
    }
    return following?.let { it in '0'.code..'9'.code } == true
}

private enum class ResolvedQuoteDirection { Initial, Final, Unresolved, WordApostrophe, None }

private val INITIAL_QUOTE_CODE_POINTS = setOf(0x00AB, 0x2018, 0x201B, 0x201C, 0x201F, 0x2039)
private val FINAL_QUOTE_CODE_POINTS = setOf(0x00BB, 0x2019, 0x201D, 0x203A)

/**
 * Resolves UAX #14's Pi/Pf distinction for the Western quote layer. U+2019 is
 * the one common mark whose glyph direction is insufficient: `’90s` binds
 * forward, `James’` binds backward, and `that's` remains part of its word.
 */
private fun String.quoteDirectionAt(
    offset: Int,
    codePoint: Int,
    lineBreakClass: UnicodePunctuationLineBreakClass,
): ResolvedQuoteDirection {
    if (lineBreakClass != UnicodePunctuationLineBreakClass.Quotation) {
        return ResolvedQuoteDirection.None
    }
    if (codePoint == 0x2019) {
        val leftIsWord = codePointBefore(offset)?.isLatinWordCodePoint() == true
        // U+2019 occupies one UTF-16 unit.
        val rightOffset = offset + 1
        val rightIsWord = codePointAtOrNull(rightOffset)?.isLatinWordCodePoint() == true
        return when {
            leftIsWord && rightIsWord -> ResolvedQuoteDirection.WordApostrophe
            !leftIsWord && rightIsWord -> ResolvedQuoteDirection.Initial
            else -> ResolvedQuoteDirection.Final
        }
    }
    return when (codePoint) {
        in INITIAL_QUOTE_CODE_POINTS -> ResolvedQuoteDirection.Initial
        in FINAL_QUOTE_CODE_POINTS -> ResolvedQuoteDirection.Final
        else -> ResolvedQuoteDirection.Unresolved
    }
}

private fun Int.isLatinWordCodePoint(): Boolean =
    this in 'A'.code..'Z'.code ||
        this in 'a'.code..'z'.code ||
        this in '0'.code..'9'.code ||
        this in 0x00C0..0x024F

private data class SignificantCodePoint(val offset: Int, val codePoint: Int)

private fun String.firstSignificantCodePoint(): SignificantCodePoint? {
    var offset = 0
    while (offset < length) {
        val codePoint = codePointAtOrNull(offset) ?: return null
        if (!codePoint.isWhitespaceCodePoint()) return SignificantCodePoint(offset, codePoint)
        // Whitespace code points are all in the BMP, so the step is one char.
        offset += 1
    }
    return null
}

private fun String.lastSignificantCodePoint(): SignificantCodePoint? {
    var end = length
    while (end > 0) {
        // validateLayoutInput rejects unpaired surrogates, so a trailing low
        // surrogate always has its high surrogate before it.
        val offset = if (this[end - 1].code in 0xDC00..0xDFFF) end - 2 else end - 1
        val codePoint = codePointAtOrNull(offset) ?: return null
        if (!codePoint.isWhitespaceCodePoint()) return SignificantCodePoint(offset, codePoint)
        end = offset
    }
    return null
}

private fun Int.isWhitespaceCodePoint(): Boolean = this <= 0xFFFF && toChar().isWhitespace()

private fun String.followsAuthoredBoundary(offset: Int): Boolean {
    var cursor = offset
    while (cursor > 0) {
        val previous = codePointBefore(cursor) ?: return true
        if (isMandatoryBreakCodePoint(previous) || isZeroWidthSpaceCodePoint(previous)) return true
        if (!previous.isWhitespaceCodePoint()) return false
        // Reaching here means `previous` is whitespace, which is always in
        // the BMP, so the step back is one char.
        cursor -= 1
    }
    return true
}

private fun List<Cluster>.previousContentCluster(index: Int, sourceText: String): Int? {
    var cursor = index - 1
    while (cursor >= 0) {
        val source = sourceText.substring(this[cursor].range.start, this[cursor].range.end)
        if (source.hasAuthoredBreak()) return null
        if (source.firstSignificantCodePoint() != null) return cursor
        cursor--
    }
    return null
}

private fun List<Cluster>.nextContentCluster(index: Int, sourceText: String): Int? {
    var cursor = index + 1
    while (cursor <= lastIndex) {
        val source = sourceText.substring(this[cursor].range.start, this[cursor].range.end)
        if (source.hasAuthoredBreak()) return null
        if (source.firstSignificantCodePoint() != null) return cursor
        cursor++
    }
    return null
}

private fun String.hasAuthoredBreak(): Boolean {
    var offset = 0
    while (offset < length) {
        val codePoint = codePointAtOrNull(offset) ?: return false
        if (isMandatoryBreakCodePoint(codePoint) || isZeroWidthSpaceCodePoint(codePoint)) return true
        offset += if (codePoint > 0xFFFF) 2 else 1
    }
    return false
}

private fun String.codePointAtOrNull(index: Int): Int? {
    if (index !in indices) return null
    val high = this[index].code
    // validateLayoutInput rejects a text that ends with an unpaired high
    // surrogate, so a high surrogate always has its low surrogate after it.
    if (high !in 0xD800..0xDBFF) return high
    val low = this[index + 1].code
    if (low !in 0xDC00..0xDFFF) return high
    return 0x10000 + ((high - 0xD800) shl 10) + (low - 0xDC00)
}

private fun String.codePointBefore(index: Int): Int? {
    if (index <= 0 || index > length) return null
    val last = index - 1
    val start = if (this[last].code in 0xDC00..0xDFFF && last > 0 && this[last - 1].code in 0xD800..0xDBFF) {
        last - 1
    } else {
        last
    }
    return codePointAtOrNull(start)
}
