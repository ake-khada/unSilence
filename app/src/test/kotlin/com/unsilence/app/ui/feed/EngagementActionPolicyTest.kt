package com.unsilence.app.ui.feed

import com.unsilence.app.data.memory.CustomEmoji
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class EngagementActionPolicyTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `pinned emoji flow resolves when emoji sets hydrate late`() = runTest {
        val pinned = MutableStateFlow(setOf("party"))
        val resolved = MutableStateFlow<List<CustomEmoji>>(emptyList())
        val observed = mutableListOf<List<CustomEmoji>>()
        val collection = launch {
            pinnedEmojisFlow(pinned, resolved).take(2).toList(observed)
        }

        runCurrent()
        resolved.value = listOf(CustomEmoji("party", "https://emoji.example/party.webp"))
        runCurrent()

        assertEquals(listOf(emptyList(), resolved.value), observed)
        collection.cancel()
    }

    @Test
    fun `repost strip dispatches boost and quote independently`() {
        var boosts = 0
        val quotes = mutableListOf<String>()
        val onBoost: () -> Unit = { boosts += 1 }
        val onQuote = { id: String -> quotes += id }

        dispatchRepostStripAction(RepostStripAction.BOOST, "note-1", onBoost, onQuote)
        dispatchRepostStripAction(RepostStripAction.QUOTE, "note-2", onBoost, onQuote)

        assertEquals(1, boosts)
        assertEquals(listOf("note-2"), quotes)
    }
}
