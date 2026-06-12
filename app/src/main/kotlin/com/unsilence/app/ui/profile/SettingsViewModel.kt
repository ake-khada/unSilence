package com.unsilence.app.ui.profile

import androidx.lifecycle.ViewModel
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.relay.RelayPool
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Minimal Settings VM — backs the account card and the Relays "N online" badge. */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    keyManager: KeyManager,
    private val memoryEventStore: MemoryEventStore,
    private val relayPool: RelayPool,
) : ViewModel() {

    val ownPubkey: String? = keyManager.getPublicKeyHex()

    val ownNpub: String? = ownPubkey?.let { runCatching { it.hexToByteArray().toNpub() }.getOrNull() }

    fun ownProfile(): UserEntity? = ownPubkey?.let { memoryEventStore.getUserEntity(it) }

    /** One-shot snapshot: how many of the user's own kind-10002 relays are connected now.
     *  No ticker — read on screen open, per the lean rule. */
    fun onlineRelayCount(): Int {
        val pk = ownPubkey ?: return 0
        val urls = memoryEventStore.writeRelaysFor(pk) + memoryEventStore.readRelaysFor(pk)
        return relayPool.connectedCountOf(urls)
    }
}
