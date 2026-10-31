package com.unsilence.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.relay.RelayPreferencesStore
import com.unsilence.app.domain.model.AutomaticMediaMode
import com.unsilence.app.ui.shared.PendingEdits
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class MediaDataSettingsViewModel @Inject constructor(
    private val preferences: RelayPreferencesStore,
) : ViewModel() {
    internal val pendingEdits = PendingEdits(viewModelScope)
    val mediaPreferences = preferences.automaticMediaPreferences

    fun setVideoAutoplayMode(mode: AutomaticMediaMode) {
        pendingEdits.launch { preferences.setVideoAutoplayMode(mode) }
    }
    fun setImageLoadingMode(mode: AutomaticMediaMode) {
        pendingEdits.launch { preferences.setImageLoadingMode(mode) }
    }
    fun setLinkPreviewMode(mode: AutomaticMediaMode) {
        pendingEdits.launch { preferences.setLinkPreviewMode(mode) }
    }
}
