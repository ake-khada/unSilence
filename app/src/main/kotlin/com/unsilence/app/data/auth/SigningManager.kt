package com.unsilence.app.data.auth

import android.content.ContentResolver
import android.content.Intent
import android.util.Log
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip19Bech32.bech32.Bech32
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip55AndroidSigner.client.NostrSignerExternal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArraySet
import javax.inject.Inject
import javax.inject.Singleton

private const val AMBER_PACKAGE = "com.greenart7c3.nostrsigner"
private const val TAG = "SigningManager"

@Singleton
class SigningManager @Inject constructor(
    private val keyManager: KeyManager,
    private val contentResolver: ContentResolver,
) {
    @Volatile
    private var signer: NostrSigner? = null

    /** Launcher registrations that survive signer rebuilds (logout/relogin). */
    private val registeredLaunchers = CopyOnWriteArraySet<(Intent) -> Unit>()

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

        if (newSigner is NostrSignerExternal && registeredLaunchers.isNotEmpty()) {
            for (l in registeredLaunchers) {
                newSigner.registerForegroundLauncher(l)
            }
        }

        signer = newSigner
        return newSigner
    }

    /**
     * Return a synchronous signer for Quartz APIs that require [NostrSignerSync]
     * (e.g., NIP-57 private zap requests). Only available in internal signing
     * mode; returns null in Amber mode (no private key access).
     */
    fun getSignerSync(): NostrSignerSync? {
        val privKeyHex = keyManager.getPrivateKeyHex() ?: return null
        return NostrSignerSync(KeyPair(privKey = privKeyHex.hexToByteArray()))
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
     * Decrypt a NIP-57 private zap anon-tag ciphertext. Tries NIP-44 → NIP-04
     * via the standard path, then falls back to unwrapping Quartz's bech32
     * pzap1…_iv1… format (used by Amethyst/Damus) into NIP-04 wire format
     * and retrying decrypt. Works for both nsec and Amber modes.
     *
     * Returns null only when all formats fail.
     */
    suspend fun decryptPrivateZap(ciphertext: String, peerPubkeyHex: String): String? {
        // Path 1+2: standard NIP-44/NIP-04.
        decrypt(ciphertext, peerPubkeyHex)?.let { return it }

        // Path 3: Quartz bech32 — unwrap to NIP-04 and decrypt directly.
        // Must call nip04Decrypt without trying nip44 first, because Amber
        // returns "Could not decrypt" as a non-null string from nip44Decrypt,
        // which blocks the NIP-04 fallback in decrypt().
        val nip04 = unwrapPzapBech32(ciphertext) ?: return null
        val s = getOrCreateSigner() ?: return null
        val result = try {
            s.nip04Decrypt(nip04, peerPubkeyHex)
        } catch (_: Throwable) {
            null
        } ?: return null
        if (result.startsWith("Could not", ignoreCase = true) ||
            result.startsWith("Error", ignoreCase = true)) {
            return null
        }
        return result
    }

    /**
     * Unwrap Quartz's bech32 private zap format (pzap1<ct>_iv1<iv>) into
     * standard NIP-04 wire format (base64(ct)?iv=base64(iv)).
     */
    private fun unwrapPzapBech32(ciphertext: String): String? {
        val sep = ciphertext.indexOf('_')
        if (sep < 0) return null
        val pzapPart = ciphertext.substring(0, sep)
        val ivPart = ciphertext.substring(sep + 1)
        if (!pzapPart.startsWith("pzap1", ignoreCase = true)) return null
        if (!ivPart.startsWith("iv1", ignoreCase = true)) return null

        val ctBytes = try {
            Bech32.decodeBytes(pzapPart, false).second
        } catch (e: Exception) {
            Log.w(TAG, "pzap1 bech32 decode failed: ${e.message}")
            return null
        }
        val ivBytes = try {
            Bech32.decodeBytes(ivPart, false).second
        } catch (e: Exception) {
            Log.w(TAG, "iv1 bech32 decode failed: ${e.message}")
            return null
        }

        val encoder = java.util.Base64.getEncoder()
        return "${encoder.encodeToString(ctBytes)}?iv=${encoder.encodeToString(ivBytes)}"
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
        registeredLaunchers.add(launcher)
        (signer as? NostrSignerExternal)?.registerForegroundLauncher(launcher)
    }

    fun unregisterLauncher(launcher: (Intent) -> Unit) {
        registeredLaunchers.remove(launcher)
        (signer as? NostrSignerExternal)?.unregisterForegroundLauncher(launcher)
    }

    fun onAmberResult(data: Intent) {
        (signer as? NostrSignerExternal)?.newResponse(data)
    }

    fun clear() {
        // Intentionally keep registeredLaunchers — the Activity composition
        // stays alive across logout/relogin, so DisposableEffect won't re-fire.
        // getOrCreateSigner() re-applies them to the next external signer.
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
