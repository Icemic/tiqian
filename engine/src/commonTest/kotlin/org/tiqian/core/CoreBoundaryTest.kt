package org.tiqian.core

import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.assertFailsWith
import org.tiqian.test.trace.assertTrue
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

class CoreBoundaryTest {
    private val testTrace = TestTraceRecorder("CoreBoundaryTest")


    // === coerceToInteractionBoundary biases ===

    @Test
    fun coerceToInteractionBoundaryBackwardReturnsBoundaryWhenAtEnd() {
        testTrace.section("coerceToInteractionBoundaryBackwardReturnsBoundaryWhenAtEnd")
        val text = "abc"
        assertEquals(3, text.coerceToInteractionBoundary(3, TextRange(0, 3), SourceBoundaryBias.Backward))
    }

    @Test
    fun coerceToInteractionBoundaryForwardReturnsNextBoundary() {
        testTrace.section("coerceToInteractionBoundaryForwardReturnsNextBoundary")
        val text = "abc"
        assertEquals(3, text.coerceToInteractionBoundary(3, TextRange(0, 3), SourceBoundaryBias.Forward))
    }

    @Test
    fun coerceToInteractionBoundaryNearestChoosesCloser() {
        testTrace.section("coerceToInteractionBoundaryNearestChoosesCloser")
        val text = "abcdef"
        val result = text.coerceToInteractionBoundary(3, TextRange(0, 6), SourceBoundaryBias.Nearest)
        assertEquals(3, result)
    }

    @Test
    fun coerceToInteractionBoundaryWithSurrogatePair() {
        testTrace.section("coerceToInteractionBoundaryWithSurrogatePair")
        val text = "a\uD83D\uDE00b"
        val result = text.coerceToInteractionBoundary(3, TextRange(0, text.length), SourceBoundaryBias.Nearest)
        assertEquals(3, result)
    }

    @Test
    fun coerceToInteractionBoundaryWithInvalidSurrogatePair() {
        testTrace.section("coerceToInteractionBoundaryWithInvalidSurrogatePair")
        val text = "\uD800A"
        val result = text.coerceToInteractionBoundary(1, TextRange(0, text.length), SourceBoundaryBias.Nearest)
        assertEquals(1, result)
    }

    // === sourceGraphemeBoundaries with Hangul jamo ===

    @Test
    fun sourceGraphemeBoundariesWithHangulLeadingJamo() {
        testTrace.section("sourceGraphemeBoundariesWithHangulLeadingJamo")
        val text = "\u1100\u1161\u11A8"
        val boundaries = text.sourceGraphemeBoundaries(TextRange(0, text.length))
        assertTrue(boundaries.contains(text.length))
    }

    @Test
    fun sourceGraphemeBoundariesWithHangulSyllable() {
        testTrace.section("sourceGraphemeBoundariesWithHangulSyllable")
        val text = "\uAC00"
        val boundaries = text.sourceGraphemeBoundaries(TextRange(0, text.length))
        assertEquals(2, boundaries.size)
        assertEquals(0, boundaries.first())
        assertEquals(text.length, boundaries.last())
    }

    @Test
    fun sourceGraphemeBoundariesWithRegionalIndicator() {
        testTrace.section("sourceGraphemeBoundariesWithRegionalIndicator")
        val text = "\uD83C\uDDE8\uD83C\uDDE6"
        val boundaries = text.sourceGraphemeBoundaries(TextRange(0, text.length))
        assertTrue(boundaries.contains(text.length))
    }

    // === sourceGraphemeBoundaries with emoji ZWJ ===

    @Test
    fun sourceGraphemeBoundariesWithEmojiZwjSequence() {
        testTrace.section("sourceGraphemeBoundariesWithEmojiZwjSequence")
        val text = "\uD83D\uDC69\u200D\uD83D\uDC69"
        val boundaries = text.sourceGraphemeBoundaries(TextRange(0, text.length))
        assertEquals(2, boundaries.size)
        assertEquals(0, boundaries.first())
    }

    // === sourceGraphemeBoundaries with emoji modifier ===

    @Test
    fun sourceGraphemeBoundariesWithEmojiModifier() {
        testTrace.section("sourceGraphemeBoundariesWithEmojiModifier")
        val text = "\uD83D\uDC69\u1F3FB"
        val boundaries = text.sourceGraphemeBoundaries(TextRange(0, text.length))
        assertTrue(boundaries.contains(text.length))
    }

    // === sourceGraphemeBoundaries empty text ===

    @Test
    fun sourceGraphemeBoundariesReturnsSingleBoundaryForEmptyText() {
        testTrace.section("sourceGraphemeBoundariesReturnsSingleBoundaryForEmptyText")
        val boundaries = "".sourceGraphemeBoundaries(TextRange(0, 0))
        assertEquals(1, boundaries.size)
        assertEquals(0, boundaries.first())
    }

    // === interactionBoundaries with TextRange ===

    @Test
    fun interactionBoundariesWithTextRange() {
        testTrace.section("interactionBoundariesWithTextRange")
        val text = "abc"
        val boundaries = text.interactionBoundaries(TextRange(1, 2))
        assertEquals(listOf(1, 2), boundaries)
    }

    // === getSelectionOffsetForPosition with positioned clusters ===

    @Test
    fun getSelectionOffsetForPositionReturnsStartOfFirstCluster() {
        testTrace.section("getSelectionOffsetForPositionReturnsStartOfFirstCluster")
        val result = LayoutResult(
            input = LayoutInput(
                content = TiqianTextContent("abc"),
                constraints = LayoutConstraints(maxWidth = 100.0f),
            ),
            size = Size(30.0f, 20.0f),
            clusters = listOf(
                Cluster(TextRange(0, 1), "a", fontKey = "latin", advance = 10.0f),
                Cluster(TextRange(1, 2), "b", fontKey = "latin", advance = 10.0f),
                Cluster(TextRange(2, 3), "c", fontKey = "latin", advance = 10.0f),
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
        assertEquals(0, result.getSelectionOffsetForPosition(0.0f, 10.0f))
        assertEquals(1, result.getSelectionOffsetForPosition(10.0f, 10.0f))
        assertEquals(2, result.getSelectionOffsetForPosition(20.0f, 10.0f))
    }

    @Test
    fun getSelectionOffsetForPositionReturnsStartOfLineWhenEmptyClusters() {
        testTrace.section("getSelectionOffsetForPositionReturnsStartOfLineWhenEmptyClusters")
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

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}