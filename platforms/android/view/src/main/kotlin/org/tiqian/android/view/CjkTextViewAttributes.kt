package org.tiqian.android.view

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import org.tiqian.clreq.ClreqProfile

/** Attribute and theme lowering for [CjkTextView], kept separate from its render/interaction path. */
internal fun CjkTextView.readAttributes(
    attrs: AttributeSet?,
    defStyleAttr: Int,
    defStyleRes: Int,
) {
    if (attrs == null) return
    val values = context.obtainStyledAttributes(
        attrs,
        R.styleable.CjkTextView,
        defStyleAttr,
        defStyleRes,
    )
    try {
        maxLines = values.getInt(R.styleable.CjkTextView_android_maxLines, maxLines)
            .coerceAtLeast(1)
        minLines = values.getInt(R.styleable.CjkTextView_android_minLines, minLines)
            .coerceAtLeast(1)
        textIsSelectable = values.getBoolean(
            R.styleable.CjkTextView_android_textIsSelectable,
            textIsSelectable,
        )
        overflow = when (values.getInt(R.styleable.CjkTextView_cjkOverflow, 0)) {
            1 -> CjkTextOverflow.Visible
            else -> CjkTextOverflow.Clip
        }
        clreqProfile = when (values.getInt(R.styleable.CjkTextView_cjkProfile, 0)) {
            1 -> ClreqProfile.TaiwanHorizontal
            2 -> ClreqProfile.HongKongHorizontal
            else -> ClreqProfile.MainlandHorizontal
        }
        val current = content
        val text = values.getText(R.styleable.CjkTextView_android_text)?.toString()
            ?: current.content.text
        val fontSize = values.getDimension(
            R.styleable.CjkTextView_android_textSize,
            current.textStyle.fontSize,
        )
        val lineHeight = if (values.hasValue(R.styleable.CjkTextView_android_lineHeight)) {
            values.getDimension(R.styleable.CjkTextView_android_lineHeight, 0f)
        } else {
            current.paragraphStyle.lineHeight
        }
        textColors = values.getColorStateList(R.styleable.CjkTextView_android_textColor)
        content = current.copy(
            content = current.content.copy(text = text),
            textStyle = current.textStyle.copy(fontSize = fontSize),
            paragraphStyle = current.paragraphStyle.copy(lineHeight = lineHeight),
        )
    } finally {
        values.recycle()
    }
}

internal fun Context.resolveCjkThemeColor(attribute: Int, fallback: Int): Int {
    val value = TypedValue()
    return if (theme.resolveAttribute(attribute, value, true)) {
        if (value.resourceId != 0) {
            runCatching { getColor(value.resourceId) }.getOrDefault(fallback)
        } else value.data
    } else fallback
}
