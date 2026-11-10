package com.unsilence.app.data.memory

import app.cash.turbine.test
import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.relay.EventDto
import com.unsilence.app.data.relay.stubTimelineServiceProvider
import com.unsilence.app.data.relay.toNostrEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
class UserEntityFlowTest {
    private fun store() = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
    private fun profile(pubkey: String, name: String, time: Long = 1L) =
        EventDto("$pubkey-$time", pubkey, 0, """{"name":"$name"}""", time, emptyList(), "sig").toNostrEvent("wss://fixture.example")

    @Test fun `unrelated kind zero update does not emit unchanged card profile`() = runBlocking {
        val store = store()
        store.insert(profile("visible", "Alice"))
        store.userEntityFlow("visible").test {
            assertEquals("Alice", awaitItem()!!.name)
            store.insert(profile("other", "Bob"))
            // A real dispatcher, with no virtual-time extrapolation or latency claim.
            delay(100)
            expectNoEvents()
            store.insert(profile("visible", "Alice updated", 2))
            assertEquals("Alice updated", awaitItem()!!.name)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `profile field lookup and construction run off collector dispatcher`() = runBlocking {
        val store = store()
        store.insert(profile("visible", "Alice"))
        store.getUserEntity("visible") // populate the existing parsed-field cache
        val field = MemoryEventStore::class.java.getDeclaredField("profileFieldsCache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val cache = field.get(store) as ConcurrentHashMap<String, Map<String, String?>>
        val original = cache.getValue("visible")
        val lookupThread = AtomicReference<String>()
        cache["visible"] = object : Map<String, String?> by original {
            override fun get(key: String): String? {
                lookupThread.set(Thread.currentThread().name)
                return original[key]
            }
        }
        newSingleThreadContext("profile-main-collector").use { collector ->
            withContext(collector) { assertEquals("Alice", store.userEntityFlow("visible").first()!!.name) }
        }
        assertNotNull(lookupThread.get())
        assertFalse("Lookup/construction ran on the caller: ${lookupThread.get()}", lookupThread.get().contains("profile-main-collector"))
    }

    @Test fun `initial absent profile stays null then resolves`() = runBlocking {
        val store = store()
        store.userEntityFlow("visible").test {
            assertNull(awaitItem())
            store.insert(profile("visible", "Alice"))
            assertEquals("Alice", awaitItem()!!.name)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
