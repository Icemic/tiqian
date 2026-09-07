package org.tiqian.android.rendering

import android.annotation.TargetApi
import android.graphics.Paint
import android.text.TextPaint
import org.tiqian.core.Glyph
import org.tiqian.shaping.android.AndroidGlyphReplay
import org.tiqian.shaping.android.AndroidGlyphReplayRegistry

/** The host replay that owns one render font key, memoised per draw cache; null when nobody does. */
internal class HostReplayFace(
    val replay: AndroidGlyphReplay,
    val providesItalic: Boolean,
)

internal fun hostReplayFaceFor(renderFontKey: String, drawCache: AndroidParagraphDrawCache): HostReplayFace? {
    val faces = drawCache.hostReplayFaces
    if (faces.containsKey(renderFontKey)) return faces[renderFontKey]
    val replay = AndroidGlyphReplayRegistry.replayFor(renderFontKey)
    val face = replay?.let { HostReplayFace(it, it.providesItalic(renderFontKey)) }
    faces[renderFontKey] = face
    return face
}

/**
 * HostGlyphReplay: glyph ids owned by one registered [AndroidGlyphReplay] are drawn as outlines at
 * their LayoutResult placements. A non-zero `textSkewX` becomes a canvas shear about the baseline
 * unless the replay's faces already provide the italic. False when the glyphs are not owned by a
 * single replay or cannot be replayed.
 */
internal fun drawAndroidReplayGlyphs(
    canvas: android.graphics.Canvas,
    glyphs: List<Glyph>,
    originX: Float,
    baselineY: Float,
    fontSize: Float,
    paint: TextPaint,
    drawCache: AndroidParagraphDrawCache,
): Boolean {
    if (glyphs.isEmpty()) return false
    var replay: AndroidGlyphReplay? = null
    var providesItalic = true
    for (glyph in glyphs) {
        val key = glyph.renderFontKey ?: return false
        val face = hostReplayFaceFor(key, drawCache) ?: return false
        if (replay == null) replay = face.replay else if (replay !== face.replay) return false
        providesItalic = providesItalic && face.providesItalic
    }
    val owner = replay ?: return false
    val skew = paint.textSkewX
    if (skew == 0f || providesItalic) {
        return owner.drawGlyphs(canvas, glyphs, originX, baselineY, fontSize, paint, drawCache.hostReplayPath)
    }
    canvas.save()
    canvas.translate(0f, baselineY)
    canvas.skew(skew, 0f)
    canvas.translate(0f, -baselineY)
    return try {
        owner.drawGlyphs(canvas, glyphs, originX, baselineY, fontSize, paint, drawCache.hostReplayPath)
    } finally {
        canvas.restore()
    }
}

/** A platform Font for `Canvas.drawGlyphs` plus the paint synthesis it was selected with. */
@TargetApi(31)
internal class AndroidPlatformGlyphFont(
    val font: android.graphics.fonts.Font,
    private val fakeBold: Boolean,
    /** Null keeps the skew the renderer chose for the run. */
    private val textSkewX: Float?,
) {
    fun applyTo(paint: Paint) {
        paint.isFakeBoldText = fakeBold
        if (textSkewX != null) paint.textSkewX = textSkewX
    }
}
