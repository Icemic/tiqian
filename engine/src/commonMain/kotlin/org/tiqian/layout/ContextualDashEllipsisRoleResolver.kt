package org.tiqian.layout

import org.tiqian.core.RoleOverrideInfo
import org.tiqian.core.TextRange
import org.tiqian.core.UnicodeEastAsianSpacing
import org.tiqian.core.UnicodeScriptEvidence
import org.tiqian.core.UnicodeScriptEvidenceClassifier
import org.tiqian.core.UnicodeWordCharacter
import org.tiqian.font.FontRole
import org.tiqian.font.FontRoleClassifier
import org.tiqian.font.FontRoleContext
import org.tiqian.linebreak.isMandatoryBreakCodePoint

internal data class DashEllipsisRoleDecision(
    val range: TextRange,
    val role: FontRole,
    val source: String,
    val reason: String,
)

/**
 * `ContextualDashEllipsisRoleResolution` resolves U+2014 EM DASH and U+2026
 * HORIZONTAL ELLIPSIS from surrounding strong-script text. The number of
 * repeated marks only defines the source run; it never decides its language.
 *
 * Matching strong evidence on both sides, or the only available side, wins.
 * Conflicting or absent evidence falls back to the paragraph language. A
 * mandatory break is a hard context boundary, so an otherwise empty source
 * line cannot borrow the script of a neighbouring line.
 *
 * `ParentheticalDashPairContext`: two adjacent equal-length pure U+2014 runs
 * whose separating content is only word characters and ASCII spaces form one
 * parenthetical insertion and resolve jointly from the text outside it. The
 * inserted content never votes, so `想Jessica——Jessica是他的前女友——睡不着`
 * keeps both dashes on one face. Any other separating character keeps the
 * runs independent; ellipsis runs never pair.
 */
internal class ContextualDashEllipsisRoleResolver {
    fun resolve(
        text: String,
        context: FontRoleContext = FontRoleContext(),
    ): List<DashEllipsisRoleDecision> {
        if (text.none { it.isContextualDashOrEllipsis() }) return emptyList()
        val strongScriptContext = StrongScriptContextIndex(text)
        val runs = collectRuns(text)
        val pairResolutions = resolveParentheticalPairs(text, runs, strongScriptContext, context)
        return runs.map { range ->
            val resolution = pairResolutions[range]
                ?: resolveSingleRun(range, strongScriptContext, context)
            DashEllipsisRoleDecision(
                range = range,
                role = resolution.role,
                source = resolution.source,
                reason = resolution.reason,
            )
        }
    }

    private fun collectRuns(text: String): List<TextRange> {
        val runs = mutableListOf<TextRange>()
        var index = 0
        while (index < text.length) {
            if (!text[index].isContextualDashOrEllipsis()) {
                index += text.codePointLengthAt(index)
                continue
            }
            val start = index
            while (index < text.length && text[index].isContextualDashOrEllipsis()) {
                index += 1
            }
            runs += TextRange(start, index)
        }
        return runs
    }

    private fun resolveSingleRun(
        range: TextRange,
        strongScriptContext: StrongScriptContextIndex,
        context: FontRoleContext,
    ): Resolution {
        val leftRole = strongScriptContext.leftOf(range.start)
        val rightRole = strongScriptContext.rightOf(range.end)
        return when {
            leftRole != null && rightRole == leftRole -> Resolution(
                role = leftRole,
                source = "DashEllipsisSurroundingScriptContext",
                reason = "matching-surrounding-script",
            )
            leftRole != null && rightRole == null -> Resolution(
                role = leftRole,
                source = "DashEllipsisSurroundingScriptContext",
                reason = "only-left-strong-script",
            )
            rightRole != null && leftRole == null -> Resolution(
                role = rightRole,
                source = "DashEllipsisSurroundingScriptContext",
                reason = "only-right-strong-script",
            )
            else -> paragraphLanguageResolution(
                context = context,
                reason = if (leftRole != null && rightRole != null) {
                    "conflicting-surrounding-script"
                } else {
                    "no-strong-script-context"
                },
            )
        }
    }

    private fun resolveParentheticalPairs(
        text: String,
        runs: List<TextRange>,
        strongScriptContext: StrongScriptContextIndex,
        context: FontRoleContext,
    ): Map<TextRange, Resolution> {
        val resolutions = mutableMapOf<TextRange, Resolution>()
        var index = 0
        while (index + 1 < runs.size) {
            val first = runs[index]
            val second = runs[index + 1]
            if (!text.isParentheticalDashPair(first, second)) {
                index += 1
                continue
            }
            val leftRole = strongScriptContext.leftOf(first.start)
            val rightRole = strongScriptContext.rightOf(second.end)
            val resolution = when {
                leftRole != null && rightRole == leftRole -> Resolution(
                    role = leftRole,
                    source = "ParentheticalDashPairContext",
                    reason = "matching-outer-script",
                )
                leftRole != null && rightRole == null -> Resolution(
                    role = leftRole,
                    source = "ParentheticalDashPairContext",
                    reason = "only-left-outer-script",
                )
                rightRole != null && leftRole == null -> Resolution(
                    role = rightRole,
                    source = "ParentheticalDashPairContext",
                    reason = "only-right-outer-script",
                )
                else -> paragraphLanguageResolution(
                    context = context,
                    reason = if (leftRole != null && rightRole != null) {
                        "parenthetical-pair-conflicting-outer-script"
                    } else {
                        "parenthetical-pair-no-outer-context"
                    },
                )
            }
            resolutions[first] = resolution
            resolutions[second] = resolution
            index += 2
        }
        return resolutions
    }

    private fun paragraphLanguageResolution(
        context: FontRoleContext,
        reason: String,
    ): Resolution = Resolution(
        role = if (UnicodeEastAsianSpacing.isChineseLanguageContext(context.locale)) {
            FontRole.CjkPunctuation
        } else {
            FontRole.LatinText
        },
        source = "ParagraphLanguageDashEllipsisContext",
        reason = "$reason; paragraph-language=${context.locale}",
    )

    private data class Resolution(
        val role: FontRole,
        val source: String,
        val reason: String,
    )
}

internal class ContextualDashEllipsisAwareFontRoleClassifier(
    private val delegate: FontRoleClassifier,
    decisions: List<DashEllipsisRoleDecision>,
) : FontRoleClassifier {
    private val roleByIndex: Map<Int, FontRole> = buildMap {
        decisions.forEach { decision ->
            for (index in decision.range.start until decision.range.end) {
                put(index, decision.role)
            }
        }
    }

    override fun classify(text: String, range: TextRange, context: FontRoleContext): FontRole =
        roleByIndex[range.start] ?: delegate.classify(text, range, context)
}

/**
 * Resolves contextual U+2014 and U+2026 roles for callers that classify several ranges from the
 * same complete paragraph outside the layout pipeline, such as markdown lowering bridges.
 */
fun FontRoleClassifier.withContextualDashEllipsisRoles(
    text: String,
    context: FontRoleContext = FontRoleContext(),
): FontRoleClassifier {
    val decisions = ContextualDashEllipsisRoleResolver().resolve(text, context)
    return if (decisions.isEmpty()) {
        this
    } else {
        ContextualDashEllipsisAwareFontRoleClassifier(this, decisions)
    }
}

internal fun List<DashEllipsisRoleDecision>.toRoleOverrideInfos(
    text: String,
    baseClassifier: FontRoleClassifier,
    context: FontRoleContext,
): List<RoleOverrideInfo> = map { decision ->
    val firstCodePointRange = TextRange(decision.range.start, decision.range.start + 1)
    RoleOverrideInfo(
        range = decision.range,
        sourceText = text.substring(decision.range.start, decision.range.end),
        originalRole = baseClassifier.classify(text, firstCodePointRange, context).name,
        overriddenRole = decision.role.name,
        source = decision.source,
        reason = decision.reason,
    )
}

private fun Char.isContextualDashOrEllipsis(): Boolean = this == '\u2014' || this == '\u2026'

private fun String.isParentheticalDashPair(first: TextRange, second: TextRange): Boolean {
    if (!isPureDashRun(first) || !isPureDashRun(second)) return false
    if (first.end - first.start != second.end - second.start) return false
    var index = first.end
    while (index < second.start) {
        val codePoint = codePointAtCompat(index)
        if (codePoint != 0x20 && !UnicodeWordCharacter.contains(codePoint)) return false
        index += codePoint.charCount()
    }
    return true
}

private fun String.isPureDashRun(range: TextRange): Boolean {
    for (index in range.start until range.end) {
        if (this[index] != '\u2014') return false
    }
    return true
}

/**
 * `StrongScriptContextIndex` records the nearest strong script at every UTF-16
 * boundary in two linear passes. Mandatory breaks reset both directions, so a
 * contextual punctuation run cannot borrow evidence from another source line.
 */
private class StrongScriptContextIndex(text: String) {
    private val leftRoleBeforeBoundary = arrayOfNulls<FontRole>(text.length + 1)
    private val rightRoleFromBoundary = arrayOfNulls<FontRole>(text.length + 1)

    init {
        var currentRole: FontRole? = null
        var scalarStart = 0
        leftRoleBeforeBoundary[0] = null
        while (scalarStart < text.length) {
            val codePoint = text.codePointAtCompat(scalarStart)
            val scalarEnd = scalarStart + codePoint.charCount()
            currentRole = codePoint.nextStrongScriptRole(currentRole)
            for (boundary in scalarStart + 1..scalarEnd) {
                leftRoleBeforeBoundary[boundary] = currentRole
            }
            scalarStart = scalarEnd
        }

        currentRole = null
        var scalarEnd = text.length
        rightRoleFromBoundary[text.length] = null
        while (scalarEnd > 0) {
            scalarStart = text.scalarStartBefore(scalarEnd)
            val codePoint = text.codePointAtCompat(scalarStart)
            currentRole = codePoint.nextStrongScriptRole(currentRole)
            for (boundary in scalarStart until scalarEnd) {
                rightRoleFromBoundary[boundary] = currentRole
            }
            scalarEnd = scalarStart
        }
    }

    fun leftOf(boundary: Int): FontRole? = leftRoleBeforeBoundary[boundary]

    fun rightOf(boundary: Int): FontRole? = rightRoleFromBoundary[boundary]
}

private fun Int.nextStrongScriptRole(currentRole: FontRole?): FontRole? {
    if (isMandatoryBreakCodePoint(this)) return null
    return when (UnicodeScriptEvidenceClassifier.classify(this)) {
        UnicodeScriptEvidence.EastAsian -> FontRole.CjkPunctuation
        UnicodeScriptEvidence.Other -> FontRole.LatinText
        UnicodeScriptEvidence.Neutral -> currentRole
    }
}

private fun String.scalarStartBefore(endExclusive: Int): Int {
    val lastIndex = endExclusive - 1
    return if (
        this[lastIndex].code in 0xDC00..0xDFFF &&
        lastIndex > 0 &&
        this[lastIndex - 1].code in 0xD800..0xDBFF
    ) {
        lastIndex - 1
    } else {
        lastIndex
    }
}

private fun String.codePointAtCompat(index: Int): Int {
    val high = this[index].code
    if (high !in 0xD800..0xDBFF || index + 1 >= length) return high
    val low = this[index + 1].code
    if (low !in 0xDC00..0xDFFF) return high
    return 0x10000 + ((high - 0xD800) shl 10) + (low - 0xDC00)
}

private fun String.codePointLengthAt(index: Int): Int = codePointAtCompat(index).charCount()

private fun Int.charCount(): Int = if (this > 0xFFFF) 2 else 1
