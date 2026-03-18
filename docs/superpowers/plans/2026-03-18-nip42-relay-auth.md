# NIP-42 Relay Authentication Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement full NIP-42 relay authentication so paid/private relays work automatically and silently.

**Architecture:** All changes live in `RelayPool.kt`. On receiving an `["AUTH", challenge]` message, sign a kind-22242 event using `SigningManager` + Quartz's `RelayAuthEvent`, send `["AUTH", signedEvent]` back, then replay all persistent subscriptions. Also intercept `["CLOSED", subId, "auth-required:..."]` to trigger auth and replay the closed subscription. No new classes, files, or database tables.

**Tech Stack:** Quartz (`RelayAuthEvent`, `NormalizedRelayUrl`), `SigningManager` (dual nsec/Amber), `RelayPool` state maps, kotlinx.coroutines.

---

### Task 1: Add AUTH State Tracking Fields

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/data/relay/RelayPool.kt:66-76`

- [ ] **Step 1: Add three state fields after existing ConcurrentHashMaps (line ~76)**

After `eventFetchInFlight` (line 79), add:

```kotlin
/** Relays that have completed NIP-42 auth successfully. */
private val authenticatedRelays = ConcurrentHashMap.newKeySet<String>()

/** Relays currently waiting for an auth response (prevents duplicate auth attempts). */
private val authInFlight = ConcurrentHashMap.newKeySet<String>()

/** Last challenge received per relay — needed for CLOSED auth-required flow. */
private val pendingChallenges = ConcurrentHashMap<String, String>()
```

- [ ] **Step 2: Add import for NormalizedRelayUrl**

At the imports section, add:

```kotlin
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip42RelayAuth.RelayAuthEvent
```

- [ ] **Step 3: Verify the project compiles**

Run: `cd /home/aivii/projects/unsilence && ./gradlew :app:compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/data/relay/RelayPool.kt
git commit -m "feat(nip42): add auth state tracking fields to RelayPool"
```

---

### Task 2: Implement handleAuthChallenge()

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/data/relay/RelayPool.kt`

- [ ] **Step 1: Add handleAuthChallenge method**

Add this method after the existing `replayPersistentSubs()` method (after line ~1132):

```kotlin
/**
 * NIP-42: Sign and send an AUTH response for the given relay challenge.
 * After successful auth, replays all persistent subscriptions on this relay.
 */
private fun handleAuthChallenge(conn: RelayConnection, challenge: String) {
    val url = conn.url
    pendingChallenges[url] = challenge

    // Skip if already authenticated or auth is in flight
    if (url in authenticatedRelays) {
        Log.d(TAG, "AUTH: already authenticated to $url, skipping")
        return
    }
    if (!authInFlight.add(url)) {
        Log.d(TAG, "AUTH: already in flight for $url, skipping")
        return
    }

    scope.launch {
        try {
            val normalizedUrl = NormalizedRelayUrl(url)
            val template = RelayAuthEvent.build(normalizedUrl, challenge)
            val signed = signingManager.sign(template)

            if (signed == null) {
                Log.w(TAG, "AUTH: signing failed for $url (signer returned null)")
                authInFlight.remove(url)
                return@launch
            }

            // Send ["AUTH", {signed event JSON}]
            val authJson = """["AUTH",${signed.toJson()}]"""
            val sent = conn.send(authJson)

            if (sent) {
                // TODO: NIP-42 specifies relay responds with ["OK",...] —
                // for now we optimistically mark as authenticated after send.
                authenticatedRelays.add(url)
                Log.d(TAG, "AUTH: sent auth response to $url")
                // Replay all persistent subs now that we're authenticated
                replayPersistentSubs(conn)
            } else {
                Log.w(TAG, "AUTH: failed to send auth to $url (connection closed?)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "AUTH: error authenticating to $url", e)
        } finally {
            authInFlight.remove(url)
        }
    }
}
```

- [ ] **Step 2: Verify the project compiles**

Run: `cd /home/aivii/projects/unsilence && ./gradlew :app:compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/data/relay/RelayPool.kt
git commit -m "feat(nip42): implement handleAuthChallenge with sign+send+replay"
```

---

### Task 3: Wire AUTH Stub to handleAuthChallenge

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/data/relay/RelayPool.kt:336-341`

- [ ] **Step 1: Replace the AUTH stub in listenForEvents()**

Replace lines 336-341:

```kotlin
// NIP-42 AUTH challenge — structural preparation (stub: log and ignore)
if (raw.startsWith("[\"AUTH\"")) {
    val challenge = raw.substringAfter("[\"AUTH\",\"", "").substringBefore("\"")
    Log.d(TAG, "AUTH challenge from ${conn.url}: ${challenge.take(20)}… (not yet implemented)")
    return@consumeEach
}
```

With:

```kotlin
// NIP-42 AUTH challenge — sign and respond automatically
if (raw.startsWith("[\"AUTH\"")) {
    val challenge = raw.substringAfter("[\"AUTH\",\"", "").substringBefore("\"")
    if (challenge.isNotEmpty()) {
        Log.d(TAG, "AUTH challenge from ${conn.url}: ${challenge.take(20)}…")
        handleAuthChallenge(conn, challenge)
    }
    return@consumeEach
}
```

- [ ] **Step 2: Verify the project compiles**

Run: `cd /home/aivii/projects/unsilence && ./gradlew :app:compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/data/relay/RelayPool.kt
git commit -m "feat(nip42): wire AUTH challenge handler in listenForEvents"
```

---

### Task 4: Handle CLOSED auth-required Messages

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/data/relay/RelayPool.kt:311-366` (listenForEvents)

- [ ] **Step 1: Add CLOSED handler before the EVENT processing block**

In `listenForEvents()`, add a CLOSED handler after the AUTH block (after the `return@consumeEach` for AUTH) and before the `// Update lastEventTime` comment (line 342):

```kotlin
// NIP-42 CLOSED with auth-required — authenticate then replay the sub
if (raw.startsWith("[\"CLOSED\"")) {
    try {
        val arr = NostrJson.parseToJsonElement(raw).jsonArray
        val closedSubId = arr[1].jsonPrimitive.content
        val reason = arr.getOrNull(2)?.jsonPrimitive?.content ?: ""
        if (reason.startsWith("auth-required")) {
            Log.d(TAG, "CLOSED auth-required for sub '$closedSubId' on ${conn.url}: $reason")
            val challenge = pendingChallenges[conn.url]
            if (challenge != null && conn.url !in authenticatedRelays) {
                handleAuthChallenge(conn, challenge)
            } else if (conn.url in authenticatedRelays) {
                // Already authed — just replay the specific closed sub
                persistentSubs[closedSubId]?.let { sub ->
                    conn.send(sub.reqJson)
                    Log.d(TAG, "Replayed closed sub '$closedSubId' on ${conn.url}")
                }
            }
        } else {
            Log.d(TAG, "CLOSED sub '$closedSubId' on ${conn.url}: $reason")
        }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to parse CLOSED message: ${e.message}")
    }
    return@consumeEach
}
```

- [ ] **Step 2: Add missing jsonArray/jsonPrimitive imports if needed**

Ensure these imports exist (they likely already do from existing JSON usage):

```kotlin
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
```

- [ ] **Step 3: Verify the project compiles**

Run: `cd /home/aivii/projects/unsilence && ./gradlew :app:compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/data/relay/RelayPool.kt
git commit -m "feat(nip42): handle CLOSED auth-required with re-auth and sub replay"
```

---

### Task 5: Clear Auth State on Disconnect/Teardown

**Files:**
- Modify: `app/src/main/kotlin/com/unsilence/app/data/relay/RelayPool.kt`

- [ ] **Step 1: Clear auth state in disconnectAll()**

Find the `disconnectAll()` method and add cleanup of auth state after the existing cleanup:

```kotlin
authenticatedRelays.clear()
authInFlight.clear()
pendingChallenges.clear()
```

- [ ] **Step 2: Clear per-relay auth state when a single relay disconnects**

In `reconnectWithBackoff()`, after `connections[url]?.close()` (line ~1080), add:

```kotlin
authenticatedRelays.remove(url)
pendingChallenges.remove(url)
```

This ensures a reconnected relay goes through auth again.

- [ ] **Step 3: Verify the project compiles**

Run: `cd /home/aivii/projects/unsilence && ./gradlew :app:compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/unsilence/app/data/relay/RelayPool.kt
git commit -m "feat(nip42): clear auth state on disconnect and reconnect"
```

---

### Task 6: Verify End-to-End

- [ ] **Step 1: Full build check**

Run: `cd /home/aivii/projects/unsilence && ./gradlew :app:assembleDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Review all changes**

Run: `cd /home/aivii/projects/unsilence && git diff HEAD~5 --stat` to confirm changes are confined to `RelayPool.kt`.

- [ ] **Step 3: Manual verification checklist**

Verify in the code:
1. AUTH challenge → `handleAuthChallenge()` called (not just logged)
2. `handleAuthChallenge()` signs with `signingManager.sign()` → sends `["AUTH", event]` → replays persistent subs
3. CLOSED auth-required → triggers auth if challenge cached, replays sub after auth
4. `authenticatedRelays` prevents duplicate auth per relay
5. `authInFlight` prevents concurrent auth per relay
6. Disconnect/reconnect clears auth state so relay re-authenticates
7. No UI changes, no new files, no new database tables
