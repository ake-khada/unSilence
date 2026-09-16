package com.unsilence.app.ui.shared

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.unsilence.app.ui.theme.Spacing
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

class SensitiveContentSizingTest {
    @Test
    fun `portrait minimum is one third after system and stable app bars`() {
        assertEquals(200.dp, sensitiveContentMinimumHeight(780.dp, 24.dp, 36.dp, compact = false))
    }

    @Test
    fun `large window scales the minimum with usable height`() {
        assertEquals(340.dp, sensitiveContentMinimumHeight(1200.dp, 24.dp, 36.dp, compact = false))
    }

    @Test
    fun `landscape retains a readable touch target`() {
        assertEquals(96.dp, sensitiveContentMinimumHeight(400.dp, 24.dp, 24.dp, compact = false))
    }

    @Test
    fun `unavailable window geometry does not produce a negative height`() {
        assertEquals(96.dp, sensitiveContentMinimumHeight(0.dp, 24.dp, 36.dp, compact = false))
    }

    @Test
    fun `embedded minimum is only ten percent shorter in phone and tablet windows`() {
        assertEquals(180.dp, sensitiveContentMinimumHeight(780.dp, 24.dp, 36.dp, compact = true))
        assertEquals(306.dp, sensitiveContentMinimumHeight(1200.dp, 24.dp, 36.dp, compact = true))
    }

    @Test
    fun `embedded minimum retains the readable floor in small or unavailable windows`() {
        assertEquals(96.dp, sensitiveContentMinimumHeight(400.dp, 24.dp, 24.dp, compact = true))
        assertEquals(96.dp, sensitiveContentMinimumHeight(0.dp, 24.dp, 36.dp, compact = true))
    }

    @Test
    fun `embedded and ordinary cards meet at the readable floor`() {
        assertEquals(96.dp, sensitiveContentMinimumHeight(450.dp, 18.dp, 24.dp, compact = false))
        assertEquals(96.dp, sensitiveContentMinimumHeight(450.dp, 18.dp, 24.dp, compact = true))
    }

    @Test
    fun `quote reduction uses usable height after system insets`() {
        assertEquals(162.dp, sensitiveContentMinimumHeight(780.dp, 48.dp, 72.dp, compact = true))
    }

    @Test
    fun `larger stable system insets reduce the usable height`() {
        assertEquals(180.dp, sensitiveContentMinimumHeight(780.dp, 48.dp, 72.dp, compact = false))
    }
}

@RunWith(Parameterized::class)
class SensitiveContentRoleSizingTest(
    private val role: CardRole,
    private val expectedCompact: Boolean,
) {
    @Test
    fun `only embedded roles use compact placeholders`() {
        assertEquals(expectedCompact, role.usesCompactSensitivePlaceholder)
    }

    @Test
    fun `ordinary and repost placeholders share feed gutters without doubling embedded insets`() {
        val expected = if (expectedCompact) Modifier else Modifier.padding(
            horizontal = Spacing.medium,
            vertical = Spacing.small,
        )
        assertEquals(expected, Modifier.sensitiveContentPlaceholderPadding(role))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}, compact={1}")
        fun cases(): List<Array<Any>> = listOf(
            arrayOf(CardRole.Feed, false),
            arrayOf(CardRole.Thread, false),
            arrayOf(CardRole.Reply, false),
            arrayOf(CardRole.Profile, false),
            arrayOf(CardRole.Article, false),
            arrayOf(CardRole.EmbeddedArticle, true),
            arrayOf(CardRole.Search, false),
            arrayOf(CardRole.Embedded, true),
        )
    }
}
