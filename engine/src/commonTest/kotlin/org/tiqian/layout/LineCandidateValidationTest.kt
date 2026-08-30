package org.tiqian.layout

import org.tiqian.core.LineEndReason
import org.tiqian.core.TextRange
import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.assertFailsWith
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

/**
 * LineCandidate's hanging-suffix invariants and the in-measure view of the
 * line: every rejection direction of the init guards and both arms of the
 * inMeasureClusterRange elvis.
 */
class LineCandidateValidationTest {
    private val testTrace = TestTraceRecorder("LineCandidateValidationTest")


    private fun candidate(hanging: Set<Int>, range: IntRange = 0..3): LineCandidate =
        LineCandidate(
            clusterRange = range,
            sourceRange = TextRange(0, 4),
            naturalWidth = 64.0f,
            adjustedWidth = 64.0f,
            endReason = LineEndReason.AutoWrap,
            hangingClusterIndices = hanging,
        )

    @Test
    fun hangingBelowLineRangeIsRejected() {
        testTrace.section("hangingBelowLineRangeIsRejected")
        // min(-1) lies outside the line range: first conjunct false.
        val error = assertFailsWith<IllegalArgumentException> { candidate(setOf(-1, 3)) }
        assertEquals(
            "Hanging clusters must be a trailing line suffix: line=0..3 hanging=[-1, 3]",
            error.message,
        )
    }

    @Test
    fun hangingEntirelyAboveLineIsRejected() {
        testTrace.section("hangingEntirelyAboveLineIsRejected")
        // Every hanging index sits past the line's last cluster: the range
        // containment test fails on its upper comparison directly.
        assertFailsWith<IllegalArgumentException> { candidate(setOf(5, 6)) }
    }

    @Test
    fun hangingAboveLineLastIsRejected() {
        testTrace.section("hangingAboveLineLastIsRejected")
        // min(1) is inside the line but max(4) is past its last cluster:
        // second conjunct false.
        assertFailsWith<IllegalArgumentException> { candidate(setOf(1, 4)) }
    }

    @Test
    fun nonContiguousHangingIsRejected() {
        testTrace.section("nonContiguousHangingIsRejected")
        // The suffix starts at the first cluster and ends at the last, but
        // the middle cluster is missing: the contiguity require fires.
        assertFailsWith<IllegalArgumentException> { candidate(setOf(0, 2, 3)) }
    }

    @Test
    fun inMeasureRangeExcludesHangingSuffix() {
        testTrace.section("inMeasureRangeExcludesHangingSuffix")
        assertEquals(0 until 2, candidate(setOf(2, 3)).inMeasureClusterRange)
    }

    @Test
    fun inMeasureRangeIsFullLineWithoutHanging() {
        testTrace.section("inMeasureRangeIsFullLineWithoutHanging")
        assertEquals(0..3, candidate(emptySet()).inMeasureClusterRange)
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
