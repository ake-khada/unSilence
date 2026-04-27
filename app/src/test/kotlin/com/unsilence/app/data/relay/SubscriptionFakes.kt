package com.unsilence.app.data.relay

import java.util.concurrent.CopyOnWriteArrayList

/** Trivial RelayTransport fake: records sends, controllable success per URL. */
class FakeRelayTransport : RelayTransport {
    data class SentMessage(val url: String, val msg: String)

    val sends = CopyOnWriteArrayList<SentMessage>()
    var sendShouldSucceed: (String) -> Boolean = { true }
    var connectAndAwaitReturns: Int = 0

    override suspend fun connectAndAwait(
        relayUrls: List<String>,
        timeoutMs: Long,
        forceEvict: Boolean,
    ): Int = connectAndAwaitReturns

    override fun sendToRelay(url: String, msg: String): Boolean {
        val ok = sendShouldSucceed(url)
        if (ok) sends.add(SentMessage(url, msg))
        return ok
    }
}

/** Trivial TapRegistration fake: records and exposes a fire() method. */
class FakeTapRegistration : TapRegistration {
    val registrations = CopyOnWriteArrayList<RelayMessageTap>()

    override fun registerTap(tap: RelayMessageTap) {
        if (!registrations.contains(tap)) registrations.add(tap)
    }

    override fun unregisterTap(tap: RelayMessageTap) {
        registrations.remove(tap)
    }

    /** Test helper: fire all registered taps synchronously. */
    fun fire(raw: String, relayUrl: String) {
        for (tap in registrations) tap.onMessage(raw, relayUrl)
    }
}
