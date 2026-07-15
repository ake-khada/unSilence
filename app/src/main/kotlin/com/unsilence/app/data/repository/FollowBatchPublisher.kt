package com.unsilence.app.data.repository

import android.util.Log
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.auth.SigningManager
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.relay.GLOBAL_RELAY_URLS
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.RelayPreferencesStore
import com.unsilence.app.data.relay.buildFollowContactTags
import com.unsilence.app.data.relay.toEventJson
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "FollowBatchPublisher"

@Singleton
class FollowBatchPublisher @Inject constructor(
    private val keyManager: KeyManager,
    private val signingManager: SigningManager,
    private val memoryEventStore: MemoryEventStore,
    private val relayPool: RelayPool,
    private val relayPreferencesStore: RelayPreferencesStore,
) {
    /** Signs and publishes one contact-list event for the entire local selection. */
    suspend fun addFollows(selectedPubkeys: Collection<String>): Set<String>? {
        val ownPubkey = keyManager.getPublicKeyHex() ?: return null
        val existing = memoryEventStore.getFollows(ownPubkey).orEmpty()
        val tags = buildFollowContactTags(existing, selectedPubkeys)
        val merged = tags.mapTo(linkedSetOf()) { it[1] }
        val now = System.currentTimeMillis() / 1000L
        val createdAt = maxOf(now, (memoryEventStore.getFollowsCreatedAt(ownPubkey) ?: 0L) + 1L)
        val template = EventTemplate<Event>(
            createdAt = createdAt,
            kind = 3,
            tags = tags.map(List<String>::toTypedArray).toTypedArray(),
            content = "",
        )
        val signed = signingManager.sign(template) ?: return null

        // The Following subscription and empty-state gate react before relay echo.
        memoryEventStore.updateFollows(ownPubkey, merged, createdAt)

        val ownWriteRelays = memoryEventStore.writeRelaysFor(ownPubkey)
        val targets = (
            ownWriteRelays.ifEmpty { GLOBAL_RELAY_URLS } +
                relayPreferencesStore.indexerRelayUrlsSnapshot()
        ).distinct()
        relayPool.publishToRelays(toEventJson(signed), targets)
        Log.i(TAG, "Published one kind-3 with ${merged.size} follows to ${targets.size} relays")
        return merged
    }
}
