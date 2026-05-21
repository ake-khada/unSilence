package com.unsilence.app.ui.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.blossom.BlossomClient
import com.unsilence.app.data.blossom.BlossomServersStore
import com.unsilence.app.data.blossom.DEFAULT_BLOSSOM_SERVERS
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import javax.inject.Inject

data class ServerRow(
    val url: String,
    val displayName: String,
    val freeTierNote: String,
    val isSelected: Boolean,
)

sealed interface SmokeTestState {
    data object Idle : SmokeTestState
    data object Running : SmokeTestState
    data class Success(val url: String, val sha256: String, val sizeBytes: Long) : SmokeTestState
    data class Failure(val message: String) : SmokeTestState
}

@HiltViewModel
class MediaUploadSettingsViewModel @Inject constructor(
    private val blossomServersStore: BlossomServersStore,
    private val blossomClient: BlossomClient,
) : ViewModel() {

    val servers: StateFlow<List<ServerRow>> = combine(
        blossomServersStore.configuredServers,
        blossomServersStore.selectedServer,
    ) { configured, selected ->
        configured.map { url ->
            val info = DEFAULT_BLOSSOM_SERVERS.firstOrNull { it.url == url }
            ServerRow(
                url = url,
                displayName = info?.displayName ?: extractDisplayName(url),
                freeTierNote = info?.freeTierNote ?: "",
                isSelected = url == selected,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val imageMaxDim: StateFlow<Int> = blossomServersStore.imageMaxDim
    val imageQuality: StateFlow<Int> = blossomServersStore.imageQuality

    private val _smokeTestState = MutableStateFlow<SmokeTestState>(SmokeTestState.Idle)
    val smokeTestState: StateFlow<SmokeTestState> = _smokeTestState.asStateFlow()

    init {
        viewModelScope.launch { blossomServersStore.initialize() }
    }

    fun runSmokeTest() {
        if (_smokeTestState.value is SmokeTestState.Running) return
        _smokeTestState.value = SmokeTestState.Running
        viewModelScope.launch {
            val bytes = generateTestPng()
            val serverUrl = blossomServersStore.selectedServer.value
            blossomClient.upload(bytes, "image/png", serverUrl).fold(
                onSuccess = { blob ->
                    _smokeTestState.value = SmokeTestState.Success(
                        url = blob.url,
                        sha256 = blob.sha256,
                        sizeBytes = blob.sizeBytes,
                    )
                },
                onFailure = { ex ->
                    _smokeTestState.value = SmokeTestState.Failure(
                        message = ex.message ?: "Unknown error",
                    )
                },
            )
        }
    }

    private fun generateTestPng(): ByteArray {
        val bmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.DKGRAY)
        val paint = Paint().apply {
            color = Color.WHITE
            textSize = 14f
            isAntiAlias = true
        }
        canvas.drawText("unSilence ${System.currentTimeMillis()}", 4f, 54f, paint)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    fun selectServer(url: String) {
        viewModelScope.launch { blossomServersStore.setSelected(url) }
    }

    fun addServer(url: String) {
        viewModelScope.launch { blossomServersStore.addServer(url) }
    }

    fun removeServer(url: String) {
        viewModelScope.launch { blossomServersStore.removeServer(url) }
    }

    fun setImageMaxDim(px: Int) {
        viewModelScope.launch { blossomServersStore.setImageMaxDim(px) }
    }

    fun setImageQuality(q: Int) {
        viewModelScope.launch { blossomServersStore.setImageQuality(q) }
    }

    private fun extractDisplayName(url: String): String =
        url.removePrefix("https://").removePrefix("http://").trimEnd('/')
}
