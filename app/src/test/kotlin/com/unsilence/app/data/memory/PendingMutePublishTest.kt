package com.unsilence.app.data.memory

import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.relay.stubTimelineServiceProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingMutePublishTest {

    @Test
    fun `pending intent overlays a late authoritative relay list`() {
        val store = store()
        store.ownPubkey = OWN
        store.recordPendingMuteMutation(MuteMutation(MuteMutationKind.User, "local", true))

        store.insert(event("relay-base", 100L, tags = listOf(listOf("p", "public"))))

        val list = requireNotNull(store.getMuteList(OWN))
        assertEquals(setOf("public"), list.pubkeys)
        assertEquals(setOf("local"), list.privatePubkeys)
        assertEquals(1L, store.getPendingMutePublish(OWN)?.revision)
    }

    @Test
    fun `pending journal survives binary snapshot and reapplies all mutation types`() = runTest {
        val source = store()
        source.ownPubkey = OWN
        source.insert(
            event(
                id = "base",
                createdAt = 100L,
                tags = listOf(
                    listOf("p", "remove-user"),
                    listOf("word", "remove-word"),
                    listOf("t", "remove-tag"),
                ),
            ),
        )
        source.recordPendingMuteMutation(MuteMutation(MuteMutationKind.User, "remove-user", false))
        source.recordPendingMuteMutation(MuteMutation(MuteMutationKind.Word, "remove-word", false))
        source.recordPendingMuteMutation(MuteMutation(MuteMutationKind.Hashtag, "remove-tag", false))
        source.recordPendingMuteMutation(MuteMutation(MuteMutationKind.User, "new-user", true))
        source.recordPendingMuteMutation(MuteMutation(MuteMutationKind.Word, "NEW-WORD", true))
        source.recordPendingMuteMutation(MuteMutation(MuteMutationKind.Hashtag, "NEW-TAG", true))

        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { source.saveSnapshotBinary(it) }
        val restored = store().apply { ownPubkey = OWN }
        DataInputStream(ByteArrayInputStream(bytes.toByteArray())).use {
            restored.restoreSnapshotBinary(it)
        }

        val pending = requireNotNull(restored.getPendingMutePublish(OWN))
        assertEquals(6L, pending.revision)
        val list = requireNotNull(restored.getMuteList(OWN))
        assertTrue(list.pubkeys.isEmpty())
        assertTrue(list.words.isEmpty())
        assertTrue(list.hashtags.isEmpty())
        assertEquals(setOf("new-user"), list.privatePubkeys)
        assertEquals(setOf("new-word"), list.privateWords)
        assertEquals(setOf("new-tag"), list.privateHashtags)
    }

    @Test
    fun `legacy V17 snapshot restores without a pending journal`() = runTest {
        val source = store()
        source.ownPubkey = OWN
        source.insert(event("base", 100L, tags = listOf(listOf("p", "public"))))
        source.recordPendingMuteMutation(MuteMutation(MuteMutationKind.User, "local", true))

        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { source.saveSnapshotBinary(it, snapshotVersion = 17) }
        val restored = store().apply { ownPubkey = OWN }
        DataInputStream(ByteArrayInputStream(bytes.toByteArray())).use {
            restored.restoreSnapshotBinary(it)
        }

        assertNull(restored.getPendingMutePublish(OWN))
        assertEquals(setOf("public"), restored.getMuteList(OWN)?.pubkeys)
        assertTrue(restored.getMuteList(OWN)?.privatePubkeys.orEmpty().isEmpty())
    }

    @Test
    fun `relay acceptance clears only the matching journal revision`() {
        val store = store()
        store.ownPubkey = OWN
        store.insert(event("base", 100L))
        store.recordPendingMuteMutation(MuteMutation(MuteMutationKind.User, "alice", true))
        val captured = requireNotNull(store.getMutePublishSnapshot(OWN))
        assertTrue(store.beginMutePublish(captured))

        // A newer local edit lands while the first event is awaiting relay OK.
        store.recordPendingMuteMutation(MuteMutation(MuteMutationKind.User, "bob", true))
        assertFalse(store.commitAcceptedMutePublish(captured, event("signed-old", 101L)))
        assertEquals(2L, store.getPendingMutePublish(OWN)?.revision)

        val current = requireNotNull(store.getMutePublishSnapshot(OWN))
        assertTrue(store.beginMutePublish(current))
        assertTrue(store.commitAcceptedMutePublish(current, event("signed-current", 102L)))
        assertNull(store.getPendingMutePublish(OWN))
        assertEquals("signed-current", store.getLatestMuteListEvent(OWN)?.id)
    }

    @Test
    fun `remote list change during signing invalidates captured publish snapshot`() {
        val store = store()
        store.ownPubkey = OWN
        store.insert(event("base", 100L))
        store.recordPendingMuteMutation(MuteMutation(MuteMutationKind.User, "alice", true))
        val captured = requireNotNull(store.getMutePublishSnapshot(OWN))

        store.insert(event("newer", 101L, tags = listOf(listOf("p", "remote"))))

        assertFalse(store.beginMutePublish(captured))
        val list = requireNotNull(store.getMuteList(OWN))
        assertEquals(setOf("remote"), list.pubkeys)
        assertEquals(setOf("alice"), list.privatePubkeys)
    }

    @Test
    fun `remote list change while awaiting OK keeps journal for merged retry`() {
        val store = store()
        store.ownPubkey = OWN
        store.insert(event("base", 100L, tags = listOf(listOf("p", "base-user"))))
        store.recordPendingMuteMutation(MuteMutation(MuteMutationKind.User, "local", true))
        val captured = requireNotNull(store.getMutePublishSnapshot(OWN))
        assertTrue(store.beginMutePublish(captured))

        store.insert(event("remote", 101L, tags = listOf(listOf("p", "remote-user"))))

        val merged = requireNotNull(store.getMuteList(OWN))
        assertEquals(setOf("remote-user"), merged.pubkeys)
        assertEquals(setOf("local"), merged.privatePubkeys)
        assertFalse(store.commitAcceptedMutePublish(captured, event("stale-signed", 102L)))
        assertEquals("remote", store.getLatestMuteListEvent(OWN)?.id)
        assertEquals(1L, store.getPendingMutePublish(OWN)?.revision)
    }

    @Test
    fun `multiple self echoes at an equal timestamp cannot revert newer local state`() {
        val store = store()
        store.ownPubkey = OWN
        store.insert(event("base", 100L))
        store.recordPendingMuteMutation(MuteMutation(MuteMutationKind.User, "alice", true))
        store.recordPendingMuteMutation(MuteMutation(MuteMutationKind.User, "bob", true))
        store.isSelfPublishedCheck = { it == "first-self" || it == "second-self" }

        store.insert(event("first-self", 101L, tags = listOf(listOf("p", "stale"))))
        store.insert(event("second-self", 101L, tags = listOf(listOf("p", "also-stale"))))

        val list = requireNotNull(store.getMuteList(OWN))
        assertEquals(setOf("alice", "bob"), list.privatePubkeys)
        assertTrue(list.pubkeys.isEmpty())
        assertEquals("base", store.getLatestMuteListEvent(OWN)?.id)
    }

    @Test
    fun `latest empty content does not resurrect older encrypted content`() {
        val store = store()
        store.ownPubkey = OWN
        store.insert(event("old-private", 100L, content = "old-encrypted"))
        store.updateMuteListPrivateTags(
            pubkey = OWN,
            privatePubkeys = setOf("old-user"),
            privateHashtags = setOf("old-tag"),
            privateWords = setOf("old-word"),
            privateEventIds = setOf("old-event"),
        )
        store.insert(event("new-empty", 101L, content = ""))

        assertEquals("new-empty", store.getLatestMuteListEvent(OWN)?.id)
        assertEquals("", store.getMuteListContent(OWN))
        val list = requireNotNull(store.getMuteList(OWN))
        assertTrue(list.privatePubkeys.isEmpty())
        assertTrue(list.privateHashtags.isEmpty())
        assertTrue(list.privateWords.isEmpty())
        assertTrue(list.privateEventIds.isEmpty())
    }

    @Test
    fun `restore merge keeps in-process edits newer than disk edits`() {
        val restored = PendingMutePublish(
            ownerPubkey = OWN,
            revision = 3L,
            userChanges = linkedMapOf("same" to true, "disk" to true),
        )
        val live = PendingMutePublish(
            ownerPubkey = OWN,
            revision = 1L,
            userChanges = linkedMapOf("same" to false, "live" to true),
        )

        val merged = mergePendingMutePublishes(restored, live)

        assertEquals(4L, merged.revision)
        assertEquals(
            linkedMapOf("disk" to true, "same" to false, "live" to true),
            merged.userChanges,
        )
    }

    @Test
    fun `encrypted journal codec round trips owner revision and ordered changes`() {
        val pending = PendingMutePublish(
            ownerPubkey = OWN,
            revision = 7L,
            userChanges = linkedMapOf("alice" to true, "bob" to false),
            wordChanges = linkedMapOf("noise" to true),
            hashtagChanges = linkedMapOf("spam" to false),
        )

        val encoded = requireNotNull(PendingMuteJournalCodec.encode(pending))

        assertEquals(pending, PendingMuteJournalCodec.decode(encoded, OWN))
        assertNull(PendingMuteJournalCodec.decode(encoded, "another-owner"))
        assertNull(PendingMuteJournalCodec.decode("not-json", OWN))
    }

    @Test
    fun `restoring the same journal twice is idempotent`() {
        val pending = PendingMutePublish(
            ownerPubkey = OWN,
            revision = 2L,
            userChanges = linkedMapOf("alice" to true),
        )

        assertEquals(pending, mergePendingMutePublishes(pending, pending))
    }

    private fun store() = MemoryEventStore(
        object : MuteKeyProvider {},
        stubTimelineServiceProvider(),
    )

    private fun event(
        id: String,
        createdAt: Long,
        tags: List<List<String>> = emptyList(),
        content: String = "",
    ) = NostrEvent(
        id = id,
        pubkey = OWN,
        kind = 10000,
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
        firstSeenAt = createdAt * 1_000L,
        relaysSeen = mutableSetOf("wss://relay.example"),
    )

    private companion object {
        const val OWN = "owner"
    }
}
