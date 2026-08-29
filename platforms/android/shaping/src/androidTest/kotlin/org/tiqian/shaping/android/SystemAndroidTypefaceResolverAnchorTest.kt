package org.tiqian.shaping.android

import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.text.TextRunShaper
import android.text.TextPaint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import org.junit.Test
import org.junit.runner.RunWith
import org.tiqian.font.FontRole
import java.util.Locale
import kotlin.test.assertEquals

/**
 * `PlatformDefaultHanFaceReadback` contract: for every requested (weight,
 * italic) the CJK anchor must select the same physical `Font` instance the
 * platform default fallback chain selects — file, TTC index, variation axes
 * and style included. A single 400-only anchor pins variable-font devices to
 * the regular instance (real weights collapse into fake bold), which this
 * fixture turns red.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 31)
class SystemAndroidTypefaceResolverAnchorTest {

    private val resolver = SystemAndroidTypefaceResolver()

    @Test
    fun cjkAnchorMatchesPlatformDefaultPerWeightAndItalic() {
        for (weight in listOf(300, 400, 500, 700)) {
            for (italic in listOf(false, true)) {
                val resolved = shapedHanFont(
                    resolver.resolve(FontRole.CjkText, emptyList(), weight, italic),
                )
                val platform = shapedHanFont(
                    Typeface.create(Typeface.DEFAULT, weight, italic),
                )
                assertEquals(
                    platform.identity(),
                    resolved.identity(),
                    "weight=$weight italic=$italic",
                )
            }
        }
    }

    private fun shapedHanFont(typeface: Typeface): Font {
        val paint = TextPaint().apply {
            textSize = 32f
            textLocale = Locale.forLanguageTag("zh-Hans")
            this.typeface = typeface
        }
        val shaped = TextRunShaper.shapeTextRun("中", 0, 1, 0, 1, 0f, 0f, false, paint)
        assertEquals(1, shaped.glyphCount())
        return shaped.getFont(0)
    }

    private fun Font.identity(): String = buildString {
        append(file?.absolutePath ?: sourceIdentifier.toString())
        append('#')
        append(ttcIndex)
        append(":w")
        append(style.weight)
        append(":s")
        append(style.slant)
        append(':')
        append(axes.orEmpty().joinToString(",") { axis -> "${axis.tag}=${axis.styleValue}" })
    }
}
