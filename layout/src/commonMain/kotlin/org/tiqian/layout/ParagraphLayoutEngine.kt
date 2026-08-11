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
import org.tiqian.core.InlineBoxDecisionInfo
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

private val COMBINING_MARK_CATEGORIES = setOf(
    CharCategory.NON_SPACING_MARK,
    CharCategory.COMBINING_SPACING_MARK,
    CharCategory.ENCLOSING_MARK,
)

/** Monotonic interval join for source-ordered clusters and source-ordered decisions. */
private fun <T> List<Cluster>.containingItems(
    items: List<T>,
    rangeOf: (T) -> TextRange,
): List<T?> {
    var itemIndex = 0
    return map { cluster ->
        while (itemIndex < items.size && rangeOf(items[itemIndex]).end <= cluster.range.start) {
            itemIndex += 1
        }
        items.getOrNull(itemIndex)?.takeIf { item ->
            val range = rangeOf(item)
            cluster.range.start >= range.start && cluster.range.end <= range.end
        }
    }
}

/** Monotonic inverse interval join when one cluster may contain several ordered items. */
private fun <T> List<Cluster>.firstContainedItem(
    items: List<T>,
    rangeOf: (T) -> TextRange,
): List<T?> {
    var itemIndex = 0
    return map { cluster ->
        while (itemIndex < items.size && rangeOf(items[itemIndex]).end <= cluster.range.start) {
            itemIndex += 1
        }
        items.getOrNull(itemIndex)?.takeIf { item ->
            val range = rangeOf(item)
            range.start >= cluster.range.start && range.end <= cluster.range.end
        }
    }
}

interface ParagraphLayoutEngine {
    fun layout(input: LayoutInput): LayoutResult
}

class ExplainableStubParagraphLayoutEngine(
    private val fontRoleClassifier: FontRoleClassifier = CjkFontRoleClassifier(),
    private val fallbackResolver: FallbackResolver = PreferCjkForAmbiguousPunctuationResolver(),
    private val clreqProfileResolver: ClreqProfileResolver = BuiltInClreqProfileResolver,
    private val fontMetricsResolver: FontMetricsResolver = StubFontMetricsResolver(),
    private val fontMetricsNormalizer: FontMetricsNormalizer = ScriptAwareFontMetricsNormalizer(),
    private val punctuationAtomBuilder: PunctuationAtomBuilder = PunctuationAtomBuilder(),
    private val punctuationSpacingCompressor: PunctuationSpacingCompressor = PunctuationSpacingCompressor(),
    private val quotePairAnalyzer: QuotePairAnalyzer = QuotePairAnalyzer(),
    private val lineBreaker: LineBreaker = GreedyLineBreaker(),
    private val justifier: Justifier = Justifier(),
    private val textShaper: TextShaper = ExplainableStubTextShaper(),
    /**
     * Western syllable hyphenation source (CLREQ「可使用连字符处」). Defaults to
     * the platform hyphenator ([defaultHyphenator]: en-US on JVM) so
     * `LineEndHangingHyphen` is ON by default; pass [NoHyphenator] to opt out.
     * (`LatinForcedHyphenBreak` over-long hard-break fires regardless.)
     */
    private val hyphenator: Hyphenator = defaultHyphenator(),
) : ParagraphLayoutEngine {
    override fun layout(input: LayoutInput): LayoutResult {
        val text = input.content.text
        val fontSize = input.textStyle.fontSize
        require(input.paragraphStyle.emphasisDotGapEm.isFinite() && input.paragraphStyle.emphasisDotGapEm >= 0f) {
            "ParagraphStyle.emphasisDotGapEm must be finite and non-negative"
        }
        require(
            input.paragraphStyle.inlineObjectMinimumClearanceEm.isFinite() &&
                input.paragraphStyle.inlineObjectMinimumClearanceEm >= 0f,
        ) {
            "ParagraphStyle.inlineObjectMinimumClearanceEm must be finite and non-negative"
        }
        input.inlineBoxes.forEach { inlineBox ->
            require(
                inlineBox.range.start >= 0 &&
                    inlineBox.range.start < inlineBox.range.end &&
                    inlineBox.range.end <= text.length,
            ) {
                "InlineBoxSpan ${inlineBox.range} must be a non-empty source range"
            }
            require(inlineBox.inlineStart.isFinite() && inlineBox.inlineEnd.isFinite()) {
                "InlineBoxSpan ${inlineBox.range} must have finite inline edges"
            }
        }
        require(input.inlineObjects.distinctBy { it.range }.size == input.inlineObjects.size) {
            "InlineObjectSpan ranges must be unique"
        }
        input.inlineObjects.sortedBy { it.range.start }.zipWithNext().forEach { (previous, next) ->
            require(previous.range.end <= next.range.start) {
                "InlineObjectSpan ranges must not overlap: ${previous.range} and ${next.range}"
            }
        }
        input.inlineObjects.forEach { inlineObject ->
            require(
                inlineObject.range.start >= 0 &&
                    inlineObject.range.start < inlineObject.range.end &&
                    inlineObject.range.end <= text.length,
            ) {
                "InlineObjectSpan ${inlineObject.range} must cover a non-empty source range"
            }
            require(
                inlineObject.advance.isFinite() && inlineObject.advance > 0f &&
                    inlineObject.ascent.isFinite() && inlineObject.ascent >= 0f &&
                    inlineObject.descent.isFinite() && inlineObject.descent >= 0f,
            ) {
                "InlineObjectSpan ${inlineObject.range} must have finite positive geometry"
            }
            require(inlineObject.leadingBoundary.shrinkCapacity == 0f) {
                "InlineObjectSpan ${inlineObject.range} cannot shrink its leading boundary"
            }
            require(inlineObject.leadingBoundary.lineEndDiscardableAdvance == 0f) {
                "InlineObjectSpan ${inlineObject.range} cannot discard advance at its leading boundary"
            }
            require(inlineObject.trailingBoundary.shrinkCapacity <= inlineObject.advance) {
                "InlineObjectSpan ${inlineObject.range} trailing shrink capacity must not exceed its advance"
            }
            require(inlineObject.trailingBoundary.lineEndDiscardableAdvance <= inlineObject.advance) {
                "InlineObjectSpan ${inlineObject.range} trailing line-end discard must not exceed its advance"
            }
        }
        val inlineObjectByRange = input.inlineObjects.associateBy { it.range }
        // Rich-text per-span style (ADR 0030 B 档): a cluster covered by a span
        // SHAPES at that span's size + weight + slant, and is MEASURED at its
        // size; the paragraph base still owns the structural em decisions (grid /
        // 段首缩进) per the mixed-size 归属 rule. The boundary em decisions
        // (中西间距、标点 glue) stay at base for now — per-owner is the follow-up.
        // Each span's TextStyle is the FULLY-RESOLVED style (base + overrides),
        // so unset fields already equal base.
        val sizedSpans = input.content.spans.filter { it.range.start < it.range.end }
        fun styleAt(offset: Int) =
            sizedSpans.lastOrNull { offset >= it.range.start && offset < it.range.end }?.style ?: input.textStyle
        fun fontSizeAt(offset: Int): Float = styleAt(offset).fontSize
        // BilingualEmphasisWesternItalic (ADR 0030/着重号惯例): 着重号 marks Han with
        // dots, but Western emphasis is ITALIC, not dots. A Latin run inside an
        // Emphasis span shapes italic (and gets no dot — see computeDecorationDecisions).
        val emphasisRanges = input.decorations.filter { it.kind == DecorationKind.Emphasis }.map { it.range }
        fun emphasisItalicAt(offset: Int): Boolean =
            emphasisRanges.any { offset >= it.start && offset < it.end }
        // 行间注 (ruby, ADR 0032):注文 first uses the existing inter-line area.
        // Only a measured deficit expands line boxes according to rubyLineHeightMode;
        // advance is still handled by 避让 before breaking.
        val rubyFontSize = fontSize * RUBY_FONT_EM
        val rubyStackGap = fontSize * RUBY_STACK_GAP_EM
        // `RubyLegibilityWeightBoost`: small above-base pinyin stays one weight
        // step heavier than the base; Android gallery shows this is enough.
        val rubyFontWeight = (input.textStyle.fontWeight + RUBY_FONT_WEIGHT_BOOST).coerceIn(1, 900)
        // `BopomofoLegibilityWeightBoost`: right-side ㄅㄆㄇ is smaller and thinner
        // on Android CJK fonts, so it gets a stronger boost than pinyin ruby.
        // It follows the annotated base glyph's effective weight, not only the
        // paragraph default: a bold base carries bold 注音 too.
        fun bopomofoFontWeightAt(offset: Int): Int =
            (styleAt(offset).fontWeight + BOPOMOFO_FONT_WEIGHT_BOOST).coerceIn(1, 900)
        // 拼音 (above-base) ruby only; 注音 (RubyKind.Bopomofo, right-side) is parsed
        // separately below because its geometry/advance/weight follow ADR 0033.
        val pinyinSpans = input.rubySpans.filter { it.kind == RubyKind.Pinyin }
        // SourceRangeBoundaryClusterSplit: span / annotation edges force cluster
        // splits so no cluster straddles a source range whose geometry is later
        // queried. Without this, a link ending before a trailing period in
        // `template.` would be sliced by UTF-16 ratio rather than by real cluster
        // advance, visibly shortening its underline.
        val spanBoundaries: Set<Int> = buildSet {
            fun addBoundary(offset: Int) {
                if (offset > 0 && offset < text.length) add(offset)
            }
            fun addRange(range: TextRange) {
                addBoundary(range.start)
                addBoundary(range.end)
            }
            sizedSpans.forEach { addRange(it.range) }
            input.decorations.forEach { addRange(it.range) }
            input.rubySpans.forEach { addRange(it.baseRange) }
            input.inlineBoxes.forEach { addRange(it.range) }
            input.inlineObjects.forEach { addRange(it.range) }
            input.content.sourceBoundaries.forEach(::addBoundary)
        }
        val clreqProfile = clreqProfileResolver.resolve(input.profileId)
        val context = FontRoleContext(
            locale = input.textStyle.locale,
            regionHint = clreqProfile.region.name,
        )
        val punctuationGlyphSubstitutor = ClreqPunctuationGlyphSubstitutor(
            policy = clreqProfile.punctuationGlyphPolicy,
        )

        // LineLengthGridQuantization (grid-first, ADR 0028): floor the
        // container to an integer number of 字 (N×fontSize) so the body lands
        // on the grid; the sub-字 leftover places the whole body within the
        // container by bodyAlignment. Bypassable for known-exact widths.
        // Computed up front because LatinForcedHyphenBreak (below) needs the
        // measure to decide which Latin pieces can never fit on a line.
        val grid = input.paragraphStyle.lineLengthGrid
        val containerWidth = input.constraints.maxWidth
        val gridCells = floor(containerWidth / fontSize).toInt().coerceAtLeast(1)
        val measure = if (grid.enabled) {
            (gridCells * fontSize).coerceAtMost(containerWidth)
        } else {
            containerWidth
        }
        val gridSlack = containerWidth - measure
        val gridBodyAlignment = grid.bodyAlignment ?: input.paragraphStyle.lastLineAlignment
        val gridBodyOffset = if (!grid.enabled) {
            0f
        } else {
            when (gridBodyAlignment) {
                LastLineAlignment.Start -> 0f
                LastLineAlignment.Center -> gridSlack / 2f
                LastLineAlignment.End -> gridSlack
            }
        }
        val lineLengthGridDecision = LineLengthGridDecisionInfo(
            enabled = grid.enabled,
            containerWidth = containerWidth,
            fontSize = fontSize,
            cells = if (grid.enabled) gridCells else (measure / fontSize).toInt(),
            measure = measure,
            slack = gridSlack,
            bodyAlignment = gridBodyAlignment.name,
            bodyOffset = gridBodyOffset,
            reason = if (grid.enabled) "LineLengthGridQuantization" else "GridBypassed",
        )
        val measureEm = measure / fontSize

        val quotePairs = quotePairAnalyzer.analyze(text)
        val quoteRoleDecisions = quotePairAnalyzer.classifyQuoteRoles(text, quotePairs, fontRoleClassifier, context)
        val quoteRoleOverrides = quoteRoleDecisions.associate { it.index to it.role }
        val roleOverrideInfos = quoteRoleDecisions.toRoleOverrideInfos(
            text = text,
            baseClassifier = fontRoleClassifier,
            context = context,
        )
        val effectiveClassifier: FontRoleClassifier = if (quoteRoleOverrides.isNotEmpty()) {
            QuotePairAwareFontRoleClassifier(fontRoleClassifier, quoteRoleOverrides)
        } else {
            fontRoleClassifier
        }

        val clusterRanges = clusterRoleRanges(
            text,
            effectiveClassifier,
            context,
            clreqProfile,
            spanBoundaries,
            input.inlineObjects.associateBy { it.range.start },
        )
        val shapeableRanges = clusterRanges.filterNot {
            it.mandatoryBreak || it.zeroWidthSoftBreak || inlineObjectByRange.containsKey(it.range)
        }
        val fontDecisions = shapeableRanges.map { resolvedRange ->
            fallbackResolver.resolve(
                text = text,
                range = resolvedRange.range,
                request = FontRequest(
                    preferredFamilies = input.textStyle.fontFamilies,
                    locale = input.textStyle.locale,
                    role = resolvedRange.role,
                ),
            )
        }
        val fontDecisionByRange = shapeableRanges.zip(fontDecisions).associate { (resolved, decision) ->
            resolved.range to decision
        }

        // A CLREQ display substitution (ADR 0003, e.g. `——` → `⸺`) is only an
        // improvement if the resolved font can actually DRAW it well; otherwise
        // re-shape with the source text and record the rollback + its cause:
        // - SubstitutionRollbackOnMissingGlyph: the font lacks the codepoint —
        //   `⸺` U+2E3A is absent from PingFang SC / Hiragino / Heiti (tofu).
        // - DashSubstitutionInkCoverageRollback: the font HAS `⸺` but its ink
        //   does not fill the two-em advance (Pixel's Noto CJK carries a
        //   ~1.6em-ink glyph left-aligned in a 2em advance → a ~0.35em hole
        //   against the next character). The source `——` tiles two full-width
        //   em dashes instead. Only judged when the shaper reports ink bounds;
        //   stub/AWT (no ink) keep the substitution.
        val substitutionRollbacks = mutableMapOf<TextRange, String>()
        fun ShapingResult.dashInkCoverageDeficient(displayText: String, segmentFontSize: Float): Boolean {
            if (!displayText.contains('\u2E3A')) return false
            val glyph = glyphRuns.flatMap { it.glyphs }.singleOrNull() ?: return false
            val ink = glyph.bounds ?: return false
            // `DashSubstitutionTwoEmInkCoverage`: compare with the CLREQ
            // target box, not the browser/font fallback's reported advance.
            // A missing U+2E3A commonly falls back to a perfectly filled 1em
            // glyph; dividing by that wrong 1em advance made the fallback look
            // valid and silently shrank a Chinese dash by half.
            val targetAdvance = DASH_SUBSTITUTION_TARGET_EM * segmentFontSize
            return (ink.right - ink.left) < targetAdvance * DASH_SUBSTITUTION_MIN_INK_COVERAGE
        }
        fun shapeSegment(decision: FontDecision, segmentRange: TextRange): ShapingResult {
            val sourceText = text.substring(segmentRange.start, segmentRange.end)
            val substitution = punctuationGlyphSubstitutor.substitute(sourceText)
            val baseSegmentStyle = styleAt(segmentRange.start)
            val segmentStyle = if (decision.role == FontRole.LatinText && emphasisItalicAt(segmentRange.start)) {
                baseSegmentStyle.copy(italic = true)
            } else {
                baseSegmentStyle
            }
            val shaped = textShaper.shape(
                ShapingInput(
                    text = text,
                    range = segmentRange,
                    style = segmentStyle,
                    fontDecision = decision,
                    displayText = substitution.displayText,
                    openTypeFeatures = cjkPunctuationFullWidthFeatures(
                        role = decision.role,
                        displayText = substitution.displayText,
                    ),
                ),
            )
            val rollbackCause = when {
                substitution.displayText == sourceText -> null
                shaped.decisions.any {
                    it.capabilityIssue == UNVERIFIED_DISPLAY_SUBSTITUTION_COVERAGE_ISSUE
                } -> "SubstitutionRollbackOnUnverifiedGlyphCoverage"
                shaped.decisions.any { it.missingGlyphs > 0 } -> "SubstitutionRollbackOnMissingGlyph"
                shaped.dashInkCoverageDeficient(substitution.displayText, segmentStyle.fontSize) ->
                    "DashSubstitutionInkCoverageRollback"
                else -> null
            }
            return if (rollbackCause == null) {
                shaped
            } else {
                substitutionRollbacks[segmentRange] = rollbackCause
                textShaper.shape(
                    ShapingInput(
                        text = text,
                        range = segmentRange,
                        style = segmentStyle,
                        fontDecision = decision,
                        displayText = sourceText,
                        openTypeFeatures = cjkPunctuationFullWidthFeatures(
                            role = decision.role,
                            displayText = sourceText,
                        ),
                    ),
                )
            }
        }
        fun shapeSegmentWithPointMarkPrefix(
            decision: FontDecision,
            segmentRange: TextRange,
        ): List<ShapingResult> {
            var prefixEnd = segmentRange.start
            while (
                prefixEnd < segmentRange.end &&
                ClreqPunctuationPolicies.isAsciiPointMark(text[prefixEnd])
            ) {
                prefixEnd += 1
            }
            return if (prefixEnd in (segmentRange.start + 1) until segmentRange.end) {
                // `PostCutAsciiPointMarkPrefixSegmentation`: opaque-token and
                // hard-cut passes can create a fresh `,A` piece after the
                // initial role segmentation. Re-shape its point-mark prefix
                // separately so kinsoku binds only the comma, not its suffix.
                listOf(
                    shapeSegment(decision, TextRange(segmentRange.start, prefixEnd)),
                    shapeSegment(decision, TextRange(prefixEnd, segmentRange.end)),
                )
            } else {
                listOf(shapeSegment(decision, segmentRange))
            }
        }
        // LatinWordSegmentation (gap audit 缺口 2): Latin runs are shaped per
        // word/space segment so each word and each space run becomes its own
        // cluster — line breaks happen at word boundaries, word spaces become
        // first-class adjustable clusters (CLREQ 西文词距). Cross-segment
        // kerning at a space boundary is negligible.
        //
        // LineEndHangingHyphen (CLREQ §换行与断词连字「可使用连字符处」, ADR 0029):
        // an all-letter Latin word is additionally split so the breaker may wrap
        // it. A break at one of these offsets earns a displayed trailing hyphen;
        // later geometry reserves that hyphen inside the measure when possible
        // and hangs only the residual that cannot fit. `hyphenOffsets` are the
        // absolute source offsets where the next line may continue the word.
        //
        // Cut points are (a) the [hyphenator]'s syllable points, plus (b)
        // `LatinForcedHyphenBreak`: for any word piece STILL wider than the
        // measure (hyphenation off, or a syllable/token that can't fit),
        // character-level fallback cuts that hard-break it — preferring 前二后三
        // (2/3) within the piece, breaking anywhere only when that can't be met
        // (满足不了就算了).
        //
        // `LatinStructuralSolidusBreak`: a solidus inside a Latin token
        // (`TeX/LaTeX`) is a clean separator boundary even when the whole token
        // would fit a fresh line. The slash stays with the previous piece, so
        // breaks read `TeX/` + `LaTeX`, never `/LaTeX`.
        //
        // `LatinOpaqueTokenBreak`: URL-like / identifier-like Latin tokens are
        // not words. They get clean breaks at ASCII separators; if a remaining
        // piece is still over-wide, it hard-breaks at character boundaries with
        // NO synthetic hyphen. This keeps links copy-faithful and avoids
        // inventing hyphens inside hashes, query strings, or mixed alpha/digit ids.
        //
        // `LatinLongUnhyphenatedLetterTokenBreak`: a very long all-letter run,
        // or a very long hyphenator-unexplained piece inside one, is also
        // opaque, not an English word. This covers pure-letter base64/hash
        // fragments and synthetic strings such as `ssss...herstory`; it uses
        // the same no-hyphen hard cuts.
        val hyphenOffsets = mutableSetOf<Int>()
        var hyphenAdvanceOrNull: Float? = null
        var hyphenGlyphs: List<Glyph> = emptyList()
        fun latinWordCuts(
            decision: FontDecision,
            wordRange: TextRange,
            syllable: List<Int>,
        ): List<Int> {
            val cuts = mutableSetOf<Int>()
            cuts += syllable.map { wordRange.start + it }
            val relBounds = (listOf(0) + syllable + listOf(wordRange.length)).distinct()
            for (i in 0 until relBounds.size - 1) {
                val a = relBounds[i]
                val b = relBounds[i + 1]
                val pieceAdvance = shapeSegment(decision, TextRange(wordRange.start + a, wordRange.start + b))
                    .clusters.singleOrNull()?.advance ?: 0f
                if (pieceAdvance <= measure) continue
                val lo = a + HYPHEN_MIN_LEFT
                val hi = b - HYPHEN_MIN_RIGHT
                val range = if (lo <= hi) lo..hi else (a + 1) until b
                for (off in range) cuts += wordRange.start + off
            }
            return cuts.sorted()
        }
        // ExistingHyphenBreak (CY/T 154-2017 §9.3): a hyphenated compound breaks
        // AT its existing hyphens — no NEW hyphen added, the existing one sits at
        // the line end. Keeps ≥2 letters on each side (§9.4「不要把单个字母放在
        // 一行的行末或行首」), which also leaves number ranges / abbreviation-number
        // tokens (3-4, COVID-19) unbroken. These are clean break boundaries, not
        // synthetic-hyphen points, so they never enter `hyphenOffsets`.
        fun existingHyphenCuts(wordRange: TextRange): List<Int> {
            val w = text.substring(wordRange.start, wordRange.end)
            val cuts = mutableListOf<Int>()
            for (i in w.indices) {
                if (w[i] != '-') continue
                var before = 0
                var j = i - 1
                while (j >= 0 && w[j].isLetter()) { before += 1; j -= 1 }
                var after = 0
                var k = i + 1
                while (k < w.length && w[k].isLetter()) { after += 1; k += 1 }
                if (before >= 2 && after >= 2) cuts += wordRange.start + i + 1
            }
            return cuts
        }
        // CamelCaseBreak: a camelCase/PascalCase product token (internal capital)
        // breaks at its humps — lowercase→uppercase, or an acronym boundary
        // Upper→Upper-then-lower (XML|Http) — with NO hyphen (the capital signals
        // the break). ≥2 letters each side (§9.4). Clean breaks, not hyphenOffsets.
        fun camelCaseCuts(wordRange: TextRange): List<Int> {
            val w = text.substring(wordRange.start, wordRange.end)
            val humps = (1 until w.length).filter { i ->
                w[i].isUpperCase() && (
                    w[i - 1].isLowerCase() ||
                        (w[i - 1].isUpperCase() && i + 1 < w.length && w[i + 1].isLowerCase())
                    )
            }
            val bounds = listOf(0) + humps + listOf(w.length)
            return humps.filter { h ->
                h - bounds.last { it < h } >= 2 && bounds.first { it > h } - h >= 2
            }.map { wordRange.start + it }
        }
        val breakOpportunityDecisions = mutableListOf<BreakOpportunityDecisionInfo>()
        fun latinSeparatorCuts(
            tokenRange: TextRange,
            tokenAdvance: Float,
            forceOpaqueBreaks: Boolean,
        ): List<Int> {
            val token = text.substring(tokenRange.start, tokenRange.end)
            val urlLike = token.isUrlLikeLatinToken()
            val opaque = token.any { !it.isLetter() }
            val structuralSolidus = token.hasBreakableLatinSolidus()
            val bibliographicLocatorCuts = token.bibliographicNumericLocatorBreakOffsets()
            val opaqueSeparatorMode = urlLike || (opaque && (tokenAdvance > measure || forceOpaqueBreaks))
            if (!structuralSolidus && !opaqueSeparatorMode && bibliographicLocatorCuts.isEmpty()) {
                return emptyList()
            }
            val cuts = bibliographicLocatorCuts
                .mapTo(mutableListOf()) { tokenRange.start + it }
            if (bibliographicLocatorCuts.isNotEmpty()) {
                breakOpportunityDecisions += BreakOpportunityDecisionInfo(
                    range = tokenRange,
                    sourceText = token,
                    breakOffsets = cuts.toList(),
                    reason = "BibliographicNumericLocatorBreak",
                )
            }
            for (i in 0 until token.lastIndex) {
                val breakAfter = (structuralSolidus && !urlLike && token[i] == '/') ||
                    (opaqueSeparatorMode && token.isLatinTokenBreakAfter(i, tokenAdvance <= measure))
                if (breakAfter) cuts += tokenRange.start + i + 1
            }
            return cuts
        }
        fun latinOpaqueHardCuts(
            decision: FontDecision,
            tokenRange: TextRange,
            cleanCuts: List<Int>,
            forceOpaqueBreaks: Boolean,
        ): List<Int> {
            val relBounds = (listOf(0) + cleanCuts.map { it - tokenRange.start } + listOf(tokenRange.length))
                .distinct()
                .sorted()
            val cuts = mutableSetOf<Int>()
            for (i in 0 until relBounds.size - 1) {
                val a = relBounds[i]
                val b = relBounds[i + 1]
                if (b - a <= 1) continue
                val pieceAdvance = shapeSegment(decision, TextRange(tokenRange.start + a, tokenRange.start + b))
                    .clusters.singleOrNull()?.advance ?: 0f
                if (pieceAdvance <= measure && !(forceOpaqueBreaks && b - a >= LATIN_OPAQUE_TOKEN_MIN_LENGTH)) {
                    continue
                }
                for (off in (a + 1) until b) cuts += tokenRange.start + off
            }
            return cuts.sorted()
        }
        val shapingResults = clusterRanges.flatMap { resolvedRange ->
            inlineObjectByRange[resolvedRange.range]?.let { inlineObject ->
                return@flatMap listOf(inlineObjectShapingResult(text, inlineObject))
            }
            if (resolvedRange.mandatoryBreak) {
                return@flatMap listOf(mandatoryBreakShapingResult(text, resolvedRange.range))
            }
            if (resolvedRange.zeroWidthSoftBreak) {
                return@flatMap listOf(zeroWidthSoftBreakShapingResult(text, resolvedRange.range))
            }
            val decision = fontDecisionByRange.getValue(resolvedRange.range)
            decision.shapingSegments(text).flatMap { segmentRange ->
                val shaped = shapeSegment(decision, segmentRange)
                val isLatin = decision.role == FontRole.LatinText && segmentRange.length > 0
                val w = if (isLatin) text.substring(segmentRange.start, segmentRange.end) else ""
                val allLetters = isLatin && w.all { it.isLetter() }
                // §9.4 全大写缩写不断词；驼峰式在驼峰处断（无连字符）；含 '-' 在
                // 已有连字符处断（§9.3，无新连字符）。以上都是 clean 断点（不进
                // hyphenOffsets）。其余全字母词走 §9.2 音节 + 硬断（加合成连字符）。
                val isAllCaps = allLetters && w.length >= 2 && w.none { it.isLowerCase() }
                val isAbbreviation = isAllCaps && w.length < LATIN_OPAQUE_TOKEN_MIN_LENGTH
                val isCamelCase = allLetters && !isAllCaps && !isAbbreviation &&
                    (1 until w.length).any { w[it].isUpperCase() }
                val tokenAdvance = shaped.clusters.sumOf { it.advance.toDouble() }.toFloat()
                val syllableCuts = if (
                    allLetters && !isAbbreviation && !isCamelCase && !w.contains('-')
                ) {
                    hyphenator.hyphenate(w).distinct().sorted()
                } else {
                    emptyList()
                }
                val longestUnhyphenatedLetterPiece = if (allLetters) {
                    val bounds = (listOf(0) + syllableCuts + listOf(w.length)).distinct().sorted()
                    bounds.zipWithNext().maxOfOrNull { (a, b) -> b - a } ?: w.length
                } else {
                    0
                }
                val isLongUnhyphenatedLetterToken =
                    allLetters && !isAbbreviation && !isCamelCase &&
                        longestUnhyphenatedLetterPiece >= LATIN_OPAQUE_TOKEN_MIN_LENGTH
                val isLongOpaqueLatinToken =
                    isLongUnhyphenatedLetterToken || (isLatin && !allLetters && w.length >= LATIN_OPAQUE_TOKEN_MIN_LENGTH)
                val cleanCuts = when {
                    !isLatin -> emptyList()
                    w.contains('-') -> existingHyphenCuts(segmentRange) +
                        latinSeparatorCuts(segmentRange, tokenAdvance, isLongOpaqueLatinToken)
                    isCamelCase -> camelCaseCuts(segmentRange)
                    !allLetters -> latinSeparatorCuts(segmentRange, tokenAdvance, isLongOpaqueLatinToken)
                    else -> emptyList()
                }
                val hyphenCuts = if (
                    allLetters && !isAbbreviation && !isCamelCase &&
                        !isLongUnhyphenatedLetterToken && !w.contains('-') && cleanCuts.isEmpty()
                ) {
                    latinWordCuts(decision, segmentRange, syllableCuts)
                } else {
                    emptyList()
                }
                val opaqueHardCuts = if (
                    isLatin &&
                    (!allLetters || isLongUnhyphenatedLetterToken) &&
                    (tokenAdvance > measure || isLongOpaqueLatinToken)
                ) {
                    latinOpaqueHardCuts(decision, segmentRange, cleanCuts, isLongOpaqueLatinToken)
                } else {
                    emptyList()
                }
                val allCuts = (cleanCuts + hyphenCuts + opaqueHardCuts).distinct().sorted()
                if (allCuts.isEmpty()) {
                    listOf(shaped)
                } else {
                    if (hyphenCuts.isNotEmpty()) {
                        hyphenOffsets += hyphenCuts
                        if (hyphenAdvanceOrNull == null) {
                            val hyphenShaped = textShaper.shape(
                                ShapingInput(
                                    text = "-",
                                    range = TextRange(0, 1),
                                    style = input.textStyle,
                                    fontDecision = decision,
                                    displayText = "-",
                                ),
                            )
                            hyphenAdvanceOrNull = hyphenShaped.clusters.singleOrNull()?.advance ?: (0.5f * fontSize)
                            hyphenGlyphs = hyphenShaped.glyphRuns.flatMap { it.glyphs }
                        }
                    }
                    val bounds = listOf(segmentRange.start) + allCuts + listOf(segmentRange.end)
                    (0 until bounds.size - 1).flatMap { k ->
                        shapeSegmentWithPointMarkPrefix(
                            decision,
                            TextRange(bounds[k], bounds[k + 1]),
                        )
                    }
                }
            }
        }
        val hyphenAdvance = hyphenAdvanceOrNull ?: 0f
        val rawNaturalClusters = shapingResults.flatMap { it.clusters }
        val shapedGlyphsByClusterRange = shapingResults
            .flatMap { it.glyphRuns }
            .flatMap { it.glyphs }
            .groupBy { it.clusterRange }
        val openTypeFeaturesByClusterRange = buildMap<TextRange, List<String>> {
            shapingResults.flatMap { it.glyphRuns }.forEach { run ->
                run.glyphs.map { it.clusterRange }.distinct().forEach { range ->
                    val previous = put(range, run.openTypeFeatures)
                    require(previous == null || previous == run.openTypeFeatures) {
                        "Conflicting OpenType features for shaped cluster $range"
                    }
                }
            }
        }
        val shapingDecisions = shapingResults.flatMap { it.decisions }
        rawNaturalClusters.requireCoveredBy(fontDecisions)

        val inlineObjectRanges = input.inlineObjects.map { it.range }
        val eastAsianSpacingEdges = rawNaturalClusters.mapIndexed { index, cluster ->
            if (
                inlineObjectRanges.any { cluster.range.isInside(it) } ||
                rawNaturalClusters.isAttachedAsciiPointMarkAt(index)
            ) {
                EastAsianSpacingEdges(
                    leading = EastAsianSpacingValue.Other,
                    trailing = EastAsianSpacingValue.Other,
                    containsWide = false,
                )
            } else {
                UnicodeEastAsianSpacing.resolvedEdges(
                    text = cluster.text,
                    locale = input.textStyle.locale,
                )
            }
        }

        val autoSpaceResult = rawNaturalClusters.applyAutoSpacePolicy(
            eastAsianSpacingEdges = eastAsianSpacingEdges,
            inlineAttachments = rawNaturalClusters.map { styleAt(it.range.start).inlineAttachment },
            policy = clreqProfile.autoSpace,
            fontSize = fontSize,
        )
        val inlineBoxResult = autoSpaceResult.clusters.applyInlineBoxSpans(input.inlineBoxes)
        val naturalClusters = inlineBoxResult.clusters
        val inlineObjectByClusterIndex = buildMap {
            for (inlineObject in input.inlineObjects) {
                val clusterIndex = naturalClusters.indexOfFirst {
                    it.range == inlineObject.range && it.isInlineObjectCluster()
                }
                require(clusterIndex >= 0) {
                    "Inline object ${inlineObject.range} did not produce a layout cluster"
                }
                put(clusterIndex, inlineObject)
            }
        }
        val inlineObjectBoundaryAfterClusters = mutableMapOf<Int, InlineObjectBoundaryAdjustment>()
        fun registerInlineObjectBoundary(
            leftClusterIndex: Int,
            boundary: InlineObjectBoundaryAdjustment,
        ) {
            val previous = inlineObjectBoundaryAfterClusters[leftClusterIndex]
            if (previous == null) {
                inlineObjectBoundaryAfterClusters[leftClusterIndex] = boundary
                return
            }
            val preferredKinds = listOfNotNull(previous.preferredStretch, boundary.preferredStretch)
                .map { it.kind }
                .distinct()
            require(preferredKinds.size <= 1) {
                "Conflicting inline-object stretch classes at cluster boundary $leftClusterIndex"
            }
            val preferred = listOfNotNull(previous.preferredStretch, boundary.preferredStretch)
                .maxByOrNull { it.capacity }
            inlineObjectBoundaryAfterClusters[leftClusterIndex] = InlineObjectBoundaryAdjustment(
                participatesInUniformStretch =
                    previous.participatesInUniformStretch || boundary.participatesInUniformStretch,
                preferredStretch = preferred,
                shrinkCapacity = maxOf(previous.shrinkCapacity, boundary.shrinkCapacity),
                lineEndDiscardableAdvance = maxOf(
                    previous.lineEndDiscardableAdvance,
                    boundary.lineEndDiscardableAdvance,
                ),
                preventsLineBreak = previous.preventsLineBreak || boundary.preventsLineBreak,
            )
        }
        inlineObjectByClusterIndex.forEach { (clusterIndex, inlineObject) ->
            if (clusterIndex > 0 && inlineObject.leadingBoundary != InlineObjectBoundaryAdjustment.Fixed) {
                registerInlineObjectBoundary(clusterIndex - 1, inlineObject.leadingBoundary)
            }
            if (
                clusterIndex < naturalClusters.lastIndex &&
                inlineObject.trailingBoundary != InlineObjectBoundaryAdjustment.Fixed
            ) {
                registerInlineObjectBoundary(clusterIndex, inlineObject.trailingBoundary)
            }
        }
        val uniformInlineObjectBoundaryAfterClusters = inlineObjectBoundaryAfterClusters
            .filterValues { it.participatesInUniformStretch }
            .keys
        val preferredInlineObjectBoundaryAfterClusters: Map<Int, InlineObjectPreferredStretch> =
            inlineObjectBoundaryAfterClusters.mapNotNull { (clusterIndex, boundary) ->
                boundary.preferredStretch?.let { clusterIndex to it }
            }.toMap()
        val inlineObjectBoundaryUnbreakableRanges = inlineObjectBoundaryAfterClusters
            .filterValues { it.preventsLineBreak }
            .keys
            .map { leftClusterIndex -> leftClusterIndex..(leftClusterIndex + 1) }
        val autoSpaceDecisions = autoSpaceResult.decisions
        val clusterRoles = naturalClusters
            .containingItems(fontDecisions, FontDecision::range)
            .map { decision -> decision?.role ?: FontRole.Unknown }
        val resolvedKinsoku = clreqProfile.kinsokuMode.resolve(measureEm)
        val kinsokuRule = ClreqKinsokuRule(resolvedKinsoku.level)
        val inlineObjectAttachedMarks = naturalClusters.inlineObjectAttachedMarks(
            clusterRoles = clusterRoles,
            level = resolvedKinsoku.level,
            kinsokuRule = kinsokuRule,
        )
        val inlineObjectSeparatorSpaceTrims = buildMap {
            inlineObjectAttachedMarks.forEach { attachment ->
                attachment.separatorClusterIndices.forEach { clusterIndex ->
                    put(clusterIndex, naturalClusters[clusterIndex].advance)
                }
            }
        }
        val inlineObjectAttachmentNoStretchBoundaries = inlineObjectAttachedMarks
            .flatMapTo(mutableSetOf()) { attachment ->
                attachment.objectClusterIndex until attachment.markClusterIndex
            }
        val inlineObjectPunctuationAttachmentDecisions = inlineObjectAttachedMarks
            .filter { it.separatorClusterIndices.isNotEmpty() }
            .map { attachment ->
                val separatorFirst = naturalClusters[attachment.separatorClusterIndices.first()]
                val separatorLast = naturalClusters[attachment.separatorClusterIndices.last()]
                val mark = naturalClusters[attachment.markClusterIndex]
                InlineObjectPunctuationAttachmentDecisionInfo(
                    objectRange = naturalClusters[attachment.objectClusterIndex].range,
                    separatorRange = TextRange(separatorFirst.range.start, separatorLast.range.end),
                    punctuationRange = mark.range,
                    punctuationText = mark.text,
                    protectedRange = TextRange(
                        naturalClusters[attachment.objectClusterIndex].range.start,
                        mark.range.end,
                    ),
                    collapsedAdvance = attachment.separatorClusterIndices
                        .sumOf { naturalClusters[it].advance.toDouble() }
                        .toFloat(),
                )
            }
        val mandatoryBreakClusters = naturalClusters.indices
            .filterTo(mutableSetOf()) { idx -> naturalClusters[idx].isMandatoryBreakCluster() }
        val zeroWidthBreakClusters = naturalClusters.indices
            .filterTo(mutableSetOf()) { idx -> naturalClusters[idx].isZeroWidthSoftBreakCluster() }
        val mandatoryBreakDecisions = naturalClusters.mapIndexedNotNull { idx, cluster ->
            if (!cluster.isMandatoryBreakCluster()) return@mapIndexedNotNull null
            MandatoryBreakDecisionInfo(
                range = cluster.range,
                sourceText = cluster.text,
                breakAfterClusterIndex = idx,
                reason = "MandatoryBreakNoShape",
            )
        }
        val zeroWidthBreakDecisions = zeroWidthBreakClusters.sorted().map { clusterIndex ->
            val cluster = naturalClusters[clusterIndex]
            ZeroWidthBreakDecisionInfo(
                range = cluster.range,
                sourceText = cluster.text,
                clusterIndex = clusterIndex,
            )
        }

        // Punctuation atoms are a CJK-text concern: a LatinText cluster's ASCII
        // punctuation ('-', '/', ',') is part of the Latin glyph run (an English
        // hyphen, not a CJK 连接号), so it must NOT get a 短横线/标点 atom that
        // would collapse the cluster to half-width.
        val punctuationAtoms = naturalClusters.mapIndexedNotNull { idx, cluster ->
            if (clusterRoles[idx] == FontRole.LatinText) null else cluster
        }.flatMap { cluster ->
            cluster.punctuationAtoms(
                em = fontSize,
                builder = punctuationAtomBuilder,
                shapedGlyphs = shapedGlyphsByClusterRange[cluster.range].orEmpty(),
                gluePlacement = clreqProfile.gluePlacement,
                widthPolicy = clreqProfile.punctuationWidth,
            )
        }
        val adjacentPunctuationSpacingPlan =
            punctuationSpacingCompressor.compress(punctuationAtoms, em = fontSize)
        val cjkClosingBeforeAsciiPointMarkPlan =
            punctuationSpacingCompressor.compressCjkClosingBeforeAsciiPointMark(
                atoms = punctuationAtoms,
                text = text,
                em = fontSize,
            )
        val spacingPlan = PunctuationSpacingCompressionResult(
            adjustments = adjacentPunctuationSpacingPlan.adjustments +
                cjkClosingBeforeAsciiPointMarkPlan.adjustments,
        )
        // Shape each注文 once in ITS OWN font for horizontal width. Vertical fit
        // deliberately uses that Latin face's declared ascent/descent, not glyph
        // ink: changing hé to p or g must not change the line-height decision.
        val rubyFontGeometryBySpan = pinyinSpans.associateWith { ruby ->
            val metricText = ruby.text.ifEmpty { "x" }
            val range = TextRange(0, metricText.length)
            val preferredFamilies = ruby.fontFamilies
            val rubyLocale = ruby.locale ?: input.textStyle.locale
            val decision = fallbackResolver.resolve(
                text = metricText,
                range = range,
                request = FontRequest(
                    preferredFamilies = preferredFamilies,
                    locale = rubyLocale,
                    role = FontRole.LatinText,
                ),
            )
            val raw = fontMetricsResolver.resolve(
                FontMetricsRequest(
                    fontKey = decision.candidate.key,
                    fontSize = rubyFontSize,
                    role = FontRole.LatinText,
                    locale = rubyLocale,
                    fontWeight = rubyFontWeight,
                    italic = input.textStyle.italic,
                    faceSelectionText = metricText,
                    fontFamilies = preferredFamilies,
                ),
            )
            val declaredAscent = raw.typoAscent ?: raw.ascent
            val declaredDescent = raw.typoDescent ?: raw.descent
            val shaped = if (ruby.text.isEmpty()) {
                null
            } else {
                textShaper.shape(
                    ShapingInput(
                        text = ruby.text,
                        range = TextRange(0, ruby.text.length),
                        style = input.textStyle.copy(
                            fontSize = rubyFontSize,
                            fontFamilies = ruby.fontFamilies,
                            fontWeight = rubyFontWeight,
                            locale = rubyLocale,
                        ),
                        fontDecision = decision,
                        displayText = ruby.text,
                    ),
                )
            }
            RubyFontGeometry(
                width = shaped?.clusters.orEmpty().sumOf { it.advance.toDouble() }.toFloat(),
                ascent = if (ruby.text.isEmpty()) 0f else declaredAscent,
                descent = if (ruby.text.isEmpty()) 0f else declaredDescent,
                requiredExtent = if (ruby.text.isEmpty()) {
                    0f
                } else {
                    declaredAscent + declaredDescent + rubyStackGap
                },
                glyphs = shaped?.glyphRuns.orEmpty().flatMap { it.glyphs },
            )
        }
        // 避让: left→right, push a 注文 (and everything after) right by the MINIMAL
        // amount that restores the word-space gap to the previous 注文; record it as
        // trailing 字距 on the cluster just before the span. Narrow 注文 (gap already
        // ok) get nothing → they overhang freely (CLREQ「只要不侵犯最小间距，可允许
        // 注文伸展到相邻基字上方」). The first span is never pushed.
        fun computeRubySpread(natural: List<Cluster>, rubySize: Float): Map<Int, Float> {
            if (pinyinSpans.isEmpty()) return emptyMap()
            val wordSpace = rubySize * RUBY_MIN_GAP_EM_OF_RUBY
            val leftX = FloatArray(natural.size)
            var acc = 0f
            for (i in natural.indices) { leftX[i] = acc; acc += natural[i].advance }
            val measures = pinyinSpans.mapNotNull { ruby ->
                val idxRange = natural.clusterIndexRangeFor(ruby.baseRange) ?: return@mapNotNull null
                val center = (leftX[idxRange.first] + leftX[idxRange.last] + natural[idxRange.last].advance) / 2f
                Triple(idxRange.first, center, rubyFontGeometryBySpan.getValue(ruby).width)
            }.sortedBy { it.first }
            val spread = HashMap<Int, Float>()
            var shift = 0f
            var prevRight = Float.NEGATIVE_INFINITY
            for ((firstCluster, centerNatural, rw) in measures) {
                var center = centerNatural + shift
                val needed = prevRight + wordSpace - (center - rw / 2f)
                if (needed > 0f && firstCluster > 0) {
                    val key = firstCluster - 1
                    spread[key] = (spread[key] ?: 0f) + needed
                    shift += needed
                    center += needed
                }
                prevRight = center + rw / 2f
            }
            return spread
        }
        // 行间注 避让 (ADR 0032): adjacent 注文 keep ≥ one 注文 word-space — add the
        // MINIMAL trailing 字距 where they'd crowd (narrower 注文 just overhang).
        // STRUCTURAL spread baked into baseGeometry so the breaker + final geometry
        // both see the widened advances.
        val rubySpread = computeRubySpread(naturalClusters, rubyFontSize)
        // 注音 (ADR 0033): reserve the 0.5em 注音 column ONLY on each annotated base
        // char's right side (its last cluster). The uniform every-char reservation is
        // 繁体中文 纵横对齐 — not built yet — so it stays OUT: the 注音 sits in its own
        // base's trailing space and adjacent unannotated text keeps normal spacing.
        val bopomofoSpans = input.rubySpans.filter { it.kind == RubyKind.Bopomofo }
        val rubyAndBopomofoSpread = if (bopomofoSpans.isEmpty()) {
            rubySpread
        } else {
            HashMap(rubySpread).apply {
                bopomofoSpans.forEach { z ->
                    val r = naturalClusters.clusterIndexRangeFor(z.baseRange) ?: return@forEach
                    mergeValue(r.last, 0.5f * fontSize) { a, b -> a + b }
                }
            }
        }
        val naturalInlineAttachments = naturalClusters.map { styleAt(it.range.start).inlineAttachment }
        val punctuationBaseGeometry = PunctuationGeometryLedger.from(
            naturalClusters = naturalClusters,
            punctuationAtoms = punctuationAtoms,
            spacingPlan = spacingPlan,
        ).withInlineBoxAdvances(inlineBoxResult.advanceByCluster)
            .withRubySpread(rubyAndBopomofoSpread)
            .withRawEdgeTrims(inlineObjectSeparatorSpaceTrims)
        val attachedPunctuationBoundary =
            punctuationBaseGeometry.resolveAttachedInlinePunctuationBoundaries(
                inlineAttachments = naturalInlineAttachments,
                punctuationAtoms = punctuationAtoms,
                em = fontSize,
            )
        val baseGeometry = attachedPunctuationBoundary.geometry
        val attachedPunctuationTrailingGlueByCluster =
            attachedPunctuationBoundary.trailingGlueByCluster
        val clusters = baseGeometry.resolveClusters()
        // CLREQ 挤压处理优先顺序 (ADR 0020): tiered shrink resources for
        // PushIn. Punctuation classes map to tiers; style knobs gate the
        // inline-stop and sino-western tiers.
        val adjustmentStyle = clreqProfile.adjustment
        val glueCaps = baseGeometry.glueCapacities()
        val gapClusterRanges = autoSpaceDecisions
            .filter { it.side == "gap" }
            .map { it.clusterRange }
            .toSet()
        // Keyed by the CLUSTER's range. An atom's own range is per DISPLAY char —
        // for a rolled-back `——` (two display chars, ADR 0003) that is two per-char
        // ranges, and a lookup by the coalesced cluster range would silently miss
        // (device bug: the dash dropped out of noStretch/centering after rollback).
        val atomClassByRange: Map<TextRange, PunctuationClass> = naturalClusters
            .zip(naturalClusters.firstContainedItem(punctuationAtoms, PunctuationAtom::range))
            .mapNotNull { (cluster, atom) -> atom?.punctuationClass?.let { cluster.range to it } }
            .toMap()
        val shrinkOpportunities = buildList {
            naturalClusters.forEachIndexed { idx, cluster ->
                val caps = glueCaps[idx]
                if (caps != null) {
                    val cls = atomClassByRange[cluster.range]
                    when (cls) {
                        PunctuationClass.Interpunct,
                        PunctuationClass.MiddleDot,
                        -> {
                            // CLREQ ③ 间隔号：双侧同时、同等量，最小挤到 0。
                            val both = caps.leading + caps.trailing
                            if (both > 0f) {
                                add(ShrinkOpportunity(idx, tier = 3, capacity = both, channel = ShrinkChannel.LeadingAndTrailingGlue))
                            }
                        }

                        // CLREQ ④ 夹注符号：开始夹注的前侧、结束夹注的
                        // 后侧，最小挤到半个汉字字宽（= glue 全部可压）。
                        // Quote 经 pair 分析后开/闭各持一侧 glue，两个分支
                        // 自然各取其有的一侧。
                        PunctuationClass.Opening,
                        PunctuationClass.Closing,
                        PunctuationClass.Quote,
                        -> {
                            if (caps.leading > 0f) {
                                add(ShrinkOpportunity(idx, tier = 4, capacity = caps.leading, channel = ShrinkChannel.LeadingGlue))
                            }
                            if (caps.trailing > 0f) {
                                add(ShrinkOpportunity(idx, tier = 4, capacity = caps.trailing, channel = ShrinkChannel.TrailingGlue))
                            }
                        }

                        PunctuationClass.PauseOrStop -> {
                            // CLREQ ⑤ 行内逗、顿、分号（冒号原文未尽列，
                            // 按同档处理）；⑦ 行内句问叹排最后，且部分
                            // 风格禁止（knob）。
                            val isStop = cluster.displayText.firstOrNull() in INLINE_STOPS
                            val tier = if (isStop) 7 else 5
                            // Knob off: 行内句问叹 keep full width — their glue
                            // is only reachable via the tier-1 line-end
                            // promotion (行末削半 is a separate rule).
                            val lineEndOnly = isStop && !adjustmentStyle.allowInlineStopCompression
                            if (caps.trailing > 0f) {
                                add(
                                    ShrinkOpportunity(
                                        idx,
                                        tier = tier,
                                        capacity = caps.trailing,
                                        channel = ShrinkChannel.TrailingGlue,
                                        lineEndOnly = lineEndOnly,
                                    ),
                                )
                            }
                        }

                        // CLREQ 未列其余带 glue 的标点：按 ⑤ 档兜底。
                        else -> if (caps.trailing > 0f) {
                            add(ShrinkOpportunity(idx, tier = 5, capacity = caps.trailing, channel = ShrinkChannel.TrailingGlue))
                        }
                    }
                } else if (cluster.isSpaceRun() && idx !in inlineObjectSeparatorSpaceTrims) {
                    if (cluster.range in gapClusterRanges) {
                        // CLREQ ⑥ 中西间距：最小挤为八分之一汉字宽（不是 0）；
                        // 部分风格禁止（knob）。
                        val capacity = cluster.advance - SINO_WESTERN_GAP_MIN_EM * fontSize
                        if (adjustmentStyle.allowSinoWesternGapAdjustment && capacity > 0f) {
                            add(ShrinkOpportunity(idx, tier = 6, capacity = capacity, channel = ShrinkChannel.RawAdvance))
                        }
                    } else {
                        // CLREQ ② 西文词距：最小挤到 1/4em。
                        val capacity = cluster.advance - WORD_SPACE_MIN_EM * fontSize
                        if (capacity > 0f) {
                            add(ShrinkOpportunity(idx, tier = 2, capacity = capacity, channel = ShrinkChannel.RawAdvance))
                        }
                    }
                }
            }
            // `InlineObjectBoundaryCompression`: an object provider may expose
            // measured blank at an edge as a last-resort compression resource.
            // It follows every CLREQ text tier and never scales object glyphs.
            inlineObjectByClusterIndex.forEach { (idx, inlineObject) ->
                val trailing = inlineObject.trailingBoundary.shrinkCapacity
                if (trailing > 0f) {
                    add(ShrinkOpportunity(idx, tier = 8, capacity = trailing, channel = ShrinkChannel.RawAdvance))
                }
            }
        }

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
        val numberSymbolClusterRanges = NumberSymbolCohesion.unbreakableRanges(text)
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
        // CLREQ 拉伸限制①：符号分离禁则规定的内部字间距不得拉伸。
        val noStretchBoundaryAfterClusters = numberSymbolClusterRanges
            .flatMapTo(mutableSetOf()) { range -> range.first until range.last }
            .apply { addAll(inlineObjectAttachmentNoStretchBoundaries) }
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
            )
        }
        val appliedHangingClusters = lineSolution.lines
            .flatMap { it.hangingClusterIndices }
            .toSet()
        val impossibleMeasureContextualHangClusters =
            asciiPointMarkKinsoku.impossibleMeasureHangEligibleClusters +
                inlineObjectKinsoku.impossibleMeasureHangEligibleClusters
        val contextualKinsokuDecisions =
            (
                asciiPointMarkKinsoku.decisions +
                    inlineObjectKinsoku.decisions +
                    unicodePunctuationBoundaries.decisions
                )
                .distinctBy { decision -> decision.range to decision.forbiddenPosition }
                .map { decision ->
            if (
                decision.clusterIndex in impossibleMeasureContextualHangClusters &&
                decision.clusterIndex in appliedHangingClusters
            ) {
                decision.copy(
                    impossibleMeasureFallback = when (decision.reason) {
                        "AttachedAsciiPointMarkKinsoku" -> "AttachedAsciiPointMarkImpossibleMeasureHang"
                        else -> "InlineObjectAttachedMarkImpossibleMeasureHang"
                    },
                )
            } else {
                decision
            }
        }
        val pushInAllocations = lineSolution.lines
            .mapNotNull { it.repair as? RepairOption.PushIn }
            .flatMap { it.allocations }
        val pushInTrailing = HashMap<Int, Float>()
        val pushInLeading = HashMap<Int, Float>()
        val pushInRawTrims = HashMap<Int, Float>()
        for (alloc in pushInAllocations) {
            when (alloc.channel) {
                ShrinkChannel.TrailingGlue ->
                    pushInTrailing.mergeValue(alloc.clusterIndex, alloc.shrink) { a, b -> a + b }
                ShrinkChannel.LeadingGlue ->
                    // 开夹注符号前侧（CLREQ 挤压④）；渲染层按 consumed
                    // leading 左移字形原点（ADR 0017 amendment）。
                    pushInLeading.mergeValue(alloc.clusterIndex, alloc.shrink) { a, b -> a + b }
                ShrinkChannel.LeadingAndTrailingGlue -> {
                    // CLREQ: 间隔号挤压必须同时从字面两侧、同等量处理.
                    pushInLeading.mergeValue(alloc.clusterIndex, alloc.shrink / 2f) { a, b -> a + b }
                    pushInTrailing.mergeValue(alloc.clusterIndex, alloc.shrink / 2f) { a, b -> a + b }
                }
                ShrinkChannel.RawAdvance ->
                    pushInRawTrims.mergeValue(alloc.clusterIndex, alloc.shrink) { a, b -> a + b }
            }
        }
        // LineEndHangingHyphen 标点挤压 (ADR 0029 amend): a reserved hyphen that
        // would overflow the measure first squeezes the line's compressible glue
        // (the same `shrinkOpportunities`, in CLREQ 挤压 tier order, minus what
        // PushIn already took); only the residual it cannot recover hangs past
        // the edge. Augments the PushIn consume maps so the geometry applies both.
        fun lineHyphenAdvanceAt(lineIndex: Int): Float {
            if (hyphenOffsets.isEmpty() || lineIndex >= lineSolution.lines.lastIndex) return 0f
            val next = lineSolution.lines[lineIndex + 1]
            if (next.clusterRange.isEmptyClusterRange()) return 0f
            val nextFirst = next.clusterRange.first
            return if (naturalClusters[nextFirst].range.start in hyphenOffsets) hyphenAdvance else 0f
        }
        if (hyphenOffsets.isNotEmpty()) {
            lineSolution.lines.forEachIndexed { lineIndex, line ->
                if (line.clusterRange.isEmptyClusterRange()) return@forEachIndexed
                val hyphen = lineHyphenAdvanceAt(lineIndex)
                if (hyphen <= 0f) return@forEachIndexed
                val lineLimit = if (line.clusterRange.first == 0) measure - firstLineIndent else measure - blockIndent
                val content = line.clusterRange.sumOf { clusters[it].advance.toDouble() }.toFloat()
                var shortfall = content + hyphen - lineLimit
                if (shortfall <= 0.001f) return@forEachIndexed
                for (opp in shrinkOpportunities.filter { it.clusterIndex in line.clusterRange && !it.lineEndOnly }.sortedBy { it.tier }) {
                    if (shortfall <= 0.001f) break
                    val used = when (opp.channel) {
                        ShrinkChannel.TrailingGlue -> pushInTrailing[opp.clusterIndex] ?: 0f
                        ShrinkChannel.LeadingGlue -> pushInLeading[opp.clusterIndex] ?: 0f
                        ShrinkChannel.RawAdvance -> pushInRawTrims[opp.clusterIndex] ?: 0f
                        ShrinkChannel.LeadingAndTrailingGlue ->
                            (pushInLeading[opp.clusterIndex] ?: 0f) + (pushInTrailing[opp.clusterIndex] ?: 0f)
                    }
                    val take = minOf(shortfall, (opp.capacity - used).coerceAtLeast(0f))
                    if (take <= 0f) continue
                    when (opp.channel) {
                        ShrinkChannel.TrailingGlue -> pushInTrailing.mergeValue(opp.clusterIndex, take) { a, b -> a + b }
                        ShrinkChannel.LeadingGlue -> pushInLeading.mergeValue(opp.clusterIndex, take) { a, b -> a + b }
                        ShrinkChannel.LeadingAndTrailingGlue -> {
                            pushInLeading.mergeValue(opp.clusterIndex, take / 2f) { a, b -> a + b }
                            pushInTrailing.mergeValue(opp.clusterIndex, take / 2f) { a, b -> a + b }
                        }
                        ShrinkChannel.RawAdvance -> pushInRawTrims.mergeValue(opp.clusterIndex, take) { a, b -> a + b }
                    }
                    shortfall -= take
                }
            }
        }
        val pushInGeometry = baseGeometry
            .consumeTrailingByCluster(pushInTrailing)
            .consumeLeadingByCluster(pushInLeading)
        val pushInClusters = pushInGeometry.resolveClusters()
        val edgeTrimResult = pushInGeometry.consumeLineEdgeGlue(
            lines = lineSolution.lines,
            forceLineEndHalfWidth = adjustmentStyle.lineEndPunctuation ==
                LineEndPunctuationStyle.ForceHalfWidth,
        )
        // TextAutoSpaceLineEdgeTrim: the autospace replacement gap lives in
        // the Latin cluster's advance, not in punctuation glue, so the edge
        // trim above can't see it. A typed-space boundary gap landing on a
        // line edge must disappear like any other line-edge blank — without
        // this, justified lines stop one gap short of the right edge.
        val autoSpaceGap = clreqProfile.autoSpace.gapEm * fontSize
        val autoSpaceEdgeTrims = HashMap<Int, Float>()
        val autoSpaceEdgeDecisions = mutableListOf<LineEdgeTrimDecisionInfo>()
        lineSolution.lines.forEach { line ->
            if (line.clusterRange.isEmptyClusterRange()) return@forEach
            fun trimEdge(clusterIdx: Int, side: String) {
                val decision = autoSpaceDecisions.firstOrNull {
                    it.clusterRange == naturalClusters[clusterIdx].range && it.side == side
                } ?: return
                autoSpaceEdgeTrims.mergeValue(clusterIdx, autoSpaceGap) { a, b -> a + b }
                autoSpaceEdgeDecisions += LineEdgeTrimDecisionInfo(
                    lineRange = line.sourceRange,
                    clusterRange = decision.clusterRange,
                    side = side,
                    trimAmount = autoSpaceGap,
                    consumedBefore = 0f,
                    naturalGlue = autoSpaceGap,
                    reason = "TextAutoSpaceLineEdgeTrim",
                )
            }
            trimEdge(line.clusterRange.last, "trailing")
            trimEdge(line.clusterRange.first, "leading")

            // LineEdgeWordSpaceCollapse: a space-run cluster landing on a
            // line edge collapses entirely (CSS-like line-edge space
            // removal; also CLREQ — no sino-western gap at line edges).
            fun collapseEdgeSpace(clusterIdx: Int, side: String) {
                val cluster = naturalClusters[clusterIdx]
                if (!cluster.isSpaceRun()) return
                if (clusterIdx in inlineObjectSeparatorSpaceTrims) return
                val advance = naturalClusters[clusterIdx].advance
                if (advance <= 0f) return
                autoSpaceEdgeTrims.mergeValue(clusterIdx, advance) { a, b -> a + b }
                autoSpaceEdgeDecisions += LineEdgeTrimDecisionInfo(
                    lineRange = line.sourceRange,
                    clusterRange = cluster.range,
                    side = side,
                    trimAmount = advance,
                    consumedBefore = 0f,
                    naturalGlue = advance,
                    reason = "LineEdgeWordSpaceCollapse",
                )
            }
            collapseEdgeSpace(line.clusterRange.last, "trailing")
            collapseEdgeSpace(line.clusterRange.first, "leading")

            val attachedGlueCluster = line.clusterRange.last
            val attachedGlue = attachedPunctuationTrailingGlueByCluster[attachedGlueCluster] ?: 0f
            if (attachedGlue > 0f) {
                autoSpaceEdgeTrims.mergeValue(attachedGlueCluster, attachedGlue) { a, b -> a + b }
                autoSpaceEdgeDecisions += LineEdgeTrimDecisionInfo(
                    lineRange = line.sourceRange,
                    clusterRange = naturalClusters[attachedGlueCluster].range,
                    side = "trailing",
                    trimAmount = attachedGlue,
                    consumedBefore = 0f,
                    naturalGlue = attachedGlue,
                    reason = "AttachedInlineVirtualBoundaryLineEndTrim",
                )
            }

            // InlineObjectLineEndDiscardableGlue: a formula fragment includes its natural
            // post-operator math spacing when it stays in the line. If the paragraph actually
            // breaks at that boundary, the space is line-edge glue rather than visible content.
            // Remove only the part not already consumed by PushIn; the following fragment has no
            // corresponding leading advance, so both the old line end and new line start stay flush.
            if (line.endReason == LineEndReason.AutoWrap) {
                val clusterIdx = line.clusterRange.last
                val discardable = inlineObjectByClusterIndex[clusterIdx]
                    ?.trailingBoundary
                    ?.lineEndDiscardableAdvance
                    ?: 0f
                val consumedBefore = minOf(pushInRawTrims[clusterIdx] ?: 0f, discardable)
                val remaining = (discardable - consumedBefore).coerceAtLeast(0f)
                if (remaining > 0f) {
                    autoSpaceEdgeTrims.mergeValue(clusterIdx, remaining) { a, b -> a + b }
                    autoSpaceEdgeDecisions += LineEdgeTrimDecisionInfo(
                        lineRange = line.sourceRange,
                        clusterRange = naturalClusters[clusterIdx].range,
                        side = "trailing",
                        trimAmount = remaining,
                        consumedBefore = consumedBefore,
                        naturalGlue = discardable,
                        reason = "InlineObjectLineEndDiscardableGlue",
                    )
                }
            }
        }
        val rawTrims = HashMap<Int, Float>(autoSpaceEdgeTrims)
        pushInRawTrims.forEach { (idx, amount) -> rawTrims.mergeValue(idx, amount) { a, b -> a + b } }
        val trimmedGeometry = edgeTrimResult.geometry.withRawEdgeTrims(rawTrims)
        val trimmedClusters = trimmedGeometry.resolveClusters()
        val edgeTrimDecisions = edgeTrimResult.decisions + autoSpaceEdgeDecisions

        // LineEndHangingHyphen reserved width (ADR 0029 amend): a line that ends
        // mid-word at a hyphenation point gives the trailing hyphen real width
        // inside the measure — like a line-end punctuation mark, NOT hung by
        // default. The content therefore fills only `measure − hyphen`; when the
        // content can't be squeezed that far (over-long words with no room) the
        // hyphen falls past the edge (hangs) as a last resort, automatically.
        // CLREQ:「中文排版特别是书籍正文排版极少使用左齐右不齐，原则上
        // 应该进行两端对齐」— justification is the baseline, not an option:
        // every non-last line goes through the justify chain. The last line
        // is positioned by ParagraphStyle.lastLineAlignment instead.
        val justificationPlans: List<JustificationPlan?> = lineSolution.lines.mapIndexed { lineIndex, lineCandidate ->
            val isLast = lineIndex == lineSolution.lines.lastIndex
            if (isLast || lineCandidate.clusterRange.isEmptyClusterRange() || lineCandidate.endReason != LineEndReason.AutoWrap) {
                null
            } else {
                // A hung mark sits beyond the measure: justify fills the
                // CONTENT (range minus the hanging mark) to maxWidth.
                justifier.justify(
                    adjustedClusters = trimmedClusters,
                    clusterRoles = clusterRoles,
                    eastAsianSpacingEdges = eastAsianSpacingEdges,
                    lineClusterRange = lineCandidate.inMeasureClusterRange,
                    maxWidth = (if (lineCandidate.clusterRange.first == 0) {
                        measure - firstLineIndent
                    } else {
                        measure - blockIndent
                    }) - lineHyphenAdvanceAt(lineIndex),
                    fontSize = fontSize,
                    skip = false,
                    allowSinoWesternGapStretch = adjustmentStyle.allowSinoWesternGapAdjustment,
                    cjkLatinSpaceBaseEm = clreqProfile.autoSpace.gapEm,
                    cjkLatinSpaceMaxEm = clreqProfile.autoSpace.stretchMaxEm,
                    noStretchBoundaryClusters = noStretchBoundaryClusters,
                    noStretchBoundaryAfterClusters = noStretchBoundaryAfterClusters,
                    westernBracketCjkInterCharBoundaryAfterClusters =
                        westernBracketCjkInterCharBoundaryAfterClusters,
                    attachedInlinePhysicalBoundaryAfterClusters =
                        attachedInlinePhysicalBoundaryAfterClusters,
                    attachedInlineVirtualBoundaryAfterClusters =
                        attachedInlineVirtualBoundaryAfterClusters,
                    attachedInlineVirtualSinoWesternBoundaryAfterClusters =
                        attachedInlineVirtualSinoWesternBoundaryAfterClusters,
                    uniformInlineObjectBoundaryAfterClusters = uniformInlineObjectBoundaryAfterClusters,
                    preferredInlineObjectBoundaryAfterClusters = preferredInlineObjectBoundaryAfterClusters,
                )
            }
        }
        val justifyDeltaByCluster = HashMap<Int, Float>().apply {
            justificationPlans.filterNotNull()
                .flatMap { it.allocations }
                .forEach { alloc -> mergeValue(alloc.targetClusterIndex, alloc.delta) { a, b -> a + b } }
        }
        val finalGeometry = trimmedGeometry.addJustificationDeltas(justifyDeltaByCluster)
        val finalClusters = finalGeometry.resolveClusters().map { c ->
            // 字身框 bottom alignment: shift so this cluster's ideographic box
            // bottom meets the base 字身框 bottom (0 for base font/size).
            // ExplicitBaselineShiftSpan then stacks author intent (sup/subscript)
            // on top of that metric alignment; Roman clusters still keep metric
            // shift 0 but may receive the explicit style shift.
            val m = metricDecisionByRange[c.range]?.layoutMetrics ?: return@map c
            val metricShift = if (m.baselineClass == BaselineClass.Roman) 0f else baseBoxDescent - m.descent
            val shift = c.baselineShift + metricShift + styleAt(c.range.start).baselineShift
            if (shift > -0.01f && shift < 0.01f) c else c.copy(baselineShift = shift)
        }
        val geometryDecisions = finalGeometry.toDecisionInfo()

        // DashInkCentering: a 破折号 body is TWO EM by model (grid), but some
        // platform fonts draw their dash rule ≈1.6em of ink left-aligned in the
        // box (Pixel's Noto CJK — both its `⸺` and its `——` ligature share that
        // narrow rule). Centering the ink turns a one-sided ~0.35em hole into
        // symmetric side bearings. Only when the shaper reported ink bounds.
        fun List<Glyph>.centerDashInk(cluster: Cluster): List<Glyph> {
            if (atomClassByRange[cluster.range] != PunctuationClass.Dash) return this
            val glyph = singleOrNull() ?: return this
            val ink = glyph.bounds ?: return this
            val inset = (cluster.advance - (ink.right - ink.left)) / 2f - ink.left
            if (inset <= 0.5f) return this
            return listOf(glyph.copy(x = glyph.x + inset))
        }
        val glyphRuns = finalClusters
            .renderableGlyphRunClusters(openTypeFeaturesByClusterRange)
            .map { runClusters ->
                val openTypeFeatures = openTypeFeaturesByClusterRange[runClusters.first().range].orEmpty()
                GlyphRun(
                    range = TextRange(runClusters.first().range.start, runClusters.last().range.end),
                    fontKey = runClusters.first().fontKey,
                    glyphs = runClusters.flatMapIndexed { fallbackGlyphId, cluster ->
                        shapedGlyphsByClusterRange[cluster.range]
                            ?.mapToClusterRange(cluster)
                            ?.centerDashInk(cluster)
                            ?: listOf(
                                Glyph(
                                    id = fallbackGlyphId.toUInt(),
                                    clusterRange = cluster.range,
                                    advance = cluster.advance,
                                ),
                            )
                    },
                    advance = runClusters.sumOf { it.advance.toDouble() }.toFloat(),
                    openTypeFeatures = openTypeFeatures,
                )
            }

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
            bopomofoFontWeightAt = ::bopomofoFontWeightAt,
            baseTextStyle = input.textStyle,
        )

        val widestLine = lines.maxOfOrNull { it.indent + it.visualWidth + it.hyphenAdvance } ?: 0f
        val totalHeight = lines.lastOrNull()?.bottom ?: if (text.isEmpty()) 0f else baseLineMetrics.height
        val resultWidth = widestLine.coerceAtMost(input.constraints.maxWidth)

        return LayoutResult(
            input = input,
            size = Size(
                width = resultWidth,
                height = totalHeight,
            ),
            clusters = finalClusters,
            glyphRuns = glyphRuns,
            lines = lines,
            debug = LayoutDebugInfo(
                fontDecisions = fontDecisions.map { decision ->
                    val clusterText = text.substring(decision.range.start, decision.range.end)
                    val substitution = punctuationGlyphSubstitutor.substitute(clusterText)
                    val rollbackCause = substitutionRollbacks.entries.firstOrNull { it.key.isInside(decision.range) }?.value
                    FontDecisionInfo(
                        range = decision.range,
                        sourceText = clusterText,
                        displayText = if (rollbackCause != null) clusterText else substitution.displayText,
                        role = decision.role.name,
                        fontKey = decision.candidate.key,
                        reason = decision.reason,
                        substitutionReason = if (rollbackCause != null) {
                            "${substitution.reason}:$rollbackCause"
                        } else {
                            substitution.reason
                        },
                    )
                },
                shapingDecisions = shapingDecisions,
                metricDecisions = metricDecisions.map { decision ->
                    MetricDecisionInfo(
                        range = decision.range,
                        sourceText = decision.sourceText,
                        role = decision.request.role.name,
                        fontKey = decision.request.fontKey,
                        rawAscent = decision.rawMetrics.ascent,
                        rawDescent = decision.rawMetrics.descent,
                        rawLeading = decision.rawMetrics.leading,
                        rawSource = decision.rawMetrics.source.name,
                        layoutAscent = decision.layoutMetrics.ascent,
                        layoutDescent = decision.layoutMetrics.descent,
                        baselineClass = decision.layoutMetrics.baselineClass.name,
                        metricBox = decision.layoutMetrics.metricBox.name,
                        layoutSource = decision.layoutMetrics.source.name,
                        reason = decision.layoutMetrics.reason,
                    )
                },
                punctuationDecisions = punctuationAtoms.map { atom ->
                    PunctuationDecisionInfo(
                        range = atom.range,
                        char = atom.char,
                        punctuationClass = atom.punctuationClass.name,
                        advance = atom.advance,
                        bodyWidth = atom.bodyWidth,
                        leadingGlueNatural = atom.leadingGlue.natural,
                        trailingGlueNatural = atom.trailingGlue.natural,
                        leadingGlueInitiallyConsumed = atom.leadingGlueInitiallyConsumed,
                        trailingGlueInitiallyConsumed = atom.trailingGlueInitiallyConsumed,
                        anchor = atom.anchor.name,
                        inkBounds = atom.inkBounds,
                        geometrySource = atom.geometrySource,
                        policyBodyFloor = atom.policyBodyFloor,
                        inkWidth = atom.inkWidth,
                        inkCenter = atom.inkCenter,
                        inkContainmentBodyFloor = atom.inkContainmentBodyFloor,
                        inkContainmentApplied = atom.inkContainmentApplied,
                        inkBoundsFallback = atom.inkBoundsFallback,
                        haltAdvance = atom.haltAdvance,
                        haltValidation = atom.haltValidation,
                        advanceExpansion = atom.advanceExpansion,
                        glyphInlineShift = atom.glyphInlineShift,
                        glyphPlacementReason = atom.glyphPlacementReason,
                    )
                },
                geometryDecisions = geometryDecisions,
                spacingDecisions = spacingPlan.adjustments.map { adjustment ->
                    SpacingDecisionInfo(
                        range = adjustment.range,
                        leftChar = adjustment.leftChar,
                        rightChar = adjustment.rightChar,
                        naturalInnerGlue = adjustment.naturalInnerGlue,
                        adjustedInnerGlue = adjustment.adjustedInnerGlue,
                        reduction = adjustment.reduction,
                        reductionTargetRange = adjustment.reductionTargetRange,
                        reason = adjustment.reason,
                    )
                } + attachedPunctuationBoundary.decisions,
                roleOverrides = roleOverrideInfos,
                // Zip over ALL laid-out lines (not the maxLines-truncated boxes): the
                // dump records every committed line, the truncation names the cut.
                lineDecisions = laidOutLines.zip(lineSolution.lines).mapIndexed { lineIndex, (line, candidate) ->
                    LineDecisionInfo(
                        range = line.range,
                        kind = lineBreaker.strategyName,
                        repair = candidate.repair?.let { "${it::class.simpleName}" },
                        repairPenalty = candidate.repair?.penalty ?: 0,
                        repairDecision = candidate.repair?.toDecisionInfo(clusters),
                        repairCandidates = candidate.repairCandidates.map { it.toDecisionInfo(clusters) },
                        notes = listOf(
                            "index:$lineIndex",
                            "end:${line.endReason}",
                            "natural:${line.naturalWidth}",
                            "adjusted:${line.adjustedWidth}",
                            "visual:${line.visualWidth}",
                        ) + listOfNotNull(
                            candidate.repair?.let { "repair-reason:${it.reason}" },
                            justificationPlans.getOrNull(lineIndex)?.fallbackReason
                                ?.let { "justify-fallback:$it" },
                        ),
                    )
                },
                justificationDecisions = justificationPlans.zip(lineSolution.lines)
                    .mapNotNull { (plan, candidate) ->
                        plan
                            ?.takeIf { it.allocations.isNotEmpty() || it.deficitBefore > 0f }
                            ?.let {
                                JustificationDecisionInfo(
                                    lineRange = candidate.sourceRange,
                                    deficitBefore = it.deficitBefore,
                                    deficitAfter = it.unfilledDeficit,
                                    allocations = it.allocations.map { alloc ->
                                        JustificationAllocationInfo(
                                            clusterRange = clusters[alloc.targetClusterIndex].range,
                                            kind = alloc.kind.name,
                                            priority = alloc.priority,
                                            delta = alloc.delta,
                                            reason = alloc.reason,
                                        )
                                    },
                                )
                            }
                    },
                autoSpaceDecisions = autoSpaceDecisions,
                lineEdgeTrimDecisions = edgeTrimDecisions,
                decorationDecisions = decorationDecisions,
                decorationSegments = decorationSegments,
                rubyDecisions = rubyDecisions,
                bopomofoDecisions = bopomofoDecisions,
                mandatoryBreakDecisions = mandatoryBreakDecisions,
                maxLinesDecision = maxLinesDecision,
                lineSpacingDecision = lineSpacingDecision,
                rubyLineHeightDecision = rubyLineHeightDecision,
                inlineObjectLineHeightDecision = inlineObjectLineHeightDecision,
                kinsokuDecision = kinsokuDecision,
                contextualKinsokuDecisions = contextualKinsokuDecisions,
                lineLengthGridDecision = lineLengthGridDecision,
                firstLineIndentDecision = firstLineIndentDecision,
                inlineBoxDecisions = inlineBoxResult.decisions,
                inlineObjectDecisions = inlineObjectDecisions,
                inlineObjectPunctuationAttachmentDecisions = inlineObjectPunctuationAttachmentDecisions,
                zeroWidthBreakDecisions = zeroWidthBreakDecisions,
                breakOpportunityDecisions = breakOpportunityDecisions,
            ),
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

    private fun List<QuoteRoleDecision>.toRoleOverrideInfos(
        text: String,
        baseClassifier: FontRoleClassifier,
        context: FontRoleContext,
    ): List<RoleOverrideInfo> =
        sortedBy { it.index }
            .map { decision ->
                val index = decision.index
                val sourceText = text.substring(index, (index + 1).coerceAtMost(text.length))
                val originalRole = baseClassifier
                    .classify(text, TextRange(index, index + 1), context)
                RoleOverrideInfo(
                    range = TextRange(index, index + 1),
                    sourceText = sourceText,
                    originalRole = originalRole.name,
                    overriddenRole = decision.role.name,
                    source = decision.source,
                    reason = decision.reason,
                )
            }

    private data class ContextualKinsoku(
        val forbiddenLineStartClusters: Set<Int>,
        val unbreakableRanges: List<IntRange>,
        val impossibleMeasureHangEligibleClusters: Set<Int>,
        val extendableHangRanges: List<IntRange>,
        val decisions: List<ContextualKinsokuDecisionInfo>,
    )

    private data class InlineObjectAttachedMark(
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
    private fun List<Cluster>.inlineObjectAttachedMarks(
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
    private fun List<Cluster>.inlineObjectAttachedKinsoku(
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
    private fun List<Cluster>.attachedAsciiPointMarkKinsoku(
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

    private fun FontRole?.isCjkKinsokuRole(): Boolean =
        this == FontRole.CjkPunctuation

    /**
     * `CjkContextCurlyQuoteFullWidthVariant`: ask the font for its full-width
     * form before layout synthesizes a missing full-width punctuation cell.
     * Some fonts advertise `fwid` but do not map U+2018..U+201D, so the
     * punctuation model must still validate the shaped advance.
     */
    private fun cjkPunctuationFullWidthFeatures(role: FontRole, displayText: String): List<String> =
        if (role == FontRole.CjkPunctuation && displayText.any { it.isSharedCurlyQuote() }) {
            listOf("fwid=1")
        } else {
            emptyList()
        }

    private fun Char.isSharedCurlyQuote(): Boolean =
        this == '\u2018' || this == '\u2019' || this == '\u201C' || this == '\u201D'

    private fun String.isUrlLikeLatinToken(): Boolean {
        val lower = lowercase()
        return "://" in this || lower.startsWith("www.") || hasDomainLikeDot()
    }

    private fun String.hasDomainLikeDot(): Boolean =
        indices.any { i ->
            if (this[i] != '.' || i == 0 || i + 2 >= length) return@any false
            if (!this[i - 1].isLetterOrDigit() || !this[i + 1].isLetterOrDigit()) return@any false
            var tld = 0
            var j = i + 1
            while (j < length && this[j].isLetter()) {
                tld += 1
                j += 1
            }
            tld >= 2
        }

    private fun String.isLatinTokenBreakAfter(index: Int, keepUrlScheme: Boolean): Boolean {
        if (index !in 0 until lastIndex) return false
        return when (this[index]) {
            '/' -> !keepUrlScheme || getOrNull(index - 1) != ':'
            '.', '-', '_', '?', '&', '=', '#', '%', '~' -> true
            else -> false
        }
    }

    /**
     * `BibliographicNumericLocatorBreak`: a volume(issue):page-range locator is
     * structured Western text, not one indivisible opaque token. Expose clean
     * breaks before the issue group and after the colon while keeping every
     * digit run and the page range itself intact.
     *
     * This is deliberately narrower than a general numeric parser: ordinary
     * decimals, thousands separators, dates, times, and short identifiers keep
     * their existing cohesion rules.
     */
    private fun String.bibliographicNumericLocatorBreakOffsets(): List<Int> {
        val open = indexOf('(')
        if (open <= 0 || this[0].isDigit().not()) return emptyList()
        val close = indexOf(')', startIndex = open + 1)
        if (close <= open + 1) return emptyList()
        val colon = indexOf(':', startIndex = close + 1)
        if (colon != close + 1 || colon >= lastIndex) return emptyList()

        val volume = substring(0, open)
        val issue = substring(open + 1, close)
        val pages = substring(colon + 1).removeSuffix(".")
        if (volume.isEmpty() || issue.isEmpty() || pages.isEmpty()) return emptyList()
        if (!volume.all(Char::isDigit) || !issue.all(Char::isDigit)) return emptyList()

        val rangeSeparator = pages.indexOfFirst { it == '-' || it == '\u2013' || it == '\u2014' }
        val pagesAreNumeric = if (rangeSeparator < 0) {
            pages.all(Char::isDigit)
        } else {
            rangeSeparator > 0 &&
                rangeSeparator < pages.lastIndex &&
                pages.substring(0, rangeSeparator).all(Char::isDigit) &&
                pages.substring(rangeSeparator + 1).all(Char::isDigit)
        }
        if (!pagesAreNumeric) return emptyList()

        return listOf(open, colon + 1)
    }

    private fun String.hasBreakableLatinSolidus(): Boolean =
        indices.any { i ->
            this[i] == '/' &&
                i > 0 &&
                i < lastIndex &&
                this[i - 1].isLetterOrDigit() &&
                this[i + 1].isLetterOrDigit()
        }

    private fun clusterRoleRanges(
        text: String,
        classifier: FontRoleClassifier,
        context: FontRoleContext,
        profile: ClreqProfile,
        spanBoundaries: Set<Int> = emptySet(),
        inlineObjectsByStart: Map<Int, InlineObjectSpan> = emptyMap(),
    ): List<ResolvedClusterRange> {
        val coalesceSet = profile.coalesceRepeatablePunctuation
        val ranges = mutableListOf<ResolvedClusterRange>()
        var index = 0
        while (index < text.length) {
            val inlineObject = inlineObjectsByStart[index]
            if (inlineObject != null) {
                ranges.add(
                    ResolvedClusterRange(
                        range = inlineObject.range,
                        role = FontRole.Unknown,
                    ),
                )
                index = inlineObject.range.end
                continue
            }
            val codePoint = text.codePointAtCompat(index)
            val charCount = codePoint.charCount()
            val start = index
            if (codePoint.isMandatoryBreakCodePointAt(text, index)) {
                val end = if (codePoint == 0x000D && index + 1 < text.length && text[index + 1] == '\n') {
                    index + 2
                } else {
                    index + charCount
                }
                ranges.add(
                    ResolvedClusterRange(
                        range = TextRange(start, end),
                        role = FontRole.Unknown,
                        mandatoryBreak = true,
                    ),
                )
                index = end
                continue
            }
            if (isZeroWidthSpaceCodePoint(codePoint)) {
                val end = index + charCount
                ranges.add(
                    ResolvedClusterRange(
                        range = TextRange(start, end),
                        role = FontRole.Unknown,
                        zeroWidthSoftBreak = true,
                    ),
                )
                index = end
                continue
            }
            val firstRange = TextRange(start, start + charCount)
            val role = classifier.classify(text, firstRange, context)
            val previousRange = ranges.lastOrNull()
            val attachedAsciiPointMark =
                role == FontRole.LatinText &&
                    codePoint.isAsciiPointMarkCodePoint() &&
                    previousRange != null &&
                    previousRange.role != FontRole.Unknown &&
                    text.getOrNull(previousRange.range.end - 1)?.isWhitespace() == false &&
                    previousRange.range.end == start

            index += charCount
            if (role == FontRole.LatinText) {
                if (attachedAsciiPointMark) {
                    // `AttachedAsciiPointMarkSegmentation`: keep the leading
                    // point-mark run independent from following Latin text so
                    // kinsoku never has to move an entire `,anyway` token.
                    while (index < text.length && index !in spanBoundaries) {
                        val nextCodePoint = text.codePointAtCompat(index)
                        if (!nextCodePoint.isAsciiPointMarkCodePoint()) break
                        index += nextCodePoint.charCount()
                    }
                } else {
                    // A sized-span edge inside a Latin run / coalesced 标点 run ends the
                    // cluster there so each cluster carries a single font size (ADR 0030).
                    while (index < text.length && index !in spanBoundaries) {
                        val nextCodePoint = text.codePointAtCompat(index)
                        val nextCharCount = nextCodePoint.charCount()
                        val nextRange = TextRange(index, index + nextCharCount)
                        if (classifier.classify(text, nextRange, context) != FontRole.LatinText) break
                        index += nextCharCount
                    }
                }
            } else if (role == FontRole.CjkPunctuation && codePoint in coalesceSet) {
                while (index < text.length && index !in spanBoundaries) {
                    val nextCodePoint = text.codePointAtCompat(index)
                    if (nextCodePoint != codePoint) break
                    index += nextCodePoint.charCount()
                }
            }

            // `GraphemeExtendStaysWithBaseCluster`: common code can classify
            // BMP Mn/Mc/Me marks through Char.category; BMP and supplementary
            // variation selectors are covered explicitly. Shaping one of those
            // extenders as an independent Unknown run loses its base context and
            // produces a legitimate zero advance which web capability validation
            // then mistakes for a broken visible glyph. Keep the source range
            // intact and send the base plus every covered extending mark through
            // one font decision and shaping call. Other supplementary combining
            // categories remain outside this deliberately narrow helper.
            while (index < text.length && index !in spanBoundaries) {
                val extender = text.codePointAtCompat(index)
                if (!extender.isCombiningMarkCodePoint() && !extender.isVariationSelectorCodePoint()) break
                index += extender.charCount()
            }

            ranges.add(ResolvedClusterRange(TextRange(start, index), role))
        }
        return ranges
    }

    private fun Int.isMandatoryBreakCodePointAt(text: String, index: Int): Boolean =
        isMandatoryBreakCodePoint(this) &&
            !(this == 0x000A && index > 0 && text[index - 1] == '\r')

    private fun String.codePointAtCompat(index: Int): Int {
        val high = this[index].code
        if (high !in 0xD800..0xDBFF || index + 1 >= length) return high

        val low = this[index + 1].code
        if (low !in 0xDC00..0xDFFF) return high

        return 0x10000 + ((high - 0xD800) shl 10) + (low - 0xDC00)
    }

    private fun Int.charCount(): Int =
        if (this > 0xFFFF) 2 else 1

    private fun Int.isVariationSelectorCodePoint(): Boolean =
        this in 0xFE00..0xFE0F || this in 0xE0100..0xE01EF

    private fun Int.isCombiningMarkCodePoint(): Boolean =
        this in 0..0xFFFF && toChar().category in COMBINING_MARK_CATEGORIES

    private fun Int.isAsciiPointMarkCodePoint(): Boolean =
        this in 0..0xFFFF && ClreqPunctuationPolicies.isAsciiPointMark(toChar())

    private fun mandatoryBreakShapingResult(text: String, range: TextRange): ShapingResult {
        val sourceText = text.substring(range.start, range.end)
        val cluster = Cluster(
            range = range,
            text = sourceText,
            displayText = "",
            fontKey = MANDATORY_BREAK_FONT_KEY,
            advance = 0f,
        )
        return ShapingResult(
            clusters = listOf(cluster),
            glyphRuns = emptyList(),
            decisions = emptyList(),
        )
    }

    private fun zeroWidthSoftBreakShapingResult(text: String, range: TextRange): ShapingResult {
        val sourceText = text.substring(range.start, range.end)
        val cluster = Cluster(
            range = range,
            text = sourceText,
            displayText = "",
            fontKey = ZERO_WIDTH_SOFT_BREAK_FONT_KEY,
            advance = 0f,
        )
        return ShapingResult(
            clusters = listOf(cluster),
            glyphRuns = emptyList(),
            decisions = listOf(
                ShapingDecisionInfo(
                    range = range,
                    sourceText = sourceText,
                    displayText = "",
                    fontKey = ZERO_WIDTH_SOFT_BREAK_FONT_KEY,
                    glyphCount = 0,
                    advance = 0f,
                    source = "StructuralControl",
                    reason = "ZeroWidthSpaceSoftBreakNoShape",
                ),
            ),
        )
    }

    private fun inlineObjectShapingResult(text: String, inlineObject: InlineObjectSpan): ShapingResult {
        val sourceText = text.substring(inlineObject.range.start, inlineObject.range.end)
        return ShapingResult(
            clusters = listOf(
                Cluster(
                    range = inlineObject.range,
                    text = sourceText,
                    displayText = "",
                    fontKey = INLINE_OBJECT_FONT_KEY,
                    advance = inlineObject.advance,
                ),
            ),
            glyphRuns = emptyList(),
            decisions = listOf(
                ShapingDecisionInfo(
                    range = inlineObject.range,
                    sourceText = sourceText,
                    displayText = "",
                    fontKey = INLINE_OBJECT_FONT_KEY,
                    glyphCount = 0,
                    advance = inlineObject.advance,
                    source = "InlineObject",
                    reason = "MeasurableOpaqueInlineObject:no-font-shaping",
                ),
            ),
        )
    }

    private fun Cluster.isMandatoryBreakCluster(): Boolean =
        fontKey == MANDATORY_BREAK_FONT_KEY && displayText.isEmpty()

    private fun Cluster.isZeroWidthSoftBreakCluster(): Boolean =
        fontKey == ZERO_WIDTH_SOFT_BREAK_FONT_KEY && displayText.isEmpty()

    private fun Cluster.isInlineObjectCluster(): Boolean = fontKey == INLINE_OBJECT_FONT_KEY

    private fun Cluster.punctuationAtoms(
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

    /**
     * Named heuristic: `LatinWordSegmentation`. A LatinText font decision is
     * shaped per alternating word / space-run segment; every other role
     * shapes as one segment. Spaces become standalone clusters: break
     * opportunities, sino-western gaps (when CJK-adjacent) or stretchable
     * word spaces (when between two Latin words).
     */
    private fun FontDecision.shapingSegments(text: String): List<TextRange> {
        if (role != FontRole.LatinText) return listOf(range)
        val segments = mutableListOf<TextRange>()
        var segStart = range.start
        var inSpace = text[range.start] == ' '
        for (i in (range.start + 1) until range.end) {
            val isSpace = text[i] == ' '
            if (isSpace != inSpace) {
                segments += TextRange(segStart, i)
                segStart = i
                inSpace = isSpace
            }
        }
        segments += TextRange(segStart, range.end)
        return segments
    }

    private fun Cluster.isSpaceRun(): Boolean =
        text.isNotEmpty() && text.all { it == ' ' }

    private fun TextRange.isInside(other: TextRange): Boolean =
        start >= other.start && end <= other.end

    private fun List<Cluster>.requireCoveredBy(fontDecisions: List<FontDecision>) {
        var clusterIndex = 0
        fontDecisions.forEach { decision ->
            while (clusterIndex < size && this[clusterIndex].range.end <= decision.range.start) {
                clusterIndex += 1
            }
            var cursor = decision.range.start
            while (clusterIndex < size && this[clusterIndex].range.start < decision.range.end) {
                val cluster = this[clusterIndex]
                require(cluster.range.isInside(decision.range)) {
                    "TextShaper returned cluster ${cluster.range} crossing ${decision.range}"
                }
                require(cluster.range.start == cursor) {
                    "TextShaper returned non-contiguous clusters for ${decision.range}; " +
                        "expected start=$cursor, actual=${cluster.range}"
                }
                cursor = cluster.range.end
                clusterIndex += 1
            }
            require(cursor == decision.range.end) {
                "TextShaper must return clusters covering ${decision.range}; coveredUntil=$cursor"
            }
        }
    }

    // Glyph advances stay the shaper's NATURAL values — cluster-level spacing
    // (autospace / justify / push-in) lives at the positioning layer, never
    // smeared across interior glyphs (that smearing was the bug squeezing the
    // letters of slash-led runs like `/TERFism`). The one exception is the
    // degenerate no-ink fallback (missing-glyph fonts return 0 advance): there
    // we distribute the cluster body so glyphs don't stack at one x.
    private fun List<Glyph>.mapToClusterRange(cluster: Cluster): List<Glyph> {
        val sourceAdvance = sumOf { it.advance.toDouble() }.toFloat()
        if (sourceAdvance <= 0f) {
            val fallbackAdvance = cluster.advance / size.coerceAtLeast(1)
            return map { it.copy(advance = fallbackAdvance, clusterRange = cluster.range) }
        }
        return map { glyph -> glyph.copy(clusterRange = cluster.range) }
    }

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
    private fun List<Cluster>.applyAutoSpacePolicy(
        eastAsianSpacingEdges: List<EastAsianSpacingEdges>,
        inlineAttachments: List<InlineAttachment>,
        policy: AutoSpacePolicy,
        fontSize: Float,
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
                        boundaryRole = "EastAsianSpacing.Wide",
                        mode = AutoSpaceMode.Insert.name,
                        charactersAffected = 0,
                        reductionPerChar = 0f,
                        totalReduction = -gap,
                        reason = "TextAutoSpaceInsert:east-asian-spacing-W-N",
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
                        boundaryRole = if (virtualTrailingGap) {
                            "InlineAttachment.Previous"
                        } else {
                            "EastAsianSpacing.Wide"
                        },
                        mode = AutoSpaceMode.Insert.name,
                        charactersAffected = 0,
                        reductionPerChar = 0f,
                        totalReduction = -gap,
                        reason = if (virtualTrailingGap) {
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
    private fun isEastAsianSpacingBoundaryAt(
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
    private fun List<Cluster>.isAttachedAsciiPointMarkAt(index: Int): Boolean {
        if (index <= 0) return false
        val cluster = this[index]
        val previous = this[index - 1]
        return cluster.text.firstOrNull()?.let(ClreqPunctuationPolicies::isAsciiPointMark) == true &&
            previous.displayText.isNotEmpty() &&
            previous.text.lastOrNull()?.isWhitespace() == false &&
            previous.range.end == cluster.range.start
    }

    private fun RepairCandidate.toDecisionInfo(clusters: List<Cluster>): LineRepairCandidateInfo =
        LineRepairCandidateInfo(
            kind = kind,
            reasonCode = reasonCode,
            offenderRange = clusters[offenderClusterIndex].range,
            penalty = penalty,
            accepted = accepted,
            rejectionReason = rejectionReason,
            targetClusterIndex = targetClusterIndex,
            carriedClusterIndex = carriedClusterIndex,
            shrink = shrink,
            requiredShrink = requiredShrink,
            availableCapacity = availableCapacity,
        )

    private fun RepairOption.toDecisionInfo(clusters: List<Cluster>): LineRepairDecisionInfo =
        when (this) {
            is RepairOption.PushIn -> LineRepairDecisionInfo(
                // 避头尾 PushIn vs LineAdjustmentPushIn (ADR 0031) — the real
                // trigger lives in `reason`; don't hardcode it away.
                kind = "PushIn",
                reasonCode = reason.substringBefore(':'),
                offenderRange = clusters[offenderClusterIndex].range,
                penalty = penalty,
                targetClusterIndex = offenderClusterIndex,
                shrink = totalShrink,
                availableCapacity = totalAvailableCapacity,
                pushInAllocations = allocations.map { alloc ->
                    LineRepairAllocationInfo(
                        clusterRange = clusters[alloc.clusterIndex].range,
                        shrink = alloc.shrink,
                        availableCapacity = alloc.availableCapacity,
                    )
                },
            )

            is RepairOption.CarryPrevious -> LineRepairDecisionInfo(
                kind = "CarryPrevious",
                reasonCode = "ForbiddenAtLineStart",
                offenderRange = clusters[offenderClusterIndex].range,
                penalty = penalty,
                carriedClusterIndex = carriedClusterIndex,
            )

            is RepairOption.LeaveRagged -> LineRepairDecisionInfo(
                kind = "LeaveRagged",
                reasonCode = "ForbiddenAtLineStart",
                offenderRange = clusters[offenderClusterIndex].range,
                penalty = penalty,
            )

            is RepairOption.Hang -> LineRepairDecisionInfo(
                kind = "Hang",
                reasonCode = "ForbiddenAtLineStart",
                offenderRange = clusters[offenderClusterIndex].range,
                penalty = penalty,
            )

            is RepairOption.CarryNext -> LineRepairDecisionInfo(
                kind = "CarryNext",
                reasonCode = "ForbiddenAtLineEnd",
                offenderRange = clusters[movedClusterIndex].range,
                penalty = penalty,
                carriedClusterIndex = movedClusterIndex,
            )
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
    private fun computeBopomofoDecisions(
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

    private fun List<ClusterMetricDecision>.lineMetrics(
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

    private fun List<Cluster>.renderableGlyphRunClusters(
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
}

private data class ClusterMetricDecision(
    val range: TextRange,
    val sourceText: String,
    val request: FontMetricsRequest,
    val rawMetrics: RawFontMetrics,
    val layoutMetrics: LayoutFontMetrics,
)

private data class RubyFontGeometry(
    val width: Float,
    val ascent: Float,
    val descent: Float,
    val requiredExtent: Float,
    val glyphs: List<Glyph>,
)

private data class ResolvedLineMetrics(
    val baseline: Float,
    val height: Float,
    val extraLeading: Float = 0f,
)

private data class AutoSpaceApplicationResult(
    val clusters: List<Cluster>,
    val decisions: List<AutoSpaceDecisionInfo>,
)

private data class InlineBoxApplicationResult(
    val clusters: List<Cluster>,
    val advanceByCluster: Map<Int, Float>,
    val decisions: List<InlineBoxDecisionInfo>,
)

private fun List<Cluster>.applyInlineBoxSpans(spans: List<InlineBoxSpan>): InlineBoxApplicationResult {
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

private data class PunctuationGeometryLedger(
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
            if (leading > 0f || trailing > 0f) index to GlueCapacity(leading, trailing) else null
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
        lines.forEach { line ->
            if (line.clusterRange.isEmptyClusterRange()) return@forEach
            val lastIdx = line.clusterRange.last
            // 宽松风格 (AllowFullWidth): the unconditional line-end half-width
            // trim is skipped; the blank was only available as on-demand
            // shrink capacity during PushIn.
            val trailingBudget = if (forceLineEndHalfWidth) budgets[lastIdx] else null
            trailingBudget?.let { budget ->
                val remaining = budget.trailingRemaining
                if (remaining > 0f) {
                    trailingConsumptionByCluster.mergeValue(lastIdx, remaining) { a, b -> a + b }
                    decisions += LineEdgeTrimDecisionInfo(
                        lineRange = line.sourceRange,
                        clusterRange = naturalClusters[lastIdx].range,
                        side = "trailing",
                        trimAmount = remaining,
                        consumedBefore = budget.trailingConsumed,
                        naturalGlue = budget.trailingNatural,
                        reason = "LineEndHalfWidthPunctuation",
                    )
                }
            }

            val firstIdx = line.clusterRange.first
            budgets[firstIdx]?.let { budget ->
                val remaining = budget.leadingRemaining
                if (remaining > 0f) {
                    leadingConsumptionByCluster.mergeValue(firstIdx, remaining) { a, b -> a + b }
                    decisions += LineEdgeTrimDecisionInfo(
                        lineRange = line.sourceRange,
                        clusterRange = naturalClusters[firstIdx].range,
                        side = "leading",
                        trimAmount = remaining,
                        consumedBefore = budget.leadingConsumed,
                        naturalGlue = budget.leadingNatural,
                        reason = "LineStartHalfWidthPunctuation",
                    )
                }
            }
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

private data class AttachedInlinePunctuationBoundaryResult(
    val geometry: PunctuationGeometryLedger,
    val trailingGlueByCluster: Map<Int, Float>,
    val decisions: List<SpacingDecisionInfo>,
)

private data class PunctuationClusterGeometry(
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
    val reason: String,
)

private data class ResolvedClusterRange(
    val range: TextRange,
    val role: FontRole,
    val mandatoryBreak: Boolean = false,
    val zeroWidthSoftBreak: Boolean = false,
)

private data class GlueBudget(
    val leadingNatural: Float,
    val leadingConsumed: Float,
    val trailingNatural: Float,
    val trailingConsumed: Float,
) {
    val leadingRemaining: Float get() = (leadingNatural - leadingConsumed).coerceAtLeast(0f)
    val trailingRemaining: Float get() = (trailingNatural - trailingConsumed).coerceAtLeast(0f)
}

private data class LineEdgeTrimResult(
    val geometry: PunctuationGeometryLedger,
    val decisions: List<LineEdgeTrimDecisionInfo>,
)

/** ADR 0018 final painted diameter; renderers must not apply another scale factor. */
private const val EMPHASIS_DOT_DIAMETER_EM = 0.19f
private const val CJK_FACE_ASCENT_FALLBACK_EM = 0.88f
private const val CJK_FACE_DESCENT_FALLBACK_EM = 0.12f

/**
 * `CjkBodyLineHeightDefault`: 中文正文默认行高 1.5em(行距约 0.5em),无显式
 * [ParagraphStyle.lineHeight] 时生效。1.0em 实贴会让真墨迹(ascent≈0.94em)与
 * 相邻行碰头,且正文常规需要行距呼吸。CLREQ 的标点 floor 是「有行间标点时的
 * 下限」,与本默认取 max:单面装 0.5em floor 正好被本默认吸收,双面装 0.625em
 * 仍可顶高。显式 lineHeight 可向下覆盖本默认(仍不低于不重叠下限)。
 */
private const val DEFAULT_BODY_LINE_HEIGHT_EM = 1.5f

/** 行间注 (ruby, ADR 0032): 注文常用基文 1/2 字号 (CLREQ 振假名惯例). */
private const val RUBY_FONT_EM = 0.5f
/** `RubyLegibilityWeightBoost`: 拼音注文默认比基文重 100，保持轻而清楚. */
private const val RUBY_FONT_WEIGHT_BOOST = 100
/** `BopomofoLegibilityWeightBoost`: 注音 ㄅㄆㄇ 更小，默认比基文重 300. */
private const val BOPOMOFO_FONT_WEIGHT_BOOST = 300
/** 注音符号和普通调号共用的稳定字号；5×5 调号格只负责定位. */
private const val BOPOMOFO_ANNOTATION_FONT_EM = 0.3f
/** ㄅㄆㄇ symbol baseline as a fraction of its 字身框 height (horizontal-baseline replay). */
private const val BOPOMOFO_SYMBOL_BASELINE_FACTOR = 0.88f
private const val MANDATORY_BREAK_FONT_KEY = "mandatory-break"
private const val ZERO_WIDTH_SOFT_BREAK_FONT_KEY = "zero-width-space"
private const val INLINE_OBJECT_FONT_KEY = "inline-object"
/** `DashSubstitutionInkCoverageRollback`: keep `⸺` only if its ink fills ≥85% of the 2em advance (Pixel Noto ≈80% rolls back; Source Han ≈94% keeps). */
private const val DASH_SUBSTITUTION_MIN_INK_COVERAGE = 0.85f
private const val DASH_SUBSTITUTION_TARGET_EM = 2f
/** `EmptyParagraphBaselineFallback`: see [lineMetrics]'s empty branch. */
private const val EMPTY_PARAGRAPH_BASELINE_RATIO = 0.75f
/**
 * 避让 最小间距 (CLREQ §罗马拼音「相邻注文的间距不应小于西文词间空格」): one 注文
 * word space ≈ 1/4 of the 注文 em. Measured in 注文 units, NOT base 字宽.
 */
private const val RUBY_MIN_GAP_EM_OF_RUBY = 0.25f
/**
 * Extra clearance between the注文 Latin font box and the base 字身框顶 — **default 0**.
 * The base glyph has its own internal top margin; bump only if a style wants
 * visibly looser ruby.
 */
private const val RUBY_STACK_GAP_EM = 0f

/** `LatinForcedHyphenBreak` 硬断时尽量满足的左右边界（前二后三，同 en-US 连字）. */
private const val HYPHEN_MIN_LEFT = 2
private const val HYPHEN_MIN_RIGHT = 3

/** `LatinOpaqueTokenBreak`: long non-lexical Latin tokens expose clean no-hyphen breakpoints. */
private const val LATIN_OPAQUE_TOKEN_MIN_LENGTH = 24

/**
 * 连字作为最后一档（ADR 0029 amendment）：整词换行后，若填满版心需要给每个汉字
 * 间距加超过此值（半个字宽）才回头连字；以下则宁可拉伸汉字间距、不连字。
 */
private const val HYPHEN_LAST_RESORT_CJK_STRETCH_EM = 0.5f

/** 中西间距可拉伸余量（justify CjkLatinSpace cap 0.5em − 自然 0.25em），算松紧时先扣它. */
private const val HYPHEN_SINO_WESTERN_STRETCH_CAP_EM = 0.25f

/** CLREQ 挤压第②档：西文词距最小压至四分之一汉字宽. */
private const val WORD_SPACE_MIN_EM = 0.25f

/** CLREQ 挤压⑥：行内中西间距「最小挤为八分之一汉字宽」. */
private const val SINO_WESTERN_GAP_MIN_EM = 0.125f

/** CLREQ 行尾悬挂适配标点：顿号、逗号、句号. */
private val HANGABLE_PUNCTUATION = setOf('、', '，', '。')

/** CLREQ 挤压第④档对象：「位于行内的句号、问号、感叹号」. */
private val INLINE_STOPS = setOf('。', '！', '？', '．')

/** Remaining glue per side, input to the tiered shrink model (ADR 0020). */
internal data class GlueCapacity(val leading: Float, val trailing: Float)

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

/**
 * Contiguous cluster-index range whose clusters are fully covered by
 * [sourceRange]; null when no cluster is covered.
 */
private fun List<Cluster>.clusterIndexRangeFor(sourceRange: TextRange): IntRange? {
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
    adjustments: List<PunctuationSpacingAdjustment>,
): Map<Int, GlueBudget> {
    if (adjustments.isEmpty()) return this

    return toMutableMap().also { updated ->
        adjustments.forEach { adjustment ->
            val targetIdx = clusters.indexOfFirst { adjustment.reductionTargetRange.isInside(it.range) }
            if (targetIdx < 0) return@forEach
            updated[targetIdx]?.let { current ->
                // Consume reduction from whichever side has remaining capacity.
                // With class-based single-sided glue, all glue may be on one
                // side (e.g. PauseOrStop → trailing only, Opening → leading only).
                val leadingRemaining = current.leadingNatural - current.leadingConsumed
                val trailingRemaining = current.trailingNatural - current.trailingConsumed
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

private fun TextRange.isInside(other: TextRange): Boolean =
    start >= other.start && end <= other.end

/**
 * Common-stdlib port of `java.util.Map.merge` (absent from the JS common
 * stdlib and only present on Android from API 24): absent key → [value];
 * present → `remap(old, value)`. The distinct name prevents the JVM member
 * from winning overload resolution in API 23 artifacts.
 */
private fun <K, V : Any> MutableMap<K, V>.mergeValue(key: K, value: V, remap: (V, V) -> V) {
    this[key] = this[key]?.let { remap(it, value) } ?: value
}
