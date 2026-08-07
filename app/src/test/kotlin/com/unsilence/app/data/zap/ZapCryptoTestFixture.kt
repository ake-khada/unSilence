package com.unsilence.app.data.zap

import com.unsilence.app.data.memory.PendingPrivateZapDecrypt
import com.unsilence.app.data.relay.EventDto
import com.unsilence.app.data.relay.NostrJson
import com.unsilence.app.data.relay.toEventJson
import com.unsilence.app.data.relay.toNostrEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** Real-key NIP-57 fixtures shared by auth and MES integration tests. */
internal class ZapCryptoTestFixture {
    data class Identity(
        val signer: NostrSignerInternal,
        val pubkey: String,
    )

    val sender = identity("11")
    val recipient = identity("22")
    val zapper = identity("33")
    val attacker = identity("44")
    val defaultTarget = "ab".repeat(32)

    fun identity(byte: String): Identity {
        val keys = KeyPair(privKey = byte.repeat(32).hexToByteArray())
        return Identity(NostrSignerInternal(keys), keys.pubKey.toHexKey())
    }

    suspend fun request(
        signer: Identity = sender,
        recipientPubkey: String = recipient.pubkey,
        targetTagName: String = "e",
        targetId: String = defaultTarget,
        amountMsats: Long = 1_000_000L,
        anonCiphertext: String? = null,
        content: String = "great post",
        createdAt: Long = 1_675_000_000L,
    ): EventDto {
        val tags = mutableListOf(
            listOf("amount", amountMsats.toString()),
            listOf("p", recipientPubkey),
            listOf(targetTagName, targetId),
            listOf("client", "test-client"),
        )
        if (anonCiphertext != null) tags += listOf("anon", anonCiphertext)
        return signedEvent(
            signer = signer,
            kind = 9734,
            tags = tags,
            content = if (anonCiphertext == null) content else "",
            createdAt = createdAt,
        )
    }

    suspend fun receipt(
        signer: Identity = zapper,
        request: EventDto,
        recipientPubkey: String = recipient.pubkey,
        targetTagName: String = "e",
        targetId: String = defaultTarget,
        invoice: String = NIP57_1K_SAT_INVOICE,
        createdAt: Long = 1_675_000_001L,
    ) = signedEvent(
        signer = signer,
        kind = 9735,
        tags = listOf(
            listOf("p", recipientPubkey),
            listOf(targetTagName, targetId),
            listOf("bolt11", invoice),
            listOf("description", NostrJson.encodeToString(request)),
        ),
        content = "",
        createdAt = createdAt,
    ).toNostrEvent("wss://test.invalid")

    suspend fun signedEvent(
        signer: Identity,
        kind: Int,
        tags: List<List<String>>,
        content: String,
        createdAt: Long = 1_675_000_000L,
    ): EventDto {
        val signed = signer.signer.sign(
            EventTemplate<Event>(
                createdAt = createdAt,
                kind = kind,
                tags = tags.map { it.toTypedArray() }.toTypedArray(),
                content = content,
            )
        )
        return NostrJson.decodeFromString(toEventJson(signed))
    }

    fun pendingPrivateZap(
        anonSignerPubkey: String = attacker.pubkey,
        recipientPubkey: String = recipient.pubkey,
        targetTagName: String = "e",
        targetId: String = defaultTarget,
    ) = PendingPrivateZapDecrypt(
        zapReceiptId = "ef".repeat(32),
        anonCiphertext = "ciphertext",
        anonSignerPubkey = anonSignerPubkey,
        recipientPubkey = recipientPubkey,
        targetTagName = targetTagName,
        targetId = targetId,
    )

    fun tamper(value: String): String {
        val replacement = if (value.last() == '0') '1' else '0'
        return value.dropLast(1) + replacement
    }

    companion object {
        // NIP-57 Appendix E fixture. Full Bech32 checksum is validated by Quartz.
        const val NIP57_1K_SAT_INVOICE =
            "lnbc10u1p3unwfusp5t9r3yymhpfqculx78u027lxspgxcr2n2987mx2j55nnfs95nxnzq" +
                "pp5jmrh92pfld78spqs78v9euf2385t83uvpwk9ldrlvf6ch7tpascqhp5zvkrmemgth3tuf" +
                "cvflmzjzfvjt023nazlhljz2n9hattj4f8jq8qxqyjw5qcqpjrzjqtc4fc44feggv7065f" +
                "qe5m4ytjarg3repr5j9el35xhmtfexc42yczarjuqqfzqqqqqqqqlgqqqqqqgq9q9qxpqys" +
                "gq079nkq507a5tw7xgttmj4u990j7wfggtrasah5gd4ywfr2pjcn29383tphp4t48gquelz9" +
                "z78p4cq7ml3nrrphw5w6eckhjwmhezhnqpy6gyf0"
    }
}
