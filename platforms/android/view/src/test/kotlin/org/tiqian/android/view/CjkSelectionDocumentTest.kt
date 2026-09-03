package org.tiqian.android.view

import org.tiqian.core.RubySpan
import org.tiqian.core.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CjkSelectionDocumentTest {
    @Test
    fun fragmentKeysMustBeUnique() {
        assertFailsWith<IllegalArgumentException> {
            CjkSelectionDocument(
                listOf(
                    CjkSelectionDocumentFragment("body", "甲"),
                    CjkSelectionDocumentFragment("body", "乙"),
                ),
            )
        }
    }

    @Test
    fun sourceAndClipboardProjectionStaySeparateAcrossFragments() {
        val fragments = listOf(
            CjkSelectionDocumentFragment(
                "first",
                "提椠",
                rubySpans = listOf(RubySpan(TextRange(0, 2), "tíqiàn")),
                separatorAfter = "\n\n",
            ),
            CjkSelectionDocumentFragment(
                "second",
                "正文",
                rubySpans = listOf(RubySpan(TextRange(0, 2), "注")),
            ),
        )
        val start = CjkDocumentSelectionAnchor("first", 0)
        val end = CjkDocumentSelectionAnchor("second", 2)

        assertEquals("提椠\n\n正文", fragments.projectSelectionText(start, end, copyProjection = false))
        assertEquals(
            "提椠（tíqiàn）\n\n正文（注）",
            fragments.projectSelectionText(start, end, copyProjection = true),
        )
        assertEquals(
            "椠\n\n正",
            fragments.projectSelectionText(
                CjkDocumentSelectionAnchor("first", 1),
                CjkDocumentSelectionAnchor("second", 1),
                copyProjection = true,
            ),
        )
        assertEquals(
            "提椠（tíqiàn）",
            fragments.projectSelectionText(
                CjkDocumentSelectionAnchor("first", 0),
                CjkDocumentSelectionAnchor("first", 2),
                copyProjection = true,
            ),
        )
    }

    @Test
    fun paragraphBoundarySelectionPreservesBothSidesOfTheSelectedSeparator() {
        val fragments = listOf(
            CjkSelectionDocumentFragment("first", "甲乙", separatorAfter = "\n\n"),
            CjkSelectionDocumentFragment("second", "丙丁"),
        )

        val projections = requireNotNull(
            fragments.selectionProjections(
                CjkDocumentSelectionAnchor("first", 2),
                CjkDocumentSelectionAnchor("second", 0),
            ),
        )

        assertEquals(
            CjkDocumentSelectionProjection(selectedSeparatorAfter = "\n\n"),
            projections.getValue("first"),
        )
        assertEquals(
            CjkDocumentSelectionProjection(selectedSeparatorBefore = "\n\n"),
            projections.getValue("second"),
        )
        assertEquals(
            "\n\n",
            fragments.projectSelectionText(
                CjkDocumentSelectionAnchor("first", 2),
                CjkDocumentSelectionAnchor("second", 0),
                copyProjection = false,
            ),
        )
    }

    @Test
    fun attachedProjectionCanBeBuiltWithoutMaterializingTheSelectedDocument() {
        val fragments = List(10_000) { index ->
            CjkSelectionDocumentFragment(index, "第${index}段")
        }
        val start = CjkDocumentSelectionAnchor(10, 1)
        val end = CjkDocumentSelectionAnchor(9_990, 2)
        val slice = requireNotNull(fragments.selectionSlice(start, end))

        assertEquals(
            fragments.selectionProjections(start, end).orEmpty()[5_000],
            fragments.selectionProjectionAt(slice, 5_000),
        )
        assertNull(fragments.selectionProjectionAt(slice, 2))
    }

    @Test
    fun autoScrollUsesQuadraticSignedEdgeRamp() {
        assertEquals(0f, cjkSelectionAutoScrollVelocity(true, 50f, 0f, 100f, 20f, 1_000f))
        assertEquals(-250f, cjkSelectionAutoScrollVelocity(true, 10f, 0f, 100f, 20f, 1_000f))
        assertEquals(250f, cjkSelectionAutoScrollVelocity(true, 90f, 0f, 100f, 20f, 1_000f))
        assertEquals(0f, cjkSelectionAutoScrollVelocity(false, 0f, 0f, 100f, 20f, 1_000f))
    }
}
