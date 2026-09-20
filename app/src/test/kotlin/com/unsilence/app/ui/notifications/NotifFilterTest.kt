package com.unsilence.app.ui.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

class NotifFilterTest {
    @Test fun `each activation selects the other filter and two activations return to the original`() {
        assertEquals(NotifFilter.Following, NotifFilter.Global.next())
        assertEquals(NotifFilter.Global, NotifFilter.Following.next())
        NotifFilter.entries.forEach { assertEquals(it, it.next().next()) }
    }
}
