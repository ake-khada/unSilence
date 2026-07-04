package com.unsilence.app.data.wallet

import com.unsilence.app.data.auth.SigningManager
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.NostrJson
import com.unsilence.app.data.relay.toEventJson
import com.unsilence.app.data.repository.UserRepository
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip57Zaps.PrivateZapEncryption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.TimeUnit

/**
 * Bundled zap parameters. [amountSats] is the only required value. [message]
 * is sent as kind-9734.content for public zaps and inside the encrypted
 * anon payload for private zaps. [isPrivate] triggers the NIP-57 private
 * zap path (Quartz PrivateZapRequestBuilder, deterministic anon key).
 */
data class ZapRequest(
    val amountSats: Long,
    val message: String? = null,
    val isPrivate: Boolean = false,
)

private const val LNURL_CACHE_TTL_MS = 5 * 60 * 1000L

class ZapException(message: String) : Exception(message)

private data class LnurlMeta(
    val callback: String,
    val minSendableMsat: Long,
    val maxSendableMsat: Long,
    val allowsNostr: Boolean,
    val nostrPubkey: String?,
)

/**
 * Orchestrates the NIP-57 zap flow:
 *   1. Build a kind-9734 zap request event (signed by user key)
 *   2. Resolve the recipient's Lightning address (lud16) → LNURL endpoint
 *   3. POST to LNURL callback → get a BOLT-11 invoice
 *   4. Pay the invoice via NwcManager (NIP-47)
 */
@Singleton
class ZapRepository @Inject constructor(
    private val nwcManager: NwcManager,
    private val userRepository: UserRepository,
    private val signingManager: SigningManager,
    private val relayPool: RelayPool,
    private val okHttpClient: OkHttpClient,
) {
    /** LNURL metadata cache: lud16 → (metadata, expiresAtMs). Saves an HTTP round-trip on repeat zaps. */
    private val lnurlCache = java.util.concurrent.ConcurrentHashMap<String, Pair<LnurlMeta, Long>>()

    /** Plain LNURL HTTP must not inherit the app's WebSocket-tuned readTimeout(0). */
    private val lnurlClient by lazy {
        okHttpClient.newBuilder()
            .callTimeout(12, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .pingInterval(0, TimeUnit.SECONDS)
            .build()
    }

    private fun getCachedMeta(lud16: String): LnurlMeta? {
        val (meta, expiresAt) = lnurlCache[lud16] ?: return null
        return if (System.currentTimeMillis() < expiresAt) meta else { lnurlCache.remove(lud16); null }
    }

    /**
     * Zap a note.
     * @param eventId       ID of the note being zapped
     * @param eventPubkey   Author's pubkey (hex)
     * @param relayUrl      Relay where the note was seen (included in zap request tags)
     * @param request       Bundled zap parameters (amount, message, privacy)
     */
    suspend fun zap(
        eventId: String,
        eventPubkey: String,
        relayUrl: String,
        request: ZapRequest,
        targetRelays: List<String> = emptyList(),
    ): Result<Event> {
        // NIP-57 relay set: where the recipient's wallet should publish the kind-9735
        // receipt AND where we publish the zap request — own write + recipient read +
        // event relays (H20c outbox targeting), falling back to the single relayUrl.
        val zapRelays = targetRelays.ifEmpty { listOf(relayUrl) }
        val amountSats = request.amountSats

        // ── 1. Get lightning address from author's profile ────────────────────
        val lud16 = userRepository.getUserLud16(eventPubkey)
        if (lud16.isNullOrBlank()) {
            return Result.failure(Exception("Author has no lightning address"))
        }

        // ── 2. Resolve LNURL metadata (cached for 5 min per lud16) ───────────
        val meta = getCachedMeta(lud16) ?: run {
            val lnurlEndpoint = lud16ToUrl(lud16)
                ?: return Result.failure(ZapException("Invalid lightning address"))
            val fetched = fetchLnurlMeta(lnurlEndpoint).getOrElse {
                return Result.failure(it)
            }
            lnurlCache[lud16] = fetched to (System.currentTimeMillis() + LNURL_CACHE_TTL_MS)
            fetched
        }

        // ── 3. Build kind-9734 zap request event ─────────────────────────────
        val msats = runCatching { Math.multiplyExact(amountSats, 1000L) }
            .getOrElse { return Result.failure(ZapException("Invalid zap amount")) }
        validateZapMetadata(meta, msats)?.let { return Result.failure(it) }
        val nowSeconds = System.currentTimeMillis() / 1000L

        val zapRequest: Event = if (request.isPrivate) {
            buildPrivateZapRequest(
                eventPubkey, eventId, zapRelays, msats, nowSeconds, request.message,
            ) ?: return Result.failure(Exception("Private zap signing failed"))
        } else {
            val template = EventTemplate<Event>(
                createdAt = nowSeconds,
                kind      = 9734,
                tags      = arrayOf(
                    // NIP-57 ["relays", url1, url2, …] — wallet publishes the receipt here.
                    (listOf("relays") + zapRelays).toTypedArray(),
                    arrayOf("amount", msats.toString()),
                    arrayOf("p", eventPubkey),
                    arrayOf("e", eventId),
                ),
                content = request.message ?: "",
            )
            signingManager.sign(template)
                ?: return Result.failure(IllegalStateException("Signing failed"))
        }

        // Publish the zap request to the target relay set (not a broadcast) so the
        // recipient's wallet can see it (H20c).
        relayPool.publish(toEventJson(zapRequest), zapRelays)

        // ── 4. Fetch bolt11 + warm up NWC WebSocket IN PARALLEL ───────────────
        val warmSocket = nwcManager.warmUp()
            ?: return Result.failure(Exception("Wallet not configured"))

        val zapRequestJson = toEventJson(zapRequest)
        val bolt11 = fetchBolt11(meta.callback, msats, zapRequestJson).getOrElse { e ->
            return Result.failure(e)
        }

        // NWC amount is only needed for amountless invoices. Some wallets reject
        // it when the BOLT-11 already carries its own amount.
        val nwcAmountMsats = if (bolt11AmountMsats(bolt11) == null) msats else null

        // ── 5. Pay on the already-connected WebSocket ─────────────────────────
        val payResult = nwcManager.sendPayment(warmSocket, bolt11, nwcAmountMsats)
        if (payResult.isSuccess) {
            return Result.success(zapRequest)
        }

        val failure = payResult.exceptionOrNull() ?: Exception("Payment failed")
        return Result.failure(failure)
    }

    /**
     * Build a NIP-57 private zap request.
     *
     * Fast path (internal signer): Quartz's NostrSignerSync.sign() detects the
     * ["anon",""] sentinel on kind-9734 and delegates to PrivateZapRequestBuilder
     * which creates an inner kind-9733, encrypts it with a deterministic key,
     * and returns the outer kind-9734 — fully spec-compliant.
     *
     * Fallback (Amber / external signer): manually builds the inner kind-9733
     * signed via signingManager, encrypts with a random anon keypair using
     * Quartz's bech32 PrivateZapEncryption, and signs the outer kind-9734
     * with that keypair. Recipient can decrypt via NIP-04 shared secret.
     */
    private suspend fun buildPrivateZapRequest(
        recipientPubkey: String,
        eventId: String,
        zapRelays: List<String>,
        msats: Long,
        nowSeconds: Long,
        message: String?,
    ): Event? {
        val tags = arrayOf(
            (listOf("relays") + zapRelays).toTypedArray(),
            arrayOf("amount", msats.toString()),
            arrayOf("p", recipientPubkey),
            arrayOf("e", eventId),
        )

        // ── Fast path: internal signer → Quartz PrivateZapRequestBuilder ─────
        val signerSync = signingManager.getSignerSync()
        if (signerSync != null) {
            return try {
                val tagsWithAnon = tags + arrayOf(arrayOf("anon", ""))
                signerSync.sign<Event>(nowSeconds, 9734, tagsWithAnon, message ?: "")
            } catch (_: Exception) {
                null
            }
        }

        // ── Fallback: Amber / external signer ────────────────────────────────
        // 1. Inner kind-9733 signed by the user's real key (via Amber intent).
        val innerTemplate = EventTemplate<Event>(
            createdAt = nowSeconds, kind = 9733, tags = tags,
            content = message ?: "",
        )
        val signedInner = signingManager.sign(innerTemplate) ?: return null
        val innerJson = toEventJson(signedInner)

        // 2. Random anon keypair for encryption + outer signing.
        val anonKeyPair = KeyPair()
        val anonPrivBytes = anonKeyPair.privKey ?: return null

        // 3. Encrypt inner JSON (bech32 pzap1…_iv1… format).
        val ciphertext = try {
            PrivateZapEncryption.encryptPrivateZapMessage(
                innerJson, anonPrivBytes, recipientPubkey.hexToByteArray(),
            )
        } catch (_: Exception) {
            return null
        }

        // 4. Outer kind-9734: anon-signed, ciphertext in anon tag, empty content.
        val outerTemplate = EventTemplate<Event>(
            createdAt = nowSeconds, kind = 9734,
            tags = tags + arrayOf(arrayOf("anon", ciphertext)),
            content = "",
        )
        return try {
            NostrSignerInternal(anonKeyPair).sign(outerTemplate)
        } catch (_: Exception) { null }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Convert a lightning address (user@domain.com) to its LNURL-pay metadata URL.
     * Spec: https://github.com/lnurl/luds/blob/luds/16.md
     */
    private fun lud16ToUrl(lud16: String): String? {
        val parts = lud16.split("@")
        if (parts.size != 2) return null
        val (name, domain) = parts
        return "https://$domain/.well-known/lnurlp/$name"
    }

    /** GET the LNURL metadata JSON (contains callback URL, min/maxSendable, etc.). */
    private suspend fun fetchLnurlMeta(url: String): Result<LnurlMeta> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).build()
            lnurlClient.newCall(req).execute().use { resp ->
                val body = resp.body.string()
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(
                        ZapException("Lightning service metadata failed: ${lnurlErrorReason(body, "HTTP ${resp.code}")}")
                    )
                }
                if (body.isNullOrBlank()) {
                    return@withContext Result.failure(ZapException("Empty response from lightning service"))
                }
                val obj = NostrJson.parseToJsonElement(body).jsonObject
                lnurlProtocolError(obj)?.let { return@withContext Result.failure(ZapException("Lightning service: $it")) }
                val callback = obj["callback"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: return@withContext Result.failure(ZapException("Lightning service missing callback"))
                Result.success(
                    LnurlMeta(
                        callback = callback,
                        minSendableMsat = obj["minSendable"]?.jsonPrimitive?.longOrNull ?: 1_000L,
                        maxSendableMsat = obj["maxSendable"]?.jsonPrimitive?.longOrNull ?: Long.MAX_VALUE,
                        allowsNostr = obj["allowsNostr"]?.jsonPrimitive?.booleanOrNull ?: false,
                        nostrPubkey = obj["nostrPubkey"]?.jsonPrimitive?.content?.trim()?.lowercase(),
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(ZapException("Lightning service unreachable: ${e.message ?: e::class.java.simpleName}"))
        }
    }

    /** GET the bolt11 invoice from the LNURL callback. */
    private suspend fun fetchBolt11(callback: String, msats: Long, zapRequestJson: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val url = callback.toHttpUrlOrNull()
                    ?.newBuilder()
                    ?.addQueryParameter("amount", msats.toString())
                    ?.addQueryParameter("nostr", zapRequestJson)
                    ?.build()
                    ?: return@withContext Result.failure(ZapException("Lightning service callback is invalid"))
                val req = Request.Builder().url(url).build()
                lnurlClient.newCall(req).execute().use { resp ->
                    val body = resp.body.string()
                    if (!resp.isSuccessful) {
                        val fallback = if (resp.code == 413 || resp.code == 414) {
                            "zap request too large (HTTP ${resp.code})"
                        } else {
                            "HTTP ${resp.code}"
                        }
                        return@withContext Result.failure(
                            ZapException("Lightning service rejected the zap: ${lnurlErrorReason(body, fallback)}")
                        )
                    }
                    if (body.isNullOrBlank()) {
                        return@withContext Result.failure(ZapException("Empty response from lightning service"))
                    }
                    val obj = NostrJson.parseToJsonElement(body).jsonObject
                    lnurlProtocolError(obj)?.let {
                        return@withContext Result.failure(ZapException("Lightning service: $it"))
                    }
                    val invoice = obj["pr"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                        ?: return@withContext Result.failure(ZapException("No invoice returned by lightning service"))
                    validateInvoiceAmount(invoice, msats)?.let {
                        return@withContext Result.failure(it)
                    }
                    Result.success(invoice)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(ZapException("Lightning service unreachable: ${e.message ?: e::class.java.simpleName}"))
            }
        }

    private fun validateZapMetadata(meta: LnurlMeta, msats: Long): ZapException? {
        val zapServicePubkey = meta.nostrPubkey
        if (!meta.allowsNostr || zapServicePubkey.isNullOrBlank()) {
            return ZapException("Recipient's lightning address doesn't support zaps")
        }
        if (!isHexPubkey(zapServicePubkey)) {
            return ZapException("Recipient's lightning address returned an invalid zap key")
        }
        if (msats < meta.minSendableMsat) {
            return ZapException("Recipient requires at least ${satsCeil(meta.minSendableMsat)} sats")
        }
        if (msats > meta.maxSendableMsat) {
            return ZapException("Recipient's maximum is ${meta.maxSendableMsat / 1000L} sats")
        }
        return null
    }

    private fun isHexPubkey(value: String): Boolean =
        value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

    private fun lnurlProtocolError(obj: JsonObject): String? {
        val status = obj["status"]?.jsonPrimitive?.content
        if (!status.equals("ERROR", ignoreCase = true)) return null
        return obj["reason"]?.jsonPrimitive?.content?.take(160) ?: "unknown reason"
    }

    private fun lnurlErrorReason(body: String?, fallback: String): String {
        if (body.isNullOrBlank()) return fallback
        return runCatching {
            val obj = NostrJson.parseToJsonElement(body).jsonObject
            lnurlProtocolError(obj) ?: fallback
        }.getOrDefault(fallback)
    }

    private fun satsCeil(msats: Long): Long = (msats + 999L) / 1000L

    private fun validateInvoiceAmount(invoice: String, expectedMsats: Long): ZapException? {
        val invoiceMsats = bolt11AmountMsats(invoice) ?: return null
        return if (invoiceMsats == expectedMsats) {
            null
        } else {
            ZapException(
                "Lightning service returned invoice for ${satsCeil(invoiceMsats)} sats " +
                    "instead of ${satsCeil(expectedMsats)} sats"
            )
        }
    }

    private fun bolt11AmountMsats(invoice: String): Long? {
        val lower = invoice.trim().lowercase()
        val separator = lower.lastIndexOf('1')
        if (separator <= 0) return null
        val hrp = lower.take(separator)
        val amountPart = when {
            hrp.startsWith("lnbcrt") -> hrp.removePrefix("lnbcrt")
            hrp.startsWith("lnbc") -> hrp.removePrefix("lnbc")
            hrp.startsWith("lntb") -> hrp.removePrefix("lntb")
            else -> return null
        }
        if (amountPart.isEmpty()) return null

        val suffix = amountPart.last()
        val digits = if (suffix in setOf('m', 'u', 'n', 'p')) {
            amountPart.dropLast(1)
        } else {
            amountPart
        }
        val amount = digits.toLongOrNull() ?: return null
        return runCatching {
            when (suffix) {
                'm' -> Math.multiplyExact(amount, 100_000_000L)
                'u' -> Math.multiplyExact(amount, 100_000L)
                'n' -> Math.multiplyExact(amount, 100L)
                'p' -> amount.takeIf { it % 10L == 0L }?.div(10L)
                else -> Math.multiplyExact(amount, 100_000_000_000L)
            }
        }.getOrNull()
    }

}
