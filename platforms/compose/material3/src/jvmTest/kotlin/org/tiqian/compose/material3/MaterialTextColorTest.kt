package org.tiqian.compose.material3

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class MaterialTextColorTest {
    @Test
    fun explicitColorWins() {
        assertEquals(
            Color.Red,
            resolveMaterialTextColor(Color.Red, Color.Green, Color.Blue),
        )
    }

    @Test
    fun styleColorWinsWhenExplicitColorIsUnspecified() {
        assertEquals(
            Color.Green,
            resolveMaterialTextColor(Color.Unspecified, Color.Green, Color.Blue),
        )
    }

    @Test
    fun contentColorIsUsedWhenNoTextColorIsSpecified() {
        assertEquals(
            Color.Blue,
            resolveMaterialTextColor(Color.Unspecified, Color.Unspecified, Color.Blue),
        )
    }

    @Test
    fun unstyledUrlReceivesMaterialLinkStyle() {
        val expected = materialLinkStyles()
        val text = buildAnnotatedString {
            withLink(LinkAnnotation.Url("https://example.com")) { append("链接") }
        }

        val styled = text.withDefaultMaterialLinkStyles(expected)
        val link = styled.getLinkAnnotations(0, styled.length).single().item

        assertEquals(expected, (link as LinkAnnotation.Url).styles)
    }

    @Test
    fun unstyledClickableReceivesMaterialLinkStyle() {
        val expected = materialLinkStyles()
        val text = buildAnnotatedString {
            withLink(LinkAnnotation.Clickable("footnote", linkInteractionListener = {})) {
                append("注释")
            }
        }

        val styled = text.withDefaultMaterialLinkStyles(expected)
        val link = styled.getLinkAnnotations(0, styled.length).single().item

        assertEquals(expected, (link as LinkAnnotation.Clickable).styles)
    }

    @Test
    fun authoredLinkStyleIsPreserved() {
        val authored = TextLinkStyles(style = SpanStyle(color = Color.Magenta))
        val text = buildAnnotatedString {
            withLink(LinkAnnotation.Url("https://example.com", styles = authored)) {
                append("链接")
            }
        }

        val styled = text.withDefaultMaterialLinkStyles(materialLinkStyles())
        val link = styled.getLinkAnnotations(0, styled.length).single().item

        assertSame(authored, (link as LinkAnnotation.Url).styles)
    }

    private fun materialLinkStyles(): TextLinkStyles = TextLinkStyles(
        style = SpanStyle(
            color = Color.Blue,
            textDecoration = TextDecoration.Underline,
        ),
    )
}
