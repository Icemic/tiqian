@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package org.tiqian.web

import kotlin.JsFun
import kotlin.js.js
import org.tiqian.font.FontMetricsRequest
import org.tiqian.font.FontMetricsResolver
import org.tiqian.font.RawFontMetrics
import org.tiqian.shaping.ShapingInput
import org.tiqian.shaping.ShapingResult
import org.tiqian.shaping.TextShaper
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event

private const val WORKER_RECORD_SEPARATOR = '\u001e'
private const val WORKER_FIELD_SEPARATOR = '\u001d'
private const val WORKER_FAMILY_SEPARATOR = '\u001f'

internal fun workerLayoutRequestJson(
    paragraph: HTMLElement,
    lowered: LoweredParagraph,
    width: Float,
    firstLineIndentIc: Float,
): String {
    val textSpans = lowered.spans.joinToString(WORKER_RECORD_SEPARATOR.toString()) { span ->
        listOf(
            span.range.start,
            span.range.end,
            span.style.fontFamilies.joinToString(WORKER_FAMILY_SEPARATOR.toString()),
            span.style.fontSize,
            span.style.fontWeight,
            span.style.italic,
            span.style.baselineShift,
        ).joinToString(WORKER_FIELD_SEPARATOR.toString())
    }
    val inlineBoxes = lowered.inlineBoxes.joinToString(WORKER_RECORD_SEPARATOR.toString()) { box ->
        listOf(
            box.range.start,
            box.range.end,
            box.inlineStart,
            box.inlineEnd,
            box.outerSpacing.name,
        ).joinToString(WORKER_FIELD_SEPARATOR.toString())
    }
    val lineBreakSpans = lowered.lineBreakSpans.joinToString(WORKER_RECORD_SEPARATOR.toString()) { span ->
        listOf(span.range.start, span.range.end, span.policy.name)
            .joinToString(WORKER_FIELD_SEPARATOR.toString())
    }
    // WorkerInlineObjectWire: the same measured geometry the runtime lowering
    // feeds its engine (advance, ascent, descent) so the Worker lays the
    // replacement character out identically; the live element itself stays on
    // the main thread and enters at commit time.
    val inlineObjects = lowered.inlineObjects.joinToString(WORKER_RECORD_SEPARATOR.toString()) { span ->
        listOf(
            span.range.start,
            span.range.end,
            span.advance,
            span.ascent,
            span.descent,
        ).joinToString(WORKER_FIELD_SEPARATOR.toString())
    }
    return buildString {
        append('{')
        append("\"text\":").appendWorkerJsonString(lowered.text).append(',')
        append("\"maxWidthPx\":").append(width).append(',')
        append("\"fontFamilies\":").appendWorkerJsonString(
            lowered.textStyle.fontFamilies.joinToString(WORKER_FAMILY_SEPARATOR.toString()),
        ).append(',')
        append("\"fontSizePx\":").append(lowered.textStyle.fontSize).append(',')
        append("\"lineHeightPx\":").append(lowered.lineHeight).append(',')
        append("\"locale\":").appendWorkerJsonString(lowered.textStyle.locale).append(',')
        append("\"fontWeight\":").append(lowered.textStyle.fontWeight).append(',')
        append("\"italic\":").append(lowered.textStyle.italic).append(',')
        append("\"firstLineIndentIc\":").append(firstLineIndentIc).append(',')
        append("\"sourceBoundaries\":").appendWorkerJsonString(
            lowered.sourceBoundaries.sorted().joinToString(","),
        ).append(',')
        append("\"textSpans\":").appendWorkerJsonString(textSpans).append(',')
        append("\"inlineBoxes\":").appendWorkerJsonString(inlineBoxes).append(',')
        append("\"lineBreakSpans\":").appendWorkerJsonString(lineBreakSpans).append(',')
        append("\"inlineObjects\":").appendWorkerJsonString(inlineObjects).append(',')
        append("\"semantics\":[")
        lowered.sourceSpans.forEachIndexed { index, span ->
            if (index > 0) append(',')
            append('{')
            append("\"start\":").append(span.range.start).append(',')
            append("\"end\":").append(span.range.end).append(',')
            append("\"tagName\":").appendWorkerJsonString(span.element.tagName.lowercase()).append(',')
            append("\"attributes\":").append(elementAttributesJson(span.element)).append(',')
            // WorkerSemanticHierarchyOrder: sourceSpans are collected after
            // their children, so the list index identifies the live element
            // but cannot also describe outer-to-inner replay order.
            append("\"sourceIndex\":").append(index).append(',')
            append("\"order\":").append(span.depth)
            append('}')
        }
        append("],\"renderInlineBoxes\":[")
        lowered.inlineBoxes.forEachIndexed { index, box ->
            if (index > 0) append(',')
            append('{')
            append("\"start\":").append(box.range.start).append(',')
            append("\"end\":").append(box.range.end).append(',')
            append("\"inlineStartPx\":").append(box.inlineStart).append(',')
            append("\"inlineEndPx\":").append(box.inlineEnd).append(',')
            append("\"outerSpacing\":").appendWorkerJsonString(box.outerSpacing.name)
            append('}')
        }
        append("],\"sourceTag\":").appendWorkerJsonString(paragraph.tagName.lowercase())
        append('}')
    }
}

private fun StringBuilder.appendWorkerJsonString(value: String): StringBuilder {
    append('"')
    for (char in value) {
        when (char) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000c' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (char.code < 0x20) {
                append("\\u").append(char.code.toString(16).padStart(4, '0'))
            } else {
                append(char)
            }
        }
    }
    return append('"')
}

internal fun isExactFontSessionCapabilityFailure(error: Throwable): Boolean {
    return isExactFontSessionCapabilityFailureDetail(error.message.orEmpty())
}

internal fun isExactFontSessionCapabilityFailureDetail(detail: String): Boolean =
    EXACT_FONT_SESSION_CAPABILITY_FAILURES.any(detail::contains)

/**
 * SemanticExactRunFallback: rich paragraphs may intentionally introduce a
 * different font family (for example inline code). Preserve the exact server
 * HarfBuzz replay for every covered run and delegate only the unsupported run
 * to the browser adapter, whose semantic clone keeps the corresponding host style.
 */
internal class ExactSessionBrowserFallbackTextShaper(
    private val exact: TextShaper,
    private val browser: TextShaper,
) : TextShaper {
    override fun shape(input: ShapingInput): ShapingResult = try {
        exact.shape(input)
    } catch (error: Throwable) {
        if (!isExactFontSessionCapabilityFailure(error)) throw error
        browser.shape(input)
    }
}

internal class ExactSessionBrowserFallbackFontMetricsResolver(
    private val exact: FontMetricsResolver,
    private val browser: FontMetricsResolver,
) : FontMetricsResolver {
    override fun resolve(request: FontMetricsRequest): RawFontMetrics = try {
        exact.resolve(request)
    } catch (error: Throwable) {
        if (!isExactFontSessionCapabilityFailure(error)) throw error
        browser.resolve(request)
    }
}

// Opaque host options bag. Field reads stay in @JsFun bodies. Public so the
// TiqianEngine JsExport facade can name it in exported signatures.
external interface EnhanceOptionsJs

@JsFun("(options, name) => options && options[name] != null ? String(options[name]) : null")
internal external fun optionString(options: EnhanceOptionsJs?, name: String): String?
@JsFun("(options, name) => { if (!options || options[name] == null) return NaN; const number = Number(options[name]); return Number.isFinite(number) ? number : NaN; }")
internal external fun optionNumber(options: EnhanceOptionsJs?, name: String): Double
@JsFun("(options, name) => options && typeof options[name] === 'boolean' ? options[name] : null")
internal external fun optionBoolean(options: EnhanceOptionsJs?, name: String): Boolean?
@JsFun("(options, name) => options && options[name] && typeof options[name] === 'object' ? options[name] : null")
internal external fun optionObject(options: EnhanceOptionsJs?, name: String): EnhanceOptionsJs?
@JsFun("(element) => JSON.stringify(Array.from(element.attributes || [], (attribute) => [attribute.name, attribute.value]))")
private external fun elementAttributesJson(element: Element): String
@JsFun("(element, sessionKey, requestText) => globalThis.__TiqianLayoutWorker && typeof globalThis.__TiqianLayoutWorker.take === 'function' ? globalThis.__TiqianLayoutWorker.take(element, sessionKey, requestText) : null")
internal external fun takePreparedWorkerLayoutPlan(
    element: HTMLElement,
    sessionKey: String,
    requestText: String,
): String?
@JsFun("(element, sessionKey, requestText) => globalThis.__TiqianLayoutWorker && typeof globalThis.__TiqianLayoutWorker.issue === 'function' ? globalThis.__TiqianLayoutWorker.issue(element, sessionKey, requestText) : null")
internal external fun preparedWorkerLayoutIssue(
    element: HTMLElement,
    sessionKey: String,
    requestText: String,
): String?
@JsFun(
    """(host, recordJson, locale, sourceText, semanticElements, inlineObjectElements, inlineObjectMetaJson, cjkStrongSemanticsJson) => (function () {
      const record = JSON.parse(recordJson);
      const inlineObjects = Array.from(inlineObjectElements || []);
      host.__tqCustodyEngineWrites = (host.__tqCustodyEngineWrites || 0) + 1;
      try {
        return globalThis.__TiqianPreparedDomRenderer.render(
          host,
          record.plan,
          locale,
          {
            sourceText,
            semanticReplay: record.semanticReplay || "snapshot-safe",
            semantics: record.semantics || [],
            inlineBoxes: record.inlineBoxes || [],
            liveSemanticElements: semanticElements || [],
            inlineObjects: (function () {
              const meta = JSON.parse(inlineObjectMetaJson || "[]");
              return meta.map(function (entry, index) {
                return {
                  start: entry.start,
                  end: entry.end,
                  marginRight: entry.marginRight,
                  element: inlineObjects[index]
                };
              });
            })(),
            cjkStrongSemantics: JSON.parse(cjkStrongSemanticsJson || "[]")
          }
        );
      } finally {
        host.__tqCustodyEngineWrites -= 1;
      }
    })()""",
)
internal external fun renderPreparedWorkerParagraphDom(
    host: HTMLElement,
    recordJson: String,
    locale: String,
    sourceText: String,
    semanticElements: Array<Element>,
    inlineObjectElements: Array<Element>,
    inlineObjectMetaJson: String,
    cjkStrongSemanticsJson: String,
)
@JsFun("(host) => !!(globalThis.__TiqianPreparedDomRenderer && globalThis.__TiqianPreparedDomRenderer.release && globalThis.__TiqianPreparedDomRenderer.release(host) === true)")
internal external fun releasePreparedParagraphDomStyles(host: HTMLElement): Boolean
@JsFun("(root) => !!(globalThis.__TiqianPreparedDomRenderer && globalThis.__TiqianPreparedDomRenderer.releaseRoot && globalThis.__TiqianPreparedDomRenderer.releaseRoot(root) === true)")
internal external fun releasePreparedRootDomStyles(root: HTMLElement): Boolean
// RuntimePreparedDomBridgeCapability: the prepared renderer bridge is a host
// installation (the font loader), not a runtime builtin. A host without the
// bridge must keep the native renderer instead of failing every paragraph at
// the unguarded render call. Schema and layout revision must match the plan
// wire the runtime itself serializes, so a stale cached bridge also stays on
// the native path.
@JsFun(
    """(expectedLayoutRevision) => !!(globalThis.__TiqianPreparedDomRenderer &&
      typeof globalThis.__TiqianPreparedDomRenderer.render === 'function' &&
      typeof globalThis.__TiqianPreparedDomRenderer.release === 'function' &&
      typeof globalThis.__TiqianPreparedDomRenderer.releaseRoot === 'function' &&
      globalThis.__TiqianPreparedDomRenderer.schema === 1 &&
      globalThis.__TiqianPreparedDomRenderer.layoutRevision === expectedLayoutRevision)""",
)
internal external fun isPreparedDomBridgeAvailable(expectedLayoutRevision: String): Boolean
// PreparedDomValidatorIsTestOnly: the validator global exists only in test
// worlds; production has no replay validator and an absent one reports no
// issue instead of failing every commit.
@JsFun("(host, width) => globalThis.__TiqianPreparedDomValidator && typeof globalThis.__TiqianPreparedDomValidator.issue === 'function' ? globalThis.__TiqianPreparedDomValidator.issue(host, width) : null")
internal external fun validatePreparedParagraphDom(host: HTMLElement, width: Double): String?
@JsFun(
    """(element) => {
      const rect = element.getBoundingClientRect();
      const viewportHeight = window.innerHeight || document.documentElement.clientHeight || 0;
      if (rect.bottom >= 0 && rect.top <= viewportHeight) return 0;
      return rect.bottom < 0 ? -rect.bottom : rect.top - viewportHeight;
    }""",
)
internal external fun paragraphViewportDistance(element: HTMLElement): Double
@JsFun(
    """(element) => {
      const rect = element.getBoundingClientRect();
      const viewportHeight = window.innerHeight || document.documentElement.clientHeight || 0;
      return rect.bottom >= 0 && rect.top <= viewportHeight;
    }""",
)
internal external fun paragraphIsWithinProgressiveForegroundRange(element: HTMLElement): Boolean
// CssFragmentedBlockInlineMeasure: plain getBoundingClientRect().width — for
// a block fragmented by CSS columns this is the union of every fragment, not
// a per-fragment measure. Every caller uses it only for coarse ≥0.5px drift
// detection, where the union error is dwarfed by the tolerance (see the ADR
// 0039 fractional fragment-aware amendment). A caller that needs the widest
// live fragment must use elementContentWidth from
// npm/core/engine/responsive-measure.js (installed as the responsive measure
// bridge) instead.
@JsFun(
    """(element) => {
      if (!element) return 0;
      return element.getBoundingClientRect ? element.getBoundingClientRect().width : 0;
    }""",
)
internal external fun elementFragmentBorderBoxInlineSize(element: HTMLElement): Double
@JsFun("(element, property) => getComputedStyle(element).getPropertyValue(property)")
internal external fun computedStyle(element: Element, property: String): String

internal fun parseCssPx(value: String): Float? {
    val trimmed = value.trim()
    if (!trimmed.endsWith("px")) return null
    return trimmed.removeSuffix("px").trim().toFloatOrNull()
}

@JsFun("(element, selector) => !!element.closest(selector)")
internal external fun hasClosest(element: HTMLElement, selector: String): Boolean
@JsFun(
    "(paragraph, root, selector) => { const owner = paragraph.closest(selector); return !owner || owner === root || !root.contains(owner); }",
)
internal external fun belongsToRootScope(paragraph: HTMLElement, root: HTMLElement, selector: String): Boolean
@JsFun("(message) => console.warn(message)")
internal external fun consoleWarn(message: String)
// CustodyEngineWriteSuspension: the prepared DOM bridge writes engine output
// into the live paragraph with plain element and text arguments, which the
// forwarding overrides would otherwise redirect into custody. The two bridge
// entry points below raise __tqCustodyEngineWrites for the duration of their
// synchronous render, and the overrides above run native while it is positive.
// @JsFun bodies inline into their Kotlin callers, so each body is a single
// IIFE expression: a bare return statement would return from the caller.
@JsFun(
    """(host, planJson, locale, sourceText, semanticElements, semanticsJson, inlineObjectElements, inlineObjectMetaJson, cjkStrongSemanticsJson) => (function () {
      const semantics = Array.from(semanticElements || []);
      const inlineObjects = Array.from(inlineObjectElements || []);
      const hasLiveSources = semantics.length > 0 || inlineObjects.length > 0;
      host.__tqCustodyEngineWrites = (host.__tqCustodyEngineWrites || 0) + 1;
      try {
        return globalThis.__TiqianPreparedDomRenderer.render(
          host,
          planJson,
          locale,
          hasLiveSources ? {
            sourceText: sourceText,
            semanticReplay: "live-source",
            semantics: JSON.parse(semanticsJson || "[]"),
            liveSemanticElements: semantics,
            inlineObjects: (function () {
              const meta = JSON.parse(inlineObjectMetaJson || "[]");
              return meta.map(function (entry, index) {
                return {
                  start: entry.start,
                  end: entry.end,
                  marginRight: entry.marginRight,
                  element: inlineObjects[index]
                };
              });
            })(),
            cjkStrongSemantics: JSON.parse(cjkStrongSemanticsJson || "[]")
          } : undefined
        );
      } finally {
        host.__tqCustodyEngineWrites -= 1;
      }
    })()""",
)
internal external fun renderPreparedParagraphDom(
    host: HTMLElement,
    planJson: String,
    locale: String,
    sourceText: String,
    semanticElements: Array<Element>,
    semanticsJson: String,
    inlineObjectElements: Array<Element>,
    inlineObjectMetaJson: String,
    cjkStrongSemanticsJson: String,
)

// RuntimeRichPreparedDomOptions (ADR 0053 B8.1): the runtime bridge replays
// live semantics exactly like the Worker adoption bridge. Source spans become
// live-source semantics whose sourceIndex addresses the same-order element
// array and whose order carries the nesting depth as the tie-break; DOM
// inline objects ride as {start, end, marginRight} metadata paired with an
// element array. Both arrays are empty for canonical plain paragraphs, which
// keeps their option-less render byte-identical.
internal fun LoweredParagraph.preparedSemanticReplayJson(): String = buildString {
    append('[')
    sourceSpans.forEachIndexed { index, span ->
        if (index > 0) append(',')
        append('{')
        append("\"start\":").append(span.range.start).append(',')
        append("\"end\":").append(span.range.end).append(',')
        append("\"tagName\":").appendWorkerJsonString(span.element.tagName.lowercase()).append(',')
        append("\"sourceIndex\":").append(index).append(',')
        append("\"order\":").append(span.depth)
        append('}')
    }
    append(']')
}

internal fun LoweredParagraph.preparedInlineObjectMetaJson(): String = buildString {
    append('[')
    domInlineObjects.forEachIndexed { index, objectSpan ->
        if (index > 0) append(',')
        append('{')
        append("\"start\":").append(objectSpan.range.start).append(',')
        append("\"end\":").append(objectSpan.range.end).append(',')
        append("\"marginRight\":").append(objectSpan.marginRight)
        append('}')
    }
    append(']')
}

// PreparedCjkStrongSemantics: strong-as-emphasis lowering records the
// inherited base weight on each weighted source span; the native renderer
// replayed it as data-tq-cjk-emphasis plus a font-weight override on the
// cloned span. The prepared lowerer replays the same marks from this
// metadata, matched by range equality. Empty unless strong-as-emphasis
// lowering produced weighted spans.
internal fun LoweredParagraph.preparedCjkStrongSemanticsJson(): String = buildString {
    append('[')
    var first = true
    for (span in sourceSpans) {
        val weight = span.cjkStrongBaseWeight ?: continue
        if (!first) append(',')
        first = false
        append('{')
        append("\"start\":").append(span.range.start).append(',')
        append("\"end\":").append(span.range.end).append(',')
        append("\"weight\":").append(weight)
        append('}')
    }
    append(']')
}
// ClockTierDiscipline: slices receive a millisecond budget from the caller, so
// the runtime measures elapsed time on the cheap coarse clock.
@JsFun("() => Date.now()")
internal external fun dateNow(): Double
@JsFun("(root) => { const value = Number(root.getAttribute('data-tiqian-snapshot-count')); return Number.isSafeInteger(value) && value > 0 ? value : 0; }")
internal external fun observableSnapshotCount(root: HTMLElement): Int
@JsFun("(root, enhancedCount, runtimeEnhancedCount, snapshotCount, issueCount, durationMs, maxSliceMs, stale) => root.dispatchEvent(new CustomEvent('tiqian:ready', { bubbles: true, composed: true, detail: { enhancedCount, runtimeEnhancedCount, snapshotCount, issueCount, durationMs, maxSliceMs, stale } }))")
internal external fun dispatchTiqianReady(
    root: HTMLElement,
    enhancedCount: Int,
    runtimeEnhancedCount: Int,
    snapshotCount: Int,
    issueCount: Int,
    durationMs: Double,
    maxSliceMs: Double,
    stale: Boolean,
)
@JsFun("(root, enhancedCount, runtimeEnhancedCount, snapshotCount, issueCount, durationMs, maxSliceMs, failed, error, stale) => root.dispatchEvent(new CustomEvent('tiqian:relayout-ready', { bubbles: true, composed: true, detail: { enhancedCount, runtimeEnhancedCount, snapshotCount, issueCount, durationMs, maxSliceMs, relayout: true, failed, error, stale } }))")
internal external fun dispatchTiqianRelayoutReady(
    root: HTMLElement,
    enhancedCount: Int,
    runtimeEnhancedCount: Int,
    snapshotCount: Int,
    issueCount: Int,
    durationMs: Double,
    maxSliceMs: Double,
    failed: Boolean,
    error: String?,
    stale: Boolean,
)
@JsFun("(root, kind, detail, durationMs, maxSliceMs) => root.dispatchEvent(new CustomEvent(kind === 'Relayout' ? 'tiqian:relayout-error' : 'tiqian:error', { bubbles: true, composed: true, detail: { kind, error: detail, durationMs, maxSliceMs } }))")
internal external fun dispatchTiqianProgressiveError(
    root: HTMLElement,
    kind: String,
    detail: String,
    durationMs: Double,
    maxSliceMs: Double,
)

internal const val DEFAULT_FONT_SIZE = 19f
internal const val INLINE_EDGE_EPSILON = 0.01f
internal const val ZERO_ADVANCE_EPSILON = 0.01f
internal const val CAPABILITY_DETAIL_LIMIT = 512
internal const val CANONICAL_SOURCE_ATTRIBUTE = "data-tq-canonical-source"
internal const val EXACT_PREPARED_DOM_ATTRIBUTE = "data-tq-exact-prepared-dom"
internal const val RUNTIME_RENDER_FONT_ATTRIBUTE = "data-tq-runtime-render-font"
internal const val HOST_INLINE_SIZE_ATTRIBUTE = "data-tq-host-inline-size"
internal const val RELAYOUT_ERROR_ATTRIBUTE = "data-tiqian-relayout-error"
internal const val EXACT_PREPARED_FALLBACK_ATTRIBUTE = "data-tiqian-exact-layout-fallback"
internal const val DEFAULT_CJK_FONT_FAMILY = "\"MiSans VF\", \"PingFang SC\", \"Noto Sans CJK SC\", sans-serif"
internal const val DEFAULT_LATIN_FONT_FAMILY = "\"InterVariable\", \"Inter\", \"MiSans VF\", sans-serif"
internal const val DEFAULT_MONOSPACE_FONT_FAMILY =
    "\"JetBrains Mono Variable\", \"SFMono-Regular\", Menlo, Consolas, \"MiSans VF\", monospace"
internal const val DEFAULT_CJK_SERIF_FONT_FAMILY = "\"MetroSungPlus-SC\", \"Songti SC\", serif"
internal const val DEFAULT_LATIN_SERIF_FONT_FAMILY = "Georgia, \"Times New Roman\", serif"

private val EXACT_FONT_SESSION_CAPABILITY_FAILURES = listOf(
    "NoExactFontFace",
    "MissingGlyph",
    "MissingServerShapingReplay",
    "NoExactMetricFace",
    "NonUniformUnicodeRangeMetrics",
)

internal val WIDTH_DEPENDENT_CAPABILITY_ISSUES = setOf(
    "InlineCloneDecorationBreakUnsupported",
)

