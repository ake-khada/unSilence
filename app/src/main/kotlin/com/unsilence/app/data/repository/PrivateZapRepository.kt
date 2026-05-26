package com.unsilence.app.data.repository

import android.util.Log
import com.unsilence.app.data.auth.SigningManager
import com.unsilence.app.data.memory.DecryptedPrivateZap
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.relay.NostrJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PrivateZapRepo"

@Singleton
class PrivateZapRepository @Inject constructor(
    private val signingManager: SigningManager,
    private val memoryEventStore: MemoryEventStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start() {
        scope.launch {
            memoryEventStore.pendingPrivateZapDecrypts.collect { pending ->
                processOne(pending)
            }
        }
    }

    private suspend fun processOne(pending: com.unsilence.app.data.memory.PendingPrivateZapDecrypt) {
        // Skip if already decrypted (rescans + live arrival can race).
        if (memoryEventStore.getDecryptedPrivateZap(pending.zapReceiptId) != null) return

        val plaintext = signingManager.decrypt(
            ciphertext = pending.anonCiphertext,
            peerPubkeyHex = pending.anonSignerPubkey,
        )
        if (plaintext == null) {
            Log.i(TAG, "decrypt failed for zap ${pending.zapReceiptId.take(8)}")
            return
        }

        // Decrypted payload is a JSON Nostr event (kind-9733 from Quartz's
        // PrivateZapRequestBuilder, or kind-9734 from legacy senders). Either
        // shape exposes pubkey + content at the top level, so we just extract
        // those without checking kind. Validation: must have non-blank 64-char hex pubkey.
        val parsed = try {
            val obj = NostrJson.parseToJsonElement(plaintext).jsonObject
            val realSender = obj["pubkey"]?.jsonPrimitive?.content
                ?.takeIf { it.length == 64 }
            val realContent = obj["content"]?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() }
            if (realSender == null) null else DecryptedPrivateZap(realSender, realContent)
        } catch (e: Exception) {
            Log.i(TAG, "decrypt result not valid JSON for ${pending.zapReceiptId.take(8)}: ${e.message}")
            null
        } ?: return

        // Resolve targetId from the kind-9735 receipt's e-tag.
        val receipt = memoryEventStore.getNostrEvent(pending.zapReceiptId) ?: return
        val targetId = receipt.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
            ?: return

        memoryEventStore.updateDecryptedPrivateZap(
            zapReceiptId = pending.zapReceiptId,
            decrypted = parsed,
            targetId = targetId,
        )
        Log.i(TAG, "decrypted private zap ${pending.zapReceiptId.take(8)} from ${parsed.senderPubkey.take(8)}\u2026")
    }
}
