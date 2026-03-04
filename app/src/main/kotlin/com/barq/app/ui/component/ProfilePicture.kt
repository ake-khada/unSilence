package com.barq.app.ui.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.barq.app.R

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProfilePicture(
    url: String?,
    modifier: Modifier = Modifier,
    pubkey: String? = null,
    size: Int = 40,
    showFollowBadge: Boolean = false,
    showBlockedBadge: Boolean = false,
    highlighted: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null
) {
    if (highlighted) {
        HighlightedProfilePicture(url, pubkey, modifier, size, showFollowBadge, showBlockedBadge, onClick, onLongPress)
    } else {
        BaseProfilePicture(url, pubkey, modifier, size, showFollowBadge, showBlockedBadge, onClick, onLongPress)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HighlightedProfilePicture(
    url: String?,
    pubkey: String?,
    modifier: Modifier,
    size: Int,
    showFollowBadge: Boolean,
    showBlockedBadge: Boolean,
    onClick: (() -> Unit)?,
    onLongPress: (() -> Unit)?
) {
    val haptic = LocalHapticFeedback.current
    val transition = rememberInfiniteTransition(label = "highlight")

    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.10f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val glowAlpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    val orange = Color(0xFFFF9800)
    val glowSpread = (size * 0.2f).coerceIn(5f, 10f)

    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .drawBehind {
                val center = Offset(this.size.width / 2f, this.size.height / 2f)
                val radius = this.size.minDimension / 2f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            orange.copy(alpha = glowAlpha * 0.8f),
                            orange.copy(alpha = glowAlpha * 0.3f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = radius + glowSpread.dp.toPx()
                    ),
                    center = center,
                    radius = radius + glowSpread.dp.toPx()
                )
            }
    ) {
        AvatarContent(
            url = url,
            pubkey = pubkey,
            size = size,
            onClick = onClick,
            onLongPress = onLongPress,
            haptic = haptic
        )
        if (showBlockedBadge) {
            BlockedBadge(size, Modifier.align(Alignment.BottomEnd))
        } else if (showFollowBadge) {
            FollowBadge(size, Modifier.align(Alignment.BottomEnd))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BaseProfilePicture(
    url: String?,
    pubkey: String?,
    modifier: Modifier,
    size: Int,
    showFollowBadge: Boolean,
    showBlockedBadge: Boolean,
    onClick: (() -> Unit)?,
    onLongPress: (() -> Unit)?
) {
    val haptic = LocalHapticFeedback.current
    Box(modifier = modifier) {
        AvatarContent(
            url = url,
            pubkey = pubkey,
            size = size,
            onClick = onClick,
            onLongPress = onLongPress,
            haptic = haptic
        )
        if (showBlockedBadge) {
            BlockedBadge(size, Modifier.align(Alignment.BottomEnd))
        } else if (showFollowBadge) {
            FollowBadge(size, Modifier.align(Alignment.BottomEnd))
        }
    }
}

// Shared avatar rendering: generated circle behind, AsyncImage on top.
// When url is null/blank or fails to load, the generated avatar shows through.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AvatarContent(
    url: String?,
    pubkey: String?,
    size: Int,
    onClick: (() -> Unit)?,
    onLongPress: (() -> Unit)?,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback
) {
    val interactionModifier = if (onClick != null || onLongPress != null) {
        Modifier.combinedClickable(
            onClick = { onClick?.invoke() },
            onLongClick = onLongPress?.let { lp -> {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                lp()
            }}
        )
    } else Modifier

    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .then(interactionModifier),
        contentAlignment = Alignment.Center
    ) {
        // Generated avatar — always present as fallback
        GeneratedAvatar(pubkey = pubkey, size = size)

        // Profile image on top — fallback/error set to null so generated avatar shows through
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = "Profile picture",
                contentScale = ContentScale.Crop,
                fallback = null,
                error = null,
                modifier = Modifier
                    .size(size.dp)
                    .clip(CircleShape)
            )
        }
    }
}

@Composable
private fun GeneratedAvatar(pubkey: String?, size: Int) {
    val bgColor = remember(pubkey) {
        if (pubkey.isNullOrBlank()) {
            Color(0xFF444444)
        } else {
            val hue = (pubkey.hashCode().and(0x7FFFFFFF) % 360).toFloat()
            Color.hsl(hue, 0.6f, 0.4f)
        }
    }
    val initials = remember(pubkey) {
        pubkey?.take(2)?.uppercase() ?: "??"
    }
    val fontSize = (size * 0.35f).sp

    Box(
        modifier = Modifier
            .size(size.dp)
            .background(bgColor, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initials,
            color = Color.White,
            fontSize = fontSize,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            lineHeight = fontSize
        )
    }
}

@Composable
private fun FollowBadge(size: Int, modifier: Modifier = Modifier) {
    val badgeSize = (size * 0.3f).coerceIn(10f, 16f)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .offset(x = 1.dp, y = 1.dp)
            .size(badgeSize.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape)
    ) {
        Icon(
            Icons.Default.Check,
            contentDescription = "Following",
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size((badgeSize * 0.65f).dp)
        )
    }
}

@Composable
private fun BlockedBadge(size: Int, modifier: Modifier = Modifier) {
    val badgeSize = (size * 0.3f).coerceIn(10f, 16f)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .offset(x = 1.dp, y = 1.dp)
            .size(badgeSize.dp)
            .background(MaterialTheme.colorScheme.error, CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape)
    ) {
        Icon(
            Icons.Default.Block,
            contentDescription = "Blocked",
            tint = MaterialTheme.colorScheme.onError,
            modifier = Modifier.size((badgeSize * 0.65f).dp)
        )
    }
}
