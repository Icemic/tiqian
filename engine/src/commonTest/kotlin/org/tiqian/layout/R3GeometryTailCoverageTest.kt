package org.tiqian.layout

import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.assertTrue
import org.tiqian.core.InlineAttachment
import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LayoutInput
import org.tiqian.core.Rect
import org.tiqian.core.RubyKind
import org.tiqian.core.RubySpan
import org.tiqian.core.TextRange
import org.tiqian.core.TextStyle
import org.tiqian.core.TextSpan
import org.tiqian.core.TiqianTextContent
import org.tiqian.shaping.ExplainableStubTextShaper
import org.tiqian.shaping.ShapingInput
import org.tiqian.shaping.ShapingResult
import org.tiqian.shaping.TextShaper
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

/**
 * Engine-level fixtures for the R3 geometry tail: maxLines truncation,
 * empty text, pure-Latin height fallback, misaligned ruby base ranges,
 * space-run autospace arms on both Wide/Narrow orders, an attached
 * reference at the very end of the source, and centered-ink punctuation
 * glue.
 */
class R3GeometryTailCoverageTest {
    private val testTrace = TestTraceRecorder("R3GeometryTailCoverageTest")


    private fun layout(
        text: String,
        maxWidth: Float = 320.0f,
        maxLines: Int = Int.MAX_VALUE,
        spans: List<TextSpan> = emptyList(),
        rubySpans: List<RubySpan> = emptyList(),
        shaper: TextShaper = ExplainableStubTextShaper(),
    ) = ExplainableStubParagraphLayoutEngine(textShaper = shaper).layout(
        LayoutInput(
            content = TiqianTextContent(text = text, spans = spans),
            rubySpans = rubySpans,
            constraints = LayoutConstraints(maxWidth = maxWidth, maxLines = maxLines),
        ),
    )

    @Test
    fun maxLinesCapsVisibleLinesToOne() {
        testTrace.section("maxLinesCapsVisibleLinesToOne")
        val long = "中文排版引擎测试文本，用于验证多行截断行为是否正确工作并继续延伸。"
        val unrestricted = layout(long, maxWidth = 64.0f)
        assertTrue(unrestricted.lines.size > 1, "fixture must wrap without the cap")
        val capped = layout(long, maxWidth = 64.0f, maxLines = 1)
        assertEquals(1, capped.lines.size)
    }

    @Test
    fun emptyTextProducesNoVisibleLines() {
        testTrace.section("emptyTextProducesNoVisibleLines")
        val result = layout("", maxWidth = 100.0f)
        assertTrue(result.lines.isEmpty())
    }

    @Test
    fun pureLatinParagraphStillProducesLines() {
        testTrace.section("pureLatinParagraphStillProducesLines")
        val result = layout("hello justified world", maxWidth = 96.0f)
        assertTrue(result.lines.isNotEmpty())
        assertTrue(result.lines[0].naturalWidth > 0.0f)
    }

    @Test
    fun rubyBaseRangeCrossingClusterBoundariesIsSkipped() {
        testTrace.section("rubyBaseRangeCrossingClusterBoundariesIsSkipped")
        // Clusters over pure CJK are one per character: (0,2)(2,4)(4,6)(6,8).
        // baseRange (1,3) starts inside the first cluster and ends inside the
        // second, so no cluster index range covers it and the ruby is skipped.
        val result = layout(
            "中文测试",
            maxWidth = 320.0f,
            rubySpans = listOf(
                RubySpan(baseRange = TextRange(0, 2), text = "zhōng", kind = RubyKind.Pinyin),
                RubySpan(baseRange = TextRange(1, 3), text = "wén", kind = RubyKind.Pinyin),
            ),
        )
        // Annotation decisions carry both rubies; the misaligned base range
        // only drops out of the per-line extent computation (clusterIndexRangeFor
        // returns null for a range crossing cluster boundaries).
        assertEquals(2, result.debug.rubyDecisions.size)
    }

    @Test
    fun spaceRunsResolveBothWideNarrowOrders() {
        testTrace.section("spaceRunsResolveBothWideNarrowOrders")
        val cjkFirst = layout("中文 abc", maxWidth = 320.0f)
        assertEquals(1, cjkFirst.lines.size)
        assertTrue(cjkFirst.lines[0].naturalWidth > 0.0f)
        val latinFirst = layout("abc 中文", maxWidth = 320.0f)
        assertEquals(1, latinFirst.lines.size)
        assertTrue(latinFirst.lines[0].naturalWidth > 0.0f)
    }

    @Test
    fun attachedReferenceAtSourceEndLaysOutWithoutVirtualBoundary() {
        testTrace.section("attachedReferenceAtSourceEndLaysOutWithoutVirtualBoundary")
        val text = "正文：“内容·[1]"
        val attachAt = text.indexOf("[1]")
        val result = layout(
            text,
            maxWidth = 320.0f,
            spans = listOf(
                TextSpan(
                    range = TextRange(attachAt, attachAt + 3),
                    style = TextStyle(inlineAttachment = InlineAttachment.Previous),
                ),
            ),
        )
        // No cluster follows the attachment, so no virtual autospace boundary
        // is emitted for it; the adjacent-punctuation collapse earlier in the
        // same paragraph still lands.
        assertTrue(result.debug.spacingDecisions.none { it.reason.startsWith("AttachedInlineVirtual") })
        val collapse = result.debug.spacingDecisions.single { it.leftChar == '：' && it.rightChar == '“' }
        assertTrue(collapse.reduction > 0.0f)
    }

    @Test
    fun centeredInkPunctuationKeepsPairedGlue() {
        testTrace.section("centeredInkPunctuationKeepsPairedGlue")
        val wide = layout("中·文", maxWidth = 320.0f, shaper = centeredInkShaper())
        assertEquals(1, wide.lines.size)
        val tight = layout("文·本，内容。", maxWidth = 60.0f, shaper = centeredInkShaper())
        assertTrue(tight.lines.size > 1)
    }

    private fun centeredInkShaper(): TextShaper = object : TextShaper {
        private val delegate = ExplainableStubTextShaper()
        override fun shape(input: ShapingInput): ShapingResult {
            val res = delegate.shape(input)
            return res.copy(
                glyphRuns = res.glyphRuns.map { run ->
                    run.copy(
                        glyphs = run.glyphs.map {
                            it.copy(bounds = Rect(left = 4.0f, top = 2.0f, right = 12.0f, bottom = 10.0f))
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
