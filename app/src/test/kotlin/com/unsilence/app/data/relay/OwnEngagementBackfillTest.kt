package com.unsilence.app.data.relay

import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.NostrEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for own-engagement backfill logic:
 * - REQ filter construction (buildOwnEngagementReq)
 * - Dedup sets (checked blocks re-fetch, failed allows retry)
 * - isOwnEngaged read-only accessor
 */
class OwnEngagementBackfillTest {

    private lateinit var store: MemoryEventStore

    @Before
    fun setUp() {
        store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
    }

    private fun event(
        id: String,
        pubkey: String = "pk-default",
        kind: Int = 1,
        content: String = "",
        createdAt: Long = 1700000000L,
        tags: List<List<String>> = emptyList(),
        relayUrl: String = "wss://relay.test",
        replyToId: String? = null,
        rootId: String? = null,
    ) = NostrEvent(
        id = id,
        pubkey = pubkey,
        kind = kind,
        content = content,
        createdAt = createdAt,
        tags = tags,
        tagsJson = "[]",
        sig = "sig",
        relayUrl = relayUrl,
        replyToId = replyToId,
        rootId = rootId,
        hasContentWarning = false,
        contentWarningReason = null,
        firstSeenAt = System.currentTimeMillis(),
        relaysSeen = mutableSetOf(relayUrl),
    )

    // ── buildOwnEngagementReq ────────────────────────────────────────────

    @Test
    fun `buildOwnEngagementReq constructs correct filter`() {
        val ownPk = "abc123hex"
        val ids = listOf("evt-1", "evt-2", "evt-3")
        val req = buildOwnEngagementReq("sub-test", ownPk, ids)

        val parsed = Json.parseToJsonElement(req).jsonArray
        assertEquals("REQ", parsed[0].jsonPrimitive.content)
        assertEquals("sub-test", parsed[1].jsonPrimitive.content)

        val filter = parsed[2].jsonObject
        // authors = [ownPk]
        val authors = filter["authors"]!!.jsonArray
        assertEquals(1, authors.size)
        assertEquals(ownPk, authors[0].jsonPrimitive.content)

        // kinds = [7, 6, 16] — reactions + note reposts + generic (NIP-18) reposts
        val kinds = filter["kinds"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() }
        assertEquals(listOf(7, 6, 16), kinds)

        // #e = [evt-1, evt-2, evt-3]
        val eTags = filter["#e"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(ids, eTags)
    }

    @Test
    fun `buildOwnEngagementReq single event`() {
        val req = buildOwnEngagementReq("s1", "pk", listOf("only-one"))
        val filter = Json.parseToJsonElement(req).jsonArray[2].jsonObject
        val eTags = filter["#e"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("only-one"), eTags)
    }

    @Test
    fun `buildOwnEngagementReq adds author-scoped coordinate filters for articles`() {
        val req = buildOwnEngagementReq("s1", "ownpk", listOf("evt"), listOf("30023:a:slug"))
        val filters = Json.parseToJsonElement(req).jsonArray.drop(2).map { it.jsonObject }
        assertEquals(3, filters.size) // #e + #a + #A
        // coordinate filters are author-scoped and kind-7 only (own likes)
        assertEquals(listOf("ownpk"), filters[1]["authors"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf(7), filters[1]["kinds"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() })
        assertEquals(listOf("30023:a:slug"), filters[1]["#a"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("30023:a:slug"), filters[2]["#A"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    // ── Dedup sets ───────────────────────────────────────────────────────

    @Test
    fun `checked post is not retried`() {
        // Simulate: a post ID was successfully checked (EOSE received)
        val checkedSet: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        checkedSet.add("already-checked")

        // Filter: should exclude already-checked IDs
        val candidates = listOf("already-checked", "new-post")
        val novel = candidates.filter { it !in checkedSet }
        assertEquals(listOf("new-post"), novel)
    }

    @Test
    fun `failed post stays retry-eligible`() {
        // Simulate: a post was in-flight but fetch failed
        val inFlight: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        val checked: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        val batch = listOf("will-fail")
        // Mark in-flight
        batch.forEach { inFlight.add(it) }

        // Simulate failure: remove from in-flight, do NOT add to checked
        batch.forEach { inFlight.remove(it) }

        // On retry: should pass filter (not in checked, not in in-flight)
        val retryNovel = batch.filter { it !in checked && it !in inFlight }
        assertEquals(listOf("will-fail"), retryNovel)
    }

    @Test
    fun `in-flight post is not double-dispatched`() {
        val inFlight: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        inFlight.add("in-progress")

        val candidates = listOf("in-progress", "new-post")
        val novel = candidates.filter { it !in inFlight }
        assertEquals(listOf("new-post"), novel)
    }

    // ── isOwnEngaged ─────────────────────────────────────────────────────

    @Test
    fun `isOwnEngaged returns false when ownPubkey is null`() {
        // ownPubkey not set
        assertFalse(store.isOwnEngaged("any-event"))
    }

    @Test
    fun `isOwnEngaged returns false for non-engaged event`() {
        store.ownPubkey = "my-pk"
        assertFalse(store.isOwnEngaged("some-event"))
    }

    @Test
    fun `isOwnEngaged returns true after reacting`() {
        store.ownPubkey = "my-pk"
        // Insert a kind-7 reaction from own pubkey targeting "target-1"
        store.insert(event(
            id = "react-1",
            pubkey = "my-pk",
            kind = 7,
            tags = listOf(listOf("e", "target-1"), listOf("p", "author-1")),
            createdAt = 100,
        ))
        assertTrue(store.isOwnEngaged("target-1"))
        assertFalse(store.isOwnEngaged("target-2"))
    }

    @Test
    fun `isOwnEngaged returns true after reposting`() {
        store.ownPubkey = "my-pk"
        // Insert a kind-6 repost from own pubkey targeting "target-2"
        store.insert(event(
            id = "repost-1",
            pubkey = "my-pk",
            kind = 6,
            tags = listOf(listOf("e", "target-2"), listOf("p", "author-2")),
            content = "",
            createdAt = 101,
            rootId = "target-2",
        ))
        assertTrue(store.isOwnEngaged("target-2"))
    }

    @Test
    fun `isOwnEngaged ignores other users engagement`() {
        store.ownPubkey = "my-pk"
        // Someone else reacted
        store.insert(event(
            id = "react-other",
            pubkey = "other-pk",
            kind = 7,
            tags = listOf(listOf("e", "target-3"), listOf("p", "author-3")),
            createdAt = 100,
        ))
        assertFalse(store.isOwnEngaged("target-3"))
    }
}
