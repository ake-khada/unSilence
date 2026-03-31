package com.unsilence.app.ui.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val SPLASH_LINES = listOf(
    "Summoning notes from the void\u2026",
    "Convincing relays to cooperate\u2026",
    "Decentralizing your patience\u2026",
    "Asking Satoshi for directions\u2026",
    "Untangling the web of trust\u2026",
    "Herding digital cats\u2026",
    "Mining for content\u2026",
    "Verifying signatures, one by one\u2026",
    "Loading\u2026 unlike fiat, this is backed by proof of work",
    "Whispering to relays\u2026",
    "Building consensus on your feed\u2026",
    "Cypherpunks write code. We\u2019re loading it.",
    "Freedom of speech loading\u2026",
    "Your keys, your notes, your wait\u2026",
    "Bootstrapping sovereignty\u2026",
)

@Composable
fun LoadingScreen() {
    var currentLine by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(2500)
            currentLine = (currentLine + 1) % SPLASH_LINES.size
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
                text = "unSilence",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF00E5FF),
            )
            Spacer(modifier = Modifier.height(24.dp))
            AnimatedContent(
                targetState = currentLine,
                transitionSpec = {
                    fadeIn(tween(400)) togetherWith fadeOut(tween(400))
                },
                label = "splash",
            ) { index ->
                Text(
                    text = SPLASH_LINES[index],
                    fontSize = 14.sp,
                    color = Color.Gray,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
        }
    }
}
