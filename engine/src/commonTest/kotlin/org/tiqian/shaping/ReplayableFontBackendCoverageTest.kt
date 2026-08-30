package org.tiqian.shaping

import org.tiqian.font.FontRole
import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.assertFailsWith
import org.tiqian.test.trace.assertFalse
import org.tiqian.test.trace.assertNull
import kotlin.test.assertSame
import org.tiqian.test.trace.assertTrue
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

class ReplayableFontBackendCoverageTest {
    private val testTrace = TestTraceRecorder("ReplayableFontBackendCoverageTest")


    @Test
    fun fontFaceIdRejectsBlankAndKeepsValue() {
        testTrace.section("fontFaceIdRejectsBlankAndKeepsValue")
        assertEquals("noto-cjk-1", FontFaceId("noto-cjk-1").value)
        assertEquals("noto-cjk-1", FontFaceId("noto-cjk-1").toString())
        val blank = assertFailsWith<IllegalArgumentException> { FontFaceId(" ") }
        assertTrue(blank.message!!.contains("blank"), blank.message)
        assertFailsWith<IllegalArgumentException> { FontFaceId("") }
    }

    @Test
    fun faceDescriptorDefaultsAreStable() {
        testTrace.section("faceDescriptorDefaultsAreStable")
        val descriptor = ReplayableFontFaceDescriptor(
            id = FontFaceId("face-a"),
            familyAliases = setOf("Serif"),
            roles = setOf(FontRole.CjkText),
            sourceLabel = "bundled/noto.ttf",
        )
        assertEquals(400, descriptor.weight)
        assertFalse(descriptor.italic)
        assertEquals(0, descriptor.collectionIndex)
        assertTrue(descriptor.variationAxes.isEmpty())
        assertEquals(FontFaceId("face-a"), descriptor.id)

        val varied = descriptor.copy(
            weight = 700,
            italic = true,
            collectionIndex = 2,
            variationAxes = mapOf("wght" to 700.0f),
        )
        assertEquals(700, varied.weight)
        assertTrue(varied.italic)
        assertEquals(2, varied.collectionIndex)
        assertEquals(700.0f, varied.variationAxes["wght"])
    }

    @Test
    fun faceRequestRejectsNonPositiveAndNonFiniteFontSize() {
        testTrace.section("faceRequestRejectsNonPositiveAndNonFiniteFontSize")
        val request = ReplayableFontFaceRequest(
            role = FontRole.LatinText,
            preferredFamilies = listOf("Plex"),
            fontSize = 15.0f,
            weight = 400,
            italic = false,
            locale = "zh-CN",
            selectionText = "A",
        )
        assertEquals(FontRole.LatinText, request.role)
        assertEquals(15.0f, request.fontSize)

        val error = assertFailsWith<IllegalArgumentException> {
            ReplayableFontFaceRequest(FontRole.LatinText, emptyList(), 0.0f, 400, false, "", "A")
        }
        assertTrue(error.message!!.contains("positive and finite"), error.message)
        assertFailsWith<IllegalArgumentException> {
            ReplayableFontFaceRequest(FontRole.LatinText, emptyList(), -1.0f, 400, false, "", "A")
        }
        assertFailsWith<IllegalArgumentException> {
            ReplayableFontFaceRequest(FontRole.LatinText, emptyList(), Float.NaN, 400, false, "", "A")
        }
        assertFailsWith<IllegalArgumentException> {
            ReplayableFontFaceRequest(FontRole.LatinText, emptyList(), Float.POSITIVE_INFINITY, 400, false, "", "A")
        }
    }

    @Test
    fun capabilityReportReplayFlagRequiresFacesAndNoMissingFaceIssue() {
        testTrace.section("capabilityReportReplayFlagRequiresFacesAndNoMissingFaceIssue")
        val face = ReplayableFontFaceDescriptor(
            id = FontFaceId("face-a"),
            familyAliases = setOf("Serif"),
            roles = setOf(FontRole.CjkText),
            sourceLabel = "bytes",
        )
        // No faces: nothing to replay from.
        assertFalse(
            FontBackendCapabilityReport(backend = "b", sourceKind = "k", faces = emptyList())
                .canReplayFromControlledBytes,
        )
        // A MissingControlledFontFace issue vetoes replay even with faces present.
        assertFalse(
            FontBackendCapabilityReport(
                backend = "b", sourceKind = "k", faces = listOf(face),
                issues = listOf(FontBackendCapabilityIssue("MissingControlledFontFace", "gone")),
            ).canReplayFromControlledBytes,
        )
        // Other issues do not veto replay.
        assertTrue(
            FontBackendCapabilityReport(
                backend = "b", sourceKind = "k", faces = listOf(face),
                issues = listOf(FontBackendCapabilityIssue("Other", "note")),
            ).canReplayFromControlledBytes,
        )
        // Faces with no issues replay.
        assertTrue(
            FontBackendCapabilityReport("b", "k", listOf(face)).canReplayFromControlledBytes,
        )
    }

    @Test
    fun catalogContractResolvesByRequest() {
        testTrace.section("catalogContractResolvesByRequest")
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
        assertTrue(catalog.capabilityReport.canReplayFromControlledBytes)
        val hit = catalog.resolve(
            ReplayableFontFaceRequest(
                role = FontRole.LatinText,
                preferredFamilies = listOf("Plex"),
                fontSize = 12.0f,
                weight = 400,
                italic = false,
                locale = "zh-CN",
                selectionText = "A",
            ),
        )
        assertEquals(FontFaceId("face-latin"), hit?.id)
        // A request whose family matches no catalogued alias resolves to null.
        val miss = catalog.resolve(
            ReplayableFontFaceRequest(
                role = FontRole.LatinText,
                preferredFamilies = listOf("Missing"),
                fontSize = 12.0f,
                weight = 400,
                italic = false,
                locale = "zh-CN",
                selectionText = "A",
            ),
        )
        assertNull(miss)
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
