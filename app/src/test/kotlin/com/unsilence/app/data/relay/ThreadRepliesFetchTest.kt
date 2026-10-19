package com.unsilence.app.data.relay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ThreadRepliesFetchTest {
    @Test fun `thread replies request ORs direct parent and NIP22 root filters`() {
        val req = Json.parseToJsonElement(buildThreadRepliesReq("thread-replies-test", "poll")).jsonArray
        assertEquals("REQ", req[0].jsonPrimitive.content)
        assertEquals("thread-replies-test", req[1].jsonPrimitive.content)
        assertEquals(4, req.size)
        val direct = req[2].jsonObject
        val root = req[3].jsonObject
        assertEquals(listOf(1, 1111), direct["kinds"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() })
        assertEquals(listOf("poll"), direct["#e"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse(direct.containsKey("#E"))
        assertEquals(listOf(1111), root["kinds"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() })
        assertEquals(listOf("poll"), root["#E"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse(root.containsKey("#e"))
    }

    @Test fun `both thread filters remain bounded and do not add article coordinate queries`() {
        val filters = Json.parseToJsonElement(buildThreadRepliesReq("thread-replies-test", "root"))
            .jsonArray.drop(2).map { it.jsonObject }
        assertEquals(2, filters.size)
        for (filter in filters) {
            assertEquals(200, filter["limit"]!!.jsonPrimitive.content.toInt())
            assertFalse(filter.containsKey("#a"))
            assertFalse(filter.containsKey("#A"))
            assertFalse(filter.containsKey("since"))
        }
    }
}
