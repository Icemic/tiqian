package org.tiqian.web

import org.tiqian.core.DecorationKind
import org.tiqian.core.DecorationSpan
import org.tiqian.core.INLINE_OBJECT_REPLACEMENT_CHAR
import org.tiqian.core.InlineBoxSpan
import org.tiqian.core.InlineObjectSpan
import org.tiqian.core.LineBreakPolicy
import org.tiqian.core.LineBreakSpan
import org.tiqian.core.TextRange
import org.tiqian.core.TextSpan
import org.tiqian.core.TextStyle
import org.tiqian.font.CjkFontRoleClassifier
import org.tiqian.font.FontRole
import org.tiqian.font.FontRoleContext
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.Node

internal object MarkdownParagraphLowerer {
    private val fontRoleClassifier = CjkFontRoleClassifier()
    private val graphemeSegmenter: GraphemeSegmenterJs? = createLowererGraphemeSegmenter()

    var lastIssue: TiqianWeb.CapabilityIssue? = null
        private set

    fun lower(paragraph: HTMLElement, options: TiqianWeb.EnhanceOptions): LoweredParagraph? {
        lastIssue = null
        val canonicalPrepared =
            paragraph.getAttribute("data-tq-rendered") == "true" &&
                paragraph.getAttribute("data-tq-canonical-plain") == "true"
        return withConfiguredFontSizeProbe(paragraph, options.fontSize) {
            if (canonicalPrepared) {
                withCanonicalPreparedHostStyleProbe(paragraph) {
                    lowerWithCurrentStyles(paragraph, options, canonicalPrepared = true)
                }
            } else {
                lowerWithCurrentStyles(paragraph, options, canonicalPrepared = false)
            }
        }
    }

    /**
     * ConfiguredFontSizeSingleSource: an explicit engine font size must be live
     * while descendant computed styles are sampled. Otherwise inherited links
     * and code runs are lowered at the host size even though the base run is
     * measured at the override. The host is restored before custody transfer;
     * the renderer then applies the same size for the enhanced paragraph.
     */
    private fun <T> withConfiguredFontSizeProbe(
        paragraph: HTMLElement,
        fontSize: Float?,
        block: () -> T,
    ): T {
        if (fontSize == null) return block()
        val originalStyle = paragraph.getAttribute("style")
        paragraph.style.setProperty("font-size", "${fontSize}px", "important")
        return try {
            block()
        } finally {
            if (originalStyle == null) {
                paragraph.removeAttribute("style")
            } else {
                paragraph.setAttribute("style", originalStyle)
            }
        }
    }

    private fun lowerWithCurrentStyles(
        paragraph: HTMLElement,
        options: TiqianWeb.EnhanceOptions,
        canonicalPrepared: Boolean,
    ): LoweredParagraph? {
        val fallbackStyle = TextStyle(fontSize = DEFAULT_FONT_SIZE)
        val computedParagraphStyle = computedTextStyle(paragraph, fallbackStyle)
        val fontSize = options.fontSize ?: computedParagraphStyle.fontSize
        val baseStyle = computedParagraphStyle.copy(fontSize = fontSize)
        val lineHeight = options.lineHeight
            ?: parseCssLineHeight(computedStyle(paragraph, "line-height"), fontSize)
            ?: fontSize * DEFAULT_LINE_HEIGHT_MULTIPLIER
        val baseInlineStyle = InlineStyle(
            textStyle = baseStyle,
            whiteSpace = cssWhiteSpaceMode(computedStyle(paragraph, "white-space")),
        )
        if (canonicalPrepared) {
            val source = canonicalPreparedPlainSource(paragraph)
            if (source.isBlank()) {
                lastIssue = TiqianWeb.CapabilityIssue("EmptyParagraph", "paragraph has no text", paragraph)
                return null
            }
            return LoweredParagraph(
                text = source,
                textStyle = baseStyle,
                lineHeight = lineHeight,
                spans = emptyList(),
                decorations = emptyList(),
                inlineBoxes = emptyList(),
                inlineObjects = emptyList(),
                domInlineObjects = emptyList(),
                sourceSpans = emptyList(),
                sourceBoundaries = emptySet(),
                lineBreakSpans = emptyList(),
            )
        }
        generatedPseudoContentIssue(paragraph)?.let { detail ->
            lastIssue = TiqianWeb.CapabilityIssue(
                "UnsupportedGeneratedInlineContent",
                detail,
                paragraph,
            )
            return null
        }
        val builder = LoweringBuilder(
            sourceElement = paragraph,
            baseInlineStyle = baseInlineStyle,
            baseLineHeight = lineHeight,
            strongAsEmphasisMarks = options.strongAsEmphasisMarks,
        )
        if (!builder.appendChildren(paragraph, baseInlineStyle, depth = 0)) {
            return null
        }
        val lowered = builder.build()
        if (lowered.text.isBlank()) {
            lastIssue = TiqianWeb.CapabilityIssue("EmptyParagraph", "paragraph has no text", paragraph)
            return null
        }
        return lowered
    }

    /**
     * CanonicalPreparedHostStyleProbe: a direct-SSR prepared paragraph carries
     * `data-tq-rendered`, so the public replay CSS intentionally gives it
     * `line-height: 0` and `white-space: pre`. When a width miss falls back to
     * runtime layout, those are renderer-owned values rather than host
     * typography. Suppress the replay selector only while sampling computed
     * paragraph styles, then restore the attribute synchronously before any
     * layout mutation can be painted.
     */
    private fun <T> withCanonicalPreparedHostStyleProbe(
        paragraph: HTMLElement,
        block: () -> T,
    ): T {
        val rendered = paragraph.getAttribute("data-tq-rendered")
        paragraph.removeAttribute("data-tq-rendered")
        return try {
            block()
        } finally {
            if (rendered == null) {
                paragraph.removeAttribute("data-tq-rendered")
            } else {
                paragraph.setAttribute("data-tq-rendered", rendered)
            }
        }
    }

    private class LoweringBuilder(
        private val sourceElement: HTMLElement,
        private val baseInlineStyle: InlineStyle,
        private val baseLineHeight: Float,
        private val strongAsEmphasisMarks: Boolean,
    ) {
        val text = StringBuilder()
        private val spans = mutableListOf<TextSpan>()
        private val decorations = mutableListOf<DecorationSpan>()
        private val inlineBoxes = mutableListOf<InlineBoxSpan>()
        private val inlineObjects = mutableListOf<InlineObjectSpan>()
        private val domInlineObjects = mutableListOf<DomInlineObject>()
        private val sourceSpans = mutableListOf<DomSourceSpan>()
        private val sourceBoundaries = linkedSetOf<Int>()
        private val whitespaceModes = mutableListOf<CssWhiteSpaceMode>()
        private val hardBreakOffsets = linkedSetOf<Int>()
        private val eligibility = eligibilityBridge()

        fun appendChildren(element: Element, style: InlineStyle, depth: Int): Boolean {
            val nodes = element.childNodes
            for (i in 0 until nodes.length) {
                val node = nodes.item(i) ?: continue
                if (!appendNode(node, style, depth)) return false
            }
            return true
        }

        private fun appendNode(node: Node, style: InlineStyle, depth: Int): Boolean {
            return when (node.nodeType) {
                Node.TEXT_NODE -> {
                    appendText(node.textContent ?: "", style)
                    true
                }
                Node.ELEMENT_NODE -> appendElement(node as Element, style, depth)
                else -> true
            }
        }

        private fun appendElement(element: Element, style: InlineStyle, depth: Int): Boolean {
            val tag = element.tagName.uppercase()
            if (tag == "BR") {
                hardBreakOffsets += text.length
                appendRawText("\n", style.whiteSpace)
                return true
            }
            val display = computedStyle(element, "display").trim().lowercase()
            val opaqueCandidate = eligibility.isNonTextInlineTag(tag) ||
                tag.contains('-') ||
                eligibility.isOpaqueInlineDisplay(display)
            if (opaqueCandidate) {
                if (!eligibility.isOpaqueInlineLevelDisplay(display)) {
                    return unsupported(
                        "UnsupportedInlineFormattingContext",
                        "${tag.lowercase()}:$display",
                    )
                }
                if (!isCloneSafeOpaqueInlineObject(element)) {
                    return unsupported("UnsupportedStatefulInlineObject", tag.lowercase())
                }
                return appendOpaqueInlineObject(element, style.whiteSpace)
            }
            if (display != "inline" && display != "contents") {
                return unsupported(
                    "UnsupportedInlineFormattingContext",
                    "${tag.lowercase()}:$display",
                )
            }
            // RuntimeInlineCodeUsesResolvedBrowserFont: build-time snapshots
            // must fail closed without exact monospace font/box evidence, but
            // the live browser already exposes the resolved host font and box
            // metrics. Lower that run normally and let the sliced browser
            // shaping fallback handle Worker-ineligible rich paragraphs.
            inlineShapingStyleIssue(element, sourceElement)?.let { property ->
                return unsupported(
                    "UnsupportedInlineShapingStyle",
                    "${tag.lowercase()}:$property",
                )
            }
            val inheritedStrongWeight = style.cjkStrongBaseWeight
            val strongBaseWeight = if (tag == "STRONG" && strongAsEmphasisMarks) {
                inheritedStrongWeight ?: style.textStyle.fontWeight
            } else {
                null
            }
            val elementStyle = computedInlineStyle(element, style).let { computed ->
                if (tag == "STRONG" && strongAsEmphasisMarks) {
                    computed.copy(cjkStrongBaseWeight = strongBaseWeight)
                } else {
                    computed
                }
            }
            return appendSemantic(element, elementStyle, depth, strongBaseWeight)
        }

        private fun appendOpaqueInlineObject(
            element: Element,
            whiteSpace: CssWhiteSpaceMode,
        ): Boolean {
            val geometry = parseOpaqueInlineObjectGeometry(measuredOpaqueInlineObjectGeometry(element))
                ?: return unsupported("InvalidInlineObjectGeometry", element.tagName.lowercase())
            val start = text.length
            appendRawText(INLINE_OBJECT_REPLACEMENT_CHAR.toString(), whiteSpace)
            val range = TextRange(start, text.length)
            sourceBoundaries += range.start
            sourceBoundaries += range.end
            inlineObjects += InlineObjectSpan(
                range = range,
                advance = geometry.advance,
                ascent = geometry.ascent,
                descent = geometry.descent,
            )
            domInlineObjects += DomInlineObject(
                range = range,
                element = element,
                marginRight = parseCssPx(computedStyle(element, "margin-right")) ?: 0f,
            )
            return true
        }

        private fun appendSemantic(
            element: Element,
            style: InlineStyle,
            depth: Int,
            cjkStrongBaseWeight: Int?,
        ): Boolean {
            val inlineStart = measuredInlineEdge(element, "start").toFloat()
            val inlineEnd = measuredInlineEdge(element, "end").toFloat()
            if (!inlineStart.isFinite() || !inlineEnd.isFinite()) {
                return unsupported("InvalidInlineBoxGeometry", element.tagName.lowercase())
            }
            val start = text.length
            if (!appendChildren(element, style, depth + 1)) return false
            val end = text.length
            if (end > start) {
                val range = TextRange(start, end)
                sourceBoundaries += start
                sourceBoundaries += end
                if (kotlin.math.abs(inlineStart) >= INLINE_EDGE_EPSILON ||
                    kotlin.math.abs(inlineEnd) >= INLINE_EDGE_EPSILON
                ) {
                    inlineBoxes += InlineBoxSpan(
                        range = range,
                        inlineStart = inlineStart,
                        inlineEnd = inlineEnd,
                    )
                }
                sourceSpans += DomSourceSpan(
                    range = range,
                    element = element,
                    depth = depth,
                    cjkStrongBaseWeight = cjkStrongBaseWeight,
                    computedColor = computedStyle(element, "color").takeIf { it.isNotBlank() },
                    inlineBoxStyle = DomInlineBoxStyle(
                        inlineStart = inlineStart,
                        inlineEnd = inlineEnd,
                        marginRight = parseCssPx(computedStyle(element, "margin-right")) ?: 0f,
                        letterSpacing = parseCssPx(computedStyle(element, "letter-spacing")) ?: 0f,
                        boxDecorationBreak = computedStyle(element, "box-decoration-break")
                            .trim()
                            .lowercase(),
                    ),
                )
            }
            return true
        }

        private fun unsupported(name: String, detail: String): Boolean {
            lastIssue = TiqianWeb.CapabilityIssue(name, detail, sourceElement)
            return false
        }

        private fun appendText(value: String, style: InlineStyle) {
            if (value.isEmpty()) return
            val strongBaseWeight = style.cjkStrongBaseWeight
            if (strongBaseWeight == null) {
                appendTextSegment(value, style.textStyle, style.whiteSpace, emphasis = false)
                return
            }

            val boundaries = lowererGraphemeBoundaries(graphemeSegmenter, value)
                .split(',')
                .mapNotNull(String::toIntOrNull)
                .filter { it in 0..value.length }
                .distinct()
                .sorted()
                .let { offsets ->
                    buildList {
                        if (offsets.firstOrNull() != 0) add(0)
                        addAll(offsets)
                        if (lastOrNull() != value.length) add(value.length)
                    }
                }
            var runStart = boundaries.first()
            var runIsCjk = false
            var hasRun = false
            for ((start, end) in boundaries.zipWithNext()) {
                if (end <= start) continue
                val role = fontRoleClassifier.classify(
                    value,
                    TextRange(start, end),
                    FontRoleContext(locale = style.textStyle.locale),
                )
                val isCjk = role == FontRole.CjkText || role == FontRole.CjkPunctuation
                if (hasRun && isCjk != runIsCjk) {
                    appendStrongTextSegment(value.substring(runStart, start), style, runIsCjk, strongBaseWeight)
                    runStart = start
                }
                runIsCjk = isCjk
                hasRun = true
            }
            if (hasRun && runStart < value.length) {
                appendStrongTextSegment(value.substring(runStart), style, runIsCjk, strongBaseWeight)
            }
        }

        private fun appendStrongTextSegment(
            value: String,
            style: InlineStyle,
            isCjk: Boolean,
            strongBaseWeight: Int,
        ) {
            val textStyle = if (isCjk) {
                style.textStyle.copy(fontWeight = strongBaseWeight)
            } else {
                style.textStyle
            }
            appendTextSegment(value, textStyle, style.whiteSpace, emphasis = isCjk)
        }

        private fun appendTextSegment(
            value: String,
            style: TextStyle,
            whiteSpace: CssWhiteSpaceMode,
            emphasis: Boolean,
        ) {
            if (value.isEmpty()) return
            val start = text.length
            appendRawText(value, whiteSpace)
            val end = text.length
            if (style != baseInlineStyle.textStyle) {
                spans += TextSpan(
                    TextRange(start, end),
                    style,
                )
                sourceBoundaries += start
                sourceBoundaries += end
            }
            if (emphasis) {
                decorations += DecorationSpan(TextRange(start, end), DecorationKind.Emphasis)
                sourceBoundaries += start
                sourceBoundaries += end
            }
        }

        private fun appendRawText(value: String, whiteSpace: CssWhiteSpaceMode) {
            text.append(value)
            repeat(value.length) { whitespaceModes += whiteSpace }
        }

        fun build(): LoweredParagraph {
            val projection = cssWhiteSpaceCollapseProjection(
                text = text.toString(),
                modes = whitespaceModes,
                hardBreakOffsets = hardBreakOffsets,
            )
            return LoweredParagraph(
                text = projection.text,
                textStyle = baseInlineStyle.textStyle,
                lineHeight = baseLineHeight,
                spans = spans.mapNotNull { span ->
                    projection.range(span.range)?.let { span.copy(range = it) }
                },
                decorations = decorations.mapNotNull { span ->
                    projection.range(span.range)?.let { span.copy(range = it) }
                },
                inlineBoxes = inlineBoxes.mapNotNull { span ->
                    projection.range(span.range)?.let { span.copy(range = it) }
                },
                inlineObjects = inlineObjects.mapNotNull { span ->
                    projection.range(span.range)?.let { span.copy(range = it) }
                },
                domInlineObjects = domInlineObjects.mapNotNull { inlineObject ->
                    projection.range(inlineObject.range)?.let { inlineObject.copy(range = it) }
                },
                sourceSpans = sourceSpans.mapNotNull { span ->
                    projection.range(span.range)?.let { span.copy(range = it) }
                },
                lineBreakSpans = sourceSpans.mapNotNull { span ->
                    if (span.element.tagName.uppercase() !in setOf("A", "CODE")) return@mapNotNull null
                    projection.range(span.range)?.let {
                        LineBreakSpan(it, LineBreakPolicy.ProgressiveTechnical)
                    }
                }.distinctBy { it.range to it.policy },
                sourceBoundaries = sourceBoundaries
                    .map(projection::boundary)
                    .filter { it > 0 && it < projection.text.length }
                    .toSet(),
            )
        }
    }
}

private fun canonicalPreparedPlainSource(parent: Node): String = buildString {
    fun appendNode(node: Node) {
        if (node.nodeType == Node.TEXT_NODE) {
            append(node.textContent.orEmpty())
            return
        }
        if (node.nodeType != Node.ELEMENT_NODE) return
        val element = node as Element
        if (element.hasAttribute("data-tq-copy-ignore")) return
        if (element.hasAttribute("data-tq-src")) {
            val following = element.nextSibling as? Element
            val pairedMandatoryBreak = element.hasAttribute("data-tq-hard-break") &&
                following?.tagName?.uppercase() == "BR" &&
                following.getAttribute("data-tq-engine-break") == "MandatoryBreak"
            if (!pairedMandatoryBreak) append(element.getAttribute("data-tq-src").orEmpty())
            return
        }
        if (element.tagName.uppercase() == "BR") {
            if (element.getAttribute("data-tq-engine-break") == "MandatoryBreak") append('\n')
            return
        }
        val children = element.childNodes
        for (index in 0 until children.length) children.item(index)?.let(::appendNode)
    }
    val children = parent.childNodes
    for (index in 0 until children.length) children.item(index)?.let(::appendNode)
}

data class LoweredParagraph(
    val text: String,
    val textStyle: TextStyle,
    val lineHeight: Float,
    val spans: List<TextSpan>,
    val decorations: List<DecorationSpan>,
    val inlineBoxes: List<InlineBoxSpan>,
    val inlineObjects: List<InlineObjectSpan>,
    val domInlineObjects: List<DomInlineObject>,
    val sourceSpans: List<DomSourceSpan>,
    val sourceBoundaries: Set<Int>,
    val lineBreakSpans: List<LineBreakSpan>,
)

internal fun LoweredParagraph.isCanonicalPlainParagraph(): Boolean =
    spans.isEmpty() &&
        decorations.isEmpty() &&
        inlineBoxes.isEmpty() &&
        inlineObjects.isEmpty() &&
        domInlineObjects.isEmpty() &&
        sourceSpans.isEmpty()

/**
 * RuntimeExactPreparedDomScope: the runtime prepared-DOM bridge adopts the
 * same paragraph shapes as the exact Worker request except inline objects,
 * which the clone swap (ADR 0053 B7.3) replays from live elements, and
 * decorations, which the plan carries as unconditional overlay segments
 * while the Worker request wire has no decoration input. Styled spans,
 * inline boxes, source semantics and decorations replay through plan
overs the remaining hosts and leaves an already-installed
    // renderer * evidence; a single-line cloned-edge box replays through the plan's
 * inlineEdges the same way a sliced box does. Locale-mismatching spans
 * fail closed with SpanLocaleMismatchUnsupported: the plan wire carries
 * one paragraph locale, so the bridge cannot replay a span shaped under
 * a different one. The Worker request keeps its own stricter exclusion
 * list because its wire has no line count to guard cloned edges with.
 */
internal fun LoweredParagraph.isRuntimeExactPreparedDomEligible(): Boolean =
    spans.none { it.style.locale != textStyle.locale }

data class DomInlineObject(
    val range: TextRange,
    val element: Element,
    val marginRight: Float = 0f,
)

data class DomSourceSpan(
    val range: TextRange,
    val element: Element,
    val depth: Int,
    val cjkStrongBaseWeight: Int? = null,
    val computedColor: String? = null,
    val inlineBoxStyle: DomInlineBoxStyle = DomInlineBoxStyle(),
)

data class DomInlineBoxStyle(
    val inlineStart: Float = 0f,
    val inlineEnd: Float = 0f,
    val marginRight: Float = 0f,
    val letterSpacing: Float = 0f,
    val boxDecorationBreak: String = "slice",
)

data class CssRenderStyleSpan(
    val range: TextRange,
    val style: CssRenderStyle,
)

data class CssRenderStyle(
    val color: String? = null,
    val backgroundColor: String? = null,
    val textDecorationLine: String? = null,
    val textDecorationColor: String? = null,
    val textDecorationStyle: String? = null,
    val textDecorationThickness: String? = null,
    val textUnderlineOffset: String? = null,
)

private data class InlineStyle(
    val textStyle: TextStyle,
    val whiteSpace: CssWhiteSpaceMode,
    val cjkStrongBaseWeight: Int? = null,
)

/**
 * CssWhiteSpaceCollapseProjection: DOM source formatting is projected through
 * the host's `white-space` semantics before it becomes Tiqian source text.
 * Only a real `<br>` is marked separately as a structural mandatory break.
 */
private enum class CssWhiteSpaceMode {
    Collapse,
    CollapsePreserveBreaks,
    Preserve,
}

private data class CssWhiteSpaceProjection(
    val text: String,
    private val boundaryMap: IntArray,
) {
    fun boundary(sourceOffset: Int): Int = boundaryMap[sourceOffset]

    fun range(sourceRange: TextRange): TextRange? {
        val start = boundary(sourceRange.start)
        val end = boundary(sourceRange.end)
        return if (end > start) TextRange(start, end) else null
    }
}

private fun cssWhiteSpaceCollapseProjection(
    text: String,
    modes: List<CssWhiteSpaceMode>,
    hardBreakOffsets: Set<Int>,
): CssWhiteSpaceProjection {
    require(modes.size == text.length) {
        "Whitespace mode count ${modes.size} must match source length ${text.length}"
    }
    require(hardBreakOffsets.all { it in text.indices && text[it] == '\n' }) {
        "Structural hard-break offsets must point at source newlines"
    }

    val projected = StringBuilder(text.length)
    val boundaryMap = IntArray(text.length + 1)
    var pendingStart = -1
    var pendingEnd = -1

    fun resolvePendingWhitespace(emit: Boolean) {
        if (pendingStart < 0) return
        val before = projected.length
        if (emit && projected.isNotEmpty() && projected.last() != '\n') {
            projected.append(' ')
        }
        val after = projected.length
        boundaryMap[pendingStart] = before
        for (boundary in (pendingStart + 1)..pendingEnd) {
            boundaryMap[boundary] = after
        }
        pendingStart = -1
        pendingEnd = -1
    }

    fun deferCollapsedWhitespace(index: Int) {
        if (pendingStart < 0) {
            pendingStart = index
            boundaryMap[index] = projected.length
        }
        pendingEnd = index + 1
    }

    fun appendPreserved(index: Int, char: Char) {
        resolvePendingWhitespace(emit = true)
        boundaryMap[index] = projected.length
        projected.append(char)
        boundaryMap[index + 1] = projected.length
    }

    var index = 0
    while (index < text.length) {
        if (index in hardBreakOffsets) {
            resolvePendingWhitespace(emit = false)
            boundaryMap[index] = projected.length
            projected.append('\n')
            boundaryMap[index + 1] = projected.length
            index += 1
            continue
        }

        val char = text[index]
        when (modes[index]) {
            CssWhiteSpaceMode.Collapse -> {
                if (char.isCssCollapsibleWhitespace()) {
                    deferCollapsedWhitespace(index)
                } else {
                    appendPreserved(index, char)
                }
                index += 1
            }

            CssWhiteSpaceMode.CollapsePreserveBreaks -> {
                if (char == '\r' || char == '\n') {
                    resolvePendingWhitespace(emit = false)
                    boundaryMap[index] = projected.length
                    projected.append('\n')
                    boundaryMap[index + 1] = projected.length
                    if (
                        char == '\r' &&
                        index + 1 < text.length &&
                        text[index + 1] == '\n' &&
                        modes[index + 1] == CssWhiteSpaceMode.CollapsePreserveBreaks &&
                        index + 1 !in hardBreakOffsets
                    ) {
                        boundaryMap[index + 2] = projected.length
                        index += 2
                    } else {
                        index += 1
                    }
                } else if (char.isCssCollapsibleWhitespace()) {
                    deferCollapsedWhitespace(index)
                    index += 1
                } else {
                    appendPreserved(index, char)
                    index += 1
                }
            }

            CssWhiteSpaceMode.Preserve -> {
                if (char == '\r') {
                    resolvePendingWhitespace(emit = true)
                    boundaryMap[index] = projected.length
                    projected.append('\n')
                    boundaryMap[index + 1] = projected.length
                    if (
                        index + 1 < text.length &&
                        text[index + 1] == '\n' &&
                        modes[index + 1] == CssWhiteSpaceMode.Preserve &&
                        index + 1 !in hardBreakOffsets
                    ) {
                        boundaryMap[index + 2] = projected.length
                        index += 2
                    } else {
                        index += 1
                    }
                } else {
                    appendPreserved(index, char)
                    index += 1
                }
            }
        }
    }
    resolvePendingWhitespace(emit = false)
    boundaryMap[text.length] = projected.length
    return CssWhiteSpaceProjection(projected.toString(), boundaryMap)
}

private fun Char.isCssCollapsibleWhitespace(): Boolean =
    this == ' ' || this == '\t' || this == '\n' || this == '\r' || this == '\u000C'

private fun cssWhiteSpaceMode(
    value: String,
    fallback: CssWhiteSpaceMode = CssWhiteSpaceMode.Collapse,
): CssWhiteSpaceMode {
    val normalized = value.trim().lowercase()
    return when {
        normalized == "normal" || normalized == "nowrap" ||
            normalized == "collapse" || normalized.startsWith("collapse ") -> CssWhiteSpaceMode.Collapse
        normalized == "pre-line" || normalized.startsWith("preserve-breaks") ->
            CssWhiteSpaceMode.CollapsePreserveBreaks
        normalized == "pre" || normalized == "pre-wrap" || normalized == "break-spaces" ||
            normalized.startsWith("preserve ") -> CssWhiteSpaceMode.Preserve
        else -> fallback
    }
}

private data class OpaqueInlineObjectGeometry(
    val advance: Float,
    val ascent: Float,
    val descent: Float,
)

private fun parseOpaqueInlineObjectGeometry(value: String): OpaqueInlineObjectGeometry? {
    val parts = value.split(',').mapNotNull(String::toFloatOrNull)
    if (parts.size != 3) return null
    val (advance, ascent, descent) = parts
    if (!advance.isFinite() || advance <= INLINE_EDGE_EPSILON) return null
    if (!ascent.isFinite() || ascent < 0f || !descent.isFinite() || descent < 0f) return null
    if (ascent + descent <= INLINE_EDGE_EPSILON) return null
    return OpaqueInlineObjectGeometry(advance, ascent, descent)
}

private fun computedTextStyle(element: Element, fallback: TextStyle): TextStyle {
    val fontFamilies = parseCssFontFamilies(computedStyle(element, "font-family"))
        .takeIf { it.isNotEmpty() }
        ?: fallback.fontFamilies
    val fontSize = parseCssPx(computedStyle(element, "font-size")) ?: fallback.fontSize
    val fontWeight = parseCssFontWeight(computedStyle(element, "font-weight")) ?: fallback.fontWeight
    val italic = parseCssItalic(computedStyle(element, "font-style")) ?: fallback.italic
    return fallback.copy(
        fontFamilies = fontFamilies,
        fontSize = fontSize,
        fontWeight = fontWeight,
        italic = italic,
    )
}

// InlineShapingStyleParityContract: TextStyle currently models family, size,
// weight, italic and baseline shift. The renderer preserves semantic wrappers,
// so an inherited shaping property that changes only inside such a wrapper
// would otherwise make browser glyph advances diverge from LayoutResult.
private val unsupportedInlineShapingProperties = listOf(
    "font-feature-settings",
    "font-variation-settings",
    "font-stretch",
    "font-kerning",
    "font-optical-sizing",
    "font-variant-ligatures",
    "font-variant-alternates",
    "font-variant-east-asian",
    "font-variant-caps",
    "font-variant-numeric",
    "font-variant-position",
    "font-language-override",
    "font-size-adjust",
    "word-spacing",
    "text-transform",
    "text-rendering",
)

private fun inlineShapingStyleIssue(element: Element, paragraph: Element): String? =
    unsupportedInlineShapingProperties.firstOrNull { property ->
        computedStyle(element, property).trim().lowercase() !=
            computedStyle(paragraph, property).trim().lowercase()
    }

// RootGeneratedInlineContentMustStayNative: a pseudo directly on the paragraph
// has no source range to which InlineBoxSpan can attach. Descendant semantic
// elements are supported instead: measuredInlineEdge() reserves their actual
// ::before/::after advance while the one cloned semantic element keeps the host
// pseudo, copy, accessibility and interaction behavior intact.
private fun generatedPseudoContentIssue(element: Element): String? {
    for (pseudo in listOf("::before", "::after")) {
        val content = flowParticipatingPseudoContent(element, pseudo)?.trim()
        if (content != null) {
            return "${element.tagName.lowercase()}$pseudo:$content"
        }
    }
    return null
}

private fun computedInlineStyle(element: Element, fallback: InlineStyle): InlineStyle {
    val computed = computedTextStyle(element, fallback.textStyle)
    val localBaselineShift = computedInlineBaselineShift(element)
    return InlineStyle(
        textStyle = computed.copy(
            baselineShift = fallback.textStyle.baselineShift + localBaselineShift,
        ),
        whiteSpace = cssWhiteSpaceMode(
            computedStyle(element, "white-space"),
            fallback.whiteSpace,
        ),
        cjkStrongBaseWeight = fallback.cjkStrongBaseWeight,
    )
}

private fun computedInlineBaselineShift(element: Element): Float {
    var relativeShift = 0f
    if (computedStyle(element, "position").trim().lowercase() == "relative") {
        val top = parseCssPx(computedStyle(element, "top"))
        val bottom = parseCssPx(computedStyle(element, "bottom"))
        relativeShift = top ?: bottom?.let { -it } ?: 0f
    }
    val verticalAlign = computedStyle(element, "vertical-align").trim().lowercase()
    return when {
        verticalAlign.isBlank() || verticalAlign == "baseline" -> relativeShift
        parseCssPx(verticalAlign) != null -> relativeShift - parseCssPx(verticalAlign)!!
        else -> measuredInlineBaselineShift(element).toFloat().takeIf { it.isFinite() } ?: 0f
    }
}

private fun parseCssFontFamilies(value: String): List<String> {
    val families = mutableListOf<String>()
    val token = StringBuilder()
    var quote: Char? = null

    fun flush() {
        val family = token.toString().trim().removeSurrounding("\"").removeSurrounding("'")
        if (family.isNotEmpty()) families += family
        token.clear()
    }

    for (char in value) {
        when {
            quote != null && char == quote -> {
                quote = null
                token.append(char)
            }
            quote != null -> token.append(char)
            char == '\'' || char == '"' -> {
                quote = char
                token.append(char)
            }
            char == ',' -> flush()
            else -> token.append(char)
        }
    }
    flush()
    return families
}

internal fun parseCssPx(value: String): Float? {
    val trimmed = value.trim()
    if (!trimmed.endsWith("px")) return null
    return trimmed.removeSuffix("px").trim().toFloatOrNull()
}

private fun parseCssLineHeight(value: String, fontSize: Float): Float? {
    val trimmed = value.trim()
    parseCssPx(trimmed)?.let { return it }
    return trimmed.toFloatOrNull()?.let { it * fontSize }
}

private fun parseCssFontWeight(value: String): Int? {
    val trimmed = value.trim().lowercase()
    return when (trimmed) {
        "normal" -> 400
        "bold" -> 700
        "lighter", "bolder" -> null
        else -> trimmed.toFloatOrNull()?.toInt()?.coerceIn(1, 900)
    }
}

private fun parseCssItalic(value: String): Boolean? {
    val trimmed = value.trim().lowercase()
    if (trimmed.isBlank()) return null
    return trimmed.startsWith("italic") || trimmed.startsWith("oblique")
}
