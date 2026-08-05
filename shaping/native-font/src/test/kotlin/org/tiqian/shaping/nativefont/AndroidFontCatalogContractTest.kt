package org.tiqian.shaping.nativefont

import org.tiqian.font.FontRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AndroidFontCatalogContractTest {
    @Test
    fun defaultChainsPreserveFirstFamilyOccurrenceAndGroupStyles() {
        val catalog = AndroidFontCatalog.host(
            listOf(
                face("cjk", weight = 400, FontRole.CjkText, FontRole.CjkPunctuation),
                face("cjk", weight = 700, FontRole.CjkText, FontRole.CjkPunctuation),
                face("latin", weight = 400, FontRole.CjkPunctuation, FontRole.LatinText),
                face("latin", weight = 700, FontRole.CjkPunctuation, FontRole.LatinText),
            ),
        )

        assertEquals(listOf("cjk"), catalog.fallbackChains.getValue(FontRole.CjkText))
        assertEquals(listOf("cjk", "latin"), catalog.fallbackChains.getValue(FontRole.CjkPunctuation))
        assertEquals(listOf("latin"), catalog.fallbackChains.getValue(FontRole.LatinText))
    }

    @Test
    fun explicitChainCanReverseFamiliesWithoutReorderingStyleFaces() {
        val faces = listOf(
            face("cjk", weight = 400, FontRole.CjkPunctuation),
            face("cjk", weight = 700, FontRole.CjkPunctuation),
            face("latin", weight = 400, FontRole.CjkPunctuation),
        )
        val catalog = AndroidFontCatalog.host(
            faceSpecs = faces,
            fallbackChains = mapOf(FontRole.CjkPunctuation to listOf("latin", "cjk")),
        )

        assertEquals(listOf("latin", "cjk"), catalog.fallbackChains.getValue(FontRole.CjkPunctuation))
        assertEquals(listOf(400, 700), catalog.faceSpecs.filter { it.familyKey == "cjk" }.map { it.weight })
    }

    @Test
    fun chainRejectsUnknownRepeatedAndUnlistedFamilies() {
        val spec = face("cjk", weight = 400, FontRole.CjkText)
        assertFailsWith<IllegalArgumentException> {
            AndroidFontCatalog.host(
                faceSpecs = listOf(spec),
                fallbackChains = mapOf(FontRole.CjkText to listOf("missing")),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AndroidFontCatalog.host(
                faceSpecs = listOf(spec),
                fallbackChains = mapOf(FontRole.CjkText to listOf("cjk", "cjk")),
            )
        }
        val error = assertFailsWith<IllegalArgumentException> {
            AndroidFontCatalog.host(
                faceSpecs = listOf(
                    spec,
                    face("unused", weight = 400, FontRole.CjkText),
                ),
                fallbackChains = mapOf(FontRole.CjkText to listOf("cjk")),
            )
        }
        assertTrue(error.message.orEmpty().contains("absent from that fallback chain"))
    }

    @Test
    fun selectionMatchesStyleInsidePrimaryFamilyBeforeFallback() {
        val primaryRegular = Candidate("primary-regular", setOf("sans-serif"), 400, covers = true)
        val primaryBold = Candidate("primary-bold", setOf("sans-serif"), 700, covers = true)
        val fallbackBold = Candidate("fallback-bold", setOf("fallback"), 700, covers = true)

        val selection = select(
            families = listOf(listOf(primaryRegular, primaryBold), listOf(fallbackBold)),
            requestedWeight = 700,
        )

        assertEquals(primaryBold, selection?.face)
        assertEquals(0, selection?.familyIndex)
    }

    @Test
    fun selectionFallsThroughOnlyWhenFamilyDoesNotCoverAndHonoursExplicitPreference() {
        val primary = Candidate("primary", setOf("primary", "sans-serif"), 400, covers = false)
        val fallback = Candidate("fallback", setOf("fallback", "sans-serif"), 400, covers = true)
        assertEquals(
            fallback,
            select(listOf(listOf(primary), listOf(fallback)), requestedWeight = 400)?.face,
        )

        val coveringPrimary = primary.copy(covers = true)
        val preferred = select(
            families = listOf(listOf(coveringPrimary), listOf(fallback)),
            requestedWeight = 400,
            preferredFamilies = listOf("fallback"),
        )
        assertEquals(fallback, preferred?.face)
        assertTrue(preferred?.exactFamily == true)
    }

    private fun select(
        families: List<List<Candidate>>,
        requestedWeight: Int,
        preferredFamilies: List<String> = emptyList(),
    ): OrderedFamilySelection<Candidate>? = selectOrderedFamilyFace(
        families = families,
        preferredFamilies = preferredFamilies,
        requestedWeight = requestedWeight,
        requestedItalic = false,
        aliases = Candidate::aliases,
        covers = Candidate::covers,
        weight = Candidate::weight,
        italic = Candidate::italic,
        stableId = Candidate::id,
    )

    private data class Candidate(
        val id: String,
        val aliases: Set<String>,
        val weight: Int,
        val covers: Boolean,
        val italic: Boolean = false,
    )

    private fun face(
        familyKey: String,
        weight: Int,
        vararg roles: FontRole,
    ): AndroidFontFaceSpec = AndroidFontFaceSpec(
        source = AndroidFontSource.bytes(byteArrayOf(0), "$familyKey-$weight"),
        familyKey = familyKey,
        familyAliases = setOf(familyKey, "sans-serif"),
        roles = roles.toSet(),
        weight = weight,
    )
}
