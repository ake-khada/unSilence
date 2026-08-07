package com.unsilence.app.data.repository

import android.util.Log
import com.unsilence.app.data.auth.SigningManager
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.zap.authenticatePrivateZapPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PrivateZapRepo"

@Singleton
class PrivateZapRepository @Inject constructor(
    private val signingManager: SigningManager,
    private val memoryEventStore: MemoryEventStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collectorJob: Job? = null

    fun start() {
        if (collectorJob?.isActive == true) return
        collectorJob = scope.launch {
            memoryEventStore.pendingPrivateZapDecrypts.collect { pending ->
                processOne(pending)
            }
        }
    }

    fun stop() {
        collectorJob?.cancel()
        collectorJob = null
    }

    private suspend fun processOne(pending: com.unsilence.app.data.memory.PendingPrivateZapDecrypt) {
        // Skip if already decrypted (rescans + live arrival can race).
        if (memoryEventStore.getVerifiedPrivateZap(pending.zapReceiptId) != null) return

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
            Log.w(TAG, "rejected unauthenticated private zap payload ${pending.zapReceiptId.take(8)}")
            return
        }

        memoryEventStore.acceptVerifiedPrivateZap(
            zapReceiptId = pending.zapReceiptId,
            verified = parsed,
            targetId = pending.targetId,
        )
    }
}
