package com.unsilence.app.data.relay

import com.unsilence.app.data.memory.NostrEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.decodeFromString
import java.util.concurrent.CopyOnWriteArrayList

/** Trivial RelayTransport fake: records sends, controllable success per URL. */
class FakeRelayTransport : RelayTransport {
    data class SentMessage(val url: String, val msg: String)
    data class ConnectRequest(val urls: List<String>, val requestClass: RelayRequestClass)

    val sends = CopyOnWriteArrayList<SentMessage>()
    val connectRequests = CopyOnWriteArrayList<ConnectRequest>()
    var sendShouldSucceed: (String) -> Boolean = { true }
    var connectAndAwaitReturns: Int = 0
    val rateLimitedUrls: MutableSet<String> = mutableSetOf()

    override suspend fun connectAndAwait(
        relayUrls: List<String>,
        timeoutMs: Long,
        forceEvict: Boolean,
        requestClass: RelayRequestClass,
    ): Int {
        connectRequests.add(ConnectRequest(relayUrls, requestClass))
        return connectAndAwaitReturns
    }

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
    private val searchOnly = mutableSetOf<String>()
    val successfulRequests = CopyOnWriteArrayList<String>()

    override fun shouldSkip(relayUrl: String): Boolean = relayUrl in skipped

    override fun shouldSkipRequest(
        relayUrl: String,
        requestClass: RelayRequestClass,
        bypassCooldown: Boolean,
    ): Boolean =
        (!bypassCooldown && relayUrl in skipped) ||
            (relayUrl in searchOnly && requestClass == RelayRequestClass.GENERAL)

    override fun recordRequestSuccess(relayUrl: String) {
        successfulRequests.add(relayUrl)
    }

    fun markSkipped(url: String) { skipped.add(url) }
    fun markSearchOnly(url: String) { searchOnly.add(url) }
}

/** No-op TimelineEventLoader for tests. */
class StubEventLoader : TimelineEventLoader {
    override suspend fun getEvents(ids: List<String>): TimelineEventResolution =
        TimelineEventResolution(emptyList(), ids.distinct())
}

/** Create a [javax.inject.Provider] of a no-op [TimelineService] for MES tests. */
fun stubTimelineServiceProvider(): javax.inject.Provider<TimelineService> {
    val svc = TimelineService(
        Subscription(
            FakeRelayTransport(),
            FakeTapRegistration(),
            FakeReconnectSource(),
            FakeRelayCapabilitiesStore(),
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

    /**
     * Test helper: adapt wire fixtures at the EventProcessor boundary. EVENT
     * fixtures become typed envelopes; control messages remain raw. Signature
     * validity itself is covered by EventProcessor/SignatureVerifier tests.
     */
    fun fire(raw: String, relayUrl: String) {
        val message = if (raw.startsWith("[\"EVENT\"")) {
            val subId = extractSubscriptionIdFromRaw(raw) ?: return
            val start = findEventObjectStart(raw)
            val end = if (start >= 0) findMatchingBraceEnd(raw, start) else -1
            if (start < 0 || end < 0) return
            val dto = NostrJson.decodeFromString<EventDto>(raw.substring(start, end + 1))
            RelayTapMessage.VerifiedEvent(subId, dto.toNostrEvent(relayUrl))
        } else {
            RelayTapMessage.Control(raw, relayUrl)
        }
        for (tap in registrations) tap.onMessage(message)
    }
}
