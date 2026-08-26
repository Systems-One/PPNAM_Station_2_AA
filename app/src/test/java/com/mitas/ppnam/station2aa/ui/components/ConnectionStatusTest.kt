package com.mitas.ppnam.station2aa.ui.components

import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionStatusTest {

    private fun resolve(
        state: MqttConnectionState = MqttConnectionState.CONNECTED,
        stationOnline: Boolean = true,
        skew: Long? = 0L,
    ) = resolveConnectionStatus(state, stationOnline, skew, SKEW_THRESHOLD_MS)

    @Test
    fun `all well reads as connected`() {
        assertEquals(ConnectionStatus.Connected, resolve())
    }

    @Test
    fun `disconnected outranks everything`() {
        assertEquals(
            ConnectionStatus.Offline,
            resolve(state = MqttConnectionState.DISCONNECTED, stationOnline = false, skew = 90_000L)
        )
    }

    @Test
    fun `reconnecting is reported distinctly from offline`() {
        assertEquals(ConnectionStatus.Reconnecting, resolve(state = MqttConnectionState.RECONNECTING))
    }

    @Test
    fun `broker up but station down is not connected`() {
        // The bug this fixes: "Connected" used to mean only that the broker was reachable, which
        // can be true while Station 2 is down and every request silently times out.
        assertEquals(ConnectionStatus.StationOffline, resolve(stationOnline = false))
    }

    @Test
    fun `station being down outranks a skewed clock`() {
        assertEquals(ConnectionStatus.StationOffline, resolve(stationOnline = false, skew = 90_000L))
    }

    @Test
    fun `a badly skewed clock is surfaced`() {
        assertEquals(ConnectionStatus.ClockSkewed, resolve(skew = 90_000L))
    }

    @Test
    fun `skew is surfaced in both directions`() {
        assertEquals(ConnectionStatus.ClockSkewed, resolve(skew = -90_000L))
    }

    @Test
    fun `skew within tolerance is not surfaced`() {
        assertEquals(ConnectionStatus.Connected, resolve(skew = SKEW_THRESHOLD_MS - 1))
    }

    @Test
    fun `an unmeasured clock is not reported as skewed`() {
        // null means no response has arrived yet to measure against — absence of evidence.
        assertEquals(ConnectionStatus.Connected, resolve(skew = null))
    }
}
