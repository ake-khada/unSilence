package com.unsilence.app.ui.compose

import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import com.unsilence.app.MainActivity
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.relay.EventDto
import com.unsilence.app.data.relay.RelayConnection
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.toNostrEvent
import com.unsilence.app.ui.common.LocalShowSnackbar
import com.unsilence.app.ui.theme.UnsilenceTheme
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class ComposePublishRetryTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun missingAcknowledgementRetriesTheExactEnvelopeWithoutCallingTheSignerAgain() {
        val activity = ui.activity
        check(Build.HARDWARE in listOf("ranchu", "goldfish") && activity.packageName.endsWith(".dev"))
        val keys = activity.keyManager
        check(keys.getPublicKeyHex() == null)
        val wire = CopyOnWriteArrayList<String>()
        val relay = "wss://publish-fixture.invalid"
        val connection = RelayConnection(relay, WebSocket.Factory { request, listener ->
            val socket = object : WebSocket {
                override fun request(): Request = request
                override fun queueSize() = 0L
                override fun send(text: String): Boolean { wire.add(text); return true }
                override fun send(bytes: ByteString) = false
                override fun close(code: Int, reason: String?) = true
                override fun cancel() = Unit
            }
            listener.onOpen(socket, Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(101).message("Switching Protocols").build())
            socket
        })
        var registry: MutableMap<String, RelayConnection>? = null
        var owner: String? = null
        val notices = mutableListOf<String>()
        var dismissals = 0
        try {
            lateinit var vm: ComposeViewModel
            lateinit var pool: RelayPool
            ui.runOnUiThread {
                keys.generateNewKey()
                val pk = requireNotNull(keys.getPublicKeyHex())
                owner = pk
                vm = ViewModelProvider(activity)[ComposeViewModel::class.java]
                fun field(name: String) = ComposeViewModel::class.java.getDeclaredField(name)
                    .apply { isAccessible = true }.get(vm)
                val memory = field("memoryEventStore") as MemoryEventStore
                memory.insert(EventDto("c".repeat(64), pk, 10002, "", 1, listOf(listOf("r", relay)),
                    "0".repeat(128)).toNostrEvent(""))
                check(memory.writeRelaysFor(pk) == listOf(relay))
                pool = field("relayPool") as RelayPool
                @Suppress("UNCHECKED_CAST")
                val connections = RelayPool::class.java.getDeclaredMethod("getConnections")
                    .apply { isAccessible = true }.invoke(pool) as MutableMap<String, RelayConnection>
                connection.connect()
                check(connections.putIfAbsent(relay, connection) == null)
                registry = connections
                activity.setContent {
                    UnsilenceTheme {
                        CompositionLocalProvider(LocalShowSnackbar provides { notices.add(it) }) {
                            ComposeScreen(onDismiss = { dismissals++ }, viewModel = vm)
                        }
                    }
                }
            }
            ui.onNode(hasSetTextAction()).performTextReplacement("Local retry fixture")
            ui.onNodeWithText("Post").performClick()
            ui.onNodeWithText("Cancel").performClick()
            ui.onNode(hasSetTextAction() and hasText("Local retry fixture")).assertIsDisplayed()
            assertTrue("Cancelling confirmation must leave the relay untouched", wire.isEmpty())
            ui.onNode(hasSetTextAction()).performTextReplacement("Edited retry fixture")
            ui.onNodeWithText("Post").performClick()
            ui.onNode(hasClickAction() and hasText("Confirm")).performClick()
            ui.waitUntil(15_000) { wire.size == 1 }
            // The fixture received the EVENT, but deliberately withholds OK.
            ui.waitUntil(15_000) { vm.sendState.value is SendState.Failed }
            val reason = (vm.sendState.value as SendState.Failed).reason
            ui.onNodeWithText("Publish status").assertIsDisplayed()
            ui.runOnIdle { keys.saveAmberLogin(requireNotNull(owner)) }
            ui.onNodeWithText("Retry").performClick()
            ui.waitUntil(12_000) { wire.size >= 2 || (vm.sendState.value as? SendState.Failed)?.reason?.contains("Signing failed") == true }
            assertEquals("Transport retry must work while the signer is unavailable", 2, wire.size)
            assertEquals(wire[0], wire[1])
            assertTrue("Missing OK is an unknown delivery outcome", reason.contains("confirm", ignoreCase = true))
            val id = org.json.JSONArray(wire[1]).getJSONObject(1).getString("id")
            val handleOk = RelayPool::class.java.getDeclaredMethod("handleOk", RelayConnection::class.java, String::class.java)
            handleOk.isAccessible = true
            handleOk.invoke(pool, connection, """["OK","$id",false,"blocked: fixture"]""")
            ui.waitUntil(5_000) { vm.sendState.value is SendState.Failed }
            ui.onNodeWithText("All target relays rejected this attempt.").assertIsDisplayed()
            ui.runOnIdle {
                assertEquals(0, dismissals)
                assertTrue(notices.isEmpty())
            }
            ui.onNodeWithText("Retry").performClick()
            ui.waitUntil(10_000) { wire.size >= 3 }
            assertEquals(listOf(wire.first(), wire.first(), wire.first()), wire.toList())
            handleOk.invoke(pool, connection, """["OK","$id",true,""]""")
            ui.waitUntil(5_000) { dismissals > 0 }
            ui.runOnIdle {
                assertEquals(1, dismissals)
                assertEquals(listOf("Post published"), notices)
            }
        } finally {
            registry?.remove(relay, connection)
            connection.close()
            if (owner != null && keys.getPublicKeyHex() == owner) keys.clear()
        }
    }
}
