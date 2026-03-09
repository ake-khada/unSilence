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

private const val PREFS_FILE      = "unsilence_keys"
private const val KEY_PRIV_HEX    = "priv_hex"
private const val KEY_PUB_HEX     = "pub_hex"
private const val KEY_SIGNER_TYPE = "signer_type"
private const val SIGNER_AMBER    = "AMBER"

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

    /** True when logged in via Amber (pubkey only — no private key stored). */
    val isAmberMode: Boolean
        get() = prefs.getString(KEY_SIGNER_TYPE, null) == SIGNER_AMBER

    /** Returns true if the user is logged in (either internal key or Amber). */
    fun hasKey(): Boolean = prefs.contains(KEY_PRIV_HEX) || prefs.contains(KEY_PUB_HEX)

    /** Returns the stored private key as a 64-char lowercase hex string, or null. Null in Amber mode. */
    fun getPrivateKeyHex(): String? = prefs.getString(KEY_PRIV_HEX, null)

    /** Returns the public key hex: derived from privkey for internal mode, or stored directly for Amber. */
    fun getPublicKeyHex(): String? {
        if (isAmberMode) return prefs.getString(KEY_PUB_HEX, null)
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

    /**
     * Saves the public key returned by Amber and marks signer mode as AMBER.
     * No private key is stored.
     */
    fun saveAmberLogin(pubkeyHex: String) {
        prefs.edit()
            .putString(KEY_PUB_HEX, pubkeyHex.lowercase())
            .putString(KEY_SIGNER_TYPE, SIGNER_AMBER)
            .apply()
    }

    /** Removes all stored credentials (logout). */
    fun clear() {
        prefs.edit()
            .remove(KEY_PRIV_HEX)
            .remove(KEY_PUB_HEX)
            .remove(KEY_SIGNER_TYPE)
            .apply()
    }
}
