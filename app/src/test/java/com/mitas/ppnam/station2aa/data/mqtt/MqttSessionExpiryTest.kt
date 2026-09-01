package com.mitas.ppnam.station2aa.data.mqtt

import com.mitas.ppnam.station2aa.data.identity.DeviceIdentity
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.data.settings.SettingsRepository
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class MqttSessionExpiryTest {

    private lateinit var repo: MqttRepositoryImpl
    private lateinit var sessionHolder: OperatorSessionHolder
    private val published = mutableListOf<Pair<String, ByteArray>>()

    @Before
    fun setup() {
        sessionHolder = OperatorSessionHolder()
        sessionHolder.set(
            OperatorSession(
                operatorSessionId = "session-abc",
                operatorId = "OP-001",
                operatorName = "Operator One",
                role = "Operator",
            )
        )
        val deviceIdentity = mock<DeviceIdentity>()
        whenever(deviceIdentity.deviceId()).thenReturn("scanner_5c64df8d86a8")
        repo = MqttRepositoryImpl(
            clientFactory = mock(),
            settingsRepository = mock<SettingsRepository>(),
            sessionHolder = sessionHolder,
            deviceIdentity = deviceIdentity,
        )
        published.clear()
        repo.publishFn = { topic, bytes -> published += topic to bytes }
        val field = MqttRepositoryImpl::class.java.getDeclaredField("_connectionState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(repo) as MutableStateFlow<MqttConnectionState>).value = MqttConnectionState.CONNECTED
    }

    private fun messageIdOf(index: Int): String =
        com.google.gson.JsonParser
            .parseString(String(published[index].second)).asJsonObject.get("messageId").asString

    private suspend fun CoroutineScope.requestAndRespond(errorCode: String?, operatorSessionId: String = "session-abc") {
        val call = async {
            repo.request("a_requested", "test_result", EmptyPayload, null, TestBody::class.java)
        }
        while (published.isEmpty()) yield()
        val id = messageIdOf(0)
        val codeJson = errorCode?.let { "\"$it\"" } ?: "null"
        repo.handleIncomingResponse(
            "PPNAM/station_2/handheld_1/res/test_result",
            """{"inResponseToMessageId":"$id","operatorSessionId":"$operatorSessionId","accepted":false,"errorCode":$codeJson,"reason":"x"}""".toByteArray()
        )
        call.await()
    }

    @Test
    fun `a session_required rejection for the current session clears it`() = runTest {
        assertNotNull(sessionHolder.session.value)

        requestAndRespond("session_required", operatorSessionId = "session-abc")

        assertNull("session_required for our own session must clear it", sessionHolder.session.value)
    }

    @Test
    fun `a session_required rejection for a DIFFERENT, already-superseded session leaves the current one intact`() = runTest {
        // Regression: a late reply to a request built under an OLD session (e.g. the operator
        // logged out and back in before the retried request's response finally arrived) rejects
        // with session_required for that old session — not the current one. Clearing the CURRENT
        // session on this would spuriously log the operator out mid-action even though Station 2
        // never said anything was wrong with their live session.
        assertNotNull(sessionHolder.session.value)

        requestAndRespond("session_required", operatorSessionId = "session-OLD-and-superseded")

        assertNotNull(
            "a rejection about a different session must not clear the current one",
            sessionHolder.session.value,
        )
    }

    @Test
    fun `a session_required rejection with no operatorSessionId leaves the current session intact`() = runTest {
        // Without a session id to match against, we can't confirm the rejection is about the
        // session we currently believe is active — safer to leave it alone than guess.
        val call = async {
            repo.request("a_requested", "test_result", EmptyPayload, null, TestBody::class.java)
        }
        while (published.isEmpty()) yield()
        repo.handleIncomingResponse(
            "PPNAM/station_2/handheld_1/res/test_result",
            """{"inResponseToMessageId":"${messageIdOf(0)}","accepted":false,"errorCode":"session_required","reason":"x"}""".toByteArray()
        )
        call.await()

        assertNotNull(sessionHolder.session.value)
    }

    @Test
    fun `an unrelated rejection leaves the session intact`() = runTest {
        // Only session_required means the session is gone. A validation failure must not log the
        // operator out.
        requestAndRespond("validation_failed")

        assertNotNull(sessionHolder.session.value)
    }

    @Test
    fun `a rejection with no error code leaves the session intact`() = runTest {
        requestAndRespond(null)

        assertNotNull(sessionHolder.session.value)
    }

    @Test
    fun `an accepted response leaves the session intact`() = runTest {
        val call = async {
            repo.request("a_requested", "test_result", EmptyPayload, null, TestBody::class.java)
        }
        while (published.isEmpty()) yield()
        repo.handleIncomingResponse(
            "PPNAM/station_2/handheld_1/res/test_result",
            """{"inResponseToMessageId":"${messageIdOf(0)}","accepted":true}""".toByteArray()
        )
        call.await()

        assertNotNull(sessionHolder.session.value)
    }
}
