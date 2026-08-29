package org.tiqian.layout.tooling

import org.tiqian.core.Cluster
import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LayoutInput
import org.tiqian.core.LayoutResult
import org.tiqian.core.LineBox
import org.tiqian.core.PunctuationDecisionInfo
import org.tiqian.core.Rect
import org.tiqian.core.SpacingDecisionInfo
import org.tiqian.core.TiqianTextContent
import org.tiqian.layout.ExplainableStubParagraphLayoutEngine
import org.tiqian.layout.GreedyLineBreaker
import org.tiqian.layout.LookaheadLineBreaker
import org.tiqian.layout.ParagraphDpLineBreaker
import org.tiqian.shaping.ExplainableStubTextShaper
import org.tiqian.shaping.TextShaper
import org.tiqian.shaping.jvm.AwtTextShaper
import org.tiqian.shaping.skia.SkiaTextShaper
import org.tiqian.test.EarlyLayoutFixtures
import org.tiqian.test.LayoutFixture
import java.awt.Color
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.geom.AffineTransform
import java.awt.geom.PathIterator
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.Locale
import javax.imageio.ImageIO

fun main() {
    val shaperMode = ShaperMode.fromEnvironment()
    val textShaper = shaperMode.createShaper()
    val reportItems = mutableListOf<LayoutReportItem>()

    println("shaper=${shaperMode.id} (${shaperMode.description})")
    println()

    EarlyLayoutFixtures.all.forEach { fixture ->
        val hyphenator = if (fixture.useEnglishHyphenation) {
            org.tiqian.linebreak.EnglishHyphenation.enUs
        } else {
            org.tiqian.linebreak.NoHyphenator
        }
        val greedyEngine = ExplainableStubParagraphLayoutEngine(
            lineBreaker = GreedyLineBreaker(),
            textShaper = textShaper,
            hyphenator = hyphenator,
        )
        val lookaheadEngine = ExplainableStubParagraphLayoutEngine(
            lineBreaker = LookaheadLineBreaker(),
            textShaper = textShaper,
            hyphenator = hyphenator,
        )
        val input = LayoutInput(
            content = TiqianTextContent(
                fixture.text,
                lineBreakSpans = fixture.lineBreakSpans,
            ),
            constraints = fixture.constraints,
            paragraphStyle = org.tiqian.core.ParagraphStyle(
                lineHeight = fixture.lineHeight,
                firstLineIndent = fixture.firstLineIndentEm?.let { org.tiqian.core.Ic(it) },
                rubyLineHeightMode = fixture.rubyLineHeightMode,
                lineLengthGrid = fixture.lineLengthGrid,
            ),
            decorations = fixture.decorations,
            rubySpans = fixture.rubySpans,
        )
        val greedyResult = greedyEngine.layout(input)
        val lookaheadResult = lookaheadEngine.layout(input)

        reportItems += LayoutReportItem(fixture, greedyResult, lookaheadResult)
        printFixtureDump(fixture, greedyResult, lookaheadResult)
    }

    val dpItems = DP_COMPARISON_PARAGRAPHS.flatMapIndexed { index, text ->
        DP_COMPARISON_WIDTHS.map { width ->
            val fixture = LayoutFixture(
                id = "dp-compare-${index + 1}-w${width.toInt()}",
                text = text,
                constraints = LayoutConstraints(maxWidth = width),
                notes = "",
                firstLineIndentEm = DP_COMPARISON_INDENT_EM,
            )
            val input = LayoutInput(
                content = TiqianTextContent(text),
                constraints = fixture.constraints,
                paragraphStyle = org.tiqian.core.ParagraphStyle(
                    firstLineIndent = org.tiqian.core.Ic(DP_COMPARISON_INDENT_EM),
                ),
            )
            fun layout(breaker: org.tiqian.layout.LineBreaker) =
                ExplainableStubParagraphLayoutEngine(
                    lineBreaker = breaker,
                    textShaper = textShaper,
                    hyphenator = org.tiqian.linebreak.NoHyphenator,
                ).layout(input)
            DpComparisonItem(
                fixture = fixture,
                lookahead = layout(LookaheadLineBreaker()),
                paragraphDp = layout(ParagraphDpLineBreaker()),
            )
        }
    }

    val reportFile = File("build/reports/tiqian-layout-report/index.html")
    reportFile.parentFile.mkdirs()
    reportFile.writeText(renderHtmlReport(reportItems, dpItems, shaperMode))
    println()
    println("HTML report: ${reportFile.absolutePath}")
}

internal data class LayoutReportItem(
    val fixture: LayoutFixture,
    val greedy: LayoutResult,
    val lookahead: LayoutResult,
)

/**
 * ADR 0041 目检区：同一批真实博客段落、同一宽度下 lookahead 与 paragraph-dp 并排。
 * 判据是肉眼——行内密度是否更匀、相邻行是否不再一紧一松。
 *
 * This lives in the report (not in a shipped frontend) because
 * `ParagraphDpLineBreaker` is `internal` to `layout`: the experimental
 * optimizer must not reach a published artifact while it is still being tuned.
 */
internal data class DpComparisonItem(
    val fixture: LayoutFixture,
    val lookahead: LayoutResult,
    val paragraphDp: LayoutResult,
) {
    /**
     * 目检导航用：断点相同的一批（v3 下真实语料上多数如此）不必逐张看图，
     * 眼睛应该直接跳到断点或行内调整确实不同的那几组。
     */
    val divergence: String
        get() {
            fun breaks(result: LayoutResult) = result.lines.map { it.clusterRange }
            fun widths(result: LayoutResult) = result.lines.map { it.adjustedWidth }
            return when {
                breaks(lookahead) != breaks(paragraphDp) -> "断点不同"
                widths(lookahead) != widths(paragraphDp) -> "断点相同、行内调整不同"
                else -> "一致"
            }
        }
}

/** 段首缩进按中文正文惯例固定 2 字，和 DP 调优探针的 narrow sweep 一致。 */
internal const val DP_COMPARISON_INDENT_EM = 2f

/** 240px ≈ 15 字/行，是 ADR 0041 里 lookahead 留下可见拉伸的窄版心；320px 是常见博客版心。 */
private val DP_COMPARISON_WIDTHS = listOf(240f, 320f)

/** 真实博客段落（《画风清奇的开源许可证》《PWM》《字体更新》）。 */
private val DP_COMPARISON_PARAGRAPHS = listOf(
    "无论你的源代码是否重要，开发者都应当为自己的源代码选择许可证。当然，如果你觉得自己的" +
        "源代码真的很不重要，甚至想跟读你代码的人们开个玩笑，那么可以考虑一下这些画风有毒的" +
        "开源许可证们 (ﾟ∀。)。",
    "和任何一个小众开源许可证一样，WTFPL 并没有被广泛的应用，虽然它是一份 GPL 兼容的许可证，" +
        "甚至还得到了 FSF 的认可（但没得到 OSI 的认可），但是并不被 FSF 与 OSI 推荐使用。" +
        "原因包括：不够严肃、细节过于模糊且解有多种解读方式。",
    "脉冲宽度调制（英语：Pulse-width modulation，缩写：PWM），简称脉宽调制，是用脉波来输出" +
        "模拟信号的一种技术，一般转换后脉波的周期固定，但脉波的工作周期会依模拟信号的大小而改变。",
    "只有一个原因：没有 Serif。如果你曾经看过我的 Blog，你会发现有一段时间我在使用思源宋体" +
        "来作为正文字体，但是，思源宋体用其超级丑的使用体验劝退了我，于是我转身向 MiSans 走去。" +
        "没有 Serif 指的是，没有一个可以在网页上分包，符合再分发协议的衬线字体（直接把方正全家" +
        "都干死了），又是因为汉仪玄宋不适合作为正文字体，所以基本上汉仪全家也死了。你没有一个" +
        "可以使用的规范的 Serif 衬线的宋体。",
)

/**
 * Picks one CJK and one Latin AWT font and uses them to actually draw the
 * engine-computed layout into a PNG. The point of the report is to let
 * the reader compare the engine output to the browser-default rendering;
 * everything else (overlays, boxes, ink dots, decision tags) is debug noise
 * that lives in `<details>` blocks.
 */
private object LayoutReportFontProbe {
    val cjk: String
    val latin: String

    init {
        val available = GraphicsEnvironment.getLocalGraphicsEnvironment()
            .availableFontFamilyNames.toSet()
        cjk = listOf(
            "Source Han Sans CN",
            "Source Han Sans CN VF",
            "Noto Sans CJK SC",
            "PingFang SC",
            "Hiragino Sans GB",
            "Sarasa UI SC",
            "Heiti SC",
            "STHeiti",
        ).firstOrNull { it in available } ?: Font.SERIF
        latin = listOf(
            "Inter",
            "SF Pro Text",
            "SF Pro",
            "Roboto",
            "Helvetica Neue",
        ).firstOrNull { it in available } ?: Font.SANS_SERIF
    }
}

/** Result of the raster step — the PNG plus the natural canvas dimensions in CSS pixels. */
internal data class RasterResult(val dataUri: String, val widthPx: Float, val heightPx: Float)

internal fun rasterizeLayoutToPngSkia(result: LayoutResult, fixture: LayoutFixture, scale: Int = 2): RasterResult {
    val maxWidth = fixture.constraints.maxWidth.coerceAtLeast(1f)
    val height = result.size.height.coerceAtLeast(16f)
    val fontSize = result.input.textStyle.fontSize

    val cjkFont = org.jetbrains.skia.Font(org.tiqian.shaping.skia.SkiaSystemTypefaces.cjk, fontSize)
    val latinFont = org.jetbrains.skia.Font(org.tiqian.shaping.skia.SkiaSystemTypefaces.latin, fontSize)

    // Same canvas padding logic as the AWT raster: engine line boxes use the
    // font-declared typo box (ADR 0002 amendment); the wider hhea ink can still
    // overflow it, so pad for that.
    val fontAscent = maxOf(-cjkFont.metrics.ascent, -latinFont.metrics.ascent)
    val fontDescent = maxOf(cjkFont.metrics.descent, latinFont.metrics.descent)
    val engineBaseline = result.lines.firstOrNull()?.baseline ?: (fontSize / 2f)
    val engineDescent = result.lines.lastOrNull()?.let { it.bottom - it.baseline } ?: (fontSize / 2f)
    val topPad = (fontAscent - engineBaseline).coerceAtLeast(0f)
    val bottomPad = (fontDescent - engineDescent).coerceAtLeast(0f)

    val canvasWidth = maxWidth
    val canvasHeight = height + topPad + bottomPad
    val widthPx = (canvasWidth * scale).toInt().coerceAtLeast(1)
    val heightPx = (canvasHeight * scale).toInt().coerceAtLeast(1)

    val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(widthPx, heightPx)
    val canvas = surface.canvas
    canvas.scale(scale.toFloat(), scale.toFloat())
    canvas.clear(-1)
    val paint = org.jetbrains.skia.Paint().apply { color = 0xFF000000.toInt() }
    val shaper = org.jetbrains.skia.shaper.Shaper.makeShaperDrivenWrapper()

    // Shared cluster-walk (shaping/skia) — same path the Compose
    // renderer uses; topPad shifts the baseline for the raster canvas.
    org.tiqian.shaping.skia.drawTiqianGlyphs(canvas, result, cjkFont, latinFont, paint, shaper, topPad)

    // Emphasis dots (ADR 0018): a filled circle of the engine-decided diameter
    // centred on the anchor (topPad shifts engine canvas coords like the
    // baselines above) — smaller than the `•` glyph so it seats in the gap.
    for (dot in result.debug.decorationDecisions) {
        if (dot.applied && dot.dotDiameter > 0f) {
            canvas.drawCircle(dot.anchorX, topPad + dot.anchorY, dot.dotDiameter / 2f, paint)
        }
    }

    // Decoration segments (ADR 0018/0024): 示亡号 frames (continuation
    // edges stay undrawn), 专名号 straight underlines, 书名号甲式 wavy.
    if (result.debug.decorationSegments.isNotEmpty()) {
        val framePaint = org.jetbrains.skia.Paint().apply {
            color = 0xFF000000.toInt()
            mode = org.jetbrains.skia.PaintMode.STROKE
            strokeWidth = (fontSize / 16f).coerceAtLeast(1f)
        }
        for (seg in result.debug.decorationSegments) {
            val t = topPad + seg.top
            val b = topPad + seg.bottom
            when (seg.kind) {
                "ProperNoun" -> canvas.drawLine(seg.left, t, seg.right, t, framePaint)
                "BookTitle" -> canvas.drawPath(
                    org.tiqian.shaping.skia.wavyLinePath(seg.left, seg.right, t, fontSize),
                    framePaint,
                )
                else -> {
                    canvas.drawLine(seg.left, t, seg.right, t, framePaint)
                    canvas.drawLine(seg.left, b, seg.right, b, framePaint)
                    if (!seg.openStart) canvas.drawLine(seg.left, t, seg.left, b, framePaint)
                    if (!seg.openEnd) canvas.drawLine(seg.right, t, seg.right, b, framePaint)
                }
            }
        }
    }

    val bytes = surface.makeImageSnapshot()
        .encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!
        .bytes
    val dataUri = "data:image/png;base64,${Base64.getEncoder().encodeToString(bytes)}"
    return RasterResult(dataUri = dataUri, widthPx = canvasWidth, heightPx = canvasHeight)
}

internal fun rasterizeLayoutToPng(result: LayoutResult, fixture: LayoutFixture, scale: Int = 2): RasterResult {
    val maxWidth = fixture.constraints.maxWidth.coerceAtLeast(1f)
    val height = result.size.height.coerceAtLeast(16f)
    val fontSize = result.input.textStyle.fontSize

    val cjkFont = Font(LayoutReportFontProbe.cjk, Font.PLAIN, 1).deriveFont(fontSize)
    val latinFont = Font(LayoutReportFontProbe.latin, Font.PLAIN, 1).deriveFont(fontSize)

    // Engine lays the CJK box on the font-declared typo metrics on the real
    // baseline (ADR 0002 amendment). The wider hhea ink (≈18.6/4.6 vs typo
    // 14/2 at 16px) still overflows the engine line box top by
    // `fontAscent - engine.ascent`
    // and the bottom by `fontDescent - engine.descent`. Pad the canvas so the
    // ink fits inside the PNG instead of getting clipped at y=0 / y=height.
    val measureCtx = FontRenderContext(AffineTransform(), true, true)
    val cjkLm = cjkFont.getLineMetrics("中", measureCtx)
    val latinLm = latinFont.getLineMetrics("Mg", measureCtx)
    val fontAscent = maxOf(cjkLm.ascent, latinLm.ascent)
    val fontDescent = maxOf(cjkLm.descent, latinLm.descent)
    val engineBaseline = result.lines.firstOrNull()?.baseline ?: (fontSize / 2f)
    val engineDescent = result.lines.lastOrNull()?.let { it.bottom - it.baseline } ?: (fontSize / 2f)
    val topPad = (fontAscent - engineBaseline).coerceAtLeast(0f)
    val bottomPad = (fontDescent - engineDescent).coerceAtLeast(0f)

    val canvasWidth = maxWidth
    val canvasHeight = height + topPad + bottomPad
    val widthPx = (canvasWidth * scale).toInt().coerceAtLeast(1)
    val heightPx = (canvasHeight * scale).toInt().coerceAtLeast(1)

    val img = BufferedImage(widthPx, heightPx, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    try {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        g.scale(scale.toDouble(), scale.toDouble())
        g.color = Color.WHITE
        g.fillRect(0, 0, (canvasWidth + 1).toInt(), (canvasHeight + 1).toInt())

        // Default autospace gap used by the engine when AutoSpacePolicy isn't
        // surfaced per-cluster. Matches ClreqProfile defaults; if a future
        // profile customises gapEm this rasterizer would over- or under-pad
        // the boundary by the difference. Acceptable for the diagnostic report.
        val defaultAutoSpaceGap = 0.25f * fontSize
        val leadingConsumedAwt = result.debug.geometryDecisions
            .filter { it.leadingGlueConsumed > 0f }
            .associate { it.range to it.leadingGlueConsumed }

        for (line in result.lines) {
            val lineClusters = result.clusters.filter {
                it.range.start >= line.range.start && it.range.end <= line.range.end
            }
            var x = line.indent
            val baselineY = line.baseline + topPad
            for ((clusterIndexInLine, cluster) in lineClusters.withIndex()) {
                val role = result.debug.fontDecisions.firstOrNull {
                // Containment: segmented word clusters sit inside the
                // decision's range (see SkiaLayoutRenderer).
                cluster.range.start >= it.range.start && cluster.range.end <= it.range.end
            }?.role
                g.font = when (role) {
                    "LatinText" -> latinFont
                    else -> cjkFont
                }
                g.color = Color.BLACK

                // A CJK↔Latin Insert gap (autospace side leading) shifts the
                // glyph right by a quarter em; the trailing gap is already in
                // cluster.advance. Consumed leading glue shifts left.
                val isLineStart = clusterIndexInLine == 0
                val leadingGap = if (
                    !isLineStart && result.debug.autoSpaceDecisions.any {
                        it.clusterRange == cluster.range && it.side == "leading"
                    }
                ) defaultAutoSpaceGap else 0f

                if (cluster.displayText.isNotEmpty()) {
                    val leadingShift = leadingConsumedAwt[cluster.range] ?: 0f
                    g.drawString(cluster.displayText, x + leadingGap - leadingShift, baselineY)
                }
                x += cluster.advance
            }
            // LineEndHangingHyphen (ADR 0029): hyphen hangs past the content end.
            if (line.hyphenAdvance > 0f) {
                g.drawString("-", x, baselineY)
            }
        }

        // Emphasis dots — a filled circle of the engine-decided diameter, the
        // same dot the Skia raster and Compose renderer draw.
        for (dot in result.debug.decorationDecisions) {
            if (!dot.applied || dot.dotDiameter <= 0f) continue
            val dotDiameter = dot.dotDiameter
            g.fillOval(
                (dot.anchorX - dotDiameter / 2f).toInt(),
                (topPad + dot.anchorY - dotDiameter / 2f).toInt(),
                dotDiameter.toInt().coerceAtLeast(2),
                dotDiameter.toInt().coerceAtLeast(2),
            )
        }

        // Decoration segments: 示亡号 frames (continuation edges stay
        // undrawn), 专名号 straight underlines, 书名号甲式 wavy (zigzag
        // approximation — the AWT raster is the legacy debug view).
        for (seg in result.debug.decorationSegments) {
            val t = (topPad + seg.top).toInt()
            val b = (topPad + seg.bottom).toInt()
            val l = seg.left.toInt()
            val r = seg.right.toInt()
            when (seg.kind) {
                "ProperNoun" -> g.drawLine(l, t, r, t)
                "BookTitle" -> {
                    // Same rounded quad-curve waveform as the Skia path
                    // (ADR 0024 amendment: 圆形波浪为简体默认形态) — the two
                    // renderers must not disagree on the wave shape.
                    val halfWave = (fontSize * 0.2f).coerceAtLeast(1f)
                    val amplitude = fontSize * 0.06f
                    val endpointEpsilon = 0.01f
                    val path = java.awt.geom.Path2D.Float()
                    val yLine = (topPad + seg.top)
                    path.moveTo(seg.left.toDouble(), yLine.toDouble())
                    var x = seg.left
                    var up = true
                    while (x < seg.right - endpointEpsilon) {
                        val rawNextX = x + halfWave
                        val nextX = if (rawNextX >= seg.right - endpointEpsilon) seg.right else rawNextX
                        val controlY = if (up) yLine - amplitude * 2f else yLine + amplitude * 2f
                        path.quadTo(
                            ((x + nextX) / 2f).toDouble(),
                            controlY.toDouble(),
                            nextX.toDouble(),
                            yLine.toDouble(),
                        )
                        x = nextX
                        up = !up
                    }
                    g.draw(path)
                }
                else -> {
                    g.drawLine(l, t, r, t)
                    g.drawLine(l, b, r, b)
                    if (!seg.openStart) g.drawLine(l, t, l, b)
                    if (!seg.openEnd) g.drawLine(r, t, r, b)
                }
            }
        }
    } finally {
        g.dispose()
    }

    val bytes = ByteArrayOutputStream().use {
        ImageIO.write(img, "PNG", it)
        it.toByteArray()
    }
    val dataUri = "data:image/png;base64,${Base64.getEncoder().encodeToString(bytes)}"
    return RasterResult(dataUri = dataUri, widthPx = canvasWidth, heightPx = canvasHeight)
}

internal enum class ShaperMode(
    val id: String,
    val description: String,
) {
    JvmAwt(
        id = "jvm-awt",
        description = "JVM AWT Font.layoutGlyphVector real advance",
    ),
    Skia(
        id = "skia",
        description = "Skiko TextLine real advance + ink bounds",
    ),
    Stub(
        id = "stub",
        description = "deterministic nominal em advance",
    );

    fun createShaper(): TextShaper =
        when (this) {
            JvmAwt -> AwtTextShaper()
            Skia -> SkiaTextShaper()
            Stub -> ExplainableStubTextShaper()
        }

    companion object {
        fun fromEnvironment(): ShaperMode {
            val configured = System.getenv("TIQIAN_LAYOUT_REPORT_SHAPER")
                ?: System.getenv("TIQIAN_PLAYGROUND_SHAPER")
            return when (configured?.lowercase(Locale.ROOT)) {
                "stub" -> Stub
                "skia", "skiko" -> Skia
                "jvm", "jvm-awt", "awt", null, "" -> JvmAwt
                else -> JvmAwt
            }
        }
    }
}

private fun printFixtureDump(fixture: LayoutFixture, greedy: LayoutResult, lookahead: LayoutResult) {
    println("${fixture.id}:")
    println("  text=${fixture.text}")
    printEngineDump("greedy   ", greedy)
    printEngineDump("lookahead", lookahead)
    if (greedy.debug.spacingDecisions.isNotEmpty()) {
        println("  spacing (paragraph-wide, identical across engines):")
        greedy.debug.spacingDecisions.forEach { println("    ${it.compactDump()}") }
    }
    if (greedy.debug.autoSpaceDecisions.isNotEmpty()) {
        println("  autospace (paragraph-wide, identical across engines):")
        greedy.debug.autoSpaceDecisions.forEach {
            println(
                "    ${it.clusterRange.start}-${it.clusterRange.end} side=${it.side} boundary=${it.boundaryRole} " +
                    "affected=${it.charactersAffected} reduction=${it.totalReduction} ${it.reason}",
            )
        }
    }
    println()
}

private fun printEngineDump(label: String, result: LayoutResult) {
    val totalVisual = result.lines.sumOf { it.visualWidth.toDouble() }.toFloat()
    val repairs = result.debug.lineDecisions.count { it.repair != null }
    val justifications = result.debug.justificationDecisions.count { it.allocations.isNotEmpty() }
    println(
        "  [$label] size=${result.size.width.oneDecimal()}x${result.size.height.oneDecimal()} lines=${result.lines.size} visual-sum=${totalVisual.oneDecimal()} repairs=$repairs justifications=$justifications",
    )
    result.debug.firstLineIndentDecision?.let { f ->
        if (f.source != "Explicit") {
            println("    firstindent ${f.resolvedEm.oneDecimal()}字 measure=${f.measureEm.oneDecimal()}字 threshold=${f.thresholdEm.oneDecimal()}字 ${f.source}")
        }
    }
    result.debug.lineLengthGridDecision?.let { g ->
        if (g.enabled && g.slack > 0f) {
            println(
                "    grid container=${g.containerWidth.oneDecimal()} measure=${g.measure.oneDecimal()}(${g.cells}字) " +
                    "slack=${g.slack.oneDecimal()} body=${g.bodyAlignment}@${g.bodyOffset.oneDecimal()}",
            )
        }
    }
    result.lines.forEachIndexed { lineIndex, line ->
        val repair = result.debug.lineDecisions.getOrNull(lineIndex)?.repair
        val justify = result.debug.justificationDecisions.firstOrNull { it.lineRange == line.range }
        val repairTag = if (repair != null) " repair=$repair" else ""
        val justifyTag = if (justify != null && justify.allocations.isNotEmpty()) {
            val kinds = justify.allocations.map { it.kind }.distinct().joinToString("+")
            " justify=$kinds(+${(justify.deficitBefore - justify.deficitAfter).oneDecimal()})"
        } else ""
        val indentTag = if (line.indent > 0f) " indent=${line.indent.oneDecimal()}" else ""
        val hyphenTag = if (line.hyphenAdvance > 0f) " hyphen=${line.hyphenAdvance.oneDecimal()}" else ""
        println(
            "    line[$lineIndex]$indentTag$hyphenTag adjusted=${line.adjustedWidth.oneDecimal()} visual=${line.visualWidth.oneDecimal()} range=${line.range.start}-${line.range.end}$repairTag$justifyTag",
        )
    }
}

internal fun SpacingDecisionInfo.compactDump(): String =
    "${range.start}-${range.end} '$leftChar$rightChar' " +
        "naturalInner=${naturalInnerGlue.oneDecimal()} adjustedInner=${adjustedInnerGlue.oneDecimal()} " +
        "reduction=${reduction.oneDecimal()} target=${reductionTargetRange.start}-${reductionTargetRange.end} $reason"

internal fun Cluster.compactDump(): String =
    "${range.start}-${range.end} '$displayText' ${advance.oneDecimal()} $fontKey"
