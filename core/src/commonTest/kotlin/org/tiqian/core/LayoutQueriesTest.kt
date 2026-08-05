package org.tiqian.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LayoutQueriesTest {

    @Test
    fun positionedClustersFollowLineIndentAndAdvance() {
        val result = sampleResult()

        val positioned = result.positionedClusters()

        assertEquals(Rect(4f, 0f, 14f, 20f), positioned[0].rect)
        assertEquals(Rect(14f, 0f, 34f, 20f), positioned[1].rect)
        assertEquals(Rect(0f, 20f, 10f, 40f), positioned[2].rect)
    }

    @Test
    fun positionedClustersSeparateOccupiedBoxFromAutoSpaceDrawOrigin() {
        val result = LayoutResult(
            input = LayoutInput(
                content = TiqianTextContent("中Hi"),
                textStyle = TextStyle(fontSize = 10f),
                constraints = LayoutConstraints(maxWidth = 40f),
            ),
            size = Size(32.5f, 20f),
            clusters = listOf(
                Cluster(TextRange(0, 1), "中", fontKey = "cjk", advance = 10f),
                Cluster(TextRange(1, 3), "Hi", fontKey = "latin", advance = 22.5f),
            ),
            glyphRuns = emptyList(),
            lines = listOf(
                LineBox(
                    range = TextRange(0, 3),
                    clusterRange = 0..1,
                    baseline = 15f,
                    top = 0f,
                    bottom = 20f,
                    naturalWidth = 32.5f,
                    adjustedWidth = 32.5f,
                    visualWidth = 32.5f,
                ),
            ),
            debug = LayoutDebugInfo(
                autoSpaceDecisions = listOf(
                    AutoSpaceDecisionInfo(
                        clusterRange = TextRange(1, 3),
                        side = "leading",
                        boundaryRole = "CjkLatin",
                        mode = "Insert",
                        charactersAffected = 1,
                        reductionPerChar = -2.5f,
                        totalReduction = -2.5f,
                        reason = "TextAutoSpaceInsert:ideograph-alpha:quarter-em",
                    ),
                ),
            ),
        )

        val positioned = result.positionedClusters()

        assertEquals(Rect(10f, 0f, 32.5f, 20f), positioned[1].rect)
        assertEquals(12.5f, positioned[1].drawX)
        assertEquals(Rect(10f, 0f, 32.5f, 20f), result.getBoundingBox(1))
        assertEquals(1, result.getOffsetForPosition(11f, 5f))
    }

    @Test
    fun positionedClustersSeparateOccupiedBoxFromConsumedLeadingGlueDrawOrigin() {
        val result = LayoutResult(
            input = LayoutInput(
                content = TiqianTextContent("（"),
                textStyle = TextStyle(fontSize = 10f),
                constraints = LayoutConstraints(maxWidth = 10f),
            ),
            size = Size(6f, 20f),
            clusters = listOf(
                Cluster(TextRange(0, 1), "（", fontKey = "cjk", advance = 6f),
            ),
            glyphRuns = emptyList(),
            lines = listOf(
                LineBox(
                    range = TextRange(0, 1),
                    clusterRange = 0..0,
                    baseline = 15f,
                    top = 0f,
                    bottom = 20f,
                    naturalWidth = 6f,
                    adjustedWidth = 6f,
                    visualWidth = 6f,
                ),
            ),
            debug = LayoutDebugInfo(
                geometryDecisions = listOf(
                    ClusterGeometryDecisionInfo(
                        range = TextRange(0, 1),
                        sourceText = "（",
                        displayText = "（",
                        baseAdvance = 10f,
                        bodyWidth = 6f,
                        leadingGlueNatural = 4f,
                        leadingGlueConsumed = 4f,
                        trailingGlueNatural = 0f,
                        trailingGlueConsumed = 0f,
                        justificationDelta = 0f,
                        resolvedAdvance = 6f,
                        source = "test",
                        reason = "LineStartOpeningPunctuationTrim",
                    ),
                ),
            ),
        )

        val positioned = result.positionedClusters().single()

        assertEquals(Rect(0f, 0f, 6f, 20f), positioned.rect)
        assertEquals(-4f, positioned.drawX)
        assertEquals(Rect(0f, 0f, 1f, 20f), result.getCursorRect(0))
        assertEquals(0, result.getOffsetForPosition(-3f, 5f))
    }

    @Test
    fun glyphInkBoundsKeepItalicOverhangSeparateFromOccupiedGeometry() {
        val result = LayoutResult(
            input = LayoutInput(
                content = TiqianTextContent("f"),
                textStyle = TextStyle(fontSize = 10f),
                constraints = LayoutConstraints(maxWidth = 10f),
            ),
            size = Size(10f, 20f),
            clusters = listOf(
                Cluster(TextRange(0, 1), "f", fontKey = "latin", advance = 10f),
            ),
            glyphRuns = listOf(
                GlyphRun(
                    range = TextRange(0, 1),
                    fontKey = "latin",
                    glyphs = listOf(
                        Glyph(
                            id = 1u,
                            clusterRange = TextRange(0, 1),
                            advance = 10f,
                            bounds = Rect(-3f, -9f, 12f, 2f),
                        ),
                    ),
                    advance = 10f,
                ),
            ),
            lines = listOf(
                LineBox(
                    range = TextRange(0, 1),
                    clusterRange = 0..0,
                    baseline = 14f,
                    top = 0f,
                    bottom = 20f,
                    naturalWidth = 10f,
                    adjustedWidth = 10f,
                    visualWidth = 10f,
                ),
            ),
        )

        assertEquals(Rect(0f, 0f, 10f, 20f), result.positionedClusters().single().rect)
        assertEquals(Rect(-3f, 5f, 12f, 16f), result.glyphInkBounds())
    }

    @Test
    fun lineAndBoxQueriesUseTiqianLineGeometry() {
        val result = sampleResult()

        assertEquals(0, result.getLineForOffset(1))
        assertEquals(1, result.getLineForOffset(3))
        assertEquals(Rect(14f, 0f, 34f, 20f), result.getBoundingBox(1))
        assertEquals(Rect(10f, 20f, 11f, 40f), result.getCursorRect(4))
    }

    @Test
    fun rangeBoxesSplitMultiUnitClustersBySourceRange() {
        val result = sampleResult()

        val boxes = result.getBoundingBoxes(TextRange(2, 4))

        assertEquals(
            listOf(
                Rect(24f, 0f, 34f, 20f),
                Rect(0f, 20f, 10f, 40f),
            ),
            boxes,
        )
    }

    @Test
    fun richTextSegmentsReusePositionedClusterGeometryAndSplitLines() {
        val result = sampleResult()
        val span = RichTextSpan(
            TextRange(1, 4),
            RichTextRole.Background,
            RichTextPaint(0x33FF0000),
        )

        val segments = result.positionedRichTextSegments(listOf(span))

        assertEquals(2, segments.size)
        assertEquals(TextRange(1, 3), segments[0].range)
        assertEquals(Rect(14f, 0f, 34f, 20f), segments[0].rect)
        assertEquals(TextRange(3, 4), segments[1].range)
        assertEquals(Rect(0f, 20f, 10f, 40f), segments[1].rect)
        assertEquals(span, segments[0].span)
    }

    @Test
    fun richTextDecorationTrimsOnlyOuterPunctuationGlue() {
        val result = punctuationGlueResult()
        val underline = RichTextSpan(
            TextRange(0, 4),
            RichTextRole.Underline,
        )

        val occupied = result.positionedRichTextSegments(listOf(underline)).single()
        val decoration = result.trimmedRichTextDecorationSegments(listOf(occupied)).single()

        assertEquals(Rect(0f, 0f, 40f, 20f), occupied.rect)
        assertEquals(Rect(5f, 0f, 35f, 20f), decoration.rect)
        assertEquals(TextRange(0, 4), decoration.range)
    }

    @Test
    fun richTextDecorationKeepsPunctuationGlueInsideItsRange() {
        val result = punctuationGlueResult()
        val underline = RichTextSpan(
            TextRange(1, 4),
            RichTextRole.Underline,
        )

        val decoration = result.trimmedRichTextDecorationSegments(
            result.positionedRichTextSegments(listOf(underline)),
        ).single()

        // The closing mark at 1..2 is internal, so its trailing glue remains part of the
        // continuous line; only the final closing mark's outer trailing glue is removed.
        assertEquals(Rect(10f, 0f, 35f, 20f), decoration.rect)
    }

    @Test
    fun richTextDecorationDoesNotTrimAlreadyConsumedOpeningGlueTwice() {
        val original = punctuationGlueResult()
        val result = original.copy(
            debug = original.debug.copy(
                geometryDecisions = original.debug.geometryDecisions.map { geometry ->
                    if (geometry.range == TextRange(0, 1)) {
                        geometry.copy(leadingGlueConsumed = geometry.leadingGlueNatural)
                    } else {
                        geometry
                    }
                },
            ),
        )
        val underline = RichTextSpan(TextRange(0, 1), RichTextRole.Underline)

        val decoration = result.trimmedRichTextDecorationSegments(
            result.positionedRichTextSegments(listOf(underline)),
        ).single()

        assertEquals(0f, decoration.left)
    }

    @Test
    fun customLineStylesReuseTheRendererUnderlineHeight() {
        val result = punctuationGlueResult()
        val underline = RichTextSpan(TextRange(0, 4), RichTextRole.Underline)
        val segment = result.trimmedRichTextDecorationSegments(
            result.positionedRichTextSegments(listOf(underline)),
        ).single()

        assertEquals(
            segment.baseline + result.input.textStyle.fontSize * 0.18f,
            result.richTextDecorationLineY(segment, strokeWidth = 1f),
            0.001f,
        )
    }

    @Test
    fun hitTestingChoosesOffsetFromTiqianClusterAdvances() {
        val result = sampleResult()

        assertEquals(0, result.getOffsetForPosition(3f, 5f))
        assertEquals(1, result.getOffsetForPosition(18f, 5f))
        assertEquals(2, result.getOffsetForPosition(24f, 5f))
        assertEquals(3, result.getOffsetForPosition(4f, 25f))
        assertEquals(4, result.getOffsetForPosition(30f, 25f))
    }

    @Test
    fun selectionHitTestingKeepsSupportedSourceSequencesAtomic() {
        val result = interactionBoundaryResult()

        assertEquals(0, result.getSelectionOffsetForPosition(5f, 10f))
        assertEquals(2, result.getSelectionOffsetForPosition(15f, 10f))
        assertEquals(2, result.getSelectionOffsetForPosition(25f, 10f))
        assertEquals(4, result.getSelectionOffsetForPosition(35f, 10f))
        assertEquals(4, result.getSelectionOffsetForPosition(45f, 10f))
        assertEquals(9, result.getSelectionOffsetForPosition(75f, 10f))
    }

    @Test
    fun externalSelectionOffsetsRespectDirectionalBoundaryBias() {
        val result = interactionBoundaryResult()

        assertEquals(2, result.coerceSelectionOffset(3, SourceBoundaryBias.Backward))
        assertEquals(4, result.coerceSelectionOffset(3, SourceBoundaryBias.Forward))
        assertEquals(4, result.coerceSelectionOffset(3, SourceBoundaryBias.Nearest))
        assertEquals(4, result.coerceSelectionOffset(6, SourceBoundaryBias.Backward))
        assertEquals(9, result.coerceSelectionOffset(6, SourceBoundaryBias.Forward))
    }

    @Test
    fun supportedSourceSequenceRemainsAtomicAcrossEngineClusterBoundaries() {
        val result = crossClusterInteractionBoundaryResult()

        assertEquals(0, result.coerceSelectionOffset(1, SourceBoundaryBias.Backward))
        assertEquals(2, result.coerceSelectionOffset(1, SourceBoundaryBias.Forward))
        assertEquals(0, result.getSelectionOffsetForPosition(8f, 10f))
        assertEquals(2, result.getSelectionOffsetForPosition(12f, 10f))
    }

    @Test
    fun selectionWordBoundaryExpandsLatinButKeepsHanAtomic() {
        val result = wordBoundaryResult()

        assertEquals(TextRange(2, 10), result.getSelectionWordBoundary(6))
        assertEquals(TextRange(0, 1), result.getSelectionWordBoundary(0))
        assertEquals(TextRange(1, 2), result.getSelectionWordBoundary(1))
        assertEquals(TextRange(11, 12), result.getSelectionWordBoundary(12))
        assertEquals(TextRange(0, 1), result.getSelectionWordBoundaryForPosition(5f, 10f))
        assertEquals(TextRange(2, 10), result.getSelectionWordBoundaryForPosition(60f, 10f))
    }

    @Test
    fun rubySelectionGeometryRedistributesAvoidanceSpreadWithoutOverlap() {
        val result = rubySelectionResult()

        val positioned = result.positionedClusters()

        assertEquals(Rect(-6f, 0f, 26f, 20f), positioned[0].rect)
        assertEquals(Rect(29f, 0f, 61f, 20f), positioned[1].rect)
        assertEquals(Rect(64f, 0f, 96f, 20f), positioned[2].rect)
        assertTrue(
            positioned.zipWithNext().all { (left, right) -> left.right <= right.left },
            "ruby selection rects must not overlap: $positioned",
        )
        assertEquals(Rect(-6f, 0f, 26f, 20f), result.getBoundingBoxes(TextRange(0, 1)).single())
        assertEquals(Rect(29f, 0f, 61f, 20f), result.getBoundingBoxes(TextRange(1, 2)).single())
    }

    private fun sampleResult(): LayoutResult =
        LayoutResult(
            input = LayoutInput(
                content = TiqianTextContent("甲——乙"),
                textStyle = TextStyle(fontSize = 10f),
                constraints = LayoutConstraints(maxWidth = 40f),
            ),
            size = Size(34f, 40f),
            clusters = listOf(
                Cluster(TextRange(0, 1), "甲", fontKey = "cjk", advance = 10f),
                Cluster(TextRange(1, 3), "——", "⸺", fontKey = "cjk", advance = 20f),
                Cluster(TextRange(3, 4), "乙", fontKey = "cjk", advance = 10f),
            ),
            glyphRuns = emptyList(),
            lines = listOf(
                LineBox(
                    range = TextRange(0, 3),
                    clusterRange = 0..1,
                    baseline = 15f,
                    top = 0f,
                    bottom = 20f,
                    naturalWidth = 30f,
                    adjustedWidth = 30f,
                    visualWidth = 30f,
                    indent = 4f,
                ),
                LineBox(
                    range = TextRange(3, 4),
                    clusterRange = 2..2,
                    baseline = 35f,
                    top = 20f,
                    bottom = 40f,
                    naturalWidth = 10f,
                    adjustedWidth = 10f,
                    visualWidth = 10f,
                ),
            ),
        )

    private fun punctuationGlueResult(): LayoutResult =
        LayoutResult(
            input = LayoutInput(
                content = TiqianTextContent("（，中）"),
                textStyle = TextStyle(fontSize = 10f),
                constraints = LayoutConstraints(maxWidth = 40f),
            ),
            size = Size(40f, 20f),
            clusters = listOf(
                Cluster(TextRange(0, 1), "（", fontKey = "cjk", advance = 10f),
                Cluster(TextRange(1, 2), "，", fontKey = "cjk", advance = 10f),
                Cluster(TextRange(2, 3), "中", fontKey = "cjk", advance = 10f),
                Cluster(TextRange(3, 4), "）", fontKey = "cjk", advance = 10f),
            ),
            glyphRuns = emptyList(),
            lines = listOf(
                LineBox(
                    range = TextRange(0, 4),
                    clusterRange = 0..3,
                    baseline = 15f,
                    top = 0f,
                    bottom = 20f,
                    naturalWidth = 40f,
                    adjustedWidth = 40f,
                    visualWidth = 40f,
                ),
            ),
            debug = LayoutDebugInfo(
                geometryDecisions = listOf(
                    punctuationGeometry(TextRange(0, 1), "（", leadingGlue = 5f),
                    punctuationGeometry(TextRange(1, 2), "，", trailingGlue = 5f),
                    punctuationGeometry(TextRange(2, 3), "中"),
                    punctuationGeometry(TextRange(3, 4), "）", trailingGlue = 5f),
                ),
            ),
        )

    private fun interactionBoundaryResult(): LayoutResult =
        LayoutResult(
            input = LayoutInput(
                content = TiqianTextContent("😀e\u0301👩‍👩"),
                textStyle = TextStyle(fontSize = 10f),
                constraints = LayoutConstraints(maxWidth = 90f),
            ),
            size = Size(90f, 20f),
            clusters = listOf(
                Cluster(TextRange(0, 2), "😀", fontKey = "emoji", advance = 20f),
                Cluster(TextRange(2, 4), "e\u0301", fontKey = "latin", advance = 20f),
                Cluster(TextRange(4, 9), "👩‍👩", fontKey = "emoji", advance = 50f),
            ),
            glyphRuns = emptyList(),
            lines = listOf(
                LineBox(
                    range = TextRange(0, 9),
                    clusterRange = 0..2,
                    baseline = 15f,
                    top = 0f,
                    bottom = 20f,
                    naturalWidth = 90f,
                    adjustedWidth = 90f,
                    visualWidth = 90f,
                ),
            ),
        )

    private fun wordBoundaryResult(): LayoutResult =
        LayoutResult(
            input = LayoutInput(
                content = TiqianTextContent("前 template 后"),
                textStyle = TextStyle(fontSize = 10f),
                constraints = LayoutConstraints(maxWidth = 120f),
            ),
            size = Size(120f, 20f),
            clusters = listOf(
                Cluster(TextRange(0, 1), "前", fontKey = "cjk", advance = 10f),
                Cluster(TextRange(1, 2), " ", fontKey = "latin", advance = 10f),
                Cluster(TextRange(2, 10), "template", fontKey = "latin", advance = 80f),
                Cluster(TextRange(10, 11), " ", fontKey = "latin", advance = 10f),
                Cluster(TextRange(11, 12), "后", fontKey = "cjk", advance = 10f),
            ),
            glyphRuns = emptyList(),
            lines = listOf(
                LineBox(
                    range = TextRange(0, 12),
                    clusterRange = 0..4,
                    baseline = 15f,
                    top = 0f,
                    bottom = 20f,
                    naturalWidth = 120f,
                    adjustedWidth = 120f,
                    visualWidth = 120f,
                ),
            ),
        )

    private fun crossClusterInteractionBoundaryResult(): LayoutResult =
        LayoutResult(
            input = LayoutInput(
                content = TiqianTextContent("e\u0301"),
                textStyle = TextStyle(fontSize = 10f),
                constraints = LayoutConstraints(maxWidth = 20f),
            ),
            size = Size(20f, 20f),
            clusters = listOf(
                Cluster(TextRange(0, 1), "e", fontKey = "latin", advance = 10f),
                Cluster(TextRange(1, 2), "\u0301", fontKey = "latin", advance = 10f),
            ),
            glyphRuns = emptyList(),
            lines = listOf(
                LineBox(
                    range = TextRange(0, 2),
                    clusterRange = 0..1,
                    baseline = 15f,
                    top = 0f,
                    bottom = 20f,
                    naturalWidth = 20f,
                    adjustedWidth = 20f,
                    visualWidth = 20f,
                ),
            ),
        )

    private fun punctuationGeometry(
        range: TextRange,
        text: String,
        leadingGlue: Float = 0f,
        trailingGlue: Float = 0f,
    ): ClusterGeometryDecisionInfo =
        ClusterGeometryDecisionInfo(
            range = range,
            sourceText = text,
            displayText = text,
            baseAdvance = 10f,
            bodyWidth = 10f - leadingGlue - trailingGlue,
            leadingGlueNatural = leadingGlue,
            leadingGlueConsumed = 0f,
            trailingGlueNatural = trailingGlue,
            trailingGlueConsumed = 0f,
            justificationDelta = 0f,
            resolvedAdvance = 10f,
            source = "test",
            reason = "PunctuationGlueTest",
        )

    private fun rubySelectionResult(): LayoutResult =
        LayoutResult(
            input = LayoutInput(
                content = TiqianTextContent("张王李"),
                textStyle = TextStyle(fontSize = 20f),
                constraints = LayoutConstraints(maxWidth = 200f),
            ),
            size = Size(90f, 20f),
            clusters = listOf(
                Cluster(TextRange(0, 1), "张", fontKey = "cjk", advance = 35f),
                Cluster(TextRange(1, 2), "王", fontKey = "cjk", advance = 35f),
                Cluster(TextRange(2, 3), "李", fontKey = "cjk", advance = 20f),
            ),
            glyphRuns = listOf(
                GlyphRun(
                    range = TextRange(0, 3),
                    fontKey = "cjk",
                    glyphs = listOf(
                        Glyph(id = 1u, clusterRange = TextRange(0, 1), advance = 20f),
                        Glyph(id = 2u, clusterRange = TextRange(1, 2), advance = 20f),
                        Glyph(id = 3u, clusterRange = TextRange(2, 3), advance = 20f),
                    ),
                    advance = 60f,
                ),
            ),
            lines = listOf(
                LineBox(
                    range = TextRange(0, 3),
                    clusterRange = 0..2,
                    baseline = 15f,
                    top = 0f,
                    bottom = 20f,
                    naturalWidth = 60f,
                    adjustedWidth = 90f,
                    visualWidth = 90f,
                ),
            ),
            debug = LayoutDebugInfo(
                geometryDecisions = listOf(
                    rubyGeometry(TextRange(0, 1), "张", rubySpread = 15f, resolvedAdvance = 35f),
                    rubyGeometry(TextRange(1, 2), "王", rubySpread = 15f, resolvedAdvance = 35f),
                    rubyGeometry(TextRange(2, 3), "李", rubySpread = 0f, resolvedAdvance = 20f),
                ),
                rubyDecisions = listOf(
                    RubyDecisionInfo(
                        baseRange = TextRange(0, 1),
                        text = "zhuāng",
                        lineIndex = 0,
                        centerX = 10f,
                        baselineY = 0f,
                        fontSize = 10f,
                        width = 32f,
                        overhang = 6f,
                    ),
                    RubyDecisionInfo(
                        baseRange = TextRange(1, 2),
                        text = "chuáng",
                        lineIndex = 0,
                        centerX = 45f,
                        baselineY = 0f,
                        fontSize = 10f,
                        width = 32f,
                        overhang = 6f,
                    ),
                    RubyDecisionInfo(
                        baseRange = TextRange(2, 3),
                        text = "shuāng",
                        lineIndex = 0,
                        centerX = 80f,
                        baselineY = 0f,
                        fontSize = 10f,
                        width = 32f,
                        overhang = 6f,
                    ),
                ),
            ),
        )

    private fun rubyGeometry(
        range: TextRange,
        text: String,
        rubySpread: Float,
        resolvedAdvance: Float,
    ): ClusterGeometryDecisionInfo =
        ClusterGeometryDecisionInfo(
            range = range,
            sourceText = text,
            displayText = text,
            baseAdvance = 20f,
            bodyWidth = 20f,
            leadingGlueNatural = 0f,
            leadingGlueConsumed = 0f,
            trailingGlueNatural = 0f,
            trailingGlueConsumed = 0f,
            justificationDelta = 0f,
            rubySpread = rubySpread,
            resolvedAdvance = resolvedAdvance,
            source = "test",
            reason = "RubyAvoidanceSpread",
        )
}
