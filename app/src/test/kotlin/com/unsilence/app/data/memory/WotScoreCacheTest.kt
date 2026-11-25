package com.unsilence.app.data.memory

import androidx.core.util.AtomicFile
import com.unsilence.app.data.DEFAULT_WOT_PROVIDER_PUBKEY
import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.relay.EventDto
import com.unsilence.app.data.relay.stubTimelineServiceProvider
import com.unsilence.app.data.relay.toNostrEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WotScoreCacheTest {
    @get:Rule val dir = TemporaryFolder()
    private val owner = "1".repeat(64)
    private val subject = "2".repeat(64)
    private val provider = DEFAULT_WOT_PROVIDER_PUBKEY
    private fun store() = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider()).apply {
        ownPubkey = owner
    }
    private fun assertion(rank: Int = 48, createdAt: Long = 100) =
        EventDto(id = "%064x".format(rank), pubkey = provider, kind = 30382, createdAt = createdAt,
            tags = listOf(listOf("d", subject), listOf("rank", rank.toString())), content = "", sig = "0".repeat(128))
            .toNostrEvent("")
    @Test fun lastConfirmedScoreSurvivesRestartWithoutRelayAccess() = runBlocking {
        val disk = AtomicFile(File(dir.root, "snapshot"))
        val live = store()
        live.insertWotAssertionChunk(provider, listOf(assertion()), listOf(subject))
        SnapshotScheduler(live, disk).saveNow()
        val reopened = store()
        SnapshotScheduler(reopened, disk).restoreIfPresent()
        assertEquals(48, (reopened.wotFor(subject) as? WotLookup.Scored)?.assertion?.rank)
    }

    @Test fun ageIsPerScoreAndReadsAndFailedRefreshesNeverExtendIt() {
        val now = System.currentTimeMillis()
        val live = store()
        live.insertWotAssertionChunk(provider, listOf(assertion()), listOf(subject))
        val confirmed = (live.wotFor(subject) as WotLookup.Scored).assertion
        val stamp = confirmed.confirmedAt
        assertTrue(stamp >= now)
        val halfDay = 12 * 3_600_000L
        val cached = live.displayWotFor(subject, stamp + halfDay) as WotLookup.Scored
        assertTrue(cached.cached)
        assertFalse((live.displayWotFor(subject, stamp + halfDay - 1) as WotLookup.Scored).cached)
        live.insertWotAssertionChunk(provider, emptyList(), emptyList())
        assertEquals(stamp, (live.displayWotFor(subject) as WotLookup.Scored).assertion.confirmedAt)
        assertEquals(WotLookup.Pending, live.displayWotFor(subject, stamp + 4 * halfDay))
        assertEquals(WotLookup.Pending, live.displayWotFor(subject, stamp - 1))
        live.refreshWotAges(stamp + 4 * halfDay)
        assertFalse(live.hasWotData())
        assertTrue(requireNotNull(live.wotCacheSnapshot()).scores.isEmpty())
    }

    @Test fun staleScoreIsDisplayOnlyAndOnlyAnActualResultCanRenewIt() {
        val now = System.currentTimeMillis()
        val live = store()
        val old = WotAssertionEntity(subject, provider, 48, updatedAt = 100, eventId = assertion().id,
            confirmedAt = now - 18 * 3_600_000L)
        live.restoreWotScores(WotScoreSnapshot(owner, provider, listOf(old)))
        assertTrue((live.displayWotFor(subject) as WotLookup.Scored).cached)
        assertEquals(WotLookup.Pending, live.wotFor(subject))
        assertTrue(live.getWotAssertions().isEmpty())
        assertFalse(live.hasFreshWotCoverage(listOf(subject)))
        live.insertWotAssertionChunk(provider, listOf(assertion(32, 90)), emptyList())
        assertEquals(old, (live.displayWotFor(subject) as WotLookup.Scored).assertion)
        live.insertWotAssertionChunk(provider, listOf(assertion()), listOf(subject))
        assertTrue((live.wotFor(subject) as WotLookup.Scored).assertion.confirmedAt >= now)
        live.insertWotAssertionChunk(provider, listOf(assertion(82, 120)), listOf(subject))
        assertEquals(82, (live.wotFor(subject) as WotLookup.Scored).assertion.rank)
        assertTrue(live.hasFreshWotCoverage(listOf(subject)))
        assertFalse(live.hasFreshWotCoverage(listOf(subject, "a".repeat(64))))
        // A slow empty response must not erase a score that arrived after its request started.
        live.insertWotAssertionChunk(provider, emptyList(), listOf(subject), queryStartedAt = now - 1000)
        assertEquals(82, (live.wotFor(subject) as WotLookup.Scored).assertion.rank)
        live.insertWotAssertionChunk(provider, emptyList(), listOf(subject), queryStartedAt = System.currentTimeMillis() + 1)
        assertEquals(WotLookup.Absent, live.displayWotFor(subject))
    }

    @Test fun restartRespectsAccountProviderExpiryAndCacheDamage() = runBlocking {
        val disk = AtomicFile(File(dir.root, "snapshot"))
        val live = store()
        live.insertWotAssertionChunk(provider, listOf(assertion()), listOf(subject))
        SnapshotScheduler(live, disk).saveNow()
        val switched = store().apply { ownPubkey = "9".repeat(64) }
        assertEquals(SnapshotRestoreOutcome.REJECTED, SnapshotScheduler(switched, disk).restoreIfPresent())
        assertEquals(WotLookup.Pending, switched.displayWotFor(subject))
        // The owner-mismatch path quarantines the main snapshot. Restore the original fixture.
        SnapshotScheduler(live, disk).saveNow()
        val otherProvider = store().apply { setActiveWotProvider("4".repeat(64)) }
        SnapshotScheduler(otherProvider, disk).restoreIfPresent()
        assertEquals(WotLookup.Pending, otherProvider.displayWotFor(subject))
        val expired = (live.wotFor(subject) as WotLookup.Scored).assertion.copy(confirmedAt = 1L)
        val cold = store()
        cold.restoreWotScores(WotScoreSnapshot(owner, provider, listOf(expired)))
        assertEquals(WotLookup.Pending, cold.displayWotFor(subject))
        WotScoreCache(File(dir.root, "snapshot.wot")).write(WotScoreSnapshot(owner, provider, listOf(expired)))
        val reopened = SnapshotScheduler(cold, disk)
        reopened.restoreIfPresent()
        reopened.saveNow()
        assertFalse(File(dir.root, "snapshot.wot").exists())
        File(dir.root, "snapshot.wot").writeText("{")
        assertEquals(SnapshotRestoreOutcome.RESTORED, SnapshotScheduler(cold, disk).restoreIfPresent())
        assertEquals(WotLookup.Pending, cold.displayWotFor(subject))
        live.ownPubkey = "5".repeat(64)
        live.insertWotAssertionChunk(provider, listOf(assertion()), listOf(subject), queryOwner = owner)
        assertEquals(WotLookup.Pending, live.displayWotFor(subject))
    }
}
