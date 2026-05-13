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
        val pubkey = keyManager.getPublicKeyHex() ?: return null

        val current = signer
        if (current != null && current.pubKey == pubkey) {
            val expectAmber = keyManager.isAmberMode
            val isAmber = current is NostrSignerExternal
            if (expectAmber == isAmber) return current
        }

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
     * Encrypt plaintext via NIP-44 v2. Self-encrypt pattern: peerPubkey == own pubkey.
     * Internal signer: sync Nip44 crypto. External (Amber): NIP-55 intent.
     * Always NIP-44 v2 — no NIP-04 fallback for new data.
     */
    suspend fun encrypt(plaintext: String, peerPubkeyHex: String): String? {
        val s = getOrCreateSigner() ?: return null

        val result = try {
            s.nip44Encrypt(plaintext, peerPubkeyHex)
        } catch (_: Throwable) {
            return null
        } ?: return null

        if (!isValidNip44V2Ciphertext(result)) return null

        return result
    }

    /**
     * Decrypt ciphertext via the active signer (NIP-44 first, NIP-04 fallback).
     * Works for both internal (direct crypto) and external (Amber intent) signers.
     */
    suspend fun decrypt(ciphertext: String, peerPubkeyHex: String): String? {
        val s = getOrCreateSigner() ?: return null

        if (!isValidNip44V2Ciphertext(ciphertext) && !isValidNip04Ciphertext(ciphertext)) {
            return null
        }

        val result = try {
            s.nip44Decrypt(ciphertext, peerPubkeyHex)
        } catch (_: Throwable) {
            null
        } ?: try {
            s.nip04Decrypt(ciphertext, peerPubkeyHex)
        } catch (_: Throwable) {
            return null
        } ?: return null

        // Catch Amber error-string returns
        if (result.startsWith("Could not", ignoreCase = true) ||
            result.startsWith("Error", ignoreCase = true)) {
            return null
        }

        return result
    }

    /**
     * Self-test: encrypt a canary then decrypt it. Returns true iff round-trip succeeds.
     * Used at bootstrap and after Amber re-authorization to verify crypto path works.
     */
    suspend fun encryptRoundTrip(): Boolean {
        val ownPubkey = keyManager.getPublicKeyHex() ?: return false
        val canary = """[["p","probe-${System.currentTimeMillis()}"]]"""
        val ct = encrypt(canary, ownPubkey) ?: return false
        val pt = decrypt(ct, ownPubkey) ?: return false
        return pt == canary
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

    companion object {
        /**
         * Validate NIP-44 v2 wire format per spec:
         *  - base64 alphabet (standard, with optional trailing `=` padding)
         *  - length 132–87472 chars
         *  - decodes to 99–65603 bytes
         *  - first decoded byte is version 0x02
         */
        internal fun isValidNip44V2Ciphertext(s: String): Boolean {
            if (s.length !in 132..87472) return false
            if (!BASE64_REGEX.matches(s)) return false
            return try {
                val bytes = java.util.Base64.getDecoder().decode(s)
                bytes.size in 99..65603 && bytes[0] == 0x02.toByte()
            } catch (_: IllegalArgumentException) {
                false
            }
        }

        /**
         * NIP-04 wire format: `<base64-ciphertext>?iv=<base64-iv>`
         */
        internal fun isValidNip04Ciphertext(s: String): Boolean {
            val idx = s.indexOf("?iv=")
            if (idx <= 0 || idx >= s.length - 4) return false
            val ct = s.substring(0, idx)
            val iv = s.substring(idx + 4)
            return BASE64_REGEX.matches(ct) && BASE64_REGEX.matches(iv)
        }

        private val BASE64_REGEX = Regex("^[A-Za-z0-9+/]+={0,2}$")
    }
}
