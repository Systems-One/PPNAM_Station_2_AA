package com.mitas.ppnam.station2aa.data.auth

import com.mitas.ppnam.station2aa.data.mqtt.ErrorCode
import com.mitas.ppnam.station2aa.data.mqtt.MqttOutcome
import com.mitas.ppnam.station2aa.data.mqtt.dto.ScramChallengeResponse
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** Backend reason codes become operator wording; raw protocol text never reaches the screen. */
class ScramExchangeMessagesTest {

    private suspend fun failingWith(code: ErrorCode, operatorMessage: String?): Result<*> {
        val mqtt = mock<MqttRepository>()
        whenever(mqtt.request(any(), any(), any(), eq(ScramChallengeResponse::class.java)))
            .thenReturn(MqttOutcome.Rejected(body = null, error = code, operatorMessage = operatorMessage))
        return ScramExchange(mqtt).authenticate("operator1", "pass")
    }

    @Test
    fun `a wrong password reads as incorrect username or password`() = runTest {
        val result = failingWith(ErrorCode.AUTHENTICATION_FAILED, "SCRAM proof rejected.")
        assertEquals("Incorrect username or password", result.exceptionOrNull()?.message)
    }

    @Test
    fun `a malformed envelope does not echo the protocol text`() = runTest {
        val result = failingWith(ErrorCode.INVALID_ENVELOPE, "username and clientNonce are required.")
        assertEquals(
            "Station 2 rejected the sign-in request as malformed. Update the app if this keeps happening.",
            result.exceptionOrNull()?.message,
        )
    }

    @Test
    fun `an unknown code still shows the station's own operator message`() = runTest {
        val result = failingWith(ErrorCode("some_future_code"), "Try again in a minute.")
        assertEquals("Try again in a minute.", result.exceptionOrNull()?.message)
    }
}
