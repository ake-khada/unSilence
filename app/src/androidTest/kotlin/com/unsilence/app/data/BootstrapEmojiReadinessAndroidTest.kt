package com.unsilence.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.init.InitGate
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.relay.EventDto
import com.unsilence.app.data.relay.toNostrEvent
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Provider

/** Android runtime readiness checks with isolated MES and no account or network. */
@RunWith(AndroidJUnit4::class)
class BootstrapEmojiReadinessAndroidTest {
    private val owner = "11".repeat(32)
    private fun store() = MemoryEventStore(object : MuteKeyProvider {}, Provider { error("No Timeline access") })
    private fun list() = EventDto("emoji-fixture", owner, 10030, "", 1L,
        listOf(listOf("a", "30030:${"22".repeat(32)}:fixture")), "sig").toNostrEvent("wss://fixture.example")

    @Test fun lateLocalListReleasesTheBoundedStartupWait() = runBlocking {
        val mes = store()
        val gate = InitGate()
        val session = gate.beginSession(owner)
        val waiting = async(Dispatchers.Default) { awaitBootstrapEmojiList(mes, owner, gate, session) }
        delay(5_200) // Beyond the previous poll; no claim about phone loading time.
        assertFalse(waiting.isCompleted)
        mes.insertBatch(listOf(list()))
        gate.signalSnapshotReady(session)
        assertEquals("fixture", withTimeout(2_000) { waiting.await() }!!.setRefs.single().setName)
    }

    @Test fun immediateCachedListAndChangedAccountUseTheActualSessionGate() = runBlocking {
        val mes = store().apply { insert(list()) }
        val gate = InitGate()
        val session = gate.beginSession(owner)
        assertNotNull(withTimeout(2_000) { awaitBootstrapEmojiList(mes, owner, gate, session) })
        gate.invalidateSession()
        gate.beginSession("33".repeat(32))
        assertNull(awaitBootstrapEmojiList(mes, owner, gate, session))
    }
}
