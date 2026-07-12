package com.unsilence.app.data.relay

import com.unsilence.app.data.auth.SignatureVerifier
import com.unsilence.app.data.memory.NostrEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.CopyOnWriteArrayList

/** Trivial RelayTransport fake: records sends, controllable success per URL. */
class FakeRelayTransport : RelayTransport {
    data class SentMessage(val url: String, val msg: String)

    val sends = CopyOnWriteArrayList<SentMessage>()
    var sendShouldSucceed: (String) -> Boolean = { true }
    var connectAndAwaitReturns: Int = 0
    val rateLimitedUrls: MutableSet<String> = mutableSetOf()

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

    override fun isRateLimited(url: String): Boolean = url in rateLimitedUrls
}

/** ReconnectSource fake: no-op SharedFlow, no emissions. */
class FakeReconnectSource : ReconnectSource {
    override val onRelayReconnected: SharedFlow<String> = MutableSharedFlow()
}

/** Test-only [RelaySkipCheck] — never skips unless explicitly told. */
class FakeRelayCapabilitiesStore : RelaySkipCheck {
    private val skipped = mutableSetOf<String>()

    override fun shouldSkip(relayUrl: String): Boolean = relayUrl in skipped

    fun markSkipped(url: String) { skipped.add(url) }
}

class FakeSignatureVerifier(private val result: Boolean = true) : SignatureVerifier() {
    override fun verify(event: NostrEvent): Boolean = result
}

/** No-op TimelineEventLoader for tests. */
class StubEventLoader : TimelineEventLoader {
    override suspend fun getEvents(ids: List<String>): List<NostrEvent> = emptyList()
}

/** Create a [javax.inject.Provider] of a no-op [TimelineService] for MES tests. */
fun stubTimelineServiceProvider(): javax.inject.Provider<TimelineService> {
    val svc = TimelineService(
        Subscription(
            FakeRelayTransport(),
            FakeTapRegistration(),
            FakeReconnectSource(),
            FakeRelayCapabilitiesStore(),
            FakeSignatureVerifier(),
        ),
        StubEventLoader(),
    )
    return javax.inject.Provider { svc }
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
