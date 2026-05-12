package com.unsilence.app.data.auth

import android.content.ContentResolver
import android.content.Intent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip55AndroidSigner.client.NostrSignerExternal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val AMBER_PACKAGE = "com.greenart7c3.nostrsigner"

@Singleton
class SigningManager @Inject constructor(
    private val keyManager: KeyManager,
    private val contentResolver: ContentResolver,
) {
    @Volatile
    private var signer: NostrSigner? = null

    @Synchronized
    private fun getOrCreateSigner(): NostrSigner? {
        signer?.let { return it }

        val pubkey = keyManager.getPublicKeyHex() ?: return null

        val newSigner = if (keyManager.isAmberMode) {
            NostrSignerExternal(pubkey, AMBER_PACKAGE, contentResolver)
        } else {
            val privKeyHex = keyManager.getPrivateKeyHex() ?: return null
            NostrSignerInternal(KeyPair(privKey = privKeyHex.hexToByteArray()))
        }

        signer = newSigner
        return newSigner
    }

    suspend fun <T : Event> sign(template: EventTemplate<T>): T? {
        val s = getOrCreateSigner() ?: return null
        return if (s is NostrSignerExternal) {
            runCatching { s.sign(template) }.getOrNull()
        } else {
            withContext(Dispatchers.Default) {
                runCatching { s.sign(template) }.getOrNull()
            }
        }
    }

    /**
     * Decrypt ciphertext via the active signer (NIP-44 first, NIP-04 fallback).
     * Works for both internal (direct crypto) and external (Amber intent) signers.
     */
    suspend fun decrypt(ciphertext: String, peerPubkeyHex: String): String? {
        val s = getOrCreateSigner() ?: return null
        return runCatching { s.nip44Decrypt(ciphertext, peerPubkeyHex) }.getOrNull()
            ?: runCatching { s.nip04Decrypt(ciphertext, peerPubkeyHex) }.getOrNull()
    }

    /**
     * Encrypt plaintext via NIP-44 v2. Self-encrypt pattern: peerPubkey == own pubkey.
     * Internal signer: sync Nip44 crypto. External (Amber): NIP-55 intent.
     * Always NIP-44 v2 — no NIP-04 fallback for new data.
     */
    suspend fun encrypt(plaintext: String, peerPubkeyHex: String): String? {
        val s = getOrCreateSigner() ?: return null
        return runCatching { s.nip44Encrypt(peerPubkeyHex, plaintext) }.getOrNull()
    }

    fun registerLauncher(launcher: (Intent) -> Unit) {
        if (keyManager.isAmberMode) {
            val s = getOrCreateSigner()
            (s as? NostrSignerExternal)?.registerForegroundLauncher(launcher)
        }
    }

    fun unregisterLauncher(launcher: (Intent) -> Unit) {
        (signer as? NostrSignerExternal)?.unregisterForegroundLauncher(launcher)
    }

    fun onAmberResult(data: Intent) {
        (signer as? NostrSignerExternal)?.newResponse(data)
    }

    fun clear() {
        signer = null
    }
}
