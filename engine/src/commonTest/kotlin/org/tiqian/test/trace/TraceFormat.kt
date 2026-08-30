package org.tiqian.test.trace

import kotlin.math.abs
import kotlin.math.floor

/**
 * Canonical process-trace formatting for engine unit-test scenarios.
 *
 * The Haxe port re-implements these rules verbatim: a scenario's output
 * must be byte-identical between the Kotlin engine and the Haxe engine.
 * The rules are pure integer arithmetic after one float scaling step, so
 * every target computes the same string.
 *
 * Line shape: `name key=value key=value`, fields in argument order.
 * Value shapes: Float and Double with a fixed decimal count, Boolean/Long
 * as their literals, null as `-`, String single-quoted with control
 * characters escaped, anything else through toString() pinned by the
 * golden.
 *
 * Int fields must be pre-formatted with [i]: Kotlin/JS boxes Int, Float,
 * and Double as the same runtime number, so `value()` cannot tell them
 * apart on JS and an unformatted Int renders as a float there ("2.0").
 */
object TraceFormat {

    /** Integer text for Int fields; see the class doc for why [i] exists. */
    fun i(v: Int): String = v.toString()

    /** One decimal, half-up on the magnitude, sign from the raw sign bit. */
    fun f(v: Float): String = floatWithDecimals(v, 1)

    /**
     * Fixed-decimal float text. `scaled = floor(|v| * 10^decimals + 0.5)`
     * in double arithmetic, printed as integer and zero-padded fraction.
     * `-0.04` renders `-0.0`; NaN and infinities render as those words.
     */
    fun fd(v: Float, decimals: Int): String = floatWithDecimals(v, decimals)

    fun fd(v: Double, decimals: Int): String = doubleWithDecimals(v, decimals)

    fun d(v: Double): String = doubleWithDecimals(v, 1)

    fun value(v: Any?): String = when (v) {
        null -> "-"
        is Float -> f(v)
        is Double -> d(v)
        is Int, is Long, is Boolean -> v.toString()
        is String -> "'${escapeText(v)}'"
        else -> v.toString()
    }

    /** Control-character escaping shared with the layout-dump format. */
    fun escapeText(s: String): String = buildString {
        for (ch in s) {
            when (ch) {
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\u000B' -> append("\\v")
                '\u000C' -> append("\\f")
                '\u0085' -> append("\\u0085")
                '\u2028' -> append("\\u2028")
                '\u2029' -> append("\\u2029")
                '\u200B' -> append("\\u200B")
                else -> append(ch)
            }
        }
    }

    private fun floatWithDecimals(v: Float, decimals: Int): String {
        // Kotlin/JS keeps Float literals in double precision until an
        // operation stores them into the Float grid, so a literal operand
        // reaches this function as its double. The bit round-trip is an
        // identity on JVM and Native and applies the Float rounding on JS,
        // which makes the recorded bytes identical on every backend.
        val f = Float.fromBits(v.toRawBits())
        if (f.isNaN()) return "NaN"
        if (f.isInfinite()) return if (f > 0.0f) "Infinity" else "-Infinity"
        val negative = f.toRawBits() < 0
        return fixedDecimalText(abs(f.toDouble()), negative, decimals)
    }

    private fun doubleWithDecimals(v: Double, decimals: Int): String {
        if (v.isNaN()) return "NaN"
        if (v.isInfinite()) return if (v > 0.0) "Infinity" else "-Infinity"
        val negative = v.toRawBits() < 0
        return fixedDecimalText(abs(v), negative, decimals)
    }

    private fun fixedDecimalText(magnitude: Double, negative: Boolean, decimals: Int): String {
        val scale = pow10(decimals)
        val scaled = floor(magnitude * scale + 0.5).toLong()
        val integer = scaled / scale
        val fraction = scaled % scale
        val fractionText = fraction.toString().padStart(decimals, '0')
        return "${if (negative) "-" else ""}$integer.$fractionText"
    }

    private fun pow10(n: Int): Long {
        var result = 1L
        repeat(n) { result *= 10L }
        return result
    }
}

/**
 * Accumulates trace lines for one scenario. Field order is fixed by the
 * call site, so the same scenario body produces the same bytes on every
 * target and in the Haxe port.
 */
class TraceRecorder {
    private val lines = StringBuilder()

    fun event(name: String, vararg fields: Pair<String, Any?>) {
        lines.append(name)
        for ((key, value) in fields) {
            lines.append(' ').append(key).append('=').append(TraceFormat.value(value))
        }
        lines.append('\n')
    }

    fun raw(text: String) {
        lines.append(text).append('\n')
    }

    fun text(): String = lines.toString()
}
