package com.unsilence.app.ui.settings.console

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LogcatReader @Inject constructor() {
    suspend fun readOwnProcessTail(limit: Int = 300): Result<List<ConsoleLogLine>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val process = ProcessBuilder(
                    "logcat",
                    "-d",
                    "--pid=${android.os.Process.myPid()}",
                    "-t",
                    limit.coerceAtLeast(1).toString(),
                    "-v",
                    "time",
                )
                    .redirectErrorStream(true)
                    .start()

                val lines = process.inputStream.bufferedReader().use { it.readLines() }
                if (!process.waitFor(4, TimeUnit.SECONDS)) {
                    process.destroy()
                    error("logcat timed out")
                }
                val exit = process.exitValue()
                if (exit != 0) {
                    error(lines.joinToString("\n").ifBlank { "logcat exited $exit" })
                }
                lines.map(::parseLogcatLine).asReversed()
            }
        }
}
