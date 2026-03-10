package com.unsilence.app.ui.feed

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unsilence.app.domain.model.FeedFilter
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Cyan
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.TextSecondary

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterScreen(
    currentFilter: FeedFilter,
    onApply: (FeedFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)

    var sinceHours       by remember { mutableStateOf(currentFilter.sinceHours) }
    var requireReposts   by remember { mutableStateOf(currentFilter.requireReposts) }
    var requireReactions by remember { mutableStateOf(currentFilter.requireReactions) }
    var requireReplies   by remember { mutableStateOf(currentFilter.requireReplies) }
    var requireZaps      by remember { mutableStateOf(currentFilter.requireZaps) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Black),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            // ── Top bar ────────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(Sizing.topBarHeight)
                    .padding(horizontal = Spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint               = Color.White,
                    )
                }
                Text(
                    text       = "Filter",
                    color      = Color.White,
                    fontSize   = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier   = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    sinceHours       = null
                    requireReposts   = false
                    requireReactions = false
                    requireReplies   = false
                    requireZaps      = false
                }) {
                    Text("Reset", color = Cyan, fontSize = 14.sp)
                }
            }

            HorizontalDivider(color = Color(0xFF222222), thickness = 0.5.dp)

            // ── Time range ────────────────────────────────────────────────────
            Text(
                text     = "Time range",
                color    = TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.small),
            )
            FlowRow(
                modifier            = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.medium)
                    .padding(bottom = Spacing.medium),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement   = Arrangement.spacedBy(8.dp),
            ) {
                listOf(6 to "6H", 12 to "12H", 24 to "1D", 72 to "3D", 168 to "1W", 720 to "1M", null to "All")
                    .forEach { (hours, label) ->
                        FilterChip(
                            label    = label,
                            selected = sinceHours == hours,
                            onClick  = { sinceHours = hours },
                        )
                    }
            }

            HorizontalDivider(color = Color(0xFF222222), thickness = 0.5.dp)

            // ── Engagement filters ────────────────────────────────────────────
            Text(
                text     = "Must have",
                color    = TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.small),
            )
            FlowRow(
                modifier              = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.medium)
                    .padding(bottom = Spacing.medium),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement   = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip("Reposts",   requireReposts)   { requireReposts   = !requireReposts }
                FilterChip("Reactions", requireReactions) { requireReactions = !requireReactions }
                FilterChip("Comments",  requireReplies)   { requireReplies   = !requireReplies }
                FilterChip("Zaps",      requireZaps)      { requireZaps      = !requireZaps }
            }

            // ── Apply button ──────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = Spacing.medium, vertical = Spacing.medium),
            ) {
                Button(
                    onClick = {
                        onApply(
                            FeedFilter(
                                sinceHours       = sinceHours,
                                requireReposts   = requireReposts,
                                requireReactions = requireReactions,
                                requireReplies   = requireReplies,
                                requireZaps      = requireZaps,
                            )
                        )
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan),
                ) {
                    Text("Apply", color = Black, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) Cyan else Color.Transparent)
            .border(1.dp, if (selected) Cyan else Color(0xFF444444), RoundedCornerShape(20.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text       = label,
            color      = if (selected) Black else Color.White,
            fontSize   = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
