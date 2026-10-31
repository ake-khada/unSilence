package com.unsilence.app.ui.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unsilence.app.data.memory.FeedRow
import kotlinx.coroutines.flow.StateFlow

/** Seed the first layout from retained rows, before lifecycle collection resumes.
 * An empty initial value would let the three profile headers clamp the saved scroll position.
 */
@Composable
internal fun StateFlow<List<FeedRow>>.collectProfilePostsAsState(): State<List<FeedRow>> =
    collectAsStateWithLifecycle()
