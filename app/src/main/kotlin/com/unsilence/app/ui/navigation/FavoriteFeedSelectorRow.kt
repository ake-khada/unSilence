package com.unsilence.app.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unsilence.app.ui.theme.AppTextStyles
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Text3

/** Favorite feed selection and removal remain separate actions. */
@Composable
internal fun FavoriteFeedSelectorRow(
    label: String,
    selected: Boolean,
    latencyMs: Int?,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Brand.copy(alpha = 0.08f) else Color.Transparent),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f)
                .clickable(onClick = onSelect)
                .padding(start = 16.dp, top = 13.dp, bottom = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            com.unsilence.app.ui.relays.RelayLatencyDot(latencyMs, modifier = Modifier.size(8.dp))
            Spacer(Modifier.width(14.dp))
            Text(
                text       = label,
                color      = if (selected) Brand else com.unsilence.app.ui.theme.White,
                style = AppTextStyles.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
                modifier   = Modifier.weight(1f),
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Selected",
                    tint = Brand,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(Spacing.small))
            }
        }
        // Keep the existing glyph center 25dp from the row's trailing edge.
        // The adjacent selection area gives up width, not height or hit isolation.
        IconButton(onClick = onRemove, modifier = Modifier.padding(end = 1.dp).size(48.dp)) {
            Icon(Icons.Default.Delete, contentDescription = "Remove favorite",
                tint = Text3, modifier = Modifier.size(16.dp))
        }
    }
}
