package com.unsilence.app.data.cache

import com.unsilence.app.data.memory.SyncStateEntity
import com.unsilence.app.data.relay.CoverageHandle
import com.unsilence.app.data.relay.CoverageIntent
import com.unsilence.app.data.relay.CoverageStatus
import com.unsilence.app.data.relay.Lane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class EphemeralTrackersTest {

    private lateinit var coverage: CoverageTracker
    private lateinit var sync: SyncTracker

    @Before
    fun setUp() {
        coverage = CoverageTracker()
        sync = SyncTracker()
    }

    // ── CoverageTracker ─────────────────────────────────────────────────────

    @Test
    fun `ensureCoverage creates pending entry and returns LOADING`() {
        val status = coverage.ensureCoverage(CoverageIntent.HomeFeed())
        assertEquals(CoverageStatus.LOADING, status)
    }

    @Test
    fun `ensureCoverage returns COMPLETE after markFromHandle with all EOSE`() {
        val intent = CoverageIntent.UserPosts("pk1")
        coverage.ensureCoverage(intent)

        val lane = Lane("sub1", "wss://relay.example.com")
        val handle = CoverageHandle(
            handleId = "h1",
            scopeType = "user_posts", scopeKey = "pk1", relaySetId = "global",
            expectedLanes = setOf(lane),
        )
        handle.eoseLanes.add(lane) // all lanes succeeded → COMPLETE
        coverage.markFromHandle(handle)

        val status = coverage.getStatus("user_posts", "pk1", "global")
        assertEquals(CoverageStatus.COMPLETE, status)
    }

    @Test
    fun `markFailed transitions pending entry to FAILED`() {
        coverage.ensureCoverage(CoverageIntent.Thread("root1"))
        coverage.markFailed("thread", "root1", "global")
        assertEquals(CoverageStatus.FAILED, coverage.getStatus("thread", "root1", "global"))
    }

    @Test
    fun `getStatus returns NEVER_FETCHED for unknown scope`() {
        assertEquals(CoverageStatus.NEVER_FETCHED, coverage.getStatus("x", "y", "z"))
    }

    @Test
    fun `clear empties all coverage state`() {
        coverage.ensureCoverage(CoverageIntent.HomeFeed())
        coverage.clear()
        assertEquals(CoverageStatus.NEVER_FETCHED, coverage.getStatus("home", "home", "global"))
    }

    // ── SyncTracker ─────────────────────────────────────────────────────────

    @Test
    fun `upsert creates new entry`() {
        val entity = SyncStateEntity("sub-1", lastSyncAt = 1000L, lastEventCount = 5, source = "relay")
        sync.upsert(entity)
        assertEquals(entity, sync.get("sub-1"))
    }

    @Test
    fun `upsert overwrites existing entry`() {
        sync.upsert(SyncStateEntity("sub-1", lastSyncAt = 1000L, lastEventCount = 5, source = "r1"))
        val updated = SyncStateEntity("sub-1", lastSyncAt = 2000L, lastEventCount = 10, source = "r2")
        sync.upsert(updated)
        assertEquals(updated, sync.get("sub-1"))
    }

    @Test
    fun `get returns null for unknown key`() {
        assertNull(sync.get("nonexistent"))
    }

    @Test
    fun `updateTimestamp accumulates delta`() {
        sync.upsert(SyncStateEntity("sub-1", lastSyncAt = 1000L, lastEventCount = 5, source = "r1"))
        sync.updateTimestamp("sub-1", ts = 2000L, delta = 3, src = "r2")
        val result = sync.get("sub-1")!!
        assertEquals(2000L, result.lastSyncAt)
        assertEquals(8, result.lastEventCount)
        assertEquals("r2", result.source)
    }

    @Test
    fun `clear empties all sync state`() {
        sync.upsert(SyncStateEntity("sub-1", lastSyncAt = 1000L, lastEventCount = 5, source = "r1"))
        sync.clear()
        assertNull(sync.get("sub-1"))
    }
}
