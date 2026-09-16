package com.unsilence.app.ui.shared

import com.unsilence.app.data.memory.SensitiveContentMode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
internal class SensitiveContentPolicyTest(
    private val mode: SensitiveContentMode,
    private val sensitive: Boolean,
    private val revealed: Boolean,
    private val expected: SensitiveContentVisibility,
) {
    @Test
    fun `only allowed content is visible on every Android version`() {
        assertEquals(expected, sensitiveContentVisibility(mode, sensitive, revealed))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}, sensitive={1}, revealed={2} -> {3}")
        fun cases(): List<Array<Any>> = listOf(
            arrayOf(SensitiveContentMode.HIDE, false, false, SensitiveContentVisibility.VISIBLE),
            arrayOf(SensitiveContentMode.HIDE, false, true, SensitiveContentVisibility.VISIBLE),
            arrayOf(SensitiveContentMode.BLUR, false, false, SensitiveContentVisibility.VISIBLE),
            arrayOf(SensitiveContentMode.BLUR, false, true, SensitiveContentVisibility.VISIBLE),
            arrayOf(SensitiveContentMode.SHOW, false, false, SensitiveContentVisibility.VISIBLE),
            arrayOf(SensitiveContentMode.SHOW, false, true, SensitiveContentVisibility.VISIBLE),
            // HIDE must win over previously granted reveal consent.
            arrayOf(SensitiveContentMode.HIDE, true, false, SensitiveContentVisibility.HIDDEN),
            arrayOf(SensitiveContentMode.HIDE, true, true, SensitiveContentVisibility.HIDDEN),
            // BLUR is the stored preference name; it no longer renders a preview.
            arrayOf(SensitiveContentMode.BLUR, true, false, SensitiveContentVisibility.REVEALABLE),
            arrayOf(SensitiveContentMode.BLUR, true, true, SensitiveContentVisibility.VISIBLE),
            arrayOf(SensitiveContentMode.SHOW, true, false, SensitiveContentVisibility.VISIBLE),
            arrayOf(SensitiveContentMode.SHOW, true, true, SensitiveContentVisibility.VISIBLE),
        )
    }
}
