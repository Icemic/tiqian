package org.tiqian.android.view

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private data class TestHandlePosition(
    val key: Any,
    val offset: Int,
) : CjkSelectionHandlePosition

class CjkTextSelectionControllerTest {
    @Test
    fun horizontalHandleHotspotsAlignWithCaret() {
        val drawableWidth = 80

        assertEquals(60, selectionHandleHotspot(drawableWidth, CjkSelectionHandle.Start))
        assertEquals(20, selectionHandleHotspot(drawableWidth, CjkSelectionHandle.End))
    }

    @Test
    fun touchUpFilterUsesAospFiveSampleReleaseWindow() {
        val filter = CjkSelectionTouchUpFilter()

        // Editor.HandleView keeps the last five changed offsets. A release within 150 ms of the
        // newest sample may still be corrected to the oldest sample more than 350 ms old. This
        // is observable when the user pauses briefly and then lifts a finger.
        filter.start(position = TestHandlePosition("paragraph", 0), timeMillis = 0L)
        filter.add(position = TestHandlePosition("paragraph", 1), timeMillis = 300L)

        assertEquals(TestHandlePosition("paragraph", 0), filter.positionForTouchUp(nowMillis = 400L))
        assertNull(filter.positionForTouchUp(nowMillis = 450L))
    }

    @Test
    fun touchUpFilterCountsRepeatedOffsetSamplesLikeFramework() {
        val filter = CjkSelectionTouchUpFilter()

        // Repeated offsets are still samples in HandleView's ring. These recent repeats evict the
        // old 0; an implementation that deduplicates them would incorrectly resurrect 0 on UP.
        filter.start(position = TestHandlePosition("paragraph", 0), timeMillis = 0L)
        filter.add(position = TestHandlePosition("paragraph", 1), timeMillis = 560L)
        filter.add(position = TestHandlePosition("paragraph", 1), timeMillis = 570L)
        filter.add(position = TestHandlePosition("paragraph", 1), timeMillis = 580L)
        filter.add(position = TestHandlePosition("paragraph", 1), timeMillis = 590L)
        filter.add(position = TestHandlePosition("paragraph", 2), timeMillis = 600L)

        assertNull(filter.positionForTouchUp(nowMillis = 700L))
    }

    @Test
    fun touchUpFilterRetainsExactlyTheLastFiveSamples() {
        val filter = CjkSelectionTouchUpFilter()

        filter.start(position = TestHandlePosition("paragraph", 0), timeMillis = 0L)
        filter.add(position = TestHandlePosition("paragraph", 1), timeMillis = 300L)
        filter.add(position = TestHandlePosition("paragraph", 2), timeMillis = 310L)
        filter.add(position = TestHandlePosition("paragraph", 3), timeMillis = 320L)
        filter.add(position = TestHandlePosition("paragraph", 4), timeMillis = 330L)
        filter.add(position = TestHandlePosition("paragraph", 5), timeMillis = 340L)

        // The old offset 0 has fallen out of Editor.HandleView's five-entry ring. All retained
        // samples are recent, so release filtering must not resurrect that evicted position.
        assertNull(filter.positionForTouchUp(nowMillis = 401L))
    }

    @Test
    fun touchUpFilterPreservesDocumentAnchorIdentityWithoutFlattening() {
        val filter = CjkSelectionTouchUpFilter()
        val start = TestHandlePosition("first", Int.MAX_VALUE)
        val moved = TestHandlePosition("last", Int.MAX_VALUE)

        filter.start(position = start, timeMillis = 0L)
        filter.add(position = moved, timeMillis = 300L)

        assertEquals(start, filter.positionForTouchUp(nowMillis = 400L))
    }
}
