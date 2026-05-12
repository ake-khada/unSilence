package com.unsilence.app.data.model

/** NIP-56 report type tags. */
enum class ReportType(val tagValue: String, val displayName: String) {
    NUDITY("nudity", "Nudity"),
    MALWARE("malware", "Malware"),
    PROFANITY("profanity", "Profanity"),
    ILLEGAL("illegal", "Illegal"),
    SPAM("spam", "Spam"),
    IMPERSONATION("impersonation", "Impersonation"),
    OTHER("other", "Other"),
}
