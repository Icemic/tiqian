package org.tiqian.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EvidenceFormatTest {
    @Test
    fun encodesUnknownCapabilityAsUnsupportedInsteadOfFalse() {
        val value = linkedMapOf<String, Any?>(
            "status" to EvidenceStatus.Unsupported.wireValue,
            "reason" to "requires API 31+",
            "items" to null,
        )

        assertEquals(
            "{\"status\":\"unsupported\",\"reason\":\"requires API 31+\",\"items\":null}",
            EvidenceJson.encode(value),
        )
    }

    @Test
    fun jsonEncodingIsStableAndEscapesControlCharacters() {
        val value = linkedMapOf<String, Any?>(
            "text" to "中\n\"文\"",
            "numbers" to listOf(1, 2.5f),
            "invalidNumber" to Float.NaN,
        )

        val first = EvidenceJson.encode(value)
        assertEquals(first, EvidenceJson.encode(value))
        assertEquals(
            "{\"text\":\"中\\n\\\"文\\\"\",\"numbers\":[1,2.5],\"invalidNumber\":null}",
            first,
        )
    }

    @Test
    fun stableTokenKeepsReadablePrefixAndCollisionSuffix() {
        val token = stableToken("sans-serif / OEM")
        assertTrue(token.startsWith("sans-serif-oem-"))
        assertEquals(token, stableToken("sans-serif / OEM"))
    }

    @Test
    fun sha256UsesCanonicalLowercaseHex() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256("abc".toByteArray()),
        )
    }
}
