package org.tiqian.layout

import org.tiqian.core.Cluster
import org.tiqian.core.EastAsianSpacingEdges
import org.tiqian.core.EastAsianSpacingValue
import org.tiqian.core.InlineObjectPreferredStretch
import org.tiqian.core.InlineObjectPreferredStretchKind
import org.tiqian.core.TextRange
import org.tiqian.font.FontRole
import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.assertFailsWith
import org.tiqian.test.trace.assertNull
import org.tiqian.test.trace.assertTrue
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

/**
 * Coverage for Justifier.kt: every stretch tier's eligibility predicate and
 * early-exit, the space-gap protection disjuncts, the attached-run virtual
 * boundary blocks, the Western-dominant gate, preferred inline-object kinds,
 * emergency tracking, and the compression distributor. All inputs are
 * hand-built at fontSize = 16 so every expected delta is exact.
 */
class JustifierCoverageTest {
    private val testTrace = TestTraceRecorder("JustifierCoverageTest")


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
    private val unknown = FontRole.Unknown

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

    private fun latinSpaceLatin(
        spaceAdvance: Float = 4.0f,
        aAdvance: Float = em,
        bAdvance: Float = em,
    ): Triple<List<Cluster>, List<FontRole>, List<EastAsianSpacingEdges>> = Triple(
        listOf(c("a", 0, aAdvance, "lat"), c(" ", 1, spaceAdvance, "lat"), c("b", 2, bAdvance, "lat")),
        listOf(latin, latin, latin),
        listOf(e(N, N), e(N, N), e(N, N)),
    )

    private fun cjkCjk(): Triple<List<Cluster>, List<FontRole>, List<EastAsianSpacingEdges>> = Triple(
        listOf(c("中", 0), c("中", 1)),
        listOf(cjk, cjk),
        listOf(e(W, W, wide = true), e(W, W, wide = true)),
    )

    private fun cjkLatin(): Triple<List<Cluster>, List<FontRole>, List<EastAsianSpacingEdges>> = Triple(
        listOf(c("中", 0), c("a", 1, em, "lat")),
        listOf(cjk, latin),
        listOf(e(W, W, wide = true), e(N, N)),
    )

    @Test
    fun misalignedRoleAndSpacingListsAreRejected() {
        testTrace.section("misalignedRoleAndSpacingListsAreRejected")
        val (clusters, roles, edges) = cjkCjk()
        assertFailsWith<IllegalArgumentException> {
            justify(clusters, roles + latin, edges, 0..1, maxWidth = 64.0f)
        }
        assertFailsWith<IllegalArgumentException> {
            justify(clusters, roles, edges + e(), 0..1, maxWidth = 64.0f)
        }
    }

    @Test
    fun skipKeepsTheDeficitAndRecordsTheReason() {
        testTrace.section("skipKeepsTheDeficitAndRecordsTheReason")
        val (clusters, roles, edges) = cjkCjk()
        val plan = justify(clusters, roles, edges, 0..1, maxWidth = 64.0f, skip = true, skipReason = "RaggedRight")
        assertEquals(32.0f, plan.deficitBefore)
        assertEquals(32.0f, plan.unfilledDeficit)
        assertTrue(plan.allocations.isEmpty())
        assertEquals("RaggedRight", plan.fallbackReason)
    }

    @Test
    fun zeroDeficitReturnsAnEmptyPlanWithoutReason() {
        testTrace.section("zeroDeficitReturnsAnEmptyPlanWithoutReason")
        val (clusters, roles, edges) = cjkCjk()
        val plan = justify(clusters, roles, edges, 0..1, maxWidth = 32.0f)
        assertEquals(0.0f, plan.deficitBefore)
        assertEquals(0.0f, plan.unfilledDeficit)
        assertTrue(plan.allocations.isEmpty())
        assertNull(plan.fallbackReason)
    }

    @Test
    fun technicalWhitespaceStretchFillsAndStopsTheTierChain() {
        testTrace.section("technicalWhitespaceStretchFillsAndStopsTheTierChain")
        // capacity 0.25em = 4 equals the deficit: tier 0 fills alone and the
        // plan returns before the word-space tier.
        val (clusters, roles, edges) = latinSpaceLatin(spaceAdvance = 2.0f)
        val plan = justify(
            clusters, roles, edges, 0..2, maxWidth = 38.0f,
            justifier = Justifier(progressiveTechnicalWhitespaceStretchMaxEm = 0.25f),
            technicalBoundaryAfterClusters = mapOf(1 to ProgressiveBreakTier.Whitespace),
        )
        val allocation = plan.allocations.single()
        assertEquals(1, allocation.targetClusterIndex)
        assertEquals(GlueKind.ProgressiveTechnical, allocation.kind)
        assertEquals("ProgressiveTechnicalWhitespaceStretch", allocation.reason)
        assertEquals(4.0f, allocation.delta)
        assertEquals(0.0f, plan.unfilledDeficit)
    }

    @Test
    fun technicalWhitespaceRequiresTheWhitespaceTierAndASourceSpace() {
        testTrace.section("technicalWhitespaceRequiresTheWhitespaceTierAndASourceSpace")
        // A non-Whitespace tier and a boundary keyed on a non-space cluster
        // both produce no tier-0 opportunity; the deficit flows on.
        val (clusters, roles, edges) = latinSpaceLatin(spaceAdvance = 4.0f)
        val wrongTier = justify(
            clusters, roles, edges, 0..2, maxWidth = 40.0f,
            technicalBoundaryAfterClusters = mapOf(1 to ProgressiveBreakTier.Structural),
        )
        assertEquals(GlueKind.WordSpace, wrongTier.allocations.single().kind)

        val wrongCluster = justify(
            clusters, roles, edges, 0..2, maxWidth = 40.0f,
            technicalBoundaryAfterClusters = mapOf(0 to ProgressiveBreakTier.Whitespace),
        )
        assertEquals(GlueKind.WordSpace, wrongCluster.allocations.single().kind)
    }

    @Test
    fun zeroTechnicalStretchCapacityProducesNoOpportunity() {
        testTrace.section("zeroTechnicalStretchCapacityProducesNoOpportunity")
        val (clusters, roles, edges) = latinSpaceLatin(spaceAdvance = 4.0f)
        val plan = justify(
            clusters, roles, edges, 0..2, maxWidth = 40.0f,
            justifier = Justifier(progressiveTechnicalWhitespaceStretchMaxEm = 0.0f),
            technicalBoundaryAfterClusters = mapOf(1 to ProgressiveBreakTier.Whitespace),
        )
        assertEquals(GlueKind.WordSpace, plan.allocations.single().kind)
    }

    @Test
    fun wordSpaceStretchesWithinItsCap() {
        testTrace.section("wordSpaceStretchesWithinItsCap")
        // natural 4, cap 0.5em = 8, headroom 4; deficit 2 allocated fully.
        val (clusters, roles, edges) = latinSpaceLatin(spaceAdvance = 4.0f)
        val plan = justify(clusters, roles, edges, 0..2, maxWidth = 38.0f)
        val allocation = plan.allocations.single()
        assertEquals(GlueKind.WordSpace, allocation.kind)
        assertEquals(1, allocation.targetClusterIndex)
        assertEquals(2.0f, allocation.delta)
        assertEquals("WordSpace", allocation.reason)
    }

    @Test
    fun wordSpaceAtTheCapOrCollapsedIsSkipped() {
        testTrace.section("wordSpaceAtTheCapOrCollapsedIsSkipped")
        // advance 8 == cap: headroom 0.
        val atCap = latinSpaceLatin(spaceAdvance = 8.0f)
        val atCapPlan = justify(atCap.first, atCap.second, atCap.third, 0..2, maxWidth = 48.0f)
        assertTrue(atCapPlan.allocations.isEmpty())
        assertEquals("WesternDominantLineNaturalSpacing", atCapPlan.fallbackReason)

        // advance 0: collapsed edge spaces never revive.
        val collapsed = latinSpaceLatin(spaceAdvance = 0.0f)
        val collapsedPlan = justify(collapsed.first, collapsed.second, collapsed.third, 0..2, maxWidth = 40.0f)
        assertTrue(collapsedPlan.allocations.isEmpty())
    }

    @Test
    fun spaceGapProtectionCoversAllFourDisjuncts() {
        testTrace.section("spaceGapProtectionCoversAllFourDisjuncts")
        val base = latinSpaceLatin(spaceAdvance = 4.0f)
        // Append a W->N pair far from the protected space so tier 2 can still
        // fill the deficit once the word space itself is protected.
        val clusters = base.first + c("中", 3) + c("x", 4, em, "lat")
        val roles = base.second + cjk + latin
        val edges = base.third + e(W, W, wide = true) + e(N, N)
        val variants = listOf(
            setOf(0) to emptySet<Int>(), // spaceIdx - 1 in noStretchBoundaryAfter
            setOf(1) to emptySet<Int>(), // spaceIdx in noStretchBoundaryAfter
            emptySet<Int>() to setOf(0), // spaceIdx - 1 in noStretchBoundaryClusters
            emptySet<Int>() to setOf(2), // spaceIdx + 1 in noStretchBoundaryClusters
        )
        for ((after, clustersSet) in variants) {
            val plan = justify(
                clusters, roles, edges, 0..4, maxWidth = 72.0f,
                noStretchBoundaryAfterClusters = after,
                noStretchBoundaryClusters = clustersSet,
            )
            assertTrue(
                plan.allocations.none { it.kind == GlueKind.WordSpace },
                "expected no word-space allocation for $after/$clustersSet",
            )
            assertEquals(0.0f, plan.unfilledDeficit)
        }
    }

    @Test
    fun virtualSinoWesternGapSkipsProtectedAndTypedEdges() {
        testTrace.section("virtualSinoWesternGapSkipsProtectedAndTypedEdges")
        // Left cluster ends with a space: the virtual gap defers to the typed
        // space tier instead of double counting.
        val typedLeftClusters = listOf(c("中", 0), c(" ", 1, advance = 4.0f), c("a", 2, em, "lat"))
        val typedLeftRoles = listOf(cjk, latin, latin)
        val typedLeftEdges = listOf(e(W, W, wide = true), e(O, W), e(N, N))
        val typedLeft = justify(typedLeftClusters, typedLeftRoles, typedLeftEdges, 0..2, maxWidth = 40.0f)
        assertEquals(GlueKind.CjkLatinSpace, typedLeft.allocations.single().kind)
        assertEquals(1, typedLeft.allocations.single().targetClusterIndex)

        // Right cluster begins with a space: same exclusion.
        val typedRightClusters = listOf(c("中", 0), c(" a", 1, em, "lat"), c("b", 2, em, "lat"))
        val typedRightRoles = listOf(cjk, latin, latin)
        val typedRightEdges = listOf(e(W, W, wide = true), e(N, O), e(N, N))
        val typedRight = justify(typedRightClusters, typedRightRoles, typedRightEdges, 0..2, maxWidth = 52.0f)
        assertTrue(typedRight.allocations.none { it.kind == GlueKind.CjkLatinSpace })

        // A protected physical edge and a closed boundary both exclude the gap.
        val (cClusters, cRoles, cEdges) = cjkLatin()
        val physical = justify(
            cClusters, cRoles, cEdges, 0..1, maxWidth = 36.0f,
            attachedInlinePhysicalBoundaryAfterClusters = setOf(0),
        )
        assertTrue(physical.allocations.none { it.kind == GlueKind.CjkLatinSpace })
        val closed = justify(
            cClusters, cRoles, cEdges, 0..1, maxWidth = 36.0f,
            noStretchBoundaryAfterClusters = setOf(0),
        )
        assertTrue(closed.allocations.none { it.kind == GlueKind.CjkLatinSpace })
        assertTrue(closed.unfilledDeficit > 0.0f)
    }

    @Test
    fun attachedInlineVirtualAutoSpaceJoinsTierTwo() {
        testTrace.section("attachedInlineVirtualAutoSpaceJoinsTierTwo")
        val clusters = listOf(c("中", 0), c("", 1, 0.0f, "obj"), c("a", 2, em, "lat"), c("b", 3, em, "lat"))
        val roles = listOf(cjk, unknown, latin, latin)
        val edges = listOf(e(W, W, wide = true), e(), e(N, N), e(N, N))

        val happy = justify(
            clusters, roles, edges, 0..3, maxWidth = 52.0f,
            attachedInlineVirtualBoundaryAfterClusters = mapOf(2 to 0),
            attachedInlineVirtualSinoWesternBoundaryAfterClusters = setOf(2),
        )
        val allocation = happy.allocations.single()
        assertEquals(GlueKind.CjkLatinSpace, allocation.kind)
        assertEquals("AttachedInlineVirtualAutoSpace", allocation.reason)
        assertEquals(2, allocation.targetClusterIndex)
        assertEquals(4.0f, allocation.delta)

        // Missing previous index: skipped entirely.
        val noPrevious = justify(
            clusters, roles, edges, 0..3, maxWidth = 52.0f,
            attachedInlineVirtualSinoWesternBoundaryAfterClusters = setOf(2),
        )
        assertTrue(noPrevious.allocations.none { it.reason == "AttachedInlineVirtualAutoSpace" })

        // Target beyond the line range: skipped.
        val targetOutOfRange = justify(
            clusters, roles, edges, 0..2, maxWidth = 36.0f,
            attachedInlineVirtualBoundaryAfterClusters = mapOf(3 to 0),
            attachedInlineVirtualSinoWesternBoundaryAfterClusters = setOf(3),
        )
        assertTrue(targetOutOfRange.allocations.none { it.reason == "AttachedInlineVirtualAutoSpace" })

        // Target at the line end (next index out of range): skipped.
        val nextOutOfRange = justify(
            clusters, roles, edges, 0..2, maxWidth = 36.0f,
            attachedInlineVirtualBoundaryAfterClusters = mapOf(2 to 0),
            attachedInlineVirtualSinoWesternBoundaryAfterClusters = setOf(2),
        )
        assertTrue(nextOutOfRange.allocations.none { it.reason == "AttachedInlineVirtualAutoSpace" })

        // Protected previous or next cluster: skipped.
        for (protected in listOf(setOf(0), setOf(3))) {
            val plan = justify(
                clusters, roles, edges, 0..3, maxWidth = 52.0f,
                attachedInlineVirtualBoundaryAfterClusters = mapOf(2 to 0),
                attachedInlineVirtualSinoWesternBoundaryAfterClusters = setOf(2),
                noStretchBoundaryClusters = protected,
            )
            assertTrue(
                plan.allocations.none { it.reason == "AttachedInlineVirtualAutoSpace" },
                "expected skip for protected $protected",
            )
        }
    }

    @Test
    fun typedSinoWesternSpaceStretchesFromItsBase() {
        testTrace.section("typedSinoWesternSpaceStretchesFromItsBase")
        val clusters = listOf(c("中", 0), c(" ", 1, advance = 2.0f), c("b", 2, em, "lat"))
        val roles = listOf(cjk, latin, latin)
        val edges = listOf(e(W, W, wide = true), e(N, N), e(N, N))
        val plan = justify(clusters, roles, edges, 0..2, maxWidth = 38.0f)
        val allocation = plan.allocations.single()
        assertEquals(GlueKind.CjkLatinSpace, allocation.kind)
        assertEquals(1, allocation.targetClusterIndex)
        assertEquals(4.0f, allocation.delta)
        assertEquals(0.0f, plan.unfilledDeficit)

        // Width already at the cap: no typed-space opportunity, and the
        // virtual boundary is excluded because its right cluster IS the typed
        // space; the deficit falls through to tier 3 instead.
        val atCap = listOf(c("中", 0), c(" ", 1, advance = 8.0f), c("b", 2, em, "lat"))
        val atCapPlan = justify(atCap, roles, edges, 0..2, maxWidth = 44.0f)
        assertEquals(0, atCapPlan.allocations.count { it.kind == GlueKind.CjkLatinSpace })
        assertEquals(2, atCapPlan.allocations.count { it.kind == GlueKind.CjkInterChar })
        assertEquals(0.0f, atCapPlan.unfilledDeficit)

        // Collapsed width: the typed tier skips it.
        val collapsed = listOf(c("中", 0), c(" ", 1, advance = 0.0f), c("b", 2, em, "lat"))
        val collapsedPlan = justify(
            collapsed, roles, edges, 0..2, maxWidth = 36.0f,
            cjkLatinSpaceBaseEm = 0.25f, cjkLatinSpaceMaxEm = 0.25f,
        )
        assertTrue(collapsedPlan.allocations.none { it.targetClusterIndex == 1 && it.delta > 0.0f })
    }

    @Test
    fun typedSinoWesternSpaceNeedsBothEdgesToPair() {
        testTrace.section("typedSinoWesternSpaceNeedsBothEdgesToPair")
        // CJK on both sides: the space is neither a word space (previous
        // trailing is Wide) nor a W/N typed gap (next leading is Wide).
        val clusters = listOf(c("中", 0), c(" ", 1, advance = 4.0f), c("中", 2))
        val roles = listOf(cjk, latin, cjk)
        val edges = listOf(e(W, W, wide = true), e(N, N), e(W, W, wide = true))
        val plan = justify(clusters, roles, edges, 0..2, maxWidth = 40.0f)
        assertTrue(plan.allocations.none { it.kind == GlueKind.WordSpace || it.kind == GlueKind.CjkLatinSpace })
        assertEquals(0.0f, plan.unfilledDeficit)
    }

    @Test
    fun zeroCapacitySinoWesternTierDefersEverythingDownward() {
        testTrace.section("zeroCapacitySinoWesternTierDefersEverythingDownward")
        // maxEm == baseEm: virtual gaps carry zero capacity and allocate
        // nothing; tier 3 then tracks the same boundary.
        val (clusters, roles, edges) = cjkLatin()
        val plan = justify(clusters, roles, edges, 0..1, maxWidth = 36.0f, cjkLatinSpaceMaxEm = 0.25f)
        // Tier 2 carried zero capacity; tier 3 tracks the same boundary.
        assertEquals(0.0f, plan.unfilledDeficit)
        val allocation = plan.allocations.single()
        assertEquals(GlueKind.CjkInterChar, allocation.kind)
        assertEquals(4.0f, allocation.delta)
    }

    @Test
    fun mixedCapacitySinoWesternOppsSkipZeroCapacityInOverflow() {
        testTrace.section("mixedCapacitySinoWesternOppsSkipZeroCapacityInOverflow")
        // Deficit 6 > total tier-2 capacity 2 (typed 2 + virtual 0): the else
        // branch allocates the positive capacity and skips the zero one.
        val clusters = listOf(c("中", 0), c(" ", 1, advance = 2.0f), c("a", 2, em, "lat"))
        val roles = listOf(cjk, latin, latin)
        val edges = listOf(e(W, W, wide = true), e(N, N), e(N, N))
        val plan = justify(clusters, roles, edges, 0..2, maxWidth = 40.0f, cjkLatinSpaceMaxEm = 0.25f)
        val tier2 = plan.allocations.filter { it.kind == GlueKind.CjkLatinSpace }
        assertEquals(listOf(1), tier2.map { it.targetClusterIndex })
        assertEquals(2.0f, tier2.single().delta)
        assertEquals(0.0f, plan.unfilledDeficit)
    }

    @Test
    fun sinoWesternStretchDisabledSkipsTierTwoAndItsVirtualTracking() {
        testTrace.section("sinoWesternStretchDisabledSkipsTierTwoAndItsVirtualTracking")
        val (clusters, roles, edges) = cjkLatin()
        val plan = justify(clusters, roles, edges, 0..1, maxWidth = 36.0f, allowSinoWesternGapStretch = false)
        assertTrue(plan.allocations.isEmpty())
        // The virtual gap also stays out of tier 3's virtual-Sino-Western path.
        assertEquals(4.0f, plan.unfilledDeficit)
    }

    @Test
    fun preferredInlineObjectStretchRunsBySemanticKind() {
        testTrace.section("preferredInlineObjectStretchRunsBySemanticKind")
        val clusters = listOf(c("中", 0), c("", 1, 0.0f, "obj"), c("中", 2))
        val roles = listOf(cjk, unknown, cjk)
        val edges = listOf(e(W, W, wide = true), e(), e(W, W, wide = true))

        for ((kind, reason, glue) in listOf(
            Triple(InlineObjectPreferredStretchKind.PunctuationTrailing, "InlineObjectPunctuationTrailing", GlueKind.InlineObjectPunctuationTrailing),
            Triple(InlineObjectPreferredStretchKind.Relation, "InlineObjectRelation", GlueKind.InlineObjectRelation),
            Triple(InlineObjectPreferredStretchKind.BinaryOperator, "InlineObjectBinaryOperator", GlueKind.InlineObjectBinaryOperator),
        )) {
            val plan = justify(
                clusters, roles, edges, 0..2, maxWidth = 36.0f,
                preferredInlineObjectBoundaryAfterClusters = mapOf(
                    1 to InlineObjectPreferredStretch(kind, naturalWidth = 4.0f, targetWidth = 8.0f),
                ),
            )
            val allocation = plan.allocations.single()
            assertEquals(glue, allocation.kind)
            assertEquals(reason, allocation.reason)
            assertEquals(4.0f, allocation.delta)
            assertEquals(2, allocation.priority)
        }

        // Boundary at the line end has no right index: skipped.
        val atEnd = justify(
            clusters, roles, edges, 0..1, maxWidth = 20.0f,
            preferredInlineObjectBoundaryAfterClusters = mapOf(
                1 to InlineObjectPreferredStretch(
                    InlineObjectPreferredStretchKind.Relation, naturalWidth = 4.0f, targetWidth = 8.0f,
                ),
            ),
        )
        assertTrue(atEnd.allocations.isEmpty())
        assertEquals(4.0f, atEnd.unfilledDeficit)

        // Closed boundary: skipped.
        val closed = justify(
            clusters, roles, edges, 0..2, maxWidth = 36.0f,
            preferredInlineObjectBoundaryAfterClusters = mapOf(
                1 to InlineObjectPreferredStretch(
                    InlineObjectPreferredStretchKind.Relation, naturalWidth = 4.0f, targetWidth = 8.0f,
                ),
            ),
            noStretchBoundaryAfterClusters = setOf(1),
        )
        assertEquals(4.0f, closed.unfilledDeficit)
        assertTrue(closed.allocations.none { it.kind == GlueKind.InlineObjectRelation })
    }

    @Test
    fun preferredInlineObjectKindsChainUntilFilled() {
        testTrace.section("preferredInlineObjectKindsChainUntilFilled")
        val clusters = listOf(c("中", 0), c("", 1, 0.0f, "obj"), c("中", 2))
        val roles = listOf(cjk, unknown, cjk)
        val edges = listOf(e(W, W, wide = true), e(), e(W, W, wide = true))
        val plan = justify(
            clusters, roles, edges, 0..2, maxWidth = 36.0f,
            preferredInlineObjectBoundaryAfterClusters = mapOf(
                1 to InlineObjectPreferredStretch(
                    InlineObjectPreferredStretchKind.PunctuationTrailing, naturalWidth = 4.0f, targetWidth = 6.0f,
                ),
                0 to InlineObjectPreferredStretch(
                    InlineObjectPreferredStretchKind.Relation, naturalWidth = 4.0f, targetWidth = 6.0f,
                ),
            ),
        )
        // PunctuationTrailing (2) then Relation (2) fill the 4-deficit; the
        // chain returns inside the kind loop.
        assertEquals(2, plan.allocations.size)
        assertEquals(0.0f, plan.unfilledDeficit)
        assertTrue(plan.allocations.all { it.kind == GlueKind.InlineObjectPunctuationTrailing || it.kind == GlueKind.InlineObjectRelation })
    }

    @Test
    fun westernDominantLineStaysRagged() {
        testTrace.section("westernDominantLineStaysRagged")
        val (clusters, roles, edges) = latinSpaceLatin(spaceAdvance = 8.0f)
        val plan = justify(clusters, roles, edges, 0..2, maxWidth = 64.0f)
        assertEquals("WesternDominantLineNaturalSpacing", plan.fallbackReason)
        assertTrue(plan.unfilledDeficit > 0.0f)

        // A closed uniform-object boundary does not open the gate.
        val closedObject = justify(
            clusters, roles, edges, 0..2, maxWidth = 64.0f,
            uniformInlineObjectBoundaryAfterClusters = setOf(0),
            noStretchBoundaryAfterClusters = setOf(0),
        )
        assertEquals("WesternDominantLineNaturalSpacing", closedObject.fallbackReason)
    }

    @Test
    fun uniformObjectBoundaryOpensTheGateAndFills() {
        testTrace.section("uniformObjectBoundaryOpensTheGateAndFills")
        val (clusters, roles, edges) = latinSpaceLatin(spaceAdvance = 8.0f)
        val plan = justify(
            clusters, roles, edges, 0..2, maxWidth = 64.0f,
            uniformInlineObjectBoundaryAfterClusters = setOf(0),
        )
        assertNull(plan.fallbackReason)
        // The object boundary joins the final uniform pass together with the
        // line's word space; both share the deficit.
        assertTrue(plan.allocations.any { it.kind == GlueKind.InlineObjectBoundary })
        assertEquals(0.0f, plan.unfilledDeficit)
    }

    @Test
    fun emergencyTrackingFillsTheResidualForAuthorizedBoundaries() {
        testTrace.section("emergencyTrackingFillsTheResidualForAuthorizedBoundaries")
        val clusters = listOf(c("a", 0, em, "lat"), c("b", 1, em, "lat"))
        val roles = listOf(latin, latin)
        val edges = listOf(e(N, N), e(N, N))

        val plan = justify(clusters, roles, edges, 0..1, maxWidth = 36.0f, emergencyTrackingBoundaryAfterClusters = mapOf(0 to "token"))
        val allocation = plan.allocations.single()
        assertEquals(GlueKind.EmergencyGraphemeTracking, allocation.kind)
        assertEquals("EmergencyGraphemeTracking:token", allocation.reason)
        assertEquals(4.0f, allocation.delta)
        assertEquals(0.0f, plan.unfilledDeficit)

        // A preferred (terminal technical) boundary runs before the ordinary
        // one and removes the boundary from the later tier.
        val preferred = justify(
            clusters, roles, edges, 0..1, maxWidth = 36.0f,
            emergencyTrackingBoundaryAfterClusters = mapOf(0 to "token"),
            preferredEmergencyTrackingBoundaryAfterClusters = mapOf(0 to "code"),
        )
        val preferredAllocation = preferred.allocations.single()
        assertEquals("TerminalTechnicalEmergencyTracking:code", preferredAllocation.reason)
        assertEquals(GlueKind.EmergencyGraphemeTracking, preferredAllocation.kind)
    }

    @Test
    fun cjkLineWithNoOpportunitiesReportsUnfilledWithoutFallback() {
        testTrace.section("cjkLineWithNoOpportunitiesReportsUnfilledWithoutFallback")
        val clusters = listOf(c("中", 0))
        val roles = listOf(cjk)
        val edges = listOf(e(W, W, wide = true))
        val plan = justify(clusters, roles, edges, 0..0, maxWidth = 20.0f)
        assertTrue(plan.allocations.isEmpty())
        assertEquals(4.0f, plan.unfilledDeficit)
        assertNull(plan.fallbackReason)
    }

    @Test
    fun uniformTextBoundariesExcludeProtectedClasses() {
        testTrace.section("uniformTextBoundariesExcludeProtectedClasses")
        val (clusters, roles, edges) = cjkLatin()
        val plain = justify(clusters, roles, edges, 0..1, maxWidth = 36.0f, cjkLatinSpaceMaxEm = 0.25f)
        assertEquals(GlueKind.CjkInterChar, plain.allocations.single().kind)

        val bracket = justify(clusters, roles, edges, 0..1, maxWidth = 36.0f, cjkLatinSpaceMaxEm = 0.25f, westernBracketCjkInterCharBoundaryAfterClusters = setOf(0))
        assertEquals("WesternBracketCjkInterChar", bracket.allocations.single().reason)

        val physical = justify(
            clusters, roles, edges, 0..1, maxWidth = 36.0f, cjkLatinSpaceMaxEm = 0.25f,
            attachedInlinePhysicalBoundaryAfterClusters = setOf(0),
        )
        assertEquals(4.0f, physical.unfilledDeficit)

        val virtualOwned = justify(clusters, roles, edges, 0..1, maxWidth = 36.0f, cjkLatinSpaceMaxEm = 0.25f, attachedInlineVirtualBoundaryAfterClusters = mapOf(0 to -1))
        val virtualAllocation = virtualOwned.allocations.single()
        assertEquals("AttachedInlineVirtualInterChar", virtualAllocation.reason)

        val uniformObject = justify(clusters, roles, edges, 0..1, maxWidth = 36.0f, cjkLatinSpaceMaxEm = 0.25f, uniformInlineObjectBoundaryAfterClusters = setOf(0))
        assertEquals(GlueKind.InlineObjectBoundary, uniformObject.allocations.single().kind)

        // The bracket class also honours the physical/uniform-object exclusions.
        val bracketPhysical = justify(
            clusters, roles, edges, 0..1, maxWidth = 36.0f, cjkLatinSpaceMaxEm = 0.25f,
            westernBracketCjkInterCharBoundaryAfterClusters = setOf(0),
            attachedInlinePhysicalBoundaryAfterClusters = setOf(0),
        )
        assertEquals(4.0f, bracketPhysical.unfilledDeficit)
        val bracketObject = justify(
            clusters, roles, edges, 0..1, maxWidth = 36.0f, cjkLatinSpaceMaxEm = 0.25f,
            westernBracketCjkInterCharBoundaryAfterClusters = setOf(0),
            uniformInlineObjectBoundaryAfterClusters = setOf(0),
        )
        assertEquals(GlueKind.InlineObjectBoundary, bracketObject.allocations.single().kind)
    }

    @Test
    fun attachedInlineVirtualInterCharHonoursNoStretchProtection() {
        testTrace.section("attachedInlineVirtualInterCharHonoursNoStretchProtection")
        val clusters = listOf(c("a", 0, em, "lat"), c("", 1, 0.0f, "obj"), c("b", 2, em, "lat"), c("中", 3))
        val roles = listOf(latin, unknown, latin, cjk)
        val edges = listOf(e(N, N), e(), e(N, N), e(O, W, wide = true))

        val happy = justify(
            clusters, roles, edges, 0..3, maxWidth = 60.0f,
            attachedInlineVirtualBoundaryAfterClusters = mapOf(1 to 0),
        )
        assertEquals("AttachedInlineVirtualInterChar", happy.allocations.single().reason)
        assertEquals(0.0f, happy.unfilledDeficit)

        for ((noStretch, noStretchAfter) in listOf(
            setOf(0) to emptySet<Int>(), // previousIndex protected
            setOf(2) to emptySet<Int>(), // rightIdx protected
            emptySet<Int>() to setOf(0), // previousIndex trailing protected
        )) {
            val plan = justify(
                clusters, roles, edges, 0..3, maxWidth = 60.0f,
                attachedInlineVirtualBoundaryAfterClusters = mapOf(1 to 0),
                noStretchBoundaryClusters = noStretch,
                noStretchBoundaryAfterClusters = noStretchAfter,
            )
            assertTrue(
                plan.allocations.none { it.reason == "AttachedInlineVirtualInterChar" },
                "expected skip for $noStretch/$noStretchAfter",
            )
            assertTrue(plan.unfilledDeficit > 0.0f)
        }

        // An edge promoted to the uniform-object tier leaves the attached
        // virtual tier; the object boundary still fills the line.
        val promoted = justify(
            clusters, roles, edges, 0..3, maxWidth = 60.0f,
            attachedInlineVirtualBoundaryAfterClusters = mapOf(1 to 0),
            uniformInlineObjectBoundaryAfterClusters = setOf(1),
        )
        assertEquals(GlueKind.InlineObjectBoundary, promoted.allocations.single { it.targetClusterIndex == 1 }.kind)
    }

    @Test
    fun attachedInlineVirtualSinoWesternNeedsStretchEnabled() {
        testTrace.section("attachedInlineVirtualSinoWesternNeedsStretchEnabled")
        val clusters = listOf(c("a", 0, em, "lat"), c("", 1, 0.0f, "obj"), c("b", 2, em, "lat"), c("中", 3))
        val roles = listOf(latin, unknown, latin, cjk)
        val edges = listOf(e(N, N), e(), e(N, N), e(O, W, wide = true))
        val plan = justify(
            clusters, roles, edges, 0..3, maxWidth = 60.0f,
            attachedInlineVirtualBoundaryAfterClusters = mapOf(1 to 0),
            attachedInlineVirtualSinoWesternBoundaryAfterClusters = setOf(1),
            allowSinoWesternGapStretch = false,
        )
        assertTrue(plan.allocations.none { it.reason == "AttachedInlineVirtualInterChar" })
        assertTrue(plan.unfilledDeficit > 0.0f)
    }

    @Test
    fun emptyClusterRangeDefersEveryTierLoop() {
        testTrace.section("emptyClusterRangeDefersEveryTierLoop")
        // An in-measure range can be empty when the whole line hangs; every
        // tier loop then iterates nothing and the line reports Western
        // composition with the full deficit unfilled.
        val (clusters, roles, edges) = cjkLatin()
        val plan = justify(clusters, roles, edges, 1..0, maxWidth = 16.0f)
        assertTrue(plan.allocations.isEmpty())
        assertEquals(16.0f, plan.unfilledDeficit)
        assertEquals("WesternDominantLineNaturalSpacing", plan.fallbackReason)
    }

    @Test
    fun paragraphEdgeSpaceLinesCoverTheBoundaryGuards() {
        testTrace.section("paragraphEdgeSpaceLinesCoverTheBoundaryGuards")
        // A space as the line's first cluster has no left neighbour: neither
        // word-space nor typed-gap eligibility can pair it.
        val leading = listOf(c(" ", 0, advance = 4.0f), c("中", 1), c("x", 2, em, "lat"))
        val leadingRoles = listOf(latin, cjk, latin)
        val leadingEdges = listOf(e(N, N), e(W, W, wide = true), e(N, N))
        val leadingPlan = justify(leading, leadingRoles, leadingEdges, 0..2, maxWidth = 40.0f)
        assertEquals(0, leadingPlan.allocations.count { it.kind == GlueKind.WordSpace })
        val leadingGap = leadingPlan.allocations.single { it.kind == GlueKind.CjkLatinSpace }
        assertEquals(1, leadingGap.targetClusterIndex)
        assertEquals(0.0f, leadingPlan.unfilledDeficit)

        // A space as the line's last cluster has no right neighbour.
        val trailing = listOf(c("中", 0), c("x", 1, em, "lat"), c(" ", 2, advance = 4.0f))
        val trailingPlan = justify(trailing, leadingRoles, leadingEdges, 0..2, maxWidth = 40.0f)
        assertEquals(0, trailingPlan.allocations.count { it.kind == GlueKind.WordSpace })
        val trailingGap = trailingPlan.allocations.single { it.kind == GlueKind.CjkLatinSpace }
        assertEquals(0, trailingGap.targetClusterIndex)
        assertEquals(0.0f, trailingPlan.unfilledDeficit)
    }

    @Test
    fun compressDistributesTierByTier() {
        testTrace.section("compressDistributesTierByTier")
        val justifier = Justifier()
        val tier1 = ShrinkOpportunity(0, tier = 1, capacity = 4.0f, channel = ShrinkChannel.TrailingGlue)
        val tier2 = ShrinkOpportunity(1, tier = 2, capacity = 16.0f, channel = ShrinkChannel.LeadingGlue)
        val plan = justifier.compress(surplus = 12.0f, shrinkOpportunities = listOf(tier2, tier1))
        assertEquals(0.0f, plan.unfilledSurplus)
        assertEquals(
            listOf(
                PushInAllocation(0, 4.0f, 4.0f, ShrinkChannel.TrailingGlue),
                PushInAllocation(1, 8.0f, 16.0f, ShrinkChannel.LeadingGlue),
            ),
            plan.allocations,
        )
    }

    @Test
    fun compressEarlyExitsAndFiltersDegenerateInputs() {
        testTrace.section("compressEarlyExitsAndFiltersDegenerateInputs")
        val justifier = Justifier()
        // Non-positive surplus: nothing to distribute.
        assertEquals(CompressionPlan(emptyList(), 0.0f, 0.0f), justifier.compress(0.0f, emptyList()))
        // Zero-capacity opportunities are filtered; the tier cannot help.
        val zero = ShrinkOpportunity(0, tier = 1, capacity = 0.0f, channel = ShrinkChannel.TrailingGlue)
        val unfilled = justifier.compress(8.0f, listOf(zero))
        assertTrue(unfilled.allocations.isEmpty())
        assertEquals(8.0f, unfilled.unfilledSurplus)
        // The first tier alone absorbs everything: later tiers are untouched.
        val big = ShrinkOpportunity(0, tier = 1, capacity = 16.0f, channel = ShrinkChannel.TrailingGlue)
        val other = ShrinkOpportunity(1, tier = 2, capacity = 16.0f, channel = ShrinkChannel.TrailingGlue)
        val capped = justifier.compress(8.0f, listOf(big, other))
        assertEquals(listOf(PushInAllocation(0, 8.0f, 16.0f, ShrinkChannel.TrailingGlue)), capped.allocations)
        assertEquals(0.0f, capped.unfilledSurplus)
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
