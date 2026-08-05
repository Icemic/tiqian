package org.tiqian.shaping.nativefont

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.fonts.SystemFonts
import android.graphics.text.TextRunShaper
import android.os.Build
import android.os.SystemClock
import android.text.TextPaint
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.tiqian.clreq.ClreqProfile
import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LayoutInput
import org.tiqian.core.ParagraphStyle
import org.tiqian.core.TextRange
import org.tiqian.core.TextStyle
import org.tiqian.core.TiqianTextContent
import org.tiqian.font.FontCandidate
import org.tiqian.font.FontDecision
import org.tiqian.font.FontRole
import org.tiqian.layout.ExplainableStubParagraphLayoutEngine
import org.tiqian.layout.LookaheadLineBreaker
import org.tiqian.shaping.ShapingInput
import org.tiqian.shaping.android.AndroidFontMetricsResolver
import org.tiqian.shaping.android.AndroidPaintTextShaper
import org.tiqian.shaping.android.AndroidTypefaceResolver
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AndroidNativeFontBackendTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun publicSystemVariableFontWeightsReplayDistinctOutlines() {
        if (Build.VERSION.SDK_INT < 29) return
        val availableFonts = SystemFonts.getAvailableFonts().toList()
        val catalog = PublicSystemFontsCatalog.createOrNull(availableFonts) ?: return
        val variableCjkFaces = catalog.faceSpecs.filter { spec ->
            FontRole.CjkText in spec.roles && "wght" in spec.variationAxes
        }
        val regularSpec = variableCjkFaces.firstOrNull { it.weight == 400 } ?: return
        val boldSpec = variableCjkFaces.firstOrNull { it.weight == 700 } ?: return
        assertEquals(400f, regularSpec.variationAxes["wght"])
        assertEquals(700f, boldSpec.variationAxes["wght"])

        val report = TiqianAndroidFontBackend.install(context, catalog)
        val regularDescriptor = report.faces.first { descriptor ->
            FontRole.CjkText in descriptor.roles && descriptor.weight == 400
        }
        val boldDescriptor = report.faces.first { descriptor ->
            FontRole.CjkText in descriptor.roles && descriptor.weight == 700
        }
        assertNotEquals(regularDescriptor.id, boldDescriptor.id)
        assertEquals(400f, regularDescriptor.variationAxes["wght"])
        assertEquals(700f, boldDescriptor.variationAxes["wght"])

        val shaper = AndroidNativeTextShaper(context)
        val regular = shaper.shape(input("永", FontRole.CjkText, 64f, fontWeight = 400))
        val bold = shaper.shape(input("永", FontRole.CjkText, 64f, fontWeight = 700))
        assertNotEquals(
            regular.glyphRuns.single().glyphs.single().renderFontKey,
            bold.glyphRuns.single().glyphs.single().renderFontKey,
        )
        val regularInk = replayInkPixels(regular.glyphRuns.single().glyphs, 64f)
        val boldInk = replayInkPixels(bold.glyphRuns.single().glyphs, 64f)
        assertTrue(
            boldInk > regularInk * 1.08f,
            "Expected wght=700 to produce materially more ink than wght=400; regular=$regularInk bold=$boldInk",
        )

        val latinFaces = catalog.faceSpecs.filter { spec -> FontRole.LatinText in spec.roles }
        val latinRegular = latinFaces.firstOrNull { it.weight == 400 } ?: return
        val latinBold = latinFaces.firstOrNull { it.weight == 700 } ?: return
        val reversedCatalog = assertNotNull(
            PublicSystemFontsCatalog.createOrNull(availableFonts.reversed()),
        )
        val reversedLatinFaces = reversedCatalog.faceSpecs.filter { spec -> FontRole.LatinText in spec.roles }
        val reversedLatinRegular = assertNotNull(reversedLatinFaces.firstOrNull { it.weight == 400 })
        val reversedLatinBold = assertNotNull(reversedLatinFaces.firstOrNull { it.weight == 700 })
        assertEquals(latinRegular.source.label, reversedLatinRegular.source.label)
        assertEquals(latinRegular.variationAxes, reversedLatinRegular.variationAxes)
        assertEquals(latinBold.source.label, reversedLatinBold.source.label)
        assertEquals(latinBold.variationAxes, reversedLatinBold.variationAxes)
        assertEquals(700f, latinBold.variationAxes["wght"])
        assertEquals(
            latinRegular.variationAxes.filterKeys { it != "wght" },
            latinBold.variationAxes.filterKeys { it != "wght" },
            "Changing weight must preserve every other variation coordinate",
        )
        if (latinRegular.familyAliases.any { "roboto" in it }) {
            assertEquals(
                100f,
                latinRegular.variationAxes["wdth"] ?: 100f,
                "The generic sans face must not resolve to Roboto Condensed",
            )
        }
        val latinRegularDescriptor = assertNotNull(report.faces.firstOrNull { descriptor ->
            FontRole.LatinText in descriptor.roles && descriptor.weight == latinRegular.weight
        }, report.toString())
        val latinBoldDescriptor = assertNotNull(report.faces.firstOrNull { descriptor ->
            FontRole.LatinText in descriptor.roles && descriptor.weight == latinBold.weight
        }, report.toString())
        assertNotEquals(latinRegularDescriptor.id, latinBoldDescriptor.id)

        val latinRegularShape = shaper.shape(input("H", FontRole.LatinText, 64f, fontWeight = 400))
        val latinBoldShape = shaper.shape(input("H", FontRole.LatinText, 64f, fontWeight = 700))
        val platformPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 64f
            typeface = Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), 400, false)
        }
        assertTrue(
            abs(latinRegularShape.clusters.single().advance - platformPaint.measureText("H")) <= 1f,
            "Native regular sans width must match Android sans-serif; " +
                "native=${latinRegularShape.clusters.single().advance} " +
                "platform=${platformPaint.measureText("H")} axes=${latinRegular.variationAxes}",
        )
        platformPaint.typeface = Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), 700, false)
        assertTrue(
            abs(latinBoldShape.clusters.single().advance - platformPaint.measureText("H")) <= 1f,
            "Native bold sans width must match Android sans-serif; " +
                "native=${latinBoldShape.clusters.single().advance} " +
                "platform=${platformPaint.measureText("H")} axes=${latinBold.variationAxes}",
        )
        val latinRegularInk = replayInkPixels(latinRegularShape.glyphRuns.single().glyphs, 64f)
        val latinBoldInk = replayInkPixels(latinBoldShape.glyphRuns.single().glyphs, 64f)
        assertTrue(
            latinBoldInk > latinRegularInk * 1.08f,
            "Expected Latin wght=700 to produce materially more ink than wght=400; " +
                "regular=$latinRegularInk bold=$latinBoldInk",
        )
    }

    @Test
    fun controlledBytesShapeMetricsInkAndOutlineReplay() {
        val report = TiqianAndroidFontBackend.capabilityReport(context)
        assertTrue(report.canReplayFromControlledBytes, report.toString())
        assertTrue(report.backend.contains("harfbuzz="), report.backend)
        assertTrue(report.backend.contains("freetype="), report.backend)

        val shaper = AndroidNativeTextShaper(context)
        val cjk = shaper.shape(input("中文。", FontRole.CjkText, 32f))
        assertEquals("HarfBuzz", cjk.decisions.single().source)
        assertEquals(0, cjk.decisions.single().missingGlyphs)
        assertTrue(cjk.clusters.single().advance > 80f)
        assertTrue(cjk.glyphRuns.single().glyphs.all { it.renderFontKey?.startsWith("tiqian-font:sha256:") == true })
        assertTrue(cjk.glyphRuns.single().glyphs.all { it.bounds != null })

        val metrics = AndroidNativeFontMetricsResolver(context).resolve(
            org.tiqian.font.FontMetricsRequest(
                fontKey = "cjk-primary",
                fontSize = 32f,
                role = FontRole.CjkText,
                locale = "zh-Hans",
                faceSelectionText = "中",
            ),
        )
        assertTrue(metrics.ascent > 0f)
        assertTrue(metrics.descent >= 0f)
        assertNotNull(metrics.typoAscent)

        val bitmap = Bitmap.createBitmap(160, 80, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() }
        assertTrue(
            AndroidNativeGlyphReplay.drawGlyphs(
                canvas = canvas,
                glyphs = cjk.glyphRuns.single().glyphs,
                originX = 8f,
                originY = 48f,
                fontSize = 32f,
                paint = paint,
            ),
        )
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertTrue(pixels.any { it != 0 }, "FreeType outline replay produced no ink")
    }

    @Test
    fun cjkLoclAndHaltEvidenceComesFromTheSameFace() {
        val shaper = AndroidNativeTextShaper(context)
        val punctuation = shaper.shape(input("。", FontRole.CjkPunctuation, 32f))
        val glyph = punctuation.glyphRuns.single().glyphs.single()
        assertTrue("locl=1" in punctuation.glyphRuns.single().openTypeFeatures)
        assertNotNull(glyph.haltAdvance)
        assertTrue(glyph.haltAdvance!! < punctuation.clusters.single().advance)
        assertEquals(punctuation.decisions.single().resolvedFace, glyph.renderFontKey)

        val dash = shaper.shape(input("—", FontRole.CjkPunctuation, 32f))
        assertEquals("Hani", dash.decisions.single().script)
        assertTrue(dash.decisions.single().featureEvidence?.contains("locl=1") == true)
        assertEquals(0, dash.decisions.single().missingGlyphs)
    }

    @Test
    fun nativeBackendLaysOutMixedLongBodyWithinBoundedTime() {
        val shaper = AndroidNativeTextShaper(context)
        val metrics = AndroidNativeFontMetricsResolver(context)
        val engine = ExplainableStubParagraphLayoutEngine(
            lineBreaker = LookaheadLineBreaker(),
            textShaper = shaper,
            fontMetricsResolver = metrics,
            clreqProfileResolver = { ClreqProfile.MainlandHorizontal },
        )
        val paragraph = buildString {
            repeat(80) {
                append("提椠以同一字体字节处理中文……English typography——与链接 https://example.com/a-b。")
            }
        }
        val start = SystemClock.elapsedRealtimeNanos()
        val result = engine.layout(
            LayoutInput(
                content = TiqianTextContent(paragraph),
                textStyle = TextStyle(fontSize = 28f, locale = "zh-Hans"),
                paragraphStyle = ParagraphStyle(),
                constraints = LayoutConstraints(maxWidth = 560f),
            ),
        )
        val elapsedMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000
        assertTrue(result.lines.size > 100)
        assertTrue(result.glyphRuns.isNotEmpty())
        assertTrue(elapsedMs < 20_000, "80-repeat long paragraph took ${elapsedMs}ms")
        android.util.Log.i(
            "TiqianNativePerf",
            "sdk=${Build.VERSION.SDK_INT} chars=${paragraph.length} longBodyMs=$elapsedMs " +
                "lines=${result.lines.size} glyphRuns=${result.glyphRuns.size}",
        )
    }

    @Test
    fun nativeAndApi31AdapterAgreeForTheSameFontBytes() {
        if (Build.VERSION.SDK_INT < 31) return
        val fontFile = cjkFontFile() ?: return
        val ttcIndex = if (fontFile.name.endsWith(".ttc")) 2 else 0
        TiqianAndroidFontBackend.install(
            context,
            AndroidFontCatalog.host(
                listOf(
                    AndroidFontFaceSpec(
                        source = AndroidFontSource.bytes(fontFile.readBytes(), "comparison:${fontFile.absolutePath}"),
                        collectionIndex = ttcIndex,
                        familyAliases = setOf("sans", "sans-serif", "comparison-cjk"),
                        roles = FontRole.entries.toSet(),
                    ),
                ),
            ),
        )
        val typeface = Typeface.Builder(fontFile).setTtcIndex(ttcIndex).build()
        val resolver = object : AndroidTypefaceResolver {
            override fun resolve(
                role: FontRole,
                fontFamilies: List<String>,
                fontWeight: Int,
                italic: Boolean,
            ): Typeface = typeface

            override fun resolve(input: ShapingInput): Typeface = typeface
        }
        val native = AndroidNativeTextShaper(context)
        val platform = AndroidPaintTextShaper(typefaceResolver = resolver)
        for ((text, role) in listOf("中文" to FontRole.CjkText, "English" to FontRole.LatinText, "。" to FontRole.CjkPunctuation)) {
            val input = input(text, role, 32f)
            val nativeResult = native.shape(input)
            val platformResult = platform.shape(input)
            assertTrue(
                abs(nativeResult.clusters.single().advance - platformResult.clusters.single().advance) <= 1.25f,
                "$text advance native=${nativeResult.clusters.single().advance} platform=${platformResult.clusters.single().advance}",
            )
            assertEquals(
                platformResult.glyphRuns.single().glyphs.map { it.id },
                nativeResult.glyphRuns.single().glyphs.map { it.id },
                "$text glyph ids",
            )
            val nativeInk = nativeResult.glyphRuns.single().glyphs.mapNotNull { it.bounds }
            val platformInk = platformResult.glyphRuns.single().glyphs.mapNotNull { it.bounds }
            assertEquals(platformInk.size, nativeInk.size, "$text ink count")
        }

        val style = TextStyle(fontSize = 32f, locale = "zh-Hans")
        val text = "中文……English——中文。"
        val input = LayoutInput(
            content = TiqianTextContent(text),
            textStyle = style,
            constraints = LayoutConstraints(maxWidth = 224f),
        )
        val nativeEngine = ExplainableStubParagraphLayoutEngine(
            textShaper = native,
            fontMetricsResolver = AndroidNativeFontMetricsResolver(context),
        )
        val platformEngine = ExplainableStubParagraphLayoutEngine(
            textShaper = platform,
            // Hold metrics constant so this is a shaping/layout geometry comparison.
            fontMetricsResolver = AndroidNativeFontMetricsResolver(context),
        )
        val nativeLayout = nativeEngine.layout(input)
        val platformLayout = platformEngine.layout(input)
        assertEquals(platformLayout.lines.map { it.range }, nativeLayout.lines.map { it.range })
        assertEquals(platformLayout.lines.size, nativeLayout.lines.size)
        nativeLayout.lines.zip(platformLayout.lines).forEach { (a, b) ->
            assertTrue(abs(a.visualWidth - b.visualWidth) <= 1.5f, "line width native=${a.visualWidth} platform=${b.visualWidth}")
        }
    }

    /**
     * PlatformResolvedFaceEvidence：我们为 CJK / 西文选的 face，是不是这台设备真正会拿来
     * 渲染的那一个。
     *
     * `SystemFonts.getAvailableFonts()` 是无序集合，丢掉了具名家族归属与 fallback 次序
     * （见 `AndroidFontCatalog.selectGenericSans` 的注释），所以 API 29+ 的 catalog 只能按
     * `localeList` + 文件名打分猜。OEM 常常只把自家字体插进具名 `sans-serif`（西文位），
     * 而 `lang="zh-Hans"` 的 fallback 链仍指向 Noto——猜测与平台实际解析就此分叉，
     * 表现为「西文换了字体、中文还是 Noto」，且分叉是静默的。
     *
     * 唯一的真值是让平台自己 shape 一遍，再读回 `PositionedGlyphs.getFont(i).file`。
     * 这里在 AOSP 上立基线；OEM 设备上同一断言会把分叉抓成失败，而不是默默用错字体。
     *
     * 不能复用 glyph 的 `renderFontKey`：Han context 下它按 `NoGlyphReplayInHanContext`
     * 被有意置 null，那是因为 glyph id 不可重放，不是字体身份不可用——所以这里直接取证。
     */
    @Test
    fun platformResolvedFacesMatchTheFacesWeSelect() {
        if (Build.VERSION.SDK_INT < 31) return
        val catalog = PublicSystemFontsCatalog.createOrNull() ?: return

        // 正文语言必须显式给到 paint：fallback 链对 zh-Hans / ja / ko 的取舍就在这里分岔，
        // 用宿主默认 locale 探出来的不是提椠正文实际要的那条链。
        fun platformFaces(text: String): List<String> {
            val paint = TextPaint().apply {
                textSize = 32f
                typeface = Typeface.DEFAULT
                textLocale = Locale.SIMPLIFIED_CHINESE
            }
            val shaped =
                TextRunShaper.shapeTextRun(text, 0, text.length, 0, text.length, 0f, 0f, false, paint)
            assertTrue(shaped.glyphCount() > 0, "platform produced no glyphs for '$text'")
            return (0 until shaped.glyphCount())
                .map { index ->
                    assertNotNull(
                        shaped.getFont(index).file,
                        "platform face for '$text' glyph $index reports no file",
                    ).absolutePath
                }
                .distinct()
        }

        fun selectedFace(role: FontRole): String = assertNotNull(
            catalog.faceSpecs
                .firstOrNull { spec -> role in spec.roles && spec.weight == 400 && !spec.italic }
                ?.source?.label
                ?.removePrefix("SystemFonts:")
                ?.substringBefore('#'),
            "catalog has no upright 400 face for $role",
        )

        for ((text, role) in listOf("中文" to FontRole.CjkText, "English" to FontRole.LatinText)) {
            val platform = platformFaces(text)
            val selected = selectedFace(role)
            assertTrue(
                selected in platform,
                "$role divergence for '$text': catalog selected $selected but the platform renders " +
                    "it with ${platform.joinToString()} — the catalog is guessing a face this device " +
                    "does not actually use",
            )
        }
    }

    private fun input(
        text: String,
        role: FontRole,
        fontSize: Float,
        fontWeight: Int = 400,
    ): ShapingInput =
        ShapingInput(
            text = text,
            range = TextRange(0, text.length),
            style = TextStyle(fontSize = fontSize, locale = "zh-Hans", fontWeight = fontWeight),
            fontDecision = FontDecision(
                range = TextRange(0, text.length),
                candidate = FontCandidate("test-$role", "sans-serif", role),
                role = role,
                reason = "NativeInstrumentationFixture",
            ),
        )

    private fun replayInkPixels(glyphs: List<org.tiqian.core.Glyph>, fontSize: Float): Int {
        val bitmap = Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() }
        assertTrue(
            AndroidNativeGlyphReplay.drawGlyphs(
                canvas = Canvas(bitmap),
                glyphs = glyphs,
                originX = 20f,
                originY = 80f,
                fontSize = fontSize,
                paint = paint,
            ),
        )
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.count { pixel -> (pixel ushr 24) >= 0x40 }
    }

    private fun cjkFontFile(): File? = listOf(
        File("/system/fonts/NotoSansCJK-Regular.ttc"),
        File("/system/fonts/NotoSansSC-Regular.otf"),
        File("/system/fonts/NotoSansCJKsc-Regular.otf"),
    ).firstOrNull(File::isFile)
}
