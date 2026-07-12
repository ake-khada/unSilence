package com.unsilence.app.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unsilence.app.domain.model.ActivityPreset
import com.unsilence.app.domain.model.FeedFilter
import com.unsilence.app.domain.model.ShowType
import com.unsilence.app.domain.model.activityPreset
import com.unsilence.app.domain.model.label
import com.unsilence.app.domain.model.sinceLabel
import com.unsilence.app.domain.model.withActivityPreset
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.ui.theme.Surface2
import com.unsilence.app.ui.theme.Text3
import com.unsilence.app.ui.theme.White
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.SmartDisplay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterBottomSheet(
    currentFilter: FeedFilter,
    onApply: (FeedFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var showTypes by remember(currentFilter) {
        mutableStateOf(currentFilter.showTypes.takeIf { it.isNotEmpty() } ?: setOf(ShowType.ALL))
    }
    var sinceHours by remember(currentFilter) { mutableStateOf(currentFilter.sinceHours) }
    var activityPreset by remember(currentFilter) { mutableStateOf(currentFilter.activityPreset()) }

    fun buildFilter(): FeedFilter =
        FeedFilter(
            showTypes = showTypes.takeIf { it.isNotEmpty() } ?: setOf(ShowType.ALL),
            sinceHours = sinceHours,
        ).withActivityPreset(activityPreset)

    fun reset() {
        showTypes = setOf(ShowType.ALL)
        sinceHours = null
        activityPreset = ActivityPreset.ANY
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Black,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .size(width = 32.dp, height = 4.dp)
                    .background(Color(0xFF333333), RoundedCornerShape(2.dp)),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            SectionLabel("SHOW")
            ChipRow {
                val isAll = ShowType.ALL in showTypes
                ShowFilterChip(ShowType.ALL, selected = isAll) { showTypes = setOf(ShowType.ALL) }

                fun toggle(type: ShowType) {
                    val current = if (isAll) emptySet() else showTypes
                    val next = if (type in current) current - type else current + type
                    showTypes = next.takeIf { it.isNotEmpty() } ?: setOf(ShowType.ALL)
                }

                listOf(ShowType.TEXT, ShowType.IMAGES, ShowType.VIDEO, ShowType.ARTICLES).forEach { type ->
                    ShowFilterChip(type, selected = !isAll && type in showTypes) { toggle(type) }
                }
            }

            Spacer(Modifier.height(20.dp))

            SectionLabel("POSTED WITHIN")
            ChipRow {
                val options = listOf(
                    null to "Any time",
                    1 to sinceLabel(1),
                    6 to sinceLabel(6),
                    24 to sinceLabel(24),
                    168 to sinceLabel(168),
                )
                options.forEach { (hours, label) ->
                    FilterChip(label, selected = sinceHours == hours) { sinceHours = hours }
                }
            }

            Spacer(Modifier.height(20.dp))

            SectionLabel("ACTIVITY")
            ChipRow {
                ActivityPreset.entries.forEach { preset ->
                    FilterChip(preset.label, selected = activityPreset == preset) {
                        activityPreset = preset
                    }
                }
            }

            Spacer(Modifier.height(22.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { reset() }) {
                    Text("Reset", color = Text3, fontSize = AppType.body)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(Brand)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            onApply(buildFilter())
                            onDismiss()
                        }
                        .padding(horizontal = 22.dp, vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Apply",
                        color = Black,
                        fontSize = AppType.body,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun ShowFilterChip(type: ShowType, selected: Boolean, onClick: () -> Unit) {
    val icon = when (filterIconKind(type)) {
        FilterIconKind.GRID -> Icons.Filled.GridView
        FilterIconKind.TEXT -> Icons.AutoMirrored.Filled.FormatAlignLeft
        FilterIconKind.IMAGE -> Icons.Filled.Photo
        FilterIconKind.VIDEO -> Icons.Filled.SmartDisplay
        FilterIconKind.ARTICLE -> Icons.AutoMirrored.Filled.Article
    }
    Row(
        modifier = Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) Brand else Surface2)
            .border(
                width = 1.dp,
                color = if (selected) Brand else Color.Transparent,
                shape = RoundedCornerShape(20.dp),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .semantics {
                contentDescription = type.label
                this.selected = selected
            }
            .padding(horizontal = if (selected) 13.dp else 10.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) Black else White.copy(alpha = 0.82f),
            modifier = Modifier.size(20.dp),
        )
        if (selected) {
            Text(
                text = type.label,
                color = Black,
                fontSize = AppType.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = Text3,
        fontSize = AppType.caption,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Surface2)
            .border(
                width = 1.dp,
                color = if (selected) Brand else Color.Transparent,
                shape = RoundedCornerShape(18.dp),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) Brand else White.copy(alpha = 0.82f),
            fontSize = AppType.bodySmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
