package com.unsilence.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.wallet.NwcManager
import com.unsilence.app.data.wallet.ZapPreferences
import com.unsilence.app.data.wallet.ZapPreferencesStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _balanceSats = MutableStateFlow<Long?>(null)
    val balanceSats: StateFlow<Long?> = _balanceSats.asStateFlow()

    fun refreshBalance() {
        if (!nwcManager.isConfigured) {
            _balanceSats.value = null
            return
        }
        viewModelScope.launch {
            val msats = nwcManager.getBalance()
            _balanceSats.value = msats?.let { it / 1000 }
        }
    }

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

    fun saveNwcUri(uri: String): Boolean {
        val saved = nwcManager.save(uri)
        if (saved) refreshBalance()
        return saved
    }

    fun disconnectWallet() {
        nwcManager.clear()
        _balanceSats.value = null
    }
}
