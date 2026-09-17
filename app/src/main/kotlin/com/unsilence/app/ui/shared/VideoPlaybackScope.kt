package com.unsilence.app.ui.shared

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.model.EventModel
import com.unsilence.app.data.model.Segment
import com.unsilence.app.data.model.VideoRenderModel
import com.unsilence.app.data.model.buildVideoRenderModels
import com.unsilence.app.ui.feed.SharedPlayerHolder
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * Consolidated video playback state for a scrolling list of events.
 *
 * Replaces the ~80 identical lines previously copy-pasted across
 * FeedScreen, ProfileScreen, and UserProfileScreen.
 */
@Stable
class VideoPlaybackScope internal constructor(
    val exoPlayer: ExoPlayer,
    private val holder: SharedPlayerHolder,
    private val ownerId: String,
    private val navigationGate: NavigationPlaybackGate,
) {
    companion object {
        /** A candidate must hold ≥60% visibility for this long before activation.
         *  After this delay, scroll must have settled before activation fires. */
        internal const val ACTIVATION_CONFIRMATION_MS = 400L
        /** After scroll stops, wait this long before activating to avoid
         *  triggering during momentary scroll pauses. */
        internal const val SCROLL_SETTLE_MS = 250L
        /** After visible item count changes, freeze detection for this long. */
        internal const val LAYOUT_SHIFT_COOLDOWN_MS = 500L
        /** Block A→B→A bounce-back transitions within this window. */
        internal const val OSCILLATION_BLOCK_MS = 3000L
    }

    var activeVideoNoteId by mutableStateOf<String?>(null)
        internal set
    var isMuted by mutableStateOf(true)
    var showFullscreenVideo by mutableStateOf(false)
        internal set
    var preFullscreenMuted by mutableStateOf(true)
        internal set

    /** Pre-computed video render models keyed by event ID. */
    var videoRenderModels by mutableStateOf<Map<String, List<VideoRenderModel>>>(emptyMap())
        internal set
    private var selectedVideoUrls by mutableStateOf<Map<String, String>>(emptyMap())
    private var playbackRegistrations by mutableStateOf<Map<Any, VideoPlaybackRegistration>>(emptyMap())
    internal var playbackPermissionRevision by mutableStateOf(0L)
        private set

    // Only composed, allowed ContentFlows register. Cache hydration must never
    // start a hidden video's audio, including a target under an ordinary wrapper.
    internal val playableVideoModels by derivedStateOf {
        permittedVideoModels(videoRenderModels, playbackRegistrations.values)
    }

    // Detector state — written only from the detection coroutine (main thread)
    internal var lastActiveTransitionAt: Long = 0L
    internal var previousActiveId: String? = null
    internal var lastVisibleItemCount: Int = -1
    internal var lastLayoutShiftAt: Long = 0L
    internal var mapPasses: Int = 0
    internal var confirmationAttempts: Int = 0
    internal var confirmationFires: Int = 0
    internal var starvationWatchdogLoggedTicks: Int = 0
    internal var lastScrollObservedAtElapsedMs: Long = 0L
    internal var modelsResolvedLogged: Boolean = false
    internal var modelsResolvedAtElapsedMs: Long = 0L
    internal var playbackRequestedAtElapsedMs: Long = 0L

    internal val canPlay: Boolean get() = navigationGate.canPlay

    // A preview uses the ordinary thumbnail, never another entry's shared surface.
    fun isActiveVideo(noteId: String): Boolean = canPlay && noteId == activeVideoNoteId

    /**
     * URL currently bound to the shared player. Multi-video rows use their
     * selected page; every other row falls back to its first render model.
     * For a quote-only row the map still keys the parent row id to the quoted
     * models, so nested grids attach only when their own URL matches.
     */
    val activeVideoUrl: String?
        get() = activeVideoNoteId?.let(::selectedVideoUrl)

    fun toggleMute() { isMuted = !isMuted }

    /** Returns a disposer; each rendered occurrence owns its own permission. */
    fun registerVideoModels(noteId: String, models: List<VideoRenderModel>): () -> Unit {
        val token = Any()
        playbackRegistrations = playbackRegistrations + (
            token to VideoPlaybackRegistration(noteId, models)
        )
        return {
            playbackRegistrations = playbackRegistrations - token
            // Re-hide/disposal revokes consent immediately, not after the scroll
            // detector's cooldown. Preserve codec retention and other scopes' ownership.
            if (activeVideoNoteId == noteId && holder.currentUrl != activeVideoUrl) {
                holder.releaseOwnership(ownerId)
                // Disposal and a new grant can occur in one Compose apply pass.
                // Restart the effect even if the final allowed URL is unchanged.
                playbackPermissionRevision += 1
                if (showFullscreenVideo) dismissFullscreen()
            }
        }
    }

    fun selectedVideoUrl(noteId: String): String? =
        resolveSelectedVideoUrl(playableVideoModels[noteId].orEmpty(), selectedVideoUrls[noteId])

    fun selectVideo(noteId: String, videoUrl: String) {
        val registeredUrl = playableVideoModels[noteId]
            ?.firstOrNull { it.videoUrl == videoUrl }
            ?.videoUrl
            ?: return
        if (selectedVideoUrls[noteId] == registeredUrl) return
        selectedVideoUrls = selectedVideoUrls + (noteId to registeredUrl)
    }

    internal fun pruneVideoSelections(modelsByNote: Map<String, List<VideoRenderModel>>) {
        val retainedSelections = retainRegisteredVideoSelections(selectedVideoUrls, modelsByNote)
        if (retainedSelections !== selectedVideoUrls) selectedVideoUrls = retainedSelections
    }

    fun openFullscreen(noteId: String, requestedVideoUrl: String) {
        if (!canPlay) return
        // Only claim fullscreen when the row resolves to a bound video URL.
        // A cold empty-repost target may render its preview before the model map
        // recomputes to include row.id → targetModels; without this guard,
        // tapping it would open an empty (black) fullscreen with no media. The
        // tap is a no-op until the target warms (map recompute on next `events`).
        val targetUrl = resolvePlaybackVideoUrl(
            models = playableVideoModels[noteId].orEmpty(),
            requestedUrl = requestedVideoUrl,
        ) ?: return
        selectVideo(noteId, targetUrl)
        val decision = decideFullscreenPlayback(targetUrl, holder.currentUrl, exoPlayer.mediaItemCount)
        when (decision) {
            FullscreenPlaybackDecision.Ignore -> return
            FullscreenPlaybackDecision.Resume -> {
                holder.claim(ownerId)
                exoPlayer.repeatMode = Player.REPEAT_MODE_ALL
                exoPlayer.playWhenReady = true
            }
            FullscreenPlaybackDecision.Rebind -> {
                // Holder truth says the shared player is either empty or bound
                // to a different scope's URL. Rebind before opening fullscreen:
                // activeVideoNoteId may be unchanged, so LaunchedEffect(activeVideoUrl)
                // will not necessarily run to repair this.
                holder.claim(ownerId)
                exoPlayer.repeatMode = Player.REPEAT_MODE_ALL
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
                exoPlayer.setMediaItem(MediaItem.fromUri(targetUrl))
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
            }
        }
        exoPlayer.volume = 1f
        activeVideoNoteId = noteId
        preFullscreenMuted = isMuted
        isMuted = false
        showFullscreenVideo = true
    }

    fun dismissFullscreen() {
        showFullscreenVideo = false
        isMuted = preFullscreenMuted
    }
}

internal fun resolveSelectedVideoUrl(
    models: List<VideoRenderModel>,
    selectedUrl: String?,
): String? = models.firstOrNull { it.videoUrl == selectedUrl }?.videoUrl
    ?: models.firstOrNull()?.videoUrl

internal fun resolvePlaybackVideoUrl(
    models: List<VideoRenderModel>,
    requestedUrl: String,
): String? = models.firstOrNull { it.videoUrl == requestedUrl }?.videoUrl

internal fun retainRegisteredVideoSelections(
    selections: Map<String, String>,
    modelsByNote: Map<String, List<VideoRenderModel>>,
): Map<String, String> {
    fun isRegistered(noteId: String, url: String): Boolean =
        modelsByNote[noteId]?.any { it.videoUrl == url } == true

    if (selections.all { (noteId, url) -> isRegistered(noteId, url) }) return selections
    return selections.filter { (noteId, url) -> isRegistered(noteId, url) }
}

internal enum class FullscreenPlaybackDecision {
    Ignore,
    Resume,
    Rebind,
}

internal fun decideFullscreenPlayback(
    targetUrl: String?,
    holderUrl: String?,
    mediaItemCount: Int,
): FullscreenPlaybackDecision = when {
    targetUrl == null -> FullscreenPlaybackDecision.Ignore
    holderUrl == targetUrl && mediaItemCount > 0 -> FullscreenPlaybackDecision.Resume
    else -> FullscreenPlaybackDecision.Rebind
}

/**
 * Resolve the [VideoRenderModel]s that a feed row's lazy item should map to.
 *
 * Own videos always win. If a row has no own video, fall back to the FIRST
 * candidate event (quoted note, or empty-repost target) that has video models
 * — so a quote-only / empty-repost video row becomes autoplay-eligible at the
 * parent row's lazy-item granularity (the detector keys off top-level lazy
 * item keys, so the entry must be keyed by parent row.id). A row with BOTH its
 * own video and a candidate video keeps its own video, so the shared player
 * binds to the parent's URL — the per-video URL gate in EventVideoGrid then
 * prevents the nested grid from attaching.
 *
 * Pure function (no Compose/Android deps) — unit-tested.
 */
internal fun resolveRowVideoModels(
    ownModels: List<VideoRenderModel>,
    candidateEventIds: List<String>,
    videoModelsFor: (String) -> List<VideoRenderModel>,
): List<VideoRenderModel> {
    if (ownModels.isNotEmpty()) return ownModels
    return candidateEventIds
        .firstNotNullOfOrNull { id -> videoModelsFor(id).takeIf { it.isNotEmpty() } }
        ?: emptyList()
}

/**
 * Event ids whose video models a row may adopt when it has none of its own,
 * in priority order:
 *   1. Kind-6/16 repost target (NIP-10 rootId) — discoverable straight from
 *      the FeedRow. Verified embedded media wins through [ownModels]; a
 *      reference-only JSON envelope can still adopt the fetched target.
 *   2. Quote-event ids from the (cached) parent model, in source order.
 *
 * [cachedModel] is cache-only (may be null); the rootId path covers the common
 * empty-repost case without it.
 */
internal fun videoSourceCandidateIds(row: FeedRow, cachedModel: EventModel?): List<String> {
    val ids = ArrayList<String>(2)
    if (row.kind == 6 || row.kind == 16) {
        row.rootId?.let { ids.add(it) }
    }
    cachedModel?.segments
        ?.filterIsInstance<Segment.QuoteEvent>()
        ?.forEach { ids.add(it.eventId) }
    return ids
}

internal fun threadParentVideoSourceCandidateIds(row: FeedRow, cachedModel: EventModel?): List<String> {
    if (cachedModel != null && cachedModel.repost != null) return emptyList()
    return listOfNotNull(row.replyToId ?: row.rootId)
}

/**
 * Creates and wires a [VideoPlaybackScope] with lifecycle, mute sync,
 * playback transitions, and active-video detection — all the plumbing
 * that was previously duplicated per screen.
 *
 * [cachedModelProvider] MUST be cache-only (no parse) — it is called per row
 * while building the model map on the composition thread. Passing a parsing
 * provider would reintroduce UI-path ContentParser calls.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Composable
fun rememberVideoPlaybackScope(
    ownerId: String,
    holder: SharedPlayerHolder,
    events: List<FeedRow>,
    listState: LazyListState,
    videoModelProvider: ((String) -> List<VideoRenderModel>)? = null,
    cachedModelProvider: ((String) -> EventModel?)? = null,
    additionalVideoSourceCandidateIds: ((FeedRow, EventModel?) -> List<String>)? = null,
): VideoPlaybackScope {
    val exoPlayer = holder.player
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val navigationGate = remember(ownerId, holder, lifecycle) {
        NavigationPlaybackGate(lifecycle) { resumed ->
            // releaseOwnership is owner-checked: a late outgoing callback must
            // not pause the incoming entry's player. Retain the codec as before.
            if (!resumed) holder.releaseOwnership(ownerId)
            Log.w("VideoScope", "navigation playback: owner=$ownerId resumed=$resumed")
        }
    }
    val scope = remember(ownerId, holder, navigationGate) {
        VideoPlaybackScope(exoPlayer, holder, ownerId, navigationGate)
    }

    DisposableEffect(navigationGate) {
        navigationGate.attach()
        onDispose { navigationGate.dispose() }
    }

    // Release ownership on disposal. A qualifying one-shot summary closes the
    // watchdog's blind spot for a screen that disappears before its first 5s tick.
    DisposableEffect(scope) {
        onDispose {
            val currentModels = scope.playableVideoModels
            if (scope.canPlay && scope.activeVideoNoteId == null && currentModels.isNotEmpty()) {
                val layoutInfo = listState.layoutInfo
                val viewportStart = layoutInfo.viewportStartOffset
                val viewportEnd = layoutInfo.viewportEndOffset
                val bestVisible = layoutInfo.visibleItemsInfo.mapNotNull { item ->
                    val id = item.key as? String ?: return@mapNotNull null
                    if (id !in currentModels.keys) return@mapNotNull null
                    val visibleTop = maxOf(item.offset, viewportStart)
                    val visibleBottom = minOf(item.offset + item.size, viewportEnd)
                    val fraction = if (item.size > 0) {
                        maxOf(0, visibleBottom - visibleTop).toFloat() / item.size
                    } else {
                        0f
                    }
                    id to fraction
                }.maxByOrNull { it.second }
                if (bestVisible != null && bestVisible.second >= 0.35f) {
                    val modelsAgeMs = scope.modelsResolvedAtElapsedMs
                        .takeIf { it > 0L }
                        ?.let { SystemClock.elapsedRealtime() - it }
                        ?: -1L
                    Log.w(
                        "VideoScope",
                        "disposed before activation: owner=$ownerId " +
                            "best=${bestVisible.first.take(8)} visibility=${bestVisible.second} " +
                            "models=${currentModels.size} modelsAgeMs=$modelsAgeMs " +
                            "mapPasses=${scope.mapPasses} " +
                            "confirmationAttempts=${scope.confirmationAttempts} " +
                            "confirmationFires=${scope.confirmationFires} " +
                            "scrolling=${listState.isScrollInProgress}",
                    )
                }
            }
            holder.releaseOwnership(ownerId)
        }
    }

    // Covered/previews cannot change the current owner's audio either.
    LaunchedEffect(scope, scope.canPlay, scope.isMuted) {
        if (scope.canPlay && holder.isOwner(ownerId)) {
            exoPlayer.volume = if (scope.isMuted) 0f else 1f
        }
    }

    // Read pre-computed VideoRenderModels from MES sidecar cache (populated at insert time).
    // Falls back to buildVideoRenderModels(row) for events not in cache (e.g. optimistic inserts).
    // Quote-only and empty-repost rows map to the QUOTED/TARGET event's models
    // (keyed by parent row.id). Discovery is cache-only: videoSourceCandidateIds
    // reads the empty-repost target from FeedRow.rootId and quote ids from an
    // already-parsed model via cachedModelProvider (no parse); videoModelProvider
    // is the MES sidecar lookup. This cache establishes source priority, not
    // playback permission: only a composed ContentFlow can make media eligible.
    val visibleEventIds = remember(events) { events.mapTo(HashSet()) { it.id } }
    val renderModelsMap = remember(events, additionalVideoSourceCandidateIds) {
        events
            .filter { it.kind != 30023 }
            .mapNotNull { row ->
                val own = videoModelProvider?.invoke(row.id)?.takeIf { it.isNotEmpty() }
                    ?: buildVideoRenderModels(row)
                val cachedModel = cachedModelProvider?.invoke(row.id)
                val candidateIds = if (own.isEmpty())
                    videoSourceCandidateIds(row, cachedModel) +
                        additionalVideoSourceCandidateIds?.invoke(row, cachedModel).orEmpty()
                else emptyList()
                val models = resolveRowVideoModels(own, candidateIds) { id ->
                    videoModelProvider?.invoke(id) ?: emptyList()
                }
                if (models.isNotEmpty()) row.id to models else null
            }
            .toMap()
    }
    LaunchedEffect(renderModelsMap, visibleEventIds) {
        val retainedLateModels = scope.videoRenderModels.filterKeys { id ->
            id in visibleEventIds && id !in renderModelsMap
        }
        val combined = retainedLateModels + renderModelsMap
        if (scope.videoRenderModels != combined) {
            scope.videoRenderModels = combined
        }
        scope.pruneVideoSelections(combined)
        if (!scope.modelsResolvedLogged && combined.isNotEmpty()) {
            scope.modelsResolvedLogged = true
            scope.modelsResolvedAtElapsedMs = SystemClock.elapsedRealtime()
            Log.w("VideoScope", "models resolved: ${combined.size} rows for $ownerId")
        }
    }

    // Playback transitions: swap media source on active note change.
    // B2 contract:
    //   Deactivation: playWhenReady=false (retain codec+media). No stop(), no clearMediaItems().
    //   Reactivation same URL: playWhenReady=true. No prepare(), no codec realloc.
    //   Reactivation different URL: stop()+clearMediaItems(), then setMediaItem()+prepare().
    val activeVideoUrl = scope.activeVideoUrl

    // Player milestones are intentionally warning-level and owner-gated so they
    // survive release shrinking without each remembered screen logging callbacks
    // from the singleton player claimed by a different surface.
    DisposableEffect(exoPlayer, ownerId) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (!holder.isOwner(ownerId)) return
                val state = when (playbackState) {
                    Player.STATE_BUFFERING -> "buffering"
                    Player.STATE_READY -> "ready"
                    Player.STATE_ENDED -> "ended"
                    else -> return
                }
                Log.w(
                    "VideoScope",
                    "player $state: id=${scope.activeVideoNoteId?.take(8) ?: "none"} " +
                        "owner=$ownerId elapsedMs=${scope.playbackRequestElapsedMs()}",
                )
            }

            override fun onRenderedFirstFrame() {
                if (!holder.isOwner(ownerId)) return
                Log.w(
                    "VideoScope",
                    "player first-frame: id=${scope.activeVideoNoteId?.take(8) ?: "none"} " +
                        "owner=$ownerId elapsedMs=${scope.playbackRequestElapsedMs()}",
                )
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!holder.isOwner(ownerId)) return
                Log.w(
                    "VideoScope",
                    "player error: id=${scope.activeVideoNoteId?.take(8) ?: "none"} " +
                        "owner=$ownerId code=${error.errorCode} name=${error.errorCodeName} " +
                        "cause=${error.cause?.javaClass?.simpleName ?: "none"} " +
                        "elapsedMs=${scope.playbackRequestElapsedMs()}",
                )
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    LaunchedEffect(scope, scope.canPlay, activeVideoUrl, scope.playbackPermissionRevision) {
        if (!scope.canPlay) return@LaunchedEffect
        // Read current live consent here, not a URL captured before a re-hide.
        val permittedUrl = scope.activeVideoUrl
        if (permittedUrl != null) {
            val retainedUrl = holder.currentUrl
            val wasRetained = holder.isRetained
            val activeId = scope.activeVideoNoteId
            scope.playbackRequestedAtElapsedMs = SystemClock.elapsedRealtime()
            val requestMode = when {
                retainedUrl != permittedUrl -> "rebind"
                wasRetained -> "resume-retained"
                else -> "resume-owned"
            }
            Log.w(
                "VideoScope",
                "player request: id=${activeId?.take(8) ?: "none"} " +
                    "owner=$ownerId mode=$requestMode",
            )
            holder.claim(ownerId)
            exoPlayer.repeatMode = Player.REPEAT_MODE_ALL
            exoPlayer.volume = if (scope.isMuted) 0f else 1f
            if (retainedUrl != permittedUrl || exoPlayer.mediaItemCount == 0) {
                // Different URL — full media swap (the one path where codec realloc is correct)
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
                exoPlayer.setMediaItem(MediaItem.fromUri(permittedUrl))
                exoPlayer.prepare()
            }
            // Same URL resumes without prepare or codec reallocation.
            exoPlayer.playWhenReady = true
        } else {
            if (holder.isOwner(ownerId)) {
                // B2: retain codec — just release ownership (sets playWhenReady=false,
                // retains the codec for the foreground session)
                holder.releaseOwnership(ownerId)
            }
        }
    }

    // Active video detection via scroll position with visibility thresholds.
    // Activate at >=60% visible; deactivate below 35% (hysteresis prevents oscillation).
    //
    // Three layers of flap protection:
    //   1. Layout shift cooldown: for 500ms after visible item count changes,
    //      the detector returns the current active (no change). Catches
    //      hydration-induced reflow without blocking normal scroll.
    //   2. Confirmation: a candidate must hold for 400ms continuous before
    //      promotion. flatMapLatest cancels stale candidates automatically.
    //   3. Oscillation detection: in collect, block A→B→A bounce-back
    //      transitions within OSCILLATION_BLOCK_MS. Targets the specific
    //      pathology (13 alternating transitions in 12s) without suppressing
    //      normal sequential transitions A→B→C.
    val showFullscreenRef = rememberUpdatedState(scope.showFullscreenVideo)
    val activeRef = rememberUpdatedState(scope.activeVideoNoteId)
    ResumedEffect(scope) {
        // Include the video row ids in the emitted value. snapshotFlow observes
        // every state read, but it only emits when the block's returned value
        // changes. Returning layoutInfo alone means a late-resolved nested video
        // can be observed but suppressed until the user scrolls and layoutInfo
        // changes. That is exactly the "video appears after sliding" failure.
        snapshotFlow { scope.playableVideoModels.keys to listState.layoutInfo }
            .map { (currentIds, layoutInfo) ->
                scope.mapPasses += 1

                val isScrolling = listState.isScrollInProgress
                if (isScrolling) {
                    scope.lastScrollObservedAtElapsedMs = SystemClock.elapsedRealtime()
                }

                // Fullscreen freeze
                if (showFullscreenRef.value) return@map activeRef.value

                // Layout shift cooldown — only while stationary. During scroll,
                // visible item count changes are expected (items of different
                // heights enter/leave viewport). While stationary, count changes
                // indicate hydration-induced reflow — the actual flap trigger.
                // Skip cooldown when no video is active yet — the cooldown
                // prevents flapping of an existing active video, not initial
                // activation (e.g. ThreadScreen loads items asynchronously).
                val now = System.currentTimeMillis()
                val visibleCount = layoutInfo.visibleItemsInfo.size
                if (!isScrolling && activeRef.value != null) {
                    if (visibleCount != scope.lastVisibleItemCount && scope.lastVisibleItemCount >= 0) {
                        scope.lastLayoutShiftAt = now
                    }
                    if (now - scope.lastLayoutShiftAt < VideoPlaybackScope.LAYOUT_SHIFT_COOLDOWN_MS) {
                        scope.lastVisibleItemCount = visibleCount
                        return@map activeRef.value
                    }
                }
                scope.lastVisibleItemCount = visibleCount

                val viewportStart = layoutInfo.viewportStartOffset
                val viewportEnd = layoutInfo.viewportEndOffset
                val viewportCenter = (viewportStart + viewportEnd) / 2

                val videoItems = layoutInfo.visibleItemsInfo
                    .filter { (it.key as? String) in currentIds }
                val currentActive = activeRef.value

                fun visibilityFraction(item: androidx.compose.foundation.lazy.LazyListItemInfo): Float {
                    val visibleTop = maxOf(item.offset, viewportStart)
                    val visibleBottom = minOf(item.offset + item.size, viewportEnd)
                    return if (item.size > 0) maxOf(0, visibleBottom - visibleTop).toFloat() / item.size else 0f
                }

                // Check if current active video is still above deactivation threshold (35%)
                val currentActiveItem = videoItems.firstOrNull { (it.key as? String) == currentActive }
                val currentStillVisible = currentActiveItem != null && visibilityFraction(currentActiveItem) >= 0.35f

                // Find best activation candidate (>=60% visible, closest to center)
                val candidate = videoItems
                    .filter { visibilityFraction(it) >= 0.6f }
                    .minByOrNull { abs(it.offset + it.size / 2 - viewportCenter) }
                    ?.key as? String

                when {
                    candidate != null -> candidate
                    currentStillVisible -> currentActive
                    else -> null
                }
            }
            .distinctUntilChanged()
            .flatMapLatest { candidate ->
                flow {
                    val current = activeRef.value

                    // Null deactivation or no change: immediate pass-through
                    if (candidate == null || candidate == current) {
                        emit(candidate)
                        return@flow
                    }

                    // Confirmation window — candidate must hold for the
                    // confirmation period. If a different candidate arrives,
                    // flatMapLatest cancels this coroutine automatically.
                    scope.confirmationAttempts += 1
                    delay(VideoPlaybackScope.ACTIVATION_CONFIRMATION_MS)

                    // Initial activation should not wait for scroll settle. On
                    // first paint of a tab/profile/thread, LazyColumn can still
                    // report transient scroll/layout motion; waiting for settle
                    // keeps autoplay dark until the user nudges the list.
                    if (activeRef.value == null) {
                        scope.confirmationFires += 1
                        emit(candidate)
                        return@flow
                    }

                    // Wait for scroll to fully settle — don't activate during
                    // momentary scroll pauses. The user must be stationary for
                    // SCROLL_SETTLE_MS after the last scroll gesture.
                    do {
                        while (listState.isScrollInProgress) {
                            delay(50)
                        }
                        delay(VideoPlaybackScope.SCROLL_SETTLE_MS)
                    } while (listState.isScrollInProgress)

                    scope.confirmationFires += 1
                    emit(candidate)
                }
            }
            .distinctUntilChanged()
            .collect { newActiveId ->
                if (!scope.canPlay) return@collect
                // Consent may have been revoked during the confirmation delay.
                if (newActiveId != null && newActiveId !in scope.playableVideoModels) return@collect
                if (scope.activeVideoNoteId != newActiveId) {
                    val now = System.currentTimeMillis()

                    // Oscillation detection: block A→B→A bounce-back within
                    // OSCILLATION_BLOCK_MS. This targets layout-shift-induced
                    // flap (13 alternations in 12s) without blocking normal
                    // sequential transitions or legitimate scroll-back.
                    if (newActiveId != null && newActiveId == scope.previousActiveId) {
                        val elapsed = now - scope.lastActiveTransitionAt
                        if (elapsed < VideoPlaybackScope.OSCILLATION_BLOCK_MS) {
                            return@collect
                        }
                    }

                    val oldId = scope.activeVideoNoteId
                    val modelsAgeMs = scope.modelsResolvedAtElapsedMs
                        .takeIf { it > 0L }
                        ?.let { SystemClock.elapsedRealtime() - it }
                        ?: -1L
                    Log.w(
                        "VideoScope",
                        "Active video: ${oldId?.take(8) ?: "none"} → " +
                            "${newActiveId?.take(8) ?: "none"} owner=$ownerId " +
                            "modelsAgeMs=$modelsAgeMs mapPasses=${scope.mapPasses} " +
                            "confirmationAttempts=${scope.confirmationAttempts} " +
                            "confirmationFires=${scope.confirmationFires}",
                    )
                    scope.previousActiveId = oldId
                    scope.activeVideoNoteId = newActiveId
                    scope.lastActiveTransitionAt = now
                    if (oldId == null && newActiveId != null) {
                        scope.mapPasses = 0
                        scope.confirmationAttempts = 0
                        scope.confirmationFires = 0
                        scope.starvationWatchdogLoggedTicks = 0
                    }
                    if (newActiveId == null && holder.isOwner(ownerId)) {
                        exoPlayer.playWhenReady = false
                    }
                }
            }
    }

    ResumedEffect(scope) {
        while (true) {
            delay(5_000)

            val currentModels = scope.playableVideoModels
            val isScrolling = listState.isScrollInProgress
            val nowElapsedMs = SystemClock.elapsedRealtime()
            val millisSinceLastScroll = scope.lastScrollObservedAtElapsedMs
                .takeIf { it > 0L }
                ?.let { nowElapsedMs - it }
            if (!shouldEvaluateVideoStarvation(
                    activeVideoNoteId = scope.activeVideoNoteId,
                    hasVideoModels = currentModels.isNotEmpty(),
                    isScrollInProgress = isScrolling,
                    millisSinceLastScroll = millisSinceLastScroll,
                )
            ) {
                scope.starvationWatchdogLoggedTicks = 0
                continue
            }

            val layoutInfo = listState.layoutInfo
            val viewportStart = layoutInfo.viewportStartOffset
            val viewportEnd = layoutInfo.viewportEndOffset
            val visibleVideoItems = layoutInfo.visibleItemsInfo.mapNotNull { item ->
                val id = item.key as? String ?: return@mapNotNull null
                if (id !in currentModels.keys) return@mapNotNull null

                val visibleTop = maxOf(item.offset, viewportStart)
                val visibleBottom = minOf(item.offset + item.size, viewportEnd)
                val fraction =
                    if (item.size > 0) {
                        maxOf(0, visibleBottom - visibleTop).toFloat() / item.size
                    } else {
                        0f
                    }
                id to fraction
            }
            val bestVisibleVideo = visibleVideoItems.maxByOrNull { it.second }
            if (bestVisibleVideo == null || bestVisibleVideo.second < 0.35f) {
                scope.starvationWatchdogLoggedTicks = 0
                continue
            }
            if (scope.starvationWatchdogLoggedTicks >= 3) continue

            scope.starvationWatchdogLoggedTicks += 1
            Log.w(
                "VideoScope",
                "starvation: owner=$ownerId models=${currentModels.size} " +
                    "visibleVideos=${visibleVideoItems.size} " +
                    "best=${bestVisibleVideo.first.take(8)} " +
                    "visibility=${bestVisibleVideo.second} mapPasses=${scope.mapPasses} " +
                    "confirmationAttempts=${scope.confirmationAttempts} " +
                    "confirmationFires=${scope.confirmationFires} " +
                    "scrolling=$isScrolling " +
                    "stationaryForMs=${millisSinceLastScroll ?: "scope-lifetime"}",
            )
        }
    }

    return scope
}

private fun VideoPlaybackScope.playbackRequestElapsedMs(): Long =
    playbackRequestedAtElapsedMs
        .takeIf { it > 0L }
        ?.let { SystemClock.elapsedRealtime() - it }
        ?: -1L

/**
 * A moving list is not starved: candidate churn and confirmation cancellation are
 * expected while rows cross the viewport. After scrolling ends, leave one complete
 * confirmation window before diagnosing a stationary visible video as stuck.
 */
internal fun shouldEvaluateVideoStarvation(
    activeVideoNoteId: String?,
    hasVideoModels: Boolean,
    isScrollInProgress: Boolean,
    millisSinceLastScroll: Long?,
): Boolean =
    activeVideoNoteId == null &&
        hasVideoModels &&
        !isScrollInProgress &&
        (millisSinceLastScroll == null ||
            millisSinceLastScroll >= VideoPlaybackScope.ACTIVATION_CONFIRMATION_MS)
