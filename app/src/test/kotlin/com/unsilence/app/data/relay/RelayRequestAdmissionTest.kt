package com.unsilence.app.data.relay

import org.junit.Assert.*
import org.junit.Test

class RelayRequestAdmissionTest {
    private val event = EventDto("a".repeat(64), "b".repeat(64), 1, "content", 100,
        listOf(listOf("e", "c".repeat(64)), listOf("p", "d".repeat(64)), listOf("t", "Nostr")),
        "0".repeat(128)).toNostrEvent("wss://relay.example")

    private fun matches(filter: String): Boolean {
        val requests = RelayRequestAdmission()
        assertTrue(requests.send("""["REQ","s",$filter]""") { true })
        return requests.request("s")!!.matches(event)
    }

    @Test fun `ids and authors match full exact values only`() {
        assertTrue(matches("""{"ids":["${event.id}"],"authors":["${event.pubkey}"]}"""))
        assertFalse(matches("""{"ids":["aaaa"]}"""))
        assertFalse(matches("""{"authors":["bbbb"]}"""))
        assertFalse(matches("""{"ids":["${event.id.uppercase()}"]}"""))
        assertFalse(matches("""{"authors":["${event.pubkey.uppercase()}"]}"""))
    }

    @Test fun `fields are AND alternatives within arrays are OR`() {
        assertTrue(matches("""{"kinds":[7,1],"authors":["other","${event.pubkey}"]}"""))
        assertFalse(matches("""{"kinds":[7],"authors":["${event.pubkey}"]}"""))
        assertFalse(matches("""{"kinds":[1],"authors":["other"]}"""))
    }

    @Test fun `separate filters use OR without merging their fields`() {
        assertTrue(matches("""{"kinds":[7]},{"authors":["${event.pubkey}"],"kinds":[1]}"""))
        assertFalse(matches("""{"kinds":[7],"authors":["${event.pubkey}"]},{"kinds":[1],"authors":["other"]}"""))
    }

    @Test fun `empty arrays match nothing rather than becoming absent predicates`() {
        for (field in listOf("ids", "authors", "kinds", "#e", "#p", "#t")) {
            assertFalse(field, matches("""{"$field":[]}"""))
        }
        assertTrue(matches("""{"kinds":[]},{}"""))
    }

    @Test fun `tag names and values are case sensitive and conjunctive`() {
        assertTrue(matches("""{"#e":["${"c".repeat(64)}"],"#p":["${"d".repeat(64)}"]}"""))
        assertFalse(matches("""{"#E":["${"c".repeat(64)}"]}"""))
        assertFalse(matches("""{"#P":["${"d".repeat(64)}"]}"""))
        assertTrue(matches("""{"#t":["other","Nostr"]}"""))
        assertFalse(matches("""{"#t":["nostr"]}"""))
        assertFalse(matches("""{"#e":["${"c".repeat(64)}"],"#p":["missing"]}"""))
    }

    @Test fun `since and until are inclusive`() {
        assertTrue(matches("""{"since":100,"until":100}"""))
        assertFalse(matches("""{"since":101}"""))
        assertFalse(matches("""{"until":99}"""))
    }

    @Test fun `search and limit are not invented local predicates`() {
        assertTrue(matches("""{"search":"not a substring","limit":0,"kinds":[1]}"""))
        assertFalse(matches("""{"search":"content","limit":100,"kinds":[7]}"""))
        assertFalse(matches("""{"search":"content","until":99}"""))
    }

    @Test fun `only accepted sends replace the current filter including auth retries`() {
        val requests = RelayRequestAdmission()
        val first = """["REQ","s",{"until":100}]"""
        val replay = """["REQ","s",{"since":101}]"""
        assertFalse(requests.send(first) { false })
        assertNull(requests.request("s"))
        assertTrue(requests.send(first) { true })
        val old = requests.request("s")!!
        assertFalse(requests.send(replay) { false })
        assertSame(old, requests.request("s"))
        assertTrue(requests.send(replay) { true })
        assertFalse(requests.isCurrent(old))
        assertFalse(requests.request("s")!!.matches(event))
        requests.close(requests.request("s")!!)
        assertNull(requests.request("s"))
        // A post-AUTH re-send restores only the filter that the socket accepted.
        assertTrue(requests.send(first) { true })
        assertTrue(requests.request("s")!!.matches(event))
    }

    @Test fun `cancellation revokes admission even if CLOSE cannot be sent`() {
        val requests = RelayRequestAdmission()
        requests.send("""["REQ","s",{}]""") { true }
        assertFalse(requests.send("""["CLOSE","s"]""") { false })
        assertNull(requests.request("s"))
    }

    @Test fun `bounded table rejects excess requests without forgetting owners`() {
        val requests = RelayRequestAdmission(capacity = 2)
        requests.send("""["REQ","a",{}]""") { true }
        requests.send("""["REQ","b",{}]""") { true }
        var sends = 0
        assertFalse(requests.send("""["REQ","c",{}]""") { sends++; true })
        assertEquals(0, sends)
        assertNotNull(requests.request("a"))
        assertNotNull(requests.request("b"))
        assertTrue(requests.send("""["REQ","a",{"kinds":[7]}]""") { true })
        requests.send("""["CLOSE","b"]""") { true }
        assertTrue(requests.send("""["REQ","c",{}]""") { true })
        requests.clear()
        assertNull(requests.request("a"))
        assertNull(requests.request("c"))
        assertFalse(requests.send("""["REQ","a",{}]""") { true })
    }

    @Test fun `sequential one shots and pages do not exhaust active request capacity`() {
        val socket = AdmissionSocket()
        val connection = socket.connection()
        try {
            repeat(1_024) { n ->
                val id = "page-$n"
                assertTrue(connection.send("""["REQ","$id",{"kinds":[1],"until":${1000 - n}}]"""))
                assertNotNull(connection.requests.request(id))
                assertTrue(connection.send("""["CLOSE","$id"]"""))
                assertNull(connection.requests.request(id))
            }
            assertEquals(2_048, socket.sent.size)
        } finally { connection.close() }
    }

    @Test fun `production thread and engagement builders preserve their distinct tag scopes`() {
        val root = "c".repeat(64)
        val coord = "30023:${event.pubkey}:article"
        val requests = RelayRequestAdmission()
        val thread = threadRequestPayloads(root, "fixture")
        thread.forEach { assertTrue(requests.send(it) { true }) }
        assertTrue(requests.request("thread-event-fixture")!!.matches(event.copy(id = root)))
        assertTrue(requests.request("thread-replies-fixture")!!.matches(event))
        assertTrue(requests.request("thread-replies-fixture")!!.matches(event.copy(kind = 1111, tags = listOf(listOf("E", root)))))
        assertFalse(requests.request("thread-replies-fixture")!!.matches(event.copy(kind = 1, tags = listOf(listOf("E", root)))))
        assertTrue(requests.request("thread-reactions-fixture")!!.matches(event.copy(kind = 7)))
        assertTrue(requests.request("thread-zaps-fixture")!!.matches(event.copy(kind = 9735)))
        assertTrue(requests.send(buildBatchedEngagementReq("batch", listOf(root), listOf(coord))) { true })
        assertTrue(requests.send(buildOwnEngagementReq("own", event.pubkey, listOf(root), listOf(coord))) { true })
        for (tag in listOf("a", "A")) {
            val reaction = event.copy(kind = 7, tags = listOf(listOf(tag, coord)))
            assertTrue(requests.request("batch")!!.matches(reaction))
            assertTrue(requests.request("own")!!.matches(reaction))
            assertFalse(requests.request("own")!!.matches(reaction.copy(pubkey = "other")))
        }
        // Coordinate-only batches intentionally contain an empty #e branch. It must
        // match nothing while the separate #a/#A filters still match the article.
        requests.send(buildBatchedEngagementReq("coordinate", emptyList(), listOf(coord))) { true }
        assertFalse(requests.request("coordinate")!!.matches(event))
        assertTrue(requests.request("coordinate")!!.matches(event.copy(kind = 1111, tags = listOf(listOf("A", coord)))))
        assertTrue(requests.send(buildCommentParentsReq("parents", listOf(root))) { true })
        assertTrue(requests.request("parents")!!.matches(event.copy(id = root)))
        assertTrue(requests.send(buildAccountMetadataReq("metadata", event.pubkey)) { true })
        assertTrue(requests.request("metadata")!!.matches(event.copy(kind = 10002)))
    }

    @Test fun `local CLOSE still revokes buffered ownership after remote socket termination`() {
        val socket = AdmissionSocket()
        val connection = socket.connection()
        connection.send("""["REQ","s",{}]""")
        socket.listener.onClosed(socket, 1000, "done")
        assertNotNull(connection.requests.request("s"))
        assertFalse(connection.send("""["CLOSE","s"]"""))
        assertNull(connection.requests.request("s"))
        assertFalse(connection.send("""["REQ","new",{}]"""))
        assertNull(connection.requests.request("new"))
        connection.close()
    }

    @Test fun `malformed request cannot create or replace an admission contract`() {
        val requests = RelayRequestAdmission()
        requests.send("""["REQ","s",{}]""") { true }
        val old = requests.request("s")
        for (bad in listOf("""["REQ","s"]""", """["REQ","s",{"ids":null}]""",
            """["REQ","s",{"authors":[42]}]""", """["REQ","s",{"kinds":"1"}]""")) {
            assertFalse(bad, requests.send(bad) { fail("malformed request sent"); true })
            assertSame(old, requests.request("s"))
        }
    }
}
