package com.unsilence.app.data.relay

import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.memory.MemoryEventStore
import dagger.Lazy
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class Nip11FetcherTest {

    @Test
    fun `device nip11 parser accepts web icon and core identity`() {
        val doc = parseDeviceNip11(
            """{"name":"Primus","icon":"https://i.nostr.build/primus.png","supported_nips":[1,11]}""",
        )

        assertEquals("Primus", doc?.name)
        assertEquals("https://i.nostr.build/primus.png", doc?.icon)
        assertEquals(setOf(1, 11), doc?.supportedNips)
    }

    @Test
    fun `device nip11 parser rejects local icon schemes and malformed documents`() {
        assertNull(parseDeviceNip11("not-json"))
        assertNull(parseDeviceNip11("[]"))
        assertNull(parseDeviceNip11("""{"icon":"file:///data/user/0/private.png"}""")?.icon)
    }

    @Test
    fun `successful fetch persists identity and cache can repopulate MES without network`() = runTest {
        val requestCount = AtomicInteger()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestCount.incrementAndGet()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        """{"name":"Primus","icon":"https://i.nostr.build/primus.png"}"""
                            .toResponseBody("application/nostr+json".toMediaType()),
                    )
                    .build()
            }
            .build()
        val store = MemoryEventStore(
            object : MuteKeyProvider {},
            stubTimelineServiceProvider(),
        )
        val fetcher = Nip11Fetcher(client, Lazy { store })

        fetcher.fetch("wss://primus.nostr1.com/")
        assertEquals("Primus", store.getRelayIdentity("wss://primus.nostr1.com")?.name)
        assertEquals(1, requestCount.get())

        store.clear()
        assertNull(store.getRelayIdentity("wss://primus.nostr1.com"))
        fetcher.fetch("wss://primus.nostr1.com")

        assertEquals(
            "https://i.nostr.build/primus.png",
            store.getRelayIdentity("wss://primus.nostr1.com")?.iconUrl,
        )
        assertEquals(1, requestCount.get())
    }
}
