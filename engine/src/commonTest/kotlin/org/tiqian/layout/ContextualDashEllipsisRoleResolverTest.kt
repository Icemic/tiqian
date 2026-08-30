package org.tiqian.layout

import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LayoutInput
import org.tiqian.core.LayoutResult
import org.tiqian.core.ParagraphStyle
import org.tiqian.core.TextStyle
import org.tiqian.core.TiqianTextContent
import org.tiqian.core.ic
import org.tiqian.font.FontRole
import org.tiqian.font.FontRoleContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContextualDashEllipsisRoleResolverTest {
    private val resolver = ContextualDashEllipsisRoleResolver()

    @Test
    fun resolvesBySurroundingScriptRatherThanMarkCount() {
        val cases = listOf(
            RoleCase("English — next", '—', FontRole.LatinText),
            RoleCase("— English", '—', FontRole.LatinText),
            RoleCase("A——B", '—', FontRole.LatinText),
            RoleCase("Wait…what", '…', FontRole.LatinText),
            RoleCase("Wait……what", '…', FontRole.LatinText),
            RoleCase("中文—下句", '—', FontRole.CjkPunctuation),
            RoleCase("中文——下句", '—', FontRole.CjkPunctuation),
            RoleCase("中文—123", '—', FontRole.CjkPunctuation),
            RoleCase("123—English", '—', FontRole.LatinText),
            RoleCase("中文……", '…', FontRole.CjkPunctuation),
            RoleCase("等等…真的", '…', FontRole.CjkPunctuation),
            RoleCase("等等……真的", '…', FontRole.CjkPunctuation),
        )

        cases.forEach { case ->
            val decision = resolver.resolve(case.text).single()
            assertEquals(case.role, decision.role, case.text)
            assertEquals(case.text.indexOf(case.mark), decision.range.start, case.text)
            assertEquals(case.text.lastIndexOf(case.mark) + 1, decision.range.end, case.text)
            assertEquals("DashEllipsisSurroundingScriptContext", decision.source, case.text)
        }
    }

    @Test
    fun conflictingOrAbsentScriptFallsBackToParagraphLanguage() {
        for ((locale, role) in listOf("zh-Hans" to FontRole.CjkPunctuation, "en-US" to FontRole.LatinText)) {
            val conflicting = resolver.resolve("中文—English", FontRoleContext(locale = locale)).single()
            val isolated = resolver.resolve("…", FontRoleContext(locale = locale)).single()
            assertEquals(role, conflicting.role, locale)
            assertEquals(role, isolated.role, locale)
            assertEquals("ParagraphLanguageDashEllipsisContext", conflicting.source, locale)
            assertEquals("ParagraphLanguageDashEllipsisContext", isolated.source, locale)
        }
    }

    @Test
    fun decisionReasonNamesTheEvidenceShape() {
        assertEquals("matching-surrounding-script", resolver.resolve("A—B").single().reason)
        assertEquals("only-left-strong-script", resolver.resolve("中文……").single().reason)
        assertEquals("only-right-strong-script", resolver.resolve("— English").single().reason)
    }

    @Test
    fun mandatoryBreakStopsContextSearch() {
        val decision = resolver.resolve("—\nEnglish", FontRoleContext(locale = "zh-Hans")).single()
        assertEquals(FontRole.CjkPunctuation, decision.role)
        assertEquals("ParagraphLanguageDashEllipsisContext", decision.source)
        assertTrue(decision.reason.startsWith("no-strong-script-context"), decision.reason)
    }

    @Test
    fun linearContextIndexPreservesSupplementaryScriptEvidence() {
        val eastAsian = resolver.resolve("\uD840\uDC00—123").single()
        val other = resolver.resolve("123—\uD801\uDC00").single()

        assertEquals(FontRole.CjkPunctuation, eastAsian.role)
        assertEquals(FontRole.LatinText, other.role)
    }

    @Test
    fun resolvesManyNeutralSeparatedRunsFromOneParagraphIndex() {
        val text = buildString {
            append('A')
            repeat(2_048) { append(" — ") }
            append('B')
        }

        val decisions = resolver.resolve(text, FontRoleContext(locale = "zh-Hans"))

        assertEquals(2_048, decisions.size)
        assertTrue(decisions.all { it.role == FontRole.LatinText })
    }

    private data class RoleCase(
        val text: String,
        val mark: Char,
        val role: FontRole,
    )
}

class ContextualDashEllipsisLayoutTest {
    private val engine = ExplainableStubParagraphLayoutEngine()

    @Test
    fun westernContextKeepsDashAndEllipsisOnLatinFaceAndPreservesSourceDisplay() {
        val text = "English — next; ellipsis… / slash. A——B; Wait……what?"
        val result = layout(text)

        for (markIndex in text.indices.filter { text[it] == '—' || text[it] == '…' }) {
            val decision = result.fontDecisionAt(markIndex)
            assertEquals(FontRole.LatinText.name, decision.role, "index=$markIndex $decision")
            assertEquals(decision.sourceText, decision.displayText, "index=$markIndex $decision")
        }
        assertTrue(result.debug.punctuationDecisions.none { it.char == '—' || it.char == '…' })
        assertTrue(
            result.debug.roleOverrides
                .filter { it.sourceText.any { char -> char == '—' || char == '…' } }
                .all { it.source == "DashEllipsisSurroundingScriptContext" },
            result.debug.roleOverrides.toString(),
        )
    }

    @Test
    fun cjkContextKeepsClreqDisplaySubstitutionIndependentOfMarkCount() {
        val text = "中—文，等…真；中文——下句，省略号……。"
        val result = layout(text)

        assertEquals("—", result.fontDecisionAt(text.indexOf('—')).displayText)
        assertEquals("⋯", result.fontDecisionAt(text.indexOf('…')).displayText)
        assertEquals("⸺", result.fontDecisionAt(text.indexOf("——")).displayText)
        assertEquals("⋯⋯", result.fontDecisionAt(text.indexOf("……")).displayText)
        for (markIndex in text.indices.filter { text[it] == '—' || text[it] == '…' }) {
            assertEquals(FontRole.CjkPunctuation.name, result.fontDecisionAt(markIndex).role)
        }
    }

    @Test
    fun standaloneWesternEllipsisCannotBeRewrittenByTheSubstitutor() {
        val result = layout("…", locale = "en-US")
        val decision = result.debug.fontDecisions.single()

        assertEquals(FontRole.LatinText.name, decision.role)
        assertEquals("…", decision.sourceText)
        assertEquals("…", decision.displayText)
        assertEquals(
            "CjkRoleGatedDisplaySubstitution:preserve-role-LatinText",
            decision.substitutionReason,
        )
        assertEquals("…", result.clusters.single().displayText)
    }

    private fun layout(text: String, locale: String = "zh-Hans"): LayoutResult = engine.layout(
        LayoutInput(
            content = TiqianTextContent(text),
            textStyle = TextStyle(locale = locale),
            paragraphStyle = ParagraphStyle(firstLineIndent = 0.ic),
            constraints = LayoutConstraints(maxWidth = 1000f),
        ),
    )

    private fun LayoutResult.fontDecisionAt(index: Int) = debug.fontDecisions.single { decision ->
        index >= decision.range.start && index < decision.range.end
    }
}
