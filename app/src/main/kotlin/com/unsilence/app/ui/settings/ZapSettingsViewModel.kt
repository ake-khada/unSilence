package com.unsilence.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.wallet.NwcManager
import com.unsilence.app.data.wallet.ZapPreferences
import com.unsilence.app.data.wallet.ZapPreferencesStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ZapSettingsViewModel @Inject constructor(
    private val zapPreferencesStore: ZapPreferencesStore,
    private val nwcManager: NwcManager,
) : ViewModel() {
    val preferences: StateFlow<ZapPreferences> = zapPreferencesStore.state

    val walletConnected: Boolean get() = nwcManager.isConfigured
    val walletLabel: String? get() = nwcManager.connection()?.relayUrl
        ?.removePrefix("wss://")?.substringBefore("/")

    fun updatePreset(index: Int, amountSats: Long?, message: String?) {
        viewModelScope.launch {
            zapPreferencesStore.updatePreset(index, amountSats, message)
        }
    }

    fun setPrivacy(isPrivate: Boolean) {
        viewModelScope.launch {
            zapPreferencesStore.setDefaultPrivate(isPrivate)
        }
    }

    fun saveNwcUri(uri: String): Boolean = nwcManager.save(uri)

    fun disconnectWallet() { nwcManager.clear() }
}
