package com.unsilence.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vitorpamplona.quartz.nip55AndroidSigner.JsonMapperNip55
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Offline fixed test keys only. No app account, signer IPC, wallet or relay writes. */
@RunWith(AndroidJUnit4::class)
class ReleaseCryptoTest {
    @Test fun jacksonEmptyCollectionsAndProtocolRoundTrip() = runBlocking {
        // Exercise the actual Amber serialization path, not a test-only mapper.
        assertEquals("[]", JsonMapperNip55.toJson(emptyList<String>()))
        assertEquals("{}", JsonMapperNip55.toJson(emptyMap<String, String>()))
        val signer = NostrSignerInternal(KeyPair(privKey = "11".repeat(32).hexToByteArray()))
        val signed = signer.sign(EventTemplate<Event>(
            createdAt = 1_675_000_000L, kind = 1, tags = emptyArray(), content = "offline validation",
        ))
        val parsed = Event.fromJson(signed.toJson())
        assertEquals(signed.id, parsed.id)
        assertEquals(signed.pubKey, parsed.pubKey)
        assertEquals(signed.sig, parsed.sig)
        assertEquals("offline validation", parsed.content)
        assertTrue(parsed.tags.isEmpty())
    }

    @Test fun nip04AndNip44RoundTrips() = runBlocking {
        val aKeys = KeyPair(privKey = "11".repeat(32).hexToByteArray())
        val bKeys = KeyPair(privKey = "22".repeat(32).hexToByteArray())
        val a = NostrSignerInternal(aKeys)
        val b = NostrSignerInternal(bKeys)
        val message = "offline crypto validation"
        val nip04 = a.nip04Encrypt(message, bKeys.pubKey.toHexKey())
        assertEquals(message, b.nip04Decrypt(nip04, aKeys.pubKey.toHexKey()))
        val nip44 = a.nip44Encrypt(message, bKeys.pubKey.toHexKey())
        assertEquals(message, b.nip44Decrypt(nip44, aKeys.pubKey.toHexKey()))
    }
}
