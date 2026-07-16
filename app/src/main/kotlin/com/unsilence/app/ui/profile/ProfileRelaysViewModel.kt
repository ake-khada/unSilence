package com.unsilence.app.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.ProfileRelayFacts
import com.unsilence.app.data.relay.ProfilePipeline
import com.unsilence.app.data.relay.Nip11Fetcher
import com.unsilence.app.data.relay.normalizeRelayUrl
import com.unsilence.app.data.relay.relayIdentityPrefetchUrls
import com.unsilence.app.data.relay.shouldRefreshRelayIdentity
import com.unsilence.app.ui.shared.resolveRelayIconUrls
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

data class ProfileRelaysUiState(
    val pubkey: String = "",
    val facts: ProfileRelayFacts = ProfileRelayFacts(),
    val relayIcons: Map<String, String> = emptyMap(),
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
)

@HiltViewModel
class ProfileRelaysViewModel @Inject constructor(
    private val memoryEventStore: MemoryEventStore,
    private val profilePipeline: ProfilePipeline,
    private val nip11Fetcher: Nip11Fetcher,
) : ViewModel() {
    private val pubkey = MutableStateFlow("")
    private val loading = MutableStateFlow(true)
    private val loadFailed = MutableStateFlow(false)
    private val identityFetchesInFlight = ConcurrentHashMap.newKeySet<String>()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val facts = pubkey.flatMapLatest { target ->
        if (target.isBlank()) flowOf(ProfileRelayFacts())
        else memoryEventStore.profileRelayFactsFlow(target)
    }

    private val relayIcons = combine(
        facts,
        memoryEventStore.relayHealthFlow(),
    ) { relayFacts, health ->
        val urls = relayFacts.relays.map { it.url } + relayFacts.searchRelays + relayFacts.blockedRelays
        val monitorIcons = health.mapValues { it.value.monitor?.iconUrl.orEmpty() }
        val deviceIcons = health.mapValues { it.value.identity?.iconUrl.orEmpty() }
        resolveRelayIconUrls(urls, monitorIcons, deviceIcons)
    }

    val uiState: StateFlow<ProfileRelaysUiState> = combine(
        pubkey,
        facts,
        relayIcons,
        loading,
        loadFailed,
    ) { target, relayFacts, icons, isLoading, failed ->
        ProfileRelaysUiState(
            pubkey = target,
            facts = relayFacts,
            relayIcons = icons,
            loading = isLoading,
            loadFailed = failed,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ProfileRelaysUiState(),
    )

    fun initialize(targetPubkey: String) {
        if (pubkey.value == targetPubkey) return
        pubkey.value = targetPubkey
        refresh(force = false)
    }

    fun retry() = refresh(force = true)

    /** Hydrate bounded list prefetches and any rows Compose reaches beyond that cap. */
    fun requestVisibleRelayIdentity(url: String) {
        val normalized = normalizeRelayUrl(url) ?: return
        val fetchedAt = memoryEventStore.getRelayIdentity(normalized)?.fetchedAt
        if (!shouldRefreshRelayIdentity(fetchedAt, System.currentTimeMillis())) return
        if (!identityFetchesInFlight.add(normalized)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                nip11Fetcher.fetch(normalized)
            } finally {
                identityFetchesInFlight.remove(normalized)
            }
        }
    }

    fun prefetchRelayIdentities(facts: ProfileRelayFacts) {
        relayIdentityPrefetchUrls(facts).forEach(::requestVisibleRelayIdentity)
    }

    private fun refresh(force: Boolean) {
        val target = pubkey.value.takeIf(String::isNotBlank) ?: return
        loading.value = true
        loadFailed.value = false
        viewModelScope.launch(Dispatchers.IO) {
            val fetched = profilePipeline.fetchProfileRelayFacts(target, force)
            loadFailed.value = !fetched
            loading.value = false
        }
    }
}
