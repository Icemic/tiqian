package org.tiqian.trace

import java.io.File
import kotlin.test.Test

/**
 * Generator for the process-trace golden files (Haxe port contract).
 *
 * The scenario corpus lives in commonTest (`TraceScenarios`); a
 * TIQIAN_UPDATE_GOLDEN=1 run writes one file per scenario under
 * `golden/process-traces/`. Regular runs return without touching the
 * files. The Haxe port runs the same scenario bodies and diffs against
 * these files.
 *
 * To regenerate:
 *
 * ```
 * TIQIAN_UPDATE_GOLDEN=1 ./gradlew :engine:jvmTest
 * ```
 */
class TraceGoldenTest {

    private val goldenDir = File("src/jvmTest/resources/golden/process-traces")
    private val updateMode = System.getenv("TIQIAN_UPDATE_GOLDEN") == "1"

    @Test
    fun writeProcessTraces() {
        if (!updateMode) return
        for (scenario in TraceScenarios.all) {
            val goldenFile = File(goldenDir, "${scenario.id}.txt")
            goldenFile.parentFile.mkdirs()
            goldenFile.writeText(scenario.run())
        }
        println("process traces written to ${goldenDir.absolutePath}")
    }
}
