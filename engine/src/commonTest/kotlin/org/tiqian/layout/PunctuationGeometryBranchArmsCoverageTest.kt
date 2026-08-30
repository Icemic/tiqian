package org.tiqian.layout

import org.tiqian.clreq.AutoSpacePolicy
import org.tiqian.clreq.KinsokuLevel
import org.tiqian.clreq.PunctuationGluePlacement
import org.tiqian.clreq.PunctuationWidthPolicy
import org.tiqian.core.Cluster
import org.tiqian.core.EastAsianSpacingEdges
import org.tiqian.core.EastAsianSpacingValue
import org.tiqian.core.Glyph
import org.tiqian.core.InlineAttachment
import org.tiqian.core.InlineBoxSpan
import org.tiqian.core.Rect
import org.tiqian.core.TextRange
import org.tiqian.font.FontRole
import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.assertFalse
import org.tiqian.test.trace.assertTrue
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

/**
 * Branch-arm coverage for the punctuation geometry cluster: the arms the
 * broad behaviour tests leave cold (empty-text clusters inside kinsoku
 * walks, union ink with partial bounds, halt guards, budget/geometry arms
 * that only fire with hand-aligned inputs). All inputs are hand-built at
 * em = 16 exactly like the sibling coverage files.
 */
class PunctuationGeometryBranchArmsCoverageTest {
    private val testTrace = TestTraceRecorder("PunctuationGeometryBranchArmsCoverageTest")


    private val em = 16.0f
    private val builder = PunctuationAtomBuilder()
    private val placement = PunctuationGluePlacement.MainlandSimplified
    private val widthPolicy = PunctuationWidthPolicy()

    private fun cluster(
        text: String,
        startIndex: Int,
        advance: Float = 16.0f,
        fontKey: String = "cjk",
        displayText: String = text,
    ): Cluster = Cluster(
        range = TextRange(startIndex, startIndex + text.length),
        text = text,
        displayText = displayText,
        fontKey = fontKey,
        advance = advance,
    )

    private fun inlineObject(startIndex: Int): Cluster = Cluster(
        range = TextRange(startIndex, startIndex + 1),
        text = "x",
        displayText = "",
        fontKey = "inline-object",
        advance = 8.0f,
    )

    private fun atomsOf(vararg clusters: Cluster): List<PunctuationAtom> =
        clusters.flatMap { it.punctuationAtoms(em, builder, emptyList(), placement, widthPolicy) }

    private fun ledgerOf(
        clusters: List<Cluster>,
        atoms: List<PunctuationAtom> = atomsOf(*clusters.toTypedArray()),
        spacingPlan: PunctuationSpacingCompressionResult = PunctuationSpacingCompressor().compress(atoms, em),
    ): PunctuationGeometryLedger = PunctuationGeometryLedger.from(clusters, atoms, spacingPlan)

    // ------------------------------------------------------------------
    // PunctuationModel: halt acceptance guards.
    // ------------------------------------------------------------------

    @Test
    fun haltAdvanceIsRejectedAtZeroAndAtFullWidth() {
        testTrace.section("haltAdvanceIsRejectedAtZeroAndAtFullWidth")
        // halt = 0 fails the positive check; halt = advance fails the
        // strict sub-width check. Both fall back to the profile split.
        val zero = builder.build(
            '，', TextRange(0, 1), em,
            inkInput = PunctuationInkInput(advance = 16.0f, haltAdvance = 0.0f),
        )!!
        assertEquals(null, zero.haltAdvance)
        assertEquals("ProfileGlueFallbackWithoutFontGeometry", zero.geometrySource)

        val fullWidth = builder.build(
            '，', TextRange(0, 1), em,
            inkInput = PunctuationInkInput(advance = 16.0f, haltAdvance = 16.0f),
        )!!
        assertEquals(null, fullWidth.haltAdvance)
        assertEquals("ProfileGlueFallbackWithoutFontGeometry", fullWidth.geometrySource)
    }

    @Test
    fun nonFiniteHaltPlacementIsIgnored() {
        testTrace.section("nonFiniteHaltPlacementIsIgnored")
        // A NaN halt placement cannot place the body, so the fitted-ink or
        // profile path runs instead of the halt path.
        val withInk = builder.build(
            '·', TextRange(0, 1), em,
            inkInput = PunctuationInkInput(
                advance = 16.0f,
                inkBounds = Rect(8.0f, 4.0f, 16.0f, 12.0f),
                haltAdvance = 8.0f,
                haltPlacementX = Float.NaN,
            ),
        )!!
        assertEquals("FontHaltAdvanceWithInkBoundsFittedPlacement", withInk.geometrySource)

        val withoutInk = builder.build(
            '，', TextRange(0, 1), em,
            inkInput = PunctuationInkInput(advance = 16.0f, haltAdvance = 8.0f, haltPlacementX = Float.NaN),
        )!!
        assertEquals("FontHaltAdvanceWithProfileFallback", withoutInk.geometrySource)
        assertEquals(8.0f, withoutInk.trailingGlue.natural)
    }

    // ------------------------------------------------------------------
    // Stage: ink attribution with partial bounds.
    // ------------------------------------------------------------------

    @Test
    fun unionIgnoresGlyphsWithoutBounds() {
        testTrace.section("unionIgnoresGlyphsWithoutBounds")
        val mark = cluster("，", 0)
        val glyphs = listOf(
            Glyph(1u, TextRange(0, 1), 8.0f, x = 0.0f, bounds = Rect(0.0f, 0.0f, 8.0f, 16.0f)),
            Glyph(2u, TextRange(0, 1), 6.0f, x = 8.0f, bounds = null),
        )
        val atom = mark.punctuationAtoms(em, builder, glyphs, placement, widthPolicy).single()
        // Only the first glyph contributes ink, but the advance still sums
        // both glyphs.
        assertEquals(8.0f, atom.inkBounds!!.width)
        assertEquals(16.0f, atom.advance)
    }

    // ------------------------------------------------------------------
    // Stage: attachment walks with empty-text clusters and gaps.
    // ------------------------------------------------------------------

    @Test
    fun attachedMarkWalkStopsMidRunAtAGap() {
        testTrace.section("attachedMarkWalkStopsMidRunAtAGap")
        // Two separators where the second is detached: the walk stops inside
        // the loop, so no separators are collected and no mark is emitted.
        val gapped = listOf(
            inlineObject(0),
            cluster(" ", 1, fontKey = "latin"),
            cluster(" ", 2, fontKey = "latin"),
            cluster("，", 4),
        )
        val gappedRoles = listOf(FontRole.Unknown, FontRole.LatinText, FontRole.LatinText, FontRole.CjkPunctuation)
        assertTrue(gapped.inlineObjectAttachedMarks(gappedRoles, KinsokuLevel.Basic, ClreqKinsokuRule()).isEmpty())

        // Contiguous separators are all collected before the object.
        val contiguous = listOf(
            inlineObject(0),
            cluster(" ", 1, fontKey = "latin"),
            cluster(" ", 2, fontKey = "latin"),
            cluster("，", 3),
        )
        val contiguousRoles = listOf(FontRole.Unknown, FontRole.LatinText, FontRole.LatinText, FontRole.CjkPunctuation)
        val mark = contiguous.inlineObjectAttachedMarks(contiguousRoles, KinsokuLevel.Basic, ClreqKinsokuRule()).single()
        assertEquals(listOf(1, 2), mark.separatorClusterIndices)
        assertEquals(3, mark.markClusterIndex)
    }

    @Test
    fun emptyTextClustersCannotBeAttachedMarks() {
        testTrace.section("emptyTextClustersCannotBeAttachedMarks")
        // An empty-text cluster fails the ASCII point-mark check before any
        // attachment is considered.
        val clusters = listOf(inlineObject(0), cluster("", 1, fontKey = "latin"))
        val roles = listOf(FontRole.Unknown, FontRole.LatinText)
        assertTrue(clusters.inlineObjectAttachedMarks(roles, KinsokuLevel.Basic, ClreqKinsokuRule()).isEmpty())

        // The kinsoku helper honours the same rule: an empty-text mark is
        // neither hangable nor an ASCII point mark.
        val attachments = listOf(InlineObjectAttachedMark(0, emptyList(), 1))
        val result = clusters.inlineObjectAttachedKinsoku(attachments, clusters, KinsokuLevel.Basic, 10.0f, 10.0f)
        assertTrue(result.extendableHangRanges.isEmpty())
        assertTrue(result.impossibleMeasureHangEligibleClusters.isEmpty())
        assertEquals(1, result.decisions.size)
    }

    @Test
    fun asciiPointMarkKinsokuSkipsEmptyTextClusters() {
        testTrace.section("asciiPointMarkKinsokuSkipsEmptyTextClusters")
        val kinsoku = { clusters: List<Cluster>, roles: List<FontRole> ->
            clusters.attachedAsciiPointMarkKinsoku(roles, clusters, KinsokuLevel.Basic, 100.0f, 100.0f)
        }

        // An empty-text cluster at the run position starts nothing.
        val emptyMark = listOf(cluster("中", 0), cluster("", 1, displayText = "x", fontKey = "latin"))
        val emptyMarkRoles = listOf(FontRole.CjkText, FontRole.LatinText)
        assertTrue(kinsoku(emptyMark, emptyMarkRoles).decisions.isEmpty())

        // An empty-text previous cluster has no last character to check.
        val emptyPrevious = listOf(cluster("", 0, displayText = "x", fontKey = "latin"), cluster(",", 0, fontKey = "latin"))
        val emptyPreviousRoles = listOf(FontRole.LatinText, FontRole.LatinText)
        assertTrue(kinsoku(emptyPrevious, emptyPreviousRoles).decisions.isEmpty())

        // An empty-text cluster after a real mark ends the run.
        val emptyNext = listOf(cluster("中", 0), cluster(",", 1, advance = 8.0f, fontKey = "latin"), cluster("", 2, displayText = "x", fontKey = "latin"))
        val emptyNextRoles = listOf(FontRole.CjkText, FontRole.LatinText, FontRole.LatinText)
        val emptyNextResult = kinsoku(emptyNext, emptyNextRoles)
        assertEquals(listOf(0..1), emptyNextResult.unbreakableRanges)
        assertEquals(1, emptyNextResult.decisions.size)

        // A source gap between two point marks splits the run.
        val gappedRun = listOf(cluster("中", 0), cluster(",", 1, advance = 8.0f, fontKey = "latin"), cluster(",", 3, advance = 8.0f, fontKey = "latin"))
        val gappedRunRoles = listOf(FontRole.CjkText, FontRole.LatinText, FontRole.LatinText)
        val gappedRunResult = kinsoku(gappedRun, gappedRunRoles)
        assertEquals(listOf(0..1), gappedRunResult.unbreakableRanges)
        assertEquals(1, gappedRunResult.decisions.size)
    }

    @Test
    fun spaceRunRequiresNonEmptyAllSpaceText() {
        testTrace.section("spaceRunRequiresNonEmptyAllSpaceText")
        assertTrue(cluster(" ", 0, fontKey = "latin").isSpaceRun())
        assertTrue(cluster("  ", 0, fontKey = "latin").isSpaceRun())
        assertFalse(cluster("", 0, displayText = " ", fontKey = "latin").isSpaceRun())
        assertFalse(cluster("a b", 0, fontKey = "latin").isSpaceRun())
        assertFalse(cluster("中", 0).isSpaceRun())
    }

    // ------------------------------------------------------------------
    // Stage: auto-space boundaries with empty-text neighbours.
    // ------------------------------------------------------------------

    private fun edges(
        leading: EastAsianSpacingValue,
        trailing: EastAsianSpacingValue,
    ): EastAsianSpacingEdges = EastAsianSpacingEdges(leading, trailing, leading == EastAsianSpacingValue.Wide)

    private val wide = EastAsianSpacingValue.Wide
    private val narrow = EastAsianSpacingValue.Narrow
    private val other = EastAsianSpacingValue.Other

    @Test
    fun attachedRunAtParagraphEndEmitsNoAutoSpace() {
        testTrace.section("attachedRunAtParagraphEndEmitsNoAutoSpace")
        // The virtual boundary has no next cluster, and the physical boundary
        // after the previous cluster is suppressed, so nothing is emitted.
        val clusters = listOf(cluster("中", 0), cluster("r", 1, fontKey = "latin"))
        val edges = listOf(edges(wide, wide), edges(other, other))
        val result = clusters.applyAutoSpacePolicy(edges, listOf(InlineAttachment.None, InlineAttachment.Previous), AutoSpacePolicy(), 16.0f)
        assertTrue(result.decisions.isEmpty())
        assertEquals(16.0f, result.clusters[1].advance)
    }

    @Test
    fun virtualGapWithEmptyPreviousTextHasNoNarrowCharacter() {
        testTrace.section("virtualGapWithEmptyPreviousTextHasNoNarrowCharacter")
        // N before the run and W after, but the previous cluster's text is
        // empty: there is no narrow character to insert for.
        val clusters = listOf(
            cluster("", 0, displayText = "y", fontKey = "latin"),
            cluster("r", 1, fontKey = "latin"),
            cluster("中", 2),
        )
        val edges = listOf(edges(narrow, narrow), edges(narrow, other), edges(wide, wide))
        val attachments = listOf(InlineAttachment.None, InlineAttachment.Previous, InlineAttachment.None)
        val result = clusters.applyAutoSpacePolicy(edges, attachments, AutoSpacePolicy(), 16.0f)
        assertTrue(result.decisions.isEmpty())

        // Narrow before the run and narrow after: the reversed arm is checked
        // but rejected, so no gap is owned.
        val bothNarrow = listOf(
            cluster("a", 0, advance = 8.0f, fontKey = "latin"),
            cluster("r", 1, fontKey = "latin"),
            cluster("a", 2, advance = 8.0f, fontKey = "latin"),
        )
        val bothNarrowEdges = listOf(edges(narrow, narrow), edges(narrow, other), edges(narrow, narrow))
        val bothNarrowResult = bothNarrow.applyAutoSpacePolicy(bothNarrowEdges, attachments, AutoSpacePolicy(), 16.0f)
        assertTrue(bothNarrowResult.decisions.isEmpty())
    }

    @Test
    fun typedSpaceWithEmptyTextNeighboursKeepsItsWidth() {
        testTrace.section("typedSpaceWithEmptyTextNeighboursKeepsItsWidth")
        // The narrow-side neighbour has no text, so no boundary character is
        // found and the space keeps its typed advance.
        val leadingNarrow = listOf(
            cluster("中", 0),
            cluster(" ", 1, fontKey = "latin"),
            cluster("", 2, displayText = "y", fontKey = "latin"),
        )
        val leadingNarrowEdges = listOf(edges(wide, wide), edges(other, other), edges(narrow, narrow))
        val leadingResult = leadingNarrow.applyAutoSpacePolicy(
            leadingNarrowEdges, List(3) { InlineAttachment.None }, AutoSpacePolicy(), 16.0f,
        )
        assertTrue(leadingResult.decisions.isEmpty())
        assertEquals(16.0f, leadingResult.clusters[1].advance)

        val trailingNarrow = listOf(
            cluster("", 0, displayText = "y", fontKey = "latin"),
            cluster(" ", 1, fontKey = "latin"),
            cluster("中", 2),
        )
        val trailingNarrowEdges = listOf(edges(narrow, narrow), edges(other, other), edges(wide, wide))
        val trailingResult = trailingNarrow.applyAutoSpacePolicy(
            trailingNarrowEdges, List(3) { InlineAttachment.None }, AutoSpacePolicy(), 16.0f,
        )
        assertTrue(trailingResult.decisions.isEmpty())

        // A space between two wide neighbours has no narrow side at all.
        val betweenWide = listOf(cluster("中", 0), cluster(" ", 1, fontKey = "latin"), cluster("中", 2))
        val betweenWideEdges = listOf(edges(wide, wide), edges(other, other), edges(wide, wide))
        val betweenWideResult = betweenWide.applyAutoSpacePolicy(
            betweenWideEdges, List(3) { InlineAttachment.None }, AutoSpacePolicy(), 16.0f,
        )
        assertTrue(betweenWideResult.decisions.isEmpty())
        assertEquals(16.0f, betweenWideResult.clusters[1].advance)
    }

    @Test
    fun spacingBoundariesAtListEdgesAreFalse() {
        testTrace.section("spacingBoundariesAtListEdgesAreFalse")
        // A space run at the very end has no narrow successor to anchor.
        val trailingSpace = listOf(cluster("中", 0), cluster(" ", 1, fontKey = "latin"))
        val trailingSpaceEdges = listOf(edges(wide, wide), edges(other, other))
        assertFalse(isEastAsianSpacingBoundaryAt(1, trailingSpace, trailingSpaceEdges))

        // A space run at the very start has no narrow predecessor.
        val leadingSpace = listOf(cluster(" ", 0, fontKey = "latin"), cluster("中", 1))
        val leadingSpaceEdges = listOf(edges(other, other), edges(wide, wide))
        assertFalse(isEastAsianSpacingBoundaryAt(1, leadingSpace, leadingSpaceEdges))
    }

    @Test
    fun attachedAsciiPointMarkCheckSkipsEmptyPreviousText() {
        testTrace.section("attachedAsciiPointMarkCheckSkipsEmptyPreviousText")
        val clusters = listOf(cluster("", 0, displayText = "x", fontKey = "latin"), cluster(",", 0, fontKey = "latin"))
        assertFalse(clusters.isAttachedAsciiPointMarkAt(1))
    }

    @Test
    fun inlineBoxSpanWithZeroNetStructuralEdgeStillAppliesLeading() {
        testTrace.section("inlineBoxSpanWithZeroNetStructuralEdgeStillAppliesLeading")
        // A leading edge of 2 cancelled by a trailing edge of -2 leaves the
        // advance untouched but still shifts the leading layout advance.
        val clusters = listOf(cluster("a", 0, advance = 8.0f, fontKey = "latin"))
        val result = clusters.applyInlineBoxSpans(
            listOf(
                InlineBoxSpan(TextRange(0, 1), inlineStart = 2.0f),
                InlineBoxSpan(TextRange(0, 1), inlineEnd = -2.0f),
            ),
        )
        assertEquals(8.0f, result.clusters[0].advance)
        assertEquals(2.0f, result.clusters[0].leadingLayoutAdvance)
        assertTrue(result.advanceByCluster.isEmpty())
        assertEquals(2, result.decisions.size)
    }

    // ------------------------------------------------------------------
    // Ledger: budget and geometry arms.
    // ------------------------------------------------------------------

    @Test
    fun resolveClustersAppliesGlyphShiftWithUnchangedAdvance() {
        testTrace.section("resolveClustersAppliesGlyphShiftWithUnchangedAdvance")
        // An underwidth opening glyph fills a full-width cell: the resolved
        // advance equals the cluster advance, but the glyph shift is nonzero,
        // so the cluster is still copied.
        val mark = cluster("「", 0)
        val glyphs = listOf(Glyph(1u, TextRange(0, 1), 8.0f, x = 0.0f))
        val atoms = mark.punctuationAtoms(em, builder, glyphs, placement, widthPolicy)
        val ledger = ledgerOf(listOf(mark), atoms)
        val resolved = ledger.resolveClusters()
        assertEquals(16.0f, resolved[0].advance)
        assertEquals(8.0f, resolved[0].glyphInlineShift)
    }

    @Test
    fun glueCapacitiesMarkCentredFramesAsPaired() {
        testTrace.section("glueCapacitiesMarkCentredFramesAsPaired")
        // Traditional placement splits the pause mark's glue on both sides
        // around a centred body.
        val clusters = listOf(cluster("，", 0))
        val atoms = clusters.flatMap {
            it.punctuationAtoms(em, builder, emptyList(), PunctuationGluePlacement.Traditional, widthPolicy)
        }
        val ledger = ledgerOf(clusters, atoms)
        val capacity = ledger.glueCapacities().getValue(0)
        assertTrue(capacity.paired)
        assertEquals(4.0f, capacity.leading)
        assertEquals(4.0f, capacity.trailing)
    }

    @Test
    fun attachedBoundaryWithPlainPreviousClusterKeepsTheRightBudget() {
        testTrace.section("attachedBoundaryWithPlainPreviousClusterKeepsTheRightBudget")
        // No budget on the previous cluster: the natural virtual glue is the
        // right leading alone, nothing is consumed, and no decision fires.
        val clusters = listOf(
            cluster("中", 0),
            cluster("r", 1, fontKey = "latin"),
            cluster("「", 2),
        )
        val atoms = atomsOf(*clusters.toTypedArray())
        val ledger = ledgerOf(clusters, atoms)
        val result = ledger.resolveAttachedInlinePunctuationBoundaries(
            listOf(InlineAttachment.None, InlineAttachment.Previous, InlineAttachment.None),
            atoms,
            em,
        )
        assertTrue(result.decisions.isEmpty())
        assertTrue(result.trailingGlueByCluster.isEmpty())
        assertEquals(16.0f, result.geometry.resolveClusters()[2].advance)
    }

    @Test
    fun attachedBoundaryRecordsNullCharactersForEmptyTextClusters() {
        testTrace.section("attachedBoundaryRecordsNullCharactersForEmptyTextClusters")
        // The next cluster carries display text but no source text: the
        // decision records the null character instead.
        val textlessNext = listOf(
            cluster("」", 0),
            cluster("r", 1, fontKey = "latin"),
            cluster("", 4, displayText = "a", fontKey = "latin"),
        )
        val textlessNextLedger = ledgerOf(textlessNext)
        val textlessResult = textlessNextLedger.resolveAttachedInlinePunctuationBoundaries(
            listOf(InlineAttachment.None, InlineAttachment.Previous, InlineAttachment.None),
            atomsOf(*textlessNext.toTypedArray()),
            em,
        )
        val decision = textlessResult.decisions.single()
        assertEquals(' ', decision.rightChar)
        assertEquals("AttachedInlineVirtualPunctuationBoundary:natural", decision.reason)
        assertEquals(8.0f, textlessResult.trailingGlueByCluster.getValue(1))

        // A previous cluster whose source text is empty still contributes its
        // display-text punctuation budget; the decision records the null left
        // character.
        val textlessPrevious = listOf(
            cluster("", 0, displayText = "」"),
            cluster("r", 1, fontKey = "latin"),
            cluster("「", 4),
        )
        val textlessPreviousLedger = ledgerOf(textlessPrevious)
        val textlessPreviousResult = textlessPreviousLedger.resolveAttachedInlinePunctuationBoundaries(
            listOf(InlineAttachment.None, InlineAttachment.Previous, InlineAttachment.None),
            atomsOf(*textlessPrevious.toTypedArray()),
            em,
        )
        val previousDecision = textlessPreviousResult.decisions.single()
        assertEquals(' ', previousDecision.leftChar)
        assertEquals("AttachedInlineVirtualPunctuationBoundary:adjacent-punctuation", previousDecision.reason)
    }

    @Test
    fun attachedTrailingGlueWidensABudgetedEndCluster() {
        testTrace.section("attachedTrailingGlueWidensABudgetedEndCluster")
        // The attached cluster itself is punctuation with a budget, so the
        // residual virtual glue widens its resolved advance.
        val clusters = listOf(
            cluster("」", 0),
            cluster("」", 1),
            cluster("「", 2),
        )
        val atoms = atomsOf(*clusters.toTypedArray())
        val widened = listOf(
            atoms[0].copy(trailingGlue = Glue(GlueKind.PunctuationTrailing, 0.0f, 12.0f, 12.0f, 0, 0)),
            atoms[1],
            atoms[2],
        )
        val ledger = ledgerOf(clusters, widened, PunctuationSpacingCompressionResult(emptyList()))
        val result = ledger.resolveAttachedInlinePunctuationBoundaries(
            listOf(InlineAttachment.None, InlineAttachment.Previous, InlineAttachment.None),
            widened,
            em,
        )
        assertEquals(4.0f, result.trailingGlueByCluster.getValue(1))
        assertEquals(20.0f, result.geometry.resolveClusters()[1].advance)
    }

    @Test
    fun spacingPlanIgnoresTargetsOutsideTheBudgets() {
        testTrace.section("spacingPlanIgnoresTargetsOutsideTheBudgets")
        // A reduction aimed at a plain cluster and one aimed past the end of
        // the text both leave the budgets untouched.
        val clusters = listOf(cluster("中", 0), cluster("。", 1))
        val stray = listOf(
            PunctuationSpacingAdjustment(
                range = TextRange(0, 2),
                reductionTargetRange = TextRange(0, 1),
                leftChar = '中',
                rightChar = '。',
                naturalInnerGlue = 8.0f,
                adjustedInnerGlue = 0.0f,
                reduction = 8.0f,
                reason = "test-stray",
            ),
            PunctuationSpacingAdjustment(
                range = TextRange(8, 9),
                reductionTargetRange = TextRange(8, 9),
                leftChar = '中',
                rightChar = '。',
                naturalInnerGlue = 8.0f,
                adjustedInnerGlue = 0.0f,
                reduction = 8.0f,
                reason = "test-past-end",
            ),
        )
        val ledger = ledgerOf(clusters, spacingPlan = PunctuationSpacingCompressionResult(stray))
        assertFalse(ledger.glueCapacities().containsKey(0))
        assertEquals(8.0f, ledger.glueCapacities().getValue(1).trailing)
    }

    @Test
    fun centredAdjacencyConsumesBothSidesEqually() {
        testTrace.section("centredAdjacencyConsumesBothSidesEqually")
        // Two Traditional pause marks collapse their inner glue; the centred
        // frame of the reduction target takes the reduction half from each
        // side, exhausting that cluster's budget.
        val clusters = listOf(cluster("，", 0), cluster("，", 1))
        val atoms = clusters.flatMap {
            it.punctuationAtoms(em, builder, emptyList(), PunctuationGluePlacement.Traditional, widthPolicy)
        }
        val ledger = ledgerOf(clusters, atoms)
        assertTrue(!ledger.glueCapacities().containsKey(0))
        assertEquals(4.0f, ledger.glueCapacities().getValue(1).leading)
        val resolved = ledger.resolveClusters()
        assertEquals(8.0f, resolved[0].advance)
        assertEquals(16.0f, resolved[1].advance)
    }

    @Test
    fun attachedBoundaryReasonFallsBackToNaturalWithoutLeftAtom() {
        testTrace.section("attachedBoundaryReasonFallsBackToNaturalWithoutLeftAtom")
        // The resolve call can receive a narrower atom list than the ledger
        // was built with: the previous cluster keeps its budget, but no atom
        // is found for it, so the natural reason is used.
        val clusters = listOf(cluster("」", 0), cluster("r", 1, fontKey = "latin"), cluster("「", 2))
        val atoms = atomsOf(*clusters.toTypedArray())
        val ledger = ledgerOf(clusters, atoms)
        val result = ledger.resolveAttachedInlinePunctuationBoundaries(
            listOf(InlineAttachment.None, InlineAttachment.Previous, InlineAttachment.None),
            emptyList(),
            em,
        )
        val decision = result.decisions.single()
        assertEquals("AttachedInlineVirtualPunctuationBoundary:natural", decision.reason)
        assertEquals('」', decision.leftChar)
        assertEquals(8.0f, result.trailingGlueByCluster.getValue(1))
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
