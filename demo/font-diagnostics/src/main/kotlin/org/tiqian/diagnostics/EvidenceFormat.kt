package org.tiqian.diagnostics

import java.security.MessageDigest

internal enum class EvidenceStatus(val wireValue: String) {
    Observed("observed"),
    Unsupported("unsupported"),
    Error("error"),
}

/**
 * Minimal deterministic JSON encoder for the evidence bundle.
 *
 * The collector deliberately avoids a serialization runtime: it is an APK that is passed to
 * other people's devices, while the wire model itself is only maps, lists and JSON primitives.
 */
internal object EvidenceJson {
    fun encode(value: Any?): String = buildString { appendValue(value) }

    private fun StringBuilder.appendValue(value: Any?) {
        when (value) {
            null -> append("null")
            is String -> appendQuoted(value)
            is Boolean -> append(if (value) "true" else "false")
            is Byte, is Short, is Int, is Long -> append(value)
            is Float -> if (value.isFinite()) append(value) else append("null")
            is Double -> if (value.isFinite()) append(value) else append("null")
            is Map<*, *> -> {
                append('{')
                value.entries.forEachIndexed { index, entry ->
                    require(entry.key is String) { "JSON object keys must be strings" }
                    if (index > 0) append(',')
                    appendQuoted(entry.key as String)
                    append(':')
                    appendValue(entry.value)
                }
                append('}')
            }
            is Iterable<*> -> {
                append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    appendValue(item)
                }
                append(']')
            }
            is Array<*> -> appendValue(value.asIterable())
            is IntArray -> appendValue(value.asIterable())
            is FloatArray -> appendValue(value.asIterable())
            else -> error("Unsupported JSON value: ${value::class.java.name}")
        }
    }

    private fun StringBuilder.appendQuoted(value: String) {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) {
                    append("\\u")
                    append(char.code.toString(16).padStart(4, '0'))
                } else {
                    append(char)
                }
            }
        }
        append('"')
    }
}

internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

internal fun stableToken(value: String): String {
    val readable = value.lowercase()
        .map { char -> if (char.isLetterOrDigit()) char else '-' }
        .joinToString(separator = "")
        .replace(Regex("-+"), "-")
        .trim('-')
        .take(32)
        .ifEmpty { "value" }
    return "$readable-${sha256(value.toByteArray(Charsets.UTF_8)).take(8)}"
}
