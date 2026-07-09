package com.unsilence.app.ui.settings.console

enum class ConsoleLogFilter(val label: String) {
    ALL("All"),
    WOT("WoT"),
    RELAY("Relay"),
    MES("MES"),
}

data class ConsoleLogLine(
    val time: String,
    val level: String,
    val tag: String,
    val message: String,
    val raw: String,
    val malformed: Boolean = false,
)

data class ConsoleDiagnosticsBundle(
    val relaySummary: String,
    val storeSummary: String,
    val gatesSummary: String,
    val logs: List<ConsoleLogLine>,
)

private val ThreadTimeRegex =
    Regex("""^(\d\d-\d\d\s+\d\d:\d\d:\d\d\.\d{3})\s+\d+\s+\d+\s+([VDIWEAF])\s+([^:]+):\s?(.*)$""")
private val LegacyTimeRegex =
    Regex("""^(\d\d-\d\d\s+\d\d:\d\d:\d\d\.\d{3})\s+([VDIWEAF])\/(.+?)\(\s*\d+\):\s?(.*)$""")

fun parseLogcatLine(raw: String): ConsoleLogLine {
    ThreadTimeRegex.matchEntire(raw)?.let { match ->
        return ConsoleLogLine(
            time = match.groupValues[1],
            level = match.groupValues[2],
            tag = match.groupValues[3].trim(),
            message = match.groupValues[4],
            raw = raw,
        )
    }
    LegacyTimeRegex.matchEntire(raw)?.let { match ->
        return ConsoleLogLine(
            time = match.groupValues[1],
            level = match.groupValues[2],
            tag = match.groupValues[3].trim(),
            message = match.groupValues[4],
            raw = raw,
        )
    }
    return ConsoleLogLine(
        time = "",
        level = "?",
        tag = "logcat",
        message = raw,
        raw = raw,
        malformed = true,
    )
}

fun filterLogLines(lines: List<ConsoleLogLine>, filter: ConsoleLogFilter): List<ConsoleLogLine> =
    lines.filter { it.matches(filter) }

fun ConsoleLogLine.matches(filter: ConsoleLogFilter): Boolean {
    if (filter == ConsoleLogFilter.ALL) return true
    val haystack = "$tag $message"
    return when (filter) {
        ConsoleLogFilter.ALL -> true
        ConsoleLogFilter.WOT -> listOf("WoT", "Wot", "wot", "NIP85", "SocialGraph").any {
            haystack.contains(it)
        }
        ConsoleLogFilter.RELAY -> listOf("Relay", "Subscription", "EventProcessor", "CardHydrator", "Timeline", "Outbox").any {
            haystack.contains(it)
        }
        ConsoleLogFilter.MES -> listOf("MES", "MemoryEventStore", "Snapshot").any {
            haystack.contains(it)
        }
    }
}

fun formatConsoleBytes(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L)
    if (safe < 1024L) return "$safe B"
    val units = listOf("KB", "MB", "GB")
    var value = safe.toDouble() / 1024.0
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    return String.format(java.util.Locale.US, "%.1f %s", value, units[unitIndex])
}

fun formatConsoleAge(timestampMs: Long, nowMs: Long): String {
    if (timestampMs <= 0L) return "never"
    val seconds = ((nowMs - timestampMs).coerceAtLeast(0L) / 1000L)
    return formatDurationSeconds(seconds)
}

fun formatSnapshotAgeSeconds(ageSeconds: Long): String =
    if (ageSeconds == Long.MAX_VALUE) "none" else formatDurationSeconds(ageSeconds)

private fun formatDurationSeconds(seconds: Long): String = when {
    seconds < 60L -> "${seconds}s"
    seconds < 3_600L -> "${seconds / 60L}m"
    seconds < 86_400L -> "${seconds / 3_600L}h"
    else -> "${seconds / 86_400L}d"
}

fun formatDiagnosticsBundle(bundle: ConsoleDiagnosticsBundle): String =
    buildString {
        appendLine("unSilence diagnostics")
        appendLine()
        appendLine("Relays")
        appendLine(bundle.relaySummary)
        appendLine()
        appendLine("Store & gates")
        appendLine(bundle.storeSummary)
        appendLine(bundle.gatesSummary)
        appendLine()
        appendLine("Log tail")
        if (bundle.logs.isEmpty()) {
            appendLine("(empty)")
        } else {
            bundle.logs.forEach { line ->
                appendLine("${line.time} ${line.level}/${line.tag}: ${line.message}".trim())
            }
        }
    }.trimEnd()
