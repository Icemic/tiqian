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
import org.tiqian.shaping.ShapingResult

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

internal fun LayoutInput.toWidthIndependentAnnotationKey(
    rejectedTechnicalTiersBySpan: Map<TextRange, Set<ProgressiveBreakTier>> = emptyMap(),
): WidthIndependentAnnotationKey = WidthIndependentAnnotationKey(
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
    val clusterRanges: List<ResolvedClusterRange>,
    val fontDecisionByRange: Map<TextRange, FontDecision>,
    val inlineObjectByRange: Map<TextRange, InlineObjectSpan>,
    val segmentShapingCache: Map<TextRange, ShapingResult>,
    val substitutionRollbacks: Map<TextRange, String>,
    val rubyFontGeometryBySpan: Map<RubySpan, RubyFontGeometry>,
)

interface WidthIndependentAnnotationCache {
    fun get(key: WidthIndependentAnnotationKey): Any?
    fun put(key: WidthIndependentAnnotationKey, annotation: Any)
    fun clear()
    val size: Int
}

class LruWidthIndependentAnnotationCache(val maxEntries: Int = 512) : WidthIndependentAnnotationCache {
    private val map = mutableMapOf<WidthIndependentAnnotationKey, Any>()
    private val keys = mutableListOf<WidthIndependentAnnotationKey>()

    override fun get(key: WidthIndependentAnnotationKey): Any? {
        val value = map[key] ?: return null
        keys.remove(key)
        keys.add(key)
        return value
    }

    override fun put(key: WidthIndependentAnnotationKey, annotation: Any) {
        if (key in map) {
            keys.remove(key)
        } else if (keys.size >= maxEntries) {
            val oldest = keys.removeAt(0)
            map.remove(oldest)
        }
        keys.add(key)
        map[key] = annotation
    }

    override fun clear() {
        map.clear()
        keys.clear()
    }

    override val size: Int
        get() = map.size
}

/** 行间注 (ruby, ADR 0032): 注文常用基文 1/2 字号 (CLREQ 振假名惯例). */
private const val RUBY_FONT_EM = 0.5f

/** `RubyLegibilityWeightBoost`: 拼音注文默认比基文重 100，保持轻而清楚. */
private const val RUBY_FONT_WEIGHT_BOOST = 100

/** `BopomofoLegibilityWeightBoost`: 注音 ㄅㄆㄇ 更小，默认比基文重 300. */
private const val BOPOMOFO_FONT_WEIGHT_BOOST = 300

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

/** CLREQ 挤压第②档：西文词距最小压至四分之一汉字宽. */
private const val WORD_SPACE_MIN_EM = 0.25f

/** CLREQ 挤压⑥：行内中西间距「最小挤为八分之一汉字宽」. */
private const val SINO_WESTERN_GAP_MIN_EM = 0.125f

/** CLREQ 挤压第④档对象：「位于行内的句号、问号、感叹号」. */
private val INLINE_STOPS = setOf('。', '！', '？', '．')

private fun TextRange.isContainedIn(other: TextRange): Boolean =
    start >= other.start && end <= other.end

private fun <K, V> MutableMap<K, V>.mergeValue(key: K, value: V, remappingFunction: (V, V) -> V): V {
    val oldValue = get(key)
    val newValue = if (oldValue == null) value else remappingFunction(oldValue, value)
    put(key, newValue)
    return newValue
}

/** Monotonic interval join for source-ordered clusters and source-ordered decisions. */
internal fun <T> List<Cluster>.containingItems(
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

    val baseShapingStage = shapeParagraph(
        input = input,
        text = text,
        fontSize = fontSize,
        measure = Float.POSITIVE_INFINITY,
        clusterRanges = clusterRanges,
        fontDecisionByRange = fontDecisionByRange,
        inlineObjectByRange = inlineObjectByRange,
        punctuationGlyphSubstitutor = punctuationGlyphSubstitutor,
        styleAt = ::styleAt,
        emphasisItalicAt = ::emphasisItalicAt,
        rejectedTechnicalTiersBySpan = rejectedTechnicalTiersBySpan,
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
        clusterRanges = clusterRanges,
        fontDecisionByRange = fontDecisionByRange,
        inlineObjectByRange = inlineObjectByRange,
        segmentShapingCache = baseShapingStage.segmentShapingCache,
        substitutionRollbacks = baseShapingStage.substitutionRollbacks,
        rubyFontGeometryBySpan = rubyFontGeometryBySpan,
    )
}

internal fun ExplainableStubParagraphLayoutEngine.buildParagraphLayoutPrep(
    input: LayoutInput,
    annotation: WidthIndependentParagraphAnnotation,
    rejectedTechnicalTiersBySpan: Map<TextRange, Set<ProgressiveBreakTier>>,
): ParagraphLayoutPrep {
    val text = annotation.text
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

    // Compute width-dependent break opportunities and emergency breaks for the exact current measure
    // using the pre-shaped segment cache so textShaper is not called repeatedly
    val shapingStage = shapeParagraph(
        input = input,
        text = text,
        fontSize = fontSize,
        measure = measure,
        clusterRanges = annotation.clusterRanges,
        fontDecisionByRange = annotation.fontDecisionByRange,
        inlineObjectByRange = annotation.inlineObjectByRange,
        punctuationGlyphSubstitutor = annotation.punctuationGlyphSubstitutor,
        styleAt = annotation.styleAt,
        emphasisItalicAt = { offset ->
            input.decorations.any { it.kind == DecorationKind.Emphasis && offset >= it.range.start && offset < it.range.end }
        },
        rejectedTechnicalTiersBySpan = rejectedTechnicalTiersBySpan,
        cachedSegmentShaping = annotation.segmentShapingCache,
        cachedSubstitutionRollbacks = annotation.substitutionRollbacks,
    )
    val shapingResults = shapingStage.shapingResults
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

    rawNaturalClusters.requireCoveredBy(annotation.fontDecisions)

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
            inlineObjectRanges.any { cluster.range.isContainedIn(it) } ||
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
        inlineAttachments = rawNaturalClusters.map { annotation.styleAt(it.range.start).inlineAttachment },
        policy = annotation.clreqProfile.autoSpace,
        fontSize = fontSize,
        narrowInlineBoxLeadingClusters = narrowInlineBoxLeadingClusters,
        narrowInlineBoxTrailingClusters = narrowInlineBoxTrailingClusters,
    )
    val inlineBoxResult = autoSpaceResult.clusters.applyInlineBoxSpans(input.inlineBoxes)
    val naturalClusters = inlineBoxResult.clusters
    val inlineObjectByClusterIndex = naturalClusters
        .mapIndexedNotNull { clusterIndex, cluster ->
            annotation.inlineObjectByRange[cluster.range]?.let { clusterIndex to it }
        }
        .toMap()

    val inlineObjectBoundaryAfterClusters = mutableMapOf<Int, InlineObjectBoundaryAdjustment>()
    fun registerInlineObjectBoundary(leftClusterIndex: Int, boundary: InlineObjectBoundaryAdjustment) {
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
        .containingItems(annotation.fontDecisions, FontDecision::range)
        .map { decision -> decision?.role ?: FontRole.Unknown }

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

    val punctuationAtoms = naturalClusters.mapIndexedNotNull { idx, cluster ->
        if (clusterRoles[idx] == FontRole.LatinText) null else cluster
    }.flatMap { cluster ->
        cluster.punctuationAtoms(
            em = fontSize,
            builder = punctuationAtomBuilder,
            shapedGlyphs = shapedGlyphsByClusterRange[cluster.range].orEmpty(),
            gluePlacement = annotation.clreqProfile.gluePlacement,
            widthPolicy = annotation.clreqProfile.punctuationWidth,
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

    fun computeRubySpread(natural: List<Cluster>, rubySize: Float): Map<Int, Float> {
        if (annotation.pinyinSpans.isEmpty()) return emptyMap()
        val wordSpace = rubySize * RUBY_MIN_GAP_EM_OF_RUBY
        val leftX = FloatArray(natural.size)
        var acc = 0f
        for (i in natural.indices) { leftX[i] = acc; acc += natural[i].advance }
        val measures = annotation.pinyinSpans.mapNotNull { ruby ->
            val idxRange = natural.clusterIndexRangeFor(ruby.baseRange) ?: return@mapNotNull null
            val center = (leftX[idxRange.first] + leftX[idxRange.last] + natural[idxRange.last].advance) / 2f
            Triple(idxRange.first, center, annotation.rubyFontGeometryBySpan.getValue(ruby).width)
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
    val rubySpread = computeRubySpread(naturalClusters, annotation.rubyFontSize)
    val bopomofoSpans = input.rubySpans.filter { it.kind == RubyKind.Bopomofo }
    val rubyAndBopomofoSpread: Map<Int, Float> = if (bopomofoSpans.isEmpty()) {
        rubySpread
    } else {
        HashMap(rubySpread).apply {
            bopomofoSpans.forEach { z ->
                val r = naturalClusters.clusterIndexRangeFor(z.baseRange) ?: return@forEach
                mergeValue(r.last, 0.5f * fontSize) { a, b -> a + b }
            }
        }
    }
    val naturalInlineAttachments = naturalClusters.map { annotation.styleAt(it.range.start).inlineAttachment }
    val adjustmentStyle = annotation.clreqProfile.adjustment

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

    return ParagraphLayoutPrep(
        input = input,
        rejectedTechnicalTiersBySpan = rejectedTechnicalTiersBySpan,
        text = text,
        fontSize = fontSize,
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
        hyphenOffsets = shapingStage.hyphenOffsets,
        hyphenAdvance = shapingStage.hyphenAdvance,
        hyphenGlyphs = shapingStage.hyphenGlyphs,
        substitutionRollbacks = shapingStage.substitutionRollbacks,
        breakOpportunityDecisions = shapingStage.breakOpportunityDecisions,
        emergencyTrackingEligibilityDecisions = shapingStage.emergencyTrackingEligibilityDecisions,
        progressiveBreakOffsets = shapingStage.progressiveBreakOffsets,
        shapedGlyphsByClusterRange = shapedGlyphsByClusterRange,
        openTypeFeaturesByClusterRange = openTypeFeaturesByClusterRange,
        shapingDecisions = shapingStage.shapingResults.flatMap { it.decisions },
        eastAsianSpacingEdges = eastAsianSpacingEdges,
        autoSpaceDecisions = autoSpaceDecisions,
        inlineBoxResult = inlineBoxResult,
        naturalClusters = naturalClusters,
        inlineObjectByClusterIndex = inlineObjectByClusterIndex,
        uniformInlineObjectBoundaryAfterClusters = uniformInlineObjectBoundaryAfterClusters,
        preferredInlineObjectBoundaryAfterClusters = preferredInlineObjectBoundaryAfterClusters,
        inlineObjectBoundaryUnbreakableRanges = inlineObjectBoundaryUnbreakableRanges,
        clusterRoles = clusterRoles,
        resolvedKinsoku = resolvedKinsoku,
        kinsokuRule = kinsokuRule,
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
        rubyFontGeometryBySpan = annotation.rubyFontGeometryBySpan,
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
