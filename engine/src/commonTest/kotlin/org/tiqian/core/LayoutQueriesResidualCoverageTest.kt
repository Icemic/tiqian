package org.tiqian.core

import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.assertFailsWith
import org.tiqian.test.trace.assertNull
import org.tiqian.test.trace.assertTrue
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

/**
 * Coverage for LayoutQueries.kt arms the base corpus leaves open: corner-radius
 * predicates, copy projection with annotations, glyph-ink bounds, cluster-gap
 * caret fallbacks, hand-crafted rich-text segments (mid-span slices, missing
 * lines, glue trimming), metric policy comparisons, adjacent clearance,
 * inline-object coercion, selection word expansion, and ruby geometry.
 */
class LayoutQueriesResidualCoverageTest {
    private val testTrace = TestTraceRecorder("LayoutQueriesResidualCoverageTest")


    private fun cluster(range: TextRange, text: String, advance: Float) =
        Cluster(range, text, fontKey = "test", advance = advance)

    private fun line(
        range: TextRange,
        clusterRange: IntRange,
        top: Float = 0.0f,
        bottom: Float = 20.0f,
        baseline: Float = 15.0f,
        indent: Float = 0.0f,
        width: Float = 10.0f,
    ) = LineBox(
        range = range,
        clusterRange = clusterRange,
        baseline = baseline,
        top = top,
        bottom = bottom,
        naturalWidth = width,
        adjustedWidth = width,
        visualWidth = width,
        indent = indent,
    )

    private fun result(
        text: String,
        clusters: List<Cluster>,
        lines: List<LineBox>,
        glyphRuns: List<GlyphRun> = emptyList(),
        spans: List<TextSpan> = emptyList(),
        inlineObjects: List<InlineObjectSpan> = emptyList(),
        debug: LayoutDebugInfo = LayoutDebugInfo(),
        textStyle: TextStyle = TextStyle(fontSize = 10.0f),
    ): LayoutResult = LayoutResult(
        input = LayoutInput(
            content = TiqianTextContent(text = text, spans = spans),
            textStyle = textStyle,
            constraints = LayoutConstraints(maxWidth = 100.0f),
            inlineObjects = inlineObjects,
        ),
        size = Size(30.0f, 40.0f),
        clusters = clusters,
        glyphRuns = glyphRuns,
        lines = lines,
        debug = debug,
    )

    private fun segment(
        range: TextRange,
        role: RichTextRole = RichTextRole.Background,
        paint: RichTextPaint = RichTextPaint(),
        lineIndex: Int = 0,
        spanRange: TextRange = range,
        left: Float = 0.0f,
        top: Float = 0.0f,
        right: Float = 20.0f,
        bottom: Float = 20.0f,
        baseline: Float = 15.0f,
    ) = RichTextLineSegment(
        span = RichTextSpan(spanRange, role, paint),
        lineIndex = lineIndex,
        range = range,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        baseline = baseline,
    )

    @Test
    fun cornerRadiiPredicatesCoverEveryComparison() {
        testTrace.section("cornerRadiiPredicatesCoverEveryComparison")
        assertTrue(RichTextCornerRadii(0.0f, 0.0f, 0.0f, 0.0f).isSquare)
        assertTrue(!RichTextCornerRadii(1.0f, 0.0f, 0.0f, 0.0f).isSquare)
        assertTrue(!RichTextCornerRadii(0.0f, 1.0f, 0.0f, 0.0f).isSquare)
        assertTrue(!RichTextCornerRadii(0.0f, 0.0f, 1.0f, 0.0f).isSquare)
        assertTrue(!RichTextCornerRadii(0.0f, 0.0f, 0.0f, 1.0f).isSquare)
        assertTrue(RichTextCornerRadii(2.0f, 2.0f, 2.0f, 2.0f).isUniform)
        assertTrue(!RichTextCornerRadii(1.0f, 2.0f, 2.0f, 2.0f).isUniform)
        assertTrue(!RichTextCornerRadii(2.0f, 1.0f, 2.0f, 2.0f).isUniform)
        assertTrue(!RichTextCornerRadii(2.0f, 2.0f, 1.0f, 2.0f).isUniform)
        assertTrue(!RichTextCornerRadii(2.0f, 2.0f, 2.0f, 1.0f).isUniform)
    }

    @Test
    fun resolvedCornerRadiiRejectsInvalidInsetsAndResolvesContinuations() {
        testTrace.section("resolvedCornerRadiiRejectsInvalidInsetsAndResolvesContinuations")
        val continuing = segment(
            range = TextRange(1, 2),
            spanRange = TextRange(0, 3),
            paint = RichTextPaint(
                background = RichTextBackgroundPaint(cornerRadius = 6.0f, continuationCornerRadius = 2.0f),
            ),
            left = 0.0f,
            top = 0.0f,
            right = 30.0f,
            bottom = 10.0f,
        )
        assertFailsWith<IllegalArgumentException> { continuing.resolvedBackgroundCornerRadii(inset = -1.0f) }
        assertFailsWith<IllegalArgumentException> { continuing.resolvedBackgroundCornerRadii(inset = Float.NaN) }
        val resolved = continuing.resolvedBackgroundCornerRadii()
        // Both edges continue (start 1 > span start 0, end 2 < span end 3),
        // so each takes continuationCornerRadius clamped to height/2.
        assertEquals(2.0f, resolved.topLeft)
        assertEquals(2.0f, resolved.topRight)
        assertEquals(2.0f, resolved.bottomRight)
        assertEquals(2.0f, resolved.bottomLeft)
    }

    @Test
    fun copyProjectionAppendsFullySelectedAnnotationsOnly() {
        testTrace.section("copyProjectionAppendsFullySelectedAnnotationsOnly")
        val debug = LayoutDebugInfo(
            rubyDecisions = listOf(
                RubyDecisionInfo(
                    baseRange = TextRange(0, 2), text = "zhù", lineIndex = 0,
                    centerX = 10.0f, baselineY = 12.0f, fontSize = 6.0f, overhang = 0.0f, width = 12.0f,
                ),
            ),
            bopomofoDecisions = listOf(
                BopomofoDecisionInfo(
                    baseRange = TextRange(2, 4), text = "ㄋㄧˇ", lineIndex = 0,
                    placements = emptyList(),
                ),
            ),
        )
        val content = result("abcd", emptyList(), emptyList(), debug = debug)
        assertEquals("", content.getTextForCopy(TextRange(1, 1)))
        // The bopomofo base (2,4) is not fully selected by (0,3): no reading.
        assertEquals("ab（zhù）c", content.getTextForCopy(TextRange(0, 3)))
        // Both annotations are fully selected and print in end order.
        assertEquals("ab（zhù）cd（ㄋㄧˇ）", content.getTextForCopy(TextRange(0, 4)))
        // A selection starting inside neither annotation appends nothing.
        assertEquals("d", content.getTextForCopy(TextRange(3, 4)))
    }

    @Test
    fun positionedClustersByLineRejectsForeignLines() {
        testTrace.section("positionedClustersByLineRejectsForeignLines")
        val owned = line(TextRange(0, 2), 0..0)
        val foreign = line(TextRange(0, 2), 0..0, top = 99.0f)
        val content = result(
            "ab",
            listOf(cluster(TextRange(0, 2), "ab", 20.0f)),
            listOf(owned),
        )
        assertEquals(1, content.positionedClusters(owned).size)
        val error = assertFailsWith<IllegalArgumentException> {
            content.positionedClusters(foreign)
        }
        assertTrue(error.message!!.contains("must belong"), error.message)
    }

    @Test
    fun glyphInkBoundsSkipsUnmatchedGlyphsAndReturnsNullWithoutInk() {
        testTrace.section("glyphInkBoundsSkipsUnmatchedGlyphsAndReturnsNullWithoutInk")
        val clusters = listOf(
            cluster(TextRange(0, 1), "a", 10.0f),
            cluster(TextRange(1, 2), "b", 10.0f),
        )
        val runs = listOf(
            GlyphRun(
                range = TextRange(0, 2), fontKey = "test",
                glyphs = listOf(
                    Glyph(1u, TextRange(0, 1), advance = 10.0f, bounds = Rect(0.0f, 2.0f, 8.0f, 12.0f)),
                    Glyph(2u, TextRange(0, 1), advance = 10.0f),
                    Glyph(3u, TextRange(5, 6), advance = 10.0f, bounds = Rect(0.0f, 0.0f, 1.0f, 1.0f)),
                ),
                advance = 20.0f,
            ),
        )
        val content = result("ab", clusters, listOf(line(TextRange(0, 2), 0..1)), glyphRuns = runs)
        // Ink is offset by the cluster baseline (15) and draw origin (0).
        assertEquals(Rect(0.0f, 17.0f, 8.0f, 27.0f), content.glyphInkBounds())

        val noInk = result("ab", clusters, listOf(line(TextRange(0, 2), 0..1)))
        assertNull(noInk.glyphInkBounds())
    }

    @Test
    fun emptyLineResultsShortCircuitEveryQuery() {
        testTrace.section("emptyLineResultsShortCircuitEveryQuery")
        val content = result("ab", emptyList(), emptyList())
        assertEquals(-1, content.getLineForOffset(0))
        assertEquals(Rect(0.0f, 0.0f, 0.0f, 0.0f), content.getBoundingBox(0))
        assertEquals(Rect(0.0f, 0.0f, 0.0f, 0.0f), content.getCursorRect(0))
        assertEquals(0, content.getOffsetForPosition(5.0f, 5.0f))
        assertEquals(0, content.getSelectionOffsetForPosition(5.0f, 5.0f))
        assertEquals(emptyList(), content.getBoundingBoxes(TextRange(0, 2)))
        assertNull(content.getSelectionWordBoundaryForPosition(5.0f, 5.0f))
        assertEquals(
            emptyList(),
            content.positionedRichTextSegments(listOf(RichTextSpan(TextRange(0, 1), RichTextRole.Underline))),
        )
        assertEquals(emptyList(), content.trimmedRichTextDecorationSegments(emptyList()))
        assertEquals(emptyList(), content.richTextBackgroundSegments(emptyList()))
    }

    @Test
    fun boundingBoxFallsBackToTheCursorRectAtClusterGaps() {
        testTrace.section("boundingBoxFallsBackToTheCursorRectAtClusterGaps")
        val clusters = listOf(
            cluster(TextRange(0, 1), "a", 10.0f),
            cluster(TextRange(2, 3), "c", 10.0f),
        )
        val content = result("abc", clusters, listOf(line(TextRange(0, 3), 0..1)))
        // Offset 1 has no covering cluster: the caret fallback returns the
        // right edge of cluster (0,1).
        assertEquals(Rect(10.0f, 0.0f, 11.0f, 20.0f), content.getBoundingBox(1))
        // The paragraph-end offset takes the final caret rect directly.
        assertEquals(Rect(20.0f, 0.0f, 21.0f, 20.0f), content.getBoundingBox(3))
        // A range whose end coerces onto its start yields no boxes.
        assertEquals(emptyList(), content.getBoundingBoxes(TextRange(3, 5)))
    }

    @Test
    fun richTextSegmentsSplitOnLineBreaksAndClusterGaps() {
        testTrace.section("richTextSegmentsSplitOnLineBreaksAndClusterGaps")
        val clusters = listOf(
            cluster(TextRange(0, 1), "a", 10.0f),
            cluster(TextRange(2, 3), "c", 10.0f),
            cluster(TextRange(3, 4), "d", 10.0f),
        )
        val content = result(
            "abcd",
            clusters,
            listOf(
                line(TextRange(0, 3), 0..1),
                line(TextRange(3, 4), 2..2, top = 20.0f, bottom = 40.0f, baseline = 35.0f),
            ),
        )
        val underline = RichTextSpan(TextRange(0, 4), RichTextRole.Underline)
        val split = content.positionedRichTextSegments(listOf(underline))
        // Line 0 carries two non-contiguous slices (the source gap at 1..2
        // breaks the merge), line 1 carries the third.
        assertEquals(3, split.size)
        assertEquals(TextRange(0, 1), split[0].range)
        assertEquals(TextRange(2, 3), split[1].range)
        assertEquals(TextRange(3, 4), split[2].range)
        assertEquals(0, split[0].lineIndex)
        assertEquals(0, split[1].lineIndex)
        assertEquals(1, split[2].lineIndex)

        // A span that coerces onto an empty range produces nothing.
        assertTrue(
            content.positionedRichTextSegments(listOf(underline.copy(range = TextRange(5, 8)))).isEmpty(),
        )
    }

    @Test
    fun richTextSegmentsSkipZeroLengthClustersBetweenSlices() {
        testTrace.section("richTextSegmentsSkipZeroLengthClustersBetweenSlices")
        val clusters = listOf(
            cluster(TextRange(0, 1), "a", 10.0f),
            cluster(TextRange(1, 1), "", 0.0f),
            cluster(TextRange(1, 2), "b", 10.0f),
        )
        val content = result("ab", clusters, listOf(line(TextRange(0, 2), 0..2)))
        val segments = content.positionedRichTextSegments(
            listOf(RichTextSpan(TextRange(0, 2), RichTextRole.Underline)),
        )
        // The empty cluster cannot slice, but its neighbours share the source
        // boundary 1 and merge into one continuous segment.
        assertEquals(1, segments.size)
        assertEquals(TextRange(0, 2), segments[0].range)
        assertEquals(0.0f, segments[0].left)
        assertEquals(20.0f, segments[0].right)
    }

    @Test
    fun trimmedDecorationSegmentsKeepOnlyDecorationRoles() {
        testTrace.section("trimmedDecorationSegmentsKeepOnlyDecorationRoles")
        val content = result("ab", emptyList(), emptyList())
        val decoration = segment(TextRange(0, 2), role = RichTextRole.Underline)
        assertEquals(listOf(decoration), content.trimmedRichTextDecorationSegments(listOf(decoration)))
        assertTrue(
            content.trimmedRichTextDecorationSegments(
                listOf(segment(TextRange(0, 2), role = RichTextRole.Background)),
            ).isEmpty(),
        )
    }

    @Test
    fun backgroundSegmentsPassThroughUnmatchableSegments() {
        testTrace.section("backgroundSegmentsPassThroughUnmatchableSegments")
        val content = result(
            "ab",
            listOf(cluster(TextRange(0, 1), "a", 10.0f)),
            listOf(line(TextRange(0, 2), 0..0)),
        )
        // No positioned cluster overlaps this range: the segment maps to itself.
        val far = segment(TextRange(10, 12))
        assertEquals(listOf(far), content.richTextBackgroundSegments(listOf(far)))
        // A segment pointing at a missing line index also maps to itself.
        val orphan = segment(TextRange(0, 1), lineIndex = 5)
        assertEquals(listOf(orphan), content.richTextBackgroundSegments(listOf(orphan)))
        // Underline-only input is not a background.
        assertTrue(
            content.richTextBackgroundSegments(
                listOf(segment(TextRange(0, 1), role = RichTextRole.Underline)),
            ).isEmpty(),
        )
    }

    @Test
    fun backgroundSegmentsTrimGlueApplyPaddingAndUseGlyphAdvances() {
        testTrace.section("backgroundSegmentsTrimGlueApplyPaddingAndUseGlyphAdvances")
        val clusters = listOf(
            cluster(TextRange(0, 1), "，", 10.0f),
            cluster(TextRange(1, 2), "字", 10.0f),
        )
        val glue = ClusterGeometryDecisionInfo(
            range = TextRange(0, 1), sourceText = "，", displayText = "，",
            baseAdvance = 10.0f, bodyWidth = 5.0f,
            leadingGlueNatural = 4.0f, leadingGlueConsumed = 1.0f,
            trailingGlueNatural = 4.0f, trailingGlueConsumed = 1.0f,
            justificationDelta = 0.0f,
            resolvedAdvance = 10.0f, source = "test", reason = "test",
        )
        val runs = listOf(
            GlyphRun(
                range = TextRange(1, 2), fontKey = "test",
                glyphs = listOf(Glyph(9u, TextRange(1, 2), advance = 9.0f, x = 1.0f)),
                advance = 10.0f,
            ),
        )
        val content = result(
            "，字",
            clusters,
            listOf(line(TextRange(0, 2), 0..1)),
            glyphRuns = runs,
            debug = LayoutDebugInfo(geometryDecisions = listOf(glue)),
        )
        val full = content.richTextBackgroundSegments(
            listOf(segment(TextRange(0, 2), left = 0.0f, right = 20.0f)),
        ).single()
        // Leading glue (4 - 1 = 3) is removed from the left; the trailing edge
        // uses the glyph draw position (10 + 1 + 9 = 20). No metric decisions
        // exist, so MarkedFaces falls back to 0.88/0.12 em around baseline 15.
        assertEquals(3.0f, full.left)
        assertEquals(20.0f, full.right)
        // Computed with the source's own operands so the doubles match exactly.
        assertEquals(15.0f - 10.0f * 0.88f, full.top)
        assertEquals(15.0f + 10.0f * 0.12f, full.bottom)

        // A head slice: the span start matches (padding applies) but its end
        // (2) differs from the span end (3), so trailing padding is 0.
        val head = content.richTextBackgroundSegments(
            listOf(
                segment(
                    TextRange(0, 2), spanRange = TextRange(0, 3),
                    paint = RichTextPaint(
                        background = RichTextBackgroundPaint(horizontalPadding = 5.0f),
                    ),
                    left = 0.0f, right = 20.0f,
                ),
            ),
        ).single()
        assertEquals(3.0f, head.left)
        assertEquals(20.0f, head.right)

        // A continuation slice: neither span edge matches, so no padding at
        // all and the box keeps its own edges.
        val continuation = content.richTextBackgroundSegments(
            listOf(
                segment(
                    TextRange(1, 2), spanRange = TextRange(0, 3),
                    paint = RichTextPaint(
                        background = RichTextBackgroundPaint(horizontalPadding = 5.0f),
                    ),
                    left = 10.0f, right = 20.0f,
                ),
            ),
        ).single()
        assertEquals(10.0f, continuation.left)
        assertEquals(20.0f, continuation.right)
    }

    @Test
    fun markedFacesUseMetricDecisionsWhenTheyCoverTheCluster() {
        testTrace.section("markedFacesUseMetricDecisionsWhenTheyCoverTheCluster")
        val metric = MetricDecisionInfo(
            range = TextRange(0, 2), sourceText = "ab", role = "body", fontKey = "test",
            rawAscent = 8.0f, rawDescent = 2.0f, rawLeading = 0.0f, rawSource = "stub",
            layoutAscent = 7.0f, layoutDescent = 3.0f, baselineClass = "ideographic",
            metricBox = "IdeographicEmBox", layoutSource = "normalized", reason = "test",
        )
        val clusters = listOf(cluster(TextRange(0, 1), "a", 10.0f), cluster(TextRange(1, 2), "b", 10.0f))
        val content = result(
            "ab", clusters, listOf(line(TextRange(0, 2), 0..1)),
            debug = LayoutDebugInfo(metricDecisions = listOf(metric)),
        )
        val box = content.richTextBackgroundSegments(
            listOf(segment(TextRange(0, 2), left = 0.0f, right = 20.0f)),
        ).single()
        // MarkedFaces unions the metric faces: top = 15 - 7, bottom = 15 + 3.
        assertEquals(8.0f, box.top)
        assertEquals(18.0f, box.bottom)
    }

    @Test
    fun uniformTextStyleFallsBackWhenEveryMetricFieldDiffers() {
        testTrace.section("uniformTextStyleFallsBackWhenEveryMetricFieldDiffers")
        val base = TextStyle(fontSize = 10.0f)
        // Each variant differs from the paragraph style in exactly one field.
        val variants = listOf(
            base.copy(fontFamilies = listOf("other")),
            base.copy(fontSize = 11.0f),
            base.copy(locale = "ja-JP"),
            base.copy(fontWeight = 700),
            base.copy(italic = true),
            base.copy(baselineShift = 2.0f),
        )
        val clusters = listOf(cluster(TextRange(0, 1), "a", 10.0f), cluster(TextRange(1, 2), "b", 10.0f))
        // The only metric decision sits at (1,2) where the resolved style is
        // the paragraph style: it never matches the span style at offset 0.
        val metric = MetricDecisionInfo(
            range = TextRange(1, 2), sourceText = "b", role = "body",
            fontKey = "test", rawAscent = 8.0f, rawDescent = 2.0f,
            rawLeading = 0.0f, rawSource = "stub", layoutAscent = 9.0f,
            layoutDescent = 1.0f, baselineClass = "latin",
            metricBox = "LatinBox", layoutSource = "normalized", reason = "test",
        )
        for (variant in variants) {
            val content = result(
                "ab",
                clusters,
                listOf(line(TextRange(0, 2), 0..1)),
                spans = listOf(TextSpan(TextRange(0, 1), variant)),
                textStyle = base,
                debug = LayoutDebugInfo(metricDecisions = listOf(metric)),
            )
            val box = content.richTextBackgroundSegments(
                listOf(
                    segment(
                        TextRange(0, 2),
                        paint = RichTextPaint(
                            background = RichTextBackgroundPaint(
                                metricPolicy = RichTextBackgroundMetricPolicy.UniformTextStyle,
                            ),
                        ),
                        left = 0.0f, right = 20.0f,
                    ),
                ),
            ).single()
            // No metric matches, so the em fallback uses the span's font size.
            assertEquals(15.0f - variant.fontSize * 0.88f, box.top, "variant=$variant")
            assertEquals(15.0f + variant.fontSize * 0.12f, box.bottom, "variant=$variant")
        }
    }

    @Test
    fun uniformTextStylePrefersIdeographicMetricsThenAnyMatchingFace() {
        testTrace.section("uniformTextStylePrefersIdeographicMetricsThenAnyMatchingFace")
        fun contentWith(metrics: List<MetricDecisionInfo>): LayoutResult = result(
            "ab",
            listOf(cluster(TextRange(0, 1), "a", 10.0f), cluster(TextRange(1, 2), "b", 10.0f)),
            listOf(line(TextRange(0, 2), 0..1)),
            debug = LayoutDebugInfo(metricDecisions = metrics),
        )
        val paint = RichTextPaint(
            background = RichTextBackgroundPaint(
                metricPolicy = RichTextBackgroundMetricPolicy.UniformTextStyle,
            ),
        )
        // A matching Latin face without an ideographic box is still a reference.
        val latin = contentWith(
            listOf(
                MetricDecisionInfo(
                    range = TextRange(0, 2), sourceText = "ab", role = "body",
                    fontKey = "test", rawAscent = 8.0f, rawDescent = 2.0f,
                    rawLeading = 0.0f, rawSource = "stub", layoutAscent = 9.0f,
                    layoutDescent = 1.0f, baselineClass = "latin",
                    metricBox = "LatinBox", layoutSource = "normalized", reason = "test",
                ),
            ),
        )
        val latinBox = latin.richTextBackgroundSegments(
            listOf(segment(TextRange(0, 2), paint = paint, left = 0.0f, right = 20.0f)),
        ).single()
        assertEquals(6.0f, latinBox.top)
        assertEquals(16.0f, latinBox.bottom)

        // When an ideographic metric also matches, it wins over the earlier
        // Latin face even though the Latin one is first in the list.
        val both = contentWith(
            listOf(
                MetricDecisionInfo(
                    range = TextRange(0, 1), sourceText = "a", role = "body",
                    fontKey = "test", rawAscent = 8.0f, rawDescent = 2.0f,
                    rawLeading = 0.0f, rawSource = "stub", layoutAscent = 9.0f,
                    layoutDescent = 1.0f, baselineClass = "latin",
                    metricBox = "LatinBox", layoutSource = "normalized", reason = "test",
                ),
                MetricDecisionInfo(
                    range = TextRange(0, 2), sourceText = "ab", role = "body",
                    fontKey = "test", rawAscent = 8.0f, rawDescent = 2.0f,
                    rawLeading = 0.0f, rawSource = "stub", layoutAscent = 8.0f,
                    layoutDescent = 2.0f, baselineClass = "ideographic",
                    metricBox = "IdeographicEmBox", layoutSource = "normalized", reason = "test",
                ),
            ),
        )
        val ideographicBox = both.richTextBackgroundSegments(
            listOf(segment(TextRange(0, 2), paint = paint, left = 0.0f, right = 20.0f)),
        ).single()
        assertEquals(7.0f, ideographicBox.top)
        assertEquals(17.0f, ideographicBox.bottom)
    }

    @Test
    fun adjacentSameStyleSegmentsShareClearance() {
        testTrace.section("adjacentSameStyleSegmentsShareClearance")
        val content = result(
            "ab",
            listOf(cluster(TextRange(0, 1), "a", 10.0f), cluster(TextRange(1, 2), "b", 10.0f)),
            listOf(line(TextRange(0, 2), 0..1)),
        )
        val paint = RichTextPaint(adjacentSameStyleClearance = 4.0f)
        val first = segment(TextRange(0, 1), paint = paint, left = 0.0f, right = 10.0f)
        val second = segment(TextRange(1, 2), paint = paint, left = 10.0f, right = 20.0f)
        val cleared = content.richTextBackgroundSegments(listOf(first, second))
        assertEquals(2, cleared.size)
        // Each box yields half the clearance at the shared edge: the left box
        // shrinks its right edge, the right box pushes out its left edge.
        assertEquals(10.0f - 4.0f / 2.0f, cleared[0].right)
        assertEquals(10.0f + 4.0f / 2.0f, cleared[1].left)

        // A visibly different paint next door is not a clearance partner.
        val otherPaint = segment(
            TextRange(1, 2),
            paint = RichTextPaint(
                adjacentSameStyleClearance = 4.0f,
                background = RichTextBackgroundPaint(cornerRadius = 3.0f),
            ),
            left = 10.0f, right = 20.0f,
        )
        val untouched = content.richTextBackgroundSegments(listOf(first, otherPaint))
        assertEquals(2, untouched.size)
        assertEquals(10.0f, untouched[0].right)
        assertEquals(10.0f, untouched[1].left)
    }

    @Test
    fun decorationLineYRequiresValidStrokeAndDecorationRoles() {
        testTrace.section("decorationLineYRequiresValidStrokeAndDecorationRoles")
        val content = result("ab", emptyList(), listOf(line(TextRange(0, 2), 0..0)))
        val underline = segment(TextRange(0, 1), role = RichTextRole.Underline)
        assertFailsWith<IllegalArgumentException> { content.richTextDecorationLineY(underline, -1.0f) }
        assertFailsWith<IllegalArgumentException> { content.richTextDecorationLineY(underline, Float.NaN) }
        val error = assertFailsWith<IllegalArgumentException> {
            content.richTextDecorationLineY(segment(TextRange(0, 1), role = RichTextRole.Background), 1.0f)
        }
        assertTrue(error.message!!.contains("underline and line-through"), error.message)

        val withSpanStyle = result(
            "ab",
            listOf(cluster(TextRange(0, 1), "a", 10.0f)),
            listOf(line(TextRange(0, 2), 0..0)),
            spans = listOf(TextSpan(TextRange(0, 1), TextStyle(fontSize = 10.0f))),
        )
        val y = withSpanStyle.richTextDecorationLineY(underline, 1.0f)
        assertTrue(y in underline.top..underline.bottom, y.toString())

        val lineThrough = segment(TextRange(0, 1), role = RichTextRole.LineThrough)
        val strike = withSpanStyle.richTextDecorationLineY(lineThrough, 1.0f)
        // The strike-through center of the 0.88/0.12 em box is 15 - 10*0.38
        // = 11.2. The accumulated product rounds the f32-arithmetic result
        // to 11.200001 while the double arithmetic of Kotlin/JS lands on
        // 11.2; both sit within one f32 ulp of the true value, so the
        // assertion bounds the distance to the true value instead of
        // pinning one backend's bucket.
        assertEquals(11.2f, strike, 0.001f)
    }

    @Test
    fun cursorRectCoversEmptyLinesEmptyClustersAndMultiUnitClusters() {
        testTrace.section("cursorRectCoversEmptyLinesEmptyClustersAndMultiUnitClusters")
        val emptyClusterLine = line(TextRange(0, 0), 0..-1, indent = 6.0f)
        val withEmptyLine = result("a", emptyList(), listOf(emptyClusterLine))
        assertEquals(Rect(6.0f, 0.0f, 7.0f, 20.0f), withEmptyLine.getCursorRect(0))

        // A two-unit cluster without per-unit glyphs interpolates linearly.
        val linear = result(
            "ab",
            listOf(cluster(TextRange(0, 2), "ab", 20.0f)),
            listOf(line(TextRange(0, 2), 0..0)),
        )
        assertEquals(10.0f, linear.getCursorRect(1).left)

        // With one glyph per source unit the caret lands on the glyph origin.
        val stops = result(
            "ab",
            listOf(cluster(TextRange(0, 2), "ab", 20.0f)),
            listOf(line(TextRange(0, 2), 0..0)),
            glyphRuns = listOf(
                GlyphRun(
                    range = TextRange(0, 2), fontKey = "test",
                    glyphs = listOf(
                        Glyph(1u, TextRange(0, 2), advance = 10.0f, x = 0.0f),
                        Glyph(2u, TextRange(0, 2), advance = 10.0f, x = 12.0f),
                    ),
                    advance = 20.0f,
                ),
            ),
        )
        assertEquals(12.0f, stops.getCursorRect(1).left)
    }

    @Test
    fun offsetForPositionCoversVerticalDistancesAndNaNPoints() {
        testTrace.section("offsetForPositionCoversVerticalDistancesAndNaNPoints")
        val content = result(
            "ab",
            listOf(cluster(TextRange(0, 1), "a", 10.0f), cluster(TextRange(1, 2), "b", 10.0f)),
            listOf(
                line(TextRange(0, 2), 0..1),
                line(TextRange(2, 2), 2..-1, top = 20.0f, bottom = 40.0f, baseline = 35.0f),
            ),
        )
        // Above the first line picks line 0; below the last line picks the
        // empty second line, whose range start is 2.
        assertEquals(0, content.getOffsetForPosition(2.0f, -50.0f))
        assertEquals(2, content.getOffsetForPosition(5.0f, 90.0f))
        assertEquals(2, content.getOffsetForPosition(5.0f, 30.0f))
        // The same distance arms run through the selection hit test.
        assertEquals(0, content.getSelectionOffsetForPosition(2.0f, -50.0f))
        assertEquals(2, content.getSelectionOffsetForPosition(5.0f, 90.0f))

        // A NaN x misses every box and boundary arm, so both hit testers fall
        // back to the nearest box. That path must run on a cluster with source
        // stops: its nearest-unit scan returns the cluster start for NaN
        // distances, while the linear ratio path would round NaN and diverge
        // between JVM and JS.
        val withStops = result(
            "ab",
            listOf(cluster(TextRange(0, 2), "ab", 20.0f)),
            listOf(line(TextRange(0, 2), 0..0)),
            glyphRuns = listOf(
                GlyphRun(
                    range = TextRange(0, 2), fontKey = "test",
                    glyphs = listOf(
                        Glyph(1u, TextRange(0, 2), advance = 10.0f, x = 0.0f),
                        Glyph(2u, TextRange(0, 2), advance = 10.0f, x = 10.0f),
                    ),
                    advance = 20.0f,
                ),
            ),
        )
        assertEquals(0, withStops.getOffsetForPosition(Float.NaN, 5.0f))
        assertEquals(0, withStops.getSelectionOffsetForPosition(Float.NaN, 5.0f))
    }

    @Test
    fun selectionSnapPrefersTheCloserInlineObjectBoundary() {
        testTrace.section("selectionSnapPrefersTheCloserInlineObjectBoundary")
        val content = result(
            "abb",
            listOf(cluster(TextRange(0, 3), "abb", 30.0f)),
            listOf(line(TextRange(0, 3), 0..0)),
            inlineObjects = listOf(
                InlineObjectSpan(TextRange(1, 3), advance = 8.0f, ascent = 4.0f, descent = 4.0f),
            ),
        )
        // x=15 snaps to raw offset 2, inside the object (1,3): backward (1,
        // caret at 10) beats forward (3, caret at 30).
        assertEquals(1, content.getSelectionOffsetForPosition(15.0f, 5.0f))
        // x=21 also snaps to 2, but the forward caret (30) is nearer.
        assertEquals(3, content.getSelectionOffsetForPosition(21.0f, 5.0f))
    }

    @Test
    fun selectionWordBoundaryForPositionRejectsDegenerateContent() {
        testTrace.section("selectionWordBoundaryForPositionRejectsDegenerateContent")
        val emptyText = result("", emptyList(), listOf(line(TextRange(0, 0), 0..-1)))
        assertNull(emptyText.getSelectionWordBoundaryForPosition(0.0f, 0.0f))

        // A point on a line without positioned clusters finds nothing.
        val emptyLine = result(
            "a",
            listOf(cluster(TextRange(0, 1), "a", 10.0f)),
            listOf(
                line(TextRange(0, 1), 0..0),
                line(TextRange(1, 1), 1..-1, top = 20.0f, bottom = 40.0f, baseline = 35.0f),
            ),
        )
        assertNull(emptyLine.getSelectionWordBoundaryForPosition(5.0f, 30.0f))

        // The first cluster is a zero-length one: the hit box is degenerate.
        val leadingEmpty = result(
            "a",
            listOf(cluster(TextRange(0, 0), "", 0.0f), cluster(TextRange(0, 1), "a", 10.0f)),
            listOf(line(TextRange(0, 1), 0..1)),
        )
        assertNull(leadingEmpty.getSelectionWordBoundaryForPosition(0.0f, 5.0f))
        assertEquals(TextRange(0, 1), leadingEmpty.getSelectionWordBoundaryForPosition(5.0f, 5.0f))
    }

    @Test
    fun zeroWidthClustersReturnTheirStartInHitTests() {
        testTrace.section("zeroWidthClustersReturnTheirStartInHitTests")
        // An empty-range cluster with advance: hit tests return range.start
        // before any proportional math.
        val emptyRange = result(
            "",
            listOf(cluster(TextRange(0, 0), "", 5.0f)),
            listOf(line(TextRange(0, 0), 0..0)),
        )
        assertEquals(0, emptyRange.getOffsetForPosition(2.0f, 5.0f))

        // A zero-advance cluster on its own line: the width guard in
        // offsetForX returns range.start instead of dividing by zero.
        val zeroAdvance = result(
            "ab",
            listOf(cluster(TextRange(0, 1), "a", 10.0f), cluster(TextRange(1, 2), "b", 0.0f)),
            listOf(
                line(TextRange(0, 1), 0..0),
                line(TextRange(1, 2), 1..1, top = 20.0f, bottom = 40.0f, baseline = 35.0f),
            ),
        )
        assertEquals(TextRange(0, 2), zeroAdvance.getSelectionWordBoundaryForPosition(0.0f, 30.0f))
    }

    @Test
    fun coerceSelectionOffsetHonoursInlineObjectBoundaries() {
        testTrace.section("coerceSelectionOffsetHonoursInlineObjectBoundaries")
        val content = result(
            "abb",
            emptyList(),
            listOf(line(TextRange(0, 3), 0..0)),
            inlineObjects = listOf(
                InlineObjectSpan(TextRange(1, 3), advance = 8.0f, ascent = 4.0f, descent = 4.0f),
            ),
        )
        assertEquals(1, content.coerceSelectionOffset(2, SourceBoundaryBias.Backward))
        assertEquals(3, content.coerceSelectionOffset(2, SourceBoundaryBias.Forward))
        // Equidistant: the nearest bias keeps the end boundary.
        assertEquals(3, content.coerceSelectionOffset(2, SourceBoundaryBias.Nearest))
        // Offsets at the object edges are already safe boundaries.
        assertEquals(1, content.coerceSelectionOffset(1, SourceBoundaryBias.Nearest))
        assertEquals(3, content.coerceSelectionOffset(3, SourceBoundaryBias.Nearest))
    }

    @Test
    fun selectionWordBoundaryExpandsWordsAndHonoursInlineObjects() {
        testTrace.section("selectionWordBoundaryExpandsWordsAndHonoursInlineObjects")
        val content = result("hello", emptyList(), listOf(line(TextRange(0, 5), 0..0)))
        assertEquals(TextRange(0, 5), content.getSelectionWordBoundary(2))
        // The paragraph-end offset resolves to the final unit.
        assertEquals(TextRange(0, 5), content.getSelectionWordBoundary(5))

        // A mid-surrogate offset snaps to the enclosing emoji unit.
        val emoji = result("😀", emptyList(), listOf(line(TextRange(0, 2), 0..0)))
        assertEquals(TextRange(0, 2), emoji.getSelectionWordBoundary(1))

        val withObject = result(
            "abb",
            emptyList(),
            listOf(line(TextRange(0, 3), 0..0)),
            inlineObjects = listOf(
                InlineObjectSpan(TextRange(1, 3), advance = 8.0f, ascent = 4.0f, descent = 4.0f),
            ),
        )
        assertEquals(TextRange(1, 3), withObject.getSelectionWordBoundary(2))

        // Mandatory breaks and word connectors cover the remaining kinds.
        val mandatory = result("a\nb", emptyList(), listOf(line(TextRange(0, 3), 0..0)))
        assertEquals(TextRange(1, 2), mandatory.getSelectionWordBoundary(1))
        val connectors = result("a_b", emptyList(), listOf(line(TextRange(0, 3), 0..0)))
        assertEquals(TextRange(0, 3), connectors.getSelectionWordBoundary(1))

        val empty = result("", emptyList(), listOf(line(TextRange(0, 0), 0..-1)))
        assertEquals(TextRange(0, 0), empty.getSelectionWordBoundary(0))
    }

    @Test
    fun selectionWordKindCoversEveryHanBlock() {
        testTrace.section("selectionWordKindCoversEveryHanBlock")
        for (text in listOf("㐀", "一", "豈", "𠀀")) {
            val content = result(text, emptyList(), listOf(line(TextRange(0, text.length), 0..0)))
            assertEquals(
                TextRange(0, text.length),
                content.getSelectionWordBoundary(0),
                "text=$text",
            )
        }
    }

    @Test
    fun nearestLineFallsBackToTheOnlyLineAtItsEndOffset() {
        testTrace.section("nearestLineFallsBackToTheOnlyLineAtItsEndOffset")
        val content = result(
            "abc",
            listOf(cluster(TextRange(0, 2), "ab", 20.0f)),
            listOf(line(TextRange(0, 2), 0..0)),
        )
        // Offset 2 equals the single line's end: no line contains it, and the
        // nearest-distance walk returns line 0 through its `else` arm.
        assertEquals(0, content.getLineForOffset(2))
    }

    @Test
    fun rubyGeometryRedistributesSelectionBoxesAndDropsSourceStops() {
        testTrace.section("rubyGeometryRedistributesSelectionBoxesAndDropsSourceStops")
        val clusters = listOf(
            cluster(TextRange(0, 2), "ab", 20.0f),
            cluster(TextRange(2, 3), "c", 10.0f),
        )
        val runs = listOf(
            GlyphRun(
                range = TextRange(0, 2), fontKey = "test",
                glyphs = listOf(
                    Glyph(1u, TextRange(0, 2), advance = 10.0f, x = 0.0f),
                    Glyph(2u, TextRange(0, 2), advance = 10.0f, x = 10.0f),
                ),
                advance = 20.0f,
            ),
        )
        val matching = RubyDecisionInfo(
            baseRange = TextRange(0, 3), text = "zhù", lineIndex = 0,
            centerX = 15.0f, baselineY = 4.0f, fontSize = 6.0f, overhang = 0.0f, width = 30.0f,
        )
        val stray = RubyDecisionInfo(
            baseRange = TextRange(5, 6), text = "x", lineIndex = 0,
            centerX = 0.0f, baselineY = 4.0f, fontSize = 6.0f, overhang = 0.0f, width = 6.0f,
        )
        val content = result(
            "abc",
            clusters,
            listOf(line(TextRange(0, 3), 0..1)),
            glyphRuns = runs,
            debug = LayoutDebugInfo(rubyDecisions = listOf(matching, stray)),
        )
        val positioned = content.positionedClusters()
        assertEquals(2, positioned.size)
        // The cluster (0,2) would carry source stops without the ruby; the
        // ruby redistribution drops them and stretches both boxes to the
        // annotation edges split at the cluster-center midpoint: centers are
        // drawX 0 + 20/2 = 10 and drawX 20 + 10/2 = 25, split at 17.5.
        assertNull(positioned[0].sourceStops)
        assertNull(positioned[1].sourceStops)
        assertEquals(0.0f, positioned[0].left)
        assertEquals(17.5f, positioned[0].right)
        assertEquals(17.5f, positioned[1].left)
        assertEquals(30.0f, positioned[1].right)
    }

    @Test
    fun boundingBoxesSliceZeroWidthAndEmptyClusters() {
        testTrace.section("boundingBoxesSliceZeroWidthAndEmptyClusters")
        val clusters = listOf(
            cluster(TextRange(0, 1), "a", 10.0f),
            cluster(TextRange(1, 2), "b", 0.0f),
        )
        val content = result("ab", clusters, listOf(line(TextRange(0, 2), 0..1)))
        val boxes = content.getBoundingBoxes(TextRange(0, 2))
        assertEquals(2, boxes.size)
        // The zero-width cluster keeps its full degenerate rect.
        assertEquals(10.0f, boxes[1].left)
        assertEquals(10.0f, boxes[1].right)
        // A range starting at 1 skips the first cluster entirely.
        val tail = content.getBoundingBoxes(TextRange(1, 2))
        assertEquals(1, tail.size)
        assertEquals(10.0f, tail[0].left)
    }

    @Test
    fun positionedClustersAndSegmentsReturnEmptyWithoutLines() {
        testTrace.section("positionedClustersAndSegmentsReturnEmptyWithoutLines")
        val noLines = result("ab", listOf(cluster(TextRange(0, 1), "a", 10.0f)), emptyList())
        assertTrue(noLines.positionedClusters().isEmpty())
        val spans = listOf(RichTextSpan(TextRange(0, 2), RichTextRole.Background))
        assertTrue(noLines.positionedRichTextSegments(spans).isEmpty())
        val noSpans = result("ab", listOf(cluster(TextRange(0, 1), "a", 10.0f)), listOf(line(TextRange(0, 1), 0..0)))
        assertTrue(noSpans.positionedRichTextSegments(emptyList()).isEmpty())
    }

    @Test
    fun sameSpanSlicesAcrossASourceBoundaryMergeIntoOneSegment() {
        testTrace.section("sameSpanSlicesAcrossASourceBoundaryMergeIntoOneSegment")
        // The explicit source boundary splits the occupied slices of one span;
        // the merge re-unifies source-contiguous slices of the same span.
        val content = LayoutResult(
            input = LayoutInput(
                content = TiqianTextContent(
                    text = "ab",
                    sourceBoundaries = setOf(1),
                    spans = listOf(TextSpan(TextRange(0, 2), TextStyle(fontSize = 10.0f))),
                ),
                textStyle = TextStyle(fontSize = 10.0f),
                constraints = LayoutConstraints(maxWidth = 100.0f),
            ),
            size = Size(20.0f, 20.0f),
            clusters = listOf(
                cluster(TextRange(0, 1), "a", 10.0f),
                cluster(TextRange(1, 2), "b", 10.0f),
            ),
            glyphRuns = emptyList(),
            lines = listOf(line(TextRange(0, 2), 0..1)),
            debug = LayoutDebugInfo(),
        )
        val segments = content.positionedRichTextSegments(
            listOf(RichTextSpan(TextRange(0, 2), RichTextRole.Background)),
        )
        assertEquals(1, segments.size)
        assertEquals(TextRange(0, 2), segments[0].range)
        assertEquals(0.0f, segments[0].left)
        assertEquals(20.0f, segments[0].right)
    }

    @Test
    fun glyphInkBoundsSkipsUnusableGlyphsAndReportsNull() {
        testTrace.section("glyphInkBoundsSkipsUnusableGlyphsAndReportsNull")
        val clusters = listOf(
            cluster(TextRange(0, 1), "a", 10.0f),
            cluster(TextRange(1, 2), "b", 10.0f),
        )
        val lines = listOf(line(TextRange(0, 2), 0..1))

        // Glyphs without ink bounds contribute nothing.
        val noBounds = result(
            "ab", clusters, lines,
            glyphRuns = listOf(
                GlyphRun(
                    range = TextRange(0, 2), fontKey = "test",
                    glyphs = listOf(Glyph(1u, TextRange(0, 1), advance = 10.0f)),
                    advance = 20.0f,
                ),
            ),
        )
        assertNull(noBounds.glyphInkBounds())

        // A glyph whose placement is not finite poisons the accumulation and
        // the bounds collapse to null instead of a bogus rect.
        val nanPlaced = result(
            "ab", clusters, lines,
            glyphRuns = listOf(
                GlyphRun(
                    range = TextRange(1, 2), fontKey = "test",
                    glyphs = listOf(
                        Glyph(
                            9u, TextRange(1, 2), advance = 9.0f, x = Float.NaN,
                            bounds = Rect(1.0f, 2.0f, 8.0f, 4.0f),
                        ),
                    ),
                    advance = 10.0f,
                ),
            ),
        )
        assertNull(nanPlaced.glyphInkBounds())

        // Usable bounds at the cluster origin resolve to the shifted ink rect.
        val usable = result(
            "ab", clusters, lines,
            glyphRuns = listOf(
                GlyphRun(
                    range = TextRange(0, 2), fontKey = "test",
                    glyphs = listOf(
                        Glyph(
                            1u, TextRange(0, 1), advance = 10.0f, x = 2.0f, y = 1.0f,
                            bounds = Rect(1.0f, 2.0f, 8.0f, 4.0f),
                        ),
                        Glyph(
                            2u, TextRange(1, 2), advance = 10.0f, x = 1.0f, y = 0.0f,
                            bounds = Rect(0.0f, 1.0f, 9.0f, 3.0f),
                        ),
                    ),
                    advance = 20.0f,
                ),
            ),
        )
        val ink = usable.glyphInkBounds()!!
        assertEquals(3.0f, ink.left)
        assertEquals(20.0f, ink.right)
        assertEquals(16.0f, ink.top)
        assertEquals(20.0f, ink.bottom)
    }

    @Test
    fun backgroundTrailingEdgeUsesGlyphAdvancesWhenAvailable() {
        testTrace.section("backgroundTrailingEdgeUsesGlyphAdvancesWhenAvailable")
        val clusters = listOf(
            cluster(TextRange(0, 1), "a", 10.0f),
            cluster(TextRange(1, 2), "b", 10.0f),
        )
        val lines = listOf(line(TextRange(0, 2), 0..1))

        // The last cluster's glyph advances only 5pt past its origin, so the
        // background's trailing edge follows the shaped ink, not the box.
        val shortGlyph = result(
            "ab", clusters, lines,
            glyphRuns = listOf(
                GlyphRun(
                    range = TextRange(1, 2), fontKey = "test",
                    glyphs = listOf(Glyph(2u, TextRange(1, 2), advance = 5.0f, x = 0.0f)),
                    advance = 10.0f,
                ),
            ),
        )
        assertEquals(
            15.0f,
            shortGlyph.richTextBackgroundSegments(
                listOf(segment(TextRange(0, 2), left = 0.0f, right = 20.0f)),
            ).single().right,
        )

        // A run with no glyphs at all leaves the cluster edge in place.
        val emptyGlyphRun = result(
            "ab", clusters, lines,
            glyphRuns = listOf(
                GlyphRun(range = TextRange(1, 2), fontKey = "test", glyphs = emptyList(), advance = 10.0f),
            ),
        )
        assertEquals(
            20.0f,
            emptyGlyphRun.richTextBackgroundSegments(
                listOf(segment(TextRange(0, 2), left = 0.0f, right = 20.0f)),
            ).single().right,
        )
    }

    @Test
    fun clearanceNeedsSameRoleAndUsesTheSmallerSide() {
        testTrace.section("clearanceNeedsSameRoleAndUsesTheSmallerSide")
        val content = result(
            "ab",
            listOf(cluster(TextRange(0, 1), "a", 10.0f), cluster(TextRange(1, 2), "b", 10.0f)),
            listOf(line(TextRange(0, 2), 0..1)),
        )
        // Different roles never share clearance even with equal paints.
        val background = segment(
            TextRange(0, 1), paint = RichTextPaint(adjacentSameStyleClearance = 4.0f),
            left = 0.0f, right = 10.0f,
        )
        val inlineCode = segment(
            TextRange(1, 2), role = RichTextRole.InlineCode,
            paint = RichTextPaint(adjacentSameStyleClearance = 4.0f),
            left = 10.0f, right = 20.0f,
        )
        val byRole = content.richTextBackgroundSegments(listOf(background, inlineCode))
        assertEquals(10.0f, byRole[0].right)
        assertEquals(10.0f, byRole[1].left)

        // The shared edge yields min(2, 6) / 2 = 1 on each side.
        val weak = segment(
            TextRange(0, 1), paint = RichTextPaint(adjacentSameStyleClearance = 2.0f),
            left = 0.0f, right = 10.0f,
        )
        val strong = segment(
            TextRange(1, 2), paint = RichTextPaint(adjacentSameStyleClearance = 6.0f),
            left = 10.0f, right = 20.0f,
        )
        val cleared = content.richTextBackgroundSegments(listOf(weak, strong))
        assertEquals(9.0f, cleared[0].right)
        assertEquals(11.0f, cleared[1].left)
    }

    @Test
    fun metricDecisionsMustFullyContainTheCluster() {
        testTrace.section("metricDecisionsMustFullyContainTheCluster")
        fun boundsWith(decisionRange: TextRange): Pair<Float, Float> {
            val metric = MetricDecisionInfo(
                range = decisionRange, sourceText = "t", role = "body", fontKey = "test",
                rawAscent = 8.0f, rawDescent = 2.0f, rawLeading = 0.0f, rawSource = "stub",
                layoutAscent = 7.0f, layoutDescent = 3.0f, baselineClass = "ideographic",
                metricBox = "IdeographicEmBox", layoutSource = "normalized", reason = "test",
            )
            val content = result(
                "ab",
                listOf(cluster(TextRange(0, 2), "ab", 20.0f)),
                listOf(line(TextRange(0, 2), 0..0)),
                debug = LayoutDebugInfo(metricDecisions = listOf(metric)),
            )
            val box = content.richTextBackgroundSegments(
                listOf(segment(TextRange(0, 2), left = 0.0f, right = 20.0f)),
            ).single()
            return box.top to box.bottom
        }
        // A decision starting after the cluster or ending before it does not
        // cover the cluster, so both fall back to the 0.88/0.12 em box.
        assertEquals(15.0f - 10.0f * 0.88f, boundsWith(TextRange(1, 2)).first)
        assertEquals(15.0f + 10.0f * 0.12f, boundsWith(TextRange(1, 2)).second)
        assertEquals(15.0f - 10.0f * 0.88f, boundsWith(TextRange(0, 1)).first)
    }

    @Test
    fun decorationStyleResolvesInsideSpansAndAtTheirEdges() {
        testTrace.section("decorationStyleResolvesInsideSpansAndAtTheirEdges")
        val content = result(
            "abc",
            listOf(
                cluster(TextRange(0, 1), "a", 10.0f),
                cluster(TextRange(1, 2), "b", 10.0f),
                cluster(TextRange(2, 3), "c", 10.0f),
            ),
            listOf(line(TextRange(0, 3), 0..2)),
            spans = listOf(
                TextSpan(TextRange(0, 1), TextStyle(fontSize = 10.0f)),
                TextSpan(TextRange(2, 3), TextStyle(fontSize = 20.0f)),
            ),
        )
        // Offset 1 is the first span's end and before the second span, so the
        // span lookup misses and the paragraph style applies.
        val between = content.richTextDecorationLineY(
            segment(TextRange(1, 2), role = RichTextRole.Underline), 1.0f,
        )
        // Offset 2 sits inside the second span: the line iterates past the
        // non-matching first span and uses the 20pt style.
        val inside = content.richTextDecorationLineY(
            segment(TextRange(2, 3), role = RichTextRole.Underline), 1.0f,
        )
        assertEquals(15.0f + 10.0f * 0.18f, between)
        assertEquals(15.0f + 20.0f * 0.18f, inside)
    }

    @Test
    fun glueTrimSkipsInteriorSegmentEdges() {
        testTrace.section("glueTrimSkipsInteriorSegmentEdges")
        val glue = ClusterGeometryDecisionInfo(
            range = TextRange(0, 2), sourceText = "ab", displayText = "ab",
            baseAdvance = 20.0f, bodyWidth = 10.0f,
            leadingGlueNatural = 4.0f, leadingGlueConsumed = 1.0f,
            trailingGlueNatural = 4.0f, trailingGlueConsumed = 1.0f,
            justificationDelta = 0.0f,
            resolvedAdvance = 20.0f, source = "test", reason = "test",
        )
        val content = result(
            "ab",
            listOf(cluster(TextRange(0, 2), "ab", 20.0f)),
            listOf(line(TextRange(0, 2), 0..0)),
            debug = LayoutDebugInfo(geometryDecisions = listOf(glue)),
        )
        // A segment starting inside the cluster does not own its leading glue.
        val interiorStart = content.richTextBackgroundSegments(
            listOf(segment(TextRange(1, 2), left = 10.0f, right = 20.0f)),
        ).single()
        assertEquals(10.0f, interiorStart.left)
        // A segment ending inside the cluster does not own its trailing glue.
        val interiorEnd = content.richTextBackgroundSegments(
            listOf(segment(TextRange(0, 1), left = 0.0f, right = 10.0f)),
        ).single()
        assertEquals(10.0f, interiorEnd.right)
    }

    @Test
    fun backgroundSegmentOutsideEverySpanUsesTheParagraphStyle() {
        testTrace.section("backgroundSegmentOutsideEverySpanUsesTheParagraphStyle")
        val content = result(
            "abc",
            listOf(
                cluster(TextRange(0, 1), "a", 10.0f),
                cluster(TextRange(1, 2), "b", 10.0f),
                cluster(TextRange(2, 3), "c", 10.0f),
            ),
            listOf(line(TextRange(0, 3), 0..2)),
            spans = listOf(TextSpan(TextRange(1, 2), TextStyle(fontSize = 40.0f))),
        )
        // Segment start 0 is before the span; start 2 is at its end.
        val before = content.richTextBackgroundSegments(
            listOf(segment(TextRange(0, 1), left = 0.0f, right = 10.0f)),
        ).single()
        assertEquals(15.0f - 10.0f * 0.88f, before.top)
        val atEnd = content.richTextBackgroundSegments(
            listOf(segment(TextRange(2, 3), left = 20.0f, right = 30.0f)),
        ).single()
        assertEquals(15.0f - 10.0f * 0.88f, atEnd.top)
    }

    @Test
    fun cursorRectFindsLaterClustersAndRejectsGappedRanges() {
        testTrace.section("cursorRectFindsLaterClustersAndRejectsGappedRanges")
        val content = result(
            "abc",
            listOf(
                cluster(TextRange(0, 1), "a", 10.0f),
                cluster(TextRange(1, 2), "b", 10.0f),
                cluster(TextRange(2, 3), "c", 10.0f),
            ),
            listOf(line(TextRange(0, 3), 0..2)),
        )
        assertEquals(20.0f, content.getCursorRect(2).left)

        // A hand-built line whose clusters skip source offsets has no
        // containing cluster for the skipped caret offset.
        val gapped = result(
            "abcde",
            listOf(
                cluster(TextRange(0, 1), "a", 10.0f),
                cluster(TextRange(4, 5), "e", 10.0f),
            ),
            listOf(line(TextRange(0, 5), 0..1)),
        )
        assertFailsWith<NoSuchElementException> { gapped.getCursorRect(2) }
    }

    @Test
    fun emptyMidClusterHoldsTheCaretAndSlicesKeepDegenerateRects() {
        testTrace.section("emptyMidClusterHoldsTheCaretAndSlicesKeepDegenerateRects")
        val content = result(
            "abc",
            listOf(
                cluster(TextRange(0, 1), "a", 10.0f),
                cluster(TextRange(2, 2), "", 0.0f),
                cluster(TextRange(2, 3), "c", 10.0f),
            ),
            listOf(line(TextRange(0, 3), 0..2)),
        )
        // The caret at 2 matches the empty cluster and returns its left edge.
        assertEquals(10.0f, content.getCursorRect(2).left)

        val withEmpty = result(
            "ab",
            listOf(
                cluster(TextRange(0, 1), "a", 10.0f),
                cluster(TextRange(1, 1), "", 0.0f),
                cluster(TextRange(1, 2), "b", 10.0f),
            ),
            listOf(line(TextRange(0, 2), 0..2)),
        )
        val boxes = withEmpty.getBoundingBoxes(TextRange(0, 2))
        // The empty middle cluster never yields a slice; the two occupied
        // clusters do.
        assertEquals(2, boxes.size)
        assertEquals(0.0f, boxes[0].left)
        assertEquals(10.0f, boxes[0].right)
        assertEquals(10.0f, boxes[1].left)
        assertEquals(20.0f, boxes[1].right)

        // A non-empty cluster with zero advance yields its own degenerate box
        // instead of a proportional slice.
        val zeroAdvance = result(
            "abc",
            listOf(
                cluster(TextRange(0, 1), "a", 10.0f),
                cluster(TextRange(1, 2), "b", 0.0f),
                cluster(TextRange(2, 3), "c", 10.0f),
            ),
            listOf(line(TextRange(0, 3), 0..2)),
        )
        val degenerate = zeroAdvance.getBoundingBoxes(TextRange(0, 3))
        assertEquals(3, degenerate.size)
        assertEquals(10.0f, degenerate[1].left)
        assertEquals(10.0f, degenerate[1].right)
    }

    @Test
    fun selectionWordBoundarySkipsInlineObjectsItDoesNotContain() {
        testTrace.section("selectionWordBoundarySkipsInlineObjectsItDoesNotContain")
        val content = result(
            "abcdefg",
            listOf(cluster(TextRange(0, 7), "abcdefg", 70.0f)),
            listOf(line(TextRange(0, 7), 0..0)),
            inlineObjects = listOf(
                InlineObjectSpan(TextRange(1, 3), advance = 8.0f, ascent = 4.0f, descent = 4.0f),
                InlineObjectSpan(TextRange(5, 7), advance = 8.0f, ascent = 4.0f, descent = 4.0f),
            ),
        )
        // Offset 4 sits between the two objects: both containment tests fail
        // and the boundary scan returns the whole Latin word run.
        assertEquals(TextRange(0, 7), content.getSelectionWordBoundary(4))
        assertEquals(TextRange(1, 3), content.getSelectionWordBoundary(2))
    }

    @Test
    fun selectionWordBoundaryForPositionCoversDistancesAndFallbacks() {
        testTrace.section("selectionWordBoundaryForPositionCoversDistancesAndFallbacks")
        val content = result(
            "甲乙",
            listOf(cluster(TextRange(0, 1), "甲", 10.0f), cluster(TextRange(1, 2), "乙", 10.0f)),
            listOf(line(TextRange(0, 2), 0..1)),
        )
        // A y inside the line takes the zero-distance arm.
        assertEquals(TextRange(0, 1), content.getSelectionWordBoundaryForPosition(5.0f, 10.0f))
        // A y above or below the line takes the distance arms of the line search.
        assertEquals(TextRange(0, 1), content.getSelectionWordBoundaryForPosition(5.0f, -10.0f))
        assertEquals(TextRange(0, 1), content.getSelectionWordBoundaryForPosition(5.0f, 60.0f))
        // An x beyond every box falls back to the nearest box on that line.
        assertEquals(TextRange(0, 1), content.getSelectionWordBoundaryForPosition(-50.0f, 10.0f))
        assertEquals(TextRange(1, 2), content.getSelectionWordBoundaryForPosition(500.0f, 10.0f))
    }

    @Test
    fun lineForOffsetInsideARangeTakesTheZeroDistanceArm() {
        testTrace.section("lineForOffsetInsideARangeTakesTheZeroDistanceArm")
        val content = result(
            "abcde",
            listOf(
                cluster(TextRange(0, 1), "a", 10.0f),
                cluster(TextRange(4, 5), "e", 10.0f),
            ),
            listOf(
                line(TextRange(0, 2), 0..0),
                line(TextRange(4, 5), 1..1, top = 20.0f, bottom = 40.0f, baseline = 35.0f),
            ),
        )
        assertEquals(0, content.getLineForOffset(1))
    }

    @Test
    fun compatibilityIdeographsFormIndividualWordUnits() {
        testTrace.section("compatibilityIdeographsFormIndividualWordUnits")
        // U+20000 (ext-B) and U+F900 (compatibility ideograph) both classify as
        // single-ideograph word units.
        val text = "𠀀豈"
        val content = result(
            text,
            listOf(
                cluster(TextRange(0, 2), "𠀀", 10.0f),
                cluster(TextRange(2, 3), "豈", 10.0f),
            ),
            listOf(line(TextRange(0, 3), 0..1)),
        )
        assertEquals(TextRange(0, 2), content.getSelectionWordBoundary(0))
        assertEquals(TextRange(2, 3), content.getSelectionWordBoundary(2))
    }

    @Test
    fun rubySpreadShiftsSelectionBoxesAndZeroWidthRubiesAreIgnored() {
        testTrace.section("rubySpreadShiftsSelectionBoxesAndZeroWidthRubiesAreIgnored")
        val clusters = listOf(
            cluster(TextRange(0, 2), "ab", 20.0f),
            cluster(TextRange(2, 3), "c", 10.0f),
        )
        val spreads = LayoutDebugInfo(
            geometryDecisions = listOf(
                ClusterGeometryDecisionInfo(
                    range = TextRange(0, 2), sourceText = "ab", displayText = "ab",
                    baseAdvance = 20.0f, bodyWidth = 10.0f,
                    leadingGlueNatural = 0.0f, leadingGlueConsumed = 0.0f,
                    trailingGlueNatural = 0.0f, trailingGlueConsumed = 0.0f,
                    justificationDelta = 0.0f,
                    rubySpread = 5.0f,
                    resolvedAdvance = 20.0f, source = "test", reason = "test",
                ),
                ClusterGeometryDecisionInfo(
                    range = TextRange(2, 3), sourceText = "c", displayText = "c",
                    baseAdvance = 10.0f, bodyWidth = 10.0f,
                    leadingGlueNatural = 0.0f, leadingGlueConsumed = 0.0f,
                    trailingGlueNatural = 0.0f, trailingGlueConsumed = 0.0f,
                    justificationDelta = 0.0f,
                    rubySpread = 2.0f,
                    resolvedAdvance = 10.0f, source = "test", reason = "test",
                ),
            ),
            rubyDecisions = listOf(
                RubyDecisionInfo(
                    baseRange = TextRange(0, 3), text = "zhù", lineIndex = 0,
                    centerX = 15.0f, baselineY = 4.0f, fontSize = 6.0f, overhang = 0.0f, width = 30.0f,
                ),
                // A zero-width ruby is inert.
                RubyDecisionInfo(
                    baseRange = TextRange(2, 3), text = "x", lineIndex = 0,
                    centerX = 25.0f, baselineY = 4.0f, fontSize = 6.0f, overhang = 0.0f, width = 0.0f,
                ),
                // A ruby whose base range covers no positioned cluster is skipped.
                RubyDecisionInfo(
                    baseRange = TextRange(5, 6), text = "y", lineIndex = 0,
                    centerX = 25.0f, baselineY = 4.0f, fontSize = 6.0f, overhang = 0.0f, width = 6.0f,
                ),
            ),
        )
        val content = result(
            "abc",
            clusters,
            listOf(line(TextRange(0, 3), 0..1)),
            debug = spreads,
        )
        val positioned = content.positionedClusters()
        // Without glyph runs the natural advance is width minus spread: centers
        // 7.5 and 24 split at 15.75; each box first loses its own spread on the
        // right.
        assertEquals(0.0f, positioned[0].left)
        assertEquals(15.75f, positioned[0].right)
        assertEquals(15.75f, positioned[1].left)
        assertEquals(30.0f, positioned[1].right)

        // With shaped glyph advances the centers come from the glyph totals:
        // 16/8 give centers 8 and 24, so the split lands at 16.
        val withGlyphs = result(
            "abc",
            clusters,
            listOf(line(TextRange(0, 3), 0..1)),
            glyphRuns = listOf(
                GlyphRun(
                    range = TextRange(0, 3), fontKey = "test",
                    glyphs = listOf(
                        Glyph(1u, TextRange(0, 2), advance = 16.0f, x = 0.0f),
                        Glyph(2u, TextRange(2, 3), advance = 8.0f, x = 0.0f),
                    ),
                    advance = 30.0f,
                ),
            ),
            debug = spreads,
        )
        val glyphPositioned = withGlyphs.positionedClusters()
        assertEquals(0.0f, glyphPositioned[0].left)
        assertEquals(16.0f, glyphPositioned[0].right)
        assertEquals(16.0f, glyphPositioned[1].left)
        assertEquals(30.0f, glyphPositioned[1].right)
    }

    @Test
    fun noArgPositionedClustersWalksEveryLine() {
        testTrace.section("noArgPositionedClustersWalksEveryLine")
        val content = result(
            "abcd",
            listOf(
                cluster(TextRange(0, 1), "a", 10.0f),
                cluster(TextRange(1, 2), "b", 10.0f),
                cluster(TextRange(2, 3), "c", 10.0f),
                cluster(TextRange(3, 4), "d", 10.0f),
            ),
            listOf(
                line(TextRange(0, 2), 0..1),
                line(TextRange(2, 4), 2..3, top = 20.0f, bottom = 40.0f, baseline = 35.0f),
                // A line with an empty cluster range contributes an empty list,
                // so the flatMap destination sees an empty addAll.
                line(TextRange(4, 4), 2..1, top = 40.0f, bottom = 60.0f, baseline = 55.0f),
            ),
        )
        val positioned = content.positionedClusters()
        assertEquals(4, positioned.size)
        assertEquals(0, positioned[0].lineIndex)
        assertEquals(1, positioned[2].lineIndex)
        // Line 1 restarts at its own indent, so its second cluster ends at 20.
        assertEquals(20.0f, positioned[3].right)
    }

    @Test
    fun glyphInkBoundsRejectsEachNonFiniteEdgeIndependently() {
        testTrace.section("glyphInkBoundsRejectsEachNonFiniteEdgeIndependently")
        fun inkWith(bounds: Rect): Rect? {
            val content = result(
                "ab",
                listOf(cluster(TextRange(0, 1), "a", 10.0f), cluster(TextRange(1, 2), "b", 10.0f)),
                listOf(line(TextRange(0, 2), 0..1)),
                glyphRuns = listOf(
                    GlyphRun(
                        range = TextRange(0, 2), fontKey = "test",
                        glyphs = listOf(Glyph(1u, TextRange(0, 1), advance = 10.0f, bounds = bounds)),
                        advance = 20.0f,
                    ),
                ),
            )
            return content.glyphInkBounds()
        }
        // One poisoned edge per call drives the finite check chain through each
        // condition's false path before the poisoned one returns null.
        assertNull(inkWith(Rect(Float.NaN, 2.0f, 8.0f, 4.0f)))
        assertNull(inkWith(Rect(1.0f, Float.NaN, 8.0f, 4.0f)))
        assertNull(inkWith(Rect(1.0f, 2.0f, Float.NaN, 4.0f)))
        assertNull(inkWith(Rect(1.0f, 2.0f, 8.0f, Float.NaN)))
    }

    @Test
    fun clearanceTakesTheSmallerSideWhicheverSegmentOwnsIt() {
        testTrace.section("clearanceTakesTheSmallerSideWhicheverSegmentOwnsIt")
        val content = result(
            "ab",
            listOf(cluster(TextRange(0, 1), "a", 10.0f), cluster(TextRange(1, 2), "b", 10.0f)),
            listOf(line(TextRange(0, 2), 0..1)),
        )
        // Reversed asymmetry: min(6, 2) / 2 = 1 again, exercising minOf's other
        // comparison arm.
        val weakFirst = segment(
            TextRange(0, 1), paint = RichTextPaint(adjacentSameStyleClearance = 6.0f),
            left = 0.0f, right = 10.0f,
        )
        val strongSecond = segment(
            TextRange(1, 2), paint = RichTextPaint(adjacentSameStyleClearance = 2.0f),
            left = 10.0f, right = 20.0f,
        )
        val cleared = content.richTextBackgroundSegments(listOf(weakFirst, strongSecond))
        assertEquals(9.0f, cleared[0].right)
        assertEquals(11.0f, cleared[1].left)

        // A same-end neighbour earlier in the list that does not share the style
        // is scanned past; the later matching one shares the clearance.
        val styledA = segment(
            TextRange(1, 2), paint = RichTextPaint(adjacentSameStyleClearance = 4.0f),
            left = 10.0f, right = 20.0f,
        )
        val scanPast = content.richTextBackgroundSegments(
            listOf(
                segment(
                    TextRange(1, 2), role = RichTextRole.InlineCode,
                    paint = RichTextPaint(adjacentSameStyleClearance = 4.0f),
                    left = 10.0f, right = 20.0f,
                ),
                segment(
                    TextRange(0, 1),
                    paint = RichTextPaint(adjacentSameStyleClearance = 4.0f),
                    left = 0.0f, right = 10.0f,
                ),
                styledA,
            ),
        )
        assertEquals(3, scanPast.size)
        // min(4, 4) / 2 = 2: the middle segment gives up 2pt to its neighbour.
        assertEquals(8.0f, scanPast[1].right)
        assertEquals(12.0f, scanPast[2].left)
    }

    @Test
    fun uniformTextStylePolicyResolvesSpanStyleOrParagraphStyle() {
        testTrace.section("uniformTextStylePolicyResolvesSpanStyleOrParagraphStyle")
        val uniform = RichTextPaint(
            background = RichTextBackgroundPaint(
                metricPolicy = RichTextBackgroundMetricPolicy.UniformTextStyle,
            ),
        )
        val content = result(
            "abc",
            listOf(
                cluster(TextRange(0, 1), "a", 10.0f),
                cluster(TextRange(1, 2), "b", 10.0f),
                cluster(TextRange(2, 3), "c", 10.0f),
            ),
            listOf(line(TextRange(0, 3), 0..2)),
            spans = listOf(TextSpan(TextRange(1, 2), TextStyle(fontSize = 40.0f))),
        )
        // Start 0 misses the only span, so the paragraph style (10pt) applies.
        val outside = content.richTextBackgroundSegments(
            listOf(segment(TextRange(0, 1), paint = uniform, left = 0.0f, right = 10.0f)),
        ).single()
        assertEquals(15.0f - 10.0f * 0.88f, outside.top)
        // Start 1 sits inside the 40pt span: the ascent overshoots the segment
        // top and is clamped to it, unlike the 10pt case above.
        val inside = content.richTextBackgroundSegments(
            listOf(segment(TextRange(1, 2), paint = uniform, left = 10.0f, right = 20.0f)),
        ).single()
        assertEquals(0.0f, inside.top)
    }

    @Test
    fun trailingGlueIsSkippedWhenNoClusterEndsBeforeTheSegmentEnd() {
        testTrace.section("trailingGlueIsSkippedWhenNoClusterEndsBeforeTheSegmentEnd")
        // The line's only cluster starts at 1, so no cluster has range.start
        // below the segment end 1: the trailing neighbour lookup yields null.
        val content = result(
            "ab",
            listOf(cluster(TextRange(1, 2), "b", 10.0f)),
            listOf(line(TextRange(0, 2), 0..0)),
        )
        val out = content.richTextBackgroundSegments(
            listOf(segment(TextRange(0, 1), left = 0.0f, right = 10.0f)),
        ).single()
        assertEquals(10.0f, out.right)
    }

    @Test
    fun decorationLineYWithoutSpansUsesTheParagraphStyle() {
        testTrace.section("decorationLineYWithoutSpansUsesTheParagraphStyle")
        val content = result(
            "ab",
            listOf(cluster(TextRange(0, 2), "ab", 20.0f)),
            listOf(line(TextRange(0, 2), 0..0)),
        )
        val y = content.richTextDecorationLineY(
            segment(TextRange(0, 2), role = RichTextRole.Underline),
            1.0f,
        )
        assertEquals(15.0f + 10.0f * 0.18f, y)
    }

    @Test
    fun wordBoundaryForPositionHandlesANonFiniteY() {
        testTrace.section("wordBoundaryForPositionHandlesANonFiniteY")
        val content = result(
            "甲乙",
            listOf(cluster(TextRange(0, 1), "甲", 10.0f), cluster(TextRange(1, 2), "乙", 10.0f)),
            listOf(line(TextRange(0, 2), 0..1)),
        )
        // A NaN y matches no ordering arm, so every line scores 0 and the first
        // line wins.
        assertEquals(TextRange(0, 1), content.getSelectionWordBoundaryForPosition(5.0f, Float.NaN))
    }

    @Test
    fun supplementaryIdeographBeyondTheHanRangesIsItsOwnUnit() {
        testTrace.section("supplementaryIdeographBeyondTheHanRangesIsItsOwnUnit")
        // U+30000 sits inside the 0x20000..0x323AF band, exercising its lower
        // and upper bound comparisons in the true direction.
        val text = "𰀀"
        val content = result(
            text,
            listOf(cluster(TextRange(0, 2), text, 10.0f)),
            listOf(line(TextRange(0, 2), 0..0)),
        )
        assertEquals(TextRange(0, 2), content.getSelectionWordBoundary(0))
    }

    @Test
    fun planeFourCodepointAboveTheHanBandsIsItsOwnUnit() {
        testTrace.section("planeFourCodepointAboveTheHanBandsIsItsOwnUnit")
        // U+40000 clears the 0x20000 lower bound but exceeds the 0x323AF upper
        // bound, so the final range test fails through its upper comparison.
        val text = "񀀀"
        val content = result(
            text,
            listOf(cluster(TextRange(0, 2), text, 10.0f)),
            listOf(line(TextRange(0, 2), 0..0)),
        )
        assertEquals(TextRange(0, 2), content.getSelectionWordBoundary(0))
    }

    @Test
    fun nearestLineSearchCoversAllThreeDistanceArms() {
        testTrace.section("nearestLineSearchCoversAllThreeDistanceArms")
        val content = result(
            "abcde",
            listOf(
                cluster(TextRange(0, 1), "a", 10.0f),
                cluster(TextRange(4, 5), "e", 10.0f),
            ),
            listOf(
                line(TextRange(0, 2), 0..0),
                line(TextRange(4, 5), 1..1, top = 20.0f, bottom = 40.0f, baseline = 35.0f),
            ),
        )
        // Offset 2 is inside no line range but within line 0's [start, end]
        // span, so the zero-distance arm fires, line 0 wins, and the caret
        // lands on its last cluster edge.
        assertEquals(10.0f, content.getCursorRect(2).left)
        // Offset 3 is before line 1's start and past line 0's end, so both
        // distance arms compute; the tie resolves to line 0.
        assertEquals(10.0f, content.getCursorRect(3).left)
    }

    @Test
    fun rubiesOnOtherLinesDoNotAffectThisLineGeometry() {
        testTrace.section("rubiesOnOtherLinesDoNotAffectThisLineGeometry")
        val content = result(
            "ab",
            listOf(
                cluster(TextRange(0, 1), "a", 10.0f),
                cluster(TextRange(1, 2), "b", 10.0f),
            ),
            listOf(line(TextRange(0, 2), 0..1)),
            debug = LayoutDebugInfo(
                rubyDecisions = listOf(
                    RubyDecisionInfo(
                        baseRange = TextRange(0, 2), text = "zhù", lineIndex = 1,
                        centerX = 10.0f, baselineY = 4.0f, fontSize = 6.0f, overhang = 0.0f, width = 30.0f,
                    ),
                ),
            ),
        )
        val positioned = content.positionedClusters()
        // The ruby belongs to line 1; line 0 keeps its natural boxes.
        assertEquals(0.0f, positioned[0].left)
        assertEquals(10.0f, positioned[0].right)
        assertEquals(10.0f, positioned[1].left)
        assertEquals(20.0f, positioned[1].right)
    }

    @Test
    fun backgroundTrailingEdgePicksTheLargestGlyphAdvance() {
        testTrace.section("backgroundTrailingEdgePicksTheLargestGlyphAdvance")
        // Two glyphs on the last cluster: the larger advance (6) wins over the
        // first (5), so the trailing edge is drawX + 6.
        val content = result(
            "ab",
            listOf(cluster(TextRange(0, 1), "a", 10.0f), cluster(TextRange(1, 2), "b", 10.0f)),
            listOf(line(TextRange(0, 2), 0..1)),
            glyphRuns = listOf(
                GlyphRun(
                    range = TextRange(1, 2), fontKey = "test",
                    glyphs = listOf(
                        Glyph(1u, TextRange(1, 2), advance = 5.0f, x = 0.0f),
                        Glyph(2u, TextRange(1, 2), advance = 6.0f, x = 0.0f),
                    ),
                    advance = 10.0f,
                ),
            ),
        )
        assertEquals(
            16.0f,
            content.richTextBackgroundSegments(
                listOf(segment(TextRange(0, 2), left = 0.0f, right = 20.0f)),
            ).single().right,
        )
    }

    @Test
    fun backgroundTrailingEdgeKeepsTheFirstGlyphWhenItIsLargest() {
        testTrace.section("backgroundTrailingEdgeKeepsTheFirstGlyphWhenItIsLargest")
        // Glyph order [6, 5]: the running maximum never updates on the second
        // glyph, covering the comparison's false direction.
        val content = result(
            "ab",
            listOf(cluster(TextRange(0, 1), "a", 10.0f), cluster(TextRange(1, 2), "b", 10.0f)),
            listOf(line(TextRange(0, 2), 0..1)),
            glyphRuns = listOf(
                GlyphRun(
                    range = TextRange(1, 2), fontKey = "test",
                    glyphs = listOf(
                        Glyph(1u, TextRange(1, 2), advance = 6.0f, x = 0.0f),
                        Glyph(2u, TextRange(1, 2), advance = 5.0f, x = 0.0f),
                    ),
                    advance = 10.0f,
                ),
            ),
        )
        assertEquals(
            16.0f,
            content.richTextBackgroundSegments(
                listOf(segment(TextRange(0, 2), left = 0.0f, right = 20.0f)),
            ).single().right,
        )
    }

    @Test
    fun selectionWordBoundaryForPositionPrefersTheCloserLaterLine() {
        testTrace.section("selectionWordBoundaryForPositionPrefersTheCloserLaterLine")
        val content = result(
            "甲乙丙丁",
            listOf(
                cluster(TextRange(0, 1), "甲", 10.0f),
                cluster(TextRange(1, 2), "乙", 10.0f),
                cluster(TextRange(2, 3), "丙", 10.0f),
                cluster(TextRange(3, 4), "丁", 10.0f),
            ),
            listOf(
                line(TextRange(0, 2), 0..1),
                line(TextRange(2, 4), 2..3, top = 40.0f, bottom = 60.0f, baseline = 55.0f),
            ),
        )
        // The point sits inside line 1, so the line search updates its minimum
        // on the second element and resolves clusters there.
        assertEquals(TextRange(2, 3), content.getSelectionWordBoundaryForPosition(5.0f, 50.0f))
        // A y equidistant from both lines scores equal distances; the first
        // line wins the tie without a minimum update.
        assertEquals(TextRange(0, 1), content.getSelectionWordBoundaryForPosition(5.0f, 30.0f))
        // A y above line 0 takes the first element's below-top arm; line 0
        // stays the minimum against line 1's larger distance.
        assertEquals(TextRange(0, 1), content.getSelectionWordBoundaryForPosition(5.0f, -10.0f))
        // A y inside line 0 takes the first element's zero-distance arm with
        // both comparisons false.
        assertEquals(TextRange(0, 1), content.getSelectionWordBoundaryForPosition(5.0f, 10.0f))
        // A y below line 1 takes the later element's above-bottom arm, and
        // line 1 wins by staying closer.
        assertEquals(TextRange(2, 3), content.getSelectionWordBoundaryForPosition(5.0f, 100.0f))
    }

    @Test
    fun nearestLineSearchUpdatesToAStrictlyCloserLaterLine() {
        testTrace.section("nearestLineSearchUpdatesToAStrictlyCloserLaterLine")
        val content = result(
            "abcde",
            listOf(
                cluster(TextRange(0, 1), "a", 10.0f),
                cluster(TextRange(5, 6), "e", 10.0f),
            ),
            listOf(
                line(TextRange(0, 2), 0..0),
                line(
                    TextRange(5, 7), 1..1,
                    top = 20.0f, bottom = 40.0f, baseline = 35.0f, indent = 10.0f,
                ),
            ),
        )
        // Offset 4 sits one unit before line 1 and two past line 0, so the
        // search updates its minimum to the later line; its first cluster box
        // starts at that line's indent.
        assertEquals(10.0f, content.getCursorRect(4).left)
    }

    @Test
    fun nearestLineSearchCoversBothLambdaCopiesOfEachArm() {
        testTrace.section("nearestLineSearchCoversBothLambdaCopiesOfEachArm")
        // The inlined minBy evaluates the first line with its own copy of the
        // distance when, so this fixture drives each arm through both the
        // first-element copy and the loop copy.
        val content = result(
            "abcdefghij",
            listOf(
                cluster(TextRange(2, 3), "c", 10.0f),
                cluster(TextRange(3, 4), "d", 10.0f),
                cluster(TextRange(6, 7), "g", 10.0f),
                cluster(TextRange(7, 8), "h", 10.0f),
            ),
            listOf(
                line(TextRange(2, 4), 0..1),
                line(TextRange(6, 8), 2..3, top = 20.0f, bottom = 40.0f, baseline = 35.0f),
            ),
        )
        // Offset 1 falls before line 0's start: the first element's below-start
        // arm fires and line 0 wins on distance.
        assertEquals(0.0f, content.getCursorRect(1).left)
        // Offset 8 equals line 1's end, so no line range contains it; line 1
        // scores zero through the loop copy's else arm and wins. The caret is
        // the one-unit box at that line's trailing edge.
        assertEquals(20.0f, content.getCursorRect(8).left)
        // Offset 9 sits past line 1's end: the loop copy's past-end arm fires
        // and line 1 still wins on distance.
        assertEquals(20.0f, content.getCursorRect(9).left)
    }

    @Test
    fun uniformTextStylePolicyPicksTheLastMatchingSpan() {
        testTrace.section("uniformTextStylePolicyPicksTheLastMatchingSpan")
        val uniform = RichTextPaint(
            background = RichTextBackgroundPaint(
                metricPolicy = RichTextBackgroundMetricPolicy.UniformTextStyle,
            ),
        )
        val content = result(
            "abc",
            listOf(
                cluster(TextRange(0, 1), "a", 10.0f),
                cluster(TextRange(1, 2), "b", 10.0f),
                cluster(TextRange(2, 3), "c", 10.0f),
            ),
            listOf(line(TextRange(0, 3), 0..2)),
            spans = listOf(
                TextSpan(TextRange(0, 2), TextStyle(fontSize = 10.0f)),
                TextSpan(TextRange(1, 3), TextStyle(fontSize = 40.0f)),
            ),
        )
        // Offset 2 matches only the later span, so the scan replaces its
        // candidate; the 40pt ascent overshoots the segment top and clamps.
        val inside = content.richTextBackgroundSegments(
            listOf(segment(TextRange(2, 3), paint = uniform, left = 20.0f, right = 30.0f)),
        ).single()
        assertEquals(0.0f, inside.top)
    }

    @Test
    fun decorationLineYPicksTheLastMatchingSpan() {
        testTrace.section("decorationLineYPicksTheLastMatchingSpan")
        val content = result(
            "abc",
            listOf(
                cluster(TextRange(0, 1), "a", 10.0f),
                cluster(TextRange(1, 2), "b", 10.0f),
                cluster(TextRange(2, 3), "c", 10.0f),
            ),
            listOf(line(TextRange(0, 3), 0..2)),
            spans = listOf(
                TextSpan(TextRange(0, 2), TextStyle(fontSize = 10.0f)),
                TextSpan(TextRange(1, 3), TextStyle(fontSize = 20.0f)),
            ),
        )
        // The segment start 2 skips past the first span and matches the later
        // one, so the scan replaces its candidate while iterating.
        val y = content.richTextDecorationLineY(
            segment(TextRange(2, 3), role = RichTextRole.Underline),
            1.0f,
        )
        assertEquals(15.0f + 20.0f * 0.18f, y)
    }

    @Test
    fun uniformTextStylePolicyKeepsTheEarlierSpanWhenALaterOneMisses() {
        testTrace.section("uniformTextStylePolicyKeepsTheEarlierSpanWhenALaterOneMisses")
        val uniform = RichTextPaint(
            background = RichTextBackgroundPaint(
                metricPolicy = RichTextBackgroundMetricPolicy.UniformTextStyle,
            ),
        )
        val content = result(
            "abc",
            listOf(
                cluster(TextRange(0, 1), "a", 10.0f),
                cluster(TextRange(1, 2), "b", 10.0f),
                cluster(TextRange(2, 3), "c", 10.0f),
            ),
            listOf(line(TextRange(0, 3), 0..2)),
            spans = listOf(
                TextSpan(TextRange(0, 3), TextStyle(fontSize = 40.0f)),
                TextSpan(TextRange(1, 2), TextStyle(fontSize = 10.0f)),
            ),
        )
        // Offset 0 matches the wide span and then rejects the narrow later
        // one, so the scan sees a match followed by a miss.
        val out = content.richTextBackgroundSegments(
            listOf(segment(TextRange(0, 1), paint = uniform, left = 0.0f, right = 10.0f)),
        ).single()
        assertEquals(0.0f, out.top)
    }

    @Test
    fun decorationLineYKeepsTheEarlierSpanWhenALaterOneMisses() {
        testTrace.section("decorationLineYKeepsTheEarlierSpanWhenALaterOneMisses")
        val content = result(
            "abc",
            listOf(
                cluster(TextRange(0, 1), "a", 10.0f),
                cluster(TextRange(1, 2), "b", 10.0f),
                cluster(TextRange(2, 3), "c", 10.0f),
            ),
            listOf(line(TextRange(0, 3), 0..2)),
            spans = listOf(
                TextSpan(TextRange(0, 3), TextStyle(fontSize = 20.0f)),
                TextSpan(TextRange(1, 2), TextStyle(fontSize = 10.0f)),
            ),
        )
        // The segment start 0 matches the wide span first and rejects the
        // narrow later one, so the scan keeps the earlier candidate.
        val y = content.richTextDecorationLineY(
            segment(TextRange(0, 1), role = RichTextRole.Underline),
            1.0f,
        )
        assertEquals(15.0f + 20.0f * 0.18f, y)
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
