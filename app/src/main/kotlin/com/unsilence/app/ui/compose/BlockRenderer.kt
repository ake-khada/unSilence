package com.unsilence.app.ui.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import coil3.compose.AsyncImage
import com.unsilence.app.ui.theme.BrandDeep
import com.unsilence.app.ui.theme.Like
import com.unsilence.app.ui.theme.Mint
import com.unsilence.app.ui.theme.Surface1
import com.unsilence.app.ui.theme.Text3
import com.unsilence.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TextBlock(
    initialText: String,
    onTextChange: (String) -> Unit,
    autoFocus: Boolean = false,
    placeholder: String = "What's on your mind?",
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()

    var textValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = initialText,
                selection = TextRange(initialText.length),
            )
        )
    }

    LaunchedEffect(textValue.text) {
        onTextChange(textValue.text)
    }

    if (autoFocus) {
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
            bringIntoViewRequester.bringIntoView()
        }
    }

    val inputStyle = TextStyle(
        color = Color.White.copy(alpha = 0.95f),
        fontSize = 15.sp,
        lineHeight = 22.sp,
    )

    BasicTextField(
        value = textValue,
        onValueChange = { textValue = it },
        modifier = modifier
            .focusRequester(focusRequester)
            .bringIntoViewRequester(bringIntoViewRequester)
            .onFocusChanged { state ->
                if (state.isFocused) {
                    scope.launch { bringIntoViewRequester.bringIntoView() }
                }
            },
        textStyle = inputStyle,
        cursorBrush = SolidColor(BrandDeep),
        decorationBox = { innerTextField ->
            Box {
                if (textValue.text.isEmpty() && placeholder.isNotEmpty()) {
                    Text(
                        text = placeholder,
                        style = inputStyle.copy(color = Text3),
                    )
                }
                innerTextField()
            }
        },
    )
}

@Composable
fun AttachmentBlock(
    state: AttachmentState,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Surface1, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Thumbnail — 60dp square
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.4f)),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = state.uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            when (state) {
                is AttachmentState.Uploading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.35f)),
                    )
                }
                is AttachmentState.Failed -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ErrorOutline,
                            contentDescription = null,
                            tint = Like,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                else -> {}
            }
        }

        Spacer(Modifier.width(12.dp))

        // Right side: filename, status, optional progress / retry
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = state.displayName,
                    color = Color.White.copy(alpha = 0.95f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (state is AttachmentState.Uploaded) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = Mint,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(20.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Remove",
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            when (state) {
                is AttachmentState.Idle -> {
                    Text(
                        text = "Queued",
                        color = TextSecondary,
                        fontSize = 11.sp,
                    )
                }
                is AttachmentState.Uploading -> {
                    Text(
                        text = "Uploading\u2026",
                        color = TextSecondary,
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = BrandDeep,
                        trackColor = Color.White.copy(alpha = 0.08f),
                    )
                }
                is AttachmentState.Uploaded -> {
                    Text(
                        text = formatBytes(state.blob.sizeBytes),
                        color = TextSecondary,
                        fontSize = 11.sp,
                    )
                }
                is AttachmentState.Failed -> {
                    Text(
                        text = state.message.ifBlank { "Upload failed" },
                        color = Like,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = onRetry,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp),
                    ) {
                        Text(
                            text = "Retry",
                            color = BrandDeep,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    val kb = bytes / 1024.0
    return when {
        kb < 1024 -> "${kb.toInt()} KB"
        else -> "%.1f MB".format(kb / 1024.0)
    }
}
