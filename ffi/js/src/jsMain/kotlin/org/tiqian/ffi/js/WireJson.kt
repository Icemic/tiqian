package org.tiqian.ffi.js

import org.tiqian.font.FontMetricsRequest
import org.tiqian.font.FontRequest
import org.tiqian.font.FontRole

/**
 * JSON decode helpers shared by the standalone capability exports
 * (`ClreqExports.kt`, `LineBreakExports.kt`, `FontExports.kt`). The wire is
 * JSON-through-JavaScript: every request arrives as a JSON string, so these
 * helpers reconstruct the engine value types without touching the engine.
 */

internal fun parseFontMetricsRequestJson(json: String): FontMetricsRequest {
    val raw = kotlin.js.JSON.parse<dynamic>(json) ?: return FontMetricsRequest(
        fontKey = "",
        fontSize = Float.NaN,
        role = FontRole.Unknown,
        locale = "",
    )
    return FontMetricsRequest(
        fontKey = (raw.fontKey as? String) ?: "",
        fontSize = (raw.fontSize as? Double)?.toFloat() ?: Float.NaN,
        role = (raw.role as? String)?.let { FontRole.valueOf(it) } ?: FontRole.Unknown,
        locale = (raw.locale as? String) ?: "",
        fontFamilies = parseDynamicStringList(raw.fontFamilies),
        fontWeight = (raw.fontWeight as? Double)?.toInt() ?: 400,
        italic = (raw.italic as? Boolean) ?: false,
        faceSelectionText = (raw.faceSelectionText as? String) ?: "",
    )
}

internal fun parseFontRequestJson(json: String): FontRequest {
    val raw = kotlin.js.JSON.parse<dynamic>(json) ?: return FontRequest(
        preferredFamilies = emptyList(),
        locale = "",
        role = FontRole.Unknown,
    )
    return FontRequest(
        preferredFamilies = parseDynamicStringList(raw.preferredFamilies),
        locale = (raw.locale as? String) ?: "",
        role = (raw.role as? String)?.let { FontRole.valueOf(it) } ?: FontRole.Unknown,
    )
}

internal fun parsePatternsJson(json: String): Map<String, IntArray> {
    val raw = kotlin.js.JSON.parse<dynamic>(json) ?: return emptyMap()
    val keys = js("Object.keys(raw)") as Array<String>
    return HashMap<String, IntArray>(keys.size).apply {
        for (key in keys) {
            val values = raw[key] as? Array<dynamic>
            this[key] = IntArray(values?.size ?: 0) { i -> (values!![i] as? Double)?.toInt() ?: 0 }
        }
    }
}

internal fun parseExceptionsJson(json: String): Map<String, List<Int>> {
    val raw = kotlin.js.JSON.parse<dynamic>(json) ?: return emptyMap()
    val keys = js("Object.keys(raw)") as Array<String>
    return HashMap<String, List<Int>>(keys.size).apply {
        for (key in keys) {
            val values = raw[key] as? Array<dynamic>
            this[key] = values?.map { (it as? Double)?.toInt() ?: 0 } ?: emptyList()
        }
    }
}

internal fun parseDynamicStringList(raw: dynamic): List<String> {
    if (raw == null) return emptyList()
    val length = (raw.length as? Double)?.toInt() ?: return emptyList()
    val list = ArrayList<String>(length)
    for (i in 0 until length) {
        list.add((raw[i] as? String) ?: "")
    }
    return list
}