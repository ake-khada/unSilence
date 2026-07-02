package com.unsilence.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unsilence.app.data.blossom.VideoTranscoder
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.BrandDeep
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Surface1
import com.unsilence.app.ui.theme.Text3
import com.unsilence.app.ui.theme.TextSecondary
import kotlin.math.roundToInt

private val IMAGE_DIM_STEPS = listOf(1024, 1600, 2048, 0)
private val IMAGE_DIM_LABELS = listOf(
    "Small — fast uploads, chat",
    "Standard — sharp on any phone",
    "High — tablets & desktop",
    "Original — no resize",
)
private val IMAGE_QUALITY_STEPS = listOf(70, 85, 100)
private val IMAGE_QUALITY_LABELS = listOf("70 — smaller files", "85 — balanced", "100 — best quality")

@Composable
fun MediaUploadSettingsScreen(
    onDismiss: () -> Unit,
    viewModel: MediaUploadSettingsViewModel = hiltViewModel(),
) {
    BackHandler(onBack = onDismiss)

    val servers by viewModel.servers.collectAsStateWithLifecycle()
    val imageMaxDim by viewModel.imageMaxDim.collectAsStateWithLifecycle()
    val imageQuality by viewModel.imageQuality.collectAsStateWithLifecycle()
    val videoQuality by viewModel.videoQuality.collectAsStateWithLifecycle()
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
                    .padding(horizontal = Spacing.medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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

                val dimIndex = IMAGE_DIM_STEPS.indexOf(imageMaxDim).takeIf { it >= 0 } ?: 1
                var dimSliderIndex by remember(imageMaxDim) {
                    mutableFloatStateOf(dimIndex.toFloat())
                }
                val displayedDimIndex = dimSliderIndex.roundToInt().coerceIn(IMAGE_DIM_STEPS.indices)

                Text(
                    text = "Max size: ${IMAGE_DIM_LABELS[displayedDimIndex]}",
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(Spacing.micro))
                Slider(
                    value = dimSliderIndex,
                    onValueChange = { dimSliderIndex = it },
                    onValueChangeFinished = {
                        viewModel.setImageMaxDim(IMAGE_DIM_STEPS[displayedDimIndex])
                    },
                    valueRange = 0f..3f,
                    steps = 2,
                    colors = SliderDefaults.colors(
                        thumbColor = BrandDeep,
                        activeTrackColor = BrandDeep,
                        inactiveTrackColor = Surface1,
                    ),
                )

                Spacer(Modifier.height(Spacing.medium))

                val qualityIndex = IMAGE_QUALITY_STEPS.indexOf(imageQuality).takeIf { it >= 0 } ?: 1
                var qualitySliderIndex by remember(imageQuality) {
                    mutableFloatStateOf(qualityIndex.toFloat())
                }
                val displayedQualityIndex = qualitySliderIndex.roundToInt().coerceIn(IMAGE_QUALITY_STEPS.indices)

                Text(
                    text = "Quality: ${IMAGE_QUALITY_LABELS[displayedQualityIndex]}",
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(Spacing.micro))
                Slider(
                    value = qualitySliderIndex,
                    onValueChange = { qualitySliderIndex = it },
                    onValueChangeFinished = {
                        viewModel.setImageQuality(IMAGE_QUALITY_STEPS[displayedQualityIndex])
                    },
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
                var videoSliderIndex by remember(videoQuality) {
                    mutableFloatStateOf(videoIndex.toFloat())
                }
                val displayedVideoIndex = videoSliderIndex.roundToInt().coerceIn(videoSteps.indices)

                Text(
                    text = "Quality: ${videoSteps[displayedVideoIndex].label}",
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(Spacing.micro))
                Slider(
                    value = videoSliderIndex,
                    onValueChange = { videoSliderIndex = it },
                    onValueChangeFinished = {
                        viewModel.setVideoQuality(videoSteps[displayedVideoIndex])
                    },
                    valueRange = 0f..(videoSteps.size - 1).toFloat(),
                    steps = videoSteps.size - 2,
                    colors = SliderDefaults.colors(
                        thumbColor = BrandDeep,
                        activeTrackColor = BrandDeep,
                        inactiveTrackColor = Surface1,
                    ),
                )


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
