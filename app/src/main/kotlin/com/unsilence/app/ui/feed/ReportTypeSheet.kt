package com.unsilence.app.ui.feed

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.unsilence.app.data.model.ReportType
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportTypeSheet(
    onDismiss: () -> Unit,
    onTypeSelected: (ReportType) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { BottomSheetDefaults.DragHandle(color = TextSecondary) },
    ) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Text(
                "Why are you reporting?",
                fontSize = AppType.subheading,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
            for (type in ReportType.entries) {
                SheetActionRow(Icons.Default.Flag, type.displayName) {
                    onTypeSelected(type)
                    onDismiss()
                }
            }
        }
    }
}
