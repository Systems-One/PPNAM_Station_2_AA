package com.mitas.ppnam.station2aa.data.mqtt

import com.mitas.ppnam.station2aa.domain.model.AppSettings
import org.junit.Assert.assertNotNull
import org.junit.Test

class MqttClientFactoryTest {

    private val factory = MqttClientFactory()

    @Test
    fun `build returns non-null client with default WSS settings`() {
        val client = factory.build(AppSettings())
        assertNotNull(client)
    }

    @Test
    fun `build returns non-null client with TCP plain settings`() {
        val client = factory.build(
            AppSettings(
                mqttHost = "localhost",
                mqttPort = 1883,
                mqttUseWebSocket = false,
                mqttUseTls = false,
                mqttUsername = ""
            )
        )
        assertNotNull(client)
    }

    @Test
    fun `build returns non-null client with TLS only settings`() {
        val client = factory.build(
            AppSettings(
                mqttHost = "localhost",
                mqttPort = 8883,
                mqttUseWebSocket = false,
                mqttUseTls = true
            )
        )
        assertNotNull(client)
    }
}
