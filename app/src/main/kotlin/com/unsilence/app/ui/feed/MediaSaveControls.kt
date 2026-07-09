package com.unsilence.app.ui.feed

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.unsilence.app.data.media.MediaSaver
import com.unsilence.app.data.media.SaveMediaKind
import com.unsilence.app.data.media.SaveResult
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class MediaSaveMessage(
    val text: String,
    val isError: Boolean,
)

internal data class MediaSaveController(
    val savingUrl: String?,
    val message: MediaSaveMessage?,
    val requestSave: (String) -> Unit,
)

@Composable
internal fun rememberMediaSaveController(
    kind: SaveMediaKind,
): MediaSaveController {
    val context = LocalContext.current
    val mediaSaver = rememberMediaSaver()
    val scope = rememberCoroutineScope()
    var savingUrl by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<MediaSaveMessage?>(null) }
    var pendingPermissionUrl by remember { mutableStateOf<String?>(null) }

    fun startSave(url: String) {
        if (savingUrl == url) return
        scope.launch {
            savingUrl = url
            message = null
            message = when (val result = mediaSaver.saveToGallery(url, kind)) {
                is SaveResult.Success -> MediaSaveMessage("Saved to gallery", isError = false)
                is SaveResult.Failure -> MediaSaveMessage(result.message, isError = true)
            }
            savingUrl = null
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val url = pendingPermissionUrl
        pendingPermissionUrl = null
        if (granted && url != null) {
            startSave(url)
        } else {
            message = MediaSaveMessage("Storage permission denied", isError = true)
        }
    }

    LaunchedEffect(message) {
        val active = message ?: return@LaunchedEffect
        delay(if (active.isError) 3000 else 2000)
        if (message == active) {
            message = null
        }
    }

    return MediaSaveController(
        savingUrl = savingUrl,
        message = message,
        requestSave = { url ->
            if (savingUrl == null) {
                if (requiresLegacyStoragePermission(context)) {
                    pendingPermissionUrl = url
                    permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } else {
                    startSave(url)
                }
            }
        },
    )
}

@Composable
internal fun MediaDownloadButton(
    url: String,
    controller: MediaSaveController,
    modifier: Modifier = Modifier,
) {
    val isSaving = controller.savingUrl == url
    IconButton(
        onClick = { controller.requestSave(url) },
        enabled = controller.savingUrl == null,
        modifier = modifier,
    ) {
        if (isSaving) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.dp,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Download,
                contentDescription = "Save to gallery",
                tint = Color.White,
            )
        }
    }
}

@Composable
internal fun MediaSaveStatusPill(
    message: MediaSaveMessage,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(
                color = if (message.isError) {
                    Color(0xFF4A1F1F).copy(alpha = 0.88f)
                } else {
                    Color.Black.copy(alpha = 0.72f)
                },
                shape = RoundedCornerShape(999.dp),
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = message.text,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun rememberMediaSaver(): MediaSaver {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        EntryPointAccessors.fromApplication(
            context,
            MediaSaverEntryPoint::class.java,
        ).mediaSaver()
    }
}

private fun requiresLegacyStoragePermission(context: Context): Boolean =
    Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) != PackageManager.PERMISSION_GRANTED

@EntryPoint
@InstallIn(SingletonComponent::class)
private interface MediaSaverEntryPoint {
    fun mediaSaver(): MediaSaver
}
