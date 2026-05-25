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
import kotlinx.coroutines.flow.map
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

    private val _state = MutableStateFlow(ZapPreferences.DEFAULT)
    val state: StateFlow<ZapPreferences> = _state.asStateFlow()

    init {
        scope.launch {
            context.zapPrefsDataStore.data
                .map { it.toZapPreferences() }
                .collect { _state.value = it }
        }
    }

    /** Synchronous snapshot — safe to call from a Compose click handler. */
    fun current(): ZapPreferences = _state.value

    suspend fun updatePreset(index: Int, amountSats: Long?, message: String?) {
        require(index in 0 until ZapPreferences.PRESET_COUNT)
        context.zapPrefsDataStore.edit { prefs ->
            if (amountSats != null) {
                prefs[longPreferencesKey("preset_${index}_amount")] = amountSats
            }
            if (message != null) {
                prefs[stringPreferencesKey("preset_${index}_message")] = message
            }
        }
    }

    suspend fun setDefaultPrivate(value: Boolean) {
        context.zapPrefsDataStore.edit { prefs ->
            prefs[booleanPreferencesKey("default_private")] = value
        }
    }

    private fun Preferences.toZapPreferences(): ZapPreferences {
        val presets = (0 until ZapPreferences.PRESET_COUNT).map { i ->
            val amount = this[longPreferencesKey("preset_${i}_amount")]
                ?: ZapPreferences.DEFAULT.presets[i].amountSats
            val message = this[stringPreferencesKey("preset_${i}_message")]
                ?.takeIf { it.isNotBlank() }
            ZapPreset(amount, message)
        }
        val privateMode = this[booleanPreferencesKey("default_private")] ?: false
        return ZapPreferences(presets, privateMode)
    }
}
