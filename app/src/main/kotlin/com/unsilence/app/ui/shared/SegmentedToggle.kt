package com.unsilence.app.ui.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.unsilence.app.ui.theme.AppTextStyles
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.TextSecondary

/** Shared Filters geometry and styling, with caller-owned choice labels. */
@Composable
internal fun <T> SegmentedToggle(
    current: T,
    options: List<T>,
    label: (T) -> String,
    textAlign: TextAlign = TextAlign.Unspecified,
    onSelect: (T) -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.large).clip(shape)
        .border(1.dp, Color.White.copy(alpha = 0.15f), shape)) {
        options.forEach { option ->
            val selected = option == current
            Box(Modifier.weight(1f)
                .background(if (selected) Brand.copy(alpha = 0.15f) else Color.Transparent)
                .clickable { onSelect(option) }
                .padding(vertical = Spacing.small), contentAlignment = Alignment.Center) {
                Text(label(option), color = if (selected) Brand else TextSecondary,
                    style = AppTextStyles.body, textAlign = textAlign,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}
