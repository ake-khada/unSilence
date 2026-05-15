package com.unsilence.app.ui.compose

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
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
    // Cursor at position 0 so the user types above a pre-filled quote link.
    var textValue    by remember { mutableStateOf(TextFieldValue(initialText, TextRange(0))) }
    val focusRequester = remember { FocusRequester() }

    val isReply = replyToEventId != null
    val replyToRow = viewModel.replyToRow

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
                        color    = Brand,
                        fontSize = 15.sp,
                    )
                }

                Spacer(Modifier.weight(1f))

                Button(
                    onClick  = {
                        val text = textValue.text.trim()
                        if (isReply) viewModel.publishReply(text) else viewModel.publishNote(text)
                    },
                    enabled  = textValue.text.isNotBlank(),
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
