package com.unsilence.app.data.memory

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PendingMuteJournal"
private const val PREFS_FILE = "unsilence_pending_mutes"

/**
 * Tiny, encrypted crash journal for unacknowledged kind-10000 edits.
 *
 * [persist] uses a synchronous commit deliberately: a mute action is not
 * reported as durable until Android has accepted the small encrypted write.
 * This avoids rewriting the multi-megabyte MES snapshot for every edit and
 * closes the force-stop window left by an asynchronous snapshot request.
 */
@Singleton
class PendingMuteJournalStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
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

    @Synchronized
    internal fun load(ownerPubkey: String): PendingMutePublish? {
        val encoded = runCatching { prefs.getString(key(ownerPubkey), null) }
            .onFailure { Log.e(TAG, "Could not read pending mute journal", it) }
            .getOrNull()
            ?: return null
        val decoded = PendingMuteJournalCodec.decode(encoded, ownerPubkey)
        if (decoded == null) {
            Log.e(TAG, "Discarding invalid pending mute journal")
            clear(ownerPubkey)
        }
        return decoded
    }

    @Synchronized
    internal fun persist(pending: PendingMutePublish): Boolean {
        val encoded = PendingMuteJournalCodec.encode(pending) ?: return false
        return runCatching {
            prefs.edit().putString(key(pending.ownerPubkey), encoded).commit()
        }.onFailure {
            Log.e(TAG, "Could not persist pending mute journal", it)
        }.getOrDefault(false)
    }

    @Synchronized
    fun clear(ownerPubkey: String): Boolean = runCatching {
        prefs.edit().remove(key(ownerPubkey)).commit()
    }.onFailure {
        Log.e(TAG, "Could not clear pending mute journal", it)
    }.getOrDefault(false)

    /** Remove only the journal represented by the accepted publish. */
    @Synchronized
    internal fun clearIfMatches(expected: PendingMutePublish): Boolean {
        val encoded = runCatching { prefs.getString(key(expected.ownerPubkey), null) }
            .getOrNull()
            ?: return true
        val current = PendingMuteJournalCodec.decode(encoded, expected.ownerPubkey)
        if (current != expected) return true
        return clear(expected.ownerPubkey)
    }

    private fun key(ownerPubkey: String): String = "pending:$ownerPubkey"
}
