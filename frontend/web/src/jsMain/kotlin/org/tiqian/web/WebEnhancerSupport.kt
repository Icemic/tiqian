@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package org.tiqian.web

import kotlin.JsFun
import kotlin.js.JsAny
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

@JsFun("(event) => event.detail && event.detail.root ? event.detail.root : null")
internal external fun eventRoot(event: Event): HTMLElement?
@JsFun("(event) => event.detail && event.detail.paragraph ? event.detail.paragraph : null")
internal external fun eventParagraph(event: Event): HTMLElement?
@JsFun("(event) => event.detail && event.detail.options && Array.isArray(event.detail.options.paragraphs) ? event.detail.options.paragraphs : []")
internal external fun eventParagraphs(event: Event): Array<HTMLElement>
@JsFun("(event) => event.detail && event.detail.options ? event.detail.options : null")
internal external fun eventOptions(event: Event): JsAny?
@JsFun("(event, value) => { if (event.detail) event.detail.result = value; }")
internal external fun setEventResult(event: Event, value: String?)
@JsFun("(options, name) => options && options[name] != null ? String(options[name]) : null")
internal external fun optionString(options: JsAny?, name: String): String?
@JsFun("(options, name) => { if (!options || options[name] == null) return NaN; const number = Number(options[name]); return Number.isFinite(number) ? number : NaN; }")
internal external fun optionNumber(options: JsAny?, name: String): Double
@JsFun("(options, name) => options && typeof options[name] === 'boolean' ? options[name] : null")
internal external fun optionBoolean(options: JsAny?, name: String): Boolean?
@JsFun("(options, name) => options && options[name] && typeof options[name] === 'object' ? options[name] : null")
internal external fun optionObject(options: JsAny?, name: String): JsAny?
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
    """(host, recordJson, locale, sourceText, semanticElements) => (function () {
      const record = JSON.parse(recordJson);
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
            liveSemanticElements: semanticElements || []
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
): JsAny?
@JsFun("(host) => !!(globalThis.__TiqianPreparedDomRenderer && globalThis.__TiqianPreparedDomRenderer.release && globalThis.__TiqianPreparedDomRenderer.release(host) === true)")
internal external fun releasePreparedParagraphDomStyles(host: HTMLElement): Boolean
@JsFun("(root) => !!(globalThis.__TiqianPreparedDomRenderer && globalThis.__TiqianPreparedDomRenderer.releaseRoot && globalThis.__TiqianPreparedDomRenderer.releaseRoot(root) === true)")
internal external fun releasePreparedRootDomStyles(root: HTMLElement): Boolean
@JsFun("(host, width) => globalThis.__TiqianPreparedDomValidator && typeof globalThis.__TiqianPreparedDomValidator.issue === 'function' ? globalThis.__TiqianPreparedDomValidator.issue(host, width) : 'PreparedDomValidatorUnavailable'")
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
// CssFragmentedBlockInlineMeasure: getBoundingClientRect() unions every CSS
// multi-column fragment and therefore grows horizontally with the number of
// occupied columns. A paragraph is still laid out against one fragmentainer;
// use the widest live fragment as its stable horizontal border-box measure.
@JsFun(
    """(element) => {
      if (!element) return 0;
      return element.getBoundingClientRect ? element.getBoundingClientRect().width : 0;
    }""",
)
internal external fun elementFragmentBorderBoxInlineSize(element: HTMLElement): Double
@JsFun(
    """(element) => {
      if (!element) return 0;
      const style = getComputedStyle(element);
      const number = (value) => Number.parseFloat(value) || 0;
      // FractionalFragmentContentMeasure: clientWidth rounds to integer
      // pixels, so a width change below 0.5px can go undetected and a
      // font-size grid crossing at a fractional width can be missed.
      // Inline-style probes cannot see padding declared in a stylesheet,
      // such as li { padding-inline-start }. getBoundingClientRect()
      // returns the union of all CSS column fragments. Take the widest
      // live client rect instead; it is the border box of a single
      // fragment. Then subtract the computed padding and borders.
      const fallback = element.getBoundingClientRect().width;
      const rects = Array.from(element.getClientRects()).filter((rect) => rect.width > 0);
      const borderBoxWidth = rects.length <= 1
        ? fallback
        : Math.max(...rects.map((rect) => rect.width));
      return borderBoxWidth - number(style.paddingLeft) - number(style.paddingRight) -
        number(style.borderLeftWidth) - number(style.borderRightWidth);
    }""",
)
internal external fun elementContentWidth(element: HTMLElement): Double
// NestedInlineBoxEdgeOwnership: compare an inline's flow edge with its direct
// in-flow content boundary. A descendant semantic box owns its own padding,
// margins and pseudo content, so an outer <sup>/<span> must not reserve that
// same edge again merely because Range.getClientRects() ends on a deep text leaf.
@JsFun(
    """(element, side) => {
      const style = getComputedStyle(element);
      const margin = Number.parseFloat(
        side === "start" ? style.marginLeft : style.marginRight
      ) || 0;
      const boxes = Array.from(element.getClientRects()).filter((rect) => rect.width || rect.height);
      if (!boxes.length) return margin;
      const boundary = (node) => {
        if (node.nodeType === Node.TEXT_NODE) {
          const range = document.createRange();
          range.selectNodeContents(node);
          const rects = Array.from(range.getClientRects()).filter((rect) => rect.width || rect.height);
          if (!rects.length) return null;
          return side === "start" ? rects[0].left : rects[rects.length - 1].right;
        }
        if (node.nodeType !== Node.ELEMENT_NODE) return null;
        const childStyle = getComputedStyle(node);
        if (childStyle.display === "none" || childStyle.position === "absolute" ||
            childStyle.position === "fixed") return null;
        const rects = Array.from(node.getClientRects()).filter((rect) => rect.width || rect.height);
        if (rects.length) {
          const rect = side === "start" ? rects[0] : rects[rects.length - 1];
          const childMargin = Number.parseFloat(
            side === "start" ? childStyle.marginLeft : childStyle.marginRight
          ) || 0;
          return side === "start" ? rect.left - childMargin : rect.right + childMargin;
        }
        const children = Array.from(node.childNodes);
        if (side === "end") children.reverse();
        for (const child of children) {
          const value = boundary(child);
          if (value != null) return value;
        }
        return null;
      };
      const children = Array.from(element.childNodes);
      if (side === "end") children.reverse();
      let contentBoundary = null;
      for (const child of children) {
        contentBoundary = boundary(child);
        if (contentBoundary != null) break;
      }
      if (contentBoundary == null) return margin;
      const flowEdge = side === "start"
        ? boxes[0].left - margin
        : boxes[boxes.length - 1].right + margin;
      return side === "start"
        ? Math.max(0, contentBoundary - flowEdge)
        : Math.max(0, flowEdge - contentBoundary);
    }""",
)
internal external fun measuredInlineEdge(element: Element, side: String): Double
@JsFun(
    """(element) => {
      if (!element.parentNode || getComputedStyle(element).display === "contents") return 0;
      const makeProbe = () => {
        const probe = document.createElement("span");
        probe.setAttribute("data-tq-baseline-probe", "");
        probe.style.cssText = "display:inline-block!important;width:0!important;height:0!important;" +
          "margin:0!important;padding:0!important;border:0!important;font-size:0!important;" +
          "line-height:0!important;vertical-align:baseline!important;position:static!important;";
        return probe;
      };
      const outer = makeProbe();
      const inner = makeProbe();
      try {
        element.parentNode.insertBefore(outer, element);
        element.insertBefore(inner, element.firstChild);
        return inner.getBoundingClientRect().bottom - outer.getBoundingClientRect().bottom;
      } finally {
        inner.remove();
        outer.remove();
      }
    }""",
)
internal external fun measuredInlineBaselineShift(element: Element): Double
@JsFun(
    """(element) => {
      const parent = element.parentNode;
      if (!parent) return "";
      const style = getComputedStyle(element);
      if (style.position === "absolute" || style.position === "fixed" ||
          style.getPropertyValue("float") !== "none" || style.transform !== "none") return "";
      const rect = element.getBoundingClientRect();
      if (!Number.isFinite(rect.width) || !Number.isFinite(rect.height) ||
          rect.width <= 0 || rect.height <= 0) return "";
      const number = (value) => Number.parseFloat(value) || 0;
      const probe = document.createElement("span");
      probe.setAttribute("data-tq-baseline-probe", "");
      probe.style.cssText = "display:inline-block!important;width:0!important;height:0!important;" +
        "margin:0!important;padding:0!important;border:0!important;font-size:0!important;" +
        "line-height:0!important;vertical-align:baseline!important;position:static!important;";
      try {
        parent.insertBefore(probe, element.nextSibling);
        const baseline = probe.getBoundingClientRect().bottom;
        const advance = rect.width + number(style.marginLeft) + number(style.marginRight);
        const ascent = Math.max(0, baseline - rect.top + number(style.marginTop));
        const descent = Math.max(0, rect.bottom - baseline + number(style.marginBottom));
        return [advance, ascent, descent].join(",");
      } finally {
        probe.remove();
      }
    }""",
)
internal external fun measuredOpaqueInlineObjectGeometry(element: Element): String
@JsFun(
    """(element) => {
      if (element.hasAttribute("data-tiqian-static-inline-object")) return true;
      const name = element.localName || "";
      if (name.includes("-")) return false;
      const interactive = "a,button,input,select,textarea,iframe,object,embed,audio,video,canvas,[contenteditable='true'],[tabindex]";
      if (element.matches(interactive) || element.querySelector(interactive)) return false;
      const nodes = [element, ...element.querySelectorAll("*")];
      return !nodes.some((node) => Array.from(node.attributes || []).some((attr) =>
        attr.name.toLowerCase().startsWith("on")
      ));
    }""",
)
internal external fun isCloneSafeOpaqueInlineObject(element: Element): Boolean
@JsFun("(element, property) => getComputedStyle(element).getPropertyValue(property)")
internal external fun computedStyle(element: Element, property: String): String
@JsFun(
    """(element, pseudo) => {
      const style = getComputedStyle(element, pseudo);
      const content = style.getPropertyValue('content').trim();
      if (!content || content === 'none' || content === 'normal' || content === '""' || content === "''") return null;
      if (style.display === 'none' || style.position === 'absolute' || style.position === 'fixed') return null;
      return content;
    }""",
)
internal external fun flowParticipatingPseudoContent(element: Element, pseudo: String): String?
@JsFun(
    """() => typeof Intl !== 'undefined' && Intl.Segmenter
      ? new Intl.Segmenter(undefined, { granularity: 'grapheme' })
      : null""",
)
internal external fun createLowererGraphemeSegmenter(): JsAny?
@JsFun(
    """(segmenter, text) => {
      const boundaries = [0];
      if (segmenter) {
        for (const item of segmenter.segment(text)) {
          if (item.index > 0) boundaries.push(item.index);
        }
      } else {
        let offset = 0;
        for (const point of Array.from(text)) {
          offset += point.length;
          if (offset < text.length) boundaries.push(offset);
        }
      }
      boundaries.push(text.length);
      return boundaries.join(',');
    }""",
)
internal external fun lowererGraphemeBoundaries(segmenter: JsAny?, text: String): String
@JsFun("(element, selector) => !!element.closest(selector)")
internal external fun hasClosest(element: HTMLElement, selector: String): Boolean
@JsFun(
    "(paragraph, root, selector) => { const owner = paragraph.closest(selector); return !owner || owner === root || !root.contains(owner); }",
)
internal external fun belongsToRootScope(paragraph: HTMLElement, root: HTMLElement, selector: String): Boolean
@JsFun("(message) => console.warn(message)")
internal external fun consoleWarn(message: String)
// CustodyAnchoredCommitForwarding: React and Svelte hold the paragraph element
// as their commit parent and mutate children through removeChild, insertBefore,
// replaceChild and appendChild. Takeover moved those children into the custody
// fragment, so the anchored calls would throw NotFoundError, and the append
// forms would drop the node into the live paragraph where the next restore
// drains it as engine output. Forward each host operation into the current
// custody fragment. Engine writes stay native: the renderer swaps output with
// replaceChildren, the custody restore and the rollback snapshot append
// DocumentFragment arguments, and the prepared DOM bridge raises
// __tqCustodyEngineWrites around its own host writes (innerHTML plus per-node
// appends of live clones). The overrides read the published fragment at call
// time, so a re-take with a fresh fragment needs no re-install. An empty
// fragment means the paragraph is not under custody, and every branch then
// falls through to native.
@JsFun(
    """(paragraph) => {
      if (!paragraph.__tqCustodyForwarding) {
        const nativeRemoveChild = Node.prototype.removeChild;
        const nativeInsertBefore = Node.prototype.insertBefore;
        const nativeReplaceChild = Node.prototype.replaceChild;
        const nativeAppendChild = Node.prototype.appendChild;
        const activeCustody = () => {
          const fragment = paragraph.__tqCustodyFragment;
          return fragment && fragment.childNodes.length > 0 ? fragment : null;
        };
        const heldInCustody = (node) => {
          const fragment = paragraph.__tqCustodyFragment;
          return !!fragment && !!node && node.parentNode === fragment;
        };
        const engineWriting = () => paragraph.__tqCustodyEngineWrites > 0;
        paragraph.removeChild = (child) => {
          if (engineWriting()) return nativeRemoveChild.call(paragraph, child);
          if (heldInCustody(child)) return paragraph.__tqCustodyFragment.removeChild(child);
          return nativeRemoveChild.call(paragraph, child);
        };
        paragraph.insertBefore = (node, ref) => {
          if (engineWriting()) return nativeInsertBefore.call(paragraph, node, ref);
          if (heldInCustody(ref)) return paragraph.__tqCustodyFragment.insertBefore(node, ref);
          if (!ref && node && node.nodeType !== 11) {
            const fragment = activeCustody();
            if (fragment) return fragment.appendChild(node);
          }
          return nativeInsertBefore.call(paragraph, node, ref);
        };
        paragraph.replaceChild = (next, prev) => {
          if (engineWriting()) return nativeReplaceChild.call(paragraph, next, prev);
          if (heldInCustody(prev)) return paragraph.__tqCustodyFragment.replaceChild(next, prev);
          return nativeReplaceChild.call(paragraph, next, prev);
        };
        paragraph.appendChild = (node) => {
          if (engineWriting()) return nativeAppendChild.call(paragraph, node);
          if (node && node.nodeType !== 11) {
            const fragment = activeCustody();
            if (fragment) return fragment.appendChild(node);
          }
          return nativeAppendChild.call(paragraph, node);
        };
        paragraph.__tqCustodyForwarding = true;
      }
    }""",
)
internal external fun installCustodyCommitForwarding(paragraph: HTMLElement)
// CustodyEngineWriteSuspension: the prepared DOM bridge writes engine output
// into the live paragraph with plain element and text arguments, which the
// forwarding overrides would otherwise redirect into custody. The two bridge
// entry points below raise __tqCustodyEngineWrites for the duration of their
// synchronous render, and the overrides above run native while it is positive.
// @JsFun bodies inline into their Kotlin callers, so each body is a single
// IIFE expression: a bare return statement would return from the caller.
@JsFun(
    """(host, planJson, locale) => (function () {
      host.__tqCustodyEngineWrites = (host.__tqCustodyEngineWrites || 0) + 1;
      try {
        return globalThis.__TiqianPreparedDomRenderer.render(host, planJson, locale);
      } finally {
        host.__tqCustodyEngineWrites -= 1;
      }
    })()""",
)
internal external fun renderPreparedParagraphDom(
    host: HTMLElement,
    planJson: String,
    locale: String,
): JsAny?
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
internal fun installTiqianGlobalApiBridge() {
    js(
        """
        if (!globalThis.TiqianWeb || !globalThis.TiqianWeb.__tiqianKotlinBridge) {
          globalThis.TiqianWeb = {
            __tiqianKotlinBridge: true,
            enhance(root, options) {
              document.dispatchEvent(new CustomEvent("tiqian:enhance", {
                detail: { root: root || document.body, options: options || {} }
              }));
              return root || document.body;
            },
            enhanceProgressively(root, options) {
              document.dispatchEvent(new CustomEvent("tiqian:enhance-progressively", {
                detail: { root: root || document.body, options: options || {} }
              }));
              return root || document.body;
            },
            workerLayoutRequest(root, paragraph, options) {
              var detail = {
                root: root || document.body,
                paragraph: paragraph,
                options: options || {},
                result: null
              };
              document.dispatchEvent(new CustomEvent("tiqian:worker-layout-request", { detail }));
              return detail.result;
            },
            destroy(root) {
              document.dispatchEvent(new CustomEvent("tiqian:destroy", {
                detail: { root: root || document.body }
              }));
            },
            enhanceAll(options) {
              document.dispatchEvent(new CustomEvent("tiqian:enhance-all", {
                detail: { options: options || {} }
              }));
            }
          };
        }
        """,
    )
}

/**
 * Package entrypoints install `SourceFaithfulSemanticClipboard` from copy.js.
 * This fallback mirrors that contract for direct Kotlin/JS runtime consumers.
 */
internal fun installTiqianCopyHandler() {
    js(
        """
        if (globalThis.__TiqianInstallCopyHandler) {
          globalThis.__TiqianInstallCopyHandler(document);
        } else if (!globalThis.__tiqianCopyHandlerInstalled) {
          var blockElements = new Set([
            "ADDRESS", "ARTICLE", "ASIDE", "BLOCKQUOTE", "DD", "DIV", "DL", "DT",
            "FIELDSET", "FIGCAPTION", "FIGURE", "FOOTER", "FORM", "H1", "H2", "H3",
            "H4", "H5", "H6", "HEADER", "HR", "LI", "MAIN", "NAV", "OL", "P", "PRE",
            "SECTION", "TABLE", "TR", "UL"
          ]);
          var engineFlowStyleProperties = [
            "white-space-collapse", "overflow-wrap", "text-autospace", "text-spacing-trim",
            "text-wrap-mode", "-webkit-hyphens", "hyphens", "word-break"
          ];
          var clipboardTextForNode;
          var clipboardTextForChildren = function (parent) {
            var children = Array.from(parent.childNodes || []);
            var containsBlock = children.some(function (child) {
              return child.nodeType === 1 && blockElements.has(child.tagName);
            });
            var result = "";
            var previous = null;
            children.forEach(function (child) {
              if (containsBlock && child.nodeType === 3 && !(child.data || "").trim()) return;
              var item = clipboardTextForNode(child);
              if (previous && (previous.block || item.block) && result && item.text &&
                  !result.endsWith("\n") && !item.text.startsWith("\n")) {
                result += "\n";
              }
              result += item.text;
              previous = item;
            });
            return result;
          };
          clipboardTextForNode = function (node) {
            if (node.nodeType === 3) return { block: false, text: node.data || "" };
            if (node.nodeType !== 1) return { block: false, text: "" };
            if (node.tagName === "BR") return { block: false, text: "\n" };
            return {
              block: blockElements.has(node.tagName),
              text: clipboardTextForChildren(node)
            };
          };
          globalThis.__TiqianCreateClipboardPayload = function (frag, documentObject) {
            if (!frag || !frag.querySelectorAll || !documentObject || !documentObject.createElement) {
              return { text: "", html: "" };
            }
            frag.querySelectorAll("[data-tq-copy-ignore]").forEach(function (el) { el.remove(); });
            frag.querySelectorAll("[data-tq-src]").forEach(function (el) {
              if (el.hasAttribute("data-tq-hard-break")) {
                var semanticBreak = el.nextElementSibling;
                if (semanticBreak && semanticBreak.matches &&
                    semanticBreak.matches("br[data-tq-engine-break='MandatoryBreak']")) {
                  el.remove();
                } else {
                  el.replaceWith(documentObject.createElement("br"));
                }
              } else {
                el.replaceWith(documentObject.createTextNode(el.getAttribute("data-tq-src") || ""));
              }
            });
            frag.querySelectorAll(
              "[data-tq-engine-break]:not([data-tq-engine-break='MandatoryBreak'])"
            ).forEach(function (el) { el.remove(); });
            Array.from(frag.querySelectorAll("[data-tq-geometry]")).reverse().forEach(function (el) {
              el.replaceWith.apply(el, Array.from(el.childNodes));
            });
            frag.querySelectorAll("*").forEach(function (el) {
              var rendered = el.hasAttribute("data-tq-rendered");
              var sourceSemantic = el.hasAttribute("data-tq-source-semantic");
              var cjkStrong = el.hasAttribute("data-tq-cjk-emphasis");
              if (el.style && (rendered || sourceSemantic)) {
                engineFlowStyleProperties.forEach(function (property) { el.style.removeProperty(property); });
                if (rendered) el.style.removeProperty("position");
                if (!(el.getAttribute("style") || "").trim()) el.removeAttribute("style");
              }
              if (cjkStrong && el.style) {
                el.style.removeProperty("font-weight");
                if (!(el.getAttribute("style") || "").trim()) el.removeAttribute("style");
              }
              Array.from(el.attributes).forEach(function (attribute) {
                if (attribute.name.startsWith("data-tq-")) el.removeAttribute(attribute.name);
              });
            });
            var wrapper = documentObject.createElement("div");
            wrapper.appendChild(frag);
            return { text: clipboardTextForChildren(wrapper), html: wrapper.innerHTML };
          };
          if (!globalThis.__tiqianCopyHandlerInstalled) {
            globalThis.__tiqianCopyHandlerInstalled = true;
            document.addEventListener("copy", function (e) {
              var sel = window.getSelection();
              if (!sel || sel.isCollapsed || sel.rangeCount === 0) return;
              var range = sel.getRangeAt(0);
              var renderedAncestor = function (node) {
                var element = node && node.nodeType === 1 ? node : node && node.parentElement;
                return element && element.closest ? element.closest("[data-tq-rendered]") : null;
              };
              var touchesRendered = !!renderedAncestor(range.startContainer) ||
                !!renderedAncestor(range.endContainer);
              if (!touchesRendered) {
                var common = range.commonAncestorContainer;
                var commonElement = common && common.nodeType === 1 ? common : common && common.parentElement;
                var candidates = commonElement && commonElement.querySelectorAll
                  ? Array.from(commonElement.querySelectorAll("[data-tq-rendered]"))
                  : [];
                if (commonElement && commonElement.matches && commonElement.matches("[data-tq-rendered]")) {
                  candidates.unshift(commonElement);
                }
                touchesRendered = candidates.some(function (candidate) {
                  try { return range.intersectsNode(candidate); } catch (_) { return false; }
                });
              }
              if (!touchesRendered) return;
              var payload = globalThis.__TiqianCreateClipboardPayload(range.cloneContents(), document);
              if ((payload.text || payload.html) && e.clipboardData) {
                e.clipboardData.setData("text/plain", payload.text);
                if (payload.html) e.clipboardData.setData("text/html", payload.html);
                e.preventDefault();
              }
            });
          }
        }
        """,
    )
}

internal const val DEFAULT_FONT_SIZE = 19f
internal const val INLINE_EDGE_EPSILON = 0.01f
internal const val ZERO_ADVANCE_EPSILON = 0.01f
internal const val CAPABILITY_DETAIL_LIMIT = 512
// StandaloneGrantAdmission caps for slices without a coordinator grant:
// the millisecond cap bounds wall time, the item cap backs it up against
// coarse-clock truncation. Coordinated grants carry their own deadline and
// quota inside the grant controller.
internal const val MAX_PROGRESSIVE_SLICE_MS = 8.0
internal const val MAX_PROGRESSIVE_ITEMS_PER_SLICE = 8
// ParagraphTierGating: three paragraph priority bands the coordinator polls
// per attached root. Tier 1 is in viewport, tier 2 near viewport, tier 3 far.
// A gate of PROGRESSIVE_TIER_COUNT admits every tier; run-to-completion jobs
// use it as their default gate.
internal const val PROGRESSIVE_TIER_COUNT = 3
internal const val PROGRESSIVE_TIER_IN_VIEWPORT = 1
// ViewportForegroundIdleTail: visible and one-viewport-adjacent paragraphs
// receive frame-budgeted work. The remaining native source stays responsive
// and advances one paragraph per input-gapped idle callback so long articles
// cannot occupy every animation frame during scrolling or window resizing.
internal const val MAX_PROGRESSIVE_IDLE_ITEMS_PER_SLICE = 1
internal const val CANONICAL_SOURCE_ATTRIBUTE = "data-tq-canonical-source"
internal const val EXACT_PREPARED_DOM_ATTRIBUTE = "data-tq-exact-prepared-dom"
internal const val RUNTIME_RENDER_FONT_ATTRIBUTE = "data-tq-runtime-render-font"
internal const val HOST_INLINE_SIZE_ATTRIBUTE = "data-tq-host-inline-size"
internal const val RELAYOUT_ERROR_ATTRIBUTE = "data-tiqian-relayout-error"
internal const val EXACT_PREPARED_FALLBACK_ATTRIBUTE = "data-tiqian-exact-layout-fallback"
internal const val DEFAULT_LINE_HEIGHT_MULTIPLIER = 1.75f
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

internal val NON_TEXT_INLINE_TAGS = setOf(
    "AREA",
    "AUDIO",
    "BUTTON",
    "CANVAS",
    "EMBED",
    "IFRAME",
    "IMG",
    "INPUT",
    "MATH",
    "OBJECT",
    "PICTURE",
    "SCRIPT",
    "SELECT",
    "STYLE",
    "SVG",
    "TEMPLATE",
    "TEXTAREA",
    "VIDEO",
)

internal val OPAQUE_INLINE_DISPLAYS = setOf("inline-block", "inline-flex", "inline-grid")
internal val OPAQUE_INLINE_LEVEL_DISPLAYS = OPAQUE_INLINE_DISPLAYS + "inline"
