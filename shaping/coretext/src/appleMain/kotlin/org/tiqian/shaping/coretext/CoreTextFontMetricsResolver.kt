package org.tiqian.shaping.coretext

import kotlinx.cinterop.get
import org.tiqian.font.FontMetricSource
import org.tiqian.font.FontMetricsRequest
import org.tiqian.font.FontMetricsResolver
import org.tiqian.font.FontRole
import org.tiqian.font.RawFontMetrics
import org.tiqian.font.usesLatinFace
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFRelease
import platform.CoreText.CTFontCopyTable
import platform.CoreText.CTFontGetAscent
import platform.CoreText.CTFontGetDescent
import platform.CoreText.CTFontGetLeading
import platform.CoreText.CTFontGetUnitsPerEm
import platform.CoreText.CTFontRef
import platform.CoreText.kCTFontTableOptionNoOptions

/** Reference size the per-family metric ratios are read at, then normalized (see [readRatios]). */
private const val METRICS_REFERENCE_SIZE: Double = 128.0

/**
 * Core Text metrics resolver (Apple / Kotlin-Native), mirroring `SkiaFontMetricsResolver`
 * (ADR 0002 amendment):
 *
 * - hhea-style ascent/descent/leading straight from the CTFont (kept for the no-OS/2
 *   fallback and overflow clamping);
 * - the CJK 字身框 → [RawFontMetrics.typoAscent] / [typoDescent], read from the font's
 *   `OS/2` sTypoAscender/sTypoDescender (the clean ideographic em). The
 *   `ScriptAwareFontMetricsNormalizer` lays the CJK line box on this.
 *
 * Fonts come from [CoreTextSupport.font] (shared process cache), then [FontMetricsRequest.faceSelectionText]
 * resolves the concrete Core Text fallback run. `measure == draw` (AGENTS.md #5) therefore holds
 * even when a requested family is absent or lacks the requested CJK glyphs.
 */
class CoreTextFontMetricsResolver(
    private val cjkFamily: String = CoreTextSupport.DEFAULT_CJK_FAMILY,
    private val latinFamily: String = CoreTextSupport.DEFAULT_LATIN_FAMILY,
) : FontMetricsResolver {

    override fun resolve(request: FontMetricsRequest): RawFontMetrics {
        val family = request.fontFamilies.firstOrNull()
            ?: if (request.role.usesLatinFace()) latinFamily else cjkFamily
        // `CoreTextFallbackFaceMetrics`: resolve the face that owns this run before reading metrics.
        // This is observable on iOS when a requested CJK family is unavailable: Core Text draws the
        // fallback face, so aligning with the unavailable base face's descent would lift the run.
        val baseFont = CoreTextSupport.font(
            family,
            METRICS_REFERENCE_SIZE,
            request.fontWeight,
            request.italic,
        ) ?: return fallback(request)
        val resolvedFont = CoreTextSupport.resolvedRunFont(
            text = request.faceSelectionText,
            font = baseFont,
            language = request.locale,
        )
        val r = CoreTextSupport.ratios(resolvedFont) {
            readRatios(resolvedFont)
        } ?: return fallback(request)
        val size = request.fontSize
        return RawFontMetrics(
            ascent = (r.ascent * size).toFloat(),
            descent = (r.descent * size).toFloat(),
            leading = (r.leading * size).toFloat(),
            source = FontMetricSource.RawTables,
            typoAscent = r.typoAscent?.let { (it * size).toFloat() },
            typoDescent = r.typoDescent?.let { (it * size).toFloat() },
        )
    }

    /**
     * Read the family's size-independent metric ratios once, at a reference size then normalized to
     * per-em. Core Text scales hhea/typo metrics linearly with point size, so `ratio * size` at draw
     * time equals what a per-size read would return — `measure == draw` (AGENTS.md #5) still holds.
     */
    private fun readRatios(font: CTFontRef): CoreTextSupport.MetricRatios? {
        val upm = CTFontGetUnitsPerEm(font).toDouble().let { if (it > 0.0) it else 1000.0 }
        val typo = readOs2Typo(font)
        return CoreTextSupport.MetricRatios(
            ascent = CTFontGetAscent(font) / METRICS_REFERENCE_SIZE,
            descent = CTFontGetDescent(font) / METRICS_REFERENCE_SIZE,
            leading = CTFontGetLeading(font) / METRICS_REFERENCE_SIZE,
            typoAscent = typo?.first?.let { it / upm },
            // sTypoDescender is a negative FUnit magnitude below the baseline; flip to +.
            typoDescent = typo?.second?.let { -it / upm },
        )
    }

    /** OS/2 sTypoAscender (offset 68) / sTypoDescender (offset 70): big-endian s16 FUnits. */
    private fun readOs2Typo(font: CTFontRef): Pair<Int, Int>? {
        val os2Tag: UInt = 0x4F532F32u // 'OS/2'
        val data = CTFontCopyTable(font, os2Tag, kCTFontTableOptionNoOptions) ?: return null
        try {
            if (CFDataGetLength(data) < 72) return null
            val ptr = CFDataGetBytePtr(data) ?: return null
            val asc = s16(ptr[68].toInt(), ptr[69].toInt())
            val desc = s16(ptr[70].toInt(), ptr[71].toInt())
            return asc to desc
        } finally {
            CFRelease(data)
        }
    }

    private fun s16(hi: Int, lo: Int): Int {
        val u = ((hi and 0xFF) shl 8) or (lo and 0xFF)
        return u.toShort().toInt()
    }

    /** Mirrors `StubFontMetricsResolver` so a missing system font still lays out. */
    private fun fallback(request: FontMetricsRequest): RawFontMetrics = when (request.role) {
        FontRole.CjkText, FontRole.CjkPunctuation -> RawFontMetrics(
            ascent = request.fontSize * 1.16f,
            descent = request.fontSize * 0.288f,
            typoAscent = request.fontSize * 0.88f,
            typoDescent = request.fontSize * 0.12f,
        )
        FontRole.LatinText -> RawFontMetrics(request.fontSize * 0.8f, request.fontSize * 0.2f)
        else -> RawFontMetrics(request.fontSize * 0.9f, request.fontSize * 0.25f)
    }
}
