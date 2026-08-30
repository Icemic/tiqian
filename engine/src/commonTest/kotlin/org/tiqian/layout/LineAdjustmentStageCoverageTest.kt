package org.tiqian.layout

import org.tiqian.core.Ic
import org.tiqian.core.INLINE_OBJECT_REPLACEMENT_CHAR
import org.tiqian.core.InlineAttachment
import org.tiqian.core.InlineObjectBoundaryAdjustment
import org.tiqian.core.InlineObjectSpan
import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LayoutInput
import org.tiqian.core.LayoutResult
import org.tiqian.core.LineBreakPolicy
import org.tiqian.core.LineBreakSpan
import org.tiqian.core.LineLengthGrid
import org.tiqian.core.ParagraphStyle
import org.tiqian.core.TextRange
import org.tiqian.core.TextSpan
import org.tiqian.core.TextStyle
import org.tiqian.core.TiqianTextContent
import org.tiqian.linebreak.EnglishHyphenation
import org.tiqian.shaping.ExplainableStubTextShaper
import org.tiqian.shaping.ShapingInput
import org.tiqian.shaping.ShapingResult
import org.tiqian.shaping.TextShaper
import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.assertTrue
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

/**
 * Coverage for LineAdjustmentStage.kt: the engine-level arms the direct
 * unit suites cannot reach — paragraph height fallbacks, empty and
 * mandatory-break lines skipping justification, hyphen squeeze across
 * every shrink channel, line-edge trims (formula discardable glue,
 * attached virtual boundary, zero-advance spaces), the Emergency
 * preferred tracking span, and the technical body-stretch rejection
 * replay.
 */
class LineAdjustmentStageCoverageTest {
    private val testTrace = TestTraceRecorder("LineAdjustmentStageCoverageTest")

    private val noIndent = ParagraphStyle(
        firstLineIndent = Ic(0.0f),
        lineLengthGrid = LineLengthGrid(enabled = false),
    )

    private fun layout(
        text: String,
        maxWidth: Float,
        spans: List<TextSpan> = emptyList(),
        inlineObjects: List<InlineObjectSpan> = emptyList(),
        lineBreakSpans: List<LineBreakSpan> = emptyList(),
        hyphenate: Boolean = false,
        textShaper: TextShaper? = null,
    ): LayoutResult {
        val engine = when {
            textShaper != null -> ExplainableStubParagraphLayoutEngine(textShaper = textShaper)
            hyphenate -> ExplainableStubParagraphLayoutEngine(hyphenator = EnglishHyphenation.enUs)
            else -> ExplainableStubParagraphLayoutEngine()
        }
        return engine.layout(
            LayoutInput(
                paragraphStyle = noIndent,
                content = TiqianTextContent(
                    text = text,
                    spans = spans,
                    lineBreakSpans = lineBreakSpans,
                ),
                inlineObjects = inlineObjects,
                constraints = LayoutConstraints(maxWidth = maxWidth),
            ),
        )
    }

    @Test
    fun emptyTextYieldsZeroHeightWithoutLines() {
        testTrace.section("emptyTextYieldsZeroHeightWithoutLines")
        val result = layout("", maxWidth = 100.0f)
        assertTrue(result.lines.isEmpty(), result.lines.toString())
        assertEquals(0.0f, result.size.height)
        assertEquals(0.0f, result.size.width)
    }

    @Test
    fun loneMandatoryBreakEmitsTwoZeroWidthLines() {
        testTrace.section("loneMandatoryBreakEmitsTwoZeroWidthLines")
        val result = layout("\n", maxWidth = 100.0f)
        assertEquals(2, result.lines.size, result.lines.toString())
        assertTrue(result.lines.all { it.naturalWidth == 0.0f && it.visualWidth == 0.0f })
        assertTrue(result.size.height > 0.0f, result.size.height.toString())
    }

    @Test
    fun mandatoryBreakMiddleLineSkipsItsJustificationPlan() {
        testTrace.section("mandatoryBreakMiddleLineSkipsItsJustificationPlan")
        val result = layout("中文中文\n中文中文", maxWidth = 80.0f)
        assertEquals(2, result.lines.size, result.lines.toString())
        assertEquals(0..4, result.lines[0].clusterRange)
        assertEquals(5..8, result.lines[1].clusterRange)
        assertTrue(
            result.lines.all { it.adjustedWidth == it.naturalWidth },
            result.lines.map { "${it.clusterRange}:${it.naturalWidth}/${it.adjustedWidth}" }.toString(),
        )
        assertTrue(result.debug.justificationDecisions.isEmpty())
    }

    @Test
    fun blankMiddleLineSkipsEveryEdgePass() {
        testTrace.section("blankMiddleLineSkipsEveryEdgePass")
        // The second mandatory break is a line of its own carrying only the
        // \n cluster: zero natural width and no justification plan.
        val result = layout("中文\n\n中文", maxWidth = 80.0f)
        assertEquals(3, result.lines.size, result.lines.toString())
        assertEquals(3..3, result.lines[1].clusterRange, result.lines.toString())
        assertEquals(0.0f, result.lines[1].naturalWidth)
        assertTrue(
            result.debug.justificationDecisions.none { it.lineRange == result.lines[1].range },
            result.debug.justificationDecisions.toString(),
        )
    }

    @Test
    fun trailingMandatoryBreakEmitsTerminalEmptyLineWithoutHyphen() {
        testTrace.section("trailingMandatoryBreakEmitsTerminalEmptyLineWithoutHyphen")
        // The terminal empty line follows a MandatoryBreak line; the hyphen
        // lookup for that predecessor sees the empty next line and yields
        // no reserved hyphen advance.
        val result = layout("中文aa internationalization\n", maxWidth = 118.0f, hyphenate = true)
        val last = result.lines.last()
        assertTrue(last.clusterRange.isEmpty(), result.lines.toString())
        assertEquals(0.0f, last.hyphenAdvance)
        val before = result.lines[result.lines.lastIndex - 1]
        assertEquals(0.0f, before.hyphenAdvance, result.lines.toString())
        assertTrue(before.hyphenGlyphs.isEmpty())
    }

    @Test
    fun hyphenSqueezeConsumesTheWordSpaceRawAdvanceChannel() {
        testTrace.section("hyphenSqueezeConsumesTheWordSpaceRawAdvanceChannel")
        // Line 0 breaks inside "internationalization" after "in" with the
        // word space in the line: the reserved hyphen (16) overflows by 4
        // and the space's raw-advance capacity (8 - 0.25em floor = 4)
        // absorbs exactly that.
        val result = layout("中文aa internationalization", maxWidth = 118.0f, hyphenate = true)
        val space = result.clusters.first { it.text == " " }
        assertEquals(4.0f, space.advance, result.clusters.joinToString(",") { "${it.text}@${it.advance}" })
        val first = result.lines[0]
        assertEquals(16.0f, first.hyphenAdvance)
        assertEquals(118.0f, first.adjustedWidth + first.hyphenAdvance, 1e-9f)
    }

    @Test
    fun hyphenSqueezeConsumesOpeningAndClosingBracketGlueChannels() {
        testTrace.section("hyphenSqueezeConsumesOpeningAndClosingBracketGlueChannels")
        // Line 0 = （中·文，in- : the reserved hyphen overflows by 16 and the
        // opening bracket's leading glue (8) plus the comma's trailing
        // glue (8) each absorb their full capacity.
        val result = layout("（中·文，internationalization", maxWidth = 112.0f, hyphenate = true)
        val byText = result.clusters.associateBy { it.text }
        val opening = byText["（"] ?: error(result.clusters.toString())
        val comma = byText["，"] ?: error(result.clusters.toString())
        assertEquals(8.0f, opening.advance, result.clusters.joinToString(",") { "${it.text}@${it.advance}" })
        assertEquals(8.0f, comma.advance, result.clusters.joinToString(",") { "${it.text}@${it.advance}" })
    }

    @Test
    fun hyphenSqueezeConsumesTheInterpunctPairedChannel() {
        testTrace.section("hyphenSqueezeConsumesTheInterpunctPairedChannel")
        // Line 0 = 中文，文in- : the comma is interior, so its paired
        // leading+trailing glue (halved per side) absorbs the 2px overflow.
        val result = layout("中文，文internationalization", maxWidth = 112.0f, hyphenate = true)
        val comma = result.clusters.first { it.text == "，" }
        assertEquals(14.0f, comma.advance, result.clusters.joinToString(",") { "${it.text}@${it.advance}" })
    }

    @Test
    fun formulaLineEndDiscardsTheTrailingBoundaryAdvance() {
        testTrace.section("formulaLineEndDiscardsTheTrailingBoundaryAdvance")
        val text = "甲${INLINE_OBJECT_REPLACEMENT_CHAR}乙丙丁戊"
        val result = layout(
            text,
            maxWidth = 48.0f,
            inlineObjects = listOf(
                InlineObjectSpan(
                    range = TextRange(1, 2),
                    advance = 24.0f,
                    ascent = 12.0f,
                    descent = 12.0f,
                    trailingBoundary = InlineObjectBoundaryAdjustment(
                        lineEndDiscardableAdvance = 6.0f,
                    ),
                ),
            ),
        )
        assertEquals(0..1, result.lines[0].clusterRange, result.lines.toString())
        val discard = result.debug.lineEdgeTrimDecisions.single {
            it.reason == "InlineObjectLineEndDiscardableGlue"
        }
        assertEquals(6.0f, discard.trimAmount)
        assertEquals(0.0f, discard.consumedBefore)
        assertEquals("trailing", discard.side)
    }

    @Test
    fun attachedFootnoteTrailingGlueTrimsWhenTheLineEndsAtTheRun() {
        testTrace.section("attachedFootnoteTrailingGlueTrimsWhenTheLineEndsAtTheRun")
        // The [1] run is attached to ”; the virtual boundary's leftover
        // glue is keyed at the run's last cluster. Line 0 ends exactly
        // there (后 no longer fits), so the glue trims at the line end.
        val text = "正文：“内容。”[1]后文"
        val result = layout(
            text,
            maxWidth = 164.0f,
            spans = listOf(
                TextSpan(
                    range = TextRange(8, 11),
                    style = TextStyle(inlineAttachment = InlineAttachment.Previous),
                ),
            ),
        )
        assertEquals(0..8, result.lines[0].clusterRange, result.lines.toString())
        val trim = result.debug.lineEdgeTrimDecisions.single {
            it.reason == "AttachedInlineVirtualBoundaryLineEndTrim"
        }
        assertEquals(TextRange(8, 11), trim.clusterRange)
        assertEquals(8.0f, trim.trimAmount)
        assertEquals("trailing", trim.side)
    }

    @Test
    fun attachedObjectMarkHangsInsteadOfLeavingTheSeparatorAtAnEdge() {
        testTrace.section("attachedObjectMarkHangsInsteadOfLeavingTheSeparatorAtAnEdge")
        // The object+mark pair exceeds the measure, so the point mark hangs
        // at the pair's line end; the separator space stays interior and is
        // never collapsed a second time by the line-edge space pass.
        val text = "中${INLINE_OBJECT_REPLACEMENT_CHAR} ，中"
        val result = layout(
            text,
            maxWidth = 48.0f,
            inlineObjects = listOf(
                InlineObjectSpan(
                    range = TextRange(1, 2),
                    advance = 100.0f,
                    ascent = 12.0f,
                    descent = 12.0f,
                ),
            ),
        )
        val hung = result.lines.first { it.hangingPunctuationAdvance > 0.0f }
        assertEquals(1..3, hung.clusterRange, result.lines.toString())
        assertTrue(
            result.debug.lineEdgeTrimDecisions.none { it.reason == "LineEdgeWordSpaceCollapse" },
            result.debug.lineEdgeTrimDecisions.toString(),
        )
    }

    @Test
    fun zeroAdvanceEdgeSpaceIsNeverCollapsed() {
        testTrace.section("zeroAdvanceEdgeSpaceIsNeverCollapsed")
        // A shaper that reports 0 advance for space runs: the line-edge
        // collapse pass skips the trailing zero-width space instead of
        // recording a zero-amount trim decision.
        val zeroSpaceShaper = object : TextShaper {
            private val delegate = ExplainableStubTextShaper()
            override fun shape(input: ShapingInput): ShapingResult {
                val shaped = delegate.shape(input)
                return ShapingResult(
                    clusters = shaped.clusters.map { cluster ->
                        if (cluster.text.isNotEmpty() && cluster.text.all { it == ' ' }) {
                            cluster.copy(advance = 0.0f)
                        } else {
                            cluster
                        }
                    },
                    glyphRuns = shaped.glyphRuns,
                    decisions = shaped.decisions,
                )
            }
        }
        val result = layout(
            "中中中中 aaa bbb",
            maxWidth = 114.0f,
            textShaper = zeroSpaceShaper,
        )
        val first = result.lines[0]
        val edge = result.clusters[first.clusterRange.last]
        assertTrue(edge.text.all { it == ' ' }, result.clusters.joinToString(",") { it.text })
        assertEquals(0.0f, edge.advance)
        assertTrue(
            result.debug.lineEdgeTrimDecisions.none { it.reason == "LineEdgeWordSpaceCollapse" },
            result.debug.lineEdgeTrimDecisions.toString(),
        )
    }

    @Test
    fun hyphenSqueezeFallsBackToZeroUsedGlueWhenTheLineAlreadyFits() {
        testTrace.section("hyphenSqueezeFallsBackToZeroUsedGlueWhenTheLineAlreadyFits")
        // Both lines fit their measure, so PushIn never consumed any glue;
        // the reserved hyphen squeezes the comma's trailing glue and the
        // bracket's leading glue from a zero-used baseline.
        val comma = layout("中文，internationalization", maxWidth = 88.0f, hyphenate = true)
        assertEquals(0..3, comma.lines[0].clusterRange, comma.lines.toString())
        assertEquals(16.0f, comma.lines[0].hyphenAdvance)
        assertEquals(8.0f, comma.clusters[2].advance, comma.clusters.joinToString(",") { "${it.advance}" })

        val bracket = layout("（中文internationalization", maxWidth = 84.0f, hyphenate = true)
        assertEquals(0..3, bracket.lines[0].clusterRange, bracket.lines.toString())
        assertEquals(16.0f, bracket.lines[0].hyphenAdvance)
        assertTrue(
            bracket.clusters[0].advance <= 16.0f,
            bracket.clusters.joinToString(",") { "${it.advance}" },
        )
    }

    @Test
    fun tinyTechnicalTrackingStaysBelowTheRejectionThreshold() {
        testTrace.section("tinyTechnicalTrackingStaysBelowTheRejectionThreshold")
        // Line 0 is six CJK characters breaking at the span's whitespace
        // boundary; the 0.004px excess lands on CjkInterChar tracking as
        // 0.0008 per boundary, under the rejection epsilon. Widening to
        // 96.4 lifts the tracking to 0.08 and the WholeToken tier is
        // rejected for the span instead.
        val text = "中中中中中中 aaaa"
        val span = listOf(LineBreakSpan(TextRange(0, text.length), LineBreakPolicy.ProgressiveTechnical))

        val tiny = layout(text, maxWidth = 96.004f, lineBreakSpans = span)
        assertEquals(0..5, tiny.lines[0].clusterRange, tiny.lines.toString())
        val deltas = tiny.debug.justificationDecisions
            .flatMap { it.allocations }
            .filter { it.kind == "CjkInterChar" }
            .map { it.delta }
        assertTrue(deltas.isNotEmpty(), tiny.debug.justificationDecisions.toString())
        assertTrue(deltas.all { it <= 0.001f }, deltas.toString())
        assertTrue(
            tiny.debug.emergencyTrackingEligibilityDecisions.none {
                it.reason.startsWith("CurrentLineTechnicalTierRejection:")
            },
            tiny.debug.emergencyTrackingEligibilityDecisions.map { it.reason }.toString(),
        )

        val rejected = layout(text, maxWidth = 96.4f, lineBreakSpans = span)
        assertTrue(
            rejected.debug.emergencyTrackingEligibilityDecisions.any {
                it.reason == "CurrentLineTechnicalTierRejection:WholeToken"
            },
            rejected.debug.emergencyTrackingEligibilityDecisions.map { it.reason }.toString(),
        )
    }

    @Test
    fun formulaObjectWithoutBoundaryDiscardsNothingAtLineEnd() {
        testTrace.section("formulaObjectWithoutBoundaryDiscardsNothingAtLineEnd")
        val text = "甲${INLINE_OBJECT_REPLACEMENT_CHAR}乙丙丁戊"
        val result = layout(
            text,
            maxWidth = 48.0f,
            inlineObjects = listOf(
                InlineObjectSpan(
                    range = TextRange(1, 2),
                    advance = 24.0f,
                    ascent = 12.0f,
                    descent = 12.0f,
                ),
            ),
        )
        assertEquals(0..1, result.lines[0].clusterRange, result.lines.toString())
        assertTrue(
            result.debug.lineEdgeTrimDecisions.none { it.reason == "InlineObjectLineEndDiscardableGlue" },
            result.debug.lineEdgeTrimDecisions.toString(),
        )
    }

    @Test
    fun baselineShiftSpanRaisesTheFinalClusterShift() {
        testTrace.section("baselineShiftSpanRaisesTheFinalClusterShift")
        val result = layout(
            "中文正文",
            maxWidth = 200.0f,
            spans = listOf(
                TextSpan(
                    range = TextRange(0, 2),
                    style = TextStyle(baselineShift = 4.0f),
                ),
            ),
        )
        assertEquals(4.0f, result.clusters[0].baselineShift)
        assertEquals(4.0f, result.clusters[1].baselineShift)
        assertEquals(0.0f, result.clusters[2].baselineShift)
    }

    @Test
    fun dashRunWithoutInkBoundsKeepsSyntheticGlyphs() {
        testTrace.section("dashRunWithoutInkBoundsKeepsSyntheticGlyphs")
        // The stub shaper reports no glyph ink bounds, so the dash ink
        // centering pass keeps the synthetic fallback glyphs untouched.
        val result = layout("中——中", maxWidth = 200.0f)
        assertEquals(1, result.lines.size, result.lines.toString())
        assertEquals(1, result.glyphRuns.size)
        val run = result.glyphRuns[0]
        assertEquals(3, run.glyphs.size)
        assertTrue(run.glyphs.all { it.bounds == null }, run.glyphs.toString())
        assertEquals(64.0f, run.advance)
    }

    @Test
    fun emergencySelectedBreakOpensThePreferredTrackingSpan() {
        testTrace.section("emergencySelectedBreakOpensThePreferredTrackingSpan")
        val text = "deadbeefcafebabefeedfaceabcdefabcdef"
        val result = layout(
            text,
            maxWidth = 101.0f,
            lineBreakSpans = listOf(
                LineBreakSpan(TextRange(0, text.length), LineBreakPolicy.ProgressiveTechnical),
            ),
        )
        assertTrue(result.lines.size > 1, result.lines.toString())
        val tracking = result.debug.justificationDecisions
            .flatMap { it.allocations }
            .filter { it.kind == "EmergencyGraphemeTracking" }
        assertTrue(tracking.isNotEmpty(), result.debug.justificationDecisions.toString())
    }

    @Test
    fun technicalLineBodyStretchRejectsTheCleanTierAndReplays() {
        testTrace.section("technicalLineBodyStretchRejectsTheCleanTierAndReplays")
        // Line 0 retains a clean whitespace-tier break whose justification
        // would use unbounded CJK body tracking; the stage rejects that
        // tier for the span and replays, which re-exposes the span's
        // Emergency cuts under the rejection reason.
        val text = "中文中 aa bb 中文中文中文中文中文中文"
        val result = layout(
            text,
            maxWidth = 96.0f,
            lineBreakSpans = listOf(
                LineBreakSpan(TextRange(0, text.length), LineBreakPolicy.ProgressiveTechnical),
            ),
        )
        assertTrue(result.lines.size > 1, result.lines.toString())
        assertTrue(
            result.debug.emergencyTrackingEligibilityDecisions.any {
                it.reason.startsWith("CurrentLineTechnicalTierRejection:")
            },
            result.debug.emergencyTrackingEligibilityDecisions.map { it.reason }.toString(),
        )
        assertTrue(
            result.debug.breakOpportunityDecisions.any { it.reason == "CurrentLineTechnicalEmergencyBreak" },
            result.debug.breakOpportunityDecisions.map { it.reason }.toString(),
        )
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
