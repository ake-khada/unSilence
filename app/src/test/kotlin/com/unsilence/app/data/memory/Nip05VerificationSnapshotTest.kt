package com.unsilence.app.data.memory

import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.network.Nip05VerificationCacheEntry
import com.unsilence.app.data.network.Nip05VerificationStatus
import com.unsilence.app.data.network.nip05VerificationCacheKey
import com.unsilence.app.data.relay.stubTimelineServiceProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class Nip05VerificationSnapshotTest {
    private val owner = "11".repeat(32)
    private val profile = "22".repeat(32)
    private val address = "alice@example.com"

    @Test
    fun `snapshot round-trip restores a validated verified mapping`() = runTest {
        val now = System.currentTimeMillis()
        val source = store()
        val key = requireNotNull(nip05VerificationCacheKey(profile, address))
        assertTrue(
            source.storeNip05Verification(
                Nip05VerificationCacheEntry(
                    key = key,
                    status = Nip05VerificationStatus.VERIFIED,
                    checkedAtMs = now,
                    resolvedPubkey = profile.uppercase(),
                ),
                now,
            ),
        )

        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { source.saveSnapshotBinary(it) }

        val restored = store()
        DataInputStream(ByteArrayInputStream(bytes.toByteArray())).use {
            restored.restoreSnapshotBinary(it)
        }
        assertEquals(
            Nip05VerificationStatus.VERIFIED,
            restored.currentNip05Verification(key, now),
        )
    }

    @Test
    fun `unreadable cache record is dropped rather than trusted`() {
        val store = store()
        assertFalse(store.restoreNip05VerificationCacheRecord(byteArrayOf(1, 0, 0)))
        assertEquals(
            Nip05VerificationStatus.UNKNOWN,
            store.currentNip05Verification(
                requireNotNull(nip05VerificationCacheKey(profile, address)),
            ),
        )
    }

    @Test
    fun `verified record whose stored mapping disagrees is dropped`() {
        val now = System.currentTimeMillis()
        val store = store()
        val key = requireNotNull(nip05VerificationCacheKey(profile, address))
        val record = store.encodeNip05VerificationCacheRecord(
            Nip05VerificationCacheEntry(
                key = key,
                status = Nip05VerificationStatus.VERIFIED,
                checkedAtMs = now,
                resolvedPubkey = "33".repeat(32),
            ),
        )

        assertFalse(store.restoreNip05VerificationCacheRecord(record, now))
        assertEquals(Nip05VerificationStatus.UNKNOWN, store.currentNip05Verification(key, now))
    }

    @Test
    fun `new profile claim invalidates the cached claim it replaces`() {
        val now = System.currentTimeMillis()
        val store = store()
        val oldKey = requireNotNull(nip05VerificationCacheKey(profile, address))
        store.insert(profileEvent("profile-old", 1L, address))
        assertTrue(
            store.storeNip05Verification(
                Nip05VerificationCacheEntry(
                    key = oldKey,
                    status = Nip05VerificationStatus.VERIFIED,
                    checkedAtMs = now,
                    resolvedPubkey = profile,
                ),
                now,
            ),
        )

        store.insert(profileEvent("profile-new", 2L, "alice@new.example"))

        assertEquals(Nip05VerificationStatus.UNKNOWN, store.currentNip05Verification(oldKey, now))
    }

    @Test
    fun `legacy snapshot without verification cache restores cleanly`() = runTest {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { store().saveSnapshotBinary(it, snapshotVersion = 18) }

        val restored = store()
        DataInputStream(ByteArrayInputStream(bytes.toByteArray())).use {
            restored.restoreSnapshotBinary(it)
        }

        val key = requireNotNull(nip05VerificationCacheKey(profile, address))
        assertEquals(Nip05VerificationStatus.UNKNOWN, restored.currentNip05Verification(key))
    }

    private fun profileEvent(id: String, createdAt: Long, nip05: String) = NostrEvent(
        id = id,
        pubkey = profile,
        kind = 0,
        content = """{"nip05":"$nip05"}""",
        createdAt = createdAt,
        tags = emptyList(),
        tagsJson = "[]",
        sig = "sig",
        relayUrl = "wss://relay.example",
        replyToId = null,
        rootId = null,
        hasContentWarning = false,
        contentWarningReason = null,
        firstSeenAt = createdAt * 1_000,
        relaysSeen = mutableSetOf("wss://relay.example"),
    )

    private fun store() = MemoryEventStore(
        object : MuteKeyProvider {},
        stubTimelineServiceProvider(),
    ).apply {
        ownPubkey = owner
    }
}
