package com.unsilence.app.data.blossom

data class BlossomServerInfo(
    val url: String,
    val displayName: String,
    val freeTierNote: String,
    val isDefaultSelected: Boolean,
)

// Validated end-to-end against blossom.primal.net, blossom.band, and
// blossom.yakihonne.com via Phase 2.0 smoke test. Azzamo (blossom.azzamo.net)
// was tested and returned 404 on /upload — their service is premium-only
// and doesn't expose the BUD-01 endpoint to non-paying users. Users who
// want Azzamo specifically can add it via "+ Add custom server".
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
)
