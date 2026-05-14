package com.unsilence.app.ui.onboarding

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unsilence.app.data.auth.AmberSigner
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.ui.common.LogoMark
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.BorderDefault
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Text3
import com.unsilence.app.ui.theme.TextSecondary
import com.unsilence.app.ui.theme.White

private val ButtonShape  = RoundedCornerShape(16.dp)
private val NeutralBorder = BorderStroke(1.dp, BorderDefault)
private val ButtonPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)

@Composable
fun OnboardingScreen(keyManager: KeyManager, onComplete: () -> Unit) {
    val context = LocalContext.current

    var showImportField by remember { mutableStateOf(false) }
    var importText      by remember { mutableStateOf("") }
    var importError     by remember { mutableStateOf<String?>(null) }

    // Amber login launcher
    val amberLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val pubkey = AmberSigner.parseLoginResult(result.data)
        if (pubkey != null) {
            keyManager.saveAmberLogin(pubkey)
            onComplete()
        } else {
            Toast.makeText(context, "Amber sign-in failed or was cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
            .padding(horizontal = 28.dp),
    ) {
        // 1. Status bar inset
        Spacer(Modifier.statusBarsPadding())

        // 2. Top weighted spacer
        Spacer(Modifier.weight(1f))

        // 3. Animated waveform mark
        LogoMark(sizeDp = 80.dp, color = Brand)

        // 4. Logo → headline
        Spacer(Modifier.height(Spacing.xxl))

        // 5. Headline
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = White)) {
                    append("Find your\npeople. Speak\n")
                }
                withStyle(SpanStyle(color = Brand)) {
                    append("freely.")
                }
            },
            fontSize      = 44.sp,
            fontWeight    = FontWeight.Bold,
            lineHeight    = 48.sp,
            letterSpacing = (-0.9).sp,
        )

        // 6. Headline → subtitle
        Spacer(Modifier.height(Spacing.large))

        // 7. Subtitle
        Text(
            text       = "unSilence is a Nostr client. Your identity lives on a key, not a server \u2014 and goes with you everywhere.",
            color      = TextSecondary,
            fontSize   = AppType.bodyLarge,
            lineHeight = 21.sp,
            modifier   = Modifier.widthIn(max = 320.dp),
        )

        // 8. Subtitle → buttons
        Spacer(Modifier.height(Spacing.xxl))

        // 9. PRIMARY — Create new identity
        Button(
            onClick = {
                keyManager.generateNewKey()
                onComplete()
            },
            shape          = ButtonShape,
            contentPadding = ButtonPadding,
            colors         = ButtonDefaults.buttonColors(
                containerColor = Brand,
                contentColor   = Color(0xFF001012),
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Create new identity", fontSize = AppType.bodyLarge, fontWeight = FontWeight.SemiBold)
        }

        // 10.
        Spacer(Modifier.height(Spacing.small))

        // 11. SECONDARY — I already have keys
        OutlinedButton(
            onClick        = { showImportField = !showImportField; importError = null },
            shape          = ButtonShape,
            border         = NeutralBorder,
            contentPadding = ButtonPadding,
            colors         = ButtonDefaults.outlinedButtonColors(contentColor = White),
            modifier       = Modifier.fillMaxWidth(),
        ) {
            Text("I already have keys", fontSize = AppType.bodyLarge)
        }

        // Import key expansion
        if (showImportField) {
            Spacer(Modifier.height(Spacing.small))
            OutlinedTextField(
                value          = importText,
                onValueChange  = { importText = it; importError = null },
                placeholder    = { Text("nsec1\u2026 or hex private key", color = TextSecondary, fontSize = AppType.body) },
                singleLine     = true,
                isError        = importError != null,
                supportingText = importError?.let { { Text(it, color = Color(0xFFCF6679)) } },
                colors         = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = Brand,
                    unfocusedBorderColor = TextSecondary,
                    cursorColor          = Brand,
                    focusedTextColor     = White,
                    unfocusedTextColor   = White,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.small))
            Button(
                onClick = {
                    if (keyManager.importKey(importText)) {
                        onComplete()
                    } else {
                        importError = "Invalid key \u2014 paste an nsec1\u2026 or 64-char hex key"
                    }
                },
                shape          = ButtonShape,
                contentPadding = ButtonPadding,
                colors         = ButtonDefaults.buttonColors(
                    containerColor = Brand,
                    contentColor   = Color(0xFF001012),
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Confirm", fontSize = AppType.bodyLarge, fontWeight = FontWeight.SemiBold)
            }
        }

        // 12.
        Spacer(Modifier.height(Spacing.small))

        // 13. TERTIARY — Sign in with Amber
        TextButton(
            onClick = {
                if (!AmberSigner.isInstalled(context)) {
                    Toast.makeText(context, "Amber app not installed", Toast.LENGTH_SHORT).show()
                } else {
                    amberLauncher.launch(AmberSigner.createLoginIntent())
                }
            },
            colors   = ButtonDefaults.textButtonColors(contentColor = TextSecondary),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector        = Icons.Filled.Key,
                contentDescription = null,
                modifier           = Modifier.padding(end = Spacing.medium).size(16.dp),
            )
            Text("Sign in with Amber", fontSize = AppType.body)
        }

        // 14. Buttons → footer
        Spacer(Modifier.height(Spacing.xl))

        // 15. Footer block, centered
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier            = Modifier.fillMaxWidth(),
        ) {
            Text(
                text          = "YOUR KEYS  \u00B7  YOUR VOICE  \u00B7  NO RECOVERY",
                color         = Text3,
                fontSize      = AppType.footnote,
                letterSpacing = 1.5.sp,
            )
            TextButton(
                onClick = { /* TODO: "What is Nostr?" bottom sheet */ },
                colors  = ButtonDefaults.textButtonColors(contentColor = TextSecondary),
            ) {
                Text("What is Nostr?", fontSize = AppType.bodyLarge)
            }
        }

        // 16. Bottom weighted spacer
        Spacer(Modifier.weight(0.5f))

        // 17. System navigation inset
        Spacer(Modifier.navigationBarsPadding())
    }
}
