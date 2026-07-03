package com.unsilence.app.data.wallet

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
class ZapPreferencesStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val activeOwner = MutableStateFlow<String?>(null)
    private val _state = MutableStateFlow(ZapPreferences.DEFAULT)
    val state: StateFlow<ZapPreferences> = _state.asStateFlow()

    init {
        scope.launch {
            context.zapPrefsDataStore.data
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
        context.zapPrefsDataStore.edit { prefs ->
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
        context.zapPrefsDataStore.edit { prefs ->
            prefs[defaultPrivateKey(owner)] = value
        }
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
}
