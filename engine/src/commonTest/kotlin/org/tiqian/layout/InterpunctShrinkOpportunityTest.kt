package org.tiqian.layout

import org.tiqian.core.Ic
import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LayoutInput
import org.tiqian.core.ParagraphStyle
import org.tiqian.core.Rect
import org.tiqian.clreq.ClreqProfile
import org.tiqian.clreq.CjkPunctuationGlyphPolicy
import org.tiqian.core.TiqianTextContent
import org.tiqian.shaping.ExplainableStubTextShaper
import org.tiqian.shaping.ShapingInput
import org.tiqian.shaping.ShapingResult
import org.tiqian.shaping.TextShaper
import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.fail
import org.tiqian.test.trace.assertTrue
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

/**
 * The interpunct family (PunctuationClass.Interpunct / MiddleDot) publishes
 * its tier-3 geometry-aware shrink opportunity only when the punctuation
 * ledger actually holds glue for the mark. The policy fallback gives the
 * family a full-em body, so the glue requires font ink evidence: a shaper
 * whose narrow centered ink lets the body compress and frees both sides.
 */
class InterpunctShrinkOpportunityTest {
    private val testTrace = TestTraceRecorder("InterpunctShrinkOpportunityTest")


    @Test
    fun interpunctInkEvidenceFreesPairedGlueForTierThreeShrink() {
        testTrace.section("interpunctInkEvidenceFreesPairedGlueForTierThreeShrink")
        val text = "正文·间隔号·后文…结尾"
        val result = ExplainableStubParagraphLayoutEngine(textShaper = haltInkShaper()).layout(
            LayoutInput(
                content = TiqianTextContent(text),
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic.Zero),
                constraints = LayoutConstraints(maxWidth = 320.0f),
            )
        )
        // The halt half-width advance with its centred placement shift makes
        // every middle dot a center-anchored atom with symmetric positive
        // glue — the precondition for the paired tier-3
        // LeadingAndTrailingGlue shrink opportunity.
        val dots = result.debug.punctuationDecisions.filter { it.char == '·' }
        assertEquals(2, dots.size)
        dots.forEach { dot ->
            assertTrue(dot.leadingGlueNatural > 0.0f, "leading glue: ${dot.leadingGlueNatural}")
            assertTrue(dot.trailingGlueNatural > 0.0f, "trailing glue: ${dot.trailingGlueNatural}")
            assertEquals("Center", dot.anchor)
            assertEquals("FontHaltFittedBodyCompression", dot.geometrySource)
        }
        // The ellipsis is outside the six named classes: its trailing-only
        // halt glue routes it through the residual tier-5 arm.
        // The source ellipsis is displayed as ⋯ (U+22EF); both share the
        // Ellipsis class.
        val ellipsis = result.debug.punctuationDecisions.single { it.punctuationClass == "Ellipsis" }
        assertEquals(0.0f, ellipsis.leadingGlueNatural)
        assertTrue(ellipsis.trailingGlueNatural > 0.0f, "trailing glue: ${ellipsis.trailingGlueNatural}")
        assertTrue(result.lines.isNotEmpty())
    }

    @Test
    fun preservedInterpunctCodepointKeepsInterpunctClassForTierThreeShrink() {
        testTrace.section("preservedInterpunctCodepointKeepsInterpunctClassForTierThreeShrink")
        // Under PreserveInput the katakana middle dot keeps its own
        // codepoint, so its atom carries the Interpunct class itself instead
        // of arriving as the substituted MiddleDot.
        val text = "正文・间隔・后文"
        val result = ExplainableStubParagraphLayoutEngine(
            clreqProfileResolver = {
                ClreqProfile.MainlandHorizontal.copy(
                    punctuationGlyphPolicy = CjkPunctuationGlyphPolicy.PreserveInput,
                )
            },
            textShaper = haltInkShaper(),
        ).layout(
            LayoutInput(
                content = TiqianTextContent(text),
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic.Zero),
                constraints = LayoutConstraints(maxWidth = 320.0f),
            )
        )
        val interpuncts = result.debug.punctuationDecisions.filter { it.punctuationClass == "Interpunct" }
        assertEquals(listOf("・", "・"), interpuncts.map { it.char.toString() })
        interpuncts.forEach { dot ->
            assertTrue(dot.leadingGlueNatural > 0.0f, "leading glue: ${dot.leadingGlueNatural}")
            assertTrue(dot.trailingGlueNatural > 0.0f, "trailing glue: ${dot.trailingGlueNatural}")
            assertEquals("Center", dot.anchor)
        }
        assertTrue(result.lines.isNotEmpty())
    }

    /**
     * Fonts answer `halt` with a half-width advance and a centred placement
     * shift; only a shaper that reports those lets the interpunct family
     * escape its full-em policy body floor and expose glue.
     */
    private fun haltInkShaper(): TextShaper = object : TextShaper {
        private val delegate = ExplainableStubTextShaper()
        override fun shape(input: ShapingInput): ShapingResult {
            val res = delegate.shape(input)
            val sourceChar = input.text.substring(input.range.start, input.range.end)
            val isInterpunct = sourceChar == "·" || sourceChar == "・"
            // An ellipsis keeps its glyph origin (placement 0) and answers
            // halt with a half advance: all removable space sits on the
            // trailing side.
            val isEllipsis = sourceChar == "…"
            return res.copy(
                clusters = res.clusters,
                glyphRuns = res.glyphRuns.map { run ->
                    run.copy(
                        glyphs = run.glyphs.map {
                            it.copy(
                                bounds = if (isEllipsis) {
                                    Rect(left = 2.0f, top = 2.0f, right = 10.0f, bottom = 10.0f)
                                } else {
                                    Rect(left = 4.0f, top = 2.0f, right = 12.0f, bottom = 10.0f)
                                },
                                haltAdvance = if (isInterpunct || isEllipsis) 8.0f else it.haltAdvance,
                                haltPlacementX = if (isInterpunct) -4.0f else if (isEllipsis) 0.0f else it.haltPlacementX,
                            )
                        },
                    )
                },
            )
        }
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
