package com.unsilence.app.data.memory

import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.relay.NostrJson
import com.unsilence.app.data.relay.stubTimelineServiceProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class NostrEventTagStorageTest {

    @Test
    fun `v19 snapshot preserves structured tags and stays byte stable after restore`() = runTest {
        val tags = listOf(
            emptyList(),
            listOf("p", "duplicate"),
            listOf("p", "duplicate"),
            listOf("custom", "quote: \"", "line one\nline two", "unicode: Ж 🦋"),
        )
        val source = newStore().apply {
            ownPubkey = "owner"
            insert(event(tags))
        }

        val firstBytes = save(source)
        val restored = newStore().apply { ownPubkey = "owner" }
        DataInputStream(ByteArrayInputStream(firstBytes)).use { restored.restoreSnapshotBinary(it) }

        assertEquals(tags, restored.getNostrEvent("event-id")?.tags)
        assertArrayEquals(
            "V19 layout and redundant serialized-tags slot must remain stable",
            firstBytes,
            save(restored),
        )
    }

    @Test
    fun `tags serializer round trips empty and escaped values exactly`() {
        val fixtures = listOf(
            emptyList(),
            listOf(emptyList()),
            listOf(listOf("x", "embedded \"quote\"", "back\\slash", "new\nline", "Ж 🦋")),
        )

        fixtures.forEach { tags ->
            val decoded = NostrJson.parseToJsonElement(tagsToJson(tags)).jsonArray.map { row ->
                row.jsonArray.map { it.jsonPrimitive.content }
            }
            assertEquals(tags, decoded)
        }
    }

    private suspend fun save(store: MemoryEventStore): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { store.saveSnapshotBinary(it) }
        return bytes.toByteArray()
    }

    private fun newStore() =
        MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())

    private fun event(tags: List<List<String>>) = NostrEvent(
        id = "event-id",
        pubkey = "owner",
        kind = 1,
        content = "body",
        createdAt = 1_700_000_000L,
        tags = tags,
        sig = "signature",
        relayUrl = "wss://relay.example",
        replyToId = null,
        rootId = null,
        hasContentWarning = false,
        contentWarningReason = null,
        firstSeenAt = 1_700_000_000_000L,
        relaysSeen = ConcurrentHashMap.newKeySet<String>().apply {
            add("wss://relay.example")
        },
    )
}
