package com.unsilence.app.data.drafts

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import java.io.IOException
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DraftMediaGrantTest {
    @get:Rule val temporary = TemporaryFolder()

    private class Grants : DraftMediaGrants {
        val retained = mutableSetOf<String>()
        var deny: String? = null
        override fun held() = retained.toSet()
        override fun take(uri: String) {
            if (uri == deny) throw SecurityException()
            retained.add(uri)
        }
        override fun release(uri: String) { check(retained.remove(uri)) }
    }

    private fun TestScope.data() = PreferenceDataStoreFactory.create(scope = backgroundScope) {
        temporary.root.resolve("drafts.preferences_pb")
    }
    private fun media(id: String) = DraftBlock.LocalMedia(id, "content://picker/$id", id, 14, "HIGH")
    private fun reply(key: String, vararg blocks: DraftBlock) =
        Draft(key, blocks.toList(), false, 1, DraftContext.Reply(key))

    @Test fun restoredMixedReplyKeepsOrderCursorFailureAndUploadedBlob() = runTest {
        val data = data()
        val grants = Grants()
        val pending = media("photo")
        val failed = media("video").copy(failure = "Server unavailable")
        val uploaded = DraftBlock.Attachment("https://example.org/photo", "sha", 15, "image/png")
        val draft = reply("parent", pending, DraftBlock.Text("caption"), failed, uploaded, DraftBlock.Text(""))
            .copy(cursor = DraftCursor(1, 0, 0))
        DraftStore(data, backgroundScope, grants).save("alice", draft).await().getOrThrow()
        val reopened = DraftStore(data, backgroundScope, grants)
        reopened.awaitLoaded()
        assertEquals(draft, reopened.draft("alice", "parent"))
        assertEquals(setOf(pending.uri, failed.uri), grants.held())
        assertTrue(reopened.draftsFor("bob").isEmpty())
    }

    @Test fun olderReleaseCanReadAndEditAnotherDraftWithoutDroppingMedia() = runTest {
        val disk = data()
        val grants = Grants()
        val store = DraftStore(disk, backgroundScope, grants)
        val file = media("picture")
        val original = reply("parent", DraftBlock.Text("before"), file, DraftBlock.Text("after"))
        store.save("alice", original).await().getOrThrow()
        val key = stringPreferencesKey("alice")
        disk.edit { prefs ->
            val legacy = Json.decodeFromString<List<Draft>>(prefs[key]!!).single()
            assertTrue(legacy.hadUnsavedMedia)
            assertEquals(listOf(DraftBlock.Text("before"), DraftBlock.Text("after")), legacy.blocks)
            prefs[key] = Json.encodeToString(listOf(legacy, reply("new", DraftBlock.Text("older build"))))
        }
        val reopened = DraftStore(disk, backgroundScope, grants)
        reopened.awaitLoaded()
        assertEquals(original, reopened.draft("alice", original.key))
        assertEquals("older build", reopened.draft("alice", "new")?.previewText)
        disk.edit { prefs ->
            val legacy = Json.decodeFromString<List<Draft>>(prefs[key]!!)
            prefs[key] = Json.encodeToString(legacy.map { if (it.key == "parent") it.copy(updatedAt = 2) else it })
        }
        val rewritten = DraftStore(disk, backgroundScope, grants)
        rewritten.awaitLoaded()
        assertTrue(grants.held().isEmpty())
        assertEquals(0, rewritten.draft("alice", "parent")?.attachmentCount)
    }

    @Test fun sharedGrantIsReleasedOnlyWhenLastAccountRemovesIt() = runTest {
        val grants = Grants()
        val store = DraftStore(data(), backgroundScope, grants)
        val file = media("a")
        val shared = reply("one", file)
        for (owner in listOf("alice", "bob")) store.save(owner, shared).await().getOrThrow()
        store.save("alice", reply("two", file)).await().getOrThrow()
        store.delete("alice", "one").await().getOrThrow()
        store.save("bob", reply("one", DraftBlock.Text("no photo"))).await().getOrThrow()
        assertEquals(setOf(file.uri), grants.held())
        store.delete("alice", "two").await().getOrThrow()
        assertTrue(grants.held().isEmpty())
    }

    @Test fun partialGrantFailureLeavesPreviousDraftAndCleansOnlyNewGrants() = runTest {
        val grants = Grants()
        val foreign = media("other-feature").uri
        grants.retained.add(foreign)
        val store = DraftStore(data(), backgroundScope, grants)
        val original = reply("parent", media("old"))
        store.save("alice", original).await().getOrThrow()
        grants.deny = media("denied").uri
        val result = store.save("alice", reply("parent", media("taken"), media("denied"))).await()
        assertTrue(result.exceptionOrNull() is DraftMediaAccessException)
        assertEquals(original, store.draft("alice", "parent"))
        assertEquals(setOf(media("old").uri, foreign), grants.held())
    }

    @Test fun diskFailureAfterGrantAcquisitionRollsBackAndRetryWorks() = runTest {
        val disk = data()
        val grants = Grants()
        var reject = true
        val flaky = object : DataStore<Preferences> by disk {
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                if (grants.held().isNotEmpty() && reject) {
                    reject = false
                    throw IOException("Interrupted write")
                }
                return disk.updateData(transform)
            }
        }
        val store = DraftStore(flaky, backgroundScope, grants)
        val draft = reply("parent", media("pending"))
        assertTrue(store.save("alice", draft).await().isFailure)
        assertTrue(grants.held().isEmpty())
        assertNull(store.draft("alice", "parent"))
        store.save("alice", draft).await().getOrThrow()
        assertEquals(draft, store.draft("alice", "parent"))
    }

    @Test fun startupReclaimsOrphansAndCapEvictionReleasesLastSource() = runTest {
        val disk = data()
        val grants = Grants()
        val file = media("old")
        disk.edit { it[stringSetPreferencesKey("draft_media_read_grants")] = setOf(file.uri) }
        grants.retained.add(file.uri)
        val store = DraftStore(disk, backgroundScope, grants)
        store.awaitLoaded()
        assertTrue(grants.held().isEmpty())
        store.save("alice", reply("old", file)).await().getOrThrow()
        repeat(MAX_DRAFTS_PER_PUBKEY) { index ->
            store.save("alice", reply("new-$index", DraftBlock.Text("new")).copy(updatedAt = 2)).await().getOrThrow()
        }
        assertNull(store.draft("alice", "old"))
        assertTrue(grants.held().isEmpty())
    }

    @Test fun unavailableSourceCanBeRemovedOrKeptWhileEditingOtherBlocks() = runTest {
        val grants = Grants()
        val store = DraftStore(data(), backgroundScope, grants)
        val file = media("picture")
        store.save("alice", reply("one", file)).await().getOrThrow()
        grants.retained.clear()
        grants.deny = file.uri
        val updated = reply("one", file.copy(failure = "Unavailable"), DraftBlock.Text("caption"))
        store.save("alice", updated).await().getOrThrow()
        assertEquals(updated, store.draft("alice", "one"))
        assertTrue(store.save("bob", updated).await().isFailure)
    }

    @Test fun limitRejectsNewGrantsBeforeTakingAnyAndAllowsRemovingSources() = runTest {
        val grants = Grants()
        val store = DraftStore(data(), backgroundScope, grants)
        val files = (1..MAX_DRAFT_MEDIA_URIS).map { media(it.toString()) }
        val first = reply("one", *files.toTypedArray())
        store.save("alice", first).await().getOrThrow()
        assertTrue(store.save("alice", reply("two", media("new"))).await().exceptionOrNull() is DraftMediaLimitException)
        assertEquals(files.size, grants.held().size)
        store.delete("alice", "one").await().getOrThrow()
        store.save("alice", reply("two", media("new"))).await().getOrThrow()
    }
}
