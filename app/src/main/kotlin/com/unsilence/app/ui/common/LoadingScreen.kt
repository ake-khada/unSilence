package com.unsilence.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val AccentCyan = Color(0xFF22D3D3)

@Composable
fun LoadingScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LogoMark(sizeDp = 64.dp)
            Spacer(modifier = Modifier.width(28.dp))
            Column {
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Light, color = AccentCyan.copy(alpha = 0.55f))) {
                            append("un")
                        }
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = AccentCyan)) {
                            append("Silence")
                        }
                    },
                    fontSize = 32.sp,
                    letterSpacing = (-1).sp,
                )
                Text(
                    text = "A RELAY BROWSER",
                    color = AccentCyan.copy(alpha = 0.55f),
                    fontSize = 11.sp,
                    letterSpacing = 2.5.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
