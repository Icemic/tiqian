package org.tiqian.layout

import org.tiqian.core.UnicodeEastAsianSpacing
import org.tiqian.core.UnicodeScriptEvidence
import org.tiqian.core.UnicodeScriptEvidenceClassifier
import org.tiqian.font.FontRole
import org.tiqian.font.FontRoleContext

/**
 * Resolves shared curly-quote code points from the full quotation structure.
 *
 * Unicode Script assigns quotation marks to Common and recommends resolving
 * paired punctuation from its enclosing level. Accordingly this resolver does
 * not let one adjacent character decide a pair. It considers, in order:
 *
 * 1. the strong-script text at the pair's enclosing level, on both sides;
 * 2. the already-resolved enclosing quotation;
 * 3. all strong-script content inside the pair;
 * 4. the paragraph language when the available text is mixed or absent.
 *
 * A whitespace-delimited, wholly non-CJK quotation remains an independent
 * Western inline run. This preserves authorial orthography such as
 * `（如 ‘O’, ‘Q’）` without allowing a Latin identifier at the start of a
 * mixed Chinese quotation to take over the whole pair.
 */
internal class ContextualQuoteRoleResolver(
    private val text: String,
    private val pairs: List<QuotePair>,
    private val context: FontRoleContext,
) {
    private val pairByOpen = pairs.associateBy { it.openIndex }
    private val pairByClose = pairs.associateBy { it.closeIndex }
    private val parentByPair = pairs.associateWith(::findParent)

    fun resolve(): List<QuoteRoleDecision> {
        val decisions = mutableListOf<QuoteRoleDecision>()
        val resolvedPairs = mutableMapOf<QuotePair, FontRole>()

        for (pair in pairs.sortedWith(compareBy<QuotePair> { it.openIndex }.thenByDescending { it.closeIndex })) {
            val decision = resolvePair(pair, resolvedPairs)
            resolvedPairs[pair] = decision.role
            decisions += QuoteRoleDecision(pair.openIndex, decision.role, decision.source, decision.reason)
            decisions += QuoteRoleDecision(pair.closeIndex, decision.role, decision.source, decision.reason)
        }

        val pairedIndices = pairs.flatMapTo(mutableSetOf()) { listOf(it.openIndex, it.closeIndex) }
        for (index in text.indices) {
            if (index in pairedIndices || !text[index].isAmbiguousCurlyQuote()) continue
            val decision = resolveUnmatched(index)
            decisions += QuoteRoleDecision(index, decision.role, decision.source, decision.reason)
        }

        return decisions.sortedBy { it.index }
    }

    private fun resolvePair(
        pair: QuotePair,
        resolvedPairs: Map<QuotePair, FontRole>,
    ): Resolution {
        val parent = parentByPair.getValue(pair)
        val enclosingStart = parent?.openIndex?.plus(1) ?: 0
        val enclosingEnd = parent?.closeIndex ?: text.length
        val outerEvidence = ScriptEvidence().apply {
            addRange(enclosingStart, pair.openIndex)
            addRange(pair.closeIndex + 1, enclosingEnd)
        }
        val contentEvidence = ScriptEvidence().apply {
            addRange(pair.openIndex + 1, pair.closeIndex)
        }

        if (
            text.getOrNull(pair.openIndex - 1)?.isAsciiSpaceOrTab() == true &&
            contentEvidence.hasWestern &&
            !contentEvidence.hasCjk
        ) {
            return Resolution(
                role = FontRole.LatinText,
                source = "DelimitedWesternQuotationRun",
                reason = "whitespace-delimited-wholly-western-quotation",
            )
        }

        outerEvidence.unambiguousRole()?.let { role ->
            return Resolution(
                role = role,
                source = "PairedPunctuationOuterScriptContext",
                reason = "quote-pair-inherits-enclosing-level-script",
            )
        }

        if (outerEvidence.isMixed) {
            return paragraphLanguageResolution("mixed-enclosing-level-script")
        }

        parent?.let { enclosingPair ->
            resolvedPairs[enclosingPair]?.let { role ->
                return Resolution(
                    role = role,
                    source = "PairedPunctuationEnclosingQuoteContext",
                    reason = "quote-pair-inherits-enclosing-quotation",
                )
            }
        }

        contentEvidence.unambiguousRole()?.let { role ->
            return Resolution(
                role = role,
                source = "PairedPunctuationContentScriptContext",
                reason = "quoted-content-script",
            )
        }

        return paragraphLanguageResolution(
            if (contentEvidence.isMixed) "mixed-quoted-content" else "no-strong-script-context",
        )
    }

    private fun resolveUnmatched(index: Int): Resolution {
        if (text[index] == '\u2019' && text.isNonCjkInWordApostrophe(index)) {
            return Resolution(
                role = FontRole.LatinText,
                source = "NonCjkInWordApostrophe",
                reason = "non-cjk-in-word-apostrophe",
            )
        }

        val leftRole = nearestStrongScriptRole(index - 1, direction = -1)
        val rightRole = nearestStrongScriptRole(index + 1, direction = 1)

        if (
            text.getOrNull(index - 1)?.isAsciiSpaceOrTab() == true &&
            rightRole == FontRole.LatinText
        ) {
            return Resolution(
                role = FontRole.LatinText,
                source = "DelimitedUnmatchedWesternQuote",
                reason = "whitespace-delimited-unmatched-western-quote",
            )
        }

        if (leftRole != null && (rightRole == null || rightRole == leftRole)) {
            return Resolution(
                role = leftRole,
                source = "UnmatchedQuoteSurroundingScriptContext",
                reason = "unmatched-quote-surrounding-script",
            )
        }
        if (rightRole != null && leftRole == null) {
            return Resolution(
                role = rightRole,
                source = "UnmatchedQuoteSurroundingScriptContext",
                reason = "unmatched-quote-surrounding-script",
            )
        }
        return paragraphLanguageResolution(
            if (leftRole != null && rightRole != null) {
                "conflicting-unmatched-quote-context"
            } else {
                "no-unmatched-quote-context"
            },
        )
    }

    private fun nearestStrongScriptRole(startIndex: Int, direction: Int): FontRole? {
        var index = startIndex
        while (index in text.indices) {
            if (direction < 0) {
                val pair = pairByClose[index]
                if (pair != null) {
                    index = pair.openIndex - 1
                    continue
                }
            } else {
                val pair = pairByOpen[index]
                if (pair != null) {
                    index = pair.closeIndex + 1
                    continue
                }
            }

            val scalarStart = if (
                direction < 0 &&
                text[index].code in 0xDC00..0xDFFF &&
                index > 0 &&
                text[index - 1].code in 0xD800..0xDBFF
            ) {
                index - 1
            } else {
                index
            }
            val scalarLength = text.codePointLengthAt(scalarStart, text.length)
            strongScriptRole(scalarStart, scalarLength)?.let { return it }
            index = if (direction < 0) scalarStart - 1 else scalarStart + scalarLength
        }
        return null
    }

    private fun paragraphLanguageResolution(reason: String): Resolution = Resolution(
        role = if (UnicodeEastAsianSpacing.isChineseLanguageContext(context.locale)) {
            FontRole.CjkPunctuation
        } else {
            FontRole.LatinText
        },
        source = "ParagraphLanguageQuoteContext",
        reason = "$reason; paragraph-language=${context.locale}",
    )

    private fun findParent(pair: QuotePair): QuotePair? = pairs
        .asSequence()
        .filter { candidate ->
            candidate !== pair &&
                candidate.openIndex < pair.openIndex &&
                candidate.closeIndex > pair.closeIndex
        }
        .minByOrNull { it.closeIndex - it.openIndex }

    private inner class ScriptEvidence(
        var hasCjk: Boolean = false,
        var hasWestern: Boolean = false,
    ) {
        val isMixed: Boolean get() = hasCjk && hasWestern

        fun addRange(start: Int, end: Int) {
            var index = start
            while (index < end) {
                val nestedPair = pairByOpen[index]
                if (nestedPair != null && nestedPair.closeIndex < end) {
                    index = nestedPair.closeIndex + 1
                    continue
                }

                val codePointLength = text.codePointLengthAt(index, end)
                when (strongScriptRole(index, codePointLength)) {
                    FontRole.CjkPunctuation -> hasCjk = true
                    FontRole.LatinText -> hasWestern = true
                    else -> Unit
                }
                index += codePointLength
            }
        }

        fun unambiguousRole(): FontRole? = when {
            hasCjk && !hasWestern -> FontRole.CjkPunctuation
            hasWestern && !hasCjk -> FontRole.LatinText
            else -> null
        }

    }

    private fun strongScriptRole(index: Int, codePointLength: Int): FontRole? {
        val codePoint = text.codePointAtCompat(index, index + codePointLength)
        return when (UnicodeScriptEvidenceClassifier.classify(codePoint)) {
            UnicodeScriptEvidence.Neutral -> null
            UnicodeScriptEvidence.EastAsian -> FontRole.CjkPunctuation
            UnicodeScriptEvidence.Other -> FontRole.LatinText
        }
    }

    private fun String.codePointAtCompat(index: Int, end: Int): Int {
        val high = this[index].code
        if (high !in 0xD800..0xDBFF || index + 1 >= end) return high
        val low = this[index + 1].code
        if (low !in 0xDC00..0xDFFF) return high
        return 0x10000 + ((high - 0xD800) shl 10) + (low - 0xDC00)
    }

    private fun String.codePointLengthAt(index: Int, end: Int): Int {
        val high = this[index].code
        return if (
            high in 0xD800..0xDBFF &&
            index + 1 < end &&
            this[index + 1].code in 0xDC00..0xDFFF
        ) {
            2
        } else {
            1
        }
    }

    private fun Char.isAmbiguousCurlyQuote(): Boolean =
        this == '\u2018' || this == '\u2019' || this == '\u201C' || this == '\u201D'

    private fun Char.isAsciiSpaceOrTab(): Boolean = this == ' ' || this == '\t'

    private data class Resolution(
        val role: FontRole,
        val source: String,
        val reason: String,
    )
}
