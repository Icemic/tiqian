package org.tiqian.layout

import org.tiqian.clreq.AutoSpaceMode
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
import org.tiqian.test.trace.assertFailsWith
import org.tiqian.test.trace.assertTrue
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

/**
 * Coverage for PunctuationGeometryStage.kt: ink attribution over shaped
 * glyphs (per character, union pseudo-glyph, ambiguous fallback), the
 * contextual kinsoku helpers for inline objects and attached ASCII point
 * marks, East_Asian_Spacing auto-space materialisation, and inline box span
 * application. Font-free inputs are hand-built so every branch is direct.
 */
class PunctuationGeometryStageCoverageTest {
    private val testTrace = TestTraceRecorder("PunctuationGeometryStageCoverageTest")


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

    private fun glyph(
        id: UInt,
        advance: Float,
        x: Float = 0.0f,
        bounds: Rect? = null,
        range: TextRange = TextRange(0, 1),
    ): Glyph = Glyph(
        id = id,
        clusterRange = range,
        advance = advance,
        x = x,
        bounds = bounds,
    )

    private fun atomsOf(cluster: Cluster, glyphs: List<Glyph>): List<PunctuationAtom> =
        cluster.punctuationAtoms(em, builder, glyphs, placement, widthPolicy)

    private fun edges(
        leading: EastAsianSpacingValue,
        trailing: EastAsianSpacingValue,
    ): EastAsianSpacingEdges = EastAsianSpacingEdges(leading, trailing, leading == EastAsianSpacingValue.Wide)

    private val wide = EastAsianSpacingValue.Wide
    private val narrow = EastAsianSpacingValue.Narrow
    private val other = EastAsianSpacingValue.Other

    // ------------------------------------------------------------------
    // Ink attribution through punctuationAtoms.
    // ------------------------------------------------------------------

    @Test
    fun multipleGlyphsForOneCharacterUnionIntoASingleInkBox() {
        testTrace.section("multipleGlyphsForOneCharacterUnionIntoASingleInkBox")
        // One display character shaped to two glyphs: the union pseudo-glyph
        // carries the summed advance and the merged ink rectangle.
        val mark = cluster("，", 0)
        val glyphs = listOf(
            glyph(1u, 8.0f, x = 0.0f, bounds = Rect(0.0f, 0.0f, 8.0f, 16.0f)),
            glyph(2u, 6.0f, x = 8.0f, bounds = Rect(0.0f, 0.0f, 6.0f, 16.0f)),
        )
        val atom = atomsOf(mark, glyphs).single()
        assertEquals(14.0f, atom.inkBounds!!.width)
        assertEquals(16.0f, atom.inkBounds!!.bottom)
        assertEquals(16.0f, atom.advance)
        assertNullFallback(atom)
    }

    @Test
    fun unionWithoutBoundsFallsBackToTheFirstGlyph() {
        testTrace.section("unionWithoutBoundsFallsBackToTheFirstGlyph")
        val mark = cluster("，", 0)
        val glyphs = listOf(
            glyph(1u, 8.0f),
            glyph(2u, 6.0f, x = 8.0f),
        )
        val atom = atomsOf(mark, glyphs).single()
        assertEquals("shaper-no-ink-bounds", atom.inkBoundsFallback)
        // The first glyph's advance (8) is the shaped evidence; the policy
        // cell still floors the atom at a full em.
        assertEquals(16.0f, atom.advance)
        assertEquals(8.0f, atom.bodyWidth)
        assertEquals(8.0f, atom.trailingGlue.natural)
    }

    @Test
    fun glyphlessClustersUseThePurePolicyPath() {
        testTrace.section("glyphlessClustersUseThePurePolicyPath")
        val atom = atomsOf(cluster("，", 0), emptyList()).single()
        assertEquals("ProfileGlueFallbackWithoutFontGeometry", atom.geometrySource)
        assertEquals(null, atom.inkBoundsFallback)
        assertEquals(8.0f, atom.trailingGlue.natural)
    }

    @Test
    fun unmatchedGlyphCountsRecordTheAmbiguousFallback() {
        testTrace.section("unmatchedGlyphCountsRecordTheAmbiguousFallback")
        // Two display characters but three glyphs: per-character attribution
        // is impossible, so the builder only records the reason.
        val mark = cluster("。，", 0)
        val glyphs = listOf(glyph(1u, 8.0f), glyph(2u, 8.0f), glyph(3u, 8.0f))
        val atoms = atomsOf(mark, glyphs)
        assertEquals(2, atoms.size)
        assertTrue(atoms.all { it.inkBoundsFallback == "glyph-cluster-mapping-ambiguous" })
        assertTrue(atoms.all { it.inkBounds == null })
        assertEquals(16.0f, atoms[0].advance)
    }

    @Test
    fun perCharacterInkSubtractsPrecedingGlyphPens() {
        testTrace.section("perCharacterInkSubtractsPrecedingGlyphPens")
        // Two characters, two glyphs: each atom sees its own glyph shifted to
        // its character-local pen origin.
        val mark = cluster("。，", 0)
        val glyphs = listOf(
            glyph(1u, 16.0f, x = 0.0f, bounds = Rect(2.0f, 0.0f, 14.0f, 16.0f)),
            glyph(2u, 16.0f, x = 16.0f, bounds = Rect(2.0f, 0.0f, 14.0f, 16.0f)),
        )
        val atoms = atomsOf(mark, glyphs)
        assertEquals(2, atoms.size)
        assertEquals(TextRange(0, 1), atoms[0].range)
        assertEquals(TextRange(1, 2), atoms[1].range)
        assertEquals(12.0f, atoms[0].inkBounds!!.width)
        assertEquals(12.0f, atoms[1].inkBounds!!.width)
        assertTrue(atoms.all { it.inkBoundsFallback == null })
    }

    @Test
    fun emptyDisplayTextProducesNoAtoms() {
        testTrace.section("emptyDisplayTextProducesNoAtoms")
        val mark = cluster("\n", 0, fontKey = "mandatory-break", displayText = "")
        assertTrue(atomsOf(mark, emptyList()).isEmpty())
    }

    private fun assertNullFallback(atom: PunctuationAtom) {
        assertEquals(null, atom.inkBoundsFallback)
    }

    // ------------------------------------------------------------------
    // Inline object attachment detection and kinsoku.
    // ------------------------------------------------------------------

    private fun inlineObject(startIndex: Int): Cluster =
        Cluster(
            range = TextRange(startIndex, startIndex + 1),
            text = "x",
            displayText = "",
            fontKey = "inline-object",
            advance = 8.0f,
        )

    @Test
    fun attachedMarksCollapseSeparatorSpaceBeforeTheMark() {
        testTrace.section("attachedMarksCollapseSeparatorSpaceBeforeTheMark")
        val clusters = listOf(
            inlineObject(0),
            cluster(" ", 1, fontKey = "latin"),
            cluster("，", 2),
        )
        val roles = listOf(FontRole.Unknown, FontRole.LatinText, FontRole.CjkPunctuation)
        val marks = clusters.inlineObjectAttachedMarks(roles, KinsokuLevel.Basic, ClreqKinsokuRule())
        val mark = marks.single()
        assertEquals(0, mark.objectClusterIndex)
        assertEquals(listOf(1), mark.separatorClusterIndices)
        assertEquals(2, mark.markClusterIndex)

        // Kinsoku disabled finds nothing.
        assertTrue(
            clusters.inlineObjectAttachedMarks(roles, KinsokuLevel.None, ClreqKinsokuRule()).isEmpty(),
        )
    }

    @Test
    fun attachedMarksAcceptAsciiPointMarksAfterObjects() {
        testTrace.section("attachedMarksAcceptAsciiPointMarksAfterObjects")
        val clusters = listOf(inlineObject(0), cluster(",", 1, fontKey = "latin"))
        val roles = listOf(FontRole.Unknown, FontRole.LatinText)
        val mark = clusters.inlineObjectAttachedMarks(roles, KinsokuLevel.Basic, ClreqKinsokuRule()).single()
        assertEquals(1, mark.markClusterIndex)
        assertTrue(mark.separatorClusterIndices.isEmpty())
    }

    @Test
    fun attachedMarksRejectMissingObjectsAndGappedRanges() {
        testTrace.section("attachedMarksRejectMissingObjectsAndGappedRanges")
        // No inline object before the mark.
        val noObject = listOf(cluster("中", 0), cluster("，", 1))
        val noObjectRoles = listOf(FontRole.CjkText, FontRole.CjkPunctuation)
        assertTrue(noObject.inlineObjectAttachedMarks(noObjectRoles, KinsokuLevel.Basic, ClreqKinsokuRule()).isEmpty())

        // Only spaces before the mark: the object is missing entirely.
        val onlySpaces = listOf(cluster(" ", 0, fontKey = "latin"), cluster("，", 1))
        val onlySpacesRoles = listOf(FontRole.LatinText, FontRole.CjkPunctuation)
        assertTrue(onlySpaces.inlineObjectAttachedMarks(onlySpacesRoles, KinsokuLevel.Basic, ClreqKinsokuRule()).isEmpty())

        // A range gap between object and separator breaks the attachment.
        val gapped = listOf(
            inlineObject(0),
            cluster(" ", 2, fontKey = "latin"),
            cluster("，", 3),
        )
        val gappedRoles = listOf(FontRole.Unknown, FontRole.LatinText, FontRole.CjkPunctuation)
        assertTrue(gapped.inlineObjectAttachedMarks(gappedRoles, KinsokuLevel.Basic, ClreqKinsokuRule()).isEmpty())

        // Plain CJK text in between never starts an attachment.
        val plain = listOf(cluster("中", 0), cluster("中", 1))
        val plainRoles = listOf(FontRole.CjkText, FontRole.CjkText)
        assertTrue(plain.inlineObjectAttachedMarks(plainRoles, KinsokuLevel.Basic, ClreqKinsokuRule()).isEmpty())
    }

    @Test
    fun inlineObjectKinsokuProtectsOrHangsAttachedMarks() {
        testTrace.section("inlineObjectKinsokuProtectsOrHangsAttachedMarks")
        val clusters = listOf(inlineObject(0), cluster("，", 1))
        val attachments = listOf(InlineObjectAttachedMark(0, emptyList(), 1))

        // Misaligned line-break geometry is rejected.
        assertFailsWith<IllegalArgumentException> {
            clusters.inlineObjectAttachedKinsoku(attachments, clusters.drop(1), KinsokuLevel.Basic, 100.0f, 100.0f)
        }

        // Disabled level performs nothing.
        val disabled = clusters.inlineObjectAttachedKinsoku(attachments, clusters, KinsokuLevel.None, 100.0f, 100.0f)
        assertTrue(disabled.unbreakableRanges.isEmpty())

        // The pair fits the body width: it becomes unbreakable.
        val fits = clusters.inlineObjectAttachedKinsoku(attachments, clusters, KinsokuLevel.Basic, 100.0f, 100.0f)
        assertEquals(listOf(0..1), fits.unbreakableRanges)
        assertEquals(setOf(1), fits.forbiddenLineStartClusters)
        val decision = fits.decisions.single()
        assertEquals("InlineObjectAttachedKinsoku", decision.reason)
        assertEquals(1, decision.clusterIndex)

        // The pair is wider than the line: the hangable mark extends a hang.
        val hangs = clusters.inlineObjectAttachedKinsoku(attachments, clusters, KinsokuLevel.Basic, 10.0f, 10.0f)
        assertTrue(hangs.unbreakableRanges.isEmpty())
        assertEquals(setOf(1), hangs.impossibleMeasureHangEligibleClusters)
        assertEquals(listOf(0..1), hangs.extendableHangRanges)

        // A non-hangable mark wider than the line keeps only the decision.
        val colon = listOf(inlineObject(0), cluster("：", 1))
        val colonAttachments = listOf(InlineObjectAttachedMark(0, emptyList(), 1))
        val blocked = colon.inlineObjectAttachedKinsoku(colonAttachments, colon, KinsokuLevel.Basic, 10.0f, 10.0f)
        assertTrue(blocked.unbreakableRanges.isEmpty())
        assertTrue(blocked.extendableHangRanges.isEmpty())
        assertEquals(1, blocked.decisions.size)

        // A multi-character display mark is not a single hangable mark.
        val pair = listOf(inlineObject(0), cluster("，。", 1, displayText = "，。"))
        val pairAttachments = listOf(InlineObjectAttachedMark(0, emptyList(), 1))
        val noHang = pair.inlineObjectAttachedKinsoku(pairAttachments, pair, KinsokuLevel.Basic, 10.0f, 10.0f)
        assertTrue(noHang.extendableHangRanges.isEmpty())

        // The first line width is used when the object opens the paragraph.
        val firstLineFits = clusters.inlineObjectAttachedKinsoku(attachments, clusters, KinsokuLevel.Basic, 5.0f, 100.0f)
        assertEquals(listOf(0..1), firstLineFits.unbreakableRanges)

        // Separators change the recorded reason and the forbidden set.
        val withSeparator = listOf(inlineObject(0), cluster(" ", 1, fontKey = "latin"), cluster("，", 2))
        val separatorAttachment = listOf(InlineObjectAttachedMark(0, listOf(1), 2))
        val separated = withSeparator.inlineObjectAttachedKinsoku(separatorAttachment, withSeparator, KinsokuLevel.Basic, 100.0f, 100.0f)
        assertEquals(setOf(1, 2), separated.forbiddenLineStartClusters)
        assertEquals("InlineObjectAttachedKinsokuAcrossCollapsedSeparatorSpace", separated.decisions.single().reason)
    }

    @Test
    fun attachedAsciiPointMarkKinsokuProtectsRuns() {
        testTrace.section("attachedAsciiPointMarkKinsokuProtectsRuns")
        val clusters = listOf(
            cluster("中", 0),
            cluster(",", 1, advance = 8.0f, fontKey = "latin"),
            cluster(",", 2, advance = 8.0f, fontKey = "latin"),
        )
        val roles = listOf(FontRole.CjkText, FontRole.LatinText, FontRole.LatinText)

        assertFailsWith<IllegalArgumentException> {
            clusters.attachedAsciiPointMarkKinsoku(roles, clusters.drop(1), KinsokuLevel.Basic, 100.0f, 100.0f)
        }
        assertTrue(clusters.attachedAsciiPointMarkKinsoku(roles, clusters, KinsokuLevel.None, 100.0f, 100.0f).unbreakableRanges.isEmpty())

        // The whole base + run fits the first line: the run is protected.
        val fits = clusters.attachedAsciiPointMarkKinsoku(roles, clusters, KinsokuLevel.Basic, 10.0f, 100.0f)
        assertEquals(listOf(0..2), fits.unbreakableRanges)
        assertEquals(setOf(1, 2), fits.forbiddenLineStartClusters)
        assertEquals(2, fits.decisions.size)
        assertTrue(fits.decisions.all { it.reason == "AttachedAsciiPointMarkKinsoku" })

        // The run outruns the first line: the run stays unbreakable and every
        // mark may hang.
        val hangs = clusters.attachedAsciiPointMarkKinsoku(roles, clusters, KinsokuLevel.Basic, 10.0f, 5.0f)
        assertEquals(listOf(0..2), hangs.unbreakableRanges)
        assertEquals(setOf(1, 2), hangs.impossibleMeasureHangEligibleClusters)
        assertEquals(listOf(0..2), hangs.extendableHangRanges)

        // A following Latin letter ends the run after one mark.
        val bounded = listOf(cluster("中", 0), cluster(",", 1, advance = 8.0f, fontKey = "latin"), cluster("a", 2, advance = 8.0f, fontKey = "latin"))
        val boundedRoles = listOf(FontRole.CjkText, FontRole.LatinText, FontRole.LatinText)
        val boundedResult = bounded.attachedAsciiPointMarkKinsoku(boundedRoles, bounded, KinsokuLevel.Basic, 10.0f, 100.0f)
        assertEquals(listOf(0..1), boundedResult.unbreakableRanges)
        assertEquals(1, boundedResult.decisions.size)

        // A body-line run measures against the body width.
        val midParagraph = listOf(
            cluster("中", 0),
            cluster("中", 1),
            cluster(",", 2, advance = 8.0f, fontKey = "latin"),
        )
        val midRoles = listOf(FontRole.CjkText, FontRole.CjkText, FontRole.LatinText)
        val midResult = midParagraph.attachedAsciiPointMarkKinsoku(midRoles, midParagraph, KinsokuLevel.Basic, 100.0f, 5.0f)
        assertEquals(listOf(1..2), midResult.unbreakableRanges)
    }

    @Test
    fun attachedAsciiPointMarkKinsokuRejectsDetachedRuns() {
        testTrace.section("attachedAsciiPointMarkKinsokuRejectsDetachedRuns")
        val kinsoku = { clusters: List<Cluster>, roles: List<FontRole> ->
            clusters.attachedAsciiPointMarkKinsoku(roles, clusters, KinsokuLevel.Basic, 100.0f, 100.0f)
        }

        // A whitespace cluster before the mark detaches it.
        val afterSpace = listOf(cluster("中", 0), cluster(" ", 1, fontKey = "latin"), cluster(",", 2, fontKey = "latin"))
        val afterSpaceRoles = listOf(FontRole.CjkText, FontRole.LatinText, FontRole.LatinText)
        assertTrue(kinsoku(afterSpace, afterSpaceRoles).decisions.isEmpty())

        // A source gap between base and mark detaches it.
        val gapped = listOf(cluster("中", 0), cluster(",", 2, fontKey = "latin"))
        val gappedRoles = listOf(FontRole.CjkText, FontRole.LatinText)
        assertTrue(kinsoku(gapped, gappedRoles).decisions.isEmpty())

        // An inline object base has no display text, so the mark stays free.
        val objectBase = listOf(inlineObject(0), cluster(",", 1, fontKey = "latin"))
        val objectBaseRoles = listOf(FontRole.Unknown, FontRole.LatinText)
        assertTrue(kinsoku(objectBase, objectBaseRoles).decisions.isEmpty())

        // A plain Latin letter is not a point mark.
        val plain = listOf(cluster("中", 0), cluster("a", 1, fontKey = "latin"))
        val plainRoles = listOf(FontRole.CjkText, FontRole.LatinText)
        assertTrue(kinsoku(plain, plainRoles).decisions.isEmpty())

        // A CJK-font point mark keeps its own contextual kinsoku path.
        val cjkMark = listOf(cluster("中", 0), cluster("，", 1))
        val cjkMarkRoles = listOf(FontRole.CjkText, FontRole.CjkPunctuation)
        assertTrue(kinsoku(cjkMark, cjkMarkRoles).decisions.isEmpty())
    }

    // ------------------------------------------------------------------
    // Auto-space materialisation.
    // ------------------------------------------------------------------

    private val insertPolicy = AutoSpacePolicy()
    private val replacePolicy = AutoSpacePolicy(cjkLatin = AutoSpaceMode.Replace, cjkDigit = AutoSpaceMode.Replace)
    private val disabledPolicy = AutoSpacePolicy.Disabled

    @Test
    fun typedSpaceBetweenWideAndNarrowIsReplacedByTheGap() {
        testTrace.section("typedSpaceBetweenWideAndNarrowIsReplacedByTheGap")
        val clusters = listOf(
            cluster("中", 0),
            cluster(" ", 1, fontKey = "latin"),
            cluster("a", 2, advance = 8.0f, fontKey = "latin"),
        )
        val spacingEdges = listOf(edges(wide, wide), edges(other, other), edges(narrow, narrow))
        val result = clusters.applyAutoSpacePolicy(spacingEdges, List(3) { InlineAttachment.None }, replacePolicy, 16.0f)
        val decision = result.decisions.single()
        assertEquals("gap", decision.side)
        assertEquals("Replace", decision.mode)
        assertEquals("EastAsianSpacing.Wide", decision.boundaryRole)
        assertEquals("TextAutoSpaceReplace:east-asian-spacing-W-space-N", decision.reason)
        assertEquals(1, decision.charactersAffected)
        assertEquals(14.0f, decision.reductionPerChar)
        assertEquals(14.0f, decision.totalReduction)
        assertEquals(2.0f, result.clusters[1].advance)
    }

    @Test
    fun spaceReplacementSkipsDisabledModeNullBoundariesAndExactWidths() {
        testTrace.section("spaceReplacementSkipsDisabledModeNullBoundariesAndExactWidths")
        // Disabled policy leaves the typed space untouched.
        val clusters = listOf(
            cluster("中", 0),
            cluster(" ", 1, fontKey = "latin"),
            cluster("a", 2, advance = 8.0f, fontKey = "latin"),
        )
        val spacingEdges = listOf(edges(wide, wide), edges(other, other), edges(narrow, narrow))
        val disabled = clusters.applyAutoSpacePolicy(spacingEdges, List(3) { InlineAttachment.None }, disabledPolicy, 16.0f)
        assertTrue(disabled.decisions.isEmpty())
        assertEquals(16.0f, disabled.clusters[1].advance)

        // A space already exactly as wide as the gap is left alone.
        val exactWidth = listOf(cluster("中", 0), cluster(" ", 1, advance = 2.0f, fontKey = "latin"), cluster("a", 2, advance = 8.0f, fontKey = "latin"))
        val exact = exactWidth.applyAutoSpacePolicy(spacingEdges, List(3) { InlineAttachment.None }, replacePolicy, 16.0f)
        assertTrue(exact.decisions.isEmpty())

        // A lone space with no Wide neighbour has no boundary to serve.
        val lone = listOf(cluster(" ", 0, fontKey = "latin"))
        val loneResult = lone.applyAutoSpacePolicy(listOf(edges(other, other)), listOf(InlineAttachment.None), replacePolicy, 16.0f)
        assertTrue(loneResult.decisions.isEmpty())

        // Misaligned inputs are rejected.
        assertFailsWith<IllegalArgumentException> {
            clusters.applyAutoSpacePolicy(listOf(edges(wide, wide)), List(3) { InlineAttachment.None }, replacePolicy, 16.0f)
        }
        assertFailsWith<IllegalArgumentException> {
            clusters.applyAutoSpacePolicy(spacingEdges, listOf(InlineAttachment.None), replacePolicy, 16.0f)
        }

        // An empty cluster list is returned as empty.
        val empty = emptyList<Cluster>().applyAutoSpacePolicy(emptyList(), emptyList(), replacePolicy, 16.0f)
        assertTrue(empty.clusters.isEmpty())
    }

    @Test
    fun wideToNarrowBoundariesInsertLeadingAndTrailingGaps() {
        testTrace.section("wideToNarrowBoundariesInsertLeadingAndTrailingGaps")
        val leading = listOf(cluster("中", 0), cluster("a", 1, advance = 8.0f, fontKey = "latin"))
        val leadingEdges = listOf(edges(wide, wide), edges(narrow, narrow))
        val leadingResult = leading.applyAutoSpacePolicy(leadingEdges, List(2) { InlineAttachment.None }, insertPolicy, 16.0f)
        val leadingDecision = leadingResult.decisions.single()
        assertEquals("leading", leadingDecision.side)
        assertEquals("EastAsianSpacing.Wide", leadingDecision.boundaryRole)
        assertEquals("TextAutoSpaceInsert:east-asian-spacing-W-N", leadingDecision.reason)
        assertEquals(-2.0f, leadingDecision.totalReduction)
        assertEquals(10.0f, leadingResult.clusters[1].advance)

        // Narrow-to-wide adds a trailing gap on the narrow cluster.
        val trailing = listOf(cluster("a", 0, advance = 8.0f, fontKey = "latin"), cluster("中", 1))
        val trailingEdges = listOf(edges(narrow, narrow), edges(wide, wide))
        val trailingResult = trailing.applyAutoSpacePolicy(trailingEdges, List(2) { InlineAttachment.None }, insertPolicy, 16.0f)
        val trailingDecision = trailingResult.decisions.single()
        assertEquals("trailing", trailingDecision.side)
        assertEquals(10.0f, trailingResult.clusters[0].advance)
    }

    @Test
    fun narrowInlineBoxesOwnTheirOuterAutoSpace() {
        testTrace.section("narrowInlineBoxesOwnTheirOuterAutoSpace")
        val clusters = listOf(cluster("中", 0), cluster("a", 1, advance = 8.0f, fontKey = "latin"))
        val spacingEdges = listOf(edges(wide, wide), edges(narrow, narrow))
        val result = clusters.applyAutoSpacePolicy(
            spacingEdges,
            List(2) { InlineAttachment.None },
            insertPolicy,
            16.0f,
            narrowInlineBoxLeadingClusters = setOf(1),
        )
        val decision = result.decisions.single()
        assertEquals("InlineBox.Narrow", decision.boundaryRole)
        assertEquals("InlineBoxOuterAutoSpace:leading-W-N", decision.reason)

        val trailingClusters = listOf(cluster("a", 0, advance = 8.0f, fontKey = "latin"), cluster("中", 1))
        val trailingEdges = listOf(edges(narrow, narrow), edges(wide, wide))
        val trailingResult = trailingClusters.applyAutoSpacePolicy(
            trailingEdges,
            List(2) { InlineAttachment.None },
            insertPolicy,
            16.0f,
            narrowInlineBoxTrailingClusters = setOf(0),
        )
        val trailingDecision = trailingResult.decisions.single()
        assertEquals("InlineBox.Narrow", trailingDecision.boundaryRole)
        assertEquals("InlineBoxOuterAutoSpace:trailing-N-W", trailingDecision.reason)
    }

    @Test
    fun attachedRunsOwnOneVirtualGapAtTheirTrailingEdge() {
        testTrace.section("attachedRunsOwnOneVirtualGapAtTheirTrailingEdge")
        // The attached run is ignored for boundary decisions: the virtual
        // W/N gap between the surrounding prose clusters materialises once,
        // on the run's trailing edge, and the physical edge is suppressed.
        val clusters = listOf(
            cluster("中", 0),
            cluster("ref", 1, fontKey = "latin"),
            cluster("a", 2, advance = 8.0f, fontKey = "latin"),
        )
        val attachments = listOf(InlineAttachment.None, InlineAttachment.Previous, InlineAttachment.None)
        val spacingEdges = listOf(edges(wide, wide), edges(other, wide), edges(narrow, narrow))
        val result = clusters.applyAutoSpacePolicy(spacingEdges, attachments, insertPolicy, 16.0f)
        val decision = result.decisions.single()
        assertEquals("trailing", decision.side)
        assertEquals("InlineAttachment.Previous", decision.boundaryRole)
        assertEquals("AttachedInlineVirtualAutoSpace:east-asian-spacing-W-N", decision.reason)
        assertEquals(18.0f, result.clusters[1].advance)
        assertEquals(8.0f, result.clusters[2].advance)
    }

    @Test
    fun virtualGapsRespectNarrowToWideEdgesAndTheirNeighbours() {
        testTrace.section("virtualGapsRespectNarrowToWideEdgesAndTheirNeighbours")
        val attachments = listOf(InlineAttachment.None, InlineAttachment.Previous, InlineAttachment.None)

        // Narrow before the run and wide after: the gap is owned the same way.
        val reversed = listOf(
            cluster("a", 0, advance = 8.0f, fontKey = "latin"),
            cluster("ref", 1, fontKey = "latin"),
            cluster("中", 2),
        )
        val reversedEdges = listOf(edges(narrow, narrow), edges(narrow, other), edges(wide, wide))
        val reversedResult = reversed.applyAutoSpacePolicy(reversedEdges, attachments, insertPolicy, 16.0f)
        assertEquals(1, reversedResult.decisions.size)
        assertEquals("AttachedInlineVirtualAutoSpace:east-asian-spacing-W-N", reversedResult.decisions.single().reason)

        // A space run after the attachment is author content: no virtual gap.
        val spaceAfter = listOf(
            cluster("中", 0),
            cluster("ref", 1, fontKey = "latin"),
            cluster(" ", 2, fontKey = "latin"),
        )
        val spaceAfterEdges = listOf(edges(wide, wide), edges(other, other), edges(other, other))
        val spaceResult = spaceAfter.applyAutoSpacePolicy(spaceAfterEdges, attachments, insertPolicy, 16.0f)
        assertTrue(spaceResult.decisions.isEmpty())

        // A mandatory break after the attachment cannot carry a gap.
        val breakAfter = listOf(
            cluster("中", 0),
            cluster("ref", 1, fontKey = "latin"),
            cluster("\n", 2, fontKey = "mandatory-break", displayText = ""),
        )
        val breakResult = breakAfter.applyAutoSpacePolicy(spaceAfterEdges, attachments, insertPolicy, 16.0f)
        assertTrue(breakResult.decisions.isEmpty())

        // Wide prose on both sides of the run has no W/N boundary at all.
        val cjkAfter = listOf(cluster("中", 0), cluster("ref", 1, fontKey = "latin"), cluster("中", 2))
        val cjkAfterEdges = listOf(edges(wide, wide), edges(other, other), edges(wide, wide))
        val cjkResult = cjkAfter.applyAutoSpacePolicy(cjkAfterEdges, attachments, insertPolicy, 16.0f)
        assertTrue(cjkResult.decisions.isEmpty())
    }

    // ------------------------------------------------------------------
    // Boundary predicates and inline box spans.
    // ------------------------------------------------------------------

    @Test
    fun spacingBoundariesCountEachWideNarrowGapOnce() {
        testTrace.section("spacingBoundariesCountEachWideNarrowGapOnce")
        val pairWn = listOf(cluster("中", 0), cluster("a", 1, fontKey = "latin"))
        val pairWnEdges = listOf(edges(wide, wide), edges(narrow, narrow))
        assertTrue(isEastAsianSpacingBoundaryAt(1, pairWn, pairWnEdges))

        val pairNw = listOf(cluster("a", 0, fontKey = "latin"), cluster("中", 1))
        val pairNwEdges = listOf(edges(narrow, narrow), edges(wide, wide))
        assertTrue(isEastAsianSpacingBoundaryAt(1, pairNw, pairNwEdges))

        // A typed space anchors the gap on its Wide side.
        val spaceRight = listOf(cluster("中", 0), cluster(" ", 1, fontKey = "latin"), cluster("a", 2, fontKey = "latin"))
        val spaceRightEdges = listOf(edges(wide, wide), edges(other, other), edges(narrow, narrow))
        assertTrue(isEastAsianSpacingBoundaryAt(1, spaceRight, spaceRightEdges))

        val spaceLeft = listOf(cluster("a", 0, fontKey = "latin"), cluster(" ", 1, fontKey = "latin"), cluster("中", 2))
        val spaceLeftEdges = listOf(edges(narrow, narrow), edges(other, other), edges(wide, wide))
        assertTrue(isEastAsianSpacingBoundaryAt(2, spaceLeft, spaceLeftEdges))

        val cjkPair = listOf(cluster("中", 0), cluster("中", 1))
        val cjkPairEdges = listOf(edges(wide, wide), edges(wide, wide))
        assertTrue(!isEastAsianSpacingBoundaryAt(1, cjkPair, cjkPairEdges))
    }

    @Test
    fun attachedAsciiPointMarksNeedAContiguousNonSpaceBase() {
        testTrace.section("attachedAsciiPointMarksNeedAContiguousNonSpaceBase")
        val attached = listOf(cluster("中", 0), cluster(",", 1, fontKey = "latin"))
        assertTrue(attached.isAttachedAsciiPointMarkAt(1))
        assertTrue(!attached.isAttachedAsciiPointMarkAt(0))

        val emptyMark = listOf(cluster("中", 0), cluster("", 1, fontKey = "latin"))
        assertTrue(!emptyMark.isAttachedAsciiPointMarkAt(1))

        val plainLetter = listOf(cluster("中", 0), cluster("a", 1, fontKey = "latin"))
        assertTrue(!plainLetter.isAttachedAsciiPointMarkAt(1))

        val afterSpace = listOf(cluster("中", 0), cluster(" ", 1, fontKey = "latin"), cluster(",", 2, fontKey = "latin"))
        assertTrue(!afterSpace.isAttachedAsciiPointMarkAt(2))

        val gapped = listOf(cluster("中", 0), cluster(",", 2, fontKey = "latin"))
        assertTrue(!gapped.isAttachedAsciiPointMarkAt(1))
    }

    @Test
    fun inlineBoxSpansAddStructuralEdgesAndSkipDegenerateRanges() {
        testTrace.section("inlineBoxSpansAddStructuralEdgesAndSkipDegenerateRanges")
        val clusters = listOf(
            cluster("a", 0, advance = 8.0f, fontKey = "latin"),
            cluster("b", 1, advance = 8.0f, fontKey = "latin"),
            cluster("c", 2, advance = 8.0f, fontKey = "latin"),
        )

        // Empty inputs pass the clusters through.
        assertTrue(clusters.applyInlineBoxSpans(emptyList()).clusters === clusters)
        val fromEmpty = emptyList<Cluster>().applyInlineBoxSpans(listOf(InlineBoxSpan(TextRange(0, 1), inlineStart = 2.0f)))
        assertTrue(fromEmpty.clusters.isEmpty())

        // Degenerate and stray spans are skipped without decisions.
        val skipped = clusters.applyInlineBoxSpans(
            listOf(
                InlineBoxSpan(TextRange(2, 2), inlineStart = 4.0f),
                InlineBoxSpan(TextRange(10, 11), inlineStart = 4.0f),
            ),
        )
        assertTrue(skipped.decisions.isEmpty())
        assertTrue(skipped.advanceByCluster.isEmpty())

        // Real spans accumulate edges on the same cluster.
        val applied = clusters.applyInlineBoxSpans(
            listOf(
                InlineBoxSpan(TextRange(0, 1), inlineStart = 2.0f),
                InlineBoxSpan(TextRange(1, 2), inlineEnd = 3.0f),
                InlineBoxSpan(TextRange(0, 2), inlineEnd = 1.5f),
            ),
        )
        assertEquals(3, applied.decisions.size)
        assertEquals(mapOf(0 to 2.0f, 1 to 4.5f), applied.advanceByCluster)
        assertEquals(10.0f, applied.clusters[0].advance)
        assertEquals(2.0f, applied.clusters[0].leadingLayoutAdvance)
        assertEquals(12.5f, applied.clusters[1].advance)
        // A cluster outside every span keeps its original box.
        assertEquals(8.0f, applied.clusters[2].advance)
        assertEquals(0.0f, applied.clusters[2].leadingLayoutAdvance)

        // A span wider than the cluster's own advance clamps the box at zero.
        val clamped = listOf(cluster("a", 0, advance = 2.0f, fontKey = "latin")).applyInlineBoxSpans(
            listOf(InlineBoxSpan(TextRange(0, 1), inlineEnd = -6.0f)),
        )
        assertEquals(0.0f, clamped.clusters[0].advance)
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
