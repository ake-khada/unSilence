package com.unsilence.app.data.memory

import kotlinx.coroutines.ensureActive
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlin.coroutines.CoroutineContext

/** Streams one snapshot into AtomicFile's temporary file; never owns or closes it. */
internal class SnapshotOutput(
    private val file: FileOutputStream,
    private val context: CoroutineContext,
) {
    private val headerPosition = file.channel.position()

    // Cancellation is checked at disk-write boundaries, not for every primitive.
    // Large individual strings bypass this fixed buffer without growing it.
    val data = DataOutputStream(BufferedOutputStream(object : OutputStream() {
        override fun write(value: Int) {
            context.ensureActive()
            file.write(value)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            context.ensureActive()
            file.write(bytes, offset, length)
        }

        override fun flush() = file.flush()
    }, 8 * 1024))

    fun section(write: (DataOutputStream) -> Unit): Int {
        context.ensureActive()
        val start = data.size()
        write(data)
        return data.size() - start
    }

    fun finish(header: ByteBuffer) {
        context.ensureActive()
        data.flush() // Must precede positional writes, or buffering overwrites the header.
        var position = headerPosition
        while (header.hasRemaining()) {
            context.ensureActive()
            val written = file.channel.write(header, position)
            if (written <= 0) throw IOException("Snapshot header write made no progress")
            position += written
        }
        context.ensureActive()
        // AtomicFile.finishWrite still owns fsync/close/rename; failure or
        // cancellation here must take the scheduler's failWrite path instead.
    }
}
