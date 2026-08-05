package org.tiqian.shaping.nativefont

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import org.tiqian.core.Glyph
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
    ): Boolean {
        val path = glyphPath(glyphs, originX, originY, fontSize) ?: return false
        if (!path.isEmpty) canvas.drawPath(path, paint)
        return true
    }

    /** True when these glyph ids were produced by faces retained by this backend. */
    fun ownsGlyphs(glyphs: List<Glyph>): Boolean =
        glyphs.isNotEmpty() && glyphs.all { glyph ->
            glyph.renderFontKey?.let(TiqianAndroidFontBackend::faceFor) != null
        }

    /** Absolute path used by both paint and decoration skip-ink interception. */
    fun glyphPath(
        glyphs: List<Glyph>,
        originX: Float,
        originY: Float,
        fontSize: Float,
    ): Path? {
        if (glyphs.isEmpty()) return null
        val result = Path().apply { fillType = Path.FillType.WINDING }
        for (glyph in glyphs) {
            val key = glyph.renderFontKey ?: return null
            val face = TiqianAndroidFontBackend.faceFor(key) ?: return null
            val outline = scaledOutline(key, face, glyph.id, fontSize) ?: return null
            result.addPath(outline, originX + glyph.x, originY + glyph.y)
        }
        return result
    }

    private fun scaledOutline(
        faceId: String,
        face: NativeFontFace,
        glyphId: UInt,
        fontSize: Float,
    ): Path? {
        val key = OutlineKey(faceId, glyphId, fontSize.toRawBits())
        synchronized(cacheLock) {
            scaledOutlineCache[key]?.let { return it }
        }
        val commands = face.outline(glyphId) ?: return null
        val scale = fontSize / face.unitsPerEm
        val path = decodeOutline(commands, scale)
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
}
