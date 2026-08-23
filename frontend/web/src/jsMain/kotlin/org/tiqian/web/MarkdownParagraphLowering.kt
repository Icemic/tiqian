package org.tiqian.web

import kotlin.JsFun
import org.tiqian.core.DecorationKind
import org.tiqian.core.DecorationSpan
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

/**
 * FacadeDecodeLoweredParagraph: the markdown lowering engine now lives in the
 * npm module (markdown-lowering.js), embedded into the runtime bundle by the
 * generateMarkdownLoweringBridge gradle task. This facade installs the bridge
 * lazily, feeds the live classifyRole hook, and decodes the returned plain
 * object back into the Kotlin layout model. lastIssue keeps the caller-facing
 * capability semantics of the removed Kotlin lowerer.
 */
internal object MarkdownParagraphLowerer {
    private const val LOWERING_DEFAULT_LOCALE = "zh-Hans"

    private val fontRoleClassifier = CjkFontRoleClassifier()

    var lastIssue: TiqianWeb.CapabilityIssue? = null
        private set

    fun lower(paragraph: HTMLElement, options: TiqianWeb.EnhanceOptions): LoweredParagraph? {
        lastIssue = null
        val result = markdownLoweringBridge().lower(
            paragraph,
            loweringOptionsJs(
                options.fontSize?.toDouble(),
                options.lineHeight?.toDouble(),
                options.strongAsEmphasisMarks,
                LOWERING_DEFAULT_LOCALE,
            ),
            loweringHelpersJs { text, start, end, locale ->
                val role = fontRoleClassifier.classify(
                    text,
                    TextRange(start, end),
                    FontRoleContext(locale = locale),
                )
                when (role) {
                    FontRole.CjkText -> "cjk-text"
                    FontRole.CjkPunctuation -> "cjk-punctuation"
                    else -> "other"
                }
            },
        )
        if (result.ok != true) {
            val issue = result.issue
            lastIssue = TiqianWeb.CapabilityIssue(
                issue.name as? String ?: "UnsupportedParagraph",
                (issue.detail as? String) ?: "paragraph could not be lowered",
                paragraph,
            )
            return null
        }
        return decodeLowered(result.lowered)
    }

    private fun decodeLowered(value: dynamic): LoweredParagraph = LoweredParagraph(
        text = value.text as String,
        textStyle = decodeTextStyle(value.textStyle),
        lineHeight = (value.lineHeight as Double).toFloat(),
        spans = decodeSpans(value.spans),
        decorations = decodeDecorations(value.decorations),
        inlineBoxes = decodeInlineBoxes(value.inlineBoxes),
        inlineObjects = decodeInlineObjects(value.inlineObjects),
        domInlineObjects = decodeDomInlineObjects(value.domInlineObjects),
        sourceSpans = decodeSourceSpans(value.sourceSpans),
        sourceBoundaries = decodeSourceBoundaries(value.sourceBoundaries),
        lineBreakSpans = decodeLineBreakSpans(value.lineBreakSpans),
    )

    private fun decodeTextStyle(value: dynamic): TextStyle = TextStyle(
        fontFamilies = decodeFontFamilies(value.fontFamilies),
        fontSize = (value.fontSize as Double).toFloat(),
        fontWeight = (value.fontWeight as Double).toInt(),
        italic = value.italic as Boolean,
        baselineShift = (value.baselineShift as Double).toFloat(),
        locale = value.locale as String,
    )

    private fun decodeFontFamilies(value: dynamic): List<String> {
        val items = value.unsafeCast<Array<dynamic>>()
        return buildList {
            for (item in items) add(item as String)
        }
    }

    private fun decodeSpans(value: dynamic): List<TextSpan> {
        val items = value.unsafeCast<Array<dynamic>>()
        return buildList {
            for (item in items) {
                add(
                    TextSpan(
                        TextRange((item.start as Double).toInt(), (item.end as Double).toInt()),
                        decodeTextStyle(item.style),
                    ),
                )
            }
        }
    }

    private fun decodeDecorations(value: dynamic): List<DecorationSpan> {
        val items = value.unsafeCast<Array<dynamic>>()
        return buildList {
            for (item in items) {
                add(
                    DecorationSpan(
                        TextRange((item.start as Double).toInt(), (item.end as Double).toInt()),
                        decodeDecorationKind(item.kind as String),
                    ),
                )
            }
        }
    }

    private fun decodeDecorationKind(kind: String): DecorationKind = when (kind) {
        "Emphasis" -> DecorationKind.Emphasis
        else -> DecorationKind.Emphasis
    }

    private fun decodeInlineBoxes(value: dynamic): List<InlineBoxSpan> {
        val items = value.unsafeCast<Array<dynamic>>()
        return buildList {
            for (item in items) {
                add(
                    InlineBoxSpan(
                        TextRange((item.start as Double).toInt(), (item.end as Double).toInt()),
                        (item.inlineStart as Double).toFloat(),
                        (item.inlineEnd as Double).toFloat(),
                    ),
                )
            }
        }
    }

    private fun decodeInlineObjects(value: dynamic): List<InlineObjectSpan> {
        val items = value.unsafeCast<Array<dynamic>>()
        return buildList {
            for (item in items) {
                add(
                    InlineObjectSpan(
                        TextRange((item.start as Double).toInt(), (item.end as Double).toInt()),
                        (item.advance as Double).toFloat(),
                        (item.ascent as Double).toFloat(),
                        (item.descent as Double).toFloat(),
                    ),
                )
            }
        }
    }

    private fun decodeDomInlineObjects(value: dynamic): List<DomInlineObject> {
        val items = value.unsafeCast<Array<dynamic>>()
        return buildList {
            for (item in items) {
                add(
                    DomInlineObject(
                        TextRange((item.start as Double).toInt(), (item.end as Double).toInt()),
                        item.element as Element,
                        (item.marginRight as Double).toFloat(),
                    ),
                )
            }
        }
    }

    private fun decodeSourceSpans(value: dynamic): List<DomSourceSpan> {
        val items = value.unsafeCast<Array<dynamic>>()
        return buildList {
            for (item in items) {
                add(
                    DomSourceSpan(
                        range = TextRange((item.start as Double).toInt(), (item.end as Double).toInt()),
                        element = item.element as Element,
                        depth = (item.depth as Double).toInt(),
                        cjkStrongBaseWeight = (item.cjkStrongBaseWeight as? Double)?.toInt(),
                        computedColor = item.computedColor as? String,
                        inlineBoxStyle = decodeDomInlineBoxStyle(item.inlineBoxStyle),
                    ),
                )
            }
        }
    }

    private fun decodeDomInlineBoxStyle(value: dynamic): DomInlineBoxStyle = DomInlineBoxStyle(
        inlineStart = (value.inlineStart as Double).toFloat(),
        inlineEnd = (value.inlineEnd as Double).toFloat(),
        marginRight = (value.marginRight as Double).toFloat(),
        letterSpacing = (value.letterSpacing as Double).toFloat(),
        boxDecorationBreak = value.boxDecorationBreak as String,
    )

    private fun decodeSourceBoundaries(value: dynamic): Set<Int> {
        val items = value.unsafeCast<Array<dynamic>>()
        return buildSet {
            for (item in items) add((item as Double).toInt())
        }
    }

    private fun decodeLineBreakSpans(value: dynamic): List<LineBreakSpan> {
        val items = value.unsafeCast<Array<dynamic>>()
        return buildList {
            for (item in items) {
                add(
                    LineBreakSpan(
                        TextRange((item.start as Double).toInt(), (item.end as Double).toInt()),
                        when (item.policy as String) {
                            "ProgressiveTechnical" -> LineBreakPolicy.ProgressiveTechnical
                            else -> LineBreakPolicy.ProgressiveTechnical
                        },
                    ),
                )
            }
        }
    }
}

@JsFun("(fontSize, lineHeight, strongAsEmphasisMarks, locale) => ({ fontSize: fontSize, lineHeight: lineHeight, strongAsEmphasisMarks: strongAsEmphasisMarks, locale: locale })")
private external fun loweringOptionsJs(
    fontSize: Double?,
    lineHeight: Double?,
    strongAsEmphasisMarks: Boolean,
    locale: String,
): dynamic

@JsFun("(classifyRole) => ({ classifyRole: classifyRole })")
private external fun loweringHelpersJs(
    classifyRole: (String, Int, Int, String) -> String,
): dynamic

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
 * evidence; a single-line cloned-edge box replays through the plan's
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