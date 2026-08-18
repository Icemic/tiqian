package org.tiqian.compose.material3

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import org.tiqian.compose.CjkInlineBackground
import org.tiqian.compose.CjkInlineDecoration
import org.tiqian.compose.CjkInlineObject
import org.tiqian.compose.DefaultCjkTextParagraphStyle
import org.tiqian.compose.ParagraphMeasurer
import org.tiqian.compose.rememberParagraphMeasurer
import org.tiqian.core.LayoutResult
import org.tiqian.core.ParagraphStyle
import org.tiqian.compose.CjkText as FoundationCjkText

/**
 * Material 3-aware Tiqian text entry. Its parameter precedence matches Material [androidx.compose.material3.Text]:
 * an explicit [color] wins, then [style], then [LocalContentColor]. All layout and drawing remain
 * owned by the Foundation-neutral Tiqian Compose frontend.
 */
@Composable
fun CjkText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    style: TextStyle = LocalTextStyle.current,
    paragraphStyle: ParagraphStyle = DefaultCjkTextParagraphStyle,
    measurer: ParagraphMeasurer = rememberParagraphMeasurer(),
    precomputedLayout: LayoutResult? = null,
    onTextLayout: (LayoutResult) -> Unit = {},
) {
    CjkText(
        text = AnnotatedString(text),
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        textDecoration = textDecoration,
        textAlign = textAlign,
        lineHeight = lineHeight,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
        style = style,
        paragraphStyle = paragraphStyle,
        measurer = measurer,
        precomputedLayout = precomputedLayout,
        onTextLayout = onTextLayout,
    )
}

/** Material 3-aware Tiqian text entry for renderer-produced rich text. */
@Composable
fun CjkText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    style: TextStyle = LocalTextStyle.current,
    paragraphStyle: ParagraphStyle = DefaultCjkTextParagraphStyle,
    inlineObjects: List<CjkInlineObject> = emptyList(),
    inlineDecorations: List<CjkInlineDecoration> = emptyList(),
    inlineBackgrounds: List<CjkInlineBackground> = emptyList(),
    measurer: ParagraphMeasurer = rememberParagraphMeasurer(),
    precomputedLayout: LayoutResult? = null,
    onTextLayout: (LayoutResult) -> Unit = {},
) {
    val materialLinkStyles = rememberMaterialTextLinkStyles()
    val textWithMaterialLinkStyles = remember(text, materialLinkStyles) {
        text.withDefaultMaterialLinkStyles(materialLinkStyles)
    }
    FoundationCjkText(
        text = textWithMaterialLinkStyles,
        modifier = modifier,
        color = resolveMaterialTextColor(
            explicitColor = color,
            styleColor = style.color,
            contentColor = LocalContentColor.current,
        ),
        fontSize = fontSize,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        textDecoration = textDecoration,
        textAlign = textAlign,
        lineHeight = lineHeight,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
        style = style,
        paragraphStyle = paragraphStyle,
        inlineObjects = inlineObjects,
        inlineDecorations = inlineDecorations,
        inlineBackgrounds = inlineBackgrounds,
        measurer = measurer,
        precomputedLayout = precomputedLayout,
        onTextLayout = onTextLayout,
    )
}

internal fun resolveMaterialTextColor(
    explicitColor: Color,
    styleColor: Color,
    contentColor: Color,
): Color = when {
    explicitColor != Color.Unspecified -> explicitColor
    styleColor != Color.Unspecified -> styleColor
    else -> contentColor
}

@Composable
private fun rememberMaterialTextLinkStyles(): TextLinkStyles {
    val primaryColor = MaterialTheme.colorScheme.primary
    return remember(primaryColor) {
        TextLinkStyles(
            style = SpanStyle(
                color = primaryColor,
                textDecoration = TextDecoration.Underline,
            ),
        )
    }
}

@Suppress("UNCHECKED_CAST")
internal fun AnnotatedString.withDefaultMaterialLinkStyles(
    linkStyles: TextLinkStyles,
): AnnotatedString = mapAnnotations { range ->
    when (val link = range.item) {
        is LinkAnnotation.Url -> if (link.styles == null) {
            (range as AnnotatedString.Range<LinkAnnotation.Url>).copy(
                item = link.copy(styles = linkStyles),
            )
        } else {
            range
        }
        is LinkAnnotation.Clickable -> if (link.styles == null) {
            (range as AnnotatedString.Range<LinkAnnotation.Clickable>).copy(
                item = link.copy(styles = linkStyles),
            )
        } else {
            range
        }
        else -> range
    }
}
