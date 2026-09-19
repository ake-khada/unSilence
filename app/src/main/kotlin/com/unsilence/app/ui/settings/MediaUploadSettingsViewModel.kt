package com.unsilence.app.ui.settings

import androidx.lifecycle.ViewModel
import com.unsilence.app.ui.shared.PendingEdits
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.blossom.AttachmentQuality
import com.unsilence.app.data.blossom.BlossomServersStore
import com.unsilence.app.data.blossom.DEFAULT_BLOSSOM_SERVERS
import com.unsilence.app.data.blossom.VideoTranscoder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ServerRow(
    val url: String,
    val displayName: String,
    val freeTierNote: String,
    val isSelected: Boolean,
)

@HiltViewModel
class MediaUploadSettingsViewModel @Inject constructor(
    private val blossomServersStore: BlossomServersStore,
) : ViewModel() {
    internal val pendingEdits = PendingEdits(viewModelScope)


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

    val imageUploadTier: StateFlow<AttachmentQuality> = blossomServersStore.imageMaxDim
        .map(AttachmentQuality::fromImageMaxDimension)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            AttachmentQuality.STANDARD,
        )
    val videoQuality: StateFlow<VideoTranscoder.Quality> = blossomServersStore.videoQuality

    init {
        viewModelScope.launch { blossomServersStore.initialize() }
    }

    fun selectServer(url: String) {
        pendingEdits.launch { blossomServersStore.setSelected(url) }
    }

    fun addServer(url: String) {
        pendingEdits.launch { blossomServersStore.addServer(url) }
    }

    fun removeServer(url: String) {
        pendingEdits.launch { blossomServersStore.removeServer(url) }
    }

    fun setImageUploadTier(tier: AttachmentQuality) {
        pendingEdits.launch { blossomServersStore.setImageUploadTier(tier) }
    }

    fun setVideoQuality(quality: VideoTranscoder.Quality) {
        pendingEdits.launch { blossomServersStore.setVideoQuality(quality) }
    }

    private fun extractDisplayName(url: String): String =
        url.removePrefix("https://").removePrefix("http://").trimEnd('/')
}
