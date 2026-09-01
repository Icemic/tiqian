package org.tiqian.core

/** Paint overhang belonging to already-visible occupied text geometry. */
data class LayoutPaintOverhang(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
)

/**
 * Right clip edge authorized by an engine-selected punctuation hang.
 *
 * Ordinary over-long text remains clipped to [viewportWidth]; only an explicit hanging mark may
 * extend the legal clip edge to its final visual position.
 */
fun LineBox.legalHangingPunctuationClipEdge(viewportWidth: Float): Float =
    if (hangingPunctuationAdvance > 0f) indent + visualWidth else viewportWidth

/**
 * Returns paint overhang for glyph ink and annotations attached to occupied geometry visible in
 * [viewportWidth] x [viewportHeight]. Layout ownership does not move into the frontend: callers use
 * this result only to avoid clipping legal ink emitted by the engine.
 */
fun LayoutResult.visiblePaintOverhang(
    viewportWidth: Float,
    viewportHeight: Float,
    positionedClusters: List<PositionedCluster> = positionedClusters(),
): LayoutPaintOverhang {
    val positionsByRange = positionedClusters.associateBy { it.range }
    var left = 0f
    var top = 0f
    var right = 0f
    var bottom = 0f

    fun includePaint(
        cluster: PositionedCluster,
        paintLeft: Float,
        paintTop: Float,
        paintRight: Float,
        paintBottom: Float,
    ) {
        if (
            cluster.right <= 0f || cluster.left >= viewportWidth ||
            cluster.bottom <= 0f || cluster.top >= viewportHeight
        ) {
            return
        }
        left = maxOf(left, cluster.left - paintLeft)
        top = maxOf(top, cluster.top - paintTop)
        right = maxOf(right, paintRight - cluster.right)
        bottom = maxOf(bottom, paintBottom - cluster.bottom)
    }

    for (run in glyphRuns) {
        for (glyph in run.glyphs) {
            val bounds = glyph.bounds ?: continue
            val cluster = positionsByRange[glyph.clusterRange] ?: continue
            includePaint(
                cluster = cluster,
                paintLeft = cluster.drawX + glyph.x + bounds.left,
                paintTop = cluster.baseline + glyph.y + bounds.top,
                paintRight = cluster.drawX + glyph.x + bounds.right,
                paintBottom = cluster.baseline + glyph.y + bounds.bottom,
            )
        }
    }
    for (dot in debug.decorationDecisions) {
        if (!dot.applied || dot.dotDiameter <= 0f) continue
        val cluster = positionsByRange[dot.clusterRange] ?: continue
        val radius = dot.dotDiameter / 2f
        includePaint(
            cluster = cluster,
            paintLeft = dot.anchorX - radius,
            paintTop = dot.anchorY - radius,
            paintRight = dot.anchorX + radius,
            paintBottom = dot.anchorY + radius,
        )
    }
    for (ruby in debug.rubyDecisions) {
        val base = positionedClusters.filter { positioned ->
            positioned.lineIndex == ruby.lineIndex &&
                positioned.range.start >= ruby.baseRange.start &&
                positioned.range.end <= ruby.baseRange.end
        }
        if (base.isEmpty()) continue
        val occupiedLeft = base.minOf { it.left }
        val occupiedTop = base.minOf { it.top }
        val occupiedRight = base.maxOf { it.right }
        val occupiedBottom = base.maxOf { it.bottom }
        if (
            occupiedRight <= 0f || occupiedLeft >= viewportWidth ||
            occupiedBottom <= 0f || occupiedTop >= viewportHeight
        ) {
            continue
        }
        val paintLeft = ruby.centerX - ruby.width / 2f
        val paintTop = ruby.baselineY - ruby.ascent
        val paintRight = ruby.centerX + ruby.width / 2f
        val paintBottom = ruby.baselineY + ruby.descent
        left = maxOf(left, occupiedLeft - paintLeft)
        top = maxOf(top, occupiedTop - paintTop)
        right = maxOf(right, paintRight - occupiedRight)
        bottom = maxOf(bottom, paintBottom - occupiedBottom)
    }
    return LayoutPaintOverhang(left, top, right, bottom)
}
