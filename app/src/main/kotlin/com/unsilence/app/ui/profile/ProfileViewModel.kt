package com.unsilence.app.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.auth.SigningManager
import com.unsilence.app.data.db.dao.FeedRow
import com.unsilence.app.data.db.dao.FollowDao
import com.unsilence.app.data.db.dao.RelayListDao
import com.unsilence.app.data.db.dao.UserDao
import com.unsilence.app.data.db.entity.UserEntity
import com.unsilence.app.data.relay.CardHydrator
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.repository.EventRepository
import com.unsilence.app.data.repository.UserRepository
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val keyManager: KeyManager,
    private val signingManager: SigningManager,
    private val userRepository: UserRepository,
    private val eventRepository: EventRepository,
    private val relayPool: RelayPool,
    private val followDao: FollowDao,
    private val userDao: UserDao,
    private val relayListDao: RelayListDao,
    private val cardHydrator: CardHydrator,
) : ViewModel() {

    val pubkeyHex: String? = keyManager.getPublicKeyHex()

    val npub: String? = pubkeyHex?.let { hex ->
        runCatching { hex.hexToByteArray().toNpub() }.getOrNull()
    }

    /** Live user metadata from Room (null until kind 0 arrives from relay). */
    val userFlow: Flow<UserEntity?> =
        if (pubkeyHex != null) userRepository.userFlow(pubkeyHex) else emptyFlow()

    /** Live top-level posts by this user, newest-first. */
    val postsFlow: Flow<List<FeedRow>> =
        if (pubkeyHex != null) eventRepository.userPostsFlow(pubkeyHex) else emptyFlow()

    // ── Profile tabs ──────────────────────────────────────────────────
    val selectedTab = MutableStateFlow(ProfileTab.NOTES)

    @OptIn(ExperimentalCoroutinesApi::class)
    val tabPostsFlow: Flow<List<FeedRow>> =
        if (pubkeyHex != null) {
            selectedTab.flatMapLatest { tab ->
                when (tab) {
                    ProfileTab.NOTES    -> eventRepository.userNotesFlow(pubkeyHex)
                    ProfileTab.REPLIES  -> eventRepository.userRepliesFlow(pubkeyHex)
                    ProfileTab.LONGFORM -> eventRepository.userLongformFlow(pubkeyHex)
                }
            }
        } else emptyFlow()

    // ── Profile lookup for repost original authors ──────────────────────
    private val profileCache = ConcurrentHashMap<String, StateFlow<UserEntity?>>()

    fun profileFlow(pubkey: String): StateFlow<UserEntity?> =
        profileCache.getOrPut(pubkey) {
            userRepository.userFlow(pubkey)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
        }

    /** Live following count from local follows table. */
    val followingCount: StateFlow<Int> = followDao.countFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Approximate follower count from NIP-45 COUNT, cached in Room. */
    val followerCount = MutableStateFlow<Long?>(null)

    val isLoadingPosts = MutableStateFlow(true)

    private val engagementFetchedIds = mutableSetOf<String>()

    init {
        if (pubkeyHex != null) {
            viewModelScope.launch {
                userRepository.fetchMissingProfiles(listOf(pubkeyHex))
            }
            // Actively request this user's posts from connected relays
            relayPool.fetchUserPosts(pubkeyHex)
        }
        // Fetch follower count via NIP-45
        if (pubkeyHex != null) {
            viewModelScope.launch(Dispatchers.IO) {
                val cached = userDao.getFollowerCount(pubkeyHex)
                val cachedAt = userDao.getFollowerCountUpdatedAt(pubkeyHex)
                val oneDayAgo = System.currentTimeMillis() / 1000 - 86_400

                if (cached != null && cachedAt != null && cachedAt > oneDayAgo) {
                    followerCount.value = cached
                    return@launch
                }

                val count = relayPool.sendCount(
                    relayUrl = "wss://antiprimal.net",
                    filter = buildJsonObject {
                        put("kinds", buildJsonArray { add(JsonPrimitive(3)) })
                        put("#p", buildJsonArray { add(JsonPrimitive(pubkeyHex)) })
                    },
                )
                if (count != null) {
                    followerCount.value = count
                    userDao.updateFollowerCount(pubkeyHex, count, System.currentTimeMillis() / 1000)
                }
            }
        }
        // Unified card hydration + engagement fetch as posts arrive
        viewModelScope.launch {
            tabPostsFlow.collectLatest { rows ->
                isLoadingPosts.value = false

                // Hydrate independently so collectLatest re-emissions don't cancel it
                val batch = rows.take(20)
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    cardHydrator.hydrateVisibleCards(batch)
                }

                // Capped engagement fetch — one batch of 20 max, debounced
                val newEventIds = rows
                    .filter { it.kind != 6 }
                    .map { it.id }
                    .filter { it !in engagementFetchedIds }
                    .take(20)
                if (newEventIds.isNotEmpty()) {
                    engagementFetchedIds.addAll(newEventIds)
                    delay(500)
                    relayPool.fetchEngagementBatch(newEventIds)
                }
            }
        }
    }

    /**
     * Builds and publishes a kind 0 (metadata) event from the provided fields.
     * Blank fields are omitted from the JSON payload.
     * [onDone] is called on the main thread once publishing completes.
     */
    fun saveProfile(
        name: String,
        displayName: String,
        about: String,
        picture: String,
        banner: String,
        nip05: String,
        lud16: String,
        website: String,
        onDone: () -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val contentJson = buildJsonObject {
                if (name.isNotBlank())        put("name",         name.trim())
                if (displayName.isNotBlank()) put("display_name", displayName.trim())
                if (about.isNotBlank())       put("about",        about.trim())
                if (picture.isNotBlank())     put("picture",      picture.trim())
                if (banner.isNotBlank())      put("banner",       banner.trim())
                if (nip05.isNotBlank())       put("nip05",        nip05.trim())
                if (lud16.isNotBlank())       put("lud16",        lud16.trim())
                if (website.isNotBlank())     put("website",      website.trim())
            }.toString()

            val template = EventTemplate<Event>(
                createdAt = System.currentTimeMillis() / 1000L,
                kind      = 0,
                tags      = emptyArray(),
                content   = contentJson,
            )

            val signed = signingManager.sign(template) ?: return@launch
            relayPool.publish(toEventJson(signed))

            // Also publish to indexer relays for discoverability
            val writeUrls = pubkeyHex?.let { getWriteRelayUrls(it) }.orEmpty()
            val indexerUrls = listOf(
                "wss://purplepag.es",
                "wss://user.kindpag.es",
                "wss://indexer.coracle.social",
                "wss://antiprimal.net",
            )
            val targetUrls = (writeUrls + indexerUrls).distinct()
            relayPool.publishToRelays(toEventJson(signed), targetUrls)

            // EventProcessor will update Room when the relay echoes the event back.
            // Switch to main for the callback.
            launch(Dispatchers.Main) { onDone() }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun getWriteRelayUrls(pubkey: String): List<String> {
        val relayList = relayListDao.getByPubkey(pubkey) ?: return emptyList()
        return runCatching {
            kotlinx.serialization.json.Json.decodeFromString<List<String>>(relayList.writeRelays)
        }.getOrDefault(emptyList())
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
}
