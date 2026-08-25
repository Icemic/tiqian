@file:OptIn(kotlin.js.ExperimentalJsExport::class)

package org.tiqian.ffi.js

import kotlin.js.JsExport
import org.tiqian.core.TextRange
import org.tiqian.font.CjkFontRoleClassifier
import org.tiqian.font.FontRole
import org.tiqian.font.FontRoleContext
import org.tiqian.font.InlineShapingStylePolicy

/**
 * Lowering helper exports consumed by the TypeScript markdown lowering engine
 * (`frontend/web/core/core/engine/markdown-lowering.js`) via `@tiqian/ffi`.
 *
 * These exports feed the helper callbacks of the TS markdown lowering. Until the
 * TsHost port deletes the Kotlin facade, both consumers share the font module as
 * the single implementation. ADR 0053.
 */

private val fontRoleClassifier = CjkFontRoleClassifier()

/**
 * Classifies the typographic font role of the substring [text] in [start]..<[end].
 *
 * Maps [FontRole.CjkText] to `"cjk-text"`, [FontRole.CjkPunctuation] to `"cjk-punctuation"`,
 * and any other role (e.g. Latin, Symbol, Emoji, Unknown) to `"other"`.
 */
@JsExport
fun classifyFontRole(
    text: String,
    start: Int,
    end: Int,
    locale: String,
): String {
    val role = fontRoleClassifier.classify(
        text = text,
        range = TextRange(start, end),
        context = FontRoleContext(locale = locale),
    )
    return when (role) {
        FontRole.CjkText -> "cjk-text"
        FontRole.CjkPunctuation -> "cjk-punctuation"
        else -> "other"
    }
}

/**
 * Returns the ordered list of 16 inherited shaping properties compared during markdown lowering.
 *
 * Returns a fresh array per call to prevent callers from mutating internal state.
 */
@JsExport
fun unsupportedInlineShapingProperties(): Array<String> =
    InlineShapingStylePolicy.unsupportedInlineShapingProperties.toTypedArray()

/**
 * Finds the first shaping property whose value in [elementValues] diverges from [paragraphValues].
 *
 * Compares property values by index along [unsupportedInlineShapingProperties]. A return value of
 * `null` (`null` or `undefined` in JavaScript) means no divergence was found within the common prefix.
 */
@JsExport
fun firstDivergentInlineShapingProperty(
    elementValues: Array<String>,
    paragraphValues: Array<String>,
): String? =
    InlineShapingStylePolicy.firstDivergentProperty(
        elementValues = elementValues.toList(),
        paragraphValues = paragraphValues.toList(),
    )
