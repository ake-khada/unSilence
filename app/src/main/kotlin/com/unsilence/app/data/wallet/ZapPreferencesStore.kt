package com.unsilence.app.data.wallet

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private val Context.zapPrefsDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "zap_preferences")

data class ZapPreset(val amountSats: Long, val message: String?)

data class ZapPreferences(
    val presets: List<ZapPreset>,
    val defaultPrivate: Boolean,
) {
    companion object {
        val DEFAULT = ZapPreferences(
            presets = listOf(
                ZapPreset(21L, null),
                ZapPreset(100L, null),
                ZapPreset(500L, null),
                ZapPreset(1_000L, null),
                ZapPreset(5_000L, null),
            ),
            defaultPrivate = false,
        )
        const val PRESET_COUNT = 5
    }
}

@Singleton
class ZapPreferencesStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        dataStore = context.zapPrefsDataStore,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    private val activeOwner = MutableStateFlow<String?>(null)
    private val _state = MutableStateFlow(ZapPreferences.DEFAULT)
    val state: StateFlow<ZapPreferences> = _state.asStateFlow()

    init {
        scope.launch {
            dataStore.data
                .combine(activeOwner) { prefs, owner -> prefs.toZapPreferences(owner) }
                .collect { _state.value = it }
        }
    }

    /** Synchronous snapshot — safe to call from a Compose click handler. */
    fun current(): ZapPreferences = _state.value

    fun selectOwner(pubkeyHex: String) {
        activeOwner.value = pubkeyHex.lowercase()
    }

    fun clearActiveOwner() {
        activeOwner.value = null
        _state.value = ZapPreferences.DEFAULT
    }

    suspend fun updatePreset(index: Int, amountSats: Long?, message: String?) {
        require(index in 0 until ZapPreferences.PRESET_COUNT)
        val owner = activeOwner.value ?: return
        dataStore.edit { prefs ->
            if (amountSats != null) {
                prefs[amountKey(owner, index)] = amountSats
            }
            if (message != null) {
                prefs[messageKey(owner, index)] = message
            }
        }
    }

    suspend fun setDefaultPrivate(value: Boolean) {
        val owner = activeOwner.value ?: return
        dataStore.edit { prefs ->
            prefs[defaultPrivateKey(owner)] = value
        }
    }

    suspend fun trustedZapperPubkeys(ownerPubkey: String): Set<String> {
        val owner = ownerPubkey.trim().lowercase()
        return dataStore.data.first()[trustedZapperPubkeysKey(owner)]
            .orEmpty()
            .mapNotNullTo(LinkedHashSet(), ::normalizedZapperPubkey)
    }

    /** Atomically retain every HTTPS-discovered zapper key previously seen for this owner. */
    suspend fun rememberTrustedZapperPubkeys(
        ownerPubkey: String,
        freshPubkeys: Set<String>,
    ): Set<String> {
        val owner = ownerPubkey.trim().lowercase()
        val fresh = freshPubkeys.mapNotNullTo(LinkedHashSet(), ::normalizedZapperPubkey)
        var merged: Set<String> = emptySet()
        dataStore.edit { prefs ->
            val existing = prefs[trustedZapperPubkeysKey(owner)]
                .orEmpty()
                .mapNotNullTo(LinkedHashSet(), ::normalizedZapperPubkey)
            merged = existing + fresh
            prefs[trustedZapperPubkeysKey(owner)] = merged
        }
        return merged
    }

    private fun Preferences.toZapPreferences(owner: String?): ZapPreferences {
        if (owner == null) return ZapPreferences.DEFAULT
        val presets = (0 until ZapPreferences.PRESET_COUNT).map { i ->
            val amount = this[amountKey(owner, i)]
                ?: ZapPreferences.DEFAULT.presets[i].amountSats
            val message = this[messageKey(owner, i)]
                ?.takeIf { it.isNotBlank() }
            ZapPreset(amount, message)
        }
        val privateMode = this[defaultPrivateKey(owner)] ?: false
        return ZapPreferences(presets, privateMode)
    }

    private fun amountKey(owner: String, index: Int) =
        longPreferencesKey("${owner}_preset_${index}_amount")

    private fun messageKey(owner: String, index: Int) =
        stringPreferencesKey("${owner}_preset_${index}_message")

    private fun defaultPrivateKey(owner: String) =
        booleanPreferencesKey("${owner}_default_private")

    private fun trustedZapperPubkeysKey(owner: String) =
        stringSetPreferencesKey("${owner}_trusted_zapper_pubkeys")
}

private fun normalizedZapperPubkey(value: String): String? = value.trim().lowercase()
    .takeIf { pubkey -> pubkey.length == 64 && pubkey.all { it in '0'..'9' || it in 'a'..'f' } }
