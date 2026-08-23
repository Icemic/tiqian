package org.tiqian.shaping.android.nativefont

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

internal sealed interface PreparedAndroidFontSource {
    val digestHex: String
    val sizeBytes: Long

    data class FileMapping(
        override val digestHex: String,
        override val sizeBytes: Long,
        val path: String,
    ) : PreparedAndroidFontSource

    data class DirectBuffer(
        override val digestHex: String,
        override val sizeBytes: Long,
        val buffer: ByteBuffer,
    ) : PreparedAndroidFontSource
}

/** Controlled immutable source accepted by the API 23+ native font backend. */
sealed class AndroidFontSource protected constructor(
    val label: String,
) {
    internal abstract fun locatorKey(context: Context): String
    internal abstract fun prepare(context: Context): PreparedAndroidFontSource

    companion object {
        fun bytes(bytes: ByteArray, label: String = "host-byte-array"): AndroidFontSource =
            DirectBufferSource(copyToDirectBuffer(bytes), label, "bytes")

        fun file(file: File, label: String = file.absolutePath): AndroidFontSource =
            FileSource(file, label)

        fun asset(path: String, label: String = "asset:$path"): AndroidFontSource =
            AssetSource(path, label)

        internal fun directBuffer(buffer: ByteBuffer, label: String): AndroidFontSource =
            DirectBufferSource(buffer.immutableDirectView(), label, "buffer")
    }

    private class DirectBufferSource(
        private val buffer: ByteBuffer,
        label: String,
        kind: String,
    ) : AndroidFontSource(label) {
        private val digestHex = sha256Hex(buffer)
        private val key = "$kind:sha256:$digestHex:${buffer.capacity()}"

        override fun locatorKey(context: Context): String = key

        override fun prepare(context: Context): PreparedAndroidFontSource =
            PreparedAndroidFontSource.DirectBuffer(
                digestHex = digestHex,
                sizeBytes = buffer.capacity().toLong(),
                buffer = buffer,
            )
    }

    private class FileSource(
        private val file: File,
        label: String,
    ) : AndroidFontSource(label) {
        override fun locatorKey(context: Context): String {
            val canonical = file.canonicalFile
            return "file:${canonical.path}:${canonical.length()}:${canonical.lastModified()}"
        }

        override fun prepare(context: Context): PreparedAndroidFontSource {
            val canonical = file.canonicalFile
            require(canonical.isFile) { "Font file does not exist: ${canonical.path}" }
            val size = canonical.length()
            require(size > 0L) { "Font file is empty: ${canonical.path}" }
            return PreparedAndroidFontSource.FileMapping(
                digestHex = sha256Hex(canonical),
                sizeBytes = size,
                path = canonical.path,
            )
        }
    }

    private class AssetSource(
        private val path: String,
        label: String,
    ) : AndroidFontSource(label) {
        override fun locatorKey(context: Context): String = "asset:${context.packageName}:$path"

        override fun prepare(context: Context): PreparedAndroidFontSource {
            val bytes = context.assets.open(path).use { it.readBytes() }
            val buffer = copyToDirectBuffer(bytes)
            return PreparedAndroidFontSource.DirectBuffer(
                digestHex = sha256Hex(buffer),
                sizeBytes = buffer.capacity().toLong(),
                buffer = buffer,
            )
        }
    }
}

private fun copyToDirectBuffer(bytes: ByteArray): ByteBuffer {
    require(bytes.isNotEmpty()) { "Font bytes must not be empty" }
    return ByteBuffer.allocateDirect(bytes.size).apply {
        put(bytes)
        position(0)
    }.asReadOnlyBuffer()
}

private fun ByteBuffer.immutableDirectView(): ByteBuffer {
    require(isDirect) { "Font ByteBuffer must be direct" }
    val view = duplicate().apply { position(0) }.slice().asReadOnlyBuffer()
    require(view.hasRemaining()) { "Font ByteBuffer must not be empty" }
    return view
}

private fun sha256Hex(buffer: ByteBuffer): String =
    MessageDigest.getInstance("SHA-256")
        .apply { update(buffer.duplicate().apply { position(0) }) }
        .digest()
        .toHex()

private fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val chunk = ByteArray(DefaultDigestChunkBytes)
        while (true) {
            val count = input.read(chunk)
            if (count < 0) break
            if (count > 0) digest.update(chunk, 0, count)
        }
    }
    return digest.digest().toHex()
}

private fun ByteArray.toHex(): String = joinToString("") { byte ->
    HexDigits[(byte.toInt() ushr 4) and 0x0F].toString() + HexDigits[byte.toInt() and 0x0F]
}

private const val HexDigits = "0123456789abcdef"
private const val DefaultDigestChunkBytes = 64 * 1024
