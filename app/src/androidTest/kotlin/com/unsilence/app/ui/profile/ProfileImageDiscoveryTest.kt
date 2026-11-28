package com.unsilence.app.ui.profile

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.ViewModelProvider
import com.unsilence.app.MainActivity
import com.unsilence.app.data.auth.SignatureVerifier
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.network.Nip05HttpClient
import com.unsilence.app.data.network.Nip05ResolutionCoordinator
import com.unsilence.app.data.network.Nip05VerificationStatus
import com.unsilence.app.data.relay.EventDto
import com.unsilence.app.data.relay.EventProcessor
import com.unsilence.app.data.relay.NostrJson
import com.unsilence.app.data.relay.RelayConnection
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.toNostrEvent
import com.unsilence.app.ui.navigation.DeepLinkTarget
import com.unsilence.app.ui.navigation.resolveDeepLink
import com.unsilence.app.ui.theme.UnsilenceTheme
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.WebSocket
import okio.ByteString
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class ProfileImageDiscoveryTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun bareKeyRecoversBothImagesFromLateVerifiedIdentityHint() {
        val activity = ui.activity
        check(Build.HARDWARE in listOf("ranchu", "goldfish") && activity.packageName.endsWith(".dev"))
        val signer = NostrSignerInternal(KeyPair())
        val link = resolveDeepLink("nostr:" + signer.pubKey.hexToByteArray().toNpub()) as DeepLinkTarget.Profile
        assertTrue(link.relayHints.isEmpty())
        val relay = "wss://identity-relay.invalid"
        val name = "Profile image fixture"
        val address = "alice@identity.invalid"
        val requests = CopyOnWriteArrayList<String>()
        val blocked = "wss://blocked-relay.invalid"
        val blockedRequests = CopyOnWriteArrayList<String>()
        val hintSocket = recordingRelay(relay, link.pubkey, requests)
        val blockedSocket = recordingRelay(blocked, link.pubkey, blockedRequests)
        val calls = AtomicInteger()
        val http = Nip05HttpClient(OkHttpClient.Builder().addInterceptor { chain ->
            check(chain.request().url.host == "identity.invalid")
            calls.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"names":{"alice":"${link.pubkey}"},"relays":{"${link.pubkey}":["$blocked","$relay"]}}""".toResponseBody()).build()
        }.build())
        val vm = ui.runOnUiThread { ViewModelProvider(activity)[UserProfileViewModel::class.java] }
        fun field(name: String) = UserProfileViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(vm)
        val memory = field("memoryEventStore") as MemoryEventStore
        val pool = field("relayPool") as RelayPool
        @Suppress("UNCHECKED_CAST")
        val pooled = RelayPool::class.java.getDeclaredMethod("getConnections").apply { isAccessible = true }
            .invoke(pool) as MutableMap<String, RelayConnection>
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val verification = Nip05ResolutionCoordinator(memory, http::resolve, {}, scope)
        val previousBlocks = pool.getBlockedUrls()
        val previousOwner = memory.ownPubkey
        val avatar = File(activity.cacheDir, "hint-avatar.jpg")
        val banner = File(activity.cacheDir, "hint-banner.jpg")
        val processor = EventProcessor(memory, SignatureVerifier())
        fun signed(time: Long, fields: String) = runBlocking {
            signer.sign(EventTemplate<Event>(time, 0, emptyArray(), """{"name":"$name","nip05":"$address"$fields}"""))
        }
        try {
            fun jpeg(file: File, color: Int) {
                val bmp = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(color)
                file.outputStream().use { check(bmp.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
                bmp.recycle()
            }
            jpeg(avatar, Color.RED)
            jpeg(banner, Color.BLUE)
            val partial = signed(100, "")
            memory.ownPubkey = "ab".repeat(32)
            memory.insert(NostrJson.decodeFromString<EventDto>(partial.toJson()).toNostrEvent("wss://source.invalid"))
            ui.runOnUiThread {
                activity.setContent {
                    UnsilenceTheme {
                        UserProfileScreen(pubkey = link.pubkey, entryId = "fixture", onArticleClick = {}, onDismiss = {}, viewModel = vm)
                    }
                }
            }
            ui.waitUntil("Partial profile visible", 12_000) { ui.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty() }
            assertNull(memory.getUserEntity(link.pubkey)?.picture)
            pool.onBlockedRelaysChanged(previousBlocks + blocked)
            blockedSocket.connect()
            check(pooled.putIfAbsent(blocked, blockedSocket) == null)
            hintSocket.connect()
            check(pooled.putIfAbsent(relay, hintSocket) == null)
            verification.markProfileOpened(link.pubkey)
            repeat(2) {
                assertEquals(Nip05VerificationStatus.VERIFIED, runBlocking { verification.resolveNowForTest(link.pubkey, address) })
            }
            assertEquals(1, calls.get())
            ui.waitUntil("One kind-0 request to the verified hint", 12_000) { requests.isNotEmpty() }
            // Another resumed collector observing the same incomplete claim must share the attempt.
            runBlocking { pool.fetchVerifiedProfileHints(link.pubkey, listOf(blocked, relay)) }
            val imageFields = ",\"picture\":\"${Uri.fromFile(avatar)}\",\"banner\":\"${Uri.fromFile(banner)}\""
            val full = signed(101, imageFields)
            runBlocking { processor.process("""["EVENT","${requests.single()}",${full.toJson()}]""", hintSocket) }
            ui.waitUntil("Avatar and banner JPEG pixels displayed", 15_000) {
                val bmp = ui.onRoot().captureToImage().asAndroidBitmap()
                var red = 0
                var blue = 0
                for (y in 0 until bmp.height step 12) for (x in 0 until bmp.width step 12) {
                    val c = bmp.getPixel(x, y)
                    if (Color.green(c) < 20) {
                        if (Color.red(c) > 230 && Color.blue(c) < 20) red++
                        if (Color.blue(c) > 230 && Color.red(c) < 20) blue++
                    }
                }
                red > 20 && blue > 80
            }
            assertEquals(1, requests.size)
            assertTrue(blockedRequests.isEmpty())
        } finally {
            pool.onBlockedRelaysChanged(previousBlocks)
            pooled.remove(blocked, blockedSocket)
            blockedSocket.close()
            pooled.remove(relay, hintSocket)
            hintSocket.close()
            processor.stop()
            scope.cancel()
            memory.ownPubkey = previousOwner
            avatar.delete()
            banner.delete()
        }
    }
    private fun recordingRelay(relay: String, author: String, requests: MutableList<String>): RelayConnection =
        RelayConnection(relay, WebSocket.Factory { request, listener ->
            val socket = object : WebSocket {
                override fun request(): Request = request
                override fun queueSize() = 0L
                override fun send(text: String): Boolean {
                    val frame = JSONArray(text)
                    if (frame.getString(0) == "REQ" && (2 until frame.length()).any { i ->
                            val filter = frame.getJSONObject(i)
                            val authors = filter.optJSONArray("authors")
                            val kinds = filter.optJSONArray("kinds")
                            authors != null && kinds != null &&
                                (0 until authors.length()).any { authors.getString(it) == author } &&
                                (0 until kinds.length()).any { kinds.getInt(it) == 0 }
                        }
                    ) requests.add(frame.getString(1))
                    return true
                }
                override fun send(bytes: ByteString) = false
                override fun close(code: Int, reason: String?) = true
                override fun cancel() = Unit
            }
            listener.onOpen(socket, Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(101).message("Switching Protocols").build())
            socket
        })
}
