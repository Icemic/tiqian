package org.tiqian.shaping.nativefont

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.fonts.Font
import org.tiqian.core.Glyph
import org.tiqian.core.Rect
import java.util.LinkedHashMap

/** FreeType outline replay for API 23+, consuming only glyph ids/origins emitted by LayoutResult. */
object AndroidNativeGlyphReplay {
    private const val MaxCachedScaledOutlines = 4096
    private val cacheLock = Any()
    private val scaledOutlineCache = object : LinkedHashMap<OutlineKey, Path>(128, 0.75f, true) {}

    fun drawGlyphs(
        canvas: Canvas,
        glyphs: List<Glyph>,
        originX: Float,
        originY: Float,
        fontSize: Float,
        paint: Paint,
        reusablePath: Path? = null,
    ): Boolean {
        if (requiresPlatformSyntheticBold(glyphs)) return false
        val path = glyphPath(glyphs, originX, originY, fontSize, reusablePath) ?: return false
        if (!path.isEmpty) canvas.drawPath(path, paint)
        return true
    }

    /** True when these glyph ids were produced by faces retained by this backend. */
    fun ownsGlyphs(glyphs: List<Glyph>): Boolean =
        glyphs.isNotEmpty() && glyphs.all { glyph ->
            glyph.renderFontKey?.let(TiqianAndroidFontBackend::faceFor) != null
        }

    fun requiresPlatformSyntheticBold(glyphs: List<Glyph>): Boolean =
        glyphs.any { glyph ->
            glyph.renderFontKey?.let(TiqianAndroidFontBackend::isSyntheticBoldFace) == true
        }

    fun usesSyntheticItalic(glyphs: List<Glyph>): Boolean =
        glyphs.any { glyph ->
            glyph.renderFontKey?.let(TiqianAndroidFontBackend::isSyntheticItalicFace) == true
        }

    fun platformFontFor(renderFontKey: String): Font? =
        TiqianAndroidFontBackend.platformFontFor(renderFontKey)

    /** Absolute path used by both paint and decoration skip-ink interception. */
    fun glyphPath(
        glyphs: List<Glyph>,
        originX: Float,
        originY: Float,
        fontSize: Float,
        reusablePath: Path? = null,
    ): Path? {
        if (glyphs.isEmpty()) return null
        if (requiresPlatformSyntheticBold(glyphs)) return null
        val result = (reusablePath ?: Path()).apply {
            reset()
            fillType = Path.FillType.WINDING
        }
        for (glyph in glyphs) {
            val key = glyph.renderFontKey ?: return null
            val face = TiqianAndroidFontBackend.faceFor(key) ?: return null
            val outline = scaledOutline(
                faceId = key,
                face = face,
                glyphId = glyph.id,
                fontSize = fontSize,
                syntheticItalic = TiqianAndroidFontBackend.isSyntheticItalicFace(key),
            ) ?: return null
            result.addPath(outline, originX + glyph.x, originY + glyph.y)
        }
        return result
    }

    private fun scaledOutline(
        faceId: String,
        face: NativeFontFace,
        glyphId: UInt,
        fontSize: Float,
        syntheticItalic: Boolean,
    ): Path? {
        val key = OutlineKey(faceId, glyphId, fontSize.toRawBits())
        synchronized(cacheLock) {
            scaledOutlineCache[key]?.let { return it }
        }
        val commands = face.outline(glyphId) ?: return null
        val scale = fontSize / face.unitsPerEm
        val path = decodeOutline(commands, scale).apply {
            if (syntheticItalic) transform(SyntheticItalicMatrix)
        }
        synchronized(cacheLock) {
            scaledOutlineCache[key] = path
            while (scaledOutlineCache.size > MaxCachedScaledOutlines) {
                val iterator = scaledOutlineCache.entries.iterator()
                if (!iterator.hasNext()) break
                iterator.next()
                iterator.remove()
            }
        }
        return path
    }

    private fun decodeOutline(commands: FloatArray, scale: Float): Path {
        val path = Path().apply { fillType = Path.FillType.WINDING }
        var index = 0
        fun x(): Float = commands[index++] * scale
        fun y(): Float = -commands[index++] * scale
        while (index < commands.size) {
            when (commands[index++].toInt()) {
                0 -> path.moveTo(x(), y())
                1 -> path.lineTo(x(), y())
                2 -> path.quadTo(x(), y(), x(), y())
                3 -> path.cubicTo(x(), y(), x(), y(), x(), y())
                4 -> path.close()
                else -> error("Unknown FreeType outline command")
            }
        }
        return path
    }

    private data class OutlineKey(
        val faceId: String,
        val glyphId: UInt,
        val fontSizeBits: Int,
    )

    internal fun transformInkBounds(bounds: Rect, syntheticItalic: Boolean): Rect {
        if (!syntheticItalic) return bounds
        val topShift = SyntheticItalicSkewX * bounds.top
        val bottomShift = SyntheticItalicSkewX * bounds.bottom
        return Rect(
            left = minOf(bounds.left + topShift, bounds.left + bottomShift),
            top = bounds.top,
            right = maxOf(bounds.right + topShift, bounds.right + bottomShift),
            bottom = bounds.bottom,
        )
    }

    private const val SyntheticItalicSkewX = -0.25f
    private val SyntheticItalicMatrix = Matrix().apply { setSkew(SyntheticItalicSkewX, 0f) }
}
