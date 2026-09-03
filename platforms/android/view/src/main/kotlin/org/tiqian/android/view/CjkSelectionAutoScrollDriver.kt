package org.tiqian.android.view

import android.graphics.Rect
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class WordDrag(
    val initial: CjkDocumentSelection,
    val originRawX: Float,
    val originRawY: Float,
    var autoScrollArmed: Boolean = false,
)

/** Frame-clock driver for document word/handle selection at a scrolling viewport edge. */
internal class CjkSelectionAutoScrollDriver(
    private val host: CjkTextSurface,
    private val isArmed: () -> Boolean,
    private val refreshEndpoint: (rawX: Float, rawY: Float) -> Unit,
) {
    var scrollHost: CjkSelectionScrollHost? = null

    private var rawX = Float.NaN
    private var rawY = Float.NaN
    private var posted = false
    private var lastFrameMillis = 0L
    private val viewportBounds = Rect()

    fun updatePointer(rawX: Float, rawY: Float) {
        this.rawX = rawX
        this.rawY = rawY
    }

    fun schedule() {
        if (!rawY.isFinite() || !isArmed() || posted) return
        posted = true
        lastFrameMillis = SystemClock.uptimeMillis()
        host.postOnAnimation(frame)
    }

    fun stop() {
        posted = false
        host.removeCallbacks(frame)
        scrollHost = null
        rawX = Float.NaN
        rawY = Float.NaN
    }

    private val frame = object : Runnable {
        override fun run() {
            if (!isArmed()) {
                posted = false
                return
            }
            val capability = scrollHost
            val velocity = capability?.let(::velocity) ?: 0f
            if (capability == null || velocity == 0f) {
                posted = false
                return
            }
            val now = SystemClock.uptimeMillis()
            val elapsed = (now - lastFrameMillis).coerceIn(1L, 50L) / 1_000f
            lastFrameMillis = now
            val delta = (velocity * elapsed).roundToInt().let { value ->
                if (value == 0) if (velocity > 0f) 1 else -1 else value
            }
            refreshEndpoint(rawX, rawY)
            val consumed = capability.scrollBy(delta.toFloat())
            if (abs(consumed) >= MINIMUM_CONSUMED_PX) {
                refreshEndpoint(rawX, rawY)
                host.postOnAnimation(this)
            } else {
                posted = false
            }
        }
    }

    private fun velocity(capability: CjkSelectionScrollHost): Float {
        if (!capability.viewportBoundsOnScreen(viewportBounds)) return 0f
        return cjkSelectionAutoScrollVelocity(
            armed = isArmed(),
            pointerY = rawY,
            viewportTop = viewportBounds.top.toFloat(),
            viewportBottom = viewportBounds.bottom.toFloat(),
            edgeSize = host.selectionAutoScrollEdgeSizeDp * host.densityValue,
            maxVelocity = host.selectionAutoScrollMaxVelocityDpPerSecond * host.densityValue,
        )
    }

    private companion object {
        const val MINIMUM_CONSUMED_PX = 0.01f
    }
}
