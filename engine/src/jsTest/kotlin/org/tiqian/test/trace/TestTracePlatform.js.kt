package org.tiqian.test.trace

internal actual object TestTracePlatform {

    actual val updateMode: Boolean = false

    actual val doubleArithmetic: Boolean = true

    actual fun writeGolden(className: String, text: String) {
        // Compare-only target: the embedded constants carry the golden.
    }
}
