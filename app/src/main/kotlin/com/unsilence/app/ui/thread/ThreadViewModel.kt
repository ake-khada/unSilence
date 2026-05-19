package com.unsilence.app.ui.thread

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.auth.SigningManager
import com.unsilence.app.data.memory.EventEntity
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.memory.tagsToJson
import com.unsilence.app.data.relay.GLOBAL_RELAY_URLS
import com.unsilence.app.data.relay.normalizeRelayUrl
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.toEventJson
import com.unsilence.app.data.repository.UserRepository
import com.unsilence.app.data.memory.EventStats
import java.util.concurrent.ConcurrentHashMap
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

data class DepthRow(val row: FeedRow, val depth: Int)

data class ThreadUiState(
    val focusedNote: FeedRow? = null,
    val replies: List<DepthRow> = emptyList(),
    val loading: Boolean = true,
    val focusedReplyId: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ThreadViewModel @Inject constructor(
    private val memoryEventStore: MemoryEventStore,
    private val relayPool: RelayPool,
    private val keyManager: KeyManager,
    private val signingManager: SigningManager,
    private val userRepository: UserRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ThreadUiState())
    val uiState: StateFlow<ThreadUiState> = _uiState.asStateFlow()

    private val eventIdFlow = MutableStateFlow<String?>(null)
    @Volatile private var tappedId: String? = null

    val pubkeyHex: String? = keyManager.getPublicKeyHex()

    val userAvatarUrl: StateFlow<String?> = pubkeyHex?.let { pk ->
        userRepository.userFlow(pk)
            .map { it?.picture }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    } ?: MutableStateFlow(null)

    var published by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            eventIdFlow
                .filterNotNull()
                .flatMapLatest { id -> memoryEventStore.threadFeedRowFlow(id) }
                .collect { rows ->
                    val focusedId = eventIdFlow.value ?: return@collect
                    val focused = rows.firstOrNull { it.id == focusedId }
                    val replyRows = rows.filter { it.id != focusedId && it.kind == 1 }

                    // Build parent→children map
                    val childrenOf = replyRows.groupBy { it.replyToId ?: it.rootId ?: focusedId }
                        .mapValues { (_, v) -> v.sortedBy { it.createdAt } }

                    // DFS flatten with depth (cap at 6), visited set prevents
                    // stack overflow from circular reply chains (malicious or bridged)
                    val flatList = mutableListOf<DepthRow>()
                    val visited = mutableSetOf<String>()
                    fun walk(parentId: String, depth: Int) {
                        childrenOf[parentId]?.forEach { row ->
                            if (visited.add(row.id)) {
                                flatList.add(DepthRow(row, depth.coerceAtMost(6)))
                                walk(row.id, depth + 1)
                            }
                        }
                    }
                    walk(focusedId, 1)

                    _uiState.value = ThreadUiState(
                        focusedNote    = focused,
                        replies        = flatList,
                        loading        = false,
                        focusedReplyId = tappedId.takeIf { it != focusedId },
                    )
                }
        }
    }

    // ── Per-event stats (reactive counts for thread cards) ─────────────
    private val statsCache = ConcurrentHashMap<String, StateFlow<EventStats>>()

    fun statsFlow(eventId: String): StateFlow<EventStats> =
        statsCache.getOrPut(eventId) {
            memoryEventStore.statsFlow(eventId)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), memoryEventStore.currentStatsSnapshot(eventId))
        }

    /** Wipe stale state so next open doesn't flash old content. */
    fun clearThread() {
        eventIdFlow.value = null
        tappedId = null
        _uiState.value = ThreadUiState()
    }

    fun loadThread(eventId: String) {
        published = false  // Always reset so LaunchedEffect won't auto-dismiss
        tappedId = eventId
        // Clear stale state immediately — prevents flash of old thread content
        _uiState.value = ThreadUiState(loading = true)
        viewModelScope.launch {
            val ownPubkey = pubkeyHex ?: ""
            val readRelays = memoryEventStore.getReadWriteRelayConfigs(ownPubkey)
                .filter { it.marker == null || it.marker == "read" }
                .mapNotNull { normalizeRelayUrl(it.url) }
            val urls = readRelays.ifEmpty { GLOBAL_RELAY_URLS }

            // Walk UP the reply chain to the true root (fetching ancestors as needed)
            val rootId = withTimeoutOrNull(8_000) {
                resolveThreadRoot(eventId, urls)
            } ?: (memoryEventStore.getEventEntity(eventId)?.rootId ?: eventId)

            if (eventIdFlow.value == rootId) return@launch  // Already showing this thread

            eventIdFlow.value = rootId
            relayPool.fetchThread(urls, rootId)
        }
    }

    /**
     * Walk UP the reply chain, fetching missing ancestors, to the true root.
     * Best-effort: returns the highest id reached if a relay never returns one.
     */
    private suspend fun resolveThreadRoot(startId: String, hints: List<String>): String {
        val visited = mutableSetOf<String>()
        var currentId = startId
        repeat(50) {                                       // hop cap — pathological guard
            if (!visited.add(currentId)) return currentId   // cycle guard
            val current = memoryEventStore.getEventEntity(currentId)
                ?: fetchAncestor(currentId, hints)
                ?: return currentId                        // unreachable — best-effort root
            val parentId = current.replyToId ?: current.rootId
            if (parentId == null || parentId == currentId) return currentId  // root
            currentId = parentId
        }
        return currentId
    }

    private suspend fun fetchAncestor(id: String, hints: List<String>): EventEntity? {
        withContext(Dispatchers.IO) { relayPool.fetchEventById(id, hints) }
        return withTimeoutOrNull(3_000) {
            memoryEventStore.eventEntityFlow(id).filterNotNull().first()
        }
    }

    fun publishReply(content: String, rootId: String, replyToId: String, replyToPubkey: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val nowMs        = System.currentTimeMillis()
            val nowSeconds   = nowMs / 1000L

            val template = TextNoteEvent.build(note = content, createdAt = nowSeconds) {
                add(arrayOf("e", rootId, "", "root"))
                add(arrayOf("e", replyToId, "", "reply"))
                add(arrayOf("p", replyToPubkey))
            }
            val signed = signingManager.sign(template) ?: return@launch

            relayPool.publish(toEventJson(signed))

            // Optimistic insert into MES → appears in thread immediately
            val parsedTags = signed.tags.map { it.toList() }
            memoryEventStore.insert(
                NostrEvent(
                    id = signed.id,
                    pubkey = signed.pubKey,
                    kind = signed.kind,
                    content = signed.content,
                    createdAt = signed.createdAt,
                    tags = parsedTags,
                    tagsJson = tagsToJson(parsedTags),
                    sig = signed.sig,
                    relayUrl = "local",
                    replyToId = replyToId,
                    rootId = rootId,
                    hasContentWarning = false,
                    contentWarningReason = null,
                    firstSeenAt = nowMs,
                    relaysSeen = ConcurrentHashMap.newKeySet<String>().apply { add("local") },
                )
            )

            published = true
        }
    }
}
