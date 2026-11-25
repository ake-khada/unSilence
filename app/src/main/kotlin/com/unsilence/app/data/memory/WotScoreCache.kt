package com.unsilence.app.data.memory

import android.util.Log
import androidx.core.util.AtomicFile
import com.unsilence.app.data.relay.WOT_STALENESS_MS
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

internal const val WOT_DISPLAY_WINDOW_MS = 48L * 60 * 60 * 1000
private const val MAX_CACHE_BYTES = 5 * 1024 * 1024

internal fun WotAssertionEntity.freshAt(now: Long) =
    confirmedAt in 1..now && now - confirmedAt < WOT_STALENESS_MS

internal fun WotAssertionEntity.displayableAt(now: Long) =
    confirmedAt in 1..now && now - confirmedAt < WOT_DISPLAY_WINDOW_MS

internal fun WotAssertionEntity.nextAgeChange(now: Long): Long = when {
    freshAt(now) -> confirmedAt + WOT_STALENESS_MS
    displayableAt(now) -> confirmedAt + WOT_DISPLAY_WINDOW_MS
    else -> Long.MAX_VALUE
}

@Serializable
internal data class WotScoreSnapshot(val owner: String, val provider: String, val scores: List<WotAssertionEntity>)

/** Separate from the main snapshot so previous app versions can continue reading their snapshot. */
@OptIn(ExperimentalSerializationApi::class)
internal class WotScoreCache(file: File) {
    private val disk = AtomicFile(file)
    private val json = Json { ignoreUnknownKeys = true; allowSpecialFloatingPointValues = true }

    fun read(): WotScoreSnapshot? = try {
        disk.openRead().use { stream ->
            if (stream.channel.size() !in 1L..MAX_CACHE_BYTES.toLong()) null
            else json.decodeFromStream<WotScoreSnapshot>(stream)
        }
    } catch (_: FileNotFoundException) {
        null
    } catch (error: IOException) {
        Log.w("WotScoreCache", "Could not read cached scores", error)
        null
    } catch (error: SerializationException) {
        Log.w("WotScoreCache", "Invalid score cache", error)
        null
    }

    fun write(snapshot: WotScoreSnapshot?) {
        if (snapshot == null || snapshot.scores.isEmpty()) {
            delete()
            return
        }
        val stream = disk.startWrite()
        try {
            json.encodeToStream(snapshot, stream)
            if (stream.channel.size() > MAX_CACHE_BYTES) throw IOException("Score cache exceeds size limit")
            disk.finishWrite(stream)
        } catch (error: Throwable) {
            disk.failWrite(stream)
            throw error
        }
    }

    fun delete() = disk.delete()
}
