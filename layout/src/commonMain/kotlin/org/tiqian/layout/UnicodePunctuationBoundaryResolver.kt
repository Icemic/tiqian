package org.tiqian.layout

import org.tiqian.core.Cluster
import org.tiqian.core.ContextualKinsokuDecisionInfo
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
    val firstLength = currentSource.firstCodePointLength()
    val followingInside = currentSource.codePointAtOrNull(firstLength)
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
        val rightOffset = offset + if (codePoint > 0xFFFF) 2 else 1
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
        offset += if (codePoint > 0xFFFF) 2 else 1
    }
    return null
}

private fun String.lastSignificantCodePoint(): SignificantCodePoint? {
    var end = length
    while (end > 0) {
        val offset = if (
            this[end - 1].code in 0xDC00..0xDFFF &&
            end >= 2 &&
            this[end - 2].code in 0xD800..0xDBFF
        ) {
            end - 2
        } else {
            end - 1
        }
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
        cursor -= if (previous > 0xFFFF) 2 else 1
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

private fun String.firstCodePointLength(): Int =
    if (length >= 2 && this[0].code in 0xD800..0xDBFF && this[1].code in 0xDC00..0xDFFF) 2 else 1

private fun String.codePointAtOrNull(index: Int): Int? {
    if (index !in indices) return null
    val high = this[index].code
    if (high !in 0xD800..0xDBFF || index + 1 >= length) return high
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
