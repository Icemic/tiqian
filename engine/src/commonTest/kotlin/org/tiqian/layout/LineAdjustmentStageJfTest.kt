package org.tiqian.layout

import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.assertTrue
import org.tiqian.clreq.ClreqProfile
import org.tiqian.clreq.ClreqProfileResolver
import org.tiqian.core.Cluster
import org.tiqian.core.Glyph
import org.tiqian.core.GlyphRun
import org.tiqian.core.INLINE_OBJECT_REPLACEMENT_CHAR
import org.tiqian.core.InlineObjectBoundaryAdjustment
import org.tiqian.core.InlineObjectSpan
import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LayoutInput
import org.tiqian.core.LayoutProfileId
import org.tiqian.core.LayoutResult
import org.tiqian.core.LineBreakSpan
import org.tiqian.core.LineLengthGrid
import org.tiqian.core.ParagraphStyle
import org.tiqian.core.Rect
import org.tiqian.core.TextRange
import org.tiqian.core.TextSpan
import org.tiqian.core.TiqianTextContent
import org.tiqian.linebreak.EnglishHyphenation
import org.tiqian.shaping.ExplainableStubTextShaper
import org.tiqian.shaping.ShapingInput
import org.tiqian.shaping.ShapingResult
import org.tiqian.shaping.TextShaper
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

class LineAdjustmentStageJfTest {
    private val testTrace = TestTraceRecorder("LineAdjustmentStageJfTest")


    private val noIndent = ParagraphStyle(
        firstLineIndent = org.tiqian.core.Ic(0.0f),
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
        clreqProfile: ClreqProfile? = null,
    ): LayoutResult {
        val resolver = if (clreqProfile != null) {
            object : ClreqProfileResolver {
                override fun resolve(profileId: LayoutProfileId): ClreqProfile = clreqProfile
            }
        } else {
            org.tiqian.clreq.BuiltInClreqProfileResolver
        }
        val engine = when {
            textShaper != null -> ExplainableStubParagraphLayoutEngine(textShaper = textShaper, clreqProfileResolver = resolver)
            hyphenate -> ExplainableStubParagraphLayoutEngine(hyphenator = EnglishHyphenation.enUs, clreqProfileResolver = resolver)
            else -> ExplainableStubParagraphLayoutEngine(clreqProfileResolver = resolver)
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
    fun hyphenSqueezeConsumesPairedLeadingAndTrailingGlueUnderTaiwanProfile() {
        testTrace.section("hyphenSqueezeConsumesPairedLeadingAndTrailingGlueUnderTaiwanProfile")
        // Under TaiwanHorizontal profile, punctuation '，' is centered with paired leading and trailing glue (ShrinkChannel.LeadingAndTrailingGlue)
        // With hyphenation, the shortfall squeezes both leading and trailing glue of '，'.
        val result = layout(
            "中文，文internationalization",
            maxWidth = 112.0f,
            hyphenate = true,
            clreqProfile = ClreqProfile.TaiwanHorizontal,
        )
        val comma = result.clusters.first { it.text == "，" }
        assertTrue(comma.advance < 16.0f, "Comma advance should have shrunk: ${comma.advance}")
    }

    @Test
    fun dashInkCenteringWithShapedBounds() {
        testTrace.section("dashInkCenteringWithShapedBounds")
        val dashShaper = object : TextShaper {
            private val delegate = ExplainableStubTextShaper()
            override fun shape(input: ShapingInput): ShapingResult {
                val shaped = delegate.shape(input)
                return ShapingResult(
                    clusters = shaped.clusters,
                    glyphRuns = shaped.glyphRuns.map { run ->
                        GlyphRun(
                            range = run.range,
                            fontKey = run.fontKey,
                            glyphs = run.glyphs.map { glyph ->
                                if (input.displayText.contains('⸺')) {
                                    // Width = 28.0 >= 32 * 0.85 = 27.2 (avoids rollback)
                                    // Inset = (32 - 28) / 2 - 1.0 = 1.0 > 0.5
                                    glyph.copy(
                                        bounds = Rect(left = 1.0f, top = 0.0f, right = 29.0f, bottom = 16.0f),
                                    )
                                } else {
                                    glyph
                                }
                            },
                            advance = run.advance,
                            openTypeFeatures = run.openTypeFeatures,
                        )
                    },
                    decisions = shaped.decisions,
                )
            }
        }
        val result = layout("中——中", maxWidth = 200.0f, textShaper = dashShaper)
        val dashGlyph = result.glyphRuns.flatMap { it.glyphs }.first { it.bounds != null }
        // Inset should center the ink: (32 - 28) / 2 - 1 = 1.0 > 0.5
        assertEquals(1.0f, dashGlyph.x)
    }

    @Test
    fun dashInkCenteringWithWideBoundsReturnsSameGlyph() {
        testTrace.section("dashInkCenteringWithWideBoundsReturnsSameGlyph")
        val dashShaper = object : TextShaper {
            private val delegate = ExplainableStubTextShaper()
            override fun shape(input: ShapingInput): ShapingResult {
                val shaped = delegate.shape(input)
                return ShapingResult(
                    clusters = shaped.clusters,
                    glyphRuns = shaped.glyphRuns.map { run ->
                        GlyphRun(
                            range = run.range,
                            fontKey = run.fontKey,
                            glyphs = run.glyphs.map { glyph ->
                                if (input.displayText.contains('⸺')) {
                                    // Inset = (32 - 31.5) / 2 - 0 = 0.25 <= 0.5
                                    glyph.copy(
                                        bounds = Rect(left = 0.0f, top = 0.0f, right = 31.5f, bottom = 16.0f),
                                    )
                                } else {
                                    glyph
                                }
                            },
                            advance = run.advance,
                            openTypeFeatures = run.openTypeFeatures,
                        )
                    },
                    decisions = shaped.decisions,
                )
            }
        }
        val result = layout("中——中", maxWidth = 200.0f, textShaper = dashShaper)
        val dashGlyph = result.glyphRuns.flatMap { it.glyphs }.first { it.bounds != null }
        assertEquals(0.0f, dashGlyph.x)
    }

    @Test
    fun inlineObjectWithZeroDiscardableAdvance() {
        testTrace.section("inlineObjectWithZeroDiscardableAdvance")
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
                        lineEndDiscardableAdvance = 0.0f,
                    ),
                ),
            ),
        )
        assertEquals(0..1, result.lines[0].clusterRange)
    }

    @Test
    fun inlineObjectSeparatorSpaceTrimEdge() {
        testTrace.section("inlineObjectSeparatorSpaceTrimEdge")
        // Line ends or starts at the separator space of an attached inline object
        val text = "中${INLINE_OBJECT_REPLACEMENT_CHAR} ，文文"
        val result = layout(
            text,
            maxWidth = 34.0f,
            inlineObjects = listOf(
                InlineObjectSpan(
                    range = TextRange(1, 2),
                    advance = 16.0f,
                    ascent = 12.0f,
                    descent = 12.0f,
                ),
            ),
        )
        assertTrue(result.lines.size > 1)
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
