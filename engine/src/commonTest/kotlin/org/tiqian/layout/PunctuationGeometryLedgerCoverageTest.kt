package org.tiqian.layout

import org.tiqian.core.Cluster
import org.tiqian.core.InlineAttachment
import org.tiqian.core.Rect
import org.tiqian.core.TextRange
import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.assertFailsWith
import org.tiqian.test.trace.assertTrue
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

/**
 * Coverage for PunctuationGeometryLedger.kt: budget construction, the
 * consume/resolve arithmetic (tiered glue capacities, justification deltas,
 * ruby spread, raw edge trims, attached inline virtual boundaries, line-edge
 * trims) and the clusterIndexRangeFor binary search.
 *
 * At em = 16 the policy fallback gives '。' and '」' a trailing half em and
 * '「' a leading half em; those known budgets drive every expectation.
 */
class PunctuationGeometryLedgerCoverageTest {
    private val testTrace = TestTraceRecorder("PunctuationGeometryLedgerCoverageTest")


    private val em = 16.0f
    private val builder = PunctuationAtomBuilder()

    private fun cluster(text: String, startIndex: Int, advance: Float = 16.0f, fontKey: String = "cjk"): Cluster =
        Cluster(
            range = TextRange(startIndex, startIndex + text.length),
            text = text,
            displayText = text,
            fontKey = fontKey,
            advance = advance,
        )

    private fun ledgerOf(vararg texts: String): PunctuationGeometryLedger {
        val clusters = texts.mapIndexed { index, text -> cluster(text, index) }
        val atoms = clusters.flatMap { cluster ->
            cluster.punctuationAtoms(em, builder, emptyList(), builderDefaults.placement, builderDefaults.policy)
        }
        return PunctuationGeometryLedger.from(
            naturalClusters = clusters,
            punctuationAtoms = atoms,
            spacingPlan = PunctuationSpacingCompressor().compress(atoms, em),
        )
    }

    private val builderDefaults = BuilderDefaults()

    private class BuilderDefaults(
        val placement: org.tiqian.clreq.PunctuationGluePlacement = org.tiqian.clreq.PunctuationGluePlacement.MainlandSimplified,
        val policy: org.tiqian.clreq.PunctuationWidthPolicy = org.tiqian.clreq.PunctuationWidthPolicy(),
    )

    private fun lineOf(range: IntRange, start: Int, end: Int): LineCandidate =
        LineCandidate(
            clusterRange = range,
            sourceRange = TextRange(start, end),
            naturalWidth = 32.0f,
            adjustedWidth = 32.0f,
        )

    @Test
    fun budgetsResolveAdvancesThroughRemainingGlue() {
        testTrace.section("budgetsResolveAdvancesThroughRemainingGlue")
        // '。' keeps a trailing half em of glue, so its resolved advance is
        // the half-em body; '「' keeps a leading half em, so it stays 16 wide.
        val ledger = ledgerOf("。", "「", "中")
        val resolved = ledger.resolveClusters()
        assertEquals(8.0f, resolved[0].advance)
        assertEquals(16.0f, resolved[1].advance)
        assertEquals(16.0f, resolved[2].advance)
        // The plain cluster is returned as the same instance.
        assertTrue(resolved[2] === ledger.resolveClusters()[2])
    }

    @Test
    fun glueCapacitiesReportSidesAndPairing() {
        testTrace.section("glueCapacitiesReportSidesAndPairing")
        val ledger = ledgerOf("。", "「")
        val capacities = ledger.glueCapacities()
        // '。' still has its trailing 8 remaining after the compressor plan?
        // The compress plan between '。' and '「' consumes the trailing side
        // of '。' first (wider side), leaving '「' with its leading 8.
        assertEquals(setOf(1), capacities.keys)
        assertEquals(8.0f, capacities.getValue(1).leading)
        assertEquals(0.0f, capacities.getValue(1).trailing)
        assertEquals(false, capacities.getValue(1).paired)
    }

    @Test
    fun sideConsumptionIsCappedAndSkipsNonPositiveAmounts() {
        testTrace.section("sideConsumptionIsCappedAndSkipsNonPositiveAmounts")
        val ledger = ledgerOf("。", "「")
        val consumed = ledger
            .consumeLeadingByCluster(mapOf(1 to 4.0f))
            .consumeLeadingByCluster(mapOf(1 to 0.0f))
            .consumeTrailingByCluster(mapOf(1 to -1.0f))
            .consumeLeadingByCluster(mapOf(99 to 8.0f))
        val capacities = consumed.glueCapacities()
        // Zero and negative amounts change nothing; unknown clusters are ignored.
        assertEquals(4.0f, capacities.getValue(1).leading)
        // Over-consumption is capped at the natural amount.
        val capped = ledger.consumeLeadingByCluster(mapOf(1 to 100.0f))
        assertTrue(1 !in capped.glueCapacities().keys)
    }

    @Test
    fun justificationDeltasAndStructuralChannelsFeedResolvedAdvance() {
        testTrace.section("justificationDeltasAndStructuralChannelsFeedResolvedAdvance")
        val base = ledgerOf("「", "中")
        // '「' body 8 + leading glue 8 = 16.
        assertEquals(16.0f, base.resolveClusters()[0].advance)

        val justified = base.addJustificationDeltas(mapOf(0 to 1.5f))
        assertEquals(17.5f, justified.resolveClusters()[0].advance)
        // The decision info reports the delta.
        assertEquals(1.5f, justified.toDecisionInfo().first().justificationDelta)
        assertEquals(0.0f, base.toDecisionInfo().first().justificationDelta)

        val spread = base.withRubySpread(mapOf(0 to 2.0f))
        assertEquals(18.0f, spread.resolveClusters()[0].advance)
        assertEquals(2.0f, spread.toDecisionInfo().first().rubySpread)

        val trimmed = base.withRawEdgeTrims(mapOf(0 to 3.0f))
        assertEquals(13.0f, trimmed.resolveClusters()[0].advance)
        // Trims accumulate across calls and clamp the advance at zero.
        val trimmedTwice = trimmed.withRawEdgeTrims(mapOf(0 to 20.0f))
        assertEquals(0.0f, trimmedTwice.resolveClusters()[0].advance)

        // Empty maps return the same instance.
        assertTrue(base.withRubySpread(emptyMap()) === base)
        assertTrue(base.withRawEdgeTrims(emptyMap()) === base)
        assertTrue(base.withInlineBoxAdvances(emptyMap()) === base)

        // Inline box advances add to punctuation geometry clusters too.
        val boxed = base.withInlineBoxAdvances(mapOf(0 to 4.0f))
        assertEquals(20.0f, boxed.resolveClusters()[0].advance)
    }

    @Test
    fun geometryWithoutBudgetFallsBackToBodyWidth() {
        testTrace.section("geometryWithoutBudgetFallsBackToBodyWidth")
        // A geometry whose budget was never built resolves to its body width
        // plus the structural channels only.
        val base = ledgerOf("「", "中")
        val noBudgets = base.copy(budgets = emptyMap())
        assertEquals(8.0f, noBudgets.resolveClusters()[0].advance)
        val decorated = noBudgets
            .addJustificationDeltas(mapOf(0 to 1.0f))
            .withRubySpread(mapOf(0 to 2.0f))
            .withRawEdgeTrims(mapOf(0 to 1.0f))
            .copy(attachedInlineTrailingGlueByCluster = mapOf(0 to 2.0f))
        // The budget-less arm resolves body + delta + spread - trim; attached
        // glue applies only to clusters with budgets or without geometry.
        assertEquals(10.0f, decorated.resolveClusters()[0].advance)
    }

    @Test
    fun decisionInfoListsEveryGeometryWithBudgets() {
        testTrace.section("decisionInfoListsEveryGeometryWithBudgets")
        val ledger = ledgerOf("。", "中")
        val infos = ledger.toDecisionInfo()
        assertEquals(1, infos.size)
        val info = infos.first()
        assertEquals(TextRange(0, 1), info.range)
        assertEquals("。", info.sourceText)
        assertEquals(16.0f, info.baseAdvance)
        assertEquals(8.0f, info.bodyWidth)
        assertEquals(0.0f, info.leadingGlueNatural)
        assertEquals(8.0f, info.trailingGlueNatural)
        assertEquals(16.0f, info.resolvedAdvance)
        assertEquals("PunctuationGeometryLedger", info.source)
    }

    @Test
    fun spacingPlanAdjustmentsConsumeByTargetAndAnchor() {
        testTrace.section("spacingPlanAdjustmentsConsumeByTargetAndAnchor")
        // An adjustment whose target range matches no cluster is ignored.
        val strayAdjustment = PunctuationSpacingAdjustment(
            range = TextRange(90, 91),
            reductionTargetRange = TextRange(90, 91),
            leftChar = '。',
            rightChar = '「',
            naturalInnerGlue = 8.0f,
            adjustedInnerGlue = 0.0f,
            reduction = 8.0f,
            reason = "stray",
        )
        val clusters = listOf(cluster("「", 0), cluster("「", 1))
        val atoms = clusters.flatMap { it.punctuationAtoms(em, builder, emptyList(), builderDefaults.placement, builderDefaults.policy) }
        val strayLedger = PunctuationGeometryLedger.from(
            naturalClusters = clusters,
            punctuationAtoms = atoms,
            spacingPlan = PunctuationSpacingCompressionResult(listOf(strayAdjustment)),
        )
        // Both leading halves remain.
        assertEquals(setOf(0, 1), strayLedger.glueCapacities().keys)

        // A leading-heavy atom consumes an adjustment from the leading side.
        val leadingTarget = PunctuationSpacingAdjustment(
            range = TextRange(0, 1),
            reductionTargetRange = TextRange(0, 1),
            leftChar = '「',
            rightChar = '「',
            naturalInnerGlue = 8.0f,
            adjustedInnerGlue = 4.0f,
            reduction = 4.0f,
            reason = "leading-side",
        )
        val leadingLedger = PunctuationGeometryLedger.from(
            naturalClusters = clusters,
            punctuationAtoms = atoms,
            spacingPlan = PunctuationSpacingCompressionResult(listOf(leadingTarget)),
        )
        assertEquals(4.0f, leadingLedger.glueCapacities().getValue(0).leading)
        assertEquals(8.0f, leadingLedger.glueCapacities().getValue(1).leading)

        // A centred atom (halt-fitted with glue on both sides) consumes from
        // both sides equally, capped by the narrower side.
        val centredAtom = builder.build(
            char = '·',
            range = TextRange(0, 1),
            em = em,
            inkInput = PunctuationInkInput(
                advance = 16.0f,
                inkBounds = Rect(2.0f, 4.0f, 10.0f, 12.0f),
                haltAdvance = 8.0f,
                haltPlacementX = -2.0f,
            ),
        )!!
        val centredClusters = listOf(cluster("·", 0), cluster("中", 1))
        val centredTarget = PunctuationSpacingAdjustment(
            range = TextRange(0, 1),
            reductionTargetRange = TextRange(0, 1),
            leftChar = '·',
            rightChar = '中',
            naturalInnerGlue = 8.0f,
            adjustedInnerGlue = 2.0f,
            reduction = 6.0f,
            reason = "centred",
        )
        val centredLedger = PunctuationGeometryLedger.from(
            naturalClusters = centredClusters,
            punctuationAtoms = listOf(centredAtom),
            spacingPlan = PunctuationSpacingCompressionResult(listOf(centredTarget)),
        )
        val centredCapacity = centredLedger.glueCapacities().getValue(0)
        assertTrue(centredCapacity.paired)
        // perSide = min(6 / 2, leading 2, trailing 6) = 2, so both sides are
        // fully consumed.
        assertEquals(0.0f, centredCapacity.leading)
        assertEquals(4.0f, centredCapacity.trailing)
    }

    @Test
    fun attachedInlineBoundariesRequireAlignmentAndRunOnlyWithAttachments() {
        testTrace.section("attachedInlineBoundariesRequireAlignmentAndRunOnlyWithAttachments")
        val ledger = ledgerOf("。", "中")
        assertFailsWith<IllegalArgumentException> {
            ledger.resolveAttachedInlinePunctuationBoundaries(listOf(InlineAttachment.None), emptyList(), em)
        }

        // Without any Previous attachment there is nothing to resolve.
        val none = ledger.resolveAttachedInlinePunctuationBoundaries(
            List(2) { InlineAttachment.None },
            emptyList(),
            em,
        )
        assertTrue(none.decisions.isEmpty())
        assertTrue(none.trailingGlueByCluster.isEmpty())

        // A ledger with no punctuation budgets returns immediately too.
        val plain = ledgerOf("中", "中")
        val attached = plain.resolveAttachedInlinePunctuationBoundaries(
            listOf(InlineAttachment.None, InlineAttachment.Previous),
            emptyList(),
            em,
        )
        assertTrue(attached.decisions.isEmpty())
    }

    @Test
    fun attachedInlineBoundaryAtLineEndConsumesTrailingGlue() {
        testTrace.section("attachedInlineBoundaryAtLineEndConsumesTrailingGlue")
        // '」' keeps an attached run at paragraph end: the virtual boundary
        // has zero width, so the trailing glue is consumed and the decision
        // records the line-end reason with no right character.
        val clusters = listOf(cluster("」", 0), cluster("ref", 1, fontKey = "latin"))
        val atoms = clusters[0].punctuationAtoms(em, builder, emptyList(), builderDefaults.placement, builderDefaults.policy)
        val ledger = PunctuationGeometryLedger.from(clusters, atoms, PunctuationSpacingCompressionResult(emptyList()))
        val result = ledger.resolveAttachedInlinePunctuationBoundaries(
            listOf(InlineAttachment.None, InlineAttachment.Previous),
            atoms,
            em,
        )
        val decision = result.decisions.single()
        assertEquals(TextRange(0, 4), decision.range)
        assertEquals('」', decision.leftChar)
        assertEquals('\u0000', decision.rightChar)
        assertEquals("AttachedInlineVirtualPunctuationBoundary:line-end", decision.reason)
        assertEquals(8.0f, decision.reduction)
        // The consumed budget leaves the half-em body.
        assertEquals(8.0f, result.geometry.resolveClusters()[0].advance)
        assertTrue(result.trailingGlueByCluster.isEmpty())
    }

    @Test
    fun attachedInlineBoundaryAdjacentPunctuationHalvesTheVirtualGlue() {
        testTrace.section("attachedInlineBoundaryAdjacentPunctuationHalvesTheVirtualGlue")
        // '」' (trailing 8) + attached run + '「' (leading 8): the virtual
        // boundary collapses by one half em; the kept right leading shrinks
        // when the subtraction bites into it.
        val clusters = listOf(
            cluster("」", 0),
            cluster("ref", 1, fontKey = "latin"),
            cluster("「", 4),
        )
        val atoms = clusters.flatMap { it.punctuationAtoms(em, builder, emptyList(), builderDefaults.placement, builderDefaults.policy) }
        val ledger = PunctuationGeometryLedger.from(clusters, atoms, PunctuationSpacingCompressionResult(emptyList()))
        val result = ledger.resolveAttachedInlinePunctuationBoundaries(
            listOf(InlineAttachment.None, InlineAttachment.Previous, InlineAttachment.None),
            atoms,
            em,
        )
        val decision = result.decisions.single()
        assertEquals("AttachedInlineVirtualPunctuationBoundary:adjacent-punctuation", decision.reason)
        assertEquals(16.0f, decision.naturalInnerGlue)
        assertEquals(8.0f, decision.adjustedInnerGlue)
        assertEquals(8.0f, decision.reduction)
        assertEquals(TextRange(0, 5), decision.range)

        // Pre-consuming part of the left glue makes the kept right leading
        // shrink: natural 4 + 8 = 12, adjusted 4, kept min(8, 4) = 4, so the
        // right cluster's leading budget loses 4.
        val preConsumed = ledger.consumeTrailingByCluster(mapOf(0 to 4.0f))
        val bitten = preConsumed.resolveAttachedInlinePunctuationBoundaries(
            listOf(InlineAttachment.None, InlineAttachment.Previous, InlineAttachment.None),
            atoms,
            em,
        )
        val bittenDecision = bitten.decisions.single()
        assertEquals(12.0f, bittenDecision.naturalInnerGlue)
        assertEquals(4.0f, bittenDecision.adjustedInnerGlue)
        // No residual target glue remains on the attached run's edge.
        assertTrue(bitten.trailingGlueByCluster.isEmpty())
        val capacities = bitten.geometry.glueCapacities()
        // '「' keeps leading 4 of its 8 after the virtual boundary.
        assertEquals(4.0f, capacities.getValue(2).leading)
    }

    @Test
    fun attachedInlineBoundaryBeforeAsciiPointMarkCollapsesLikeAdjacent() {
        testTrace.section("attachedInlineBoundaryBeforeAsciiPointMarkCollapsesLikeAdjacent")
        val clusters = listOf(cluster("」", 0), cluster("ref", 1, fontKey = "latin"), cluster(",", 4, fontKey = "latin"))
        val atoms = clusters[0].punctuationAtoms(em, builder, emptyList(), builderDefaults.placement, builderDefaults.policy)
        val ledger = PunctuationGeometryLedger.from(clusters, atoms, PunctuationSpacingCompressionResult(emptyList()))
        val result = ledger.resolveAttachedInlinePunctuationBoundaries(
            listOf(InlineAttachment.None, InlineAttachment.Previous, InlineAttachment.None),
            atoms,
            em,
        )
        val decision = result.decisions.single()
        assertEquals("AttachedInlineVirtualPunctuationBoundary:ascii-point-mark", decision.reason)
        assertEquals(8.0f, decision.naturalInnerGlue)
        assertEquals(0.0f, decision.adjustedInnerGlue)
        assertEquals(',', decision.rightChar)
    }

    @Test
    fun attachedInlineBoundarySkipsMandatoryBreakNeighbour() {
        testTrace.section("attachedInlineBoundarySkipsMandatoryBreakNeighbour")
        // A mandatory-break cluster after the attached run is not prose: the
        // boundary resolves as a line end instead of pairing with it.
        val clusters = listOf(
            cluster("」", 0),
            cluster("ref", 1, fontKey = "latin"),
            Cluster(
                range = TextRange(3, 4),
                text = "\n",
                displayText = "",
                fontKey = "mandatory-break",
                advance = 0.0f,
            ),
        )
        val atoms = clusters[0].punctuationAtoms(em, builder, emptyList(), builderDefaults.placement, builderDefaults.policy)
        val ledger = PunctuationGeometryLedger.from(clusters, atoms, PunctuationSpacingCompressionResult(emptyList()))
        val result = ledger.resolveAttachedInlinePunctuationBoundaries(
            listOf(InlineAttachment.None, InlineAttachment.Previous, InlineAttachment.None),
            atoms,
            em,
        )
        assertEquals("AttachedInlineVirtualPunctuationBoundary:line-end", result.decisions.single().reason)
    }

    @Test
    fun attachedInlineBoundaryWithoutGlueEmitsNoDecision() {
        testTrace.section("attachedInlineBoundaryWithoutGlueEmitsNoDecision")
        // '「' has only leading glue; with a plain CJK cluster on the right
        // the virtual boundary has nothing to consume and nothing to report.
        val clusters = listOf(cluster("「", 0), cluster("ref", 1, fontKey = "latin"), cluster("中", 2))
        val atoms = clusters.flatMap { it.punctuationAtoms(em, builder, emptyList(), builderDefaults.placement, builderDefaults.policy) }
        val ledger = PunctuationGeometryLedger.from(clusters, atoms, PunctuationSpacingCompressionResult(emptyList()))
        val result = ledger.resolveAttachedInlinePunctuationBoundaries(
            listOf(InlineAttachment.None, InlineAttachment.Previous, InlineAttachment.None),
            atoms,
            em,
        )
        assertTrue(result.decisions.isEmpty())
        // The natural path (left trailing glue, plain right) reports as natural.
        val closingClusters = listOf(cluster("」", 0), cluster("ref", 1, fontKey = "latin"), cluster("中", 2))
        val closingAtoms = closingClusters.flatMap {
            it.punctuationAtoms(em, builder, emptyList(), builderDefaults.placement, builderDefaults.policy)
        }
        val closingLedger = PunctuationGeometryLedger.from(
            closingClusters,
            closingAtoms,
            PunctuationSpacingCompressionResult(emptyList()),
        )
        val natural = closingLedger.resolveAttachedInlinePunctuationBoundaries(
            listOf(InlineAttachment.None, InlineAttachment.Previous, InlineAttachment.None),
            closingAtoms,
            em,
        )
        assertEquals("AttachedInlineVirtualPunctuationBoundary:natural", natural.decisions.single().reason)
        assertEquals(8.0f, natural.decisions.single().naturalInnerGlue)
        assertEquals(8.0f, natural.decisions.single().adjustedInnerGlue)

        // A widened left trailing glue (12 instead of 8) leaves the whole
        // natural virtual glue as residual target glue; the attached run's
        // trailing edge owns it and the resolved advance of that cluster grows.
        val wideClosing = closingAtoms.map { atom ->
            if (atom.char == '」') {
                atom.copy(trailingGlue = atom.trailingGlue.copy(natural = 12.0f, max = 12.0f))
            } else {
                atom
            }
        }
        val wideLedger = PunctuationGeometryLedger.from(
            closingClusters,
            wideClosing,
            PunctuationSpacingCompressionResult(emptyList()),
        )
        val residual = wideLedger.resolveAttachedInlinePunctuationBoundaries(
            listOf(InlineAttachment.None, InlineAttachment.Previous, InlineAttachment.None),
            wideClosing,
            em,
        )
        assertEquals(mapOf(1 to 12.0f), residual.trailingGlueByCluster)
        // The ref cluster carries the virtual glue on its resolved advance.
        assertEquals(28.0f, residual.geometry.resolveClusters()[1].advance)
    }

    @Test
    fun lineEdgeTrimConsumesHalfWidthAtEdgesAndSkipsEmptyInputs() {
        testTrace.section("lineEdgeTrimConsumesHalfWidthAtEdgesAndSkipsEmptyInputs")
        // Empty lines or no budgets return the same ledger.
        val ledger = ledgerOf("」", "中")
        assertTrue(ledger.consumeLineEdgeGlue(emptyList()).decisions.isEmpty())
        val plain = ledgerOf("中", "中")
        assertTrue(plain.consumeLineEdgeGlue(listOf(lineOf(0..1, 0, 2))).decisions.isEmpty())
        // An empty cluster range line is skipped.
        val skipped = ledger.consumeLineEdgeGlue(listOf(lineOf(1..0, 0, 0)))
        assertTrue(skipped.decisions.isEmpty())

        // '」' at a line end loses its trailing half em.
        val trimmed = ledger.consumeLineEdgeGlue(listOf(lineOf(0..0, 0, 1)))
        val decision = trimmed.decisions.single()
        assertEquals("trailing", decision.side)
        assertEquals(8.0f, decision.trimAmount)
        assertEquals(8.0f, decision.naturalGlue)
        assertEquals("LineEndHalfWidthPunctuation", decision.reason)
        assertEquals(TextRange(0, 1), decision.clusterRange)
        assertEquals(8.0f, trimmed.geometry.resolveClusters()[0].advance)

        // The relaxed style keeps the full width at line end.
        val relaxed = ledger.consumeLineEdgeGlue(
            listOf(lineOf(0..0, 0, 1)),
            forceLineEndHalfWidth = false,
        )
        assertTrue(relaxed.decisions.isEmpty())
        assertEquals(16.0f, relaxed.geometry.resolveClusters()[0].advance)
    }

    @Test
    fun lineEdgeTrimConsumesCentredPunctuationOncePerLine() {
        testTrace.section("lineEdgeTrimConsumesCentredPunctuationOncePerLine")
        // A halt-fitted centred atom on a one-cluster line is consumed as a
        // pair: the end edge takes min(leading, trailing) from both sides and
        // the start edge finds nothing left.
        val centredAtom = builder.build(
            char = '·',
            range = TextRange(0, 1),
            em = em,
            inkInput = PunctuationInkInput(
                advance = 16.0f,
                inkBounds = Rect(2.0f, 4.0f, 10.0f, 12.0f),
                haltAdvance = 8.0f,
                haltPlacementX = -2.0f,
            ),
        )!!
        val clusters = listOf(cluster("·", 0))
        val ledger = PunctuationGeometryLedger.from(clusters, listOf(centredAtom), PunctuationSpacingCompressionResult(emptyList()))
        val trimmed = ledger.consumeLineEdgeGlue(listOf(lineOf(0..0, 0, 1)))
        val decision = trimmed.decisions.single()
        assertEquals("both", decision.side)
        assertEquals(4.0f, decision.trimAmount)
        assertEquals(8.0f, decision.naturalGlue)
        assertEquals("LineEndCenteredPunctuationPairedCompression", decision.reason)
        // leading 2 and trailing 2 of 2/6 are gone: 2 remains on the right.
        val capacity = trimmed.geometry.glueCapacities().getValue(0)
        assertEquals(0.0f, capacity.leading)
        assertEquals(4.0f, capacity.trailing)
    }

    @Test
    fun clusterIndexRangeFindCoveredClusters() {
        testTrace.section("clusterIndexRangeFindCoveredClusters")
        val clusters = listOf(cluster("中", 0), cluster("中", 1), cluster("中", 2))
        assertTrue(emptyList<Cluster>().clusterIndexRangeFor(TextRange(0, 3)) == null)
        assertEquals(0..2, clusters.clusterIndexRangeFor(TextRange(0, 3)))
        assertEquals(1..1, clusters.clusterIndexRangeFor(TextRange(1, 2)))
        assertEquals(null, clusters.clusterIndexRangeFor(TextRange(5, 6)))
        // A range that only partially covers a cluster excludes it.
        assertEquals(0..0, clusters.clusterIndexRangeFor(TextRange(0, 1)))
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
