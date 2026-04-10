package com.unsilence.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.TextSecondary

@Composable
fun EmptyState(
    icon: ImageVector,
    message: String,
    hint: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(Spacing.medium))
        Text(
            text = message,
            color = TextSecondary,
            fontSize = AppType.bodyLarge,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp,
        )
        if (hint != null) {
            Spacer(Modifier.height(Spacing.small))
            Text(
                text = hint,
                color = TextSecondary.copy(alpha = 0.6f),
                fontSize = AppType.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}
