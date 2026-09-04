package com.unsilence.app.data.relay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountMetadataFetchTest {

    @Test
    fun `one request carries profile contacts and relay-list filters`() {
        val frame = Json.parseToJsonElement(
            buildAccountMetadataReq("account", "owner"),
        ).jsonArray

        assertEquals("REQ", frame[0].jsonPrimitive.content)
        assertEquals("account", frame[1].jsonPrimitive.content)
        assertEquals(listOf(0, 3, 10002), frame.drop(2).map { filter ->
            filter.jsonObject.getValue("kinds").jsonArray.single().jsonPrimitive.content.toInt()
        })
        frame.drop(2).forEach { filter ->
            assertEquals(
                "owner",
                filter.jsonObject.getValue("authors").jsonArray.single().jsonPrimitive.content,
            )
            assertEquals(1, filter.jsonObject.getValue("limit").jsonPrimitive.content.toInt())
        }
    }

    @Test
    fun `kind-3 or corroborated real EOSE resolves graph discovery`() {
        assertFalse(AccountMetadataFetchResult(queriedRelays = setOf("wss://one")).hasGraphResponse)
        assertFalse(
            AccountMetadataFetchResult(
                queriedRelays = setOf("wss://one"),
                eoseRelays = setOf("wss://one"),
            ).hasGraphResponse,
        )
        assertTrue(
            AccountMetadataFetchResult(
                queriedRelays = setOf("wss://one", "wss://two"),
                eoseRelays = setOf("wss://one", "wss://two"),
            ).hasGraphResponse,
        )
        assertTrue(AccountMetadataFetchResult(receivedKinds = setOf(3)).hasGraphResponse)
        assertFalse(AccountMetadataFetchResult(receivedKinds = setOf(0, 10002)).hasGraphResponse)
    }

    @Test
    fun `empty evidence is tracked independently for contacts and relay lists`() {
        val empty = AccountMetadataFetchResult(
            queriedRelays = setOf("wss://one", "wss://two"),
            eoseRelays = setOf("wss://one", "wss://two"),
        )
        assertTrue(empty.confirmsAbsent(3))
        assertTrue(empty.confirmsAbsent(10002))

        val relayListFound = empty.copy(receivedKinds = setOf(10002))
        assertTrue(relayListFound.confirmsAbsent(3))
        assertFalse(relayListFound.confirmsAbsent(10002))
        assertTrue(relayListFound.resolves(10002))
    }

    @Test
    fun `EOSE outside the admitted query set is not absence evidence`() {
        val result = AccountMetadataFetchResult(
            queriedRelays = setOf("wss://one"),
            eoseRelays = setOf("wss://one", "wss://unrelated"),
        )

        assertFalse(result.confirmsAbsent(3))
    }

    @Test
    fun `empty contact list is materialized only for an unresolved account without an outbox`() {
        val confirmedEmpty = AccountMetadataFetchResult(
            queriedRelays = setOf("wss://one", "wss://two"),
            eoseRelays = setOf("wss://one", "wss://two"),
        )

        assertTrue(
            canMaterializeEmptyContactList(
                localStateResolved = false,
                declaredWriteRelays = emptyList(),
                result = confirmedEmpty,
            ),
        )
        assertFalse(
            canMaterializeEmptyContactList(
                localStateResolved = true,
                declaredWriteRelays = emptyList(),
                result = confirmedEmpty,
            ),
        )
        assertFalse(
            canMaterializeEmptyContactList(
                localStateResolved = false,
                declaredWriteRelays = listOf("wss://outbox.example"),
                result = confirmedEmpty,
            ),
        )

        val outboxConfirmedEmpty = AccountMetadataFetchResult(
            queriedRelays = setOf("wss://outbox.example", "wss://index.example"),
            eoseRelays = setOf("wss://outbox.example"),
        )
        assertTrue(
            canMaterializeEmptyContactList(
                localStateResolved = false,
                declaredWriteRelays = listOf("wss://outbox.example/"),
                result = outboxConfirmedEmpty,
            ),
        )
    }
}
