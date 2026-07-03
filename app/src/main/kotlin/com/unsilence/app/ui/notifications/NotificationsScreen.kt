package com.unsilence.app.ui.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import com.unsilence.app.ui.common.EmptyState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unsilence.app.ui.common.LocalAppSessionKey
import com.unsilence.app.ui.shared.NotificationEventRow
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.ui.theme.TextSecondary

/**
 * Notifications screen — now uses [NotificationEventRow] from the shared
 * rendering pipeline instead of a custom inline implementation.
 *
 * Each notification row renders actor info + type icon + a compact embedded
 * note preview using the same rendering style as quoted events.
 */
@Composable
fun NotificationsScreen(
    onNoteClick: (String) -> Unit = {},
    onProfileClick: (String) -> Unit = {},
    staticTopPadding: Dp = 0.dp,
    viewModel: NotificationsViewModel = hiltViewModel(
        key = "notif-${LocalAppSessionKey.current}",
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Black),
    ) {
        when {
            state.loading -> {
                CircularProgressIndicator(
                    color    = Brand,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            state.items.isEmpty() -> {
                EmptyState(
                    icon    = Icons.Outlined.Notifications,
                    message = "No notifications yet",
                    hint    = "Interactions will appear here",
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(top = staticTopPadding),
                ) {
                    items(state.items, key = { it.key }) { row ->
                        NotificationEventRow(
                            row            = row,
                            onNoteClick    = onNoteClick,
                            onProfileClick = onProfileClick,
                        )
                        HorizontalDivider(
                            color     = MaterialTheme.colorScheme.surfaceVariant,
                            thickness = 0.5.dp,
                        )
                    }
                }
            }
        }
    }
}
