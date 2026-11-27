package com.unsilence.app.ui.settings

import androidx.lifecycle.ViewModel
import com.unsilence.app.ui.shared.PendingEdits
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.wallet.NwcManager
import com.unsilence.app.data.wallet.ZapPreferences
import com.unsilence.app.data.wallet.ZapPreferencesStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ZapSettingsViewModel @Inject constructor(
    private val zapPreferencesStore: ZapPreferencesStore,
    private val nwcManager: NwcManager,
) : ViewModel() {
    internal val pendingEdits = PendingEdits(viewModelScope)

    val preferences: StateFlow<ZapPreferences> = zapPreferencesStore.state

    val walletState = nwcManager.walletState

    val balanceSats = nwcManager.cachedBalanceMsats
        .map { msats -> msats?.div(1000L) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun refreshBalance() {
        viewModelScope.launch { nwcManager.getBalance() }
    }

    fun updatePreset(index: Int, amountSats: Long?, message: String?) {
        pendingEdits.launch {
            zapPreferencesStore.updatePreset(index, amountSats, message)
        }
    }

    fun setPrivacy(isPrivate: Boolean) {
        pendingEdits.launch {
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
    }
}
