package org.tiqian.coretext

import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LayoutInput
import org.tiqian.core.TiqianTextContent
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End-to-end CLREQ conformance of the APPLE path: proves the engine's CLREQ rules fire
 * correctly when driven by the Core Text shaper + font metrics on macOS native. This does
 * not re-test the rules themselves (that is :clreq's own commonTest, which also runs on
 * macosArm64) — it proves real Core Text measurements produce CLREQ-conformant layout.
 */
class AppleClreqConformanceTest {
    private val engine = appleParagraphEngine()

    private fun layout(text: String, maxWidth: Float) =
        engine.layout(LayoutInput(TiqianTextContent(text), constraints = LayoutConstraints(maxWidth = maxWidth)))

    // 行首禁则 (line-start-forbidden): pause/stop/closing marks must never start a line.
    private val lineStartForbidden = "。，、！？；：）」』】》".toSet()

    @Test
    fun kinsoku_noLineStartsWithForbiddenPunctuation() {
        val text = "春眠不觉晓，处处闻啼鸟，夜来风雨声，花落知多少，问君能有几多愁，恰似一江春水向东流。"
        val result = layout(text, maxWidth = 100f)
        assertTrue(result.lines.size >= 3, "need multiple lines to exercise 避头尾, got ${result.lines.size}")
        for ((idx, line) in result.lines.withIndex()) {
            val firstCluster = result.clusters.getOrNull(line.clusterRange.first) ?: continue
            val firstChar = firstCluster.displayText.firstOrNull() ?: firstCluster.text.firstOrNull()
            assertTrue(
                firstChar == null || firstChar !in lineStartForbidden,
                "line $idx starts with forbidden punctuation '$firstChar' — 避头尾 violated",
            )
        }
        assertTrue(
            result.debug.kinsokuDecision != null || result.debug.contextualKinsokuDecisions.isNotEmpty(),
            "kinsoku machinery should have engaged",
        )
    }

    @Test
    fun justification_machineryEngagedAndNonLastLinesFill() {
        val text = "这是一段用于测试两端对齐的足够长的中文正文它应当在窄宽度下被引擎按 CLREQ 两端对齐处理。"
        val width = 120f
        val result = layout(text, maxWidth = width)
        assertTrue(result.lines.size >= 2, "need multiple lines, got ${result.lines.size}")
        assertTrue(result.debug.justificationDecisions.isNotEmpty(), "两端对齐 machinery should have run")
        for ((idx, line) in result.lines.dropLast(1).withIndex()) {
            assertTrue(
                line.visualWidth >= width * 0.8f,
                "non-last line $idx should fill toward the measure (两端对齐), got ${line.visualWidth} of $width",
            )
        }
    }

    @Test
    fun autospace_cjkLatinBoundarySpacing() {
        val result = layout("中文Abc漢字123你好World", maxWidth = 400f)
        assertTrue(
            result.debug.autoSpaceDecisions.isNotEmpty(),
            "中西文间距 (autospace) should run at CJK/Latin and CJK/digit boundaries",
        )
    }

    @Test
    fun punctuationCompression_machineryEngaged() {
        val result = layout("他说：「你好。」然后离开了，真的。", maxWidth = 400f)
        assertTrue(
            result.debug.punctuationDecisions.isNotEmpty(),
            "标点挤压 machinery should have produced punctuation decisions",
        )
        // CLREQ expresses 标点挤压 through the mark's compressible glue (a half-em of
        // leading/trailing space), not by shrinking the glyph box — advance stays 1em.
        val hasCompressibleGlue = result.debug.punctuationDecisions.any {
            it.leadingGlueNatural > 0f || it.trailingGlueNatural > 0f
        }
        assertTrue(
            hasCompressibleGlue,
            "full-width punctuation should carry compressible glue (CLREQ 标点挤压 channel); got " +
                "${result.debug.punctuationDecisions.map { "adv=${it.advance},lead=${it.leadingGlueNatural},trail=${it.trailingGlueNatural}" }}",
        )
    }
}
