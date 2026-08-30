package org.tiqian.layout

import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.assertNull
import org.tiqian.test.trace.assertTrue
import org.tiqian.core.Cluster
import org.tiqian.core.EastAsianSpacingEdges
import org.tiqian.core.EastAsianSpacingValue
import org.tiqian.core.InlineObjectPreferredStretch
import org.tiqian.core.InlineObjectPreferredStretchKind
import org.tiqian.core.TextRange
import org.tiqian.font.FontRole
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

class JustifierJfTest {
    private val testTrace = TestTraceRecorder("JustifierJfTest")


    private val W = EastAsianSpacingValue.Wide
    private val N = EastAsianSpacingValue.Narrow
    private val O = EastAsianSpacingValue.Other

    private val em = 16.0f

    private fun c(
        text: String,
        index: Int,
        advance: Float = em,
        fontKey: String = "k",
    ): Cluster = Cluster(
        range = TextRange(index, index + text.length),
        text = text,
        fontKey = fontKey,
        advance = advance,
    )

    private fun e(
        leading: EastAsianSpacingValue = O,
        trailing: EastAsianSpacingValue = O,
        wide: Boolean = false,
    ): EastAsianSpacingEdges = EastAsianSpacingEdges(leading, trailing, wide)

    private val cjk = FontRole.CjkText
    private val latin = FontRole.LatinText

    private fun justify(
        clusters: List<Cluster>,
        roles: List<FontRole>,
        edges: List<EastAsianSpacingEdges>,
        range: IntRange,
        maxWidth: Float,
        fontSize: Float = em,
        justifier: Justifier = Justifier(),
        skip: Boolean = false,
        skipReason: String? = null,
        allowSinoWesternGapStretch: Boolean = true,
        cjkLatinSpaceBaseEm: Float = 0.25f,
        cjkLatinSpaceMaxEm: Float = 0.5f,
        noStretchBoundaryClusters: Set<Int> = emptySet(),
        noStretchBoundaryAfterClusters: Set<Int> = emptySet(),
        westernBracketCjkInterCharBoundaryAfterClusters: Set<Int> = emptySet(),
        attachedInlinePhysicalBoundaryAfterClusters: Set<Int> = emptySet(),
        attachedInlineVirtualBoundaryAfterClusters: Map<Int, Int> = emptyMap(),
        attachedInlineVirtualSinoWesternBoundaryAfterClusters: Set<Int> = emptySet(),
        uniformInlineObjectBoundaryAfterClusters: Set<Int> = emptySet(),
        preferredInlineObjectBoundaryAfterClusters: Map<Int, InlineObjectPreferredStretch> = emptyMap(),
        technicalBoundaryAfterClusters: Map<Int, ProgressiveBreakTier> = emptyMap(),
        emergencyTrackingBoundaryAfterClusters: Map<Int, String> = emptyMap(),
        preferredEmergencyTrackingBoundaryAfterClusters: Map<Int, String> = emptyMap(),
    ): JustificationPlan = justifier.justify(
        adjustedClusters = clusters,
        clusterRoles = roles,
        eastAsianSpacingEdges = edges,
        lineClusterRange = range,
        maxWidth = maxWidth,
        fontSize = fontSize,
        skip = skip,
        skipReason = skipReason,
        allowSinoWesternGapStretch = allowSinoWesternGapStretch,
        cjkLatinSpaceBaseEm = cjkLatinSpaceBaseEm,
        cjkLatinSpaceMaxEm = cjkLatinSpaceMaxEm,
        noStretchBoundaryClusters = noStretchBoundaryClusters,
        noStretchBoundaryAfterClusters = noStretchBoundaryAfterClusters,
        westernBracketCjkInterCharBoundaryAfterClusters = westernBracketCjkInterCharBoundaryAfterClusters,
        attachedInlinePhysicalBoundaryAfterClusters = attachedInlinePhysicalBoundaryAfterClusters,
        attachedInlineVirtualBoundaryAfterClusters = attachedInlineVirtualBoundaryAfterClusters,
        attachedInlineVirtualSinoWesternBoundaryAfterClusters = attachedInlineVirtualSinoWesternBoundaryAfterClusters,
        uniformInlineObjectBoundaryAfterClusters = uniformInlineObjectBoundaryAfterClusters,
        preferredInlineObjectBoundaryAfterClusters = preferredInlineObjectBoundaryAfterClusters,
        technicalBoundaryAfterClusters = technicalBoundaryAfterClusters,
        emergencyTrackingBoundaryAfterClusters = emergencyTrackingBoundaryAfterClusters,
        preferredEmergencyTrackingBoundaryAfterClusters = preferredEmergencyTrackingBoundaryAfterClusters,
    )

    @Test
    fun attachedInlineVirtualSinoWesternBoundaryOutOfBounds() {
        testTrace.section("attachedInlineVirtualSinoWesternBoundaryOutOfBounds")
        val clusters = listOf(c("中", 0), c("文", 1), c("a", 2))
        val roles = listOf(cjk, cjk, latin)
        val edges = listOf(e(W, W, wide = true), e(W, W, wide = true), e(N, N))

        // targetIndex outside range (e.g. -1 and 5)
        // targetIndex inside range but nextIndex outside range (e.g. targetIndex = 2 when range = 0..2)
        val plan = justify(
            clusters = clusters,
            roles = roles,
            edges = edges,
            range = 0..2,
            maxWidth = 60.0f,
            attachedInlineVirtualBoundaryAfterClusters = mapOf(-1 to -2, 2 to 1, 5 to 4),
            attachedInlineVirtualSinoWesternBoundaryAfterClusters = setOf(-1, 2, 5),
        )
        assertTrue(plan.allocations.isNotEmpty())
    }

    @Test
    fun preferredInlineObjectBoundaryOutOfBounds() {
        testTrace.section("preferredInlineObjectBoundaryOutOfBounds")
        val clusters = listOf(c("中", 0), c("文", 1), c("字", 2))
        val roles = listOf(cjk, cjk, cjk)
        val edges = listOf(e(W, W, wide = true), e(W, W, wide = true), e(W, W, wide = true))

        // leftIdx outside range (e.g. -1)
        // leftIdx inside range but rightIdx outside range (e.g. leftIdx = 2 when range = 0..2)
        val plan = justify(
            clusters = clusters,
            roles = roles,
            edges = edges,
            range = 0..2,
            maxWidth = 60.0f,
            preferredInlineObjectBoundaryAfterClusters = mapOf(
                -1 to InlineObjectPreferredStretch(InlineObjectPreferredStretchKind.Relation, naturalWidth = 0.0f, targetWidth = 4.0f),
                2 to InlineObjectPreferredStretch(InlineObjectPreferredStretchKind.Relation, naturalWidth = 0.0f, targetWidth = 4.0f),
                5 to InlineObjectPreferredStretch(InlineObjectPreferredStretchKind.Relation, naturalWidth = 0.0f, targetWidth = 4.0f),
            ),
        )
        // Since indices -1, 2 and 5 are out of bounds, no Relation allocation occurs, but CjkInterChar tier runs
        assertTrue(plan.allocations.none { it.kind == GlueKind.InlineObjectRelation })
        assertTrue(plan.allocations.isNotEmpty())
    }

    @Test
    fun closedSpaceGapInTypedSinoWesternAndUniformSpace() {
        testTrace.section("closedSpaceGapInTypedSinoWesternAndUniformSpace")
        val clusters = listOf(c("中", 0), c(" ", 1, advance = 4.0f), c("a", 2))
        val roles = listOf(cjk, latin, latin)
        val edges = listOf(e(W, W, wide = true), e(O, O), e(N, N))

        // spaceIdx = 1 is closed by noStretchBoundaryClusters on 2 (spaceIdx + 1)
        val plan = justify(
            clusters = clusters,
            roles = roles,
            edges = edges,
            range = 0..2,
            maxWidth = 60.0f,
            noStretchBoundaryClusters = setOf(2),
        )
        // space gap is closed, so no allocation on space
        assertTrue(plan.allocations.none { it.targetClusterIndex == 1 })
    }

    @Test
    fun closedSpaceGapInUniformSpaceWhenWordSpace() {
        testTrace.section("closedSpaceGapInUniformSpaceWhenWordSpace")
        val clusters = listOf(c("a", 0), c(" ", 1, advance = 4.0f), c("b", 2))
        val roles = listOf(latin, latin, latin)
        val edges = listOf(e(N, N), e(O, O), e(N, N))

        // spaceIdx = 1 is closed by noStretchBoundaryClusters on 0 (spaceIdx - 1)
        val plan = justify(
            clusters = clusters,
            roles = roles,
            edges = edges,
            range = 0..2,
            maxWidth = 60.0f,
            noStretchBoundaryClusters = setOf(0),
        )
        // space gap is closed
        assertTrue(plan.allocations.none { it.targetClusterIndex == 1 })
    }

    @Test
    fun virtualSinoWesternGapWhenAllowSinoWesternGapStretchIsFalse() {
        testTrace.section("virtualSinoWesternGapWhenAllowSinoWesternGapStretchIsFalse")
        val clusters = listOf(c("中", 0), c("[", 1), c("1", 2), c("]", 3), c("a", 4))
        val roles = listOf(cjk, cjk, cjk, cjk, latin)
        val edges = listOf(e(W, W, wide = true), e(O, O), e(O, O), e(O, O), e(N, N))

        val plan = justify(
            clusters = clusters,
            roles = roles,
            edges = edges,
            range = 0..4,
            maxWidth = 100.0f,
            allowSinoWesternGapStretch = false,
            attachedInlineVirtualBoundaryAfterClusters = mapOf(3 to 0),
            attachedInlineVirtualSinoWesternBoundaryAfterClusters = setOf(3),
        )
        // Since allowSinoWesternGapStretch is false and 3 is in attachedInlineVirtualSinoWesternBoundaryAfterClusters,
        // it is excluded from attachedInlineVirtualOpps in tier 3
        assertTrue(plan.allocations.none { it.targetClusterIndex == 3 })
    }

    @Test
    fun singleClusterRangeProducesNoOpportunities() {
        testTrace.section("singleClusterRangeProducesNoOpportunities")
        val clusters = listOf(c("中", 0))
        val roles = listOf(cjk)
        val edges = listOf(e(W, W, wide = true))

        val plan = justify(
            clusters = clusters,
            roles = roles,
            edges = edges,
            range = 0..0,
            maxWidth = 30.0f,
        )
        assertEquals(0, plan.allocations.size)
    }

    @Test
    fun zeroCjkLatinHeadroomProducesNoOpportunities() {
        testTrace.section("zeroCjkLatinHeadroomProducesNoOpportunities")
        val clusters = listOf(c("中", 0), c("a", 1))
        val roles = listOf(cjk, latin)
        val edges = listOf(e(W, W, wide = true), e(N, N))

        // cjkLatinSpaceMaxEm == cjkLatinSpaceBaseEm so capacity <= 0.0
        val plan = justify(
            clusters = clusters,
            roles = roles,
            edges = edges,
            range = 0..1,
            maxWidth = 40.0f,
            cjkLatinSpaceBaseEm = 0.5f,
            cjkLatinSpaceMaxEm = 0.5f,
        )
        // Still expands under CjkInterChar tier
        assertTrue(plan.allocations.all { it.kind == GlueKind.CjkInterChar })
    }

    @Test
    fun typedSpaceAndWordSpacePredicateEdgeConditions() {
        testTrace.section("typedSpaceAndWordSpacePredicateEdgeConditions")
        val clusters = listOf(
            c("", 0), // empty text
            c(" ", 1, advance = 4.0f), // space at index 1
            c(" ", 2, advance = 4.0f), // adjacent space at index 2
            c("abc", 3), // Latin word
            c("xyz", 4), // Latin word
            c("字", 5), // CJK character so hasCjkBodyText is true
        )
        val roles = listOf(latin, latin, latin, latin, latin, cjk)
        val edges = listOf(
            e(O, O),
            e(O, O),
            e(O, O),
            e(N, N),
            e(N, N),
            e(W, W, wide = true),
        )

        val plan = justify(
            clusters = clusters,
            roles = roles,
            edges = edges,
            range = 0..5,
            maxWidth = 150.0f,
        )
        assertTrue(plan.allocations.isNotEmpty())
    }

    @Test
    fun compressionWithZeroSurplusAndZeroCapacity() {
        testTrace.section("compressionWithZeroSurplusAndZeroCapacity")
        val justifier = Justifier()
        val emptyPlan = justifier.compress(0.0f, emptyList())
        assertEquals(0.0f, emptyPlan.surplusBefore)
        assertEquals(0.0f, emptyPlan.unfilledSurplus)
        assertTrue(emptyPlan.allocations.isEmpty())

        val planWithZeroCapacity = justifier.compress(
            surplus = 10.0f,
            shrinkOpportunities = listOf(
                ShrinkOpportunity(
                    clusterIndex = 0,
                    tier = 1,
                    capacity = 0.0f,
                    channel = ShrinkChannel.TrailingGlue,
                ),
            ),
        )
        assertEquals(10.0f, planWithZeroCapacity.surplusBefore)
        assertEquals(10.0f, planWithZeroCapacity.unfilledSurplus)
        assertTrue(planWithZeroCapacity.allocations.isEmpty())
    }

    @Test
    fun virtualNonSinoWesternBoundaryWhenAllowSinoWesternGapStretchIsFalse() {
        testTrace.section("virtualNonSinoWesternBoundaryWhenAllowSinoWesternGapStretchIsFalse")
        val clusters = listOf(c("中", 0), c("[", 1), c("1", 2), c("]", 3), c("文", 4))
        val roles = listOf(cjk, cjk, cjk, cjk, cjk)
        val edges = listOf(e(W, W, wide = true), e(O, O), e(O, O), e(O, O), e(W, W, wide = true))

        val plan = justify(
            clusters = clusters,
            roles = roles,
            edges = edges,
            range = 0..4,
            maxWidth = 100.0f,
            allowSinoWesternGapStretch = false,
            attachedInlineVirtualBoundaryAfterClusters = mapOf(3 to 0),
            attachedInlineVirtualSinoWesternBoundaryAfterClusters = emptySet(),
        )
        // Index 3 is in attachedInlineVirtualBoundaryAfterClusters, not in attachedInlineVirtualSinoWesternBoundaryAfterClusters,
        // and allowSinoWesternGapStretch is false -> passes L471 condition and gets an allocation under CjkInterChar
        assertTrue(plan.allocations.any { it.targetClusterIndex == 3 && it.reason == "AttachedInlineVirtualInterChar" })
    }

    @Test
    fun emptyLineClusterRangeSkipsUniformSpaceLoop() {
        testTrace.section("emptyLineClusterRangeSkipsUniformSpaceLoop")
        val clusters = listOf(c("中", 0), c("文", 1))
        val roles = listOf(cjk, cjk)
        val edges = listOf(e(W, W, wide = true), e(W, W, wide = true))

        val plan = justify(
            clusters = clusters,
            roles = roles,
            edges = edges,
            range = 1..0,
            maxWidth = 50.0f,
        )
        assertEquals(0, plan.allocations.size)
        assertEquals(50.0f, plan.unfilledDeficit)
    }

    @Test
    fun compressSubnormalUnderflowShrinkZero() {
        testTrace.section("compressSubnormalUnderflowShrinkZero")
        val justifier = Justifier()
        val plan = justifier.compress(
            surplus = 1e-300f,
            shrinkOpportunities = listOf(
                ShrinkOpportunity(
                    clusterIndex = 0,
                    tier = 1,
                    capacity = 1e300f,
                    channel = ShrinkChannel.TrailingGlue,
                ),
            ),
        )
        // factor = (1e-300 / 1e300) = 0.0 due to underflow
        // shrink = 1e300 * 0.0 = 0.0, so shrink > 0.0 is false
        assertEquals(0, plan.allocations.size)
        assertEquals(1e-300f, plan.surplusBefore)
    }

    @Test
    fun attachedInlineVirtualSinoWesternZeroHeadroomInAllocate() {
        testTrace.section("attachedInlineVirtualSinoWesternZeroHeadroomInAllocate")
        val clusters = listOf(c("中", 0), c("文", 1), c("a", 2))
        val roles = listOf(cjk, cjk, latin)
        val edges = listOf(e(W, W, wide = true), e(W, W, wide = true), e(N, N))

        // cjkLatinSpaceBaseEm == cjkLatinSpaceMaxEm -> capacity = 0.0
        // attachedInlineVirtualSinoWestern adds an opportunity with capacity = 0.0
        // totalCapacity = 0.0 <= 0.0 -> L634 returns deficit without allocation
        val plan = justify(
            clusters = clusters,
            roles = roles,
            edges = edges,
            range = 0..2,
            maxWidth = 60.0f,
            cjkLatinSpaceBaseEm = 0.5f,
            cjkLatinSpaceMaxEm = 0.5f,
            attachedInlineVirtualBoundaryAfterClusters = mapOf(1 to 0),
            attachedInlineVirtualSinoWesternBoundaryAfterClusters = setOf(1),
        )
        assertTrue(plan.allocations.none { it.kind == GlueKind.CjkLatinSpace })
        assertTrue(plan.allocations.any { it.kind == GlueKind.CjkInterChar })
    }

    @Test
    fun cjkLatinMixedZeroAndPositiveCapacityAllocation() {
        testTrace.section("cjkLatinMixedZeroAndPositiveCapacityAllocation")
        val clusters = listOf(
            c("中", 0),
            c(" ", 1, advance = 2.0f),
            c("a", 2),
            c("b", 3),
        )
        val roles = listOf(cjk, latin, latin, latin)
        val edges = listOf(
            e(W, W, wide = true),
            e(O, O),
            e(N, N),
            e(N, N),
        )

        // Case 1: totalCapacity (6.0) >= deficit (4.0) -> tests L640 (alloc > 0.0 branch and false branch for capacity 0.0)
        val planExact = justify(
            clusters = clusters,
            roles = roles,
            edges = edges,
            range = 0..3,
            maxWidth = 54.0f, // adjustedWidth = 50.0, deficit = 4.0
            cjkLatinSpaceBaseEm = 0.5f,
            cjkLatinSpaceMaxEm = 0.5f,
            attachedInlineVirtualBoundaryAfterClusters = mapOf(2 to 0),
            attachedInlineVirtualSinoWesternBoundaryAfterClusters = setOf(2),
        )
        val cjkLatinAllocs = planExact.allocations.filter { it.kind == GlueKind.CjkLatinSpace }
        assertEquals(1, cjkLatinAllocs.size)
        assertEquals(1, cjkLatinAllocs[0].targetClusterIndex)
        assertEquals(4.0f, cjkLatinAllocs[0].delta)

        // Case 2: totalCapacity (6.0) < deficit (10.0) -> tests L653 (opp.capacity > 0.0 branch and false branch for capacity 0.0)
        val planUnder = justify(
            clusters = clusters,
            roles = roles,
            edges = edges,
            range = 0..3,
            maxWidth = 60.0f, // adjustedWidth = 50.0, deficit = 10.0
            cjkLatinSpaceBaseEm = 0.5f,
            cjkLatinSpaceMaxEm = 0.5f,
            attachedInlineVirtualBoundaryAfterClusters = mapOf(2 to 0),
            attachedInlineVirtualSinoWesternBoundaryAfterClusters = setOf(2),
        )
        val cjkLatinUnderAllocs = planUnder.allocations.filter { it.kind == GlueKind.CjkLatinSpace }
        assertEquals(1, cjkLatinUnderAllocs.size)
        assertEquals(1, cjkLatinUnderAllocs[0].targetClusterIndex)
        assertEquals(6.0f, cjkLatinUnderAllocs[0].delta)
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
