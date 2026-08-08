package com.unsilence.app.data.media

import android.media.MediaDataSource
import com.unsilence.app.data.BROWSER_USER_AGENT
import com.unsilence.app.data.network.isAllowedUntrustedHttpUrl
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.Request
import okio.BufferedSource
import java.io.Closeable
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

internal const val REMOTE_THUMBNAIL_MAX_NETWORK_BYTES = 16L * 1024 * 1024
private const val REMOTE_THUMBNAIL_RANGE_CHUNK_BYTES = 256 * 1024
private const val REMOTE_THUMBNAIL_MAX_RANGE_BYTES = 1024 * 1024
private const val REMOTE_THUMBNAIL_RANGE_CACHE_BYTES = 1024 * 1024
private const val REMOTE_THUMBNAIL_CALL_TIMEOUT_MS = 5_000L
private const val REMOTE_THUMBNAIL_TOTAL_TIMEOUT_MS = 8_000L

/**
 * Android media-framework adapter for a bounded, seekable OkHttp byte source.
 *
 * The framework never receives the remote URL, so every request and redirect
 * remains on the injected guarded client's network-interceptor path.
 */
internal class GuardedRemoteMediaDataSource(
    url: HttpUrl,
    callFactory: Call.Factory,
    maxNetworkBytes: Long = REMOTE_THUMBNAIL_MAX_NETWORK_BYTES,
) : MediaDataSource() {
    private val reader = GuardedHttpRangeReader(
        url = url,
        callFactory = callFactory,
        maxNetworkBytes = maxNetworkBytes,
    )

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
        reader.readAt(position, buffer, offset, size)

    override fun getSize(): Long = reader.size()

    override fun close() = reader.close()
}

internal class RemoteMediaByteLimitException(limit: Long) :
    IOException("remote thumbnail byte limit exceeded ($limit bytes)")

/**
 * Synchronous range reader used by [GuardedRemoteMediaDataSource]. Kept free
 * of Android framework calls so its transport, seeking and limits are JVM-testable.
 */
internal class GuardedHttpRangeReader(
    private val url: HttpUrl,
    private val callFactory: Call.Factory,
    private val maxNetworkBytes: Long = REMOTE_THUMBNAIL_MAX_NETWORK_BYTES,
    private val minimumFetchBytes: Int = REMOTE_THUMBNAIL_RANGE_CHUNK_BYTES,
    private val maxCachedBytes: Int = REMOTE_THUMBNAIL_RANGE_CACHE_BYTES,
    private val callTimeoutMs: Long = REMOTE_THUMBNAIL_CALL_TIMEOUT_MS,
    totalTimeoutMs: Long = REMOTE_THUMBNAIL_TOTAL_TIMEOUT_MS,
) : Closeable {
    private data class RangeChunk(val start: Long, val bytes: ByteArray) {
        val endExclusive: Long get() = start + bytes.size
    }

    private data class PartialRange(val start: Long, val endInclusive: Long, val total: Long?)

    private val cachedRanges = ArrayDeque<RangeChunk>()
    private var cachedByteCount = 0
    private var knownSize = UNKNOWN_SIZE
    private var fetchedByteCount = 0L
    private val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(totalTimeoutMs)

    @Volatile
    private var activeCall: Call? = null

    @Volatile
    private var closed = false

    init {
        require(isAllowedUntrustedHttpUrl(url)) { "unsafe remote media URL" }
        require(maxNetworkBytes > 0L) { "maxNetworkBytes must be positive" }
        require(minimumFetchBytes > 0) { "minimumFetchBytes must be positive" }
        require(maxCachedBytes >= 0) { "maxCachedBytes must not be negative" }
        require(callTimeoutMs > 0L) { "callTimeoutMs must be positive" }
        require(totalTimeoutMs > 0L) { "totalTimeoutMs must be positive" }
    }

    internal val networkBytesRead: Long
        @Synchronized get() = fetchedByteCount

    @Synchronized
    fun size(): Long {
        ensureOpen()
        if (knownSize == UNKNOWN_SIZE && cachedRanges.none { it.start == 0L }) {
            fetchRange(position = 0L, requestedBytes = 1)
        }
        return knownSize
    }

    @Synchronized
    fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        ensureOpen()
        if (position < 0L || offset < 0 || size < 0 || offset > buffer.size - size) {
            throw IOException("invalid remote media read")
        }
        if (size == 0) return 0
        if (knownSize != UNKNOWN_SIZE && position >= knownSize) return -1

        val chunk = findCached(position) ?: fetchRange(position, size)
        if (chunk.bytes.isEmpty()) return -1

        val sourceOffset = (position - chunk.start).toInt()
        if (sourceOffset !in chunk.bytes.indices) return -1
        val copied = min(size, chunk.bytes.size - sourceOffset)
        System.arraycopy(chunk.bytes, sourceOffset, buffer, offset, copied)
        return copied
    }

    override fun close() {
        if (closed) return
        closed = true
        activeCall?.cancel()
        synchronized(this) {
            cachedRanges.clear()
            cachedByteCount = 0
        }
    }

    private fun findCached(position: Long): RangeChunk? {
        val iterator = cachedRanges.iterator()
        while (iterator.hasNext()) {
            val chunk = iterator.next()
            if (position >= chunk.start && position < chunk.endExclusive) return chunk
        }
        return null
    }

    private fun fetchRange(position: Long, requestedBytes: Int): RangeChunk {
        val remainingBudget = maxNetworkBytes - fetchedByteCount
        if (remainingBudget <= 0L) throw RemoteMediaByteLimitException(maxNetworkBytes)

        var fetchBytes = max(requestedBytes, minimumFetchBytes).toLong()
            .coerceAtMost(remainingBudget)
            .coerceAtMost(REMOTE_THUMBNAIL_MAX_RANGE_BYTES.toLong())
            .coerceAtMost(Int.MAX_VALUE.toLong())
        if (knownSize != UNKNOWN_SIZE) {
            fetchBytes = fetchBytes.coerceAtMost(knownSize - position)
        }
        if (fetchBytes <= 0L) return RangeChunk(position, ByteArray(0))
        if (position > Long.MAX_VALUE - fetchBytes) throw IOException("remote media range overflow")

        val endInclusive = position + fetchBytes - 1L
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$position-$endInclusive")
            .header("Accept-Encoding", "identity")
            .header("User-Agent", BROWSER_USER_AGENT)
            .build()
        val remainingNanos = deadlineNanos - System.nanoTime()
        if (remainingNanos <= 0L) throw IOException("remote thumbnail extraction timed out")
        val call = callFactory.newCall(request)
        val perCallNanos = min(TimeUnit.MILLISECONDS.toNanos(callTimeoutMs), remainingNanos)
        call.timeout().timeout(perCallNanos, TimeUnit.NANOSECONDS)
        activeCall = call
        if (closed) {
            call.cancel()
            throw IOException("remote media source is closed")
        }

        try {
            call.execute().use { response ->
                val body = response.body
                val chunk = when (response.code) {
                    206 -> {
                        val contentRange = parsePartialRange(response.header("Content-Range"))
                            ?: throw IOException("invalid partial media response")
                        if (contentRange.start != position || contentRange.endInclusive < position) {
                            throw IOException("misaligned partial media response")
                        }
                        contentRange.total?.let(::recordKnownSize)
                        val responseBytes = contentRange.endInclusive - contentRange.start + 1L
                        val allowedBytes = min(fetchBytes, responseBytes).toInt()
                        RangeChunk(position, readBounded(body.source(), allowedBytes))
                    }

                    200 -> {
                        body.contentLength().takeIf { it >= 0L }?.let(::recordKnownSize)
                        if (knownSize != UNKNOWN_SIZE && position >= knownSize) {
                            return RangeChunk(position, ByteArray(0))
                        }
                        if (position > 0L) {
                            val skipped = skipBounded(body.source(), position)
                            if (skipped < position) {
                                if (knownSize == UNKNOWN_SIZE) recordKnownSize(skipped)
                                return RangeChunk(position, ByteArray(0))
                            }
                        }
                        val bytes = readBounded(body.source(), fetchBytes.toInt())
                        if (body.contentLength() < 0L && bytes.size < fetchBytes) {
                            recordKnownSize(position + bytes.size)
                        }
                        RangeChunk(position, bytes)
                    }

                    416 -> {
                        parseUnsatisfiedSize(response.header("Content-Range"))?.let(::recordKnownSize)
                        if (knownSize != UNKNOWN_SIZE && position >= knownSize) {
                            RangeChunk(position, ByteArray(0))
                        } else {
                            throw IOException("unexpected remote media range rejection")
                        }
                    }

                    else -> throw IOException("remote media HTTP ${response.code}")
                }
                cache(chunk)
                return chunk
            }
        } finally {
            if (activeCall === call) activeCall = null
        }
    }

    private fun readBounded(source: BufferedSource, requestedBytes: Int): ByteArray {
        val allowed = min(requestedBytes.toLong(), maxNetworkBytes - fetchedByteCount).toInt()
        if (allowed <= 0) throw RemoteMediaByteLimitException(maxNetworkBytes)
        val output = ByteArray(allowed)
        var readTotal = 0
        while (readTotal < allowed) {
            val read = source.read(output, readTotal, allowed - readTotal)
            if (read < 0) break
            if (read == 0) throw IOException("remote media source made no progress")
            fetchedByteCount += read
            readTotal += read
        }
        return if (readTotal == output.size) output else output.copyOf(readTotal)
    }

    /** Returns the actual number of network bytes discarded. */
    private fun skipBounded(source: BufferedSource, requestedSkip: Long): Long {
        val scratch = ByteArray(8 * 1024)
        var skipped = 0L
        while (skipped < requestedSkip) {
            val remainingBudget = maxNetworkBytes - fetchedByteCount
            if (remainingBudget <= 0L) throw RemoteMediaByteLimitException(maxNetworkBytes)
            val next = min(
                min(requestedSkip - skipped, remainingBudget),
                scratch.size.toLong(),
            ).toInt()
            val read = source.read(scratch, 0, next)
            if (read < 0) break
            if (read == 0) throw IOException("remote media source made no progress")
            fetchedByteCount += read
            skipped += read
        }
        return skipped
    }

    private fun recordKnownSize(size: Long) {
        if (size < 0L) throw IOException("negative remote media size")
        if (knownSize != UNKNOWN_SIZE && knownSize != size) {
            throw IOException("remote media size changed during extraction")
        }
        knownSize = size
    }

    private fun cache(chunk: RangeChunk) {
        if (chunk.bytes.isEmpty() || chunk.bytes.size > maxCachedBytes) return
        while (cachedByteCount + chunk.bytes.size > maxCachedBytes && cachedRanges.isNotEmpty()) {
            cachedByteCount -= cachedRanges.removeFirst().bytes.size
        }
        cachedRanges.addLast(chunk)
        cachedByteCount += chunk.bytes.size
    }

    private fun ensureOpen() {
        if (closed) throw IOException("remote media source is closed")
    }

    private companion object {
        const val UNKNOWN_SIZE = -1L
        val PARTIAL_CONTENT_RANGE = Regex(
            """^bytes\s+(\d+)-(\d+)/(\d+|\*)$""",
            RegexOption.IGNORE_CASE,
        )
        val UNSATISFIED_CONTENT_RANGE = Regex(
            """^bytes\s+\*/(\d+)$""",
            RegexOption.IGNORE_CASE,
        )

        fun parsePartialRange(raw: String?): PartialRange? {
            val match = raw?.trim()?.let(PARTIAL_CONTENT_RANGE::matchEntire) ?: return null
            val start = match.groupValues[1].toLongOrNull() ?: return null
            val end = match.groupValues[2].toLongOrNull() ?: return null
            val total = match.groupValues[3].takeUnless { it == "*" }?.toLongOrNull()
            if (end < start || total != null && (total <= end || total < 0L)) return null
            return PartialRange(start, end, total)
        }

        fun parseUnsatisfiedSize(raw: String?): Long? =
            raw?.trim()
                ?.let(UNSATISFIED_CONTENT_RANGE::matchEntire)
                ?.groupValues
                ?.get(1)
                ?.toLongOrNull()
    }
}
