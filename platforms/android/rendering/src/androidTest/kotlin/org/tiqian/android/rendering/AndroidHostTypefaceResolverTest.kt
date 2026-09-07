package org.tiqian.android.rendering

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LayoutInput
import org.tiqian.core.ParagraphStyle
import org.tiqian.core.TextStyle
import org.tiqian.core.TiqianTextContent
import org.tiqian.core.toReplayIndex
import org.tiqian.font.FontRole
import org.tiqian.shaping.ShapingInput
import org.tiqian.shaping.android.AndroidPositionedGlyphFontRegistry
import org.tiqian.shaping.android.AndroidTypefaceResolver
import org.tiqian.shaping.android.AndroidTypefaceResolverRegistry
import org.tiqian.shaping.android.SystemAndroidTypefaceResolver
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AndroidHostTypefaceResolverTest {
    @Test
    fun installedResolverServesBothMeasurementAndDrawing() {
        val serif = File("/system/fonts/NotoSerif-Regular.ttf")
        assumeTrue("No NotoSerif on this image", serif.isFile)
        val input = LayoutInput(
            content = TiqianTextContent("Hamburgefonstiv 中文"),
            textStyle = TextStyle(fontSize = 32f),
            paragraphStyle = ParagraphStyle(),
            constraints = LayoutConstraints(maxWidth = 2000f),
        )
        val defaultLayout = AndroidParagraphMeasurer().measure(input)
        val host = CountingResolver(Typeface.createFromFile(serif), SystemAndroidTypefaceResolver())
        try {
            AndroidTypefaceResolverRegistry.install(host)
            val layout = AndroidParagraphMeasurer().measure(input)
            assertTrue(host.resolutions > 0, "the installed resolver must serve shaping")
            val measured = host.resolutions

            // Platform clusters are per grapheme; the leading "H" sits in the first cluster of both layouts.
            val latin = layout.clusters.first { it.range.start == 0 }
            val defaultLatin = defaultLayout.clusters.first { it.range.start == 0 }
            assertNotEquals(defaultLatin.advance, latin.advance, "NotoSerif must change the Latin advance")
            if (Build.VERSION.SDK_INT >= 31) {
                val key = checkNotNull(layout.glyphRuns.first { it.range.start == 0 }.glyphs.first().renderFontKey)
                assertEquals(serif, AndroidPositionedGlyphFontRegistry.fontFor(key)?.file)
            }

            val bitmap = Bitmap.createBitmap(1200, 80, Bitmap.Config.ARGB_8888)
            AndroidParagraphRenderer().use { renderer ->
                renderer.draw(Canvas(bitmap), layout, layout.toReplayIndex(emptyList()), 0xFF000000.toInt())
            }
            assertTrue(host.resolutions > measured, "the renderer must draw with the installed resolver")
        } finally {
            AndroidTypefaceResolverRegistry.reset()
        }
    }

    /** Serves Latin from one host file and everything else from the system resolver. */
    private class CountingResolver(
        private val latin: Typeface,
        private val system: AndroidTypefaceResolver,
    ) : AndroidTypefaceResolver {
        var resolutions = 0

        override fun resolve(
            role: FontRole,
            fontFamilies: List<String>,
            fontWeight: Int,
            italic: Boolean,
        ): Typeface {
            resolutions += 1
            return if (role == FontRole.LatinText) latin else system.resolve(role, fontFamilies, fontWeight, italic)
        }

        override fun resolve(input: ShapingInput): Typeface =
            resolve(input.fontDecision.role, input.style.fontFamilies, input.style.fontWeight, input.style.italic)
    }
}
