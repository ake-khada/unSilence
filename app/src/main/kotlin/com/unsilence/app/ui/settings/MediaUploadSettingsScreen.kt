package com.unsilence.app.ui.settings

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.unsilence.app.BuildConfig
import com.unsilence.app.data.blossom.VideoTranscoder
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.BrandDeep
import com.unsilence.app.ui.theme.Like
import com.unsilence.app.ui.theme.Mint
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Surface1
import com.unsilence.app.ui.theme.Text3
import com.unsilence.app.ui.theme.TextSecondary

@Composable
fun MediaUploadSettingsScreen(
    onDismiss: () -> Unit,
    viewModel: MediaUploadSettingsViewModel = hiltViewModel(),
) {
    BackHandler(onBack = onDismiss)

    val servers by viewModel.servers.collectAsState()
    val imageMaxDim by viewModel.imageMaxDim.collectAsState()
    val imageQuality by viewModel.imageQuality.collectAsState()
    val videoQuality by viewModel.videoQuality.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Black),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Top bar ──────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Black)
                    .statusBarsPadding()
                    .height(Sizing.topBarHeight)
                    .padding(horizontal = Spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                    )
                }
                Text(
                    text = "Media Upload",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.medium),
            ) {
                Spacer(Modifier.height(Spacing.large))

                // ── Servers section ──────────────────────────────────────────
                Text(
                    text = "Servers",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(Spacing.micro))
                Text(
                    text = "Where your photos and videos are stored. Your selection is published as a kind-10063 event so other clients can find your media.",
                    color = Text3,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(Spacing.medium))

                for (server in servers) {
                    ServerRow(
                        server = server,
                        onSelect = { viewModel.selectServer(server.url) },
                        onRemove = { viewModel.removeServer(server.url) },
                        canRemove = !(server.isSelected && servers.size <= 1),
                    )
                }

                Spacer(Modifier.height(Spacing.small))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showAddDialog = true }
                        .padding(vertical = Spacing.medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "Add server",
                        tint = BrandDeep,
                        modifier = Modifier.size(Sizing.actionIcon),
                    )
                    Spacer(Modifier.width(Spacing.small))
                    Text(
                        text = "Add custom server",
                        color = BrandDeep,
                        fontSize = 14.sp,
                    )
                }

                Spacer(Modifier.height(Spacing.large))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Spacer(Modifier.height(Spacing.large))

                // ── Image upload section ─────────────────────────────────────
                Text(
                    text = "Image upload",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(Spacing.medium))

                val dimSteps = listOf(1024, 1600, 2048, 0)
                val dimLabels = listOf(
                    "Small — fast uploads, chat",
                    "Standard — sharp on any phone",
                    "High — tablets & desktop",
                    "Original — no resize",
                )
                val dimIndex = dimSteps.indexOf(imageMaxDim).takeIf { it >= 0 } ?: 1

                Text(
                    text = "Max size: ${dimLabels[dimIndex]}",
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(Spacing.micro))
                Slider(
                    value = dimIndex.toFloat(),
                    onValueChange = { viewModel.setImageMaxDim(dimSteps[it.toInt()]) },
                    valueRange = 0f..3f,
                    steps = 2,
                    colors = SliderDefaults.colors(
                        thumbColor = BrandDeep,
                        activeTrackColor = BrandDeep,
                        inactiveTrackColor = Surface1,
                    ),
                )

                Spacer(Modifier.height(Spacing.medium))

                val qualitySteps = listOf(70, 85, 100)
                val qualityLabels = listOf("70 — smaller files", "85 — balanced", "100 — best quality")
                val qualityIndex = qualitySteps.indexOf(imageQuality).takeIf { it >= 0 } ?: 1

                Text(
                    text = "Quality: ${qualityLabels[qualityIndex]}",
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(Spacing.micro))
                Slider(
                    value = qualityIndex.toFloat(),
                    onValueChange = { viewModel.setImageQuality(qualitySteps[it.toInt()]) },
                    valueRange = 0f..2f,
                    steps = 1,
                    colors = SliderDefaults.colors(
                        thumbColor = BrandDeep,
                        activeTrackColor = BrandDeep,
                        inactiveTrackColor = Surface1,
                    ),
                )

                Spacer(Modifier.height(Spacing.large))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Spacer(Modifier.height(Spacing.large))

                // ── Video upload section ────────────────────────────────
                Text(
                    text = "Video upload",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(Spacing.medium))

                val videoSteps = VideoTranscoder.Quality.entries
                val videoIndex = videoSteps.indexOf(videoQuality).takeIf { it >= 0 } ?: 0

                Text(
                    text = "Quality: ${videoSteps[videoIndex].label}",
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(Spacing.micro))
                Slider(
                    value = videoIndex.toFloat(),
                    onValueChange = { viewModel.setVideoQuality(videoSteps[it.toInt()]) },
                    valueRange = 0f..(videoSteps.size - 1).toFloat(),
                    steps = videoSteps.size - 2,
                    colors = SliderDefaults.colors(
                        thumbColor = BrandDeep,
                        activeTrackColor = BrandDeep,
                        inactiveTrackColor = Surface1,
                    ),
                )

                // ── Debug smoke test ─────────────────────────────────────
                if (BuildConfig.DEBUG) {
                    val smokeState by viewModel.smokeTestState.collectAsState()
                    val clipboard = LocalClipboardManager.current

                    Spacer(Modifier.height(Spacing.large))
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    Spacer(Modifier.height(Spacing.large))

                    Text(
                        text = "Debug — Upload test",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(Spacing.micro))
                    Text(
                        text = "Generates a 100×100 PNG and uploads to the selected server. Throwaway scaffolding — removed before release.",
                        color = Text3,
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(Spacing.medium))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Surface1, RoundedCornerShape(8.dp))
                            .clickable(enabled = smokeState !is SmokeTestState.Running) {
                                viewModel.runSmokeTest()
                            }
                            .padding(Spacing.medium),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (smokeState is SmokeTestState.Running) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(Sizing.actionIcon),
                                color = BrandDeep,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.CloudUpload,
                                contentDescription = "Test upload",
                                tint = BrandDeep,
                                modifier = Modifier.size(Sizing.actionIcon),
                            )
                        }
                        Spacer(Modifier.width(Spacing.small))
                        Text(
                            text = when (smokeState) {
                                is SmokeTestState.Idle -> "Run test upload"
                                is SmokeTestState.Running -> "Uploading…"
                                is SmokeTestState.Success -> "Upload succeeded"
                                is SmokeTestState.Failure -> "Upload failed"
                            },
                            color = when (smokeState) {
                                is SmokeTestState.Success -> Mint
                                is SmokeTestState.Failure -> Like
                                else -> Color.White
                            },
                            fontSize = 14.sp,
                        )
                    }

                    when (val state = smokeState) {
                        is SmokeTestState.Success -> {
                            Spacer(Modifier.height(Spacing.small))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = state.url,
                                        color = BrandDeep,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "SHA-256: ${state.sha256.take(16)}…  •  ${state.sizeBytes} bytes",
                                        color = Text3,
                                        fontSize = 11.sp,
                                    )
                                }
                                IconButton(onClick = {
                                    clipboard.setText(AnnotatedString(state.url))
                                }) {
                                    Icon(
                                        imageVector = Icons.Filled.ContentCopy,
                                        contentDescription = "Copy URL",
                                        tint = TextSecondary,
                                        modifier = Modifier.size(Sizing.actionIcon),
                                    )
                                }
                            }
                        }
                        is SmokeTestState.Failure -> {
                            Spacer(Modifier.height(Spacing.small))
                            Text(
                                text = state.message,
                                color = Like,
                                fontSize = 12.sp,
                            )
                        }
                        else -> {}
                    }
                }

                Spacer(Modifier.height(Spacing.xxl))
            }
        }
    }

    if (showAddDialog) {
        AddServerDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { url ->
                viewModel.addServer(url)
                showAddDialog = false
            },
        )
    }
}

@Composable
private fun ServerRow(
    server: ServerRow,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
    canRemove: Boolean,
) {
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = server.isSelected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(
                selectedColor = BrandDeep,
                unselectedColor = TextSecondary,
            ),
        )
        Spacer(Modifier.width(Spacing.small))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = server.displayName,
                color = Color.White,
                fontSize = 14.sp,
            )
            Text(
                text = server.url,
                color = Text3,
                fontSize = 11.sp,
            )
            if (server.freeTierNote.isNotEmpty()) {
                Text(
                    text = server.freeTierNote,
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
            }
        }
        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "Options",
                    tint = TextSecondary,
                )
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Remove") },
                    onClick = {
                        showMenu = false
                        onRemove()
                    },
                    enabled = canRemove,
                )
            }
        }
    }
    HorizontalDivider(
        color = MaterialTheme.colorScheme.surfaceVariant,
        thickness = 0.5.dp,
        modifier = Modifier.padding(start = 48.dp),
    )
}

@Composable
private fun AddServerDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var url by remember { mutableStateOf("https://") }
    val isValid = url.startsWith("https://") && url.length > 10

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text("Add Blossom server", color = Color.White) },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Server URL") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = BrandDeep,
                    unfocusedBorderColor = TextSecondary,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedLabelColor = BrandDeep,
                    unfocusedLabelColor = TextSecondary,
                    cursorColor = BrandDeep,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(url) },
                enabled = isValid,
            ) {
                Text("Add", color = if (isValid) BrandDeep else Text3)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        },
    )
}
