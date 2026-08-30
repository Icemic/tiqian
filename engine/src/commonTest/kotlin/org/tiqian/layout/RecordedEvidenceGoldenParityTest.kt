package org.tiqian.layout

import org.tiqian.test.EarlyLayoutFixtures
import org.tiqian.test.RecordedEvidenceFontMetricsResolver
import org.tiqian.test.RecordedEvidenceTextShaper
import org.tiqian.test.ShapingEvidenceJson
import kotlin.test.Test
import org.tiqian.test.trace.fail
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder
import org.tiqian.test.trace.assertTrue

/**
 * Replays the recorded third-party shaping corpus on every target: the engine
 * lays out each fixture with the recorded Skia/HarfBuzz shaping answers and
 * font metrics instead of the deterministic stub, and the resulting decision
 * dumps must match the goldens written at recording time. This proves engine
 * behavior against real font evidence — fallback rollback on missing glyphs,
 * halt-informed punctuation geometry, ink-bounds heuristics — without any
 * platform font stack, so the suite runs identically for JS/Native consumers
 * and future engine ports. Re-record on the JVM after intentional changes:
 *
 * ```
 * TIQIAN_RECORD_SHAPING=1 ./gradlew :engine:jvmTest --tests '*ShapingEvidenceRecorder*'
 * ```
 */
class RecordedEvidenceGoldenParityTest {
    private val testTrace = TestTraceRecorder("RecordedEvidenceGoldenParityTest")


    @Test
    fun recordedEvidenceLayoutMatchesGolden() {
        testTrace.section("recordedEvidenceLayoutMatchesGolden")
        if (RecordedShapingEvidenceData.EVIDENCE_JSON.isEmpty()) {
            fail(
                "No recorded shaping evidence embedded — record on the JVM with " +
                    "TIQIAN_RECORD_SHAPING=1 ./gradlew :engine:jvmTest --tests '*ShapingEvidenceRecorder*'",
            )
        }
        val evidence = ShapingEvidenceJson.parse(RecordedShapingEvidenceData.EVIDENCE_JSON)
        val shaper = RecordedEvidenceTextShaper(evidence)
        val metrics = RecordedEvidenceFontMetricsResolver(evidence)
        val failures = mutableListOf<String>()
        for (fixture in EarlyLayoutFixtures.all) {
            val golden = RecordedLayoutDumpGoldens.byId[fixture.id]
            if (golden == null) {
                failures += "missing recorded golden for fixture '${fixture.id}' — re-record"
                continue
            }
            val dump = layoutFixtureDump(fixture, textShaper = shaper, fontMetricsResolver = metrics)
            if (golden != dump) {
                failures += layoutDumpDiffMessage(fixture.id, golden, dump)
            }
        }
        assertTrue(
            failures.isEmpty(),
            failures.joinToString("\n\n") +
                "\n\nIf the change is intentional, re-record with " +
                "TIQIAN_RECORD_SHAPING=1 on the JVM and review the golden diff.",
        )
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
