package com.unsilence.app.ui.profile

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.blossom.BlossomImageUploader
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.repository.EditableProfileMetadata
import com.unsilence.app.data.repository.ProfileMetadataPublisher
import com.unsilence.app.data.repository.ProfilePublishResult
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import com.unsilence.app.ui.shared.EditorImageResult

private const val TAG = "ProfileEditorVM"

sealed interface ProfileSaveState {
    data object Idle : ProfileSaveState
    data object Saving : ProfileSaveState
    data object Saved : ProfileSaveState
    data class Failed(val message: String) : ProfileSaveState
}

/** Entry-owned editing and upload work; opening a form never starts a profile timeline. */
@HiltViewModel
class ProfileEditorViewModel @Inject constructor(
    keyManager: KeyManager,
    private val profileMetadataPublisher: ProfileMetadataPublisher,
    private val memoryEventStore: MemoryEventStore,
    private val blossomImageUploader: BlossomImageUploader,
) : ViewModel() {
    val pubkeyHex: String? = keyManager.getPublicKeyHex()
    val userFlow: Flow<UserEntity?> =
        pubkeyHex?.let(memoryEventStore::userEntityFlow) ?: emptyFlow()

    private val _uploadingAvatar = MutableStateFlow(false)
    val uploadingAvatar: StateFlow<Boolean> = _uploadingAvatar.asStateFlow()

    private val _uploadingBanner = MutableStateFlow(false)
    val uploadingBanner: StateFlow<Boolean> = _uploadingBanner.asStateFlow()

    private val _profileSaveState = MutableStateFlow<ProfileSaveState>(ProfileSaveState.Idle)
    val profileSaveState: StateFlow<ProfileSaveState> = _profileSaveState.asStateFlow()
    private var profileSaveJob: Job? = null
    private val profileSaveGeneration = AtomicLong(0L)
    private val imageResults = Channel<EditorImageResult>(Channel.BUFFERED)
    internal val imageUploadResults = imageResults.receiveAsFlow()

    fun uploadProfileImage(
        uri: Uri,
        isBanner: Boolean,
    ) {
        val loading = if (isBanner) _uploadingBanner else _uploadingAvatar
        if (!loading.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val maxDim = if (isBanner) 1600 else 512
                val url = blossomImageUploader.upload(uri, maxDimension = maxDim)
                imageResults.send(EditorImageResult.Uploaded(url, isBanner))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.w(TAG, "Profile image upload failed", e)
                imageResults.send(EditorImageResult.Failed(e.message ?: "Upload failed"))
            } finally {
                loading.value = false
            }
        }
    }

    internal fun saveProfile(
        original: EditableProfileMetadata,
        edited: EditableProfileMetadata,
    ) {
        if (!_profileSaveState.compareAndSet(ProfileSaveState.Idle, ProfileSaveState.Saving)) return
        val generation = profileSaveGeneration.incrementAndGet()
        val ownPubkey = pubkeyHex
        if (ownPubkey == null) {
            _profileSaveState.value = profileSaveStateFor(ProfilePublishResult.AccountUnavailable)
            return
        }

        profileSaveJob = viewModelScope.launch(Dispatchers.IO) {
            val result = profileMetadataPublisher.publish(ownPubkey, original, edited)
            // SigningManager deliberately converts Amber cancellation to null.
            // The generation fence prevents that late result from resurrecting
            // a failed state after the user has already cancelled and left.
            if (profileSaveGeneration.get() == generation) {
                _profileSaveState.value = profileSaveStateFor(result)
            }
        }
    }

    fun consumeProfileSaveResult() {
        _profileSaveState.update { state ->
            if (state == ProfileSaveState.Saving) state else ProfileSaveState.Idle
        }
    }

    fun cancelProfileSave() {
        profileSaveGeneration.incrementAndGet()
        profileSaveJob?.cancel()
        profileSaveJob = null
        _profileSaveState.value = ProfileSaveState.Idle
    }


    override fun onCleared() {
        cancelProfileSave()
        super.onCleared()
    }
}

internal fun profileSaveStateFor(result: ProfilePublishResult): ProfileSaveState = when (result) {
    ProfilePublishResult.Success -> ProfileSaveState.Saved
    ProfilePublishResult.AccountUnavailable ->
        ProfileSaveState.Failed("No signing account is available.")
    ProfilePublishResult.FreshnessUnavailable ->
        ProfileSaveState.Failed("Could not refresh your latest profile from relays. Nothing was changed.")
    ProfilePublishResult.ProfileUnavailable ->
        ProfileSaveState.Failed("Your profile has not loaded yet. Connect and try again.")
    ProfilePublishResult.InvalidExistingProfile ->
        ProfileSaveState.Failed("Your existing profile could not be read safely. Nothing was changed.")
    ProfilePublishResult.SigningFailed ->
        ProfileSaveState.Failed("Profile signing was cancelled or failed.")
    ProfilePublishResult.ChangedWhileSigning ->
        ProfileSaveState.Failed("Your profile changed while signing. Review it and try again.")
    ProfilePublishResult.NoRelayAccepted ->
        ProfileSaveState.Failed("No relay accepted the profile update. Check your connection and try again.")
    ProfilePublishResult.SupersededAfterAcceptance ->
        ProfileSaveState.Failed("A newer profile update arrived. Reload and try again.")
}
