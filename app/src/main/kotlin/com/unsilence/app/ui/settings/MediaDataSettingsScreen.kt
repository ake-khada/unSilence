package com.unsilence.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unsilence.app.domain.model.AutomaticMediaMode
import com.unsilence.app.domain.model.AutomaticMediaPreferences
import com.unsilence.app.ui.common.LocalAppSessionKey
import com.unsilence.app.ui.shared.SettingsSectionLabel
import com.unsilence.app.ui.shared.SegmentedToggle
import androidx.compose.ui.text.style.TextAlign
import com.unsilence.app.ui.shared.rememberWriteAwareDismiss
import com.unsilence.app.ui.theme.AppTextStyles
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.TextSecondary

@Composable
fun MediaDataSettingsScreen(
    onDismiss: () -> Unit,
    navigationOwnsBack: Boolean = false,
    viewModel: MediaDataSettingsViewModel = hiltViewModel(key = "media-data-${LocalAppSessionKey.current}"),
) {
    rememberWriteAwareDismiss(viewModel.pendingEdits, navigationOwnsBack, onDismiss)
    val preferences by viewModel.mediaPreferences.collectAsStateWithLifecycle()
    MediaDataSettingsContent(
        preferences = preferences ?: AutomaticMediaPreferences(),
        onVideoMode = viewModel::setVideoAutoplayMode,
        onImageMode = viewModel::setImageLoadingMode,
        onPreviewMode = viewModel::setLinkPreviewMode,
    )
}

@Composable
internal fun MediaDataSettingsContent(
    preferences: AutomaticMediaPreferences,
    onVideoMode: (AutomaticMediaMode) -> Unit,
    onImageMode: (AutomaticMediaMode) -> Unit,
    onPreviewMode: (AutomaticMediaMode) -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Black)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().height(Sizing.topBarHeight).padding(horizontal = Spacing.medium),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Media & data", color = Color.White, style = AppTextStyles.subheading, fontWeight = FontWeight.SemiBold)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            MediaModeSelector("Autoplay videos", preferences.video, "Tap a video to play it in any mode.", onVideoMode)
            MediaModeSelector("Load images automatically", preferences.images,
                "Content images and video posters. Avatars and profile banners are unchanged.", onImageMode)
            MediaModeSelector("Load link previews automatically", preferences.previews,
                "Tap to load a preview in any mode.", onPreviewMode)
            Spacer(Modifier.height(Spacing.large))
        }
    }
}

@Composable
private fun MediaModeSelector(
    title: String,
    current: AutomaticMediaMode,
    description: String,
    onSelect: (AutomaticMediaMode) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.medium)) {
        SettingsSectionLabel(title.uppercase())
        Spacer(Modifier.height(Spacing.small))
        SegmentedToggle(
            current = current,
            options = AutomaticMediaMode.entries,
            label = { mode -> when (mode) {
                AutomaticMediaMode.ALWAYS -> "Always"
                AutomaticMediaMode.UNMETERED_ONLY -> "Unmetered only"
                AutomaticMediaMode.NEVER -> "Never"
            } },
            textAlign = TextAlign.Center,
            onSelect = onSelect,
        )
        Text(description, style = AppTextStyles.bodySmall, color = TextSecondary,
            modifier = Modifier.padding(horizontal = Spacing.large).padding(top = Spacing.small))
    }
}
