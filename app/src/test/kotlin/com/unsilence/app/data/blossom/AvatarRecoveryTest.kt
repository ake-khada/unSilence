package com.unsilence.app.data.blossom

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.init.InitGate
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.relay.RelayPreferencesStore
import com.unsilence.app.data.relay.stubTimelineServiceProvider
import dagger.Lazy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class AvatarRecoveryTest {
    private val author = "ab".repeat(32)
    private val bytes = "synthetic avatar body".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val url = "https://original.example/$hash.gif"
    private val blob = AvatarFallbackPolicy.blob(url)!!

    @Test fun `stored declared server failure falls through to selected fallback`() = fixture { f ->
        f.store.insert(event("list", 10063, tags = listOf(listOf("server", "http://declared.onion"))))
        f.preferences.setAvatarFallbackServers(listOf("http://chosen.onion"))
        f.preferences.setAvatarFallbackEnabled(true)
        f.preferences.avatarFallbackPreferences.first { it.enabled }
        f.server.enqueue(MockResponse().setResponseCode(404))
        f.server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        assertArrayEquals(bytes, f.recovery.recover(author, url, blob)!!.bytes)
        assertEquals("http://declared.onion/$hash.gif", f.server.takeRequest().path)
        assertEquals("http://chosen.onion/$hash.gif", f.server.takeRequest().path)
        assertEquals(2, f.server.requestCount)
    }

    @Test fun `failed attempt is not repeated and a later declared list can recover`() = fixture { f ->
        f.store.insert(event("list", 10063, tags = listOf(listOf("server", "http://dead.onion"))))
        f.server.enqueue(MockResponse().setResponseCode(404))
        assertNull(f.recovery.recover(author, url, blob))
        assertNull(f.recovery.recover(author, url, blob))
        assertEquals(1, f.server.requestCount)
        f.store.insert(event("new-list", 10063, tags = listOf(listOf("server", "http://good.onion"))).copy(createdAt = 2))
        f.server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        assertArrayEquals(bytes, f.recovery.recover(author, url, blob)!!.bytes)
        assertEquals(2, f.server.requestCount)
    }

    @Test fun `default off never contacts configured public server and enabling recomputes`() = fixture { f ->
        f.preferences.setAvatarFallbackServers(listOf("http://chosen.onion"))
        f.preferences.avatarFallbackPreferences.first { it.servers == listOf("http://chosen.onion") }
        assertNull(f.recovery.recover(author, url, blob))
        assertEquals(0, f.server.requestCount)
        f.preferences.setAvatarFallbackEnabled(true)
        f.preferences.avatarFallbackPreferences.first { it.enabled }
        f.server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        assertNotNull(f.recovery.recover(author, url, blob))
        assertEquals("http://chosen.onion/$hash.gif", f.server.takeRequest().path)
    }

    @Test fun `simultaneous cards coalesce while account teardown cancels old work`() = fixture { f ->
        f.store.insert(event("list", 10063, tags = listOf(listOf("server", "http://declared.onion"))))
        f.server.enqueue(MockResponse().setBody(Buffer().write(bytes)).setBodyDelay(250, TimeUnit.MILLISECONDS))
        coroutineScope {
            val first = async { f.recovery.recover(author, url, blob) }
            val second = async { f.recovery.recover(author, url, blob) }
            assertArrayEquals(first.await()!!.bytes, second.await()!!.bytes)
        }
        assertEquals(1, f.server.requestCount)
        f.server.enqueue(MockResponse().setBody(Buffer().write(bytes)).setBodyDelay(3, TimeUnit.SECONDS))
        coroutineScope {
            val old = async { f.recovery.recover(author, url, blob) }
            withContext(Dispatchers.IO) { while (f.server.requestCount < 2) Thread.sleep(5) }
            f.gate.invalidateSession(); f.recovery.clear()
            try { old.await(); fail("old account work must cancel") } catch (_: CancellationException) { }
        }
    }

    @Test fun `disposing the first card does not cancel a visible card sharing recovery`() = fixture { f ->
        f.store.insert(event("list", 10063, tags = listOf(listOf("server", "http://declared.onion"))))
        f.server.enqueue(MockResponse().setBody(Buffer().write(bytes)).setBodyDelay(1, TimeUnit.SECONDS))
        f.server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        coroutineScope {
            val first = async(start = CoroutineStart.UNDISPATCHED) { f.recovery.recover(author, url, blob) }
            withContext(Dispatchers.IO) { assertNotNull(f.server.takeRequest(3, TimeUnit.SECONDS)) }
            // UNDISPATCHED reaches the existing deferred before cancellation, without a timing guess.
            val visible = async(start = CoroutineStart.UNDISPATCHED) { f.recovery.recover(author, url, blob) }
            first.cancelAndJoin()
            assertArrayEquals(bytes, visible.await()!!.bytes)
        }
        assertEquals(2, f.server.requestCount)
    }

    @Test fun `many broken avatar hashes back off the same unreachable host`() {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        fixture(configure = { addInterceptor {
            calls.incrementAndGet()
            throw java.net.UnknownHostException("synthetic unavailable host")
        } }) { f ->
            repeat(20) { i ->
                val differentAuthor = i.toString(16).padStart(64, 'a')
                f.store.insert(event("list-$i", 10063, tags = listOf(listOf("server", "http://dead.onion"))).copy(pubkey = differentAuthor))
                val original = "https://original.example/${i.toString(16).padStart(64, '0')}.gif"
                assertNull(f.recovery.recover(differentAuthor, original, AvatarFallbackPolicy.blob(original)!!))
            }
            assertEquals(1, calls.get())
            assertEquals(0, f.server.requestCount)
        }
    }

    @Test fun `fast failures cannot exceed six candidate attempts`() = fixture { f ->
        f.store.insert(event("list", 10063, tags = (1..5).map { listOf("server", "http://declared$it.onion") }))
        f.preferences.setAvatarFallbackServers((1..3).map { "http://selected$it.onion" })
        f.preferences.setAvatarFallbackEnabled(true)
        f.preferences.avatarFallbackPreferences.first { it.enabled }
        repeat(7) { f.server.enqueue(MockResponse().setResponseCode(404)) }
        assertNull(f.recovery.recover(author, url, blob))
        assertEquals(6, f.server.requestCount)
        assertEquals("http://declared1.onion/$hash.gif", f.server.takeRequest().path)
    }

    @Test fun `loaded media and imeta do not supply recovery hints`() = fixture { f ->
        f.store.insert(event("media", 1, "https://inferred.example/$hash.gif"))
        f.store.insert(event("profile", 0, """{"picture":"$url"}""",
            listOf(listOf("imeta", "url $url", "fallback http://explicit.onion/$hash.gif"))))
        assertNull(f.recovery.recover(author, url, blob))
        assertEquals(0, f.server.requestCount)
    }

    private fun event(id: String, kind: Int, content: String = "", tags: List<List<String>> = emptyList()) = NostrEvent(
        id, author, kind, content, 1L, tags, "", "wss://relay.example", null, null, false, null, 1L, mutableSetOf())
    private data class Fixture(val server: MockWebServer, val store: MemoryEventStore,
        val preferences: RelayPreferencesStore, val gate: InitGate, val recovery: AvatarRecovery)
    private fun fixture(configure: OkHttpClient.Builder.() -> Unit = {}, block: suspend (Fixture) -> Unit) = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val data = PreferenceDataStoreFactory.create(scope = scope, produceFile = { Files.createTempDirectory("avatar-prefs").resolve("prefs.preferences_pb").toFile() })
            val prefs = RelayPreferencesStore(data, scope)
            val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
            val gate = InitGate().apply { beginSession("test") }
            val client = OkHttpClient.Builder().proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", server.port)))
                .dns { error("No external network") }.apply(configure).build()
            val recovery = AvatarRecovery(Lazy { store }, prefs, gate, client)
            try { withTimeout(10_000) { block(Fixture(server, store, prefs, gate, recovery)) } }
            finally { recovery.clear(); scope.cancel(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
        }
    }
}
