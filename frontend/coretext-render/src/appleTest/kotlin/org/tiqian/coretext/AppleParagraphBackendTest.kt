package org.tiqian.coretext

import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LayoutInput
import org.tiqian.core.TiqianTextContent
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End-to-end runtime validation of the Apple backend: the full composition pipeline
 * (Core Text font metrics -> Core Text shaping -> line breaking -> LayoutResult) runs
 * on macOS native with real system fonts.
 */
class AppleParagraphBackendTest {

    @Test
    fun laysOutCjkParagraphEndToEndWithCoreText() {
        val engine = appleParagraphEngine()
        val input = LayoutInput(
            content = TiqianTextContent("你好，世界！这是提椠中文排版引擎的一段测试文字。"),
            constraints = LayoutConstraints(maxWidth = 200f),
        )
        val result = engine.layout(input)

        assertTrue(result.clusters.isNotEmpty(), "should produce clusters")
        assertTrue(result.glyphRuns.isNotEmpty(), "should produce glyph runs")
        assertTrue(result.lines.isNotEmpty(), "should produce line boxes")
        assertTrue(result.size.width > 0f && result.size.height > 0f, "should have positive size")
        assertTrue(
            result.debug.shapingDecisions.any { it.source == "CoreText" },
            "shaping should run through Core Text",
        )
    }

    @Test
    fun wrapsToMultipleLinesUnderNarrowWidth() {
        val engine = appleParagraphEngine()
        val input = LayoutInput(
            content = TiqianTextContent("这是一段足够长的中文文字用来测试在窄宽度下的自动换行行为是否正确无误。"),
            constraints = LayoutConstraints(maxWidth = 96f),
        )
        val result = engine.layout(input)

        assertTrue(result.lines.size >= 2, "narrow width should wrap to multiple lines, got ${result.lines.size}")
    }
}
