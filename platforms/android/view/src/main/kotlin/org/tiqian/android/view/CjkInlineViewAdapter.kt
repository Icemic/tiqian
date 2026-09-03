package org.tiqian.android.view

import android.view.View
import android.view.ViewGroup
import org.tiqian.core.InlineObjectSpan

/**
 * Supplies native child views for Tiqian inline-object geometry.
 *
 * [InlineObjectSpan] remains the measurement contract: its advance/ascent/descent must describe
 * the supplied child's margin box before paragraph layout. The View frontend places the child at
 * the engine's final draw origin and never reinterprets or reflows that geometry.
 */
interface CjkInlineViewAdapter {
    /** Stable identity used to retain a child across content updates. */
    fun getItemId(content: CjkTextContent, span: InlineObjectSpan): Any = span.range

    fun createView(parent: ViewGroup, content: CjkTextContent, span: InlineObjectSpan): View

    fun bindView(view: View, content: CjkTextContent, span: InlineObjectSpan) = Unit

    fun recycleView(view: View) = Unit
}

/** Host callback for link activation. Return true to consume instead of opening the target URI. */
fun interface CjkLinkClickListener {
    fun onLinkClick(view: CjkTextView, target: String, start: Int, end: Int): Boolean
}
