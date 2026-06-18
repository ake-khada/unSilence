package com.unsilence.app.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.ui.common.IdentIcon
import com.unsilence.app.ui.common.rememberAvatarImageRequest
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.BrandDeep
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MentionPickerSheet(
    follows: List<UserEntity>,
    searchResults: List<UserEntity>,
    query: String,
    onQueryChange: (String) -> Unit,
    onSelect: (UserEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.padding(horizontal = Spacing.medium)) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("Search people\u2026", color = TextSecondary) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = BrandDeep,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
                    cursorColor = BrandDeep,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                ),
            )
            Spacer(Modifier.height(Spacing.small))

            val list = if (query.isBlank()) follows else searchResults
            LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
                if (list.isEmpty()) {
                    item {
                        Text(
                            text = if (query.isBlank()) "No follows yet" else "No matches",
                            color = TextSecondary,
                            modifier = Modifier.padding(Spacing.medium),
                        )
                    }
                } else {
                    items(list, key = { it.pubkey }) { user ->
                        UserRow(user = user, onClick = { onSelect(user) })
                    }
                }
            }
        }
    }
}

@Composable
private fun UserRow(user: UserEntity, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(36.dp).clip(CircleShape)) {
            IdentIcon(pubkey = user.pubkey, modifier = Modifier.size(36.dp))
            if (!user.picture.isNullOrBlank()) {
                AsyncImage(
                    model = rememberAvatarImageRequest(user.picture, 36.dp),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(Spacing.small))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = user.displayName?.takeIf { it.isNotBlank() }
                    ?: user.name?.takeIf { it.isNotBlank() }
                    ?: "${user.pubkey.take(6)}\u2026${user.pubkey.takeLast(4)}",
                color = Color.White,
                fontSize = AppType.body,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!user.nip05.isNullOrBlank()) {
                Text(
                    text = user.nip05,
                    color = TextSecondary,
                    fontSize = AppType.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
