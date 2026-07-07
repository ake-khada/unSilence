package com.unsilence.app.data

/** Realistic browser UA — many sites (yahoo.co.jp, etc.) 403 bot-like UAs. */
const val BROWSER_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

/** Pubkey of the trustedrelays.xyz operator who publishes kind-30385 relay-trust events. */
const val TRUST_SCORE_PROVIDER_PUBKEY =
    "ad3cdbe9fb09b8edf7b3e0e5286d66e58b58eaa64d061bbcf3a935edf8abf421"

/** Default NIP-85 user-level WoT provider: NosFabrica house provider signing key. */
const val DEFAULT_WOT_PROVIDER_PUBKEY =
    "e6aefe2087dcc55cfe547a86523e8fc75a04133721254d7acbd7277897f05d56"

const val DEFAULT_WOT_RELAY = "wss://nip85.nosfabrica.com"

/** Public kind-10040 provider registries may be hosted separately from assertions. */
val WOT_REGISTRY_LOOKUP_RELAYS = listOf(
    DEFAULT_WOT_RELAY,
    "wss://nip85.brainstorm.world",
    "wss://nos.lol",
)
