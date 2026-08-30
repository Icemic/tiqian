package org.tiqian.layout

import kotlin.math.abs
import org.tiqian.core.Cluster
import org.tiqian.core.DecorationKind
import org.tiqian.core.LayoutResult
import org.tiqian.core.PunctuationDecisionInfo
import org.tiqian.core.ShapingDecisionInfo
import org.tiqian.core.TextRange
import org.tiqian.core.TextStyle
import org.tiqian.core.positionedClusters
import org.tiqian.font.FontRole

/** Wire fields of the prepared-paragraph plan; readers reject other values. */
public const val PREPARED_PARAGRAPH_SCHEMA: Int = 1
public const val PREPARED_PARAGRAPH_LAYOUT_REVISION: String = "tiqian-layout-v2"

/**
 * Canonical plain-paragraph render plan shared by build-time snapshots and the
 * browser exact-font fallback. Keeping this lowering beside [LayoutResult]
 * prevents the two Web entry points from growing independent DOM geometry.
 *
 * `renderEvidence = true` appends the engine evidence the DOM lowerer needs
 * (ADR 0053 SinglePlanLowerer): render font overrides, dash replay data,
 * punctuation ink floors, ruby / bopomofo geometry, interlinear segments,
 * emphasis dots, per-cluster style deltas and inline-object edges. Every
 * appended field is optional and omitted at its default so plain paragraphs
 * stay byte-identical to the evidence-free form; the schema stays 1 because
 * both existing readers (prepared-dom.js, tiqian-precompute plan.rs) read
 * fields by name and ignore the unknown ones.
 */
fun LayoutResult.toPreparedParagraphJson(renderEvidence: Boolean = false): String {
    val naturalWidth = mutableMapOf<TextRange, Float>()
    val openTypeFeatures = mutableMapOf<TextRange, LinkedHashSet<String>>()
    val renderFontFamily = mutableMapOf<TextRange, String>()
    val glyphIdsByRange = mutableMapOf<TextRange, MutableList<UInt>>()
    for (run in glyphRuns) {
        for (glyph in run.glyphs) {
            naturalWidth[glyph.clusterRange] =
                (naturalWidth[glyph.clusterRange] ?: 0f) + glyph.advance
            if (run.openTypeFeatures.isNotEmpty()) {
                openTypeFeatures.getOrPut(glyph.clusterRange) { linkedSetOf() }
                    .addAll(run.openTypeFeatures)
            }
            // Last non-null renderFontKey per cluster wins, mirroring the DOM
            // renderer's map assignment.
            glyph.renderFontKey?.let { renderFontFamily[glyph.clusterRange] = it }
            glyphIdsByRange.getOrPut(glyph.clusterRange) { mutableListOf() }.add(glyph.id)
        }
    }
    val zeroWidthBreaks = debug.zeroWidthBreakDecisions.mapTo(mutableSetOf()) { it.range }
    val shapingDecisionByRange = debug.shapingDecisions.associateBy { it.range }
    val punctuationDecisionByRange = debug.punctuationDecisions.associateBy { it.range }
    val inlineStartByOffset = HashMap<Int, Float>()
    val inlineEndByOffset = HashMap<Int, Float>()
    for (box in input.inlineBoxes) {
        if (box.inlineStart != 0f) {
            inlineStartByOffset[box.range.start] =
                (inlineStartByOffset[box.range.start] ?: 0f) + box.inlineStart
        }
        if (box.inlineEnd != 0f) {
            inlineEndByOffset[box.range.end] =
                (inlineEndByOffset[box.range.end] ?: 0f) + box.inlineEnd
        }
    }
    val inlineObjectAdvanceByRange = input.inlineObjects.associate { it.range to it.advance }
    fun styleAt(offset: Int): TextStyle =
        input.content.spans.lastOrNull { offset >= it.range.start && offset < it.range.end }?.style
            ?: input.textStyle
    return buildString {
        append('{')
        append("\"schema\":").append(PREPARED_PARAGRAPH_SCHEMA).append(',')
        append("\"layoutRevision\":\"").append(PREPARED_PARAGRAPH_LAYOUT_REVISION).append("\",")
        append("\"width\":").appendJsonNumber(input.constraints.maxWidth).append(',')
        append("\"height\":").appendJsonNumber(size.height).append(',')
        append("\"lines\":[")
        lines.forEachIndexed { lineIndex, line ->
            if (lineIndex > 0) append(',')
            val cells = positionedClusters(line).filter { positioned ->
                val cluster = clusters[positioned.clusterIndex]
                cluster.displayText.isNotEmpty() || cluster.range in zeroWidthBreaks ||
                    (renderEvidence && cluster.range in inlineObjectAdvanceByRange)
            }
            append('{')
            append("\"rangeStart\":").append(line.range.start).append(',')
            append("\"rangeEnd\":").append(line.range.end).append(',')
            append("\"top\":").appendJsonNumber(line.top).append(',')
            append("\"bottom\":").appendJsonNumber(line.bottom).append(',')
            append("\"baseline\":").appendJsonNumber(line.baseline).append(',')
            append("\"indent\":").appendJsonNumber(line.indent).append(',')
            append("\"visualWidth\":").appendJsonNumber(line.visualWidth).append(',')
            append("\"hyphenAdvance\":").appendJsonNumber(line.hyphenAdvance).append(',')
            append("\"endReason\":").appendJsonString(line.endReason.name).append(',')
            append("\"cells\":[")
            cells.forEachIndexed { cellIndex, positioned ->
                if (cellIndex > 0) append(',')
                val cluster = clusters[positioned.clusterIndex]
                append('{')
                append("\"rangeStart\":").append(cluster.range.start).append(',')
                append("\"rangeEnd\":").append(cluster.range.end).append(',')
                append("\"source\":").appendJsonString(cluster.text).append(',')
                append("\"display\":").appendJsonString(cluster.displayText).append(',')
                append("\"drawX\":").appendJsonNumber(positioned.drawX).append(',')
                append("\"naturalWidth\":")
                    .appendJsonNumber(naturalWidth[cluster.range] ?: cluster.advance).append(',')
                append("\"leadingLayoutAdvance\":")
                    .appendJsonNumber(cluster.leadingLayoutAdvance)
                // MultiCodeUnitShapingBoundary: Latin words, URLs, emoji, and
                // other multi-unit clusters are already independently shaped
                // by the core. DOM text must not merge adjacent clusters and
                // ask the browser to shape a different, wider run.
                if (cluster.range.end - cluster.range.start > 1) {
                    append(",\"shapingBoundary\":true")
                }
                openTypeFeatures[cluster.range]?.takeIf { it.isNotEmpty() }?.let { features ->
                    append(",\"openTypeFeatures\":[")
                    features.forEachIndexed { featureIndex, feature ->
                        if (featureIndex > 0) append(',')
                        appendJsonString(feature)
                    }
                    append(']')
                }
                if (renderEvidence) {
                    appendCellRenderEvidence(
                        out = this,
                        cluster = cluster,
                        naturalWidth = naturalWidth,
                        renderFontFamily = renderFontFamily,
                        glyphIdsByRange = glyphIdsByRange,
                        shapingDecisionByRange = shapingDecisionByRange,
                        punctuationDecisionByRange = punctuationDecisionByRange,
                        inlineObjectAdvanceByRange = inlineObjectAdvanceByRange,
                        styleAt = ::styleAt,
                    )
                }
                append('}')
            }
            append("]}")
        }
        append(']')
        if (renderEvidence) {
            appendParagraphRenderEvidence(
                out = this,
                inlineStartByOffset = inlineStartByOffset,
                inlineEndByOffset = inlineEndByOffset,
            )
        }
        append('}')
    }
}

/**
 * Plan-plus-diagnostics envelope for the TsHost worker/precompute path
 * (ADR 0053). The plan is embedded as an escaped JSON string value so the
 * host decodes a single document. Diagnostics carry facts only — the verdicts
 * for the web pipeline's named checks stay host-side: the capability-issue
 * and InvalidWebShapingAdvance checks read these two lists, while the
 * clone-decoration cross-line check reads the plan lines' rangeStart/rangeEnd,
 * which is why it has no diagnostics entry. [zeroAdvanceEpsilonPx] is the host
 * threshold passed through so the layout module holds no host policy.
 */
fun LayoutResult.toPlanWithDiagnosticsJson(renderEvidence: Boolean, zeroAdvanceEpsilonPx: Float): String =
    buildString {
        append("{\"plan\":")
        appendJsonString(toPreparedParagraphJson(renderEvidence))
        append(",\"diagnostics\":{\"capabilityIssues\":[")
        var firstCapabilityIssue = true
        for (decision in debug.shapingDecisions) {
            val capabilityIssue = decision.capabilityIssue ?: continue
            if (!firstCapabilityIssue) append(',')
            firstCapabilityIssue = false
            append("{\"name\":").appendJsonString(capabilityIssue)
            append(",\"reason\":").appendJsonString(decision.reason)
            append(",\"rangeStart\":").append(decision.range.start)
            append(",\"rangeEnd\":").append(decision.range.end)
            append('}')
        }
        append("],\"advanceSuspects\":[")
        var firstAdvanceSuspect = true
        for (decision in debug.shapingDecisions) {
            if (decision.advance.isFinite() && decision.advance > zeroAdvanceEpsilonPx) continue
            if (!firstAdvanceSuspect) append(',')
            firstAdvanceSuspect = false
            append("{\"displayText\":").appendJsonString(decision.displayText)
            // The advance is always a JSON string: ecmaJsonNumber normalizes finite
            // values across platforms; toString renders "NaN" and "Infinity" so the
            // non-finite cases survive the wire.
            val advanceJson = if (decision.advance.isFinite()) ecmaJsonNumber(decision.advance) else decision.advance.toString()
            append(",\"advance\":\"").append(advanceJson).append('"')
            append(",\"reason\":").appendJsonString(decision.reason)
            append(",\"rangeStart\":").append(decision.range.start)
            append(",\"rangeEnd\":").append(decision.range.end)
            append('}')
        }
        append("]}}")
    }

/**
 * Per-cell render evidence (all fields omitted at default). Field values and
 * lookup orders mirror DomParagraphRenderer so the plan-driven lowerer paints
 * the same DOM: `expectedShapedAdvance` is deliberately absent because it is
 * the cell's `naturalWidth` for every dash cluster.
 */
private fun LayoutResult.appendCellRenderEvidence(
    out: StringBuilder,
    cluster: Cluster,
    naturalWidth: Map<TextRange, Float>,
    renderFontFamily: Map<TextRange, String>,
    glyphIdsByRange: Map<TextRange, List<UInt>>,
    shapingDecisionByRange: Map<TextRange, ShapingDecisionInfo>,
    punctuationDecisionByRange: Map<TextRange, PunctuationDecisionInfo>,
    inlineObjectAdvanceByRange: Map<TextRange, Float>,
    styleAt: (Int) -> TextStyle,
) {
    val inlineObjectAdvance = inlineObjectAdvanceByRange[cluster.range]
    if (inlineObjectAdvance != null) {
        out.append(",\"inlineObject\":")
        out.appendJsonNumber(inlineObjectAdvance)
    }
    val glyphWidth = inlineObjectAdvance
        ?: naturalWidth[cluster.range]
        ?: cluster.advance
    if (cluster.advance != glyphWidth) {
        out.append(",\"advance\":")
        out.appendJsonNumber(cluster.advance)
    }
    renderFontFamily[cluster.range]?.let { family ->
        out.append(",\"renderFontFamily\":")
        out.appendJsonString(family)
    }
    val shapingDecision = shapingDecisionByRange[cluster.range]
    val dashStrategy = shapingDecision?.strategy
    if (dashStrategy != null) {
        out.append(",\"dashStrategy\":")
        out.appendJsonString(dashStrategy)
        shapingDecision.language?.let { language ->
            out.append(",\"shapingLanguage\":")
            out.appendJsonString(language)
        }
        shapingDecision.resolvedFace?.let { face ->
            out.append(",\"resolvedFace\":")
            out.appendJsonString(face)
        }
        glyphIdsByRange[cluster.range]?.takeIf { it.isNotEmpty() }?.let { ids ->
            out.append(",\"glyphIds\":")
            out.appendJsonString(ids.joinToString(","))
        }
        out.append(",\"shapingEvidence\":")
        out.appendJsonString(shapingDecision.reason)
    }
    val punctuationDecision = punctuationDecisionByRange[cluster.range]
    if (punctuationDecision?.inkContainmentApplied == true) {
        punctuationDecision.inkContainmentBodyFloor?.let { floor ->
            out.append(",\"punctuationInkFloor\":")
            out.appendJsonNumber(floor)
            out.append(",\"punctuationBodyWidth\":")
            out.appendJsonNumber(punctuationDecision.bodyWidth)
        }
    }
    val latin = FontRole.LatinText.name ==
        debug.fontDecisions.firstOrNull {
            cluster.range.start >= it.range.start && cluster.range.end <= it.range.end
        }?.role
    if (latin) {
        out.append(",\"latin\":true")
    }
    val clusterStyle = styleAt(cluster.range.start)
    if (clusterStyle != input.textStyle) {
        // Only paint-relevant deltas are listed; a TextStyle that differs in
        // non-paint fields alone (fontFamilies, locale, baselineShift) emits
        // an empty object, which still marks the cluster as non-default.
        out.append(",\"style\":{")
        var fieldCount = 0
        if (clusterStyle.fontSize != input.textStyle.fontSize) {
            out.append("\"fontSize\":")
            out.appendJsonNumber(clusterStyle.fontSize)
            fieldCount += 1
        }
        if (clusterStyle.fontWeight != input.textStyle.fontWeight) {
            if (fieldCount > 0) out.append(',')
            out.append("\"fontWeight\":").append(clusterStyle.fontWeight)
            fieldCount += 1
        }
        if (clusterStyle.italic != input.textStyle.italic) {
            if (fieldCount > 0) out.append(',')
            out.append("\"italic\":").append(clusterStyle.italic)
        }
        out.append('}')
    }
}

private fun LayoutResult.appendParagraphRenderEvidence(
    out: StringBuilder,
    inlineStartByOffset: Map<Int, Float>,
    inlineEndByOffset: Map<Int, Float>,
) {
    out.append(",\"fontSize\":").appendJsonNumber(input.textStyle.fontSize)
    out.append(",\"overlayWidth\":").appendJsonNumber(size.width)
    val emphasisRanges = input.decorations.filter { it.kind == DecorationKind.Emphasis }
    if (emphasisRanges.isNotEmpty()) {
        out.append(",\"emphasisRanges\":[")
        emphasisRanges.forEachIndexed { index, span ->
            if (index > 0) out.append(',')
            out.append('[').append(span.range.start).append(',')
                .append(span.range.end).append(']')
        }
        out.append(']')
    }
    if (inlineStartByOffset.isNotEmpty() || inlineEndByOffset.isNotEmpty()) {
        out.append(",\"inlineEdges\":[")
        val offsets = (inlineStartByOffset.keys + inlineEndByOffset.keys).toSet().sorted()
        offsets.forEachIndexed { index, offset ->
            if (index > 0) out.append(',')
            out.append("{\"offset\":").append(offset)
            inlineStartByOffset[offset]?.let { start ->
                out.append(",\"inlineStart\":").appendJsonNumber(start)
            }
            inlineEndByOffset[offset]?.let { end ->
                out.append(",\"inlineEnd\":").appendJsonNumber(end)
            }
            out.append('}')
        }
        out.append(']')
    }
    if (debug.rubyDecisions.isNotEmpty()) {
        out.append(",\"rubyDecisions\":[")
        debug.rubyDecisions.forEachIndexed { index, ruby ->
            if (index > 0) out.append(',')
            out.append("{\"baseRangeStart\":").append(ruby.baseRange.start)
            out.append(",\"baseRangeEnd\":").append(ruby.baseRange.end)
            out.append(",\"text\":").appendJsonString(ruby.text)
            out.append(",\"centerX\":").appendJsonNumber(ruby.centerX)
            out.append(",\"baselineY\":").appendJsonNumber(ruby.baselineY)
            out.append(",\"fontSize\":").appendJsonNumber(ruby.fontSize)
            // RubyPlanAscentEvidence: the declared ascent of the annotation
            // face. The renderer cannot measure it in the string-builder
            // path; without this field it falls back to a fixed ratio that
            // only matches the stub metrics resolver.
            out.append(",\"ascent\":").appendJsonNumber(ruby.ascent)
            out.append(",\"fontWeight\":").append(ruby.fontWeight)
            if (ruby.fontFamilies.isNotEmpty()) {
                out.append(",\"fontFamilies\":[")
                ruby.fontFamilies.forEachIndexed { familyIndex, family ->
                    if (familyIndex > 0) out.append(',')
                    out.appendJsonString(family)
                }
                out.append(']')
            }
            out.append('}')
        }
        out.append(']')
    }
    if (debug.bopomofoDecisions.isNotEmpty()) {
        out.append(",\"bopomofoDecisions\":[")
        debug.bopomofoDecisions.forEachIndexed { index, z ->
            if (index > 0) out.append(',')
            out.append("{\"baseRangeStart\":").append(z.baseRange.start)
            out.append(",\"baseRangeEnd\":").append(z.baseRange.end)
            out.append(",\"text\":").appendJsonString(z.text)
            out.append(",\"fontWeight\":").append(z.fontWeight)
            if (z.fontFamilies.isNotEmpty()) {
                out.append(",\"fontFamilies\":[")
                z.fontFamilies.forEachIndexed { familyIndex, family ->
                    if (familyIndex > 0) out.append(',')
                    out.appendJsonString(family)
                }
                out.append(']')
            }
            out.append(",\"placements\":[")
            z.placements.forEachIndexed { placementIndex, placement ->
                if (placementIndex > 0) out.append(',')
                out.append("{\"text\":").appendJsonString(placement.text)
                out.append(",\"left\":").appendJsonNumber(placement.left)
                out.append(",\"top\":").appendJsonNumber(placement.top)
                out.append(",\"width\":").appendJsonNumber(placement.width)
                out.append(",\"height\":").appendJsonNumber(placement.height)
                out.append(",\"role\":").appendJsonString(placement.role.name)
                out.append('}')
            }
            out.append("]}")
        }
        out.append(']')
    }
    val interlinearSegments = debug.decorationSegments.filter {
        it.kind == DecorationKind.ProperNoun.name || it.kind == DecorationKind.BookTitle.name
    }
    if (interlinearSegments.isNotEmpty()) {
        out.append(",\"decorationSegments\":[")
        interlinearSegments.forEachIndexed { index, seg ->
            if (index > 0) out.append(',')
            out.append("{\"kind\":").appendJsonString(seg.kind)
            out.append(",\"left\":").appendJsonNumber(seg.left)
            out.append(",\"top\":").appendJsonNumber(seg.top)
            out.append(",\"right\":").appendJsonNumber(seg.right)
            out.append(",\"sourceRangeStart\":").append(seg.sourceRange.start)
            out.append(",\"sourceRangeEnd\":").append(seg.sourceRange.end)
            out.append('}')
        }
        out.append(']')
    }
    val emphasisDots = debug.decorationDecisions.filter {
        it.applied && it.kind == DecorationKind.Emphasis.name && it.dotDiameter > 0f
    }
    if (emphasisDots.isNotEmpty()) {
        out.append(",\"emphasisDots\":[")
        emphasisDots.forEachIndexed { index, dot ->
            if (index > 0) out.append(',')
            out.append("{\"clusterRangeStart\":").append(dot.clusterRange.start)
            out.append(",\"anchorX\":").appendJsonNumber(dot.anchorX)
            out.append(",\"anchorY\":").appendJsonNumber(dot.anchorY)
            out.append(",\"dotDiameter\":").appendJsonNumber(dot.dotDiameter)
            out.append('}')
        }
        out.append(']')
    }
}

/**
 * Plan JSON numbers use ECMAScript `Number::toString` layout on every Kotlin
 * backend. `Float.toString` differs per platform: Kotlin/JS prints the f64
 * widening, JVM and Native print the f32 shortest form with a forced fraction.
 * `Double.toString` also differs per platform: Kotlin/Native prints strings
 * that do not round-trip for powers of two. The digit count therefore comes
 * from the exact shortest-round-trip search of [shortestRoundTripDigits], and
 * the digit content from the f32 grid value through [canonicalFloatDigits];
 * only the layout is normalized here.
 */
private fun StringBuilder.appendJsonNumber(value: Float): StringBuilder =
    append(if (value == -0f) "0" else ecmaJsonNumber(value))

/**
 * Single source for boundary serialization; `ffi/js` consumes these helpers.
 *
 * A Kotlin/JS `Float` is a double at runtime and can hold a value off the
 * f32 grid (literals and mixed arithmetic are not rounded to f32 there), so
 * the digit count is taken from the runtime double while the digit content
 * is re-derived from the f32 grid value the `Float` type denotes. On JVM and
 * Native the runtime value already is the widened f32, so the two agree.
 */
public fun ecmaJsonNumber(floatValue: Float): String {
    if (floatValue.isNaN()) return "NaN"
    if (floatValue.isInfinite()) return if (floatValue < 0f) "-Infinity" else "Infinity"
    if (floatValue == 0f) return "0"
    val negative = floatValue < 0f
    val magnitudeValue = abs(floatValue)
    val (shortestDigits, decimalExponent) = shortestRoundTripDigits(magnitudeValue)
    val digits = canonicalFloatDigits(magnitudeValue, shortestDigits)

    val k = digits.length
    val n = decimalExponent
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

/**
 * The shortest round-trip digits of the runtime double behind [magnitude],
 * with the decimal exponent `n` such that the value is
 * `digits × 10^(n - digits.length)`.
 *
 * The digit count is the shortest length at which a decimal parses back to
 * the double (the platform `Double.toString` length); the digits are the
 * exact binary expansion rounded half to even at that count. The two agree
 * except at an exact decimal half beside an asymmetric power-of-two
 * boundary, where the half-even candidate sits outside the rounding
 * interval: the engine keeps the even digit there, and the Float still
 * round-trips through the f32 parse the consumers perform.
 *
 * The search expands the value and both rounding-interval boundaries of the
 * f64 into exact decimal integers, so the result depends on the bits alone
 * and never on a platform `Double.toString` (Kotlin/Native prints
 * non-round-tripping strings for powers of two). A widened Float is always
 * normal in f64, so the two boundary cases below cover every input.
 */
internal fun shortestRoundTripDigits(magnitude: Float): Pair<String, Int> {
    val bits64 = magnitude.toDouble().toRawBits()
    val mantissa = (bits64 and 0x000FFFFFFFFFFFFFL) or 0x0010000000000000L
    val exponent = ((bits64 ushr 52) and 0x7FF).toInt() - 1075
    // magnitude == mantissa × 2^exponent exactly.

    // Exact decimal digits of the value; n places the leading digit. For a
    // negative exponent the expansion is mantissa × 5^k with the value at
    // (that integer) × 10^exponent, so n folds in the exponent; for a
    // non-negative exponent the expansion is the plain integer mantissa × 2^k.
    val expansion = dyadicDecimal(mantissa, exponent)
    var exact = expansion.first
    val n = expansion.second
    exact = exact.trimEnd('0')

    // The interval of decimals that parse back to the value: value ± half
    // ulp. At a binade floor (mantissa == 2^52) the neighbor below lives in
    // the finer binade, so the lower boundary is one quarter of the ulp
    // away instead of one half.
    val hi = dyadicDecimal(mantissa * 2 + 1, exponent - 1)
    val lo = if (mantissa == 0x0010000000000000L) {
        dyadicDecimal(mantissa * 4 - 1, exponent - 2)
    } else {
        dyadicDecimal(mantissa * 2 - 1, exponent - 1)
    }
    // A decimal exactly on a boundary parses half to even; that lands on the
    // value only when its mantissa is even.
    val boundaryParsesToValue = mantissa % 2 == 0L

    fun insideInterval(digits: String, digitsN: Int): Boolean {
        val versusLo = compareDecimal(digits, digitsN, lo.first, lo.second)
        val versusHi = compareDecimal(digits, digitsN, hi.first, hi.second)
        val aboveLo = versusLo > 0 || (versusLo == 0 && boundaryParsesToValue)
        val belowHi = versusHi < 0 || (versusHi == 0 && boundaryParsesToValue)
        return aboveLo && belowHi
    }

    // The digit count is the shortest length at which the truncated or the
    // incremented candidate parses back; any other digit of that length is
    // farther out on the same side, so the disjunction decides existence.
    // The emitted digits are the half-even rounding at that count.
    for (length in 1..17) {
        val keep = exact.substring(0, length)
        val up = incrementDecimal(keep)
        val upN = n + (up.length - length)
        if (insideInterval(keep, n) || insideInterval(up, upN)) {
            val candidate = roundToSignificant(exact, length)
            return Pair(candidate.first, n + candidate.second)
        }
    }
    // Unreachable for finite doubles: a 17-significant-digit decimal always
    // parses back.
    return Pair(exact, n)
}

/**
 * Re-derives the digit content from the f32 grid value behind [magnitude]
 * (a Kotlin/JS `Float` can hold a double off the grid; the bits quantize it)
 * and rounds the exact expansion half to even at the digit count of
 * [doubleDigits]. A shorter result means the double digits were not
 * shortest; a longer one means a carry changed the digit count. Either way
 * the double digits are the safer answer.
 */
private fun canonicalFloatDigits(magnitude: Float, doubleDigits: String): String {
    val bits = magnitude.toRawBits() and 0x7FFFFFFF
    val biasedExponent = (bits ushr 23) and 0xFF
    var mantissa = bits and 0x7FFFFF
    if (mantissa == 0 && biasedExponent == 0) return doubleDigits
    val exponent = if (biasedExponent == 0) {
        -149
    } else {
        mantissa = mantissa or 0x800000
        biasedExponent - 150
    }

    val exact = if (exponent >= 0) {
        timesLong(twoToThe(exponent), mantissa.toLong())
    } else {
        // value = mantissa × 5^k × 10^-k; only the digits matter here, the
        // caller keeps the decimal scale.
        timesLong(fiveToThe(-exponent), mantissa.toLong())
    }
    val stripped = exact.trimEnd('0')
    if (stripped.length <= doubleDigits.length) return doubleDigits

    val rounded = roundToSignificant(stripped, doubleDigits.length).first
    return if (rounded.length == doubleDigits.length) rounded else doubleDigits
}

/**
 * p × 2^f as an exact decimal in the digits-and-n form: the value is
 * `digits × 10^(n - digits.length)`. The power strings are memoized, so a
 * sweep of same-binade values expands each quantity with one small-times-big
 * multiply instead of a chain of small multiplies.
 */
private fun dyadicDecimal(p: Long, f: Int): Pair<String, Int> {
    val digits = if (f < 0) {
        timesLong(fiveToThe(-f), p)
    } else {
        timesLong(twoToThe(f), p)
    }
    val n = if (f < 0) digits.length + f else digits.length
    return Pair(digits, n)
}

/**
 * 5^k as exact decimal digits; the layout engine is single-threaded, so a
 * plain map caches the chains across calls. The exponent of a finite double
 * keeps k below ~1100 and the digits below ~800.
 */
private val fivePowers = HashMap<Int, String>()

private fun fiveToThe(k: Int): String = fivePowers.getOrPut(k) {
    var anchorK = 0
    var digits = "1"
    for ((j, cached) in fivePowers) {
        if (j in 0 until k && j > anchorK) {
            anchorK = j
            digits = cached
        }
    }
    repeat(k - anchorK) { digits = timesSmall(digits, 5) }
    digits
}

/** 2^k as exact decimal digits, memoized like [fiveToThe]. */
private val twoPowers = HashMap<Int, String>()

private fun twoToThe(k: Int): String = twoPowers.getOrPut(k) {
    var anchorK = 0
    var digits = "1"
    for ((j, cached) in twoPowers) {
        if (j in 0 until k && j > anchorK) {
            anchorK = j
            digits = cached
        }
    }
    repeat(k - anchorK) { digits = timesSmall(digits, 2) }
    digits
}

/**
 * Multiplies a digit string by a factor below 2^57 exactly. The factor is
 * processed in 8-decimal-digit chunks so every per-digit product stays inside
 * Int; a Kotlin/JS `Long` is boxed, and the chunked form keeps the hot
 * serialization path free of boxed arithmetic.
 */
private fun timesLong(digits: String, factor: Long): String {
    var result: String? = null
    var shift = 0
    var remaining = factor
    while (remaining > 0) {
        val chunk = (remaining % 100000000L).toInt()
        remaining /= 100000000L
        if (chunk != 0) {
            var part = timesSmall(digits, chunk)
            if (shift > 0) part += "0".repeat(shift)
            result = if (result == null) part else addDecimal(result, part)
        } else if (result == null && remaining == 0L) {
            return "0"
        }
        shift += 8
    }
    return result ?: "0"
}

/** Adds two decimal digit strings exactly. */
private fun addDecimal(a: String, b: String): String {
    val out = StringBuilder(maxOf(a.length, b.length) + 1)
    var i = a.length - 1
    var j = b.length - 1
    var carry = 0
    while (i >= 0 || j >= 0 || carry > 0) {
        val sum = (if (i >= 0) a[i] - '0' else 0) +
            (if (j >= 0) b[j] - '0' else 0) +
            carry
        out.append(('0' + sum % 10))
        carry = sum / 10
        i--
        j--
    }
    return out.toString().reversed()
}

/**
 * Rounds the exact digit string (value `exact × 10^(n - exact.length)`) to
 * [length] significant digits, half to even. Returns the stripped digits and
 * the n shift caused by a carry past the kept window (999 -> 1000).
 */
private fun roundToSignificant(exact: String, length: Int): Pair<String, Int> {
    if (length >= exact.length) return Pair(exact, 0)
    val keep = exact.substring(0, length)
    val remainder = exact.substring(length)
    val pastHalf = remainder.length > 1 && remainder.substring(1).any { it != '0' }
    val roundUp = when {
        remainder[0] > '5' -> true
        remainder[0] < '5' -> false
        // Exact half rounds to even.
        else -> pastHalf || (keep.last() - '0') % 2 != 0
    }
    val rounded = if (roundUp) incrementDecimal(keep) else keep
    return Pair(rounded.trimEnd('0'), rounded.length - length)
}

/**
 * Compares `a × 10^(nA - a.length)` with `b × 10^(nB - b.length)` exactly;
 * both digit strings are free of leading zeros.
 */
private fun compareDecimal(a: String, nA: Int, b: String, nB: Int): Int {
    val eA = nA - a.length
    val eB = nB - b.length
    val aa = if (eA >= eB) a + "0".repeat(eA - eB) else a
    val bb = if (eB >= eA) b + "0".repeat(eB - eA) else b
    return when {
        aa.length != bb.length -> aa.length - bb.length
        else -> aa.compareTo(bb)
    }
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
    return out.toString().reversed()
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
