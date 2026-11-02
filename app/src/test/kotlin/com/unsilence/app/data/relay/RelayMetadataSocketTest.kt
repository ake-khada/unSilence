package com.unsilence.app.data.relay

import android.content.ContentResolver
import android.content.ContextWrapper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.auth.SignatureVerifier
import com.unsilence.app.data.auth.SigningManager
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.zap.ZapCryptoTestFixture
import dagger.Lazy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Startup refresh uses the real pooled listener/processor; all HTTP stays on loopback. */
class RelayMetadataSocketTest {
    @Test fun `pooled indexer delivers old published list without since and completes on real EOSE`() = runBlocking { lookup(false) }
    @Test fun `CLOSED cleans up metadata query without certifying successful freshness`() = runBlocking { lookup(true) }

    private suspend fun lookup(reject: Boolean) = coroutineScope {
        val crypto = ZapCryptoTestFixture()
        val event = crypto.signedEvent(crypto.sender, 10002, listOf(listOf("r", "wss://author.test", "write")), "", createdAt = 100)
        val url = "wss://indexer.test"
        val received = CopyOnWriteArrayList<String>()
        val closedQuery = CompletableDeferred<Unit>()
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                received += text
                val arr = NostrJson.parseToJsonElement(text).jsonArray
                if (arr[0].jsonPrimitive.content == "REQ") {
                    val sub = arr[1].jsonPrimitive.content
                    if (reject) webSocket.send("""["CLOSED","$sub","blocked: fixture"]""")
                    else {
                        webSocket.send("""["EVENT","$sub",${NostrJson.encodeToString(event)}]""")
                        webSocket.send("""["EOSE","$sub"]""")
                    }
                } else if (arr[0].jsonPrimitive.content == "CLOSE") closedQuery.complete(Unit)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        }))
        server.start()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            check(chain.request().url.host == "indexer.test") { "Unexpected network destination" }
            chain.proceed(chain.request().newBuilder().url(server.url("/")).build())
        }.build()
        val context = object : ContextWrapper(null) {}
        val monitor = NetworkMonitor(context)
        val capabilities = RelayCapabilitiesStore(context, monitor)
        val keys = KeyManager(context)
        val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
        val processor = EventProcessor(store, SignatureVerifier())
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        processor.setTestScope(testScope)
        val preferences = RelayPreferencesStore(object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                transform(data.value).also { data.value = it }
        }, testScope)
        preferences.setIndexerUrls(listOf(url))
        val pool = RelayPool(
            RelayConnectionFactory(client, capabilities, RelayTrafficMetrics()), processor,
            Lazy { preferences }, SigningManager(keys, object : ContentResolver(context) {}), keys,
            Lazy { store }, capabilities,
            Lazy { object : ActiveSubsSource {
                override fun activeRelayUrls() = emptySet<String>()
                override fun activeSubIds() = emptySet<String>()
            } }, monitor, Nip11Fetcher(client, Lazy { store }),
        )
        try {
            pool.connect(listOf(url))
            withTimeout(5_000) { pool.connectionStates.first { it[url] == RelayState.CONNECTED } }
            pool.fetchRelayLists(listOf(crypto.sender.pubkey))
            val job = RelayPool::class.java.getDeclaredField("relayListRefreshJob").apply { isAccessible = true }.get(pool) as Job
            withTimeout(5_000) { job.join(); closedQuery.await() }
            val requests = received.filter { it.startsWith("[\"REQ\"") }.map { NostrJson.parseToJsonElement(it).jsonArray }
            assertEquals(1, requests.size)
            assertTrue(requests.single()[1].jsonPrimitive.content.startsWith("kind10002-"))
            val filter = requests.single()[2].jsonObject
            assertEquals(setOf("kinds", "authors"), filter.keys)
            assertEquals(listOf("10002"), filter.getValue("kinds").jsonArray.map { it.jsonPrimitive.content })
            assertEquals(listOf(crypto.sender.pubkey), filter.getValue("authors").jsonArray.map { it.jsonPrimitive.content })
            processor.drainForTest()
            assertEquals(if (reject) emptyList<String>() else listOf("wss://author.test"), store.writeRelaysFor(crypto.sender.pubkey))
            // Inspect bookkeeping's success evidence, not just the waiter being released.
            val refresh = RelayPool::class.java.getDeclaredField("relayListRefresh").apply { isAccessible = true }.get(pool)
            val checks = refresh.javaClass.getDeclaredField("checks").apply { isAccessible = true }.get(refresh) as Map<*, *>
            val check = checks[crypto.sender.pubkey]!!
            val checkedAt = check.javaClass.getDeclaredField("checkedAt").apply { isAccessible = true }.get(check)
            assertEquals(!reject, checkedAt != null)
            assertEquals(1, server.requestCount)
            assertEquals(RelayState.CONNECTED, pool.connectionStates.first()[url])
        } finally {
            pool.disconnectAll()
            for ((owner, name) in listOf(pool to "scope", capabilities to "persistenceScope")) {
                (owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as CoroutineScope).cancel()
            }
            processor.stop(); testScope.cancel()
            client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll(); server.shutdown()
        }
    }
}
