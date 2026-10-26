package com.unsilence.app.data

import com.unsilence.app.data.memory.RelayList
import com.unsilence.app.data.relay.ConnectionPurpose
import com.unsilence.app.data.relay.normalizeRelayUrl
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Own declared inboxes need persistent connections before the live notification tail. */
internal suspend fun bootstrapNotificationInbox(
    relayList: RelayList?,
    isCurrent: () -> Boolean,
    addPurpose: (String, ConnectionPurpose) -> Unit,
    connect: suspend (List<String>, Long) -> Unit,
    subscribe: () -> Unit,
) {
    currentCoroutineContext().ensureActive()
    if (!isCurrent()) return
    val readUrls = relayList?.read.orEmpty().mapNotNull(::normalizeRelayUrl).distinct()
    if (readUrls.isNotEmpty()) {
        readUrls.forEach { addPurpose(it, ConnectionPurpose.PERSISTENT) }
        // Existing transport gates and capability/blocked-relay policies apply.
        connect(readUrls, 5_000L)
    }
    currentCoroutineContext().ensureActive()
    if (isCurrent()) subscribe()
}
