package org.tiqian.linebreak

/**
 * The UAX #14 line-break classes needed for punctuation boundary protection.
 * This is deliberately not a claim that Tiqian implements the complete Unicode
 * Line Breaking Algorithm: word, numeric, combining-mark and script-specific
 * rules remain in their existing pipeline stages.
 */
enum class UnicodePunctuationLineBreakClass {
    BreakAfter,
    BreakBoth,
    ClosePunctuation,
    CloseParenthesis,
    Exclamation,
    HyphenHH,
    Hyphen,
    Inseparable,
    InfixNumericSeparator,
    Nonstarter,
    OpenPunctuation,
    Quotation,
    SymbolsAllowingBreakAfter,
    Other,
}

/**
 * Unicode 17.0.0 punctuation properties used by the layout layer to preserve
 * the tailorable UAX #14 punctuation boundaries independently of font choice.
 */
object UnicodePunctuationLineBreak {
    const val DATA_REVISION: String = "17.0.0"
    const val DATA_SOURCE: String = "https://www.unicode.org/Public/17.0.0/ucd/LineBreak.txt"
    const val DATA_SHA256: String = "e6a18fa91f8f6a6f8e534b1d3f128c21ada45bfe152eb6b1bcc5e15fd8ac92e6"

    fun classOf(codePoint: Int): UnicodePunctuationLineBreakClass {
        require(codePoint in 0..0x10FFFF) { "Not a Unicode scalar value: $codePoint" }
        require(codePoint !in 0xD800..0xDFFF) { "Surrogate is not a Unicode scalar value: $codePoint" }
        return when (UnicodePunctuationLineBreakData.lookup(codePoint)) {
            0 -> UnicodePunctuationLineBreakClass.BreakAfter
            1 -> UnicodePunctuationLineBreakClass.BreakBoth
            2 -> UnicodePunctuationLineBreakClass.ClosePunctuation
            3 -> UnicodePunctuationLineBreakClass.CloseParenthesis
            4 -> UnicodePunctuationLineBreakClass.Exclamation
            5 -> UnicodePunctuationLineBreakClass.HyphenHH
            6 -> UnicodePunctuationLineBreakClass.Hyphen
            7 -> UnicodePunctuationLineBreakClass.Inseparable
            8 -> UnicodePunctuationLineBreakClass.InfixNumericSeparator
            9 -> UnicodePunctuationLineBreakClass.Nonstarter
            10 -> UnicodePunctuationLineBreakClass.OpenPunctuation
            11 -> UnicodePunctuationLineBreakClass.Quotation
            12 -> UnicodePunctuationLineBreakClass.SymbolsAllowingBreakAfter
            else -> UnicodePunctuationLineBreakClass.Other
        }
    }
}
