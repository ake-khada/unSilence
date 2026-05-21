package com.unsilence.app.ui.compose

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.unsilence.app.ui.common.IdentIcon
import com.unsilence.app.ui.common.rememberAvatarImageRequest
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.ui.theme.BrandDeep
import com.unsilence.app.ui.theme.Like
import com.unsilence.app.ui.theme.Mint
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Surface2
import com.unsilence.app.ui.theme.TextSecondary

@Composable
fun ComposeScreen(
    onDismiss: () -> Unit,
    initialText: String = "",
    replyToEventId: String? = null,
    viewModel: ComposeViewModel = hiltViewModel(),
) {
    val pubkeyHex      = viewModel.pubkeyHex
    val userAvatarUrl by viewModel.userAvatarUrl.collectAsStateWithLifecycle()
    val userEntity    by viewModel.userEntity.collectAsStateWithLifecycle()
    val attachments   by viewModel.attachments.collectAsStateWithLifecycle()
    val canPublish    by viewModel.canPublish.collectAsStateWithLifecycle()
    // Cursor at position 0 so the user types above a pre-filled quote link.
    var textValue    by remember { mutableStateOf(TextFieldValue(initialText, TextRange(0))) }
    val focusRequester = remember { FocusRequester() }

    val isReply = replyToEventId != null
    val replyToRow = viewModel.replyToRow

    // Keep ViewModel's text state in sync for canPublish.
    LaunchedEffect(textValue.text) {
        viewModel.updateComposeText(textValue.text)
    }

    // Reset ViewModel state on open (activity-scoped VM survives recomposition)
    LaunchedEffect(Unit) {
        viewModel.reset()
        if (replyToEventId != null) viewModel.loadReplyTo(replyToEventId)
    }

    // Auto-dismiss once the note is published
    LaunchedEffect(viewModel.published) {
        if (viewModel.published) onDismiss()
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // ── Photo picker ────────────────────────────────────────────────────────
    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 8),
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.addAttachments(uris)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Black),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            // ── Top bar ───────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Sizing.topBarHeight)
                    .padding(horizontal = Spacing.medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(
                        text     = "Cancel",
                        color    = TextSecondary,
                        fontSize = 15.sp,
                    )
                }

                Spacer(Modifier.weight(1f))

                Button(
                    onClick  = {
                        val text = textValue.text.trim()
                        if (isReply) viewModel.publishReply(text) else viewModel.publishNote(text)
                    },
                    enabled  = canPublish,
                    shape    = RoundedCornerShape(24.dp),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor         = Color.White,
                        contentColor           = Black,
                        disabledContainerColor = Color.White.copy(alpha = 0.38f),
                        disabledContentColor   = Black.copy(alpha = 0.38f),
                    ),
                    modifier = Modifier.height(36.dp),
                ) {
                    Text(
                        text       = "Post",
                        fontSize   = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            // ── Parent note preview (reply mode) ─────────────────────────────
            if (isReply && replyToRow != null) {
                val parentName = replyToRow.authorDisplayName?.takeIf { it.isNotBlank() }
                    ?: replyToRow.authorName?.takeIf { it.isNotBlank() }
                    ?: "${replyToRow.pubkey.take(6)}…${replyToRow.pubkey.takeLast(4)}"
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.medium)
                        .background(Surface2, RoundedCornerShape(8.dp))
                        .padding(Spacing.medium),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape),
                        ) {
                            IdentIcon(pubkey = replyToRow.pubkey, modifier = Modifier.size(24.dp))
                            if (!replyToRow.authorPicture.isNullOrBlank()) {
                                AsyncImage(
                                    model              = rememberAvatarImageRequest(replyToRow.authorPicture, 24.dp),
                                    contentDescription = null,
                                    contentScale       = ContentScale.Crop,
                                    modifier           = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        Spacer(Modifier.width(Spacing.small))
                        Text(
                            text     = parentName,
                            color    = Color.White,
                            fontSize = AppType.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(Spacing.micro))
                    Text(
                        text     = replyToRow.content.take(200),
                        color    = TextSecondary,
                        fontSize = AppType.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(Spacing.small))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, thickness = 0.5.dp)
            }

            // ── Author header ────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.medium, vertical = Spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(Sizing.avatar)
                        .clip(CircleShape),
                ) {
                    if (pubkeyHex != null) {
                        IdentIcon(pubkey = pubkeyHex, modifier = Modifier.size(Sizing.avatar))
                    } else {
                        Box(modifier = Modifier.size(Sizing.avatar).background(Color(0xFF333333)))
                    }
                    if (!userAvatarUrl.isNullOrBlank()) {
                        AsyncImage(
                            model              = rememberAvatarImageRequest(userAvatarUrl, Sizing.avatar),
                            contentDescription = null,
                            contentScale       = ContentScale.Crop,
                            modifier           = Modifier.fillMaxSize(),
                        )
                    }
                }

                Spacer(Modifier.width(Spacing.small))

                Column {
                    val displayName = userEntity?.displayName?.takeIf { it.isNotBlank() }
                        ?: userEntity?.name?.takeIf { it.isNotBlank() }
                        ?: pubkeyHex?.let { "${it.take(6)}…${it.takeLast(4)}" } ?: ""
                    Text(
                        text       = displayName,
                        color      = Color.White,
                        fontSize   = AppType.body,
                        fontWeight = FontWeight.SemiBold,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis,
                    )
                    val nip05 = userEntity?.nip05
                    if (!nip05.isNullOrBlank()) {
                        Text(
                            text     = nip05,
                            color    = TextSecondary,
                            fontSize = AppType.caption,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // ── Compose area ────────────────────────────────────────────────
            BasicTextField(
                value         = textValue,
                onValueChange = { textValue = it },
                modifier      = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = Spacing.medium)
                    .focusRequester(focusRequester),
                textStyle     = TextStyle(
                    color    = Color.White,
                    fontSize = 16.sp,
                ),
                cursorBrush   = SolidColor(Brand),
                decorationBox = { inner ->
                    if (textValue.text.isEmpty()) {
                        Text(
                            text     = if (isReply) "Write your reply…" else "Break the silence...",
                            color    = TextSecondary,
                            fontSize = 16.sp,
                        )
                    }
                    inner()
                },
            )

            // ── Attachment thumbnails ───────────────────────────────────────
            if (attachments.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.medium, vertical = Spacing.small),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    items(attachments, key = { it.id }) { att ->
                        AttachmentChip(
                            state = att,
                            onRemove = { viewModel.removeAttachment(att.id) },
                            onRetry = { viewModel.retryAttachment(att.id) },
                        )
                    }
                }
            }

            // ── Action row ──────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.small, vertical = Spacing.micro),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    pickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                    )
                }) {
                    Icon(
                        imageVector = Icons.Filled.AddPhotoAlternate,
                        contentDescription = "Add photo",
                        tint = BrandDeep,
                        modifier = Modifier.size(Sizing.actionIcon),
                    )
                }
            }

            // ── Error banner ─────────────────────────────────────────────────
            viewModel.publishError?.let { error ->
                Text(
                    text     = error,
                    color    = Color(0xFFFF5252),
                    fontSize = 13.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.medium, vertical = Spacing.small),
                )
            }
        }
    }
}

@Composable
private fun AttachmentChip(
    state: AttachmentState,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
) {
    val chipSize = 80.dp

    Box(
        modifier = Modifier
            .size(chipSize)
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (state is AttachmentState.Failed) {
                    Modifier.clickable(onClick = onRetry)
                } else {
                    Modifier
                }
            ),
    ) {
        // Thumbnail from local URI
        AsyncImage(
            model = state.uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (state is AttachmentState.Uploaded) Modifier
                    else Modifier.alpha(0.5f)
                ),
        )

        // State overlay
        when (state) {
            is AttachmentState.Idle, is AttachmentState.Uploading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = BrandDeep,
                        strokeWidth = 2.dp,
                    )
                }
            }
            is AttachmentState.Uploaded -> {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .size(16.dp)
                        .background(Black.copy(alpha = 0.7f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "Uploaded",
                        tint = Mint,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
            is AttachmentState.Failed -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.ErrorOutline,
                        contentDescription = "Upload failed — tap to retry",
                        tint = Like,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }

        // Remove button — always visible top-right
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .size(22.dp)
                .background(Black.copy(alpha = 0.7f), CircleShape)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Remove",
                tint = Color.White,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
