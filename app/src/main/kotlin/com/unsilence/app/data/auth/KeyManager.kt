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
import java.math.BigInteger
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

/** secp256k1 curve order N — a private key is only valid as a scalar in [1, N). */
private val SECP256K1_ORDER =
    BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)

/** True only for a canonical 32-byte secp256k1 private scalar in [1, N). */
internal fun isValidPrivateKeyScalar(hexKey: String): Boolean {
    if (hexKey.length != 64 || !hexKey.all(Char::isHexDigit)) return false
    val scalar = runCatching { BigInteger(hexKey, 16) }.getOrNull() ?: return false
    return scalar >= BigInteger.ONE && scalar < SECP256K1_ORDER
}

private fun Char.isHexDigit(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

internal fun normalizeAmberPubkey(input: String): String? {
    if (input.length == 64 && input.all(Char::isHexDigit)) return input.lowercase()
    if (input.length != 63 || !input.startsWith("npub1", ignoreCase = true)) return null
    val pub = Nip19Parser.uriToRoute(input.lowercase())?.entity as? NPub ?: return null
    if (pub.hex.length != 64) return null
    // The URI parser also accepts keys embedded in prose. Require the entire checksummed key.
    val canonical = NPub.create(pub.hex)
    return pub.hex.takeIf { input == canonical || input == canonical.uppercase() }
}

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
     * Overwrites any existing key; returns whether the encrypted preferences were committed.
     */
    fun savePrivateKey(hexKey: String): Boolean {
        require(isValidPrivateKeyScalar(hexKey)) {
            "Private key must be a valid 32-byte secp256k1 scalar"
        }
        cachedPubKeyHex = null
        // Establish a clean internal-signer state: remove any Amber markers from a
        // prior session, else isAmberMode stays true and getPublicKeyHex() returns
        // the OLD Amber pubkey instead of deriving from this key. commit() (not
        // apply()) — bootstrap reads auth state immediately after and it must
        // survive process death.
        return prefs.edit()
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
     * Returns true only after a valid key is committed; false for invalid input or a failed save.
     */
    fun importKey(input: String): Boolean {
        val trimmed = input.trim()
        val hexKey: String? = when {
            // Raw hex — 64 lowercase/uppercase hex chars
            trimmed.length == 64 && trimmed.all(Char::isHexDigit) ->
                trimmed.lowercase()
            // nsec1… bech32 — let Quartz decode it
            trimmed.startsWith("nsec1", ignoreCase = true) -> {
                val parsed = Nip19Parser.uriToRoute(trimmed)
                (parsed?.entity as? NSec)?.hex
            }
            else -> null
        }
        // Quartz normally returns canonical hex for nsec, but validate the decoded
        // value instead of trusting that implementation detail. The helper is
        // deliberately non-throwing for malformed decoder output.
        if (hexKey == null || !isValidPrivateKeyScalar(hexKey)) return false
        return savePrivateKey(hexKey)
    }

    /** Finish a signed-out onboarding request. A late result cannot replace an active account. */
    fun signInWithAmber(pubkey: String): Boolean {
        try {
            if (hasKey()) return false
            if (saveAmberLogin(pubkey)) return true
            // Even a failed disk commit changes SharedPreferences in memory. Bootstrap reads
            // hasKey() when the activity reopens, so restore the signed-out state here.
            clear()
        } catch (_: Exception) {
            // The encrypted store can also be unavailable during lookup or cleanup.
        }
        return false
    }

    internal fun saveAmberLogin(pubkey: String): Boolean {
        val hex = normalizeAmberPubkey(pubkey) ?: return false
        cachedPubKeyHex = null
        return try {
            prefs.edit()
                .putString(KEY_PUB_HEX, hex)
                .putString(KEY_SIGNER_TYPE, SIGNER_AMBER)
                .remove(KEY_PRIV_HEX)
                .remove(KEY_GRAPH_ONBOARDING_PENDING)
                .remove(KEY_GRAPH_ONBOARDING_COMPLETED)
                .remove(KEY_GRAPH_KNOWN_EMPTY)
                .commit()
        } catch (_: Exception) {
            false
        }
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
