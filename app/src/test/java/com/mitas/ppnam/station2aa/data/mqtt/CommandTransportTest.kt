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
        repo.ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
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
        assertEquals(device, saved.deviceId)
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
    fun `retry of a command sent from another scanner publishes nothing and goes to a manager`() = runTest {
        send().await()   // times out
        val foreign = outbox.commands.value.single().copy(deviceId = "scanner_2")
        val result = repo.retryCommand(foreign, Body::class.java)
        assertEquals(UnresolvedReason.ManagerReconcile, (result as CommandOutcome.Unresolved).reason)
        assertEquals(1, published.size)
        assertEquals(PendingStatus.ManagerReconcile, outbox.commands.value.single().status)
    }

    @Test
    fun `retry of a legacy command with no device id is still republished`() = runTest {
        send().await()   // times out
        val legacy = outbox.commands.value.single().copy(deviceId = "")
        val retry = async { repo.retryCommand(legacy, Body::class.java) }
        runCurrent()
        assertEquals(2, published.size)
        reply(legacy.messageId)
        assertTrue(retry.await() is CommandOutcome.Settled)
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

    @Test
    fun `retry while the same id is still awaiting a reply publishes nothing and the first still settles`() = runTest {
        val first = send()
        val command = outbox.commands.value.single()
        val result = repo.retryCommand(command, Body::class.java)
        assertEquals(UnresolvedReason.RetryIdentical, (result as CommandOutcome.Unresolved).reason)
        assertEquals(1, published.size)
        reply(command.messageId)
        assertTrue(first.await() is CommandOutcome.Settled)
        assertTrue(outbox.commands.value.isEmpty())
    }

    @Test
    fun `two concurrent retries of the same command publish at most once more`() {
        // A fresh transport whose first device-id lookup is held open: on the old code that lookup
        // sat between the in-flight pre-check and the registration, so both callers passed the check.
        val gate = java.util.concurrent.CountDownLatch(1)
        val identity = mock<DeviceIdentity>()
        whenever(identity.deviceId()).thenAnswer { gate.await(5, java.util.concurrent.TimeUnit.SECONDS); device }
        val publishes = java.util.concurrent.atomic.AtomicInteger()
        val fresh = MqttRepositoryImpl(
            clientFactory = mock(),
            settingsRepository = mock<SettingsRepository>(),
            sessionHolder = sessionHolder,
            deviceIdentity = identity,
            commandOutbox = outbox,
        )
        fresh.ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        fresh.publishFn = { _, _ -> publishes.incrementAndGet() }
        val stateField = MqttRepositoryImpl::class.java.getDeclaredField("_connectionState").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (stateField.get(fresh) as MutableStateFlow<MqttConnectionState>).value = MqttConnectionState.CONNECTED
        val payload = """{"action":"capture","messageId":"77777777-7777-7777-7777-777777777777"}"""
        val command = com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingCommand(
            messageId = "77777777-7777-7777-7777-777777777777",
            requestType = "rev2_general_requested",
            responseType = "rev2_general_result",
            action = "capture",
            payload = payload,
            fingerprint = RequestFingerprint.of(payload.toByteArray()),
            operatorId = "OP-1",
            sessionId = "S-1",
        )
        outbox.save(command)

        kotlinx.coroutines.runBlocking {
            val a = async(kotlinx.coroutines.Dispatchers.Default) { fresh.retryCommand(command, Body::class.java) }
            val b = async(kotlinx.coroutines.Dispatchers.Default) { fresh.retryCommand(command, Body::class.java) }
            Thread.sleep(300)   // both callers reach the held lookup
            gate.countDown()
            // One caller is refused without publishing; wait for it, then answer the other.
            kotlinx.coroutines.withTimeout(5_000) {
                while (!a.isCompleted && !b.isCompleted) kotlinx.coroutines.delay(10)
            }
            val json = """{"schemaVersion":"rev2.1","contractRevision":"2026-10-01","deviceId":"$device",""" +
                """"inResponseToMessageId":"${command.messageId}","requestFingerprint":"","success":true,"error":"",""" +
                """"operatorMessage":"","nextAction":"read_saved_state","data":{"value":"ok"}}"""
            fresh.handleIncomingResponse("PPNAM/station_2/$device/res/rev2_general_result", json.toByteArray())
            kotlinx.coroutines.withTimeout(5_000) { a.await(); b.await() }
        }
        assertEquals(1, publishes.get())
    }

    @Test
    fun `cancelling the caller after the reply arrived still removes the entry`() = runTest {
        // Outbox writes run on their own scheduler, so the caller can be cancelled after the reply
        // resumed it but before the post-settle write was dispatched.
        val ioQueue = ArrayDeque<Runnable>()
        val io = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { ioQueue += block }
        }
        val payload = """{"action":"capture","messageId":"88888888-8888-8888-8888-888888888888"}"""
        val command = com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingCommand(
            messageId = "88888888-8888-8888-8888-888888888888",
            requestType = "rev2_general_requested",
            responseType = "rev2_general_result",
            action = "capture",
            payload = payload,
            fingerprint = RequestFingerprint.of(payload.toByteArray()),
            operatorId = "OP-1",
            sessionId = "S-1",
        )
        outbox.save(command)
        repo.ioDispatcher = io
        val call = async { repo.retryCommand(command, Body::class.java) }
        runCurrent()
        assertEquals(1, published.size)
        reply(command.messageId)
        runCurrent()                 // the caller resumes and asks for the outbox write
        call.cancel()                // ...and is cancelled before that write runs
        assertEquals(1, ioQueue.size)
        while (ioQueue.isNotEmpty()) ioQueue.removeFirst().run()
        runCurrent()
        assertTrue(outbox.commands.value.isEmpty())
    }

    @Test
    fun `a publish failure after persisting stays unresolved and keeps the entry`() = runTest {
        repo.publishFn = { _, _ -> throw java.io.IOException("socket closed") }
        val result = repo.sendCommand("rev2_general_requested", "rev2_general_result", CaptureBody(), Body::class.java)
        assertEquals(UnresolvedReason.RetryIdentical, (result as CommandOutcome.Unresolved).reason)
        assertEquals(1, outbox.commands.value.size)
    }
}
