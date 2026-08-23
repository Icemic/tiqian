package org.tiqian.shaping.android.nativefont

import org.tiqian.font.FontRole
import org.tiqian.shaping.FontBackendCapabilityIssue

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
