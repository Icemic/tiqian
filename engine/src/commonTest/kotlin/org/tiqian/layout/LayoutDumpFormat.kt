package org.tiqian.layout

import org.tiqian.core.LayoutInput
import org.tiqian.core.LayoutResult
import org.tiqian.core.TiqianTextContent
import org.tiqian.test.LayoutFixture

/**
 * Shared golden-dump builder: one fixture laid out with the deterministic stub
 * shaper under every breaker, serialized as the structured decision dump. The
 * common parity test compares this against the embedded golden on every
 * target; the JVM golden test compares and regenerates the checked-in files.
 */
internal fun layoutFixtureDump(
    fixture: LayoutFixture,
    textShaper: org.tiqian.shaping.TextShaper = org.tiqian.shaping.ExplainableStubTextShaper(),
    fontMetricsResolver: org.tiqian.font.FontMetricsResolver = org.tiqian.font.StubFontMetricsResolver(),
): String = buildString {
    appendLine("fixture: ${fixture.id}")
    appendLine("text: ${fixture.text.escapeDumpText()}")
    appendLine("maxWidth: ${fixture.constraints.maxWidth.dumpFmt()}")
    for ((label, breaker) in listOf(
        "greedy" to GreedyLineBreaker(),
        "lookahead" to LookaheadLineBreaker(),
        "paragraph-dp" to ParagraphDpLineBreaker(),
    )) {
        val hyphenator = if (fixture.useEnglishHyphenation) {
            org.tiqian.linebreak.EnglishHyphenation.enUs
        } else {
            org.tiqian.linebreak.NoHyphenator
        }
        val engine = if (fixture.pinBasicNoHang) {
            ExplainableStubParagraphLayoutEngine(
                lineBreaker = breaker,
                hyphenator = hyphenator,
                textShaper = textShaper,
                fontMetricsResolver = fontMetricsResolver,
                clreqProfileResolver = {
                    org.tiqian.clreq.ClreqProfile.MainlandHorizontal.copy(
                        kinsokuMode = org.tiqian.clreq.KinsokuMode.Fixed(
                            org.tiqian.clreq.KinsokuLevel.Basic,
                        ),
                    )
                },
            )
        } else {
            ExplainableStubParagraphLayoutEngine(
                lineBreaker = breaker,
                hyphenator = hyphenator,
                textShaper = textShaper,
                fontMetricsResolver = fontMetricsResolver,
            )
        }
        val result = engine.layout(
            LayoutInput(
                content = TiqianTextContent(
                    fixture.text,
                    lineBreakSpans = fixture.lineBreakSpans,
                ),
                constraints = fixture.constraints,
                paragraphStyle = org.tiqian.core.ParagraphStyle(
                    lineHeight = fixture.lineHeight,
                    firstLineIndent = fixture.firstLineIndentEm?.let { org.tiqian.core.Ic(it) },
                    rubyLineHeightMode = fixture.rubyLineHeightMode,
                    lineLengthGrid = fixture.lineLengthGrid,
                ),
                decorations = fixture.decorations,
                rubySpans = fixture.rubySpans,
            ),
        )
        append(result.decisionDump(label))
    }
}

internal fun LayoutResult.decisionDump(label: String): String = buildString {
    appendLine("== $label ==")
    appendLine("size ${size.width.dumpFmt()}x${size.height.dumpFmt()}")
    debug.lineLengthGridDecision?.let { g ->
        if (g.enabled && g.slack > 0f) {
            appendLine(
                "grid container=${g.containerWidth.dumpFmt()} measure=${g.measure.dumpFmt()}(${g.cells}字) " +
                    "slack=${g.slack.dumpFmt()} body=${g.bodyAlignment}@${g.bodyOffset.dumpFmt()}",
            )
        }
    }
    debug.firstLineIndentDecision?.let { f ->
        if (f.source != "Explicit") {
            appendLine(
                "firstindent ${f.resolvedEm.dumpFmt()}字 measure=${f.measureEm.dumpFmt()}字 " +
                    "threshold=${f.thresholdEm.dumpFmt()}字 ${f.source}",
            )
        }
    }
    debug.kinsokuDecision?.let { k ->
        appendLine("kinsoku measure=${k.measureEm.dumpFmt()}字 level=${k.level} hang=${k.hanging} reason=${k.reason}")
    }
    debug.contextualKinsokuDecisions.forEach { k ->
        appendLine(
            "context-kinsoku ${k.range.start}-${k.range.end} " +
                "source='${k.sourceText.escapeDumpText()}' cluster=${k.clusterIndex} " +
                "forbid=${k.forbiddenPosition} reason=${k.reason}" +
                (k.impossibleMeasureFallback?.let { " fallback=$it" } ?: ""),
        )
    }
    debug.breakOpportunityDecisions.forEach { decision ->
        appendLine(
            "break-opportunity ${decision.range.start}-${decision.range.end} " +
                "source='${decision.sourceText.escapeDumpText()}' " +
                "offsets=${decision.breakOffsets.joinToString(",")}" +
                (decision.tier?.let { " tier=$it" } ?: "") +
                " reason=${decision.reason}",
        )
    }
    debug.emergencyTrackingEligibilityDecisions.forEach { decision ->
        appendLine(
            "tracking-eligibility ${decision.range.start}-${decision.range.end} " +
                "source='${decision.sourceText.escapeDumpText()}' reason=${decision.reason}",
        )
    }
    debug.inlineObjectPunctuationAttachmentDecisions.forEach { attachment ->
        appendLine(
            "inline-object-punctuation ${attachment.objectRange.start}-${attachment.objectRange.end} " +
                "separator=${attachment.separatorRange.start}-${attachment.separatorRange.end} " +
                "punctuation=${attachment.punctuationRange.start}-${attachment.punctuationRange.end} " +
                "source='${attachment.punctuationText.escapeDumpText()}' " +
                "collapsed=${attachment.collapsedAdvance.dumpFmt()} " +
                "protected=${attachment.protectedRange.start}-${attachment.protectedRange.end} " +
                "reason=${attachment.reason}",
        )
    }
    lines.forEachIndexed { i, line ->
        val decision = debug.lineDecisions.getOrNull(i)
        val repair = decision?.repairDecision?.let { r ->
            "${r.kind}(${r.reasonCode} shrink=${r.shrink.dumpFmt()})"
        } ?: "-"
        val candidates = decision?.repairCandidates.orEmpty()
            .joinToString(",") { "${it.kind}${if (it.accepted) "+" else "-"}" }
            .ifEmpty { "-" }
        val justify = debug.justificationDecisions.firstOrNull { it.lineRange == line.range }
            ?.let { j ->
                "deficit=${j.deficitBefore.dumpFmt()}->${j.deficitAfter.dumpFmt()}" +
                    j.allocations
                        .joinToString(",") {
                            "${it.kind}@${it.clusterRange.start}+${it.delta.dumpFmt()}" +
                                if (it.reason == it.kind) "" else "(${it.reason})"
                        }
                        .takeIf { it.isNotEmpty() }
                        ?.let { " $it" }
                        .orEmpty()
            } ?: "-"
        val indent = if (line.indent > 0f) "indent=${line.indent.dumpFmt()} " else ""
        val hyphen = if (line.hyphenAdvance > 0f) "hyphen=${line.hyphenAdvance.dumpFmt()} " else ""
        appendLine(
            "line[$i] ${line.range.start}-${line.range.end} $indent$hyphen" +
                "natural=${line.naturalWidth.dumpFmt()} adjusted=${line.adjustedWidth.dumpFmt()} " +
                "visual=${line.visualWidth.dumpFmt()} repair=$repair candidates=$candidates justify=$justify",
        )
    }
    clusters.forEach { c ->
        appendLine(
            "cluster ${c.range.start}-${c.range.end} '${c.displayText}' adv=${c.advance.dumpFmt()}" +
                (if (c.glyphInlineShift != 0f) " glyphShift=${c.glyphInlineShift.dumpFmt()}" else ""),
        )
    }
    debug.fontDecisions.forEach { f ->
        appendLine(
            "font ${f.range.start}-${f.range.end} role=${f.role} key=${f.fontKey} " +
                "display='${f.displayText}' sub=${f.substitutionReason}",
        )
    }
    debug.roleOverrides.forEach { role ->
        appendLine(
            "role-override ${role.range.start}-${role.range.end} " +
                "source='${role.sourceText.escapeDumpText()}' " +
                "${role.originalRole}->${role.overriddenRole} " +
                "policy=${role.source} reason=${role.reason}",
        )
    }
    debug.punctuationDecisions.forEach { p ->
        appendLine(
            "punct ${p.range.start}-${p.range.end} '${p.char}' class=${p.punctuationClass} " +
                "adv=${p.advance.dumpFmt()} body=${p.bodyWidth.dumpFmt()} " +
                "lead=${p.leadingGlueNatural.dumpFmt()} trail=${p.trailingGlueNatural.dumpFmt()} " +
                (if (p.leadingGlueInitiallyConsumed != 0f || p.trailingGlueInitiallyConsumed != 0f) {
                    "initial=${p.leadingGlueInitiallyConsumed.dumpFmt()}/${p.trailingGlueInitiallyConsumed.dumpFmt()} "
                } else {
                    ""
                }) +
                "anchor=${p.anchor} source=${p.geometrySource}" +
                (if (p.advanceExpansion != 0f) " expand=${p.advanceExpansion.dumpFmt()}" else "") +
                (if (p.glyphInlineShift != 0f) " glyphShift=${p.glyphInlineShift.dumpFmt()}" else "") +
                (p.glyphPlacementReason?.let { " placement=$it" } ?: "") +
                (p.haltAdvance?.let { " halt=${it.dumpFmt()}" } ?: "") +
                (p.inkBoundsFallback?.let { " fallback=$it" } ?: "") +
                (p.haltValidation?.let { " haltWarn=$it" } ?: ""),
        )
    }
    debug.geometryDecisions.forEach { g ->
        appendLine(
            "geom ${g.range.start}-${g.range.end} body=${g.bodyWidth.dumpFmt()} " +
                "lead=${g.leadingGlueConsumed.dumpFmt()}/${g.leadingGlueNatural.dumpFmt()} " +
                "trail=${g.trailingGlueConsumed.dumpFmt()}/${g.trailingGlueNatural.dumpFmt()} " +
                "justify=${g.justificationDelta.dumpFmt()}" +
                (if (g.rubySpread != 0f) " ruby=${g.rubySpread.dumpFmt()}" else "") +
                (if (g.glyphInlineShift != 0f) " glyphShift=${g.glyphInlineShift.dumpFmt()}" else "") +
                (g.glyphPlacementReason?.let { " placement=$it" } ?: "") +
                " resolved=${g.resolvedAdvance.dumpFmt()}",
        )
    }
    debug.inlineBoxDecisions.forEach { box ->
        appendLine(
            "inline-box ${box.range.start}-${box.range.end} " +
                "start=${box.inlineStart.dumpFmt()} end=${box.inlineEnd.dumpFmt()} " +
                "outer=${box.outerSpacing} " +
                "clusters=${box.firstClusterIndex}-${box.lastClusterIndex} reason=${box.reason}",
        )
    }
    debug.inlineObjectDecisions.forEach { inlineObject ->
        appendLine(
            "inline-object ${inlineObject.range.start}-${inlineObject.range.end} " +
                "advance=${inlineObject.advance.dumpFmt()} ascent=${inlineObject.ascent.dumpFmt()} " +
                "descent=${inlineObject.descent.dumpFmt()} cluster=${inlineObject.clusterIndex} " +
                "line=${inlineObject.lineIndex} " +
                "edges=${if (inlineObject.leadingUniformStretch) "stretch" else "fixed"}/" +
                "${inlineObject.leadingPreferredStretchKind ?: "-"}/" +
                "${inlineObject.leadingPreferredStretchNaturalWidth.dumpFmt()}→" +
                "${inlineObject.leadingPreferredStretchTargetWidth.dumpFmt()}/" +
                "${inlineObject.leadingPreferredStretchCapacity.dumpFmt()}/" +
                "${if (inlineObject.leadingPreventsLineBreak) "closed" else "natural"}/" +
                "${inlineObject.leadingShrinkCapacity.dumpFmt()}/" +
                "${inlineObject.leadingLineEndDiscardableAdvance.dumpFmt()}.." +
                "${if (inlineObject.trailingUniformStretch) "stretch" else "fixed"}/" +
                "${inlineObject.trailingPreferredStretchKind ?: "-"}/" +
                "${inlineObject.trailingPreferredStretchNaturalWidth.dumpFmt()}→" +
                "${inlineObject.trailingPreferredStretchTargetWidth.dumpFmt()}/" +
                "${inlineObject.trailingPreferredStretchCapacity.dumpFmt()}/" +
                "${if (inlineObject.trailingPreventsLineBreak) "closed" else "natural"}/" +
                "${inlineObject.trailingShrinkCapacity.dumpFmt()}/" +
                "${inlineObject.trailingLineEndDiscardableAdvance.dumpFmt()} reason=${inlineObject.reason}",
        )
    }
    debug.spacingDecisions.forEach { s ->
        appendLine(
            "spacing ${s.range.start}-${s.range.end} '${s.leftChar}${s.rightChar}' " +
                "inner=${s.naturalInnerGlue.dumpFmt()}->${s.adjustedInnerGlue.dumpFmt()} " +
                "target=${s.reductionTargetRange.start}-${s.reductionTargetRange.end}",
        )
    }
    debug.autoSpaceDecisions.forEach { a ->
        appendLine(
            "autospace ${a.clusterRange.start}-${a.clusterRange.end} side=${a.side} " +
                "boundary=${a.boundaryRole} reduction=${a.totalReduction.dumpFmt()}",
        )
    }
    debug.mandatoryBreakDecisions.forEach { b ->
        appendLine(
            "mandatorybreak ${b.range.start}-${b.range.end} afterCluster=${b.breakAfterClusterIndex} " +
                "reason=${b.reason}",
        )
    }
    debug.zeroWidthBreakDecisions.forEach { b ->
        appendLine(
            "zerowidthbreak ${b.range.start}-${b.range.end} " +
                "source='${b.sourceText.escapeDumpText()}' cluster=${b.clusterIndex} reason=${b.reason}",
        )
    }
    debug.lineEdgeTrimDecisions.forEach { t ->
        appendLine(
            "edgetrim ${t.clusterRange.start}-${t.clusterRange.end} side=${t.side} " +
                "trim=${t.trimAmount.dumpFmt()} reason=${t.reason}",
        )
    }
    debug.decorationDecisions.forEach { d ->
        appendLine(
            "deco ${d.clusterRange.start}-${d.clusterRange.end} '${d.sourceText}' kind=${d.kind} " +
                "applied=${d.applied} anchor=${d.anchorX.dumpFmt()},${d.anchorY.dumpFmt()} " +
                "diameter=${d.dotDiameter.dumpFmt()} reason=${d.reason}",
        )
    }
    debug.lineSpacingDecision?.let { d ->
        appendLine(
            "linespacing natural=${d.naturalHeight.dumpFmt()} requested=${d.requestedLineHeight?.dumpFmt() ?: "-"} " +
                "resolved=${d.resolvedHeight.dumpFmt()} floor=${d.spacingFloor.dumpFmt()} " +
                "applied=${d.floorApplied} reason=${d.reason}",
        )
    }
    debug.rubyLineHeightDecision?.let { d ->
        appendLine(
            "rubylineheight mode=${d.mode} base=${d.baseLineHeight.dumpFmt()} " +
                "face=${d.baseFaceHeight.dumpFmt()} ruby=${d.rubyExtent.dumpFmt()} " +
                "available=${d.availableInterlineSpace.dumpFmt()} maxExtra=${d.maxExtra.dumpFmt()} " +
                "extras=${d.lineExtras.joinToString(",") { it.dumpFmt() }.ifEmpty { "-" }} " +
                "lines=${d.expandedLineIndices.joinToString(",").ifEmpty { "-" }} reason=${d.reason}",
        )
    }
    debug.inlineObjectLineHeightDecision?.let { d ->
        appendLine(
            "inlineobjectlineheight base=${d.baseLineHeight.dumpFmt()} " +
                "face=${d.baseFaceAscent.dumpFmt()}+${d.baseFaceDescent.dumpFmt()} " +
                "available=${d.availableInterlineSpace.dumpFmt()} " +
                "clearance=${d.minimumClearance.dumpFmt()} " +
                "ascents=${d.lineAscents.joinToString(",") { it.dumpFmt() }.ifEmpty { "-" }} " +
                "descents=${d.lineDescents.joinToString(",") { it.dumpFmt() }.ifEmpty { "-" }} " +
                "extras=${d.lineExtras.joinToString(",") { it.dumpFmt() }.ifEmpty { "-" }} " +
                "boundaries=${d.boundaryShiftsAfter.joinToString(",") { it.dumpFmt() }.ifEmpty { "-" }} " +
                "trailing=${d.trailingExtra.dumpFmt()} " +
                "lines=${d.expandedLineIndices.joinToString(",").ifEmpty { "-" }} reason=${d.reason}",
        )
    }
    debug.maxLinesDecision?.let { d ->
        appendLine("maxlines laidOut=${d.laidOutLines} visible=${d.visibleLines} reason=${d.reason}")
    }
    debug.decorationSegments.forEach { seg ->
        appendLine(
            "decobox ${seg.sourceRange.start}-${seg.sourceRange.end} kind=${seg.kind} line=${seg.lineIndex} " +
                "rect=${seg.left.dumpFmt()},${seg.top.dumpFmt()},${seg.right.dumpFmt()},${seg.bottom.dumpFmt()} " +
                "open=${if (seg.openStart) "start" else "-"}/${if (seg.openEnd) "end" else "-"} reason=${seg.reason}",
        )
    }
    debug.rubyDecisions.forEach { r ->
        appendLine(
            "ruby ${r.baseRange.start}-${r.baseRange.end} '${r.text}' line=${r.lineIndex} " +
                "centerX=${r.centerX.dumpFmt()} baselineY=${r.baselineY.dumpFmt()} size=${r.fontSize.dumpFmt()} " +
                "box=${r.ascent.dumpFmt()}/${r.descent.dumpFmt()} width=${r.width.dumpFmt()} " +
                "overhang=${r.overhang.dumpFmt()} locale=${r.locale}",
        )
    }
    debug.bopomofoDecisions.forEach { z ->
        appendLine(
            "bopomofo ${z.baseRange.start}-${z.baseRange.end} '${z.text}' " +
                "line=${z.lineIndex} locale=${z.locale}",
        )
        z.placements.forEach { p ->
            appendLine(
                "  ${p.role} '${p.text}' rect=${p.left.dumpFmt()},${p.top.dumpFmt()},${p.width.dumpFmt()},${p.height.dumpFmt()} " +
                    "draw=${p.drawX.dumpFmt()},${p.baselineY.dumpFmt()} size=${p.fontSize.dumpFmt()}",
            )
        }
    }
}

internal fun layoutDumpDiffMessage(id: String, expected: String, actual: String): String {
    val expectedLines = expected.lines()
    val actualLines = actual.lines()
    val diffs = mutableListOf<String>()
    for (i in 0 until maxOf(expectedLines.size, actualLines.size)) {
        val e = expectedLines.getOrNull(i)
        val a = actualLines.getOrNull(i)
        if (e != a) {
            diffs += "  line ${i + 1}:\n    golden: ${e ?: "<missing>"}\n    actual: ${a ?: "<missing>"}"
            if (diffs.size >= 8) {
                diffs += "  …"
                break
            }
        }
    }
    return "golden mismatch for fixture '$id':\n" + diffs.joinToString("\n")
}

/**
 * Common `%.1f`: one decimal, half-up on the magnitude, sign taken from the
 * float's sign bit so `-0.04f` renders `-0.0` exactly like the JVM formatter.
 */
internal fun Float.dumpFmt(): String {
    if (isNaN()) return "NaN"
    if (isInfinite()) return if (this > 0f) "Infinity" else "-Infinity"
    val negative = toRawBits() < 0
    val scaled = kotlin.math.floor(kotlin.math.abs(toDouble()) * 10.0 + 0.5).toLong()
    return "${if (negative) "-" else ""}${scaled / 10}.${scaled % 10}"
}

internal fun String.escapeDumpText(): String = buildString {
    for (ch in this@escapeDumpText) {
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
