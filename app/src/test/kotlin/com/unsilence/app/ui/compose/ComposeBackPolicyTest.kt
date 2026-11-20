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

    @Test
    fun `clearing a saved draft offers deletion even when the editor has no draftable content`() {
        assertEquals(ComposerBackAction.CONFIRM_DELETE, composerBackAction(false, clearedSavedDraft = true))
        assertEquals(ComposerBackAction.CONFIRM_DELETE, composerBackAction(true, clearedSavedDraft = true))
    }
}
