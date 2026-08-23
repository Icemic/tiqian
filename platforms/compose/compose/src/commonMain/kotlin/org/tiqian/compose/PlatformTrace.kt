package org.tiqian.compose

internal expect fun beginTiqianTraceSection(name: String)

internal expect fun endTiqianTraceSection()

internal inline fun <T> tiqianTraceSection(name: String, block: () -> T): T {
    beginTiqianTraceSection(name)
    return try {
        block()
    } finally {
        endTiqianTraceSection()
    }
}
