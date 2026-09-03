package org.tiqian.core

import kotlin.test.Test
import kotlin.test.assertEquals

class LayoutResultReplayIndexTest {
    @Test
    fun replayIndexKeepsSelectionAndRichTextOnEngineGeometry() {
        val link = RichTextSpan(TextRange(1, 3), RichTextRole.Link("https://example.com"))
        val result = sampleResult()

        val index = result.toReplayIndex(listOf(link))

        assertEquals(3, index.positionedClusters.size)
        assertEquals(TextRange(1, 3), index.richTextSegments.single().range)
        assertEquals(link, index.richTextSegments.single().span)
        assertEquals(2, index.selectionOffsetForPosition(result, 19f, 10f))
        assertEquals(
            listOf(Rect(10f, 0f, 30f, 20f)),
            index.selectionBoxes(result, TextRange(1, 3)),
        )
    }

    private fun sampleResult(): LayoutResult = LayoutResult(
        input = LayoutInput(
            content = TiqianTextContent("甲乙丙"),
            textStyle = TextStyle(fontSize = 10f),
            constraints = LayoutConstraints(maxWidth = 30f),
        ),
        size = Size(30f, 20f),
        clusters = listOf(
            Cluster(TextRange(0, 1), "甲", fontKey = "cjk", advance = 10f),
            Cluster(TextRange(1, 2), "乙", fontKey = "cjk", advance = 10f),
            Cluster(TextRange(2, 3), "丙", fontKey = "cjk", advance = 10f),
        ),
        glyphRuns = emptyList(),
        lines = listOf(
            LineBox(
                range = TextRange(0, 3),
                clusterRange = 0..2,
                baseline = 15f,
                top = 0f,
                bottom = 20f,
                naturalWidth = 30f,
                adjustedWidth = 30f,
                visualWidth = 30f,
            ),
        ),
    )
}
