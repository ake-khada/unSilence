package com.unsilence.app.ui.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.auth.SigningManager
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.memory.tagsToJson
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.toEventJson
import com.unsilence.app.data.repository.UserRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.ConcurrentHashMap
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ComposeViewModel @Inject constructor(
    private val keyManager: KeyManager,
    private val signingManager: SigningManager,
    private val relayPool: RelayPool,
    private val memoryEventStore: MemoryEventStore,
    private val userRepository: UserRepository,
) : ViewModel() {

    /** Pubkey for the avatar in the compose UI. */
    val pubkeyHex: String? = keyManager.getPublicKeyHex()

    /** Signed-in user's avatar URL, for the compose avatar. */
    val userAvatarUrl: StateFlow<String?> = pubkeyHex?.let { pk ->
        userRepository.userFlow(pk)
            .map { it?.picture }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    } ?: MutableStateFlow(null)

    /** True once the note has been signed, published, and inserted into MES. */
    var published by mutableStateOf(false)
        private set

    /** Non-null when signing or publishing failed — shown to the user. */
    var publishError by mutableStateOf<String?>(null)
        private set

    fun publishNote(content: String) {
        publishError = null
        viewModelScope.launch(Dispatchers.IO) {
            val template = TextNoteEvent.build(note = content)
            val signed   = signingManager.sign(template) ?: run {
                publishError = "Signing failed — check your key or Amber connection"
                return@launch
            }

            // Publish wire command to all connected relays
            relayPool.publish(toEventJson(signed))

            // Optimistic insert into MES → appears in feed immediately
            val nowMs = System.currentTimeMillis()
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
                    replyToId = null,
                    rootId = null,
                    hasContentWarning = false,
                    contentWarningReason = null,
                    firstSeenAt = nowMs / 1000L,
                    relaysSeen = ConcurrentHashMap.newKeySet<String>().apply { add("local") },
                )
            )

            published = true
        }
    }
}
