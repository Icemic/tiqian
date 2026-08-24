package org.tiqian.ffi.js

import kotlin.math.abs
import org.tiqian.core.Cluster
import org.tiqian.core.Glyph
import org.tiqian.core.GlyphRun
import org.tiqian.core.Rect
import org.tiqian.core.ShapingDecisionInfo
import org.tiqian.core.TextRange
import org.tiqian.font.FontMetricSource
import org.tiqian.font.FontMetricsRequest
import org.tiqian.font.FontMetricsResolver
import org.tiqian.font.RawFontMetrics
import org.tiqian.shaping.ShapingInput
import org.tiqian.shaping.ShapingResult
import org.tiqian.shaping.TextShaper

/**
 * Text shaper adapter that delegates shaping requests across the JavaScript ABI
 * via a synchronous callback returning a JSON string.
 */
internal class JsCallbackTextShaper(
    private val shapeJson: (String) -> String,
) : TextShaper {
    override fun shape(input: ShapingInput): ShapingResult {
        val requestJson = input.appendShapingInputJson()
        val responseJson = shapeJson(requestJson)
        return parseShapingResultJson(responseJson)
    }
}

/**
 * Font metrics resolver adapter that delegates font metrics resolution across the JavaScript ABI
 * via a synchronous callback returning a JSON string.
 */
internal class JsCallbackFontMetricsResolver(
    private val metricsJson: (String) -> String,
) : FontMetricsResolver {
    override fun resolve(request: FontMetricsRequest): RawFontMetrics {
        val requestJson = request.appendFontMetricsRequestJson()
        val responseJson = metricsJson(requestJson)
        return parseRawFontMetricsJson(responseJson)
    }
}

internal fun ShapingInput.appendShapingInputJson(): String {
    val builder = StringBuilder()
    builder.append("{\"text\":")
    builder.appendJsonString(text)
    builder.append(",\"range\":{\"start\":")
    builder.append(range.start)
    builder.append(",\"end\":")
    builder.append(range.end)
    builder.append("},\"style\":{\"fontFamilies\":")
    builder.appendJsonStringArray(style.fontFamilies)
    builder.append(",\"fontSize\":")
    builder.appendJsonNumber(style.fontSize)
    builder.append(",\"fontWeight\":")
    builder.append(style.fontWeight)
    builder.append(",\"italic\":")
    builder.append(style.italic)
    builder.append(",\"locale\":")
    builder.appendJsonString(style.locale)
    builder.append("},\"fontDecision\":{\"role\":")
    builder.appendJsonString(fontDecision.role.name)
    builder.append(",\"candidateKey\":")
    builder.appendJsonString(fontDecision.candidate.key)
    builder.append("},\"displayText\":")
    builder.appendJsonString(displayText)
    builder.append(",\"openTypeFeatures\":")
    builder.appendJsonStringArray(openTypeFeatures)
    builder.append("}")
    return builder.toString()
}

internal fun FontMetricsRequest.appendFontMetricsRequestJson(): String {
    val builder = StringBuilder()
    builder.append("{\"fontKey\":")
    builder.appendJsonString(fontKey)
    builder.append(",\"fontSize\":")
    builder.appendJsonNumber(fontSize)
    builder.append(",\"role\":")
    builder.appendJsonString(role.name)
    builder.append(",\"locale\":")
    builder.appendJsonString(locale)
    builder.append(",\"fontFamilies\":")
    builder.appendJsonStringArray(fontFamilies)
    builder.append(",\"fontWeight\":")
    builder.append(fontWeight)
    builder.append(",\"italic\":")
    builder.append(italic)
    builder.append(",\"faceSelectionText\":")
    builder.appendJsonString(faceSelectionText)
    builder.append("}")
    return builder.toString()
}

private inline fun <T> parseDynamicList(raw: dynamic, transform: (dynamic) -> T): List<T> {
    if (raw == null) return emptyList()
    val length = (raw.length as? Double)?.toInt() ?: return emptyList()
    val list = ArrayList<T>(length)
    for (i in 0 until length) {
        list.add(transform(raw[i]))
    }
    return list
}

private fun parseTextRange(raw: dynamic): TextRange {
    if (raw == null) return TextRange(0, 0)
    val start = ((raw.start as? Double) ?: 0.0).toInt()
    val end = ((raw.end as? Double) ?: 0.0).toInt()
    return TextRange(start = start, end = end)
}

private fun parseBounds(raw: dynamic): Rect? {
    if (raw == null) return null
    return Rect(
        left = (raw.left as? Double)?.toFloat() ?: 0f,
        top = (raw.top as? Double)?.toFloat() ?: 0f,
        right = (raw.right as? Double)?.toFloat() ?: 0f,
        bottom = (raw.bottom as? Double)?.toFloat() ?: 0f,
    )
}

internal fun parseShapingResultJson(json: String): ShapingResult {
    val raw = kotlin.js.JSON.parse<dynamic>(json) ?: return ShapingResult(emptyList(), emptyList(), emptyList())
    val clusters = parseDynamicList(raw.clusters) { clusterRaw ->
        Cluster(
            range = parseTextRange(clusterRaw?.range),
            text = (clusterRaw?.text as? String) ?: "",
            displayText = (clusterRaw?.displayText as? String) ?: "",
            fontKey = (clusterRaw?.fontKey as? String) ?: "",
            advance = (clusterRaw?.advance as? Double)?.toFloat() ?: Float.NaN,
            baselineShift = (clusterRaw?.baselineShift as? Double)?.toFloat() ?: 0f,
        )
    }
    val glyphRuns = parseDynamicList(raw.glyphRuns) { runRaw ->
        GlyphRun(
            range = parseTextRange(runRaw?.range),
            fontKey = (runRaw?.fontKey as? String) ?: "",
            glyphs = parseDynamicList(runRaw?.glyphs) { glyphRaw ->
                Glyph(
                    id = (glyphRaw?.id as? Double)?.toUInt() ?: 0u,
                    clusterRange = parseTextRange(glyphRaw?.clusterRange),
                    advance = (glyphRaw?.advance as? Double)?.toFloat() ?: Float.NaN,
                    x = (glyphRaw?.x as? Double)?.toFloat() ?: 0f,
                    y = (glyphRaw?.y as? Double)?.toFloat() ?: 0f,
                    bounds = parseBounds(glyphRaw?.bounds),
                )
            },
            advance = (runRaw?.advance as? Double)?.toFloat() ?: Float.NaN,
            openTypeFeatures = parseDynamicList(runRaw?.openTypeFeatures) { (it as? String) ?: "" },
        )
    }
    val decisions = parseDynamicList(raw.decisions) { decRaw ->
        ShapingDecisionInfo(
            range = parseTextRange(decRaw?.range),
            sourceText = (decRaw?.sourceText as? String) ?: "",
            displayText = (decRaw?.displayText as? String) ?: "",
            fontKey = (decRaw?.fontKey as? String) ?: "",
            glyphCount = (decRaw?.glyphCount as? Double)?.toInt() ?: 0,
            advance = (decRaw?.advance as? Double)?.toFloat() ?: Float.NaN,
            source = (decRaw?.source as? String) ?: "",
            reason = (decRaw?.reason as? String) ?: "",
            glyphsWithoutInkBounds = (decRaw?.glyphsWithoutInkBounds as? Double)?.toInt() ?: 0,
            missingGlyphs = (decRaw?.missingGlyphs as? Double)?.toInt() ?: 0,
            resolvedFace = decRaw?.resolvedFace as? String,
            script = decRaw?.script as? String,
            language = decRaw?.language as? String,
            strategy = decRaw?.strategy as? String,
            featureEvidence = decRaw?.featureEvidence as? String,
            capabilityIssue = decRaw?.capabilityIssue as? String,
        )
    }
    return ShapingResult(
        clusters = clusters,
        glyphRuns = glyphRuns,
        decisions = decisions,
    )
}

internal fun parseRawFontMetricsJson(json: String): RawFontMetrics {
    val raw = kotlin.js.JSON.parse<dynamic>(json) ?: return RawFontMetrics(
        ascent = Float.NaN,
        descent = Float.NaN,
    )
    val ascent = (raw.ascent as? Double)?.toFloat() ?: Float.NaN
    val descent = (raw.descent as? Double)?.toFloat() ?: Float.NaN
    val leading = (raw.leading as? Double)?.toFloat() ?: 0f
    val source = (raw.source as? String)?.let { FontMetricSource.valueOf(it) } ?: FontMetricSource.RawTables
    val typoAscent = (raw.typoAscent as? Double)?.toFloat()
    val typoDescent = (raw.typoDescent as? Double)?.toFloat()
    return RawFontMetrics(
        ascent = ascent,
        descent = descent,
        leading = leading,
        source = source,
        typoAscent = typoAscent,
        typoDescent = typoDescent,
    )
}

private fun StringBuilder.appendJsonStringArray(items: List<String>): StringBuilder {
    append('[')
    for (i in items.indices) {
        if (i > 0) append(',')
        appendJsonString(items[i])
    }
    return append(']')
}

// Copied from layout/src/commonMain/kotlin/org/tiqian/layout/PreparedParagraph.kt

private fun StringBuilder.appendJsonNumber(value: Float): StringBuilder =
    append(if (value == -0f) "0" else ecmaJsonNumber(value))

private fun ecmaJsonNumber(floatValue: Float): String {
    val raw = floatValue.toDouble().toString()
    val negative = raw.startsWith("-")
    val body = if (negative) raw.substring(1) else raw
    val exponentAt = body.indexOfFirst { it == 'e' || it == 'E' }
    val mantissa = if (exponentAt >= 0) body.substring(0, exponentAt) else body
    val exponent = if (exponentAt >= 0) body.substring(exponentAt + 1).toInt() else 0
    val dotAt = mantissa.indexOf('.')
    val integerPart = if (dotAt >= 0) mantissa.substring(0, dotAt) else mantissa
    val fractionPart = if (dotAt >= 0) mantissa.substring(dotAt + 1) else ""

    // digits × 10^(n - digits.length) == value, digits without leading or
    // trailing zeros.
    var digits = if (integerPart.any { it != '0' }) integerPart + fractionPart else fractionPart
    var decimalExponent = if (integerPart.any { it != '0' }) integerPart.length else 0
    decimalExponent += exponent
    val firstSignificant = digits.indexOfFirst { it != '0' }
    if (firstSignificant < 0) return "0"
    if (firstSignificant > 0) {
        digits = digits.substring(firstSignificant)
        decimalExponent -= firstSignificant
    }
    val lastSignificant = digits.indexOfLast { it != '0' }
    if (lastSignificant < digits.length - 1) {
        digits = digits.substring(0, lastSignificant + 1)
    }

    val k = digits.length
    val n = decimalExponent
    digits = canonicalTieBreak(digits, floatValue)
    val magnitude = if (negative) "-" else ""
    return when {
        k <= n && n <= 21 -> magnitude + digits + "0".repeat(n - k)
        0 < n && n <= 21 -> magnitude + digits.substring(0, n) + "." + digits.substring(n)
        -6 < n && n <= 0 -> magnitude + "0." + "0".repeat(-n) + digits
        else -> {
            val mantissaText = if (k > 1) digits[0] + "." + digits.substring(1) else digits[0].toString()
            val exponentValue = n - 1
            val exponentSign = if (exponentValue < 0) "-" else "+"
            magnitude + mantissaText + "e" + exponentSign + abs(exponentValue).toString()
        }
    }
}

private fun canonicalTieBreak(digits: String, value: Float): String {
    val bits = value.toRawBits() and 0x7FFFFFFF
    val biasedExponent = (bits ushr 23) and 0xFF
    var mantissa = bits and 0x7FFFFF
    if (mantissa == 0 && biasedExponent == 0) return digits
    val exponent = if (biasedExponent == 0) {
        -149
    } else {
        mantissa = mantissa or 0x800000
        biasedExponent - 150
    }

    var exact = mantissa.toString()
    if (exponent >= 0) {
        repeat(exponent) { exact = timesSmall(exact, 2) }
    } else {
        // value = mantissa × 5^k × 10^-k; only the digits matter here, the
        // caller keeps the decimal scale.
        repeat(-exponent) { exact = timesSmall(exact, 5) }
    }
    val stripped = exact.trimEnd('0')
    if (stripped.length <= digits.length) return digits

    val keep = stripped.substring(0, digits.length)
    val remainder = stripped.substring(digits.length)
    val pastHalf = remainder.length > 1 && remainder.substring(1).any { it != '0' }
    val roundUp = when {
        remainder[0] > '5' -> true
        remainder[0] < '5' -> false
        // Exact half rounds to even.
        else -> pastHalf || (keep.last() - '0') % 2 != 0
    }
    val canonical = if (roundUp) incrementDecimal(keep) else keep
    // A shorter result means the platform string was not shortest; a longer
    // one means a carry changed the digit count. Either way the platform
    // string is the safer answer.
    return if (canonical.trimEnd('0').length == digits.length) canonical else digits
}

private fun timesSmall(digits: String, factor: Int): String {
    val out = StringBuilder()
    var carry = 0
    for (index in digits.length - 1 downTo 0) {
        val product = (digits[index] - '0') * factor + carry
        out.append('0' + product % 10)
        carry = product / 10
    }
    while (carry > 0) {
        out.append('0' + carry % 10)
        carry /= 10
    }
    return out.reverse().toString()
}

private fun incrementDecimal(digits: String): String {
    val chars = StringBuilder(digits)
    var index = chars.length - 1
    while (true) {
        if (chars[index] < '9') {
            chars[index] = chars[index] + 1
            return chars.toString()
        }
        chars[index] = '0'
        if (index == 0) return "1" + chars
        index -= 1
    }
}

private fun StringBuilder.appendJsonString(value: String): StringBuilder {
    append('"')
    for (char in value) {
        when (char) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000c' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (char.code < 0x20) {
                append("\\u").append(char.code.toString(16).padStart(4, '0'))
            } else {
                append(char)
            }
        }
    }
    return append('"')
}
