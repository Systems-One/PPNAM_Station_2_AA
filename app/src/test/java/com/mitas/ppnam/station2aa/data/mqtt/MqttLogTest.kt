package com.mitas.ppnam.station2aa.data.mqtt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttLogTest {

    private fun line(payload: String? = null, durationMs: Long? = 42L, action: String? = "lookup") = MqttLog.line(
        direction = Direction.OUT,
        topic = "PPNAM/station_2/scanner_x/req/rev2_general_requested",
        qos = 1, retain = false, deviceId = "scanner_x",
        messageType = "rev2_general_requested", action = action,
        result = "published", durationMs = durationMs, payload = payload,
    )

    @Test
    fun `carries every field the base standard requires, plus the rev2_1 action`() {
        val out = line()
        listOf("dir=OUT", "topic=PPNAM/station_2/scanner_x/req/rev2_general_requested", "qos=1",
            "retain=false", "deviceId=scanner_x", "type=rev2_general_requested", "action=lookup",
            "result=published", "durationMs=42").forEach { assertTrue("missing $it in: $out", out.contains(it)) }
    }

    @Test
    fun `omits duration and action when there are none`() {
        val out = line(durationMs = null, action = null)
        assertFalse(out.contains("durationMs"))
        assertFalse(out.contains("action="))
    }

    @Test
    fun `a logged payload is redacted`() {
        val out = line(payload = """{"sessionId":"secret-session","action":"read"}""")
        assertFalse(out.contains("secret-session"))
        assertTrue(out.contains("read"))
    }
}
