package org.tiqian.layout

import org.tiqian.core.Cluster
import org.tiqian.core.LineEndReason
import org.tiqian.core.TextRange
import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.assertFailsWith
import kotlin.test.assertIs
import org.tiqian.test.trace.assertNull
import org.tiqian.test.trace.assertTrue
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTracePlatform
import org.tiqian.test.trace.TestTraceRecorder

/**
 * Coverage for LineRepair.kt: the kinsoku repair chain (PushIn / Hang /
 * CarryPrevious / LeaveRagged), the mandatory-break tail merge, the fill
 * PushIn gate cascade with progressive-tier promotion and neighbour
 * amortization, and the tiered shrink distributor including its denormal
 * underflow path. All inputs are hand-built at advance 16.
 */
class LineRepairCoverageTest {
    private val testTrace = TestTraceRecorder("LineRepairCoverageTest")


    private val em = 16.0f

    private fun c(
        text: String,
        index: Int,
        advance: Float = em,
        displayText: String = text,
    ): Cluster = Cluster(
        range = TextRange(index, index + text.length),
        text = text,
        displayText = displayText,
        fontKey = "k",
        advance = advance,
    )

    private fun clusters4(text: String, startIndex: Int): List<Cluster> =
        (0 until text.length).map { c(text[it].toString(), startIndex + it) }

    private fun line(
        range: IntRange,
        natural: List<Cluster>,
        adjusted: List<Cluster>,
        endReason: LineEndReason = LineEndReason.AutoWrap,
        repair: RepairOption? = null,
        hanging: Set<Int> = emptySet(),
    ): LineCandidate = rebuildLine(range, natural, adjusted, endReason, repair).let {
        if (hanging.isEmpty()) it else it.copy(hangingClusterIndices = hanging)
    }

    private fun repairs(
        initial: List<LineCandidate>,
        natural: List<Cluster>,
        adjusted: List<Cluster>,
        maxWidth: Float,
        shrinkOpportunities: List<ShrinkOpportunity> = emptyList(),
        unbreakableRanges: UnbreakableRanges = UnbreakableRanges.Empty,
        hangableClusters: Set<Int> = emptySet(),
        extendableHangRanges: List<IntRange> = emptyList(),
        forbiddenLineStartClusters: Set<Int>? = null,
        useDefaults: Boolean = false,
    ): LineSolution = if (useDefaults) {
        applyKinsokuRepairs(
            initial = initial,
            naturalClusters = natural,
            adjustedClusters = adjusted,
            maxWidth = maxWidth,
            kinsoku = ClreqKinsokuRule(),
            pushInPenalty = 10,
            carryPreviousPenalty = 20,
            leaveRaggedPenalty = 30,
        )
    } else {
        applyKinsokuRepairs(
            initial = initial,
            naturalClusters = natural,
            adjustedClusters = adjusted,
            maxWidth = maxWidth,
            kinsoku = ClreqKinsokuRule(),
            shrinkOpportunities = shrinkOpportunities,
            pushInPenalty = 10,
            carryPreviousPenalty = 20,
            leaveRaggedPenalty = 30,
            unbreakableRanges = unbreakableRanges,
            hangableClusters = hangableClusters,
            extendableHangRanges = extendableHangRanges,
            forbiddenLineStartClusters = forbiddenLineStartClusters,
        )
    }

    // "AAAA，BBBB": cluster 4 is a forbidden line-start comma.
    private fun commaParagraph(): Triple<List<Cluster>, List<Cluster>, List<LineCandidate>> {
        val natural = clusters4("AAAA", 0) + c("，", 4) + clusters4("BBBB", 5)
        return Triple(natural, natural, listOf(line(0..3, natural, natural), line(4..8, natural, natural)))
    }

    @Test
    fun pushInFitsWithoutShrinkWhenTheMergedLineAlreadyMatches() {
        testTrace.section("pushInFitsWithoutShrinkWhenTheMergedLineAlreadyMatches")
        val (natural, adjusted, initial) = commaParagraph()
        val solution = repairs(initial, natural, adjusted, maxWidth = 80.0f)
        assertEquals(2, solution.lines.size)
        val merged = solution.lines[0]
        val repair = assertIs<RepairOption.PushIn>(merged.repair)
        assertEquals(0..4, merged.clusterRange)
        assertEquals(0.0f, repair.totalShrink)
        assertEquals(5..8, solution.lines[1].clusterRange)
        assertTrue(repair.reason.endsWith("fits-no-shrink"))
    }

    @Test
    fun pushInPromotesTheOffendersOwnTrailingGlueToTierOne() {
        testTrace.section("pushInPromotesTheOffendersOwnTrailingGlueToTierOne")
        val (natural, adjusted, initial) = commaParagraph()
        val solution = repairs(
            initial, natural, adjusted, maxWidth = 76.0f,
            shrinkOpportunities = listOf(
                ShrinkOpportunity(4, tier = 3, capacity = 8.0f, channel = ShrinkChannel.TrailingGlue),
                ShrinkOpportunity(0, tier = 2, capacity = 8.0f, channel = ShrinkChannel.TrailingGlue),
            ),
        )
        val repair = assertIs<RepairOption.PushIn>(solution.lines.first().repair)
        assertEquals(4.0f, repair.totalShrink)
        assertEquals(listOf(PushInAllocation(4, 4.0f, 8.0f, ShrinkChannel.TrailingGlue)), repair.allocations)
        assertEquals("ForbiddenAtLineStart:，:pushed-in=4.0/16.0", repair.reason)
    }

    @Test
    fun pushInRejectsWhenCapacityIsInsufficient() {
        testTrace.section("pushInRejectsWhenCapacityIsInsufficient")
        val (natural, adjusted, initial) = commaParagraph()
        val solution = repairs(
            initial, natural, adjusted, maxWidth = 60.0f,
            shrinkOpportunities = listOf(ShrinkOpportunity(0, tier = 1, capacity = 8.0f, channel = ShrinkChannel.TrailingGlue)),
        )
        // PushIn rejected, comma not hangable, carry overflows: LeaveRagged.
        val curr = solution.lines[1]
        val rejected = curr.repairCandidates.first { it.kind == "PushIn" }
        assertEquals(false, rejected.accepted)
        assertEquals("insufficient-capacity", rejected.rejectionReason)
        assertEquals(20.0f, rejected.requiredShrink)
        assertEquals(8.0f, rejected.availableCapacity)
        assertIs<RepairOption.LeaveRagged>(curr.repair)
    }

    @Test
    fun pushInRejectsMergeThroughOutsideTheCurrentLine() {
        testTrace.section("pushInRejectsMergeThroughOutsideTheCurrentLine")
        val (natural, adjusted, initial) = commaParagraph()
        assertFailsWith<IllegalArgumentException> {
            tryPushIn(
                prev = initial[0],
                curr = initial[1],
                naturalClusters = natural,
                adjustedClusters = adjusted,
                maxWidth = 80.0f,
                shrinkOpportunities = emptyList(),
                pushInPenalty = 10,
                mergeThroughClusterIndex = 9,
            )
        }
    }

    @Test
    fun pushInFiltersOutOfRangeZeroCapacityAndForeignLineEndOnlyOpportunities() {
        testTrace.section("pushInFiltersOutOfRangeZeroCapacityAndForeignLineEndOnlyOpportunities")
        val (natural, adjusted, initial) = commaParagraph()
        val result = tryPushIn(
            prev = initial[0],
            curr = initial[1],
            naturalClusters = natural,
            adjustedClusters = adjusted,
            maxWidth = 72.0f,
            shrinkOpportunities = listOf(
                ShrinkOpportunity(9, tier = 1, capacity = 8.0f, channel = ShrinkChannel.TrailingGlue), // out of range
                ShrinkOpportunity(0, tier = 1, capacity = 0.0f, channel = ShrinkChannel.TrailingGlue), // zero capacity
                ShrinkOpportunity(1, tier = 1, capacity = 8.0f, channel = ShrinkChannel.TrailingGlue, lineEndOnly = true), // not offender
                ShrinkOpportunity(4, tier = 4, capacity = 16.0f, channel = ShrinkChannel.LeadingAndTrailingGlue, lineEndOnly = true), // offender
            ),
            pushInPenalty = 10,
        )
        assertTrue(result.candidate.accepted)
        val repair = assertIs<RepairOption.PushIn>(result.previous.repair)
        // Only the offender's own lineEndOnly opportunity survives; the
        // LeadingAndTrailingGlue channel is promoted to tier 1.
        assertEquals(listOf(PushInAllocation(4, 8.0f, 16.0f, ShrinkChannel.LeadingAndTrailingGlue)), repair.allocations)
        assertEquals(16.0f, repair.totalAvailableCapacity)
    }

    @Test
    fun pushInReportsInfinityCapacityWithAPortableDebugString() {
        testTrace.section("pushInReportsInfinityCapacityWithAPortableDebugString")
        val (natural, adjusted, initial) = commaParagraph()
        val result = tryPushIn(
            prev = initial[0],
            curr = initial[1],
            naturalClusters = natural,
            adjustedClusters = adjusted,
            maxWidth = Float.NEGATIVE_INFINITY,
            shrinkOpportunities = listOf(
                ShrinkOpportunity(0, tier = 1, capacity = Float.POSITIVE_INFINITY, channel = ShrinkChannel.TrailingGlue),
            ),
            pushInPenalty = 10,
        )
        assertTrue(result.candidate.accepted)
        assertTrue(result.candidate.shrink.isInfinite())
        val repair = assertIs<RepairOption.PushIn>(result.previous.repair)
        assertEquals("ForbiddenAtLineStart:，:pushed-in=Infinity.0/Infinity.0", repair.reason)
    }

    @Test
    fun pushInUnderflowSharesSkipZeroValuedProportionalShares() {
        testTrace.section("pushInUnderflowSharesSkipZeroValuedProportionalShares")
        // Two denormal capacities. On an f32 backend the proportional
        // share of the first opportunity is tierShrink * capacity /
        // tierCapacity; the denormal product underflows to zero, the
        // zero share is skipped, and the tier's last entry absorbs
        // everything that remains. Kotlin/JS evaluates the same
        // expression in double arithmetic, where the product survives,
        // so both opportunities receive their share.
        val minDenormal = Float.fromBits(1)
        val natural = listOf(c("a", 0, minDenormal), c("b", 1, minDenormal))
        val result = tryPushIn(
            prev = line(0..0, natural, natural),
            curr = line(1..1, natural, natural),
            naturalClusters = natural,
            adjustedClusters = natural,
            maxWidth = 0.0f,
            shrinkOpportunities = listOf(
                ShrinkOpportunity(0, tier = 1, capacity = minDenormal, channel = ShrinkChannel.TrailingGlue),
                ShrinkOpportunity(1, tier = 1, capacity = minDenormal, channel = ShrinkChannel.TrailingGlue),
            ),
            pushInPenalty = 10,
        )
        assertTrue(result.candidate.accepted)
        val repair = assertIs<RepairOption.PushIn>(result.previous.repair)
        if (TestTracePlatform.doubleArithmetic) {
            assertEquals(2, repair.allocations.size)
            assertEquals(0, repair.allocations[0].clusterIndex)
            assertEquals(1, repair.allocations[1].clusterIndex)
            assertEquals(minDenormal, repair.allocations[0].shrink)
            assertEquals(minDenormal, repair.allocations[1].shrink)
        } else {
            assertEquals(1, repair.allocations.size)
            assertEquals(1, repair.allocations.single().clusterIndex)
            assertEquals(minDenormal, repair.allocations.single().shrink)
        }
    }

    @Test
    fun hangMergesTheOffenderBeyondTheMeasure() {
        testTrace.section("hangMergesTheOffenderBeyondTheMeasure")
        val (natural, adjusted, initial) = commaParagraph()
        val solution = repairs(initial, natural, adjusted, maxWidth = 64.0f, hangableClusters = setOf(4))
        val merged = solution.lines[0]
        val repair = assertIs<RepairOption.Hang>(merged.repair)
        assertEquals(0..4, merged.clusterRange)
        assertEquals(setOf(4), merged.hangingClusterIndices)
        assertEquals(4, repair.offenderClusterIndex)
        // The hung mark overflows the measure: adjustedWidth keeps prev's.
        assertEquals(64.0f, merged.adjustedWidth)
        assertEquals(80.0f, merged.naturalWidth)
        assertEquals(5..8, solution.lines[1].clusterRange)
        val hangCandidate = merged.repairCandidates.last()
        assertEquals("Hang", hangCandidate.kind)
        assertEquals(5, hangCandidate.penalty)
    }

    @Test
    fun pushInRejectsAMergeThroughClusterOutsideTheCurrentLine() {
        testTrace.section("pushInRejectsAMergeThroughClusterOutsideTheCurrentLine")
        val natural = clusters4("AAAA", 0) + clusters4("BB", 4)
        val error = assertFailsWith<IllegalArgumentException> {
            tryPushIn(
                prev = line(0..3, natural, natural),
                curr = line(4..5, natural, natural),
                naturalClusters = natural,
                adjustedClusters = natural,
                maxWidth = 96.0f,
                shrinkOpportunities = emptyList(),
                pushInPenalty = 10,
                mergeThroughClusterIndex = 0,
            )
        }
        assertTrue(
            error.message!!.contains("must belong to the current line"),
            error.message,
        )
    }

    @Test
    fun hangConsumesAZeroWidthMandatoryBreakTail() {
        testTrace.section("hangConsumesAZeroWidthMandatoryBreakTail")
        val natural = clusters4("AAAA", 0) + c("，", 4) + c("\n", 5, 0.0f, displayText = "")
        val initial = listOf(
            line(0..3, natural, natural),
            line(4..5, natural, natural, endReason = LineEndReason.MandatoryBreak),
        )
        val solution = repairs(initial, natural, natural, maxWidth = 64.0f, hangableClusters = setOf(4))
        val merged = solution.lines.single()
        assertEquals(0..5, merged.clusterRange)
        assertEquals(setOf(4, 5), merged.hangingClusterIndices)
        assertEquals(LineEndReason.MandatoryBreak, merged.endReason)
    }

    @Test
    fun hangStopsBeforeANonZeroWidthMandatoryBreakTail() {
        testTrace.section("hangStopsBeforeANonZeroWidthMandatoryBreakTail")
        val natural = clusters4("AAAA", 0) + c("，", 4) + c("\n", 5, 8.0f, displayText = "")
        val initial = listOf(
            line(0..3, natural, natural),
            line(4..5, natural, natural, endReason = LineEndReason.MandatoryBreak),
        )
        val solution = repairs(initial, natural, natural, maxWidth = 64.0f, hangableClusters = setOf(4))
        val merged = solution.lines[0]
        assertEquals(0..4, merged.clusterRange)
        assertEquals(setOf(4), merged.hangingClusterIndices)
        assertEquals(LineEndReason.AutoWrap, merged.endReason)
        assertEquals(5..5, solution.lines[1].clusterRange)
    }

    @Test
    fun contextualHangExtendsOnlyInsideItsProtectedGroup() {
        testTrace.section("contextualHangExtendsOnlyInsideItsProtectedGroup")
        val natural = clusters4("AAA，", 0) + c("，", 4) + clusters4("BBB", 5)
        val prevHanging = line(0..3, natural, natural, hanging = setOf(3))
        val initial = listOf(prevHanging, line(4..7, natural, natural))

        // Adjacent offender inside a group covering the existing hang: extends.
        val extended = repairs(
            initial, natural, natural, maxWidth = 64.0f,
            hangableClusters = setOf(4),
            extendableHangRanges = listOf(3..5),
        )
        assertEquals(setOf(3, 4), extended.lines[0].hangingClusterIndices)

        // Offender outside the group: no extension, no fresh hang (the
        // existing one blocks it), so the chain falls through to carry. The
        // carried tail (3..5) fits while the merged line (0..4) does not.
        val outsideNatural = clusters4("AAAA", 0) + c("，", 4) + clusters4("BB", 5)
        val outside = repairs(
            listOf(line(0..3, outsideNatural, outsideNatural, hanging = setOf(3)), line(4..5, outsideNatural, outsideNatural)),
            outsideNatural, outsideNatural, maxWidth = 64.0f,
            hangableClusters = setOf(4),
            extendableHangRanges = listOf(6..7),
        )
        assertTrue(outside.lines[0].hangingClusterIndices.isEmpty())
        val carry = assertIs<RepairOption.CarryPrevious>(outside.lines[1].repair)
        assertEquals(3..5, outside.lines[1].clusterRange)
        assertEquals(3, carry.carriedClusterIndex)

        // A group that contains the offender but not the existing hang does
        // not protect the extension either: the whole suffix must stay inside.
        val partial = repairs(
            listOf(line(0..3, outsideNatural, outsideNatural, hanging = setOf(3)), line(4..5, outsideNatural, outsideNatural)),
            outsideNatural, outsideNatural, maxWidth = 64.0f,
            hangableClusters = setOf(4),
            extendableHangRanges = listOf(4..5),
        )
        assertIs<RepairOption.CarryPrevious>(partial.lines[1].repair)

        // Non-adjacent forbidden offender: the existing hang blocks a fresh
        // hang and the carry overflows, so the line stays ragged.
        val gapNatural = clusters4("AAA，", 0) + c("中", 4) + c("，", 5) + clusters4("BB", 6)
        val gappedInitial = listOf(
            line(0..3, gapNatural, gapNatural, hanging = setOf(3)),
            line(5..7, gapNatural, gapNatural),
        )
        val gapped = repairs(gappedInitial, gapNatural, gapNatural, maxWidth = 64.0f, hangableClusters = setOf(5))
        assertTrue(gapped.lines[0].hangingClusterIndices == setOf(3))
        assertIs<RepairOption.LeaveRagged>(gapped.lines[1].repair)
    }

    @Test
    fun leaveRaggedRecordsNoRoomToCarryForASingleClusterLine() {
        testTrace.section("leaveRaggedRecordsNoRoomToCarryForASingleClusterLine")
        val natural = listOf(c("中", 0), c("，", 1), c("中", 2))
        val initial = listOf(line(0..0, natural, natural), line(1..2, natural, natural))
        val solution = repairs(initial, natural, natural, maxWidth = 16.0f)
        val curr = solution.lines[1]
        val carry = curr.repairCandidates.first { it.kind == "CarryPrevious" }
        assertEquals(false, carry.accepted)
        assertEquals("no-room-to-carry", carry.rejectionReason)
        val ragged = assertIs<RepairOption.LeaveRagged>(curr.repair)
        assertTrue(ragged.reason.contains("no-room-to-carry"))
    }

    @Test
    fun leaveRaggedRefusesCarriesThatWouldSplitAnUnbreakableSpan() {
        testTrace.section("leaveRaggedRefusesCarriesThatWouldSplitAnUnbreakableSpan")
        val (natural, adjusted, initial) = commaParagraph()
        val solution = repairs(
            initial, natural, adjusted, maxWidth = 60.0f,
            unbreakableRanges = UnbreakableRanges(listOf(2..3)),
        )
        val curr = solution.lines[1]
        val carry = curr.repairCandidates.first { it.kind == "CarryPrevious" }
        assertEquals("carry-would-split-mourning-span", carry.rejectionReason)
        assertEquals(3, carry.carriedClusterIndex)
        val ragged = assertIs<RepairOption.LeaveRagged>(curr.repair)
        assertTrue(ragged.reason.contains("carry-would-split-mourning-span"))
    }

    @Test
    fun carryPreviousMovesThePreviousTailDownWhenItFits() {
        testTrace.section("carryPreviousMovesThePreviousTailDownWhenItFits")
        val natural = clusters4("AAAA", 0) + c("，", 4) + clusters4("BB", 5)
        val initial = listOf(line(0..3, natural, natural), line(4..6, natural, natural))
        // Merged 0..4 = 80 needs 10 shrink but only 4 exists: PushIn rejected.
        // Carried 3..6 = 64 fits: CarryPrevious accepted.
        val solution = repairs(
            initial, natural, natural, maxWidth = 70.0f,
            shrinkOpportunities = listOf(ShrinkOpportunity(0, tier = 1, capacity = 4.0f, channel = ShrinkChannel.TrailingGlue)),
        )
        assertEquals(0..2, solution.lines[0].clusterRange)
        assertEquals(3..6, solution.lines[1].clusterRange)
        val repair = assertIs<RepairOption.CarryPrevious>(solution.lines[1].repair)
        assertEquals(3, repair.carriedClusterIndex)
        assertTrue(repair.reason.contains("carried=A"))
        val carry = solution.lines[1].repairCandidates.first { it.kind == "CarryPrevious" && it.accepted }
        assertEquals(3, carry.carriedClusterIndex)
    }

    @Test
    fun mandatoryBreakAndEmptyLinesSkipTheRepairLoop() {
        testTrace.section("mandatoryBreakAndEmptyLinesSkipTheRepairLoop")
        val (natural, adjusted, _) = commaParagraph()
        val mandatoryInitial = listOf(
            line(0..3, natural, adjusted, endReason = LineEndReason.MandatoryBreak),
            line(4..8, natural, adjusted),
        )
        val mandatory = repairs(mandatoryInitial, natural, adjusted, maxWidth = 16.0f)
        assertNull(mandatory.lines[1].repair)

        val emptyInitial = listOf(
            line(0..3, natural, adjusted),
            emptyLineCandidate(sourceOffset = 64),
        )
        val empty = repairs(emptyInitial, natural, adjusted, maxWidth = 16.0f)
        assertEquals(2, empty.lines.size)
        assertTrue(empty.lines[1].clusterRange.isEmptyClusterRange())
    }

    @Test
    fun forbiddenStartOverrideControlsTheKinsokuCheck() {
        testTrace.section("forbiddenStartOverrideControlsTheKinsokuCheck")
        val (natural, adjusted, initial) = commaParagraph()
        // An explicit empty set disables the rule's own forbidden check.
        val disabled = repairs(initial, natural, adjusted, maxWidth = 64.0f, forbiddenLineStartClusters = emptySet())
        assertNull(disabled.lines[1].repair)

        // An explicit set forces the repair even where the rule allows.
        val plain = clusters4("AAAAB", 0)
        val forcedInitial = listOf(line(0..3, plain, plain), line(4..4, plain, plain))
        val forced = repairs(
            forcedInitial, plain, plain, maxWidth = 20.0f,
            forbiddenLineStartClusters = setOf(4),
        )
        assertIs<RepairOption.LeaveRagged>(forced.lines[1].repair)
    }

    @Test
    fun defaultArgumentsRunTheFullRaggedChain() {
        testTrace.section("defaultArgumentsRunTheFullRaggedChain")
        val (natural, adjusted, initial) = commaParagraph()
        val solution = repairs(initial, natural, adjusted, maxWidth = 60.0f, useDefaults = true)
        val curr = solution.lines[1]
        val rejected = curr.repairCandidates.first { it.kind == "PushIn" }
        assertEquals(false, rejected.accepted)
        assertEquals(10, rejected.penalty)
        val ragged = assertIs<RepairOption.LeaveRagged>(curr.repair)
        assertEquals(30, ragged.penalty)
    }

    private fun fillLines(
        natural: List<Cluster>,
        ranges: List<IntRange>,
        endReasons: List<LineEndReason> = ranges.map { LineEndReason.AutoWrap },
        repairsOnLines: Map<Int, RepairOption?> = emptyMap(),
        hanging: Map<Int, Set<Int>> = emptyMap(),
    ): List<LineCandidate> = ranges.mapIndexed { i, range ->
        line(range, natural, natural, endReasons[i], repairsOnLines[i], hanging[i] ?: emptySet())
    }

    private fun fillPushIn(
        lines: List<LineCandidate>,
        natural: List<Cluster>,
        maxWidth: Float,
        firstLineIndent: Float = 0.0f,
        compressBias: Float = 1.0f,
        forbiddenLineStartClusters: Set<Int>? = null,
        forbiddenLineEndClusters: Set<Int> = emptySet(),
        unbreakableRanges: UnbreakableRanges = UnbreakableRanges.Empty,
        gapBoundaries: Set<Int> = emptySet(),
        progressiveBreakOpportunities: Map<Int, ProgressiveBreakOpportunity> = emptyMap(),
        shrinkOpportunities: List<ShrinkOpportunity> = emptyList(),
    ): List<LineCandidate> = applyFillPushIn(
        lines, natural, natural, maxWidth, shrinkOpportunities, firstLineIndent, compressBias,
        forbiddenLineStartClusters, forbiddenLineEndClusters, unbreakableRanges, 10,
        gapBoundaries, progressiveBreakOpportunities,
    )

    @Test
    fun fillPushInSkipsShortInputsAndZeroBias() {
        testTrace.section("fillPushInSkipsShortInputsAndZeroBias")
        val natural = clusters4("ABCDE", 0)
        val single = fillPushIn(listOf(line(0..3, natural, natural)), natural, maxWidth = 64.0f)
        assertEquals(1, single.size)
        val zeroBias = fillPushIn(
            listOf(line(0..3, natural, natural), line(4..4, natural, natural)),
            natural, maxWidth = 64.0f, compressBias = 0.0f,
        )
        assertEquals(2, zeroBias.size)
    }

    @Test
    fun fillPushInSkipsRepairedHangingAndNonAutoWrapLines() {
        testTrace.section("fillPushInSkipsRepairedHangingAndNonAutoWrapLines")
        val natural = clusters4("ABCDEFGH", 0)
        val repaired = line(
            0..3, natural, natural,
            repair = RepairOption.Hang(penalty = 5, reason = "ForbiddenAtLineStart:，:hang", offenderClusterIndex = 3),
        )
        val repairedResult = fillPushIn(listOf(repaired, line(4..7, natural, natural)), natural, maxWidth = 128.0f)
        assertEquals(0..3, repairedResult[0].clusterRange)

        val hangingPrev = line(0..3, natural, natural, hanging = setOf(3))
        val hangingResult = fillPushIn(listOf(hangingPrev, line(4..7, natural, natural)), natural, maxWidth = 128.0f)
        assertEquals(0..3, hangingResult[0].clusterRange)

        val mandatory = line(0..3, natural, natural, endReason = LineEndReason.MandatoryBreak)
        val mandatoryResult = fillPushIn(listOf(mandatory, line(4..7, natural, natural)), natural, maxWidth = 128.0f)
        assertEquals(0..3, mandatoryResult[0].clusterRange)
    }

    @Test
    fun fillPushInSkipsFullLinesAndUnpullableGroups() {
        testTrace.section("fillPushInSkipsFullLinesAndUnpullableGroups")
        val natural = clusters4("ABCDEFGH", 0)
        // deficit <= 0: the line already fills the measure.
        val full = fillPushIn(listOf(line(0..3, natural, natural), line(4..7, natural, natural)), natural, maxWidth = 64.0f)
        assertEquals(0..3, full[0].clusterRange)

        // The first cluster's unbreakable group extends past the line end.
        val spill = fillPushIn(
            listOf(line(0..3, natural, natural), line(4..7, natural, natural)),
            natural, maxWidth = 128.0f,
            unbreakableRanges = UnbreakableRanges(listOf(4..9)),
        )
        assertEquals(0..3, spill[0].clusterRange)

        // Every head is forbidden at line end: the group scan exhausts the line.
        val exhausted = fillPushIn(
            listOf(line(0..3, natural, natural), line(4..5, natural, natural)),
            natural, maxWidth = 128.0f,
            forbiddenLineEndClusters = setOf(4, 5),
            forbiddenLineStartClusters = setOf(5),
        )
        assertEquals(0..3, exhausted[0].clusterRange)
    }

    @Test
    fun mandatoryBreakTailEndReturnsTheMergeThroughAtTheLineEnd() {
        testTrace.section("mandatoryBreakTailEndReturnsTheMergeThroughAtTheLineEnd")
        val natural = clusters4("AAAA", 0) + c("，", 4) + c("\n", 5, advance = 0.0f, displayText = "")
        val result = tryPushIn(
            prev = line(0..3, natural, natural),
            curr = line(4..5, natural, natural, endReason = LineEndReason.MandatoryBreak),
            naturalClusters = natural,
            adjustedClusters = natural,
            maxWidth = 96.0f,
            shrinkOpportunities = emptyList(),
            pushInPenalty = 10,
            mergeThroughClusterIndex = 5,
        )
        assertEquals(0..5, result.previous.clusterRange)
    }

    @Test
    fun fillPushInDefaultArgumentsOmitTheOptionalBoundaries() {
        testTrace.section("fillPushInDefaultArgumentsOmitTheOptionalBoundaries")
        val natural = clusters4("AAAA", 0) + clusters4("BBBB", 4)
        val result = applyFillPushIn(
            listOf(line(0..3, natural, natural), line(4..7, natural, natural)),
            natural, natural, 80.0f, emptyList(), 0.0f, 1.0f / 3.0f,
            null, emptySet(), UnbreakableRanges.Empty, 10,
        )
        assertEquals(listOf(0..4, 5..7), result.map { it.clusterRange })
    }

    @Test
    fun fillPushInPullsTheGroupAndCascadesZeroShrinkFills() {
        testTrace.section("fillPushInPullsTheGroupAndCascadesZeroShrinkFills")
        val natural = clusters4("AAAA", 0) + clusters4("BBBB", 4)
        val lines = listOf(line(0..3, natural, natural), line(4..7, natural, natural))
        val result = fillPushIn(lines, natural, maxWidth = 80.0f)
        // Pulling B (16) exactly fills the 16-deficit with zero shrink; a
        // zero-shrink fill continues cascading, and the next line has no
        // deficit left.
        assertEquals(0..4, result[0].clusterRange)
        assertEquals(5..7, result[1].clusterRange)
        val repair = assertIs<RepairOption.PushIn>(result[0].repair)
        assertEquals(0.0f, repair.totalShrink)
        assertTrue(repair.reason.startsWith("LineAdjustmentPushIn:B:fits-no-shrink"))
    }

    @Test
    fun fillPushInExtendsPastForbiddenHeadsAndUnbreakableChains() {
        testTrace.section("fillPushInExtendsPastForbiddenHeadsAndUnbreakableChains")
        val natural = clusters4("ABCDEFGH", 0)
        // Cluster 5 is forbidden at line start: the pulled group extends to 5.
        val forbiddenStart = fillPushIn(
            listOf(line(0..3, natural, natural), line(4..7, natural, natural)),
            natural, maxWidth = 96.0f,
            forbiddenLineStartClusters = setOf(5),
        )
        assertEquals(0..5, forbiddenStart[0].clusterRange)

        // Cluster 4 is forbidden at line end: the group extends to 5 as well.
        val forbiddenEnd = fillPushIn(
            listOf(line(0..3, natural, natural), line(4..7, natural, natural)),
            natural, maxWidth = 96.0f,
            forbiddenLineEndClusters = setOf(4),
        )
        assertEquals(0..5, forbiddenEnd[0].clusterRange)

        // Contiguous unbreakable ranges are walked to the chain's end, and
        // the fill cascade keeps pulling while the measure still has room.
        val chained = fillPushIn(
            listOf(line(0..3, natural, natural), line(4..7, natural, natural)),
            natural, maxWidth = 128.0f,
            unbreakableRanges = UnbreakableRanges(listOf(4..5, 5..6)),
        )
        assertEquals(1, chained.size)
        assertEquals(0..7, chained[0].clusterRange)
    }

    @Test
    fun fillPushInRejectsOverlargePullsAndWorseCompressionDensity() {
        testTrace.section("fillPushInRejectsOverlargePullsAndWorseCompressionDensity")
        val natural = clusters4("AAAA", 0) + clusters4("BBBB", 4)
        val lines = listOf(line(0..3, natural, natural), line(4..7, natural, natural))

        // Pulling the whole unbreakable run overflows 48 >= deficit 16 * bias:
        // the bias gate rejects the pull.
        val biased = fillPushIn(
            lines, natural, maxWidth = 80.0f,
            unbreakableRanges = UnbreakableRanges(listOf(4..7)),
        )
        assertEquals(0..3, biased[0].clusterRange)
        assertEquals(4..7, biased[1].clusterRange)

        // Positive overflow with no stretch gaps to cure: the density gate
        // rejects the compression the pull would introduce.
        val dense = fillPushIn(lines, natural, maxWidth = 72.0f)
        assertEquals(0..3, dense[0].clusterRange)
        assertEquals(4..7, dense[1].clusterRange)
    }

    @Test
    fun fillPushInAcceptsCompressionDenserThanTheCuredStretch() {
        testTrace.section("fillPushInAcceptsCompressionDenserThanTheCuredStretch")
        val natural = clusters4("AAAA", 0) + clusters4("BB", 4)
        // prev deficit 20 over 3 gaps = 6.67/gap; pulling both clusters
        // overflows 12 over 5 gaps = 2.4/gap: the compression is denser than
        // the cured stretch, and available shrink covers the overflow.
        val lines = listOf(line(0..3, natural, natural), line(4..5, natural, natural))
        val result = fillPushIn(
            lines, natural, maxWidth = 84.0f,
            forbiddenLineStartClusters = setOf(5),
            gapBoundaries = setOf(0, 1, 2, 3, 4),
            shrinkOpportunities = listOf(ShrinkOpportunity(0, tier = 1, capacity = 16.0f, channel = ShrinkChannel.TrailingGlue)),
        )
        assertEquals(0..5, result[0].clusterRange)
        val repair = assertIs<RepairOption.PushIn>(result[0].repair)
        assertEquals(12.0f, repair.totalShrink)
    }

    @Test
    fun fillPushInHonoursProgressiveTierPromotionBoundaries() {
        testTrace.section("fillPushInHonoursProgressiveTierPromotionBoundaries")
        val natural = clusters4("AABBBB", 0)
        val span = TextRange(0, 6)
        val emergency = ProgressiveBreakOpportunity(ProgressiveBreakTier.Emergency, span)
        val whitespace = ProgressiveBreakOpportunity(ProgressiveBreakTier.Whitespace, span)

        // A cleaner boundary of the same span one cluster later: the pull is
        // promoted and the tier is renamed rather than degraded.
        val promoted = fillPushIn(
            listOf(line(0..1, natural, natural), line(2..5, natural, natural)),
            natural, maxWidth = 48.0f,
            progressiveBreakOpportunities = mapOf(2 to emergency, 3 to whitespace),
        )
        val promotedRepair = assertIs<RepairOption.PushIn>(promoted[0].repair)
        assertEquals("ProgressiveTechnicalTierPromotion", promotedRepair.reason.substringBefore(':'))

        // A dirtier resulting boundary of the same span: the pull is refused.
        val degraded = fillPushIn(
            listOf(line(0..1, natural, natural), line(2..5, natural, natural)),
            natural, maxWidth = 48.0f,
            progressiveBreakOpportunities = mapOf(2 to whitespace, 3 to emergency),
        )
        assertNull(degraded[0].repair)

        // Promotion that still leaves the line short with no later boundary of
        // the current tier: refused (full-line requirement). The boundary one
        // past the scanned range is absent, so the refill search finds nothing.
        val shortPromotion = fillPushIn(
            listOf(line(0..1, natural, natural), line(2..3, natural, natural)),
            natural, maxWidth = 64.0f,
            progressiveBreakOpportunities = mapOf(2 to emergency, 3 to whitespace),
        )
        assertNull(shortPromotion[0].repair)

        // The whole current line pulled: the refill search range is empty.
        val emptySearch = fillPushIn(
            listOf(line(0..1, natural, natural), line(2..3, natural, natural)),
            natural, maxWidth = 96.0f,
            forbiddenLineStartClusters = setOf(3),
            progressiveBreakOpportunities = mapOf(2 to emergency, 4 to whitespace),
        )
        assertEquals(0..1, emptySearch[0].clusterRange)

        // The refill search crosses mismatching boundaries (wrong span at 4,
        // absent at 5) to find the next boundary of the current tier at 6.
        val refill = fillPushIn(
            listOf(line(0..1, natural, natural), line(2..5, natural, natural)),
            natural, maxWidth = 96.0f,
            progressiveBreakOpportunities = mapOf(
                2 to emergency,
                3 to whitespace,
                4 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Whitespace, TextRange(0, 6)),
                5 to ProgressiveBreakOpportunity(ProgressiveBreakTier.Structural, TextRange(0, 3)),
                6 to emergency,
            ),
        )
        assertEquals(0..5, refill[0].clusterRange)
        val refillRepair = assertIs<RepairOption.PushIn>(refill[0].repair)
        // The refill renamed the promotion back to an ordinary fill pull.
        assertEquals("LineAdjustmentPushIn", refillRepair.reason.substringBefore(':'))
    }

    @Test
    fun withFillPushInGateAppliesOrReturnsTheSolution() {
        testTrace.section("withFillPushInGateAppliesOrReturnsTheSolution")
        val natural = clusters4("AAAA", 0) + clusters4("BBBB", 4)
        val solution = LineSolution(listOf(line(0..3, natural, natural), line(4..7, natural, natural)))
        val disabled = solution.withFillPushIn(
            enabled = false,
            naturalClusters = natural,
            adjustedClusters = natural,
            maxWidth = 80.0f,
            shrinkOpportunities = emptyList(),
            firstLineIndent = 0.0f,
            compressBias = 1.0f,
            forbiddenLineStartClusters = null,
            forbiddenLineEndClusters = emptySet(),
            unbreakableRanges = UnbreakableRanges.Empty,
            pushInPenalty = 10,
        )
        assertEquals(solution, disabled)

        val enabled = solution.withFillPushIn(
            enabled = true,
            naturalClusters = natural,
            adjustedClusters = natural,
            maxWidth = 80.0f,
            shrinkOpportunities = emptyList(),
            firstLineIndent = 0.0f,
            compressBias = 1.0f,
            forbiddenLineStartClusters = null,
            forbiddenLineEndClusters = emptySet(),
            unbreakableRanges = UnbreakableRanges.Empty,
            pushInPenalty = 10,
        )
        assertEquals(0..4, enabled.lines[0].clusterRange)
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
