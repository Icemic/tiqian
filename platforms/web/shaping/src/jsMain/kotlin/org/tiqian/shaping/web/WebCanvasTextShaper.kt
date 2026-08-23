@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package org.tiqian.shaping.web

import kotlin.JsFun
import kotlinx.browser.document
import org.khronos.webgl.get
import org.tiqian.core.Cluster
import org.tiqian.core.Glyph
import org.tiqian.core.GlyphRun
import org.tiqian.core.Rect
import org.tiqian.core.ShapingDecisionInfo
import org.tiqian.font.CjkDashCapabilityPolicy
import org.tiqian.font.FontMetricSource
import org.tiqian.font.FontMetricsRequest
import org.tiqian.font.FontMetricsResolver
import org.tiqian.font.FontRole
import org.tiqian.font.RawFontMetrics
import org.tiqian.font.StubFontMetricsResolver
import org.tiqian.shaping.ShapingInput
import org.tiqian.shaping.ShapingResult
import org.tiqian.shaping.TextShaper
import org.tiqian.shaping.UNVERIFIED_DISPLAY_SUBSTITUTION_COVERAGE_ISSUE
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement

/**
 * The CJK and Latin CSS font stacks, supplied by the APPLICATION — Tiqian does
 * not pick fonts. This SAME instance MUST feed both the shaper (measure) and the
 * DOM renderer (draw), or advances won't match the drawn glyphs (measure == draw
 * is the whole point; a mismatch is what made an earlier prototype's dash tofu).
 */
class WebFontFamilies(
    /** CSS `font-family` for CJK text / punctuation / symbols. */
    val cjk: String,
    /** CSS `font-family` for Latin text runs. */
    val latin: String,
    /** CSS `font-family` for generic Latin monospace spans such as inline code. */
    val latinMonospace: String = "\"SFMono-Regular\", Menlo, Consolas, \"Liberation Mono\", monospace",
    /** CSS `font-family` for generic CJK serif spans. */
    val cjkSerif: String = "\"Songti SC\", \"Noto Serif CJK SC\", serif",
    /** CSS `font-family` for generic Latin serif spans. */
    val latinSerif: String = "Georgia, \"Times New Roman\", serif",
    /**
     * CSS `font-family` for 注音 (ADR 0033): prefer Traditional Chinese system
     * faces, because they expose the full-size vertical U+02CA/02C7/02CB/02D9 tone
     * forms through the browser's vertical text path. Dedicated Bopomofo fonts stay
     * after TC sans faces: many machines do not ship them, and their metrics are not
     * the fallback profile the web renderer mirrors.
     */
    val bopomofo: String = BOPOMOFO_FALLBACK_FAMILIES.joinToString(", ") { it.cssFamilyToken() },
) {
    private val roleFamilyCache = mutableMapOf<Pair<FontRole, List<String>>, String>()

    fun forRole(role: FontRole, preferredFamilies: List<String> = emptyList()): String {
        val key = role to preferredFamilies
        roleFamilyCache[key]?.let { return it }
        val default = if (role == FontRole.LatinText) latin else cjk
        val resolved = if (preferredFamilies.isEmpty()) {
            default
        } else {
            when (preferredFamilies.singleOrNull()?.lowercase()) {
                "monospace" -> if (role == FontRole.LatinText) latinMonospace else cjk
                "serif" -> if (role == FontRole.LatinText) latinSerif else cjkSerif
                "sans-serif", "sansserif" -> default
                else -> preferredFamilies.joinToString(", ") { it.cssFamilyToken() }
            }
        }
        roleFamilyCache[key] = resolved
        return resolved
    }

    /**
     * Canvas occasionally accepts a webfont as the selected face even when that
     * face intentionally maps an unsupported character to zero advance. DOM text
     * continues through the CSS stack in that case, so measurement must probe the
     * same suffixes instead of hard-coding a family name to exclude.
     */
    fun fallbackStacks(role: FontRole, preferredFamilies: List<String> = emptyList()): List<String> {
        if (preferredFamilies.size <= 1) return listOf(forRole(role, preferredFamilies))
        return preferredFamilies.indices
            .map { index -> preferredFamilies.subList(index, preferredFamilies.size) }
            .map { families -> families.joinToString(", ") { it.cssFamilyToken() } }
            .distinct()
    }

    /** Ruby defaults to the application-provided Latin stack; explicit families override it. */
    fun forRuby(preferredFamilies: List<String> = emptyList()): String =
        forRole(FontRole.LatinText, preferredFamilies)

    /** 注音 face — explicit RubySpan families first, then the 注音-capable [bopomofo] stack. */
    fun forBopomofo(preferredFamilies: List<String> = emptyList()): String =
        if (preferredFamilies.isEmpty()) {
            bopomofo
        } else {
            preferredFamilies.joinToString(", ") { it.cssFamilyToken() } + ", $bopomofo"
        }

    /** For callers holding only the serialized role name (LayoutResult dumps). */
    fun forRoleName(name: String?, preferredFamilies: List<String> = emptyList()): String {
        val role = if (name == FontRole.LatinText.name) FontRole.LatinText else FontRole.CjkText
        return forRole(role, preferredFamilies)
    }

}

/** Browser dash capability after the host-side exact-font path has been resolved. */
data class WebCjkDashCapability(
    val status: String,
    val detail: String? = null,
)

private val BOPOMOFO_FALLBACK_FAMILIES = listOf(
    // Traditional Chinese system sans first: they carry the vertical tone glyphs.
    "PingFang TC",
    "Hiragino Sans CNS",
    "Heiti TC",
    "Microsoft JhengHei UI",
    "Microsoft JhengHei",
    "Noto Sans CJK TC",
    "Source Han Sans TC",
    // Dedicated 注音 fonts remain valid explicit fallbacks, but are not the default
    // metric profile used by the web renderer.
    "Noto Sans Bopomofo",
    "Noto Serif Bopomofo",
    "BpmfGenYoGothic",
    "BpmfGenSenRounded",
    // Ming/Song fallbacks still usually have correct 注音 marks.
    "Apple LiGothic",
    "Apple LiSung",
    "PMingLiU",
    "MingLiU",
    "Noto Serif CJK TC",
    "Source Han Serif TC",
    "sans-serif",
)

private fun String.cssFamilyToken(): String = when (lowercase()) {
    "serif", "sans-serif", "sansserif", "monospace", "cursive", "fantasy", "system-ui" -> this
    else -> if (startsWith("\"") || startsWith("'")) this else "\"$this\""
}

/**
 * Web font metrics paired with [WebCanvasTextShaper].
 *
 * Canvas exposes the browser's actual font box. For CJK roles we derive Tiqian's
 * 字身框 from `ideographicBaseline` when available; for ruby/Latin, the font
 * descent is the edge aligned to the base 字身框 top (ADR 0032). Missing browser
 * metrics fall back to the common stub rather than inventing another constant.
 */
class WebCanvasFontMetricsResolver(
    private val fonts: WebFontFamilies,
) : FontMetricsResolver {

    private val fallback = StubFontMetricsResolver()
    private val cache = mutableMapOf<FontMetricsRequest, RawFontMetrics>()
    private var currentCanvasFont: String? = null

    private val ctx: CanvasRenderingContext2D by lazy {
        val canvas = document.createElement("canvas") as HTMLCanvasElement
        canvas.getContext("2d") as CanvasRenderingContext2D
    }

    override fun resolve(request: FontMetricsRequest): RawFontMetrics {
        // Canvas selects metrics from the role probe and CSS stack, not from
        // the source cluster. Excluding faceSelectionText keeps the cache at
        // one entry per actual typography instance instead of per ideograph.
        val cacheKey = request.copy(faceSelectionText = "")
        cache[cacheKey]?.let { return it }
        val cjkBox = request.role == FontRole.CjkText || request.role == FontRole.CjkPunctuation
        val probe = if (cjkBox) CJK_METRIC_PROBE_TEXT else LATIN_METRIC_PROBE_TEXT
        for (family in fonts.fallbackStacks(request.role, request.fontFamilies)) {
            val cssStyle = if (request.italic) "italic" else "normal"
            val cssFont = "$cssStyle ${request.fontWeight} ${request.fontSize}px $family"
            if (cssFont != currentCanvasFont) {
                ctx.font = cssFont
                currentCanvasFont = ctx.font
            }
            val m = ctx.measureText(probe)
            if (!m.width.isFinite() || m.width <= ZERO_ADVANCE_EPSILON) continue

            val ascent = m.fontBoundingBoxAscent.toFloatOrNull()
                ?: m.actualBoundingBoxAscent.toFloatOrNull()
                ?: continue
            val descent = m.fontBoundingBoxDescent.toFloatOrNull()
                ?: m.actualBoundingBoxDescent.toFloatOrNull()
                ?: continue
            val ideographicDescent = (-m.ideographicBaseline).toFloatOrNull()
            val result = RawFontMetrics(
                ascent = ascent,
                descent = descent,
                leading = 0f,
                source = FontMetricSource.GlyphSampling,
                typoAscent = if (cjkBox && ideographicDescent != null) {
                    (request.fontSize - ideographicDescent).coerceAtLeast(0f)
                } else {
                    null
                },
                typoDescent = if (cjkBox && ideographicDescent != null) {
                    ideographicDescent.coerceAtLeast(0f)
                } else {
                    null
                },
            )
            cache[cacheKey] = result
            return result
        }
        return fallback.resolve(request).also { cache[cacheKey] = it }
    }

    private fun Double.toFloatOrNull(): Float? =
        if (isFinite() && this > 0.0) toFloat() else null

    private companion object {
        private const val CJK_METRIC_PROBE_TEXT = "中"
        private const val LATIN_METRIC_PROBE_TEXT = "Hg"
        private const val ZERO_ADVANCE_EPSILON = 0.01
    }
}

/**
 * `OffscreenMeasureTextShaping` (ADR 0039): the web shaping adapter. It MEASURES
 * with an offscreen 2D canvas — `measureText` for advance, `TextMetrics`
 * ink-box extents for ink bounds — and never rasterizes to screen (that's the
 * DOM renderer's job). The measuring [fonts] MUST be the SAME instance the DOM
 * renderer draws with.
 *
 * Slice 1 scope: plain per-segment advance + ink. `halt` half-width body and
 * Han-context `locl` are unavailable on web canvas (no `fontFeatureSettings`,
 * `ctx.lang` doesn't affect `measureText`), so the engine degrades to
 * policy-derived punctuation geometry exactly as the AWT adapter does (ADR 0014)
 * — a platform limit, not a model change.
 */
class WebCanvasTextShaper(
    private val fonts: WebFontFamilies,
    private val cjkDashCapability: WebCjkDashCapability? = null,
) : TextShaper {

    init {
        installFontLoadInvalidation()
    }

    private data class MeasuredText(
        val advance: Float,
        val bounds: Rect?,
        val requestedFont: String,
        val actualFont: String,
        val boundsAdjustment: String?,
    )

    private data class MeasurementKey(
        val actualFont: String,
        val display: String,
        val featureSignature: String,
        val role: FontRole,
    )

    private var currentCanvasFont: String? = null

    private val ctx: CanvasRenderingContext2D by lazy {
        val canvas = document.createElement("canvas") as HTMLCanvasElement
        canvas.getContext("2d") as CanvasRenderingContext2D
    }

    private val featureMeasureProbe: HTMLElement by lazy {
        (document.createElement("span") as HTMLElement).also { probe ->
            probe.setAttribute("aria-hidden", "true")
            probe.style.apply {
                setProperty("position", "absolute", "important")
                setProperty("left", "-100000px", "important")
                setProperty("top", "0", "important")
                setProperty("visibility", "hidden", "important")
                setProperty("white-space", "pre", "important")
                setProperty("margin", "0", "important")
                setProperty("padding", "0", "important")
                setProperty("border", "0", "important")
            }
            document.body?.appendChild(probe)
        }
    }

    override fun shape(input: ShapingInput): ShapingResult {
        val source = input.text.substring(input.range.start, input.range.end)
        if (isUnverifiedEllipsisDisplaySubstitution(source, input.displayText)) {
            return shapeWithCanvas(
                input,
                capabilityIssue = UNVERIFIED_DISPLAY_SUBSTITUTION_COVERAGE_ISSUE to
                    "CanvasCannotVerifySameFaceU+22EFCoverage",
            )
        }
        if (source == CJK_DASH_SOURCE || input.displayText == TWO_EM_DASH) {
            return shapeWithCanvas(input, capabilityIssue = dashCapabilityIssue())
        }
        return shapeWithCanvas(input)
    }

    private fun dashCapabilityIssue(): Pair<String, String> =
        CjkDashCapabilityPolicy.issueNameFor(cjkDashCapability?.status) to
            CjkDashCapabilityPolicy.issueDetailFor(cjkDashCapability?.status, cjkDashCapability?.detail)

    private fun shapeWithCanvas(
        input: ShapingInput,
        capabilityIssue: Pair<String, String>? = null,
    ): ShapingResult {
        val size = input.style.fontSize
        val key = input.fontDecision.candidate.key
        val source = input.text.substring(input.range.start, input.range.end)
        val display = input.displayText

        val style = if (input.style.italic) "italic" else "normal"
        val stacks = fonts.fallbackStacks(input.fontDecision.role, input.style.fontFamilies)
        val openTypeFeatures = contextualWebOpenTypeFeatures(
            role = input.fontDecision.role,
            display = display,
        )
        var chosenIndex = 0
        val requiresAdvance = display.isNotEmpty() && display.none { it == '\n' || it == '\r' }
        var measured = measure(
            display,
            "$style ${input.style.fontWeight} ${size}px ${stacks.first()}",
            openTypeFeatures,
            input.fontDecision.role,
        )
        if (requiresAdvance && !measured.hasUsableAdvance()) {
            for (index in 1 until stacks.size) {
                val candidate = measure(
                    display,
                    "$style ${input.style.fontWeight} ${size}px ${stacks[index]}",
                    openTypeFeatures,
                    input.fontDecision.role,
                )
                measured = candidate
                chosenIndex = index
                if (candidate.hasUsableAdvance()) break
            }
        }
        val advance = measured.advance
        val bounds = measured.bounds

        val cluster = Cluster(
            range = input.range,
            text = source,
            displayText = display,
            fontKey = key,
            advance = advance,
        )
        val glyph = Glyph(
            id = 0u,
            clusterRange = input.range,
            advance = advance,
            x = 0f,
            bounds = bounds,
        )
        val run = GlyphRun(
            range = input.range,
            fontKey = key,
            glyphs = listOf(glyph),
            advance = advance,
            openTypeFeatures = openTypeFeatures,
        )
        val decision = ShapingDecisionInfo(
            range = input.range,
            sourceText = source,
            displayText = display,
            fontKey = key,
            glyphCount = 1,
            advance = advance,
            source = "OffscreenMeasureTextShaping",
            reason = buildString {
                append("web-canvas-measureText")
                append("; stackIndex=")
                append(chosenIndex)
                append("; requestedFont=")
                append(measured.requestedFont)
                append("; actualFont=")
                append(measured.actualFont)
                measured.boundsAdjustment?.let { adjustment ->
                    append("; inkBounds=")
                    append(adjustment)
                }
                if (openTypeFeatures.isNotEmpty()) {
                    append("; features=")
                    append(openTypeFeatures.joinToString(","))
                    append("; featureMeasure=HiddenDomRange")
                }
                capabilityIssue?.let { (_, detail) ->
                    append("; ")
                    append(detail)
                }
            },
            glyphsWithoutInkBounds = if (bounds == null) 1 else 0,
            capabilityIssue = capabilityIssue?.first,
            featureEvidence = openTypeFeatures.takeIf { it.isNotEmpty() }?.joinToString(","),
        )
        return ShapingResult(listOf(cluster), listOf(run), listOf(decision))
    }

    private fun measure(
        display: String,
        cssFont: String,
        openTypeFeatures: List<String> = emptyList(),
        role: FontRole,
    ): MeasuredText {
        if (cssFont != currentCanvasFont) {
            ctx.font = cssFont
            currentCanvasFont = ctx.font
        }
        val actualFont = ctx.font
        val featureSignature = openTypeFeatures.joinToString(",")
        return measurementCacheGetOrPut(MeasurementKey(actualFont, display, featureSignature, role)) {
            if (featureSignature != PROPORTIONAL_CURLY_QUOTE_FEATURE_SIGNATURE &&
                !canvasAdvanceTrusted(role, cssFont, actualFont)
            ) {
                return@measurementCacheGetOrPut MeasuredText(
                    advance = measureViaHiddenDom(display, cssFont).toFloat(),
                    bounds = null,
                    requestedFont = cssFont,
                    actualFont = actualFont,
                    boundsAdjustment = "CanvasDomAdvanceParityGate",
                )
            }
            val m = ctx.measureText(display)
            val advance = if (featureSignature == PROPORTIONAL_CURLY_QUOTE_FEATURE_SIGNATURE) {
                measureProportionalCurlyQuote(display, cssFont)
            } else {
                m.width
            }
            if (role == FontRole.CjkPunctuation && canvasInkBoundsDegenerate(actualFont)) {
                val fontSizePx = FONT_PX_SIZE_REGEX.find(actualFont)?.groupValues?.get(1)?.toFloatOrNull()
                val rasterized = if (fontSizePx != null && fontSizePx > 0f) {
                    rasterizedInlineInkBounds(display, advance, fontSizePx)
                } else {
                    null
                }
                return@measurementCacheGetOrPut MeasuredText(
                    advance = advance.toFloat(),
                    bounds = rasterized,
                    requestedFont = cssFont,
                    actualFont = actualFont,
                    boundsAdjustment = if (rasterized != null) {
                        "DegenerateCanvasInkBoundsProbe;RasterizedInkBoundsMeasure"
                    } else {
                        "DegenerateCanvasInkBoundsProbe"
                    },
                )
            }
            val canvasBounds = Rect(
                left = -m.actualBoundingBoxLeft.toFloat(),
                top = -m.actualBoundingBoxAscent.toFloat(),
                right = m.actualBoundingBoxRight.toFloat(),
                bottom = m.actualBoundingBoxDescent.toFloat(),
            )
            val normalizedBounds = if (role == FontRole.CjkPunctuation) {
                normalizeSubpixelCanvasInkOverhang(canvasBounds, advance.toFloat())
            } else {
                NormalizedCanvasInkBounds(canvasBounds, null)
            }
            MeasuredText(
                advance = advance.toFloat(),
                bounds = normalizedBounds.bounds,
                requestedFont = cssFont,
                actualFont = actualFont,
                boundsAdjustment = normalizedBounds.adjustment,
            )
        }
    }

    /**
     * `DegenerateCanvasInkBoundsProbe`: WebKit's `actualBoundingBox*`
     * mirrors the advance box for CJK text, which drives the
     * ink-containment floor to the full cell and disables every compression
     * decision. Probe U+3002 (real ink is a corner dot) once per resolved
     * font; degenerate fonts get their punctuation ink measured through
     * `RasterizedInkBoundsMeasure` below instead.
     */
    private fun canvasInkBoundsDegenerate(actualFont: String): Boolean =
        degenerateInkBoundsByFont.getOrPut(actualFont) {
            val probe = ctx.measureText(DEGENERATE_INK_PROBE_TEXT)
            val advance = probe.width
            advance > 0.0 &&
                kotlin.math.abs(probe.actualBoundingBoxLeft) <= DEGENERATE_INK_EPSILON_PX &&
                kotlin.math.abs(probe.actualBoundingBoxRight - advance) <= DEGENERATE_INK_EPSILON_PX
        }

    /**
     * `RasterizedInkBoundsMeasure`: when the metrics API cannot be trusted,
     * draw the glyph into a scratch canvas at RASTER_INK_SCALE× (advance
     * plus one em of overhang margin per side) and scan alpha for the true
     * ink extents on both axes. One rasterization per (font, glyph), shared
     * through the measurement cache.
     */
    private val inkProbeCanvas: HTMLCanvasElement by lazy {
        document.createElement("canvas") as HTMLCanvasElement
    }

    private fun rasterizedInlineInkBounds(display: String, advance: Double, fontSizePx: Float): Rect? {
        val scale = RASTER_INK_SCALE
        val margin = fontSizePx.toDouble()
        val width = ((advance + 2 * margin) * scale).toInt().coerceAtLeast(1)
        val height = (fontSizePx * 2.0 * scale).toInt().coerceAtLeast(1)
        val canvas = inkProbeCanvas
        if (canvas.width < width) canvas.width = width
        if (canvas.height < height) canvas.height = height
        val probeCtx = canvas.getContext("2d") as? CanvasRenderingContext2D ?: return null
        probeCtx.setTransform(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        probeCtx.clearRect(0.0, 0.0, canvas.width.toDouble(), canvas.height.toDouble())
        probeCtx.setTransform(scale, 0.0, 0.0, scale, margin * scale, fontSizePx * 1.25 * scale)
        probeCtx.font = currentCanvasFont ?: return null
        probeCtx.fillText(display, 0.0, 0.0)
        val image = try {
            probeCtx.getImageData(0.0, 0.0, width.toDouble(), height.toDouble())
        } catch (error: Throwable) {
            return null
        }
        val data = image.data
        var minX = -1
        var maxX = -1
        val stride = width * 4
        for (x in 0 until width) {
            var inked = false
            var offset = x * 4 + 3
            val end = height * stride
            while (offset < end) {
                if (data[offset].toInt() != 0) {
                    inked = true
                    break
                }
                offset += stride
            }
            if (!inked) continue
            if (minX < 0) minX = x
            maxX = x
        }
        if (minX < 0) return null
        // Vertical extents come from the same pixels: scan rows across the
        // inked column range so the block axis is measured, not assumed.
        var minY = -1
        var maxY = -1
        for (y in 0 until height) {
            var inked = false
            var offset = y * stride + minX * 4 + 3
            val rowEnd = y * stride + (maxX + 1) * 4
            while (offset < rowEnd) {
                if (data[offset].toInt() != 0) {
                    inked = true
                    break
                }
                offset += 4
            }
            if (!inked) continue
            if (minY < 0) minY = y
            maxY = y
        }
        val baseline = fontSizePx * 1.25
        val left = minX / scale - margin
        val right = (maxX + 1) / scale - margin
        return Rect(
            left = left.toFloat(),
            top = (minY / scale - baseline).toFloat(),
            right = right.toFloat(),
            bottom = ((maxY + 1) / scale - baseline).toFloat(),
        )
    }

    /**
     * `CanvasDomAdvanceParityGate`: Firefox resolves the font stack
     * differently between the canvas parser and DOM style, so `measureText`
     * can report Latin advances from a face the DOM never paints. Probe
     * each resolved font once (canvas vs hidden DOM, same string); past one
     * percent divergence every measurement for that font routes through the
     * hidden-DOM path, still keyed into the bounded measurement cache.
     */
    private fun canvasAdvanceTrusted(role: FontRole, cssFont: String, actualFont: String): Boolean {
        // Gated by role, not code points: the divergence lives in the
        // Latin-side stack every non-CJK role shares. CJK roles are exempt —
        // their advances agree across parsers, a Latin probe string would
        // measure a CJK face's fallback instead of what the run paints, and
        // the exemption keeps CjkPunctuation on the canvas path where the
        // raster ink measurement stays reachable.
        if (role == FontRole.CjkText || role == FontRole.CjkPunctuation) return true
        return canvasAdvanceParityByFont.getOrPut(actualFont) {
            val canvasWidth = ctx.measureText(ADVANCE_PARITY_PROBE_TEXT).width
            val domWidth = measureViaHiddenDom(ADVANCE_PARITY_PROBE_TEXT, cssFont)
            domWidth <= 0.0 ||
                kotlin.math.abs(canvasWidth - domWidth) <=
                domWidth * ADVANCE_PARITY_RELATIVE_EPSILON + ADVANCE_PARITY_ABSOLUTE_EPSILON_PX
        }
    }

    // A dedicated probe element: sharing featureMeasureProbe with the
    // curly-quote feature measurement would leave each function's style
    // pins visible to the other in browsers whose font shorthand does not
    // reset every longhand. Kerning is left at the browser default (`auto`)
    // to match how non-canonical paragraphs paint; canonical paragraphs pin
    // `normal`, which resolves identically in the engines this gate serves.
    private val parityMeasureProbe: HTMLElement by lazy {
        (document.createElement("span") as HTMLElement).also { probe ->
            probe.setAttribute("aria-hidden", "true")
            probe.style.apply {
                setProperty("position", "absolute", "important")
                setProperty("left", "-100000px", "important")
                setProperty("visibility", "hidden", "important")
                setProperty("white-space", "pre", "important")
            }
        }
    }

    private fun measureViaHiddenDom(display: String, cssFont: String): Double {
        val probe = parityMeasureProbe
        if (probe.parentNode == null) document.body?.appendChild(probe)
        probe.textContent = display
        probe.style.setProperty("font", cssFont, "important")
        return probe.getBoundingClientRect().width
    }

    private fun measureProportionalCurlyQuote(display: String, cssFont: String): Double {
        val probe = featureMeasureProbe
        if (probe.parentNode == null) document.body?.appendChild(probe)
        probe.textContent = display
        probe.style.apply {
            setProperty("font", cssFont, "important")
            setProperty("font-variant-east-asian", "proportional-width", "important")
            setProperty("font-feature-settings", "\"palt\" 1", "important")
        }
        return probe.getBoundingClientRect().width
    }

    private fun MeasuredText.hasUsableAdvance(): Boolean =
        advance.isFinite() && advance > ZERO_ADVANCE_EPSILON

    companion object {
        // Probe verdicts live beside the shared measurement cache they
        // qualify, and both invalidate together on webfont arrival below.
        private val degenerateInkBoundsByFont = mutableMapOf<String, Boolean>()
        private val canvasAdvanceParityByFont = mutableMapOf<String, Boolean>()
        private var fontLoadInvalidationInstalled = false

        /**
         * `WebfontArrivalMeasurementInvalidation`: cache keys carry the
         * serialized font string, which cannot tell a fallback-face
         * measurement taken mid-load from one against the loaded face. One
         * FontFaceSet listener drops the cache and probe verdicts when a
         * load batch completes; the runtime's loadingdone re-enhancement
         * re-measures.
         */
        internal fun installFontLoadInvalidation() {
            if (fontLoadInvalidationInstalled) return
            fontLoadInvalidationInstalled = true
            val fonts = document.asDynamic().fonts ?: return
            fonts.addEventListener("loadingdone") { _: dynamic ->
                clearMeasurementCache()
            }
        }

        private const val DEGENERATE_INK_PROBE_TEXT = "\u3002"
        private const val DEGENERATE_INK_EPSILON_PX = 0.1
        private const val RASTER_INK_SCALE = 4.0
        private const val ADVANCE_PARITY_PROBE_TEXT = "Benjamini-Hochberg WAVE fjord, 0x7f."
        private const val ADVANCE_PARITY_RELATIVE_EPSILON = 0.01
        private const val ADVANCE_PARITY_ABSOLUTE_EPSILON_PX = 0.25
        private val FONT_PX_SIZE_REGEX = Regex("""(\d+(?:\.\d+)?)px""")
        private const val CJK_DASH_SOURCE = "——"
        private const val TWO_EM_DASH = "⸺"
        private const val ZERO_ADVANCE_EPSILON = 0.01f
        private const val PROPORTIONAL_CURLY_QUOTE_FEATURE_SIGNATURE = "pwid,palt"
        // BoundedSharedMeasurementCache: shared across every shaper
        // instance so cross-root resizes stay warm (ADR 0039), and bounded
        // so a long-lived page cannot retain every glyph run it has ever
        // measured. A hit reinserts its entry, so eviction drops the least
        // recently used key.
        private const val MEASUREMENT_CACHE_MAX_ENTRIES = 2048
        private val measurementCache = LinkedHashMap<MeasurementKey, MeasuredText>()

        private inline fun measurementCacheGetOrPut(
            key: MeasurementKey,
            compute: () -> MeasuredText,
        ): MeasuredText {
            val hit = measurementCache.remove(key)
            if (hit != null) {
                measurementCache[key] = hit
                return hit
            }
            val value = compute()
            measurementCache[key] = value
            if (measurementCache.size > MEASUREMENT_CACHE_MAX_ENTRIES) {
                val eldest = measurementCache.keys.iterator()
                while (measurementCache.size > MEASUREMENT_CACHE_MAX_ENTRIES && eldest.hasNext()) {
                    eldest.next()
                    eldest.remove()
                }
            }
            return value
        }

        fun clearMeasurementCache() {
            measurementCache.clear()
            degenerateInkBoundsByFont.clear()
            canvasAdvanceParityByFont.clear()
        }

        val measurementCacheSize: Int
            get() = measurementCache.size
    }
}

internal fun isUnverifiedEllipsisDisplaySubstitution(source: String, display: String): Boolean =
    source.isNotEmpty() &&
        source.length == display.length &&
        source.all { it == '\u2026' } &&
        display.all { it == '\u22EF' }

internal data class NormalizedCanvasInkBounds(
    val bounds: Rect,
    val adjustment: String?,
)

/**
 * `SubpixelCanvasInkOverhangClamp`: Canvas `actualBoundingBox*` is rasterizer
 * evidence rather than an outline bound. Firefox can offset the reported CJK
 * punctuation box by one CSS pixel even when Canvas and DOM advances are
 * identical, leaving a subpixel edge as a false glyph overhang. Feeding that
 * noise into the fitted punctuation body can falsely enlarge it and make the
 * DOM replay under-compress punctuation gaps.
 *
 * Keep real overhangs of one CSS pixel or more (italic and synthetic-slant
 * safety still applies). Only clamp smaller excursions back to the measured
 * advance box; the named adjustment is copied into the shaping decision.
 */
internal fun normalizeSubpixelCanvasInkOverhang(
    bounds: Rect,
    advance: Float,
): NormalizedCanvasInkBounds {
    val leftOverhang = (-bounds.left).coerceAtLeast(0f)
    val rightOverhang = (bounds.right - advance).coerceAtLeast(0f)
    val clampLeft = leftOverhang > 0f && leftOverhang < CANVAS_INK_OVERHANG_EVIDENCE_THRESHOLD_PX
    val clampRight = rightOverhang > 0f && rightOverhang < CANVAS_INK_OVERHANG_EVIDENCE_THRESHOLD_PX
    if (!clampLeft && !clampRight) return NormalizedCanvasInkBounds(bounds, null)

    return NormalizedCanvasInkBounds(
        bounds = Rect(
            left = if (clampLeft) 0f else bounds.left,
            top = bounds.top,
            right = if (clampRight) advance else bounds.right,
            bottom = bounds.bottom,
        ),
        adjustment = buildString {
            append("SubpixelCanvasInkOverhangClamp")
            if (clampLeft) append("(left=$leftOverhang)")
            if (clampRight) append("(right=$rightOverhang)")
        },
    )
}

private const val CANVAS_INK_OVERHANG_EVIDENCE_THRESHOLD_PX = 1f
/**
 * ContextualWebCurlyQuoteFeatures: the common classifier has already resolved
 * whether a shared curly quote belongs to Latin or CJK context. The browser
 * adapter requests proportional forms only for the Latin decision and reports
 * that feature list in GlyphRun so DOM paint can replay the same measurement.
 */
private fun contextualWebOpenTypeFeatures(role: FontRole, display: String): List<String> =
    if (role == FontRole.LatinText && display.any { it in '\u2018'..'\u201D' }) {
        listOf("pwid", "palt")
    } else {
        emptyList()
    }
