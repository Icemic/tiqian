package org.tiqian.core

/** Stable Unicode 17 Letter/Mark/Number membership for lexical boundaries. */
object UnicodeWordCharacter {
    const val DATA_REVISION: String = "17.0.0"
    const val DATA_SOURCE: String =
        "https://www.unicode.org/Public/17.0.0/ucd/extracted/DerivedGeneralCategory.txt"
    const val DATA_SHA256: String =
        "d62e5bab70ca74f099343f71224fa051cb1fdd61a1ab45c0488c44cfc0b6102e"

    fun contains(codePoint: Int): Boolean {
        require(codePoint in 0..0x10FFFF) { "Not a Unicode scalar value: $codePoint" }
        require(codePoint !in 0xD800..0xDFFF) { "Surrogate is not a Unicode scalar value: $codePoint" }
        return UnicodeWordCharacterData.contains(codePoint)
    }
}
