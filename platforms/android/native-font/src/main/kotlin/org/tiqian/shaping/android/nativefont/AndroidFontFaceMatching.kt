package org.tiqian.shaping.android.nativefont

import org.tiqian.shaping.ReplayableFontFaceDescriptor
import kotlin.math.abs

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

private fun normaliseFamily(value: String): String =
    value.lowercase().replace("-", "").replace(" ", "")
