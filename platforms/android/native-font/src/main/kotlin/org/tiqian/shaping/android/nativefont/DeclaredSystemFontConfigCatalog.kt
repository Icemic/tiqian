package org.tiqian.shaping.android.nativefont

import org.tiqian.font.FontRole
import org.tiqian.shaping.FontBackendCapabilityIssue
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * API 23-30 best public evidence: the ordered declarations in fonts.xml.
 * This intentionally does not claim to observe Minikin's merged runtime graph
 * or an OEM theme replacement; API 31+ uses [AndroidPlatformFontOracle].
 */
internal object DeclaredSystemFontConfigCatalog {
    private val candidateConfigs = listOf(
        File("/system/etc/fonts.xml"),
        File("/vendor/etc/fonts.xml"),
        File("/product/etc/fonts.xml"),
        File("/system_ext/etc/fonts.xml"),
    )

    fun createOrNull(
        configFiles: List<File> = candidateConfigs,
        fontDirectory: File = File("/system/fonts"),
    ): AndroidFontCatalog? = runCatching {
        create(configFiles, fontDirectory)
    }.getOrNull()

    /** Throwing counterpart used by tests so malformed declarations stay diagnosable. */
    internal fun create(
        configFiles: List<File>,
        fontDirectory: File,
    ): AndroidFontCatalog? {
        val readable = configFiles.filter { it.isFile && it.canRead() }
        if (readable.isEmpty()) return null
        // The platform's overlay merge is private. A single readable root file
        // is a coherent ordered declaration; multiple files require a future
        // explicit merge model and must not be concatenated as if order survived.
        val config = LegacyFontConfigParser.parse(readable.first())
        val namedSans = config.families.firstOrNull { family ->
            family.names.any { it.equals("sans-serif", ignoreCase = true) }
        } ?: return null
        val cjkFamilies = config.families.filter { family ->
            family.names.isEmpty() && family.languages.any(::isSimplifiedHanCompatibleLanguage)
        }
        if (cjkFamilies.isEmpty()) return null
        val neutralFallbacks = config.families.filter { family ->
            family.names.isEmpty() && family.languages.isEmpty()
        }
        val emojiFamilies = config.families.filter { family ->
            family.names.isEmpty() && family.languages.any { it.equals("und-Zsye", ignoreCase = true) }
        }

        val familySpecs = linkedMapOf<String, List<AndroidFontFaceSpec>>()
        fun addFamily(
            key: String,
            family: LegacyFontConfigFamily,
            roles: Set<FontRole>,
            aliases: Set<String>,
        ) {
            val specs = family.fonts.mapNotNull { font ->
                if (font.fallbackFor != null && !font.fallbackFor.equals("sans-serif", ignoreCase = true)) {
                    return@mapNotNull null
                }
                val file = File(fontDirectory, font.fileName)
                if (!file.isFile || !file.canRead()) return@mapNotNull null
                AndroidFontFaceSpec(
                    source = AndroidFontSource.file(file, "DeclaredFontConfig:${file.absolutePath}"),
                    collectionIndex = font.collectionIndex,
                    familyKey = key,
                    familyAliases = aliases,
                    roles = roles,
                    weight = font.weight,
                    italic = font.italic,
                    variationAxes = font.axes,
                )
            }
            if (specs.isNotEmpty()) familySpecs[key] = specs
        }

        // The named sans face is the final punctuation fallback after the
        // language-specific Han families, so its declared roles must match the
        // CjkPunctuation chain below.
        val latinRoles = setOf(
            FontRole.CjkPunctuation,
            FontRole.LatinText,
            FontRole.Symbol,
            FontRole.Unknown,
        )
        addFamily(
            key = "declared-sans-serif",
            family = namedSans,
            roles = latinRoles,
            aliases = namedSans.names.toSet() + setOf("sans", "sans-serif"),
        )
        cjkFamilies.forEach { family ->
            addFamily(
                key = "declared-cjk-${family.order}",
                family = family,
                roles = setOf(FontRole.CjkText, FontRole.CjkPunctuation, FontRole.Symbol, FontRole.Unknown),
                aliases = setOf("sans", "sans-serif", "zh-Hans"),
            )
        }
        neutralFallbacks.forEach { family ->
            addFamily(
                key = "declared-neutral-${family.order}",
                family = family,
                roles = buildSet {
                    add(FontRole.CjkText)
                    add(FontRole.CjkPunctuation)
                    add(FontRole.Symbol)
                    add(FontRole.Unknown)
                    // Neutral families participate in the emoji chain only
                    // when that chain exists; keep catalog roles and chains
                    // structurally identical.
                    if (emojiFamilies.isNotEmpty()) add(FontRole.Emoji)
                },
                aliases = setOf("system-fallback-${family.order}"),
            )
        }
        emojiFamilies.forEach { family ->
            addFamily(
                key = "declared-emoji-${family.order}",
                family = family,
                roles = setOf(FontRole.Emoji, FontRole.Symbol, FontRole.Unknown),
                aliases = setOf("emoji", "system-emoji"),
            )
        }

        val latin = listOf("declared-sans-serif").filter(familySpecs::containsKey)
        val cjk = (cjkFamilies.map { "declared-cjk-${it.order}" } + neutralFallbacks.map { "declared-neutral-${it.order}" })
            .filter(familySpecs::containsKey)
        val neutral = neutralFallbacks.map { "declared-neutral-${it.order}" }.filter(familySpecs::containsKey)
        val emoji = emojiFamilies.map { "declared-emoji-${it.order}" }.filter(familySpecs::containsKey)
        if (latin.isEmpty() || cjk.isEmpty()) return null

        return AndroidFontCatalog(
            faceSpecs = familySpecs.values.flatten(),
            fallbackChains = buildMap {
                put(FontRole.CjkText, cjk)
                put(FontRole.CjkPunctuation, (cjk + latin).distinct())
                put(FontRole.LatinText, latin)
                put(FontRole.Symbol, (latin + cjk + neutral + emoji).distinct())
                if (emoji.isNotEmpty()) put(FontRole.Emoji, (emoji + neutral).distinct())
                put(FontRole.Unknown, (latin + cjk + neutral + emoji).distinct())
            },
            sourceKind = "DeclaredAndroidFontConfigApi23To30",
            declaredIssues = buildList {
                add(
                    FontBackendCapabilityIssue(
                        code = "RuntimeFontSelectionUnobservableBelowApi31",
                        detail = "fonts.xml declaration order is available, but Minikin's effective runtime/theme selection cannot be read back below API 31",
                    ),
                )
                if (readable.size > 1) {
                    add(
                        FontBackendCapabilityIssue(
                            code = "UnmergedFontConfigOverlays",
                            detail = "Multiple readable font config roots exist; using ${readable.first().absolutePath} without pretending to reproduce the private overlay merge",
                        ),
                    )
                }
            },
        )
    }

    /**
     * `SimplifiedHanCompatibleFontConfigLanguage`: Android/OEM configs do not
     * consistently spell a Simplified-Chinese fallback as `zh-Hans`. Xiaomi,
     * for example, declares the MiSans rare-character supplement MiSansL3 as
     * plain `zh`. A zh-Hans request must retain that family in declaration
     * order; explicit Traditional tags remain outside this chain.
     */
    private fun isSimplifiedHanCompatibleLanguage(value: String): Boolean {
        val language = value.lowercase()
        return language == "zh" ||
            language == "zh-cn" ||
            language.startsWith("zh-cn-") ||
            language == "zh-sg" ||
            language.startsWith("zh-sg-") ||
            language == "zh-hans" ||
            language.startsWith("zh-hans-") ||
            language == "und-hani"
    }
}

internal data class LegacyFontConfig(
    val families: List<LegacyFontConfigFamily>,
)

internal data class LegacyFontConfigFamily(
    val order: Int,
    val names: List<String>,
    val languages: List<String>,
    val fonts: List<LegacyFontConfigFont>,
)

internal data class LegacyFontConfigFont(
    val fileName: String,
    val weight: Int,
    val italic: Boolean,
    val collectionIndex: Int,
    val fallbackFor: String?,
    val axes: Map<String, Float>,
)

internal object LegacyFontConfigParser {
    fun parse(file: File): LegacyFontConfig {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            runCatching { isXIncludeAware = false }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val root = file.inputStream().use { stream ->
            factory.newDocumentBuilder().parse(stream).documentElement
        } ?: error("font config has no document element")
        return LegacyFontConfig(
            families = root.descendantsNamed("family").mapIndexed { order, family ->
                val names = buildList {
                    family.attributeOrNull("name")?.let(::add)
                    addAll(
                        family.directChildrenNamed("nameset")
                            .flatMap { it.directChildrenNamed("name") }
                            .mapNotNull { it.textContent?.trim()?.takeIf(String::isNotEmpty) },
                    )
                }.distinct()
                val languages = family.attributeOrNull("lang")
                    ?.split(Regex("[\\s,]+"))
                    ?.filter(String::isNotEmpty)
                    .orEmpty()
                val fonts = (
                    family.directChildrenNamed("font") +
                        family.directChildrenNamed("fileset").flatMap { it.directChildrenNamed("file") }
                    ).map(::parseFont)
                LegacyFontConfigFamily(order, names, languages, fonts)
            },
        )
    }

    private fun parseFont(element: Element): LegacyFontConfigFont {
        val fileName = element.childNodes.asSequence()
            .filter { it.nodeType == Node.TEXT_NODE || it.nodeType == Node.CDATA_SECTION_NODE }
            .joinToString("") { it.nodeValue.orEmpty() }
            .trim()
        val axes = element.directChildrenNamed("axis").mapNotNull { axis ->
            val tag = axis.attributeOrNull("tag") ?: return@mapNotNull null
            val value = axis.attributeOrNull("stylevalue")?.toFloatOrNull() ?: return@mapNotNull null
            tag to value
        }.toMap().toSortedMap()
        return LegacyFontConfigFont(
            fileName = fileName,
            weight = element.attributeOrNull("weight")?.toIntOrNull()?.coerceIn(1, 1000) ?: 400,
            italic = element.attributeOrNull("style").equals("italic", ignoreCase = true),
            collectionIndex = (element.attributeOrNull("index") ?: element.attributeOrNull("ttcIndex"))
                ?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
            fallbackFor = element.attributeOrNull("fallbackFor"),
            axes = axes,
        )
    }

    private fun Element.attributeOrNull(name: String): String? =
        getAttribute(name).trim().takeIf(String::isNotEmpty)

    private fun Element.directChildrenNamed(name: String): List<Element> =
        childNodes.asSequence().filterIsInstance<Element>().filter { it.tagName == name }.toList()

    private fun Element.descendantsNamed(name: String): List<Element> =
        getElementsByTagName(name).asSequence().filterIsInstance<Element>().toList()

    private fun org.w3c.dom.NodeList.asSequence(): Sequence<Node> = sequence {
        for (index in 0 until length) yield(item(index))
    }
}
