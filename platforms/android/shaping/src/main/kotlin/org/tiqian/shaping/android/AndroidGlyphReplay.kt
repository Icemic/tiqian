package org.tiqian.shaping.android

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import org.tiqian.core.Glyph

/**
 * `HostGlyphReplay`: a host-installed replay for glyph ids whose render font keys the platform
 * registry does not own, such as the native font backend's controlled-bytes faces. Renderers keep
 * drawing `LayoutResult` glyph ids at their placements; this hook only supplies the face behind
 * a key. Every query is per render font key so renderers can memoise the answers per draw cache.
 */
interface AndroidGlyphReplay {
    /** True when this replay retains the face behind [renderFontKey]. */
    fun ownsFont(renderFontKey: String): Boolean

    /**
     * True when the face behind [renderFontKey] already carries the requested italic, as a real
     * italic face or as the replay's own slant, so the renderer adds no synthetic shear.
     */
    fun providesItalic(renderFontKey: String): Boolean

    /**
     * API 31+: the platform font over the same bytes and axis instance as [renderFontKey] for
     * `Canvas.drawGlyphs`, with the paint synthesis that face was selected with; null when none is
     * retained.
     */
    fun platformFont(renderFontKey: String): AndroidReplayPlatformFont?

    /**
     * Draws the glyph outlines at their `LayoutResult` placements relative to the origin, using
     * [scratch] for the assembled path. Returns false when the glyphs cannot be replayed, so the
     * renderer takes its fallback.
     */
    fun drawGlyphs(
        canvas: Canvas,
        glyphs: List<Glyph>,
        originX: Float,
        originY: Float,
        fontSize: Float,
        paint: Paint,
        scratch: Path,
    ): Boolean
}

/**
 * A retained `android.graphics.fonts.Font` plus the paint synthesis the platform selected it
 * with. [font] is typed as [Any] so the class loads on API 23-30.
 */
class AndroidReplayPlatformFont(
    val font: Any,
    val fakeBold: Boolean,
    /** Null keeps the skew the renderer chose for the run. */
    val textSkewX: Float?,
)

/** Process-wide registrations that Android renderers consult after the platform font registry. */
object AndroidGlyphReplayRegistry {
    @Volatile
    private var replays: List<AndroidGlyphReplay> = emptyList()

    val registered: List<AndroidGlyphReplay>
        get() = replays

    @Synchronized
    fun register(replay: AndroidGlyphReplay) {
        if (replay !in replays) replays = replays + replay
    }

    @Synchronized
    fun unregister(replay: AndroidGlyphReplay) {
        replays = replays - replay
    }

    /** The registered replay that owns [renderFontKey], or null. */
    fun replayFor(renderFontKey: String): AndroidGlyphReplay? =
        replays.firstOrNull { it.ownsFont(renderFontKey) }
}
