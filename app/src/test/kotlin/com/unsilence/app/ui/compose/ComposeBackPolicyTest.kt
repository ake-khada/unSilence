package com.unsilence.app.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Test

class ComposeBackPolicyTest {
    @Test
    fun `empty composer dismisses on back`() {
        assertEquals(ComposerBackAction.DISMISS, composerBackAction(hasUnsavedDraftChanges = false))
    }

    @Test
    fun `dirty composer confirms before discard`() {
        assertEquals(ComposerBackAction.CONFIRM_DISCARD, composerBackAction(hasUnsavedDraftChanges = true))
    }
}
