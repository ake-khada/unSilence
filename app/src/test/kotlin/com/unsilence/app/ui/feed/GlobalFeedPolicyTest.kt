package com.unsilence.app.ui.feed

import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.memory.WotAssertionEntity
import com.unsilence.app.data.memory.WotLookup
import com.unsilence.app.domain.model.GlobalFeedLens
import com.unsilence.app.domain.model.parseGlobalFeedLens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalFeedPolicyTest {
    @Test
    fun `json artifact requires the entire trimmed content to be an object or array`() {
        assertTrue(isJsonArtifact("""{"type":"bridge"}"""))
        assertTrue(isJsonArtifact("""[1,{"type":"bridge"}]"""))
        assertTrue(isJsonArtifact("  \n {" + "\"type\":\"bridge\"}" + " \t"))

        assertFalse(isJsonArtifact("""{"type":"bridge"} human note"""))
        assertFalse(isJsonArtifact("A human note with {braces} in it"))
        assertFalse(isJsonArtifact(""))
        assertFalse(isJsonArtifact("   \n\t"))
    }

    @Test
    fun `json artifact parsing is bounded`() {
        val oversized = "{" + " ".repeat(MAX_JSON_ARTIFACT_LENGTH) + "}"

        assertFalse(isJsonArtifact(oversized))
    }

    @Test
    fun `tag-only miasma peer announcements are protocol artifacts`() {
        val fieldEvent = event(
            id = "peer",
            content = "",
            tags = listOf(
                listOf("t", "miasma-peer"),
                listOf("multiaddr", "/ip4/127.0.0.1/tcp/4100/p2p/peer"),
            ),
        )

        assertTrue(isTagOnlyProtocolArtifact(fieldEvent))
        assertFalse(isTagOnlyProtocolArtifact(fieldEvent.copy(content = "Peer announcement")))
        assertFalse(isTagOnlyProtocolArtifact(fieldEvent.copy(tags = listOf(listOf("t", "miasma-peer")))))
        assertFalse(isTagOnlyProtocolArtifact(fieldEvent.copy(kind = 10_002)))
    }

    @Test
    fun `burst duplicate keeps the first event inside ten minutes`() {
        val tracker = BurstDuplicateTracker()
        val hash = contentFingerprint64("same note")

        assertFalse(tracker.isBurstDuplicate("alice", hash, 1_000L))
        assertTrue(tracker.isBurstDuplicate("alice", hash, 1_599L))
        assertFalse(tracker.isBurstDuplicate("alice", hash, 1_600L))
        assertFalse(tracker.isBurstDuplicate("bob", hash, 1_601L))
    }

    @Test
    fun `burst tracker evicts least recently used entries at its cap`() {
        val tracker = BurstDuplicateTracker(maxEntries = 2)

        assertFalse(tracker.isBurstDuplicate("alice", 1L, 1_000L))
        assertFalse(tracker.isBurstDuplicate("bob", 2L, 1_000L))
        assertFalse(tracker.isBurstDuplicate("carol", 3L, 1_000L))
        assertEquals(2, tracker.trackedEntryCount)
        assertFalse(tracker.isBurstDuplicate("alice", 1L, 1_001L))
    }

    @Test
    fun `trusted gate maps scored absent and pending distinctly`() {
        assertEquals(TrustedGateVerdict.PASS, trustedGateVerdict(scored("alice")))
        assertEquals(TrustedGateVerdict.DROP, trustedGateVerdict(WotLookup.Absent))
        assertEquals(TrustedGateVerdict.HOLD, trustedGateVerdict(WotLookup.Pending))
    }

    @Test
    fun `policy modes keep Following explicit and Raw genuinely raw`() {
        assertEquals(FeedPolicyMode.TRUSTED, feedPolicyMode(FeedType.Global, GlobalFeedLens.TRUSTED))
        assertEquals(FeedPolicyMode.NONE, feedPolicyMode(FeedType.Global, GlobalFeedLens.RAW))
        assertEquals(FeedPolicyMode.NONE, feedPolicyMode(FeedType.Following, GlobalFeedLens.TRUSTED))
        assertEquals(
            FeedPolicyMode.HEURISTICS,
            feedPolicyMode(FeedType.SingleRelay("wss://nos.lol", "nos.lol"), GlobalFeedLens.RAW),
        )
        assertEquals(
            FeedPolicyMode.HEURISTICS,
            feedPolicyMode(FeedType.RelaySet("set", "Set"), GlobalFeedLens.RAW),
        )
    }

    @Test
    fun `pending trusted row is held and enters after its score resolves`() {
        val policy = GlobalFeedPolicy()
        val note = event(id = "note", pubkey = "alice", content = "hello")
        var lookup: WotLookup = WotLookup.Pending

        val pending = policy.project(listOf(note), applyHeuristics = true) { lookup }
        assertTrue(pending.accepted.isEmpty())
        assertEquals(listOf("alice"), pending.pendingAuthorPubkeys)
        assertEquals(1, pending.counters.pendingAuthors)

        lookup = scored("alice")
        val resolved = policy.project(listOf(note), applyHeuristics = true) { lookup }
        assertEquals(listOf(note), resolved.accepted)
        assertEquals(0, resolved.counters.total)
    }

    @Test
    fun `heuristics count json and duplicate drops without collapsing valid empty media`() {
        val policy = GlobalFeedPolicy()
        val candidates = listOf(
            event(id = "json", content = """{"type":"bot"}""", createdAt = 4_000L),
            event(id = "first", content = "repeated", createdAt = 3_000L),
            event(id = "duplicate", content = "repeated", createdAt = 2_999L),
            event(
                id = "peer",
                content = "",
                createdAt = 2_500L,
                tags = listOf(
                    listOf("t", "miasma-peer"),
                    listOf("multiaddr", "/ip4/127.0.0.1/tcp/4100/p2p/peer"),
                ),
            ),
            event(id = "media-a", kind = 22, content = "", createdAt = 2_000L),
            event(id = "media-b", kind = 22, content = "", createdAt = 1_999L),
            event(id = "repost", kind = 6, content = """{"kind":1}""", createdAt = 1_000L),
        )

        val result = policy.project(candidates, applyHeuristics = true)

        assertEquals(listOf("first", "media-a", "media-b", "repost"), result.accepted.map { it.id })
        assertEquals(1, result.counters.jsonArtifacts)
        assertEquals(1, result.counters.tagOnlyProtocolArtifacts)
        assertEquals(1, result.counters.burstDuplicates)
    }

    @Test
    fun `legacy Popular selection restores to Global without affecting relay browse`() {
        assertSame(
            FeedType.Global,
            restoreFeedTypeOrGlobal(FeedType.SingleRelay("wss://antiprimal.net/hot/", "Popular")),
        )

        val browse = FeedType.SingleRelay("wss://nos.lol", "nos.lol")
        assertEquals(browse, restoreFeedTypeOrGlobal(browse))
    }

    @Test
    fun `global lens preference defaults safely and restores raw`() {
        assertEquals(GlobalFeedLens.TRUSTED, parseGlobalFeedLens(null))
        assertEquals(GlobalFeedLens.TRUSTED, parseGlobalFeedLens("REMOVED_VALUE"))
        assertEquals(GlobalFeedLens.RAW, parseGlobalFeedLens("RAW"))
    }

    private fun scored(pubkey: String): WotLookup.Scored = WotLookup.Scored(
        WotAssertionEntity(
            subjectPubkey = pubkey,
            providerPubkey = "provider",
            rank = 1,
        ),
    )

    private fun event(
        id: String,
        pubkey: String = "alice",
        kind: Int = 1,
        content: String,
        createdAt: Long = 1_000L,
        tags: List<List<String>> = emptyList(),
    ): NostrEvent = NostrEvent(
        id = id,
        pubkey = pubkey,
        kind = kind,
        content = content,
        createdAt = createdAt,
        tags = tags,
        tagsJson = "[]",
        sig = "sig",
        relayUrl = "wss://relay.example",
        replyToId = null,
        rootId = null,
        hasContentWarning = false,
        contentWarningReason = null,
        firstSeenAt = createdAt,
        relaysSeen = mutableSetOf(),
    )
}
