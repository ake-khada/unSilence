package com.barq.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

@Composable
fun OnboardingRelayScreen(
    onContinue: (indexerRelays: List<String>, searchRelays: List<String>) -> Unit
) {
    var indexerRelays by remember {
        mutableStateOf(listOf(
            "wss://purplepag.es",
            "wss://indexer.coracle.social",
            "wss://user.kindpag.es"
        ))
    }
    var searchRelays by remember {
        mutableStateOf(listOf(
            "wss://relay.noswhere.com",
            "wss://search.nos.today"
        ))
    }
    var showAddIndexer by remember { mutableStateOf(false) }
    var addIndexerText by remember { mutableStateOf("") }
    var showAddSearch by remember { mutableStateOf(false) }
    var addSearchText by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000))
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 80.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = "Your Relays",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "These relays connect you to the network",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF888888)
                )
                Spacer(Modifier.height(8.dp))
            }

            item {
                RelaySection(
                    title = "Indexer Relays",
                    relays = indexerRelays,
                    onRemove = { url -> indexerRelays = indexerRelays - url },
                    showAdd = showAddIndexer,
                    addText = addIndexerText,
                    onAddTextChange = { addIndexerText = it },
                    onAddConfirm = {
                        val trimmed = addIndexerText.trim().trimEnd('/')
                        if (trimmed.isNotEmpty() && trimmed !in indexerRelays) {
                            indexerRelays = indexerRelays + trimmed
                        }
                        addIndexerText = ""
                        showAddIndexer = false
                    },
                    onShowAdd = { showAddIndexer = true },
                    onCancelAdd = { showAddIndexer = false; addIndexerText = "" }
                )
            }

            item {
                RelaySection(
                    title = "Search Relays",
                    relays = searchRelays,
                    onRemove = { url -> searchRelays = searchRelays - url },
                    showAdd = showAddSearch,
                    addText = addSearchText,
                    onAddTextChange = { addSearchText = it },
                    onAddConfirm = {
                        val trimmed = addSearchText.trim().trimEnd('/')
                        if (trimmed.isNotEmpty() && trimmed !in searchRelays) {
                            searchRelays = searchRelays + trimmed
                        }
                        addSearchText = ""
                        showAddSearch = false
                    },
                    onShowAdd = { showAddSearch = true },
                    onCancelAdd = { showAddSearch = false; addSearchText = "" }
                )
            }
        }

        Button(
            onClick = { onContinue(indexerRelays, searchRelays) },
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Continue")
        }
    }
}

@Composable
private fun RelaySection(
    title: String,
    relays: List<String>,
    onRemove: (String) -> Unit,
    showAdd: Boolean,
    addText: String,
    onAddTextChange: (String) -> Unit,
    onAddConfirm: () -> Unit,
    onShowAdd: () -> Unit,
    onCancelAdd: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0A0A0A)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White
                )
                TextButton(
                    onClick = onShowAdd,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("+ Add", style = MaterialTheme.typography.labelMedium)
                }
            }

            Spacer(Modifier.height(8.dp))

            relays.forEach { url ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = url.removePrefix("wss://").removePrefix("ws://"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFCCCCCC),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { onRemove(url) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Remove",
                            tint = Color(0xFF666666),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            if (showAdd) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = addText,
                    onValueChange = onAddTextChange,
                    placeholder = { Text("wss://relay.example.com", color = Color(0xFF555555)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onAddConfirm() }),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color(0xFF333333),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = MaterialTheme.colorScheme.primary
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onCancelAdd) { Text("Cancel") }
                    TextButton(onClick = onAddConfirm) { Text("Add") }
                }
            }
        }
    }
}
