package org.tiqian.compose

import android.os.Trace

internal actual fun beginTiqianTraceSection(name: String) {
    Trace.beginSection(name)
}

internal actual fun endTiqianTraceSection() {
    Trace.endSection()
}
