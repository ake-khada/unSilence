package com.unsilence.app.ui.settings.console

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.MesSizeSnapshot
import com.unsilence.app.data.memory.SnapshotScheduler
import com.unsilence.app.data.relay.ConnectionPurpose
import com.unsilence.app.data.relay.RelayConnectionDebugSnapshot
import com.unsilence.app.data.relay.RelayDirectoryEntry
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.RelayPreferencesStore
import com.unsilence.app.data.relay.RelayState
import com.unsilence.app.data.relay.WotCoverage
import com.unsilence.app.data.relay.computeWotCoverage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConsoleUiState(
    val refreshing: Boolean = false,
    val relays: List<ConsoleRelayRow> = emptyList(),
    val relaySummary: String = "0 connected · 0 connecting · 0 inactive",
    val relayCatalogSummary: String = "0 catalogued for discovery",
    val store: MesSizeSnapshot? = null,
    val snapshotAgeSeconds: Long = Long.MAX_VALUE,
    val gates: ConsoleGateState = ConsoleGateState(),
    val selectedFilter: ConsoleLogFilter = ConsoleLogFilter.ALL,
    val logs: List<ConsoleLogLine> = emptyList(),
    val filteredLogs: List<ConsoleLogLine> = emptyList(),
    val logsUnavailableMessage: String? = null,
) {
    val storeSummary: String
        get() = store?.let {
            "${it.eventCount} events · ${formatConsoleBytes(it.totalEstimatedBytes)} · ${it.profileCount} profiles"
        } ?: "Store snapshot unavailable"

    val gatesSummary: String
        get() = gates.format()
}

data class ConsoleRelayRow(
    val url: String,
    val state: RelayState?,
    val purposes: Set<ConnectionPurpose>,
    val oneShotCount: Int,
    val queuedReqCount: Int,
    val hasActiveSubscription: Boolean,
    val directory: RelayDirectoryEntry?,
) {
    val isTransient: Boolean
        get() = ConnectionPurpose.PERSISTENT !in purposes &&
            (hasActiveSubscription || oneShotCount > 0 || queuedReqCount > 0)
}

data class ConsoleGateState(
    val lastTrustFetchAt: Long = 0L,
    val lastMonitorFetchAt: Long = 0L,
    val lastWotFetchAt: Long = 0L,
    val wotTargetsHash: String = "",
    val coverage: WotCoverage = WotCoverage(0, 0),
    val nowMs: Long = System.currentTimeMillis(),
) {
    fun format(): String =
        "trust ${formatConsoleAge(lastTrustFetchAt, nowMs)} · " +
            "monitors ${formatConsoleAge(lastMonitorFetchAt, nowMs)} · " +
            "WoT ${formatConsoleAge(lastWotFetchAt, nowMs)} · " +
            "${coverage.scored}/${coverage.total} scored"
}

private data class ConsoleSnapshotState(
    val refreshing: Boolean = false,
    val store: MesSizeSnapshot? = null,
    val snapshotAgeSeconds: Long = Long.MAX_VALUE,
    val gates: ConsoleGateState = ConsoleGateState(),
    val logs: List<ConsoleLogLine> = emptyList(),
    val logsUnavailableMessage: String? = null,
)

@HiltViewModel
class ConsoleViewModel @Inject constructor(
    private val memoryEventStore: MemoryEventStore,
    private val relayPreferencesStore: RelayPreferencesStore,
    private val relayPool: RelayPool,
    private val keyManager: KeyManager,
    private val snapshotScheduler: SnapshotScheduler,
    private val logcatReader: LogcatReader,
) : ViewModel() {
    private val snapshotState = MutableStateFlow(ConsoleSnapshotState())
    private val selectedFilter = MutableStateFlow(ConsoleLogFilter.ALL)

    val uiState: StateFlow<ConsoleUiState> =
        combine(
            relayPool.connectionStates,
            relayPool.directoryFlow,
            snapshotState,
            selectedFilter,
        ) { connectionStates, directory, snapshot, filter ->
            val relayRows = buildConsoleRelayRows(
                connectionStates = connectionStates,
                directory = directory,
                debug = relayPool.connectionDebugSnapshot(),
            )
            val filteredLogs = filterLogLines(snapshot.logs, filter)
            ConsoleUiState(
                refreshing = snapshot.refreshing,
                relays = relayRows,
                relaySummary = formatConsoleRelaySummary(relayRows),
                relayCatalogSummary = "${directory.size} catalogued for discovery",
                store = snapshot.store,
                snapshotAgeSeconds = snapshot.snapshotAgeSeconds,
                gates = snapshot.gates,
                selectedFilter = filter,
                logs = snapshot.logs,
                filteredLogs = filteredLogs,
                logsUnavailableMessage = snapshot.logsUnavailableMessage,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConsoleUiState())

    init {
        refresh()
    }

    fun setFilter(filter: ConsoleLogFilter) {
        selectedFilter.value = filter
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            snapshotState.update { it.copy(refreshing = true) }
            try {
                val now = System.currentTimeMillis()
                val own = keyManager.getPublicKeyHex()
                val follows = own?.let { memoryEventStore.getFollows(it) } ?: emptySet()
                val coverage = computeWotCoverage(follows, memoryEventStore.getWotAssertions())
                val logsResult = logcatReader.readOwnProcessTail()
                snapshotState.value = ConsoleSnapshotState(
                    refreshing = false,
                    store = memoryEventStore.snapshotSize(),
                    snapshotAgeSeconds = snapshotScheduler.getSnapshotAgeSeconds(),
                    gates = ConsoleGateState(
                        lastTrustFetchAt = relayPreferencesStore.lastTrustFetchAt(),
                        lastMonitorFetchAt = relayPreferencesStore.lastMonitorFetchAt(),
                        lastWotFetchAt = relayPreferencesStore.lastWotFetchAt(),
                        wotTargetsHash = relayPreferencesStore.lastWotTargetsHash(),
                        coverage = coverage,
                        nowMs = now,
                    ),
                    logs = logsResult.getOrDefault(emptyList()),
                    logsUnavailableMessage = logsResult.exceptionOrNull()?.message,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                snapshotState.update {
                    it.copy(
                        refreshing = false,
                        logsUnavailableMessage = t.message ?: "logs unavailable on this device",
                    )
                }
            }
        }
    }

    fun diagnosticsText(state: ConsoleUiState): String =
        formatDiagnosticsBundle(
            ConsoleDiagnosticsBundle(
                relaySummary = "${state.relaySummary} · ${state.relayCatalogSummary}",
                storeSummary = "${state.storeSummary} · snapshot ${formatSnapshotAgeSeconds(state.snapshotAgeSeconds)}",
                gatesSummary = state.gatesSummary,
                logs = state.filteredLogs,
            ),
        )

}

/**
 * Build rows only for relays participating in the live connection system.
 *
 * [directory] is discovery metadata, not connection state. It enriches a live row but
 * never creates one; otherwise hundreds of catalog-only relays appear as fake "idle"
 * connections and obscure the sockets the Console is meant to diagnose.
 */
internal fun buildConsoleRelayRows(
    connectionStates: Map<String, RelayState>,
    directory: Map<String, RelayDirectoryEntry>,
    debug: Map<String, RelayConnectionDebugSnapshot>,
): List<ConsoleRelayRow> {
    val urls = connectionStates.keys + debug.keys
    return urls.map { url ->
        val d = debug[url]
        ConsoleRelayRow(
            url = url,
            state = connectionStates[url],
            purposes = d?.purposes.orEmpty(),
            oneShotCount = d?.oneShotCount ?: 0,
            queuedReqCount = d?.queuedReqCount ?: 0,
            hasActiveSubscription = d?.hasActiveSubscription == true,
            directory = directory[url],
        )
    }.sortedWith(
        compareBy<ConsoleRelayRow> { consoleRelayStateRank(it.state) }
            .thenBy { it.url },
    )
}

internal fun formatConsoleRelaySummary(rows: List<ConsoleRelayRow>): String {
    val connected = rows.count { it.state == RelayState.CONNECTED }
    val connecting = rows.count { it.state == RelayState.CONNECTING }
    val inactive = rows.size - connected - connecting
    return "$connected connected · $connecting connecting · $inactive inactive"
}

private fun consoleRelayStateRank(state: RelayState?): Int = when (state) {
    RelayState.CONNECTED -> 0
    RelayState.CONNECTING -> 1
    RelayState.FAILED -> 2
    RelayState.DISCONNECTED -> 3
    null -> 4
}
