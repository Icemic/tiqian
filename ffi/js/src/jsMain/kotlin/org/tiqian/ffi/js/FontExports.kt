@file:OptIn(kotlin.js.ExperimentalJsExport::class)

package org.tiqian.ffi.js

import kotlin.js.JsExport
import org.tiqian.core.TextRange
import org.tiqian.font.PreferCjkForAmbiguousPunctuationResolver
import org.tiqian.font.StubFontMetricsResolver

/**
 * Standalone font-policy capability exports for `@tiqian/ffi`.
 *
 * Each export wraps exactly one engine capability:
 * - `fontMetricsResolve` wraps `FontMetricsResolver.resolve()` (typed
 *   request/response) using the data-free [StubFontMetricsResolver]
 *   (`engine/src/commonMain/kotlin/org/tiqian/font/FontMetrics.kt`).
 * - `fontFallbackResolve` wraps `FallbackResolver.resolve()` using
 *   [PreferCjkForAmbiguousPunctuationResolver]
 *   (`engine/src/commonMain/kotlin/org/tiqian/font/FontPolicy.kt`).
 *
 * The wire is JSON-through-JavaScript: requests enter as JSON strings
 * (`WireJson.kt` decodes them), responses leave as JSON strings. Source text
 * is never rewritten; decisions carry the engine `reason` verbatim.
 */

/**
 * Resolves the engine's data-free font metrics for a JSON `FontMetricsRequest`
 * and returns the `RawFontMetrics` as JSON
 * (`ascent`/`descent`/`leading`/`source`, plus `typoAscent`/`typoDescent`
 * when the face carries a declared typographic box).
 */
@JsExport
fun fontMetricsResolve(requestJson: String): String {
    val request = parseFontMetricsRequestJson(requestJson)
    val metrics = StubFontMetricsResolver().resolve(request)
    return buildString {
        append("{\"ascent\":")
        appendJsonNumber(metrics.ascent)
        append(",\"descent\":")
        appendJsonNumber(metrics.descent)
        append(",\"leading\":")
        appendJsonNumber(metrics.leading)
        append(",\"source\":")
        appendJsonString(metrics.source.name)
        metrics.typoAscent?.let {
            append(",\"typoAscent\":")
            appendJsonNumber(it)
        }
        metrics.typoDescent?.let {
            append(",\"typoDescent\":")
            appendJsonNumber(it)
        }
        append('}')
    }
}

/**
 * Resolves the engine's `LatinVsCjkFaceSelection` fallback decision for the
 * source [text] substring in [start]..<[end] given a JSON `FontRequest`
 * (`preferredFamilies`/`locale`/`role`). Returns the `FontDecision` as JSON:
 * `range`, `candidate` (`key`/`family`/`role`), resolved `role` and `reason`.
 */
@JsExport
fun fontFallbackResolve(text: String, start: Int, end: Int, requestJson: String): String {
    val request = parseFontRequestJson(requestJson)
    val decision = PreferCjkForAmbiguousPunctuationResolver().resolve(
        text = text,
        range = TextRange(start, end),
        request = request,
    )
    return buildString {
        append("{\"range\":{\"start\":").append(decision.range.start)
        append(",\"end\":").append(decision.range.end).append('}')
        append(",\"candidate\":{\"key\":")
        appendJsonString(decision.candidate.key)
        append(",\"family\":")
        appendJsonString(decision.candidate.family)
        append(",\"role\":")
        appendJsonString(decision.candidate.role.name)
        append("},\"role\":")
        appendJsonString(decision.role.name)
        append(",\"reason\":")
        appendJsonString(decision.reason)
        append('}')
    }
}