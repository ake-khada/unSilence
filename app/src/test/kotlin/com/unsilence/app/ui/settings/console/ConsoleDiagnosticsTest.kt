package com.unsilence.app.ui.settings.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleDiagnosticsTest {
    @Test
    fun `parses thread-time logcat lines`() {
        val parsed = parseLogcatLine("07-09 11:22:33.444 123 456 D RelayPool: connected")

        assertEquals("07-09 11:22:33.444", parsed.time)
        assertEquals("D", parsed.level)
        assertEquals("RelayPool", parsed.tag)
        assertEquals("connected", parsed.message)
        assertFalse(parsed.malformed)
    }

    @Test
    fun `parses legacy time logcat lines`() {
        val parsed = parseLogcatLine("07-09 11:22:33.444 W/MES( 1234): snapshot saved")

        assertEquals("07-09 11:22:33.444", parsed.time)
        assertEquals("W", parsed.level)
        assertEquals("MES", parsed.tag)
        assertEquals("snapshot saved", parsed.message)
        assertFalse(parsed.malformed)
    }

    @Test
    fun `malformed logcat lines are retained`() {
        val parsed = parseLogcatLine("not a normal log line")

        assertEquals("logcat", parsed.tag)
        assertEquals("?", parsed.level)
        assertTrue(parsed.malformed)
        assertEquals("not a normal log line", parsed.message)
    }

    @Test
    fun `tag filters match expected diagnostic families`() {
        val lines = listOf(
            parseLogcatLine("07-09 11:22:33.444 123 456 D RelayPool: connected"),
            parseLogcatLine("07-09 11:22:33.445 123 456 D AppBootstrapper: WoT coverage 2/3"),
            parseLogcatLine("07-09 11:22:33.446 123 456 D SnapshotScheduler: saved"),
            parseLogcatLine("07-09 11:22:33.447 123 456 D FeedScreen: recomposed"),
        )

        assertEquals(4, filterLogLines(lines, ConsoleLogFilter.ALL).size)
        assertEquals(listOf("AppBootstrapper"), filterLogLines(lines, ConsoleLogFilter.WOT).map { it.tag })
        assertEquals(listOf("RelayPool"), filterLogLines(lines, ConsoleLogFilter.RELAY).map { it.tag })
        assertEquals(listOf("SnapshotScheduler"), filterLogLines(lines, ConsoleLogFilter.MES).map { it.tag })
    }

    @Test
    fun `formats bytes and ages compactly`() {
        assertEquals("0 B", formatConsoleBytes(0))
        assertEquals("1023 B", formatConsoleBytes(1023))
        assertEquals("1.0 KB", formatConsoleBytes(1024))
        assertEquals("1.5 MB", formatConsoleBytes(1_572_864))

        val now = 10_000_000L
        assertEquals("never", formatConsoleAge(0, now))
        assertEquals("30s", formatConsoleAge(now - 30_000L, now))
        assertEquals("5m", formatConsoleAge(now - 300_000L, now))
        assertEquals("2h", formatConsoleAge(now - 7_200_000L, now))
        assertEquals("none", formatSnapshotAgeSeconds(Long.MAX_VALUE))
    }

    @Test
    fun `formats diagnostics bundle`() {
        val bundle = formatDiagnosticsBundle(
            ConsoleDiagnosticsBundle(
                relaySummary = "2 connected · 1 idle",
                storeSummary = "100 events · 1.0 MB",
                gatesSummary = "trust 1h · monitors 2h · WoT 3h · 4/5 scored",
                logs = listOf(parseLogcatLine("07-09 11:22:33.444 123 456 D RelayPool: connected")),
            ),
        )

        assertTrue(bundle.contains("unSilence diagnostics"))
        assertTrue(bundle.contains("2 connected"))
        assertTrue(bundle.contains("D/RelayPool: connected"))
    }
}
