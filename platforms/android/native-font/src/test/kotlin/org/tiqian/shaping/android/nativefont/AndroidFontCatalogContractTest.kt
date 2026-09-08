package org.tiqian.shaping.android.nativefont

import org.tiqian.font.FontRole
import org.tiqian.shaping.FontBackendCapabilityIssue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
    fun styleMatchedFaceDecidesCoverage() {
        val familyA = listOf(
            Candidate("a-regular", setOf("a"), 400, covers = false),
            Candidate("a-bold", setOf("a"), 700, covers = true),
        )
        val familyB = listOf(Candidate("b-regular", setOf("b"), 400, covers = true))

        val atRegular = select(listOf(familyA, familyB), requestedWeight = 400)
        assertEquals(familyB.single(), atRegular?.face)
        assertEquals(1, atRegular?.familyIndex)

        val atBold = select(listOf(familyA, familyB), requestedWeight = 700)
        assertEquals(familyA[1], atBold?.face)
        assertEquals(0, atBold?.familyIndex)

        // Family A's matched weight-400 face does not cover; its bold face is not consulted.
        assertNull(select(listOf(familyA), requestedWeight = 400))
    }

    @Test
    fun italicMatchPrecedesWeight() {
        val family = listOf(
            Candidate("regular", setOf("f"), 400, covers = true, italic = false),
            Candidate("italic", setOf("f"), 400, covers = true, italic = true),
            Candidate("bold", setOf("f"), 700, covers = true, italic = false),
        )
        assertEquals(
            family[1],
            select(listOf(family), requestedWeight = 400, requestedItalic = true)?.face,
        )
        assertEquals(
            family[1],
            select(listOf(family), requestedWeight = 700, requestedItalic = true)?.face,
            "italic precedes weight: the italic 400 face wins over the upright bold",
        )
        assertEquals(
            family[2],
            select(listOf(family), requestedWeight = 700, requestedItalic = false)?.face,
        )
    }

    @Test
    fun cssWeightSearchOrderFollowsCssFontsLevel4() {
        assertEquals(500, cssWeightSearchOrder(400, listOf(300, 500)))
        assertEquals(300, cssWeightSearchOrder(400, listOf(300, 700)))
        assertEquals(200, cssWeightSearchOrder(300, listOf(200, 600)))
        assertEquals(400, cssWeightSearchOrder(300, listOf(400, 600)))
        assertEquals(900, cssWeightSearchOrder(600, listOf(400, 900)))
        assertEquals(500, cssWeightSearchOrder(600, listOf(400, 500)))
        assertEquals(500, cssWeightSearchOrder(500, listOf(300, 500, 700)))
    }

    @Test
    fun honoursExplicitFamilyPreferenceOverCoveringPrimary() {
        val primary = Candidate("primary", setOf("primary", "sans-serif"), 400, covers = true)
        val fallback = Candidate("fallback", setOf("fallback", "sans-serif"), 400, covers = true)
        val preferred = select(
            families = listOf(listOf(primary), listOf(fallback)),
            requestedWeight = 400,
            preferredFamilies = listOf("fallback"),
        )
        assertEquals(fallback, preferred?.face)
        assertTrue(preferred?.exactFamily == true)
    }

    @Test
    fun platformDefaultMarkerIsAllowedInChainsButNotAsAFamily() {
        val marker = AndroidFontCatalog.PLATFORM_DEFAULT_FAMILY
        val catalog = AndroidFontCatalog(
            faceSpecs = listOf(face("latin", weight = 400, FontRole.LatinText)),
            fallbackChains = mapOf(
                FontRole.CjkText to listOf(marker),
                FontRole.LatinText to listOf("latin", marker),
            ),
        )
        assertTrue(catalog.referencesPlatformDefault)

        assertFailsWith<IllegalArgumentException> {
            AndroidFontCatalog(
                faceSpecs = listOf(face(marker, weight = 400, FontRole.LatinText)),
                fallbackChains = mapOf(FontRole.LatinText to listOf(marker)),
            )
        }

        val withoutMarker = AndroidFontCatalog.host(listOf(face("latin", weight = 400, FontRole.LatinText)))
        assertFalse(withoutMarker.referencesPlatformDefault)
    }

    @Test
    fun expandPlatformDefaultSplicesSystemFamiliesAndNarrowsRoles() {
        val marker = AndroidFontCatalog.PLATFORM_DEFAULT_FAMILY
        val host = AndroidFontCatalog(
            faceSpecs = listOf(face("latin", weight = 400, FontRole.LatinText, FontRole.Symbol)),
            fallbackChains = mapOf(
                FontRole.CjkText to listOf(marker),
                FontRole.CjkPunctuation to listOf(marker),
                FontRole.LatinText to listOf("latin", marker),
                FontRole.Symbol to listOf("latin"),
                FontRole.Emoji to listOf(marker),
            ),
        )
        val system = AndroidFontCatalog(
            faceSpecs = listOf(
                face("cjk", weight = 400, FontRole.CjkText, FontRole.CjkPunctuation, FontRole.Symbol),
                face("sans", weight = 400, FontRole.CjkPunctuation, FontRole.LatinText, FontRole.Symbol),
            ),
            fallbackChains = mapOf(
                FontRole.CjkText to listOf("cjk"),
                FontRole.CjkPunctuation to listOf("cjk", "sans"),
                FontRole.LatinText to listOf("sans"),
                FontRole.Symbol to listOf("sans", "cjk"),
            ),
            sourceKind = "sys",
            declaredIssues = listOf(FontBackendCapabilityIssue("X", "y")),
        )

        val expanded = host.expandPlatformDefault(system)

        assertEquals(listOf("platform-default:cjk"), expanded.fallbackChains.getValue(FontRole.CjkText))
        assertEquals(
            listOf("platform-default:cjk", "platform-default:sans"),
            expanded.fallbackChains.getValue(FontRole.CjkPunctuation),
        )
        assertEquals(listOf("latin", "platform-default:sans"), expanded.fallbackChains.getValue(FontRole.LatinText))
        assertEquals(listOf("latin"), expanded.fallbackChains.getValue(FontRole.Symbol))
        assertFalse(expanded.fallbackChains.containsKey(FontRole.Emoji), "system had no Emoji chain")

        assertEquals(
            setOf(FontRole.CjkText, FontRole.CjkPunctuation),
            expanded.faceSpecs.first { it.familyKey == "platform-default:cjk" }.roles,
        )
        assertEquals(
            setOf(FontRole.CjkPunctuation, FontRole.LatinText),
            expanded.faceSpecs.first { it.familyKey == "platform-default:sans" }.roles,
        )
        assertTrue(expanded.sourceKind.contains(host.sourceKind), expanded.sourceKind)
        assertTrue(expanded.sourceKind.contains("sys"), expanded.sourceKind)
        assertTrue(expanded.declaredIssues.any { it.code == "X" }, expanded.declaredIssues.toString())
    }

    @Test
    fun catalogWithoutFacesIsAllowedWhenEveryChainDelegatesToThePlatform() {
        val marker = AndroidFontCatalog.PLATFORM_DEFAULT_FAMILY
        val catalog = AndroidFontCatalog.host(
            faceSpecs = emptyList(),
            fallbackChains = mapOf(FontRole.CjkText to listOf(marker), FontRole.LatinText to listOf(marker)),
        )
        assertTrue(catalog.referencesPlatformDefault)
        assertTrue(catalog.faceSpecs.isEmpty())
        assertFailsWith<IllegalArgumentException> {
            AndroidFontCatalog.host(faceSpecs = emptyList(), fallbackChains = emptyMap())
        }
    }

    @Test
    fun requiresSyntheticBoldFollowsMinikin() {
        assertTrue(requiresSyntheticBold(700, 400))
        assertTrue(requiresSyntheticBold(700, 500))
        assertTrue(requiresSyntheticBold(600, 400))
        assertFalse(requiresSyntheticBold(600, 500))
        assertFalse(requiresSyntheticBold(500, 300))
        assertFalse(requiresSyntheticBold(700, 700))
        assertTrue(requiresSyntheticBold(900, 700))
    }

    private fun select(
        families: List<List<Candidate>>,
        requestedWeight: Int,
        requestedItalic: Boolean = false,
        preferredFamilies: List<String> = emptyList(),
    ): OrderedFamilySelection<Candidate>? = selectOrderedFamilyFace(
        families = families,
        preferredFamilies = preferredFamilies,
        requestedWeight = requestedWeight,
        requestedItalic = requestedItalic,
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
