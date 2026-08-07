package com.unsilence.app.data.auth

import android.util.Log
import com.unsilence.app.data.memory.NostrEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SigVerify"

/**
 * Schnorr/secp256k1 signature verification for incoming Nostr events.
 *
 * Verifies BOTH:
 *   1. event.id == sha256(canonical_serialization(event))
 *   2. event.sig is a valid Schnorr signature of event.id by event.pubkey
 *
 * Backed by Quartz's [Event.verify] extension, which delegates to native
 * secp256k1 via JNI (~0.5-2ms per verify on Pixel-class BIG core).
 *
 * Call sites:
 *   - EventProcessor.process() — after seenIds dedup, before tap+channel
 *
 * Not called for:
 *   - Snapshot restore (local trusted data)
 *   - Optimistic inserts of our own signed events (we just signed them)
 *   - Direct MES.insert paths from trusted internal callers
 */
@Singleton
open class SignatureVerifier @Inject constructor() {

    private val verifiedOk = AtomicLong(0)
    private val verifiedBad = AtomicLong(0)
    private val verifyErrors = AtomicLong(0)

    /**
     * Verify an event's id-hash and Schnorr signature.
     * Returns true iff both checks pass.
     *
     * Performance: ~0.5-2ms per call on BIG core. Caller is responsible
     * for running this on a dispatcher that won't starve the UI thread.
     */
    open fun verify(event: NostrEvent): Boolean {
        return try {
            val ok = quartzVerify(event)
            if (ok) verifiedOk.incrementAndGet() else verifiedBad.incrementAndGet()
            if (!ok) {
                Log.w(
                    TAG,
                    "BAD SIG: kind=${event.kind} id=${event.id.take(12)}… " +
                    "pubkey=${event.pubkey.take(12)}… from=${event.relayUrl}",
                )
            }
            ok
        } catch (e: Exception) {
            verifyErrors.incrementAndGet()
            Log.w(TAG, "verify threw: kind=${event.kind} id=${event.id.take(12)}… ${e.message}")
            false
        }
    }

    data class Stats(val ok: Long, val bad: Long, val errors: Long)
    fun stats(): Stats = Stats(verifiedOk.get(), verifiedBad.get(), verifyErrors.get())

    fun reset() {
        verifiedOk.set(0)
        verifiedBad.set(0)
        verifyErrors.set(0)
    }

    /**
     * Bridge to Quartz's verification API.
     *
     * Constructs a Quartz [Event] from our [NostrEvent] fields, then calls
     * the `verify()` extension function (EventExtKt) which checks both
     * id-hash (sha256 of canonical serialization) and Schnorr signature.
     */
    private fun quartzVerify(event: NostrEvent): Boolean {
        return verifyNostrEventFields(
            id = event.id,
            pubkey = event.pubkey,
            createdAt = event.createdAt,
            kind = event.kind,
            tags = event.tags,
            content = event.content,
            sig = event.sig,
        )
    }
}

/**
 * Pure verification seam shared by outer relay events and NIP-18 embedded
 * events. Keeping canonical-id and Schnorr verification in one implementation
 * prevents the renderer from drifting into a weaker notion of "valid".
 * Callers must catch malformed/native-library failures at their trust boundary.
 */
internal fun verifyNostrEventFields(
    id: String,
    pubkey: String,
    createdAt: Long,
    kind: Int,
    tags: List<List<String>>,
    content: String,
    sig: String,
): Boolean {
    val quartzTags = tags.map { it.toTypedArray() }.toTypedArray()
    return Event(
        id = id,
        pubKey = pubkey,
        createdAt = createdAt,
        kind = kind,
        tags = quartzTags,
        content = content,
        sig = sig,
    ).verify()
}
