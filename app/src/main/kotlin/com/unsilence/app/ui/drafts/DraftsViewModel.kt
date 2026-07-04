package com.unsilence.app.ui.drafts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.drafts.Draft
import com.unsilence.app.data.drafts.DraftStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class DraftsViewModel @Inject constructor(
    private val keyManager: KeyManager,
    private val draftStore: DraftStore,
) : ViewModel() {
    private val pubkeyHex: String? = keyManager.getPublicKeyHex()

    val drafts: StateFlow<List<Draft>> = pubkeyHex?.let { pk ->
        draftStore.drafts
            .map { it[pk].orEmpty() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    } ?: MutableStateFlow(emptyList())

    fun delete(draft: Draft) {
        pubkeyHex?.let { draftStore.delete(it, draft.key) }
    }
}
