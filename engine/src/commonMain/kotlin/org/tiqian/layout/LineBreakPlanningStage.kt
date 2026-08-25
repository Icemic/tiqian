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
import org.tiqian.clreq.AdjustmentStylePolicy
import org.tiqian.clreq.ResolvedKinsoku

/**
 * Paragraph-scope shared state produced by cluster preparation in
 * [TiqianParagraphLayoutEngine.layoutWithRejectedTechnicalTiers] and
 * consumed unchanged by [planParagraphLines] and [finishParagraphLayout].
 */
internal class ParagraphLayoutPrep(
    val input: LayoutInput,
    val rejectedTechnicalTiersBySpan: Map<TextRange, Set<ProgressiveBreakTier>>,
    val text: String,
    val fontSize: Float,
    val styleAt: (Int) -> TextStyle,
    val fontSizeAt: (Int) -> Float,
    val bopomofoFontWeightAt: (Int) -> Int,
    val rubyFontSize: Float,
    val rubyStackGap: Float,
    val rubyFontWeight: Int,
    val pinyinSpans: List<RubySpan>,
    val clreqProfile: ClreqProfile,
    val punctuationGlyphSubstitutor: ClreqPunctuationGlyphSubstitutor,
    val measure: Float,
    val measureEm: Float,
    val gridBodyOffset: Float,
    val lineLengthGridDecision: LineLengthGridDecisionInfo,
    val quotePairs: List<QuotePair>,
    val roleOverrideInfos: List<RoleOverrideInfo>,
    val fontDecisions: List<FontDecision>,
    val hyphenOffsets: Set<Int>,
    val hyphenAdvance: Float,
    val hyphenGlyphs: List<Glyph>,
    val substitutionRollbacks: Map<TextRange, String>,
    val breakOpportunityDecisions: List<BreakOpportunityDecisionInfo>,
    val emergencyTrackingEligibilityDecisions: List<EmergencyTrackingEligibilityDecisionInfo>,
    val progressiveBreakOffsets: Map<Int, ProgressiveBreakOpportunity>,
    val shapedGlyphsByClusterRange: Map<TextRange, List<Glyph>>,
    val openTypeFeaturesByClusterRange: Map<TextRange, List<String>>,
    val shapingDecisions: List<ShapingDecisionInfo>,
    val eastAsianSpacingEdges: List<EastAsianSpacingEdges>,
    val autoSpaceDecisions: List<AutoSpaceDecisionInfo>,
    val inlineBoxResult: InlineBoxApplicationResult,
    val naturalClusters: List<Cluster>,
    val inlineObjectByClusterIndex: Map<Int, InlineObjectSpan>,
    val uniformInlineObjectBoundaryAfterClusters: Set<Int>,
    val preferredInlineObjectBoundaryAfterClusters: Map<Int, InlineObjectPreferredStretch>,
    val inlineObjectBoundaryUnbreakableRanges: List<IntRange>,
    val clusterRoles: List<FontRole>,
    val resolvedKinsoku: ResolvedKinsoku,
    val kinsokuRule: ClreqKinsokuRule,
    val inlineObjectAttachedMarks: List<InlineObjectAttachedMark>,
    val inlineObjectSeparatorSpaceTrims: Map<Int, Float>,
    val inlineObjectAttachmentNoStretchBoundaries: Set<Int>,
    val inlineObjectPunctuationAttachmentDecisions: List<InlineObjectPunctuationAttachmentDecisionInfo>,
    val mandatoryBreakClusters: Set<Int>,
    val zeroWidthBreakClusters: Set<Int>,
    val mandatoryBreakDecisions: List<MandatoryBreakDecisionInfo>,
    val zeroWidthBreakDecisions: List<ZeroWidthBreakDecisionInfo>,
    val punctuationAtoms: List<PunctuationAtom>,
    val spacingPlan: PunctuationSpacingCompressionResult,
    val rubyFontGeometryBySpan: Map<RubySpan, RubyFontGeometry>,
    val rubyAndBopomofoSpread: Map<Int, Float>,
    val naturalInlineAttachments: List<InlineAttachment>,
    val attachedPunctuationBoundary: AttachedInlinePunctuationBoundaryResult,
    val baseGeometry: PunctuationGeometryLedger,
    val attachedPunctuationTrailingGlueByCluster: Map<Int, Float>,
    val clusters: List<Cluster>,
    val adjustmentStyle: AdjustmentStylePolicy,
    val atomClassByRange: Map<TextRange, PunctuationClass>,
    val shrinkOpportunities: List<ShrinkOpportunity>,
)

/** Locals of the line-planning stage that the finishing stage consumes unchanged. */
internal class LineBreakPlanningStageResult(
    val metricDecisions: List<ClusterMetricDecision>,
    val metricDecisionByRange: Map<TextRange, ClusterMetricDecision>,
    val baseAscent: Float,
    val baseDescent: Float,
    val baseBoxDescent: Float,
    val baseFaceHeight: Float,
    val existingInterlineSpace: Float,
    val rubyExtent: Float,
    val baseLineMetrics: ResolvedLineMetrics,
    val lineSpacingDecision: LineSpacingDecisionInfo?,
    val blockIndent: Float,
    val firstLineIndent: Float,
    val firstLineIndentDecision: FirstLineIndentDecisionInfo,
    val kinsokuDecision: KinsokuDecisionInfo,
    val asciiPointMarkKinsoku: ContextualKinsoku,
    val inlineObjectKinsoku: ContextualKinsoku,
    val unicodePunctuationBoundaries: UnicodePunctuationBoundaries,
    val westernBracketCjkInterCharBoundaryAfterClusters: Set<Int>,
    val attachedInlinePhysicalBoundaryAfterClusters: Set<Int>,
    val attachedInlineVirtualBoundaryAfterClusters: Map<Int, Int>,
    val attachedInlineVirtualSinoWesternBoundaryAfterClusters: Set<Int>,
    val noStretchBoundaryClusters: Set<Int>,
    val noStretchBoundaryAfterClusters: Set<Int>,
    val technicalBoundaryAfterClusters: Map<Int, ProgressiveBreakTier>,
    val emergencyTrackingBoundaryAfterClusters: Map<Int, String>,
    val progressiveBreakOpportunities: Map<Int, ProgressiveBreakOpportunity>,
    val lineSolution: LineSolution,
)

/**
 * Font-metric, line-spacing, indent, kinsoku and break-constraint resolution
 * ending in the initial [LineSolution]. Pure move of the corresponding
 * pipeline segment out of [TiqianParagraphLayoutEngine].
 */
internal fun TiqianParagraphLayoutEngine.planParagraphLines(
    prep: ParagraphLayoutPrep,
): LineBreakPlanningStageResult = with(prep) {
        // `FontMetricFaceSelectionTextJoin`: font decisions and shaped clusters
        // are both source ordered. Walk them once instead of filtering the whole
        // paragraph for every decision (which made layout quadratic on long text).
        var metricClusterIndex = 0
        val metricDecisions = fontDecisions.map { decision ->
            while (
                metricClusterIndex < naturalClusters.size &&
                naturalClusters[metricClusterIndex].range.end <= decision.range.start
            ) {
                metricClusterIndex += 1
            }
            val displayedFaceSelectionText = buildString {
                while (
                    metricClusterIndex < naturalClusters.size &&
                    naturalClusters[metricClusterIndex].range.start < decision.range.end
                ) {
                    val cluster = naturalClusters[metricClusterIndex]
                    require(cluster.range.isInside(decision.range)) {
                        "Shaped cluster ${cluster.range} crosses font decision ${decision.range}"
                    }
                    append(cluster.displayText)
                    metricClusterIndex += 1
                }
            }.ifEmpty { text.substring(decision.range.start, decision.range.end) }
            val request = FontMetricsRequest(
                fontKey = decision.candidate.key,
                fontSize = fontSizeAt(decision.range.start),
                role = decision.role,
                locale = input.textStyle.locale,
                fontWeight = styleAt(decision.range.start).fontWeight,
                italic = styleAt(decision.range.start).italic,
                faceSelectionText = displayedFaceSelectionText,
                fontFamilies = styleAt(decision.range.start).fontFamilies,
            )
            val rawMetrics = fontMetricsResolver.resolve(request)
            val layoutMetrics = fontMetricsNormalizer.normalize(
                FontMetricsNormalizationInput(
                    request = request,
                    rawMetrics = rawMetrics,
                ),
            )
            ClusterMetricDecision(
                range = decision.range,
                sourceText = text.substring(decision.range.start, decision.range.end),
                request = request,
                rawMetrics = rawMetrics,
                layoutMetrics = layoutMetrics,
            )
        }

        // 行间注 vertical placement (ADR 0032): use the ideographic base face,
        // then seat the ruby font's declared Latin box above it. Glyph ink does
        // not participate, so reading content cannot change the baseline grid.
        val baseMetricDecisions = metricDecisions
            .filter { it.layoutMetrics.metricBox == MetricBox.IdeographicEmBox }
            .ifEmpty { metricDecisions }
        val baseAscent = baseMetricDecisions.maxOfOrNull { it.layoutMetrics.ascent }
            ?: (fontSize * CJK_FACE_ASCENT_FALLBACK_EM)
        val baseDescent = baseMetricDecisions.maxOfOrNull { it.layoutMetrics.descent }
            ?: (fontSize * CJK_FACE_DESCENT_FALLBACK_EM)
        // BaseIdeographicMetricReference (ADR 0002/0030): CJK body text AND CJK
        // punctuation both normalize to an IdeographicEmBox on the shared Roman
        // baseline. The reference therefore comes from any base-size ideographic
        // metric, not only FontRole.CjkText; otherwise "MacBook。" has an
        // ideographic line box but the punctuation is shifted toward Latin raw
        // descent because no Han body cluster appears.
        val baseRefMetrics = metricDecisions
            .firstOrNull {
                it.layoutMetrics.metricBox == MetricBox.IdeographicEmBox &&
                    it.request.fontSize == fontSize
            }
            ?.layoutMetrics
        val baseBoxDescent = baseRefMetrics?.descent ?: baseDescent
        // Required vertical space comes from the resolved Latin face's declared
        // ascent/descent. It is stable for every reading in the same font; glyph
        // bounds are deliberately irrelevant to line-height and placement.
        val rubyExtent = rubyFontGeometryBySpan.values.maxOfOrNull { it.requiredExtent } ?: 0f

        // InterlinearMarkLineSpacingFloor (CLREQ 5.6.1.1): with 行间标点
        // (着重号、示亡号 etc.) present, line spacing (height − 字身高) must not
        // drop below 1/2 字号 — a tight line height would collide the marks with
        // the next line. (双面装 5/8 is print-only — show-through — deferred to a
        // print backend, like 竖排.)
        val interlinearSpacingFloor = if (input.decorations.isEmpty()) 0f else 0.5f * fontSize
        val defaultBodyLineHeight = fontSize * DEFAULT_BODY_LINE_HEIGHT_EM
        // Resolve the unannotated baseline grid first. Ruby can consume its full
        // inter-line gap (not merely this line box's upper half-leading); only the
        // measured shortfall is added below on a per-line or paragraph-wide basis.
        val baseLineMetrics = metricDecisions.lineMetrics(
            explicitLineHeight = input.paragraphStyle.lineHeight,
            defaultLineHeight = defaultBodyLineHeight,
            spacingFloor = interlinearSpacingFloor,
        )
        val metricDecisionByRange: Map<TextRange, ClusterMetricDecision> = naturalClusters
            .zip(naturalClusters.containingItems(metricDecisions, ClusterMetricDecision::range))
            .mapNotNull { (cluster, decision) -> decision?.let { cluster.range to it } }
            .toMap()
        val baseFaceHeight = baseAscent + baseDescent
        val existingInterlineSpace = (baseLineMetrics.height - baseFaceHeight).coerceAtLeast(0f)
        val lineSpacingDecision = if (baseLineMetrics.height <= 0f) {
            null
        } else {
            val natural = baseLineMetrics.height - baseLineMetrics.extraLeading
            val requested = input.paragraphStyle.lineHeight
            // Did the mark floor raise the line above what the explicit/default
            // height alone would give? (The 0.5em floor is subsumed by the 1.5em
            // body default, so it only binds against an explicit tight lineHeight.)
            val markFloorBinds = interlinearSpacingFloor > 0f &&
                natural + interlinearSpacingFloor > (requested ?: defaultBodyLineHeight) + 0.001f
            LineSpacingDecisionInfo(
                naturalHeight = natural,
                requestedLineHeight = requested,
                resolvedHeight = baseLineMetrics.height,
                spacingFloor = interlinearSpacingFloor,
                floorApplied = markFloorBinds,
                reason = when {
                    requested != null && !markFloorBinds -> "ExplicitLineHeight"
                    markFloorBinds -> "InterlinearMarkLineSpacingFloor"
                    else -> "CjkBodyLineHeightDefault"
                },
            )
        }
        // ParagraphFirstLineIndent (CLREQ 段首缩排): the first line's usable
        // measure shrinks by the indent; rendering shifts its start edge.
        // MeasureAdaptiveFirstLineIndent: the indent default narrows to 1 字 on
        // short measures (< shortBelowEm 字); an explicit firstLineIndent (ic)
        // overrides. Threshold defaults to 14 字 like MeasureAdaptiveKinsoku's
        // hanging but is an INDEPENDENT knob (ADR 0021 amendment).
        val explicitIndentEm = input.paragraphStyle.firstLineIndent?.count
        val indentPolicy = input.paragraphStyle.firstLineIndentPolicy
        // 段落缩排 (block indent) insets EVERY line; 段首缩进 (firstLine) stacks on
        // top, relative to the block, and MAY be negative (凸排：首行退回字头).
        // Adaptive default is ≥0; an explicit value flows through as-is (incl.
        // negative). The effective per-line indent is clamped ≥0 at use.
        // `ic` resolves against the paragraph base 字身框 = fontSize (ADR 0034 段级锚点).
        val blockIndent = input.paragraphStyle.blockIndent.toPx(fontSize)
        val resolvedIndentEm = explicitIndentEm ?: indentPolicy.resolveEm(measureEm)
        val firstLineIndent = (blockIndent + resolvedIndentEm * fontSize).coerceAtLeast(0f)
        val firstLineIndentDecision = FirstLineIndentDecisionInfo(
            source = if (explicitIndentEm != null) "Explicit" else "MeasureAdaptiveFirstLineIndent",
            measureEm = measureEm,
            thresholdEm = indentPolicy.shortBelowEm,
            resolvedEm = resolvedIndentEm,
        )
        // Resolve 禁则档 + 悬挂 from the kinsoku mode and the measure in 字
        // (MeasureAdaptiveKinsoku default keys on measure/fontSize).
        val kinsokuDecision = KinsokuDecisionInfo(
            measureEm = measureEm,
            level = resolvedKinsoku.level.name,
            hanging = resolvedKinsoku.hanging.name,
            reason = resolvedKinsoku.reason,
        )
        // LineEndHangingPunctuation (CLREQ 行尾点号悬挂, ADR 0006): which
        // clusters may hang past the measure. 顿/逗/句 only.
        val hangableClusters: Set<Int> = when (resolvedKinsoku.hanging) {
            HangingPunctuationStyle.Disabled -> emptySet()
            HangingPunctuationStyle.PauseStops -> naturalClusters.indices.filterTo(mutableSetOf()) { idx ->
                naturalClusters[idx].displayText.singleOrNull() in HANGABLE_PUNCTUATION
            }
        }
        // 行首/行尾禁则按解析出的 KinsokuLevel（CLREQ 四档）；空集 = 不处理档.
        val asciiPointMarkKinsoku = naturalClusters.attachedAsciiPointMarkKinsoku(
            clusterRoles = clusterRoles,
            lineBreakClusters = clusters,
            level = resolvedKinsoku.level,
            bodyLineWidth = measure - blockIndent,
            firstLineWidth = measure - firstLineIndent,
        )
        val inlineObjectKinsoku = naturalClusters.inlineObjectAttachedKinsoku(
            attachments = inlineObjectAttachedMarks,
            lineBreakClusters = clusters,
            level = resolvedKinsoku.level,
            bodyLineWidth = measure - blockIndent,
            firstLineWidth = measure - firstLineIndent,
        )
        val resolvedHangableClusters =
            hangableClusters +
                asciiPointMarkKinsoku.impossibleMeasureHangEligibleClusters +
                inlineObjectKinsoku.impossibleMeasureHangEligibleClusters
        val unicodePunctuationBoundaries = resolveUnicodePunctuationBoundaries(
            text = text,
            clusters = naturalClusters,
            clusterRoles = clusterRoles,
            quotePairs = quotePairs,
        )
        val inlineAttachments = naturalInlineAttachments
        val westernBracketBoundaries = resolveWesternBracketCjkInterCharBoundaries(
                text = text,
                clusters = naturalClusters,
                clusterRoles = clusterRoles,
            )
        val attachedInlineInterCharBoundaries = resolveAttachedInlineInterCharBoundaries(
            text = text,
            clusters = naturalClusters,
            clusterRoles = clusterRoles,
            eastAsianSpacingEdges = eastAsianSpacingEdges,
            westernBoundaryAfterClusters = westernBracketBoundaries,
            inlineAttachments = inlineAttachments,
        )
        val westernBracketCjkInterCharBoundaryAfterClusters =
            attachedInlineInterCharBoundaries.ordinaryWesternBoundaryAfterClusters
        val attachedInlinePhysicalBoundaryAfterClusters =
            attachedInlineInterCharBoundaries.suppressedPhysicalBoundaryAfterClusters
        val attachedInlineVirtualBoundaryAfterClusters =
            attachedInlineInterCharBoundaries.virtualBoundaryAfterClusters
        val attachedInlineVirtualSinoWesternBoundaryAfterClusters =
            attachedInlineInterCharBoundaries.virtualSinoWesternBoundaryAfterClusters
        val attachedInlineForbiddenLineStartClusters = inlineAttachments.indices.filterTo(mutableSetOf()) {
            inlineAttachments[it] == InlineAttachment.Previous
        }
        val forbiddenLineStartClusters: Set<Int> = naturalClusters.indices.filterTo(mutableSetOf()) { idx ->
            idx in attachedInlineForbiddenLineStartClusters ||
                idx in zeroWidthBreakClusters ||
                (
                clusterRoles.getOrNull(idx).isCjkKinsokuRole() &&
                    kinsokuRule.forbiddenAtLineStart(naturalClusters[idx])
                ) ||
                idx in unicodePunctuationBoundaries.forbiddenLineStartClusters ||
                idx in asciiPointMarkKinsoku.forbiddenLineStartClusters ||
                idx in inlineObjectKinsoku.forbiddenLineStartClusters
        }
        val forbiddenLineEndClusters: Set<Int> = naturalClusters.indices.filterTo(mutableSetOf()) { idx ->
            (
                clusterRoles.getOrNull(idx).isCjkKinsokuRole() &&
                    kinsokuRule.forbiddenAtLineEnd(naturalClusters[idx])
                ) ||
                idx in unicodePunctuationBoundaries.forbiddenLineEndClusters
        }
        // LineEndHangingHyphen as a LAST resort (ADR 0029 amendment): a break
        // before one of these clusters is a syllable/hard-break continuation —
        // the breaker prefers whole-word wrap + justification and only takes it
        // when the line would otherwise stretch 汉字间距 past the threshold.
        val hyphenBreakClusters: Set<Int> = if (hyphenOffsets.isEmpty()) {
            emptySet()
        } else {
            naturalClusters.indices.filterTo(mutableSetOf()) {
                naturalClusters[it].range.start in hyphenOffsets
            }
        }
        val clusterIndexBySourceStart = naturalClusters.indices.associateBy { naturalClusters[it].range.start }
        val progressiveTechnicalWhitespaceStretchCapacity =
            justifier.progressiveTechnicalWhitespaceStretchCapacity(fontSize)
        val progressiveBreakOpportunities: Map<Int, ProgressiveBreakOpportunity> =
            progressiveBreakOffsets.mapNotNull { (sourceOffset, opportunity) ->
                clusterIndexBySourceStart[sourceOffset]?.let { clusterIndex ->
                    clusterIndex to if (opportunity.tier == ProgressiveBreakTier.Whitespace) {
                        opportunity.copy(
                            precedingWhitespaceStretchCapacity =
                                progressiveTechnicalWhitespaceStretchCapacity,
                        )
                    } else {
                        opportunity
                    }
                }
            }.toMap()
        val progressiveTechnicalRanges = input.content.lineBreakSpans
            .filter { it.policy == LineBreakPolicy.ProgressiveTechnical }
            .map { it.range }
        val numberSymbolClusterRanges = NumberSymbolCohesion.unbreakableRanges(text)
            // `ProgressiveTechnicalOverridesNumberSymbolCohesion`: CLREQ's number/unit cohesion
            // describes prose numbers such as `37℃` and `¥100`. Digits inside an explicitly
            // technical URL/hash/code span belong to that span's Structural → Syllable →
            // Emergency policy; treating a long digit run as unbreakable can retreat a rightmost
            // Emergency cut hundreds of pixels and then fill the gap with letter tracking.
            .filterNot { sourceRange ->
                progressiveTechnicalRanges.any { technicalRange ->
                    sourceRange.first < technicalRange.end &&
                        sourceRange.last + 1 > technicalRange.start
                }
            }
            .mapNotNull { r ->
                naturalClusters.clusterIndexRangeFor(TextRange(r.first, r.last + 1))
            }
        val numberSymbolUnbreakableRanges = numberSymbolClusterRanges
            .filter { idxRange ->
                idxRange.sumOf { naturalClusters[it].advance.toDouble() } <= measure
            }
        // CLREQ 拉伸限制②：连接号、分隔号与其左右字符之间不拉伸。
        // 原子长标号也封住两侧，避免制造看似源文本空格的间距。
        val noStretchBoundaryClusters: Set<Int> = naturalClusters.indices.filterTo(mutableSetOf()) { idx ->
            when (atomClassByRange[naturalClusters[idx].range]) {
                PunctuationClass.Connector,
                PunctuationClass.Solidus,
                PunctuationClass.Dash,
                PunctuationClass.Ellipsis,
                -> true
                else -> false
            }
        }
        val noStretchBoundaryAfterClusters = numberSymbolClusterRanges
            .flatMapTo(mutableSetOf()) { range -> range.first until range.last }
            .apply {
                addAll(inlineObjectAttachmentNoStretchBoundaries)
            }
        val technicalBoundaryAfterClusters = progressiveBreakOpportunities
            .filterValues { it.tier == ProgressiveBreakTier.Whitespace }
            .mapKeys { (rightIndex, _) -> rightIndex - 1 }
            .mapValues { (_, opportunity) -> opportunity.tier }
        // `ExplicitEmergencyTrackingEligibility`: only ranges named by the shaping
        // stage may open intra-token tracking. The map is cluster-indexed so the
        // Justifier replays source-grapheme boundaries without reclassifying text.
        val emergencyTrackingBoundaryAfterClusters = buildMap<Int, String> {
            for (leftIndex in 0 until naturalClusters.lastIndex) {
                val rightIndex = leftIndex + 1
                val left = naturalClusters[leftIndex]
                val right = naturalClusters[rightIndex]
                if (left.range.end != right.range.start) continue
                if (
                    leftIndex in inlineObjectByClusterIndex || rightIndex in inlineObjectByClusterIndex ||
                    leftIndex in zeroWidthBreakClusters || rightIndex in zeroWidthBreakClusters ||
                    leftIndex in mandatoryBreakClusters || rightIndex in mandatoryBreakClusters ||
                    left.text.isEmpty() || right.text.isEmpty() ||
                    left.text.all(Char::isWhitespace) || right.text.all(Char::isWhitespace)
                ) {
                    continue
                }
                val eligibility = emergencyTrackingEligibilityDecisions.firstOrNull { decision ->
                    left.range.start >= decision.range.start && right.range.end <= decision.range.end
                } ?: continue
                put(leftIndex, eligibility.reason)
            }
        }
        // The breaker's looseness estimate keeps its established two-class
        // approximation. The exact final allocation, including repeated
        // participation of word and sino-western gaps, belongs to Justifier.
        val adjustableInlineBoundaryRightClusters = uniformInlineObjectBoundaryAfterClusters
            .mapNotNullTo(mutableSetOf()) { leftIndex ->
                val rightIndex = leftIndex + 1
                if (
                    leftIndex in noStretchBoundaryAfterClusters ||
                    leftIndex in noStretchBoundaryClusters ||
                    rightIndex in noStretchBoundaryClusters
                ) {
                    null
                } else {
                    rightIndex
                }
            }
        val cjkInterCharBoundaries: Set<Int> = buildSet {
            addAll((1 until naturalClusters.size).filter {
                it - 1 !in attachedInlinePhysicalBoundaryAfterClusters &&
                    it - 1 !in noStretchBoundaryAfterClusters &&
                    clusterRoles[it - 1] == FontRole.CjkText && clusterRoles[it] == FontRole.CjkText
            })
            // Paragraph-global and lookahead breakers price the same safe
            // inline-object gaps that the final justifier can actually use.
            addAll(adjustableInlineBoundaryRightClusters)
            // `WesternBracketCjkInterChar`: the breaker must price the same
            // proportional-bracket gaps that final tier-3 justification uses.
            addAll(westernBracketCjkInterCharBoundaryAfterClusters.map { it + 1 })
            addAll(attachedInlineVirtualBoundaryAfterClusters.keys.map { it + 1 })
        }
        val sinoWesternBoundaries: Set<Int> = buildSet {
            addAll((1 until naturalClusters.size).filter {
                it - 1 !in attachedInlinePhysicalBoundaryAfterClusters &&
                    it - 1 !in noStretchBoundaryAfterClusters &&
                    isEastAsianSpacingBoundaryAt(
                        rightIndex = it,
                        clusters = naturalClusters,
                        spacingEdges = eastAsianSpacingEdges,
                    )
            })
            addAll(attachedInlineVirtualSinoWesternBoundaryAfterClusters.map { it + 1 })
        }
        val attachedInlineUnbreakableRanges =
            resolveAttachedInlineVirtualBoundaries(inlineAttachments).map { boundary ->
                boundary.previousClusterIndex..boundary.attachedClusterRange.last
            }
        val unbreakableRanges =
            input.decorations
                .filter { it.kind == DecorationKind.Mourning }
                .mapNotNull { span -> naturalClusters.clusterIndexRangeFor(span.range) } +
                // 行间注 (ADR 0032): 基文+注文不可拆 (CLREQ §注释符号).
                pinyinSpans.mapNotNull { naturalClusters.clusterIndexRangeFor(it.baseRange) } +
                // `AttachedInlineAvoidLineStart`: the forbidden-line-start set
                // drives kinsoku repair; this structural range also prevents the
                // initial breaker from proposing a split inside base+reference.
                attachedInlineUnbreakableRanges +
                numberSymbolUnbreakableRanges +
                // `Uax14WesternPunctuationBoundary`: express punctuation
                // protection as a closed boundary up front, not only as a
                // post-break repair. This allows ordinary reflow to move the
                // following suffix instead of leaving a closing mark at line start.
                unicodePunctuationBoundaries.unbreakableRanges +
                // `AttachedAsciiPointMarkKinsoku`: the preceding visible
                // cluster and its attached point mark form a hard no-break boundary.
                asciiPointMarkKinsoku.unbreakableRanges +
                // `InlineObjectAttachedKinsoku`: formula/widget objects are
                // visibly present even though their displayText is empty.
                inlineObjectKinsoku.unbreakableRanges +
                // Adjustment-only formula boundaries do not become accidental line breaks.
                inlineObjectBoundaryUnbreakableRanges
        val lineSolution = if (text.isEmpty()) {
            LineSolution(emptyList())
        } else {
            lineBreaker.breakLines(
                naturalClusters = naturalClusters,
                adjustedClusters = clusters,
                // The breaker only needs per-line USABLE widths (via lineLimit):
                // feed it the body width (measure − blockIndent) and a first-line
                // indent relative to it. Rest lines then get the body width, line 0
                // gets `measure − firstLineIndent`. Identical to before when
                // blockIndent = 0; enables 段落缩排/凸排 with zero breaker changes.
                maxWidth = measure - blockIndent,
                firstLineIndent = firstLineIndent - blockIndent,
                shrinkOpportunities = shrinkOpportunities,
                // MourningSpanKeptUnbroken: 示亡号 spans stay on one line
                // whenever they fit (ADR 0018). NumberSymbolCohesion: CLREQ
                // 符号分离禁则 keeps 数字 + 前后缀符号/货币 on one line — but only
                // when the group actually fits the measure; a number wider than
                // the column can't be kept whole, so it falls back to normal
                // breaking instead of forcing an impossible constraint.
                unbreakableRanges = unbreakableRanges,
                hangableClusters = resolvedHangableClusters,
                extendableHangRanges =
                    asciiPointMarkKinsoku.extendableHangRanges + inlineObjectKinsoku.extendableHangRanges,
                forbiddenLineStartClusters = forbiddenLineStartClusters,
                forbiddenLineEndClusters = forbiddenLineEndClusters,
                hyphenBreakClusters = hyphenBreakClusters,
                cjkInterCharBoundaries = cjkInterCharBoundaries,
                maxCjkStretchPerGap = HYPHEN_LAST_RESORT_CJK_STRETCH_EM * fontSize,
                sinoWesternBoundaries = sinoWesternBoundaries,
                sinoWesternStretchCap = HYPHEN_SINO_WESTERN_STRETCH_CAP_EM * fontSize,
                // LineAdjustmentStrategy (ADR 0031 修订): 推入/推出 是固定顺序,
                // 不再有「偏差最小化」折中。PushInFirst = 能压就压(bias→∞),
                // PushOutFirst = 先断行拉伸, PushOutOnly = 从不推入(旧行为)。
                lineAdjustmentPushIn = adjustmentStyle.lineAdjustment != LineAdjustmentStrategy.PushOutOnly,
                lineAdjustmentCompressBias = when (adjustmentStyle.lineAdjustment) {
                    LineAdjustmentStrategy.PushInFirst -> 1_000_000f
                    LineAdjustmentStrategy.PushOutFirst -> 0.5f
                    LineAdjustmentStrategy.PushOutOnly -> 0f
                },
                hardBreakAfterClusters = mandatoryBreakClusters,
                nonRenderingControlClusters = zeroWidthBreakClusters,
                progressiveBreakOpportunities = progressiveBreakOpportunities,
            )
        }
        LineBreakPlanningStageResult(
            metricDecisions = metricDecisions,
            metricDecisionByRange = metricDecisionByRange,
            baseAscent = baseAscent,
            baseDescent = baseDescent,
            baseBoxDescent = baseBoxDescent,
            baseFaceHeight = baseFaceHeight,
            existingInterlineSpace = existingInterlineSpace,
            rubyExtent = rubyExtent,
            baseLineMetrics = baseLineMetrics,
            lineSpacingDecision = lineSpacingDecision,
            blockIndent = blockIndent,
            firstLineIndent = firstLineIndent,
            firstLineIndentDecision = firstLineIndentDecision,
            kinsokuDecision = kinsokuDecision,
            asciiPointMarkKinsoku = asciiPointMarkKinsoku,
            inlineObjectKinsoku = inlineObjectKinsoku,
            unicodePunctuationBoundaries = unicodePunctuationBoundaries,
            westernBracketCjkInterCharBoundaryAfterClusters = westernBracketCjkInterCharBoundaryAfterClusters,
            attachedInlinePhysicalBoundaryAfterClusters = attachedInlinePhysicalBoundaryAfterClusters,
            attachedInlineVirtualBoundaryAfterClusters = attachedInlineVirtualBoundaryAfterClusters,
            attachedInlineVirtualSinoWesternBoundaryAfterClusters =
                attachedInlineVirtualSinoWesternBoundaryAfterClusters,
            noStretchBoundaryClusters = noStretchBoundaryClusters,
            noStretchBoundaryAfterClusters = noStretchBoundaryAfterClusters,
            technicalBoundaryAfterClusters = technicalBoundaryAfterClusters,
            emergencyTrackingBoundaryAfterClusters = emergencyTrackingBoundaryAfterClusters,
            progressiveBreakOpportunities = progressiveBreakOpportunities,
            lineSolution = lineSolution,
        )
}

private const val CJK_FACE_ASCENT_FALLBACK_EM = 0.88f

internal const val CJK_FACE_DESCENT_FALLBACK_EM = 0.12f

/**
 * `CjkBodyLineHeightDefault`: 中文正文默认行高 1.5em(行距约 0.5em),无显式
 * [ParagraphStyle.lineHeight] 时生效。1.0em 实贴会让真墨迹(ascent≈0.94em)与
 * 相邻行碰头,且正文常规需要行距呼吸。CLREQ 的标点 floor 是「有行间标点时的
 * 下限」,与本默认取 max:单面装 0.5em floor 正好被本默认吸收,双面装 0.625em
 * 仍可顶高。显式 lineHeight 可向下覆盖本默认(仍不低于不重叠下限)。
 */
private const val DEFAULT_BODY_LINE_HEIGHT_EM = 1.5f

/**
 * 连字作为最后一档（ADR 0029 amendment）：整词换行后，若填满版心需要给每个汉字
 * 间距加超过此值（半个字宽）才回头连字；以下则宁可拉伸汉字间距、不连字。
 */
private const val HYPHEN_LAST_RESORT_CJK_STRETCH_EM = 0.5f

/** 中西间距可拉伸余量（justify CjkLatinSpace cap 0.5em − 自然 0.25em），算松紧时先扣它. */
private const val HYPHEN_SINO_WESTERN_STRETCH_CAP_EM = 0.25f

/** CLREQ 行尾悬挂适配标点：顿号、逗号、句号. */
internal val HANGABLE_PUNCTUATION = setOf('、', '，', '。')
