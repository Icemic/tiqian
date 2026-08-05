package org.tiqian.core

import kotlin.text.CharCategory

/**
 * Values of Unicode's draft `East_Asian_Spacing` property.
 *
 * The property is deliberately separate from font/script classification: it answers whether
 * two grapheme clusters receive East Asian auto-spacing, not which font shapes either cluster.
 */
enum class EastAsianSpacingValue {
    Wide,
    Narrow,
    Other,
    Conditional,
}

/** Boundary properties of all source grapheme units contained in one shaping/layout cluster. */
data class EastAsianSpacingEdges(
    val leading: EastAsianSpacingValue,
    val trailing: EastAsianSpacingValue,
    val containsWide: Boolean,
)

/**
 * Unicode Proposed Draft UTR #59 classifier, pinned to the official 2024-12-16 data file.
 *
 * UTR #59 is informative work in progress, not a stable Unicode property. Pinning the data
 * revision keeps layout deterministic while still making future draft updates explicit.
 */
object UnicodeEastAsianSpacing {
    const val DATA_REVISION: String = "draft-2024-12-16"
    const val DATA_SOURCE: String = "https://www.unicode.org/reports/tr59/east-asian-spacing.txt"
    const val DATA_SHA256: String = "49fe340a964a6e8e0ebc30099709c665cc6138d444b5c36dc336604047f1010f"
    const val LANGUAGE_REGISTRY_REVISION: String = "2026-06-14"
    const val LANGUAGE_REGISTRY_SOURCE: String =
        "https://www.iana.org/assignments/language-subtag-registry/language-subtag-registry"

    /** Returns the unresolved property value for one Unicode scalar value. */
    fun propertyOf(codePoint: Int): EastAsianSpacingValue {
        require(codePoint in 0..0x10FFFF) { "Not a Unicode scalar value: $codePoint" }
        require(codePoint !in 0xD800..0xDFFF) { "Surrogate is not a Unicode scalar value: $codePoint" }
        return EastAsianSpacingData.lookup(codePoint)
    }

    /**
     * Resolves one already-segmented grapheme cluster for horizontal layout.
     *
     * Per UTR #59, the first code point supplies the property; a cluster containing an enclosing
     * mark resolves to [EastAsianSpacingValue.Other]. Conditional values become Narrow only in a
     * Chinese language context. Empty/invalid input is Other rather than a fabricated boundary.
     */
    fun resolvedForGraphemeCluster(
        graphemeCluster: String,
        locale: String,
    ): EastAsianSpacingValue {
        if (graphemeCluster.isEmpty()) return EastAsianSpacingValue.Other
        if (graphemeCluster.any { it.category == CharCategory.ENCLOSING_MARK }) {
            return EastAsianSpacingValue.Other
        }
        val property = propertyOf(graphemeCluster.codePointAtCompat(0))
        return when (property) {
            EastAsianSpacingValue.Conditional -> if (locale.isChineseLanguageContext()) {
                EastAsianSpacingValue.Narrow
            } else {
                EastAsianSpacingValue.Other
            }

            else -> property
        }
    }

    /**
     * Resolves the leading and trailing source-grapheme properties of a larger shaping cluster.
     * Shapers may coalesce `/Hi` or whole words; auto-spacing must still inspect the actual source
     * unit touching each boundary instead of assigning the first code point to the whole run. This
     * deliberately reuses Tiqian's interaction-boundary map so spacing, selection and hit testing
     * cannot disagree about an indivisible source unit; full UAX #29 coverage remains that map's
     * own tracked contract rather than being reimplemented here.
     */
    fun resolvedEdges(
        text: String,
        locale: String,
    ): EastAsianSpacingEdges {
        if (text.isEmpty()) {
            return EastAsianSpacingEdges(
                leading = EastAsianSpacingValue.Other,
                trailing = EastAsianSpacingValue.Other,
                containsWide = false,
            )
        }
        val boundaries = text.interactionBoundaries(TextRange(0, text.length))
        val values = boundaries.zipWithNext { start, end ->
            resolvedForGraphemeCluster(text.substring(start, end), locale)
        }
        return EastAsianSpacingEdges(
            leading = values.first(),
            trailing = values.last(),
            containsWide = values.any { it == EastAsianSpacingValue.Wide },
        )
    }
}

private fun String.isChineseLanguageContext(): Boolean {
    val language = substringBefore('-').substringBefore('_').lowercase()
    return language == "zh" || language in CHINESE_MACROLANGUAGE_MEMBERS
}

/** `Macrolanguage: zh` records in the pinned IANA language subtag registry revision above. */
private val CHINESE_MACROLANGUAGE_MEMBERS = setOf(
    "cdo",
    "cjy",
    "cmn",
    "cnp",
    "cpx",
    "csp",
    "czh",
    "czo",
    "gan",
    "hak",
    "hnm",
    "hsn",
    "luh",
    "lzh",
    "mnp",
    "nan",
    "sjc",
    "wuu",
    "yue",
)

private fun String.codePointAtCompat(index: Int): Int {
    val high = this[index].code
    if (high !in 0xD800..0xDBFF || index + 1 >= length) return high
    val low = this[index + 1].code
    if (low !in 0xDC00..0xDFFF) return high
    return 0x10000 + ((high - 0xD800) shl 10) + (low - 0xDC00)
}
