package com.unsilence.app.data

import com.unsilence.app.data.init.InitGate
import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.copySnapshotForTest
import com.unsilence.app.data.relay.EventDto
import com.unsilence.app.data.relay.stubTimelineServiceProvider
import com.unsilence.app.data.relay.toNostrEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*

@OptIn(ExperimentalCoroutinesApi::class)
class BootstrapEmojiReadinessTest {
    private val owner = "11".repeat(32)
    private val gate = InitGate()
    private val session = gate.beginSession(owner)
    private fun store() = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider()).apply { ownPubkey = owner }
    private fun list(author: String = owner) = EventDto("list-$author", author, 10030, "", 1L,
        listOf(listOf("a", "30030:${"22".repeat(32)}:fixture")), "sig").toNostrEvent("wss://fixture.example")

    @Test fun `already restored list is ready without waiting`() = runTest {
        val mes = store().apply { insert(list()) }
        assertEquals(1, awaitBootstrapEmojiList(mes, owner, gate, session)!!.setRefs.size)
        assertEquals(0L, currentTime)
    }

    @Test fun `new list releases readiness without a polling tick`() = runTest {
        val mes = store()
        launch { delay(37); mes.insertBatch(listOf(list())) }
        assertNotNull(awaitBootstrapEmojiList(mes, owner, gate, session))
        assertEquals("Signal readiness must not wait until the next 250ms poll", 37L, currentTime)
    }

    @Test fun `snapshot list restored after five seconds is still resolved`() = runTest {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { store().apply { insert(list()) }.copySnapshotForTest(it) }
        val mes = store()
        launch {
            delay(6_000)
            DataInputStream(ByteArrayInputStream(bytes.toByteArray())).use { mes.restoreSnapshotBinary(it) }
            gate.signalSnapshotReady(session)
        }
        val waiting = async { awaitBootstrapEmojiList(mes, owner, gate, session) }
        advanceUntilIdle()
        assertNotNull("A completed valid restore must not miss the startup emoji sets", waiting.await())
        assertEquals("fixture", waiting.await()!!.setRefs.single().setName)
    }

    @Test fun `missing or rejected snapshot retains the bounded network deadline`() = runTest {
        gate.signalSnapshotReady(session)
        assertNull(awaitBootstrapEmojiList(store(), owner, gate, session))
        assertEquals(5_000L, currentTime)
    }

    @Test fun `another account list cannot resolve this account`() = runTest {
        gate.signalSnapshotReady(session)
        val mes = store().apply { insert(list("33".repeat(32))) }
        assertNull(awaitBootstrapEmojiList(mes, owner, gate, session))
    }

    @Test fun `cancelled startup cannot fetch sets on late list arrival`() = runTest {
        val mes = store()
        var continued = false
        val job = launch { awaitBootstrapEmojiList(mes, owner, gate, session); continued = true }
        runCurrent()
        job.cancelAndJoin()
        mes.insert(list())
        advanceUntilIdle()
        assertFalse(continued)
    }
    @Test fun `stalled restore cannot create an indefinite startup collector`() = runTest {
        assertNull(awaitBootstrapEmojiList(store(), owner, gate, session))
        assertEquals(35_000L, currentTime)
    }

    @Test fun `changed account invalidates readiness after a late old list`() = runTest {
        val mes = store()
        val waiting = async { awaitBootstrapEmojiList(mes, owner, gate, session) }
        runCurrent()
        gate.invalidateSession()
        gate.beginSession("33".repeat(32))
        mes.insert(list())
        advanceUntilIdle()
        assertNull(waiting.await())
    }

    @Test fun `stale session does not start a wait even with cached data`() = runTest {
        val mes = store().apply { insert(list()) }
        gate.invalidateSession()
        assertNull(awaitBootstrapEmojiList(mes, owner, gate, session))
        assertEquals(0L, currentTime)
    }

}
