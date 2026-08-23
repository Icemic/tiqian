package org.tiqian.web

import kotlinx.browser.document
import org.tiqian.core.BopomofoDecisionInfo
import org.tiqian.core.BopomofoGlyphRole
import org.tiqian.core.ColorSpan
import org.tiqian.core.DecorationKind
import org.tiqian.core.LayoutResult
import org.tiqian.core.RichTextRole
import org.tiqian.core.RichTextSpan
import org.tiqian.core.RubyDecisionInfo
import org.tiqian.core.TextRange
import org.tiqian.shaping.web.WebFontFamilies
import org.tiqian.web.DomParagraphRenderer.BopomofoCssPlacement
import org.tiqian.web.DomParagraphRenderer.BopomofoFontSpec
import org.tiqian.web.DomParagraphRenderer.ClusterDeco
import org.tiqian.web.DomParagraphRenderer.Options
import org.w3c.dom.HTMLElement
import org.w3c.dom.Node

internal fun DomParagraphRenderer.populateTrailingLetterSpacing(
    leaf: HTMLElement,
    text: String,
    letterSpacing: Float,
): Boolean {
    if (text.isEmpty()) {
        leaf.textContent = text
        return false
    }
    leaf.textContent = ""
    leaf.appendChild(document.createTextNode(text))
    val carrier = document.createElement("span") as HTMLElement
    carrier.textContent = "\u00A0"
    carrier.setAttribute("aria-hidden", "true")
    carrier.setAttribute("data-tq-copy-ignore", "true")
    carrier.setAttribute(SPACING_CARRIER_ATTRIBUTE, "true")
    resetEngineInline(carrier)
    carrier.style.apply {
        setProperty("display", "inline-block", "important")
        setProperty("inline-size", "${letterSpacing}px", "important")
        setProperty("height", "0", "important")
        setProperty("line-height", "0", "important")
        setProperty("letter-spacing", "${letterSpacing}px", "important")
        setProperty("overflow", "hidden", "important")
        setProperty("vertical-align", "baseline", "important")
        setProperty("white-space", "pre", "important")
    }
    leaf.appendChild(carrier)
    return true
}

/**
 * 注音 (ADR 0033): the engine places each ㄅㄆㄇ symbol + 调号 in its own box on the
 * base's right. The annotation itself is one inline selectable/copyable span;
 * its children only paint the engine placements inside that span.
 */
internal fun DomParagraphRenderer.bopomofoInlineSpan(
    z: BopomofoDecisionInfo,
    width: Float,
    lineTop: Float,
    lineHeight: Float,
    fonts: WebFontFamilies,
    colorSpans: List<ColorSpan>,
): HTMLElement {
    val span = document.createElement("span") as HTMLElement
    span.setAttribute("data-tq-src", "（${z.text}）")
    span.setAttribute("lang", BOPOMOFO_LANG)
    resetEngineInline(span)
    span.style.apply {
        setProperty("display", "inline-block", "important")
        setProperty("position", "relative", "important")
        setProperty("vertical-align", "top", "important")
        setProperty("width", "${width}px", "important")
        setProperty("height", "${lineHeight}px", "important")
        setProperty("box-sizing", "border-box", "important")
        setProperty("line-height", "${lineHeight}px", "important")
        setProperty("white-space", "pre", "important")
        setProperty("overflow", "visible", "important")
        setProperty("user-select", "all", "important")
        setProperty("-webkit-user-select", "all", "important")
    }

    // 注音-capable face: not just "a CJK font" — the ㄅㄆㄇ symbols are everywhere,
    // but the 注音-shaped 调号 (full-width U+02CA…) only live in TC/system fonts;
    // SC / Noto / Source Han fall back to a small Western accent for the tone.
    val font = BopomofoFontSpec(fonts.forBopomofo(z.fontFamilies), z.fontWeight)
    val color = colorAt(z.baseRange.start, colorSpans)
    val zoneLeft = bopomofoZoneLeft(z)
    for (p in z.placements) {
        val placement = bopomofoCssPlacement(p.text, p.role, font, p.left, p.top, p.width, p.height)
        val glyph = document.createElement("span") as HTMLElement
        glyph.textContent = p.text
        glyph.setAttribute("lang", BOPOMOFO_LANG)
        resetEngineInline(glyph)
        glyph.style.apply {
            setProperty("position", "absolute", "important")
            setProperty("left", "${placement.left - zoneLeft}px", "important")
            setProperty("top", "${placement.top - lineTop}px", "important")
            setProperty("font-family", font.family, "important")
            setProperty("font-style", "normal", "important")
            setProperty("font-weight", "${font.weight}", "important")
            setProperty("font-size", "${placement.fontSize}px", "important")
            setProperty("color", color, "important")
            setProperty("line-height", "${placement.lineHeight}px", "important")
            setProperty("white-space", "pre", "important")
            setProperty("display", "inline-block", "important")
            setProperty("pointer-events", "none", "important")
            setProperty("overflow", "visible", "important")
            setProperty("writing-mode", "vertical-rl", "important")
            setProperty("text-orientation", "upright", "important")
            setProperty("font-feature-settings", "'vert' 1, 'vrt2' 1", "important")
        }
        span.appendChild(glyph)
    }
    return span
}

/** Web mirror of Compose's 注音 placement formulas for Symbol / Tone / Neutral. */
internal fun DomParagraphRenderer.bopomofoCssPlacement(
    text: String,
    role: BopomofoGlyphRole,
    font: BopomofoFontSpec,
    boxLeft: Float,
    boxTop: Float,
    boxWidth: Float,
    boxHeight: Float,
): BopomofoCssPlacement = when (role) {
    BopomofoGlyphRole.Symbol -> BopomofoCssPlacement(
        left = boxLeft,
        top = boxTop,
        fontSize = boxHeight,
        lineHeight = boxWidth,
    )
    BopomofoGlyphRole.Neutral -> {
        val fontSize = boxWidth
        BopomofoCssPlacement(
            left = boxLeft,
            top = boxTop + (boxHeight - fontSize) / 2f,
            fontSize = fontSize,
            lineHeight = boxWidth,
        )
    }
    BopomofoGlyphRole.Tone -> {
        val inkWidthEm = browserVerticalBopomofoToneInkWidthEm(text, font.weight)
        val fontSize = boxWidth * BOPOMOFO_TONE_TARGET_INK_WIDTH_SCALE / inkWidthEm.coerceAtLeast(0.1f)
        BopomofoCssPlacement(
            left = boxLeft,
            top = boxTop + (boxHeight - fontSize) / 2f,
            fontSize = fontSize,
            lineHeight = boxWidth,
        )
    }
}

internal fun DomParagraphRenderer.bopomofoZoneLeft(z: BopomofoDecisionInfo): Float {
    val symbol = z.placements.firstOrNull { it.role == BopomofoGlyphRole.Symbol }
    return symbol?.let { it.left - it.width / 9f }
        ?: z.placements.minOfOrNull { it.left }
        ?: 0f
}

/**
 * DomLineBaselineAlignment: body text stays native inline DOM text for
 * selection/copy, but the inline baseline is shifted onto Tiqian's line
 * baseline so engine-owned ruby / emphasis / interlinear geometry aligns with
 * the glyphs actually drawn by the browser.
 */
internal fun DomParagraphRenderer.cssBaselineOffset(lineHeight: Float, fontSize: Float, fontFamily: String): Float? {
    metricsCtx.font = "normal 400 ${fontSize}px $fontFamily"
    val m = metricsCtx.measureText(BASELINE_METRIC_PROBE)
    val ascent = m.fontBoundingBoxAscent.toFloatOrNull()
        ?: m.actualBoundingBoxAscent.toFloatOrNull()
        ?: return null
    val descent = m.fontBoundingBoxDescent.toFloatOrNull()
        ?: m.actualBoundingBoxDescent.toFloatOrNull()
        ?: return null
    val leading = (lineHeight - (ascent + descent)).coerceAtLeast(0f)
    return leading / 2f + ascent
}

/**
 * `InlineSelectableRuby` (ADR 0032): the 注文 is a real, selectable span placed in
 * DOM right after its base's last cluster (so a copy of a ruby'd base carries it),
 * but absolutely positioned into the engine's 注文 band (centreX / baselineY, in the
 * line's own coordinate space) so it does NOT push the base flow. It shows the plain
 * 拼音 but COPIES parenthesised (`data-tq-src` = 「（拼音）」).
 */
internal fun DomParagraphRenderer.rubyInlineSpan(
    ruby: RubyDecisionInfo,
    lineTop: Float,
    fonts: WebFontFamilies,
    colorSpans: List<ColorSpan>,
): HTMLElement {
    val span = document.createElement("span") as HTMLElement
    span.textContent = ruby.text
    span.setAttribute("data-tq-src", "（${ruby.text}）")
    resetEngineInline(span)
    val family = fonts.forRuby(ruby.fontFamilies)
    metricsCtx.font = "normal ${ruby.fontWeight} ${ruby.fontSize}px $family"
    val ascent = metricsCtx.measureText(ruby.text).fontBoundingBoxAscent.toFloatOrNull()
        ?: (ruby.fontSize * RUBY_ASCENT_RATIO)
    span.style.apply {
        setProperty("position", "absolute", "important")
        setProperty("left", "${ruby.centerX}px", "important")
        setProperty("top", "${ruby.baselineY - lineTop - ascent}px", "important")
        setProperty("transform", "translateX(-50%)", "important")
        setProperty("font-family", family, "important")
        setProperty("font-size", "${ruby.fontSize}px", "important")
        setProperty("font-weight", "${ruby.fontWeight}", "important")
        setProperty("line-height", "1", "important")
        setProperty("white-space", "pre", "important")
        setProperty("color", colorAt(ruby.baseRange.start, colorSpans), "important")
    }
    return span
}

/**
 * EngineOwnedInterlinearLines (ADR 0024): 专名号 / 书名号甲式 use
 * DecorationSegmentInfo directly. CSS wavy underline restarts its wave per
 * inline span, so it cannot represent a continuous 书名号 over multiple CJK
 * clusters.
 */
internal fun DomParagraphRenderer.appendInterlinearLines(
    container: Node,
    result: LayoutResult,
    colorSpans: List<ColorSpan>,
) {
    val segments = result.debug.decorationSegments.filter {
        it.kind == DecorationKind.ProperNoun.name || it.kind == DecorationKind.BookTitle.name
    }
    if (segments.isEmpty()) return

    val svg = document.createElementNS(SVG_NS, "svg")
    svg.setAttribute("aria-hidden", "true")
    svg.setAttribute("data-tq-copy-ignore", "true")
    svg.setAttribute(GEOMETRY_SPAN_ATTRIBUTE, "true")
    svg.setAttribute(
        "style",
        "--tq-overlay-width:${result.size.width}px;--tq-overlay-height:${result.size.height}px",
    )

    val fontSize = result.input.textStyle.fontSize
    val strokeWidth = fontSize * LINE_THICKNESS_EM
    for (seg in segments) {
        val stroke = colorAt(seg.sourceRange.start, colorSpans)
        when (seg.kind) {
            DecorationKind.ProperNoun.name -> {
                val line = document.createElementNS(SVG_NS, "line")
                line.setAttribute("x1", "${seg.left}")
                line.setAttribute("y1", "${seg.top}")
                line.setAttribute("x2", "${seg.right}")
                line.setAttribute("y2", "${seg.top}")
                line.setAttribute("stroke", stroke)
                line.setAttribute("stroke-width", "$strokeWidth")
                line.setAttribute("stroke-linecap", "butt")
                line.setAttribute("data-tq-decoration-line", "true")
                line.setAttribute(
                    "style",
                    "--tq-decoration-color:$stroke;--tq-decoration-stroke-width:${strokeWidth}px",
                )
                svg.appendChild(line)
            }
            DecorationKind.BookTitle.name -> {
                val path = document.createElementNS(SVG_NS, "path")
                path.setAttribute("d", wavyLinePath(seg.left, seg.right, seg.top, fontSize))
                path.setAttribute("fill", "none")
                path.setAttribute("stroke", stroke)
                path.setAttribute("stroke-width", "$strokeWidth")
                path.setAttribute("stroke-linecap", "butt")
                path.setAttribute("stroke-linejoin", "round")
                path.setAttribute("data-tq-decoration-wave", "true")
                path.setAttribute(
                    "style",
                    "--tq-decoration-color:$stroke;--tq-decoration-stroke-width:${strokeWidth}px",
                )
                svg.appendChild(path)
            }
        }
    }

    container.appendChild(svg)
}

/**
 * EmphasisDotAnchorAlignment (ADR 0018 amendment): render the engine-sized
 * dot as a real SVG circle centered on the engine anchor. This is intentionally
 * not CSS `text-emphasis`: native emphasis owns its own position and cannot
 * consume Tiqian's decoration geometry.
 */
internal fun DomParagraphRenderer.appendEmphasisDots(
    container: Node,
    result: LayoutResult,
    colorSpans: List<ColorSpan>,
    sourceSpans: List<DomSourceSpan>,
) {
    val dots = result.debug.decorationDecisions.filter {
        it.applied && it.kind == DecorationKind.Emphasis.name && it.dotDiameter > 0f
    }
    if (dots.isEmpty()) return

    val svg = document.createElementNS(SVG_NS, "svg")
    svg.setAttribute("aria-hidden", "true")
    svg.setAttribute("data-tq-copy-ignore", "true")
    svg.setAttribute(GEOMETRY_SPAN_ATTRIBUTE, "true")
    svg.setAttribute(
        "style",
        "--tq-overlay-width:${result.size.width}px;--tq-overlay-height:${result.size.height}px",
    )

    for (dot in dots) {
        val color = colorSpans.lastOrNull {
            dot.clusterRange.start >= it.start && dot.clusterRange.start < it.end
        }?.let { argbToCss(it.argb) }
            ?: sourceSpans
                .filter {
                    dot.clusterRange.start >= it.range.start &&
                        dot.clusterRange.start < it.range.end &&
                        it.computedColor != null
                }
                .maxByOrNull { it.depth }
                ?.computedColor
            ?: "currentColor"

        val circle = document.createElementNS(SVG_NS, "circle")
        circle.setAttribute("cx", "${dot.anchorX}")
        circle.setAttribute("cy", "${dot.anchorY}")
        circle.setAttribute("r", "${dot.dotDiameter / 2f}")
        circle.setAttribute("fill", color)
        circle.setAttribute("data-tq-decoration-dot", "true")
        circle.setAttribute("style", "--tq-decoration-color:$color")
        svg.appendChild(circle)
    }

    container.appendChild(svg)
}

internal fun DomParagraphRenderer.decoFor(
    range: TextRange,
    colorSpans: List<ColorSpan>,
    richTextSpans: List<RichTextSpan>,
    cssStyleSpans: List<CssRenderStyleSpan>,
    options: Options,
): ClusterDeco {
    val off = range.start
    var color = colorSpans.lastOrNull { off >= it.start && off < it.end }?.let { argbToCss(it.argb) }

    val lines = LinkedHashSet<String>()
    var decorationColor: String? = null
    var textDecorationStyle: String? = null
    var textDecorationThickness: String? = null
    var textUnderlineOffset: String? = null
    var background: String? = null
    var linkTarget: String? = null
    var linkId: String? = null
    for (s in cssStyleSpans) {
        if (off < s.range.start || off >= s.range.end) continue
        s.style.color?.let { color = it }
        s.style.backgroundColor?.let { background = it }
        s.style.textDecorationLine?.split(' ')?.filterTo(lines) { it.isNotBlank() && it != "none" }
        s.style.textDecorationColor?.let { decorationColor = it }
        s.style.textDecorationStyle?.let { textDecorationStyle = it }
        s.style.textDecorationThickness?.let { textDecorationThickness = it }
        s.style.textUnderlineOffset?.let { textUnderlineOffset = it }
    }
    for ((index, s) in richTextSpans.withIndex()) {
        if (off < s.range.start || off >= s.range.end) continue
        when (val role = s.role) {
            RichTextRole.Underline -> { lines += "underline"; s.paint.argb?.let { decorationColor = argbToCss(it) } }
            RichTextRole.LineThrough -> lines += "line-through"
            RichTextRole.Background -> s.paint.argb?.let { background = argbToCss(it) }
            RichTextRole.InlineCode -> if (background == null) {
                background = argbToCss(s.paint.argb ?: options.inlineCodeBackgroundArgb)
            }
            RichTextRole.TechnicalInline -> Unit
            is RichTextRole.Link -> {
                linkTarget = role.target
                linkId = "link-${s.range.start}-${s.range.end}-$index"
            }
        }
    }
    val textDecorationLine = if (lines.isEmpty()) null else lines.joinToString(" ")
    return ClusterDeco(
        color = color,
        background = background,
        textDecorationLine = textDecorationLine,
        decorationColor = decorationColor,
        textDecorationStyle = textDecorationStyle,
        textDecorationThickness = textDecorationThickness,
        textUnderlineOffset = textUnderlineOffset,
        linkTarget = linkTarget,
        linkId = linkId,
    )
}

internal fun DomParagraphRenderer.wavyLinePath(left: Float, right: Float, y: Float, fontSize: Float): String {
    val halfWave = (fontSize * WAVY_HALF_WAVE_EM).coerceAtLeast(1f)
    val amplitude = fontSize * WAVY_AMPLITUDE_EM
    val path = StringBuilder("M $left $y")
    var x = left
    var up = true
    while (x < right - WAVY_ENDPOINT_EPSILON_PX) {
        val rawNextX = x + halfWave
        val nextX = if (rawNextX >= right - WAVY_ENDPOINT_EPSILON_PX) right else rawNextX
        val controlY = if (up) y - amplitude * 2f else y + amplitude * 2f
        path.append(" Q ${(x + nextX) / 2f} $controlY $nextX $y")
        x = nextX
        up = !up
    }
    return path.toString()
}

internal fun DomParagraphRenderer.colorAt(offset: Int, colorSpans: List<ColorSpan>): String =
    colorSpans.lastOrNull { offset >= it.start && offset < it.end }?.let { argbToCss(it.argb) } ?: "currentColor"

/** ARGB Int → CSS `rgba(...)`. */
internal fun DomParagraphRenderer.argbToCss(argb: Int): String {
    val a = ((argb ushr 24) and 0xFF) / 255.0
    val r = (argb ushr 16) and 0xFF
    val g = (argb ushr 8) and 0xFF
    val b = argb and 0xFF
    return "rgba($r, $g, $b, $a)"
}

/**
 * BrowserVerticalBopomofoToneGlyphMetrics:
 * CSS vertical text paints the correct TC `vert` glyph, but the web platform
 * does not expose that glyph's ink bounds to DOM/canvas. These ratios mirror
 * Skia's `Font.getBounds(vertGlyphIds(...))` path with HarfBuzz extents for
 * PingFang TC Regular/Semibold (UPEM 1000), the system TC face preferred by
 * [WebFontFamilies]. Weight interpolation only selects between those two
 * measured profiles; it is a renderer fallback, not a layout decision.
 */
internal fun DomParagraphRenderer.browserVerticalBopomofoToneInkWidthEm(text: String, fontWeight: Int): Float {
    val regular = when (text) {
        "ˇ" -> BOPOMOFO_TONE_CARON_INK_WIDTH_EM_REGULAR
        else -> BOPOMOFO_TONE_SLASH_INK_WIDTH_EM_REGULAR
    }
    val semibold = when (text) {
        "ˇ" -> BOPOMOFO_TONE_CARON_INK_WIDTH_EM_SEMIBOLD
        else -> BOPOMOFO_TONE_SLASH_INK_WIDTH_EM_SEMIBOLD
    }
    val t = ((fontWeight - 400) / 300f).coerceIn(0f, 1f)
    return regular + (semibold - regular) * t
}
