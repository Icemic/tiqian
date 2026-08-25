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
 * Without normalization the same LayoutResult yields different plan bytes per
 * host. Digits come from `Double.toString`; the last digit is normalized from
 * the exact Float expansion; only the layout is normalized here.
 */
private fun StringBuilder.appendJsonNumber(value: Float): StringBuilder =
    append(if (value == -0f) "0" else ecmaJsonNumber(value))

/**
 * Single source for boundary serialization; `ffi/js` consumes these helpers.
 */
public fun ecmaJsonNumber(floatValue: Float): String {
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

/**
 * dtoa libraries disagree only in the last digit of their shortest strings:
 * on exact decimal ties ECMAScript rounds half to even while some dtoa round
 * half up. Every number here is a Float, so the exact decimal expansion
 * (mantissa × 2^exponent with a 24-bit mantissa) is finite and small; rounding
 * that expansion to the platform digit count with half to even reproduces the
 * ECMAScript choice on every backend.
 */
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
