package org.tiqian.compose

import android.graphics.Typeface
import android.text.TextPaint
import org.tiqian.core.Cluster
import org.tiqian.core.ColorSpan
import org.tiqian.core.LayoutResult
import org.tiqian.core.TextSpan
import org.tiqian.font.FontRole
import org.tiqian.shaping.android.AndroidTypefaceResolver
import org.tiqian.shaping.android.requiresHanShapingContext
import kotlin.math.abs
import kotlin.math.min

/**
 * `NaturalRunCoalescedDraw` (ADR 0050): the API 23-30 draw plan.
 *
 * API 31+ replays positioned glyph ids through `Canvas.drawGlyphs`; API 23-30 has no positioned
 * batch API and would otherwise record one `drawTextRun` per cluster, so the first display-list
 * recording of a heavily-referenced block scales with cluster count. This plan groups a line's
 * consecutive, same-paint, non-punctuation clusters whose positions are the engine's natural
 * advances into a single `drawTextRun` command, verified to be positionally identical to the
 * per-cluster draw. Everything else stays on the per-cluster path. The plan is built once per
 * geometry (see [AndroidParagraphDrawCache.androidDrawPlan]) and replayed each frame.
 */
internal sealed interface AndroidDrawCommand {
    val paint: AndroidRunPaintKey
    val typeface: Typeface
}

/**
 * The paint state [prepareAndroidGlyphPaint] would install for a cluster, reduced to an equality
 * key. `isFakeBoldText` is always false, and `textSkewX` is derived from `role`+`italic` (both
 * keyed — the synthetic CJK-italic shear), so neither needs its own key field. Two clusters share a
 * key when their resolved color, text size, font role/family/weight/italic and OpenType feature
 * string all match — i.e. when `prepareAndroidGlyphPaint` would leave the paint in the same state.
 */
internal data class AndroidRunPaintKey(
    val color: Int,
    val textSizeBits: Int,
    val role: FontRole,
    val fontFamilies: List<String>,
    val fontWeight: Int,
    val italic: Boolean,
    val fontFeatureSettings: String?,
)

/** A merged run of natural-position clusters drawn with one [drawContextShapedText] call. */
internal class AndroidMergedRunCommand(
    val text: String,
    val startX: Float,
    val baselineY: Float,
    val role: FontRole,
    override val paint: AndroidRunPaintKey,
    override val typeface: Typeface,
) : AndroidDrawCommand

/** A single cluster drawn exactly as the legacy per-cluster path would. */
internal class AndroidSingleClusterCommand(
    val cluster: Cluster,
    val drawX: Float,
    val baselineY: Float,
    val run: AndroidClusterRun,
    override val paint: AndroidRunPaintKey,
    override val typeface: Typeface,
) : AndroidDrawCommand

private class AndroidPlanCluster(
    val cluster: Cluster,
    val drawX: Float,
    val baselineY: Float,
    val run: AndroidClusterRun,
    val paintKey: AndroidRunPaintKey,
    val typeface: Typeface,
    /** Structurally allowed to join a merged run (before the geometric natural-position check). */
    val mergeEligible: Boolean,
    /** Identity of the owning line; a merged run never crosses a line break. */
    val line: Any,
)

/**
 * Builds the [NaturalRunCoalescedDraw] command table for one geometry. The [paint] is the shared
 * glyph paint (already carrying the paragraph text locale); it is used both to resolve the natural
 * run advance during validation and, on replay, to draw — so measurement and draw stay same-source.
 */
internal fun buildAndroidDrawPlan(
    result: LayoutResult,
    replayIndex: LayoutResultReplayIndex,
    color: Int,
    colorSpans: List<ColorSpan>,
    spans: List<TextSpan>,
    typefaces: AndroidTypefaceResolver,
    paint: TextPaint,
): List<AndroidDrawCommand> = tiqianTraceSection("AndroidDrawPlan.build") {
    buildAndroidDrawPlanTraced(result, replayIndex, color, colorSpans, spans, typefaces, paint)
}

private fun buildAndroidDrawPlanTraced(
    result: LayoutResult,
    replayIndex: LayoutResultReplayIndex,
    color: Int,
    colorSpans: List<ColorSpan>,
    spans: List<TextSpan>,
    typefaces: AndroidTypefaceResolver,
    paint: TextPaint,
): List<AndroidDrawCommand> {
    val planClusters = ArrayList<AndroidPlanCluster>()
    result.forEachAndroidPositionedCluster(replayIndex, spans) { line, cluster, drawX, baselineY, run ->
        val resolvedColor = colorSpans.lastOrNull {
            cluster.range.start >= it.start && cluster.range.start < it.end
        }?.argb ?: color
        val paintKey = AndroidRunPaintKey(
            color = resolvedColor,
            textSizeBits = run.style.fontSize.toRawBits(),
            role = run.role,
            fontFamilies = run.style.fontFamilies,
            fontWeight = run.style.fontWeight,
            italic = run.style.italic,
            fontFeatureSettings = run.openTypeFeatures.toAndroidFontFeatureSettings(),
        )
        val typeface = typefaces.resolve(
            run.role,
            run.style.fontFamilies,
            run.style.fontWeight,
            run.style.italic && !synthesizeCjkItalic(run.role, run.style.italic),
        )
        planClusters += AndroidPlanCluster(
            cluster = cluster,
            drawX = drawX,
            baselineY = baselineY,
            run = run,
            paintKey = paintKey,
            typeface = typeface,
            mergeEligible = isAndroidMergeEligible(cluster, run, replayIndex),
            line = line,
        )
    }

    val commands = ArrayList<AndroidDrawCommand>(planClusters.size)
    var i = 0
    while (i < planClusters.size) {
        val anchor = planClusters[i]
        if (!anchor.mergeEligible) {
            commands += anchor.toSingleCommand()
            i += 1
            continue
        }
        var j = i + 1
        while (j < planClusters.size && canJoinAndroidRun(anchor, planClusters[j])) {
            j += 1
        }
        if (j - i >= 2 && isNaturalAndroidRun(planClusters, i, j, paint)) {
            commands += mergedAndroidRunCommand(planClusters, i, j)
        } else {
            for (k in i until j) commands += planClusters[k].toSingleCommand()
        }
        i = j
    }
    return commands
}

/** Replays a prebuilt plan, switching paint state only when the run's paint key changes. */
internal fun replayAndroidDrawPlan(
    canvas: android.graphics.Canvas,
    plan: List<AndroidDrawCommand>,
    paint: TextPaint,
) = tiqianTraceSection("AndroidDrawPlan.replay") {
    var appliedKey: AndroidRunPaintKey? = null
    for (command in plan) {
        if (command.paint != appliedKey) {
            applyAndroidRunPaint(paint, command.paint, command.typeface)
            appliedKey = command.paint
        }
        when (command) {
            is AndroidMergedRunCommand ->
                drawContextShapedText(canvas, command.text, command.startX, command.baselineY, command.role, paint)
            is AndroidSingleClusterCommand ->
                drawAndroidClusterRun(canvas, command.cluster, command.drawX, command.baselineY, command.run, paint)
        }
    }
}

/**
 * Installs exactly the paint state [prepareAndroidGlyphPaint] produces, so a merged run and a
 * per-cluster run drawn from the same key are pixel-equivalent apart from the run boundary.
 */
private fun applyAndroidRunPaint(paint: TextPaint, key: AndroidRunPaintKey, typeface: Typeface) {
    paint.color = key.color
    paint.textSize = Float.fromBits(key.textSizeBits)
    paint.typeface = typeface
    paint.fontFeatureSettings = key.fontFeatureSettings
    paint.isFakeBoldText = false
    // SyntheticCjkItalicSkew: derived from the key's role+italic (both already keyed), so a merged
    // and a per-cluster CJK-italic run install the identical shear (only single clusters here — an
    // italic run is not merge-eligible).
    paint.textSkewX = if (synthesizeCjkItalic(key.role, key.italic)) SYNTHETIC_CJK_ITALIC_SKEW else 0f
}

/**
 * Structural merge exclusions (ADR 0050): CjkPunctuation (glue landing point + context-clipped
 * GSUB draw), italic (context ink overhang), display-substituted clusters (`displayText` differs
 * from source), and clusters whose glyphs mix fallback faces. On API 23-30 the legacy shaper emits
 * no per-glyph render key, so the fallback check reduces to intra-cluster render-key uniformity
 * (always true here) and the geometric natural-position check below is the load-bearing guard.
 */
private fun isAndroidMergeEligible(
    cluster: Cluster,
    run: AndroidClusterRun,
    replayIndex: LayoutResultReplayIndex,
): Boolean {
    if (run.role == FontRole.CjkPunctuation) return false
    if (run.style.italic) return false
    if (cluster.displayText != cluster.text) return false
    // WhitespaceGlueExclusion (ADR 0050 device review): a whitespace cluster is the prime site of
    // engine glue compression (word-space collapse, W↔N autospace) — its engine advance is driven
    // to ~0 while the platform would draw the space at its natural width in a merged run, opening a
    // visible gap after it. Merging across whitespace also has zero recording-cost benefit. Exclude
    // it outright, independent of the geometric validation below.
    if (cluster.displayText.any { it.isWhitespace() }) return false
    val renderKeys = replayIndex.glyphsByClusterRange[cluster.range].orEmpty()
        .mapTo(HashSet()) { it.renderFontKey }
    return renderKeys.size <= 1
}

private fun canJoinAndroidRun(anchor: AndroidPlanCluster, candidate: AndroidPlanCluster): Boolean =
    candidate.mergeEligible &&
        candidate.line === anchor.line &&
        candidate.paintKey == anchor.paintKey &&
        // Mixed-size / non-roman baseline shifts would land at different baselines in one run.
        candidate.baselineY.toRawBits() == anchor.baselineY.toRawBits()

/**
 * Measure/draw-same-source natural-position check (ADR 0050, hardened after device review). The
 * candidate run may merge only when the platform would draw its concatenated text at exactly the
 * engine's per-cluster positions. All three sub-checks share the `min(0.1px * 簇数, 0.5px)`
 * tolerance; any failure keeps the whole run per-cluster:
 * 1. Whole run: the concatenated text's [drawContextShapedText]-measured advance matches the engine
 *    span `末簇 drawX + 末簇 advance − 首簇 drawX`.
 * 2. Per boundary: each interior step `cluster[k+1].drawX − cluster[k].drawX` matches the engine
 *    advance, so no leading glue/autospace shift moved a cluster's draw origin.
 * 3. Per cluster: each cluster's engine advance equals the platform's NATURAL advance for its own
 *    text. Check (2) alone is engine-vs-engine and therefore tautological for a glue-compressed
 *    cluster (a space collapsed to advance≈0 sits at the matching collapsed drawX); (3) is the
 *    load-bearing guard that rejects such a cluster, since a merged draw would lay it out at the
 *    platform's natural width. This is the whitespace-collapse defect the device review caught; the
 *    eligibility [WhitespaceGlueExclusion] already drops the common case, and (3) covers any other
 *    glue-adjusted (justified / autospaced) advance.
 */
private fun isNaturalAndroidRun(
    clusters: List<AndroidPlanCluster>,
    start: Int,
    end: Int,
    paint: TextPaint,
): Boolean {
    val count = end - start
    val tolerance = min(0.1f * count, 0.5f)
    val anchor = clusters[start]
    val role = anchor.run.role
    applyAndroidRunPaint(paint, anchor.paintKey, anchor.typeface)
    val text = buildString { for (k in start until end) append(clusters[k].cluster.displayText) }
    val measured = measuredAndroidRunAdvance(paint, text, role)
    val expected = clusters[end - 1].drawX + clusters[end - 1].cluster.advance - anchor.drawX
    if (abs(measured - expected) > tolerance) return false
    for (k in start until end) {
        if (k < end - 1) {
            val step = clusters[k + 1].drawX - clusters[k].drawX
            if (abs(step - clusters[k].cluster.advance) > tolerance) return false
        }
        val natural = memoizedNaturalAdvance(paint, clusters[k].cluster.displayText, role)
        if (abs(natural - clusters[k].cluster.advance) > tolerance) return false
    }
    return true
}

// `NaturalAdvanceMemo`: the per-cluster engine-vs-platform validation is what makes ADR 0050's
// merge safe, but sweeping getRunAdvance over every cluster concentrated at a heavy block's
// first record (~240ms observed on SD835). CJK running text repeats a small character set, so a
// bounded memo keyed by (text, size, typeface, features) collapses the sweep into hash lookups
// after warmup. Plan building runs on the UI thread only, so the memo needs no locking.
private const val NATURAL_ADVANCE_MEMO_ENTRIES = 4096

private class NaturalAdvanceMemoKey(
    val text: String,
    val sizeBits: Int,
    val typefaceIdentity: Int,
    val features: String?,
) {
    override fun hashCode(): Int =
        ((text.hashCode() * 31 + sizeBits) * 31 + typefaceIdentity) * 31 + (features?.hashCode() ?: 0)
    override fun equals(other: Any?): Boolean = other is NaturalAdvanceMemoKey &&
        text == other.text && sizeBits == other.sizeBits &&
        typefaceIdentity == other.typefaceIdentity && features == other.features
}

private val naturalAdvanceMemo =
    object : LinkedHashMap<NaturalAdvanceMemoKey, Float>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<NaturalAdvanceMemoKey, Float>) =
            size > NATURAL_ADVANCE_MEMO_ENTRIES
    }

private fun memoizedNaturalAdvance(paint: TextPaint, text: String, role: FontRole): Float {
    val key = NaturalAdvanceMemoKey(
        text = text,
        sizeBits = paint.textSize.toRawBits(),
        typefaceIdentity = System.identityHashCode(paint.typeface),
        features = paint.fontFeatureSettings,
    )
    naturalAdvanceMemo[key]?.let { return it }
    val advance = measuredAndroidRunAdvance(paint, text, role)
    naturalAdvanceMemo[key] = advance
    return advance
}

/**
 * The advance [drawContextShapedText] will consume for [text] under [role], measured with the same
 * paint that draws it. Mirrors the Han-context buffer the draw uses so measurement equals draw.
 */
private fun measuredAndroidRunAdvance(paint: TextPaint, text: String, role: FontRole): Float {
    if (text.isEmpty()) return 0f
    if (!requiresHanShapingContext(text, role)) {
        return paint.getRunAdvance(text, 0, text.length, 0, text.length, false, text.length)
    }
    val buffer = "中${text}中"
    val penStart = paint.getRunAdvance(buffer, 0, buffer.length, 0, buffer.length, false, 1)
    val penEnd = paint.getRunAdvance(buffer, 0, buffer.length, 0, buffer.length, false, 1 + text.length)
    return penEnd - penStart
}

private fun mergedAndroidRunCommand(
    clusters: List<AndroidPlanCluster>,
    start: Int,
    end: Int,
): AndroidMergedRunCommand {
    val anchor = clusters[start]
    val text = buildString { for (k in start until end) append(clusters[k].cluster.displayText) }
    return AndroidMergedRunCommand(
        text = text,
        startX = anchor.drawX,
        baselineY = anchor.baselineY,
        role = anchor.run.role,
        paint = anchor.paintKey,
        typeface = anchor.typeface,
    )
}

private fun AndroidPlanCluster.toSingleCommand(): AndroidSingleClusterCommand =
    AndroidSingleClusterCommand(cluster, drawX, baselineY, run, paintKey, typeface)
