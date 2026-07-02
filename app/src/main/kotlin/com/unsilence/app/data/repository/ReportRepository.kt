package com.unsilence.app.data.repository

import android.util.Log
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.auth.SigningManager
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.model.ReportType
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.toEventJson
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ReportRepo"

@Singleton
class ReportRepository @Inject constructor(
    private val signingManager: SigningManager,
    private val memoryEventStore: MemoryEventStore,
    private val keyManager: KeyManager,
    private val relayPool: RelayPool,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Fire-and-forget report. Returns immediately; publish happens in background. */
    fun reportEvent(
        eventId: String,
        authorPubkey: String,
        type: ReportType,
        reason: String = "",
    ) {
        scope.launch {
            val ownPubkey = keyManager.getPublicKeyHex() ?: return@launch
            val template = EventTemplate<Event>(
                createdAt = System.currentTimeMillis() / 1000L,
                kind = 1984,
                tags = arrayOf(
                    arrayOf("e", eventId, type.tagValue),
                    arrayOf("p", authorPubkey, type.tagValue),
                ),
                content = reason,
            )
            val signed = signingManager.sign(template) ?: run {
                Log.w(TAG, "Sign failed for report ${eventId.take(8)}…"); return@launch
            }
            val writeRelays = memoryEventStore.writeRelaysFor(ownPubkey)
            relayPool.publishToRelays(toEventJson(signed), writeRelays)
            Log.i(TAG, "Reported ${eventId.take(8)}… type=${type.tagValue}")
        }
    }

    /** Fire-and-forget NIP-56 profile report. Returns immediately; publish happens in background. */
    fun reportProfile(
        pubkey: String,
        type: ReportType,
        reason: String = "",
    ) {
        scope.launch {
            val ownPubkey = keyManager.getPublicKeyHex() ?: return@launch
            val template = EventTemplate<Event>(
                createdAt = System.currentTimeMillis() / 1000L,
                kind = 1984,
                tags = arrayOf(
                    arrayOf("p", pubkey, type.tagValue),
                ),
                content = reason,
            )
            val signed = signingManager.sign(template) ?: run {
                Log.w(TAG, "Sign failed for profile report ${pubkey.take(8)}…"); return@launch
            }
            val writeRelays = memoryEventStore.writeRelaysFor(ownPubkey)
            relayPool.publishToRelays(toEventJson(signed), writeRelays)
            Log.i(TAG, "Reported profile ${pubkey.take(8)}… type=${type.tagValue}")
        }
    }
}
