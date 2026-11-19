package com.unsilence.app.data.drafts

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DraftStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun reply(id: String, text: String): Draft {
        val context = DraftContext.Reply(id)
        return Draft(context.key, listOf(DraftBlock.Text(text)), false, 1, context)
    }

    @Test fun saveBeforeHydrationPreservesExistingDrafts() = runTest {
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporary.root.resolve("drafts.preferences_pb")
        }
        val previous = reply("old", "Already on disk")
        val next = reply("new", "First edit after startup")
        val key = stringPreferencesKey("alice")
        data.edit { it[key] = Json.encodeToString(listOf(previous)) }
        val store = DraftStore(data, backgroundScope)
        store.save("alice", next)
        val persisted = data.data.first { it[key].orEmpty().contains(next.previewText) }
        assertEquals(setOf(previous, next), Json.decodeFromString<List<Draft>>(persisted[key]!!).toSet())
    }

    @Test fun failedWriteNeverAppearsInSavedDrafts() = runTest {
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporary.root.resolve("drafts.preferences_pb")
        }
        val attempted = CompletableDeferred<Unit>()
        val unavailable = object : DataStore<Preferences> by data {
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                attempted.complete(Unit)
                throw IOException("Storage unavailable")
            }
        }
        val store = DraftStore(unavailable, backgroundScope)
        store.awaitLoaded()
        val draft = reply("id", "Recoverable text")
        val write = store.save("alice", draft)
        attempted.await()
        assertTrue(write.await().exceptionOrNull() is IOException)
        assertNull(store.draft("alice", draft.key))
    }

    @Test fun queuedSaveDeleteAndReplacementAreDurableInOrderAndIsolatedByAccount() = runTest {
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporary.root.resolve("drafts.preferences_pb")
        }
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val delayed = object : DataStore<Preferences> by data {
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                started.complete(Unit)
                release.await()
                return data.updateData(transform)
            }
        }
        val store = DraftStore(delayed, backgroundScope)
        val first = reply("reply", "first version")
        val last = reply("reply", "last version")
        val other = reply("reply", "other account")
        val save = store.save("alice", first)
        started.await()
        val deletion = store.delete("alice", first.key)
        val separate = store.save("bob", other)
        val replacement = store.save("alice", last)
        assertFalse(save.isCompleted)
        assertNull(store.draft("alice", first.key))
        release.complete(Unit)
        listOf(save, deletion, separate, replacement).forEach { it.await().getOrThrow() }
        val reopened = DraftStore(data, backgroundScope)
        reopened.awaitLoaded()
        assertEquals(listOf(last), reopened.draftsFor("alice"))
        assertEquals(listOf(other), reopened.draftsFor("bob"))
    }

    @Test fun failedReplacementKeepsPreviousVersionAndCanBeRetried() = runTest {
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporary.root.resolve("drafts.preferences_pb")
        }
        var reject = false
        val flaky = object : DataStore<Preferences> by data {
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                if (reject) throw IOException("Disk full")
                return data.updateData(transform)
            }
        }
        val store = DraftStore(flaky, backgroundScope)
        val first = reply("id", "persisted")
        val second = reply("id", "updated")
        store.save("alice", first).await()
        reject = true
        assertTrue(store.save("alice", second).await().exceptionOrNull() is IOException)
        assertEquals(first, store.draft("alice", first.key))
        reject = false
        store.save("alice", second).await()
        assertEquals(second, store.draft("alice", first.key))
        store.delete("alice", second.key).await()
        assertNull(data.data.first()[stringPreferencesKey("alice")])
    }
}
