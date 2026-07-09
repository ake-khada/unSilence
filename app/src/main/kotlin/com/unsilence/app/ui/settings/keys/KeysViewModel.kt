package com.unsilence.app.ui.settings.keys

import android.content.Context
import androidx.lifecycle.ViewModel
import com.unsilence.app.data.auth.AmberSigner
import com.unsilence.app.data.auth.KeyManager
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class KeysUiState(
    val isAmberMode: Boolean = false,
    val amberInstalled: Boolean = false,
    val publicKeyHex: String? = null,
    val publicNpub: String? = null,
)

@HiltViewModel
class KeysViewModel @Inject constructor(
    private val keyManager: KeyManager,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _uiState = MutableStateFlow(buildState())
    val uiState: StateFlow<KeysUiState> = _uiState.asStateFlow()

    fun refreshAmberInstallState() {
        _uiState.update { it.copy(amberInstalled = AmberSigner.isInstalled(context)) }
    }

    fun privateKeyHexForReveal(): String? = keyManager.getPrivateKeyHex()

    private fun buildState(): KeysUiState {
        val pubHex = keyManager.getPublicKeyHex()
        return KeysUiState(
            isAmberMode = keyManager.isAmberMode,
            amberInstalled = AmberSigner.isInstalled(context),
            publicKeyHex = pubHex,
            publicNpub = pubHex?.hexToByteArray()?.toNpub(),
        )
    }
}
