package com.unsilence.app.data.blossom

data class BlossomServerInfo(
    val url: String,
    val displayName: String,
    val freeTierNote: String,
    val isDefaultSelected: Boolean,
)

val DEFAULT_BLOSSOM_SERVERS = listOf(
    BlossomServerInfo(
        url = "https://blossom.primal.net",
        displayName = "Primal Blossom",
        freeTierNote = "Default for Primal users; limits per provider",
        isDefaultSelected = true,
    ),
    BlossomServerInfo(
        url = "https://blossom.band",
        displayName = "Blossom.band",
        freeTierNote = "20 MiB free, 100 MiB paid · no retention limit",
        isDefaultSelected = false,
    ),
    BlossomServerInfo(
        url = "https://blossom.yakihonne.com",
        displayName = "YakiHonne Blossom",
        freeTierNote = "Free tier · limits per provider",
        isDefaultSelected = false,
    ),
    BlossomServerInfo(
        url = "https://blossom.azzamo.net",
        displayName = "Azzamo Blossom",
        freeTierNote = "Free tier with rate limits · Premium pay-as-you-go",
        isDefaultSelected = false,
    ),
)
