package org.tiqian.layout

import org.tiqian.core.Ic
import org.tiqian.core.InlineBoxSpan
import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LayoutInput
import org.tiqian.core.ParagraphStyle
import org.tiqian.core.TextRange
import org.tiqian.core.TiqianTextContent
import kotlin.test.Test
import org.tiqian.test.trace.assertFalse
import org.tiqian.test.trace.assertTrue
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

/**
 * The inlineEdges gate of the prepared-paragraph JSON: a box that answers
 * with a trailing edge only (inlineStart zero, inlineEnd positive) takes the
 * second disjunct of the emptiness check, and its serialized edge entry
 * carries the inlineEnd field alone.
 */
class PreparedParagraphInlineEdgesTest {
    private val testTrace = TestTraceRecorder("PreparedParagraphInlineEdgesTest")


    @Test
    fun endOnlyInlineBoxEmitsEdgeWithoutInlineStartField() {
        testTrace.section("endOnlyInlineBoxEmitsEdgeWithoutInlineStartField")
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                content = TiqianTextContent("中文正文"),
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic.Zero),
                constraints = LayoutConstraints(maxWidth = 320.0f),
                inlineBoxes = listOf(
                    InlineBoxSpan(range = TextRange(0, 2), inlineStart = 0.0f, inlineEnd = 4.0f),
                ),
            )
        )
        val json = result.toPreparedParagraphJson(renderEvidence = true)
        val edgesAt = json.indexOf("\"inlineEdges\":[")
        assertTrue(edgesAt >= 0, "inlineEdges array missing: $json")
        val entry = json.substring(edgesAt)
        assertTrue("\"offset\":2" in entry, "edge offset (box end) missing: $entry")
        assertTrue("\"inlineEnd\":4" in entry, "inlineEnd field missing: $entry")
        assertFalse("\"inlineStart\":" in entry, "inlineStart must be absent for an end-only box: $entry")
    }

    @Test
    fun contentWithoutInlineBoxesOmitsInlineEdgesArray() {
        testTrace.section("contentWithoutInlineBoxesOmitsInlineEdgesArray")
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                content = TiqianTextContent("中文正文"),
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic.Zero),
                constraints = LayoutConstraints(maxWidth = 320.0f),
            )
        )
        val json = result.toPreparedParagraphJson(renderEvidence = true)
        assertFalse("\"inlineEdges\":" in json, "no boxes, no edges: $json")
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
