package com.unsilence.app.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.ui.common.IdentIcon
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Cyan
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.TextSecondary
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private val DEFAULT_RELAYS = listOf(
    "wss://relay.damus.io",
    "wss://nos.lol",
    "wss://nostr.mom",
    "wss://relay.nostr.net",
)

@Composable
fun ComposeScreen(
    keyManager: KeyManager,
    nostrClient: NostrClient? = null,
    onDismiss: () -> Unit,
) {
    val pubkeyHex = keyManager.getPublicKeyHex()
    var text by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Black),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            // ── Top bar ───────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Sizing.topBarHeight)
                    .padding(horizontal = Spacing.medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(
                        text      = "Cancel",
                        color     = Cyan,
                        fontSize  = 15.sp,
                    )
                }

                Spacer(Modifier.weight(1f))

                Button(
                    onClick  = {
                        if (text.isNotBlank()) {
                            scope.launch(Dispatchers.IO) {
                                publishNote(keyManager, nostrClient, text.trim())
                            }
                            onDismiss()
                        }
                    },
                    enabled  = text.isNotBlank(),
                    shape    = RoundedCornerShape(24.dp),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor         = Cyan,
                        contentColor           = Black,
                        disabledContainerColor = Color(0xFF004D59),
                        disabledContentColor   = Color(0xFF007A8A),
                    ),
                    modifier = Modifier.height(36.dp),
                ) {
                    Text(
                        text       = "Post",
                        fontSize   = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            // ── Compose area ─────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.medium, vertical = Spacing.small),
                verticalAlignment = Alignment.Top,
            ) {
                // Avatar
                Box(
                    modifier = Modifier
                        .size(Sizing.avatar)
                        .clip(CircleShape),
                ) {
                    if (pubkeyHex != null) {
                        IdentIcon(pubkey = pubkeyHex, modifier = Modifier.size(Sizing.avatar))
                    } else {
                        Box(modifier = Modifier.size(Sizing.avatar).background(Color(0xFF333333)))
                    }
                }

                Spacer(Modifier.width(Spacing.small))

                BasicTextField(
                    value          = text,
                    onValueChange  = { text = it },
                    modifier       = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    textStyle      = TextStyle(
                        color    = Color.White,
                        fontSize = 16.sp,
                    ),
                    cursorBrush    = SolidColor(Cyan),
                    decorationBox  = { inner ->
                        if (text.isEmpty()) {
                            Text(
                                text     = "What's on your mind?",
                                color    = TextSecondary,
                                fontSize = 16.sp,
                            )
                        }
                        inner()
                    },
                )
            }
        }
    }
}

private fun publishNote(keyManager: KeyManager, nostrClient: NostrClient?, content: String) {
    val privKeyHex = keyManager.getPrivateKeyHex() ?: return
    val privKeyBytes = privKeyHex.hexToByteArray()
    val keyPair = KeyPair(privKey = privKeyBytes)
    val signer = NostrSignerInternal(keyPair)
    val template = TextNoteEvent.build(note = content)

    // Sign synchronously via NostrSignerInternal's underlying sync path
    // NostrSignerInternal.sign() is suspend; use runBlocking in IO context
    val signedEvent = runCatching {
        kotlinx.coroutines.runBlocking { signer.sign(template) }
    }.getOrNull() ?: return

    if (nostrClient != null) {
        nostrClient.send(
            event     = signedEvent,
            relayList = DEFAULT_RELAYS.map { NormalizedRelayUrl(it) }.toSet(),
        )
    }
}
