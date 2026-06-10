package com.unsilence.app.data.wallet

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.unsilence.app.data.relay.NostrJson
import com.unsilence.app.data.relay.toEventJson
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
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG        = "NwcManager"
private const val PREFS_FILE = "unsilence_nwc"
private const val KEY_PUBKEY = "wallet_pubkey"
private const val KEY_RELAY  = "wallet_relay"
private const val KEY_SECRET = "wallet_secret"
private const val BALANCE_TTL_MS = 60_000L

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

    /** Ping-free client for genuinely one-shot sockets (balance query, ≤10s) —
     *  the base client's 25s pingInterval would schedule keepalives that never
     *  fire usefully. The pre-warmed payment socket stays on [okHttpClient]:
     *  it can sit open across invoice fetch + user confirmation and benefits
     *  from keepalives. newBuilder() shares pools, so this is cheap. */
    private val wsClient by lazy {
        okHttpClient.newBuilder().pingInterval(0, TimeUnit.MILLISECONDS).build()
    }

    /** Balance TTL cache: (timestampMs, msats) — settings screens query on every entry. */
    @Volatile private var lastBalance: Pair<Long, Long>? = null

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
        lastBalance = null
        Log.d(TAG, "Saved NWC connection to ${conn.relayUrl}")
        return true
    }

    fun clear() {
        prefs.edit().clear().apply()
        lastBalance = null
        Log.d(TAG, "NWC connection cleared")
    }

    fun connection(): NwcConnection? {
        val pubkey = prefs.getString(KEY_PUBKEY, null) ?: return null
        val relay  = prefs.getString(KEY_RELAY,  null) ?: return null
        val secret = prefs.getString(KEY_SECRET, null) ?: return null
        return NwcConnection(pubkey, relay, secret)
    }

    /** Pre-warmed NWC WebSocket handle. Call [warmUp] to start connecting, [payInvoice] to use. */
    data class WarmSocket(
        val ws: WebSocket,
        val deferred: CompletableDeferred<Result<Unit>>,
        val nwcPrivKeyBytes: ByteArray,
        val walletPubBytes: ByteArray,
        val nwcPubkeyHex: String,
        val nwcSigner: NostrSignerInternal,
        val walletPubkey: String,
    )

    /** Crypto material derived from the stored connection — shared by [warmUp] and [getBalance]. */
    private class NwcCredentials(
        val conn: NwcConnection,
        val nwcPrivKeyBytes: ByteArray,
        val nwcSigner: NostrSignerInternal,
        val nwcPubkeyHex: String,
        val walletPubBytes: ByteArray,
    )

    /** Derive per-request crypto material from stored credentials, or null if not configured. */
    private fun credentials(): NwcCredentials? {
        val conn = connection() ?: return null
        val nwcPrivKeyBytes = conn.secret.hexToByteArray()
        val nwcKeyPair      = KeyPair(privKey = nwcPrivKeyBytes)
        return NwcCredentials(
            conn            = conn,
            nwcPrivKeyBytes = nwcPrivKeyBytes,
            nwcSigner       = NostrSignerInternal(nwcKeyPair),
            nwcPubkeyHex    = nwcKeyPair.pubKey.toHexKey(),
            walletPubBytes  = conn.walletPubkey.hexToByteArray(),
        )
    }

    /**
     * Start connecting the NWC WebSocket BEFORE the bolt11 is ready.
     * Returns a [WarmSocket] handle. Call [sendPayment] once the invoice is available.
     * Returns null if NWC is not configured.
     */
    fun warmUp(): WarmSocket? {
        val creds = credentials() ?: return null

        val nwcPrivKeyBytes = creds.nwcPrivKeyBytes
        val nwcPubkeyHex    = creds.nwcPubkeyHex
        val walletPubBytes  = creds.walletPubBytes

        val deferred = CompletableDeferred<Result<Unit>>()

        val reqCmd = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive("nwc-resp-${System.currentTimeMillis()}"))
            add(buildJsonObject {
                put("kinds",   buildJsonArray { add(JsonPrimitive(23195)) })
                put("authors", buildJsonArray { add(JsonPrimitive(creds.conn.walletPubkey)) })
                put("#p",      buildJsonArray { add(JsonPrimitive(nwcPubkeyHex)) })
            })
        }.toString()

        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                ws.send(reqCmd)
                Log.d(TAG, "NWC WS pre-warmed, subscription sent")
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
                    }.getOrNull()
                    if (decrypted == null) {
                        Log.w(TAG, "NWC decrypt failed, skipping")
                        return
                    }

                    Log.d(TAG, "NWC response: $decrypted")
                    val resp = NostrJson.parseToJsonElement(decrypted).jsonObject
                    val errorElement = resp["error"]
                    if (errorElement != null && errorElement !is kotlinx.serialization.json.JsonNull) {
                        val errObj = errorElement.jsonObject
                        val code = errObj["code"]?.jsonPrimitive?.content
                        val rawMsg = errObj["message"]?.jsonPrimitive?.content
                        Log.w(TAG, "NWC payment error [$code]: $rawMsg")
                        val userMsg = when (code) {
                            "PAYMENT_FAILED"       -> "No route found — recipient may be unreachable"
                            "INSUFFICIENT_BALANCE"  -> "Insufficient wallet balance"
                            "QUOTA_EXCEEDED"        -> "Wallet spending limit reached"
                            "NOT_FOUND"             -> "Invoice expired or not found"
                            else                    -> rawMsg?.take(80) ?: "Payment failed"
                        }
                        deferred.complete(Result.failure(Exception(userMsg)))
                    } else {
                        Log.d(TAG, "NWC payment success")
                        deferred.complete(Result.success(Unit))
                    }
                    ws.close(1000, "done")
                } catch (e: Exception) {
                    Log.w(TAG, "NWC response parse error: ${e.message}")
                    if (!deferred.isCompleted) {
                        deferred.complete(Result.failure(e))
                    }
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "NWC WS failure: ${t.message}")
                if (!deferred.isCompleted) deferred.complete(Result.failure(t))
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (!deferred.isCompleted) deferred.complete(Result.failure(Exception("WS closed: $reason")))
            }
        }

        val request = Request.Builder().url(creds.conn.relayUrl).build()
        val ws = okHttpClient.newWebSocket(request, listener)

        return WarmSocket(ws, deferred, nwcPrivKeyBytes, walletPubBytes, nwcPubkeyHex, creds.nwcSigner, creds.conn.walletPubkey)
    }

    /**
     * Send a pay_invoice request on an already-connected [WarmSocket].
     * Blocks until the wallet responds or 15s timeout.
     */
    suspend fun sendPayment(warm: WarmSocket, bolt11: String): Result<Unit> = withContext(Dispatchers.IO) {
        val nowSeconds = System.currentTimeMillis() / 1000L
        val plaintext = buildJsonObject {
            put("method", "pay_invoice")
            put("id",     nowSeconds.toString())
            put("params", buildJsonObject { put("invoice", bolt11) })
        }.toString()

        val encryptedContent = runCatching {
            Nip04.encrypt(plaintext, warm.nwcPrivKeyBytes, warm.walletPubBytes)
        }.getOrElse { return@withContext Result.failure(it) }

        val template = EventTemplate<Event>(
            createdAt = nowSeconds,
            kind      = 23194,
            tags      = arrayOf(arrayOf("p", warm.walletPubkey)),
            content   = encryptedContent,
        )
        val signed = runCatching { warm.nwcSigner.sign(template) }
            .getOrElse { return@withContext Result.failure(it) }

        val eventCmd = buildJsonArray {
            add(JsonPrimitive("EVENT"))
            add(NostrJson.parseToJsonElement(toEventJson(signed)))
        }.toString()

        warm.ws.send(eventCmd)
        Log.d(TAG, "NWC payment sent on warm WS")

        val result = runCatching {
            withTimeout(15_000) { warm.deferred.await() }
        }.getOrElse { e ->
            warm.ws.close(1000, "timeout")
            Result.failure(e)
        }

        warm.ws.close(1000, "done")
        result
    }

    /**
     * Pay a BOLT-11 invoice via the configured NWC wallet (convenience — cold start).
     */
    suspend fun payInvoice(bolt11: String): Result<Unit> {
        val warm = warmUp() ?: return Result.failure(IllegalStateException("NWC not configured"))
        return sendPayment(warm, bolt11)
    }

    /**
     * Query the connected NWC wallet for the current balance via NIP-47
     * `get_balance`. Returns balance in millisats, or null on any failure
     * (not configured, timeout, wallet doesn't support the method).
     * Successful results are cached for 60s — settings screens call this on
     * every entry. Pass [forceRefresh] to bypass the cache (e.g. explicit
     * user-initiated refresh).
     */
    suspend fun getBalance(forceRefresh: Boolean = false): Long? = withContext(Dispatchers.IO) {
        val nowMs = System.currentTimeMillis()
        if (!forceRefresh) {
            lastBalance?.let { (ts, msats) ->
                if (nowMs - ts < BALANCE_TTL_MS) return@withContext msats
            }
        }

        val creds = credentials() ?: return@withContext null

        val nwcPrivKeyBytes = creds.nwcPrivKeyBytes
        val nwcPubkeyHex    = creds.nwcPubkeyHex
        val walletPubBytes  = creds.walletPubBytes
        val nowSeconds      = nowMs / 1000L

        val plaintext = buildJsonObject {
            put("method", "get_balance")
            put("id",     nowSeconds.toString())
            put("params", buildJsonObject {})
        }.toString()

        val encryptedContent = runCatching {
            Nip04.encrypt(plaintext, nwcPrivKeyBytes, walletPubBytes)
        }.getOrElse { return@withContext null }

        val template = EventTemplate<Event>(
            createdAt = nowSeconds,
            kind      = 23194,
            tags      = arrayOf(arrayOf("p", creds.conn.walletPubkey)),
            content   = encryptedContent,
        )
        val signed = runCatching { creds.nwcSigner.sign(template) }
            .getOrElse { return@withContext null }

        val deferred = CompletableDeferred<Long?>()
        val subId = "nwc-balance-${System.currentTimeMillis()}"
        val reqCmd = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(buildJsonObject {
                put("kinds",   buildJsonArray { add(JsonPrimitive(23195)) })
                put("authors", buildJsonArray { add(JsonPrimitive(creds.conn.walletPubkey)) })
                put("#p",      buildJsonArray { add(JsonPrimitive(nwcPubkeyHex)) })
            })
        }.toString()
        val eventCmd = buildJsonArray {
            add(JsonPrimitive("EVENT"))
            add(NostrJson.parseToJsonElement(toEventJson(signed)))
        }.toString()

        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                ws.send(reqCmd)
                ws.send(eventCmd)
                Log.d(TAG, "NWC balance request sent")
            }
            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val msg = NostrJson.parseToJsonElement(text).jsonArray
                    if (msg.getOrNull(0)?.jsonPrimitive?.content != "EVENT") return
                    if (msg.size < 3) return
                    val obj = msg[2].jsonObject
                    if (obj["kind"]?.jsonPrimitive?.content?.toIntOrNull() != 23195) return
                    val encContent = obj["content"]?.jsonPrimitive?.content ?: return
                    val decrypted = runCatching {
                        Nip04.decrypt(encContent, nwcPrivKeyBytes, walletPubBytes)
                    }.getOrNull() ?: return
                    Log.d(TAG, "NWC balance response: $decrypted")
                    val resp = NostrJson.parseToJsonElement(decrypted).jsonObject
                    if (resp["error"] != null && resp["error"] !is kotlinx.serialization.json.JsonNull) {
                        deferred.complete(null)
                    } else {
                        val msats = resp["result"]?.jsonObject?.get("balance")
                            ?.jsonPrimitive?.content?.toLongOrNull()
                        deferred.complete(msats)
                    }
                    ws.close(1000, "done")
                } catch (_: Exception) {
                    if (!deferred.isCompleted) deferred.complete(null)
                }
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "NWC balance WS failure: ${t.message}")
                if (!deferred.isCompleted) deferred.complete(null)
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (!deferred.isCompleted) deferred.complete(null)
            }
        }

        val ws = wsClient.newWebSocket(Request.Builder().url(creds.conn.relayUrl).build(), listener)
        val result = runCatching {
            withTimeout(10_000) { deferred.await() }
        }.getOrElse { null }
        ws.close(1000, "done")
        if (result != null) lastBalance = nowMs to result
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

}
