package org.tiqian.shaping.android.nativefont

import android.annotation.TargetApi
import android.graphics.fonts.Font
import android.graphics.fonts.FontStyle
import android.graphics.fonts.SystemFonts
import android.os.Build
import org.tiqian.font.FontRole
import org.tiqian.shaping.FontBackendCapabilityIssue
import java.io.File
import kotlin.math.abs

@TargetApi(29)
internal object ApproximatePublicSystemFontsCatalog {
    fun createOrNull(
        fonts: List<Font> = SystemFonts.getAvailableFonts().toList(),
    ): AndroidFontCatalog? = runCatching {
        if (fonts.isEmpty()) return null
        val upright = fonts.filter { it.style.slant == FontStyle.FONT_SLANT_UPRIGHT }
        val cjkRegular = selectApproximateCjk(upright, targetWeight = 400)
        val cjkBold = selectApproximateCjk(upright, targetWeight = 700)
        val latinRegular = selectGenericSans(upright, targetWeight = 400)
        val latinBold = selectGenericSans(upright, targetWeight = 700)
        val latinItalic = selectGenericSans(
            fonts.filter { it.style.slant == FontStyle.FONT_SLANT_ITALIC },
            targetWeight = 400,
        )
        val cjkRoles = setOf(
            FontRole.CjkText,
            FontRole.CjkPunctuation,
            FontRole.Symbol,
            FontRole.Emoji,
            FontRole.Unknown,
        )
        val latinFallbackRoles = setOf(
            FontRole.LatinText,
            FontRole.CjkPunctuation,
            FontRole.Symbol,
            FontRole.Unknown,
        )
        val selected = listOfNotNull(
            cjkRegular?.let { Triple(it, cjkRoles, "approximate-system-cjk") },
            cjkBold?.let { Triple(it, cjkRoles, "approximate-system-cjk") },
            latinRegular?.let { Triple(it, latinFallbackRoles, "approximate-system-latin") },
            latinBold?.let { Triple(it, latinFallbackRoles, "approximate-system-latin") },
            latinItalic?.let { Triple(it, latinFallbackRoles, "approximate-system-latin") },
        )
        val specs = selected
            .groupBy { (font, _, familyKey) -> font.instanceKey() to familyKey }
            .map { (_, selections) ->
                val (font, _, familyKey) = selections.first()
                val roles = selections.flatMapTo(linkedSetOf()) { it.second }
                val axes = font.variationAxes()
                AndroidFontFaceSpec(
                    source = font.file?.takeIf(File::isFile)?.let { file ->
                        AndroidFontSource.file(file, systemFontLabel(font, axes))
                    } ?: AndroidFontSource.directBuffer(
                        font.buffer.duplicate().apply { position(0) },
                        systemFontLabel(font, axes),
                    ),
                    collectionIndex = font.ttcIndex,
                    familyKey = familyKey,
                    familyAliases = familyAliases(font),
                    roles = roles,
                    weight = font.style.weight.coerceIn(1, 1000),
                    italic = font.style.slant == FontStyle.FONT_SLANT_ITALIC,
                    variationAxes = axes,
                )
            }
        if (specs.none { FontRole.CjkText in it.roles } || specs.none { FontRole.LatinText in it.roles }) return null
        AndroidFontCatalog(
            faceSpecs = specs,
            sourceKind = "ApproximateAndroidPublicSystemFontsApi29",
            declaredIssues = listOf(
                FontBackendCapabilityIssue(
                    code = "ApproximateSystemFontSelection",
                    detail = "SystemFonts is unordered and does not expose the active family/fallback graph; selected faces may differ from the OEM or user default",
                ),
            ),
        )
    }.getOrNull()

    private fun cjkScore(font: Font, targetWeight: Int): Int {
        val name = font.file?.name.orEmpty().lowercase()
        val languages = font.localeList.toLanguageTags().lowercase()
        val languageScore = when {
            "zh-hans" in languages -> 0
            "zh" in languages -> 100
            "cjk" in name || "sc" in name -> 200
            else -> 10_000
        }
        val sansScore = if ("sans" in name) 0 else 500
        return languageScore + sansScore + abs(font.style.weight - targetWeight)
    }

    private fun selectApproximateCjk(fonts: List<Font>, targetWeight: Int): Font? =
        fonts.minWithOrNull(
            compareBy<Font>(
                { font -> cjkScore(font, targetWeight) },
                { font -> font.file?.absolutePath.orEmpty() },
                Font::getTtcIndex,
                { font ->
                    font.variationAxes().entries.joinToString(",") { (tag, value) ->
                        "$tag=${value.toRawBits()}"
                    }
                },
                { font -> font.style.weight },
            ),
        )

    /**
     * `SystemFonts.getAvailableFonts()` is an unordered set and loses named-family membership.
     * Roboto's normal and condensed aliases can therefore expose the same file and weight with
     * only `wdth` distinguishing them. Resolve generic sans deterministically, preferring the
     * registered normal width before weight proximity. This is deliberately diagnostic-only:
     * it preserves the enumerated instance and never manufactures a new 400/700 axis value.
     */
    private fun selectGenericSans(fonts: List<Font>, targetWeight: Int): Font? =
        fonts.minWithOrNull(
            compareBy<Font>(
                ::latinFamilyRank,
                ::genericSansWidthDistance,
                { font -> abs(font.style.weight - targetWeight) },
                { font -> font.file?.absolutePath.orEmpty() },
                Font::getTtcIndex,
                { font ->
                    font.variationAxes().entries.joinToString(",") { (tag, value) ->
                        "$tag=${value.toRawBits()}"
                    }
                },
            ),
        )

    private fun latinFamilyRank(font: Font): Int {
        val name = font.file?.name.orEmpty().lowercase()
        return when {
            "roboto" in name && "mono" !in name -> 0
            "notosans" in name && "cjk" !in name -> 1
            else -> 2
        }
    }

    private fun genericSansWidthDistance(font: Font): Float =
        abs((font.variationAxes()["wdth"] ?: 100f) - 100f)

    private fun familyAliases(font: Font): Set<String> {
        val name = font.file?.nameWithoutExtension.orEmpty().lowercase()
        return buildSet {
            add(name.ifEmpty { "system-${font.stableSourceId()}" })
            when {
                "mono" in name -> addAll(listOf("mono", "monospace"))
                "serif" in name && "sans" !in name -> add("serif")
                else -> addAll(listOf("sans", "sans-serif"))
            }
        }
    }

    /**
     * `Font.getSourceIdentifier` only exists on API 31+, but the public `SystemFonts`
     * enumeration this catalog is built from starts at API 29. Below 31 the enumerated
     * instances are the identity the catalog actually needs: the id is consumed once,
     * inside the single [createOrNull] pass, to separate faces that share a file path,
     * collection index and axis set.
     */
    private fun Font.stableSourceId(): Int =
        if (Build.VERSION.SDK_INT >= 31) sourceIdentifier else System.identityHashCode(this)

    private fun Font.variationAxes(): Map<String, Float> =
        axes.orEmpty().associate { axis -> axis.tag to axis.styleValue }.toSortedMap()

    private fun Font.instanceKey(): FontInstanceKey = FontInstanceKey(
        sourceIdentifier = stableSourceId(),
        filePath = file?.absolutePath,
        collectionIndex = ttcIndex,
        variationAxes = variationAxes().entries.map { it.key to it.value.toRawBits() },
        weight = style.weight,
        slant = style.slant,
    )

    private fun systemFontLabel(font: Font, axes: Map<String, Float>): String = buildString {
        append("SystemFonts:")
        append(font.file?.absolutePath ?: font.stableSourceId())
        if (axes.isNotEmpty()) {
            append('#')
            append(axes.entries.joinToString(",") { (tag, value) -> "$tag=$value" })
        }
    }

    private data class FontInstanceKey(
        val sourceIdentifier: Int,
        val filePath: String?,
        val collectionIndex: Int,
        val variationAxes: List<Pair<String, Int>>,
        val weight: Int,
        val slant: Int,
    )

}
