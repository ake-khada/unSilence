package com.unsilence.app.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AppNavigatorTest {
    private fun navigator(): AppNavigator {
        var next = 0
        return AppNavigator(mutableListOf<NavKey>(AppEntry("tabs", AppDestination.Tabs))) { "visit-${++next}" }
    }

    @Test fun `profile back returns to the exact thread visit then tabs`() {
        val nav = navigator()
        nav.push(AppDestination.Thread("note", listOf("wss://relay.example")))
        val thread = nav.backStack.last()
        nav.push(AppDestination.Profile("author"))
        nav.pop()
        assertSame(thread, nav.backStack.last())
        nav.pop()
        assertEquals(AppDestination.Tabs, (nav.backStack.single() as AppEntry).destination)
    }

    @Test fun `nested profile thread profile unwinds in actual visit order`() {
        val nav = navigator()
        val path = listOf(AppDestination.Profile("one"), AppDestination.Thread("note"), AppDestination.Profile("two"))
        path.forEach(nav::push)
        for (destination in path.reversed()) {
            assertEquals(destination, (nav.backStack.last() as AppEntry).destination)
            nav.pop()
        }
        assertEquals(1, nav.backStack.size)
    }

    @Test fun `double taps do not create duplicate active entries`() {
        val nav = navigator()
        repeat(3) { nav.push(AppDestination.Thread("note")) }
        assertEquals(2, nav.backStack.size)
    }

    @Test fun `revisiting the same event has a distinct store identity`() {
        val nav = navigator()
        nav.push(AppDestination.Thread("note"))
        val first = nav.backStack.last() as AppEntry
        nav.pop()
        nav.push(AppDestination.Thread("note"))
        val second = nav.backStack.last() as AppEntry
        assertEquals(first.destination, second.destination)
        assertNotEquals(first.id, second.id)
    }

    @Test fun `same author at different depths keeps independent visits`() {
        val nav = navigator()
        nav.push(AppDestination.Profile("author"))
        val first = nav.backStack.last()
        nav.push(AppDestination.Thread("note"))
        nav.push(AppDestination.Profile("author"))
        assertNotEquals(first, nav.backStack.last())
        nav.pop()
        nav.pop()
        assertSame(first, nav.backStack.last())
    }

    @Test fun `root survives repeated back and clearing`() {
        val nav = navigator()
        repeat(5) { nav.pop() }
        nav.popToTabs()
        assertEquals(listOf(AppEntry("tabs", AppDestination.Tabs)), nav.backStack)
    }

    @Test fun `external deep link replaces content history but retains tabs`() {
        val nav = navigator()
        val root = nav.backStack.first()
        nav.push(AppDestination.Thread("old"))
        nav.push(AppDestination.Profile("author"))
        val target = AppDestination.Thread("new", listOf("wss://hint.example"), true)
        nav.replaceAboveTabs(target)
        assertEquals(2, nav.backStack.size)
        assertSame(root, nav.backStack.first())
        assertEquals(target, (nav.backStack.last() as AppEntry).destination)
    }

    @Test fun `serialized stack preserves visit identities hints and article intent`() {
        val nav = navigator()
        nav.push(AppDestination.Thread("note", listOf("wss://relay.example"), true))
        nav.push(AppDestination.Profile("author"))
        val entries = nav.backStack.map { it as AppEntry }
        val restored = Json.decodeFromString<List<AppEntry>>(Json.encodeToString(entries))
        assertEquals(entries, restored)
        val resumed = AppNavigator(restored.toMutableList<NavKey>())
        resumed.pop()
        assertEquals(entries[1], resumed.backStack.last())
    }

    @Test fun `many open pop cycles never accumulate entries`() {
        val nav = navigator()
        repeat(100) {
            nav.push(AppDestination.Thread("note-$it"))
            nav.push(AppDestination.Profile("author-$it"))
            nav.pop()
            nav.pop()
            assertEquals(1, nav.backStack.size)
        }
    }
}
