package com.unsilence.app.ui.onboarding

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unsilence.app.data.auth.AmberSigner
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Cyan
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.TextSecondary

private val ButtonShape = RoundedCornerShape(Sizing.mediaCornerRadius)  // 8.dp
private val CyanBorder  = BorderStroke(1.dp, Cyan)

@Composable
fun OnboardingScreen(keyManager: KeyManager, onComplete: () -> Unit) {
    val context = LocalContext.current

    var showImportField by remember { mutableStateOf(false) }
    var importText      by remember { mutableStateOf("") }
    var importError     by remember { mutableStateOf<String?>(null) }

    // ── Amber login launcher ──────────────────────────────────────────────────
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
            .padding(horizontal = 32.dp),
        verticalArrangement   = Arrangement.Center,
        horizontalAlignment   = Alignment.CenterHorizontally,
    ) {
        // ── Brand ─────────────────────────────────────────────────────────────
        Text(
            text       = "unSilence",
            color      = Color.White,
            fontSize   = 24.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text     = "Nostr client",
            color    = TextSecondary,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 6.dp),
        )

        Spacer(Modifier.height(48.dp))

        // ── Create new account ────────────────────────────────────────────────
        Button(
            onClick = {
                keyManager.generateNewKey()
                onComplete()
            },
            shape    = ButtonShape,
            colors   = ButtonDefaults.buttonColors(
                containerColor = Cyan,
                contentColor   = Black,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(Sizing.topBarHeight),  // 52.dp
        ) {
            Text(text = "Create New Account", fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(12.dp))

        // ── Import key ────────────────────────────────────────────────────────
        OutlinedButton(
            onClick  = { showImportField = !showImportField; importError = null },
            shape    = ButtonShape,
            border   = CyanBorder,
            colors   = ButtonDefaults.outlinedButtonColors(contentColor = Cyan),
            modifier = Modifier
                .fillMaxWidth()
                .height(Sizing.topBarHeight),
        ) {
            Text(text = "Import Key")
        }

        if (showImportField) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value         = importText,
                onValueChange = { importText = it; importError = null },
                placeholder   = { Text("nsec1… or hex private key", color = TextSecondary, fontSize = 13.sp) },
                singleLine    = true,
                isError       = importError != null,
                supportingText = importError?.let { { Text(it, color = Color(0xFFCF6679)) } },
                colors        = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = Cyan,
                    unfocusedBorderColor = TextSecondary,
                    cursorColor          = Cyan,
                    focusedTextColor     = Color.White,
                    unfocusedTextColor   = Color.White,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    if (keyManager.importKey(importText)) {
                        onComplete()
                    } else {
                        importError = "Invalid key — paste an nsec1… or 64-char hex key"
                    }
                },
                shape  = ButtonShape,
                colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Black),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Confirm", fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── Sign in with Amber ────────────────────────────────────────────────
        OutlinedButton(
            onClick = {
                if (!AmberSigner.isInstalled(context)) {
                    Toast.makeText(context, "Amber app not installed", Toast.LENGTH_SHORT).show()
                } else {
                    amberLauncher.launch(AmberSigner.createLoginIntent())
                }
            },
            shape    = ButtonShape,
            border   = CyanBorder,
            colors   = ButtonDefaults.outlinedButtonColors(contentColor = Cyan),
            modifier = Modifier
                .fillMaxWidth()
                .height(Sizing.topBarHeight),
        ) {
            Icon(
                imageVector        = Icons.Filled.Key,
                contentDescription = null,
                modifier           = Modifier.padding(end = 8.dp),
            )
            Text(text = "Sign in with Amber")
        }
    }
}
