package com.unsilence.app.ui.profile

import com.unsilence.app.data.repository.EditableProfileMetadata
import com.unsilence.app.data.repository.profileMetadataHasChanges
import com.unsilence.app.data.repository.ProfilePublishResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSaveStateTest {
    @Test
    fun `zero relay acceptance surfaces failure rather than dismissal state`() {
        val state = profileSaveStateFor(ProfilePublishResult.NoRelayAccepted)

        assertTrue(state is ProfileSaveState.Failed)
        assertTrue((state as ProfileSaveState.Failed).message.contains("No relay accepted"))
    }

    @Test
    fun `relay acceptance maps to the dismissal state`() {
        assertEquals(
            ProfileSaveState.Saved,
            profileSaveStateFor(ProfilePublishResult.Success),
        )
    }

    @Test
    fun `unresolved profile gives actionable failure`() {
        val state = profileSaveStateFor(ProfilePublishResult.ProfileUnavailable)

        assertTrue(state is ProfileSaveState.Failed)
        assertTrue((state as ProfileSaveState.Failed).message.contains("not loaded"))
    }

    @Test
    fun `unproven freshness gives a non-destructive retry message`() {
        val state = profileSaveStateFor(ProfilePublishResult.FreshnessUnavailable)

        assertTrue(state is ProfileSaveState.Failed)
        assertTrue((state as ProfileSaveState.Failed).message.contains("refresh"))
        assertTrue(state.message.contains("Nothing was changed"))
    }

    @Test
    fun `profile-less account starts from an editable blank baseline`() {
        val baseline = profileMetadataForEdit(null)
        val entered = baseline.copy(name = "First profile")

        assertEquals(
            EditableProfileMetadata("", "", "", "", "", "", "", ""),
            baseline,
        )
        assertTrue(profileMetadataHasChanges(baseline, entered))
    }
}
