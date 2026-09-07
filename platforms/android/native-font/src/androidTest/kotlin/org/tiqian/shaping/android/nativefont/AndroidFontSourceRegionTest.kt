package org.tiqian.shaping.android.nativefont

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AndroidFontSourceRegionTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun storedAssetPreparesAsADescriptorRegionOverItsApkBytes() {
        val expected = context.assets.open("probe.tiqianprobe").use { it.readBytes() }
        val prepared = AndroidFontSource.asset("probe.tiqianprobe").prepare(context)
        assertTrue(prepared is PreparedAndroidFontSource.DescriptorRegion, prepared::class.simpleName)
        assertEquals(expected.size.toLong(), prepared.sizeBytes)
        assertEquals(sha256Hex(expected), prepared.digestHex)
        val mapped = prepared.descriptor.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { input ->
                input.channel.position(prepared.offset)
                ByteArray(expected.size).also { bytes ->
                    var read = 0
                    while (read < bytes.size) {
                        val count = input.read(bytes, read, bytes.size - read)
                        check(count > 0)
                        read += count
                    }
                }
            }
        }
        assertContentEquals(expected, mapped)
    }

    @Test
    fun descriptorRegionMapsAnUnalignedOffsetToTheSameGlyphs() {
        val font = listOf(
            File("/system/fonts/Roboto-Regular.ttf"),
            File("/system/fonts/NotoSansCJK-Regular.ttc"),
        ).firstOrNull(File::isFile)
        assumeTrue("No system font", font != null)
        val prefix = 4099
        val bytes = font!!.readBytes()
        val padded = File.createTempFile("region", ".bin", context.cacheDir)
        try {
            padded.outputStream().use { output ->
                output.write(ByteArray(prefix) { 0x5A })
                output.write(bytes)
            }
            val fd = ParcelFileDescriptor.open(padded, ParcelFileDescriptor.MODE_READ_ONLY).detachFd()
            val regionSource = NativeFontBridge.nativeRegisterDescriptorRegionSource(fd, prefix.toLong(), bytes.size.toLong())
            val fileSource = NativeFontBridge.nativeRegisterFileSource(font.absolutePath)
            val regionFace = NativeFontBridge.nativeCreateFace(regionSource, 0, IntArray(0), FloatArray(0))
            val fileFace = NativeFontBridge.nativeCreateFace(fileSource, 0, IntArray(0), FloatArray(0))
            try {
                val fromRegion = NativeFontFace(regionFace, NativeFontBridge.nativeUnitsPerEm(regionFace))
                    .shape("Ag", 32f, "en", 2, emptyList())
                val fromFile = NativeFontFace(fileFace, NativeFontBridge.nativeUnitsPerEm(fileFace))
                    .shape("Ag", 32f, "en", 2, emptyList())
                assertTrue(fromRegion.glyphIds.all { it != 0 })
                assertContentEquals(fromFile.glyphIds, fromRegion.glyphIds)
                assertEquals(fromFile.advance, fromRegion.advance)
            } finally {
                NativeFontBridge.nativeReleaseFace(regionFace)
                NativeFontBridge.nativeReleaseFace(fileFace)
                NativeFontBridge.nativeReleaseSource(regionSource)
                NativeFontBridge.nativeReleaseSource(fileSource)
            }
        } finally {
            padded.delete()
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
