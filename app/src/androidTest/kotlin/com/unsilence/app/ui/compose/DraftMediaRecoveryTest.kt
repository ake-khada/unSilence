package com.unsilence.app.ui.compose

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.test.filters.SdkSuppress
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.unsilence.app.MainActivity
import com.unsilence.app.data.blossom.AttachmentQuality
import com.unsilence.app.data.blossom.BlossomBlob
import com.unsilence.app.data.drafts.DraftBlock
import com.unsilence.app.data.drafts.DraftContext
import com.unsilence.app.data.drafts.DraftStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class DraftMediaRecoveryTest {
    @Test fun interruptedFailedAndUploadedBlocksRestoreInOrderWithoutStartingUploads() {
        val uri = Uri.parse("content://picker/photo")
        val idle = AttachmentState.Idle(uri, "first", "photo", 32, AttachmentQuality.HIGH)
        val uploading = AttachmentState.Uploading(uri, "second", "clip", 64, AttachmentQuality.SMALL,
            AttachmentUploadPhase.Transcoding, 14)
        val failed = AttachmentState.Failed(uri, "third", "image", 80, AttachmentQuality.ORIGINAL, "Server refused")
        val blob = BlossomBlob("https://example.org/image", "hash", 80, "image/png")
        val completed = AttachmentState.Uploaded(uri, "fourth", "done", 80, AttachmentQuality.ORIGINAL, blob)
        val saved = listOf(idle, uploading, failed, completed).map { ComposeBlock.Attachment(it).toDraftBlock() }
        val local = saved.filterIsInstance<DraftBlock.LocalMedia>()
        val restored = local.map { it.restoreMedia(hasAccess = true) }
        assertEquals(listOf("first", "second", "third"), restored.map { it.id })
        assertEquals(idle, restored.first())
        assertEquals(AttachmentState.Idle(uri, "second", "clip", 64, AttachmentQuality.SMALL), restored[1])
        assertEquals(failed, restored[2])
        assertEquals("File unavailable. Remove it and choose it again.",
            (local.first().restoreMedia(hasAccess = false) as AttachmentState.Failed).message)
        assertEquals(blob.url, (saved.last() as DraftBlock.Attachment).url)
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test fun pickerGrantSurvivesReopeningMediaOnlyReplyAndEndsAtDeletion() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(Build.HARDWARE in listOf("ranchu", "goldfish") && context.packageName.endsWith(".dev"))
        val resolver = context.contentResolver
        val fixture = UUID.randomUUID().toString()
        val owner = UUID.randomUUID().toString().replace("-", "").repeat(2)
        val other = UUID.randomUUID().toString().replace("-", "").repeat(2)
        val parent = "4".repeat(64)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var vm: ComposeViewModel
        lateinit var store: DraftStore
        lateinit var keys: com.unsilence.app.data.auth.KeyManager
        try {
            scenario.onActivity { activity ->
                keys = activity.keyManager
                assertNull(keys.getPublicKeyHex())
                keys.saveAmberLogin(owner)
                vm = ViewModelProvider(activity)[ComposeViewModel::class.java]
                store = ComposeViewModel::class.java.getDeclaredField("draftStore").apply { isAccessible = true }
                    .get(vm) as DraftStore
                vm.beginComposeSession("draft-test")
                vm.loadReplyTo(parent)
            }
            suspend fun fixtureResult(remove: Boolean): Intent {
                val result = CompletableDeferred<Intent>()
                scenario.onActivity { activity ->
                    val intent = Intent().setClassName(instrumentation.context.packageName,
                        DraftGrantFixtureActivity::class.java.name)
                        .putExtra("fixture", fixture).putExtra("remove", remove)
                    activity.activityResultRegistry.register("fixture-$remove",
                        ActivityResultContracts.StartActivityForResult()) {
                        if (it.resultCode == android.app.Activity.RESULT_OK && it.data != null) {
                            result.complete(requireNotNull(it.data))
                        } else result.completeExceptionally(AssertionError("Fixture did not return a result"))
                    }.launch(intent)
                }
                return withTimeout(10_000) { result.await() }
            }
            val uri = requireNotNull(fixtureResult(false).data)
            assertFalse(resolver.persistedUriPermissions.any { it.uri == uri })
            withContext(Dispatchers.Main) {
                vm.addAttachments(listOf(uri))
                vm.updateAttachmentQuality(vm.blocks.value.first().id, AttachmentQuality.SMALL)
                vm.updateSelection(vm.blocks.value.last().id, TextRange.Zero)
                assertEquals(DraftSaveResult.SAVED, vm.saveCurrentDraft())
            }
            val draft = requireNotNull(store.draft(owner, "reply:$parent"))
            assertEquals(DraftContext.Reply(parent), draft.context)
            assertEquals(0, draft.cursor?.start)
            assertTrue(resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission })
            withContext(Dispatchers.Main) {
                vm.reset()
                vm.restoreDraft(draft)
                val state = (vm.blocks.value.first() as ComposeBlock.Attachment).state as AttachmentState.Idle
                assertEquals(uri, state.uri)
                assertEquals(AttachmentQuality.SMALL, state.quality)
                assertEquals(TextRange.Zero, vm.selectionFor(vm.blocks.value.last().id))
            }
            requireNotNull(resolver.openInputStream(uri)).use { assertTrue(it.read() >= 0) }
            keys.saveAmberLogin(other)
            withContext(Dispatchers.Main) {
                vm.startUpload(vm.blocks.value.first().id)
                assertTrue((vm.blocks.value.first() as ComposeBlock.Attachment).state is AttachmentState.Idle)
                assertEquals(DraftSaveResult.SESSION_CHANGED, vm.saveCurrentDraft())
            }
            assertTrue(store.draftsFor(other).isEmpty())
            keys.saveAmberLogin(owner)
            assertTrue(fixtureResult(true).getBooleanExtra("removed", false))
            withContext(Dispatchers.Main) { vm.startUpload(vm.blocks.value.first().id) }
            val unavailable = withTimeout(5_000) {
                vm.blocks.first { (it.first() as ComposeBlock.Attachment).state is AttachmentState.Failed }
            }
            assertEquals("File unavailable. Remove it and choose it again.",
                ((unavailable.first() as ComposeBlock.Attachment).state as AttachmentState.Failed).message)
            store.delete(owner, draft.key).await().getOrThrow()
            assertFalse(resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission })
        } finally {
            scenario.close()
            runCatching { store.delete(owner, "reply:$parent").await().getOrThrow() }
            runCatching { if (keys.getPublicKeyHex() in setOf(owner, other)) keys.clear() }
        }
    }
}
