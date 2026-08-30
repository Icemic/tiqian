package org.tiqian.core

/**
 * Stable Unicode Script evidence for language-sensitive Common punctuation.
 * Common, Inherited, and unassigned scalars are neutral: punctuation, spaces,
 * and ASCII digits do not get to decide the language of surrounding marks.
 */
enum class UnicodeScriptEvidence {
    Neutral,
    EastAsian,
    Other,
}

object UnicodeScriptEvidenceClassifier {
    const val DATA_REVISION: String = "17.0.0"
    const val DATA_SOURCE: String =
        "https://www.unicode.org/Public/17.0.0/ucd/Scripts.txt"
    const val DATA_SHA256: String =
        "9f5e50d3abaee7d6ce09480f325c706f485ae3240912527e651954d2d6b035bf"

    fun classify(codePoint: Int): UnicodeScriptEvidence {
        require(codePoint in 0..0x10FFFF) { "Not a Unicode scalar value: $codePoint" }
        require(codePoint !in 0xD800..0xDFFF) { "Surrogate is not a Unicode scalar value: $codePoint" }
        return UnicodeScriptEvidenceData.classify(codePoint)
    }
}
