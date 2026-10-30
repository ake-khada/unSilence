package com.unsilence.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Data
import androidx.work.ForegroundUpdater
import androidx.work.ProgressUpdater
import androidx.work.WorkerParameters
import androidx.work.impl.utils.taskexecutor.TaskExecutor
import androidx.work.impl.utils.taskexecutor.SerialExecutor
import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.SensitiveContentMode
import com.unsilence.app.data.relay.EventDto
import com.unsilence.app.data.relay.NostrJson
import com.unsilence.app.data.relay.TimelineEventLoader
import com.unsilence.app.data.relay.TimelineEventResolution
import com.unsilence.app.data.relay.TimelineService
import com.unsilence.app.data.relay.toNostrEvent
import com.unsilence.app.data.wallet.NwcResponseExpectation
import com.unsilence.app.data.wallet.authenticateNwcResponse
import com.unsilence.app.domain.model.FeedFilter
import com.unsilence.app.domain.model.ShowType
import com.unsilence.app.ui.feed.SavedFeedSession
import com.unsilence.app.ui.feed.SavedFeedSource
import com.unsilence.app.ui.feed.decodeFeedSession
import com.unsilence.app.ui.feed.encodeFeedSession
import com.unsilence.app.ui.navigation.AppDestination
import com.unsilence.app.work.BackgroundSyncWorker
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import java.io.DataInputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.Executor
import javax.inject.Provider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Offline fixtures only. No Activity, account, worker execution, signer IPC or network. */
@RunWith(AndroidJUnit4::class)
class ReleasePersistenceTest {
    private val app get() = ApplicationProvider.getApplicationContext<UnsilenceApp>()
    private fun signer() = NostrSignerInternal(KeyPair(privKey = "11".repeat(32).hexToByteArray()))

    @Test fun signedNwcResponseUsesAppJsonAndVerification() = runBlocking {
        val requestId = "ab".repeat(32)
        val signed = signer().sign(EventTemplate<Event>(
            createdAt = 1_675_000_000L, kind = 23195,
            tags = arrayOf(arrayOf("e", requestId)), content = "offline ciphertext fixture",
        ))
        val parsed = NostrJson.parseToJsonElement(signed.toJson()).jsonObject
        val result = authenticateNwcResponse(
            parsed, NwcResponseExpectation(signed.pubKey, requestId, "fixture", "get_balance"),
            decrypt = {
                assertEquals("offline ciphertext fixture", it)
                """{"result_type":"get_balance","id":"fixture","result":{"balance":1234}}"""
            },
            onCorrelatedRejection = { fail("Rejected offline signed fixture: $it") },
        )
        assertNotNull(result)
        assertEquals("1234", result!!.payload["result"]!!.jsonObject["balance"].toString())
    }

    @Test fun snapshotRoundTripPreservesSignedFixture() = runBlocking {
        val signed = signer().sign(EventTemplate<Event>(
            createdAt = 1_675_000_000L, kind = 1, tags = emptyArray(), content = "offline snapshot fixture",
        ))
        val event = NostrJson.decodeFromString<EventDto>(signed.toJson()).toNostrEvent("wss://relay.example")
        fun store(): MemoryEventStore {
            val timeline = TimelineService(app.subscription, object : TimelineEventLoader {
                override suspend fun getEvents(ids: List<String>) =
                    TimelineEventResolution(emptyList(), ids)
            })
            return MemoryEventStore(object : MuteKeyProvider {}, Provider { timeline })
                .apply { ownPubkey = signed.pubKey }
        }
        val source = store()
        assertTrue(source.insert(event))
        val file = File.createTempFile("snapshot-fixture-", ".bin", app.cacheDir)
        try {
            file.outputStream().use { source.saveSnapshotBinary(it) }
            val restored = store()
            DataInputStream(file.inputStream()).use { restored.restoreSnapshotBinary(it) }
            val actual = restored.getNostrEvent(event.id)
            assertNotNull(actual)
            assertEquals(event.content, actual!!.content)
            assertEquals(event.sig, actual.sig)
            assertEquals(event.tags, actual.tags)
        } finally {
            file.delete()
        }
    }

    @Test fun persistedEnumsFeedStateAndNavigationDestinationsRoundTrip() {
        assertEquals(listOf("HIDE", "BLUR", "SHOW"), SensitiveContentMode.entries.map { it.name })
        SensitiveContentMode.entries.forEach { assertEquals(it, SensitiveContentMode.valueOf(it.name)) }
        assertEquals(listOf("ALL", "TEXT", "IMAGES", "VIDEO", "ARTICLES"), ShowType.entries.map { it.name })
        val state = SavedFeedSession(
            owner = "fixture-owner", source = SavedFeedSource("following", "", ""),
            filter = FeedFilter(setOf(ShowType.TEXT), null, 0, 0, 0, 0L),
            contentFilter = 1, automaticTrustedGlobal = false, isAtTop = true,
            eventIds = listOf("fixture-event"), pagingPages = 0, pagingThrough = null,
        )
        assertEquals(state, decodeFeedSession(encodeFeedSession(state), "fixture-owner"))
        assertNull(decodeFeedSession(encodeFeedSession(state), "other-owner"))
        val destinations: List<AppDestination> = listOf(
            AppDestination.Tabs, AppDestination.Thread("fixture-event", listOf("wss://relay.example"), false),
            AppDestination.Profile("11".repeat(32)), AppDestination.Search("fixture"),
        )
        assertEquals(destinations, Json.decodeFromString<List<AppDestination>>(Json.encodeToString(destinations)))
    }

    @Test fun hiltWorkerFactoryConstructsWithoutRunningWork() {
        val executor = Executor { it.run() }
        val serialExecutor = object : SerialExecutor {
            override fun execute(command: Runnable) = executor.execute(command)
            override fun hasPendingTasks() = false
        }
        val taskExecutor = object : TaskExecutor {
            override fun getMainThreadExecutor(): Executor = executor
            override fun getSerialTaskExecutor(): SerialExecutor = serialExecutor
        }
        val params = WorkerParameters(
            UUID.randomUUID(), Data.EMPTY, emptyList(), WorkerParameters.RuntimeExtras(), 0, 0,
            executor, taskExecutor, app.workerFactory,
            ProgressUpdater { _, _, _ -> error("Fixture must not execute work") },
            ForegroundUpdater { _, _, _ -> error("Fixture must not execute work") },
        )
        val worker = app.workerFactory.createWorker(app, BackgroundSyncWorker::class.java.name, params)
        assertTrue(worker is BackgroundSyncWorker)
    }
}
