package com.unsilence.app.ui.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import com.unsilence.app.ui.theme.Cyan
import kotlinx.coroutines.delay

private val LOADING_MESSAGES = listOf(
    "Brewing your feed\u2026",
    "Whispering to relays\u2026",
    "Decrypting the signal\u2026",
    "Tuning into the nostrverse\u2026",
    "Keys, not credentials\u2026",
    "Connecting to freedom\u2026",
    "Your relay, your rules\u2026",
    "Censorship-resistant by design\u2026",
)

@Composable
fun LoadingScreen() {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )

    var messageIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2500)
            messageIndex = (messageIndex + 1) % LOADING_MESSAGES.size
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "uS",
                color = Cyan,
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.alpha(pulseAlpha),
            )

            Spacer(Modifier.height(24.dp))

            AnimatedContent(
                targetState = messageIndex,
                transitionSpec = {
                    fadeIn(tween(400)) togetherWith fadeOut(tween(400))
                },
                label = "message",
            ) { index ->
                Text(
                    text = LOADING_MESSAGES[index],
                    color = Color.White,
                    fontSize = 14.sp,
                )
            }
        }
    }
}
