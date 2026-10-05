package com.mitas.ppnam.station2aa.domain.usecase

import com.mitas.ppnam.station2aa.data.auth.ScramExchange
import com.mitas.ppnam.station2aa.data.mqtt.ErrorCode
import com.mitas.ppnam.station2aa.data.mqtt.FailureKind
import com.mitas.ppnam.station2aa.data.mqtt.MqttOutcome
import com.mitas.ppnam.station2aa.data.mqtt.dto.BadgeLoginPayload
import com.mitas.ppnam.station2aa.data.mqtt.dto.LoginResultResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2Session
import com.mitas.ppnam.station2aa.data.mqtt.dto.ScramProofResponse
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.domain.model.SessionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import kotlinx.coroutines.test.runTest
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

class AuthUseCaseTest {

    private lateinit var scramExchange: ScramExchange
    private lateinit var mqttRepository: MqttRepository
    private lateinit var sessionHolder: OperatorSessionHolder
    private lateinit var useCase: AuthUseCase

    /** What a successful `login_result` returns under `data`: a session for the card's holder. */
    private val badgeLogin = LoginResultResponse(
        session = Rev2Session(
            sessionId = "badge-session",
            operatorId = "0F5D6A2E-1D2B-4C3A-9E8F-0123456789AB",
            displayName = "Fleet Operator",
            role = "Worker",
            expiresAtUtc = "2026-10-03T18:00:00.000000Z",
            sessionState = "Active",
            isActive = true,
        ),
    )

    /** What a successful rev2.1 SCRAM proof returns under `data`. */
    private val provedLogin = ScramProofResponse(
        serverSignature = "verified-by-ScramExchange",
        session = Rev2Session(
            sessionId = "session-id",
            operatorId = "OP-001",
            displayName = "Operator One",
            role = "Worker",
            expiresAtUtc = "2026-09-30T18:00:00.000000Z",
            sessionState = "Active",
            isActive = true,
        ),
    )

    @Before
    fun setup() {
        scramExchange = mock()
        mqttRepository = mock()
        sessionHolder = OperatorSessionHolder()
        useCase = AuthUseCase(sessionHolder, scramExchange, mqttRepository)
    }

    private suspend fun stubScram(result: Result<ScramProofResponse>) {
        whenever(scramExchange.authenticate(any(), any())).thenReturn(result)
    }

    private suspend fun stubBadge(outcome: MqttOutcome<LoginResultResponse>) {
        whenever(mqttRepository.request(any(), any(), any(), eq(LoginResultResponse::class.java))).thenReturn(outcome)
    }

    @Test
    fun `a badge login sends the tag on login_requested and stores the holder's session`() = runTest {
        stubBadge(MqttOutcome.Accepted(badgeLogin))

        val session = useCase.loginWithBadge("E2000017221101441890ABCD").getOrThrow()

        verify(mqttRepository).request(
            eq("login_requested"),
            eq("login_result"),
            eq(BadgeLoginPayload("E2000017221101441890ABCD")),
            eq(LoginResultResponse::class.java),
        )
        assertEquals("badge-session", session.operatorSessionId)
        assertEquals("0F5D6A2E-1D2B-4C3A-9E8F-0123456789AB", session.operatorId)
        assertEquals("Fleet Operator", session.operatorName)
        assertEquals("Worker", session.role)
        assertEquals(SessionState.Active, session.sessionState)
        assertEquals(Instant.parse("2026-10-03T18:00:00.000000Z"), session.sessionExpiresAtUtc)
        assertEquals("badge-session", sessionHolder.session.value?.operatorSessionId)
    }

    @Test
    fun `a badge Station 2 does not recognise is reported in operator words and stores no session`() = runTest {
        stubBadge(MqttOutcome.Rejected(body = null, error = ErrorCode.BADGE_REJECTED, operatorMessage = "Badge is unknown or inactive."))

        val result = useCase.loginWithBadge("0000000000000000")

        assertTrue(result.isFailure)
        assertEquals(AuthUseCase.BADGE_REJECTED_MESSAGE, result.exceptionOrNull()?.message)
        assertNull(sessionHolder.session.value)
    }

    @Test
    fun `a badge login Station 2 never answers fails with the transport message`() = runTest {
        stubBadge(MqttOutcome.NoResponse(FailureKind.Timeout))

        val result = useCase.loginWithBadge("E2000017221101441890ABCD")

        assertTrue(result.isFailure)
        assertEquals("Station 2 did not respond. Check the station and retry.", result.exceptionOrNull()?.message)
        assertNull(sessionHolder.session.value)
    }

    @Test
    fun `a badge login answered with a closed or missing session is a failure`() = runTest {
        stubBadge(MqttOutcome.Accepted(badgeLogin.copy(session = badgeLogin.session!!.copy(sessionState = "Closed"))))
        assertTrue(useCase.loginWithBadge("E2000017221101441890ABCD").isFailure)

        stubBadge(MqttOutcome.Accepted(LoginResultResponse(session = null)))
        assertTrue(useCase.loginWithBadge("E2000017221101441890ABCD").isFailure)

        assertNull(sessionHolder.session.value)
    }

    @Test
    fun `a blank badge tag is refused without a request`() = runTest {
        val result = useCase.loginWithBadge("   ")

        assertTrue(result.isFailure)
        verifyNoInteractions(mqttRepository)
    }

    @Test
    fun `a login runs a SCRAM exchange with the operator's credentials`() = runTest {
        stubScram(Result.success(provedLogin))

        useCase.login("operator1", "secret")

        verify(scramExchange).authenticate("operator1", "secret")
    }

    @Test
    fun `a successful login stores the session`() = runTest {
        stubScram(Result.success(provedLogin))

        val session = useCase.login("operator1", "secret").getOrThrow()

        assertEquals("session-id", session.operatorSessionId)
        assertEquals("OP-001", session.operatorId)
        assertEquals("Operator One", session.operatorName)
        assertEquals("Worker", session.role)
        assertEquals("session-id", sessionHolder.session.value?.operatorSessionId)
    }

    @Test
    fun `a proved login with no session id is still a failure`() = runTest {
        stubScram(Result.success(provedLogin.copy(session = provedLogin.session!!.copy(sessionId = ""))))

        val result = useCase.login("operator1", "secret")

        assertTrue(result.isFailure)
        assertNull(sessionHolder.session.value)
    }

    @Test
    fun `a failed SCRAM exchange fails the login and stores no session`() = runTest {
        stubScram(Result.failure(Exception("Incorrect username or password.")))

        val result = useCase.login("operator1", "wrong")

        assertTrue(result.isFailure)
        assertEquals("Incorrect username or password.", result.exceptionOrNull()?.message)
        assertNull(sessionHolder.session.value)
    }

    @Test
    fun `a successful login carries session state and expiry`() = runTest {
        stubScram(
            Result.success(provedLogin)
        )

        val session = useCase.login("operator1", "secret").getOrThrow()

        assertEquals(SessionState.Active, session.sessionState)
        assertEquals(Instant.parse("2026-09-30T18:00:00.000000Z"), session.sessionExpiresAtUtc)
    }

    @Test
    fun `a login answered with a Closed session is a failure`() = runTest {
        // Accepting a session Station 2 has already closed would strand the operator in a UI that
        // rejects every action.
        stubScram(Result.success(provedLogin.copy(session = provedLogin.session!!.copy(sessionState = "Closed"))))

        val result = useCase.login("operator1", "secret")

        assertTrue(result.isFailure)
        assertNull(sessionHolder.session.value)
    }

    @Test
    fun `an unparseable expiry does not fail the login`() = runTest {
        stubScram(Result.success(provedLogin.copy(session = provedLogin.session!!.copy(expiresAtUtc = "not-a-date"))))

        val session = useCase.login("operator1", "secret").getOrThrow()

        assertNull(session.sessionExpiresAtUtc)
    }

    @Test
    fun `a proof with no session object fails the login`() = runTest {
        stubScram(Result.success(provedLogin.copy(session = null)))

        val result = useCase.login("operator1", "pass")

        assertTrue(result.isFailure)
        assertNull(sessionHolder.session.value)
    }

    @Test
    fun `a session Station 2 reports inactive fails the login`() = runTest {
        stubScram(Result.success(provedLogin.copy(session = provedLogin.session!!.copy(isActive = false))))

        val result = useCase.login("operator1", "pass")

        assertTrue(result.isFailure)
        assertNull(sessionHolder.session.value)
    }

    @Test
    fun `the stored session takes identity from data_session`() = runTest {
        stubScram(Result.success(provedLogin))

        val session = useCase.login("operator1", "pass").getOrThrow()

        assertEquals("session-id", session.operatorSessionId)
        assertEquals("OP-001", session.operatorId)
        assertEquals("Operator One", session.operatorName)
        assertEquals("Worker", session.role)
    }

    @Test
    fun `logout clears the local session and sends nothing`() = runTest {
        stubScram(Result.success(provedLogin))
        useCase.login("operator1", "pass")

        useCase.logout()

        assertNull(sessionHolder.session.value)
    }
}
