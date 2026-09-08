package org.tiqian.shaping.android.nativefont

import org.tiqian.shaping.ReplayableFontFaceDescriptor

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
    /** Why the segment is drawn by the platform text stack; null when [replayable]. */
    val stringDrawCause: PlatformStringDrawCause? = null,
)

internal enum class PlatformStringDrawCause { MultiFace, NoCoveringFace }

internal data class OrderedFamilySelection<T>(
    val familyIndex: Int,
    val face: T,
    val exactFamily: Boolean,
)

/**
 * StyleMatchedFaceCoverage: family order is authoritative; inside a family the face is chosen by
 * italic then weight (CSS Fonts Level 4 §5.2 search order), and only that face is checked for glyph
 * coverage. A family whose matched face lacks the text is skipped; its other faces are not consulted.
 */
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
        matchStyle(families[familyIndex], requestedWeight, requestedItalic, weight, italic, stableId)
            ?.takeIf(covers)
            ?.let { face ->
                OrderedFamilySelection(
                    familyIndex = familyIndex,
                    face = face,
                    exactFamily = preferred.isEmpty() || familyIndex in exactFamilyIndices,
                )
            }
    }
}

/** Italic first, then the CSS weight search order; declaration order and stable id break ties. */
internal fun <T> matchStyle(
    faces: List<T>,
    requestedWeight: Int,
    requestedItalic: Boolean,
    weight: (T) -> Int,
    italic: (T) -> Boolean,
    stableId: (T) -> String,
): T? {
    if (faces.isEmpty()) return null
    val styled = faces.withIndex().filter { italic(it.value) == requestedItalic }.ifEmpty { faces.withIndex() }
    val chosenWeight = cssWeightSearchOrder(requestedWeight, styled.map { weight(it.value) }.toSortedSet())
    return styled
        .filter { weight(it.value) == chosenWeight }
        .minWithOrNull(compareBy(IndexedValue<T>::index, { stableId(it.value) }))
        ?.value
}

/** The weight CSS Fonts Level 4 §5.2 picks from [available] for [requested]. */
internal fun cssWeightSearchOrder(
    requested: Int,
    available: Collection<Int>,
): Int {
    require(available.isNotEmpty()) { "At least one weight is required" }
    if (requested in available) return requested
    val below = available.filter { it < requested }.sortedDescending()
    val above = available.filter { it > requested }.sorted()
    return when {
        requested in 400..500 -> {
            above.firstOrNull { it <= 500 } ?: below.firstOrNull() ?: above.first()
        }
        requested < 400 -> below.firstOrNull() ?: above.first()
        else -> above.firstOrNull() ?: below.first()
    }
}

private fun normaliseFamily(value: String): String =
    value.lowercase().replace("-", "").replace(" ", "")
