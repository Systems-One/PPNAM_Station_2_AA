package com.mitas.ppnam.station2aa.data.mqtt

import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import com.mitas.ppnam.station2aa.domain.model.AppSettings
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MqttClientFactory @Inject constructor() {

    fun build(
        settings: AppSettings,
        onConnected: () -> Unit = {},
        onDisconnected: () -> Unit = {}
    ): Mqtt5AsyncClient {
        val builder = MqttClient.builder()
            .useMqttVersion5()
            // Transport identity, distinct from the derived deviceId and unique per connection
            // (base standard §2 rule 6): reusing the deviceId here would let a stale connection
            // with the same client id kick the live one off the broker.
            .identifier("ScannerApp_" + UUID.randomUUID().toString().take(8))
            .serverHost(settings.mqttHost)
            .serverPort(settings.mqttPort)
            .addConnectedListener { onConnected() }
            .addDisconnectedListener { onDisconnected() }

        if (settings.mqttUseWebSocket) {
            builder.webSocketConfig()
                .serverPath("/mqtt")
                .applyWebSocketConfig()
        }

        if (settings.mqttUseTls) {
            builder.sslWithDefaultConfig()
        }

        if (settings.mqttUsername.isNotBlank()) {
            builder.simpleAuth()
                .username(settings.mqttUsername)
                .password(settings.mqttPassword.toByteArray())
                .applySimpleAuth()
        }

        return builder
            .automaticReconnect()
            .initialDelay(1, TimeUnit.SECONDS)
            .maxDelay(30, TimeUnit.SECONDS)
            .applyAutomaticReconnect()
            .buildAsync()
    }
}
