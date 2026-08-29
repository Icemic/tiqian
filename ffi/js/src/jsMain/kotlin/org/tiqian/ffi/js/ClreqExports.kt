@file:OptIn(kotlin.js.ExperimentalJsExport::class)

package org.tiqian.ffi.js

import kotlin.js.JsExport
import org.tiqian.clreq.BopomofoParser
import org.tiqian.clreq.NumberSymbolCohesion

/**
 * Standalone CLREQ capability exports for `@tiqian/ffi`.
 *
 * Each export wraps exactly one engine capability:
 * - `bopomofoParse` wraps `BopomofoParser.parse()`
 *   (`engine/src/commonMain/kotlin/org/tiqian/clreq/BopomofoReading.kt`).
 * - `numberSymbolCohesionUnbreakableRanges` wraps
 *   `NumberSymbolCohesion.unbreakableRanges()`
 *   (`engine/src/commonMain/kotlin/org/tiqian/clreq/NumberSymbolCohesion.kt`).
 *
 * The wire is JSON-through-JavaScript: both functions return a JSON string
 * (decode helpers in `WireJson.kt` reconstruct engine values on request
 * paths). Source text is never rewritten.
 */

/**
 * Parses a 注音 reading into its symbols + derived tone (ADR 0033).
 *
 * Returns `{"symbols":["ㄓ","ㄨ","ㄥ"],"tone":"Yinping"}`. [BopomofoTone]
 * names pass through verbatim: `Yinping`, `Yangping`, `Shang`, `Qu`,
 * `Neutral`, `Ru`.
 */
@JsExport
fun bopomofoParse(reading: String): String {
    val parsed = BopomofoParser.parse(reading)
    return buildString {
        append("{\"symbols\":[")
        parsed.symbols.forEachIndexed { index, symbol ->
            if (index > 0) append(',')
            appendJsonString(symbol)
        }
        append("],\"tone\":")
        appendJsonString(parsed.tone.name)
        append('}')
    }
}

/**
 * Returns the source-text ranges (inclusive `start..end`, as `[start,end]`
 * pairs) the line breaker must keep unbroken per CLREQ §符号分离禁则.
 *
 * Returns a JSON array of inclusive pairs, e.g. `[[2,4]]` for `"增长50%了"`.
 */
@JsExport
fun numberSymbolCohesionUnbreakableRanges(text: String): String {
    val ranges = NumberSymbolCohesion.unbreakableRanges(text)
    return buildString {
        append('[')
        ranges.forEachIndexed { index, range ->
            if (index > 0) append(',')
            append('[').append(range.first).append(',').append(range.last).append(']')
        }
        append(']')
    }
}