package com.unsilence.app.data.repository

import android.content.ContentResolver
import android.content.ContextWrapper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.auth.SigningManager
import com.unsilence.app.data.blossom.BlossomServersStore
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.model.ReportType
import com.unsilence.app.data.relay.EventDto
import com.unsilence.app.data.relay.NostrJson
import com.unsilence.app.data.relay.hasValidCanonicalSignature
import com.unsilence.app.data.relay.stubTimelineServiceProvider
import com.unsilence.app.data.relay.toNostrEvent
import com.unsilence.app.data.wallet.FakeSharedPreferences
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip57Zaps.LnZapRequestEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundSigningOwnerTest {
    private val context = object : ContextWrapper(null) {}
    private val keys = KeyManager(context).also {
        KeyManager::class.java.getDeclaredField("prefs\$delegate").apply { isAccessible = true }
            .set(it, lazyOf(FakeSharedPreferences()))
    }
    private val signing = SigningManager(keys, object : ContentResolver(context) {})
    private val memory = MemoryEventStore(keys, stubTimelineServiceProvider())
    private val sent = mutableListOf<Pair<EventDto, List<String>>>()
    private val owner = useKey("11")

    private fun useKey(byte: String): String {
        check(keys.savePrivateKey(byte.repeat(32)))
        return requireNotNull(keys.getPublicKeyHex())
    }

    private fun record(json: String, targets: List<String>) {
        sent.add(NostrJson.decodeFromString<EventDto>(json) to targets)
    }

    private fun setWriteRelay(pubkey: String, url: String) {
        memory.insert(EventDto(pubkey, pubkey, 10002, "", 1, listOf(listOf("r", url)), "0".repeat(128)).toNostrEvent(""))
        check(memory.writeRelaysFor(pubkey) == listOf(url))
    }

    private val target = "cc".repeat(32)
    private val note = "dd".repeat(32)
    private fun report(scope: CoroutineScope, profile: Boolean): Job {
        val reports = ReportRepository(signing, memory, keys, scope, ::record)
        if (profile) reports.reportProfile(target, ReportType.SPAM, "test")
        else reports.reportEvent(note, target, ReportType.SPAM, "test")
        return scope.coroutineContext.job.children.single()
    }

    @Test fun queuedEventReportIsDroppedAfterSwitch() = runTest {
        val pending = report(backgroundScope, false)
        useKey("22")
        pending.join()
        assertTrue(sent.isEmpty())
    }

    @Test fun queuedProfileReportIsDroppedAfterSwitch() = runTest {
        val pending = report(backgroundScope, true)
        useKey("22")
        pending.join()
        assertTrue(sent.isEmpty())
    }

    @Test fun eventReportIsDroppedWhenSignerReturnsAfterSwitch() = runTest {
        afterSigning { useKey("22") }
        report(backgroundScope, false).join()
        assertTrue(sent.isEmpty())
    }

    @Test fun profileReportIsDroppedWhenSignerReturnsAfterSwitch() = runTest {
        afterSigning { useKey("22") }
        report(backgroundScope, true).join()
        assertTrue(sent.isEmpty())
    }

    @Test fun sameAccountReportsKeepTheirTagsAndWriteRelay() = runTest {
        val url = "wss://owner.invalid"
        setWriteRelay(owner, url)
        for (profile in listOf(false, true)) {
            report(backgroundScope, profile).join()
            val (event, targets) = sent.removeAt(0)
            assertTrue(event.hasValidCanonicalSignature())
            assertEquals(owner, event.pubkey)
            assertEquals(1984, event.kind)
            assertEquals("test", event.content)
            val expected = buildList {
                if (!profile) add(listOf("e", note, "spam"))
                add(listOf("p", target, "spam"))
            }
            assertEquals(expected, event.tags)
            assertEquals(listOf(url), targets)
        }
        assertTrue(sent.isEmpty())
    }

    private class Prefs : DataStore<Preferences> {
        var value = emptyPreferences()
        var onRead: suspend () -> Unit = {}
        var onWrite: suspend () -> Unit = {}
        override val data = flow {
            val snapshot = value
            onRead()
            emit(snapshot)
        }
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val updated = transform(value)
            value = updated
            onWrite()
            return updated
        }
    }

    private fun servers(scope: CoroutineScope, prefs: Prefs = Prefs()) =
        BlossomServersStore(lazyOf(prefs), signing, memory, scope, ::record).apply { selectOwner(owner) }

    @Test fun serverListOwnedByAnotherAccountIsNeitherStoredNorDispatched() = runTest {
        val store = servers(this)
        store.addServer("https://a.invalid")
        val other = useKey("22")
        setWriteRelay(other, "wss://other.invalid")
        var signed = false
        afterSigning { signed = true }
        coroutineContext.job.children.single().join()
        assertFalse("The new owner's signer received the previous owner's list", signed)
        assertEquals(0, memory.snapshotSize().eventsByKind[10063] ?: 0)
        assertTrue(sent.isEmpty())
    }

    @Test fun clearingTheStoreWhileSigningDiscardsTheSignedServerList() = runTest {
        val store = servers(this)
        setWriteRelay(owner, "wss://owner.invalid")
        var signed = false
        afterSigning {
            signed = true
            store.clearActiveOwner()
        }
        store.addServer("https://a.invalid")
        coroutineContext.job.children.single().join()
        assertTrue(signed)
        assertEquals(0, memory.snapshotSize().eventsByKind[10063] ?: 0)
        assertTrue(sent.isEmpty())
    }

    @Test fun sameOwnerEditsPublishOneSignedListWithSelectedServerFirst() = runTest {
        val store = servers(this)
        setWriteRelay(owner, "wss://owner.invalid")
        store.addServer("https://first.invalid/")
        store.addServer("https://second.invalid")
        store.addServer("https://removed.invalid")
        store.setSelected("https://second.invalid")
        store.removeServer("https://removed.invalid")
        coroutineContext.job.children.toList().forEach { it.join() }
        val (event, targets) = sent.single()
        assertTrue(event.hasValidCanonicalSignature())
        assertEquals(owner, event.pubkey)
        assertEquals(10063, event.kind)
        assertEquals(listOf(listOf("server", "https://second.invalid"), listOf("server", "https://first.invalid")), event.tags)
        assertEquals(listOf("wss://owner.invalid"), targets)
        assertEquals(1, memory.snapshotSize().eventsByKind[10063])
    }

    @Test fun hydrationFromPreviousOwnerCannotReplaceCurrentServers() = runTest {
        val prefs = Prefs()
        val releaseRead = CompletableDeferred<Unit>()
        prefs.onRead = { releaseRead.await() }
        prefs.value = preferencesOf(stringSetPreferencesKey("${owner}_servers") to setOf("https://a.invalid"))
        val store = servers(this, prefs)
        val reading = launch(start = CoroutineStart.UNDISPATCHED) { store.initialize() }
        store.selectOwner(useKey("22"))
        releaseRead.complete(Unit)
        reading.join()
        assertTrue(store.configuredServers.value.isEmpty())
        assertEquals("", store.selectedServer.value)
        store.initialize()
        assertTrue(store.configuredServers.value.isNotEmpty())
        assertFalse("https://a.invalid" in store.configuredServers.value)
    }

    @Test fun staleEditFinishingAfterNewOwnersEditKeepsTheNewOwnersDebounce() = runTest {
        val prefs = Prefs()
        val releaseWrite = CompletableDeferred<Unit>()
        prefs.onWrite = { releaseWrite.await() }
        val publisher = CoroutineScope(coroutineContext + Job())
        val store = servers(publisher, prefs)
        val oldEdit = launch(start = CoroutineStart.UNDISPATCHED) { store.addServer("https://a.invalid") }
        val other = useKey("22")
        store.selectOwner(other)
        setWriteRelay(other, "wss://other.invalid")
        lateinit var queuedByNewOwner: Job
        // Resume the new owner's waiting edit inline as the old edit releases its write mutex.
        val newEdit = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            store.addServer("https://b.invalid")
            queuedByNewOwner = publisher.coroutineContext.job.children.single()
        }
        try {
            releaseWrite.complete(Unit)
            oldEdit.join()
            newEdit.join()
            assertFalse("An old edit cancelled the new owner's publication", queuedByNewOwner.isCancelled)
            queuedByNewOwner.join()
            val (event, targets) = sent.single()
            assertEquals(other, event.pubkey)
            assertEquals(listOf(listOf("server", "https://b.invalid")), event.tags)
            assertEquals(listOf("wss://other.invalid"), targets)
        } finally {
            publisher.coroutineContext.job.cancel()
        }
    }

    // The real internal signer finishes first. Change session before SigningManager receives its result.
    private fun afterSigning(onSigned: () -> Unit) {
        val delegate = NostrSignerInternal(KeyPair(requireNotNull(keys.getPrivateKeyHex()).hexToByteArray()))
        val signer = object : NostrSigner(delegate.pubKey) {
            override suspend fun <T : Event> sign(createdAt: Long, kind: Int, tags: Array<Array<String>>, content: String): T =
                delegate.sign<T>(createdAt, kind, tags, content).also { onSigned() }

            override fun isWriteable() = true
            override fun hasForegroundSupport() = false
            override suspend fun nip04Encrypt(plaintext: String, toPublicKey: String) = error("unused")
            override suspend fun nip04Decrypt(ciphertext: String, fromPublicKey: String) = error("unused")
            override suspend fun nip44Encrypt(plaintext: String, toPublicKey: String) = error("unused")
            override suspend fun nip44Decrypt(ciphertext: String, fromPublicKey: String) = error("unused")
            override suspend fun decryptZapEvent(event: LnZapRequestEvent) = error("unused")
            override suspend fun deriveKey(nonce: String) = error("unused")
        }
        SigningManager::class.java.getDeclaredField("signer").apply { isAccessible = true }.set(signing, signer)
    }
}
