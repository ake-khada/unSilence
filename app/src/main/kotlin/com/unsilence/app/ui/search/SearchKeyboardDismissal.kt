package com.unsilence.app.ui.search

/** One dismissal per visible keyboard, including its hide animation. Main-thread owned. */
internal class SearchKeyboardDismissal {
    private var visible = false
    private var requested = false

    fun updateVisibility(isVisible: Boolean) {
        visible = isVisible
        if (!isVisible) requested = false
    }

    fun onScroll(userInput: Boolean, verticalMovement: Boolean): Boolean {
        if (!visible || requested || !userInput || !verticalMovement) return false
        requested = true
        return true
    }
}
