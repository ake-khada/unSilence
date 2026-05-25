package com.unsilence.app.data.wallet

import android.util.Log
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.auth.SigningManager
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.NostrJson
import com.unsilence.app.data.relay.toEventJson
import com.unsilence.app.data.repository.UserRepository
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton
import java.net.URLEncoder

/**
 * Bundled zap parameters. [amountSats] is the only required value. [message]
 * is sent as kind-9734.content for public zaps and inside the encrypted
 * anon payload for private zaps. [isPrivate] triggers the NIP-57 private
 * zap path (one-shot anon keypair, NIP-44-encrypted sender info).
 */
data class ZapRequest(
    val amountSats: Long,
    val message: String? = null,
    val isPrivate: Boolean = false,
)

private const val TAG = "ZapRepository"

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
    private val keyManager: KeyManager,
    private val relayPool: RelayPool,
    private val okHttpClient: OkHttpClient,
) {
    /** LNURL metadata cache: lud16 → (callbackUrl, expiresAtMs). Saves an HTTP round-trip on repeat zaps. */
    private val lnurlCache = mutableMapOf<String, Pair<String, Long>>()
    private val CACHE_TTL_MS = 5 * 60 * 1000L  // 5 minutes

    private fun getCachedCallback(lud16: String): String? {
        val (url, expiresAt) = lnurlCache[lud16] ?: return null
        return if (System.currentTimeMillis() < expiresAt) url else { lnurlCache.remove(lud16); null }
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
    ): Result<Event> {
        val t0 = System.currentTimeMillis()
        val amountSats = request.amountSats

        // ── 1. Get lightning address from author's profile ────────────────────
        val lud16 = userRepository.getUserLud16(eventPubkey)
        if (lud16.isNullOrBlank()) {
            return Result.failure(Exception("Author has no lightning address"))
        }

        // ── 2. Resolve LNURL callback (cached for 5 min per lud16) ───────────
        val callbackUrl = getCachedCallback(lud16) ?: run {
            val lnurlEndpoint = lud16ToUrl(lud16)
                ?: return Result.failure(Exception("Invalid lightning address"))
            val lnurlMeta = fetchLnurlMeta(lnurlEndpoint)
                ?: return Result.failure(Exception("Lightning service unreachable"))
            val cb = lnurlMeta["callback"]?.jsonPrimitive?.content
                ?: return Result.failure(Exception("Lightning service misconfigured"))
            lnurlCache[lud16] = cb to (System.currentTimeMillis() + CACHE_TTL_MS)
            cb
        }
        Log.d(TAG, "LNURL resolved in ${System.currentTimeMillis() - t0}ms")

        // ── 3. Build kind-9734 zap request event ─────────────────────────────
        val msats      = amountSats * 1000L
        val nowSeconds = System.currentTimeMillis() / 1000L

        val zapRequest: Event = if (request.isPrivate) {
            buildPrivateZapRequest(
                recipientPubkey = eventPubkey,
                eventId = eventId,
                relayUrl = relayUrl,
                msats = msats,
                nowSeconds = nowSeconds,
                message = request.message,
            ) ?: return Result.failure(Exception("Private zap encryption failed"))
        } else {
            val template = EventTemplate<Event>(
                createdAt = nowSeconds,
                kind      = 9734,
                tags      = arrayOf(
                    arrayOf("relays", relayUrl),
                    arrayOf("amount", msats.toString()),
                    arrayOf("p", eventPubkey),
                    arrayOf("e", eventId),
                ),
                content = request.message ?: "",
            )
            signingManager.sign(template)
                ?: return Result.failure(IllegalStateException("Signing failed"))
        }

        // Publish the zap request to the relay so the recipient's wallet can see it
        relayPool.publish(toEventJson(zapRequest))

        // ── 4. Fetch bolt11 + warm up NWC WebSocket IN PARALLEL ───────────────
        val warmSocket = nwcManager.warmUp()
            ?: return Result.failure(Exception("Wallet not configured"))

        val zapRequestJson = toEventJson(zapRequest)
        val bolt11 = fetchBolt11(callbackUrl, msats, zapRequestJson)
        if (bolt11 == null) {
            warmSocket.ws.close(1000, "no invoice")
            return Result.failure(Exception("Could not get invoice from lightning service"))
        }

        Log.d(TAG, "bolt11 obtained in ${System.currentTimeMillis() - t0}ms for $amountSats sats")

        // ── 5. Pay on the already-connected WebSocket ─────────────────────────
        val payResult = nwcManager.sendPayment(warmSocket, bolt11)
        Log.d(TAG, "Zap total: ${System.currentTimeMillis() - t0}ms, success=${payResult.isSuccess}, private=${request.isPrivate}")
        return if (payResult.isSuccess) Result.success(zapRequest)
               else Result.failure(payResult.exceptionOrNull() ?: Exception("Payment failed"))
    }

    /**
     * Build a NIP-57 private zap request. The outer kind-9734 is signed by a
     * one-shot anonymous keypair — the user's real key never touches this path.
     * The real sender pubkey + message are NIP-44 encrypted inside an "anon" tag
     * that only the recipient can decrypt.
     */
    private suspend fun buildPrivateZapRequest(
        recipientPubkey: String,
        eventId: String,
        relayUrl: String,
        msats: Long,
        nowSeconds: Long,
        message: String?,
    ): Event? {
        val realSenderPubkey = keyManager.getPublicKeyHex() ?: return null

        // One-shot anon keypair — no relationship to the user's real identity.
        val anonKeyPair = KeyPair()
        val anonSigner = NostrSignerInternal(anonKeyPair)

        // Inner payload: receiver's PrivateZapRepository.processOne reads pubkey + content.
        val innerPayload = buildJsonObject {
            put("pubkey", JsonPrimitive(realSenderPubkey))
            put("content", JsonPrimitive(message ?: ""))
        }.toString()

        // NIP-44 encrypt between anon key and recipient.
        val ciphertext = try {
            anonSigner.nip44Encrypt(innerPayload, recipientPubkey)
        } catch (e: Exception) {
            Log.w(TAG, "anon nip44Encrypt failed: ${e.message}")
            return null
        } ?: return null

        // Outer kind-9734: standard zap tags + anon ciphertext. Content is empty —
        // the message lives only inside the encrypted payload.
        val template = EventTemplate<Event>(
            createdAt = nowSeconds,
            kind      = 9734,
            tags      = arrayOf(
                arrayOf("relays", relayUrl),
                arrayOf("amount", msats.toString()),
                arrayOf("p", recipientPubkey),
                arrayOf("e", eventId),
                arrayOf("anon", ciphertext),
            ),
            content = "",
        )

        // Sign with anon key — public observers see kind-9734 from a one-shot pubkey.
        return try {
            anonSigner.sign(template)
        } catch (e: Exception) {
            Log.w(TAG, "anon sign failed: ${e.message}")
            null
        }
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
    private suspend fun fetchLnurlMeta(url: String) = withContext(Dispatchers.IO) {
        runCatching {
            val req  = Request.Builder().url(url).build()
            val body = okHttpClient.newCall(req).execute().use { it.body?.string() }
                ?: return@withContext null
            NostrJson.parseToJsonElement(body).jsonObject
        }.getOrNull()
    }

    /** GET the bolt11 invoice from the LNURL callback. */
    private suspend fun fetchBolt11(callback: String, msats: Long, zapRequestJson: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val encoded  = URLEncoder.encode(zapRequestJson, "UTF-8")
                val url      = "$callback?amount=$msats&nostr=$encoded"
                val req      = Request.Builder().url(url).build()
                val body     = okHttpClient.newCall(req).execute().use { it.body?.string() }
                    ?: return@withContext null
                val obj      = NostrJson.parseToJsonElement(body).jsonObject
                obj["pr"]?.jsonPrimitive?.content
            }.getOrNull()
        }

}
