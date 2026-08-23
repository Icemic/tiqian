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

internal fun renderHtmlReport(
    items: List<LayoutReportItem>,
    dpItems: List<DpComparisonItem>,
    shaperMode: ShaperMode,
): String =
    buildString {
        appendLine("<!doctype html>")
        appendLine("<html lang=\"zh-Hans\">")
        appendLine("<head>")
        appendLine("<meta charset=\"utf-8\">")
        appendLine("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        appendLine("<title>提椠 layout report</title>")
        appendLine(
            """
            <style>
              :root {
                --fg: #1f2328;
                --muted: #596168;
                --bg: #f7f7f4;
                --panel: #ffffff;
                --rule: #d8d4ca;
                --baseline: rgba(196, 80, 60, 0.55);
                --linebox: rgba(80, 120, 200, 0.45);
                --glyph-cjk: rgba(108, 168, 230, 0.18);
                --glyph-cjk-border: rgba(80, 130, 200, 0.45);
                --glyph-punct: rgba(232, 174, 80, 0.22);
                --glyph-punct-border: rgba(195, 134, 50, 0.55);
                --glyph-latin: rgba(140, 200, 140, 0.18);
                --glyph-latin-border: rgba(80, 160, 80, 0.50);
                --ink: rgba(210, 55, 55, 0.78);
                --ink-fill: rgba(210, 55, 55, 0.10);
              }
              body { margin: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; background: var(--bg); color: var(--fg); }
              main { max-width: 1280px; margin: 0 auto; padding: 32px 24px 64px; }
              h1 { font-size: 28px; margin: 0 0 8px; }
              .intro { color: var(--muted); margin: 0 0 24px; font-size: 14px; max-width: 80ch; }
              section { border-top: 1px solid var(--rule); padding: 24px 0 32px; }
              h2 { font-size: 18px; margin: 0 0 4px; }
              .notes { margin: 0 0 14px; color: var(--muted); font-size: 13px; }
              .compare { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 24px; align-items: start; margin: 12px 0 14px; }
              /* 目检区按 raster 原尺寸并排，窄视口横向滚动而不是缩放或重叠——
                 缩放后的图像不能用来判断行内密度。 */
              .compare.pair { grid-template-columns: max-content max-content; justify-content: start; overflow-x: auto; }
              .dp-case { border-top: 1px dashed var(--rule); padding-top: 14px; margin-top: 14px; }
              .dp-case:first-of-type { border-top: 0; padding-top: 0; margin-top: 0; }
              .dp-case-label { font-size: 12px; color: var(--muted); margin-bottom: 8px; }
              .render-col { min-width: 0; }
              .col-label { font-size: 11px; color: var(--muted); text-transform: uppercase; letter-spacing: 0.06em; margin-bottom: 6px; }
              .sample-browser { line-height: 1; padding: 0; background: var(--panel); border: 1px dashed var(--rule); box-sizing: content-box; word-break: break-word; }
              .sample-raster { display: block; background: var(--panel); border: 1px dashed var(--rule); image-rendering: -webkit-optimize-contrast; }
              details { margin-top: 14px; }
              summary { cursor: pointer; font-size: 13px; color: var(--muted); user-select: none; }
              .metrics { display: flex; gap: 10px; flex-wrap: wrap; font-size: 12px; margin: 10px 0; }
              .metric { background: var(--panel); border: 1px solid var(--rule); border-radius: 4px; padding: 4px 8px; }
              pre { margin: 8px 0 0; padding: 12px; overflow: auto; background: #20242a; color: #eef2f7; border-radius: 6px; font-size: 12px; }
            </style>
            """.trimIndent(),
        )
        appendLine("</head>")
        appendLine("<body><main>")
        appendLine("<h1>提椠 layout report</h1>")
        appendLine(
            "<p class=\"intro\">三栏对比：<strong>浏览器默认</strong>排版 · 提椠 <strong>greedy</strong> · 提椠 <strong>lookahead</strong>。" +
                "中间和右侧都是用 AWT 按引擎计算出的位置直接绘制的 PNG——你看到的就是引擎实际产出的图像，跟浏览器渲染一对一可比。" +
                "决策细节（line decisions、spacing、justification、几何账本等）折叠在每个 fixture 下方 <code>decisions</code> 块里，按需展开。" +
                "当前 shaper：<code>${shaperMode.id.escapeHtml()}</code>（${shaperMode.description.escapeHtml()}）；" +
                "切回 deterministic stub 用 <code>TIQIAN_LAYOUT_REPORT_SHAPER=stub</code>。</p>",
        )
        items.forEach { item -> appendLine(item.renderSection(shaperMode)) }
        appendLine(renderDpComparisonSection(dpItems, shaperMode))
        appendLine("</main></body></html>")
    }

private fun LayoutReportItem.renderSection(shaperMode: ShaperMode): String {
    val maxWidth = fixture.constraints.maxWidth
    val spacing = greedy.debug.spacingDecisions
    val fontSize = greedy.input.textStyle.fontSize

    return buildString {
        appendLine("<section>")
        appendLine("<h2>${fixture.id.escapeHtml()}</h2>")
        appendLine("<p class=\"notes\">${fixture.notes.escapeHtml()}</p>")

        appendLine("<div class=\"compare\">")

        // Browser default column.
        appendLine("<div class=\"render-col\">")
        appendLine(
            "<div class=\"col-label\">browser default · ${maxWidth.oneDecimal()}px · ${fontSize.oneDecimal()}px font</div>",
        )
        appendLine(
            "<div class=\"sample-browser\" style=\"width:${maxWidth.oneDecimal()}px; font-size:${fontSize.oneDecimal()}px\">${fixture.text.escapeHtml()}</div>",
        )
        appendLine("</div>")

        // Tiqian engine columns — actual rasterized output from the engine.
        appendLine(renderRasterColumn("Tiqian greedy", greedy, fixture, shaperMode))
        appendLine(renderRasterColumn("Tiqian lookahead", lookahead, fixture, shaperMode))

        appendLine("</div>") // .compare

        // Decision metadata is collapsed by default — read on demand only.
        appendLine("<details>")
        appendLine(
            "<summary>decisions · greedy size ${greedy.size.width.oneDecimal()}×${greedy.size.height.oneDecimal()} · lookahead size ${lookahead.size.width.oneDecimal()}×${lookahead.size.height.oneDecimal()} · spacing ${spacing.size}</summary>",
        )
        appendLine(renderEngineMetadata("greedy", greedy))
        appendLine(renderEngineMetadata("lookahead", lookahead))
        if (spacing.isNotEmpty()) {
            appendLine("<pre>${spacing.joinToString("\n") { it.compactDump() }.escapeHtml()}</pre>")
        }
        appendLine("</details>")

        appendLine("</section>")
    }
}

private fun renderDpComparisonSection(
    items: List<DpComparisonItem>,
    shaperMode: ShaperMode,
): String {
    if (items.isEmpty()) return ""
    return buildString {
        appendLine("<section>")
        appendLine("<h2>断行策略目检：lookahead vs paragraph-dp</h2>")
        appendLine(
            "<p class=\"notes\">ADR 0041 目检区：同一批真实博客段落、同一宽度并排渲染。" +
                "判据是肉眼——行内密度是否更匀、相邻行是否不再一紧一松。" +
                "<code>paragraph-dp</code> 仍是实验策略，默认断行器不变，也不进入任何发布产物。</p>",
        )
        items.forEach { item ->
            val width = item.fixture.constraints.maxWidth
            appendLine("<div class=\"dp-case\">")
            appendLine(
                "<div class=\"dp-case-label\">${item.fixture.id.escapeHtml()} · " +
                    "${width.oneDecimal()}px · 段首缩进 ${DP_COMPARISON_INDENT_EM.oneDecimal()} 字 · " +
                    "<strong>${item.divergence.escapeHtml()}</strong></div>",
            )
            appendLine("<div class=\"compare pair\">")
            appendLine(renderRasterColumn("lookahead", item.lookahead, item.fixture, shaperMode))
            appendLine(renderRasterColumn("paragraph-dp", item.paragraphDp, item.fixture, shaperMode))
            appendLine("</div>")
            appendLine("<details>")
            appendLine(
                "<summary>decisions · lookahead ${item.lookahead.lines.size} lines · " +
                    "paragraph-dp ${item.paragraphDp.lines.size} lines</summary>",
            )
            appendLine(renderEngineMetadata("lookahead", item.lookahead))
            appendLine(renderEngineMetadata("paragraph-dp", item.paragraphDp))
            appendLine("</details>")
            appendLine("</div>")
        }
        appendLine("</section>")
    }
}

private fun renderRasterColumn(
    label: String,
    result: LayoutResult,
    fixture: LayoutFixture,
    shaperMode: ShaperMode,
): String {
    val repairs = result.debug.lineDecisions.count { it.repair != null }
    val justifications = result.debug.justificationDecisions.count { it.allocations.isNotEmpty() }
    val summary = buildList {
        add("${result.lines.size} lines")
        if (repairs > 0) add("$repairs repairs")
        if (justifications > 0) add("$justifications justify")
    }.joinToString(" · ")
    // The raster must use the same measurement stack as the engine: in skia
    // mode an AWT drawString would paint Western glyph forms while the engine
    // stepped by the locl CJK advances (visible as a hole after `——`).
    val raster = when (shaperMode) {
        ShaperMode.Skia -> rasterizeLayoutToPngSkia(result, fixture)
        else -> rasterizeLayoutToPng(result, fixture)
    }

    return buildString {
        appendLine("<div class=\"render-col\">")
        appendLine("<div class=\"col-label\">${label.escapeHtml()} · ${summary.escapeHtml()}</div>")
        appendLine(
            "<img class=\"sample-raster\" src=\"${raster.dataUri}\" " +
                "style=\"width:${raster.widthPx.oneDecimal()}px; height:${raster.heightPx.oneDecimal()}px\" " +
                "alt=\"${label.escapeHtml()} raster\">",
        )
        appendLine("</div>")
    }
}

private fun renderEngineColumn(label: String, result: LayoutResult, maxWidth: Float): String {
    val totalHeight = if (result.lines.isEmpty()) result.size.height else result.lines.last().bottom
    val repairs = result.debug.lineDecisions.count { it.repair != null }
    return buildString {
        appendLine("<div>")
        appendLine(
            "<div class=\"col-label\">${label.escapeHtml()} · width ${maxWidth.oneDecimal()}px · lines ${result.lines.size}${if (repairs > 0) " · repairs $repairs" else ""}</div>",
        )
        appendLine(
            "<div class=\"sample-engine\" style=\"width:${maxWidth.oneDecimal()}px; height:${totalHeight.oneDecimal()}px\">",
        )
        result.lines.forEachIndexed { lineIndex, line ->
            appendLine(renderLineOverlays(line, lineIndex, result.lines.lastIndex))
            val lineClusters = result.clusters.filter {
                it.range.start >= line.range.start && it.range.end <= line.range.end
            }
            var x = line.indent
            lineClusters.forEach { cluster ->
                val role = result.debug.fontDecisions.firstOrNull {
                // Containment: segmented word clusters sit inside the
                // decision's range (see SkiaLayoutRenderer).
                cluster.range.start >= it.range.start && cluster.range.end <= it.range.end
            }?.role
                appendLine(
                    renderGlyphBox(
                        cluster = cluster,
                        role = role,
                        inkOverlays = result.debug.punctuationDecisions.inkOverlaysFor(cluster),
                        fontSize = result.input.textStyle.fontSize,
                        leftPx = x,
                        line = line,
                    ),
                )
                x += cluster.advance
            }
            val repair = result.debug.lineDecisions.getOrNull(lineIndex)?.repair
            if (repair != null) {
                appendLine(
                    "<div class=\"repair-tag\" style=\"top:${line.top.oneDecimal()}px\">↻ $repair</div>",
                )
            }
            val justification = result.debug.justificationDecisions.firstOrNull { it.lineRange == line.range }
            if (justification != null && justification.allocations.isNotEmpty()) {
                val kinds = justification.allocations.map { it.kind }.distinct().joinToString("+")
                val totalDelta = justification.allocations.sumOf { it.delta.toDouble() }.toFloat()
                appendLine(
                    "<div class=\"justify-tag\" style=\"top:${(line.top + 18f).oneDecimal()}px\">↔ ${kinds} +${totalDelta.oneDecimal()}</div>",
                )
            }
        }
        appendLine("</div>")
        appendLine("</div>")
    }
}

private fun renderEngineMetadata(label: String, result: LayoutResult): String =
    buildString {
        appendLine("<div class=\"col-label\">${label.escapeHtml()}</div>")
        if (result.debug.shapingDecisions.isNotEmpty()) {
            appendLine("<div class=\"metrics\">")
            result.debug.shapingDecisions.forEach { decision ->
                val noBounds = if (decision.glyphsWithoutInkBounds > 0) {
                    " noBounds=${decision.glyphsWithoutInkBounds}/${decision.glyphCount}"
                } else {
                    ""
                }
                appendLine(
                    "<span class=\"metric\">shape ${decision.range.start}-${decision.range.end} " +
                        "'${decision.displayText.escapeHtml()}' ${decision.advance.oneDecimal()} ${decision.source}$noBounds</span>",
                )
            }
            appendLine("</div>")
        }
        if (result.debug.autoSpaceDecisions.isNotEmpty()) {
            appendLine("<div class=\"metrics\">")
            result.debug.autoSpaceDecisions.forEach { decision ->
                appendLine(
                    "<span class=\"metric\">aspace ${decision.clusterRange.start}-${decision.clusterRange.end} " +
                        "side=${decision.side} boundary=${decision.boundaryRole} affected=${decision.charactersAffected} " +
                        "reduction=${decision.totalReduction.oneDecimal()}</span>",
                )
            }
            appendLine("</div>")
        }
        if (result.debug.decorationDecisions.isNotEmpty()) {
            appendLine("<div class=\"metrics\">")
            result.debug.decorationDecisions.forEach { decision ->
                appendLine(
                    "<span class=\"metric\">deco ${decision.clusterRange.start}-${decision.clusterRange.end} " +
                        "'${decision.sourceText.escapeHtml()}' ${decision.kind} applied=${decision.applied} " +
                        "anchor=${decision.anchorX.oneDecimal()},${decision.anchorY.oneDecimal()} ${decision.reason}</span>",
                )
            }
            appendLine("</div>")
        }
        if (result.debug.decorationSegments.isNotEmpty()) {
            appendLine("<div class=\"metrics\">")
            result.debug.decorationSegments.forEach { seg ->
                appendLine(
                    "<span class=\"metric\">decobox ${seg.sourceRange.start}-${seg.sourceRange.end} ${seg.kind} " +
                        "line=${seg.lineIndex} rect=${seg.left.oneDecimal()},${seg.top.oneDecimal()}," +
                        "${seg.right.oneDecimal()},${seg.bottom.oneDecimal()} ${seg.reason}</span>",
                )
            }
            appendLine("</div>")
        }
        if (result.debug.punctuationDecisions.isNotEmpty()) {
            appendLine("<div class=\"metrics\">")
            result.debug.punctuationDecisions.forEach { decision ->
                val ink = decision.inkBounds?.let { " ink=${it.compactDump()}" } ?: ""
                val inkMeasures = buildList {
                    add("floor=${decision.policyBodyFloor.oneDecimal()}")
                    if (decision.inkContainmentApplied) {
                        add("inkFloor=${decision.inkContainmentBodyFloor?.oneDecimal()}")
                    }
                    decision.haltAdvance?.let { add("halt=${it.oneDecimal()}") }
                    decision.inkWidth?.let { add("inkW=${it.oneDecimal()}") }
                    decision.inkCenter?.let { add("inkC=${it.oneDecimal()}") }
                    if (decision.advanceExpansion != 0f) add("expand=${decision.advanceExpansion.oneDecimal()}")
                    if (
                        decision.leadingGlueInitiallyConsumed != 0f ||
                        decision.trailingGlueInitiallyConsumed != 0f
                    ) {
                        add(
                            "initial=${decision.leadingGlueInitiallyConsumed.oneDecimal()}/" +
                                decision.trailingGlueInitiallyConsumed.oneDecimal(),
                        )
                    }
                    if (decision.glyphInlineShift != 0f) add("glyphShift=${decision.glyphInlineShift.oneDecimal()}")
                    decision.glyphPlacementReason?.let { add("placement=$it") }
                }.joinToString(" ")
                val sourceTag = decision.geometrySource
                val fallback = decision.inkBoundsFallback?.let { " fallback=$it" } ?: ""
                val haltWarn = decision.haltValidation?.let { " haltWarn=$it" } ?: ""
                appendLine(
                    "<span class=\"metric\">punct ${decision.range.start}-${decision.range.end} " +
                        "'${decision.char.toString().escapeHtml()}' body=${decision.bodyWidth.oneDecimal()} " +
                        "lead=${decision.leadingGlueNatural.oneDecimal()} trail=${decision.trailingGlueNatural.oneDecimal()} " +
                        "$inkMeasures $sourceTag$fallback$haltWarn$ink</span>",
                )
            }
            appendLine("</div>")
        }
        if (result.debug.geometryDecisions.isNotEmpty()) {
            appendLine("<div class=\"metrics\">")
            result.debug.geometryDecisions.forEach { decision ->
                appendLine(
                    "<span class=\"metric\">geom ${decision.range.start}-${decision.range.end} " +
                        "'${decision.displayText.escapeHtml()}' body=${decision.bodyWidth.oneDecimal()} " +
                        "lead=${decision.leadingGlueConsumed.oneDecimal()}/${decision.leadingGlueNatural.oneDecimal()} " +
                        "trail=${decision.trailingGlueConsumed.oneDecimal()}/${decision.trailingGlueNatural.oneDecimal()} " +
                        "justify=+${decision.justificationDelta.oneDecimal()}" +
                        (if (decision.glyphInlineShift != 0f) " glyphShift=${decision.glyphInlineShift.oneDecimal()}" else "") +
                        (decision.glyphPlacementReason?.let { " placement=$it" } ?: "") +
                        " resolved=${decision.resolvedAdvance.oneDecimal()}</span>",
                )
            }
            appendLine("</div>")
        }
        if (result.debug.inlineBoxDecisions.isNotEmpty()) {
            appendLine("<div class=\"metrics\">")
            result.debug.inlineBoxDecisions.forEach { box ->
                appendLine(
                    "<span class=\"metric\">inline-box ${box.range.start}-${box.range.end} " +
                        "start=${box.inlineStart.oneDecimal()} end=${box.inlineEnd.oneDecimal()} " +
                        "outer=${box.outerSpacing} " +
                        "clusters=${box.firstClusterIndex}-${box.lastClusterIndex} ${box.reason}</span>",
                )
            }
            appendLine("</div>")
        }
        if (result.debug.inlineObjectDecisions.isNotEmpty()) {
            appendLine("<div class=\"metrics\">")
            result.debug.inlineObjectLineHeightDecision?.let { decision ->
                appendLine(
                    "<span class=\"metric\">inline-object-line-height " +
                        "base=${decision.baseLineHeight.oneDecimal()} " +
                        "face=${decision.baseFaceAscent.oneDecimal()}+${decision.baseFaceDescent.oneDecimal()} " +
                        "available=${decision.availableInterlineSpace.oneDecimal()} " +
                        "clearance=${decision.minimumClearance.oneDecimal()} " +
                        "extras=${decision.lineExtras.joinToString(",") { it.oneDecimal() }} " +
                        "boundaries=${decision.boundaryShiftsAfter.joinToString(",") { it.oneDecimal() }} " +
                        "trailing=${decision.trailingExtra.oneDecimal()} ${decision.reason}</span>",
                )
            }
            result.debug.inlineObjectDecisions.forEach { inlineObject ->
                appendLine(
                    "<span class=\"metric\">inline-object ${inlineObject.range.start}-${inlineObject.range.end} " +
                        "advance=${inlineObject.advance.oneDecimal()} ascent=${inlineObject.ascent.oneDecimal()} " +
                        "descent=${inlineObject.descent.oneDecimal()} cluster=${inlineObject.clusterIndex} " +
                        "line=${inlineObject.lineIndex} " +
                        "edges=${if (inlineObject.leadingUniformStretch) "stretch" else "fixed"}/" +
                        "${inlineObject.leadingPreferredStretchKind ?: "-"}/" +
                        "${inlineObject.leadingPreferredStretchNaturalWidth.oneDecimal()}→" +
                        "${inlineObject.leadingPreferredStretchTargetWidth.oneDecimal()}/" +
                        "${inlineObject.leadingPreferredStretchCapacity.oneDecimal()}/" +
                        "${if (inlineObject.leadingPreventsLineBreak) "closed" else "natural"}/" +
                        "${inlineObject.leadingShrinkCapacity.oneDecimal()}/" +
                        "${inlineObject.leadingLineEndDiscardableAdvance.oneDecimal()}.." +
                        "${if (inlineObject.trailingUniformStretch) "stretch" else "fixed"}/" +
                        "${inlineObject.trailingPreferredStretchKind ?: "-"}/" +
                        "${inlineObject.trailingPreferredStretchNaturalWidth.oneDecimal()}→" +
                        "${inlineObject.trailingPreferredStretchTargetWidth.oneDecimal()}/" +
                        "${inlineObject.trailingPreferredStretchCapacity.oneDecimal()}/" +
                        "${if (inlineObject.trailingPreventsLineBreak) "closed" else "natural"}/" +
                        "${inlineObject.trailingShrinkCapacity.oneDecimal()}/" +
                        "${inlineObject.trailingLineEndDiscardableAdvance.oneDecimal()} ${inlineObject.reason}</span>",
                )
            }
            appendLine("</div>")
        }
        if (result.debug.contextualKinsokuDecisions.isNotEmpty()) {
            appendLine("<div class=\"metrics\">")
            result.debug.contextualKinsokuDecisions.forEach { decision ->
                appendLine(
                    "<span class=\"metric\">context-kinsoku ${decision.range.start}-${decision.range.end} " +
                        "'${decision.sourceText.escapeHtml()}' cluster=${decision.clusterIndex} " +
                        "forbid=${decision.forbiddenPosition} ${decision.reason}" +
                        (decision.impossibleMeasureFallback?.let { " fallback=${it.escapeHtml()}" } ?: "") +
                        "</span>",
                )
            }
            appendLine("</div>")
        }
        if (result.debug.breakOpportunityDecisions.isNotEmpty()) {
            appendLine("<div class=\"metrics\">")
            result.debug.breakOpportunityDecisions.forEach { decision ->
                appendLine(
                    "<span class=\"metric\">break-opportunity ${decision.range.start}-${decision.range.end} " +
                        "offsets=${decision.breakOffsets.joinToString(",")}" +
                        (decision.tier?.let { " tier=$it" } ?: "") +
                        " ${decision.reason}</span>",
                )
            }
            appendLine("</div>")
        }
        if (result.debug.emergencyTrackingEligibilityDecisions.isNotEmpty()) {
            appendLine("<div class=\"metrics\">")
            result.debug.emergencyTrackingEligibilityDecisions.forEach { decision ->
                appendLine(
                    "<span class=\"metric\">tracking-eligibility " +
                        "${decision.range.start}-${decision.range.end} " +
                        "${decision.reason.escapeHtml()}</span>",
                )
            }
            appendLine("</div>")
        }
        if (result.debug.inlineObjectPunctuationAttachmentDecisions.isNotEmpty()) {
            appendLine("<div class=\"metrics\">")
            result.debug.inlineObjectPunctuationAttachmentDecisions.forEach { attachment ->
                appendLine(
                    "<span class=\"metric\">inline-object-punctuation " +
                        "${attachment.objectRange.start}-${attachment.objectRange.end} " +
                        "separator=${attachment.separatorRange.start}-${attachment.separatorRange.end} " +
                        "punctuation=${attachment.punctuationRange.start}-${attachment.punctuationRange.end} " +
                        "collapsed=${attachment.collapsedAdvance.oneDecimal()} " +
                        "protected=${attachment.protectedRange.start}-${attachment.protectedRange.end} " +
                        "${attachment.reason}</span>",
                )
            }
            appendLine("</div>")
        }
        if (result.debug.zeroWidthBreakDecisions.isNotEmpty()) {
            appendLine("<div class=\"metrics\">")
            result.debug.zeroWidthBreakDecisions.forEach { decision ->
                appendLine(
                    "<span class=\"metric\">zero-width-break ${decision.range.start}-${decision.range.end} " +
                        "cluster=${decision.clusterIndex} ${decision.reason}</span>",
                )
            }
            appendLine("</div>")
        }
        result.lines.forEachIndexed { lineIndex, line ->
            val repair = result.debug.lineDecisions.getOrNull(lineIndex)
            val justification = result.debug.justificationDecisions.firstOrNull { it.lineRange == line.range }
            appendLine("<div class=\"metrics\">")
            appendLine("<span class=\"metric\">line $lineIndex</span>")
            appendLine("<span class=\"metric\">range ${line.range.start}-${line.range.end}</span>")
            appendLine("<span class=\"metric\">natural ${line.naturalWidth.oneDecimal()}</span>")
            appendLine("<span class=\"metric\">adjusted ${line.adjustedWidth.oneDecimal()}</span>")
            appendLine("<span class=\"metric\">visual ${line.visualWidth.oneDecimal()}</span>")
            if (line.indent > 0f) {
                appendLine("<span class=\"metric\">indent ${line.indent.oneDecimal()}</span>")
            }
            if (repair?.repair != null) {
                appendLine("<span class=\"metric\">repair ${repair.repair} (+${repair.repairPenalty})</span>")
            }
            repair?.repairDecision?.let { decision ->
                appendLine(
                    "<span class=\"metric\">reason ${decision.reasonCode} @${decision.offenderRange.start}-${decision.offenderRange.end}</span>",
                )
                decision.targetClusterIndex?.let { target ->
                    appendLine(
                        "<span class=\"metric\">target cluster $target shrink ${decision.shrink.oneDecimal()}/${decision.availableCapacity.oneDecimal()}</span>",
                    )
                }
                decision.carriedClusterIndex?.let { carried ->
                    appendLine("<span class=\"metric\">carried cluster $carried</span>")
                }
            }
            repair?.repairCandidates?.forEach { candidate ->
                val status = if (candidate.accepted) "accepted" else "rejected:${candidate.rejectionReason}"
                val details = buildList {
                    candidate.targetClusterIndex?.let { add("target $it") }
                    candidate.carriedClusterIndex?.let { add("carried $it") }
                    if (candidate.requiredShrink > 0f || candidate.availableCapacity > 0f) {
                        add("shrink ${candidate.requiredShrink.oneDecimal()}/${candidate.availableCapacity.oneDecimal()}")
                    }
                }.joinToString(" ")
                val suffix = if (details.isEmpty()) "" else " $details"
                appendLine(
                    "<span class=\"metric\">candidate ${candidate.kind} $status$suffix</span>",
                )
            }
            if (justification != null) {
                appendLine(
                    "<span class=\"metric\">justify deficit ${justification.deficitBefore.oneDecimal()}→${justification.deficitAfter.oneDecimal()}</span>",
                )
                justification.allocations.forEach { alloc ->
                    appendLine(
                        "<span class=\"metric\">${alloc.kind} +${alloc.delta.oneDecimal()} " +
                            "@${alloc.clusterRange.start}-${alloc.clusterRange.end}" +
                            (if (alloc.reason == alloc.kind) "" else " ${alloc.reason}") +
                            "</span>",
                    )
                }
            }
            appendLine("</div>")
        }
    }

private fun renderLineOverlays(line: LineBox, lineIndex: Int, lastIndex: Int): String {
    // line.baseline is paragraph-absolute (cumulative across lines) — matches the
    // sample-engine container's top, so it can be used as the CSS `top` directly.
    val lineBoxClass = if (lineIndex == lastIndex) "line-box last" else "line-box"
    return buildString {
        appendLine("<div class=\"$lineBoxClass\" style=\"top:${line.top.oneDecimal()}px; height:${(line.bottom - line.top).oneDecimal()}px\"></div>")
        appendLine("<div class=\"baseline\" style=\"top:${line.baseline.oneDecimal()}px\"></div>")
    }
}

private fun renderGlyphBox(
    cluster: Cluster,
    role: String?,
    inkOverlays: List<InkOverlay>,
    fontSize: Float,
    leftPx: Float,
    line: LineBox,
): String {
    val klass = when {
        role == "CjkPunctuation" || cluster.text.any { it.isCjkPunctuationLike() } -> "glyph cjk-punct"
        role == "LatinText" -> "glyph latin"
        role == "CjkText" -> "glyph cjk-text"
        else -> "glyph cjk-text"
    }
    val height = line.bottom - line.top
    val baselineWithinLine = line.baseline - line.top
    return "<div class=\"$klass\" style=\"left:${leftPx.oneDecimal()}px; top:${line.top.oneDecimal()}px; width:${cluster.advance.oneDecimal()}px; height:${height.oneDecimal()}px\">" +
        inkOverlays.renderMeasuredLayer(
            width = cluster.advance,
            height = height,
            baselineWithinLine = baselineWithinLine,
            role = role,
            fontSize = fontSize,
        ) +
        "<span class=\"ch${if (inkOverlays.isNotEmpty()) " has-measured-layer" else ""}\">${cluster.displayText.escapeHtml()}</span>" +
        "</div>"
}

private data class InkOverlay(
    val xOffset: Float,
    val char: Char,
    val advance: Float,
    val bounds: Rect,
)

private fun List<PunctuationDecisionInfo>.inkOverlaysFor(cluster: Cluster): List<InkOverlay> {
    val decisions = filter { decision ->
        decision.range.start >= cluster.range.start && decision.range.end <= cluster.range.end
    }.sortedBy { it.range.start }
    var x = 0f
    return decisions.mapNotNull { decision ->
        val overlay = decision.inkBounds?.let { bounds ->
            InkOverlay(
                xOffset = x,
                char = decision.char,
                advance = decision.advance,
                bounds = bounds,
            )
        }
        x += decision.advance
        overlay
    }
}

private fun List<InkOverlay>.renderMeasuredLayer(
    width: Float,
    height: Float,
    baselineWithinLine: Float,
    role: String?,
    fontSize: Float,
): String {
    if (isEmpty()) return ""
    return buildString {
        append(
            "<svg class=\"measured-layer\" viewBox=\"0 0 ${width.oneDecimal()} ${height.oneDecimal()}\" " +
                "width=\"${width.oneDecimal()}\" height=\"${height.oneDecimal()}\" aria-hidden=\"true\">",
        )
        this@renderMeasuredLayer.forEach { overlay ->
            val xOffset = overlay.xOffset
            val bounds = overlay.bounds
            val pathData = overlay.char.awtGlyphPathData(
                x = xOffset,
                baseline = baselineWithinLine,
                role = role,
                fontSize = fontSize,
            )
            append(
                "<path d=\"${pathData.escapeHtml()}\" />",
            )
            append(
                "<rect x=\"${(xOffset + bounds.left).oneDecimal()}\" " +
                    "y=\"${(baselineWithinLine + bounds.top).oneDecimal()}\" " +
                    "width=\"${bounds.width.oneDecimal()}\" height=\"${bounds.height.oneDecimal()}\" />",
            )
        }
        append("</svg>")
    }
}

private val fontRenderContext = FontRenderContext(AffineTransform(), true, true)

private fun Char.awtGlyphPathData(
    x: Float,
    baseline: Float,
    role: String?,
    fontSize: Float,
): String {
    val font = Font(role.awtLogicalFamily(), Font.PLAIN, 1).deriveFont(fontSize)
    val glyphVector = font.layoutGlyphVector(
        fontRenderContext,
        charArrayOf(this),
        0,
        1,
        Font.LAYOUT_LEFT_TO_RIGHT,
    )
    return glyphVector
        .getGlyphOutline(0, x, baseline)
        .toSvgPathData()
}

private fun String?.awtLogicalFamily(): String =
    when (this) {
        "CjkText",
        "CjkPunctuation",
        -> Font.SERIF

        else -> Font.SANS_SERIF
    }

private fun java.awt.Shape.toSvgPathData(): String {
    val iterator = getPathIterator(null)
    val coords = FloatArray(6)
    return buildString {
        while (!iterator.isDone) {
            when (iterator.currentSegment(coords)) {
                PathIterator.SEG_MOVETO -> append("M ${coords[0].pathNumber()} ${coords[1].pathNumber()} ")
                PathIterator.SEG_LINETO -> append("L ${coords[0].pathNumber()} ${coords[1].pathNumber()} ")
                PathIterator.SEG_QUADTO -> append(
                    "Q ${coords[0].pathNumber()} ${coords[1].pathNumber()} " +
                        "${coords[2].pathNumber()} ${coords[3].pathNumber()} ",
                )
                PathIterator.SEG_CUBICTO -> append(
                    "C ${coords[0].pathNumber()} ${coords[1].pathNumber()} " +
                        "${coords[2].pathNumber()} ${coords[3].pathNumber()} " +
                        "${coords[4].pathNumber()} ${coords[5].pathNumber()} ",
                )
                PathIterator.SEG_CLOSE -> append("Z ")
            }
            iterator.next()
        }
    }.trim()
}

private fun renderLegend(): String =
    """
    <div class="legend">
      <span><span class="swatch" style="background: var(--glyph-cjk); color: var(--glyph-cjk-border)"></span>CJK text</span>
      <span><span class="swatch" style="background: var(--glyph-punct); color: var(--glyph-punct-border)"></span>CJK punct</span>
      <span><span class="swatch" style="background: var(--glyph-latin); color: var(--glyph-latin-border)"></span>Latin</span>
      <span><span class="swatch" style="background: var(--ink-fill); color: var(--ink)"></span>ink bounds</span>
      <span><span class="swatch baseline-sw" style="color: var(--baseline)"></span>baseline</span>
      <span><span class="swatch linebox-sw" style="color: var(--linebox)"></span>line box</span>
    </div>
    """.trimIndent()

private fun Char.isCjkPunctuationLike(): Boolean =
    this in "，、。；：！？“”‘’（）《》〈〉「」『』·・‧•～…⋯—⸺"

internal fun Float.oneDecimal(): String =
    String.format(Locale.US, "%.1f", this)

private fun Float.pathNumber(): String =
    String.format(Locale.US, "%.3f", this).trimEnd('0').trimEnd('.')

private fun Rect.compactDump(): String =
    "[${left.oneDecimal()},${top.oneDecimal()},${right.oneDecimal()},${bottom.oneDecimal()}]"

private fun String.escapeHtml(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
