package org.tiqian.shaping.android

import org.tiqian.font.FontRole

/**
 * `ContextOnlyForNonAlphanumericCjk`: Android needs a Han-script neighbour for isolated
 * script-common CJK marks such as U+2014, otherwise Minikin can select the Western form.
 * Ordinary letters and numbers already carry their own Unicode script and gain nothing from
 * shaping the synthetic `中…中` buffer.
 */
fun requiresHanShapingContext(displayText: String, role: FontRole): Boolean {
    if (displayText.isEmpty()) return false
    if (role == FontRole.CjkPunctuation) return true
    if (role != FontRole.CjkText) return false

    var offset = 0
    while (offset < displayText.length) {
        val codePoint = Character.codePointAt(displayText, offset)
        if (Character.isLetterOrDigit(codePoint)) return false
        offset += Character.charCount(codePoint)
    }
    return true
}
