package org.tiqian.android.rendering

import android.os.Trace

internal inline fun <T> tiqianTraceSection(name: String, block: () -> T): T {
    Trace.beginSection(name)
    return try {
        block()
    } finally {
        Trace.endSection()
    }
}
