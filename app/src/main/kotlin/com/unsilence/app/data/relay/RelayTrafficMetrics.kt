package com.unsilence.app.data.relay

import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** Process-cumulative mechanism counts. No relay/identity registry and no reset API. */
@Singleton
class RelayTrafficMetrics @Inject constructor() {
    private val reqAccepted = AtomicLong()
    private val replayReqAccepted = AtomicLong()
    private val eventFrames = AtomicLong()
    private val subscriptionDuplicates = AtomicLong()
    private val processorDuplicates = AtomicLong()

    internal fun reqAccepted() { reqAccepted.incrementAndGet() }
    internal fun replayReqAccepted() { replayReqAccepted.incrementAndGet() }
    internal fun eventFrameReceived() { eventFrames.incrementAndGet() }
    internal fun subscriptionDuplicate() { subscriptionDuplicates.incrementAndGet() }
    internal fun processorDuplicate() { processorDuplicates.incrementAndGet() }

    internal fun snapshot(): Map<String, Long> = linkedMapOf(
        "req_accepted" to reqAccepted.get(),
        "replay_req_accepted" to replayReqAccepted.get(),
        "event_frames" to eventFrames.get(),
        "subscription_duplicates" to subscriptionDuplicates.get(),
        "processor_duplicates" to processorDuplicates.get(),
    )
}
