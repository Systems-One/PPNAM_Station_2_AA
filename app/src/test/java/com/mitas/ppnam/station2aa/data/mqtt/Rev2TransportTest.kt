package com.mitas.ppnam.station2aa.data.mqtt

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mitas.ppnam.station2aa.data.identity.DeviceIdentity
import com.mitas.ppnam.station2aa.data.mqtt.dto.ResponseEnvelope
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.data.settings.SettingsRepository
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

data class TestBody(val value: String = "")

/**
 * The rev2.1 request/response contract as the transport sees it: envelope, body under `data`,
 * correlation on `inResponseToMessageId`, duplicate handling, and the request-side session guard.
 */
class Rev2TransportTest {

    private val device = "scanner_5c64df8d86a8"
    private lateinit var repo: MqttRepositoryImpl
    private lateinit var sessionHolder: OperatorSessionHolder
    private val published = mutableListOf<Pair<String, ByteArray>>()

    @Before
    fun setup() {
        sessionHolder = OperatorSessionHolder()
        val identity = mock<DeviceIdentity>()
        whenever(identity.deviceId()).thenReturn(device)
        repo = MqttRepositoryImpl(
            clientFactory = mock(),
            settingsRepository = mock<SettingsRepository>(),
            sessionHolder = sessionHolder,
            deviceIdentity = identity,
        )
        published.clear()
        repo.publishFn = { topic, bytes -> published += topic to bytes }
        val field = MqttRepositoryImpl::class.java.getDeclaredField("_connectionState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(repo) as MutableStateFlow<MqttConnectionState>).value = MqttConnectionState.CONNECTED
    }

    private fun sent(index: Int): JsonObject =
        JsonParser.parseString(String(published[index].second)).asJsonObject

    private fun idOf(index: Int): String = sent(index)["messageId"].asString

    private fun session(id: String) =
        OperatorSession(operatorSessionId = id, operatorId = "OP-1", operatorName = "Op", role = "Worker")

    private fun reply(
        inResponseTo: String,
        success: Boolean = true,
        error: String = "",
        message: String = "",
        data: String? = """{"value":"ok"}""",
    ) {
        val dataPart = if (data == null) "" else ""","data":$data"""
        val json = """{"schemaVersion":"rev2.1","deviceId":"$device",""" +
            """"inResponseToMessageId":"$inResponseTo","receivedAtUtc":"2026-09-30T10:00:00.000000Z",""" +
            """"sentAtUtc":"2026-09-30T10:00:00.012000Z","durationMs":12.5,"success":$success,""" +
            """"error":"$error","operatorMessage":"$message",""" +
            """"nextAction":"Follow the saved job or preparation state."$dataPart}"""
        repo.handleIncomingResponse("PPNAM/station_2/$device/res/rev2_general_result", json.toByteArray())
    }

    private fun push(messageId: String?) {
        val idPart = if (messageId == null) "" else """"messageId":"$messageId","""
        val json = """{"schemaVersion":"rev2.1","deviceId":"$device",$idPart""" +
            """"timestampUtc":"2026-09-30T10:00:00.000000Z","mode":"General",""" +
            """"reason":"preparation_created","nextAction":"read"}"""
        repo.handleIncomingResponse(
            "PPNAM/station_2/$device/res/active_job_cards_invalidated", json.toByteArray()
        )
    }

    private suspend fun read() = repo.request(
        "rev2_general_requested", "rev2_general_result", mapOf("action" to "read"), TestBody::class.java
    )

    // ---- envelope -------------------------------------------------------------------------------

    @Test
    fun `publishes to the device's own req topic`() = runTest {
        val call = async { read() }
        while (published.isEmpty()) yield()
        assertEquals("PPNAM/station_2/$device/req/rev2_general_requested", published[0].first)
        reply(idOf(0)); call.await()
    }

    @Test
    fun `a workflow request carries the rev2_1 envelope and the active session`() = runTest {
        sessionHolder.set(session("sess-A"))
        val call = async { read() }
        while (published.isEmpty()) yield()
        val body = sent(0)
        assertEquals("rev2.1", body["schemaVersion"].asString)
        assertEquals(device, body["deviceId"].asString)
        assertEquals("sess-A", body["sessionId"].asString)
        assertEquals("read", body["action"].asString)
        assertTrue(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z""")
            .matches(body["timestampUtc"].asString))
        assertTrue(body["messageId"].asString.length in 1..100)
        assertFalse(body.has("operatorSessionId"))
        assertFalse(body.has("correlationKey"))
        reply(idOf(0)); call.await()
    }

    @Test
    fun `a request with no session omits sessionId`() = runTest {
        val call = async { read() }
        while (published.isEmpty()) yield()
        assertFalse(sent(0).has("sessionId"))
        reply(idOf(0)); call.await()
    }

    @Test
    fun `a SCRAM request never carries a session even when one is active`() = runTest {
        sessionHolder.set(session("sess-A"))
        val call = async {
            repo.request("scram_start_requested", "scram_challenge", mapOf("username" to "op"), TestBody::class.java)
        }
        while (published.isEmpty()) yield()
        assertFalse(sent(0).has("sessionId"))
        reply(idOf(0)); call.await()
    }

    @Test
    fun `operator_list_requested never carries a session even when one is active`() = runTest {
        sessionHolder.set(session("sess-A"))
        val call = async {
            repo.request("operator_list_requested", "operator_list", EmptyPayload, TestBody::class.java)
        }
        while (published.isEmpty()) yield()
        val body = sent(0)
        assertFalse(body.has("sessionId"))
        assertEquals("PPNAM/station_2/$device/req/operator_list_requested", published[0].first)
        assertEquals(setOf("schemaVersion", "deviceId", "messageId", "timestampUtc"), body.keySet())
        reply(idOf(0)); call.await()
    }

    // ---- correlation and parsing ------------------------------------------------------------

    @Test
    fun `two concurrent requests answered out of order each get their own body`() = runTest {
        val first = async { read() }
        val second = async { read() }
        while (published.size < 2) yield()
        assertNotEquals(idOf(0), idOf(1))

        reply(idOf(1), data = """{"value":"second"}""")
        reply(idOf(0), data = """{"value":"first"}""")

        assertEquals("first", (first.await() as MqttOutcome.Accepted).body.value)
        assertEquals("second", (second.await() as MqttOutcome.Accepted).body.value)
    }

    @Test
    fun `a success with no data is a malformed response`() = runTest {
        val call = async { read() }
        while (published.isEmpty()) yield()
        reply(idOf(0), data = null)
        assertEquals(MqttOutcome.NoResponse(FailureKind.MalformedResponse), call.await())
    }

    @Test
    fun `a rejection without data carries a null body, the code and the operator message`() = runTest {
        val call = async { read() }
        while (published.isEmpty()) yield()
        reply(idOf(0), success = false, error = "invalid_envelope", message = "Check schemaVersion.", data = null)
        val outcome = call.await() as MqttOutcome.Rejected
        assertNull(outcome.body)
        assertEquals(ErrorCode.INVALID_ENVELOPE, outcome.error)
        assertEquals("Check schemaVersion.", outcome.operatorMessage)
    }

    @Test
    fun `a rejection with data keeps the snapshot body`() = runTest {
        val call = async { read() }
        while (published.isEmpty()) yield()
        reply(idOf(0), success = false, error = "rev2_rejected", data = """{"value":"snapshot"}""")
        val outcome = call.await() as MqttOutcome.Rejected
        assertEquals("snapshot", outcome.body?.value)
        assertEquals(ErrorCode.REV2_REJECTED, outcome.error)
    }

    @Test
    fun `a rejection with a blank error and message reports both as absent`() = runTest {
        val call = async { read() }
        while (published.isEmpty()) yield()
        reply(idOf(0), success = false, error = "", message = "", data = null)
        val outcome = call.await() as MqttOutcome.Rejected
        assertNull(outcome.error)
        assertNull(outcome.operatorMessage)
    }

    @Test
    fun `a second reply to the same request is dropped`() = runTest {
        val call = async { read() }
        while (published.isEmpty()) yield()
        reply(idOf(0), data = """{"value":"first"}""")
        reply(idOf(0), data = """{"value":"again"}""")
        assertEquals("first", (call.await() as MqttOutcome.Accepted).body.value)
    }

    // ---- session guard ------------------------------------------------------------------------

    @Test
    fun `operator_session_invalid clears the session the request was sent with`() = runTest {
        sessionHolder.set(session("sess-A"))
        val call = async { read() }
        while (published.isEmpty()) yield()
        reply(idOf(0), success = false, error = "operator_session_invalid", data = null)
        call.await()
        assertNull(sessionHolder.session.value)
    }

    @Test
    fun `operator_session_invalid for a request sent under an older session keeps the new one`() = runTest {
        sessionHolder.set(session("sess-A"))
        val call = async { read() }
        while (published.isEmpty()) yield()
        sessionHolder.set(session("sess-B"))
        reply(idOf(0), success = false, error = "operator_session_invalid", data = null)
        call.await()
        assertEquals("sess-B", sessionHolder.session.value?.operatorSessionId)
    }

    @Test
    fun `operator_session_invalid for a request sent before login keeps the new session`() = runTest {
        val call = async { read() }
        while (published.isEmpty()) yield()
        sessionHolder.set(session("sess-A"))
        reply(idOf(0), success = false, error = "operator_session_invalid", data = null)
        call.await()
        assertEquals("sess-A", sessionHolder.session.value?.operatorSessionId)
    }

    @Test
    fun `a late operator_session_invalid matching no request keeps the session`() {
        sessionHolder.set(session("sess-A"))
        reply("no-such-request", success = false, error = "operator_session_invalid", data = null)
        assertEquals("sess-A", sessionHolder.session.value?.operatorSessionId)
    }

    @Test
    fun `client_upgrade_required latches upgradeRequired`() {
        reply("no-such-request", success = false, error = "client_upgrade_required", data = null)
        assertTrue(repo.upgradeRequired.value)
    }

    // ---- pushes -----------------------------------------------------------------------------

    @Test
    fun `a server push reaches the push handler`() {
        val seen = mutableListOf<ResponseEnvelope>()
        repo.setServerPushHandler { _, envelope, _ -> seen += envelope }
        push("rev2-preparations-1")
        assertEquals(1, seen.size)
        assertEquals("General", seen[0].mode)
        assertEquals("preparation_created", seen[0].reason)
    }

    @Test
    fun `a redelivered server push is handled once`() {
        var count = 0
        repo.setServerPushHandler { _, _, _ -> count++ }
        push("rev2-preparations-1")
        push("rev2-preparations-1")
        assertEquals(1, count)
    }

    @Test
    fun `a push without a messageId is still handled`() {
        var count = 0
        repo.setServerPushHandler { _, _, _ -> count++ }
        push(null)
        assertEquals(1, count)
    }

    @Test
    fun `a push with no handler registered is dropped without error`() {
        push("rev2-preparations-1")
    }

    // ---- strict parsing ---------------------------------------------------------------------

    @Test
    fun `a reply with a duplicate property is dropped, so the request times out rather than guessing`() = runTest {
        val call = async { read() }
        while (published.isEmpty()) yield()
        val json = """{"inResponseToMessageId":"${idOf(0)}","success":true,"Success":false,"data":{"value":"x"}}"""
        repo.handleIncomingResponse("PPNAM/station_2/$device/res/rev2_general_result", json.toByteArray())
        assertEquals(MqttOutcome.NoResponse(FailureKind.Timeout), call.await())
    }

    // ---- uncorrelated non-push messages -----------------------------------------------------

    @Test
    fun `an uncorrelated invalid_envelope rejection does not reach the push handler`() {
        var count = 0
        repo.setServerPushHandler { _, _, _ -> count++ }
        reply("", success = false, error = "invalid_envelope", data = null)
        assertEquals(0, count)
    }

    @Test
    fun `an uncorrelated client_upgrade_required still latches but does not reach the push handler`() {
        var count = 0
        repo.setServerPushHandler { _, _, _ -> count++ }
        reply("", success = false, error = "client_upgrade_required", data = null)
        assertTrue(repo.upgradeRequired.value)
        assertEquals(0, count)
    }
}
