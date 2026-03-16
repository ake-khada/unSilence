package com.unsilence.app.data.relay

enum class CoverageStatus {
    NEVER_FETCHED,  // No coverage record for this scope
    LOADING,        // Fetch in progress
    COMPLETE,       // ALL expected (subId, relayUrl) lanes sent EOSE
    PARTIAL,        // TERMINAL: some lanes succeeded, some failed
    STALE,          // Was COMPLETE/PARTIAL but older than staleAfterMs
    FAILED,         // TERMINAL: ALL lanes failed this fetch attempt
}
