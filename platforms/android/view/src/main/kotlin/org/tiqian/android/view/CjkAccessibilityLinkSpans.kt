package org.tiqian.android.view

import android.text.SpannableString
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.View

/** Keeps framework ClickableSpan identity stable for exactly one content/document revision. */
internal class CjkAccessibilityLinkSpans(
    private val activate: (CjkTextView.LinkHit) -> Unit,
) {
    private var spans = emptyMap<CjkTextView.LinkHit, ClickableSpan>()

    fun reset() {
        spans = emptyMap()
    }

    fun applyTo(source: String, links: List<CjkTextView.LinkHit>): CharSequence {
        if (source.isEmpty() || links.isEmpty()) {
            spans = emptyMap()
            return source
        }
        spans = links.associateWith { link ->
            spans[link] ?: object : ClickableSpan() {
                override fun onClick(widget: View) {
                    activate(link)
                }
            }
        }
        return SpannableString(source).apply {
            links.forEach { link ->
                val start = link.range.start.coerceIn(0, source.length)
                val end = link.range.end.coerceIn(start, source.length)
                if (start < end) {
                    setSpan(
                        spans.getValue(link),
                        start,
                        end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
        }
    }
}
