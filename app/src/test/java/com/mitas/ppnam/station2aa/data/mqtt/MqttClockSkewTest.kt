package com.mitas.ppnam.station2aa.data.mqtt

import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.data.settings.SettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import java.time.Instant

class MqttClockSkewTest {

    private lateinit var repo: MqttRepositoryImpl
    private val deviceNow = Instant.parse("2026-07-16T10:00:00Z")

    @Before
    fun setup() {
        repo = MqttRepositoryImpl(
            clientFactory = mock(),
            settingsRepository = mock<SettingsRepository>(),
            sessionHolder = OperatorSessionHolder(),
        )
        repo.nowFn = { deviceNow }
    }

    private fun receive(serverTimestamp: String) {
        repo.handleIncomingResponse(
            "PPNAM/station_2/handheld_1/res/test_result",
            """{"inResponseToMessageId":"x","timestampUtc":"$serverTimestamp","accepted":true}""".toByteArray()
        )
    }

    @Test
    fun `skew is unknown until a response arrives`() = runTest {
        assertNull(repo.clockSkewMillis.value)
    }

    @Test
    fun `a synchronised clock reports zero skew`() = runTest {
        receive("2026-07-16T10:00:00Z")
        assertEquals(0L, repo.clockSkewMillis.value)
    }

    @Test
    fun `a device clock running behind the server reports positive skew`() = runTest {
        // Server is 45s ahead of the device.
        receive("2026-07-16T10:00:45Z")
        assertEquals(45_000L, repo.clockSkewMillis.value)
    }

    @Test
    fun `a device clock running ahead of the server reports negative skew`() = runTest {
        receive("2026-07-16T09:59:30Z")
        assertEquals(-30_000L, repo.clockSkewMillis.value)
    }

    @Test
    fun `skew is measured even when the response matches no pending request`() = runTest {
        // The response is dropped for correlation purposes, but its timestamp is still evidence
        // about our clock — that signal must not depend on winning a correlation race.
        receive("2026-07-16T10:01:00Z")
        assertEquals(60_000L, repo.clockSkewMillis.value)
    }

    @Test
    fun `an unparseable timestamp leaves the last known skew untouched`() = runTest {
        receive("2026-07-16T10:00:10Z")
        receive("not-a-timestamp")
        assertEquals(10_000L, repo.clockSkewMillis.value)
    }

    @Test
    fun `an absent timestamp leaves the last known skew untouched`() = runTest {
        receive("2026-07-16T10:00:10Z")
        repo.handleIncomingResponse(
            "PPNAM/station_2/handheld_1/res/test_result",
            """{"inResponseToMessageId":"x","accepted":true}""".toByteArray()
        )
        assertEquals(10_000L, repo.clockSkewMillis.value)
    }

    @Test
    fun `the warn threshold is a positive duration`() {
        assertTrue(MqttRepositoryImpl.CLOCK_SKEW_WARN_MS > 0)
    }
}
