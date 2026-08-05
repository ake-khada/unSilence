package com.unsilence.app.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.auth.SigningManager
import com.unsilence.app.data.memory.CustomEmoji
import com.unsilence.app.data.memory.EmojiSetEntity
import com.unsilence.app.data.memory.EmojiSetRef
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.toEventJson
import com.unsilence.app.data.settings.SettingsStore
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NAddress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

private const val TAG = "EmojiSettingsVM"

// ── Row models ──────────────────────────────────────────────────────────────

data class SubscribedSetRow(
    val authorPubkey: String,
    val dTag: String,
    val title: String?,
    val emojis: List<CustomEmoji>,
    val authorProfile: UserEntity?,
    val unresolved: Boolean,
)

data class DiscoverSetRow(
    val authorPubkey: String,
    val dTag: String,
    val title: String,
    val emojis: List<CustomEmoji>,
    val authorProfile: UserEntity?,
)

sealed interface PasteState {
    data object Idle : PasteState
    data class Resolving(val naddr: String) : PasteState
    data class Error(val message: String) : PasteState
    data object Subscribed : PasteState
}

@HiltViewModel
class CustomEmojiSettingsViewModel @Inject constructor(
    private val keyManager: KeyManager,
    private val signingManager: SigningManager,
    private val memoryEventStore: MemoryEventStore,
    private val relayPool: RelayPool,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val pubkeyHex: String? = keyManager.getPublicKeyHex()

    init {
        // Broad fetch — no author filter — so the discover surface has data to rank.
        pubkeyHex?.let {
            viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    relayPool.fetchDiscoverEmojiSets()
                }
            }
        }
    }

    // ── Paste state ─────────────────────────────────────────────────────────

    private val _pasteState = MutableStateFlow<PasteState>(PasteState.Idle)
    val pasteState: StateFlow<PasteState> = _pasteState.asStateFlow()

    // ── Pinned emojis ───────────────────────────────────────────────────────

    val pinnedEmojis: StateFlow<List<CustomEmoji>> = pubkeyHex?.let { pk ->
        combine(
            settingsStore.pinnedEmojiShortcodes,
            memoryEventStore.resolvedEmojisFlow(pk),
        ) { pinned, resolved ->
            if (pinned.isEmpty()) return@combine emptyList()
            val byShortcode = resolved.associateBy { it.shortcode }
            pinned.mapNotNull { byShortcode[it] }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    } ?: MutableStateFlow(emptyList())

    // ── Picker support (for pinned strip → EmojiPickerSheet) ──────────────

    val resolvedEmojis: StateFlow<List<CustomEmoji>> = pubkeyHex?.let { pk ->
        memoryEventStore.resolvedEmojisFlow(pk)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    } ?: MutableStateFlow(emptyList())

    val emojiCategories: StateFlow<List<Pair<String, List<CustomEmoji>>>> =
        pubkeyHex?.let { pk ->
            memoryEventStore.resolvedEmojisBySetFlow(pk)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
        } ?: MutableStateFlow(emptyList())

    val pinnedShortcodes: StateFlow<Set<String>> = settingsStore.pinnedEmojiShortcodes

    fun toggleEmojiPin(shortcode: String) {
        viewModelScope.launch {
            val current = settingsStore.pinnedEmojiShortcodes.value
            val updated = if (shortcode in current) current - shortcode else current + shortcode
            settingsStore.setPinnedEmojiShortcodes(updated)
        }
    }

    // ── Subscribed sets ─────────────────────────────────────────────────────

    val subscribedSets: StateFlow<List<SubscribedSetRow>> = pubkeyHex?.let { pk ->
        memoryEventStore.resolvedEmojisFlow(pk) // piggyback on _emojiSetSignal
            .map { _ -> buildSubscribedRows(pk) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    } ?: MutableStateFlow(emptyList())

    private fun buildSubscribedRows(pubkey: String): List<SubscribedSetRow> {
        val list = memoryEventStore.getUserEmojiList(pubkey) ?: return emptyList()
        return list.setRefs.map { ref ->
            val set = memoryEventStore.getEmojiSet(ref.authorPubkey, ref.setName)
            SubscribedSetRow(
                authorPubkey = ref.authorPubkey,
                dTag = ref.setName,
                title = set?.title,
                emojis = set?.emojis ?: emptyList(),
                authorProfile = memoryEventStore.getUserEntity(ref.authorPubkey),
                unresolved = set == null,
            )
        }
    }

    // ── Discover sets ───────────────────────────────────────────────────────

    val discoverSets: StateFlow<List<DiscoverSetRow>> = pubkeyHex?.let { pk ->
        memoryEventStore.allEmojiSetsFlow()
            .map { _ -> buildDiscoverRows(pk) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    } ?: MutableStateFlow(emptyList())

    private fun buildDiscoverRows(pubkey: String): List<DiscoverSetRow> {
        val subscribedCoords = memoryEventStore.getUserEmojiList(pubkey)
            ?.setRefs
            ?.map { it.authorPubkey to it.setName }
            ?.toSet()
            ?: emptySet()
        val subscribedAuthors = subscribedCoords.map { it.first }.toSet()
        val follows = memoryEventStore.getFollows(pubkey) ?: emptySet()

        return memoryEventStore.allEmojiSets()
            .filter { (it.authorPubkey to it.setName) !in subscribedCoords }
            .filter { it.emojis.isNotEmpty() }
            .map { set ->
                val tier = when {
                    set.authorPubkey in subscribedAuthors -> 0
                    set.authorPubkey in follows -> 1
                    else -> 2
                }
                Triple(set, tier, set.emojis.size)
            }
            .sortedWith(
                compareBy<Triple<EmojiSetEntity, Int, Int>> { it.second }
                    .thenByDescending { it.third }
            )
            .take(20)
            .map { (set, _, _) ->
                DiscoverSetRow(
                    authorPubkey = set.authorPubkey,
                    dTag = set.setName,
                    title = set.title ?: set.setName,
                    emojis = set.emojis,
                    authorProfile = memoryEventStore.getUserEntity(set.authorPubkey),
                )
            }
    }

    // ── Actions ─────────────────────────────────────────────────────────────

    fun subscribeByNaddr(rawInput: String) {
        val pk = pubkeyHex ?: return
        val trimmed = rawInput.trim()
        viewModelScope.launch {
            _pasteState.value = PasteState.Resolving(trimmed)

            // Parse naddr
            val parsed = runCatching {
                val bech32 = if (trimmed.startsWith("nostr:")) trimmed.removePrefix("nostr:") else trimmed
                Nip19Parser.uriToRoute(bech32)?.entity as? NAddress
            }.getOrNull()

            if (parsed == null || parsed.kind != 30030) {
                _pasteState.value = PasteState.Error("Not an emoji set address")
                return@launch
            }

            val authorPubkey = parsed.author
            val dTag = parsed.dTag

            // Check if already subscribed
            val existing = memoryEventStore.getUserEmojiList(pk)
            val alreadySubscribed = existing?.setRefs?.any {
                it.authorPubkey == authorPubkey && it.setName == dTag
            } == true
            if (alreadySubscribed) {
                _pasteState.value = PasteState.Error("Already subscribed")
                return@launch
            }

            // Build and publish updated kind-10030
            val success = publishUpdatedEmojiList(pk, existing) { refs ->
                val hintRelay = parsed.relay.firstOrNull()?.url
                refs + EmojiSetRef(authorPubkey, dTag, hintRelay)
            }
            if (!success) {
                _pasteState.value = PasteState.Error("Signing failed")
                return@launch
            }

            // Fetch unresolved set
            if (memoryEventStore.getEmojiSet(authorPubkey, dTag) == null) {
                val hintRelay = parsed.relay.firstOrNull()?.url
                val ref = EmojiSetRef(authorPubkey, dTag, hintRelay)
                withContext(Dispatchers.IO) {
                    relayPool.fetchEmojiSets(listOf(ref))
                }
            }

            _pasteState.value = PasteState.Subscribed
            delay(1500)
            _pasteState.value = PasteState.Idle
        }
    }

    fun subscribeDiscoverSet(authorPubkey: String, dTag: String) {
        val pk = pubkeyHex ?: return
        viewModelScope.launch {
            val existing = memoryEventStore.getUserEmojiList(pk)
            publishUpdatedEmojiList(pk, existing) { refs ->
                refs + EmojiSetRef(authorPubkey, dTag, null)
            }
        }
    }

    fun unsubscribeSet(authorPubkey: String, dTag: String) {
        val pk = pubkeyHex ?: return
        viewModelScope.launch {
            val existing = memoryEventStore.getUserEmojiList(pk)
            publishUpdatedEmojiList(pk, existing) { refs ->
                refs.filter { !(it.authorPubkey == authorPubkey && it.setName == dTag) }
            }
        }
    }

    fun retryUnresolved(authorPubkey: String, dTag: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                relayPool.fetchEmojiSets(listOf(EmojiSetRef(authorPubkey, dTag, null)))
            }
        }
    }

    // ── Publish helper ──────────────────────────────────────────────────────

    /**
     * Build a new kind-10030 event with mutated setRefs, sign, optimistic insert, publish.
     * Returns true on success.
     */
    private suspend fun publishUpdatedEmojiList(
        ownPubkey: String,
        existing: com.unsilence.app.data.memory.UserEmojiListEntity?,
        mutateRefs: (List<EmojiSetRef>) -> List<EmojiSetRef>,
    ): Boolean {
        val currentRefs = existing?.setRefs ?: emptyList()
        val currentInline = existing?.inlineEmojis ?: emptyList()
        val newRefs = mutateRefs(currentRefs)

        // Build tags: "a" tags for set refs + "emoji" tags for inline emojis
        val tags = mutableListOf<Array<String>>()
        for (ref in newRefs) {
            val aValue = "30030:${ref.authorPubkey}:${ref.setName}"
            if (ref.hintRelay != null) {
                tags.add(arrayOf("a", aValue, ref.hintRelay))
            } else {
                tags.add(arrayOf("a", aValue))
            }
        }
        for (emoji in currentInline) {
            tags.add(arrayOf("emoji", emoji.shortcode, emoji.url))
        }

        val template = EventTemplate<Event>(
            createdAt = System.currentTimeMillis() / 1000L,
            kind = 10030,
            tags = tags.toTypedArray(),
            content = "",
        )
        val signed = signingManager.sign(template) ?: return false

        // Optimistic local insert — MES.insert() routes kind-10030 through
        // handleUserEmojiList, updating userEmojiListByPubkey + bumping signal
        val parsedTags = signed.tags.map { it.toList() }
        val nowMs = System.currentTimeMillis()
        memoryEventStore.insert(
            NostrEvent(
                id = signed.id,
                pubkey = signed.pubKey,
                kind = 10030,
                content = signed.content,
                createdAt = signed.createdAt,
                tags = parsedTags,
                sig = signed.sig,
                relayUrl = "local",
                replyToId = null,
                rootId = null,
                hasContentWarning = false,
                contentWarningReason = null,
                firstSeenAt = nowMs,
                relaysSeen = ConcurrentHashMap.newKeySet<String>().apply { add("local") },
            )
        )

        // Publish to write relays
        val writeRelays = memoryEventStore.writeRelaysFor(ownPubkey)
        if (writeRelays.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                relayPool.publishToRelays(toEventJson(signed), writeRelays)
            }
        }
        Log.d(TAG, "Published kind-10030 with ${newRefs.size} set refs")
        return true
    }
}
