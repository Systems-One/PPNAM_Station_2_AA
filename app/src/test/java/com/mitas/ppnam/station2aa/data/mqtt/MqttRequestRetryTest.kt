package com.mitas.ppnam.station2aa.data.mqtt

import com.mitas.ppnam.station2aa.data.identity.DeviceIdentity
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.data.settings.SettingsRepository
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class MqttRequestRetryTest {

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

    @Test
    fun `an unanswered request is retried up to the attempt limit`() = runTest {
        val outcome = repo.request("a_requested", "test_result", EmptyPayload, TestBody::class.java)

        assertEquals(MqttOutcome.NoResponse(FailureKind.Timeout), outcome)
        assertEquals(MqttRepositoryImpl.REQUEST_MAX_ATTEMPTS, published.size)
    }

    @Test
    fun `every retry republishes a byte-identical payload`() = runTest {
        repo.request("a_requested", "test_result", EmptyPayload, TestBody::class.java)

        assertTrue("expected more than one attempt", published.size > 1)
        val first = published.first().second
        published.forEach { (_, bytes) ->
            // Same messageId AND same timestampUtc. Changing either would break replay identity:
            // the contract rejects a reused messageId with different content as message_id_reused.
            assertArrayEquals(first, bytes)
        }
    }

    @Test
    fun `every retry publishes to the same topic`() = runTest {
        repo.request("a_requested", "test_result", EmptyPayload, TestBody::class.java)
        assertTrue(published.all { it.first == "PPNAM/station_2/scanner_5c64df8d86a8/req/a_requested" })
    }

    @Test
    fun `a response to the first attempt stops further retries`() = runTest {
        val call = async {
            repo.request("a_requested", "test_result", EmptyPayload, TestBody::class.java)
        }
        while (published.isEmpty()) yield()

        val id = com.google.gson.JsonParser
            .parseString(String(published[0].second)).asJsonObject.get("messageId").asString
        repo.handleIncomingResponse(
            "PPNAM/station_2/handheld_1/res/test_result",
            """{"inResponseToMessageId":"$id","success":true,"data":{"value":"ok"}}""".toByteArray()
        )

        val outcome = call.await()
        assertTrue(outcome is MqttOutcome.Accepted)
        assertEquals(1, published.size)
    }

    @Test
    fun `a late response to an earlier attempt still satisfies the request`() = runTest {
        val call = async {
            repo.request("a_requested", "test_result", EmptyPayload, TestBody::class.java)
        }
        // Let the first attempt publish, then time out (50ms) so the second attempt publishes.
        // NOTE: a busy `while (published.size < 2) yield()` loop deadlocks here under runTest's
        // virtual clock — yield() re-enqueues the loop at the SAME virtual instant forever, which
        // starves the scheduler from ever reaching the already-registered 50ms timeout task (it
        // always finds the loop's own immediate continuation ready first). Advancing the virtual
        // clock explicitly past the timeout, then draining ready work, is the deterministic fix.
        runCurrent()
        advanceTimeBy(60)
        runCurrent()
        assertEquals(2, published.size)

        // Both attempts carry the SAME messageId, so answering "the first attempt" answers the request.
        val id = com.google.gson.JsonParser
            .parseString(String(published[0].second)).asJsonObject.get("messageId").asString
        repo.handleIncomingResponse(
            "PPNAM/station_2/handheld_1/res/test_result",
            """{"inResponseToMessageId":"$id","success":true,"data":{"value":"late"}}""".toByteArray()
        )

        val outcome = call.await()
        assertTrue(outcome is MqttOutcome.Accepted)
        assertEquals("late", (outcome as MqttOutcome.Accepted).body.value)
    }

    @Test
    fun `a publish failure on the first attempt is retried rather than abandoned`() = runTest {
        var attempts = 0
        repo.publishFn = { topic, bytes ->
            attempts++
            if (attempts == 1) throw IllegalStateException("transient publish failure")
            published += topic to bytes
        }

        repo.request("a_requested", "test_result", EmptyPayload, TestBody::class.java)

        assertTrue("expected a retry after the transient failure", attempts > 1)
    }
}
