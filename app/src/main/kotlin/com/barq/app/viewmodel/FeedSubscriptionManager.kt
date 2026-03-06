package com.barq.app.viewmodel

import android.util.Log
import com.barq.app.nostr.ClientMessage
import com.barq.app.nostr.Filter
import com.barq.app.nostr.NostrEvent
import com.barq.app.relay.ConsoleLogType
import com.barq.app.relay.RelayConfig
import com.barq.app.relay.RelayHealthTracker
import com.barq.app.relay.RelayPool
import com.barq.app.relay.RelayScoreBoard
import com.barq.app.relay.SubscriptionManager
import com.barq.app.repo.ContactRepository
import com.barq.app.repo.EventRepository
import com.barq.app.repo.ExtendedNetworkRepository
import com.barq.app.repo.KeyRepository
import com.barq.app.repo.ListRepository
import com.barq.app.repo.MetadataFetcher
import com.barq.app.repo.NotificationRepository
import com.barq.app.repo.ProfileRepository
import com.barq.app.nostr.Nip57
import com.barq.app.nostr.RelaySet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Manages feed subscription lifecycle, feed type switching, engagement subscriptions,
 * relay feed status monitoring, and load-more pagination.
 * Extracted from FeedViewModel to reduce its size.
 */
class FeedSubscriptionManager(
    private val relayPool: RelayPool,
    private val subManager: SubscriptionManager,
    private val eventRepo: EventRepository,
    private val contactRepo: ContactRepository,
    private val listRepo: ListRepository,
    private val notifRepo: NotificationRepository,
    private val extendedNetworkRepo: ExtendedNetworkRepository,
    private val keyRepo: KeyRepository,
    private val healthTracker: RelayHealthTracker,
    private val relayScoreBoard: RelayScoreBoard,
    private val profileRepo: ProfileRepository,
    private val metadataFetcher: MetadataFetcher,
    private val scope: CoroutineScope,
    private val processingContext: CoroutineContext,
    private val pubkeyHex: String?
) {
    init {
        // Relay feed subs bypass RelayPool's seen-event dedup so events already
        // received by the main feed subscription can still appear in relay feeds.
        relayPool.registerDedupBypass("relay-feed-")
        relayPool.registerDedupBypass("relay-loadmore")
    }

    private val _feedType = MutableStateFlow(FeedType.FOLLOWS)
    val feedType: StateFlow<FeedType> = _feedType

    private val _selectedRelay = MutableStateFlow<String?>(null)
    val selectedRelay: StateFlow<String?> = _selectedRelay

    private val _selectedRelaySet = MutableStateFlow<RelaySet?>(null)
    val selectedRelaySet: StateFlow<RelaySet?> = _selectedRelaySet

    private val _relayFeedStatus = MutableStateFlow<RelayFeedStatus>(RelayFeedStatus.Idle)
    val relayFeedStatus: StateFlow<RelayFeedStatus> = _relayFeedStatus

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    val _initialLoadDone = MutableStateFlow(false)
    val initialLoadDone: StateFlow<Boolean> = _initialLoadDone

    // Mutable for StartupCoordinator to write loading progress
    val _initLoadingState = MutableStateFlow<InitLoadingState>(InitLoadingState.SearchingProfile)
    val initLoadingState: StateFlow<InitLoadingState> = _initLoadingState

    private val _loadingScreenComplete = MutableStateFlow(false)
    val loadingScreenComplete: StateFlow<Boolean> = _loadingScreenComplete

    private var feedGeneration = 0
    var feedSubId = "feed"
        private set
    private var relayFeedGeneration = 0
    var relayFeedSubId = "relay-feed"
        private set
    val activeEngagementSubIds = java.util.concurrent.CopyOnWriteArrayList<String>()
    private var feedEoseJob: Job? = null
    private var relayFeedEoseJob: Job? = null
    private var relayStatusMonitorJob: Job? = null
    private var isLoadingMore = false

    fun markLoadingComplete() { _loadingScreenComplete.value = true }

    /** Resolve indexer relays: user's search relays (kind 10007) with default fallback. */
    private fun getIndexerRelays(): List<String> {
        val userSearchRelays = keyRepo.getSearchRelays()
        return userSearchRelays.ifEmpty { RelayConfig.DEFAULT_INDEXER_RELAYS }
    }

    /** User's NIP-65 read relays from keyRepo, used as the follows/list feed relay set. */
    private fun getFollowFeedRelays(): Set<String> =
        keyRepo.getRelays().filter { it.read }.map { it.url }.toSet()

    /** Blocked + bad relay URLs combined for outbox routing exclusion. */
    private fun getExcludedRelayUrls(): Set<String> =
        relayPool.getBlockedUrls() + healthTracker.getBadRelays()

    fun applyAuthorFilterForFeedType(type: FeedType) {
        eventRepo.setAuthorFilter(when (type) {
            FeedType.FOLLOWS -> {
                val follows = contactRepo.getFollowList().map { it.pubkey }.toSet()
                if (pubkeyHex != null) follows + pubkeyHex else follows
            }
            FeedType.LIST -> listRepo.selectedList.value?.members
            else -> null  // EXTENDED_FOLLOWS and RELAY show everything
        })
    }

    fun setFeedType(type: FeedType) {
        val prev = _feedType.value
        Log.d("RLC", "[FeedSub] setFeedType $prev → $type feedSize=${eventRepo.feed.value.size}")
        _feedType.value = type
        applyAuthorFilterForFeedType(type)

        // Tear down relay feed when leaving RELAY mode
        if (prev == FeedType.RELAY && type != FeedType.RELAY) {
            unsubscribeRelayFeed()
        }

        when (type) {
            FeedType.FOLLOWS, FeedType.EXTENDED_FOLLOWS -> {
                relayPool.setProxyRelays(getFollowFeedRelays())
                if (prev == FeedType.LIST) {
                    Log.d("RLC", "[FeedSub] switching from $prev to $type — clearing feed and resubscribing")
                    eventRepo.resetFeedDisplay()
                    resubscribeFeed()
                } else {
                    // Switching from RELAY or between FOLLOWS/EXTENDED — main feed still running
                    Log.d("RLC", "[FeedSub] setFeedType $prev → $type — filter-only switch, no resubscribe needed, feedSize=${eventRepo.feed.value.size}")
                }
            }
            FeedType.RELAY -> {
                relayPool.setProxyRelays(emptySet())
                // Skip if already in RELAY mode — setSelectedRelay() already triggered
                // subscribeRelayFeed(). Double-subscribing causes a race where the second
                // call finds the ephemeral relay still connecting and fails.
                if (prev == FeedType.RELAY) {
                    Log.d("RLC", "[FeedSub] setFeedType RELAY → RELAY — skipping, already subscribed")
                    return
                }
                eventRepo.clearRelayFeed()
                subscribeRelayFeed()
            }
            FeedType.LIST -> {
                relayPool.setProxyRelays(getFollowFeedRelays())
                eventRepo.resetFeedDisplay()
                resubscribeFeed()
            }
        }
    }

    fun setSelectedRelay(url: String) {
        _selectedRelaySet.value = null
        _selectedRelay.value = url
        if (_feedType.value == FeedType.RELAY) {
            eventRepo.clearRelayFeed()
            subscribeRelayFeed()
        }
    }

    fun setSelectedRelaySet(relaySet: RelaySet) {
        _selectedRelaySet.value = relaySet
        _selectedRelay.value = null
        if (_feedType.value == FeedType.RELAY) {
            eventRepo.clearRelayFeed()
            subscribeRelayFeed()
        }
    }

    fun retryRelayFeed() {
        val url = _selectedRelay.value ?: return
        healthTracker.clearBadRelay(url)
        relayPool.clearCooldown(url)
        eventRepo.clearRelayFeed()
        _relayFeedStatus.value = RelayFeedStatus.Connecting
        subscribeRelayFeed()
    }

    fun subscribeFeed() {
        resubscribeFeed()
        if (_feedType.value == FeedType.RELAY) {
            eventRepo.clearRelayFeed()
            subscribeRelayFeed()
        }
    }

    fun refreshFeed() {
        _isRefreshing.value = true
        scope.launch {
            delay(3000)
            _isRefreshing.value = false
        }
    }

    fun resubscribeFeed() {
        Log.d("RLC", "[FeedSub] resubscribeFeed() feedType=${_feedType.value} connectedCount=${relayPool.connectedCount.value}")
        val oldSubId = feedSubId
        feedGeneration++
        feedSubId = "feed-$feedGeneration"
        Log.d("RLC", "[FeedSub] feed generation $feedGeneration: $oldSubId → $feedSubId")
        relayPool.closeOnAllRelays(oldSubId)
        for (subId in activeEngagementSubIds) relayPool.closeOnAllRelays(subId)
        activeEngagementSubIds.clear()
        eventRepo.countNewNotes = false
        feedEoseJob?.cancel()

        // Always request the full 24h window. Relying on newestTimestamp from the current
        // feed caused a race condition: premature subscribeFeed() calls (from followWatcherJob,
        // connectivity changes, or lifecycle callbacks) would receive partial events, then the
        // proper startup subscribeFeed() would use those events' timestamps as `since`, missing
        // the full window. seenEventIds + feedIds dedup handles re-received events cheaply.
        val sinceTimestamp = System.currentTimeMillis() / 1000 - 60 * 60 * 24
        Log.d("RLC", "[FeedSub] resubscribeFeed: since=$sinceTimestamp (24h window)")
        val indexerRelays = getIndexerRelays()
        val excludedUrls = getExcludedRelayUrls()
        val targetedRelays: Set<String> = when (_feedType.value) {
            FeedType.FOLLOWS, FeedType.EXTENDED_FOLLOWS -> {
                val cache = extendedNetworkRepo.cachedNetwork.value
                val firstDegree = contactRepo.getFollowList().map { it.pubkey }
                val allAuthors = if (cache != null) {
                    (listOfNotNull(pubkeyHex) + firstDegree + cache.qualifiedPubkeys).distinct()
                } else {
                    listOfNotNull(pubkeyHex) + firstDegree
                }
                if (allAuthors.isEmpty()) {
                    Log.d("RLC", "[FeedSub] resubscribeFeed: no authors, returning")
                    return
                }
                Log.d("RLC", "[FeedSub] resubscribeFeed: ${allAuthors.size} authors, ${indexerRelays.size} indexers, ${excludedUrls.size} excluded")
                val notesFilter = Filter(kinds = listOf(1, 6), since = sinceTimestamp)
                val msg = ClientMessage.req(feedSubId, notesFilter.copy(authors = allAuthors))
                if (relayPool.isProxyModeActive()) {
                    relayPool.sendToProxyRelays(msg)
                } else {
                    relayPool.sendToReadRelays(msg)
                }
                setOf(feedSubId)
            }
            FeedType.RELAY -> {
                // RELAY feeds use subscribeRelayFeed() — should not reach here
                Log.w("RLC", "[FeedSub] resubscribeFeed() called for RELAY type, skipping")
                return
            }
            FeedType.LIST -> {
                relayStatusMonitorJob?.cancel()
                _relayFeedStatus.value = RelayFeedStatus.Idle
                val list = listRepo.selectedList.value ?: return
                val authors = list.members.toList()
                if (authors.isEmpty()) return

                // Lists are small (5-50 authors) so use a 7-day window instead of 24h.
                // Infrequent posters in curated lists would otherwise produce a nearly empty feed.
                val listSince = System.currentTimeMillis() / 1000 - 60 * 60 * 24 * 7

                // TODO: new relay model — request kind:0+10002 from indexer relays for list members
                val notesFilter = Filter(kinds = listOf(1, 6), since = listSince)
                // TODO: new relay model — send REQ only to active feed relay set
                emptySet<String>()
            }
        }

        // Use connected relay count (not total targeted) for the EOSE threshold.
        // Many pool relays are dead (DNS failures, SSL errors, etc.) and will never
        // send EOSE. Basing the threshold on total targeted relays (e.g. 38/59) makes
        // it unreachable, causing the 15s timeout to fire every time with a sparse feed.
        // Wait for 3 EOSEs or 30% of connected relays, whichever is higher — this is
        // achievable when a few key relays (damus.io, primal.net) are connected.
        val connected = relayPool.connectedCount.value
        Log.d("RLC", "[FeedSub] resubscribeFeed() sent to ${targetedRelays.size} relays (connected=$connected), awaiting EOSE...")
        feedEoseJob = scope.launch {
            val eoseTarget = maxOf(3, (connected * 0.3).toInt()).coerceAtLeast(1)
            Log.d("RLC", "[FeedSub] awaiting $eoseTarget/$connected EOSEs for feedSubId=$feedSubId")
            subManager.awaitEoseCount(feedSubId, eoseTarget)
            Log.d("RLC", "[FeedSub] EOSE received, feed loaded")
            _initialLoadDone.value = true
            _initLoadingState.value = InitLoadingState.Done
            onRelayFeedEose()

            eventRepo.enableNewNoteCounting()
            subscribeEngagementForFeed()
            subscribeNotifEngagement()

            withContext(processingContext) {
                metadataFetcher.sweepMissingProfiles()
            }
        }
    }

    fun loadMore() {
        if (isLoadingMore) return
        isLoadingMore = true

        when (_feedType.value) {
            FeedType.FOLLOWS, FeedType.EXTENDED_FOLLOWS -> {
                val oldest = eventRepo.getOldestTimestamp() ?: run { isLoadingMore = false; return }
                val cache = extendedNetworkRepo.cachedNetwork.value
                val firstDegree = contactRepo.getFollowList().map { it.pubkey }
                val allAuthors = if (cache != null) {
                    (listOfNotNull(pubkeyHex) + firstDegree + cache.qualifiedPubkeys).distinct()
                } else {
                    listOfNotNull(pubkeyHex) + firstDegree
                }
                if (allAuthors.isEmpty()) { isLoadingMore = false; return }
                val templateFilter = Filter(kinds = listOf(1, 6), until = oldest - 1, limit = 50)
                val msg = ClientMessage.req("loadmore", templateFilter.copy(authors = allAuthors))
                if (relayPool.isProxyModeActive()) {
                    relayPool.sendToProxyRelays(msg)
                } else {
                    relayPool.sendToReadRelays(msg)
                }
            }
            FeedType.RELAY -> {
                val oldest = eventRepo.getOldestRelayFeedTimestamp() ?: run { isLoadingMore = false; return }
                val relaySet = _selectedRelaySet.value
                if (relaySet != null) {
                    val filter = Filter(kinds = listOf(1, 6), until = oldest - 1, limit = 50)
                    val msg = ClientMessage.req("relay-loadmore", filter)
                    for (setUrl in relaySet.relays) {
                        relayPool.sendToRelayOrEphemeral(setUrl, msg, skipBadCheck = true)
                    }
                } else {
                    val url = _selectedRelay.value
                    if (url != null) {
                        val filter = Filter(kinds = listOf(1, 6), until = oldest - 1, limit = 50)
                        relayPool.sendToRelayOrEphemeral(url, ClientMessage.req("relay-loadmore", filter), skipBadCheck = true)
                    } else { isLoadingMore = false; return }
                }
            }
            FeedType.LIST -> {
                val oldest = eventRepo.getOldestTimestamp() ?: run { isLoadingMore = false; return }
                val list = listRepo.selectedList.value ?: run { isLoadingMore = false; return }
                val authors = list.members.toList()
                if (authors.isEmpty()) { isLoadingMore = false; return }

                // TODO: new relay model — request kind:10002 from indexer relays for list members
                val templateFilter = Filter(kinds = listOf(1, 6), until = oldest - 1)
                // TODO: new relay model — send load-more REQ to active feed relay set only
            }
        }

        val loadMoreSubId = if (_feedType.value == FeedType.RELAY) "relay-loadmore" else "loadmore"
        scope.launch {
            val feedSizeBefore = if (_feedType.value == FeedType.RELAY) {
                eventRepo.relayFeed.value.size
            } else {
                eventRepo.feed.value.size
            }
            subManager.awaitEoseWithTimeout(loadMoreSubId)
            subManager.closeSubscription(loadMoreSubId)

            val feedSizeAfter = if (_feedType.value == FeedType.RELAY) {
                eventRepo.relayFeed.value.size
            } else {
                eventRepo.feed.value.size
            }
            if (feedSizeAfter > feedSizeBefore) {
                subscribeEngagementForFeed()
            }

            isLoadingMore = false
        }
    }

    fun pauseEngagement() {
        for (subId in activeEngagementSubIds) relayPool.closeOnAllRelays(subId)
        activeEngagementSubIds.clear()
    }

    fun resumeEngagement() {
        if (activeEngagementSubIds.isEmpty()) {
            subscribeEngagementForFeed()
        }
    }

    // -- Isolated relay feed subscription --

    private fun subscribeRelayFeed() {
        val oldSubId = relayFeedSubId
        relayFeedGeneration++
        relayFeedSubId = "relay-feed-$relayFeedGeneration"
        relayPool.closeOnAllRelays(oldSubId)
        relayFeedEoseJob?.cancel()

        // Always request the latest 100 notes per relay — no since timestamp.
        // Using a since timestamp caused empty feeds on switch because RelayPool's
        // seen-event dedup interacts with the shared timestamp state.
        val relaySet = _selectedRelaySet.value
        if (relaySet != null) {
            relayStatusMonitorJob?.cancel()
            _relayFeedStatus.value = RelayFeedStatus.Subscribing
            val filter = Filter(kinds = listOf(1, 6), limit = 100)
            val msg = ClientMessage.req(relayFeedSubId, filter)
            val sentUrls = mutableSetOf<String>()
            for (setUrl in relaySet.relays) {
                val sent = relayPool.sendToRelayOrEphemeral(setUrl, msg, skipBadCheck = true)
                if (sent) sentUrls.add(setUrl)
            }
            if (sentUrls.isEmpty()) {
                _relayFeedStatus.value = RelayFeedStatus.ConnectionFailed("Failed to connect to any relay in set")
                return
            }
            relayFeedEoseJob = scope.launch {
                val eoseTarget = maxOf(1, (sentUrls.size * 0.3).toInt()).coerceIn(1, sentUrls.size)
                subManager.awaitEoseCount(relayFeedSubId, eoseTarget)
                onRelayFeedEose()
                subscribeEngagementForFeed()
                withContext(processingContext) {
                    metadataFetcher.sweepMissingProfiles()
                }
            }
        } else {
            val url = _selectedRelay.value ?: return
            startRelayStatusMonitor(url)
            val status = _relayFeedStatus.value
            if (status is RelayFeedStatus.Cooldown || status is RelayFeedStatus.BadRelay) {
                return
            }
            val filter = Filter(kinds = listOf(1, 6), limit = 100)
            val msg = ClientMessage.req(relayFeedSubId, filter)
            val sent = relayPool.sendToRelayOrEphemeral(url, msg, skipBadCheck = true)
            if (!sent) {
                _relayFeedStatus.value = RelayFeedStatus.ConnectionFailed("Failed to connect to relay")
                return
            }
            relayFeedEoseJob = scope.launch {
                subManager.awaitEoseCount(relayFeedSubId, 1)
                onRelayFeedEose()
                subscribeEngagementForFeed()
                withContext(processingContext) {
                    metadataFetcher.sweepMissingProfiles()
                }
            }
        }
    }

    private fun unsubscribeRelayFeed() {
        relayFeedEoseJob?.cancel()
        relayStatusMonitorJob?.cancel()
        relayPool.closeOnAllRelays(relayFeedSubId)
        eventRepo.clearRelayFeed()
        _relayFeedStatus.value = RelayFeedStatus.Idle
    }

    // -- Relay status monitoring --

    private fun startRelayStatusMonitor(url: String) {
        relayStatusMonitorJob?.cancel()

        val cooldownRemaining = relayPool.getRelayCooldownRemaining(url)
        if (cooldownRemaining > 0) {
            _relayFeedStatus.value = RelayFeedStatus.Cooldown(cooldownRemaining)
            relayStatusMonitorJob = scope.launch {
                var remaining = cooldownRemaining
                while (remaining > 0) {
                    _relayFeedStatus.value = RelayFeedStatus.Cooldown(remaining)
                    delay(1000)
                    remaining = relayPool.getRelayCooldownRemaining(url)
                }
                _relayFeedStatus.value = RelayFeedStatus.Idle
                eventRepo.clearRelayFeed()
                subscribeRelayFeed()
            }
            return
        }

        if (healthTracker.isBad(url)) {
            _relayFeedStatus.value = RelayFeedStatus.BadRelay("Marked unreliable by health tracker")
            return
        }

        _relayFeedStatus.value = if (relayPool.isRelayConnected(url)) {
            RelayFeedStatus.Subscribing
        } else {
            RelayFeedStatus.Connecting
        }

        relayStatusMonitorJob = scope.launch {
            launch {
                // Track the current console log size so we only react to NEW entries,
                // not stale CONN_FAILURE entries from previous connection attempts.
                var baselineSize = relayPool.consoleLog.value.size
                relayPool.consoleLog.collectLatest { entries ->
                    if (entries.size <= baselineSize) {
                        baselineSize = entries.size
                        return@collectLatest
                    }
                    // Only check entries added since the monitor started
                    val newEntries = entries.subList(baselineSize, entries.size)
                    val latest = newEntries.lastOrNull { it.relayUrl == url } ?: return@collectLatest
                    val currentStatus = _relayFeedStatus.value
                    if (currentStatus is RelayFeedStatus.Connecting ||
                        currentStatus is RelayFeedStatus.Subscribing) {
                        when (latest.type) {
                            ConsoleLogType.CONN_FAILURE -> {
                                _relayFeedStatus.value = RelayFeedStatus.ConnectionFailed(
                                    latest.message ?: "Connection failed"
                                )
                            }
                            ConsoleLogType.NOTICE -> {
                                val msg = latest.message?.lowercase() ?: ""
                                if ("rate" in msg || "throttle" in msg || "slow down" in msg || "too many" in msg) {
                                    _relayFeedStatus.value = RelayFeedStatus.RateLimited
                                }
                            }
                            else -> {}
                        }
                    }
                }
            }

            launch {
                relayPool.connectedCount.collectLatest {
                    val connected = relayPool.isRelayConnected(url)
                    val currentStatus = _relayFeedStatus.value
                    if (connected && currentStatus is RelayFeedStatus.Connecting) {
                        _relayFeedStatus.value = RelayFeedStatus.Subscribing
                    } else if (!connected && (currentStatus is RelayFeedStatus.Streaming ||
                                currentStatus is RelayFeedStatus.Subscribing)) {
                        _relayFeedStatus.value = RelayFeedStatus.Disconnected
                    }
                }
            }

            // Two-phase timeout: connection (10s) then data (15s)
            launch {
                // Phase 1 — Connection timeout
                delay(10_000)
                if (_relayFeedStatus.value is RelayFeedStatus.Connecting) {
                    val isPersistent = relayPool.getRelayUrls().contains(url)
                    Log.d("RLC", "[FeedSub] relay feed CONNECTION TIMEOUT for $url (persistent=$isPersistent) — closing sub")
                    _relayFeedStatus.value = RelayFeedStatus.ConnectionFailed("Connection timed out")
                    relayPool.closeOnAllRelays(relayFeedSubId)
                    if (!isPersistent) relayPool.disconnectRelay(url)
                    return@launch
                }
                // Phase 2 — Data timeout (15s after connection phase)
                delay(15_000)
                if (_relayFeedStatus.value is RelayFeedStatus.Subscribing) {
                    val isPersistent = relayPool.getRelayUrls().contains(url)
                    Log.d("RLC", "[FeedSub] relay feed DATA TIMEOUT for $url (persistent=$isPersistent) — closing sub")
                    _relayFeedStatus.value = RelayFeedStatus.TimedOut
                    relayPool.closeOnAllRelays(relayFeedSubId)
                    if (!isPersistent) relayPool.disconnectRelay(url)
                }
            }
        }
    }

    private fun onRelayFeedEose() {
        if (_feedType.value != FeedType.RELAY) return
        val status = _relayFeedStatus.value
        if (status is RelayFeedStatus.Connecting || status is RelayFeedStatus.Subscribing) {
            _relayFeedStatus.value = if (eventRepo.relayFeed.value.isEmpty()) {
                RelayFeedStatus.NoEvents
            } else {
                RelayFeedStatus.Streaming
            }
        }
    }

    /** Mark status as Streaming when events start arriving. Called by EventRouter. */
    fun onRelayFeedEventReceived() {
        if (_feedType.value != FeedType.RELAY) return
        val status = _relayFeedStatus.value
        if (status is RelayFeedStatus.Subscribing || status is RelayFeedStatus.Connecting) {
            _relayFeedStatus.value = RelayFeedStatus.Streaming
        }
    }

    // -- Engagement subscriptions --

    fun subscribeEngagementForFeed() {
        for (subId in activeEngagementSubIds) relayPool.closeOnAllRelays(subId)
        activeEngagementSubIds.clear()

        val feedEvents = if (_feedType.value == FeedType.RELAY) eventRepo.relayFeed.value else eventRepo.feed.value
        if (feedEvents.isEmpty()) return

        val eventIds = feedEvents.map { it.id }
        val engageSubId = "engage"
        activeEngagementSubIds.add(engageSubId)
        val engagementFilters = eventIds.chunked(150).map { chunk ->
            Filter(kinds = listOf(7, 6, 9735), eTags = chunk)
        }
        val engageMsg = if (engagementFilters.size == 1)
            ClientMessage.req(engageSubId, engagementFilters[0])
        else
            ClientMessage.req(engageSubId, engagementFilters)
        if (relayPool.isProxyModeActive()) {
            relayPool.sendToProxyRelays(engageMsg)
        } else {
            relayPool.sendToReadRelays(engageMsg)
        }

        // Await EOSE so engagement counts populate before the user sees the feed.
        scope.launch {
            subManager.awaitEoseCount(engageSubId, 1, timeoutMs = 4_000)
            Log.d("RLC", "[FeedSub] engagement EOSE received")
        }
    }

    fun subscribeNotifEngagement() {
        val eventIds = notifRepo.getAllPostCardEventIds()
        if (eventIds.isEmpty()) return

        // Own events are already cached from the self-notes subscription in
        // subscribeDmsAndNotifications(), so go straight to engagement.
        subscribeNotifEngagementInner(eventIds)
    }

    private fun subscribeNotifEngagementInner(eventIds: List<String>) {
        val eventsByAuthor = mutableMapOf<String, MutableList<String>>()
        for (id in eventIds) {
            val event = eventRepo.getEvent(id)
            val author = event?.pubkey ?: "fallback"
            eventsByAuthor.getOrPut(author) { mutableListOf() }.add(id)
        }
        // TODO: new relay model — send notif engagement REQ to user's own NIP-65 read relays
        val zapSubId = "engage-notif-zap"
        activeEngagementSubIds.add(zapSubId)
        val zapFilters = eventIds.chunked(150).map { chunk ->
            Filter(kinds = listOf(9735), eTags = chunk)
        }
        val zapMsg = if (zapFilters.size == 1) ClientMessage.req(zapSubId, zapFilters[0])
        else ClientMessage.req(zapSubId, zapFilters)
        relayPool.sendToReadRelays(zapMsg)
    }

    /** Reset state for account switch. */
    fun reset() {
        feedEoseJob?.cancel()
        unsubscribeRelayFeed()
        relayPool.closeOnAllRelays(feedSubId)
        for (subId in activeEngagementSubIds) relayPool.closeOnAllRelays(subId)
        activeEngagementSubIds.clear()
        _loadingScreenComplete.value = false
        _initialLoadDone.value = false
        _initLoadingState.value = InitLoadingState.SearchingProfile
        _selectedRelay.value = null
        _selectedRelaySet.value = null
        isLoadingMore = false
    }
}
