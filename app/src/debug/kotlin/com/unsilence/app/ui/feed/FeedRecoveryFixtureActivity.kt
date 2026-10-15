package com.unsilence.app.ui.feed

import android.os.Bundle
import android.os.Process
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.ui.shared.rememberFeedScrollState
import com.unsilence.app.ui.theme.UnsilenceTheme
import kotlinx.coroutines.delay

/** Offline process-death fixture; never opens the real feed or accesses an account. */
class FeedRecoveryFixtureActivity : ComponentActivity() {
    private var scenario = "exact"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scenario = savedInstanceState?.getString("scenario")
            ?: intent.getStringExtra("scenario")?.takeIf { it == "missing" } ?: "exact"
        val restored = savedInstanceState != null
        Log.i(TAG, "created pid=${Process.myPid()} restored=$restored scenario=$scenario")
        setContent {
            UnsilenceTheme {
                val original = remember { (0 until 60).map { row("note-$it") } }
                var rows by remember { mutableStateOf(if (restored) emptyList() else original) }
                var ready by remember { mutableStateOf(!restored) }
                val list = rememberFeedScrollState(rows, ready, "fixture-source")
                LaunchedEffect(Unit) {
                    if (!restored) {
                        list.scrollToItem(20, 23)
                    } else {
                        // Deliberately exercise an empty first projection after process death.
                        delay(250)
                        rows = listOf(row("new-1"), row("new-2")) + original.filter {
                            scenario != "missing" || it.id != "note-20"
                        }
                        ready = true
                    }
                }
                LaunchedEffect(list) {
                    snapshotFlow {
                        Triple(rows.getOrNull(list.firstVisibleItemIndex)?.id, list.firstVisibleItemScrollOffset, ready)
                    }.collect { (id, offset, settled) ->
                        if (settled && id != null) {
                            Log.i(TAG, "anchor pid=${Process.myPid()} restored=$restored scenario=$scenario id=$id offset=$offset")
                        }
                    }
                }
                LazyColumn(Modifier.fillMaxSize(), state = list) {
                    items(rows, key = { it.id }) { Spacer(Modifier.height(180.dp)) }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("scenario", scenario)
        super.onSaveInstanceState(outState)
        Log.i(TAG, "saved pid=${Process.myPid()} scenario=$scenario")
    }

    private fun row(id: String) = FeedRow(
        id, "fixture-author", 1, "", 0L, "[]", "", null, null, false, null,
        0L, null, null, null, null, 0, 0, 0, 0,
    )

    private companion object { const val TAG = "FeedRecoveryFixture" }
}
