package com.mitas.ppnam.station2aa.domain.model

import org.junit.Assert.*
import org.junit.Test

class AppSettingsTest {

    @Test
    fun `default deviceId is handheld_1`() {
        assertEquals("handheld_1", AppSettings().deviceId)
    }

    @Test
    fun `default mqtt host is mqtt sysone co za`() {
        assertEquals("mqtt.sysone.co.za", AppSettings().mqttHost)
    }

    @Test
    fun `default mqtt port is 443`() {
        assertEquals(443, AppSettings().mqttPort)
    }

    @Test
    fun `default uses websocket and tls`() {
        val s = AppSettings()
        assertTrue(s.mqttUseWebSocket)
        assertTrue(s.mqttUseTls)
    }

    @Test
    fun `there are no default broker credentials`() {
        // This test used to assert admin/admin. The Schema 4.1 handoff blocks production on the
        // absence of exactly that — shared handheld credentials, source-code credentials and APK
        // constants must all be gone, and each handheld needs its own credential bound to its own
        // client ID. A default here IS an APK constant: it ships to every device inside the app.
        val s = AppSettings()

        assertEquals("", s.mqttUsername)
        assertEquals("", s.mqttPassword)
        assertFalse("an unprovisioned handheld must not claim to have a credential", s.hasBrokerCredential)
    }

    @Test
    fun `a handheld reports provisioned only when both parts are present`() {
        assertFalse(AppSettings(mqttUsername = "station2-hh-01").hasBrokerCredential)
        assertFalse(AppSettings(mqttPassword = "secret").hasBrokerCredential)
        assertFalse(AppSettings(mqttUsername = "  ", mqttPassword = "secret").hasBrokerCredential)
        assertTrue(AppSettings(mqttUsername = "station2-hh-01", mqttPassword = "secret").hasBrokerCredential)
    }

    @Test
    fun `default requestTimeoutMs is 20000`() {
        // Raised from 10s: factory-floor WiFi is unreliable enough that 3 x 10s (30s total)
        // regularly wasn't enough headroom, causing genuinely-accepted requests to be timed out
        // client-side while Station 2 processed them — see MixingBoardViewModel's Failed re-sync.
        assertEquals(20_000L, AppSettings().requestTimeoutMs)
    }
}
