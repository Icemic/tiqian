package org.tiqian.android.view

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Region
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import kotlin.math.roundToInt

/**
 * Replays engine-authorized paint in the overlay of the ancestor that actually clips it.
 *
 * Keeping the replay in that ancestor's display list is important for scrolling containers:
 * ordinary children and their overhang are then recorded and submitted by the same RenderNode
 * transaction. A root-level second pass can observe the right geometry and still visibly trail a
 * RecyclerView whose child movement is committed in a different display list.
 *
 * The registry never calls [View.draw], changes layout, or expands engine paint bounds. A
 * paragraph registers with its nearest clipping ancestor inside the owning [CjkTextSurface], and
 * the ancestor overlay replays only the legal pixels outside the paragraph viewport after its
 * ordinary children have drawn. The surface is the public replay boundary: an outer viewport keeps
 * authority over clipping the complete text surface.
 */
internal class CjkOverhangRegistry(
    private val owner: ViewGroup,
) {
    private class Entry(val view: CjkTextView) {
        private var cachedHost: ViewGroup? = null
        private var cachedPath: Array<View>? = null

        fun pathTo(host: ViewGroup): Array<View>? {
            cachedPath?.takeIf { cachedHost === host && it.isCurrent(view, host) }?.let { return it }
            val path = ArrayList<View>(4)
            var current: View = view
            while (current !== host) {
                val parent = current.parent as? ViewGroup ?: return null
                path += current
                current = parent
            }
            cachedHost = host
            return path.toTypedArray().also { cachedPath = it }
        }

        fun clearPath() {
            cachedHost = null
            cachedPath = null
        }

        private fun Array<View>.isCurrent(view: View, host: ViewGroup): Boolean {
            if (isEmpty() || first() !== view) return false
            for (index in 1..lastIndex) {
                if (this[index - 1].parent !== this[index]) return false
            }
            return last().parent === host
        }
    }

    private inner class AncestorOverlay(
        val host: ViewGroup,
    ) : Drawable(), View.OnLayoutChangeListener {
        val entries = ArrayList<Entry>()
        private val legalBounds = RectF()
        private val normalPassPath = Path()
        private val clipPath = Path()
        private val parentToHost = Matrix()
        private val childToParent = Matrix()
        private val nextToHost = Matrix()
        private val firstTransform = Matrix()
        private val secondTransform = Matrix()

        init {
            updateBounds()
            host.addOnLayoutChangeListener(this)
            host.overlay.add(this)
        }

        override fun draw(canvas: Canvas) {
            var index = 0
            while (index < entries.size) {
                val entry = entries[index++]
                val view = entry.view
                if (
                    !view.canDrawLegalPaintOverhang() ||
                    !view.isShown ||
                    view.width <= 0 ||
                    view.height <= 0
                ) {
                    continue
                }
                val path = entry.pathTo(host) ?: continue
                drawOverhang(
                    canvas = canvas,
                    host = host,
                    view = view,
                    path = path,
                    legalBounds = legalBounds,
                    normalPassPath = normalPassPath,
                    clipPath = clipPath,
                    parentToHost = parentToHost,
                    childToParent = childToParent,
                    nextToHost = nextToHost,
                    firstTransform = firstTransform,
                    secondTransform = secondTransform,
                )
            }
        }

        fun invalidateEntry(entry: Entry) {
            if (entry in entries) invalidateSelf()
        }

        fun dispose() {
            host.removeOnLayoutChangeListener(this)
            host.overlay.remove(this)
            entries.clear()
        }

        override fun onLayoutChange(
            view: View,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
            oldLeft: Int,
            oldTop: Int,
            oldRight: Int,
            oldBottom: Int,
        ) {
            updateBounds()
            invalidateSelf()
        }

        private fun updateBounds() {
            setBounds(0, 0, host.width, host.height)
        }

        @Deprecated("Deprecated in the Android Drawable contract")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun setAlpha(alpha: Int) = Unit

        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    }

    private val entriesByView = LinkedHashMap<CjkTextView, Entry>()
    private val overlaysByHost = LinkedHashMap<ViewGroup, AncestorOverlay>()
    private val hostByEntry = HashMap<Entry, ViewGroup>()

    internal fun register(view: CjkTextView) {
        if (entriesByView.containsKey(view)) return
        val entry = Entry(view)
        entriesByView[view] = entry
        attachToClippingAncestor(entry)
    }

    internal fun unregister(view: CjkTextView) {
        val entry = entriesByView.remove(view) ?: return
        detachFromOverlay(entry)
    }

    /** Notifies the owning ancestor when geometry or paint-only state changes. */
    internal fun onChildStateChanged(view: CjkTextView) {
        val entry = entriesByView[view] ?: return
        val expectedHost = view.nearestClippingAncestor(owner)
        if (hostByEntry[entry] !== expectedHost) {
            detachFromOverlay(entry)
            entry.clearPath()
            attachToClippingAncestor(entry, expectedHost)
        } else {
            expectedHost?.let { overlaysByHost[it]?.invalidateEntry(entry) }
        }
    }

    internal fun requiresReplay(view: View): Boolean {
        val entry = entriesByView[view] ?: return false
        return hostByEntry[entry] != null
    }

    internal fun dispose() {
        overlaysByHost.values.toList().forEach(AncestorOverlay::dispose)
        overlaysByHost.clear()
        hostByEntry.clear()
        entriesByView.values.forEach(Entry::clearPath)
        entriesByView.clear()
    }

    private fun attachToClippingAncestor(
        entry: Entry,
        host: ViewGroup? = entry.view.nearestClippingAncestor(owner),
    ) {
        if (host == null) return
        val overlay = overlaysByHost.getOrPut(host) { AncestorOverlay(host) }
        overlay.entries += entry
        hostByEntry[entry] = host
        overlay.invalidateSelf()
    }

    private fun detachFromOverlay(entry: Entry) {
        val host = hostByEntry.remove(entry) ?: return
        val overlay = overlaysByHost[host] ?: return
        overlay.entries.remove(entry)
        entry.clearPath()
        if (overlay.entries.isEmpty()) {
            overlaysByHost.remove(host)
            overlay.dispose()
        } else {
            overlay.invalidateSelf()
        }
    }
}

private fun CjkTextView.nearestClippingAncestor(owner: ViewGroup): ViewGroup? {
    var current = parent as? ViewGroup
    while (current != null) {
        if (current.clipChildren) return current
        if (current === owner) break
        current = current.parent as? ViewGroup
    }
    return null
}

private fun drawOverhang(
    canvas: Canvas,
    host: ViewGroup,
    view: CjkTextView,
    path: Array<View>,
    legalBounds: RectF,
    normalPassPath: Path,
    clipPath: Path,
    parentToHost: Matrix,
    childToParent: Matrix,
    nextToHost: Matrix,
    firstTransform: Matrix,
    secondTransform: Matrix,
) {
    var alpha = 1f
    for (item in path) alpha *= item.alpha
    if (alpha <= 0f) return

    val save = canvas.save()
    try {
        val hasNormalPassRegion = buildNormalPassVisiblePath(
            host = host,
            path = path,
            outPath = normalPassPath,
            clipPath = clipPath,
            parentToHost = parentToHost,
            childToParent = childToParent,
            nextToHost = nextToHost,
            firstTransform = firstTransform,
            secondTransform = secondTransform,
        )
        if (hasNormalPassRegion) {
            val hasReplayRegion = if (Build.VERSION.SDK_INT >= 26) {
                canvas.clipOutPath(normalPassPath)
            } else {
                @Suppress("DEPRECATION")
                canvas.clipPath(normalPassPath, Region.Op.DIFFERENCE)
            }
            if (!hasReplayRegion) return
        }

        // Overlay canvas is host-local; mirror host -> ... -> child transforms.
        for (index in path.lastIndex downTo 0) applyFrameworkChildTransform(canvas, path[index])

        view.legalPaintBounds(legalBounds)
        if (!canvas.clipRect(legalBounds)) return

        val alphaSave = if (alpha < 0.999f) {
            canvas.saveLayerAlpha(
                legalBounds.left,
                legalBounds.top,
                legalBounds.right,
                legalBounds.bottom,
                (alpha * 255f).roundToInt().coerceIn(0, 255),
            )
        } else {
            -1
        }
        try {
            view.drawLegalPaintOverhang(canvas)
        } finally {
            if (alphaSave >= 0) canvas.restoreToCount(alphaSave)
        }
    } finally {
        canvas.restoreToCount(save)
    }
}

/**
 * Reconstructs the exact region in which the ordinary child pass could already have painted the
 * paragraph. The overlay subtracts this intersection, not a union of ancestor bounds: a
 * non-clipping intermediate parent may preserve overhang, while the nearest clipping ancestor and
 * every active clip-to-padding boundary still constrain the pixels that survive that first pass.
 */
private fun buildNormalPassVisiblePath(
    host: ViewGroup,
    path: Array<View>,
    outPath: Path,
    clipPath: Path,
    parentToHost: Matrix,
    childToParent: Matrix,
    nextToHost: Matrix,
    firstTransform: Matrix,
    secondTransform: Matrix,
): Boolean {
    outPath.reset()
    outPath.addRect(0f, 0f, host.width.toFloat(), host.height.toFloat(), Path.Direction.CW)
    parentToHost.reset()
    var parent = host

    for (index in path.lastIndex downTo 0) {
        if (parent.clipToPadding && !intersectTransformedRect(
                outPath,
                clipPath,
                parentToHost,
                (parent.scrollX + parent.paddingLeft).toFloat(),
                (parent.scrollY + parent.paddingTop).toFloat(),
                (parent.scrollX + parent.width - parent.paddingRight).toFloat(),
                (parent.scrollY + parent.height - parent.paddingBottom).toFloat(),
            )
        ) {
            return false
        }

        val child = path[index]
        childToParent.setFrameworkChildTransform(child, firstTransform, secondTransform)
        nextToHost.setConcat(parentToHost, childToParent)
        if (parent.clipChildren && !intersectTransformedRect(
                outPath,
                clipPath,
                nextToHost,
                0f,
                0f,
                child.width.toFloat(),
                child.height.toFloat(),
            )
        ) {
            return false
        }
        child.clipBounds?.let { bounds ->
            if (!intersectTransformedRect(
                    outPath,
                    clipPath,
                    nextToHost,
                    bounds.left.toFloat(),
                    bounds.top.toFloat(),
                    bounds.right.toFloat(),
                    bounds.bottom.toFloat(),
                )
            ) {
                return false
            }
        }
        parentToHost.set(nextToHost)
        if (index > 0) parent = child as ViewGroup
    }
    return !outPath.isEmpty
}

private fun intersectTransformedRect(
    outPath: Path,
    clipPath: Path,
    transform: Matrix,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
): Boolean {
    if (right <= left || bottom <= top) return false
    clipPath.reset()
    clipPath.addRect(left, top, right, bottom, Path.Direction.CW)
    clipPath.transform(transform)
    if (!outPath.op(clipPath, Path.Op.INTERSECT)) return false
    return !outPath.isEmpty
}

private fun Matrix.setFrameworkChildTransform(
    child: View,
    firstTransform: Matrix,
    secondTransform: Matrix,
) {
    firstTransform.setTranslate(-child.scrollX.toFloat(), -child.scrollY.toFloat())
    secondTransform.setConcat(child.matrix, firstTransform)
    firstTransform.setTranslate(child.left.toFloat(), child.top.toFloat())
    setConcat(firstTransform, secondTransform)
}

/** Mirrors the framework's child placement and property-matrix order. */
private fun applyFrameworkChildTransform(canvas: Canvas, child: View) {
    val scrollX = child.scrollX.toFloat()
    val scrollY = child.scrollY.toFloat()
    canvas.translate(child.left.toFloat() - scrollX, child.top.toFloat() - scrollY)
    if (!child.matrix.isIdentity) {
        canvas.translate(scrollX, scrollY)
        canvas.concat(child.matrix)
        canvas.translate(-scrollX, -scrollY)
    }
}
