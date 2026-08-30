package org.tiqian.test

import org.tiqian.core.Cluster
import org.tiqian.core.Glyph
import org.tiqian.core.GlyphRun
import org.tiqian.core.Rect
import org.tiqian.core.ShapingDecisionInfo
import org.tiqian.font.FontMetricSource
import org.tiqian.font.FontMetricsRequest
import org.tiqian.font.FontMetricsResolver
import org.tiqian.font.RawFontMetrics
import org.tiqian.shaping.ShapingInput
import org.tiqian.shaping.ShapingResult
import org.tiqian.shaping.TextShaper

/**
 * Recorded third-party shaping evidence (JSON on disk, embedded for common
 * tests): every `TextShaper` and `FontMetricsResolver` request issued while
 * laying out the fixture corpus with a real platform shaper, keyed by the
 * request fields that determine the platform answer. Replay is exact and
 * fails loudly on a request outside the recorded corpus, so engine behavior
 * against real font evidence is testable on every target without that
 * platform's font stack.
 */
data class ShapingEvidence(
    val meta: Map<String, String>,
    val shaping: Map<ShapingEvidenceKey, RecordedShapingResult>,
    val metrics: Map<MetricsEvidenceKey, RecordedFontMetrics>,
)

/** The [ShapingInput] fields a platform shaper's answer can depend on. Source offsets are excluded. */
data class ShapingEvidenceKey(
    val displayText: String,
    val fontKey: String,
    val fontFamily: String,
    val role: String,
    val styleFontFamilies: List<String>,
    val fontSize: Float,
    val fontWeight: Int,
    val italic: Boolean,
    val locale: String,
    val openTypeFeatures: List<String>,
)

internal fun ShapingInput.toEvidenceKey(): ShapingEvidenceKey = ShapingEvidenceKey(
    displayText = displayText,
    fontKey = fontDecision.candidate.key,
    fontFamily = fontDecision.candidate.family,
    role = fontDecision.role.name,
    styleFontFamilies = style.fontFamilies,
    fontSize = style.fontSize,
    fontWeight = style.fontWeight,
    italic = style.italic,
    locale = style.locale,
    openTypeFeatures = openTypeFeatures,
)

data class RecordedGlyph(
    val id: Long,
    val advance: Float,
    val x: Float,
    val y: Float,
    val bounds: Rect?,
    val haltAdvance: Float?,
    val haltPlacementX: Float?,
)

data class RecordedShapingDecision(
    val glyphCount: Int,
    val advance: Float,
    val source: String,
    val reason: String,
    val glyphsWithoutInkBounds: Int,
    val missingGlyphs: Int,
    val resolvedFace: String?,
    val script: String?,
    val language: String?,
    val strategy: String?,
    val featureEvidence: String?,
    val capabilityIssue: String?,
)

data class RecordedShapingResult(
    val clusterAdvance: Float,
    val runAdvance: Float,
    val runFeatures: List<String>,
    val glyphs: List<RecordedGlyph>,
    val decisions: List<RecordedShapingDecision>,
)

data class MetricsEvidenceKey(
    val fontKey: String,
    val fontSize: Float,
    val role: String,
    val locale: String,
    val fontFamilies: List<String>,
    val fontWeight: Int,
    val italic: Boolean,
    val faceSelectionText: String,
)

internal fun FontMetricsRequest.toEvidenceKey(): MetricsEvidenceKey = MetricsEvidenceKey(
    fontKey = fontKey,
    fontSize = fontSize,
    role = role.name,
    locale = locale,
    fontFamilies = fontFamilies,
    fontWeight = fontWeight,
    italic = italic,
    faceSelectionText = faceSelectionText,
)

data class RecordedFontMetrics(
    val ascent: Float,
    val descent: Float,
    val leading: Float,
    val source: String,
    val typoAscent: Float?,
    val typoDescent: Float?,
) {
    fun toRawFontMetrics(): RawFontMetrics = RawFontMetrics(
        ascent = ascent,
        descent = descent,
        leading = leading,
        source = FontMetricSource.valueOf(source),
        typoAscent = typoAscent,
        typoDescent = typoDescent,
    )
}

/** Replays recorded shaping answers; a request outside the corpus is a loud failure, never a guess. */
class RecordedEvidenceTextShaper(private val evidence: ShapingEvidence) : TextShaper {
    override fun shape(input: ShapingInput): ShapingResult {
        val key = input.toEvidenceKey()
        val recorded = evidence.shaping[key] ?: error(
            "No recorded shaping evidence for $key — re-record on the JVM with " +
                "TIQIAN_RECORD_SHAPING=1 ./gradlew :engine:jvmTest --tests '*ShapingEvidenceRecorder*'",
        )
        val sourceText = input.text.substring(input.range.start, input.range.end)
        val fontKey = input.fontDecision.candidate.key
        val cluster = Cluster(
            range = input.range,
            text = sourceText,
            displayText = input.displayText,
            fontKey = fontKey,
            advance = recorded.clusterAdvance,
        )
        val glyphs = recorded.glyphs.map { g ->
            Glyph(
                id = g.id.toUInt(),
                clusterRange = input.range,
                advance = g.advance,
                x = g.x,
                y = g.y,
                bounds = g.bounds,
                haltAdvance = g.haltAdvance,
                haltPlacementX = g.haltPlacementX,
            )
        }
        val run = GlyphRun(
            range = input.range,
            fontKey = fontKey,
            glyphs = glyphs,
            advance = recorded.runAdvance,
            openTypeFeatures = recorded.runFeatures,
        )
        val decisions = recorded.decisions.map { d ->
            ShapingDecisionInfo(
                range = input.range,
                sourceText = sourceText,
                displayText = input.displayText,
                fontKey = fontKey,
                glyphCount = d.glyphCount,
                advance = d.advance,
                source = d.source,
                reason = d.reason,
                glyphsWithoutInkBounds = d.glyphsWithoutInkBounds,
                missingGlyphs = d.missingGlyphs,
                resolvedFace = d.resolvedFace,
                script = d.script,
                language = d.language,
                strategy = d.strategy,
                featureEvidence = d.featureEvidence,
                capabilityIssue = d.capabilityIssue,
            )
        }
        return ShapingResult(
            clusters = listOf(cluster),
            glyphRuns = listOf(run),
            decisions = decisions,
        )
    }
}

class RecordedEvidenceFontMetricsResolver(private val evidence: ShapingEvidence) : FontMetricsResolver {
    override fun resolve(request: FontMetricsRequest): RawFontMetrics {
        val key = request.toEvidenceKey()
        val recorded = evidence.metrics[key] ?: error(
            "No recorded font metrics evidence for $key — re-record on the JVM with " +
                "TIQIAN_RECORD_SHAPING=1 ./gradlew :engine:jvmTest --tests '*ShapingEvidenceRecorder*'",
        )
        return recorded.toRawFontMetrics()
    }
}

/** Collects (request, answer) pairs while a real platform implementation runs; rejects nondeterminism. */
class ShapingEvidenceBuilder {
    private val shaping = LinkedHashMap<ShapingEvidenceKey, RecordedShapingResult>()
    private val metrics = LinkedHashMap<MetricsEvidenceKey, RecordedFontMetrics>()

    fun record(key: ShapingEvidenceKey, result: RecordedShapingResult) {
        val existing = shaping[key]
        require(existing == null || existing == result) {
            "Nondeterministic shaping answer for $key:\nfirst: $existing\nnow: $result"
        }
        shaping[key] = result
    }

    fun record(key: MetricsEvidenceKey, result: RecordedFontMetrics) {
        val existing = metrics[key]
        require(existing == null || existing == result) {
            "Nondeterministic font metrics answer for $key:\nfirst: $existing\nnow: $result"
        }
        metrics[key] = result
    }

    fun build(meta: Map<String, String>): ShapingEvidence =
        ShapingEvidence(meta = meta, shaping = shaping.toMap(), metrics = metrics.toMap())
}

class RecordingTextShaper(
    private val delegate: TextShaper,
    private val sink: ShapingEvidenceBuilder,
) : TextShaper {
    override fun shape(input: ShapingInput): ShapingResult {
        val result = delegate.shape(input)
        val cluster = result.clusters.singleOrNull()
            ?: error("Recording expects single-cluster shaper output, got ${result.clusters.size} clusters")
        val run = result.glyphRuns.singleOrNull()
            ?: error("Recording expects single-run shaper output, got ${result.glyphRuns.size} runs")
        sink.record(
            input.toEvidenceKey(),
            RecordedShapingResult(
                clusterAdvance = cluster.advance,
                runAdvance = run.advance,
                runFeatures = run.openTypeFeatures,
                glyphs = run.glyphs.map { g ->
                    RecordedGlyph(
                        id = g.id.toLong(),
                        advance = g.advance,
                        x = g.x,
                        y = g.y,
                        bounds = g.bounds,
                        haltAdvance = g.haltAdvance,
                        haltPlacementX = g.haltPlacementX,
                    )
                },
                decisions = result.decisions.map { d ->
                    RecordedShapingDecision(
                        glyphCount = d.glyphCount,
                        advance = d.advance,
                        source = d.source,
                        reason = d.reason,
                        glyphsWithoutInkBounds = d.glyphsWithoutInkBounds,
                        missingGlyphs = d.missingGlyphs,
                        resolvedFace = d.resolvedFace,
                        script = d.script,
                        language = d.language,
                        strategy = d.strategy,
                        featureEvidence = d.featureEvidence,
                        capabilityIssue = d.capabilityIssue,
                    )
                },
            ),
        )
        return result
    }
}

class RecordingFontMetricsResolver(
    private val delegate: FontMetricsResolver,
    private val sink: ShapingEvidenceBuilder,
) : FontMetricsResolver {
    override fun resolve(request: FontMetricsRequest): RawFontMetrics {
        val result = delegate.resolve(request)
        sink.record(
            request.toEvidenceKey(),
            RecordedFontMetrics(
                ascent = result.ascent,
                descent = result.descent,
                leading = result.leading,
                source = result.source.name,
                typoAscent = result.typoAscent,
                typoDescent = result.typoDescent,
            ),
        )
        return result
    }
}
