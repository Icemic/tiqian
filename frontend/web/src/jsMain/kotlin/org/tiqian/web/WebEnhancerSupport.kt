@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package org.tiqian.web

import kotlin.JsFun
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement

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
@JsFun("(root) => !!(globalThis.__TiqianPreparedDomRenderer && globalThis.__TiqianPreparedDomRenderer.releaseRoot && globalThis.__TiqianPreparedDomRenderer.releaseRoot(root) === true)")
internal external fun releasePreparedRootDomStyles(root: HTMLElement): Boolean
@JsFun(
    """(element) => {
      const rect = element.getBoundingClientRect();
      const viewportHeight = window.innerHeight || document.documentElement.clientHeight || 0;
      if (rect.bottom >= 0 && rect.top <= viewportHeight) return 0;
      return rect.bottom < 0 ? -rect.bottom : rect.top - viewportHeight;
    }""",
)
internal external fun paragraphViewportDistance(element: HTMLElement): Double
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

@JsFun(
    "(paragraph, root, selector) => { const owner = paragraph.closest(selector); return !owner || owner === root || !root.contains(owner); }",
)
internal external fun belongsToRootScope(paragraph: HTMLElement, root: HTMLElement, selector: String): Boolean
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

internal val WIDTH_DEPENDENT_CAPABILITY_ISSUES = setOf(
    "InlineCloneDecorationBreakUnsupported",
)

