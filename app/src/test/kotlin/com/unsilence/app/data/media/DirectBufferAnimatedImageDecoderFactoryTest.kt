package com.unsilence.app.data.media

import coil3.decode.ImageSource
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class DirectBufferAnimatedImageDecoderFactoryTest {
    @Test
    fun `animated source strips file backing before ImageDecoder sees it`() {
        val file = File.createTempFile("animated-source-", ".gif")
        file.writeBytes(byteArrayOf('G'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte()))
        val original = ImageSource(file.absolutePath.toPath(), FileSystem.SYSTEM)
        val directBufferSource = directBufferAnimatedSource(original)

        try {
            assertNotNull("fixture must begin file-backed", original.fileOrNull())
            assertNull("wrapped source must force Coil's direct-buffer fallback", directBufferSource.fileOrNull())
            assertNull("metadata must not restore a file or URI source", directBufferSource.metadata)
        } finally {
            directBufferSource.close()
            original.close()
            file.delete()
        }
    }
}
