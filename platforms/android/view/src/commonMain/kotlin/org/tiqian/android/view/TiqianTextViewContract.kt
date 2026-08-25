package org.tiqian.android.view

import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LayoutInput
import org.tiqian.core.LayoutResult
import org.tiqian.core.TiqianTextContent
import org.tiqian.layout.TiqianParagraphLayoutEngine
import org.tiqian.layout.ParagraphLayoutEngine

data class TiqianTextViewState(
    val text: String,
    val constraints: LayoutConstraints,
    val lastLayout: LayoutResult? = null,
)

class TiqianTextViewLayoutAdapter(
    private val engine: ParagraphLayoutEngine = TiqianParagraphLayoutEngine(),
) {
    fun layout(state: TiqianTextViewState): LayoutResult =
        engine.layout(
            LayoutInput(
                content = TiqianTextContent(state.text),
                constraints = state.constraints,
            ),
        )
}

