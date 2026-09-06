package com.unsilence.app.ui.feed

import androidx.compose.runtime.Stable
import com.unsilence.app.data.memory.CustomEmoji
import com.unsilence.app.data.memory.EventEntity
import com.unsilence.app.data.memory.EventStats
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.ReactionInfo
import com.unsilence.app.data.memory.SensitiveContentMode
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.memory.WotLookup
import com.unsilence.app.data.memory.ZapDetail
import com.unsilence.app.data.model.EventModel
import com.unsilence.app.data.relay.FeedWotDisplayMode
import com.unsilence.app.data.relay.OgMetadata
import com.unsilence.app.data.wallet.ZapRequest
import com.unsilence.app.ui.shared.PollActionCallbacks
import com.unsilence.app.ui.shared.VideoPlaybackScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Screen-owned navigation and UI callbacks for every card rendered on that screen.
 *
 * These callbacks are deliberately required: a new embedded rendering surface cannot
 * silently lose taps by inheriting an empty default.
 */
@Stable
data class EventCardActions(
    val onNoteClick: (String) -> Unit,
    val onComment: (FeedRow, EventModel) -> Unit,
    val onAuthorClick: (String) -> Unit,
    val onHashtagClick: (String) -> Unit,
    val onQuote: (String) -> Unit,
    val onArticleClick: (FeedRow) -> Unit,
    val onReactLongPress: ((eventId: String, pubkey: String) -> Unit)?,
    val onLongPress: ((FeedRow) -> Unit)?,
)

/** Shared card operations and resolvers supplied by [NoteActionsViewModel]. */
@Stable
data class EventCardServices(
    val react: (eventId: String, pubkey: String, emoji: String, customEmojiUrl: String?) -> Unit,
    val repost: (eventId: String, pubkey: String, relayUrl: String) -> Unit,
    val zap: (eventId: String, pubkey: String, relayUrl: String, request: ZapRequest) -> Unit,
    val saveNwcUri: (String) -> Unit,
    val isNwcConfigured: () -> Boolean,
    val lookupProfile: suspend (pubkey: String, relayHints: List<String>) -> UserEntity?,
    val lookupEvent: suspend (EventReferenceTarget) -> EventEntity?,
    val lookupModel: (String) -> EventModel?,
    val fetchOgMetadata: suspend (String) -> OgMetadata?,
    val hasCachedOgMetadata: (String) -> Boolean,
    val articleRowFlow: (String) -> Flow<FeedRow?>,
    val ensureArticle: (coord: String, author: String, dTag: String, hints: List<String>) -> Unit,
    val hydrateEngagement: (List<FeedRow>) -> Unit,
    val imageDimensionCache: ImageDimensionCache?,
    val thumbnailCache: VideoThumbnailCache?,
)

/** Screen-specific display providers and policies used by card leaves. */
@Stable
data class EventCardSurface(
    val profileFlow: ((String) -> StateFlow<UserEntity?>)?,
    val statsFlow: ((String) -> StateFlow<EventStats>)?,
    val zapDetailsForEvent: ((String) -> List<ZapDetail>)?,
    val repostPubkeysForEvent: ((String) -> List<String>)?,
    val reactionsForEvent: ((String) -> List<ReactionInfo>)?,
    val pinnedEmojis: List<CustomEmoji>,
    val videoScope: VideoPlaybackScope?,
    val sensitiveMode: SensitiveContentMode,
    val wotLookup: ((String) -> WotLookup?)?,
    val feedWotDisplayMode: FeedWotDisplayMode,
    val onWotSubjectsVisible: (Collection<String>) -> Unit,
    val pollActions: PollActionCallbacks?,
)

/**
 * Complete ambient state for one card-hosting screen.
 *
 * Per-card state ([EventModel], [FeedRow], role and engagement) remains explicit at
 * [EventCard]. Relay hints are bound per top-level row and inherited by every embedded
 * resolver below it.
 */
@Stable
data class EventCardHost(
    val actions: EventCardActions,
    val services: EventCardServices,
    val surface: EventCardSurface,
    val relayHints: List<String> = emptyList(),
) {
    fun withRelayHints(hints: List<String>): EventCardHost {
        val merged = mergedRelayHints(hints)
        return if (merged == relayHints) this else copy(relayHints = merged)
    }

    suspend fun lookupProfile(pubkey: String, hints: List<String> = emptyList()): UserEntity? =
        services.lookupProfile(pubkey, mergedRelayHints(hints))

    suspend fun lookupEvent(target: EventReferenceTarget): EventEntity? =
        services.lookupEvent(
            target.copy(relayHints = mergedRelayHints(target.relayHints)),
        )

    private fun mergedRelayHints(hints: List<String>): List<String> =
        (relayHints + hints).filter(String::isNotBlank).distinct()
}

@Stable
data class EventCardParent(
    val event: EventEntity,
    val author: UserEntity?,
)

/** Per-card presentation state that does not belong to the shared screen host. */
@Stable
data class EventCardPresentation(
    val focused: Boolean = false,
    val newPostAnimated: (() -> Unit)? = null,
    val parent: EventCardParent? = null,
)

/** Build the shared, screen-independent half once instead of unpacking this VM at every card. */
internal fun NoteActionsViewModel.eventCardServices(): EventCardServices = EventCardServices(
    react = ::react,
    repost = ::repost,
    zap = ::zap,
    saveNwcUri = ::saveNwcUri,
    isNwcConfigured = { isNwcConfigured },
    lookupProfile = { pubkey, hints ->
        if (hints.isEmpty()) lookupProfile(pubkey) else lookupProfileWithHints(pubkey, hints)
    },
    lookupEvent = ::lookupEvent,
    lookupModel = ::getEventModel,
    fetchOgMetadata = ::fetchOgMetadata,
    hasCachedOgMetadata = ::hasCachedOgMetadata,
    articleRowFlow = ::articleRowFlow,
    ensureArticle = ::ensureArticle,
    hydrateEngagement = ::hydrateEngagement,
    imageDimensionCache = imageDimensionCache,
    thumbnailCache = videoThumbnailCache,
)

internal fun NoteActionsViewModel.eventCardHost(
    actions: EventCardActions,
    profileFlow: ((String) -> StateFlow<UserEntity?>)?,
    statsFlow: ((String) -> StateFlow<EventStats>)?,
    zapDetailsForEvent: ((String) -> List<ZapDetail>)?,
    repostPubkeysForEvent: ((String) -> List<String>)?,
    reactionsForEvent: ((String) -> List<ReactionInfo>)?,
    pinnedEmojis: List<CustomEmoji>,
    videoScope: VideoPlaybackScope?,
    sensitiveMode: SensitiveContentMode,
    wotLookup: ((String) -> WotLookup?)?,
    feedWotDisplayMode: FeedWotDisplayMode,
    onWotSubjectsVisible: (Collection<String>) -> Unit,
    pollActions: PollActionCallbacks?,
): EventCardHost = EventCardHost(
    actions = actions,
    services = eventCardServices(),
    surface = EventCardSurface(
        profileFlow = profileFlow,
        statsFlow = statsFlow,
        zapDetailsForEvent = zapDetailsForEvent,
        repostPubkeysForEvent = repostPubkeysForEvent,
        reactionsForEvent = reactionsForEvent,
        pinnedEmojis = pinnedEmojis,
        videoScope = videoScope,
        sensitiveMode = sensitiveMode,
        wotLookup = wotLookup,
        feedWotDisplayMode = feedWotDisplayMode,
        onWotSubjectsVisible = onWotSubjectsVisible,
        pollActions = pollActions,
    ),
)

/**
 * Deliberately non-interactive host for a compose draft preview.
 *
 * Keeping the no-op behavior in this named factory makes the exceptional surface explicit;
 * production card hosts must provide every behavior callback.
 */
internal fun previewEventCardHost(
    services: EventCardServices,
): EventCardHost = EventCardHost(
    actions = EventCardActions(
        onNoteClick = {},
        onComment = { _, _ -> },
        onAuthorClick = {},
        onHashtagClick = {},
        onQuote = {},
        onArticleClick = {},
        onReactLongPress = null,
        onLongPress = null,
    ),
    services = services.copy(
        react = { _, _, _, _ -> },
        repost = { _, _, _ -> },
        zap = { _, _, _, _ -> },
        saveNwcUri = {},
        isNwcConfigured = { false },
        hydrateEngagement = {},
    ),
    surface = EventCardSurface(
        profileFlow = null,
        statsFlow = null,
        zapDetailsForEvent = null,
        repostPubkeysForEvent = null,
        reactionsForEvent = null,
        pinnedEmojis = emptyList(),
        videoScope = null,
        sensitiveMode = SensitiveContentMode.SHOW,
        wotLookup = null,
        feedWotDisplayMode = FeedWotDisplayMode.NUMBERS,
        onWotSubjectsVisible = {},
        pollActions = null,
    ),
)
