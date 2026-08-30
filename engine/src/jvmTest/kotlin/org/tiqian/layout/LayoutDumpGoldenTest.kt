package org.tiqian.layout

import org.tiqian.test.EarlyLayoutFixtures
import java.io.File
import kotlin.test.Test
import org.tiqian.test.trace.fail
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder
import org.tiqian.test.trace.assertTrue

/**
 * Golden regression baseline for layout decisions (Slice 6 验收).
 *
 * Every fixture is laid out with the deterministic stub shaper (platform
 * fonts would make goldens machine-dependent) under both breakers, and the
 * full structured decision dump is compared against the checked-in golden.
 * Any change to line breaking, punctuation geometry, spacing, justification
 * or repairs shows up as a readable diff here instead of waiting for an
 * eyeball in the playground.
 *
 * The dump builder lives in commonTest (`layoutFixtureDump`), where
 * [LayoutDumpGoldenParityTest] replays the same goldens on every target;
 * this JVM class owns the checked-in files and the update path.
 *
 * To regenerate after an INTENTIONAL behaviour change:
 *
 * ```
 * TIQIAN_UPDATE_GOLDEN=1 ./gradlew :engine:jvmTest --tests '*LayoutDumpGoldenTest*'
 * ```
 *
 * then review the golden diff like any other code change.
 */
class LayoutDumpGoldenTest {
    private val testTrace = TestTraceRecorder("LayoutDumpGoldenTest")


    private val goldenDir = File("src/jvmTest/resources/golden/layout-dumps")
    private val updateMode = System.getenv("TIQIAN_UPDATE_GOLDEN") == "1"

    @Test
    fun layoutDecisionDumpsMatchGolden() {
        testTrace.section("layoutDecisionDumpsMatchGolden")
        val failures = mutableListOf<String>()
        for (fixture in EarlyLayoutFixtures.all) {
            val dump = layoutFixtureDump(fixture)
            val goldenFile = File(goldenDir, "${fixture.id}.txt")
            if (updateMode) {
                goldenFile.parentFile.mkdirs()
                goldenFile.writeText(dump)
                continue
            }
            if (!goldenFile.exists()) {
                failures += "missing golden ${goldenFile.path} — run with TIQIAN_UPDATE_GOLDEN=1"
                continue
            }
            val expected = goldenFile.readText()
            if (expected != dump) {
                failures += layoutDumpDiffMessage(fixture.id, expected, dump)
            }
        }
        assertTrue(
            failures.isEmpty(),
            failures.joinToString("\n\n") +
                "\n\nIf the change is intentional, regenerate with " +
                "TIQIAN_UPDATE_GOLDEN=1 and review the golden diff.",
        )
        if (updateMode) {
            println("golden dumps written to ${goldenDir.absolutePath}")
        }
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
