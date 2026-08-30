package org.tiqian.layout

import org.tiqian.clreq.PunctuationClass
import org.tiqian.core.Cluster
import org.tiqian.core.EmergencyTrackingEligibilityDecisionInfo
import org.tiqian.core.InlineObjectSpan
import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LayoutInput
import org.tiqian.core.LineBreakSpan
import org.tiqian.core.ParagraphStyle
import org.tiqian.core.TextRange
import org.tiqian.core.TiqianTextContent
import org.tiqian.font.FontCandidate
import org.tiqian.font.FontDecision
import org.tiqian.font.FontRole
import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.assertFailsWith
import org.tiqian.test.trace.assertTrue
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

class LineBreakPlanningStageCoverage2Test {
    private val testTrace = TestTraceRecorder("LineBreakPlanningStageCoverage2Test")


    private val engine = ExplainableStubParagraphLayoutEngine()

    private fun layout(
        text: String,
        maxWidth: Float = 200.0f,
        lineBreakSpans: List<LineBreakSpan> = emptyList(),
        inlineObjects: List<InlineObjectSpan> = emptyList(),
    ): org.tiqian.core.LayoutResult {
        return engine.layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(),
                content = TiqianTextContent(text = text, spans = emptyList(), lineBreakSpans = lineBreakSpans),
                inlineObjects = inlineObjects,
                constraints = LayoutConstraints(maxWidth = maxWidth),
            ),
        )
    }

    private fun getBasePrep(text: String): ParagraphLayoutPrep {
        val input = LayoutInput(
            paragraphStyle = ParagraphStyle(),
            content = TiqianTextContent(text = text, spans = emptyList(), lineBreakSpans = emptyList()),
            constraints = LayoutConstraints(maxWidth = 200.0f),
        )
        val annotation = engine.prepareWidthIndependentAnnotation(input, emptyMap())
        return engine.buildParagraphLayoutPrep(input, annotation, emptyMap())
    }

    private fun ParagraphLayoutPrep.with(
        naturalClusters: List<Cluster> = this.naturalClusters,
        clusters: List<Cluster> = this.clusters,
        fontDecisions: List<FontDecision> = this.fontDecisions,
        progressiveBreakOffsets: Map<Int, ProgressiveBreakOpportunity> = this.progressiveBreakOffsets,
        emergencyTrackingEligibilityDecisions: List<EmergencyTrackingEligibilityDecisionInfo> = this.emergencyTrackingEligibilityDecisions,
        uniformInlineObjectBoundaryAfterClusters: Set<Int> = this.uniformInlineObjectBoundaryAfterClusters,
        atomClassByRange: Map<TextRange, PunctuationClass> = this.atomClassByRange,
        clusterRoles: List<FontRole> = if (naturalClusters.size == this.naturalClusters.size) this.clusterRoles else List(naturalClusters.size) { FontRole.LatinText },
        eastAsianSpacingEdges: List<org.tiqian.core.EastAsianSpacingEdges> = if (naturalClusters.size == this.naturalClusters.size) this.eastAsianSpacingEdges else List(naturalClusters.size) { org.tiqian.core.EastAsianSpacingEdges(org.tiqian.core.EastAsianSpacingValue.Other, org.tiqian.core.EastAsianSpacingValue.Other, false) },
        naturalInlineAttachments: List<org.tiqian.core.InlineAttachment> = if (naturalClusters.size == this.naturalClusters.size) this.naturalInlineAttachments else List(naturalClusters.size) { org.tiqian.core.InlineAttachment.None },
    ) = ParagraphLayoutPrep(
        input = input,
        rejectedTechnicalTiersBySpan = rejectedTechnicalTiersBySpan,
        text = text,
        fontSize = fontSize,
        styleAt = styleAt,
        fontSizeAt = fontSizeAt,
        bopomofoFontWeightAt = bopomofoFontWeightAt,
        rubyFontSize = rubyFontSize,
        rubyStackGap = rubyStackGap,
        rubyFontWeight = rubyFontWeight,
        pinyinSpans = pinyinSpans,
        clreqProfile = clreqProfile,
        punctuationGlyphSubstitutor = punctuationGlyphSubstitutor,
        measure = measure,
        measureEm = measureEm,
        gridBodyOffset = gridBodyOffset,
        lineLengthGridDecision = lineLengthGridDecision,
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

    @Test
    fun testClusterCrossesFontDecisionThrows() {
        testTrace.section("testClusterCrossesFontDecisionThrows")
        val prep = getBasePrep("abcdef")
        val badCluster = Cluster(
            range = TextRange(0, 5),
            text = "abcde",
            displayText = "abcde",
            fontKey = "test",
            advance = 50.0f,
        )
        val badDecision = FontDecision(
            range = TextRange(0, 3),
            candidate = FontCandidate("test", "test", FontRole.LatinText),
            role = FontRole.LatinText,
            reason = "test",
        )
        val prepModified = prep.with(
            naturalClusters = listOf(badCluster),
            clusters = listOf(badCluster),
            fontDecisions = listOf(badDecision),
        )
        val err = assertFailsWith<IllegalArgumentException> {
            engine.planParagraphLines(prepModified)
        }
        assertTrue(err.message!!.contains("crosses font decision"), err.message)
    }

    @Test
    fun testFontDecisionWithNoMatchingClustersUsesTextSubstring() {
        testTrace.section("testFontDecisionWithNoMatchingClustersUsesTextSubstring")
        val prep = getBasePrep("abcdef")
        // Font decision at range 4..6 with no clusters in that range
        val decision = FontDecision(
            range = TextRange(4, 6),
            candidate = FontCandidate("test", "test", FontRole.LatinText),
            role = FontRole.LatinText,
            reason = "test",
        )
        val cluster = Cluster(
            range = TextRange(0, 2),
            text = "ab",
            displayText = "ab",
            fontKey = "test",
            advance = 20.0f,
        )
        val prepModified = prep.with(
            naturalClusters = listOf(cluster),
            clusters = listOf(cluster),
            fontDecisions = listOf(decision),
        )
        val result = engine.planParagraphLines(prepModified)
        assertEquals(1, result.metricDecisions.size)
        assertEquals("ef", result.metricDecisions[0].request.faceSelectionText)
    }

    @Test
    fun testAsciiPointMarkKinsokuLineStart() {
        testTrace.section("testAsciiPointMarkKinsokuLineStart")
        val result = layout("hello, world", maxWidth = 50.0f)
        assertTrue(result.lines.isNotEmpty())
    }

    @Test
    fun testInlineObjectKinsokuLineStart() {
        testTrace.section("testInlineObjectKinsokuLineStart")
        val text = "￼hello"
        val result = layout(
            text,
            maxWidth = 50.0f,
            inlineObjects = listOf(
                InlineObjectSpan(range = TextRange(0, 1), advance = 16.0f, ascent = 8.0f, descent = 8.0f),
            ),
        )
        assertTrue(result.lines.isNotEmpty())
    }

    @Test
    fun testProgressiveBreakOffsetsUnmappedClusterIndex() {
        testTrace.section("testProgressiveBreakOffsetsUnmappedClusterIndex")
        val prep = getBasePrep("abc")
        val opp = ProgressiveBreakOpportunity(ProgressiveBreakTier.Whitespace, TextRange(0, 3))
        val prepModified = prep.with(
            progressiveBreakOffsets = mapOf(999 to opp),
        )
        val result = engine.planParagraphLines(prepModified)
        assertTrue(result.progressiveBreakOpportunities.isEmpty())
    }

    @Test
    fun testEmergencyTrackingEligibilityDecisionsBranches() {
        testTrace.section("testEmergencyTrackingEligibilityDecisionsBranches")
        // Multi-cluster CJK text so boundaryEligible is true for intermediate clusters
        val prep = getBasePrep("中文字符")
        val prepModified = prep.with(
            emergencyTrackingEligibilityDecisions = listOf(
                EmergencyTrackingEligibilityDecisionInfo(TextRange(100, 200), "unmapped", "reason"),
                EmergencyTrackingEligibilityDecisionInfo(TextRange(0, 4), "中文字符", "validReason"),
                EmergencyTrackingEligibilityDecisionInfo(TextRange(0, 4), "中文字符", "duplicateReason"),
            ),
        )
        val result = engine.planParagraphLines(prepModified)
        assertTrue(result.lineSolution.lines.isNotEmpty())
    }

    @Test
    fun testEmergencyTrackingBoundaryWhitespaceAndEmpty() {
        testTrace.section("testEmergencyTrackingBoundaryWhitespaceAndEmpty")
        val prep = getBasePrep("ab")
        val clusters = listOf(
            Cluster(TextRange(0, 0), "", "", "test", 0.0f),
            Cluster(TextRange(0, 1), "a", "a", "test", 10.0f),
            Cluster(TextRange(1, 1), "", "", "test", 0.0f),
            Cluster(TextRange(1, 2), "b", "b", "test", 10.0f),
        )
        val prepModified = prep.with(
            naturalClusters = clusters,
            clusters = clusters,
            emergencyTrackingEligibilityDecisions = listOf(
                EmergencyTrackingEligibilityDecisionInfo(TextRange(0, 2), "ab", "reason"),
            ),
        )
        val result = engine.planParagraphLines(prepModified)
        assertTrue(result.lineSolution.lines.isNotEmpty())
    }

    @Test
    fun testAdjustableInlineBoundaryRightClustersNoStretchBoundaries() {
        testTrace.section("testAdjustableInlineBoundaryRightClustersNoStretchBoundaries")
        val prep = getBasePrep("中文字符排版")
        val prepModified = prep.with(
            uniformInlineObjectBoundaryAfterClusters = setOf(0, 1, 3),
            atomClassByRange = mapOf(
                TextRange(0, 1) to PunctuationClass.Dash,
                TextRange(2, 3) to PunctuationClass.Connector,
            ),
        )
        val result = engine.planParagraphLines(prepModified)
        assertTrue(result.lineSolution.lines.isNotEmpty())
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
