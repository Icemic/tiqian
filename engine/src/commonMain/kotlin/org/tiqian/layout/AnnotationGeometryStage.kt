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

internal data class AnnotationGeometryStageResult(
    val inlineObjectDecisions: List<InlineObjectDecisionInfo>,
    val decorationDecisions: List<DecorationDecisionInfo>,
    val decorationSegments: List<DecorationSegmentInfo>,
    val rubyDecisions: List<RubyDecisionInfo>,
    val bopomofoDecisions: List<BopomofoDecisionInfo>,
)

/** Resolves decorations and annotation geometry against the final visible lines. */
internal fun TiqianParagraphLayoutEngine.resolveAnnotationGeometry(
    input: LayoutInput,
    fontSize: Float,
    inlineObjectByClusterIndex: Map<Int, InlineObjectSpan>,
    lineSolution: LineSolution,
    clreqProfile: ClreqProfile,
    geometryDecisions: List<ClusterGeometryDecisionInfo>,
    autoSpaceDecisions: List<AutoSpaceDecisionInfo>,
    visibleLineRanges: List<IntRange>,
    lines: List<LineBox>,
    finalClusters: List<Cluster>,
    clusterRoles: List<FontRole>,
    justifyDeltaByCluster: Map<Int, Float>,
    rubyAndBopomofoSpread: Map<Int, Float>,
    metricDecisions: List<ClusterMetricDecision>,
    pinyinSpans: List<RubySpan>,
    naturalClusters: List<Cluster>,
    rubyFontGeometryBySpan: Map<RubySpan, RubyFontGeometry>,
    rubyStackGap: Float,
    baseAscent: Float,
    rubyFontSize: Float,
    rubyFontWeight: Int,
    baseDescent: Float,
    bopomofoFontWeightAt: (Int) -> Int,
): AnnotationGeometryStageResult {
    val inlineObjectDecisions = inlineObjectByClusterIndex.entries
        .sortedBy { it.key }
        .map { (clusterIndex, inlineObject) ->
            InlineObjectDecisionInfo(
                range = inlineObject.range,
                advance = inlineObject.advance,
                ascent = inlineObject.ascent,
                descent = inlineObject.descent,
                clusterIndex = clusterIndex,
                lineIndex = lineSolution.lines.indexOfFirst { clusterIndex in it.clusterRange },
                leadingUniformStretch =
                    inlineObject.leadingBoundary.participatesInUniformStretch,
                leadingPreferredStretchKind =
                    inlineObject.leadingBoundary.preferredStretch?.kind?.name,
                leadingPreferredStretchNaturalWidth =
                    inlineObject.leadingBoundary.preferredStretch?.naturalWidth ?: 0f,
                leadingPreferredStretchTargetWidth =
                    inlineObject.leadingBoundary.preferredStretch?.targetWidth ?: 0f,
                leadingPreferredStretchCapacity =
                    inlineObject.leadingBoundary.preferredStretch?.capacity ?: 0f,
                leadingPreventsLineBreak = inlineObject.leadingBoundary.preventsLineBreak,
                leadingShrinkCapacity = inlineObject.leadingBoundary.shrinkCapacity,
                leadingLineEndDiscardableAdvance =
                    inlineObject.leadingBoundary.lineEndDiscardableAdvance,
                trailingUniformStretch =
                    inlineObject.trailingBoundary.participatesInUniformStretch,
                trailingPreferredStretchKind =
                    inlineObject.trailingBoundary.preferredStretch?.kind?.name,
                trailingPreferredStretchNaturalWidth =
                    inlineObject.trailingBoundary.preferredStretch?.naturalWidth ?: 0f,
                trailingPreferredStretchTargetWidth =
                    inlineObject.trailingBoundary.preferredStretch?.targetWidth ?: 0f,
                trailingPreferredStretchCapacity =
                    inlineObject.trailingBoundary.preferredStretch?.capacity ?: 0f,
                trailingPreventsLineBreak = inlineObject.trailingBoundary.preventsLineBreak,
                trailingShrinkCapacity = inlineObject.trailingBoundary.shrinkCapacity,
                trailingLineEndDiscardableAdvance =
                    inlineObject.trailingBoundary.lineEndDiscardableAdvance,
                reason = if (
                    inlineObject.leadingBoundary != org.tiqian.core.InlineObjectBoundaryAdjustment.Fixed ||
                    inlineObject.trailingBoundary != org.tiqian.core.InlineObjectBoundaryAdjustment.Fixed
                ) {
                    "AdjustableInlineObject"
                } else {
                    "MeasurableOpaqueInlineObject"
                },
            )
        }
    // Ink-edge insets so 行间线/着重号 hug the text, not the edge blanks: the leading
    // autospace gap + consumed 开标点 leading glue (mirrors the renderer's glyph
    // shift, SkiaTextBlobs.forEachPositionedCluster). The trailing justify stretch
    // is already excluded at use; the LEADING side was being missed (CLREQ「两侧」).
    val autoSpaceGapPx = clreqProfile.autoSpace.gapEm * fontSize
    val geometryByRange = geometryDecisions.associateBy { it.range }
    val leadingGapRanges = autoSpaceDecisions.filter { it.side == "leading" }.map { it.clusterRange }.toSet()
    val trailingGapRanges = autoSpaceDecisions.filter { it.side == "trailing" }.map { it.clusterRange }.toSet()
    val decorationDecisions = computeDecorationDecisions(
        decorations = input.decorations,
        lineRanges = visibleLineRanges,
        lineBoxes = lines,
        finalClusters = finalClusters,
        clusterRoles = clusterRoles,
        justifyDeltaByCluster = justifyDeltaByCluster,
        rubySpreadByCluster = rubyAndBopomofoSpread,
        metricDecisions = metricDecisions,
        fontSize = fontSize,
        emphasisDotGapEm = input.paragraphStyle.emphasisDotGapEm,
    )
    val decorationSegments = computeDecorationSegments(
        decorations = input.decorations,
        lineRanges = visibleLineRanges,
        lineBoxes = lines,
        finalClusters = finalClusters,
        justifyDeltaByCluster = justifyDeltaByCluster,
        geometryByRange = geometryByRange,
        leadingGapRanges = leadingGapRanges,
        trailingGapRanges = trailingGapRanges,
        autoSpaceGapPx = autoSpaceGapPx,
        fontSize = fontSize,
    )
    val rubyDecisions = computeRubyDecisions(
        rubySpans = pinyinSpans,
        lineRanges = visibleLineRanges,
        lineBoxes = lines,
        finalClusters = finalClusters,
        naturalClusters = naturalClusters,
        metricDecisions = metricDecisions,
        rubyFontGeometryBySpan = rubyFontGeometryBySpan,
        rubyStackGap = rubyStackGap,
        fallbackBaseAscent = baseAscent,
        rubyFontSize = rubyFontSize,
        rubyFontWeight = rubyFontWeight,
        baseLocale = input.textStyle.locale,
    )
    val bopomofoDecisions = computeBopomofoDecisions(
        rubySpans = input.rubySpans.filter { it.kind == RubyKind.Bopomofo },
        lineRanges = visibleLineRanges,
        lineBoxes = lines,
        finalClusters = finalClusters,
        naturalClusters = naturalClusters,
        baseAscent = baseAscent,
        baseDescent = baseDescent,
        fontSize = fontSize,
        bopomofoFontWeightAt = bopomofoFontWeightAt,
        baseTextStyle = input.textStyle,
    )
    return AnnotationGeometryStageResult(
        inlineObjectDecisions = inlineObjectDecisions,
        decorationDecisions = decorationDecisions,
        decorationSegments = decorationSegments,
        rubyDecisions = rubyDecisions,
        bopomofoDecisions = bopomofoDecisions,
    )
}

/**
 * Named heuristic: `EmphasisDotOnHanText` (ADR 0018, CLREQ 着重号).
 *
 * Resolves decoration spans into per-cluster dot anchors AFTER all
 * geometry is final — decorations never feed back into metrics, breaks
 * or justification. Per CLREQ, only Han text carries a dot: punctuation
 * inside the span is skipped (`clreq-no-dot-on-punctuation`), and
 * non-Han clusters are skipped (`no-dot-on-non-han`; western emphasis is
 * italics instead — `BilingualEmphasisWesternItalic`, applied at shaping).
 *
 * Anchor = the point the dot INK CENTRE must land on: x is the glyph
 * centre (final position minus the trailing justification delta); y starts
 * at the annotated cluster's real ideographic-face bottom, then adds
 * `ParagraphStyle.emphasisDotGapEm·clusterEm + dotRadius`. This
 * `ExplicitEmphasisDotGap` is independent of line height and stays correct
 * for mixed font sizes and explicit baseline shifts. [dotDiameter] is final
 * paint geometry: renderers draw it exactly and apply no hidden scaling.
 */
private fun computeDecorationDecisions(
    decorations: List<DecorationSpan>,
    lineRanges: List<IntRange>,
    lineBoxes: List<LineBox>,
    finalClusters: List<Cluster>,
    clusterRoles: List<FontRole>,
    justifyDeltaByCluster: Map<Int, Float>,
    rubySpreadByCluster: Map<Int, Float>,
    metricDecisions: List<ClusterMetricDecision>,
    fontSize: Float,
    emphasisDotGapEm: Float,
): List<DecorationDecisionInfo> {
    if (decorations.isEmpty()) return emptyList()

    val decisions = mutableListOf<DecorationDecisionInfo>()
    for (span in decorations) {
        if (span.kind != DecorationKind.Emphasis) continue
        lineRanges.forEachIndexed { lineIndex, clusterRange ->
            var x = lineBoxes[lineIndex].indent
            for (idx in clusterRange) {
                val cluster = finalClusters[idx]
                val coveredBySpan = cluster.range.start >= span.range.start &&
                    cluster.range.end <= span.range.end
                if (coveredBySpan) {
                    val role = clusterRoles[idx]
                    val applied = role == FontRole.CjkText
                    // Centre on the base BODY: drop the trailing justify stretch AND
                    // the 注音 column reservation (着重号 belongs under 基文, not 基文+注音).
                    val glyphAdvance = cluster.advance -
                        (justifyDeltaByCluster[idx] ?: 0f) - (rubySpreadByCluster[idx] ?: 0f)
                    val metric = metricDecisions.firstOrNull {
                        cluster.range.start >= it.range.start && cluster.range.end <= it.range.end
                    }
                    val clusterEm = metric?.request?.fontSize ?: fontSize
                    val faceDescent = metric?.layoutMetrics?.descent
                        ?: clusterEm * CJK_FACE_DESCENT_FALLBACK_EM
                    val candidateDotDiameter = clusterEm * EMPHASIS_DOT_DIAMETER_EM
                    val dotDiameter = if (applied) candidateDotDiameter else 0f
                    decisions += DecorationDecisionInfo(
                        clusterRange = cluster.range,
                        sourceText = cluster.text,
                        kind = span.kind.name,
                        applied = applied,
                        reason = when {
                            applied -> "EmphasisDotOnHanText"
                            role == FontRole.CjkPunctuation -> "clreq-no-dot-on-punctuation"
                            else -> "no-dot-on-non-han"
                        },
                        anchorX = x + glyphAdvance / 2f,
                        anchorY = lineBoxes[lineIndex].baseline + cluster.baselineShift +
                            faceDescent + clusterEm * emphasisDotGapEm + candidateDotDiameter / 2f,
                        dotDiameter = dotDiameter,
                    )
                }
                x += cluster.advance
            }
        }
    }
    return decisions
}

/**
 * 示亡号 frame geometry (ADR 0018). One rectangle per line the span
 * touches. Vertical bounds are the conventional CJK CHARACTER FACE
 * (字面): `baseline - 0.88em .. baseline + 0.12em`, hugging the face
 * with NO margin. Neither layout em box (artificial 0.5/0.5 split that
 * real ink overflows), nor raw line metrics (include inter-line air),
 * nor per-glyph ink (varies with glyph shape — `一` would collapse the
 * frame and break uniformity across a name list) describe the face;
 * the 0.88/0.12 split encodes the standard CJK design box. Replacing
 * it with font-reported ideographic metrics (BASE table) is follow-up.
 * `openStart`/`openEnd` mark continuation edges when the span had to
 * split across lines (only when wider than the measure —
 * `MourningSpanKeptUnbroken` otherwise prevents the split at break
 * time).
 */
private fun computeDecorationSegments(
    decorations: List<DecorationSpan>,
    lineRanges: List<IntRange>,
    lineBoxes: List<LineBox>,
    finalClusters: List<Cluster>,
    justifyDeltaByCluster: Map<Int, Float>,
    geometryByRange: Map<TextRange, ClusterGeometryDecisionInfo>,
    leadingGapRanges: Set<TextRange>,
    trailingGapRanges: Set<TextRange>,
    autoSpaceGapPx: Float,
    fontSize: Float,
): List<DecorationSegmentInfo> {
    // Remaining edge blank to strip off a covered cluster so 行间线 hugs the ink/body
    // (CLREQ 避两侧空白): the autospace gap + the punctuation glue still present
    // (开/闭标点 half-width), mirroring how the renderer positions the glyph.
    fun leadingBlank(range: TextRange, atLineStart: Boolean): Float {
        val g = geometryByRange[range]
        val glue = if (g != null) g.leadingGlueNatural - g.leadingGlueConsumed else 0f
        val auto = if (range in leadingGapRanges && !atLineStart) autoSpaceGapPx else 0f
        return glue + auto
    }
    fun trailingBlank(range: TextRange, atLineEnd: Boolean): Float {
        val g = geometryByRange[range]
        val glue = if (g != null) g.trailingGlueNatural - g.trailingGlueConsumed else 0f
        val auto = if (range in trailingGapRanges && !atLineEnd) autoSpaceGapPx else 0f
        return glue + auto
    }
    val boxSpans = decorations.filter {
        it.kind == DecorationKind.Mourning ||
            it.kind == DecorationKind.ProperNoun ||
            it.kind == DecorationKind.BookTitle
    }
    if (boxSpans.isEmpty()) return emptyList()

    val segments = mutableListOf<DecorationSegmentInfo>()
    for (span in boxSpans) {
        val spanSegments = mutableListOf<DecorationSegmentInfo>()
        lineRanges.forEachIndexed { lineIndex, clusterRange ->
            var x = lineBoxes[lineIndex].indent
            var left: Float? = null
            var right = 0f
            var segStart = -1
            var segEnd = -1
            for (idx in clusterRange) {
                val cluster = finalClusters[idx]
                val covered = cluster.range.start >= span.range.start &&
                    cluster.range.end <= span.range.end
                if (covered) {
                    if (left == null) {
                        // Start at the first covered cluster's ink/body left: skip the
                        // leading blank (autospace + 开标点 glue), CLREQ 避两侧空白.
                        left = x + leadingBlank(cluster.range, idx == clusterRange.first)
                        segStart = cluster.range.start
                    }
                    // End at the last covered cluster's ink/body right: drop the
                    // trailing justify stretch AND the trailing blank (autospace +
                    // 闭标点 glue) — 长度与文字外框一致, both sides.
                    right = x + cluster.advance - (justifyDeltaByCluster[idx] ?: 0f) -
                        trailingBlank(cluster.range, idx == clusterRange.last)
                    segEnd = cluster.range.end
                }
                x += cluster.advance
            }
            val leftEdge = left ?: return@forEachIndexed
            val baseline = lineBoxes[lineIndex].baseline
            val isLine = span.kind != DecorationKind.Mourning
            // 行间线贴字：face bottom (+0.12em) plus a hairline of air.
            // At the default 0.1em emphasis gap, dot ink starts at +0.22em,
            // so the +0.18em line remains first.
            // The straight line's centre and the wavy line's upper envelope keep the same
            // visual clearance from the face. A shared centre line made the wave crest rise
            // 0.06em into that clearance and touch the glyphs.
            val lineYEm = if (span.kind == DecorationKind.BookTitle) {
                BOOK_TITLE_WAVE_LINE_Y_EM
            } else {
                INTERLINEAR_LINE_Y_EM
            }
            val lineY = baseline + fontSize * lineYEm
            spanSegments += DecorationSegmentInfo(
                sourceRange = TextRange(segStart, segEnd),
                kind = span.kind.name,
                lineIndex = lineIndex,
                left = leftEdge,
                top = if (isLine) lineY else baseline - fontSize * MOURNING_FRAME_FACE_ASCENT_EM,
                right = right,
                bottom = if (isLine) lineY else baseline + fontSize * MOURNING_FRAME_FACE_DESCENT_EM,
                openStart = segStart > span.range.start,
                openEnd = segEnd < span.range.end,
                reason = "",
            )
        }
        val reason = when {
            span.kind == DecorationKind.Mourning && spanSegments.size <= 1 -> "MourningSpanKeptUnbroken"
            span.kind == DecorationKind.Mourning -> "mourning-span-split-across-lines"
            else -> "InterlinearLinePerAnnotatedItem"
        }
        segments += spanSegments.map { it.copy(reason = reason) }
    }
    return shortenAdjacentInterlinearLines(segments, fontSize)
}

/**
 * `AdjacentInterlinearLineShortening` (CLREQ 行间标点通则): adjacent
 * 专名号/书名号 marks shorten their ADJACENT sides only, so two
 * annotated items read as two — the outer sides keep the text's outer
 * frame. Each adjacent edge pulls back 1/16 em (the visible gap is
 * 1/8 em, within the ≤1/8 em-per-side cap).
 */
private fun shortenAdjacentInterlinearLines(
    segments: List<DecorationSegmentInfo>,
    fontSize: Float,
): List<DecorationSegmentInfo> {
    val lineKinds = setOf(DecorationKind.ProperNoun.name, DecorationKind.BookTitle.name)
    val result = segments.toMutableList()
    val byLine = result.withIndex()
        .filter { it.value.kind in lineKinds }
        .groupBy { it.value.lineIndex }
    for ((_, entries) in byLine) {
        val ordered = entries.sortedBy { it.value.left }
        for (i in 0 until ordered.size - 1) {
            val a = ordered[i]
            val b = ordered[i + 1]
            if (b.value.left - a.value.right > ADJACENT_LINE_EPSILON * fontSize) continue
            val pullback = fontSize * ADJACENT_LINE_SHORTEN_EM
            result[a.index] = result[a.index].copy(
                right = result[a.index].right - pullback,
                reason = result[a.index].reason + ";AdjacentInterlinearLineShortening",
            )
            result[b.index] = result[b.index].copy(
                left = result[b.index].left + pullback,
                reason = result[b.index].reason + ";AdjacentInterlinearLineShortening",
            )
        }
    }
    return result
}

/**
 * 行间注 geometry (ruby, ADR 0032): centre each注文 over the x-span of its
 * base clusters on the line they land. `advance` is untouched (注文 overhangs
 * if wider — diagnostic [RubyDecisionInfo.overhang]); the renderer measures
 * the real注文 width and centres on [RubyDecisionInfo.centerX]. Vertical
 * placement seats each annotation's declared Latin descent above the highest
 * annotated base face. It first occupies existing inter-line space; any
 * font-metric deficit was already reflected in the selected line-height mode.
 * A base split across lines yields one decision per line (each over its
 * on-line fragment).
 */
private fun computeRubyDecisions(
    rubySpans: List<RubySpan>,
    lineRanges: List<IntRange>,
    lineBoxes: List<LineBox>,
    finalClusters: List<Cluster>,
    naturalClusters: List<Cluster>,
    metricDecisions: List<ClusterMetricDecision>,
    rubyFontGeometryBySpan: Map<RubySpan, RubyFontGeometry>,
    rubyStackGap: Float,
    fallbackBaseAscent: Float,
    rubyFontSize: Float,
    rubyFontWeight: Int,
    baseLocale: String,
): List<RubyDecisionInfo> {
    if (rubySpans.isEmpty()) return emptyList()
    val out = mutableListOf<RubyDecisionInfo>()
    for (ruby in rubySpans) {
        val rubyGeometry = rubyFontGeometryBySpan.getValue(ruby)
        lineRanges.forEachIndexed { lineIndex, clusterRange ->
            var x = lineBoxes[lineIndex].indent
            var baseLeft = Float.NaN
            var contentWidth = 0f
            var baseFaceTop = Float.POSITIVE_INFINITY
            for (idx in clusterRange) {
                val cluster = finalClusters[idx]
                if (cluster.range.start >= ruby.baseRange.start && cluster.range.end <= ruby.baseRange.end) {
                    if (baseLeft.isNaN()) baseLeft = x
                    // Centre on the base CONTENT (natural width), NOT the 避让-widened
                    // slot — the spread is trailing space the注文 must not centre over.
                    contentWidth += naturalClusters[idx].advance
                    val ascent = metricDecisions.firstOrNull {
                        cluster.range.start >= it.range.start && cluster.range.end <= it.range.end
                    }?.layoutMetrics?.ascent ?: fallbackBaseAscent
                    baseFaceTop = minOf(
                        baseFaceTop,
                        lineBoxes[lineIndex].baseline + cluster.baselineShift - ascent,
                    )
                }
                x += cluster.advance
            }
            if (!baseLeft.isNaN()) {
                val rubyWidth = rubyGeometry.width
                out += RubyDecisionInfo(
                    baseRange = ruby.baseRange,
                    text = ruby.text,
                    lineIndex = lineIndex,
                    centerX = baseLeft + contentWidth / 2f,
                    baselineY = baseFaceTop - rubyStackGap - rubyGeometry.descent,
                    fontSize = rubyFontSize,
                    ascent = rubyGeometry.ascent,
                    descent = rubyGeometry.descent,
                    width = rubyWidth,
                    overhang = ((rubyWidth - contentWidth) / 2f).coerceAtLeast(0f),
                    fontFamilies = ruby.fontFamilies,
                    fontWeight = rubyFontWeight,
                    locale = ruby.locale ?: baseLocale,
                    glyphs = rubyGeometry.glyphs,
                )
            }
        }
    }
    return out
}

/**
 * 注音 geometry (ADR 0033): for each Bopomofo span, lay the ㄅㄆㄇ symbols (9×9 份)
 * and the 调号 (5×5 份 / 轻声) in the base's right-side 15-份 zone, mapping the
 * 30-份 grid onto the base 字身框 (typo box). `BopomofoParser` derives the tone.
 */
private fun TiqianParagraphLayoutEngine.computeBopomofoDecisions(
    rubySpans: List<RubySpan>,
    lineRanges: List<IntRange>,
    lineBoxes: List<LineBox>,
    finalClusters: List<Cluster>,
    naturalClusters: List<Cluster>,
    baseAscent: Float,
    baseDescent: Float,
    fontSize: Float,
    bopomofoFontWeightAt: (Int) -> Int,
    baseTextStyle: TextStyle,
): List<BopomofoDecisionInfo> {
    if (rubySpans.isEmpty()) return emptyList()
    val hUnit = fontSize / 30f
    val vUnit = (baseAscent + baseDescent) / 30f
    val out = mutableListOf<BopomofoDecisionInfo>()
    for (ruby in rubySpans) {
        val rubyLocale = ruby.locale ?: baseTextStyle.locale
        lineRanges.forEachIndexed { lineIndex, clusterRange ->
            var x = lineBoxes[lineIndex].indent
            var contentLeft = Float.NaN
            var contentWidth = 0f
            for (idx in clusterRange) {
                val cluster = finalClusters[idx]
                if (cluster.range.start >= ruby.baseRange.start && cluster.range.end <= ruby.baseRange.end) {
                    if (contentLeft.isNaN()) contentLeft = x
                    contentWidth += naturalClusters[idx].advance
                }
                x += cluster.advance
            }
            if (contentLeft.isNaN()) return@forEachIndexed
            val zoneLeft = contentLeft + contentWidth // 注音 zone = right of base content
            val boxTop = lineBoxes[lineIndex].baseline - baseAscent
            val parsed = BopomofoParser.parse(ruby.text)
            val n = parsed.symbols.size.coerceIn(1, 3)
            val neutral = parsed.tone == BopomofoTone.Neutral
            fun box(leftU: Float, widthU: Float, topU: Int, botU: Int, role: BopomofoGlyphRole, text: String) =
                BopomofoGlyphPlacement(
                    text = text,
                    left = zoneLeft + leftU * hUnit,
                    top = boxTop + topU * vUnit,
                    width = widthU * hUnit,
                    height = (botU - topU) * vUnit,
                    role = role,
                )
            val placements = buildList {
                if (parsed.tone == BopomofoTone.Neutral) {
                    // 轻声在视觉/阅读顺序上都先于注音符号；它仍放在同一个符号列内。
                    val (topU, botU) = bopomofoNeutralRow(n)
                    add(box(1f, 9f, topU, botU, BopomofoGlyphRole.Neutral, "˙"))
                }
                // ㄅㄆㄇ symbols: 9-份 column at [1,10]份.
                val rows = bopomofoSymbolRows(n, neutral)
                parsed.symbols.take(3).forEachIndexed { i, sym ->
                    val (topU, botU) = rows[i]
                    add(box(1f, 9f, topU, botU, BopomofoGlyphRole.Symbol, sym))
                }
                when (parsed.tone) {
                    // 轻声: full-width vert-alt drawn at the 9-份 column size; the box
                    // is the DOT's target rect (column-wide × the 2-份 neutral row) —
                    // the renderer h-centres + ink-positions the dot into it.
                    BopomofoTone.Neutral -> Unit
                    // 平上去: 5×5 in the 调号 column [10,15]份, upper-right.
                    BopomofoTone.Yangping, BopomofoTone.Shang, BopomofoTone.Qu -> {
                        val (topU, botU) = bopomofoRegularToneRow(n)
                        add(box(10f, 5f, topU, botU, BopomofoGlyphRole.Tone, bopomofoToneGlyph(parsed.tone)))
                    }
                    // 入声: 5×5 lower-right (parser does not emit it in v1).
                    BopomofoTone.Ru -> {
                        val (topU, botU) = bopomofoRuToneRow(n)
                        add(box(10f, 5f, topU, botU, BopomofoGlyphRole.Tone, bopomofoToneGlyph(parsed.tone)))
                    }
                    BopomofoTone.Yinping -> Unit // no mark
                }
            }
            if (placements.isNotEmpty()) {
                val placementWeight = bopomofoFontWeightAt(ruby.baseRange.start)
                val replayPlacements = placements.map { placement ->
                    fun shapeAt(size: Float): ShapingResult {
                        val range = TextRange(0, placement.text.length)
                        val decision = fallbackResolver.resolve(
                            text = placement.text,
                            range = range,
                            request = FontRequest(
                                preferredFamilies = ruby.fontFamilies,
                                locale = rubyLocale,
                                role = FontRole.CjkText,
                            ),
                        )
                        return textShaper.shape(
                            ShapingInput(
                                text = placement.text,
                                range = range,
                                style = baseTextStyle.copy(
                                    fontSize = size,
                                    fontFamilies = ruby.fontFamilies,
                                    fontWeight = placementWeight,
                                    italic = false,
                                    locale = rubyLocale,
                                ),
                                fontDecision = decision,
                                displayText = placement.text,
                                openTypeFeatures = listOf("vert=1"),
                            ),
                        )
                    }

                    fun ShapingResult.inkBounds(): Rect? {
                        val bounds = glyphRuns.flatMap { it.glyphs }.mapNotNull { glyph ->
                            glyph.bounds?.let { bound ->
                                Rect(
                                    left = bound.left + glyph.x,
                                    top = bound.top + glyph.y,
                                    right = bound.right + glyph.x,
                                    bottom = bound.bottom + glyph.y,
                                )
                            }
                        }
                        if (bounds.isEmpty()) return null
                        return Rect(
                            left = bounds.minOf { it.left },
                            top = bounds.minOf { it.top },
                            right = bounds.maxOf { it.right },
                            bottom = bounds.maxOf { it.bottom },
                        )
                    }

                    val replayFontSize = when (placement.role) {
                        BopomofoGlyphRole.Neutral -> placement.width
                        BopomofoGlyphRole.Symbol,
                        BopomofoGlyphRole.Tone,
                        -> fontSize * BOPOMOFO_ANNOTATION_FONT_EM
                    }
                    // BopomofoToneSharedAnnotationEmSizing: keep the previously verified
                    // annotation size; the 5×5 tone slot only supplies the centre target.
                    val shaped = shapeAt(replayFontSize)
                    val glyphs = shaped.glyphRuns.flatMap { it.glyphs }
                    val advance = shaped.clusters.sumOf { it.advance.toDouble() }.toFloat()
                    val ink = shaped.inkBounds()
                    // Skia/Android/web replay this horizontal-baseline origin directly:
                    // the ㄅㄆㄇ symbol centres by its advance and sits on the 字身框
                    // baseline. Core Text draws a real vertical run, so its renderer derives
                    // its own top-centre origin from the box instead of replaying these.
                    val drawX = when (placement.role) {
                        BopomofoGlyphRole.Symbol,
                        BopomofoGlyphRole.Neutral,
                        -> placement.left + (placement.width - advance) / 2f

                        BopomofoGlyphRole.Tone -> placement.left + placement.width / 2f -
                            ((ink?.left ?: 0f) + (ink?.right ?: advance)) / 2f
                    }
                    val baselineY = when (placement.role) {
                        BopomofoGlyphRole.Symbol ->
                            placement.top + placement.height * BOPOMOFO_SYMBOL_BASELINE_FACTOR

                        BopomofoGlyphRole.Neutral,
                        BopomofoGlyphRole.Tone,
                        -> placement.top + placement.height / 2f -
                            ((ink?.top ?: 0f) + (ink?.bottom ?: 0f)) / 2f
                    }
                    placement.copy(
                        glyphs = glyphs,
                        drawX = drawX,
                        baselineY = baselineY,
                        fontSize = replayFontSize,
                    )
                }
                out += BopomofoDecisionInfo(
                    ruby.baseRange,
                    ruby.text,
                    lineIndex,
                    replayPlacements,
                    ruby.fontFamilies,
                    bopomofoFontWeightAt(ruby.baseRange.start),
                    rubyLocale,
                )
            }
        }
    }
    return out
}

/** ㄅㄆㄇ vertical rows [顶,底]份 by symbol count (ADR 0033 表), with/without 轻声. */
private fun bopomofoSymbolRows(n: Int, neutral: Boolean): List<Pair<Int, Int>> = when {
    n <= 1 -> listOf(11 to 20)
    n == 2 -> listOf(6 to 15, 17 to 26)
    else -> if (neutral) listOf(3 to 12, 12 to 21, 21 to 30) else listOf(2 to 11, 11 to 20, 20 to 29)
}

private fun bopomofoNeutralRow(n: Int): Pair<Int, Int> = when (n) {
    1 -> 8 to 10
    2 -> 3 to 5
    else -> 0 to 2
}

private fun bopomofoRegularToneRow(n: Int): Pair<Int, Int> = when (n) {
    1 -> 9 to 14
    2 -> 15 to 20
    else -> 18 to 23
}

private fun bopomofoRuToneRow(n: Int): Pair<Int, Int> = when (n) {
    1 -> 16 to 21
    2 -> 21 to 26
    else -> 24 to 29
}

private fun bopomofoToneGlyph(tone: BopomofoTone): String = when (tone) {
    BopomofoTone.Yangping -> "ˊ" // ˊ
    BopomofoTone.Shang -> "ˇ"    // ˇ
    BopomofoTone.Qu -> "ˋ"       // ˋ
    BopomofoTone.Neutral -> "˙"  // ˙
    else -> ""
}

internal data class RubyFontGeometry(
    val width: Float,
    val ascent: Float,
    val descent: Float,
    val requiredExtent: Float,
    val glyphs: List<Glyph>,
)

/** ADR 0018 final painted diameter; renderers must not apply another scale factor. */
private const val EMPHASIS_DOT_DIAMETER_EM = 0.19f

/** 注音符号和普通调号共用的稳定字号；5×5 调号格只负责定位. */
private const val BOPOMOFO_ANNOTATION_FONT_EM = 0.3f

/** ㄅㄆㄇ symbol baseline as a fraction of its 字身框 height (horizontal-baseline replay). */
private const val BOPOMOFO_SYMBOL_BASELINE_FACTOR = 0.88f

/**
 * ADR 0018: 示亡号 frame hugs the CJK character face (字面) with no margin.
 * The face spans baseline-0.88em..baseline+0.12em in conventional CJK
 * design; font-reported ideographic metrics are a follow-up.
 */
private const val MOURNING_FRAME_FACE_ASCENT_EM = 0.88f

private const val MOURNING_FRAME_FACE_DESCENT_EM = 0.12f

/**
 * 行间线（专名号/书名号甲式）的横排 y：字身底 (+0.12em) 下方留一线空气
 * （行间标点应尽量紧贴所标注汉字一侧）。着重号默认净空 0.1em，点墨水
 * 上缘在 +0.22em，故 +0.18em 的线仍在点之前。
 */
private const val INTERLINEAR_LINE_Y_EM = 0.18f

/**
 * 书名号中心线额外下移一个 0.06em 波幅，使最上方波峰与专名号直线的上缘保持同等净空。
 * 波形参数仍由 renderer 复用；这里只记录布局拥有的最终物理 y。
 */
private const val BOOK_TITLE_WAVE_LINE_Y_EM = 0.24f

/** 相邻行间线各自回缩量（可见间隙 1/8em，单侧 ≤1/8em 上限内）. */
private const val ADJACENT_LINE_SHORTEN_EM = 0.0625f

/** 相邻判定：间距小于此值视为相邻（密排时为 0）. */
private const val ADJACENT_LINE_EPSILON = 0.01f
