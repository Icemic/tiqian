package org.tiqian.shaping

import org.tiqian.font.FontRole
import kotlin.jvm.JvmInline

/**
 * Stable identity of one physical SFNT face, collection index and variation instance.
 *
 * A [FontFaceId] is safe to store in [org.tiqian.core.Glyph.renderFontKey]: it
 * names bytes, not a process-local platform object. Platform renderers resolve
 * it through the same catalog that supplied shaping and metrics.
 */
@JvmInline
value class FontFaceId(val value: String) {
    init {
        require(value.isNotBlank()) { "FontFaceId must not be blank" }
    }

    override fun toString(): String = value
}

data class ReplayableFontFaceDescriptor(
    val id: FontFaceId,
    val familyAliases: Set<String>,
    val roles: Set<FontRole>,
    val weight: Int = 400,
    val italic: Boolean = false,
    val collectionIndex: Int = 0,
    val sourceLabel: String,
    val variationAxes: Map<String, Float> = emptyMap(),
)

data class ReplayableFontFaceRequest(
    val role: FontRole,
    val preferredFamilies: List<String>,
    /** Requested em size; platform default selection may resolve size-dependent variation axes. */
    val fontSize: Float,
    val weight: Int,
    val italic: Boolean,
    val locale: String,
    /** Text used to reject a face that cannot cover this concrete run. */
    val selectionText: String,
) {
    init {
        require(fontSize > 0f && fontSize.isFinite()) { "fontSize must be positive and finite" }
    }
}

/** A named loss of evidence or coverage. Reports inform hosts; they never route to another renderer. */
data class FontBackendCapabilityIssue(
    val code: String,
    val detail: String,
)

data class FontBackendCapabilityReport(
    val backend: String,
    val sourceKind: String,
    val faces: List<ReplayableFontFaceDescriptor>,
    val issues: List<FontBackendCapabilityIssue> = emptyList(),
) {
    val canReplayFromControlledBytes: Boolean
        get() = faces.isNotEmpty() && issues.none { it.code == "MissingControlledFontFace" }
}

/**
 * Platform-neutral catalog contract shared by shaping, metrics and replay.
 * Concrete catalogs may own files, byte arrays, assets or public system-font
 * handles, but callers only observe stable face descriptors.
 */
interface ReplayableFontCatalog {
    val faces: List<ReplayableFontFaceDescriptor>
    val capabilityReport: FontBackendCapabilityReport

    fun resolve(request: ReplayableFontFaceRequest): ReplayableFontFaceDescriptor?
}
