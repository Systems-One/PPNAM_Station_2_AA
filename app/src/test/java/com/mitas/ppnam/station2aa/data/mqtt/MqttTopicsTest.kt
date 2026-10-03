package com.mitas.ppnam.station2aa.data.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MqttTopicsTest {

    @Test
    fun `request topic nests under the station namespace`() {
        assertEquals(
            "PPNAM/station_2/handheld_1/req/login_requested",
            MqttTopics.request("handheld_1", "login_requested")
        )
    }

    @Test
    fun `responseWildcard subscribes to the res segment only`() {
        assertEquals("PPNAM/station_2/handheld_1/res/+", MqttTopics.responseWildcard("handheld_1"))
    }

    @Test
    fun `device presence lives on the device base node, not a status sub-topic`() {
        assertEquals("PPNAM/station_2/handheld_1", MqttTopics.devicePresence("handheld_1"))
    }

    @Test
    fun `station presence is the literal station base topic`() {
        assertEquals("PPNAM/station_2", MqttTopics.STATION_PRESENCE)
    }

    @Test
    fun `responseTypeOf extracts the last topic segment`() {
        assertEquals(
            "operator_context",
            MqttTopics.responseTypeOf("PPNAM/station_2/handheld_1/res/operator_context")
        )
    }

    @Test
    fun `deviceId containing a slash is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            MqttTopics.request("hand/held", "login_requested")
        }
    }

    @Test
    fun `requestType containing a plus wildcard is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            MqttTopics.request("handheld_1", "login+requested")
        }
    }

    @Test
    fun `requestType containing a hash wildcard is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            MqttTopics.request("handheld_1", "login#requested")
        }
    }

    @Test
    fun `blank deviceId is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            MqttTopics.request("", "login_requested")
        }
    }

    @Test
    fun `responseWildcard validates its device id`() {
        assertThrows(IllegalArgumentException::class.java) {
            MqttTopics.responseWildcard("hand#held")
        }
    }

    @Test
    fun `devicePresence validates its device id`() {
        assertThrows(IllegalArgumentException::class.java) {
            MqttTopics.devicePresence("hand+held")
        }
    }
}
