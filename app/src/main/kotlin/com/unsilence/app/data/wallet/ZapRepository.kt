package com.unsilence.app.data.wallet

import android.util.Log
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.NostrJson
import com.unsilence.app.data.repository.UserRepository
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton
import java.net.URLEncoder

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
    private val keyManager: KeyManager,
    private val relayPool: RelayPool,
    private val okHttpClient: OkHttpClient,
) {

    /**
     * Zap a note.
     * @param eventId       ID of the note being zapped
     * @param eventPubkey   Author's pubkey (hex)
     * @param relayUrl      Relay where the note was seen (included in zap request tags)
     * @param amountSats    Amount in satoshis (converted to millisats internally)
     */
    suspend fun zap(
        eventId: String,
        eventPubkey: String,
        relayUrl: String,
        amountSats: Long,
    ): Result<Unit> {
        // ── 1. Get lightning address from author's profile ────────────────────
        val lud16 = userRepository.getUserLud16(eventPubkey)
        if (lud16.isNullOrBlank()) {
            return Result.failure(Exception("Author has no lightning address (lud16) set"))
        }

        // ── 2. Resolve LNURL endpoint from lud16 ─────────────────────────────
        val lnurlEndpoint = lud16ToUrl(lud16)
            ?: return Result.failure(Exception("Cannot resolve lud16: $lud16"))

        val lnurlMeta = fetchLnurlMeta(lnurlEndpoint)
            ?: return Result.failure(Exception("LNURL fetch failed for $lnurlEndpoint"))

        val callbackUrl = lnurlMeta["callback"]?.jsonPrimitive?.content
            ?: return Result.failure(Exception("LNURL response has no callback"))

        // ── 3. Build kind-9734 zap request event ─────────────────────────────
        val signer = buildSigner()
            ?: return Result.failure(IllegalStateException("No signing key"))

        val msats      = amountSats * 1000L
        val nowSeconds = System.currentTimeMillis() / 1000L

        val template = EventTemplate<Event>(
            createdAt = nowSeconds,
            kind      = 9734,
            tags      = arrayOf(
                arrayOf("relays", relayUrl),
                arrayOf("amount", msats.toString()),
                arrayOf("p", eventPubkey),
                arrayOf("e", eventId),
            ),
            content = "",
        )
        val zapRequest = runCatching { signer.sign(template) }
            .getOrElse { e -> return Result.failure(e) }

        // Publish the zap request to the relay so the recipient's wallet can see it
        relayPool.publish(toEventJson(zapRequest))

        // ── 4. Fetch bolt11 invoice from LNURL callback ───────────────────────
        val zapRequestJson = toEventJson(zapRequest)
        val bolt11 = fetchBolt11(callbackUrl, msats, zapRequestJson)
            ?: return Result.failure(Exception("LNURL callback did not return an invoice"))

        Log.d(TAG, "Got bolt11 for $amountSats sats, paying via NWC…")

        // ── 5. Pay via NWC ────────────────────────────────────────────────────
        return nwcManager.payInvoice(bolt11)
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
    private fun fetchLnurlMeta(url: String) = runCatching {
        val req  = Request.Builder().url(url).build()
        val body = okHttpClient.newCall(req).execute().use { it.body?.string() ?: return null }
        NostrJson.parseToJsonElement(body).jsonObject
    }.getOrNull()

    /** GET the bolt11 invoice from the LNURL callback. */
    private fun fetchBolt11(callback: String, msats: Long, zapRequestJson: String): String? =
        runCatching {
            val encoded  = URLEncoder.encode(zapRequestJson, "UTF-8")
            val url      = "$callback?amount=$msats&nostr=$encoded"
            val req      = Request.Builder().url(url).build()
            val body     = okHttpClient.newCall(req).execute()
                .use { it.body?.string() ?: return null }
            val obj      = NostrJson.parseToJsonElement(body).jsonObject
            obj["pr"]?.jsonPrimitive?.content
        }.getOrNull()

    private fun buildSigner(): NostrSignerInternal? {
        val privKeyHex = keyManager.getPrivateKeyHex() ?: return null
        return NostrSignerInternal(KeyPair(privKey = privKeyHex.hexToByteArray()))
    }

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
