package org.tiqian.diagnostics

import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

internal data class FontConfigIndex(
    val rootElement: String,
    val version: String?,
    val families: List<FontConfigFamily>,
    val aliases: List<FontConfigAlias>,
) {
    val declaredNames: List<String>
        get() = (families.flatMap(FontConfigFamily::names) + aliases.map(FontConfigAlias::name))
            .distinct()
            .sorted()

    fun toJsonValue(): Map<String, Any?> = linkedMapOf(
        "rootElement" to rootElement,
        "version" to version,
        "families" to families.map(FontConfigFamily::toJsonValue),
        "aliases" to aliases.map(FontConfigAlias::toJsonValue),
    )
}

internal data class FontConfigFamily(
    val order: Int,
    val names: List<String>,
    val languages: List<String>,
    val variant: String?,
    val fallbackFor: String?,
    val fonts: List<FontConfigFont>,
) {
    fun toJsonValue(): Map<String, Any?> = linkedMapOf(
        "order" to order,
        "names" to names,
        "languages" to languages,
        "variant" to variant,
        "fallbackFor" to fallbackFor,
        "fonts" to fonts.map(FontConfigFont::toJsonValue),
    )
}

internal data class FontConfigFont(
    val fileName: String,
    val weight: Int?,
    val style: String?,
    val ttcIndex: Int?,
    val fallbackFor: String?,
    val postScriptName: String?,
    val axes: Map<String, Float>,
) {
    fun toJsonValue(): Map<String, Any?> = linkedMapOf(
        "fileName" to fileName,
        "weight" to weight,
        "style" to style,
        "ttcIndex" to ttcIndex,
        "fallbackFor" to fallbackFor,
        "postScriptName" to postScriptName,
        "axes" to axes,
    )
}

internal data class FontConfigAlias(
    val order: Int,
    val name: String,
    val to: String,
    val weight: Int?,
) {
    fun toJsonValue(): Map<String, Any?> = linkedMapOf(
        "order" to order,
        "name" to name,
        "to" to to,
        "weight" to weight,
    )
}

/**
 * Parses declarations without pretending to reproduce Minikin's runtime merge or theme rules.
 * Family and alias order is retained per source file so an offline analysis can reconstruct the
 * declared graph while keeping it separate from the platform shaping observations.
 */
internal object FontConfigIndexParser {
    fun parse(bytes: ByteArray): FontConfigIndex {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            runCatching { isXIncludeAware = false }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        val root = document.documentElement ?: error("font config has no document element")

        val families = root.descendantsNamed("family").mapIndexed { order, family ->
            val legacyNames = family.directChildrenNamed("nameset")
                .flatMap { it.directChildrenNamed("name") }
                .mapNotNull { it.textContent?.trim()?.takeIf(String::isNotEmpty) }
            val names = buildList {
                family.attributeOrNull("name")?.let(::add)
                addAll(legacyNames)
            }.distinct()
            val languages = family.attributeOrNull("lang")
                ?.split(Regex("[\\s,]+"))
                ?.filter(String::isNotEmpty)
                .orEmpty()

            val modernFonts = family.directChildrenNamed("font").map(::parseFontElement)
            val legacyFonts = family.directChildrenNamed("fileset")
                .flatMap { it.directChildrenNamed("file") }
                .map(::parseFontElement)

            FontConfigFamily(
                order = order,
                names = names,
                languages = languages,
                variant = family.attributeOrNull("variant"),
                fallbackFor = family.attributeOrNull("fallbackFor"),
                fonts = modernFonts + legacyFonts,
            )
        }

        val aliases = root.descendantsNamed("alias").mapIndexedNotNull { order, alias ->
            val name = alias.attributeOrNull("name") ?: return@mapIndexedNotNull null
            val to = alias.attributeOrNull("to") ?: return@mapIndexedNotNull null
            FontConfigAlias(
                order = order,
                name = name,
                to = to,
                weight = alias.attributeOrNull("weight")?.toIntOrNull(),
            )
        }

        return FontConfigIndex(
            rootElement = root.tagName,
            version = root.attributeOrNull("version"),
            families = families,
            aliases = aliases,
        )
    }

    private fun parseFontElement(element: Element): FontConfigFont {
        val axes = element.directChildrenNamed("axis")
            .mapNotNull { axis ->
                val tag = axis.attributeOrNull("tag") ?: return@mapNotNull null
                val value = axis.attributeOrNull("stylevalue")?.toFloatOrNull()
                    ?: return@mapNotNull null
                tag to value
            }
            .toMap()
            .toSortedMap()
        val fileName = element.childNodes.asSequence()
            .filter { child -> child.nodeType == Node.TEXT_NODE || child.nodeType == Node.CDATA_SECTION_NODE }
            .joinToString(separator = "") { child -> child.nodeValue.orEmpty() }
            .trim()

        return FontConfigFont(
            fileName = fileName,
            weight = element.attributeOrNull("weight")?.toIntOrNull(),
            style = element.attributeOrNull("style"),
            ttcIndex = (element.attributeOrNull("index") ?: element.attributeOrNull("ttcIndex"))?.toIntOrNull(),
            fallbackFor = element.attributeOrNull("fallbackFor"),
            postScriptName = element.attributeOrNull("postScriptName"),
            axes = axes,
        )
    }

    private fun Element.attributeOrNull(name: String): String? =
        getAttribute(name).trim().takeIf(String::isNotEmpty)

    private fun Element.directChildrenNamed(name: String): List<Element> =
        childNodes.asSequence()
            .filterIsInstance<Element>()
            .filter { child -> child.tagName == name }
            .toList()

    private fun Element.descendantsNamed(name: String): List<Element> =
        getElementsByTagName(name).asSequence().filterIsInstance<Element>().toList()

    private fun org.w3c.dom.NodeList.asSequence(): Sequence<Node> = sequence {
        for (index in 0 until length) yield(item(index))
    }
}
