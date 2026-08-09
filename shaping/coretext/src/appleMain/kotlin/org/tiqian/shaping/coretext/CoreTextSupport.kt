package org.tiqian.shaping.coretext

import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CValue
import kotlinx.cinterop.DoubleVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cValue
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import kotlin.native.concurrent.ThreadLocal
import platform.CoreFoundation.CFAttributedStringCreateMutable
import platform.CoreFoundation.CFAttributedStringReplaceString
import platform.CoreFoundation.CFAttributedStringSetAttribute
import platform.CoreFoundation.CFDictionaryCreate
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRange
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringGetLength
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFNumberDoubleType
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.CoreGraphics.CGAffineTransform
import platform.CoreText.CTFontCreateWithFontDescriptor
import platform.CoreText.CTFontCreateWithName
import platform.CoreText.CTFontDescriptorCreateWithAttributes
import platform.CoreText.CTFontRef
import platform.CoreText.CTLineCreateWithAttributedString
import platform.CoreText.CTLineRef
import platform.CoreText.CTRunGetAttributes
import platform.CoreText.CTRunRef
import platform.CoreText.kCTFontAttributeName
import platform.CoreText.kCTFontFamilyNameAttribute
import platform.CoreText.kCTLanguageAttributeName
import platform.CoreText.kCTFontTraitsAttribute
import platform.CoreText.kCTVerticalFormsAttributeName
import platform.CoreText.kCTFontWeightTrait

/**
 * Shared Core Text helpers for the Apple shaping adapter (`CoreTextShaper`,
 * `CoreTextFontMetricsResolver`) and the Core Text rendering frontend (`CoreTextLayoutRenderer`),
 * previously duplicated across all three.
 *
 * Fonts are **cached and process-owned**: [font] returns a *borrowed* [CTFontRef] the caller must
 * NOT `CFRelease` — the cache holds the single owning reference for the process lifetime. This
 * removes the per-glyph `CTFontDescriptor`/`CTFont` construction churn the renderer hit in the
 * 注音 hot path. State is `@ThreadLocal`, so each thread keeps its own cache (no shared-mutable
 * data race); call from a consistent thread (the engine + renderer both run on one thread).
 */
@OptIn(ExperimentalForeignApi::class)
@ThreadLocal
object CoreTextSupport {
    const val DEFAULT_CJK_FAMILY: String = "PingFang SC"
    const val DEFAULT_LATIN_FAMILY: String = "Helvetica Neue"

    /**
     * Synthetic-oblique shear for [italic] (ADR 0030 B 档). ~11.3° (`tan⁻¹ 0.2`). A horizontal
     * shear leaves the horizontal advance unchanged, so `measure == draw` holds; and it renders a
     * visible slant even for families with no italic face (e.g. PingFang), which matching an italic
     * face by trait would not.
     */
    private const val OBLIQUE_SHEAR: Double = 0.2

    private const val MAX_LINE_CACHE_ENTRIES: Int = 4096

    private data class FontKey(val family: String, val size: Double, val weight: Int, val italic: Boolean)

    private val fontCache = HashMap<FontKey, CTFontRef>()

    private data class LineKey(val text: String, val fontId: Long, val vertical: Boolean, val language: String?)

    // LinkedHashMap, not HashMap: eviction removes `keys.first()`, which must be the oldest-inserted
    // entry. Only LinkedHashMap guarantees that iteration order, so the documented oldest-insertion
    // eviction is deterministic instead of dropping an arbitrary (possibly hot) line.
    private val lineCache = LinkedHashMap<LineKey, CTLineRef>()

    /**
     * A borrowed, cached [CTFontRef] for [family] at [size] with OpenType [weight] (400 = regular;
     * Core Text picks the closest available weight) and optional synthetic-oblique [italic]. Do NOT
     * `CFRelease` the result — the cache owns it.
     */
    fun font(family: String, size: Double, weight: Int = 400, italic: Boolean = false): CTFontRef? {
        val key = FontKey(family, size, weight, italic)
        fontCache[key]?.let { return it }
        val created = if (weight in 351..449 && !italic) createPlain(family, size) else createStyled(family, size, weight, italic)
        if (created != null) fontCache[key] = created // cache keeps createFont's owning reference
        return created
    }

    fun cfRange(location: Long, length: Long): CValue<CFRange> =
        cValue { this.location = location; this.length = length }

    /** The font Core Text actually resolved for [run] (may be a fallback face); borrowed from the run. */
    fun runFontOf(run: CTRunRef): CTFontRef? {
        val attrs = CTRunGetAttributes(run)
        val value = CFDictionaryGetValue(attrs, kCTFontAttributeName) ?: return null
        return value.reinterpret()
    }

    /**
     * A borrowed, cached shaped [CTLineRef] for [text] in [font] (optionally the font's vertical
     * forms). Do NOT `CFRelease` — the cache owns it. Lets the renderer replay a cluster's shaping on
     * every repaint (window-resize redraw) instead of rebuilding a `CFAttributedString` + `CTLine`
     * each frame. Bounded (oldest-insertion eviction, releasing the evicted line).
     */
    fun line(text: String, font: CTFontRef, vertical: Boolean = false, language: String? = null): CTLineRef? {
        val key = LineKey(text, font.rawValue.toLong(), vertical, language)
        lineCache[key]?.let { return it }
        val created = createLine(text, font, vertical, language) ?: return null
        if (lineCache.size >= MAX_LINE_CACHE_ENTRIES) {
            lineCache.remove(lineCache.keys.first())?.let { CFRelease(it) }
        }
        lineCache[key] = created // cache owns CTLineCreateWithAttributedString's owning reference
        return created
    }

    private fun createLine(text: String, font: CTFontRef, vertical: Boolean, language: String?): CTLineRef? {
        val cfStr = CFStringCreateWithCString(null, text, kCFStringEncodingUTF8) ?: return null
        try {
            val attr = CFAttributedStringCreateMutable(null, 0) ?: return null
            try {
                CFAttributedStringReplaceString(attr, cfRange(0, 0), cfStr)
                val len = CFStringGetLength(cfStr)
                CFAttributedStringSetAttribute(attr, cfRange(0, len), kCTFontAttributeName, font)
                if (vertical) {
                    CFAttributedStringSetAttribute(attr, cfRange(0, len), kCTVerticalFormsAttributeName, kCFBooleanTrue)
                }
                // Language drives `locl` glyph selection (e.g. SC vs TC forms). Applied on the ONE
                // shared entry so measure (shaper) and draw (renderer) select the same glyphs — they
                // reuse the very same cached CTLine, keeping measure == draw (AGENTS.md #5).
                if (language != null) {
                    val lang = CFStringCreateWithCString(null, language, kCFStringEncodingUTF8)
                    if (lang != null) {
                        try {
                            CFAttributedStringSetAttribute(attr, cfRange(0, len), kCTLanguageAttributeName, lang)
                        } finally {
                            CFRelease(lang)
                        }
                    }
                }
                return CTLineCreateWithAttributedString(attr)
            } finally {
                CFRelease(attr)
            }
        } finally {
            CFRelease(cfStr)
        }
    }

    /**
     * Per-em (size-independent) metric ratios for a font family — the size-invariant part of
     * `RawFontMetrics`. Apple system fonts are fixed, so these are read once and reused at every
     * size (a font-size change no longer re-reads OS/2 tables per font). Multiply by the point size.
     */
    data class MetricRatios(
        val ascent: Double,
        val descent: Double,
        val leading: Double,
        val typoAscent: Double?,
        val typoDescent: Double?,
    )

    private data class RatioKey(val family: String, val weight: Int, val italic: Boolean)

    private val ratioCache = HashMap<RatioKey, MetricRatios?>()

    /**
     * Cached (process-lived) metric ratios for the exact face instance ([family], [weight],
     * [italic]); [compute] runs at most once per instance. Keyed by the full instance — not family
     * alone — so a bold/oblique rich-text span is measured against its own face's declared metrics,
     * keeping `measure == draw` (AGENTS.md #5) when a weight's ascent/descent differs from regular.
     */
    fun ratios(family: String, weight: Int, italic: Boolean, compute: () -> MetricRatios?): MetricRatios? {
        val key = RatioKey(family, weight, italic)
        if (ratioCache.containsKey(key)) return ratioCache[key]
        return compute().also { ratioCache[key] = it }
    }

    private fun createPlain(family: String, size: Double): CTFontRef? {
        val name = CFStringCreateWithCString(null, family, kCFStringEncodingUTF8) ?: return null
        try {
            return CTFontCreateWithName(name, size, null)
        } finally {
            CFRelease(name)
        }
    }

    /** OpenType weight (100-900) -> Core Text normalized weight trait (-1..1); Apple's system weight anchors. */
    private fun ctWeightTrait(otWeight: Int): Double = when {
        otWeight <= 150 -> -0.80
        otWeight <= 250 -> -0.60
        otWeight <= 350 -> -0.40
        otWeight < 500 -> 0.0
        otWeight < 600 -> 0.23
        otWeight < 700 -> 0.30
        otWeight < 800 -> 0.40
        otWeight < 900 -> 0.56
        else -> 0.62
    }

    /**
     * A [family] font at [size] carrying the OpenType [weight] via the weight trait (Core Text picks
     * the closest available weight), and a synthetic-oblique transform when [italic]. Falls back to
     * [createPlain] if the descriptor can't be built.
     */
    private fun createStyled(family: String, size: Double, weight: Int, italic: Boolean): CTFontRef? {
        val familyStr = CFStringCreateWithCString(null, family, kCFStringEncodingUTF8) ?: return null
        val matrix: CValue<CGAffineTransform>? =
            if (italic) cValue { a = 1.0; b = 0.0; c = OBLIQUE_SHEAR; d = 1.0; tx = 0.0; ty = 0.0 } else null
        var font: CTFontRef? = null
        memScoped {
            val wv = alloc<DoubleVar>()
            wv.value = ctWeightTrait(weight)
            val weightNum = CFNumberCreate(null, kCFNumberDoubleType, wv.ptr)
            if (weightNum != null) {
                val traitKeys = allocArray<COpaquePointerVar>(1)
                traitKeys[0] = kCTFontWeightTrait
                val traitVals = allocArray<COpaquePointerVar>(1)
                traitVals[0] = weightNum
                val traits = CFDictionaryCreate(null, traitKeys, traitVals, 1, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
                if (traits != null) {
                    val attrKeys = allocArray<COpaquePointerVar>(2)
                    attrKeys[0] = kCTFontFamilyNameAttribute
                    attrKeys[1] = kCTFontTraitsAttribute
                    val attrVals = allocArray<COpaquePointerVar>(2)
                    attrVals[0] = familyStr
                    attrVals[1] = traits
                    val attrs = CFDictionaryCreate(null, attrKeys, attrVals, 2, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
                    if (attrs != null) {
                        val desc = CTFontDescriptorCreateWithAttributes(attrs)
                        if (desc != null) {
                            font = CTFontCreateWithFontDescriptor(desc, size, matrix)
                            CFRelease(desc)
                        }
                        CFRelease(attrs)
                    }
                    CFRelease(traits)
                }
                CFRelease(weightNum)
            }
        }
        CFRelease(familyStr)
        return font ?: createPlain(family, size)
    }
}
