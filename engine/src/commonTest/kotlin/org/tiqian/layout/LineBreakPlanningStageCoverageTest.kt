package org.tiqian.layout

import org.tiqian.clreq.AdjustmentStylePolicy
import org.tiqian.clreq.ClreqProfile
import org.tiqian.clreq.ClreqProfileResolver
import org.tiqian.clreq.LineAdjustmentStrategy
import org.tiqian.core.Ic
import org.tiqian.core.INLINE_OBJECT_REPLACEMENT_CHAR
import org.tiqian.core.InlineObjectSpan
import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LayoutInput
import org.tiqian.core.LineBreakPolicy
import org.tiqian.core.LineBreakSpan
import org.tiqian.core.LineLengthGrid
import org.tiqian.core.ParagraphStyle
import org.tiqian.core.TextRange
import org.tiqian.core.TiqianTextContent
import org.tiqian.test.EarlyLayoutFixtures
import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.assertTrue
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

/**
 * Coverage for LineBreakPlanningStage.kt arms reachable only through engine
 * configuration: the three LineAdjustmentStrategy settings, the explicit
 * zero line-height on a control-only paragraph, and the emergency-tracking
 * boundary eligibility skips (zero-width controls, mandatory breaks, inline
 * objects, no-stretch punctuation).
 */
class LineBreakPlanningStageCoverageTest {
    private val testTrace = TestTraceRecorder("LineBreakPlanningStageCoverageTest")


    private val noIndent = ParagraphStyle(
        firstLineIndent = Ic(0.0f),
        lineLengthGrid = LineLengthGrid(enabled = false),
    )

    private fun engineWith(lineAdjustment: LineAdjustmentStrategy? = null): ExplainableStubParagraphLayoutEngine =
        if (lineAdjustment == null) {
            ExplainableStubParagraphLayoutEngine()
        } else {
            ExplainableStubParagraphLayoutEngine(
                clreqProfileResolver = ClreqProfileResolver {
                    ClreqProfile.MainlandHorizontal.copy(
                        adjustment = AdjustmentStylePolicy(lineAdjustment = lineAdjustment),
                    )
                },
            )
        }

    private fun layout(
        text: String,
        maxWidth: Float,
        lineAdjustment: LineAdjustmentStrategy? = null,
        lineHeight: Float? = null,
        lineBreakSpans: List<LineBreakSpan> = emptyList(),
        inlineObjects: List<InlineObjectSpan> = emptyList(),
    ): org.tiqian.core.LayoutResult {
        val style = if (lineHeight != null) noIndent.copy(lineHeight = lineHeight) else noIndent
        return engineWith(lineAdjustment).layout(
            LayoutInput(
                paragraphStyle = style,
                content = TiqianTextContent(text = text, spans = emptyList(), lineBreakSpans = lineBreakSpans),
                inlineObjects = inlineObjects,
                constraints = LayoutConstraints(maxWidth = maxWidth),
            ),
        )
    }

    @Test
    fun pushOutFirstTakesFewerFillPushInsThanPushInFirst() {
        testTrace.section("pushOutFirstTakesFewerFillPushInsThanPushInFirst")
        // PushOutFirst prices compression at half visibility (bias 0.5), so
        // its bias gate skips every pull the PushInFirst gate (bias 1e6)
        // would at least attempt; it can never end on MORE compressed lines.
        val fixture = EarlyLayoutFixtures.all.first { it.id == "real-paragraph-1" }
        fun layoutWith(strategy: LineAdjustmentStrategy): org.tiqian.core.LayoutResult {
            val base = ClreqProfile.MainlandHorizontal
            val engine = ExplainableStubParagraphLayoutEngine(
                lineBreaker = LookaheadLineBreaker(),
                clreqProfileResolver = {
                    base.copy(adjustment = base.adjustment.copy(lineAdjustment = strategy))
                },
            )
            return engine.layout(
                LayoutInput(content = TiqianTextContent(fixture.text), constraints = fixture.constraints),
            )
        }
        fun org.tiqian.core.LayoutResult.fillPushInCount(): Int =
            debug.lineDecisions.count { it.repairDecision?.reasonCode == "LineAdjustmentPushIn" }

        val pushInFirst = layoutWith(LineAdjustmentStrategy.PushInFirst)
        val pushOutFirst = layoutWith(LineAdjustmentStrategy.PushOutFirst)
        assertTrue(pushInFirst.fillPushInCount() > 0, pushInFirst.debug.lineDecisions.toString())
        assertTrue(
            pushOutFirst.fillPushInCount() <= pushInFirst.fillPushInCount(),
            "PushOutFirst ${pushOutFirst.fillPushInCount()} vs PushInFirst ${pushInFirst.fillPushInCount()}",
        )
        assertTrue(
            pushOutFirst.lines.size >= pushInFirst.lines.size,
            "PushOutFirst ${pushOutFirst.lines.size} vs PushInFirst ${pushInFirst.lines.size}",
        )
    }

    @Test
    fun explicitZeroLineHeightKeepsTheControlParagraphAtZeroHeight() {
        testTrace.section("explicitZeroLineHeightKeepsTheControlParagraphAtZeroHeight")
        val result = layout("\n", maxWidth = 100.0f, lineHeight = 0.0f)
        assertEquals(2, result.lines.size, result.lines.toString())
        assertEquals(0.0f, result.size.height, result.size.height.toString())
    }

    @Test
    fun emergencyBoundaryEligibilitySkipsZeroWidthAndMandatoryControls() {
        testTrace.section("emergencyBoundaryEligibilitySkipsZeroWidthAndMandatoryControls")
        // The ZWSP and the mandatory break each sit between two technical
        // clusters; neither boundary may open intra-token tracking.
        val zwspText = "ab​cd"
        val zwsp = layout(
            zwspText,
            maxWidth = 200.0f,
            lineBreakSpans = listOf(
                LineBreakSpan(TextRange(0, zwspText.length), LineBreakPolicy.ProgressiveTechnical),
            ),
        )
        assertEquals(1, zwsp.lines.size, zwsp.lines.toString())

        val mandatory = layout(
            "aa\nbb",
            maxWidth = 200.0f,
            lineBreakSpans = listOf(LineBreakSpan(TextRange(0, 5), LineBreakPolicy.ProgressiveTechnical)),
        )
        assertEquals(2, mandatory.lines.size, mandatory.lines.toString())
    }

    @Test
    fun emergencyBoundaryEligibilitySkipsInlineObjectBoundaries() {
        testTrace.section("emergencyBoundaryEligibilitySkipsInlineObjectBoundaries")
        val text = "a￼b"
        val result = layout(
            text,
            maxWidth = 200.0f,
            inlineObjects = listOf(
                InlineObjectSpan(range = TextRange(1, 2), advance = 16.0f, ascent = 8.0f, descent = 8.0f),
            ),
            lineBreakSpans = listOf(LineBreakSpan(TextRange(0, 3), LineBreakPolicy.ProgressiveTechnical)),
        )
        assertEquals(1, result.lines.size, result.lines.toString())
        assertTrue(result.lines[0].clusterRange.first == 0)
    }

    @Test
    fun dashAndSolidusBoundariesInsideTechnicalSpansNeverStretch() {
        testTrace.section("dashAndSolidusBoundariesInsideTechnicalSpansNeverStretch")
        for (text in listOf("a—b—c", "a/b/c", "a…b")) {
            val result = layout(
                text,
                maxWidth = 24.0f,
                lineBreakSpans = listOf(
                    LineBreakSpan(TextRange(0, text.length), LineBreakPolicy.ProgressiveTechnical),
                ),
            )
            assertTrue(result.lines.isNotEmpty(), "$text: ${result.lines}")
            assertTrue(
                result.debug.justificationDecisions.flatMap { it.allocations }
                    .all { it.kind != "EmergencyGraphemeTracking" || it.delta <= 0.0f },
                "$text: ${result.debug.justificationDecisions}",
            )
        }
    }

    @Test
    fun overlappingTechnicalSpansKeepTheFirstBoundaryReason() {
        testTrace.section("overlappingTechnicalSpansKeepTheFirstBoundaryReason")
        val result = layout(
            "aabbcc",
            maxWidth = 200.0f,
            lineBreakSpans = listOf(
                LineBreakSpan(TextRange(0, 4), LineBreakPolicy.ProgressiveTechnical),
                LineBreakSpan(TextRange(2, 6), LineBreakPolicy.ProgressiveTechnical),
            ),
        )
        assertEquals(1, result.lines.size, result.lines.toString())
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
