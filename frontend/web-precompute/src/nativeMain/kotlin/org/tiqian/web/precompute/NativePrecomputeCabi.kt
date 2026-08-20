@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package org.tiqian.web.precompute

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.pointed
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import platform.posix.free
import platform.posix.malloc
import org.tiqian.font.FontMetricsRequest
import org.tiqian.font.FontMetricsResolver
import org.tiqian.font.RawFontMetrics
import org.tiqian.shaping.ShapingInput
import org.tiqian.shaping.ShapingResult
import org.tiqian.shaping.TextShaper

/**
 * Flat C ABI over the shared wire layer (`PrecomputeWire.kt`). ADR 0050.
 *
 * `tiqian_precompute_paragraph` returns a NUL-terminated UTF-8 JSON plan owned by
 * `nativeHeap`; the caller releases it with `tiqian_precompute_release_string`.
 * Failures surface through `error_out` as a named issue string with the same names
 * the npm tests assert. Kotlin exceptions never cross the C boundary.
 */
@CName("tiqian_precompute_paragraph")
fun tiqianPrecomputeParagraph(
    fontSessionId: String,
    text: String,
    maxWidthPx: Double,
    fontFamilies: String,
    fontSizePx: Double,
    lineHeightPx: Double,
    locale: String,
    fontWeight: Int,
    italic: Boolean,
    firstLineIndentIc: Double,
    lineLengthGridEnabled: Boolean,
    sourceBoundaries: String,
    textSpans: String,
    inlineBoxes: String,
    lineBreakSpans: String,
    errorOut: CPointer<CPointerVar<ByteVar>>?,
): CPointer<ByteVar>? = try {
    val plan = precomputeParagraphPlan(
        fontSessionId = fontSessionId,
        text = text,
        maxWidthPx = maxWidthPx,
        fontFamilies = fontFamilies,
        fontSizePx = fontSizePx,
        lineHeightPx = lineHeightPx,
        locale = locale,
        fontWeight = fontWeight,
        italic = italic,
        firstLineIndentIc = firstLineIndentIc,
        lineLengthGridEnabled = lineLengthGridEnabled,
        sourceBoundaries = sourceBoundaries,
        textSpans = textSpans,
        inlineBoxes = inlineBoxes,
        lineBreakSpans = lineBreakSpans,
    )
    errorOut?.pointed?.value = null
    plan.copyToNativeCString()
} catch (error: Throwable) {
    val name = error.message?.takeIf(String::isNotBlank)
        ?: error::class.simpleName
        ?: "UnknownPrecomputeError"
    errorOut?.pointed?.value = name.copyToNativeCString()
    null
}

@CName("tiqian_precompute_release_string")
fun tiqianPrecomputeReleaseString(value: CPointer<ByteVar>?) {
    if (value != null) free(value)
}

private fun String.copyToNativeCString(): CPointer<ByteVar> {
    val bytes = encodeToByteArray()
    val buffer = malloc((bytes.size + 1).toULong())!!.reinterpret<ByteVar>()
    for (index in bytes.indices) buffer[index] = bytes[index]
    buffer[bytes.size] = 0
    return buffer
}

/**
 * `UninstalledNativeFontBackend`: ADR 0050 font backend vtable consumer lands with
 * the Rust font session (Slice B). Until `tiqian_install_font_backend` exists,
 * every shaping and metrics request reports the named error `FontBackendNotInstalled`
 * instead of guessing glyph geometry.
 */
internal actual fun buildPrecomputeBackends(fontSessionId: String): PrecomputeBackends =
    PrecomputeBackends(
        textShaper = UninstalledNativeFontBackendShaper,
        fontMetricsResolver = UninstalledNativeFontBackendMetricsResolver,
    )

private object UninstalledNativeFontBackendShaper : TextShaper {
    override fun shape(input: ShapingInput): ShapingResult =
        error("FontBackendNotInstalled")
}

private object UninstalledNativeFontBackendMetricsResolver : FontMetricsResolver {
    override fun resolve(request: FontMetricsRequest): RawFontMetrics =
        error("FontBackendNotInstalled")
}
