package org.tiqian.layout

import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LayoutInput
import org.tiqian.core.TiqianTextContent
import org.tiqian.core.ParagraphStyle
import org.tiqian.core.Ic
import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.assertTrue
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

class OpeningBracketLineStartTest {
    private val testTrace = TestTraceRecorder("OpeningBracketLineStartTest")

    @Test
    fun testOpeningBracketAtLineStartCompression() {
        testTrace.section("testOpeningBracketAtLineStartCompression")
        val text = "这是第一行测试文字这是第一行测试\n（Shaping & Font Metrics）这是第二行文字\n（GPOS / GSUB 特性表查询）这是第三行文字"
        val engine = ExplainableStubParagraphLayoutEngine()
        val result = engine.layout(
            LayoutInput(
                content = TiqianTextContent(text),
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                constraints = LayoutConstraints(maxWidth = 672f),
            )
        )

        // Line 1 and Line 2 start with '（'
        assertEquals(3, result.lines.size)
        val line1FirstCluster = result.clusters[result.lines[1].clusterRange.first]
        val line2FirstCluster = result.clusters[result.lines[2].clusterRange.first]

        assertEquals("（", line1FirstCluster.text)
        assertEquals(8.0f, line1FirstCluster.advance, 0.01f)

        assertEquals("（", line2FirstCluster.text)
        assertEquals(8.0f, line2FirstCluster.advance, 0.01f)

        val startTrims = result.debug.lineEdgeTrimDecisions.filter { it.reason == "LineStartHalfWidthPunctuation" }
        assertEquals(2, startTrims.size)
        assertTrue(startTrims.all { it.side == "leading" && it.trimAmount == 8.0f })
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
