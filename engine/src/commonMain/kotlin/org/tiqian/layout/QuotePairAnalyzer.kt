package org.tiqian.layout

import org.tiqian.core.TextRange
import org.tiqian.core.UnicodeScriptEvidence
import org.tiqian.core.UnicodeScriptEvidenceClassifier
import org.tiqian.core.UnicodeNumber
import org.tiqian.core.UnicodeWordCharacter
import org.tiqian.font.FontRole
import org.tiqian.font.FontRoleClassifier
import org.tiqian.font.FontRoleContext

data class QuotePair(
    val openIndex: Int,
    val closeIndex: Int,
    val quoteType: QuoteType,
)

enum class QuoteType { Double, Single }

data class QuoteRoleDecision(
    val index: Int,
    val role: FontRole,
    val source: String,
    val reason: String,
)

/**
 * Finds structurally paired curly quotes and delegates their script role to
 * [ContextualQuoteRoleResolver]. Pairing and language/script resolution stay
 * separate so neither phase has to guess the other's state.
 */
class QuotePairAnalyzer {
    fun analyze(text: String): List<QuotePair> {
        val stack = ArrayDeque<Pair<Int, QuoteType>>()
        val pairs = mutableListOf<QuotePair>()

        for (index in text.indices) {
            when (text[index].code) {
                0x201C -> stack.addLast(index to QuoteType.Double)
                0x2018 -> stack.addLast(index to QuoteType.Single)
                0x201D -> if (stack.lastOrNull()?.second == QuoteType.Double) {
                    val match = stack.removeLast()
                    pairs += QuotePair(match.first, index, QuoteType.Double)
                }
                0x2019 -> if (
                    !text.isNonCjkInWordApostrophe(index) &&
                    stack.lastOrNull()?.second == QuoteType.Single
                ) {
                    val match = stack.removeLast()
                    pairs += QuotePair(match.first, index, QuoteType.Single)
                }
            }
        }
        return pairs
    }

    fun classifyPairs(
        text: String,
        pairs: List<QuotePair>,
        context: FontRoleContext = FontRoleContext(),
    ): Map<Int, FontRole> =
        classifyQuoteRoles(text, pairs, context).associate { it.index to it.role }

    /**
     * Source-compatible entry point retained for callers of the first alpha.
     * Script evidence is now Unicode-defined and no longer delegated to a font
     * classifier, so [fontRoleClassifier] is intentionally ignored.
     */
    @Suppress("UNUSED_PARAMETER")
    fun classifyPairs(
        text: String,
        pairs: List<QuotePair>,
        fontRoleClassifier: FontRoleClassifier,
        context: FontRoleContext = FontRoleContext(),
    ): Map<Int, FontRole> = classifyPairs(text, pairs, context)

    fun classifyQuoteRoles(
        text: String,
        pairs: List<QuotePair>,
        context: FontRoleContext = FontRoleContext(),
    ): List<QuoteRoleDecision> = ContextualQuoteRoleResolver(
        text = text,
        pairs = pairs,
        context = context,
    ).resolve()

    /** Source-compatible counterpart to [classifyPairs]. */
    @Suppress("UNUSED_PARAMETER")
    fun classifyQuoteRoles(
        text: String,
        pairs: List<QuotePair>,
        fontRoleClassifier: FontRoleClassifier,
        context: FontRoleContext = FontRoleContext(),
    ): List<QuoteRoleDecision> = classifyQuoteRoles(text, pairs, context)

}

internal fun String.isNonCjkInWordApostrophe(index: Int): Boolean =
    codePointBefore(index)?.isNonCjkWordCharacter() == true &&
        codePointAtOrNull(index + 1)?.isNonCjkWordCharacter() == true

internal fun String.isNonCjkWordInternalQuotePair(pair: QuotePair): Boolean {
    if (
        codePointBefore(pair.openIndex)?.isNonCjkNonNumericWordCharacter() != true ||
        codePointAtOrNull(pair.closeIndex + 1)?.isNonCjkNonNumericWordCharacter() != true
    ) {
        return false
    }

    // UTF-16 indices must advance by code point to avoid inspecting a low surrogate.
    var index = pair.openIndex + 1
    while (index < pair.closeIndex) {
        val codePoint = codePointAtOrNull(index) ?: return false
        if (!codePoint.isNonCjkWordCharacter()) return false
        index += if (codePoint > 0xFFFF) 2 else 1
    }
    return true
}

private fun Int.isNonCjkWordCharacter(): Boolean =
    UnicodeWordCharacter.contains(this) &&
        UnicodeScriptEvidenceClassifier.classify(this) != UnicodeScriptEvidence.EastAsian

private fun Int.isNonCjkNonNumericWordCharacter(): Boolean =
    isNonCjkWordCharacter() && !UnicodeNumber.contains(this)

private fun String.codePointBefore(index: Int): Int? {
    if (index <= 0) return null
    val low = this[index - 1].code
    if (low !in 0xDC00..0xDFFF || index < 2) return low
    val high = this[index - 2].code
    if (high !in 0xD800..0xDBFF) return low
    return 0x10000 + ((high - 0xD800) shl 10) + (low - 0xDC00)
}

private fun String.codePointAtOrNull(index: Int): Int? {
    if (index !in indices) return null
    val high = this[index].code
    if (high !in 0xD800..0xDBFF || index + 1 >= length) return high
    val low = this[index + 1].code
    if (low !in 0xDC00..0xDFFF) return high
    return 0x10000 + ((high - 0xD800) shl 10) + (low - 0xDC00)
}

/**
 * Overrides a delegate classifier only at curly quotes resolved for this
 * paragraph. Every override remains traceable through [QuoteRoleDecision].
 */
class QuotePairAwareFontRoleClassifier(
    private val delegate: FontRoleClassifier,
    private val quoteRoles: Map<Int, FontRole>,
) : FontRoleClassifier {
    override fun classify(text: String, range: TextRange, context: FontRoleContext): FontRole =
        quoteRoles[range.start] ?: delegate.classify(text, range, context)
}
