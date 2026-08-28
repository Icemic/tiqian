package org.tiqian.diagnostics

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontStyle
import android.graphics.fonts.SystemFonts
import android.graphics.text.PositionedGlyphs
import android.graphics.text.TextRunShaper
import android.os.Build
import android.system.Os
import android.text.TextPaint
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.ceil
import kotlin.math.max

data class CollectedFontEvidence(
    val bundleFile: File,
    val summary: String,
)

/**
 * Independent Android platform evidence collector.
 *
 * It records what the public platform APIs expose and never compares those observations with a
 * Tiqian font-selection implementation. Unsupported API levels remain explicit unknowns; they are
 * never converted into empty lists that can accidentally compare equal.
 */
object AndroidFontEvidenceCollector {
    // internal (was private): read by the report/manifest extension functions in
    // AndroidFontEvidenceReport.kt.
    internal const val SCHEMA_VERSION = 1
    private const val TEXT_SIZE_PX = 32f

    private data class Probe(
        val id: String,
        val text: String,
        val label: String,
        val rtl: Boolean = false,
    )

    private enum class TypefaceConstruction(val wireValue: String) {
        Default("default"),
        NamedStyle("named-style"),
        ExactWeight("exact-weight"),
        PaintVariation("paint-variation"),
    }

    private data class TypefaceRequest(
        val id: String,
        val construction: TypefaceConstruction,
        val family: String?,
        val familyOrigins: List<String> = emptyList(),
        val legacyStyle: Int = Typeface.NORMAL,
        val requestedWeight: Int? = null,
        val requestedItalic: Boolean? = null,
        val variationSettings: String? = null,
    )

    private data class TypefaceBuild(
        val status: EvidenceStatus,
        val typeface: Typeface? = null,
        val reason: String? = null,
        val error: Throwable? = null,
    )

    // internal (was private): consumed by the report/manifest extension functions in
    // AndroidFontEvidenceReport.kt.
    internal data class Observation(
        val status: EvidenceStatus,
        val value: Map<String, Any?>,
    )

    private data class RasterCapture(
        val evidence: Map<String, Any?>,
        val pngBytes: ByteArray? = null,
    )

    // internal (was private): consumed by summaryMarkdown in AndroidFontEvidenceReport.kt.
    internal data class ConfigArtifact(
        val path: String,
        val exists: Boolean,
        val readable: Boolean,
        val bytes: ByteArray?,
        val readError: Throwable?,
        val index: FontConfigIndex?,
        val parseError: Throwable?,
    )

    private val locales = listOf(
        "zh-Hans-CN",
        "zh-Hant-TW",
        "zh-Hant-HK",
        "ja-JP",
        "ko-KR",
        "en-US",
    )

    private val coreProbes = listOf(
        Probe("cjk-body", "中文", "汉字正文"),
        Probe("locale-sensitive-han", "骨直海角刃真", "语言敏感汉字"),
        Probe("mixed-cjk-latin", "中文，。……——English", "中文、中文标点与拉丁混排"),
        Probe("cjk-punctuation-context", "中文「括号」，句号。中文", "中文上下文中的点号"),
        Probe("ascii-punctuation-context", "中文, punctuation. English", "中文上下文中的 ASCII 标点"),
        Probe("shared-punctuation-context", "中文—…“text”中文", "中西共用标点"),
        Probe("latin", "English Typography", "拉丁正文"),
        Probe("greek", "Ελληνικά", "希腊文"),
        Probe("cyrillic", "Привет", "西里尔文"),
        Probe("japanese", "日本語ひらがな", "日文"),
        Probe("korean", "한국어", "韩文"),
        Probe("arabic", "العربية", "阿拉伯文", rtl = true),
        Probe("emoji", "😀✈️", "emoji 与变体选择符"),
        Probe("regional-indicators", "🇨🇳", "区域指示符序列"),
        Probe("cjk-extension-b", "𠮷𡈽", "CJK 扩展 B"),
    )

    private val familyProbes = coreProbes.filter { probe ->
        probe.id in setOf("cjk-body", "mixed-cjk-latin", "locale-sensitive-han", "emoji")
    }

    private val styleProbes = coreProbes.filter { probe ->
        probe.id in setOf("cjk-body", "latin", "mixed-cjk-latin")
    }

    private val platformFamilyNames = listOf(
        "sans-serif",
        "sans-serif-medium",
        "sans-serif-light",
        "sans-serif-black",
        "sans-serif-condensed",
        "sans-serif-thin",
        "sans-serif-smallcaps",
        "serif",
        "monospace",
        "serif-monospace",
        "casual",
        "cursive",
    )

    private val fontConfigPaths = listOf(
        "/system/etc/fonts.xml",
        "/system/etc/fonts_base.xml",
        "/system/etc/fonts_customization.xml",
        "/system/etc/system_fonts.xml",
        "/system/etc/fallback_fonts.xml",
        "/system/etc/font_fallback.xml",
        "/product/etc/fonts_customization.xml",
        "/vendor/etc/fonts_customization.xml",
        "/system_ext/etc/fonts_customization.xml",
        "/data/system/theme/fonts.xml",
        "/data/themes/0/fonts/fonts.xml",
        "/data/skin/fonts/fonts.xml",
    )

    private val fontDirectories = listOf(
        "/system/fonts",
        "/product/fonts",
        "/vendor/fonts",
        "/system_ext/fonts",
        "/system/font",
        "/data/system/theme/fonts",
        "/data/themes/0/fonts",
        "/data/themes/fonts",
        "/data/skin/fonts",
        "/data/fonts",
        "/data/fonts/files",
    )

    @Synchronized
    fun collect(outputDirectory: File): CollectedFontEvidence {
        val capturedAt = utcTimestamp()
        val configArtifacts = collectFontConfigs()
        val familyOrigins = familyOrigins(configArtifacts)
        val fileHashCache = mutableMapOf<String, Map<String, Any?>>()
        val renderEntries = linkedMapOf<String, ByteArray>()
        val observations = collectShapeObservations(familyOrigins, fileHashCache, renderEntries) +
            collectCoverageObservations()

        val entries = linkedMapOf<String, ByteArray>()
        entries["observations.jsonl"] = observations
            .joinToString(separator = "\n", postfix = "\n") { observation -> EvidenceJson.encode(observation.value) }
            .toByteArray(Charsets.UTF_8)
        entries["font-config.json"] = (EvidenceJson.encode(fontConfigJson(configArtifacts)) + "\n")
            .toByteArray(Charsets.UTF_8)
        entries["system-fonts.json"] = (EvidenceJson.encode(systemFontsJson(fileHashCache)) + "\n")
            .toByteArray(Charsets.UTF_8)
        entries["font-directories.json"] = (EvidenceJson.encode(fontDirectoriesJson()) + "\n")
            .toByteArray(Charsets.UTF_8)
        entries.putAll(renderEntries)

        configArtifacts.forEach { artifact ->
            artifact.bytes?.let { bytes ->
                entries["raw/font-config/${artifact.path.trimStart('/')}"] = bytes
            }
        }

        val summary = summaryMarkdown(capturedAt, observations, configArtifacts, renderEntries.size)
        entries["summary.md"] = summary.toByteArray(Charsets.UTF_8)
        val manifest = manifestJson(capturedAt, observations, entries)

        outputDirectory.mkdirs()
        val outputFile = File(outputDirectory, bundleFileName(capturedAt))
        writeZip(outputFile, linkedMapOf("manifest.json" to manifest.toByteArray(Charsets.UTF_8)) + entries)
        return CollectedFontEvidence(bundleFile = outputFile, summary = summary)
    }

    private fun collectShapeObservations(
        familyOrigins: Map<String, List<String>>,
        fileHashCache: MutableMap<String, Map<String, Any?>>,
        renderEntries: MutableMap<String, ByteArray>,
    ): List<Observation> = buildList {
        val defaultRequest = TypefaceRequest(
            id = "default",
            construction = TypefaceConstruction.Default,
            family = null,
        )
        locales.forEach { locale ->
            coreProbes.forEach { probe ->
                add(shapeObservation(defaultRequest, probe, locale, fileHashCache, renderEntries))
            }
        }

        familyOrigins.forEach { (family, origins) ->
            val request = TypefaceRequest(
                id = "family-${stableToken(family)}",
                construction = TypefaceConstruction.NamedStyle,
                family = family,
                familyOrigins = origins,
            )
            familyProbes.forEach { probe ->
                add(shapeObservation(request, probe, "zh-Hans-CN", fileHashCache, renderEntries))
            }
        }

        if (Build.VERSION.SDK_INT >= 28) {
            listOf(100, 300, 400, 500, 700, 900).forEach { weight ->
                val request = TypefaceRequest(
                    id = "sans-weight-$weight",
                    construction = TypefaceConstruction.ExactWeight,
                    family = "sans-serif",
                    familyOrigins = listOf("platform-generic"),
                    requestedWeight = weight,
                    requestedItalic = false,
                )
                styleProbes.forEach { probe ->
                    add(shapeObservation(request, probe, "zh-Hans-CN", fileHashCache, renderEntries))
                }
            }
        } else {
            listOf(
                "sans-legacy-normal" to Typeface.NORMAL,
                "sans-legacy-bold" to Typeface.BOLD,
            ).forEach { (id, style) ->
                val request = TypefaceRequest(
                    id = id,
                    construction = TypefaceConstruction.NamedStyle,
                    family = "sans-serif",
                    familyOrigins = listOf("platform-generic"),
                    legacyStyle = style,
                )
                styleProbes.forEach { probe ->
                    add(shapeObservation(request, probe, "zh-Hans-CN", fileHashCache, renderEntries))
                }
            }
            listOf(100, 300, 400, 500, 700, 900).forEach { weight ->
                val request = TypefaceRequest(
                    id = "sans-weight-$weight",
                    construction = TypefaceConstruction.ExactWeight,
                    family = "sans-serif",
                    familyOrigins = listOf("platform-generic"),
                    requestedWeight = weight,
                    requestedItalic = false,
                )
                styleProbes.forEach { probe ->
                    add(shapeObservation(request, probe, "zh-Hans-CN", fileHashCache, renderEntries))
                }
            }
        }

        val italicRequest = TypefaceRequest(
            id = "sans-legacy-italic",
            construction = TypefaceConstruction.NamedStyle,
            family = "sans-serif",
            familyOrigins = listOf("platform-generic"),
            legacyStyle = Typeface.ITALIC,
        )
        styleProbes.forEach { probe ->
            add(shapeObservation(italicRequest, probe, "zh-Hans-CN", fileHashCache, renderEntries))
        }

        listOf(
            "wght-300" to "'wght' 300",
            "wght-700" to "'wght' 700",
            "wdth-75" to "'wdth' 75",
            "wdth-100" to "'wdth' 100",
            "wdth-125" to "'wdth' 125",
            "opsz-12" to "'opsz' 12",
            "opsz-72" to "'opsz' 72",
        ).forEach { (id, settings) ->
            val request = TypefaceRequest(
                id = "sans-axis-$id",
                construction = TypefaceConstruction.PaintVariation,
                family = "sans-serif",
                familyOrigins = listOf("platform-generic"),
                variationSettings = settings,
            )
            styleProbes.forEach { probe ->
                add(shapeObservation(request, probe, "zh-Hans-CN", fileHashCache, renderEntries))
            }
        }
    }

    private fun shapeObservation(
        request: TypefaceRequest,
        probe: Probe,
        localeTag: String,
        fileHashCache: MutableMap<String, Map<String, Any?>>,
        renderEntries: MutableMap<String, ByteArray>,
    ): Observation {
        val id = "shape.${request.id}.${stableToken(localeTag)}.${probe.id}"
        val requestJson = requestJson(request, localeTag)
        val base = linkedMapOf<String, Any?>(
            "id" to id,
            "kind" to "platform-shape",
            "probe" to probeJson(probe),
            "request" to requestJson,
        )
        val built = buildTypeface(request)
        if (built.status != EvidenceStatus.Observed || built.typeface == null) {
            base["status"] = built.status.wireValue
            base["reason"] = built.reason
            built.error?.let { error -> base["error"] = errorJson(error) }
            return Observation(built.status, base)
        }

        if (request.variationSettings != null && Build.VERSION.SDK_INT < 26) {
            base["status"] = EvidenceStatus.Unsupported.wireValue
            base["reason"] = "Paint font variation settings require API 26+"
            return Observation(EvidenceStatus.Unsupported, base)
        }

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = TEXT_SIZE_PX
            textLocale = Locale.forLanguageTag(localeTag)
            typeface = built.typeface
            color = Color.BLACK
        }
        val variationAccepted = request.variationSettings?.let { settings ->
            runCatching { paint.setFontVariationSettings(settings) }.getOrNull()
        }

        base["status"] = EvidenceStatus.Observed.wireValue
        base["paint"] = linkedMapOf(
            "textSizePx" to TEXT_SIZE_PX,
            "flags" to paint.flags,
            "variationSettingsAccepted" to variationAccepted,
        )
        base["runMetrics"] = runMetricsEvidence(paint, probe)
        base["glyphReadback"] = glyphEvidence(paint, probe, fileHashCache)
        val raster = rasterEvidence(paint, probe)
        val renderEntry = raster.pngBytes?.let { bytes ->
            "renders/$id.png".also { name -> renderEntries[name] = bytes }
        }
        base["raster"] = LinkedHashMap(raster.evidence).apply {
            put("pngEntry", renderEntry)
        }
        return Observation(EvidenceStatus.Observed, base)
    }

    private fun requestJson(request: TypefaceRequest, localeTag: String): Map<String, Any?> = linkedMapOf(
        "construction" to request.construction.wireValue,
        "family" to request.family,
        "familyOrigins" to request.familyOrigins,
        "legacyStyle" to legacyStyleName(request.legacyStyle),
        "requestedWeight" to request.requestedWeight,
        "requestedItalic" to request.requestedItalic,
        "variationSettings" to request.variationSettings,
        "locale" to localeTag,
    )

    private fun buildTypeface(request: TypefaceRequest): TypefaceBuild = when (request.construction) {
        TypefaceConstruction.Default -> TypefaceBuild(EvidenceStatus.Observed, Typeface.DEFAULT)
        TypefaceConstruction.NamedStyle,
        TypefaceConstruction.PaintVariation,
        -> runCatching {
            Typeface.create(request.family, request.legacyStyle)
        }.fold(
            onSuccess = { typeface -> TypefaceBuild(EvidenceStatus.Observed, typeface) },
            onFailure = { error -> TypefaceBuild(EvidenceStatus.Error, error = error) },
        )
        TypefaceConstruction.ExactWeight -> {
            if (Build.VERSION.SDK_INT < 28) {
                TypefaceBuild(
                    status = EvidenceStatus.Unsupported,
                    reason = "Exact Typeface weight requests require API 28+; no legacy weight was substituted",
                )
            } else {
                runCatching {
                    val base = Typeface.create(request.family, Typeface.NORMAL)
                    Typeface.create(base, requireNotNull(request.requestedWeight), request.requestedItalic == true)
                }.fold(
                    onSuccess = { typeface -> TypefaceBuild(EvidenceStatus.Observed, typeface) },
                    onFailure = { error -> TypefaceBuild(EvidenceStatus.Error, error = error) },
                )
            }
        }
    }

    private fun runMetricsEvidence(paint: TextPaint, probe: Probe): Map<String, Any?> = runCatching {
        val text = probe.text
        val metrics = paint.fontMetrics
        linkedMapOf<String, Any?>(
            "status" to EvidenceStatus.Observed.wireValue,
            "measureText" to paint.measureText(text),
            "runAdvance" to paint.getRunAdvance(
                text,
                0,
                text.length,
                0,
                text.length,
                probe.rtl,
                text.length,
            ),
            "paintFontMetrics" to linkedMapOf(
                "top" to metrics.top,
                "ascent" to metrics.ascent,
                "descent" to metrics.descent,
                "bottom" to metrics.bottom,
                "leading" to metrics.leading,
            ),
        )
    }.getOrElse { error -> errorEnvelope(error) }

    private fun glyphEvidence(
        paint: TextPaint,
        probe: Probe,
        fileHashCache: MutableMap<String, Map<String, Any?>>,
    ): Map<String, Any?> {
        if (Build.VERSION.SDK_INT < 31) {
            return unsupportedEnvelope("Per-glyph font and position readback requires API 31+", 31)
        }
        return runCatching { glyphEvidenceApi31(paint, probe, fileHashCache) }
            .getOrElse(::errorEnvelope)
    }

    @RequiresApi(31)
    private fun glyphEvidenceApi31(
        paint: TextPaint,
        probe: Probe,
        fileHashCache: MutableMap<String, Map<String, Any?>>,
    ): Map<String, Any?> {
        val text = probe.text
        val shaped = TextRunShaper.shapeTextRun(
            text,
            0,
            text.length,
            0,
            text.length,
            0f,
            0f,
            probe.rtl,
            paint,
        )
        val glyphs = (0 until shaped.glyphCount()).map { index ->
            val font = shaped.getFont(index)
            val glyphId = shaped.getGlyphId(index)
            val bounds = RectF()
            val nominalAdvance = font.getGlyphBounds(glyphId, paint, bounds)
            linkedMapOf<String, Any?>(
                "index" to index,
                "glyphId" to glyphId,
                "x" to shaped.getGlyphX(index),
                "y" to shaped.getGlyphY(index),
                "font" to fontJson(font, fileHashCache),
                "fontGlyphAdvance" to nominalAdvance,
                "fontGlyphBounds" to rectJson(bounds),
                "styleApplication" to styleApplicationJson(shaped, index),
            )
        }
        return linkedMapOf(
            "status" to EvidenceStatus.Observed.wireValue,
            "sourceMapping" to linkedMapOf(
                "status" to EvidenceStatus.Unsupported.wireValue,
                "reason" to "PositionedGlyphs does not expose glyph-to-UTF-16 cluster mapping",
            ),
            "glyphCount" to shaped.glyphCount(),
            "advance" to shaped.advance,
            "ascent" to shaped.ascent,
            "descent" to shaped.descent,
            "offsetX" to shaped.offsetX,
            "offsetY" to shaped.offsetY,
            "glyphs" to glyphs,
        )
    }

    @RequiresApi(31)
    private fun fontJson(
        font: Font,
        fileHashCache: MutableMap<String, Map<String, Any?>>,
    ): Map<String, Any?> = linkedMapOf(
        "sourceIdentifier" to font.sourceIdentifier,
        "file" to font.file?.let { file -> fileIdentity(file, fileHashCache) },
        "buffer" to if (font.file == null) fontBufferJson(font) else null,
        "ttcIndex" to font.ttcIndex,
        "weight" to font.style.weight,
        "slant" to fontSlantName(font.style.slant),
        "axes" to font.axes.orEmpty()
            .associate { axis -> axis.tag to axis.styleValue }
            .toSortedMap(),
        "locales" to font.localeList.toLanguageTags(),
    )

    @RequiresApi(31)
    private fun styleApplicationJson(shaped: PositionedGlyphs, index: Int): Map<String, Any?> {
        if (Build.VERSION.SDK_INT < 35) {
            return unsupportedEnvelope("Direct fake-style and style-override readback requires API 35+", 35)
        }
        return styleApplicationJsonApi35(shaped, index)
    }

    @RequiresApi(35)
    private fun styleApplicationJsonApi35(shaped: PositionedGlyphs, index: Int): Map<String, Any?> {
        fun overrideOrNull(value: Float): Float? =
            value.takeUnless { candidate -> candidate == PositionedGlyphs.NO_OVERRIDE }
        return linkedMapOf(
            "status" to EvidenceStatus.Observed.wireValue,
            "fakeBold" to shaped.getFakeBold(index),
            "fakeItalic" to shaped.getFakeItalic(index),
            "weightOverride" to overrideOrNull(shaped.getWeightOverride(index)),
            "italicOverride" to overrideOrNull(shaped.getItalicOverride(index)),
        )
    }

    private fun fileIdentity(
        file: File,
        fileHashCache: MutableMap<String, Map<String, Any?>>,
    ): Map<String, Any?> = fileHashCache.getOrPut(file.absolutePath) {
        val hashResult = if (file.canRead()) runCatching { sha256(file.readBytes()) } else null
        linkedMapOf(
            "path" to file.absolutePath,
            "exists" to file.exists(),
            "readable" to file.canRead(),
            "sizeBytes" to file.takeIf(File::exists)?.length(),
            "sha256" to hashResult?.getOrNull(),
            "hashError" to hashResult?.exceptionOrNull()?.let(::errorJson),
        )
    }

    private fun rasterEvidence(paint: TextPaint, probe: Probe): RasterCapture = runCatching {
        val fontMetrics = paint.fontMetrics
        val padding = 24
        val advance = max(1f, paint.measureText(probe.text))
        val width = ceil(advance + padding * 2f).toInt().coerceIn(1, 4096)
        val height = ceil(fontMetrics.bottom - fontMetrics.top + padding * 2f).toInt().coerceIn(1, 1024)
        val baseline = padding - fontMetrics.top
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            canvas.drawTextRun(
                probe.text,
                0,
                probe.text.length,
                0,
                probe.text.length,
                padding.toFloat(),
                baseline,
                probe.rtl,
                paint,
            )
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            var left = width
            var top = height
            var right = -1
            var bottom = -1
            var inkPixels = 0
            pixels.forEachIndexed { index, pixel ->
                if (pixel != Color.WHITE) {
                    val x = index % width
                    val y = index / width
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x)
                    bottom = maxOf(bottom, y)
                    inkPixels++
                }
            }
            val pngOutput = ByteArrayOutputStream()
            val pngBytes = if (bitmap.compress(Bitmap.CompressFormat.PNG, 100, pngOutput)) {
                pngOutput.toByteArray()
            } else {
                null
            }
            RasterCapture(
                evidence = linkedMapOf<String, Any?>(
                    "status" to EvidenceStatus.Observed.wireValue,
                    "renderer" to "software-bitmap-canvas-drawTextRun-white-background",
                    "width" to width,
                    "height" to height,
                    "baseline" to baseline,
                    "argbSha256" to sha256Pixels(pixels),
                    "inkPixelCount" to inkPixels,
                    "inkBounds" to if (right >= left && bottom >= top) {
                        linkedMapOf("left" to left, "top" to top, "right" to right + 1, "bottom" to bottom + 1)
                    } else {
                        null
                    },
                ),
                pngBytes = pngBytes,
            )
        } finally {
            bitmap.recycle()
        }
    }.getOrElse { error -> RasterCapture(errorEnvelope(error)) }

    private fun collectCoverageObservations(): List<Observation> {
        val typefaces = listOf(
            "default" to Typeface.DEFAULT,
            "sans-serif" to Typeface.create("sans-serif", Typeface.NORMAL),
            "serif" to Typeface.SERIF,
            "monospace" to Typeface.MONOSPACE,
        )
        return typefaces.flatMap { (name, typeface) ->
            coreProbes.map { probe ->
                val base = linkedMapOf<String, Any?>(
                    "id" to "coverage.$name.${probe.id}",
                    "kind" to "paint-has-glyph",
                    "probe" to probeJson(probe),
                    "request" to linkedMapOf("family" to name),
                )
                runCatching {
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        textSize = TEXT_SIZE_PX
                        this.typeface = typeface
                    }
                    base["status"] = EvidenceStatus.Observed.wireValue
                    base["sequenceHasGlyph"] = paint.hasGlyph(probe.text)
                    base["codePoints"] = probe.text.codePointUnits().map { unit ->
                        linkedMapOf(
                            "text" to unit,
                            "codePoint" to "U+${unit.codePointAt(0).toString(16).uppercase()}",
                            "hasGlyph" to paint.hasGlyph(unit),
                        )
                    }
                    Observation(EvidenceStatus.Observed, base)
                }.getOrElse { error ->
                    base["status"] = EvidenceStatus.Error.wireValue
                    base["error"] = errorJson(error)
                    Observation(EvidenceStatus.Error, base)
                }
            }
        }
    }

    private fun collectFontConfigs(): List<ConfigArtifact> = fontConfigPaths.map { path ->
        val file = File(path)
        if (!file.exists()) {
            return@map ConfigArtifact(path, exists = false, readable = false, null, null, null, null)
        }
        if (!file.canRead()) {
            return@map ConfigArtifact(path, exists = true, readable = false, null, null, null, null)
        }
        val read = runCatching { file.readBytes() }
        val bytes = read.getOrNull()
        val parsed = bytes?.let { content -> runCatching { FontConfigIndexParser.parse(content) } }
        ConfigArtifact(
            path = path,
            exists = true,
            readable = true,
            bytes = bytes,
            readError = read.exceptionOrNull(),
            index = parsed?.getOrNull(),
            parseError = parsed?.exceptionOrNull(),
        )
    }

    private fun familyOrigins(configArtifacts: List<ConfigArtifact>): Map<String, List<String>> {
        val origins = linkedMapOf<String, MutableSet<String>>()
        platformFamilyNames.forEach { family ->
            origins.getOrPut(family, ::linkedSetOf).add("platform-generic")
        }
        configArtifacts.forEach { artifact ->
            artifact.index?.declaredNames?.forEach { family ->
                origins.getOrPut(family, ::linkedSetOf).add("config:${artifact.path}")
            }
        }
        return origins.toSortedMap().mapValues { (_, value) -> value.sorted() }
    }

    private fun fontConfigJson(configArtifacts: List<ConfigArtifact>): Map<String, Any?> = linkedMapOf(
        "schemaVersion" to SCHEMA_VERSION,
        "meaning" to "Parsed declarations only; not a claim about Minikin's effective runtime fallback graph",
        "sources" to configArtifacts.map { artifact ->
            linkedMapOf<String, Any?>(
                "path" to artifact.path,
                "access" to when {
                    !artifact.exists -> linkedMapOf(
                        "status" to EvidenceStatus.Observed.wireValue,
                        "exists" to false,
                    )
                    !artifact.readable -> linkedMapOf(
                        "status" to EvidenceStatus.Unsupported.wireValue,
                        "exists" to true,
                        "reason" to "The file exists but the application sandbox cannot read it",
                    )
                    artifact.readError != null -> linkedMapOf(
                        "status" to EvidenceStatus.Error.wireValue,
                        "exists" to true,
                        "error" to errorJson(artifact.readError),
                    )
                    else -> linkedMapOf(
                        "status" to EvidenceStatus.Observed.wireValue,
                        "exists" to true,
                        "sizeBytes" to artifact.bytes?.size,
                        "sha256" to artifact.bytes?.let(::sha256),
                        "rawEntry" to "raw/font-config/${artifact.path.trimStart('/')}",
                    )
                },
                "parse" to when {
                    artifact.index != null -> linkedMapOf(
                        "status" to EvidenceStatus.Observed.wireValue,
                        "declarations" to artifact.index.toJsonValue(),
                    )
                    artifact.parseError != null -> linkedMapOf(
                        "status" to EvidenceStatus.Error.wireValue,
                        "error" to errorJson(artifact.parseError),
                    )
                    else -> linkedMapOf(
                        "status" to EvidenceStatus.Unsupported.wireValue,
                        "reason" to "No readable bytes were available to parse",
                    )
                },
            )
        },
    )

    private fun systemFontsJson(fileHashCache: MutableMap<String, Map<String, Any?>>): Map<String, Any?> {
        if (Build.VERSION.SDK_INT < 29) {
            return linkedMapOf(
                "status" to EvidenceStatus.Unsupported.wireValue,
                "reason" to "SystemFonts enumeration requires API 29+",
                "minimumApi" to 29,
            )
        }
        return runCatching { systemFontsJsonApi29(fileHashCache) }.getOrElse(::errorEnvelope)
    }

    @RequiresApi(29)
    private fun systemFontsJsonApi29(
        fileHashCache: MutableMap<String, Map<String, Any?>>,
    ): Map<String, Any?> {
        val fonts = SystemFonts.getAvailableFonts().sortedWith(
            compareBy(
                { font: Font -> font.file?.absolutePath.orEmpty() },
                Font::getTtcIndex,
                { font -> font.style.weight },
                { font -> font.style.slant },
                { font -> font.axes.orEmpty().joinToString { axis -> "${axis.tag}=${axis.styleValue}" } },
                { font -> font.localeList.toLanguageTags() },
            ),
        )
        return linkedMapOf(
            "status" to EvidenceStatus.Observed.wireValue,
            "apiContract" to "Unordered set of fonts; no named-family membership or fallback order",
            "count" to fonts.size,
            "fonts" to fonts.map { font ->
                linkedMapOf(
                    "sourceIdentifier" to if (Build.VERSION.SDK_INT >= 31) font.sourceIdentifier else null,
                    "file" to font.file?.let { file -> fileIdentity(file, fileHashCache) },
                    "buffer" to if (font.file == null) fontBufferJson(font) else null,
                    "ttcIndex" to font.ttcIndex,
                    "weight" to font.style.weight,
                    "slant" to fontSlantName(font.style.slant),
                    "axes" to font.axes.orEmpty()
                        .associate { axis -> axis.tag to axis.styleValue }
                        .toSortedMap(),
                    "locales" to font.localeList.toLanguageTags(),
                )
            },
        )
    }

    @RequiresApi(29)
    private fun fontBufferJson(font: Font): Map<String, Any?> = runCatching {
        val buffer = font.buffer.duplicate().apply { position(0) }
        val bytes = ByteArray(buffer.remaining()).also(buffer::get)
        linkedMapOf<String, Any?>(
            "status" to EvidenceStatus.Observed.wireValue,
            "sizeBytes" to bytes.size,
            "sha256" to sha256(bytes),
        )
    }.getOrElse(::errorEnvelope)

    private fun fontDirectoriesJson(): Map<String, Any?> = linkedMapOf(
        "directories" to fontDirectories.map { path ->
            val directory = File(path)
            when {
                !directory.exists() -> linkedMapOf(
                    "path" to path,
                    "status" to EvidenceStatus.Observed.wireValue,
                    "exists" to false,
                )
                !directory.isDirectory -> linkedMapOf(
                    "path" to path,
                    "status" to EvidenceStatus.Error.wireValue,
                    "exists" to true,
                    "reason" to "Path exists but is not a directory",
                )
                else -> {
                    val files = directory.listFiles()
                    if (files == null) {
                        linkedMapOf(
                            "path" to path,
                            "status" to EvidenceStatus.Unsupported.wireValue,
                            "exists" to true,
                            "reason" to "Application sandbox cannot list this directory",
                        )
                    } else {
                        linkedMapOf(
                            "path" to path,
                            "status" to EvidenceStatus.Observed.wireValue,
                            "exists" to true,
                            "items" to files.sortedBy(File::getName).map { file ->
                                linkedMapOf(
                                    "name" to file.name,
                                    "isFile" to file.isFile,
                                    "readable" to file.canRead(),
                                    "sizeBytes" to file.takeIf(File::isFile)?.length(),
                                )
                            },
                        )
                    }
                }
            }
        },
    )

    private fun probeJson(probe: Probe): Map<String, Any?> = linkedMapOf(
        "id" to probe.id,
        "text" to probe.text,
        "label" to probe.label,
        "direction" to if (probe.rtl) "rtl" else "ltr",
    )

    private fun rectJson(rect: RectF): Map<String, Any?> = linkedMapOf(
        "left" to rect.left,
        "top" to rect.top,
        "right" to rect.right,
        "bottom" to rect.bottom,
    )

    private fun errorEnvelope(error: Throwable): Map<String, Any?> = linkedMapOf(
        "status" to EvidenceStatus.Error.wireValue,
        "error" to errorJson(error),
    )

    private fun errorJson(error: Throwable): Map<String, Any?> = linkedMapOf(
        "type" to error::class.java.name,
        "message" to error.message,
    )

    private fun unsupportedEnvelope(reason: String, minimumApi: Int): Map<String, Any?> = linkedMapOf(
        "status" to EvidenceStatus.Unsupported.wireValue,
        "reason" to reason,
        "minimumApi" to minimumApi,
    )

    private fun legacyStyleName(style: Int): String = when (style) {
        Typeface.NORMAL -> "normal"
        Typeface.BOLD -> "bold"
        Typeface.ITALIC -> "italic"
        Typeface.BOLD_ITALIC -> "bold-italic"
        else -> "unknown-$style"
    }

    private fun fontSlantName(slant: Int): String = when (slant) {
        FontStyle.FONT_SLANT_UPRIGHT -> "upright"
        FontStyle.FONT_SLANT_ITALIC -> "italic"
        else -> "unknown-$slant"
    }

    private fun String.codePointUnits(): List<String> = buildList {
        var index = 0
        while (index < length) {
            val codePoint = codePointAt(index)
            val count = Character.charCount(codePoint)
            add(substring(index, index + count))
            index += count
        }
    }

    private fun sha256Pixels(pixels: IntArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = ByteArray(4)
        pixels.forEach { pixel ->
            bytes[0] = (pixel ushr 24).toByte()
            bytes[1] = (pixel ushr 16).toByte()
            bytes[2] = (pixel ushr 8).toByte()
            bytes[3] = pixel.toByte()
            digest.update(bytes)
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun utcTimestamp(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())

    private fun bundleFileName(capturedAt: String): String {
        val raw = "tiqian-android-font-evidence-${Build.MANUFACTURER}-${Build.MODEL}-api${Build.VERSION.SDK_INT}-$capturedAt"
        return raw.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".zip"
    }

    private fun writeZip(file: File, entries: Map<String, ByteArray>) {
        val temporary = File.createTempFile("${file.name}.", ".tmp", file.parentFile)
        try {
            ZipOutputStream(FileOutputStream(temporary)).use { zip ->
                entries.forEach { (name, bytes) ->
                    val entry = ZipEntry(name).apply { time = 0L }
                    zip.putNextEntry(entry)
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            // Same-directory rename publishes only a complete archive, even if Activity recreation
            // starts another collection while a host-side automation is watching this directory.
            Os.rename(temporary.absolutePath, file.absolutePath)
        } finally {
            temporary.delete()
        }
    }
}
