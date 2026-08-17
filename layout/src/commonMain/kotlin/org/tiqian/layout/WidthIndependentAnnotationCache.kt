package org.tiqian.layout

import kotlin.math.floor
import org.tiqian.clreq.AdjustmentStylePolicy
import org.tiqian.clreq.ClreqProfile
import org.tiqian.clreq.ClreqPunctuationGlyphSubstitutor
import org.tiqian.clreq.PunctuationClass
import org.tiqian.core.AutoSpaceDecisionInfo
import org.tiqian.core.BreakOpportunityDecisionInfo
import org.tiqian.core.Cluster
import org.tiqian.core.DecorationKind
import org.tiqian.core.DecorationSpan
import org.tiqian.core.EastAsianSpacingEdges
import org.tiqian.core.EastAsianSpacingValue
import org.tiqian.core.EmergencyTrackingEligibilityDecisionInfo
import org.tiqian.core.Glyph
import org.tiqian.core.InlineAttachment
import org.tiqian.core.InlineBoxOuterSpacing
import org.tiqian.core.InlineBoxSpan
import org.tiqian.core.InlineObjectBoundaryAdjustment
import org.tiqian.core.InlineObjectPunctuationAttachmentDecisionInfo
import org.tiqian.core.InlineObjectPreferredStretch
import org.tiqian.core.InlineObjectSpan
import org.tiqian.core.LastLineAlignment
import org.tiqian.core.LayoutInput
import org.tiqian.core.LayoutProfileId
import org.tiqian.core.LineBreakSpan
import org.tiqian.core.LineLengthGridDecisionInfo
import org.tiqian.core.MandatoryBreakDecisionInfo
import org.tiqian.core.RoleOverrideInfo
import org.tiqian.core.RubyKind
import org.tiqian.core.RubySpan
import org.tiqian.core.ShapingDecisionInfo
import org.tiqian.core.TextRange
import org.tiqian.core.TextSpan
import org.tiqian.core.TextStyle
import org.tiqian.core.UnicodeEastAsianSpacing
import org.tiqian.core.ZeroWidthBreakDecisionInfo
import org.tiqian.font.FontDecision
import org.tiqian.font.FontMetricsRequest
import org.tiqian.font.FontRequest
import org.tiqian.font.FontRole
import org.tiqian.font.FontRoleClassifier
import org.tiqian.font.FontRoleContext
import org.tiqian.shaping.ShapingInput

/**
 * Key for caching width-independent layout annotations (`WidthIndependentAnnotationCache`).
 * Captures all textual, semantic, typographic, and annotation inputs that affect cluster
 * advances, punctuation atoms, autospace, and shaping before line breaking.
 */
data class WidthIndependentAnnotationKey(
    val text: String,
    val spans: List<TextSpan>,
    val lineBreakSpans: List<LineBreakSpan>,
    val sourceBoundaries: Set<Int>,
    val textStyle: TextStyle,
    val decorations: List<DecorationSpan>,
    val rubySpans: List<RubySpan>,
    val inlineBoxes: List<InlineBoxSpan>,
    val inlineObjects: List<InlineObjectSpan>,
    val profileId: LayoutProfileId,
    val emphasisDotGapEm: Float,
    val rejectedTechnicalTiersBySpan: Map<TextRange, Set<ProgressiveBreakTier>>,
)

/**
 * Complete set of width-independent paragraph annotations prepared before line breaking.
 * Reused during responsive resizing to avoid re-running font resolution, shaping, autospace,
 * inline box measurement, punctuation atomization, and ruby layout.
 */
internal class WidthIndependentParagraphAnnotation(
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

interface WidthIndependentAnnotationCache {
    fun get(key: WidthIndependentAnnotationKey): Any?
    fun put(key: WidthIndependentAnnotationKey, annotation: Any)
    fun clear()
    val size: Int
}

class LruWidthIndependentAnnotationCache(val maxEntries: Int = 512) : WidthIndependentAnnotationCache {
    private val entries = LinkedHashMap<WidthIndependentAnnotationKey, Any>()

    override fun get(key: WidthIndependentAnnotationKey): Any? {
        val value = entries.remove(key) ?: return null
        entries[key] = value
        return value
    }

    override fun put(key: WidthIndependentAnnotationKey, annotation: Any) {
        entries.remove(key)
        entries[key] = annotation
        if (entries.size > maxEntries) {
            val oldest = entries.keys.firstOrNull()
            if (oldest != null) entries.remove(oldest)
        }
    }

    override fun clear() {
        entries.clear()
    }

    override val size: Int
        get() = entries.size
}

internal fun LayoutInput.toWidthIndependentAnnotationKey(
    rejectedTechnicalTiersBySpan: Map<TextRange, Set<ProgressiveBreakTier>> = emptyMap(),
): WidthIndependentAnnotationKey =
    WidthIndependentAnnotationKey(
        text = content.text,
        spans = content.spans,
        lineBreakSpans = content.lineBreakSpans,
        sourceBoundaries = content.sourceBoundaries,
        textStyle = textStyle,
        decorations = decorations,
        rubySpans = rubySpans,
        inlineBoxes = inlineBoxes,
        inlineObjects = inlineObjects,
        profileId = profileId,
        emphasisDotGapEm = paragraphStyle.emphasisDotGapEm,
        rejectedTechnicalTiersBySpan = rejectedTechnicalTiersBySpan,
    )

internal fun ExplainableStubParagraphLayoutEngine.prepareWidthIndependentAnnotation(
    input: LayoutInput,
    rejectedTechnicalTiersBySpan: Map<TextRange, Set<ProgressiveBreakTier>>,
): WidthIndependentParagraphAnnotation {
    val text = input.content.text
    val fontSize = input.textStyle.fontSize
    val inlineObjectByRange = input.inlineObjects.associateBy { it.range }
    val sizedSpans = input.content.spans.filter { it.range.start < it.range.end }
    fun styleAt(offset: Int) =
        sizedSpans.lastOrNull { offset >= it.range.start && offset < it.range.end }?.style ?: input.textStyle
    fun fontSizeAt(offset: Int): Float = styleAt(offset).fontSize
    val emphasisRanges = input.decorations.filter { it.kind == DecorationKind.Emphasis }.map { it.range }
    fun emphasisItalicAt(offset: Int): Boolean =
        emphasisRanges.any { offset >= it.start && offset < it.end }
    val rubyFontSize = fontSize * RUBY_FONT_EM
    val rubyStackGap = fontSize * RUBY_STACK_GAP_EM
    val rubyFontWeight = (input.textStyle.fontWeight + RUBY_FONT_WEIGHT_BOOST).coerceIn(1, 900)
    fun bopomofoFontWeightAt(offset: Int): Int =
        (styleAt(offset).fontWeight + BOPOMOFO_FONT_WEIGHT_BOOST).coerceIn(1, 900)
    val pinyinSpans = input.rubySpans.filter { it.kind == RubyKind.Pinyin }
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
        input.content.lineBreakSpans.forEach { addRange(it.range) }
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

    val quotePairs = quotePairAnalyzer.analyze(text)
    val quoteRoleDecisions = quotePairAnalyzer.classifyQuoteRoles(text, quotePairs, context)
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
        val spanStyle = styleAt(resolvedRange.range.start)
        fallbackResolver.resolve(
            text = text,
            range = resolvedRange.range,
            request = FontRequest(
                preferredFamilies = spanStyle.fontFamilies,
                locale = spanStyle.locale,
                role = resolvedRange.role,
            ),
        )
    }
    val fontDecisionByRange = shapeableRanges.zip(fontDecisions).associate { (resolved, decision) ->
        resolved.range to decision
    }

    val shapingStage = shapeParagraph(
        input = input,
        text = text,
        fontSize = fontSize,
        measure = input.constraints.maxWidth,
        clusterRanges = clusterRanges,
        fontDecisionByRange = fontDecisionByRange,
        inlineObjectByRange = inlineObjectByRange,
        punctuationGlyphSubstitutor = punctuationGlyphSubstitutor,
        styleAt = ::styleAt,
        emphasisItalicAt = ::emphasisItalicAt,
        rejectedTechnicalTiersBySpan = rejectedTechnicalTiersBySpan,
    )
    val shapingResults = shapingStage.shapingResults
    val hyphenOffsets = shapingStage.hyphenOffsets
    val hyphenAdvance = shapingStage.hyphenAdvance
    val hyphenGlyphs = shapingStage.hyphenGlyphs
    val substitutionRollbacks = shapingStage.substitutionRollbacks
    val breakOpportunityDecisions = shapingStage.breakOpportunityDecisions
    val emergencyTrackingEligibilityDecisions = shapingStage.emergencyTrackingEligibilityDecisions
    val progressiveBreakOffsets = shapingStage.progressiveBreakOffsets
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
    val narrowInlineBoxRanges = input.inlineBoxes
        .filter { it.outerSpacing == InlineBoxOuterSpacing.Narrow }
        .mapTo(mutableSetOf()) { it.range }
    val narrowInlineBoxLeadingClusters = rawNaturalClusters.indices
        .filterTo(mutableSetOf()) { index ->
            narrowInlineBoxRanges.any { it.start == rawNaturalClusters[index].range.start }
        }
    val narrowInlineBoxTrailingClusters = rawNaturalClusters.indices
        .filterTo(mutableSetOf()) { index ->
            narrowInlineBoxRanges.any { it.end == rawNaturalClusters[index].range.end }
        }
    val eastAsianSpacingEdges = rawNaturalClusters.mapIndexed { index, cluster ->
        if (
            inlineObjectRanges.any { cluster.range.isInside(it) } ||
            (rawNaturalClusters.isAttachedAsciiPointMarkAt(index) &&
                index !in narrowInlineBoxLeadingClusters)
        ) {
            EastAsianSpacingEdges(
                leading = EastAsianSpacingValue.Other,
                trailing = EastAsianSpacingValue.Other,
                containsWide = false,
            )
        } else {
            val resolved = UnicodeEastAsianSpacing.resolvedEdges(
                text = cluster.text,
                locale = input.textStyle.locale,
            )
            resolved.copy(
                leading = if (index in narrowInlineBoxLeadingClusters) {
                    EastAsianSpacingValue.Narrow
                } else {
                    resolved.leading
                },
                trailing = if (index in narrowInlineBoxTrailingClusters) {
                    EastAsianSpacingValue.Narrow
                } else {
                    resolved.trailing
                },
            )
        }
    }

    val autoSpaceResult = rawNaturalClusters.applyAutoSpacePolicy(
        eastAsianSpacingEdges = eastAsianSpacingEdges,
        inlineAttachments = rawNaturalClusters.map { styleAt(it.range.start).inlineAttachment },
        policy = clreqProfile.autoSpace,
        fontSize = fontSize,
        narrowInlineBoxLeadingClusters = narrowInlineBoxLeadingClusters,
        narrowInlineBoxTrailingClusters = narrowInlineBoxTrailingClusters,
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

    val kinsokuRule = ClreqKinsokuRule(org.tiqian.clreq.KinsokuLevel.Strict)
    val inlineObjectAttachedMarks = naturalClusters.inlineObjectAttachedMarks(
        clusterRoles = clusterRoles,
        level = org.tiqian.clreq.KinsokuLevel.Strict,
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

    val rubySpread = computeRubySpread(naturalClusters, rubyFontSize)
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
    val adjustmentStyle = clreqProfile.adjustment
    val glueCaps = baseGeometry.glueCapacities()
    val gapClusterRanges = autoSpaceDecisions
        .filter { it.side == "gap" }
        .map { it.clusterRange }
        .toSet()
    val atomClassByRange: Map<TextRange, PunctuationClass> = naturalClusters
        .zip(naturalClusters.firstContainedItem(punctuationAtoms, PunctuationAtom::range))
        .mapNotNull { (cluster, atom) -> atom?.punctuationClass?.let { cluster.range to it } }
        .toMap()
    val shrinkOpportunities = buildList {
        naturalClusters.forEachIndexed { idx, cluster ->
            val caps = glueCaps[idx]
            if (caps != null) {
                val cls = atomClassByRange[cluster.range]
                fun addGeometryAwareOpportunity(
                    tier: Int,
                    lineEndOnly: Boolean = false,
                ) {
                    if (caps.paired) {
                        val pairedCapacity = 2f * minOf(caps.leading, caps.trailing)
                        if (pairedCapacity > 0f) {
                            add(
                                ShrinkOpportunity(
                                    clusterIndex = idx,
                                    tier = tier,
                                    capacity = pairedCapacity,
                                    channel = ShrinkChannel.LeadingAndTrailingGlue,
                                    lineEndOnly = lineEndOnly,
                                ),
                            )
                        }
                    } else {
                        if (caps.leading > 0f) {
                            add(
                                ShrinkOpportunity(
                                    clusterIndex = idx,
                                    tier = tier,
                                    capacity = caps.leading,
                                    channel = ShrinkChannel.LeadingGlue,
                                    lineEndOnly = lineEndOnly,
                                ),
                            )
                        }
                        if (caps.trailing > 0f) {
                            add(
                                ShrinkOpportunity(
                                    clusterIndex = idx,
                                    tier = tier,
                                    capacity = caps.trailing,
                                    channel = ShrinkChannel.TrailingGlue,
                                    lineEndOnly = lineEndOnly,
                                ),
                            )
                        }
                    }
                }
                when (cls) {
                    PunctuationClass.Interpunct,
                    PunctuationClass.MiddleDot,
                    -> {
                        addGeometryAwareOpportunity(tier = 3)
                    }
                    PunctuationClass.Opening,
                    PunctuationClass.Closing,
                    PunctuationClass.Quote,
                    -> {
                        addGeometryAwareOpportunity(tier = 4)
                    }
                    PunctuationClass.PauseOrStop -> {
                        val isStop = cluster.displayText.firstOrNull() in INLINE_STOPS
                        val tier = if (isStop) 7 else 5
                        val lineEndOnly = isStop && !adjustmentStyle.allowInlineStopCompression
                        addGeometryAwareOpportunity(tier = tier, lineEndOnly = lineEndOnly)
                    }
                    else -> addGeometryAwareOpportunity(tier = 5)
                }
            } else if (cluster.isSpaceRun() && idx !in inlineObjectSeparatorSpaceTrims) {
                if (cluster.range in gapClusterRanges) {
                    val capacity = cluster.advance - SINO_WESTERN_GAP_MIN_EM * fontSize
                    if (adjustmentStyle.allowSinoWesternGapAdjustment && capacity > 0f) {
                        add(ShrinkOpportunity(idx, tier = 6, capacity = capacity, channel = ShrinkChannel.RawAdvance))
                    }
                } else {
                    val capacity = cluster.advance - WORD_SPACE_MIN_EM * fontSize
                    if (capacity > 0f) {
                        add(ShrinkOpportunity(idx, tier = 2, capacity = capacity, channel = ShrinkChannel.RawAdvance))
                    }
                }
            }
        }
        inlineObjectByClusterIndex.forEach { (idx, inlineObject) ->
            val trailing = inlineObject.trailingBoundary.shrinkCapacity
            if (trailing > 0f) {
                add(ShrinkOpportunity(idx, tier = 8, capacity = trailing, channel = ShrinkChannel.RawAdvance))
            }
        }
    }

    return WidthIndependentParagraphAnnotation(
        text = text,
        fontSize = fontSize,
        styleAt = ::styleAt,
        fontSizeAt = ::fontSizeAt,
        bopomofoFontWeightAt = ::bopomofoFontWeightAt,
        rubyFontSize = rubyFontSize,
        rubyStackGap = rubyStackGap,
        rubyFontWeight = rubyFontWeight,
        pinyinSpans = pinyinSpans,
        clreqProfile = clreqProfile,
        punctuationGlyphSubstitutor = punctuationGlyphSubstitutor,
        quotePairs = quotePairs,
        roleOverrideInfos = roleOverrideInfos,
        fontDecisions = fontDecisions,
        hyphenOffsets = hyphenOffsets,
        hyphenAdvance = hyphenAdvance,
        hyphenGlyphs = hyphenGlyphs,
        substitutionRollbacks = substitutionRollbacks,
        breakOpportunityDecisions = breakOpportunityDecisions,
        emergencyTrackingEligibilityDecisions = emergencyTrackingEligibilityDecisions,
        progressiveBreakOffsets = progressiveBreakOffsets,
        shapedGlyphsByClusterRange = shapedGlyphsByClusterRange,
        openTypeFeaturesByClusterRange = openTypeFeaturesByClusterRange,
        shapingDecisions = shapingDecisions,
        eastAsianSpacingEdges = eastAsianSpacingEdges,
        autoSpaceDecisions = autoSpaceDecisions,
        inlineBoxResult = inlineBoxResult,
        naturalClusters = naturalClusters,
        inlineObjectByClusterIndex = inlineObjectByClusterIndex,
        uniformInlineObjectBoundaryAfterClusters = uniformInlineObjectBoundaryAfterClusters,
        preferredInlineObjectBoundaryAfterClusters = preferredInlineObjectBoundaryAfterClusters,
        inlineObjectBoundaryUnbreakableRanges = inlineObjectBoundaryUnbreakableRanges,
        clusterRoles = clusterRoles,
        inlineObjectAttachedMarks = inlineObjectAttachedMarks,
        inlineObjectSeparatorSpaceTrims = inlineObjectSeparatorSpaceTrims,
        inlineObjectAttachmentNoStretchBoundaries = inlineObjectAttachmentNoStretchBoundaries,
        inlineObjectPunctuationAttachmentDecisions = inlineObjectPunctuationAttachmentDecisions,
        mandatoryBreakClusters = mandatoryBreakClusters,
        zeroWidthBreakClusters = zeroWidthBreakClusters,
        mandatoryBreakDecisions = mandatoryBreakDecisions,
        zeroWidthBreakDecisions = zeroWidthBreakDecisions,
        punctuationAtoms = punctuationAtoms,
        spacingPlan = spacingPlan,
        rubyFontGeometryBySpan = rubyFontGeometryBySpan,
        rubyAndBopomofoSpread = rubyAndBopomofoSpread,
        naturalInlineAttachments = naturalInlineAttachments,
        attachedPunctuationBoundary = attachedPunctuationBoundary,
        baseGeometry = baseGeometry,
        attachedPunctuationTrailingGlueByCluster = attachedPunctuationTrailingGlueByCluster,
        clusters = clusters,
        adjustmentStyle = adjustmentStyle,
        atomClassByRange = atomClassByRange,
        shrinkOpportunities = shrinkOpportunities,
    )
}

internal fun ExplainableStubParagraphLayoutEngine.buildParagraphLayoutPrep(
    input: LayoutInput,
    annotation: WidthIndependentParagraphAnnotation,
    rejectedTechnicalTiersBySpan: Map<TextRange, Set<ProgressiveBreakTier>>,
): ParagraphLayoutPrep {
    val fontSize = input.textStyle.fontSize
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
    val resolvedKinsoku = annotation.clreqProfile.kinsokuMode.resolve(measureEm)
    val kinsokuRule = ClreqKinsokuRule(resolvedKinsoku.level)

    return ParagraphLayoutPrep(
        input = input,
        rejectedTechnicalTiersBySpan = rejectedTechnicalTiersBySpan,
        text = annotation.text,
        fontSize = annotation.fontSize,
        styleAt = annotation.styleAt,
        fontSizeAt = annotation.fontSizeAt,
        bopomofoFontWeightAt = annotation.bopomofoFontWeightAt,
        rubyFontSize = annotation.rubyFontSize,
        rubyStackGap = annotation.rubyStackGap,
        rubyFontWeight = annotation.rubyFontWeight,
        pinyinSpans = annotation.pinyinSpans,
        clreqProfile = annotation.clreqProfile,
        punctuationGlyphSubstitutor = annotation.punctuationGlyphSubstitutor,
        measure = measure,
        measureEm = measureEm,
        gridBodyOffset = gridBodyOffset,
        lineLengthGridDecision = lineLengthGridDecision,
        quotePairs = annotation.quotePairs,
        roleOverrideInfos = annotation.roleOverrideInfos,
        fontDecisions = annotation.fontDecisions,
        hyphenOffsets = annotation.hyphenOffsets,
        hyphenAdvance = annotation.hyphenAdvance,
        hyphenGlyphs = annotation.hyphenGlyphs,
        substitutionRollbacks = annotation.substitutionRollbacks,
        breakOpportunityDecisions = annotation.breakOpportunityDecisions,
        emergencyTrackingEligibilityDecisions = annotation.emergencyTrackingEligibilityDecisions,
        progressiveBreakOffsets = annotation.progressiveBreakOffsets,
        shapedGlyphsByClusterRange = annotation.shapedGlyphsByClusterRange,
        openTypeFeaturesByClusterRange = annotation.openTypeFeaturesByClusterRange,
        shapingDecisions = annotation.shapingDecisions,
        eastAsianSpacingEdges = annotation.eastAsianSpacingEdges,
        autoSpaceDecisions = annotation.autoSpaceDecisions,
        inlineBoxResult = annotation.inlineBoxResult,
        naturalClusters = annotation.naturalClusters,
        inlineObjectByClusterIndex = annotation.inlineObjectByClusterIndex,
        uniformInlineObjectBoundaryAfterClusters = annotation.uniformInlineObjectBoundaryAfterClusters,
        preferredInlineObjectBoundaryAfterClusters = annotation.preferredInlineObjectBoundaryAfterClusters,
        inlineObjectBoundaryUnbreakableRanges = annotation.inlineObjectBoundaryUnbreakableRanges,
        clusterRoles = annotation.clusterRoles,
        resolvedKinsoku = resolvedKinsoku,
        kinsokuRule = kinsokuRule,
        inlineObjectAttachedMarks = annotation.inlineObjectAttachedMarks,
        inlineObjectSeparatorSpaceTrims = annotation.inlineObjectSeparatorSpaceTrims,
        inlineObjectAttachmentNoStretchBoundaries = annotation.inlineObjectAttachmentNoStretchBoundaries,
        inlineObjectPunctuationAttachmentDecisions = annotation.inlineObjectPunctuationAttachmentDecisions,
        mandatoryBreakClusters = annotation.mandatoryBreakClusters,
        zeroWidthBreakClusters = annotation.zeroWidthBreakClusters,
        mandatoryBreakDecisions = annotation.mandatoryBreakDecisions,
        zeroWidthBreakDecisions = annotation.zeroWidthBreakDecisions,
        punctuationAtoms = annotation.punctuationAtoms,
        spacingPlan = annotation.spacingPlan,
        rubyFontGeometryBySpan = annotation.rubyFontGeometryBySpan,
        rubyAndBopomofoSpread = annotation.rubyAndBopomofoSpread,
        naturalInlineAttachments = annotation.naturalInlineAttachments,
        attachedPunctuationBoundary = annotation.attachedPunctuationBoundary,
        baseGeometry = annotation.baseGeometry,
        attachedPunctuationTrailingGlueByCluster = annotation.attachedPunctuationTrailingGlueByCluster,
        clusters = annotation.clusters,
        adjustmentStyle = annotation.adjustmentStyle,
        atomClassByRange = annotation.atomClassByRange,
        shrinkOpportunities = annotation.shrinkOpportunities,
    )
}

private const val RUBY_FONT_EM = 0.5f
private const val RUBY_FONT_WEIGHT_BOOST = 100
private const val BOPOMOFO_FONT_WEIGHT_BOOST = 300
private const val RUBY_MIN_GAP_EM_OF_RUBY = 0.25f
private const val RUBY_STACK_GAP_EM = 0f
private const val WORD_SPACE_MIN_EM = 0.25f
private const val SINO_WESTERN_GAP_MIN_EM = 0.125f
private val INLINE_STOPS = setOf('。', '！', '？', '．')

internal fun <T> List<Cluster>.firstContainedItem(
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
