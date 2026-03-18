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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.unsilence.app.ui.common.IdentIcon
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Cyan
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.TextSecondary

@Composable
fun ComposeScreen(
    onDismiss: () -> Unit,
    initialText: String = "",
    viewModel: ComposeViewModel = hiltViewModel(),
) {
    val pubkeyHex      = viewModel.pubkeyHex
    val userAvatarUrl by viewModel.userAvatarUrl.collectAsStateWithLifecycle()
    // Cursor at position 0 so the user types above a pre-filled quote link.
    var textValue    by remember { mutableStateOf(TextFieldValue(initialText, TextRange(0))) }
    val focusRequester = remember { FocusRequester() }

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
                        color    = Cyan,
                        fontSize = 15.sp,
                    )
                }

                Spacer(Modifier.weight(1f))

                Button(
                    onClick  = { viewModel.publishNote(textValue.text.trim()) },
                    enabled  = textValue.text.isNotBlank(),
                    shape    = RoundedCornerShape(24.dp),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor         = Cyan,
                        contentColor           = Black,
                        disabledContainerColor = Color(0xFF004D59),
                        disabledContentColor   = Color(0xFF007A8A),
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

            // ── Compose area ─────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.medium, vertical = Spacing.small),
                verticalAlignment = Alignment.Top,
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
                            model              = userAvatarUrl,
                            contentDescription = null,
                            contentScale       = ContentScale.Crop,
                            modifier           = Modifier.fillMaxSize(),
                        )
                    }
                }

                Spacer(Modifier.width(Spacing.small))

                BasicTextField(
                    value         = textValue,
                    onValueChange = { textValue = it },
                    modifier      = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    textStyle     = TextStyle(
                        color    = Color.White,
                        fontSize = 16.sp,
                    ),
                    cursorBrush   = SolidColor(Cyan),
                    decorationBox = { inner ->
                        if (textValue.text.isEmpty()) {
                            Text(
                                text     = "What's on your mind?",
                                color    = TextSecondary,
                                fontSize = 16.sp,
                            )
                        }
                        inner()
                    },
                )
            }
        }
    }
}
