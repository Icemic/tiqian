package org.tiqian.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.tiqian.clreq.ClreqProfile
import org.tiqian.clreq.ClreqProfileResolver
import org.tiqian.clreq.HangingPunctuationStyle
import org.tiqian.clreq.KinsokuLevel
import org.tiqian.clreq.KinsokuMode
import org.tiqian.core.Ic
import org.tiqian.core.INLINE_OBJECT_REPLACEMENT_CHAR
import org.tiqian.core.InlineObjectBoundaryAdjustment
import org.tiqian.core.InlineObjectPreferredStretch
import org.tiqian.core.InlineObjectPreferredStretchKind
import org.tiqian.core.InlineObjectSpan
import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LayoutInput
import org.tiqian.core.LineLengthGrid
import org.tiqian.core.ParagraphStyle
import org.tiqian.core.TextRange
import org.tiqian.core.TextStyle
import org.tiqian.core.TiqianTextContent
import org.tiqian.core.positionedClusters

class InlineObjectLayoutTest {
    private val style = ParagraphStyle(
        firstLineIndent = Ic(0f),
        lineHeight = 24f,
        lineLengthGrid = LineLengthGrid(enabled = false),
    )

    @Test
    fun lineBoundaryClosesOneUlpGapWithoutChangingBaselineDistance() {
        val boundary = resolveInlineObjectLineBoundaryExtent(
            nominalBoundaryExtent = 80f,
            currentContentBottomExtent = 84.14f,
            baselineDistance = 100f,
            nextContentTopExtent = 15.86001f,
        )

        assertEquals(84.14f, boundary)
    }

    @Test
    fun inlineObjectUsesExistingInterlineSpaceWithoutMovingBaselines() {
        fun layout(inlineObjects: List<InlineObjectSpan>) =
            TiqianParagraphLayoutEngine().layout(
                LayoutInput(
                    content = TiqianTextContent("甲乙"),
                    textStyle = TextStyle(fontSize = 16f),
                    constraints = LayoutConstraints(maxWidth = 16f),
                    paragraphStyle = style,
                    inlineObjects = inlineObjects,
                ),
            )

        val plain = layout(emptyList())
        val withObject = layout(
            listOf(InlineObjectSpan(TextRange(1, 2), advance = 16f, ascent = 20f, descent = 2f)),
        )

        assertEquals(2, withObject.lines.size)
        assertEquals(
            plain.lines[1].baseline - plain.lines[0].baseline,
            withObject.lines[1].baseline - withObject.lines[0].baseline,
            0.001f,
        )
        assertEquals(24f, withObject.lines[1].baseline - withObject.lines[0].baseline, 0.001f)
        assertEquals(plain.size.height, withObject.size.height, 0.001f)
        assertEquals(
            withObject.lines[1].baseline - 20f,
            withObject.lines[1].top,
            0.001f,
            "the existing inter-line gap should be reassigned to the object's own line box",
        )

        val decision = assertNotNull(withObject.debug.inlineObjectLineHeightDecision)
        assertEquals(1.6f, decision.minimumClearance, 0.001f)
        assertTrue(
            withObject.lines[1].baseline - 20f -
                (withObject.lines[0].baseline + decision.baseFaceDescent) >=
                decision.minimumClearance - 0.001f,
        )
        assertTrue(decision.lineExtras.all { it == 0f })
        assertTrue(decision.expandedLineIndices.isEmpty())
        assertTrue(decision.boundaryShiftsAfter.single() < 0f)
        assertEquals("ExistingInterlineSpaceFitsInlineObjects", decision.reason)
    }

    @Test
    fun inlineObjectExpandsBaselineGapOnlyForActualCollision() {
        fun layout(paragraphStyle: ParagraphStyle) = TiqianParagraphLayoutEngine().layout(
            LayoutInput(
                content = TiqianTextContent("甲乙"),
                textStyle = TextStyle(fontSize = 16f),
                constraints = LayoutConstraints(maxWidth = 16f),
                paragraphStyle = paragraphStyle,
                inlineObjects = listOf(
                    InlineObjectSpan(TextRange(0, 1), advance = 16f, ascent = 14f, descent = 10f),
                    InlineObjectSpan(TextRange(1, 2), advance = 16f, ascent = 20f, descent = 2f),
                ),
            ),
        )
        val result = layout(style)

        assertEquals(31.6f, result.lines[1].baseline - result.lines[0].baseline, 0.001f)
        assertEquals(
            1.6f,
            result.lines[1].baseline - 20f - (result.lines[0].baseline + 10f),
            0.001f,
            "the measured collision deficit must retain the configured safety clearance",
        )
        val decision = assertNotNull(result.debug.inlineObjectLineHeightDecision)
        assertEquals(0f, decision.lineExtras[0], 0.001f)
        assertEquals(7.6f, decision.lineExtras[1], 0.001f)
        assertEquals(listOf(1), decision.expandedLineIndices)
        assertEquals("InlineObjectInterlineCollision", decision.reason)

        val withoutClearance = layout(style.copy(inlineObjectMinimumClearanceEm = 0f))
        assertEquals(30f, withoutClearance.lines[1].baseline - withoutClearance.lines[0].baseline, 0.001f)
        assertEquals(0f, assertNotNull(withoutClearance.debug.inlineObjectLineHeightDecision).minimumClearance)
    }

    @Test
    fun inlineObjectSkipsFontShapingAndExpandsItsOwnLineMetrics() {
        val result = TiqianParagraphLayoutEngine().layout(
            LayoutInput(
                content = TiqianTextContent("中${INLINE_OBJECT_REPLACEMENT_CHAR}文"),
                textStyle = TextStyle(fontSize = 16f),
                constraints = LayoutConstraints(maxWidth = 120f),
                paragraphStyle = style,
                inlineObjects = listOf(
                    InlineObjectSpan(
                        range = TextRange(1, 2),
                        advance = 20f,
                        ascent = 30f,
                        descent = 4f,
                    ),
                ),
            ),
        )

        val objectCluster = result.clusters.single { it.range == TextRange(1, 2) }
        assertEquals(20f, objectCluster.advance, 0.001f)
        assertTrue(result.glyphRuns.flatMap { it.glyphs }.none { it.clusterRange == objectCluster.range })

        val shaping = result.debug.shapingDecisions.single { it.range == objectCluster.range }
        assertEquals(0, shaping.glyphCount)
        assertEquals("MeasurableOpaqueInlineObject:no-font-shaping", shaping.reason)

        val line = result.lines.single()
        assertTrue(line.baseline - line.top >= 30f)
        assertTrue(line.bottom - line.baseline >= 4f)
        val decision = result.debug.inlineObjectDecisions.single()
        assertEquals(0, decision.lineIndex)
        assertEquals("MeasurableOpaqueInlineObject", decision.reason)
    }

    @Test
    fun inlineObjectIsOneIndivisibleBreakCluster() {
        val result = TiqianParagraphLayoutEngine().layout(
            LayoutInput(
                content = TiqianTextContent("中${INLINE_OBJECT_REPLACEMENT_CHAR}文"),
                textStyle = TextStyle(fontSize = 16f),
                constraints = LayoutConstraints(maxWidth = 35f),
                paragraphStyle = style,
                inlineObjects = listOf(
                    InlineObjectSpan(TextRange(1, 2), advance = 20f, ascent = 16f, descent = 4f),
                ),
            ),
        )

        val objectIndex = result.clusters.indexOfFirst { it.range == TextRange(1, 2) }
        val objectLine = result.lines.single { objectIndex in it.clusterRange }
        assertEquals(objectIndex..objectIndex, objectLine.clusterRange)
    }

    @Test
    fun inlineObjectKeepsAlternateSourceTextWhileSkippingItsGlyphShaping() {
        val result = TiqianParagraphLayoutEngine().layout(
            LayoutInput(
                content = TiqianTextContent("中图片文"),
                textStyle = TextStyle(fontSize = 16f),
                constraints = LayoutConstraints(maxWidth = 120f),
                paragraphStyle = style,
                inlineObjects = listOf(
                    InlineObjectSpan(TextRange(1, 3), advance = 20f, ascent = 16f, descent = 4f),
                ),
            ),
        )

        val objectCluster = result.clusters.single { it.range == TextRange(1, 3) }
        assertEquals("图片", objectCluster.text)
        assertEquals("", objectCluster.displayText)
        assertTrue(result.glyphRuns.flatMap { it.glyphs }.none { it.clusterRange == objectCluster.range })
        val shaping = result.debug.shapingDecisions.single { it.range == objectCluster.range }
        assertEquals("图片", shaping.sourceText)
        assertEquals("", shaping.displayText)
    }

    @Test
    fun formulaBoundaryCompressionPushesAttachedCommaIntoPreviousLine() {
        val text = "x+，后"
        val engine = fixedBasicKinsokuEngine()
        val result = engine.layout(
            LayoutInput(
                content = TiqianTextContent(text),
                textStyle = TextStyle(fontSize = 16f),
                constraints = LayoutConstraints(maxWidth = 36f),
                paragraphStyle = style,
                inlineObjects = listOf(
                    InlineObjectSpan(
                        range = TextRange(0, 2),
                        advance = 30f,
                        ascent = 16f,
                        descent = 4f,
                        trailingBoundary = InlineObjectBoundaryAdjustment(
                            participatesInUniformStretch = true,
                            shrinkCapacity = 4f,
                        ),
                    ),
                ),
            ),
        )

        assertTrue(result.lines.none { text.substring(it.range.start, it.range.end).startsWith('，') })
        val repair = assertNotNull(result.debug.lineDecisions.first().repairDecision)
        assertEquals("PushIn", repair.kind)
        assertTrue(
            repair.pushInAllocations.any { it.clusterRange == TextRange(0, 2) && it.shrink > 0f },
            "formula boundary space must contribute to compression: ${repair.pushInAllocations}",
        )
    }

    @Test
    fun adjustBreakForUnbreakablesRetreatsPastTheWholeContiguousRun() {
        // A per-atom formula (e.g. `P(0.5)`) produces a CHAIN of adjacent unbreakable ranges. A
        // break landing inside it must retreat past the ENTIRE run, not a single range — the
        // closure gap that let `0.5|)` slip back onto the phone. Ranges below forbid breaks at
        // clusters 2,3,4; the only legal points are 1 and 5.
        val chain = listOf(1..2, 2..3, 3..4)
        // Break inside the run (2, 3, or 4) retreats all the way to 1, not just one range back.
        assertEquals(1, adjustBreakForUnbreakables(4, lineStart = 0, unbreakableRanges = chain))
        assertEquals(1, adjustBreakForUnbreakables(3, lineStart = 0, unbreakableRanges = chain))
        assertEquals(1, adjustBreakForUnbreakables(2, lineStart = 0, unbreakableRanges = chain))
        // A break already outside the run is untouched.
        assertEquals(5, adjustBreakForUnbreakables(5, lineStart = 0, unbreakableRanges = chain))
        // Retreat halts at the first legal point strictly above lineStart.
        assertEquals(3, adjustBreakForUnbreakables(5, lineStart = 2, unbreakableRanges = listOf(3..4, 4..5)))
        // Give up (keep the original break) when the run reaches the line start: it is wider than
        // the line, so an overflow inside it is unavoidable.
        assertEquals(4, adjustBreakForUnbreakables(4, lineStart = 1, unbreakableRanges = chain))
    }

    @Test
    fun perAtomFormulaChainNeverBreaksMidRun() {
        // A per-atom formula (`10^{34}x^3`-style) splits into a CHAIN of adjacent unbreakable
        // ranges. When it overflows, the whole chain must move to the next line together — the fill
        // push-in pass must not refill a break back inside it (the `10^{34}|x^3` slip). Four atoms
        // at 12f each = 48f follow a 16f leading glyph; at maxWidth 60f the chain cannot share the
        // first line, so the only legal break is right after the leading cluster.
        val closed = InlineObjectBoundaryAdjustment(preventsLineBreak = true)
        val run = (1..4).map { atom ->
            InlineObjectSpan(
                range = TextRange(atom, atom + 1),
                advance = 12f,
                ascent = 16f,
                descent = 4f,
                trailingBoundary = if (atom < 4) closed else InlineObjectBoundaryAdjustment(),
            )
        }
        val result = fixedBasicKinsokuEngine(LookaheadLineBreaker()).layout(
            LayoutInput(
                content = TiqianTextContent("中一二三四"),
                textStyle = TextStyle(fontSize = 16f),
                constraints = LayoutConstraints(maxWidth = 60f),
                paragraphStyle = style,
                inlineObjects = run,
            ),
        )
        // Clusters 2,3,4 sit strictly inside the chain — no line may end there.
        val illegalBreaks = setOf(2, 3, 4)
        for (line in result.lines) {
            assertTrue(
                line.range.end !in illegalBreaks,
                "line ended inside the unbreakable formula chain: ${result.lines.map { it.range }}",
            )
        }
    }

    @Test
    fun punctuationAttachedToInlineObjectNeverStartsWrappedLine() {
        val breakers = listOf(
            GreedyLineBreaker(),
            LookaheadLineBreaker(),
            ParagraphDpLineBreaker(),
        )
        for (breaker in breakers) {
            for (comma in listOf('，', ',')) {
                val text = "x+$comma 后"
                for (width in listOf(24f, 32f, 36f, 48f, 64f)) {
                    val result = fixedBasicKinsokuEngine(breaker).layout(
                        LayoutInput(
                            content = TiqianTextContent(text),
                            textStyle = TextStyle(fontSize = 16f),
                            constraints = LayoutConstraints(maxWidth = width),
                            paragraphStyle = style,
                            inlineObjects = listOf(
                                InlineObjectSpan(
                                    range = TextRange(0, 2),
                                    advance = 30f,
                                    ascent = 16f,
                                    descent = 4f,
                                ),
                            ),
                        ),
                    )
                    val lineTexts = result.lines.map { text.substring(it.range.start, it.range.end) }
                    assertTrue(
                        lineTexts.none { it.startsWith(comma) },
                        "breaker=${breaker.strategyName} width=$width comma=$comma lines=$lineTexts",
                    )
                    assertTrue(
                        result.debug.contextualKinsokuDecisions.any {
                            it.sourceText == comma.toString() && it.reason == "InlineObjectAttachedKinsoku"
                        },
                    )
                }
            }
        }
    }

    @Test
    fun separatorSpaceBeforePunctuationCollapsesAndStaysWithInlineObject() {
        val breakers = listOf(
            GreedyLineBreaker(),
            LookaheadLineBreaker(),
            ParagraphDpLineBreaker(),
        )
        val text = "前x ，后文"
        for (breaker in breakers) {
            for (width in listOf(32f, 40f, 48f, 56f, 64f)) {
                val result = fixedBasicKinsokuEngine(breaker).layout(
                    LayoutInput(
                        content = TiqianTextContent(text),
                        textStyle = TextStyle(fontSize = 16f),
                        constraints = LayoutConstraints(maxWidth = width),
                        paragraphStyle = style,
                        inlineObjects = listOf(
                            InlineObjectSpan(
                                range = TextRange(1, 2),
                                advance = 24f,
                                ascent = 16f,
                                descent = 4f,
                                leadingBoundary = InlineObjectBoundaryAdjustment(
                                    participatesInUniformStretch = true,
                                ),
                                trailingBoundary = InlineObjectBoundaryAdjustment(
                                    participatesInUniformStretch = true,
                                ),
                            ),
                        ),
                    ),
                )

                val space = result.clusters.single { it.range == TextRange(2, 3) }
                assertEquals(0f, space.advance, 0.001f)
                val lineTexts = result.lines.map { text.substring(it.range.start, it.range.end) }
                assertTrue(
                    lineTexts.none { it.trimStart().startsWith('，') },
                    "breaker=${breaker.strategyName} width=$width lines=$lineTexts",
                )
                assertTrue(
                    result.debug.contextualKinsokuDecisions.any {
                        it.sourceText == "，" &&
                            it.reason == "InlineObjectAttachedKinsokuAcrossCollapsedSeparatorSpace"
                    },
                )
                val attachment = result.debug.inlineObjectPunctuationAttachmentDecisions.single()
                assertEquals(TextRange(2, 3), attachment.separatorRange)
                assertTrue(attachment.collapsedAdvance > 0f)
                assertTrue(
                    result.debug.justificationDecisions.flatMap { it.allocations }.none {
                        it.clusterRange == TextRange(1, 2) && it.kind == GlueKind.InlineObjectBoundary.name
                    },
                    "the formula edge before attached punctuation must stay closed",
                )
            }
        }
    }

    @Test
    fun relationStretchMovesBothFormulaSidesByTheSameFinalGeometry() {
        val naturalRelationGap = 5f / 18f * 16f
        val targetGap = 0.5f * 16f
        val formulaBodyWidth = 10f
        val text = "a=b中"
        val result = fixedBasicKinsokuEngine().layout(
            LayoutInput(
                content = TiqianTextContent(text),
                textStyle = TextStyle(fontSize = 16f),
                constraints = LayoutConstraints(maxWidth = 47f),
                paragraphStyle = style,
                inlineObjects = listOf(
                    InlineObjectSpan(
                        range = TextRange(0, 1),
                        advance = formulaBodyWidth + naturalRelationGap,
                        ascent = 12f,
                        descent = 4f,
                        trailingBoundary = InlineObjectBoundaryAdjustment(
                            participatesInUniformStretch = true,
                            preferredStretch = InlineObjectPreferredStretch(
                                kind = InlineObjectPreferredStretchKind.Relation,
                                naturalWidth = naturalRelationGap,
                                targetWidth = targetGap,
                            ),
                        ),
                    ),
                    InlineObjectSpan(
                        range = TextRange(1, 2),
                        advance = formulaBodyWidth + naturalRelationGap,
                        ascent = 12f,
                        descent = 4f,
                        trailingBoundary = InlineObjectBoundaryAdjustment(
                            participatesInUniformStretch = true,
                            preferredStretch = InlineObjectPreferredStretch(
                                kind = InlineObjectPreferredStretchKind.Relation,
                                naturalWidth = naturalRelationGap,
                                targetWidth = targetGap,
                            ),
                            preventsLineBreak = true,
                        ),
                    ),
                    InlineObjectSpan(
                        range = TextRange(2, 3),
                        advance = formulaBodyWidth,
                        ascent = 12f,
                        descent = 4f,
                    ),
                ),
            ),
        )

        assertTrue(result.lines.size > 1)
        val formula = result.positionedClusters().filter { it.range.end <= 3 }
        assertEquals(3, formula.size)
        assertEquals(0, formula.map { it.lineIndex }.distinct().single())
        val beforeEquals = formula[1].drawX - (formula[0].drawX + formulaBodyWidth)
        val afterEquals = formula[2].drawX - (formula[1].drawX + formulaBodyWidth)
        assertEquals(beforeEquals, afterEquals, 0.001f)
        assertTrue(beforeEquals >= targetGap)

        val relationAllocations = result.debug.justificationDecisions.first().allocations
            .filter { it.kind == GlueKind.InlineObjectRelation.name }
        assertEquals(2, relationAllocations.size)
        assertEquals(relationAllocations[0].delta, relationAllocations[1].delta, 0.001f)
    }

    @Test
    fun formulaBreakKeepsBaselineOperatorOnPreviousLine() {
        val text = "a+b"
        val inlineObjects = listOf(
            InlineObjectSpan(TextRange(0, 1), advance = 12f, ascent = 12f, descent = 4f),
            InlineObjectSpan(
                TextRange(1, 2),
                advance = 12f,
                ascent = 12f,
                descent = 4f,
                leadingBoundary = InlineObjectBoundaryAdjustment(preventsLineBreak = true),
                trailingBoundary = InlineObjectBoundaryAdjustment(
                    shrinkCapacity = 4f,
                    lineEndDiscardableAdvance = 4f,
                ),
            ),
            InlineObjectSpan(TextRange(2, 3), advance = 12f, ascent = 12f, descent = 4f),
        )
        for (breaker in listOf(GreedyLineBreaker(), LookaheadLineBreaker(), ParagraphDpLineBreaker())) {
            val result = fixedBasicKinsokuEngine(breaker).layout(
                LayoutInput(
                    content = TiqianTextContent(text),
                    textStyle = TextStyle(fontSize = 16f),
                    constraints = LayoutConstraints(maxWidth = 24f),
                    paragraphStyle = style,
                    inlineObjects = inlineObjects,
                ),
            )

            val lineTexts = result.lines.map { text.substring(it.range.start, it.range.end) }
            assertTrue(lineTexts.size > 1, "breaker=${breaker.strategyName} lines=$lineTexts")
            assertTrue(
                lineTexts.drop(1).none { it.startsWith('+') },
                "the adjustment-only boundary before the operator must stay closed: " +
                    "breaker=${breaker.strategyName} lines=$lineTexts",
            )
            assertTrue(lineTexts.dropLast(1).any { it.endsWith('+') }, "breaker=${breaker.strategyName} lines=$lineTexts")
            assertEquals(
                8f,
                result.clusters.single { it.range == TextRange(1, 2) }.advance,
                0.001f,
                "the operator glyph stays while its post-operator line-end glue disappears",
            )
            assertEquals(
                20f,
                result.lines.first().visualWidth,
                0.001f,
                "the discarded glue must not remain in the previous line's width",
            )
            assertEquals(
                0f,
                result.positionedClusters().single { it.range == TextRange(2, 3) }.drawX,
                0.001f,
                "the following operand must start without inherited formula glue",
            )
            assertTrue(
                result.debug.lineEdgeTrimDecisions.any {
                    it.clusterRange == TextRange(1, 2) &&
                        it.reason == "InlineObjectLineEndDiscardableGlue" &&
                        it.naturalGlue == 4f
                },
                "breaker=${breaker.strategyName} trims=${result.debug.lineEdgeTrimDecisions}",
            )
            assertTrue(
                result.debug.inlineObjectDecisions.single { it.range == TextRange(1, 2) }
                    .leadingPreventsLineBreak,
            )
            assertEquals(
                4f,
                result.debug.inlineObjectDecisions.single { it.range == TextRange(1, 2) }
                    .trailingLineEndDiscardableAdvance,
            )

            val unbroken = fixedBasicKinsokuEngine(breaker).layout(
                LayoutInput(
                    content = TiqianTextContent(text),
                    textStyle = TextStyle(fontSize = 16f),
                    constraints = LayoutConstraints(maxWidth = 60f),
                    paragraphStyle = style,
                    inlineObjects = inlineObjects,
                ),
            )
            assertEquals(1, unbroken.lines.size)
            assertEquals(12f, unbroken.clusters.single { it.range == TextRange(1, 2) }.advance, 0.001f)
            assertTrue(
                unbroken.debug.lineEdgeTrimDecisions.none {
                    it.reason == "InlineObjectLineEndDiscardableGlue"
                },
            )
        }
    }

    private fun fixedBasicKinsokuEngine(
        lineBreaker: LineBreaker = GreedyLineBreaker(),
    ) = TiqianParagraphLayoutEngine(
        lineBreaker = lineBreaker,
        clreqProfileResolver = ClreqProfileResolver {
            ClreqProfile.MainlandHorizontal.copy(
                kinsokuMode = KinsokuMode.Fixed(
                    level = KinsokuLevel.Basic,
                    hanging = HangingPunctuationStyle.Disabled,
                ),
            )
        },
    )
}
