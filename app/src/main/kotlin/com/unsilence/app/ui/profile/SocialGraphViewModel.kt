package com.unsilence.app.ui.profile

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.auth.SigningManager
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.memory.WotLookup
import com.unsilence.app.data.memory.WotProviderDescriptor
import com.unsilence.app.data.relay.RelayPreferencesStore
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.WotCoverage
import com.unsilence.app.data.relay.WotProviderOptionState
import com.unsilence.app.data.relay.WotProviderPrefs
import com.unsilence.app.data.relay.WotProviderSource
import com.unsilence.app.data.relay.computeWotCoverage
import com.unsilence.app.data.relay.defaultWotProviderDescriptor
import com.unsilence.app.data.relay.deriveWotProviderOptions
import com.unsilence.app.data.relay.normalizeRelayUrl
import com.unsilence.app.data.relay.normalizeWotProviderPubkeyInput
import com.unsilence.app.data.relay.parseEncryptedWotProviderTagsJson
import com.unsilence.app.data.relay.wotTargetsHash
import com.unsilence.app.data.repository.UserRepository
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

private const val TAG = "SocialGraph"

data class SocialGraphUiState(
    val ownPubkey: String?,
    val ownNpub: String?,
    val prefs: WotProviderPrefs,
    val activeProvider: WotProviderDescriptor,
    val activeProviderProfile: UserEntity?,
    val ownProvider: WotProviderDescriptor?,
    val encryptedOwnProviderAvailable: Boolean,
    val selectorOptions: List<WotProviderOptionState>,
    val ownStanding: WotLookup,
    val coverage: WotCoverage,
    val lastWotFetchAt: Long,
    val refreshing: Boolean,
    val customExpanded: Boolean,
    val customPubkeyInput: String,
    val customRelayInput: String,
    val customError: String?,
    val statusMessage: String?,
) {
    companion object {
        val EMPTY = SocialGraphUiState(
            ownPubkey = null,
            ownNpub = null,
            prefs = WotProviderPrefs(
                pubkey = defaultWotProviderDescriptor().providerPubkey,
                relay = defaultWotProviderDescriptor().relayHint,
                source = WotProviderSource.DEFAULT,
            ),
            activeProvider = defaultWotProviderDescriptor(),
            activeProviderProfile = null,
            ownProvider = null,
            encryptedOwnProviderAvailable = false,
            selectorOptions = emptyList(),
            ownStanding = WotLookup.Pending,
            coverage = WotCoverage(0, 0),
            lastWotFetchAt = 0L,
            refreshing = false,
            customExpanded = false,
            customPubkeyInput = "",
            customRelayInput = "",
            customError = null,
            statusMessage = null,
        )
    }
}

private data class SocialGraphLocalState(
    val decryptedOwnProvider: WotProviderDescriptor? = null,
    val refreshing: Boolean = false,
    val customExpanded: Boolean = false,
    val customPubkeyInput: String = "",
    val customRelayInput: String = "",
    val customError: String? = null,
    val statusMessage: String? = null,
)

@HiltViewModel
class SocialGraphViewModel @Inject constructor(
    keyManager: KeyManager,
    private val memoryEventStore: MemoryEventStore,
    private val relayPreferencesStore: RelayPreferencesStore,
    private val relayPool: RelayPool,
    private val userRepository: UserRepository,
    private val signingManager: SigningManager,
) : ViewModel() {

    private val ownPubkey = keyManager.getPublicKeyHex()
    private val ownNpub = ownPubkey?.let { runCatching { it.hexToByteArray().toNpub() }.getOrNull() }
    private val localState = MutableStateFlow(SocialGraphLocalState())
    private val refreshGeneration = AtomicInteger(0)
    @Volatile private var refreshJob: Job? = null

    val uiState = combine(
        relayPreferencesStore.wotProviderPrefsFlow(),
        memoryEventStore.wotSignalFlow,
        memoryEventStore.profileSignalFlow,
        relayPreferencesStore.lastWotFetchAtFlow(),
        localState,
    ) { prefs, _, _, lastFetchAt, local ->
        buildUiState(prefs, lastFetchAt, local)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        SocialGraphUiState.EMPTY,
    )

    init {
        Log.i(TAG, "opened own=${ownPubkey?.take(8).orEmpty()}")
        viewModelScope.launch(Dispatchers.IO) {
            fetchOwnProviderRegistryIfNeeded()
        }
        viewModelScope.launch(Dispatchers.IO) {
            combine(
                relayPreferencesStore.wotProviderPrefsFlow(),
                memoryEventStore.wotSignalFlow,
                localState,
            ) { prefs, _, local ->
                listOfNotNull(
                    prefs.pubkey,
                    memoryEventStore.activeWotProvider().providerPubkey,
                    memoryEventStore.ownWotProviderFromRegistry()?.providerPubkey,
                    local.decryptedOwnProvider?.providerPubkey,
                ).distinct()
            }
                .distinctUntilChanged()
                .collect { providers ->
                    userRepository.fetchMissingProfiles(providers)
            }
        }
    }

    private suspend fun fetchOwnProviderRegistryIfNeeded() {
        val own = ownPubkey ?: return
        if (memoryEventStore.ownWotProviderFromRegistry() != null) return
        if (!memoryEventStore.ownWotProviderEncryptedContent().isNullOrBlank()) return
        relayPool.fetchOwn10040(own)
    }

    fun refresh() {
        startRefresh(memoryEventStore.activeWotProvider(), replaceExisting = false)
    }

    fun selectDefault() {
        viewModelScope.launch(Dispatchers.IO) {
            switchProvider(WotProviderSource.DEFAULT, defaultWotProviderDescriptor())
        }
    }

    fun selectOwnGrapevine() {
        viewModelScope.launch(Dispatchers.IO) {
            val own = ownPubkey ?: return@launch setStatus("No account is loaded")
            var provider = memoryEventStore.ownWotProviderFromRegistry()
                ?: localState.value.decryptedOwnProvider

            if (provider == null && memoryEventStore.ownWotProviderEncryptedContent().isNullOrBlank()) {
                Log.i(TAG, "fetchOwn10040 start")
                setBusy(true, "Checking your kind 10040 provider list")
                val ok = relayPool.fetchOwn10040(own)
                Log.i(TAG, "fetchOwn10040 returned ok=$ok")
                provider = waitForOwnProvider()
                Log.i(TAG, "fetchOwn10040 wait provider=${provider?.providerPubkey?.take(8) ?: "none"} encrypted=${!memoryEventStore.ownWotProviderEncryptedContent().isNullOrBlank()}")
            }

            if (provider != null) {
                switchProvider(WotProviderSource.OWN_10040, provider)
                return@launch
            }

            val decrypted = decryptOwnProviderContent(own)
            if (decrypted != null) {
                switchProvider(WotProviderSource.OWN_10040, decrypted)
            } else {
                setBusy(false, "No provider list (kind 10040) found")
            }
        }
    }

    fun expandCustomProvider() {
        val current = uiState.value
        localState.value = localState.value.copy(
            customExpanded = true,
            customPubkeyInput = current.customPubkeyInput.ifBlank {
                if (current.prefs.source == WotProviderSource.CUSTOM) current.prefs.pubkey else ""
            },
            customRelayInput = current.customRelayInput.ifBlank {
                if (current.prefs.source == WotProviderSource.CUSTOM) current.prefs.relay else ""
            },
            customError = null,
            statusMessage = null,
        )
    }

    fun setCustomPubkeyInput(value: String) {
        localState.value = localState.value.copy(customPubkeyInput = value, customError = null)
    }

    fun setCustomRelayInput(value: String) {
        localState.value = localState.value.copy(customRelayInput = value, customError = null)
    }

    fun applyCustomProvider() {
        viewModelScope.launch(Dispatchers.IO) {
            val local = localState.value
            val providerPubkey = normalizeWotProviderPubkeyInput(local.customPubkeyInput)
            val relay = normalizeRelayUrl(local.customRelayInput)
            if (providerPubkey == null || relay == null) {
                localState.value = local.copy(
                    customError = when {
                        providerPubkey == null -> "Enter a valid npub or 64-hex provider key"
                        else -> "Enter a valid wss relay URL"
                    },
                )
                return@launch
            }
            switchProvider(
                WotProviderSource.CUSTOM,
                WotProviderDescriptor(providerPubkey, relay, updatedAt = 0L),
            )
            localState.value = localState.value.copy(customExpanded = false, customError = null)
        }
    }

    private fun buildUiState(
        prefs: WotProviderPrefs,
        lastFetchAt: Long,
        local: SocialGraphLocalState,
    ): SocialGraphUiState {
        val activeProvider = memoryEventStore.activeWotProvider()
        val ownProvider = memoryEventStore.ownWotProviderFromRegistry() ?: local.decryptedOwnProvider
        val follows = ownPubkey?.let { memoryEventStore.getFollows(it) } ?: emptySet()
        val assertions = memoryEventStore.getWotAssertions()
        return SocialGraphUiState(
            ownPubkey = ownPubkey,
            ownNpub = ownNpub,
            prefs = prefs,
            activeProvider = activeProvider,
            activeProviderProfile = memoryEventStore.getUserEntity(activeProvider.providerPubkey),
            ownProvider = ownProvider,
            encryptedOwnProviderAvailable = !memoryEventStore.ownWotProviderEncryptedContent().isNullOrBlank(),
            selectorOptions = deriveWotProviderOptions(
                prefs = prefs,
                ownProvider = ownProvider,
                encryptedOwnProviderAvailable = !memoryEventStore.ownWotProviderEncryptedContent().isNullOrBlank(),
            ),
            ownStanding = ownPubkey?.let { memoryEventStore.wotFor(it) } ?: WotLookup.Pending,
            coverage = computeWotCoverage(follows, assertions),
            lastWotFetchAt = lastFetchAt,
            refreshing = local.refreshing,
            customExpanded = local.customExpanded,
            customPubkeyInput = local.customPubkeyInput,
            customRelayInput = local.customRelayInput,
            customError = local.customError,
            statusMessage = local.statusMessage,
        )
    }

    private suspend fun waitForOwnProvider(): WotProviderDescriptor? {
        repeat(20) {
            memoryEventStore.ownWotProviderFromRegistry()?.let { return it }
            if (!memoryEventStore.ownWotProviderEncryptedContent().isNullOrBlank()) return null
            delay(100)
        }
        return memoryEventStore.ownWotProviderFromRegistry()
    }

    private suspend fun decryptOwnProviderContent(own: String): WotProviderDescriptor? {
        val content = memoryEventStore.ownWotProviderEncryptedContent()?.takeIf { it.isNotBlank() } ?: return null
        Log.d(TAG, "decryptOwn10040 start")
        setBusy(true, "Decrypting your provider list")
        val plaintext = signingManager.decrypt(content, own)
        val provider = plaintext?.let {
            parseEncryptedWotProviderTagsJson(it, updatedAt = System.currentTimeMillis() / 1000L)
        }
        if (provider != null) {
            localState.value = localState.value.copy(decryptedOwnProvider = provider)
            return provider
        }
        setBusy(false, "Could not decrypt a 30382:rank provider row")
        return null
    }

    private suspend fun switchProvider(source: WotProviderSource, provider: WotProviderDescriptor) {
        Log.i(TAG, "switchProvider source=$source provider=${provider.providerPubkey.take(8)} relay=${provider.relayHint}")
        relayPreferencesStore.setWotProvider(provider.providerPubkey, provider.relayHint, source)
        memoryEventStore.setActiveWotProvider(provider.providerPubkey, provider.relayHint)
        userRepository.fetchMissingProfiles(listOf(provider.providerPubkey))
        startRefresh(provider, replaceExisting = true)
    }

    private fun startRefresh(provider: WotProviderDescriptor, replaceExisting: Boolean) {
        val existing = refreshJob
        if (existing?.isActive == true) {
            if (!replaceExisting) {
                Log.d(TAG, "refresh skipped inFlight provider=${provider.providerPubkey.take(8)}")
                viewModelScope.launch {
                    setStatus("Refresh already running")
                }
                return
            }
            existing.cancel()
        }
        val generation = refreshGeneration.incrementAndGet()
        refreshJob = viewModelScope.launch(Dispatchers.IO) {
            runRefresh(provider, generation)
        }
    }

    private suspend fun runRefresh(provider: WotProviderDescriptor, generation: Int) {
        setBusy(true, null, generation)
        try {
            val own = ownPubkey ?: return setStatus("No account is loaded", generation)
            if (!isCurrentProvider(provider)) {
                Log.d(TAG, "refresh skipped stale provider=${provider.providerPubkey.take(8)}")
                return
            }
            val targets = buildWotTargets(own)
            Log.d(TAG, "refresh start provider=${provider.providerPubkey.take(8)} targets=${targets.size} relay=${provider.relayHint}")
            val ok = relayPool.fetchWotAssertions(
                providerPubkey = provider.providerPubkey,
                relayHint = provider.relayHint,
                subjects = targets,
                prioritySubjects = listOf(own),
            )
            Log.d(TAG, "refresh finish ok=$ok provider=${provider.providerPubkey.take(8)} targets=${targets.size}")
            if (!isCurrentProvider(provider) || !isCurrentRefresh(generation)) {
                Log.d(TAG, "refresh result ignored stale provider=${provider.providerPubkey.take(8)}")
                return
            }
            if (ok) {
                relayPreferencesStore.setLastWotFetch(
                    System.currentTimeMillis(),
                    wotTargetsHash(provider.providerPubkey, targets),
                )
                setStatus("Synced from ${provider.relayHint}", generation)
            } else if (hasUsefulWotData(own)) {
                setStatus("Partial sync", generation)
            } else {
                setStatus("Sync did not complete", generation)
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "refresh cancelled provider=${provider.providerPubkey.take(8)}")
            throw e
        } finally {
            setBusy(false, localState.value.statusMessage, generation)
        }
    }

    private fun isCurrentRefresh(generation: Int): Boolean =
        refreshGeneration.get() == generation

    private fun isCurrentProvider(provider: WotProviderDescriptor): Boolean =
        memoryEventStore.activeWotProvider().providerPubkey == provider.providerPubkey

    private fun buildWotTargets(own: String): Set<String> =
        (memoryEventStore.getFollows(own) ?: emptySet()) + own

    private fun hasUsefulWotData(own: String): Boolean {
        val follows = memoryEventStore.getFollows(own) ?: emptySet()
        val coverage = computeWotCoverage(follows, memoryEventStore.getWotAssertions())
        return memoryEventStore.wotFor(own) is WotLookup.Scored || coverage.scored > 0
    }

    private suspend fun setBusy(value: Boolean, message: String?, generation: Int? = null) {
        withContext(Dispatchers.Main.immediate) {
            if (generation == null || isCurrentRefresh(generation)) {
                localState.value = localState.value.copy(refreshing = value, statusMessage = message)
            }
        }
    }

    private suspend fun setStatus(message: String, generation: Int? = null) {
        withContext(Dispatchers.Main.immediate) {
            if (generation == null || isCurrentRefresh(generation)) {
                localState.value = localState.value.copy(statusMessage = message)
            }
        }
    }
}
