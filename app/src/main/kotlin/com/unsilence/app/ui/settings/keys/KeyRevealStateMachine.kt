package com.unsilence.app.ui.settings.keys

private const val DEFAULT_REVEAL_DURATION_MILLIS = 30_000L

sealed interface KeyRevealState {
    data object Masked : KeyRevealState
    data object Authenticating : KeyRevealState
    data class Revealed(val expiresAtMillis: Long) : KeyRevealState
}

class KeyRevealStateMachine(
    private val revealDurationMillis: Long = DEFAULT_REVEAL_DURATION_MILLIS,
) {
    var state: KeyRevealState = KeyRevealState.Masked
        private set

    fun startAuthentication(): KeyRevealState {
        state = KeyRevealState.Authenticating
        return state
    }

    fun reveal(nowMillis: Long): KeyRevealState {
        state = KeyRevealState.Revealed(nowMillis + revealDurationMillis)
        return state
    }

    fun cancel(): KeyRevealState = mask()

    fun pause(): KeyRevealState = mask()

    fun tick(nowMillis: Long): KeyRevealState {
        val current = state
        if (current is KeyRevealState.Revealed && nowMillis >= current.expiresAtMillis) {
            return mask()
        }
        return current
    }

    fun mask(): KeyRevealState {
        state = KeyRevealState.Masked
        return state
    }
}
