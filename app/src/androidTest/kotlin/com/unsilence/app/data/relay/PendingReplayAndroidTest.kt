package com.unsilence.app.data.relay

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.auth.SignatureVerifier
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.NostrEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Provider

/** Android crypto and enabled replay path, with an offline socket and no signer IPC/account. */
@RunWith(AndroidJUnit4::class)
class PendingReplayAndroidTest {
    @Test fun pendingVerifiedBodySurvivesSmallCacheMissOnAndroid() = runBlocking {
        val signed = NostrSignerInternal(KeyPair(privKey = "11".repeat(32).hexToByteArray()))
            .sign(EventTemplate<Event>(1_675_000_000L, 1, emptyArray(), "offline pending fixture"))
        val store = MemoryEventStore(object : MuteKeyProvider {}, Provider { error("No Timeline access") })
        var checks = 0
        val processor = EventProcessor(store, object : SignatureVerifier() {
            override fun verify(event: NostrEvent): Boolean { checks++; return super.verify(event) }
        })
        // The constructor starts drainers. Join their cancellation before holding the queue.
        processor.setTestScope(this)
        val delivered = mutableListOf<NostrEvent>()
        processor.registerTap(RelayMessageTap { if (it is RelayTapMessage.VerifiedEvent) delivered += it.event })
        val connection = RelayConnection("wss://fixture.example", WebSocket.Factory { request, _ ->
            object : WebSocket {
                override fun request(): Request = request
                override fun queueSize() = 0L
                override fun send(text: String) = true
                override fun send(bytes: ByteString) = true
                override fun close(code: Int, reason: String?) = true
                override fun cancel() = Unit
            }
        })
        try {
            // Hold drainers, so the real process entry owns one verified pending event.
            connection.connect()
            assertTrue(connection.send("""["REQ","fixture",{"kinds":[1]}]"""))
            val wire = """["EVENT","fixture",${signed.toJson()}]"""
            assertTrue(processor.process(wire, connection))
            assertEquals(1, processor.pendingContentCount)
            val field = EventProcessor::class.java.getDeclaredField("verifiedEvents").apply { isAccessible = true }
            (field.get(processor) as MutableMap<*, *>).clear()
            assertTrue(processor.process(wire, connection))
            assertEquals(1, checks)
            assertEquals(2, delivered.size)
            assertTrue(delivered.all { it.id == signed.id && it.sig == signed.sig && it.content == signed.content })
            assertEquals(1, processor.pendingContentCount)
        } finally { processor.stop(); connection.close(); connection.messages.cancel() }
    }
}
