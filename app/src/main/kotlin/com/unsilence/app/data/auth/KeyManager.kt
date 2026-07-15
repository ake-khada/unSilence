package com.unsilence.app.data.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub
import com.vitorpamplona.quartz.nip19Bech32.entities.NSec
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val PREFS_FILE      = "unsilence_keys"
private const val KEY_PRIV_HEX    = "priv_hex"
private const val KEY_PUB_HEX     = "pub_hex"
private const val KEY_SIGNER_TYPE = "signer_type"
private const val KEY_GRAPH_ONBOARDING_PENDING = "graph_onboarding_pending"
private const val KEY_GRAPH_ONBOARDING_COMPLETED = "graph_onboarding_completed"
private const val KEY_GRAPH_KNOWN_EMPTY = "graph_known_empty"
private const val SIGNER_AMBER    = "AMBER"

/** Minimal interface for mute-list decrypt — allows test construction without Android Context. */
interface MuteKeyProvider {
    val isAmberMode: Boolean get() = false
    fun getPrivateKeyHex(): String? = null
}

@Singleton
class KeyManager @Inject constructor(
    @ApplicationContext private val context: Context,
) : MuteKeyProvider {
    /** Cached derived pubkey — avoids secp256k1 math on every getPublicKeyHex() call. */
    @Volatile private var cachedPubKeyHex: String? = null

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
    override val isAmberMode: Boolean
        get() = prefs.getString(KEY_SIGNER_TYPE, null) == SIGNER_AMBER

    /** Returns true if the user is logged in (either internal key or Amber). */
    fun hasKey(): Boolean = prefs.contains(KEY_PRIV_HEX) || prefs.contains(KEY_PUB_HEX)

    /** True only for an identity created in unSilence whose graph step is unfinished. */
    fun isGraphOnboardingPending(): Boolean =
        prefs.getBoolean(KEY_GRAPH_ONBOARDING_PENDING, false)

    fun isGraphKnownEmpty(): Boolean = prefs.getBoolean(KEY_GRAPH_KNOWN_EMPTY, false)
    fun isGraphOnboardingCompleted(): Boolean =
        prefs.getBoolean(KEY_GRAPH_ONBOARDING_COMPLETED, false)

    fun completeGraphOnboarding(hasFollows: Boolean) {
        prefs.edit()
            .remove(KEY_GRAPH_ONBOARDING_PENDING)
            .putBoolean(KEY_GRAPH_ONBOARDING_COMPLETED, true)
            .apply {
                if (hasFollows) remove(KEY_GRAPH_KNOWN_EMPTY)
                else putBoolean(KEY_GRAPH_KNOWN_EMPTY, true)
            }
            .apply()
    }

    /** Returns the stored private key as a 64-char lowercase hex string, or null. Null in Amber mode. */
    override fun getPrivateKeyHex(): String? = prefs.getString(KEY_PRIV_HEX, null)

    /** Returns the public key hex: derived from privkey for internal mode, or stored directly for Amber. */
    fun getPublicKeyHex(): String? {
        if (isAmberMode) return prefs.getString(KEY_PUB_HEX, null)
        cachedPubKeyHex?.let { return it }
        val privHex = getPrivateKeyHex() ?: return null
        return KeyPair(privKey = privHex.hexToByteArray()).pubKey.toHexKey()
            .also { cachedPubKeyHex = it }
    }

    /**
     * Stores a private key (hex). Derives and caches nothing — pubkey is derived on demand.
     * Overwrites any existing key.
     */
    fun savePrivateKey(hexKey: String) {
        require(hexKey.length == 64) { "Private key must be 64 hex chars" }
        cachedPubKeyHex = null
        // Establish a clean internal-signer state: remove any Amber markers from a
        // prior session, else isAmberMode stays true and getPublicKeyHex() returns
        // the OLD Amber pubkey instead of deriving from this key. commit() (not
        // apply()) — bootstrap reads auth state immediately after and it must
        // survive process death.
        prefs.edit()
            .putString(KEY_PRIV_HEX, hexKey.lowercase())
            .remove(KEY_PUB_HEX)
            .remove(KEY_SIGNER_TYPE)
            .remove(KEY_GRAPH_ONBOARDING_PENDING)
            .remove(KEY_GRAPH_ONBOARDING_COMPLETED)
            .remove(KEY_GRAPH_KNOWN_EMPTY)
            .commit()
    }

    /**
     * Generates a fresh secp256k1 keypair via Quartz, persists the private key,
     * and returns the public key hex.
     */
    fun generateNewKey(): String {
        val keyPair = KeyPair()  // no args → Nip01.privKeyCreate() + pubKeyCreate()
        cachedPubKeyHex = null
        // Clean internal-signer state — drop any prior Amber markers (see savePrivateKey).
        prefs.edit()
            .putString(KEY_PRIV_HEX, keyPair.privKey!!.toHexKey())
            .remove(KEY_PUB_HEX)
            .remove(KEY_SIGNER_TYPE)
            .putBoolean(KEY_GRAPH_ONBOARDING_PENDING, true)
            .remove(KEY_GRAPH_ONBOARDING_COMPLETED)
            .remove(KEY_GRAPH_KNOWN_EMPTY)
            .commit()
        val pubHex = keyPair.pubKey.toHexKey()
        cachedPubKeyHex = pubHex
        return pubHex
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
    fun saveAmberLogin(pubkey: String) {
        // Amber may return npub1… (bech32) or raw hex — normalise to hex
        val hex = if (pubkey.startsWith("npub1", ignoreCase = true)) {
            (Nip19Parser.uriToRoute(pubkey)?.entity as? NPub)?.hex
                ?: error("Invalid npub from Amber: $pubkey")
        } else {
            pubkey
        }
        cachedPubKeyHex = null
        // Establish a clean Amber state: remove any stale private key, else
        // getSignerSync()/getPrivateKeyHex() would surface the PREVIOUS account's
        // private key while logged in via Amber (wrong-identity signing + a key the
        // user believed was logged out). commit() — bootstrap reads auth immediately.
        prefs.edit()
            .putString(KEY_PUB_HEX, hex.lowercase())
            .putString(KEY_SIGNER_TYPE, SIGNER_AMBER)
            .remove(KEY_PRIV_HEX)
            .remove(KEY_GRAPH_ONBOARDING_PENDING)
            .remove(KEY_GRAPH_ONBOARDING_COMPLETED)
            .remove(KEY_GRAPH_KNOWN_EMPTY)
            .commit()
    }

    /** Removes all stored credentials (logout). Uses commit() so the write is
     *  guaranteed to flush to disk before exitProcess(0) kills the process. */
    fun clear() {
        cachedPubKeyHex = null
        prefs.edit()
            .remove(KEY_PRIV_HEX)
            .remove(KEY_PUB_HEX)
            .remove(KEY_SIGNER_TYPE)
            .remove(KEY_GRAPH_ONBOARDING_PENDING)
            .remove(KEY_GRAPH_ONBOARDING_COMPLETED)
            .remove(KEY_GRAPH_KNOWN_EMPTY)
            .commit()
    }
}
