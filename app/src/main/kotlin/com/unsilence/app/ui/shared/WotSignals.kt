package com.unsilence.app.ui.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unsilence.app.data.memory.WotAssertionEntity
import com.unsilence.app.data.memory.WotLookup
import com.unsilence.app.data.relay.FeedWotDisplayMode
import com.unsilence.app.data.relay.FeedWotSignal
import com.unsilence.app.data.relay.wotFeedSignal
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.BorderSubtle
import com.unsilence.app.ui.theme.Like
import com.unsilence.app.ui.theme.Mint
import com.unsilence.app.ui.theme.Surface2
import com.unsilence.app.ui.theme.Text3
import com.unsilence.app.ui.theme.TextSecondary
import com.unsilence.app.ui.theme.Zap

@Composable
fun WotFeedMetaTimestamp(
    lookup: WotLookup?,
    mode: FeedWotDisplayMode,
    timestamp: String,
    modifier: Modifier = Modifier,
    timestampColor: Color = TextSecondary,
) {
    val signal = wotFeedSignal(lookup, mode)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (signal != null) {
            val (text, color) = when (signal) {
                is FeedWotSignal.Rank -> signal.rank.toString() to
                    if (signal.distrusted) Like else wotTierColor(signal.rank)
                FeedWotSignal.Absent -> "-" to Text3
                is FeedWotSignal.Distant -> "${signal.hops}h" to Zap
                is FeedWotSignal.Distrusted -> "!" to Like
            }
            Text(
                text = text,
                color = color,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
            Text(
                text = " · ",
                color = Text3,
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
        Text(
            text = timestamp,
            color = timestampColor,
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

fun hasWotFeedSignal(lookup: WotLookup?, mode: FeedWotDisplayMode): Boolean =
    wotFeedSignal(lookup, mode) != null

@Composable
fun ProfileWotInlineLabel(
    assertion: WotAssertionEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .widthIn(min = 50.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "WoT",
            color = Text3,
            fontSize = 10.5.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = assertion.rank.toString(),
            color = wotTierColor(assertion.rank),
            fontSize = 10.5.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

@Composable
fun WotSearchSignal(
    lookup: WotLookup?,
    modifier: Modifier = Modifier,
) {
    when (lookup) {
        is WotLookup.Scored -> WotScoreChip(assertion = lookup.assertion, modifier = modifier)
        WotLookup.Absent -> WotSearchAbsentText(
            text = "not in grapevine",
            color = Text3,
            modifier = modifier,
        )
        WotLookup.Pending, null -> Unit
    }
}

@Composable
fun WotScoreChip(
    assertion: WotAssertionEntity,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val color = wotTierColor(assertion.rank)
    val text = buildString {
        append("WoT ")
        append(assertion.rank)
        assertion.hops?.let { append(" · ").append(it).append(" hops") }
    }
    val clickable = if (onClick != null) modifier.clickable(onClick = onClick) else modifier
    Text(
        text = text,
        color = color,
        fontSize = AppType.caption,
        fontWeight = FontWeight.SemiBold,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        modifier = clickable
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(999.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

@Composable
fun WotTierDot(
    rank: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(wotTierColor(rank))
            .border(1.dp, Black.copy(alpha = 0.65f), CircleShape),
    )
}

@Composable
private fun WotSearchAbsentText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
fun WotBreakdownProvenance(
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Surface2)
            .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(Mint),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            color = TextSecondary,
            fontSize = AppType.caption,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

fun wotTierColor(rank: Int): Color = when {
    rank >= 70 -> Mint
    rank >= 40 -> Zap
    else -> Like
}
