package com.unsilence.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.unsilence.app.ui.common.IdentIcon
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Cyan
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.TextSecondary

private val EDIT_AVATAR_SIZE = 72.dp
private val EDIT_BANNER_HEIGHT = 100.dp

@Composable
fun EditProfileScreen(
    viewModel: ProfileViewModel,
    onDismiss: () -> Unit,
) {
    val user by viewModel.userFlow.collectAsStateWithLifecycle(initialValue = null)

    // Form state — pre-populated once when user data first arrives.
    var initialized  by remember { mutableStateOf(false) }
    var name         by remember { mutableStateOf("") }
    var displayName  by remember { mutableStateOf("") }
    var about        by remember { mutableStateOf("") }
    var picture      by remember { mutableStateOf("") }
    var nip05        by remember { mutableStateOf("") }
    var lud16        by remember { mutableStateOf("") }
    var website      by remember { mutableStateOf("") }

    LaunchedEffect(user) {
        if (user != null && !initialized) {
            name        = user?.name        ?: ""
            displayName = user?.displayName ?: ""
            about       = user?.about       ?: ""
            picture     = user?.picture     ?: ""
            nip05       = user?.nip05       ?: ""
            lud16       = user?.lud16       ?: ""
            initialized = true
        }
    }

    // Save enabled when any field differs from stored values.
    val hasChanges = initialized && (
        name.trim()        != (user?.name        ?: "") ||
        displayName.trim() != (user?.displayName ?: "") ||
        about.trim()       != (user?.about       ?: "") ||
        picture.trim()     != (user?.picture     ?: "") ||
        nip05.trim()       != (user?.nip05       ?: "") ||
        lud16.trim()       != (user?.lud16       ?: "") ||
        website.isNotBlank()
    )

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
                TextButton(onClick = onDismiss) {
                    Text(text = "Cancel", color = TextSecondary, fontSize = 14.sp)
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick  = {
                        if (hasChanges) {
                            viewModel.saveProfile(
                                name        = name,
                                displayName = displayName,
                                about       = about,
                                picture     = picture,
                                nip05       = nip05,
                                lud16       = lud16,
                                website     = website,
                                onDone      = onDismiss,
                            )
                        }
                    },
                    enabled = hasChanges,
                ) {
                    Text(
                        text  = "Save",
                        color = if (hasChanges) Cyan else TextSecondary,
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

                // ── Banner placeholder ─────────────────────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(EDIT_BANNER_HEIGHT)
                        .clip(RoundedCornerShape(Sizing.mediaCornerRadius))
                        .background(Color(0xFF1A1A1A)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (picture.isNotBlank()) {
                        // Show picture URL as banner preview for now; banner editing is v1.1
                        AsyncImage(
                            model              = picture,
                            contentDescription = null,
                            contentScale       = ContentScale.Crop,
                            modifier           = Modifier.fillMaxSize(),
                        )
                    }
                    Text(
                        text     = "Banner (coming soon)",
                        color    = TextSecondary,
                        fontSize = 12.sp,
                    )
                }

                Spacer(Modifier.height(Spacing.small))

                // ── Avatar ─────────────────────────────────────────────────────
                Box(
                    modifier = Modifier
                        .size(EDIT_AVATAR_SIZE)
                        .clip(CircleShape)
                        .border(2.dp, Color(0xFF333333), CircleShape),
                ) {
                    viewModel.pubkeyHex?.let {
                        IdentIcon(pubkey = it, modifier = Modifier.fillMaxSize())
                    }
                    if (picture.isNotBlank()) {
                        AsyncImage(
                            model              = picture,
                            contentDescription = null,
                            contentScale       = ContentScale.Crop,
                            modifier           = Modifier.fillMaxSize(),
                        )
                    }
                }
                Text(
                    text     = "Tap to change (coming soon)",
                    color    = TextSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )

                Spacer(Modifier.height(Spacing.large))

                // ── Text fields ────────────────────────────────────────────────
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
            cursorBrush   = SolidColor(Cyan),
            singleLine    = !multiline,
            maxLines      = if (multiline) 6 else 1,
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty() && hint.isNotBlank()) {
                        Text(hint, color = Color(0xFF555555), fontSize = 15.sp)
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
