package com.unsilence.app.ui.profile

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.repository.EditableProfileMetadata
import com.unsilence.app.data.repository.profileMetadataHasChanges
import com.unsilence.app.ui.common.IdentIcon
import com.unsilence.app.ui.common.rememberAvatarImageRequest
import com.unsilence.app.ui.common.rememberFullWidthImageRequest
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.BorderSubtle
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.ui.theme.Text3
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Surface1
import com.unsilence.app.ui.theme.TextSecondary

private val EDIT_AVATAR_SIZE = 72.dp
private val EDIT_BANNER_HEIGHT = 100.dp

internal fun profileMetadataForEdit(user: UserEntity?): EditableProfileMetadata =
    EditableProfileMetadata(
        name = user?.name ?: "",
        displayName = user?.displayName ?: "",
        about = user?.about ?: "",
        picture = user?.picture ?: "",
        banner = user?.banner ?: "",
        nip05 = user?.nip05 ?: "",
        lud16 = user?.lud16 ?: "",
        website = user?.website ?: "",
    )

@Composable
fun EditProfileScreen(
    viewModel: ProfileViewModel,
    onDismiss: () -> Unit,
) {
    val user by viewModel.userFlow.collectAsStateWithLifecycle(initialValue = null)
    val uploadingAvatar by viewModel.uploadingAvatar.collectAsStateWithLifecycle()
    val uploadingBanner by viewModel.uploadingBanner.collectAsStateWithLifecycle()
    val saveState by viewModel.profileSaveState.collectAsStateWithLifecycle()
    val showSnackbar = com.unsilence.app.ui.common.LocalShowSnackbar.current
    val isSaving = saveState == ProfileSaveState.Saving

    // Start with a legitimate blank baseline so a never-published key can edit.
    // If cached/relay metadata arrives before the user types, hydrate it below.
    var name         by remember { mutableStateOf("") }
    var displayName  by remember { mutableStateOf("") }
    var about        by remember { mutableStateOf("") }
    var picture      by remember { mutableStateOf("") }
    var bannerUrl    by remember { mutableStateOf("") }
    var nip05        by remember { mutableStateOf("") }
    var lud16        by remember { mutableStateOf("") }
    var website      by remember { mutableStateOf("") }
    var original     by remember { mutableStateOf(profileMetadataForEdit(null)) }

    val edited = EditableProfileMetadata(
        name = name,
        displayName = displayName,
        about = about,
        picture = picture,
        banner = bannerUrl,
        nip05 = nip05,
        lud16 = lud16,
        website = website,
    )

    LaunchedEffect(user) {
        val loaded = user?.let(::profileMetadataForEdit) ?: return@LaunchedEffect
        // Never replace text already entered into a provisional blank form.
        if (profileMetadataHasChanges(original, edited)) return@LaunchedEffect
        original = loaded
        name        = loaded.name
        displayName = loaded.displayName
        about       = loaded.about
        picture     = loaded.picture
        bannerUrl   = loaded.banner
        nip05       = loaded.nip05
        lud16       = loaded.lud16
        website     = loaded.website
    }

    LaunchedEffect(saveState) {
        when (val state = saveState) {
            ProfileSaveState.Saved -> {
                viewModel.consumeProfileSaveResult()
                onDismiss()
            }
            is ProfileSaveState.Failed -> {
                viewModel.consumeProfileSaveResult()
                showSnackbar(state.message)
            }
            ProfileSaveState.Idle,
            ProfileSaveState.Saving,
            -> Unit
        }
    }

    // Compare against the form's opening snapshot, not the live MES flow. A
    // save-time relay refresh may update MES while this screen is still open.
    val hasChanges = profileMetadataHasChanges(original, edited)

    val dismiss = {
        viewModel.cancelProfileSave()
        onDismiss()
    }

    val avatarPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            viewModel.uploadProfileImage(
                uri = uri,
                isBanner = false,
                onUrl = { picture = it },
                onError = { showSnackbar(it) },
            )
        }
    }

    val bannerPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            viewModel.uploadProfileImage(
                uri = uri,
                isBanner = true,
                onUrl = { bannerUrl = it },
                onError = { showSnackbar(it) },
            )
        }
    }

    BackHandler(onBack = dismiss)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Black),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── Top bar ───────────────────────────────────────────────────────
            Row(
                modifier          = Modifier
                    .fillMaxWidth()
                    .background(Black)
                    .statusBarsPadding()
                    .height(Sizing.topBarHeight)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = dismiss) {
                    Text(text = "Cancel", color = TextSecondary, fontSize = 14.sp)
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick  = {
                        if (hasChanges) {
                            viewModel.saveProfile(original, edited)
                        }
                    },
                    enabled = hasChanges && saveState == ProfileSaveState.Idle,
                ) {
                    Text(
                        text  = if (isSaving) "Saving…" else "Save",
                        color = if (hasChanges && !isSaving) Brand else TextSecondary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            // ── Scrollable fields ─────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.medium),
            ) {
                Spacer(Modifier.height(Spacing.medium))

                // ── Banner ─────────────────────────────────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(EDIT_BANNER_HEIGHT)
                        .clip(RoundedCornerShape(Sizing.mediaCornerRadius))
                        .background(Surface1)
                        .clickable {
                            bannerPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (bannerUrl.isNotBlank()) {
                        AsyncImage(
                            model              = rememberFullWidthImageRequest(bannerUrl),
                            contentDescription = null,
                            contentScale       = ContentScale.Crop,
                            modifier           = Modifier.fillMaxSize(),
                        )
                    }
                    if (uploadingBanner) {
                        CircularProgressIndicator(
                            color = Brand,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(24.dp),
                        )
                    } else if (bannerUrl.isBlank()) {
                        Text(
                            text     = "Tap to set banner",
                            color    = TextSecondary,
                            fontSize = 12.sp,
                        )
                    }
                }

                Spacer(Modifier.height(Spacing.small))

                // ── Avatar ─────────────────────────────────────────────────────
                Box(
                    modifier = Modifier
                        .size(EDIT_AVATAR_SIZE)
                        .clip(CircleShape)
                        .border(2.dp, BorderSubtle, CircleShape)
                        .clickable {
                            avatarPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                ) {
                    viewModel.pubkeyHex?.let {
                        IdentIcon(pubkey = it, modifier = Modifier.fillMaxSize())
                    }
                    if (picture.isNotBlank()) {
                        AsyncImage(
                            model              = rememberAvatarImageRequest(picture, EDIT_AVATAR_SIZE),
                            contentDescription = null,
                            contentScale       = ContentScale.Crop,
                            modifier           = Modifier.fillMaxSize(),
                        )
                    }
                    if (uploadingAvatar) {
                        Box(
                            modifier = Modifier.fillMaxSize().background(Black.copy(alpha = 0.5f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                color = Brand,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
                Text(
                    text     = "Tap to change",
                    color    = TextSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )

                Spacer(Modifier.height(Spacing.large))

                // ── Text fields ────────────────────────────────────────────────
                ProfileField(
                    label    = "Banner URL",
                    value    = bannerUrl,
                    onChange = { bannerUrl = it },
                    hint     = "https://…",
                )
                ProfileField(
                    label    = "Display Name",
                    value    = displayName,
                    onChange = { displayName = it },
                )
                ProfileField(
                    label    = "Username",
                    value    = name,
                    onChange = { name = it },
                    hint     = "@username",
                )
                ProfileField(
                    label    = "Picture URL",
                    value    = picture,
                    onChange = { picture = it },
                    hint     = "https://…",
                )
                ProfileField(
                    label     = "About",
                    value     = about,
                    onChange  = { about = it },
                    multiline = true,
                )
                ProfileField(
                    label    = "NIP-05",
                    value    = nip05,
                    onChange = { nip05 = it },
                    hint     = "user@domain.com",
                )
                ProfileField(
                    label    = "Lightning Address",
                    value    = lud16,
                    onChange = { lud16 = it },
                    hint     = "user@wallet.com",
                )
                ProfileField(
                    label    = "Website",
                    value    = website,
                    onChange = { website = it },
                    hint     = "https://…",
                )

                Spacer(Modifier.height(Spacing.xl))
            }
        }
    }
}

@Composable
private fun ProfileField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    hint: String = "",
    multiline: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.small),
    ) {
        Text(
            text     = label,
            color    = TextSecondary,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(4.dp))
        BasicTextField(
            value         = value,
            onValueChange = onChange,
            textStyle     = TextStyle(color = Color.White, fontSize = 15.sp),
            cursorBrush   = SolidColor(Brand),
            singleLine    = !multiline,
            maxLines      = if (multiline) 6 else 1,
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty() && hint.isNotBlank()) {
                        Text(hint, color = Text3, fontSize = 15.sp)
                    }
                    inner()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.small))
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
    }
}
