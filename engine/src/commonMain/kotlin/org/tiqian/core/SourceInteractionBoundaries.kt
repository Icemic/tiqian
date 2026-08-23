package org.tiqian.core

/** Direction used when an interaction offset falls inside one source grapheme. */
enum class SourceBoundaryBias {
    Backward,
    Forward,
    Nearest,
}

/**
 * `SourceInteractionBoundaryMap`: keeps caret/selection offsets on stable UTF-16 source
 * boundaries. Simple Latin code points remain individually selectable, while surrogate pairs,
 * combining/variation sequences, emoji modifiers, regional-indicator pairs, Hangul syllable
 * sequences, and ZWJ-connected sequences stay indivisible.
 *
 * This is deliberately an interaction projection; the public source ABI remains UTF-16 and layout
 * atoms/shaping clusters keep their existing ranges.
 */
internal fun String.coerceToInteractionBoundary(
    offset: Int,
    range: TextRange,
    bias: SourceBoundaryBias,
): Int {
    val start = range.start.coerceIn(0, length)
    val end = range.end.coerceIn(start, length)
    val target = offset.coerceIn(start, end)
    if (target == start || target == end) return target
    val boundaries = interactionBoundaries(start, end)
    if (target in boundaries) return target
    val previous = boundaries.last { it < target }
    val next = boundaries.first { it > target }
    return when (bias) {
        SourceBoundaryBias.Backward -> previous
        SourceBoundaryBias.Forward -> next
        SourceBoundaryBias.Nearest -> if (target - previous < next - target) previous else next
    }
}

internal fun String.interactionBoundaries(range: TextRange): List<Int> {
    val start = range.start.coerceIn(0, length)
    val end = range.end.coerceIn(start, length)
    return interactionBoundaries(start, end)
}

/**
 * Safe source-grapheme boundaries for layout policies that must never split a
 * surrogate pair, combining sequence, emoji modifier/ZWJ sequence, regional
 * indicator pair, or Hangul syllable sequence.
 */
fun String.sourceGraphemeBoundaries(range: TextRange): List<Int> = interactionBoundaries(range)

private fun String.interactionBoundaries(start: Int, end: Int): List<Int> {
    val out = mutableListOf(start)
    var index = start
    while (index < end) {
        val first = codePointAtCompat(index, end)
        var next = index + first.charCountCompat()

        if (first == CR && next < end && codePointAtCompat(next, end) == LF) {
            next += 1
        } else if (first.isRegionalIndicator() && next < end) {
            val following = codePointAtCompat(next, end)
            if (following.isRegionalIndicator()) next += following.charCountCompat()
        } else if (first.isHangulL()) {
            while (next < end && codePointAtCompat(next, end).isHangulL()) {
                next += codePointAtCompat(next, end).charCountCompat()
            }
            if (next < end && codePointAtCompat(next, end).isHangulV()) {
                while (next < end && codePointAtCompat(next, end).isHangulV()) {
                    next += codePointAtCompat(next, end).charCountCompat()
                }
                while (next < end && codePointAtCompat(next, end).isHangulT()) {
                    next += codePointAtCompat(next, end).charCountCompat()
                }
            }
        } else if (first.isHangulLvOrLvt()) {
            if (first.isHangulLv()) {
                while (next < end && codePointAtCompat(next, end).isHangulV()) {
                    next += codePointAtCompat(next, end).charCountCompat()
                }
            }
            while (next < end && codePointAtCompat(next, end).isHangulT()) {
                next += codePointAtCompat(next, end).charCountCompat()
            }
        }

        next = consumeExtenders(next, end)
        while (next < end && codePointAtCompat(next, end) == ZWJ) {
            val afterJoiner = next + 1
            if (afterJoiner >= end) {
                next = afterJoiner
                break
            }
            val joined = codePointAtCompat(afterJoiner, end)
            next = consumeExtenders(afterJoiner + joined.charCountCompat(), end)
        }
        index = next
        out += index
    }
    return out
}

private fun String.consumeExtenders(from: Int, end: Int): Int {
    var index = from
    while (index < end) {
        val codePoint = codePointAtCompat(index, end)
        if (!codePoint.isInteractionExtender()) break
        index += codePoint.charCountCompat()
    }
    return index
}

internal fun String.codePointAtCompat(index: Int, end: Int): Int {
    val high = this[index].code
    if (high !in HIGH_SURROGATE_RANGE || index + 1 >= end) return high
    val low = this[index + 1].code
    if (low !in LOW_SURROGATE_RANGE) return high
    return 0x10000 + ((high - 0xD800) shl 10) + (low - 0xDC00)
}

private fun Int.charCountCompat(): Int = if (this > 0xFFFF) 2 else 1

private fun Int.isInteractionExtender(): Boolean =
    this == ZWNJ ||
        this in VARIATION_SELECTOR_BMP_RANGE ||
        this in VARIATION_SELECTOR_SUPPLEMENT_RANGE ||
        this in EMOJI_MODIFIER_RANGE ||
        this in EMOJI_TAG_RANGE ||
        (this <= 0xFFFF && this.toChar().category in EXTENDING_CATEGORIES)

private fun Int.isRegionalIndicator(): Boolean = this in REGIONAL_INDICATOR_RANGE
private fun Int.isHangulL(): Boolean = this in 0x1100..0x115F || this in 0xA960..0xA97C
private fun Int.isHangulV(): Boolean = this in 0x1160..0x11A7 || this in 0xD7B0..0xD7C6
private fun Int.isHangulT(): Boolean = this in 0x11A8..0x11FF || this in 0xD7CB..0xD7FB
private fun Int.isHangulLvOrLvt(): Boolean = this in HANGUL_SYLLABLE_RANGE
private fun Int.isHangulLv(): Boolean = isHangulLvOrLvt() && (this - HANGUL_SYLLABLE_RANGE.first) % 28 == 0

private val EXTENDING_CATEGORIES = setOf(
    CharCategory.NON_SPACING_MARK,
    CharCategory.COMBINING_SPACING_MARK,
    CharCategory.ENCLOSING_MARK,
)
private val HIGH_SURROGATE_RANGE = 0xD800..0xDBFF
private val LOW_SURROGATE_RANGE = 0xDC00..0xDFFF
private val VARIATION_SELECTOR_BMP_RANGE = 0xFE00..0xFE0F
private val VARIATION_SELECTOR_SUPPLEMENT_RANGE = 0xE0100..0xE01EF
private val EMOJI_MODIFIER_RANGE = 0x1F3FB..0x1F3FF
private val EMOJI_TAG_RANGE = 0xE0020..0xE007F
private val REGIONAL_INDICATOR_RANGE = 0x1F1E6..0x1F1FF
private val HANGUL_SYLLABLE_RANGE = 0xAC00..0xD7A3
private const val CR = 0x000D
private const val LF = 0x000A
private const val ZWNJ = 0x200C
private const val ZWJ = 0x200D
