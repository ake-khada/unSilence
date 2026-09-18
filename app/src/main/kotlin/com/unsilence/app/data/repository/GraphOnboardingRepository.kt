package com.unsilence.app.data.repository

import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.init.InitGate
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.relay.shouldAutoOpenStartGraph
import com.unsilence.app.data.relay.shouldMaterializeEmptyFollows
import com.unsilence.app.data.relay.shouldShowEmptyFollowingEntry
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

data class GraphOnboardingPrompt(val autoOpen: Boolean = false, val showEmptyFollowing: Boolean = false)

/** Startup needs only the account graph decision, not the people/pack loader ViewModel. */
@Singleton
class GraphOnboardingRepository @Inject constructor(
    private val keyManager: KeyManager,
    private val gate: InitGate,
    private val store: MemoryEventStore,
) {
    fun prompts(owner: String) = flow {
        gate.awaitFollows()
        if (keyManager.getPublicKeyHex() != owner) return@flow
        val follows = store.getFollows(owner)
        if (shouldMaterializeEmptyFollows(
                follows, keyManager.isGraphOnboardingPending(), keyManager.isGraphKnownEmpty(),
            )) {
            store.updateFollows(owner, emptySet(), System.currentTimeMillis() / 1000L)
        }
        emitAll(store.followsSignalFlow.map {
            if (keyManager.getPublicKeyHex() != owner) return@map GraphOnboardingPrompt()
            val current = store.getFollows(owner)
            if (current?.isNotEmpty() == true && !keyManager.isGraphOnboardingCompleted()) {
                keyManager.completeGraphOnboarding(hasFollows = true)
            }
            GraphOnboardingPrompt(
                autoOpen = shouldAutoOpenStartGraph(
                    keyManager.isGraphOnboardingPending(), keyManager.isGraphOnboardingCompleted(), current,
                ),
                showEmptyFollowing = shouldShowEmptyFollowingEntry(true, current),
            )
        }.distinctUntilChanged())
    }
}
