package org.tiqian.core

import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.assertTrue
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

class CoreLayoutQueriesGapsTest {
    private val testTrace = TestTraceRecorder("CoreLayoutQueriesGapsTest")


    @Test
    fun positionedClusterHeightReturnsDifference() {
        testTrace.section("positionedClusterHeightReturnsDifference")
        val result = sampleResult()
        assertEquals(20.0f, result.positionedClusters().first().height)
    }

    @Test
    fun getLineForOffsetUsesNearestLineWhenGapBetweenLines() {
        testTrace.section("getLineForOffsetUsesNearestLineWhenGapBetweenLines")
        val result = LayoutResult(
            input = LayoutInput(
                content = TiqianTextContent("abcde"),
                constraints = LayoutConstraints(maxWidth = 100.0f),
            ),
            size = Size(10.0f, 40.0f),
            clusters = listOf(
                Cluster(TextRange(0, 1), "a", fontKey = "cjk", advance = 10.0f),
                Cluster(TextRange(1, 2), "b", fontKey = "cjk", advance = 10.0f),
                Cluster(TextRange(2, 3), "c", fontKey = "cjk", advance = 10.0f),
                Cluster(TextRange(4, 5), "e", fontKey = "cjk", advance = 10.0f),
            ),
            glyphRuns = emptyList(),
            lines = listOf(
                LineBox(
                    range = TextRange(0, 2), clusterRange = 0..1,
                    baseline = 15.0f, top = 0.0f, bottom = 20.0f,
                    naturalWidth = 20.0f, adjustedWidth = 20.0f, visualWidth = 20.0f,
                ),
                LineBox(
                    range = TextRange(4, 5), clusterRange = 2..3,
                    baseline = 35.0f, top = 25.0f, bottom = 45.0f,
                    naturalWidth = 10.0f, adjustedWidth = 10.0f, visualWidth = 10.0f,
                ),
            ),
        )
        assertEquals(0, result.getLineForOffset(3))
    }

    @Test
    fun getBoundingBoxesIntDelegatesToTextRange() {
        testTrace.section("getBoundingBoxesIntDelegatesToTextRange")
        val result = sampleResult()
        val fromInt = result.getBoundingBoxes(2, 4)
        val fromRange = result.getBoundingBoxes(TextRange(2, 4))
        assertEquals(fromRange, fromInt)
    }

    @Test
    fun richTextBackgroundUsesHorizontalPadding() {
        testTrace.section("richTextBackgroundUsesHorizontalPadding")
        val result = LayoutResult(
            input = LayoutInput(
                content = TiqianTextContent("AB"),
                textStyle = TextStyle(fontSize = 10.0f),
                constraints = LayoutConstraints(maxWidth = 100.0f),
            ),
            size = Size(20.0f, 20.0f),
            clusters = listOf(
                Cluster(TextRange(0, 1), "A", fontKey = "latin", advance = 10.0f),
                Cluster(TextRange(1, 2), "B", fontKey = "latin", advance = 10.0f),
            ),
            glyphRuns = emptyList(),
            lines = listOf(
                LineBox(
                    range = TextRange(0, 2), clusterRange = 0..1,
                    baseline = 15.0f, top = 0.0f, bottom = 20.0f,
                    naturalWidth = 20.0f, adjustedWidth = 20.0f, visualWidth = 20.0f,
                ),
            ),
            debug = LayoutDebugInfo(),
        )
        val span = RichTextSpan(
            TextRange(0, 2), RichTextRole.Background,
            RichTextPaint(background = RichTextBackgroundPaint(horizontalPadding = 5.0f)),
        )
        val segments = result.richTextBackgroundSegments(
            result.positionedRichTextSegments(listOf(span)),
        )
        assertEquals(1, segments.size)
    }

    @Test
    fun richTextBackgroundTrailingPaddingWhenSpanEndsAtSegmentEnd() {
        testTrace.section("richTextBackgroundTrailingPaddingWhenSpanEndsAtSegmentEnd")
        val result = LayoutResult(
            input = LayoutInput(
                content = TiqianTextContent("AB"),
                textStyle = TextStyle(fontSize = 10.0f),
                constraints = LayoutConstraints(maxWidth = 100.0f),
            ),
            size = Size(20.0f, 20.0f),
            clusters = listOf(
                Cluster(TextRange(0, 1), "A", fontKey = "latin", advance = 10.0f),
                Cluster(TextRange(1, 2), "B", fontKey = "latin", advance = 10.0f),
            ),
            glyphRuns = emptyList(),
            lines = listOf(
                LineBox(
                    range = TextRange(0, 2), clusterRange = 0..1,
                    baseline = 15.0f, top = 0.0f, bottom = 20.0f,
                    naturalWidth = 20.0f, adjustedWidth = 20.0f, visualWidth = 20.0f,
                ),
            ),
            debug = LayoutDebugInfo(),
        )
        val span = RichTextSpan(
            TextRange(0, 2), RichTextRole.Background,
            RichTextPaint(background = RichTextBackgroundPaint(horizontalPadding = 5.0f)),
        )
        val segments = result.richTextBackgroundSegments(
            result.positionedRichTextSegments(listOf(span)),
        )
        assertEquals(1, segments.size)
        assertTrue(segments[0].right > 15.0f)
    }

    @Test
    fun richTextBackgroundUniformParagraphStyleUsesParagraphStyle() {
        testTrace.section("richTextBackgroundUniformParagraphStyleUsesParagraphStyle")
        val result = LayoutResult(
            input = LayoutInput(
                content = TiqianTextContent("AB"),
                textStyle = TextStyle(fontSize = 12.0f),
                constraints = LayoutConstraints(maxWidth = 100.0f),
            ),
            size = Size(20.0f, 20.0f),
            clusters = listOf(
                Cluster(TextRange(0, 1), "A", fontKey = "latin", advance = 10.0f),
                Cluster(TextRange(1, 2), "B", fontKey = "latin", advance = 10.0f),
            ),
            glyphRuns = emptyList(),
            lines = listOf(
                LineBox(
                    range = TextRange(0, 2), clusterRange = 0..1,
                    baseline = 15.0f, top = 0.0f, bottom = 20.0f,
                    naturalWidth = 20.0f, adjustedWidth = 20.0f, visualWidth = 20.0f,
                ),
            ),
            debug = LayoutDebugInfo(metricDecisions = emptyList()),
        )
        val paint = RichTextPaint(
            background = RichTextBackgroundPaint(
                metricPolicy = RichTextBackgroundMetricPolicy.UniformParagraphStyle,
            ),
        )
        val span = RichTextSpan(TextRange(0, 2), RichTextRole.Background, paint)
        val segments = result.richTextBackgroundSegments(
            result.positionedRichTextSegments(listOf(span)),
        )
        assertEquals(1, segments.size)
    }

    @Test
    fun markedFaceVerticalBoundsUsesFallbackWhenNoMetricMatches() {
        testTrace.section("markedFaceVerticalBoundsUsesFallbackWhenNoMetricMatches")
        val result = LayoutResult(
            input = LayoutInput(
                content = TiqianTextContent("AB"),
                textStyle = TextStyle(fontSize = 10.0f),
                constraints = LayoutConstraints(maxWidth = 100.0f),
            ),
            size = Size(20.0f, 20.0f),
            clusters = listOf(
                Cluster(TextRange(0, 2), "AB", fontKey = "latin", advance = 20.0f),
            ),
            glyphRuns = emptyList(),
            lines = listOf(
                LineBox(
                    range = TextRange(0, 2), clusterRange = 0..0,
                    baseline = 15.0f, top = 0.0f, bottom = 20.0f,
                    naturalWidth = 20.0f, adjustedWidth = 20.0f, visualWidth = 20.0f,
                ),
            ),
            debug = LayoutDebugInfo(
                metricDecisions = listOf(
                    MetricDecisionInfo(
                        range = TextRange(0, 1), sourceText = "test", role = "test",
                        fontKey = "test", rawAscent = 8.0f, rawDescent = 2.0f, rawLeading = 0.0f,
                        rawSource = "test", layoutAscent = 8.0f, layoutDescent = 2.0f,
                        baselineClass = "test", metricBox = "test", layoutSource = "test", reason = "test",
                    ),
                ),
            ),
        )
        val span = RichTextSpan(TextRange(0, 2), RichTextRole.Background)
        val segments = result.richTextBackgroundSegments(
            result.positionedRichTextSegments(listOf(span)),
        )
        assertEquals(1, segments.size)
    }

    @Test
    fun getSelectionOffsetForPositionReturnsNearestWhenBeforeFirstCluster() {
        testTrace.section("getSelectionOffsetForPositionReturnsNearestWhenBeforeFirstCluster")
        val result = sampleResult()
        val offset = result.getSelectionOffsetForPosition(3.0f, 5.0f)
        assertEquals(0, offset)
    }

    @Test
    fun getSelectionOffsetForPositionReturnsNearestWhenAfterLastCluster() {
        testTrace.section("getSelectionOffsetForPositionReturnsNearestWhenAfterLastCluster")
        val result = sampleResult()
        val offset = result.getSelectionOffsetForPosition(35.0f, 25.0f)
        assertEquals(4, offset)
    }

    @Test
    fun getSelectionOffsetForPositionReturnsStartOfLineWhenClustersEmpty() {
        testTrace.section("getSelectionOffsetForPositionReturnsStartOfLineWhenClustersEmpty")
        val result = LayoutResult(
            input = LayoutInput(
                content = TiqianTextContent(""),
                constraints = LayoutConstraints(maxWidth = 100.0f),
            ),
            size = Size(0.0f, 20.0f),
            clusters = emptyList(),
            glyphRuns = emptyList(),
            lines = listOf(
                LineBox(
                    range = TextRange(0, 0), clusterRange = IntRange(0, -1),
                    baseline = 15.0f, top = 0.0f, bottom = 20.0f,
                    naturalWidth = 0.0f, adjustedWidth = 0.0f, visualWidth = 0.0f,
                ),
            ),
        )
        assertEquals(0, result.getSelectionOffsetForPosition(5.0f, 10.0f))
    }

    @Test
    fun getSelectionWordBoundaryForEmojiZwjSequence() {
        testTrace.section("getSelectionWordBoundaryForEmojiZwjSequence")
        val result = LayoutResult(
            input = LayoutInput(
                content = TiqianTextContent("\uD83D\uDC69\u200D\uD83D\uDC69"),
                constraints = LayoutConstraints(maxWidth = 100.0f),
            ),
            size = Size(50.0f, 20.0f),
            clusters = listOf(
                Cluster(TextRange(0, 5), "\uD83D\uDC69\u200D\uD83D\uDC69", fontKey = "emoji", advance = 50.0f),
            ),
            glyphRuns = emptyList(),
            lines = listOf(
                LineBox(
                    range = TextRange(0, 5), clusterRange = 0..0,
                    baseline = 15.0f, top = 0.0f, bottom = 20.0f,
                    naturalWidth = 50.0f, adjustedWidth = 50.0f, visualWidth = 50.0f,
                ),
            ),
        )
        val boundary = result.getSelectionWordBoundary(5)
        assertEquals(TextRange(0, 5), boundary)
    }

    @Test
    fun getSelectionWordBoundaryForPunctuationReturnsSingle() {
        testTrace.section("getSelectionWordBoundaryForPunctuationReturnsSingle")
        val result = LayoutResult(
            input = LayoutInput(
                content = TiqianTextContent("A,B"),
                constraints = LayoutConstraints(maxWidth = 100.0f),
            ),
            size = Size(30.0f, 20.0f),
            clusters = listOf(
                Cluster(TextRange(0, 1), "A", fontKey = "latin", advance = 10.0f),
                Cluster(TextRange(1, 2), ",", fontKey = "latin", advance = 10.0f),
                Cluster(TextRange(2, 3), "B", fontKey = "latin", advance = 10.0f),
            ),
            glyphRuns = emptyList(),
            lines = listOf(
                LineBox(
                    range = TextRange(0, 3), clusterRange = 0..2,
                    baseline = 15.0f, top = 0.0f, bottom = 20.0f,
                    naturalWidth = 30.0f, adjustedWidth = 30.0f, visualWidth = 30.0f,
                ),
            ),
        )
        val boundary = result.getSelectionWordBoundary(1)
        assertEquals(TextRange(1, 2), boundary)
    }

    @Test
    fun positionedClustersProducesSourceStopsForLatinRun() {
        testTrace.section("positionedClustersProducesSourceStopsForLatinRun")
        val result = LayoutResult(
            input = LayoutInput(
                content = TiqianTextContent("Hi"),
                textStyle = TextStyle(fontSize = 10.0f),
                constraints = LayoutConstraints(maxWidth = 100.0f),
            ),
            size = Size(20.0f, 20.0f),
            clusters = listOf(
                Cluster(TextRange(0, 2), "Hi", fontKey = "latin", advance = 20.0f),
            ),
            glyphRuns = listOf(
                GlyphRun(
                    range = TextRange(0, 2), fontKey = "latin",
                    glyphs = listOf(
                        Glyph(1u, TextRange(0, 2), advance = 10.0f),
                        Glyph(2u, TextRange(0, 2), advance = 10.0f),
                    ),
                    advance = 20.0f,
                ),
            ),
            lines = listOf(
                LineBox(
                    range = TextRange(0, 2), clusterRange = 0..0,
                    baseline = 15.0f, top = 0.0f, bottom = 20.0f,
                    naturalWidth = 20.0f, adjustedWidth = 20.0f, visualWidth = 20.0f,
                ),
            ),
        )
        val positioned = result.positionedClusters().single()
        assertEquals(3, positioned.sourceStops?.size ?: 0)
        assertTrue(positioned.sourceStops != null)
    }

    @Test
    fun offsetForXUsesSourceStopsWhenAvailable() {
        testTrace.section("offsetForXUsesSourceStopsWhenAvailable")
        val result = LayoutResult(
            input = LayoutInput(
                content = TiqianTextContent("Hi"),
                textStyle = TextStyle(fontSize = 10.0f),
                constraints = LayoutConstraints(maxWidth = 100.0f),
            ),
            size = Size(20.0f, 20.0f),
            clusters = listOf(
                Cluster(TextRange(0, 2), "Hi", fontKey = "latin", advance = 20.0f),
            ),
            glyphRuns = listOf(
                GlyphRun(
                    range = TextRange(0, 2), fontKey = "latin",
                    glyphs = listOf(
                        Glyph(1u, TextRange(0, 2), advance = 10.0f, x = 5.0f),
                        Glyph(2u, TextRange(0, 2), advance = 10.0f, x = 15.0f),
                    ),
                    advance = 20.0f,
                ),
            ),
            lines = listOf(
                LineBox(
                    range = TextRange(0, 2), clusterRange = 0..0,
                    baseline = 15.0f, top = 0.0f, bottom = 20.0f,
                    naturalWidth = 20.0f, adjustedWidth = 20.0f, visualWidth = 20.0f,
                ),
            ),
            debug = LayoutDebugInfo(),
        )
        val positioned = result.positionedClusters().single()
        assertEquals(0, result.getOffsetForPosition(positioned.left, 10.0f))
        assertEquals(1, result.getOffsetForPosition(15.0f, 10.0f))
    }

    @Test
    fun getBoundingBoxesEmptyRangeReturnsEmptyList() {
        testTrace.section("getBoundingBoxesEmptyRangeReturnsEmptyList")
        val result = sampleResult()
        assertEquals(emptyList(), result.getBoundingBoxes(TextRange(2, 2)))
    }

    @Test
    fun getLineForOffsetReturnsNearestLine() {
        testTrace.section("getLineForOffsetReturnsNearestLine")
        val result = LayoutResult(
            input = LayoutInput(
                content = TiqianTextContent("abc"),
                constraints = LayoutConstraints(maxWidth = 100.0f),
            ),
            size = Size(30.0f, 40.0f),
            clusters = listOf(
                Cluster(TextRange(0, 1), "a", fontKey = "cjk", advance = 10.0f),
                Cluster(TextRange(1, 2), "b", fontKey = "cjk", advance = 10.0f),
                Cluster(TextRange(2, 3), "c", fontKey = "cjk", advance = 10.0f),
            ),
            glyphRuns = emptyList(),
            lines = listOf(
                LineBox(
                    range = TextRange(0, 1), clusterRange = 0..0,
                    baseline = 15.0f, top = 0.0f, bottom = 20.0f,
                    naturalWidth = 10.0f, adjustedWidth = 10.0f, visualWidth = 10.0f,
                ),
                LineBox(
                    range = TextRange(1, 2), clusterRange = 1..1,
                    baseline = 35.0f, top = 25.0f, bottom = 45.0f,
                    naturalWidth = 10.0f, adjustedWidth = 10.0f, visualWidth = 10.0f,
                ),
            ),
        )
        assertEquals(1, result.getLineForOffset(10))
    }

    @Test
    fun getCursorRectReturnsCaretInCluster() {
        testTrace.section("getCursorRectReturnsCaretInCluster")
        val result = sampleResult()
        val rect = result.getCursorRect(2)
        assertEquals(Rect(24.0f, 0.0f, 25.0f, 20.0f), rect)
    }

    @Test
    fun getOffsetForPositionUsesMinByWhenOutsideClusters() {
        testTrace.section("getOffsetForPositionUsesMinByWhenOutsideClusters")
        val result = sampleResult()
        val offset = result.getOffsetForPosition(12.0f, 5.0f)
        assertEquals(1, offset)
    }

    @Test
    fun getSelectionWordBoundaryReturnsEmptyForEmptyText() {
        testTrace.section("getSelectionWordBoundaryReturnsEmptyForEmptyText")
        val result = LayoutResult(
            input = LayoutInput(
                content = TiqianTextContent(""),
                constraints = LayoutConstraints(maxWidth = 100.0f),
            ),
            size = Size(0.0f, 20.0f),
            clusters = emptyList(),
            glyphRuns = emptyList(),
            lines = emptyList(),
        )
        assertEquals(TextRange(0, 0), result.getSelectionWordBoundary(0))
    }

    private fun sampleResult(): LayoutResult =
        LayoutResult(
            input = LayoutInput(
                content = TiqianTextContent("甲——乙"),
                textStyle = TextStyle(fontSize = 10.0f),
                constraints = LayoutConstraints(maxWidth = 40.0f),
            ),
            size = Size(34.0f, 40.0f),
            clusters = listOf(
                Cluster(TextRange(0, 1), "甲", fontKey = "cjk", advance = 10.0f),
                Cluster(TextRange(1, 3), "——", "⸺", fontKey = "cjk", advance = 20.0f),
                Cluster(TextRange(3, 4), "乙", fontKey = "cjk", advance = 10.0f),
            ),
            glyphRuns = emptyList(),
            lines = listOf(
                LineBox(
                    range = TextRange(0, 3), clusterRange = 0..1,
                    baseline = 15.0f, top = 0.0f, bottom = 20.0f,
                    naturalWidth = 30.0f, adjustedWidth = 30.0f, visualWidth = 30.0f,
                    indent = 4.0f,
                ),
                LineBox(
                    range = TextRange(3, 4), clusterRange = 2..2,
                    baseline = 35.0f, top = 20.0f, bottom = 40.0f,
                    naturalWidth = 10.0f, adjustedWidth = 10.0f, visualWidth = 10.0f,
                ),
            ),
        )

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}