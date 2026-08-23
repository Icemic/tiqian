package org.tiqian.layout

import org.tiqian.core.DecorationKind
import org.tiqian.core.DecorationSpan
import org.tiqian.core.InlineBoxSpan
import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LayoutInput
import org.tiqian.core.RubyKind
import org.tiqian.core.RubySpan
import org.tiqian.core.TextRange
import org.tiqian.core.TextSpan
import org.tiqian.core.TextStyle
import org.tiqian.core.TiqianTextContent
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Render-evidence extension of the prepared plan (ADR 0053 SinglePlanLowerer
 * step 1). The stub shaper keeps every number deterministic, so structural
 * assertions pin field names, ordering and the append-only property without
 * hand-writing full JSON.
 */
class PreparedParagraphRenderEvidenceTest {

    private fun layout(input: LayoutInput) =
        ExplainableStubParagraphLayoutEngine(lineBreaker = LookaheadLineBreaker()).layout(input)

    @Test
    fun plainParagraphEvidenceIsAppendOnly() {
        val pureInput = LayoutInput(
            content = TiqianTextContent("中文段落纯文本测试"),
            constraints = LayoutConstraints(maxWidth = 200f),
        )
        val pureResult = layout(pureInput)
        val purePlain = pureResult.toPreparedParagraphJson()
        val pureEvidence = pureResult.toPreparedParagraphJson(renderEvidence = true)
        assertTrue(pureEvidence.startsWith(purePlain.removeSuffix("}")))

        val input = LayoutInput(
            content = TiqianTextContent("中文段落，含标点与替换破折号——测试。"),
            constraints = LayoutConstraints(maxWidth = 200f),
        )
        val result = layout(input)
        val plain = result.toPreparedParagraphJson()
        val evidence = result.toPreparedParagraphJson(renderEvidence = true)
        assertFalse(plain.contains("\"fontSize\""))
        assertFalse(plain.contains("\"rubyDecisions\""))
        assertFalse(plain.contains("\"dashStrategy\""))
        assertTrue(evidence.contains("\"schema\":1,"))
        assertTrue(evidence.contains("\"fontSize\":"))
        assertTrue(evidence.contains("\"overlayWidth\":"))
    }

    @Test
    fun pinyinRubyEmitsRubyDecisions() {
        val input = LayoutInput(
            content = TiqianTextContent("北京是首都。"),
            constraints = LayoutConstraints(maxWidth = 200f),
            rubySpans = listOf(RubySpan(baseRange = TextRange(0, 2), text = "Běijīng")),
        )
        val result = layout(input)
        assertFalse(result.toPreparedParagraphJson().contains("rubyDecisions"))
        val evidence = result.toPreparedParagraphJson(renderEvidence = true)
        assertContains(evidence, "\"rubyDecisions\":[")
        assertContains(evidence, "\"baseRangeStart\":0")
        assertContains(evidence, "\"baseRangeEnd\":2")
        assertContains(evidence, "\"text\":\"Běijīng\"")
        assertContains(evidence, "\"centerX\":")
        assertContains(evidence, "\"baselineY\":")
        assertContains(evidence, "\"fontSize\":")
        assertContains(evidence, "\"fontWeight\":500")
    }

    @Test
    fun bopomofoRubyEmitsBopomofoDecisions() {
        val input = LayoutInput(
            content = TiqianTextContent("好文。"),
            constraints = LayoutConstraints(maxWidth = 200f),
            rubySpans = listOf(
                RubySpan(baseRange = TextRange(0, 1), text = "ㄏㄠˇ", kind = RubyKind.Bopomofo),
            ),
        )
        val result = layout(input)
        assertFalse(result.toPreparedParagraphJson().contains("bopomofoDecisions"))
        val evidence = result.toPreparedParagraphJson(renderEvidence = true)
        assertContains(evidence, "\"bopomofoDecisions\":[")
        assertContains(evidence, "\"placements\":[")
        assertContains(evidence, "\"role\":\"")
        assertFalse(evidence.contains("\"rubyDecisions\""))
    }

    @Test
    fun decorationsEmitSegmentsDotsAndRanges() {
        val input = LayoutInput(
            content = TiqianTextContent("鲁迅的小说在中国现代文学里很重要。"),
            constraints = LayoutConstraints(maxWidth = 200f),
            decorations = listOf(
                DecorationSpan(range = TextRange(0, 2), kind = DecorationKind.ProperNoun),
                DecorationSpan(range = TextRange(3, 5), kind = DecorationKind.BookTitle),
                DecorationSpan(range = TextRange(6, 9), kind = DecorationKind.Emphasis),
            ),
        )
        val result = layout(input)
        val plain = result.toPreparedParagraphJson()
        assertFalse(plain.contains("decorationSegments"))
        assertFalse(plain.contains("emphasisDots"))
        assertFalse(plain.contains("emphasisRanges"))
        val evidence = result.toPreparedParagraphJson(renderEvidence = true)
        assertContains(evidence, "\"decorationSegments\":[")
        assertContains(evidence, "\"kind\":\"ProperNoun\"")
        assertContains(evidence, "\"kind\":\"BookTitle\"")
        assertContains(evidence, "\"sourceRangeStart\":0")
        assertContains(evidence, "\"emphasisRanges\":[[6,9]]")
        if (evidence.contains("\"emphasisDots\"")) {
            assertContains(evidence, "\"anchorX\":")
            assertContains(evidence, "\"dotDiameter\":")
        }
    }

    @Test
    fun styleDeltaEmitsPerCellStyleBlock() {
        val input = LayoutInput(
            content = TiqianTextContent(
                text = "普通字与小字混排的段落。",
                spans = listOf(
                    TextSpan(
                        range = TextRange(4, 6),
                        style = TextStyle(fontSize = 12f, fontWeight = 700),
                    ),
                ),
            ),
            constraints = LayoutConstraints(maxWidth = 200f),
        )
        val result = layout(input)
        assertFalse(result.toPreparedParagraphJson().contains("\"style\":{"))
        val evidence = result.toPreparedParagraphJson(renderEvidence = true)
        assertContains(evidence, "\"style\":{\"fontSize\":")
        assertContains(evidence, "\"fontWeight\":700")
    }

    @Test
    fun inlineBoxesEmitInlineEdges() {
        val input = LayoutInput(
            content = TiqianTextContent("文字与边距。"),
            constraints = LayoutConstraints(maxWidth = 200f),
            inlineBoxes = listOf(
                InlineBoxSpan(
                    range = TextRange(0, 1),
                    inlineStart = 2f,
                    inlineEnd = 3f,
                ),
            ),
        )
        val result = layout(input)
        assertFalse(result.toPreparedParagraphJson().contains("inlineEdges"))
        val evidence = result.toPreparedParagraphJson(renderEvidence = true)
        assertContains(evidence, "\"inlineEdges\":[")
        assertContains(evidence, "\"offset\":0")
        assertContains(evidence, "\"offset\":1")
        assertContains(evidence, "\"inlineStart\":2")
        assertContains(evidence, "\"inlineEnd\":3")
    }
}
