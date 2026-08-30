package org.tiqian.layout

import org.tiqian.test.EarlyLayoutFixtures
import kotlin.test.Test
import org.tiqian.test.trace.fail
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder
import org.tiqian.test.trace.assertTrue

/**
 * Cross-target golden net: the checked-in layout-dump goldens are embedded at
 * build time (`generateLayoutDumpGoldens`) and compared on EVERY target, so a
 * numeric or behavioral divergence between JVM, JS and Native — e.g. JS `Float`
 * arithmetic running in double precision — fails here instead of surfacing as
 * an unexplained frontend difference. Regeneration stays on the JVM:
 *
 * ```
 * TIQIAN_UPDATE_GOLDEN=1 ./gradlew :engine:jvmTest --tests 'org.tiqian.layout.LayoutDumpGoldenTest'
 * ```
 */
class LayoutDumpGoldenParityTest {
    private val testTrace = TestTraceRecorder("LayoutDumpGoldenParityTest")


    @Test
    fun layoutDecisionDumpsMatchEmbeddedGolden() {
        testTrace.section("layoutDecisionDumpsMatchEmbeddedGolden")
        val failures = mutableListOf<String>()
        for (fixture in EarlyLayoutFixtures.all) {
            val golden = LayoutDumpGoldens.byId[fixture.id]
            if (golden == null) {
                failures += "missing embedded golden for fixture '${fixture.id}' — " +
                    "run with TIQIAN_UPDATE_GOLDEN=1 on the JVM, then rebuild"
                continue
            }
            val dump = layoutFixtureDump(fixture)
            if (golden != dump) {
                failures += layoutDumpDiffMessage(fixture.id, golden, dump)
            }
        }
        assertTrue(
            failures.isEmpty(),
            failures.joinToString("\n\n") +
                "\n\nIf the change is intentional, regenerate with " +
                "TIQIAN_UPDATE_GOLDEN=1 on the JVM and review the golden diff.",
        )
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
