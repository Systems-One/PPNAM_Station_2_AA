package com.mitas.ppnam.station2aa.data.mqtt

import com.mitas.ppnam.station2aa.data.identity.DeviceIdentity
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.data.settings.SettingsRepository
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * One publish, one wait of exactly the configured timeout, then NoResponse. The transport used
 * to republish three times, each waiting the full timeout, so a 20 s setting meant ~60–105 s of
 * spinner with no feedback (audit S2-05). Retry is the operator's call now (a Retry button).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MqttRequestTimeoutTest {

    private lateinit var repo: MqttRepositoryImpl
    private val published = mutableListOf<Pair<String, ByteArray>>()

    @Before
    fun setup() {
        val deviceIdentity = mock<DeviceIdentity>()
        whenever(deviceIdentity.deviceId()).thenReturn("scanner_5c64df8d86a8")
        repo = MqttRepositoryImpl(
            clientFactory = mock(),
            settingsRepository = mock<SettingsRepository>(),
            sessionHolder = OperatorSessionHolder(),
            deviceIdentity = deviceIdentity,
        )
        published.clear()
        repo.publishFn = { topic, bytes -> published += topic to bytes }
        setTimeout(50L)
        forceConnected()
    }

    private fun forceConnected() {
        val field = MqttRepositoryImpl::class.java.getDeclaredField("_connectionState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(repo) as MutableStateFlow<MqttConnectionState>).value = MqttConnectionState.CONNECTED
    }

    private fun setTimeout(ms: Long) {
        val field = MqttRepositoryImpl::class.java.getDeclaredField("requestTimeoutMs")
        field.isAccessible = true
        field.setLong(repo, ms)
    }

    private fun idOf(index: Int): String = com.google.gson.JsonParser
        .parseString(String(published[index].second)).asJsonObject.get("messageId").asString

    @Test
    fun `an unanswered request is published once and times out`() = runTest {
        val outcome = repo.request("a_requested", "test_result", EmptyPayload, TestBody::class.java)

        assertEquals(MqttOutcome.NoResponse(FailureKind.Timeout), outcome)
        assertEquals(1, published.size)
        assertEquals("PPNAM/station_2/scanner_5c64df8d86a8/req/a_requested", published[0].first)
    }

    @Test
    fun `a request waits exactly the configured timeout and no longer`() = runTest {
        setTimeout(10_000L)
        val call = async { repo.request("a_requested", "test_result", EmptyPayload, TestBody::class.java) }
        runCurrent()
        advanceTimeBy(9_999)
        runCurrent()
        assertFalse(call.isCompleted)
        advanceTimeBy(2)
        runCurrent()
        assertTrue(call.isCompleted)
        assertEquals(MqttOutcome.NoResponse(FailureKind.Timeout), call.await())
        assertEquals(1, published.size)
    }

    @Test
    fun `a response stops the wait`() = runTest {
        val call = async { repo.request("a_requested", "test_result", EmptyPayload, TestBody::class.java) }
        while (published.isEmpty()) yield()

        repo.handleIncomingResponse(
            "PPNAM/station_2/scanner_5c64df8d86a8/res/test_result",
            """{"inResponseToMessageId":"${idOf(0)}","success":true,"data":{"value":"ok"}}""".toByteArray()
        )

        val outcome = call.await()
        assertTrue(outcome is MqttOutcome.Accepted)
        assertEquals("ok", (outcome as MqttOutcome.Accepted).body.value)
    }

    @Test
    fun `a reply arriving after the timeout is ignored`() = runTest {
        val outcome = repo.request("a_requested", "test_result", EmptyPayload, TestBody::class.java)
        assertEquals(MqttOutcome.NoResponse(FailureKind.Timeout), outcome)

        // Late reply: no waiter left, nothing to complete, and it must not throw.
        repo.handleIncomingResponse(
            "PPNAM/station_2/scanner_5c64df8d86a8/res/test_result",
            """{"inResponseToMessageId":"${idOf(0)}","success":true,"data":{"value":"late"}}""".toByteArray()
        )
        assertEquals(1, published.size)
    }

    @Test
    fun `a publish failure is reported as not connected, not retried`() = runTest {
        var attempts = 0
        repo.publishFn = { _, _ ->
            attempts++
            throw IllegalStateException("transient publish failure")
        }

        val outcome = repo.request("a_requested", "test_result", EmptyPayload, TestBody::class.java)

        assertEquals(MqttOutcome.NoResponse(FailureKind.NotConnected), outcome)
        assertEquals(1, attempts)
    }
}
