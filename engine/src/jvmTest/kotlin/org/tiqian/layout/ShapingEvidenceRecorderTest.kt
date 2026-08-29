package org.tiqian.layout

import org.tiqian.shaping.skia.SkiaFontMetricsResolver
import org.tiqian.shaping.skia.SkiaSystemTypefaces
import org.tiqian.shaping.skia.SkiaTextShaper
import org.tiqian.test.EarlyLayoutFixtures
import org.tiqian.test.RecordingFontMetricsResolver
import org.tiqian.test.RecordingTextShaper
import org.tiqian.test.ShapingEvidenceBuilder
import org.tiqian.test.ShapingEvidenceJson
import java.io.File
import kotlin.test.Test

/**
 * Records third-party shaping evidence: lays out the fixture corpus with the
 * real Skia/HarfBuzz shaper and metrics resolver, captures every request and
 * answer at the `TextShaper` / `FontMetricsResolver` contracts, and writes
 *
 *  - `golden/shaping-evidence.json` — the recorded corpus (language-neutral),
 *  - `golden/layout-dumps-recorded/` — per-fixture decision dumps the engine
 *    produces from that evidence.
 *
 * [RecordedEvidenceGoldenParityTest] replays both on every target without a
 * platform font stack. Recording itself uses the machine's system fonts, so
 * only re-recording is JVM- and machine-bound; the checked-in corpus is
 * deterministic everywhere. To re-record after an intentional change:
 *
 * ```
 * TIQIAN_RECORD_SHAPING=1 ./gradlew :engine:jvmTest --tests '*ShapingEvidenceRecorder*'
 * ```
 */
class ShapingEvidenceRecorderTest {

    private val goldenRoot = File("src/jvmTest/resources/golden")
    private val recordMode = System.getenv("TIQIAN_RECORD_SHAPING") == "1"

    @Test
    fun recordShapingEvidence() {
        if (!recordMode) {
            println("TIQIAN_RECORD_SHAPING not set — skipping shaping evidence recording")
            return
        }
        val sink = ShapingEvidenceBuilder()
        val shaper = RecordingTextShaper(SkiaTextShaper(), sink)
        val metrics = RecordingFontMetricsResolver(SkiaFontMetricsResolver(), sink)
        val dumpDir = File(goldenRoot, "layout-dumps-recorded")
        dumpDir.mkdirs()
        for (fixture in EarlyLayoutFixtures.all) {
            val dump = layoutFixtureDump(fixture, textShaper = shaper, fontMetricsResolver = metrics)
            File(dumpDir, "${fixture.id}.txt").writeText(dump)
        }
        val evidence = sink.build(
            meta = buildMap {
                put("recorder", "skia-jvm")
                put("os", System.getProperty("os.name") ?: "unknown")
                SkiaSystemTypefaces.cjk?.let { put("cjkFace", it.familyName) }
                SkiaSystemTypefaces.latin?.let { put("latinFace", it.familyName) }
            },
        )
        File(goldenRoot, "shaping-evidence.json").writeText(ShapingEvidenceJson.encode(evidence))
        println(
            "recorded ${evidence.shaping.size} shaping runs and ${evidence.metrics.size} metric " +
                "answers to ${goldenRoot.absolutePath}",
        )
    }
}
