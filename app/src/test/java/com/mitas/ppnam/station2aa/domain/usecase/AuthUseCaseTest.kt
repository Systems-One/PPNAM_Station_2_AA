package com.mitas.ppnam.station2aa.domain.usecase

import com.mitas.ppnam.station2aa.data.auth.ScramExchange
import com.mitas.ppnam.station2aa.data.mqtt.ErrorCode
import com.mitas.ppnam.station2aa.data.mqtt.FailureKind
import com.mitas.ppnam.station2aa.data.mqtt.MqttOutcome
import com.mitas.ppnam.station2aa.data.mqtt.NextAction
import com.mitas.ppnam.station2aa.data.mqtt.dto.BadgeLoginPayload
import com.mitas.ppnam.station2aa.data.mqtt.dto.OperatorContextResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.ScramProofResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.ScramPurpose
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
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class AuthUseCaseTest {

    private lateinit var mqtt: MqttRepository
    private lateinit var scramExchange: ScramExchange
    private lateinit var sessionHolder: OperatorSessionHolder
    private lateinit var useCase: AuthUseCase

    /** What a successful SCRAM login proof returns. */
    private val provedLogin = ScramProofResponse(
        serverSignature = "verified-by-ScramExchange",
        operatorSessionId = "session-id",
        operatorId = "OP-001",
        username = "operator1",
        displayName = "Operator One",
        role = "Operator",
        allowedActions = listOf("scan_ingredient", "start_machine_cycle"),
        allowedTabs = listOf("collect", "mixing"),
    )

    private val acceptedBadge = OperatorContextResponse(
        operatorSessionId = "session-id",
        operatorId = "OP-001",
        username = "operator1",
        displayName = "Operator One",
        role = "Operator",
        allowedActions = listOf("scan_ingredient", "start_machine_cycle"),
        allowedTabs = listOf("collect", "mixing"),
    )

    @Before
    fun setup() {
        mqtt = mock()
        scramExchange = mock()
        sessionHolder = OperatorSessionHolder()
        useCase = AuthUseCase(mqtt, sessionHolder, scramExchange)
    }

    private suspend fun stubScram(result: Result<ScramProofResponse>) {
        whenever(scramExchange.authenticate(any(), any(), any(), any(), any())).thenReturn(result)
    }

    private suspend fun stubBadge(outcome: MqttOutcome<OperatorContextResponse>) {
        whenever(
            mqtt.request(any(), any(), any(), anyOrNull(), eq(OperatorContextResponse::class.java))
        ).thenReturn(outcome)
    }

    @Test
    fun `a credentials login runs a SCRAM exchange with the login purpose`() = runTest {
        stubScram(Result.success(provedLogin))

        useCase.login(LoginMethod.Credentials("operator1", "secret"))

        verify(scramExchange).authenticate(
            eq("operator1"),
            eq("secret"),
            eq(ScramPurpose.LOGIN),
            // Scope fields are empty for a login — they only bind a manager action's token.
            eq(""),
            eq(""),
        )
    }

    @Test
    fun `a credentials login never publishes the password on login_requested`() = runTest {
        // Schema 4.1 rejects ANY message containing a `password` property. The whole point of the
        // cutover is that this request no longer happens for a credentials login.
        stubScram(Result.success(provedLogin))

        useCase.login(LoginMethod.Credentials("operator1", "secret"))

        verify(mqtt, never()).request(eq("login_requested"), any(), any(), anyOrNull(), any<Class<Any>>())
    }

    @Test
    fun `badge login still uses the single login topic with a badge payload`() = runTest {
        // A badge carries no secret, so it survives the 4.1 cutover unchanged.
        stubBadge(MqttOutcome.Accepted(acceptedBadge, NextAction.NONE))

        useCase.login(LoginMethod.Badge("BADGE001"))

        verify(mqtt).request(
            eq("login_requested"),
            eq("operator_context"),
            argThat<Any> { this is BadgeLoginPayload && badgeTag == "BADGE001" },
            eq(null),
            eq(OperatorContextResponse::class.java),
        )
    }

    @Test
    fun `a successful login stores the session`() = runTest {
        stubScram(Result.success(provedLogin))

        val session = useCase.login(LoginMethod.Credentials("operator1", "secret")).getOrThrow()

        assertEquals("session-id", session.operatorSessionId)
        assertEquals("OP-001", session.operatorId)
        assertEquals("Operator One", session.operatorName)
        assertEquals("Operator", session.role)
        assertEquals(listOf("scan_ingredient", "start_machine_cycle"), session.allowedActions)
        assertEquals(listOf("collect", "mixing"), session.allowedTabs)
        assertEquals("session-id", sessionHolder.session.value?.operatorSessionId)
    }

    @Test
    fun `a badge login stores the session too`() = runTest {
        stubBadge(MqttOutcome.Accepted(acceptedBadge, NextAction.NONE))

        val session = useCase.login(LoginMethod.Badge("BADGE001")).getOrThrow()

        assertEquals("session-id", session.operatorSessionId)
        assertEquals("session-id", sessionHolder.session.value?.operatorSessionId)
    }

    @Test
    fun `a proved login with no session id is still a failure`() = runTest {
        stubScram(Result.success(provedLogin.copy(operatorSessionId = "")))

        val result = useCase.login(LoginMethod.Credentials("operator1", "secret"))

        assertTrue(result.isFailure)
        assertNull(sessionHolder.session.value)
    }

    @Test
    fun `a failed SCRAM exchange fails the login and stores no session`() = runTest {
        stubScram(Result.failure(Exception("Incorrect username or password.")))

        val result = useCase.login(LoginMethod.Credentials("operator1", "wrong"))

        assertTrue(result.isFailure)
        assertEquals("Incorrect username or password.", result.exceptionOrNull()?.message)
        assertNull(sessionHolder.session.value)
    }

    @Test
    fun `a rejected badge login fails with the operator-readable reason`() = runTest {
        stubBadge(
            MqttOutcome.Rejected(
                body = OperatorContextResponse(),
                errorCode = ErrorCode.PERMISSION_DENIED,
                reason = "Badge not recognised.",
                nextAction = NextAction.LOGIN,
            )
        )

        val result = useCase.login(LoginMethod.Badge("BADGE-BAD"))

        assertTrue(result.isFailure)
        assertEquals("Badge not recognised.", result.exceptionOrNull()?.message)
        assertNull(sessionHolder.session.value)
    }

    @Test
    fun `a timeout during badge login fails with a connection message`() = runTest {
        stubBadge(MqttOutcome.NoResponse(FailureKind.Timeout))

        val result = useCase.login(LoginMethod.Badge("BADGE001"))

        assertTrue(result.isFailure)
        assertEquals("Station 2 did not respond", result.exceptionOrNull()?.message)
    }

    @Test
    fun `being disconnected during badge login fails with a connection message`() = runTest {
        stubBadge(MqttOutcome.NoResponse(FailureKind.NotConnected))

        val result = useCase.login(LoginMethod.Badge("BADGE001"))

        assertTrue(result.isFailure)
        assertEquals("Not connected to Station 2", result.exceptionOrNull()?.message)
    }

    @Test
    fun `a successful login carries session state and expiry`() = runTest {
        stubScram(
            Result.success(
                provedLogin.copy(sessionState = "Active", sessionExpiresAtUtc = "2026-07-17T00:00:01Z")
            )
        )

        val session = useCase.login(LoginMethod.Credentials("operator1", "secret")).getOrThrow()

        assertEquals(SessionState.Active, session.sessionState)
        assertEquals(Instant.parse("2026-07-17T00:00:01Z"), session.sessionExpiresAtUtc)
    }

    @Test
    fun `a login answered with a Closed session is a failure`() = runTest {
        // Accepting a session Station 2 has already closed would strand the operator in a UI that
        // rejects every action.
        stubScram(Result.success(provedLogin.copy(sessionState = "Closed")))

        val result = useCase.login(LoginMethod.Credentials("operator1", "secret"))

        assertTrue(result.isFailure)
        assertNull(sessionHolder.session.value)
    }

    @Test
    fun `an unparseable expiry does not fail the login`() = runTest {
        stubScram(Result.success(provedLogin.copy(sessionExpiresAtUtc = "not-a-timestamp")))

        val session = useCase.login(LoginMethod.Credentials("operator1", "secret")).getOrThrow()

        assertNull(session.sessionExpiresAtUtc)
    }

    @Test
    fun `logout sends the envelope-only request and clears the session`() = runTest {
        sessionHolder.set(
            com.mitas.ppnam.station2aa.data.session.OperatorSession(
                operatorSessionId = "session-id",
                operatorId = "OP-001",
                operatorName = "Operator One",
                role = "Operator",
            )
        )
        stubBadge(MqttOutcome.Accepted(OperatorContextResponse(), NextAction.LOGIN))

        val result = useCase.logout()

        assertTrue(result.isSuccess)
        verify(mqtt).request(
            eq("reader_logout_requested"),
            eq("operator_context"),
            eq(com.mitas.ppnam.station2aa.data.mqtt.EmptyPayload),
            eq(null),
            eq(OperatorContextResponse::class.java),
        )
        assertNull(sessionHolder.session.value)
    }

    @Test
    fun `logout clears the local session even when Station 2 never answers`() = runTest {
        sessionHolder.set(
            com.mitas.ppnam.station2aa.data.session.OperatorSession(
                operatorSessionId = "session-id",
                operatorId = "OP-001",
                operatorName = "Operator One",
                role = "Operator",
            )
        )
        stubBadge(MqttOutcome.NoResponse(FailureKind.Timeout))

        val result = useCase.logout()

        // Leaving an operator stuck logged-in on the handheld because the network blipped would be
        // worse than a server-side session that expires on its own.
        assertTrue(result.isSuccess)
        assertNull(sessionHolder.session.value)
    }
}
