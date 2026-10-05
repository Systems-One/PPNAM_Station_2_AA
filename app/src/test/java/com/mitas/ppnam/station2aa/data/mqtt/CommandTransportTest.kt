package com.mitas.ppnam.station2aa.data.mqtt

import com.google.gson.JsonParser
import com.mitas.ppnam.station2aa.data.identity.DeviceIdentity
import com.mitas.ppnam.station2aa.data.mqtt.outbox.CommandOutcome
import com.mitas.ppnam.station2aa.data.mqtt.outbox.InMemoryCommandOutbox
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingStatus
import com.mitas.ppnam.station2aa.data.mqtt.outbox.UnresolvedReason
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.data.settings.SettingsRepository
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class CommandTransportTest {

    data class CaptureBody(val action: String = "capture", val targetId: String = "PREP_1", val code: String = "TAG-1")
    data class Body(val value: String = "")

    private val device = "scanner_1"
    private lateinit var repo: MqttRepositoryImpl
    private lateinit var sessionHolder: OperatorSessionHolder
    private lateinit var outbox: InMemoryCommandOutbox
    private val published = mutableListOf<Pair<String, ByteArray>>()
    private var outboxSizeAtPublish = -1

    @Before
    fun setup() {
        sessionHolder = OperatorSessionHolder()
        sessionHolder.set(OperatorSession("S-1", "OP-1", "Op", "Worker"))
        outbox = InMemoryCommandOutbox()
        val identity = mock<DeviceIdentity>()
        whenever(identity.deviceId()).thenReturn(device)
        repo = MqttRepositoryImpl(
            clientFactory = mock(),
            settingsRepository = mock<SettingsRepository>(),
            sessionHolder = sessionHolder,
            deviceIdentity = identity,
            commandOutbox = outbox,
        )
        published.clear()
        repo.publishFn = { topic, bytes -> outboxSizeAtPublish = outbox.commands.value.size; published += topic to bytes }
        setConnected(true)
    }

    private fun setConnected(connected: Boolean) {
        val field = MqttRepositoryImpl::class.java.getDeclaredField("_connectionState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(repo) as MutableStateFlow<MqttConnectionState>).value =
            if (connected) MqttConnectionState.CONNECTED else MqttConnectionState.DISCONNECTED
    }

    private fun idOf(index: Int) = JsonParser.parseString(String(published[index].second)).asJsonObject["messageId"].asString

    private fun reply(id: String, success: Boolean = true, error: String = "", data: String? = """{"value":"ok"}""") {
        val dataPart = if (data == null) "" else ""","data":$data"""
        val json = """{"schemaVersion":"rev2.1","contractRevision":"2026-10-01","deviceId":"$device",""" +
            """"inResponseToMessageId":"$id","requestFingerprint":"","success":$success,"error":"$error",""" +
            """"operatorMessage":"","nextAction":"read_saved_state"$dataPart}"""
        repo.handleIncomingResponse("PPNAM/station_2/$device/res/rev2_general_result", json.toByteArray())
    }

    private fun hint() {
        val json = """{"schemaVersion":"rev2.1","contractRevision":"2026-10-01","deviceId":"$device",""" +
            """"messageId":"hint-1","timestampUtc":"2026-10-01T08:00:00.000000Z","mode":"General","reason":"capture","nextAction":"read"}"""
        repo.handleIncomingResponse("PPNAM/station_2/$device/res/active_job_cards_invalidated", json.toByteArray())
    }

    private suspend fun kotlinx.coroutines.test.TestScope.send() =
        async { repo.sendCommand("rev2_general_requested", "rev2_general_result", CaptureBody(), Body::class.java) }
            .also { runCurrent() }

    @Test
    fun `the command is persisted before it is published, with the fingerprint of the exact bytes`() = runTest {
        val call = send()
        assertEquals(1, outboxSizeAtPublish)
        val saved = outbox.commands.value.single()
        assertArrayEquals(published.single().second, saved.payloadBytes)
        assertEquals(RequestFingerprint.of(published.single().second), saved.fingerprint)
        assertEquals("OP-1", saved.operatorId)
        assertEquals("S-1", saved.sessionId)
        assertEquals("capture", saved.action)
        assertEquals("PREP_1", saved.targetId)
        reply(idOf(0))
        call.await()
    }

    @Test
    fun `success settles and leaves the outbox`() = runTest {
        val call = send()
        reply(idOf(0))
        val result = call.await()
        assertTrue(result is CommandOutcome.Settled && result.outcome is MqttOutcome.Accepted)
        assertTrue(outbox.commands.value.isEmpty())
    }

    @Test
    fun `a definite business rejection settles with its snapshot`() = runTest {
        val call = send()
        reply(idOf(0), success = false, error = "rev2_rejected", data = """{"value":"snapshot"}""")
        val result = call.await() as CommandOutcome.Settled
        assertEquals("snapshot", (result.outcome as MqttOutcome.Rejected).body!!.value)
        assertTrue(outbox.commands.value.isEmpty())
    }

    @Test
    fun `no reply stays unresolved for an identical retry`() = runTest {
        val result = send().await()   // virtual time runs the timeout out
        assertEquals(UnresolvedReason.RetryIdentical, (result as CommandOutcome.Unresolved).reason)
        assertEquals(1, outbox.commands.value.size)
    }

    @Test
    fun `outcome_unconfirmed and session loss stay unresolved`() = runTest {
        val first = send()
        reply(idOf(0), success = false, error = "outcome_unconfirmed", data = null)
        assertEquals(UnresolvedReason.RetryIdentical, (first.await() as CommandOutcome.Unresolved).reason)

        val second = send()
        reply(idOf(1), success = false, error = "operator_session_invalid", data = null)
        assertEquals(UnresolvedReason.LoginThenRecover, (second.await() as CommandOutcome.Unresolved).reason)
        assertEquals(2, outbox.commands.value.size)
    }

    @Test
    fun `an unattributable receipt is marked for a manager`() = runTest {
        val call = send()
        reply(idOf(0), success = false, error = "receipt_owner_mismatch", data = null)
        assertEquals(UnresolvedReason.ManagerReconcile, (call.await() as CommandOutcome.Unresolved).reason)
        assertEquals(PendingStatus.ManagerReconcile, outbox.commands.value.single().status)
    }

    @Test
    fun `retry republishes the identical bytes and message id`() = runTest {
        send().await()   // times out
        val command = outbox.commands.value.single()
        val retry = async { repo.retryCommand(command, Body::class.java) }
        runCurrent()
        assertArrayEquals(published[0].second, published[1].second)
        reply(command.messageId)
        assertTrue(retry.await() is CommandOutcome.Settled)
        assertTrue(outbox.commands.value.isEmpty())
    }

    @Test
    fun `retry after a re-login does not publish and asks for recovery`() = runTest {
        send().await()
        sessionHolder.set(OperatorSession("S-2", "OP-1", "Op", "Worker"))
        val result = repo.retryCommand(outbox.commands.value.single(), Body::class.java)
        assertEquals(UnresolvedReason.LoginThenRecover, (result as CommandOutcome.Unresolved).reason)
        assertEquals(1, published.size)
    }

    @Test
    fun `not connected sends nothing and persists nothing`() = runTest {
        setConnected(false)
        val result = repo.sendCommand("rev2_general_requested", "rev2_general_result", CaptureBody(), Body::class.java)
        assertEquals(MqttOutcome.NoResponse(FailureKind.NotConnected), (result as CommandOutcome.Settled).outcome)
        assertTrue(outbox.commands.value.isEmpty())
        assertTrue(published.isEmpty())
    }

    @Test
    fun `a hint arriving mid-command does not resolve it`() = runTest {
        repo.setServerPushHandler { _, _, _ -> }
        val call = send()
        hint()
        runCurrent()
        assertTrue(call.isActive)
        assertEquals(1, outbox.commands.value.size)
        reply(idOf(0))
        assertTrue(call.await() is CommandOutcome.Settled)
    }

    @Test
    fun `a duplicate reply after settlement changes nothing`() = runTest {
        val call = send()
        reply(idOf(0))
        call.await()
        reply(idOf(0))   // QoS 1 redelivery
        assertTrue(outbox.commands.value.isEmpty())
    }
}
