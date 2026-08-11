package org.tiqian.shaping.nativefont

import android.annotation.TargetApi
import android.content.Context
import android.graphics.fonts.Font
import android.graphics.fonts.FontStyle
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
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.abs

internal sealed interface PreparedAndroidFontSource {
    val digestHex: String
    val sizeBytes: Long

    data class FileMapping(
        override val digestHex: String,
        override val sizeBytes: Long,
        val path: String,
    ) : PreparedAndroidFontSource

    data class DirectBuffer(
        override val digestHex: String,
        override val sizeBytes: Long,
        val buffer: ByteBuffer,
    ) : PreparedAndroidFontSource
}

/** Controlled immutable source accepted by the API 23+ native font backend. */
sealed class AndroidFontSource protected constructor(
    val label: String,
) {
    internal abstract fun locatorKey(context: Context): String
    internal abstract fun prepare(context: Context): PreparedAndroidFontSource

    companion object {
        fun bytes(bytes: ByteArray, label: String = "host-byte-array"): AndroidFontSource =
            DirectBufferSource(copyToDirectBuffer(bytes), label, "bytes")

        fun file(file: File, label: String = file.absolutePath): AndroidFontSource =
            FileSource(file, label)

        fun asset(path: String, label: String = "asset:$path"): AndroidFontSource =
            AssetSource(path, label)

        internal fun directBuffer(buffer: ByteBuffer, label: String): AndroidFontSource =
            DirectBufferSource(buffer.immutableDirectView(), label, "buffer")
    }

    private class DirectBufferSource(
        private val buffer: ByteBuffer,
        label: String,
        kind: String,
    ) : AndroidFontSource(label) {
        private val digestHex = sha256Hex(buffer)
        private val key = "$kind:sha256:$digestHex:${buffer.capacity()}"

        override fun locatorKey(context: Context): String = key

        override fun prepare(context: Context): PreparedAndroidFontSource =
            PreparedAndroidFontSource.DirectBuffer(
                digestHex = digestHex,
                sizeBytes = buffer.capacity().toLong(),
                buffer = buffer,
            )
    }

    private class FileSource(
        private val file: File,
        label: String,
    ) : AndroidFontSource(label) {
        override fun locatorKey(context: Context): String {
            val canonical = file.canonicalFile
            return "file:${canonical.path}:${canonical.length()}:${canonical.lastModified()}"
        }

        override fun prepare(context: Context): PreparedAndroidFontSource {
            val canonical = file.canonicalFile
            require(canonical.isFile) { "Font file does not exist: ${canonical.path}" }
            val size = canonical.length()
            require(size > 0L) { "Font file is empty: ${canonical.path}" }
            return PreparedAndroidFontSource.FileMapping(
                digestHex = sha256Hex(canonical),
                sizeBytes = size,
                path = canonical.path,
            )
        }
    }

    private class AssetSource(
        private val path: String,
        label: String,
    ) : AndroidFontSource(label) {
        override fun locatorKey(context: Context): String = "asset:${context.packageName}:$path"

        override fun prepare(context: Context): PreparedAndroidFontSource {
            val bytes = context.assets.open(path).use { it.readBytes() }
            val buffer = copyToDirectBuffer(bytes)
            return PreparedAndroidFontSource.DirectBuffer(
                digestHex = sha256Hex(buffer),
                sizeBytes = buffer.capacity().toLong(),
                buffer = buffer,
            )
        }
    }
}

private fun copyToDirectBuffer(bytes: ByteArray): ByteBuffer {
    require(bytes.isNotEmpty()) { "Font bytes must not be empty" }
    return ByteBuffer.allocateDirect(bytes.size).apply {
        put(bytes)
        position(0)
    }.asReadOnlyBuffer()
}

private fun ByteBuffer.immutableDirectView(): ByteBuffer {
    require(isDirect) { "Font ByteBuffer must be direct" }
    val view = duplicate().apply { position(0) }.slice().asReadOnlyBuffer()
    require(view.hasRemaining()) { "Font ByteBuffer must not be empty" }
    return view
}

private fun sha256Hex(buffer: ByteBuffer): String =
    MessageDigest.getInstance("SHA-256")
        .apply { update(buffer.duplicate().apply { position(0) }) }
        .digest()
        .toHex()

private fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val chunk = ByteArray(DefaultDigestChunkBytes)
        while (true) {
            val count = input.read(chunk)
            if (count < 0) break
            if (count > 0) digest.update(chunk, 0, count)
        }
    }
    return digest.digest().toHex()
}

private fun ByteArray.toHex(): String = joinToString("") { byte ->
    HexDigits[(byte.toInt() ushr 4) and 0x0F].toString() + HexDigits[byte.toInt() and 0x0F]
}

private const val HexDigits = "0123456789abcdef"
private const val DefaultDigestChunkBytes = 64 * 1024

/**
 * One concrete host/system face instance. [familyKey] groups regular, bold and italic faces into
 * one fallback family; [roles] says which role chains may reference it. [variationAxes] must be
 * the effective coordinates used for replay, including a platform weight/italic override after
 * that override has been lowered to its OpenType axis.
 */
data class AndroidFontFaceSpec(
    val source: AndroidFontSource,
    val collectionIndex: Int = 0,
    val familyKey: String,
    val familyAliases: Set<String>,
    val roles: Set<FontRole>,
    val weight: Int = 400,
    val italic: Boolean = false,
    /** OpenType variation coordinates that identify the concrete face instance. */
    val variationAxes: Map<String, Float> = emptyMap(),
) {
    init {
        require(collectionIndex >= 0) { "collectionIndex must be non-negative" }
        require(familyKey.isNotBlank()) { "familyKey must not be blank" }
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
    /** Ordered family keys for every role. Style matching happens inside one family before fallback. */
    val fallbackChains: Map<FontRole, List<String>> = defaultFallbackChains(faceSpecs),
    val sourceKind: String = "ExplicitHostFontCatalog",
    val declaredIssues: List<FontBackendCapabilityIssue> = emptyList(),
) {
    init {
        require(faceSpecs.isNotEmpty()) { "AndroidFontCatalog must declare at least one face" }
        val families = faceSpecs.groupBy(AndroidFontFaceSpec::familyKey)
        val declaredRoles = faceSpecs.flatMapTo(linkedSetOf(), AndroidFontFaceSpec::roles)
        require(fallbackChains.keys.containsAll(declaredRoles)) {
            "Every declared font role must have an ordered fallback chain"
        }
        fallbackChains.forEach { (role, chain) ->
            require(chain.isNotEmpty()) { "Fallback chain for $role must not be empty" }
            require(chain.size == chain.distinct().size) { "Fallback chain for $role repeats a family" }
            chain.forEach { familyKey ->
                val family = requireNotNull(families[familyKey]) {
                    "Fallback chain for $role references unknown family $familyKey"
                }
                require(family.any { role in it.roles }) {
                    "Fallback family $familyKey has no face for role $role"
                }
            }
        }
        faceSpecs.forEach { spec ->
            spec.roles.forEach { role ->
                require(spec.familyKey in fallbackChains.getValue(role)) {
                    "Face family ${spec.familyKey} declares $role but is absent from that fallback chain"
                }
            }
        }
    }

    companion object {
        /**
         * Production host contract for API 23–28: package fonts as assets, files
         * or byte arrays and install this catalog before the first CjkText.
         */
        fun host(
            faceSpecs: List<AndroidFontFaceSpec>,
            fallbackChains: Map<FontRole, List<String>> = defaultFallbackChains(faceSpecs),
        ): AndroidFontCatalog = AndroidFontCatalog(
            faceSpecs = faceSpecs,
            fallbackChains = fallbackChains,
        )
    }
}

private fun defaultFallbackChains(faceSpecs: List<AndroidFontFaceSpec>): Map<FontRole, List<String>> =
    FontRole.entries.mapNotNull { role ->
        faceSpecs
            .asSequence()
            .filter { role in it.roles }
            .map(AndroidFontFaceSpec::familyKey)
            .distinct()
            .toList()
            .takeIf(List<String>::isNotEmpty)
            ?.let { role to it }
    }.toMap()

/** Loaded face plus evidence about request matching; shared by shaping, metrics and replay. */
internal data class ResolvedNativeFontFace(
    val descriptor: ReplayableFontFaceDescriptor,
    val nativeFace: NativeFontFace,
    val exactFamily: Boolean,
    val exactStyle: Boolean,
    val coversSelectionText: Boolean,
    /**
     * False when the platform itemized the segment across multiple faces: this face is kept
     * only for metrics, and the segment is measured/drawn through the platform text stack
     * ([PLATFORM_MULTI_FACE_STRING_DRAW_ISSUE]) rather than controlled-byte outline replay.
     */
    val replayable: Boolean = true,
    /** Platform-measured run advance used by the non-replayable degrade path. */
    val degradedRunAdvance: Float = 0f,
)

private data class LoadedFace(
    val catalogIndex: Int,
    val familyKey: String,
    val descriptor: ReplayableFontFaceDescriptor,
    val nativeFace: NativeFontFace,
)

private data class LoadedFamily(
    val faces: List<LoadedFace>,
)

private data class PlatformLoadedFace(
    val descriptor: ReplayableFontFaceDescriptor,
    val nativeFace: NativeFontFace,
)

private data class LoadedNativeFontSource(
    val handle: Long,
    val digestHex: String,
    val sizeBytes: Long,
)

internal data class OrderedFamilySelection<T>(
    val familyIndex: Int,
    val face: T,
    val exactFamily: Boolean,
)

/** Family order is authoritative; italic/weight matching happens only inside one covering family. */
internal fun <T> selectOrderedFamilyFace(
    families: List<List<T>>,
    preferredFamilies: List<String>,
    requestedWeight: Int,
    requestedItalic: Boolean,
    aliases: (T) -> Set<String>,
    covers: (T) -> Boolean,
    weight: (T) -> Int,
    italic: (T) -> Boolean,
    stableId: (T) -> String,
): OrderedFamilySelection<T>? {
    val preferred = preferredFamilies.map(::normaliseFamily).filter(String::isNotEmpty)
    val exactFamilyIndices = if (preferred.isEmpty()) {
        families.indices.toList()
    } else {
        families.indices.filter { familyIndex ->
            families[familyIndex].any { face ->
                aliases(face).any { normaliseFamily(it) in preferred }
            }
        }
    }
    val familyPool = exactFamilyIndices.ifEmpty { families.indices.toList() }
    return familyPool.firstNotNullOfOrNull { familyIndex ->
        families[familyIndex]
            .withIndex()
            .filter { covers(it.value) }
            .minWithOrNull(
                compareBy<IndexedValue<T>>(
                    { if (italic(it.value) == requestedItalic) 0 else 1 },
                    { abs(weight(it.value) - requestedWeight) },
                    IndexedValue<T>::index,
                    { stableId(it.value) },
                ),
            )
            ?.value
            ?.let { face ->
                OrderedFamilySelection(
                    familyIndex = familyIndex,
                    face = face,
                    exactFamily = preferred.isEmpty() || familyIndex in exactFamilyIndices,
                )
            }
    }
}

private class RevisionListener(
    private val callback: (Long) -> Unit,
) {
    private var lastDelivered = 0L

    fun deliver(revision: Long) {
        val shouldDeliver = synchronized(this) {
            if (revision <= lastDelivered) false else {
                lastDelivered = revision
                true
            }
        }
        if (shouldDeliver) callback(revision)
    }
}

private class LoadedAndroidFontCatalog(
    private val loadedFaces: List<LoadedFace>,
    private val familiesByRole: Map<FontRole, List<LoadedFamily>>,
    val revision: Long,
    val usesPlatformDefaultOracle: Boolean,
    override val capabilityReport: FontBackendCapabilityReport,
) : ReplayableFontCatalog {
    override val faces: List<ReplayableFontFaceDescriptor> = loadedFaces.map { it.descriptor }

    override fun resolve(request: ReplayableFontFaceRequest): ReplayableFontFaceDescriptor? =
        resolveNative(request)?.descriptor

    fun resolveNative(request: ReplayableFontFaceRequest): ResolvedNativeFontFace? {
        val roleFamilies = familiesByRole[request.role].orEmpty()
        if (roleFamilies.isEmpty()) return null
        val selection = selectOrderedFamilyFace(
            families = roleFamilies.map(LoadedFamily::faces),
            preferredFamilies = request.preferredFamilies,
            requestedWeight = request.weight,
            requestedItalic = request.italic,
            aliases = { it.descriptor.familyAliases },
            covers = { it.nativeFace.hasGlyphs(request.selectionText) },
            weight = { it.descriptor.weight },
            italic = { it.descriptor.italic },
            stableId = { "${it.catalogIndex}:${it.descriptor.id.value}" },
        ) ?: return null
        val selected = selection.face
        return ResolvedNativeFontFace(
            descriptor = selected.descriptor,
            nativeFace = selected.nativeFace,
            exactFamily = selection.exactFamily,
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
    private val sourceByLocator = LinkedHashMap<String, LoadedNativeFontSource>()
    private val sourceByDigest = LinkedHashMap<String, LoadedNativeFontSource>()
    private val faceById = LinkedHashMap<FontFaceId, NativeFontFace>()
    private val descriptorById = LinkedHashMap<FontFaceId, ReplayableFontFaceDescriptor>()
    private val platformFaceByRequest = object : LinkedHashMap<ReplayableFontFaceRequest, ResolvedNativeFontFace>(256, 0.75f, true) {}
    private val platformFaceByInstance = LinkedHashMap<String, PlatformLoadedFace>()
    private val platformFontById = LinkedHashMap<FontFaceId, Font>()
    private val revisionListeners = linkedSetOf<RevisionListener>()
    private val syntheticBoldFaceIds = linkedSetOf<FontFaceId>()
    private val syntheticItalicFaceIds = linkedSetOf<FontFaceId>()
    private var lastRevision = 0L

    @Volatile
    private var activeCatalog: LoadedAndroidFontCatalog? = null

    private val versionEvidence: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        NativeFontBridge.nativeVersions()
    }

    val nativeVersions: String
        get() = versionEvidence

    /** Install one immutable font environment. Old faces remain retained for old LayoutResult replay. */
    fun install(context: Context, catalog: AndroidFontCatalog): FontBackendCapabilityReport {
        val installed: LoadedAndroidFontCatalog
        val listeners: List<RevisionListener>
        synchronized(lock) {
            installed = loadCatalog(
                context = context.applicationContext,
                catalog = catalog,
                revision = nextRevisionLocked(),
                usesPlatformDefaultOracle = false,
            )
            activeCatalog = installed
            listeners = revisionListeners.toList()
        }
        listeners.forEach { listener -> runCatching { listener.deliver(installed.revision) } }
        return installed.capabilityReport
    }

    /** Monotonic identity of the active immutable catalog, suitable for layout/cache keys. */
    fun catalogRevision(context: Context): Long = ensureInstalled(context).revision

    /**
     * Observe active-catalog replacement. The current revision is delivered immediately so a
     * registration cannot miss an install racing with subscription.
     */
    fun addCatalogRevisionListener(context: Context, listener: (Long) -> Unit): AutoCloseable {
        ensureInstalled(context)
        val subscription = RevisionListener(listener)
        val current = synchronized(lock) {
            revisionListeners += subscription
            checkNotNull(activeCatalog).revision
        }
        subscription.deliver(current)
        return AutoCloseable { synchronized(lock) { revisionListeners -= subscription } }
    }

    fun capabilityReport(context: Context): FontBackendCapabilityReport =
        ensureInstalled(context).capabilityReport

    internal fun resolveFace(
        context: Context,
        request: ReplayableFontFaceRequest,
    ): ResolvedNativeFontFace {
        val catalog = ensureInstalled(context)
        if (Build.VERSION.SDK_INT >= 31 && catalog.usesPlatformDefaultOracle) {
            resolvePlatformDefaultFace(context.applicationContext, request)?.let { return it }
        }
        return catalog.resolveNative(request) ?: error(
            "MissingControlledFontFace: role=${request.role}; families=${request.preferredFamilies}; " +
                "install an AndroidFontCatalog before composing CjkText; report=${catalog.capabilityReport}",
        )
    }

    internal fun faceFor(renderFontKey: String): NativeFontFace? = synchronized(lock) {
        faceById[FontFaceId(renderFontKey)]
    }

    /** Stable replay evidence for a glyph id emitted by [AndroidNativeTextShaper]. */
    fun replayFaceDescriptor(renderFontKey: String): ReplayableFontFaceDescriptor? = synchronized(lock) {
        descriptorById[FontFaceId(renderFontKey)]
    }

    internal fun isSyntheticItalicFace(renderFontKey: String): Boolean = synchronized(lock) {
        FontFaceId(renderFontKey) in syntheticItalicFaceIds
    }

    internal fun isSyntheticBoldFace(renderFontKey: String): Boolean = synchronized(lock) {
        FontFaceId(renderFontKey) in syntheticBoldFaceIds
    }

    internal fun platformFontFor(renderFontKey: String): Font? = synchronized(lock) {
        platformFontById[FontFaceId(renderFontKey)]
    }

    internal fun resourceStatsForTesting(): NativeFontResourceStats = nativeFontResourceStats()

    internal fun resetDefaultCatalogForTesting(context: Context) {
        synchronized(lock) {
            platformFaceByRequest.clear()
            platformFaceByInstance.clear()
            val catalog = defaultCatalog(context.applicationContext)
            activeCatalog = loadCatalog(
                context = context.applicationContext,
                catalog = catalog,
                revision = nextRevisionLocked(),
                usesPlatformDefaultOracle = catalog.isPlatformDefaultOracleCatalog(),
            )
        }
    }

    private fun ensureInstalled(context: Context): LoadedAndroidFontCatalog {
        activeCatalog?.let { return it }
        return synchronized(lock) {
            activeCatalog ?: run {
                val catalog = defaultCatalog(context.applicationContext)
                loadCatalog(
                    context.applicationContext,
                    catalog,
                    nextRevisionLocked(),
                    usesPlatformDefaultOracle = catalog.isPlatformDefaultOracleCatalog(),
                ).also { activeCatalog = it }
            }
        }
    }

    private fun nextRevisionLocked(): Long = (++lastRevision).also {
        check(it > 0L) { "Android font catalog revision overflow" }
    }

    private fun defaultCatalog(context: Context): AndroidFontCatalog {
        if (Build.VERSION.SDK_INT >= 31) {
            AndroidPlatformFontOracle.bootstrapCatalogOrNull()?.let { return it }
        }
        DeclaredSystemFontConfigCatalog.createOrNull()?.let { return it }
        if (Build.VERSION.SDK_INT >= 29) {
            ApproximatePublicSystemFontsCatalog.createOrNull()?.let { return it }
        }
        return wellKnownSystemPathCatalog()
    }

    private fun AndroidFontCatalog.isPlatformDefaultOracleCatalog(): Boolean =
        sourceKind == "AndroidPlatformTextRunOracleApi31"

    private fun loadCatalog(
        context: Context,
        catalog: AndroidFontCatalog,
        revision: Long,
        usesPlatformDefaultOracle: Boolean,
    ): LoadedAndroidFontCatalog {
        val issues = catalog.declaredIssues.toMutableList()
        val loaded = mutableListOf<LoadedFace>()
        for ((catalogIndex, spec) in catalog.faceSpecs.withIndex()) {
            val axes = spec.variationAxes.toSortedMap()
            val source = runCatching { loadSourceLocked(context, spec.source) }.getOrElse { error ->
                issues += FontBackendCapabilityIssue(
                    code = "FontSourceUnavailable",
                    detail = "${spec.source.label}: ${error::class.simpleName}: ${error.message}",
                )
                continue
            }
            val loadedFace = runCatching {
                val id = stableFaceId(source.digestHex, spec.collectionIndex, axes)
                val native = faceById.getOrPut(id) {
                    val handle = NativeFontBridge.nativeCreateFace(
                        sourceHandle = source.handle,
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
            val descriptor = ReplayableFontFaceDescriptor(
                id = id,
                familyAliases = spec.familyAliases.toSet(),
                roles = spec.roles.toSet(),
                weight = spec.weight,
                italic = spec.italic,
                collectionIndex = spec.collectionIndex,
                sourceLabel = spec.source.label,
                variationAxes = axes,
            )
            descriptorById[id] = descriptor
            loaded += LoadedFace(
                catalogIndex = catalogIndex,
                familyKey = spec.familyKey,
                descriptor = descriptor,
                nativeFace = native,
            )
        }
        val familiesByRole = catalog.fallbackChains.mapValues { (role, familyKeys) ->
            familyKeys.mapNotNull { familyKey ->
                loaded
                    .filter { it.familyKey == familyKey && role in it.descriptor.roles }
                    .takeIf(List<LoadedFace>::isNotEmpty)
                    ?.let(::LoadedFamily)
            }
        }
        val roles = familiesByRole.filterValues(List<LoadedFamily>::isNotEmpty).keys
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
        return LoadedAndroidFontCatalog(
            loadedFaces = loaded,
            familiesByRole = familiesByRole,
            revision = revision,
            usesPlatformDefaultOracle = usesPlatformDefaultOracle,
            capabilityReport = report,
        )
    }

    @TargetApi(31)
    private fun resolvePlatformDefaultFace(
        context: Context,
        request: ReplayableFontFaceRequest,
    ): ResolvedNativeFontFace? {
        synchronized(lock) {
            platformFaceByRequest[request]?.let { return it }
        }
        val selection = AndroidPlatformFontOracle.select(request)
        synchronized(lock) {
            platformFaceByInstance[selection.instanceKey]?.let { loaded ->
                return ResolvedNativeFontFace(
                    descriptor = loaded.descriptor.copy(
                        familyAliases = selection.aliases,
                        roles = setOf(request.role),
                        weight = selection.weight,
                        italic = selection.italic,
                    ),
                    nativeFace = loaded.nativeFace,
                    exactFamily = true,
                    exactStyle = true,
                    coversSelectionText = !selection.spansMultipleFaces,
                    replayable = !selection.spansMultipleFaces,
                    degradedRunAdvance = selection.degradedRunAdvance,
                ).also { resolved -> cachePlatformRequestLocked(request, resolved) }
            }
        }
        val axes = selection.variationAxes.toSortedMap()
        val resolved = runCatching {
            val native = synchronized(lock) {
                val source = loadSourceLocked(context, selection.source)
                val physicalId = stableFaceId(source.digestHex, selection.collectionIndex, axes)
                val id = platformReplayFaceId(
                    physicalId = physicalId,
                    syntheticBold = selection.syntheticBold,
                    syntheticItalic = selection.syntheticItalic,
                )
                val physicalFace = faceById.getOrPut(physicalId) {
                    val handle = NativeFontBridge.nativeCreateFace(
                        sourceHandle = source.handle,
                        collectionIndex = selection.collectionIndex,
                        variationTags = axes.keys.map(::variationTag).toIntArray(),
                        variationValues = axes.values.toFloatArray(),
                    )
                    NativeFontFace(handle, NativeFontBridge.nativeUnitsPerEm(handle))
                }
                faceById[id] = physicalFace
                if (selection.syntheticBold) syntheticBoldFaceIds += id
                if (selection.syntheticItalic) syntheticItalicFaceIds += id
                // The platform Font and the NativeFontFace above are two handles over the same
                // source, TTC index and variation axes. Retain it for exact Canvas.drawGlyphs
                // replay on API 31+, not only for the fake-bold special case.
                platformFontById[id] = selection.font
                physicalFace to id
            }
            val (nativeFace, id) = native
            ResolvedNativeFontFace(
                descriptor = ReplayableFontFaceDescriptor(
                    id = id,
                    familyAliases = selection.aliases,
                    roles = setOf(request.role),
                    weight = selection.weight,
                    italic = selection.italic,
                    collectionIndex = selection.collectionIndex,
                    sourceLabel = buildString {
                        append(selection.source.label)
                        if (selection.syntheticBold) append(":syntheticBold=platform")
                        if (selection.syntheticItalic) append(":syntheticItalic=-0.25")
                    },
                    variationAxes = axes,
                ),
                nativeFace = nativeFace,
                exactFamily = true,
                exactStyle = true,
                coversSelectionText = nativeFace.hasGlyphs(request.selectionText),
                replayable = !selection.spansMultipleFaces,
                degradedRunAdvance = selection.degradedRunAdvance,
            )
        }.getOrElse { return null }
        // CjkPunctuationHanFaceAnchor deliberately keeps the CJK face even when a proposed
        // display substitution is absent. HarfBuzz must report the missing glyph so layout can
        // roll the substitution back to its source form; silently switching to a Latin face here
        // would both defeat the role decision and conceal that evidence. A non-replayable
        // multi-face degrade intentionally does not cover the whole run, so it bypasses this
        // rejection and is handled by the platform string-draw path.
        if (resolved.replayable && !resolved.coversSelectionText && request.role != FontRole.CjkPunctuation) return null
        synchronized(lock) {
            descriptorById[resolved.descriptor.id] = resolved.descriptor
            platformFaceByInstance[selection.instanceKey] = PlatformLoadedFace(
                descriptor = resolved.descriptor,
                nativeFace = resolved.nativeFace,
            )
            cachePlatformRequestLocked(request, resolved)
        }
        return resolved
    }

    private fun cachePlatformRequestLocked(
        request: ReplayableFontFaceRequest,
        resolved: ResolvedNativeFontFace,
    ) {
        platformFaceByRequest[request] = resolved
        while (platformFaceByRequest.size > MaxPlatformRequestEntries) {
            val iterator = platformFaceByRequest.entries.iterator()
            if (!iterator.hasNext()) break
            iterator.next()
            iterator.remove()
        }
    }

    private fun stableFaceId(
        sourceDigestHex: String,
        collectionIndex: Int,
        variationAxes: Map<String, Float>,
    ): FontFaceId {
        val axes = variationAxes.entries.joinToString(",") { (tag, value) ->
            "$tag=${value.toRawBits().toUInt().toString(16)}"
        }
        return FontFaceId(
            buildString {
                append("tiqian-font:sha256:")
                append(sourceDigestHex)
                append(':')
                append(collectionIndex)
                if (axes.isNotEmpty()) {
                    append(":axes:")
                    append(axes)
                }
            },
        )
    }

    /**
     * Sources are immutable for the process lifetime. Locator lookup avoids re-hashing a system
     * file for every platform request; digest lookup folds different locators with identical
     * content into one native mapping/direct buffer. A Face only retains this shared source.
     */
    private fun loadSourceLocked(context: Context, source: AndroidFontSource): LoadedNativeFontSource {
        val locator = source.locatorKey(context)
        sourceByLocator[locator]?.let { return it }
        val prepared = source.prepare(context)
        sourceByDigest[prepared.digestHex]?.let { existing ->
            sourceByLocator[locator] = existing
            return existing
        }
        val handle = when (prepared) {
            is PreparedAndroidFontSource.FileMapping ->
                NativeFontBridge.nativeRegisterFileSource(prepared.path)
            is PreparedAndroidFontSource.DirectBuffer ->
                NativeFontBridge.nativeRegisterBufferSource(prepared.buffer, prepared.sizeBytes)
        }
        check(handle != 0L) { "Native font source registration did not return a handle" }
        return LoadedNativeFontSource(
            handle = handle,
            digestHex = prepared.digestHex,
            sizeBytes = prepared.sizeBytes,
        ).also { loaded ->
            sourceByLocator[locator] = loaded
            sourceByDigest[prepared.digestHex] = loaded
        }
    }

    private const val MaxPlatformRequestEntries = 4096
}

internal fun platformReplayFaceId(
    physicalId: FontFaceId,
    syntheticBold: Boolean,
    syntheticItalic: Boolean,
): FontFaceId = buildString {
    append(physicalId.value)
    if (syntheticBold) append(":syntheticBold=platform")
    if (syntheticItalic) append(":syntheticItalic=-0.25")
}.let(::FontFaceId)

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
        familyKey = if (FontRole.CjkText in roles) "well-known-cjk" else "well-known-latin",
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
internal object ApproximatePublicSystemFontsCatalog {
    fun createOrNull(
        fonts: List<Font> = SystemFonts.getAvailableFonts().toList(),
    ): AndroidFontCatalog? = runCatching {
        if (fonts.isEmpty()) return null
        val upright = fonts.filter { it.style.slant == FontStyle.FONT_SLANT_UPRIGHT }
        val cjkRegular = selectApproximateCjk(upright, targetWeight = 400)
        val cjkBold = selectApproximateCjk(upright, targetWeight = 700)
        val latinRegular = selectGenericSans(upright, targetWeight = 400)
        val latinBold = selectGenericSans(upright, targetWeight = 700)
        val latinItalic = selectGenericSans(
            fonts.filter { it.style.slant == FontStyle.FONT_SLANT_ITALIC },
            targetWeight = 400,
        )
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
            cjkRegular?.let { Triple(it, cjkRoles, "approximate-system-cjk") },
            cjkBold?.let { Triple(it, cjkRoles, "approximate-system-cjk") },
            latinRegular?.let { Triple(it, latinFallbackRoles, "approximate-system-latin") },
            latinBold?.let { Triple(it, latinFallbackRoles, "approximate-system-latin") },
            latinItalic?.let { Triple(it, latinFallbackRoles, "approximate-system-latin") },
        )
        val specs = selected
            .groupBy { (font, _, familyKey) -> font.instanceKey() to familyKey }
            .map { (_, selections) ->
                val (font, _, familyKey) = selections.first()
                val roles = selections.flatMapTo(linkedSetOf()) { it.second }
                val axes = font.variationAxes()
                AndroidFontFaceSpec(
                    source = font.file?.takeIf(File::isFile)?.let { file ->
                        AndroidFontSource.file(file, systemFontLabel(font, axes))
                    } ?: AndroidFontSource.directBuffer(
                        font.buffer.duplicate().apply { position(0) },
                        systemFontLabel(font, axes),
                    ),
                    collectionIndex = font.ttcIndex,
                    familyKey = familyKey,
                    familyAliases = familyAliases(font),
                    roles = roles,
                    weight = font.style.weight.coerceIn(1, 1000),
                    italic = font.style.slant == FontStyle.FONT_SLANT_ITALIC,
                    variationAxes = axes,
                )
            }
        if (specs.none { FontRole.CjkText in it.roles } || specs.none { FontRole.LatinText in it.roles }) return null
        AndroidFontCatalog(
            faceSpecs = specs,
            sourceKind = "ApproximateAndroidPublicSystemFontsApi29",
            declaredIssues = listOf(
                FontBackendCapabilityIssue(
                    code = "ApproximateSystemFontSelection",
                    detail = "SystemFonts is unordered and does not expose the active family/fallback graph; selected faces may differ from the OEM or user default",
                ),
            ),
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

    private fun selectApproximateCjk(fonts: List<Font>, targetWeight: Int): Font? =
        fonts.minWithOrNull(
            compareBy<Font>(
                { font -> cjkScore(font, targetWeight) },
                { font -> font.file?.absolutePath.orEmpty() },
                Font::getTtcIndex,
                { font ->
                    font.variationAxes().entries.joinToString(",") { (tag, value) ->
                        "$tag=${value.toRawBits()}"
                    }
                },
                { font -> font.style.weight },
            ),
        )

    /**
     * `SystemFonts.getAvailableFonts()` is an unordered set and loses named-family membership.
     * Roboto's normal and condensed aliases can therefore expose the same file and weight with
     * only `wdth` distinguishing them. Resolve generic sans deterministically, preferring the
     * registered normal width before weight proximity. This is deliberately diagnostic-only:
     * it preserves the enumerated instance and never manufactures a new 400/700 axis value.
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

    private fun familyAliases(font: Font): Set<String> {
        val name = font.file?.nameWithoutExtension.orEmpty().lowercase()
        return buildSet {
            add(name.ifEmpty { "system-${font.stableSourceId()}" })
            when {
                "mono" in name -> addAll(listOf("mono", "monospace"))
                "serif" in name && "sans" !in name -> add("serif")
                else -> addAll(listOf("sans", "sans-serif"))
            }
        }
    }

    /**
     * `Font.getSourceIdentifier` only exists on API 31+, but the public `SystemFonts`
     * enumeration this catalog is built from starts at API 29. Below 31 the enumerated
     * instances are the identity the catalog actually needs: the id is consumed once,
     * inside the single [createOrNull] pass, to separate faces that share a file path,
     * collection index and axis set.
     */
    private fun Font.stableSourceId(): Int =
        if (Build.VERSION.SDK_INT >= 31) sourceIdentifier else System.identityHashCode(this)

    private fun Font.variationAxes(): Map<String, Float> =
        axes.orEmpty().associate { axis -> axis.tag to axis.styleValue }.toSortedMap()

    private fun Font.instanceKey(): FontInstanceKey = FontInstanceKey(
        sourceIdentifier = stableSourceId(),
        filePath = file?.absolutePath,
        collectionIndex = ttcIndex,
        variationAxes = variationAxes().entries.map { it.key to it.value.toRawBits() },
        weight = style.weight,
        slant = style.slant,
    )

    private fun systemFontLabel(font: Font, axes: Map<String, Float>): String = buildString {
        append("SystemFonts:")
        append(font.file?.absolutePath ?: font.stableSourceId())
        if (axes.isNotEmpty()) {
            append('#')
            append(axes.entries.joinToString(",") { (tag, value) -> "$tag=$value" })
        }
    }

    private data class FontInstanceKey(
        val sourceIdentifier: Int,
        val filePath: String?,
        val collectionIndex: Int,
        val variationAxes: List<Pair<String, Int>>,
        val weight: Int,
        val slant: Int,
    )

}

private fun variationTag(tag: String): Int =
    tag.fold(0) { result, char -> (result shl 8) or char.code }

private fun normaliseFamily(value: String): String =
    value.lowercase().replace("-", "").replace(" ", "")
