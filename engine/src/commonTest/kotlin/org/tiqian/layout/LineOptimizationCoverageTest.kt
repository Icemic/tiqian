package org.tiqian.layout

import org.tiqian.core.TextRange
import org.tiqian.linebreak.BreakKind
import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.assertFailsWith
import org.tiqian.test.trace.assertNull
import org.tiqian.test.trace.assertTrue
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

/**
 * Coverage for LineOptimization.kt: the BreakCandidate default-parameter
 * constructors, the LineCandidate hanging-suffix invariants, the hanging
 * index / in-measure views, the repair data classes (CarryNext included),
 * and the strategy enum entries.
 */
class LineOptimizationCoverageTest {
    private val testTrace = TestTraceRecorder("LineOptimizationCoverageTest")


    @Test
    fun breakCandidateDefaultsAreUsable() {
        testTrace.section("breakCandidateDefaultsAreUsable")
        val candidate = BreakCandidate(
            index = 3,
            kind = BreakKind.Allowed,
            naturalWidth = 16.0f,
            compressedWidth = 14.0f,
            expandedWidth = 18.0f,
        )
        assertNull(candidate.forbiddenReason)
        assertTrue(candidate.repairOptions.isEmpty())
    }

    @Test
    fun breakCandidateCarriesExplicitForbiddenReasonAndRepairs() {
        testTrace.section("breakCandidateCarriesExplicitForbiddenReasonAndRepairs")
        val repair = RepairOption.LeaveRagged(
            penalty = 30,
            reason = "ForbiddenAtLineStart:，:leave-ragged",
            offenderClusterIndex = 3,
        )
        val candidate = BreakCandidate(
            index = 2,
            kind = BreakKind.Problematic,
            naturalWidth = 32.0f,
            compressedWidth = 28.0f,
            expandedWidth = 36.0f,
            forbiddenReason = "kinsoku",
            repairOptions = listOf(repair),
        )
        assertEquals("kinsoku", candidate.forbiddenReason)
        assertEquals(listOf<RepairOption>(repair), candidate.repairOptions)
    }

    @Test
    fun lineCandidateRejectsHangingThatIsNotATrailingSuffix() {
        testTrace.section("lineCandidateRejectsHangingThatIsNotATrailingSuffix")
        assertFailsWith<IllegalArgumentException> {
            LineCandidate(
                clusterRange = 0..4,
                sourceRange = TextRange(0, 5),
                naturalWidth = 80.0f,
                adjustedWidth = 80.0f,
                hangingClusterIndices = setOf(0, 1),
            )
        }
        // In range but not reaching the last cluster: same invariant.
        assertFailsWith<IllegalArgumentException> {
            LineCandidate(
                clusterRange = 0..4,
                sourceRange = TextRange(0, 5),
                naturalWidth = 80.0f,
                adjustedWidth = 80.0f,
                hangingClusterIndices = setOf(2, 3),
            )
        }
        // Outside the line entirely: the first conjunct rejects it.
        assertFailsWith<IllegalArgumentException> {
            LineCandidate(
                clusterRange = 0..4,
                sourceRange = TextRange(0, 5),
                naturalWidth = 80.0f,
                adjustedWidth = 80.0f,
                hangingClusterIndices = setOf(7),
            )
        }
    }

    @Test
    fun lineCandidateRejectsDiscontiguousHanging() {
        testTrace.section("lineCandidateRejectsDiscontiguousHanging")
        assertFailsWith<IllegalArgumentException> {
            LineCandidate(
                clusterRange = 0..4,
                sourceRange = TextRange(0, 5),
                naturalWidth = 80.0f,
                adjustedWidth = 80.0f,
                hangingClusterIndices = setOf(2, 4),
            )
        }
    }

    @Test
    fun lineCandidateAcceptsAContiguousTrailingHangingSuffix() {
        testTrace.section("lineCandidateAcceptsAContiguousTrailingHangingSuffix")
        val line = LineCandidate(
            clusterRange = 0..4,
            sourceRange = TextRange(0, 5),
            naturalWidth = 80.0f,
            adjustedWidth = 80.0f,
            hangingClusterIndices = setOf(3, 4),
        )
        assertEquals(setOf(3, 4), line.hangingClusterIndices)
    }

    @Test
    fun hangingClusterIndexPrefersTheHangOffenderOverTheSuffixEnd() {
        testTrace.section("hangingClusterIndexPrefersTheHangOffenderOverTheSuffixEnd")
        val withRepair = LineCandidate(
            clusterRange = 0..4,
            sourceRange = TextRange(0, 5),
            naturalWidth = 80.0f,
            adjustedWidth = 80.0f,
            repair = RepairOption.Hang(
                penalty = 5,
                reason = "ForbiddenAtLineStart:，:hang",
                offenderClusterIndex = 3,
            ),
            hangingClusterIndices = setOf(3, 4),
        )
        assertEquals(3, withRepair.hangingClusterIndex)

        val withoutRepair = LineCandidate(
            clusterRange = 0..4,
            sourceRange = TextRange(0, 5),
            naturalWidth = 80.0f,
            adjustedWidth = 80.0f,
            hangingClusterIndices = setOf(3, 4),
        )
        assertEquals(4, withoutRepair.hangingClusterIndex)
    }

    @Test
    fun inMeasureClusterRangeExcludesTheHangingSuffix() {
        testTrace.section("inMeasureClusterRangeExcludesTheHangingSuffix")
        val hanging = LineCandidate(
            clusterRange = 0..4,
            sourceRange = TextRange(0, 5),
            naturalWidth = 80.0f,
            adjustedWidth = 80.0f,
            hangingClusterIndices = setOf(3, 4),
        )
        assertEquals(0..2, hanging.inMeasureClusterRange)

        val plain = LineCandidate(
            clusterRange = 0..4,
            sourceRange = TextRange(0, 5),
            naturalWidth = 80.0f,
            adjustedWidth = 80.0f,
        )
        assertEquals(0..4, plain.inMeasureClusterRange)
    }

    @Test
    fun carryNextRecordsTheMovedMark() {
        testTrace.section("carryNextRecordsTheMovedMark")
        val carryNext = RepairOption.CarryNext(
            penalty = 15,
            reason = "ForbiddenAtLineEnd:“:carry-next",
            movedClusterIndex = 4,
        )
        assertEquals(15, carryNext.penalty)
        assertEquals(4, carryNext.movedClusterIndex)
        assertEquals("ForbiddenAtLineEnd:“:carry-next", carryNext.reason)
    }

    @Test
    fun repairCandidateDefaultsAreUsable() {
        testTrace.section("repairCandidateDefaultsAreUsable")
        val candidate = RepairCandidate(
            kind = "PushIn",
            reasonCode = "ForbiddenAtLineStart",
            offenderClusterIndex = 4,
            penalty = 10,
            accepted = true,
        )
        assertNull(candidate.rejectionReason)
        assertNull(candidate.targetClusterIndex)
        assertNull(candidate.carriedClusterIndex)
        assertEquals(0.0f, candidate.shrink)
        assertEquals(0.0f, candidate.requiredShrink)
        assertEquals(0.0f, candidate.availableCapacity)
    }

    @Test
    fun lineSolutionDefaultsToZeroBadness() {
        testTrace.section("lineSolutionDefaultsToZeroBadness")
        val solution = LineSolution(lines = emptyList())
        assertEquals(0.0f, solution.totalBadness)
    }

    @Test
    fun optimizationStrategyEnumeratesAllThreeStrategies() {
        testTrace.section("optimizationStrategyEnumeratesAllThreeStrategies")
        assertEquals(
            listOf(
                LineOptimizationStrategy.Greedy,
                LineOptimizationStrategy.Lookahead,
                LineOptimizationStrategy.ParagraphDynamicProgramming,
            ),
            LineOptimizationStrategy.entries.toList(),
        )
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
