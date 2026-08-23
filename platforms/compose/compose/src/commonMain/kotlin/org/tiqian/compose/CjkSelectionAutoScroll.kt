package org.tiqian.compose

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs

private const val MaxAutoScrollFrameSeconds = 0.05f
private const val MinConsumedScrollPx = 0.01f

/**
 * Drives selection-owned vertical scrolling only after the pointer gesture has become a real drag.
 * A stationary long press never arms this effect, even when it lies inside an edge band.
 */
@Composable
internal fun CjkSelectionAutoScrollEffect(
    state: CjkSelectionState,
    scrollState: ScrollState?,
    edgeSize: Dp,
    maxVelocity: Dp,
) {
    if (scrollState == null) return
    require(edgeSize.value > 0f) { "autoScrollEdgeSize must be positive" }
    require(maxVelocity.value > 0f) { "autoScrollMaxVelocity must be positive" }
    val density = LocalDensity.current
    val edgeSizePx = with(density) { edgeSize.toPx() }
    val maxVelocityPxPerSecond = with(density) { maxVelocity.toPx() }

    LaunchedEffect(state, scrollState, edgeSizePx, maxVelocityPxPerSecond) {
        // Observe only the gesture lifetime. Pointer samples are read inside the frame loop so a
        // continuously moving finger cannot cancel/restart the scroll mutation before it advances.
        snapshotFlow { state.isSelectionAutoScrollArmed }.collectLatest { armed ->
            if (!armed) return@collectLatest
            scrollState.scroll(MutatePriority.UserInput) {
                var previousFrame = withFrameNanos { it }
                while (state.isSelectionAutoScrollArmed) {
                    val frame = withFrameNanos { it }
                    // The previous frame's scroll has now moved child coordinates. Refresh the
                    // source endpoint before applying this frame's next scroll delta.
                    state.refreshSelectionAfterAutoScroll()
                    val velocity = selectionAutoScrollVelocity(
                        armed = state.isSelectionAutoScrollArmed,
                        pointerY = state.currentDragPosition.y,
                        viewportHeight = state.selectionViewportHeightPx,
                        edgeSize = edgeSizePx,
                        maxVelocity = maxVelocityPxPerSecond,
                    )
                    val elapsedSeconds =
                        ((frame - previousFrame) / 1_000_000_000f)
                            .coerceIn(0f, MaxAutoScrollFrameSeconds)
                    previousFrame = frame
                    if (velocity != 0f) {
                        val consumed = scrollBy(velocity * elapsedSeconds)
                        // Stay alive at a scroll boundary: the same gesture may cross to the
                        // opposite edge without transitioning through an unarmed state.
                        if (abs(consumed) >= MinConsumedScrollPx) {
                            state.refreshSelectionAfterAutoScroll()
                        }
                    }
                }
            }
        }
    }
}

/** Quadratic edge ramp: zero in the safe center and signed px/s inside either edge band. */
internal fun selectionAutoScrollVelocity(
    armed: Boolean,
    pointerY: Float,
    viewportHeight: Float,
    edgeSize: Float,
    maxVelocity: Float,
): Float {
    if (!armed || !pointerY.isFinite() || viewportHeight <= 0f || edgeSize <= 0f) return 0f
    val effectiveEdge = edgeSize.coerceAtMost(viewportHeight / 2f)
    val topPenetration = ((effectiveEdge - pointerY) / effectiveEdge).coerceIn(0f, 1f)
    if (topPenetration > 0f) return -maxVelocity * topPenetration * topPenetration
    val bottomPenetration =
        ((pointerY - (viewportHeight - effectiveEdge)) / effectiveEdge).coerceIn(0f, 1f)
    return maxVelocity * bottomPenetration * bottomPenetration
}
