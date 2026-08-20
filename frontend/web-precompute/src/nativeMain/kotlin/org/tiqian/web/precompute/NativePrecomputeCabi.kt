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
import org.tiqian.shaping.NativeFontBackendFontMetricsResolver
import org.tiqian.shaping.NativeFontBackendTextShaper
import org.tiqian.shaping.tiqianInstallFontBackend as installFontBackend
import org.tiqian.shaping.backend.tiqian_font_backend_vtable_t

/**
 * The static library only exports `@CName` symbols defined in this module, so
 * the font backend install entry (registry lives in shaping:api) is re-exported
 * here. Signature and result codes: tiqian_font_backend.h.
 */
@CName("tiqian_install_font_backend")
fun tiqianInstallFontBackendCabi(vtable: CPointer<tiqian_font_backend_vtable_t>?): Int =
    installFontBackend(vtable)

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
 * The font session lives in the host binding (Rust in ADR 0050); the engine
 * reaches it through the vtable installed via `tiqian_install_font_backend`.
 * Requests before installation report the named error `FontBackendNotInstalled`.
 */
internal actual fun buildPrecomputeBackends(fontSessionId: String): PrecomputeBackends =
    PrecomputeBackends(
        textShaper = NativeFontBackendTextShaper(fontSessionId),
        fontMetricsResolver = NativeFontBackendFontMetricsResolver(fontSessionId),
    )
