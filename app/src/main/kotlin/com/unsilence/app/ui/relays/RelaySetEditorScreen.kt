package com.unsilence.app.ui.relays

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.unsilence.app.data.memory.RelaySet
import com.unsilence.app.data.relay.normalizeRelayUrl
import com.unsilence.app.ui.common.LocalAppSessionKey
import com.unsilence.app.ui.common.LocalShowSnackbar
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Surface1
import com.unsilence.app.ui.theme.TextSecondary

private val RELAY_SET_IMAGE_SIZE = 72.dp

@Composable
fun RelaySetEditorScreen(
    onDismiss: () -> Unit,
    relaySet: RelaySet? = null,
    viewModel: RelayManagementViewModel = hiltViewModel(
        key = "relay-management-${LocalAppSessionKey.current}",
    ),
) {
    BackHandler(onBack = onDismiss)
    val initialDraft = remember(relaySet) { relaySetEditorDraft(relaySet) }
    var name by remember(initialDraft) { mutableStateOf(initialDraft.title) }
    var description by remember(initialDraft) { mutableStateOf(initialDraft.description) }
    var image by remember(initialDraft) { mutableStateOf(initialDraft.image) }
    val relayUrls = remember(initialDraft) {
        mutableStateListOf<String>().apply { addAll(initialDraft.members) }
    }
    var newRelayUrl by remember { mutableStateOf("") }
    val uploadingImage by viewModel.uploadingRelaySetImage.collectAsStateWithLifecycle()
    val showSnackbar = LocalShowSnackbar.current
    val isEdit = relaySet != null
    val canSave = name.isNotBlank() && relayUrls.isNotEmpty() && !uploadingImage

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            viewModel.uploadRelaySetImage(
                uri = uri,
                onUrl = { image = it },
                onError = showSnackbar,
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Black),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(Sizing.topBarHeight)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Cancel", tint = Color.White)
                }
                Text(
                    text = if (isEdit) "Edit Relay Set" else "New Relay Set",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        viewModel.saveRelaySet(
                            initialDraft.copy(
                                title = name,
                                description = description,
                                image = image,
                                members = relayUrls.toList(),
                            ),
                        )
                        onDismiss()
                    },
                    enabled = canSave,
                ) {
                    Text(
                        text = if (isEdit) "Save" else "Create",
                        color = if (canSave) Brand else TextSecondary,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item(key = "metadata") {
                    Column(
                        modifier = Modifier.padding(
                            horizontal = Spacing.medium,
                            vertical = Spacing.medium,
                        ),
                    ) {
                        RelaySetTextField(
                            label = "Name",
                            value = name,
                            placeholder = "e.g. Bitcoin Relays",
                            onValueChange = { name = it },
                            singleLine = true,
                        )
                        Spacer(Modifier.height(Spacing.medium))
                        RelaySetTextField(
                            label = "Description",
                            value = description,
                            placeholder = "Optional",
                            onValueChange = { description = it },
                            singleLine = false,
                        )
                        Spacer(Modifier.height(Spacing.medium))
                        Text(text = "Image", color = TextSecondary, fontSize = 12.sp)
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(RELAY_SET_IMAGE_SIZE)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Surface1)
                                    .clickable(enabled = !uploadingImage) {
                                        imagePicker.launch(
                                            PickVisualMediaRequest(
                                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                                            ),
                                        )
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (image.isNotBlank()) {
                                    AsyncImage(
                                        model = image,
                                        contentDescription = "Relay set image",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else {
                                    Icon(
                                        Icons.Filled.Image,
                                        contentDescription = "Choose image",
                                        tint = TextSecondary,
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                                if (uploadingImage) {
                                    Box(
                                        modifier = Modifier.fillMaxSize().background(Black.copy(alpha = 0.55f)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        CircularProgressIndicator(
                                            color = Brand,
                                            strokeWidth = 2.dp,
                                            modifier = Modifier.size(22.dp),
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.width(Spacing.small))
                            Column(modifier = Modifier.weight(1f)) {
                                BasicTextField(
                                    value = image,
                                    onValueChange = { image = it },
                                    textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                                    cursorBrush = SolidColor(Brand),
                                    singleLine = true,
                                    decorationBox = { inner ->
                                        Box {
                                            if (image.isEmpty()) {
                                                Text("Paste image URL", color = TextSecondary, fontSize = 13.sp)
                                            }
                                            inner()
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(Modifier.height(8.dp))
                                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                                Text(
                                    text = "Tap the preview to upload",
                                    color = TextSecondary,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                            if (image.isNotBlank()) {
                                IconButton(
                                    onClick = { image = "" },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "Remove image",
                                        tint = TextSecondary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }

                item(key = "add-relay") {
                    Text(
                        text = "RELAYS",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(
                            start = Spacing.medium,
                            end = Spacing.medium,
                            top = Spacing.medium,
                        ),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicTextField(
                            value = newRelayUrl,
                            onValueChange = { newRelayUrl = it },
                            textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                            cursorBrush = SolidColor(Brand),
                            singleLine = true,
                            decorationBox = { inner ->
                                Box {
                                    if (newRelayUrl.isEmpty()) {
                                        Text("wss://relay.example.com", color = TextSecondary, fontSize = 14.sp)
                                    }
                                    inner()
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(Spacing.small))
                        IconButton(
                            onClick = {
                                val normalized = normalizeRelayUrl(newRelayUrl)
                                if (normalized != null && relayUrls.none {
                                        normalizeRelayUrl(it) == normalized
                                    }
                                ) {
                                    relayUrls.add(normalized)
                                    newRelayUrl = ""
                                }
                            },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = "Add relay", tint = Brand)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }

                itemsIndexed(
                    items = relayUrls,
                    key = { index, url -> "relay:$index:$url" },
                ) { index, url ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.medium, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = url,
                            color = Color.White,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { relayUrls.removeAt(index) },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Remove relay",
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        thickness = 0.5.dp,
                    )
                }
                item(key = "bottom-space") { Spacer(Modifier.height(Spacing.xl)) }
            }
        }
    }
}

@Composable
private fun RelaySetTextField(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean,
) {
    Text(text = label, color = TextSecondary, fontSize = 12.sp)
    Spacer(Modifier.height(6.dp))
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
        cursorBrush = SolidColor(Brand),
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        maxLines = if (singleLine) 1 else 5,
        decorationBox = { inner ->
            Box(modifier = Modifier.fillMaxWidth()) {
                if (value.isEmpty()) {
                    Text(placeholder, color = TextSecondary, fontSize = 15.sp)
                }
                inner()
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}
