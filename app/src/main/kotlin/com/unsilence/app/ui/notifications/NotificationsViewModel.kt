package com.unsilence.app.ui.notifications

import com.unsilence.app.ui.shared.CardDataFlow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.memory.EventEntity
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.NotificationRow
import com.unsilence.app.data.memory.SensitiveContentMode
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.memory.WotLookup
import com.unsilence.app.data.relay.FeedWotDisplayMode
import com.unsilence.app.data.relay.RelayPreferencesStore
import com.unsilence.app.data.relay.WotHydrationCoalescer
import com.unsilence.app.data.relay.wotLookupSnapshot
import com.unsilence.app.data.repository.NotificationRepository
import com.unsilence.app.data.repository.UserRepository
import com.unsilence.app.data.settings.SettingsStore
import com.unsilence.app.ui.feed.EventReferenceTarget
import com.unsilence.app.ui.shared.TimelineCardData
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import java.io.IOException

data class NotificationsUiState(
    val items: List<NotificationRow> = emptyList(),
    val loading: Boolean = true,
)

enum class NotifFilter {
    Following, Global;

    fun next(): NotifFilter = when (this) {
        Following -> Global
        Global -> Following
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val keyManager: KeyManager,
    private val memoryEventStore: MemoryEventStore,
    private val relayPreferencesStore: RelayPreferencesStore,
    private val userRepository: UserRepository,
    private val wotHydrationCoalescer: WotHydrationCoalescer,
    private val timelineCardData: TimelineCardData,
    private val notifications: NotificationRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _filter = MutableStateFlow(NotifFilter.Global)
    val filter: StateFlow<NotifFilter> = _filter.asStateFlow()
    val showFilterHint = settingsStore.notificationFilterHintDismissed
        .map { dismissed -> !dismissed }
        .catch { error ->
            if (error !is IOException) throw error
            emit(false)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), false)
    private val _wotSubjects = MutableStateFlow<Set<String>>(emptySet())
    private val previewWotSubjects = MutableStateFlow<Set<String>>(emptySet())
    val wotLookups: StateFlow<Map<String, WotLookup>> =
        combine(_wotSubjects, previewWotSubjects, memoryEventStore.wotSignalFlow) { subjects, previews, _ ->
            wotLookupSnapshot(subjects + previews, memoryEventStore::wotFor)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val feedWotDisplayMode: StateFlow<FeedWotDisplayMode> =
        relayPreferencesStore.feedWotDisplayModeFlow()
            .stateIn(viewModelScope, SharingStarted.Eagerly, FeedWotDisplayMode.NUMBERS)

    val sensitiveMode = relayPreferencesStore.sensitiveContentMode

    private val previewTargets = MutableStateFlow<List<String>>(emptyList())
    internal val previews = notificationPreviews(
        targets = previewTargets,
        eventSignal = memoryEventStore.feedSignalFlow,
        muteLists = memoryEventStore.ownMuteListFlow(),
        eventProvider = memoryEventStore::getNostrEvent,
        modelProvider = memoryEventStore::getOrParseEventModel,
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun profileFlow(pubkey: String): CardDataFlow<UserEntity?> =
        timelineCardData.profileFlow(pubkey)

    fun requestPreviewWotHydration(pubkeys: Collection<String>) {
        // Embedded references can introduce authors too; retain only a small recent window.
        previewWotSubjects.update { (it + pubkeys).toList().takeLast(NOTIFICATION_PREVIEW_WINDOW * 4).toSet() }
        wotHydrationCoalescer.requestHydration(pubkeys)
    }

    internal fun setPreviewTargets(ids: List<String>) {
        previewTargets.value = ids.distinct().take(NOTIFICATION_PREVIEW_WINDOW)
    }

    /** Existing asset warmer accepts FeedRows; take bounded snapshots off Main, never stats flows. */
    internal suspend fun previewWarmRows(ids: List<String>) = withContext(Dispatchers.Default) {
        memoryEventStore.feedRowsByIds(ids.take(NOTIFICATION_PREVIEW_WINDOW).toSet())
    }

    /** Screen-lifetime work only. Existing resolver owns relay selection, caching and misses. */
    internal suspend fun loadPreviewTargets(
        ids: List<String>,
        lookup: suspend (EventReferenceTarget) -> EventEntity?,
        onFinished: (String) -> Unit,
    ) = resolveNotificationPreviews(
        targets = ids,
        eventProvider = memoryEventStore::getNostrEvent,
        muteList = { keyManager.getPublicKeyHex()?.let(memoryEventStore::getMuteList) },
        lookup = lookup,
        onFinished = onFinished,
    )


    // Collection and actor hydration stop as soon as the tab leaves composition.
    val uiState: StateFlow<NotificationsUiState> = _filter.flatMapLatest { filter ->
        val owner = keyManager.getPublicKeyHex()
        if (owner == null) flowOf(NotificationsUiState(loading = false)) else
            notifications.rows(owner, followedOnly = filter == NotifFilter.Following).map { items ->
            // Fetch missing profiles across ALL actors (singles + every
            // grouped actor), not just one actor per row.
            val missingPubkeys = items.flatMap { row ->
                when (row) {
                    is NotificationRow.Single ->
                        if (row.actorPicture == null) listOf(row.actorPubkey) else emptyList()
                    is NotificationRow.Grouped ->
                        row.actors.filter { it.picture == null && it.pubkey != null }.map { it.pubkey!! }
                }
            }.distinct()
            if (missingPubkeys.isNotEmpty()) {
                userRepository.fetchMissingProfiles(missingPubkeys)
            }
            val actorPubkeys = items.flatMap { row ->
                when (row) {
                    is NotificationRow.Single -> listOf(row.actorPubkey)
                    is NotificationRow.Grouped -> row.actors.mapNotNull { it.pubkey }
                }
            }.toSet()
            _wotSubjects.value = actorPubkeys
            wotHydrationCoalescer.requestHydration(actorPubkeys)
                NotificationsUiState(items = items, loading = false)
            }.onStart { emit(NotificationsUiState()) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), NotificationsUiState())

    fun markSeen() {
        val timestamp = uiState.value.items.firstOrNull()?.mostRecentAt ?: return
        val owner = keyManager.getPublicKeyHex() ?: return
        viewModelScope.launch { notifications.markSeen(owner, timestamp) }
    }

    fun toggleFilter() {
        _filter.update { it.next() }
        viewModelScope.launch {
            try {
                settingsStore.dismissNotificationFilterHint()
            } catch (_: IOException) {
                // Filtering still works if the local hint preference cannot be saved.
            }
        }
    }
}
