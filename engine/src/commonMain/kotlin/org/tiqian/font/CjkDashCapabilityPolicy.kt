package org.tiqian.font

/**
 * `CjkDashCapabilityPolicy`: the CJK dash shaping outcome fails closed while no
 * conforming glyph source exists. When the status is "conforming" but no exact
 * font session is attached, the missing capability is the session itself; every
 * other status means no conforming CJK dash glyph is available. The evidence
 * (status/detail) arrives from the host-side asynchronous probe, so naming
 * belongs to the engine font policy and the platform adapter only forwards the
 * evidence.
 */
object CjkDashCapabilityPolicy {
    /** No conforming CJK dash glyph source is available for shaping. */
    const val NoConformingCjkDashGlyph: String = "NoConformingCjkDashGlyph"

    /** Shaping is conforming but an exact font session is still missing. */
    const val ConformingCjkDashRequiresExactFontSession: String =
        "ConformingCjkDashRequiresExactFontSession"

    /**
     * Returns the capability issue name for [status]. "conforming" names the
     * missing exact font session; any other status (including null) reports the
     * absence of a conforming CJK dash glyph.
     */
    fun issueNameFor(status: String?): String =
        if (status == "conforming") ConformingCjkDashRequiresExactFontSession else NoConformingCjkDashGlyph

    /**
     * Returns the capability issue detail for [status] and [detail]. A null
     * status means shaping was never prepared. A null or blank [detail] keeps
     * only the status prefix; otherwise the host detail is appended after "; ".
     */
    fun issueDetailFor(status: String?, detail: String?): String = when {
        status == null -> "CjkDashFontShapingNotPrepared"
        detail.isNullOrBlank() -> "status=" + status
        else -> "status=" + status + "; " + detail
    }
}