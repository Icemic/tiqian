package org.tiqian.test.trace

import java.io.File

internal actual object TestTracePlatform {

    actual val updateMode: Boolean = System.getenv("TIQIAN_UPDATE_GOLDEN") == "1"

    actual val doubleArithmetic: Boolean = false

    actual fun writeGolden(className: String, text: String) {
        val goldenDir = File("src/jvmTest/resources/golden/test-traces")
        goldenDir.mkdirs()
        File(goldenDir, "$className.txt").writeText(text)
    }
}
