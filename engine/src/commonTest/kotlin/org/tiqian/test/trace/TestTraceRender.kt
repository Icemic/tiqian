package org.tiqian.test.trace

/**
 * Operand rendering for the traced assertions. Primitive operands are
 * rendered by the static overloads in [TracedAssertions]; this object
 * handles what reaches the generic path: null, strings, and composite
 * values. Composites render recursively so list/map/pair assertions
 * produce deterministic text. Numbers inside erased composites cannot
 * keep their static type on Kotlin/JS, so they all render through one
 * fd-based text normalized by [canonicalNumbers] — identical bytes on
 * every platform.
 *
 * Every rendered operand is capped at [MAX_OPERAND_CHARS]. Composite
 * operands in the coverage tests reach hundreds of thousands of chars,
 * which is beyond review anyway; the truncation marker keeps the full
 * length and an FNV-1a hash of the untruncated text so two different
 * giant values still compare unequal on every platform.
 */
@PublishedApi
internal object TestTraceRender {

    private const val MAX_OPERAND_CHARS = 240

    private val IDENT_HASH_SUFFIX = Regex("@[0-9a-fA-F]+$")

    /**
     * String escaping for operand text. Same table as the shared
     * [TraceFormat.escapeText] plus NUL, which appears as a raw byte in
     * two coverage-test Char literals; a raw NUL in the golden files
     * would make them binary for text tools.
     */
    /** [TraceFormat.escapeText] plus NUL-to-text, so a raw NUL byte
     *  never lands in the golden files (binary for text tools). */
    private fun esc(v: String): String =
        TraceFormat.escapeText(v).replace("\u0000", "\\u0000")

    fun escapeOperand(v: String): String =
        cap(esc(v))

    @PublishedApi
    internal fun render(v: Any?): String = cap(completeRender(v))

    private fun completeRender(v: Any?): String = when (v) {
        null -> "-"
        is String -> "'${esc(v)}'"
        is Double -> compositeNumber(v)
        is Float -> compositeNumber(v.toDouble())
        is Int -> compositeNumber(v.toDouble())
        is Long -> v.toString()
        is Boolean -> v.toString()
        is Char -> if (v == '\u0000') "'\\u0000'" else "'$v'"
        is Iterable<*> -> v.joinToString(", ", "[", "]") { render(it) }
        is Map<*, *> -> v.entries.joinToString(", ", "{", "}") { entry ->
            "${render(entry.key)}=${render(entry.value)}"
        }
        is Pair<*, *> -> "(${render(v.first)}, ${render(v.second)})"
        is Triple<*, *, *> -> "(${render(v.first)}, ${render(v.second)}, ${render(v.third)})"
        else -> renderOpaque(v)
    }

    /**
     * Types without a toString override would leak the JVM identity
     * hash into the golden, which changes every run. The default
     * Object.toString on the JVM ends in "@<hex>"; Kotlin/JS renders
     * the same case as "[object Object]". Both normalize to
     * "<SimpleName>@identity" so the text is stable across runs and
     * platforms. Overridden toString output is kept verbatim.
     */
    private fun renderOpaque(v: Any): String {
        val text = v.toString()
        if (text == "[object Object]" || IDENT_HASH_SUFFIX.containsMatchIn(text)) {
            val simple = v::class.simpleName
            if (simple != null) return "$simple@identity"
        }
        return text
    }

    /**
     * Numbers inside erased composites cannot distinguish Int from
     * Double on Kotlin/JS (one runtime number type), so every composite
     * number renders through the same fd text. [canonicalNumbers] then
     * strips whole-number fractions, which also aligns data-class
     * toString output: the JVM prints whole doubles as "10.0" while
     * Kotlin/JS prints "10". Applied at one choke point (recordEvent),
     * the direct static Int/Double overloads included, so one number
     * convention holds across the whole trace on every platform.
     *
     * Both passes are linear scanners, not Regex: the Kotlin/Native
     * regex engine evaluates a lookbehind by scanning back to the start
     * of the input, which made these patterns quadratic on the giant
     * composite operands of the coverage tests. The scanners reproduce
     * the pattern semantics exactly:
     * SCIENTIFIC "(?<![\\d.])(-?\\d(?:\\.\\d+)?)[Ee]([+-]?\\d+)" and
     * WHOLE_FRACTION "(?<=\\d)\\.0+(?![\\d.])".
     */
    internal fun canonicalNumbers(text: String): String =
        stripWholeFractionPass(expandScientificPass(text))

    /**
     * Expands scientific notation by exact string point-shifting;
     * re-formatting through a Double could re-round the value. The JVM
     * prints doubles below 1e-3 in scientific notation while Kotlin/JS
     * prints plain decimals ("8.0E-4" vs "0.0008").
     */
    private fun expandScientificPass(input: String): String {
        val out = StringBuilder(input.length)
        var copied = 0
        var e = 0
        while (e < input.length) {
            val c = input[e]
            if (c != 'E' && c != 'e') {
                e++
                continue
            }
            val match = scientificMatchAt(input, e, copied) ?: run {
                e++
                continue
            }
            out.append(input, copied, match.start)
            out.append(
                expandScientific(
                    input.substring(match.start, match.mantissaEnd),
                    input.substring(e + 1, match.end).toInt(),
                ),
            )
            copied = match.end
            e = match.end
        }
        out.append(input, copied, input.length)
        return out.toString()
    }

    private class ScientificMatch(val start: Int, val mantissaEnd: Int, val end: Int)

    /**
     * One match of the SCIENTIFIC pattern at the letter [eIndex]. The
     * mantissa "-?\d(\.\d+)?" must end right before the letter, the
     * exponent "[+-]?\d+" must follow it, the char before the mantissa
     * must be neither a digit nor a dot, and the mantissa must not reach
     * before [floor] (matches cannot overlap a previous match). The
     * greedy-fraction candidate sits left of the bare-digit candidate,
     * and the minus form left of the unsigned form, matching the
     * leftmost-first order of a regex engine.
     */
    private fun scientificMatchAt(s: String, eIndex: Int, floor: Int): ScientificMatch? {
        var i = eIndex + 1
        if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
        val digitsStart = i
        while (i < s.length && s[i] in '0'..'9') i++
        if (i == digitsStart) return null
        val end = i

        val last = eIndex - 1
        if (last < floor || s[last] !in '0'..'9') return null
        var runStart = last
        while (runStart > floor && s[runStart - 1] in '0'..'9') runStart--
        // Fraction form: a dot and one leading digit before the digit run.
        val lead = if (runStart > floor + 1 && s[runStart - 1] == '.' && s[runStart - 2] in '0'..'9') {
            runStart - 2
        } else {
            // Bare form: a lone digit, so anything before it inside the
            // run would fail the lookbehind anyway.
            if (runStart != last) return null
            last
        }

        // Greedy "-?" first: the signed candidate starts one char left of
        // the unsigned one; if the digit before the minus fails the
        // lookbehind, the engine retries at the digit with '-' before it.
        if (lead > 0 && s[lead - 1] == '-' && lead - 1 >= floor) {
            val beforeMinus = lead >= 2 && (s[lead - 2] in '0'..'9' || s[lead - 2] == '.')
            if (!beforeMinus) return ScientificMatch(lead - 1, eIndex, end)
            return ScientificMatch(lead, eIndex, end)
        }
        val beforeDigit = lead > 0 && (s[lead - 1] in '0'..'9' || s[lead - 1] == '.')
        if (!beforeDigit) return ScientificMatch(lead, eIndex, end)
        return null
    }

    private fun expandScientific(mantissa: String, exponent: Int): String {
        val negative = mantissa.startsWith("-")
        val digits = mantissa.removePrefix("-").replace(".", "")
        val point = mantissa.removePrefix("-").substringBefore('.').length + exponent
        val plain = when {
            point <= 0 -> "0." + "0".repeat(-point) + digits
            point >= digits.length -> digits + "0".repeat(point - digits.length)
            else -> digits.substring(0, point) + "." + digits.substring(point)
        }
        return if (negative) "-$plain" else plain
    }

    /** Strips ".0+" preceded by a digit and followed by neither digit
     *  nor dot. A shorter zero run would end on a digit, so checking the
     *  maximal run decides the lookahead exactly. */
    private fun stripWholeFractionPass(input: String): String {
        val out = StringBuilder(input.length)
        var copied = 0
        var p = 0
        while (p < input.length) {
            if (input[p] != '.' || p == 0 || input[p - 1] !in '0'..'9') {
                p++
                continue
            }
            var q = p + 1
            while (q < input.length && input[q] == '0') q++
            if (q == p + 1 || (q < input.length && (input[q] in '0'..'9' || input[q] == '.'))) {
                p++
                continue
            }
            out.append(input, copied, p)
            copied = q
            p = q
        }
        out.append(input, copied, input.length)
        return out.toString()
    }

    private fun compositeNumber(v: Double): String = canonicalNumbers(TraceFormat.fd(v, 6))

    /** Caps rendered text; the marker keeps full length + FNV-1a of the
     *  untruncated text so truncated operands stay distinguishable.
     *  Canonicalization happens BEFORE truncation: the JVM raw text
     *  carries whole-double ".0" suffixes Kotlin/JS never prints, so
     *  hashing or measuring the raw text would make identical values
     *  produce different markers on the two platforms. */
    private fun cap(text: String): String {
        val canonical = canonicalNumbers(text)
        if (canonical.length <= MAX_OPERAND_CHARS) return canonical
        return canonical.take(MAX_OPERAND_CHARS) +
            "~" + canonical.length + "#" + fnv1a(canonical)
    }

    /** FNV-1a 32-bit over UTF-16 code units; identical on every target. */
    private fun fnv1a(text: String): String {
        var hash = 0x811C9DC5u
        for (ch in text) {
            hash = hash xor ch.code.toUInt()
            hash *= 0x01000193u
        }
        return hash.toString(16)
    }
}
