package com.unsilence.app.data.wallet

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.unsilence.app.data.relay.NostrJson
import com.unsilence.app.data.relay.toEventJson
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip04Dm.crypto.Nip04
import com.vitorpamplona.quartz.nip42RelayAuth.RelayAuthEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG        = "NwcManager"
private const val PREFS_FILE = "unsilence_nwc"
private const val KEY_PUBKEY = "wallet_pubkey"
private const val KEY_RELAY  = "wallet_relay"
private const val KEY_SECRET = "wallet_secret"
private const val KEY_OWNER  = "owner_pubkey"
private const val BALANCE_TTL_MS = 60_000L
// Lightning routing can legitimately outlast the fast relay/network legs. Keep
// listening without ever resending the payment; timeout remains an unknown state.
private const val PAYMENT_RESPONSE_TIMEOUT_MS = 90_000L
private val DIAGNOSTIC_CONTROL_REGEX = Regex("[\\p{Cc}\\p{Cf}]+")

class WalletPaymentPendingException(message: String) : Exception(message)

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
    @Volatile private var paymentSession: PaymentSession? = null
    private val paymentSessionLock = Any()
    private val requestPayloadIds = AtomicLong(System.currentTimeMillis())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val isConfigured: Boolean
        get() = prefs.contains(KEY_PUBKEY)

    /**
     * Parse and persist a nostr+walletconnect:// URI.
     * Returns true on success, false if the URI is malformed or missing required fields.
     */
    fun save(uri: String): Boolean {
        val conn = parseUri(uri.trim()) ?: return false
        closePaymentSession("wallet changed")
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
        closePaymentSession("wallet cleared")
        prefs.edit().clear().apply()
        lastBalance = null
        Log.d(TAG, "NWC connection cleared")
    }

    /** Retire the persistent payment socket before Android suspends networking. */
    fun suspendForBackground() {
        closePaymentSession("app backgrounded")
    }

    /**
     * Backstop for fast account switches where teardown can bail behind a newer
     * bootstrap. Normal logout clears NWC credentials; this catches credentials
     * that survive only because that teardown was intentionally skipped.
     */
    fun resetIfOwnerChanged(ownerPubkeyHex: String) {
        val owner = ownerPubkeyHex.lowercase()
        val stampedOwner = prefs.getString(KEY_OWNER, null)?.lowercase()
        if (stampedOwner != null && stampedOwner != owner) {
            clear()
        }
        prefs.edit().putString(KEY_OWNER, owner).apply()
    }

    fun connection(): NwcConnection? {
        val pubkey = prefs.getString(KEY_PUBKEY, null) ?: return null
        val relay  = prefs.getString(KEY_RELAY,  null) ?: return null
        val secret = prefs.getString(KEY_SECRET, null) ?: return null
        return NwcConnection(pubkey, relay, secret)
    }

    internal class WarmSocket internal constructor(internal val sessionKey: String)

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

    private fun paymentSessionKey(creds: NwcCredentials): String =
        "${creds.conn.walletPubkey}|${creds.conn.relayUrl}|${creds.nwcPubkeyHex}"

    private fun currentPaymentSession(): PaymentSession? {
        val creds = credentials() ?: return null
        val key = paymentSessionKey(creds)
        var newSession: PaymentSession? = null
        val session = synchronized(paymentSessionLock) {
            paymentSession
                ?.takeIf { it.key == key && !it.isClosed }
                ?: PaymentSession(creds, key).also {
                    paymentSession = it
                    newSession = it
                }
        }
        newSession?.connect()
        return session
    }

    private fun closePaymentSession(reason: String) {
        val session = synchronized(paymentSessionLock) {
            paymentSession.also { paymentSession = null }
        }
        session?.close(reason)
    }

    private fun discardPaymentSession(session: PaymentSession) {
        synchronized(paymentSessionLock) {
            if (paymentSession === session) paymentSession = null
        }
    }

    /**
     * Start or reuse the NWC payment WebSocket BEFORE the bolt11 is ready.
     * The socket is kept open for later zaps and closed on wallet/account changes.
     */
    internal fun warmUp(): WarmSocket? {
        val session = currentPaymentSession() ?: return null
        return WarmSocket(session.key)
    }

    /**
     * Send a pay_invoice request after the persistent NWC socket is connected and subscribed.
     * Payments are serialized because a single NWC connection has one active request/response pair.
     */
    internal suspend fun sendPayment(
        warm: WarmSocket,
        bolt11: String,
        amountMsats: Long? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val creds = credentials() ?: return@withContext Result.failure(IllegalStateException("NWC not configured"))
        val key = paymentSessionKey(creds)
        if (key != warm.sessionKey) {
            return@withContext Result.failure(IllegalStateException("Wallet connection changed before payment"))
        }
        val session = currentPaymentSession()
            ?: return@withContext Result.failure(IllegalStateException("NWC not configured"))
        session.sendPayment(bolt11, amountMsats)
    }

    private data class PendingPayment(
        val requestEventId: String,
        val requestPayloadId: String,
        val deferred: CompletableDeferred<Result<Unit>>,
        val eventCmd: String,
    )

    private inner class PaymentSession(
        private val creds: NwcCredentials,
        val key: String,
    ) {
        private val ready = CompletableDeferred<Result<Unit>>()
        private val sendMutex = Mutex()
        private val pending = AtomicReference<PendingPayment?>(null)
        private val subId = "nwc-resp-${System.currentTimeMillis()}"
        private val relayLabel = creds.conn.relayUrl.toUri().host ?: "unknown"

        @Volatile var isClosed: Boolean = false
            private set
        @Volatile private var webSocket: WebSocket? = null
        @Volatile private var authRecoveryInFlight = false

        fun connect() {
            Log.i(TAG, "Payment socket connecting: relay=$relayLabel")
            val request = Request.Builder().url(creds.conn.relayUrl).build()
            webSocket = okHttpClient.newWebSocket(request, listener())
        }

        fun close(reason: String) {
            isClosed = true
            Log.i(TAG, "Payment socket closing: relay=$relayLabel reason=$reason")
            if (!ready.isCompleted) {
                ready.complete(Result.failure(Exception("Wallet relay closed: $reason")))
            }
            pending.getAndSet(null)?.deferred?.complete(Result.failure(Exception("Wallet relay closed: $reason")))
            webSocket?.close(1000, reason.take(120))
            webSocket = null
            discardPaymentSession(this)
        }

        suspend fun sendPayment(bolt11: String, amountMsats: Long?): Result<Unit> = sendMutex.withLock {
            val readyResult = try {
                withTimeout(ZAP_NETWORK_LEG_TIMEOUT_MS) { ready.await() }
            } catch (e: TimeoutCancellationException) {
                close("ready timeout")
                return@withLock Result.failure(Exception("Wallet relay did not become ready"))
            } catch (e: CancellationException) {
                throw e
            }
            readyResult.getOrElse { return@withLock Result.failure(it) }

            val ws = webSocket ?: return@withLock Result.failure(Exception("Wallet relay is not connected"))
            val nowSeconds = System.currentTimeMillis() / 1000L
            val requestPayloadId = nextRequestPayloadId()
            val plaintext = buildJsonObject {
                put("method", "pay_invoice")
                put("id",     requestPayloadId)
                put("params", buildJsonObject {
                    put("invoice", bolt11)
                    if (amountMsats != null) put("amount", amountMsats)
                })
            }.toString()

            val encryptedContent = runCatching {
                Nip04.encrypt(plaintext, creds.nwcPrivKeyBytes, creds.walletPubBytes)
            }.getOrElse { return@withLock Result.failure(it) }

            val template = EventTemplate<Event>(
                createdAt = nowSeconds,
                kind      = 23194,
                tags      = arrayOf(arrayOf("p", creds.conn.walletPubkey)),
                content   = encryptedContent,
            )
            val signed = runCatching { creds.nwcSigner.sign(template) }
                .getOrElse { return@withLock Result.failure(it) }

            val eventCmd = buildJsonArray {
                add(JsonPrimitive("EVENT"))
                add(NostrJson.parseToJsonElement(toEventJson(signed)))
            }.toString()

            val payment = PendingPayment(
                requestEventId = signed.id,
                requestPayloadId = requestPayloadId,
                deferred = CompletableDeferred(),
                eventCmd = eventCmd,
            )
            if (!pending.compareAndSet(null, payment)) {
                return@withLock Result.failure(IllegalStateException("Another wallet payment is already pending"))
            }
            if (!ws.send(eventCmd)) {
                pending.compareAndSet(payment, null)
                close("send failed")
                return@withLock Result.failure(IllegalStateException("Wallet relay disconnected before payment was sent"))
            }
            Log.i(TAG, "Payment request queued: relay=$relayLabel request=${signed.id.take(8)}")

            try {
                withTimeout(PAYMENT_RESPONSE_TIMEOUT_MS) { payment.deferred.await() }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "Payment response timeout: relay=$relayLabel request=${signed.id.take(8)}")
                close("payment timeout")
                Result.failure(
                    WalletPaymentPendingException(
                        "Wallet has not confirmed this zap yet. Check your wallet before retrying."
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } finally {
                pending.compareAndSet(payment, null)
            }
        }

        private fun listener() = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!webSocket.send(responseSubscription())) {
                    fail(Exception("Wallet relay disconnected before response subscription was sent"))
                    return
                }
                Log.i(TAG, "Payment socket open: relay=$relayLabel")
                if (!ready.isCompleted) ready.complete(Result.success(Unit))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching {
                    NostrJson.parseToJsonElement(text) as? JsonArray
                }.getOrNull() ?: return
                when ((msg.getOrNull(0) as? JsonPrimitive)?.content ?: return) {
                    "AUTH" -> {
                        val challenge = (msg.getOrNull(1) as? JsonPrimitive)?.content
                        if (challenge.isNullOrBlank()) return
                        authRecoveryInFlight = true
                        Log.i(TAG, "Payment relay requested authentication: relay=$relayLabel")
                        scope.launch {
                            if (sendAuthResponse(webSocket, creds, challenge)) {
                                val subscriptionQueued = webSocket.send(responseSubscription())
                                val paymentQueued = pending.get()?.eventCmd?.let { webSocket.send(it) } ?: true
                                authRecoveryInFlight = false
                                if (!subscriptionQueued || !paymentQueued) {
                                    fail(Exception("Wallet relay disconnected during authentication recovery"))
                                    return@launch
                                }
                                if (!ready.isCompleted) ready.complete(Result.success(Unit))
                            } else {
                                authRecoveryInFlight = false
                                fail(Exception("Wallet relay authentication failed"))
                            }
                        }
                        return
                    }
                    "OK" -> {
                        handleEventOk(msg)
                        return
                    }
                    "NOTICE" -> {
                        val reason = diagnosticMessage((msg.getOrNull(1) as? JsonPrimitive)?.content.orEmpty())
                        Log.w(TAG, "Payment relay notice: relay=$relayLabel reason=$reason")
                        return
                    }
                    "CLOSED" -> {
                        val closedSubId = (msg.getOrNull(1) as? JsonPrimitive)?.content ?: return
                        if (closedSubId != subId) return
                        val reason = (msg.getOrNull(2) as? JsonPrimitive)?.content.orEmpty()
                        if (reason.startsWith("auth-required", ignoreCase = true) && authRecoveryInFlight) {
                            Log.i(TAG, "Payment response subscription awaiting authentication: relay=$relayLabel")
                            return
                        }
                        fail(Exception("Wallet relay closed its response subscription: ${diagnosticMessage(reason)}"))
                        return
                    }
                    "EVENT" -> handleResponseEvent(msg)
                    else -> return
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                fail(t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!isClosed) fail(Exception("WS closed: $reason"))
            }
        }

        private fun responseSubscription(): String = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(buildJsonObject {
                put("kinds",   buildJsonArray { add(JsonPrimitive(23195)) })
                put("authors", buildJsonArray { add(JsonPrimitive(creds.conn.walletPubkey)) })
                put("#p",      buildJsonArray { add(JsonPrimitive(creds.nwcPubkeyHex)) })
            })
        }.toString()

        private fun handleEventOk(msg: JsonArray) {
            val payment = pending.get() ?: return
            val eventId = (msg.getOrNull(1) as? JsonPrimitive)?.content ?: return
            if (eventId != payment.requestEventId) return
            val success = (msg.getOrNull(2) as? JsonPrimitive)?.booleanOrNull ?: return
            val message = (msg.getOrNull(3) as? JsonPrimitive)?.content.orEmpty()
            if (success) {
                Log.i(TAG, "Payment request accepted: relay=$relayLabel request=${eventId.take(8)}")
                return
            }

            if (message.contains("auth", ignoreCase = true)) {
                Log.i(TAG, "Payment request awaiting authentication: relay=$relayLabel request=${eventId.take(8)}")
                return
            }
            if (pending.compareAndSet(payment, null)) {
                val reason = diagnosticMessage(message)
                Log.w(TAG, "Payment request rejected: relay=$relayLabel request=${eventId.take(8)} reason=$reason")
                payment.deferred.complete(Result.failure(Exception("Wallet relay rejected payment request: $reason")))
            }
        }

        private fun handleResponseEvent(msg: JsonArray) {
            val payment = pending.get() ?: return
            val event = msg.getOrNull(2) as? JsonObject ?: return
            val response = authenticateNwcResponse(
                event = event,
                expected = NwcResponseExpectation(
                    walletPubkey = creds.conn.walletPubkey,
                    requestEventId = payment.requestEventId,
                    requestPayloadId = payment.requestPayloadId,
                    resultType = NWC_PAY_INVOICE,
                ),
                decrypt = { encrypted ->
                    runCatching {
                        Nip04.decrypt(encrypted, creds.nwcPrivKeyBytes, creds.walletPubBytes)
                    }.getOrNull()
                },
                onCorrelatedRejection = { reason ->
                    Log.w(
                        TAG,
                        "Payment response rejected: relay=$relayLabel " +
                            "request=${payment.requestEventId.take(8)} reason=$reason",
                    )
                },
            ) ?: return
            val result = payInvoiceResult(response)

            if (pending.compareAndSet(payment, null)) {
                Log.i(
                    TAG,
                    "Payment response accepted: relay=$relayLabel " +
                        "request=${payment.requestEventId.take(8)} success=${result.isSuccess}",
                )
                payment.deferred.complete(result)
            }
        }

        private fun fail(cause: Throwable) {
            isClosed = true
            Log.w(
                TAG,
                "Payment socket failed: relay=$relayLabel request=${pending.get()?.requestEventId?.take(8) ?: "none"} " +
                    "cause=${cause::class.java.simpleName} message=${diagnosticMessage(cause.message.orEmpty())}",
            )
            if (!ready.isCompleted) ready.complete(Result.failure(cause))
            pending.getAndSet(null)?.deferred?.complete(Result.failure(cause))
            webSocket?.close(1000, "failed")
            webSocket = null
            discardPaymentSession(this)
        }
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
        val requestPayloadId = nextRequestPayloadId()

        val plaintext = buildJsonObject {
            put("method", "get_balance")
            put("id",     requestPayloadId)
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
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(reqCmd)
                webSocket.send(eventCmd)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching {
                    NostrJson.parseToJsonElement(text) as? JsonArray
                }.getOrNull() ?: return
                when ((msg.getOrNull(0) as? JsonPrimitive)?.content ?: return) {
                    "AUTH" -> {
                        val challenge = (msg.getOrNull(1) as? JsonPrimitive)?.content
                        if (challenge.isNullOrBlank()) return
                        scope.launch {
                            if (sendAuthResponse(webSocket, creds, challenge)) {
                                webSocket.send(reqCmd)
                                webSocket.send(eventCmd)
                            } else if (!deferred.isCompleted) {
                                deferred.complete(null)
                            }
                        }
                        return
                    }
                    "OK" -> {
                        val eventId = (msg.getOrNull(1) as? JsonPrimitive)?.content
                        val success = (msg.getOrNull(2) as? JsonPrimitive)?.booleanOrNull
                        val message = (msg.getOrNull(3) as? JsonPrimitive)?.content.orEmpty()
                        if (eventId == signed.id && success == false) {
                            if (!message.contains("auth", ignoreCase = true) && !deferred.isCompleted) {
                                deferred.complete(null)
                            }
                        }
                        return
                    }
                    "NOTICE" -> return
                    "CLOSED" -> {
                        if (!deferred.isCompleted) deferred.complete(null)
                        return
                    }
                    "EVENT" -> Unit
                    else -> return
                }
                val event = msg.getOrNull(2) as? JsonObject ?: return
                val response = authenticateNwcResponse(
                    event = event,
                    expected = NwcResponseExpectation(
                        walletPubkey = creds.conn.walletPubkey,
                        requestEventId = signed.id,
                        requestPayloadId = requestPayloadId,
                        resultType = NWC_GET_BALANCE,
                    ),
                    decrypt = { encrypted ->
                        runCatching {
                            Nip04.decrypt(encrypted, nwcPrivKeyBytes, walletPubBytes)
                        }.getOrNull()
                    },
                    onCorrelatedRejection = { reason ->
                        Log.w(TAG, "Balance response rejected: relay=${creds.conn.relayUrl.toUri().host ?: "unknown"} reason=$reason")
                    },
                ) ?: return
                if (deferred.complete(balanceMsats(response))) {
                    webSocket.close(1000, "done")
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!deferred.isCompleted) deferred.complete(null)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
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
     * Keep the legacy numeric-string payload id while making it unique across
     * concurrent payment and balance requests in this process. This also makes
     * the signed request event id unique for otherwise-identical rapid retries.
     */
    private fun nextRequestPayloadId(): String = requestPayloadIds.updateAndGet { previous ->
        maxOf(System.currentTimeMillis(), previous + 1L)
    }.toString()

    private fun diagnosticMessage(raw: String): String = raw
        .replace(DIAGNOSTIC_CONTROL_REGEX, " ")
        .trim()
        .take(120)
        .ifBlank { "unspecified" }

    /**
     * Parses a nostr+walletconnect:// or nostrwalletconnect:// URI.
     * Normalises the scheme so Android's Uri parser can handle it.
     */
    private fun parseUri(raw: String): NwcConnection? = runCatching {
        val normalised = raw
            .replace("nostr+walletconnect://", "nwc://")
            .replace("nostrwalletconnect://",  "nwc://")
        val uri    = normalised.toUri()
        val pubkey = uri.host?.takeIf { it.length == 64 } ?: return null
        val relay  = uri.getQueryParameter("relay")?.takeIf { it.isNotBlank() } ?: return null
        val secret = uri.getQueryParameter("secret")?.takeIf { it.length == 64 } ?: return null
        NwcConnection(walletPubkey = pubkey, relayUrl = relay, secret = secret)
    }.getOrNull()

    private suspend fun sendAuthResponse(
        webSocket: WebSocket,
        creds: NwcCredentials,
        challenge: String,
    ): Boolean {
        val signed = runCatching {
            val normalizedUrl = RelayUrlNormalizer.normalize(creds.conn.relayUrl)
            creds.nwcSigner.sign(RelayAuthEvent.build(normalizedUrl, challenge))
        }.getOrElse {
            return false
        }

        val authCmd = buildJsonArray {
            add(JsonPrimitive("AUTH"))
            add(NostrJson.parseToJsonElement(toEventJson(signed)))
        }.toString()
        return webSocket.send(authCmd)
    }

}
