package com.unsilence.app.data.memory

import java.io.DataOutputStream
import java.nio.file.Files

/** Existing small wire-format fixtures use the real file writer, then inspect its bytes. */
internal suspend fun MemoryEventStore.copySnapshotForTest(
    out: DataOutputStream,
    snapshotVersion: Int? = null,
): SnapshotSectionSizes {
    val file = Files.createTempFile("snapshot-wire-", ".bin").toFile()
    try {
        val sizes = file.outputStream().use { stream ->
            if (snapshotVersion == null) saveSnapshotBinary(stream)
            else saveSnapshotBinary(stream, snapshotVersion)
        }
        file.inputStream().use { it.copyTo(out) }
        return sizes
    } finally {
        file.delete()
    }
}
