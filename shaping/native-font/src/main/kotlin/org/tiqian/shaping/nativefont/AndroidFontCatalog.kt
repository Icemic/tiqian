package org.tiqian.shaping.nativefont

import android.annotation.TargetApi
import android.content.Context
import android.graphics.fonts.Font
import android.graphics.fonts.FontStyle
import android.graphics.fonts.FontVariationAxis
import android.graphics.fonts.SystemFonts
import android.os.Build
import org.tiqian.font.FontRole
import org.tiqian.shaping.FontBackendCapabilityIssue
import org.tiqian.shaping.FontBackendCapabilityReport
import org.tiqian.shaping.FontFaceId
import org.tiqian.shaping.ReplayableFontCatalog
import org.tiqian.shaping.ReplayableFontFaceDescriptor
import org.tiqian.shaping.ReplayableFontFaceRequest
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs

/** Controlled bytes source accepted by the API 23+ native font backend. */
sealed class AndroidFontSource protected constructor(
    val label: String,
) {
    internal abstract fun readBytes(context: Context): ByteArray

    companion object {
        fun bytes(bytes: ByteArray, label: String = "host-byte-array"): AndroidFontSource =
            ByteArraySource(bytes.copyOf(), label)

        fun file(file: File, label: String = file.absolutePath): AndroidFontSource =
            FileSource(file, label)

        fun asset(path: String, label: String = "asset:$path"): AndroidFontSource =
            AssetSource(path, label)
    }

    private class ByteArraySource(
        private val bytes: ByteArray,
        label: String,
    ) : AndroidFontSource(label) {
        override fun readBytes(context: Context): ByteArray = bytes.copyOf()
    }

    private class FileSource(
        private val file: File,
        label: String,
    ) : AndroidFontSource(label) {
        override fun readBytes(context: Context): ByteArray = file.readBytes()
    }

    private class AssetSource(
        private val path: String,
        label: String,
    ) : AndroidFontSource(label) {
        override fun readBytes(context: Context): ByteArray = context.assets.open(path).use { it.readBytes() }
    }
}

/**
 * One physical host/system face. [roles] and [familyAliases] form an explicit
 * fallback catalog; the backend never asks Android to silently substitute a
 * different face after shaping.
 */
data class AndroidFontFaceSpec(
    val source: AndroidFontSource,
    val collectionIndex: Int = 0,
    val familyAliases: Set<String>,
    val roles: Set<FontRole>,
    val weight: Int = 400,
    val italic: Boolean = false,
    /** OpenType variation coordinates that identify the concrete face instance. */
    val variationAxes: Map<String, Float> = emptyMap(),
) {
    init {
        require(collectionIndex >= 0) { "collectionIndex must be non-negative" }
        require(familyAliases.isNotEmpty()) { "At least one family alias is required" }
        require(roles.isNotEmpty()) { "At least one font role is required" }
        require(weight in 1..1000) { "OpenType weight must be in 1..1000" }
        variationAxes.forEach { (tag, value) ->
            require(tag.length == 4 && tag.all { it.code in 0x20..0x7E }) {
                "OpenType variation axis tags must contain four printable ASCII characters: $tag"
            }
            require(value.isFinite()) { "OpenType variation axis $tag must be finite" }
        }
    }
}

data class AndroidFontCatalog(
    val faceSpecs: List<AndroidFontFaceSpec>,
    val sourceKind: String = "ExplicitHostFontCatalog",
    val declaredIssues: List<FontBackendCapabilityIssue> = emptyList(),
) {
    init {
        require(faceSpecs.isNotEmpty()) { "AndroidFontCatalog must declare at least one face" }
    }

    companion object {
        /**
         * Production host contract for API 23–28: package fonts as assets, files
         * or byte arrays and install this catalog before the first CjkText.
         */
        fun host(faceSpecs: List<AndroidFontFaceSpec>): AndroidFontCatalog =
            AndroidFontCatalog(faceSpecs = faceSpecs)
    }
}

/** Loaded face plus evidence about request matching; shared by shaping, metrics and replay. */
internal data class ResolvedNativeFontFace(
    val descriptor: ReplayableFontFaceDescriptor,
    val nativeFace: NativeFontFace,
    val exactFamily: Boolean,
    val exactStyle: Boolean,
    val coversSelectionText: Boolean,
)

private data class LoadedFace(
    val descriptor: ReplayableFontFaceDescriptor,
    val nativeFace: NativeFontFace,
)

private class LoadedAndroidFontCatalog(
    private val loadedFaces: List<LoadedFace>,
    override val capabilityReport: FontBackendCapabilityReport,
) : ReplayableFontCatalog {
    override val faces: List<ReplayableFontFaceDescriptor> = loadedFaces.map { it.descriptor }

    override fun resolve(request: ReplayableFontFaceRequest): ReplayableFontFaceDescriptor? =
        resolveNative(request)?.descriptor

    fun resolveNative(request: ReplayableFontFaceRequest): ResolvedNativeFontFace? {
        val roleCandidates = loadedFaces.filter { request.role in it.descriptor.roles }
        if (roleCandidates.isEmpty()) return null
        val preferred = request.preferredFamilies.map(::normaliseFamily).filter { it.isNotEmpty() }
        val exactFamilyCandidates = if (preferred.isEmpty()) {
            roleCandidates
        } else {
            roleCandidates.filter { face ->
                face.descriptor.familyAliases.any { normaliseFamily(it) in preferred }
            }
        }
        val familyPool = exactFamilyCandidates.ifEmpty { roleCandidates }
        val covered = familyPool.filter { it.nativeFace.hasGlyphs(request.selectionText) }
        val selected = covered.minWithOrNull(
            compareBy<LoadedFace>(
                { if (it.descriptor.italic == request.italic) 0 else 1 },
                { abs(it.descriptor.weight - request.weight) },
                { it.descriptor.id.value },
            ),
        ) ?: return null
        return ResolvedNativeFontFace(
            descriptor = selected.descriptor,
            nativeFace = selected.nativeFace,
            exactFamily = preferred.isEmpty() || selected in exactFamilyCandidates,
            exactStyle = selected.descriptor.italic == request.italic && selected.descriptor.weight == request.weight,
            coversSelectionText = true,
        )
    }
}

/**
 * Process-wide stable resource contract. Registered faces are retained so an
 * already-produced LayoutResult remains replayable after catalog changes.
 */
object TiqianAndroidFontBackend {
    private const val BackendName = "TiqianHarfBuzzFreeType"
    private val lock = Any()
    private val faceById = LinkedHashMap<FontFaceId, NativeFontFace>()

    @Volatile
    private var activeCatalog: LoadedAndroidFontCatalog? = null

    private val versionEvidence: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        NativeFontBridge.nativeVersions()
    }

    val nativeVersions: String
        get() = versionEvidence

    /** Install explicit host fonts. This report is diagnostic and never routes to a host renderer. */
    fun install(context: Context, catalog: AndroidFontCatalog): FontBackendCapabilityReport =
        synchronized(lock) {
            loadCatalog(context.applicationContext, catalog).also { activeCatalog = it }.capabilityReport
        }

    fun capabilityReport(context: Context): FontBackendCapabilityReport =
        ensureInstalled(context).capabilityReport

    internal fun resolveFace(
        context: Context,
        request: ReplayableFontFaceRequest,
    ): ResolvedNativeFontFace {
        val catalog = ensureInstalled(context)
        return catalog.resolveNative(request) ?: error(
            "MissingControlledFontFace: role=${request.role}; families=${request.preferredFamilies}; " +
                "install an AndroidFontCatalog before composing CjkText; report=${catalog.capabilityReport}",
        )
    }

    internal fun faceFor(renderFontKey: String): NativeFontFace? = synchronized(lock) {
        faceById[FontFaceId(renderFontKey)]
    }

    private fun ensureInstalled(context: Context): LoadedAndroidFontCatalog {
        activeCatalog?.let { return it }
        return synchronized(lock) {
            activeCatalog ?: loadCatalog(context.applicationContext, defaultCatalog()).also { activeCatalog = it }
        }
    }

    private fun defaultCatalog(): AndroidFontCatalog {
        if (Build.VERSION.SDK_INT >= 29) {
            PublicSystemFontsCatalog.createOrNull()?.let { return it }
        }
        return wellKnownSystemPathCatalog()
    }

    private fun loadCatalog(context: Context, catalog: AndroidFontCatalog): LoadedAndroidFontCatalog {
        val issues = catalog.declaredIssues.toMutableList()
        val loaded = mutableListOf<LoadedFace>()
        for (spec in catalog.faceSpecs) {
            val axes = spec.variationAxes.toSortedMap()
            val bytes = runCatching { spec.source.readBytes(context) }.getOrElse { error ->
                issues += FontBackendCapabilityIssue(
                    code = "FontSourceUnavailable",
                    detail = "${spec.source.label}: ${error::class.simpleName}: ${error.message}",
                )
                continue
            }
            val loadedFace = runCatching {
                val id = stableFaceId(bytes, spec.collectionIndex, axes)
                val native = faceById.getOrPut(id) {
                    val handle = NativeFontBridge.nativeRegisterFace(
                        bytes = bytes,
                        collectionIndex = spec.collectionIndex,
                        variationTags = axes.keys.map(::variationTag).toIntArray(),
                        variationValues = axes.values.toFloatArray(),
                    )
                    NativeFontFace(handle, NativeFontBridge.nativeUnitsPerEm(handle))
                }
                id to native
            }.getOrElse { error ->
                issues += FontBackendCapabilityIssue(
                    code = "FontFaceLoadFailed",
                    detail = "${spec.source.label}#${spec.collectionIndex}: ${error::class.simpleName}: ${error.message}",
                )
                continue
            }
            val (id, native) = loadedFace
            loaded += LoadedFace(
                descriptor = ReplayableFontFaceDescriptor(
                    id = id,
                    familyAliases = spec.familyAliases,
                    roles = spec.roles,
                    weight = spec.weight,
                    italic = spec.italic,
                    collectionIndex = spec.collectionIndex,
                    sourceLabel = spec.source.label,
                    variationAxes = axes,
                ),
                nativeFace = native,
            )
        }
        val roles = loaded.flatMapTo(linkedSetOf()) { it.descriptor.roles }
        val requiredRoles = setOf(FontRole.CjkText, FontRole.CjkPunctuation, FontRole.LatinText)
        val missingRoles = requiredRoles - roles
        if (missingRoles.isNotEmpty()) {
            issues += FontBackendCapabilityIssue(
                code = "MissingControlledFontFace",
                detail = "No loaded face covers roles ${missingRoles.joinToString()}",
            )
        }
        val report = FontBackendCapabilityReport(
            backend = "$BackendName;${NativeFontBridge.nativeVersions()}",
            sourceKind = catalog.sourceKind,
            faces = loaded.map { it.descriptor },
            issues = issues,
        )
        return LoadedAndroidFontCatalog(loaded, report)
    }

    private fun stableFaceId(
        bytes: ByteArray,
        collectionIndex: Int,
        variationAxes: Map<String, Float>,
    ): FontFaceId {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = digest.joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xFF) }
        val axes = variationAxes.entries.joinToString(",") { (tag, value) ->
            "$tag=${value.toRawBits().toUInt().toString(16)}"
        }
        return FontFaceId(
            buildString {
                append("tiqian-font:sha256:")
                append(hex)
                append(':')
                append(collectionIndex)
                if (axes.isNotEmpty()) {
                    append(":axes:")
                    append(axes)
                }
            },
        )
    }
}

private fun wellKnownSystemPathCatalog(): AndroidFontCatalog {
    val cjkRoles = setOf(
        FontRole.CjkText,
        FontRole.CjkPunctuation,
        FontRole.Symbol,
        FontRole.Emoji,
        FontRole.Unknown,
    )
    val latinFallbackRoles = setOf(
        FontRole.LatinText,
        FontRole.CjkPunctuation,
        FontRole.Symbol,
        FontRole.Unknown,
    )
    fun face(
        path: String,
        index: Int,
        aliases: Set<String>,
        roles: Set<FontRole>,
        weight: Int = 400,
        italic: Boolean = false,
    ) = AndroidFontFaceSpec(
        source = AndroidFontSource.file(File(path)),
        collectionIndex = index,
        familyAliases = aliases,
        roles = roles,
        weight = weight,
        italic = italic,
    )
    return AndroidFontCatalog(
        sourceKind = "ControlledWellKnownSystemPaths",
        declaredIssues = listOf(
            FontBackendCapabilityIssue(
                code = "HostFontCatalogRecommendedBelowApi29",
                detail = "API 23-28 cannot enumerate system fonts publicly; install asset/file/ByteArray faces for OEM-portable production use",
            ),
        ),
        faceSpecs = listOf(
            face(
                "/system/fonts/NotoSansCJK-Regular.ttc",
                2,
                setOf("sans", "sans-serif", "noto sans cjk sc"),
                cjkRoles,
            ),
            face(
                "/system/fonts/NotoSansCJK-Bold.ttc",
                2,
                setOf("sans", "sans-serif", "noto sans cjk sc"),
                cjkRoles,
                weight = 700,
            ),
            face(
                "/system/fonts/NotoSansSC-Regular.otf",
                0,
                setOf("sans", "sans-serif", "noto sans sc"),
                cjkRoles,
            ),
            face(
                "/system/fonts/Roboto-Regular.ttf",
                0,
                setOf("sans", "sans-serif", "roboto"),
                latinFallbackRoles,
            ),
            face(
                "/system/fonts/Roboto-Bold.ttf",
                0,
                setOf("sans", "sans-serif", "roboto"),
                latinFallbackRoles,
                weight = 700,
            ),
            face(
                "/system/fonts/Roboto-Italic.ttf",
                0,
                setOf("sans", "sans-serif", "roboto"),
                latinFallbackRoles,
                italic = true,
            ),
        ),
    )
}

@TargetApi(29)
internal object PublicSystemFontsCatalog {
    fun createOrNull(
        fonts: List<Font> = SystemFonts.getAvailableFonts().toList(),
    ): AndroidFontCatalog? = runCatching {
        if (fonts.isEmpty()) return null
        val upright = fonts.filter { it.style.slant == FontStyle.FONT_SLANT_UPRIGHT }
        val cjkRegular = upright.minByOrNull { cjkScore(it, targetWeight = 400) }
            ?.instantiateWeight(400)
        val cjkBold = upright.minByOrNull { cjkScore(it, targetWeight = 700) }
            ?.instantiateWeight(700)
        val latinRegularFont = selectGenericSans(upright, targetWeight = 400)
        val latinRegular = latinRegularFont?.instantiateWeight(400)
        val latinBold = latinRegularFont?.instantiateWeight(700)?.takeIf { it.weight == 700 }
            ?: selectGenericSans(upright, targetWeight = 700)?.instantiateWeight(700)
        val latinItalic = selectGenericSans(
            fonts.filter { it.style.slant == FontStyle.FONT_SLANT_ITALIC },
            targetWeight = 400,
        )
            ?.instantiateWeight(400)
        val cjkRoles = setOf(
            FontRole.CjkText,
            FontRole.CjkPunctuation,
            FontRole.Symbol,
            FontRole.Emoji,
            FontRole.Unknown,
        )
        val latinFallbackRoles = setOf(
            FontRole.LatinText,
            FontRole.CjkPunctuation,
            FontRole.Symbol,
            FontRole.Unknown,
        )
        val selected = listOfNotNull(
            cjkRegular?.let { it to cjkRoles },
            cjkBold?.let { it to cjkRoles },
            latinRegular?.let { it to latinFallbackRoles },
            latinBold?.let { it to latinFallbackRoles },
            latinItalic?.let { it to latinFallbackRoles },
        )
        val specs = selected
            .groupBy { (instance, _) -> instance.instanceKey() }
            .map { (_, selections) ->
                val instance = selections.first().first
                val font = instance.font
                val roles = selections.flatMapTo(linkedSetOf()) { it.second }
                val axes = instance.variationAxes
                AndroidFontFaceSpec(
                    source = AndroidFontSource.bytes(
                        fontBytes(font),
                        systemFontLabel(font, axes),
                    ),
                    collectionIndex = font.ttcIndex,
                    familyAliases = familyAliases(font),
                    roles = roles,
                    weight = instance.weight,
                    italic = instance.italic,
                    variationAxes = axes,
                )
            }
        if (specs.none { FontRole.CjkText in it.roles } || specs.none { FontRole.LatinText in it.roles }) return null
        AndroidFontCatalog(
            faceSpecs = specs,
            sourceKind = "AndroidPublicSystemFontsApi29",
        )
    }.getOrNull()

    private fun cjkScore(font: Font, targetWeight: Int): Int {
        val name = font.file?.name.orEmpty().lowercase()
        val languages = font.localeList.toLanguageTags().lowercase()
        val languageScore = when {
            "zh-hans" in languages -> 0
            "zh" in languages -> 100
            "cjk" in name || "sc" in name -> 200
            else -> 10_000
        }
        val sansScore = if ("sans" in name) 0 else 500
        return languageScore + sansScore + abs(font.style.weight - targetWeight)
    }

    /**
     * `SystemFonts.getAvailableFonts()` is an unordered set and loses named-family membership.
     * Roboto's normal and condensed aliases can therefore expose the same file and weight with
     * only `wdth` distinguishing them. Resolve generic sans deterministically, preferring the
     * registered normal width before weight proximity, then retain that instance's complete
     * reported coordinate set when deriving another weight.
     */
    private fun selectGenericSans(fonts: List<Font>, targetWeight: Int): Font? =
        fonts.minWithOrNull(
            compareBy<Font>(
                ::latinFamilyRank,
                ::genericSansWidthDistance,
                { font -> abs(font.style.weight - targetWeight) },
                { font -> font.file?.absolutePath.orEmpty() },
                Font::getTtcIndex,
                { font ->
                    font.variationAxes().entries.joinToString(",") { (tag, value) ->
                        "$tag=${value.toRawBits()}"
                    }
                },
            ),
        )

    private fun latinFamilyRank(font: Font): Int {
        val name = font.file?.name.orEmpty().lowercase()
        return when {
            "roboto" in name && "mono" !in name -> 0
            "notosans" in name && "cjk" !in name -> 1
            else -> 2
        }
    }

    private fun genericSansWidthDistance(font: Font): Float =
        abs((font.variationAxes()["wdth"] ?: 100f) - 100f)

    private fun fontBytes(font: Font): ByteArray {
        font.file?.takeIf(File::isFile)?.let { return it.readBytes() }
        val buffer = font.buffer.duplicate()
        buffer.position(0)
        return ByteArray(buffer.remaining()).also(buffer::get)
    }

    private fun familyAliases(font: Font): Set<String> {
        val name = font.file?.nameWithoutExtension.orEmpty().lowercase()
        return buildSet {
            add(name.ifEmpty { "system-${font.sourceIdentifier}" })
            when {
                "mono" in name -> addAll(listOf("mono", "monospace"))
                "serif" in name && "sans" !in name -> add("serif")
                else -> addAll(listOf("sans", "sans-serif"))
            }
        }
    }

    private fun Font.variationAxes(): Map<String, Float> =
        axes.orEmpty().associate { axis -> axis.tag to axis.styleValue }.toSortedMap()

    /**
     * Android may expose a variable family only at its default weight and use aliases for the
     * remaining weights. Materialize and retain the requested coordinates before exporting bytes;
     * [Font.getAxes] is not a complete record for alias-created instances on every Android release.
     */
    private fun Font.instantiateWeight(targetWeight: Int): SystemFontInstance {
        val currentAxes = variationAxes()
        if (style.weight == targetWeight && "wght" !in currentAxes) {
            return SystemFontInstance(
                font = this,
                variationAxes = currentAxes,
                weight = targetWeight,
                italic = style.slant == FontStyle.FONT_SLANT_ITALIC,
            )
        }
        if (currentAxes["wght"] == targetWeight.toFloat()) {
            return SystemFontInstance(
                font = this,
                variationAxes = currentAxes,
                weight = targetWeight,
                italic = style.slant == FontStyle.FONT_SLANT_ITALIC,
            )
        }
        val targetAxes = currentAxes.toMutableMap().apply {
            this["wght"] = targetWeight.toFloat()
        }.toSortedMap()
        val candidate = runCatching {
            val builder = file?.let { sourceFile -> Font.Builder(sourceFile) }
                ?: Font.Builder(buffer.duplicate().apply { position(0) })
            builder
                .setTtcIndex(ttcIndex)
                .setWeight(targetWeight)
                .setSlant(style.slant)
                .setFontVariationSettings(
                    targetAxes.map { (tag, value) -> FontVariationAxis(tag, value) }.toTypedArray(),
                )
                .build()
        }.getOrNull()?.takeIf { it.style.weight == targetWeight }
        return if (candidate != null) {
            SystemFontInstance(
                font = candidate,
                variationAxes = targetAxes,
                weight = targetWeight,
                italic = candidate.style.slant == FontStyle.FONT_SLANT_ITALIC,
            )
        } else {
            SystemFontInstance(
                font = this,
                variationAxes = currentAxes,
                weight = style.weight.coerceIn(1, 1000),
                italic = style.slant == FontStyle.FONT_SLANT_ITALIC,
            )
        }
    }

    private fun SystemFontInstance.instanceKey(): SystemFontInstanceKey = SystemFontInstanceKey(
        sourceIdentifier = font.sourceIdentifier,
        filePath = font.file?.absolutePath,
        collectionIndex = font.ttcIndex,
        variationAxes = variationAxes.entries.map { it.key to it.value.toRawBits() },
        weight = weight,
        slant = if (italic) FontStyle.FONT_SLANT_ITALIC else FontStyle.FONT_SLANT_UPRIGHT,
    )

    private fun systemFontLabel(font: Font, axes: Map<String, Float>): String = buildString {
        append("SystemFonts:")
        append(font.file?.absolutePath ?: font.sourceIdentifier)
        if (axes.isNotEmpty()) {
            append('#')
            append(axes.entries.joinToString(",") { (tag, value) -> "$tag=$value" })
        }
    }

    private data class SystemFontInstanceKey(
        val sourceIdentifier: Int,
        val filePath: String?,
        val collectionIndex: Int,
        val variationAxes: List<Pair<String, Int>>,
        val weight: Int,
        val slant: Int,
    )

    private data class SystemFontInstance(
        val font: Font,
        val variationAxes: Map<String, Float>,
        val weight: Int,
        val italic: Boolean,
    )
}

private fun variationTag(tag: String): Int =
    tag.fold(0) { result, char -> (result shl 8) or char.code }

private fun normaliseFamily(value: String): String =
    value.lowercase().replace("-", "").replace(" ", "")
