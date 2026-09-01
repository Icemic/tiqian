package org.tiqian.demo.android

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import org.tiqian.android.view.CjkTextContent
import org.tiqian.core.ParagraphStyle
import org.tiqian.core.TextStyle

internal const val VIEW_DEMO_PARAGRAPH_COUNT = 48
internal const val VIEW_DEMO_LINK_COLOR = 0xFF1558D6.toInt()
private const val VIEW_DEMO_TEXT_COLOR = 0xFF202124.toInt()

/** Pure demo-document input, kept separate from Activity and RecyclerView lifecycle code. */
internal fun tiqianViewDemoParagraph(
    index: Int,
    textSize: Float,
    lineHeight: Float,
): CjkTextContent = CjkTextContent(
    text = tiqianViewDemoParagraphSource(index),
    textStyle = TextStyle(fontSize = textSize),
    paragraphStyle = ParagraphStyle(lineHeight = lineHeight),
    textColor = VIEW_DEMO_TEXT_COLOR,
)

/** The article paragraph as platform rich text; the View frontend lowers it on submission. */
internal fun tiqianViewDemoParagraphSource(index: Int): Spanned {
    val source = SpannableStringBuilder()
    source.append("第${index + 1}段：中文正文并不只是把字依次画在屏幕上。")
    source.appendSpanned("粗体", StyleSpan(Typeface.BOLD))
    source.append("、")
    source.appendSpanned("italic", StyleSpan(Typeface.ITALIC))
    source.append("、删除线和中西混排 OpenType 都会共同参与断行与绘制。")
    val linkStart = source.length
    source.append("这是第${index + 1}个链接")
    source.setSpan(
        URLSpan("https://example.com/$index"),
        linkStart,
        source.length,
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
    )
    source.setSpan(
        ForegroundColorSpan(VIEW_DEMO_LINK_COLOR),
        linkStart,
        source.length,
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
    )
    source.append("，后文继续补足一段真实文章常见的长度，用于观察首轮布局、滚动绘制和暖态重放。")
    return source
}

private fun SpannableStringBuilder.appendSpanned(text: String, span: Any) {
    val start = length
    append(text)
    setSpan(span, start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
}
