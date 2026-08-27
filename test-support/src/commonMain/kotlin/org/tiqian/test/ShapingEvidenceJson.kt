package org.tiqian.test

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.tiqian.core.Rect

/**
 * Language-neutral JSON form of [ShapingEvidence]. The file on disk is the
 * portable conformance artifact; Kotlin is one consumer, a non-Kotlin engine
 * port can replay the same corpus.
 */
object ShapingEvidenceJson {

    fun encode(evidence: ShapingEvidence): String {
        val root = buildJsonObject {
            put(
                "meta",
                buildJsonObject {
                    evidence.meta.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                },
            )
            put(
                "shaping",
                buildJsonArray {
                    evidence.shaping.forEach { (key, result) ->
                        add(
                            buildJsonObject {
                                put("key", key.toJson())
                                put("result", result.toJson())
                            },
                        )
                    }
                },
            )
            put(
                "metrics",
                buildJsonArray {
                    evidence.metrics.forEach { (key, result) ->
                        add(
                            buildJsonObject {
                                put("key", key.toJson())
                                put("result", result.toJson())
                            },
                        )
                    }
                },
            )
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    fun parse(text: String): ShapingEvidence {
        val root = json.parseToJsonElement(text).jsonObject
        val meta = root.getValue("meta").jsonObject
            .mapValues { (_, v) -> v.jsonPrimitive.content }
        val shaping = root.getValue("shaping").jsonArray.associate { entry ->
            val obj = entry.jsonObject
            obj.getValue("key").jsonObject.toShapingKey() to
                obj.getValue("result").jsonObject.toShapingResult()
        }
        val metrics = root.getValue("metrics").jsonArray.associate { entry ->
            val obj = entry.jsonObject
            obj.getValue("key").jsonObject.toMetricsKey() to
                obj.getValue("result").jsonObject.toFontMetrics()
        }
        return ShapingEvidence(meta = meta, shaping = shaping, metrics = metrics)
    }

    private val json = Json { prettyPrint = true }

    private fun ShapingEvidenceKey.toJson(): JsonObject = buildJsonObject {
        put("displayText", JsonPrimitive(displayText))
        put("fontKey", JsonPrimitive(fontKey))
        put("fontFamily", JsonPrimitive(fontFamily))
        put("role", JsonPrimitive(role))
        put("styleFontFamilies", styleFontFamilies.toJsonArray())
        put("fontSize", JsonPrimitive(fontSize))
        put("fontWeight", JsonPrimitive(fontWeight))
        put("italic", JsonPrimitive(italic))
        put("locale", JsonPrimitive(locale))
        put("openTypeFeatures", openTypeFeatures.toJsonArray())
    }

    private fun JsonObject.toShapingKey(): ShapingEvidenceKey = ShapingEvidenceKey(
        displayText = getValue("displayText").jsonPrimitive.content,
        fontKey = getValue("fontKey").jsonPrimitive.content,
        fontFamily = getValue("fontFamily").jsonPrimitive.content,
        role = getValue("role").jsonPrimitive.content,
        styleFontFamilies = getValue("styleFontFamilies").jsonArray.toStringList(),
        fontSize = getValue("fontSize").jsonPrimitive.float,
        fontWeight = getValue("fontWeight").jsonPrimitive.int,
        italic = getValue("italic").jsonPrimitive.boolean,
        locale = getValue("locale").jsonPrimitive.content,
        openTypeFeatures = getValue("openTypeFeatures").jsonArray.toStringList(),
    )

    private fun RecordedShapingResult.toJson(): JsonObject = buildJsonObject {
        put("clusterAdvance", JsonPrimitive(clusterAdvance))
        put("runAdvance", JsonPrimitive(runAdvance))
        put("runFeatures", runFeatures.toJsonArray())
        put(
            "glyphs",
            buildJsonArray {
                glyphs.forEach { g ->
                    add(
                        buildJsonObject {
                            put("id", JsonPrimitive(g.id))
                            put("advance", JsonPrimitive(g.advance))
                            put("x", JsonPrimitive(g.x))
                            put("y", JsonPrimitive(g.y))
                            put("bounds", g.bounds.toJson())
                            put("haltAdvance", g.haltAdvance.toJsonFloat())
                            put("haltPlacementX", g.haltPlacementX.toJsonFloat())
                        },
                    )
                }
            },
        )
        put(
            "decisions",
            buildJsonArray {
                decisions.forEach { d ->
                    add(
                        buildJsonObject {
                            put("glyphCount", JsonPrimitive(d.glyphCount))
                            put("advance", JsonPrimitive(d.advance))
                            put("source", JsonPrimitive(d.source))
                            put("reason", JsonPrimitive(d.reason))
                            put("glyphsWithoutInkBounds", JsonPrimitive(d.glyphsWithoutInkBounds))
                            put("missingGlyphs", JsonPrimitive(d.missingGlyphs))
                            put("resolvedFace", d.resolvedFace.toJsonString())
                            put("script", d.script.toJsonString())
                            put("language", d.language.toJsonString())
                            put("strategy", d.strategy.toJsonString())
                            put("featureEvidence", d.featureEvidence.toJsonString())
                            put("capabilityIssue", d.capabilityIssue.toJsonString())
                        },
                    )
                }
            },
        )
    }

    private fun JsonObject.toShapingResult(): RecordedShapingResult = RecordedShapingResult(
        clusterAdvance = getValue("clusterAdvance").jsonPrimitive.float,
        runAdvance = getValue("runAdvance").jsonPrimitive.float,
        runFeatures = getValue("runFeatures").jsonArray.toStringList(),
        glyphs = getValue("glyphs").jsonArray.map { g ->
            val obj = g.jsonObject
            RecordedGlyph(
                id = obj.getValue("id").jsonPrimitive.long,
                advance = obj.getValue("advance").jsonPrimitive.float,
                x = obj.getValue("x").jsonPrimitive.float,
                y = obj.getValue("y").jsonPrimitive.float,
                bounds = obj.getValue("bounds").toRectOrNull(),
                haltAdvance = obj.getValue("haltAdvance").toFloatOrNull(),
                haltPlacementX = obj.getValue("haltPlacementX").toFloatOrNull(),
            )
        },
        decisions = getValue("decisions").jsonArray.map { d ->
            val obj = d.jsonObject
            RecordedShapingDecision(
                glyphCount = obj.getValue("glyphCount").jsonPrimitive.int,
                advance = obj.getValue("advance").jsonPrimitive.float,
                source = obj.getValue("source").jsonPrimitive.content,
                reason = obj.getValue("reason").jsonPrimitive.content,
                glyphsWithoutInkBounds = obj.getValue("glyphsWithoutInkBounds").jsonPrimitive.int,
                missingGlyphs = obj.getValue("missingGlyphs").jsonPrimitive.int,
                resolvedFace = obj.getValue("resolvedFace").toStringOrNull(),
                script = obj.getValue("script").toStringOrNull(),
                language = obj.getValue("language").toStringOrNull(),
                strategy = obj.getValue("strategy").toStringOrNull(),
                featureEvidence = obj.getValue("featureEvidence").toStringOrNull(),
                capabilityIssue = obj.getValue("capabilityIssue").toStringOrNull(),
            )
        },
    )

    private fun MetricsEvidenceKey.toJson(): JsonObject = buildJsonObject {
        put("fontKey", JsonPrimitive(fontKey))
        put("fontSize", JsonPrimitive(fontSize))
        put("role", JsonPrimitive(role))
        put("locale", JsonPrimitive(locale))
        put("fontFamilies", fontFamilies.toJsonArray())
        put("fontWeight", JsonPrimitive(fontWeight))
        put("italic", JsonPrimitive(italic))
        put("faceSelectionText", JsonPrimitive(faceSelectionText))
    }

    private fun JsonObject.toMetricsKey(): MetricsEvidenceKey = MetricsEvidenceKey(
        fontKey = getValue("fontKey").jsonPrimitive.content,
        fontSize = getValue("fontSize").jsonPrimitive.float,
        role = getValue("role").jsonPrimitive.content,
        locale = getValue("locale").jsonPrimitive.content,
        fontFamilies = getValue("fontFamilies").jsonArray.toStringList(),
        fontWeight = getValue("fontWeight").jsonPrimitive.int,
        italic = getValue("italic").jsonPrimitive.boolean,
        faceSelectionText = getValue("faceSelectionText").jsonPrimitive.content,
    )

    private fun RecordedFontMetrics.toJson(): JsonObject = buildJsonObject {
        put("ascent", JsonPrimitive(ascent))
        put("descent", JsonPrimitive(descent))
        put("leading", JsonPrimitive(leading))
        put("source", JsonPrimitive(source))
        put("typoAscent", typoAscent.toJsonFloat())
        put("typoDescent", typoDescent.toJsonFloat())
    }

    private fun JsonObject.toFontMetrics(): RecordedFontMetrics = RecordedFontMetrics(
        ascent = getValue("ascent").jsonPrimitive.float,
        descent = getValue("descent").jsonPrimitive.float,
        leading = getValue("leading").jsonPrimitive.float,
        source = getValue("source").jsonPrimitive.content,
        typoAscent = getValue("typoAscent").toFloatOrNull(),
        typoDescent = getValue("typoDescent").toFloatOrNull(),
    )

    private fun Rect?.toJson(): kotlinx.serialization.json.JsonElement =
        if (this == null) {
            JsonNull
        } else {
            buildJsonArray {
                add(JsonPrimitive(left))
                add(JsonPrimitive(top))
                add(JsonPrimitive(right))
                add(JsonPrimitive(bottom))
            }
        }

    private fun kotlinx.serialization.json.JsonElement.toRectOrNull(): Rect? {
        if (this is JsonNull) return null
        val values = jsonArray
        return Rect(
            left = values[0].jsonPrimitive.float,
            top = values[1].jsonPrimitive.float,
            right = values[2].jsonPrimitive.float,
            bottom = values[3].jsonPrimitive.float,
        )
    }

    private fun Float?.toJsonFloat(): kotlinx.serialization.json.JsonElement =
        if (this == null) JsonNull else JsonPrimitive(this)

    private fun kotlinx.serialization.json.JsonElement.toFloatOrNull(): Float? =
        if (this is JsonNull) null else jsonPrimitive.float

    private fun String?.toJsonString(): kotlinx.serialization.json.JsonElement =
        if (this == null) JsonNull else JsonPrimitive(this)

    private fun kotlinx.serialization.json.JsonElement.toStringOrNull(): String? =
        if (this is JsonNull) null else jsonPrimitive.content

    private fun List<String>.toJsonArray(): JsonArray = buildJsonArray {
        this@toJsonArray.forEach { add(JsonPrimitive(it)) }
    }

    private fun JsonArray.toStringList(): List<String> = map { it.jsonPrimitive.content }
}
