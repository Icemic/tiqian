package org.tiqian.shaping.nativefont

import android.annotation.TargetApi
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontStyle
import android.graphics.text.PositionedGlyphs
import android.graphics.text.TextRunShaper
import android.os.Build
import android.text.TextPaint
import org.tiqian.font.FontRole
import org.tiqian.shaping.ReplayableFontFaceRequest
import java.io.File
import java.util.Locale

/**
 * API 31+ system-default face oracle.
 *
 * Android does not expose Minikin's active family/fallback graph. Asking the
 * platform to shape the concrete selection text and reading the resulting
 * Font is the public source of truth. The native backend then replays that
 * exact file/index/axis instance with HarfBuzz and FreeType.
 */
@TargetApi(31)
internal object AndroidPlatformFontOracle {
    fun select(request: ReplayableFontFaceRequest): PlatformFontSelection {
        val selectionText = request.selectionText.ifEmpty { representativeText(request.role) }
        val paint = TextPaint().apply {
            isAntiAlias = true
            textSize = request.fontSize
            textLocale = Locale.forLanguageTag(request.locale)
            typeface = requestedTypeface(request)
        }
        val shaped = TextRunShaper.shapeTextRun(
            selectionText,
            0,
            selectionText.length,
            0,
            selectionText.length,
            0f,
            0f,
            false,
            paint,
        )
        check(shaped.glyphCount() > 0) {
            "PlatformFontSelectionEmpty: role=${request.role}; text=$selectionText"
        }
        val instances = (0 until shaped.glyphCount())
            .map { index -> shaped.platformInstance(index) }
            .distinctBy(PlatformGlyphInstance::instanceKey)
        check(instances.size == 1) {
            "PlatformSelectionSpansMultipleFaces: role=${request.role}; text=$selectionText; " +
                "faces=${instances.joinToString { it.font.sourceLabel() }}"
        }
        val instance = instances.single()
        check(!instance.fakeBold && !instance.fakeItalic) {
            "PlatformSelectionUsesSyntheticStyle: role=${request.role}; text=$selectionText; " +
                "fakeBold=${instance.fakeBold}; fakeItalic=${instance.fakeItalic}"
        }
        val font = instance.font
        return PlatformFontSelection(
            font = font,
            source = font.source(),
            collectionIndex = font.ttcIndex,
            variationAxes = instance.variationAxes,
            weight = instance.effectiveWeight,
            italic = instance.effectiveItalic,
            aliases = buildSet {
                addAll(request.preferredFamilies.filter(String::isNotBlank))
                font.file?.nameWithoutExtension?.takeIf(String::isNotBlank)?.let(::add)
                when (request.role) {
                    FontRole.LatinText, FontRole.CjkText, FontRole.CjkPunctuation -> {
                        add("sans")
                        add("sans-serif")
                    }
                    FontRole.Emoji -> add("emoji")
                    else -> add("system-default")
                }
            },
        )
    }

    fun bootstrapCatalogOrNull(): AndroidFontCatalog? = runCatching {
        val probes = listOf(
            FontRole.CjkText to "中",
            FontRole.CjkPunctuation to "。",
            FontRole.LatinText to "A",
        )
        val selections = probes.map { (role, text) ->
            role to select(
                ReplayableFontFaceRequest(
                    role = role,
                    preferredFamilies = emptyList(),
                    fontSize = 32f,
                    weight = 400,
                    italic = false,
                    locale = "zh-Hans",
                    selectionText = text,
                ),
            )
        }
        val specs = selections
            .groupBy { (_, selection) -> selection.instanceKey }
            .values
            .mapIndexed { index, group ->
                val selection = group.first().second
                AndroidFontFaceSpec(
                    source = selection.source,
                    collectionIndex = selection.collectionIndex,
                    familyKey = "platform-default-$index",
                    familyAliases = group.flatMapTo(linkedSetOf()) { it.second.aliases },
                    roles = group.mapTo(linkedSetOf()) { it.first },
                    weight = selection.weight,
                    italic = selection.italic,
                    variationAxes = selection.variationAxes,
                )
            }
        AndroidFontCatalog(
            faceSpecs = specs,
            sourceKind = "AndroidPlatformTextRunOracleApi31",
        )
    }.getOrNull()

    private fun requestedTypeface(request: ReplayableFontFaceRequest): Typeface {
        val family = request.preferredFamilies.firstOrNull(String::isNotBlank)
        return if (family == null) {
            Typeface.create(Typeface.DEFAULT, request.weight, request.italic)
        } else {
            Typeface.create(
                Typeface.create(family, Typeface.NORMAL),
                request.weight,
                request.italic,
            )
        }
    }

    private fun representativeText(role: FontRole): String = when (role) {
        FontRole.CjkText -> "中"
        FontRole.CjkPunctuation -> "。"
        FontRole.LatinText -> "A"
        FontRole.Symbol -> "∑"
        FontRole.Emoji -> "😀"
        FontRole.Unknown -> "�"
    }
}

@TargetApi(31)
internal data class PlatformFontSelection(
    val font: Font,
    val source: AndroidFontSource,
    val collectionIndex: Int,
    val variationAxes: Map<String, Float>,
    val weight: Int,
    val italic: Boolean,
    val aliases: Set<String>,
) {
    val instanceKey: String = buildString {
        append(font.sourceIdentifier)
        append(':')
        append(font.file?.absolutePath)
        append(':')
        append(collectionIndex)
        append(':')
        append(variationAxes.entries.joinToString(",") { (tag, value) -> "$tag=${value.toRawBits()}" })
    }
}

@TargetApi(31)
private fun PositionedGlyphs.platformInstance(index: Int): PlatformGlyphInstance {
    val font = getFont(index)
    val overrides = if (Build.VERSION.SDK_INT >= 35) styleOverrides(index) else PlatformStyleOverrides()
    return PlatformGlyphInstance(
        font = font,
        variationAxes = applyPlatformStyleOverrides(font.effectiveVariationAxes(), overrides),
        effectiveWeight = (overrides.weight ?: font.style.weight.toFloat()).toInt().coerceIn(1, 1000),
        effectiveItalic = overrides.italic?.let { it > 0f }
            ?: (font.style.slant == FontStyle.FONT_SLANT_ITALIC),
        fakeBold = overrides.fakeBold,
        fakeItalic = overrides.fakeItalic,
    )
}

@TargetApi(35)
private fun PositionedGlyphs.styleOverrides(index: Int): PlatformStyleOverrides {
    fun valueOrNull(value: Float): Float? = value.takeUnless { it == PositionedGlyphs.NO_OVERRIDE }
    return PlatformStyleOverrides(
        weight = valueOrNull(getWeightOverride(index)),
        italic = valueOrNull(getItalicOverride(index)),
        fakeBold = getFakeBold(index),
        fakeItalic = getFakeItalic(index),
    )
}

internal data class PlatformStyleOverrides(
    val weight: Float? = null,
    val italic: Float? = null,
    val fakeBold: Boolean = false,
    val fakeItalic: Boolean = false,
)

internal fun applyPlatformStyleOverrides(
    fontAxes: Map<String, Float>,
    overrides: PlatformStyleOverrides,
): Map<String, Float> = fontAxes.toMutableMap().apply {
    overrides.weight?.let { this["wght"] = it }
    overrides.italic?.let { this["ital"] = it }
}.toSortedMap()

@TargetApi(31)
private data class PlatformGlyphInstance(
    val font: Font,
    val variationAxes: Map<String, Float>,
    val effectiveWeight: Int,
    val effectiveItalic: Boolean,
    val fakeBold: Boolean,
    val fakeItalic: Boolean,
) {
    val instanceKey: String = buildString {
        append(font.sourceIdentifier)
        append(':')
        append(font.file?.absolutePath)
        append(':')
        append(font.ttcIndex)
        append(':')
        append(variationAxes.entries.joinToString(",") { (tag, value) -> "$tag=${value.toRawBits()}" })
        append(":fakeBold=")
        append(fakeBold)
        append(":fakeItalic=")
        append(fakeItalic)
    }
}

@TargetApi(31)
private fun Font.source(): AndroidFontSource {
    file?.takeIf(File::isFile)?.let { file ->
        return AndroidFontSource.file(file, sourceLabel())
    }
    val buffer = buffer.duplicate().apply { position(0) }
    val bytes = ByteArray(buffer.remaining()).also(buffer::get)
    return AndroidFontSource.bytes(bytes, sourceLabel())
}

@TargetApi(31)
private fun Font.effectiveVariationAxes(): Map<String, Float> =
    axes.orEmpty().associate { axis -> axis.tag to axis.styleValue }.toSortedMap()

@TargetApi(31)
private fun Font.sourceLabel(): String = buildString {
    append("PlatformTextRun:")
    append(file?.absolutePath ?: sourceIdentifier)
    append('#')
    append(ttcIndex)
    val axes = effectiveVariationAxes()
    if (axes.isNotEmpty()) {
        append(':')
        append(axes.entries.joinToString(",") { (tag, value) -> "$tag=$value" })
    }
}
