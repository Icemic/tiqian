package org.tiqian.layout

import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.assertTrue
import org.tiqian.core.Cluster
import org.tiqian.core.DecorationDecisionInfo
import org.tiqian.core.DecorationKind
import org.tiqian.core.Glyph
import org.tiqian.core.GlyphRun
import org.tiqian.core.InlineBoxSpan
import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LayoutDebugInfo
import org.tiqian.core.LayoutInput
import org.tiqian.core.LayoutResult
import org.tiqian.core.LineBox
import org.tiqian.core.ShapingDecisionInfo
import org.tiqian.core.Size
import org.tiqian.core.TextRange
import org.tiqian.core.TextSpan
import org.tiqian.core.TextStyle
import org.tiqian.core.TiqianTextContent
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder
import org.tiqian.test.trace.f32Literal

class PreparedParagraphJfTest {
    private val testTrace = TestTraceRecorder("PreparedParagraphJfTest")


    private fun line(
        range: TextRange,
        clusterRange: IntRange,
        baseline: Float = 20.0f,
        top: Float = 0.0f,
        bottom: Float = 24.0f,
        naturalWidth: Float = 26.0f,
    ): LineBox = LineBox(
        range = range,
        clusterRange = clusterRange,
        baseline = baseline,
        top = top,
        bottom = bottom,
        naturalWidth = naturalWidth,
        adjustedWidth = naturalWidth,
        visualWidth = naturalWidth,
    )

    @Test
    fun styleAtAndStyleDeltasInPreparedParagraphJson() {
        testTrace.section("styleAtAndStyleDeltasInPreparedParagraphJson")
        val defaultStyle = TextStyle(fontSize = 16.0f, fontWeight = 400, italic = false)
        // Multi-span coverage for styleAt predicate evaluation
        val text = "甲乙丙丁戊己"
        val content = TiqianTextContent(
            text = text,
            spans = listOf(
                TextSpan(TextRange(1, 3), TextStyle(fontSize = 20.0f, fontWeight = 700, italic = true)),
                TextSpan(TextRange(2, 4), TextStyle(fontSize = 16.0f, fontWeight = 700, italic = false)),
                TextSpan(TextRange(4, 5), TextStyle(fontSize = 16.0f, fontWeight = 400, italic = true)),
            ),
        )

        val clusters = listOf(
            Cluster(range = TextRange(0, 1), text = "甲", fontKey = "k", advance = 16.0f),
            Cluster(range = TextRange(1, 2), text = "乙", fontKey = "k", advance = 20.0f),
            Cluster(range = TextRange(2, 3), text = "丙", fontKey = "k", advance = 16.0f),
            Cluster(range = TextRange(3, 4), text = "丁", fontKey = "k", advance = 16.0f),
            Cluster(range = TextRange(4, 5), text = "戊", fontKey = "k", advance = 16.0f),
            Cluster(range = TextRange(5, 6), text = "己", fontKey = "k", advance = 16.0f),
        )

        val glyphRuns = listOf(
            GlyphRun(
                range = TextRange(0, 6),
                fontKey = "k",
                glyphs = listOf(
                    Glyph(1u, TextRange(0, 1), 16.0f),
                    Glyph(2u, TextRange(1, 2), 20.0f),
                    Glyph(3u, TextRange(2, 3), 16.0f),
                    Glyph(4u, TextRange(3, 4), 16.0f),
                    Glyph(5u, TextRange(4, 5), 16.0f),
                    Glyph(6u, TextRange(5, 6), 16.0f),
                ),
                advance = 100.0f,
                openTypeFeatures = listOf("liga", "dlig"),
            ),
        )

        val result = LayoutResult(
            input = LayoutInput(
                content = content,
                textStyle = defaultStyle,
                constraints = LayoutConstraints(maxWidth = 200.0f),
            ),
            size = Size(200.0f, 24.0f),
            clusters = clusters,
            glyphRuns = glyphRuns,
            lines = listOf(line(TextRange(0, 6), 0..5, naturalWidth = 100.0f)),
            debug = LayoutDebugInfo(),
        )

        val json = result.toPreparedParagraphJson(renderEvidence = true)
        assertTrue(json.contains("\"openTypeFeatures\":[\"liga\",\"dlig\"]"))
        assertTrue(json.contains("\"fontSize\":20"))
        assertTrue(json.contains("\"fontWeight\":700"))
        assertTrue(json.contains("\"italic\":true"))
    }

    @Test
    fun inlineBoxEdgesAndEmphasisDotsFilter() {
        testTrace.section("inlineBoxEdgesAndEmphasisDotsFilter")
        val content = TiqianTextContent("甲乙")
        val clusters = listOf(
            Cluster(range = TextRange(0, 1), text = "甲", fontKey = "k", advance = 16.0f),
            Cluster(range = TextRange(1, 2), text = "乙", fontKey = "k", advance = 16.0f),
        )
        val glyphRuns = listOf(
            GlyphRun(
                range = TextRange(0, 2),
                fontKey = "k",
                glyphs = listOf(
                    Glyph(1u, TextRange(0, 1), 16.0f),
                    Glyph(2u, TextRange(1, 2), 16.0f),
                ),
                advance = 32.0f,
            ),
        )

        val debug = LayoutDebugInfo(
            decorationDecisions = listOf(
                // applied = false
                DecorationDecisionInfo(
                    clusterRange = TextRange(0, 1),
                    sourceText = "甲",
                    kind = DecorationKind.Emphasis.name,
                    applied = false,
                    reason = "test",
                    dotDiameter = 4.0f,
                ),
                // kind != Emphasis
                DecorationDecisionInfo(
                    clusterRange = TextRange(0, 1),
                    sourceText = "甲",
                    kind = DecorationKind.ProperNoun.name,
                    applied = true,
                    reason = "test",
                    dotDiameter = 4.0f,
                ),
                // dotDiameter <= 0
                DecorationDecisionInfo(
                    clusterRange = TextRange(0, 1),
                    sourceText = "甲",
                    kind = DecorationKind.Emphasis.name,
                    applied = true,
                    reason = "test",
                    dotDiameter = 0.0f,
                ),
                // valid dot
                DecorationDecisionInfo(
                    clusterRange = TextRange(0, 1),
                    sourceText = "甲",
                    kind = DecorationKind.Emphasis.name,
                    applied = true,
                    reason = "test",
                    anchorX = 8.0f,
                    anchorY = 20.0f,
                    dotDiameter = 4.0f,
                ),
            ),
        )

        // One box with only inlineStart, one box with only inlineEnd
        val inlineBoxes = listOf(
            InlineBoxSpan(TextRange(0, 1), inlineStart = 4.0f, inlineEnd = 0.0f),
            InlineBoxSpan(TextRange(1, 2), inlineStart = 0.0f, inlineEnd = 6.0f),
        )

        val result = LayoutResult(
            input = LayoutInput(
                content = content,
                constraints = LayoutConstraints(maxWidth = 200.0f),
                inlineBoxes = inlineBoxes,
            ),
            size = Size(200.0f, 24.0f),
            clusters = clusters,
            glyphRuns = glyphRuns,
            lines = listOf(line(TextRange(0, 2), 0..1, naturalWidth = 32.0f)),
            debug = debug,
        )

        val json = result.toPreparedParagraphJson(renderEvidence = true)
        assertTrue(json.contains("\"inlineStart\":4"))
        assertTrue(json.contains("\"inlineEnd\":6"))
        assertTrue(json.contains("\"emphasisDots\":"))
    }

    @Test
    fun dashShapingDecisionWithGlyphIds() {
        testTrace.section("dashShapingDecisionWithGlyphIds")
        val content = TiqianTextContent("——")
        val clusters = listOf(Cluster(range = TextRange(0, 2), text = "——", fontKey = "k", advance = 32.0f))
        val glyphRuns = listOf(
            GlyphRun(
                range = TextRange(0, 2),
                fontKey = "k",
                glyphs = listOf(Glyph(42u, TextRange(0, 2), 32.0f)),
                advance = 32.0f,
            ),
        )
        val debug = LayoutDebugInfo(
            shapingDecisions = listOf(
                ShapingDecisionInfo(
                    range = TextRange(0, 2),
                    sourceText = "——",
                    displayText = "——",
                    fontKey = "k",
                    glyphCount = 1,
                    advance = 32.0f,
                    source = "test",
                    reason = "DashRule",
                    language = "zh",
                    resolvedFace = "NotoSansCJK",
                    strategy = "DashTwoEmLigature",
                ),
            ),
        )
        val result = LayoutResult(
            input = LayoutInput(content = content, constraints = LayoutConstraints(maxWidth = 200.0f)),
            size = Size(200.0f, 24.0f),
            clusters = clusters,
            glyphRuns = glyphRuns,
            lines = listOf(line(TextRange(0, 2), 0..0, naturalWidth = 32.0f)),
            debug = debug,
        )
        val json = result.toPreparedParagraphJson(renderEvidence = true)
        assertTrue(json.contains("\"glyphIds\":\"42\""))
        assertTrue(json.contains("\"shapingLanguage\":\"zh\""))
        assertTrue(json.contains("\"resolvedFace\":\"NotoSansCJK\""))
    }

    @Test
    fun ecmaJsonNumberEdgeCases() {
        testTrace.section("ecmaJsonNumberEdgeCases")
        // Subnormal number
        val subnormal = Float.fromBits(1)
        val sJson = ecmaJsonNumber(subnormal)
        assertTrue(sJson.isNotEmpty())

        val subnormal2 = Float.fromBits(0x007F_FFFF)
        assertTrue(ecmaJsonNumber(subnormal2).isNotEmpty())

        // The Float nearest 9.000000000000001e-17 widens to
        // 8.999999688540309e-17; its exact expansion rounds the kept digit up
        // through incrementDecimal, and the sweep below widens the coverage.
        assertEquals("8.999999688540309e-17", ecmaJsonNumber(f32Literal(9.000000000000001e-17f)))
        for (i in 1..2000) {
            ecmaJsonNumber(i * 1e-17f)
            ecmaJsonNumber(i * 1e-15f)
            ecmaJsonNumber(i * 1e-20f)
        }

        // Powers of two
        for (shift in 1..60) {
            val v = (1L shl shift).toFloat()
            assertTrue(ecmaJsonNumber(v).isNotEmpty())
            assertTrue(ecmaJsonNumber(-v).isNotEmpty())
            assertTrue(ecmaJsonNumber(1.0f / v).isNotEmpty())
            assertTrue(ecmaJsonNumber(-1.0f / v).isNotEmpty())
        }

        // Test numbers near powers of 10 and edge float values
        val edgeValues = floatArrayOf(
            0.9999999999999999f,
            0.09999999999999999f,
            0.009999999999999999f,
            9.999999999999999e-5f,
            1.9999999999999998e-4f,
            9.999999999999999e20f,
            1.9999999999999999e20f,
            1e-300f,
            1e300f,
            Float.MIN_VALUE,
            Float.MAX_VALUE,
            1.0e-302f,
            5.960464477539063e-8f,
            2.9802322387695312e-8f,
        )
        for (v in edgeValues) {
            assertTrue(ecmaJsonNumber(v).isNotEmpty())
            assertTrue(ecmaJsonNumber(-v).isNotEmpty())
        }
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
