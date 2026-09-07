package org.tiqian.shaping.android

import android.text.TextPaint
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

/**
 * The advance the platform string draw consumes for [text] under [role] with [paint]: measured in
 * the same `中…中` buffer the draw uses when the text needs Han context, so measure equals draw.
 */
fun platformRunAdvance(paint: TextPaint, text: String, role: FontRole): Float {
    if (text.isEmpty()) return 0f
    if (!requiresHanShapingContext(text, role)) {
        return paint.getRunAdvance(text, 0, text.length, 0, text.length, false, text.length)
    }
    val buffer = "中${text}中"
    val penStart = paint.getRunAdvance(buffer, 0, buffer.length, 0, buffer.length, false, 1)
    val penEnd = paint.getRunAdvance(buffer, 0, buffer.length, 0, buffer.length, false, 1 + text.length)
    return penEnd - penStart
}
