package com.unsilence.app.ui.shared

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.unsilence.app.ui.theme.AppTextStyles
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.TextSecondary

@Composable
internal fun SettingsSectionLabel(title: String) {
    Text(
        text = title,
        color = TextSecondary,
        style = AppTextStyles.caption,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = AppType.caption * 0.08f,
        modifier = Modifier.padding(horizontal = Spacing.large, vertical = Spacing.small),
    )
}
