package com.unsilence.app.data.memory

import com.unsilence.app.data.relay.RelayTrafficMetrics
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Owned by the serialized metrics writer, never called from a relay callback. */
internal class RelayTrafficSnapshot(private val metrics: RelayTrafficMetrics) {
    private var previousRx: Long? = null
    private var previousTx: Long? = null
    private var invalid = false

    fun sample(elapsedMs: Long, pid: Int, uid: Int, rx: Long, tx: Long, reason: String): String {
        // A reset invalidates the process series, even if a subsequent read rises.
        // Negative platform values mean unsupported/unavailable, not zero traffic.
        if (rx < 0 || tx < 0 || previousRx?.let { rx < it } == true || previousTx?.let { tx < it } == true) {
            invalid = true
        }
        previousRx = rx
        previousTx = tx
        return buildJsonObject {
            put("v", 1)
            put("elapsed_ms", elapsedMs)
            put("pid", pid)
            put("uid", uid)
            put("reason", reason)
            put("uid_status", if (invalid) "invalid" else "ok")
            if (invalid) {
                put("uid_rx", JsonNull)
                put("uid_tx", JsonNull)
            } else {
                put("uid_rx", rx)
                put("uid_tx", tx)
            }
            metrics.snapshot().forEach { (key, value) -> put(key, value) }
        }.toString()
    }
}
