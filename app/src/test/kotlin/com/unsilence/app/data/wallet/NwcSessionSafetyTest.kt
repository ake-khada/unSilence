package com.unsilence.app.data.wallet

import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.unsilence.app.data.relay.NostrJson
import com.unsilence.app.data.relay.toEventJson
import com.unsilence.app.ui.settings.ZapSettingsViewModel
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip04Dm.crypto.Nip04
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class NwcSessionSafetyTest {
    @get:Rule val folder = TemporaryFolder()
    private val relay = MockWebServer()
    private val lastCall = AtomicReference<Call>()
    private val client = OkHttpClient.Builder()
        .dns { error("Unexpected external DNS") }
        .addInterceptor { chain ->
            lastCall.set(chain.call())
            chain.proceed(chain.request())
        }
        .build()
    private val wallet = KeyPair(privKey = "22".repeat(32).hexToByteArray())
    private val secret = "11".repeat(32)
    private val manager = NwcManager(ContextWrapper(null), client).also { manager ->
        NwcManager::class.java.getDeclaredField("prefs\$delegate").apply { isAccessible = true }
            .set(manager, lazy { FakeSharedPreferences() })
    }
    private val connection = NwcConnection(wallet.pubKey.toHexKey(),
        relay.url("/").newBuilder().host("127.0.0.1").build().toString(), secret)

    private data class WalletRequest(val socket: WebSocket, val id: String, val clientKey: String)
    private val requests = LinkedBlockingQueue<WalletRequest>()

    private fun acceptSocket() {
        relay.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = NostrJson.parseToJsonElement(text).jsonArray
                if (frame[0].jsonPrimitive.content != "EVENT") return
                val event = frame[1].jsonObject
                requests.add(WalletRequest(webSocket, event.getValue("id").jsonPrimitive.content,
                    event.getValue("pubkey").jsonPrimitive.content))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }
        }))
    }

    private fun nextRequest() = checkNotNull(requests.poll(7, TimeUnit.SECONDS)) { "Wallet request timed out" }

    private suspend fun WalletRequest.replyBalance(msats: Long) {
        val payload = """{"result_type":"get_balance","result":{"balance":$msats}}"""
        val response = NostrSignerInternal(wallet).sign(EventTemplate<Event>(
            createdAt = 1_700_000_000L, kind = 23195,
            tags = arrayOf(arrayOf("e", id), arrayOf("p", clientKey)),
            content = Nip04.encrypt(payload, requireNotNull(wallet.privKey), clientKey.hexToByteArray()),
        ))
        check(socket.send("""["EVENT","balance",${toEventJson(response)}]"""))
    }

    @After fun shutdown() {
        manager.clear()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        relay.close()
    }

    @Test fun `a lost socket after queueing leaves payment unconfirmed`() = runBlocking {
        manager.persistConnection(connection)
        acceptSocket()
        val payment = async(Dispatchers.Default) { manager.payInvoice("test-only") }
        nextRequest()
        lastCall.get().cancel()
        assertTrue(withTimeout(5000) { payment.await() }.exceptionOrNull() is WalletPaymentPendingException)
    }

    @Test fun `clearing a wallet with a queued payment leaves payment unconfirmed`() = runBlocking {
        manager.persistConnection(connection)
        acceptSocket()
        val payment = async(Dispatchers.Default) { manager.payInvoice("test-only") }
        nextRequest()
        manager.clear()
        assertTrue(withTimeout(5000) { payment.await() }.exceptionOrNull() is WalletPaymentPendingException)
    }

    @Test fun `peer closing its socket reports an unconfirmed payment promptly`() = runBlocking {
        manager.persistConnection(connection)
        acceptSocket()
        val payment = async(Dispatchers.Default) { manager.payInvoice("test-only") }
        check(nextRequest().socket.close(1000, "finished"))
        assertTrue(withTimeout(5000) { payment.await() }.exceptionOrNull() is WalletPaymentPendingException)
    }

    @Test fun `retired warm handle cannot pay after clearing or replacing a wallet`() = runBlocking {
        manager.persistConnection(connection)
        acceptSocket()
        val warm = requireNotNull(manager.warmUp())
        checkNotNull(relay.takeRequest(5, TimeUnit.SECONDS))
        manager.clear()
        val cleared = manager.sendPayment(warm, "test-only")
        assertEquals("Wallet connection changed before payment", cleared.exceptionOrNull()?.message)
        manager.persistConnection(connection.copy(secret = "33".repeat(32)))
        acceptSocket()
        val replaced = manager.sendPayment(warm, "test-only")
        assertEquals("Wallet connection changed before payment", replaced.exceptionOrNull()?.message)
        assertNull(requests.poll())
    }

    @Test fun `failure before queueing remains definitive`() = runBlocking {
        manager.persistConnection(connection)
        relay.enqueue(MockResponse().setResponseCode(403))
        val failure = manager.payInvoice("test-only").exceptionOrNull()
        assertNotNull(failure)
        assertFalse(failure is WalletPaymentPendingException)
    }

    @Test fun `explicit rejection of a queued request remains definitive`() = runBlocking {
        manager.persistConnection(connection)
        acceptSocket()
        val payment = async(Dispatchers.Default) { manager.payInvoice("test-only") }
        val request = nextRequest()
        check(request.socket.send("""["OK","${request.id}",false,"blocked: fixture"]"""))
        val failure = payment.await().exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("rejected"))
        assertFalse(failure is WalletPaymentPendingException)
    }

    @Test fun `late balance after account reset cannot refill cache`() = runBlocking {
        manager.resetIfOwnerChanged("a".repeat(64))
        manager.persistConnection(connection)
        acceptSocket()
        val old = async(Dispatchers.Default) { manager.getBalance() }
        val request = nextRequest()
        manager.resetIfOwnerChanged("b".repeat(64))
        request.replyBalance(9000)
        assertNull(old.await())
        assertNull(manager.getBalance())
    }

    @Test fun `reconnecting identical credentials does not revive an old balance query`() = runBlocking {
        manager.persistConnection(connection)
        acceptSocket()
        val old = async(Dispatchers.Default) { manager.getBalance() }
        val request = nextRequest()
        manager.clear()
        manager.persistConnection(connection)
        request.replyBalance(9000)
        assertNull(old.await())
    }

    @Test fun `older refresh cannot overwrite the newer response`() = runBlocking {
        manager.persistConnection(connection)
        acceptSocket()
        val first = async(Dispatchers.Default) { manager.getBalance(true) }
        val firstRequest = nextRequest()
        acceptSocket()
        val second = async(Dispatchers.Default) { manager.getBalance(true) }
        nextRequest().replyBalance(4000)
        assertEquals(4000L, second.await())
        firstRequest.replyBalance(8000)
        first.await()
        assertEquals(4000L, manager.getBalance())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `wallet replacement clears the settings balance even on the same relay`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { folder.root.resolve("z.preferences_pb") }
            manager.persistConnection(connection)
            val vm = ZapSettingsViewModel(ZapPreferencesStore(store, scope), manager)
            acceptSocket()
            vm.refreshBalance()
            nextRequest().replyBalance(3000)
            withTimeout(4000) { vm.balanceSats.first { it == 3L } }
            manager.persistConnection(connection.copy(secret = "33".repeat(32)))
            assertNull(vm.balanceSats.value)
            acceptSocket()
            vm.refreshBalance()
            nextRequest().replyBalance(5000)
            withTimeout(4000) { vm.balanceSats.first { it == 5L } }
            vm.disconnectWallet()
            assertNull(vm.balanceSats.value)
        } finally {
            scope.cancel()
            Dispatchers.resetMain()
        }
    }
}
