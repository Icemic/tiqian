@file:OptIn(kotlin.js.ExperimentalJsExport::class)

package org.tiqian.ffi.js

import kotlin.js.JsExport
import org.tiqian.core.TextRange
import org.tiqian.font.CjkFontRoleClassifier
import org.tiqian.font.FontRole
import org.tiqian.font.FontRoleClassifier
import org.tiqian.font.FontRoleContext
import org.tiqian.font.InlineShapingStylePolicy
import org.tiqian.layout.withContextualDashEllipsisRoles

/**
 * Lowering helper exports consumed by the TypeScript markdown lowering engine
 * (`platforms/web/client/core/src/engine/markdown-lowering.js`) via `@tiqian/ffi`.
 *
 * These exports feed the helper callbacks of the TS markdown lowering. Until the
 * TsHost port deletes the Kotlin facade, both consumers share the font module as
 * the single implementation. ADR 0053.
 */

private val fontRoleClassifier = CjkFontRoleClassifier()

/**
 * Classifies the typographic font role of [start]..<[end] within the complete paragraph [text].
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
    val context = FontRoleContext(locale = locale)
    val role = contextualFontRoleClassifier(text, context).classify(
        text = text,
        range = TextRange(start, end),
        context = context,
    )
    return role.toLoweringRoleName()
}

/**
 * Classifies several ranges against one complete paragraph and resolves contextual dash and
 * ellipsis roles once. The batch boundary keeps markdown lowering linear while preserving
 * surrounding-script evidence across DOM text-node boundaries.
 */
@JsExport
fun classifyFontRoles(
    text: String,
    starts: Array<Int>,
    ends: Array<Int>,
    locale: String,
): Array<String> {
    require(starts.size == ends.size) { "starts and ends must have the same size" }
    val context = FontRoleContext(locale = locale)
    val classifier = contextualFontRoleClassifier(text, context)
    return Array(starts.size) { index ->
        classifier.classify(
            text = text,
            range = TextRange(starts[index], ends[index]),
            context = context,
        ).toLoweringRoleName()
    }
}

private fun contextualFontRoleClassifier(
    text: String,
    context: FontRoleContext,
): FontRoleClassifier {
    return fontRoleClassifier.withContextualDashEllipsisRoles(text, context)
}

private fun FontRole.toLoweringRoleName(): String = when (this) {
    FontRole.CjkText -> "cjk-text"
    FontRole.CjkPunctuation -> "cjk-punctuation"
    else -> "other"
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
