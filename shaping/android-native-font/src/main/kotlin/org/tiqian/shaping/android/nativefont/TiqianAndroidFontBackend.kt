package org.tiqian.shaping.android.nativefont

import android.annotation.TargetApi
import android.content.Context
import android.graphics.fonts.Font
import android.os.Build
import org.tiqian.font.FontRole
import org.tiqian.shaping.FontBackendCapabilityIssue
import org.tiqian.shaping.FontBackendCapabilityReport
import org.tiqian.shaping.FontFaceId
import org.tiqian.shaping.ReplayableFontCatalog
import org.tiqian.shaping.ReplayableFontFaceDescriptor
import org.tiqian.shaping.ReplayableFontFaceRequest
import java.io.File

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

private fun variationTag(tag: String): Int =
    tag.fold(0) { result, char -> (result shl 8) or char.code }
