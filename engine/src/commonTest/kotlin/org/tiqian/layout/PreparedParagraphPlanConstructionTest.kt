package org.tiqian.layout

import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.assertFalse
import org.tiqian.test.trace.f32Literal
import org.tiqian.test.trace.assertTrue
import org.tiqian.core.BopomofoDecisionInfo
import org.tiqian.core.BopomofoGlyphPlacement
import org.tiqian.core.BopomofoGlyphRole
import org.tiqian.core.Cluster
import org.tiqian.core.DecorationDecisionInfo
import org.tiqian.core.DecorationKind
import org.tiqian.core.DecorationSegmentInfo
import org.tiqian.core.DecorationSpan
import org.tiqian.core.FontDecisionInfo
import org.tiqian.core.Glyph
import org.tiqian.core.GlyphRun
import org.tiqian.core.InlineBoxSpan
import org.tiqian.core.InlineObjectSpan
import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LayoutDebugInfo
import org.tiqian.core.LayoutInput
import org.tiqian.core.LayoutResult
import org.tiqian.core.LineBox
import org.tiqian.core.PunctuationDecisionInfo
import org.tiqian.core.RubyDecisionInfo
import org.tiqian.core.ShapingDecisionInfo
import org.tiqian.core.Size
import org.tiqian.core.TextRange
import org.tiqian.core.TextSpan
import org.tiqian.core.TextStyle
import org.tiqian.core.TiqianTextContent
import org.tiqian.core.ZeroWidthBreakDecisionInfo
import org.tiqian.font.FontRole
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

/**
 * Hand-built LayoutResult values drive the plan serializer through the field
 * combinations the real pipeline never produces in one paragraph: render
 * evidence blocks, diagnostics envelopes, string escapes and the -0.0 guard.
 * Numbers used here (integers, halves) render identically on every backend.
 */
class PreparedParagraphPlanConstructionTest {
    private val testTrace = TestTraceRecorder("PreparedParagraphPlanConstructionTest")


    private fun result(
        content: TiqianTextContent = TiqianTextContent("汉"),
        textStyle: TextStyle = TextStyle(),
        decorations: List<DecorationSpan> = emptyList(),
        inlineBoxes: List<InlineBoxSpan> = emptyList(),
        inlineObjects: List<InlineObjectSpan> = emptyList(),
        clusters: List<Cluster>,
        glyphRuns: List<GlyphRun>,
        lines: List<LineBox>,
        debug: LayoutDebugInfo = LayoutDebugInfo(),
        width: Float = 480.0f,
        height: Float = 24.0f,
    ): LayoutResult = LayoutResult(
        input = LayoutInput(
            content = content,
            textStyle = textStyle,
            constraints = LayoutConstraints(maxWidth = width),
            decorations = decorations,
            inlineBoxes = inlineBoxes,
            inlineObjects = inlineObjects,
        ),
        size = Size(width, height),
        clusters = clusters,
        glyphRuns = glyphRuns,
        lines = lines,
        debug = debug,
    )

    private fun line(
        range: TextRange,
        clusterRange: IntRange,
        baseline: Float = 20.0f,
        top: Float = 0.0f,
        bottom: Float = 24.0f,
        naturalWidth: Float = 26.0f,
    ): LineBox = LineBox(
        range = range,
        clusterRange = clusterRange,
        baseline = baseline,
        top = top,
        bottom = bottom,
        naturalWidth = naturalWidth,
        adjustedWidth = naturalWidth,
        visualWidth = naturalWidth,
    )

    @Test
    fun openTypeFeaturesAndRenderFontFamilyAttachPerCluster() {
        testTrace.section("openTypeFeaturesAndRenderFontFamilyAttachPerCluster")
        val result = result(
            clusters = listOf(
                Cluster(range = TextRange(0, 1), text = "汉", fontKey = "cjk", advance = 16.0f),
            ),
            glyphRuns = listOf(
                GlyphRun(
                    range = TextRange(0, 1),
                    fontKey = "cjk",
                    glyphs = listOf(
                        Glyph(id = 7u, clusterRange = TextRange(0, 1), advance = 16.0f, renderFontKey = "Noto Serif CJK"),
                    ),
                    advance = 16.0f,
                    openTypeFeatures = listOf("kern", "liga"),
                ),
            ),
            lines = listOf(line(TextRange(0, 1), 0..0)),
        )
        val json = result.toPreparedParagraphJson(renderEvidence = true)
        assertTrue(json.contains("\"openTypeFeatures\":[\"kern\",\"liga\"]"), json)
        assertTrue(json.contains("\"renderFontFamily\":\"Noto Serif CJK\""), json)
        assertFalse(json.contains("shapingBoundary"), json)
    }

    @Test
    fun multiUnitClusterMarksShapingBoundary() {
        testTrace.section("multiUnitClusterMarksShapingBoundary")
        val result = result(
            content = TiqianTextContent("AB"),
            clusters = listOf(
                Cluster(range = TextRange(0, 2), text = "AB", fontKey = "latin", advance = 18.0f),
            ),
            glyphRuns = listOf(
                GlyphRun(
                    range = TextRange(0, 2),
                    fontKey = "latin",
                    glyphs = listOf(Glyph(id = 1u, clusterRange = TextRange(0, 2), advance = 18.0f)),
                    advance = 18.0f,
                ),
            ),
            lines = listOf(line(TextRange(0, 2), 0..0)),
        )
        val json = result.toPreparedParagraphJson()
        assertTrue(json.contains("\"shapingBoundary\":true"), json)
    }

    @Test
    fun inlineObjectCellEmitsAdvanceOverride() {
        testTrace.section("inlineObjectCellEmitsAdvanceOverride")
        val result = result(
            content = TiqianTextContent("汉图"),
            inlineObjects = listOf(
                InlineObjectSpan(range = TextRange(1, 2), advance = 24.0f, ascent = 12.0f, descent = 4.0f),
            ),
            clusters = listOf(
                Cluster(range = TextRange(0, 1), text = "汉", fontKey = "cjk", advance = 16.0f),
                Cluster(range = TextRange(1, 2), text = "图", fontKey = "inline", advance = 10.0f),
            ),
            glyphRuns = listOf(
                GlyphRun(
                    range = TextRange(0, 1),
                    fontKey = "cjk",
                    glyphs = listOf(Glyph(id = 1u, clusterRange = TextRange(0, 1), advance = 16.0f)),
                    advance = 16.0f,
                ),
                GlyphRun(
                    range = TextRange(1, 2),
                    fontKey = "inline",
                    glyphs = listOf(Glyph(id = 2u, clusterRange = TextRange(1, 2), advance = 24.0f)),
                    advance = 24.0f,
                ),
            ),
            lines = listOf(line(TextRange(0, 2), 0..1, naturalWidth = 40.0f)),
        )
        val json = result.toPreparedParagraphJson(renderEvidence = true)
        assertTrue(json.contains("\"inlineObject\":24"), json)
        assertTrue(json.contains("\"advance\":10"), json)
        // Without render evidence an inline-object cell with no display text
        // is dropped from the cells list entirely; with render evidence the
        // inline-object lookup alone keeps it.
        val emptyDisplay = result.copy(
            clusters = result.clusters.map { it.copy(displayText = if (it.range.start == 1) "" else it.displayText) },
        )
        val plain = emptyDisplay.toPreparedParagraphJson(renderEvidence = false)
        assertFalse(plain.contains("\"inlineObject\""), plain)
        assertFalse(plain.contains("\"rangeStart\":1"), plain)
        val evidence = emptyDisplay.toPreparedParagraphJson(renderEvidence = true)
        assertTrue(evidence.contains("\"inlineObject\":24"), evidence)
    }

    @Test
    fun styleDeltaListsOnlyPaintFields() {
        testTrace.section("styleDeltaListsOnlyPaintFields")
        val result = result(
            content = TiqianTextContent(
                text = "汉A字",
                spans = listOf(
                    TextSpan(range = TextRange(0, 1), style = TextStyle(fontSize = 20.0f, fontWeight = 700, italic = true)),
                    TextSpan(range = TextRange(1, 2), style = TextStyle(fontFamilies = listOf("Kai"))),
                ),
            ),
            clusters = listOf(
                Cluster(range = TextRange(0, 1), text = "汉", fontKey = "cjk", advance = 16.0f),
                Cluster(range = TextRange(1, 2), text = "A", fontKey = "latin", advance = 10.0f),
                Cluster(range = TextRange(2, 3), text = "字", fontKey = "cjk", advance = 16.0f),
            ),
            glyphRuns = listOf(
                GlyphRun(
                    range = TextRange(0, 1),
                    fontKey = "cjk",
                    glyphs = listOf(Glyph(id = 1u, clusterRange = TextRange(0, 1), advance = 16.0f)),
                    advance = 16.0f,
                ),
                GlyphRun(
                    range = TextRange(1, 2),
                    fontKey = "latin",
                    glyphs = listOf(Glyph(id = 2u, clusterRange = TextRange(1, 2), advance = 10.0f)),
                    advance = 10.0f,
                ),
                GlyphRun(
                    range = TextRange(2, 3),
                    fontKey = "cjk",
                    glyphs = listOf(Glyph(id = 3u, clusterRange = TextRange(2, 3), advance = 16.0f)),
                    advance = 16.0f,
                ),
            ),
            lines = listOf(line(TextRange(0, 3), 0..2, naturalWidth = 42.0f)),
        )
        val json = result.toPreparedParagraphJson(renderEvidence = true)
        assertTrue(json.contains("\"style\":{\"fontSize\":20,\"fontWeight\":700,\"italic\":true}"), json)
        // A style that differs only in non-paint fields still marks the
        // cluster, as an empty object.
        assertTrue(json.contains("\"style\":{}"), json)
        // The unstyled third cluster carries no style field; exactly two
        // style objects exist in the plan.
        assertEquals(2, json.split("\"style\":").size - 1)
    }

    @Test
    fun dashClusterEmitsShapingEvidenceBlock() {
        testTrace.section("dashClusterEmitsShapingEvidenceBlock")
        val result = result(
            content = TiqianTextContent("汉——"),
            clusters = listOf(
                Cluster(range = TextRange(0, 1), text = "汉", fontKey = "cjk", advance = 16.0f),
                Cluster(range = TextRange(1, 3), text = "——", fontKey = "cjk", advance = 32.0f),
            ),
            glyphRuns = listOf(
                GlyphRun(
                    range = TextRange(0, 1),
                    fontKey = "cjk",
                    glyphs = listOf(Glyph(id = 1u, clusterRange = TextRange(0, 1), advance = 16.0f)),
                    advance = 16.0f,
                ),
                GlyphRun(
                    range = TextRange(1, 3),
                    fontKey = "cjk",
                    glyphs = listOf(
                        Glyph(id = 9u, clusterRange = TextRange(1, 3), advance = 32.0f, renderFontKey = "Noto Sans CJK"),
                        Glyph(id = 10u, clusterRange = TextRange(1, 3), advance = 0.0f),
                    ),
                    advance = 32.0f,
                ),
            ),
            lines = listOf(line(TextRange(0, 3), 0..1, naturalWidth = 48.0f)),
            debug = LayoutDebugInfo(
                shapingDecisions = listOf(
                    ShapingDecisionInfo(
                        range = TextRange(1, 3),
                        sourceText = "——",
                        displayText = "——",
                        fontKey = "cjk",
                        glyphCount = 2,
                        advance = 32.0f,
                        source = "ShapingStage",
                        reason = "dash-reason",
                        strategy = "PairedEmDash",
                        language = "zh-Hans",
                        resolvedFace = "NotoSansCJK",
                    ),
                ),
            ),
        )
        val json = result.toPreparedParagraphJson(renderEvidence = true)
        assertTrue(json.contains("\"dashStrategy\":\"PairedEmDash\""), json)
        assertTrue(json.contains("\"shapingLanguage\":\"zh-Hans\""), json)
        assertTrue(json.contains("\"resolvedFace\":\"NotoSansCJK\""), json)
        assertTrue(json.contains("\"glyphIds\":\"9,10\""), json)
        assertTrue(json.contains("\"shapingEvidence\":\"dash-reason\""), json)
        // Both glyphs of the dash cluster widen the summed natural width.
        assertTrue(json.contains("\"naturalWidth\":32"), json)
    }

    @Test
    fun punctuationInkFloorAndLatinRoleMarkCells() {
        testTrace.section("punctuationInkFloorAndLatinRoleMarkCells")
        val result = result(
            content = TiqianTextContent("。A中"),
            clusters = listOf(
                Cluster(range = TextRange(0, 1), text = "。", fontKey = "cjk", advance = 16.0f),
                Cluster(range = TextRange(1, 2), text = "A", fontKey = "latin", advance = 10.0f),
                Cluster(range = TextRange(2, 3), text = "中", fontKey = "cjk", advance = 16.0f),
            ),
            glyphRuns = listOf(
                GlyphRun(
                    range = TextRange(0, 1),
                    fontKey = "cjk",
                    glyphs = listOf(Glyph(id = 1u, clusterRange = TextRange(0, 1), advance = 16.0f)),
                    advance = 16.0f,
                ),
                GlyphRun(
                    range = TextRange(1, 2),
                    fontKey = "latin",
                    glyphs = listOf(Glyph(id = 2u, clusterRange = TextRange(1, 2), advance = 10.0f)),
                    advance = 10.0f,
                ),
                GlyphRun(
                    range = TextRange(2, 3),
                    fontKey = "cjk",
                    glyphs = listOf(Glyph(id = 3u, clusterRange = TextRange(2, 3), advance = 16.0f)),
                    advance = 16.0f,
                ),
            ),
            lines = listOf(line(TextRange(0, 3), 0..2, naturalWidth = 42.0f)),
            debug = LayoutDebugInfo(
                punctuationDecisions = listOf(
                    PunctuationDecisionInfo(
                        range = TextRange(0, 1),
                        char = '。',
                        punctuationClass = "PauseOrStop",
                        advance = 16.0f,
                        bodyWidth = 16.0f,
                        leadingGlueNatural = 0.0f,
                        trailingGlueNatural = 0.0f,
                        anchor = "centre",
                        inkContainmentBodyFloor = 6.0f,
                        inkContainmentApplied = true,
                    ),
                    PunctuationDecisionInfo(
                        range = TextRange(1, 2),
                        char = 'A',
                        punctuationClass = "Other",
                        advance = 10.0f,
                        bodyWidth = 10.0f,
                        leadingGlueNatural = 0.0f,
                        trailingGlueNatural = 0.0f,
                        anchor = "centre",
                        inkContainmentApplied = true,
                    ),
                    PunctuationDecisionInfo(
                        range = TextRange(2, 3),
                        char = '中',
                        punctuationClass = "Other",
                        advance = 16.0f,
                        bodyWidth = 16.0f,
                        leadingGlueNatural = 0.0f,
                        trailingGlueNatural = 0.0f,
                        anchor = "centre",
                        inkContainmentApplied = false,
                    ),
                ),
                fontDecisions = listOf(
                    FontDecisionInfo(
                        range = TextRange(1, 2),
                        sourceText = "A",
                        displayText = "A",
                        role = FontRole.LatinText.name,
                        fontKey = "latin",
                        reason = "latin-run",
                        substitutionReason = "none",
                    ),
                ),
            ),
        )
        val json = result.toPreparedParagraphJson(renderEvidence = true)
        assertTrue(json.contains("\"punctuationInkFloor\":6"), json)
        assertTrue(json.contains("\"punctuationBodyWidth\":16"), json)
        // Applied with no floor emits nothing; not applied emits nothing.
        assertEquals(1, json.split("\"punctuationInkFloor\":").size - 1)
        assertTrue(json.contains("\"latin\":true"), json)
    }

    @Test
    fun zeroWidthBreakClusterSurvivesEmptyDisplayText() {
        testTrace.section("zeroWidthBreakClusterSurvivesEmptyDisplayText")
        val result = result(
            content = TiqianTextContent("汉​字"),
            clusters = listOf(
                Cluster(range = TextRange(0, 1), text = "汉", fontKey = "cjk", advance = 16.0f),
                Cluster(range = TextRange(1, 2), text = "​", displayText = "", fontKey = "cjk", advance = 0.0f),
                Cluster(range = TextRange(2, 3), text = "字", fontKey = "cjk", advance = 16.0f),
            ),
            glyphRuns = listOf(
                GlyphRun(
                    range = TextRange(0, 1),
                    fontKey = "cjk",
                    glyphs = listOf(Glyph(id = 1u, clusterRange = TextRange(0, 1), advance = 16.0f)),
                    advance = 16.0f,
                ),
                GlyphRun(
                    range = TextRange(2, 3),
                    fontKey = "cjk",
                    glyphs = listOf(Glyph(id = 3u, clusterRange = TextRange(2, 3), advance = 16.0f)),
                    advance = 16.0f,
                ),
            ),
            lines = listOf(line(TextRange(0, 3), 0..2, naturalWidth = 32.0f)),
            debug = LayoutDebugInfo(
                zeroWidthBreakDecisions = listOf(
                    ZeroWidthBreakDecisionInfo(range = TextRange(1, 2), sourceText = "​", clusterIndex = 1),
                ),
                shapingDecisions = listOf(
                    ShapingDecisionInfo(
                        range = TextRange(1, 2),
                        sourceText = "​",
                        displayText = "",
                        fontKey = "cjk",
                        glyphCount = 0,
                        advance = 0.0f,
                        source = "ShapingStage",
                        reason = "no-shape",
                        strategy = "ZeroWidthNoShape",
                    ),
                ),
            ),
        )
        val json = result.toPreparedParagraphJson()
        // The empty-display zero-width cluster stays a cell.
        assertTrue(json.contains("\"display\":\"\",\"drawX\":16"), json)
        assertEquals(3, json.split("\"source\":").size - 1)
        // With render evidence the glyph-less cluster falls back to its own
        // advance and emits the dash block without optional evidence fields.
        val evidence = result.toPreparedParagraphJson(renderEvidence = true)
        assertTrue(evidence.contains("\"dashStrategy\":\"ZeroWidthNoShape\""), evidence)
        assertTrue(evidence.contains("\"shapingEvidence\":\"no-shape\""), evidence)
        assertFalse(evidence.contains("shapingLanguage"), evidence)
        assertFalse(evidence.contains("resolvedFace"), evidence)
        assertFalse(evidence.contains("glyphIds"), evidence)
    }

    @Test
    fun paragraphEvidenceEmitsEverySection() {
        testTrace.section("paragraphEvidenceEmitsEverySection")
        val result = result(
            content = TiqianTextContent("汉注"),
            decorations = listOf(
                DecorationSpan(range = TextRange(0, 1), kind = DecorationKind.Emphasis),
                DecorationSpan(range = TextRange(1, 2), kind = DecorationKind.Emphasis),
            ),
            inlineBoxes = listOf(
                InlineBoxSpan(range = TextRange(0, 1), inlineStart = 2.0f),
                InlineBoxSpan(range = TextRange(0, 1), inlineStart = 0.5f),
                InlineBoxSpan(range = TextRange(1, 2), inlineEnd = 3.0f),
                InlineBoxSpan(range = TextRange(0, 2), inlineEnd = 1.5f),
                InlineBoxSpan(range = TextRange(1, 2), inlineStart = 0.0f, inlineEnd = 0.0f),
            ),
            clusters = listOf(
                Cluster(range = TextRange(0, 1), text = "汉", fontKey = "cjk", advance = 16.0f),
                Cluster(range = TextRange(1, 2), text = "注", fontKey = "cjk", advance = 16.0f),
            ),
            glyphRuns = listOf(
                GlyphRun(
                    range = TextRange(0, 2),
                    fontKey = "cjk",
                    glyphs = listOf(
                        Glyph(id = 1u, clusterRange = TextRange(0, 1), advance = 16.0f),
                        Glyph(id = 2u, clusterRange = TextRange(1, 2), advance = 16.0f),
                    ),
                    advance = 32.0f,
                ),
            ),
            lines = listOf(line(TextRange(0, 2), 0..1, naturalWidth = 32.0f)),
            debug = LayoutDebugInfo(
                rubyDecisions = listOf(
                    RubyDecisionInfo(
                        baseRange = TextRange(0, 1),
                        text = "hàn",
                        lineIndex = 0,
                        centerX = 8.0f,
                        baselineY = 2.0f,
                        fontSize = 8.0f,
                        ascent = 6.0f,
                        overhang = 0.5f,
                        fontFamilies = listOf("RubyKai", "RubyLatin"),
                    ),
                    RubyDecisionInfo(
                        baseRange = TextRange(1, 2),
                        text = "zhù",
                        lineIndex = 0,
                        centerX = 24.0f,
                        baselineY = 2.0f,
                        fontSize = 8.0f,
                        overhang = 0.0f,
                    ),
                ),
                bopomofoDecisions = listOf(
                    BopomofoDecisionInfo(
                        baseRange = TextRange(1, 2),
                        text = "ㄓㄨˋ",
                        lineIndex = 0,
                        placements = listOf(
                            BopomofoGlyphPlacement(text = "ㄓ", left = 1.0f, top = 2.0f, width = 4.0f, height = 4.0f, role = BopomofoGlyphRole.Symbol),
                            BopomofoGlyphPlacement(text = "ˋ", left = 2.0f, top = 0.0f, width = 2.0f, height = 2.0f, role = BopomofoGlyphRole.Tone),
                        ),
                        fontFamilies = listOf("BopomofoKai", "BopomofoLatin"),
                    ),
                    BopomofoDecisionInfo(
                        baseRange = TextRange(0, 1),
                        text = "ㄏㄢˋ",
                        lineIndex = 0,
                        placements = listOf(
                            BopomofoGlyphPlacement(text = "ㄏ", left = 0.0f, top = 2.0f, width = 4.0f, height = 4.0f, role = BopomofoGlyphRole.Symbol),
                        ),
                    ),
                ),
                decorationSegments = listOf(
                    DecorationSegmentInfo(
                        sourceRange = TextRange(0, 1),
                        kind = DecorationKind.ProperNoun.name,
                        lineIndex = 0,
                        left = 0.0f,
                        top = 20.0f,
                        right = 16.0f,
                        bottom = 22.0f,
                        openStart = false,
                        openEnd = false,
                        reason = "proper-noun",
                    ),
                    DecorationSegmentInfo(
                        sourceRange = TextRange(1, 2),
                        kind = DecorationKind.BookTitle.name,
                        lineIndex = 0,
                        left = 16.0f,
                        top = 20.0f,
                        right = 32.0f,
                        bottom = 22.0f,
                        openStart = false,
                        openEnd = false,
                        reason = "book-title",
                    ),
                    DecorationSegmentInfo(
                        sourceRange = TextRange(0, 2),
                        kind = DecorationKind.Emphasis.name,
                        lineIndex = 0,
                        left = 0.0f,
                        top = 0.0f,
                        right = 32.0f,
                        bottom = 4.0f,
                        openStart = false,
                        openEnd = false,
                        reason = "filtered",
                    ),
                ),
                decorationDecisions = listOf(
                    DecorationDecisionInfo(
                        clusterRange = TextRange(0, 1),
                        sourceText = "汉",
                        kind = DecorationKind.Emphasis.name,
                        applied = true,
                        reason = "dot-applied",
                        anchorX = 8.0f,
                        anchorY = 22.0f,
                        dotDiameter = 2.0f,
                    ),
                    DecorationDecisionInfo(
                        clusterRange = TextRange(1, 2),
                        sourceText = "注",
                        kind = DecorationKind.Emphasis.name,
                        applied = true,
                        reason = "dot-without-size",
                        anchorX = 24.0f,
                        anchorY = 22.0f,
                        dotDiameter = 0.0f,
                    ),
                    DecorationDecisionInfo(
                        clusterRange = TextRange(1, 2),
                        sourceText = "注",
                        kind = DecorationKind.Emphasis.name,
                        applied = false,
                        reason = "dot-skipped",
                        anchorX = 24.0f,
                        anchorY = 22.0f,
                        dotDiameter = 2.0f,
                    ),
                ),
            ),
        )
        val json = result.toPreparedParagraphJson(renderEvidence = true)
        assertTrue(json.contains("\"emphasisRanges\":[[0,1],[1,2]]"), json)
        // Two boxes at offset 0 accumulate one inlineStart, two boxes at
        // offset 2 accumulate one inlineEnd; the all-zero box contributes
        // nothing.
        assertTrue(
            json.contains(
                "\"inlineEdges\":[{\"offset\":0,\"inlineStart\":2.5},{\"offset\":2,\"inlineEnd\":4.5}]",
            ),
            json,
        )
        assertTrue(json.contains("\"rubyDecisions\":[{\"baseRangeStart\":0"), json)
        assertTrue(json.contains("\"ascent\":6"), json)
        assertTrue(json.contains("\"fontFamilies\":[\"RubyKai\",\"RubyLatin\"]"), json)
        assertTrue(json.contains("\"bopomofoDecisions\":[{\"baseRangeStart\":1"), json)
        assertTrue(json.contains("\"role\":\"Symbol\""), json)
        assertTrue(json.contains("\"role\":\"Tone\""), json)
        assertTrue(json.contains("\"fontFamilies\":[\"BopomofoKai\",\"BopomofoLatin\"]"), json)
        assertTrue(json.contains("\"decorationSegments\":[{\"kind\":\"ProperNoun\""), json)
        assertTrue(json.contains("\"kind\":\"BookTitle\""), json)
        assertFalse(json.contains("Emphasis"), json)
        assertTrue(json.contains("\"emphasisDots\":[{\"clusterRangeStart\":0,\"anchorX\":8,\"anchorY\":22,\"dotDiameter\":2}]"), json)
        assertTrue(json.contains("\"fontSize\":16"), json)
        assertTrue(json.contains("\"overlayWidth\":480"), json)
    }

    @Test
    fun negativeZeroAndExponentWidthsNormalize() {
        testTrace.section("negativeZeroAndExponentWidthsNormalize")
        val result = result(
            width = f32Literal(1.0e21f),
            height = -0.0f,
            clusters = listOf(
                Cluster(range = TextRange(0, 1), text = "汉", fontKey = "cjk", advance = 16.0f),
            ),
            glyphRuns = listOf(
                GlyphRun(
                    range = TextRange(0, 1),
                    fontKey = "cjk",
                    glyphs = listOf(Glyph(id = 1u, clusterRange = TextRange(0, 1), advance = 16.0f)),
                    advance = 16.0f,
                ),
            ),
            lines = listOf(
                line(TextRange(0, 1), 0..0).copy(indent = -0.0f, hyphenAdvance = -0.0f),
            ),
        )
        val json = result.toPreparedParagraphJson()
        // The Float nearest 1e21 widens to 1.0000000200408773e+21.
        assertTrue(json.contains("\"width\":1.0000000200408773e+21"), json)
        assertTrue(json.contains("\"height\":0"), json)
        assertTrue(json.contains("\"indent\":0"), json)
        assertTrue(json.contains("\"hyphenAdvance\":0"), json)
    }

    @Test
    fun jsonStringEscapesQuotesBackslashesAndControlCharacters() {
        testTrace.section("jsonStringEscapesQuotesBackslashesAndControlCharacters")
        val tricky = "\"\\\b\u000C\n\r\t\u0001"
        val result = result(
            content = TiqianTextContent(tricky),
            clusters = listOf(
                Cluster(range = TextRange(0, 8), text = tricky, fontKey = "cjk", advance = 8.0f),
            ),
            glyphRuns = listOf(
                GlyphRun(
                    range = TextRange(0, 8),
                    fontKey = "cjk",
                    glyphs = listOf(Glyph(id = 1u, clusterRange = TextRange(0, 8), advance = 8.0f)),
                    advance = 8.0f,
                ),
            ),
            lines = listOf(line(TextRange(0, 8), 0..0, naturalWidth = 8.0f)),
        )
        val json = result.toPreparedParagraphJson()
        assertTrue(json.contains("\\\""), json)
        assertTrue(json.contains("\\\\"), json)
        assertTrue(json.contains("\\b"), json)
        assertTrue(json.contains("\\f"), json)
        assertTrue(json.contains("\\n"), json)
        assertTrue(json.contains("\\r"), json)
        assertTrue(json.contains("\\t"), json)
        assertTrue(json.contains("\\u0001"), json)
    }

    @Test
    fun planWithDiagnosticsListsCapabilityIssuesAndAdvanceSuspects() {
        testTrace.section("planWithDiagnosticsListsCapabilityIssuesAndAdvanceSuspects")
        val result = result(
            content = TiqianTextContent("汉零臣"),

            clusters = listOf(
                Cluster(range = TextRange(0, 1), text = "汉", fontKey = "cjk", advance = 32.0f),
                Cluster(range = TextRange(1, 2), text = "零", fontKey = "cjk", advance = 0.0f),
                Cluster(range = TextRange(2, 3), text = "臣", fontKey = "cjk", advance = 16.0f),
            ),
            glyphRuns = listOf(
                GlyphRun(
                    range = TextRange(0, 1),
                    fontKey = "cjk",
                    glyphs = listOf(Glyph(id = 1u, clusterRange = TextRange(0, 1), advance = 32.0f)),
                    advance = 32.0f,
                ),
                GlyphRun(
                    range = TextRange(1, 2),
                    fontKey = "cjk",
                    glyphs = listOf(Glyph(id = 2u, clusterRange = TextRange(1, 2), advance = 0.0f)),
                    advance = 0.0f,
                ),
                GlyphRun(
                    range = TextRange(2, 3),
                    fontKey = "cjk",
                    glyphs = listOf(Glyph(id = 3u, clusterRange = TextRange(2, 3), advance = 16.0f)),
                    advance = 16.0f,
                ),
            ),
            lines = listOf(line(TextRange(0, 3), 0..2, naturalWidth = 48.0f)),
            debug = LayoutDebugInfo(
                shapingDecisions = listOf(
                    ShapingDecisionInfo(
                        range = TextRange(0, 1),
                        sourceText = "汉",
                        displayText = "汉",
                        fontKey = "cjk",
                        glyphCount = 1,
                        advance = 32.0f,
                        source = "ShapingStage",
                        reason = "capability-reason",
                        capabilityIssue = "InvalidWebShapingAdvance",
                    ),
                    ShapingDecisionInfo(
                        range = TextRange(2, 3),
                        sourceText = "臣",
                        displayText = "臣",
                        fontKey = "cjk",
                        glyphCount = 1,
                        advance = Float.POSITIVE_INFINITY,
                        source = "ShapingStage",
                        reason = "infinite-capability",
                        capabilityIssue = "MissingInkBoundsFallback",
                    ),
                    ShapingDecisionInfo(
                        range = TextRange(1, 2),
                        sourceText = "零",
                        displayText = "零",
                        fontKey = "cjk",
                        glyphCount = 1,
                        advance = 0.0f,
                        source = "ShapingStage",
                        reason = "zero-advance",
                    ),
                    ShapingDecisionInfo(
                        range = TextRange(2, 3),
                        sourceText = "臣",
                        displayText = "臣",
                        fontKey = "cjk",
                        glyphCount = 1,
                        advance = Float.NaN,
                        source = "ShapingStage",
                        reason = "nan-advance",
                    ),
                    ShapingDecisionInfo(
                        range = TextRange(2, 3),
                        sourceText = "臣",
                        displayText = "臣",
                        fontKey = "cjk",
                        glyphCount = 1,
                        advance = Float.POSITIVE_INFINITY,
                        source = "ShapingStage",
                        reason = "infinite-advance",
                    ),
                ),
            ),
        )
        val envelope = result.toPlanWithDiagnosticsJson(renderEvidence = false, zeroAdvanceEpsilonPx = 0.5f)
        val diagnostics = envelope.substringAfter("\"diagnostics\":")
        assertTrue(diagnostics.contains("\"name\":\"InvalidWebShapingAdvance\""), envelope)
        assertTrue(diagnostics.contains("\"reason\":\"capability-reason\""), envelope)
        assertTrue(diagnostics.contains("\"rangeStart\":0"), envelope)
        assertTrue(diagnostics.contains("\"rangeEnd\":1"), envelope)
        assertTrue(diagnostics.contains("\"displayText\":\"零\""), envelope)
        assertTrue(diagnostics.contains("\"advance\":\"0\""), envelope)
        assertTrue(diagnostics.contains("\"advance\":\"NaN\""), envelope)
        assertTrue(diagnostics.contains("\"advance\":\"Infinity\""), envelope)
        // The healthy finite advance stays out of the suspects list.
        assertFalse(diagnostics.contains("\"advance\":\"32\""), envelope)
        // The plan itself is embedded as an escaped JSON string value.
        assertTrue(envelope.startsWith("{\"plan\":\""), envelope.take(20))
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
