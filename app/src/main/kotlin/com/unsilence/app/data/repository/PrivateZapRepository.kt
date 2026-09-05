package com.unsilence.app.data.repository

import android.util.Log
import com.unsilence.app.data.auth.SigningManager
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.zap.authenticatePrivateZapPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PrivateZapRepo"
private const val MAX_DECRYPT_ATTEMPTS_PER_SESSION = 3

@Singleton
class PrivateZapRepository @Inject constructor(
    private val signingManager: SigningManager,
    private val memoryEventStore: MemoryEventStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collectorJob: Job? = null
    private val foregroundGeneration = MutableStateFlow(0L)
    private val attemptsByReceipt = ConcurrentHashMap<String, Int>()

    fun start() {
        if (collectorJob?.isActive == true) return
        collectorJob = scope.launch {
            memoryEventStore.pendingPrivateZapDecrypts
                .combine(foregroundGeneration) { pending, _ -> pending }
                .collect { pending ->
                    pending.forEach { processOne(it) }
                }
        }
    }

    /** Retry unresolved receipts when the signer becomes reachable after foregrounding. */
    fun retryPendingOnForeground() {
        if (collectorJob?.isActive == true) {
            foregroundGeneration.update { it + 1L }
        }
    }

    fun stop() {
        collectorJob?.cancel()
        collectorJob = null
        attemptsByReceipt.clear()
    }

    private fun reserveAttempt(receiptId: String): Boolean {
        val next = attemptsByReceipt.compute(receiptId) { _, current ->
            ((current ?: 0) + 1).coerceAtMost(MAX_DECRYPT_ATTEMPTS_PER_SESSION + 1)
        } ?: return false
        return next <= MAX_DECRYPT_ATTEMPTS_PER_SESSION
    }

    private suspend fun processOne(pending: com.unsilence.app.data.memory.PendingPrivateZapDecrypt) {
        // Derived-set emissions and foreground retries can race a successful patch.
        if (memoryEventStore.getVerifiedPrivateZap(pending.zapReceiptId) != null) {
            attemptsByReceipt.remove(pending.zapReceiptId)
            return
        }
        if (!reserveAttempt(pending.zapReceiptId)) return

        val plaintext = signingManager.decryptPrivateZap(
            ciphertext = pending.anonCiphertext,
            peerPubkeyHex = pending.anonSignerPubkey,
        )
        if (plaintext == null) {
            Log.i(TAG, "decrypt failed for zap ${pending.zapReceiptId.take(8)}")
            return
        }

        val parsed = authenticatePrivateZapPayload(plaintext, pending)
        if (parsed == null) {
            // Decryption succeeded, so this payload is permanently invalid.
            attemptsByReceipt[pending.zapReceiptId] = MAX_DECRYPT_ATTEMPTS_PER_SESSION
            Log.w(TAG, "rejected unauthenticated private zap payload ${pending.zapReceiptId.take(8)}")
            return
        }

        memoryEventStore.acceptVerifiedPrivateZap(
            zapReceiptId = pending.zapReceiptId,
            verified = parsed,
            targetId = pending.targetId,
        )
        attemptsByReceipt.remove(pending.zapReceiptId)
    }
}
