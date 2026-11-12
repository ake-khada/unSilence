package com.unsilence.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.unsilence.app.ui.theme.AppTextStyles
import com.unsilence.app.ui.theme.TextSecondary
import com.unsilence.app.data.blossom.AvatarFallbackPolicy
import com.unsilence.app.ui.theme.Spacing

/** Device-local retrieval choices; never invokes the upload/server-list publication path. */
@Composable
internal fun AvatarFallbackServersSection(
    servers: List<String>,
    configuredServers: List<String> = emptyList(),
    enabled: Boolean = false,
    onChange: (List<String>) -> Unit,
) {
    var adding by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("https://") }
    val choices = (listOf("https://blossom.primal.net") + configuredServers + servers).mapNotNull(AvatarFallbackPolicy::server).distinct()
    val parsed = AvatarFallbackPolicy.server(input)
    val error = when {
        parsed in servers -> "Already selected"
        parsed == null && input != "https://" -> "Enter a full server address, such as blossom.primal.net."
        else -> null
    }
    Column {
        Text("Avatar fallback servers", style = AppTextStyles.body, color = Color.White, fontWeight = FontWeight.SemiBold)
        Text("Use avatar fallback servers: ${if (enabled) "On" else "Off"}", style = AppTextStyles.caption, color = TextSecondary)
        if (!enabled) Text("Enable in Content & Safety → Media & data.", style = AppTextStyles.caption, color = TextSecondary)
        Text(AVATAR_FALLBACK_DESCRIPTION, style = AppTextStyles.caption, color = TextSecondary)
        val primal = "https://blossom.primal.net"
        for (url in choices) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (url == primal) "Primal" else url.removePrefix("https://"), color = Color.White, style = AppTextStyles.body, modifier = Modifier.weight(1f).padding(vertical = Spacing.medium))
                Checkbox(checked = url in servers, onCheckedChange = { checked ->
                    onChange(if (checked) (servers + url).take(AvatarFallbackPolicy.MAX_SERVERS) else servers - url)
                }, enabled = url in servers || servers.size < AvatarFallbackPolicy.MAX_SERVERS)
            }
        }
        TextButton(onClick = { adding = true }, enabled = servers.size < AvatarFallbackPolicy.MAX_SERVERS) { Text("Add avatar fallback server") }
    }
    if (adding) AlertDialog(
        onDismissRequest = { adding = false },
        title = { Text("Add avatar fallback server") },
        text = { OutlinedTextField(value = input, onValueChange = { input = it }, label = { Text("Server URL") }, singleLine = true,
            isError = error != null,
            supportingText = { error?.let { Text(it) } }) },
        confirmButton = { TextButton(enabled = parsed != null && error == null, onClick = {
            AvatarFallbackPolicy.server(input)?.let { onChange((servers + it).distinct().take(AvatarFallbackPolicy.MAX_SERVERS)) }
            adding = false; input = "https://"
        }) { Text("Add") } },
        dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
    )
}
