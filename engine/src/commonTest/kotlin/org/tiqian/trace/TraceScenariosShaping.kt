package org.tiqian.trace

import org.tiqian.font.FontRole
import org.tiqian.shaping.FontBackendCapabilityIssue
import org.tiqian.shaping.FontBackendCapabilityReport
import org.tiqian.shaping.FontFaceId
import org.tiqian.shaping.ReplayableFontCatalog
import org.tiqian.shaping.ReplayableFontFaceDescriptor
import org.tiqian.shaping.ReplayableFontFaceRequest
import org.tiqian.test.trace.TraceFormat
import org.tiqian.test.trace.TraceRecorder

/**
 * Process-trace scenario for the shaping cluster: value validation of
 * FontFaceId and ReplayableFontFaceRequest, descriptor defaults, the
 * replay capability flag, and catalog resolution. Validation branches are
 * recorded as rejected=true/false; exception messages are not recorded
 * because their text is not guaranteed to match across targets.
 */
internal object ShapingTraceScenarios {

    val all: List<TraceScenario> = listOf(
        replayableContracts(),
    )

    private fun header(id: String): String = "scenario: $id\n"

    private fun rejected(action: () -> Unit): Boolean =
        try {
            action()
            false
        } catch (e: IllegalArgumentException) {
            true
        }

    private fun replayableContracts(): TraceScenario = TraceScenario(
        id = "shaping.replayable-contracts",
        notes = "FontFaceId and request guards, descriptor defaults, replay capability flag, catalog resolve",
    ) {
        val t = TraceRecorder()

        t.event("face-id", "input" to "noto-cjk-1", "value" to FontFaceId("noto-cjk-1").value)
        t.event("face-id", "input" to "blank", "rejected" to rejected { FontFaceId(" ") })
        t.event("face-id", "input" to "empty", "rejected" to rejected { FontFaceId("") })

        val descriptor = ReplayableFontFaceDescriptor(
            id = FontFaceId("face-a"),
            familyAliases = setOf("Serif"),
            roles = setOf(FontRole.CjkText),
            sourceLabel = "bundled/noto.ttf",
        )
        t.event(
            "descriptor-defaults", "face" to "face-a",
            "weight" to TraceFormat.i(descriptor.weight), "italic" to descriptor.italic,
            "collection-index" to TraceFormat.i(descriptor.collectionIndex),
            "axes-count" to TraceFormat.i(descriptor.variationAxes.size),
        )
        val varied = descriptor.copy(
            weight = 700,
            italic = true,
            collectionIndex = 2,
            variationAxes = mapOf("wght" to 700.0f),
        )
        t.event(
            "descriptor-varied", "face" to "face-a",
            "weight" to TraceFormat.i(varied.weight), "italic" to varied.italic,
            "collection-index" to TraceFormat.i(varied.collectionIndex),
            "wght" to varied.variationAxes["wght"],
        )

        val request = ReplayableFontFaceRequest(
            role = FontRole.LatinText,
            preferredFamilies = listOf("Plex"),
            fontSize = 15.0f,
            weight = 400,
            italic = false,
            locale = "zh-CN",
            selectionText = "A",
        )
        t.event(
            "request", "case" to "valid",
            "role" to request.role.name, "font-size" to request.fontSize,
            "weight" to TraceFormat.i(request.weight), "italic" to request.italic,
        )
        t.event("request", "case" to "zero-size", "rejected" to rejected {
            ReplayableFontFaceRequest(FontRole.LatinText, emptyList(), 0.0f, 400, false, "", "A")
        })
        t.event("request", "case" to "negative-size", "rejected" to rejected {
            ReplayableFontFaceRequest(FontRole.LatinText, emptyList(), -1.0f, 400, false, "", "A")
        })
        t.event("request", "case" to "nan-size", "rejected" to rejected {
            ReplayableFontFaceRequest(FontRole.LatinText, emptyList(), Float.NaN, 400, false, "", "A")
        })
        t.event("request", "case" to "infinite-size", "rejected" to rejected {
            ReplayableFontFaceRequest(FontRole.LatinText, emptyList(), Float.POSITIVE_INFINITY, 400, false, "", "A")
        })

        val face = ReplayableFontFaceDescriptor(
            id = FontFaceId("face-a"),
            familyAliases = setOf("Serif"),
            roles = setOf(FontRole.CjkText),
            sourceLabel = "bytes",
        )
        t.event(
            "replay-flag", "case" to "no-faces",
            "replayable" to FontBackendCapabilityReport(backend = "b", sourceKind = "k", faces = emptyList())
                .canReplayFromControlledBytes,
        )
        t.event(
            "replay-flag", "case" to "missing-face-issue",
            "replayable" to FontBackendCapabilityReport(
                backend = "b", sourceKind = "k", faces = listOf(face),
                issues = listOf(FontBackendCapabilityIssue("MissingControlledFontFace", "gone")),
            ).canReplayFromControlledBytes,
        )
        t.event(
            "replay-flag", "case" to "other-issue",
            "replayable" to FontBackendCapabilityReport(
                backend = "b", sourceKind = "k", faces = listOf(face),
                issues = listOf(FontBackendCapabilityIssue("Other", "note")),
            ).canReplayFromControlledBytes,
        )
        t.event(
            "replay-flag", "case" to "clean",
            "replayable" to FontBackendCapabilityReport("b", "k", listOf(face)).canReplayFromControlledBytes,
        )

        val cjkFace = ReplayableFontFaceDescriptor(
            id = FontFaceId("face-cjk"),
            familyAliases = setOf("Noto Serif CJK"),
            roles = setOf(FontRole.CjkText),
            sourceLabel = "bytes",
        )
        val latinFace = ReplayableFontFaceDescriptor(
            id = FontFaceId("face-latin"),
            familyAliases = setOf("Plex"),
            roles = setOf(FontRole.LatinText),
            sourceLabel = "bytes",
        )
        val catalog = object : ReplayableFontCatalog {
            override val faces = listOf(cjkFace, latinFace)
            override val capabilityReport =
                FontBackendCapabilityReport(backend = "test", sourceKind = "bytes", faces = faces)
            override fun resolve(request: ReplayableFontFaceRequest): ReplayableFontFaceDescriptor? =
                faces.firstOrNull { face ->
                    request.role in face.roles && request.preferredFamilies.any { it in face.familyAliases }
                }
        }
        t.event("catalog", "case" to "capability", "replayable" to catalog.capabilityReport.canReplayFromControlledBytes)
        t.event(
            "catalog", "case" to "hit",
            "face" to catalog.resolve(
                ReplayableFontFaceRequest(
                    role = FontRole.LatinText,
                    preferredFamilies = listOf("Plex"),
                    fontSize = 12.0f,
                    weight = 400,
                    italic = false,
                    locale = "zh-CN",
                    selectionText = "A",
                ),
            )?.id?.value,
        )
        t.event(
            "catalog", "case" to "miss",
            "face" to catalog.resolve(
                ReplayableFontFaceRequest(
                    role = FontRole.LatinText,
                    preferredFamilies = listOf("Missing"),
                    fontSize = 12.0f,
                    weight = 400,
                    italic = false,
                    locale = "zh-CN",
                    selectionText = "A",
                ),
            )?.id?.value,
        )
        header("shaping.replayable-contracts") + t.text()
    }
}
