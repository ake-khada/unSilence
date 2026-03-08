package com.unsilence.app.data.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NSec
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val PREFS_FILE   = "unsilence_keys"
private const val KEY_PRIV_HEX = "priv_hex"

@Singleton
class KeyManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** Returns true if a private key is stored. */
    fun hasKey(): Boolean = prefs.contains(KEY_PRIV_HEX)

    /** Returns the stored private key as a 64-char lowercase hex string, or null. */
    fun getPrivateKeyHex(): String? = prefs.getString(KEY_PRIV_HEX, null)

    /** Derives and returns the public key hex from the stored private key, or null. */
    fun getPublicKeyHex(): String? {
        val privHex = getPrivateKeyHex() ?: return null
        return KeyPair(privKey = privHex.hexToByteArray()).pubKey.toHexKey()
    }

    /**
     * Stores a private key (hex). Derives and caches nothing — pubkey is derived on demand.
     * Overwrites any existing key.
     */
    fun savePrivateKey(hexKey: String) {
        require(hexKey.length == 64) { "Private key must be 64 hex chars" }
        prefs.edit().putString(KEY_PRIV_HEX, hexKey.lowercase()).apply()
    }

    /**
     * Generates a fresh secp256k1 keypair via Quartz, persists the private key,
     * and returns the public key hex.
     */
    fun generateNewKey(): String {
        val keyPair = KeyPair()  // no args → Nip01.privKeyCreate() + pubKeyCreate()
        prefs.edit().putString(KEY_PRIV_HEX, keyPair.privKey!!.toHexKey()).apply()
        return keyPair.pubKey.toHexKey()
    }

    /**
     * Accepts either a 64-char hex private key or an nsec1… bech32 string.
     * Returns true and saves on success; returns false if the input is unrecognisable.
     */
    fun importKey(input: String): Boolean {
        val trimmed = input.trim()
        val hexKey: String? = when {
            // Raw hex — 64 lowercase/uppercase hex chars
            trimmed.length == 64 && trimmed.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' } ->
                trimmed.lowercase()
            // nsec1… bech32 — let Quartz decode it
            trimmed.startsWith("nsec1", ignoreCase = true) -> {
                val parsed = Nip19Parser.uriToRoute(trimmed)
                (parsed?.entity as? NSec)?.hex
            }
            else -> null
        }
        if (hexKey == null) return false
        savePrivateKey(hexKey)
        return true
    }

    /** Removes the stored private key (logout). */
    fun clear() {
        prefs.edit().remove(KEY_PRIV_HEX).apply()
    }
}
