package org.tiqian.layout

import org.tiqian.core.Cluster
import org.tiqian.core.InlineObjectPreferredStretch
import org.tiqian.core.InlineObjectPreferredStretchKind
import org.tiqian.core.TextRange
import org.tiqian.core.UnicodeEastAsianSpacing
import org.tiqian.font.FontRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit-level checks for the CLREQ stretch tiers, focused on the 中西间距
 * (tier ②) and the `TypedSinoWesternSpaceStretches` fix: an author-typed
 * U+0020 between an ideograph and a Latin word is the sino-western gap and
 * must stretch in tier ②, equally with a virtual CJK↔Latin boundary —
 * otherwise it falls through every tier and the line stretches unevenly.
 */
class JustifierTest {

    private val em = 16f
    private fun cjk(at: Int) = Cluster(TextRange(at, at + 1), "中", fontKey = "cjk", advance = em)
    private fun space(at: Int) = Cluster(TextRange(at, at + 1), " ", fontKey = "latin", advance = 0.25f * em)
    private fun latin(at: Int, w: Float) = Cluster(TextRange(at, at + 2), "Hi", fontKey = "latin", advance = w)
    private fun slashLatin(at: Int, w: Float) = Cluster(TextRange(at, at + 3), "/Hi", fontKey = "latin", advance = w)
    private fun punctuation(at: Int, text: String = "（") =
        Cluster(TextRange(at, at + 1), text, fontKey = "cjk", advance = em)
    private fun inlineObject(at: Int, text: String) =
        Cluster(TextRange(at, at + text.length), text, displayText = "", fontKey = "inline-object", advance = 2f * em)
    private fun spacingEdges(clusters: List<Cluster>) =
        clusters.map { UnicodeEastAsianSpacing.resolvedEdges(it.text, "zh-Hans") }

    @Test
    fun westernDominantLineDoesNotStretchAroundCjkPunctuation() {
        // Rust（Winio）、Rust — the visual line contains Chinese punctuation,
        // but no CJK body text. Tier ③ must not manufacture wide spaces around
        // the brackets merely to fill the measure.
        val clusters = listOf(
            latin(0, 3f * em),
            punctuation(2),
            latin(3, 3f * em),
            punctuation(5, "）"),
            punctuation(6, "、"),
            latin(7, 3f * em),
        )
        val roles = listOf(
            FontRole.LatinText,
            FontRole.CjkPunctuation,
            FontRole.LatinText,
            FontRole.CjkPunctuation,
            FontRole.CjkPunctuation,
            FontRole.LatinText,
        )
        val natural = clusters.sumOf { it.advance.toDouble() }.toFloat()
        val plan = Justifier().justify(
            adjustedClusters = clusters,
            clusterRoles = roles,
            eastAsianSpacingEdges = spacingEdges(clusters),
            lineClusterRange = clusters.indices,
            maxWidth = natural + 2f * em,
            fontSize = em,
            skip = false,
            cjkLatinSpaceBaseEm = 0.25f,
            cjkLatinSpaceMaxEm = 0.5f,
        )

        assertTrue(plan.allocations.none { it.kind == GlueKind.CjkInterChar })
        assertEquals(2f * em, plan.unfilledDeficit, 0.001f)
        assertEquals("WesternDominantLineNaturalSpacing", plan.fallbackReason)
    }

    @Test
    fun explicitInlineObjectBoundariesShareUniformStretchOnFormulaOnlyLine() {
        val clusters = listOf(
            inlineObject(0, "a+"),
            inlineObject(2, "b="),
            inlineObject(4, "c"),
        )
        val roles = List(clusters.size) { FontRole.Unknown }
        val natural = clusters.sumOf { it.advance.toDouble() }.toFloat()

        val plan = Justifier().justify(
            adjustedClusters = clusters,
            clusterRoles = roles,
            eastAsianSpacingEdges = spacingEdges(clusters),
            lineClusterRange = clusters.indices,
            maxWidth = natural + em,
            fontSize = em,
            skip = false,
            cjkLatinSpaceBaseEm = 0.25f,
            cjkLatinSpaceMaxEm = 0.5f,
            uniformInlineObjectBoundaryAfterClusters = setOf(0, 1),
        )

        assertEquals(0f, plan.unfilledDeficit, 0.001f)
        assertEquals(setOf(0, 1), plan.allocations.map { it.targetClusterIndex }.toSet())
        assertTrue(plan.allocations.all { it.kind == GlueKind.InlineObjectBoundary })
        plan.allocations.forEach { assertEquals(0.5f * em, it.delta, 0.001f) }
    }

    @Test
    fun formulaBoundariesStretchPunctuationThenRelationsThenBinaryOperators() {
        val clusters = listOf("a", ",", "b", "=", "c", "+", "d")
            .mapIndexed { index, text -> inlineObject(index, text) }
        val roles = List(clusters.size) { FontRole.Unknown }
        val natural = clusters.sumOf { it.advance.toDouble() }.toFloat()
        val preferred = mapOf(
            1 to InlineObjectPreferredStretch(InlineObjectPreferredStretchKind.PunctuationTrailing, 1f, 8f),
            2 to InlineObjectPreferredStretch(InlineObjectPreferredStretchKind.Relation, 2f, 8f),
            3 to InlineObjectPreferredStretch(InlineObjectPreferredStretchKind.Relation, 2f, 8f),
            4 to InlineObjectPreferredStretch(InlineObjectPreferredStretchKind.BinaryOperator, 3f, 8f),
            5 to InlineObjectPreferredStretch(InlineObjectPreferredStretchKind.BinaryOperator, 3f, 8f),
        )

        val preferredOnly = Justifier().justify(
            adjustedClusters = clusters,
            clusterRoles = roles,
            eastAsianSpacingEdges = spacingEdges(clusters),
            lineClusterRange = clusters.indices,
            maxWidth = natural + 24f,
            fontSize = em,
            skip = false,
            cjkLatinSpaceBaseEm = 0.25f,
            cjkLatinSpaceMaxEm = 0.5f,
            preferredInlineObjectBoundaryAfterClusters = preferred,
        )

        assertEquals(
            listOf(
                GlueKind.InlineObjectPunctuationTrailing,
                GlueKind.InlineObjectRelation,
                GlueKind.InlineObjectRelation,
                GlueKind.InlineObjectBinaryOperator,
                GlueKind.InlineObjectBinaryOperator,
            ),
            preferredOnly.allocations.map { it.kind },
        )
        assertEquals(listOf(7f, 6f, 6f, 2.5f, 2.5f), preferredOnly.allocations.map { it.delta })
        preferredOnly.allocations.take(3).forEach { allocation ->
            val boundary = preferred.getValue(allocation.targetClusterIndex)
            assertEquals(8f, boundary.naturalWidth + allocation.delta, 0.001f)
        }
        assertEquals(
            preferredOnly.allocations[1].delta,
            preferredOnly.allocations[2].delta,
            0.001f,
            "both relation sides must stretch by exactly the same amount",
        )
        assertEquals(0f, preferredOnly.unfilledDeficit)

        val withFinalUniform = Justifier().justify(
            adjustedClusters = clusters,
            clusterRoles = roles,
            eastAsianSpacingEdges = spacingEdges(clusters),
            lineClusterRange = clusters.indices,
            maxWidth = natural + 34f,
            fontSize = em,
            skip = false,
            cjkLatinSpaceBaseEm = 0.25f,
            cjkLatinSpaceMaxEm = 0.5f,
            preferredInlineObjectBoundaryAfterClusters = preferred,
            uniformInlineObjectBoundaryAfterClusters = preferred.keys,
        )
        assertEquals(29f, withFinalUniform.allocations.take(5).sumOf { it.delta.toDouble() }.toFloat())
        val uniform = withFinalUniform.allocations.filter { it.kind == GlueKind.InlineObjectBoundary }
        assertEquals(preferred.keys, uniform.map { it.targetClusterIndex }.toSet())
        uniform.forEach { assertEquals(1f, it.delta, 0.001f) }
        val finalWidths = preferred.mapValues { (clusterIndex, boundary) ->
            boundary.naturalWidth + withFinalUniform.allocations
                .filter { it.targetClusterIndex == clusterIndex }
                .sumOf { it.delta.toDouble() }
                .toFloat()
        }
        finalWidths.values.forEach { assertEquals(9f, it, 0.001f) }
        assertEquals(0f, withFinalUniform.unfilledDeficit)
    }

    @Test
    fun mixedCjkLineStillStretchesPunctuationWesternBoundary() {
        // Once the visual line contains CJK body text it remains a mixed CJK
        // line: the established uniform tier-③ treatment still includes the
        // Western↔punctuation boundary.
        val clusters = listOf(latin(0, 2f * em), punctuation(2), cjk(3))
        val roles = listOf(FontRole.LatinText, FontRole.CjkPunctuation, FontRole.CjkText)
        val natural = clusters.sumOf { it.advance.toDouble() }.toFloat()
        val plan = Justifier().justify(
            adjustedClusters = clusters,
            clusterRoles = roles,
            eastAsianSpacingEdges = spacingEdges(clusters),
            lineClusterRange = clusters.indices,
            maxWidth = natural + 0.5f * em,
            fontSize = em,
            skip = false,
            cjkLatinSpaceBaseEm = 0.25f,
            cjkLatinSpaceMaxEm = 0.5f,
        )

        assertTrue(
            plan.allocations.any {
                it.kind == GlueKind.CjkInterChar && it.targetClusterIndex == 0
            },
            "mixed CJK lines retain punctuation-western tier-3 tracking",
        )
        assertEquals(0f, plan.unfilledDeficit)
        assertEquals(null, plan.fallbackReason)
    }

    @Test
    fun typedSinoWesternSpaceStretchesInTierTwo() {
        // 中 ⎵ Hi  — one ideograph, a typed space (0.25em), a Latin word.
        val clusters = listOf(cjk(0), space(1), latin(2, 2f * em))
        val roles = listOf(FontRole.CjkText, FontRole.LatinText, FontRole.LatinText)
        val natural = clusters.sumOf { it.advance.toDouble() }.toFloat() // 16+4+32 = 52
        val plan = Justifier().justify(
            adjustedClusters = clusters,
            clusterRoles = roles,
            eastAsianSpacingEdges = spacingEdges(clusters),
            lineClusterRange = clusters.indices,
            maxWidth = natural + 0.2f * em, // small deficit, within the gap's headroom
            fontSize = em,
            skip = false,
            cjkLatinSpaceBaseEm = 0.25f,
            cjkLatinSpaceMaxEm = 0.5f,
        )

        assertEquals(0f, plan.unfilledDeficit)
        val alloc = plan.allocations.single()
        // …landed on the typed space (index 1) as a CjkLatinSpace stretch,
        // not on a boundary and not unfilled.
        assertEquals(1, alloc.targetClusterIndex)
        assertEquals(GlueKind.CjkLatinSpace, alloc.kind)
        assertEquals(0.2f * em, alloc.delta, 0.001f)
    }

    @Test
    fun typedSinoWesternSpaceIsCappedAtHalfEm() {
        // A huge deficit: the typed space stretches only to 0.5em (+0.25em over
        // its 0.25em base); the rest falls to the CJK inter-char tier.
        val clusters = listOf(cjk(0), space(1), latin(2, 2f * em), cjk(3), cjk(4))
        val roles = listOf(
            FontRole.CjkText, FontRole.LatinText, FontRole.LatinText,
            FontRole.CjkText, FontRole.CjkText,
        )
        val natural = clusters.sumOf { it.advance.toDouble() }.toFloat()
        val plan = Justifier().justify(
            adjustedClusters = clusters,
            clusterRoles = roles,
            eastAsianSpacingEdges = spacingEdges(clusters),
            lineClusterRange = clusters.indices,
            maxWidth = natural + 2f * em,
            fontSize = em,
            skip = false,
            cjkLatinSpaceBaseEm = 0.25f,
            cjkLatinSpaceMaxEm = 0.5f,
        )

        // 两个中西间距先各自拉到 0.5em：作者输入的空格在 index 1，Hi↔中
        // 在 index 2。剩余宽度再平均加到这两个间距和末尾汉字间距上。
        val sino = plan.allocations.filter { it.kind == GlueKind.CjkLatinSpace }
        assertEquals(setOf(1, 2), sino.map { it.targetClusterIndex }.toSet())
        sino.forEach { assertEquals(0.25f * em, it.delta, 0.001f) }
        val uniform = plan.allocations.filter { it.kind == GlueKind.CjkInterChar }
        assertEquals(3, uniform.size)
        assertEquals(setOf(1, 2, 3), uniform.map { it.targetClusterIndex }.toSet())
        uniform.forEach { assertEquals(0.5f * em, it.delta, 0.001f) }
        assertEquals(0f, plan.unfilledDeficit)
    }

    @Test
    fun finalUniformSpacingIncludesWordAndSinoWesternGapsOnceEach() {
        // 中Hi there中中: 先把词间空格和两处中西间距各拉到上限，随后把
        // 剩余宽度平均加到四个位置。原有空格只占一个位置，不按左右两边重复算。
        val clusters = listOf(
            cjk(0),
            latin(1, 2f * em),
            space(3),
            latin(4, 2f * em),
            cjk(6),
            cjk(7),
        )
        val roles = listOf(
            FontRole.CjkText,
            FontRole.LatinText,
            FontRole.LatinText,
            FontRole.LatinText,
            FontRole.CjkText,
            FontRole.CjkText,
        )
        val natural = clusters.sumOf { it.advance.toDouble() }.toFloat()
        val plan = Justifier().justify(
            adjustedClusters = clusters,
            clusterRoles = roles,
            eastAsianSpacingEdges = spacingEdges(clusters),
            lineClusterRange = clusters.indices,
            maxWidth = natural + 2.25f * em,
            fontSize = em,
            skip = false,
            cjkLatinSpaceBaseEm = 0.25f,
            cjkLatinSpaceMaxEm = 0.5f,
        )

        val word = plan.allocations.filter { it.kind == GlueKind.WordSpace }
        assertEquals(listOf(2), word.map { it.targetClusterIndex })
        assertEquals(0.25f * em, word.single().delta, 0.001f)

        val sino = plan.allocations.filter { it.kind == GlueKind.CjkLatinSpace }
        assertEquals(listOf(0, 3), sino.map { it.targetClusterIndex })
        sino.forEach { assertEquals(0.25f * em, it.delta, 0.001f) }

        val uniform = plan.allocations.filter { it.kind == GlueKind.CjkInterChar }
        assertEquals(listOf(0, 3, 4, 2), uniform.map { it.targetClusterIndex })
        uniform.forEach { assertEquals(0.375f * em, it.delta, 0.001f) }
        assertEquals(0f, plan.unfilledDeficit)
    }

    @Test
    fun inseparableNumberSymbolBoundaryNeverStretches() {
        // CLREQ 明令禁止拉开符号分离禁则里的字间距。这里把 50|% 的边界
        // 关掉；行内其他合法间距仍可继续平均加宽。
        val clusters = listOf(
            cjk(0),
            latin(1, 2f * em),
            punctuation(3, "%"),
            cjk(4),
            cjk(5),
        )
        val roles = listOf(
            FontRole.CjkText,
            FontRole.LatinText,
            FontRole.CjkPunctuation,
            FontRole.CjkText,
            FontRole.CjkText,
        )
        val natural = clusters.sumOf { it.advance.toDouble() }.toFloat()
        val plan = Justifier().justify(
            adjustedClusters = clusters,
            clusterRoles = roles,
            eastAsianSpacingEdges = spacingEdges(clusters),
            lineClusterRange = clusters.indices,
            maxWidth = natural + em,
            fontSize = em,
            skip = false,
            cjkLatinSpaceBaseEm = 0.25f,
            cjkLatinSpaceMaxEm = 0.5f,
            noStretchBoundaryAfterClusters = setOf(1),
        )

        assertTrue(plan.allocations.isNotEmpty())
        assertTrue(
            plan.allocations.none {
                it.targetClusterIndex == 1 &&
                    (it.kind == GlueKind.CjkLatinSpace || it.kind == GlueKind.CjkInterChar)
            },
            "50|% must stay closed: ${plan.allocations}",
        )
        assertEquals(0f, plan.unfilledDeficit)
    }

    @Test
    fun fixedSinoWesternGapDoesNotJoinFinalUniformSpacing() {
        // 风格明确要求中西间距固定时，两个中西边界既不走优先拉伸，
        // 也不参加最后均分；余量只落在末尾汉字间距上。
        val clusters = listOf(cjk(0), latin(1, 2f * em), cjk(3), cjk(4))
        val roles = listOf(
            FontRole.CjkText,
            FontRole.LatinText,
            FontRole.CjkText,
            FontRole.CjkText,
        )
        val natural = clusters.sumOf { it.advance.toDouble() }.toFloat()
        val plan = Justifier().justify(
            adjustedClusters = clusters,
            clusterRoles = roles,
            eastAsianSpacingEdges = spacingEdges(clusters),
            lineClusterRange = clusters.indices,
            maxWidth = natural + em,
            fontSize = em,
            skip = false,
            allowSinoWesternGapStretch = false,
            cjkLatinSpaceBaseEm = 0.25f,
            cjkLatinSpaceMaxEm = 0.5f,
        )

        assertEquals(1, plan.allocations.size)
        assertEquals(GlueKind.CjkInterChar, plan.allocations.single().kind)
        assertEquals(2, plan.allocations.single().targetClusterIndex)
        assertEquals(em, plan.allocations.single().delta, 0.001f)
        assertEquals(0f, plan.unfilledDeficit)
    }

    @Test
    fun virtualSinoWesternStretchRequiresAlphaNumericBoundaryChar() {
        // 中/Hi中 — `/Hi` is LatinText for shaping, but the leading boundary is
        // ideograph↔solidus, not ideograph↔alpha. Tier ② may stretch `Hi↔中`
        // (target index 1), but must not synthesize `中 /Hi` (target index 0).
        val clusters = listOf(cjk(0), slashLatin(1, 2f * em), cjk(4))
        val roles = listOf(FontRole.CjkText, FontRole.LatinText, FontRole.CjkText)
        val natural = clusters.sumOf { it.advance.toDouble() }.toFloat()
        val plan = Justifier().justify(
            adjustedClusters = clusters,
            clusterRoles = roles,
            eastAsianSpacingEdges = spacingEdges(clusters),
            lineClusterRange = clusters.indices,
            maxWidth = natural + 0.2f * em,
            fontSize = em,
            skip = false,
            cjkLatinSpaceBaseEm = 0.25f,
            cjkLatinSpaceMaxEm = 0.5f,
        )

        val sino = plan.allocations.filter { it.kind == GlueKind.CjkLatinSpace }
        assertEquals(listOf(1), sino.map { it.targetClusterIndex })
    }

    @Test
    fun typedSpaceBeforeSlashLedLatinRunIsNotSinoWesternGap() {
        // 中 /Hi — if the author typed a space before a slash-led technical run,
        // it is preserved as ordinary source spacing. It must not be promoted to
        // tier-② 中西间距 because the boundary-adjacent western char is `/`.
        val clusters = listOf(cjk(0), space(1), slashLatin(2, 2f * em))
        val roles = listOf(FontRole.CjkText, FontRole.LatinText, FontRole.LatinText)
        val natural = clusters.sumOf { it.advance.toDouble() }.toFloat()
        val plan = Justifier().justify(
            adjustedClusters = clusters,
            clusterRoles = roles,
            eastAsianSpacingEdges = spacingEdges(clusters),
            lineClusterRange = clusters.indices,
            maxWidth = natural + 0.2f * em,
            fontSize = em,
            skip = false,
            cjkLatinSpaceBaseEm = 0.25f,
            cjkLatinSpaceMaxEm = 0.5f,
        )

        assertTrue(plan.allocations.none { it.kind == GlueKind.CjkLatinSpace })
    }

    @Test
    fun sinoWesternStretchRespectsThirdEmCapWhenStyleSetsIt() {
        // CLREQ ② 注: a style may cap 中西间距 stretch at ⅓字 instead of ½.
        val clusters = listOf(cjk(0), space(1), latin(2, 2f * em))
        val roles = listOf(FontRole.CjkText, FontRole.LatinText, FontRole.LatinText)
        val natural = clusters.sumOf { it.advance.toDouble() }.toFloat()
        val plan = Justifier().justify(
            adjustedClusters = clusters,
            clusterRoles = roles,
            eastAsianSpacingEdges = spacingEdges(clusters),
            lineClusterRange = clusters.indices,
            maxWidth = natural + 1f * em, // big deficit, beyond the gap's headroom
            fontSize = em,
            skip = false,
            cjkLatinSpaceBaseEm = 0.25f,
            cjkLatinSpaceMaxEm = 1f / 3f,
        )

        val sino = plan.allocations.single { it.kind == GlueKind.CjkLatinSpace }
        // base 0.25em → capped at ⅓em, so the typed gap opens at most 1/12 em
        // (not the 0.25em it would reach under the ½em default).
        assertEquals((1f / 3f - 0.25f) * em, sino.delta, 0.001f)
    }
}
