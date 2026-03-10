package com.unsilence.app.ui.thread

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.db.dao.FeedRow
import com.unsilence.app.data.db.entity.EventEntity
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.repository.EventRepository
import com.unsilence.app.data.repository.RelaySetRepository
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

data class ThreadUiState(
    val focusedNote: FeedRow? = null,
    val replies: List<FeedRow> = emptyList(),
    val loading: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ThreadViewModel @Inject constructor(
    private val eventRepository: EventRepository,
    private val relaySetRepository: RelaySetRepository,
    private val relayPool: RelayPool,
    private val keyManager: KeyManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ThreadUiState())
    val uiState: StateFlow<ThreadUiState> = _uiState.asStateFlow()

    private val eventIdFlow = MutableStateFlow<String?>(null)

    val pubkeyHex: String? = keyManager.getPublicKeyHex()

    var published by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            eventIdFlow
                .filterNotNull()
                .flatMapLatest { id -> eventRepository.threadFlow(id) }
                .collect { rows ->
                    val focusedId = eventIdFlow.value ?: return@collect
                    val focused = rows.firstOrNull { it.id == focusedId }
                    val replies = rows.filter { it.id != focusedId && it.kind == 1 }
                    _uiState.value = ThreadUiState(
                        focusedNote = focused,
                        replies     = replies,
                        loading     = false,
                    )
                }
        }
    }

    fun loadThread(eventId: String) {
        if (eventIdFlow.value == eventId) return
        eventIdFlow.value = eventId
        _uiState.value = ThreadUiState(loading = true)
        viewModelScope.launch {
            val set  = relaySetRepository.defaultSet() ?: return@launch
            val urls = relaySetRepository.decodeUrls(set)
            relayPool.fetchThread(urls, eventId)
        }
    }

    fun publishReply(content: String, rootId: String, replyToId: String, replyToPubkey: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val privKeyHex   = keyManager.getPrivateKeyHex() ?: return@launch
            val privKeyBytes = privKeyHex.hexToByteArray()
            val keyPair      = KeyPair(privKey = privKeyBytes)
            val signer       = NostrSignerInternal(keyPair)
            val nowSeconds   = System.currentTimeMillis() / 1000L

            val template = TextNoteEvent.build(note = content, createdAt = nowSeconds) {
                add(arrayOf("e", rootId, "", "root"))
                add(arrayOf("e", replyToId, "", "reply"))
                add(arrayOf("p", replyToPubkey))
            }
            val signed = runCatching { signer.sign(template) }.getOrNull() ?: return@launch

            relayPool.publish(toEventJson(signed))

            eventRepository.insertEvent(
                EventEntity(
                    id        = signed.id,
                    pubkey    = signed.pubKey,
                    kind      = signed.kind,
                    content   = signed.content,
                    createdAt = signed.createdAt,
                    tags      = tagsToJson(signed.tags),
                    sig       = signed.sig,
                    relayUrl  = "wss://relay.damus.io",
                    replyToId = replyToId,
                    rootId    = rootId,
                    cachedAt  = nowSeconds,
                )
            )

            published = true
        }
    }

    private fun toEventJson(event: Event): String = buildJsonObject {
        put("id",         event.id)
        put("pubkey",     event.pubKey)
        put("created_at", event.createdAt)
        put("kind",       event.kind)
        put("tags",       buildJsonArray {
            event.tags.forEach { row ->
                add(buildJsonArray { row.forEach { cell -> add(JsonPrimitive(cell)) } })
            }
        })
        put("content",    event.content)
        put("sig",        event.sig)
    }.toString()

    private fun tagsToJson(tags: Array<Array<String>>): String = buildJsonArray {
        tags.forEach { row ->
            add(buildJsonArray { row.forEach { cell -> add(JsonPrimitive(cell)) } })
        }
    }.toString()
}
