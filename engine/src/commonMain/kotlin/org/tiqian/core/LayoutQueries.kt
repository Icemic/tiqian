package org.tiqian.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private const val INTERLINEAR_UNDERLINE_OFFSET_EM = 0.18f

/**
 * A cluster positioned in layout coordinates. The rectangle is the cluster's line box slice, not
 * glyph ink bounds: selection, hit testing, and accessibility need stable occupied text geometry.
 */
data class PositionedCluster(
    val lineIndex: Int,
    val clusterIndex: Int,
    val range: TextRange,
    /** Occupied text box left edge, used for selection, hit testing, and accessibility. */
    val left: Float,
    val top: Float,
    /** Occupied text box right edge, used for selection, hit testing, and accessibility. */
    val right: Float,
    val bottom: Float,
    val baseline: Float,
    /**
     * Glyph draw origin. This may differ from [left] when the cluster carries
     * leading autospace or consumed leading punctuation glue.
     */
    val drawX: Float = left,
    /**
     * Per-source-offset x boundaries inside this cluster: `range.length + 1` entries running from
     * [left] to [right]. The two ends are always the occupied box edges, so compressed line-edge
     * punctuation (a full-width glyph advancing past its half-width cluster box) and 两端对齐 stretch
     * never overshoot. Interior entries come from the shaped glyph origins, so a caret or selection
     * endpoint inside a proportional Latin word lands on the real letter edge instead of a linear
     * guess. Null when the run did not emit one glyph per source unit; callers then interpolate
     * linearly over the occupied box.
     */
    val sourceStops: List<Float>? = null,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val rect: Rect get() = Rect(left, top, right, bottom)
}

/**
 * A per-line occupied geometry segment covered by a [RichTextSpan]. Segments are derived from the
 * same positioned clusters used for selection/hit testing, not from renderer-side text shaping.
 */
data class RichTextLineSegment(
    val span: RichTextSpan,
    val lineIndex: Int,
    val range: TextRange,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val baseline: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val rect: Rect get() = Rect(left, top, right, bottom)
    /** The marked source range began on an earlier visual line. */
    val continuesFromPreviousLine: Boolean get() = range.start > span.range.start
    /** The marked source range continues onto a later visual line. */
    val continuesOnNextLine: Boolean get() = range.end < span.range.end
}

/** Four physical corner radii resolved from one final rich-text background segment. */
data class RichTextCornerRadii(
    val topLeft: Float,
    val topRight: Float,
    val bottomRight: Float,
    val bottomLeft: Float,
) {
    val isSquare: Boolean
        get() = topLeft == 0f && topRight == 0f && bottomRight == 0f && bottomLeft == 0f

    val isUniform: Boolean
        get() = topLeft == topRight && topRight == bottomRight && bottomRight == bottomLeft
}

/**
 * Resolves `RichTextBackgroundContinuationCorners` from source-continuation geometry. A true
 * source endpoint keeps [RichTextBackgroundPaint.cornerRadius]; an edge split by line breaking uses
 * [RichTextBackgroundPaint.continuationCornerRadius]. [inset] mirrors a centered border stroke that
 * is moved inside the measured box. Renderers consume these four values instead of independently
 * deciding whether a fragment is open or closed.
 */
fun RichTextLineSegment.resolvedBackgroundCornerRadii(inset: Float = 0f): RichTextCornerRadii {
    require(inset.isFinite() && inset >= 0f)
    val boxWidth = (width - inset * 2f).coerceAtLeast(0f)
    val boxHeight = (height - inset * 2f).coerceAtLeast(0f)
    val maximum = minOf(boxWidth / 2f, boxHeight / 2f)
    fun resolve(radius: Float): Float = (radius - inset).coerceIn(0f, maximum)

    val paint = span.paint.background
    val left = resolve(
        if (continuesFromPreviousLine) paint.continuationCornerRadius else paint.cornerRadius,
    )
    val right = resolve(
        if (continuesOnNextLine) paint.continuationCornerRadius else paint.cornerRadius,
    )
    return RichTextCornerRadii(
        topLeft = left,
        topRight = right,
        bottomRight = right,
        bottomLeft = left,
    )
}

/**
 * Plain-text clipboard projection for [range]. Visible layout substitutions and soft-wrap glyphs
 * never enter this string: it starts from source text, then mirrors the Web frontend's annotation
 * contract by appending a fully-selected ruby / 注音 reading in full-width parentheses after its
 * base. A partial selection of a multi-character base does not invent a detached reading.
 */
fun projectTextForCopy(
    source: String,
    range: TextRange,
    rubySpans: List<RubySpan>,
): String = projectAnnotatedTextForCopy(
    source = source,
    range = range,
    annotations = rubySpans.map { it.baseRange to it.text },
)

fun LayoutResult.getTextForCopy(range: TextRange): String = projectAnnotatedTextForCopy(
    source = input.content.text,
    range = range,
    annotations = buildList {
        debug.rubyDecisions.forEach { add(it.baseRange to it.text) }
        debug.bopomofoDecisions.forEach { add(it.baseRange to it.text) }
    },
)

private fun projectAnnotatedTextForCopy(
    source: String,
    range: TextRange,
    annotations: List<Pair<TextRange, String>>,
): String {
    val start = range.start.coerceIn(0, source.length)
    val end = range.end.coerceIn(start, source.length)
    if (start == end) return ""

    val annotationsByEnd = mutableMapOf<Int, MutableList<String>>()
    fun addAnnotation(baseRange: TextRange, text: String) {
        if (baseRange.start < start || baseRange.end > end) return
        annotationsByEnd.getOrPut(baseRange.end) { mutableListOf() } += "（$text）"
    }
    annotations.forEach { (baseRange, text) -> addAnnotation(baseRange, text) }

    return buildString {
        var cursor = start
        for (annotationEnd in annotationsByEnd.keys.sorted()) {
            if (annotationEnd < cursor || annotationEnd > end) continue
            append(source, cursor, annotationEnd)
            annotationsByEnd.getValue(annotationEnd).forEach(::append)
            cursor = annotationEnd
        }
        append(source, cursor, end)
    }
}

/**
 * Returns each cluster's occupied rectangle using the same line/cluster advances consumed by
 * renderers. This is the geometry bridge for links, selection, and accessibility.
 */
fun LayoutResult.positionedClusters(): List<PositionedCluster> =
    lines.flatMapIndexed { lineIndex, line -> positionedClusters(lineIndex, line) }

/** Returns the positioned clusters for [line]. */
fun LayoutResult.positionedClusters(line: LineBox): List<PositionedCluster> {
    val lineIndex = lines.indexOf(line)
    require(lineIndex >= 0) { "line must belong to this LayoutResult." }
    return positionedClusters(lineIndex, line)
}

/**
 * Returns the visible glyph-ink bounds of the laid-out lines, in layout coordinates.
 *
 * This is intentionally separate from [positionedClusters]: ink overhang (notably italic
 * Latin) may extend outside the occupied advance box, but selection, links, and hit testing
 * must continue to use stable occupied geometry. Renderers use this only to avoid clipping
 * real ink that belongs to already-emitted line boxes.
 */
fun LayoutResult.glyphInkBounds(): Rect? {
    val positionsByRange = positionedClusters().associateBy { it.range }
    var left = Float.POSITIVE_INFINITY
    var top = Float.POSITIVE_INFINITY
    var right = Float.NEGATIVE_INFINITY
    var bottom = Float.NEGATIVE_INFINITY
    for (run in glyphRuns) {
        for (glyph in run.glyphs) {
            val bounds = glyph.bounds ?: continue
            val cluster = positionsByRange[glyph.clusterRange] ?: continue
            left = minOf(left, cluster.drawX + glyph.x + bounds.left)
            top = minOf(top, cluster.baseline + glyph.y + bounds.top)
            right = maxOf(right, cluster.drawX + glyph.x + bounds.right)
            bottom = maxOf(bottom, cluster.baseline + glyph.y + bounds.bottom)
        }
    }
    if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) return null
    return Rect(left, top, right, bottom)
}

/**
 * Compose-like line lookup backed by Tiqian line boxes. End offsets attach to the previous line so
 * a caret at paragraph end stays on the final visible line.
 */
fun LayoutResult.getLineForOffset(offset: Int): Int {
    if (lines.isEmpty()) return -1
    val clamped = offset.coerceIn(0, input.content.text.length)
    if (clamped == input.content.text.length) return lines.lastIndex
    return lines.indexOfFirst { clamped >= it.range.start && clamped < it.range.end }
        .takeIf { it >= 0 }
        ?: nearestLineForOffset(clamped)
}

/**
 * Returns the occupied cluster box containing [offset]. A paragraph-end offset returns the final
 * caret rectangle so accessibility callers still get a concrete position.
 */
fun LayoutResult.getBoundingBox(offset: Int): Rect {
    if (lines.isEmpty()) return Rect(0f, 0f, 0f, 0f)
    val clamped = offset.coerceIn(0, input.content.text.length)
    if (clamped == input.content.text.length) return getCursorRect(clamped)
    val positioned = positionedClusters().firstOrNull { clamped >= it.range.start && clamped < it.range.end }
        ?: return getCursorRect(clamped)
    return positioned.rect
}

/**
 * Returns one or more line-box slices covered by [range]. When a source range cuts through a
 * multi-code-unit display cluster, `SourceRangeLinearClusterSplit` divides the cluster box
 * proportionally by source UTF-16 offsets until glyph-level source mapping lands.
 */
fun LayoutResult.getBoundingBoxes(range: TextRange): List<Rect> {
    if (range.isEmpty || lines.isEmpty()) return emptyList()
    val start = range.start.coerceIn(0, input.content.text.length)
    val end = range.end.coerceIn(start, input.content.text.length)
    if (start == end) return emptyList()
    return positionedClusters().mapNotNull { cluster ->
        val sliceStart = max(start, cluster.range.start)
        val sliceEnd = minOf(end, cluster.range.end)
        if (sliceStart >= sliceEnd) return@mapNotNull null
        cluster.sliceRect(sliceStart, sliceEnd)
    }
}

fun LayoutResult.getBoundingBoxes(start: Int, end: Int): List<Rect> =
    getBoundingBoxes(TextRange(start, end))

/**
 * Returns continuous line-local geometry for rich-text spans. A span crossing lines is split at
 * line boundaries; a span cutting through a multi-code-unit display cluster uses the same
 * proportional source split as [getBoundingBoxes].
 */
fun LayoutResult.positionedRichTextSegments(spans: List<RichTextSpan>): List<RichTextLineSegment> {
    if (spans.isEmpty() || lines.isEmpty()) return emptyList()
    val clusters = positionedClusters()
    val textLength = input.content.text.length
    val out = mutableListOf<RichTextLineSegment>()
    for (span in spans) {
        val start = span.range.start.coerceIn(0, textLength)
        val end = span.range.end.coerceIn(start, textLength)
        if (start == end) continue
        // One normalized span instance for ALL of this span's slices — allocated once
        // (not per overlapping cluster) so the merge check compares by identity.
        val normalized = span.copy(range = TextRange(start, end))
        var pending: RichTextLineSegment? = null
        fun flushPending() {
            pending?.let(out::add)
            pending = null
        }
        // Clusters are source-ordered: binary-search the first cluster that reaches
        // the span, and stop past its end — each span scans only its own window.
        var lo = 0
        var hi = clusters.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (clusters[mid].range.end <= start) lo = mid + 1 else hi = mid
        }
        for (i in lo until clusters.size) {
            val cluster = clusters[i]
            if (cluster.range.start >= end) break
            val sliceStart = max(start, cluster.range.start)
            val sliceEnd = minOf(end, cluster.range.end)
            if (sliceStart >= sliceEnd) continue
            val rect = cluster.sliceRect(sliceStart, sliceEnd)
            val next = RichTextLineSegment(
                span = normalized,
                lineIndex = cluster.lineIndex,
                range = TextRange(sliceStart, sliceEnd),
                left = rect.left,
                top = rect.top,
                right = rect.right,
                bottom = rect.bottom,
                baseline = cluster.baseline,
            )
            val current = pending
            if (
                current != null &&
                current.lineIndex == next.lineIndex &&
                current.span === next.span &&
                current.range.end == next.range.start
            ) {
                // Source-contiguous occupied slices merge into one continuous rect, so a decoration
                // or background never acquires an internal sliver (涂). Outer punctuation glue is
                // removed afterwards by trimmedRichTextDecorationSegments, not here.
                pending = current.copy(
                    range = TextRange(current.range.start, next.range.end),
                    right = next.right,
                    top = minOf(current.top, next.top),
                    bottom = max(current.bottom, next.bottom),
                )
            } else {
                flushPending()
                pending = next
            }
        }
        flushPending()
    }
    return out
}

/**
 * Returns underline/strike-through segments with punctuation glue removed at the decoration's outer
 * source edges. `RichTextDecorationPunctuationGlueTrim` removes punctuation glue, which lives in
 * the recorded occupied geometry rather than the glyph. It keeps the occupied geometry unchanged for
 * backgrounds, links, selection and hit testing; glue between two decorated clusters also remains
 * covered so a continuous decoration does not acquire an internal gap.
 */
fun LayoutResult.trimmedRichTextDecorationSegments(
    occupiedSegments: List<RichTextLineSegment>,
): List<RichTextLineSegment> {
    if (occupiedSegments.isEmpty()) return emptyList()
    val decorationSegments = occupiedSegments.filter {
        it.span.role == RichTextRole.Underline || it.span.role == RichTextRole.LineThrough
    }
    if (decorationSegments.isEmpty()) return emptyList()
    return trimOuterPunctuationGlue(decorationSegments).withAdjacentSameStyleClearance()
}

/**
 * Returns one continuous paint box per visual line for background roles. The horizontal box keeps
 * every authored/internal gap between the first and last marked clusters, while its two outer edges
 * exclude autospace, justification and punctuation glue owned by neighbouring text. Vertically it
 * uses the marked clusters' typographic faces rather than the complete line box, so paragraph
 * leading does not inflate a short highlight.
 */
fun LayoutResult.richTextBackgroundSegments(
    occupiedSegments: List<RichTextLineSegment>,
): List<RichTextLineSegment> {
    if (occupiedSegments.isEmpty()) return emptyList()
    val backgrounds = occupiedSegments.filter {
        it.span.role == RichTextRole.Background || it.span.role == RichTextRole.InlineCode
    }
    if (backgrounds.isEmpty()) return emptyList()
    val positioned = positionedClusters()
    val positionedByLine = positioned.groupBy { it.lineIndex }
    val glyphsByRange = glyphRuns.asSequence()
        .flatMap { it.glyphs.asSequence() }
        .groupBy { it.clusterRange }
    val metrics = debug.metricDecisions

    return trimOuterPunctuationGlue(backgrounds).map { segment ->
        val covered = positionedByLine[segment.lineIndex].orEmpty().filter { cluster ->
            cluster.range.end > segment.range.start && cluster.range.start < segment.range.end
        }
        if (covered.isEmpty()) return@map segment

        val first = covered.first()
        val last = covered.last()
        val horizontalPadding = segment.span.paint.background.horizontalPadding
        val leadingPadding = horizontalPadding.takeIf {
            segment.range.start == segment.span.range.start
        } ?: 0f
        val trailingPadding = horizontalPadding.takeIf {
            segment.range.end == segment.span.range.end
        } ?: 0f
        val left = max(segment.left, first.drawX - leadingPadding).coerceAtMost(segment.right)
        val naturalLastRight = glyphsByRange[last.range]
            ?.maxOfOrNull { glyph -> last.drawX + glyph.x + glyph.advance }
            ?: last.right
        val right = minOf(segment.right, naturalLastRight + trailingPadding).coerceAtLeast(left)

        val (faceTop, faceBottom) = when (segment.span.paint.background.metricPolicy) {
            RichTextBackgroundMetricPolicy.MarkedFaces -> markedFaceVerticalBounds(covered, metrics)
            RichTextBackgroundMetricPolicy.UniformTextStyle -> uniformTextStyleVerticalBounds(
                segment = segment,
                metrics = metrics,
                style = resolvedTextStyleAt(segment.range.start),
            )
            RichTextBackgroundMetricPolicy.UniformParagraphStyle -> uniformTextStyleVerticalBounds(
                segment = segment,
                metrics = metrics,
                style = input.textStyle,
            )
        }
        val verticalPadding = segment.span.paint.background.verticalPadding
        segment.copy(
            left = left,
            right = right,
            top = (faceTop - verticalPadding).coerceAtLeast(segment.top),
            bottom = (faceBottom + verticalPadding).coerceAtMost(segment.bottom),
        )
    }.withAdjacentSameStyleClearance()
}

/**
 * `AdjacentSameStyleRichTextClearance`: two source-adjacent runs with the same role and visible
 * paint share one explicit gap. Each side yields half of the configured clearance. Different
 * roles or paints remain untouched; in particular, a highlight beside an underline is not a
 * cross-style avoidance case.
 */
private fun List<RichTextLineSegment>.withAdjacentSameStyleClearance(): List<RichTextLineSegment> {
    if (size < 2) return this
    val byLineAndStart = groupBy { it.lineIndex to it.range.start }
    val byLineAndEnd = groupBy { it.lineIndex to it.range.end }

    fun RichTextLineSegment.sameVisibleStyle(other: RichTextLineSegment): Boolean =
        span.role == other.span.role &&
            span.paint.copy(adjacentSameStyleClearance = 0f) ==
            other.span.paint.copy(adjacentSameStyleClearance = 0f)

    fun RichTextLineSegment.sharedClearance(other: RichTextLineSegment?): Float =
        other
            ?.takeIf(::sameVisibleStyle)
            ?.let { minOf(span.paint.adjacentSameStyleClearance, it.span.paint.adjacentSameStyleClearance) }
            ?: 0f

    return map { segment ->
        val leadingNeighbour = byLineAndEnd[segment.lineIndex to segment.range.start]
            .orEmpty()
            .firstOrNull(segment::sameVisibleStyle)
        val trailingNeighbour = byLineAndStart[segment.lineIndex to segment.range.end]
            .orEmpty()
            .firstOrNull(segment::sameVisibleStyle)
        val left = (segment.left + segment.sharedClearance(leadingNeighbour) / 2f)
            .coerceAtMost(segment.right)
        segment.copy(
            left = left,
            right = (segment.right - segment.sharedClearance(trailingNeighbour) / 2f)
                .coerceAtLeast(left),
        )
    }
}

private fun LayoutResult.markedFaceVerticalBounds(
    covered: List<PositionedCluster>,
    metrics: List<MetricDecisionInfo>,
): Pair<Float, Float> {
    var top = Float.POSITIVE_INFINITY
    var bottom = Float.NEGATIVE_INFINITY
    covered.forEach { cluster ->
        val metric = metrics.lastOrNull { decision ->
            cluster.range.start >= decision.range.start && cluster.range.end <= decision.range.end
        }
        if (metric != null) {
            top = minOf(top, cluster.baseline - metric.layoutAscent)
            bottom = maxOf(bottom, cluster.baseline + metric.layoutDescent)
        } else {
            val style = resolvedTextStyleAt(cluster.range.start)
            top = minOf(top, cluster.baseline - style.fontSize * BACKGROUND_FALLBACK_ASCENT_EM)
            bottom = maxOf(bottom, cluster.baseline + style.fontSize * BACKGROUND_FALLBACK_DESCENT_EM)
        }
    }
    return top to bottom
}

/**
 * `UniformTextStyleBackgroundMetricBox`: fallback faces inside one highlighted run must not change
 * its height. Prefer an ideographic metric for the run's resolved style, then any face carrying the
 * same style, and finally the named em-box fallback used by generic backgrounds.
 */
private fun LayoutResult.uniformTextStyleVerticalBounds(
    segment: RichTextLineSegment,
    metrics: List<MetricDecisionInfo>,
    style: TextStyle,
): Pair<Float, Float> {
    val matchingStyle = metrics.filter { decision ->
        resolvedTextStyleAt(decision.range.start).sameFontMetricStyle(style)
    }
    val reference = matchingStyle.firstOrNull { it.metricBox == IDEOGRAPHIC_EM_BOX_NAME }
        ?: matchingStyle.firstOrNull()
    val ascent = reference?.layoutAscent ?: style.fontSize * BACKGROUND_FALLBACK_ASCENT_EM
    val descent = reference?.layoutDescent ?: style.fontSize * BACKGROUND_FALLBACK_DESCENT_EM
    return segment.baseline - ascent to segment.baseline + descent
}

private fun LayoutResult.resolvedTextStyleAt(offset: Int): TextStyle =
    input.content.spans.lastOrNull { offset >= it.range.start && offset < it.range.end }?.style
        ?: input.textStyle

private fun TextStyle.sameFontMetricStyle(other: TextStyle): Boolean =
    fontFamilies == other.fontFamilies &&
        fontSize == other.fontSize &&
        locale == other.locale &&
        fontWeight == other.fontWeight &&
        italic == other.italic &&
        baselineShift == other.baselineShift

private const val IDEOGRAPHIC_EM_BOX_NAME = "IdeographicEmBox"
private const val BACKGROUND_FALLBACK_ASCENT_EM = 0.88f
private const val BACKGROUND_FALLBACK_DESCENT_EM = 0.12f

private fun LayoutResult.trimOuterPunctuationGlue(
    segments: List<RichTextLineSegment>,
): List<RichTextLineSegment> {
    val geometryByRange = debug.geometryDecisions.associateBy { it.range }
    return segments.map { segment ->
        val line = lines.getOrNull(segment.lineIndex) ?: return@map segment
        val firstCluster = line.clusterRange.asSequence()
            .mapNotNull(clusters::getOrNull)
            .firstOrNull { it.range.end > segment.range.start }
        val lastCluster = line.clusterRange.asSequence()
            .mapNotNull(clusters::getOrNull)
            .lastOrNull { it.range.start < segment.range.end }
        val leadingGlue = firstCluster
            ?.takeIf { segment.range.start == it.range.start }
            ?.let { geometryByRange[it.range] }
            ?.let { (it.leadingGlueNatural - it.leadingGlueConsumed).coerceAtLeast(0f) }
            ?: 0f
        val trailingGlue = lastCluster
            ?.takeIf { segment.range.end == it.range.end }
            ?.let { geometryByRange[it.range] }
            ?.let { (it.trailingGlueNatural - it.trailingGlueConsumed).coerceAtLeast(0f) }
            ?: 0f
        val left = (segment.left + leadingGlue).coerceAtMost(segment.right)
        segment.copy(
            left = left,
            right = (segment.right - trailingGlue).coerceAtLeast(left),
        )
    }
}

/**
 * Resolves the physical center line used by Tiqian's underline/strike-through renderers.
 * Consumers drawing a custom stroke style reuse this query instead of guessing from a line box.
 */
fun LayoutResult.richTextDecorationLineY(
    segment: RichTextLineSegment,
    strokeWidth: Float,
): Float {
    require(strokeWidth.isFinite() && strokeWidth >= 0f) { "strokeWidth must be finite and non-negative" }
    val role = segment.span.role
    require(role == RichTextRole.Underline || role == RichTextRole.LineThrough) {
        "richTextDecorationLineY only supports underline and line-through segments"
    }
    val style = input.content.spans.lastOrNull {
        segment.range.start >= it.range.start && segment.range.start < it.range.end
    }?.style ?: input.textStyle
    val rawLineY = if (role == RichTextRole.Underline) {
        segment.baseline + style.fontSize * INTERLINEAR_UNDERLINE_OFFSET_EM
    } else {
        // `IdeographicMetricBoxLineThroughCenter`: a Chinese strike-through bisects the
        // resolved style's declared 字身框. Prefer its real ideographic metric decision; the
        // shared 0.88/0.12 em fallback is used only when the platform supplied no metrics.
        val (faceTop, faceBottom) = uniformTextStyleVerticalBounds(
            segment = segment,
            metrics = debug.metricDecisions,
            style = style,
        )
        (faceTop + faceBottom) / 2f
    }
    return rawLineY.coerceIn(
        segment.top + strokeWidth / 2f,
        segment.bottom - strokeWidth / 2f,
    )
}

/**
 * Returns a caret rectangle for [offset]. The x position is derived from Tiqian's cluster advances;
 * inside a multi-code-unit cluster, `SourceRangeLinearClusterSplit` places the caret proportionally.
 */
fun LayoutResult.getCursorRect(offset: Int): Rect {
    if (lines.isEmpty()) return Rect(0f, 0f, 0f, 0f)
    val clamped = offset.coerceIn(0, input.content.text.length)
    val lineIndex = getLineForOffset(clamped).coerceAtLeast(0)
    val line = lines[lineIndex]
    val clusters = positionedClusters(lineIndex, line)
    val caretWidth = 1f
    val x = when {
        clusters.isEmpty() -> line.indent
        clamped <= clusters.first().range.start -> clusters.first().left
        clamped >= clusters.last().range.end -> clusters.last().right
        else -> {
            val cluster = clusters.first { clamped >= it.range.start && clamped <= it.range.end }
            cluster.xForOffset(clamped)
        }
    }
    return Rect(x, line.top, x + caretWidth, line.bottom)
}

/**
 * Hit-tests a point against Tiqian line/cluster geometry. `ClusterAdvanceLinearHitTest` chooses the
 * nearest source offset inside a cluster using its occupied advance until glyph-level source maps
 * are available.
 */
fun LayoutResult.getOffsetForPosition(x: Float, y: Float): Int {
    if (lines.isEmpty()) return 0
    val lineIndex = lines.indices.minBy { index ->
        val line = lines[index]
        when {
            y < line.top -> line.top - y
            y > line.bottom -> y - line.bottom
            else -> 0f
        }
    }
    val clusters = positionedClusters(lineIndex, lines[lineIndex])
    if (clusters.isEmpty()) return lines[lineIndex].range.start
    if (x <= clusters.first().left) return clusters.first().range.start
    if (x >= clusters.last().right) return clusters.last().range.end
    val cluster = clusters.firstOrNull { x >= it.left && x <= it.right }
        ?: clusters.minBy { minOf(abs(x - it.left), abs(x - it.right)) }
    return cluster.offsetForX(x)
}

/**
 * Hit-tests a static-text selection endpoint and snaps it to a safe source interaction boundary.
 * This retains per-code-point Latin selection inside word layout atoms without ever returning the
 * middle of a surrogate, combining sequence, emoji ZWJ sequence, or other grouped source unit.
 */
fun LayoutResult.getSelectionOffsetForPosition(x: Float, y: Float): Int {
    if (lines.isEmpty()) return 0
    val lineIndex = lines.indices.minBy { index ->
        val line = lines[index]
        when {
            y < line.top -> line.top - y
            y > line.bottom -> y - line.bottom
            else -> 0f
        }
    }
    val positioned = positionedClusters(lineIndex, lines[lineIndex])
    if (positioned.isEmpty()) {
        return coerceSelectionOffset(lines[lineIndex].range.start, SourceBoundaryBias.Nearest)
    }
    if (x <= positioned.first().left) {
        return coerceSelectionOffset(positioned.first().range.start, SourceBoundaryBias.Nearest)
    }
    if (x >= positioned.last().right) {
        return coerceSelectionOffset(positioned.last().range.end, SourceBoundaryBias.Nearest)
    }
    val cluster = positioned.firstOrNull { x >= it.left && x <= it.right }
        ?: positioned.minBy { minOf(abs(x - it.left), abs(x - it.right)) }
    val rawOffset = cluster.offsetForX(x)
    val backward = coerceSelectionOffset(rawOffset, SourceBoundaryBias.Backward)
    val forward = coerceSelectionOffset(rawOffset, SourceBoundaryBias.Forward)
    if (backward == forward) return backward
    val backwardDistance = abs(getCursorRect(backward).left - x)
    val forwardDistance = abs(getCursorRect(forward).left - x)
    return if (backwardDistance < forwardDistance) backward else forward
}

/** Coerces an external UTF-16 offset to a safe selection/caret boundary. */
fun LayoutResult.coerceSelectionOffset(offset: Int, bias: SourceBoundaryBias): Int {
    return coerceTextSelectionOffset(input.content.text, input.inlineObjects, offset, bias)
}

/** Safe source-boundary coercion for logical documents that outlive their attached layout. */
fun coerceTextSelectionOffset(
    text: String,
    inlineObjects: List<InlineObjectSpan>,
    offset: Int,
    bias: SourceBoundaryBias,
): Int {
    val clamped = offset.coerceIn(0, text.length)
    inlineObjects.firstOrNull { clamped > it.range.start && clamped < it.range.end }?.let { inlineObject ->
        return when (bias) {
            SourceBoundaryBias.Backward -> inlineObject.range.start
            SourceBoundaryBias.Forward -> inlineObject.range.end
            SourceBoundaryBias.Nearest -> {
                if (clamped - inlineObject.range.start < inlineObject.range.end - clamped) {
                    inlineObject.range.start
                } else {
                    inlineObject.range.end
                }
            }
        }
    }
    return text.coerceToInteractionBoundary(clamped, TextRange(0, text.length), bias)
}

/**
 * Expands a safe source offset to the word selected by a static-text double click.
 * `SourceInteractionWordExpansion` joins adjacent letter/digit/connector graphemes, keeps Han
 * ideographs individually selectable, groups adjacent whitespace, and otherwise returns exactly
 * one source interaction unit. It does not depend on shaping-cluster width or line-break atoms.
 */
fun LayoutResult.getSelectionWordBoundary(offset: Int): TextRange {
    val text = input.content.text
    if (text.isEmpty()) return TextRange(0, 0)
    val clamped = offset.coerceIn(0, text.length)
    input.inlineObjects.firstOrNull {
        clamped >= it.range.start && clamped < it.range.end
    }?.let { inlineObject ->
        return inlineObject.range
    }
    val boundaries = text.interactionBoundaries(TextRange(0, text.length))
    val exactIndex = boundaries.binarySearch(clamped)
    val unitIndex = when {
        clamped == text.length -> boundaries.lastIndex - 1
        exactIndex >= 0 -> exactIndex
        else -> (-exactIndex - 2).coerceAtLeast(0)
    }
    val kind = text.selectionWordKind(boundaries[unitIndex], boundaries[unitIndex + 1])
    if (kind == SelectionWordKind.Single) {
        return TextRange(boundaries[unitIndex], boundaries[unitIndex + 1])
    }
    var first = unitIndex
    var last = unitIndex
    while (
        first > 0 &&
        text.selectionWordKind(boundaries[first - 1], boundaries[first]) == kind
    ) {
        first--
    }
    while (
        last + 2 < boundaries.size &&
        text.selectionWordKind(boundaries[last + 1], boundaries[last + 2]) == kind
    ) {
        last++
    }
    return TextRange(boundaries[first], boundaries[last + 1])
}

/**
 * Returns the word/source unit visually under a point. Unlike caret hit testing, a point in the
 * right half of one Han box still belongs to that ideograph instead of the following insertion
 * boundary.
 */
fun LayoutResult.getSelectionWordBoundaryForPosition(x: Float, y: Float): TextRange? {
    if (lines.isEmpty() || input.content.text.isEmpty()) return null
    val lineIndex = lines.indices.minBy { index ->
        val line = lines[index]
        when {
            y < line.top -> line.top - y
            y > line.bottom -> y - line.bottom
            else -> 0f
        }
    }
    val positioned = positionedClusters(lineIndex, lines[lineIndex])
    if (positioned.isEmpty()) return null
    val cluster = positioned.firstOrNull { x >= it.left && x <= it.right }
        ?: positioned.minBy { minOf(abs(x - it.left), abs(x - it.right)) }
    if (cluster.range.isEmpty) return null
    val sourceUnitOffset = cluster.offsetForX(x)
        .coerceIn(cluster.range.start, cluster.range.end - 1)
    return getSelectionWordBoundary(sourceUnitOffset)
}

private enum class SelectionWordKind { Word, Whitespace, Single }

private fun String.selectionWordKind(start: Int, end: Int): SelectionWordKind {
    val codePoint = codePointAtCompat(start, end)
    if (codePoint in SELECTION_MANDATORY_BREAKS) return SelectionWordKind.Single
    if (codePoint <= Char.MAX_VALUE.code && codePoint.toChar().isWhitespace()) {
        return SelectionWordKind.Whitespace
    }
    if (codePoint.isHanIdeograph()) return SelectionWordKind.Single
    if (
        codePoint <= Char.MAX_VALUE.code &&
        (codePoint.toChar().isLetterOrDigit() || codePoint.toChar() in SELECTION_WORD_CONNECTORS)
    ) {
        return SelectionWordKind.Word
    }
    return SelectionWordKind.Single
}

private fun Int.isHanIdeograph(): Boolean =
    this in 0x3400..0x4DBF ||
        this in 0x4E00..0x9FFF ||
        this in 0xF900..0xFAFF ||
        this in 0x20000..0x323AF

private val SELECTION_WORD_CONNECTORS = setOf('_', '\'', '\u2019')
private val SELECTION_MANDATORY_BREAKS = setOf(0x000A, 0x000D, 0x0085, 0x2028, 0x2029)

private fun LayoutResult.positionedClusters(lineIndex: Int, line: LineBox): List<PositionedCluster> {
    val leadingConsumed = debug.geometryDecisions
        .filter { it.leadingGlueConsumed > 0f }
        .associate { it.range to it.leadingGlueConsumed }
    // The applied autospace width is recorded on the decision (an Insert gap is a
    // negative reduction, `AutoSpacePolicy.gapEm` at apply time) — geometry reads
    // the recorded value instead of re-deriving a constant (ADR 0009 amendment).
    val leadingAutoSpaceGaps = debug.autoSpaceDecisions
        .filter { it.side == "leading" }
        .associate { it.clusterRange to -it.totalReduction }
    val glyphsByClusterRange = glyphRuns.asSequence()
        .flatMap { it.glyphs.asSequence() }
        .groupBy { it.clusterRange }

    var x = line.indent
    val positioned = line.clusterRange.mapIndexed { indexInLine, clusterIndex ->
        val cluster = clusters[clusterIndex]
        val leadingGap = if (indexInLine != 0) leadingAutoSpaceGaps[cluster.range] ?: 0f else 0f
        val drawX = x + cluster.leadingLayoutAdvance + cluster.glyphInlineShift + leadingGap -
            (leadingConsumed[cluster.range] ?: 0f)
        // Interior source-offset boundaries from the shaped glyph origins, so a caret/selection
        // endpoint inside a proportional Latin word lands on the real letter edge. Only when the run
        // emitted one glyph per source unit (typical Latin); ligatures/complex runs stay null and
        // callers interpolate linearly. The two ends are always the occupied box edges — a
        // full-width punctuation glyph advancing past its compressed cluster box must not overshoot.
        val right = x + cluster.advance
        val glyphs = glyphsByClusterRange[cluster.range]
        val sourceStops: List<Float>? =
            if (glyphs != null && cluster.range.length > 1 && glyphs.size == cluster.range.length) {
                buildList(cluster.range.length + 1) {
                    add(x)
                    for (gi in 1 until cluster.range.length) add((drawX + glyphs[gi].x).coerceIn(x, right))
                    add(right)
                }
            } else {
                null
            }
        val positioned = PositionedCluster(
            lineIndex = lineIndex,
            clusterIndex = clusterIndex,
            range = cluster.range,
            left = x,
            top = line.top,
            right = right,
            bottom = line.bottom,
            baseline = line.baseline + cluster.baselineShift,
            drawX = drawX,
            sourceStops = sourceStops,
        )
        x += cluster.advance
        positioned
    }
    return withRubySelectionGeometry(positioned, lineIndex)
}

private fun LayoutResult.nearestLineForOffset(offset: Int): Int =
    lines.indices.minBy { index ->
        val line = lines[index]
        when {
            offset < line.range.start -> line.range.start - offset
            offset > line.range.end -> offset - line.range.end
            else -> 0
        }
    }

private fun PositionedCluster.xForOffset(offset: Int): Float {
    if (range.length <= 0) return left
    val i = (offset - range.start).coerceIn(0, range.length)
    sourceStops?.let { return it[i] }
    return left + width * (i.toFloat() / range.length.toFloat())
}

private fun PositionedCluster.offsetForX(x: Float): Int {
    if (range.length <= 0) return range.start
    sourceStops?.let { stops ->
        var best = 0
        var bestDistance = Float.MAX_VALUE
        for (i in stops.indices) {
            val distance = abs(x - stops[i])
            if (distance < bestDistance) {
                bestDistance = distance
                best = i
            }
        }
        return (range.start + best).coerceIn(range.start, range.end)
    }
    if (width <= 0f) return range.start
    val ratio = ((x - left) / width).coerceIn(0f, 1f)
    return (range.start + (ratio * range.length).roundToInt()).coerceIn(range.start, range.end)
}

private fun PositionedCluster.sliceRect(start: Int, end: Int): Rect {
    if (range.length <= 0 || width <= 0f) return rect
    return Rect(xForOffset(start), top, xForOffset(end), bottom)
}

private fun LayoutResult.naturalAdvanceByRange(): Map<TextRange, Float> {
    val out = HashMap<TextRange, Float>()
    for (run in glyphRuns) {
        for (glyph in run.glyphs) {
            out[glyph.clusterRange] = (out[glyph.clusterRange] ?: 0f) + glyph.advance
        }
    }
    return out
}

private fun LayoutResult.rubySpreadByRange(): Map<TextRange, Float> =
    debug.geometryDecisions
        .filter { it.rubySpread != 0f }
        .associate { it.range to it.rubySpread }

private data class SelectionBounds(
    var left: Float,
    var right: Float,
)

private fun LayoutResult.withRubySelectionGeometry(
    positioned: List<PositionedCluster>,
    lineIndex: Int,
): List<PositionedCluster> {
    val rubies = debug.rubyDecisions.filter { it.lineIndex == lineIndex && it.width > 0f }
    if (rubies.isEmpty()) return positioned

    val naturalAdvanceByRange = naturalAdvanceByRange()
    val rubySpreadByRange = rubySpreadByRange()
    val bounds = positioned.map { pc ->
        val rubySpread = rubySpreadByRange[pc.range] ?: 0f
        SelectionBounds(pc.left, (pc.right - rubySpread).coerceAtLeast(pc.left))
    }
    fun centerOf(pc: PositionedCluster): Float {
        val natural = naturalAdvanceByRange[pc.range] ?: (pc.width - (rubySpreadByRange[pc.range] ?: 0f))
        return pc.drawX + natural.coerceAtLeast(0f) / 2f
    }

    for (ruby in rubies) {
        val baseIndices = positioned.indices.filter { index ->
            val range = positioned[index].range
            range.start >= ruby.baseRange.start && range.end <= ruby.baseRange.end
        }
        if (baseIndices.isEmpty()) continue

        val centers = baseIndices.map { centerOf(positioned[it]) }
        val rubyLeft = ruby.centerX - ruby.width / 2f
        val rubyRight = ruby.centerX + ruby.width / 2f
        for (i in baseIndices.indices) {
            val segmentLeft = if (i == 0) {
                rubyLeft
            } else {
                maxOf(rubyLeft, (centers[i - 1] + centers[i]) / 2f)
            }
            val segmentRight = if (i == baseIndices.lastIndex) {
                rubyRight
            } else {
                minOf(rubyRight, (centers[i] + centers[i + 1]) / 2f)
            }
            val bound = bounds[baseIndices[i]]
            bound.left = minOf(bound.left, segmentLeft)
            bound.right = maxOf(bound.right, segmentRight)
        }
    }

    for (i in 0 until bounds.lastIndex) {
        val left = bounds[i]
        val right = bounds[i + 1]
        if (left.right <= right.left) continue
        val boundary = ((centerOf(positioned[i]) + centerOf(positioned[i + 1])) / 2f)
            .coerceIn(minOf(left.left, right.left), maxOf(left.right, right.right))
        left.right = minOf(left.right, boundary).coerceAtLeast(left.left)
        right.left = maxOf(right.left, boundary).coerceAtMost(right.right)
    }

    return positioned.mapIndexed { index, pc ->
        val bound = bounds[index]
        // Ruby redistributes occupied boxes to fill the annotation width, so absolute per-source
        // glyph stops no longer align; selection and bounding boxes interpolate linearly over the
        // redistributed box on ruby lines.
        pc.copy(left = bound.left, right = bound.right, sourceStops = null)
    }
}
