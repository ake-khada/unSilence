package com.unsilence.app.data.memory

import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.network.Nip05VerificationCacheEntry
import com.unsilence.app.data.network.Nip05VerificationStatus
import com.unsilence.app.data.network.nip05VerificationCacheKey
import com.unsilence.app.data.relay.stubTimelineServiceProvider
import kotlinx.coroutines.flow.first
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
                    relayHints = listOf("wss://author.example"),
                ),
                now,
            ),
        )

        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { source.copySnapshotForTest(it) }

        val restored = store()
        DataInputStream(ByteArrayInputStream(bytes.toByteArray())).use {
            restored.restoreSnapshotBinary(it)
        }
        assertEquals(listOf("wss://author.example"), restored.nip05VerificationSnapshot(now).single().relayHints)
        assertEquals(
            Nip05VerificationStatus.VERIFIED,
            restored.currentNip05Verification(key, now),
        )
    }

    @Test
    fun `legacy record retains badge and timestamp and v2 drops only invalid hints`() {
        val now = System.currentTimeMillis()
        val key = requireNotNull(nip05VerificationCacheKey(profile, address))
        val store = store()
        fun record(version: Int, vararg hints: String): ByteArray {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                fun string(value: String) {
                    val data = value.toByteArray()
                    out.writeInt(data.size)
                    out.write(data)
                }
                out.writeByte(version)
                string(profile)
                string(address)
                out.writeByte(1)
                out.writeLong(now)
                out.writeBoolean(true)
                string(profile)
                for (hint in hints) {
                    val data = hint.toByteArray()
                    out.writeShort(data.size)
                    out.write(data)
                }
            }
            return bytes.toByteArray()
        }
        assertTrue(store.restoreNip05VerificationCacheRecord(record(1), now))
        val legacy = store.nip05VerificationSnapshot(now).single()
        assertEquals(Nip05VerificationCacheEntry(key, Nip05VerificationStatus.VERIFIED, now, profile), legacy)
        val mixed = record(2, "ws://bad.example", "wss://large.example/" + "a".repeat(513), "wss://ok.example")
        assertTrue(store.restoreNip05VerificationCacheRecord(mixed, now))
        assertEquals(listOf("wss://ok.example"), store.nip05VerificationSnapshot(now).single().relayHints)
    }

    @Test
    fun `only an incomplete current profile gets unseen verified hints`() = runTest {
        val store = store()
        val key = requireNotNull(nip05VerificationCacheKey(profile, address))
        val partial = profileEvent("profile-1", 1, address)
        store.insert(partial)
        val now = System.currentTimeMillis()
        val hints = listOf("wss://relay.example", "wss://new.example")
        store.storeNip05Verification(Nip05VerificationCacheEntry(key, Nip05VerificationStatus.VERIFIED, now, profile, hints), now)
        assertEquals(listOf("wss://new.example"), store.missingPictureRelayHints(profile).first())
        store.insert(partial.copy(id = "profile-2", createdAt = 2, content = """{"nip05":"$address","picture":"https://image.example/me.jpg"}"""))
        assertTrue(store.missingPictureRelayHints(profile).first().isEmpty())
        store.insert(profileEvent("profile-3", 3, "bob@elsewhere.example"))
        assertTrue(store.missingPictureRelayHints(profile).first().isEmpty())
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
        DataOutputStream(bytes).use { store().copySnapshotForTest(it, snapshotVersion = 18) }

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
