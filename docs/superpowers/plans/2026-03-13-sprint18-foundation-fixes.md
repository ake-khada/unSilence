# Sprint 18: Foundation Fixes — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add WebSocket health management, comprehensive session teardown, one missing DB index, and periodic FIFO pruning — resolving bugs A1, A2, A9, A10.

**Architecture:** Enhance existing `RelayConnection` with state tracking, `RelayPool` with backoff reconnection and persistent sub replay, `AppBootstrapper` with comprehensive teardown. Add `DatabaseMaintenanceJob` for periodic pruning. All changes layer on top of existing patterns.

**Tech Stack:** Kotlin, Hilt, Room 2.7.1, OkHttp WebSocket, Kotlin Coroutines/Flow, Android ProcessLifecycleOwner

**Spec:** `docs/superpowers/specs/2026-03-13-sprint18-foundation-fixes-design.md`

**Base path:** `app/src/main/kotlin/com/unsilence/app/`

---

## Chunk 1: RelayConnection State Tracking

### Task 1: Add RelayState enum and StateFlow to RelayConnection

**Files:**
- Modify: `data/relay/RelayConnection.kt`

- [ ] **Step 1: Add RelayState enum and state StateFlow**

Add imports and the enum before the class. Add `_state` MutableStateFlow and expose `state` as read-only. Replace `isConnected` boolean with state-derived property.

```kotlin
// Add to imports:
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Add enum before class:
enum class RelayState { CONNECTING, CONNECTED, DISCONNECTED, FAILED }
```

Inside `RelayConnection`, add:
```kotlin
    private val _state = MutableStateFlow(RelayState.DISCONNECTED)
    val state: StateFlow<RelayState> get() = _state.asStateFlow()
```

Replace `var isConnected: Boolean = false` with:
```kotlin
    /** True while the WebSocket handshake has completed and onClosed/onFailure has not fired. */
    val isConnected: Boolean get() = _state.value == RelayState.CONNECTED
```

- [ ] **Step 2: Update state transitions in connect()**

In `connect()`, set state to CONNECTING before creating the WebSocket:

```kotlin
    fun connect() {
        if (connected.getAndSet(true)) return
        _state.value = RelayState.CONNECTING
        val request = Request.Builder().url(url).build()
        ws = client.newWebSocket(request, Listener())
        Log.d(TAG, "Connecting to $url")
    }
```

- [ ] **Step 3: Update state transitions in Listener callbacks**

In `onOpen`:
```kotlin
        override fun onOpen(webSocket: WebSocket, response: Response) {
            _state.value = RelayState.CONNECTED
            Log.d(TAG, "Connected: $url")
        }
```

In `onFailure`:
```kotlin
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "Failure on $url: ${t.message}")
            _state.value = RelayState.FAILED
            connected.set(false)
            _messages.close()
        }
```

In `onClosed`:
```kotlin
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "Closed $url: $code $reason")
            _state.value = RelayState.DISCONNECTED
            connected.set(false)
            _messages.close()
        }
```

- [ ] **Step 4: Update close() to set state**

```kotlin
    fun close() {
        _state.value = RelayState.DISCONNECTED
        connected.set(false)
        ws?.close(1000, "Client shutdown")
        _messages.close()
        Log.d(TAG, "Closed $url")
    }
```

- [ ] **Step 5: Verify the file compiles**

Open the file in Android Studio and verify no red underlines. Do NOT run `./gradlew` from terminal.

- [ ] **Step 6: Commit**

From `aivii` user terminal:
```bash
cd /home/aivii/projects/unsilence
git add app/src/main/kotlin/com/unsilence/app/data/relay/RelayConnection.kt
git commit -m "feat: add RelayState enum and StateFlow to RelayConnection"
```

---

## Chunk 2: RelayPool Backoff Reconnection & Persistent Sub Tracking

### Task 2: Add PersistentSub data class and tracking map

**Files:**
- Modify: `data/relay/RelayPool.kt`

- [ ] **Step 1: Add imports and PersistentSub data class**

Add to imports:
```kotlin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
```

Add data class before `RelayPool`:
```kotlin
/**
 * Tracks a persistent subscription for replay after reconnection.
 * [lastEventTime] is Unix seconds — updated when events arrive for this sub.
 */
data class PersistentSub(
    val subId: String,
    val reqJson: String,
    val lastEventTime: Long = 0L,
)
```

- [ ] **Step 2: Replace connections map, add persistentSubs and reconnecting guards**

Replace:
```kotlin
    private val connections = mutableMapOf<String, RelayConnection>()
```
With:
```kotlin
    private val connections = ConcurrentHashMap<String, RelayConnection>()
    private val persistentSubs = ConcurrentHashMap<String, PersistentSub>()
    private val reconnecting = ConcurrentHashMap<String, AtomicBoolean>()

    private val _connectionStates = MutableStateFlow<Map<String, RelayState>>(emptyMap())
    val connectionStates: StateFlow<Map<String, RelayState>> get() = _connectionStates.asStateFlow()
```

- [ ] **Step 3: Commit**

```bash
cd /home/aivii/projects/unsilence
git add app/src/main/kotlin/com/unsilence/app/data/relay/RelayPool.kt
git commit -m "feat: add PersistentSub tracking and ConcurrentHashMap to RelayPool"
```

### Task 3: Register persistent subs and update lastEventTime

**Files:**
- Modify: `data/relay/RelayPool.kt`

- [ ] **Step 1: Add helper to register persistent subscriptions**

Add method to RelayPool:
```kotlin
    /** Register a subscription as persistent so it's replayed after reconnect. */
    private fun registerPersistentSub(subId: String, reqJson: String) {
        persistentSubs[subId] = PersistentSub(subId = subId, reqJson = reqJson)
    }
```

- [ ] **Step 2: Register feed subs as persistent in subscribeAfterConnect()**

In `subscribeAfterConnect()`, after building `feedReq` and before `conn.send(feedReq)`, add:
```kotlin
        val subId = "feed-${conn.url.hashCode()}"
        registerPersistentSub(subId, feedReq)
```

- [ ] **Step 3: Register notifs subs as persistent in fetchNotifications()**

In `fetchNotifications()`, use a stable sub ID prefix so repeated calls replace the previous persistent sub entry (avoids accumulation). Replace the method:
```kotlin
    fun fetchNotifications(userPubkey: String) {
        // Remove any previous notifs persistent sub before registering a new one
        persistentSubs.keys.removeIf { it.startsWith("notifs-") }
        val subId = "notifs-${System.currentTimeMillis()}"
        val req = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(buildJsonObject {
                put("kinds", buildJsonArray {
                    add(JsonPrimitive(1))
                    add(JsonPrimitive(6))
                    add(JsonPrimitive(7))
                    add(JsonPrimitive(9735))
                })
                put("#p",    buildJsonArray { add(JsonPrimitive(userPubkey)) })
                put("limit", JsonPrimitive(100))
            })
        }.toString()
        registerPersistentSub(subId, req)
        connections.values.forEach { it.send(req) }
        Log.d(TAG, "Fetching notifications for $userPubkey from ${connections.size} relay(s)")
    }
```

- [ ] **Step 4: Register follows subs as persistent in connectForAuthors()**

In `connectForAuthors()`, the subId is `"follows-${relayUrl.hashCode()}"` (from `buildAuthorsReq`). Register it. After `val req = buildAuthorsReq(relayUrl, authorPubkeys)`, add:
```kotlin
        val subId = "follows-${relayUrl.hashCode()}"
        registerPersistentSub(subId, req)
```

- [ ] **Step 5: Update lastEventTime in listenForEvents()**

In `listenForEvents()`, when an EVENT message arrives (before calling `processor.process`), extract the subId and update the persistent sub's lastEventTime. Add before the `processor.process(raw, conn.url)` call:

```kotlin
                // Update lastEventTime for persistent sub tracking
                val subId = extractEventSubId(raw)
                if (subId != null) {
                    persistentSubs.computeIfPresent(subId) { _, sub ->
                        sub.copy(lastEventTime = System.currentTimeMillis() / 1000L)
                    }
                }
```

Add the helper method:
```kotlin
    /**
     * Extract the subscription ID from an EVENT message without JSON parsing.
     * Format: ["EVENT","subscription-id",{...}]
     */
    private fun extractEventSubId(raw: String): String? {
        // Skip past "EVENT", find the sub-id quoted string
        val eventEnd = raw.indexOf("\"EVENT\"")
        if (eventEnd < 0) return null
        val quoteOpen = raw.indexOf('"', eventEnd + 7)
        if (quoteOpen < 0) return null
        val subStart = quoteOpen + 1
        val quoteClose = raw.indexOf('"', subStart)
        if (quoteClose < 0) return null
        return raw.substring(subStart, quoteClose)
    }
```

- [ ] **Step 6: Commit**

```bash
cd /home/aivii/projects/unsilence
git add app/src/main/kotlin/com/unsilence/app/data/relay/RelayPool.kt
git commit -m "feat: register persistent subs and track lastEventTime in RelayPool"
```

### Task 4: Backoff reconnection and persistent sub replay

**Files:**
- Modify: `data/relay/RelayPool.kt`

- [ ] **Step 1: Replace reconnectAll() with state-aware backoff reconnection**

Replace the existing `reconnectAll()` method:
```kotlin
    /**
     * Reconnect any relay that has dropped its WebSocket.
     * Called when the app returns to the foreground.
     * Creates new RelayConnection instances (Channel can't be reused after close).
     */
    fun reconnectAll() {
        val dropped = connections.entries
            .filter { it.value.state.value == RelayState.DISCONNECTED ||
                      it.value.state.value == RelayState.FAILED }
            .map { it.key }
        for (url in dropped) {
            reconnectWithBackoff(url)
        }
        if (dropped.isNotEmpty()) Log.d(TAG, "Reconnecting ${dropped.size} relay(s)")
    }

    /**
     * Reconnect a single relay with exponential backoff.
     * Guard: AtomicBoolean per URL prevents concurrent reconnect attempts.
     */
    private fun reconnectWithBackoff(url: String, attempt: Int = 0) {
        val guard = reconnecting.getOrPut(url) { AtomicBoolean(false) }
        if (!guard.compareAndSet(false, true)) return  // already reconnecting

        scope.launch {
            try {
                if (attempt > 0) {
                    val delayMs = minOf(1000L * (1L shl minOf(attempt - 1, 4)), 30_000L)
                    Log.d(TAG, "Backoff $url: attempt $attempt, delay ${delayMs}ms")
                    delay(delayMs)
                }

                connections[url]?.close()
                val conn = RelayConnection(url, okHttpClient)
                connections[url] = conn
                conn.connect()

                // Wait briefly for connection to establish
                var waited = 0
                while (conn.state.value == RelayState.CONNECTING && waited < 5000) {
                    delay(100)
                    waited += 100
                }

                if (conn.state.value == RelayState.CONNECTED) {
                    guard.set(false)
                    updateConnectionStates()
                    replayPersistentSubs(conn)
                    scope.launch { listenForEvents(conn) }
                    Log.d(TAG, "Reconnected $url")
                } else {
                    guard.set(false)
                    if (attempt < 8) {
                        reconnectWithBackoff(url, attempt + 1)
                    } else {
                        Log.w(TAG, "Giving up reconnection to $url after $attempt attempts")
                    }
                }
            } catch (e: Exception) {
                guard.set(false)
                Log.w(TAG, "Reconnect failed for $url: ${e.message}")
                if (attempt < 8) {
                    reconnectWithBackoff(url, attempt + 1)
                }
            }
        }
    }
```

- [ ] **Step 2: Add replayPersistentSubs() and updateConnectionStates()**

```kotlin
    /**
     * Replay all persistent subscriptions on a reconnected relay.
     * Updates the `since` filter to avoid re-fetching old events.
     */
    private fun replayPersistentSubs(conn: RelayConnection) {
        val nowSeconds = System.currentTimeMillis() / 1000L
        for ((_, sub) in persistentSubs) {
            val since = if (sub.lastEventTime > 0) {
                maxOf(sub.lastEventTime - 30, 0)  // 30s buffer for clock skew
            } else {
                nowSeconds - 300  // fallback: 5 minutes ago
            }
            // Inject "since" into the existing REQ JSON
            val updatedReq = injectSince(sub.reqJson, since)
            conn.send(updatedReq)
            Log.d(TAG, "Replayed persistent sub '${sub.subId}' on ${conn.url} (since=$since)")
        }
    }

    /**
     * Inject a "since" field into a REQ JSON filter object.
     * Finds the last '}' of the filter object and inserts before it.
     */
    private fun injectSince(reqJson: String, since: Long): String {
        // Parse and rebuild with since — simpler and more reliable than string manipulation
        val arr = NostrJson.parseToJsonElement(reqJson).jsonArray
        return buildJsonArray {
            add(arr[0]) // "REQ"
            add(arr[1]) // sub-id
            for (i in 2 until arr.size) {
                val filter = arr[i].jsonObject
                add(buildJsonObject {
                    for ((key, value) in filter) {
                        put(key, value)
                    }
                    put("since", JsonPrimitive(since))
                })
            }
        }.toString()
    }

    private fun updateConnectionStates() {
        _connectionStates.value = connections.mapValues { it.value.state.value }
    }
```

- [ ] **Step 3: Add import for jsonObject**

Ensure this import exists at the top of RelayPool.kt:
```kotlin
import kotlinx.serialization.json.jsonObject
```

- [ ] **Step 4: Fix stale comment on line 130**

Replace the comment block:
```kotlin
    /**
     * Subscription IDs are prefixed to encode their lifecycle type.
     *
     *  ONE_SHOT  (close after EOSE): kind3-, kind10002-, profiles-, search-, older-,
     *                                thread-event-, thread-replies-, user-posts-
     *  PERSISTENT (keep open):       feed-, follows-, notifs-
     */
```

- [ ] **Step 5: Add clearPersistentSubs() method for teardown**

```kotlin
    /** Cancel all persistent subscriptions and clear tracking. Called on logout. */
    fun clearPersistentSubs() {
        for ((subId, _) in persistentSubs) {
            connections.values.forEach { it.send("""["CLOSE","$subId"]""") }
        }
        persistentSubs.clear()
    }
```

- [ ] **Step 6: Verify the file compiles in Android Studio**

- [ ] **Step 7: Commit**

```bash
cd /home/aivii/projects/unsilence
git add app/src/main/kotlin/com/unsilence/app/data/relay/RelayPool.kt
git commit -m "feat: add backoff reconnection and persistent sub replay to RelayPool"
```

---

## Chunk 3: EventProcessor & OutboxRouter stop/start + AppBootstrapper Teardown

### Task 5: Add stop() and start() to EventProcessor

**Files:**
- Modify: `data/relay/EventProcessor.kt`

- [ ] **Step 1: Add child Job for drainers**

Add import:
```kotlin
import kotlinx.coroutines.Job
```

Replace the `init` block and add a `drainerJob` field:
```kotlin
    private var drainerJob: Job? = null

    init {
        start()
    }

    /** Launch drainer coroutines under a child Job so they can be cancelled independently. */
    fun start() {
        if (drainerJob?.isActive == true) return
        drainerJob = Job(scope.coroutineContext[Job])
        val drainerScope = CoroutineScope(scope.coroutineContext + drainerJob!!)
        drainerScope.launch { drainHot() }
        drainerScope.launch { drainCold() }
        Log.d(TAG, "Drainers started")
    }

    /** Cancel drainer coroutines and clear in-memory state. Called on logout. */
    fun stop() {
        drainerJob?.cancel()
        drainerJob = null
        seenIds.clear()
        synchronized(kindHandlers) { kindHandlers.clear() }
        // Drain and discard any buffered events
        while (hotChannel.tryReceive().isSuccess) { /* discard */ }
        while (coldChannel.tryReceive().isSuccess) { /* discard */ }
        Log.d(TAG, "Stopped and cleared state")
    }
```

- [ ] **Step 2: Verify the file compiles in Android Studio**

- [ ] **Step 3: Commit**

```bash
cd /home/aivii/projects/unsilence
git add app/src/main/kotlin/com/unsilence/app/data/relay/EventProcessor.kt
git commit -m "feat: add stop()/start() lifecycle to EventProcessor"
```

### Task 6: Add stop() and start() to OutboxRouter

**Files:**
- Modify: `data/relay/OutboxRouter.kt`

- [ ] **Step 1: Add child Job and stop/start methods**

Add import:
```kotlin
import kotlinx.coroutines.Job
```

Add a `routingJob` field after `started`:
```kotlin
    private var routingJob: Job? = null
```

Update `launchRouting()` to use a child job. Replace the line:
```kotlin
    private fun launchRouting() {
```
with:
```kotlin
    private fun launchRouting() {
        routingJob = Job(scope.coroutineContext[Job])
        val routingScope = CoroutineScope(scope.coroutineContext + routingJob!!)
```

Then replace the two `scope.launch` calls inside `launchRouting()` with `routingScope.launch`.

Add stop method:
```kotlin
    /** Cancel routing coroutines. Called on logout. */
    fun stop() {
        routingJob?.cancel()
        routingJob = null
        started.set(false)
        Log.d(TAG, "Stopped")
    }
```

- [ ] **Step 2: Verify the file compiles in Android Studio**

- [ ] **Step 3: Commit**

```bash
cd /home/aivii/projects/unsilence
git add app/src/main/kotlin/com/unsilence/app/data/relay/OutboxRouter.kt
git commit -m "feat: add stop()/start() lifecycle to OutboxRouter"
```

### Task 7: Comprehensive AppBootstrapper teardown

**Files:**
- Modify: `data/AppBootstrapper.kt`

- [ ] **Step 1: Add OutboxRouter and EventProcessor to constructor**

Update the constructor to inject the new dependencies:
```kotlin
@Singleton
class AppBootstrapper @Inject constructor(
    private val relayPool: RelayPool,
    private val keyManager: KeyManager,
    private val appDatabase: AppDatabase,
    private val eventProcessor: EventProcessor,
    private val outboxRouter: OutboxRouter,
) {
```

Add imports:
```kotlin
import com.unsilence.app.data.relay.EventProcessor
import com.unsilence.app.data.relay.OutboxRouter
```

- [ ] **Step 2: Update bootstrap() to call start() on child components**

```kotlin
    fun bootstrap(pubkeyHex: String) {
        eventProcessor.start()
        relayPool.connect(GLOBAL_RELAY_URLS)
        relayPool.fetchProfiles(listOf(pubkeyHex))
        relayPool.fetchFollowList(pubkeyHex)
        relayPool.fetchRelayLists(listOf(pubkeyHex))
        // OutboxRouter.start() is idempotent — safe to call here so it re-registers
        // kind handlers after a teardown/re-login cycle. Also called from FollowingFeedViewModel.
        outboxRouter.start()
        maintenanceJob.start()
        Log.d(TAG, "Bootstrapped for $pubkeyHex")
    }
```

- [ ] **Step 3: Replace teardown() with comprehensive ordered teardown**

```kotlin
    /**
     * Full teardown on logout. Order matters:
     * 1. Cancel persistent subs (send CLOSE messages while connections are still alive)
     * 2. Disconnect all WebSockets
     * 3. Clear all Room tables
     * 4. Clear KeyManager (EncryptedSharedPreferences)
     * 5. Cancel child scopes (OutboxRouter, EventProcessor)
     * 6. Reset in-memory state (seenIds, connection map)
     */
    fun teardown() {
        // 1. Cancel persistent subscriptions
        relayPool.clearPersistentSubs()

        // 2. Disconnect all WebSockets
        relayPool.disconnectAll()

        // 3. Clear all Room tables
        scope.launch { appDatabase.clearAllTables() }

        // 4. Clear credentials
        keyManager.clear()

        // 5. Cancel child scopes (NOT this scope — it must survive for next login)
        outboxRouter.stop()
        eventProcessor.stop()

        // 6. In-memory state already cleared by eventProcessor.stop() (seenIds)
        // and relayPool.disconnectAll() (connections map)

        Log.d(TAG, "Teardown complete")
    }
```

- [ ] **Step 4: Verify the file compiles in Android Studio**

- [ ] **Step 5: Commit**

```bash
cd /home/aivii/projects/unsilence
git add app/src/main/kotlin/com/unsilence/app/data/AppBootstrapper.kt
git commit -m "feat: comprehensive ordered teardown in AppBootstrapper"
```

---

## Chunk 4: Database Index and FIFO Pruning

### Task 8: MIGRATION_3_4 — add (root_id, created_at) index

**Files:**
- Modify: `data/db/Migrations.kt`
- Modify: `data/db/AppDatabase.kt`
- Modify: `di/DatabaseModule.kt`

- [ ] **Step 1: Add MIGRATION_3_4 to Migrations.kt**

Add at the top of the file (before MIGRATION_2_3):
```kotlin
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_root_id_created_at ON events(root_id, created_at)")
    }
}
```

- [ ] **Step 2: Bump database version in AppDatabase.kt**

Change `version = 3` to `version = 4`.

- [ ] **Step 3: Register migration in DatabaseModule.kt**

Add import:
```kotlin
import com.unsilence.app.data.db.MIGRATION_3_4
```

Change:
```kotlin
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
```
To:
```kotlin
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
```

- [ ] **Step 4: Verify the files compile in Android Studio**

- [ ] **Step 5: Commit**

```bash
cd /home/aivii/projects/unsilence
git add app/src/main/kotlin/com/unsilence/app/data/db/Migrations.kt \
        app/src/main/kotlin/com/unsilence/app/data/db/AppDatabase.kt \
        app/src/main/kotlin/com/unsilence/app/di/DatabaseModule.kt
git commit -m "feat: add MIGRATION_3_4 with (root_id, created_at) index for thread queries"
```

### Task 9: Add deleteOldest() DAO method

**Files:**
- Modify: `data/db/dao/EventDao.kt`

- [ ] **Step 1: Add deleteOldest query**

Add to `EventDao` interface, before the closing `}`:
```kotlin
    /** Delete the [limit] oldest events by created_at. Used by FIFO pruning. */
    @Query("""
        DELETE FROM events
        WHERE id IN (
            SELECT id FROM events
            ORDER BY created_at ASC
            LIMIT :limit
        )
    """)
    suspend fun deleteOldest(limit: Int)
```

- [ ] **Step 2: Verify the file compiles in Android Studio**

- [ ] **Step 3: Commit**

```bash
cd /home/aivii/projects/unsilence
git add app/src/main/kotlin/com/unsilence/app/data/db/dao/EventDao.kt
git commit -m "feat: add deleteOldest() DAO method for FIFO pruning"
```

### Task 10: Create DatabaseMaintenanceJob

**Files:**
- Create: `data/db/DatabaseMaintenanceJob.kt`
- Modify: `data/AppBootstrapper.kt`

**Note:** `pruneExpired()` is defined in `EventRepository` and `EventDao` but is NOT called from any insert path — verified by grep. No insert-path cleanup needed.

- [ ] **Step 1: Create DatabaseMaintenanceJob.kt**

```kotlin
package com.unsilence.app.data.db

import android.util.Log
import com.unsilence.app.data.db.dao.EventDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DatabaseMaintenanceJob"
private const val ROW_CAP = 800_000
private const val BATCH_SIZE = 10_000
private const val BATCH_DELAY_MS = 50L
private const val INTERVAL_MS = 5 * 60 * 1000L  // 5 minutes

/**
 * Periodic database maintenance: FIFO pruning (800K row cap) and NIP-40 expiration cleanup.
 * Runs every 5 minutes. Deletes in 10K-row batches with 50ms delays to avoid long DB locks.
 *
 * Lifecycle: started by AppBootstrapper.bootstrap(), stopped by AppBootstrapper.teardown().
 */
@Singleton
class DatabaseMaintenanceJob @Inject constructor(
    private val eventDao: EventDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (true) {
                delay(INTERVAL_MS)
                runMaintenance()
            }
        }
        Log.d(TAG, "Started (interval=${INTERVAL_MS}ms)")
    }

    fun stop() {
        job?.cancel()
        job = null
        Log.d(TAG, "Stopped")
    }

    private suspend fun runMaintenance() {
        try {
            // 1. FIFO prune: keep at most ROW_CAP rows
            val count = eventDao.count()
            var excess = count - ROW_CAP
            if (excess > 0) {
                Log.d(TAG, "FIFO pruning: $count rows, deleting $excess")
                while (excess > 0) {
                    val batch = minOf(excess, BATCH_SIZE)
                    eventDao.deleteOldest(batch)
                    excess -= batch
                    delay(BATCH_DELAY_MS)
                }
                Log.d(TAG, "FIFO prune complete, ${eventDao.count()} rows remaining")
            }

            // 2. NIP-40 expiration prune
            eventDao.pruneExpired(System.currentTimeMillis() / 1000L)
        } catch (e: Exception) {
            Log.w(TAG, "Maintenance failed: ${e.message}")
        }
    }
}
```

- [ ] **Step 2: Wire DatabaseMaintenanceJob into AppBootstrapper**

Add to `AppBootstrapper` constructor:
```kotlin
    private val maintenanceJob: DatabaseMaintenanceJob,
```

Add import:
```kotlin
import com.unsilence.app.data.db.DatabaseMaintenanceJob
```

The `maintenanceJob.start()` and `outboxRouter.start()` calls are already added to `bootstrap()` in Task 7 Step 2.

In `teardown()`, add before the `Log.d` line (after step 5):
```kotlin
        maintenanceJob.stop()
```

- [ ] **Step 3: Verify both files compile in Android Studio**

- [ ] **Step 4: Commit**

```bash
cd /home/aivii/projects/unsilence
git add app/src/main/kotlin/com/unsilence/app/data/db/DatabaseMaintenanceJob.kt \
        app/src/main/kotlin/com/unsilence/app/data/AppBootstrapper.kt
git commit -m "feat: add DatabaseMaintenanceJob with periodic FIFO and expiration pruning"
```

---

## Chunk 5: Verification

### Task 11: Verify A1 — Amber login bootstrap

- [ ] **Step 1: Code trace verification**

Trace the Amber login path in code:
1. `OnboardingScreen.kt:61` — `keyManager.saveAmberLogin(pubkey)`
2. `OnboardingScreen.kt:62` — `onComplete()`
3. `RootScreen.kt:16` — `onComplete = viewModel::onOnboardingComplete`
4. `RootViewModel.kt:21-25` — `onOnboardingComplete()` calls `bootstrapper.bootstrap(pubkey)`

The path is already wired. No code change needed for A1.

- [ ] **Step 2: Manual test on device with Amber installed**

Install on Android 16.0 Baklava emulator (or real device with Amber):
1. Fresh install or logout first
2. Tap "Login with Amber"
3. Approve in Amber
4. Verify: feed loads, profile picture appears, notifications work

If bootstrap doesn't fire, check Logcat for `AppBootstrapper` tag.

### Task 12: Manual verification of all changes

- [ ] **Step 1: Test A10 — background/foreground reconnection**

1. Open app, wait for feed to load
2. Background the app for 30+ seconds
3. Return to app
4. Check Logcat for `RelayPool` tag: should see "Reconnecting" and "Replayed persistent sub" messages
5. Verify feed continues to update

- [ ] **Step 2: Test A9 — notifications survive reconnect**

1. Open notifications tab, verify notifications load
2. Background app for 30+ seconds
3. Return to app
4. Check Logcat: `notifs-` sub should be replayed
5. Have another account interact with your posts
6. Verify new notifications appear without needing to restart

- [ ] **Step 3: Test A2 — logout teardown**

1. Tap logout
2. Check Logcat for `AppBootstrapper`: should see "Teardown complete"
3. Login with a DIFFERENT account
4. Verify: no stale data from previous account, feed shows new account's content

- [ ] **Step 4: Test migration**

1. Install the previous version of the app (pre-migration)
2. Use the app to populate the database
3. Install the new version (with MIGRATION_3_4)
4. Verify app starts without crash (migration succeeds)

- [ ] **Step 5: Verify pruning runs**

1. Check Logcat for `DatabaseMaintenanceJob` tag after 5+ minutes
2. Should see "Started" message on bootstrap
3. Should see maintenance running (even if no pruning needed for small DB)
