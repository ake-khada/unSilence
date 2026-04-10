package com.unsilence.app.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.unsilence.app.domain.model.ShowType
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Cyan
import com.unsilence.app.ui.theme.Surface1

// ── Zap slider breakpoints (sats) ───────────────────────────────────────────
private val ZAP_BREAKPOINTS = longArrayOf(
    0, 100, 500, 1_000, 5_000, 10_000, 50_000, 100_000, 210_000, 500_000, 1_000_000, 5_000_000,
)

private fun zapSliderToSats(fraction: Float): Long {
    val idx = (fraction * (ZAP_BREAKPOINTS.size - 1)).toInt()
        .coerceIn(0, ZAP_BREAKPOINTS.size - 1)
    return ZAP_BREAKPOINTS[idx]
}

private fun satsToZapSlider(sats: Long): Float {
    val idx = ZAP_BREAKPOINTS.indexOfFirst { it >= sats }.coerceAtLeast(0)
    return idx.toFloat() / (ZAP_BREAKPOINTS.size - 1).toFloat()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterBottomSheet(
    currentFilter: FeedFilter,
    onApply: (FeedFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Local editable state
    var showTypes     by remember { mutableStateOf(currentFilter.showTypes) }
    var sinceHours    by remember { mutableStateOf(currentFilter.sinceHours) }
    var minReplies    by remember { mutableFloatStateOf(currentFilter.minReplies.toFloat()) }
    var minReposts    by remember { mutableFloatStateOf(currentFilter.minReposts.toFloat()) }
    var minReactions  by remember { mutableFloatStateOf(currentFilter.minReactions.toFloat()) }
    var zapSlider     by remember { mutableFloatStateOf(satsToZapSlider(currentFilter.minZapSats)) }

    fun buildFilter() = FeedFilter(
        showTypes    = showTypes,
        sinceHours   = sinceHours,
        minReplies   = minReplies.toInt(),
        minReposts   = minReposts.toInt(),
        minReactions = minReactions.toInt(),
        minZapSats   = zapSliderToSats(zapSlider),
    )

    fun reset() {
        showTypes    = setOf(ShowType.ALL)
        sinceHours   = null
        minReplies   = 0f
        minReposts   = 0f
        minReactions = 0f
        zapSlider    = 0f
    }

    ModalBottomSheet(
        onDismissRequest = {
            onApply(buildFilter())
            onDismiss()
        },
        sheetState     = sheetState,
        containerColor = Surface1,
        shape          = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        dragHandle     = {
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
            // ── SHOW section ─────────────────────────────────────────────
            SectionLabel("SHOW")
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement   = Arrangement.spacedBy(8.dp),
            ) {
                val isAll = ShowType.ALL in showTypes

                FilterChip("All", isAll) {
                    showTypes = setOf(ShowType.ALL)
                }

                fun toggleType(type: ShowType) {
                    val current = if (isAll) emptySet() else showTypes
                    val next = if (type in current) current - type else current + type
                    showTypes = if (next.isEmpty()) setOf(ShowType.ALL) else next
                }

                FilterChip("Text", !isAll && ShowType.TEXT in showTypes) { toggleType(ShowType.TEXT) }
                FilterChip("Images", !isAll && ShowType.IMAGES in showTypes) { toggleType(ShowType.IMAGES) }
                FilterChip("Video", !isAll && ShowType.VIDEO in showTypes) { toggleType(ShowType.VIDEO) }
                FilterChip("Articles", !isAll && ShowType.ARTICLES in showTypes) { toggleType(ShowType.ARTICLES) }
                FilterChip("Reposts", !isAll && ShowType.REPOSTS in showTypes) { toggleType(ShowType.REPOSTS) }
            }

            Spacer(Modifier.height(20.dp))

            // ── WHEN section ─────────────────────────────────────────────
            SectionLabel("WHEN")
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement   = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    1 to "1h", 6 to "6h", 24 to "24h",
                    168 to "Week", 720 to "Month", null to "All",
                ).forEach { (hours, label) ->
                    FilterChip(label, sinceHours == hours) { sinceHours = hours }
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── ENGAGEMENT section ───────────────────────────────────────
            SectionLabel("ENGAGEMENT")
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                EngagementSlider(
                    label = "\uD83D\uDCAC Replies",
                    value = minReplies,
                    onValueChange = { minReplies = it },
                    range = 0f..100f,
                    steps = 99,
                    displayValue = minReplies.toInt().toString(),
                )
                EngagementSlider(
                    label = "\uD83D\uDD04 Reposts",
                    value = minReposts,
                    onValueChange = { minReposts = it },
                    range = 0f..100f,
                    steps = 99,
                    displayValue = minReposts.toInt().toString(),
                )
                EngagementSlider(
                    label = "\u2764\uFE0F Reactions",
                    value = minReactions,
                    onValueChange = { minReactions = it },
                    range = 0f..100f,
                    steps = 99,
                    displayValue = minReactions.toInt().toString(),
                )
                EngagementSlider(
                    label = "\u26A1 Zaps (sats)",
                    value = zapSlider,
                    onValueChange = { zapSlider = it },
                    range = 0f..1f,
                    steps = ZAP_BREAKPOINTS.size - 2,
                    displayValue = zapSliderToSats(zapSlider).toCompactSats(),
                )
            }

            Spacer(Modifier.height(20.dp))

            // ── Actions ──────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = { reset() }) {
                    Text("Reset", color = Color(0xFF888888), fontSize = AppType.body)
                }
                TextButton(onClick = {
                    onApply(buildFilter())
                    onDismiss()
                }) {
                    Text("Apply", color = Cyan, fontSize = AppType.body, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text       = text,
        color      = Color(0xFF555555),
        fontSize   = AppType.caption,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        modifier   = Modifier.padding(start = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun EngagementSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    displayValue: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text  = label,
            color = Color(0xFFBBBBBB),
            fontSize = AppType.bodySmall,
            modifier = Modifier.width(110.dp),
        )
        Slider(
            value         = value,
            onValueChange = onValueChange,
            valueRange    = range,
            steps         = steps,
            modifier      = Modifier.weight(1f),
            colors        = SliderDefaults.colors(
                thumbColor            = Cyan,
                activeTrackColor      = Cyan,
                inactiveTrackColor    = Color(0xFF333333),
                activeTickColor       = Color.Transparent,
                inactiveTickColor     = Color.Transparent,
            ),
        )
        Text(
            text  = displayValue,
            color = if (value > 0f) Cyan else Color(0xFF666666),
            fontSize = AppType.bodySmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(48.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
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
            fontSize   = AppType.bodySmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
