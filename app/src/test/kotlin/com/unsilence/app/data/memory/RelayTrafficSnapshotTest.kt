package com.unsilence.app.data.memory

import com.unsilence.app.data.relay.RelayTrafficMetrics
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RelayTrafficSnapshotTest {
    @Test fun `versioned snapshot separates UID bytes from mechanism counts`() {
        val metrics = RelayTrafficMetrics()
        metrics.eventFrameReceived()
        val sampler = RelayTrafficSnapshot(metrics)
        val value = Json.parseToJsonElement(sampler.sample(123, 456, 10001, 2000, 3000, "stop")).jsonObject
        assertEquals(1, value.getValue("v").jsonPrimitive.int)
        assertEquals(123L, value.getValue("elapsed_ms").jsonPrimitive.long)
        assertEquals(456, value.getValue("pid").jsonPrimitive.int)
        assertEquals(10001, value.getValue("uid").jsonPrimitive.int)
        assertEquals(2000L, value.getValue("uid_rx").jsonPrimitive.long)
        assertEquals(1L, value.getValue("event_frames").jsonPrimitive.long)
        assertEquals("ok", value.getValue("uid_status").jsonPrimitive.content)
    }

    @Test fun `unsupported or decreasing bytes invalidate subsequent process samples`() {
        for ((rx, tx) in listOf(-1L to 200L, 200L to -1L, 99L to 200L, 200L to 99L)) {
            val sampler = RelayTrafficSnapshot(RelayTrafficMetrics())
            sampler.sample(1, 2, 3, 100, 100, "periodic")
            for (sample in listOf(sampler.sample(2, 2, 3, rx, tx, "stop"), sampler.sample(3, 2, 3, 1000, 1000, "periodic"))) {
                val json = Json.parseToJsonElement(sample).jsonObject
                assertEquals("invalid", json.getValue("uid_status").jsonPrimitive.content)
                assertEquals(JsonNull, json["uid_rx"])
                assertEquals(JsonNull, json["uid_tx"])
            }
        }
    }
}
