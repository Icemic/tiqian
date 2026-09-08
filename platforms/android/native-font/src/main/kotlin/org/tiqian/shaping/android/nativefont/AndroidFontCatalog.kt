package org.tiqian.shaping.android.nativefont

import android.content.Context
import org.tiqian.font.FontRole
import org.tiqian.shaping.FontBackendCapabilityIssue

/**
 * `OpticalSizeFollowsFontSize`: the face's `opsz` axis is set per request to
 * `fontSize × pointsPerPixel`, clamped to the axis range the font declares and quantised to
 * whole units, instead of staying at the declared instance. Off unless a face declares it.
 */
data class AndroidOpticalSizeRule(
    val pointsPerPixel: Float = 1f,
) {
    init {
        require(pointsPerPixel > 0f && pointsPerPixel.isFinite()) { "pointsPerPixel must be positive and finite" }
    }
}

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
    /** Per-request `opsz` derivation; null keeps the declared instance. */
    val opticalSize: AndroidOpticalSizeRule? = null,
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
        require(faceSpecs.isNotEmpty() || referencesPlatformDefault) {
            "AndroidFontCatalog must declare at least one face or delegate to $PLATFORM_DEFAULT_FAMILY"
        }
        require(faceSpecs.none { it.familyKey == PLATFORM_DEFAULT_FAMILY }) {
            "$PLATFORM_DEFAULT_FAMILY is reserved for fallback chains; it cannot declare faces"
        }
        val families = faceSpecs.groupBy(AndroidFontFaceSpec::familyKey)
        val declaredRoles = faceSpecs.flatMapTo(linkedSetOf(), AndroidFontFaceSpec::roles)
        require(fallbackChains.keys.containsAll(declaredRoles)) {
            "Every declared font role must have an ordered fallback chain"
        }
        fallbackChains.forEach { (role, chain) ->
            require(chain.isNotEmpty()) { "Fallback chain for $role must not be empty" }
            require(chain.size == chain.distinct().size) { "Fallback chain for $role repeats a family" }
            chain.forEach { familyKey ->
                if (familyKey == PLATFORM_DEFAULT_FAMILY) return@forEach
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

    /** True when any chain delegates a position to the platform's own font selection. */
    val referencesPlatformDefault: Boolean
        get() = fallbackChains.values.any { PLATFORM_DEFAULT_FAMILY in it }

    companion object {
        /**
         * PlatformDefaultFamily: a chain entry that stands for whatever the platform would select
         * for the request. API 31+ asks the platform per request; API 23–30 expands it into the
         * declared system families for that role at install time.
         */
        const val PLATFORM_DEFAULT_FAMILY = "platform-default"

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

        /**
         * The system catalog the backend uses when no host catalog is installed; hosts merge its
         * face specs and chains with their own before `install()`.
         */
        fun system(context: Context): AndroidFontCatalog = TiqianAndroidFontBackend.systemCatalog(context)
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

/** Replaces every platform-default chain entry with [system]'s families for that role. */
internal fun AndroidFontCatalog.expandPlatformDefault(system: AndroidFontCatalog): AndroidFontCatalog {
    val marker = AndroidFontCatalog.PLATFORM_DEFAULT_FAMILY
    val delegatedRoles = fallbackChains.filterValues { marker in it }.keys
    fun prefixed(key: String) = "$marker:$key"
    val systemSpecs = system.faceSpecs.mapNotNull { spec ->
        val roles = spec.roles.intersect(delegatedRoles)
        if (roles.isEmpty()) null else spec.copy(familyKey = prefixed(spec.familyKey), roles = roles)
    }
    val chains = fallbackChains.mapNotNull { (role, chain) ->
        val expanded = chain.flatMap { key ->
            if (key == marker) system.fallbackChains[role].orEmpty().map(::prefixed) else listOf(key)
        }.distinct()
        if (expanded.isEmpty()) null else role to expanded
    }.toMap()
    return AndroidFontCatalog(
        faceSpecs = faceSpecs + systemSpecs,
        fallbackChains = chains,
        sourceKind = "$sourceKind+${system.sourceKind}",
        declaredIssues = declaredIssues + system.declaredIssues,
    )
}
