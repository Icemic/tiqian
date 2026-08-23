package org.tiqian.layout

import org.tiqian.clreq.AutoSpaceMode
import org.tiqian.clreq.AutoSpacePolicy
import org.tiqian.clreq.BuiltInClreqProfileResolver
import org.tiqian.clreq.ClreqProfile
import org.tiqian.clreq.ClreqProfileResolver
import org.tiqian.clreq.ClreqPunctuationPolicies
import org.tiqian.clreq.HangingPunctuationStyle
import org.tiqian.clreq.KinsokuLevel
import org.tiqian.clreq.LineAdjustmentStrategy
import org.tiqian.clreq.LineEndPunctuationStyle
import org.tiqian.clreq.NumberSymbolCohesion
import org.tiqian.clreq.PunctuationClass
import org.tiqian.clreq.PunctuationGluePlacement
import org.tiqian.clreq.ClreqPunctuationGlyphSubstitutor
import org.tiqian.core.AutoSpaceDecisionInfo
import org.tiqian.core.BreakOpportunityDecisionInfo
import org.tiqian.core.Cluster
import org.tiqian.core.EmergencyTrackingEligibilityDecisionInfo
import org.tiqian.core.EastAsianSpacingEdges
import org.tiqian.core.EastAsianSpacingValue
import org.tiqian.core.UnicodeEastAsianSpacing
import org.tiqian.core.ClusterGeometryDecisionInfo
import org.tiqian.core.ContextualKinsokuDecisionInfo
import org.tiqian.core.DecorationDecisionInfo
import org.tiqian.core.RubyDecisionInfo
import org.tiqian.clreq.BopomofoParser
import org.tiqian.clreq.BopomofoTone
import org.tiqian.core.RubyKind
import org.tiqian.core.RubyLineHeightDecisionInfo
import org.tiqian.core.RubyLineHeightMode
import org.tiqian.core.RubySpan
import org.tiqian.core.BopomofoDecisionInfo
import org.tiqian.core.BopomofoGlyphPlacement
import org.tiqian.core.BopomofoGlyphRole
import org.tiqian.core.DecorationKind
import org.tiqian.core.DecorationSegmentInfo
import org.tiqian.core.DecorationSpan
import org.tiqian.core.FontDecisionInfo
import org.tiqian.core.LineEdgeTrimDecisionInfo
import org.tiqian.core.Glyph
import org.tiqian.core.GlyphRun
import org.tiqian.core.JustificationAllocationInfo
import org.tiqian.core.JustificationDecisionInfo
import org.tiqian.core.LayoutDebugInfo
import org.tiqian.core.LayoutInput
import org.tiqian.core.LayoutResult
import org.tiqian.core.LineBreakPolicy
import org.tiqian.core.InlineBoxDecisionInfo
import org.tiqian.core.InlineBoxOuterSpacing
import org.tiqian.core.InlineBoxSpan
import org.tiqian.core.InlineAttachment
import org.tiqian.core.InlineObjectBoundaryAdjustment
import org.tiqian.core.InlineObjectDecisionInfo
import org.tiqian.core.InlineObjectLineHeightDecisionInfo
import org.tiqian.core.InlineObjectPunctuationAttachmentDecisionInfo
import org.tiqian.core.InlineObjectPreferredStretch
import org.tiqian.core.InlineObjectSpan
import org.tiqian.core.LineBox
import org.tiqian.core.LineDebugInfo
import org.tiqian.core.LineDecisionInfo
import org.tiqian.core.LineEndReason
import org.tiqian.core.LineRepairAllocationInfo
import org.tiqian.core.LineRepairCandidateInfo
import org.tiqian.core.LineRepairDecisionInfo
import org.tiqian.core.MandatoryBreakDecisionInfo
import org.tiqian.core.ZeroWidthBreakDecisionInfo
import org.tiqian.core.MaxLinesDecisionInfo
import org.tiqian.core.MetricDecisionInfo
import org.tiqian.core.PunctuationDecisionInfo
import org.tiqian.core.Rect
import org.tiqian.core.RoleOverrideInfo
import org.tiqian.core.Size
import org.tiqian.core.SpacingDecisionInfo
import org.tiqian.core.ShapingDecisionInfo
import org.tiqian.core.LastLineAlignment
import org.tiqian.core.KinsokuDecisionInfo
import org.tiqian.core.LineLengthGridDecisionInfo
import org.tiqian.core.FirstLineIndentDecisionInfo
import kotlin.math.floor
import kotlin.text.CharCategory
import org.tiqian.core.LineSpacingDecisionInfo
import org.tiqian.core.TextRange
import org.tiqian.core.TextStyle
import org.tiqian.core.sourceGraphemeBoundaries
import org.tiqian.font.CjkFontRoleClassifier
import org.tiqian.font.FallbackResolver
import org.tiqian.font.FontMetricsNormalizationInput
import org.tiqian.font.FontMetricsNormalizer
import org.tiqian.font.FontMetricsRequest
import org.tiqian.font.FontMetricsResolver
import org.tiqian.font.FontDecision
import org.tiqian.font.FontRequest
import org.tiqian.font.FontRole
import org.tiqian.font.BaselineClass
import org.tiqian.font.MetricBox
import org.tiqian.font.FontRoleClassifier
import org.tiqian.font.FontRoleContext
import org.tiqian.font.LayoutFontMetrics
import org.tiqian.font.PreferCjkForAmbiguousPunctuationResolver
import org.tiqian.font.RawFontMetrics
import org.tiqian.font.ScriptAwareFontMetricsNormalizer
import org.tiqian.font.StubFontMetricsResolver
import org.tiqian.linebreak.Hyphenator
import org.tiqian.linebreak.isMandatoryBreakCodePoint
import org.tiqian.linebreak.isZeroWidthSpaceCodePoint
import org.tiqian.linebreak.NoHyphenator
import org.tiqian.shaping.ExplainableStubTextShaper
import org.tiqian.shaping.ShapingInput
import org.tiqian.shaping.ShapingResult
import org.tiqian.shaping.TextShaper
import org.tiqian.shaping.UNVERIFIED_DISPLAY_SUBSTITUTION_COVERAGE_ISSUE

internal data class LineBoxStageResult(
    val laidOutLines: List<LineBox>,
    val visibleLines: List<LineBox>,
    val maxLinesDecision: MaxLinesDecisionInfo?,
    val visibleLineRanges: List<IntRange>,
)

/** Converts committed break candidates and vertical geometry into visible line boxes. */
internal fun buildLineBoxes(
    input: LayoutInput,
    lineSolution: LineSolution,
    trimmedClusters: List<Cluster>,
    finalClusters: List<Cluster>,
    firstLineIndent: Float,
    blockIndent: Float,
    measure: Float,
    gridBodyOffset: Float,
    lineBaseline: FloatArray,
    lineTop: FloatArray,
    lineBottom: FloatArray,
    lineHyphenAdvanceAt: (Int) -> Float,
    hyphenGlyphs: List<Glyph>,
    justificationPlans: List<JustificationPlan?>,
): LineBoxStageResult {
    val laidOutLines = lineSolution.lines.mapIndexed { lineIndex, lineCandidate ->
        // LineEndHangingPunctuation: the hung mark is excluded from the
        // measure-fill width (adjustedWidth) but kept in visualWidth —
        // it overflows the measure (突出版心).
        val adjustedWidth = lineCandidate.clusterRange
            .sumOf { idx ->
                if (idx in lineCandidate.hangingClusterIndices) 0.0 else trimmedClusters[idx].advance.toDouble()
            }
            .toFloat()
        val visualWidth = lineCandidate.clusterRange
            .sumOf { finalClusters[it].advance.toDouble() }
            .toFloat()
        val hangingPunctuationAdvance = lineCandidate.hangingClusterIndices
            .sumOf { finalClusters[it].advance.toDouble() }
            .toFloat()
        val hasDrawableContent = !lineCandidate.clusterRange.isEmptyClusterRange() &&
            lineCandidate.clusterRange.any { finalClusters[it].displayText.isNotEmpty() }
        val baseIndent = when {
            !hasDrawableContent -> 0f
            lineCandidate.clusterRange.first == 0 -> firstLineIndent
            else -> blockIndent
        }
        // LastLineAlignment: every line that ends its visual paragraph —
        // the true last line AND every MandatoryBreak-ended line (it is the
        // last line of ITS 段, ADR 0037) — is an alignment degree of freedom
        // (CLREQ 双齐 baseline). AutoWrap lines are justified instead.
        // Center/End express as an extra start-edge inset within the line's
        // usable measure — renderers and decoration geometry consume
        // LineBox.indent unchanged.
        // LineEndHangingHyphen: this line ends mid-word when the NEXT line
        // begins at a hyphenation source offset (reserved in the justify
        // measure above; renderers draw it at indent + visualWidth).
        val lineHyphenAdvance = lineHyphenAdvanceAt(lineIndex)
        val limit = measure - baseIndent
        val alignmentInset = if (lineCandidate.endReason == LineEndReason.AutoWrap) {
            0f
        } else {
            when (input.paragraphStyle.lastLineAlignment) {
                LastLineAlignment.Start -> 0f
                LastLineAlignment.Center -> ((limit - visualWidth) / 2f).coerceAtLeast(0f)
                LastLineAlignment.End -> (limit - visualWidth).coerceAtLeast(0f)
            }
        }
        LineBox(
            range = lineCandidate.sourceRange,
            clusterRange = lineCandidate.clusterRange,
            baseline = lineBaseline[lineIndex],
            top = lineTop[lineIndex],
            bottom = lineBottom[lineIndex],
            naturalWidth = lineCandidate.naturalWidth,
            adjustedWidth = adjustedWidth,
            visualWidth = visualWidth,
            hangingPunctuationAdvance = hangingPunctuationAdvance,
            // GridBodyAlignment: the whole body shifts by the container
            // slack offset; per-line indent (段首缩进 + 末行对齐) stacks on top.
            indent = gridBodyOffset + baseIndent + alignmentInset,
            endReason = lineCandidate.endReason,
            hyphenAdvance = lineHyphenAdvance,
            hyphenGlyphs = if (lineHyphenAdvance > 0f) hyphenGlyphs else emptyList(),
            debug = LineDebugInfo(
                repair = lineCandidate.repair?.let { "${it::class.simpleName}:${it.reason}" },
                notes = listOf(
                    if (lineCandidate.clusterRange.isEmptyClusterRange()) {
                        "line:${lineIndex}:clusters=empty"
                    } else {
                        "line:${lineIndex}:clusters=${lineCandidate.clusterRange.first}-${lineCandidate.clusterRange.last}"
                    },
                    "end:${lineCandidate.endReason}",
                    "natural=${lineCandidate.naturalWidth},adjusted=${lineCandidate.adjustedWidth},visual=$visualWidth",
                ) + listOfNotNull(
                    justificationPlans.getOrNull(lineIndex)?.fallbackReason
                        ?.let { "justify-fallback:$it" },
                ),
            ),
        )
    }
    // MaxLinesLineTruncation: layout ran on the FULL text above (a truncated
    // middle line keeps its justification); only the emitted line boxes are
    // capped. `lineDecisions` below still records every laid-out line so the
    // dump stays complete. Ruby/注音/decoration geometry below is computed
    // over the VISIBLE lines only — renderers consume those lists directly.
    val lines = if (laidOutLines.size > input.constraints.maxLines) {
        laidOutLines.take(input.constraints.maxLines)
    } else {
        laidOutLines
    }
    val maxLinesDecision = if (lines.size < laidOutLines.size) {
        MaxLinesDecisionInfo(laidOutLines = laidOutLines.size, visibleLines = lines.size)
    } else {
        null
    }
    val visibleLineRanges = lineSolution.lines.take(lines.size).map { it.clusterRange }
    return LineBoxStageResult(
        laidOutLines = laidOutLines,
        visibleLines = lines,
        maxLinesDecision = maxLinesDecision,
        visibleLineRanges = visibleLineRanges,
    )
}

internal data class LineVerticalGeometryStageResult(
    val rubyLineHeightDecision: RubyLineHeightDecisionInfo?,
    val inlineObjectLineHeightDecision: InlineObjectLineHeightDecisionInfo?,
    val lineBaseline: FloatArray,
    val lineTop: FloatArray,
    val lineBottom: FloatArray,
)

/** Resolves annotation and opaque-object vertical demand into final line boxes. */
internal fun resolveLineVerticalGeometry(
    input: LayoutInput,
    fontSize: Float,
    pinyinSpans: List<RubySpan>,
    naturalClusters: List<Cluster>,
    lineSolution: LineSolution,
    rubyFontGeometryBySpan: Map<RubySpan, RubyFontGeometry>,
    existingInterlineSpace: Float,
    baseLineMetrics: ResolvedLineMetrics,
    baseFaceHeight: Float,
    rubyExtent: Float,
    inlineObjectByClusterIndex: Map<Int, InlineObjectSpan>,
    baseAscent: Float,
    baseDescent: Float,
): LineVerticalGeometryStageResult {
    // Per-line annotation/object extents. Ruby consumes existing inter-line
    // space first; a deficit is added before annotated lines by default, or
    // before every line in UniformParagraph mode. Opaque inline objects own
    // layout geometry and may extend above or below the base box.
    val pinyinClusterRanges = pinyinSpans.mapNotNull { ruby ->
        naturalClusters.clusterIndexRangeFor(ruby.baseRange)?.let { ruby to it }
    }
    val perLineRubyExtent = lineSolution.lines.map { line ->
        val requiredExtent = pinyinClusterRanges.mapNotNull { (ruby, range) ->
            if (range.first <= line.clusterRange.last && range.last >= line.clusterRange.first) {
                rubyFontGeometryBySpan[ruby]?.requiredExtent
            } else {
                null
            }
        }.maxOrNull() ?: 0f
        requiredExtent
    }
    val perLineRubyDeficit = perLineRubyExtent.map {
        (it - existingInterlineSpace).coerceAtLeast(0f)
    }
    val paragraphRubyDeficit = perLineRubyDeficit.maxOrNull() ?: 0f
    val lineRubyTopExtra = when (input.paragraphStyle.rubyLineHeightMode) {
        RubyLineHeightMode.PerLine -> perLineRubyDeficit
        RubyLineHeightMode.UniformParagraph -> List(lineSolution.lines.size) { paragraphRubyDeficit }
    }
    val lineRubyInterlineDemand = when (input.paragraphStyle.rubyLineHeightMode) {
        RubyLineHeightMode.PerLine -> perLineRubyExtent
        RubyLineHeightMode.UniformParagraph ->
            List(lineSolution.lines.size) { perLineRubyExtent.maxOrNull() ?: 0f }
    }
    val rubyLineHeightDecision = if (pinyinSpans.isEmpty()) {
        null
    } else {
        RubyLineHeightDecisionInfo(
            mode = input.paragraphStyle.rubyLineHeightMode.name,
            baseLineHeight = baseLineMetrics.height,
            baseFaceHeight = baseFaceHeight,
            rubyExtent = rubyExtent,
            availableInterlineSpace = existingInterlineSpace,
            maxExtra = lineRubyTopExtra.maxOrNull() ?: 0f,
            lineExtras = lineRubyTopExtra,
            expandedLineIndices = lineRubyTopExtra.indices.filter { lineRubyTopExtra[it] > 0f },
            reason = if (lineRubyTopExtra.any { it > 0f }) {
                "ConditionalRubyLineHeight"
            } else {
                "ExistingInterlineSpaceFitsRuby"
            },
        )
    }
    val baseTopExtent = baseLineMetrics.baseline
    val baseBottomExtent = baseLineMetrics.height - baseLineMetrics.baseline
    val lineObjectAscent = lineSolution.lines.map { line ->
        line.clusterRange.mapNotNull(inlineObjectByClusterIndex::get)
            .maxOfOrNull { it.ascent } ?: 0f
    }
    val lineObjectDescent = lineSolution.lines.map { line ->
        line.clusterRange.mapNotNull(inlineObjectByClusterIndex::get)
            .maxOfOrNull { it.descent } ?: 0f
    }
    val lineObjectTopIntrusion = lineObjectAscent.map {
        (it - baseAscent).coerceAtLeast(0f)
    }
    val lineObjectBottomIntrusion = lineObjectDescent.map {
        (it - baseDescent).coerceAtLeast(0f)
    }
    val inlineObjectMinimumClearance =
        input.paragraphStyle.inlineObjectMinimumClearanceEm * fontSize

    // InlineObjectInterlineCollision: ascent/descent describe visible object
    // geometry, not an instruction to reserve each half of the current line box.
    // An object may consume the existing gap between adjacent base faces while
    // retaining the paragraph's minimum visible-content clearance. Only a
    // clearance deficit changes baseline distance; otherwise the boundary between
    // the two line boxes moves inside that already available space.
    val combinedLineExtra = lineSolution.lines.indices.map { index ->
        if (index == 0) {
            maxOf(
                lineRubyTopExtra[index],
                (lineObjectAscent[index] - baseTopExtent).coerceAtLeast(0f),
            )
        } else {
            val topDemand = maxOf(
                lineRubyInterlineDemand[index],
                lineObjectTopIntrusion[index],
            )
            val objectIntrudesBoundary =
                lineObjectBottomIntrusion[index - 1] > 0f ||
                    (
                        lineObjectTopIntrusion[index] > 0f &&
                            lineObjectTopIntrusion[index] >= lineRubyInterlineDemand[index]
                    )
            val minimumClearance = if (objectIntrudesBoundary) inlineObjectMinimumClearance else 0f
            (
                lineObjectBottomIntrusion[index - 1] + topDemand + minimumClearance -
                    existingInterlineSpace
                ).coerceAtLeast(0f)
        }
    }
    val objectLineExtra = combinedLineExtra.indices.map { index ->
        (combinedLineExtra[index] - lineRubyTopExtra[index]).coerceAtLeast(0f)
    }
    val lineBaseline = FloatArray(lineSolution.lines.size)
    if (lineBaseline.isNotEmpty()) {
        lineBaseline[0] = baseLineMetrics.baseline + combinedLineExtra[0]
        for (index in 1 until lineBaseline.size) {
            lineBaseline[index] = lineBaseline[index - 1] +
                baseLineMetrics.height + combinedLineExtra[index]
        }
    }

    val lineTop = FloatArray(lineSolution.lines.size)
    val lineBottom = FloatArray(lineSolution.lines.size)
    val boundaryShiftsAfter = MutableList((lineSolution.lines.size - 1).coerceAtLeast(0)) { 0f }
    for (index in 0 until lineSolution.lines.lastIndex) {
        val currentContentBottomExtent = maxOf(baseDescent, lineObjectDescent[index])
        val boundaryExtent = resolveInlineObjectLineBoundaryExtent(
            nominalBoundaryExtent = baseBottomExtent,
            currentContentBottomExtent = currentContentBottomExtent,
            baselineDistance = lineBaseline[index + 1] - lineBaseline[index],
            nextContentTopExtent = maxOf(baseAscent, lineObjectAscent[index + 1]),
        )
        val nominalBoundary = lineBaseline[index] + baseBottomExtent
        val boundary = lineBaseline[index] + boundaryExtent
        lineBottom[index] = boundary
        lineTop[index + 1] = boundary
        boundaryShiftsAfter[index] = boundary - nominalBoundary
    }
    val trailingObjectExtra = if (lineSolution.lines.isEmpty()) {
        0f
    } else {
        (lineObjectDescent.last() - baseBottomExtent).coerceAtLeast(0f)
    }
    if (lineSolution.lines.isNotEmpty()) {
        lineBottom[lineSolution.lines.lastIndex] = lineBaseline.last() +
            baseBottomExtent + trailingObjectExtra
    }
    val inlineObjectLineHeightDecision = if (inlineObjectByClusterIndex.isEmpty()) {
        null
    } else {
        val expandedLineIndices = objectLineExtra.indices.filter { objectLineExtra[it] > 0f }
        InlineObjectLineHeightDecisionInfo(
            baseLineHeight = baseLineMetrics.height,
            baseFaceAscent = baseAscent,
            baseFaceDescent = baseDescent,
            availableInterlineSpace = existingInterlineSpace,
            minimumClearance = inlineObjectMinimumClearance,
            lineAscents = lineObjectAscent,
            lineDescents = lineObjectDescent,
            lineExtras = objectLineExtra,
            boundaryShiftsAfter = boundaryShiftsAfter,
            trailingExtra = trailingObjectExtra,
            expandedLineIndices = expandedLineIndices,
            reason = if (expandedLineIndices.isEmpty() && trailingObjectExtra == 0f) {
                "ExistingInterlineSpaceFitsInlineObjects"
            } else {
                "InlineObjectInterlineCollision"
            },
        )
    }
    return LineVerticalGeometryStageResult(
        rubyLineHeightDecision = rubyLineHeightDecision,
        inlineObjectLineHeightDecision = inlineObjectLineHeightDecision,
        lineBaseline = lineBaseline,
        lineTop = lineTop,
        lineBottom = lineBottom,
    )
}

internal fun List<ClusterMetricDecision>.lineMetrics(
    explicitLineHeight: Float?,
    defaultLineHeight: Float,
    spacingFloor: Float = 0f,
): ResolvedLineMetrics {
    if (isEmpty()) {
        // EmptyParagraphBaselineFallback: a paragraph with no shapeable
        // content (pure mandatory breaks, e.g. "\n\n") still needs a caret
        // baseline but has no font metrics to consult. 0.75 × line height
        // is where the CJK baseline would sit on the default line: half
        // leading 0.25em + 字身框 ascent 0.88em over a 1.5em line ≈ 0.753.
        val height = explicitLineHeight ?: defaultLineHeight
        return ResolvedLineMetrics(
            baseline = height * EMPTY_PARAGRAPH_BASELINE_RATIO,
            height = height,
        )
    }

    // The CJK line box follows the 字身框 (IdeographicEmBox). Latin/other runs use
    // the taller RawFontBox (full hhea incl. line gap); they sit WITHIN the CJK box +
    // its leading and must NOT inflate the line — otherwise one inline Latin word (or
    // ㄅㄆㄇ in a different metric) stretches EVERY line in the paragraph. Pure-Latin
    // paragraphs (no 字身框 cluster) fall back to all clusters so they still fit.
    val heightSource = filter { it.layoutMetrics.metricBox == MetricBox.IdeographicEmBox }.ifEmpty { this }
    val ascent = heightSource.maxOf { it.layoutMetrics.ascent }
    val descent = heightSource.maxOf { it.layoutMetrics.descent }
    val naturalHeight = ascent + descent
    // Height = the explicit value, else the CjkBodyLineHeightDefault, but
    // never below naturalHeight + InterlinearMarkLineSpacingFloor — that
    // minimum keeps glyph ink and 行间标点 from overlapping the next line
    // (CLREQ「不应小于」). So an explicit lineHeight overrides the body
    // default downward, but is still clamped up to the no-overlap minimum.
    val minHeight = naturalHeight + spacingFloor
    val height = (explicitLineHeight ?: defaultLineHeight).coerceAtLeast(minHeight)
    val extraLeading = (height - naturalHeight).coerceAtLeast(0f)

    return ResolvedLineMetrics(
        baseline = extraLeading / 2f + ascent,
        height = height,
        extraLeading = extraLeading,
    )
}

internal fun List<Cluster>.renderableGlyphRunClusters(
    openTypeFeaturesByClusterRange: Map<TextRange, List<String>>,
): List<List<Cluster>> =
    filter { it.displayText.isNotEmpty() && !it.isInlineObjectCluster() }.groupAdjacentBy { previous, current ->
        previous.fontKey == current.fontKey &&
            previous.range.end == current.range.start &&
            openTypeFeaturesByClusterRange[previous.range].orEmpty() ==
            openTypeFeaturesByClusterRange[current.range].orEmpty()
    }

private inline fun <T> List<T>.groupAdjacentBy(sameGroup: (previous: T, current: T) -> Boolean): List<List<T>> {
    if (isEmpty()) return emptyList()

    val groups = mutableListOf<MutableList<T>>()
    var currentGroup = mutableListOf(first())

    for (item in drop(1)) {
        if (sameGroup(currentGroup.last(), item)) {
            currentGroup.add(item)
        } else {
            groups.add(currentGroup)
            currentGroup = mutableListOf(item)
        }
    }

    groups.add(currentGroup)
    return groups
}

internal data class ClusterMetricDecision(
    val range: TextRange,
    val sourceText: String,
    val request: FontMetricsRequest,
    val rawMetrics: RawFontMetrics,
    val layoutMetrics: LayoutFontMetrics,
)

internal data class ResolvedLineMetrics(
    val baseline: Float,
    val height: Float,
    val extraLeading: Float = 0f,
)

/** `EmptyParagraphBaselineFallback`: see [lineMetrics]'s empty branch. */
private const val EMPTY_PARAGRAPH_BASELINE_RATIO = 0.75f

/**
 * InlineObjectBoundaryFloatClosure: resolve a shared line-box boundary in local line coordinates.
 *
 * The collision calculation guarantees that [currentContentBottomExtent] does not exceed the next
 * line's content-top offset. Independent Float additions/subtractions can nevertheless invert two
 * mathematically equal bounds by one ULP (for example 84.14 versus 84.13999). Closing that numerical
 * sliver onto the current content edge preserves the already-resolved baseline grid and keeps the
 * interval valid without inventing visible line spacing.
 */
internal fun resolveInlineObjectLineBoundaryExtent(
    nominalBoundaryExtent: Float,
    currentContentBottomExtent: Float,
    baselineDistance: Float,
    nextContentTopExtent: Float,
): Float {
    val nextContentTopOffset = baselineDistance - nextContentTopExtent
    val closedUpperBound = maxOf(currentContentBottomExtent, nextContentTopOffset)
    return nominalBoundaryExtent.coerceIn(currentContentBottomExtent, closedUpperBound)
}
