package com.mitas.ppnam.station2aa.data.mqtt

import com.google.gson.JsonParser
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.data.settings.SettingsRepository
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

data class TestBody(val value: String = "")

class MqttRequestCorrelationTest {

    private lateinit var repo: MqttRepositoryImpl
    private lateinit var sessionHolder: OperatorSessionHolder
    private val published = mutableListOf<Pair<String, ByteArray>>()

    @Before
    fun setup() {
        sessionHolder = OperatorSessionHolder()
        repo = MqttRepositoryImpl(
            clientFactory = mock(),
            settingsRepository = mock<SettingsRepository>(),
            sessionHolder = sessionHolder,
        )
        published.clear()
        repo.publishFn = { topic, bytes -> published += topic to bytes }
        forceConnected()
    }

    private fun forceConnected() {
        val field = MqttRepositoryImpl::class.java.getDeclaredField("_connectionState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(repo) as MutableStateFlow<MqttConnectionState>).value = MqttConnectionState.CONNECTED
    }

    private fun messageIdOf(index: Int): String =
        JsonParser.parseString(String(published[index].second)).asJsonObject.get("messageId").asString

    @Suppress("UNCHECKED_CAST")
    private fun pendingMap(): Map<String, *> {
        val field = MqttRepositoryImpl::class.java.getDeclaredField("pending")
        field.isAccessible = true
        return field.get(repo) as Map<String, *>
    }

    private fun respond(inResponseTo: String, accepted: Boolean = true, value: String = "ok") {
        val json = """
            {"inResponseToMessageId":"$inResponseTo","schemaVersion":"4.0","accepted":$accepted,
             "nextAction":"scan_ingredient","value":"$value"}
        """.trimIndent()
        repo.handleIncomingResponse("PPNAM/station_2/handheld_1/res/test_result", json.toByteArray())
    }

    @Test
    fun `two concurrent requests whose responses arrive out of order each resolve correctly`() = runTest {
        val first = async {
            repo.request("a_requested", "test_result", EmptyPayload, null, TestBody::class.java)
        }
        val second = async {
            repo.request("b_requested", "test_result", EmptyPayload, null, TestBody::class.java)
        }

        // Wait until both requests have actually published before answering either.
        while (published.size < 2) yield()

        val firstId = messageIdOf(0)
        val secondId = messageIdOf(1)

        // The contract requires every request to be a distinct operation: an approval retry that
        // reuses a messageId is rejected as message_id_reused and silently does NOT approve.
        // Without this, id collision would surface only as a hang, not a failure.
        assertNotEquals("every request() call must mint a fresh messageId", firstId, secondId)

        // Answer in REVERSE order. Topic-based matching would hand the second response to the
        // first caller; inResponseToMessageId must not.
        respond(secondId, value = "second")
        respond(firstId, value = "first")

        val firstOutcome = first.await()
        val secondOutcome = second.await()

        assertTrue(firstOutcome is MqttOutcome.Accepted)
        assertTrue(secondOutcome is MqttOutcome.Accepted)
        assertEquals("first", (firstOutcome as MqttOutcome.Accepted).body.value)
        assertEquals("second", (secondOutcome as MqttOutcome.Accepted).body.value)
    }

    @Test
    fun `login and logout in flight together on operator_context resolve correctly`() = runTest {
        // Both request types answer on the SAME response topic. This is why topic matching cannot work.
        val login = async {
            repo.request("login_requested", "operator_context", EmptyPayload, null, TestBody::class.java)
        }
        val logout = async {
            repo.request("reader_logout_requested", "operator_context", EmptyPayload, null, TestBody::class.java)
        }
        while (published.size < 2) yield()

        respond(messageIdOf(1), value = "logout")
        respond(messageIdOf(0), value = "login")

        assertEquals("login", (login.await() as MqttOutcome.Accepted).body.value)
        assertEquals("logout", (logout.await() as MqttOutcome.Accepted).body.value)
    }

    @Test
    fun `request publishes to the req topic`() = runTest {
        val call = async {
            repo.request("login_requested", "operator_context", EmptyPayload, null, TestBody::class.java)
        }
        while (published.isEmpty()) yield()
        assertEquals("PPNAM/station_2/handheld_1/req/login_requested", published[0].first)
        respond(messageIdOf(0))
        call.await()
    }

    @Test
    fun `request injects the active operator session id`() = runTest {
        sessionHolder.set(
            OperatorSession(
                operatorSessionId = "session-abc",
                operatorId = "OP-001",
                operatorName = "Operator One",
                role = "Operator",
            )
        )
        val call = async {
            repo.request("active_job_cards_requested", "active_job_cards_list", EmptyPayload, null, TestBody::class.java)
        }
        while (published.isEmpty()) yield()
        val json = JsonParser.parseString(String(published[0].second)).asJsonObject
        assertEquals("session-abc", json.get("operatorSessionId").asString)
        respond(messageIdOf(0))
        call.await()
    }

    @Test
    fun `a rejected response is classified as Rejected and still carries its body`() = runTest {
        val call = async {
            repo.request("a_requested", "test_result", EmptyPayload, null, TestBody::class.java)
        }
        while (published.isEmpty()) yield()
        respond(messageIdOf(0), accepted = false, value = "still-here")

        val outcome = call.await()
        assertTrue(outcome is MqttOutcome.Rejected)
        assertEquals("still-here", (outcome as MqttOutcome.Rejected).body.value)
        assertEquals(NextAction("scan_ingredient"), outcome.nextAction)
    }

    @Test
    fun `a rejected response exposes its error code`() = runTest {
        val call = async {
            repo.request("a_requested", "test_result", EmptyPayload, null, TestBody::class.java)
        }
        while (published.isEmpty()) yield()
        val id = messageIdOf(0)
        repo.handleIncomingResponse(
            "PPNAM/station_2/handheld_1/res/test_result",
            """{"inResponseToMessageId":"$id","accepted":false,"errorCode":"session_required","reason":"No session"}""".toByteArray()
        )

        val outcome = call.await() as MqttOutcome.Rejected
        assertEquals(ErrorCode.SESSION_REQUIRED, outcome.errorCode)
        assertEquals("No session", outcome.reason)
    }

    @Test
    fun `request returns NotConnected without publishing when disconnected`() = runTest {
        val field = MqttRepositoryImpl::class.java.getDeclaredField("_connectionState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(repo) as MutableStateFlow<MqttConnectionState>).value = MqttConnectionState.DISCONNECTED

        val outcome = repo.request("a_requested", "test_result", EmptyPayload, null, TestBody::class.java)

        assertEquals(MqttOutcome.NoResponse(FailureKind.NotConnected), outcome)
        assertTrue(published.isEmpty())
    }

    @Test
    fun `an unmatched response is dropped without side effects`() = runTest {
        repo.handleIncomingResponse(
            "PPNAM/station_2/handheld_1/res/test_result",
            """{"inResponseToMessageId":"nobody-is-waiting","accepted":true}""".toByteArray()
        )
        // No crash, nothing pending. Reaching here is the assertion.
        assertTrue(published.isEmpty())
    }

    @Test
    fun `a response with no inResponseToMessageId is dropped`() = runTest {
        repo.handleIncomingResponse(
            "PPNAM/station_2/handheld_1/res/test_result",
            """{"accepted":true}""".toByteArray()
        )
        assertTrue(published.isEmpty())
    }

    @Test
    fun `a malformed response body yields MalformedResponse`() = runTest {
        val call = async {
            repo.request("a_requested", "test_result", EmptyPayload, null, TestBody::class.java)
        }
        while (published.isEmpty()) yield()
        val id = messageIdOf(0)
        // Valid envelope so it routes, but `value` is an object where a String is expected.
        repo.handleIncomingResponse(
            "PPNAM/station_2/handheld_1/res/test_result",
            """{"inResponseToMessageId":"$id","accepted":true,"value":{"nested":1}}""".toByteArray()
        )
        assertEquals(MqttOutcome.NoResponse(FailureKind.MalformedResponse), call.await())
    }

    @Test
    fun `cancelling a caller mid-publish propagates rather than reporting a publish failure`() = runTest {
        // Note: a plain `job.await()` throws CancellationException whether or not request()
        // rethrows it, because JobSupport forces a cancelled Deferred to finalize as Cancelled
        // once cancel() has been called, no matter what value the coroutine body returns. That
        // makes it the wrong probe for this bug. The actual bug is that, without the fix,
        // request() *swallows* the CancellationException and returns a normal MqttOutcome value
        // instead of rethrowing — so code sequenced after the request() call keeps running with
        // a bogus "result" during the window before the coroutine reaches its next suspension
        // point and is torn down. This test proves that code-after-the-call does NOT run.
        val started = CompletableDeferred<Unit>()
        repo.publishFn = { _, _ ->
            started.complete(Unit)
            awaitCancellation() // suspend here until the caller's scope is cancelled
        }

        var ranAfterRequestReturned = false
        val job = async {
            repo.request("a_requested", "test_result", EmptyPayload, null, TestBody::class.java)
            // If request() swallows the cancellation and returns normally instead of
            // rethrowing, execution reaches here even though the job was cancelled.
            ranAfterRequestReturned = true
        }
        started.await()
        job.cancel()

        var threw: CancellationException? = null
        try {
            job.await()
        } catch (e: CancellationException) {
            threw = e
        }
        assertTrue("job.await() should have thrown CancellationException", threw != null)
        assertTrue(
            "code after request() must not run once the caller was cancelled mid-publish",
            !ranAfterRequestReturned
        )
    }

    @Test
    fun `client_upgrade_required latches the upgradeRequired flag`() = runTest {
        assertTrue(!repo.upgradeRequired.value)
        val call = async {
            repo.request("machine_cycle_start_requested", "machine_cycle_result",
                EmptyPayload, null, TestBody::class.java)
        }
        while (published.isEmpty()) yield()
        val id = messageIdOf(0)
        repo.handleIncomingResponse(
            "PPNAM/station_2/handheld_1/res/workflow_upgrade_required",
            """{"inResponseToMessageId":"$id","accepted":false,
                "errorCode":"client_upgrade_required",
                "nextAction":"upgrade_reader_for_mixing"}""".toByteArray()
        )
        val outcome = call.await() as MqttOutcome.Rejected
        assertEquals(ErrorCode.CLIENT_UPGRADE_REQUIRED, outcome.errorCode)
        assertTrue(repo.upgradeRequired.value)
    }

    @Test
    fun `an UNSOLICITED upgrade rejection still latches the flag`() = runTest {
        repo.handleIncomingResponse(
            "PPNAM/station_2/handheld_1/res/workflow_upgrade_required",
            """{"inResponseToMessageId":"nobody-is-waiting","accepted":false,
                "errorCode":"client_upgrade_required"}""".toByteArray()
        )
        assertTrue(repo.upgradeRequired.value)
    }

    @Test
    fun `cancelling a caller mid-publish does not leak the pending entry`() = runTest {
        val started = CompletableDeferred<Unit>()
        repo.publishFn = { _, _ ->
            started.complete(Unit)
            awaitCancellation()
        }

        val job = async {
            repo.request("a_requested", "test_result", EmptyPayload, null, TestBody::class.java)
        }
        started.await()
        job.cancel()
        try {
            job.await()
        } catch (_: CancellationException) {
            // expected; assertion below is what matters for this test
        }

        assertTrue("pending map should not leak the cancelled request's entry", pendingMap().isEmpty())
    }
}
