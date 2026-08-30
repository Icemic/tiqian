package org.tiqian.layout

import org.tiqian.clreq.CjkPunctuationGlyphSubstitution
import org.tiqian.clreq.ClreqPunctuationGlyphSubstitutor
import org.tiqian.font.FontRole

/**
 * `CjkRoleGatedDisplaySubstitution` keeps CLREQ display-codepoint replacement
 * downstream of contextual font-role resolution. A dash or ellipsis resolved
 * as Western must retain its source code point even when a style boundary
 * leaves that punctuation in a standalone shaping segment.
 */
internal fun ClreqPunctuationGlyphSubstitutor.substituteForRole(
    sourceText: String,
    role: FontRole,
): CjkPunctuationGlyphSubstitution {
    val candidate = substitute(sourceText)
    return if (role == FontRole.CjkPunctuation || candidate.displayText == sourceText) {
        candidate
    } else {
        CjkPunctuationGlyphSubstitution(
            sourceText = sourceText,
            displayText = sourceText,
            reason = "CjkRoleGatedDisplaySubstitution:preserve-role-$role",
        )
    }
}
