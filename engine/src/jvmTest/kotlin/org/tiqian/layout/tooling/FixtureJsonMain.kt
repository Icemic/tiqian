package org.tiqian.layout.tooling

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tiqian.core.DecorationSpan
import org.tiqian.core.Ic
import org.tiqian.core.LayoutInput
import org.tiqian.core.LineBreakSpan
import org.tiqian.core.ParagraphStyle
import org.tiqian.core.RubySpan
import org.tiqian.core.TextRange
import org.tiqian.core.TiqianTextContent
import org.tiqian.test.EarlyLayoutFixtures

/** Development-only fixture exporter. It serializes values already resolved by Kotlin fixtures. */
fun main(args: Array<String>) {
    require(args.size == 1) { "Usage: exportLayoutFixture -PfixtureId=<id>" }
    val fixture = EarlyLayoutFixtures.all.singleOrNull { it.id == args.single() }
        ?: error("Unknown EarlyLayoutFixture '${args.single()}'")
    val input = LayoutInput(
        content = TiqianTextContent(fixture.text, lineBreakSpans = fixture.lineBreakSpans),
        constraints = fixture.constraints,
        paragraphStyle = ParagraphStyle(
            lineHeight = fixture.lineHeight,
            firstLineIndent = fixture.firstLineIndentEm?.let(::Ic),
            rubyLineHeightMode = fixture.rubyLineHeightMode,
            lineLengthGrid = fixture.lineLengthGrid,
        ),
        decorations = fixture.decorations,
        rubySpans = fixture.rubySpans,
    )
    println(
        buildJsonObject {
            put("id", fixture.id)
            put("useEnglishHyphenation", fixture.useEnglishHyphenation)
            put("pinBasicNoHang", fixture.pinBasicNoHang)
            put("input", input.json())
        },
    )
}

private fun LayoutInput.json(): JsonObject = buildJsonObject {
    put("content", buildJsonObject {
        put("text", content.text)
        put("sourceBoundaries", content.sourceBoundaries.sorted().jsonInts())
        put("lineBreakSpans", content.lineBreakSpans.jsonLineBreakSpans())
        put("autoSpaceSuppressedRanges", content.autoSpaceSuppressedRanges.jsonRanges())
        put("spans", JsonArray(emptyList()))
    })
    put("textStyle", buildJsonObject {
        put("fontFamilies", textStyle.fontFamilies.jsonStrings())
        put("fontSize", textStyle.fontSize)
        put("locale", textStyle.locale)
        put("fontWeight", textStyle.fontWeight)
        put("italic", textStyle.italic)
        put("baselineShift", textStyle.baselineShift)
        put("inlineAttachment", textStyle.inlineAttachment.name)
    })
    put("paragraphStyle", buildJsonObject {
        put("lastLineAlignment", paragraphStyle.lastLineAlignment.name)
        put("writingMode", paragraphStyle.writingMode.name)
        put("lineHeight", paragraphStyle.lineHeight?.let(::JsonPrimitive) ?: JsonNull)
        put("firstLineIndent", paragraphStyle.firstLineIndent?.count?.let(::JsonPrimitive) ?: JsonNull)
        put("blockIndent", paragraphStyle.blockIndent.count)
        put("firstLineIndentPolicy", buildJsonObject {
            put("shortBelowEm", paragraphStyle.firstLineIndentPolicy.shortBelowEm)
            put("shortEm", paragraphStyle.firstLineIndentPolicy.shortEm)
            put("longEm", paragraphStyle.firstLineIndentPolicy.longEm)
        })
        put("lineLengthGrid", buildJsonObject {
            put("enabled", paragraphStyle.lineLengthGrid.enabled)
            put("bodyAlignment", paragraphStyle.lineLengthGrid.bodyAlignment?.name?.let(::JsonPrimitive) ?: JsonNull)
        })
        put("rubyLineHeightMode", paragraphStyle.rubyLineHeightMode.name)
        put("inlineObjectMinimumClearanceEm", paragraphStyle.inlineObjectMinimumClearanceEm)
        put("emphasisDotGapEm", paragraphStyle.emphasisDotGapEm)
    })
    put("constraints", buildJsonObject {
        put("maxWidth", constraints.maxWidth)
        put("maxHeight", constraints.maxHeight.takeIf(Float::isFinite)?.let(::JsonPrimitive) ?: JsonNull)
        put("maxLines", constraints.maxLines)
    })
    put("profileId", profileId.value)
    put("decorations", decorations.jsonDecorations())
    put("rubySpans", rubySpans.jsonRubySpans())
    put("inlineBoxes", JsonArray(emptyList()))
    put("inlineObjects", JsonArray(emptyList()))
}

private fun TextRange.json(): JsonObject = buildJsonObject {
    put("start", start)
    put("end", end)
}

private fun List<Int>.jsonInts(): JsonArray = buildJsonArray { forEach { add(JsonPrimitive(it)) } }
private fun List<String>.jsonStrings(): JsonArray = buildJsonArray { forEach { add(JsonPrimitive(it)) } }
private fun List<TextRange>.jsonRanges(): JsonArray = buildJsonArray { forEach { add(it.json()) } }
private fun List<LineBreakSpan>.jsonLineBreakSpans(): JsonArray = buildJsonArray {
    forEach { span -> add(buildJsonObject { put("range", span.range.json()); put("policy", span.policy.name) }) }
}
private fun List<DecorationSpan>.jsonDecorations(): JsonArray = buildJsonArray {
    forEach { span -> add(buildJsonObject { put("range", span.range.json()); put("kind", span.kind.name) }) }
}
private fun List<RubySpan>.jsonRubySpans(): JsonArray = buildJsonArray {
    forEach { span ->
        add(buildJsonObject {
            put("baseRange", span.baseRange.json())
            put("text", span.text)
            put("fontFamilies", span.fontFamilies.jsonStrings())
            put("kind", span.kind.name)
            put("locale", span.locale?.let(::JsonPrimitive) ?: JsonNull)
        })
    }
}