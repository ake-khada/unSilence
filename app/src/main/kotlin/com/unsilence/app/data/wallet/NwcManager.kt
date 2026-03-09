package com.unsilence.app.data.wallet

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.unsilence.app.data.relay.NostrJson
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip04Dm.crypto.Nip04
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG        = "NwcManager"
private const val PREFS_FILE = "unsilence_nwc"
private const val KEY_PUBKEY = "wallet_pubkey"
private const val KEY_RELAY  = "wallet_relay"
private const val KEY_SECRET = "wallet_secret"

/** Parsed fields from a nostr+walletconnect:// URI. */
data class NwcConnection(
    val walletPubkey: String,
    val relayUrl: String,
    val secret: String,          // client private key hex (32 bytes = 64 chars)
)

/**
 * Manages the Nostr Wallet Connect (NIP-47) connection.
 *
 * Stores NWC credentials in EncryptedSharedPreferences and provides a single
 * [payInvoice] entry point that opens a one-shot WebSocket to the NWC relay,
 * sends a kind-23194 pay_invoice request, and waits for the kind-23195 response.
 */
@Singleton
class NwcManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
) {
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    val isConfigured: Boolean
        get() = prefs.contains(KEY_PUBKEY)

    /**
     * Parse and persist a nostr+walletconnect:// URI.
     * Returns true on success, false if the URI is malformed or missing required fields.
     */
    fun save(uri: String): Boolean {
        val conn = parseUri(uri.trim()) ?: return false
        prefs.edit()
            .putString(KEY_PUBKEY, conn.walletPubkey)
            .putString(KEY_RELAY,  conn.relayUrl)
            .putString(KEY_SECRET, conn.secret)
            .apply()
        Log.d(TAG, "Saved NWC connection to ${conn.relayUrl}")
        return true
    }

    fun clear() {
        prefs.edit().clear().apply()
        Log.d(TAG, "NWC connection cleared")
    }

    fun connection(): NwcConnection? {
        val pubkey = prefs.getString(KEY_PUBKEY, null) ?: return null
        val relay  = prefs.getString(KEY_RELAY,  null) ?: return null
        val secret = prefs.getString(KEY_SECRET, null) ?: return null
        return NwcConnection(pubkey, relay, secret)
    }

    /**
     * Pay a BOLT-11 invoice via the configured NWC wallet.
     *
     * Opens a one-shot WebSocket to the NWC relay, sends a kind-23194 request,
     * and awaits the kind-23195 response (30 second timeout).
     */
    suspend fun payInvoice(bolt11: String): Result<Unit> = withContext(Dispatchers.IO) {
        val conn = connection()
            ?: return@withContext Result.failure(IllegalStateException("NWC not configured"))

        val nwcPrivKeyBytes = conn.secret.hexToByteArray()
        val nwcKeyPair      = KeyPair(privKey = nwcPrivKeyBytes)
        val nwcSigner       = NostrSignerInternal(nwcKeyPair)
        val nwcPubkeyHex    = nwcKeyPair.pubKey.toHexKey()
        val walletPubBytes  = conn.walletPubkey.hexToByteArray()

        val nowSeconds = System.currentTimeMillis() / 1000L
        val requestId  = nowSeconds.toString()  // used to match request↔response

        // ── Build kind 23194 encrypted request ───────────────────────────────
        val plaintext = buildJsonObject {
            put("method", "pay_invoice")
            put("id",     requestId)
            put("params", buildJsonObject { put("invoice", bolt11) })
        }.toString()

        val encryptedContent = runCatching {
            Nip04.encrypt(plaintext, nwcPrivKeyBytes, walletPubBytes)
        }.getOrElse { e ->
            return@withContext Result.failure(e)
        }

        val template = EventTemplate<Event>(
            createdAt = nowSeconds,
            kind      = 23194,
            tags      = arrayOf(arrayOf("p", conn.walletPubkey)),
            content   = encryptedContent,
        )
        val signed = runCatching { nwcSigner.sign(template) }
            .getOrElse { e -> return@withContext Result.failure(e) }

        val eventCmd = buildJsonArray {
            add(JsonPrimitive("EVENT"))
            add(NostrJson.parseToJsonElement(toEventJson(signed)))
        }.toString()

        // ── Subscribe to kind 23195 responses ────────────────────────────────
        val reqCmd = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive("nwc-resp-$nowSeconds"))
            add(buildJsonObject {
                put("kinds",   buildJsonArray { add(JsonPrimitive(23195)) })
                put("authors", buildJsonArray { add(JsonPrimitive(conn.walletPubkey)) })
                put("#p",      buildJsonArray { add(JsonPrimitive(nwcPubkeyHex)) })
            })
        }.toString()

        // ── Open WebSocket, send, await response ─────────────────────────────
        val deferred = CompletableDeferred<Result<Unit>>()

        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                ws.send(reqCmd)
                ws.send(eventCmd)
                Log.d(TAG, "NWC WS opened, request sent")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val msg  = NostrJson.parseToJsonElement(text).jsonArray
                    val type = msg.getOrNull(0)?.jsonPrimitive?.content ?: return
                    if (type != "EVENT" || msg.size < 3) return

                    val obj  = msg[2].jsonObject
                    val kind = obj["kind"]?.jsonPrimitive?.content?.toIntOrNull() ?: return
                    if (kind != 23195) return

                    val encContent = obj["content"]?.jsonPrimitive?.content ?: return
                    val decrypted  = runCatching {
                        Nip04.decrypt(encContent, nwcPrivKeyBytes, walletPubBytes)
                    }.getOrNull() ?: return

                    val resp = NostrJson.parseToJsonElement(decrypted).jsonObject
                    if (resp["error"] != null) {
                        val errMsg = resp["error"]?.jsonObject?.get("message")
                            ?.jsonPrimitive?.content ?: "Payment failed"
                        deferred.complete(Result.failure(Exception(errMsg)))
                    } else {
                        deferred.complete(Result.success(Unit))
                    }
                    ws.close(1000, "done")
                } catch (e: Exception) {
                    Log.w(TAG, "NWC response parse error: ${e.message}")
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "NWC WS failure: ${t.message}")
                if (!deferred.isCompleted) {
                    deferred.complete(Result.failure(t))
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (!deferred.isCompleted) {
                    deferred.complete(Result.failure(Exception("WS closed before response: $reason")))
                }
            }
        }

        val request = Request.Builder().url(conn.relayUrl).build()
        val ws = okHttpClient.newWebSocket(request, listener)

        val result = runCatching {
            withTimeout(30_000) { deferred.await() }
        }.getOrElse { e ->
            ws.close(1000, "timeout")
            Result.failure(e)
        }

        ws.close(1000, "done")
        result
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Parses a nostr+walletconnect:// or nostrwalletconnect:// URI.
     * Normalises the scheme so Android's Uri parser can handle it.
     */
    private fun parseUri(raw: String): NwcConnection? = runCatching {
        val normalised = raw
            .replace("nostr+walletconnect://", "nwc://")
            .replace("nostrwalletconnect://",  "nwc://")
        val uri    = Uri.parse(normalised)
        val pubkey = uri.host?.takeIf { it.length == 64 } ?: return null
        val relay  = uri.getQueryParameter("relay")?.takeIf { it.isNotBlank() } ?: return null
        val secret = uri.getQueryParameter("secret")?.takeIf { it.length == 64 } ?: return null
        NwcConnection(walletPubkey = pubkey, relayUrl = relay, secret = secret)
    }.getOrNull()

    private fun toEventJson(event: Event) = buildJsonObject {
        put("id",         event.id)
        put("pubkey",     event.pubKey)
        put("created_at", event.createdAt)
        put("kind",       event.kind)
        put("tags", buildJsonArray {
            event.tags.forEach { row ->
                add(buildJsonArray { row.forEach { cell -> add(JsonPrimitive(cell)) } })
            }
        })
        put("content", event.content)
        put("sig",     event.sig)
    }.toString()
}
