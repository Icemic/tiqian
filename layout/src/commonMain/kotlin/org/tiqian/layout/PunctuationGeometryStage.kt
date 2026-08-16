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

internal data class ContextualKinsoku(
    val forbiddenLineStartClusters: Set<Int>,
    val unbreakableRanges: List<IntRange>,
    val impossibleMeasureHangEligibleClusters: Set<Int>,
    val extendableHangRanges: List<IntRange>,
    val decisions: List<ContextualKinsokuDecisionInfo>,
)

internal data class InlineObjectAttachedMark(
    val objectClusterIndex: Int,
    val separatorClusterIndices: List<Int>,
    val markClusterIndex: Int,
)

/**
 * `InlineObjectPunctuationSeparatorSpaceCollapse`: Markdown commonly retains an author-written
 * ASCII separator between inline math and following prose punctuation (`$x$ ，`). That space is
 * source content, but it is not visual punctuation spacing. Identify the complete attachment so
 * layout can collapse the space, close every intervening stretch boundary, and apply kinsoku to
 * the first visible mark rather than to the invisible space at line start.
 */
internal fun List<Cluster>.inlineObjectAttachedMarks(
    clusterRoles: List<FontRole>,
    level: KinsokuLevel,
    kinsokuRule: KinsokuRule,
): List<InlineObjectAttachedMark> {
    if (level == KinsokuLevel.None) return emptyList()
    val result = mutableListOf<InlineObjectAttachedMark>()
    for (markIndex in 1 until size) {
        val mark = this[markIndex]
        val isCjkForbidden =
            clusterRoles.getOrNull(markIndex).isCjkKinsokuRole() && kinsokuRule.forbiddenAtLineStart(mark)
        val isAttachedAsciiPointMark =
            clusterRoles.getOrNull(markIndex) == FontRole.LatinText &&
                mark.text.firstOrNull()?.let(ClreqPunctuationPolicies::isAsciiPointMark) == true
        if (!isCjkForbidden && !isAttachedAsciiPointMark) continue

        var previousIndex = markIndex - 1
        val separatorIndices = mutableListOf<Int>()
        while (
            previousIndex >= 0 &&
            this[previousIndex].isSpaceRun() &&
            this[previousIndex].range.end == this[previousIndex + 1].range.start
        ) {
            separatorIndices += previousIndex
            previousIndex--
        }
        if (previousIndex < 0) continue
        val previous = this[previousIndex]
        if (!previous.isInlineObjectCluster() || previous.range.end != this[previousIndex + 1].range.start) continue

        result += InlineObjectAttachedMark(
            objectClusterIndex = previousIndex,
            separatorClusterIndices = separatorIndices.asReversed(),
            markClusterIndex = markIndex,
        )
    }
    return result
}

/**
 * `InlineObjectAttachedKinsoku`: an inline object has no glyph display text,
 * but it is still the visible base immediately before an attached mark.
 * Keep the object's last fragment with that mark whenever the pair fits;
 * when the pair itself is wider than the line, permit the point mark to hang
 * as the last-resort alternative to leaving it at line start.
 */
internal fun List<Cluster>.inlineObjectAttachedKinsoku(
    attachments: List<InlineObjectAttachedMark>,
    lineBreakClusters: List<Cluster>,
    level: KinsokuLevel,
    bodyLineWidth: Float,
    firstLineWidth: Float,
): ContextualKinsoku {
    if (level == KinsokuLevel.None) {
        return ContextualKinsoku(emptySet(), emptyList(), emptySet(), emptyList(), emptyList())
    }
    require(size == lineBreakClusters.size) {
        "Inline-object kinsoku requires cluster-for-cluster line-break geometry"
    }

    val forbiddenLineStart = mutableSetOf<Int>()
    val unbreakableRanges = mutableListOf<IntRange>()
    val forcedHangable = mutableSetOf<Int>()
    val extendableHangRanges = mutableListOf<IntRange>()
    val decisions = mutableListOf<ContextualKinsokuDecisionInfo>()
    for (attachment in attachments) {
        val previousIndex = attachment.objectClusterIndex
        val index = attachment.markClusterIndex
        val mark = this[index]
        val isAttachedAsciiPointMark =
            mark.text.firstOrNull()?.let(ClreqPunctuationPolicies::isAsciiPointMark) == true

        forbiddenLineStart += attachment.separatorClusterIndices
        forbiddenLineStart += index
        val protectedPair = previousIndex..index
        val pairWidth = protectedPair.sumOf { lineBreakClusters[it].advance.toDouble() }.toFloat()
        val availableWidth = if (previousIndex == 0) firstLineWidth else bodyLineWidth
        if (pairWidth <= availableWidth) {
            unbreakableRanges += protectedPair
        } else {
            val mayHang =
                mark.displayText.singleOrNull() in HANGABLE_PUNCTUATION || isAttachedAsciiPointMark
            if (mayHang) {
                forcedHangable += attachment.separatorClusterIndices
                forcedHangable += index
                extendableHangRanges += protectedPair
            }
        }
        decisions += ContextualKinsokuDecisionInfo(
            range = mark.range,
            sourceText = mark.text,
            clusterIndex = index,
            forbiddenPosition = "LineStart",
            reason = if (attachment.separatorClusterIndices.isEmpty()) {
                "InlineObjectAttachedKinsoku"
            } else {
                "InlineObjectAttachedKinsokuAcrossCollapsedSeparatorSpace"
            },
        )
    }
    return ContextualKinsoku(
        forbiddenLineStartClusters = forbiddenLineStart,
        unbreakableRanges = unbreakableRanges,
        impossibleMeasureHangEligibleClusters = forcedHangable,
        extendableHangRanges = extendableHangRanges,
        decisions = decisions,
    )
}

/**
 * `AttachedAsciiPointMarkKinsoku`: directly attached ASCII `, . : ; ! ?`
 * keep their Latin face and proportional advance, but cannot begin an
 * automatically wrapped line. This covers their non-typical use in Chinese
 * prose and hard-broken Latin tokens alike. `KinsokuLevel.None` remains an
 * explicit opt-out.
 *
 * The leading point-mark run is split from following Latin text by
 * `AttachedAsciiPointMarkSegmentation`, so `中文,anyway` does not make the
 * whole `,anyway` token an indivisible kinsoku offender. Consecutive point
 * clusters remain one logical protected run across style/shaping boundaries;
 * impossible-width eligibility uses the exact post-geometry breaker advances.
 */
internal fun List<Cluster>.attachedAsciiPointMarkKinsoku(
    clusterRoles: List<FontRole>,
    lineBreakClusters: List<Cluster>,
    level: KinsokuLevel,
    bodyLineWidth: Float,
    firstLineWidth: Float,
): ContextualKinsoku {
    if (level == KinsokuLevel.None) {
        return ContextualKinsoku(emptySet(), emptyList(), emptySet(), emptyList(), emptyList())
    }
    require(size == lineBreakClusters.size) {
        "Contextual kinsoku requires cluster-for-cluster line-break geometry"
    }

    val forbiddenLineStart = mutableSetOf<Int>()
    val unbreakableRanges = mutableListOf<IntRange>()
    val forcedHangable = mutableSetOf<Int>()
    val extendableHangRanges = mutableListOf<IntRange>()
    val decisions = mutableListOf<ContextualKinsokuDecisionInfo>()
    var index = 1
    while (index < size) {
        val cluster = this[index]
        val previous = this[index - 1]
        val startsAttachedPointMarkRun =
            clusterRoles.getOrNull(index) == FontRole.LatinText &&
                cluster.text.firstOrNull()?.let(ClreqPunctuationPolicies::isAsciiPointMark) == true &&
                previous.displayText.isNotEmpty() &&
                previous.text.lastOrNull()?.isWhitespace() == false &&
                previous.range.end == cluster.range.start
        if (!startsAttachedPointMarkRun) {
            index += 1
            continue
        }

        val runStart = index
        var runEnd = index
        while (runEnd + 1 < size) {
            val next = this[runEnd + 1]
            val continuesRun =
                clusterRoles.getOrNull(runEnd + 1) == FontRole.LatinText &&
                    next.text.firstOrNull()?.let(ClreqPunctuationPolicies::isAsciiPointMark) == true &&
                    this[runEnd].range.end == next.range.start
            if (!continuesRun) break
            runEnd += 1
        }

        forbiddenLineStart += runStart..runEnd
        unbreakableRanges += (runStart - 1)..runEnd
        // `AttachedAsciiPointMarkRunCohesion`: eligibility follows the
        // exact geometry consumed by the breaker, including ruby/Bopomofo
        // spread. If the whole base+run cannot fit, every shaped/style
        // cluster in the run may extend the same last-resort hang.
        val runLineWidth = if (runStart - 1 == 0) firstLineWidth else bodyLineWidth
        val runWidth = (runStart - 1..runEnd)
            .sumOf { lineBreakClusters[it].advance.toDouble() }
            .toFloat()
        if (runWidth > runLineWidth) {
            forcedHangable += runStart..runEnd
            // The protected group includes its base cluster. If that base
            // is itself a profile-hangable point mark, provenance remains
            // inside this same contextual group when the ASCII run extends
            // the hang; unrelated ordinary hangs still cannot chain.
            extendableHangRanges += (runStart - 1)..runEnd
        }
        for (pointMarkIndex in runStart..runEnd) {
            val pointMark = this[pointMarkIndex]
            decisions += ContextualKinsokuDecisionInfo(
                range = pointMark.range,
                sourceText = pointMark.text,
                clusterIndex = pointMarkIndex,
                forbiddenPosition = "LineStart",
                reason = "AttachedAsciiPointMarkKinsoku",
            )
        }
        index = runEnd + 1
    }
    return ContextualKinsoku(
        forbiddenLineStart,
        unbreakableRanges,
        forcedHangable,
        extendableHangRanges,
        decisions,
    )
}

internal fun FontRole?.isCjkKinsokuRole(): Boolean =
    this == FontRole.CjkPunctuation

internal fun Cluster.punctuationAtoms(
    em: Float,
    builder: PunctuationAtomBuilder,
    shapedGlyphs: List<Glyph>,
    gluePlacement: PunctuationGluePlacement,
    widthPolicy: org.tiqian.clreq.PunctuationWidthPolicy,
): List<PunctuationAtom> {
    if (displayText.isEmpty()) return emptyList()

    return displayText.mapIndexedNotNull { index, char ->
        builder.build(
            char = char,
            range = displayCharSourceRange(index),
            em = em,
            inkInput = punctuationInkInputFor(index, shapedGlyphs),
            gluePlacement = gluePlacement,
            widthPolicy = widthPolicy,
        )
    }
}

/**
 * Named heuristic: `MissingInkBoundsFallback` (recording side).
 *
 * Returns null only when no shaping information exists at all — the
 * expected pure-policy path. When shaping ran but ink cannot be
 * attributed, this returns a [PunctuationInkInput] carrying a
 * `boundsFallbackReason` so the punctuation decision records *why*
 * geometry degraded instead of silently looking like the policy path.
 */
private fun Cluster.punctuationInkInputFor(displayIndex: Int, shapedGlyphs: List<Glyph>): PunctuationInkInput? {
    if (shapedGlyphs.isEmpty()) return null
    val glyph = when {
        shapedGlyphs.size == displayText.length -> shapedGlyphs.getOrNull(displayIndex)?.let { glyph ->
            // Glyph.x is cluster-local, while each punctuation atom needs
            // bounds relative to its own character pen. Remove preceding
            // glyph advances before folding the residual placement into ink.
            val characterPen = shapedGlyphs.take(displayIndex)
                .sumOf { it.advance.toDouble() }
                .toFloat()
            glyph.copy(x = glyph.x - characterPen)
        }
        displayText.length == 1 -> shapedGlyphs.unionAsSingleGlyph()
        else -> null
    } ?: return PunctuationInkInput(
        // Glyph count does not line up with display characters, so per-
        // character advance/ink cannot be attributed. Advance 0 keeps the
        // builder on the policy advance; only the reason is recorded.
        advance = 0f,
        inkBounds = null,
        boundsFallbackReason = "glyph-cluster-mapping-ambiguous",
    )
    return PunctuationInkInput(
        advance = glyph.advance,
        inkBounds = glyph.bounds?.let { bounds ->
            Rect(
                left = bounds.left + glyph.x,
                top = bounds.top + glyph.y,
                right = bounds.right + glyph.x,
                bottom = bounds.bottom + glyph.y,
            )
        },
        boundsFallbackReason = if (glyph.bounds == null) "shaper-no-ink-bounds" else null,
        haltAdvance = glyph.haltAdvance,
        haltPlacementX = glyph.haltPlacementX,
    )
}

private fun List<Glyph>.unionAsSingleGlyph(): Glyph? {
    if (isEmpty()) return null
    val first = first()
    val bounds = mapNotNull { glyph ->
        glyph.bounds?.let {
            Rect(
                left = it.left + glyph.x,
                top = it.top + glyph.y,
                right = it.right + glyph.x,
                bottom = it.bottom + glyph.y,
            )
        }
    }
    if (bounds.isEmpty()) return first
    return first.copy(
        advance = sumOf { it.advance.toDouble() }.toFloat(),
        x = 0f,
        y = 0f,
        // halt metrics are per-glyph; a union pseudo-glyph has none.
        haltAdvance = null,
        haltPlacementX = null,
        bounds = Rect(
            left = bounds.minOf { it.left.toDouble() }.toFloat(),
            top = bounds.minOf { it.top.toDouble() }.toFloat(),
            right = bounds.maxOf { it.right.toDouble() }.toFloat(),
            bottom = bounds.maxOf { it.bottom.toDouble() }.toFloat(),
        ),
    )
}

private fun Cluster.displayCharSourceRange(displayIndex: Int): TextRange =
    if (displayText.length == text.length) {
        TextRange(
            start = range.start + displayIndex,
            end = range.start + displayIndex + 1,
        )
    } else {
        range
    }

internal fun Cluster.isSpaceRun(): Boolean =
    text.isNotEmpty() && text.all { it == ' ' }

/**
 * Applies [AutoSpacePolicy] at Unicode `East_Asian_Spacing` W↔N boundaries.
 *
 * `UnicodeEastAsianSpacingBoundary` is deliberately independent of [FontRole]: Greek,
 * Cyrillic and every other code point assigned Narrow by the pinned UTR #59 data receive
 * the same spacing even when their font fallback role differs. Inline objects are resolved
 * to Other before this function, so alternate text never fabricates a text boundary.
 *
 * U+0020 remains a higher-level author override in UTR #59 (its property is Other). ADR 0009
 * keeps Tiqian's existing `Replace` contract: when one standalone space sits between W and N,
 * that source space is normalised to the configured gap instead of adding a second gap.
 */
internal fun List<Cluster>.applyAutoSpacePolicy(
    eastAsianSpacingEdges: List<EastAsianSpacingEdges>,
    inlineAttachments: List<InlineAttachment>,
    policy: AutoSpacePolicy,
    fontSize: Float,
    narrowInlineBoxLeadingClusters: Set<Int> = emptySet(),
    narrowInlineBoxTrailingClusters: Set<Int> = emptySet(),
): AutoSpaceApplicationResult {
    if (isEmpty()) return AutoSpaceApplicationResult(emptyList(), emptyList())
    require(eastAsianSpacingEdges.size == size) {
        "East_Asian_Spacing values must align with natural clusters."
    }
    require(inlineAttachments.size == size) {
        "Inline attachments must align with natural clusters."
    }

    val decisions = mutableListOf<AutoSpaceDecisionInfo>()
    val gap = policy.gapEm * fontSize
    fun modeForNarrow(boundaryChar: Char?): AutoSpaceMode? = when {
        boundaryChar == null -> null
        boundaryChar.isDigit() -> policy.cjkDigit
        else -> policy.cjkLatin
    }

    // AttachedInlineVirtualAutoSpace: neither physical edge touching an attached
    // reference is a prose boundary. Decide W/N spacing from the prose clusters
    // that would be adjacent without the reference, then own that one result at
    // the reference's trailing edge. CJK[1]CJK consequently gets no gap, while
    // CJK[1]Latin gets exactly one.
    val attachedBoundaries = resolveAttachedInlineVirtualBoundaries(inlineAttachments)
    val suppressedPhysicalBoundaryAfterClusters = buildSet {
        attachedBoundaries.forEach { boundary ->
            add(boundary.previousClusterIndex)
            if (boundary.nextClusterIndex != null) add(boundary.attachedClusterRange.last)
        }
    }
    val virtualGapAtRunEnd = BooleanArray(size)
    attachedBoundaries.forEach { boundary ->
        val nextIndex = boundary.nextClusterIndex ?: return@forEach
        val nextCluster = getOrNull(nextIndex) ?: return@forEach
        if (nextCluster.isSpaceRun() || nextCluster.isMandatoryBreakCluster()) return@forEach
        val previousIndex = boundary.previousClusterIndex
        val previousEdge = eastAsianSpacingEdges[previousIndex].trailing
        val nextEdge = eastAsianSpacingEdges[nextIndex].leading
        val narrowChar = when {
            previousEdge == EastAsianSpacingValue.Wide &&
                nextEdge == EastAsianSpacingValue.Narrow -> nextCluster.text.firstOrNull()

            previousEdge == EastAsianSpacingValue.Narrow &&
                nextEdge == EastAsianSpacingValue.Wide -> this[previousIndex].text.lastOrNull()

            else -> null
        }
        virtualGapAtRunEnd[boundary.attachedClusterRange.last] =
            modeForNarrow(narrowChar) == AutoSpaceMode.Insert
    }

    val updated = mapIndexed { idx, cluster ->
        val previousSpacing = eastAsianSpacingEdges.getOrNull(idx - 1)?.trailing
        val currentSpacing = eastAsianSpacingEdges[idx]
        val nextSpacing = eastAsianSpacingEdges.getOrNull(idx + 1)?.leading

        if (cluster.isSpaceRun()) {
            val narrowBoundaryChar = when {
                previousSpacing == EastAsianSpacingValue.Wide &&
                    nextSpacing == EastAsianSpacingValue.Narrow -> getOrNull(idx + 1)?.text?.firstOrNull()

                previousSpacing == EastAsianSpacingValue.Narrow &&
                    nextSpacing == EastAsianSpacingValue.Wide -> getOrNull(idx - 1)?.text?.lastOrNull()

                else -> null
            }
            val mode = modeForNarrow(narrowBoundaryChar)
            if (mode == null || mode == AutoSpaceMode.Disabled) return@mapIndexed cluster
            val reduction = cluster.advance - gap
            if (reduction == 0f) return@mapIndexed cluster
            decisions += AutoSpaceDecisionInfo(
                clusterRange = cluster.range,
                side = "gap",
                boundaryRole = "EastAsianSpacing.Wide",
                mode = AutoSpaceMode.Replace.name,
                charactersAffected = cluster.text.length,
                reductionPerChar = reduction / cluster.text.length,
                totalReduction = reduction,
                reason = "TextAutoSpaceReplace:east-asian-spacing-W-space-N",
            )
            cluster.copy(advance = gap)
        } else {
            var added = 0f
            if (previousSpacing == EastAsianSpacingValue.Wide &&
                currentSpacing.leading == EastAsianSpacingValue.Narrow &&
                modeForNarrow(cluster.text.firstOrNull()) == AutoSpaceMode.Insert &&
                idx - 1 !in suppressedPhysicalBoundaryAfterClusters
            ) {
                added += gap
                decisions += AutoSpaceDecisionInfo(
                    clusterRange = cluster.range,
                    side = "leading",
                    boundaryRole = if (idx in narrowInlineBoxLeadingClusters) {
                        "InlineBox.Narrow"
                    } else {
                        "EastAsianSpacing.Wide"
                    },
                    mode = AutoSpaceMode.Insert.name,
                    charactersAffected = 0,
                    reductionPerChar = 0f,
                    totalReduction = -gap,
                    reason = if (idx in narrowInlineBoxLeadingClusters) {
                        "InlineBoxOuterAutoSpace:leading-W-N"
                    } else {
                        "TextAutoSpaceInsert:east-asian-spacing-W-N"
                    },
                )
            }
            val normalTrailingGap = nextSpacing == EastAsianSpacingValue.Wide &&
                currentSpacing.trailing == EastAsianSpacingValue.Narrow &&
                modeForNarrow(cluster.text.lastOrNull()) == AutoSpaceMode.Insert &&
                idx !in suppressedPhysicalBoundaryAfterClusters
            val virtualTrailingGap = virtualGapAtRunEnd[idx]
            if (normalTrailingGap || virtualTrailingGap) {
                added += gap
                decisions += AutoSpaceDecisionInfo(
                    clusterRange = cluster.range,
                    side = "trailing",
                    boundaryRole = if (idx in narrowInlineBoxTrailingClusters) {
                        "InlineBox.Narrow"
                    } else if (virtualTrailingGap) {
                        "InlineAttachment.Previous"
                    } else {
                        "EastAsianSpacing.Wide"
                    },
                    mode = AutoSpaceMode.Insert.name,
                    charactersAffected = 0,
                    reductionPerChar = 0f,
                    totalReduction = -gap,
                    reason = if (idx in narrowInlineBoxTrailingClusters) {
                        "InlineBoxOuterAutoSpace:trailing-N-W"
                    } else if (virtualTrailingGap) {
                        "AttachedInlineVirtualAutoSpace:east-asian-spacing-W-N"
                    } else {
                        "TextAutoSpaceInsert:east-asian-spacing-W-N"
                    },
                )
            }
            if (added == 0f) cluster else cluster.copy(advance = cluster.advance + added)
        }
    }
    return AutoSpaceApplicationResult(updated, decisions)
}

/**
 * Returns true for one physical boundary representing a single W↔N gap. A typed U+0020
 * anchors the gap on its Wide side so line-cost accounting counts it exactly once.
 */
internal fun isEastAsianSpacingBoundaryAt(
    rightIndex: Int,
    clusters: List<Cluster>,
    spacingEdges: List<EastAsianSpacingEdges>,
): Boolean {
    val leftIndex = rightIndex - 1
    val left = spacingEdges[leftIndex].trailing
    val right = spacingEdges[rightIndex].leading
    if (left.isWideNarrowPairWith(right)) return true

    return when {
        clusters[rightIndex].isSpaceRun() &&
            left == EastAsianSpacingValue.Wide &&
            spacingEdges.getOrNull(rightIndex + 1)?.leading == EastAsianSpacingValue.Narrow -> true

        clusters[leftIndex].isSpaceRun() &&
            right == EastAsianSpacingValue.Wide &&
            spacingEdges.getOrNull(leftIndex - 1)?.trailing == EastAsianSpacingValue.Narrow -> true

        else -> false
    }
}

private fun EastAsianSpacingValue.isWideNarrowPairWith(other: EastAsianSpacingValue): Boolean =
    (this == EastAsianSpacingValue.Wide && other == EastAsianSpacingValue.Narrow) ||
        (this == EastAsianSpacingValue.Narrow && other == EastAsianSpacingValue.Wide)

/**
 * `AttachedAsciiPointMarkOverridesConditionalEastAsianSpacing`: UTR #59 is an informative
 * default that higher-level protocols may override. Tiqian already recognizes directly
 * attached ASCII `, . : ; ! ?` as Chinese point marks for kinsoku while retaining their
 * proportional Latin glyphs; resolving those same marks from C to N would insert gaps and
 * contradict that established punctuation contract. Standalone `%`, `#`, etc. still follow
 * the Unicode Conditional value in Chinese language context.
 */
internal fun List<Cluster>.isAttachedAsciiPointMarkAt(index: Int): Boolean {
    if (index <= 0) return false
    val cluster = this[index]
    val previous = this[index - 1]
    return cluster.text.firstOrNull()?.let(ClreqPunctuationPolicies::isAsciiPointMark) == true &&
        previous.displayText.isNotEmpty() &&
        previous.text.lastOrNull()?.isWhitespace() == false &&
        previous.range.end == cluster.range.start
}

internal data class AutoSpaceApplicationResult(
    val clusters: List<Cluster>,
    val decisions: List<AutoSpaceDecisionInfo>,
)

internal data class InlineBoxApplicationResult(
    val clusters: List<Cluster>,
    val advanceByCluster: Map<Int, Float>,
    val decisions: List<InlineBoxDecisionInfo>,
)

internal fun List<Cluster>.applyInlineBoxSpans(spans: List<InlineBoxSpan>): InlineBoxApplicationResult {
    if (isEmpty() || spans.isEmpty()) {
        return InlineBoxApplicationResult(this, emptyMap(), emptyList())
    }
    val leadingByCluster = HashMap<Int, Float>()
    val trailingByCluster = HashMap<Int, Float>()
    val decisions = mutableListOf<InlineBoxDecisionInfo>()
    for (span in spans) {
        if (span.range.start >= span.range.end) continue
        val clusterRange = clusterIndexRangeFor(span.range) ?: continue
        if (span.inlineStart != 0f) {
            leadingByCluster.mergeValue(clusterRange.first, span.inlineStart) { a, b -> a + b }
        }
        if (span.inlineEnd != 0f) {
            trailingByCluster.mergeValue(clusterRange.last, span.inlineEnd) { a, b -> a + b }
        }
        decisions += InlineBoxDecisionInfo(
            range = span.range,
            inlineStart = span.inlineStart,
            inlineEnd = span.inlineEnd,
            outerSpacing = span.outerSpacing.name,
            firstClusterIndex = clusterRange.first,
            lastClusterIndex = clusterRange.last,
        )
    }
    val advanceByCluster = HashMap<Int, Float>()
    val resolved = mapIndexed { index, cluster ->
        val leading = leadingByCluster[index] ?: 0f
        val trailing = trailingByCluster[index] ?: 0f
        val structural = leading + trailing
        if (structural != 0f) advanceByCluster[index] = structural
        if (structural == 0f && leading == 0f) {
            cluster
        } else {
            cluster.copy(
                advance = (cluster.advance + structural).coerceAtLeast(0f),
                leadingLayoutAdvance = cluster.leadingLayoutAdvance + leading,
            )
        }
    }
    return InlineBoxApplicationResult(resolved, advanceByCluster, decisions)
}

internal data class PunctuationGeometryLedger(
    private val naturalClusters: List<Cluster>,
    private val geometries: Map<Int, PunctuationClusterGeometry>,
    private val budgets: Map<Int, GlueBudget>,
    private val justificationDeltaByCluster: Map<Int, Float> = emptyMap(),
    /**
     * Raw advance reductions that are NOT punctuation glue — currently only
     * `TextAutoSpaceLineEdgeTrim` (the autospace replacement gap baked into a
     * Latin cluster's advance, removed again when the boundary lands on a
     * line edge). Applied unconditionally in [resolvedAdvance].
     */
    private val rawEdgeTrimByCluster: Map<Int, Float> = emptyMap(),
    /**
     * 行间注 避让 (ADR 0032): trailing advance ADDED to a base cluster so adjacent
     * 注文 keep ≥ one 注文 word-space between them (CLREQ §罗马拼音). STRUCTURAL —
     * applied unconditionally, BEFORE breaking, and survives the chain (so the
     * breaker + final render both see it). Distinct from justify deltas (those
     * are post-break and get replaced).
     */
    private val rubySpreadByCluster: Map<Int, Float> = emptyMap(),
    /** Structural inline box edges are never punctuation compression budget. */
    private val inlineBoxAdvanceByCluster: Map<Int, Float> = emptyMap(),
    /** Virtual prose-boundary glue physically owned by an attached run's trailing edge. */
    private val attachedInlineTrailingGlueByCluster: Map<Int, Float> = emptyMap(),
) {
    companion object {
        fun from(
            naturalClusters: List<Cluster>,
            punctuationAtoms: List<PunctuationAtom>,
            spacingPlan: PunctuationSpacingCompressionResult,
        ): PunctuationGeometryLedger {
            val geometries = buildPunctuationClusterGeometries(
                naturalClusters = naturalClusters,
                punctuationAtoms = punctuationAtoms,
            )
            val budgets = geometries.mapValues { (_, geometry) ->
                GlueBudget(
                    leadingNatural = geometry.leadingGlueNatural,
                    leadingConsumed = geometry.leadingGlueInitiallyConsumed,
                    trailingNatural = geometry.trailingGlueNatural,
                    trailingConsumed = geometry.trailingGlueInitiallyConsumed,
                )
            }
            return PunctuationGeometryLedger(
                naturalClusters = naturalClusters,
                geometries = geometries,
                budgets = budgets,
            ).consumeSpacing(spacingPlan)
        }

        private fun buildPunctuationClusterGeometries(
            naturalClusters: List<Cluster>,
            punctuationAtoms: List<PunctuationAtom>,
        ): Map<Int, PunctuationClusterGeometry> {
            if (punctuationAtoms.isEmpty()) return emptyMap()

            return naturalClusters.mapIndexedNotNull { index, cluster ->
                val atomsForCluster = punctuationAtoms.filter { it.range.isInside(cluster.range) }
                if (atomsForCluster.isEmpty()) return@mapIndexedNotNull null
                index to PunctuationClusterGeometry(
                    range = cluster.range,
                    sourceText = cluster.text,
                    displayText = cluster.displayText,
                    baseAdvance = cluster.advance,
                    bodyWidth = atomsForCluster.sumOf { it.bodyWidth.toDouble() }.toFloat(),
                    leadingGlueNatural = atomsForCluster.first().leadingGlue.natural,
                    trailingGlueNatural = atomsForCluster.last().trailingGlue.natural,
                    leadingGlueInitiallyConsumed = atomsForCluster.first().leadingGlueInitiallyConsumed,
                    trailingGlueInitiallyConsumed = atomsForCluster.last().trailingGlueInitiallyConsumed,
                    glyphInlineShift = atomsForCluster.singleOrNull()?.glyphInlineShift ?: 0f,
                    glyphPlacementReason = atomsForCluster.singleOrNull()?.glyphPlacementReason,
                    anchor = atomsForCluster.singleOrNull()?.anchor,
                    reason = atomsForCluster.first().geometrySource,
                )
            }.toMap()
        }
    }

    fun resolveClusters(): List<Cluster> =
        naturalClusters.mapIndexed { index, cluster ->
            val resolved = resolvedAdvance(index, cluster)
            val glyphInlineShift = geometries[index]?.glyphInlineShift ?: 0f
            if (resolved == cluster.advance && glyphInlineShift == 0f) {
                cluster
            } else {
                cluster.copy(
                    advance = resolved,
                    glyphInlineShift = cluster.glyphInlineShift + glyphInlineShift,
                )
            }
        }

    fun withInlineBoxAdvances(advanceByCluster: Map<Int, Float>): PunctuationGeometryLedger =
        if (advanceByCluster.isEmpty()) this else copy(inlineBoxAdvanceByCluster = advanceByCluster)

    fun consumeTrailingByCluster(consumptionByCluster: Map<Int, Float>): PunctuationGeometryLedger =
        copy(
            budgets = budgets.consume(consumptionByCluster) { budget, amount ->
                budget.copy(
                    trailingConsumed = (budget.trailingConsumed + amount)
                        .coerceAtMost(budget.trailingNatural),
                )
            },
        )

    fun consumeLeadingByCluster(consumptionByCluster: Map<Int, Float>): PunctuationGeometryLedger =
        copy(
            budgets = budgets.consume(consumptionByCluster) { budget, amount ->
                budget.copy(
                    leadingConsumed = (budget.leadingConsumed + amount)
                        .coerceAtMost(budget.leadingNatural),
                )
            },
        )

    /** Remaining leading/trailing glue per punctuation cluster index. */
    fun glueCapacities(): Map<Int, GlueCapacity> =
        budgets.mapNotNull { (index, budget) ->
            val leading = budget.leadingRemaining
            val trailing = budget.trailingRemaining
            if (leading > 0f || trailing > 0f) {
                index to GlueCapacity(
                    leading = leading,
                    trailing = trailing,
                    paired = geometries[index]?.anchor == PunctuationAnchor.Center,
                )
            } else {
                null
            }
        }.toMap()

    fun addJustificationDeltas(deltaByCluster: Map<Int, Float>): PunctuationGeometryLedger =
        copy(justificationDeltaByCluster = deltaByCluster)

    /** 行间注 避让 structural spread (ADR 0032) — applied before breaking, kept through the chain. */
    fun withRubySpread(spreadByCluster: Map<Int, Float>): PunctuationGeometryLedger =
        if (spreadByCluster.isEmpty()) this else copy(rubySpreadByCluster = spreadByCluster)

    fun withRawEdgeTrims(trimByCluster: Map<Int, Float>): PunctuationGeometryLedger =
        if (trimByCluster.isEmpty()) {
            this
        } else {
            copy(
                rawEdgeTrimByCluster = HashMap(rawEdgeTrimByCluster).apply {
                    trimByCluster.forEach { (index, amount) -> mergeValue(index, amount) { a, b -> a + b } }
                },
            )
        }

    /**
     * `AttachedInlineVirtualPunctuationBoundary`: ignore the attached run while
     * deciding punctuation spacing. Both sides are recomputed as if the prose
     * clusters were adjacent; this is not a transfer of the left-side glue.
     *
     * The right punctuation keeps as much of its own leading glue as the virtual
     * boundary needs. Any remainder is owned by the attached run's trailing edge.
     * At paragraph end the virtual boundary has zero width.
     */
    fun resolveAttachedInlinePunctuationBoundaries(
        inlineAttachments: List<InlineAttachment>,
        punctuationAtoms: List<PunctuationAtom>,
        em: Float,
    ): AttachedInlinePunctuationBoundaryResult {
        require(inlineAttachments.size == naturalClusters.size) {
            "Inline attachments must align with punctuation geometry clusters."
        }
        if (budgets.isEmpty() || inlineAttachments.none { it == InlineAttachment.Previous }) {
            return AttachedInlinePunctuationBoundaryResult(this, emptyMap(), emptyList())
        }

        val updatedBudgets = budgets.toMutableMap()
        val trailingGlue = mutableMapOf<Int, Float>()
        val decisions = mutableListOf<SpacingDecisionInfo>()
        resolveAttachedInlineVirtualBoundaries(inlineAttachments).forEach { boundary ->
            val previousIndex = boundary.previousClusterIndex
            val end = boundary.attachedClusterRange.last
            val previousBudget = updatedBudgets[previousIndex]
            val leftTrailing = previousBudget?.trailingRemaining ?: 0f
            val nextIndex = boundary.nextClusterIndex?.takeUnless {
                naturalClusters[it].fontKey == MANDATORY_BREAK_FONT_KEY &&
                    naturalClusters[it].displayText.isEmpty()
            }
            val nextBudget = nextIndex?.let(updatedBudgets::get)
            val rightLeading = nextBudget?.leadingRemaining ?: 0f
            val leftAtom = punctuationAtoms.lastOrNull { atom ->
                atom.range.isInside(naturalClusters[previousIndex].range)
            }
            val rightAtom = nextIndex?.let { index ->
                punctuationAtoms.firstOrNull { atom -> atom.range.isInside(naturalClusters[index].range) }
            }
            val nextChar = nextIndex?.let { naturalClusters[it].text.firstOrNull() }
            val naturalVirtualGlue = leftTrailing + rightLeading
            val adjustedVirtualGlue = when {
                nextIndex == null -> 0f
                leftAtom != null && rightAtom != null ->
                    (naturalVirtualGlue - em / 2f).coerceAtLeast(0f)

                leftAtom?.punctuationClass == PunctuationClass.Closing &&
                    nextChar?.let(ClreqPunctuationPolicies::isAsciiPointMark) == true ->
                    (naturalVirtualGlue - em / 2f).coerceAtLeast(0f)

                else -> naturalVirtualGlue
            }

            if (previousBudget != null && leftTrailing > 0f) {
                updatedBudgets[previousIndex] = previousBudget.copy(
                    trailingConsumed = previousBudget.trailingNatural,
                )
            }
            val keptRightLeading = minOf(rightLeading, adjustedVirtualGlue)
            if (nextIndex != null && nextBudget != null && keptRightLeading < rightLeading) {
                updatedBudgets[nextIndex] = nextBudget.copy(
                    leadingConsumed = nextBudget.leadingNatural - keptRightLeading,
                )
            }
            val targetGlue = (adjustedVirtualGlue - keptRightLeading).coerceAtLeast(0f)
            if (targetGlue > 0f) trailingGlue[end] = targetGlue

            if (leftTrailing > 0f || rightLeading != adjustedVirtualGlue) {
                val previous = naturalClusters[previousIndex]
                val next = nextIndex?.let(naturalClusters::get)
                decisions += SpacingDecisionInfo(
                    range = TextRange(previous.range.start, next?.range?.end ?: naturalClusters[end].range.end),
                    leftChar = previous.text.lastOrNull() ?: '\u0000',
                    rightChar = next?.text?.firstOrNull() ?: '\u0000',
                    naturalInnerGlue = naturalVirtualGlue,
                    adjustedInnerGlue = adjustedVirtualGlue,
                    reduction = naturalVirtualGlue - adjustedVirtualGlue,
                    reductionTargetRange = previous.range,
                    reason = when {
                        nextIndex == null -> "AttachedInlineVirtualPunctuationBoundary:line-end"
                        leftAtom != null && rightAtom != null ->
                            "AttachedInlineVirtualPunctuationBoundary:adjacent-punctuation"
                        leftAtom?.punctuationClass == PunctuationClass.Closing &&
                            nextChar?.let(ClreqPunctuationPolicies::isAsciiPointMark) == true ->
                            "AttachedInlineVirtualPunctuationBoundary:ascii-point-mark"
                        else -> "AttachedInlineVirtualPunctuationBoundary:natural"
                    },
                )
            }
        }

        val geometry = copy(
            budgets = updatedBudgets,
            attachedInlineTrailingGlueByCluster = HashMap(attachedInlineTrailingGlueByCluster).apply {
                trailingGlue.forEach { (cluster, amount) ->
                    mergeValue(cluster, amount) { a, b -> maxOf(a, b) }
                }
            },
        )
        return AttachedInlinePunctuationBoundaryResult(geometry, trailingGlue, decisions)
    }

    fun consumeLineEdgeGlue(
        lines: List<LineCandidate>,
        forceLineEndHalfWidth: Boolean = true,
    ): LineEdgeTrimResult {
        if (lines.isEmpty() || budgets.isEmpty()) {
            return LineEdgeTrimResult(this, emptyList())
        }

        val decisions = mutableListOf<LineEdgeTrimDecisionInfo>()
        val leadingConsumptionByCluster = HashMap<Int, Float>()
        val trailingConsumptionByCluster = HashMap<Int, Float>()

        fun consumeAtEdge(
            line: LineCandidate,
            clusterIndex: Int,
            edge: PunctuationLineEdge,
        ) {
            val budget = budgets[clusterIndex] ?: return
            // A one-cluster line reaches this helper twice. Subtract the first
            // edge's scheduled amount so a centred frame is consumed once.
            val leadingRemaining = (
                budget.leadingRemaining -
                    (leadingConsumptionByCluster[clusterIndex] ?: 0f)
                ).coerceAtLeast(0f)
            val trailingRemaining = (
                budget.trailingRemaining -
                    (trailingConsumptionByCluster[clusterIndex] ?: 0f)
                ).coerceAtLeast(0f)
            val paired = geometries[clusterIndex]?.anchor == PunctuationAnchor.Center
            val pairedPerSide = if (paired) minOf(leadingRemaining, trailingRemaining) else 0f
            val leadingAmount = when {
                paired -> pairedPerSide
                edge == PunctuationLineEdge.Start -> leadingRemaining
                else -> 0f
            }
            val trailingAmount = when {
                paired -> pairedPerSide
                edge == PunctuationLineEdge.End -> trailingRemaining
                else -> 0f
            }
            val total = leadingAmount + trailingAmount
            if (total <= 0f) return

            if (leadingAmount > 0f) {
                leadingConsumptionByCluster.mergeValue(clusterIndex, leadingAmount) { a, b -> a + b }
            }
            if (trailingAmount > 0f) {
                trailingConsumptionByCluster.mergeValue(clusterIndex, trailingAmount) { a, b -> a + b }
            }
            decisions += LineEdgeTrimDecisionInfo(
                lineRange = line.sourceRange,
                clusterRange = naturalClusters[clusterIndex].range,
                side = if (paired) "both" else edge.side,
                trimAmount = total,
                consumedBefore = if (paired) {
                    budget.leadingConsumed + budget.trailingConsumed
                } else if (edge == PunctuationLineEdge.Start) {
                    budget.leadingConsumed
                } else {
                    budget.trailingConsumed
                },
                naturalGlue = if (paired) {
                    budget.leadingNatural + budget.trailingNatural
                } else if (edge == PunctuationLineEdge.Start) {
                    budget.leadingNatural
                } else {
                    budget.trailingNatural
                },
                reason = if (paired) {
                    "Line${edge.reasonPart}CenteredPunctuationPairedCompression"
                } else {
                    "Line${edge.reasonPart}HalfWidthPunctuation"
                },
            )
        }

        lines.forEach { line ->
            if (line.clusterRange.isEmptyClusterRange()) return@forEach
            // 宽松风格 (AllowFullWidth): the unconditional line-end half-width
            // trim is skipped; the blank was only available as on-demand
            // shrink capacity during PushIn.
            if (forceLineEndHalfWidth) {
                consumeAtEdge(line, line.clusterRange.last, PunctuationLineEdge.End)
            }
            consumeAtEdge(line, line.clusterRange.first, PunctuationLineEdge.Start)
        }

        val updated = copy(
            budgets = budgets
                .consume(leadingConsumptionByCluster) { budget, amount ->
                    budget.copy(
                        leadingConsumed = (budget.leadingConsumed + amount)
                            .coerceAtMost(budget.leadingNatural),
                    )
                }
                .consume(trailingConsumptionByCluster) { budget, amount ->
                    budget.copy(
                        trailingConsumed = (budget.trailingConsumed + amount)
                            .coerceAtMost(budget.trailingNatural),
                    )
                },
        )
        return LineEdgeTrimResult(updated, decisions)
    }

    fun toDecisionInfo(): List<ClusterGeometryDecisionInfo> =
        geometries.map { (index, geometry) ->
            val budget = budgets.getValue(index)
            val delta = justificationDeltaByCluster[index] ?: 0f
            ClusterGeometryDecisionInfo(
                range = geometry.range,
                sourceText = geometry.sourceText,
                displayText = geometry.displayText,
                baseAdvance = geometry.baseAdvance,
                bodyWidth = geometry.bodyWidth,
                leadingGlueNatural = budget.leadingNatural,
                leadingGlueConsumed = budget.leadingConsumed,
                trailingGlueNatural = budget.trailingNatural,
                trailingGlueConsumed = budget.trailingConsumed,
                justificationDelta = delta,
                rubySpread = rubySpreadByCluster[index] ?: 0f,
                glyphInlineShift = geometry.glyphInlineShift,
                glyphPlacementReason = geometry.glyphPlacementReason,
                resolvedAdvance = resolvedAdvance(index, naturalClusters[index]),
                source = "PunctuationGeometryLedger",
                reason = geometry.reason,
            )
        }

    private fun consumeSpacing(
        spacingPlan: PunctuationSpacingCompressionResult,
    ): PunctuationGeometryLedger =
        copy(
            budgets = budgets.consumeByRange(
                clusters = naturalClusters,
                geometries = geometries,
                adjustments = spacingPlan.adjustments,
            ),
        )

    private fun resolvedAdvance(index: Int, cluster: Cluster): Float {
        val rawTrim = rawEdgeTrimByCluster[index] ?: 0f
        val spread = rubySpreadByCluster[index] ?: 0f
        val geometry = geometries[index] ?: run {
            val delta = justificationDeltaByCluster[index] ?: 0f
            val attachedGlue = attachedInlineTrailingGlueByCluster[index] ?: 0f
            return (cluster.advance + delta + spread + attachedGlue - rawTrim).coerceAtLeast(0f)
        }
        val inlineBoxAdvance = inlineBoxAdvanceByCluster[index] ?: 0f
        val budget = budgets[index]
            ?: return (
                geometry.bodyWidth + inlineBoxAdvance +
                    (justificationDeltaByCluster[index] ?: 0f) + spread - rawTrim
                ).coerceAtLeast(0f)
        val delta = justificationDeltaByCluster[index] ?: 0f
        val attachedGlue = attachedInlineTrailingGlueByCluster[index] ?: 0f
        return (
            geometry.bodyWidth +
                budget.leadingRemaining +
                budget.trailingRemaining +
                delta +
                spread -
                rawTrim +
                inlineBoxAdvance +
                attachedGlue
            ).coerceAtLeast(0f)
    }
}

internal data class AttachedInlinePunctuationBoundaryResult(
    val geometry: PunctuationGeometryLedger,
    val trailingGlueByCluster: Map<Int, Float>,
    val decisions: List<SpacingDecisionInfo>,
)

internal data class PunctuationClusterGeometry(
    val range: TextRange,
    val sourceText: String,
    val displayText: String,
    val baseAdvance: Float,
    val bodyWidth: Float,
    val leadingGlueNatural: Float,
    val trailingGlueNatural: Float,
    val leadingGlueInitiallyConsumed: Float,
    val trailingGlueInitiallyConsumed: Float,
    val glyphInlineShift: Float,
    val glyphPlacementReason: String?,
    val anchor: PunctuationAnchor?,
    val reason: String,
)

internal data class GlueBudget(
    val leadingNatural: Float,
    val leadingConsumed: Float,
    val trailingNatural: Float,
    val trailingConsumed: Float,
) {
    val leadingRemaining: Float get() = (leadingNatural - leadingConsumed).coerceAtLeast(0f)
    val trailingRemaining: Float get() = (trailingNatural - trailingConsumed).coerceAtLeast(0f)
}

internal data class LineEdgeTrimResult(
    val geometry: PunctuationGeometryLedger,
    val decisions: List<LineEdgeTrimDecisionInfo>,
)

private enum class PunctuationLineEdge(
    val side: String,
    val reasonPart: String,
) {
    Start(side = "leading", reasonPart = "Start"),
    End(side = "trailing", reasonPart = "End"),
}

/** Remaining glue per side, input to the tiered shrink model (ADR 0020). */
internal data class GlueCapacity(
    val leading: Float,
    val trailing: Float,
    /** True when resolved punctuation geometry selected a centred body frame. */
    val paired: Boolean,
)

/**
 * Contiguous cluster-index range whose clusters are fully covered by
 * [sourceRange]; null when no cluster is covered.
 */
internal fun List<Cluster>.clusterIndexRangeFor(sourceRange: TextRange): IntRange? {
    var first = -1
    var last = -1
    forEachIndexed { idx, cluster ->
        if (cluster.range.start >= sourceRange.start && cluster.range.end <= sourceRange.end) {
            if (first == -1) first = idx
            last = idx
        }
    }
    return if (first == -1) null else first..last
}

private fun Map<Int, GlueBudget>.consume(
    consumptionByCluster: Map<Int, Float>,
    apply: (GlueBudget, Float) -> GlueBudget,
): Map<Int, GlueBudget> {
    if (consumptionByCluster.isEmpty()) return this

    return toMutableMap().also { updated ->
        consumptionByCluster.forEach { (index, amount) ->
            if (amount <= 0f) return@forEach
            updated[index]?.let { budget -> updated[index] = apply(budget, amount) }
        }
    }
}

private fun Map<Int, GlueBudget>.consumeByRange(
    clusters: List<Cluster>,
    geometries: Map<Int, PunctuationClusterGeometry>,
    adjustments: List<PunctuationSpacingAdjustment>,
): Map<Int, GlueBudget> {
    if (adjustments.isEmpty()) return this

    return toMutableMap().also { updated ->
        adjustments.forEach { adjustment ->
            val targetIdx = clusters.indexOfFirst { adjustment.reductionTargetRange.isInside(it.range) }
            if (targetIdx < 0) return@forEach
            updated[targetIdx]?.let { current ->
                val leadingRemaining = current.leadingRemaining
                val trailingRemaining = current.trailingRemaining
                if (
                    geometries[targetIdx]?.anchor == PunctuationAnchor.Center
                ) {
                    val perSide = minOf(
                        adjustment.reduction / 2f,
                        leadingRemaining,
                        trailingRemaining,
                    )
                    updated[targetIdx] = current.copy(
                        leadingConsumed = current.leadingConsumed + perSide,
                        trailingConsumed = current.trailingConsumed + perSide,
                    )
                    return@let
                }
                // Consume reduction from whichever side has remaining capacity.
                // With single-sided font geometry or the profile fallback, all
                // glue may be on one side (e.g. PauseOrStop → trailing only).
                updated[targetIdx] = if (trailingRemaining >= leadingRemaining) {
                    current.copy(
                        trailingConsumed = (current.trailingConsumed + adjustment.reduction)
                            .coerceAtMost(current.trailingNatural),
                    )
                } else {
                    current.copy(
                        leadingConsumed = (current.leadingConsumed + adjustment.reduction)
                            .coerceAtMost(current.leadingNatural),
                    )
                }
            }
        }
    }
}

internal fun TextRange.isInside(other: TextRange): Boolean =
    start >= other.start && end <= other.end

/**
 * Common-stdlib port of `java.util.Map.merge` (absent from the JS common
 * stdlib and only present on Android from API 24): absent key → [value];
 * present → `remap(old, value)`. The distinct name prevents the JVM member
 * from winning overload resolution in API 23 artifacts.
 */
internal fun <K, V : Any> MutableMap<K, V>.mergeValue(key: K, value: V, remap: (V, V) -> V) {
    this[key] = this[key]?.let { remap(it, value) } ?: value
}
