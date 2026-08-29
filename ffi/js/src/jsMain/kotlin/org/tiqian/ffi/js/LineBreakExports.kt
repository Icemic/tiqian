@file:OptIn(kotlin.js.ExperimentalJsExport::class)

package org.tiqian.ffi.js

import kotlin.js.JsExport
import org.tiqian.linebreak.LiangHyphenator
import org.tiqian.linebreak.UnicodePunctuationLineBreak

/**
 * Standalone line-break capability exports for `@tiqian/ffi`.
 *
 * Each export wraps exactly one engine capability:
 * - `liangHyphenate` wraps `LiangHyphenator.hyphenate()`
 *   (`engine/src/commonMain/kotlin/org/tiqian/linebreak/Hyphenation.kt`).
 * - `unicodePunctuationLineBreakClassOf` wraps
 *   `UnicodePunctuationLineBreak.classOf()`
 *   (`engine/src/commonMain/kotlin/org/tiqian/linebreak/UnicodePunctuationLineBreak.kt`).
 *
 * The wire is JSON-through-JavaScript: pattern/exception data enters as JSON
 * strings, break offsets leave as a JSON array. `NoHyphenator` is deliberately
 * not exported — it always yields `[]`, so `liangHyphenate` with empty JSON is
 * the data-free case.
 */

/**
 * Hyphenates [word] with Frank Liang's algorithm using JSON-encoded [patterns]
 * (map from pattern key to inter-letter level array) and [exceptions] (map from
 * lowercased word to explicit break offsets). [leftMin]/[rightMin] keep the
 * engine defaults. Returns a JSON array of break offsets (codepoint indices,
 * ascending), e.g. `[2]`.
 */
@JsExport
fun liangHyphenate(
    word: String,
    patternsJson: String,
    exceptionsJson: String,
    leftMin: Int = 2,
    rightMin: Int = 3,
): String {
    val hyphenator = LiangHyphenator(
        patterns = parsePatternsJson(patternsJson),
        exceptions = parseExceptionsJson(exceptionsJson),
        leftMin = leftMin,
        rightMin = rightMin,
    )
    val offsets = hyphenator.hyphenate(word)
    return buildString {
        append('[')
        offsets.forEachIndexed { index, offset ->
            if (index > 0) append(',')
            append(offset)
        }
        append(']')
    }
}

/**
 * Returns the UAX #14 punctuation line-break class name for [codePoint]
 * (e.g. `"OpenPunctuation"`, `"CloseParenthesis"`, `"Other"`). Requires a
 * Unicode scalar value; throws on surrogates per the engine contract.
 */
@JsExport
fun unicodePunctuationLineBreakClassOf(codePoint: Int): String =
    UnicodePunctuationLineBreak.classOf(codePoint).name