package org.tiqian.layout

import org.tiqian.core.Ic

import org.tiqian.clreq.CjkPunctuationGlyphPolicy
import org.tiqian.clreq.ClreqProfile
import org.tiqian.clreq.ClreqProfileResolver
import org.tiqian.clreq.LineAdjustmentStrategy
import org.tiqian.core.Cluster
import org.tiqian.core.Glyph
import org.tiqian.core.GlyphRun
import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LayoutResult
import org.tiqian.core.LineBreakPolicy
import org.tiqian.core.LineBreakSpan
import org.tiqian.core.LineLengthGrid
import org.tiqian.core.LineEndReason
import org.tiqian.linebreak.Hyphenator
import org.tiqian.linebreak.NoHyphenator
import org.tiqian.core.LayoutInput
import org.tiqian.core.ParagraphStyle
import org.tiqian.core.Rect
import org.tiqian.core.TextRange
import org.tiqian.core.TextSpan
import org.tiqian.core.TextStyle
import org.tiqian.core.TiqianTextContent
import org.tiqian.core.positionedClusters
import org.tiqian.font.FontRole
import org.tiqian.shaping.ExplainableStubTextShaper
import org.tiqian.shaping.ShapingInput
import org.tiqian.shaping.ShapingResult
import org.tiqian.shaping.TextShaper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PunctuationGeometryEngineTest {
    @Test
    fun buildsTwoEmPunctuationAtomForRecommendedDashCodepoint() {
        val atom = PunctuationAtomBuilder().build("⸺", index = 0, em = 16f)

        requireNotNull(atom)
        assertEquals(32f, atom.advance)
        assertEquals(32f, atom.bodyWidth)
    }

    @Test
    fun inkBoundsDetermineCompressionAmountAndSides() {
        val atom = PunctuationAtomBuilder().build(
            char = '，',
            range = org.tiqian.core.TextRange(0, 1),
            em = 16f,
            inkInput = PunctuationInkInput(
                advance = 16f,
                inkBounds = Rect(left = 9f, top = -2f, right = 11f, bottom = 2f),
            ),
        )

        requireNotNull(atom)
        assertEquals(16f, atom.advance)
        assertEquals(8f, atom.bodyWidth)
        assertEquals(8f, atom.inkContainmentBodyFloor)
        assertTrue(!atom.inkContainmentApplied)
        // The ink fits the centred half-em frame, so both sides lose 4px.
        assertEquals(4f, atom.leadingGlue.natural)
        assertEquals(4f, atom.trailingGlue.natural)
        assertEquals("InkBoundsFittedBodyCompression", atom.geometrySource)
        // Ink fields are retained as diagnostics
        assertEquals(2f, atom.inkWidth)
        assertEquals(10f, atom.inkCenter)
    }

    @Test
    fun recordsInkCalibratedPunctuationGeometryInLayoutDebug() {
        val inkBounds = Rect(left = 9f, top = -2f, right = 11f, bottom = 2f)
        val engine = ExplainableStubParagraphLayoutEngine(
            textShaper = object : TextShaper {
                override fun shape(input: ShapingInput): ShapingResult =
                    ShapingResult(
                        clusters = listOf(
                            Cluster(
                                range = input.range,
                                text = input.text.substring(input.range.start, input.range.end),
                                displayText = input.displayText,
                                fontKey = input.fontDecision.candidate.key,
                                advance = 16f,
                            ),
                        ),
                        glyphRuns = listOf(
                            GlyphRun(
                                range = input.range,
                                fontKey = input.fontDecision.candidate.key,
                                glyphs = listOf(
                                    Glyph(
                                        id = 7u,
                                        clusterRange = input.range,
                                        advance = 16f,
                                        bounds = inkBounds,
                                    ),
                                ),
                                advance = 16f,
                            ),
                        ),
                    )
            },
        )

        val result = engine.layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("。"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        val punctuation = result.debug.punctuationDecisions.single()
        assertEquals(inkBounds, punctuation.inkBounds)
        assertEquals(8f, punctuation.bodyWidth)
        assertEquals(8f, punctuation.inkContainmentBodyFloor)
        assertTrue(!punctuation.inkContainmentApplied)
        assertEquals(4f, punctuation.leadingGlueNatural)
        assertEquals(4f, punctuation.trailingGlueNatural)
        assertEquals("InkBoundsFittedBodyCompression", punctuation.geometrySource)

        val geometry = result.debug.geometryDecisions.single()
        assertEquals("InkBoundsFittedBodyCompression", geometry.reason)
        assertEquals(8f, geometry.bodyWidth)
        assertEquals(4f, geometry.leadingGlueNatural)
        assertEquals(4f, geometry.trailingGlueNatural)
        assertEquals(4f, geometry.leadingGlueConsumed)
        assertEquals(4f, geometry.trailingGlueConsumed)
        assertEquals(8f, geometry.resolvedAdvance)

        val edge = result.debug.lineEdgeTrimDecisions.single()
        assertEquals("both", edge.side)
        assertEquals(8f, edge.trimAmount)
        assertEquals("LineEndCenteredPunctuationPairedCompression", edge.reason)
    }

    @Test
    fun pushInKeepsFontCenteredPunctuationCompressionPaired() {
        val engine = ExplainableStubParagraphLayoutEngine(
            textShaper = object : TextShaper {
                override fun shape(input: ShapingInput): ShapingResult {
                    val clusters = input.displayText.mapIndexed { index, character ->
                        val range = TextRange(input.range.start + index, input.range.start + index + 1)
                        Cluster(
                            range = range,
                            text = input.text.substring(range.start, range.end),
                            displayText = character.toString(),
                            fontKey = input.fontDecision.candidate.key,
                            advance = 16f,
                        )
                    }
                    return ShapingResult(
                        clusters = clusters,
                        glyphRuns = listOf(
                            GlyphRun(
                                range = input.range,
                                fontKey = input.fontDecision.candidate.key,
                                glyphs = clusters.mapIndexed { index, cluster ->
                                    Glyph(
                                        id = (index + 1).toUInt(),
                                        clusterRange = cluster.range,
                                        advance = 16f,
                                        bounds = if (cluster.displayText == "，") {
                                            Rect(left = 5f, top = -2f, right = 11f, bottom = 2f)
                                        } else {
                                            Rect(left = 0f, top = -12f, right = 16f, bottom = 4f)
                                        },
                                    )
                                },
                                advance = clusters.sumOf { it.advance.toDouble() }.toFloat(),
                            ),
                        ),
                    )
                }
            },
        )

        val result = engine.layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(
                    firstLineIndent = Ic(0f),
                    lineLengthGrid = LineLengthGrid(enabled = false),
                ),
                content = TiqianTextContent("中文中文，中文"),
                constraints = LayoutConstraints(maxWidth = 72f),
            ),
        )

        val comma = result.debug.geometryDecisions.single { it.sourceText == "，" }
        assertEquals(4f, comma.leadingGlueConsumed)
        assertEquals(4f, comma.trailingGlueConsumed)
        assertEquals(8f, comma.resolvedAdvance)
        val pushIn = result.debug.lineDecisions
            .mapNotNull { it.repairDecision }
            .single { it.kind == "PushIn" }
        assertEquals(8f, pushIn.shrink)
        assertEquals(TextRange(4, 5), pushIn.pushInAllocations.single().clusterRange)
    }

    @Test
    fun recordsPunctuationAtomsInLayoutDebug() {
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("你好，世界。——"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        val comma = result.debug.punctuationDecisions.single { it.char == '，' }
        assertEquals(2, comma.range.start)
        assertEquals(3, comma.range.end)
        assertEquals("PauseOrStop", comma.punctuationClass)
        assertEquals(16f, comma.advance)
        assertEquals(8f, comma.bodyWidth)
        // PauseOrStop: all glue on trailing side
        assertEquals(0f, comma.leadingGlueNatural)
        assertEquals(8f, comma.trailingGlueNatural)
        assertEquals("Leading", comma.anchor)

        val stop = result.debug.punctuationDecisions.single { it.char == '。' }
        assertEquals(5, stop.range.start)
        assertEquals(6, stop.range.end)

        val dash = result.debug.punctuationDecisions.single { it.char == '⸺' }
        assertEquals(6, dash.range.start)
        assertEquals(8, dash.range.end)
        assertEquals("Dash", dash.punctuationClass)
        assertEquals(32f, dash.advance)

        assertEquals(3, result.debug.punctuationDecisions.size)
    }

    @Test
    fun lineStartLenticularBracketConsumesOpeningGlue() {
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("【引用结束】"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        val opening = result.debug.punctuationDecisions.single { it.char == '【' }
        assertEquals("Opening", opening.punctuationClass)
        assertEquals(8f, opening.leadingGlueNatural)

        val geometry = result.debug.geometryDecisions.single { it.sourceText == "【" }
        assertEquals(8f, geometry.leadingGlueNatural)
        assertEquals(8f, geometry.leadingGlueConsumed)
        assertEquals(8f, geometry.resolvedAdvance)

        val positioned = result.positionedClusters().first()
        assertEquals(0f, positioned.left)
        assertEquals(-8f, positioned.drawX)
    }

    @Test
    fun traditionalProfileCentresPauseStopGlueOnBothSides() {
        // Per CLREQ 3.1.3, Traditional Chinese places 。 ， at the centre of
        // the em box, so 。's glue is split symmetrically: 4 leading + 4
        // trailing, anchor = Center. This is the regional behaviour the
        // hardcoded Mainland-style assumption used to miss.
        val engine = ExplainableStubParagraphLayoutEngine(
            clreqProfileResolver = ClreqProfileResolver { ClreqProfile.TaiwanHorizontal },
        )

        val result = engine.layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("你好。"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        val stop = result.debug.punctuationDecisions.single { it.char == '。' }
        assertEquals("PauseOrStop", stop.punctuationClass)
        assertEquals(8f, stop.bodyWidth)
        assertEquals(4f, stop.leadingGlueNatural)
        assertEquals(4f, stop.trailingGlueNatural)
        assertEquals("Center", stop.anchor)

        val geometry = result.debug.geometryDecisions.single { it.sourceText == "。" }
        assertEquals(4f, geometry.leadingGlueConsumed)
        assertEquals(4f, geometry.trailingGlueConsumed)
        assertEquals(8f, geometry.resolvedAdvance)
        val edge = result.debug.lineEdgeTrimDecisions.single()
        assertEquals("both", edge.side)
        assertEquals("LineEndCenteredPunctuationPairedCompression", edge.reason)
    }

    @Test
    fun appliesAdjacentPunctuationCompressionToDrawableGeometry() {
        // 」。 is a Closing+PauseOrStop pair — a standard CLREQ collapse.
        // (，。 was used here before, but consecutive PauseOrStop pairs are
        // now exempt from compression per ConsecutivePauseOrStopKeepsFullWidth.)
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("你好」。"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        val line = result.lines.single()
        val stop = result.clusters.first { it.text == "。" }

        // Class-based glue: 」 trailing=8, 。 trailing=8.
        // Spacing compression: inner glue = 」.trailing(8) + 。.leading(0) = 8
        //   → adjusted = max(0, 8 - 0.5em) = 0 (CLREQ: closing+pause-stop bodies touch),
        //   reduction=8, target=」(has trailing glue).
        // Line-end trim: 。 trailing(8) fully consumed → 。 advance = 8.
        // 」: 16 - 8(spacing) = 8 (body only). 。: 16 - 8(edge trim) = 8.
        // Total: 16 + 16 + 8 + 8 = 48.
        assertEquals(64f, line.naturalWidth)
        assertEquals(48f, line.adjustedWidth)
        assertEquals(48f, line.visualWidth)
        assertEquals(48f, result.size.width)
        assertEquals(8f, stop.advance)
        assertEquals(48f, result.clusters.sumOf { it.advance.toDouble() }.toFloat())
        assertEquals(48f, result.glyphRuns.sumOf { it.advance.toDouble() }.toFloat())
        assertEquals(8f, result.debug.spacingDecisions.sumOf { it.reduction.toDouble() }.toFloat())
        val edgeTrim = result.debug.lineEdgeTrimDecisions.single()
        assertEquals("trailing", edgeTrim.side)
        assertEquals("LineEndHalfWidthPunctuation", edgeTrim.reason)
        assertEquals(8f, edgeTrim.trimAmount)
        assertEquals(3, edgeTrim.clusterRange.start)
        assertEquals(4, edgeTrim.clusterRange.end)

        val stopGeometry = result.debug.geometryDecisions.single { it.sourceText == "。" }
        assertEquals("PunctuationGeometryLedger", stopGeometry.source)
        assertEquals("ProfileGlueFallbackWithoutFontGeometry", stopGeometry.reason)
        assertEquals(16f, stopGeometry.baseAdvance)
        assertEquals(8f, stopGeometry.bodyWidth)
        // PauseOrStop: all glue on trailing side, fully consumed by edge trim
        assertEquals(0f, stopGeometry.leadingGlueNatural)
        assertEquals(0f, stopGeometry.leadingGlueConsumed)
        assertEquals(8f, stopGeometry.trailingGlueNatural)
        assertEquals(8f, stopGeometry.trailingGlueConsumed)
        assertEquals(0f, stopGeometry.justificationDelta)
        assertEquals(8f, stopGeometry.resolvedAdvance)

        val spacing = result.debug.spacingDecisions.single()
        assertEquals(2, spacing.range.start)
        assertEquals(4, spacing.range.end)
        assertEquals('」', spacing.leftChar)
        assertEquals('。', spacing.rightChar)
        assertEquals(8f, spacing.naturalInnerGlue)
        assertEquals(0f, spacing.adjustedInnerGlue)
        assertEquals(8f, spacing.reduction)
        // Reduction targets 」 (which has the trailing glue)
        assertEquals(2, spacing.reductionTargetRange.start)
        assertEquals(3, spacing.reductionTargetRange.end)
        assertEquals("collapse-adjacent-punctuation-inner-glue", spacing.reason)
    }

    @Test
    fun compressesAdjacentCjkSingleQuoteCommaSequence() {
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("’，‘"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        assertTrue(
            result.debug.fontDecisions.all { it.role == FontRole.CjkPunctuation.name },
            result.debug.fontDecisions.toString(),
        )
        assertEquals(3, result.debug.punctuationDecisions.size)
        assertEquals(2, result.debug.spacingDecisions.size)
        assertTrue(
            result.debug.spacingDecisions.all {
                it.reason == "collapse-adjacent-punctuation-inner-glue" &&
                    it.reduction == 8f
            },
            result.debug.spacingDecisions.toString(),
        )
        assertEquals(32f, result.lines.single().visualWidth)
        assertEquals(32f, result.size.width)
        assertEquals(
            listOf(0f, 8f, 16f),
            result.positionedClusters().map { it.drawX },
        )
    }

    @Test
    fun compressesCjkClosingBeforeAsciiPointMarkWithoutReclassifyingAscii() {
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("中」,next"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        val closing = result.clusters.single { it.text == "」" }
        val commaDecision = result.debug.fontDecisions.single { it.range.start == 2 }
        val spacing = result.debug.spacingDecisions.single {
            it.reason == "collapse-cjk-closing-before-ascii-point-mark"
        }
        assertEquals(FontRole.LatinText.name, commaDecision.role)
        assertEquals(8f, closing.advance)
        assertEquals('」', spacing.leftChar)
        assertEquals(',', spacing.rightChar)
        assertEquals(8f, spacing.reduction)
    }

    @Test
    fun haltAdvanceFromShaperDrivesPunctuationBodyEndToEnd() {
        // A shaper that reports halt=7 for 。 — the engine's punctuation
        // decision must carry the font-derived body and the FontHalt
        // geometry source, and the ledger must keep resolved >= body.
        val engine = ExplainableStubParagraphLayoutEngine(
            textShaper = object : TextShaper {
                val delegate = ExplainableStubTextShaper()
                override fun shape(input: ShapingInput): ShapingResult {
                    val result = delegate.shape(input)
                    if (input.displayText != "。") return result
                    return result.copy(
                        glyphRuns = result.glyphRuns.map { run ->
                            run.copy(glyphs = run.glyphs.map { it.copy(haltAdvance = 7f, haltPlacementX = 0f) })
                        },
                    )
                }
            },
        )

        val result = engine.layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("中文。"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        val stop = result.debug.punctuationDecisions.single()
        assertEquals(7f, stop.haltAdvance)
        assertEquals(7f, stop.bodyWidth)
        assertEquals("FontHaltFittedBodyCompression", stop.geometrySource)
        // Trailing glue grows to advance - haltBody = 9; at line end it is
        // trimmed away leaving exactly the font body.
        assertEquals(9f, stop.trailingGlueNatural)
        val stopCluster = result.clusters.single { it.text == "。" }
        assertEquals(7f, stopCluster.advance)
    }

    @Test
    fun looseLineEndStyleKeepsFullWidthPunctuation() {
        // AdjustmentStylePolicy.lineEndPunctuation = AllowFullWidth (宽松风格):
        // the unconditional line-end half-width trim is skipped; the 字身
        // grid stays intact at line end.
        val loose = ExplainableStubParagraphLayoutEngine(
            clreqProfileResolver = ClreqProfileResolver {
                ClreqProfile.MainlandHorizontal.copy(
                    adjustment = org.tiqian.clreq.AdjustmentStylePolicy(
                        lineEndPunctuation = org.tiqian.clreq.LineEndPunctuationStyle.AllowFullWidth,
                    ),
                )
            },
        )
        val result = loose.layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("中文中文。"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )
        val stop = result.clusters.single { it.text == "。" }
        assertEquals(16f, stop.advance)
        assertTrue(result.debug.lineEdgeTrimDecisions.none { it.reason == "LineEndHalfWidthPunctuation" })

        // Default strict style trims to half width.
        val strict = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("中文中文。"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )
        assertEquals(8f, strict.clusters.single { it.text == "。" }.advance)
    }

    @Test
    fun inlineStopCompressionKnobLimitsPushInCapacity() {
        // "中中中。中中。" maxWidth=96: line0 = 6 clusters (96), offender 。
        // (idx 6) overflows by 16. Capacities: offender 。 tier-1 (8) +
        // mid-line 。 idx3 tier-4 (8) = 16 → PushIn succeeds by default.
        val text = "中中中。中中。"
        val default = fixedBasicEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent(text),
                constraints = LayoutConstraints(maxWidth = 96f),
            ),
        )
        assertEquals(1, default.lines.size)
        assertTrue(default.debug.lineDecisions.single().repairDecision?.kind == "PushIn")

        // Knob off: mid-line 。 keeps full width (its glue is lineEndOnly);
        // capacity drops to the offender's own 8 < 16 → PushIn rejected,
        // CarryPrevious instead.
        val noInline = fixedBasicEngine(
            adjustment = org.tiqian.clreq.AdjustmentStylePolicy(
                allowInlineStopCompression = false,
            ),
        ).layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent(text),
                constraints = LayoutConstraints(maxWidth = 96f),
            ),
        )
        assertTrue(noInline.lines.size > 1)
        val pushInCandidate = noInline.debug.lineDecisions
            .flatMap { it.repairCandidates }
            .first { it.kind == "PushIn" }
        assertEquals("insufficient-capacity", pushInCandidate.rejectionReason)
        assertEquals(8f, pushInCandidate.availableCapacity)
    }

    @Test
    fun sinoWesternGapKnobDisablesStretchAndShrink() {
        // allowSinoWesternGapAdjustment=false: the gap stays fixed — no
        // CjkLatinSpace stretch under justify.
        val fixedGap = ExplainableStubParagraphLayoutEngine(
            clreqProfileResolver = ClreqProfileResolver {
                ClreqProfile.MainlandHorizontal.copy(
                    adjustment = org.tiqian.clreq.AdjustmentStylePolicy(
                        allowSinoWesternGapAdjustment = false,
                    ),
                )
            },
        ).layout(
            LayoutInput(
                content = TiqianTextContent("中文Hello文中文中文中文中"),
                constraints = LayoutConstraints(maxWidth = 160f),
                paragraphStyle = org.tiqian.core.ParagraphStyle(
                    firstLineIndent = Ic(0f),
                ),
            ),
        )
        assertTrue(fixedGap.debug.justificationDecisions.isNotEmpty())
        assertTrue(
            fixedGap.debug.justificationDecisions
                .flatMap { it.allocations }
                .none { it.kind == "CjkLatinSpace" },
        )
    }

    @Test
    fun shortHyphenConnectorIsHalfWidthWavyTildeFullWidth() {
        // CLREQ 5.1.6: 短横线（–, U+2013）占半个字位置；浪纹线（～, U+FF5E）
        // 占一字。Both classify as Connector but differ in width.
        val full = org.tiqian.clreq.PunctuationWidthPolicy()
        assertEquals(8f, advanceOfMidLinePunct("中–中文", "–", full))
        assertEquals(16f, advanceOfMidLinePunct("中～中文", "～", full))
    }

    @Test
    fun kaimingStyleHalvesInteriorPunctuationButNotSentenceEnd() {
        val full = org.tiqian.clreq.PunctuationWidthPolicy()
        val kaiming = org.tiqian.clreq.PunctuationWidthPolicy(
            interior = org.tiqian.clreq.InteriorPunctuationStyle.Kaiming,
        )
        // 句中点号 逗号：全身式 1em → 开明式半字 0.5em.
        assertEquals(16f, advanceOfMidLinePunct("中，中文", "，", full))
        assertEquals(8f, advanceOfMidLinePunct("中，中文", "，", kaiming))
        // 夹注/括号：开明式半字.
        assertEquals(8f, advanceOfMidLinePunct("中（中）文", "（", kaiming))
        // 句末点号 句号：开明式仍占一字（行中）.
        assertEquals(16f, advanceOfMidLinePunct("中。中文", "。", kaiming))
    }

    @Test
    fun gbFixedSeparatorsAreHalfWidthAndUnadjustable() {
        val default = org.tiqian.clreq.PunctuationWidthPolicy()
        val gb = org.tiqian.clreq.PunctuationWidthPolicy(gbFixedSeparators = true)
        // 间隔号 ·：默认 1em → GB 固定半宽 0.5em.
        assertEquals(16f, advanceOfMidLinePunct("中·中文", "·", default))
        assertEquals(8f, advanceOfMidLinePunct("中·中文", "·", gb))

        // Fixed = its full measured compression budget is consumed before breaking;
        // no remaining capacity can be borrowed by PushIn.
        val result = ExplainableStubParagraphLayoutEngine(
            clreqProfileResolver = ClreqProfileResolver {
                ClreqProfile.MainlandHorizontal.copy(
                    punctuationWidth = org.tiqian.clreq.PunctuationWidthPolicy(gbFixedSeparators = true),
                )
            },
        ).layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("中文·中文"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )
        val mid = result.debug.geometryDecisions.single { it.sourceText == "·" }
        assertEquals(mid.trailingGlueNatural, mid.trailingGlueConsumed)
        assertEquals(mid.leadingGlueNatural, mid.leadingGlueConsumed)
        assertEquals(8f, mid.resolvedAdvance)
    }

    @Test
    fun pushInDrainsBracketOuterGlueBeforeInlineComma() {
        // CLREQ 挤压七档（ADR 0020 amendment）：tier 4 夹注符号外侧
        // （（ 前侧、） 后侧）先于 tier 5 行内逗号被消耗。
        // 中（文）中，中文中。 @144：line0 = 前 9 cluster (144)，。 PushIn
        // overflow 16 = tier1 。(8) + tier4 （/）各 4 —— ， 保持全宽。
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("中（文）中，中文中。"),
                constraints = LayoutConstraints(maxWidth = 144f),
            ),
        )

        assertEquals(1, result.lines.size)
        fun geom(text: String) = result.debug.geometryDecisions.single { it.sourceText == text }
        assertEquals(8f, geom("。").trailingGlueConsumed)
        assertEquals(4f, geom("（").leadingGlueConsumed)
        assertEquals(4f, geom("）").trailingGlueConsumed)
        assertEquals(0f, geom("，").trailingGlueConsumed)
    }

    @Test
    fun sinoWesternGapShrinkFloorsAtEighthEm() {
        // CLREQ 挤压⑥：中西间距最小挤为 1/8 汉字宽，不是 0。用 Clreq 预设
        // (base 1/4)：两个 gap (advance 4) 各只有 2px 容量：。 推入需要 16，
        // 可用 8+2+2=12 → PushIn 拒绝，CarryPrevious 兜底。（floor 为 0 的旧
        // 行为会接受。Default 预设 base=floor=1/8，间距本就不可压。）
        val result = fixedBasicEngine(autoSpace = org.tiqian.clreq.AutoSpacePolicy.Clreq).layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(
                    firstLineIndent = Ic(0f),
                    lineLengthGrid = LineLengthGrid(enabled = false),
                ),
                content = TiqianTextContent("中文 AB 中。"),
                constraints = LayoutConstraints(maxWidth = 88f),
            ),
        )

        assertEquals(2, result.lines.size)
        val decision = result.debug.lineDecisions[1]
        assertEquals("CarryPrevious", decision.repairDecision?.kind)
        val pushIn = decision.repairCandidates.first { it.kind == "PushIn" }
        assertEquals(false, pushIn.accepted)
        assertEquals("insufficient-capacity", pushIn.rejectionReason)
        assertEquals(12f, pushIn.availableCapacity)
    }

    @Test
    fun pushInConsumesWordSpaceBeforeMidLinePunctGlue() {
        // Breaker-level tier ordering with mixed channels: line
        // [A][ ][B][、][中] + offender 。. Tiers in the merged line:
        // offender 。 trailing (promoted tier 1, 8) → word space (tier 2,
        // capacity 12) → 、 trailing (tier 6, 8). Overflow 16 must consume
        // tier 1 fully then 8 of tier 2 — 、 stays untouched.
        val clusters = listOf(
            Cluster(range = org.tiqian.core.TextRange(0, 1), text = "A", fontKey = "t", advance = 32f),
            Cluster(range = org.tiqian.core.TextRange(1, 2), text = " ", fontKey = "t", advance = 16f),
            Cluster(range = org.tiqian.core.TextRange(2, 3), text = "B", fontKey = "t", advance = 32f),
            Cluster(range = org.tiqian.core.TextRange(3, 4), text = "、", fontKey = "t", advance = 16f),
            Cluster(range = org.tiqian.core.TextRange(4, 5), text = "中", fontKey = "t", advance = 16f),
            Cluster(range = org.tiqian.core.TextRange(5, 6), text = "。", fontKey = "t", advance = 16f),
        )
        val solution = GreedyLineBreaker().breakLines(
            naturalClusters = clusters,
            adjustedClusters = clusters,
            maxWidth = 112f,
            shrinkOpportunities = listOf(
                ShrinkOpportunity(1, tier = 2, capacity = 12f, channel = ShrinkChannel.RawAdvance),
                ShrinkOpportunity(3, tier = 6, capacity = 8f, channel = ShrinkChannel.TrailingGlue),
                ShrinkOpportunity(5, tier = 4, capacity = 8f, channel = ShrinkChannel.TrailingGlue),
            ),
        )

        assertEquals(1, solution.lines.size)
        val repair = solution.lines.single().repair
        assertTrue(repair is RepairOption.PushIn)
        assertEquals(16f, repair.totalShrink)
        // Tier 1 (offender 。) → tier 2 (word space); tier-6 、 untouched.
        assertEquals(listOf(5, 1), repair.allocations.map { it.clusterIndex })
        assertEquals(8f, repair.allocations[0].shrink)
        assertEquals(8f, repair.allocations[1].shrink)
        assertEquals(ShrinkChannel.RawAdvance, repair.allocations[1].channel)
        assertTrue(repair.allocations.none { it.clusterIndex == 3 })
    }
}

private fun advanceOfMidLinePunct(
    text: String,
    punct: String,
    width: org.tiqian.clreq.PunctuationWidthPolicy,
): Float {
    val result = ExplainableStubParagraphLayoutEngine(
        clreqProfileResolver = ClreqProfileResolver {
            ClreqProfile.MainlandHorizontal.copy(punctuationWidth = width)
        },
    ).layout(
        LayoutInput(
            paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
            content = TiqianTextContent(text),
            constraints = LayoutConstraints(maxWidth = 320f),
        ),
    )
    return result.clusters.first { it.text == punct }.advance
}
